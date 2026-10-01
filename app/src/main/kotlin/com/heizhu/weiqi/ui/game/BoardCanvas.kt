package com.heizhu.weiqi.ui.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import com.heizhu.weiqi.core.rules.BoardGeometry
import com.heizhu.weiqi.core.rules.Stone
import com.heizhu.weiqi.ui.components.drawGlossyStone
import com.heizhu.weiqi.ui.theme.Accent
import com.heizhu.weiqi.ui.theme.BoardEdge
import com.heizhu.weiqi.ui.theme.BoardLine
import com.heizhu.weiqi.ui.theme.CursorRing
import com.heizhu.weiqi.ui.theme.HintRing
import com.heizhu.weiqi.ui.theme.IllegalMark
import com.heizhu.weiqi.ui.theme.LastMoveMark
import com.heizhu.weiqi.ui.theme.PreviewAlpha
import com.heizhu.weiqi.ui.theme.StoneBlack
import com.heizhu.weiqi.ui.theme.StoneWhite
import com.heizhu.weiqi.ui.theme.WoodDark
import com.heizhu.weiqi.ui.theme.WoodLight
import com.heizhu.weiqi.ui.theme.WoodMid
import kotlinx.coroutines.launch

/**
 * 围棋棋盘。用手绘 Canvas 而不是堆组件 —— 19 路有 361 个交叉点，
 * 每个点做成一个 composable 会直接把重组开销拉满。
 *
 * 绘制顺序（从下到上）：
 * 外框 → 木面 → 木纹 → 网格 → 坐标 → 星位 → 棋子（含落影）→ 最后一手
 * → 落子涟漪 → 提示环 → 光标 → 落子预览 → 非法叉号
 *
 * @param cells         棋盘数据（`Board.cells`），0=空 1=黑 2=白
 * @param cursorX/Y     遥控器光标位置
 * @param previewCapture 光标处落子能提几子；配合 [previewIllegal] 一起显示
 * @param previewIllegal 非空表示此处不可落子
 * @param showCoordinates 是否画坐标。放大镜（130dp）里关掉 —— 那个尺寸下坐标只会变成噪点
 */
@Composable
fun BoardCanvas(
    boardSize: Int,
    cells: ByteArray,
    lastMoveX: Int,
    lastMoveY: Int,
    cursorX: Int,
    cursorY: Int,
    cursorVisible: Boolean,
    previewCapture: Int,
    previewIllegal: String?,
    previewColor: Stone,
    hintX: Int,
    hintY: Int,
    modifier: Modifier = Modifier,
) {
    // 提示环的呼吸效果：静态高亮容易被忽略，闪烁才能抓住孩子视线
    val transition = rememberInfiniteTransition(label = "hint")
    val hintAlpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "hintAlpha",
    )
    // 光标本体的呼吸：让孩子在 19 路盘上一眼锁定光标
    val cursorPulse by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "cursorPulse",
    )

    // 落子动效：新棋子从 1.3 倍弹回原大小，同时一圈涟漪扩散开。
    // 孩子需要「我这一手落下去了」的即时反馈，静态棋子给不了。
    val dropScale = remember { Animatable(1f) }
    val ripple = remember { Animatable(1f) }
    LaunchedEffect(lastMoveX, lastMoveY) {
        if (lastMoveX >= 0 && lastMoveY >= 0) {
            dropScale.snapTo(1.30f)
            ripple.snapTo(0f)
            launch { dropScale.animateTo(1f, tween(200, easing = FastOutSlowInEasing)) }
            launch { ripple.animateTo(1f, tween(560)) }
        }
    }

    val measurer = rememberTextMeasurer()

    Canvas(modifier = modifier) {
        val n = boardSize
        // 留出 1 格边距放坐标；棋盘本身为正方形，居中绘制
        val step = size.minDimension / (n + 1f)
        val boardExtent = step * (n - 1)
        val left = (size.width - boardExtent) / 2f
        val top = (size.height - boardExtent) / 2f

        fun cx(col: Int) = left + col * step
        fun cy(row: Int) = top + row * step

        val woodPad = step * 0.86f
        val woodLeft = left - woodPad
        val woodTop = top - woodPad
        val woodSize = boardExtent + woodPad * 2f

        // ---------- 外框（深色木边，给棋盘一个清楚的边界）----------
        drawRoundRect(
            color = BoardEdge,
            topLeft = Offset(woodLeft - step * 0.08f, woodTop - step * 0.08f),
            size = Size(woodSize + step * 0.16f, woodSize + step * 0.16f),
            cornerRadius = CornerRadius(step * 0.34f, step * 0.34f),
        )

        // ---------- 木面 ----------
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(WoodLight, WoodMid, WoodDark),
                startY = woodTop,
                endY = woodTop + woodSize,
            ),
            topLeft = Offset(woodLeft, woodTop),
            size = Size(woodSize, woodSize),
            cornerRadius = CornerRadius(step * 0.28f, step * 0.28f),
        )

        // ---------- 木纹（几条横向浅色波浪，纯色木面会显得很假）----------
        val grainCount = 7
        for (i in 1..grainCount) {
            val y = woodTop + woodSize * i / (grainCount + 1f)
            val wave = step * 0.14f
            val path = Path().apply {
                moveTo(woodLeft + woodSize * 0.03f, y)
                cubicTo(
                    woodLeft + woodSize * 0.3f, y - wave,
                    woodLeft + woodSize * 0.55f, y + wave,
                    woodLeft + woodSize * 0.97f, y,
                )
            }
            drawPath(path, WoodMid.copy(alpha = 0.55f), style = Stroke(width = step * 0.05f))
        }

        // ---------- 网格 ----------
        val lineWidth = (step * 0.045f).coerceAtLeast(1f)
        for (i in 0 until n) {
            drawLine(BoardLine, Offset(cx(i), cy(0)), Offset(cx(i), cy(n - 1)), lineWidth)
            drawLine(BoardLine, Offset(cx(0), cy(i)), Offset(cx(n - 1), cy(i)), lineWidth)
        }
        // 最外圈加粗：正式棋盘都是这样，也让边界更清楚
        drawRect(
            color = BoardLine,
            topLeft = Offset(cx(0), cy(0)),
            size = Size(boardExtent, boardExtent),
            style = Stroke(width = lineWidth * 2.1f),
        )

        // ---------- 坐标 ----------
        run {
            val coordSize = (step * 0.42f).coerceAtLeast(9f)
            val style = TextStyle(
                color = BoardLine.copy(alpha = 0.85f),
                fontSize = coordSize.toSp(),
                fontWeight = FontWeight.Bold,
            )
            for (i in 0 until n) {
                // 围棋惯例：跳过字母 I
                val letter = ("ABCDEFGHJKLMNOPQRST" + "UVWXYZ")[i].toString()
                val lm = measurer.measure(AnnotatedString(letter), style)
                drawText(
                    textLayoutResult = lm,
                    topLeft = Offset(
                        cx(i) - lm.size.width / 2f,
                        cy(n - 1) + step * 0.30f,
                    ),
                )
                val num = measurer.measure(AnnotatedString("${n - i}"), style)
                drawText(
                    textLayoutResult = num,
                    topLeft = Offset(
                        cx(0) - step * 0.30f - num.size.width,
                        cy(i) - num.size.height / 2f,
                    ),
                )
            }
        }

        // ---------- 星位 ----------
        val starRadius = (step * 0.13f).coerceAtLeast(2f)
        for (star in BoardGeometry.starPoints(n)) {
            drawCircle(BoardLine, starRadius, Offset(cx(star.x), cy(star.y)))
        }

        // ---------- 棋子 ----------
        val stoneRadius = step * 0.47f
        val lastIndex = if (lastMoveX in 0 until n && lastMoveY in 0 until n) {
            lastMoveY * n + lastMoveX
        } else {
            -1
        }
        for (index in cells.indices) {
            val code = cells[index].toInt()
            if (code == 0) continue
            val col = index % n
            val row = index / n
            val center = Offset(cx(col), cy(row))
            val isLast = index == lastIndex
            val r = if (isLast) stoneRadius * dropScale.value else stoneRadius
            drawGlossyStone(center = center, radius = r, isBlack = code == 1)
        }

        // ---------- 最后一手：涟漪 + 暖橙点 ----------
        if (lastIndex in cells.indices && cells[lastIndex].toInt() != 0) {
            val center = Offset(cx(lastMoveX), cy(lastMoveY))
            val lastIsBlack = cells[lastIndex].toInt() == 1
            if (ripple.value < 1f) {
                drawCircle(
                    color = LastMoveMark.copy(alpha = (1f - ripple.value) * 0.65f),
                    radius = stoneRadius * (1.05f + 1.25f * ripple.value),
                    center = center,
                    style = Stroke(width = (step * 0.07f).coerceAtLeast(1.5f)),
                )
            }
            drawCircle(
                color = LastMoveMark,
                radius = (step * 0.17f).coerceAtLeast(2.5f),
                center = center,
            )
            // 内点颜色跟棋子反着来：白子上用深色点，黑子上用亮点，
            // 否则「白子 + 白心」在 2 米外只剩一个看不出形状的橙圈。
            drawCircle(
                color = if (lastIsBlack) Color.White.copy(alpha = 0.92f)
                else Color(0xFF2A1410).copy(alpha = 0.85f),
                radius = (step * 0.06f).coerceAtLeast(1f),
                center = center,
            )
        }

        // ---------- 提示环（AI 建议落点）----------
        if (hintX in 0 until n && hintY in 0 until n) {
            drawCircle(
                color = HintRing.copy(alpha = hintAlpha),
                radius = stoneRadius * 1.15f,
                center = Offset(cx(hintX), cy(hintY)),
                style = Stroke(width = (step * 0.10f).coerceAtLeast(2f)),
            )
        }

        // ---------- 光标 ----------
        if (cursorVisible) {
            val center = Offset(cx(cursorX), cy(cursorY))
            val hoveredIndex = cursorY * n + cursorX
            val occupied = hoveredIndex in cells.indices && cells[hoveredIndex].toInt() != 0

            // 落子预览：半透明子 + 可提子数
            if (!occupied && previewIllegal == null) {
                drawCircle(
                    color = when {
                        previewColor == Stone.BLACK -> StoneBlack.copy(alpha = PreviewAlpha)
                        else -> StoneWhite.copy(alpha = PreviewAlpha)
                    },
                    radius = stoneRadius,
                    center = center,
                )
                if (previewCapture > 0) {
                    // 有提子时加一圈醒目的绿环，避免孩子忽略「能吃掉对方」
                    drawCircle(
                        color = Accent,
                        radius = stoneRadius * 1.2f,
                        center = center,
                        style = Stroke(width = (step * 0.09f).coerceAtLeast(2f)),
                    )
                }
            }

            // 光标本体：暖黄双环 + 呼吸，必须是屏幕上最显眼的东西
            drawCircle(
                color = CursorRing,
                radius = stoneRadius * 1.26f * cursorPulse,
                center = center,
                style = Stroke(width = (step * 0.10f).coerceAtLeast(2f)),
            )
            drawCircle(
                color = CursorRing.copy(alpha = 0.34f),
                radius = stoneRadius * 1.62f,
                center = center,
                style = Stroke(width = (step * 0.05f).coerceAtLeast(1f)),
            )

            // 非法落点：红叉
            if (previewIllegal != null && !occupied) {
                val arm = stoneRadius * 0.62f
                val w = (step * 0.09f).coerceAtLeast(2f)
                drawLine(
                    IllegalMark, Offset(center.x - arm, center.y - arm),
                    Offset(center.x + arm, center.y + arm), w,
                )
                drawLine(
                    IllegalMark, Offset(center.x + arm, center.y - arm),
                    Offset(center.x - arm, center.y + arm), w,
                )
            }
        }
    }
}
