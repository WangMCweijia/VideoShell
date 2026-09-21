package com.videoshell.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.videoshell.R
import com.videoshell.databinding.ItemSearchSuggestBinding

/**
 * 搜索历史下拉（FN-2）。轻量单 TextView 列表，点击即回填并搜索。
 */
class SearchSuggestAdapter(
    private val onClick: (String) -> Unit,
    private val onLongClick: (String) -> Unit
) : RecyclerView.Adapter<SearchSuggestAdapter.VH>() {

    private val items = ArrayList<String>()

    fun submit(list: List<String>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSearchSuggestBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val kw = items[position]
        holder.b.tvKw.text = kw
        holder.b.root.setOnClickListener { onClick(kw) }
        holder.b.root.setOnLongClickListener {
            onLongClick(kw)
            true
        }
    }

    class VH(val b: ItemSearchSuggestBinding) : RecyclerView.ViewHolder(b.root)
}
