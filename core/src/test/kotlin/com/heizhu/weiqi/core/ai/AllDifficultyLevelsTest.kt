package com.heizhu.weiqi.core.ai

import com.heizhu.weiqi.core.rules.Board
import com.heizhu.weiqi.core.rules.PlayOutcome
import com.heizhu.weiqi.core.rules.Stone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 五个难度档的逐档自测 —— **纯 JVM，本机跑，不需要电视、不需要看图**。
 *
 * 测的是「孩子能直接感觉到」的那几件事，而不是抽象的棋力：
 * 该提的子提不提？开局是不是下在一线？会不会填自己的眼？单手会不会让对局卡住？
 *
 * 每一档都跑一遍，并把实测数据打出来（`showStandardStreams = true` 会显示）。
 */
class AllDifficultyLevelsTest {

    private fun name(size: Int, index: Int): String {
        if (index < 0) return "PASS"
        val letters = "ABCDEFGHJKLMNOPQRST"
        return "${letters[index % size]}${size - index / size}"
    }

    private fun pick(size: Int, board: Board, color: Stone, d: Difficulty, seed: Int): Int =
        runBlocking { MctsEngine(size).findBestMove(board, color, d, Random(seed)) }

    /** 白 E4 只剩 E5 一口气，黑先 —— 围棋里最基础的一手就是提掉它。 */
    private fun atariPosition(): Board {
        val b = Board(9)
        b.play(4, 4, Stone.WHITE)
        b.play(3, 4, Stone.BLACK)
        b.play(5, 4, Stone.BLACK)
        b.play(4, 3, Stone.BLACK)
        return b
    }

    private val capturePoint = 5 * 9 + 4   // (4,5)

    @Test
    fun `五档-都能提掉只剩一气的子`() {
        for (d in Difficulty.entries) {
            val b = atariPosition()
            val hits = (1..3).count { pick(9, b, Stone.BLACK, d, it) == capturePoint }
            println("  ${d.displayName}（战术档=${d.tacticAssist}）: 3 次里提子 $hits 次")
            // 「能提就提」是规则层面的常识，不是难度旋钮 —— 五档都必须做到。
            // 难点在于：随机走子的搜索本身看不见提子（见 Difficulty.tacticAssist 注释）
            assertEquals("${d.displayName} 档没有提子", 3, hits)
        }
    }

    /**
     * 属性测试：**只要盘上存在能提子的着法，就必须提。**
     *
     * 来源：黑猪大人反馈「9 路中级，AI 有子都不吃」。
     * 单元测试只测我手写的那一个棋形，会漏掉「某些具体形状下规则不生效」这一类问题，
     * 所以这里改为在**大量随机局面**上验证这条性质。
     */
    @Test
    fun `随机局面里只要有子可提就必须提`() {
        for (d in Difficulty.entries) {
            var opportunities = 0
            var missed = 0
            for (seed in 1..8) {
                val rng = Random(seed * 7919L)
                val b = Board(9)
                var color = Stone.BLACK
                // 随机铺子，双方交替、只落合法点
                repeat(14 + rng.nextInt(18)) {
                    val empties = (0 until 81).filter { b.cells[it].toInt() == 0 }
                    for (c in empties.shuffled(rng).take(8)) {
                        if (b.play(c % 9, c / 9, color) is PlayOutcome.Ok) break
                    }
                    color = color.opponent
                }

                // 光随机铺子几乎碰不到提子机会（实测 8 个局面只有 1 个），
                // 得**主动构造打吃**：找一颗孤子，把它四邻中的三个填上对方颜色，
                // 目标方下一手就能提它。
                val target = Stone.BLACK
                repeat(3) {
                    val empties = (0 until 81).filter { b.cells[it].toInt() == 0 }
                    for (p in empties.shuffled(rng)) {
                        val px = p % 9
                        val py = p / 9
                        val nbs = listOfNotNull(
                            (px - 1 to py).takeIf { px > 0 },
                            (px + 1 to py).takeIf { px < 8 },
                            (px to py - 1).takeIf { py > 0 },
                            (px to py + 1).takeIf { py < 8 },
                        )
                        if (nbs.size < 4) continue
                        if (nbs.any { b.cells[it.second * 9 + it.first].toInt() != 0 }) continue
                        if (b.play(px, py, target) !is PlayOutcome.Ok) continue
                        var filled = 0
                        for ((nx, ny) in nbs) {
                            if (filled == 3) break
                            val opponent = target.opponent
                            if (b.play(nx, ny, opponent) is PlayOutcome.Ok) filled++
                        }
                        if (filled == 3) break
                    }
                }
                color = target.opponent

                // 这一方现在有哪些着法能提子
                val capturing = (0 until 81).filter { i ->
                    val out = b.preview(i % 9, i / 9, color)
                    out is PlayOutcome.Ok && out.captureCount > 0
                }
                if (capturing.isEmpty()) continue
                opportunities++

                val move = pick(9, b, color, d, seed)
                if (move !in capturing) {
                    missed++
                    if (missed == 1) {
                        println("  ✘ ${d.displayName} 漏提：可提着法 $capturing，实际选了 $move")
                        println(b.toString().lines().joinToString("\n") { "      $it" })
                    }
                }
            }
            println("  ${d.displayName}: 有提子机会的局面 $opportunities 个，漏提 $missed 个")
            assertEquals(
                "${d.displayName} 档在有提子机会时没提（漏 $missed / $opportunities）",
                0,
                missed,
            )
        }
    }

    @Test
    fun `五档-空盘第一手都不在一线`() {
        for (d in Difficulty.entries) {
            val move = pick(9, Board(9), Stone.BLACK, d, 3)
            val col = move % 9
            val row = move / 9
            val line = minOf(col, row, 8 - col, 8 - row) + 1
            println("  ${d.displayName}: 空盘第一手 ${name(9, move)}（第 $line 线）")
            assertTrue("${d.displayName} 档第一手落在一线：${name(9, move)}", line >= 2)
        }
    }

    @Test
    fun `五档-自战十二手全部合法且单手不超时`() {
        for (d in Difficulty.entries) {
            val b = Board(9)
            val engine = MctsEngine(9)
            var color = Stone.BLACK
            var worst = 0L
            var total = 0L

            repeat(12) { i ->
                val t0 = System.currentTimeMillis()
                val mv = runBlocking { engine.findBestMove(b, color, d, Random(500 + i)) }
                val cost = System.currentTimeMillis() - t0
                worst = maxOf(worst, cost)
                total += cost

                if (mv < 0) {
                    b.pass()
                } else {
                    val out = b.play(mv % 9, mv / 9, color)
                    assertTrue(
                        "${d.displayName} 档第 ${i + 1} 手给出非法着法 ${name(9, mv)} → $out",
                        out is PlayOutcome.Ok,
                    )
                }
                color = color.opponent
            }

            println(
                "  ${d.displayName}: 12 手共 ${total}ms，单手最慢 ${worst}ms" +
                    "（预算 ${d.timeBudgetMs}ms，上限 ${d.maxPlayouts} playouts）",
            )
            assertTrue(
                "${d.displayName} 单手 ${worst}ms 明显超出时间预算 ${d.timeBudgetMs}ms",
                worst <= d.timeBudgetMs + 600,
            )
        }
    }

    /**
     * 终局机制：局面已经算得清、对方也停了一手时，引擎必须**停一手**。
     *
     * 不加这条规则，对局在实战里结束不了（引擎只要还有空点就一定会下），
     * 「双方停一手 → 数子结算」这条路走不到，结果页的比分永远是摆设。
     */
    @Test
    fun `局面已定且对方停一手时应当收工`() {
        // 9 路：黑吃下 x=0..5，自己肚子里留一个 2×2 空穴（填了也不涨地）；
        // 白占 x=6..8，留两个真眼（对黑来说是自杀点，候选里会被筛掉）。
        val b = Board(9)
        val hole = setOf(2 to 2, 2 to 3, 3 to 2, 3 to 3)
        for (y in 0 until 9) {
            for (x in 0..5) {
                if ((x to y) in hole) continue
                assertTrue("摆黑棋失败", b.play(x, y, Stone.BLACK) is PlayOutcome.Ok)
            }
        }
        val whiteEyes = setOf(7 to 2, 7 to 6)
        for (y in 0 until 9) {
            for (x in 6..8) {
                if ((x to y) in whiteEyes) continue
                assertTrue("摆白棋失败", b.play(x, y, Stone.WHITE) is PlayOutcome.Ok)
            }
        }
        println("  收工测试局面：\n" + b.toString().lines().joinToString("\n") { "    $it" })

        // 对方（白）刚刚停了一手
        b.pass()

        val move = pick(9, b, Stone.BLACK, Difficulty.INTERMEDIATE, 7)
        println("  黑的选择 = ${name(9, move)}（-1 = 停一手，期望如此）")
        assertEquals("局面已定、对方停一手时应当收工停一手", -1, move)
    }

    /**
     * 局面已定时，**即使对方没有停手**也必须收工。
     *
     * 原来的实现多带了一个条件「对方刚停过手」，于是只要对手一直有子可下
     * （哪怕是填自己的地），这一方就永远陪着下，对局永远结束不了 ——
     * 真机上黑猪大人的问题就是「有一方都没地方下子了还不判输赢吗」。
     */
    @Test
    fun `局面已定时对方没停手也应当收工`() {
        val b = Board(9)
        val hole = setOf(2 to 2, 2 to 3, 3 to 2, 3 to 3)
        for (y in 0 until 9) {
            for (x in 0..5) {
                if ((x to y) in hole) continue
                b.play(x, y, Stone.BLACK)
            }
        }
        val whiteEyes = setOf(7 to 2, 7 to 6)
        for (y in 0 until 9) {
            for (x in 6..8) {
                if ((x to y) in whiteEyes) continue
                b.play(x, y, Stone.WHITE)
            }
        }
        // ⚠️ 刻意**不调用** b.pass() —— 对方（白）刚刚并没有停手

        val move = pick(9, b, Stone.BLACK, Difficulty.INTERMEDIATE, 3)
        println("  对方未停手时的选择 = ${name(9, move)}（-1 = 停一手，期望如此）")
        assertEquals("局面已定时不应当继续填自己的地", -1, move)
    }

    /** 反向闸门：开局阶段对方停一手，**绝不能**收工（否则会直接数子出个荒唐比分）。 */
    @Test
    fun `开局对方停一手不应当收工`() {
        val b = Board(9)
        b.play(4, 4, Stone.BLACK)   // 只落一子
        b.pass()                     // 对方停一手
        val move = pick(9, b, Stone.BLACK, Difficulty.INTERMEDIATE, 11)
        println("  开局停一手后黑的选择 = ${name(9, move)}")
        assertTrue("开局就被误判成收工（选了 ${name(9, move)}）", move >= 0)
    }

    @Test
    fun `五档-都不会填自己的眼`() {
        for (d in Difficulty.entries) {
            // 角上真眼：A9 空着，它的两个正交邻居与对角（唯一一个）都是黑
            val b = Board(9)
            b.play(1, 0, Stone.BLACK)
            b.play(0, 1, Stone.BLACK)
            b.play(1, 1, Stone.BLACK)

            val move = pick(9, b, Stone.BLACK, d, 9)
            println("  ${d.displayName}: 有真眼时的选择 ${name(9, move)}")
            assertTrue(
                "${d.displayName} 档会填自己的眼（选了 ${name(9, move)}）",
                move != 0,
            )
        }
    }
}
