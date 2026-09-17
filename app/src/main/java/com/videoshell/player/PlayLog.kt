package com.videoshell.player

import android.content.Context
import com.videoshell.App
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 播放记录（持久化）。
 *
 * ## 解决什么
 *
 * 前几版的诊断全都停在「站点侧」：自检能证明首页/分类/列表/详情/播放地址/媒体都是好的，
 * 但**播放器自己失败时说的那句话，从来没有被带回来过** —— 用户看到的是一块错误面板，
 * 而错误面板只能截图，内容又长又碎。于是每轮都只能靠推测："可能没编码"、"可能嗅探挑错文件"、
 * "可能是网络抖动"，改了一版又一版。
 *
 * 这里把播放器这一侧的每一次动作都落到 SharedPreferences：
 * 开始播放 / 解析出的地址 / 失败的错误码与完整 cause 链 / 失败在哪个地址上。
 * 然后 [com.videoshell.data.site.SiteDoctor] 的报告末尾会附带最近几条 ——
 * 用户只需要像以前一样"跑一次自检、复制报告"，我们就能看到播放器到底报什么错，
 * 不用再教用户走新的流程。
 */
object PlayLog {

    private const val SP = "videoshell"
    private const val KEY = "play_log"

    /** 最多留几条：够覆盖"这次进来播了两三个源都失败"就够，太多了报告会淹没重点 */
    private const val MAX = 8

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

    /** 每次播放最多记几条媒体请求：够看到"playlist 通不通、第一个分片通不通"就够了 */
    private const val MAX_REQ = 6

    /** 本次播放已经记了几条媒体请求（[newSession] 重置） */
    private var sessionReqs = 0

    private fun sp() = App.instance.getSharedPreferences(SP, Context.MODE_PRIVATE)

    /** 开始一次新的播放：重置本次会话的计数 */
    @Synchronized
    fun newSession() {
        sessionReqs = 0
    }

    /**
     * 记一条**播放器真正发出的**媒体请求。
     *
     * 只记前 [MAX_REQ] 条：一部剧有几千个分片，全记下来既没意义又会把报告冲垮；
     * 而"playlist 拿到了吗、第一个分片拿到了吗"这两条恰恰决定了能不能开播。
     * 这些条目也会进 [com.videoshell.data.net.NetLog]，在自检报告里标成 `[播放器]`。
     */
    @Synchronized
    fun request(url: String, code: Int, ms: Long) {
        if (sessionReqs >= MAX_REQ) return
        sessionReqs++
        val st = if (code < 0) "ERR" else code.toString()
        record("[MEDIA $st] ${ms}ms ${shorten(url)}")
    }

    private fun shorten(u: String): String =
        if (u.length <= 120) u else u.take(80) + "…" + u.takeLast(36)

    /** 给外部（错误面板、嗅探页）共用的地址截断，保证各处日志口径一致 */
    fun shortenPublic(u: String): String = shorten(u)

    @Synchronized
    fun record(line: String) {
        if (line.isBlank()) return
        runCatching {
            val entry = "${fmt.format(Date())}  $line"
            val old = sp().getString(KEY, "").orEmpty()
            val list = ArrayDeque<String>()
            list.addLast(entry)
            if (old.isNotBlank()) {
                old.split('\n').filter { it.isNotBlank() }.forEach { list.addLast(it) }
            }
            while (list.size > MAX) list.removeLast()
            sp().edit().putString(KEY, list.joinToString("\n")).apply()
        }
    }

    @Synchronized
    fun report(): String {
        val s = runCatching { sp().getString(KEY, "").orEmpty() }.getOrDefault("")
        return s.ifBlank { "（没有播放记录）" }
    }

    @Synchronized
    fun clear() {
        runCatching { sp().edit().remove(KEY).apply() }
    }
}
