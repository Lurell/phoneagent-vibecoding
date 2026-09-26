package io.phoneagent.container

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 把「运行环境说明」部署进容器，让 Agent 知道自己在哪、边界在哪。
 *
 * 这不是锦上添花，是**可用性的必要条件**：Agent 不知道产出该放哪，就会写进 `/root/`，
 * 而那个位置用户永远看不到。实测（Claude Code 2.1.283）它读完之后会主动复述
 * 「APK 必须由用户亲手安装」这类约定 —— 安全边界因此从「一堵墙」变成「一条规则」，
 * Agent 是在配合这个模型工作，而不是反复撞墙报错。
 *
 * 部署位置是 `~/.claude/CLAUDE.md`（容器内 `/root/.claude/CLAUDE.md`），
 * Claude Code 会把它作为用户级记忆读取。注意 `/root` 是 bind mount 到
 * 宿主的 `files/home`，所以文件实际落在应用私有目录里。
 *
 * ## 为什么不能简单地「有就跳过」
 *
 * 这份文档会随着新能力（通道、指令白名单）不断更新。如果只在文件不存在时写入，
 * 早期用户就永远拿不到新版本；而每次都覆盖，又会把用户自己的修改冲掉。
 *
 * 所以用「托管文件」的常规做法：把我们**上次写入内容的哈希**记在旁边。
 * 只有当文件不存在、或者内容仍等于我们上次写的那份（说明用户没改过）时才覆盖。
 * 用户一旦动过，我们就让路。
 */
class AgentEnvDoc(
    private val context: Context,
    private val paths: Paths,
) {

    companion object {
        private const val ASSET = "env/CLAUDE.md"

        /**
         * 这份文档开头的固定标题。用来判断一个**没有 stamp 记录**的同名文件是不是我们写的。
         * 没有它的话，一次手动放置（或早期版本遗留）就会让文件被永久误判成「用户的」。
         */
        private const val OWNERSHIP_MARKER = "# 你的运行环境"

        /** Claude Code 的用户级记忆文件。 */
        private fun targetFile(paths: Paths) = File(paths.home, ".claude/CLAUDE.md")

        /** 记录我们上次写入内容的哈希，用来判断文件是否被用户改过。 */
        private fun stampFile(paths: Paths) = File(paths.home, ".claude/.phoneagent-env-hash")
    }

    /**
     * 需要时更新环境说明。
     * @return true 表示这次写了（新装或升级），false 表示保持原样（用户改过或无变化）
     */
    fun ensureUpToDate(): Boolean {
        val target = targetFile(paths)
        val stamp = stampFile(paths)

        val bundled = try {
            context.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Exception) {
            return false
        }

        // 按当前实际状态渲染（见 render 的说明），再拿**渲染结果**算哈希 ——
        // 哈希算的是将要写出去的内容，不是模板本身。
        val rendered = render(bundled, flags(), values()).toByteArray(Charsets.UTF_8)
        val bundledHash = sha256(rendered)

        if (target.isFile) {
            val currentText = try {
                target.readText()
            } catch (_: Exception) {
                ""
            }
            val currentHash = sha256(currentText.toByteArray())
            val lastWrittenHash = if (stamp.isFile) stamp.readText().trim() else ""

            // 内容已经是最新的 → 不用重写，但**仍然要落 stamp**。
            //
            // 这一条曾经漏掉，导致一个很隐蔽的问题：如果文件是靠手动放置等方式
            // 变成「已是最新」的，stamp 就永远不写；等我们之后更新了资源，
            // 判断逻辑会因为「没有 stamp 且内容不等于资源」而认定用户改过它，
            // 于是**永远不再更新** —— 表现就是「新写的说明根本没生效」。
            if (currentHash == bundledHash) {
                if (lastWrittenHash != bundledHash) {
                    runCatching { stamp.writeText(bundledHash) }
                }
                return false
            }

            // 内容与我们不同。判断它是不是「我们写的、但用户改过」：
            //   - 有 stamp 且等于当前内容 → 我们写的、未被改动（只是资源更新了）→ 覆盖
            //   - 无 stamp 但开头是我们的标记 → 早期遗留或手动放置 → 认领并覆盖
            //   - 其它 → 真的是用户的文件 → 让路
            val ours = currentHash == lastWrittenHash || currentText.startsWith(OWNERSHIP_MARKER)
            if (!ours) return false
        }

        return try {
            target.parentFile?.mkdirs()
            target.writeBytes(rendered)
            stamp.writeText(bundledHash)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 渲染文档时要知道的那些「当前状态」。
     *
     * 凡是**会随用户设置变化**的陈述，都必须在这里有一条对应的取值 ——
     * 环境说明写错的代价不是「不好看」，而是 Agent 会照着一件不成立的事去行动：
     * 以为产出能送到用户手里、以为某条指令能用、以为工具已经装好了。
     */
    private fun flags(): Map<String, Boolean> = mapOf(
        "android-tools" to AndroidToolchain.isInstalled(paths),
        "bridge" to BridgeStore(context, paths).isAuthorized(),
        "phone-any" to phonePolicy().allowedCommands().isNotEmpty(),
    )

    /** 模板里 `{{name}}` 的取值。与 [flags] 的区别：这些是**内容**，不是开关。 */
    private fun values(): Map<String, String> = mapOf(
        "phone-commands" to phonePolicy().let { policy ->
            PhonePolicy.COMMANDS.filter { policy.isAllowed(it.name) }
                .joinToString("\n") { "- `phone ${it.example}` —— ${it.title}" }
        },
    )

    private fun phonePolicy() = PhonePolicy(context)

    /**
     * 渲染文档里的条件块与占位符。
     *
     * 语法（标记各占一整行，不支持嵌套）：
     *
     * ```
     * <!--if android-tools-->
     * ……这个能力装好时才出现的内容……
     * <!--else-->
     * ……没装时出现的内容……
     * <!--endif-->
     * ```
     *
     * 行内还可以出现 `{{name}}` 占位符（见 [values]），替换在**逐行**上做，
     * 所以替换进去的内容可以自带换行。
     *
     * ## 为什么需要它
     *
     * 环境说明必须**说真话**，而里面有相当一部分是**会随用户设置变化**的：
     * 构建工具装没装、文件通道开没开、哪几条手机指令被允许。这个区别很要命：
     *
     *  - 装了却不说 → Agent 不知道工具就在手边，会去 `apt install gradle`、下载 SDK，
     *    白折腾很久然后失败
     *  - 没装却说有 → 它一直报「找不到 build-apk.sh」，用户也不知道该怎么办
     *  - 没开通道却说「产出放这儿用户就能拿到」→ **它以为交付了，其实东西烂在容器里**
     *
     * 与其让 Agent 自己去探测（它未必会想到），不如在部署时就把对应的一段写进去。
     * 包里的 `env/CLAUDE.md` 因此是一份**模板**，不是最终稿。
     *
     * ⚠ 渲染结果变了 = 文件内容变了 = 下次部署会重写。这是对的：状态变了，
     * 文档就该变。**但正在跑的 Agent 不会重读**（见 [ensureUpToDate] 的说明）。
     */
    private fun render(
        template: String,
        flags: Map<String, Boolean>,
        values: Map<String, String>,
    ): String {
        val out = StringBuilder()
        var emitting = true
        for (line in template.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("<!--if ") && trimmed.endsWith("-->") -> {
                    val name = trimmed.removePrefix("<!--if ").removeSuffix("-->").trim()
                    emitting = flags[name] == true
                }
                trimmed == "<!--else-->" -> emitting = !emitting
                trimmed == "<!--endif-->" -> emitting = true
                else -> if (emitting) out.append(substitute(line, values)).append('\n')
            }
        }
        return out.toString()
    }

    private fun substitute(line: String, values: Map<String, String>): String {
        if (!line.contains("{{")) return line
        var s = line
        values.forEach { (key, value) -> s = s.replace("{{$key}}", value) }
        return s
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
