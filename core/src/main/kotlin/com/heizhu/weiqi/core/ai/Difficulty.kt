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
    ),
    MASTER(
        id = "master",
        displayName = "大师",
        description = "全力应战 · 9 路盘上不好惹",
        timeBudgetMs = 3_000,
        maxPlayouts = 20_000,
        temperature = 0.0f,
        blunderRate = 0.0f,
        localRadius = 0,
    );

    /** 是否启用「只在棋子附近落子」的视野限制 */
    val hasLocalRestriction: Boolean get() = localRadius > 0

    companion object {
        /** 从存档的 id 还原难度。找不到时回退到 [BEGINNER]，避免存档损坏导致崩溃。 */
        fun fromId(id: String): Difficulty =
            entries.firstOrNull { it.id == id } ?: BEGINNER
    }
}
