package com.videoshell.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.videoshell.R
import com.videoshell.databinding.ActivityCastBinding
import com.videoshell.databinding.ItemCastDeviceBinding
import com.videoshell.util.toast
import kotlinx.coroutines.launch

/**
 * 投屏设备选择（新增）。发现局域网 DLNA 渲染器，点一个就把当前播放地址推过去。
 */
class CastActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_HAS_HEADERS = "has_headers"

        fun intent(context: Context, url: String, title: String, headers: Map<String, String>): Intent =
            Intent(context, CastActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                // DLNA 渲染器是自己去取流的，我们**没法**把 App 的防盗链请求头（Referer/UA）
                // 捎过去。带头的地址可能投不动 —— 这里只把「有没有头」记下来，界面上如实提示。
                putExtra(EXTRA_HAS_HEADERS, headers.isNotEmpty())
            }
    }

    private lateinit var binding: ActivityCastBinding
    private var url = ""
    private var title = ""
    private var hasHeaders = false
    private val devices = ArrayList<CastManager.Renderer>()
    private val adapter = DeviceAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCastBinding.inflate(layoutInflater)
        setContentView(binding.root)

        url = intent.getStringExtra(EXTRA_URL).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        hasHeaders = intent.getBooleanExtra(EXTRA_HAS_HEADERS, false)

        binding.btnBack.setOnClickListener { finish() }
        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = adapter

        if (url.isBlank()) {
            binding.tvStatus.text = getString(R.string.cast_none)
            return
        }

        binding.tvStatus.text = getString(R.string.cast_searching)
        lifecycleScope.launch {
            val list = CastManager.discover()
            devices.clear()
            devices.addAll(list)
            adapter.notifyDataSetChanged()
            val base = if (list.isEmpty()) getString(R.string.cast_none)
            else getString(R.string.cast_title)
            val hint = if (hasHeaders) "\n${getString(R.string.cast_header_hint)}" else ""
            val head = if (title.isBlank()) "" else "$title\n"
            binding.tvStatus.text = "$head$base$hint"
        }
    }

    private fun castTo(r: CastManager.Renderer) {
        lifecycleScope.launch {
            binding.tvStatus.text = getString(R.string.cast_send, r.name)
            val err = CastManager.play(r, url, title)
            if (err == "ok") {
                toast(getString(R.string.cast_connected, r.name))
                binding.tvStatus.text = getString(R.string.cast_connected, r.name)
                // 必须显式 setResult：调用方（播放页）靠它决定要不要停掉本机播放。
                // 漏了这行会出现「电视在放、手机也在放」—— 本项目在别的返回链上踩过同一个坑。
                setResult(RESULT_OK)
                finish()
            } else {
                toast(getString(R.string.cast_failed, err))
                binding.tvStatus.text = getString(R.string.cast_failed, err)
            }
        }
    }

    private inner class DeviceAdapter : RecyclerView.Adapter<DeviceAdapter.VH>() {
        inner class VH(val b: ItemCastDeviceBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemCount(): Int = devices.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemCastDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = devices[position]
            holder.b.tvName.text = r.name
            holder.b.root.setOnClickListener { castTo(r) }
        }
    }
}
