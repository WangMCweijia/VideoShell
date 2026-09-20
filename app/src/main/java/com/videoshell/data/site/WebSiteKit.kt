package com.videoshell.data.site

import android.content.Context
import com.videoshell.data.Store
import com.videoshell.data.model.SiteConfig

/**
 * 「在网页上把一个站收进来」（v1.0.37）。
 *
 * 这个对象存在的理由只有一个：**「识别并添加」与「手动校准」这两条路必须共用同一套判据**。
 *
 * 它们在同一件事上会给出相反的答案，如果各写各的：
 * - 「识别并添加」用 `SiteDetector.detect` ⇒ 得到一个 **key**（采集接口站的 key 带接口路径）；
 * - 「手动校准」要 `Store.find(key)` ⇒ 拿不到那个 key 就以为"站点不存在"，于是**再添加一遍**；
 * - 站点列表里于是出现两条指向同一个域名的记录，用户看到的是"我明明加过了"。
 *
 * 所以两边都先问 [matchExisting]（**按 host 归一**，不是按 key），
 * 已经存在就直接用它 —— 一次都不重复加。
 */
object WebSiteKit {

    data class AddResult(
        val ok: Boolean,
        val message: String,
        val site: SiteConfig? = null,
        /** 这一条是"本来就在列表里"，界面据此把按钮语义从「已添加」换成「打开」 */
        val existed: Boolean = false
    )

    /**
     * 纯函数：在已保存站点里按 host 找同一个站。
     *
     * 不按 `key` 找的原因见文件头。另注意 `http`/`https` 与 `www.` 前缀的差异
     * 由 [Store.hostOf] 归一 —— 用户从搜索页点进来的是 `https://www.x.com/...`，
     * 而当初识别时存的是 `http://x.com`，`www` 的差别不能当两个站。
     */
    fun matchExisting(sites: List<SiteConfig>, pageUrl: String): SiteConfig? {
        val h = hostOfBase(pageUrl)
        if (h.isBlank()) return null
        return sites.firstOrNull { hostOfBase(it.baseUrl) == h }
    }

    /** 取 host 并把 `www.` 前缀去掉（只用于**比较**，不改站点自己的 baseUrl） */
    fun hostOfBase(urlOrHost: String): String =
        Store.hostOf(urlOrHost).removePrefix("www.")

    /** `https://a.b/x/y?z` → `https://a.b`；没有 scheme 时返回空串（网页地址一定有） */
    fun originOf(url: String): String =
        Regex("^https?://[^/\\s]+", RegexOption.IGNORE_CASE).find(url.trim())?.value.orEmpty()

    /** [ensureSite] 的结果：站点本身 + **这次是不是新建的**（界面据此决定要不要解释一句） */
    data class Ensured(val site: SiteConfig, val created: Boolean)

    /**
     * 确保这个网址对应的站**在库里有配置**，返回它（没有就按网页模式建一条）。
     *
     * 手动校准必须有这一步：`CalibrateActivity` 的第一件事是 `Store.find(key)`，
     * 库里没有它就 `finish()` —— 用户从网页浏览里点「手动校准」会被直接弹回来，
     * 看起来就是"点了没反应"（这类"静默 exit"是本项目踩过最多次的一类）。
     *
     * 新建时用 **HTML 模式**：校准本来就是针对网页结构的；
     * 若这站其实有采集接口，「识别并添加」那条路会把它升级掉（见 [upgradeToward]）。
     *
     * ⚠️ 返回 [Ensured.created] 而不是让调用方去比对 `note` 之类的字段猜"是不是刚建的"：
     * 拿文案当状态用，改一次文案就静默失效。
     */
    fun ensureSite(ctx: Context, pageUrl: String, pageTitle: String = ""): Ensured? {
        val origin = originOf(pageUrl)
        if (origin.isBlank()) return null
        matchExisting(Store.sites(ctx), origin)?.let { return Ensured(it, created = false) }
        val host = Store.hostOf(origin)
        val site = SiteConfig(
            key = origin.lowercase(),
            name = pageTitle.trim().take(30).ifBlank { host },
            baseUrl = origin,
            apiUrl = "",
            apiMode = SiteConfig.MODE_HTML,
            note = "从网页浏览添加",
            createdAt = System.currentTimeMillis()
        )
        Store.add(ctx, site)
        return Ensured(site, created = true)
    }

    /**
     * 一键「识别并添加」。
     *
     * 三种结果都要能区分，措辞也不能含糊：
     * - **还在搜索引擎上** ⇒ 明确说"先点进一个结果页"（用户以为按钮坏了，其实是没到站上）；
     * - **识别成功** ⇒ 说清识别成了什么模式（采集接口 / 网页适配），必要时**升级**已有配置；
     * - **不是视频站** ⇒ 原样转达 [SiteDetector] 的判断，不粉饰。
     */
    suspend fun recognizeAndAdd(ctx: Context, pageUrl: String): AddResult {
        val url = pageUrl.trim()
        if (url.isBlank() || !url.startsWith("http")) {
            return AddResult(false, "当前没有可识别的网页地址")
        }
        if (SearchScope.isWebHost(url)) {
            return AddResult(false, "现在还在搜索引擎的结果页上 —— 先点进一个影片页面，再按「识别并添加」")
        }

        val r = runCatching { SiteDetector.detect(url) }.getOrNull()
            ?: return AddResult(false, "识别失败：网络异常或该站无法访问")
        val fresh = r.site
        if (!r.isVideoSite || fresh == null) return AddResult(false, r.message)

        val existing = matchExisting(Store.sites(ctx), fresh.baseUrl)
        if (existing != null) {
            val merged = upgradeToward(existing, fresh)
            if (merged == null) {
                return AddResult(true, "已在列表中：${existing.name.ifBlank { existing.baseUrl }}", existing, existed = true)
            }
            Store.save(ctx, Store.sites(ctx).map { if (it.key == existing.key) merged else it })
            return AddResult(true, "已更新本站配置：${merged.note}", merged, existed = true)
        }

        val added = Store.add(ctx, fresh)
        return AddResult(
            true,
            if (added) "已添加：${fresh.name.ifBlank { fresh.baseUrl }}（${fresh.note}）" else "该站点已存在",
            fresh
        )
    }

    /**
     * 只有在**明确变好**时才改已有配置：原本没有采集接口、这次找到了。
     *
     * 反过来的方向（已有接口 → 退成 HTML）绝不能自动做：那会把一个本来好用的站改坏，
     * 而用户什么都没点。要退，走站源页的「重学本站」。
     */
    fun upgradeToward(existing: SiteConfig, fresh: SiteConfig): SiteConfig? {
        if (existing.apiUrl.isNotBlank() || fresh.apiUrl.isBlank()) return null
        return existing.copy(
            apiUrl = fresh.apiUrl,
            apiMode = fresh.apiMode,
            fixedParams = fresh.fixedParams,
            note = fresh.note
        )
    }
}
