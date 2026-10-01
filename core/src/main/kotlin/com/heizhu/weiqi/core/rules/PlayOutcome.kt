package com.heizhu.weiqi.core.rules

/** 落子结果。 */
sealed interface PlayOutcome {

    /**
     * 落子成功。
     *
     * [captured] 是被提掉的点的扁平索引，可能为空数组。
     * 界面预览（`commit = false`）时同样会填充，用于显示「提 N 子」。
     */
    class Ok(
        val captured: IntArray,
        /**
         * 这手落下后，落子点所在棋块的气数。
         *
         * 棋盘层做自杀判定时**本来就要算这个数**，顺手回传给调用方不额外花时间。
         * 根节点用它判断「是不是自杀式落子」（=1 气且没提到子）。
         */
        val ownLiberties: Int,
    ) : PlayOutcome {
        val captureCount: Int get() = captured.size
    }

    /** 该点已有子 */
    data object Occupied : PlayOutcome

    /** 自杀：落子后自身无气，且没有提到任何对方子 */
    data object Suicide : PlayOutcome

    /** 打劫：此处不能立即提回（简单劫规则） */
    data object Ko : PlayOutcome

    /** 坐标越界 */
    data object OutOfBounds : PlayOutcome
}
