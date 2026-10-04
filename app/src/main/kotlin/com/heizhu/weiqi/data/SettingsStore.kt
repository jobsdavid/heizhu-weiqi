package com.heizhu.weiqi.data

import android.content.Context
import com.heizhu.weiqi.core.ai.Difficulty
import com.heizhu.weiqi.core.rules.Stone

/**
 * 用户偏好设置。
 *
 * 用 SharedPreferences 而非 DataStore：只有三个开关/档位，
 * 引 DataStore 的协程包装不划算。读取集中在构造时一次，写入即生效。
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("weiqi_settings", Context.MODE_PRIVATE)

    /** 音效总开关，默认开启（落子/吃子的声音反馈对小孩很重要） */
    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND, true)
        set(value) = prefs.edit().putBoolean(KEY_SOUND, value).apply()

    /**
     * 光标移动速度档位。对应 [CursorSpeed]。
     * 影响长按加速的触发时机与步长 —— 手小的孩子需要更慢的档位。
     */
    var cursorSpeed: Int
        get() = prefs.getInt(KEY_CURSOR_SPEED, CursorSpeed.NORMAL.ordinal)
        set(value) = prefs.edit().putInt(KEY_CURSOR_SPEED, value).apply()

    /** 落子时是否振动（电视遥控器部分支持） */
    var hapticEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC, false)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC, value).apply()

    /**
     * AI 每手**最长**思考时长（毫秒）。可选项见 [AI_THINK_OPTIONS]，默认最低档 15 秒。
     *
     * 为什么做成设置：这个值直接换取棋力（网络路径下每次前向都是一次真实的局面判断，
     * 算得越多越准），但"愿意等多久"因人而异 —— 孩子在旁边等不了太久，
     * 大人复盘时又希望它认真点。
     *
     * 语义（2026-10-04 真机实测后订正，旧注释是错的）：
     *
     * - 它**不是**「档位自带预算的上限」，而是**直接取代**档位预算 ——
     *   `MctsEngine.findBestMoveWithNet` 取的就是 `budgetMs = 本值`，
     *   档位自带的那几个预算（入门 300ms / 初级 600ms / 中级 1200ms / 高级 2000ms）
     *   在网络路径下**不参与计时**。
     * - 「想清楚就收工」只体现在**候选循环跑完就返回**；候选没跑完时，实际耗时由
     *   「候选数 × 单次前向」决定。真机实测（9 路）：入门 2.6 秒、初级 0.5 秒、
     *   中级 1.4 秒、高级 14.2 秒、大师 15.3 秒 —— 单次前向 32×2 约 38ms、
     *   64×4 约 236ms、64×6 约 356ms（13 路同架构再乘约 2.1）。
     * - 默认取**最大档 15 秒**是黑猪大人明确要的：宁可等，也要它想清楚
     *   （「我们的电脑思考时长默认应当是最大时长」）。所以低难度档也不会因为
     *   这个上限被迫变慢这句话**不再成立** —— 入门现在自己就要 2.6 秒。
     */
    var aiThinkMs: Long
        get() = prefs.getLong(KEY_AI_THINK_MS, AI_THINK_OPTIONS.first())
        set(value) = prefs.edit().putLong(KEY_AI_THINK_MS, value).apply()

    /**
     * 上次对局用的三项设置（棋盘 / 难度 / 执色）。
     *
     * 记住它们是为了让「新对局」页**开箱就是上次那套** —— 孩子通常会连续好几局
     * 都练同一档位，每局都重选一遍是纯粹的摩擦。焦点也直接落在「开始对局」上，
     * 于是「再来一局」按两下 OK 就能开。
     *
     * 首次运行的默认值是「9 路 · 入门 · 执黑」：孩子第一局要能赢下来，
     * 建立信心比「有挑战」重要得多。
     */
    var lastBoardSize: Int
        get() = prefs.getInt(KEY_BOARD_SIZE, 9)
        set(value) = prefs.edit().putInt(KEY_BOARD_SIZE, value).apply()

    /** 上次用的难度档位 id，对应 [Difficulty.id]。 */
    var lastDifficultyId: String
        get() = prefs.getString(KEY_DIFFICULTY, Difficulty.ENTRY.id) ?: Difficulty.ENTRY.id
        set(value) = prefs.edit().putString(KEY_DIFFICULTY, value).apply()

    /** 上次玩家执的颜色（[Stone.code]：1=黑 2=白）。 */
    var lastPlayerColorCode: Int
        get() = prefs.getInt(KEY_PLAYER_COLOR, Stone.BLACK.code.toInt())
        set(value) = prefs.edit().putInt(KEY_PLAYER_COLOR, value).apply()

    companion object {
        /** AI 思考时长可选项（毫秒）。默认取第一项 = 最低档。 */
        val AI_THINK_OPTIONS = listOf(15_000L, 20_000L, 25_000L, 30_000L)

        private const val KEY_AI_THINK_MS = "ai_think_ms"
        private const val KEY_SOUND = "sound_enabled"
        private const val KEY_CURSOR_SPEED = "cursor_speed"
        private const val KEY_HAPTIC = "haptic_enabled"
        private const val KEY_BOARD_SIZE = "last_board_size"
        private const val KEY_DIFFICULTY = "last_difficulty_id"
        private const val KEY_PLAYER_COLOR = "last_player_color_code"
    }
}

/**
 * 光标移动速度档位。
 *
 * 长按方向键后，光标会按 [accelIntervalMs] 的节奏以 [stepSize] 格为单位快速移动。
 * 慢速档给手小的孩子用 —— 他们更容易按过头。
 */
enum class CursorSpeed(
    val label: String,
    /** 长按多久后开始加速 */
    val accelDelayMs: Long,
    /** 加速后每隔多久移动一次 */
    val accelIntervalMs: Long,
    /** 加速后每次移动几格 */
    val stepSize: Int,
) {
    SLOW("慢", accelDelayMs = 600, accelIntervalMs = 160, stepSize = 1),
    NORMAL("中", accelDelayMs = 450, accelIntervalMs = 100, stepSize = 2),
    FAST("快", accelDelayMs = 300, accelIntervalMs = 70, stepSize = 3),
}
