package io.phoneagent.container

import android.content.Context
import android.os.Build
import android.system.Os
import java.io.File

/**
 * 环境自检。
 *
 * 这个 PoC 最大的不确定性在于「这台设备到底允许不允许我们跑外来二进制」——
 * 各厂商的 SELinux 策略、内核配置、Android 版本都会影响结果。与其让失败表现成
 * 一句笼统的「命令执行失败」，不如把这几个关键事实直接摆到界面上：
 *
 *   - 应用落在哪个 SELinux 域里（targetSdk 决定，直接反映 execve 是否被允许）
 *   - nativeLibraryDir 里的文件权限位是什么
 *   - 从 nativeLibraryDir 能否真的 execve 起来
 *   - 备用路线（复制到可写区再 chmod）是否可行
 *
 * 这几行信息能立刻区分「是 SELinux 拦了」还是「是二进制依赖没配好」。
 */
class EnvProbe(
    private val context: Context,
    private val paths: Paths,
) {

    data class Item(val label: String, val value: String, val ok: Boolean)

    fun run(): List<Item> = buildList {
        add(appInfo())
        addAll(abiInfo())
        add(selinuxDomain())
        addAll(nativeLibInfo())
        addAll(prootExecTest())
        add(rootfsInfo())
    }

    private fun appInfo(): Item {
        val info = context.applicationInfo
        return Item(
            "应用 targetSdk / minSdk",
            "${info.targetSdkVersion} / ${if (Build.VERSION.SDK_INT >= 24) info.minSdkVersion else -1}",
            true,
        )
    }

    private fun abiInfo(): List<Item> {
        val abis = Build.SUPPORTED_ABIS.toList()
        val isArm64 = abis.firstOrNull()?.contains("arm64") == true
        return listOf(
            Item("设备 ABI", abis.joinToString(", "), isArm64),
            if (isArm64) {
                Item("架构检查", "arm64 —— 与随包分发的 proot/rootfs 匹配", true)
            } else {
                Item("架构检查", "不是 arm64，包内的 proot 与 rootfs 都无法运行", false)
            },
        )
    }

    /** 进程所处的 SELinux 域。targetSdk 决定它，也就决定了 execve 能不能过。 */
    private fun selinuxDomain(): Item {
        val domain = try {
            File("/proc/self/attr/current").readText().trim().trimEnd('\u0000')
        } catch (e: Exception) {
            return Item("SELinux 域", "读不到（${e.message}）", false)
        }
        // untrusted_app_27 是 targetSdk <= 28 才有的域，它保留了 execve 数据目录的能力
        val legacy = domain.contains("untrusted_app_2") || domain.contains("untrusted_app_25")
        return Item(
            "进程 SELinux 域",
            domain + if (legacy) "   ← 老式域，允许 execve 数据目录" else "",
            true,
        )
    }

    private fun nativeLibInfo(): List<Item> {
        val dir = paths.nativeLib
        if (!dir.isDirectory) {
            return listOf(Item("nativeLibraryDir", "${dir.absolutePath} 不存在", false))
        }
        val entries = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
        val described = entries.joinToString("\n") { f ->
            val mode = try {
                Os.stat(f.absolutePath).st_mode and 0x1FF
            } catch (e: Exception) {
                -1
            }
            val modeStr = if (mode >= 0) String.format("%03o", mode) else "?"
            "  $modeStr  ${f.name}  (${f.length()} 字节)"
        }
        val anyExecutable = entries.any { f ->
            try {
                (Os.stat(f.absolutePath).st_mode and 0x1FF and 0b001001001) != 0
            } catch (e: Exception) {
                false
            }
        }
        return listOf(
            Item("nativeLibraryDir", dir.absolutePath, true),
            Item("随包分发的可执行文件（权限位 / 名字 / 大小）", described, entries.size >= 2),
            Item(
                "可执行位检查",
                if (anyExecutable) "至少一个文件带 x 位" else "没有任何文件带 x 位 —— 安装器可能没解压或没置位",
                anyExecutable,
            ),
        )
    }

    /**
     * 真刀真枪试着把 proot 跑起来。
     * 这是本 PoC 最核心的一个问题，直接给出答案而不是让它表现成后续的莫名失败。
     */
    private fun prootExecTest(): List<Item> {
        val binary = paths.prootBinary
        if (!binary.isFile) {
            return listOf(
                Item(
                    "execve 测试",
                    "${binary.absolutePath} 不存在 —— 多半是 jniLibs 没被解压（检查 useLegacyPackaging 是否为 true）",
                    false,
                )
            )
        }

        val (ok, detail) = tryExec(binary)
        return listOf(
            Item(
                "从 nativeLibraryDir execve proot",
                if (ok) "成功\n$detail" else "失败：$detail",
                ok,
            )
        )
    }

    private fun tryExec(file: File): Pair<Boolean, String> = try {
        val pb = ProcessBuilder(file.absolutePath, "--help")
        pb.redirectErrorStream(true)
        pb.environment().apply {
            clear()
            put("PROOT_TMP_DIR", paths.tmp.absolutePath)
            put("LD_LIBRARY_PATH", paths.lib.absolutePath)
            put("PROOT_LOADER", paths.prootLoader.absolutePath)
            put("PROOT_NO_SECCOMP", "1")
        }
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        // proot --help 会打印用法并以非 0 退出，所以「有输出」才是有意义的信号
        val meaningful = output.isNotBlank()
        meaningful to "exit=$code\n${output.lines().take(6).joinToString("\n")}"
    } catch (e: Exception) {
        false to (e.message ?: e.toString())
    }

    private fun rootfsInfo(): Item {
        val marker = paths.installMarker
        if (!marker.isFile) return Item("rootfs", "尚未安装", false)
        val osRelease = File(paths.rootfs, "etc/os-release")
        val pretty = if (osRelease.isFile) {
            osRelease.readLines().firstOrNull { it.startsWith("PRETTY_NAME=") }
                ?.substringAfter('=')?.trim('"') ?: "?"
        } else {
            "找不到 /etc/os-release"
        }
        return Item("rootfs", pretty, true)
    }
}
