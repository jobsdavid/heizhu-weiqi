package com.heizhu.weiqi.core.ai

/**
 * 五档难度。
 *
 * ## 设计理念：从**搜索根源**上弱化，而不是让强 AI 随机放水
 *
 * 让孩子感觉到「它在故意让我」是很糟的体验 —— 成就感消失，而且放水时下出的
 * 怪棋形会教坏孩子。所以每一档的弱化都体现在引擎参数上，AI 始终在下
 * **自己认为最合理**的棋，只是「它认为合理」的水平不同：
 *
 * | 参数 | 作用 |
 * |---|---|
 * | [timeBudgetMs] / [maxPlayouts] | 搜索量。少 → 看不住后续变化 |
 * | [temperature] | ⚠️ **已弃用（全档恒 0）**：原为"按价值采样"，那是**放水**（明知有更好的却按概率挑差的） |
 * | [blunderRate] | ⚠️ **已弃用（全档恒 0）**：原为"概率性挑次优着法"，同样是放水 |
 * | [localRadius] | **只在已有棋子 N 格范围内落子**，模拟初学者的视野局限 |
 * | [tacticAssist] | 根节点战术辅助档位，见下 |
 *
 * ## 档位靠什么拉开（2026-10-05 定稿）
 *
 * **绝不放水**：每一档都下"自己认为最好"的那一手，[temperature] 与 [blunderRate] 恒为 0。
 * 差距只来自两个"不放水"的轴：
 *   1. **棋感**（网络质量 —— 数据量 / 容量）：弱网看错局面，但它仍按自己认为最好的下；
 *   2. **想得深浅**（[netPlies] 前瞻层数、[netTopK] 候选数）：少算几步。
 * 这两条都必须在档位间**单调**（低档两项都不得高于高档），否则弱档会反超。
 *
 * ## [tacticAssist] 为什么必须存在
 *
 * 纯随机走子的 MCTS 在小棋盘上**看不见提子**：被提掉的那一点很快会被重新填上，
 * 地盘几乎不变，于是「现在提」和「以后提」在评估上没有区别。
 * 实测（AiDiagnosticTest）：面对「白子只剩一口气」的局面，不加战术层时
 * 初级档 5 个随机种子全部不去提子 —— 孩子看到的就是「这电脑是智障吗」。
 *
 * 实测（AllDifficultyLevelsTest）：中级及以上不加这条时，面对「白子只剩一口气」
 * 的局面**三档全部不提**，孩子看到的就是「这电脑是智障吗」。
 *
 * 注意：「能提就提」是**规则层面的常识**（中国规则下提一子就是 2 目差），
 * 不是难度旋钮 —— 所以五档全都开。难度的差异仍然来自搜索量、随机性与失误率，
 * 而不是「强档反而不会提子」这种荒唐事。
 *
 * - `2` 强制：存在能提子的着法时，**只在能提子的着法里选**（能提就提）+ 排除自杀式落子
 * - `1` 强介入：只排除自杀式落子，不做提子限制
 * - `0` 不干预：完全交给搜索
 *
 * [localRadius] 是让低难度对手「可以理解、可以预判」的关键：它不会突然跑到
 * 棋盘另一头去布局，行为模式接近一个刚学棋的人。
 */
enum class Difficulty(
    val id: String,
    val displayName: String,
    /** 设置页显示的一行说明，用孩子/家长都能读懂的话 */
    val description: String,
    val timeBudgetMs: Long,
    val maxPlayouts: Int,
    val temperature: Float,
    val blunderRate: Float,
    val localRadius: Int,
    /** 根节点战术辅助档位：2=强制能提就提，1=强介入，0=不干预。见类注释。 */
    val tacticAssist: Int,
    /**
     * 走「网络引导」路径时，根候选保留网络策略的前多少个点。
     *
     * 这是网络路径下**档位差异的主要旋钮**：候选越少越弱。
     * 原来的随机 rollout 路径里"模拟次数"是旋钮，但实测它几乎不影响着法质量
     * （300 次 vs 8000 次，丢目差 t=1.24，噪声内），所以档位必须换一个真正起作用的旋钮。
     */
    val netTopK: Int,
    /**
     * 网络路径的前瞻层数：1 = 只看"我这手之后对方视角的价值"；2 = 再算一步对方的最佳应手。
     *
     * 这是档位差异里**最可靠**的旋钮：实测同一批局面，一层前瞻平均丢 6.60 目、
     * 两层 4.65 目 —— 差距确定且量级够大。相比之下"netTopK 候选数"并不单调
     * （候选池小、噪声也小，反而躲过了价值网络的误差），所以低难度档改用层数+温度。
     */
    val netPlies: Int,
    /**
     * **让子数（handicap）—— 难度阶梯的最终旋钮**（2026-10-05 决策）。
     *
     * 为什么最终落到让子上：另外三条轴都实测失败 ——
     *   · 网络容量（16×1/32×3/64×5）：棋力非单调
     *   · 训练数据量（300/800/3000 条）：反单调
     *   · 搜索量（netTopK / 前瞻层数）：非单调
     *   · 训练步数做梯度：因语料量不足（24 局），验证集只有 1 局 ⇒ 噪声盖过信号，测不出
     * 档位差始终小于测量噪声 ⇒ 只能换一条**确定性**的路径。
     *
     * 让子是围棋界调难度的标准做法（KataGo / Leela 同样用它），
     * **不等于放水**：AI 每一手仍按自己最强的判断落子，只是起始局面不同。
     *
     * 语义：让 N 子 = 在星位摆 N 颗**玩家颜色的子**，由 AI 先下；贴目保持标准值不动
（实测：让 komi 归零会在 9 路小盘上反过来补偿 AI，见 GameState.komi 的注释）。
     */
    val handicap: Int = 0,
    // 标定依据（2026-10-05 实测：同网同预算、每档 40 局、两侧同网，唯一变量是让子数）
    //   logit(受让方胜率) = +0.152 + 0.2118 × n  ⇒ 每颗星位子 ≈ +5.3 个百分点
    //   实测点：n=0→0.525、1→0.600、2→0.625、3→0.650、4→0.750（单调）
    //   反解：单子价值 ≈ 2.3 目，结局目差 σ ≈ 22 目（9 路共 81 点）
    // 五档取 5 / 3 / 2 / 1 / 0，让相邻档的胜率差落在 4~8 个百分点，
    // 强档留细一级（1 子）方便再调。
) {
    ENTRY(
        id = "entry",
        displayName = "入门",
        description = "会犯错的对手 · 刚学会规则就能赢",
        timeBudgetMs = 300,
        maxPlayouts = 300,
        temperature = 0f,     // 去放水：不再按价值随机采样（原 1.2）
        blunderRate = 0f,     // 去放水：不再故意挑次优着法（原 0.25，每 4 手送一手）
        localRadius = 0,
        tacticAssist = 2,
        netTopK = 6,
        netPlies = 1,     // 入门：**想得最浅** —— 只看"我这手之后对方视角的价值"，不推对方应手
        handicap = 5,   // 让 5 子：起步就比玩家少 3 手，是最确定的强度差
    ),
    BEGINNER(
        id = "beginner",
        displayName = "初级",
        description = "会吃子、会逃跑 · 贪吃但会露破绽",
        timeBudgetMs = 600,
        maxPlayouts = 1_200,
        temperature = 0f,     // 去放水（原 0.7）
        blunderRate = 0f,     // 去放水（原 0.10）
        localRadius = 0,
        tacticAssist = 2,
        netTopK = 8,
        netPlies = 1,     // 初级：同样只想一层，与入门的差距靠**更小的弱网**（数据量/容量）拉开
        handicap = 3,   // 让 3 子：起步就比玩家少 2 手，是最确定的强度差
    ),
    INTERMEDIATE(
        id = "intermediate",
        displayName = "中级",
        description = "有基本战术 · 需要认真下才能赢",
        timeBudgetMs = 1_200,
        maxPlayouts = 3_000,
        temperature = 0f,     // 去放水（原 0.35）
        blunderRate = 0f,     // 去放水（原 0.03）
        localRadius = 0,
        tacticAssist = 2,
        netTopK = 10,
        netPlies = 2,     // 中级起改用两层前瞻
        handicap = 2,   // 让 2 子：起步就比玩家少 1 手，是最确定的强度差
    ),
    ADVANCED(
        id = "advanced",
        displayName = "高级",
        description = "有全局观 · 会经营实地和外势",
        timeBudgetMs = 2_000,
        maxPlayouts = 8_000,
        temperature = 0f,     // 去放水（原 0.15）
        blunderRate = 0.0f,
        localRadius = 0,
        tacticAssist = 2,
        netTopK = 16,
        netPlies = 2,
        handicap = 1,     // 让 1 子：与大师只差这一颗，是五档里最细的一级
    ),
    MASTER(
        id = "master",
        displayName = "大师",
        // ⚠️ 这里**不要写死棋盘尺寸**：这条说明会和任意尺寸一起显示。
        // 之前写的是「全力应战 · 9 路盘上不好惹」，结果在 13 路设置页上
        // 明晃晃地说着 9 路 —— 真机核对时发现。
        // 需要区分尺寸的文案走 [descriptionFor]。
        description = "全力应战 · 不给机会",
        // 上限 15 秒：思考久一点不影响体验，15 秒是可接受的等待上限。
        //
        // 这条预算的意义**取决于走哪条路径**，别混：
        //   · 随机 rollout 路径（无网络）：多算等于白等。实测 300 次 vs 8000 次模拟，
        //     平均丢目 7.32 → 8.89（t=1.24，噪声内）—— 评估函数太噪，多搜只是
        //     在同一个错误判断上反复强化。
        //   · 网络引导路径（有网络）：每一次前向都是一次真实的局面判断，算得越多越准。
        //     端侧实测约 12~13 ms/次（32 通道×2 块），15 秒可跑 1000 次以上，
        //     足以把网络放大数倍或做两层前瞻。
        // 所以预算是给网络路径留的余量，这也是这条路线成立的前提之一。
        timeBudgetMs = 15_000,
        maxPlayouts = 20_000,
        temperature = 0.0f,
        blunderRate = 0.0f,
        localRadius = 0,
        tacticAssist = 2,
        netTopK = 24,     // 候选最全 → 最强
        netPlies = 2,
        handicap = 0,     // 分先：不让子，靠搜索与网络全力下
    );

    /** 是否启用「只在棋子附近落子」的视野限制 */
    val hasLocalRestriction: Boolean get() = localRadius > 0

    /**
     * 设置页显示的说明文案。**需要区分棋盘尺寸时必须用这个**，
     * 不要用裸的 [description]（它是固定文案，写死尺寸就会撒谎）。
     *
     * 大师档在 9 路上确实凶；13 路上它算得浅一些（棋盘大了，同样的时间能算的点更少）。
     * 这话得跟着尺寸说才对。
     */
    fun descriptionFor(boardSize: Int): String {
        // 让子是难度阶梯的主要旋钮，每一档都必须把它说清楚。
        // ⚠️ 只在这一处拼：文案与让子数若是两个源，迟早会不一致 ——
        //    曾经踩过：改了 description，而大师档走的是下面这条尺寸分支，
        //    于是界面上完全看不到改动（真机截图才发现）。
        val handicapText = if (handicap > 0) "让你 $handicap 子 · " else "不让子 · "
        val styleText = when {
            this != MASTER -> description
            boardSize <= 9 -> "全力应战 · 9 路盘上不好惹"
            else -> "全力应战 · ${boardSize} 路盘上它算不太深，但仍会咬人"
        }
        return handicapText + styleText
    }

    companion object {
        /** 从存档的 id 还原难度。找不到时回退到 [BEGINNER]，避免存档损坏导致崩溃。 */
        fun fromId(id: String): Difficulty =
            entries.firstOrNull { it.id == id } ?: BEGINNER
    }
}
