package com.videoshell.ui

// SiteBrowser 的**站点自检与配方导出/导入**（拆出来的第三块）。
//
// 自检报告是**唯一**会摊开「这份配方由哪些模板组成」的地方，所以配方菜单挂在它上面
// （v1.0.54 加导出/导入时就是按这个理由放的）—— 用户在它的语境内才知道自己在搬什么。
// 拆法与约束同上（纯搬运 + internal 扩展函数）。

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
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
import com.videoshell.data.ListCache
import com.videoshell.data.Store
import com.videoshell.data.model.Category
import com.videoshell.data.model.SiteConfig
import com.videoshell.data.model.VideoItem
import com.videoshell.data.net.Http
import com.videoshell.data.net.NetLog
import com.videoshell.data.site.AdapterFactory
import com.videoshell.data.site.AggSearch
import com.videoshell.data.model.VideoRow
import com.videoshell.data.site.CryptFamily
import com.videoshell.data.site.RecipeStore
import com.videoshell.data.site.RecipeTransfer
import com.videoshell.data.site.SearchEngine
import com.videoshell.data.site.SearchScope
import com.videoshell.data.site.SiteAdapter
import com.videoshell.data.site.SiteDoctor
import com.videoshell.databinding.ViewSiteBrowserBinding
import com.videoshell.player.SniffActivity
import com.videoshell.ui.adapter.CategoryAdapter
import com.videoshell.ui.adapter.SearchSuggestAdapter
import com.videoshell.ui.adapter.VideoAdapter
import com.videoshell.util.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ 站点自检 / 重学

internal fun SiteBrowser.runDoctor() {
    val s = site ?: return
    act.toast(act.getString(R.string.site_doctor_running))
    b.pb.visibility = View.VISIBLE
    act.lifecycleScope.launch {
        val report = runCatching { SiteDoctor.run(s) }
            .getOrElse { "自检本身出错：${it.javaClass.simpleName}: ${it.message}" }
        b.pb.visibility = View.GONE
        showReport(report)
    }
}

internal fun SiteBrowser.showReport(text: String) {
    val tv = TextView(act).apply {
        setTextIsSelectable(true)
        typeface = Typeface.MONOSPACE
        textSize = 11f
        setTextColor(ContextCompat.getColor(act, R.color.text_primary))
        setPadding(dp(16), dp(12), dp(16), dp(12))
        this.text = text
    }
    val sv = ScrollView(act).apply { addView(tv) }
    AlertDialog.Builder(act)
        .setTitle(R.string.site_doctor_title)
        .setView(sv)
        .setPositiveButton(R.string.site_doctor_copy) { _, _ ->
            runCatching {
                val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("videoshell-doctor", text))
                act.toast(act.getString(R.string.site_doctor_copied))
            }
        }
        .setNegativeButton(R.string.site_doctor_close, null)
        // 原来这一格直接是「重学本站」。现在它变成「配方…」二级菜单 ——
        // 导出 / 导入 / 重学三件事**同一族**（都在动这份配方），摆在一起才看得出关系；
        // 而"重学"是破坏性操作，放进二级菜单反而更安全（少一次误点）。
        .setNeutralButton(R.string.site_recipe_io) { _, _ -> showRecipeMenu() }
        .show()
}

/**
 * 配方菜单（自检报告的第三格）。
 *
 * 出口摆在自检报告里不是随手选的：自检报告是**唯一**会摊开"这份配方由哪些模板组成"
 * 的地方，导出/导入在它的语境内，用户才知道自己在搬什么。
 */
internal fun SiteBrowser.showRecipeMenu() {
    val items = arrayOf(
        act.getString(R.string.site_recipe_export),
        act.getString(R.string.site_recipe_import),
        act.getString(R.string.site_recipe_reset)
    )
    AlertDialog.Builder(act)
        .setTitle(R.string.site_recipe_io_title)
        .setItems(items) { _, which ->
            when (which) {
                0 -> exportRecipe()
                1 -> importRecipe()
                else -> resetRecipe()
            }
        }
        .show()
}

/** 导出：把本站配方变成一段可粘贴的文本（走剪贴板，另一台设备粘进导入即可） */
internal fun SiteBrowser.exportRecipe() {
    val s = site ?: return
    val text = RecipeTransfer.export(s.baseUrl)
    if (text == null) {
        act.toast(act.getString(R.string.site_recipe_export_empty))
        return
    }
    runCatching {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("videoshell-recipe", text))
        act.toast(act.getString(R.string.site_recipe_exported))
    }.onFailure {
        act.toast(act.getString(R.string.site_recipe_io_copy_failed, it.message.orEmpty()))
    }
}

/**
 * 导入：严格按当前站校验（见 [RecipeTransfer.import]）。
 *
 * 成功之后**必须重新建适配器并重载分类** —— 配方的价值就在于影响解析结果，
 * 如果导入完界面还是旧的，用户只会看到"导入了但没反应"。
 */
internal fun SiteBrowser.importRecipe() {
    val s = site ?: return
    val text = runCatching {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(act).toString()
        } else ""
    }.getOrElse {
        act.toast(act.getString(R.string.site_recipe_io_copy_failed, it.message.orEmpty()))
        return
    }
    if (text.isBlank()) {
        act.toast(act.getString(R.string.site_recipe_import_empty))
        return
    }
    val r = RecipeTransfer.import(text, RecipeStore.hostOf(s.baseUrl))
    if (!r.ok) {
        act.toast(act.getString(R.string.site_recipe_import_failed, r.reason))
        return
    }
    act.toast(act.getString(R.string.site_recipe_import_ok, r.host) + " " + r.note)
    // 与 resetRecipe 同一套"忘掉旧状态"的动作：适配器要重造，否则导入的模板
    // 只写在盘上、当前这个实例还在用旧的穷举结果。
    adapter = AdapterFactory.create(s)
    webRendered = false
    cats = emptyList()
    loadCategories()
}

/**
 * 站点配方的手动校准入口：清掉配方 + 丢开旧适配器实例，下一次解析等同首次访问。
 */
internal fun SiteBrowser.resetRecipe() {
    val s = site ?: return
    RecipeStore.clear(s.baseUrl)
    // 「血缘判定」也要一起忘掉：否则一个被自证成加密接口族的域名
    // reset 之后仍会立刻被路由回接口，用户会觉得"重置了没反应"（v1.0.35）
    CryptFamily.forget(s.baseUrl)
    adapter = AdapterFactory.create(s)
    webRendered = false
    cats = emptyList()
    act.toast(act.getString(R.string.site_recipe_reset_done))
    loadCategories()
}
