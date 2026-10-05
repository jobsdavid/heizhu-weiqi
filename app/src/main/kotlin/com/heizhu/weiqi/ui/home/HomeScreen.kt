package com.heizhu.weiqi.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.heizhu.weiqi.R
import com.heizhu.weiqi.core.ai.Difficulty
import com.heizhu.weiqi.core.rules.Stone
import com.heizhu.weiqi.data.GameRecord
import com.heizhu.weiqi.data.NetAssets
import com.heizhu.weiqi.data.StatsCalculator
import com.heizhu.weiqi.ui.components.SectionCard
import com.heizhu.weiqi.ui.components.StatTile
import com.heizhu.weiqi.ui.components.StoneDot
import com.heizhu.weiqi.ui.components.TvButton
import com.heizhu.weiqi.ui.components.TvButtonStyle
import com.heizhu.weiqi.ui.components.TvIcon
import com.heizhu.weiqi.ui.components.TvOptionRow
import com.heizhu.weiqi.ui.components.TvScaffold
import com.heizhu.weiqi.ui.theme.Accent
import com.heizhu.weiqi.ui.theme.AccentWarm
import com.heizhu.weiqi.ui.theme.Background
import com.heizhu.weiqi.ui.theme.FocusRing
import com.heizhu.weiqi.ui.theme.SurfaceBorder
import com.heizhu.weiqi.ui.theme.TextDim
import com.heizhu.weiqi.ui.theme.TextPrimary
import com.heizhu.weiqi.ui.theme.TextSecondary
import com.heizhu.weiqi.ui.theme.WoodLight

/**
 * 主菜单。
 *
 * ## 焦点为什么由页面自己管
 * 第一版让每个按钮各自 `focusable()`，靠 Compose 的空间导航自动找「下面那个元素」。
 * 模拟器实测**不可靠**：按键后焦点不按预期移动，自动化脚本连设置页都走不出去。
 * 现在改成显式管理：页面持有每个可聚焦单元的 [FocusRequester] 和当前索引，
 * 上下键由页面统一处理。
 *
 * ## 布局预算（1080p = 540dp 高，改任何尺寸前先读这段）
 * 页面可用高度 ≈ 540 - 上下留白 52 - 底部提示条 56 = 432dp。
 * 左列 = 标题块 60 + 4 个按钮。按钮纵向 padding 压到 13dp，
 * 加起来 381dp，留 50dp 余量。**加高任何一个按钮都要重新核一遍 432dp 这个预算。**
 */
@Composable
fun HomeScreen(
    records: List<GameRecord>,
    onStartGame: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onExit: () -> Unit,
) {
    val buttonCount = 4
    val focusRequesters = remember { List(buttonCount) { FocusRequester() } }
    var focusIndex by remember { mutableStateOf(0) }

    LaunchedEffect(focusIndex) {
        runCatching { focusRequesters[focusIndex].requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        focusIndex = (focusIndex - 1).coerceAtLeast(0)
                        true
                    }
                    Key.DirectionDown -> {
                        focusIndex = (focusIndex + 1).coerceAtMost(buttonCount - 1)
                        true
                    }
                    else -> false
                }
            },
    ) {
        TvScaffold(hint = "上下键选择 · OK 确认") {
            Row(modifier = Modifier.fillMaxSize()) {
                // ---- 左：标题 + 主按钮 ----
                Column(modifier = Modifier.width(460.dp).fillMaxSize()) {
                    HomeTitle()
                    Spacer(Modifier.height(14.dp))
                    TvButton(
                        label = "开始对局",
                        subtitle = "选好棋盘和难度就能下",
                        icon = TvIcon.PLAY,
                        style = TvButtonStyle.PRIMARY,
                        onClick = onStartGame,
                        focusRequester = focusRequesters[0],
                        fillWidth = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    TvButton(
                        label = "历史战绩",
                        icon = TvIcon.HISTORY,
                        onClick = onHistory,
                        focusRequester = focusRequesters[1],
                        fillWidth = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    TvButton(
                        label = "设置",
                        icon = TvIcon.SETTINGS,
                        onClick = onSettings,
                        focusRequester = focusRequesters[2],
                        fillWidth = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    TvButton(
                        label = "退出",
                        icon = TvIcon.EXIT,
                        style = TvButtonStyle.DANGER,
                        onClick = onExit,
                        focusRequester = focusRequesters[3],
                        fillWidth = true,
                    )
                }

                Spacer(Modifier.width(24.dp))

                // ---- 右：战绩概览 ----
                HomeSummary(records, modifier = Modifier.weight(1f).fillMaxSize())
            }
        }
    }
}

/** 首页标题块：色条 + 应用名 + 两颗棋子。棋子让顶部立刻有「围棋」的味道。 */
@Composable
private fun HomeTitle() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(12.dp)
                .height(58.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(WoodLight),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // 从资源读取 —— 第一版把「围棋练习」硬编码在这里，改了 app_name 界面却没变
                text = stringResource(R.string.app_name),
                color = TextPrimary,
                fontSize = 40.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = "和黑猪大人下棋 · 从 9 路开始",
                color = TextSecondary,
                fontSize = 21.sp,
            )
        }
        Row {
            StoneDot(isBlack = true, size = 34.dp)
            Spacer(Modifier.width(4.dp))
            StoneDot(isBlack = false, size = 34.dp)
        }
    }
}

/** 首页右侧的成长概览。让孩子每次进来都能看见自己的进步。 */
@Composable
private fun HomeSummary(records: List<GameRecord>, modifier: Modifier = Modifier) {
    val stats = remember(records) { StatsCalculator.overall(records) }
    val streak = remember(records) { StatsCalculator.currentStreak(records) }
    val best = remember(records) { StatsCalculator.longestWinStreak(records) }
    val recent = remember(records) { StatsCalculator.recentResults(records, 10) }

    SectionCard(title = "我的成绩", modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatTile("总对局", "${stats.total}")
            StatTile("胜", "${stats.wins}", valueColor = Accent)
            StatTile("负", "${stats.losses}", valueColor = AccentWarm)
            StatTile(
                "胜率",
                if (stats.hasData) "${(stats.winRate * 100).toInt()}%" else "—",
                valueColor = FocusRing,
            )
        }

        Spacer(Modifier.height(18.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatTile("当前连胜", if (streak > 0) "$streak" else "—", valueColor = Accent)
            StatTile("最长连胜", "$best", valueColor = FocusRing)
        }

        Spacer(Modifier.height(18.dp))

        Text(text = "最近 10 局", color = TextDim, fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))

        if (recent.isEmpty()) {
            Text(text = "还没有记录 · 下第一局吧", color = TextDim, fontSize = 21.sp)
        } else {
            // 用棋子形状表示胜负：比纯色方块更像围棋，孩子也能一眼扫出趋势
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                recent.forEach { result ->
                    Canvas(modifier = Modifier.size(30.dp)) {
                        val r = size.minDimension / 2f
                        val c = Offset(r, r)
                        val fill = when {
                            result > 0 -> Accent
                            result < 0 -> AccentWarm
                            else -> TextDim
                        }
                        drawCircle(fill.copy(alpha = 0.25f), r, c)
                        drawCircle(fill, r * 0.62f, c)
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // 底部一句鼓励语：孩子赢一局回来就能看到变化
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Accent.copy(alpha = 0.12f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = when {
                    stats.total == 0 -> "今天先下 9 路盘，吃子快、几分钟就一盘"
                    stats.winRate >= 0.6f -> "赢得多，可以试试 13 路或者更难的档位"
                    stats.total >= 5 -> "多下一局就会变强，输棋也是练计算"
                    else -> "刚开局，慢慢熟悉棋盘吧"
                },
                color = TextSecondary,
                fontSize = 21.sp,
            )
        }
    }
}

/**
 * 对局设置页。
 *
 * ## 为什么改成左右分栏（2026-10-01）
 * 第一版三行选择器竖着排，1080p 上**放不下**：三行各要 304px（共 912px），
 * 但页头与按钮之间只有 712px。结果是第三行「执什么颜色」被压扁成 16px 高，
 * 选项和说明在控件树里直接不存在 —— 孩子改不了执棋颜色，而且看不出这是 bug。
 *
 * 现在：左列放两个较宽的选择器（棋盘、难度），右列放颜色 + 按钮。
 * 内容总高降到 ~270dp，可用高度 366dp，留出余量。
 */
@Composable
fun NewGameScreen(
    initialSize: Int,
    initialDifficulty: Difficulty,
    initialColor: Stone,
    onStart: (boardSize: Int, difficulty: Difficulty, playerColor: Stone) -> Unit,
    onBack: () -> Unit,
) {
    // 可选尺寸**只有一个来源**：NetAssets.supportedSizes。
    // 界面永远不该列出"没有对应权重"的尺寸 —— 13 路就是因为这个直接崩过应用
    // （见 NetAssets.load 的注释）。13/19 的权重训好放进 assets 后，
    // 只需改 NetAssets.supportedSizes 这一行，界面自动跟着开放。
    val sizes = remember { NetAssets.supportedSizes }
    var sizeIndex by remember { mutableStateOf(sizes.indexOf(initialSize).coerceAtLeast(0)) }
    var diffIndex by remember {
        mutableStateOf(Difficulty.entries.indexOf(initialDifficulty).coerceAtLeast(0))
    }
    var colorIndex by remember { mutableStateOf(if (initialColor == Stone.BLACK) 0 else 1) }

    val unitCount = 4
    val focusRequesters = remember { List(unitCount) { FocusRequester() } }
    // 初始焦点直接放在最后一项「开始对局」上。
    //
    // ⚠️ 这是**用户明确要求**的行为（进这一页就是想把上一局的设置原样再开一局，
    // 所以焦点就落在按钮上，按一下 OK 即可开局）。不要为了别的理由改它 ——
    // 2026-10-05 我擅自把它挪到难度行「方便改难度」，被用户当场纠正。
    var focusIndex by remember { mutableStateOf(unitCount - 1) }

    LaunchedEffect(focusIndex) {
        runCatching { focusRequesters[focusIndex].requestFocus() }
    }

    // 上下键在 4 个焦点单元间移动；左右键放给当前那行改值
    BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        focusIndex = (focusIndex - 1).coerceAtLeast(0)
                        true
                    }
                    Key.DirectionDown -> {
                        focusIndex = (focusIndex + 1).coerceAtMost(unitCount - 1)
                        true
                    }
                    else -> false
                }
            },
    ) {
        TvScaffold(
            title = "新对局",
            hint = "上下键换位置 · 左右键改选项 · 到「开始对局」按 OK",
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // ---- 左列：需要宽度的两个选择器 ----
                Column(
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    TvOptionRow(
                        title = "棋盘大小",
                        options = sizes,
                        selectedIndex = sizeIndex,
                        labelOf = { "$it 路" },
                        descriptionOf = { size ->
                            when (size) {
                                9 -> "吃子快、一盘只要几分钟，最适合刚开始学"
                                13 -> "介于两者之间，练计算和死活的好地方"
                                else -> "标准棋盘。黑猪大人在这个尺寸上会弱一些"
                            }
                        },
                        onSelect = { sizeIndex = it },
                        focusRequester = focusRequesters[0],
                    )
                    TvOptionRow(
                        title = "对手难度",
                        options = Difficulty.entries,
                        selectedIndex = diffIndex,
                        labelOf = { it.displayName },
                        // 用 descriptionFor 而不是 description：难度文案里
                        // 有跟棋盘尺寸相关的部分（见 Difficulty）
                        descriptionOf = { it.descriptionFor(sizes[sizeIndex]) },
                        onSelect = { diffIndex = it },
                        focusRequester = focusRequesters[1],
                    )
                }

                Spacer(Modifier.width(24.dp))

                // ---- 右列：执棋颜色 + 开始按钮 ----
                Column(
                    modifier = Modifier.width(400.dp).fillMaxSize(),
                ) {
                    TvOptionRow(
                        title = "黑猪勇士执什么颜色",
                        options = listOf(Stone.BLACK, Stone.WHITE),
                        selectedIndex = colorIndex,
                        labelOf = { if (it == Stone.BLACK) "黑棋" else "白棋" },
                        descriptionOf = {
                            if (it == Stone.BLACK) "黑棋先下，通常更容易赢一点"
                            else "白棋后下，黑猪大人先走"
                        },
                        onSelect = { colorIndex = it },
                        focusRequester = focusRequesters[2],
                    )

                    Spacer(Modifier.weight(1f))

                    TvButton(
                        label = "开始对局",
                        icon = TvIcon.PLAY,
                        style = TvButtonStyle.PRIMARY,
                        // 焦点**初始就落在这个按钮上**（用户明确要求的设定），左右键在这里
                        // **直接改难度** —— 交给按钮自己接管，而不是靠父层猜「焦点在哪一行」。
                        //
                        // 为什么必须由按钮自己接管（2026-10-05 真机实测）：父层用 `focusIndex`
                        // 猜位置的写法在电视上会与实际焦点错位，出现「说明文字换了一档、高亮
                        // 芯片还停在上一档」这种状态不一致。挂在按钮的 modifier 上，只有焦点
                        // 真在这个按钮时才响应，不存在错位。
                        //
                        // 用户的原始反馈是「无法修改难度」（"按了键但光标不动"只是他描述的
                        // 现象）；而他要求焦点停在按钮上 —— 让按钮上的左右键能改难度，
                        // 两个要求就同时满足了。
                        modifier = Modifier.onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionLeft -> {
                                        diffIndex = (diffIndex - 1).coerceAtLeast(0)
                                        true
                                    }
                                    Key.DirectionRight -> {
                                        diffIndex = (diffIndex + 1)
                                            .coerceAtMost(Difficulty.entries.size - 1)
                                        true
                                    }
                                    else -> false
                                }
                            } else {
                                false
                            }
                        },
                        onClick = {
                            onStart(
                                sizes[sizeIndex],
                                Difficulty.entries[diffIndex],
                                if (colorIndex == 0) Stone.BLACK else Stone.WHITE,
                            )
                        },
                        focusRequester = focusRequesters[3],
                        fillWidth = true,
                    )
                    Spacer(Modifier.height(10.dp))
                    TvButton(label = "返回", onClick = onBack, fillWidth = true)
                }
            }
        }
    }
}
