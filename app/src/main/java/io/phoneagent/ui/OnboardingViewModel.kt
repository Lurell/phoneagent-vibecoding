package io.phoneagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.phoneagent.container.AgentEnvDoc
import io.phoneagent.container.AgentInstaller
import io.phoneagent.container.AssetInstaller
import io.phoneagent.container.BridgeStore
import io.phoneagent.container.ClaudeProviders
import io.phoneagent.container.ClaudeSettings
import io.phoneagent.container.Paths
import io.phoneagent.container.ProotCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 引导清单里的一项。 */
data class SetupStep(
    val key: String,
    val title: String,
    val detail: String,
    val done: Boolean,
    /** 必需项全做完才允许进入主界面；建议项可以跳过。 */
    val required: Boolean,
    val actionLabel: String,
    /**
     * 已完成时的按钮文字。**null = 完成后不再给按钮**。
     *
     * 「已完成」不等于「不该再改」：API Key 可能填错、目录可能想换一个。
     * 原来这一栏一律不显示按钮，于是**填过一次就再也改不了**。
     *
     * 只给真正需要「改」的两项配它。装 rootfs / 装 Agent 是幂等操作，
     * 没有可改的参数，而给它们一个显眼的「重装」按钮反而是危险入口。
     */
    val doneActionLabel: String? = null,
)

data class OnboardingUiState(
    val steps: List<SetupStep> = emptyList(),
    val busy: Boolean = false,
    val busyLabel: String = "",
    val message: String = "",
    /** API key 输入对话框是否打开。 */
    val editingApiKey: Boolean = false,
    val apiKeyDraft: String = "",
    val baseUrlDraft: String = "",
    /**
     * 凭据写成哪种变量。
     *
     * true = `ANTHROPIC_AUTH_TOKEN`（`Authorization: Bearer`，中转/网关用）
     * false = `ANTHROPIC_API_KEY`（`x-api-key`，官方用）
     */
    val authTokenMode: Boolean = false,
    /** 选中的厂商预设（[ClaudeProviders] 的 key）。 */
    val providerKey: String = ClaudeProviders.OFFICIAL,
    /** 模型名。留空 = 不写模型映射。 */
    val modelDraft: String = "",
    /** 思考强度（[ClaudeSettings.EFFORT_LEVELS] 的 value）。默认「最高」。 */
    val effortDraft: String = ClaudeSettings.DEFAULT_EFFORT,
) {
    /** 必需项都完成了才能「开始使用」。 */
    val canStart: Boolean get() = steps.filter { it.required }.all { it.done }
    val remainingRequired: Int get() = steps.count { it.required && !it.done }
}

/**
 * 引导清单。
 *
 * 取代了原来散在四个标签页里的初始化流程 —— 新用户不必再去「把每个标签点一遍、
 * 自己拼出正确顺序」，而是照着一张清单从上往下做。
 *
 * 这也是 API key 第一个有界面的地方：之前只能手动把 `settings.json` 放进容器，
 * **别人装完会卡在这一步 —— 他知道要填 key，却没地方填。**
 */
class OnboardingViewModel(app: Application) : AndroidViewModel(app) {

    private val paths = Paths(app)
    private val command = ProotCommand(paths)
    private val assets = AssetInstaller(app, paths)
    private val agentInstaller = AgentInstaller(app, paths, command)
    private val bridge = BridgeStore(app, paths)
    private val claudeSettings = ClaudeSettings(paths)

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val rootfs = withContext(Dispatchers.IO) { assets.isInstalled() }
            val agent = withContext(Dispatchers.IO) { hasClaude() }
            val credential = withContext(Dispatchers.IO) { claudeSettings.readCredential() }
            val model = withContext(Dispatchers.IO) {
                claudeSettings.readEnv()[ClaudeSettings.KEY_MODEL].orEmpty()
            }
            val bridged = bridge.isAuthorized()

            _state.update { s ->
                s.copy(
                    steps = listOf(
                        SetupStep(
                            key = "rootfs",
                            title = "容器环境",
                            detail = if (rootfs) "Ubuntu 24.04 已解压" else "解压 Linux 根文件系统（约 1 分钟）",
                            done = rootfs,
                            required = true,
                            actionLabel = "安装",
                        ),
                        SetupStep(
                            key = "agent",
                            title = "Agent 运行环境",
                            detail = if (agent) "Node.js 与 Claude Code 已就绪" else "装 Node.js 22 与 Claude Code（约 3 分钟）",
                            done = agent,
                            required = true,
                            actionLabel = "准备",
                        ),
                        SetupStep(
                            key = "apikey",
                            title = "API Key",
                            detail = if (credential == null) "填进容器，Claude 才能连上模型"
                            else "${if (credential.isAuthToken) "中转令牌" else "官方 Key"} " +
                                maskKey(credential.value) + if (model.isEmpty()) "" else " · $model",
                            done = credential != null,
                            required = false,
                            actionLabel = "填写",
                            doneActionLabel = "修改",
                        ),
                        SetupStep(
                            key = "bridge",
                            title = "文件通道",
                            detail = if (bridged) bridge.describeTree() else "选一个目录，作为你和 Agent 交换文件的地方",
                            done = bridged,
                            required = false,
                            actionLabel = "选择目录",
                            doneActionLabel = "重选",
                        ),
                    )
                )
            }
        }
    }

    /**
     * 只露头尾。
     *
     * 显示「已配置」而不显示是哪一个，在填错 key 的时候帮不上任何忙 ——
     * 你没法判断里面那个到底是不是你想换掉的那个。露头尾是通行做法
     * （Stripe、AWS 都这么显示），既够认出是哪把，又不把整串密钥摊在屏幕上。
     */
    private fun maskKey(k: String): String =
        if (k.length <= 12) "（已配置）" else "${k.take(7)}…${k.takeLast(4)}"

    /** 容器里有没有 claude。 */
    private fun hasClaude(): Boolean =
        File(paths.rootfs, "usr/local/bin/claude").exists() ||
            File(paths.rootfs, "usr/local/lib/node_modules/@anthropic-ai/claude-code").isDirectory

    // ── 各步骤的动作 ───────────────────────────────────────────────────────

    fun installRootfs() = runStep("解压容器环境") {
        if (assets.isInstalled()) {
            appendMessage("容器环境已安装")
            return@runStep
        }
        assets.install { p ->
            _state.update { it.copy(busyLabel = p.phase) }
        }
    }

    fun installAgent() = runStep("准备 Agent 环境") {
        if (!assets.isInstalled()) {
            appendMessage("请先完成「容器环境」这一步")
            return@runStep
        }
        val result = agentInstaller.install { p ->
            _state.update { s -> s.copy(busyLabel = if (p.detail.isEmpty()) p.phase else "${p.phase}：${p.detail}") }
        }
        appendMessage(
            when (result) {
                is AgentInstaller.Result.AlreadyReady -> "Agent 环境已就绪：${result.claude}"
                is AgentInstaller.Result.Installed -> "Agent 环境准备完成：Node ${result.node}"
                is AgentInstaller.Result.Failed -> "失败于「${result.step}」：${result.message}"
            }
        )
        // 顺带把环境说明部署进去，Agent 才知道 /bridge 与 phone 的存在
        withContext(Dispatchers.IO) { AgentEnvDoc(getApplication(), paths).ensureUpToDate() }
    }

    fun openApiKeyEditor() {
        val env = claudeSettings.readEnv()
        val credential = claudeSettings.readCredential()
        val baseUrl = env[ClaudeSettings.KEY_BASE_URL].orEmpty()
        _state.update {
            it.copy(
                editingApiKey = true,
                apiKeyDraft = credential?.value.orEmpty(),
                baseUrlDraft = baseUrl,
                // 没配过就按 base URL 猜：填了中转地址的，凭据本来就该走 Authorization
                authTokenMode = credential?.isAuthToken ?: baseUrl.isNotEmpty(),
                modelDraft = env[ClaudeSettings.KEY_MODEL].orEmpty(),
                // 文件里没写就用默认档 —— 默认是「最高」，会随保存写进去
                effortDraft = env[ClaudeSettings.KEY_EFFORT] ?: ClaudeSettings.DEFAULT_EFFORT,
                // 从已有地址反推厂商，否则用户会看到「官方」高亮而文件里配着 DeepSeek
                providerKey = ClaudeProviders.match(baseUrl),
                message = "",
            )
        }
    }

    /**
     * 选厂商预设：地址、凭据类型、模型预填值一次性填好。
     *
     * 重复点已经选中的那个不做任何事 —— 否则用户改完模型名再点一下同一个芯片，
     * 改动就被预设值抹掉了。
     */
    fun selectProvider(key: String) {
        val p = ClaudeProviders.byKey(key) ?: return
        _state.update {
            if (it.providerKey == key) it
            else it.copy(
                providerKey = key,
                baseUrlDraft = p.baseUrl,
                authTokenMode = p.authToken,
                modelDraft = p.modelHint,
            )
        }
    }

    fun closeApiKeyEditor() = _state.update { it.copy(editingApiKey = false) }

    fun onApiKeyChange(v: String) = _state.update { it.copy(apiKeyDraft = v) }
    fun onBaseUrlChange(v: String) = _state.update { it.copy(baseUrlDraft = v) }
    fun onAuthTokenModeChange(v: Boolean) = _state.update { it.copy(authTokenMode = v) }
    fun onModelChange(v: String) = _state.update { it.copy(modelDraft = v) }
    fun onEffortChange(v: String) = _state.update { it.copy(effortDraft = v) }

    /** 用户在系统选择器里选好目录。 */
    fun authorizeBridge(uri: android.net.Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { bridge.persistTree(uri) }
            _state.update {
                it.copy(message = if (ok) "文件通道已选定" else "授权失败，请重试")
            }
            refresh()
        }
    }

    fun saveApiKey() {
        val s = _state.value
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) {
                claudeSettings.write(
                    s.apiKeyDraft.trim(),
                    s.baseUrlDraft.trim(),
                    s.authTokenMode,
                    s.modelDraft.trim(),
                    s.effortDraft,
                )
            }
            _state.update {
                it.copy(editingApiKey = false, message = err ?: "API Key 已保存")
            }
            refresh()
        }
    }

    fun clearMessage() = _state.update { it.copy(message = "") }

    // ── 内部 ───────────────────────────────────────────────────────────────

    private fun runStep(label: String, block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, busyLabel = label, message = "") }
            try {
                block()
            } catch (e: Exception) {
                appendMessage("$label 失败：${e.javaClass.simpleName}: ${e.message ?: ""}")
            } finally {
                _state.update { it.copy(busy = false, busyLabel = "") }
                refresh()
            }
        }
    }

    private fun appendMessage(msg: String) = _state.update { it.copy(message = msg) }
}
