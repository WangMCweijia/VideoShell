package com.videoshell.data.site

import com.videoshell.data.model.SiteConfig
import com.videoshell.data.net.Http
import com.videoshell.util.stripHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 站点识别器：给一个网址，判断它是不是视频站、属于哪种类型，并产出适配配置。
 *
 * 识别顺序：
 *  1. 苹果CMS/海洋CMS 的标准 JSON / XML 采集接口（覆盖绝大多数视频站，识别后即完整可用）
 *  2. 都没有 -> 用通用 HTML 适配（尽力而为，配合嗅探播放）
 *
 * ## v1.0.67：一次可以给**多个网址**
 *
 * 影视站的域名会轮换，用户手上常有一串地址（潇洒 TVBox 本地包就是这么配的，
 * 实测清单见 `docs/网盘站源清单.md`）。所以这里先 [splitUrls] 把输入拆开：
 * **所有候选一起探首页**，最先拿到的那个（按列表顺序）当主地址，其余进
 * [SiteConfig.mirrors]，运行时由 [MirrorRace] **并发赛马挑最快的**那个。
 *
 * 识别阶段为什么取"列表顺序里第一个成功的"而不是"最快的那个"：这一步只要求
 * **找到一个能用的**；而"哪个最快"是运行时的事（那时候选已确定、比较才有意义）。
 * 但探针本身是**并发**发出去的 —— 顺序里排第一的地址若是个死域名，用户不必先干等它超时。
 */
object SiteDetector {

    data class Result(
        val site: SiteConfig?,
        val message: String,
        val isVideoSite: Boolean,
        val pageUrl: String? = null
    )

    private data class Probe(val path: String, val fixed: String, val label: String)

    private val PROBES = listOf(
        Probe("/api.php/provide/vod/at/json/", "", "苹果CMS JSON 路径"),
        Probe("/api.php/provide/vod/", "at=json", "苹果CMS JSON 参数"),
        Probe("/api.php/provide/vod/from/json/", "", "苹果CMS from/json"),
        Probe("/api.php/provide/vod/", "", "苹果CMS 默认接口"),
        Probe("/api.php/provide/vod/at/xml/", "", "苹果CMS XML 路径"),
        Probe("/provide/vod/", "at=json", "精简提供接口"),
        Probe("/index.php/api/vod/", "at=json", "index.php 接口"),
        Probe("/api/vod/", "at=json", "api/vod 接口")
    )

    private val VIDEO_WORDS = listOf(
        "视频", "影视", "电影", "电视剧", "在线观看", "在线播放", "动漫", "综艺", "追剧",
        "vodplay", "vodshow", "voddetail", "vodtype", "maccms", "苹果cms", "海洋cms", "player_aaaa"
    )

    suspend fun detect(rawInput: String): Result = withContext(Dispatchers.IO) {
        val raws = splitUrls(rawInput)
        if (raws.isEmpty()) return@withContext Result(null, "网址格式不正确，请检查后重试", false)

        // 所有输入 → 各自的 base 候选（深层页面会**多**产生一个 origin 候选），去重保序
        val bases = LinkedHashSet<String>()
        for (r in raws) {
            val u = normalize(r) ?: continue
            bases.addAll(buildBases(u))
        }
        if (bases.isEmpty()) return@withContext Result(null, "网址格式不正确，请检查后重试", false)
        val basesList = bases.toList()

        // 1) 首页探活 + 取站点标题。
        //    并发探、按**列表顺序**取第一个成功的（写法与下面第 2 步一致）：
        //    死域名只要不是在最后，用户就不会为它多等一轮。
        val probed = coroutineScope {
            basesList.map { b -> async { b to Http.getOrNull(b, referer = b, fast = true) } }
                .firstNotNullOfOrNull { p -> p.await().takeIf { !it.second.isNullOrBlank() } }
        }
        val base = probed?.first ?: basesList.first()
        val homeHtml: String? = probed?.second
        val siteName = homeHtml?.let { titleOf(it) }.orEmpty().ifBlank { hostOf(base) }
        val spare = spareOf(basesList, base)

        // 2) 并发探测采集接口
        val hit = coroutineScope {
            PROBES.map { p ->
                async {
                    val u = base + p.path + "?ac=list" + if (p.fixed.isBlank()) "" else "&" + p.fixed
                    val body = Http.getOrNull(u, referer = base, fast = true) ?: return@async null
                    val mode = classify(body) ?: return@async null
                    if (!hasData(body, mode)) return@async null
                    Triple(p, mode, body)
                }
            }.firstNotNullOfOrNull { it.await() }
        }

        if (hit != null) {
            val p = hit.first
            val mode = hit.second
            val site = SiteConfig(
                key = (base + p.path + p.fixed).lowercase(),
                name = siteName,
                baseUrl = base,
                apiUrl = base + p.path,
                apiMode = mode,
                fixedParams = p.fixed,
                note = if (mode == SiteConfig.MODE_MACCMS_JSON) "苹果CMS JSON" else "苹果CMS XML",
                createdAt = System.currentTimeMillis(),
                mirrors = spare
            )
            return@withContext Result(site, "识别成功：${p.label}（${p.path}）", true)
        }

        // 3) HTML 兜底
        if (homeHtml != null && looksLikeVideoSite(homeHtml)) {
            val site = SiteConfig(
                key = base.lowercase(),
                name = siteName,
                baseUrl = base,
                apiUrl = "",
                apiMode = SiteConfig.MODE_HTML,
                note = "HTML 通用适配",
                createdAt = System.currentTimeMillis(),
                mirrors = spare
            )
            return@withContext Result(
                site,
                "未找到标准采集接口，已启用 HTML 通用适配（搜索/分类可能不完整，播放可走嗅探）",
                true
            )
        }

        Result(null, "未能识别为视频站。可点「用网页嗅探打开」直接嗅探播放。", false, pageUrl = sniffTarget(rawInput))
    }

    // ---------------- 内部工具 ----------------

    /**
     * 把一段输入拆成多个网址（v1.0.67）。
     *
     * 分隔符取「换行 / 空格 / 制表符 / 中英文逗号 / 分号 / 竖线」—— 用户从清单里复制粘贴
     * 过来时这几种都出现过。**去重保序**，空段丢掉。
     *
     * 不做"是不是合法网址"的过滤：那是 [normalize] 的事，两处各判一次只会多一个不一致点。
     * 代价是"网址里带未编码逗号"的极端情况会被拆错 —— 影视站首页地址里不会出现它。
     */
    fun splitUrls(raw: String): List<String> =
        raw.split(Regex("[\\s,，;；|]+")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** 某个输入的第一个地址（给"不是视频站"时的嗅探兜底用） */
    private fun sniffTarget(rawInput: String): String =
        normalize(splitUrls(rawInput).firstOrNull().orEmpty()) ?: rawInput.trim()

    /**
     * 备用地址 = 探过的候选里**除选中那个之外**的（v1.0.67）。
     *
     * 按 host 去重：同一个 host 的深层页面（`/watch/1`）与它的 origin 是同一个入口，
     * 都塞进备用列表只会让运行时白探一遍。
     *
     * 一条都没有时返回 **null** 而不是空列表：让"这个站有没有备用地址"在存储里
     * 一眼可判，也免得给单地址的站在配置里留一个永远为空的字段。
     */
    private fun spareOf(bases: List<String>, chosen: String): List<String>? {
        val ch = hostOf(chosen)
        val out = bases.filter { it != chosen && hostOf(it) != ch }
        return out.ifEmpty { null }
    }

    private fun normalize(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        val m = Regex("^https?://[^/\\s]+", RegexOption.IGNORE_CASE).find(s) ?: return null
        if (m.value.length <= 8) return null
        return s
    }

    private fun buildBases(raw: String): List<String> {
        var s = raw
        val q = s.indexOf('?')
        if (q > 0) s = s.substring(0, q)
        s = s.trimEnd('/')
        val lower = s.lowercase()
        val deep = lower.endsWith(".html") || lower.endsWith(".php") ||
            lower.contains("voddetail") || lower.contains("vodplay") || lower.contains("vodshow")

        val out = LinkedHashSet<String>()
        if (!deep) out.add(s)
        Regex("^(https?://[^/]+)", RegexOption.IGNORE_CASE).find(s)?.let { out.add(it.groupValues[1]) }
        return out.filter { it.length > 8 }.toList()
    }

    private fun titleOf(html: String): String {
        val m = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(html) ?: return ""
        return cleanSiteName(stripHtml(m.groupValues[1]).replace(Regex("\\s+"), " ").trim())
    }

    /**
     * 站名净化（v1.0.68，E49）：别把站长的反爬牢骚当站名。
     *
     * 实测（2026-09-24，网盘站族大巡查）：这一族站长爱把牢骚写进 `<title>` ——
     * 「再见，我们跑路了」（木偶）、「随机接口纯自用，求大佬们别爬了」（快映）、
     * 「自用求大佬不要爬！」（蜡笔）、「网站关闭」（欧哥）—— 而站本身**活得好好的**
     * （首页 2000+ 卡片）。旧逻辑原样采纳 ⇒ 用户在站点列表里看到的是
     * 「跑路了」「已关站」，以为站全死了（实际是我们/网络的问题）。
     *
     * 做法：按标点与空白**切子句**，含牢骚词的子句整段剔除；剔完为空 ⇒ 返回空串，
     * 调用方回落 `hostOf(base)` —— 一个朴素的域名比一句「跑路了」诚实得多。
     * 词表刻意收窄（「跑路/别爬/关站/自用/再见…」在正经站名里几乎不出现），
     * **不写域名白名单**：判据是词汇形状，换域名的站照样命中。
     *
     * ⚠️ 必须 public + `@JvmOverloads`：`internal` 在 JVM 侧有名字修饰、
     * 且 Kotlin 默认参数对 Java 不可见 —— 守卫 harness（Java）要直接调它。
     */
    @JvmOverloads
    fun cleanSiteName(raw: String, hostFallback: String = ""): String {
        if (raw.isBlank()) return hostFallback
        val gripes = listOf(
            "跑路", "别爬", "不要爬", "勿爬", "禁止爬", "关站", "网站关闭", "关闭",
            "自用", "再见", "断更", "失联", "停止运营", "停止更新", "求大佬"
        )
        val kept = raw.split(Regex("[，,。！!？?；;|·…\\-—\\s]+"))
            .filter { p ->
                val t = p.trim()
                t.isNotEmpty() && gripes.none { t.contains(it) }
            }
        val name = kept.joinToString(" ").trim().take(30)
        return name.ifBlank { hostFallback }
    }

    internal fun hostOf(url: String): String =
        Regex("^https?://([^/]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1).orEmpty()

    private fun classify(body: String): String? {
        val t = body.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("{")) {
            val j = runCatching { JSONObject(t) }.getOrNull() ?: return null
            return if (j.has("class") || j.has("list")) SiteConfig.MODE_MACCMS_JSON else null
        }
        if (t.startsWith("<") && (t.contains("<rss") || t.contains("<list") || t.contains("<?xml"))) {
            return SiteConfig.MODE_MACCMS_XML
        }
        return null
    }

    private fun hasData(body: String, mode: String): Boolean = if (mode == SiteConfig.MODE_MACCMS_JSON) {
        runCatching {
            val j = JSONObject(body.trim())
            (j.optJSONArray("class")?.length() ?: 0) > 0 || (j.optJSONArray("list")?.length() ?: 0) > 0
        }.getOrDefault(false)
    } else {
        body.contains("<ty ") || body.contains("<ty>") || body.contains("<video>")
    }

    private fun looksLikeVideoSite(html: String): Boolean {
        val head = html.take(300_000).lowercase()
        var hit = 0
        for (w in VIDEO_WORDS) {
            if (head.contains(w)) {
                hit++
                if (hit >= 2) return true
            }
        }
        return false
    }
}
