package com.videoshell.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.videoshell.R
import com.videoshell.data.model.SiteConfig
import com.videoshell.databinding.ItemSiteBinding

class SiteListAdapter(
    private val onClick: (SiteConfig) -> Unit,
    private val onDelete: (SiteConfig) -> Unit,
    private val onLongClick: (SiteConfig) -> Unit = {},
    private val onSetDefault: (SiteConfig) -> Unit = {}
) : RecyclerView.Adapter<SiteListAdapter.VH>() {

    private val items = ArrayList<SiteConfig>()

    /** 当前默认站源 key —— bind 时用来给星标上色 */
    var defaultKey: String = ""

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
            b.root.setOnLongClickListener { onLongClick(s); true }
            b.btnDelete.setOnClickListener { onDelete(s) }
            // 星标 = 默认站源：默认的那个实心亮色，其余描边灰
            val isDefault = s.key == defaultKey
            b.btnStar.setImageResource(
                if (isDefault) R.drawable.ic_star else R.drawable.ic_star_outline
            )
            b.btnStar.setColorFilter(
                ContextCompat.getColor(
                    b.root.context,
                    if (isDefault) R.color.brand else R.color.text_hint
                )
            )
            b.btnStar.setOnClickListener { onSetDefault(s) }
        }
    }
}
