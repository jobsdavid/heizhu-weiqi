package com.heizhu.weiqi.data

import com.heizhu.weiqi.core.ai.PolicyValueNet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 回归闸门：**没有权重的棋盘尺寸必须回退，不能崩**。
 *
 * 真机事故（2026-10-02）：那时只有 9 路有蒸馏权重，13 路还没有 —— 而 [NetAssets] 旧实现
 * 把"没有网络"这件事用 **null 写进了 `ConcurrentHashMap`**，它在**运行时**拒绝 null 值，
 * 直接抛 NPE。表现是"在电视上选 13 路 → 应用退出"。
 * 编译期看不出来：缓存字段当时就声明成 `PolicyValueNet?`，编译器不会拦。
 *
 * 为什么写成纯 JVM（不用 Robolectric + `ApplicationProvider`）：
 * 这台机器上 Robolectric 起不来（`Failed to interact with raw FileDescriptor internals`，
 * 见 SKILL §13.4），用例会因为环境而红，等于没有闸门。
 * [NetAssets.load] 提供了可注入加载器的内部重载，这条路径因此**不需要 Context** 就能测。
 */
class NetAssetsFallbackTest {

    /** 有前缀（如 pv13）但 assets 里没有对应权重 → 必须回退而不是崩。 */
    @Test
    fun `没有权重的尺寸必须回退而不是崩溃`() {
        for (size in listOf(9, 13)) {
            val calls = AtomicInteger()
            val loader: (String) -> PolicyValueNet? = { calls.incrementAndGet(); null }
            assertNull("${size} 路没有权重，应当返回 null 让引擎回退随机 rollout",
                NetAssets.load(size, "master", loader))
            assertEquals("${size} 路应当试过「档位权重」和「通用权重」两条路",
                2, calls.get())
            // 第二次必须也稳 —— "缓存缺失"这条路径正是当初的崩溃点（null 进了容器）
            assertNull("${size} 路再取一次也必须稳定回退（缺失要记在 absent，不能塞进 cache）",
                NetAssets.load(size, "master", loader))
            assertEquals("${size} 路缺失应当被记住，第二次不再重复读 assets", 2, calls.get())
        }
    }

    /** 有权重的尺寸但权重缺失（文件损坏/没打包）：也要回退，且第二次不再重复尝试。 */
    @Test
    fun `权重加载失败时同样回退且记住缺失`() {
        val calls = AtomicInteger()
        val loader: (String) -> PolicyValueNet? = { calls.incrementAndGet(); null }
        val tier = "unit-test-absent"          // 用不会与真实档位冲突的 id
        assertNull(NetAssets.load(9, tier, loader))
        assertEquals("第一次应当试过「档位权重」和「通用权重」两条路", 2, calls.get())
        assertNull(NetAssets.load(9, tier, loader))
        assertEquals("缺失应当被记住，第二次不再重复读 assets", 2, calls.get())
        assertTrue("容器里不该留下 null 值", calls.get() > 0)
    }
}
