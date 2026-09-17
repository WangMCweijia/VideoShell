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
            b.tvType.text = c.type
            b.root.setOnClickListener { onClick(c) }
        }
    }
}
