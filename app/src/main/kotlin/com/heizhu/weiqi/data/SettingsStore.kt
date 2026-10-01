package com.heizhu.weiqi.data

import android.content.Context

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

    /** 上次选择的棋盘尺寸，用于「再来一局」时沿用 */
    var lastBoardSize: Int
        get() = prefs.getInt(KEY_BOARD_SIZE, 9)
        set(value) = prefs.edit().putInt(KEY_BOARD_SIZE, value).apply()

    companion object {
        private const val KEY_SOUND = "sound_enabled"
        private const val KEY_CURSOR_SPEED = "cursor_speed"
        private const val KEY_HAPTIC = "haptic_enabled"
        private const val KEY_BOARD_SIZE = "last_board_size"
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
