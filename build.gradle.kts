// ============================================================
// 根构建脚本 — 只声明插件，不在此应用
// 各模块（core / app）自行 apply
// ============================================================
plugins {
    alias(libs.plugins.android.application) apply false
    // kotlin.android 不再声明：AGP 9 内置 Kotlin（见 app/build.gradle.kts 注释）
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}