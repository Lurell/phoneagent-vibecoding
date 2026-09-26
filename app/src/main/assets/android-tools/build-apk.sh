#!/bin/bash
#
# 在手机上的 Linux 容器里编出一个能安装的 APK —— 不需要 Gradle，不需要 Android Studio。
#
# ## 为什么不用 Gradle
#
# Gradle + AGP 会把问题放大好几倍：需要完整的 SDK 目录布局、需要 zipalign
# （**Termux 没有这个包**）、还要下载几百 MB 依赖。而「编出一个 APK」本身
# 只需要四个步骤，每一步都有 aarch64 可用的工具。
#
# ## 为什么这些工具在 aarch64 上能用
#
# Google 官方的 Linux 构建工具是 x86_64 的，在手机容器里跑不了。
# 但拆开看，只有 aapt2 是原生二进制，其余都是纯 Java：
#
#   aapt2      ← Termux 为 aarch64 编译的版本（bionic 链接，靠 /system 与 /apex 运行）
#   javac      ← JDK 自带，Adoptium 有 linux-aarch64 版
#   d8         ← 纯 Java（build-tools 里的 d8.jar）
#   apksigner  ← 纯 Java（build-tools 里的 apksigner.jar）
#   android.jar← 纯 Java 字节码（platform 包）
#
# 用法: build-apk.sh [项目目录]
#
set -euo pipefail

TOOLS=/opt/android-tools
JAVA="$TOOLS/jdk/bin/java"
JAVAC="$TOOLS/jdk/bin/javac"
JAR="$TOOLS/jdk/bin/jar"
KEYTOOL="$TOOLS/jdk/bin/keytool"
AAPT2="$TOOLS/bin/aapt2"
AAPT2_LIB="$TOOLS/aapt2-lib"
ANDROID_JAR="$TOOLS/android.jar"
D8_JAR="$TOOLS/lib/d8.jar"
APKSIGNER_JAR="$TOOLS/lib/apksigner.jar"

MIN_SDK=24

# aapt2 是 Termux 编译的 **bionic** 二进制，它的依赖库同样链接 Android 的 libc；
# 而 JDK 是 **glibc** 的。把 aapt2 的库目录放进全局 LD_LIBRARY_PATH 会让
# JVM 去加载 bionic 版的 libz 之类 —— 直接崩。
#
# 所以只在调用 aapt2 的那一条命令上设 LD_LIBRARY_PATH，其余一律不设。
# 这和 proot 自己用 LD_LIBRARY_PATH 是同一个道理，但作用域必须分开。
run_aapt2() {
  LD_LIBRARY_PATH="$AAPT2_LIB" "$AAPT2" "$@"
}

PROJ="${1:-.}"
PROJ="$(cd "$PROJ" && pwd)"
OUT="$PROJ/build"
APP_NAME="app.apk"

step() { echo; echo "── $* ──"; }
die()  { echo "错误: $*" >&2; exit 1; }

for f in "$JAVA" "$JAVAC" "$AAPT2" "$ANDROID_JAR" "$D8_JAR" "$APKSIGNER_JAR"; do
  [ -e "$f" ] || die "缺少工具: $f"
done
[ -f "$PROJ/AndroidManifest.xml" ] || die "$PROJ 下没有 AndroidManifest.xml"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

# ── 1. 链接清单与资源 ──────────────────────────────────────────────────────
# 这一步产出 APK 的骨架：二进制格式的 AndroidManifest.xml（加上 resources.arsc，
# 如果项目有 res/ 的话）。注意直接产出的是 .apk，后面往里塞 classes.dex。
step "1/5  aapt2 处理清单与资源"
RES_ARGS=()
if [ -d "$PROJ/res" ]; then
  run_aapt2 compile --dir "$PROJ/res" -o "$OUT/res.zip"
  RES_ARGS+=("$OUT/res.zip")
fi
run_aapt2 link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$PROJ/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version 34 \
  --version-code 1 --version-name 1.0 \
  "${RES_ARGS[@]}"
echo "  骨架 APK: $(stat -c %s "$OUT/base.apk") 字节"

# ── 2. 编译 Java ───────────────────────────────────────────────────────────
step "2/5  javac 编译 Java 源码"
SOURCES=$(mktemp)
find "$PROJ/src" "$OUT/gen" -name "*.java" 2>/dev/null > "$SOURCES" || true
[ -s "$SOURCES" ] || die "找不到任何 .java 源文件（$PROJ/src）"
wc -l < "$SOURCES" | xargs echo "  源文件数:"
# 用 --release 而不是 -source/-target：后者在新 JDK 上会报「已过时」并可能被移除。
# android.jar 放在 classpath 提供 Android API；--release 限制 JDK API 面。
"$JAVAC" --release 11 -nowarn \
  -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" \
  @"$SOURCES"
echo "  编译产物: $(find "$OUT/classes" -name '*.class' | wc -l) 个 .class"

# ── 3. 转成 dex ────────────────────────────────────────────────────────────
step "3/5  d8 把 .class 转成 classes.dex"
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 \
  --lib "$ANDROID_JAR" \
  --min-api "$MIN_SDK" \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name "*.class")
[ -f "$OUT/dex/classes.dex" ] || die "d8 没有产出 classes.dex"
echo "  classes.dex: $(stat -c %s "$OUT/dex/classes.dex") 字节"

# ── 4. 把 dex 塞进 APK ─────────────────────────────────────────────────────
step "4/5  打包进 APK"
if command -v zip >/dev/null 2>&1; then
  (cd "$OUT/dex" && zip -q -u "$OUT/base.apk" classes.dex)
else
  # 容器里没有 zip 时退回 JDK 自带的 jar。它会额外写一个 META-INF/MANIFEST.MF，
  # 对 APK 无害（签名时会重新写 META-INF）。
  (cd "$OUT/dex" && "$JAR" uf "$OUT/base.apk" classes.dex)
fi

# ── 5. 签名 ────────────────────────────────────────────────────────────────
step "5/5  签名"
KS="$TOOLS/debug.keystore"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -keystore "$KS" \
    -alias androiddebugkey \
    -storepass android -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
  echo "  已生成调试密钥库"
fi
"$JAVA" -jar "$APKSIGNER_JAR" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --min-sdk-version "$MIN_SDK" \
  --out "$OUT/$APP_NAME" "$OUT/base.apk"

"$JAVA" -jar "$APKSIGNER_JAR" verify --min-sdk-version "$MIN_SDK" "$OUT/$APP_NAME" \
  && echo "  签名校验通过"

echo
echo "完成: $OUT/$APP_NAME"
ls -l "$OUT/$APP_NAME" | awk '{print "  大小: " $5 " 字节"}'
