package com.videoshell.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.videoshell.databinding.ItemCandidateBinding
import com.videoshell.player.SniffCandidate

class CandidateAdapter(
    private val onClick: (SniffCandidate) -> Unit
) : RecyclerView.Adapter<CandidateAdapter.VH>() {

    private val items = ArrayList<SniffCandidate>()

    fun submit(list: List<SniffCandidate>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemCandidateBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemCandidateBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(c: SniffCandidate) {
            b.tvName.text = c.url
            // 徽标写清"这个候选是什么"：类型 + 正片/疑似广告 + 时长或分片数，
            // 让用户点之前就能看出该选哪个，而不是靠猜文件名。
            b.tvType.text = c.display()
            b.root.setOnClickListener { onClick(c) }
        }
    }
}
