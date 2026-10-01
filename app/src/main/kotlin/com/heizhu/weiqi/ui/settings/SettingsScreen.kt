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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.heizhu.weiqi.data.CursorSpeed
import com.heizhu.weiqi.data.SettingsStore
import com.heizhu.weiqi.ui.components.RequestFocusOnEnter
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
 * 「光标移动速度」这一项是给孩子调的 —— 手小、容易按过头的小朋友适合「慢」档。
 *
 * ## 布局预算（1080p）
 * 可用内容高度 ≈ 366dp。左列三行选择器各 ~111dp（含间距）＝ 357dp，
 * 右列「关于」卡片 + 返回按钮 ≈ 279dp。两列都留有余量，不会重演设置页被裁切的老问题。
 */
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    onBack: () -> Unit,
) {
    var soundEnabled by remember { mutableStateOf(settings.soundEnabled) }
    var cursorSpeed by remember { mutableStateOf(CursorSpeed.entries[settings.cursorSpeed]) }
    var hapticEnabled by remember { mutableStateOf(settings.hapticEnabled) }
    val backFocus = remember { FocusRequester() }
    RequestFocusOnEnter(backFocus)

    // 返回键回主菜单，而不是退出应用
    BackHandler { onBack() }

    TvScaffold(
        title = "设置",
        hint = "上下键换位置 · 左右键改选项",
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // ---- 左列：三个可调项 ----
            //
            // 两个开关并排、速度行独占：三行竖排在 1080p 上放不下
            // （需要 818px，可用只有 770px），第三行会被压扁成 38px 高、说明整行消失 ——
            // 跟「新对局」旧版第三行被裁切是同一个坑。并排后只要 536px，余量 234px。
            // 并排后每行只有约 444px 宽，所以这俩开关的说明文字必须短，改长会被省略号截断。
            Column(
                modifier = Modifier.weight(1f).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvOptionRow(
                        title = "音效",
                        options = listOf(true, false),
                        selectedIndex = if (soundEnabled) 0 else 1,
                        labelOf = { if (it) "开" else "关" },
                        descriptionOf = { enabled ->
                            if (enabled) "落子和吃子会有声音" else "安静下棋"
                        },
                        onSelect = { index ->
                            soundEnabled = index == 0
                            settings.soundEnabled = soundEnabled
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TvOptionRow(
                        title = "落子振动",
                        options = listOf(false, true),
                        selectedIndex = if (hapticEnabled) 1 else 0,
                        labelOf = { if (it) "开" else "关" },
                        descriptionOf = {
                            if (it) "落子时振动（看遥控器）" else "不振动"
                        },
                        onSelect = { index ->
                            hapticEnabled = index == 1
                            settings.hapticEnabled = hapticEnabled
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
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
                    focusRequester = backFocus,
                    fillWidth = true,
                )
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
