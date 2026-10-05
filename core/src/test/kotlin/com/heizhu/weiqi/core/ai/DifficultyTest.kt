package com.heizhu.weiqi.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 档位定义测试。重点是**让子**：它现在是难度阶梯的主要旋钮，必须可见、可核对。 */
class DifficultyTest {

    @Test
    fun `每一档的说明里都必须写明让几子`() {
        // 这条是为了防止"改了让子数却忘了改文案"——文案与数值分处两个源就一定会漂移。
        for (d in Difficulty.entries) {
            val text = d.descriptionFor(9)
            if (d.handicap > 0) {
                assertTrue("${d.displayName} 的说明应写明让 ${d.handicap} 子，实际：$text",
                    text.contains("让你 ${d.handicap} 子"))
            } else {
                assertTrue("${d.displayName} 的说明应写明不让子，实际：$text", text.contains("不让子"))
            }
        }
    }

    @Test
    fun `让子数随难度单调递减（越强让得越少）`() {
        val ordered = listOf(Difficulty.ENTRY, Difficulty.BEGINNER, Difficulty.INTERMEDIATE,
            Difficulty.ADVANCED, Difficulty.MASTER)
        val hs = ordered.map { it.handicap }
        assertEquals("让子数应为 5/3/2/1/0", listOf(5, 3, 2, 1, 0), hs)
        for (i in 0 until hs.size - 1) {
            assertTrue("档位越强让子应越少：$hs", hs[i] >= hs[i + 1])
        }
    }
}
