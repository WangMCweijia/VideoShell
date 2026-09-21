package com.videoshell.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.videoshell.data.model.VideoItem

/**
 * 列表页内容缓存（FN-8）。
 *
 * 只缓存**分类浏览的首屏**。理由：
 *  - 翻页内容不该缓存 —— 用户翻到第 5 页却看到缓存，会以为"这站只有这几页"；
 *  - 搜索结果不缓存 —— 搜索本身就该是实时的，拿三天前的搜素结果充数比空着更误导。
 *
 * 目标很窄：「断网 / 超时的时候，上次看过的那个分类还在」。所以缓存键带上
 * 站点 + 分类，避免 A 分类的缓存被 B 分类借去用；再带 TTL，过期就作废、
 * 宁可空着说实话。
 */
object ListCache {

    private const val SP = "videoshell_cache"
    private const val PREFIX = "list_"

    /** 缓存有效期：3 天。过期不再顶替，避免长期显示"早就换掉的老内容" */
    private const val TTL_MS = 3L * 24 * 3600 * 1000

    private val gson = Gson()

    /** 一条缓存：内容 + 落盘时间 */
    data class Entry(val items: List<VideoItem> = emptyList(), val ts: Long = 0L)

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    private fun keyOf(siteKey: String, type: String): String = PREFIX + siteKey + "|" + type

    fun save(ctx: Context, siteKey: String, type: String, items: List<VideoItem>) {
        if (siteKey.isBlank() || items.isEmpty()) return
        val e = Entry(items, System.currentTimeMillis())
        sp(ctx).edit().putString(keyOf(siteKey, type), gson.toJson(e)).apply()
    }

    /** @return 命中且没过期的缓存；否则 null */
    fun load(ctx: Context, siteKey: String, type: String): Entry? {
        if (siteKey.isBlank()) return null
        val s = sp(ctx).getString(keyOf(siteKey, type), null) ?: return null
        val e = runCatching {
            val t = object : TypeToken<Entry>() {}.type
            gson.fromJson<Entry>(s, t)
        }.getOrNull() ?: return null
        if (e.items.isEmpty()) return null
        if (System.currentTimeMillis() - e.ts > TTL_MS) return null
        return e
    }
}
