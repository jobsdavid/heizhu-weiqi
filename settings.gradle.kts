// ============================================================
// 围棋练习 TV — Gradle 设置
// 模块：core（纯 JVM 规则+AI，可高速单测） / app（Android UI+存储）
// ============================================================
pluginManagement {
    repositories {
        // 国内镜像优先 + 官方源兜底。
        // 实测：services.gradle.org 直连只有 ~170KB/s 且会断流，
        // 腾讯云镜像可达 13~30MB/s。dl.google.com 直连本身很快（42MB/s）。
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "WeiqiTV"

include(":core")
include(":app")