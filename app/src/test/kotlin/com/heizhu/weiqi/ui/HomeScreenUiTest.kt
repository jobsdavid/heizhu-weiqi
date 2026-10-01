package com.heizhu.weiqi.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heizhu.weiqi.ui.home.HomeScreen
import com.heizhu.weiqi.ui.theme.WeiqiTvTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 本机 UI 自测的**探针**：确认 Robolectric + Compose 这条链路能在开发机上跑起来。
 *
 * 目的：把界面验证从「装到电视上按键 + 截图 + 看图」搬回本机 JVM ——
 * 不用电视、不用模拟器、不用视觉识别，断言直接输出文字。
 *
 * ## 两个必须的配置（少一个就废）
 *
 * 1. `@GraphicsMode(NATIVE)`：否则 Robolectric 的文字测量是空实现，
 *    所有文本节点尺寸为 0，「有没有被压扁」这类断言全部失效。
 * 2. `qualifiers` 必须**指定电视的屏幕尺寸与密度**：Robolectric 默认是手机屏幕，
 *    TV 布局的下半部分会跑到屏幕外，`assertIsDisplayed` 直接失败
 *    （实测：首页的「历史战绩」按钮就报 not displayed）。
 *    真机小米电视是 1920×1080 px / density 320 → 960dp × 540dp / xhdpi。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class HomeScreenUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `首页渲染出四个主按钮与成绩卡`() {
        rule.setContent {
            WeiqiTvTheme {
                HomeScreen(
                    records = emptyList(),
                    onStartGame = {},
                    onHistory = {},
                    onSettings = {},
                    onExit = {},
                )
            }
        }

        // 先把根节点尺寸打出来，确认拿到的确实是电视那块屏
        val root = rule.onRoot().fetchSemanticsNode().size
        println("  根节点尺寸 = ${root.width} x ${root.height} px（期望 1920 x 1080）")

        listOf("开始对局", "历史战绩", "设置", "退出", "我的成绩").forEach { text ->
            val node = rule.onNodeWithText(text).fetchSemanticsNode()
            println("  $text → ${node.boundsInRoot}")
            rule.onNodeWithText(text).assertIsDisplayed()
        }
        println("  探针通过：本机能渲染 Compose 界面并读到真实布局尺寸")
    }
}
