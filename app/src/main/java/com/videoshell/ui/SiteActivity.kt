package com.videoshell.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.videoshell.R
import com.videoshell.data.Store
import com.videoshell.data.model.Category
import com.videoshell.data.model.SiteConfig
import com.videoshell.data.model.VideoItem
import com.videoshell.data.net.NetLog
import com.videoshell.data.site.AdapterFactory
import com.videoshell.data.site.SiteAdapter
import com.videoshell.data.site.SiteDoctor
import com.videoshell.databinding.ActivitySiteBinding
import com.videoshell.ui.adapter.CategoryAdapter
import com.videoshell.ui.adapter.VideoAdapter
import com.videoshell.util.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 站点浏览页：分类浏览 + 搜索 */
class SiteActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_KEY = "site_key"
        private const val MODE_CATEGORY = 0
        private const val MODE_SEARCH = 1

        fun intent(context: Context, siteKey: String): Intent =
            Intent(context, SiteActivity::class.java).putExtra(EXTRA_KEY, siteKey)
    }

    private lateinit var binding: ActivitySiteBinding
    private lateinit var site: SiteConfig
    private var adapter: SiteAdapter? = null

    private val catAdapter = CategoryAdapter { index, c -> onCategory(index, c) }
    private val videoAdapter = VideoAdapter { openDetail(it) }

    private var page = 1
    private var loading = false
    private var mode = MODE_CATEGORY
    private var currentType = ""
    private var keyword = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySiteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        val s = Store.find(this, key)
        if (s == null) {
            toast("站点不存在")
            finish()
            return
        }
        site = s
        adapter = AdapterFactory.create(s)

        binding.tvTitle.text = s.name.ifBlank { s.baseUrl }
        binding.btnBack.setOnClickListener { finish() }

        val span = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 5 else 3
        binding.rvCats.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        binding.rvCats.adapter = catAdapter
        binding.rvVideos.layoutManager = GridLayoutManager(this, span)
        binding.rvVideos.adapter = videoAdapter
        binding.rvVideos.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loading) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= videoAdapter.itemCount - 4) {
                    load(page + 1, true)
                }
            }
        })

        binding.btnSearchToggle.setOnClickListener {
            val show = binding.searchRow.visibility != View.VISIBLE
            binding.searchRow.visibility = if (show) View.VISIBLE else View.GONE
            if (!show) {
                // 收起搜索框时回到分类模式
                if (mode == MODE_SEARCH) {
                    mode = MODE_CATEGORY
                    catAdapter.select(0)
                    currentType = ""
                    reload()
                }
            }
        }
        binding.btnSearch.setOnClickListener { doSearch() }
        binding.inputSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }
        binding.btnCatRetry.setOnClickListener { loadCategories() }
        binding.btnDoctor.setOnClickListener { runDoctor() }

        loadCategories()
    }

    private fun loadCategories() {
        binding.pb.visibility = View.VISIBLE
        showState(null)
        binding.tvCatHint.text = getString(R.string.cat_loading)
        binding.catHintRow.visibility = View.VISIBLE
        lifecycleScope.launch {
            val a = adapter ?: return@launch
            var list: List<Category> = emptyList()
            var why = ""
            // 手机网络下"每个新域名的第一次请求"很容易抖一下（DNS 慢 / 首次握手超时）。
            // 旧实现一抖整条分类栏就消失，只能靠用户手动点"重新加载"。这里自动补两轮
            // （退避 1.5s / 3s），抖动就自愈了 —— 用户不该为这种事点第二次。
            for (round in 0 until 3) {
                if (round > 0) {
                    binding.tvCatHint.text = getString(R.string.cat_retrying, round)
                    delay(if (round == 1) 1_500L else 3_000L)
                }
                val res = runCatching { a.categories() }
                list = res.getOrElse { emptyList() }
                if (list.isNotEmpty()) break
                // 优先报真实异常（比"首页请求失败"这种笼统描述有用得多），否则用适配器给的诊断
                why = res.exceptionOrNull()
                    ?.let { it.javaClass.simpleName + ": " + it.message }
                    ?.takeIf { it.isNotBlank() }
                    ?: a.lastDiag.ifBlank { NetLog.lastFailure() }
            }
            binding.pb.visibility = View.GONE
            val all = listOf(Category("", getString(R.string.cat_latest))) + list
            catAdapter.submit(all)
            // 解析不到分类时不禁用浏览 —— 至少"最新"还能用，同时给出重试入口与原因
            if (list.isEmpty()) {
                binding.tvCatHint.text =
                    if (why.isBlank()) getString(R.string.cat_only_home)
                    else getString(R.string.cat_only_home) + "（" + why + "）"
                binding.catHintRow.visibility = View.VISIBLE
            } else {
                binding.catHintRow.visibility = View.GONE
            }
            onCategory(0, all[0])
        }
    }

    // ------------------------------------------------------------------ 站点自检

    private fun runDoctor() {
        if (adapter == null) return
        toast(getString(R.string.site_doctor_running))
        binding.pb.visibility = View.VISIBLE
        lifecycleScope.launch {
            val report = runCatching { SiteDoctor.run(site) }
                .getOrElse { "自检本身出错：${it.javaClass.simpleName}: ${it.message}" }
            binding.pb.visibility = View.GONE
            showReport(report)
        }
    }

    private fun showReport(text: String) {
        val tv = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@SiteActivity, R.color.text_primary))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            this.text = text
        }
        val sv = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle(R.string.site_doctor_title)
            .setView(sv)
            .setPositiveButton(R.string.site_doctor_copy) { _, _ ->
                runCatching {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("videoshell-doctor", text))
                    toast(getString(R.string.site_doctor_copied))
                }
            }
            .setNegativeButton(R.string.site_doctor_close, null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun onCategory(index: Int, c: Category) {
        catAdapter.select(index)
        binding.rvCats.smoothScrollToPosition(index)
        mode = MODE_CATEGORY
        currentType = c.id
        reload()
    }

    private fun doSearch() {
        val kw = binding.inputSearch.text.toString().trim()
        if (kw.isEmpty()) {
            toast("请输入关键词")
            return
        }
        keyword = kw
        mode = MODE_SEARCH
        reload()
    }

    private fun reload() {
        page = 1
        videoAdapter.clear()
        showState(null)
        binding.pb.visibility = View.VISIBLE
        load(1, false)
    }

    private fun load(p: Int, append: Boolean) {
        val a = adapter ?: return
        if (loading) return
        loading = true

        lifecycleScope.launch {
            val res = runCatching {
                if (mode == MODE_SEARCH) a.search(keyword, p) else a.browse(currentType, p)
            }
            val items = res.getOrElse { emptyList() }

            loading = false
            binding.pb.visibility = View.GONE

            if (items.isEmpty()) {
                if (!append) {
                    val why = res.exceptionOrNull()?.let { it.javaClass.simpleName + ": " + it.message }
                    val net = NetLog.lastFailure()
                    showState(
                        when {
                            !why.isNullOrBlank() -> getString(R.string.err_prefix, why)
                            net.isNotBlank() -> "暂无数据（$net）"
                            mode == MODE_SEARCH -> getString(R.string.no_result)
                            else -> "暂无数据，可换个分类试试"
                        }
                    )
                } else {
                    toast(getString(R.string.no_more))
                }
                return@launch
            }
            videoAdapter.submit(items, append)
            page = p
            showState(null)
        }
    }

    private fun showState(msg: String?) {
        binding.tvState.text = msg.orEmpty()
        binding.tvState.visibility = if (msg == null) View.GONE else View.VISIBLE
    }

    private fun openDetail(item: VideoItem) {
        startActivity(DetailActivity.intent(this, site.key, item))
    }
}
