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
import androidx.compose.ui.text.style.TextOverflow
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

// ============================================================
// 两侧栏里凡是**内容会变**的块，高度都必须固定（状态胶囊、提示区、回合文字）。
// 面板是由两个 weight(1f) 间距居中的，任一兄弟块高度一变，面板就整块上下跳 ——
// 这类缺陷在真机上表现为「头像莫名往下掉」，极难从现象反推，所以把尺寸钉死并配测试。
// ============================================================

/**
 * 顶部信息行高度（26dp）。左栏顶部显示「总用时」，实测 19sp 中文行高 25.5dp，取 26dp。
 *
 * 两栏顶部必须**等高**：左右面板是被各自栏内的权重间距居中的，
 * 一栏顶多一块、另一栏没有，两个面板就会一高一低（实测差 49px，肉眼能看出来）。
 */
private val TopInfoHeight = 26.dp

/**
 * 状态胶囊槽位高度（40dp）。推导：胶囊 = 垂直内边距 5dp×2 + 20sp 中文行高（约 28sp）≈ 38~40dp。
 * 实测胶囊出现/消失让面板高度变化 100px（=50dp，含 10dp 间距），面板居中 → 上下各跳 50px。
 */
private val StatusPillHeight = 40.dp

/**
 * 提示区槽位高度（108dp）。推导：最长提示在 176dp 侧栏里折两行（19sp 中文约 9 字/行，
 * 实测两行 112px）+ 间距 5dp(10px) + 坐标行 18sp(约 56px) + 上下内边距 9dp×2(36px)
 * ≈ 214px = 107dp，取 108dp。提示框在槽内底部对齐，超出只会向上吃空白，**不会挤到面板**。
 */
private val HintSlotHeight = 108.dp

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
 *
 * 两侧栏里凡是**内容会变**的块，高度都必须固定（状态胶囊、提示区、回合文字）。
 * 原因见下方常量注释：面板是由两个 weight(1f) 间距居中的，任一兄弟块的高度一变，
 * 面板就会上下跳。这类缺陷在真机上表现为「头像莫名往下掉」，极难从现象反推。
 */
/** mm:ss；超过一小时才显示小时（别写出 61:30 这种）。 */
private fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * 计时文案。`startAt <= 0` 表示状态里没有时间信息（例如界面自测的样例数据）→ 显示占位，
 * 否则会算出「56 年」这种荒唐数字。
 */
private fun elapsedText(startAt: Long, now: Long): String =
    if (startAt <= 0L) "--:--" else formatClock((now - startAt).coerceAtLeast(0L))

/**
 * 秒级时钟。**必须在界面本地刷新**：如果让 ViewModel 每秒推一次状态，
 * 整个棋盘 Canvas 会跟着每秒重组一次 —— 为了几个数字把最贵的那块重画，不划算。
 */
@Composable
private fun rememberNowMs(activeKey: Any?): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(activeKey) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    return now
}

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
    // 返回键悔棋需要一个确认框：误碰一下就把刚下的一手撤掉，孩子会直接崩溃
    var confirmUndo by remember { mutableStateOf(false) }
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

    /** 请求悔棋：先弹确认框，确认后才真的撤。 */
    fun requestUndo() {
        if (ui.isOver) return
        confirmUndo = true
    }

    fun handleKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        fun dirOf(key: Key): Dir? = when (key) {
            Key.DirectionUp -> Dir.UP
            Key.DirectionDown -> Dir.DOWN
            Key.DirectionLeft -> Dir.LEFT
            Key.DirectionRight -> Dir.RIGHT
            else -> null
        }

        // 悔棋确认框优先：打开时所有按键都归它，先于棋盘与暂停菜单被消费
        if (confirmUndo) {
            if (event.type != KeyEventType.KeyDown) return true
            return when (event.key) {
                Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                    confirmUndo = false
                    onUndo()
                    true
                }
                Key.Back, Key.Menu -> {
                    confirmUndo = false
                    true
                }
                else -> true
            }
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
            // 返回键悔棋 —— **必须弹确认框**。
            //
            // 第一版是「返回键直接悔棋」，理由是孩子下错棋的挫败感是劝退主因。
            // 但真机验证时发现：误碰一下返回键，刚下的一手就被撤了，反而更崩溃。
            // 现在先弹一句「要悔棋吗？」，确认才撤；要接着下就再按一次返回键。
            // 配额限制（每局 5 次）仍由 ViewModel 把关。
            Key.Back -> {
                requestUndo(); true
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
    BackHandler { requestUndo() }

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
        // ---- 计时 ----
        // 总时长（本局开始至今）、以及两侧各自的思考时长。
        // 定义：从轮到自己开始，到落子/停一手为止 —— 所以它天然包含玩家移光标与
        // AI 的搜索时间，正是看棋的人想知道的「这一手想了多久」。
        // 正在思考的那一方，显示的是「已累计 + 这一手已经用掉的」，所以数字是活的。
        val nowMs = rememberNowMs(ui.startedAtMs)
        val totalText = elapsedText(ui.startedAtMs, nowMs)
        val ongoingMs = (nowMs - ui.turnStartedAtMs).coerceAtLeast(0L)
        val aiColor = if (ui.playerColor == Stone.BLACK) Stone.WHITE else Stone.BLACK
        val bossThinkLive = ui.bossThinkMs +
            if (!ui.isOver && ui.toMove == aiColor) ongoingMs else 0L
        val playerThinkLive = ui.playerThinkMs +
            if (!ui.isOver && ui.toMove == ui.playerColor) ongoingMs else 0L

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
                // 总用时放在**顶部的空白区** —— 这里是全屏最空的地方。
                //
                // 为什么不放棋盘正上方：那块只有 50px（棋盘自带半格留白），而且正落在电视
                // overscan 裁切带里；要安全塞下得让棋盘缩约 6%，不值得。
                // 为什么从底部信息块挪上来：放底部时底边到 y=1032，距屏幕底仅 48px，
                // 正好压在裁切带上（本项目的闸门按「距边缘 40px 内」判风险）。
                Box(
                    modifier = Modifier.height(TopInfoHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "总用时 $totalText", color = TextDim, fontSize = 19.sp)
                }
                Spacer(Modifier.weight(1f))
                // ⚠️ 身份约定（2026-10-02 修正；这一处之前**整体弄反**，真机核对时发现）：
                //     · **黑猪大人 = AI 对手**。证据是应用自己的文案：
                //       设置页 `InfoRow("对手", "黑猪大人（本机 AI）")`、首页"和黑猪大人下棋"、
                //       对局中的"黑猪大人正在思考…"、"黑猪大人停了一手"（AI 停手时）。
                //     · **黑猪勇士 = 玩的人**（孩子）。所以首屏那行「黑猪勇士执什么颜色」
                //       选的就是玩家的颜色（内部字段名 `playerColor`）—— 那行文案是对的。
                //     之前把 AI 的面板（大人）绑到了玩家数据上 ⇒ 孩子选黑棋后，
                //     "黑猪大人"那块显示成黑棋，看起来像选项反了。
                PlayerPanel(
                    name = "黑猪大人",
                    avatarRes = R.drawable.avatar_boss,
                    ringColor = AccentWarm,
                    // AI 那一侧：颜色 / 提子 / 用时（ViewModel 里 `boss*` 就是 AI）
                    stoneIsBlack = aiIsBlack,
                    captured = aiCaptured,
                    thinkMs = bossThinkLive,
                    isTurn = !ui.isOver && ui.toMove == aiColor,
                    // AI 那一侧不能写「该你了」——那是跟机器说话。它在想就说在想。
                    statusText = if (ui.thinking) "思考中…" else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.weight(1f))
                // 与右栏提示区**等高**的槽：两栏底部结构一致，面板才会真正对齐。
                Box(
                    modifier = Modifier.fillMaxWidth().height(HintSlotHeight),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    TurnIndicator(ui)
                }
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
                    capturedPoints = ui.lastCapturedPoints,
                    capturedColor = ui.lastCapturedColor,
                    // 动画触发键用「手数」：IntArray 每次刷新都是新对象，
                    // 直接用它当 key 会让动画每帧重新开始
                    animationKey = ui.moveCount,
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
                // 与左栏顶部的「总用时」**等高占位**：两栏纵向结构必须一致，
                // 否则两个面板一高一低。这是显示总用时带来的副作用，必须一起处理。
                Spacer(Modifier.height(TopInfoHeight))
                Spacer(Modifier.weight(1f))
                PlayerPanel(
                    name = "黑猪勇士",
                    avatarRes = R.drawable.avatar_warrior,
                    ringColor = Accent,
                    // 玩家那一侧（孩子）：颜色 / 提子 / 用时
                    stoneIsBlack = ui.playerColor == Stone.BLACK,
                    captured = playerCaptured,
                    thinkMs = playerThinkLive,
                    isTurn = !ui.isOver && ui.toMove == ui.playerColor,
                    statusText = if (!ui.isOver && ui.toMove == ui.playerColor) "该你了" else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.weight(1f))
                // 提示区高度**必须固定**。它一变，上面两个 weight(1f) 间距就重新分配，
                // 面板整块上下跳 —— 用户实测：提示变成「这里已经有子了」（比默认提示少一行）
                // 时，黑猪勇士头像往下掉 28px。
                // 槽位底部对齐 → 提示框照旧贴着屏幕底部，只在槽内向上长。
                Box(
                    modifier = Modifier.fillMaxWidth().height(HintSlotHeight),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    HintBox(ui)
                }
            }
        }

        if (ui.thinking) {
            ThinkingBadge(modifier = Modifier.align(Alignment.Center))
        }

        ui.toast?.let { message ->
            ToastBanner(message, modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp))
        }

        if (confirmUndo) {
            UndoConfirmDialog(
                remaining = ui.undoRemaining,
                onConfirm = { confirmUndo = false; onUndo() },
            )
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
        // 必须短到一行放得下：「黑猪大人正在想…」有 8 字，在 176dp 侧栏里
        // （减去棋子圆点 24dp + 间距 8dp 后只剩约 7 字宽）会折成两行，
        // 整列高度一变，上面的左侧面板就被推着跳（实测 79px）。
        ui.thinking -> "正在想…"
        // ⚠️ 身份：`ui.playerColor` = **玩家（黑猪勇士）**，`aiColor` = **AI（黑猪大人）**。
        // 2026-10-02 按应用自己的文案统一了这套约定（设置页"对手 · 黑猪大人（本机 AI）"、
        // 首页"和黑猪大人下棋"、"黑猪大人正在思考…"）；此处与两块面板的绑定必须一致，
        // 否则就会出现"面板写着大人、提示条写着勇士"的自相矛盾。
        ui.toMove == ui.playerColor -> "轮到黑猪勇士"
        else -> "轮到黑猪大人"
    }
    val color = when {
        ui.isOver -> TextSecondary
        ui.thinking -> Warning
        // 颜色跟着"该谁走"走：玩家面板的描边是 Accent，AI 面板的是 AccentWarm
        ui.toMove == ui.playerColor -> Accent
        else -> AccentWarm
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!ui.isOver) {
                StoneDot(isBlack = ui.toMove == Stone.BLACK, size = 24.dp)
                Spacer(Modifier.width(8.dp))
            }
            // maxLines=1 是**布局稳定性**的兜底，不只是排版：这行一旦折行，
            // 整列高度就变，上面的面板跟着跳（实测 79px）。宁可省略号，不要折行。
            Text(
                text = text,
                color = color,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${ui.boardSize} 路 · ${ui.difficulty.displayName}",
            color = TextSecondary,
            fontSize = 19.sp,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            // 文案必须是「可悔棋 N 次」而不是「悔棋 N」——
            // 后者读起来像「已经悔了 5 次」，真机上黑猪大人就被这个误导过
            text = "第 ${ui.moveCount} 手 · 可悔棋 ${ui.undoRemaining} 次",
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
    /** 该方累计思考时长。**恒定显示**，见下方注释。 */
    thinkMs: Long,
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
        Spacer(Modifier.height(6.dp))
        // 用时**恒定存在**（不是「想完才显示」）：这一列的高度必须固定，
        // 否则面板会被上下两个 weight(1f) 间距推着跳（详见文件顶部「高度必须固定」）。
        Text(
            text = "用时 ${formatClock(thinkMs)}",
            color = TextDim,
            fontSize = 19.sp,
        )
        // 状态胶囊（该你了 / 思考中…）的位置**必须恒定**：有则显示、无则留空。
        //
        // 原来的写法是「没状态就不渲染这一块」，于是面板自身高度随回合变化，
        // 而面板被两个 weight(1f) 间距居中 → 整块面板上下跳（实测 50px）。
        // 这一条是被用户抓出来的：提示文字变化时头像会往下掉。
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier.height(StatusPillHeight),
            contentAlignment = Alignment.Center,
        ) {
            if (statusText != null) {
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
}

/** 操作提示 + 光标坐标。放在**右栏底部**。 */
@Composable
private fun HintBox(ui: GameUi) {
    val hint = when {
        ui.isOver -> "对局结束"
        // 无处可下时给出**明确的收工指引**：不加这条，孩子会一直找不到落子点，
        // 又不知道可以停一手，对局就永远结束不了
        // 用显式换行而不是让它自动折：自动折的行数随字号/字体/屏宽变化，
        // 提示区高度就不固定了。这里定死两行，正好落在 HintSlotHeight 里。
        ui.playerHasNoLegalMove -> "你没地方下了\n菜单键 → 停一手"
        ui.thinking -> "黑猪大人正在思考…"
        ui.previewIllegal != null -> ui.previewIllegal
        ui.previewCapture > 0 -> "落在光标处可以吃掉对方 ${ui.previewCapture} 子"
        else -> "OK 落子 · 返回键悔棋 · 菜单键更多"
    }
    val color = when {
        ui.isOver -> TextSecondary
        ui.playerHasNoLegalMove -> FocusRing
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

/**
 * 悔棋确认框。
 *
 * 返回键是遥控器上最容易误碰的键之一 —— 第一版「返回键直接悔棋」的结果是
 * 真机验证时发现「有时候会误点返回」，刚下的一手莫名消失比下错棋更让人崩溃。
 * 所以先问一句：OK 确认，返回键继续下（再按一次返回键就是接着下）。
 */
@Composable
private fun UndoConfirmDialog(
    remaining: Int,
    onConfirm: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Scrim),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(580.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Surface)
                .border(3.dp, FocusRing, RoundedCornerShape(26.dp))
                .padding(horizontal = 26.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "要悔棋吗？",
                color = TextPrimary,
                fontSize = 34.sp,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "会撤销你和黑猪大人各一手 · 本局还剩 $remaining 次",
                color = TextSecondary,
                fontSize = 21.sp,
            )
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "OK 确认悔棋",
                    color = FocusRing,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(30.dp))
                Text(
                    text = "返回键 接着下",
                    color = TextDim,
                    fontSize = 23.sp,
                )
            }
        }
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
