package io.phoneagent.container

import android.content.Context

/**
 * 指令白名单。
 *
 * 这是**容器与手机之间真正的安全边界** —— 容器内部放开权限是合理的（容器就是沙箱），
 * 但 Agent 伸手到手机上的每一个动作都必须在这里被明确允许。
 *
 * 设计要点：
 *  - **默认拒绝**：没在表里的指令一律拒绝，并告诉 Agent 原因
 *  - **逐条开关**：用户能单独关掉某一条，而不是「全开/全关」
 *  - 被拒绝时给 Agent 明确的理由，让它能换方案，而不是反复重试同一条
 */
class PhonePolicy(context: Context) {

    enum class Level { ALLOW, DENY }

    data class Command(
        val name: String,
        val title: String,
        val description: String,
        val defaultAllow: Boolean,
        /**
         * 给 Agent 看的调用示例（不含 `phone` 前缀）。
         *
         * 放在这里而不是写死在环境说明的模板里：那份说明里列的指令必须**等于当前
         * 实际允许的那些** —— 用户关掉一条，说明里就少一条。写死在模板里的话，
         * Agent 会照着一条已经被关掉的指令去调，白跑一趟。
         */
        val example: String,
    )

    companion object {
        /**
         * 支持的指令表。
         *
         * 风险分级参考：「发通知」「设闹钟」都是单向、可撤销、不泄露数据的操作，
         * 默认允许；而像「打开 App」「读文件」这类要么能被用作外泄通道、
         * 要么涉及数据，默认拒绝，需要用户显式打开。
         */
        val COMMANDS = listOf(
            Command(
                name = "notify",
                title = "发通知",
                description = "让 Agent 能主动告诉你它做完了什么。单向、可撤销。",
                defaultAllow = true,
                example = """notify "标题" "正文"""",
            ),
            Command(
                name = "alarm",
                title = "设闹钟",
                description = "让 Agent 能给你设提醒。会写进你的闹钟列表，随时可删。",
                defaultAllow = true,
                example = """alarm "09:30" "起床"""",
            ),
            Command(
                name = "list",
                title = "查看可用指令",
                description = "Agent 用它确认自己有哪些能力。始终允许。",
                defaultAllow = true,
                example = "list",
            ),
        )

        /** 不需要经过白名单的元指令。 */
        private val ALWAYS_ALLOWED = setOf("list")
    }

    private val prefs = context.getSharedPreferences("phoneagent_phone", Context.MODE_PRIVATE)

    fun levelOf(command: String): Level {
        if (command in ALWAYS_ALLOWED) return Level.ALLOW
        val spec = COMMANDS.firstOrNull { it.name == command } ?: return Level.DENY
        val stored = prefs.getString(command, null)
        return when (stored) {
            "allow" -> Level.ALLOW
            "deny" -> Level.DENY
            else -> if (spec.defaultAllow) Level.ALLOW else Level.DENY
        }
    }

    fun isAllowed(command: String): Boolean = levelOf(command) == Level.ALLOW

    fun setLevel(command: String, level: Level) {
        prefs.edit().putString(command, if (level == Level.ALLOW) "allow" else "deny").apply()
    }

    fun allowedCommands(): List<String> = COMMANDS.filter { isAllowed(it.name) }.map { it.name }

    fun unknownCommandMessage(command: String): String =
        "没有名为 `$command` 的指令。用 `phone list` 看有哪些可用。"

    fun deniedMessage(command: String): String =
        "`$command` 不在用户允许的指令里。可用的是：${allowedCommands().joinToString(", ")}。" +
            "如果确实需要它，请让用户在 App 的「指令」页里打开。不要反复重试。"
}
