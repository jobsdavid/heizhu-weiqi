package com.heizhu.weiqi.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.heizhu.weiqi.core.ai.Difficulty
import com.heizhu.weiqi.core.rules.Stone
import com.heizhu.weiqi.data.CursorSpeed
import com.heizhu.weiqi.ui.game.GameScreen
import com.heizhu.weiqi.ui.history.HistoryScreen
import com.heizhu.weiqi.ui.home.HomeScreen
import com.heizhu.weiqi.ui.home.NewGameScreen
import com.heizhu.weiqi.ui.result.ResultScreen
import com.heizhu.weiqi.ui.settings.SettingsScreen
import com.heizhu.weiqi.ui.theme.WeiqiTvTheme
import com.heizhu.weiqi.vm.GameViewModel
import com.heizhu.weiqi.vm.Screen

/**
 * 应用根节点：主题 + 页面分发。
 *
 * 只有 6 个页面，用 `when` 直接分发即可，不引入 navigation-compose ——
 * 那个库的价值在于深层链接和复杂返回栈，这里都用不上，纯属增加依赖和构建风险。
 */
@Composable
fun AppRoot(vm: GameViewModel) {
    val screen by vm.screen.collectAsState()
    val ui by vm.ui.collectAsState()
    val history by vm.history.collectAsState()
    val context = LocalContext.current

    WeiqiTvTheme {
        when (screen) {
            Screen.HOME -> HomeScreen(
                records = history,
                onStartGame = { vm.navigateTo(Screen.SETUP) },
                onHistory = { vm.navigateTo(Screen.HISTORY) },
                onSettings = { vm.navigateTo(Screen.SETTINGS) },
                onExit = { (context as? Activity)?.finish() },
            )

            Screen.SETUP -> NewGameScreen(
                initialSize = vm.settings.lastBoardSize,
                initialDifficulty = Difficulty.BEGINNER,
                initialColor = Stone.BLACK,
                onStart = { size, difficulty, color ->
                    vm.startNewGame(size, difficulty, color)
                },
                onBack = { vm.navigateTo(Screen.HOME) },
            )

            Screen.GAME -> GameScreen(
                ui = ui,
                cursorSpeed = CursorSpeed.entries[vm.settings.cursorSpeed],
                onMoveCursor = vm::moveCursor,
                onJumpToRecentMove = {
                    // 快捷跳跃：把光标送回最后一手落子处，快速回到主战场
                    if (ui.lastMoveX >= 0 && ui.lastMoveY >= 0) {
                        vm.setCursor(ui.lastMoveX, ui.lastMoveY)
                    }
                },
                onConfirm = vm::confirmAtCursor,
                onUndo = vm::undo,
                onPass = vm::pass,
                onResign = vm::resign,
                onHint = vm::requestHint,
                onExit = { vm.navigateTo(Screen.HOME) },
                onConsumeToast = vm::consumeToast,
                onClearHint = vm::clearHint,
            )

            Screen.RESULT -> {
                val result = ui.result
                if (result == null) {
                    // 结果丢失（例如进程重建）时兜底回首页，不留在空白页
                    vm.navigateTo(Screen.HOME)
                } else {
                    ResultScreen(
                        result = result,
                        playerColor = ui.playerColor,
                        boardSize = ui.boardSize,
                        difficultyName = ui.difficulty.displayName,
                        onRematch = vm::rematch,
                        onHome = { vm.navigateTo(Screen.HOME) },
                    )
                }
            }

            Screen.HISTORY -> HistoryScreen(
                records = history,
                onBack = { vm.navigateTo(Screen.HOME) },
                onClear = vm::clearHistory,
            )

            Screen.SETTINGS -> SettingsScreen(
                settings = vm.settings,
                onBack = { vm.navigateTo(Screen.HOME) },
            )
        }
    }
}
