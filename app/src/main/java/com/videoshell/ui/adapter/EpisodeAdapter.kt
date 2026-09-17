package com.videoshell.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.videoshell.R
import com.videoshell.data.model.Episode
import com.videoshell.databinding.ItemEpisodeBinding

class EpisodeAdapter(
    private val onClick: (Int, Episode) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

    private val items = ArrayList<Episode>()
    private var selected = -1

    fun submit(list: List<Episode>) {
        items.clear()
        items.addAll(list)
        selected = -1
        notifyDataSetChanged()
    }

    fun select(index: Int) {
        val old = selected
        selected = index
        if (old in items.indices) notifyItemChanged(old)
        if (selected in items.indices) notifyItemChanged(selected)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(items[position], position == selected)

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
            b.root.setOnClickListener { onClick(bindingAdapterPosition, ep) }
        }
    }
}
