package com.videoshell.ui

// SiteBrowser 的**搜索范围 / 引擎 / 输入联想**（拆出来的第一块）。
//
// 为什么这一族放一起：范围 chip、引擎 chip、右侧提示、联想下拉**必须共用一份渲染** ——
// 早期它们是各自刷各自的，于是出现过「改了引擎，页面上还是旧的」（v1.0.50 收口到 renderScope）。
// 而 doSearch 只是这套状态的读方，摆在旁边才看得出「谁写、谁读」。
//
// 拆法与约束同 PlayerActivity_Play.kt（纯搬运 + internal 扩展函数）：
// ⚠️ 本类的 act / b / launchCalib / onOpenDetail / openSearch 是**构造参数属性**，
// 搬出去的扩展函数访问不了 private，所以它们被放宽成 internal val（语义不变，
// 只是从「类内可见」变成「模块内可见」）。
// ⚠️ MODE_SEARCH / MODE_CATEGORY 住在 companion object 里：它们不能靠放宽可见性解决
// （companion 成员在类外必须写 `SiteBrowser.MODE_XXX`，这是语法要求），所以由 _fixvis
// 在调用点补限定名。
// 守卫按「主文件 + 同主名拆分子文件」读源码，所以断言写的 SiteBrowser.kt 覆盖本文件。

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.videoshell.R
import com.videoshell.data.ListCache
import com.videoshell.data.Store
import com.videoshell.data.model.Category
import com.videoshell.data.model.SiteConfig
import com.videoshell.data.model.VideoItem
import com.videoshell.data.net.Http
import com.videoshell.data.net.NetLog
import com.videoshell.data.site.AdapterFactory
import com.videoshell.data.site.AggSearch
import com.videoshell.data.model.VideoRow
import com.videoshell.data.site.CryptFamily
import com.videoshell.data.site.RecipeStore
import com.videoshell.data.site.RecipeTransfer
import com.videoshell.data.site.SearchEngine
import com.videoshell.data.site.SearchScope
import com.videoshell.data.site.SiteAdapter
import com.videoshell.data.site.SiteDoctor
import com.videoshell.databinding.ViewSiteBrowserBinding
import com.videoshell.player.SniffActivity
import com.videoshell.ui.adapter.CategoryAdapter
import com.videoshell.ui.adapter.SearchSuggestAdapter
import com.videoshell.ui.adapter.VideoAdapter
import com.videoshell.util.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ 搜索范围 / 搜索引擎

internal fun SiteBrowser.setScope(s: SearchScope) {
    if (scope == s) {
        renderScope()
        return
    }
    val before = scope
    scope = s
    Store.setSearchScope(act, s.name)
    renderScope()
    // ⚠️「已经在搜」的判据是**搜索区开着 + 关键词非空**，不能用 `mode == MODE_SEARCH`：
    // v1.0.50 起搜索结果在二级页里，本页网格始终是分类内容 ⇒ mode 恒为 MODE_CATEGORY。
    // 继续用 mode 判断的话，换范围会静默什么都不做（用户："我改成全站了，没反应"）。
    if (b.searchRow.visibility != View.VISIBLE || keyword.isBlank()) return
    // v1.0.50：换范围 = 用新范围**重新打开那一页**。不重开的话用户会经历
    // "我明明改成全站了，怎么屏幕上还是本站的结果" —— 那一页还停在返回栈上，
    // 他看到的确实还是旧的（与 setEngine 同一个理由）。
    val page = openSearch
    if (page != null && s != SearchScope.WEB) {
        page(keyword, s == SearchScope.ALL)
        return
    }
    // 已经在搜了 ⇒ 按新范围重来一遍。唯独 WEB 不重来网格（结果不在网格里，在网页里）
    when (s) {
        SearchScope.WEB -> openWebSearch(keyword)
        SearchScope.ALL -> { refreshSiteNames(); reload() }
        SearchScope.SITE -> if (before == SearchScope.ALL) reload()
    }
}

/**
 * 换引擎 = 换一个搜索地址，**正在搜就立刻用新引擎重开一次**。
 *
 * 不重开的话用户会经历"我明明改成百度了，怎么屏幕上还是 Bing"——
 * 上一次的网页还停在返回栈上，他看到的确实还是旧的。
 */
internal fun SiteBrowser.setEngine(e: SearchEngine) {
    engine = e
    Store.setSearchEngine(act, e.name)
    renderScope()
    if (scope == SearchScope.WEB && mode == SiteBrowser.MODE_SEARCH && keyword.isNotBlank()) {
        openWebSearch(keyword)
    }
}

internal fun SiteBrowser.setEnhance(on: Boolean) {
    enhance = on
    Store.setSearchEnhance(act, on)
    renderScope()
}

/**
 * 范围 chip + 引擎行 + 右侧提示，**一份渲染**。
 *
 * 引擎 chip 的选中态也在这里刷（不在单独的函数里各刷一遍）：两处各自维护一份的话，
 * 迟早出现"chip 显示百度、实际发出去的地址是 Bing"这种只看得见一半的不一致。
 */
internal fun SiteBrowser.renderScope() {
    b.scopeSite.isSelected = scope == SearchScope.SITE
    b.scopeAll.isSelected = scope == SearchScope.ALL
    b.scopeWeb.isSelected = scope == SearchScope.WEB

    b.engBaidu.isSelected = engine == SearchEngine.BAIDU
    b.engSogou.isSelected = engine == SearchEngine.SOGOU
    b.eng360.isSelected = engine == SearchEngine.SO360
    b.engBing.isSelected = engine == SearchEngine.BING
    b.engDdg.isSelected = engine == SearchEngine.DDG
    b.engSuffix.isSelected = enhance

    // 引擎行只在「全网」下出现，而且搜索区收起时跟着收起 ——
    // 它不是 scopeRow 的子视图，不显式跟着走就会在收起后**悬在界面上**
    val shown = b.searchRow.visibility == View.VISIBLE
    b.engineRow.visibility = if (shown && scope == SearchScope.WEB) View.VISIBLE else View.GONE

    b.tvScopeHint.text = when (scope) {
        SearchScope.SITE -> act.getString(R.string.scope_hint_site)
        SearchScope.ALL -> {
            val n = Store.sites(act).size
            if (n == 0) act.getString(R.string.scope_hint_all_empty)
            else act.getString(R.string.scope_hint_all, n)
        }
        SearchScope.WEB -> act.getString(
            R.string.scope_hint_web, act.getString(engine.labelRes)
        )
    }
}

/**
 * 全网搜索：**不做抓取解析**，直接把搜索引擎的结果页当网页打开。
 *
 * 理由见 [SearchScope] 的注释 —— 抓搜索引擎结果再解析，等于把"站点的适配难题"
 * 换成"搜索引擎的适配难题"，而且对方改版我们必挂。打开网页则一次也不用修。
 */
internal fun SiteBrowser.openWebSearch(kw: String) {
    b.tvScopeHint.text = act.getString(
        R.string.scope_web_searching, act.getString(engine.labelRes), kw
    )
    act.startActivity(
        SniffActivity.intent(
            act,
            SearchScope.webSearchUrl(kw, engine, enhance),
            kw,
            mapOf("User-Agent" to Http.UA),
            browse = true
        )
    )
}

/** 站名表只在「要用到聚合搜索」时刷一次（新增/改名/删除站点后都够新） */
internal fun SiteBrowser.refreshSiteNames() {
    siteNames.clear()
    Store.sites(act).forEach {
        siteNames[it.key] = it.name.ifBlank { Store.hostOf(it.baseUrl) }
    }
}

internal fun SiteBrowser.doSearch() {
    val kw = b.inputSearch.text.toString().trim()
    if (kw.isEmpty()) {
        act.toast("请输入关键词")
        return
    }
    keyword = kw
    hideSuggestions()
    // FN-2 的搜索历史以前**从来没被写过盘**（`Store.addSearchHistory` 全工程零调用），
    // 于是"最近搜索"永远是空的、长按删单条也就无从谈起。收口在这里：
    // 只要真的发起了一次搜索（本站 / 全站 / 全网任一），就该记一笔。
    Store.addSearchHistory(act, kw)
    if (scope == SearchScope.WEB) {
        // 全网：结果在网页里看。**刻意不清空网格** —— 清空会让人以为"没搜到"，
        // 而真相是结果换了地方显示；留着旧内容，视线自然跟着新开的网页走。
        openWebSearch(kw)
        return
    }
    val page = openSearch
    if (page != null) {
        // v1.0.50：结果交给二级页（左侧可换站源）。
        // ⚠️ 这里**不动**本页的网格，也不动 mode：网格仍然是分类浏览的内容，
        // 从二级页返回时看到的还是刚才那一屏，不会被清空再重拉。
        page(kw, scope == SearchScope.ALL)
        return
    }
    // 没有二级页宿主（离线自检 / 别的入口）⇒ 维持旧行为：在本页网格里搜
    mode = SiteBrowser.MODE_SEARCH
    if (scope == SearchScope.ALL) refreshSiteNames()
    reload()
}

// ------------------------------------------------------------------ 搜索历史（FN-2）

internal fun SiteBrowser.showSuggestions() {
    val hist = Store.searchHistory(act)
    if (hist.isEmpty()) {
        hideSuggestions()
        return
    }
    suggestAdapter.submit(hist)
    b.rvSearchSuggest.visibility = View.VISIBLE
}

internal fun SiteBrowser.hideSuggestions() {
    b.rvSearchSuggest.visibility = View.GONE
}
