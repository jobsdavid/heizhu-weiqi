package com.heizhu.weiqi.core.rules

/**
 * 棋盘几何运算：坐标 ↔ 扁平索引、四邻域查询。
 *
 * 所有索引运算集中在这里，不要散落到各处——散落的 `y * size + x` 是
 * 越界 bug 和「行列颠倒」bug 的主要来源。
 *
 * [neighbors] 采用「写入调用方提供的数组」的写法而非返回 List：
 * MCTS 每秒会调用它上万次，每次分配一个 List 会带来明显的 GC 压力。
 */
object BoardGeometry {

    /** (x, y) → 扁平索引 */
    fun index(size: Int, x: Int, y: Int): Int = y * size + x

    /** 扁平索引 → 列 */
    fun x(size: Int, index: Int): Int = index % size

    /** 扁平索引 → 行 */
    fun y(size: Int, index: Int): Int = index / size

    /**
     * 把 [index] 的四邻域（上下左右）写入 [out]，返回实际邻居个数（2～4 个）。
     *
     * [out] 长度必须 ≥ 4。边界上的点邻居更少，返回的个数是真实值。
     */
    fun neighbors(size: Int, index: Int, out: IntArray): Int {
        val col = index % size
        val row = index / size
        var n = 0
        if (row > 0) out[n++] = index - size          // 上
        if (row < size - 1) out[n++] = index + size   // 下
        if (col > 0) out[n++] = index - 1             // 左
        if (col < size - 1) out[n++] = index + 1      // 右
        return n
    }

    // ---------------------------------------------------------------
    // 星位：用于让子摆放和界面上的「快捷跳跃」标记。
    // 13 路取 3/6/9（中心）5 个；
    // 9 路取 2/4/6 的 5 个交点（无天元以外的星）。
    // ---------------------------------------------------------------

    /** 该棋盘尺寸下的星位（内部坐标）。 */
    fun starPoints(size: Int): List<Point> = when (size) {
        9 -> listOf(
            Point(2, 2), Point(6, 2), Point(4, 4), Point(2, 6), Point(6, 6),
        )
        13 -> listOf(
            Point(3, 3), Point(9, 3), Point(6, 6), Point(3, 9), Point(9, 9),
        )
        19 -> listOf(
            Point(3, 3), Point(9, 3), Point(15, 3),
            Point(3, 9), Point(9, 9), Point(15, 9),
            Point(3, 15), Point(9, 15), Point(15, 15),
        )
        else -> emptyList()
    }
}
