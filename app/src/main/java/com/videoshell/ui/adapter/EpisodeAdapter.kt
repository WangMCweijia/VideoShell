package com.videoshell.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.videoshell.R
import com.videoshell.data.model.Episode
import com.videoshell.databinding.ItemEpisodeBinding
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
 */
class EpisodeAdapter(
    private val onClick: (Int, Episode) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

    private val items = ArrayList<Episode>()

    /** 显示位置 → 组内原始序号 */
    private val origin = ArrayList<Int>()

    /** 当前选中项，存的是**原始序号** */
    private var selected = -1

    /**
     * 提交一整个线路。
     * @param desc true = 倒序显示（顺序由 [EpisodeOrder] 决定，不改变数据本身）
     */
    @JvmOverloads
    fun submit(list: List<Episode>, desc: Boolean = false) {
        items.clear()
        origin.clear()
        for (i in EpisodeOrder.order(list, desc)) {
            origin.add(i)
            items.add(list[i])
        }
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
            b.tvName.text = ep.name
            b.root.isSelected = isSelected
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
    }
}
