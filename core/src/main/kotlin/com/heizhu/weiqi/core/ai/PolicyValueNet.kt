package com.heizhu.weiqi.core.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.exp
import kotlin.math.max

/**
 * 小网络前向（策略 + 价值），**纯 Kotlin、零依赖**。
 *
 * 权重由 `tools/katago/train_pv.py` 导出。导出时已把 BatchNorm 折进前面的卷积，
 * 所以这里只需要实现 conv 与 relu —— 这也是刻意选的路径：
 * app 是离线的，引入 ONNX/TFLite 只会多出体积、ABI 与线程的失败面，
 * 而一个有 3 万参数的小网络的乘法量，手写完全够快。
 *
 * 张量布局与 PyTorch 一致（NCHW 的 C 序展开）：`idx = c * S * S + y * S + x`。
 * 这点必须对齐 —— 布局错位不会报错，只会让网络输出垃圾。
 */
class PolicyValueNet private constructor(
    val size: Int,
    private val weights: FloatArray,
    private val spec: Map<String, LayerSpec>,
    /** 目差 → 胜率的斜率：winrate = sigmoid(kWinrate × scoreLead)。训练侧拟合后写进 manifest。 */
    val kWinrate: Double,
    private val valueScale: Double,
    /** 输入是否用「我方/对方」相对视角（见 [Manifest.view]）。旧网络无此字段 ⇒ 默认绝对视角。 */
    private val relativeView: Boolean = false,
) {

    @Serializable
    private data class LayerSpec(val name: String, val shape: List<Int>, val offset: Int)

    @Serializable
    private data class Manifest(
        val size: Int,
        @SerialName("input_planes") val inputPlanes: Int,
        val layers: List<LayerSpec>,
        val bytes: Int = 0,
        @SerialName("value_scale") val valueScale: Double = 10.0,
        @SerialName("k_winrate") val kWinrate: Double = 0.5,
        /**
         * 输入视角：
         * - `"absolute"`（默认，兼容旧网络）：[黑子, 白子, 是否轮到黑]
         * - `"relative"`：[我方子, 对方子, 恒 1]，网络在结构上无法区分黑白 ⇒ 颜色对称是数学保证
         *
         * 为什么要这个字段：低档网络小、数据少，用绝对视角会学出"偏袒某一色"
         * （实测入门档自战黑 33% / 白 67%）。而相对视角虽对称，却在同训练量下棋力偏低
         * —— 对"低档"这正好（本来就该弱），对"高档"则不可接受。
         * 于是让**每个网络自带视角标记**，两档可以共存、各取所长。
         */
        val view: String = "absolute",
    )

    /** 一次评估的结果。 */
    data class Eval(
        /** 各点的策略概率（长度 size*size，已按合法点归一化；非法点恒为 0）。 */
        val policy: FloatArray,
        /** 当前行棋方的目差（正 = 领先）。 */
        val scoreLead: Float,
    ) {
        /** 转成 MCTS 需要的胜率（它按胜负回传），用训练侧拟合的斜率。 */
        fun winrate(k: Double): Double = 1.0 / (1.0 + exp(-k * scoreLead.toDouble()))
    }

    private val s = size
    private val ss = size * size

    private fun specOf(name: String): LayerSpec =
        spec[name] ?: error("权重里没有 $name（导出与推理的层名必须一致）")

    private inline fun <T> with(name: String, block: (FloatArray, Int, LayerSpec) -> T): T {
        val sp = specOf(name)
        return block(weights, sp.offset, sp)
    }

    /**
     * 评估一个局面。
     *
     * @param cells `Board.cells`（0 空 / 1 黑 / 2 白）
     * @param toMove 当前行棋方（1 = 黑，2 = 白）
     */
    fun evaluate(cells: ByteArray, toMove: Byte): Eval {
        require(cells.size == ss) { "棋盘尺寸不符：${cells.size} != $ss" }

        // ---- 输入 3 个平面（按网络自带的视角标记构造）----
        //  · relative：[我方子, 对方子, 恒 1] —— 网络看不到"黑/白"，偏色在结构上不可能发生
        //  · absolute：[黑子, 白子, 是否轮到黑] —— 旧网络（含发货版）的编码，必须逐字保持
        // ⚠️ 两者不可混用：网络权重是按各自编码训练的，错配会让输出全错。
        //    标记存在 manifest 的 view 字段里，由训练侧导出时写入（见 train_pv.py）。
        var x = FloatArray(3 * ss)
        if (relativeView) {
            val me = toMove.toInt()                       // 我方 = 当前行棋方（1 黑 / 2 白）
            val opp = if (me == 1) 2 else 1
            for (i in 0 until ss) {
                when (cells[i].toInt()) {
                    me -> x[i] = 1f                        // 我方棋子平面
                    opp -> x[ss + i] = 1f                  // 对方棋子平面
                }
            }
            java.util.Arrays.fill(x, 2 * ss, 3 * ss, 1f)   // 恒 1：视角已统一
        } else {
            for (i in 0 until ss) {
                when (cells[i].toInt()) {
                    1 -> x[i] = 1f                         // 黑子平面
                    2 -> x[ss + i] = 1f                    // 白子平面
                }
            }
            val blackToMove = if (toMove.toInt() == 1) 1f else 0f
            java.util.Arrays.fill(x, 2 * ss, 3 * ss, blackToMove)
        }

        // ---- stem：3→width，3×3，pad 1 ----
        // ⚠️ 这里**必须带 relu**（PyTorch 侧是 F.relu(stem_bn(stem(x)))）。
        // 一次漏写让端侧输出全错：策略偏差 0.094 > 策略本身的量级 0.019，
        // 而权重/manifest 两侧逐字节一致、残差块也对 —— 最后是靠"把 Kotlin 逐行
        // 照搬成 Python 复现"才定位到的。移植手写前向时，激活函数的对齐比权重更易漏。
        var width = specOf("stem.w").shape[0]
        var cur = relu(conv3x3("stem.w", "stem.b", 3, width, x))

        // ---- 若干残差块 ----
        var block = 0
        while (spec.containsKey("block$block.c1.w")) {
            val c = specOf("block$block.c1.w").shape[0]
            val inner = relu(conv3x3("block$block.c1.w", "block$block.c1.b", c, c, cur))
            val out = conv3x3("block$block.c2.w", "block$block.c2.b", c, c, inner)
            for (i in cur.indices) out[i] += cur[i]      // 残差
            cur = relu(out)
            block++
            width = c
        }

        // ---- 策略头 ----
        val ph = relu(conv1x1("pconv.w", "pconv.b", width, 16, cur))
        val logits = conv1x1("pout.w", "pout.b", 16, 1, ph)

        // ---- 价值头 ----
        val vh = relu(conv1x1("vconv.w", "vconv.b", width, 8, cur))
        val flat = vh                                             // 已是 C 序，(c,y,x)
        val h1 = matVec("vfc1.w", "vfc1.b", 64, flat)
        val h1r = FloatArray(h1.size) { max(0f, h1[it]) }
        val v = matVec("vfc2.w", "vfc2.b", 1, h1r)

        // ---- 策略掩码 + 归一化：只保留空点 ----
        val policy = FloatArray(ss)
        var sum = 0f
        for (i in 0 until ss) {
            if (cells[i].toInt() == 0) {
                val e = exp(logits[i].toDouble()).toFloat()
                policy[i] = e
                sum += e
            }
        }
        if (sum > 0f) for (i in 0 until ss) policy[i] /= sum

        return Eval(policy = policy, scoreLead = v[0] * valueScale.toFloat())
    }

    // ============================================================
    // 基本算子
    // ============================================================

    private fun relu(a: FloatArray): FloatArray {
        for (i in a.indices) if (a[i] < 0f) a[i] = 0f
        return a
    }

    /** 3×3 卷积，pad=1（保持分辨率）。权重按 PyTorch 的 (out,in,kh,kw) 展开。 */
    private fun conv3x3(wName: String, bName: String, cin: Int, cout: Int, input: FloatArray): FloatArray {
        val out = FloatArray(cout * ss)
        with(wName) { wArr, wOff, _ ->
            with(bName) { bArr, bOff, _ ->
                for (oc in 0 until cout) {
                    val bias = bArr[bOff + oc]
                    val wBase = wOff + oc * cin * 9
                    val oBase = oc * ss
                    for (y in 0 until s) {
                        for (xx in 0 until s) {
                            var acc = bias
                            for (ic in 0 until cin) {
                                val wIc = wBase + ic * 9
                                val iBase = ic * ss
                                for (ky in 0..2) {
                                    val iy = y + ky - 1
                                    if (iy < 0 || iy >= s) continue
                                    val row = iBase + iy * s
                                    val wRow = wIc + ky * 3
                                    for (kx in 0..2) {
                                        val ix = xx + kx - 1
                                        if (ix < 0 || ix >= s) continue
                                        acc += wArr[wRow + kx] * input[row + ix]
                                    }
                                }
                            }
                            out[oBase + y * s + xx] = acc
                        }
                    }
                }
            }
        }
        return out
    }

    /** 1×1 卷积。权重按 (out,in,1,1) 展开。 */
    private fun conv1x1(wName: String, bName: String, cin: Int, cout: Int, input: FloatArray): FloatArray {
        val out = FloatArray(cout * ss)
        with(wName) { wArr, wOff, _ ->
            with(bName) { bArr, bOff, _ ->
                for (oc in 0 until cout) {
                    val bias = bArr[bOff + oc]
                    val wBase = wOff + oc * cin
                    val oBase = oc * ss
                    for (ic in 0 until cin) {
                        val wv = wArr[wBase + ic]
                        if (wv == 0f) continue
                        val iBase = ic * ss
                        for (i in 0 until ss) out[oBase + i] += wv * input[iBase + i]
                    }
                    for (i in 0 until ss) out[oBase + i] += bias
                }
            }
        }
        return out
    }

    /** 全连接：权重按 (out,in) 展开。 */
    private fun matVec(wName: String, bName: String, nout: Int, input: FloatArray): FloatArray {
        val out = FloatArray(nout)
        with(wName) { wArr, wOff, _ ->
            with(bName) { bArr, bOff, _ ->
                val nin = input.size
                for (o in 0 until nout) {
                    var acc = bArr[bOff + o]
                    val base = wOff + o * nin
                    for (i in nin - 1 downTo 0) acc += wArr[base + i] * input[i]
                    out[o] = acc
                }
            }
        }
        return out
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * 从 manifest.json + weights.bin 加载。
         *
         * @param manifestJson train_pv.py 导出的 manifest
         * @param bin          平铺的 float32 权重（小端）
         */
        fun load(manifestJson: String, bin: ByteArray): PolicyValueNet {
            val m = json.decodeFromString<Manifest>(manifestJson)
            require(bin.size % 4 == 0) { "权重文件长度不是 4 的倍数：${bin.size}" }
            val floats = FloatArray(bin.size / 4)
            for (i in floats.indices) {
                val o = i * 4
                val bits = (bin[o].toInt() and 0xFF) or
                    ((bin[o + 1].toInt() and 0xFF) shl 8) or
                    ((bin[o + 2].toInt() and 0xFF) shl 16) or
                    ((bin[o + 3].toInt() and 0xFF) shl 24)
                floats[i] = Float.fromBits(bits)
            }
            val map = m.layers.associateBy { it.name }
            // 校验：每个偏移都必须落在文件内 —— 否则以后导出格式一变就是越界读
            for (l in m.layers) {
                val n = l.shape.fold(1) { a, b -> a * b }
                require(l.offset + n <= floats.size) {
                    "层 ${l.name} 越界：offset=${l.offset} n=$n total=${floats.size}"
                }
            }
            return PolicyValueNet(m.size, floats, map, m.kWinrate, m.valueScale, m.view == "relative")
        }

        /** 权重占用（字节），用于日志与体积核对。 */
        fun weightBytes(bin: ByteArray): Int = bin.size
    }
}
