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

    data class Entry(
        val url: String,
        val status: Int,
        val ms: Long,
        val err: String?,
        /** 来源标注，如 `播放器` —— 用于在报告里区分"这条是播放器发的"还是"自检发的" */
        val tag: String? = null
    )

    private val buf = ArrayDeque<Entry>()

    /** 自检模式：连成功的请求也记 */
    @Volatile
    var verbose: Boolean = false

    /**
     * @param tag 来源标注（如 `播放器`）。带 tag 的记录**不受 [verbose] 限制**：
     *            播放器发出的请求本来就是排查的核心，永远要留痕。
     */
    @Synchronized
    fun record(url: String, status: Int, ms: Long, err: String? = null, tag: String? = null) {
        val ok = err == null && status in 200..399
        if (ok && !verbose && tag == null) return
        buf.addLast(Entry(short(redact(url)), status, ms, err?.let { redact(it) }, tag))
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
            val tag = if (e.tag.isNullOrBlank()) "" else " [${e.tag}]"
            val tail = if (e.err.isNullOrBlank()) "" else "  ${e.err}"
            "[$st]$tag ${e.ms}ms  ${e.url}$tail"
        }
    }

    private fun short(u: String): String = if (u.length <= 150) u else u.take(90) + "…" + u.takeLast(50)

    /**
     * ## 凭据脱敏（v1.0.65）
     *
     * 为什么非做不可：网盘接口的 URL 里**带着分享令牌**（夸克 `stoken=…`），而 [report]
     * 的输出会被用户直接贴出来求助（本站点自检报告就是这么用的）。不做这一步，
     * 「贴个报告」等于把凭据公开。
     *
     * 只抹**看起来是密钥**的值（长度 ≥ 8），短参数（`?pwd=6107` 这种提取码属于公开信息、
     * 且是排查时最需要看到的）原样保留 —— 过度脱敏会让报告失去诊断价值。
     * [com.videoshell.data.pan.DriveStore] 的凭据本身从不进 NetLog（只进请求头）。
     */
    private val SECRET = Regex(
        "(?i)\\b(pwd|passcode|stoken|sign|signature|token|access_token|auth|" +
                "authorization|cookie|secret|dltoken|download_url)=([^&\\s]{8,400})"
    )

    private fun redact(s: String): String =
        if (s.indexOf('=') < 0) s else SECRET.replace(s) { "${it.groupValues[1]}=**" }
}
