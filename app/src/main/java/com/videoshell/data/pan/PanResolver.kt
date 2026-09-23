package com.videoshell.data.pan

import com.videoshell.data.model.Episode
import com.videoshell.data.model.MediaSource
import com.videoshell.data.site.Media

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
                listOf(Episode("${link.type.label}（暂不支持）", rawOf(link))), false, 0
            )
        }
        val ex = expand(link)
        if (ex.files.isEmpty()) {
            lastNote = "${link.type.key} 展开为空：${reason(p)}"
            return PanEpisodes(listOf(Episode("打开分享（未展开）", rawOf(link))), false, ex.dirsSeen)
        }
        lastNote = "${link.type.key} 展开到 ${ex.files.size} 个文件 / ${ex.dirsSeen} 个目录"
        return PanEpisodes(
            ex.files.map { Episode(it.name, PanLink.refOf(link, it.fid, it.token)) },
            ex.truncated,
            ex.dirsSeen
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

        val files = ArrayList<PanFile>()
        val seenDir = HashSet<String>()
        val queue = ArrayDeque<String?>()
        queue.add(null)                        // null = 分享根
        var dirs = 0
        var truncated = false
        var guard = 0

        while (queue.isNotEmpty()) {
            if (guard++ > MAX_DIRS * 3) {
                truncated = true
                break
            }
            val fid = queue.removeFirst()
            val items = p.list(link, fid)
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
        val out = naturalSort(files)
        treeCache[key] = Tree(out, dirs, truncated, System.currentTimeMillis())
        return PanExpanded(out, dirs, truncated)
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

/** [PanResolver.episodes] 的结果：集数 + 是否被截断（截断要能在界面上说出来） */
data class PanEpisodes(
    val episodes: List<Episode>,
    val truncated: Boolean,
    val dirs: Int
)
