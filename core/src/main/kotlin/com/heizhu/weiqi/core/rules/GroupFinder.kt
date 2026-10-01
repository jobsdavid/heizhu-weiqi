package com.heizhu.weiqi.core.rules

/**
 * 棋块（同色连通块）与「气」的计算器。
 *
 * 三个设计要点：
 *
 * 1. **迭代式 flood fill，绝不用递归**。
 *    19 路最长单色链可达 180 子，递归深度足够在某些设备上触发 StackOverflow。
 *
 * 2. **用递增 stamp 代替每次清零 visited 数组**。
 *    清零是 O(点数)，而 playout 每秒要做上万次，累加起来很可观。
 *
 * 3. **缓冲区全部复用**，热路径零分配。
 *    [groupBuffer] / [libertyBuffer] 在方法返回后仍持有数据，但
 *    **下次调用本类任何方法后即失效**——调用方必须立即消费，不要持有引用。
 *
 * ⚠️ 本类**不是线程安全的**。每个线程（每个 MCTS playout 线程）必须持有独立实例。
 */
class GroupFinder(private val size: Int) {

    private val cellCount = size * size

    /** 棋块访问标记。值为「本次遍历的编号」，靠编号递增来区分新旧，因此无需清零。 */
    private val groupStamp = IntArray(cellCount)
    private var groupCounter = 0

    /** 气的去重标记，原理同上 */
    private val libertyStamp = IntArray(cellCount)
    private var libertyCounter = 0

    /** flood fill 的显式栈 */
    private val stack = IntArray(cellCount)

    /** 复用的四邻域缓冲 */
    private val neighborBuf = IntArray(4)

    /** 结果缓冲：同色连通块包含的所有索引 */
    val groupBuffer = IntArray(cellCount)

    /** 结果缓冲：该连通块所有「气」的索引（已去重） */
    val libertyBuffer = IntArray(cellCount)

    /**
     * 找出包含 [index] 的同色连通块，结果写入 [groupBuffer]，返回棋子个数。
     * 返回 0 表示该点是空点。
     */
    fun findGroup(cells: ByteArray, index: Int): Int {
        val color = cells[index]
        if (color.toInt() == 0) return 0

        // 极端长时间运行后编号会回绕，此处重置以免新旧标记撞车
        if (groupCounter == Int.MAX_VALUE) {
            groupStamp.fill(0)
            groupCounter = 0
        }
        groupCounter++
        val stamp = groupCounter

        var top = 0
        var count = 1
        groupStamp[index] = stamp
        stack[top++] = index
        groupBuffer[0] = index

        while (top > 0) {
            val current = stack[--top]
            val n = BoardGeometry.neighbors(size, current, neighborBuf)
            for (k in 0 until n) {
                val nb = neighborBuf[k]
                if (groupStamp[nb] != stamp && cells[nb] == color) {
                    groupStamp[nb] = stamp
                    groupBuffer[count++] = nb
                    stack[top++] = nb
                }
            }
        }
        return count
    }

    /**
     * 计算 [index] 所在连通块的**气数**，并把所有气的位置写入 [libertyBuffer]。
     * 返回 0 表示该点为空，或该块已无气（应当被提）。
     */
    fun findLiberties(cells: ByteArray, index: Int): Int {
        val groupSize = findGroup(cells, index)
        if (groupSize == 0) return 0

        if (libertyCounter == Int.MAX_VALUE) {
            libertyStamp.fill(0)
            libertyCounter = 0
        }
        libertyCounter++
        val stamp = libertyCounter

        var libertyCount = 0
        for (g in 0 until groupSize) {
            val stone = groupBuffer[g]
            val n = BoardGeometry.neighbors(size, stone, neighborBuf)
            for (k in 0 until n) {
                val nb = neighborBuf[k]
                if (cells[nb].toInt() == 0 && libertyStamp[nb] != stamp) {
                    libertyStamp[nb] = stamp
                    libertyBuffer[libertyCount++] = nb
                }
            }
        }
        return libertyCount
    }

    /** 该块是否被打吃（恰好只剩 1 口气）。 */
    fun isInAtari(cells: ByteArray, index: Int): Boolean =
        findLiberties(cells, index) == 1
}
