#!/usr/bin/env bash
#
# 拉取 phoneagent 需要的全部外部资产。跑一次即可，产物不进版本库（见 .gitignore）。
#
#   app/src/main/jniLibs/arm64-v8a/libproot.so          proot 主程序
#   app/src/main/jniLibs/arm64-v8a/libproot_loader.so   proot 的 loader
#   app/src/main/assets/proot/lib/libtalloc.so.2        运行期依赖
#   app/src/main/assets/proot/lib/libandroid-shmem.so   运行期依赖
#   app/src/main/assets/rootfs/ubuntu-24.04-base-arm64.tar.gz
#   app/src/main/assets/ca/ca-certificates.crt          CA 证书链（Ubuntu base 镜像不含）
#
# ── 为什么 proot 必须用 Termux 的构建 ──────────────────────────────────────
# proot 拦截被追踪进程的 execve 后，会把执行目标改写成它自己的一个 loader 程序。
# 上游 proot（含 Alpine 的 proot-static）把这个 loader 打包在二进制内部，运行时
# 解压到临时目录并要求该目录可执行 —— 在 Android 10+ 上不存在「既可写又可执行」
# 的目录，必然失败。
#
# Termux 的 fork 编译时带了 PROOT_UNBUNDLE_LOADER，loader 是独立文件，可以用
# PROOT_LOADER 环境变量指向。这是 unrooted Android 上唯一可行的路线。
# Alpine 的 proot 没有这个选项，所以不能用 —— 别换源。
#
# ── 为什么 proot 和 loader 放 jniLibs，而依赖库放 assets ──────────────────
# APK 安装器只会把 lib*.so 模式的 native 库解压到 nativeLibraryDir。那里是
# apk_data_file，SELinux 在「任何 targetSdk」下都允许 execve，是 W^X 规则之外
# 的口子。proot 与 loader 需要被 execve，所以必须住那里；而 jniLibs 不允许
# libtalloc.so.2 这种名字（不以 .so 结尾）。
#
# 两个依赖库只是被动态链接器 mmap，不需要 execve —— app_data_file 有 execute
# 权限、只是缺 execute_no_trans。所以它们放 assets、解到数据目录即可。
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

JNI_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"
LIB_DIR="$ROOT/app/src/main/assets/proot/lib"
ROOTFS_DIR="$ROOT/app/src/main/assets/rootfs"
CA_DIR="$ROOT/app/src/main/assets/ca"
WORK="$ROOT/.toolchain/assets-work"

TERMUX_REPO="https://packages.termux.dev/apt/termux-main"

# 版本与校验和全部写死。proot 5.1.107.95 / libtalloc 2.4.3 / libandroid-shmem 0.7
PROOT_URL="$TERMUX_REPO/pool/main/p/proot/proot_5.1.107.95_aarch64.deb"
PROOT_SHA="0a1b3d0f6ef76436c5ed924cd8e8f5a6b7186e99e1650eb2d9bc734e218a74cb"

TALLOC_URL="$TERMUX_REPO/pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb"
TALLOC_SHA="ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da"

SHMEM_URL="$TERMUX_REPO/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb"
SHMEM_SHA="0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6"

UBUNTU_ROOTFS_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"
UBUNTU_ROOTFS_SHA="a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"

# Mozilla 的 CA 证书链。Ubuntu base 镜像不含 ca-certificates，缺了它容器里
# 一切 HTTPS 都会报 "certificate issuer is unknown"。
CACERT_URL="https://curl.se/ca/cacert.pem"
CACERT_SHA="a41b5d356aea97a529fe27e0f7316d2f9d946d75927476cf9cf1b90637d00505"

# ── 前置检查 ───────────────────────────────────────────────────────────────
need() {
  command -v "$1" >/dev/null 2>&1 || { echo "缺少依赖: $1 —— $2" >&2; exit 1; }
}
need curl "用于下载"
need tar  "用于解开 deb 与 rootfs"
need xz   "用于解 .tar.xz（Termux 的 deb 内部是 xz）"

# 解 deb 需要 ar 或 node 二者之一
DEB_TOOL=""
if command -v ar >/dev/null 2>&1; then
  DEB_TOOL="ar"
elif command -v node >/dev/null 2>&1; then
  DEB_TOOL="node"
else
  echo "需要 ar (binutils) 或 node 之一来解开 .deb" >&2
  exit 1
fi
echo "解 deb 方式: $DEB_TOOL"

mkdir -p "$JNI_DIR" "$LIB_DIR" "$ROOTFS_DIR" "$CA_DIR" "$WORK"

# 下载并校验。校验失败立即中止 —— 这些二进制会以可执行身份进入 APK。
fetch() {
  local url="$1" sha="$2" out="$3"
  if [ -f "$out" ] && echo "$sha  $out" | sha256sum -c --status 2>/dev/null; then
    echo "  已存在且校验通过: $(basename "$out")"
    return
  fi
  echo "  下载 $(basename "$out")"
  curl -fSL --retry 3 --max-time 600 -o "$out" "$url"
  echo "$sha  $out" | sha256sum -c - >/dev/null || {
    echo "!! 校验和不匹配: $out" >&2
    echo "!! 期望 $sha" >&2
    echo "!! 实际 $(sha256sum "$out" | cut -d' ' -f1)" >&2
    rm -f "$out"
    exit 1
  }
}

# 从 deb 里取出 data.tar.xz，并解开到指定目录
extract_deb() {
  local deb="$1" dest="$2"
  rm -rf "$dest"
  mkdir -p "$dest"
  if [ "$DEB_TOOL" = "ar" ]; then
    ar p "$deb" data.tar.xz > "$dest/data.tar.xz"
  else
    node "$SCRIPT_DIR/deb-extract.mjs" "$deb" data.tar.xz "$dest/data.tar.xz"
  fi
  # 用 xz -dc | tar 而不是 tar -xJf，少依赖 tar 的编译选项
  xz -dc "$dest/data.tar.xz" | tar -x -C "$dest" 2>/dev/null || true
  rm -f "$dest/data.tar.xz"
}

echo
echo "=== 1/4  proot 主程序 ==="
fetch "$PROOT_URL" "$PROOT_SHA" "$WORK/proot.deb"
extract_deb "$WORK/proot.deb" "$WORK/proot"
TERMUX_USR="$WORK/proot/data/data/com.termux/files/usr"

install -m 0755 "$TERMUX_USR/bin/proot" "$JNI_DIR/libproot.so"
install -m 0755 "$TERMUX_USR/libexec/proot/loader" "$JNI_DIR/libproot_loader.so"
echo "  -> libproot.so ($(stat -c %s "$JNI_DIR/libproot.so") 字节)"
echo "  -> libproot_loader.so ($(stat -c %s "$JNI_DIR/libproot_loader.so") 字节)"
# 注意：libexec/proot/loader32 是 32 位 ARM 的 loader，且只有 4K 页对齐，
# 在 Android 15+ 的 16KB 页设备上会失败。我们只跑 64 位 guest，不带它。

echo
echo "=== 2/4  libtalloc ==="
fetch "$TALLOC_URL" "$TALLOC_SHA" "$WORK/libtalloc.deb"
extract_deb "$WORK/libtalloc.deb" "$WORK/libtalloc"
# 按 SONAME 落地：proot 的 DT_NEEDED 写的是 libtalloc.so.2，
# 而包里的文件名是 libtalloc.so.2.4.3。
install -m 0644 \
  "$WORK/libtalloc/data/data/com.termux/files/usr/lib/libtalloc.so.2.4.3" \
  "$LIB_DIR/libtalloc.so.2"
echo "  -> libtalloc.so.2 ($(stat -c %s "$LIB_DIR/libtalloc.so.2") 字节)"

echo
echo "=== 3/4  libandroid-shmem ==="
fetch "$SHMEM_URL" "$SHMEM_SHA" "$WORK/libandroid-shmem.deb"
extract_deb "$WORK/libandroid-shmem.deb" "$WORK/libandroid-shmem"
install -m 0644 \
  "$WORK/libandroid-shmem/data/data/com.termux/files/usr/lib/libandroid-shmem.so" \
  "$LIB_DIR/libandroid-shmem.so"
echo "  -> libandroid-shmem.so ($(stat -c %s "$LIB_DIR/libandroid-shmem.so") 字节)"

echo
echo "=== 4/5  Ubuntu 24.04 LTS rootfs (aarch64) ==="
fetch "$UBUNTU_ROOTFS_URL" "$UBUNTU_ROOTFS_SHA" "$ROOTFS_DIR/ubuntu-24.04-base-arm64.tar.gz"
echo "  -> $(stat -c %s "$ROOTFS_DIR/ubuntu-24.04-base-arm64.tar.gz") 字节"

echo
echo "=== 5/5  Mozilla CA 证书链 ==="
fetch "$CACERT_URL" "$CACERT_SHA" "$CA_DIR/ca-certificates.crt"
echo "  -> $(stat -c %s "$CA_DIR/ca-certificates.crt") 字节"

echo
echo "全部资产就位："
ls -l "$JNI_DIR" "$LIB_DIR" "$ROOTFS_DIR" "$CA_DIR"
