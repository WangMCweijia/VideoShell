package com.videoshell.data.site

import com.videoshell.data.model.Category
import com.videoshell.data.model.PlayGroup
import com.videoshell.data.model.SiteConfig
import com.videoshell.data.model.VideoDetail
import com.videoshell.data.model.VideoItem
import com.videoshell.data.net.Http
import com.videoshell.util.stripHtml
import org.json.JSONObject
import java.io.IOException

/** 苹果CMS v10 / 海洋CMS 的 JSON 接口适配器（覆盖绝大多数视频站） */
class MaccmsAdapter(site: SiteConfig) : SiteAdapter(site) {

    private suspend fun load(params: Map<String, String>): JSONObject {
        val body = Http.get(MaccmsKit.apiUrl(site, params), referer = site.baseUrl)
        runCatching { return JSONObject(body.trim()) }
        val s = body.indexOf('{')
        val e = body.lastIndexOf('}')
        if (s >= 0 && e > s) {
            runCatching { return JSONObject(body.substring(s, e + 1)) }
        }
        throw IOException("接口返回不是有效 JSON")
    }

    override suspend fun categories(): List<Category> {
        val j = load(mapOf("ac" to "list"))
        val tops = MaccmsKit.topCategories(j.optJSONArray("class"))
        if (tops.isNotEmpty()) return tops
        val j2 = load(mapOf("ac" to "detail", "pg" to "1"))
        return MaccmsKit.topCategories(j2.optJSONArray("class"))
    }

    override suspend fun browse(typeId: String, page: Int): List<VideoItem> {
        val p = HashMap<String, String>()
        p["ac"] = "detail"
        p["pg"] = "$page"
        if (typeId.isNotBlank()) p["t"] = typeId
        var items = parseList(runCatching { load(p) }.getOrNull())
        if (items.isEmpty()) {
            p["ac"] = "list"
            items = parseList(runCatching { load(p) }.getOrNull())
        }
        return items
    }

    override suspend fun search(keyword: String, page: Int): List<VideoItem> {
        val p = HashMap<String, String>()
        p["ac"] = "detail"
        p["wd"] = keyword
        p["pg"] = "$page"
        var items = parseList(runCatching { load(p) }.getOrNull())
        if (items.isEmpty()) {
            p["ac"] = "list"
            items = parseList(runCatching { load(p) }.getOrNull())
        }
        return items
    }

    override suspend fun detail(id: String): VideoDetail {
        val j = load(mapOf("ac" to "detail", "ids" to id))
        val arr = j.optJSONArray("list")
        val o = if (arr != null && arr.length() > 0) arr.optJSONObject(0) else null
        o ?: throw IOException("未获取到影片详情")

        val froms = MaccmsKit.str(o, "vod_play_from").split(MaccmsKit.SEP_GROUP)
        val urls = MaccmsKit.str(o, "vod_play_url").split(MaccmsKit.SEP_GROUP)
        val groups = ArrayList<PlayGroup>()
        for (i in urls.indices) {
            val eps = MaccmsKit.parseEpisodes(urls[i])
            if (eps.isEmpty()) continue
            val raw = froms.getOrNull(i)?.trim().orEmpty()
            val name = if (raw.isBlank()) "线路 ${groups.size + 1}" else raw
            groups.add(PlayGroup(name, eps))
        }

        return VideoDetail(
            id = MaccmsKit.str(o, "vod_id").ifBlank { id },
            name = MaccmsKit.str(o, "vod_name"),
            pic = MaccmsKit.str(o, "vod_pic"),
            remarks = MaccmsKit.str(o, "vod_remarks"),
            typeName = MaccmsKit.str(o, "type_name"),
            year = MaccmsKit.str(o, "vod_year"),
            area = MaccmsKit.str(o, "vod_area"),
            actor = MaccmsKit.str(o, "vod_actor"),
            director = MaccmsKit.str(o, "vod_director"),
            summary = stripHtml(MaccmsKit.str(o, "vod_content")),
            groups = groups
        )
    }

    private fun parseList(j: JSONObject?): List<VideoItem> {
        val arr = j?.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<VideoItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = MaccmsKit.str(o, "vod_id")
            val name = MaccmsKit.str(o, "vod_name")
            if (id.isBlank() || name.isBlank()) continue
            out.add(
                VideoItem(
                    id = id,
                    name = name,
                    pic = MaccmsKit.str(o, "vod_pic"),
                    remarks = MaccmsKit.str(o, "vod_remarks"),
                    typeName = MaccmsKit.str(o, "type_name"),
                    score = MaccmsKit.str(o, "vod_score").let { if (it == "0.0" || it == "0") "" else it },
                    year = MaccmsKit.str(o, "vod_year"),
                    area = MaccmsKit.str(o, "vod_area")
                )
            )
        }
        return out
    }
}
