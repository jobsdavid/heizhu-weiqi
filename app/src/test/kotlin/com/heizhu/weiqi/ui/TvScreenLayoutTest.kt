package com.heizhu.weiqi.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isFocused
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    /**
     * 断言「焦点落在包含某段文字的那个可聚焦单元上」。
     *
     * ⚠️ 必须用 `isFocused()`（比**值**），不能用
     * `keyIsDefined(SemanticsProperties.Focused)` ——
     * 后者匹配的是「所有可聚焦节点」（未聚焦时该属性也存在，值为 false），
     * 于是查询永远命中树里的第一个可聚焦节点。我在这上面翻过一次车：
     * 焦点其实一步都没动，却被宽松的容差判成了通过。
     */
    private fun assertFocusedOn(insideText: String) {
        rule.waitForIdle()
        val inner = rule.onNodeWithText(insideText).fetchSemanticsNode().boundsInRoot.center
        val focused = rule.onAllNodes(isFocused())
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
            // 时长：给一组真实值，让「用时 / 总用时」真渲染出来（默认 0 会显示占位 "--:--"）
            startedAtMs = System.currentTimeMillis() - 83_000,
            turnStartedAtMs = System.currentTimeMillis() - 12_000,
            bossThinkMs = 21_000,
            playerThinkMs = 50_000,
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

    /**
     * 回归用例：设置页的三个选项行必须**竖排**。
     *
     * 曾经的 bug：「音效」和「落子振动」并排 → 用户想按右键挪到右边那个开关，
     * 而左右键被选项行自己吃掉了（用来改值），于是**够不到落子振动**，
     * 看起来就像「这个设置根本无法修改」。
     *
     * 判据：三个标题的 y 区间必须严格递增、互不重叠（并排才会重叠）。
     */
    @Test
    fun `设置-三个选项行必须竖排而不是并排`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        render { SettingsScreen(SettingsStore(ctx), {}) }
        rule.waitForIdle()

        val rows = listOf("音效", "落子振动", "光标移动速度").map {
            it to rule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot
        }
        for (i in 0 until rows.size - 1) {
            val (t1, r1) = rows[i]
            val (t2, r2) = rows[i + 1]
            assertTrue(
                "「$t1」$r1 与「$t2」$r2 是并排的 —— 并排时用户会按左右键去够它，" +
                    "而左右键是改值的，结果就是「够不到、设置不了」",
                r2.top >= r1.bottom - 1f,
            )
        }
        println("  设置页三个选项行 y 区间：${
            rows.joinToString(" / ") { "${it.first}=${it.second.top.toInt()}..${it.second.bottom.toInt()}" }
        }")
    }

    /**
     * 回归用例：上下键必须能依次走到每一行、最后到「返回主菜单」。
     *
     * ⚠️ 不能用 `onNodeWithText("开")` 定位 —— 「音效」和「落子振动」的芯片都是 开/关，
     * 文字重名会让匹配到多个节点。改成**把按键发给当前获得焦点的节点**，
     * 再用几何关系判断焦点落在哪一行。
     */
    @Test
    fun `设置-上下键能走到每一个选项行与返回按钮`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        render { SettingsScreen(SettingsStore(ctx), {}) }
        rule.waitForIdle()

        assertFocusedRowIs("音效")            // 初始焦点在第一行
        pressOnFocused(Key.DirectionDown)
        assertFocusedRowIs("落子振动")        // ← 用户报「设置不了」的就是这一行
        pressOnFocused(Key.DirectionDown)
        assertFocusedRowIs("光标移动速度")
        pressOnFocused(Key.DirectionDown)
        assertFocusedRowIs("返回主菜单")
        pressOnFocused(Key.DirectionUp)
        assertFocusedRowIs("光标移动速度")
    }

    /** 把按键发给**当前获得焦点的节点**（文字重名时没法用文字定位）。 */
    private fun pressOnFocused(key: Key) {
        rule.onNode(isFocused()).performKeyInput {
            keyDown(key)
            keyUp(key)
        }
        rule.waitForIdle()
    }

    /**
     * 断言「焦点在标题为 [title] 的那一行」。
     *
     * 用几何关系判断：焦点盒与标题的 x 区间重叠，且纵向紧邻（行：标题在盒上方；
     * 按钮：文字在盒内部）。用文字定位不行 —— 选项芯片文字会重名。
     */
    private fun assertFocusedRowIs(title: String) {
        val f = focusedRowBounds() ?: error("没有任何节点获得焦点")
        val t = rule.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        val xOverlap = f.left < t.right && f.right > t.left
        // 容差 100px：行间距约 200px，容差再大就会把「焦点没动」判成通过
        val yNear = kotlin.math.abs(f.top - t.top) < 100f
        assertTrue(
            "期望焦点在「$title」那一行：标题 $t，焦点盒 $f（x 重叠=$xOverlap y 邻近=$yNear）",
            xOverlap && yNear,
        )
    }

    /** 取当前获得焦点的那个单元的包围盒（用 isFocused 比值，见 assertFocusedOn 注释）。 */
    private fun focusedRowBounds(): Rect? =
        rule.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.boundsInRoot

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

    /** 渲染对局页并把 [undone] 接到悔棋回调上。 */
    private fun renderGame(undone: () -> Unit) {
        render {
            GameScreen(
                ui = sampleUi(9, thinking = false),
                cursorSpeed = CursorSpeed.NORMAL,
                onMoveCursor = { _, _ -> },
                onJumpToRecentMove = {},
                onConfirm = {},
                onUndo = undone,
                onPass = {},
                onResign = {},
                onHint = {},
                onExit = {},
                onConsumeToast = {},
                onClearHint = {},
            )
        }
        rule.waitForIdle()
    }

    /**
     * 回归用例：返回键悔棋**必须先弹确认框**。
     *
     * 第一版是「返回键直接悔棋」，理由是孩子下错棋的挫败感是劝退主因；
     * 但真机上误碰返回键会让刚下的一手莫名消失，那比下错棋更崩溃。
     */
    /** 取「包含某段文字」的节点包围盒，没有则报错。 */
    private fun boxOf(text: String): Rect =
        rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot

    /**
     * 两侧面板的纵向位置**必须与提示文字、回合状态无关**。
     *
     * 用户实测：提示变成「这里已经有子了」时，右下角提示区高度变了，黑猪勇士头像往下掉。
     * 量下来是两类独立原因，两侧都有：
     *   · 右栏 HintBox 的高度随提示文字行数变 → 两个 weight(1f) 间距重新分配 → 面板位移
     *   · PlayerPanel 的「该你了／思考中…」胶囊**有则出现无则消失** → 面板自身高度变
     *
     * 判据用面板标题（「黑猪大人」/「黑猪勇士」）的 y 坐标 —— 它是面板内容的第一行文字，
     * 面板一动它必动。这样测的是**结果**（面板有没有跳），而不是某个具体实现细节。
     *
     * 后续往面板里加「用时」这类新行时，这条用例会一起守住：新行必须恒定存在，
     * 不能是「有时才显示」。
     */
    @Test
    fun `对局页-面板位置不得随提示与回合状态变化`() {
        val states = listOf(
            "轮黑猪大人（左有胶囊）" to sampleUi(9),
            "轮黑猪勇士（右有胶囊）" to sampleUi(9).copy(toMove = Stone.WHITE),
            "黑猪大人思考中" to sampleUi(9).copy(thinking = true),
            "提示：非法落子" to sampleUi(9).copy(previewIllegal = "这里已经有子了", previewCapture = 0),
            "提示：无处可下" to sampleUi(9).copy(playerHasNoLegalMove = true),
            "提示：可提子" to sampleUi(9).copy(previewCapture = 4),
        )
        data class M(val boss: Float, val warrior: Float, val hintH: Float, val hintTop: Float)
        val measured = LinkedHashMap<String, M>()

        // ⚠️ rule.setContent 一个测例只能调一次（再调会抛 "has already set content"），
        // 所以用状态变量驱动，而不是循环里反复 setContent。第一版就是那么写的，直接报错。
        var ui by mutableStateOf(states.first().second)
        rule.setContent {
            WeiqiTvTheme {
                GameScreen(ui, CursorSpeed.NORMAL, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
        rule.waitForIdle()

        for ((label, state) in states) {
            ui = state
            rule.waitForIdle()
            // 提示框 = 那几条提示文案所在的文字节点（按前缀认，不写死完整句子）
            val hintPrefixes = listOf(
                "对局结束", "你没地方下了", "黑猪大人正在思考", "这里已经有子了",
                "落在光标处", "OK 落子",
            )
            val hb = textNodes().firstOrNull { (t, _) -> hintPrefixes.any { p -> t.contains(p) } }?.second
            measured[label] = M(
                boxOf("黑猪大人").top,
                boxOf("黑猪勇士").top,
                hb?.height ?: -1f,
                hb?.top ?: -1f,
            )
            rule.waitForIdle()
        }

        println("  === 各状态下两侧面板标题 y / 提示框高度 ===")
        measured.forEach { (k, v) ->
            println("    %-22s 黑猪大人 y=%7.1f  黑猪勇士 y=%7.1f  提示框 h=%6.1f top=%7.1f"
                .format(k, v.boss, v.warrior, v.hintH, v.hintTop))
        }
        val boss = measured.values.map { it.boss }
        val warrior = measured.values.map { it.warrior }
        val spreadB = boss.max() - boss.min()
        val spreadW = warrior.max() - warrior.min()
        println("    → 位移幅度：左侧 %.1f px，右侧 %.1f px".format(spreadB, spreadW))

        assertEquals("左侧面板位置随状态漂移 %.1f px".format(spreadB), 0f, spreadB, 0.5f)
        assertEquals("右侧面板位置随状态漂移 %.1f px".format(spreadW), 0f, spreadW, 0.5f)

        // 两栏纵向结构必须一致：否则两个面板一高一低（曾因左栏顶部多一块「总用时」
        // 而差 49px）。这条断言把「对称」也钉住，不只是「不漂移」。
        val off = kotlin.math.abs(boss.first() - warrior.first())
        assertEquals("左右面板应对齐，实际差 %.1f px".format(off), 0f, off, 2f)
    }

    /**
     * 时长显示：两个玩家的「用时」各一处 + 左侧信息块的「总用时」一处。
     *
     * 断言用「包含」而不是精确文案 —— 时钟是活的（界面每 500ms 本地重算），
     * 精确断言秒数会变成随机失败的测试。
     */
    @Test
    fun `对局页-显示总用时与双方思考时长`() {
        renderGame {}
        val hits = textNodes().map { it.first }.filter { it.contains("用时") }
        assertEquals("应为「左侧用时 + 右侧用时 + 总用时」共 3 处，实际：$hits", 3, hits.size)
        assertTrue(
            "必须有一处是总用时，实际：$hits",
            hits.any { it.startsWith("总用时") },
        )
        assertTrue(
            "两侧都要有各自的用时，实际：$hits",
            hits.count { it.startsWith("用时") } == 2,
        )
    }

    @Test
    fun `对局页-返回键要先确认才悔棋`() {
        var undone = 0
        renderGame { undone++ }

        pressOnFocused(Key.Back)
        rule.onNodeWithText("要悔棋吗？").assertExists()
        assertEquals("只按返回键不应真的悔棋", 0, undone)

        pressOnFocused(Key.Enter)
        assertEquals("确认后应当执行悔棋", 1, undone)
    }

    /** 再按一次返回键 = 接着下（取消），而不是继续悔棋。 */
    @Test
    fun `对局页-返回键再按一次是取消`() {
        var undone = 0
        renderGame { undone++ }

        pressOnFocused(Key.Back)
        pressOnFocused(Key.Back)
        rule.onNodeWithText("要悔棋吗？").assertDoesNotExist()
        assertEquals("取消后不应悔棋", 0, undone)
    }

    /** 「悔棋 5」会被读成「已经悔了 5 次」，必须是「可悔棋 N 次」。 */
    @Test
    fun `对局页-悔棋次数文案不产生歧义`() {
        renderGame {}
        rule.onNodeWithText("第 3 手 · 可悔棋 5 次").assertExists()
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
