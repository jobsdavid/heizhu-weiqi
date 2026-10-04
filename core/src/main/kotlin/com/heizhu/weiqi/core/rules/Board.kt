package com.heizhu.weiqi.core.rules

/**
 * 围棋棋盘状态。
 *
 * ## 数据布局
 * 一维 [cells]（ByteArray），长度 `size * size`，索引 = `y * size + x`。
 * 值：0=空 1=黑 2=白。
 *
 * 选扁平 ByteArray 而非 `Array<Array<Stone>>`：MCTS 每个 playout 都要拷贝
 * 或重建棋盘，扁平数组的拷贝快一个数量级。
 *
 * ## 规则实现范围
 * - 打劫：**简单劫（Simple Ko）**。不实现位置超级劫，因为「这个点莫名其妙
 *   不能下」对孩子是负反馈，且简单劫已是中国规则下的标准判定。
 * - 自杀：禁止。提子之后才判定自身气，顺序见 [tryPlay] 注释。
 *
 * ⚠️ 非线程安全。每个线程（每个 MCTS playout 线程）必须持有独立实例。
 */
class Board(val size: Int) {

    val cellCount: Int = size * size

    /** 棋盘上的点。外部只读，**不要直接写入**——请用 [play]。 */
    val cells: ByteArray = ByteArray(cellCount)

    private val finder = GroupFinder(size)
    private val neighborBuf = IntArray(4)
    private val capturedScratch = IntArray(cellCount)

    /** 当前劫争禁着点（扁平索引）。-1 表示当前无劫。 */
    var koPoint: Int = -1
        private set

    /** 最近一手。`Point.PASS` 表示尚未落子或刚停一手。 */
    var lastMove: Point = Point.PASS
        private set

    /** 黑方**提掉的白子**总数（界面「黑方提子」显示此值）。 */
    var blackCaptured: Int = 0
        private set

    /** 白方**提掉的黑子**总数。 */
    var whiteCaptured: Int = 0
        private set

    // ===============================================================
    // 读取
    // ===============================================================

    fun inBounds(x: Int, y: Int): Boolean =
        x >= 0 && y >= 0 && x < size && y < size

    fun stoneAt(index: Int): Stone = Stone.of(cells[index])

    fun stoneAt(x: Int, y: Int): Stone =
        if (inBounds(x, y)) Stone.of(cells[BoardGeometry.index(size, x, y)]) else Stone.EMPTY

    fun isEmpty(index: Int): Boolean = cells[index].toInt() == 0

    /** 统计棋盘上某色的棋子总数。数子阶段使用。 */
    fun countStones(color: Stone): Int {
        val code = color.code
        var n = 0
        for (i in 0 until cellCount) if (cells[i] == code) n++
        return n
    }

    /** 棋盘上是否一子未落 */
    fun isEmpty(): Boolean {
        for (i in 0 until cellCount) if (cells[i].toInt() != 0) return false
        return true
    }

    // ===============================================================
    // 落子
    // ===============================================================

    /** 正式落子，会改变棋盘状态。 */
    fun play(x: Int, y: Int, color: Stone): PlayOutcome =
        tryPlay(x, y, color, commit = true)

    fun play(point: Point, color: Stone): PlayOutcome =
        tryPlay(point.x, point.y, color, commit = true)

    /**
     * 落子预览：判断该点能否落子、会提几子，**不改变棋盘**。
     *
     * 光标悬停在棋盘上时调用，用于实时显示「提 2 子」/「打劫，不能提回」等提示。
     */
    fun preview(x: Int, y: Int, color: Stone): PlayOutcome =
        tryPlay(x, y, color, commit = false)

    private fun tryPlay(x: Int, y: Int, color: Stone, commit: Boolean): PlayOutcome {
        require(color != Stone.EMPTY) { "不能落空子" }

        if (!inBounds(x, y)) return PlayOutcome.OutOfBounds
        val index = BoardGeometry.index(size, x, y)

        if (cells[index].toInt() != 0) return PlayOutcome.Occupied
        if (index == koPoint) return PlayOutcome.Ko

        val myCode = color.code
        val oppCode = color.opponent.code

        // ① 试落
        cells[index] = myCode

        // ② 提掉相邻的、无气的对方块。
        //
        //    边找边清：某块被清空后，它的点变成 0，不再匹配 oppCode，
        //    因此后续邻居检查会自然跳过它 —— 这就实现了「同块不重复提」
        //    的去重，无需额外的标记数组。
        //
        //    这么做安全的前提：同色的相邻块必然是**同一块**，所以清空块 A
        //    不可能给另一块 B 增气（A、B 若相邻则本就同块）。
        var capturedCount = 0
        val neighborCount = BoardGeometry.neighbors(size, index, neighborBuf)
        for (k in 0 until neighborCount) {
            val nb = neighborBuf[k]
            if (cells[nb] == oppCode) {
                if (finder.findLiberties(cells, nb) == 0) {
                    val groupSize = finder.findGroup(cells, nb)
                    for (g in 0 until groupSize) {
                        val p = finder.groupBuffer[g]
                        capturedScratch[capturedCount++] = p
                        cells[p] = 0
                    }
                }
            }
        }

        // ③ 自杀判定。
        //
        //    必须在**提子之后**才算自己的气：提子腾出的空点可能恰好是自己
        //    新的气。若把这一步挪到提子之前，合法的「提子逃出」会被误判为
        //    自杀 —— 这是围棋规则实现里最常见的 bug。
        val ownLiberties = finder.findLiberties(cells, index)
        if (ownLiberties == 0) {
            rollback(index, oppCode, capturedCount)
            return PlayOutcome.Suicide
        }

        val captured = capturedScratch.copyOf(capturedCount)

        if (!commit) {
            // 预览模式：判定完立即撤回，棋盘保持原样
            rollback(index, oppCode, capturedCount)
            return PlayOutcome.Ok(captured, ownLiberties)
        }

        // ④ 提交，更新劫点与统计
        //
        //    简单劫规则：本手恰好只提了一子时，那个被提的点成为对方的
        //    禁着点（对方不能「立即」提回）。隔一手后自动解除 —— 因为
        //    任何一手落子都会重设 koPoint，而 pass 会清空它。
        koPoint = if (capturedCount == 1) capturedScratch[0] else -1
        lastMove = Point(x, y)
        if (color == Stone.BLACK) {
            blackCaptured += capturedCount
        } else {
            whiteCaptured += capturedCount
        }

        return PlayOutcome.Ok(captured, ownLiberties)
    }

    /** 撤销一次试落：恢复被提的子，并清掉落子点。 */
    private fun rollback(index: Int, oppCode: Byte, capturedCount: Int) {
        for (c in 0 until capturedCount) cells[capturedScratch[c]] = oppCode
        cells[index] = 0
    }

    /**
     * 停一手（pass）。
     *
     * 同时清除劫点：隔了一手之后，劫争的「不能立即提回」限制即失效。
     */
    fun pass() {
        koPoint = -1
        lastMove = Point.PASS
    }

    // ===============================================================
    // 眼判定 —— 供 AI 禁止「填自己的眼」使用
    // ===============================================================

    /**
     * [index] 是否为 [color] 的**眼**（即落在这里等同于自毁）。
     *
     * 判定依据：
     * 1. 该点为空；
     * 2. 四个正交邻居全部是 [color]（边界点邻居少于 4，全部满足即可）；
     * 3. 非边缘点额外要求四个对角中至少 3 个是 [color]，用于排除**假眼**
     *    （假眼最终会被对方打吃提掉，等同于填自己的眼）。
     *
     * 用途：MCTS 的走子策略里硬禁止填自己的眼。
     * 不做这一步的话，AI 会把自己的实地填掉 —— 这是随机走子围棋 AI
     * 最致命的症状，会直接毁掉对局体验。
     */
    fun isOwnEye(index: Int, color: Stone): Boolean {
        if (cells[index].toInt() != 0) return false
        val code = color.code

        val n = BoardGeometry.neighbors(size, index, neighborBuf)
        for (k in 0 until n) {
            if (cells[neighborBuf[k]] != code) return false
        }

        val col = index % size
        val row = index / size
        if (col in 1 until size - 1 && row in 1 until size - 1) {
            var friendlyCorners = 0
            if (cells[index - size - 1] == code) friendlyCorners++
            if (cells[index - size + 1] == code) friendlyCorners++
            if (cells[index + size - 1] == code) friendlyCorners++
            if (cells[index + size + 1] == code) friendlyCorners++
            if (friendlyCorners < 3) return false
        }

        return true
    }

    // ===============================================================
    // 拷贝 —— MCTS 热路径的主力操作
    // ===============================================================

    fun copy(): Board {
        val b = Board(size)
        b.copyFrom(this)
        return b
    }

    /**
     * 用 [other] 的内容覆盖本棋盘，**复用已有的内部缓冲区**。
     *
     * MCTS 每个 playout 开头都会调用它来把棋盘重置回根局面。
     * 相比每次 `copy()` 新建对象，能省下 GroupFinder 三个 int 数组的分配
     * （大盘每次几 KB），在每秒上万次 playout 的量级下差别显著。
     */
    fun copyFrom(other: Board) {
        require(other.size == size) { "棋盘尺寸不一致：$size vs ${other.size}" }
        other.cells.copyInto(cells)
        koPoint = other.koPoint
        lastMove = other.lastMove
        blackCaptured = other.blackCaptured
        whiteCaptured = other.whiteCaptured
    }

    /** 清空棋盘，回到开局状态。缓冲区保持不变，故无需重新分配。 */
    fun clear() {
        cells.fill(0)
        koPoint = -1
        lastMove = Point.PASS
        blackCaptured = 0
        whiteCaptured = 0
    }

    /**
     * 从快照恢复棋盘状态，供对局悔棋使用。
     *
     * 只接受**裸数据**而非整个 Board 对象，是为了让调用方能存下轻量快照
     * （一次悔棋要回退两手，一盘对局最多可能存几百份快照，不能每份都带
     * 一套 GroupFinder 缓冲区）。
     */
    fun restoreFrom(
        source: ByteArray,
        koPoint: Int,
        lastMove: Point,
        blackCaptured: Int,
        whiteCaptured: Int,
    ) {
        require(source.size == cellCount) { "快照尺寸不匹配：${source.size} vs $cellCount" }
        source.copyInto(cells)
        this.koPoint = koPoint
        this.lastMove = lastMove
        this.blackCaptured = blackCaptured
        this.whiteCaptured = whiteCaptured
    }

    override fun toString(): String {
        val sb = StringBuilder()
        for (y in 0 until size) {
            for (x in 0 until size) {
                sb.append(
                    when (stoneAt(x, y)) {
                        Stone.BLACK -> 'X'
                        Stone.WHITE -> 'O'
                        Stone.EMPTY -> '.'
                    }
                )
            }
            sb.append('\n')
        }
        return sb.toString()
    }
}
