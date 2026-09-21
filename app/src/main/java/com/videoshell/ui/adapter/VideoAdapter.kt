package com.videoshell.ui.adapter

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.videoshell.R
import com.videoshell.data.model.VideoItem
import com.videoshell.data.model.VideoRow
import com.videoshell.databinding.ItemSiteHeaderBinding
import com.videoshell.databinding.ItemVideoBinding

/**
 * 影片网格适配器。**列表里装的是"行"（[VideoRow]）而不是清一色的卡片**（v1.0.39）。
 *
 * 两种行：分组标题（「搜全站源」时每个站一块的开头）与影片卡片。
 * 单站浏览 / 分类浏览只产出卡片行，行为与 v1.0.38 完全一致。
 */
class VideoAdapter(
    private val onClick: (VideoItem) -> Unit,
    /**
     * `siteKey` → 站名。
     *
     * 「搜全站源」的聚合结果里，**站名是这张卡片最要紧的信息之一**：同一部剧在几个站上都有，
     * 用户要选的是"哪个站"，而不是"哪部剧"。
     *
     * ⚠️ v1.0.39 起它的主要用途变成了**分组标题**（[VideoRow.Header] 的站名由 `AggSearch.rows`
     * 直接从 `SiteHits.name` 带进来）。卡片副标题里**只在搜索展示之外的场合**才会出现站名，
     * 而那时 `siteKey` 本来是空的。留着这个回调是为了收藏页等复用方，以及单站场景的兼容。
     */
    private val siteNameOf: (String) -> String = { "" }
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        /** 一张影片卡片 */
        const val TYPE_CARD = 0

        /** 一个站的分组标题（独占整行，靠 GridLayoutManager 的 SpanSizeLookup 实现） */
        const val TYPE_HEADER = 1
    }

    private val rows = ArrayList<VideoRow>()

    /**
     * 当前搜索关键词：命中时在片名里标出来（加粗 + 品牌色）。空串 = 不标。
     *
     * 为什么不直接给外部写：它和 [hideTags] 是**同一件事的两面**，必须同时设置 ——
     * 详见 [setSearchKeyword]。
     */
    var highlight: String = ""
        private set

    /**
     * 搜索展示时副标题里**不显示分类标签**（类型 / 年份 / 地区）。
     *
     * 取值口径与 [highlight] 严格一致，只能通过 [setSearchKeyword] 改。
     */
    var hideTags: Boolean = false
        private set

    /**
     * 进入 / 退出「搜索展示」。**这是唯一入口**（v1.0.39）。
     *
     * 一次设置两样东西：
     *  - [highlight]：片名里命中关键词的那一段标黄，用户一眼看清"为什么这条会出现"；
     *  - [hideTags]：副标题里的分类标签（类型 / 年份 / 地区）不再显示。
     *
     * ## 为什么搜索时不显示分类标签
     *
     * 分类浏览时"电影 / 2024 / 美国"是有用的——你在按分类翻片子。
     * 但搜索时用户已经知道自己在找什么，这一行标签只剩下噪音；更要紧的是，
     * 聚合搜索里**同一部剧在几个站上都有**，那一行真正该占位置的信息是"来自哪个站"，
     * 而不是三行通用的元数据。
     *
     * ## 为什么合成一个入口而不是两个 `var`
     *
     * 这两个字段永远是同一件事的两面（有搜索词 = 在看搜索结果）。留成两个公开字段，
     * 就一定会有某一处只设了其中一个 —— 本项目踩过"字段漏传 ⇒ 功能静默失效"的坑，
     * 能只给一个入口就不给两个。空串 = 退出搜索展示，行为与 v1.0.38 完全一致。
     */
    fun setSearchKeyword(keyword: String) {
        highlight = keyword
        hideTags = keyword.isNotBlank()
    }

    fun submitRows(list: List<VideoRow>, append: Boolean) {
        if (!append) rows.clear()
        val start = rows.size
        rows.addAll(list)
        if (append) notifyItemRangeInserted(start, list.size) else notifyDataSetChanged()
    }

    /** 只有卡片（单站浏览 / 分类浏览 / 收藏页）：把每个条目包成一行卡片 */
    fun submit(list: List<VideoItem>, append: Boolean) =
        submitRows(list.map { VideoRow.Card(it) }, append)

    /**
     * 「搜全站源」流式铺网格专用：把**一个站的一整块行**插到 [at] 位置。
     *
     * 为什么不复用 [submitRows]：流式的每一站要插到"排在它前面的那些站之后"，
     * 那个位置**不在尾部**。这条路径用 `notifyItemRangeInserted` 而不是
     * `notifyDataSetChanged` —— 前者让 RecyclerView 按"插入"处理，
     * 已经显示出来的卡片不会被整表重绑（重绑会让用户在盯着看时闪一下）。
     *
     * 插入位置由 `AggSearch.insertAt` 算（**以行为单位**），与最终全量结果同源，
     * 这里不做任何判断。
     */
    fun insertBlock(at: Int, block: List<VideoRow>) {
        if (block.isEmpty()) return
        val pos = at.coerceIn(0, rows.size)
        rows.addAll(pos, block)
        notifyItemRangeInserted(pos, block.size)
    }

    /** 卡片投影（断言与自检用）。分组标题不是内容，不进这里 */
    fun snapshot(): List<VideoItem> = rows.mapNotNull { (it as? VideoRow.Card)?.item }

    /** 行快照（自检用）：能验"每个标题行是不是恰好落在它那一块的开头" */
    fun rowSnapshot(): List<VideoRow> = ArrayList(rows)

    /** 卡片条数（不含分组标题）。进度行的"先显示 N 条"与它同口径 */
    fun size(): Int = rows.count { it is VideoRow.Card }

    /**
     * 该位置是不是分组标题。
     *
     * 给 `GridLayoutManager.SpanSizeLookup` 用：标题必须**独占整行**，
     * 否则在 3/5 列的网格里它会和卡片挤在一行，分组边界反而更糊。
     */
    fun isHeader(position: Int): Boolean = rows.getOrNull(position) is VideoRow.Header

    fun clear() {
        rows.clear()
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is VideoRow.Header) TYPE_HEADER else TYPE_CARD

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(ItemSiteHeaderBinding.inflate(inf, parent, false))
        } else {
            VH(ItemVideoBinding.inflate(inf, parent, false))
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        when {
            holder is HeaderVH && row is VideoRow.Header -> holder.bind(row)
            holder is VH && row is VideoRow.Card -> holder.bind(row.item)
        }
    }

    /** 分组标题：站名 + 这一块的条数 */
    inner class HeaderVH(private val b: ItemSiteHeaderBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(h: VideoRow.Header) {
            b.tvHeaderSite.text = h.name
            b.tvHeaderCount.text = "${h.count} 条"
        }
    }

    inner class VH(private val b: ItemVideoBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(v: VideoItem) {
            b.tvName.text = markedName(v.name)

            // v1.0.39：搜索展示时不显示分类标签。
            //  - 全站搜索：副标题只剩来源站名 —— 正是聚合搜索唯一要说的事；
            //  - 单站搜索：副标题整行消失（没有站名可显示），列表更干净。
            val site = if (v.siteKey.isNotBlank()) siteNameOf(v.siteKey).trim() else ""
            val sub = if (hideTags) {
                site
            } else {
                listOf(site, v.typeName, v.year, v.area)
                    .filter { it.isNotBlank() }.joinToString(" · ")
            }
            b.tvSub.text = sub
            b.tvSub.visibility = if (sub.isBlank()) View.GONE else View.VISIBLE

            b.tvRemarks.text = v.remarks
            b.tvRemarks.visibility = if (v.remarks.isBlank()) View.GONE else View.VISIBLE

            b.tvScore.text = v.score
            b.tvScore.visibility = if (v.score.isBlank()) View.GONE else View.VISIBLE

            b.ivPic.contentDescription = v.name   // UI-6：读屏时能读出影片名

            if (v.pic.isNotBlank()) {
                b.ivPic.load(v.pic) {
                    crossfade(true)
                    placeholder(R.drawable.bg_poster)
                    error(R.drawable.bg_poster)
                }
            } else {
                b.ivPic.setImageDrawable(null)
            }

            // UI-2：按卡片宽度 × 1.4 算海报高度，大屏不再被压扁/压方
            adjustPoster(b)

            b.root.setOnClickListener { onClick(v) }
        }

        /**
         * 海报高度 = 卡片宽度 × 1.4（UI-2）。
         *
         * 网格列宽随屏宽变，写死 136dp 在平板上会被压扁、在窄屏上又不协调。
         * 这里在绑定后用实际测量到的宽度反算高度；宽度还没量到时（首帧）
         * 用 `post` 等下一布局周期再算一次，算过就不再重复 requestLayout。
         */
        private fun adjustPoster(b: ItemVideoBinding) {
            b.ivPic.post {
                val w = b.posterFrame.width
                if (w <= 0) return@post
                val h = (w * 1.4f).toInt()
                if (b.posterFrame.layoutParams.height != h) {
                    b.posterFrame.layoutParams.height = h
                    b.posterFrame.requestLayout()
                }
            }
        }

        /** 片名里标出关键词；没设置关键词、或片名不含关键词，都原样返回 */
        private fun markedName(name: String): CharSequence {
            val kw = highlight.trim()
            if (kw.isEmpty() || name.isEmpty()) return name
            val i = name.indexOf(kw, ignoreCase = true)
            if (i < 0) return name
            val end = (i + kw.length).coerceAtMost(name.length)
            return SpannableString(name).apply {
                setSpan(StyleSpan(Typeface.BOLD), i, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(b.root.context, R.color.brand)),
                    i, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }
}
