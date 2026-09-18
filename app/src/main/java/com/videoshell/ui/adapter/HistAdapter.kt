package com.videoshell.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.videoshell.R
import com.videoshell.data.HistEntry
import com.videoshell.databinding.ItemHistBinding

/** 播放历史列表（一部剧一条，点击回详情页，长按删除该条） */
class HistAdapter(
    private val onClick: (HistEntry) -> Unit,
    private val onLongClick: (HistEntry) -> Unit
) : RecyclerView.Adapter<HistAdapter.VH>() {

    private val items = ArrayList<HistEntry>()

    fun submit(list: List<HistEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemHistBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemHistBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(e: HistEntry) {
            b.tvName.text = e.name
            b.tvEp.text = if (e.ep.isBlank()) "看过了" else "看到 ${e.ep}"
            b.tvTime.text = formatTs(e.ts)
            if (e.pic.isNotBlank()) {
                b.ivPic.load(e.pic) {
                    crossfade(true)
                    placeholder(R.drawable.bg_poster)
                    error(R.drawable.bg_poster)
                }
            } else {
                b.ivPic.setImageDrawable(null)
            }
            b.root.setOnClickListener { onClick(e) }
            b.root.setOnLongClickListener { onLongClick(e); true }
        }

        private fun formatTs(ts: Long): String {
            if (ts <= 0) return ""
            val diff = System.currentTimeMillis() - ts
            val day = 86_400_000L
            return when {
                diff < 3_600_000L -> "${(diff / 60_000L).coerceAtLeast(1)} 分钟前"
                diff < day -> "${diff / 3_600_000L} 小时前"
                diff < day * 30 -> "${diff / day} 天前"
                else -> ""
            }
        }
    }
}
