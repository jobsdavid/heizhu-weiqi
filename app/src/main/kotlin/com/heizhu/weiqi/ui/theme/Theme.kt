package com.heizhu.weiqi.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

// ================================================================
// 配色
//
// 大屏远距离观看（视距 2-3 米）的两条硬约束：
// 1. 对比度必须够高，否则暗部棋子看不清
// 2. 饱和度不能高，长时间盯着大面积亮色会累
// ================================================================

// ---- 棋盘 ----
val WoodLight = Color(0xFFE7C595)
val WoodDark = Color(0xFFC49868)
val BoardLine = Color(0xFF5C4026)
val StoneBlack = Color(0xFF1C1C20)
val StoneWhite = Color(0xFFF5F4EE)

/** 最后一手的标记色。红色小圆点是围棋界通行的「上一手」记号。 */
val LastMoveMark = Color(0xFFE53935)

/** 光标所在点的描边色。必须是屏幕上最显眼的元素。 */
val CursorRing = Color(0xFF4FC3F7)

/** 落子预览（半透明提示子） */
val PreviewAlpha = 0.45f

/** 非法落点的叉号 */
val IllegalMark = Color(0xFFEF5350)

// ---- 界面 ----
val Background = Color(0xFF14171C)
val Surface = Color(0xFF1E232B)
val SurfaceFocused = Color(0xFF33404F)
val SurfaceBorder = Color(0xFF3A4553)
val Accent = Color(0xFF4FC3F7)
val TextPrimary = Color(0xFFECEDEE)
val TextSecondary = Color(0xFF9AA3AD)
val TextDim = Color(0xFF6B7480)
val Success = Color(0xFF81C784)
val Danger = Color(0xFFE57373)
val Warning = Color(0xFFFFB74D)

// ================================================================
// 排版
//
// 基准字体比手机端整体放大一档：电视上最小的字也不能小于 24sp，
// 否则 2 米外就是一团糊。
// ================================================================

val TvTypography = Typography(
    displayLarge = TextStyle(fontSize = 72.sp, fontWeight = FontWeight.Bold),
    displayMedium = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Bold),
    displaySmall = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.SemiBold),
    headlineLarge = TextStyle(fontSize = 38.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Medium),
    headlineSmall = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 24.sp),
    bodyMedium = TextStyle(fontSize = 22.sp),
    labelLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 20.sp),
)

// ================================================================
// 主题包装
// ================================================================

/**
 * 应用主题。
 *
 * 深色为唯一配色 —— 电视多数在晚上看，浅色底大面积发光很刺眼，
 * 而且深底能让木色棋盘成为画面里最亮的部分，视线自然聚到棋盘上。
 */
@Composable
fun WeiqiTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Background,
            background = Background,
            onBackground = TextPrimary,
            surface = Surface,
            onSurface = TextPrimary,
            surfaceVariant = SurfaceFocused,
            onSurfaceVariant = TextSecondary,
            error = Danger,
            onError = Background,
        ),
        typography = TvTypography,
        content = content,
    )
}
