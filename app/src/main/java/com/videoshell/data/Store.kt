package com.videoshell.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.videoshell.data.model.SiteConfig

/** 已适配站点的本地存储 */
object Store {

    private const val SP = "videoshell"
    private const val KEY_SITES = "sites"
    private const val KEY_DEFAULT = "default_site_key"
    private val gson = Gson()

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    fun sites(ctx: Context): MutableList<SiteConfig> {
        val s = sp(ctx).getString(KEY_SITES, null) ?: return mutableListOf()
        return runCatching {
            val t = object : TypeToken<MutableList<SiteConfig>>() {}.type
            gson.fromJson<MutableList<SiteConfig>>(s, t) ?: mutableListOf()
        }.getOrDefault(mutableListOf())
    }

    fun save(ctx: Context, list: List<SiteConfig>) {
        sp(ctx).edit().putString(KEY_SITES, gson.toJson(list)).apply()
    }

    /** @return true 表示新加入；false 表示已存在 */
    fun add(ctx: Context, site: SiteConfig): Boolean {
        val list = sites(ctx)
        if (list.any { it.key == site.key }) return false
        list.add(0, site)
        save(ctx, list)
        return true
    }

    fun remove(ctx: Context, key: String) {
        val list = sites(ctx)
        list.removeAll { it.key == key }
        save(ctx, list)
    }

    fun find(ctx: Context, key: String): SiteConfig? = sites(ctx).firstOrNull { it.key == key }

    /** 只改显示名（SiteConfig.name 是 val，用 copy 重建），key/识别结果都不动 */
    fun rename(ctx: Context, key: String, name: String) {
        val list = sites(ctx)
        var changed = false
        for (i in list.indices) {
            if (list[i].key == key) {
                list[i] = list[i].copy(name = name)
                changed = true
            }
        }
        if (changed) save(ctx, list)
    }

    // ------------------------------------------------------------------ 默认站源

    /** @return 默认站源 key；没设过/被删了返回空串 */
    fun defaultKey(ctx: Context): String = sp(ctx).getString(KEY_DEFAULT, "").orEmpty()

    fun setDefault(ctx: Context, key: String) {
        sp(ctx).edit().putString(KEY_DEFAULT, key).apply()
    }

    /**
     * 默认站源：用户星标的那个；没星标则取列表第一个；
     * 一个站点都没有返回 null（首页内容区据此显示引导文案）。
     */
    fun defaultSite(ctx: Context): SiteConfig? {
        val list = sites(ctx)
        if (list.isEmpty()) return null
        return list.firstOrNull { it.key == defaultKey(ctx) } ?: list.first()
    }
}
