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
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.heizhu.weiqi.core.ai.Difficulty
import com.heizhu.weiqi.data.GameRecord
import com.heizhu.weiqi.data.StatsCalculator
import com.heizhu.weiqi.ui.components.RequestFocusOnEnter
import com.heizhu.weiqi.ui.components.SectionCard
import com.heizhu.weiqi.ui.components.StatTile
import com.heizhu.weiqi.ui.components.StoneDot
import com.heizhu.weiqi.ui.components.Tag
import com.heizhu.weiqi.ui.components.TvButton
import com.heizhu.weiqi.ui.components.TvButtonStyle
import com.heizhu.weiqi.ui.components.TvScaffold
import com.heizhu.weiqi.ui.theme.Accent
import com.heizhu.weiqi.ui.theme.AccentWarm
import com.heizhu.weiqi.ui.theme.Background
import com.heizhu.weiqi.ui.theme.FocusRing
import com.heizhu.weiqi.ui.theme.Surface
import com.heizhu.weiqi.ui.theme.SurfaceAlt
import com.heizhu.weiqi.ui.theme.SurfaceBorder
import com.heizhu.weiqi.ui.theme.TextDim
import com.heizhu.weiqi.ui.theme.TextPrimary
import com.heizhu.weiqi.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * 历史战绩页。
 *
 * 上半部分是按难度、按棋盘尺寸分组的胜率 —— 这是让孩子看见「我在哪一档
 * 变强了」的地方，比一个笼统的总胜率有意义得多。
 *
 * 下半部分是逐局列表，最近的在最上面。**列表用上下方向键滚动**：
 * 列表项本身不承载焦点，避免焦点在几十个条目间跳来跳去、还得一路按过去。
 *
 * ## 布局预算（1080p）
 * 左栏三块统计 + 按钮 = 354dp，可用内容高度 ≈ 366dp，留有余量。
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
        // 尺寸分组**按记录里实际出现过的尺寸**算，不硬编码一份清单：
        // 硬编码的那份（曾经写的是 9/13/19）与「哪些尺寸能玩」是两回事 ——
        // 它既会把没玩过的尺寸列成空组，又会在开放/撤下某个尺寸时静默对不上。
        // 能玩哪些尺寸的唯一来源是 NetAssets.supportedSizes（首页选择器读的就是它）。
        StatsCalculator.byBoardSize(records, records.map { it.boardSize }.distinct().sorted())
    }

    val pageFocus = remember { FocusRequester() }
    RequestFocusOnEnter(pageFocus)

    // 返回键回主菜单
    BackHandler { onBack() }

    TvScaffold(
        title = "历史战绩",
        hint = if (records.isEmpty()) "还没有下过棋 · 返回键回主菜单"
        else "共 ${records.size} 局 · 上下键滚动 · 返回键回主菜单",
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // ---- 左：统计侧栏 ----
            Column(
                modifier = Modifier.width(520.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 总览刻意不套标题、内边距减半：这张卡只是四个数字，
                // 而下面两张分组卡的行数**会随记录变多而增长**（最多 5 档难度 + 3 种棋盘），
                // 高度必须优先留给它们 —— 否则记录一多，分组卡就会像以前那样被压扁。
                // 总览刻意做成**一行紧凑的「标签 数值」**，不用四块大数字砖。
                //
                // 原因：下面两张分组卡的行数是**数据决定的**（按难度最多 5 档、按棋盘最多 3 种），
                // 高度必须留给它们。本机 UI 自测实测：用四块砖时，「按难度」第 5 行
                // （大师）会被压成 **0px** —— 整行直接消失，孩子看不到自己在大师档的战绩。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Surface.copy(alpha = 0.92f))
                        .border(2.dp, SurfaceBorder, RoundedCornerShape(18.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SumItem("总对局", "${overall.total}")
                    SumItem("胜", "${overall.wins}", Accent)
                    SumItem("负", "${overall.losses}", AccentWarm)
                    SumItem(
                        "胜率",
                        if (overall.hasData) "${(overall.winRate * 100).toInt()}%" else "—",
                        FocusRing,
                    )
                }

                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (byDifficulty.isNotEmpty()) {
                        GroupCard("按难度", byDifficulty, Modifier.weight(1f).fillMaxHeight())
                    }
                    if (bySize.isNotEmpty()) {
                        GroupCard("按棋盘", bySize, Modifier.weight(1f).fillMaxHeight())
                    }
                    if (byDifficulty.isEmpty() && bySize.isEmpty()) {
                        SectionCard(title = "数据", modifier = Modifier.weight(1f)) {
                            Text(text = "下完第一局就有统计了", color = TextDim, fontSize = 21.sp)
                            Spacer(Modifier.height(6.dp))
                            Text(text = "当前连胜 ${if (streak > 0) "$streak" else "—"}", color = TextSecondary, fontSize = 21.sp)
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(
                        label = "返回主菜单",
                        style = TvButtonStyle.PRIMARY,
                        onClick = onBack,
                        focusRequester = backFocus,
                    )
                    if (records.isNotEmpty()) {
                        TvButton(
                            label = if (confirmClear) "再按一次确认清空" else "清空记录",
                            subtitle = if (confirmClear) "清空后无法恢复" else null,
                            style = TvButtonStyle.DANGER,
                            onClick = { if (confirmClear) onClear() else confirmClear = true },
                        )
                    }
                }
            }

            Spacer(Modifier.width(22.dp))

            // ---- 右：逐局列表 ----
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .focusRequester(pageFocus)
                    .focusable()
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionUp -> {
                                scope.launch { listState.scrollBy(-140f) }
                                true
                            }
                            Key.DirectionDown -> {
                                scope.launch { listState.scrollBy(140f) }
                                true
                            }
                            else -> false
                        }
                    },
            ) {
                if (ordered.isEmpty()) {
                    SectionCard(modifier = Modifier.fillMaxWidth()) {
                        Text(text = "还没有下过棋", color = TextSecondary, fontSize = 26.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "回到首页按「开始对局」，下完一局这里就会记下来",
                            color = TextDim,
                            fontSize = 21.sp,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
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
}

/**
 * 分组统计卡。
 *
 * 行数**由数据决定**（按难度最多 5 行、按棋盘最多 3 行），所以这里不能用「够用就行」
 * 的内边距 —— 记录一多就会把最后一行压扁（布局闸门抓到过：行高 53px 被压成 21px/9px）。
 * 这里刻意把内边距与行距压小，把高度预算留给行数。
 */
/** 总览里的一个「标签 + 数值」。刻意做得扁 —— 见上面总览那段注释。 */
@Composable
private fun SumItem(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = TextPrimary) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(text = label, color = TextDim, fontSize = 19.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            color = valueColor,
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

@Composable
private fun GroupCard(
    title: String,
    groups: List<com.heizhu.weiqi.data.GroupedStats>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Surface.copy(alpha = 0.92f))
            .border(2.dp, SurfaceBorder, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text = title, color = Accent, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        groups.forEach { group ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = group.label, color = TextPrimary, fontSize = 20.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${group.stats.wins}/${group.stats.total}",
                        color = TextDim,
                        fontSize = 19.sp,
                    )
                    Spacer(Modifier.width(10.dp))
                    // 百分比刻意**不用 Tag**：Tag 自带内边距 + 描边，会把行高从 40px 撑到
                    // 54px，5 行就多出 70px —— 布局闸门实测按难度卡第 5 行会被压成 11px。
                    // 行数是数据决定的（最多 5 档），所以每行的高度必须抠。
                    Text(
                        text = "${(group.stats.winRate * 100).toInt()}%",
                        color = rateColor(group.stats),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordRow(record: GameRecord) {
    val (resultText, resultColor) = when {
        record.playerWon > 0 -> "胜" to Accent
        record.playerWon < 0 -> "负" to AccentWarm
        else -> "和" to TextDim
    }
    val difficulty = Difficulty.fromId(record.difficultyId)
    val isBlack = record.playerColorCode == 1

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Surface)
            .border(2.dp, SurfaceBorder, RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 用棋子表示「我执什么颜色」，比文字更快认
        StoneDot(isBlack = isBlack, size = 38.dp)
        Spacer(Modifier.width(14.dp))
        Box(
            modifier = Modifier
                .width(46.dp)
                .height(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(resultColor.copy(alpha = 0.18f))
                .border(2.dp, resultColor.copy(alpha = 0.7f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = resultText, color = resultColor, fontSize = 24.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${record.boardSize} 路 · ${difficulty.displayName}",
                    color = TextPrimary,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(10.dp))
                if (record.endedByResign) Tag(text = "认输", color = TextDim)
                if (record.usedUndo) {
                    Spacer(Modifier.width(6.dp))
                    Tag(text = "悔棋 ${record.undoCount}", color = SurfaceAlt)
                }
            }
            Text(
                // 认输局不数子（见 ResultScreen 注释），存档里的比分是无意义的，
                // 所以这里也不能显示 —— 否则历史列表里会出现「黑 361 : 0 白」
                text = if (record.endedByResign) {
                    "${record.moveCount} 手 · 中盘认输"
                } else {
                    "黑 ${record.blackTotal} : ${record.whiteTotal} 白 · ${record.moveCount} 手"
                },
                color = TextDim,
                fontSize = 20.sp,
            )
        }
        Text(text = relativeTime(record.playedAt), color = TextSecondary, fontSize = 21.sp)
    }
}

private fun rateColor(stats: com.heizhu.weiqi.data.WinStats): androidx.compose.ui.graphics.Color = when {
    !stats.hasData -> TextDim
    stats.winRate >= 0.6f -> Accent
    stats.winRate >= 0.35f -> TextPrimary
    else -> AccentWarm
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
        else -> "$days 天前"
    }
}
