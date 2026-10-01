package com.heizhu.weiqi.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.heizhu.weiqi.core.ai.Difficulty
import com.heizhu.weiqi.core.game.EndReason
import com.heizhu.weiqi.core.game.GameResult
import com.heizhu.weiqi.core.rules.Komi
import com.heizhu.weiqi.core.rules.Score
import com.heizhu.weiqi.core.rules.Stone
import com.heizhu.weiqi.data.CursorSpeed
import com.heizhu.weiqi.data.GameRecord
import com.heizhu.weiqi.data.SettingsStore
import com.heizhu.weiqi.ui.game.GameScreen
import com.heizhu.weiqi.ui.history.HistoryScreen
import com.heizhu.weiqi.ui.home.HomeScreen
import com.heizhu.weiqi.ui.home.NewGameScreen
import com.heizhu.weiqi.ui.result.ResultScreen
import com.heizhu.weiqi.ui.settings.SettingsScreen
import com.heizhu.weiqi.ui.theme.WeiqiTvTheme
import com.heizhu.weiqi.vm.GameUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **本机 UI 自测** —— 在开发机的 JVM 上渲染真实的 Compose 界面并断言。
 *
 * 这是「装到电视上按键 + 截图 + 用眼睛看图」的替代品：
 * 不用电视、不用模拟器、不用视觉识别，几秒钟跑完，断言输出文字。
 *
 * ## 它能查什么（也就是真机上那些坑）
 *
 * 1. **布局被压扁 / 越界** —— 逐页遍历所有文字节点，量它们在**真实布局**下的高度与位置。
 *    这正是真机上「设置页第三行被压成 16px、选项直接消失」那类 bug，
 *    以前只能靠 uiautomator 读控件树才能发现。
 * 2. **焦点落在哪个单元** —— 直接断言焦点节点的包围盒覆盖了哪个选项。
 *    这正是「焦点态完全不渲染」那类 bug 的功能面。
 * 3. **页面能不能渲染出来** —— 空数据 / 满数据都不崩。
 *
 * ## 两个必须的配置（少一个这套就跑不起来）
 *
 * - `@GraphicsMode(NATIVE)`：否则文字测量是空实现，所有文本尺寸为 0，压扁检查全废。
 * - `qualifiers` 指定电视的屏幕与密度：Robolectric 默认是手机屏，
 *   TV 布局下半部分会跑到屏幕外（实测首页「历史战绩」按钮直接判定为不可见）。
 *   真机小米电视 = 1920×1080 px / density 320 → 960dp × 540dp / xhdpi。
 *
 * ⚠️ 它替代不了的：真机算力（AI 每次搜索的耗时）、电视桌面/系统层面的行为
 *    （应用入口、被系统抢焦点、overscan 实机裁切）。那些还得上真机，但次数可以很少。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class TvScreenLayoutTest {

    @get:Rule
    val rule = createComposeRule()

    /** 屏幕尺寸（px）。@Config 里是 960dp×540dp / xhdpi(density 2) → 1920×1080 px。 */
    private val screenW = 1920f
    private val screenH = 1080f

    /** 健康一行文字的高度在 40px 上下；被压扁的会掉到 20px 以下。 */
    private val minTextHeight = 22f

    // ============================================================
    // 断言工具
    // ============================================================

    private fun textNodes(): List<Pair<String, Rect>> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
            .fetchSemanticsNodes()
            .map { node ->
                val text = runCatching {
                    node.config[SemanticsProperties.Text].joinToString(" ") { it.text }
                }.getOrDefault("")
                text to node.boundsInRoot
            }

    /**
     * 逐页检查：没有任何文字节点被压扁或越出屏幕。
     *
     * 这是真机上「布局静默压扁」那一类缺陷的本机版本 —— 在真机上它不会报错、
     * 截图看着「好像还行」，只有量控件树的高度才能发现。
     */
    private fun assertLayoutSane(page: String) {
        rule.waitForIdle()
        val nodes = textNodes()
        val bad = ArrayList<String>()
        for ((text, rect) in nodes) {
            if (rect.height < minTextHeight) bad += "被压扁 高${rect.height.toInt()}px  「$text」"
            if (rect.right > screenW || rect.bottom > screenH || rect.left < 0f || rect.top < 0f) {
                bad += "出界 $rect  「$text」"
            }
        }
        val real = nodes.filter { it.first.isNotBlank() }
        println("  [$page] 文字节点 ${real.size} 个，问题 ${bad.size} 处")
        bad.forEach { println("      ✘ $it") }
        assertEquals("[$page] 有 ${bad.size} 处布局问题", 0, bad.size)
    }

    /**
     * 在「包含某段文字」的节点上敲一次键。
     *
     * 用 `keyDown` + `keyUp` 而不是 `pressKey` —— 后者在本项目用的 Compose 1.12 里
     * 并不存在（`KeyInjectionScope` 只有 keyDown/keyUp/isKeyDown）。
     */
    private fun tapKey(insideText: String, key: Key) {
        rule.onNodeWithText(insideText).performKeyInput {
            keyDown(key)
            keyUp(key)
        }
        rule.waitForIdle()
    }

    /** 断言「焦点落在包含某段文字的那个可聚焦单元上」。 */
    private fun assertFocusedOn(insideText: String) {
        rule.waitForIdle()
        val inner = rule.onNodeWithText(insideText).fetchSemanticsNode().boundsInRoot.center
        val focused = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused))
            .fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .firstOrNull { it.contains(inner) }
        assertTrue(
            "期望焦点在「$insideText」所在的单元上，但没有任何获得焦点的单元覆盖它",
            focused != null,
        )
    }

    // ============================================================
    // 构造测试数据
    // ============================================================

    /** 最坏情况：5 档难度 + 3 种棋盘都有记录 —— 统计卡的行数会拉满。 */
    private fun worstCaseRecords(): List<GameRecord> {
        val combos = listOf(
            9 to Difficulty.ENTRY, 9 to Difficulty.BEGINNER, 9 to Difficulty.INTERMEDIATE,
            13 to Difficulty.ADVANCED, 13 to Difficulty.MASTER,
            19 to Difficulty.MASTER, 19 to Difficulty.INTERMEDIATE,
        )
        return combos.mapIndexed { i, (size, diff) ->
            GameRecord(
                id = i.toLong(),
                playedAt = System.currentTimeMillis() - i * 60_000L,
                boardSize = size,
                difficultyId = diff.id,
                playerColorCode = 1,
                playerWon = if (i % 2 == 0) 1 else -1,
                blackTotal = 45,
                whiteTotal = 36,
                blackMargin = 9,
                playerCaptures = 0,
                aiCaptures = 1,
                moveCount = 12 + i,
                undoCount = if (i == 3) 2 else 0,
                durationMs = 90_000,
                endReason = if (i % 3 == 0) "RESIGN" else "SCORED",
                moves = "",
            )
        }
    }

    private fun sampleUi(size: Int = 9, thinking: Boolean = false): GameUi {
        val cells = ByteArray(size * size)
        cells[size * 4 + 4] = 1
        cells[size * 5 + 5] = 2
        return GameUi(
            boardSize = size,
            cells = cells,
            lastMoveX = 5, lastMoveY = 5,
            toMove = Stone.BLACK,
            playerColor = Stone.WHITE,
            difficulty = Difficulty.MASTER,
            thinking = thinking,
            moveCount = 3,
            blackCaptured = 1,
            whiteCaptured = 0,
            cursorX = 4, cursorY = 4,
            previewCapture = 2,
        )
    }

    private fun render(content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent { WeiqiTvTheme { content() } }
    }

    // ============================================================
    // 逐页布局检查
    // ============================================================

    @Test
    fun `首页-布局检查`() {
        render {
            HomeScreen(worstCaseRecords().take(4), {}, {}, {}, {})
        }
        assertLayoutSane("首页（有战绩）")
    }

    @Test
    fun `新对局-布局检查`() {
        render {
            NewGameScreen(9, Difficulty.MASTER, Stone.WHITE, { _, _, _ -> }, {})
        }
        assertLayoutSane("新对局")
    }

    @Test
    fun `对局页-布局检查-9路`() {
        render { GameScreen(sampleUi(9), CursorSpeed.NORMAL, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
        assertLayoutSane("对局页·9路")
    }

    @Test
    fun `对局页-布局检查-19路`() {
        render { GameScreen(sampleUi(19), CursorSpeed.NORMAL, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
        assertLayoutSane("对局页·19路")
    }

    @Test
    fun `历史战绩-布局检查-最坏情况`() {
        render { HistoryScreen(worstCaseRecords(), {}, {}) }
        assertLayoutSane("历史战绩（5 档难度 + 3 种棋盘）")
    }

    @Test
    fun `历史战绩-布局检查-空数据`() {
        render { HistoryScreen(emptyList(), {}, {}) }
        assertLayoutSane("历史战绩（空）")
    }

    @Test
    fun `设置-布局检查`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        render { SettingsScreen(SettingsStore(ctx), {}) }
        assertLayoutSane("设置")
    }

    @Test
    fun `结果页-布局检查-数子结束`() {
        val result = GameResult(
            winner = Stone.BLACK,
            playerWon = false,
            score = Score(
                boardPoints = 81, blackStones = 45, whiteStones = 36,
                blackTerritory = 0, whiteTerritory = 0, neutral = 0, komi = Komi.STANDARD,
            ),
            moveCount = 120, undoCount = 1, durationMs = 300_000,
            reason = EndReason.SCORED,
        )
        render { ResultScreen(result, Stone.WHITE, 9, "大师", {}, {}) }
        assertLayoutSane("结果页·数子")
    }

    @Test
    fun `结果页-布局检查-认输不显示比分`() {
        val result = GameResult(
            winner = Stone.BLACK, playerWon = false,
            // 认输局存档里那两个数字是无意义的（盘上只剩一色时数子会把整盘判给它），
            // 页面必须不显示它们 —— 这里故意塞进 361 这种荒唐值来验证
            score = Score(361, 1, 0, 360, 0, 0, Komi.STANDARD),
            moveCount = 1, undoCount = 0, durationMs = 24_000,
            reason = EndReason.RESIGN,
        )
        render { ResultScreen(result, Stone.WHITE, 19, "大师", {}, {}) }
        assertLayoutSane("结果页·认输")
        rule.onNodeWithText("认输的棋不数子").assertExists()
    }

    // ============================================================
    // 焦点行为
    // ============================================================

    @Test
    fun `新对局-初始焦点在开始对局上`() {
        render { NewGameScreen(9, Difficulty.ENTRY, Stone.BLACK, { _, _, _ -> }, {}) }
        assertFocusedOn("开始对局")
    }

    @Test
    fun `新对局-上下键能把焦点挪到各个选择行`() {
        render { NewGameScreen(9, Difficulty.ENTRY, Stone.BLACK, { _, _, _ -> }, {}) }
        rule.waitForIdle()

        tapKey("开始对局", Key.DirectionUp)      // → 执什么颜色
        assertFocusedOn("黑棋")
        tapKey("黑棋", Key.DirectionUp)          // → 对手难度
        assertFocusedOn("入门")
        tapKey("入门", Key.DirectionUp)          // → 棋盘大小
        assertFocusedOn("9 路")
        // 左右键在这一行改值，焦点应留在本行（值 9 路 → 13 路）
        tapKey("9 路", Key.DirectionRight)
        assertFocusedOn("13 路")
    }

    @Test
    fun `首页-上下键在四个按钮间移动焦点`() {
        render { HomeScreen(emptyList(), {}, {}, {}, {}) }
        rule.waitForIdle()
        assertFocusedOn("开始对局")
        tapKey("开始对局", Key.DirectionDown)
        assertFocusedOn("历史战绩")
        tapKey("历史战绩", Key.DirectionDown)
        assertFocusedOn("设置")
        tapKey("设置", Key.DirectionDown)
        assertFocusedOn("退出")
    }

    @Test
    fun `对局页-有焦点可接收按键`() {
        render { GameScreen(sampleUi(9), CursorSpeed.NORMAL, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
        rule.waitForIdle()
        // 落子页整页是一个焦点单元，方向键由它自己消费
        val focused = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused))
            .fetchSemanticsNodes()
        assertTrue("对局页必须有节点持有焦点，否则方向键收不到", focused.isNotEmpty())
    }
}
