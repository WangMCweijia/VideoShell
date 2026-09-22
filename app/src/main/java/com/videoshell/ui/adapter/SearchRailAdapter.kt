package com.videoshell.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.videoshell.databinding.ItemSearchRailBinding

/**
 * 搜索结果页左侧的站源栏（v1.0.50）。
 *
 * 第 **0 项约定为「聚合」**（= 全部站源），之后依次是已保存的站点 —— 这个约定由
 * [com.videoshell.ui.SearchActivity] 建立并维护（它那边的 `railSites[0] == null`），
 * 适配器只按顺序显示，不做任何身份判断。
 *
 * ## 为什么计数单独一个 [setCount] 而不是整表重提
 *
 * 每搜完一个站就会得到一个条数，聚合搜索下这是**逐个到达**的（10 个站 10 次）。
 * 每次都 `notifyDataSetChanged` 会让整栏（10 行）反复重绑，用户正盯着它看的时候会闪。
 * 按位置 `notifyItemChanged` 只重绑那一行。
 */
class SearchRailAdapter(
    private val onClick: (index: Int) -> Unit
) : RecyclerView.Adapter<SearchRailAdapter.VH>() {

    private val names = ArrayList<String>()
    private val counts = ArrayList<String?>()
    private var selected = 0

    fun submit(newNames: List<String>, newCounts: List<String?>, select: Int) {
        names.clear(); names.addAll(newNames)
        counts.clear()
        repeat(newNames.size) { counts += newCounts.getOrNull(it) }
        selected = select.coerceIn(0, (newNames.size - 1).coerceAtLeast(0))
        notifyDataSetChanged()
    }

    /** 切换选中项：只重绑新旧两行 */
    fun select(index: Int) {
        if (index == selected || index !in names.indices) return
        val before = selected
        selected = index
        notifyItemChanged(before)
        notifyItemChanged(index)
    }

    /** 更新某一项的计数（值没变就不刷，避免流式搜索下每个站到达时都重绑一遍） */
    fun setCount(index: Int, text: String?) {
        if (index !in counts.indices) return
        if (counts[index] == text) return
        counts[index] = text
        notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSearchRailBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = names.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(position)

    inner class VH(private val b: ItemSearchRailBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(index: Int) {
            b.tvRailName.text = names[index]
            val c = counts.getOrNull(index)
            b.tvRailCount.text = c.orEmpty()
            b.tvRailCount.visibility = if (c.isNullOrBlank()) View.GONE else View.VISIBLE
            // selected 设在**根布局**上，两个子 TextView 靠 duplicateParentState 跟着变
            b.root.isSelected = index == selected
            b.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(pos)
            }
        }
    }
}
