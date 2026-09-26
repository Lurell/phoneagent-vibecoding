// phoneagent — 在未 root 的 Android 上运行 proot + Linux rootfs
//
// 仓库只用 google() 与 mavenCentral()，刻意不加 gradlePluginPortal()：
// 本项目要构建的机器上 github.com 不可达，而 plugins.gradle.org 会往那边跳。
// AGP 的 plugin marker 在 Google Maven 上，两个 Kotlin plugin marker 在 Maven Central 上，
// 这两个源已经覆盖全部所需插件。
pluginManagement {
    repositories {
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "phoneagent"
include(":app")
