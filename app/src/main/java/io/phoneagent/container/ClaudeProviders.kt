package io.phoneagent.container

/**
 * 第三方模型接入的预设。
 *
 * ## 为什么需要这个
 *
 * 接一个第三方端点远不止「填地址 + 钥匙」。Claude Code 内部是**按档位**请求模型的
 * —— 主对话、`/model` 切换时的 opus / sonnet / haiku、以及子 Agent —— 而第三方
 * 端点往往只有一个模型名。不把这些档位都映射过去，一启动就报模型不存在。
 *
 * 一份能用的配置长这样（都在 `env` 里）：
 *
 * ```
 * ANTHROPIC_BASE_URL / ANTHROPIC_AUTH_TOKEN
 * ANTHROPIC_MODEL
 * ANTHROPIC_DEFAULT_OPUS_MODEL / _SONNET_ / _HAIKU_
 * CLAUDE_CODE_SUBAGENT_MODEL
 * ```
 *
 * ## 哪些是钉死的，哪些只是预填
 *
 * **地址**相对稳定，写进代码。**模型名变化频繁** —— 同一个厂商半年内换好几代，
 * 而且同一个名字在不同套餐/中转站下也可能不同。所以模型名只作为**预填值**：
 * 界面上随时可改，改了就覆盖，留空则完全不写模型映射。
 *
 * 换句话说，下面这些 `modelHint` 是「上次见到的样子」，不是「默认值」。
 * 填错的后果是启动时报「模型不存在」，属于一眼能看出、也一眼能改的错。
 */
object ClaudeProviders {

    const val OFFICIAL = "official"
    const val CUSTOM = "custom"

    data class Provider(
        val key: String,
        val label: String,
        /** 空 = 官方端点，不写 `ANTHROPIC_BASE_URL`。 */
        val baseUrl: String,
        /** true = 写 `ANTHROPIC_AUTH_TOKEN`（中转），false = `ANTHROPIC_API_KEY`（官方）。 */
        val authToken: Boolean,
        /** 模型名预填值（可变，见类注释）。空 = 不预填。 */
        val modelHint: String,
    )

    /**
     * 厂商列表。顺序即界面顺序，[OFFICIAL] 放第一个 —— 没配过的人多半是官方。
     *
     * 各家端点都来自各自的官方文档（或实测可用的配置），但**厂商会变**：
     * 智谱国内端点目前只对 Coding Plan 订阅开放，按量付费的 key 会拿到 429。
     * 遇到这类情况应查厂商最新的「Claude Code 接入」文档，而不是改这里硬试。
     */
    val ALL = listOf(
        Provider(OFFICIAL, "官方", "", authToken = false, modelHint = ""),
        Provider(
            "deepseek", "DeepSeek",
            "https://api.deepseek.com/anthropic",
            authToken = true,
            modelHint = "deepseek-flash[1m]",
        ),
        Provider(
            "kimi", "Kimi",
            "https://api.moonshot.ai/anthropic",
            authToken = true,
            modelHint = "kimi-k2.5",
        ),
        Provider(
            "zhipu", "智谱 GLM",
            "https://open.bigmodel.cn/api/anthropic",
            authToken = true,
            modelHint = "glm-4.6",
        ),
        Provider(CUSTOM, "自定义", "", authToken = true, modelHint = ""),
    )

    fun byKey(key: String): Provider? = ALL.firstOrNull { it.key == key }

    /**
     * 从已有配置反推是哪个厂商。
     *
     * 打开对话框时要选中对应的芯片 —— 否则用户看到的是「官方」高亮，而文件里
     * 明明配着 DeepSeek，会以为配置丢了。
     */
    fun match(baseUrl: String): String {
        val normalized = ClaudeSettings.normalizeBaseUrl(baseUrl)
        if (normalized.isEmpty()) return OFFICIAL
        return ALL.firstOrNull {
            it.baseUrl.isNotEmpty() && ClaudeSettings.normalizeBaseUrl(it.baseUrl) == normalized
        }?.key ?: CUSTOM
    }
}
