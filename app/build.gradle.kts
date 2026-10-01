// ============================================================
// app 模块 — Android 应用（Compose for TV 界面 + 本地存储）
// ============================================================
plugins {
    alias(libs.plugins.android.application)
    // 故意不应用 org.jetbrains.kotlin.android：AGP 9.0 起内置 Kotlin 支持，
    // 再显式应用该插件会直接构建失败（AGP 会给出明确报错）。
    // Compose 编译器插件与 serialization 编译器插件仍需显式声明。
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.heizhu.weiqi"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.heizhu.weiqi"
        minSdk = 26          // Android 8.0：2026 年旗舰电视无需向下兼容
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // 自用侧载，不做混淆（避免 ProGuard 误杀序列化类）
            isMinifyEnabled = false
            isShrinkResources = false
            // 用 debug 签名，方便直接 U 盘侧载；无需配置 keystore
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/*.kotlin_module",
        )
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Compose：BOM 统一约束版本
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)

    // Compose for TV（D-pad 焦点、焦点缩放、大屏组件）
    implementation(libs.androidx.tv.material)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}