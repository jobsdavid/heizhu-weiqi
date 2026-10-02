package com.heizhu.weiqi.data

import com.heizhu.weiqi.core.ai.Difficulty
import com.heizhu.weiqi.core.ai.PolicyValueNet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * 网络装配的闸门（纯 JVM，不依赖 Robolectric）。覆盖**所有放行的棋盘尺寸**。
 *
 * 难度阶梯**完全靠「每档一份不同强弱的权重」实现**（见 NetAssets 类注释）。
 * 这套装配有四种静默失效 —— 都不会报错、不会崩、编译与运行全绿，
 * 现象一律是「某一档忽然和另一档一样」，排查方向却完全不同：
 *
 *  1. **漏了某一档的资源** → 该档悄悄回退到通用权重或随机 rollout 路径；
 *  2. **两档指向同一份权重**（复制粘贴改漏文件名）→ 两档强弱完全相同，
 *     阶梯静默塌掉一节 —— 最坏的一种，用户看不出任何异常；
 *  3. **权重与 manifest 不配套**（换了 .bin 没换 .json）→ 加载抛异常被吞掉，同样只剩回退；
 *  4. **manifest 的层偏移与权重长度对不上**（导出脚本只改了一半）→ 同上。
 *
 * 尺寸清单**从 [NetAssets.supportedSizes] 取**，不在测试里另抄一份：
 * 界面上能选的尺寸 = 真的有全套权重、且这组用例过得去的尺寸。抄一份的结果就是
 * 「代码放行了 13 路，用例还在只查 9 路」——今天已经因为「同一事实写两份」栽过三次。
 *
 * 为什么不用 Robolectric 走 `Assets.open`：本机 JDK 上 Robolectric 与
 * raw FileDescriptor 不兼容（`Failed to interact with raw FileDescriptor internals`），
 * 会把这条闸门变成永远红的假闸门。直接读文件既绕开它，又能顺带校验长度一致性；
 * 而「能不能真的加载出来」直接调生产代码的 `PolicyValueNet.load`，
 * 与 app 里 `NetAssets` 用的是同一个入口 —— 比读文件更像真的。
 */
class NetAssetsTest {

    /** app 模块的 assets/net 目录。测试工作目录可能是模块根，也可能是仓库根，两种都找。 */
    private val netDir: File = run {
        val candidates = listOf("src/main/assets/net", "app/src/main/assets/net")
        candidates.map { File(it) }.firstOrNull { it.isDirectory }
            ?: error(
                "定位不到 assets/net —— 当前工作目录 ${File("").absolutePath}，" +
                    "找过：$candidates（工作目录变了就得更新这里，别让闸门变成永远绿）",
            )
    }

    private val sizes: List<Int> get() = NetAssets.supportedSizes

    private fun bin(size: Int, tier: String) = File(netDir, "pv$size-$tier.bin")
    private fun manifest(size: Int, tier: String) = File(netDir, "pv$size-$tier.json")

    private fun md5(f: File): String =
        MessageDigest.getInstance("MD5").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    /**
     * 层表覆盖到的字节数：最大 (offset + 层元素数) × 4。
     *
     * ⚠️ manifest 里的 `offset` 是**浮点元素**偏移，不是字节偏移
     * （`PolicyValueNet.load` 里就是拿它和 `bin.size / 4` 比的）。
     * 单位搞错不会报错，只会算出一个"对不上"的假数字，
     * 让人去查根本不存在的导出 bug —— 我在这里白查了一轮。
     */
    private fun coveredBytes(manifestJson: String): Long {
        val layers = Json.parseToJsonElement(manifestJson).jsonObject["layers"]!!.jsonArray
        var maxElems = 0L
        for (l in layers) {
            val o = l.jsonObject
            val elems = o["shape"]!!.jsonArray
                .map { it.jsonPrimitive.content.toLong() }.fold(1L) { a, b -> a * b }
            val end = o["offset"]!!.jsonPrimitive.content.toLong() + elems
            if (end > maxElems) maxElems = end
        }
        return maxElems * 4
    }

    @Test
    fun `每个放行尺寸的每一档都能经生产代码加载出网络`() {
        assertTrue("放行的尺寸不许为空", sizes.isNotEmpty())
        for (s in sizes) {
            for (d in Difficulty.entries) {
                val net = PolicyValueNet.load(manifest(s, d.id).readText(), bin(s, d.id).readBytes())
                assertTrue("${s} 路 · 档位 ${d.id} 加载出的网络尺寸不对", net.size == s)
            }
        }
    }

    @Test
    fun `权重与清单配套且长度自洽`() {
        for (s in sizes) {
            for (d in Difficulty.entries) {
                val b = bin(s, d.id)
                val m = manifest(s, d.id)
                assertTrue("${s} 路 · 档位 ${d.id} 缺权重：${b.path}", b.isFile)
                assertTrue("${s} 路 · 档位 ${d.id} 缺清单：${m.path}", m.isFile)

                val json = m.readText()
                val declared = Json.parseToJsonElement(json).jsonObject["bytes"]!!.jsonPrimitive.content.toLong()
                assertEquals(
                    "${m.name} 声明的 bytes 与实际权重长度不符 —— 装了错的 .json",
                    b.length(),
                    declared,
                )
                assertEquals(
                    "${m.name} 的层偏移加起来不等于权重长度 —— 导出脚本只改了一半",
                    b.length(),
                    coveredBytes(json),
                )
            }
        }
    }

    @Test
    fun `同一尺寸内五档用的确实是五份不同的权重`() {
        for (s in sizes) {
            val digests = Difficulty.entries.map { it.id to md5(bin(s, it.id)) }
            println("  ${s} 路权重指纹： " + digests.joinToString("  ") { "${it.first}=${it.second.take(8)}" })
            for (i in digests.indices) {
                for (j in i + 1 until digests.size) {
                    assertNotEquals(
                        "${s} 路：「${digests[i].first}」和「${digests[j].first}」装配了同一份权重 —— " +
                            "两档强弱会完全相同，阶梯静默塌掉一节，而界面上看不出任何异常",
                        digests[i].second,
                        digests[j].second,
                    )
                }
            }
        }
    }
}
