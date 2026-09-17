package com.videoshell.data.site

import com.videoshell.data.model.SiteConfig

/**
 * 按站点配置造适配器。
 *
 * ⚠️ 判定顺序很关键：**`apiUrl` 为空一律走 HTML 适配**。
 * 原实现写成 `when (site.apiMode) { ... else -> MaccmsAdapter(site) }`，
 * 于是 `apiMode` 只要不是预期值（例如老配置被 Gson 反序列化成 null —— Gson 用 Unsafe
 * 分配对象，Kotlin 的字段默认值不会生效），就会掉进 maccms 分支；
 * 而 maccms 分支依赖 `apiUrl` 拼接采集接口 —— `apiUrl` 为空时每次请求都失败，
 * 表现为**分类/列表/详情/选集全部静默为空**，很难定位。
 *
 * ## v1.0.15：人工校准过的站点**优先于一切**
 *
 * 采集接口模式与网页模式是两条完全独立的路：maccms 分支根本不读「站点配方」。
 * 于是只要 `apiMode` 是采集接口，**用户在调试校准模式里点出来的规则会被整个绕过** ——
 * 他明明校准成功了（还试播成功），重载站源却"规则没生效"。
 *
 * 而「校准过」（`calibAt > 0`）意味着：用户已经在真实网页里点通了 分类 → 详情 → 分集，
 * 并且那次播放**真的成功了**。这是比 `apiMode` 强得多的证据，必须压过它。
 * 典型触发场景：站点先被识别成有采集接口、后来接口被站方关掉（返回 `closed`），
 * 或用户把站点删掉重加了一遍 —— 两种情况都会让 apiMode 变回 maccms。
 */
object AdapterFactory {
    fun create(site: SiteConfig): SiteAdapter {
        // 人工校准过 ⇒ 网页解析被真实验证过 ⇒ 不允许被采集接口模式绕过
        val r = RecipeStore.load(site.baseUrl)
        if (r != null && r.calibAt > 0L) return HtmlAdapter(site)
        return when {
            site.apiUrl.isBlank() -> HtmlAdapter(site)
            site.apiMode == SiteConfig.MODE_MACCMS_XML -> MaccmsXmlAdapter(site)
            site.apiMode == SiteConfig.MODE_HTML -> HtmlAdapter(site)
            else -> MaccmsAdapter(site)
        }
    }
}
