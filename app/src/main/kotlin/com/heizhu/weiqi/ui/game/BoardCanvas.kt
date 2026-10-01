package com.heizhu.weiqi.ui.game

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.heizhu.weiqi.core.rules.BoardGeometry
import com.heizhu.weiqi.core.rules.Stone
import com.heizhu.weiqi.ui.theme.BoardLine
import com.heizhu.weiqi.ui.theme.CursorRing
import com.heizhu.weiqi.ui.theme.IllegalMark
import com.heizhu.weiqi.ui.theme.LastMoveMark
import com.heizhu.weiqi.ui.theme.PreviewAlpha
import com.heizhu.weiqi.ui.theme.StoneBlack
import com.heizhu.weiqi.ui.theme.StoneWhite
import com.heizhu.weiqi.ui.theme.Success
import com.heizhu.weiqi.ui.theme.WoodDark
import com.heizhu.weiqi.ui.theme.WoodLight

/**
 * 围棋棋盘。用手绘 Canvas 而不是堆组件 —— 19 路有 361 个交叉点，
 * 每个点做成一个 composable 会直接把重组开销拉满。
 *
 * 绘制顺序（从下到上）：
 * 木纹底 → 网格 → 星位 → 落影 → 棋子 → 最后一手标记 → 提示环 → 光标 → 落子预览
 *
 * @param cells        棋盘数据（`Board.cells`），0=空 1=黑 2=白
 * @param cursorX/Y    遥控器光标位置
 * @param previewCapture 光标处落子能提几子；配合 [previewIllegal] 一起显示
 * @param previewIllegal 非空表示此处不可落子
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

    Canvas(modifier = modifier) {
        val n = boardSize
        // 留出 1 格边距；棋盘本身为正方形，居中绘制
        val step = size.minDimension / (n + 1f)
        val boardExtent = step * (n - 1)
        val left = (size.width - boardExtent) / 2f
        val top = (size.height - boardExtent) / 2f

        fun cx(col: Int) = left + col * step
        fun cy(row: Int) = top + row * step

        // ---------- 木纹底 ----------
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(WoodLight, WoodDark),
                startY = top - step,
                endY = top + boardExtent + step,
            ),
            topLeft = Offset(left - step * 0.92f, top - step * 0.92f),
            size = Size(boardExtent + step * 1.84f, boardExtent + step * 1.84f),
        )

        // ---------- 网格 ----------
        val lineWidth = (step * 0.035f).coerceAtLeast(1f)
        for (i in 0 until n) {
            drawLine(
                color = BoardLine,
                start = Offset(cx(i), cy(0)),
                end = Offset(cx(i), cy(n - 1)),
                strokeWidth = lineWidth,
            )
            drawLine(
                color = BoardLine,
                start = Offset(cx(0), cy(i)),
                end = Offset(cx(n - 1), cy(i)),
                strokeWidth = lineWidth,
            )
        }

        // ---------- 星位 ----------
        val starRadius = (step * 0.09f).coerceAtLeast(1.5f)
        for (star in BoardGeometry.starPoints(n)) {
            drawCircle(
                color = BoardLine,
                radius = starRadius,
                center = Offset(cx(star.x), cy(star.y)),
            )
        }

        // ---------- 棋子（含落影，产生立体感） ----------
        val stoneRadius = step * 0.46f
        val shadowOffset = step * 0.055f
        for (index in cells.indices) {
            val code = cells[index].toInt()
            if (code == 0) continue
            val col = index % n
            val row = index / n
            val center = Offset(cx(col), cy(row))

            drawCircle(
                color = Color(0x33000000),
                radius = stoneRadius,
                center = Offset(center.x + shadowOffset, center.y + shadowOffset),
            )
            val isBlack = code == 1
            drawStone(
                center = center,
                radius = stoneRadius,
                baseColor = if (isBlack) StoneBlack else StoneWhite,
                highlight = if (isBlack) Color(0x44FFFFFF) else Color(0x88FFFFFF),
            )
        }

        // ---------- 最后一手标记 ----------
        if (lastMoveX in 0 until n && lastMoveY in 0 until n) {
            val lastIndex = lastMoveY * n + lastMoveX
            if (lastIndex in cells.indices && cells[lastIndex].toInt() != 0) {
                drawCircle(
                    color = LastMoveMark,
                    radius = (step * 0.13f).coerceAtLeast(2f),
                    center = Offset(cx(lastMoveX), cy(lastMoveY)),
                )
            }
        }

        // ---------- 提示环（AI 建议落点） ----------
        if (hintX in 0 until n && hintY in 0 until n) {
            drawCircle(
                color = Success.copy(alpha = hintAlpha),
                radius = stoneRadius * 1.15f,
                center = Offset(cx(hintX), cy(hintY)),
                style = Stroke(width = (step * 0.1f).coerceAtLeast(2f)),
            )
        }

        // ---------- 光标 ----------
        if (cursorVisible) {
            val center = Offset(cx(cursorX), cy(cursorY))

            // 落子预览：半透明子 + 可提子数
            val hoveredIndex = cursorY * n + cursorX
            val occupied = hoveredIndex in cells.indices && cells[hoveredIndex].toInt() != 0
            if (!occupied && previewIllegal == null) {
                drawCircle(
                    color = if (previewColor == Stone.BLACK) StoneBlack.copy(alpha = PreviewAlpha)
                    else StoneWhite.copy(alpha = PreviewAlpha),
                    radius = stoneRadius,
                    center = center,
                )
                if (previewCapture > 0) {
                    // 有提子时加一圈醒目的绿环，避免孩子忽略「能吃掉对方」
                    drawCircle(
                        color = Success,
                        radius = stoneRadius * 1.2f,
                        center = center,
                        style = Stroke(width = (step * 0.09f).coerceAtLeast(2f)),
                    )
                }
            }

            // 光标本体：亮青色双环，必须是屏幕上最显眼的东西
            drawCircle(
                color = CursorRing,
                radius = stoneRadius * 1.28f,
                center = center,
                style = Stroke(width = (step * 0.085f).coerceAtLeast(2f)),
            )
            drawCircle(
                color = CursorRing.copy(alpha = 0.32f),
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

/**
 * 画一颗棋子。
 *
 * 纯色圆片在木色底上会显得很平，加径向渐变高光后才有棋子的圆润感 ——
 * 这是纯 Canvas 绘制里性价比最高的一个细节。
 */
private fun DrawScope.drawStone(
    center: Offset,
    radius: Float,
    baseColor: Color,
    highlight: Color,
) {
    drawCircle(color = baseColor, radius = radius, center = center)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(highlight, Color.Transparent),
            center = Offset(center.x - radius * 0.3f, center.y - radius * 0.35f),
            radius = radius * 1.25f,
        ),
        radius = radius,
        center = center,
    )
}
