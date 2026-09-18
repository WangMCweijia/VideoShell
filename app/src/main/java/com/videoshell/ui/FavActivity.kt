package com.videoshell.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.videoshell.R
import com.videoshell.data.Library
import com.videoshell.data.Store
import com.videoshell.data.model.VideoItem
import com.videoshell.databinding.ActivityFavBinding
import com.videoshell.ui.adapter.VideoAdapter
import com.videoshell.util.toast
import kotlinx.coroutines.launch

/** 我的收藏：封面网格，点击回详情页 */
class FavActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFavBinding
    private val adapter = VideoAdapter { open(it) }
    private var favs: List<com.videoshell.data.FavEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFavBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rv.layoutManager = GridLayoutManager(this, 3)
        binding.rv.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        favs = Library.favorites(this)
        adapter.submit(
            favs.map { VideoItem(id = it.vid, name = it.name, pic = it.pic, remarks = it.remarks) },
            false
        )
        binding.tvEmpty.visibility = if (favs.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun open(item: VideoItem) {
        val e = favs.firstOrNull { it.vid == item.id } ?: return
        val site = Store.find(this, e.siteKey)
        if (site == null) {
            toast(R.string.hist_site_gone)
            return
        }
        startActivity(DetailActivity.intent(this, e.siteKey, item))
    }
}
