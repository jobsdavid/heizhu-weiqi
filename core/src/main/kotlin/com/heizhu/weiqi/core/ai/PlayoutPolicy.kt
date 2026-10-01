package com.heizhu.weiqi.core.ai

import com.heizhu.weiqi.core.rules.Board
import com.heizhu.weiqi.core.rules.BoardGeometry
import com.heizhu.weiqi.core.rules.Stone
import kotlin.random.Random

/**
 * 随机走子（playout）策略 —— MCTS 的评估质量几乎完全由它决定。
 *
 * ## 为什么不能纯随机
 * 纯随机走子的围棋 AI 会做一件让孩子崩溃的事：**填自己的眼**。
 * 那等于把自己的实地白送掉，对局会变得毫无道理。
 * 所以本策略的第一条硬规则是 **禁止填自己的眼**（见 [Board.isOwnEye]）。
 *
 * ## 性能设计
 * playout 每次落子都要选点，而 19 路有 300 多个空点。若每个空点都做一次
 * 完整评估（含 flood fill 算气），单次 playout 就会慢到不可用。
 * 因此这里采用**随机采样 + 加权择优**：只抽样 `sampleCount` 个候选点，
 * 在其中选权重最高者。采样点 65% 落在最后一手附近（局部战斗），
 * 35% 全局随机（照顾布局）。这样既快，又自然产生了「就近应战」的棋风。
 *
 * ⚠️ 非线程安全。每个 playout 线程持有独立实例。
 */
class PlayoutPolicy(private val size: Int) {

    private val cellCount = size * size
    private val neighborBuf = IntArray(4)

    /** 采样次数。棋盘越大，单次评估越贵，采样相应减少以控制总耗时。 */
    private val sampleCount: Int = if (cellCount > 200) 20 else 32

    /**
     * 为 [color] 选一步棋。
     *
     * @return 落子点的扁平索引；返回 **-1 表示无合理着法，应当停一手（pass）**。
     */
    fun selectMove(board: Board, color: Stone, rng: Random): Int {
        var bestIndex = -1
        var bestWeight = -1

        for (attempt in 0 until sampleCount) {
            val index = sampleIndex(board, rng)
            if (board.cells[index].toInt() != 0) continue          // 已有子
            if (board.isOwnEye(index, color)) continue             // 自己的眼，硬禁止

            val weight = heuristicScore(board, index, color)
            if (weight > bestWeight) {
                bestWeight = weight
                bestIndex = index
            }
        }

        return bestIndex
    }

    /**
     * 采样一个候选点。
     *
     * 65% 概率落在最后一手的 5×5 邻域内 —— 围棋的局部性很强，战斗几乎总是
     * 发生在最近落子的附近；剩下 35% 全局均匀随机，让 AI 有机会走出大场。
     */
    private fun sampleIndex(board: Board, rng: Random): Int {
        val last = board.lastMove
        if (!last.isPass && rng.nextFloat() < 0.65f) {
            val x = last.x + rng.nextInt(5) - 2
            val y = last.y + rng.nextInt(5) - 2
            if (x in 0 until size && y in 0 until size) {
                return y * size + x
            }
        }
        return rng.nextInt(cellCount)
    }

    /**
     * 廉价的着法评分 —— **不做 flood fill**，只数邻居。
     *
     * 这是刻意的取舍：精确判断「能否提子/是否被打吃」需要算气，成本高出一个
     * 数量级。在 playout 里用邻居结构做近似，足以产生「贴着对方下、连着自己下、
     * 少走一线」这些基本棋理。至于提子这类精确战术，交给 MCTS 的搜索去发现。
     *
     * 同样被 MCTS 用于**根节点候选的排序截断**（见 MctsEngine），
     * 两边共用一套评价标准，避免出现两处逻辑不一致。
     */
    fun heuristicScore(board: Board, index: Int, color: Stone): Int {
        val myCode = color.code
        val oppCode = color.opponent.code

        var friendly = 0
        var enemy = 0
        val n = BoardGeometry.neighbors(size, index, neighborBuf)
        for (k in 0 until n) {
            val code = board.cells[neighborBuf[k]]
            when {
                code == myCode -> friendly++
                code == oppCode -> enemy++
            }
        }

        // 贴着双方棋子的点价值最高；四周全空的点也不该被压成最低档。
        //
        // 原实现是 `if (friendly == 0 && enemy == 0) return 1` —— 它在**跳过一线惩罚之前**
        // 就返回了，于是空盘上 81 个点全部同分，开局第一手等于随机摸。
        // 实测结果：9 路空盘第一手落在 A7，也就是一线（见 AiDiagnosticTest）。
        var weight = 4 + friendly * 2 + enemy * 2

        weight += lineValue(index % size, index / size)

        return if (weight < 1) 1 else weight
    }

    /**
     * 「线」的位置价值 —— 围棋最基本的棋理之一：一线最次、二线偏低、
     * 三线四线最好、中腹偏空。
     *
     * 只惩罚一线是不够的。实测自战 30 手，AI 的子几乎全部沿着一线/二线排开：
     * 随机走子下「沿边爬」最省事，没有正分把它拉回三线四线。必须给三线/四线加分。
     */
    private fun lineValue(col: Int, row: Int): Int {
        val line = minOf(col, row, size - 1 - col, size - 1 - row) + 1
        return when (line) {
            1 -> -4
            2 -> -1
            3 -> 3
            4 -> 2
            else -> 0
        }
    }
}
