package io.phoneagent.container

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 把 Agent 运行环境准备好：Node.js + Claude Code。
 *
 * ## 为什么要绕开 apt
 *
 * 走 `apt-get install nodejs npm` 实测要装 **665 个包、十几分钟** —— 而且慢的不是网络，
 * 是 **proot 拦截每一个系统调用**带来的开销（dpkg 要处理大量小文件）。
 *
 * 所以这里换一条路：
 *
 * ```
 * 宿主网络直接下载官方 Node tarball   ~30 秒（30MB，完全不过 proot）
 *   → 写进 rootfs
 *   → 容器内只做一次解压              ~10 秒
 * ```
 *
 * 同一件事从十几分钟压到一分钟以内。**慢的那部分根本不需要在容器里发生。**
 *
 * ## 为什么用官方 tarball 而不是 NodeSource 脚本
 *
 * NodeSource 的 `setup_22.x` 脚本会改 apt 源、装一堆依赖，又回到 apt 那条慢路。
 * 官方 tarball 是自包含的，解压即用，且可以做校验。
 */
class AgentInstaller(
    private val context: Context,
    private val paths: Paths,
    private val command: ProotCommand,
) {

    companion object {
        /** 与 Claude Code 的 engines 要求对齐（它要 >= 22）。 */
        const val NODE_VERSION = "v22.23.3"

        /**
         * 用 **.tar.gz 而不是 .tar.xz**。
         *
         * `tar -xJf` 需要外部 `xz` 程序，而干净的 Ubuntu base 镜像里**没有它**
         * （只有 gzip —— dpkg 自己依赖它）。这个坑只在全新安装时出现：
         * 之前测试的容器里之所以有 xz，是因为更早跑过 `apt install nodejs npm`
         * 把它顺带装了进来。
         *
         * 代价是包大 24MB（54MB vs 30MB），但下载走宿主网络很快，
         * 而且 gzip 解压比 xz 快 —— 实际是净赚。
         */
        private const val NODE_ARCHIVE = "node-$NODE_VERSION-linux-arm64.tar.gz"

        /** 旧版本用过的 xz 包名，启动时清掉，免得白占 30MB。 */
        private const val LEGACY_ARCHIVE = "node-$NODE_VERSION-linux-arm64.tar.xz"

        /** 国内镜像优先，失败再走官方。 */
        private val NODE_URLS = listOf(
            "https://npmmirror.com/mirrors/node/$NODE_VERSION/$NODE_ARCHIVE",
            "https://nodejs.org/dist/$NODE_VERSION/$NODE_ARCHIVE",
        )

        private const val NPM_REGISTRY = "https://registry.npmmirror.com"
        private const val CLAUDE_PACKAGE = "@anthropic-ai/claude-code"

        /** 判断上次残留的压缩包是否完整（实际约 30.1MB）。 */
        private const val MIN_ARCHIVE_BYTES = 25_000_000L
    }

    data class Progress(val phase: String, val detail: String = "")

    sealed interface Result {
        /** 已经装好，无需动作。 */
        data class AlreadyReady(val node: String, val claude: String) : Result

        data class Installed(val node: String, val claude: String) : Result

        data class Failed(val step: String, val message: String) : Result
    }

    /**
     * 检测并安装。幂等 —— 已经装好的步骤会跳过。
     */
    suspend fun install(onProgress: (Progress) -> Unit): Result {
        paths.ensureDirs()

        // ── 0. 先把容器内的网络配好 ─────────────────────────────────────────
        //
        // 这一步不能省：Android 从不填 rootfs 里的 /etc/resolv.conf，所以新容器里
        // 那个文件是空的，容器内的一切域名解析都会失败（npm 报 EAI_AGAIN）。
        //
        // 而且这个失败很容易误判：Node 的压缩包是**App 下载**的（走宿主网络，不经容器），
        // 能成功；而 npm 是**容器内**跑的，需要 DNS。同一件事两个网络路径，
        // 于是表现为「下载都好好的，只有 npm 装不上」。
        onProgress(Progress("配置容器网络…"))
        val net = ContainerNetwork(context, paths).refresh()

        // ── 1. 检测现状 ────────────────────────────────────────────────────
        onProgress(Progress("检查容器环境…", "DNS: " + net.dns.joinToString(", ")))
        val nodeNow = exec("node -v 2>/dev/null || true").second.trim()
        val claudeNow = exec("claude --version 2>/dev/null || true").second.trim()

        val nodeOk = nodeNow.removePrefix("v").substringBefore('.').toIntOrNull()?.let { it >= 22 } == true
        val claudeOk = claudeNow.contains("Claude Code")

        if (nodeOk && claudeOk) {
            return Result.AlreadyReady(nodeNow, claudeNow)
        }

        // ── 2. Node ────────────────────────────────────────────────────────
        if (!nodeOk) {
            onProgress(
                Progress(
                    "下载 Node.js $NODE_VERSION",
                    if (nodeNow.isEmpty()) "约 30MB" else "当前是 $nodeNow，需要 >= 22",
                )
            )
            val archive = File(paths.rootfs, "tmp/$NODE_ARCHIVE")

            // 上一次失败留下的压缩包如果看着是完整的，就别再下一次 30MB。
            // 用体积做粗判 —— 真损坏的话下面的 tar 会明确报错，不会静默出错。
            if (archive.isFile && archive.length() > MIN_ARCHIVE_BYTES) {
                onProgress(Progress("已有下载好的压缩包，跳过下载"))
            } else {
                try {
                    download(NODE_URLS, archive)
                } catch (e: Exception) {
                    return Result.Failed("下载 Node", "${e.javaClass.simpleName}: ${e.message ?: ""}")
                }
            }

            // 实测 47 秒（proot 下解压慢）。别写「约 10 秒」—— 虚假的进度预期
            // 会让用户以为卡住了，反而更焦虑。
            onProgress(Progress("解压到 /usr/local", "约 1 分钟，proot 下解压较慢"))
            // 用 -xzf（gzip）而不是 -xJf（xz）：新 rootfs 里没有 xz 程序。
            // 先 mkdir：tar -C 要求目标目录已存在 —— 干净的 rootfs 里 /usr/local 本来就有，
            // 但不该依赖这一点。
            val (code, out) = exec(
                "rm -f /tmp/$LEGACY_ARCHIVE; " +
                    "mkdir -p /usr/local && " +
                    "tar -xzf /tmp/$NODE_ARCHIVE -C /usr/local --strip-components=1 && " +
                    "rm -f /tmp/$NODE_ARCHIVE && node -v"
            )
            if (code != 0) return Result.Failed("解压 Node", out.trim().take(400))
        }

        // ── 3. Claude Code ─────────────────────────────────────────────────
        if (!claudeOk) {
            onProgress(Progress("配置 npm 镜像", NPM_REGISTRY))
            exec("npm config set registry $NPM_REGISTRY")

            onProgress(Progress("安装 Claude Code", "npm 在 proot 下较慢，请稍候"))
            // 注意：**不要**在这里接 `| tail`。
            //
            // 管道的退出码是最后一个命令（tail）的，永远是 0 —— npm 失败了也会被
            // 当成成功，一直拖到最后一步「验证」才暴露，而且看不出真正原因。
            // 输出可能很长，但只在失败时取末尾，无所谓。
            val (code, out) = exec("npm install -g $CLAUDE_PACKAGE 2>&1")
            if (code != 0) {
                return Result.Failed("安装 Claude Code", out.trim().takeLast(800))
            }
        }

        // ── 4. 验证 ────────────────────────────────────────────────────────
        onProgress(Progress("验证…"))
        val nodeFinal = exec("node -v").second.trim()
        val claudeFinal = exec("claude --version 2>&1 | head -1").second.trim()

        return if (claudeFinal.contains("Claude Code")) {
            Result.Installed(nodeFinal, claudeFinal)
        } else {
            Result.Failed("验证", "claude 装完了但跑不起来：${claudeFinal.take(200)}")
        }
    }

    // ── 执行 ───────────────────────────────────────────────────────────────

    /**
     * 在容器里跑一条命令。
     *
     * 走的是一次性 proot（不是终端那个常驻会话）—— 安装流程不该要求用户先去启动终端。
     */
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

    /**
     * 下载。直接写进 rootfs，容器里就能以 `/tmp/xxx` 看到同一个文件。
     * 走宿主网络，完全不过 proot。
     *
     * **线程切换由本函数自己做**，不指望调用方记得 —— 之前就是因为调用方
     * （`install()`）是从 `viewModelScope.launch` 起的、落在主线程上，
     * 这里又是普通函数没切线程，直接抛了 `NetworkOnMainThreadException`。
     * 把切换放在做 I/O 的地方，这类错误就不会再犯。
     */
    private suspend fun download(urls: List<String>, dest: File): Unit = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        var lastError: Exception? = null

        for (url in urls) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 120_000
                    instanceFollowRedirects = true
                }
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    throw IllegalStateException("HTTP ${conn.responseCode}")
                }
                conn.inputStream.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
                }
                conn.disconnect()
                return@withContext
            } catch (e: Exception) {
                lastError = e
                dest.delete()
            }
        }
        throw lastError ?: IllegalStateException("所有下载源都失败了")
    }
}
