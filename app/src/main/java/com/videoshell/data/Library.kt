package com.videoshell.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** 一条播放历史（按「站点+剧名」去重，一部剧只留最新一条） */
data class HistEntry(
    val siteKey: String = "",
    val vid: String = "",
    val name: String = "",
    val pic: String = "",
    val ep: String = "",
    val epIdx: Int = 0,
    val pos: Long = 0,
    val dur: Long = 0,
    val ts: Long = 0
)

/** 一条收藏 */
data class FavEntry(
    val siteKey: String = "",
    val vid: String = "",
    val name: String = "",
    val pic: String = "",
    val remarks: String = "",
    val ts: Long = 0
)

/**
 * 播放历史 + 收藏（本地 SP JSON 存储，与站点配置同一把 SP）。
 *
 * 历史记录由 PlayerActivity 在落进度时写入；收藏由 DetailActivity 星标切换。
 * 历史点击回跳详情页需要 siteKey + vid（详情 id）—— PlayQueue 带上 vid 即可闭环。
 */
object Library {

    private const val SP = "videoshell"
    private const val KEY_HIST = "history"
    private const val KEY_FAV = "favorites"
    private const val MAX_HIST = 100

    private val gson = Gson()

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ 播放历史

    fun history(ctx: Context): MutableList<HistEntry> {
        val s = sp(ctx).getString(KEY_HIST, null) ?: return mutableListOf()
        return runCatching {
            val t = object : TypeToken<MutableList<HistEntry>>() {}.type
            gson.fromJson<MutableList<HistEntry>>(s, t) ?: mutableListOf()
        }.getOrDefault(mutableListOf())
    }

    /** 记录一次播放：同「站点+剧」去重置顶，超出上限裁尾 */
    fun addHistory(ctx: Context, e: HistEntry) {
        if (e.name.isBlank() || e.siteKey.isBlank()) return
        val list = history(ctx)
        list.removeAll { it.siteKey == e.siteKey && it.name == e.name }
        list.add(0, e)
        while (list.size > MAX_HIST) list.removeAt(list.size - 1)
        sp(ctx).edit().putString(KEY_HIST, gson.toJson(list)).apply()
    }

    fun removeHistory(ctx: Context, siteKey: String, name: String) {
        val list = history(ctx)
        list.removeAll { it.siteKey == siteKey && it.name == name }
        sp(ctx).edit().putString(KEY_HIST, gson.toJson(list)).apply()
    }

    fun clearHistory(ctx: Context) {
        sp(ctx).edit().remove(KEY_HIST).apply()
    }

    // ------------------------------------------------------------------ 收藏

    fun favorites(ctx: Context): MutableList<FavEntry> {
        val s = sp(ctx).getString(KEY_FAV, null) ?: return mutableListOf()
        return runCatching {
            val t = object : TypeToken<MutableList<FavEntry>>() {}.type
            gson.fromJson<MutableList<FavEntry>>(s, t) ?: mutableListOf()
        }.getOrDefault(mutableListOf())
    }

    fun isFav(ctx: Context, siteKey: String, vid: String): Boolean =
        favorites(ctx).any { it.siteKey == siteKey && it.vid == vid }

    /** @return true = 已收藏；false = 已取消 */
    fun toggleFav(
        ctx: Context,
        siteKey: String,
        vid: String,
        name: String,
        pic: String,
        remarks: String
    ): Boolean {
        val list = favorites(ctx)
        val existed = list.removeAll { it.siteKey == siteKey && it.vid == vid }
        if (!existed) {
            list.add(
                0,
                FavEntry(siteKey, vid, name, pic, remarks, System.currentTimeMillis())
            )
        }
        sp(ctx).edit().putString(KEY_FAV, gson.toJson(list)).apply()
        return !existed
    }
}
