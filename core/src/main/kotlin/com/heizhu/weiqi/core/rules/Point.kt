package com.heizhu.weiqi.core.rules

/**
 * 棋盘坐标点。x = 列（0..size-1，从左往右），y = 行（0..size-1，从上往下）。
 *
 * 注意：这是**内部坐标**，与围棋界通用的「Q16」式坐标不同。
 * 界面显示时会转换成围棋习惯坐标（见 [toGoNotation]）。
 */
data class Point(val x: Int, val y: Int) {

    /** 是否为「停一手」(pass) 的哨兵值 */
    val isPass: Boolean
        get() = x < 0 || y < 0

    /** 转为扁平索引。调用方需保证坐标合法。 */
    fun toIndex(size: Int): Int = y * size + x

    /**
     * 转为围棋习惯坐标（如 19 路的 D4）。
     *
     * 列用字母 A-T（跳过 I，这是围棋界为了区别于数字 1 的传统）。
     * 行号从下往上数，最下一线为 1。棋盘尺寸不同时行号起点也不同
     * （9 路最大行号为 9）。
     */
    fun toGoNotation(size: Int): String {
        if (isPass) return "停一手"
        // 跳过 I：A B C D E F G H J K L M N O P Q R S T
        val letters = "ABCDEFGHJKLMNOPQRST"
        val col = letters.getOrElse(x) { '?' }
        val row = size - y   // y=0 是最上行，对应最大行号
        return "$col$row"
    }

    companion object {
        /** 表示「停一手」(pass) 的哨兵点 */
        val PASS = Point(-1, -1)

        /** 由扁平索引还原坐标 */
        fun fromIndex(size: Int, index: Int): Point =
            Point(index % size, index / size)
    }
}
