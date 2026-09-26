package io.phoneagent.container

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 一个**可选**的安装项：让 Agent 能在手机上自己编 APK。
 *
 * 约 500MB（JDK 就占 400MB），日常用 Agent 完全不需要它 —— 所以它是独立的一步，
 * 不是「准备 Agent 环境」的一部分。
 *
 * ## 为什么要这么绕
 *
 * Google 官方的 Android 构建工具**全是 x86_64**（它们的解释器是
 * `/lib64/ld-linux-x86-64.so.2`，在 aarch64 的 Android 上根本不存在）。
 * 所以拆开看，只有 aapt2 是死路，其余都能找到 aarch64 或架构无关的替代：
 *
 * | 组件 | 来源 | 架构问题 |
 * |---|---|---|
 * | JDK | Adoptium 的 linux-aarch64 | 无 |
 * | **aapt2** | **Termux 为 aarch64 编译的** | bionic 链接，靠 `-b /system -b /apex` 运行 |
 * | d8 / apksigner | 官方包里的**纯 Java JAR** | 无 |
 * | android.jar | 官方 platform 包 | 纯 Java 字节码 |
 *
 * 详细的装配步骤见 `assets/android-tools/setup-toolchain.sh`。
 */
class AndroidToolchain(
    private val context: Context,
    private val paths: Paths,
    private val command: ProotCommand,
) {

    companion object {
        private const val TERMUX_REPO = "https://packages.termux.dev/apt/termux-main"

        /** 与 setup-toolchain.sh 约定的标记文件。 */
        private fun markerFile(paths: Paths) = File(paths.rootfs, "opt/android-tools/.installed")

        /**
         * 只查标记文件在不在，不需要构造实例。
         *
         * 给 [AgentEnvDoc] 用 —— 它要在渲染环境说明时知道构建工具装没装，
         * 而那件事发生在容器启动的路径上，没必要为此构造整个安装器。
         */
        fun isInstalled(paths: Paths): Boolean = markerFile(paths).isFile

        /** 这些版本是实测验证过的，钉死以免上游变动导致行为漂移。 */
        private val TERMUX_PACKAGES = listOf(
            "pool/main/a/aapt2/aapt2_16.0.0.4-2_aarch64.deb",
            "pool/main/a/abseil-cpp/abseil-cpp_20260526.0_aarch64.deb",
            "pool/main/libp/libprotobuf/libprotobuf_2:35.1_aarch64.deb",
            "pool/main/f/fmt/fmt_1:11.2.0-1_aarch64.deb",
            "pool/main/libc/libc++/libc++_30_aarch64.deb",
            "pool/main/libe/libexpat/libexpat_2.8.5_aarch64.deb",
            "pool/main/libp/libpng/libpng_1.6.58_aarch64.deb",
            "pool/main/libz/libzopfli/libzopfli_1.0.3-5_aarch64.deb",
            "pool/main/z/zlib/zlib_1.3.2_aarch64.deb",
        )

        /**
         * JDK 的镜像源。
         *
         * ⚠ **列表里所有 URL 必须提供逐字节相同的文件** —— 断点续传会把前一个源
         * 下了一半的字节接在后一个源上。所以：
         *
         *  - 版本号**钉死**，不用 `latest`（浮动版本 = 不同的字节）
         *  - 官方 API（`api.adoptium.net`）**不能**进这个列表：它 307 跳到
         *    github.com（国内不可达），而且路径里的 `latest/ga` 指向的还不一定
         *    是同一个构建 —— 万一哪天它可达了，就会和清华的部分文件拼在一起
         *  - 南大那条虽然是小写 `adoptium`、路径不同，内容与清华**完全一致**
         *    （同为 Adoptium 21.0.12.1+1，205641175 字节）
         *
         * `Downloader` 里另有一道防线（`.total` 标记）会在总量对不上时丢弃重下。
         */
        private val JDK_URLS = listOf(
            "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/aarch64/linux/" +
                "OpenJDK21U-jdk_aarch64_linux_hotspot_21.0.12.1_1.tar.gz",
            "https://mirror.nju.edu.cn/adoptium/21/jdk/aarch64/linux/" +
                "OpenJDK21U-jdk_aarch64_linux_hotspot_21.0.12.1_1.tar.gz",
        )

        private const val PLATFORM_ZIP =
            "https://dl.google.com/android/repository/platform-37.0_r02.zip"
        private const val BUILD_TOOLS_ZIP =
            "https://dl.google.com/android/repository/build-tools_r37_linux.zip"

        private const val BUILD_APK_ASSET = "android-tools/build-apk.sh"
        private const val SETUP_ASSET = "android-tools/setup-toolchain.sh"
    }

    data class Progress(val phase: String, val detail: String = "", val percent: Int = -1)

    sealed interface Result {
        data object AlreadyInstalled : Result
        data class Installed(val summary: String) : Result
        data class Failed(val step: String, val message: String) : Result
    }

    fun isInstalled(): Boolean = markerFile(paths).isFile

    suspend fun install(onProgress: (Progress) -> Unit): Result {
        if (isInstalled()) return Result.AlreadyInstalled

        paths.ensureDirs()
        val tmp = File(paths.rootfs, "tmp").apply { mkdirs() }
        val toolsDir = File(paths.rootfs, "opt/android-tools").apply { mkdirs() }

        // ── 1. JDK ─────────────────────────────────────────────────────────
        // 已经解压好的话就不重下 —— 这是 196MB，重试时不该再付一次。
        // （装配脚本解压完会删掉压缩包，所以「有 jdk/bin/java 且没有压缩包」是正常态。）
        if (File(toolsDir, "jdk/bin/java").isFile) {
            onProgress(Progress("JDK 已就绪，跳过"))
        } else {
            try {
                onProgress(Progress("下载 JDK 21（约 200MB）", "aarch64 Linux"))
                Downloader.fetch(JDK_URLS, File(tmp, "jdk21.tar.gz")) { pct ->
                    onProgress(Progress("下载 JDK 21", "$pct%", pct))
                }
            } catch (e: Exception) {
                return Result.Failed("下载 JDK", "${e.javaClass.simpleName}: ${e.message}")
            }
        }

        // ── 2. platform 包 → android.jar ───────────────────────────────────
        try {
            onProgress(Progress("下载 Android platform 包（约 64MB）"))
            val zip = File(tmp, "platform.zip")
            Downloader.fetch(listOf(PLATFORM_ZIP), zip) { pct ->
                onProgress(Progress("下载 platform 包", "$pct%", pct))
            }
            onProgress(Progress("取出 android.jar"))
            if (!Downloader.extractFromZip(zip, "/android.jar", File(toolsDir, "android.jar"))) {
                return Result.Failed("取出 android.jar", "压缩包里没有找到 android.jar")
            }
            zip.delete()
        } catch (e: Exception) {
            return Result.Failed("platform 包", "${e.javaClass.simpleName}: ${e.message}")
        }

        // ── 3. build-tools 包 → d8.jar / apksigner.jar ─────────────────────
        // 注意只取纯 Java 的那两个 JAR。这个包里其余是 x86_64 原生工具
        // （aapt2/zipalign 等），在 aarch64 上跑不了 —— aapt2 我们用 Termux 的替代。
        try {
            onProgress(Progress("下载 build-tools 包（约 66MB）"))
            val zip = File(tmp, "buildtools.zip")
            Downloader.fetch(listOf(BUILD_TOOLS_ZIP), zip) { pct ->
                onProgress(Progress("下载 build-tools 包", "$pct%", pct))
            }
            onProgress(Progress("取出 d8.jar 与 apksigner.jar"))
            val lib = File(toolsDir, "lib").apply { mkdirs() }
            val okD8 = Downloader.extractFromZip(zip, "lib/d8.jar", File(lib, "d8.jar"))
            val okSigner = Downloader.extractFromZip(zip, "lib/apksigner.jar", File(lib, "apksigner.jar"))
            if (!okD8 || !okSigner) return Result.Failed("取出 JAR", "压缩包里缺少 d8.jar 或 apksigner.jar")
            zip.delete()
        } catch (e: Exception) {
            return Result.Failed("build-tools 包", "${e.javaClass.simpleName}: ${e.message}")
        }

        // ── 4. Termux 的 aapt2 与依赖 ──────────────────────────────────────
        try {
            TERMUX_PACKAGES.forEachIndexed { i, path ->
                val name = path.substringAfterLast('/')
                onProgress(Progress("下载 aapt2 依赖 ${i + 1}/${TERMUX_PACKAGES.size}", name))
                Downloader.fetch(listOf("$TERMUX_REPO/$path"), File(tmp, name))
            }
        } catch (e: Exception) {
            return Result.Failed("下载 aapt2 依赖", "${e.javaClass.simpleName}: ${e.message}")
        }

        // ── 5. 脚本 ────────────────────────────────────────────────────────
        try {
            copyAsset(BUILD_APK_ASSET, File(tmp, "build-apk.sh"))
            copyAsset(SETUP_ASSET, File(tmp, "setup-toolchain.sh"))
        } catch (e: Exception) {
            return Result.Failed("部署脚本", e.message ?: "")
        }

        // ── 6. 在容器里装配 ────────────────────────────────────────────────
        onProgress(Progress("装配工具链", "解压 JDK 与 aapt2，需要几分钟"))
        val (code, out) = exec("bash /tmp/setup-toolchain.sh")
        if (code != 0) {
            return Result.Failed("装配", out.trim().takeLast(600))
        }

        markerFile(paths).parentFile?.mkdirs()
        markerFile(paths).writeText(summaryOf(out))

        return Result.Installed(out.trim().lines().takeLast(6).joinToString("\n"))
    }

    /** 卸载（清掉那 500MB）。 */
    suspend fun uninstall() = withContext(Dispatchers.IO) {
        File(paths.rootfs, "opt/android-tools").deleteRecursively()
    }

    private fun summaryOf(output: String): String =
        output.lines().filter { it.contains("version") || it.contains("aapt2") }.joinToString("\n")

    private fun copyAsset(asset: String, dest: File) {
        context.assets.open(asset).use { input ->
            dest.outputStream().use { input.copyTo(it, 64 * 1024) }
        }
    }

    private suspend fun exec(shellCommand: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val pb = ProcessBuilder(command.oneShotArgv(shellCommand))
            pb.directory(paths.files)
            pb.redirectErrorStream(true)
            pb.environment().apply {
                clear()
                putAll(command.environment())
            }
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            code to out
        }
}
