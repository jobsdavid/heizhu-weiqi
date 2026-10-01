package com.heizhu.weiqi.core.rules

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 围棋规则一致性测试 —— **纯 JVM，本机跑，不需要电视也不需要看图**。
 *
 * 分两层，缺一不可：
 *
 * 1. **固定局面断言**：提子、禁自杀、打劫、提子逃出、数子。
 *    每一条都能对着围棋规则原文逐字读一遍，人可复核。
 *
 * 2. **与「朴素参考实现」的差分测试**：随机对局里每一手都比对
 *    「谁被提了、落子后棋盘长什么样」。
 *    参考实现（[NaiveGo]）按定义全盘重算，**不与快速实现共用任何优化代码** ——
 *    所以两边一致才有说服力。共用代码的自测等于自己给自己判卷。
 */
class GoRulesConformanceTest {

    // ============================================================
    // 朴素参考实现：怎么笨怎么写，绝不与快速实现共享代码
    // ============================================================

    private class NaiveGo(private val size: Int) {

        fun neighbors(i: Int): List<Int> {
            val x = i % size
            val y = i / size
            val out = ArrayList<Int>(4)
            if (y > 0) out.add(i - size)
            if (y < size - 1) out.add(i + size)
            if (x > 0) out.add(i - 1)
            if (x < size - 1) out.add(i + 1)
            return out
        }

        /** (该色连通块的所有点, 该块的气) */
        fun group(cells: ByteArray, start: Int): Pair<List<Int>, Set<Int>> {
            val color = cells[start]
            val stones = LinkedHashSet<Int>()
            val libs = LinkedHashSet<Int>()
            val queue = ArrayDeque<Int>()
            stones.add(start)
            queue.addLast(start)
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                for (nb in neighbors(cur)) {
                    if (cells[nb].toInt() == 0) {
                        libs.add(nb)
                    } else if (cells[nb] == color && stones.add(nb)) {
                        queue.addLast(nb)
                    }
                }
            }
            return stones.toList() to libs
        }

        /**
         * 落子。返回 (落子后的棋盘, 被提的点)；非法（越界 / 已有子 / 自杀）返回 null。
         *
         * **不实现打劫** —— 劫属于「禁止全局同形」，是另一条规则，由被测实现单独负责，
         * 差分测试也只比对提子与自杀这两条。
         */
        fun play(before: ByteArray, x: Int, y: Int, color: Int): Pair<ByteArray, List<Int>>? {
            if (x !in 0 until size || y !in 0 until size) return null
            val me = y * size + x
            if (before[me].toInt() != 0) return null

            val cells = before.copyOf()
            cells[me] = color.toByte()
            val opp = if (color == 1) 2 else 1

            // 反复全盘扫描，把任何一块无气的对方棋提掉（一直扫到没有变化为止）
            val captured = ArrayList<Int>()
            var found = true
            while (found) {
                found = false
                for (i in cells.indices) {
                    if (cells[i].toInt() != opp) continue
                    val (stones, libs) = group(cells, i)
                    if (libs.isEmpty()) {
                        for (p in stones) {
                            cells[p] = 0
                            captured.add(p)
                        }
                        found = true
                    }
                }
            }

            // 自杀：提子之后才数自己的气
            val (_, myLibs) = group(cells, me)
            if (myLibs.isEmpty()) return null
            return cells to captured
        }
    }

    private fun name(size: Int, index: Int): String {
        if (index < 0) return "PASS"
        val letters = "ABCDEFGHJKLMNOPQRST"
        return "${letters[index % size]}${size - index / size}"
    }

    // ============================================================
    // 1. 固定局面
    // ============================================================

    @Test
    fun `提子-提掉只剩一口气的单子`() {
        val b = Board(9)
        b.play(4, 4, Stone.WHITE)   // E4
        b.play(3, 4, Stone.BLACK)
        b.play(5, 4, Stone.BLACK)
        b.play(4, 3, Stone.BLACK)
        assertEquals("白还剩一口气", 0, b.blackCaptured)

        val out = b.play(4, 5, Stone.BLACK)
        assertTrue("应当成功提子", out is PlayOutcome.Ok)
        assertEquals("应提 1 子", 1, (out as PlayOutcome.Ok).captureCount)
        assertEquals("黑方提子数应为 1", 1, b.blackCaptured)
        assertEquals("被提的点应当空出来", Stone.EMPTY, b.stoneAt(4, 4))
    }

    @Test
    fun `提子-一次提掉三子`() {
        val b = Board(9)
        // 白三子横排在 E 行
        b.play(3, 4, Stone.WHITE)
        b.play(4, 4, Stone.WHITE)
        b.play(5, 4, Stone.WHITE)
        // 黑把四周堵住，只留 (5,5)
        listOf(2 to 4, 6 to 4, 3 to 3, 4 to 3, 5 to 3, 3 to 5, 4 to 5).forEach {
            assertTrue(b.play(it.first, it.second, Stone.BLACK) is PlayOutcome.Ok)
        }

        val out = b.play(5, 5, Stone.BLACK)
        assertTrue(out is PlayOutcome.Ok)
        assertEquals("应一次提 3 子", 3, (out as PlayOutcome.Ok).captureCount)
        assertEquals(3, b.blackCaptured)
        assertEquals(Stone.EMPTY, b.stoneAt(3, 4))
        assertEquals(Stone.EMPTY, b.stoneAt(4, 4))
        assertEquals(Stone.EMPTY, b.stoneAt(5, 4))
    }

    @Test
    fun `禁自杀-四周被对方填满且提不到子`() {
        val b = Board(9)
        listOf(3 to 4, 5 to 4, 4 to 3, 4 to 5).forEach {
            assertTrue(b.play(it.first, it.second, Stone.WHITE) is PlayOutcome.Ok)
        }
        assertEquals("(4,4) 是自杀点", PlayOutcome.Suicide, b.play(4, 4, Stone.BLACK))
        assertEquals("被拒的落子不能污染棋盘", Stone.EMPTY, b.stoneAt(4, 4))
    }

    /**
     * 打劫 + 「提子逃出」。
     *
     * 这一手在**提子之前自己一口气都没有**（四个邻居三个是黑、一个是白），
     * 全靠提掉那个白子腾出来的气才活下来。
     * 如果实现把自杀判定放在提子之前，这一手会被误判成自杀 ——
     * 这是围棋规则实现里最经典的一个顺序 bug，所以单独钉一条用例。
     */
    /**
     * 标准劫形。
     *
     * ```
     *    A  B  C  D  E  F
     * 9  .  .  .  .  .  .        (4,4) 是劫子：(3,4)(4,3)(4,5) 三面是黑，只剩 (5,4) 一口气
     * 8  .  .  .  X  O  .        (5,4) 落子后自己一口气都没有，全靠提子腾出的气活下来
     * 7  .  .  .  .  O  .        (6,4)(5,3)(5,5) 是三个互不相连的白子，
     * 6  .  .  .  X  O  .        它们让黑这手提完之后只剩 (4,4) 一口气 —— 这才是劫
     * 5  .  .  .  O  X  O
     * ```
     */
    private fun koPosition(): Board {
        val b = Board(9)
        // (4,4) 是劫子；(5,4) 的另外三个邻居必须是**互不相连**的白子，
        // 否则黑提完之后自己的气大于 1，就不是劫了（第一版我就在这里摆错，
        // 被自测当场抓出来）。
        listOf(4 to 4, 6 to 4, 5 to 3, 5 to 5).forEach {
            assertTrue("摆棋失败 @${name(9, it.second * 9 + it.first)}", b.play(it.first, it.second, Stone.WHITE) is PlayOutcome.Ok)
        }
        listOf(3 to 4, 4 to 3, 4 to 5).forEach {
            assertTrue("摆棋失败 @${name(9, it.second * 9 + it.first)}", b.play(it.first, it.second, Stone.BLACK) is PlayOutcome.Ok)
        }
        return b
    }

    @Test
    fun `打劫-提子逃出与不能立即提回`() {
        val b = koPosition()

        // 这一手在**提子之前自己一口气都没有**，全靠提掉白子腾出的气活下来。
        // 把自杀判定写在提子之前的实现，会在这里误判成自杀。
        val out = b.play(5, 4, Stone.BLACK)
        assertTrue("「提子逃出」被误判成自杀 —— 自杀判定写在了提子之前", out is PlayOutcome.Ok)
        assertEquals("只应提到那一颗劫子", 1, (out as PlayOutcome.Ok).captureCount)
        assertEquals("劫点必须是刚被提掉的那个点", 4 * 9 + 4, b.koPoint)

        assertEquals("白不能立即提回同一劫", PlayOutcome.Ko, b.play(4, 4, Stone.WHITE))

        // 白在别处走一手（找劫材），劫就解了
        assertTrue(b.play(0, 0, Stone.WHITE) is PlayOutcome.Ok)
        assertTrue("隔一手后应当可以提回", b.play(4, 4, Stone.WHITE) is PlayOutcome.Ok)
        assertEquals("这次轮到白提 1 子", 1, b.whiteCaptured)
    }

    @Test
    fun `停一手会解除劫的限制`() {
        val b = koPosition()
        assertTrue(b.play(5, 4, Stone.BLACK) is PlayOutcome.Ok)
        assertEquals(4 * 9 + 4, b.koPoint)
        b.pass()
        assertEquals("pass 之后不应再有劫点", -1, b.koPoint)
    }

    /** 中国规则数子：黑占左边四列、白占右边三列，中间一条黑柱隔开。 */
    @Test
    fun `数子-中国规则`() {
        val b = Board(9)
        for (y in 0 until 9) {
            assertTrue(b.play(4, y, Stone.BLACK) is PlayOutcome.Ok)
            assertTrue(b.play(5, y, Stone.WHITE) is PlayOutcome.Ok)
        }
        val s = Scorer(9).score(b.cells, Komi.STANDARD)

        assertEquals("黑子 9", 9, s.blackStones)
        assertEquals("白子 9", 9, s.whiteStones)
        assertEquals("左边 4 列 36 点全归黑", 36, s.blackTerritory)
        assertEquals("右边 3 列 27 点全归白", 27, s.whiteTerritory)
        assertEquals("没有单官", 0, s.neutral)
        assertEquals("黑 45 子", 45, s.blackTotal)
        assertEquals("白 36 子", 36, s.whiteTotal)
        assertEquals("黑净胜 9 子", 9, s.blackMargin)
        assertEquals("贴 3¾ 子时净胜 9 > 7.5，黑胜", Stone.BLACK, s.winner)
    }

    // ============================================================
    // 2. 差分测试：随机对局，逐手与朴素实现比对
    // ============================================================

    @Test
    fun `随机对局-每一手的提子与棋盘都与朴素实现一致`() {
        val size = 9
        val pointCount = size * size
        var compared = 0
        var games = 0

        repeat(20) { g ->
            val b = Board(size)
            val rng = Random(g * 7919L + 12345L)
            var color = Stone.BLACK

            repeat(120) { ply ->
                val before = b.cells.copyOf()
                val empties = (0 until pointCount).filter { before[it].toInt() == 0 }
                var moved = false

                // 一次只看 10 个随机候选，够覆盖到提子/自杀/劫这些边界就够了
                for (cand in empties.shuffled(rng).take(10)) {
                    val x = cand % size
                    val y = cand / size
                    val out = b.play(x, y, color)
                    val ref = NaiveGo(size).play(before, x, y, color.code.toInt())

                    if (out is PlayOutcome.Ok) {
                        assertNotNull(
                            "快速实现接受了 ${name(size, cand)}，朴素实现却判非法",
                            ref,
                        )
                        assertEquals(
                            "手 $ply 提子集合不一致 @${name(size, cand)}",
                            ref!!.second.sorted(),
                            out.captured.sorted(),
                        )
                        assertArrayEquals(
                            "手 $ply 落子后棋盘不一致 @${name(size, cand)}",
                            ref.first,
                            b.cells,
                        )
                        compared++
                        moved = true
                        break
                    }
                    if (out is PlayOutcome.Suicide) {
                        assertNull(
                            "快速实现把 ${name(size, cand)} 判成自杀，朴素实现认为合法",
                            ref,
                        )
                    }
                    // Ko 属于「禁止全局同形」，朴素实现不实现它，这里跳过
                }

                if (!moved) b.pass()
                color = color.opponent
            }
            games++
        }

        println("  差分测试：$games 局 / 比对 $compared 手提子结果与整盘棋盘，全部一致")
        assertTrue("比对样本太少（$compared 手），这条测试没有实际意义", compared > 1200)
    }
}
