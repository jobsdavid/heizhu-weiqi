package com.heizhu.weiqi.core.ai

import com.heizhu.weiqi.core.rules.Board
import com.heizhu.weiqi.core.rules.Stone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络引导路径的行为测试。
 *
 * 用 core 的测试资源里的夹具网络（`net/pv9_*`，由 `tools/katago/make_fixture.py` 生成）。
 * 这里故意**不做耗时的精确断言** —— 那种测试在 CI 上必然抖动；改断言"语义"：
 * 预算耗尽时要立刻收工（拿已有最优），而不是空转或抛异常。
 */
class NetGuidedEngineTest {

    private fun fixtureNet(): PolicyValueNet {
        val bin = javaClass.getResourceAsStream("/net/pv9_weights.bin")?.readBytes()
            ?: error("缺少测试资源 net/pv9_weights.bin，先跑 tools/katago/make_fixture.py")
        val manifest = javaClass.getResourceAsStream("/net/pv9_manifest.json")
            ?.readBytes()?.decodeToString()
            ?: error("缺少测试资源 net/pv9_manifest.json")
        return PolicyValueNet.load(manifest, bin)
    }

    @Test
    fun `有网络时返回盘内合法着法`() {
        val engine = MctsEngine(9, net = fixtureNet())
        val board = Board(9)
        val move = runBlocking {
            engine.findBestMove(board, Stone.BLACK, Difficulty.MASTER, kotlin.random.Random(1))
        }
        assertTrue(
            "网络路径应返回盘内着法或停一手，实际 $move",
            move == MctsEngine.PASS_MOVE || move in 0 until 81,
        )
    }

    @Test
    fun `最长时长是上限而不是固定等待_预算为 0 也要立刻收工`() {
        val engine = MctsEngine(9, net = fixtureNet())
        val board = Board(9)
        val t0 = System.currentTimeMillis()
        val move = runBlocking {
            engine.findBestMove(
                board, Stone.BLACK, Difficulty.MASTER, kotlin.random.Random(2),
                timeBudgetOverrideMs = 0L,
            )
        }
        val elapsed = System.currentTimeMillis() - t0
        assertTrue(
            "预算为 0 时仍应返回合法着法（用候选里的第一个），实际 $move",
            move == MctsEngine.PASS_MOVE || move in 0 until 81,
        )
        // 宽松上限：只用来抓"没有检查 deadline、把 15~30 秒跑满"这类回归。
        // 一台慢 CI 上一次前向可能上百毫秒，所以给 5 秒余量。
        assertTrue("预算为 0 时不该长时间空转，实际 ${elapsed}ms", elapsed < 5_000)
    }

    @Test
    fun `预算极小时也必须给出盘内着法_不得因为一个候选都没算完就停一手`() {
        // ⚠️ 这条用例的判据（结果必须落在盘内）**在旧实现下是红的**：
        // 旧实现先检查「到点没有」，再评估第一个候选 —— 预算 0 时当场 break，
        // scored 为空 → 返回停一手。而「AI 莫名停一手」比多等几毫秒严重得多
        // （孩子看到的是对手突然不下了），所以新实现把第一个候选改成**无条件评估**。
        // 变异验证：把 firstCandidate 那段无条件评估去掉，本用例必红。
        val engine = MctsEngine(9, net = fixtureNet())
        val board = Board(9)
        for (budget in listOf(0L, 1L, 10L)) {
            val move = runBlocking {
                engine.findBestMove(
                    board, Stone.BLACK, Difficulty.MASTER, kotlin.random.Random(4),
                    timeBudgetOverrideMs = budget,
                )
            }
            assertTrue(
                "预算 ${budget}ms 时仍应给出盘内着法（可以不最优，但不能停一手），实际 $move",
                move in 0 until 81,
            )
        }
    }

    @Test
    fun `无网络时参数不影响原路径_仍能出着法`() {
        // 覆盖"忘记给 net 传参时旧路径照常工作"这条底线
        val engine = MctsEngine(9)
        val board = Board(9)
        val move = runBlocking {
            engine.findBestMove(
                board, Stone.BLACK, Difficulty.ENTRY, kotlin.random.Random(3),
                timeBudgetOverrideMs = 1_000L,
            )
        }
        assertTrue(
            "原路径应返回盘内着法或停一手，实际 $move",
            move == MctsEngine.PASS_MOVE || move in 0 until 81,
        )
    }
}
