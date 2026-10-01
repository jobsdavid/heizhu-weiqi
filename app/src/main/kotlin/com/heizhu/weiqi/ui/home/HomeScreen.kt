package com.heizhu.weiqi.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.heizhu.weiqi.R
import com.heizhu.weiqi.core.ai.Difficulty
import com.heizhu.weiqi.core.rules.Stone
import com.heizhu.weiqi.data.GameRecord
import com.heizhu.weiqi.data.StatsCalculator
import com.heizhu.weiqi.ui.components.TvButton
import com.heizhu.weiqi.ui.components.TvOptionRow
import com.heizhu.weiqi.ui.components.TvScaffold
import com.heizhu.weiqi.ui.theme.Accent
import com.heizhu.weiqi.ui.theme.Surface
import com.heizhu.weiqi.ui.theme.SurfaceBorder
import com.heizhu.weiqi.ui.theme.TextDim
import com.heizhu.weiqi.ui.theme.TextPrimary
import com.heizhu.weiqi.ui.theme.TextSecondary
import com.heizhu.weiqi.ui.theme.WoodDark
import com.heizhu.weiqi.ui.theme.WoodLight

/**
 * 主菜单。
 *
 * ## 焦点为什么由页面自己管
 * 第一版让每个按钮各自 `focusable()`，靠 Compose 的空间导航自动找「下面那个元素」。
 * 模拟器实测**不可靠**：按键后焦点不按预期移动，自动化脚本连设置页都走不出去。
 *
 * 现在改成显式管理：页面持有每个可聚焦单元的 [FocusRequester] 和当前索引，
 * 上下键由页面统一处理。大屏应用的可聚焦元素数量有限，显式管理比赌自动导航稳。
 *
 * 布局用 `weight` + `SpaceEvenly` 自适应，**不再写死间距** ——
 * 第一版固定 14dp 间距，在 1080p 上把「退出」按钮挤出了屏幕。
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
            Column(modifier = Modifier.fillMaxSize()) {
                // ---- 标题 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(12.dp)
                            .height(52.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(WoodLight),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            // 从资源读取 —— 第一版把「围棋练习」硬编码在这里，
                            // 改了 app_name 界面却没变
                            text = stringResource(R.string.app_name),
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "和黑猪大人下棋 · 从 9 路开始",
                            color = TextSecondary,
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    // ---- 主按钮区：高度自适应 ----
                    Column(
                        modifier = Modifier.width(340.dp).fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        TvButton(
                            label = "开始对局",
                            subtitle = "选好棋盘和难度就能下",
                            onClick = onStartGame,
                            focusRequester = focusRequesters[0],
                        )
                        TvButton(
                            label = "历史战绩",
                            subtitle = if (records.isEmpty()) "还没有下过棋" else "已下 ${records.size} 局",
                            onClick = onHistory,
                            focusRequester = focusRequesters[1],
                        )
                        TvButton(
                            label = "设置",
                            onClick = onSettings,
                            focusRequester = focusRequesters[2],
                        )
                        TvButton(
                            label = "退出",
                            onClick = onExit,
                            danger = true,
                            focusRequester = focusRequesters[3],
                        )
                    }

                    Spacer(Modifier.width(32.dp))

                    // ---- 右侧战绩概览 ----
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        HomeSummary(records)
                    }
                }
            }
        }
    }
}

/** 首页右侧的成长概览。让孩子每次进来都能看见自己的进步。 */
@Composable
private fun HomeSummary(records: List<GameRecord>) {
    val stats = remember(records) { StatsCalculator.overall(records) }
    val streak = remember(records) { StatsCalculator.currentStreak(records) }
    val best = remember(records) { StatsCalculator.longestWinStreak(records) }
    val recent = remember(records) { StatsCalculator.recentResults(records, 10) }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Surface)
            .border(1.dp, SurfaceBorder, RoundedCornerShape(16.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(text = "我的成绩", color = TextSecondary)

        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            StatBlock("总对局", "${stats.total}")
            StatBlock("胜", "${stats.wins}")
            StatBlock("负", "${stats.losses}")
            StatBlock("胜率", if (stats.hasData) "${(stats.winRate * 100).toInt()}%" else "—")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            StatBlock("当前连胜", if (streak > 0) "$streak" else "—")
            StatBlock("最长连胜", "$best")
        }

        Column {
            Text(text = "最近 10 局", color = TextDim)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (recent.isEmpty()) {
                    Text(text = "还没有记录", color = TextDim)
                } else {
                    recent.forEach { result ->
                        val color = when {
                            result > 0 -> Accent
                            result < 0 -> WoodDark
                            else -> TextDim
                        }
                        Box(
                            modifier = Modifier
                                .width(20.dp)
                                .height(20.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(color),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatBlock(label: String, value: String) {
    Column {
        Text(text = value, color = TextPrimary, fontWeight = FontWeight.Bold)
        Text(text = label, color = TextDim)
    }
}

/**
 * 对局设置页。
 *
 * 焦点单元共 4 个：3 个选择行 + 1 个按钮行。上下键由页面统一移动焦点，
 * 左右键**不拦截**（放给当前行改值）。
 *
 * 初始焦点落在第一行选择器而不是「开始对局」按钮：用户进这一页绝大多数时候
 * 是要先改设置。
 */
@Composable
fun NewGameScreen(
    initialSize: Int,
    initialDifficulty: Difficulty,
    initialColor: Stone,
    onStart: (boardSize: Int, difficulty: Difficulty, playerColor: Stone) -> Unit,
    onBack: () -> Unit,
) {
    val sizes = remember { listOf(9, 13, 19) }
    var sizeIndex by remember { mutableStateOf(sizes.indexOf(initialSize).coerceAtLeast(0)) }
    var diffIndex by remember {
        mutableStateOf(Difficulty.entries.indexOf(initialDifficulty).coerceAtLeast(0))
    }
    var colorIndex by remember { mutableStateOf(if (initialColor == Stone.BLACK) 0 else 1) }

    val unitCount = 4
    val focusRequesters = remember { List(unitCount) { FocusRequester() } }
    var focusIndex by remember { mutableStateOf(0) }

    LaunchedEffect(focusIndex) {
        runCatching { focusRequesters[focusIndex].requestFocus() }
    }

    // 返回键回主菜单，而不是把整个应用关掉
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
            hint = "上下键换焦点 · 左右键改值 · 到「开始对局」按 OK",
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 三行选择器：SpaceEvenly 自适应，避免第 3 行被屏幕底部截断
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.SpaceEvenly,
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
                                else -> "标准棋盘。黑猪大人在这个尺寸上会弱一些，但更接近正式对局"
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
                        descriptionOf = { it.description },
                        onSelect = { diffIndex = it },
                        focusRequester = focusRequesters[1],
                    )
                    TvOptionRow(
                        title = "黑猪勇士执什么颜色",
                        options = listOf(Stone.BLACK, Stone.WHITE),
                        selectedIndex = colorIndex,
                        labelOf = { if (it == Stone.BLACK) "黑棋（先下）" else "白棋（后下）" },
                        descriptionOf = {
                            if (it == Stone.BLACK) "黑棋先下，通常更容易赢一点"
                            else "白棋后下，黑猪大人先走"
                        },
                        onSelect = { colorIndex = it },
                        focusRequester = focusRequesters[2],
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TvButton(
                        label = "开始对局",
                        onClick = {
                            onStart(
                                sizes[sizeIndex],
                                Difficulty.entries[diffIndex],
                                if (colorIndex == 0) Stone.BLACK else Stone.WHITE,
                            )
                        },
                        focusRequester = focusRequesters[3],
                    )
                    TvButton(label = "返回", onClick = onBack)
                }
            }
        }
    }
}
