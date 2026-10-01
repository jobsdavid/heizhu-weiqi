package com.heizhu.weiqi.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.heizhu.weiqi.ui.theme.Accent
import com.heizhu.weiqi.ui.theme.AccentWarm
import com.heizhu.weiqi.ui.theme.Background
import com.heizhu.weiqi.ui.theme.BackgroundTop
import com.heizhu.weiqi.ui.theme.Danger
import com.heizhu.weiqi.ui.theme.FocusRing
import com.heizhu.weiqi.ui.theme.RadiusButton
import com.heizhu.weiqi.ui.theme.RadiusCard
import com.heizhu.weiqi.ui.theme.RadiusChip
import com.heizhu.weiqi.ui.theme.SpacePageH
import com.heizhu.weiqi.ui.theme.SpacePageV
import com.heizhu.weiqi.ui.theme.StoneBlack
import com.heizhu.weiqi.ui.theme.StoneBlackGloss
import com.heizhu.weiqi.ui.theme.StoneOutline
import com.heizhu.weiqi.ui.theme.StoneWhite
import com.heizhu.weiqi.ui.theme.StoneWhiteGloss
import com.heizhu.weiqi.ui.theme.Surface
import com.heizhu.weiqi.ui.theme.SurfaceAlt
import com.heizhu.weiqi.ui.theme.SurfaceBorder
import com.heizhu.weiqi.ui.theme.SurfaceFocused
import com.heizhu.weiqi.ui.theme.TextDim
import com.heizhu.weiqi.ui.theme.TextPrimary
import com.heizhu.weiqi.ui.theme.TextSecondary

// ================================================================
// 焦点：全局唯一的入口
// ================================================================

/**
 * 把「焦点监听」和「可聚焦」绑成一个修饰符。
 *
 * ## 为什么必须封起来（2026-10-01 真机踩坑）
 *
 * `onFocusChanged` **必须挂在 `focusable()` 之前**。写成 `.focusable().onFocusChanged{}`
 * 会静默失效：按键照样能进来（`onKeyEvent` 正常、选项能被改），但焦点回调一次都不触发，
 * 表现就是「焦点到处走、界面上完全看不出焦点在哪」，而且没有任何报错。
 *
 * 这一条花了一整轮真机排查才定位（a11y 节点 bounds 在动、像素却零变化、强调色全屏不出现）。
 * 所以把顺序钉死在一个函数里，页面代码不再各自拼 modifier，从根上杜绝写反。
 *
 * @param onFocusChange 焦点变化回调。注意首次组合会先回调一次 false。
 */
fun Modifier.tvFocusable(
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true,
    onFocusChange: (Boolean) -> Unit,
): Modifier = this
    .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
    // ← 顺序不能动：onFocusChanged 必须在 focusable 之前
    .onFocusChanged { onFocusChange(it.isFocused) }
    .focusable(enabled)

/** 遥控器「确认」键（OK / 回车）。必须在 [tvFocusable] 之后接，才能收到按键。 */
fun Modifier.onConfirmKey(
    enabled: Boolean = true,
    onConfirm: () -> Unit,
): Modifier = this.onKeyEvent { event ->
    if (!enabled || event.type != KeyEventType.KeyDown) return@onKeyEvent false
    val isConfirm = event.key == Key.Enter ||
        event.key == Key.DirectionCenter ||
        event.key == Key.NumPadEnter
    if (isConfirm) {
        onConfirm()
        true
    } else {
        false
    }
}

// ================================================================
// 棋子（界面里所有「棋子形状」都走这里，保证一致）
// ================================================================

/** 画一颗带光泽的棋子。木色底和卡片底上都好看。 */
fun DrawScope.drawGlossyStone(center: Offset, radius: Float, isBlack: Boolean) {
    // 落影
    drawCircle(
        color = Color(0x40000000),
        radius = radius,
        center = Offset(center.x + radius * 0.10f, center.y + radius * 0.14f),
    )
    drawCircle(if (isBlack) StoneBlack else StoneWhite, radius, center)
    // 左上方高光：纯色圆片在木色底上很平，加了高光才有圆润的实物感
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(if (isBlack) StoneBlackGloss else StoneWhiteGloss, Color.Transparent),
            center = Offset(center.x - radius * 0.32f, center.y - radius * 0.38f),
            radius = radius * 1.15f,
        ),
        radius = radius,
        center = center,
    )
    drawCircle(
        color = StoneOutline,
        radius = radius,
        center = center,
        style = Stroke(width = radius * 0.09f),
    )
}

/** 界面用的棋子圆片（玩家面板、战绩行等）。 */
@Composable
fun StoneDot(isBlack: Boolean, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Canvas(modifier = modifier.size(size)) {
        drawGlossyStone(
            center = Offset(this.size.width / 2f, this.size.height / 2f),
            radius = this.size.minDimension / 2f,
            isBlack = isBlack,
        )
    }
}

// ================================================================
// 图标
// ================================================================

/** 用 Canvas 画图标，不引第三方图标库 —— 只需要这几个形状。 */
enum class TvIcon { PLAY, HISTORY, SETTINGS, EXIT, BOARD, LEVEL, STONE, HOME }

@Composable
fun TvIconView(icon: TvIcon, tint: Color, modifier: Modifier = Modifier, size: Dp = 30.dp) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val m = this.size.minDimension
        when (icon) {
            TvIcon.PLAY -> {
                drawPath(
                    Path().apply {
                        moveTo(w * 0.26f, h * 0.14f)
                        lineTo(w * 0.84f, h * 0.5f)
                        lineTo(w * 0.26f, h * 0.86f)
                        close()
                    },
                    tint,
                )
            }

            TvIcon.HISTORY -> {
                val c = Offset(w / 2f, h / 2f)
                val r = m * 0.38f
                drawCircle(tint, r, c, style = Stroke(width = m * 0.10f))
                drawLine(tint, c, Offset(c.x, c.y - r * 0.58f), m * 0.10f)
                drawLine(tint, c, Offset(c.x + r * 0.5f, c.y + r * 0.24f), m * 0.10f)
            }

            TvIcon.SETTINGS -> {
                // 三条滑杆：比画齿轮稳，缩到 26dp 也不会糊成一团
                val stroke = m * 0.09f
                listOf(0.24f to 0.70f, 0.5f to 0.35f, 0.76f to 0.58f).forEach { (y, knob) ->
                    val yy = h * y
                    drawLine(tint.copy(alpha = 0.55f), Offset(0f, yy), Offset(w, yy), stroke)
                    drawCircle(tint, stroke * 1.9f, Offset(w * knob, yy))
                }
            }

            TvIcon.EXIT -> {
                val stroke = m * 0.10f
                drawLine(tint, Offset(w * 0.52f, h * 0.5f), Offset(w * 0.88f, h * 0.5f), stroke)
                drawPath(
                    Path().apply {
                        moveTo(w * 0.74f, h * 0.32f)
                        lineTo(w * 0.9f, h * 0.5f)
                        lineTo(w * 0.74f, h * 0.68f)
                    },
                    tint,
                    style = Stroke(width = stroke),
                )
                drawLine(tint.copy(alpha = 0.6f), Offset(w * 0.32f, h * 0.12f), Offset(w * 0.14f, h * 0.12f), stroke)
                drawLine(tint.copy(alpha = 0.6f), Offset(w * 0.14f, h * 0.12f), Offset(w * 0.14f, h * 0.88f), stroke)
                drawLine(tint.copy(alpha = 0.6f), Offset(w * 0.14f, h * 0.88f), Offset(w * 0.32f, h * 0.88f), stroke)
            }

            TvIcon.BOARD -> {
                val stroke = m * 0.07f
                for (i in 0..2) {
                    val f = 0.2f + i * 0.3f
                    drawLine(tint, Offset(w * 0.16f, h * f), Offset(w * 0.84f, h * f), stroke)
                    drawLine(tint, Offset(w * f, h * 0.16f), Offset(w * f, h * 0.84f), stroke)
                }
            }

            TvIcon.LEVEL -> {
                val barW = w * 0.2f
                listOf(0.35f, 0.6f, 0.88f).forEachIndexed { i, frac ->
                    val barH = h * frac
                    drawRoundRect(
                        color = if (i == 2) tint else tint.copy(alpha = 0.55f),
                        topLeft = Offset(w * (0.12f + i * 0.32f), h - barH - h * 0.08f),
                        size = Size(barW, barH),
                        cornerRadius = CornerRadius(barW * 0.45f, barW * 0.45f),
                    )
                }
            }

            TvIcon.HOME -> {
                // 屋顶（三角）+ 屋身（方框）+ 门
                val stroke = m * 0.10f
                drawPath(
                    Path().apply {
                        moveTo(w * 0.5f, h * 0.12f)
                        lineTo(w * 0.9f, h * 0.48f)
                        lineTo(w * 0.1f, h * 0.48f)
                        close()
                    },
                    tint,
                    style = Stroke(width = stroke),
                )
                drawRect(
                    color = tint,
                    topLeft = Offset(w * 0.2f, h * 0.48f),
                    size = Size(w * 0.6f, h * 0.4f),
                    style = Stroke(width = stroke),
                )
                drawRect(
                    color = tint.copy(alpha = 0.7f),
                    topLeft = Offset(w * 0.42f, h * 0.64f),
                    size = Size(w * 0.16f, h * 0.24f),
                )
            }

            TvIcon.STONE -> {
                val r = m * 0.36f
                val black = Offset(w * 0.32f, h * 0.54f)
                val white = Offset(w * 0.68f, h * 0.54f)
                drawCircle(StoneBlack, r, black)
                drawCircle(StoneBlackGloss, r * 0.6f, Offset(black.x - r * 0.28f, black.y - r * 0.3f))
                drawCircle(StoneWhite, r, white)
                drawCircle(StoneWhiteGloss, r * 0.55f, Offset(white.x - r * 0.28f, white.y - r * 0.3f))
                drawCircle(StoneOutline, r, white, style = Stroke(width = r * 0.12f))
            }
        }
    }
}

// ================================================================
// 页面外壳
// ================================================================

/**
 * 页面外壳：渐变底 + 标题区 + 底部按键提示。
 *
 * 背景做了三层（渐变底 + 两团柔和光斑），不用图片资源 ——
 * 纯色底是「廉价感」的来源之一，而两团 radialGradient 的成本几乎为零。
 */
@Composable
fun TvScaffold(
    title: String? = null,
    subtitle: String? = null,
    hint: String? = null,
    accent: Color = Accent,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BackgroundTop, Background))),
    ) {
        // 柔和光斑：左上青绿、右下暖橙，让深色底不死板
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Accent.copy(alpha = 0.13f), Color.Transparent),
                    center = Offset(size.width * 0.12f, size.height * 0.02f),
                    radius = size.height * 0.85f,
                ),
                radius = size.height * 0.85f,
                center = Offset(size.width * 0.12f, size.height * 0.02f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(AccentWarm.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(size.width * 0.95f, size.height * 0.95f),
                    radius = size.height * 0.70f,
                ),
                radius = size.height * 0.70f,
                center = Offset(size.width * 0.95f, size.height * 0.95f),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = SpacePageH, vertical = SpacePageV),
        ) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 左侧色条：给标题一个视觉锚点，比纯文字更像「一个页面」
                    Box(
                        modifier = Modifier
                            .width(10.dp)
                            .height(46.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(accent),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            text = title,
                            color = TextPrimary,
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                color = TextSecondary,
                                fontSize = 21.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            Box(modifier = Modifier.weight(1f)) { content() }

            if (hint != null) {
                Spacer(Modifier.height(10.dp))
                KeyHintBar(hint)
            }
        }
    }
}

/** 底部按键提示条。遥控器没有屏幕上的按钮，必须随时能看见「我现在能按什么」。 */
@Composable
fun KeyHintBar(hint: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x33FFFFFF))
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(FocusRing),
        )
        Spacer(Modifier.width(12.dp))
        Text(text = hint, color = TextSecondary, fontSize = 21.sp)
    }
}

// ================================================================
// 按钮
// ================================================================

enum class TvButtonStyle { NORMAL, PRIMARY, DANGER }

/**
 * 可聚焦按钮。
 *
 * 焦点态做了四件事（远距离下要「余光里也能捕捉到」）：
 * 放大 1.06 倍 + 暖黄 4dp 描边 + 底色提亮一档 + 投影。
 * 只变色是不够的 —— 坐在 2 米外的孩子根本注意不到焦点在哪。
 */
@Composable
fun TvButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: TvIcon? = null,
    style: TvButtonStyle = TvButtonStyle.NORMAL,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    fillWidth: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused && enabled) 1.06f else 1f,
        animationSpec = tween(140),
        label = "btnScale",
    )

    val background = when {
        !enabled -> Surface.copy(alpha = 0.45f)
        focused -> SurfaceFocused
        style == TvButtonStyle.PRIMARY -> Accent.copy(alpha = 0.20f)
        style == TvButtonStyle.DANGER -> Danger.copy(alpha = 0.14f)
        else -> Surface
    }
    val borderColor = when {
        !enabled -> SurfaceBorder.copy(alpha = 0.4f)
        focused -> FocusRing
        style == TvButtonStyle.PRIMARY -> Accent.copy(alpha = 0.75f)
        style == TvButtonStyle.DANGER -> Danger.copy(alpha = 0.6f)
        else -> SurfaceBorder
    }
    val labelColor = when {
        !enabled -> TextDim
        focused -> TextPrimary
        style == TvButtonStyle.DANGER -> Danger
        style == TvButtonStyle.PRIMARY -> Accent
        else -> TextPrimary
    }
    val iconTint = when {
        !enabled -> TextDim
        focused -> FocusRing
        style == TvButtonStyle.DANGER -> Danger
        style == TvButtonStyle.PRIMARY -> Accent
        else -> TextSecondary
    }

    Row(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .scale(scale)
            .shadow(if (focused) 16.dp else 0.dp, RoundedCornerShape(RadiusButton))
            .clip(RoundedCornerShape(RadiusButton))
            .background(background)
            .border(if (focused) 4.dp else 2.dp, borderColor, RoundedCornerShape(RadiusButton))
            .tvFocusable(focusRequester = focusRequester, enabled = enabled) { focused = it }
            .onConfirmKey(enabled = enabled, onConfirm = onClick)
            .padding(horizontal = 22.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            TvIconView(icon = icon, tint = iconTint, size = 30.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column {
            Text(
                text = label,
                color = labelColor,
                fontSize = 25.sp,
                fontWeight = if (focused || style == TvButtonStyle.PRIMARY) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = TextDim,
                    fontSize = 19.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ================================================================
// 选项行
// ================================================================

/**
 * 横向选项选择器。
 *
 * 一整行是**一个焦点单元**：左右键换值，不用先把焦点对准第几个选项。
 * 对小孩的心智负担小很多 —— 他只需要记住「这一行是干什么的」。
 *
 * ## 高度是硬指标
 * 这一行被裁切过一次：第三行在 1080p 上被压扁成 16px，选项与说明直接消失，
 * 孩子根本改不了执棋颜色。所以在保证可读的前提下把纵向开销压到最小：
 * 标题 22sp + 选项行 44dp + 说明 20sp，整行约 106dp。
 * **改动这里的任何 padding 都要重新跑一遍 1080p 的溢出检查（见 verify-on-emulator.sh）。**
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
    focusRequester: FocusRequester? = null,
    accent: Color = Accent,
) {
    var focused by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = if (focused) accent else TextSecondary,
                fontSize = 22.sp,
                fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
            )
            if (focused) {
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .width(26.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(RadiusChip))
                .background(if (focused) SurfaceFocused.copy(alpha = 0.55f) else Surface.copy(alpha = 0.7f))
                .border(
                    if (focused) 3.dp else 1.dp,
                    if (focused) FocusRing else SurfaceBorder,
                    RoundedCornerShape(RadiusChip),
                )
                .tvFocusable(focusRequester = focusRequester) { focused = it }
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
                .padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(RadiusChip - 4.dp))
                        .background(
                            if (isSelected) {
                                if (focused) accent else accent.copy(alpha = 0.22f)
                            } else {
                                SurfaceAlt.copy(alpha = 0.75f)
                            },
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = labelOf(option),
                        // 焦点在这一行时，选中项用深色字压在亮底上，对比度拉满
                        color = when {
                            isSelected && focused -> Background
                            isSelected -> TextPrimary
                            else -> TextDim
                        },
                        fontSize = 23.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        val desc = descriptionOf(options[selectedIndex])
        if (desc != null) {
            Spacer(Modifier.height(5.dp))
            Text(
                text = desc,
                color = if (focused) TextSecondary else TextDim,
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ================================================================
// 卡片 / 统计块 / 标签
// ================================================================

/** 内容卡片。所有「一块信息」都套它，保证圆角、底色、描边一致。 */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    accent: Color = Accent,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(RadiusCard))
            .background(Surface.copy(alpha = 0.92f))
            .border(2.dp, SurfaceBorder, RoundedCornerShape(RadiusCard))
            .padding(18.dp),
    ) {
        if (title != null) {
            Text(text = title, color = accent, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
        }
        content()
    }
}

/** 数字统计块：大数字 + 小标签。 */
@Composable
fun StatTile(
    label: String,
    value: String,
    valueColor: Color = TextPrimary,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = valueColor,
            // 30sp 而不是 34sp：这几个数字所在的卡片通常和一列「行数会变的」列表抢高度，
            // 数字收一点，把纵向预算让给后者（见 HistoryScreen 分组卡的注释）
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(1.dp))
        Text(text = label, color = TextDim, fontSize = 18.sp, textAlign = TextAlign.Center)
    }
}

/** 小圆角标签（「执黑」「认输」这类）。 */
@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(color.copy(alpha = 0.20f))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(9.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(text = text, color = color, fontSize = 19.sp, fontWeight = FontWeight.Medium)
    }
}

/** 让某个元素在进入页面时自动获得焦点。 */
@Composable
fun RequestFocusOnEnter(focusRequester: FocusRequester) {
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }
}
