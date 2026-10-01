package com.heizhu.weiqi.core.rules

/**
 * 棋子颜色。
 *
 * [code] 的数值直接存进棋盘的 ByteArray，**不可随意改动**——
 * 全项目所有棋盘读写都依赖 0/1/2 这三个值。
 */
enum class Stone(val code: Byte) {
    EMPTY(0),
    BLACK(1),
    WHITE(2);

    /** 对方颜色。EMPTY 的对方仍是 EMPTY（调用方应先排除空点）。 */
    val opponent: Stone
        get() = when (this) {
            BLACK -> WHITE
            WHITE -> BLACK
            EMPTY -> EMPTY
        }

    /** 中文名，用于界面显示 */
    val displayName: String
        get() = when (this) {
            BLACK -> "黑"
            WHITE -> "白"
            EMPTY -> "空"
        }

    companion object {
        /** 由棋盘字节还原颜色。任何非法值一律视作空点，避免脏数据扩散。 */
        fun of(code: Byte): Stone = when (code.toInt()) {
            1 -> BLACK
            2 -> WHITE
            else -> EMPTY
        }
    }
}
