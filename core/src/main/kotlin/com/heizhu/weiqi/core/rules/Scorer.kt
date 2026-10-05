package com.heizhu.weiqi.core.rules

/**
 * 终局比分（中国规则 / 数子法）。
 *
 * 各字段含义：
 * - [blackStones] / [whiteStones]：棋盘上各色棋子数
 * - [blackTerritory] / [whiteTerritory]：各方**独占**的空点数
 * - [neutral]：单官 —— 同时接触黑白两色的空点，双方均不计
 *
 * ⚠️ 已知限制：**不含死子判定**。被围死但仍有气的棋会被算作活棋。
 * 详见设计文档 §5.1。缓解：AI 的 MCTS 会自然提掉所有可提之子，
 * 终局时棋盘上的明显死子极少。
 */
data class Score(
    val boardPoints: Int,
    val blackStones: Int,
    val whiteStones: Int,
    val blackTerritory: Int,
    val whiteTerritory: Int,
    val neutral: Int,
    val komi: Double,
) {
    /** 黑方总子数（子 + 围空） */
    val blackTotal: Int get() = blackStones + blackTerritory

    /** 白方总子数（子 + 围空），**不含贴目** */
    val whiteTotal: Int get() = whiteStones + whiteTerritory

    /** 黑方净胜子数。正数 = 黑多，负数 = 白多。界面「领先 N 子」用它。 */
    val blackMargin: Int get() = blackTotal - whiteTotal

    /**
     * 胜负。
     *
     * 中国规则：黑贴 [komi] 子。判定的等价形式是「黑方净胜子数是否超过贴目的两倍」——
     * 因为净胜子数 = 黑 − 白，而黑需 > 半数 + komi，代入 黑 + 白 = 棋盘点数 即可推得。
     *
     * 举例（贴 3¾ 子，临界为净胜 7.5 子）：
     * - 黑 185 / 白 176 → 净胜 9 > 7.5 → **黑胜** ✓
     * - 黑 184 / 白 177 → 净胜 7 < 7.5 → **白胜** ✓
     *
     * 贴目带小数，因此实际上不会出现和棋；[Stone.EMPTY] 只在异常情况下返回。
     */
    val winner: Stone
        get() = when {
            blackMargin > komi * 2 -> Stone.BLACK
            blackMargin < komi * 2 -> Stone.WHITE
            else -> Stone.EMPTY
        }

    /** 单官是否已填完。未填完时比分会有小偏差，界面可据此提示。 */
    val isFullySettled: Boolean get() = neutral == 0
}

/**
 * 各尺寸棋盘的贴目（单位：子），中国规则。
 *
 * 统一取 3¾ 子 —— 这是中国规则的标准值（「黑 185 子胜」即由此而来）。
 * 若后续实测发现 9 路对局明显偏袒白方，可单独下调 9 路的值（小棋盘的
 * 先手优势与棋盘大小的换算关系本身没有唯一标准，各地约定不一）。
 */
object Komi {
    /** 中国规则标准贴目（单位：子）。9 路也用它 —— 实测标定如此，见下。 */
    const val STANDARD = 3.75

    /**
     * 按尺寸取贴目。
     *
     * ⚠️ **不要按"小棋盘先手优势小"想当然地下调** —— 2026-10-05 实测（产品口径自战，
     * 60 局/点，收工判据修正后）：
     * ```
     *   贴 1¼ 子  → 黑 55%   （黑方占优）
     *   贴 3¾ 子  → 黑 50% / 白 50%   ← 平衡
     *   贴 4½ 子  → 黑 48%
     *   贴 6 子   → 黑 47%
     * ```
     * ⚠️ 贴目必须**先用足够局数扫出平衡点，再拿去标难度** —— 顺序反了会让整个阶梯被压平。
     * 2026-10-06 重测（修正引擎两处规则缺陷后，每点 80 局）：
     * ```
     *   komi 0.0  → 黑 54%      komi 1.5  → 黑 48%
     *   komi 0.75 → 黑 51%      komi 2.25 → 黑 49%
     *   komi 3.0  → 黑 32%   ← 明显偏白
     * ```
     * 平衡点落在 0.75~2.25 之间 ⇒ 取 **1.25**。
     * 旧值 3.75 下实测：**白方稳定赢 72~78%**（n=80/档，五档让子数全一样）——
     * 因为黑方中位净胜仅 ~0，而判胜门槛是 2×3.75 = 7.5 ⇒ 白方让多少子都赢。
     * 也就是说：**贴目偏大时，让子阶梯会被整体压平、完全失效**（实测让 0 子与让 4 子胜率相同）。
     *
     * 历史注：本项目曾把 9 路下调到 1¼ 子，后来又回滚了（理由是"那是在早终局失真的基线上标的"）。
     * 现在用修正后的口径、80 局/点重测，结论**又回到 1¼ 子** —— 说明当时回滚的依据也不成立。
     * 教训：这类常数只能靠"足够功效的测量 + 明确的判据"定，不能靠叙事。
     *
     * 13 路仍沿用 STANDARD，**尚未标定**，必须用同样口径扫（每点 ≥80 局）。
     */
    fun forSize(size: Int): Double = when (size) {
        9 -> 1.25
        else -> STANDARD      // 13 路仍用标准值，**尚未标定**（需按同样口径扫，见下）
    }
}

/**
 * 中国规则数子。
 *
 * 算法：对每个空区域做一次 flood fill，统计该区域接触到的颜色：
 * - 只接触黑 → 黑地
 * - 只接触白 → 白地
 * - 两者都接触 → 单官（不计）
 *
 * ⚠️ 非线程安全（内部复用访问标记与栈）。每个线程持有独立实例。
 */
class Scorer(private val size: Int) {

    private val cellCount = size * size
    private val visited = BooleanArray(cellCount)
    private val stack = IntArray(cellCount)
    private val neighborBuf = IntArray(4)

    /**
     * 计算终局比分。
     *
     * @param cells 棋盘数据（`Board.cells`）
     * @param komi  贴目，用 [Komi.forSize] 获取
     */
    fun score(cells: ByteArray, komi: Double = Komi.STANDARD): Score {
        var blackStones = 0
        var whiteStones = 0
        for (i in 0 until cellCount) {
            when (cells[i].toInt()) {
                1 -> blackStones++
                2 -> whiteStones++
            }
        }

        visited.fill(false)
        var blackTerritory = 0
        var whiteTerritory = 0
        var neutral = 0

        for (start in 0 until cellCount) {
            if (visited[start] || cells[start].toInt() != 0) continue

            // 迭代式 flood fill 整个空区域，同时记录它接触到的颜色
            var top = 0
            var regionSize = 0
            var touchesBlack = false
            var touchesWhite = false

            visited[start] = true
            stack[top++] = start

            while (top > 0) {
                val current = stack[--top]
                regionSize++

                val n = BoardGeometry.neighbors(size, current, neighborBuf)
                for (k in 0 until n) {
                    val nb = neighborBuf[k]
                    when (cells[nb].toInt()) {
                        0 -> if (!visited[nb]) {
                            visited[nb] = true
                            stack[top++] = nb
                        }
                        1 -> touchesBlack = true
                        2 -> touchesWhite = true
                    }
                }
            }

            when {
                touchesBlack && !touchesWhite -> blackTerritory += regionSize
                touchesWhite && !touchesBlack -> whiteTerritory += regionSize
                // 双方都接触 = 单官；空盘时两色都不接触，同样落入此分支
                else -> neutral += regionSize
            }
        }

        return Score(
            boardPoints = cellCount,
            blackStones = blackStones,
            whiteStones = whiteStones,
            blackTerritory = blackTerritory,
            whiteTerritory = whiteTerritory,
            neutral = neutral,
            komi = komi,
        )
    }
}
