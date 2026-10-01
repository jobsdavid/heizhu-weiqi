package com.heizhu.weiqi.vm

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 提子特效的数据链路自测（本机 JVM，不需要电视）。
 *
 * 来源：黑猪大人反馈「提子的时候没有任何特效，刷的一下就没了，都看不清」，
 * 同时「AI 有子都不吃」——后者经属性测试证伪（五档各 8 个提子机会、漏提 0 个），
 * 真正的问题是**提子没有视觉反馈**，导致吃子被误认为没吃。
 *
 * 这条链路有两段可能出错：①从棋盘差算出被提的点；②把它交给绘制层。
 * ①在这里用纯函数直接测；②由 `TvScreenLayoutTest` 保证页面能带着这些数据渲染。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CaptureEffectDataTest {

    private fun vm(): GameViewModel =
        GameViewModel(ApplicationProvider.getApplicationContext<Application>())

    @Test
    fun `前后对盘能找出被提的点与被提一方的颜色`() {
        val before = ByteArray(9 * 9)
        before[40] = 1          // 黑子在 (4,4)
        before[41] = 2          // 白子两颗在 (5,4) (6,4)
        before[42] = 2
        val after = before.copyOf()
        after[41] = 0           // 白子被提
        after[42] = 0

        val (points, code) = vm().diffCaptured(before, after)
        assertEquals("应当正好找到这两个被提的点", listOf(41, 42), points.toList())
        assertEquals("颜色码应为白(2)", 2, code)
    }

    @Test
    fun `没有提子时返回空`() {
        val before = ByteArray(9 * 9)
        before[40] = 1
        val after = before.copyOf()
        after[50] = 1           // 只是多落了一手

        val (points, code) = vm().diffCaptured(before, after)
        assertTrue("这一手没提子，不应产生特效数据", points.isEmpty())
        assertEquals(0, code)
    }

    /**
     * 悔棋：棋子是**重新出现**而不是消失，所以绝不能被当成提子 ——
     * 否则孩子按一次悔棋就会看到满屏「被吃」特效。
     */
    @Test
    fun `悔棋时棋子重新出现不应被当成提子`() {
        val before = ByteArray(9 * 9)
        before[40] = 1
        val after = before.copyOf()
        after[41] = 2           // 悔棋后棋子变多了

        val (points, code) = vm().diffCaptured(before, after)
        assertTrue("悔棋不应触发提子特效", points.isEmpty())
        assertEquals(0, code)
    }

    @Test
    fun `尺寸不一致时安全返回空`() {
        val (points, code) = vm().diffCaptured(ByteArray(81), ByteArray(361))
        assertTrue(points.isEmpty())
        assertEquals(0, code)
    }
}
