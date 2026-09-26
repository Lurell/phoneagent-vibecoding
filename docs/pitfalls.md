# 踩过的坑

这份文档记录本项目在真机上撞过的每一个「看起来正常、跑起来不对」的问题。

写下来的理由很实际：**其中大部分都有极具误导性的表象**，如果不知道背后的机制，
下次遇到同样的报错会往完全错误的方向排查。每条都附了「怎么发现的」，因为
**发现方式本身往往比结论更有用**。

---

## 一、Android 的 W^X：为什么不退回 targetSdk 28

### 现象

`targetSdk >= 29` 的应用，`execve()` 自己数据目录里的文件会被拒绝。

### 机制

SELinux 策略（`system/sepolicy`，Android 15 与 16 的分支逐字相同）：

```
# private/untrusted_app_all.te
allow untrusted_app_all app_data_file:file { r_file_perms execute };

# private/app_neverallows.te
neverallow { all_untrusted_apps -untrusted_app_25 -untrusted_app_27 -runas_app }
          { app_data_file privapp_data_file }:file execute_no_trans;
```

`execute` **是给了的**，缺的是 `execute_no_trans`。没有类型转换时 `execve` 会额外检查
它，所以被杀；而 **`mmap(PROT_EXEC)` 只看 `execute` —— 这条是通的**。

也就是说：**数据目录里的代码不能被「执行」，但可以被「映射」。**

### 本项目的做法

proot 拦截被追踪进程的 `execve` 后，会把执行目标改写成它自带的 loader；
**这个 loader 用裸系统调用把 ELF 映射进内存再跳转，从不 `execve` 目标程序**。
于是 rootfs 里的 `/bin/sh` 全程只被 mmap，`execute_no_trans` 根本没被查过。

再把 proot 与 loader 放进 `jniLibs`（落到 `nativeLibraryDir`，标签是
`apk_data_file`，`execute_no_trans` 也给了），全程就没有任何一次针对
`app_data_file` 的 `execve`。

### 怎么验证的

不看文档，看**内核自己的审计日志**。容器跑起来时 logcat 里是：

```
avc: granted { execute } for name="uname"
    tcontext=u:object_r:app_data_file:s0  tclass=file
```

全部是 `granted`，而且目标是 `app_data_file` —— 机制成立的直接证据。

---

## 二、proot 的 loader 必须能被执行

### 现象

stock proot（含 Alpine 的 `proot-static`）在 Android 10+ 上**必然失败**，报
「it seems the current temporary directory is mounted with no execution permission」。

### 机制

proot 把 loader 打包在自己内部，运行时解压到临时目录并要求那个目录可执行。
**Android 10+ 上不存在「既可写又可执行」的目录**，所以这条路是死的。

Termux 的 fork 编译时带了 `PROOT_UNBUNDLE_LOADER`，loader 是独立文件、
可用 `PROOT_LOADER` 环境变量指向 —— 这是唯一可行的路线。**别换源。**

---

## 三、网络：三个必然踩的坑

### 3.1 DNS 是空的

Android 自己用 netd 解析域名，**根本不读 `/etc/resolv.conf`**，所以新 rootfs 里
那个文件是 0 字节。

而且 **Android 报上来的第一条 DNS 不能直接用**：实测 Redmi Note 8 首选
`fe80::1%wlan0` —— 带作用域后缀的链路本地 IPv6，glibc 处理这种格式不稳定，
会让容器里所有域名解析直接超时。必须过滤掉 `fe80::/10`。

### 3.2 rootfs 里没有 CA 证书链

Ubuntu 的 base 镜像是 `debootstrap --variant=minbase` 构建的，**不含 `ca-certificates`**，
连 `/etc/ssl` 目录都没有。于是 apt 走 HTTPS 时报
`Certificate verification failed: The certificate issuer is unknown`。

这是个鸡生蛋问题（要用 apt 装 ca-certificates，而 apt 自己就需要它），
所以随包带一份 Mozilla 证书链直接写进去。

### 3.3 错误链极具迷惑性

这三个问题串起来是一条**顺序出现的错误链**，排查时按这个顺序看能少走弯路：

```
Temporary failure resolving          ← DNS
  → 修好后变成 certificate is NOT trusted   ← CA
    → 再修好才是网络本身的事
```

---

## 四、平台差异：「看起来可移植」的 API

### 4.1 `InetAddress.getLoopbackAddress()` 在 Android 上返回 `::1`

| 平台 | 返回 |
|---|---|
| 桌面 JVM | `127.0.0.1` |
| **Android** | **`::1`** |

于是服务器只监听 IPv6，而客户端连 IPv4 → `Connection refused`。

**排查方式值得记**：一开始怀疑「服务器没起来」，但
`/proc/net/tcp6` 里那条 `0A`（LISTEN）直接排除了这个可能，把范围缩到「绑错地址」。
**先看内核状态，比读代码快。**

> 顺带更正一个容易搞错的细节：内核把 IPv4 socket 以 **v4-mapped 形式**
> （`::ffff:127.0.0.1`）报在 `tcp6` 表里，所以「`/proc/net/tcp` 为空」不代表没监听。

### 4.2 `org.json` 会把 base64 里的 `/` 转义成 `\/`

JSON 允许但不要求这种转义，而 Android 的 `org.json` 默认会做。

于是：base64 里出现 `/`（约一半概率）→ 被转义成 `\/` → CLI 侧的提取正则
（只认 base64 字符集）匹配失败 → 报「响应无法解析」。

**这个 bug 是间歇性的**：同样的代码，有的命令成功、有的失败。

修法是从源头消除：**改用 URL-safe base64**（`-` `_` 代替 `+` `/`），
字符集里没有 `/`，JSON 就永远不需要转义。

### 4.3 `android.system.Os` 的 read/write 收的是 `FileDescriptor` 对象，不是 int

而把 int fd 变成 `FileDescriptor` 有个**不需要反射**的正解：
**`ParcelFileDescriptor.adoptFd(int)`**（公开 API，专为接管已有 fd 而设）。

上游 Termux 的 `TerminalSession` 用的是反射私有字段 + `System.exit(1)` 的失败路径 ——
那在现代 targetSdk 上会被隐藏 API 限制拦掉，而**失败路径会直接杀掉整个应用进程**。

### 4.4 `targetSdk` 在 AGP 9 里默认等于 `compileSdk`

不显式写就是 37。这条是「静默失效」的典型：**不报错，但能力没了**。

### 4.5 MIUI 会拦 `adb install` 与 `input tap`

`INSTALL_FAILED_USER_RESTRICTED` / `INJECT_EVENTS permission`，都需要插 SIM 卡
才能打开对应的开发者选项开关。所以「把 APK 送进手机」这件事得另找路子 ——
有两条，**第二条连 USB 调试都不用开**。

**一、`adb push` 到 `/sdcard/Download/`，用户在文件管理器里手动装。**
前提是 USB 调试已开、且已授权本机。

**二、完全不走 adb：用 Windows 的 shell 命名空间直接写 MTP。**
只要手机的 USB 用途是「传输文件」，系统就会把它挂成 WPD 设备，这条路就通：

```powershell
$sh = New-Object -ComObject Shell.Application
$pc = $sh.NameSpace(17)                       # 17 = 「此电脑」
$phone = $pc.Items() | Where-Object { $_.Name -like "*Redmi*" }
$storage = $phone.GetFolder.Items() | Where-Object { $_.IsFolder } | Select-Object -First 1
$dl = $storage.GetFolder.Items() | Where-Object { $_.Name -eq "Download" }
$dl.GetFolder.CopyHere("D:\...\app-debug.apk", 1040)   # 1040 = 不弹确认/错误框
```

怎么判断设备走的是哪条路：`adb devices` 为空但
`Get-PnpDevice` 里能看到它（Class 是 `WPD`）→ 说明**线和 USB 模式都没问题，
只是没开 USB 调试**。这个区分很有用 —— 空列表容易让人去换线，其实是开关没开。
（如果只是没授权，adb 会列出 `unauthorized`，不是空。）

⚠ **`CopyHere` 会把 `.apk` 后缀吃掉**：文件传过去叫 `app-debug`，在手机上点不开
安装。传完必须再改一次名：

```powershell
$item = $dest.Items() | Where-Object { $_.Name -eq "app-debug" }
$item.Name = "phoneagent-debug.apk"     # 对 MTP 项也能生效
```

而且 **MTP 的目录视图是缓存的** —— 改完名得重新 `New-Object -ComObject
Shell.Application` 读一遍才看得到新状态，拿旧对象读会以为改名没生效（我第一次
就是这么被骗的：复制其实早就完成了，只是列表没刷新）。

---

## 五、「全新环境」专属的五个坑

**这一组是最值得记的。** 它们全部只在「干净环境 + 第一次走某条路径」时出现，
在被反复折腾过的开发容器里一个都测不出来 —— 因为开发环境太「脏」，
脏到把所有初始化问题都盖住了。

| # | 现象 | 根因 |
|---|---|---|
| 1 | `tar (child): xz: Cannot exec` | `tar -xJf` 需要外部 `xz` 程序，而基础镜像里没有（只有 gzip，dpkg 自己依赖它）。**改用 `.tar.gz` 即可** |
| 2 | 装 Agent 时 npm 报 `EAI_AGAIN` | 容器没配 DNS，而「配 DNS」只在**启动容器**时做，**装 Agent** 时没做 |
| 3 | npm 失败了却报成功 | `npm install ... \| tail -20` —— **管道的退出码是 `tail` 的，永远是 0** |
| 4 | `IOException: unexpected end of stream` | 196MB 下载中断，而当时的逻辑是「失败就删掉重来」—— 大文件永远下不完。**必须支持 HTTP Range 续传** |
| 5 | `/usr/bin/env: '-b': No such file or directory` | 「装构建工具时才启用的 bind 参数」被接在了参数列表**末尾**，也就是 `/usr/bin/env` 之后 —— env 把 `-b` 当成了自己的参数 |

### 从这五个坑里提炼出的三条

**一、「下载成功」不等于「环境能用」。** 第 2 条里，Node 的压缩包是 **App** 下载的
（走宿主网络，不经容器），能成功；而 npm 是**容器内**跑的，需要 DNS。
**同一件事两条网络路径**，表现为「下载都好好的，只有 npm 装不上」。

**二、管道会吃掉退出码。** 任何 `cmd | tail` / `cmd | head` 都让 `$?` 变成后者的。
要么别接管道，要么记着取 `PIPESTATUS[0]`。

**三、对不稳定的网络，「失败就重来」不是健壮，是赌博。**

---

## 六、调试方法（这部分可能比结论更有用）

### 6.1 先看画面，再读代码

有一次排查「终端空白」，读了半天渲染代码没结果。**截了一张图**才发现：
终端其实一直在正常渲染，只是**白底白字**看不见。

**「先看一眼实际输出」这一步，我拖了三个来回才做。早该做。**

### 6.2 用埋点夹出断点，而不是推理

数据流有六段时，与其推理「应该是哪一段断了」，不如每段都打一行日志：

```
读取线程 → 主线程队列 → 模拟器 → 回调 → 视图 → 重绘
   ✓44字节    ？          ？      ？     ？     ？
```

缺哪一行，断点就在哪一段。**日志进 logcat 就够** —— logcat 什么都不会盖掉。

> 这里原来写的是「一定要**同时**进 UI 和 logcat」，理由是状态条只显示最后一行、
> 会把信息盖掉。那个理由是错的：**真正不会盖掉信息的是 logcat**，UI 那一份
> 纯属多余 —— 而且正是它把埋点漏给了用户（见第八节）。教训是「为了避免丢信息
> 而多写一份」的方向反了，该做的是少写一份、写对地方。

### 6.3 脚手架必须与被测对象逐字对齐

调试脚手架（`.shots/devrun.sh`）和 App 的 `ProotCommand` 曾经**两次不一致**，
方向相反：

- 第一次：脚手架漏了两个 bind，我误判成「App 的 bind 有 bug」
- 第二次：脚手架的参数顺序是对的，而 App 是错的 —— 于是**我能跑通、App 跑不通**

**脚手架和被测对象不一致，比没有脚手架更危险**，因为它会给出错误的信心。

### 6.4 区分「被拒绝」和「依赖没配好」

proot 跑不起来时，错误信息本身能说明问题：

| 报错 | 含义 |
|---|---|
| `Permission denied` | SELinux 拦了 |
| `CANNOT LINK EXECUTABLE ... library "x" not found` | **exec 成功了**，只是缺库 |
| `Segmentation fault`（loader 单独跑） | **正常现象** —— loader 不是普通程序 |

第二条尤其有用：它证明 `execve` **通过了**，问题在依赖。

---

## 七、Android 的痕迹漏进容器：那一行 groups 警告

### 现象

每次开交互式 shell，终端里都会先刷这么几行：

```
groups: cannot find name for group ID 3003
groups: cannot find name for group ID 9997
groups: cannot find name for group ID 20463
groups: cannot find name for group ID 50463
```

像报错，但容器接着一切正常。

### 机制

`groups` 要把进程的**补充组**逐个解析成名字。Android 给每个应用进程挂了几个
它自己体系里的组（这里是 `3003`=inet、`9997`=everybody，以及两个应用专属的
`20463`/`50463`），而 **proot 管不了补充组** —— 它伪造的是 uid/gid，
而补充组在非特权进程里连 `setgroups()` 都调不动（需要 `CAP_SETGID`）。
于是这些 ID 原样带进容器，而容器的 `/etc/group` 里当然没有它们，
每解析失败一个就打一行。

### 为什么不去堵 bash

触发点是 rootfs 的 `/etc/bash.bashrc` 里那段 sudo 提示：

```sh
case " $(groups) " in *\ admin\ *|*\ sudo\ *) ...
```

所以最省事的修法是在 `$HOME` 放个 `.hushlogin` 或 `.sudo_as_admin_successful`
让这段短路。**但那只是堵住了一个调用点**：`id -Gn`、`groups`、`getent group`
只要被调用就还会报。而 Agent 恰恰很可能去跑这些命令 —— **一个 Agent 看到 4 行
"cannot find name" 会以为自己身处一个坏掉的环境，甚至动手去"修"它。**

（顺带：`id` 本身**不报**。它打印数字 GID 而不解析名字，只有 `id -Gn` 这类
「要名字」的调用才会报。）

### 做法

补根因：这些组 ID **真实存在于进程上**（内核的 `/proc/<pid>/status` 里列着），
只是容器不认识它们的名字。启动容器前读 `/proc/self/status` 的 `Groups:` 行，
把 `/etc/group` 里没有的 GID 补成 `aid_<gid>:x:<gid>:`（见 `ContainerGroups`）。

用 `aid_` 前缀而不是 Android 的内部叫法（`inet`/`everybody`）：那些名字在
Linux 语境里另有含义，塞进容器的 `/etc/group` 会误导。

读 `/proc/self/status` 拿到的是**应用进程自己**的组 —— pty 里 fork 出来的
proot 原样继承，两者一致。

### 怎么验证的

在真机上给 `/etc/group` 补上这 4 行再跑一次（跑完还原）：

```
--- getent group 3003 9997 20463 50463 ---
aid_3003:x:3003:                     ← 解析成功
--- id -Gn（同一个进程里）---
root 1004 1007 ... aid_3003 ... aid_50463   ← 3003 与 50463 不再报错
```

补上的那两个 ID 从警告里消失、没补的照旧报 —— 说明警告确实由「解析不到名字」
产生，而不是被哪一处绕开了。

---

## 八、调试日志三次漏进界面

### 现象

界面上出现红色的 `[I/TerminalSession] 主线程从队列取出 88 字节，交给模拟器`。
用户看到「红色的警告」，以为出了故障 —— 其实那是我自己的埋点。

这已经是第三次了：`视图已接上模拟器`、`onTextChanged 到达` 都漏过一遍。

### 两个各自独立的原因，缺一不可

1. **埋点没清干净。** 为了夹出数据流的断点（见 6.2），在内联的 `TerminalSession`
   里加过一批中文 `logInfo`。收尾时只删了一部分，剩下的跟着 `logInfo`
   一路转到了界面上。
2. **界面把所有日志一律画成红色。** 状态条那处颜色写死 `colorScheme.error` ——
   于是「已从交互目录取回 3 个文件」和「pty 建不起来」长得一模一样，
   用户没法从颜色上分辨哪条需要管。

### 做法

- **严重程度必须是数据的一部分**（`LogLevel`），不能由渲染处一刀切。
  调用点知道自己是 INFO 还是 ERROR，界面不该猜。
- INFO 显示几秒后**自动消失**（它是进度，不是问题）；WARN / ERROR 留着，
  要用户自己点掉 —— **会消失的告警等于没有告警**。
- 排查用的埋点**只写 logcat，不进 `onLog`**。`onLog` 是给用户看的通道。

### 教训

「调试时加的日志忘了删」几乎必然发生，靠自觉不可靠。真正管用的是
**让两类日志走不同的通道** —— 走错了通道才会被用户看见。

### 追加：上游库的「error」不等于用户看得懂的问题

同一条原则后来挡下了一整类噪音。Claude Code 一启动就发一个 `CSI > 4 m`
（xterm 的 modifyOtherKeys，用来区分 ESC 和其它键），Termux 的模拟器不实现它，
于是以 **error** 级别打一条：

```
[E/TerminalEmulator] (ignored) CSI > MODIFY RESOURCE: 4 to -1
```

看着像故障，其实只是模拟器在说「这个序列我不认识」。而这类在整个文件里有
十来处（`SGR unknown code`、`Unrecognized device control string`、
`Unhandled termcap name`…），**TUI 程序越新，撞上的越多** —— 症状是每次
升级 Claude Code 都可能多出几条"红色警告"。

根子在于：**上游的日志级别是按「对终端模拟器开发者多重要」定的，不是按
「对用户多重要」定的。** 所以转发时不能照搬级别，得自己筛一次：

| tag | 含义 | 去向 |
|---|---|---|
| `TerminalSession` | pty 建不起来、读写失败 —— 环境真的坏了 | logcat **+ 界面** |
| `TerminalEmulator` | 「这个转义序列我不认识」—— 模拟器的协议覆盖度 | 只进 logcat |
| 其它 | 同上，默认不打扰用户 | 只进 logcat |

用**白名单**而不是黑名单：将来上游再加一个爱抱怨的模块，也不会自动冒到用户面前。
（上游其实有 `LOG_ESCAPE_SEQUENCES` 开关表达过同样的意图，只是有十来处
`logError` 漏了走它 —— 但即便全修好，也只解决这一类，白名单才是通用的。）

### 追加：白名单收窄之后，它立刻抓出了第二条误报

`TerminalSession` 是白名单里唯一进界面的 tag，于是它里面一条**早就存在**的误报
立刻露了出来：**每次点「结束」，界面都会报一条红色的
「pty 读取异常结束: EIO (I/O error)」**。

机制是个容易想反的 Linux 细节：**pty master 在从端全部关闭后，`read` 返回的是
`EIO` 而不是 0。** 也就是说「对方走了」这个信号是以**异常**形式送来的，而
「读到 0 就是 EOF」那条正常路径基本走不到 —— 当初那句注释正好写反了，
于是代码把最常见的正常结束当成了故障。

（另外一条是 `InterruptedIOException`：用户的 fd 被 `cleanupResources()` 关掉时，
阻塞中的 read 会抛这个。）

修法是让「收尾」和「真故障」分开，而且**必须分两层判定** ——
进程退出的消息和 pty 断开的信号，谁先到是不确定的：

| 条件 | 判定 |
|---|---|
| `mShellPid == -1`（cleanupResources 已开跑） | 一定是收尾 |
| `EIO` 或 `InterruptedIOException` | pty 已断开，也是收尾 |
| 其它 | 才是真故障 |

顺带把同一处的另外两条也一起修了：读线程的「数据入队失败」（队列只在
cleanupResources 里关，写不进去就等于会话结束）和写线程的「pty 写入失败」。

**这算是白名单的意外收获**：噪音被挡掉之后，剩下的那一条才看得见。
原来它一直混在一堆「转义序列不认识」里，谁也不会注意到。

### 再追加：结束会话时的两个顺序约束

做「优雅退出」时撞到两条，都是顺序问题 —— 搞反了不报错，只是结果不对：

**一、清屏必须等进程真正退出之后。** `TerminalSession` 在进程退出时会往屏幕上
补一行 `[Process completed (code N) - press Enter]`，位置在 `cleanupResources()`
之后、`onSessionFinished()` 之前。清早了会被它盖回来，表现为「点了结束，屏幕还是
脏的」。所以顺序是：**退出 → 等 `onSessionFinished` → 清屏**。

**二、退出之后的清屏不能再走 `session.write()`。** 那时 pty 另一头已经没有进程
在读，写进去石沉大海。只能拿 `session.getEmulator()` 直接 `append()` 一段
ANSI（`\e[2J\e[3J\e[H`；`3J` 一并清回滚缓冲，否则往回滚还能看到 TUI 残影）。

而且 `append()` 会改屏幕缓冲，渲染也在读它，**必须在主线程** —— `viewModelScope`
默认就在主线程，但这属于「碰巧对」，值得写明白，否则哪天挪到 IO 线程就会变成
偶发的花屏。

顺带：优雅退出的手势用 `Ctrl+C` + `Ctrl+D` 就够了，**不需要知道里面跑的是哪个
Agent** —— TUI 和 bash 都认 Ctrl+D 是退出。走成了退出码是 0，而不是强杀留下的
`137`（= 128+9，用户看到会以为出错了）。

---

## 九、许可与内联代码

`terminal-emulator` / `terminal-view` 这两个模块**不是 GPL**：Termux 的
`LICENSE.md` 里有一条明确例外，它们源自
[jackpal/Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator)，
采用 **Apache-2.0**。

这个区别很重要：如果是 GPL-3.0，内联会把整个应用拖入更强的传染性约束。
详见 `third_party/termux-terminal/README.md`。
