package com.videoshell.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.videoshell.data.model.SiteConfig

/** 已适配站点的本地存储 */
object Store {

    private const val SP = "videoshell"
    private const val KEY_SITES = "sites"
    private const val KEY_DEFAULT = "default_site_key"
    private val gson = Gson()

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    fun sites(ctx: Context): MutableList<SiteConfig> {
        val s = sp(ctx).getString(KEY_SITES, null) ?: return mutableListOf()
        return runCatching {
            val t = object : TypeToken<MutableList<SiteConfig>>() {}.type
            gson.fromJson<MutableList<SiteConfig>>(s, t) ?: mutableListOf()
        }.getOrDefault(mutableListOf())
    }

    fun save(ctx: Context, list: List<SiteConfig>) {
        sp(ctx).edit().putString(KEY_SITES, gson.toJson(list)).apply()
    }

    /** @return true 表示新加入；false 表示已存在 */
    fun add(ctx: Context, site: SiteConfig): Boolean {
        val list = sites(ctx)
        if (list.any { it.key == site.key }) return false
        list.add(0, site)
        save(ctx, list)
        return true
    }

    fun remove(ctx: Context, key: String) {
        val list = sites(ctx)
        list.removeAll { it.key == key }
        save(ctx, list)
    }

    fun find(ctx: Context, key: String): SiteConfig? = sites(ctx).firstOrNull { it.key == key }

    /**
     * 按 **host** 找站（v1.0.37）。
     *
     * ⚠️ 不能拿 `key` 当同一个站的判据：`key` 的构成取决于**当时的识别结果**——
     * 采集接口站的 key 是 `base+接口路径+参数`，网页站是 `base`，自证命中后还可能变。
     * 于是"这个站在不在库里"用 key 问就会得到"不在"，接着被重复添加，
     * 站点列表里出现两条一模一样的站（用户看到的就是"我加过了怎么又出现一个"）。
     *
     * 域名会轮换，但**同一个站**在我们手里的 baseUrl 一定是同源的 ⇒ 按 host 归一。
     */
    fun findByHost(ctx: Context, urlOrHost: String): SiteConfig? {
        val h = hostOf(urlOrHost)
        if (h.isBlank()) return null
        return sites(ctx).firstOrNull { hostOf(it.baseUrl) == h }
    }

    /** 从 `https://a.b/c?d` 或 `a.b` 里取 host（小写）。唯一实现放 RecipeStore，这里只是转发 */
    fun hostOf(urlOrHost: String): String {
        val s = urlOrHost.trim()
        if (s.isEmpty()) return ""
        val h = com.videoshell.data.site.RecipeStore.hostOf(s)
        if (h.isNotBlank()) return h
        // 传进来的本来就是 host（没有 scheme）：去掉端口与路径
        return s.substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
    }

    /** 只改显示名（SiteConfig.name 是 val，用 copy 重建），key/识别结果都不动 */
    fun rename(ctx: Context, key: String, name: String) {
        val list = sites(ctx)
        var changed = false
        for (i in list.indices) {
            if (list[i].key == key) {
                list[i] = list[i].copy(name = name)
                changed = true
            }
        }
        if (changed) save(ctx, list)
    }

    // ------------------------------------------------------------------ 默认站源

    /** @return 默认站源 key；没设过/被删了返回空串 */
    fun defaultKey(ctx: Context): String = sp(ctx).getString(KEY_DEFAULT, "").orEmpty()

    fun setDefault(ctx: Context, key: String) {
        sp(ctx).edit().putString(KEY_DEFAULT, key).apply()
    }

    /**
     * 默认站源：用户星标的那个；没星标则取列表第一个；
     * 一个站点都没有返回 null（首页内容区据此显示引导文案）。
     */
    fun defaultSite(ctx: Context): SiteConfig? {
        val list = sites(ctx)
        if (list.isEmpty()) return null
        return list.firstOrNull { it.key == defaultKey(ctx) } ?: list.first()
    }

    // ------------------------------------------------------------------ 剧集列表排序

    private const val KEY_EP_DESC = "episode_desc"

    /**
     * 剧集列表是否倒序。
     *
     * 放在全局偏好里而不是各页面各自记：详情页与播放器选集面板是同一个列表的两个入口，
     * 用户在详情页切成倒序、进播放器又变回正序会很难受（也会让人以为按钮没生效）。
     */
    fun episodeDesc(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_EP_DESC, false)

    fun setEpisodeDesc(ctx: Context, desc: Boolean) {
        sp(ctx).edit().putBoolean(KEY_EP_DESC, desc).apply()
    }

    // ------------------------------------------------------------------ 搜索范围

    private const val KEY_SEARCH_SCOPE = "search_scope"

    /**
     * 上次用的搜索范围（`SearchScope.name`；空 = 从没设置过）。
     *
     * 存字符串而不是存下标：枚举将来插一项就会把老用户的选项**静默换成另一个**，
     * 而那正好是"我明明选的是全网、怎么又只搜本站了"的成因。
     */
    fun searchScope(ctx: Context): String = sp(ctx).getString(KEY_SEARCH_SCOPE, "").orEmpty()

    fun setSearchScope(ctx: Context, scope: String) {
        sp(ctx).edit().putString(KEY_SEARCH_SCOPE, scope).apply()
    }

    // ------------------------------------------------------------------ 搜索引擎（v1.0.38）

    private const val KEY_SEARCH_ENGINE = "search_engine"
    private const val KEY_SEARCH_ENHANCE = "search_enhance"

    /**
     * 全网搜索用哪个引擎（`SearchEngine.name`；空 = 从没设置过 ⇒ 用默认的那个）。
     *
     * 与 [searchScope] 同一个理由存名字：枚举迟早要加（Yandex / 头条搜索 …），
     * 存下标的版本一旦加成员就会把用户的选项整体挪位。
     */
    fun searchEngine(ctx: Context): String = sp(ctx).getString(KEY_SEARCH_ENGINE, "").orEmpty()

    fun setSearchEngine(ctx: Context, engine: String) {
        sp(ctx).edit().putString(KEY_SEARCH_ENGINE, engine).apply()
    }

    /** 全网搜索是否追加「在线观看」后缀（影视化关键词增强） */
    fun searchEnhance(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_SEARCH_ENHANCE, false)

    fun setSearchEnhance(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_SEARCH_ENHANCE, on).apply()
    }

    // ------------------------------------------------------------------ 搜索历史（FN-2）

    private const val KEY_SEARCH_HIST = "search_history"
    private const val MAX_SEARCH_HIST = 20

    fun searchHistory(ctx: Context): List<String> {
        val s = sp(ctx).getString(KEY_SEARCH_HIST, null) ?: return emptyList()
        return runCatching {
            val t = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(s, t) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** 记一条搜索词（去重置顶，最短 2 字才记） */
    fun addSearchHistory(ctx: Context, kw: String) {
        val k = kw.trim()
        if (k.length < 2) return
        val list = searchHistory(ctx).toMutableList()
        list.removeAll { it.equals(k, ignoreCase = true) }
        list.add(0, k)
        while (list.size > MAX_SEARCH_HIST) list.removeAt(list.size - 1)
        sp(ctx).edit().putString(KEY_SEARCH_HIST, gson.toJson(list)).apply()
    }

    fun clearSearchHistory(ctx: Context) {
        sp(ctx).edit().remove(KEY_SEARCH_HIST).apply()
    }

    fun removeSearchHistory(ctx: Context, kw: String) {
        val list = searchHistory(ctx).toMutableList()
        list.removeAll { it.equals(kw, ignoreCase = true) }
        sp(ctx).edit().putString(KEY_SEARCH_HIST, gson.toJson(list)).apply()
    }

    // ------------------------------------------------------------------ 站点导入 / 合并（FN-6）

    /**
     * 批量导入站点：先按 key 去重，再按 host 去重。
     *
     * 为什么不只看 key：见 [findByHost] 的注释 —— key 会随识别结果变，同一个站可能以
     * 两个 key 存在。这里对新来的每条都同时查 key 与 host，防止"导入之后列表里多出一排
     * 和我现有的一模一样的站"。
     *
     * @return 实际新增条数
     */
    fun importSites(ctx: Context, incoming: List<SiteConfig>): Int {
        val list = sites(ctx)
        val keys = list.map { it.key }.toHashSet()
        val hosts = list.map { hostOf(it.baseUrl) }.filter { it.isNotBlank() }.toHashSet()
        var added = 0
        for (s in incoming) {
            if (s.key.isBlank() && s.baseUrl.isBlank()) continue
            if (s.key.isNotBlank() && s.key in keys) continue
            val h = hostOf(s.baseUrl)
            if (h.isNotBlank() && h in hosts) continue
            list.add(s)
            if (s.key.isNotBlank()) keys.add(s.key)
            if (h.isNotBlank()) hosts.add(h)
            added++
        }
        if (added > 0) save(ctx, list)
        return added
    }

    /**
     * 合并重复站点：按 host 归一，同一个 host 只留一条。
     *
     * 同 host 多条时**优先保留带 apiUrl 的那条**（采集接口站比纯网页站的配方更完整），
     * 否则保留靠前的那条 —— 用户自己排过的顺序不该被合并动作打乱。
     * 若默认站源正好被合并掉，顺手把默认改到剩下第一条，避免星标"失踪"。
     *
     * @return (合并后条数, 去掉的条数)
     */
    fun dedupSites(ctx: Context): Pair<Int, Int> {
        val list = sites(ctx)
        val out = ArrayList<SiteConfig>()
        val indexByHost = HashMap<String, Int>()
        for (s in list) {
            val h = hostOf(s.baseUrl).ifBlank { s.key }
            val idx = indexByHost[h]
            if (idx == null) {
                indexByHost[h] = out.size
                out.add(s)
            } else if (out[idx].apiUrl.isBlank() && s.apiUrl.isNotBlank()) {
                out[idx] = s
            }
        }
        val removed = list.size - out.size
        if (removed > 0) {
            save(ctx, out)
            val dk = defaultKey(ctx)
            if (dk.isNotBlank() && out.none { it.key == dk }) {
                setDefault(ctx, out.firstOrNull()?.key.orEmpty())
            }
        }
        return out.size to removed
    }
}
