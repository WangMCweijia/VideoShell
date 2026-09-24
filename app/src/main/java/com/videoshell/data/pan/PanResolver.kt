package com.videoshell.data.pan

import com.videoshell.data.model.Episode
import com.videoshell.data.model.MediaSource
import com.videoshell.data.site.Media
import kotlinx.coroutines.delay

/**
 * ## 网盘解析层：分享链接 / 站内引用 → 可播地址
 *
 * 这是「③ 网盘层」，**与站层解耦**。两个入口都从这里进：
 *
 * | 入口 | 输入 | 谁产生 |
 * |---|---|---|
 * | [resolve] | `pan://…` 站内引用 | 详情页展开文件夹后，每一集的 url |
 * | [resolve] | 原始分享链接 `https://pan.quark.cn/s/xxx` | 用户直接粘贴（不经任何"站"） |
 *
 * 上游接入点是 [com.videoshell.data.site.SiteAdapter.resolve] 的**第一行**判据 ——
 * 因为网盘分享链接长得不像媒体文件（[Media.isDirect] 必返回 false），
 * 不先截住就会白走一轮 HTML 抠址 + 嗅探，最后弹 WebView 也播不了。
 *
 * ## 缓存纪律（这一条不能破）
 *
 * | 对象 | 缓存 | 理由 |
 * |---|---|---|
 * | 分享 `stoken` | ✅ 20 min（[PanCloudDrive] 内） | 匿名可得，与登录态无关 |
 * | 目录树 | ✅ 进程内 10 min（[treeCache]） | 稳定；但**连着 `share_fid_token` 一起存** |
 * | **直链** | ❌ **绝不落盘、也不缓存** | 带时效签名，缓存必然变成"昨天能播今天不能" |
 *
 * ⚠️ 目录树缓存必须存**整个 [PanFile]**（含 token），不能只存 `fid → name`：
 * 取直链要提交 `fid + share_fid_token` 两个值，少一个就取不到流 ——
 * 而症状是"列得出集数、点进去说没登录"，非常难查。
 */
object PanResolver {

    /** 一次展开最多认多少个目录 / 收集多少个文件（防病态分享把内存和请求打爆） */
    private const val MAX_DIRS = 40
    private const val MAX_FILES = 800

    /** 目录树缓存时长 */
    private const val TREE_TTL_MS = 10 * 60_000L

    /** 展开失败后补一次前的间隔（有界：只补一次，见 [expand]） */
    private const val EXPAND_RETRY_DELAY_MS = 400L

    private class Tree(val files: List<PanFile>, val dirs: Int, val truncated: Boolean, val at: Long)

    private val treeCache = java.util.concurrent.ConcurrentHashMap<String, Tree>()

    /** 最近一次的展开结论（诊断用，不含凭据） */
    @Volatile
    var lastNote: String = ""
        private set

    /** 清掉目录缓存（「网盘账号」页退出登录时调 —— 换了账号，目录权限可能就不同了） */
    fun invalidate() {
        treeCache.clear()
        lastNote = ""
    }

    // ------------------------------------------------------------------ 入口

    /** 这一条地址能不能交给网盘层（自门控：纯函数，无 IO） */
    fun handles(url: String?): Boolean = PanLink.isRef(url) || PanLink.isPan(url)

    /**
     * 这是不是网盘**媒体直链**（清单/分片 URL，不是分享页、不是站内引用）。
     *
     * 给播放器的 403 自愈做判据（v1.0.69）：只有这类 URL 的 403 才值得
     * "重新解析换凭据" —— 分享页 404/403 是分享没了，语义完全不同。
     * 域名按后缀宽判、**不写死主机名**：`video-play-h-zb` 这类 CDN 前缀会变。
     * 纯函数、无 IO，`PanMediaCookieTest` 有逐条断言。
     */
    @JvmStatic
    fun isPanMediaUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return false
        return host.endsWith("drive.quark.cn") || host.endsWith("drive.uc.cn")
    }

    /**
     * 分享链接 / 站内引用 → 可播地址。
     *
     * 站内引用走「按 fid 取流」；原始分享链接走「先展开、播第一个文件」。
     * 后者的存在是为了「用户直接粘一条分享链接」也能播 —— 但**同时**要给得出原因：
     * 分享里没有视频时返回的是可读的错误，不是静默失败。
     */
    suspend fun resolve(url: String): MediaSource {
        PanLink.parseRef(url)?.let { ref ->
            val p = PanProviders.of(ref.link.type)
            if (!p.supported) return unsupported(ref.link.type)
            val s = p.stream(ref) ?: return failure(p, ref.link.type)
            return direct(s)
        }
        val link = PanLink.parse(url)
            ?: return MediaSource.Error("无法识别的网盘分享链接：${url.take(80)}")
        val p = PanProviders.of(link.type)
        if (!p.supported) return unsupported(link.type)
        val ex = expand(link)
        val first = ex.files.firstOrNull()
            ?: return MediaSource.Error(
                "${link.type.label}分享里没有找到可播放的视频" + reason(p)
            )
        val s = p.stream(PanRef(link, first.fid, first.token)) ?: return failure(p, link.type)
        return direct(s)
    }

    /**
     * 展开一条分享 → 剧集列表（供详情页用）。
     *
     * 展开失败**不返回空表**：留一条"打开分享（未展开）"的兜底集，它的 url 就是原始分享链接
     * —— 点它时 [resolve] 会**再试一次**并给出具体原因。这样"详情页整页空"永远不会发生，
     * 而失败原因也一定传达得到（这是 [com.videoshell.data.site.PanShareAdapter] 的第一条纪律）。
     */
    suspend fun episodes(link: PanLink): PanEpisodes {
        val p = PanProviders.of(link.type)
        if (!p.supported) {
            return PanEpisodes(
                listOf(Episode("${link.type.label}（暂不支持）", rawOf(link))), false, 0,
                expanded = false
            )
        }
        val ex = expand(link)
        if (ex.files.isEmpty()) {
            // 顺着契约读 [PanProvider.lastError]：**"取不到"和"目录里真没有视频"要分得开**。
            // 两者都表现为"0 集"，但一个是我们的故障（可重试、要留痕），
            // 一个是分享本身的内容问题（换线路才是对的）—— 混成一句话用户无从下手。
            lastNote = if (p.lastError != null) {
                "${link.type.key} 展开失败（取不到目录）：${reason(p)}"
            } else {
                "${link.type.key} 展开为空：目录里没有视频文件（扫了 ${ex.dirsSeen} 个目录）"
            }
            return PanEpisodes(
                listOf(Episode("打开分享（未展开）", rawOf(link))), false, ex.dirsSeen,
                expanded = false
            )
        }
        lastNote = "${link.type.key} 展开到 ${ex.files.size} 个文件 / ${ex.dirsSeen} 个目录"
        return PanEpisodes(
            ex.files.map { Episode(it.name, PanLink.refOf(link, it.fid, it.token)) },
            ex.truncated,
            ex.dirsSeen,
            expanded = true
        )
    }

    /** 目录树（带缓存）：递归走到**含文件的层级**，收集可播视频 */
    suspend fun expand(link: PanLink): PanExpanded {
        val key = link.type.key + "|" + link.id + "|" + link.pwd
        treeCache[key]?.let { t ->
            if (System.currentTimeMillis() - t.at < TREE_TTL_MS) {
                return PanExpanded(t.files, t.dirs, t.truncated)
            }
        }
        val p = PanProviders.of(link.type)
        if (!p.supported) return PanExpanded(emptyList(), 0, false)

        var ex = walkOnce(p, link)
        // 瞬时失败再给一次机会：`Http` 只重试 429/5xx，**404 是"一次就断"**（见 Http.worthRetry），
        // 而展开在详情页的必经路径上 —— 白丢一次请求换来的是整页降级（只剩一条「未展开」）。
        // 有界（只补一次、带固定间隔），不会把"真挂了"拖成转圈。
        if (ex.failed) {
            delay(EXPAND_RETRY_DELAY_MS)
            ex = walkOnce(p, link)
        }
        // ⚠️ **失败的展开绝不入缓存**。缓存的时效是 [TREE_TTL_MS]（10 分钟），而失败往往只
        //    持续几秒 —— 一旦把失败也缓存起来，用户在详情页点那条「打开分享（未展开）」
        //    重试时命中的还是同一份空结果，**"重试"这个最后的自救入口就形同不存在**
        //    （2026-09-24 真机症状：那一条点下去也永远不恢复，而分享在 PC 上匿名可完整展开）。
        if (!ex.failed) {
            treeCache[key] = Tree(ex.files, ex.dirsSeen, ex.truncated, System.currentTimeMillis())
        }
        return ex
    }

    /**
     * 一次展开（不含缓存、不含重试）：BFS 走到含文件的层级，收集可播视频。
     *
     * 失败判据是 [PanProvider.lastError]，**不是**"返回了空表" —— 契约（[PanProvider] 第 32 行）
     * 说得很清楚：取不到也返回空表，原因写在 `lastError` 里。这里混同一次，
     * 用户就会看到"0 集"却没有任何原因，而且那份空树还会进缓存（见 [expand]）。
     */
    private suspend fun walkOnce(p: PanProvider, link: PanLink): PanExpanded {
        val files = ArrayList<PanFile>()
        val seenDir = HashSet<String>()
        val queue = ArrayDeque<String?>()
        queue.add(null)                        // null = 分享根
        var dirs = 0
        var truncated = false
        var failed = false
        var guard = 0

        while (queue.isNotEmpty()) {
            if (guard++ > MAX_DIRS * 3) {
                truncated = true
                break
            }
            val fid = queue.removeFirst()
            val items = p.list(link, fid)
            if (items.isEmpty() && p.lastError != null) {
                failed = true
                break
            }
            for (it in items) {
                if (it.dir) {
                    if (dirs >= MAX_DIRS) {
                        truncated = true
                        continue
                    }
                    if (seenDir.add(it.fid)) {
                        dirs++
                        queue.add(it.fid)
                    }
                } else if (it.isVideo) {
                    if (files.size >= MAX_FILES) {
                        truncated = true
                        continue
                    }
                    files.add(it)
                }
            }
        }
        return PanExpanded(naturalSort(files), dirs, truncated, failed)
    }

    // ------------------------------------------------------------------ 内部

    private fun direct(s: PanStream): MediaSource = MediaSource.Direct(
        Media.encodeUrl(s.url), s.headers, s.hls, s.mime
    )

    private fun failure(p: PanProvider, t: PanType): MediaSource = when (val e = p.lastError) {
        is PanError.NeedLogin -> MediaSource.NeedLogin(e.message, t.key)
        null -> MediaSource.Error("${t.label}解析失败（没有拿到直链）")
        else -> MediaSource.Error(e.message)
    }

    private fun unsupported(t: PanType): MediaSource =
        MediaSource.Error("暂不支持${t.label}直链播放（该网盘适配进行中，可改用夸克/UC 线路）")

    /** 把 Provider 的失败原因补进文案里（"没有找到视频"要能说清是权限还是结构问题） */
    private fun reason(p: PanProvider): String = when (val e = p.lastError) {
        null -> ""
        is PanError.NeedLogin -> "（${e.message}）"
        else -> "（${e.message}）"
    }

    private fun rawOf(link: PanLink): String =
        link.raw.ifBlank {
            when (link.type) {
                PanType.QUARK -> "https://pan.quark.cn/s/${link.id}"
                PanType.UC -> "https://drive.uc.cn/s/${link.id}"
                PanType.BAIDU -> "https://pan.baidu.com/s/1${link.id}"
                else -> ""
            }
        }

    /**
     * 按名字做**自然序**排序。
     *
     * 为什么非做不可：分享里几乎一定是 `第1集`…`第10集`…`第100集` 这种名字，
     * 纯字典序会排成 `第100集 < 第10集 < 第1集`（按字符比），用户点"下一集"会跳到
     * 完全不相邻的一集 —— 这是"选集顺序乱了"的唯一成因，且只在两位/三位数混排时出现。
     */
    fun naturalSort(files: List<PanFile>): List<PanFile> =
        files.sortedWith(Comparator { a, b -> naturalCompare(a.name, b.name) })

    /** 数字段按**数值**比，其余按忽略大小写的字符比（纯函数，可离线断言） */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var x = i
                while (x < a.length && a[x].isDigit()) x++
                var y = j
                while (y < b.length && b[y].isDigit()) y++
                val na = a.substring(i, x).trimStart('0').ifEmpty { "0" }
                val nb = b.substring(j, y).trimStart('0').ifEmpty { "0" }
                // 先比位数（位数多的一定大），位数相同再比字典序 —— 避免 toInt 溢出
                val c = if (na.length != nb.length) na.length - nb.length else na.compareTo(nb)
                if (c != 0) return c
                i = x
                j = y
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

/**
 * [PanResolver.episodes] 的结果：集数 + 是否被截断（截断要能在界面上说出来）。
 *
 * [expanded] 是**这次到底有没有真的展开出来**：false 表示返回的那条「打开分享（未展开）」
 * 只是兜底（详见 [PanResolver.episodes]）—— 上层（[com.videoshell.data.site.PanShareAdapter]）
 * 据此把"线路数"报成告警而不是成功。没有它，界面上会显示"✓ 1 条线路（1 集）"，
 * 把一次失败伪装成一次成功（2026-09-24 修）。
 *
 * 默认值给 `true` 是为了兼容 Kotlin 侧的既有构造点；Java harness 若要位置构造请用
 * 四参构造（本项目已踩过"Kotlin 默认参数对 Java 不可见"的坑，见 PITFALLS E-alias）。
 */
data class PanEpisodes(
    val episodes: List<Episode>,
    val truncated: Boolean,
    val dirs: Int,
    val expanded: Boolean = true
)
