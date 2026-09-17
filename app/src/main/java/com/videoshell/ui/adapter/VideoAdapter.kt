package com.videoshell.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.videoshell.R
import com.videoshell.data.model.VideoItem
import com.videoshell.databinding.ItemVideoBinding

class VideoAdapter(
    private val onClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    private val items = ArrayList<VideoItem>()

    fun submit(list: List<VideoItem>, append: Boolean) {
        if (!append) items.clear()
        val start = items.size
        items.addAll(list)
        if (append) notifyItemRangeInserted(start, list.size) else notifyDataSetChanged()
    }

    fun size(): Int = items.size

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemVideoBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(v: VideoItem) {
            b.tvName.text = v.name

            val sub = listOf(v.typeName, v.year, v.area).filter { it.isNotBlank() }.joinToString(" · ")
            b.tvSub.text = sub
            b.tvSub.visibility = if (sub.isBlank()) View.GONE else View.VISIBLE

            b.tvRemarks.text = v.remarks
            b.tvRemarks.visibility = if (v.remarks.isBlank()) View.GONE else View.VISIBLE

            b.tvScore.text = v.score
            b.tvScore.visibility = if (v.score.isBlank()) View.GONE else View.VISIBLE

            if (v.pic.isNotBlank()) {
                b.ivPic.load(v.pic) {
                    crossfade(true)
                    placeholder(R.drawable.bg_poster)
                    error(R.drawable.bg_poster)
                }
            } else {
                b.ivPic.setImageDrawable(null)
            }

            b.root.setOnClickListener { onClick(v) }
        }
    }
}
