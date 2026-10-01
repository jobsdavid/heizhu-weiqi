package com.heizhu.weiqi.ui.result

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.heizhu.weiqi.core.game.EndReason
import com.heizhu.weiqi.core.game.GameResult
import com.heizhu.weiqi.core.rules.Stone
import com.heizhu.weiqi.ui.components.RequestFocusOnEnter
import com.heizhu.weiqi.ui.components.TvButton
import com.heizhu.weiqi.ui.components.TvScaffold
import com.heizhu.weiqi.ui.theme.Accent
import com.heizhu.weiqi.ui.theme.Danger
import com.heizhu.weiqi.ui.theme.Surface
import com.heizhu.weiqi.ui.theme.SurfaceBorder
import com.heizhu.weiqi.ui.theme.TextDim
import com.heizhu.weiqi.ui.theme.TextPrimary
import com.heizhu.weiqi.ui.theme.TextSecondary
import com.heizhu.weiqi.ui.theme.Warning

/**
 * 对局结果页。
 *
 * 文案刻意避免生硬的判定语。孩子输棋时看到的是「这局输了，再来一次吧」，
 * 而不是冷冰冰的「失败」—— 这个年龄段的挫败感直接决定他还会不会继续玩。
 *
 * 比分用**子数明细**呈现（子数 + 围空），让家长能一眼核对，也顺便让孩子
 * 看见「围空」和「棋子」是两回事。
 */
@Composable
fun ResultScreen(
    result: GameResult,
    playerColor: Stone,
    boardSize: Int,
    difficultyName: String,
    onRematch: () -> Unit,
    onHome: () -> Unit,
) {
    val rematchFocus = remember { FocusRequester() }
    RequestFocusOnEnter(rematchFocus)

    // 返回键回主菜单（结果已入库存档，不会丢）
    BackHandler { onHome() }

    val score = result.score
    val headline = when (result.playerWon) {
        true -> "黑猪勇士赢了！"
        false -> "这局输了"
        null -> "和棋"
    }
    val headColor = when (result.playerWon) {
        true -> Accent
        false -> Warning
        null -> TextSecondary
    }
    val subline = when {
        result.reason == EndReason.RESIGN && result.playerWon == false -> "认输了，下次再挑战黑猪大人"
        result.playerWon == true -> "黑猪勇士下得不错，继续保持"
        result.playerWon == false -> "再来一局吧，赢黑猪大人可不容易"
        else -> "势均力敌"
    }

    TvScaffold(hint = "OK 再来一局 · 也可以回首页") {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(text = headline, color = headColor, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(text = subline, color = TextSecondary)

            Spacer(Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                ScoreCard(
                    title = "黑棋",
                    total = score.blackTotal,
                    stones = score.blackStones,
                    territory = score.blackTerritory,
                    isPlayer = playerColor == Stone.BLACK,
                    isWinner = score.winner == Stone.BLACK,
                    stoneColor = androidx.compose.ui.graphics.Color(0xFF1C1C20),
                )
                ScoreCard(
                    title = "白棋",
                    total = score.whiteTotal,
                    stones = score.whiteStones,
                    territory = score.whiteTerritory,
                    isPlayer = playerColor == Stone.WHITE,
                    isWinner = score.winner == Stone.WHITE,
                    stoneColor = androidx.compose.ui.graphics.Color(0xFFF5F4EE),
                )
            }

            Spacer(Modifier.height(18.dp))

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (score.blackMargin >= 0) "黑棋领先 ${score.blackMargin} 子"
                    else "白棋领先 ${-score.blackMargin} 子",
                    color = TextPrimary,
                )
                Text(
                    text = "${boardSize} 路 · ${difficultyName} · 共 ${result.moveCount} 手",
                    color = TextSecondary,
                )
                Text(
                    text = "用时 ${formatDuration(result.durationMs)}" +
                        if (result.undoCount > 0) " · 本局悔棋 ${result.undoCount} 次" else "",
                    color = TextDim,
                )
                if (!score.isFullySettled) {
                    // 诚实告知：自动数子不含死子判定，可能与被围死的棋有偏差
                    Text(
                        text = "提示：还有 ${score.neutral} 个点双方都能走到（单官），比分可能略有偏差",
                        color = TextDim,
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvButton(
                    label = "再来一局",
                    onClick = onRematch,
                    focusRequester = rematchFocus,
                )
                TvButton(label = "回到首页", onClick = onHome)
            }
        }
    }
}

@Composable
private fun ScoreCard(
    title: String,
    total: Int,
    stones: Int,
    territory: Int,
    isPlayer: Boolean,
    isWinner: Boolean,
    stoneColor: androidx.compose.ui.graphics.Color,
) {
    Column(
        modifier = Modifier
            .width(280.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Surface)
            .border(
                if (isWinner) 3.dp else 1.dp,
                if (isWinner) Accent else SurfaceBorder,
                RoundedCornerShape(16.dp),
            )
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(30.dp)
                    .height(30.dp)
                    .clip(CircleShape)
                    .background(stoneColor)
                    .border(1.dp, SurfaceBorder, CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Text(text = title, color = TextPrimary, fontWeight = FontWeight.Medium)
            if (isPlayer) {
                Spacer(Modifier.width(8.dp))
                Text(text = "黑猪勇士", color = Accent)
            }
            if (isWinner) {
                Spacer(Modifier.width(8.dp))
                Text(text = "胜", color = Accent)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(text = "$total 子", color = TextPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(text = "其中棋子 $stones · 围空 $territory", color = TextDim)
    }
}

/** 把毫秒格式化成「X 分 Y 秒」。超过一小时才显示小时，避免孩子看到无意义的长串。 */
private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "$hours 小时 $minutes 分"
        minutes > 0 -> "$minutes 分 $seconds 秒"
        else -> "$seconds 秒"
    }
}