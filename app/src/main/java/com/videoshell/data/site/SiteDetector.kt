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
        val url = normalize(rawInput) ?: return@withContext Result(null, "网址格式不正确，请检查后重试", false)
        val bases = buildBases(url)
        if (bases.isEmpty()) return@withContext Result(null, "网址格式不正确，请检查后重试", false)

        // 1) 首页探活 + 取站点标题
        var base = bases.first()
        var homeHtml: String? = null
        for (b in bases) {
            val h = Http.getOrNull(b, referer = b, fast = true)
            if (!h.isNullOrBlank()) {
                homeHtml = h
                base = b
                break
            }
        }
        val siteName = homeHtml?.let { titleOf(it) }.orEmpty().ifBlank { hostOf(base) }

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
                createdAt = System.currentTimeMillis()
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
                createdAt = System.currentTimeMillis()
            )
            return@withContext Result(
                site,
                "未找到标准采集接口，已启用 HTML 通用适配（搜索/分类可能不完整，播放可走嗅探）",
                true
            )
        }

        Result(null, "未能识别为视频站。可点「用网页嗅探打开」直接嗅探播放。", false, pageUrl = url)
    }

    // ---------------- 内部工具 ----------------

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

    private fun hostOf(url: String): String =
        Regex("^https?://([^/]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1).orEmpty()

    private fun titleOf(html: String): String {
        val m = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(html) ?: return ""
        return stripHtml(m.groupValues[1]).replace(Regex("\\s+"), " ").trim().take(30)
    }

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
