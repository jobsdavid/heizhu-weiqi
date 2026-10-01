package com.heizhu.weiqi.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

// ================================================================
// 配色 ·「儿童活力」
//
// 面向小孩 + 家长陪着看，视距 2-3 米。三条设计约束：
//
// 1. **不能是死黑**。原来整屏只有一档深灰 + 一个蓝，观感廉价、也没有情绪。
//    现在底色改为深青绿（有色彩倾向但足够暗），大面积看两小时不刺眼。
// 2. **焦点必须一眼看得见**。暖黄描边在青绿底上是对比最强的组合，
//    比原来的青色描边跳得多 —— 孩子不用凑近就能看出光标在哪。
// 3. **棋盘与棋子要有实物感**。暖木棋盘 + 带光泽的圆润棋子，
//    让屏幕中心那一块成为整个应用里最好看的区域。
//
// 色相分工（不要混用，混了就会花）：
//   青绿 Accent  = 我方 / 主操作 / 中性强调
//   暖橙 AccentWarm = 对手 / 胜负结果 / 温度
//   暖黄 FocusRing = 焦点，全局唯一，不与任何语义色混用
// ================================================================

// ---- 棋盘：暖木 ----
val WoodLight = Color(0xFFF7DCB0)
val WoodMid = Color(0xFFEAC38A)
val WoodDark = Color(0xFFD8A768)
/** 棋盘外框：比木面深一档，给棋盘一个清楚的边界 */
val BoardEdge = Color(0xFFA97540)
val BoardLine = Color(0xFF8A5F32)
val StarPoint = Color(0xFF6B4823)

// ---- 棋子：卡通光泽 ----
val StoneBlack = Color(0xFF22303C)
val StoneBlackGloss = Color(0x99A8D8FF)
val StoneWhite = Color(0xFFFFFBF2)
val StoneWhiteGloss = Color(0xE6FFFFFF)
/** 棋子外描边。白棋在木色底上没它就会糊成一片 */
val StoneOutline = Color(0x2E1A1208)

/** 最后一手的标记色：暖橙红，在木色底上很跳 */
val LastMoveMark = Color(0xFFFF6B4A)

/** 棋盘光标。全局最醒目元素，用暖黄 + 呼吸双环 */
val CursorRing = Color(0xFFFFC24D)

/** AI 提示落点的呼吸环 */
val HintRing = Color(0xFF35D0A5)

/** 落子预览的半透明程度 */
val PreviewAlpha = 0.45f

/** 非法落点的叉号 */
val IllegalMark = Color(0xFFFF5C5C)

// ---- 界面：深青绿 ----
/** 页面底色（上浅下深，做出一层空间感） */
val BackgroundTop = Color(0xFF123A40)
val Background = Color(0xFF0E2C31)
/** 卡片底 */
val Surface = Color(0xFF17454C)
/** 卡片内的次级底（如选项未选中态） */
val SurfaceAlt = Color(0xFF1E5860)
/** 焦点卡片底：必须比普通卡片明显亮一档 */
val SurfaceFocused = Color(0xFF2A7C82)
/** 卡片描边（未选中） */
val SurfaceBorder = Color(0xFF2F6E75)

/** 主强调：亮青绿 */
val Accent = Color(0xFF34D9BF)
/** 次强调：暖橙。用于对手、输棋、需要温度的地方 */
val AccentWarm = Color(0xFFFFA447)
val AccentWarmDeep = Color(0xFFF2842B)

/** 焦点描边。全局唯一，只表示「你现在在这里」 */
val FocusRing = Color(0xFFFFD166)

val TextPrimary = Color(0xFFF2FBF9)
val TextSecondary = Color(0xFFA9CFCB)
val TextDim = Color(0xFF6F9C9A)

val Success = Color(0xFF5BE08A)
val Danger = Color(0xFFFF7A7A)
val Warning = Color(0xFFFFC24D)

/** 弹层遮罩 */
val Scrim = Color(0xCC04181B)

// ================================================================
// 圆角与间距
//
// 大圆角是这个方向的基础语言：卡通感几乎全靠它 + 亮描边。
// 不要在这些地方写魔法数字 —— 统一走 token，才能一改全局一致。
// ================================================================
val RadiusCard = 26.dp
val RadiusButton = 20.dp
val RadiusChip = 14.dp
val RadiusPill = 999.dp

val SpacePageH = 40.dp
val SpacePageV = 26.dp

// ================================================================
// 排版
//
// 基准比手机端整体放大约一档：电视最小字号不能低于 22sp，
// 否则 2 米外就是一团糊。字号阶梯拉开，别让所有字都一样大 ——
// 「所有字一样大」是廉价感的第二大来源（第一是没有焦点态）。
// ================================================================
val TvTypography = Typography(
    displayLarge = TextStyle(fontSize = 76.sp, fontWeight = FontWeight.Black),
    displayMedium = TextStyle(fontSize = 58.sp, fontWeight = FontWeight.Black),
    displaySmall = TextStyle(fontSize = 46.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 26.sp),
    bodyMedium = TextStyle(fontSize = 23.sp),
    bodySmall = TextStyle(fontSize = 21.sp),
    labelLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 19.sp),
)

// ================================================================
// 主题包装
// ================================================================

/**
 * 应用主题。
 *
 * 深色为唯一配色 —— 电视多数在晚上看，浅底大面积发光很刺眼。
 * 但深色不等于纯黑：底色带青绿倾向，棋盘才是画面里最亮的部分，
 * 视线自然落到棋盘上。
 */
@Composable
fun WeiqiTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Background,
            secondary = AccentWarm,
            onSecondary = Background,
            background = Background,
            onBackground = TextPrimary,
            surface = Surface,
            onSurface = TextPrimary,
            surfaceVariant = SurfaceAlt,
            onSurfaceVariant = TextSecondary,
            error = Danger,
            onError = Background,
        ),
        typography = TvTypography,
        content = content,
    )
}
