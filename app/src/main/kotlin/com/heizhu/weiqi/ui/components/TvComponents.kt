package com.heizhu.weiqi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.heizhu.weiqi.ui.theme.Accent
import com.heizhu.weiqi.ui.theme.Background
import com.heizhu.weiqi.ui.theme.CursorRing
import com.heizhu.weiqi.ui.theme.Danger
import com.heizhu.weiqi.ui.theme.Surface
import com.heizhu.weiqi.ui.theme.SurfaceBorder
import com.heizhu.weiqi.ui.theme.SurfaceFocused
import com.heizhu.weiqi.ui.theme.TextDim
import com.heizhu.weiqi.ui.theme.TextPrimary
import com.heizhu.weiqi.ui.theme.TextSecondary

/**
 * 通用可聚焦按钮。
 *
 * 用基础的 `focusable` + `onFocusChanged` 自己实现，而不用 Compose TV 的高阶组件 ——
 * 大屏按钮形态特殊（要有明显焦点态、要大），自己控制更容易做到位。
 *
 * 焦点态做了两件事：**变色 + 放大 1.04 倍**。远距离观看时，单靠变色不够醒目，
 * 尺寸变化是人眼在余光里也能捕捉到的信号。
 */
@Composable
fun TvButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }

    val background = when {
        !enabled -> Surface.copy(alpha = 0.5f)
        focused -> SurfaceFocused
        else -> Surface
    }
    val borderColor = when {
        !enabled -> SurfaceBorder.copy(alpha = 0.4f)
        focused -> CursorRing
        danger -> Danger.copy(alpha = 0.55f)
        else -> SurfaceBorder
    }
    val labelColor = when {
        !enabled -> TextDim
        focused -> TextPrimary
        danger -> Danger
        else -> TextSecondary
    }

    Box(
        modifier = modifier
            .scale(if (focused && enabled) 1.04f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(if (focused) 3.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .focusable(enabled)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val isConfirm = event.key == Key.Enter ||
                    event.key == Key.DirectionCenter ||
                    event.key == Key.NumPadEnter
                if (isConfirm && enabled) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .padding(horizontal = 26.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(
                    text = label,
                    color = labelColor,
                    fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal,
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(text = subtitle, color = TextDim)
                }
            }
        }
    }
}

/**
 * 横向选项选择器（棋盘尺寸、难度等）。
 *
 * 左右方向键切换选项，选项本身不单独持有焦点 —— 让一整行作为**一个焦点单元**，
 * 这样在遥控器上「换选项」只需要按左右，不用先确认焦点落在第几个选项上，
 * 对小孩的心智负担小很多。
 */
@Composable
fun <T> TvOptionRow(
    title: String,
    options: List<T>,
    selectedIndex: Int,
    labelOf: (T) -> String,
    descriptionOf: (T) -> String? = { null },
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showFocusHint: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = if (focused) Accent else TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (focused) SurfaceFocused.copy(alpha = 0.6f) else Color.Transparent)
                .border(
                    if (focused) 2.dp else 1.dp,
                    if (focused) CursorRing else SurfaceBorder.copy(alpha = 0.5f),
                    RoundedCornerShape(12.dp),
                )
                .focusable()
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> {
                            if (selectedIndex > 0) onSelect(selectedIndex - 1)
                            true
                        }
                        Key.DirectionRight -> {
                            if (selectedIndex < options.size - 1) onSelect(selectedIndex + 1)
                            true
                        }
                        else -> false
                    }
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (isSelected) Accent.copy(alpha = 0.22f) else Surface.copy(alpha = 0.5f))
                        .border(
                            if (isSelected) 2.dp else 1.dp,
                            if (isSelected) Accent else SurfaceBorder.copy(alpha = 0.6f),
                            RoundedCornerShape(9.dp),
                        )
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = labelOf(option),
                        color = if (isSelected) TextPrimary else TextDim,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
        val desc = descriptionOf(options[selectedIndex])
        if (desc != null) {
            Spacer(Modifier.height(6.dp))
            Text(text = desc, color = TextDim)
        } else if (showFocusHint) {
            Spacer(Modifier.height(6.dp))
            Text(text = " ", color = TextDim)
        }
    }
}

/**
 * 页面外壳：统一背景、内边距、标题区和底部按键提示。
 *
 * 底部提示条是 TV 应用的必备元素 —— 遥控器没有屏幕上的按钮，用户必须
 * 随时能看见「我现在能按什么」。
 */
@Composable
fun TvScaffold(
    title: String? = null,
    subtitle: String? = null,
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(horizontal = 40.dp, vertical = 28.dp),
    ) {
        if (title != null) {
            Text(text = title, color = TextPrimary, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(text = subtitle, color = TextSecondary)
            }
            Spacer(Modifier.height(20.dp))
        }
        Box(modifier = Modifier.weight(1f)) { content() }
        if (hint != null) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Accent),
                )
                Spacer(Modifier.width(10.dp))
                Text(text = hint, color = TextDim)
            }
        }
    }
}

/** 让某个元素在进入页面时自动获得焦点。 */
@Composable
fun RequestFocusOnEnter(focusRequester: FocusRequester) {
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }
}
