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
 * | [temperature] | 从访问分布采样而非取最优。高 → 选择更随机 |
 * | [blunderRate] | 概率性挑次优着法，模拟「看漏了」 |
 * | [localRadius] | **只在已有棋子 N 格范围内落子**，模拟初学者的视野局限 |
 * | [tacticAssist] | 根节点战术辅助档位，见下 |
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
) {
    ENTRY(
        id = "entry",
        displayName = "入门",
        description = "会犯错的对手 · 刚学会规则就能赢",
        timeBudgetMs = 300,
        maxPlayouts = 300,
        temperature = 1.2f,
        blunderRate = 0.25f,
        localRadius = 1,
        tacticAssist = 2,
    ),
    BEGINNER(
        id = "beginner",
        displayName = "初级",
        description = "会吃子、会逃跑 · 贪吃但会露破绽",
        timeBudgetMs = 600,
        maxPlayouts = 1_200,
        temperature = 0.7f,
        blunderRate = 0.10f,
        localRadius = 2,
        tacticAssist = 2,
    ),
    INTERMEDIATE(
        id = "intermediate",
        displayName = "中级",
        description = "有基本战术 · 需要认真下才能赢",
        timeBudgetMs = 1_200,
        maxPlayouts = 3_000,
        temperature = 0.35f,
        blunderRate = 0.03f,
        localRadius = 0,
        tacticAssist = 2,
    ),
    ADVANCED(
        id = "advanced",
        displayName = "高级",
        description = "有全局观 · 会经营实地和外势",
        timeBudgetMs = 2_000,
        maxPlayouts = 8_000,
        temperature = 0.15f,
        blunderRate = 0.0f,
        localRadius = 0,
        tacticAssist = 2,
    ),
    MASTER(
        id = "master",
        displayName = "大师",
        // ⚠️ 这里**不要写死棋盘尺寸**：这条说明会和任意尺寸一起显示。
        // 之前写的是「全力应战 · 9 路盘上不好惹」，结果在 13 路/19 路设置页上
        // 明晃晃地说着 9 路 —— 真机上被黑猪大人当场抓到。
        // 需要区分尺寸的文案走 [descriptionFor]。
        description = "全力应战 · 不给机会",
        timeBudgetMs = 3_000,
        maxPlayouts = 20_000,
        temperature = 0.0f,
        blunderRate = 0.0f,
        localRadius = 0,
        tacticAssist = 2,
    );

    /** 是否启用「只在棋子附近落子」的视野限制 */
    val hasLocalRestriction: Boolean get() = localRadius > 0

    /**
     * 设置页显示的说明文案。**需要区分棋盘尺寸时必须用这个**，
     * 不要用裸的 [description]（它是固定文案，写死尺寸就会撒谎）。
     *
     * 大师档在 9 路上确实凶；19 路上它算不过来 —— 真机实测 19 路 3 秒只能跑约
     * 1100 次模拟，而候选点从 80 涨到 360。这话得跟着尺寸说才对。
     */
    fun descriptionFor(boardSize: Int): String = when {
        this != MASTER -> description
        boardSize <= 9 -> "全力应战 · 9 路盘上不好惹"
        else -> "全力应战 · ${boardSize} 路盘上它算不太深，但仍会咬人"
    }

    companion object {
        /** 从存档的 id 还原难度。找不到时回退到 [BEGINNER]，避免存档损坏导致崩溃。 */
        fun fromId(id: String): Difficulty =
            entries.firstOrNull { it.id == id } ?: BEGINNER
    }
}
