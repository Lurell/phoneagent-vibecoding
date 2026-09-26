package io.phoneagent.container

import org.json.JSONObject
import java.io.File

/**
 * 读写容器里的 `~/.claude/settings.json`（即 Claude Code 的用户级配置）。
 *
 * ## 为什么要单独一个类
 *
 * 这个文件**不完全归我们管** —— 用户可能已经放过自己的配置（自定义端点、
 * 模型名、权限策略）。所以写入时**只合并 `env` 那一段，其它字段一律不动**，
 * 而且解析失败时宁可不写，也不要覆盖掉用户原有的内容。
 *
 * ## 凭据有两种写法，选错就连不上
 *
 * | 变量 | 发出去的头 | 用在哪 |
 * |---|---|---|
 * | `ANTHROPIC_API_KEY` | `x-api-key` | 官方 API |
 * | `ANTHROPIC_AUTH_TOKEN` | `Authorization: Bearer` | **中转 / 网关** |
 *
 * 这一条是被真实用户踩出来的：应用写了一整轮 `ANTHROPIC_API_KEY`，而用户实际
 * 用的是中转站，需要的恰恰是 `ANTHROPIC_AUTH_TOKEN` —— 表现就是「配置填好了，
 * 但 Claude 连不上」。
 *
 * 而且**两个都设且值不同时，谁生效取决于 Claude Code 的版本**（各方文档对此
 * 说法不一致），官方明确警告不要这么干。所以我们只写一个，并主动清掉另一个。
 */
class ClaudeSettings(private val paths: Paths) {

    companion object {
        const val KEY_API = "ANTHROPIC_API_KEY"
        const val KEY_AUTH_TOKEN = "ANTHROPIC_AUTH_TOKEN"
        const val KEY_BASE_URL = "ANTHROPIC_BASE_URL"
        const val KEY_MODEL = "ANTHROPIC_MODEL"
        const val KEY_EFFORT = "CLAUDE_CODE_EFFORT_LEVEL"

        /** 思考强度档位。`value` 为空 = 不写这个键，用模型自己的默认。 */
        data class EffortLevel(val value: String, val label: String)

        /**
         * 档位从高到低 —— 界面按这个顺序排，最常用的在最前。
         *
         * **默认「最高」**：这个项目面向的是「在手机上跑 Agent」，而手机上的等待
         * 成本远高于算力成本 —— 用户要的是少出错、少来回，不是省钱。想省 token
         * 的在同一个选择器里换低一档即可，代价一眼可见。
         */
        val EFFORT_LEVELS = listOf(
            EffortLevel("max", "最高"),
            EffortLevel("xhigh", "极高"),
            EffortLevel("high", "高"),
            EffortLevel("medium", "中"),
            EffortLevel("low", "低"),
            EffortLevel("", "不设置"),
        )

        const val DEFAULT_EFFORT = "max"

        /**
         * 模型映射要写的五个键。
         *
         * Claude Code 内部**按档位**请求模型：主对话、`/model` 切换时的
         * opus / sonnet / haiku、以及子 Agent。而第三方端点通常只有一个模型名 ——
         * 不把这几个档位都指过去，一启动就报「模型不存在」。
         */
        private val MODEL_KEYS = listOf(
            KEY_MODEL,
            "ANTHROPIC_DEFAULT_OPUS_MODEL",
            "ANTHROPIC_DEFAULT_SONNET_MODEL",
            "ANTHROPIC_DEFAULT_HAIKU_MODEL",
            "CLAUDE_CODE_SUBAGENT_MODEL",
        )

        /**
         * 走第三方端点时默认打开的两个开关。切回官方会自动清掉。
         *
         * - `DISABLE_NONSTREAMING_FALLBACK` —— 网关在**流中断**时回退到非流式重发，
         *   会造成**工具被重复执行**。这条是功能正确性，不是偏好。
         * - `DISABLE_NONESSENTIAL_TRAFFIC` —— 遥测 / 自动更新 / 特性开关全部不再联网。
         *   走第三方端点本来也没必要跟 Anthropic 通信；顺带避免手机流量下突然自动更新。
         *   注意它**存在即生效**（写成 `0` 也照样关），并且会关掉依赖特性开关的功能。
         */
        private val GATEWAY_FLAGS = mapOf(
            "CLAUDE_CODE_DISABLE_NONSTREAMING_FALLBACK" to "1",
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" to "1",
        )

        /**
         * 把用户填的 base URL 规整成 Claude Code 能用的形式。
         *
         * 两种写法都很常见，且都会以「连不上」收场，所以这里直接纠正而不是报错：
         *
         *  - 漏了协议头（`www.xxx.com`）→ 补 `https://`
         *  - 带了 `/v1` 后缀 → 去掉。**Claude Code 自己会拼 `/v1/messages`**，
         *    留着就变成 `/v1/v1/messages`
         *
         * 界面上会把规整后的结果回显出来，所以这不是「偷偷改用户的输入」。
         */
        fun normalizeBaseUrl(raw: String): String {
            var url = raw.trim().trimEnd('/')
            if (url.isEmpty()) return ""
            if (!url.contains("://")) url = "https://$url"
            if (url.endsWith("/v1")) url = url.removeSuffix("/v1").trimEnd('/')
            return url
        }
    }

    /** 已配置的凭据，以及它是哪一种写法。 */
    data class Credential(val value: String, val isAuthToken: Boolean)

    private fun file() = File(paths.home, ".claude/settings.json")

    fun readEnv(): Map<String, String> {
        val f = file()
        if (!f.isFile) return emptyMap()
        return try {
            val root = JSONObject(f.readText())
            val env = root.optJSONObject("env") ?: return emptyMap()
            env.keys().asSequence().associateWith { env.optString(it) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * 读凭据。两种变量都认 —— 用户可能自己手改过，也可能是我们早期版本写下的。
     *
     * 同时存在时优先 AUTH_TOKEN：那说明这个配置是中转用途。
     */
    fun readCredential(): Credential? {
        val env = readEnv()
        env[KEY_AUTH_TOKEN]?.takeIf { it.isNotEmpty() }?.let { return Credential(it, true) }
        env[KEY_API]?.takeIf { it.isNotEmpty() }?.let { return Credential(it, false) }
        return null
    }

    fun isConfigured(): Boolean = readCredential() != null

    /**
     * 只改 `env` 里这一组「接入配置」的键，**保留文件里其它所有内容**
     * （用户可能自己写了模型、权限、hooks 等等）。
     *
     * 这里管的键是固定的一组：两个凭据变量、`ANTHROPIC_BASE_URL`、
     * 五个模型映射键、两个网关开关。**管理的含义就是「保存时这一组会被设成
     * 它应该有的样子」** —— 比如从第三方切回官方，模型映射和网关开关会被清掉，
     * 而不是留在文件里继续生效。
     *
     * @param isAuthToken true 写 `ANTHROPIC_AUTH_TOKEN`（中转），false 写 `ANTHROPIC_API_KEY`（官方）
     * @param model 模型名。留空则**不写**模型映射（官方端点不需要）
     * @param effort 思考强度（[EFFORT_LEVELS] 里的 value）。留空则**不写**，用模型自己的默认
     * @return null 表示成功，否则是失败原因
     */
    fun write(
        credential: String,
        baseUrl: String,
        isAuthToken: Boolean,
        model: String = "",
        effort: String = DEFAULT_EFFORT,
    ): String? {
        val f = file()
        f.parentFile?.mkdirs()

        val root = try {
            if (f.isFile) JSONObject(f.readText()) else JSONObject()
        } catch (e: Exception) {
            // 解析不了就**不写** —— 覆盖掉用户可能手写的配置，比写不进去更糟。
            return "现有的 settings.json 解析失败（${e.message}），为免覆盖你的内容，没有改动它"
        }

        return try {
            val env = root.optJSONObject("env") ?: JSONObject().also { root.put("env", it) }

            val chosen = if (isAuthToken) KEY_AUTH_TOKEN else KEY_API
            val other = if (isAuthToken) KEY_API else KEY_AUTH_TOKEN

            if (credential.isNotEmpty()) env.put(chosen, credential) else env.remove(chosen)
            // 另一个必须清掉：两个都设且值不同时谁生效取决于版本，不能赌。
            // 这一行也顺手修好了「用户从官方切到中转（或反过来）」的场景。
            env.remove(other)

            val url = normalizeBaseUrl(baseUrl)
            if (url.isNotEmpty()) env.put(KEY_BASE_URL, url) else env.remove(KEY_BASE_URL)

            val m = model.trim()
            MODEL_KEYS.forEach { key -> if (m.isEmpty()) env.remove(key) else env.put(key, m) }

            // 只有「走第三方端点」才打开这两个开关
            val gateway = url.isNotEmpty() && isAuthToken
            GATEWAY_FLAGS.forEach { (key, value) ->
                if (gateway) env.put(key, value) else env.remove(key)
            }

            if (effort.isNotEmpty()) env.put(KEY_EFFORT, effort) else env.remove(KEY_EFFORT)

            // `org.json` 会把 `/` 转义成 `\/`。那是**合法** JSON，但 base URL 写出来
            // 就成了 `https:\/\/api.xxx.com` —— 用户打开文件一看就会觉得「格式坏了」，
            // 而这个文件恰恰是要给人看、给人手改的。
            //
            // 直接换掉不改变含义：JSON 里 `\/` 与 `/` 完全等价。也不必担心误伤字面
            // 反斜杠 —— 字面反斜杠在输出里是 `\\`，所以输出中出现的 `\/` 只可能来自
            // 这次转义。
            f.writeText(root.toString(2).replace("\\/", "/") + "\n")
            null
        } catch (e: Exception) {
            e.message ?: "写入失败"
        }
    }
}
