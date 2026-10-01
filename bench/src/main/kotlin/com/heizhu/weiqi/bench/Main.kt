package com.heizhu.weiqi.bench

import com.heizhu.weiqi.core.ai.Difficulty
import com.heizhu.weiqi.core.ai.MctsEngine
import com.heizhu.weiqi.core.rules.Board
import com.heizhu.weiqi.core.rules.PlayOutcome
import com.heizhu.weiqi.core.rules.Stone
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.random.Random

/**
 * 棋力评测台入口。
 *
 * 设计取舍：**不做 GTP 协议**。评测只需要「给局面 → 要一手」这一件事，
 * 而 KataGo 的分析引擎本身也是 JSON 进出 —— 两边对称，Python 侧驱动最省事。
 * 多实现一套 GTP 协议，只会给评测流程多增加一个与棋力无关的失败点。
 *
 * 协议：stdin 一行一条命令，stdout 一行一条结果（Python 直接按行配对即可）。
 *
 *     genmove <size> <B|W> <difficultyId> <seed> <moves>
 *         → "x,y"          这一手
 *         → "pass"         停一手
 *     selfplay <size> <difficultyId> <plies> <seed>
 *         → 空格分隔的落子序列，如 "3,3 4,5 pass ..."（黑先、交替）
 *     quit
 *
 * `<moves>` 用空格分隔，元素为 "x,y"，停一手写作 `pass`，空局面写作 `-`。
 * 坐标是**扁平索引的分解**：index = y * size + x（与 app 内部一致）。
 */
fun main() {
    val out = java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true)
    val reader = BufferedReader(InputStreamReader(System.`in`))
    while (true) {
        val line = reader.readLine() ?: break
        val t = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (t.isEmpty()) continue
        try {
            when (t[0]) {
                "quit" -> return
                "genmove" -> out.println(genmove(t))
                "selfplay" -> out.println(selfplay(t))
                else -> out.println("err unknown-command ${t[0]}")
            }
        } catch (e: Exception) {
            // 出错必须**把错误回给调用方**而不是静默断流：评测台悄悄少一条结果，
            // 统计会拿到错位的配对，出来的棋力数字全是假的。
            out.println("err ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}

/** 按交替颜色把着法序列摆回棋盘。非法着法直接抛，不静默跳过 —— 局面喂错等于白测。 */
private fun parseBoard(size: Int, movesSpec: String): Board {
    val board = Board(size)
    var color = Stone.BLACK
    for (m in movesSpec.split(' ')) {
        if (m.isEmpty() || m == "-") continue
        if (m == "pass") {
            board.pass()
        } else {
            val parts = m.split(',')
            val x = parts[0].toInt()
            val y = parts[1].toInt()
            val outcome = board.play(x, y, color)
            check(outcome is PlayOutcome.Ok) { "illegal move $m for $color" }
        }
        color = color.opponent
    }
    return board
}

/**
 * 解析难度，**不认识的直接报错**。
 *
 * 不能用 Difficulty.fromId：它找不到时会静默回退到 BEGINNER（那是为「存档损坏」准备的
 * 容错）。评测里这个兜底极其危险 —— 我传了 "ENTRY"（大写），fromId 匹配的是小写 id
 * "entry"，于是五次「不同难度」其实全跑了 BEGINNER，得出「难度旋钮失效」的假结论，
 * 而且任何地方都不会报错。
 */
private fun difficultyOf(raw: String): Difficulty {
    val id = raw.lowercase()
    return Difficulty.entries.firstOrNull { it.id == id }
        ?: error("未知难度 '$raw'，可用: ${Difficulty.entries.joinToString(", ") { it.id }}")
}

/**
 * 电视实测速率（模拟次数/秒）。设了它，就把各档的模拟次数封到「预算 × 速率」，
 * 复现电视上的真实条件；不设则用各档自己的 maxPlayouts（开发机的满速跑法）。
 *
 * 用途：开发机比电视快 5~6 倍，不封顶的话评测出来的棋力会偏乐观。
 */
private fun tvPlayoutCap(difficulty: Difficulty): Int? {
    val rate = System.getenv("WEIQI_TV_RATE")?.toDoubleOrNull() ?: return null
    return (difficulty.timeBudgetMs * rate / 1000.0).toInt().coerceAtLeast(1)
}

private fun genmove(t: List<String>): String {
    val size = t[1].toInt()
    val color = if (t[2].uppercase().startsWith("B")) Stone.BLACK else Stone.WHITE
    val diff = difficultyOf(t[3])
    val seed = t.getOrNull(4)?.toLongOrNull() ?: 1L
    // ⚠️ 着法序列是**整行剩余部分**，不能只取 t[5]。
    // 只取第一个 token 时，引擎会在几乎空盘上选点（合法），而调用方以为送的是完整局面，
    // 现象是 KataGo 报「非法着法」—— 排查方向会被带偏到引擎身上（实际是我的解析丢了着法）。
    val spec = if (t.size > 5) t.drop(5).joinToString(" ") else "-"
    val board = parseBoard(size, spec)
    val cap = tvPlayoutCap(diff)
    val move = runBlocking { MctsEngine(size).findBestMove(board, color, diff, Random(seed), cap) }
    return if (move < 0) "pass" else "${move % size},${move / size}"
}

private fun selfplay(t: List<String>): String {
    val size = t[1].toInt()
    val diff = difficultyOf(t[2])
    val plies = t[3].toInt()
    val seed = t.getOrNull(4)?.toLongOrNull() ?: 1L
    val engine = MctsEngine(size)
    val board = Board(size)
    var color = Stone.BLACK
    val moves = ArrayList<String>(plies)
    val cap = tvPlayoutCap(diff)
    for (i in 0 until plies) {
        // 每手换种子：定长种子的 MCTS 会走出完全一样的棋，样本就没有多样性了
        val move = runBlocking { engine.findBestMove(board, color, diff, Random(seed + i), cap) }
        if (move < 0) {
            board.pass()
            moves += "pass"
        } else {
            val x = move % size
            val y = move / size
            check(board.play(x, y, color) is PlayOutcome.Ok) { "selfplay produced illegal move $x,$y" }
            moves += "$x,$y"
        }
        color = color.opponent
    }
    return moves.joinToString(" ")
}
