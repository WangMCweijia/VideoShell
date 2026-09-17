package com.videoshell.data.net

/**
 * HTTP 请求留痕。
 *
 * 解决的问题：以前所有网络失败都被 `getOrNull` 吞成 null，界面上只看到"空列表"，
 * 分不清是 DNS 失败、超时、403 还是解析不出东西 —— 用户反馈"没有分类"时无从下手。
 *
 * 这里把每次请求的 URL、状态码、耗时、异常都记下来（环形缓冲，最多 [MAX] 条）：
 * - 平时只记失败（异常或非 2xx），开销可忽略；
 * - 站点自检时通过 [verbose] 打开全量记录。
 */
object NetLog {

    private const val MAX = 80

    data class Entry(val url: String, val status: Int, val ms: Long, val err: String?)

    private val buf = ArrayDeque<Entry>()

    /** 自检模式：连成功的请求也记 */
    @Volatile
    var verbose: Boolean = false

    @Synchronized
    fun record(url: String, status: Int, ms: Long, err: String? = null) {
        val ok = err == null && status in 200..399
        if (ok && !verbose) return
        buf.addLast(Entry(short(url), status, ms, err))
        while (buf.size > MAX) buf.removeFirst()
    }

    @Synchronized
    fun clear() {
        buf.clear()
    }

    @Synchronized
    fun entries(): List<Entry> = buf.toList()

    @Synchronized
    fun isEmpty(): Boolean = buf.isEmpty()

    /** 最近一次失败的简述（异常信息或 HTTP 码），没有失败则返回空串 */
    @Synchronized
    fun lastFailure(): String {
        val e = buf.lastOrNull { it.err != null || it.status !in 200..399 } ?: return ""
        return buildString {
            append("HTTP ").append(if (e.status < 0) "无响应" else e.status.toString())
            if (!e.err.isNullOrBlank()) append(' ').append(e.err)
            append(" @ ").append(e.url)
        }
    }

    @Synchronized
    fun report(): String {
        if (buf.isEmpty()) return "（无网络请求记录）"
        return buf.joinToString("\n") { e ->
            val st = if (e.status < 0) "ERR" else e.status.toString()
            val tail = if (e.err.isNullOrBlank()) "" else "  ${e.err}"
            "[$st] ${e.ms}ms  ${e.url}$tail"
        }
    }

    private fun short(u: String): String = if (u.length <= 150) u else u.take(90) + "…" + u.takeLast(50)
}
