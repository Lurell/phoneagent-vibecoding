package io.phoneagent.container

import android.content.Context
import java.io.File

/**
 * 全部路径的唯一来源，避免路径字符串散落各处。
 *
 * 这里最关键的一点是把「可写区」和「可执行区」明确分开，因为 Android 10 起
 * 这两个属性和 SELinux 标签绑死：
 *
 *   filesDir         -> app_data_file   : 可写，可 mmap(PROT_EXEC)，**不可 execve**
 *   nativeLibraryDir -> apk_data_file   : 只读，可 execve（任何 targetSdk 下都放行）
 *
 * 所以可执行文件必须住 nativeLibraryDir，可写数据必须住 filesDir。
 */
class Paths(context: Context) {

    private val appInfo = context.applicationInfo

    /** 可写区根目录。 */
    val files: File = context.filesDir

    /** proot 运行期依赖的两个 .so。只需被 mmap，不需要 execve，所以可以住可写区。 */
    val lib: File = File(files, "proot/lib")

    /** rootfs 解压目标，即容器的 `/`。 */
    val rootfs: File = File(files, "rootfs")

    /** proot 的临时目录；同时 bind 到容器的 /dev/shm。 */
    val tmp: File = File(files, "proot/tmp")

    /** bind 到容器 /root —— 后续 Agent 的工作区。 */
    val home: File = File(files, "home")

    /** bind 到容器 /out —— 预留给能力 3（让 Agent 编译出 APK 再导出给用户安装）。 */
    val out: File = File(files, "out")

    /**
     * 与用户交换文件的**唯一通道**。容器里对应 `/bridge/in` 与 `/bridge/out`。
     *
     * 为什么要分两个单向目录而不是一个：这样权限也是单向的 —— Agent 对 in 只读、
     * 对 out 只写，改不了用户放进去的东西，也伪造不了「用户之前给过的文件」。
     * 而且单向同步没有冲突处理问题。
     *
     * 注意：`in` 的「只读」是**约定而非强制** —— proot 的 bind 不支持只读挂载，
     * 且容器与应用同属一个 uid，没法用文件权限区分。真正的保护在于
     * **用户手机上的 SAF 目录才是源头**，Agent 改的只是镜像副本。
     */
    val bridgeIn: File = File(files, "bridge/in")
    val bridgeOut: File = File(files, "bridge/out")

    /**
     * 可执行区。必须运行时读 applicationInfo 解析，不能硬编码 ——
     * 路径里带安装时生成的随机串（/data/app/~~xxxx/pkg-yyyy/lib/arm64）。
     */
    val nativeLib: File = File(appInfo.nativeLibraryDir)

    val prootBinary: File get() = File(nativeLib, "libproot.so")
    val prootLoader: File get() = File(nativeLib, "libproot_loader.so")

    /** rootfs 是否已经解压完成。用标记文件判断，避免重复解压 30MB。 */
    val installMarker: File get() = File(rootfs, ".phoneagent-installed")

    fun ensureDirs() {
        listOf(lib, rootfs, tmp, home, out, bridgeIn, bridgeOut).forEach { it.mkdirs() }
    }
}
