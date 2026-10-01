package com.heizhu.weiqi.ui.game

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.heizhu.weiqi.R
import com.heizhu.weiqi.core.rules.Stone
import com.heizhu.weiqi.data.CursorSpeed
import com.heizhu.weiqi.ui.components.StoneDot
import com.heizhu.weiqi.ui.theme.Accent
import com.heizhu.weiqi.ui.theme.AccentWarm
import com.heizhu.weiqi.ui.theme.Background
import com.heizhu.weiqi.ui.theme.BackgroundTop
import com.heizhu.weiqi.ui.theme.Danger
import com.heizhu.weiqi.ui.theme.FocusRing
import com.heizhu.weiqi.ui.theme.Scrim
import com.heizhu.weiqi.ui.theme.Success
import com.heizhu.weiqi.ui.theme.Surface
import com.heizhu.weiqi.ui.theme.SurfaceBorder
import com.heizhu.weiqi.ui.theme.SurfaceFocused
import com.heizhu.weiqi.ui.theme.TextDim
import com.heizhu.weiqi.ui.theme.TextPrimary
import com.heizhu.weiqi.ui.theme.TextSecondary
import com.heizhu.weiqi.ui.theme.Warning
import com.heizhu.weiqi.vm.GameUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** 暂停菜单的项数。选中项在 0..MENU_ITEM_COUNT-1 之间循环。 */
private const val MENU_ITEM_COUNT = 6

/** 方向键产生的移动意图 */
private enum class Dir(val dx: Int, val dy: Int) {
    UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0)
}

/**
 * 对局界面。
 *
 * ## 遥控器交互（本应用成败的关键）
 *
 * 19 路棋盘有 361 个点，如果只能一格一格挪，孩子会直接放弃。这里实现的是
 * 设计文档 §5.3 的五重加速：
 *
 * 1. **长按加速** —— 按住方向键超过一定时间后，按 [CursorSpeed] 的档位
 *    以每次 1~3 格的速度连续移动
 * 2. **光标吸附** —— 光标停在空点时直接显示「落子预览」，不需要像素级对齐
 * 3. **放大镜** —— 左下角常驻显示光标周围 5×5 的放大视图
 * 4. **快捷跳跃** —— 菜单键循环跳到最后几手落子处
 * 5. **记忆位置** —— 开局光标落在棋盘中央，而非左上角
 *
 * 长按加速**不依赖系统按键重复**，而是自己计时：系统的重复速率无法控制
 * 加速度曲线，而且首发延迟在不同电视上不一致。
 *
 * 视觉上「棋盘是唯一的主角」：两侧面板刻意压低对比度，焦点全交给棋盘。
 */
@Composable
fun GameScreen(
    ui: GameUi,
    cursorSpeed: CursorSpeed,
    onMoveCursor: (Int, Int) -> Unit,
    onJumpToRecentMove: () -> Unit,
    onConfirm: () -> Unit,
    onUndo: () -> Unit,
    onPass: () -> Unit,
    onResign: () -> Unit,
    onHint: () -> Unit,
    onExit: () -> Unit,
    onConsumeToast: () -> Unit,
    onClearHint: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var heldDirection by remember { mutableStateOf<Dir?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    // 暂停菜单是自己画的（不是 Compose 的焦点组件），所以选中位置必须自行维护，
    // 并由父级的 handleKey 分发按键 —— 否则菜单项永远选不中。
    var menuIndex by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // ---- 长按加速 ----
    LaunchedEffect(heldDirection) {
        val dir = heldDirection ?: return@LaunchedEffect
        delay(cursorSpeed.accelDelayMs)
        while (isActive) {
            onMoveCursor(dir.dx * cursorSpeed.stepSize, dir.dy * cursorSpeed.stepSize)
            delay(cursorSpeed.accelIntervalMs)
        }
    }

    // ---- toast 自动消失 ----
    LaunchedEffect(ui.toast) {
        if (ui.toast != null) {
            delay(1800)
            onConsumeToast()
        }
    }

    // 落子或思考开始后自动清掉提示
    LaunchedEffect(ui.moveCount, ui.thinking) {
        if (ui.thinking) onClearHint()
    }

    fun handleKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        fun dirOf(key: Key): Dir? = when (key) {
            Key.DirectionUp -> Dir.UP
            Key.DirectionDown -> Dir.DOWN
            Key.DirectionLeft -> Dir.LEFT
            Key.DirectionRight -> Dir.RIGHT
            else -> null
        }

        // 暂停菜单打开时，所有按键都归菜单：先于棋盘被消费，不落到棋盘上
        if (menuOpen) {
            if (event.type != KeyEventType.KeyDown) return true
            return when (event.key) {
                Key.Back, Key.Menu -> {
                    menuOpen = false
                    true
                }
                Key.DirectionUp -> {
                    menuIndex = (menuIndex - 1 + MENU_ITEM_COUNT) % MENU_ITEM_COUNT
                    true
                }
                Key.DirectionDown -> {
                    menuIndex = (menuIndex + 1) % MENU_ITEM_COUNT
                    true
                }
                Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                    // 先关菜单再执行动作，避免动作改变状态后菜单还挂在屏幕上
                    menuOpen = false
                    when (menuIndex) {
                        // 「继续下棋」不需要额外动作 —— 关掉菜单本身就是在继续。
                        0 -> Unit
                        1 -> onUndo()
                        2 -> onHint()
                        3 -> onPass()
                        4 -> onResign()
                        else -> onExit()
                    }
                    true
                }
                else -> true
            }
        }

        val dir = dirOf(event.key)
        if (dir != null) {
            when (event.type) {
                KeyEventType.KeyDown -> {
                    // 只在「首次按下」时移动一格；长按的连续移动交给上面的协程，
                    // 这样系统自带的按键重复不会和我们的加速逻辑打架
                    if (heldDirection == null) {
                        onMoveCursor(dir.dx, dir.dy)
                        heldDirection = dir
                    }
                    return true
                }
                KeyEventType.KeyUp -> {
                    heldDirection = null
                    return true
                }
                else -> return false
            }
        }

        if (event.type != KeyEventType.KeyDown) return false

        return when (event.key) {
            Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                onConfirm(); true
            }
            // 返回键直接悔棋，不弹确认框 —— 孩子下错棋的挫败感是劝退主因。
            // 配额限制（每局 5 次）由 ViewModel 把关。
            Key.Back -> {
                onUndo(); true
            }
            Key.Menu -> {
                menuOpen = true
                menuIndex = 0
                true
            }
            // 快捷跳跃：快速在最后几手落子处之间切换视图
            Key.MediaPlayPause, Key.Tab, Key.NumPadAdd -> {
                onJumpToRecentMove(); true
            }
            else -> false
        }
    }

    // 兜底：正常情况下返回键由上面的 onPreviewKeyEvent 拦下来当悔棋用。
    // 但如果焦点因为某种原因丢失（例如被系统弹窗抢走），onPreviewKeyEvent 收不到事件，
    // 返回键就会冒泡到系统把整个应用关掉。这里兜一层，保证返回键始终是「悔棋」。
    BackHandler { onUndo() }

    // 面板上显示「提了多少子」。棋盘层记的是「黑方提掉的白子」，
    // 所以必须按各自实际执的颜色换算一次，否则两个面板的数字会串。
    val aiColor = ui.playerColor.opponent
    val aiIsBlack = aiColor == Stone.BLACK
    val aiCaptured = if (aiIsBlack) ui.blackCaptured else ui.whiteCaptured
    val playerCaptured = if (aiIsBlack) ui.whiteCaptured else ui.blackCaptured

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BackgroundTop, Background)))
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { handleKey(it) },
    ) {
        // 布局：左右两条侧栏 + 中间棋盘吃满整个高度。
        //
        // **横跨整屏的顶栏与底栏全部取消，信息挪进侧栏** —— 这是专门为了棋盘面积：
        // 棋盘是正方形、受高度限制，而每一条通栏状态栏都在从棋盘身上直接切掉
        // 同样高度的一条。实测去掉两条栏后木面从 825px 涨到约 1030px（+25%）。
        //
        // 代价是「轮到谁」不再有一句大字横在屏幕顶端，改为由两侧面板承担：
        // 该走的一方整块面板描边变暖黄 + 头像外环加粗 + 面板上直接写「该你了／思考中…」。
        // 纵向留白**上紧下松**：顶部只是视觉呼吸，而底部必须给电视的 overscan
        // 安全区留位置 —— 不少电视会把画面外圈裁掉几个百分点。布局闸门抓到过
        // 侧栏底部的「第 N 手·悔棋」贴到 y2=1064（距屏幕底仅 16px）。
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp),
        ) {
            // ---- 左栏：黑猪大人（固定左侧）+ 局面信息 ----
            Column(
                modifier = Modifier.width(176.dp).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))
                PlayerPanel(
                    name = "黑猪大人",
                    avatarRes = R.drawable.avatar_boss,
                    ringColor = AccentWarm,
                    stoneIsBlack = aiIsBlack,
                    captured = aiCaptured,
                    isTurn = !ui.isOver && ui.toMove == aiColor,
                    statusText = if (ui.thinking) "思考中…" else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.weight(1f))
                TurnIndicator(ui)
            }

            // ---- 中间：棋盘占满全部可用高度 ----
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                BoardCanvas(
                    boardSize = ui.boardSize,
                    cells = ui.cells,
                    lastMoveX = ui.lastMoveX,
                    lastMoveY = ui.lastMoveY,
                    cursorX = ui.cursorX,
                    cursorY = ui.cursorY,
                    cursorVisible = !ui.isOver && !ui.thinking,
                    previewCapture = ui.previewCapture,
                    previewIllegal = ui.previewIllegal,
                    previewColor = ui.playerColor,
                    hintX = ui.hintX,
                    hintY = ui.hintY,
                    // 直接占满盒子：BoardCanvas 内部按「最短边」算格距并居中，
                    // 给满约束 = 它能画出的最大棋盘。
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // ---- 右栏：黑猪勇士（固定右侧）+ 操作提示 ----
            Column(
                modifier = Modifier.width(176.dp).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))
                PlayerPanel(
                    name = "黑猪勇士",
                    avatarRes = R.drawable.avatar_warrior,
                    ringColor = Accent,
                    stoneIsBlack = ui.playerColor == Stone.BLACK,
                    captured = playerCaptured,
                    isTurn = !ui.isOver && ui.toMove == ui.playerColor,
                    statusText = if (!ui.isOver && ui.toMove == ui.playerColor) "该你了" else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.weight(1f))
                HintBox(ui)
            }
        }

        if (ui.thinking) {
            ThinkingBadge(modifier = Modifier.align(Alignment.Center))
        }

        ui.toast?.let { message ->
            ToastBanner(message, modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp))
        }

        if (menuOpen) {
            PauseMenu(
                ui = ui,
                selectedIndex = menuIndex,
                onResume = { menuOpen = false },
                onUndo = { menuOpen = false; onUndo() },
                onHint = { menuOpen = false; onHint() },
                onPass = { menuOpen = false; onPass() },
                onResign = { menuOpen = false; onResign() },
                onExit = { menuOpen = false; onExit() },
            )
        }
    }
}

// ================================================================
// 局部组件
// ================================================================

/**
 * 回合指示 + 局面信息。放在**左栏底部**。
 *
 * 它原来是一条横跨整屏的顶栏。之所以拆掉：通栏状态栏会从一个方形棋盘身上
 * 直接切掉等高的那条面积（见主布局的注释）。信息一条没少，只是换了个位置。
 */
@Composable
private fun TurnIndicator(ui: GameUi) {
    val text = when {
        ui.isOver -> "对局结束"
        ui.thinking -> "黑猪大人正在想…"
        ui.toMove == ui.playerColor -> "轮到黑猪勇士"
        else -> "轮到黑猪大人"
    }
    val color = when {
        ui.isOver -> TextSecondary
        ui.thinking -> Warning
        ui.toMove == ui.playerColor -> Accent
        else -> AccentWarm
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!ui.isOver) {
                StoneDot(isBlack = ui.toMove == Stone.BLACK, size = 24.dp)
                Spacer(Modifier.width(8.dp))
            }
            // 20sp 是这条侧栏能容纳的最大字号：再大「黑猪大人正在想…」就会折行
            Text(text = text, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${ui.boardSize} 路 · ${ui.difficulty.displayName}",
            color = TextSecondary,
            fontSize = 19.sp,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = "第 ${ui.moveCount} 手 · 悔棋 ${ui.undoRemaining}",
            color = TextDim,
            fontSize = 18.sp,
        )
    }
}

@Composable
private fun PlayerPanel(
    name: String,
    avatarRes: Int,
    ringColor: Color,
    stoneIsBlack: Boolean,
    captured: Int,
    isTurn: Boolean,
    statusText: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(if (isTurn) SurfaceFocused else Surface.copy(alpha = 0.85f))
            .border(
                width = if (isTurn) 4.dp else 2.dp,
                color = if (isTurn) FocusRing else SurfaceBorder,
                shape = RoundedCornerShape(22.dp),
            )
            .padding(horizontal = 10.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(ringColor.copy(alpha = 0.20f))
                .border(
                    width = if (isTurn) 5.dp else 3.dp,
                    color = if (isTurn) ringColor else ringColor.copy(alpha = 0.42f),
                    shape = CircleShape,
                )
                .padding(4.dp),
        ) {
            Image(
                painter = painterResource(avatarRes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(text = name, color = TextPrimary, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StoneDot(isBlack = stoneIsBlack, size = 26.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (stoneIsBlack) "执黑" else "执白",
                color = TextSecondary,
                fontSize = 20.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "提子", color = TextDim, fontSize = 19.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$captured",
                color = TextPrimary,
                fontSize = 27.sp,
                fontWeight = FontWeight.Black,
            )
        }
        // 状态胶囊只在需要时出现（该你了 / 思考中），两侧面板高度差不影响观感
        if (statusText != null) {
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(ringColor.copy(alpha = 0.22f))
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            ) {
                Text(
                    text = statusText,
                    color = ringColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** 操作提示 + 光标坐标。放在**右栏底部**。 */
@Composable
private fun HintBox(ui: GameUi) {
    val hint = when {
        ui.isOver -> "对局结束"
        ui.thinking -> "黑猪大人正在思考…"
        ui.previewIllegal != null -> ui.previewIllegal
        ui.previewCapture > 0 -> "落在光标处可以吃掉对方 ${ui.previewCapture} 子"
        else -> "OK 落子 · 返回键悔棋 · 菜单键更多"
    }
    val color = when {
        ui.isOver -> TextSecondary
        ui.thinking -> Warning
        ui.previewIllegal != null -> Danger
        ui.previewCapture > 0 -> Success
        else -> TextSecondary
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(text = hint, color = color, fontSize = 19.sp)
        Spacer(Modifier.height(5.dp))
        Text(
            text = "光标 ${ui.cursorX + 1},${ui.cursorY + 1}",
            color = TextDim,
            fontSize = 18.sp,
        )
    }
}

/** 「黑猪大人正在思考」的气泡。三个点轮流亮，让等待这件事有进度感。 */
@Composable
private fun ThinkingBadge(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "thinking")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1050, easing = LinearEasing)),
        label = "phase",
    )
    val active = phase.toInt().coerceIn(0, 2)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Surface.copy(alpha = 0.96f))
            .border(3.dp, Warning, RoundedCornerShape(22.dp))
            .padding(horizontal = 30.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "黑猪大人正在思考", color = Warning, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        repeat(3) { i ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(Warning.copy(alpha = if (i == active) 1f else 0.3f)),
            )
        }
    }
}

@Composable
private fun ToastBanner(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Surface.copy(alpha = 0.97f))
            .border(2.dp, Accent, RoundedCornerShape(16.dp))
            .padding(horizontal = 26.dp, vertical = 13.dp),
    ) {
        Text(text = message, color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PauseMenu(
    ui: GameUi,
    selectedIndex: Int,
    onResume: () -> Unit,
    onUndo: () -> Unit,
    onHint: () -> Unit,
    onPass: () -> Unit,
    onResign: () -> Unit,
    onExit: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Scrim),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                // 固定宽度：第一版没限宽，菜单按钮 fillMaxWidth 直接把面板撑到满屏、左右贴边
                .width(560.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Surface)
                .border(2.dp, SurfaceBorder, RoundedCornerShape(26.dp))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "暂停",
                color = TextPrimary,
                fontSize = 34.sp,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(14.dp))
            MenuButton("继续下棋", enabled = true, focused = selectedIndex == 0, onClick = onResume)
            MenuButton(
                label = if (ui.undoRemaining > 0) "悔棋（还剩 ${ui.undoRemaining} 次）" else "悔棋（已用完）",
                enabled = ui.undoRemaining > 0,
                focused = selectedIndex == 1,
                onClick = onUndo,
            )
            MenuButton(
                "让黑猪大人给个提示",
                enabled = !ui.thinking,
                focused = selectedIndex == 2,
                onClick = onHint,
            )
            MenuButton("这一手停一手", enabled = true, focused = selectedIndex == 3, onClick = onPass)
            MenuButton("认输", enabled = true, danger = true, focused = selectedIndex == 4, onClick = onResign)
            MenuButton("退出这局", enabled = true, danger = true, focused = selectedIndex == 5, onClick = onExit)
        }
    }
}

@Composable
private fun MenuButton(
    label: String,
    enabled: Boolean,
    focused: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val textColor = when {
        !enabled -> TextDim
        danger -> Danger
        focused -> TextPrimary
        else -> TextPrimary
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (focused) SurfaceFocused else Surface.copy(alpha = 0.6f))
            .border(
                width = if (focused) 4.dp else 1.dp,
                color = when {
                    focused -> FocusRing
                    danger -> Danger.copy(alpha = 0.5f)
                    else -> SurfaceBorder
                },
                shape = RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 22.dp, vertical = 12.dp),
    ) {
        Text(text = label, color = textColor, fontSize = 24.sp, fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal)
    }
}
