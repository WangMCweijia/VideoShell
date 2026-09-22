package com.videoshell.data.net

/**
 * 自更新的**高速线路**（v1.0.56）：GitHub 公共加速镜像（URL 前缀反代）。
 *
 * ## 为什么要它（2026-09-22 本机实测，两次独立测量）
 *
 * 双通道（api → 裸链，v1.0.55）解决了"清单取不到"，但解决不了"包下得慢"：
 * 同一时刻、同一台机器对同一个 APK（`Range: bytes=0-2MiB`）测得的吞吐：
 *
 * | 线路 | 吞吐 | 8 MB 包约需 |
 * |---|---|---|
 * | 直连 `github.com` | **74 KB/s**（第二次测量直接整段超时） | ~110 s |
 * | `gh-proxy.com` | **1860 KB/s** | ~5 s |
 * | `gh.xxooo.cf` | 1581 KB/s | ~6 s |
 * | `gh.ddlc.top` | 1063 KB/s | ~8 s |
 *
 * 差距是 **25 倍**。"换网络再试"救不了它 —— 这不是可达性问题，是国际出口带宽问题。
 *
 * ## 形式：URL 前缀反代，不是代理服务器
 *
 * `wrap(prefix, url)` 把 GitHub 地址变成 `https://<镜像>/https://github.com/...`。
 * 选这种形式的原因：App 里**没有可配置的代理入口**（用户也不想配），而公共前缀反代
 * 是免配置的；代价是必须自带一串候选并**运行时测速** —— 这类镜像来去很快，
 * 写死一个等于把单点故障从 `github.com` 换到镜像上。
 *
 * ## 三条不变量（安全边界，改前先想清楚）
 *
 * 1. **镜像只服务"包"的字节，不扩展信任。** 清单（sha256 的来源）优先走 GitHub 本尊；
 *    镜像通道拿清单是最后兜底，且必须 **≥2 个独立镜像源内容一致** 才接受（见
 *    [UpdateChecker.fetchManifest]）。单镜像给出的清单可能被该镜像篡改 —— 拒绝。
 * 2. **下载白名单看的是"剥掉镜像前缀之后的内层主机"**（`UpdateChecker.hostAllowed`）：
 *    镜像主机本身**不在**白名单里，`wrap(镜像, https://evil.com/x.apk)` 照样被拒 ——
 *    否则白名单等于对镜像开洞。
 * 3. **探测必须验 ZIP 魔数**（[looksLikeApk]）。实测有两个镜像（`ghps.cc` / `gh-proxy.net`）
 *    对**任何**地址都返回 `200 + 自己的 HTML 页面` —— HTTP 状态完全正常、字节数对不上。
 *    只看状态码会把它们当成"最快的高速线路"，然后整个下载废在 sha256 上。
 *    前 4 字节是 `PK\x03\x04`（ZIP/APK 的本地文件头）才允许进入候选。
 *
 * ## 纯逻辑
 *
 * 本文件不 import 任何 OkHttp/Android 类：包装、剥皮、魔数判断、按速度排序全是纯函数，
 * 离线 harness（`UpdMirror.java`）逐条断言。真网络探测在 [UpdateDownloader]。
 */
object UpdateMirror {

    /**
     * 候选镜像前缀（2026-09-22 实测存活且诚实的，按当时吞吐降序）。
     *
     * 顺序只是默认值：每次下载前都会真探测一遍并重排（[rank]）——
     * 这份列表会腐化，腐化的表现是"探测全部失败 ⇒ 回落直连"，不会崩。
     *
     * ⚠️ 实测被排除的「说谎镜像」（返回 200 + HTML，不是文件）：`ghps.cc`、`gh-proxy.net`。
     * 别因为"它们响应快"就加回来 —— [looksLikeApk] 会在运行时把它们揪出来，但列表本身
     * 应该保持干净。新镜像加进来之前，先用 `Range` 请求验一次返回的是不是真 ZIP。
     */
    val MIRROR_PREFIXES: List<String> = listOf(
        "https://gh-proxy.com/",
        "https://gh.xxooo.cf/",
        "https://gh.ddlc.top/",
        "https://ghfast.top/",
        "https://ghproxy.cc/",
        "https://cf.ghproxy.cc/",
        "https://ghpxy.hwinzniej.top/",
        "https://ghproxy.net/",
    )

    /** 前缀统一以 `/` 结尾；`url` 必须是**完整的**绝对地址 */
    fun wrap(prefix: String, url: String): String =
        prefix.trimEnd('/') + "/" + url

    /**
     * 剥掉镜像前缀后剩下的**内层**地址。
     * 不是已知镜像前缀开头 ⇒ null（调用方按"这不是镜像地址"处理）。
     */
    fun innerOf(url: String): String? {
        val u = url.trim()
        for (p in MIRROR_PREFIXES) {
            if (u.startsWith(p, ignoreCase = true)) return u.substring(p.length)
        }
        return null
    }

    /** 命中的镜像前缀（没命中为 null） */
    fun prefixOf(url: String): String? {
        val u = url.trim()
        return MIRROR_PREFIXES.firstOrNull { u.startsWith(it, ignoreCase = true) }
    }

    fun isMirrored(url: String): Boolean = prefixOf(url) != null

    /**
     * APK 的前 4 字节是不是 ZIP 本地文件头（`PK\x03\x04`）。
     *
     * 所有 APK 都是 ZIP；sha256 能验完整性但验不了"下载前"，它是唯一能在
     * **浪费整次下载之前**识破"200 + HTML"说谎镜像的判据。
     */
    fun looksLikeApk(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
                head[2] == 3.toByte() && head[3] == 4.toByte()

    /**
     * 下载候选清单：**直连两条在前，镜像包一层在后**（每条镜像都包裸链那个地址 ——
     * 镜像对 `api.github.com` 形式的内层地址支持与否没有实测证据，不放进候选）。
     *
     * @param apkUrl    清单里的裸链地址（`github.com`，镜像可反代）
     * @param apkApiUrl api 资产地址（直连用；重定向到 GitHub 自己的 CDN）
     */
    fun candidates(apkUrl: String, apkApiUrl: String?): List<String> {
        val direct = listOfNotNull(apkApiUrl, apkUrl).distinct()
        val mirrored = MIRROR_PREFIXES.map { wrap(it, apkUrl) }
        return direct + mirrored
    }

    /**
     * 按探测速度重排候选。
     *
     * - 测出速度的在前，**快的在前**；
     * - 没测出 / 探测失败的保持原相对顺序垫底（stable sort）—— 直连虽然常慢，
     *   但它是"镜像全灭"时唯一确定的退路，必须留在候选里。
     *
     * @param speed KB/s（字节/毫秒）；缺失 = 没测出
     */
    fun rank(candidates: List<String>, speed: Map<String, Double>): List<String> =
        candidates.sortedBy { -(speed[it] ?: -1.0) }

    /** 镜像标签（报错/提示里点名用的短名，不带 scheme） */
    fun labelOf(url: String): String? =
        prefixOf(url)?.let { it.removePrefix("https://").trimEnd('/') }
}
