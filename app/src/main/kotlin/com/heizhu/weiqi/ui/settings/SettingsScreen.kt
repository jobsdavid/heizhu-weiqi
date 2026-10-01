package com.heizhu.weiqi.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.heizhu.weiqi.data.CursorSpeed
import com.heizhu.weiqi.data.SettingsStore
import com.heizhu.weiqi.ui.components.SectionCard
import com.heizhu.weiqi.ui.components.TvButton
import com.heizhu.weiqi.ui.components.TvButtonStyle
import com.heizhu.weiqi.ui.components.TvOptionRow
import com.heizhu.weiqi.ui.components.TvScaffold
import com.heizhu.weiqi.ui.theme.AccentWarm
import com.heizhu.weiqi.ui.theme.FocusRing
import com.heizhu.weiqi.ui.theme.TextDim
import com.heizhu.weiqi.ui.theme.TextPrimary
import com.heizhu.weiqi.ui.theme.TextSecondary

/**
 * 设置页。
 *
 * 「光标移动速度」是给孩子调的 —— 手小、容易按过头的小朋友适合「慢」档。
 *
 * ## 焦点必须由页面自己管（2026-10-01 真机踩坑）
 *
 * 这一页原来是唯一没有显式焦点管理的页面，靠 Compose 默认的**空间导航**。
 * 结果是：「音效」和「落子振动」并排时，用户想按右键挪到右边那个开关 ——
 * 而左右键被选项行自己吃掉了（改成值），于是**够不到落子振动**，
 * 看起来就像「这个设置根本无法修改」。
 *
 * 现在和其他页面统一：页面持有每个可聚焦单元的 [FocusRequester] 与当前索引，
 * **上下键换位置、左右键改值**，并且每一行都是**竖排**的（视觉上右边没有东西，
 * 用户自然会用上下键）。
 *
 * ## 布局预算（1080p）
 * 左列三行选择器各约 234px + 两个 10dp 间距 = 742px，可用约 770px；
 * 右列「关于」卡片 + 返回按钮约 530px。见 [TvOptionRow] 里关于行高的注释 ——
 * 行高一改，这两处预算都要重算。
 */
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    onBack: () -> Unit,
) {
    var soundEnabled by remember { mutableStateOf(settings.soundEnabled) }
    var cursorSpeed by remember { mutableStateOf(CursorSpeed.entries[settings.cursorSpeed]) }
    var hapticEnabled by remember { mutableStateOf(settings.hapticEnabled) }

    // 4 个焦点单元：音效 / 落子振动 / 光标移动速度 / 返回主菜单
    val unitCount = 4
    val focusRequesters = remember { List(unitCount) { FocusRequester() } }
    var focusIndex by remember { mutableStateOf(0) }

    LaunchedEffect(focusIndex) {
        runCatching { focusRequesters[focusIndex].requestFocus() }
    }

    // 返回键回主菜单，而不是退出应用
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
                    // 左右键不拦截：放给当前那一行改值
                    else -> false
                }
            },
    ) {
        TvScaffold(
            title = "设置",
            hint = "上下键换位置 · 左右键改选项",
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // ---- 左列：三个可调项，**竖排** ----
                Column(
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TvOptionRow(
                        title = "音效",
                        options = listOf(true, false),
                        selectedIndex = if (soundEnabled) 0 else 1,
                        labelOf = { if (it) "开" else "关" },
                        descriptionOf = { enabled ->
                            if (enabled) "落子和吃子都会有声音" else "安静下棋"
                        },
                        onSelect = { index ->
                            soundEnabled = index == 0
                            settings.soundEnabled = soundEnabled
                        },
                        focusRequester = focusRequesters[0],
                        inlineDescription = true,
                    )
                    TvOptionRow(
                        title = "落子振动",
                        options = listOf(false, true),
                        selectedIndex = if (hapticEnabled) 1 else 0,
                        labelOf = { if (it) "开" else "关" },
                        descriptionOf = {
                            if (it) "落子时轻微振动（需要遥控器支持）" else "不振动"
                        },
                        onSelect = { index ->
                            hapticEnabled = index == 1
                            settings.hapticEnabled = hapticEnabled
                        },
                        focusRequester = focusRequesters[1],
                        inlineDescription = true,
                    )
                    TvOptionRow(
                        title = "光标移动速度",
                        options = CursorSpeed.entries,
                        selectedIndex = CursorSpeed.entries.indexOf(cursorSpeed),
                        labelOf = { it.label },
                        descriptionOf = { speed ->
                            when (speed) {
                                CursorSpeed.SLOW -> "按住方向键后慢慢加速，适合手小的孩子"
                                CursorSpeed.NORMAL -> "默认速度，大部分时候都合适"
                                CursorSpeed.FAST -> "按住方向键后移动很快，适合已经用熟了的人"
                            }
                        },
                        onSelect = { index ->
                            cursorSpeed = CursorSpeed.entries[index]
                            settings.cursorSpeed = index
                        },
                        focusRequester = focusRequesters[2],
                        inlineDescription = true,
                    )
                }

                Spacer(Modifier.width(24.dp))

                // ---- 右列：关于 + 返回 ----
                Column(modifier = Modifier.width(400.dp).fillMaxSize()) {
                    SectionCard(title = "关于", accent = FocusRing) {
                        InfoRow("应用", "黑猪围棋 1.0")
                        InfoRow("对手", "黑猪大人（本机 AI）")
                        InfoRow("联网", "不需要网络")
                        InfoRow("战绩", "只存在这台电视上")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "黑猪大人的棋力受电视算力限制，9 路和 13 路是它表现最好的地方。",
                            color = TextDim,
                            fontSize = 19.sp,
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    TvButton(
                        label = "返回主菜单",
                        style = TvButtonStyle.PRIMARY,
                        onClick = onBack,
                        focusRequester = focusRequesters[3],
                        fillWidth = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(6.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(AccentWarm.copy(alpha = 0.8f)),
        )
        Spacer(Modifier.width(10.dp))
        Text(text = label, color = TextDim, fontSize = 20.sp)
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
