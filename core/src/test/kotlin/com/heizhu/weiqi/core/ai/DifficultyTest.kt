package com.heizhu.weiqi.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 档位定义测试。
 *
 * 这里只断言**结构与可核对的事实**（顺序、id 唯一、说明非空、预算递增），
 * 因为难度阶梯的强弱是**测量出来的**（见 tools/katago/README.md 的方法论），
 * 不是靠断言宣称的。
 */
class DifficultyTest {

    private val ladder = listOf(
        Difficulty.ENTRY, Difficulty.BEGINNER, Difficulty.INTERMEDIATE,
        Difficulty.ADVANCED, Difficulty.MASTER,
    )

    @Test
    fun `五档按固定顺序排列，且 id 唯一`() {
        assertEquals(5, Difficulty.entries.size)
        assertEquals(ladder, Difficulty.entries.toList())
        assertEquals(5, ladder.map { it.id }.toSet().size)
    }

    @Test
    fun `档位越强，思考预算越大`() {
        val budgets = ladder.map { it.timeBudgetMs }
        for (i in 0 until budgets.size - 1) {
            assertTrue("预算应随档位递增：$budgets", budgets[i] < budgets[i + 1])
        }
    }

    @Test
    fun `每一档都有说明文字，且设置页用的是 descriptionFor`() {
        for (d in ladder) {
            assertTrue("${d.displayName} 的说明不应为空", d.description.isNotBlank())
            assertTrue("说明不应写死棋盘尺寸", !d.description.contains("9 路") && !d.description.contains("13 路"))
            assertTrue("descriptionFor 应返回非空文案", d.descriptionFor(9).isNotBlank())
        }
    }

    @Test
    fun `存损坏时回退到初级，不崩`() {
        assertEquals(Difficulty.BEGINNER, Difficulty.fromId("不存在"))
    }
}
