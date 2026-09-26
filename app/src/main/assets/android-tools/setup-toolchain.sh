#!/bin/bash
#
# 在容器里装配 Android 构建工具链。由 App 调用，不要手动运行。
#
# 期待的输入（都在 /tmp 下）：
#   jdk21.tar.gz        JDK 21（aarch64 Linux）
#   aapt2.deb           Termux 的 aapt2 + 下面这些依赖
#   <8 个依赖>.deb      abseil-cpp / libprotobuf / fmt / libc++ / libexpat / libpng / libzopfli / zlib
#
# 由 App 侧直接放好的（zip 在 Java 里解，容器里没有 unzip）：
#   android.jar         /opt/android-tools/android.jar
#   d8.jar              /opt/android-tools/lib/d8.jar
#   apksigner.jar       /opt/android-tools/lib/apksigner.jar
#
# 产出：
#   /opt/android-tools/jdk/          JDK
#   /opt/android-tools/bin/aapt2
#   /opt/android-tools/aapt2-lib/    aapt2 的 105 个依赖库
#   /opt/android-tools/bin/build-apk.sh
#
set -euo pipefail

TOOLS=/opt/android-tools
TMP=/tmp

say() { echo "  $*"; }
die() { echo "错误: $*" >&2; exit 1; }

mkdir -p "$TOOLS/bin" "$TOOLS/lib" "$TOOLS/jdk" "$TOOLS/aapt2-lib"

# ── 1. JDK ─────────────────────────────────────────────────────────────────
if [ ! -x "$TOOLS/jdk/bin/java" ]; then
  [ -f "$TMP/jdk21.tar.gz" ] || die "缺少 $TMP/jdk21.tar.gz"
  say "解压 JDK（约 200MB，proot 下需要一两分钟）"
  rm -rf "$TOOLS/jdk"
  mkdir -p "$TOOLS/jdk"
  tar -xzf "$TMP/jdk21.tar.gz" -C "$TOOLS/jdk" --strip-components=1
  rm -f "$TMP/jdk21.tar.gz"
fi
[ -x "$TOOLS/jdk/bin/java" ] || die "JDK 解压后仍找不到 java"
say "JDK: $("$TOOLS/jdk/bin/java" -version 2>&1 | head -1)"

# ── 2. aapt2 与它的依赖 ────────────────────────────────────────────────────
# Termux 的包用 dpkg -x 展开 —— 只解包，不安装，不会污染容器。
#
# 注意：aapt2 是 **bionic** 二进制（依赖 Android 的 libc，靠 /system/bin/linker64 运行），
# 而 JDK 是 **glibc** 的。两者的库路径必须严格分开，绝不能合并到同一个
# LD_LIBRARY_PATH —— 否则 JVM 会去加载 bionic 版的 libz 之类然后崩掉。
# build-apk.sh 里只为 aapt2 那一条命令设 LD_LIBRARY_PATH，就是这个原因。
if [ ! -x "$TOOLS/bin/aapt2" ]; then
  say "展开 Termux 的 aapt2 与依赖"
  rm -rf "$TMP/tx" && mkdir -p "$TMP/tx"
  found=0
  for d in "$TMP"/*.deb; do
    [ -f "$d" ] || continue
    dpkg -x "$d" "$TMP/tx"
    found=$((found + 1))
  done
  [ "$found" -gt 0 ] || die "找不到任何 .deb"

  # 包里统一是 data/data/com.termux/files/usr/... 这个前缀
  PREFIX="$TMP/tx/data/data/com.termux/files/usr"
  [ -d "$PREFIX" ] || die "deb 解出来的目录结构不对"

  cp -f "$PREFIX/bin/aapt2" "$TOOLS/bin/aapt2" 2>/dev/null || die "aapt2 不在预期位置"
  cp -f "$PREFIX"/lib/*.so* "$TOOLS/aapt2-lib/" 2>/dev/null || true
  chmod +x "$TOOLS/bin/aapt2"

  # 按 SONAME 补齐文件名。
  #
  # Linux 包里一个库通常有「真文件 + SONAME 软链」两份名字，而动态链接器只认 SONAME。
  # 跨平台搬运时软链会丢，结果就是 `library "libz.so.1" not found`，
  # 而 libz.so.1.3.2 明明就躺在同一个目录里 —— 这个错误信息非常误导人。
  #
  # 这里直接在容器里判断：对每个「看起来是 SONAME」的缺失名字补一份拷贝。
  # 容器里没有 readelf，所以用「文件名前缀匹配」这个更笨但可靠的办法。
  say "补齐 SONAME 名字"
  for f in "$TOOLS/aapt2-lib"/*; do
    [ -f "$f" ] || continue
    base=$(basename "$f")
    case "$base" in
      *.so) continue ;;
    esac
    # libfoo.so.1.2.3 -> 候选 SONAME 是 libfoo.so.N
    soname=$(echo "$base" | sed -n 's/^\(.*\.so\.[0-9]*\)\.[0-9.]*$/\1/p')
    if [ -n "$soname" ] && [ ! -e "$TOOLS/aapt2-lib/$soname" ]; then
      cp -f "$f" "$TOOLS/aapt2-lib/$soname"
      say "  $base -> $soname"
    fi
  done

  rm -rf "$TMP/tx" "$TMP"/*.deb
fi
say "aapt2: $(LD_LIBRARY_PATH=$TOOLS/aapt2-lib "$TOOLS/bin/aapt2" version 2>&1 | head -1)"
say "aapt2 依赖库: $(ls "$TOOLS/aapt2-lib" | wc -l) 个"

# ── 3. 构建脚本 ────────────────────────────────────────────────────────────
if [ -f "$TMP/build-apk.sh" ]; then
  cp -f "$TMP/build-apk.sh" "$TOOLS/bin/build-apk.sh"
  rm -f "$TMP/build-apk.sh"
fi
chmod +x "$TOOLS/bin/build-apk.sh" 2>/dev/null || true
say "构建脚本: $TOOLS/bin/build-apk.sh"

# ── 4. 收尾检查 ────────────────────────────────────────────────────────────
for f in "$TOOLS/jdk/bin/java" "$TOOLS/jdk/bin/javac" "$TOOLS/bin/aapt2" \
         "$TOOLS/android.jar" "$TOOLS/lib/d8.jar" "$TOOLS/lib/apksigner.jar"; do
  [ -e "$f" ] || die "装配后仍缺少: $f"
done

echo "工具链就绪"
