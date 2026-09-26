package io.phoneagent.container

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom

/**
 * 容器里的 Agent 调用手机能力的入口。
 *
 * ```
 * 容器内 Agent
 *    ↓  执行 /usr/local/bin/phone notify "标题" "正文"
 * phone CLI（bash，用内建 /dev/tcp 连过来）
 *    ↓  TCP 127.0.0.1:<随机端口>，带令牌
 * 本类：校验令牌 → 查白名单 → 执行 Android API → 回一行 JSON
 * ```
 *
 * ## 为什么是 TCP 而不是 Unix domain socket
 *
 * Android 的 Java API **不支持文件系统上的 Unix domain socket**（`LocalServerSocket`
 * 用的是抽象命名空间，Node 侧连接支持差）。而 TCP 有个额外好处：容器里的 CLI 可以用
 * bash 内建的 `/dev/tcp` 直连 —— **零依赖**，不用装 socat、不用依赖 Node。
 *
 * ## 安全
 *
 * - 只监听 `127.0.0.1`，不对外
 * - 端口是**随机分配的**（bind 到 0），不是写死的，避免冲突也避免被猜
 * - 每次启动生成**随机令牌**，写在 App 私有目录里的 endpoint 文件里。
 *   同一台设备上其它应用即使扫到端口，也拿不到令牌。
 * - 真正决定「能不能做」的是 [PhonePolicy] 白名单 —— 令牌只解决「谁在说话」，
 *   白名单解决「允许说什么」。
 */
class PhoneServer(
    private val context: Context,
    private val paths: Paths,
    private val policy: PhonePolicy,
    private val onLog: (LogLevel, String) -> Unit,
) {

    companion object {
        private const val CHANNEL_ID = "phoneagent.agent"
        private const val CLI_NAME = "phone"

        /** endpoint 文件在容器里的路径，CLI 会读它。 */
        private const val RUN_DIR = "run/phoneagent"
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var running = false
    private var token: String = ""

    val isRunning: Boolean get() = running

    /** 容器里 endpoint 文件的真实路径（App 视角）。 */
    private fun endpointFile(container: Boolean): File {
        val rel = "$RUN_DIR/endpoint"
        return if (container) File("/$rel") else File(paths.rootfs, rel)
    }

    private fun cliFile(): File = File(paths.rootfs, "usr/local/bin/$CLI_NAME")

    fun start() {
        if (running) return

        try {
            // 必须显式绑 127.0.0.1，**不能用 InetAddress.getLoopbackAddress()** ——
            // 那个方法在 Android 上返回的是 `::1`（IPv6 回环），于是服务器只监听 IPv6，
            // 而 CLI 连的是 IPv4 的 127.0.0.1，结果 Connection refused。
            // 实测证据：/proc/net/tcp6 里有 `...01000000:PORT 0A`（监听），/proc/net/tcp 为空。
            //
            // 也不要图省事绑通配地址 —— 那会把端口暴露到局域网。
            val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            token = newToken()

            // 每次启动都重新部署：端口和令牌每次都变，CLI 也必须是最新的
            deployEndpoint(socket.localPort)
            deployCli()

            running = true
            acceptThread = Thread({
                while (running) {
                    val client = try {
                        socket.accept()
                    } catch (_: Exception) {
                        break // socket 关了
                    }
                    try {
                        client.use { handleConnection(it) }
                    } catch (e: Exception) {
                        onLog(LogLevel.WARN, "指令通道出错：${e.message}")
                    }
                }
            }, "PhoneServer").apply { isDaemon = true; start() }

            onLog(LogLevel.INFO, "指令通道已就绪（端口 ${socket.localPort}）")
        } catch (e: Exception) {
            running = false
            onLog(LogLevel.ERROR, "指令通道启动失败：${e.message}")
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        acceptThread = null
    }

    // ── 部署 ───────────────────────────────────────────────────────────────

    private fun deployEndpoint(port: Int) {
        val f = endpointFile(container = false)
        f.parentFile?.mkdirs()
        f.writeText("PORT=$port\nTOKEN=$token\n")
    }

    private fun deployCli() {
        val dest = cliFile()
        dest.parentFile?.mkdirs()
        context.assets.open("phone/phone").use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        // 必须可执行，否则 Agent 调不动（而且报错很难懂）
        try {
            android.system.Os.chmod(dest.absolutePath, 0b111101101) // 0755
        } catch (_: Exception) {
        }
    }

    // ── 协议 ───────────────────────────────────────────────────────────────

    private fun handleConnection(socket: java.net.Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)

        val line = reader.readLine() ?: return
        val response = try {
            handleRequest(JSONObject(line))
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "请求格式错误：${e.message}")
        }
        writer.write(response.toString())
        writer.write("\n")
        writer.flush()
    }

    private fun handleRequest(req: JSONObject): JSONObject {
        if (req.optString("token") != token) return err("令牌不对")

        // cmd 与 args 都是 base64。见 CLI 脚本头部的说明：与其在 shell 里和
        // JSON 转义规则搏斗，不如换一种不需要转义的编码。
        val cmd = decode(req.optString("cmd"))
        val rawArgs = req.optJSONArray("args") ?: JSONArray()
        val args = (0 until rawArgs.length()).map { decode(rawArgs.optString(it)) }
        val arg = { i: Int -> args.getOrElse(i) { "" } }

        if (cmd.isEmpty()) return err("缺少 cmd")

        // 白名单在这里生效 —— 这是容器与手机之间的边界
        if (cmd !in PhonePolicy.COMMANDS.map { it.name }) return err(policy.unknownCommandMessage(cmd))
        if (!policy.isAllowed(cmd)) return err(policy.deniedMessage(cmd))

        return try {
            when (cmd) {
                "list" -> ok(policy.allowedCommands().joinToString(", "))

                "notify" -> {
                    val title = arg(0)
                    val text = arg(1)
                    if (title.isEmpty() && text.isEmpty()) err("notify 需要标题或正文")
                    else ok(doNotify(title.ifEmpty { "来自容器" }, text))
                }

                "alarm" -> {
                    val time = arg(0)
                    val message = arg(1)
                    if (!TIME_RE.matches(time)) err("alarm 的时间格式应为 HH:MM（24 小时制），收到的是 `$time`")
                    else ok(doAlarm(time, message))
                }

                else -> err(policy.unknownCommandMessage(cmd))
            }
        } catch (e: Exception) {
            err("执行失败：${e.message}")
        }
    }

    /**
     * 用 **URL-safe** base64（`-` `_` 代替 `+` `/`），两个方向都是。
     *
     * 标准 base64 会产出 `/`，而 Android 的 `org.json` 默认把它转义成 `\/`
     * —— 于是 CLI 侧的提取正则（只认 base64 字符集）匹配失败，报「响应无法解析」。
     * base64 里出现 `/` 的概率约一半，所以这个 bug 是**间歇性**的：
     * 同样的代码，有的命令成功、有的失败。
     *
     * URL-safe 的字符集里没有 `/`，JSON 就永远不需要转义。
     */
    private fun decode(b64: String): String = try {
        String(android.util.Base64.decode(b64, B64), Charsets.UTF_8)
    } catch (_: Exception) {
        ""
    }

    private fun encode(s: String): String =
        android.util.Base64.encodeToString(s.toByteArray(Charsets.UTF_8), B64)

    private fun ok(message: String) = JSONObject().put("ok", true).put("body", encode(message))

    private fun err(message: String) = JSONObject().put("ok", false).put("body", encode(message))

    // ── 具体能力 ───────────────────────────────────────────────────────────

    private fun doNotify(title: String, text: String): String {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Agent 通知",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "容器里的 Agent 发来的通知" }
            )
        }

        // Android 13+ 需要运行时权限。没有的话给出可操作的提示，
        // 而不是让 Agent 以为成功了。
        val compat = NotificationManagerCompat.from(context)
        if (!compat.areNotificationsEnabled()) {
            return "通知被系统关掉了 —— 请让用户在系统设置里为本应用开启通知权限"
        }

        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        compat.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
        return "已发送通知"
    }

    private fun doAlarm(time: String, message: String): String {
        val h = time.substringBefore(':').toInt()
        val m = time.substringAfter(':').toInt()
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, h)
            putExtra(AlarmClock.EXTRA_MINUTES, m)
            if (message.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "已设置 $time 的闹钟${if (message.isEmpty()) "" else "（$message）"}"
    }

    private fun newToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private val TIME_RE = Regex("^([01]?[0-9]|2[0-3]):[0-5][0-9]$")

    /** 请求与响应统一用 URL-safe base64，不加换行。 */
    private val B64 = android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
}
