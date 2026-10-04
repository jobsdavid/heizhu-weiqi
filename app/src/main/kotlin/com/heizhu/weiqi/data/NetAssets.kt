package com.heizhu.weiqi.data

import android.content.Context
import android.util.Log
import com.heizhu.weiqi.core.ai.PolicyValueNet
import java.util.concurrent.ConcurrentHashMap

/**
 * 蒸馏小网络的加载（从 app 的 assets，纯离线推理，不申请任何权限）。
 *
 * 三条设计原则，都是为了让网络**不出现在失败路径上**：
 *  1. **缺失即回退**：没有对应尺寸/档位的权重（还没训练 / 只训了 9 路）→ 返回 null，
 *     引擎自动走原来的随机 rollout 路径，功能不受影响。
 *  2. **解析失败也不崩**：任何异常都吞掉并记日志。网络是"锦上添花"，
 *     不能让一个权重文件把整个 app 拖死在启动路径上。
 *  3. **按「尺寸 + 档位」缓存**：一局一次调用，缓存避免反复读 assets 与解包权重。
 *
 * ## 难度阶梯靠「网络强弱」实现，不靠搜索参数
 *
 * 实测：温度、候选数、前瞻层数这些旋钮，在网络已经把「每一手都教得不错」之后
 * **没有分辨力** —— 五档平均丢目差距只有 0.68 目，全在噪声里（28 样本标准误约
 * 1.5~2 目），入门档甚至偶尔比大师还"好"。而**不同容量/训练量的网络之间是量级差**：
 * 同一批局面实测 64×4 丢 8.80 目、64×6 丢 3.81 目，差一倍以上。
 * 所以阶梯用网络配给实现：入门配弱网，大师配最强网。
 *
 * 加载顺序：`net/pv9-<档位>.bin` → `net/pv9.bin`（通用兜底）→ null（回退随机 rollout）。
 * 少任何一个文件都只是回退，不影响 app 可用性。
 *
 * 权重文件是 `tools/katago/train_pv.py` 的导出物：
 *   assets/net/pv9-entry.bin  （平铺 float32，BatchNorm 已折进卷积）
 *   assets/net/pv9-entry.json （层名/形状/偏移 + 目差→胜率的斜率）
 */
object NetAssets {

    private const val TAG = "WeiqiNet"

    /**
     * 已加载的权重缓存。
     *
     * ⚠️ 值类型**不能是 nullable** —— 虽然写成 `PolicyValueNet?` 编译器不拦，
     * 但底层 `java.util.concurrent.ConcurrentHashMap` 在**运行时**拒绝 null 值（抛 NPE）。
     * "没有网络"这种情况用 [absent] 单独记（见 [load] 的注释：
     * 这个坑曾让"选 13 路开局"直接崩掉应用）。
     */
    private val cache = ConcurrentHashMap<String, PolicyValueNet>()

    /**
     * 已知**该尺寸/档位没有权重**的 key。
     *
     * 作用有两个：① 避免每次开局都去读一遍 assets 再失败；② 让"没有网络"这件事
     * 不经过 [cache]（见其注释）。
     */
    private val absent = ConcurrentHashMap.newKeySet<String>()

    /** assets 里各尺寸的权重前缀。没有条目 = 该尺寸没有网络。 */
    private fun baseName(boardSize: Int): String? = when (boardSize) {
        9 -> "net/pv9"
        13 -> "net/pv13"
        else -> null
    }

    /**
     * **首屏「棋盘大小」可选的尺寸 —— 单一来源。**
     *
     * 为什么放在这里而不是写死在界面里：界面只能列"真的有对应权重"的尺寸，
     * 否则孩子一选就掉进"没有网络"的回退路径（13 路曾经因此直接崩掉应用）。
     * 某个尺寸的权重练好、装进 `assets/net/` 之后，**只改这一行**即可开放它。
     *
     * 现状：**9 路 / 13 路**有全套五档权重。
     * 开放某个尺寸 = 装好权重 + 改这一行 + 过 [NetAssetsTest]：
     * 每个放行尺寸的每一档都要有配套权重与清单、能被生产代码加载、且五档互不相同。
     */
    val supportedSizes: List<Int> = listOf(9, 13)

    /**
     * 取该尺寸 + 该难度档的网络；没有可用网络时返回 null（调用方据此回退旧路径）。
     *
     * @param difficultyId 难度档 id（如 `master`）。先找 `net/pv9-<id>`，找不到退通用权重。
     *
     * ⚠️ **缺失不能用 null 记进 [cache]** —— `ConcurrentHashMap` 不允许 null 值，
     *    `cache[key] = null` 会抛 NPE。13 路那时还没有权重（只有 9 路的），
     *    于是"选 13 路开局"直接崩、应用退出（真机实测栈：
     *    `NetAssets.load(NetAssets.kt:58) → GameViewModel.startNewGame`）。
     *    缺失单独记在 [absent] 里，既避免重复读 assets，也不会踩这个坑。
     */
    fun load(context: Context, boardSize: Int, difficultyId: String): PolicyValueNet? =
        load(boardSize, difficultyId) { base -> loadOne(context, base) }

    /**
     * [load] 的实现，但**权重怎么来由调用方给** —— 这样"没有权重时必须回退而不是崩"
     * 这条路径可以在**纯 JVM** 上直接测（不需要 Context，也就不会踩 Robolectric
     * 在这台机器上起不来的问题，见 SKILL §13.4）。
     * 真机事故复现路径：`load(13, "master") { null }` —— 旧实现在这里把 null 写进
     * `ConcurrentHashMap` 直接 NPE（"选 13 路 → 应用退出"）。
     */
    internal fun load(
        boardSize: Int,
        difficultyId: String,
        loadOne: (String) -> PolicyValueNet?,
    ): PolicyValueNet? {
        val key = "$boardSize:$difficultyId"
        cache[key]?.let { return it }
        if (absent.contains(key)) return null
        val base = baseName(boardSize)
        val net = if (base == null) {
            null
        } else {
            loadOne("$base-$difficultyId") ?: loadOne(base)
        }
        if (net != null) cache[key] = net else absent.add(key)
        return net
    }

    /** 单个权重文件的加载。任何失败都返回 null（见类注释第 2 条）。 */
    private fun loadOne(context: Context, base: String): PolicyValueNet? = try {
        val bin = context.assets.open("$base.bin").readBytes()
        val manifest = context.assets.open("$base.json").bufferedReader().use { it.readText() }
        val loaded = PolicyValueNet.load(manifest, bin)
        Log.i(TAG, "已加载网络 $base（${bin.size / 1024} KB）")
        loaded
    } catch (e: Exception) {
        Log.i(TAG, "没有可用的网络（$base）：${e.javaClass.simpleName}，向上回退")
        null
    }
}
