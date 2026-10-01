// ============================================================
// app 模块 — Android 应用（Compose for TV 界面 + 本地存储）
// ============================================================
// 注意：Gradle 的 Kotlin DSL 里 `java` 会被 Java 插件扩展**遮蔽**，
// 直接写 `java.util.Properties` 会报 Unresolved reference 'util'。
// 必须在文件顶部显式 import 才能用。
import java.io.File
import java.util.Properties

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

    testOptions {
        unitTests {
            // Robolectric 在 JVM 上跑界面测试时必须打开：否则读不到 res 资源
            // （字符串、drawable 全部拿不到，界面直接崩）
            isIncludeAndroidResources = true
        }
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

    // ---- 本机 UI 自测（Robolectric + Compose UI test）----
    // 在开发机的 JVM 上渲染真实的 Compose 界面并断言，不依赖电视/模拟器，也不用截图。
    // 能测到：页面渲染、文字内容、**布局边界（是否被压扁/越界）**、焦点所在单元。
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// ============================================================
// 本地真实头像（可选，且**绝不进版本控制**）
//
// 公开仓库里只有生成的卡通占位头像。真实照片放在仓库**外面**，路径写在
// local.properties（该文件本来也不进版本控制）的 avatars.dir 里：
// 设了就优先用真照片，没设就用仓库里的占位图。
//
// 这样「推上去的代码」与「你本地跑的应用」可以不一样，而照片永远不会进 git 历史。
// 为什么不放仓库里再加 .gitignore：那样只隔了一层可能被绕过的规则（git add -f、
// 换台机器忘了配），放到仓库外面则是**结构上**不可能被提交。
// ============================================================
run {
    val lp = rootProject.file("local.properties")
    if (!lp.exists()) return@run
    val dir = Properties()
        .apply { lp.inputStream().use { load(it) } }
        .getProperty("avatars.dir")
        ?: return@run
    val res = File(dir)
    if (!res.isDirectory) {
        logger.lifecycle("avatars.dir 指向的目录不存在，使用仓库里的卡通占位头像：$dir")
        return@run
    }
    // **必须校验**：avatars.dir 要指向「资源目录的父目录」（里面是 drawable-nodpi/），
    // 不是直接指向 drawable-nodpi —— 指错了 AGP 什么也找不到，而且**静默不生效**。
    // 我自己就在这里踩过一次：构建日志一切正常，装到电视上还是卡通头像。
    if (!File(res, "drawable-nodpi/avatar_boss.png").isFile) {
        logger.lifecycle(
            "avatars.dir 里没有 drawable-nodpi/avatar_boss.png，忽略该设置（用占位头像）：$dir" +
                "｜正确写法是资源目录的父目录，比如 .../weiqi-avatars（里面放 drawable-nodpi/）",
        )
        return@run
    }
    // 用 buildType 对应的 **source set** 覆盖 main。
    // AGP 的资源优先级是「buildType source set > main」，这是文档保证的顺序，
    // 比在同级 srcDirs 里赌先后可靠（我先赌了一次，赌反了：
    // 构建日志一切正常，装到电视上还是卡通头像）。
    // 注意不是 `buildTypes.getByName("release").res` —— BuildType 在这个 AGP 版本没有 res。
    android.sourceSets.getByName("release").res.srcDir(res)
    android.sourceSets.getByName("debug").res.srcDir(res)
    logger.lifecycle("使用本地真实头像：$dir")
}

// 让 UI 自测里的 println 显示出来 —— 布局尺寸、焦点位置这些实测值靠它输出
tasks.withType<Test> {
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}