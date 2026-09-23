package com.videoshell.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.videoshell.databinding.ItemSearchRailBinding

/**
 * 搜索结果页左侧的站源栏（v1.0.50）。
 *
 * 第 **0 项约定为「聚合」**（= 全部站源），之后依次是已显示的站点 —— 这个约定由
 * [com.videoshell.ui.SearchActivity] 建立并维护（它那边的 `railSites[0] == null`），
 * 适配器只按顺序显示，不做任何身份判断。
 *
 * ## 为什么计数单独一个 [setCount] 而不是整表重提
 *
 * 每搜完一个站就会得到一个条数，聚合搜索下这是**逐个到达**的（10 个站 10 次）。
 * 每次都 `notifyDataSetChanged` 会让整栏（10 行）反复重绑，用户正盯着它看的时候会闪。
 * 按位置 `notifyItemChanged` 只重绑那一行。
 *
 * ## v1.0.67：左栏会**随结果长出来**，末尾还可能多一格
 *
 * 聚合搜索下左栏不再一次铺满（见 `data/site/RailGrowth.kt` 的文件头），
 * 于是这里多了两件事：
 *
 *  - **[insert]**：又有一个站出结果了 ⇒ 在算好的位置插一格。用 `notifyItemInserted`
 *    而不是整表重提，插入动画本身就是"又长出来一个"的反馈，其余格子也不会重绑。
 *    ⚠️ 插入位置在**当前选中格之前**时必须把 [selected] 一起右移 —— 下标是"位置"，
 *    格子的身份没变，不同步的话高亮会跳到隔壁那个站上（并且 [select] 也会错位）。
 *
 *  - **[setFooter]**：末尾那一格「显示全部」。它**不是站源**，所以不进 [names]，
 *    而是靠 [getItemCount] 多加一格表达 —— 这样所有"按名字下标"的操作（[select] /
 *    [setCount]）的语义一字不改，只有点击要分个岔。
 */
class SearchRailAdapter(
    private val onClick: (index: Int) -> Unit
) : RecyclerView.Adapter<SearchRailAdapter.VH>() {

    private val names = ArrayList<String>()
    private val counts = ArrayList<String?>()
    private var selected = 0

    /** 末尾那一格「显示全部」（null = 不显示）。见类注释 */
    private var footer: String? = null

    /** 尾巴格被点了（展开全部站源）。由 [com.videoshell.ui.SearchActivity] 挂上 */
    var onFooterClick: (() -> Unit)? = null

    fun submit(newNames: List<String>, newCounts: List<String?>, select: Int) {
        names.clear(); names.addAll(newNames)
        counts.clear()
        repeat(newNames.size) { counts += newCounts.getOrNull(it) }
        selected = select.coerceIn(0, (newNames.size - 1).coerceAtLeast(0))
        notifyDataSetChanged()
    }

    /**
     * 在 [at] 处插一格。
     *
     * [at] 与 [names] 的下标**同单位**（含 0 号位的「聚合」）。越界夹到合法范围，
     * 而不是抛异常：调用方算错了位置最多是"插得不好看"，不该让整页崩掉。
     */
    fun insert(at: Int, name: String, count: String?) {
        val pos = at.coerceIn(0, names.size)
        names.add(pos, name)
        counts.add(pos, count)
        // 插在选中格之前（或就是它那一格）⇒ 选中格整体右移一位。
        // 不同步的话高亮会跳到隔壁站上 —— 而"我点的是这个站，亮的却是那个"没人能解释。
        if (pos <= selected) selected++
        notifyItemInserted(pos)
    }

    /** 切换选中项：只重绑新旧两行 */
    fun select(index: Int) {
        if (index == selected || index !in names.indices) return
        val before = selected
        selected = index
        notifyItemChanged(before)
        notifyItemChanged(index)
    }

    /** 更新某一项的计数（值没变就不刷，避免流式搜索下每个站到达时都重绑一遍） */
    fun setCount(index: Int, text: String?) {
        if (index !in counts.indices) return
        if (counts[index] == text) return
        counts[index] = text
        notifyItemChanged(index)
    }

    /**
     * 末尾那格「显示全部」的文案（null = 收起这一格）。
     *
     * 只在"有没有"这件事变了的时候才动数据集 —— 文案变了（比如"还有 7 个站"里数字变了）
     * 走 `notifyItemChanged`，不然每次到达都会插一格又删一格。
     */
    fun setFooter(text: String?) {
        val had = footer != null
        val has = text != null
        footer = text
        when {
            !had && has -> notifyItemInserted(names.size)
            had && !has -> notifyItemRemoved(names.size)
            had && has -> notifyItemChanged(names.size)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSearchRailBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = names.size + if (footer != null) 1 else 0

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(position)

    inner class VH(private val b: ItemSearchRailBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(index: Int) {
            // 末尾那一格不是站源：没有计数、也不参与选中态（它随时会消失，选中它毫无意义）
            val isFooter = index >= names.size
            b.tvRailName.text = if (isFooter) footer.orEmpty() else names[index]
            val c = if (isFooter) null else counts.getOrNull(index)
            b.tvRailCount.text = c.orEmpty()
            b.tvRailCount.visibility = if (c.isNullOrBlank()) View.GONE else View.VISIBLE
            // selected 设在**根布局**上，两个子 TextView 靠 duplicateParentState 跟着变
            b.root.isSelected = !isFooter && index == selected
            b.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (pos < names.size) onClick(pos) else onFooterClick?.invoke()
            }
        }
    }
}
