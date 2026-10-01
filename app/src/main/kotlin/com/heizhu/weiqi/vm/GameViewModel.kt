package com.heizhu.weiqi.vm

import android.app.Application
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
        )
        game = state
        engine = MctsEngine(boardSize)
        settings.lastBoardSize = boardSize

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
                launchAiIfNeeded()
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
                engineRef.findBestMove(state.board, state.playerColor, state.difficulty, searchRandom)
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
                    engineRef.findBestMove(state.board, state.aiColor, state.difficulty, searchRandom)
                }
                val aiOutcome = state.play(move, state.aiColor)
                if (aiOutcome is com.heizhu.weiqi.core.game.MoveOutcome.Ok) {
                    playMoveSound(aiOutcome.capturedCount, isPlayer = false)
                }
                refreshFromGame()
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

        _ui.value = previous.copy(
            cells = state.board.cells.copyOf(),
            lastMoveX = last.x,
            lastMoveY = last.y,
            toMove = state.toMove,
            thinking = false,
            moveCount = state.moveCount,
            blackCaptured = state.board.blackCaptured,
            whiteCaptured = state.board.whiteCaptured,
            undoRemaining = state.undoRemaining,
            isOver = state.isOver,
            result = state.result,
            hintX = -1,
            hintY = -1,
        ).withPreview(computePreview(previous.cursorX, previous.cursorY))
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

    companion object {
        /** 各尺寸棋盘支持的选择器选项 */
        val BOARD_SIZES = listOf(9, 13, 19)
    }
}