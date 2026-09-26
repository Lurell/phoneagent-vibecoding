package io.phoneagent.container

import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * 纯 Kotlin 的 tar.gz 解压器。
 *
 * 为什么不用外部工具：Android 不带 tar，而调用外部 busybox 解压会形成循环依赖
 * —— 解压本身也需要可执行文件能跑起来。自己解让「解压」与「执行」解耦：
 * 即使某台设备的 SELinux 挡住了 execve，也能明确知道卡在哪一步。
 *
 * 需要处理的格式（Ubuntu base 的 rootfs 里这些全都会出现）：
 *   - ustar 基本头（含 prefix 字段拼长路径）
 *   - GNU 长文件名 'L' 与长链接名 'K'
 *   - PAX 扩展头 'x'（path= / linkpath= / size= 记录）
 *   - 普通文件、目录、符号链接、硬链接
 *   - 八进制与 GNU base-256 两种长度编码
 */
class TarExtractor {

    private companion object {
        const val BLOCK = 512
        const val TYPE_REGULAR = '0'
        const val TYPE_HARDLINK = '1'
        const val TYPE_SYMLINK = '2'
        const val TYPE_DIR = '5'
        const val TYPE_GNU_LONGNAME = 'L'
        const val TYPE_GNU_LONGLINK = 'K'
        const val TYPE_PAX = 'x'
        const val TYPE_PAX_GLOBAL = 'g'
    }

    private class Entry(val header: ByteArray) {
        var name: String = ""
        var linkName: String = ""
        var size: Long = 0
        var mode: Int = 0
        var mtime: Long = 0
        var type: Char = TYPE_REGULAR

        /** PAX / GNU 扩展头覆盖后的路径，优先级高于头部里的字段。 */
        var paxPath: String? = null
        var paxLinkPath: String? = null
    }

    /** 解压结果统计，用于向界面汇报。errors 不为空时说明有部分条目没解出来。 */
    data class Stats(
        var files: Int = 0,
        var dirs: Int = 0,
        var links: Int = 0,
        var bytes: Long = 0,
        val errors: MutableList<String> = mutableListOf(),
    )

    /**
     * @param onProgress 每处理若干条目回调一次 (已处理条目数, 当前路径)，用于 UI 进度。
     */
    fun extract(
        rawInput: InputStream,
        destDir: File,
        onProgress: ((Int, String) -> Unit)? = null,
    ): Stats {
        require(destDir.isDirectory || destDir.mkdirs()) { "无法创建目标目录: $destDir" }
        val destCanonical = destDir.canonicalFile
        val stats = Stats()

        // 自动判断是不是 gzip。
        //
        // 不能想当然认为资产一定是 .tar.gz：AAPT2 对 .gz 结尾的文件有特殊处理，
        // 构建时会把它解压成纯 tar 再打进 APK（文件名里的 .gz 也会被去掉）。
        // 所以这里按魔数嗅探，两种形态都能吃下，不去依赖构建系统的具体行为。
        val buffered = BufferedInputStream(rawInput, 64 * 1024)
        buffered.mark(2)
        val magic = ByteArray(2)
        val read = buffered.read(magic)
        buffered.reset()
        val isGzip = read == 2 && magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte()

        val input = if (isGzip) {
            BufferedInputStream(GZIPInputStream(buffered, 64 * 1024), 64 * 1024)
        } else {
            buffered
        }

        val header = ByteArray(BLOCK)
        var pendingLongName: String? = null
        var pendingLongLink: String? = null
        var pendingPax: Map<String, String> = emptyMap()
        var zeroBlocks = 0
        var count = 0

        while (true) {
            if (!readFully(input, header)) break

            // 两个连续的全零块表示归档结束
            if (header.all { it == 0.toByte() }) {
                if (++zeroBlocks >= 2) break
                continue
            }
            zeroBlocks = 0

            val entry = parseHeader(header)

            // 扩展头本身也占数据区，读完必须补齐到 512 边界再继续。
            // 漏掉这一步会让后续所有块错位 —— 而 Ubuntu 的 rootfs 里有一半条目
            // 带 PAX 头（实测 3413/6826），长度几乎都不是 512 的整数倍，
            // 所以这里一旦写错，解压必然崩坏。
            fun skipExtHeaderPadding() {
                val pad = ((BLOCK - (entry.size % BLOCK)) % BLOCK).toInt()
                if (pad > 0) skipFully(input, pad.toLong())
            }

            when (entry.type) {
                TYPE_GNU_LONGNAME -> {
                    pendingLongName = readString(input, entry.size)
                    skipExtHeaderPadding()
                    continue
                }
                TYPE_GNU_LONGLINK -> {
                    pendingLongLink = readString(input, entry.size)
                    skipExtHeaderPadding()
                    continue
                }
                TYPE_PAX -> {
                    pendingPax = parsePax(readBytes(input, entry.size))
                    skipExtHeaderPadding()
                    continue
                }
                TYPE_PAX_GLOBAL -> {
                    readBytes(input, entry.size) // 全局 PAX 头对本项目无用，跳过
                    skipExtHeaderPadding()
                    continue
                }
            }

            // 扩展头的值优先于头部字段
            val rawName = pendingPax["path"] ?: pendingLongName ?: entry.paxPath ?: entry.name
            val rawLink = pendingPax["linkpath"] ?: pendingLongLink ?: entry.paxLinkPath ?: entry.linkName
            val size = pendingPax["size"]?.toLongOrNull() ?: entry.size
            pendingLongName = null
            pendingLongLink = null
            pendingPax = emptyMap()

            val target = resolveSafely(destCanonical, rawName) ?: run {
                // 归档里有越出目标目录的路径 —— 不静默跳过，直接拒绝。
                throw SecurityException("归档条目路径越界: $rawName")
            }

            count++
            if (count % 200 == 0) onProgress?.invoke(count, rawName)

            // 单个条目失败不应该毁掉整场解压（rootfs 有 6800+ 个条目），
            // 但要记录下来让界面能看到。注意：无论成败都必须把该条目的数据
            // 从流里消费干净，否则后续 512 字节块会全部错位。
            when (entry.type) {
                TYPE_DIR -> {
                    try {
                        target.mkdirs()
                        chmodQuietly(target, entry.mode)
                        stats.dirs++
                    } catch (e: Exception) {
                        stats.errors += "目录 $rawName: ${e.message}"
                    }
                }

                TYPE_SYMLINK -> {
                    try {
                        target.parentFile?.mkdirs()
                        target.delete()
                        // 符号链接的目标是容器内的绝对/相对路径，原样保留即可
                        Os.symlink(rawLink, target.absolutePath)
                        stats.links++
                    } catch (e: Exception) {
                        stats.errors += "符号链接 $rawName -> $rawLink: ${e.message}"
                    }
                }

                TYPE_HARDLINK -> {
                    try {
                        target.parentFile?.mkdirs()
                        target.delete()
                        val linkTarget = resolveSafely(destCanonical, rawLink)
                        if (linkTarget != null && linkTarget.isFile) {
                            // Android 的 SELinux 禁止 untrusted app 创建硬链接
                            // (neverallow all_untrusted_apps file_type:file link)，
                            // 而这里跑在宿主侧、不受 proot 的 --link2symlink 保护，
                            // 所以 Os.link 多半会失败 —— 退化成复制，语义上等价。
                            val linked = try {
                                Os.link(linkTarget.absolutePath, target.absolutePath)
                                true
                            } catch (_: Exception) {
                                false
                            }
                            if (!linked) {
                                linkTarget.copyTo(target, overwrite = true)
                                // copyTo 用默认 umask 建文件（实测得到 0600），
                                // 会丢掉可执行位。硬链接语义上应继承源文件的权限，
                                // 所以这里按归档里记的模式位补回来 ——
                                // 否则 perl5.38.2、uncompress 这类衍生命令无法执行。
                                chmodQuietly(target, entry.mode)
                            }
                        } else {
                            // 链接源还没解出来（tar 允许乱序）—— 先占位，
                            // 保证路径存在，后续真正的写入会覆盖它。
                            target.createNewFile()
                        }
                        stats.links++
                    } catch (e: Exception) {
                        stats.errors += "硬链接 $rawName -> $rawLink: ${e.message}"
                    }
                }

                else -> { // '0'、'\0'、'7' 都当普通文件
                    try {
                        target.parentFile?.mkdirs()
                        target.delete()
                        val wrote = target.outputStream().use { out -> copyExactly(input, out, size) }
                        if (wrote) {
                            chmodQuietly(target, entry.mode)
                            if (entry.mtime > 0) target.setLastModified(entry.mtime * 1000)
                            stats.files++
                            stats.bytes += size
                        } else {
                            stats.errors += "写文件 $rawName（已保持流对齐）"
                        }
                    } catch (e: Exception) {
                        stats.errors += "写文件 $rawName: ${e.message}"
                        skipFully(input, size) // 兜底对齐
                    }
                }
            }

            // 文件数据补齐到 512 字节边界
            val padding = ((BLOCK - (size % BLOCK)) % BLOCK).toInt()
            if (padding > 0) skipFully(input, padding.toLong())
        }

        onProgress?.invoke(count, "")
        return stats
    }

    // ── 下面是格式解析 ──────────────────────────────────────────────────────

    private fun parseHeader(h: ByteArray): Entry {
        val e = Entry(h)
        e.name = readCString(h, 0, 100)
        e.mode = readOctal(h, 100, 8).toInt()
        e.size = readSize(h, 124, 12)
        e.mtime = readOctal(h, 136, 12)
        e.type = h[156].toInt().toChar().takeIf { it != '\u0000' } ?: TYPE_REGULAR
        e.linkName = readCString(h, 157, 100)

        // POSIX ustar 的 magic 是 "ustar\0"，GNU tar 的 magic 是 "ustar "（末尾空格）。
        // 只有 POSIX 变体才使用 prefix 字段拼长路径。
        val magic = h.copyOfRange(257, 263).toString(Charsets.US_ASCII)
        if (magic == "ustar\u0000") {
            val prefix = readCString(h, 345, 155)
            if (prefix.isNotEmpty()) e.name = "$prefix/${e.name}"
        }
        return e
    }

    /**
     * PAX 扩展头的格式是一串 `"%d %s=%s\n"`：
     * 开头的十进制数表示整条记录（含长度字段本身）的字节数。
     */
    private fun parsePax(data: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var pos = 0
        while (pos < data.size) {
            val space = indexOf(data, ' '.code.toByte(), pos)
            if (space < 0) break
            val len = data.copyOfRange(pos, space).toString(Charsets.US_ASCII).trim().toIntOrNull() ?: break
            if (len <= 0 || pos + len > data.size) break

            val record = data.copyOfRange(space + 1, pos + len).toString(Charsets.UTF_8)
            val eq = record.indexOf('=')
            if (eq > 0) {
                val key = record.substring(0, eq)
                val value = record.substring(eq + 1).trimEnd('\n')
                result[key] = value
            }
            pos += len
        }
        return result
    }

    private fun readOctal(buf: ByteArray, off: Int, len: Int): Long {
        // GNU base-256：首字节最高位为 1 时表示大数值
        if (len > 0 && (buf[off].toInt() and 0x80) != 0) {
            var v = (buf[off].toInt() and 0x7F).toLong()
            for (i in 1 until len) v = (v shl 8) or (buf[off + i].toInt() and 0xFF).toLong()
            return v
        }
        var v = 0L
        for (i in 0 until len) {
            val c = buf[off + i].toInt()
            if (c == 0 || c == ' '.code) continue
            if (c < '0'.code || c > '7'.code) break
            v = v * 8 + (c - '0'.code)
        }
        return v
    }

    private fun readSize(buf: ByteArray, off: Int, len: Int): Long {
        // 先探测 base-256，再按八进制解析
        val raw = readOctal(buf, off, len)
        return if (raw < 0) 0 else raw
    }

    private fun readCString(buf: ByteArray, off: Int, len: Int): String {
        var end = off
        val limit = off + len
        while (end < limit && buf[end] != 0.toByte()) end++
        return buf.copyOfRange(off, end).toString(Charsets.UTF_8)
    }

    private fun indexOf(buf: ByteArray, b: Byte, from: Int): Int {
        for (i in from until buf.size) if (buf[i] == b) return i
        return -1
    }

    // ── 下面是 IO 辅助 ──────────────────────────────────────────────────────

    /**
     * 把归档里的相对路径解析到目标目录下，并确保没有越界（`..` 或绝对路径逃逸）。
     * 返回 null 表示这个条目不安全，必须拒绝。
     */
    private fun resolveSafely(destCanonical: File, rawName: String): File? {
        val cleaned = rawName.trimStart('/').removePrefix("./")
        if (cleaned.isEmpty()) return destCanonical

        val candidate = File(destCanonical, cleaned)
        val canonical = try {
            // 用 parent 的规范路径判断，因为目标文件本身可能还不存在
            val parent = candidate.parentFile?.canonicalFile ?: return null
            File(parent, candidate.name)
        } catch (e: Exception) {
            return null
        }

        val destPath = destCanonical.path
        val targetPath = canonical.path
        return if (targetPath == destPath || targetPath.startsWith(destPath + File.separator)) canonical else null
    }

    private fun chmodQuietly(file: File, mode: Int) {
        if (mode == 0) return
        try {
            Os.chmod(file.absolutePath, mode and 0xFFF)
        } catch (_: Exception) {
            // 权限位设置失败不致命，继续
        }
    }

    /**
     * 读满整个缓冲区。返回 false 表示流已经结束 —— 必须严格区分「读满」与
     * 「读到一半就断了」，否则会把截断的归档当成正常数据继续解析。
     */
    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun readBytes(input: InputStream, size: Long): ByteArray {
        val n = size.toInt()
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(out, read, n - read)
            if (r < 0) break
            read += r
        }
        return if (read == n) out else out.copyOf(read)
    }

    private fun readString(input: InputStream, size: Long): String =
        readBytes(input, size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')

    /**
     * 从归档流里精确读取 size 字节写入 out。
     *
     * 关键约定：**无论写入是否成功，都必须把 size 字节全部从输入流消费掉**。
     * 中途放弃写入但又不继续读，会让后续 512 字节块全部错位，整个解压崩坏 ——
     * 这是流式 tar 解析最容易踩的坑。
     *
     * @return false 表示写文件失败（但流的位置仍然是对的）
     */
    private fun copyExactly(input: InputStream, out: java.io.OutputStream, size: Long): Boolean {
        val buf = ByteArray(64 * 1024)
        var remaining = size
        var ok = true
        while (remaining > 0) {
            val want = minOf(remaining, buf.size.toLong()).toInt()
            val n = input.read(buf, 0, want)
            if (n < 0) break
            if (ok) {
                try {
                    out.write(buf, 0, n)
                } catch (_: Exception) {
                    ok = false // 继续读，只是不再写
                }
            }
            remaining -= n
        }
        return ok
    }

    private fun skipFully(input: InputStream, size: Long) {
        var remaining = size
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                // skip 返回 0 时退化成读一个字节
                if (input.read() < 0) break
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }
}
