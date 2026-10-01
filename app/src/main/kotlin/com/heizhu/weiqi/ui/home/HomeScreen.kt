package com.heizhu.weiqi.ui.home

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.focusable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.tv.material3.Text
import com.heizhu.weiqi.core.ai.Difficulty
import com.heizhu.weiqi.core.rules.Stone
import com.heizhu.weiqi.data.GameRecord
import com.heizhu.weiqi.data.StatsCalculator
import com.heizhu.weiqi.ui.components.RequestFocusOnEnter
import com.heizhu.weiqi.ui.components.TvButton
import com.heizhu.weiqi.ui.components.TvOptionRow
import com.heizhu.weiqi.ui.components.TvScaffold
import com.heizhu.weiqi.ui.theme.Accent
import com.heizhu.weiqi.ui.theme.Surface
import com.heizhu.weiqi.ui.theme.SurfaceBorder
import com.heizhu.weiqi.ui.theme.SurfaceFocused
import com.heizhu.weiqi.ui.theme.TextDim
import com.heizhu.weiqi.ui.theme.TextPrimary
import com.heizhu.weiqi.ui.theme.TextSecondary
import com.heizhu.weiqi.ui.theme.WoodDark
import com.heizhu.weiqi.ui.theme.WoodLight

/**
 * 主菜单。
 *
 * 布局按「场景 S1：小孩自己开一局」优化 —— 「开始对局」是第一个、也是
 * 默认获得焦点的按钮，从开机到能落子只需要按一次 OK。
 */
@Composable
fun HomeScreen(
    records: List<GameRecord>,
    onStartGame: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onExit: () -> Unit,
) {
    val startFocus = remember { FocusRequester() }
    RequestFocusOnEnter(startFocus)

    TvScaffold(hint = "上下键选择 · OK 确认") {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- 标题 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(14.dp)
                        .height(64.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(WoodLight),
                )
                Spacer(Modifier.width(18.dp))
                Column {
                    Text(
                        text = "围棋练习",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "和黑猪大人下棋 · 从 9 路开始",
                        color = TextSecondary,
                    )
                }
            }

            Spacer(Modifier.height(36.dp))

            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                // ---- 主按钮区 ----
                Column(
                    modifier = Modifier.width(360.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    TvButton(
                        label = "开始对局",
                        subtitle = "选好棋盘和难度就能下",
                        onClick = onStartGame,
                        focusRequester = startFocus,
                    )
                    TvButton(
                        label = "历史战绩",
                        subtitle = if (records.isEmpty()) "还没有下过棋" else "已下 ${records.size} 局",
                        onClick = onHistory,
                    )
                    TvButton(label = "设置", onClick = onSettings)
                    TvButton(label = "退出", onClick = onExit, danger = true)
                }

                Spacer(Modifier.width(48.dp))

                // ---- 右侧战绩概览 ----
                Box(modifier = Modifier.weight(1f)) {
                    HomeSummary(records)
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
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(text = "我的成绩", color = TextSecondary)

        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            StatBlock("总对局", "${stats.total}")
            StatBlock("胜", "${stats.wins}")
            StatBlock("负", "${stats.losses}")
            StatBlock("胜率", if (stats.hasData) "${(stats.winRate * 100).toInt()}%" else "—")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
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
                                .width(22.dp)
                                .height(22.dp)
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
 * 三个选择器都用 [TvOptionRow]（整行一个焦点单元），难度选项带一行说明文字 ——
 * 家长和孩子都需要知道「入门」和「初级」到底差在哪。
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
    val startFocus = remember { FocusRequester() }

    TvScaffold(
        title = "新对局",
        hint = "上下键换选项 · 左右键改值 · 到「开始」按 OK",
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                TvOptionRow(
                    title = "棋盘大小",
                    options = sizes,
                    selectedIndex = sizeIndex,
                    labelOf = { "$it 路" },
                    descriptionOf = { size ->
                        when (size) {
                            9 -> "吃子快、一盘只要几分钟，最适合刚开始学"
                            13 -> "介于两者之间，练计算和死活的好地方"
                            else -> "标准棋盘。电脑在这个尺寸上会弱一些，但更接近正式对局"
                        }
                    },
                    onSelect = { sizeIndex = it },
                )
                TvOptionRow(
                    title = "对手难度",
                    options = Difficulty.entries,
                    selectedIndex = diffIndex,
                    labelOf = { it.displayName },
                    descriptionOf = { it.description },
                    onSelect = { diffIndex = it },
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
                )
            }

            Spacer(Modifier.height(30.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvButton(
                    label = "开始对局",
                    onClick = {
                        onStart(sizes[sizeIndex], Difficulty.entries[diffIndex],
                            if (colorIndex == 0) Stone.BLACK else Stone.WHITE)
                    },
                    focusRequester = startFocus,
                )
                TvButton(label = "返回", onClick = onBack)
            }
            RequestFocusOnEnter(startFocus)
        }
    }
}