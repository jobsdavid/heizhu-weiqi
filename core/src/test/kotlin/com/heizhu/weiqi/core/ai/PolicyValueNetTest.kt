package com.heizhu.weiqi.core.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 端侧前向与 PyTorch 的**逐值对齐**测试。
 *
 * 为什么必须做这件事：手写的 conv 只要张量布局（NCHW 的 C 序展开）或权重偏移差一点，
 * **不会报任何错**，只会静默输出垃圾。而"网络接进 MCTS 之后棋力没提升"这种现象，
 * 根本分不清是移植错了还是本来就不行 —— 所以先用固定局面把两侧输出对到浮点容差内。
 *
 * 夹具由 `tools/katago/make_fixture.py` 生成（同一份权重 + 3 个固定局面的期望输出）。
 * 夹具缺失时直接失败并提示怎么生成 —— 不能让这条测试"悄悄跳过"。
 */
class PolicyValueNetTest {

    @Serializable
    private data class Expected(val size: Int, val cases: List<Case>)

    @Serializable
    private data class Case(
        val toMove: String,
        val cells: List<Int>,
        val policy: List<Float>,
        val scoreLead: Float,
    )

    private fun res(name: String): ByteArray =
        javaClass.getResourceAsStream("/net/$name")?.readBytes()
            ?: error("缺少测试资源 net/$name —— 用 tools/katago/make_fixture.py 生成后拷进 core/src/test/resources/net/")

    @Test
    fun `端侧前向必须与 PyTorch 对齐`() {
        val manifest = res("pv9_manifest.json").decodeToString()
        val bin = res("pv9_weights.bin")
        val net = PolicyValueNet.load(manifest, bin)
        val expected = Json { ignoreUnknownKeys = true }
            .decodeFromString<Expected>(res("pv9_expected.json").decodeToString())

        assertEquals("棋盘尺寸应与夹具一致", expected.size, net.size)
        assertTrue("夹具里应有样本", expected.cases.isNotEmpty())

        var worstPolicy = 0f
        var worstScore = 0f
        for ((i, c) in expected.cases.withIndex()) {
            val cells = ByteArray(c.cells.size) { c.cells[it].toByte() }
            val toMove: Byte = if (c.toMove == "B") 1 else 2
            val e = net.evaluate(cells, toMove)

            var polDiff = 0f
            for (j in c.policy.indices) {
                val d = abs(e.policy[j] - c.policy[j])
                if (d > polDiff) polDiff = d
            }
            val scoreDiff = abs(e.scoreLead - c.scoreLead)
            if (polDiff > worstPolicy) worstPolicy = polDiff
            if (scoreDiff > worstScore) worstScore = scoreDiff

            println(
                "  局面 $i（${c.toMove} 走）策略最大差 %.2e  目差 端侧 %.3f / PyTorch %.3f"
                    .format(polDiff, e.scoreLead, c.scoreLead),
            )
        }

        // 容差 1e-3（策略）：exp 与累加顺序不同会有微小差异；布局错位则是量级差异
        assertTrue("策略最大偏差 $worstPolicy 过大 —— 前向实现与 PyTorch 不一致", worstPolicy < 1e-3f)
        assertTrue("目差最大偏差 $worstScore 过大 —— 价值头实现不一致", worstScore < 1e-2f)
    }

    @Test
    fun `策略必须在空点上并且归一化`() {
        val net = PolicyValueNet.load(res("pv9_manifest.json").decodeToString(), res("pv9_weights.bin"))
        val expected = Json { ignoreUnknownKeys = true }
            .decodeFromString<Expected>(res("pv9_expected.json").decodeToString())
        val c = expected.cases.first()
        val cells = ByteArray(c.cells.size) { c.cells[it].toByte() }
        val e = net.evaluate(cells, if (c.toMove == "B") 1 else 2)

        var sum = 0f
        for (i in cells.indices) {
            if (cells[i].toInt() != 0) {
                assertEquals("已被占据的点概率必须为 0", 0f, e.policy[i], 1e-9f)
            }
            sum += e.policy[i]
        }
        assertEquals("策略应归一化到 1", 1f, sum, 1e-4f)
    }
}
