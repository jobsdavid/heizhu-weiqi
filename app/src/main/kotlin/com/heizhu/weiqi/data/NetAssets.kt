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
    private val cache = ConcurrentHashMap<String, PolicyValueNet?>()

    /** assets 里各尺寸的权重前缀。没有条目 = 该尺寸没有网络。 */
    private fun baseName(boardSize: Int): String? = when (boardSize) {
        9 -> "net/pv9"
        else -> null
    }

    /**
     * 取该尺寸 + 该难度档的网络；没有可用网络时返回 null（调用方据此回退旧路径）。
     *
     * @param difficultyId 难度档 id（如 `master`）。先找 `net/pv9-<id>`，找不到退通用权重。
     */
    fun load(context: Context, boardSize: Int, difficultyId: String): PolicyValueNet? {
        val key = "$boardSize:$difficultyId"
        if (cache.containsKey(key)) return cache[key]
        val base = baseName(boardSize)
        val net = if (base == null) {
            null
        } else {
            loadOne(context, "$base-$difficultyId") ?: loadOne(context, base)
        }
        cache[key] = net
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
