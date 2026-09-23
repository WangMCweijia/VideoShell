package com.videoshell.data.site

import com.videoshell.data.pan.PanLink
import org.jsoup.nodes.Document

/**
 * 「网盘分享站」详情页的**形状抽取**（纯 DOM 操作，不发请求、不写盘）。
 *
 * 独立成一个 object 是为了能被离线回归直接喂样本 HTML 断言 ——
 * 判据一旦埋在适配器里，就只能靠"去真站点点一遍"来验证。
 *
 * 实测形状（2026-09-23，快映 `43.248.128.118:12512` 与玩偶 `www.wogg.live` **同构**）：
 *
 * ```html
 * <div class="module" id="download-list">
 *   <div class="module-tab-content">
 *     <div class="module-tab-item downtab-item"><span data-dropdown-value="百度">百度</span><small>1</small></div>
 *     <div class="module-tab-item downtab-item selected"><span data-dropdown-value="夸克网盘">夸克网盘</span>…</div>
 *   </div>
 *   <div class="module-list module-downlist selected"><div class="scroll-box-y">
 *     <div class="module-row-one">
 *       <a class="module-row-text copy" data-clipboard-text="https://pan.baidu.com/s/1…?pwd=6107">…</a>
 *       <a class="btn-copyurl copy" data-clipboard-text="https://pan.baidu.com/s/1…?pwd=6107">…</a>
 *     </div>
 *     …
 * ```
 *
 * 两个必须注意的细节：
 *
 * 1. **`data-dropdown-value` 挂在 `.downtab-item` 的**子 `<span>`** 上**，不在那个 div 上 ——
 *    所以选择器必须是后代关系 `.downtab-item [data-dropdown-value]`。写成
 *    `.downtab-item[data-dropdown-value]` 会一条都选不中（这是最容易写错的一处）。
 * 2. **同一个链接在一条 `.module-row-one` 里出现两次**（一次在"标题"上、一次在"复制链接"
 *    按钮上）。所以必须**按值去重**，否则线路数会翻倍、剧集也会跟着翻倍。
 */
object PanShareExtract {

    /** 线路名（`<span data-dropdown-value="夸克网盘">`） */
    private const val TAB_SEL = ".downtab-item [data-dropdown-value]"

    /** 分享链接所在的行（限定在 `.module-row-one` 内，避免把页面别处的"复制"按钮收进来） */
    private const val ROW_SEL = ".module-row-one [data-clipboard-text]"

    private const val ANY_SEL = "[data-clipboard-text]"

    /**
     * 线路名，**按出现顺序、保留重复**。
     *
     * 刻意不去重：玩偶站实测有两条线路**都叫"夸克网盘"**（两个不同的分享文件夹）。
     * 去重后名字数（1）就与链接数（2）对不上，配对只能退化成按网盘类型猜 ——
     * 而顺序配对比类型猜测准确得多（见 [PanShareAdapter]）。
     */
    fun lineNames(doc: Document): List<String> = doc.select(TAB_SEL)
        .map { it.attr("data-dropdown-value").trim() }
        .filter { it.isNotEmpty() && it.length <= 24 }

    /**
     * 分享链接，**按出现顺序、按值去重**。
     *
     * 只保留 [PanLink] 认得出的值 —— 这就是 D2 判据的落地形态：
     * 判据锚在"这条链接真的指向一个我们认识的网盘"，而不是"页面上有个复制按钮"。
     */
    fun shareLinks(doc: Document): List<String> {
        val out = LinkedHashSet<String>()
        fun collect(sel: String) {
            for (e in doc.select(sel)) {
                val v = e.attr("data-clipboard-text").trim()
                if (v.isNotEmpty() && PanLink.parse(v) != null) out.add(v)
            }
        }
        collect(ROW_SEL)
        // 兜底：有的主题把整块包在别的容器名里，此时全文档扫一遍
        // （仍然要求"值能被 PanLink 认出来"，所以不会误收普通分享按钮）
        if (out.isEmpty()) collect(ANY_SEL)
        return out.toList()
    }

    /** D1 + D2 同时成立 ⇒ 这是网盘分享站的详情页 */
    fun isPanSharePage(doc: Document): Boolean =
        lineNames(doc).isNotEmpty() && shareLinks(doc).isNotEmpty()

    /**
     * 判据的现场证据（进 `calibDiag` / 自检报告）。
     *
     * 只列**数得出来的东西**，不写"疑似"：用户看到「D1=2 线路｜D2=2 链接｜
     * player_aaaa=0 处｜module-item=2302 处」就能判断这次判定站不站得住。
     */
    fun evidence(doc: Document, html: String): String {
        val names = lineNames(doc)
        val links = shareLinks(doc)
        val pa = Regex("player_aaaa").findAll(html).count()
        val kind = links.mapNotNull { PanLink.parse(it)?.type?.label }.distinct()
        return "D1 线路=${names.size}（${names.take(4).joinToString("/")}）｜" +
                "D2 分享链接=${links.size}（${kind.joinToString("/")}）｜" +
                "D3 player_aaaa=$pa 处｜D4 module-item=${doc.select(".module-item").size} 处"
    }
}
