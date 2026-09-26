package com.videoshell.data.pan

import com.videoshell.data.net.Http
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * ## 百度网盘（P1 的第一个）
 *
 * ### 为什么是它先做
 *
 * 依据不是"它有名"，而是**我们自己的样本里有它**：`tools/verify/samples/panshare/ky_detail.html`
 * （快映）的 `data-clipboard-text` 里，夸克旁边挂的就是一条 `pan.baidu.com/s/1…?pwd=…`。
 * 九个盘里只有夸克/UC 有实现，而**百度是唯一一条在真样本里出现过的空白**。
 *
 * ### 实测确认的接口事实（匿名段，2026-09-26，`tools/verify/panbaidu_spike.py`）
 *
 * | 步骤 | 端点 | 匿名 | 已登录 |
 * |---|---|---|---|
 * | 分享页 | `GET /s/1{surl}` | ✅ 200 + `yunData`（含 `shareid` / `share_uk`） | ✅ |
 * | 换放行票据 | `POST /share/verify` | ✅ `errno=0` + `Set-Cookie: BDCLND` | ✅ |
 * | 列**根**目录 | `GET /share/list?…&root=1&dir=/` | ✅ `errno=0` + `list[]` | ✅ |
 * | 列**子**目录 | `GET /share/list?…&dir={path}`（⚠️ **不带 `root`**） | ✅ `errno=0` | ✅ |
 * | 取直链 | `POST /api/sharedownload` | ❌ `errno=113 验证码签名错误` | ⏳ **未实测** |
 *
 * ⇒ **详情页能像夸克/UC 一样匿名展开真实集数**（[list] 一整条都实测过），
 * 只有"点开某一集"需要登录 —— 与 P0 两盘的形状一致。
 *
 * ### ⚠️ [stream] 是**按文档形状写的、还没实测**的那一半
 *
 * 取 dlink 需要的 `sign` **匿名拿不到**：2026-09-26 直接把放行后的分享页落盘看过
 * （`/tmp` 级的一次性探针），`yunData` 的字面量是
 * `{… bdstoken:'', uk:'0', loginstate:'0', share_uk:"…", shareid:"…"}` —— 没有 `sign`，
 * 也没有 `timestamp`；`sign`/`sign1..3` 只出现在 webpack 模块的 `locals` **元数据**里
 * （那是"这个模块要消费哪些变量"，不是值）。所以匿名打 `/api/sharedownload` 恒
 * `errno=113`，不是我们参数写错。
 *
 * ⇒ "Cookie 就够、还是必须再带 `bdstoken`"这个判据，**只能靠一份真实登录态跑一次**
 * 才能钉住（文件和工具都已就绪）：
 *
 * ```
 * BAIDU_COOKIE="$(cat /path/to/baidu_cookie.txt)" python tools/verify/panbaidu_spike.py
 * ```
 *
 * 跑出来的 ⑤⑥ 两段就是本类 [stream] 的验收依据。在那之前它的行为是**可预期地失败**：
 * 失败文案一定带**哪一步 + 服务端 errno 原话**（[note]），而不是"点了没反应"。
 *
 * ### 两个与夸克/UC 相反的形状（容易抄错的地方）
 *
 * 1. **子目录不能用 fs_id 去列**。`/share/list` 的 `dir` 要的是**拥有者侧绝对路径**
 *    （返回项自己的 `path`，如 `/2026-YIN/出入`）—— 拿 fs_id 或"按名字拼出来的
 *    `/子目录`"去问只会得到 `errno=2`。所以目录项的 [PanFile.fid] 存的是它的 `path`。
 * 2. **子目录不能带 `root=1`**：它会让服务端**静默忽略 `dir`**、把根目录的内容再发一遍。
 *    症状极具误导性 —— 看起来每层都"下钻成功"，其实是原地打转（PITFALLS §4.80）。
 *    [useRoot] 是这条判据的唯一出处。
 */
class PanBaidu private constructor() : PanProvider {

    companion object {
        fun baidu() = PanBaidu()

        /** PC 接口用桌面 UA —— 与 [PanCloudDrive] 同一条理由（PC 接口就别装手机） */
        private const val PAN_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private const val API = "https://pan.baidu.com"
        private const val WEB = "https://pan.baidu.com/"

        /** `app_id=250528` = 网页版这个产品；`channel=chunlei` 是网页端一直用的值 */
        private const val APP_ID = "250528"
        private const val CHANNEL = "chunlei"

        /** 一次列目录要多少项（分享根通常只有几个目录，200 足够且省流量） */
        private const val PAGE_SIZE = 200

        /** 分享页结构信息（`shareid`/`share_uk`）缓存时长 —— 与夸克的 stoken 同一个量级 */
        private const val SHARE_TTL_MS = 20 * 60_000L

        /**
         * 从分享页 HTML 里抠 `yunData` 的结构字段（**纯函数**，可离线断言）。
         *
         * ⚠️ 它必须用**正则**、不能用 `JSONObject`：`yunData` 是 **JS 字面量**
         * （键名**不带引号**：`yunData={skinName:'white', …, shareid:"51458864580"}`），
         * 不是 JSON —— `JSONObject` 上去必抛。这与夸克那边 [PanCloudDrive.envelopeFields]
         * 不能依赖 JSON 解析是同一类问题。
         *
         * 只取**与登录态无关**的那几个；`bdstoken`/`sign` 顺带取（匿名页上是空串），
         * 调用方要用它们的场景（[stream]）必须**带 cookie 重新取一次页面**。
         *
         * @return 缺 `shareid` 或 `share_uk` 时返回 null（列目录至少要这两个）
         */
        @JvmStatic
        fun shareFields(html: String): BaiduShare? {
            if (html.isBlank()) return null
            val sid = Regex("shareid\\s*[:=]\\s*[\"']?(\\d{5,})")
                .find(html)?.groupValues?.get(1)
            // `share_uk` 与 `uk` 都在页面上；只认前者 —— 匿名页的 `uk` 是 `'0'`（游客 id）
            val uk = Regex("share_uk\\s*[:=]\\s*[\"']?(\\d{5,})")
                .find(html)?.groupValues?.get(1)
            if (sid.isNullOrBlank() || uk.isNullOrBlank()) return null
            val tok = Regex("bdstoken\\s*[:=]\\s*[\"']([^\"']*)[\"']")
                .find(html)?.groupValues?.get(1).orEmpty()
            // `\bsign\b` 才不会连 `sign1`/`sign2` 一起吃掉（那三个是另一套签名材料），
            // 而 webpack 元数据里的 `"sign","servertime"` 后面跟的是逗号、不是 `:`/`=` ⇒ 不命中
            val sign = Regex("\\bsign\\b\\s*[:=]\\s*[\"']([^\"']{4,200})[\"']")
                .find(html)?.groupValues?.get(1).orEmpty()
            val ts = Regex("\\btimestamp\\b\\s*[:=]\\s*(\\d{9,13})")
                .find(html)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            return BaiduShare(sid, uk, tok, sign, ts)
        }

        /**
         * 这个分享页是不是**错误页**（**纯函数**，可离线断言）。
         *
         * 判据是服务端自己在页面上写的 `share_page_type:"error"` —— **不是抠不到字段**。
         * 实测（PITFALLS §4.80）：分享已失效时页面照样 `200`、照样有完整的
         * `yunData`（`shareid`/`share_uk` 都在），只是 `share_page_type` 变成 `error`
         * 且 `errno=145`。所以"抠到了 shareid + uk"**不构成**"这条分享可用"的证据 ——
         * 这一条正是当初把一条死链报告成"体验最好的一条路"的原因。
         */
        @JvmStatic
        fun shareIsDead(html: String): Boolean =
            html.contains("share_page_type", ignoreCase = false) &&
                    Regex("share_page_type\\s*[:=]\\s*[\"']error[\"']").containsMatchIn(html) ||
                    Regex("\"errno\"\\s*:\\s*145\\b").containsMatchIn(html)

        /**
         * 从信封里抠 `errno`（**纯函数**）。百度用 `{"errno":0,…}`，
         * 没有夸克那种 `{status,code,message}`。
         *
         * 用正则而不是 `JSONObject`：失败响应体经 [Http.HttpError] 只留前 200 字符，
         * 可能截断（与 [PanCloudDrive.envelopeFields] 同一个理由）。
         */
        @JvmStatic
        fun errnoOf(body: String): Int? {
            if (body.isBlank()) return null
            return Regex("\"errno\"\\s*:\\s*(-?\\d+)").find(body)
                ?.groupValues?.get(1)?.toIntOrNull()
        }

        /** 从 `sharedownload` 的响应里抠 `dlink`（**纯函数**，`\/` 是 JSON 里的转义斜杠） */
        @JvmStatic
        fun dlinkOf(body: String): String? {
            if (body.isBlank()) return null
            val v = Regex("\"dlink\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
                ?.groupValues?.get(1) ?: return null
            val u = v.replace("\\/", "/").replace("&amp;", "&")
            return u.takeIf { it.startsWith("http") }
        }

        /**
         * 列这个目录要不要带 `root=1`（**纯函数**，可离线断言）。
         *
         * **只有分享根**要带：`root=1 & dir=/`。子目录**必须不带** —— 带了服务端会
         * 静默忽略 `dir`、把根目录再发一遍，于是"下钻"看起来成功却永远在原地（§4.80）。
         * 缺 `root=1` 的根目录请求恒 `errno=-21`，所以这一条不能反过来。
         */
        @JvmStatic
        fun useRoot(fid: String?): Boolean = fid.isNullOrBlank() || fid == "/"
    }

    override val type: PanType get() = PanType.BAIDU

    /**
     * ⚠️ `true` 的含义是"这个盘能取直链"。[list] 那条链路**已实测**，
     * [stream] 按文档形状实现、**待一次真实登录态复跑**（见类文档）。
     * 之所以不先压成 false：那会让百度整条线路显示成"暂不支持"，
     * 而"匿名能展开真实集数"这件事是实测成立的——那半本来就该给用户。
     */
    override val supported: Boolean get() = true

    @Volatile
    private var err: PanError? = null

    override val lastError: PanError? get() = err

    @Volatile
    private var step: String = ""

    /** 分享页结构信息缓存（`shareid`/`share_uk`）—— 匿名可得、与登录态无关、短时间内稳定 */
    private val shareCache = ConcurrentHashMap<String, Pair<BaiduShare, Long>>()

    // ------------------------------------------------------------------ 契约

    override suspend fun list(link: PanLink, fid: String?): List<PanFile> {
        err = null
        val sh = shareOf(link) ?: return emptyList()
        val root = useRoot(fid)
        val dir = if (root) "/" else fid!!
        val url = buildString {
            append(API).append("/share/list?uk=").append(u(sh.uk))
            append("&shareid=").append(u(sh.shareid))
            append("&order=other&desc=1&showempty=0&web=1&page=1&num=").append(PAGE_SIZE)
            // ⚠️ 顺序即语义：`root=1` 只对分享根成立，见 [useRoot]
            if (root) append("&root=1")
            append("&dir=").append(u(dir))
            append("&t=").append(nowSec())
            append("&channel=").append(CHANNEL).append("&app_id=").append(APP_ID)
            if (sh.bdstoken.isNotBlank()) append("&bdstoken=").append(u(sh.bdstoken))
            append("&clienttype=0")
        }
        val body = getText(url, null, "列目录") ?: return emptyList()
        val e = errnoOf(body)
        if (e == null) {
            err = PanError.Broken("${type.label}列目录返回的不是信封（接口可能变了）")
            return emptyList()
        }
        if (e != 0) {
            note(e, msgOf(body))
            return emptyList()
        }
        val arr = runCatching { JSONObject(body).optJSONArray("list") }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<PanFile>(arr.length())
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val isDir = it.optInt("isdir", 0) == 1
            val fsId = it.optLong("fs_id", 0L)
            // 目录用 `path`（拥有者侧绝对路径），文件用 `fs_id` —— 见类文档第 1 条
            val id = if (isDir) it.optString("path") else if (fsId > 0L) fsId.toString() else ""
            if (id.isBlank()) continue
            out.add(
                PanFile(
                    fid = id,
                    name = it.optString("server_filename"),
                    dir = isDir,
                    size = it.optLong("size", 0L)
                )
            )
        }
        return out
    }

    /**
     * 取直链（dlink）。⚠️ **本方法是待实测的那一半**（见类文档）。
     *
     * 形状来自方案 §6.3 与 spike 的候选并列探测：带登录态**重取一次分享页**拿
     * `sign`/`timestamp`/`bdstoken`（匿名页上没有 `sign`），再表单 POST
     * `/api/sharedownload`。
     *
     * 失败**绝不静默**：[note] 会把"哪一步 + errno 原话"写进 [lastError]，
     * 上层（[PanResolver.failure]）据此给出可执行文案。
     */
    override suspend fun stream(ref: PanRef): PanStream? {
        err = null
        val ck = DriveStore.cookie(type)
        if (ck.isNullOrBlank()) {
            err = PanError.NeedLogin(needLoginMsg(type), type)
            return null
        }
        val sh = cachedShare(ref.link) ?: shareOf(ref.link) ?: return null
        // 带 cookie 重取：`sign` 只在登录态下才有（匿名页的字面量里根本没有它）
        val page = pageOf(ref.link, ck, "取直链·读分享页")
        val sign = page?.sign.orEmpty()
        val ts = page?.timestamp?.takeIf { it > 0L } ?: nowSec()
        val bdstoken = page?.bdstoken.orEmpty().ifBlank { sh.bdstoken }
        // 放行票据（BDCLND）在 Http 的 CookieJar 里、账号凭据在 DriveStore 里，
        // 两边都要带上 —— 合并用的是 [PanCloudDrive.mediaCookie]（纯函数、离线有断言，
        // `keys = null` = 不筛键）。不复用就只能再抄一份合并逻辑，早晚改一处漏一处。
        val merged = PanCloudDrive.mediaCookie(
            ck, Http.cookieValuesFor("$API/api/sharedownload"), null
        )
        val url = buildString {
            append(API).append("/api/sharedownload?sign=").append(u(sign))
            append("&timestamp=").append(ts)
            append("&channel=").append(CHANNEL).append("&web=1&app_id=").append(APP_ID)
            if (bdstoken.isNotBlank()) append("&bdstoken=").append(u(bdstoken))
            append("&clienttype=0")
        }
        val form = LinkedHashMap<String, String>().apply {
            put("encrypt", "0")
            put("product", "share")
            put("type", "dlink")
            put("uk", sh.uk)
            put("primaryid", sh.shareid)
            put("fid_list", "[${ref.fid}]")
        }
        val body = postForm(url, form, merged, "取直链") ?: return null
        val e = errnoOf(body)
        if (e == null) {
            err = PanError.Broken("${type.label}取直链返回的不是信封（接口可能变了）")
            return null
        }
        if (e != 0) {
            note(e, msgOf(body))
            return null
        }
        val dlink = dlinkOf(body)
        if (dlink.isNullOrBlank()) {
            err = PanError.Broken("${type.label}取直链没有返回 dlink（接口可能变了）")
            return null
        }
        return PanStream(url = dlink, headers = mediaHeaders(merged), hls = false, mime = null)
    }

    /**
     * 校验本地凭据。
     *
     * 两段判据，**都不依赖未实测的 errno 语义**：
     * 1. **结构判据**：百度登录态的会话键就是 `BDUSS`。没有它，这份 cookie 不可能是
     *    一个登录会话（只有 `BAIDUID` 是游客）⇒ 直接判过期，连请求都不用打。
     * 2. **网络探针**：`/api/quota`（便宜的"我是谁"）。`errno=0` ⇒ 确证有效；
     *    其余错误码 ⇒ **判有效**（未开通会员 / 接口小改都不代表凭据无效）。
     *
     * ⚠️ 第 2 条刻意保守：把"认不出的码"判成过期，就是 UC §4.79 那个"刚登录就过期"
     * 的复现路径（用户会白重登一次）。宁可漏判，不可误判 —— 真过期时 [stream] 会
     * 以 `NeedLogin` 的形式告诉用户。
     */
    override suspend fun verify(): DriveState {
        val ck = DriveStore.cookie(type)
        if (ck.isNullOrBlank()) return DriveState.None
        if (!ck.contains("BDUSS=")) {
            DriveStore.markExpired(type)
            return DriveState.Expired
        }
        return try {
            val body = Http.get(
                "$API/api/quota?checkfree=1&checkexpire=1",
                referer = WEB, ua = PAN_UA, headers = cookie(ck)
            )
            when (errnoOf(body)) {
                null -> DriveState.Valid
                0 -> {
                    DriveStore.clearExpired(type)
                    DriveState.Valid
                }
                // 别的 errno 不判过期（见上面的注释）
                else -> DriveState.Valid
            }
        } catch (e: Exception) {
            if (httpCode(e) == 401 || httpCode(e) == 403) {
                DriveStore.markExpired(type)
                DriveState.Expired
            } else {
                DriveState.Valid
            }
        }
    }

    // ------------------------------------------------------------------ 分享页 / 提取码

    private fun cachedShare(link: PanLink): BaiduShare? {
        val hit = shareCache[keyOf(link)] ?: return null
        return if (System.currentTimeMillis() < hit.second) hit.first else null
    }

    private fun keyOf(link: PanLink) = link.type.key + "|" + link.id + "|" + link.pwd

    /**
     * 取分享页的结构信息（[list] 的唯一前置）。
     *
     * 顺序照 spike：**先取一次分享页**（它给会话下发 `BAIDUID`，让后面那次
     * `share/verify` 落在同一个会话里）→ 有提取码就换放行票据 → **再取一次**分享页。
     */
    private suspend fun shareOf(link: PanLink): BaiduShare? {
        cachedShare(link)?.let { return it }
        val first = pageOf(link, null, "读分享页") ?: return null
        val sh = if (link.pwd.isBlank()) {
            first
        } else {
            if (!verifyPwd(link)) return null
            pageOf(link, null, "读分享页（换票据后）") ?: return null
        }
        shareCache[keyOf(link)] = sh to System.currentTimeMillis() + SHARE_TTL_MS
        return sh
    }

    /**
     * 取分享页并解析；页面自称是错误页 ⇒ [PanError.Dead]（这是"分享没了"的**唯一判据**）。
     *
     * ⚠️ 带 cookie 取页面时**必须合并 jar**：`BDCLND`（提取码换来的放行票据）是[verifyPwd]
     * 那一次响应 `Set-Cookie` 下来的、只活在 `Http` 的 CookieJar 里；而 OkHttp 的
     * `BridgeInterceptor` 会被显式 `Cookie` 头**顶掉** jar（见 [com.videoshell.data.net.Http]
     * 的 `ExplicitCookie` 说明）⇒ 只发账号 cookie 的话，有提取码的分享会停在
     * "请输入提取码"那一页，`sign` 抠不到、取直链必失败。
     */
    private suspend fun pageOf(link: PanLink, ck: String?, what: String): BaiduShare? {
        val url = "$WEB/s/${link.id}"
        val jar = Http.cookieValuesFor(url)
        val merged = if (ck.isNullOrBlank()) null else PanCloudDrive.mediaCookie(ck, jar, null)
        val html = getText(url, merged, what, json = false) ?: return null
        if (shareIsDead(html)) {
            err = PanError.Dead("分享已失效（${type.label}分享页自称 share_page_type=error）")
            return null
        }
        return shareFields(html) ?: run {
            err = PanError.Broken("${type.label}分享页里没有 shareid/share_uk（页面形状可能变了）")
            null
        }
    }

    /**
     * 用提取码换放行票据。`surl` 是**去掉那个固定 `1` 前缀**的短码
     * （页面路径是 `/s/1XXXX`，而接口要 `XXXX` —— 弄混的症状是"分享明明活着却被报失效"）。
     *
     * ⚠️ 这里**不能**显式带 `Cookie`：放行票据（`BDCLND`）正是这次响应 `Set-Cookie` 下发的，
     * 而显式 `Cookie` 头会让 OkHttp **跳过 CookieJar** ⇒ 票据根本进不来、下一步列目录必空。
     */
    private suspend fun verifyPwd(link: PanLink): Boolean {
        val surl = link.id.removePrefix("1")
        val url = "$API/share/verify?surl=${u(surl)}&t=${nowSec()}" +
                "&channel=$CHANNEL&web=1&app_id=$APP_ID&clienttype=0"
        val body = postForm(
            url,
            linkedMapOf("pwd" to link.pwd, "vcode" to "", "vcode_str" to ""),
            null, "换放行票据"
        ) ?: return false
        val e = errnoOf(body)
        if (e == null) {
            err = PanError.Broken("${type.label}换放行票据返回的不是信封（接口可能变了）")
            return false
        }
        if (e != 0) {
            note(e, msgOf(body))
            return false
        }
        return true
    }

    // ------------------------------------------------------------------ 内部

    private fun u(s: String): String =
        runCatching { URLEncoder.encode(s, "UTF-8") }.getOrDefault(s)

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    private fun cookie(ck: String?) =
        if (ck.isNullOrBlank()) emptyMap() else mapOf("Cookie" to ck)

    /** JSON 接口一律显式声明 Accept —— 百度按它做内容协商，用 HTML 的 Accept 会回 HTML */
    private val jsonAccept = mapOf("Accept" to "application/json, text/plain, */*")

    private fun stepAt(): String = if (step.isBlank()) "" else "·$step"

    private suspend fun getText(
        url: String,
        ck: String?,
        what: String,
        json: Boolean = true
    ): String? = try {
        step = what
        Http.get(
            url, referer = WEB, ua = PAN_UA,
            headers = (if (json) jsonAccept else emptyMap()) + cookie(ck)
        )
    } catch (e: Exception) {
        err = classify(e)
        null
    }

    /** 表单 POST（带可选 `Cookie`）。`ck = null` 表示**走 CookieJar**（`share/verify` 要这样） */
    private suspend fun postForm(
        url: String,
        params: Map<String, String>,
        ck: String?,
        what: String
    ): String? = try {
        step = what
        Http.postForm(url, params, referer = WEB, ua = PAN_UA, headers = cookie(ck))
    } catch (e: Exception) {
        err = classify(e)
        null
    }

    /** 从后端的原话里取名（`errmsg` / `error_msg` / `show_msg` 三种都见过） */
    private fun msgOf(body: String): String {
        for (k in arrayOf("errmsg", "error_msg", "show_msg", "error")) {
            val v = Regex("\"$k\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
                ?.groupValues?.get(1)
            if (!v.isNullOrBlank()) return v.take(60)
        }
        return ""
    }

    /**
     * 信封级的失败归类（判据**唯一出处**）。
     *
     * ⚠️ 这里的两类判据**分两种成分**：
     *  - `-21`（缺 `root=1`）与 `-6`（游客）是**实测/文档见过**的码，但**都不能当死链**：
     *    `-21` 在**失效分享**上也会出现（§4.80）⇒ 死链判据只认**分享页自己说**的
     *    `share_page_type=error`（见 [pageOf]），不认这个码。
     *  - `需要登录` 的文案匹配是**保守兜底**：取直链那一段还没有实测记录，
     *    真拿到错误码后应当回来收紧 [isNeedLogin]。
     */
    private fun note(errno: Int, rawMsg: String) {
        val msg = rawMsg.take(60)
        err = when {
            isNeedLogin(errno, msg) -> {
                DriveStore.markExpired(type)
                PanError.NeedLogin(needLoginMsg(type), type)
            }
            else -> PanError.Broken("${type.label}${stepAt()}接口返回 errno $errno：$msg")
        }
    }

    private fun isNeedLogin(errno: Int, msg: String): Boolean =
        errno == -6 || msg.contains("登录")

    private fun httpCode(e: Exception): Int =
        Regex("HTTP (\\d{3})").find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun classify(e: Exception): PanError {
        // ① 信封优先：服务端自己说的那句 `errno`/`errmsg` 比 HTTP 状态码准得多
        val he = e as? Http.HttpError
        val raw = he?.body.orEmpty()
        if (raw.trimStart().startsWith("{")) {
            val en = errnoOf(raw)
            if (en != null && en != 0) {
                note(en, msgOf(raw))
                err?.let { return it }
            }
        }
        val code = he?.code ?: httpCode(e)
        if (code <= 0) {
            return PanError.Net(
                "${type.label}${stepAt()}：网络请求失败（${e.javaClass.simpleName} " +
                        "${e.message.orEmpty().take(60)}）"
            )
        }
        return PanCloudDrive.errorForHttp(code, type, step)
    }

    /**
     * 播放器分片要带的头。
     *
     * ⚠️ **这一段是待实测的**（spike ⑥）：夸克那边实测"只认 Cookie"，
     * 而百度的 dlink 对 UA / Referer / Range 的校验强度**还没测过**。
     * 先按"带全"发（Cookie 是确定的，UA/Referer 是与页面对齐的常规身份），
     * 实测后按结论**收紧** —— 多带的头只会宽松，不会让本来能播的变成不能播。
     *
     * 直链**不落盘、不缓存**（§6.4 的纪律）：这里只当次交给播放器。
     */
    private fun mediaHeaders(ck: String): Map<String, String> = mapOf(
        "Cookie" to ck,
        "Referer" to WEB,
        "User-Agent" to PAN_UA
    )
}

/**
 * 百度分享页 `yunData` 里我们关心的那几个字段。
 *
 * [bdstoken] / [sign] / [timestamp] 在**匿名**页面上是空的（或没有）——
 * 只有 [shareid] / [uk] 是匿名可得的。`sign` 必须在登录态下重取页面才有。
 */
data class BaiduShare(
    val shareid: String,
    val uk: String,
    val bdstoken: String = "",
    val sign: String = "",
    val timestamp: Long = 0L
)
