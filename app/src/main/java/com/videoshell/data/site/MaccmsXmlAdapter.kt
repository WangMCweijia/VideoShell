package com.videoshell.data.site

import com.videoshell.data.model.Category
import com.videoshell.data.model.PlayGroup
import com.videoshell.data.model.SiteConfig
import com.videoshell.data.model.VideoDetail
import com.videoshell.data.model.VideoItem
import com.videoshell.data.net.Http
import com.videoshell.util.stripHtml
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.IOException

/** 苹果CMS / 海洋CMS 的 XML 接口适配器 */
class MaccmsXmlAdapter(site: SiteConfig) : SiteAdapter(site) {

    private suspend fun doc(params: Map<String, String>): Document {
        val body = Http.get(MaccmsKit.apiUrl(site, params), referer = site.baseUrl)
        if (!body.contains("<")) throw IOException("接口返回不是有效 XML")
        return Jsoup.parse(body, site.baseUrl, Parser.xmlParser())
    }

    override suspend fun categories(): List<Category> {
        val d = doc(mapOf("ac" to "list"))
        val out = ArrayList<Category>()
        for (ty in d.select("class > ty")) {
            val id = ty.attr("id").trim()
            val name = ty.text().trim()
            if (id.isBlank() || name.isBlank()) continue
            out.add(Category(id, name, "0"))
        }
        return out
    }

    override suspend fun browse(typeId: String, page: Int): List<VideoItem> {
        val p = HashMap<String, String>()
        p["ac"] = "detail"
        p["pg"] = "$page"
        if (typeId.isNotBlank()) p["t"] = typeId
        return parseList(runCatching { doc(p) }.getOrNull())
    }

    override suspend fun search(keyword: String, page: Int): List<VideoItem> {
        val p = HashMap<String, String>()
        p["ac"] = "detail"
        p["wd"] = keyword
        p["pg"] = "$page"
        return parseList(runCatching { doc(p) }.getOrNull())
    }

    override suspend fun detail(id: String): VideoDetail {
        val d = doc(mapOf("ac" to "detail", "ids" to id))
        val v = d.selectFirst("list > video") ?: throw IOException("未获取到影片详情")
        val groups = ArrayList<PlayGroup>()
        for ((i, dd) in v.select("dl > dd").withIndex()) {
            val eps = MaccmsKit.parseEpisodes(dd.text())
            if (eps.isEmpty()) continue
            val flag = dd.attr("flag").trim()
            groups.add(PlayGroup(flag.ifBlank { "线路 ${groups.size + 1}" }, eps))
        }
        if (groups.isEmpty()) {
            // 个别接口把 dl 放在 ll 里的另一个层级，兜底直接扫所有 dd
            for ((i, dd) in v.select("dd").withIndex()) {
                val eps = MaccmsKit.parseEpisodes(dd.text())
                if (eps.isEmpty()) continue
                val flag = dd.attr("flag").trim()
                groups.add(PlayGroup(flag.ifBlank { "线路 ${groups.size + 1}" }, eps))
            }
        }
        return VideoDetail(
            id = id,
            name = v.selectFirst("name")?.text().orEmpty().trim(),
            pic = v.selectFirst("pic")?.text().orEmpty().trim(),
            remarks = v.selectFirst("note")?.text().orEmpty().trim(),
            typeName = v.selectFirst("type")?.text().orEmpty().trim(),
            year = v.selectFirst("year")?.text().orEmpty().trim(),
            area = v.selectFirst("area")?.text().orEmpty().trim(),
            actor = v.selectFirst("actor")?.text().orEmpty().trim(),
            director = v.selectFirst("director")?.text().orEmpty().trim(),
            summary = stripHtml(v.selectFirst("des")?.text().orEmpty()),
            groups = groups
        )
    }

    private fun parseList(d: Document?): List<VideoItem> {
        d ?: return emptyList()
        val out = ArrayList<VideoItem>()
        for (v in d.select("list > video")) {
            val id = v.selectFirst("id")?.text().orEmpty().trim()
            val name = v.selectFirst("name")?.text().orEmpty().trim()
            if (id.isBlank() || name.isBlank()) continue
            out.add(
                VideoItem(
                    id = id,
                    name = name,
                    pic = v.selectFirst("pic")?.text().orEmpty().trim(),
                    remarks = v.selectFirst("note")?.text().orEmpty().trim(),
                    typeName = v.selectFirst("type")?.text().orEmpty().trim(),
                    year = v.selectFirst("year")?.text().orEmpty().trim(),
                    area = v.selectFirst("area")?.text().orEmpty().trim()
                )
            )
        }
        return out
    }
}
