package io.phoneagent.container

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 下载与从 zip 里取文件。
 *
 * 两件事都**自己负责线程切换**，不指望调用方记得 —— 这条教训来自
 * `AgentInstaller`：那里 `download()` 是普通函数没切线程，调用方又落在主线程，
 * 直接抛了 `NetworkOnMainThreadException`。把切换放在做 I/O 的地方就不会再犯。
 *
 * ## 为什么必须支持断点续传
 *
 * JDK 那个包有 196MB，在手机网络上下载中途被切断是常态
 * （实测报过 `unexpected end of stream`）。不支持续传的话，一次中断就要从零重来，
 * 而重来的过程中大概率又断一次 —— 大文件永远下不完。
 *
 * 所以这里用 HTTP `Range` 请求续传：失败后保留已下载的部分，下次从断点接着下。
 */
object Downloader {

    private const val MAX_ATTEMPTS = 4
    private const val CONNECT_TIMEOUT = 20_000

    /**
     * 读超时设得短（30 秒）是**故意的**：它只在下游彻底没数据时才触发，
     * 而触发后我们会带着断点重试。设得长反而会让真正的死连接卡很久。
     */
    private const val READ_TIMEOUT = 30_000

    /**
     * 依次尝试多个 URL，每个 URL 最多重试 [MAX_ATTEMPTS] 轮，成功即返回。
     *
     * @param onPercent 进度百分比（0-100）。服务器没给 Content-Length 时传 -1。
     */
    suspend fun fetch(
        urls: List<String>,
        dest: File,
        onPercent: ((Int) -> Unit)? = null,
    ): Unit = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        var lastError: Exception? = null

        repeat(MAX_ATTEMPTS) { attempt ->
            for (url in urls) {
                try {
                    downloadOnce(url, dest, onPercent)
                    return@withContext
                } catch (e: Exception) {
                    lastError = e
                    // 保留已下载的部分，下一轮从断点继续
                }
            }
        }
        throw lastError ?: IllegalStateException("所有下载源都失败了")
    }

    private fun downloadOnce(url: String, dest: File, onPercent: ((Int) -> Unit)?) {
        var existing = if (dest.isFile) dest.length() else 0L

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = true
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        conn.connect()

        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IllegalStateException("HTTP $code")
        }

        // 服务器没理会 Range（回了 200 而不是 206）→ 只能从头下
        if (existing > 0 && code != HttpURLConnection.HTTP_PARTIAL) {
            dest.delete()
            existing = 0L
        }

        val append = existing > 0 && code == HttpURLConnection.HTTP_PARTIAL
        val contentLength = conn.contentLengthLong
        // 206 的 Content-Length 是「剩余字节数」，总量要把已下的加上
        val total = when {
            contentLength <= 0 -> -1L
            append -> existing + contentLength
            else -> contentLength
        }

        // 续传的**前提**是「同一个文件」。总量和上次记的对不上，说明换了源 ——
        // 把 A 站的前 40MB 拼上 B 站的后 160MB，大小会对得上、「下载成功」，
        // 直到解压才炸，而且下次还会重来一遍。所以宁可丢掉重下。
        val expected = totalMarker(dest).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
        if (append && expected != null && total > 0 && total != expected) {
            conn.disconnect()
            dest.delete()
            totalMarker(dest).delete()
            throw IllegalStateException("断点属于另一个版本的文件（$expected → $total），已丢弃重下")
        }

        var done = existing
        try {
            conn.inputStream.use { input ->
                FileOutputStream(dest, append).use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onPercent?.invoke(if (total > 0) (done * 100 / total).toInt() else -1)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        // 完整性自检：流提前结束（服务端切断）时这里会抛，让上层带着断点重试。
        // 不能只看「读到了 EOF」—— 被切断时同样是 EOF，区别只在总量对不对。
        if (total > 0 && done != total) {
            throw IllegalStateException("下载不完整：$done / $total 字节")
        }

        // 记下这个文件「应该有多大」，下次续传时用它确认对端还是同一个文件。
        if (total > 0) totalMarker(dest).writeText(total.toString())
    }

    /**
     * 断点所属文件的身份标记（`.total` 侧文件）。
     *
     * [fetch] 的多个 URL 是「同一个文件的多个镜像」才对，可这一点**没有任何机制
     * 保证** —— 而违反它的后果是**静默**的：大小对得上、下载报成功、解压才炸。
     * 所以把总量记在文件旁边，续传前核对一次。
     */
    private fun totalMarker(dest: File) = File(dest.parentFile, dest.name + ".total")

    /**
     * 从 zip 里取出第一个「路径以 suffix 结尾」的条目，写到 dest。
     *
     * 为什么在 App 侧做：容器里**没有 unzip**（有 tar/xz/dpkg/ar，就是没有它）。
     * 而 Google 的包是 zip，我们每次只要里面一两个文件 —— 为此去装个 unzip 不划算。
     */
    suspend fun extractFromZip(zip: File, suffix: String, dest: File): Boolean =
        withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(suffix)) {
                        dest.outputStream().use { out -> zis.copyTo(out, 128 * 1024) }
                        return@withContext true
                    }
                    entry = zis.nextEntry
                }
            }
            false
        }
}
