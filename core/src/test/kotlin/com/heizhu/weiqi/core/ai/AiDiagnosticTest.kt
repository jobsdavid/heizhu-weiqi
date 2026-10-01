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
 * AI 行为诊断 —— 不看代码猜，直接把引擎的着法打出来。
 *
 * 这些用例回答的是「孩子能直接感觉到」的问题：
 * 该提的子提不提？开局第一手下在哪？几十手之后像不像在下围棋？
 */
class AiDiagnosticTest {

    private fun name(size: Int, index: Int): String {
        if (index < 0) return "PASS"
        // 围棋惯例：跳过字母 I
        val letters = "ABCDEFGHJKLMNOPQRST"
        return "${letters[index % size]}${size - index / size}"
    }

    private fun pick(size: Int, board: Board, color: Stone, d: Difficulty, seed: Int = 42): Int =
        runBlocking { MctsEngine(size).findBestMove(board, color, d, Random(seed)) }

    /** 孩子最先学会的一手：对方只剩一口气，提掉它。 */
    @Test
    fun `初级应当提掉只剩一气的单子`() {
        val b = Board(9)
        b.play(4, 4, Stone.WHITE)   // 白 D5
        b.play(3, 4, Stone.BLACK)   // 黑 C5
        b.play(5, 4, Stone.BLACK)   // 黑 E5
        b.play(4, 3, Stone.BLACK)   // 黑 D6
        // 白 (4,4) 只剩 (4,5) 一口气 —— 黑下 (4,5) 即提子

        val expected = 5 * 9 + 4
        var caught = 0
        val seeds = 1..5
        for (s in seeds) {
            val move = pick(9, b, Stone.BLACK, Difficulty.BEGINNER, s)
            println("  [提子测试] 种子$s → ${name(9, move)}")
            if (move == expected) caught++
        }
        println("  [提子测试] 5 次里命中 $caught 次")
        assertEquals("初级档连「提掉只剩一气的子」都不做（5 次全没命中）", true, caught > 0)
    }

    /** 空盘开局第一手。围棋开局不该下在一线。 */
    @Test
    fun `空盘第一手不该下在一线`() {
        val b = Board(9)
        val move = pick(9, b, Stone.BLACK, Difficulty.BEGINNER)
        val col = move % 9
        val row = move / 9
        println("  [开局] 初级空盘第一手 = ${name(9, move)}")
        val onEdge = col == 0 || row == 0 || col == 8 || row == 8
        assertTrue("第一手下在一线（$col,$row），不像在下围棋", !onEdge)
    }

    /**
     * 自战三十手，把棋盘打出来肉眼判断。
     * 同时校验一件硬事实：**引擎给出的着法必须全部合法**（这是「是否按规则下」的底线）。
     */
    @Test
    fun `自战三十手的棋盘与合法性`() {
        val size = 9
        val b = Board(size)
        val engine = MctsEngine(size)
        var color = Stone.BLACK
        var passes = 0
        val log = StringBuilder()

        repeat(30) { i ->
            val move = runBlocking { engine.findBestMove(b, color, Difficulty.BEGINNER, Random(100 + i)) }
            if (move < 0) {
                passes++
                log.append("${i + 1}. $color PASS\n")
                b.pass()
            } else {
                val outcome = b.play(move % size, move / size, color)
                // 硬约束：引擎返回的着法必须是合法的
                assertTrue(
                    "第 ${i + 1} 手引擎给出了非法着法 ${name(size, move)} → $outcome",
                    outcome is PlayOutcome.Ok,
                )
                log.append("${i + 1}. $color ${name(size, move)}" +
                    if ((outcome as PlayOutcome.Ok).captureCount > 0) " 提${outcome.captureCount}子" else "" +
                        "\n")
            }
            color = color.opponent
        }

        println("  [自战三十手]")
        log.lines().forEach { if (it.isNotBlank()) println("    $it") }
        println("  棋盘：")
        b.toString().lines().forEach { if (it.isNotBlank()) println("    $it") }
        println("  pass 次数 = $passes")

        // 一手没提过、三十手全在一个角落 —— 都是「不像围棋」的信号，打印出来供判断
        val emptyRegions = b.cells.count { it.toInt() == 0 }
        println("  剩余空点 = $emptyRegions")
    }
}
