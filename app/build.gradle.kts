plugins {
    // Kotlin 支持由 AGP 9 内置提供，不要再加 org.jetbrains.kotlin.android。
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// targetSdk 是本项目唯一一个「还在用实测判定」的抉择，所以做成可切换的参数。
//
// 背景：Android 10 起，targetSdk >= 29 的 App 不能 execve 自己数据目录里的文件
// （SELinux 的 app_data_file 没有 execute_no_trans）。Termux 因此常年停在 28。
//
// 但 proot 的 loader 机制提供了一个出口：它不 execve 目标程序，而是用裸系统调用
// 把 ELF mmap 进内存再跳转。而 mmap(PROT_EXEC) 只需要 app_data_file 的 execute
// 权限 —— 这一条是允许的。于是只要把 proot 与 loader 放进 nativeLibraryDir
// （apk_data_file，任何 targetSdk 下 execve 都放行），全程就没有任何一次对
// app_data_file 的 execve。
//
// 默认 36 走这条路；若在某台设备上失败，退回 28 是这一行的事：
//     ./gradlew assembleDebug -Pphoneagent.targetSdk=28
// ---------------------------------------------------------------------------
val phoneagentTargetSdk: Int =
    providers.gradleProperty("phoneagent.targetSdk").orNull?.toInt() ?: 36

android {
    namespace = "io.phoneagent"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.phoneagent"
        minSdk = 28
        targetSdk = phoneagentTargetSdk
        versionCode = 1
        versionName = "1.0.0"

        // 只出 arm64-v8a：随包分发的 proot 与 rootfs 都是 aarch64，
        // 出别的 ABI 没有意义，只会白白撑大 APK。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        jniLibs {
            // 必须为 true。我们随 APK 分发的是「假 .so」可执行文件（proot 与它的 loader），
            // 它们只有真正落到 nativeLibraryDir 的磁盘上才能被 execve。
            // useLegacyPackaging=false 会导致安装时什么都不解出来，整个容器直接失效。
            useLegacyPackaging = true
        }
    }

    androidResources {
        // 这里刻意不设 noCompress。
        //
        // 实测：AAPT2 对 .gz 结尾的资产有特殊处理 —— 构建时会把它解压成纯 tar，
        // 去掉文件名里的 .gz，再按普通资产 deflate。所以 noCompress += "gz" 是
        // 无效的，反而会误导人以为资产是原样打包的。
        //
        // 对应地，代码侧不去假设资产的格式与文件名：
        //   - TarExtractor 按魔数嗅探是否 gzip
        //   - AssetInstaller 运行时枚举 assets/rootfs/ 取实际文件名
        // 这样不管构建系统怎么改写都能工作。
    }

    lint {
        // 只有在走 -Pphoneagent.targetSdk=28 回退路径时才会触发。属预期，不是问题。
        disable += "ExpiredTargetSdkVersion"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // SAF 目录的读写。用 DocumentFile 而不是裸路径 —— 分区存储下 App 拿不到
    // 公共目录的真实路径，SAF 给的是 content:// URI。
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
