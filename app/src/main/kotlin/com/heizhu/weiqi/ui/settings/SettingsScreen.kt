package com.heizhu.weiqi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.heizhu.weiqi.data.CursorSpeed
import com.heizhu.weiqi.data.SettingsStore
import com.heizhu.weiqi.ui.components.RequestFocusOnEnter
import com.heizhu.weiqi.ui.components.TvButton
import com.heizhu.weiqi.ui.components.TvOptionRow
import com.heizhu.weiqi.ui.components.TvScaffold
import com.heizhu.weiqi.ui.theme.Surface
import com.heizhu.weiqi.ui.theme.SurfaceBorder
import com.heizhu.weiqi.ui.theme.TextDim
import com.heizhu.weiqi.ui.theme.TextSecondary

/**
 * 设置页。
 *
 * 「光标移动速度」这一项是给孩子调的 —— 手小、容易按过头的小朋友适合「慢」档。
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

    TvScaffold(
        title = "设置",
        hint = "上下键换选项 · 左右键改值",
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
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
                )
            }

            Spacer(Modifier.height(30.dp))

            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surface)
                    .border(1.dp, SurfaceBorder, RoundedCornerShape(14.dp))
                    .padding(18.dp),
            ) {
                Text(text = "关于", color = TextSecondary)
                Spacer(Modifier.height(8.dp))
                InfoRow("应用", "黑猪围棋 1.0")
                InfoRow("对手", "黑猪大人（本地 AI，无需联网）")
                InfoRow("战绩", "只保存在这台电视上")
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "提示：黑猪大人的棋力受电视算力限制，19 路盘上大约相当于初学水平。" +
                        "9 路和 13 路是它表现最好的地方。",
                    color = TextDim,
                )
            }

            Spacer(Modifier.height(24.dp))
            Row {
                TvButton(label = "返回主菜单", onClick = onBack, focusRequester = backFocus)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = TextDim)
        Text(text = value, color = TextSecondary)
    }
}