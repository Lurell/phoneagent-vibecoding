package io.phoneagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.phoneagent.container.AgentEnvDoc
import io.phoneagent.container.AndroidToolchain
import io.phoneagent.container.EnvProbe
import io.phoneagent.container.Paths
import io.phoneagent.container.PhonePolicy
import io.phoneagent.container.ProotCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class CommandState(
    val name: String,
    val title: String,
    val description: String,
    val allowed: Boolean,
    /** 元指令（如 list）没有开关，始终允许。 */
    val toggleable: Boolean,
)

data class SettingsUiState(
    val commands: List<CommandState> = emptyList(),
    val rootfsInstalled: Boolean = false,
    val rootfsVersion: String = "",
    val rootfsSize: String = "",
    val computingSize: Boolean = false,
    /** 有耗时操作在进行（自检等）。 */
    val busy: Boolean = false,
    val toolchainInstalled: Boolean = false,
    val toolchainBusy: Boolean = false,
    val toolchainPhase: String = "",
    val toolchainPercent: Int = -1,
    /** 环境自检结果（从删掉的命令页搬过来）。 */
    val probe: List<EnvProbe.Item> = emptyList(),
    val probeVisible: Boolean = false,
    val message: String = "",
)

/**
 * 设置页。
 *
 * 这一页存在的理由是：**一个无法配置的安全机制等于没有安全机制。**
 * 白名单、授权这些边界如果只存在于代码里的默认值中，那它只是「作者认为安全」，
 * 而不是「用户能控制的安全」。
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val paths = Paths(app)
    private val policy = PhonePolicy(app)
    private val toolchain = AndroidToolchain(app, paths, ProotCommand(paths))
    private val probe = EnvProbe(app, paths)

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        refresh()
        computeRootfsInfo()
    }

    private fun refresh() {
        _state.update {
            it.copy(
                commands = PhonePolicy.COMMANDS.map { c ->
                    CommandState(
                        name = c.name,
                        title = c.title,
                        description = c.description,
                        allowed = policy.isAllowed(c.name),
                        toggleable = c.name !in ALWAYS_ALLOWED,
                    )
                },
                toolchainInstalled = toolchain.isInstalled(),
            )
        }
    }

    // ── Android 构建工具（可选，约 500MB）────────────────────────────────────

    fun installToolchain() {
        if (_state.value.toolchainBusy) return
        if (!_state.value.rootfsInstalled) {
            _state.update { it.copy(message = "请先在引导清单里完成「容器环境」这一步。") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(toolchainBusy = true, toolchainPhase = "准备中", toolchainPercent = -1) }
            val result = toolchain.install { p ->
                _state.update { s ->
                    s.copy(toolchainPhase = if (p.detail.isEmpty()) p.phase else "${p.phase}：${p.detail}",
                        toolchainPercent = p.percent)
                }
            }
            val msg = when (result) {
                AndroidToolchain.Result.AlreadyInstalled -> "构建工具已安装"
                is AndroidToolchain.Result.Installed -> "Android 构建工具就绪。现在可以让 Agent 编 APK 了。"
                is AndroidToolchain.Result.Failed -> "失败于「${result.step}」：${result.message}"
            }
            if (result !is AndroidToolchain.Result.Failed) redeployEnvDoc()
            refresh()
            _state.update { it.copy(toolchainBusy = false, toolchainPhase = "", toolchainPercent = -1, message = msg) }
        }
    }

    fun uninstallToolchain() {
        viewModelScope.launch {
            _state.update { it.copy(toolchainBusy = true, toolchainPhase = "正在删除…") }
            toolchain.uninstall()
            redeployEnvDoc()
            refresh()
            _state.update {
                it.copy(toolchainBusy = false, toolchainPhase = "", message = "已删除构建工具，回收约 500MB")
            }
        }
    }

    /**
     * 重写容器的环境说明。
     *
     * 这份文档里有一段是**按「构建工具装没装」二选一**的，所以装完/卸完必须立刻重写 ——
     * 否则文档要等到下次重启容器才更新，而这中间 Agent 可能已经在跑了：
     * 明明工具已经装好，它却仍在告诉用户「编不了 APK」。
     */
    private suspend fun redeployEnvDoc() {
        withContext(Dispatchers.IO) { AgentEnvDoc(getApplication(), paths).ensureUpToDate() }
    }

    fun setAllowed(name: String, allowed: Boolean) {
        policy.setLevel(name, if (allowed) PhonePolicy.Level.ALLOW else PhonePolicy.Level.DENY)
        refresh()
    }

    /** 强制重新部署 Agent 的环境说明（用户如果把它改坏了，可以靠这个恢复）。 */
    fun resetEnvDoc() {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                // 删掉文件和 stamp，让 ensureUpToDate 走「全新部署」那条路
                runCatching {
                    java.io.File(paths.home, ".claude/CLAUDE.md").delete()
                    java.io.File(paths.home, ".claude/.phoneagent-env-hash").delete()
                }
                AgentEnvDoc(getApplication(), paths).ensureUpToDate()
            }
            _state.update {
                it.copy(message = if (ok) "已重新部署运行环境说明（下次启动容器时生效）" else "重新部署失败")
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = "") }

    /**
     * 环境自检。真机上撞到「容器起不来」时，这几行能直接区分
     * 「被 SELinux 拦了」和「二进制依赖没配好」。
     */
    fun runProbe() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = "自检中…") }
            val items = withContext(Dispatchers.IO) { probe.run() }
            _state.update { it.copy(probe = items, probeVisible = true, busy = false, message = "") }
        }
    }

    fun toggleProbe() = _state.update { it.copy(probeVisible = !it.probeVisible) }

    /**
     * 算 rootfs 占用。没有快的办法 —— Android 没有 du，只能遍历。
     * 所以放后台线程，并且只在结果回来时更新一次。
     */
    private fun computeRootfsInfo() {
        viewModelScope.launch {
            _state.update { it.copy(computingSize = true) }

            val info = withContext(Dispatchers.IO) {
                val installed = paths.installMarker.isFile
                val version = File(paths.rootfs, "etc/os-release")
                    .takeIf { it.isFile }
                    ?.readLines()
                    ?.firstOrNull { it.startsWith("PRETTY_NAME=") }
                    ?.substringAfter('=')?.trim('"')
                    ?: ""

                var total = 0L
                if (installed) {
                    runCatching {
                        paths.rootfs.walkTopDown().forEach { if (it.isFile) total += it.length() }
                    }
                }
                Triple(installed, version, total)
            }

            _state.update {
                it.copy(
                    rootfsInstalled = info.first,
                    rootfsVersion = info.second,
                    rootfsSize = humanSize(info.third),
                    computingSize = false,
                )
            }
        }
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / 1024.0 / 1024)
        bytes > 0 -> "%.0f KB".format(bytes / 1024.0)
        else -> "—"
    }

    private companion object {
        val ALWAYS_ALLOWED = setOf("list")
    }
}
