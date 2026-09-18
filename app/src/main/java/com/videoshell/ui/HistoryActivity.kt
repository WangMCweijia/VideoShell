package com.videoshell.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.videoshell.R
import com.videoshell.data.Library
import com.videoshell.data.Store
import com.videoshell.data.model.VideoItem
import com.videoshell.databinding.ActivityHistoryBinding
import com.videoshell.ui.adapter.HistAdapter
import com.videoshell.util.toast
import kotlinx.coroutines.launch

/** 播放历史：点击回详情页（站点还在的话），长按删除单条，右上角清空 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val adapter = HistAdapter(
        onClick = { open(it) },
        onLongClick = { remove(it) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rv.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        binding.rv.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.btnClear.setOnClickListener {
            if (Library.history(this).isEmpty()) return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle(R.string.row_history)
                .setMessage(R.string.hist_clear_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok) { _, _ ->
                    Library.clearHistory(this)
                    refresh()
                    toast(R.string.hist_cleared)
                }
                .show()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val list = Library.history(this)
        adapter.submit(list)
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.btnClear.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun open(e: com.videoshell.data.HistEntry) {
        val site = Store.find(this, e.siteKey)
        if (site == null) {
            toast(R.string.hist_site_gone)
            return
        }
        startActivity(
            DetailActivity.intent(this, e.siteKey, VideoItem(id = e.vid, name = e.name, pic = e.pic))
        )
    }

    private fun remove(e: com.videoshell.data.HistEntry) {
        AlertDialog.Builder(this)
            .setTitle(e.name)
            .setMessage(R.string.hist_remove_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                Library.removeHistory(this, e.siteKey, e.name)
                refresh()
            }
            .show()
    }
}
