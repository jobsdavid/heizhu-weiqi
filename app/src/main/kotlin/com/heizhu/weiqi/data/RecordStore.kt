package com.heizhu.weiqi.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 战绩存档 —— 存成一个 JSON 文件。
 *
 * ## 为什么不用 Room
 * 单用户、单表、预估几百到几千条记录。为这个规模引入 KSP 注解处理器，
 * 会让构建复杂度和首次编译时间显著上升，属于过度设计。
 * 代价是没有 SQL 查询能力，统计在内存里算 —— 几千条记录耗时 < 10ms。
 *
 * **触发迁移的阈值**：单设备记录长期超过 5 万条，或需要跨维度复杂查询时，再迁 Room。
 *
 * ## 一致性
 * 所有读写都走 [mutex]，且内存缓存与磁盘始终同步更新。
 * 存档损坏时**不会让应用起不来** —— 坏文件会被改名保留，然后从空存档继续。
 */
class RecordStore(private val file: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val serializer = ListSerializer(GameRecord.serializer())
    private val mutex = Mutex()

    /** 内存缓存。避免每次进战绩页都读盘。 */
    private var cache: List<GameRecord>? = null

    suspend fun loadAll(): List<GameRecord> = mutex.withLock {
        cache ?: readFromDisk().also { cache = it }
    }

    /** 追加一条记录，返回更新后的完整列表。 */
    suspend fun append(record: GameRecord): List<GameRecord> = mutex.withLock {
        val updated = (cache ?: readFromDisk()) + record
        writeToDisk(updated)
        cache = updated
        updated
    }

    /** 清空全部记录（战绩页的「清空记录」，调用前需二次确认）。 */
    suspend fun clear(): Unit = mutex.withLock {
        writeToDisk(emptyList())
        cache = emptyList()
    }

    private suspend fun readFromDisk(): List<GameRecord> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            json.decodeFromString(serializer, file.readText())
        } catch (_: Exception) {
            // 存档损坏不能让应用打不开。保留坏文件供排查，然后从空开始。
            runCatching { file.renameTo(File(file.parentFile, file.name + ".corrupt")) }
            emptyList()
        }
    }

    private suspend fun writeToDisk(records: List<GameRecord>) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(serializer, records))
    }
}

// ================================================================
// 统计
// ================================================================

/** 一组对局的胜负统计。 */
data class WinStats(val total: Int, val wins: Int, val losses: Int, val draws: Int) {
    val winRate: Float get() = if (total == 0) 0f else wins.toFloat() / total
    val hasData: Boolean get() = total > 0

    companion object {
        val EMPTY = WinStats(0, 0, 0, 0)
    }
}

/** 按维度分组后的统计，带一个用于显示的标签。 */
data class GroupedStats(val label: String, val stats: WinStats)

/** 战绩统计计算。纯函数，便于测试。 */
object StatsCalculator {

    fun overall(records: List<GameRecord>): WinStats = tally(records)

    private fun tally(records: List<GameRecord>): WinStats = WinStats(
        total = records.size,
        wins = records.count { it.playerWon > 0 },
        losses = records.count { it.playerWon < 0 },
        draws = records.count { it.playerWon == 0 },
    )

    /** 按难度分组，顺序按难度由低到高。 */
    fun byDifficulty(
        records: List<GameRecord>,
        orderedIds: List<String>,
        labelOf: (String) -> String,
    ): List<GroupedStats> = orderedIds.mapNotNull { id ->
        val group = records.filter { it.difficultyId == id }
        if (group.isEmpty()) null else GroupedStats(labelOf(id), tally(group))
    }

    /** 按棋盘尺寸分组，从小到大。 */
    fun byBoardSize(records: List<GameRecord>, sizes: List<Int>): List<GroupedStats> =
        sizes.mapNotNull { size ->
            val group = records.filter { it.boardSize == size }
            if (group.isEmpty()) null else GroupedStats("${size} 路", tally(group))
        }

    /**
     * 当前连胜数：正数表示连胜，负数表示连败。
     *
     * 倒着扫到结果发生变化为止。和棋视为中断（返回 0）。
     */
    fun currentStreak(records: List<GameRecord>): Int {
        if (records.isEmpty()) return 0
        val latest = records.last().playerWon
        if (latest == 0) return 0
        var streak = 0
        for (i in records.indices.reversed()) {
            if (records[i].playerWon != latest) break
            streak++
        }
        return streak * latest
    }

    /** 历史最长连胜。 */
    fun longestWinStreak(records: List<GameRecord>): Int {
        var best = 0
        var run = 0
        for (record in records) {
            if (record.playerWon > 0) {
                run++
                if (run > best) best = run
            } else {
                run = 0
            }
        }
        return best
    }

    /** 最近 N 局的结果，按时间从早到晚，用于画趋势点。 */
    fun recentResults(records: List<GameRecord>, count: Int): List<Int> =
        records.takeLast(count).map { it.playerWon }
}
