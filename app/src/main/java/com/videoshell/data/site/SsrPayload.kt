package com.videoshell.data.site

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.videoshell.data.model.Episode
import com.videoshell.data.model.PlayGroup

/**
 * 从「页面内嵌的 JSON」里取分集列表（详情页/播放页的 DOM 抠不出分集时的兜底）。
 *
 * ## 为什么需要这一层（v1.0.16）
 *
 * 野果短剧那类 **Nuxt3 / Vue SSR 自研站**，分集按钮走前端路由：**服务端渲染出来的 DOM 里几乎没有分集信息**。
 * 实测详情页 `episode-list` 容器里只有 1~3 个 `<a>`，而且 href 全是同一个
 * （`/drama/video/{id}/`）—— 集号靠客户端 JS 从路由读取。`HtmlExtractor.parseGroups`
 * 面对这种 DOM 只能返回空，用户看到的就是「**获取不到分集列表**」。
 *
 * 但页面里其实**有全量数据**：SSR 把接口响应整块塞进 `<script id="__NUXT_DATA__">`，
 * 其中 `episodeAll` 是完整分集数组，而且**每一集自带独立播放地址**（`video_url`）。
 * 本对象负责把那块 JSON 解出来。
 *
 * ## 支持两种编码
 *
 * 1. **devalue 扁平数组**（Nuxt 3 的格式）：数组里每个值要么是字面量，
 *    要么是**指向同数组的下标**。例如 `{"episodeAll": 30}` 配 `arr[30] = [31, 39, ...]`，
 *    `arr[31] = {"video_url": 34, ...}`，`arr[34] = "https://…m3u8?auth_key=…"`。
 *    ⇒ 必须整棵树按「下标 = 引用」解析，不能当普通 JSON 读。
 * 2. **普通嵌套 JSON**：`<script type="application/json">`、`__NEXT_DATA__`、
 *    `window.__INITIAL_STATE__ = {...}`。
 *
 * ## 挑哪一条数组：靠「元素形态」而不是靠键名
 *
 * 不去找叫 `episodes` / `episodeAll` 的键（每站命名都不同），而是找
 * **「每一项都是对象，且每一项都带一个像媒体地址的 URL」** 的那条数组。
 * 这个判据天然排掉封面列表（图是 `.jpg`）、推荐列表（没有地址）——
 * 实测本站 `episodeAll` 全中，`related_list`、`actorList` 全不中。
 * 多条命中时取**最长的那条**。
 *
 * ## ⚠️ 拿到的地址带时效签名，**只能当次用，绝不能固化**
 *
 * 这类站的 `video_url` 形如
 * `https://op.udhhzr.cn/videos5/<hash>/<hash>.m3u8?auth_key=1789657042-6aab…&v=2`，
 * `auth_key` 是 **几小时后过期** 的签名。所以它只能作为「本次播放地址」直接交给播放器；
 * 配方里固化的永远是**模板**（怎么找），不是**它找到的东西**（带签名的直链）——
 * 这是 v1.0.11 就定下的原则，别在这里破例。
 *
 * 取不到一律返回空列表、**不抛异常**：它只是兜底路径，失败要继续走后面的分支。
 */
object SsrPayload {

    /**
     * devalue 用 `["<标记>", 下标]` 表示「包装类型」，遇到就往下钻一层。
     * 第一个元素落在这些名字里才认为整个 payload 是扁平数组格式。
     */
    private val WRAPPERS = setOf(
        "ShallowReactive", "Reactive", "Ref", "ShallowRef", "EmptyRef",
        "Object", "Set", "Map", "Date", "BigInt", "RegExp", "URL", "NumericString"
    )

    /** 解析预算：正常 payload 几百个节点；给足余量，但必须能兜住环与异常大的页面 */
    private const val MAX_NODES = 80_000
    private const val MAX_DEPTH = 26

    /** 单块 JSON 的大小上限（超过就不解析了，避免超大页面把内存打爆） */
    private const val MAX_BLOB = 4 shl 20

    /** 一条分集：集号（1 起）、显示名、播放地址 */
    data class Ep(val no: Int, val name: String, val url: String)

    // ------------------------------------------------------------------ 对外

    /** 从 HTML 里取分集；取不到返回空列表 */
    fun episodes(html: String?): List<Ep> {
        val text = html ?: return emptyList()
        if (text.length < 64) return emptyList()
        var best: List<Ep> = emptyList()
        for (blob in jsonBlobs(text)) {
            if (blob.isBlank()) continue
            val root = runCatching { JsonParser.parseString(blob) }.getOrNull() ?: continue

            val found = ArrayList<List<Ep>>()

            // ① 扁平数组格式：先按「值即下标」整棵树解析，再在结果里找
            val flat = runCatching { resolveFlat(root) }.getOrNull()
            if (flat != null) collectEpisodeLists(flat, found, 0, IntArray(1) { MAX_NODES })

            // ② 普通嵌套 JSON 兜底：直接扫原始结构
            collectEpisodeLists(root, found, 0, IntArray(1) { MAX_NODES })

            // ③ 还有一种更粗暴但常见的形态：整页就是一个大 JSON 字符串（转义过的）
            //    这里不展开 —— 上面的正则已经把 <script> 内容原样取出来了。

            for (l in found) if (l.size > best.size) best = l
        }
        return best
    }

    /** 映射成 App 的统一分组结构（单线路） */
    fun groups(html: String?): List<PlayGroup> {
        val eps = episodes(html)
        if (eps.isEmpty()) return emptyList()
        return listOf(PlayGroup(DEFAULT_GROUP, eps.map { Episode(it.name, it.url) }))
    }

    /** 单线路站点的分组名（分组标签在 UI 上会显示，别留空串） */
    const val DEFAULT_GROUP = "默认"

    // ------------------------------------------------------------------ JSON 提取

    /** `<script id="__NUXT_DATA__">` / `__NEXT_DATA__` / `__INITIAL_STATE__` 之类，最可靠 */
    private val SCRIPT_WITH_ID = Regex(
        "<script[^>]*\\bid\\s*=\\s*[\"'](__NUXT_DATA__|__NUXT__|__NEXT_DATA__|__INITIAL_STATE__|__APOLLO_STATE__)[\"'][^>]*>([\\s\\S]*?)</script>",
        RegexOption.IGNORE_CASE
    )

    /** 任何 `<script type="application/json">`（Nuxt 3 之外最常见的形态） */
    private val SCRIPT_JSON = Regex(
        "<script[^>]*\\btype\\s*=\\s*[\"']application/(?:ld\\+)?json[\"'][^>]*>([\\s\\S]*?)</script>",
        RegexOption.IGNORE_CASE
    )

    /** `window.__NUXT__ = {...};` / `window.__INITIAL_STATE__ = {...};` */
    private val WINDOW_ASSIGN = Regex(
        "window\\.__[A-Z_]+__\\s*=\\s*(\\{[\\s\\S]*?\\})\\s*;\\s*(?:</script>|\\n)",
        RegexOption.IGNORE_CASE
    )

    private fun jsonBlobs(html: String): List<String> {
        val out = LinkedHashSet<String>()
        for (m in SCRIPT_WITH_ID.findAll(html)) add(out, m.groupValues[2])
        for (m in SCRIPT_JSON.findAll(html)) add(out, m.groupValues[1])
        for (m in WINDOW_ASSIGN.findAll(html)) add(out, m.groupValues[1])
        return out.toList()
    }

    private fun add(out: MutableSet<String>, raw: String) {
        val s = raw.trim()
        if (s.length < 32 || s.length > MAX_BLOB) return
        if (!s.startsWith("{") && !s.startsWith("[")) return
        out.add(s)
    }

    // ------------------------------------------------------------------ 扁平数组还原

    /**
     * 如果 [root] 是 devalue 的扁平数组，按「值即下标」还原成普通 JSON 树；否则原样返回。
     *
     * 判据：`arr[0]` 是 `["<标记>", 下标]` 形态。**这个签名必须严格**——
     * 一个「根是数组」的普通 JSON（`[{…},{…}]`）如果被误当扁平数组，
     * 里面每个数字都会被当成下标，会造出假的"分集列表"来。
     */
    private fun resolveFlat(root: JsonElement): JsonElement? {
        val arr = root as? JsonArray ?: return null
        if (arr.size() < 3) return null
        val head = arr.get(0) as? JsonArray ?: return null
        if (head.size() != 2) return null
        val tag = head.get(0) as? JsonPrimitive ?: return null
        if (!tag.isString || tag.asString !in WRAPPERS) return null

        val budget = IntArray(1) { MAX_NODES }
        return runCatching { deref(arr, 0, 0, budget, HashSet()) }.getOrNull()
    }

    private fun deref(
        arr: JsonArray,
        index: Int,
        depth: Int,
        budget: IntArray,
        visiting: MutableSet<Int>
    ): JsonElement? {
        if (budget[0] <= 0 || depth > MAX_DEPTH) return null
        if (index < 0 || index >= arr.size()) return null
        budget[0]--
        if (!visiting.add(index)) return null            // 环：不再展开

        try {
            val v = arr.get(index)
            return when {
                v.isJsonArray -> {
                    val a = v.asJsonArray
                    val wrapped = wrapperTarget(a)
                    if (wrapped != null) {
                        deref(arr, wrapped, depth + 1, budget, visiting)
                    } else {
                        val out = JsonArray()
                        for (e in a) {
                            val r = if (isRef(e)) deref(arr, e.asInt, depth + 1, budget, visiting) else e
                            out.add(r ?: continue)
                        }
                        out
                    }
                }
                v.isJsonObject -> {
                    val out = JsonObject()
                    for ((k, e) in v.asJsonObject.entrySet()) {
                        val r = if (isRef(e)) deref(arr, e.asInt, depth + 1, budget, visiting) else e
                        if (r != null) out.add(k, r)
                    }
                    out
                }
                // 数组里的字面量：这里才是真值（对象属性/数组元素里的数字才是下标）
                else -> v
            }
        } finally {
            visiting.remove(index)
        }
    }

    /** `["ShallowReactive", 3]` -> 3 */
    private fun wrapperTarget(a: JsonArray): Int? {
        if (a.size() != 2) return null
        val t = a.get(0) as? JsonPrimitive ?: return null
        if (!t.isString || t.asString !in WRAPPERS) return null
        val i = a.get(1) as? JsonPrimitive ?: return null
        return if (i.isNumber) i.asInt else null
    }

    /** 数字在「对象属性值 / 数组元素」的位置上 = 指向同数组的下标 */
    private fun isRef(e: JsonElement): Boolean {
        val p = e as? JsonPrimitive ?: return false
        return p.isNumber && !p.isString && p.asInt >= 0
    }

    // ------------------------------------------------------------------ 找分集数组

    private fun collectEpisodeLists(
        el: JsonElement,
        out: MutableList<List<Ep>>,
        depth: Int,
        budget: IntArray
    ) {
        if (depth > MAX_DEPTH || budget[0] <= 0) return
        budget[0]--
        when {
            el.isJsonArray -> {
                val a = el.asJsonArray
                toEpisodes(a)?.let { out.add(it); return }
                for (e in a) collectEpisodeLists(e, out, depth + 1, budget)
            }
            el.isJsonObject -> {
                for ((_, v) in el.asJsonObject.entrySet()) collectEpisodeLists(v, out, depth + 1, budget)
            }
        }
    }

    /**
     * 把一条数组当分集列表来试：**每一项都是对象，且每一项都带一个像媒体地址的 URL**。
     *
     * 为什么是这个判据：靠键名（`episodes` / `episodeAll`）追不上站点改版，
     * 而「一集一个可播地址」是这个语义本身。它同时天然排掉
     * 封面列表（图是 `.jpg`，不像媒体地址）、演员表、推荐位（没有地址）。
     */
    private fun toEpisodes(arr: JsonArray): List<Ep>? {
        val n = arr.size()
        if (n < 1 || n > 3000) return null

        val urls = ArrayList<String>(n)
        val titles = ArrayList<String>(n)
        val inlineNos = ArrayList<Int>(n)
        val adv = ArrayList<Boolean>(n)

        for (i in 0 until n) {
            val o = arr.get(i) as? JsonObject ?: return null
            val u = firstString(o, URL_KEYS).takeIf { it.isNotBlank() && Media.looksLikeMedia(it) }
                ?: return null
            urls.add(u)
            titles.add(firstString(o, TITLE_KEYS))
            // 0 = 这个对象没给出可用的集号
            inlineNos.add(firstInt(o, NO_KEYS) ?: 0)
            adv.add(firstBool(o, ADV_KEYS))
        }

        // 集号来源优先级：标题里的「第N集」 > 结构字段（index/sort/…） > 出现顺序。
        //
        // 实测本站：`episodeAll` 的顺序是**乱序**（依次是第 3、2、1、4、5 集），
        // 而 `sort` 恒为 1、`index` 只是数组下标 —— 只有标题里的集号是准的。
        // 所以标题能用就一定要用，否则会把集号整体错位（点第 2 集播到第 4 集）。
        val fromTitle = ArrayList<Int>(n)
        var titleOk = true
        for (t in titles) {
            val no = TITLE_NO.find(t)?.groupValues?.get(1)?.toIntOrNull()
            if (no == null || no <= 0) { titleOk = false; break }
            fromTitle.add(no)
        }
        val useTitle = titleOk && fromTitle.toSet().size == n
        val inlineOk = inlineNos.none { it == 0 } && inlineNos.toSet().size == n

        val seen = HashSet<Int>()
        val eps = ArrayList<Ep>(n)
        for (i in 0 until n) {
            if (adv[i]) continue                                   // 插播广告不是正片
            val no = when {
                useTitle -> fromTitle[i]
                inlineOk -> inlineNos[i]
                else -> i + 1                                      // 都不可靠 ⇒ 按出现顺序编号
            }
            if (!seen.add(no)) continue
            eps.add(Ep(no, "第${no}集", urls[i]))
        }
        if (eps.isEmpty()) return null
        eps.sortBy { it.no }
        return eps
    }

    private val TITLE_NO = Regex("第\\s*(\\d{1,4})\\s*[集话]")

    private val URL_KEYS = listOf("video_url", "videoUrl", "play_url", "playUrl", "m3u8", "url")
    private val TITLE_KEYS = listOf("episode_title", "episodeTitle", "title", "name", "episode_name")
    private val NO_KEYS = listOf("episode_sort", "episodeSort", "episode_no", "epNo", "sort", "index", "ep")
    private val ADV_KEYS = listOf("is_adv", "isAdv", "is_ad", "isAd", "ad")

    private fun firstString(o: JsonObject, keys: List<String>): String {
        for (k in keys) {
            val e = o.get(k) ?: continue
            if (e.isJsonPrimitive) {
                val p = e.asJsonPrimitive
                if (p.isString) return p.asString
                if (p.isNumber) return p.asString
            }
        }
        return ""
    }

    private fun firstInt(o: JsonObject, keys: List<String>): Int? {
        for (k in keys) {
            val e = o.get(k) ?: continue
            val p = e as? JsonPrimitive ?: continue
            if (!p.isNumber) continue
            val v = runCatching { p.asInt }.getOrNull() ?: continue
            if (v > 0) return v
        }
        return null
    }

    private fun firstBool(o: JsonObject, keys: List<String>): Boolean {
        for (k in keys) {
            val e = o.get(k) ?: continue
            val p = e as? JsonPrimitive ?: continue
            if (p.isBoolean) return p.asBoolean
        }
        return false
    }
}
