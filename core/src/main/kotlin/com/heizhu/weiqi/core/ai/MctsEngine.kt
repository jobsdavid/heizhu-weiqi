package com.heizhu.weiqi.core.ai

import com.heizhu.weiqi.core.rules.Board
import com.heizhu.weiqi.core.rules.Komi
import com.heizhu.weiqi.core.rules.PlayOutcome
import com.heizhu.weiqi.core.rules.Scorer
import com.heizhu.weiqi.core.rules.Stone
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 蒙特卡洛树搜索（MCTS / UCT）围棋引擎。
 *
 * ## 为什么自己实现而不用 KataGo / GNU Go
 * - KataGo 依赖神经网络推理，目标设备的 GPU 是 Mali-G57 **MC1**（单核）且无 NPU，
 *   单步推理就是秒级，跑不动。
 * - GNU Go 棋力固定，**做不出五档细腻分级** —— 而「AI 要能被孩子打败」正是本应用的核心需求。
 * - 自研 MCTS 可以精确控制「AI 有多弱」，这才是练习工具真正需要的能力。
 *
 * ## 搜索流程（标准四步）
 * 1. **Selection** 从根节点沿 UCB 最优路径下行
 * 2. **Expansion** 展开一个未试着法
 * 3. **Simulation** 随机走子到终局，数子判定胜者
 * 4. **Backpropagation** 回传胜负
 *
 * 每个节点保存的 `wins` 是**以该节点轮到走棋的一方为视角**的胜场数
 * （negamax 风格），因此 Selection 在每一层都是取 UCB 最大值。
 *
 * ## 线程与取消
 * ⚠️ 非线程安全。每个线程持有独立实例。
 * [findBestMove] 是 suspend 函数，内部周期性检查协程取消 ——
 * 这样孩子按「悔棋」时能立刻中断 AI 的思考，而不是干等几秒。
 *
 * @param size 棋盘边长（9 / 13 / 19）
 * @param komi 贴目，默认取 [Komi.forSize]
 */
class MctsEngine(
    private val size: Int,
    private val komi: Double = Komi.forSize(size),
) {

    private val cellCount = size * size
    private val policy = PlayoutPolicy(size)
    private val scorer = Scorer(size)

    /** 复用的模拟棋盘，每个 playout 开头用 copyFrom 重置，避免反复分配 */
    private val simBoard = Board(size)

    /**
     * 单次 playout 的手数上限。
     *
     * 设为棋盘总点数：随机走子最终会把棋盘填满，用这个值当兜底即可。
     * 真正的耗时控制靠 [Difficulty.timeBudgetMs] 的硬时间预算。
     */
    private val maxPlayoutMoves = cellCount

    /** 深层每个节点最多展开多少个子节点，防止树无限变宽 */
    private val maxChildrenPerNode = 16

    /**
     * 上一次搜索的统计。用于**实测目标设备上的算力**——
     * 电视 CPU 比开发机弱得多，「每秒能跑多少次 playout」直接决定
     * 各难度档的棋力上限，必须能观测、能调参，不能靠猜。
     */
    var lastStats: SearchStats? = null
        private set

    /**
     * 搜索当前局面的最佳着法。
     *
     * @return 落子点的扁平索引；**-1 表示应当停一手（pass）**。
     */
    suspend fun findBestMove(
        board: Board,
        color: Stone,
        difficulty: Difficulty,
        rng: Random = Random.Default,
    ): Int {
        val startedAt = System.currentTimeMillis()
        val rootCandidates = generateRootCandidates(board, color, difficulty)
        if (rootCandidates.isEmpty()) {
            lastStats = SearchStats(0, System.currentTimeMillis() - startedAt, 0)
            return PASS_MOVE
        }

        val root = Node(move = PASS_MOVE, parent = null, toMove = color)
        root.candidates = rootCandidates

        val deadline = System.currentTimeMillis() + difficulty.timeBudgetMs
        var playouts = 0
        var countdownToCancelCheck = CANCEL_CHECK_INTERVAL

        while (playouts < difficulty.maxPlayouts) {
            // 时间预算优先：宁可少搜，也不能让孩子等
            if (System.currentTimeMillis() >= deadline) break

            if (--countdownToCancelCheck <= 0) {
                countdownToCancelCheck = CANCEL_CHECK_INTERVAL
                currentCoroutineContext().ensureActive()
            }

            simBoard.copyFrom(board)
            var node = root

            // ---------- 1. Selection ----------
            while (!node.hasUntried && node.children.isNotEmpty()) {
                node = selectChild(node)
                applyMove(simBoard, node.move, node.toMove)
            }

            // ---------- 2. Expansion ----------
            val child = expand(node, rng)
            if (child != null) node = child

            // ---------- 3. Simulation ----------
            val winner = simulate(simBoard, node.toMove, rng)

            // ---------- 4. Backpropagation ----------
            var current: Node? = node
            while (current != null) {
                current.visits++
                // wins 以「该节点轮到走棋的一方」为视角，故此处比较 toMove
                if (current.toMove == winner) current.wins += 1.0
                current = current.parent
            }

            playouts++
        }

        lastStats = SearchStats(
            playouts = playouts,
            elapsedMs = System.currentTimeMillis() - startedAt,
            candidateCount = rootCandidates.size,
        )
        return selectFinalMove(root, difficulty, rng)
    }

    /**
     * 一次搜索的统计快照。
     *
     * @param playouts      实际完成的模拟局数
     * @param elapsedMs     总耗时（毫秒），含候选生成与最终选择
     * @param candidateCount 根节点候选着法数
     */
    data class SearchStats(
        val playouts: Int,
        val elapsedMs: Long,
        val candidateCount: Int,
    ) {
        /** 每秒模拟局数 —— 目标设备算力的直接指标 */
        val playoutsPerSecond: Double
            get() = if (elapsedMs <= 0L) 0.0 else playouts * 1000.0 / elapsedMs
    }

    // ===============================================================
    // 候选生成
    // ===============================================================

    /**
     * 根节点候选：**完整扫描**所有合法着法，按启发式分数降序排列。
     *
     * 根节点只扫描一次，值得做得准 —— 候选的质量直接决定整棵树的上限。
     * 深层节点则改用 [policy] 采样生成（见 [expand]），因为那里每扩展一个节点
     * 都要重建候选，成本必须压下来。
     */
    private fun generateRootCandidates(
        board: Board,
        color: Stone,
        difficulty: Difficulty,
    ): IntArray {
        val restricted = collectCandidates(board, color, difficulty.localRadius)
        if (restricted.isNotEmpty()) return restricted

        // 视野限制可能把候选清空 —— 最典型的就是**开局空盘**：
        // 棋盘上一个子都没有，「棋子附近」无从谈起，于是候选为空。
        // 若不放宽限制，低难度档会在空盘上直接停一手，对局根本开不了局。
        return collectCandidates(board, color, radius = 0)
    }

    /**
     * 收集所有合法候选着法，按启发式分数降序排列。
     *
     * @param radius > 0 时只保留该半径内有棋子的点（低难度档的「视野限制」）
     */
    private fun collectCandidates(
        board: Board,
        color: Stone,
        radius: Int,
    ): IntArray {
        val scored = ArrayList<ScoredMove>(cellCount)
        for (index in 0 until cellCount) {
            if (board.cells[index].toInt() != 0) continue
            // 填自己的眼等于自毁，直接排除
            if (board.isOwnEye(index, color)) continue
            // 低难度档的「视野限制」：只看得见棋子附近
            if (radius > 0 && !isNearAnyStone(board, index, radius)) continue
            // 真正的合法性校验（自杀 / 打劫）
            if (board.preview(index % size, index / size, color) !is PlayOutcome.Ok) continue

            scored.add(ScoredMove(index, policy.heuristicScore(board, index, color)))
        }

        scored.sortByDescending { it.score }
        return IntArray(scored.size) { scored[it].index }
    }

    private class ScoredMove(val index: Int, val score: Int)

    /** [index] 周围 [radius] 格内是否有任何棋子。 */
    private fun isNearAnyStone(board: Board, index: Int, radius: Int): Boolean {
        val centerX = index % size
        val centerY = index / size
        val x0 = maxOf(0, centerX - radius)
        val x1 = minOf(size - 1, centerX + radius)
        val y0 = maxOf(0, centerY - radius)
        val y1 = minOf(size - 1, centerY + radius)
        for (y in y0..y1) {
            for (x in x0..x1) {
                if (board.cells[y * size + x].toInt() != 0) return true
            }
        }
        return false
    }

    // ===============================================================
    // 树的三个操作
    // ===============================================================

    /**
     * 展开一个子节点。
     *
     * - 若 [node] 还持有未试过的候选（根节点，以及由根候选直接产生的第一层），
     *   就按随机次序取出一个。
     * - 否则用走子策略现场提一个着法（深层节点），并去重。
     */
    private fun expand(node: Node, rng: Random): Node? {
        if (node.exhausted) return null

        var move = PASS_MOVE
        if (node.hasUntried) {
            move = node.pickUntried(rng)
        } else if (node.children.size < maxChildrenPerNode) {
            move = policy.selectMove(simBoard, node.toMove, rng)
            if (move < 0) {
                node.exhausted = true
                return null
            }
            if (node.hasChild(move)) {
                // 策略给出了一个已有的着法：这一轮不再展开
                return null
            }
        } else {
            return null
        }

        if (!applyMove(simBoard, move, node.toMove)) {
            // 候选在深层局面下已不合法（局面已变），跳过本轮展开
            return null
        }

        val child = Node(move = move, parent = node, toMove = node.toMove.opponent)
        node.children.add(child)
        return child
    }

    /**
     * UCB1 选择。每个节点的 wins 都是「该节点走棋方视角」，因此各层统一取最大值。
     *
     * `UCB = 胜率 + C × √(ln(父访问数) / 本节点访问数)`
     */
    private fun selectChild(node: Node): Node {
        val children = node.children
        val logParentVisits = ln(node.visits.toDouble().coerceAtLeast(1.0))

        var best = children[0]
        var bestScore = Double.NEGATIVE_INFINITY
        for (child in children) {
            val score = if (child.visits == 0) {
                // 没访问过的着法要优先试一次（否则它的胜率无从谈起）
                Double.POSITIVE_INFINITY
            } else {
                child.wins / child.visits +
                    EXPLORATION_CONSTANT * sqrt(logParentVisits / child.visits)
            }
            if (score > bestScore) {
                bestScore = score
                best = child
            }
        }
        return best
    }

    /**
     * 随机走子到终局，返回胜方。
     *
     * 连续两次 pass，或达到手数上限，或时间预算耗尽，即进入数子评估。
     */
    private fun simulate(board: Board, firstToMove: Stone, rng: Random): Stone {
        var toMove = firstToMove
        var consecutivePasses = 0
        var moves = 0

        while (moves < maxPlayoutMoves && consecutivePasses < 2) {
            val move = policy.selectMove(board, toMove, rng)
            if (move < 0) {
                board.pass()
                consecutivePasses++
            } else {
                val outcome = board.play(move % size, move / size, toMove)
                if (outcome is PlayOutcome.Ok) {
                    consecutivePasses = 0
                } else {
                    // 策略给出了非法着法（理论上不该发生），按停一手处理，避免死循环
                    board.pass()
                    consecutivePasses++
                }
            }
            toMove = toMove.opponent
            moves++
        }

        return scorer.score(board.cells, komi).winner
    }

    // ===============================================================
    // 最终着法选择 —— 难度弱化在这里生效
    // ===============================================================

    /**
     * 从搜索结果的访问分布中挑出最终着法。
     *
     * 次序上先应用 [Difficulty.blunderRate]（概率性挑次优，模拟「看漏了」），
     * 再应用 [Difficulty.temperature]（按访问分布采样，温度越高越随机）。
     * 大师档两者都为 0，等价于取访问次数最多的着法 —— 标准 MCTS 行为。
     */
    private fun selectFinalMove(root: Node, difficulty: Difficulty, rng: Random): Int {
        val children = root.children
        if (children.isEmpty()) return PASS_MOVE

        val ranked = children.sortedByDescending { it.visits }
        val best = ranked[0]

        // ---- 失误率：偶尔挑一个「还不错但非最优」的着法 ----
        if (difficulty.blunderRate > 0f && ranked.size > 1 &&
            rng.nextFloat() < difficulty.blunderRate
        ) {
            val threshold = best.visits / 4
            val pool = ranked.drop(1).filter { it.visits >= threshold }
            if (pool.isNotEmpty()) {
                return pool[rng.nextInt(pool.size)].move
            }
        }

        // ---- 温度采样 ----
        if (difficulty.temperature <= 0f) return best.move

        val exponent = 1.0 / difficulty.temperature.toDouble()
        var total = 0.0
        val weights = DoubleArray(ranked.size)
        for (i in ranked.indices) {
            val v = ranked[i].visits.toDouble()
            weights[i] = if (v <= 0.0) 0.0 else v.pow(exponent)
            total += weights[i]
        }
        if (total <= 0.0) return best.move

        var pick = rng.nextDouble() * total
        for (i in ranked.indices) {
            pick -= weights[i]
            if (pick <= 0.0) return ranked[i].move
        }
        return best.move
    }

    // ===============================================================

    private fun applyMove(board: Board, move: Int, color: Stone): Boolean {
        if (move < 0) return false
        return board.play(move % size, move / size, color) is PlayOutcome.Ok
    }

    /** MCTS 搜索树的节点。 */
    private class Node(
        /** 到达本节点的着法（扁平索引）。-1 用于根节点。 */
        val move: Int,
        val parent: Node?,
        /** 本节点局面下轮到走棋的一方 */
        val toMove: Stone,
    ) {
        var visits: Int = 0
        var wins: Double = 0.0

        val children: MutableList<Node> = ArrayList(8)

        /** 仅根节点持有：完整扫描得到的候选着法，按启发式降序 */
        var candidates: IntArray? = null
        private var candidateCursor: Int = 0

        /** 已确认无着法可下 */
        var exhausted: Boolean = false

        val hasUntried: Boolean
            get() = candidates?.let { candidateCursor < it.size } ?: false

        /**
         * 随机取出一个未试过的候选着法。
         *
         * 用「与游标位置随机交换」来实现，避免 `removeAt(0)` 的数组搬移开销
         * —— 19 路根节点可能有 300 个候选，反复搬移会明显拖慢搜索。
         */
        fun pickUntried(rng: Random): Int {
            val array = candidates!!
            val i = candidateCursor
            val j = i + rng.nextInt(array.size - i)
            val tmp = array[i]
            array[i] = array[j]
            array[j] = tmp
            candidateCursor++
            return array[i]
        }

        fun hasChild(move: Int): Boolean {
            for (child in children) if (child.move == move) return true
            return false
        }
    }

    companion object {
        /** 表示停一手（pass）的着法值 */
        const val PASS_MOVE = -1

        /** UCB 探索常数，√2 是经典取值 */
        private const val EXPLORATION_CONSTANT = 1.41421356

        /** 每多少次 playout 检查一次协程取消。太频繁会有额外开销。 */
        private const val CANCEL_CHECK_INTERVAL = 64
    }
}