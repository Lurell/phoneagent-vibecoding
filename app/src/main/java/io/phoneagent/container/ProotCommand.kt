package io.phoneagent.container

/**
 * proot 的 argv 与环境的唯一构造点。
 *
 * 下面每一项都对应一个具体的失败模式，删任何一个容器都跑不起来：
 *
 *  - `-b /dev`        容器里没有 /dev/null 的话，apt 和绝大多数程序会立刻崩。
 *  - `--link2symlink` Android 的 SELinux 禁止 untrusted app 创建硬链接
 *                     (neverallow all_untrusted_apps file_type:file link)，
 *                     而 dpkg/apt/npm 都依赖硬链接。这个扩展把硬链接降级为符号链接。
 *  - `-0`             伪造 uid 0。以普通 uid 跑 apt 会因权限检查直接退出。
 *  - `env -i`         必须把宿主环境变量全部洗掉。Android 的 LD_PRELOAD、
 *                     ANDROID_* 等进了容器会引发难以定位的问题。
 *  - `--kill-on-exit` proot 退出时一并杀掉被追踪进程，避免留下孤儿。
 *
 * 环境变量侧：
 *  - `PROOT_LOADER`   编译进 Termux proot 的 loader 路径指向
 *                     /data/data/com.termux/files/usr/libexec/proot，本应用里不存在，
 *                     必须显式指向 nativeLibraryDir 中的那份。
 *  - `PROOT_TMP_DIR`  proot 默认用 /tmp，在 Android 上不可写。
 *  - `LD_LIBRARY_PATH` 指向 libtalloc.so.2 与 libandroid-shmem.so 所在目录。
 *                     它的优先级高于 proot 里硬编码的 Termux RUNPATH。
 */
class ProotCommand(private val paths: Paths) {

    /**
     * 宿主进程的环境。刻意不继承任何东西 —— ProcessBuilder 会复制当前进程的
     * 环境，其中可能残留 LD_PRELOAD 之类的变量，必须清空后只放我们要的。
     */
    fun environment(): Map<String, String> = mapOf(
        "LD_LIBRARY_PATH" to paths.lib.absolutePath,
        "PROOT_LOADER" to paths.prootLoader.absolutePath,
        // 终端路径不需要它（那边由 argv 里的 `env -i PATH=...` 设定），
        // 但一次性执行的路径需要 —— 否则 `bash -c "node -v"` 找不到 node。
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "PROOT_TMP_DIR" to paths.tmp.absolutePath,
        // 刻意**不设** PROOT_NO_SECCOMP。
        //
        // 这个变量是性能开关，不是兼容开关：设了它，proot 就必须用 PTRACE_SYSCALL
        // 拦截被追踪进程的**每一个**系统调用，而不是只拦截 seccomp 过滤出来的少数几个。
        // 真机基准（find /usr/share/doc -type f）显示关掉它能让操作快约 40%，
        // 而 dpkg 解包这类操作会被放大得更明显。
        //
        // 若某台设备上 proot 因 seccomp 冲突启动失败，再把它按需打开。
    )

    /** 环境变量转成 `KEY=VALUE` 数组（pty 的 execve 需要这个形式）。 */
    fun environmentArray(): Array<String> =
        environment().map { (k, v) -> "$k=$v" }.toTypedArray()

    /**
     * proot 自身的参数前缀，两种模式共用。
     * 终端模式与命令模式必须用同一份参数，否则两边会慢慢漂移 —— 这类不一致
     * 极难排查（本项目已经在调试脚手架上吃过一次亏）。
     */
    private fun prootPrefix(): List<String> = listOf(
        paths.prootBinary.absolutePath,
        "--link2symlink",
        "--kill-on-exit",
        "-0",
        "-r", paths.rootfs.absolutePath,
        "-w", "/root",
        "-b", "/dev",
        "-b", "/proc",
        "-b", "/sys",
        "-b", "${paths.tmp.absolutePath}:/dev/shm",
        "-b", "${paths.home.absolutePath}:/root",
        "-b", "${paths.out.absolutePath}:/out",
        // 与用户的文件通道。容器里的 Agent 只能通过这两个目录碰到手机侧的东西。
        "-b", "${paths.bridgeIn.absolutePath}:/bridge/in",
        "-b", "${paths.bridgeOut.absolutePath}:/bridge/out",
        // ⚠ `androidRuntimeBinds()` 必须插在**这里** —— 也就是 `/usr/bin/env` 之前。
        //
        // 它返回的是 `-b /system -b /apex`，而这些是 **proot** 的参数。
        // 曾经把它接在整个列表末尾（env 之后），结果 env 收到了这些 -b，
        // 报 `/usr/bin/env: '-b': No such file or directory`。
        //
        // 那个 bug 只在「装了 Android 构建工具」时才出现（bind 是条件性的），
        // 所以终端、命令、Agent 安装全都正常，唯独这一条路径炸。
    ) + androidRuntimeBinds() + listOf(
        "/usr/bin/env", "-i",
        "HOME=/root",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM=xterm",
        "LANG=C.UTF-8",
        // 这个必须有，且必须是全局的。
        //
        // 我们的容器**没有交互式终端** —— 容器的 stdin 是命令管道，界面没法回答
        // debconf 的交互提问。而 Ubuntu 的 tzdata 等包在配置阶段会弹
        // 「Please select the geographic area」，一旦弹出，dpkg 会永远等下去，
        // 表现为界面卡死、只能强杀。
        //
        // 放在基础环境里而不是逐个命令加前缀，是因为任何软件包在任何时候都可能
        // 触发 debconf，逐个打补丁必漏。
        "DEBIAN_FRONTEND=noninteractive",
        // 时区跟宿主保持一致，否则容器里的时间是 UTC，日志和证书校验都会别扭。
        "TZ=" + java.util.TimeZone.getDefault().id,
    )

    /**
     * 装了 Android 构建工具时，才把系统的 `/system` 与 `/apex` 挂进容器。
     *
     * 为什么需要：Termux 编译的 aapt2 是 bionic 二进制，它的解释器是
     * `/system/bin/linker64` —— 而那是个指向 `/apex/com.android.runtime/bin/linker64`
     * 的软链，所以两个都得挂，只挂一个链接会断。
     *
     * 为什么是条件性的：这只对「编 APK」有用。日常跑 Agent 时没必要把 Android 的
     * 系统目录暴露给容器 —— 少挂一个目录就少一分被误用的可能。
     */
    private fun androidRuntimeBinds(): List<String> =
        if (java.io.File(paths.rootfs, "opt/android-tools").isDirectory) {
            listOf("-b", "/system", "-b", "/apex")
        } else {
            emptyList()
        }

    /**
     * 命令模式：从 stdin 逐行读命令并执行，靠哨兵行回报退出码。
     * 没有 pty，适合界面上的命令框与预设按钮。
     */
    /**
     * 终端模式：交互式 bash，直接挂在 pty 上。
     * 不用 --norc，让它读 /etc/bash.bashrc 以拿到正常的提示符与补全。
     */
    fun interactiveArgv(): List<String> = prootPrefix() + listOf("/bin/bash", "-i")

    /**
     * 一次性执行：不开常驻会话，直接跑一条命令。
     *
     * 装 Agent、装构建工具、环境自检都走这条 —— 它们不该要求用户先去启动终端。
     */
    fun oneShotArgv(shellCommand: String): List<String> =
        prootPrefix() + listOf("/bin/bash", "--norc", "-c", shellCommand)
}
