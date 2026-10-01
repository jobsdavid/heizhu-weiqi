package com.heizhu.weiqi.core.rules

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则引擎测试。
 *
 * 这里每个用例都对应一个**真实会出错**的规则点，不是凑覆盖率：
 * 提子、自杀、打劫、提子逃出的判定顺序、眼识别。
 */
class BoardTest {

    /** 直接摆子，用于构造测试局面（相当于「摆谱」，不触发提子判定）。 */
    private fun Board.put(x: Int, y: Int, stone: Stone) {
        cells[BoardGeometry.index(size, x, y)] = stone.code
    }

    // ===============================================================
    // 基础
    // ===============================================================

    @Test
    fun newBoardIsEmpty() {
        val b = Board(9)
        assertEquals(81, b.cellCount)
        assertTrue(b.isEmpty())
        assertEquals(Stone.EMPTY, b.stoneAt(0, 0))
        assertEquals(Point.PASS, b.lastMove)
        assertEquals(-1, b.koPoint)
    }

    @Test
    fun placeSingleStone() {
        val b = Board(9)
        val r = b.play(4, 4, Stone.BLACK)
        assertTrue(r is PlayOutcome.Ok)
        assertEquals(Stone.BLACK, b.stoneAt(4, 4))
        assertEquals(Point(4, 4), b.lastMove)
    }

    @Test
    fun rejectOccupiedPoint() {
        val b = Board(9)
        b.play(4, 4, Stone.BLACK)
        assertEquals(PlayOutcome.Occupied, b.play(4, 4, Stone.WHITE))
    }

    @Test
    fun rejectOutOfBounds() {
        val b = Board(9)
        assertEquals(PlayOutcome.OutOfBounds, b.play(-1, 0, Stone.BLACK))
        assertEquals(PlayOutcome.OutOfBounds, b.play(9, 0, Stone.BLACK))
        assertEquals(PlayOutcome.OutOfBounds, b.play(0, 9, Stone.BLACK))
    }

    // ===============================================================
    // 提子
    // ===============================================================

    @Test
    fun captureSingleStoneInCorner() {
        val b = Board(4)
        b.put(0, 0, Stone.WHITE)   // 白在角上，唯一的气是 (0,1)
        b.put(1, 0, Stone.BLACK)

        val r = b.play(0, 1, Stone.BLACK)

        val ok = r as PlayOutcome.Ok
        assertEquals(1, ok.captureCount)
        assertEquals(Stone.EMPTY, b.stoneAt(0, 0))
        assertEquals(1, b.blackCaptured)      // 黑方提子数 +1
        assertEquals(0, b.whiteCaptured)
    }

    @Test
    fun captureMultipleStonesAtOnce() {
        val b = Board(4)
        b.put(0, 0, Stone.WHITE)
        b.put(1, 0, Stone.WHITE)
        b.put(2, 0, Stone.BLACK)
        b.put(0, 1, Stone.BLACK)
        // 白两子连通成一块，唯一的气是 (1,1)

        val r = b.play(1, 1, Stone.BLACK)

        val ok = r as PlayOutcome.Ok
        assertEquals(2, ok.captureCount)
        assertEquals(Stone.EMPTY, b.stoneAt(0, 0))
        assertEquals(Stone.EMPTY, b.stoneAt(1, 0))
        assertEquals(2, b.blackCaptured)
    }

    @Test
    fun captureCountsGoToTheRightSide() {
        val b = Board(4)
        b.put(0, 1, Stone.BLACK)
        b.put(0, 0, Stone.WHITE)
        b.put(1, 0, Stone.WHITE)
        b.put(2, 0, Stone.WHITE)
        // 白三子一块，气在 (0,1)? 不 —— (0,1) 已被黑占。
        // 重新构造：白 (0,0) 与黑相邻，气在下方
        // 此处直接验证「白提黑」时统计落在白方
        val b2 = Board(4)
        b2.put(0, 0, Stone.BLACK)
        b2.put(1, 0, Stone.WHITE)
        b2.play(0, 1, Stone.WHITE)    // 白提黑一子

        assertEquals(1, b2.whiteCaptured)
        assertEquals(0, b2.blackCaptured)
    }

    // ===============================================================
    // 自杀 —— 以及最容易写错的那个顺序问题
    // ===============================================================

    @Test
    fun rejectPlainSuicide() {
        val b = Board(4)
        b.put(0, 1, Stone.BLACK)
        b.put(2, 1, Stone.BLACK)
        b.put(1, 0, Stone.BLACK)
        b.put(1, 2, Stone.BLACK)
        // (1,1) 被黑四面包围，白落此处无气且提不到任何子 → 自杀

        val r = b.play(1, 1, Stone.WHITE)

        assertEquals(PlayOutcome.Suicide, r)
        assertEquals(Stone.EMPTY, b.stoneAt(1, 1))   // 棋盘必须被完整回滚
        assertEquals(0, b.whiteCaptured)
    }

    /**
     * **关键用例**：本手自身无气，但能提掉对方，因此合法。
     *
     * 这验证的是「先提子、后判自杀」的判定顺序。如果实现里把两者顺序颠倒，
     * 这个用例会返回 Suicide —— 那是围棋规则实现中最常见的 bug。
     */
    @Test
    fun capturingEscapesSuicide() {
        val b = Board(3)
        // 白棋把中心围住，黑在 (1,2) 嵌在包围圈里
        b.put(0, 0, Stone.WHITE); b.put(1, 0, Stone.WHITE); b.put(2, 0, Stone.WHITE)
        b.put(0, 1, Stone.WHITE);                           b.put(2, 1, Stone.WHITE)
        b.put(0, 2, Stone.WHITE);                           b.put(2, 2, Stone.WHITE)
        b.put(1, 2, Stone.BLACK)
        // 白棋 7 子连成一块，唯一的气就是 (1,1)

        val r = b.play(1, 1, Stone.BLACK)

        val ok = r as PlayOutcome.Ok
        assertEquals("应提掉整块白棋", 7, ok.captureCount)
        assertEquals(Stone.BLACK, b.stoneAt(1, 1))   // 这一手必须留下
        assertEquals(Stone.BLACK, b.stoneAt(1, 2))
        assertEquals(7, b.blackCaptured)
    }

    // ===============================================================
    // 打劫（简单劫）
    // ===============================================================

    /** 构造一个标准劫形，见 [koSetup] 注释。 */
    private fun koSetup(): Board {
        val b = Board(4)
        //        x=0    x=1     x=2
        // y=0           B
        // y=1    B      W       B
        // y=2    W              W
        // y=3           W
        // 白 (1,1) 是孤子，唯一的气是 (1,2)
        b.put(1, 0, Stone.BLACK)
        b.put(0, 1, Stone.BLACK)
        b.put(2, 1, Stone.BLACK)
        b.put(1, 1, Stone.WHITE)
        b.put(0, 2, Stone.WHITE)
        b.put(2, 2, Stone.WHITE)
        b.put(1, 3, Stone.WHITE)
        return b
    }

    @Test
    fun koSetupIsValid() {
        val b = koSetup()
        val finder = GroupFinder(4)
        val whiteLiberties = finder.findLiberties(b.cells, BoardGeometry.index(4, 1, 1))
        assertEquals("白 (1,1) 应恰好只有一口气", 1, whiteLiberties)
    }

    @Test
    fun blackKoTakesOneStone() {
        val b = koSetup()
        val r = b.play(1, 2, Stone.BLACK)

        val ok = r as PlayOutcome.Ok
        assertEquals(1, ok.captureCount)
        assertEquals(Stone.EMPTY, b.stoneAt(1, 1))
        // 黑 (1,2) 自己只剩提子腾出的那一口气，但不算自杀
        assertEquals(Stone.BLACK, b.stoneAt(1, 2))
        assertEquals(BoardGeometry.index(4, 1, 1), b.koPoint)
    }

    @Test
    fun koForbidsImmediateRecapture() {
        val b = koSetup()
        b.play(1, 2, Stone.BLACK)          // 黑提劫

        val r = b.play(1, 1, Stone.WHITE)  // 白想立刻提回

        assertEquals(PlayOutcome.Ko, r)
        assertEquals(Stone.EMPTY, b.stoneAt(1, 1))   // 棋盘不得被改动
    }

    @Test
    fun koReleasedAfterOneInterveningMove() {
        val b = koSetup()
        b.play(1, 2, Stone.BLACK)   // 黑提劫，生成劫点
        b.play(3, 3, Stone.WHITE)   // 白下别处 —— 隔一手
        b.play(3, 2, Stone.BLACK)   // 黑应一手

        val r = b.play(1, 1, Stone.WHITE)   // 白现在可以提回了

        val ok = r as PlayOutcome.Ok
        assertEquals(1, ok.captureCount)
        assertEquals(Stone.EMPTY, b.stoneAt(1, 2))
    }

    @Test
    fun passClearsKoPoint() {
        val b = koSetup()
        b.play(1, 2, Stone.BLACK)
        assertEquals(BoardGeometry.index(4, 1, 1), b.koPoint)

        b.pass()

        assertEquals(-1, b.koPoint)
        assertEquals(Point.PASS, b.lastMove)
    }

    // ===============================================================
    // 眼识别（AI 禁止填自己的眼所依赖）
    // ===============================================================

    @Test
    fun detectTrueEye() {
        val b = Board(9)
        b.put(3, 4, Stone.BLACK); b.put(5, 4, Stone.BLACK)
        b.put(4, 3, Stone.BLACK); b.put(4, 5, Stone.BLACK)
        b.put(3, 3, Stone.BLACK); b.put(5, 3, Stone.BLACK)
        b.put(3, 5, Stone.BLACK); b.put(5, 5, Stone.BLACK)
        // (4,4) 空，四邻与四角全黑 → 真眼

        assertTrue(b.isOwnEye(BoardGeometry.index(9, 4, 4), Stone.BLACK))
        assertFalse("对白方而言不是眼", b.isOwnEye(BoardGeometry.index(9, 4, 4), Stone.WHITE))
    }

    @Test
    fun eyeDetectionRejectsFalseEye() {
        val b = Board(9)
        // 四邻全黑，但对角只有一个黑子 —— 对方可从对角打吃，是假眼
        b.put(3, 4, Stone.BLACK); b.put(5, 4, Stone.BLACK)
        b.put(4, 3, Stone.BLACK); b.put(4, 5, Stone.BLACK)
        b.put(3, 3, Stone.BLACK)

        assertFalse(b.isOwnEye(BoardGeometry.index(9, 4, 4), Stone.BLACK))
    }

    @Test
    fun occupiedPointIsNeverAnEye() {
        val b = Board(9)
        b.put(4, 4, Stone.BLACK)
        assertFalse(b.isOwnEye(BoardGeometry.index(9, 4, 4), Stone.BLACK))
    }

    // ===============================================================
    // 预览（光标悬停）不能改变棋盘
    // ===============================================================

    @Test
    fun previewDoesNotMutateBoard() {
        val b = Board(4)
        b.put(0, 0, Stone.WHITE)
        b.put(1, 0, Stone.BLACK)
        val snapshot = b.cells.copyOf()

        val r = b.preview(0, 1, Stone.BLACK)

        val ok = r as PlayOutcome.Ok
        assertEquals(1, ok.captureCount)
        assertArrayEquals("预览不得改变棋盘", snapshot, b.cells)
        assertEquals(0, b.blackCaptured)
    }

    @Test
    fun previewReportsIllegalMoves() {
        val b = Board(4)
        b.put(0, 0, Stone.BLACK)
        assertEquals(PlayOutcome.Occupied, b.preview(0, 0, Stone.WHITE))
        assertEquals(PlayOutcome.OutOfBounds, b.preview(4, 0, Stone.WHITE))

        val b2 = Board(4)
        b2.put(0, 1, Stone.BLACK); b2.put(2, 1, Stone.BLACK)
        b2.put(1, 0, Stone.BLACK); b2.put(1, 2, Stone.BLACK)
        assertEquals(PlayOutcome.Suicide, b2.preview(1, 1, Stone.WHITE))
    }

    // ===============================================================
    // 拷贝（MCTS 热路径依赖）
    // ===============================================================

    @Test
    fun copyPreservesState() {
        val b = koSetup()
        b.play(1, 2, Stone.BLACK)

        val c = b.copy()

        assertArrayEquals(b.cells, c.cells)
        assertEquals(b.koPoint, c.koPoint)
        assertEquals(b.lastMove, c.lastMove)
        assertEquals(b.blackCaptured, c.blackCaptured)

        // 拷贝后互不影响
        c.play(3, 3, Stone.WHITE)
        assertEquals(Stone.EMPTY, b.stoneAt(3, 3))
    }

    @Test
    fun copyFromReusesBuffers() {
        val source = koSetup()
        source.play(1, 2, Stone.BLACK)

        val target = Board(4)
        target.play(0, 3, Stone.WHITE)   // 先弄脏
        target.copyFrom(source)

        assertArrayEquals(source.cells, target.cells)
        assertEquals(source.koPoint, target.koPoint)
        assertEquals(source.lastMove, target.lastMove)
    }
}
