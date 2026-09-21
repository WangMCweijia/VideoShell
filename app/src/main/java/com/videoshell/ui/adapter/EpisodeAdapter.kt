package com.videoshell.ui.adapter

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.videoshell.R
import com.videoshell.data.model.Episode
import com.videoshell.databinding.ItemEpisodeBinding
import com.videoshell.util.EpisodeCell
import com.videoshell.util.EpisodeOrder

/**
 * 选集网格。
 *
 * ⚠️ 两个「序号」必须分清（[submit] 的排序功能让这件事第一次有了意义）：
 * - **显示位置**：`onBindViewHolder` 的 position，会随「顺序/倒序」变；
 * - **组内原始序号**：分集在 `PlayGroup.episodes` 里的下标 —— 进度记忆的 key、
 *   上一集/下一集、自动连播都只认它。**它绝不跟着排序走**。
 *
 * 所以 [onClick] 回调给的是**原始序号**，[select] 收的也是原始序号。
 * 老版本两者恰好相等（不排序），换成排序后如果还按位置传，进度会被记到别的集上。
 *
 * ## 长集名（v1.0.49）
 *
 * 综艺的分集名是「20240921 第10期 嘉宾：周深」，单行一定被截成「202409…」而失
 * 去区分度。拆名与列数规划都在 [EpisodeCell] 里，这里只负责把结果画上去。
 *
 * ⚠️ **不要在 bind 里改 [Episode.name]**：进度身份是「站点|剧名|集名#组内序号」，
 * 名字一改，进度、上一集/下一集、自动连播全部错位。拆名是纯渲染期行为。
 * ⚠️ `twoLine` 由 [EpisodeCell.plan] **整列**算出，不要在这里逐格判断 ——
 * 逐格判断会让同一行有的格子高、有的矮。
 */
class EpisodeAdapter(
    private val onClick: (Int, Episode) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

    private val items = ArrayList<Episode>()

    /** 显示位置 → 组内原始序号 */
    private val origin = ArrayList<Int>()

    /** 当前选中项，存的是**原始序号** */
    private var selected = -1

    /** 是否走主副两行（整列统一，来自 [EpisodeCell.plan]） */
    private var twoLine = false

    /**
     * 提交一整个线路。
     * @param desc true = 倒序显示（顺序由 [EpisodeOrder] 决定，不改变数据本身）
     * @param twoLine true = 主副两行（长名站点，由 [EpisodeCell.plan] 整列决定）
     */
    @JvmOverloads
    fun submit(list: List<Episode>, desc: Boolean = false, twoLine: Boolean = false) {
        items.clear()
        origin.clear()
        for (i in EpisodeOrder.order(list, desc)) {
            origin.add(i)
            items.add(list[i])
        }
        this.twoLine = twoLine
        selected = -1
        notifyDataSetChanged()
    }

    /** @param index **组内原始序号**（不是显示位置） */
    fun select(index: Int) {
        val old = positionOf(selected)
        selected = index
        val now = positionOf(index)
        if (old in items.indices) notifyItemChanged(old)
        if (now in items.indices) notifyItemChanged(now)
    }

    private fun positionOf(originIndex: Int): Int = origin.indexOf(originIndex)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(items[position], position == positionOf(selected))

    inner class VH(private val b: ItemEpisodeBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(ep: Episode, isSelected: Boolean) {
            bindName(ep, isSelected)
            // FN-1：分集缩略图（可选）
            if (ep.pic.isNotBlank()) {
                b.ivPic.visibility = View.VISIBLE
                b.ivPic.load(ep.pic) {
                    crossfade(true)
                    placeholder(R.drawable.bg_poster)
                    error(R.drawable.bg_poster)
                }
            } else {
                b.ivPic.visibility = View.GONE
            }
            b.root.isSelected = isSelected
            // 这一句必须**在** bindName 之后：Spannable 里的 ForegroundColorSpan 是
            // 给单位字单独上色的，而 setTextColor 管的是其余部分。两者各管一段，
            // 但只有先 setText 后 setTextColor 才成立（反过来 setText 会把颜色重置）。
            b.tvName.setTextColor(
                ContextCompat.getColor(
                    b.root.context,
                    if (isSelected) R.color.white else R.color.text_primary
                )
            )
            b.root.setOnClickListener {
                // 用绑定后的实时位置回查原始序号：列表被换掉时也不会点到隔壁
                val pos = bindingAdapterPosition
                if (pos in origin.indices) onClick(origin[pos], ep)
            }
        }

        /**
         * 画集名。
         *
         * 单行模式 = 原文照贴（短名站点与 v1.0.48 之前完全一致，不做任何加工）。
         * 两行模式 = 主行「期号+单位字」、副行「日期 + 剩余文字」。
         *
         * 副行在 `twoLine` 下**永远 VISIBLE**（哪怕文本为空）：网格是逐格测量高度的，
         * 靠"有内容才显示"会让同一行出现两种高度。空串照样占住 minHeight。
         */
        private fun bindName(ep: Episode, isSelected: Boolean) {
            if (!twoLine) {
                b.tvName.text = ep.name
                b.tvSub.visibility = View.GONE
                return
            }
            val cell = EpisodeCell.of(ep.name)
            b.tvName.text = spannedMain(cell, isSelected)
            b.tvSub.text = cell.sub
            b.tvSub.visibility = View.VISIBLE
        }

        /** 主行 = 数字（正常字号）+ 单位字（0.78 倍，次要色） */
        private fun spannedMain(cell: EpisodeCell.Cell, isSelected: Boolean): CharSequence {
            val unit = cell.unit
            if (unit.isEmpty()) return cell.main
            val end = cell.main.length + unit.length
            return SpannableString(cell.main + unit).apply {
                setSpan(
                    RelativeSizeSpan(0.78f), cell.main.length, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                // 选中时不要叠颜色 span：底色已经是品牌蓝，再由 span 定死次要色
                // 会出现"蓝底灰字"，而且和 setTextColor(白) 互相覆盖
                if (!isSelected) {
                    setSpan(
                        ForegroundColorSpan(
                            ContextCompat.getColor(b.root.context, R.color.text_secondary)
                        ),
                        cell.main.length, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }
    }
}
