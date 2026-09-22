package com.videoshell.data.site

import com.videoshell.data.model.Category
import com.videoshell.data.model.Episode
import com.videoshell.data.model.PlayGroup
import com.videoshell.data.model.SiteConfig
import com.videoshell.data.model.VideoDetail
import com.videoshell.data.model.VideoItem
import com.videoshell.data.net.Http
import com.videoshell.util.resolveUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * 「签名种子配置」族的接口适配器（v1.0.53）。
 *
 * 由 [SeedFamily] 自证命中后使用，[apiBase] 来自站点自己的 `config.json`。
 * 这一族的**接口形状是固定的**（同一份前端模板），所以字段名写死是安全的 ——
 * 需要记的只有一件事：**地址不写死，全部相对 [apiBase] 拼**，这样换域名不用改代码。
 *
 * ## 接口（实测 huangju.net，2026-09-22）
 *
 * | 用途 | 请求 | 关键字段 |
 * |---|---|---|
 * | 分类 | `GET {api}/categories` | `[{slug,name}]`（实测 264 条） |
 * | 列表 | `GET {api}/dramas?page=N&pageSize=M` | `{total,items[{id,slug,title,coverUrl,totalEpisodes}]}` |
 * | 分类筛选 | `GET {api}/dramas?category={slug}` | 同上 |
 * | 搜索 | `GET {api}/dramas?q={关键词}` | 同上（`keyword=` **不认**，必须是 `q`） |
 * | 详情 | `GET {api}/dramas/{slug}-{id}` | 加 `episodes[{id,epNo,title,playable}]` |
 * | 播放 | `GET {api}/play/{episodeId}` | `{url,expiresAt}` + **CloudFront 签名 Cookie** |
 *
 * ## 三个必须记住的坑
 *
 * 1. **详情 id 是 `{slug}-{id}` 两段**（不是纯数字）。列表里两个字段都在，
 *    所以 `VideoItem.id` 存**拼好的**那个，详情直接用 —— 少一次"从 id 反查 slug"的猜测。
 * 2. **播放地址不在任何 HTML 里**：`<video>` 是空的、`src` 由 JS 设。
 *    集地址直接给 `{api}/play/{id}`，由 [SiteAdapter.resolve] 的「JSON 接口直出」那一步取回，
 *    **不需要 WebView 嗅探**。
 * 3. **播放地址由签名 Cookie 授权**（`/play` 在下发 JSON 的同时 `Set-Cookie`
 *    `CloudFront-Policy/Signature/Key-Pair-Id`，`Domain=.huangju.net`）——
 *    它能不能送到 `video.huangju.net` 上，取决于共用 CookieJar 是否正确跨子域，
 *    与本站适配器无关（见 `Http.cookieJar` 的注释）。
 */
class SeedApiAdapter(site: SiteConfig, private val apiBase: String) : SiteAdapter(site) {

    private var diag: String = ""
    override val lastDiag: String get() = diag

    /** 列表每页条数：比接口默认的 20 大一点，竖屏 2 列正好铺满几屏 */
    private val pageSize = 24

    // ------------------------------------------------------------------ 请求

    private fun url(path: String, params: List<Pair<String, String>>): String {
        val sb = StringBuilder(apiBase).append(path)
        var first = true
        for ((k, v) in params) {
            if (v.isBlank()) continue
            sb.append(if (first) "?" else "&")
            first = false
            sb.append(k).append('=').append(URLEncoder.encode(v, "UTF-8"))
        }
        return sb.toString()
    }

    private suspend fun getJson(url: String): JSONObject {
        val body = Http.get(url, referer = site.baseUrl)
        runCatching { return JSONObject(body.trim()) }
        // 前面可能挂了 BOM / 防盗链脚本，退回"取最外层那一对花括号"
        val s = body.indexOf('{')
        val e = body.lastIndexOf('}')
        if (s >= 0 && e > s) runCatching { return JSONObject(body.substring(s, e + 1)) }
        throw IOException("接口返回不是有效 JSON")
    }

    private fun str(o: JSONObject, key: String): String {
        if (!o.has(key) || o.isNull(key)) return ""
        val v = o.optString(key, "")
        return if (v == "null") "" else v.trim()
    }

    // ------------------------------------------------------------------ 契约

    override suspend fun categories(): List<Category> {
        diag = ""
        val body = runCatching { Http.get(url("/categories", emptyList()), referer = site.baseUrl) }
            .getOrElse {
                diag = "分类接口无响应：" + it.javaClass.simpleName
                return emptyList()
            }
        val arr = runCatching { JSONArray(body.trim()) }.getOrNull()
        if (arr == null) {
            diag = "分类接口返回不是数组"
            return emptyList()
        }
        val out = ArrayList<Category>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // ⚠️ id 存 **slug**：浏览时要拿它当 `?category=`，而数字 id 那个参数
            // 接口**不认**（实测 `?categoryId=` 会返回全量 1393 条，静默忽略）。
            val slug = str(o, "slug")
            val name = str(o, "name")
            if (slug.isBlank() || name.isBlank()) continue
            out.add(Category(slug, name, "0"))
        }
        if (out.isEmpty()) diag = "分类接口未返回条目"
        return out
    }

    override suspend fun browse(typeId: String, page: Int): List<VideoItem> {
        diag = ""
        val params = listOf(
            "page" to "$page",
            "pageSize" to "$pageSize",
            // typeId 为空 = 界面那个合成的「最新」项 ⇒ 不带 category，取全量
            "category" to typeId
        )
        val j = runCatching { getJson(url("/dramas", params)) }.getOrElse {
            diag = "列表接口失败：" + it.javaClass.simpleName
            return emptyList()
        }
        return items(j.optJSONArray("items"))
    }

    override suspend fun search(keyword: String, page: Int): List<VideoItem> {
        diag = ""
        // 实测：这个接口的搜索参数是 `q`，`keyword=` 会被**静默忽略**（返回全量 1393 条），
        // 症状是"搜什么都一样" —— 与 S1 那条坑同形，所以写死在注释里。
        val params = listOf("page" to "$page", "pageSize" to "$pageSize", "q" to keyword)
        val j = runCatching { getJson(url("/dramas", params)) }.getOrElse {
            diag = "搜索接口失败：" + it.javaClass.simpleName
            return emptyList()
        }
        return items(j.optJSONArray("items"))
    }

    override suspend fun detail(id: String): VideoDetail {
        diag = ""
        val raw = id.trim().trim('/')
        if (raw.isBlank()) throw IOException("详情 id 为空")
        val j = getJson("$apiBase/dramas/$raw")

        // 先收 (epNo, Episode)，再按 epNo 排 —— 接口不保证有序（实测分集偶有乱序）。
        // ⚠️ 排序判据只能有一份：这里排完，交给界面的就已经是最终顺序（同 D8 那条坑）。
        val staged = ArrayList<Pair<Int, Episode>>()
        val arr = j.optJSONArray("episodes")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // playable=false 的是"还没上线"，放进去只会点开报错
                if (o.has("playable") && !o.isNull("playable") && !o.optBoolean("playable", true)) continue
                val eid = str(o, "id")
                if (eid.isBlank()) continue
                val epNo = o.optInt("epNo", i + 1)
                val name = str(o, "title").ifBlank { "第${epNo}集" }
                // 集地址就是播放接口；真 m3u8 由 resolve() 的「JSON 接口直出」取回
                staged.add(epNo to Episode(name, "$apiBase/play/$eid"))
            }
        }
        val eps = staged.sortedBy { it.first }.map { it.second }

        val cat = j.optJSONObject("category")
        val total = j.optInt("totalEpisodes", eps.size)
        return VideoDetail(
            id = raw,
            name = str(j, "title"),
            pic = resolveUrl(site.baseUrl, str(j, "coverUrl")),
            remarks = if (total > 1) "${total}集" else "",
            typeName = cat?.let { str(it, "name") } ?: "",
            year = str(j, "year"),
            area = str(j, "region"),
            summary = str(j, "description"),
            groups = if (eps.isEmpty()) emptyList() else listOf(PlayGroup("默认", eps))
        )
    }

    private fun items(arr: JSONArray?): List<VideoItem> {
        if (arr == null) return emptyList()
        val out = ArrayList<VideoItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val slug = str(o, "slug")
            val num = str(o, "id")
            val title = str(o, "title")
            if (title.isBlank() || num.isBlank()) continue
            val total = o.optInt("totalEpisodes", 0)
            out.add(
                VideoItem(
                    // 详情要 `{slug}-{id}` 两段一起；这里拼好，详情直接拿它请求
                    id = if (slug.isBlank()) num else "$slug-$num",
                    name = title,
                    pic = resolveUrl(site.baseUrl, str(o, "coverUrl")),
                    remarks = if (total > 1) "${total}集" else "",
                    score = str(o, "score").let { if (it == "0.0" || it == "0") "" else it }
                )
            )
        }
        return out
    }
}
