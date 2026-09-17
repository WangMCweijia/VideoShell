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
 */
object AdapterFactory {
    fun create(site: SiteConfig): SiteAdapter = when {
        site.apiUrl.isBlank() -> HtmlAdapter(site)
        site.apiMode == SiteConfig.MODE_MACCMS_XML -> MaccmsXmlAdapter(site)
        site.apiMode == SiteConfig.MODE_HTML -> HtmlAdapter(site)
        else -> MaccmsAdapter(site)
    }
}
