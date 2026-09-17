package com.videoshell.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.videoshell.data.model.SiteConfig
import com.videoshell.databinding.ItemSiteBinding

class SiteListAdapter(
    private val onClick: (SiteConfig) -> Unit,
    private val onDelete: (SiteConfig) -> Unit
) : RecyclerView.Adapter<SiteListAdapter.VH>() {

    private val items = ArrayList<SiteConfig>()

    fun submit(list: List<SiteConfig>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSiteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemSiteBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(s: SiteConfig) {
            b.tvName.text = s.name.ifBlank { s.baseUrl }
            b.tvUrl.text = s.baseUrl
            b.tvMode.text = listOf(s.note, s.apiMode).filter { it.isNotBlank() }.joinToString(" · ")
            b.root.setOnClickListener { onClick(s) }
            b.btnDelete.setOnClickListener { onDelete(s) }
        }
    }
}
