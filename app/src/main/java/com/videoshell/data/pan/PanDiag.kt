package com.videoshell.data.pan

/**
 * 网盘取流的**决定性留痕**（进「复制诊断」）。
 *
 * ## 为什么非要有它
 *
 * 两个用户反馈都卡在同一件事上：**服务端说了话，但话没被带回来**。
 *
 * | 现象 | 我们手里已有的 | 真正缺的 |
 * |---|---|---|
 * | 百度 `errno=113 验证码签名错误` | 一句错误码（[PanError] 已能带出来） | 发出去的 `sign`/`timestamp` 到底是不是空、`extra` 有没有带上 |
 * | 夸克/UC「不是原画」 | 一句「解析成功」 | 服务端到底给了哪几档、我们选了哪一档 |
 *
 * [com.videoshell.data.net.NetLog] 记的是**请求**（URL / 状态码），
 * [com.videoshell.player.PlayLog] 记的是**播放器**这个进程的动作；
 * 而这两件事的答案都在**响应体**里 —— 响应体此前从不落任何日志，于是每轮只能靠推测，
 * 改一版猜一版（v1.0.81/v1.0.82 就是这么过来的）。
 *
 * 这里只记**几十行短文本**：档位表、选中项、签名字段的有无与长度。
 *
 * ⚠️ **绝不记凭据本身**：`sign` / `sekey` / `cookie` 只记**长度或有无**。
 * 这份报告会被用户直接贴出来求助（[com.videoshell.player.PlayerActivity.copyDiag]），
 * 记了明文就等于把凭据公开 —— 与 [com.videoshell.data.net.NetLog.redact] 同一条纪律。
 */
object PanDiag {

    /** 够看清"这次那一条为什么播不了"，又不至于把诊断报告撑爆 */
    private const val MAX = 40

    private val buf = ArrayDeque<String>()

    @Synchronized
    fun record(line: String) {
        if (line.isBlank()) return
        buf.addLast(line)
        while (buf.size > MAX) buf.removeFirst()
    }

    @Synchronized
    fun report(): String = if (buf.isEmpty()) "（无网盘取流记录）" else buf.joinToString("\n")

    /** 取值的留痕口径：只报有无与长度，永不报明文（见类文档） */
    @JvmStatic
    fun brief(v: String?): String = when {
        v.isNullOrBlank() -> "空"
        else -> "${v.length}字符"
    }
}
