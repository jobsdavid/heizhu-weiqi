package com.heizhu.weiqi.core.ai

import com.heizhu.weiqi.core.rules.Board
import com.heizhu.weiqi.core.rules.BoardGeometry
import com.heizhu.weiqi.core.rules.GroupFinder
import com.heizhu.weiqi.core.rules.Komi
import com.heizhu.weiqi.core.rules.PlayOutcome
import com.heizhu.weiqi.core.rules.Scorer
import com.heizhu.weiqi.core.rules.Stone
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 蒙特卡洛树搜索（MCTS / UCT）围棋引擎。
 *
 * ## 为什么自己实现而不用 KataGo / GNU Go
 * - KataGo 依赖神经网络推理，目标设备的 GPU 是 Mali-G57 **MC1**（单核）且无 NPU，
 *   单步推理就是秒级，跑不动。
 * - GNU Go 棋力固定，**做不出五档细腻分级** —— 而「AI 要能被孩子打败」正是本应用的核心需求。
 * - 自研 MCTS 可以精确控制「AI 有多弱」，这才是练习工具真正需要的能力。
 *
 * ## 搜索流程（标准四步）
 * 1. **Selection** 从根节点沿 UCB 最优路径下行
 * 2. **Expansion** 展开一个未试着法
 * 3. **Simulation** 随机走子到终局，数子判定胜者
 * 4. **Backpropagation** 回传胜负
 *
 * 每个节点保存的 `wins` 是**以该节点轮到走棋的一方为视角**的胜场数
 * （negamax 风格），因此 Selection 在每一层都是取 UCB 最大值。
 *
 * ## 线程与取消
 * ⚠️ 非线程安全。每个线程持有独立实例。
 * [findBestMove] 是 suspend 函数，内部周期性检查协程取消 ——
 * 这样孩子按「悔棋」时能立刻中断 AI 的思考，而不是干等几秒。
 *
 * @param size 棋盘边长（9 / 13 / 19）
 * @param komi 贴目，默认取 [Komi.forSize]
 */
class MctsEngine(
    private val size: Int,
    private val komi: Double = Komi.forSize(size),
    /**
     * 可选的小网络（由 KataGo 蒸馏而来）。
     *
     * 给定时走「网络引导」路径：用网络策略剪枝根候选、用网络价值做一手前瞻，
     * 替掉随机 rollout 评估。**不传则行为与以前完全一致** —— 老路径的测试与
     * 随机 rollout 的实现都保留，便于对照（也便于必要时回退）。
     */
    private val net: PolicyValueNet? = null,
    /**
     * 终局裁判用的网络。**应当比 [net] 更强**（app 侧统一传「大师」档权重）。
     *
     * ## 为什么要跟选点网络分开（2026-10-05 实测）
     *
     * 判「该不该收工」靠的是价值头对「对方下一手能赚多少」的敏感度。
     * 低难度档的网络太粗糙：入门档只有 `16×1 @100 样本`，实测它会给出
     * 「对方走任何一手，我的目差都不变」（`cost=0.0`）—— 而对方明明在吃子。
     * 于是它一路停手被吃光，孩子看到的就是「电脑不动了，我一路吃」
     * （真机记录里留下「9 路 · 入门 黑 81 : 0 白」）。
     *
     * 选点仍用 [net]（保住档位难度），只把**这一步判断**换成强网络 —— 裁判要准，
     * 棋手可以弱。这样入门档仍然是入门的棋力，但不会再摆烂。
     *
     * null = 用 [net]（老行为，便于对照与回退）。
     */
    private val judgeNet: PolicyValueNet? = null,
) {

    private val cellCount = size * size
    private val policy = PlayoutPolicy(size)
    private val scorer = Scorer(size)

    /** 根节点战术层算气用。只在 [collectCandidates] 里用，与 playout 无关。 */
    private val libertyFinder = GroupFinder(size)
    private val neighborBuf = IntArray(4)

    /** 复用的模拟棋盘，每个 playout 开头用 copyFrom 重置，避免反复分配 */
    private val simBoard = Board(size)

    /** 网络路径第二层前瞻用的第二块棋盘（复用，避免每手分配） */
    private val probeBoard = Board(size)

    /**
     * 单次 playout 的手数上限。
     *
     * 设为棋盘总点数：随机走子最终会把棋盘填满，用这个值当兜底即可。
     * 真正的耗时控制靠 [Difficulty.timeBudgetMs] 的硬时间预算。
     */
    private val maxPlayoutMoves = cellCount

    /** 深层每个节点最多展开多少个子节点，防止树无限变宽 */
    private val maxChildrenPerNode = 16

    /**
     * 收工规则里「多少子才算还得继续下」的阈值（子数 + 围空，单位：子）。
     *
     * 取 2：判据是 `> before + 2`，即"填自己的地/填单官"（0~1 点）不算得利、
     * 不至于让对局永远结束不了；而大块攻防（10+ 点）算得利。
     *
     * ⚠️ 这一条**本身不足以防住"落后方一路停手"**（实测：38 局互殴 31% 出现，
     * 净胜 ±80 目）—— 因为静态面积估算分不清"真得利"和"填子"，吃子方每手只涨
     * 1~2 点，正好落在阈值内被判成"无利可图"。
     * ⇒ 试过的四条补丁（阈值 2→1、阈值→0、改按盘面差、加"能提子就不许停手"）
     * 全部失败，详见类内收工判据处的记录。真正的修法是让终局判断改用**网络价值**。
     */
    /**
     * 停手原因诊断日志开关（排查用）：`WEIQI_PASS_LOG=1` 时把每次"返回停一手"的
     * 出口和现场打到 stderr。默认关闭 —— 产品里不该有调试输出。
     */
    private val passLogEnabled = System.getenv("WEIQI_PASS_LOG") == "1"

    private val PASS_GAIN_THRESHOLD = 2

    /**
     * 上一次搜索的统计。用于**实测目标设备上的算力**——
     * 电视 CPU 比开发机弱得多，「每秒能跑多少次 playout」直接决定
     * 各难度档的棋力上限，必须能观测、能调参，不能靠猜。
     */
    var lastStats: SearchStats? = null
        private set

    /**
     * 搜索当前局面的最佳着法。
     *
     * @return 落子点的扁平索引；**-1 表示应当停一手（pass）**。
     */
    suspend fun findBestMove(
        board: Board,
        color: Stone,
        difficulty: Difficulty,
        rng: Random = Random.Default,
        /**
         * 模拟次数上限，null = 用 [Difficulty.maxPlayouts]。
         *
         * 存在的理由：**评测保真**。同一档在电视上受时间限制（如 9 路大师 3 秒只跑
         * 3222 次），在更快的开发机上却会跑满 maxPlayouts（20000 次），
         * 于是评测出来的棋力比电视上真实的高一截。评测台用它把次数封到电视的实测值。
         * 生产路径不传这个参数，行为与以前完全一致。
         */
        playoutCap: Int? = null,
        /**
         * 每手**最长**思考时长（毫秒），null = 用难度自带的预算。对应 app 设置里的
         * 「AI 最长思考（秒）」。
         *
         * ⚠️ **只作用于网络路径**。网络路径下算得越多越准，这个上限才有意义；
         * 随机 rollout 路径上给 15~30 秒等于让孩子干等 —— 实测模拟次数翻 27 倍，
         * 平均丢目 7.32 → 8.89（t=1.24，噪声内），换不来可测量的棋力。
         * 所以那条路径继续用难度自带的短预算。
         */
        timeBudgetOverrideMs: Long? = null,
    ): Int {
        val startedAt = System.currentTimeMillis()
        val rootCandidates = generateRootCandidates(board, color, difficulty)
        if (rootCandidates.isEmpty()) {
            var st = 0
            for (i in 0 until cellCount) if (board.cells[i].toInt() != 0) st++
            if (passLogEnabled) System.err.println(
                "[PASS-1/候选为空] color=$color 盘上子=$st 难度=${difficulty.id} " +
                    "radius=${difficulty.localRadius} 空点=${cellCount - st}"
            )
            lastStats = SearchStats(0, System.currentTimeMillis() - startedAt, 0)
            return PASS_MOVE
        }

        // 收工规则（围棋的终局机制）：**双方都无利可图时才停手**。
        //
        // 为什么需要这条：引擎只要还有空点就会一直下，于是「双方停一手 → 数子结算」
        // 永远走不到，孩子下腻了只能认输，而认输是不数子的，结果页的比分形同虚设。
        //
        // 判据曾经是单边的：「我这手改善不了我的目差 → 停手」。实测这会让对局崩坏：
        // 静态地盘估算（子数 + 围空）**看不见「防守能救活一大片棋」的价值** ——
        // 白方一块棋将被杀时，算出来是「加一子 +1、自己的空 -1 = 净 0」，判定「没用」→ 停手；
        // 而黑方能靠吃子实实在在得分 → 一直下。结果白方反复送停一手、黑方吃光全盘
        // （实测同档对局终局黑方 39/81 子，KataGo 也判 +75.5 目）。
        // 发到电视上，孩子看到的就是「对手一直在我家里填子」，完全不像围棋。
        //
        // 围棋的正确语义是：**双方一致认为终局**才停手（两人各自认为没有获利手段了）。
        // 所以必须两边都无利可图。这么改也天然接上「双方停一手 → 数子结算」的终局机制。
        //
        // 两道闸门保证不提前收工：
        //   1. 空点 ≤ 40%：否则数子这个判据本身会退化 —— 空盘上「所有空点都算我的」，
        //      随手下一手也「不提高地盘」，会直接把开局误判成收工。
        //   2. 任一方还有能提高（子数 + 围空）的一手，就都继续下。
        //
        // ⚠️ 已知未解决（2026-10-02 实测，四条补丁全试过、全部失败）：
        //   落后方仍会出现"一路停手、被对手逐步吃光"（38 局互殴里 31%，净胜 ±80 目）。
        //   试过并否决的修法：① 阈值 2→1（更差，连停率升到 70%）② 收益改按"盘面差"
        //   衡量（定型局面永不收工，既有用例当场变红）③ 加"对手能提子就不许停手"
        //   这条精确判据（实测提子收益是 +3 > 阈值 2，旧判据本就拦得住 ⇒ 冗余）。
        //   根因：**静态面积估算分不清"翻 1 目的地"和"填单官"**，而吃人靠的正是
        //   一串 +1 的翻转；任何阈值都切不开这两类。
        //   ⇒ 真正的修法是让终局判断改用**网络价值**（端侧已有可用的价值头），
        //     这是一次结构性改动，不再靠打补丁。
        // 收工规则（围棋的终局机制）：**双方都无利可图时才停手**。
        //
        // ⚠️ 判据在 2026-10-05 换了**评估口径**（不是调参）：
        //   网络可用 → 用**网络价值头**判断"还有没有值得下的手"
        //   网络不可用 → 回退到原来的静态面积估算（保证老路径行为不变）
        //
        // 为什么非换不可：静态面积估算**看不见防守的价值**。一块棋将被杀时，
        // 网络会说"这一手能救回一大片"，面积估算只看到"加一子、自己的空少一点 = 净 0"，
        // 判"没收益"→ 停手 → 对手一路吃光整盘。孩子在真机上遇到的就是这个：
        // 历史战绩里留下「9 路 · 入门 黑 81 : 0 白 · 129 手」——白棋（AI）被吃光全盘。
        // 四条静态口径的补丁（阈值 2→1 / 改盘面差 / 加提子判据 / 不动）实测全败，
        // 说明病在口径本身。
        //
        // 两道闸门仍然保留：
        //   1. 空点 ≤ 40%：否则数子/估值在空盘上退化，开局随手一停就被误判成「没得下了」。
        //   2. 任一方还有能改善目差的一手，就都继续下。
        // ⚠️ 2026-10-05 的两次尝试都**实测无效**，已回滚，别再走：
        //   ① 收工判据换成「网络价值口径」（判"停手会不会亏"）→ 废局 10 → 15 局
        //   ② 引入 judgeNet（用大师网络当终局裁判）→ 无明显改善
        //   ③ 加「落后方不许停手」→ 废局 10 → 40 局，最差
        // 现在的判据回到发货版的静态面积口径 —— **保持基准干净**，
        // 摆烂问题留待用 app_baseline.py（产品口径探针）定位后一次修对。
        // ⚠️ 收工判据**不受档位视野限制**（2026-10-05 实测定位的机制，别改回去）：
        // 原来这里用 `generateRootCandidates(..., difficulty)` 去猜"对方还有没有得利手段"，
        // 用的却是**我自己的档位视野**。低档视野窄（入门 localRadius=1）⇒ 看不见远处的杀着
        // ⇒ 判"无利可图" ⇒ 停手 ⇒ **提前把地盘让出去**。
        // 那不是"棋感弱"，是变相放水；更要命的是双方各按自己的档位评估 ⇒ 判据不一致
        // ⇒ 交替停手、对局到不了终点（实测：跨档对局黑方净胜从 3 子掉到 0~1 子，
        //   而"配置不同、网络相同"的对照同样偏 19 个点 ⇒ 根因就是这条）。
        // 收工是"盘面到底定型没有"的**事实判断**，两侧必须用同一个更宽的视野；
        // 「想得浅」仍由搜索侧体现（候选池/前瞻层数），不在这里。
        val settleCandidates = { c: Stone ->
            collectCandidates(board, c, radius = 0, tacticAssist = difficulty.tacticAssist)
        }
        val nobodyLosesByPassing =
            !anyMoveGainsOver(board, color, settleCandidates(color), PASS_GAIN_THRESHOLD) &&
                !anyMoveGainsOver(board, color.opponent, settleCandidates(color.opponent), PASS_GAIN_THRESHOLD)
        // ⚠️ 2026-10-05 试过并**回滚**的一版：给判据加「落后方不许停手」。
        // 理由是"落后方没有停手的资格"，但实测**适得其反**：落后方被迫绝望填子，
        // 局面更崩、对局还打不完 —— 9 路 100 局里被判废的从 10 局涨到 **40 局**。
        // ⇒ 别再用「不许停」这种硬堵的办法，问题的根在**裁判的网络太弱**（见下面 judgeNet）。
        // ⚠️ 硬闸门：盘面远未定型时**任何一方都不许收工**（2026-10-05 实测的早终局缺陷）
        //
        // 实测现象：跨档对局里，entry 配置执黑时**48 手就双方停手**
        // （9 路 81 点，此时盘上才 ~50 子、空点还有 30+），对手白捡一大片，
        // 黑白胜率被扭曲到 25%/75%；而同档自战是正常的 111 手。
        // 这条与档位无关、不猜对手，只问一个事实：**盘上子数够不够多**。
        // 取"点数的一半"作门槛（9 路=40 子、13 路=84 子）：真终局时盘上通常远多于它，
        // 而"提前收工"一律被挡住。这不是"不许停"那种硬堵（那条实测把局面搞得更崩），
        // 它只否掉**明显没下完**的局面。
        var stonesOnBoard = 0
        for (i in 0 until cellCount) if (board.cells[i].toInt() != 0) stonesOnBoard++
        // ⚠️ 用 hasDamePoint（结构判据）替代 isSettledEnough（空点数代理指标）：
        //    后者会被提子扰动而自锁，导致对局永远靠 1.6 倍手数兜底结束。
        if (stonesOnBoard * 2 >= cellCount && !hasDamePoint(board) && nobodyLosesByPassing) {
            if (passLogEnabled) System.err.println("[PASS-2/收工判据] color=$color 盘上子=$stonesOnBoard 难度=${difficulty.id}")
            lastStats = SearchStats(0, System.currentTimeMillis() - startedAt, rootCandidates.size)
            return PASS_MOVE
        }

        // 有网络就走网络引导路径。原因（实测）：随机 rollout 的评估噪声是棋力瓶颈 ——
        // 同一档同一局面换个种子，丢目能从 0 摆到 21 目；而把模拟次数从 300 加到 8000，
        // 丢目差 t=1.24（噪声内）。所以问题不在搜多少，而在"用什么评估局面"。
        net?.let {
            return findBestMoveWithNet(
                board, color, difficulty, it, rng, startedAt,
                budgetMs = timeBudgetOverrideMs ?: difficulty.timeBudgetMs,
            )
        }

        val root = Node(move = PASS_MOVE, parent = null, toMove = color)
        root.candidates = rootCandidates

        val deadline = System.currentTimeMillis() + difficulty.timeBudgetMs
        var playouts = 0
        var countdownToCancelCheck = CANCEL_CHECK_INTERVAL

        val playoutLimit = playoutCap ?: difficulty.maxPlayouts
        while (playouts < playoutLimit) {
            // 时间预算优先：宁可少搜，也不能让孩子等
            if (System.currentTimeMillis() >= deadline) break

            if (--countdownToCancelCheck <= 0) {
                countdownToCancelCheck = CANCEL_CHECK_INTERVAL
                currentCoroutineContext().ensureActive()
            }

            simBoard.copyFrom(board)
            var node = root

            // ---------- 1. Selection ----------
            while (!node.hasUntried && node.children.isNotEmpty()) {
                node = selectChild(node)
                applyMove(simBoard, node.move, node.toMove)
            }

            // ---------- 2. Expansion ----------
            val child = expand(node, rng)
            if (child != null) node = child

            // ---------- 3. Simulation ----------
            val winner = simulate(simBoard, node.toMove, rng)

            // ---------- 4. Backpropagation ----------
            var current: Node? = node
            while (current != null) {
                current.visits++
                // wins 以「该节点轮到走棋的一方」为视角，故此处比较 toMove
                if (current.toMove == winner) current.wins += 1.0
                current = current.parent
            }

            playouts++
        }

        lastStats = SearchStats(
            playouts = playouts,
            elapsedMs = System.currentTimeMillis() - startedAt,
            candidateCount = rootCandidates.size,
        )
        return selectFinalMove(root, difficulty, rng)
    }

    /**
     * 网络引导的选点。
     *
     * 为什么不是「每次模拟都过一遍网络」：端侧算力有限（电视 CPU 一次前向几十毫秒），
     * 那样在 6 秒预算里跑不了几次搜索。改成「策略剪枝 + 一手前瞻」：
     *   · 根候选 = 网络策略前 K 个（K 由难度给，是网络路径下**真正起作用**的档位旋钮）
     *   · 每个候选落子后，用网络评估「对方视角」的目差，取对我最优的
     * 前向次数被控制在 K+1 次（电视上不到 1 秒），却用上了网络的判断力。
     *
     * 另掺入引擎启发式的头部候选兜底：网络是蒸馏来的，样本覆盖不到的局部战术形状
     * 仍可能判错，而「能提就提」这类铁律不该被漏掉。
     */
    private fun findBestMoveWithNet(
        board: Board,
        color: Stone,
        difficulty: Difficulty,
        net: PolicyValueNet,
        rng: Random,
        startedAt: Long,
        /** 本轮选点的**最长**时长（不是固定耗时：想清楚就提前收工）。 */
        budgetMs: Long,
    ): Int {
        val deadline = startedAt + budgetMs
        val rootEval = net.evaluate(board.cells, color.code)

        // 候选池大小随预算伸缩 —— 这是「AI 最长思考时间」设置的**作用点之一**
        // （另一个是循环的收工判据：下一次前向放不下就停，见下面注释）。
        //
        // 实测发现的问题：候选数（netTopK）和前瞻层数（netPlies）原来都按档位写死，
        // 一手在电视上约 6 秒就算完了 —— 用户把「最长思考时间」设成 15/20/25/30 秒，
        // 多出来的时间**完全没用上**，那个设置就是个摆设（设置项注释里写的
        // "用满的方式是加深前瞻"描述的是一个还没实现的行为）。
        //
        // 修法：预算每超过**设置下限**一档，候选池就跟着放宽一档。
        // 因为下面的评估循环本来就在截止时间退出，"池子变大"必然转化为"搜得更宽"，
        // 而不是空转；时间没给够也不会硬凑（想清楚就提前收工，这是"最长"的语义）。
        //
        // ⚠️ 基准必须用**设置下限**（app 里那一项的默认值），不能用该档自带的预算：
        // 自带预算越小的档（低难度档）算出来的倍数越大，等于"越弱的档加宽越多"，
        // 恰好把低档拖慢、把高档限制住 —— 方向整个反了。默认值加宽倍数为 1，
        // 即默认设置下各档行为与加宽前完全一致，只有用户主动调大才会变。
        val widen = (budgetMs.toDouble() / MIN_THINK_BUDGET_MS).coerceIn(1.0, 3.0)
        val poolSize = (difficulty.netTopK * widen).toInt().coerceAtLeast(difficulty.netTopK)
        // 开局判定：与 collectCandidates 同一口径（盘上子数 < 边长）
        var netStoneCount = 0
        for (i in 0 until cellCount) if (board.cells[i].toInt() != 0) netStoneCount++
        val netOpening = netStoneCount < size
        val byPolicy = (0 until cellCount)
            // ⚠️ 必须用 isConsiderable：只按「空点」筛会漏掉"自己的真眼"，
            //    那会让 AI 填掉自己的眼、大块被提（见 isConsiderable 的注释）。
            .filter { isConsiderable(board, it, color, netOpening) && rootEval.policy[it] > 0f }
            .sortedByDescending { rootEval.policy[it] }
            .take(poolSize)

        val candidates = ArrayList<Int>(byPolicy.size + NET_HEURISTIC_TAIL)
        candidates.addAll(byPolicy)
        var tail = 0
        for (m in generateRootCandidates(board, color, difficulty)) {
            if (tail >= NET_HEURISTIC_TAIL) break
            if (!candidates.contains(m)) {
                candidates.add(m)
                tail++
            }
        }
        if (candidates.isEmpty()) {
            if (passLogEnabled) System.err.println("[PASS-3/网络候选为空] color=$color 难度=${difficulty.id}")
            lastStats = SearchStats(0, System.currentTimeMillis() - startedAt, 0)
            return PASS_MOVE
        }

        // 先算出每个候选的价值，最后统一挑选 —— 因为挑选方式取决于难度温度，
        // 不能在循环里"见到更高的就替换"（那样温度就没用了）
        val scored = ArrayList<Pair<Int, Float>>(candidates.size)
        // ⚠️ 初始着法不能直接取 candidates[0]：网络给的候选只按"空点"筛过，
        // **没有滤掉劫禁着点/自杀点**（MCTS 路径用的是合法性校验过的候选，不会有这问题）。
        // 若这一手恰好非法、且后面所有候选都因非法被跳过，就会返回一个非法着法 ——
        // 实测被对局评测台抓到（"illegal move 3,4 for WHITE"）。所以初始值留空、
        // 只用**实际落子成功过**的候选，一个都没有就停一手。
        var bestMove = PASS_MOVE
        var bestValue = -Float.MAX_VALUE
        var evaluated = 0
        val oppColor = color.opponent
        // 单次前向的**实测**均值（本次搜索内自适应）：已用时间 ÷ 已评估次数。
        //
        // 存在的理由：截止时间只在两次前向**之间**检查，所以单次前向比剩余预算长时
        // 根本无法截断 —— 设定 15 秒、实测跑出 15.32 秒就是这么来的。
        // 有了它才能改成「下一次前向放不下就收工」，让「最长思考」真的是最长。
        fun forwardMsAt(now: Long): Double =
            if (evaluated <= 0) 0.0 else (now - startedAt).toDouble() / evaluated

        var firstCandidate = true
        for (mv in candidates) {
            // 收工判据：不是「到点才停」，而是「**下一轮评估放不下**就停」。
            // 下一轮 = 1 次对方评估 + 最多 REPLY_CANDIDATES 次应手评估。
            // ⚠️ 第一个候选无条件评估：否则预算极小时会一个候选都不算，直接走到
            // 「scored 为空 → 停一手」—— AI 莫名停手比超时严重得多。
            val now = System.currentTimeMillis()
            if (!firstCandidate) {
                val nextRound = forwardMsAt(now) * (1 + REPLY_CANDIDATES)
                if (now >= deadline || (nextRound > 0.0 && now + nextRound > deadline)) break
            }
            firstCandidate = false
            simBoard.copyFrom(board)
            if (!applyMove(simBoard, mv, color)) continue

            // ---- 第二层：对方会怎么应？----
            // 网络给的是**对方视角**的目差；只有一层前瞻时无法知道对方的最佳应手，
            // 很多"看着不错"的手其实是送的。这里对对方的最佳应手做最小化（minimax）。
            val oppEval = net.evaluate(simBoard.cells, oppColor.code)
            evaluated++
            // 低难度档只用一层前瞻（netPlies = 1）：这是档位之间最可靠的强弱旋钮
            val oppCands = if (difficulty.netPlies < 2) {
                emptyList()
            } else {
                (0 until cellCount)
                    .filter { simBoard.cells[it].toInt() == 0 && oppEval.policy[it] > 0f }
                    .sortedByDescending { oppEval.policy[it] }
                    .take(REPLY_CANDIDATES)
            }

            var worstForMe = Float.MAX_VALUE
            var sawReply = false
            for (om in oppCands) {
                // 同一个道理：这一次应手的前向放不下就收工。
                // 应手是**可选深度**，提前收工是安全的 —— 下面 sawReply=false 时
                // 会退回「我这手的即时价值」。
                val nowReply = System.currentTimeMillis()
                val oneForward = forwardMsAt(nowReply)
                if (nowReply >= deadline ||
                    (oneForward > 0.0 && nowReply + oneForward > deadline)
                ) {
                    break
                }
                probeBoard.copyFrom(simBoard)
                if (!applyMove(probeBoard, om, oppColor)) continue
                // 对方应完又轮到我，此时网络给的正是**我方视角**的目差
                val back = net.evaluate(probeBoard.cells, color.code)
                evaluated++
                sawReply = true
                if (back.scoreLead < worstForMe) worstForMe = back.scoreLead
            }
            // 对方无从应手（例如被我提光）时退回"我这手的即时价值"
            val myValue = if (sawReply) worstForMe else -oppEval.scoreLead
            scored.add(mv to myValue)
            if (myValue > bestValue) {
                bestValue = myValue
                bestMove = mv
            }
        }

        // 一个合法候选都没有 → 停一手（宁可停手也不能下非法手）
        if (scored.isEmpty()) {
            if (passLogEnabled) System.err.println(
                "[PASS-4/候选全非法或被战术排除] color=$color 难度=${difficulty.id} " +
                    "候选数=${candidates.size}"
            )
            lastStats = SearchStats(0, System.currentTimeMillis() - startedAt, candidates.size)
            return PASS_MOVE
        }
        if (bestMove == PASS_MOVE) bestMove = scored[0].first

        // ---- 按难度温度挑选：低难度档更随机 → 更弱；大师档温度 0 → 取最优 ----
        if (difficulty.temperature > 0f && scored.size > 1) {
            var sum = 0.0
            val weights = DoubleArray(scored.size)
            for (i in scored.indices) {
                weights[i] = kotlin.math.exp(
                    ((scored[i].second - bestValue) / (difficulty.temperature * VALUE_TEMP_SCALE)).toDouble(),
                )
                sum += weights[i]
            }
            if (sum > 0.0) {
                var pick = rng.nextDouble() * sum
                for (i in weights.indices) {
                    pick -= weights[i]
                    if (pick <= 0.0) {
                        bestMove = scored[i].first
                        break
                    }
                }
            }
        }

        // 低难度档保留「会犯错」的手感：按概率挑一个次优候选。
        // ⚠️ 必须从 **scored**（已经实际落子成功过的候选）里挑，不能从 candidates 挑 ——
        // candidates 只按空点筛过，含劫禁点/自杀点；从这里挑等于按概率下非法手。
        // 实测被对局评测台抓到（"illegal move 3,4 for WHITE"）；app 里因为上层会拒绝
        // 并改判停一手，所以一直没暴露，但那是"每四手送一个停一手"的隐性缺陷。
        if (difficulty.blunderRate > 0f && scored.size > 1 &&
            rng.nextFloat() < difficulty.blunderRate
        ) {
            val pool = scored.filter { it.first != bestMove }
            if (pool.isNotEmpty()) bestMove = pool[rng.nextInt(pool.size)].first
        }

        lastStats = SearchStats(
            playouts = evaluated,
            elapsedMs = System.currentTimeMillis() - startedAt,
            candidateCount = candidates.size,
        )
        return bestMove
    }

    /**
     * 一次搜索的统计快照。
     *
     * @param playouts      实际完成的模拟局数
     * @param elapsedMs     总耗时（毫秒），含候选生成与最终选择
     * @param candidateCount 根节点候选着法数
     */
    data class SearchStats(
        val playouts: Int,
        val elapsedMs: Long,
        val candidateCount: Int,
    ) {
        /** 每秒模拟局数 —— 目标设备算力的直接指标 */
        val playoutsPerSecond: Double
            get() = if (elapsedMs <= 0L) 0.0 else playouts * 1000.0 / elapsedMs
    }

    // ===============================================================
    // 候选生成
    // ===============================================================

    /**
     * 根节点候选：**完整扫描**所有合法着法，按启发式分数降序排列。
     *
     * 根节点只扫描一次，值得做得准 —— 候选的质量直接决定整棵树的上限。
     * 深层节点则改用 [policy] 采样生成（见 [expand]），因为那里每扩展一个节点
     * 都要重建候选，成本必须压下来。
     */
    private fun generateRootCandidates(
        board: Board,
        color: Stone,
        difficulty: Difficulty,
    ): IntArray {
        val restricted = collectCandidates(
            board, color, difficulty.localRadius, difficulty.tacticAssist,
        )
        if (restricted.isNotEmpty()) return restricted

        // 视野限制可能把候选清空 —— 最典型的就是**开局空盘**：
        // 棋盘上一个子都没有，「棋子附近」无从谈起，于是候选为空。
        // 若不放宽限制，低难度档会在空盘上直接停一手，对局根本开不了局。
        return collectCandidates(board, color, radius = 0, tacticAssist = difficulty.tacticAssist)
    }

    /**
     * 收集所有合法候选着法，按启发式分数降序排列。
     *
     * @param radius > 0 时只保留该半径内有棋子的点（低难度档的「视野限制」）
     */
    /**
     * 两条路径（随机 rollout / 网络引导）**必须共用**的「这个点值不值得考虑」规则。
     *
     * ⚠️ 为什么要有这个函数（2026-10-05 实测出来的真 bug）：
     * `collectCandidates` 里一直有「排除自己的真眼」这一条，但**网络路径没有**——
     * `findBestMoveWithNet` 的候选只按「空点 + 网络策略>0」筛过（注释里也写明了
     * 它没滤劫禁/自杀，后来补了合法性，却漏了「真眼」）。于是产品里只要加载了网络，
     * AI 就会往自己的真眼里落子：实测 9 路一盘棋 **填真眼 11 手**，
     * 自己的眼被填掉 ⇒ 大块被提 ⇒ 终局黑净胜 -27/-65 目（9 路全盘才 81 点）。
     * 这不是"棋力弱"，是规则级低级错误，也是"AI 会填自己的空"这个用户可见症状的根因。
     *
     * 抽成一个函数的理由：规则只能有一个源。两条路径各写一遍，迟早再次漂移。
     */
    private fun isConsiderable(board: Board, index: Int, color: Stone, opening: Boolean): Boolean {
        if (board.cells[index].toInt() != 0) return false   // 已有子
        if (board.isOwnEye(index, color)) return false      // 自己的真眼：填了等于自毁
        if (opening && lineOf(index) == 1) return false     // 开局一线：布局阶段价值极低
        return true
    }

    /**
     * 盘上**还有没有单官**（同时接触黑白两色的空点）。
     *
     * 「盘面是否真的定型」的**结构性**判据，用来替代原来的 `isSettledEnough`（空点数 ≤15%）。
     *
     * ⚠️ 为什么必须换掉"数空点"这个代理指标（2026-10-05 实测的自锁缺陷）：
     *   空点数会被**提子**扰动 —— 每提一子就多出一个空点。于是
     *   "填子 ⇒ 某块被提 ⇒ 空点又变多 ⇒ 永远不满足 ≤15% ⇒ 永远不停手" 形成自锁。
     *   实测后果：9 路每盘都下到 129 手（= 81 × 1.6 的兜底上限，**最长连停只有 1**）
     *   ⇒ 对局从来不是"双方停手"自然结束，而是被手数兜底掐断；13 路平均 264 手
     *   （≈169 × 1.6）同一回事。而在无限填子的过程里，某一方大块被提 ⇒ −27/−65/−81 目的惨败。
     *
     * 而"无单官"不会被提子扰动：边界一旦闭合就不会重新打开。数子制下无单官之后，
     * 往自家空里填子是零收益、往对方空里填子也无收益，唯一有收益的是"提掉死子" ——
     * 那种着法由 [anyMoveGainsOver]（面积增益 > 2）挡住，所以这里只管结构。
     */
    private fun hasDamePoint(board: Board): Boolean {
        for (i in 0 until cellCount) {
            if (board.cells[i].toInt() != 0) continue
            val n = BoardGeometry.neighbors(size, i, neighborBuf)
            var touchesBlack = false
            var touchesWhite = false
            for (k in 0 until n) {
                when (board.cells[neighborBuf[k]].toInt()) {
                    1 -> touchesBlack = true
                    2 -> touchesWhite = true
                }
            }
            if (touchesBlack && touchesWhite) return true
        }
        return false
    }

    private fun collectCandidates(
        board: Board,
        color: Stone,
        radius: Int,
        tacticAssist: Int,
    ): IntArray {
        val myCode = color.code
        var stoneCount = 0
        for (i in 0 until cellCount) if (board.cells[i].toInt() != 0) stoneCount++
        // 开局：棋盘上棋子比边还少。此时一线着法要排除（见下面的注释）
        val opening = stoneCount < size

        val scored = ArrayList<ScoredMove>(cellCount)
        for (index in 0 until cellCount) {
            // 共用规则：空点 + 非自己的真眼 + 开局不落一线
            if (!isConsiderable(board, index, color, opening)) continue
            // 低难度档的「视野限制」：只看得见棋子附近（仅此一路径有）
            if (radius > 0 && !isNearAnyStone(board, index, radius)) continue

            // 合法性校验（自杀 / 打劫），顺带白拿两个战术信号：
            //   captureCount —— 这手能提几子
            //   ownLiberties —— 这手落下后自己这块有几口气
            // 两者都是棋盘层做判定时本来就算出来的，不额外花时间。
            val outcome = board.preview(index % size, index / size, color)
            if (outcome !is PlayOutcome.Ok) continue

            val captureCount = outcome.captureCount
            // 自杀式落子：没提到子，且自己落下去立刻只剩一口气
            val selfAtari = captureCount == 0 && outcome.ownLiberties == 1
            // 逃出打吃：自己旁边有一块只剩一口气，这一手把它接到 2 口气以上
            val escapesAtari = captureCount == 0 && !selfAtari &&
                outcome.ownLiberties >= 2 &&
                hasAdjacentGroupInAtari(board, index, myCode)

            var score = policy.heuristicScore(board, index, color)
            if (captureCount > 0) score += CAPTURE_ORDER_BONUS * captureCount
            if (escapesAtari) score += ESCAPE_ORDER_BONUS
            scored.add(ScoredMove(index, score, captureCount, selfAtari))
        }

        applyTactics(scored, tacticAssist)

        if (scored.isEmpty()) return IntArray(0)
        scored.sortByDescending { it.score }
        return IntArray(scored.size) { scored[it].index }
    }

    /**
     * 根节点战术层 —— 低难度档的「基本棋理补丁」。
     *
     * 纯随机走子的 MCTS 在小棋盘上看不见提子（详见 [Difficulty.tacticAssist] 注释），
     * 所以这里用规则化的两条战术兜底。**只在根节点做，只扫一次**，不影响 playout 速度。
     *
     * @param scored 会被原地修改
     * @param tacticAssist 2=能提就提（强制）、1=强介入、0=不干预
     */
    private fun applyTactics(scored: MutableList<ScoredMove>, tacticAssist: Int) {
        if (tacticAssist <= 0) return

        // 能提就提：只要存在能提子的着法，就只在能提子的着法里选
        if (tacticAssist >= 2 && scored.any { it.captureCount > 0 }) {
            scored.removeAll { it.captureCount == 0 }
        }

        // 排除自杀式落子（落下去立刻只剩一口气）。全部如此时保留，否则一手都下不出来。
        if (tacticAssist >= 1) {
            val keep = scored.filter { !it.selfAtari }
            if (keep.isNotEmpty()) {
                scored.clear()
                scored.addAll(keep)
            }
        }
    }


    private fun anyMoveGainsOver(
        board: Board,
        color: Stone,
        candidates: IntArray,
        threshold: Int,
    ): Boolean {
        val isBlack = color == Stone.BLACK
        val scored0 = scorer.score(board.cells, komi)
        val before = if (isBlack) scored0.blackTotal else scored0.whiteTotal

        for (c in candidates) {
            simBoard.copyFrom(board)
            if (simBoard.play(c % size, c / size, color) !is PlayOutcome.Ok) continue
            val scored = scorer.score(simBoard.cells, komi)
            val after = if (isBlack) scored.blackTotal else scored.whiteTotal
            if (after > before + threshold) return true
        }
        return false
    }

    /** 该点是第几线。1 = 棋盘最外圈。 */
    private fun lineOf(index: Int): Int {
        val col = index % size
        val row = index / size
        return minOf(col, row, size - 1 - col, size - 1 - row) + 1
    }

    /** [index] 的正交邻居里，是否存在「自己这一色、且只剩一口气」的棋块。 */
    private fun hasAdjacentGroupInAtari(board: Board, index: Int, myCode: Byte): Boolean {
        val n = BoardGeometry.neighbors(size, index, neighborBuf)
        for (k in 0 until n) {
            val nb = neighborBuf[k]
            if (board.cells[nb] != myCode) continue
            if (libertyFinder.findLiberties(board.cells, nb) == 1) return true
        }
        return false
    }

    private class ScoredMove(
        val index: Int,
        val score: Int,
        val captureCount: Int,
        val selfAtari: Boolean,
    )

    /** [index] 周围 [radius] 格内是否有任何棋子。 */
    private fun isNearAnyStone(board: Board, index: Int, radius: Int): Boolean {
        val centerX = index % size
        val centerY = index / size
        val x0 = maxOf(0, centerX - radius)
        val x1 = minOf(size - 1, centerX + radius)
        val y0 = maxOf(0, centerY - radius)
        val y1 = minOf(size - 1, centerY + radius)
        for (y in y0..y1) {
            for (x in x0..x1) {
                if (board.cells[y * size + x].toInt() != 0) return true
            }
        }
        return false
    }

    // ===============================================================
    // 树的三个操作
    // ===============================================================

    /**
     * 展开一个子节点。
     *
     * - 若 [node] 还持有未试过的候选（根节点，以及由根候选直接产生的第一层），
     *   就按随机次序取出一个。
     * - 否则用走子策略现场提一个着法（深层节点），并去重。
     */
    private fun expand(node: Node, rng: Random): Node? {
        if (node.exhausted) return null

        var move = PASS_MOVE
        if (node.hasUntried) {
            move = node.pickUntried()
        } else if (node.children.size < maxChildrenPerNode) {
            move = policy.selectMove(simBoard, node.toMove, rng)
            if (move < 0) {
                node.exhausted = true
                return null
            }
            if (node.hasChild(move)) {
                // 策略给出了一个已有的着法：这一轮不再展开
                return null
            }
        } else {
            return null
        }

        if (!applyMove(simBoard, move, node.toMove)) {
            // 候选在深层局面下已不合法（局面已变），跳过本轮展开
            return null
        }

        val child = Node(move = move, parent = node, toMove = node.toMove.opponent)
        node.children.add(child)
        return child
    }

    /**
     * UCB1 选择。每个节点的 wins 都是「该节点走棋方视角」，因此各层统一取最大值。
     *
     * `UCB = 胜率 + C × √(ln(父访问数) / 本节点访问数)`
     */
    private fun selectChild(node: Node): Node {
        val children = node.children
        val logParentVisits = ln(node.visits.toDouble().coerceAtLeast(1.0))

        var best = children[0]
        var bestScore = Double.NEGATIVE_INFINITY
        for (child in children) {
            val score = if (child.visits == 0) {
                // 没访问过的着法要优先试一次（否则它的胜率无从谈起）
                Double.POSITIVE_INFINITY
            } else {
                child.wins / child.visits +
                    EXPLORATION_CONSTANT * sqrt(logParentVisits / child.visits)
            }
            if (score > bestScore) {
                bestScore = score
                best = child
            }
        }
        return best
    }

    /**
     * 随机走子到终局，返回胜方。
     *
     * 连续两次 pass，或达到手数上限，即进入数子评估。
     */
    private fun simulate(board: Board, firstToMove: Stone, rng: Random): Stone {
        var toMove = firstToMove
        var consecutivePasses = 0
        var moves = 0
        var blackCaptured = 0
        var whiteCaptured = 0

        while (moves < maxPlayoutMoves && consecutivePasses < 2) {
            val move = policy.selectMove(board, toMove, rng)
            if (move < 0) {
                board.pass()
                consecutivePasses++
            } else {
                val outcome = board.play(move % size, move / size, toMove)
                if (outcome is PlayOutcome.Ok) {
                    if (toMove == Stone.BLACK) blackCaptured += outcome.captureCount
                    else whiteCaptured += outcome.captureCount
                    consecutivePasses = 0
                } else {
                    // 策略给出了非法着法（理论上不该发生），按停一手处理，避免死循环
                    board.pass()
                    consecutivePasses++
                }
            }
            toMove = toMove.opponent
            moves++
        }

        return judge(board, blackCaptured, whiteCaptured)
    }

    /**
     * 终局判定。**必须在数子上显式加上提子数。**
     *
     * 原因：随机走子走到最后，被提掉的那一点通常很快会被重新填上，地盘的最终
     * 归属几乎不变。于是纯数子评估下「提掉对方一个子」在搜索里等于什么都没发生 ——
     * 引擎根本看不见提子，也不会去打吃/逃子。
     *
     * 实测（AiDiagnosticTest）：修复前，面对「白子只剩一口气」的局面，
     * 初级档 5 个随机种子全部不去提，而是跑到别处下。修复后稳定提子。
     *
     * 系数 [CAPTURE_BONUS] 取 2：中国规则下提一子让对方少一子、自己多一目，
     * 恰好是 2 目差。
     */
    private fun judge(board: Board, blackCaptured: Int, whiteCaptured: Int): Stone {
        val margin = scorer.score(board.cells, komi).blackMargin +
            (blackCaptured - whiteCaptured) * CAPTURE_BONUS
        return when {
            margin > komi * 2 -> Stone.BLACK
            margin < komi * 2 -> Stone.WHITE
            else -> Stone.EMPTY
        }
    }

    // ===============================================================
    // 最终着法选择 —— 难度弱化在这里生效
    // ===============================================================

    /**
     * 从搜索结果的访问分布中挑出最终着法。
     *
     * 次序上先应用 [Difficulty.blunderRate]（概率性挑次优，模拟「看漏了」），
     * 再应用 [Difficulty.temperature]（按访问分布采样，温度越高越随机）。
     * 大师档两者都为 0，等价于取访问次数最多的着法 —— 标准 MCTS 行为。
     */
    private fun selectFinalMove(root: Node, difficulty: Difficulty, rng: Random): Int {
        val children = root.children
        if (children.isEmpty()) return PASS_MOVE

        val ranked = children.sortedByDescending { it.visits }
        val best = ranked[0]

        // 先砍出一个「有竞争力」的池子：访问量至少是头名的 1/4。
        //
        // 这一步不能省。否则温度采样会把只被访问过一两次的着法也选上 ——
        // 实测（AllDifficultyLevelsTest）：9 路空盘、初级档，**第一手落在了一线**，
        // 就因为那个点恰好被随机抽中过一次。放水可以，下出垃圾不行。
        val floor = maxOf(1, best.visits / 4)
        val pool = ranked.filter { it.visits >= floor }.ifEmpty { listOf(best) }

        // ---- 失误率：偶尔挑一个「还不错但非最优」的着法 ----
        if (difficulty.blunderRate > 0f && pool.size > 1 &&
            rng.nextFloat() < difficulty.blunderRate
        ) {
            val others = pool.filter { it !== best }
            if (others.isNotEmpty()) {
                return others[rng.nextInt(others.size)].move
            }
        }

        // ---- 温度采样（只在池子里）----
        if (difficulty.temperature <= 0f) return best.move

        val exponent = 1.0 / difficulty.temperature.toDouble()
        var total = 0.0
        val weights = DoubleArray(pool.size)
        for (i in pool.indices) {
            val v = pool[i].visits.toDouble()
            weights[i] = if (v <= 0.0) 0.0 else v.pow(exponent)
            total += weights[i]
        }
        if (total <= 0.0) return best.move

        var pick = rng.nextDouble() * total
        for (i in pool.indices) {
            pick -= weights[i]
            if (pick <= 0.0) return pool[i].move
        }
        return best.move
    }

    // ===============================================================

    private fun applyMove(board: Board, move: Int, color: Stone): Boolean {
        if (move < 0) return false
        return board.play(move % size, move / size, color) is PlayOutcome.Ok
    }

    /** MCTS 搜索树的节点。 */
    private class Node(
        /** 到达本节点的着法（扁平索引）。-1 用于根节点。 */
        val move: Int,
        val parent: Node?,
        /** 本节点局面下轮到走棋的一方 */
        val toMove: Stone,
    ) {
        var visits: Int = 0
        var wins: Double = 0.0

        val children: MutableList<Node> = ArrayList(8)

        /** 仅根节点持有：完整扫描得到的候选着法，按启发式降序 */
        var candidates: IntArray? = null
        private var candidateCursor: Int = 0

        /** 已确认无着法可下 */
        var exhausted: Boolean = false

        val hasUntried: Boolean
            get() = candidates?.let { candidateCursor < it.size } ?: false

        /**
         * 随机取出一个未试过的候选着法。
         *
         * 用「与游标位置随机交换」来实现，避免 `removeAt(0)` 的数组搬移开销
         * —— 大盘根节点可能有上百个候选，反复搬移会明显拖慢搜索。
         */
        fun pickUntried(): Int {
            val array = candidates!!
            val i = candidateCursor
            candidateCursor++
            return array[i]
        }

        fun hasChild(move: Int): Boolean {
            for (child in children) if (child.move == move) return true
            return false
        }
    }

    companion object {
        /** 表示停一手（pass）的着法值 */
        const val PASS_MOVE = -1

        /** 网络路径下额外掺入的启发式候选个数（兜底，见 findBestMoveWithNet 注释） */
        private const val NET_HEURISTIC_TAIL = 4

        /**
         * 第二层前瞻里，给「对方应手」保留的候选个数。
         *
         * **取 2，不是越多越好** —— 这是实测出来的（同一批 8 个局面、同一网络）：
         *
         *   | 应手数 | 聚合 | 平均丢目 | 最差单手 |
         *   |---|---|---|---|
         *   | 0（只看一层） | — | 6.60 | 20.84 |
         *   | **2** | 取最差 | **4.65** | **14.13** |
         *   | 6 | 取最差 | 4.88 | 17.16 |
         *   | 4 | 取平均 | 5.72 | 25.67 |
         *
         * 为什么不是越多越好：对 N 个**含噪**价值取最小值，最小值会系统性偏低 ——
         * N 越大偏得越狠，好手被误判成坏手（现象就是"漏掉要点 + 尾部爆炸"）。
         * 为什么不用平均：平均等价于假设对方乱下，会忽略对方最狠的一手（实测最差 25.67）。
         * 围棋里必须用最小值，但应手池要小到噪声可控。
         */
        private const val REPLY_CANDIDATES = 2

        /**
         * 把"目"换算成温度采样尺度的分母：权重 = exp((价值 − 最高价值) / (温度 × 本值))。
         *
         * 为什么需要它：难度里的 temperature 原本是按"MCTS 访问量分布"设计的，
         * 而网络路径的价值单位是**目**（动辄差十几目），直接 exp(-目差/温度) 会瞬间退化成
         * 取最优 —— 低难度档就永远不会变弱。按实测的目差量级取 4 目一档比较合适：
         * 温度 1.0 时，落后 4 目的候选权重约为最优的 1/e。
         */
        private const val VALUE_TEMP_SCALE = 4.0f

        /** UCB 探索常数，√2 是经典取值 */
        private const val EXPLORATION_CONSTANT = 1.41421356

        /** 每多少次 playout 检查一次协程取消。太频繁会有额外开销。 */
        private const val CANCEL_CHECK_INTERVAL = 64

        /** 终局判定里每提一子折算的目数（中国规则下提一子的真实价值是 2 目） */
        private const val CAPTURE_BONUS = 2

        /** 根候选里「能提子」的排序加权 */
        private const val CAPTURE_ORDER_BONUS = 60

        /** 根候选里「逃出打吃」的排序加权 */
        private const val ESCAPE_ORDER_BONUS = 30
    }
}

/**
 * app 设置里「AI 最长思考时间」的**默认值**（也是该选项的最小值），毫秒。
 *
 * 用途：给「候选池随预算加宽」定基准 —— 默认设置下加宽倍数为 1（各档行为与加宽前
 * 完全一致），只有用户主动调大才加宽。
 *
 * ⚠️ 必须与 app 的 `SettingsStore.AI_THINK_OPTIONS.first()` 一致。两处不一致时
 * 不会报错，只会表现成"某一档忽然变慢/变弱"，极难排查 ——
 * 所以有回归用例 `思考预算基准与设置下限一致` 守着这一条。
 */
const val MIN_THINK_BUDGET_MS = 15_000L
