package com.videoshell.player

/**
 * 「这一次请求该不该原样再试一次」——播放器侧的**瞬时失败**判定（v1.0.39）。
 *
 * ## 为什么需要它
 *
 * 用户报的现象是：**播放器能读出本集时长，画面却一直转圈**。
 * 时长来自 playlist（早就拿到了），转圈来自分片取不到。而"分片取不到"里相当大一部分
 * 是**瞬时**的：
 *
 * - **限流 / 风控**：CDN 对突发的分片请求回 `402 Payment Required` / `429`。
 *   实测枫叶影院那条 co 线路背后的 `cibn-edge-5g.1ljx.com`（Vercel 边缘）就会这样，
 *   清单始终 200、分片成片地 402，**停一会儿自己就恢复了**；
 * - **边缘切换 / 回源抖动**：`500 / 502 / 503 / 504`；
 * - **链路抖动**：连接或读超时（这一支在 DataSource 里以 IOException 出现）。
 *
 * ExoPlayer 自带的加载策略把 4xx 当"内容的错"（404 明确不重试），于是同一类故障在它那里
 * 要么直接判死、要么在静默重试里无声地耗掉几十秒 —— 两种表现都不像"稍等就能好"。
 *
 * ## 为什么做成一个纯对象
 *
 * 「哪些状态码算瞬时」是一条**判据**。判据必须能被离线断言逐条钉住，
 * 不能埋在 DataSource 的 catch 块里靠肉眼。这里不碰网络、不碰 Android、不碰 OkHttp。
 *
 * ## 为什么退避只有几百毫秒
 *
 * 只退避 0.6s / 1.5s，单次请求最多多等 2.1 秒。**刻意不去猜服务端的限流窗口**：
 * "等 90 秒它就好了"这种判断一旦写进播放路径，等于把一个瞬时故障变成用户眼里的
 * "这软件坏了"。这里只负责**抹平抖动**；真正需要长等的场景，交给上层的
 * 「卡住 ⇒ 重新解析本集（换一份新令牌）」和换源。
 */
object PlayRetry {

    /** 每个请求最多额外重试几次（不含第一次）。取 2 是为了让最坏代价可控 */
    const val MAX_RETRY = 2

    /** 第 0、1 次重试前各等多久 */
    private val DELAYS = longArrayOf(600L, 1_500L)

    /**
     * 这个 HTTP 状态码是不是**瞬时**的（值得原样再试一次）。
     *
     * 刻意**不含 403 / 404 / 410**：那是"这块内容没有了 / 不给看"，
     * 重试只会白等，还会把真正该换源的情况拖成一段没有信息的转圈。
     *
     * `402` 必须在内：它看着像"要付款"，实际被不少边缘节点当成限流信号用（实测就是它）。
     */
    fun transientStatus(code: Int): Boolean = when (code) {
        402, 408, 425, 429, 500, 502, 503, 504 -> true
        else -> false
    }

    /**
     * 第 [attempt] 次重试之前等多久（attempt 从 0 起）。超出表长就取最后一档。
     *
     * 不返回随机的抖动值：播放器只有一个 loader 在取分片，不存在惊群，
     * 而**确定性**让这条判据可以被断言。
     */
    fun delayMs(attempt: Int): Long = DELAYS[attempt.coerceIn(0, DELAYS.size - 1)]

    /** 拿到 [code] 时还要不要再试一次（已试过 [attempt] 次） */
    fun shouldRetry(attempt: Int, code: Int): Boolean =
        attempt < MAX_RETRY && transientStatus(code)

    /**
     * 这一串尝试总共最多多花多少毫秒。给"最坏代价"留一个可断言的数：
     * 它必须小到用户感觉不到"卡了"，否则这个机制本身就成了新的抱怨源。
     */
    fun worstExtraMs(): Long {
        var sum = 0L
        for (i in 0 until MAX_RETRY) sum += delayMs(i)
        return sum
    }
}
