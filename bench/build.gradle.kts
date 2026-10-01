// ============================================================
// bench 模块 — 棋力评测台（纯 JVM，命令行）
//
// 为什么不放进 core：core 要能作为库被 app 引用，不该带一个 main。
// 为什么不放进 app：app 是 Android 模块，跑不了 JVM 命令行。
//
// 定位：让 Python 侧能驱动本引擎 ——「给局面 → 要一手」。
// KataGo 的分析引擎也是 JSON 进出，两边对称，不需要实现 GTP 协议。
// ============================================================
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
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
    implementation(project(":core"))
    // findBestMove 是 suspend 函数，命令行里用 runBlocking 调
    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass.set("com.heizhu.weiqi.bench.MainKt")
}
