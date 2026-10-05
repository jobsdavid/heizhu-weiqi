package com.heizhu.weiqi.vm

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.heizhu.weiqi.audio.SoundPlayer
import com.heizhu.weiqi.audio.SoundPlayer.Sfx
import com.heizhu.weiqi.core.ai.Difficulty
import com.heizhu.weiqi.core.ai.MctsEngine
import com.heizhu.weiqi.core.game.EndReason
import com.heizhu.weiqi.core.game.GameResult
import com.heizhu.weiqi.core.game.GameState
import com.heizhu.weiqi.core.rules.PlayOutcome
import com.heizhu.weiqi.core.rules.Point
import com.heizhu.weiqi.core.rules.Stone
import com.heizhu.weiqi.data.CursorSpeed
import com.heizhu.weiqi.data.GameRecord
import com.heizhu.weiqi.data.RecordStore
import com.heizhu.weiqi.data.NetAssets
import com.heizhu.weiqi.data.SettingsStore
import com.heizhu.weiqi.data.encodeMoves
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

/** 应用内的页面。用简单的枚举导航，不引 navigation-compose —— 只有 6 个页面，不值得。 */
enum class Screen { HOME, SETUP, GAME, RESULT, HISTORY, SETTINGS }

/**
 * 对局界面的全部可渲染状态。
 *
 * 之所以做成不可变快照而不是让 Compose 直接读 [GameState]：GameState 内部是
 * 可变的 ByteArray，Compose 无法感知它的变化。每次操作后重发一份快照，
 * 重组才会正确触发。
 */
data class GameUi(
    val boardSize: Int = 9,
    val cells: ByteArray = ByteArray(81),
    val lastMoveX: Int = -1,
    val lastMoveY: Int = -1,
    val toMove: Stone = Stone.BLACK,
    val playerColor: Stone = Stone.BLACK,
    val difficulty: Difficulty = Difficulty.BEGINNER,
    val thinking: Boolean = false,
    val moveCount: Int = 0,
    val blackCaptured: Int = 0,
    val whiteCaptured: Int = 0,
    val undoRemaining: Int = GameState.DEFAULT_MAX_UNDO,
    val cursorX: Int = 4,
    val cursorY: Int = 4,
    /** 光标处落子能提几子；0 表示不提子 */
    val previewCapture: Int = 0,
    /** 光标处不能落子的原因，null 表示可落子 */
    val previewIllegal: String? = null,
    /** AI 建议的落点（提示功能），null 表示无提示 */
    val hintX: Int = -1,
    val hintY: Int = -1,
    val isOver: Boolean = false,
    val result: GameResult? = null,
    /** 一次性提示消息（如「悔棋次数已用完」），显示后由界面清除 */
    val toast: String? = null,
    /**
     * 最近一手被提掉的点（扁平索引），用于**提子特效**。
     *
     * 为什么要单独存：引擎那条路径只回传提子数（`MoveOutcome.Ok` 里没有点表），
     * 而「刷的一下就没了」孩子根本看不清 —— 实测会被误解成「电脑没吃我的子」。
     */
    val lastCapturedPoints: IntArray = IntArray(0),
    /** 被提的那一方的颜色（用于画幽灵子）。[lastCapturedPoints] 为空时无意义。 */
    val lastCapturedColor: Stone = Stone.EMPTY,
    /**
     * 玩家已经「无处可下」（一个合法着法都没有：空点全被占，或全是自杀/自己的眼）。
     *
     * 界面据此给出明确的收工指引 —— 不加这个提示，孩子会一直找不到落子点、
     * 又不知道可以停一手，对局就永远结束不了。
     */
    val playerHasNoLegalMove: Boolean = false,
    /**
     * 本局开始时刻（epoch ms）。
     *
     * 时长由**界面**每秒本地重算，不让 ViewModel 按秒推状态 ——
     * 后者会让整个棋盘（Canvas）每秒钟重组一次，为了几个数字不值当。
     */
    val startedAtMs: Long = 0,
    /** 当前这一手开始的时刻。界面据此算出「正在想的这一方已经用掉多少」。 */
    val turnStartedAtMs: Long = 0,
    /** AI 已累计的思考时长（不含正在进行中的这一手）。 */
    val bossThinkMs: Long = 0,
    /** 玩家已累计的思考时长。 */
    val playerThinkMs: Long = 0,
)

/**
 * 对局与战绩的中枢。
 *
 * 职责边界：本类负责**AI 调度、光标逻辑、存档**；围棋规则与回合管理在
 * core 模块的 [GameState]；AI 算法在 [MctsEngine]。这样拆的好处是规则层
 * 可以脱离 Android 单独测试（已测 27 个用例），而这里的异步/生命周期
 * 复杂性不会污染规则正确性。
 */
class GameViewModel(app: Application) : AndroidViewModel(app) {

    private val recordStore = RecordStore(File(app.filesDir, "records.json"))
    val settings = SettingsStore(app)

    /**
     * 音效播放器。开关状态实时读 [settings]，所以在设置页关掉立刻生效，
     * 不需要重启应用或重新开局。
     */
    private val soundPlayer = SoundPlayer(app) { settings.soundEnabled }

    private val _screen = MutableStateFlow(Screen.HOME)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _ui = MutableStateFlow(GameUi())
    val ui: StateFlow<GameUi> = _ui.asStateFlow()

    private val _history = MutableStateFlow<List<GameRecord>>(emptyList())
    val history: StateFlow<List<GameRecord>> = _history.asStateFlow()

    /** 当前对局。未开局时为 null。 */
    private var game: GameState? = null

    /** AI 引擎。与棋盘尺寸绑定，每次开新局重建。 */
    private var engine: MctsEngine? = null

    /** AI 思考任务。悔棋/退出时必须能取消，让孩子不用干等。 */
    private var aiJob: Job? = null

    private var hintJob: Job? = null

    /** 用固定种子的随机源，保证同一局面下 AI 表现可复现（便于排查问题） */
    private val searchRandom = Random(System.nanoTime())

    init {
        viewModelScope.launch { _history.value = recordStore.loadAll() }
    }

    // ===============================================================
    // 导航
    // ===============================================================

    fun navigateTo(screen: Screen) {
        if (screen != Screen.GAME) cancelThinking()
        _screen.value = screen
    }

    // ===============================================================
    // 开局
    // ===============================================================

    fun startNewGame(boardSize: Int, difficulty: Difficulty, playerColor: Stone) {
        cancelThinking()

        val state = GameState(
            size = boardSize,
            difficulty = difficulty,
            playerColor = playerColor,
            // 让子（handicap）：难度阶梯的**确定性**旋钮 —— 在星位给玩家摆 N 子、AI 先下、不贴目。
            // 为什么不用"弱网络"做阶梯：容量/数据量/搜索量三条轴实测都不单调（差异小于测量噪声）；
            // 而让子是把目差分布的**均值**平移一个确定量，信噪比完全不同。详见 Difficulty.handicap。
            handicap = difficulty.handicap,
        )
        game = state
        // 有蒸馏网络就用「网络引导」路径（9 路），没有就回退随机 rollout。
        // 回退是刻意的：网络文件缺失/解析失败都不能影响 app 可用性。
        // **按难度档取网**：阶梯是靠网络强弱实现的（弱网 = 入门，最强网 = 大师），
        // 传难度 id 才能拿到对应的那一个。
        engine = MctsEngine(
            boardSize,
            net = NetAssets.load(getApplication(), boardSize, difficulty.id),
            // 终局裁判统一用最强的「大师」档权重：**裁判要准，棋手可以弱**。
            // 低档网络的价值头太粗（见过它给出"对方走什么我的目差都不变"），
            // 拿它当裁判会让 AI 一路停手被吃光。详见 MctsEngine.judgeNet 的注释。
            judgeNet = NetAssets.load(getApplication(), boardSize, Difficulty.MASTER.id),
        )
        // 记住这一局用的三项设置，下次进「新对局」直接带出来（见 SettingsStore 注释）
        settings.lastBoardSize = boardSize
        settings.lastDifficultyId = difficulty.id
        settings.lastPlayerColorCode = playerColor.code.toInt()

        _ui.value = GameUi(
            boardSize = boardSize,
            cells = state.board.cells.copyOf(),
            toMove = state.toMove,
            playerColor = playerColor,
            difficulty = difficulty,
            undoRemaining = state.undoRemaining,
            // 光标从棋盘中央起步 —— 比从左上角(0,0)开始近得多
            cursorX = boardSize / 2,
            cursorY = boardSize / 2,
            startedAtMs = state.startedAtMs,
            turnStartedAtMs = state.turnStartedAtMs,
        )
        _screen.value = Screen.GAME

        // 玩家执白时 AI 先手
        launchAiIfNeeded()
    }

    /** 沿用上一局的设置再来一局。 */
    fun rematch() {
        val current = _ui.value
        startNewGame(current.boardSize, current.difficulty, current.playerColor)
    }

    // ===============================================================
    // 光标
    // ===============================================================

    fun moveCursor(dx: Int, dy: Int) {
        val current = _ui.value
        if (current.isOver) return
        val nx = (current.cursorX + dx).coerceIn(0, current.boardSize - 1)
        val ny = (current.cursorY + dy).coerceIn(0, current.boardSize - 1)
        if (nx == current.cursorX && ny == current.cursorY) return
        _ui.value = current.copy(cursorX = nx, cursorY = ny).withPreview(computePreview(nx, ny))
    }

    /** 光标瞬移到指定点（快捷跳跃用）。 */
    fun setCursor(x: Int, y: Int) {
        val current = _ui.value
        _ui.value = current.copy(
            cursorX = x.coerceIn(0, current.boardSize - 1),
            cursorY = y.coerceIn(0, current.boardSize - 1),
        ).withPreview(computePreview(x, y))
    }

    private fun GameUi.withPreview(preview: PreviewInfo): GameUi =
        copy(previewCapture = preview.captureCount, previewIllegal = preview.illegalReason)

    private class PreviewInfo(val captureCount: Int, val illegalReason: String?)

    private fun computePreview(x: Int, y: Int): PreviewInfo {
        val state = game ?: return PreviewInfo(0, null)
        if (!state.isPlayerTurn) return PreviewInfo(0, null)
        return when (val outcome = state.board.preview(x, y, state.playerColor)) {
            is PlayOutcome.Ok -> PreviewInfo(outcome.captureCount, null)
            PlayOutcome.Occupied -> PreviewInfo(0, "这里已经有子了")
            PlayOutcome.Suicide -> PreviewInfo(0, "这里落下就没有气了")
            PlayOutcome.Ko -> PreviewInfo(0, "打劫：不能马上提回来")
            PlayOutcome.OutOfBounds -> PreviewInfo(0, null)
        }
    }

    // ===============================================================
    // 落子
    // ===============================================================

    /** 确认键：在光标处落子。 */
    fun confirmAtCursor() {
        val state = game ?: return
        val current = _ui.value
        if (current.isOver) return

        if (!state.isPlayerTurn) {
            _ui.value = current.copy(toast = "等黑猪大人下完这一手")
            return
        }

        when (val outcome = state.play(current.cursorX, current.cursorY, state.playerColor)) {
            is com.heizhu.weiqi.core.game.MoveOutcome.Ok -> {
                playMoveSound(outcome.capturedCount, isPlayer = true)
                refreshFromGame()
                // refreshFromGame 里可能已经把对局结算掉（棋盘满了 / 双方都没地方下），
                // 所以这里必须先看 isOver，否则结果页永远不显示
                if (state.isOver) onGameFinished() else launchAiIfNeeded()
            }
            com.heizhu.weiqi.core.game.MoveOutcome.Occupied -> {
                soundPlayer.play(Sfx.ILLEGAL)
                _ui.value = current.copy(toast = "这里已经有子了")
            }
            com.heizhu.weiqi.core.game.MoveOutcome.Suicide -> {
                soundPlayer.play(Sfx.ILLEGAL)
                _ui.value = current.copy(toast = "这里落下就没有气了")
            }
            com.heizhu.weiqi.core.game.MoveOutcome.Ko -> {
                soundPlayer.play(Sfx.ILLEGAL)
                _ui.value = current.copy(toast = "打劫：不能马上提回来")
            }
            else -> Unit
        }
    }

    /** 停一手。 */
    fun pass() {
        val state = game ?: return
        if (!state.isPlayerTurn) return
        state.passMove(state.playerColor)
        refreshFromGame()
        if (state.isOver) {
            onGameFinished()
        } else {
            // 停一手必须说一声：不然界面上只是「手数 +1、没有任何子落下」，
            // 孩子完全不知道刚才发生了什么
            _ui.value = _ui.value.copy(toast = "你停了一手")
            launchAiIfNeeded()
        }
    }

    /** 认输。 */
    fun resign() {
        val state = game ?: return
        if (state.isOver) return
        cancelThinking()
        state.resign()
        refreshFromGame()
        onGameFinished()
    }

    // ===============================================================
    // 悔棋
    // ===============================================================

    /**
     * 悔棋：撤销玩家一手 + AI 一手，只消耗 1 次配额。
     *
     * 刻意**不做确认弹窗** —— 孩子下错一步棋的挫败感是劝退主因。
     * 但用满配额后会明确提示，不静默失效。
     */
    fun undo() {
        val state = game ?: return
        if (state.isOver) {
            // 已经终局了还想悔棋：退回棋盘继续下
            _ui.value = _ui.value.copy(toast = "这局已经结束了")
            return
        }

        // 先取消 AI 思考，否则它会把撤销掉的局面又走回去
        cancelThinking()

        if (state.undoRemaining <= 0) {
            soundPlayer.play(Sfx.ILLEGAL)
            _ui.value = _ui.value.copy(toast = "本局悔棋次数已用完（每局 5 次）")
            return
        }

        if (!state.undo()) {
            soundPlayer.play(Sfx.ILLEGAL)
            _ui.value = _ui.value.copy(toast = "还没有可以撤销的棋")
            return
        }

        soundPlayer.play(Sfx.UNDO)
        refreshFromGame()
        _ui.value = _ui.value.copy(
            toast = "已撤销 · 还剩 ${state.undoRemaining} 次",
            hintX = -1,
            hintY = -1,
        )
    }

    // ===============================================================
    // 提示
    // ===============================================================

    /** 让 AI 在当前难度下给一个建议落点，闪烁显示。 */
    fun requestHint() {
        val state = game ?: return
        val engineRef = engine ?: return
        if (!state.isPlayerTurn) return
        if (hintJob?.isActive == true) return

        hintJob = viewModelScope.launch {
            _ui.value = _ui.value.copy(thinking = true)
            val move = withContext(Dispatchers.Default) {
                engineRef.findBestMove(state.board, state.playerColor, state.difficulty, searchRandom,
                        timeBudgetOverrideMs = minOf(state.difficulty.timeBudgetMs, settings.aiThinkMs))
            }
            if (move >= 0) {
                val x = move % state.size
                val y = move / state.size
                _ui.value = _ui.value.copy(thinking = false, hintX = x, hintY = y)
            } else {
                _ui.value = _ui.value.copy(thinking = false, toast = "黑猪大人觉得这里可以停一手")
            }
        }
    }

    fun clearHint() {
        _ui.value = _ui.value.copy(hintX = -1, hintY = -1)
    }

    // ===============================================================
    // AI 调度
    // ===============================================================

    private fun launchAiIfNeeded() {
        val state = game ?: return
        val engineRef = engine ?: return
        if (!state.isAiTurn) return

        aiJob?.cancel()
        aiJob = viewModelScope.launch {
            _ui.value = _ui.value.copy(thinking = true)
            try {
                val move = withContext(Dispatchers.Default) {
                    engineRef.findBestMove(state.board, state.aiColor, state.difficulty, searchRandom,
                        timeBudgetOverrideMs = minOf(state.difficulty.timeBudgetMs, settings.aiThinkMs))
                }
                logSearchStats(engineRef, state)
                val aiPassed = move < 0
                val aiOutcome = state.play(move, state.aiColor)
                if (aiOutcome is com.heizhu.weiqi.core.game.MoveOutcome.Ok) {
                    if (!aiPassed) playMoveSound(aiOutcome.capturedCount, isPlayer = false)
                } else {
                    // 兜底：引擎的候选都过了合法性校验，正常情况下这里不会走到。
                    // 但**一旦走到，对局会彻底卡死** —— 没有别的地方会再次触发 AI 落子，
                    // 界面就一直停在「轮到黑猪大人」。所以宁可改判停一手，保证能继续推进。
                    state.passMove(state.aiColor)
                }
                refreshFromGame()
                if (aiPassed && !state.isOver) {
                    // 对方停一手时给一句明确的话 —— 否则孩子只会看到「没落子」，
                    // 不知道轮到自己、也不知道可以跟着停手收工
                    _ui.value = _ui.value.copy(
                        toast = "黑猪大人停了一手 —— 你也长按返回键选「停一手」就能收工数子",
                    )
                }
                if (state.isOver) onGameFinished()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // 被悔棋/退出取消：不改变棋局，仅恢复界面状态再向上抛。
                // 注意 catch 参数不能写成 `_` —— Kotlin 的 `_` 是保留名，无法在体内引用。
                _ui.value = _ui.value.copy(thinking = false)
                throw cancelled
            }
        }
    }

    private fun cancelThinking() {
        aiJob?.cancel()
        aiJob = null
        hintJob?.cancel()
        hintJob = null
        if (_ui.value.thinking) {
            _ui.value = _ui.value.copy(thinking = false)
        }
    }

    // ===============================================================
    // 状态同步
    // ===============================================================

    private fun refreshFromGame() {
        val state = game ?: return
        val previous = _ui.value
        val last = state.board.lastMove

        // 提子特效要知道「刚才哪些子被提了、是什么颜色」。
        val captured = diffCaptured(previous.cells, state.board.cells)
        val capturedIdx = captured.first
        val capturedCode = captured.second

        if (capturedIdx.isNotEmpty()) {
            // 埋点：提子数据链路的可观测性。真机上用 `adb logcat -s WeiqiCapture` 看，
            // 用来确认「确实提子了、特效数据也传下去了」——不埋点就只能靠肉眼猜。
            Log.d(
                "WeiqiCapture",
                "提子 ${capturedIdx.size} 个，颜色码=$capturedCode，" +
                    "位置=${capturedIdx.joinToString(",")}",
            )
        }

        // 双方都落不下子 —— 对局实际上已经结束，直接数子定胜负。
        //
        // 围棋的正规做法是「双方各停一手」，但孩子不一定知道要去菜单里停一手；
        // 而「两个人谁也放不下子」本身就是终局的铁证，自动结算不会误判。
        if (!state.isOver &&
            !hasAnyLegalMoveFor(state, Stone.BLACK) &&
            !hasAnyLegalMoveFor(state, Stone.WHITE)
        ) {
            state.finishNow()
        }

        _ui.value = previous.copy(
            cells = state.board.cells.copyOf(),
            playerHasNoLegalMove = !state.isOver && !hasAnyLegalMoveFor(state, state.playerColor),
            lastCapturedPoints = capturedIdx,
            lastCapturedColor = when (capturedCode) {
                1 -> Stone.BLACK
                2 -> Stone.WHITE
                else -> Stone.EMPTY
            },
            lastMoveX = last.x,
            lastMoveY = last.y,
            toMove = state.toMove,
            thinking = false,
            moveCount = state.moveCount,
            blackCaptured = state.board.blackCaptured,
            whiteCaptured = state.board.whiteCaptured,
            undoRemaining = state.undoRemaining,
            startedAtMs = state.startedAtMs,
            turnStartedAtMs = state.turnStartedAtMs,
            bossThinkMs = state.thinkMs(state.aiColor),
            playerThinkMs = state.thinkMs(state.playerColor),
            isOver = state.isOver,
            result = state.result,
            hintX = -1,
            hintY = -1,
        ).withPreview(computePreview(previous.cursorX, previous.cursorY))
    }

    /**
     * 玩家手上还有没有任何一个**合法**着法。
     *
     * 注意这是「合法」而不是「有用」——空点全被占了、或者剩下的全是自杀点 /
     * 自己的眼，都属于无处可下。棋盘快满时这种情况很常见，此时棋局就该收工了。
     */
    private fun hasAnyLegalMoveFor(
        state: com.heizhu.weiqi.core.game.GameState,
        color: Stone,
    ): Boolean {
        val size = state.size
        val cells = state.board.cells
        for (i in cells.indices) {
            if (cells[i].toInt() != 0) continue
            val out = state.board.preview(i % size, i / size, color)
            if (out is com.heizhu.weiqi.core.rules.PlayOutcome.Ok) return true
        }
        return false
    }

    /**
     * 前后对盘，求出这一手被提掉的点与被提一方的颜色码。
     *
     * 为什么要这么算：引擎那条路径只回传**提子数**（`MoveOutcome.Ok` 里没有点表），
     * 而提子特效需要知道具体哪些点，才能画出「被吃」的反馈。
     * 13 路也才 169 字节，代价可忽略。
     *
     * 注意：悔棋时是「子重新出现」而不是消失，所以返回空 —— 不会误触发特效。
     *
     * @return (被提点的扁平索引, 被提一方的颜色码 1=黑 2=白；无提子时点为 0)
     */
    internal fun diffCaptured(before: ByteArray, after: ByteArray): Pair<IntArray, Int> {
        if (before.size != after.size) return IntArray(0) to 0
        val idx = ArrayList<Int>(8)
        var code = 0
        for (i in after.indices) {
            if (before[i].toInt() != 0 && after[i].toInt() == 0) {
                idx.add(i)
                code = before[i].toInt()
            }
        }
        return idx.toIntArray() to code
    }

    private fun onGameFinished() {
        val state = game ?: return
        val result = state.result ?: return
        when (result.playerWon) {
            true -> soundPlayer.play(Sfx.WIN)
            false -> soundPlayer.play(Sfx.LOSE)
            null -> Unit          // 和棋不播，避免误导
        }
        _screen.value = Screen.RESULT
        viewModelScope.launch {
            val record = buildRecord(state, result)
            _history.value = recordStore.append(record)
        }
    }

    private fun buildRecord(state: GameState, result: GameResult): GameRecord {
        val playerIsBlack = state.playerColor == Stone.BLACK
        return GameRecord(
            id = System.currentTimeMillis(),
            playedAt = System.currentTimeMillis(),
            boardSize = state.size,
            difficultyId = state.difficulty.id,
            playerColorCode = state.playerColor.code.toInt(),
            playerWon = when (result.playerWon) {
                true -> 1
                false -> -1
                null -> 0
            },
            blackTotal = result.score.blackTotal,
            whiteTotal = result.score.whiteTotal,
            blackMargin = result.score.blackMargin,
            playerCaptures = if (playerIsBlack) state.board.blackCaptured else state.board.whiteCaptured,
            aiCaptures = if (playerIsBlack) state.board.whiteCaptured else state.board.blackCaptured,
            moveCount = result.moveCount,
            undoCount = result.undoCount,
            durationMs = result.durationMs,
            endReason = result.reason.name,
            moves = encodeMoves(state.moves.map { it.index }),
        )
    }

    // ===============================================================
    // 其他
    // ===============================================================

    /**
     * 把本次搜索的性能数据打到 logcat（tag = WeiqiBench）。
     *
     * 存在的唯一理由：**目标设备的真实算力只能实测**。开发机是 24 核桌面 CPU，
     * 远远强于电视的四核 Cortex-A73，任何「估算」都可能差好几倍。
     * 在电视上连 adb 抓这个 tag，就能拿到真实的 playout 速率，
     * 用来校准各难度档的时间预算。
     *
     *   adb logcat -s WeiqiBench
     */
    private fun logSearchStats(engineRef: MctsEngine, state: GameState) {
        val stats = engineRef.lastStats ?: return
        Log.i(
            "WeiqiBench",
            "board=${state.size} difficulty=${state.difficulty.id} " +
                "playouts=${stats.playouts} elapsedMs=${stats.elapsedMs} " +
                "rate=%.0f/s candidates=${stats.candidateCount}"
                    .format(stats.playoutsPerSecond),
        )
    }

    /**
     * 播放落子音效。
     *
     * 提子时会在落子声之上叠一层「哗」——叠音比替换成单一音效更能传达
     *「这一手同时发生了两件事」。对手的音量压到 0.7，孩子能从听感上分辨敌我。
     */
    private fun playMoveSound(capturedCount: Int, isPlayer: Boolean) {
        soundPlayer.play(
            if (isPlayer) Sfx.STONE_PLACE else Sfx.STONE_AI,
            if (isPlayer) 1f else 0.7f,
        )
        if (capturedCount > 0) {
            soundPlayer.play(Sfx.CAPTURE, 0.9f)
        }
    }

    fun consumeToast() {
        if (_ui.value.toast != null) _ui.value = _ui.value.copy(toast = null)
    }

    fun clearHistory() {
        viewModelScope.launch {
            recordStore.clear()
            _history.value = emptyList()
        }
    }

    override fun onCleared() {
        super.onCleared()
        cancelThinking()
        soundPlayer.release()
    }
}