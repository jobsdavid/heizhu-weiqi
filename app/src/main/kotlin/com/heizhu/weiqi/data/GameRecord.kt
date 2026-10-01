package com.heizhu.weiqi.data

import kotlinx.serialization.Serializable

/**
 * 一局对战的存档。
 *
 * ## 为什么棋谱用字符串而不是 List<Int>
 * 存档是 JSON 文件，一局 19 路可达 300 手。若每手存成一个 JSON 数字对象，
 * 光是括号和逗号就能让文件膨胀数倍。这里压成 `"12,45,-1,67"` 的形式
 * （扁平索引，-1 表示停一手），体积只有对象数组的几分之一。
 *
 * @param playerWon    1 = 玩家胜，-1 = 玩家负，0 = 和棋
 * @param blackMargin  黑方净胜子数（正数=黑多）
 * @param playerColorCode 1 = 玩家执黑，2 = 玩家执白
 */
@Serializable
data class GameRecord(
    val id: Long,
    val playedAt: Long,
    val boardSize: Int,
    val difficultyId: String,
    val playerColorCode: Int,
    val playerWon: Int,
    val blackTotal: Int,
    val whiteTotal: Int,
    val blackMargin: Int,
    val playerCaptures: Int,
    val aiCaptures: Int,
    val moveCount: Int,
    val undoCount: Int,
    val durationMs: Long,
    val endReason: String,
    /** 棋谱：逗号分隔的扁平索引，-1 表示停一手 */
    val moves: String,
) {

    /** 是否使用过悔棋 —— 战绩页据此标注「含悔棋」，让数据保持诚实 */
    val usedUndo: Boolean get() = undoCount > 0

    /** 是否认输结束 */
    val endedByResign: Boolean get() = endReason == "RESIGN"
}

/** 把棋谱索引串还原成数组。损坏的数据返回空数组，不抛异常打断界面。 */
fun decodeMoves(encoded: String): IntArray {
    if (encoded.isEmpty()) return IntArray(0)
    return try {
        encoded.split(',').map { it.trim().toInt() }.toIntArray()
    } catch (_: NumberFormatException) {
        IntArray(0)
    }
}

/** 把着法序列编码成紧凑字符串。 */
fun encodeMoves(indices: List<Int>): String =
    indices.joinToString(",")
