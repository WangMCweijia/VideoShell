package com.videoshell.data.site

/**
 * 搜索范围（v1.0.37）。
 *
 * 三种范围解决的是三件不同的事，**判据与出口都不一样**，所以必须显式分开，
 * 而不是"在代码里悄悄多试几个站"：
 *
 * - [SITE]  只搜当前站源 —— 快、结果一定能在本站打开，是默认行为；
 * - [ALL]   同时搜**全部已保存站源** —— 剧在别的站有而本站没有时用；结果是聚合的，
 *           每条都带来源站（见 `VideoItem.siteKey`）；
 * - [WEB]   搜**全网** —— 直接交给搜索引擎（见 [SearchEngine]，可选），结果在
 *           「网页嗅探模式」里浏览，看到合适的视频页可以一键识别并添加成站源。
 *
 * ⚠️ 为什么不把 [WEB] 也做成"抓搜索结果再解析成卡片"：
 * 搜索引擎的结果页是 JS 渲染 + 反爬的，抓回来再解析等于把"站点的适配问题"换成
 * "搜索引擎的适配问题"，而且搜索引擎改版我们必挂。**直接把它当网页打开**，
 * 我们只负责"在网页上做我们擅长的事"（识别站型、嗅探播放），这条线才不会年年修。
 */
enum class SearchScope {

    SITE,
    ALL,
    WEB;

    companion object {

        /** 从持久化值恢复；认不出（老版本存的空串 / 手改坏了）一律回 [SITE] */
        fun of(raw: String?): SearchScope {
            val v = raw?.trim().orEmpty()
            if (v.isEmpty()) return SITE
            for (s in values()) if (s.name.equals(v, ignoreCase = true)) return s
            return SITE
        }

        /**
         * 全网搜索地址。默认用 [SearchEngine.DEFAULT]（v1.0.38 起为百度）。
         *
         * 引擎与"是否加影视化后缀"都由调用方传进来 —— 这个函数只负责拼地址，
         * **不自己读配置**：读配置散在工具类里，就会出现"同一个设置在不同入口表现不同"。
         */
        fun webSearchUrl(
            keyword: String,
            engine: SearchEngine = SearchEngine.DEFAULT,
            enhance: Boolean = false
        ): String = engine.searchUrl(keyword, enhance)

        /** 全网搜索的入口页（没输关键词时用） */
        fun webHomeUrl(engine: SearchEngine = SearchEngine.DEFAULT): String = engine.home()

        /**
         * 「当前还在搜索引擎上」——用 [SearchEngine.isSearchHost]，覆盖全部已支持的引擎。
         *
         * 旧版这里写死 `bing.com`：一旦允许换引擎，百度/搜狗的结果页就会被当成
         * "一个还没识别出站型的普通网页"，用户可以把它添加成站源（拦不住的那种错）。
         */
        fun isWebHost(url: String): Boolean = SearchEngine.isSearchHost(url)
    }
}
