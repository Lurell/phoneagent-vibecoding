package io.phoneagent.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.phoneagent.container.BridgeStore
import io.phoneagent.container.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BridgeUiState(
    val authorized: Boolean = false,
    val treeUri: String = "",
    val inFiles: List<BridgeStore.Entry> = emptyList(),
    val outFiles: List<BridgeStore.Entry> = emptyList(),
    val busy: Boolean = false,
    val status: String = "未授权目录",
)

/**
 * 文件通道页。
 *
 * 这一页不是「可选的便利功能」—— SAF 的所有操作都必须由 App 主动发起
 * （用户没法在系统文件管理器里「授权某个目录给某个 App」），所以这一页是
 * 目录授权方案的**必需组成部分**。既然必须有，把文件列出来几乎是免费的，
 * 而它带来的可见性正是「用户愿意给权限」的前提。
 */
class BridgeViewModel(app: Application) : AndroidViewModel(app) {

    private val paths = Paths(app)
    private val store = BridgeStore(app, paths)

    private val _state = MutableStateFlow(BridgeUiState())
    val state: StateFlow<BridgeUiState> = _state.asStateFlow()

    init {
        paths.ensureDirs()
        val authorized = store.isAuthorized()
        _state.update {
            it.copy(
                authorized = authorized,
                treeUri = store.describeTree(),
                status = if (authorized) "已授权，可同步" else "未授权目录",
            )
        }
        if (authorized) sync()
    }

    /** 用户在系统选择器里选完目录。 */
    fun authorize(uri: Uri) {
        val ok = store.persistTree(uri)
        _state.update {
            it.copy(
                authorized = ok,
                treeUri = store.describeTree(),
                status = if (ok) "授权成功，正在同步…" else "授权失败",
            )
        }
        if (ok) sync()
    }

    fun revoke() {
        store.clearTree()
        _state.update {
            it.copy(authorized = false, treeUri = "（未授权）", status = "已撤销授权")
        }
    }

    fun sync() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, status = "同步中…") }
            val result = withContext(Dispatchers.IO) { store.refresh() }
            val status = when (result) {
                is BridgeStore.SyncResult.Ok ->
                    if (result.pulled == 0 && result.pushed == 0) "已是最新"
                    else "同步完成：取回 ${result.pulled} 个，送出 ${result.pushed} 个"
                BridgeStore.SyncResult.NotAuthorized -> "未授权目录"
                is BridgeStore.SyncResult.Failed -> "同步失败：${result.message}"
            }
            refreshLists()
            _state.update { it.copy(busy = false, status = status) }
        }
    }

    fun clearOut() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                store.clearOut()
                store.refresh()
            }
            refreshLists()
            _state.update { it.copy(status = "已清空产出目录") }
        }
    }

    private fun refreshLists() {
        _state.update {
            it.copy(
                inFiles = store.listIn(),
                outFiles = store.listOut(),
            )
        }
    }
}
