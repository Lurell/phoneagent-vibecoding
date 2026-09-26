// phoneagent 的 pty 支持。
//
// 为什么需要这一层：Android 的 bionic **不提供 `forkpty`/`openpty`**
// （它们在 glibc 的 libutil 里，Android 没有这个库，NDK 的 sysroot 里连 pty.h 都没有）。
// 所以这里用 bionic 提供的基础原语手写一个等价实现：
//   posix_openpt / grantpt / unlockpt / ptsname_r  -> 打开一对 pty
//   fork + setsid + TIOCSCTTY + dup2               -> 把子进程绑到 pty 从端
//
// 为什么不用 Java 层绕（比如 script / socat）：手机上的终端必须能**动态调整尺寸**。
// 旋转屏幕、弹出软键盘都会改变行列数，没有 TIOCSWINSZ + SIGWINCH 的话画面会错乱。
// 只有自己持有 pty 主端 fd 才能做到这一点 —— 这也是 Termux 用 JNI 的原因。
//
// ── fork 的安全约束（这段是本文件最容易写错的地方）────────────────────────
// Android 应用进程是多线程的。`fork()` 之后子进程里**只能调用 async-signal-safe 的函数**
// —— 因为子进程继承的是调用线程的内存快照，其他线程持有的锁永远是加锁状态，
// 一旦在子进程里调用 malloc / JNI 函数等非 async-signal-safe 的东西就会死锁。
//
// 所以：**所有 JNI 数组转换、内存分配都在 fork 之前做完**，
// 子进程里只做 setsid/open/ioctl/dup2/close/chdir/execve/_exit 这些系统调用。
//
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <android/log.h>

#define LOG_TAG "phoneagentPty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── 辅助：把 Java 的 String[] 转成 malloc 出来的 char**（必须在 fork 之前调用）──

static char **to_c_array(JNIEnv *env, jobjectArray arr) {
    if (arr == NULL) {
        char **empty = (char **) calloc(1, sizeof(char *));
        return empty;
    }
    jsize n = (*env)->GetArrayLength(env, arr);
    char **out = (char **) calloc((size_t) n + 1, sizeof(char *));
    if (out == NULL) return NULL;

    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        if (s == NULL) {
            out[i] = strdup("");
            continue;
        }
        const char *utf = (*env)->GetStringUTFChars(env, s, NULL);
        out[i] = strdup(utf != NULL ? utf : "");
        if (utf != NULL) (*env)->ReleaseStringUTFChars(env, s, utf);
        (*env)->DeleteLocalRef(env, s);
    }
    out[n] = NULL;
    return out;
}

static void free_c_array(char **arr) {
    if (arr == NULL) return;
    for (char **p = arr; *p != NULL; p++) free(*p);
    free(arr);
}

// ── 对外接口 1：建 pty、fork、exec ──────────────────────────────────────────
//
// 返回子进程 pid（>0）；失败返回 -1，并在 masterFdOut[0] 写入 -1。
// 成功时 masterFdOut[0] 得到 pty 主端的 fd。
//
JNIEXPORT jint JNICALL
Java_io_phoneagent_container_Pty_forkExec(JNIEnv *env, jclass clazz,
                                        jobjectArray argv, jobjectArray envp,
                                        jstring cwd, jint cols, jint rows,
                                        jintArray masterFdOut) {
    // ---- 阶段一：fork 之前把所有准备工作做完 ----
    char **c_argv = to_c_array(env, argv);
    char **c_envp = to_c_array(env, envp);
    char *c_cwd = NULL;
    if (cwd != NULL) {
        const char *utf = (*env)->GetStringUTFChars(env, cwd, NULL);
        c_cwd = strdup(utf != NULL ? utf : "");
        if (utf != NULL) (*env)->ReleaseStringUTFChars(env, cwd, utf);
    }
    if (c_argv == NULL || c_envp == NULL) {
        free_c_array(c_argv);
        free_c_array(c_envp);
        free(c_cwd);
        jint bad = -1;
        (*env)->SetIntArrayRegion(env, masterFdOut, 0, 1, &bad);
        return -1;
    }

    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) {
        LOGE("posix_openpt 失败: %s", strerror(errno));
        free_c_array(c_argv); free_c_array(c_envp); free(c_cwd);
        jint bad = -1;
        (*env)->SetIntArrayRegion(env, masterFdOut, 0, 1, &bad);
        return -1;
    }
    if (grantpt(master) < 0 || unlockpt(master) < 0) {
        LOGE("grantpt/unlockpt 失败: %s", strerror(errno));
        close(master);
        free_c_array(c_argv); free_c_array(c_envp); free(c_cwd);
        jint bad = -1;
        (*env)->SetIntArrayRegion(env, masterFdOut, 0, 1, &bad);
        return -1;
    }

    // ptsname_r 也要在 fork 之前调用（它不是 async-signal-safe）
    char slave_name[256];
    if (ptsname_r(master, slave_name, sizeof(slave_name)) != 0) {
        LOGE("ptsname_r 失败: %s", strerror(errno));
        close(master);
        free_c_array(c_argv); free_c_array(c_envp); free(c_cwd);
        jint bad = -1;
        (*env)->SetIntArrayRegion(env, masterFdOut, 0, 1, &bad);
        return -1;
    }

    // ---- 阶段二：fork ----
    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork 失败: %s", strerror(errno));
        close(master);
        free_c_array(c_argv); free_c_array(c_envp); free(c_cwd);
        jint bad = -1;
        (*env)->SetIntArrayRegion(env, masterFdOut, 0, 1, &bad);
        return -1;
    }

    if (pid == 0) {
        // ---- 子进程：只允许 async-signal-safe 调用 ----
        setsid();

        int slave = open(slave_name, O_RDWR);
        if (slave < 0) _exit(127);

        // 拿到控制终端，之后 Ctrl+C 之类的信号才会送到这里
        ioctl(slave, TIOCSCTTY, 0);

        struct winsize ws;
        ws.ws_row = (unsigned short) rows;
        ws.ws_col = (unsigned short) cols;
        ws.ws_xpixel = 0;
        ws.ws_ypixel = 0;
        ioctl(slave, TIOCSWINSZ, &ws);

        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        if (slave > 2) close(slave);
        close(master);

        if (c_cwd != NULL) chdir(c_cwd);

        execve(c_argv[0], c_argv, c_envp);
        _exit(127); // execve 失败
    }

    // ---- 父进程 ----
    free_c_array(c_argv);
    free_c_array(c_envp);
    free(c_cwd);

    jint jmaster = (jint) master;
    (*env)->SetIntArrayRegion(env, masterFdOut, 0, 1, &jmaster);
    return (jint) pid;
}

// ── 对外接口 2：调整窗口尺寸（触发 SIGWINCH 给前台进程组）──────────────────

JNIEXPORT jboolean JNICALL
Java_io_phoneagent_container_Pty_setWindowSize(JNIEnv *env, jclass clazz,
                                             jint fd, jint cols, jint rows) {
    struct winsize ws;
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;
    return ioctl((int) fd, TIOCSWINSZ, &ws) == 0 ? JNI_TRUE : JNI_FALSE;
}

// ── 对外接口 3：回收子进程（阻塞，需在后台线程调用）────────────────────────

JNIEXPORT jint JNICALL
Java_io_phoneagent_container_Pty_waitFor(JNIEnv *env, jclass clazz, jint pid) {
    int status = 0;
    if (waitpid((pid_t) pid, &status, 0) < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}
