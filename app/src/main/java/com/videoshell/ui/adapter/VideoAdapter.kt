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
import com.videoshell.databinding.ItemVideoBinding

class VideoAdapter(
    private val onClick: (VideoItem) -> Unit,
    /**
     * `siteKey` → 站名（v1.0.37）。
     *
     * 「搜全站源」的聚合结果里，**站名是这张卡片最要紧的信息之一**：同一部剧在几个站上都有，
     * 用户要选的是"哪个站"，而不是"哪部剧"。所以把它排在副标题最前面（那行是 ellipsize=end，
     * 排后面的信息会被截掉）。单站浏览时 `siteKey` 为空 ⇒ 一个字都不显示，行为与以前完全一致。
     */
    private val siteNameOf: (String) -> String = { "" }
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    private val items = ArrayList<VideoItem>()

    /**
     * 当前搜索关键词：命中时在片名里标出来（加粗 + 品牌色）。空串 = 不标。
     *
     * 为什么需要它：站点的搜索是宽匹配（标题/标签/演员/简介都算），标出片名里的命中词，
     * 用户一眼就能分清「这条是标题匹配」和「那条是靠标签进来的（角标会写明）」。
     * 收藏页等其它复用方不设这个字段，行为与以前完全一致。
     */
    var highlight: String = ""

    fun submit(list: List<VideoItem>, append: Boolean) {
        if (!append) items.clear()
        val start = items.size
        items.addAll(list)
        if (append) notifyItemRangeInserted(start, list.size) else notifyDataSetChanged()
    }

    fun size(): Int = items.size

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemVideoBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(v: VideoItem) {
            b.tvName.text = markedName(v.name)

            val site = if (v.siteKey.isNotBlank()) siteNameOf(v.siteKey).trim() else ""
            val sub = (listOf(site, v.typeName, v.year, v.area))
                .filter { it.isNotBlank() }.joinToString(" · ")
            b.tvSub.text = sub
            b.tvSub.visibility = if (sub.isBlank()) View.GONE else View.VISIBLE

            b.tvRemarks.text = v.remarks
            b.tvRemarks.visibility = if (v.remarks.isBlank()) View.GONE else View.VISIBLE

            b.tvScore.text = v.score
            b.tvScore.visibility = if (v.score.isBlank()) View.GONE else View.VISIBLE

            if (v.pic.isNotBlank()) {
                b.ivPic.load(v.pic) {
                    crossfade(true)
                    placeholder(R.drawable.bg_poster)
                    error(R.drawable.bg_poster)
                }
            } else {
                b.ivPic.setImageDrawable(null)
            }

            b.root.setOnClickListener { onClick(v) }
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
