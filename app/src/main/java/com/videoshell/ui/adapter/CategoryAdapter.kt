package com.videoshell.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.videoshell.R
import com.videoshell.data.model.Category
import com.videoshell.databinding.ItemCategoryBinding

class CategoryAdapter(
    private val onClick: (Int, Category) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    private val items = ArrayList<Category>()
    private var selected = 0

    fun submit(list: List<Category>) {
        items.clear()
        items.addAll(list)
        selected = 0
        notifyDataSetChanged()
    }

    fun select(index: Int) {
        if (index < 0 || index >= items.size || index == selected) return
        val old = selected
        selected = index
        notifyItemChanged(old)
        notifyItemChanged(selected)
    }

    fun currentIndex(): Int = selected

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(items[position], position == selected)

    inner class VH(private val b: ItemCategoryBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(c: Category, isSelected: Boolean) {
            b.tvName.text = c.name
            b.root.isSelected = isSelected
            b.tvName.setTextColor(
                ContextCompat.getColor(
                    b.root.context,
                    if (isSelected) R.color.white else R.color.text_primary
                )
            )
            b.root.setOnClickListener { onClick(bindingAdapterPosition, c) }
        }
    }
}
