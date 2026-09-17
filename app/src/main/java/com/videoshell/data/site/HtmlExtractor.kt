package com.videoshell.data.site

import com.videoshell.data.model.Episode
import com.videoshell.data.model.PlayGroup
import com.videoshell.data.model.VideoItem
import com.videoshell.util.resolveUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** HTML 站点的通用抽取器（尽力而为：按常见 maccms 模板的 class 约定抓取） */
object HtmlExtractor {

    private val PIC_ATTRS = listOf("data-original", "data-src", "data-echo", "src")
    private val NOTE_SELECTORS =
        ".pic-text, .module-item-note, .public-list-prb, .v-tag, .note, .time-title, .module-item-text, .module-card-item-note, .tag"

    fun parseList(doc: Document, base: String): List<VideoItem> {
        val out = LinkedHashMap<String, VideoItem>()
        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            val id = HtmlTemplates.videoIdOf(href) ?: continue
            if (id.isBlank()) continue

            val img = a.selectFirst("img")
                ?: a.parent()?.selectFirst("img")
                ?: a.parent()?.parent()?.selectFirst("img")

            var name = a.attr("title").trim()
            if (name.isBlank()) name = a.text().trim()
            if (name.isBlank()) name = img?.attr("alt").orEmpty().trim()
            if (name.isBlank() || name.length > 40) continue

            val pic = img?.let { im ->
                PIC_ATTRS.firstNotNullOfOrNull { at ->
                    im.attr(at).trim().takeIf { s -> s.isNotBlank() && !s.startsWith("data:") }
                }
            }.orEmpty()

            val box: Element? = a.parent()
            var remarks = box?.selectFirst(NOTE_SELECTORS)?.text()?.trim().orEmpty()
            if (remarks.isBlank()) {
                remarks = a.selectFirst(NOTE_SELECTORS)?.text()?.trim().orEmpty()
            }
            if (remarks.length > 20) remarks = ""

            if (!out.containsKey(id)) {
                out[id] = VideoItem(id = id, name = name, pic = resolveUrl(base, pic), remarks = remarks)
            }
        }
        return out.values.toList()
    }

    private val PLAY_LIST_SELECTORS = listOf(
        ".module-play-list", ".playlist", ".ff-playurl", "#playlist", ".play-list",
        ".stui-content__playlist", ".content-playlist", ".playlist-content"
    )

    private fun isPlayLink(href: String): Boolean =
        href.contains("vodplay") || href.contains("/play/") || href.contains("-1-1.html") ||
            href.contains("play.html") || href.contains("/vod/play")

    fun parseGroups(doc: Document, base: String): List<PlayGroup> {
        val tabNames = doc.select(".module-tab-item, .stui-pannel__head h3, .playlist-title, .play-source-tab")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val containers = LinkedHashSet<Element>()
        for (s in PLAY_LIST_SELECTORS) containers.addAll(doc.select(s))
        val pools: Collection<Element> = if (containers.isNotEmpty()) containers else doc.select("ul")

        val out = ArrayList<PlayGroup>()
        var idx = 0
        for (c in pools) {
            val eps = ArrayList<Episode>()
            val seen = HashSet<String>()
            for (a in c.select("a[href]")) {
                val href = a.attr("href")
                if (!isPlayLink(href)) continue
                val u = resolveUrl(base, href)
                if (u.isBlank() || !seen.add(u)) continue
                var n = a.text().trim()
                if (n.isBlank()) n = a.attr("title").trim()
                eps.add(Episode(n.ifBlank { "第${eps.size + 1}集" }, u))
            }
            if (eps.isEmpty()) continue

            var name = tabNames.getOrNull(idx)?.trim()?.take(16).orEmpty()
            if (name.isBlank()) name = guessGroupName(c, idx)
            out.add(PlayGroup(name, eps))
            idx++
        }
        return out
    }

    private fun guessGroupName(ul: Element, idx: Int): String {
        var p: Element? = ul
        var depth = 0
        while (p != null && depth < 4) {
            val t = p.selectFirst(".module-tab-item, .playlist-title, h3, .title")
            val txt = t?.text()?.trim().orEmpty()
            if (txt.isNotBlank() && txt.length <= 16) return txt
            p = p.parent()
            depth++
        }
        return "线路 ${idx + 1}"
    }
}
