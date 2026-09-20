package com.videoshell.data.site

import com.videoshell.R
import java.net.URLEncoder

/**
 * 「全网搜索」用哪个搜索引擎（v1.0.38）。
 *
 * ## 为什么要有得选
 *
 * v1.0.37 只有 Bing 一个。实测下来它对**中文在线影视站**很不友好：出于合规与降权策略，
 * 这类站点在 Bing 的首页结果里几乎不出现，搜一部剧出来全是正版平台和豆瓣。
 * 而这个功能的用途恰恰是"找一个能播的站" —— 引擎选错了，整个功能就等于没有。
 *
 * 换成国内引擎后同一个关键词能直接落到影视站上，所以这里做成**可选**而不是换死：
 * 不同引擎的收录偏好不一样，而且哪家好用是会变的（见 docs/PITFALLS.md §4.1：
 * 会变的东西不能写死在判据里，只能作为"可切换的选项"）。
 *
 * ## 为什么每家的地址都写全
 *
 * 结果页一律**当网页打开**（不抓取解析，理由见 [SearchScope]），所以新增一个引擎的成本
 * 就是一个 URL 模板 —— 这也是把"选哪家"交给用户的前提：选项便宜，才敢给。
 *
 * ⚠️ [owns] 是"防误收"用的：用户从结果页点进目标站之前，当前页面还停在搜索引擎上，
 * 这时点「识别并添加」不能把百度/搜狗本身变成一个视频站源。多一个引擎就多一组域名，
 * 所以这份清单必须与枚举**同源**（放在枚举里，而不是散在调用点）。
 */
enum class SearchEngine(
    val labelRes: Int,
    /** 该引擎自己（含跳转域）的域名后缀。判断"当前还在搜索引擎上"用 */
    private val hosts: List<String>,
    /** 搜索地址模板，`%s` 处填百分号编码后的关键词 */
    private val searchTpl: String,
    /** 首页地址（没输关键词就进全网时用） */
    private val homeUrl: String
) {

    /**
     * 百度：中文影视站收录最全。
     *
     * 定为默认 —— 这个功能的目的是"找能播的站"，覆盖度是第一位的；
     * 广告多、页面重是它的代价，用户随时可以切到搜狗/360。
     */
    BAIDU(
        R.string.engine_baidu,
        listOf("baidu.com"),
        "https://www.baidu.com/s?wd=%s",
        "https://www.baidu.com/"
    ),

    /** 搜狗：结果页相对干净，影视/微信内容源收录不错 */
    SOGOU(
        R.string.engine_sogou,
        listOf("sogou.com"),
        "https://www.sogou.com/web?query=%s",
        "https://www.sogou.com/"
    ),

    /** 360 搜索：影视站收录也广，页面比百度轻 */
    SO360(
        R.string.engine_360,
        listOf("so.com", "360.cn"),
        "https://www.so.com/s?q=%s",
        "https://www.so.com/"
    ),

    /** Bing：原来的默认。界面干净、不弹 App，但中文影视站降权严重 */
    BING(
        R.string.engine_bing,
        listOf("bing.com"),
        "https://www.bing.com/search?q=%s",
        "https://www.bing.com/"
    ),

    /** DuckDuckGo（HTML 版）：不过滤、不个性化，适合找被国内引擎屏蔽的目标；中文影视站收录少 */
    DDG(
        R.string.engine_ddg,
        listOf("duckduckgo.com"),
        "https://html.duckduckgo.com/html/?q=%s",
        "https://html.duckduckgo.com/html/"
    );

    /** 搜索地址。中文/空格/`&`/`#` 一律百分号编码，否则关键词会把 URL 拆坏 */
    fun searchUrl(keyword: String, enhance: Boolean = false): String =
        String.format(searchTpl, encode(keywordOf(keyword, enhance)))

    fun home(): String = homeUrl

    /** 这个（已经过 `Store.hostOf` 归一的）域名是不是本引擎 */
    fun owns(host: String): Boolean {
        val h = host.trim().lowercase()
        if (h.isEmpty()) return false
        return hosts.any { h == it || h.endsWith(".$it") }
    }

    companion object {

        /**
         * 默认引擎：百度。
         *
         * 这是"找能播的站"这个用途下的最优解（中文影视站收录最全），
         * 但**它只是个默认值，不是判据** —— 用户随时可以改，
         * 而"哪家收录好"将来会变，所以写在这里而不是写进任何匹配逻辑里。
         */
        val DEFAULT: SearchEngine = BAIDU

        /**
         * 关键词增强的后缀（默认不启用，用户在界面上勾）。
         *
         * 影视站的页面标题几乎都带「在线观看 / 免费观看」，加上这四个字能把结果
         * 从"正版平台 + 影评"直接压向"能点开就播的站" —— 比换引擎更立竿见影。
         */
        const val SUFFIX = "在线观看"

        /** 认不出就回默认（老版本存的空串 / 手改坏了），**绝不按序号解析** */
        fun of(raw: String?): SearchEngine {
            val v = raw?.trim().orEmpty()
            if (v.isEmpty()) return BAIDU
            for (e in values()) if (e.name.equals(v, ignoreCase = true)) return e
            return BAIDU
        }

        /** 追加影视化后缀；已经带了就不重复加（用户自己写全了就别画蛇添足） */
        fun keywordOf(keyword: String, enhance: Boolean): String {
            val k = keyword.trim()
            if (!enhance || k.isEmpty() || k.contains(SUFFIX)) return k
            return "$k $SUFFIX"
        }

        /** URL 或裸域名 → 是不是任意一个搜索引擎（防把结果页当成视频站源添加） */
        fun isSearchHost(urlOrHost: String): Boolean {
            val h = com.videoshell.data.Store.hostOf(urlOrHost)
            return values().any { it.owns(h) }
        }

        private fun encode(s: String): String =
            runCatching { URLEncoder.encode(s.trim(), "UTF-8") }.getOrDefault("")
    }
}
