package com.heizhu.weiqi.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.heizhu.weiqi.core.ai.Difficulty
import com.heizhu.weiqi.data.GameRecord
import com.heizhu.weiqi.data.StatsCalculator
import com.heizhu.weiqi.data.WinStats
import com.heizhu.weiqi.ui.components.RequestFocusOnEnter
import com.heizhu.weiqi.ui.components.TvButton
import com.heizhu.weiqi.ui.components.TvScaffold
import com.heizhu.weiqi.ui.theme.Accent
import com.heizhu.weiqi.ui.theme.Danger
import com.heizhu.weiqi.ui.theme.Surface
import com.heizhu.weiqi.ui.theme.SurfaceBorder
import com.heizhu.weiqi.ui.theme.SurfaceFocused
import com.heizhu.weiqi.ui.theme.TextDim
import com.heizhu.weiqi.ui.theme.TextPrimary
import com.heizhu.weiqi.ui.theme.TextSecondary
import com.heizhu.weiqi.ui.theme.Warning
import kotlinx.coroutines.launch

/**
 * 历史战绩页。
 *
 * 上半部分是按难度、按棋盘尺寸分组的胜率 —— 这是让孩子看见「我在哪一档
 * 变强了」的地方，比一个笼统的总胜率有意义得多。
 *
 * 下半部分是逐局列表，最近的在最上面。**列表用上下方向键滚动**：
 * 列表项本身不承载焦点，避免焦点在几十个条目间跳来跳去、还得一路按过去。
 */
@Composable
fun HistoryScreen(
    records: List<GameRecord>,
    onBack: () -> Unit,
    onClear: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    val backFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 记录按时间倒序展示：最新的在最上面
    val ordered = remember(records) { records.sortedByDescending { it.playedAt } }
    val overall = remember(records) { StatsCalculator.overall(records) }
    val streak = remember(records) { StatsCalculator.currentStreak(records) }
    val byDifficulty = remember(records) {
        StatsCalculator.byDifficulty(records, Difficulty.entries.map { it.id }) { id ->
            Difficulty.fromId(id).displayName
        }
    }
    val bySize = remember(records) {
        StatsCalculator.byBoardSize(records, listOf(9, 13, 19))
    }

    val pageFocus = remember { FocusRequester() }
    RequestFocusOnEnter(pageFocus)

    // 返回键回主菜单
    BackHandler { onBack() }

    TvScaffold(
        title = "历史战绩",
        subtitle = if (records.isEmpty()) "还没有下过棋" else "共 ${records.size} 局，最近的排在最前面",
        hint = "上下键滚动 · 返回键回主菜单",
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(pageFocus)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> {
                            scope.launch { listState.scrollBy(-120f) }
                            true
                        }
                        Key.DirectionDown -> {
                            scope.launch { listState.scrollBy(120f) }
                            true
                        }
                        else -> false
                    }
                },
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // ---- 左：分组统计 ----
                Column(
                    modifier = Modifier.width(400.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    StatsCard("总览", listOf(
                        "总对局" to "${overall.total}",
                        "胜 / 负 / 和" to "${overall.wins} / ${overall.losses} / ${overall.draws}",
                        "胜率" to (if (overall.hasData) "${(overall.winRate * 100).toInt()}%" else "—"),
                        "当前连胜" to (if (streak > 0) "$streak 连胜" else "—"),
                        "最长连胜" to "${StatsCalculator.longestWinStreak(records)} 连胜",
                    ))

                    if (byDifficulty.isNotEmpty()) {
                        GroupCard("按难度", byDifficulty)
                    }
                    if (bySize.isNotEmpty()) {
                        GroupCard("按棋盘", bySize)
                    }

                    Spacer(Modifier.weight(1f))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton(label = "返回主菜单", onClick = onBack, focusRequester = backFocus)
                        if (records.isNotEmpty()) {
                            TvButton(
                                label = if (confirmClear) "再按一次确认清空" else "清空记录",
                                onClick = { if (confirmClear) onClear() else confirmClear = true },
                                danger = true,
                                subtitle = if (confirmClear) "清空后无法恢复" else null,
                            )
                        }
                    }
                }

                Spacer(Modifier.width(28.dp))

                // ---- 右：逐局列表 ----
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(ordered, key = { it.id }) { record ->
                        RecordRow(record)
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupCard(title: String, groups: List<com.heizhu.weiqi.data.GroupedStats>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface)
            .border(1.dp, SurfaceBorder, RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        Text(text = title, color = TextSecondary)
        Spacer(Modifier.height(10.dp))
        groups.forEach { group ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = group.label, color = TextPrimary)
                Text(
                    text = "${group.stats.wins}/${group.stats.total}  " +
                        "${(group.stats.winRate * 100).toInt()}%",
                    color = rateColor(group.stats),
                )
            }
        }
    }
}

@Composable
private fun StatsCard(title: String, rows: List<Pair<String, String>>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface)
            .border(1.dp, SurfaceBorder, RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        Text(text = title, color = TextSecondary)
        Spacer(Modifier.height(10.dp))
        rows.forEach { (label, value) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = label, color = TextDim)
                Text(text = value, color = TextPrimary, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun RecordRow(record: GameRecord) {
    val (resultText, resultColor) = when {
        record.playerWon > 0 -> "胜" to Accent
        record.playerWon < 0 -> "负" to Warning
        else -> "和" to TextDim
    }
    val difficulty = Difficulty.fromId(record.difficultyId)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .border(1.dp, SurfaceBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(resultColor.copy(alpha = 0.18f))
                .border(1.dp, resultColor.copy(alpha = 0.6f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = resultText, color = resultColor, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${record.boardSize} 路 · ${difficulty.displayName} · " +
                    (if (record.playerColorCode == 1) "执黑" else "执白"),
                color = TextPrimary,
            )
            Text(
                text = "黑 ${record.blackTotal} : ${record.whiteTotal} 白 · ${record.moveCount} 手" +
                    (if (record.endedByResign) " · 认输" else "") +
                    (if (record.usedUndo) " · 悔棋 ${record.undoCount} 次" else ""),
                color = TextDim,
            )
        }
        Text(text = relativeTime(record.playedAt), color = TextSecondary)
    }
}

private fun rateColor(stats: WinStats): androidx.compose.ui.graphics.Color = when {
    !stats.hasData -> TextDim
    stats.winRate >= 0.6f -> Accent
    stats.winRate >= 0.35f -> TextPrimary
    else -> Warning
}

/** 相对时间。孩子对「3 分钟前」的理解远好于「2026-10-01 19:22」。 */
private fun relativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val minutes = diff / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        hours < 24 -> "$hours 小时前"
        days < 30 -> "$days 天前"
        else -> "$days 天前"
    }
}