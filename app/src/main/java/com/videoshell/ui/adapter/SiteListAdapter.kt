package com.videoshell.ui.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.videoshell.R
import com.videoshell.data.model.SiteConfig
import com.videoshell.databinding.ItemSiteBinding

/** 站点体检状态（FN-6）：无 → 检测中 → 可访问 / 失败 */
enum class SiteHealth { UNKNOWN, CHECKING, OK, BAD }

class SiteListAdapter(
    private val onClick: (SiteConfig) -> Unit,
    private val onDelete: (SiteConfig) -> Unit,
    private val onLongClick: (SiteConfig) -> Unit = {},
    private val onSetDefault: (SiteConfig) -> Unit = {}
) : RecyclerView.Adapter<SiteListAdapter.VH>() {

    private val items = ArrayList<SiteConfig>()

    /** 当前默认站源 key —— bind 时用来给星标上色 */
    var defaultKey: String = ""

    /**
     * 从手柄起拖的回调。
     *
     * 不给 RecyclerView 直接依赖 ItemTouchHelper：适配器只管「我这一行的手柄被按下了」，
     * 由持有 ItemTouchHelper 的 Activity 决定起拖。这样排序落盘也在 Activity 手里。
     */
    var onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null

    /** 体检结果，key = SiteConfig.key */
    private val health = HashMap<String, SiteHealth>()

    fun submit(list: List<SiteConfig>) {
        items.clear()
        items.addAll(list)
        // 站点列表变了：上一轮的"检测中"标记就该作废，免得永远转圈
        for (s in list) if (health[s.key] == SiteHealth.CHECKING) health[s.key] = SiteHealth.UNKNOWN
        notifyDataSetChanged()
    }

    /** 当前顺序（拖拽后由 Activity 落盘） */
    fun currentItems(): List<SiteConfig> = items.toList()

    /** 拖拽换位：同步内存顺序并发出移动动画 */
    fun moveItem(from: Int, to: Int) {
        if (from == to) return
        if (from !in items.indices || to !in items.indices) return
        val moved = items.removeAt(from)
        items.add(to, moved)
        notifyItemMoved(from, to)
    }

    fun markChecking(keys: Collection<String>) {
        for (k in keys) health[k] = SiteHealth.CHECKING
        notifyDataSetChanged()
    }

    fun applyHealth(result: Map<String, Boolean>) {
        for ((k, ok) in result) health[k] = if (ok) SiteHealth.OK else SiteHealth.BAD
        notifyDataSetChanged()
    }

    fun clearHealth() {
        health.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSiteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemSiteBinding) : RecyclerView.ViewHolder(b.root) {

        @SuppressLint("ClickableViewAccessibility")
        fun bind(s: SiteConfig) {
            // 站名常带未解码的 HTML 实体（如「厂&#8211;长」——采集自网页 title），
            // 在展示侧解码而不是存储侧：老数据不用迁移，新数据错了也还能救。
            b.tvName.text = org.jsoup.parser.Parser.unescapeEntities(s.name, false)
                .ifBlank { s.baseUrl }
            // 备用地址（域名轮换，v1.0.67）：有就在地址后面点出来。
            // 没有这一句的话，用户根本看不出自己填的第二、第三个地址有没有被存进去 ——
            // 而"填了没生效"正是这个功能最容易让人怀疑的地方。
            val mirrors = s.mirrorList()
            b.tvUrl.text =
                if (mirrors.isEmpty()) s.baseUrl
                else s.baseUrl + "  ·  另 " + mirrors.size + " 个地址"
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

            // 拖拽手柄：在 ACTION_DOWN 就起拖（不等长按），所以不会和整行的长按改名抢
            b.btnDrag.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag?.invoke(this)
                false
            }

            bindHealth(s)
        }

        private fun bindHealth(s: SiteConfig) {
            val h = health[s.key]
            if (h == null || h == SiteHealth.UNKNOWN) {
                b.tvHealth.visibility = View.GONE
                return
            }
            val ctx = b.root.context
            b.tvHealth.visibility = View.VISIBLE
            b.tvHealth.setBackgroundResource(R.drawable.bg_badge)
            when (h) {
                SiteHealth.CHECKING -> {
                    b.tvHealth.text = ctx.getString(R.string.site_health_checking)
                    b.tvHealth.setTextColor(ContextCompat.getColor(ctx, R.color.text_hint))
                }
                SiteHealth.OK -> {
                    b.tvHealth.text = "✓ " + ctx.getString(R.string.site_health_ok)
                    b.tvHealth.setTextColor(ContextCompat.getColor(ctx, R.color.ok))
                }
                SiteHealth.BAD -> {
                    b.tvHealth.text = "✕ " + ctx.getString(R.string.site_health_bad)
                    b.tvHealth.setTextColor(ContextCompat.getColor(ctx, R.color.bad))
                }
                else -> Unit
            }
        }
    }
}
