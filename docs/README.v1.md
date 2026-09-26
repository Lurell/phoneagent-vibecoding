> **这是 v1.0 时的 README 原文，保留作为记录。** 其中一部分内容（例如「没有 pty」「常驻管道会话」「修复 dpkg 按钮」）已被后续版本取代 —— 以根目录的 `README.md` 为准。

# ProotBox

在**未 root** 的 Android 手机上运行一个隔离的 Linux 用户空间。

proot 与一个完整的 Linux rootfs 直接打进 APK，首次启动时铺开，之后就能在容器里
跑任意 Linux 程序 —— 包括 Node.js、npm，以及建立在其上的 Agent 类工具。

> **当前状态：v1.0.0 —— 三项核心能力全部实现并在真机验证。**
>
> 2026-09-26 在 **Redmi Note 8（Android 11 / API 30、MIUI 12.5、arm64-v8a）** 上实测，
> 全程未 root、`targetSdk 36`（**没有退回 28**）。
>
> | 能力 | 状态 |
> |---|---|
> | **容器** | proot + Ubuntu 24.04.5 LTS，`uid=0`，带**真 pty** 的交互式终端 |
> | **Agent 运行环境** | Node.js 22.23.3 + Claude Code 2.1.283，应用内一键安装 |
> | **SAF 受控互通** | 与用户之间只有 `/bridge/in`（Agent 只读）和 `/bridge/out`（产出） |
> | **指令白名单** | `phone notify` / `phone alarm`，逐条可开关，拒绝时给可操作的理由 |
> | **APK 产物交付** | **Agent 能在手机上编出签名 APK**，落在 `out/`，用户手动安装 |
>
> 完整链路的每一环都在真机上验证过：`execve` 走 `apk_data_file`、loader 以 mmap 接管
> guest 程序（SELinux 审计日志显示 `avc: granted { execute }`）、网络（DNS + CA 证书链）、
> npm 包管理、交互式 TUI，以及**在 aarch64 容器里编出 APK**——后者需要绕过
> 「Google 的构建工具全是 x86_64」这个障碍，做法见「工作原理」。
>
> ## 快速开始
>
> ```
> 1. 安装 APK
> 2. 命令页 →「1 安装环境」      解压 rootfs（约 1 分钟）
> 3. 命令页 →「2 准备 Agent」    装 Node + Claude Code（约 3 分钟）
> 4. 把一个 settings.json 放进容器（填 API key）—— 见下方「已知限制」
> 5. 通道页 → 选一个目录作为文件通道
> 6. 终端页 →「启动容器」→ 敲 claude
> ```
>
> 之后 Agent 就能在容器里工作，产出会出现在你选定的那个目录的 `out/` 下。
>
> 想让 Agent 自己编 Android App，再去设置页装可选的「Android 构建工具」（约 500MB）。

---

## v1 包含什么、不包含什么

**包含**：一个能跑起来、能用的容器 + 一个真实的 Agent 运行环境 + 三条受控的
手机交互通道（文件、指令、App 产物）。

**不包含**（都是有意留到 v1 之后，见文末「路线图」）：进程与会话的持久化、
更丰富的手机指令、Claude Code 之外的 Agent 框架、以及 API key 的设置界面。

---

## 它解决的核心问题

Android 10 起有一条硬性限制：**`targetSdk >= 29` 的应用不能 `execve` 自己数据
目录里的文件**。这条规则的初衷是 W^X（可写内存不该同时可执行），它把「在应用
私有目录里放一堆 Linux 可执行文件然后跑起来」这条常规路线彻底堵死了。
Termux 至今仍把 `targetSdk` 停在 28，正是为了绕开它 —— 代价是永远无法上架
Google Play，且必须一直背着 Android 10 之前的 legacy 行为。

ProotBox 走的是另一条路，**不需要停留在旧 targetSdk**。机制拆开看是三步：

**一、SELinux 拦的是 `execve`，不是 `mmap`。**

AOSP 策略里（`system/sepolicy`，Android 15 与 16 的分支逐字相同）：

```
# private/untrusted_app_all.te
allow untrusted_app_all app_data_file:file { r_file_perms execute };

# private/app_neverallows.te
neverallow { all_untrusted_apps -untrusted_app_25 -untrusted_app_27 -runas_app }
          { app_data_file privapp_data_file }:file execute_no_trans;
```

`execute` 是给了的，缺的是 `execute_no_trans`。没有类型转换时 `execve` 会额外
检查 `execute_no_trans`，所以 `execve` 被拒；而 `mmap(PROT_EXEC)` 只看 `execute`
—— **这条是通的**。也就是说，数据目录里的代码不能被「执行」，但可以被「映射」。

**二、proot 的 loader 恰好走 mmap，不走 execve。**

proot 拦截被追踪进程的 `execve` 后，会把执行目标改写成它自带的一个 loader。
这个 loader 用裸系统调用把 ELF **映射**进内存再跳转到入口点 —— 它从不 `execve`
目标程序。于是 rootfs 里的 `/bin/sh`、`/bin/ls` 全程只被 mmap，`execute_no_trans`
根本没被查过。

**三、proot 与 loader 自己住 `nativeLibraryDir`。**

`/data/app/**/lib/arm64/` 的标签是 `apk_data_file`，策略里对它的授权是：

```
allow appdomain apk_data_file:file { getattr open read ioctl lock map x_file_perms };
# x_file_perms = { getattr execute execute_no_trans map }
```

`execute` 和 `execute_no_trans` **都有**，且与 targetSdk 无关。把 proot 和 loader
作为「假 `.so`」放进 `jniLibs`，它们就落在这里，`execve` 一路绿灯。

三步串起来：**全程没有任何一次针对 `app_data_file` 的 `execve`**，W^X 规则自然
不再适用。

> 本项目**不**依赖 `targetSdk 28` 这条老路。仍保留了一行开关可以退回（见下），
> 供机制不成立的机型使用，但主线走的是现代 targetSdk。

### 真机验证记录

Redmi Note 8 / Android 11 / `targetSdk 36` / 域 `u:r:untrusted_app:s0`。
容器跑起来时，logcat 里的 SELinux 审计记录直接印证了上面三步：

```
avc: granted { execute } for name="uname"
    scontext=u:r:untrusted_app:s0:c202,c257,c512,c768
    tcontext=u:object_r:app_data_file:s0:c202,c257,c512,c768  tclass=file   ← mmap 放行
avc: granted { execute } for path=".../files/proot/lib/libtalloc.so.2"
    tcontext=u:object_r:app_data_file:s0:...                  tclass=file   ← 依赖库放行
```

全部是 **`granted`**，且目标标签是 `app_data_file` —— 内核明确放行了数据目录里文件的
`execute`（mmap），只有 `execve` 走的那条 `execute_no_trans` 被拒。这正是方案成立的
根据：**不 execve，只 mmap。**

同时可见三个进程并存：`libproot.so`、`bash`、`libproot_loader` —— loader 确实被
派生出来接管了 guest 程序的加载。全程无 `avc: denied`，无崩溃。

---

## 工程结构

```
scripts/
  fetch-assets.sh        拉取 proot 二进制与 rootfs（产物不进版本库）
  deb-extract.mjs        解 .deb 用（Windows 的 Git Bash 不带 binutils，没有 ar）
app/src/main/
  jniLibs/arm64-v8a/
    libproot.so          proot 主程序（实为可执行文件，借 .so 后缀过安装器）
    libproot_loader.so   proot 的 loader
  assets/
    proot/lib/           运行期依赖 .so（只需被 mmap，可住可写区）
    rootfs/              Ubuntu 24.04 LTS aarch64 rootfs
  java/io/prootbox/
    container/
      Paths.kt           可写区 / 可执行区的划分
      TarExtractor.kt    纯 Kotlin 的 tar.gz 解压器
      ProotCommand.kt    proot argv 与环境的唯一构造点
      ProotSession.kt    常驻容器会话（stdin/stdout + 哨兵分帧）
      AssetInstaller.kt  首次运行铺开资产
      EnvProbe.kt        环境自检
    ui/                  Compose 界面
```

**为什么 tar 解压要自己写**：Android 不带 tar；调用外部 busybox 解压会形成循环
依赖（解压本身也要可执行文件能跑起来）。自己解让「解压」与「执行」解耦 ——
即使某台设备的 SELinux 挡住了 `execve`，也能明确知道卡在哪一步。

---

## 构建

### 前置

- JDK 17+（Android Studio 自带的 `jbr` 即可）
- Android SDK：platform **37**、build-tools **36.0.0**、platform-tools
- Node.js（仅用于 `scripts/fetch-assets.sh` 解 `.deb`；若系统有 `ar` 则不需要）
- 一台 **arm64** 真机。x86_64 主机跑不动 arm64 系统镜像的模拟器

### 步骤

```bash
bash scripts/fetch-assets.sh      # 拉 proot + rootfs 到 app/src/main/
./gradlew assembleDebug           # 产出 app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` 里的 `sdk.dir` 指向你的 Android SDK。

> **国内网络**：`gradle-wrapper.properties` 里的 `distributionUrl` 默认指向腾讯云
> 镜像。官方 `services.gradle.org` 会 307 跳转到 `github.com`，在国内常常不可达。
> 镜像内容与官方一致，wrapper 会用 `distributionSha256Sum` 校验。海外用户可改回
> 官方地址。

### 构建时容易踩的三个坑

都是这个项目实际踩过的，写下来省得重复排查：

**一、AGP 9 内置 Kotlin，不能再应用 `org.jetbrains.kotlin.android`。**
同时应用会直接在配置阶段报错。Compose 编译器插件仍需单独应用
（`org.jetbrains.kotlin.plugin.compose`）。

**二、AAPT2 会改写 `.gz` 资产。**
构建时它把 `assets/rootfs/xxx.tar.gz` 解压成纯 tar，**去掉文件名里的 `.gz`**，
再按普通资产 deflate。后果是：代码里写死的 `.tar.gz` 路径在 APK 里不存在，
而且拿到的数据已经不是 gzip。`noCompress += "gz"` 对此无效。

本项目不去猜构建系统的规则，而是让代码自适应：

- `TarExtractor` 按魔数嗅探输入是不是 gzip，两种形态都吃
- `AssetInstaller` 运行时枚举 `assets/rootfs/` 取实际文件名

**三、Kotlin 的块注释可以嵌套。**
注释里出现「斜杠紧跟星号」会开一个永不闭合的嵌套注释，而报错定位在**文件末尾**，
极难定位。写路径通配符时要避开这个字符序列。

### 内存

`gradle.properties` 里的内存参数是按 **8GB 物理内存**调的（本项目的开发机就是）。
Gradle 守护进程 + Kotlin 编译器 + AGP worker 在默认 `-Xmx3g` 下会把整机吃穿，
表现是 JVM 硬崩：`Native memory allocation (mmap) failed to map ... G1 virtual space`。
内存充裕的机器可以放宽到 `-Xmx3g` 并把 `org.gradle.parallel` 打开，构建会快很多。

### 退回 targetSdk 28

若自检显示 `execve` 失败，说明这台设备的策略与上面分析的不符，可退回 Termux 的
老路（数据目录直接 `execve` 是允许的）：

```bash
./gradlew assembleDebug -Pprootbox.targetSdk=28
```

代价：无法上架 Google Play，并会启用 Android 10 之前的一系列 legacy 行为。

---

## 使用

1. **安装环境** —— 铺开 proot 运行库并解压 rootfs（约 30MB，首次需要几十秒）
2. **自检** —— 报告 SELinux 域、nativeLibraryDir 里的文件权限位、以及能否真的
   `execve`。**失败时先看这里**，它能区分「被 SELinux 拦了」和「二进制依赖没配好」
3. **启动容器** —— 起一个常驻的 proot + bash
4. 输入命令并**执行**。默认命令 `uname -a && cat /etc/os-release && id` 能一眼
   看出容器是否真的起来了

容器是**常驻会话**而非一次性命令：`cd`、环境变量、后台进程在多条命令之间保留。

---

## 已知限制

- **没有 pty**。因此没有交互式提示，`apt install` 的进度条、`vim`、`top` 都没法用。
  要支持这些需要引入 pty（`forkpty` + `openpty` 的 JNI 封装）。
- **读 stdin 的命令会吃掉后续输入**。会话循环从 stdin 逐行读命令，裸 `cat` 之类
  会把后面的命令当数据吃掉。
- **容器随应用生命周期**。没有前台服务，锁屏或切后台久了可能被系统回收。
- **没有真实隔离**。proot 是用户态的路径翻译，容器内进程与宿主共享内核，
  容器里能通过 `/proc` 看到宿主进程。它隔离的是**文件系统视图**，不是安全边界。
- **单 rootfs、单架构**。只支持 arm64，只支持一个已安装的 rootfs。
- **rootfs 换发行版需要改代码**。目前只认 `assets/rootfs/ubuntu-24.04-base-arm64.tar.gz`
  这个路径与 `.tar.gz` 格式。

---

## 资产从哪来

`scripts/fetch-assets.sh` 拉三样东西，全部校验 SHA256：

| 来源 | 内容 |
|---|---|
| Termux APT 仓库 | `proot` 5.1.107.95 + `libtalloc` 2.4.3 + `libandroid-shmem` 0.7 |
| Ubuntu cdimage | `ubuntu-base-24.04.5-base-arm64.tar.gz`（29.9MB） |

**proot 必须用 Termux 的构建，不能换源。** 上游 proot（含 Alpine 的
`proot-static`）把 loader 打包在二进制内部，运行时解压到临时目录并要求该目录
可执行 —— Android 10+ 上不存在「既可写又可执行」的目录，必然失败。Termux 的
fork 编译时带了 `PROOT_UNBUNDLE_LOADER`，loader 是独立文件、可用 `PROOT_LOADER`
环境变量指向，这是唯一可行的路线。

产物不进版本库（见 `.gitignore`）：仓库只存代码与脚本，避免 30MB 二进制污染
git 历史。

---

## 容器内的网络

proot **不做网络命名空间隔离** —— 容器里的 `apt`、`npm` 发出的请求就是本应用发出的，
因此受本应用的权限约束。这也意味着只要应用有 `INTERNET` 权限，容器就能上网，不需要
额外的网络配置。但实际跑起来有三个坑必须处理（见 `ContainerNetwork.kt`）：

**一、DNS 是空的，而且系统报上来的第一条不能用。** Android 自己用 netd 解析域名，
**根本不读 `/etc/resolv.conf`**，所以 rootfs 里那个文件是 0 字节。做法是在每次启动容器前
通过 `ConnectivityManager` 读出 Android 当前生效网络的 DNS，写进 rootfs。

但**不能原样写入**：实测 Redmi Note 8 会首选报上来 `fe80::1%wlan0` —— 一个带作用域
后缀的**链路本地 IPv6** 地址。glibc 的解析器处理这种格式不稳定，会让容器里所有域名
解析直接超时（apt 报 `Temporary failure resolving`）。必须过滤掉 `fe80::/10`。

**二、rootfs 里没有 CA 证书链。** Ubuntu 的 base 镜像是 `debootstrap --variant=minbase`
构建的，**不含 `ca-certificates` 包**，连 `/etc/ssl` 目录都没有。于是 apt 走 HTTPS 时报
`Certificate verification failed: The certificate issuer is unknown`。

这是个鸡生蛋问题：要用 apt 装 `ca-certificates`，而 apt 自己就需要它。所以随包带一份
Mozilla 证书链（`scripts/fetch-assets.sh` 会拉取并校验）直接写进容器。

**三、apt 的降权沙箱必然失败。** apt 默认把下载动作降权到 `_apt` 用户执行，而
**Android 的 seccomp 过滤器屏蔽了 `setuid`/`setgid` 一族系统调用**，降权必然报
`Could not switch to '_apt'`。修法是在 `apt.conf.d` 里把 `APT::Sandbox::User` 指回 root。

**四、IPv6 黑洞。** 部分网络下 IPv6 路由不通，但 DNS 仍会返回 AAAA 记录，apt 会先卡在
IPv6 上直到超时。强制 `Acquire::ForceIPv4` 省掉这段无谓等待。

这四个问题串起来的表现是一条很有迷惑性的错误链：先 `Temporary failure resolving`
（DNS），修好后变成 `certificate is NOT trusted`（CA），再修好才是网络本身。
排查时按这个顺序看，能少走弯路。

另外可选地把 apt 源换成 `mirrors.aliyun.com`：arm64 的 Ubuntu 走的是 **`ports.ubuntu.com`**
（不是 `archive.ubuntu.com`），官方源在中国大陆往往只有几十 KB/s。镜像是同一批包的完整
镜像、签名密钥相同，安全性不受影响。这一步是幂等的，且会在界面上明确提示。

## 与用户的文件通道

**容器里的 Agent 和用户之间只有一个交互口**，这是整个安全模型的支点：用户能看到那个
文件夹，就是 Agent 能看到的一切。

```
用户授权的 SAF 目录              App 私有目录               容器内
├── in/    ─── 同步 ───→   files/bridge/in/   ── bind ──→  /bridge/in
└── out/   ←── 同步 ────   files/bridge/out/  ── bind ──→  /bridge/out
```

### 为什么必须走 SAF

实测（targetSdk 36、只声明 `INTERNET`）：`ls /sdcard/` 直接 **Permission denied**。
分区存储下 App 够不着公共目录的真实路径。

要访问用户可选的目录只有三条路：

| 方案 | 代价 |
|---|---|
| App 私有目录 | 用户看不见 |
| 公共目录 + `MANAGE_EXTERNAL_STORAGE` | App 能读写**手机上所有文件** —— 拿安全换便利，方向反了 |
| **SAF 授权目录** | 用户要走一次系统选择器 |

选第三条。但 SAF 给的是 `content://` URI，**proot 要 bind mount 必须用真实路径** ——
所以中间必须有这一层镜像。

### 为什么是两个单向目录

不是「一个文件夹拆成两个」的妥协，而是**权限也变成了单向的**：

- Agent 对 `in/` 只读 → 改不了用户放进去的原始文件
- Agent 对 `out/` 只写 → 伪造不了「用户之前给过的文件」
- 单向同步**没有双向冲突**要处理

`in/` 的「只读」是**约定而非强制**：proot 的 bind 不支持只读挂载，且容器与应用同属
一个 uid，没法用文件权限区分。真正的保障是**用户手机上的 SAF 目录才是源头**，
Agent 改的只是镜像副本，原件不受影响。

### 延迟

镜像有延迟：用户往 `in/` 丢文件后，Agent 要等 App 同步才看得到。目前的触发时机是
**切到通道页时**、**启动容器前**、以及手动点「立即同步」。

## Agent 知道自己在哪

App 会把一份「运行环境说明」部署到容器的 `~/.claude/CLAUDE.md`，告诉 Agent：
它在 Android 上的 proot 容器里、`/bridge/in` 和 `/bridge/out` 是唯一的文件通道、
**产出必须放 `/bridge/out/` 否则用户永远看不到**、以及它不能装 APK（要交给用户装）。

这不是锦上添花，是**可用性的必要条件**：不说的话 Agent 会把结果写进 `/root/`，
然后告诉你「完成了」，而用户在手机上永远找不到。

实测（Claude Code 2.1.283）它读完之后会主动复述这类约定。**安全边界因此从「一堵墙」
变成「一条规则」** —— Agent 是在配合这个模型工作，而不是反复撞墙报错。

部署用「托管文件」策略：记下上次写入内容的哈希，只有当文件不存在、或内容仍等于我们
上次写的那份（说明用户没改过）时才覆盖。用户一旦动过，我们就让路。

## 指令白名单：容器与手机之间的边界

容器内部是放开的（容器就是沙箱），**但 Agent 伸手到手机上的每一个动作都必须被明确允许**。
这条边界实现在三处：

```
容器内 Agent
   ↓  phone notify "标题" "正文"        ← bash 内建 /dev/tcp，零依赖
TCP 127.0.0.1:<随机端口> + 随机令牌
   ↓                                   ← 令牌：只写在 App 私有目录，其它应用拿不到
PhoneServer：校验令牌 → 查白名单
   ↓                                   ← ★ 白名单：真正的边界
Android API（通知 / 闹钟 / …）
```

**默认拒绝**。不在表里的指令一律拒绝，并回一句**可操作**的理由：

```
phone: 没有名为 `open_app` 的指令。用 `phone list` 看有哪些可用。
phone: alarm 的时间格式应为 HH:MM（24 小时制），收到的是 `99:99`
```

这不是礼貌问题：**一个被困住的 Agent 会反复重试同一条错误路径**，而一句好错误信息能让它
自己换方案，或者直接告诉用户。

白名单在 App 的**设置页**可以逐条开关 —— 一个无法配置的安全机制等于没有安全机制，
那只是「作者认为安全」，不是「用户能控制的安全」。

### 为什么 `phone` 是 bash + `/dev/tcp`

| 方案 | 问题 |
|---|---|
| Unix domain socket | Android 的 Java API **不支持文件系统上的**（`LocalServerSocket` 用的是抽象命名空间，Node 侧连接支持差） |
| socat / nc | 容器里不一定装了，为一个 CLI 去装包不划算 |
| Node | 能连，但会让这个 CLI 依赖 Node 存在 |
| **bash `/dev/tcp`** | **零依赖，直接可用** |

安全性由随机令牌补上：端口每次随机分配、令牌每次随机生成。

### 协议里两个踩过的坑

**一、必须显式绑 `127.0.0.1`。** `InetAddress.getLoopbackAddress()` 在桌面 JVM 返回
`127.0.0.1`，**在 Android 返回 `::1`** —— 于是服务器只监听 IPv6，而 CLI 连 IPv4，
报 `Connection refused`。

**二、参数走 URL-safe base64。** 手写 JSON 转义在 shell 里极易出错，而 Android 的
`org.json` 会把标准 base64 里的 `/` 转义成 `\/`，破坏 CLI 的提取正则 ——
**而且 `/` 是否出现取决于内容，所以那个 bug 是间歇性的**，同样的代码有的命令成功、
有的失败。URL-safe base64 的字符集里没有 `/`，从源头消除这个问题。

## 性能：为什么装包这么慢

**proot 是 ptrace 实现的，被追踪进程的每个系统调用都要经过拦截与路径翻译。**
I/O 密集的操作会被显著放大，`dpkg` 是其中之最（大量小文件读写 + 硬链接 + 维护脚本）。

实测：在 Redmi Note 8 上 `apt-get install -y nodejs npm` 会牵扯到 **665 个包、
约 177MB**，解包阶段耗时以十分钟计。这不是故障，是 proot 的固有代价。

两条能实际提速的做法：

**一、不要设 `PROOT_NO_SECCOMP`。** 它是性能开关而非兼容开关 —— 设了它，proot 就得用
`PTRACE_SYSCALL` 拦截每一个系统调用，而不是只拦截 seccomp 过滤出的少数几个。真机基准
（`find /usr/share/doc -type f`）显示关掉后快约 40%，dpkg 这类操作放大得更多。
本项目默认**不设**它；只有某台设备上 proot 因 seccomp 冲突启动失败时才按需打开。

**二、保留 apt 缓存。** 已下载的 `.deb` 留在 `rootfs/var/cache/apt/archives/`，
重试安装时不必重新下载（177MB 在 proot 下要下很久）。所以**除非必要不要清空应用数据** ——
那会连同 rootfs 一起丢掉。

安装被中断后（proot 慢，中断是常事），用界面上的 **「修复 dpkg」** 按钮
（即 `dpkg --configure -a`）把状态拉回一致，然后重试安装即可。

## 延伸阅读：踩过的坑

[`docs/pitfalls.md`](docs/pitfalls.md) 记录了本项目在真机上撞过的每一个
「看起来正常、跑起来不对」的问题 —— 共十几条，**其中大部分都有极具误导性的表象**。

其中一节专门讲**「只在新环境第一次运行时才出现」的五个坑**（`xz` 缺失、
DNS 没配、管道吃掉退出码、下载不支持续传、参数插错位置）—— 这一组最值得读，
因为它们在开发者的机器上永远测不出来。

文档里每条都附了「怎么发现的」，因为**发现方式往往比结论更有用**。

---

## 路线图

v1 把三条通道打通了。下面是**明确没做**的部分，以及为什么。

### 1. 持久化

容器与终端会话现在随 App 生命周期走：系统回收应用，容器就停了，终端历史也没了。
（Agent 的对话本身可以用 `claude --continue` 找回，因为它在容器的文件系统里。）

要做的是前台服务 + 会话重建。

### 2. 更丰富的手机指令

现在只有 `notify` 和 `alarm`。加一条指令的**代码成本很低**（CLI 加一个 case、
`PhonePolicy.COMMANDS` 加一行、`PhoneServer` 加一个实现），但**每条都要先想清楚
它的参数空间**，而不是只决定「允不允许用这条」。

比如「发通知」看着无害，但 Android 上任何有 `NotificationListenerService` 权限的
应用都能读到通知内容 —— 所以它其实是一条**外泄通道**。

`open_app`、读通讯录、发短信属于「要么能被用作外泄、要么涉及资费与身份冒用」的
类别，需要单独设计，不适合顺手加。

### 3. 支持其他 Agent 框架

边界机制本来就是 Agent 无关的：`phone` 是个 CLI、`/bridge` 是两个目录、
环境说明是一份 Markdown。**换 Agent 只需改两处**：

- `AgentInstaller` —— 现在写死了「装 Node + 装 `@anthropic-ai/claude-code`」
- `assets/env/CLAUDE.md` —— 现在是 Claude Code 的格式约定

把这两处抽象出接口，就能接别的框架。

### 4. API key 的设置界面

**这是 v1 最明显的缺口**：应用里没有任何地方能填 API key，只能手动把
`settings.json` 放进容器。自己用没问题，但**别人装完会卡在这一步 —— 他知道要填
key，却没地方填。**

做法很直接：一个输入框，写进 `~/.claude/settings.json` 时**只合并 `env` 那一段、
不碰其它字段**。

### 5. 其他

- **多 rootfs 支持** —— 现在只认 `assets/rootfs/` 下那一个；`AssetInstaller` 里的
  路径与格式假设（`.tar.gz`）需要一并抽象
- **容器内编译复杂 Android 项目** —— 现在跳过 Gradle，直接调 `aapt2` / `d8` /
  `apksigner`。走 Gradle + AGP 会撞上 `zipalign`（Termux 没有这个包）和大量依赖
  下载，尚未验证
- **release 构建与签名** —— 目前只产 debug APK；`build-apk.sh` 里跳过 zipalign
  也是同一个原因

---

## 许可

GPL-3.0。proot 本身是 GPL-2.0+，随 APK 捆绑分发时这是唯一省心的选择。
