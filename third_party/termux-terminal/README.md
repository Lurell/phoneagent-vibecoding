# 内联的第三方代码：Termux 终端模拟器与终端视图

本目录的许可文件适用于 `app/src/main/java/com/termux/` 下的代码。

## 来源

| 项 | 值 |
|---|---|
| 仓库 | `https://github.com/termux/termux-app` |
| 版本 | tag **v0.118.0** |
| 获取方式 | `https://codeload.github.com/termux/termux-app/tar.gz/refs/tags/v0.118.0` |
| 内联日期 | 2026-09-26 |

取的是其中两个模块：

```
terminal-emulator/src/main/java/com/termux/terminal/   -> app/src/main/java/com/termux/terminal/
terminal-view/src/main/java/com/termux/view/           -> app/src/main/java/com/termux/view/
terminal-view/src/main/res/                            -> app/src/main/res/
```

**保留了原始的 `com.termux.*` 包名**，这是有意为之：内联代码保持原包名，来源一眼可辨，
日后对照上游更新时也不会混淆。

## 许可

`termux/termux-app` 整体是 GPL-3.0-only，但 **Termux 自己的 `LICENSE.md` 里有一条明确例外**
（见本目录 `LICENSE.md`）：

> [Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator)
> code is used which is released under [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
> Check `terminal-view` and `terminal-emulator` libraries.

也就是说这两个模块源自 **jackpal/Android-Terminal-Emulator**，采用 **Apache-2.0**。
完整许可文本见本目录 `LICENSE-Apache-2.0.txt`。

**为什么这件事重要**：如果这两个模块是 GPL-3.0，内联它们会把整个应用拖入更强的传染性
约束。它们是 Apache-2.0 则宽松得多——只需保留版权与许可声明。

**注意**：上游源码文件里**没有**许可头（我们下载的版本就是如此）。为了让归属清晰，
本项目在 `com/termux/` 下的每个文件顶部补加了来源与许可声明——这是新增内容，
不是上游原有内容。

## 本项目所做的改动

内联的代码**不是原样照抄**，以下改动是必要的（逐条列出以便日后同步上游时对照）：

1. **补加许可头** —— 每个文件顶部加一段说明来源、版本与 Apache-2.0 的注释。
   上游源码文件里本来是没有许可头的。

2. **`JNI.java` 已删除** —— 那是 Termux 自己创建 pty 的 JNI 绑定，依赖 Termux 的
   native 库 `libtermux`。本项目改用自有的 `io.phoneagent.container.Pty`
   （Java 类 + 静态 native 方法，实现在 `app/src/main/cpp/pty.c`）。

3. **`TerminalSession.java` 就地改造**（不是新建类，这样 `TerminalView` 依赖的
   公开接口与包名完全不变）。具体改了四处：

   - **`initializeEmulator()`**：`JNI.createSubprocess(...)` 换成 `Pty.forkExec(...)`，
     参数由 `mShellPath` + `mArgs` 拼成完整的 argv。
   - **`updateSize()`**：`JNI.setPtyWindowSize(fd, rows, columns)` 换成
     `Pty.setWindowSize(fd, columns, rows)`。**注意参数顺序变了** ——
     上游那个是 (fd, rows, cols)，本项目按其自身语义统一成了 (fd, cols, rows)。
   - **三个工作线程**：读写改用 `Os.read`/`Os.write`，回收改用 `Pty.waitFor`。
   - **删除了 `wrapFileDescriptor()`**：上游那个方法用反射把 int 塞进
     `java.io.FileDescriptor` 的**私有字段**（隐藏 API，现代 targetSdk 下受限），
     而且失败路径是 `System.exit(1)` —— 会直接杀掉整个应用进程。
     本项目的做法是 `ParcelFileDescriptor.adoptFd(int)`（公开 API，专为接管已有 fd 而设）
     拿到 `FileDescriptor`，再交给 `Os.read`/`Os.write`。整个文件不再有任何反射。

     注意 `android.system.Os` 的 read/write 收的是 `FileDescriptor` **对象**而不是
     int fd —— 这一点容易搞错。

   - 另外把内部 `Handler` 显式钉到 `Looper.getMainLooper()`。上游用默认构造函数，
     于是它绑定的是**构造 `TerminalSession` 的那个线程**的 Looper；一旦在后台线程构造，
     终端解析会跑到非主线程，随后的 `invalidate()` 抛 `CalledFromWrongThreadException`。

4. **`strings.xml` 改名为 `termux_terminal_strings.xml`** —— 避免与本项目自己的
   `strings.xml` 冲突。

5. **两处 `import com.termux.view.R;` 改为 `import io.phoneagent.R;`** ——
   内联进应用模块后，R 类是本项目的（`TerminalViewClient` 相关的两个文件）。

其余文件（`TerminalEmulator`、`TerminalBuffer`、`TerminalRow`、`TerminalRenderer`、
`TerminalView`、`KeyHandler`、`WcWidth` 等）**未做逻辑修改**。

## 集成时必须知道的两个前提

这两条**不是对上游代码的改动**，而是「把这两个模块接进一个非 Termux 的宿主」时
必须自己补上的东西。不知道的话会撞上极难排查的现象。

### 一、必须给 TerminalView 设背景色，否则是白底白字

`TerminalRenderer.drawTextRun()` 里有一句：

```java
if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
    // Only draw non-default background.
    mTextPaint.setColor(backColor);
    canvas.drawRect(...);
}
```

它**刻意不画默认背景色** —— 这是一条优化，前提是**画布已经被刷成终端默认背景色了**。

Termux 的视图在布局 XML 里设了 `android:background`，所以这个前提成立。而把
`TerminalView` 塞进 Compose 的 `AndroidView`（或任何没有背景的容器）时，画布保持透明，
于是露出下层界面的浅色；同时文字用默认前景色（白）绘制 —— **结果是白底白字，
屏幕上看起来一片空白，但终端其实一直在正常工作**。

修法：拿到模拟器后，用调色板里的默认背景色给视图设上（本项目在
`ProotTerminalClient.onEmulatorSet()` 里做）。

### 二、点屏幕不会自动弹出软键盘

`TerminalView` 的手势回调里调了 `requestFocus()`，但 **Android 不会因为一个视图获得焦点
就自动弹出输入法** —— 必须自己调 `InputMethodManager.showSoftInput()`。
上游把这个决定留给了应用（Termux 有自己的软键盘开关），所以
`TerminalViewClient.onSingleTapUp()` 必须由宿主实现，空着不写就是「点屏幕毫无反应」。

## 日后同步上游

```bash
curl -fsSL -o /tmp/termux.tar.gz \
  https://codeload.github.com/termux/termux-app/tar.gz/refs/tags/<新版本>
tar -xzf /tmp/termux.tar.gz -C /tmp
# 对照上面「本项目所做的改动」逐条重新应用
```

注意 `github.com` 直连在国内不可达，必须用 `codeload.github.com`。
