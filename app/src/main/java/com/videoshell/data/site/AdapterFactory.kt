package com.videoshell.data.site

import com.videoshell.data.model.SiteConfig

object AdapterFactory {
    fun create(site: SiteConfig): SiteAdapter = when (site.apiMode) {
        SiteConfig.MODE_MACCMS_XML -> MaccmsXmlAdapter(site)
        SiteConfig.MODE_HTML -> HtmlAdapter(site)
        else -> MaccmsAdapter(site)
    }
}
