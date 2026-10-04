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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.heizhu.weiqi.core.game.EndReason
import com.heizhu.weiqi.core.game.GameResult
import com.heizhu.weiqi.core.rules.Stone
import com.heizhu.weiqi.ui.components.RequestFocusOnEnter
import com.heizhu.weiqi.ui.components.SectionCard
import com.heizhu.weiqi.ui.components.StoneDot
import com.heizhu.weiqi.ui.components.Tag
import com.heizhu.weiqi.ui.components.TvButton
import com.heizhu.weiqi.ui.components.TvButtonStyle
import com.heizhu.weiqi.ui.components.TvIcon
import com.heizhu.weiqi.ui.components.TvScaffold
import com.heizhu.weiqi.ui.theme.Accent
import com.heizhu.weiqi.ui.theme.AccentWarm
import com.heizhu.weiqi.ui.theme.FocusRing
import com.heizhu.weiqi.ui.theme.Surface
import com.heizhu.weiqi.ui.theme.SurfaceBorder
import com.heizhu.weiqi.ui.theme.TextDim
import com.heizhu.weiqi.ui.theme.TextPrimary
import com.heizhu.weiqi.ui.theme.TextSecondary

/**
 * 对局结果页。
 *
 * 文案刻意避免生硬的判定语。孩子输棋时看到的是「这局输了，再来一次吧」，
 * 而不是冷冰冰的「失败」—— 这个年龄段的挫败感直接决定他还会不会继续玩。
 *
 * 比分用**子数明细**呈现（棋子数 + 围空），让家长能一眼核对，
 * 也顺便让孩子看见「围空」和「棋子」是两回事。
 *
 * 左右分栏：左边看成绩，右边按「再来一局」。胜利时标题用青绿、落败用暖橙，
 * 不要用红色 —— 红色在大屏上是「报错」的情绪，不是「再来一次」的情绪。
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
    // 认输是**终局**，围棋里不结算。更要命的是数子算法有一条已知简化：
    // 只接触单色的空区归该色 —— 盘上若只剩一方的棋子，整盘都会被判给它。
    // 实测：认输在第 1 手时，结果页会显示「黑棋 169 子、黑棋领先 169 子」。
    // 所以认输局一概不显示比分。
    val resigned = result.reason == EndReason.RESIGN
    val headline = when (result.playerWon) {
        true -> "黑猪勇士赢了！"
        false -> "这局输了"
        null -> "和棋"
    }
    val headColor = when (result.playerWon) {
        true -> Accent
        false -> AccentWarm
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
            Text(
                text = headline,
                color = headColor,
                fontSize = 58.sp,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(4.dp))
            Text(text = subline, color = TextSecondary, fontSize = 24.sp)

            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxSize()) {
                // ---- 左：比分 ----
                Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                    if (resigned) {
                        SectionCard(modifier = Modifier.fillMaxWidth(), accent = AccentWarm) {
                            Text(
                                text = "认输的棋不数子",
                                color = TextPrimary,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "围棋里认输就是终局，不再数子结算。下一局换个难度，或者试试让黑猪大人少提你几个子。",
                                color = TextSecondary,
                                fontSize = 22.sp,
                            )
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ScoreCard(
                                title = "黑棋",
                                isBlack = true,
                                total = score.blackTotal,
                                stones = score.blackStones,
                                territory = score.blackTerritory,
                                isPlayer = playerColor == Stone.BLACK,
                                isWinner = score.winner == Stone.BLACK,
                            )
                            ScoreCard(
                                title = "白棋",
                                isBlack = false,
                                total = score.whiteTotal,
                                stones = score.whiteStones,
                                territory = score.whiteTerritory,
                                isPlayer = playerColor == Stone.WHITE,
                                isWinner = score.winner == Stone.WHITE,
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    if (!resigned) {
                        sectionLine(
                            if (score.blackMargin >= 0) "黑棋领先 ${score.blackMargin} 子"
                            else "白棋领先 ${-score.blackMargin} 子",
                            TextPrimary,
                        )
                    }
                    sectionLine("$boardSize 路 · $difficultyName · 共 ${result.moveCount} 手", TextSecondary)
                    sectionLine(
                        "用时 ${formatDuration(result.durationMs)}" +
                            if (result.undoCount > 0) " · 本局悔棋 ${result.undoCount} 次" else "",
                        TextDim,
                    )
                    if (!resigned && !score.isFullySettled) {
                        // 诚实告知：自动数子不含死子判定，可能与被围死的棋有偏差
                        sectionLine(
                            "提示：还有 ${score.neutral} 个点双方都能走到（单官），比分可能略有偏差",
                            TextDim,
                        )
                    }
                }

                Spacer(Modifier.width(24.dp))

                // ---- 右：下一步 ----
                Column(modifier = Modifier.width(280.dp).fillMaxSize()) {
                    TvButton(
                        label = "再来一局",
                        icon = TvIcon.PLAY,
                        style = TvButtonStyle.PRIMARY,
                        onClick = onRematch,
                        focusRequester = rematchFocus,
                        fillWidth = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    TvButton(
                        label = "回到首页",
                        icon = TvIcon.HOME,
                        onClick = onHome,
                        fillWidth = true,
                    )
                }
            }
        }
    }
}

/**
 * 结果页的一行说明。
 *
 * 字号与行距都刻意收紧：数子结束那一支的元素最多（标题 + 副标题 + 两张比分卡 +
 * 领先/手数/用时/单官提示四行 + 按钮），本机 UI 自测实测会挤到「用时…」那一行
 * 被压成 17px。**改这里的字号/行距必须重跑 TvScreenLayoutTest。**
 */
@Composable
private fun sectionLine(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text = text, color = color, fontSize = 21.sp)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun ScoreCard(
    title: String,
    isBlack: Boolean,
    total: Int,
    stones: Int,
    territory: Int,
    isPlayer: Boolean,
    isWinner: Boolean,
) {
    SectionCard(
        modifier = Modifier.width(280.dp),
        accent = if (isWinner) Accent else SurfaceBorder,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StoneDot(isBlack = isBlack, size = 40.dp)
            Spacer(Modifier.width(10.dp))
            Text(text = title, color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            if (isPlayer) {
                Spacer(Modifier.width(8.dp))
                Tag(text = "我方", color = Accent)
            }
            if (isWinner) {
                Spacer(Modifier.width(6.dp))
                Tag(text = "胜", color = FocusRing)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "$total 子",
            color = if (isWinner) Accent else TextPrimary,
            fontSize = 42.sp,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "棋子 $stones", color = TextDim, fontSize = 19.sp)
            Text(text = "围空 $territory", color = TextDim, fontSize = 19.sp)
        }
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
