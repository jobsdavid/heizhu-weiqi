// ============================================================
// core 模块 — 纯 JVM，零 Android 依赖
//
// 为什么单独拆模块：围棋规则（打劫/自杀/提子/数子）是整个项目
// 唯一"出错就会让孩子学错棋"的地方。纯 JVM 模块可以跑毫秒级
// 单元测试，无需启动模拟器，才能在开发中反复验证。
// ============================================================
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // MCTS 引擎的 suspend 取消支持
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}

tasks.withType<Test> {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
        // 打印测试里的 println —— 引擎的性能数据靠它输出
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
