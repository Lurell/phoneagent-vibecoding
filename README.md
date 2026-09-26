# phoneagent

**在未 root 的 Android 手机上跑一个 AI Agent，并让它受控地操作这台手机。**

proot 与一个完整的 Linux rootfs 直接打进 APK，首次启动时铺开。Agent 在一个隔离的
Linux 用户空间里工作，通过三条受控通道与手机交互：**文件、指令、App 产物**。

> **状态：v1.0（初版）。** 各项能力均已在 arm64 真机上端到端验证：容器、真 pty 终端、
> Agent 运行环境、文件通道、指令白名单，以及**在手机容器里编译出已签名的 APK**。
>
> 不需要 root，也**没有**把 `targetSdk` 退到 28（那是 Termux 走的老路）。

---

## 能力

| | 说明 |
|---|---|
| **容器** | proot + Ubuntu 24.04 LTS，`uid=0`，**真 pty** 的交互式终端 —— `vim`、`top`、各种 TUI 都能正常跑 |
| **Agent 运行环境** | 应用内一键安装 Node.js 22 + Claude Code |
| **模型接入** | 内置 DeepSeek / Kimi / 智谱 / 官方 预设，一键写好 `settings.json` 里整套接入配置（端点、令牌变量、模型映射、思考强度） |
| **文件通道** | SAF 授权一个目录作为唯一交互口：`/bridge/in`（Agent 只读）、`/bridge/out`（交付口） |
| **指令白名单** | `phone notify` / `phone alarm`，逐条可开关；被拒时给 Agent 可操作的出路 |
| **APK 产物** | Agent 能在手机上编出**已签名**的 APK（需可选装约 500MB 的构建工具链） |

## 快速开始

> **本仓库目前仅发布源码，不提供预编译 APK** 

1. 按「构建」一节产出 APK，装到手机上（**仅 arm64**）
2. 打开 App，跟着引导清单走完四步：**容器环境 → Agent 运行环境 → 模型接入 → 文件通道**
3. 点「**启动 Claude Code**」

之后 Agent 就在容器里干活了。它的产出会出现在选定目录的 `out/` 下。

> 想让 Agent 自己编 Android App，再去「设置 → Android 构建工具」装上工具链。

---

## 安全模型

**容器本身不是安全边界。** proot 是用户态的路径翻译，容器与宿主共享内核 ——
容器里能通过 `/proc` 看到宿主进程。它隔离的是**文件系统视图**，不是安全边界。

真正的边界在**容器与手机之间**：

**一、文件只有一个口，而且是单向的。**
用户授权的那个目录是 Agent 能碰到的全部。`in/` 只读（改不了用户放进去的原件）、
`out/` 只写（伪造不了「用户之前给过的文件」）。而且单向同步没有冲突要处理。

**二、指令默认拒绝，逐条开。**
`open_app`、读通讯录、发短信这类属于「要么能被用作外泄通道、要么涉及资费与身份冒用」，
需要单独设计，不适合顺手加。只有 `notify` / `alarm` 这种**单向、可撤销、不泄露数据**
的操作默认打开。

**三、产物只能由用户亲手安装。**
Agent 可以编出 APK，但装不了 —— 这是 Android 的机制，也是有意保留的约束。

**此外还有一条软件层的**：App 会把一份「运行环境说明」写进容器的
`~/.claude/CLAUDE.md`，告诉 Agent 它在哪、边界在哪、产出该放哪。这不是锦上添花 ——
不说的话它会把结果写进 `/root/` 然后告诉你「完成了」，而你在手机上永远找不到。

> 这份说明是**按当前实际状态渲染**的：构建工具装没装、文件通道开没开、哪几条手机指令
> 被允许 —— 都会如实反映。装了却不说，Agent 会去 `apt install gradle` 白折腾；
> 没开通道却说「产出放这儿用户就能拿到」，它会以为自己交付了。

---

## 核心机制：怎么在未 root 的现代 Android 上跑起 Linux

Android 10 起有一条硬性限制：**`targetSdk >= 29` 的应用不能 `execve` 自己数据目录里的
文件**。初衷是 W^X（可写内存不该同时可执行），代价是把「在应用私有目录里放一堆 Linux
可执行文件然后跑起来」这条常规路线彻底堵死。Termux 至今把 `targetSdk` 停在 28 正是
为了绕开它 —— 代价是永远无法上架 Google Play，且一直背着 Android 10 之前的 legacy 行为。

本项目不需要停在旧 targetSdk。拆开看是三步：

**一、SELinux 拦的是 `execve`，不是 `mmap`。** AOSP 策略里 `untrusted_app_all` 对
`app_data_file` 是**给了 `execute`** 的，缺的只有 `execute_no_trans` —— 而后者只在
`execve` 没有类型转换时才会被查。`mmap(PROT_EXEC)` 只看 `execute`，**这条路是通的**。
数据目录里的代码不能被「执行」，但可以被「映射」。

**二、proot 的 loader 恰好走 mmap。** proot 拦截被追踪进程的 `execve` 后，会把执行目标
改写成自带的 loader；这个 loader 用裸系统调用把 ELF **映射**进内存再跳转，**从不
`execve` 目标程序**。于是 rootfs 里的 `/bin/sh`、`/bin/ls` 全程只被 mmap。

**三、proot 与 loader 自己住 `nativeLibraryDir`。** `/data/app/**/lib/arm64/` 的标签是
`apk_data_file`，策略对它 `execute` 与 `execute_no_trans` **都给了**，且与 targetSdk 无关。
把它们作为「假 `.so`」放进 `jniLibs`，`execve` 一路绿灯。

三步串起来：**全程没有任何一次针对 `app_data_file` 的 `execve`**，W^X 规则自然不再适用。

真机 logcat 里的 SELinux 审计记录直接印证了这一点 —— 全部是 `avc: granted { execute }`，
目标是 `app_data_file`，没有任何 `denied`。

> **proot 必须用 Termux 的构建，不能换源。** 上游 proot（含 Alpine 的 `proot-static`）
> 把 loader 打包在二进制内部，运行时解压到临时目录并要求该目录可执行 —— 而 Android 10+
> 上不存在「既可写又可执行」的目录，必然失败。Termux 的 fork 编译时带了
> `PROOT_UNBUNDLE_LOADER`，loader 是独立文件、可用 `PROOT_LOADER` 指向。

---

## 构建

### 前置

- JDK 17+（Android Studio 自带的 `jbr` 即可）
- Android SDK：platform **37**、build-tools **36.0.0**、platform-tools
- Node.js（仅用于 `scripts/fetch-assets.sh` 解 `.deb`；系统有 `ar` 则不需要）
- 一台 **arm64** 真机（x86_64 主机跑不动 arm64 系统镜像的模拟器）

### 步骤

```bash
bash scripts/fetch-assets.sh      # 拉 proot + rootfs 到 app/src/main/
./gradlew assembleDebug           # 产出 app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` 里的 `sdk.dir` 指向你的 Android SDK。

> **国内网络**：`gradle-wrapper.properties` 的 `distributionUrl` 默认指向腾讯云镜像 ——
> 官方 `services.gradle.org` 会 307 跳转到 `github.com`，在国内常常不可达。镜像内容与
> 官方一致，wrapper 会用官方公布的 `distributionSha256Sum` 校验。海外用户可改回官方地址。

### Tips

**一、AGP 9 内置 Kotlin**，不能再应用 `org.jetbrains.kotlin.android`，否则配置阶段直接
报错。Compose 编译器插件仍需单独应用。

**二、AAPT2 会改写 `.gz` 资产** —— 它把 `assets/rootfs/xxx.tar.gz` 解压成纯 tar，
**去掉文件名里的 `.gz`**，`noCompress += "gz"` 对此无效。本项目不去猜构建系统的规则，
而是让代码自适应：`TarExtractor` 按魔数嗅探输入是不是 gzip，`AssetInstaller` 运行时
枚举 `assets/rootfs/` 取实际文件名。

---

## 工程结构

```
scripts/
  fetch-assets.sh        拉取 proot 二进制、rootfs、CA 证书链（校验 SHA256）
  deb-extract.mjs        解 .deb 用（Windows 的 Git Bash 不带 binutils，没有 ar）
app/src/main/
  cpp/pty.c              forkpty 的手写封装（bionic 没有 forkpty/openpty）
  jniLibs/arm64-v8a/     proot 主程序与 loader（实为可执行文件，借 .so 后缀过安装器）
  assets/
    proot/lib/           运行期依赖 .so
    rootfs/              Ubuntu 24.04 LTS aarch64 rootfs
    env/CLAUDE.md        Agent 的环境说明（**模板**，部署时按实际状态渲染）
    phone/phone          指令白名单的 CLI（bash + /dev/tcp，零依赖）
    android-tools/       容器内装配构建工具链、以及不依赖 Gradle 的 APK 构建流程
  java/
    io/phoneagent/container/   容器层：路径、命令、网络、桥、白名单、设置、环境说明
    io/phoneagent/ui/          Compose 界面
    com/termux/              内联的终端模拟器与视图（Apache-2.0，见 third_party/）
docs/
  pitfalls.md            踩过的坑 —— 每条都附「怎么发现的」
  README.v1.md           v1 时的 README 原文（保留作记录）
```

---

## 已知限制

- **容器随应用生命周期。** 没有前台服务，系统回收应用容器就停了。
  （Agent 的对话本身可以用 `claude --continue` 找回，因为它在容器的文件系统里。）
- **没有真实隔离。** 见上「安全模型」。
- **单 rootfs、单架构。** 只支持 arm64。
- **只有 debug 构建。** 目前没有 release 签名配置，APK 是 `debuggable` 的 ——
  **这意味着有 USB 访问权的人可以读到应用私有目录的内容（包括容器里的 API key）**。
  自己用没问题，别拿去分发。
- **容器内只能编简单的 Android 工程。** 那条链路上没有 kotlinc、没有 AndroidX/Compose、
  没有 Gradle，只能用平台 API 写不依赖第三方库的原生应用。
- **proot 慢。** 它是 ptrace 实现，被追踪进程的每个系统调用都要经过拦截与路径翻译。
  I/O 密集的操作会被显著放大 —— 装一个几百 MB 的包，耗时以十分钟计。这是固有代价。


---

## 许可

GPL-3.0。proot 本身是 GPL-2.0+，随 APK 捆绑分发时这是唯一省心的选择。

内联的 `com.termux` 终端模块**不是 GPL** —— Termux 的 `LICENSE.md` 有一条明确例外，
它们源自 [jackpal/Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator)，
采用 Apache-2.0。详见 [`third_party/termux-terminal/`](third_party/termux-terminal/)。
