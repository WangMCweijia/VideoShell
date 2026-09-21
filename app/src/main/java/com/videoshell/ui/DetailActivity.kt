package com.videoshell.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.videoshell.R
import com.videoshell.data.Library
import com.videoshell.data.Store
import com.videoshell.data.model.Episode
import com.videoshell.data.model.MediaSource
import com.videoshell.data.model.VideoDetail
import com.videoshell.data.model.VideoItem
import com.videoshell.data.net.NetLog
import com.videoshell.data.site.AdapterFactory
import com.videoshell.data.site.SiteAdapter
import com.videoshell.databinding.ActivityDetailBinding
import com.videoshell.player.PlayQueue
import com.videoshell.player.PlayerActivity
import com.videoshell.player.SniffActivity
import com.videoshell.ui.adapter.CategoryAdapter
import com.videoshell.ui.adapter.EpisodeAdapter
import com.videoshell.util.toast
import kotlinx.coroutines.launch

/** 影片详情 + 选集 */
class DetailActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_KEY = "site_key"
        private const val EXTRA_ID = "video_id"
        private const val EXTRA_NAME = "video_name"
        private const val EXTRA_PIC = "video_pic"
        private const val EXTRA_REMARKS = "video_remarks"

        fun intent(context: Context, siteKey: String, item: VideoItem): Intent =
            Intent(context, DetailActivity::class.java).apply {
                putExtra(EXTRA_KEY, siteKey)
                putExtra(EXTRA_ID, item.id)
                putExtra(EXTRA_NAME, item.name)
                putExtra(EXTRA_PIC, item.pic)
                putExtra(EXTRA_REMARKS, item.remarks)
            }
    }

    private lateinit var binding: ActivityDetailBinding
    private lateinit var siteKey: String
    private var videoId: String = ""
    private var adapter: SiteAdapter? = null
    private var detail: VideoDetail? = null
    private var groupIndex = 0

    /** 收藏/历史要用的当前影片信息（详情解析出来后会被真名/真封面覆盖） */
    private var curName = ""
    private var curPic = ""
    private var curRemarks = ""

    /** 校准页回来：站点配方可能变了（也可能顺带切成了 HTML 模式）⇒ 丢旧 Adapter，重新解析 */
    private val calibLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { reload() }

    private val groupAdapter = CategoryAdapter { index, _ -> switchGroup(index) }
    private val episodeAdapter = EpisodeAdapter { index, ep -> playEpisode(index, ep) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        siteKey = intent.getStringExtra(EXTRA_KEY).orEmpty()
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        videoId = id
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val pic = intent.getStringExtra(EXTRA_PIC).orEmpty()
        val remarks = intent.getStringExtra(EXTRA_REMARKS).orEmpty()
        curName = name
        curPic = pic
        curRemarks = remarks

        val site = Store.find(this, siteKey)
        if (site == null) {
            toast("站点不存在")
            finish()
            return
        }
        adapter = AdapterFactory.create(site)

        binding.tvTitle.text = name
        binding.tvName.text = name
        if (remarks.isNotBlank()) {
            binding.tvRemarks.text = remarks
            binding.tvRemarks.visibility = View.VISIBLE
        }
        if (pic.isNotBlank()) {
            binding.ivPoster.load(pic) {
                crossfade(true)
                placeholder(R.drawable.bg_poster)
                error(R.drawable.bg_poster)
            }
        }
        binding.btnBack.setOnClickListener { finish() }
        binding.btnFav.setOnClickListener { toggleFav() }
        refreshFavIcon()
        binding.btnCalib.setOnClickListener {
            calibLauncher.launch(CalibrateActivity.intent(this, siteKey))
        }

        binding.rvGroups.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        binding.rvGroups.adapter = groupAdapter
        // 列数不在这里定：submitEpisodes() 会按整列名字算出合适的列数再重设
        // layoutManager（v1.0.49，综艺长名 5→3 列）。这里只挂 adapter。
        binding.rvEpisodes.adapter = episodeAdapter

        loadDetail(id)
    }

    /**
     * 丢旧 Adapter 重新走一遍详情。
     *
     * 必须**换一个新实例**：配方是 `HtmlAdapter.init` 时读进字段的，老实例手上还是旧模板。
     * （v1.0.11 那条「每个页面各自 new 一个 Adapter，状态活在实例里」的教训，这里正向用一次。）
     */
    private fun reload() {
        val site = Store.find(this, siteKey) ?: return
        adapter = AdapterFactory.create(site)
        detail = null
        groupIndex = 0
        loadDetail(videoId)
    }

    private fun loadDetail(id: String) {
        binding.pb.visibility = View.VISIBLE
        showState(null)
        lifecycleScope.launch {
            val a = adapter ?: return@launch
            val res = runCatching { a.detail(id) }
            var d = res.getOrNull()

            // ⑥ 预渲染兜底（v1.0.25）：首屏 HTML 里没有分集（JS 渲染站）时，
            // 用 WebView 真跑一遍详情页，再把渲染后的 DOM 交回适配器解析。
            if (d == null) {
                val url = a.detailUrlFor(id)
                if (!url.isNullOrBlank()) {
                    val html = WebRender.html(this@DetailActivity, url)
                    if (!html.isNullOrBlank()) d = a.parseDetailFromHtml(html)
                }
            }

            binding.pb.visibility = View.GONE
            if (d == null) {
                val why = res.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                    ?: NetLog.lastFailure()
                showState(
                    if (why.isBlank()) "获取影片详情失败（可返回列表换一部，或该站需要嗅探播放）"
                    else "获取影片详情失败：$why"
                )
                return@launch
            }
            detail = d
            render(d)
        }
    }

    private fun render(d: VideoDetail) {
        if (d.name.isNotBlank()) {
            binding.tvName.text = d.name
            binding.tvTitle.text = d.name
            curName = d.name
        }
        if (d.pic.isNotBlank()) {
            binding.ivPoster.load(d.pic) {
                crossfade(true)
                placeholder(R.drawable.bg_poster)
                error(R.drawable.bg_poster)
            }
            curPic = d.pic
        }
        if (d.remarks.isNotBlank()) {
            binding.tvRemarks.text = d.remarks
            binding.tvRemarks.visibility = View.VISIBLE
            curRemarks = d.remarks
        }
        refreshFavIcon()

        val meta = listOf(
            if (d.typeName.isNotBlank()) "类型：${d.typeName}" else "",
            if (d.year.isNotBlank()) "年份：${d.year}" else "",
            if (d.area.isNotBlank()) "地区：${d.area}" else "",
            if (d.director.isNotBlank()) "导演：${d.director}" else "",
            if (d.actor.isNotBlank()) "主演：${d.actor}" else ""
        ).filter { it.isNotBlank() }.joinToString("\n")
        binding.tvMeta.text = meta
        binding.tvMeta.visibility = if (meta.isBlank()) View.GONE else View.VISIBLE

        binding.tvSummary.text = d.summary.ifBlank { "暂无简介" }
        binding.tvSummaryTitle.visibility = View.VISIBLE
        binding.tvSummary.visibility = View.VISIBLE

        if (d.groups.isEmpty()) {
            binding.tvSourceTitle.visibility = View.GONE
            binding.rvGroups.visibility = View.GONE
            // 整行一起收（标题 + 切序按钮），否则会剩一个孤零零的「正序」按钮
            binding.rowEpisodeHeader.visibility = View.GONE
            binding.rvEpisodes.visibility = View.GONE
            showState("该影片未解析到播放列表，可尝试网页嗅探")
            return
        }

        showState(null)
        binding.tvSourceTitle.visibility = View.VISIBLE
        binding.rvGroups.visibility = View.VISIBLE
        groupAdapter.submit(d.groups.map { com.videoshell.data.model.Category(it.name, it.name) })
        switchGroup(0)
    }

    private fun switchGroup(index: Int) {
        val d = detail ?: return
        val g = d.groups.getOrNull(index) ?: return
        groupIndex = index
        groupAdapter.select(index)
        binding.tvEpisodeTitle.text = "选集 · 共 ${g.episodes.size} 集"
        binding.rowEpisodeHeader.visibility = View.VISIBLE
        binding.rvEpisodes.visibility = View.VISIBLE
        showOrderButton()
        submitEpisodes()
    }

    /** 显示当前排序状态（正序/倒序）；状态与播放器的选集面板共用 */
    private fun showOrderButton() {
        binding.tvOrder.text = getString(
            if (Store.episodeDesc(this)) R.string.order_desc else R.string.order_asc
        )
        binding.tvOrder.setOnClickListener {
            val desc = !Store.episodeDesc(this)
            Store.setEpisodeDesc(this, desc)
            showOrderButton()
            submitEpisodes()
            toast(if (desc) R.string.order_hud_desc else R.string.order_hud_asc)
        }
    }

    /**
     * 按当前偏好提交分集列表。回调里的 index 是**组内原始序号**，与显示顺序无关。
     *
     * v1.0.49：列数与「是否两行」由 EpisodeCell.plan 按**整列**名字算出来（综艺那种
     * `20240921 第10期` 的长名会从 5 列降到 3 列，并启用主副两行）。
     * ⚠️ layoutManager 因此必须在这里**每次重设** —— 换线路、换分类都可能改变列数，
     * 只在 onCreate 里设一次是不够的。重设会丢滚动位置，但这里本来就该从头看起。
     */
    private fun submitEpisodes() {
        val g = detail?.groups?.getOrNull(groupIndex) ?: return
        val land =
            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val plan = com.videoshell.util.EpisodeCell.plan(g.episodes, land)
        binding.rvEpisodes.layoutManager = GridLayoutManager(this, plan.cols)
        episodeAdapter.submit(g.episodes, Store.episodeDesc(this), plan.twoLine)
    }

    private fun playEpisode(index: Int, ep: Episode) {
        val d = detail ?: return
        PlayQueue.title = d.name.ifBlank { binding.tvName.text.toString() }
        PlayQueue.siteKey = siteKey
        PlayQueue.vid = d.id.ifBlank { videoId }
        PlayQueue.pic = d.pic.ifBlank { curPic }
        PlayQueue.groups = d.groups
        PlayQueue.groupIndex = groupIndex
        PlayQueue.episodeIndex = index
        episodeAdapter.select(index)

        val a = adapter ?: return
        val label = "${PlayQueue.title} ${ep.name}".trim()

        lifecycleScope.launch {
            binding.pb.visibility = View.VISIBLE
            val r = runCatching { a.resolve(ep) }.getOrNull()
            binding.pb.visibility = View.GONE
            when (r) {
                is MediaSource.Direct -> startActivity(
                    PlayerActivity.intent(this@DetailActivity, r.url, label, r.headers, ep.url)
                )
                is MediaSource.Sniff -> startActivity(
                    SniffActivity.intent(this@DetailActivity, r.pageUrl, label, r.headers)
                )
                is MediaSource.Error -> toast(r.message)
                null -> toast("解析播放地址失败")
            }
        }
    }

    private fun toggleFav() {
        val added = Library.toggleFav(this, siteKey, videoId, curName, curPic, curRemarks)
        refreshFavIcon()
        toast(if (added) R.string.fav_added else R.string.fav_removed)
    }

    private fun refreshFavIcon() {
        val fav = Library.isFav(this, siteKey, videoId)
        binding.btnFav.setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_outline)
        binding.btnFav.setColorFilter(
            androidx.core.content.ContextCompat.getColor(this, R.color.brand)
        )
    }

    private fun showState(msg: String?) {
        binding.tvState.text = msg.orEmpty()
        binding.tvState.visibility = if (msg == null) View.GONE else View.VISIBLE
    }
}
