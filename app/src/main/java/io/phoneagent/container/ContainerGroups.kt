package io.phoneagent.container

import java.io.File

/**
 * 让容器认识 Android 派给应用进程的补充组 ID。
 *
 * 起因是每次开交互式 shell 都会出现的一行输出：
 *
 *     groups: cannot find name for group ID 3003
 *
 * 它看起来像错误，其实不是故障 —— 但也不只是噪音。来源是 rootfs 的
 * `/etc/bash.bashrc` 里那段「sudo 提示」：
 *
 *     case " $(groups) " in *\ admin\ *|*\ sudo\ *) ...
 *
 * `groups` 要把进程的**补充组**逐个解析成名字，而 Android 给每个应用进程挂了
 * 几个它自己体系里的组（inet、everybody、以及每个应用专属的 cache 与 ext-data 组），
 * 这些 ID 在容器的 `/etc/group` 里当然不存在，于是每解析失败一个就打一行。
 * 组越多，刷得越长 —— 而这跟容器本身好不好用没有任何关系。
 *
 * ── 为什么不去堵 bash 那一边 ──────────────────────────────────────────────
 *
 * 最容易想到的修法是在 `$HOME` 放一个 `.hushlogin` 或 `.sudo_as_admin_successful`，
 * 让那段 `if` 短路、`groups` 不再被调用。但那只是堵住了**一个**调用点：
 * `id -Gn`、`groups`、`getent group` 只要被调用就还会报同样的东西，
 * 而 Agent 恰恰很可能去跑这些命令。一个 Agent 看到 4 行 "cannot find name"
 * 会以为自己身处一个坏掉的环境，甚至可能动手去"修"。
 *
 * 所以这里补的是根因：这些组 ID **真实存在于进程上**（内核的
 * `/proc/<pid>/status` 里列着），只是容器不认识它们的名字。给它们补上名字，
 * 所有调用点就一起干净了。
 *
 * ── 为什么用 `aid_` 前缀 ──────────────────────────────────────────────────
 *
 * 不用 Android 的内部叫法（`inet`、`everybody` 之类）。那些名字在 Linux 语境里
 * 另有含义，塞进容器的 `/etc/group` 会误导；`aid_3003` 则明确表达
 * 「这是 Android 分配的一个组 ID」，不冒充任何 Linux 组。
 *
 * 幂等：已存在的 GID 不会重复添加。rootfs 重装后，下次启动容器会重新补上。
 */
class ContainerGroups(private val paths: Paths) {

    /**
     * 补齐缺失的组名。
     *
     * @return 这次补了几条。rootfs 未安装或写不进去时返回 0 —— 这只是消除
     *         一行提示，失败不该影响容器启动，所以全部就地吞掉。
     */
    fun ensure(): Int {
        val groupFile = File(paths.rootfs, "etc/group")
        if (!groupFile.isFile) return 0

        val present = runCatching {
            groupFile.readLines()
                .mapNotNull { line -> line.split(':').getOrNull(2)?.trim()?.toLongOrNull() }
                .toSet()
        }.getOrElse { return 0 }

        val missing = processGroups().filter { it !in present && it > 0 }
        if (missing.isEmpty()) return 0

        return runCatching {
            val existing = groupFile.readText()
            val sb = StringBuilder(existing)
            if (existing.isNotEmpty() && !existing.endsWith("\n")) sb.append('\n')
            missing.forEach { gid ->
                // 格式与其它行一致：name:passwd:gid:members
                sb.append("aid_").append(gid).append(":x:").append(gid).append(":\n")
            }
            groupFile.writeText(sb.toString())
            missing.size
        }.getOrElse { 0 }
    }

    /**
     * 本进程的补充组 ID，取自 `/proc/self/status` 的 `Groups:` 行。
     *
     * 读的是**应用进程自己**的组 —— pty 里 fork 出来的 proot 原样继承，
     * 所以两边一致。Java 没有 `getgroups()`，`/proc` 是唯一的路。
     */
    private fun processGroups(): List<Long> = runCatching {
        File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("Groups:") }
            ?.removePrefix("Groups:")
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.mapNotNull { it.toLongOrNull() }
            ?: emptyList()
    }.getOrElse { emptyList() }
}
