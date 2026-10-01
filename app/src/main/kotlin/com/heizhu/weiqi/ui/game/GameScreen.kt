package com.heizhu.weiqi.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.heizhu.weiqi.core.rules.Stone
import com.heizhu.weiqi.data.CursorSpeed
import com.heizhu.weiqi.ui.theme.Accent
import com.heizhu.weiqi.ui.theme.Background
import com.heizhu.weiqi.ui.theme.CursorRing
import com.heizhu.weiqi.ui.theme.Danger
import com.heizhu.weiqi.ui.theme.StoneBlack
import com.heizhu.weiqi.ui.theme.StoneWhite
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
                        // （这里不能调 PauseMenu 的 onResume，它是那个 composable 的参数，
                        //   在 handleKey 作用域里不可见。）
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { handleKey(it) },
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            TopBar(ui)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerPanel(
                    title = "黑棋",
                    isPlayer = ui.playerColor == Stone.BLACK,
                    captured = ui.blackCaptured,
                    isTurn = !ui.isOver && ui.toMove == Stone.BLACK,
                    stoneColor = StoneBlack,
                    modifier = Modifier.width(150.dp),
                )
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
                        modifier = Modifier.aspectRatio(1f).fillMaxHeight(),
                    )
                }
                PlayerPanel(
                    title = "白棋",
                    isPlayer = ui.playerColor == Stone.WHITE,
                    captured = ui.whiteCaptured,
                    isTurn = !ui.isOver && ui.toMove == Stone.WHITE,
                    stoneColor = StoneWhite,
                    modifier = Modifier.width(150.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            BottomBar(ui)
        }

        // 放大镜：常驻左下角，显示光标周围 5x5，解决「棋子太小看不清」
        if (!ui.isOver) {
            Magnifier(ui, modifier = Modifier.align(Alignment.BottomStart).padding(24.dp))
        }

        if (ui.thinking) {
            ThinkingBadge(modifier = Modifier.align(Alignment.Center))
        }

        ui.toast?.let { message ->
            ToastBanner(message, modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp))
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

@Composable
private fun TopBar(ui: GameUi) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${ui.boardSize} 路 · ${ui.difficulty.displayName}",
            color = TextSecondary,
        )
        Text(
            text = "第 ${ui.moveCount} 手",
            color = TextPrimary,
        )
        Text(
            text = "悔棋剩余 ${ui.undoRemaining} 次",
            color = if (ui.undoRemaining > 0) TextSecondary else TextDim,
        )
    }
}

@Composable
private fun PlayerPanel(
    title: String,
    isPlayer: Boolean,
    captured: Int,
    isTurn: Boolean,
    stoneColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (isTurn) SurfaceFocused else Surface)
            .border(
                width = if (isTurn) 3.dp else 1.dp,
                color = if (isTurn) CursorRing else SurfaceBorder,
                shape = RoundedCornerShape(14.dp),
            )
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(stoneColor)
                .border(1.dp, SurfaceBorder, CircleShape),
        )
        Spacer(Modifier.height(10.dp))
        Text(text = title, color = TextPrimary, fontWeight = FontWeight.Medium)
        // 两侧都标出身份，孩子一眼能分清「哪个是我、哪个是黑猪大人」
        Text(
            text = if (isPlayer) "黑猪勇士" else "黑猪大人",
            color = if (isPlayer) Accent else Warning,
        )
        Spacer(Modifier.height(10.dp))
        Text(text = "提子 $captured", color = TextSecondary)
        if (isTurn) {
            Spacer(Modifier.height(6.dp))
            Text(text = "该你了", color = CursorRing)
        }
    }
}

@Composable
private fun BottomBar(ui: GameUi) {
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = hint, color = color)
        Text(
            text = "光标 ${ui.cursorX + 1},${ui.cursorY + 1}",
            color = TextDim,
        )
    }
}

/**
 * 放大镜：显示光标周围 5x5 区域。
 *
 * 电视视距 2-3 米，19 路棋盘上单颗棋子只有几十像素，靠眼睛判断
 * 「这里能不能下、提几子」很吃力。放大镜把关键局部放大，配合
 * 棋盘上的光标本体，构成双重确认。
 */
@Composable
private fun Magnifier(ui: GameUi, modifier: Modifier = Modifier) {
    val size = ui.boardSize
    val radius = 2
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Surface.copy(alpha = 0.92f))
            .border(1.dp, SurfaceBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(text = "放大镜", color = TextDim)
        Spacer(Modifier.height(6.dp))
        BoardCanvas(
            boardSize = size,
            cells = ui.cells,
            lastMoveX = ui.lastMoveX,
            lastMoveY = ui.lastMoveY,
            cursorX = ui.cursorX,
            cursorY = ui.cursorY,
            cursorVisible = !ui.isOver,
            previewCapture = ui.previewCapture,
            previewIllegal = ui.previewIllegal,
            previewColor = ui.playerColor,
            hintX = if (ui.hintX < 0) -1 else ui.hintX,
            hintY = if (ui.hintY < 0) -1 else ui.hintY,
            modifier = Modifier.size(190.dp),
        )
    }
}

@Composable
private fun ThinkingBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Surface.copy(alpha = 0.94f))
            .border(2.dp, Warning, RoundedCornerShape(20.dp))
            .padding(horizontal = 28.dp, vertical = 14.dp),
    ) {
        Text(text = "黑猪大人正在思考…", color = Warning)
    }
}

@Composable
private fun ToastBanner(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Surface.copy(alpha = 0.96f))
            .border(1.dp, Accent, RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(text = message, color = TextPrimary)
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
        modifier = Modifier.fillMaxSize().background(Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(Surface)
                .border(1.dp, SurfaceBorder, RoundedCornerShape(18.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "暂停", color = TextPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
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
        else -> TextPrimary
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) SurfaceFocused else Surface)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = when {
                    focused -> CursorRing
                    danger -> Danger.copy(alpha = 0.5f)
                    else -> SurfaceBorder
                },
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 22.dp, vertical = 12.dp),
    ) {
        Text(text = label, color = textColor)
    }
}