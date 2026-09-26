package io.phoneagent.container

import android.content.Context
import java.io.File

/**
 * 首次运行时把随 APK 分发的资产铺开：
 *
 *   assets/proot/lib 下的 .so  -> filesDir/proot/lib/   （mmap 即可，不需要 execve）
 *   assets/rootfs 下的 .tar.gz -> filesDir/rootfs/      （容器 /）
 *
 * 注意 proot 主程序和 loader 不在这里 —— 它们在 jniLibs 里，由安装器直接
 * 解压到 nativeLibraryDir，我们只负责读取。那边的 SELinux 标签是 apk_data_file，
 * 是唯一允许 execve 的地方。
 *
 * （写通配符时别把斜杠和星号连在一起：Kotlin 的块注释支持嵌套，
 *   注释里出现那个两字符序列会开一个永不闭合的嵌套注释，
 *   而且报错定位在文件末尾，很难排查。）
 */
class AssetInstaller(
    private val context: Context,
    private val paths: Paths,
) {

    data class Progress(val phase: String, val entries: Int, val current: String)

    companion object {
        private const val ROOTFS_ASSET_DIR = "rootfs"

        /** 容器里需要存在、但 rootfs 归档里不一定有的挂载点。 */
        private val RUNTIME_DIRS = listOf("root", "tmp", "dev", "proc", "sys", "out", "mnt")
    }

    fun isInstalled(): Boolean = paths.installMarker.isFile

    /**
     * 运行时枚举资产目录，而不是写死文件名。
     *
     * AAPT2 会改写以 .gz 结尾的资产名（构建时解压并去掉后缀），写死的路径
     * 在构建产物里可能根本不存在。枚举出来更耐操。
     */
    private fun rootfsAssetName(): String {
        val names = context.assets.list(ROOTFS_ASSET_DIR)?.filter { it.isNotBlank() } ?: emptyList()
        return names.firstOrNull { it.contains("ubuntu", ignoreCase = true) }
            ?: names.firstOrNull()
            ?: throw IllegalStateException(
                "assets/$ROOTFS_ASSET_DIR 下没有文件 —— 先跑 scripts/fetch-assets.sh 拉取 rootfs"
            )
    }

    /** 清空已安装的 rootfs（重新安装时用）。 */
    fun wipe() {
        paths.rootfs.deleteRecursively()
        paths.lib.deleteRecursively()
    }

    /**
     * 铺开全部资产。
     * @return 解压过程中出错的条目说明（空表示全部成功）
     */
    fun install(onProgress: (Progress) -> Unit): List<String> {
        paths.ensureDirs()

        // ── 1. 依赖库 ──────────────────────────────────────────────────────
        onProgress(Progress("铺开 proot 运行库", 0, ""))
        copyAsset("proot/lib/libtalloc.so.2", File(paths.lib, "libtalloc.so.2"))
        copyAsset("proot/lib/libandroid-shmem.so", File(paths.lib, "libandroid-shmem.so"))

        // ── 2. rootfs ──────────────────────────────────────────────────────
        var errors: List<String> = emptyList()
        if (paths.installMarker.isFile) {
            onProgress(Progress("rootfs 已安装，跳过", 0, ""))
        } else {
            val assetName = rootfsAssetName()
            onProgress(Progress("解压 rootfs（$assetName）", 0, ""))
            context.assets.open("$ROOTFS_ASSET_DIR/$assetName").use { input ->
                val stats = TarExtractor().extract(input, paths.rootfs) { entries, current ->
                    onProgress(Progress("解压 rootfs", entries, current))
                }
                errors = stats.errors.toList()
                onProgress(
                    Progress(
                        "解压完成：${stats.files} 文件 / ${stats.dirs} 目录 / ${stats.links} 链接" +
                            if (errors.isEmpty()) "" else "，${errors.size} 项失败",
                        0,
                        "",
                    )
                )
            }
            // 只在零错误时落标记 —— 否则一次失败的解压会被永久当成"已安装"跳过
            if (errors.isEmpty()) {
                paths.installMarker.writeText("installed\n")
            }
        }

        // ── 3. 挂载点 ──────────────────────────────────────────────────────
        RUNTIME_DIRS.forEach { File(paths.rootfs, it).mkdirs() }

        onProgress(Progress("就绪", 0, ""))
        return errors
    }

    private fun copyAsset(assetPath: String, dest: File) {
        dest.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            dest.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
    }
}
