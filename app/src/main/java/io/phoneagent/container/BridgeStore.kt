package io.phoneagent.container

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 与用户之间的文件通道。
 *
 * ## 为什么必须用 SAF
 *
 * 实测（targetSdk 36、只有 INTERNET 权限）：`ls /sdcard/` 直接 `Permission denied`。
 * 分区存储下 App 够不着公共目录的真实路径。要访问用户可选的目录，只有 SAF 一条路，
 * 而 SAF 给的是 `content://` URI —— proot 要 bind mount 必须用真实路径。
 * 所以中间必须有这一层「镜像」。
 *
 * ## 目录结构
 *
 * ```
 * 用户授权的 SAF 目录              App 私有目录               容器内
 * ├── in/    ─── 同步 ───→   files/bridge/in/   ── bind ──→  /bridge/in
 * └── out/   ←── 同步 ────   files/bridge/out/  ── bind ──→  /bridge/out
 * ```
 *
 * ## 为什么是两个单向目录而不是一个
 *
 * 这样权限也是单向的：Agent 对 in 只读、对 out 只写，改不了用户放进去的东西。
 * 而且单向同步没有双向冲突处理的问题 —— 这不是取舍，是更简单也更安全。
 *
 * 注意 `in` 的「只读」是**约定而非强制**（proot 的 bind 不支持只读，且容器与
 * 应用同属一个 uid）。真正的保障是：**用户手机上的 SAF 目录才是源头**，
 * Agent 改的只是镜像副本，原件不受影响。
 */
class BridgeStore(
    private val context: Context,
    private val paths: Paths,
) {

    companion object {
        private const val PREFS = "phoneagent_bridge"
        private const val KEY_TREE_URI = "tree_uri"

        const val IN_DIR = "in"
        const val OUT_DIR = "out"
    }

    data class Entry(val name: String, val size: Long, val lastModified: Long)

    sealed interface SyncResult {
        /** 无变化或成功。pulled/pushed 是本次实际搬运的文件数。 */
        data class Ok(val pulled: Int, val pushed: Int) : SyncResult

        /** 用户还没授权目录。 */
        data object NotAuthorized : SyncResult

        data class Failed(val message: String) : SyncResult
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── 授权 ───────────────────────────────────────────────────────────────

    fun treeUri(): Uri? = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)

    fun isAuthorized(): Boolean = treeUri() != null

    /** 记住用户在系统选择器里选的目录，拿到长期访问权。 */
    fun persistTree(uri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
        true
    } catch (_: Exception) {
        false
    }

    fun clearTree() {
        // 光删记录不够：`takePersistableUriPermission` 拿到的是一份**系统级**的长期
        // 授权，不还回去的话，我们不再使用的那个目录会一直被系统认为「已授权给本应用」。
        treeUri()?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        prefs.edit().remove(KEY_TREE_URI).apply()
    }

    /** 调试用：把授权也写进日志区（状态栏那条）。 */
    fun describeTree(): String = treeUri()?.toString() ?: "（未授权）"

    // ── 同步 ───────────────────────────────────────────────────────────────

    fun refresh(): SyncResult {
        val uri = treeUri() ?: return SyncResult.NotAuthorized
        val tree = DocumentFile.fromTreeUri(context, uri) ?: return SyncResult.NotAuthorized
        if (!tree.canRead()) return SyncResult.NotAuthorized

        return try {
            val safIn = tree.findFile(IN_DIR) ?: tree.createDirectory(IN_DIR)
            val safOut = tree.findFile(OUT_DIR) ?: tree.createDirectory(OUT_DIR)
            SyncResult.Ok(pullInto(safIn), pushOut(safOut))
        } catch (e: Exception) {
            SyncResult.Failed(e.message ?: e.toString())
        }
    }

    /** SAF 的 in/ → 私有的 in/。以 SAF 为准，那边删了的这边也删。 */
    private fun pullInto(safIn: DocumentFile?): Int {
        if (safIn == null) return 0
        paths.bridgeIn.mkdirs()

        val wanted = mutableSetOf<String>()
        var copied = 0

        for (doc in safIn.listFiles()) {
            val name = doc.name ?: continue
            if (doc.isDirectory) continue
            wanted += name

            val dest = File(paths.bridgeIn, name)
            // 长度相同就当作已同步 —— 比时间戳可靠（SAF 的 lastModified 精度不一致）
            if (dest.isFile && dest.length() == doc.length()) continue

            try {
                context.contentResolver.openInputStream(doc.uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it, 64 * 1024) }
                }
                copied++
            } catch (_: Exception) {
                dest.delete()
            }
        }

        // SAF 那边删掉的，这边也清掉，否则 Agent 会看到已经不存在的文件
        paths.bridgeIn.listFiles()?.forEach { f -> if (f.name !in wanted) f.delete() }

        return copied
    }

    /** 私有的 out/ → SAF 的 out/。 */
    private fun pushOut(safOut: DocumentFile?): Int {
        if (safOut == null) return 0
        var copied = 0

        paths.bridgeOut.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val existing = safOut.findFile(f.name)
            if (existing != null && existing.length() == f.length()) return@forEach

            try {
                existing?.delete()
                var doc = safOut.createFile(mimeFor(f.name), f.name) ?: return@forEach

                // createFile 会按 MIME 类型拼扩展名，可能把名字改掉
                // （比如把 app-debug.apk 变成 app-debug.apk.bin）。押注各家 provider
                // 的行为不如直接改名，保证用户在文件管理器里看到的名字是我们想要的。
                if (doc.name != f.name) {
                    val renamed = DocumentsContract.renameDocument(context.contentResolver, doc.uri, f.name)
                    if (renamed != null) {
                        DocumentFile.fromSingleUri(context, renamed)?.let { doc = it }
                    }
                }

                context.contentResolver.openOutputStream(doc.uri, "w")?.use { out ->
                    f.inputStream().use { it.copyTo(out, 64 * 1024) }
                }
                copied++
            } catch (_: Exception) {
                // 单个文件失败不影响其它的
            }
        }

        return copied
    }

    /** 尽量给对 MIME，避免 createFile 拼出错误的扩展名。 */
    private fun mimeFor(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "apk" -> "application/vnd.android.package-archive"
            "txt", "log", "ini", "conf" -> "text/plain"
            "json" -> "application/json"
            "md" -> "text/markdown"
            "sh", "bash" -> "application/x-sh"
            "js", "mjs" -> "application/javascript"
            "html", "htm" -> "text/html"
            "xml" -> "text/xml"
            "csv" -> "text/csv"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "gz", "tgz" -> "application/gzip"
            "tar" -> "application/x-tar"
            "xz" -> "application/x-xz"
            else -> "application/octet-stream"
        }

    // ── 列表 ───────────────────────────────────────────────────────────────

    fun listIn(): List<Entry> = listDir(paths.bridgeIn)

    fun listOut(): List<Entry> = listDir(paths.bridgeOut)

    private fun listDir(dir: File): List<Entry> =
        dir.listFiles()
            ?.filter { it.isFile }
            ?.map { Entry(it.name, it.length(), it.lastModified()) }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()

    /** 清空某个方向（用户主动操作）。 */
    fun clearIn() {
        paths.bridgeIn.listFiles()?.forEach { it.delete() }
    }

    fun clearOut() {
        paths.bridgeOut.listFiles()?.forEach { it.delete() }
    }
}
