package io.phoneagent.container;

/**
 * pty 的 JNI 绑定。实现见 {@code app/src/main/cpp/pty.c}。
 *
 * <p>Android 的 bionic 不提供 {@code forkpty}/{@code openpty}，所以底层是手写的等价实现。
 * 为什么非要 pty：没有它，容器里的程序 {@code isatty()} 返回假，行缓冲与转义序列全都不对，
 * 交互式程序（claude、vim、top）根本起不来；而且旋转屏幕、弹出软键盘改变行列数时
 * 无法通知子进程，画面会错乱。
 *
 * <p><b>为什么这个类是 Java 而不是 Kotlin</b>：Kotlin 的 {@code object} 编译后，
 * Java 侧必须写成 {@code Pty.INSTANCE.forkExec(...)}；而 {@code @JvmStatic} 加在
 * {@code external} 方法上会不会生成静态桥接、JNI 符号会不会变，都有不确定性。
 * 写成 Java 的静态 native 方法，JNI 符号就是
 * {@code Java_io_phoneagent_container_Pty_forkExec}，与 C 侧一一对应，没有任何歧义。
 */
public final class Pty {

    static {
        System.loadLibrary("phoneagentpty");
    }

    private Pty() {
    }

    /**
     * 建立 pty 并 fork，子进程立即 execve 目标程序。
     *
     * @param argv        完整命令行，argv[0] 必须是可执行文件的绝对路径
     * @param envp        环境变量数组，每项形如 {@code KEY=VALUE}
     * @param cwd         子进程的工作目录，null 表示继承
     * @param cols        终端初始列数
     * @param rows        终端初始行数
     * @param masterFdOut 出参：成功时 [0] 为 pty 主端 fd，失败时为 -1
     * @return 成功返回子进程 pid，失败返回 -1
     */
    public static native int forkExec(String[] argv, String[] envp, String cwd,
                                      int cols, int rows, int[] masterFdOut);

    /** 调整 pty 尺寸，内核会给前台进程组发 SIGWINCH。 */
    public static native boolean setWindowSize(int fd, int cols, int rows);

    /** 回收子进程并取出退出码。阻塞，必须在后台线程调用。 */
    public static native int waitFor(int pid);
}
