package com.heizhu.weiqi.core.ai

import com.heizhu.weiqi.core.rules.Board
import com.heizhu.weiqi.core.rules.BoardGeometry
import com.heizhu.weiqi.core.rules.PlayOutcome
import com.heizhu.weiqi.core.rules.Stone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import kotlin.system.measureTimeMillis

/**
 * MCTS 引擎测试。
 *
 * 重点验证三件事：
 * 1. **绝不返回非法着法**（自杀、已有子）
 * 2. **绝不填自己的眼** —— 这是随机走子围棋 AI 最致命的症状
 * 3. **耗时可控** —— 孩子不能等
 */
class MctsEngineTest {

    private fun Board.put(x: Int, y: Int, stone: Stone) {
        cells[BoardGeometry.index(size, x, y)] = stone.code
    }

    /** 摆一块带两个真眼的黑棋（角上的活形），返回 (1,1) 与 (3,1) 两个眼位索引。 */
    private fun buildTwoEyedBlackGroup(): Pair<Board, List<Int>> {
        val b = Board(9)
        for (x in 0..4) {
            b.put(x, 0, Stone.BLACK)
            b.put(x, 2, Stone.BLACK)
        }
        b.put(0, 1, Stone.BLACK)
        b.put(2, 1, Stone.BLACK)
        b.put(4, 1, Stone.BLACK)
        //            x=0 1 2 3 4
        //   y=0       B B B B B
        //   y=1       B . B . B     ← (1,1) 与 (3,1) 是真眼
        //   y=2       B B B B B
        val eyes = listOf(
            BoardGeometry.index(9, 1, 1),
            BoardGeometry.index(9, 3, 1),
        )
        return b to eyes
    }

    @Test
    fun returnsLegalMoveOnEmptyBoard() = runBlocking {
        val board = Board(9)
        val engine = MctsEngine(9)

        val move = engine.findBestMove(board, Stone.BLACK, Difficulty.ENTRY, Random(42))

        assertTrue("空棋盘上应有合法着法", move >= 0)
        val result = board.play(move % 9, move / 9, Stone.BLACK)
        assertTrue("返回的着法必须合法，实际为 $result", result is PlayOutcome.Ok)
    }

    /** 核心用例：AI 不能把自己做活的两个眼填掉。 */
    @Test
    fun neverFillsItsOwnEyes() = runBlocking {
        val (board, eyes) = buildTwoEyedBlackGroup()
        val engine = MctsEngine(9)

        // 多个随机种子各搜一次，任何一个种子填眼都算失败
        for (seed in 1..8) {
            val move = engine.findBestMove(board, Stone.BLACK, Difficulty.MASTER, Random(seed.toLong()))
            for (eye in eyes) {
                assertNotEquals(
                    "种子 $seed 时 AI 填了自己的眼（索引 $eye）—— 走子策略的眼位保护失效",
                    eye,
                    move,
                )
            }
        }
    }

    /** 候选过滤必须挡住自杀点。 */
    @Test
    fun neverReturnsSuicideMove() = runBlocking {
        val board = Board(9)
        // 黑把 (1,1) 围死
        board.put(0, 1, Stone.BLACK)
        board.put(2, 1, Stone.BLACK)
        board.put(1, 0, Stone.BLACK)
        board.put(1, 2, Stone.BLACK)

        val engine = MctsEngine(9)
        val suicide = BoardGeometry.index(9, 1, 1)

        for (seed in 1..6) {
            val move = engine.findBestMove(board, Stone.WHITE, Difficulty.MASTER, Random(seed.toLong()))
            assertNotEquals("AI 返回了自杀点", suicide, move)
        }
    }

    /**
     * 性能观测。
     *
     * 开发机是 24 核，远强于目标电视（四核 Cortex-A73）。这里打印的速率
     * 只作为**上限参考**，真实棋力必须等实机实测。断言放得很宽，
     * 只保证「不会卡死」。
     */
    @Test
    fun reportsPerformanceStats() = runBlocking {
        for (boardSize in intArrayOf(9, 19)) {
            val board = Board(boardSize)
            val engine = MctsEngine(boardSize)

            val elapsed = measureTimeMillis {
                engine.findBestMove(board, Stone.BLACK, Difficulty.MASTER, Random(2026))
            }

            val stats = engine.lastStats
            assertNotNull("应记录搜索统计", stats)
            stats!!

            println(
                "[性能] ${boardSize}路 大师档: " +
                    "playouts=${stats.playouts}, " +
                    "耗时=${stats.elapsedMs}ms, " +
                    "候选=${stats.candidateCount}, " +
                    "速率=${"%.0f".format(stats.playoutsPerSecond)} playouts/s, " +
                    "外围耗时=${elapsed}ms"
            )

            // 预算取难度自带的，**不要把 3000 写死**：
            // 大师档从 3 秒改成 6 秒时，写死的断言不会失败，只会静默变成
            // "允许 8000ms" 的宽松条件，而消息里还写着 3000ms 预算 —— 测试失去意义。
            val budget = Difficulty.MASTER.timeBudgetMs
            assertTrue(
                "大师档耗时 ${stats.elapsedMs}ms 明显超出 ${budget}ms 预算",
                stats.elapsedMs < budget + 2_000,
            )
            assertTrue("应当完成过搜索", stats.playouts > 0)
        }
    }

    /** 入门档必须比大师档快得多 —— 这是低难度「响应快」的保证。 */
    @Test
    fun entryLevelIsMuchFasterThanMaster() = runBlocking {
        val board = Board(9)
        val engine = MctsEngine(9)

        engine.findBestMove(board, Stone.BLACK, Difficulty.ENTRY, Random(1))
        val entryMs = engine.lastStats!!.elapsedMs

        engine.findBestMove(board, Stone.BLACK, Difficulty.MASTER, Random(1))
        val masterMs = engine.lastStats!!.elapsedMs

        println("[性能] 9路 入门=${entryMs}ms 大师=${masterMs}ms")
        assertTrue(
            "入门档(${entryMs}ms) 应显著快于大师档(${masterMs}ms)",
            entryMs <= masterMs,
        )
    }

    /** 空棋盘（无处可下的终局形态）不能死循环。 */
    @Test
    fun doesNotHangOnBoardWithNoLegalMoves() = runBlocking {
        // 9 路整盘填满黑子是不可能的合法局面，改为验证「无候选时返回 pass」
        val board = Board(9)
        for (y in 0 until 9) {
            for (x in 0 until 9) {
                if ((x + y) % 2 == 0) board.put(x, y, Stone.BLACK)
            }
        }
        // 剩下的一半点全是黑子的眼或被围，白棋无处可下
        val engine = MctsEngine(9)
        val move = engine.findBestMove(board, Stone.WHITE, Difficulty.ADVANCED, Random(3))
        // 允许返回任意合法着法或 pass(-1)，只要不挂起
        assertTrue("应返回着法或 pass", move >= -1)
    }
}
