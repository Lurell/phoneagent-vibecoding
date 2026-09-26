package io.phoneagent.container

import android.content.Context
import android.net.ConnectivityManager
import java.io.File

/**
 * 容器内的网络与 apt 配置。每次启动容器前刷新一次。
 *
 * 解决三个在 proot 环境里必然撞上的问题：
 *
 * **一、DNS。** Android 自己用 netd 解析域名，根本不读 /etc/resolv.conf，
 * 所以 rootfs 里那个文件是空的。容器里任何联网操作（apt、npm、curl）都会
 * 「无法解析域名」。必须把 Android 当前生效的 DNS 写进去。
 *
 * **二、apt 的降权沙箱。** apt 默认把下载动作降权到 `_apt` 用户执行，
 * 而 Android 的 seccomp 过滤器屏蔽了 setuid/setgid 一族系统调用，
 * 降权必然失败，表现为 `Could not switch to '_apt'`。直接把沙箱用户指回 root。
 *
 * **三、IPv6 黑洞。** 部分网络下 IPv6 路由不通但 DNS 仍会返回 AAAA 记录，
 * apt 会先卡在 IPv6 上直到超时。强制走 IPv4 能省掉这段无谓等待。
 *
 * 另外可选地把 apt 源换成国内镜像：官方 ports.ubuntu.com 在中国大陆往往只有
 * 几十 KB/s，装一个 Node.js 要等很久。镜像是同一批包的完整镜像、签名密钥相同，
 * 安全性不受影响。
 */
class ContainerNetwork(
    private val context: Context,
    private val paths: Paths,
) {

    data class Result(
        val dns: List<String>,
        val mirrorSwitched: Boolean,
        val caBundleWritten: Boolean,
    ) {
        val usedFallbackDns: Boolean get() = dns == FALLBACK_DNS
    }

    companion object {
        /** 拿不到系统 DNS 时的兜底：阿里 DNS 与 DNSPod，国内可达性好。 */
        private val FALLBACK_DNS = listOf("223.5.5.5", "119.29.29.29")

        private const val OFFICIAL_PORTS = "http://ports.ubuntu.com/ubuntu-ports/"
        private const val CHINA_MIRROR_HOST = "mirrors.aliyun.com"
        private const val CHINA_PORTS = "https://$CHINA_MIRROR_HOST/ubuntu-ports/"

        /** 随包分发的 Mozilla CA 证书链。 */
        private const val CA_ASSET = "ca/ca-certificates.crt"

        /**
         * 判定「容器里已经有 CA 证书链」的体积下限。
         * 这份 Mozilla 证书链约 185KB；apt 装上 ca-certificates 后是 200KB 上下，
         * 都远大于这个值。用一个宽松阈值是为了避免覆盖 apt 装的正版。
         */
        private const val MIN_CA_BYTES = 150_000L
    }

    fun refresh(useChinaMirror: Boolean = true): Result {
        val dns = systemDns()
        writeResolvConf(dns)
        writeAptConf()
        val caWritten = writeCaCertificates()
        val switched = useChinaMirror && switchToChinaMirror()
        return Result(dns, switched, caWritten)
    }

    /**
     * 把 CA 证书链写进容器。
     *
     * Ubuntu 的 base 镜像是 `debootstrap --variant=minbase` 构建的，**不含
     * ca-certificates 包**，连 /etc/ssl 目录都没有。于是 apt 走 HTTPS 时报
     * `Certificate verification failed: The certificate issuer is unknown`。
     *
     * 随包带一份 Mozilla 证书链直接写进去，让 HTTPS 立刻可用 —— 否则会陷入
     * 鸡生蛋：要用 apt 装 ca-certificates，而 apt 自己就需要它。
     */
    private fun writeCaCertificates(): Boolean {
        val target = File(paths.rootfs, "etc/ssl/certs/ca-certificates.crt")
        // 已经被 apt 装的正版（或我们之前写的）替换过，就不再动
        if (target.isFile && target.length() > MIN_CA_BYTES) return false
        return try {
            target.parentFile?.mkdirs()
            context.assets.open(CA_ASSET).use { input ->
                target.outputStream().use { input.copyTo(it, 64 * 1024) }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 读 Android 当前生效网络的 DNS 服务器。
     *
     * 必须过滤掉**链路本地 IPv6**（fe80::/10）。实测在 Redmi Note 8 上，
     * Android 会把 `fe80::1%wlan0` 作为首选 DNS 报上来 —— 带作用域后缀的
     * 链路本地地址，glibc 的解析器处理不稳定，会让容器里所有域名解析
     * 直接超时失败（表现为 apt 报 `Temporary failure resolving`）。
     * 换掉这一条之后 apt 立刻恢复正常。
     */
    private fun systemDns(): List<String> = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val props = network?.let { cm.getLinkProperties(it) }
        props?.dnsServers
            ?.mapNotNull { it.hostAddress }
            ?.map { it.substringBefore('%') } // 去掉 %wlan0 之类的作用域后缀
            ?.filter { it.isNotBlank() }
            ?.filterNot { it.startsWith("fe80:", ignoreCase = true) }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
            ?: FALLBACK_DNS
    } catch (_: Exception) {
        FALLBACK_DNS
    }

    private fun writeResolvConf(dns: List<String>) {
        val target = File(paths.rootfs, "etc/resolv.conf")
        target.parentFile?.mkdirs()
        // 先删再写：rootfs 里这个路径可能是符号链接（systemd-resolved 的惯例），
        // 直接写会写到链接指向的地方甚至失败。
        target.delete()
        target.writeText(
            buildString {
                appendLine("# 由 phoneagent 在容器启动前生成 —— Android 不会填这个文件")
                dns.forEach { appendLine("nameserver $it") }
                appendLine("options timeout:2 attempts:2")
            }
        )
    }

    private fun writeAptConf() {
        val dir = File(paths.rootfs, "etc/apt/apt.conf.d")
        dir.mkdirs()
        File(dir, "99phoneagent").writeText(
            """
            // 由 phoneagent 生成。见 ContainerNetwork.kt 的说明。
            APT::Sandbox::User "root";
            Acquire::ForceIPv4 "true";
            Acquire::Retries "3";
            """.trimIndent() + "\n"
        )
    }

    /** 幂等：已经换过就不再动，方便重复调用。 */
    private fun switchToChinaMirror(): Boolean {
        var changed = false
        for (name in listOf("etc/apt/sources.list.d/ubuntu.sources", "etc/apt/sources.list")) {
            val f = File(paths.rootfs, name)
            if (!f.isFile) continue
            val text = f.readText()
            if (text.contains(OFFICIAL_PORTS) && !text.contains(CHINA_MIRROR_HOST)) {
                f.writeText(text.replace(OFFICIAL_PORTS, CHINA_PORTS))
                changed = true
            }
        }
        return changed
    }
}
