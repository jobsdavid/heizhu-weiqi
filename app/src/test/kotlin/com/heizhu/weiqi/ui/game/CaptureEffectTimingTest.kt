package com.heizhu.weiqi.ui.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提子特效的**时长与节奏闸门**（纯 JVM，不用 Robolectric）。
 *
 * 这个特效因为「太快」被反馈过两次：560ms → 900ms → 用户明确要求「闪一秒、再一秒淡出」。
 * 时长原来藏在一个 `tween(900)` 里，**没人测得到**；现在抽成 [captureEffectFrame]，
 * 这条用例把「两段各 1 秒、第一段在闪、第二段在淡出」钉死 —— 谁再改短会红。
 */
class CaptureEffectTimingTest {

    private fun alphasBetween(fromMs: Int, toMs: Int, stepMs: Int = 50): List<Float> =
        (fromMs..toMs step stepMs).map { captureEffectFrame(it.toLong()).ghostAlpha }

    @Test
    fun `时长是闪一秒加淡出一秒`() {
        assertEquals("第一段（闪）必须是 1 秒", 1000, CAPTURE_FLASH_MS)
        assertEquals("第二段（淡出）必须是 1 秒", 1000, CAPTURE_FADE_MS)
    }

    @Test
    fun `第一秒内确实在闪：明暗交替，且保持原大小`() {
        val alphas = alphasBetween(0, 999)
        assertTrue("第一秒里必须出现「亮」帧", alphas.any { it > 0.5f })
        assertTrue("第一秒里必须出现「暗」帧（否则不叫闪）", alphas.any { it < 0.3f })
        assertTrue(
            "闪烁期间要保持原大小 —— 一上来就缩小，孩子还是看不见",
            (0..999 step 50).all { captureEffectFrame(it.toLong()).ghostScale > 0.99f },
        )
    }

    @Test
    fun `第二秒确实在淡出：逐渐变淡，末尾几乎看不见`() {
        val fading = alphasBetween(1000, 1999)
        assertTrue("第二秒应当越来越淡", fading.last() < fading.first())
        assertTrue("第二秒末尾应当几乎看不见", fading.last() < 0.1f)
    }

    @Test
    fun `两秒之后彻底结束，不留残影`() {
        assertEquals(0f, captureEffectFrame(2000).ghostAlpha)
        assertEquals(0f, captureEffectFrame(2000).ringAlpha)
        assertEquals(0f, captureEffectFrame(9999).ghostAlpha)
        assertEquals("负数时刻不该画出任何东西", 0f, captureEffectFrame(-1).ghostAlpha)
    }
}
