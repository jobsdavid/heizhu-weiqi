package com.heizhu.weiqi.core.game

import com.heizhu.weiqi.core.ai.Difficulty
import com.heizhu.weiqi.core.rules.Stone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对局状态机测试。
 *
 * 重点在**悔棋**：它的语义是「撤销玩家一手 + AI 一手，但只消耗 1 次配额」，
 * 这套规则靠推导很容易写错 —— 尤其是玩家执白（AI 先手）时，到底该退几步。
 */
class GameStateTest {

    private fun game(
        size: Int = 9,
        player: Stone = Stone.BLACK,
        maxUndo: Int = 5,
    ) = GameState(size, Difficulty.BEGINNER, player, maxUndo)

    // ===============================================================
    // 回合
    // ===============================================================

    @Test
    fun blackMovesFirst() {
        val g = game()
        assertEquals(Stone.BLACK, g.toMove)
        assertTrue(g.isPlayerTurn)
        assertFalse(g.isAiTurn)
    }

    @Test
    fun playerMoveSwitchesTurn() {
        val g = game()
        assertTrue(g.play(4, 4, Stone.BLACK) is MoveOutcome.Ok)
        assertEquals(Stone.WHITE, g.toMove)
        assertTrue(g.isAiTurn)
        assertEquals(1, g.moveCount)
    }

    @Test
    fun rejectsMoveOutOfTurn() {
        val g = game()
        assertEquals(MoveOutcome.NotYourTurn, g.play(4, 4, Stone.WHITE))
    }

    @Test
    fun playerWhiteMeansAiOpens() {
        val g = game(player = Stone.WHITE)
        assertTrue("玩家执白时，开局轮到 AI（执黑）", g.isAiTurn)
        assertEquals(Stone.BLACK, g.aiColor)
    }

    // ===============================================================
    // 悔棋
    // ===============================================================

    @Test
    fun undoRemovesPlayerMoveWhenAiHasNotReplied() {
        val g = game()
        g.play(4, 4, Stone.BLACK)        // 玩家落子，AI 还没应

        assertTrue(g.undo())

        assertEquals(0, g.moveCount)
        assertEquals(Stone.BLACK, g.toMove)
        assertEquals(1, g.undoCount)
        assertEquals(Stone.EMPTY, g.board.stoneAt(4, 4))
    }

    @Test
    fun undoRemovesBothPlayerAndAiMoveInOneCall() {
        val g = game()
        g.play(4, 4, Stone.BLACK)        // 玩家
        g.play(3, 3, Stone.WHITE)        // AI 应手
        assertEquals(2, g.moveCount)

        assertTrue(g.undo())

        assertEquals("应同时撤销玩家与 AI 各一手", 0, g.moveCount)
        assertEquals("一次悔棋只消耗 1 次配额", 1, g.undoCount)
        assertEquals(Stone.BLACK, g.toMove)
        assertEquals(Stone.EMPTY, g.board.stoneAt(4, 4))
        assertEquals(Stone.EMPTY, g.board.stoneAt(3, 3))
    }

    @Test
    fun undoWorksWhenPlayerIsWhiteAndAiOpened() {
        val g = game(player = Stone.WHITE)
        g.play(4, 4, Stone.BLACK)        // AI 开局
        g.play(3, 3, Stone.WHITE)        // 玩家
        assertEquals(2, g.moveCount)

        assertTrue(g.undo())

        assertEquals("应回到 AI 开局后的局面，由玩家重新下", 1, g.moveCount)
        assertEquals(Stone.WHITE, g.toMove)
        assertEquals(Stone.BLACK, g.board.stoneAt(4, 4))
        assertEquals(Stone.EMPTY, g.board.stoneAt(3, 3))
    }

    @Test
    fun undoRespectsQuota() {
        val g = game(maxUndo = 2)
        repeat(3) { round ->
            g.play(4, 4 + round, Stone.BLACK)
            g.play(3, 3 + round, Stone.WHITE)
        }
        assertEquals(6, g.moveCount)

        assertTrue(g.undo())
        assertTrue(g.undo())
        assertFalse("配额用尽后必须返回 false，不能静默继续", g.undo())

        assertEquals(2, g.undoCount)
        assertEquals(0, g.undoRemaining)
        assertEquals("失败的悔棋不得改变棋局", 2, g.moveCount)
    }

    @Test
    fun undoRestoresCaptureCountAndBoard() {
        val g = game(size = 4)
        // 注意落子顺序必须与 toMove 一致（黑先）。第一版这里写成白先走，
        // 第一步就被 NotYourTurn 拒了，后续棋形全乱 —— 断言失败在提子数上，
        // 但根因在测试自己的开局顺序。
        g.play(0, 1, Stone.BLACK)        // 黑：占一路
        g.play(0, 0, Stone.WHITE)        // 白：角上落子，只剩 (1,0) 一口气
        g.play(3, 3, Stone.BLACK)
        g.play(2, 3, Stone.WHITE)
        g.play(1, 0, Stone.BLACK)        // 黑：提掉白 (0,0)

        assertEquals(1, g.board.blackCaptured)
        assertEquals(Stone.EMPTY, g.board.stoneAt(0, 0))

        assertTrue(g.undo())

        assertEquals("提子计数必须回滚", 0, g.board.blackCaptured)
        assertEquals("被提的子必须回到棋盘上", Stone.WHITE, g.board.stoneAt(0, 0))
        assertEquals(4, g.moveCount)
    }

    @Test
    fun rejectedMoveDoesNotCorruptUndoStack() {
        val g = game()
        g.play(4, 4, Stone.BLACK)
        g.play(3, 3, Stone.WHITE)
        // 一连串非法落子：占位、越界 —— 都不该往悔棋栈里塞东西
        g.play(4, 4, Stone.BLACK)
        g.play(-1, 0, Stone.BLACK)
        g.play(99, 0, Stone.BLACK)

        assertTrue(g.undo())
        assertEquals("非法落子不得污染悔棋栈", 0, g.moveCount)
        assertTrue(g.board.isEmpty())
    }

    @Test
    fun undoWithoutAnyPlayerMoveFails() {
        val g = game(player = Stone.WHITE)
        g.play(4, 4, Stone.BLACK)        // 只有 AI 走过一手
        assertFalse("玩家还没下过，没有可悔的棋", g.undo())
        assertEquals(0, g.undoCount)
    }

    @Test
    fun undoIsRefusedAfterGameOver() {
        val g = game()
        g.play(4, 4, Stone.BLACK)
        g.resign()
        assertFalse(g.undo())
    }

    // ===============================================================
    // 终局
    // ===============================================================

    @Test
    fun twoConsecutivePassesEndTheGame() {
        val g = game()
        g.passMove(Stone.BLACK)
        assertFalse(g.isOver)

        g.passMove(Stone.WHITE)
        assertTrue(g.isOver)
        assertNotNull(g.result)
        assertEquals(EndReason.SCORED, g.result!!.reason)
    }

    @Test
    fun aMoveResetsThePassCounter() {
        val g = game()
        g.passMove(Stone.BLACK)
        g.play(4, 4, Stone.WHITE)        // 白落子，打断连续 pass
        g.passMove(Stone.BLACK)
        assertFalse("中间有落子，不能算连续两次停一手", g.isOver)
    }

    @Test
    fun resignEndsGameImmediately() {
        val g = game()
        g.play(4, 4, Stone.BLACK)

        val result = g.resign()

        assertTrue(g.isOver)
        assertEquals(false, result.playerWon)
        assertEquals(EndReason.RESIGN, result.reason)
    }

    @Test
    fun rejectsMovesAndPassAfterGameOver() {
        val g = game()
        g.resign()
        assertEquals(MoveOutcome.GameAlreadyOver, g.play(4, 4, Stone.BLACK))
        assertEquals(MoveOutcome.GameAlreadyOver, g.passMove(Stone.BLACK))
    }

    // ===============================================================
    // 重开
    // ===============================================================

    @Test
    fun restartClearsBoardMovesAndUndoQuota() {
        val g = game()
        g.play(4, 4, Stone.BLACK)
        g.undo()
        g.play(5, 5, Stone.BLACK)

        g.restart()

        assertEquals(0, g.moveCount)
        assertEquals(0, g.undoCount)
        assertTrue(g.board.isEmpty())
        assertEquals(Stone.BLACK, g.toMove)
        assertFalse(g.isOver)
        assertEquals(5, g.undoRemaining)
    }

    @Test
    fun restartKeepsBoardSizeAndDifficulty() {
        val g = GameState(13, Difficulty.ADVANCED, Stone.WHITE)
        g.restart()
        assertEquals(13, g.size)
        assertEquals(Difficulty.ADVANCED, g.difficulty)
        assertEquals(Stone.WHITE, g.playerColor)
    }
}