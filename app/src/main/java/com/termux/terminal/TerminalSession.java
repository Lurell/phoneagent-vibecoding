/*
 * 内联自 termux/termux-app (tag v0.118.0) 的 terminal-emulator / terminal-view 模块。
 * 上游：https://github.com/termux/termux-app
 * 该部分源自 jackpal/Android-Terminal-Emulator，采用 Apache License 2.0。
 * 许可全文见 third_party/termux-terminal/LICENSE-Apache-2.0.txt
 * 本项目的改动见 third_party/termux-terminal/README.md
 */
package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import io.phoneagent.container.Pty;

/**
 * A terminal session, consisting of a process coupled to a terminal interface.
 * <p>
 * The subprocess will be executed by the constructor, and when the size is made known by a call to
 * {@link #updateSize(int, int)} terminal emulation will begin and threads will be spawned to handle the subprocess I/O.
 * All terminal emulation and callback methods will be performed on the main thread.
 * <p>
 * The child process may be exited forcefully by using the {@link #finishIfRunning()} method.
 * <p>
 * NOTE: The terminal session may outlive the EmulatorView, so be careful with callbacks!
 */
public final class TerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_PROCESS_EXITED = 4;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /**
     * A queue written to from a separate thread when the process outputs, and read by main thread to process by
     * terminal emulator.
     */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(4096);
    /**
     * A queue written to from the main thread due to user interaction, and read by another thread which forwards by
     * writing to the {@link #mTerminalFileDescriptor}.
     */
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);
    /** Buffer to write translate code points into utf8 before writing to mTerminalToProcessIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    /** The pid of the shell process. 0 if not started and -1 if finished running. */
    int mShellPid;

    /** The exit status of the shell process. Only valid if ${@link #mShellPid} is -1. */
    int mShellExitStatus;

    /**
     * pty 主端的文件描述符，由 {@link Pty#forkExec} 返回。
     *
     * 上游 Termux 在这里保存 `JNI.createSubprocess` 的返回值，并把 fd 用反射塞进一个
     * `java.io.FileDescriptor` 再包成流。本项目改为直接用 int fd + `Os.read`/`Os.write`：
     * 那个反射访问的是 JDK 私有字段，在现代 targetSdk 上属于受限的隐藏 API，
     * 而它的失败路径是 `System.exit(1)` —— 会直接杀掉整个应用进程。
     */
    private int mTerminalFileDescriptor;

    /**
     * pty 主端的 ParcelFileDescriptor。存在的唯一理由是：
     * `android.system.Os` 的 read/write 收的是 `FileDescriptor` 对象而不是 int，
     * 而 {@link ParcelFileDescriptor#adoptFd(int)} 是**公开 API**，正是为「接管一个已有的 fd」
     * 设计的 —— 于是完全不需要上游那种反射私有字段的做法。
     */
    private ParcelFileDescriptor mPtyPfd;

    /** 交给 Os.read / Os.write 用的 FileDescriptor，由 mPtyPfd 派生。 */
    private FileDescriptor mPtyFd;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final String mShellPath;
    private final String mCwd;
    private final String[] mArgs;
    private final String[] mEnv;
    private final Integer mTranscriptRows;


    private static final String LOG_TAG = "TerminalSession";

    public TerminalSession(String shellPath, String cwd, String[] args, String[] env, Integer transcriptRows, TerminalSessionClient client) {
        this.mShellPath = shellPath;
        this.mCwd = cwd;
        this.mArgs = args;
        this.mEnv = env;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the attached pty of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows);
        } else {
            // 注意参数顺序：Pty.setWindowSize 是 (fd, cols, rows)，与底层 winsize 的
            // ws_col/ws_row 对应。写反了终端会变成竖条。
            Pty.setWindowSize(mTerminalFileDescriptor, columns, rows);
            mEmulator.resize(columns, rows);
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start terminal emulation.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows) {
        mEmulator = new TerminalEmulator(this, columns, rows, mTranscriptRows, mClient);

        // 建 pty、fork、execve 全部由我们自己的实现完成（见 app/src/main/cpp/pty.c）。
        // argv[0] 是程序本身，mArgs 是其后的参数 —— 这一点不能搞错，
        // execve 拿 argv[0] 当路径用。
        final String[] argv = new String[mArgs.length + 1];
        argv[0] = mShellPath;
        System.arraycopy(mArgs, 0, argv, 1, mArgs.length);

        int[] masterFdHolder = new int[1];
        final int pid = Pty.forkExec(argv, mEnv, mCwd, columns, rows, masterFdHolder);
        final int fd = masterFdHolder[0];

        if (pid < 0 || fd < 0) {
            mShellPid = -1;
            mTerminalFileDescriptor = -1;
            mClient.logError(LOG_TAG, "创建 pty 或 fork 失败（pid=" + pid + ", fd=" + fd + "）");
            return;
        }
        mShellPid = pid;
        mTerminalFileDescriptor = fd;

        // 用公开 API 把 int fd 变成 FileDescriptor —— 不碰任何私有字段。
        mPtyPfd = ParcelFileDescriptor.adoptFd(fd);
        final FileDescriptor ptyFd = mPtyPfd.getFileDescriptor();
        mPtyFd = ptyFd;

        new Thread("TermSessionInputReader[pid=" + pid + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                while (true) {
                    int read;
                    try {
                        read = Os.read(ptyFd, buffer, 0, buffer.length);
                    } catch (ErrnoException | InterruptedIOException e) {
                        if (!isTeardown(e)) {
                            mClient.logError(LOG_TAG, "pty 读取异常结束: " + e);
                        }
                        return;
                    }
                    // 读到 0 或负值：从端已关闭。
                    // 实测这条分支很少走到 —— pty 把「对方走了」以 EIO 异常的形式送过来，
                    // 而不是以 EOF（见 isTeardown 的说明）。
                    if (read <= 0) {
                        return;
                    }
                    if (!mProcessToTerminalIOQueue.write(buffer, 0, read)) {
                        // 队列只在 cleanupResources 里关，而那里会先把 mShellPid 置 -1，
                        // 所以「写不进去」等于会话已经结束，不是故障。
                        if (isRunning()) {
                            mClient.logError(LOG_TAG, "pty 数据入队失败，读取线程退出");
                        }
                        return;
                    }
                    mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
                }
            }
        }.start();

        new Thread("TermSessionOutputWriter[pid=" + pid + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                while (true) {
                    int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
                    if (bytesToWrite == -1) return;
                    try {
                        Os.write(ptyFd, buffer, 0, bytesToWrite);
                    } catch (ErrnoException | InterruptedIOException e) {
                        if (!isTeardown(e)) {
                            mClient.logError(LOG_TAG, "pty 写入失败: " + e);
                        }
                        return;
                    }
                }
            }
        }.start();

        new Thread("TermSessionWaiter[pid=" + pid + "]") {
            @Override
            public void run() {
                int processExitCode = Pty.waitFor(pid);
                mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, processExitCode));
            }
        }.start();
    }

    /** Write data to the shell process. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (mShellPid > 0) mTerminalToProcessIOQueue.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Finish this terminal session by sending SIGKILL to the shell. */
    /**
     * pty 上的 I/O 异常是否只是「会话结束了」。
     *
     * **这不是可选的谨慎，是必需的。** Linux 的 pty master 在从端全部关闭之后，
     * read 返回的是 **EIO 而不是 0** —— 也就是说「对方走了」这个信号是以异常形式
     * 送来的，而「读到 0」那条正常路径基本走不到。用户点「结束」时
     * `finishIfRunning()` 先 SIGKILL 掉容器进程，从端随之关闭，读线程拿到的就是 EIO；
     * 若恰好是在 read 阻塞期间关掉了 fd，则拿到 InterruptedIOException。
     *
     * 曾经这两种都按 error 打日志，于是**每次正常关闭容器，界面上都会冒一条红色的
     * 「pty 读取异常结束」** —— 用户看到的是「我刚点的那个按钮报了个错」。
     *
     * 分两层判定，因为两个信号的先后顺序不确定：
     *   - 进程已退出（cleanupResources 把 mShellPid 置了 -1）→ 一定是在收尾
     *   - EIO / interrupted → pty 已断开
     * 只有「会话还在跑」且「不是这两种错误」才说明真有问题。
     */
    private boolean isTeardown(Exception e) {
        if (!isRunning()) return true;
        return e instanceof InterruptedIOException
                || (e instanceof ErrnoException && ((ErrnoException) e).errno == OsConstants.EIO);
    }

    public void finishIfRunning() {
        if (isRunning()) {
            try {
                Os.kill(mShellPid, OsConstants.SIGKILL);
            } catch (ErrnoException e) {
                mClient.logWarn(LOG_TAG, "Failed sending SIGKILL: " + e.getMessage());
            }
        }
    }

    /** Cleanup resources when the process exits. */
    void cleanupResources(int exitStatus) {
        synchronized (this) {
            mShellPid = -1;
            mShellExitStatus = exitStatus;
        }

        // Stop the reader and writer threads, and close the I/O streams
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();

        // 关闭 pty 主端。从端那边会收到 EOF，前台进程组随后退出。
        // 关 ParcelFileDescriptor 即可，它持有 fd 的所有权。
        try {
            if (mPtyPfd != null) {
                mPtyPfd.close();
                mPtyPfd = null;
                mPtyFd = null;
            }
        } catch (IOException e) {
            // 已经关过了或者本来就没建起来，都无所谓
        }
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mShellPid != -1;
    }

    /** Only valid if not {@link #isRunning()}. */
    public synchronized int getExitStatus() {
        return mShellExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    public int getPid() {
        return mShellPid;
    }

    /** Returns the shell's working directory or null if it was unavailable. */
    public String getCwd() {
        if (mShellPid < 1) {
            return null;
        }
        try {
            final String cwdSymlink = String.format("/proc/%s/cwd/", mShellPid);
            String outputPath = new File(cwdSymlink).getCanonicalPath();
            String outputPathWithTrailingSlash = outputPath;
            if (!outputPath.endsWith("/")) {
                outputPathWithTrailingSlash += '/';
            }
            if (!cwdSymlink.equals(outputPathWithTrailingSlash)) {
                return outputPath;
            }
        } catch (IOException | SecurityException e) {
            mClient.logStackTraceWithMessage(LOG_TAG, "Error getting current directory", e);
        }
        return null;
    }

    // 上游这里有个 wrapFileDescriptor()，用反射把 int fd 塞进 java.io.FileDescriptor
    // 的私有字段，失败时调用 System.exit(1)。本项目已删除该方法：
    //   - 反射访问 JDK 私有字段属于隐藏 API，现代 targetSdk 下会被限制；
    //   - 失败路径是 System.exit(1)，会杀掉整个应用进程；
    //   - 我们本来就持有 int fd，直接 Os.read/Os.write 即可，不需要包装。

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[4 * 1024];

        // 上游用 Handler 的默认构造函数，于是它绑定的是**构造该 TerminalSession 的线程**
        // 的 Looper。一旦在后台线程构造，终端解析就跑到了非主线程，
        // 随后的 invalidate() 会抛 CalledFromWrongThreadException。
        // 显式钉到主 Looper，消除这个隐患。
        MainThreadHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                mEmulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
            }

            if (msg.what == MSG_PROCESS_EXITED) {
                int exitCode = (Integer) msg.obj;
                cleanupResources(exitCode);

                String exitDescription = "\r\n[Process completed";
                if (exitCode > 0) {
                    // Non-zero process exit.
                    exitDescription += " (code " + exitCode + ")";
                } else if (exitCode < 0) {
                    // Negated signal.
                    exitDescription += " (signal " + (-exitCode) + ")";
                }
                exitDescription += " - press Enter]";

                byte[] bytesToWrite = exitDescription.getBytes(StandardCharsets.UTF_8);
                mEmulator.append(bytesToWrite, bytesToWrite.length);
                notifyScreenUpdate();

                mClient.onSessionFinished(TerminalSession.this);
            }
        }

    }

}
