package com.videoshell.data.site

import com.videoshell.data.model.Category
import com.videoshell.data.model.Episode
import com.videoshell.data.model.SiteConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** 苹果CMS / 海洋CMS 系接口的公共处理 */
object MaccmsKit {

    /** 拼接口地址：apiUrl 只保留 path，固定参数（如 at=json）与本次参数依次追加 */
    fun apiUrl(site: SiteConfig, params: Map<String, String>): String {
        val base = if (site.apiUrl.isNotBlank()) site.apiUrl else site.baseUrl
        val sb = StringBuilder(base)
        val parts = ArrayList<String>()
        if (site.fixedParams.isNotBlank()) parts.add(site.fixedParams)
        for ((k, v) in params) parts.add(k + "=" + URLEncoder.encode(v, "UTF-8"))
        if (parts.isNotEmpty()) {
            sb.append(if (base.contains("?")) "&" else "?")
            sb.append(parts.joinToString("&"))
        }
        return sb.toString()
    }

    fun str(o: JSONObject, key: String): String {
        if (!o.has(key) || o.isNull(key)) return ""
        val v = o.optString(key, "")
        return if (v == "null") "" else v.trim()
    }

    /** 顶层分类（type_pid = 0）；没有层级信息时返回全部 */
    fun topCategories(arr: JSONArray?): List<Category> {
        if (arr == null) return emptyList()
        val all = ArrayList<Category>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = str(o, "type_id").ifBlank { str(o, "id") }
            val name = str(o, "type_name").ifBlank { str(o, "name") }
            if (id.isBlank() || name.isBlank()) continue
            all.add(Category(id, name, str(o, "type_pid").ifBlank { "0" }))
        }
        val tops = all.filter { it.pid == "0" }
        return if (tops.size >= 2) tops else all
    }

    /** "第01集$http://a.m3u8#第02集$http://b.m3u8" -> 剧集列表 */
    fun parseEpisodes(raw: String): List<Episode> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<Episode>()
        for (seg in raw.split("#")) {
            val s = seg.trim()
            if (s.isEmpty()) continue
            val idx = s.indexOf('$')
            if (idx > 0) {
                val n = s.substring(0, idx).trim()
                val u = s.substring(idx + 1).trim()
                if (u.isNotEmpty()) out.add(Episode(n.ifBlank { "第${out.size + 1}集" }, u))
            } else if (s.startsWith("http") || Media.isDirect(s)) {
                out.add(Episode("第${out.size + 1}集", s))
            }
        }
        return out
    }

    const val SEP_GROUP = "$$$"
}
