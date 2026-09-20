package com.videoshell.data.site

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.videoshell.data.model.Category
import com.videoshell.data.model.Episode
import com.videoshell.data.model.MediaSource
import com.videoshell.data.model.PlayGroup
import com.videoshell.data.model.SiteConfig
import com.videoshell.data.model.VideoDetail
import com.videoshell.data.model.VideoItem
import com.videoshell.util.EpisodeOrder
import java.io.IOException

/**
 * ## 野果短剧（yeguodj.com）：加密接口适配器
 *
 * 这个站把**所有**接口响应都做了 AES 加密（`data` 字段是 base64 密文），
 * 于是壳子原先那两条路都不通：
 *
 * - HTML 模式：首页是 Nuxt3 SSR，**服务端只渲染 SEO 内容**，剧集列表由客户端 JS
 *   解密接口后填上 —— 抓 HTML 只能拿到一堆没有封面的「查看剧集」占位卡片；
 * - 采集接口模式：`/api/xxx` 返回的是密文，JSON 里既没有 `list` 也没有 `vod_play_url`。
 *
 * 表现就是用户最直观的那几条：**搜索"搜什么都一样"**（候选模板全打歪）、
 * **播放地址靠 SSR 页内 JSON 硬抠**（脆弱，且被 `auth_key` 时效卡住）。
 *
 * 密钥与算法已逆向确认（见 [CryptRecipes] 的实测表），这一版按**接口原生能力**重写。
 * 具体接口、参数、字段见本文件各处注释；纯映射逻辑集中在 [YeguoMap]，
 * 与网络 I/O 分开 —— 好让离线套件能用真实密文夹具断言它（`runygo`）。
 *
 * ### 分集地址为什么是伪地址
 *
 * `video_url` 带 `auth_key`（unix 秒，约 1 小时过期）。详情一次能把 22 集直链全拿到，
 * 但"今天存列表、明天接着看"必然 403。所以列表里只放
 * [PseudoPlayUrl]（`yeguo://play/{videoId}/{episodeId}`），
 * [resolve] 在**播放前一刻**才去换真链 —— 每次都是新签的 key，重播也不会失效。
 */
class YeguoAdapter(site: SiteConfig, private val recipe: CryptRecipe) : SiteAdapter(site) {

    /** 单页条数。站点 `config.max_page_size = 30`，取 20 留余量 */
    private val limit = 20

    private var diag: String = ""

    override val lastDiag: String get() = diag

    private val root: String get() = site.baseUrl.trimEnd('/')

    // ------------------------------------------------------------------ 接口

    private suspend fun api(path: String, params: Map<String, String> = emptyMap()): JsonObject? {
        // ⚠️ 必须带上配方的握手参数：**自证时用的就是这一组**，
        // 运行时如果少发，就变成"验过的姿势"和"跑的姿势"不是同一个（本项目明令禁止）。
        // 实测服务端不校验签名，但姿态一致才谈得上"验过"。
        val resp = CryptApi.call(recipe, path, recipe.baseParams + params, referer = root)
        if (resp == null) {
            diag = CryptApi.lastError
            return null
        }
        CryptApi.businessError(resp)?.let {
            diag = it
            return null
        }
        return resp
    }

    private fun pageParams(page: Int) = mapOf("page" to "$page", "limit" to "$limit")

    // ------------------------------------------------------------------ 分类

    /**
     * 分类栏 = 两个虚拟 tab + 站点下发的筛选标签。
     *
     * 为什么用 `contentOptions` 而不是抓页面的 `/tag/{slug}/`：那是**加密站唯一的活水**。
     * 页面上的 tag 链接是 SEO 用的静态快照（站点改版/换域名就没了），
     * 而 `video_filter` 是站点自己给客户端发的**权威分类表**，还带 `title`（主题/设定/背景）——
     * 顺序和分组也跟官方 App 一致。
     */
    override suspend fun categories(): List<Category> {
        val resp = api("/api/home/contentOptions")
        val list = YeguoMap.categoriesFrom(resp)
        if (list.size <= 2 && diag.isBlank()) diag = "分类筛选表未取到（contentOptions 为空）"
        return list
    }

    // ------------------------------------------------------------------ 浏览

    override suspend fun browse(typeId: String, page: Int): List<VideoItem> {
        val ref = typeId.trim()
        val p = page.coerceAtLeast(1)
        val resp = when {
            ref.isBlank() || ref == YeguoMap.TAB_LATEST ->
                api("/api/theater/exploreList", pageParams(p))

            ref == YeguoMap.TAB_RANK ->
                api("/api/theater/videoRank", pageParams(p))

            // 未知 id 一律当标签名试 —— 站点加维度时不必改代码
            else -> api(
                "/api/theater/tagList",
                pageParams(p) + ("tag" to ref.removePrefix(YeguoMap.TAG_PREFIX))
            )
        }
        val items = YeguoMap.itemsFromResp(resp)
        if (items.isEmpty() && diag.isBlank()) diag = "列表为空（接口未返回 list）"
        return items
    }

    // ------------------------------------------------------------------ 搜索

    /**
     * 搜索走**原生接口**。
     *
     * 这修掉的是本项目最顽固的一类现象：HTML 适配器的搜索是"候选模板 + 两遍式"，
     * 对一个加密站而言**每一条候选都是错的** —— 打不中关键词就 404 软跳首页、
     * 页面上的推荐位被当成搜索结果，用户看到的就是「搜什么都一样」。
     * 有了原生接口，这条路的正确率是 100%。
     *
     * ## 为什么只查 `tab=video`，不去查 `tab=actor`
     *
     * 站点把搜索分成剧集 / 演员两套结果（演员项是 `{actor_id,name,avatar,works,
     * representative_work,representative_video_id}`，没有 `title`）。但**实测不需要**：
     *
     * - 剧集 tab 本身就是**宽匹配** —— 标题、标签、演员名都算。搜「木君」（演员名）返回 20 条，
     *   搜「AI短剧」（标签）也返回 20 条。所以「搜人名搜不到」这个场景不存在；
     * - 演员项没法映射进本壳的列表模型（只能退化成一堆 `representative_work`），
     *   而它的代表作本来就在剧集 tab 的结果里。
     *
     * 结论：加演员 tab 只会多一次请求、且永远轮不到它 —— 别加。
     * （演员接口的结构与夹具见 `_ygo/search_actor.json`。）
     *
     * ## 为什么还要标「命中来源」
     *
     * 上面说了剧集 tab 是**宽匹配**——这是站点行为，不是我们的缺陷。但它有个副作用：
     * 搜标签词时结果**看片名全是无关的**。实测（`_ygo/search_tag.json` 等夹具）：
     *
     * | 关键词 | 条数 | 标题命中 | 其余靠什么命中 |
     * |---|---|---|---|
     * | 爱 | 20 | 17 | 标签 3 |
     * | 甜宠 | 9 | **0** | 标签 7 + 简介 2 |
     * | 短剧 | 20 | **0** | 标签 20（全是 `AI短剧` 的**子串**，等于整个片库） |
     *
     * 用户只看得到片名 ⇒ 结论必然是「结果错的」。站点其实在每一项里都下发了
     * `matched_fields`，把它翻成中文标在角标位（那里原本是空的）就够解释了。
     */
    override suspend fun search(keyword: String, page: Int): List<VideoItem> {
        val kw = keyword.trim()
        if (kw.isBlank()) return emptyList()
        val resp = api(
            "/api/search/result",
            mapOf("keyword" to kw, "page" to "${page.coerceAtLeast(1)}", "limit" to "$limit")
        )
        // 「关键词无效」是站点的正常业务回复（词太短 / 被过滤），不是故障
        return YeguoMap.searchItemsFromResp(resp)
    }

    // ------------------------------------------------------------------ 详情

    override suspend fun detail(id: String): VideoDetail {
        val vid = id.trim().substringBefore('|')
        if (vid.isBlank()) throw IOException("野果：影片 id 为空")

        val d = api("/api/playlet/detail", mapOf("id" to vid))?.obj("data")
            ?: throw IOException("野果详情接口失败：${diag.ifBlank { "未知原因" }}")

        val title = d.get("title").str().ifBlank { "未命名" }
        val videoId = d.get("video_id").str().ifBlank { vid }

        // 详情接口的 episodes[] 带**集名/时长/分辨率**（最完整），拿它做集名的权威来源；
        // 播放接口的 episodeAll[] 才是"每集各自一个 video_url"，拿它做列表主体。
        val detailEps = d.arr("episodes")
        val titleById = YeguoMap.titleMapFrom(detailEps)

        val firstEpId = detailEps.firstOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject?.get("id").str().orEmpty()

        // 分集与播放地址一起取：episodeAll[] 的每一项都自带 video_url
        val playData = if (firstEpId.isNotBlank()) {
            api("/api/playlet/play", mapOf("video_id" to videoId, "episode_id" to firstEpId))?.obj("data")
        } else null

        val episodes = YeguoMap.episodesFrom(videoId, playData, detailEps, titleById)
        if (episodes.isEmpty()) {
            throw IOException("野果：没有取到任何分集（${diag.ifBlank { "接口未返回集列表" }}）")
        }

        return VideoDetail(
            id = videoId,
            name = title,
            pic = d.get("cover").str().ifBlank { d.get("cover_img").str() },
            remarks = d.get("serialize_status_text").str().ifBlank { d.get("update_status").str() },
            typeName = d.arr("tags").joinToString(" ") { it.str() }.trim(),
            year = d.get("created_at").str().take(4),
            actor = d.arr("actor_list")
                .mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.get("name").str() }
                .filter { it.isNotBlank() }.joinToString(" "),
            summary = d.get("description").str(),
            groups = listOf(PlayGroup(YeguoMap.LINE_NAME, episodes))
        )
    }

    // ------------------------------------------------------------------ 播放

    /**
     * 播放前一刻换真链。
     *
     * 伪地址（[PseudoPlayUrl]）走接口现取；既然是伪地址，就说明本站**一定会**走这条路，
     * 取不到时要给出**能定位**的原因（`auth_key` 失效 / 接口换密钥 / 网络），
     * 而不是把 ExoPlayer 丢到一个可能 403 的地址上让它报一句看不懂的错。
     */
    override suspend fun resolve(episode: Episode): MediaSource {
        val u = episode.url.trim()
        if (!PseudoPlayUrl.isPseudo(u)) return super.resolve(episode)

        val (vid, eid) = PseudoPlayUrl.parse(u)
            ?: return MediaSource.Error("野果：播放入口格式异常（$u）")

        val d = api("/api/playlet/play", mapOf("video_id" to vid, "episode_id" to eid))?.obj("data")
        val url = YeguoMap.streamUrlOf(d, eid)
        if (url.isBlank()) {
            return MediaSource.Error(
                "野果未返回播放地址" + diag.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
            )
        }
        return MediaSource.Direct(Media.encodeUrl(url), playHeaders(), Media.isHls(url))
    }
}

/**
 * 野果接口 → 壳子模型 的**纯映射**（不发一个请求）。
 *
 * 单独拎出来是因为：解密与映射是这条链路里**唯一有两处会错**的地方
 * （密钥变了 / 站点改了字段名），而它们恰好都能用**离线的真实密文夹具**断言。
 * 只要映射不进 I/O，`runygo` 就能在没有网络、没有真机的情况下守住整条 ③。
 */
object YeguoMap {

    /** 虚拟分类：最新 / 热播（站点自己的 tab，不来自 contentOptions） */
    const val TAB_LATEST = "@latest"
    const val TAB_RANK = "@rank"

    /** 标签分类前缀：`tag:都市` */
    const val TAG_PREFIX = "tag:"

    /** 单线路名（野果是单线路站，分集直接平铺） */
    const val LINE_NAME = "默认线路"

    /**
     * 从 `/api/home/contentOptions` 的解密结果生成分类栏。
     *
     * 结构：`{data:{video_filter:{主题:{title,parameter,list:[{value,name}]}, ...}}}`
     * 每个维度下的 `name` 就是标签名 —— 实测直接丢给 `/api/theater/tagList?tag=<name>`
     * 就能筛出对应内容（都市 39 / 巨乳 251 / AI短剧 436 / 奇幻 3）。
     */
    fun categoriesFrom(resp: JsonObject?): List<Category> {
        val out = LinkedHashMap<String, Category>()
        out[TAB_LATEST] = Category(TAB_LATEST, "最新", "0")
        out[TAB_RANK] = Category(TAB_RANK, "热播", "0")

        val filter = resp?.obj("data")?.get("video_filter")
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return out.values.toList()

        for ((_, v) in filter.entrySet()) {
            val dim = v.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val list = dim.get("list")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            for (el in list) {
                val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val name = o.get("name").str().trim()
                // 「全部/默认」这类占位项没有筛选意义，收进分类栏只会让用户多点一层
                if (name.isBlank() || name == "全部" || name == "默认") continue
                val id = TAG_PREFIX + name
                if (out.containsKey(id)) continue
                out[id] = Category(id, name, "0")
            }
        }
        return out.values.take(40).toList()
    }

    /** 从解密后的顶层响应取 `data.list` 映射成列表项 */
    fun itemsFromResp(resp: JsonObject?): List<VideoItem> {
        val arr = resp?.obj("data")?.get("list")
            ?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return itemsFrom(arr)
    }

    /**
     * `matched_fields` 的取值 → 中文角标。
     *
     * 实测取值只有 `title` / `tags` / `actors` / `description` 四种（见 `_ygo/search*.json`），
     * 多留一个 `alias` 是防御 —— 站点加维度时不该静默变成无标注。
     */
    private val MATCH_LABEL = mapOf(
        "title" to "标题",
        "tags" to "标签",
        "actors" to "演员",
        "description" to "简介",
        "alias" to "别名"
    )

    /**
     * 非标题命中时给出「为什么它会在结果里」的角标文案。
     *
     * **标题命中一律返回空串**：关键词就在片名里，用户一眼能看见，标了只占地方。
     * 认不出的字段名退化成「关键词相关」，宁可含糊也不静默（本项目一贯纪律）。
     */
    fun matchHintOf(o: JsonObject?): String {
        val fields = o.arr("matched_fields").map { it.str().trim() }.filter { it.isNotBlank() }
        if (fields.isEmpty()) return ""
        if (fields.any { it == "title" }) return ""
        return MATCH_LABEL[fields.first()]?.let { "命中$it" } ?: "关键词相关"
    }

    /**
     * 搜索结果专用映射：在 [itemsFromResp] 基础上标「命中来源」。
     *
     * 角标位在搜索结果里**本来就是空的**（站点只在浏览/榜单接口给 `play_count_text`），
     * 所以这里是纯增量，不覆盖任何既有信息。如果哪天站点在搜索里也开始下发角标，
     * 这里选择了**命中来源优先** —— 「这条为什么在这儿」比「多少人看过」更该先说。
     */
    fun searchItemsFromResp(resp: JsonObject?): List<VideoItem> {
        val arr = resp?.obj("data")?.get("list")
            ?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return arr.toList().mapNotNull { el ->
            val o = el.takeIf { e -> e.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val item = itemOf(o) ?: return@mapNotNull null
            val hint = matchHintOf(o)
            if (hint.isBlank()) item else item.copy(remarks = hint)
        }
    }

    fun itemsFrom(arr: JsonArray?): List<VideoItem> =
        (arr?.toList().orEmpty())
            .mapNotNull { itemOf(it.takeIf { e -> e.isJsonObject }?.asJsonObject) }

    private fun itemOf(o: JsonObject?): VideoItem? {
        if (o == null) return null
        val id = o.get("video_id").str().ifBlank { o.get("id").str() }
        val name = o.get("title").str().ifBlank { o.get("name").str() }
        if (id.isBlank() || name.isBlank()) return null
        return VideoItem(
            id = id,
            name = name,
            pic = o.get("cover").str().ifBlank { o.get("cover_img").str() },
            remarks = o.get("play_count_text").str()
                .ifBlank { o.get("serialize_status_text").str() },
            typeName = o.arr("tags").joinToString(" ") { it.str() }.trim(),
            score = "",
            year = ""
        )
    }

    /** `episodes[]` -> `id -> 集名`（集名最全，拿它当权威） */
    fun titleMapFrom(detailEps: List<JsonElement>): Map<String, String> {
        val m = HashMap<String, String>()
        for (e in detailEps) {
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val eid = o.get("id").str()
            if (eid.isNotBlank()) m[eid] = o.get("title").str()
        }
        return m
    }

    // ------------------------------------------------------------------ 分集显示名（v1.0.36）

    /** 分集显示名。集号从哪来由 [episodesNoes] 整列决定，这里只负责把数字说成人话 */
    fun episodeLabel(no: Int): String = "第${no.coerceAtLeast(1)}集"

    /**
     * **整列**定集号（纯函数，离线可断言）：标题 → 站点序号 → 数组位置，三选一，**整列同源**。
     *
     * ## 为什么不能逐条回退
     *
     * 「标题取不到就退到站点序号」这种**逐条**回退放在一列里是错的：标题和站点序号
     * 是两个来源，混起来会造出重复号与错位号 —— 实测 `窥破爱人谎言` 三集标题里一个
     * 「第N集」都没有（`AI短剧 窥破爱人谎言` / `…2` / `…3`），逐条回退会让三集全落到
     * 同一个站点序号上。这跟 [EpisodeOrder.order] 的「一列集号必须来自同一个来源」
     * 是同一条纪律，只是那边管排序、这边管编号。
     *
     * ## 三档判定（实测 2026-09-20，20 部剧）
     *
     * 1. **标题**：整列都能读出「第N集」且互不相同 ⇒ 用标题。
     *    站点自己标的号最权威（`少妇白洁 第二十一集` → 21）。实测可用 17/20；
     * 2. **站点序号** `index` / `sort`：实测可用 **20/20**（都是 1..n，SsrPayload 注释里
     *    那句"sort 恒为 1"说的是 SSR payload，不是这个接口）；
     * 3. **数组位置**：`index` 本身就是数组下标，这一步只是兜底，但保证一定有号 ——
     *    绝不出现空白格，也绝不出现两个「第1集」。
     */
    fun episodesNoes(titles: List<String>, ords: List<Int>): List<Int> {
        if (titles.isEmpty()) return emptyList()
        val fromTitle = titles.map { EpisodeOrder.noInTitle(it) }
        if (fromTitle.all { it != null } && fromTitle.toSet().size == titles.size) {
            return fromTitle.map { it!! }
        }
        if (ords.all { it > 0 } && ords.toSet().size == ords.size) return ords
        return titles.indices.map { it + 1 }
    }

    /**
     * 组装分集：**以 `episodeAll[]` 为主体**（顺序 + id 最全），显示名一律收敛成「第N集」。
     * 两者 id 相同（实测 `episodes[0].id == episodeAll[0].id == 186514`），所以能直接对上。
     *
     * ## 为什么不能直接显示站点给的标题
     *
     * 站点下发的 `episodes[].title` / `episodeAll[].episode_title` 是**自带剧名**的长串
     * （实测 20 部剧全部如此）：
     *
     * | 剧名 | 站点给的分集名 |
     * |---|---|
     * | 少妇白洁 | `少妇白洁 第一集` … `少妇白洁 第二十二集` |
     * | 庆余年 第三季 | `《庆余年》 第三季第一集` |
     * | 速通西游 | `AI魔改 速通西游第一集` |
     * | 舔狗2应有尽有 | `舔狗2应有尽有` / `舔狗2应有尽有 第二集` / `舔狗2应有尽有第三集` |
     *
     * 照原样铺进选集网格，结果就是用户报的那句：**「分集名称全是剧名」** ——
     * 每一格都在复读剧名，区分度只剩末尾那个数字；第一集还常常连数字都没有
     * （`时间停止` 那一集的名字就等于剧名）。个别集甚至把整段剧情简介贴进标题
     * （实测 `妹妹帮我操妈妈 第1集觉醒了最离谱的异能，竟是…`），一格里六十多个字。
     *
     * 分集地址一律是 [PseudoPlayUrl] 伪地址，真链播放时现取（`auth_key` 有时效）。
     */
    fun episodesFrom(
        videoId: String,
        playData: JsonObject?,
        detailEps: List<JsonElement>,
        titleById: Map<String, String>
    ): List<Episode> {
        val out = ArrayList<Episode>()

        val all = playData?.arr("episodeAll").orEmpty()
        if (all.isNotEmpty()) {
            val ids = ArrayList<String>(all.size)
            val titles = ArrayList<String>(all.size)
            val ords = ArrayList<Int>(all.size)
            for (e in all) {
                val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val eid = o.get("id").str()
                if (eid.isBlank()) continue
                ids += eid
                // 集名优先用详情接口的 episodes[]（字段最全），再退播放接口自己的
                titles += titleById[eid]?.takeIf { it.isNotBlank() }
                    ?: o.get("episode_title").str().takeIf { it.isNotBlank() }
                    ?: o.get("title").str()
                ords += o.get("index").asIntOrNull() ?: o.get("sort").asIntOrNull() ?: 0
            }
            val nos = episodesNoes(titles, ords)
            for (i in ids.indices) {
                out += Episode(episodeLabel(nos[i]), PseudoPlayUrl.build(videoId, ids[i]))
            }
        }

        // 兜底：播放接口没给 episodeAll（站点改版 / 只有一集），用详情的 episodes[]
        if (out.isEmpty()) {
            val ids = ArrayList<String>()
            val titles = ArrayList<String>()
            val ords = ArrayList<Int>()
            for (e in detailEps) {
                val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val eid = o.get("id").str()
                if (eid.isBlank()) continue
                ids += eid
                titles += o.get("title").str()
                ords += o.get("sort").asIntOrNull() ?: 0
            }
            val nos = episodesNoes(titles, ords)
            for (i in ids.indices) {
                out += Episode(episodeLabel(nos[i]), PseudoPlayUrl.build(videoId, ids[i]))
            }
        }
        return out
    }

    /** 从 `playlet/play` 的 `data` 里取真链；顶层为空时回落到 `episodeAll` 里对应的那一集 */
    fun streamUrlOf(playData: JsonObject?, episodeId: String): String {
        val top = playData?.get("video_url").str().orEmpty()
        if (top.isNotBlank()) return top
        return playData?.arr("episodeAll").orEmpty()
            .mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            .firstOrNull { it.get("id").str() == episodeId }
            ?.get("video_url").str().orEmpty()
    }
}
