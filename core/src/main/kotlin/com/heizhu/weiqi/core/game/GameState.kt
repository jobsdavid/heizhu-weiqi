package com.heizhu.weiqi.core.game

import com.heizhu.weiqi.core.ai.Difficulty
import com.heizhu.weiqi.core.rules.Board
import com.heizhu.weiqi.core.rules.Komi
import com.heizhu.weiqi.core.rules.PlayOutcome
import com.heizhu.weiqi.core.rules.Point
import com.heizhu.weiqi.core.rules.Score
import com.heizhu.weiqi.core.rules.Scorer
import com.heizhu.weiqi.core.rules.Stone

/** 一手棋的记录。用于棋谱存储与复盘。 */
data class Move(
    /** 落子点的扁平索引；-1 表示停一手（pass） */
    val index: Int,
    val color: Stone,
    /** 本手提掉的子数 */
    val capturedCount: Int,
) {
    val isPass: Boolean get() = index < 0
}

/** 终局原因。 */
enum class EndReason {
    /** 双方停一手，数子定胜负 */
    SCORED,

    /** 玩家认输 */
    RESIGN,
}

/** 一局的结果。 */
data class GameResult(
    val winner: Stone,
    /** 玩家是否获胜。和棋时为 null。 */
    val playerWon: Boolean?,
    val score: Score,
    val moveCount: Int,
    val undoCount: Int,
    val durationMs: Long,
    val reason: EndReason,
)

/** 落子的处理结果，供界面区分提示文案。 */
sealed interface MoveOutcome {
    data class Ok(val capturedCount: Int) : MoveOutcome
    data object NotYourTurn : MoveOutcome
    data object GameAlreadyOver : MoveOutcome
    data object Occupied : MoveOutcome
    data object Suicide : MoveOutcome
    data object Ko : MoveOutcome
    data object OutOfBounds : MoveOutcome
}

/**
 * 对局状态机 —— 管规则、管回合、管悔棋，**不管 AI 计算**。
 *
 * AI 的着法由外部（ViewModel）算好后通过 [play] 传入。这样切分的好处是
 * 状态机是纯逻辑、可同步测试的，而 AI 的异步/可取消特性不会污染规则层。
 *
 * ## 悔棋规则
 * 一次 [undo] 撤销**玩家一手 + 随之而来的 AI 一手**（共 2 步），但只消耗
 * 1 次配额。每局限 [maxUndoCount] 次 —— 既给孩子容错空间，又不让「悔棋」
 * 退化成无限重下的外挂。
 */
class GameState(
    val size: Int,
    val difficulty: Difficulty,
    val playerColor: Stone,
    val maxUndoCount: Int = DEFAULT_MAX_UNDO,
) {

    val board: Board = Board(size)
    private val scorer = Scorer(size)
    val komi: Double = Komi.forSize(size)

    /** 棋谱 */
    private val _moves = ArrayList<Move>(size * size / 2)
    val moves: List<Move> get() = _moves

    /** 轮到谁走 */
    var toMove: Stone = Stone.BLACK
        private set

    var isOver: Boolean = false
        private set

    var result: GameResult? = null
        private set

    /** 已使用的悔棋次数 */
    var undoCount: Int = 0
        private set

    val undoRemaining: Int get() = maxUndoCount - undoCount

    val moveCount: Int get() = _moves.size

    /** AI 的执子颜色 */
    val aiColor: Stone get() = playerColor.opponent

    /** 是否轮到玩家 */
    val isPlayerTurn: Boolean get() = !isOver && toMove == playerColor

    /** 是否轮到 AI */
    val isAiTurn: Boolean get() = !isOver && toMove == aiColor

    private var consecutivePasses = 0
    private var startedAt: Long = System.currentTimeMillis()

    /** 悔棋快照栈。每个元素是「走这一步之前」的完整局面。 */
    private val history = ArrayList<Snapshot>()

    private class Snapshot(
        val cells: ByteArray,
        val koPoint: Int,
        val lastMove: Point,
        val blackCaptured: Int,
        val whiteCaptured: Int,
        val toMove: Stone,
        val consecutivePasses: Int,
    )

    /** 重新开始一局（保留尺寸/难度/执子设置）。 */
    fun restart() {
        board.clear()
        _moves.clear()
        history.clear()
        toMove = Stone.BLACK
        isOver = false
        result = null
        undoCount = 0
        consecutivePasses = 0
        startedAt = System.currentTimeMillis()
    }

    // ===============================================================
    // 落子
    // ===============================================================

    /**
     * 当前轮到的一方落子。
     *
     * 调用方需自行保证 [color] 与 [toMove] 一致（[MoveOutcome.NotYourTurn] 会兜住）。
     */
    fun play(x: Int, y: Int, color: Stone): MoveOutcome {
        if (isOver) return MoveOutcome.GameAlreadyOver
        if (color != toMove) return MoveOutcome.NotYourTurn

        val outcome = board.play(x, y, color)
        return when (outcome) {
            is PlayOutcome.Ok -> {
                commitMove(
                    index = y * size + x,
                    color = color,
                    capturedCount = outcome.captureCount,
                )
                MoveOutcome.Ok(outcome.captureCount)
            }
            PlayOutcome.Occupied -> MoveOutcome.Occupied
            PlayOutcome.Suicide -> MoveOutcome.Suicide
            PlayOutcome.Ko -> MoveOutcome.Ko
            PlayOutcome.OutOfBounds -> MoveOutcome.OutOfBounds
        }
    }

    /** 当前轮到的一方按扁平索引落子。AI 走这条路径。 */
    fun play(index: Int, color: Stone): MoveOutcome {
        if (index < 0) return passMove(color)
        return play(index % size, index / size, color)
    }

    /** 当前轮到的一方停一手。 */
    fun passMove(color: Stone): MoveOutcome {
        if (isOver) return MoveOutcome.GameAlreadyOver
        if (color != toMove) return MoveOutcome.NotYourTurn

        pushSnapshot()
        board.pass()
        _moves.add(Move(index = PASS_INDEX, color = color, capturedCount = 0))
        consecutivePasses++

        if (consecutivePasses >= 2) {
            finishByScoring()
        } else {
            toMove = toMove.opponent
        }
        return MoveOutcome.Ok(0)
    }

    private fun commitMove(index: Int, color: Stone, capturedCount: Int) {
        // 快照必须在落子**之前**取，否则悔棋回不到原局面
        pushSnapshot()
        _moves.add(Move(index = index, color = color, capturedCount = capturedCount))
        consecutivePasses = 0
        toMove = toMove.opponent
    }

    private fun pushSnapshot() {
        history.add(
            Snapshot(
                cells = board.cells.copyOf(),
                koPoint = board.koPoint,
                lastMove = board.lastMove,
                blackCaptured = board.blackCaptured,
                whiteCaptured = board.whiteCaptured,
                toMove = toMove,
                consecutivePasses = consecutivePasses,
            )
        )
    }

    private fun restore(snapshot: Snapshot) {
        board.restoreFrom(
            source = snapshot.cells,
            koPoint = snapshot.koPoint,
            lastMove = snapshot.lastMove,
            blackCaptured = snapshot.blackCaptured,
            whiteCaptured = snapshot.whiteCaptured,
        )
        toMove = snapshot.toMove
        consecutivePasses = snapshot.consecutivePasses
    }

    // ===============================================================
    // 悔棋
    // ===============================================================

    /**
     * 悔棋：回退到「玩家上一次落子之前」。
     *
     * 一次调用同时撤掉玩家那一手和 AI 的应手，让玩家重新下 —— 这才是孩子
     * 按下返回键时真正想要的效果。整个动作只消耗 1 次配额。
     *
     * @return 是否成功。配额用尽、无子可悔、或对局已结束时返回 false。
     */
    fun undo(): Boolean {
        if (isOver) return false
        if (undoCount >= maxUndoCount) return false
        if (history.isEmpty()) return false

        // 至少要能退到玩家自己的上一手之前
        var stepsToUndo = 0
        var found = false
        for (i in _moves.indices.reversed()) {
            stepsToUndo++
            if (_moves[i].color == playerColor) {
                found = true
                break
            }
        }
        if (!found) return false

        repeat(stepsToUndo) {
            if (history.isEmpty()) return@repeat
            restore(history.removeAt(history.size - 1))
            _moves.removeAt(_moves.size - 1)
        }

        undoCount++
        return true
    }

    // ===============================================================
    // 结束
    // ===============================================================

    /** 玩家认输。 */
    fun resign(): GameResult {
        val score = scorer.score(board.cells, komi)
        val finished = GameResult(
            winner = aiColor,
            playerWon = false,
            score = score,
            moveCount = _moves.size,
            undoCount = undoCount,
            durationMs = System.currentTimeMillis() - startedAt,
            reason = EndReason.RESIGN,
        )
        isOver = true
        result = finished
        return finished
    }

    private fun finishByScoring() {
        val score = scorer.score(board.cells, komi)
        val winner = if (score.winner == Stone.EMPTY) Stone.EMPTY else score.winner
        val finished = GameResult(
            winner = winner,
            playerWon = when (winner) {
                Stone.EMPTY -> null
                else -> winner == playerColor
            },
            score = score,
            moveCount = _moves.size,
            undoCount = undoCount,
            durationMs = System.currentTimeMillis() - startedAt,
            reason = EndReason.SCORED,
        )
        isOver = true
        result = finished
    }

    /** 手动结束并结算（用于测试与「双方停手」的显式触发）。 */
    fun finishNow(): GameResult {
        if (isOver) return result!!
        finishByScoring()
        return result!!
    }

    /** 当前局面的比分快照（不改变对局状态）。 */
    fun currentScore(): Score = scorer.score(board.cells, komi)

    companion object {
        /** 表示停一手的着法索引 */
        const val PASS_INDEX = -1

        /** 每局默认悔棋次数上限（黑猪大人 2026-10-01 决定） */
        const val DEFAULT_MAX_UNDO = 5
    }
}
