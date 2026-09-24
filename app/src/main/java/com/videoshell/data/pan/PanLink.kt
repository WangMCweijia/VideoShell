package com.videoshell.data.pan

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 网盘类型。
 *
 * [key] 用于两个地方，两边必须一致：
 *  - 凭据 key 前缀（`quark.cookie` / `uc.cookie`，与 TVBox 的 `quark_cookie.txt` 一一对应，便于对照排错）；
 *  - 站内引用 scheme（`panref://quark/{shareId}/{fid}/{token}`）。
 */
enum class PanType(val label: String, val key: String) {
    QUARK("夸克网盘", "quark"),
    UC("UC网盘", "uc"),
    ALI("阿里云盘", "ali"),
    CLOUD123("123网盘", "123"),
    CLOUD189("天翼云盘", "189"),
    XUNLEI("迅雷云盘", "xunlei"),
    GUANGYA("光鸭云盘", "guangya"),
    BAIDU("百度网盘", "baidu"),
    /**
     * 115 网盘（v1.0.72 补）。
     *
     * ⚠️ 它不是"顺手加的第九个盘"，而是**一个判据缺陷的解药**：木偶站在「115臻享」分类
     * 里贴的全是 `115cdn.com/s/…`，而 `PanLink` 认不出它 ⇒ 那一页的 D2 不成立 ⇒
     * **整站被判成"不是网盘分享站族"**（判定按 host 落盘，一票否决）⇒ 它自己那些
     * 贴夸克/UC 的标题也跟着走不到网盘路径。见 docs/PITFALLS.md E54。
     */
    CLOUD115("115网盘", "115"),
    MOBILE("移动云盘", "mobile");

    companion object {
        fun ofKey(k: String?): PanType? =
            if (k.isNullOrBlank()) null else values().firstOrNull { it.key == k }
    }
}

/**
 * 站内引用：**分享文件夹里某一个文件**。
 *
 * 为什么不用原始直链：网盘直链带时效签名（一次性 token），落盘/入历史后必然"昨天能播今天不能"。
 * 所以详情页展开出来的每一集，存的是**如何再取一次直链**（分享 id + fid + 分享令牌），
 * 取直链这一步每次播放时现做。
 */
data class PanRef(val link: PanLink, val fid: String, val token: String)

/**
 * 一条**网盘分享链接**。
 *
 * [id] 是分享 id（夸克是 `pan.quark.cn/s/{id}` 里的 id；百度是 `s/1{id}`，那个 `1` 由
 * [Companion.Rule.prefix] 拼回去）；[pwd] 是提取码（没有就是空串）；[raw] 是**原始链接原文**，
 * 留着是因为：详情页展开失败时要能把它原样交给 [PanResolver] 兜底，而**重建**一个等价 URL
 * 是有风险的（各盘的分享链接形态并不统一，比如阿里还有 `/folder/{id}` 段）。
 *
 * ## 解析 API 为什么放在 companion 里
 *
 * 因为这些函数的返回类型就是 [PanLink] 本身（`PanLink.parse(url): PanLink?`）——
 * 写成同名的另一个 `object PanLink` 会和这个数据类**重名冲突**（Kotlin 不允许同名类与对象共存），
 * 而把名字改成 `PanLinks` 又会让调用点全部变成"复数"这种无意义的区分。
 */
data class PanLink(
    val type: PanType,
    val id: String,
    val pwd: String = "",
    val raw: String = ""
) {
    /** 显示/日志用（**不要**把完整链接写进日志） */
    override fun toString(): String = "${type.key}:$id"

    companion object {

        private class Rule(val type: PanType, val re: Regex, val prefix: String = "")

        /**
         * 顺序即优先级：先匹配到的类型胜出。夸克/UC 放最前（实测这两个站的公开资源只用这两家）。
         *
         * 正则来源：TVBox `Pan.smali` 的 9 条分享链接正则（反编译实测），已按本项目口径收紧：
         *  - 一律排除空白与引号，避免把整段 HTML/JS 当成链接吃掉；
         *  - 分享 id 的字符类**额外排除 `.`** —— 这一条是离线套件抓出来的真误判：
         *    `https://pan.baidu.com/s/1.html` 在旧字符类下被解析成 `baidu:1.html`（长度过闸），
         *    于是**任何引用了百度页面的普通站点**都会被拖进网盘链路。九个盘的分享 id
         *    实际都只用字母数字（百度/迅雷另带 `_` `-`），而路径里出现 `.` 的几乎一定是
         *    "某个页面"而不是分享。
         *  - 移动云盘（`139`）**要求 host 里出现 `139.com`** —— 原正则 `(?:\?linkID=…)` 这种
         *    在整页文本上会误伤任何带 `linkID` 的普通网址，而这里是"自门控"入口，
         *    误判的代价是正常站点被拖进网盘链路。
         */
        private val RULES = listOf(
            Rule(
                PanType.QUARK,
                Regex("""pan\.quark\.cn/s/([^/\s?#"'<>&\\.]+)""", RegexOption.IGNORE_CASE)
            ),
            Rule(
                PanType.UC,
                Regex("""drive\.uc\.cn/s/([0-9A-Za-z]{6,40})""", RegexOption.IGNORE_CASE)
            ),
            Rule(
                PanType.ALI,
                Regex(
                    """(?:www\.)?(?:alipan|aliyundrive)\.com/s/([^/\s?#"'<>&\\.]+)""",
                    RegexOption.IGNORE_CASE
                )
            ),
            Rule(
                PanType.CLOUD123,
                Regex(
                    """(?:www\.)?123(?:[0-9A-Za-z]{3}|pan)\.(?:com|cn)/(?:s|123pan)/([^/\s?#"'<>&\\.]+)""",
                    RegexOption.IGNORE_CASE
                )
            ),
            Rule(
                PanType.CLOUD189,
                Regex(
                    """cloud\.189\.(?:cn|com)/(?:[^/\s?#"'<>&\\]+/)?(?:share\.html#/)?t/([0-9A-Za-z]+)""",
                    RegexOption.IGNORE_CASE
                )
            ),
            Rule(
                PanType.XUNLEI,
                Regex("""pan\.xunlei\.com/s/([^/\s?#"'<>&\\.]+)""", RegexOption.IGNORE_CASE)
            ),
            Rule(
                PanType.GUANGYA,
                Regex("""(?:www\.)?guangyapan\.com/s/([^/\s?#"'<>&\\.]+)""", RegexOption.IGNORE_CASE)
            ),
            // 百度：`pan.baidu.com/s/1{code}` —— 那个 `1` 是 surl 的固定前缀，必须拼回 id 里
            Rule(
                PanType.BAIDU,
                Regex("""pan\.baidu\.com/s/1([^/\s?#"'<>&\\.]+)""", RegexOption.IGNORE_CASE),
                prefix = "1"
            ),
            // 115：实测（2026-09-24）木偶站「115臻享」分类贴的是
            // `https://115cdn.com/s/swsagii36dh?password=f9e3` —— 提取码参数叫 `password`
            // 而不是 `pwd`。`115.com/s/…` 是同一家的老域名，一起认。
            Rule(
                PanType.CLOUD115,
                Regex("""115(?:cdn)?\.com/s/([0-9A-Za-z_\-]+)""", RegexOption.IGNORE_CASE)
            ),
            Rule(
                PanType.MOBILE,
                Regex(
                    """139\.com/(?:w/i/|m/i[/?]|[^/\s?#"'<>&\\]+/share/)([0-9A-Za-z]+)""",
                    RegexOption.IGNORE_CASE
                )
            )
        )

        /** 提取码参数名：百度/夸克是 `pwd`、夸克另接受 `passcode`、115 是 `password` */
        private val PWD = Regex(
            """(?:password|passcode|pwd)=([0-9A-Za-z]{1,8})""", RegexOption.IGNORE_CASE
        )

        /** 分享 id 的合理长度区间：挡掉"正则吃到了半截 HTML"这类噪声 */
        private const val MIN_ID = 4
        private const val MAX_ID = 96

        /**
         * ## 网盘分享链接识别（纯函数，无 IO）
         *
         * 这是**整个网盘能力唯一的入口判据**，插在
         * [com.videoshell.data.site.SiteAdapter.resolve] 最前面 —— 与站型识别无关。这样：
         *
         * 1. 「输入网址 → 识别 → 播放」这条主线一个字都不用改（非网盘链接返回 null，零开销、零请求）；
         * 2. 用户**直接粘一条** `https://pan.quark.cn/s/xxx` 也能播（不经过任何"站"）。
         *
         * ⚠️ **判据只能是链接形状，不能是站点域名**。实测（2026-09-23）：快映站
         * `http://xsayang.fun` 会 302 到 `http://43.248.128.118:12512/`，同站还挂着
         * `38.76.197.172:12521` 这类入口 —— 域名就是跳板，而**分享链接本身**
         * （`pan.quark.cn/s/…`）才是稳定的东西。这与 `SeedFamily` / `CryptFamily`
         * 的"形状自证"是同一条纪律。
         *
         * @return 命中返回 [PanLink]；不是网盘链接返回 **null**（调用方据此零成本跳过）。
         */
        fun parse(raw: String?): PanLink? {
            if (raw.isNullOrBlank()) return null
            val u = raw.trim()
            if (u.length < 12 || u.length > 2048) return null
            val s = if (u.contains("&amp;")) u.replace("&amp;", "&") else u
            for (r in RULES) {
                val m = r.re.find(s) ?: continue
                val id = r.prefix + m.groupValues[1]
                if (id.length < MIN_ID || id.length > MAX_ID) continue
                return PanLink(r.type, id, pwdOf(s), u)
            }
            return null
        }

        /** 这条 URL 是不是网盘分享链接（自门控判据） */
        fun isPan(url: String?): Boolean = parse(url) != null

        // -------------------------------------------------------------- 站内引用

        private const val SCHEME = "panref"

        /** 造一条站内引用（详情页展开文件夹时用） */
        fun refOf(link: PanLink, fid: String, token: String): String = buildString {
            append(SCHEME).append("://").append(link.type.key)
            append('/').append(enc(link.id))
            append('/').append(enc(fid))
            append('/').append(enc(token))
            if (link.pwd.isNotBlank()) append("?pwd=").append(enc(link.pwd))
        }

        fun isRef(url: String?): Boolean =
            !url.isNullOrBlank() && url.startsWith("$SCHEME://", ignoreCase = true)

        /** 解一条站内引用；不是引用 / 类型不认识 / 段数不对都返回 null（调用方据此回退） */
        fun parseRef(url: String?): PanRef? {
            if (!isRef(url)) return null
            val s = url!!.trim()
            val body = s.substring(SCHEME.length + 3)
            val q = body.indexOf('?')
            val path = if (q >= 0) body.substring(0, q) else body
            val pwd = if (q >= 0) pwdOf(body.substring(q)) else ""
            val seg = path.split('/')
            if (seg.size < 4) return null
            val type = PanType.ofKey(seg[0]) ?: return null
            val id = dec(seg[1])
            val fid = dec(seg[2])
            val token = dec(seg[3])
            if (id.isBlank() || fid.isBlank()) return null
            return PanRef(PanLink(type, id, pwd, ""), fid, token)
        }

        private fun pwdOf(s: String): String =
            runCatching { PWD.find(s)?.groupValues?.get(1).orEmpty() }.getOrDefault("")

        private fun enc(s: String): String =
            runCatching { URLEncoder.encode(s, "UTF-8") }.getOrDefault(s)

        private fun dec(s: String): String =
            runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    }
}
