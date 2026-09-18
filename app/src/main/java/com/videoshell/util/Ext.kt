package com.videoshell.util

import android.content.Context
import android.widget.Toast
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.nio.charset.Charset

/** 按 声明编码 -> meta charset -> UTF-8/GBK 兜底 的顺序解码响应体（很多国内视频站是 GBK/GB2312）。 */
fun decodeBody(bytes: ByteArray, declared: String?): String {
    if (bytes.isEmpty()) return ""
    if (!declared.isNullOrBlank()) {
        runCatching { return String(bytes, Charset.forName(declared)) }
    }
    val probe = String(bytes, Charsets.ISO_8859_1).take(4096)
    val m = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE).find(probe)
    val cs = m?.groupValues?.get(1)
    if (!cs.isNullOrBlank() && !cs.equals("utf-8", true)) {
        runCatching { return String(bytes, Charset.forName(cs)) }
    }
    val utf8 = String(bytes, Charsets.UTF_8)
    if (utf8.contains('\uFFFD')) {
        runCatching { return String(bytes, Charset.forName("GBK")) }
    }
    return utf8
}

fun Context.toast(msg: String) {
    runCatching { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}

fun Context.toast(resId: Int) {
    runCatching { Toast.makeText(this, resId, Toast.LENGTH_SHORT).show() }
}

/** 相对地址补全 */
fun resolveUrl(base: String, url: String): String {
    val u = url.trim()
    if (u.isEmpty()) return ""
    if (u.startsWith("http://") || u.startsWith("https://")) return u
    if (u.startsWith("//")) return "https:$u"
    val b = base.trim()
    if (b.startsWith("http://") || b.startsWith("https://")) {
        b.toHttpUrlOrNull()?.resolve(u)?.let { return it.toString() }
    }
    return u
}

/** 去标签、还原实体，用于简介文本 */
fun stripHtml(html: String?): String {
    if (html.isNullOrBlank()) return ""
    return html
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&ldquo;", "“")
        .replace("&rdquo;", "”")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/** 毫秒 -> mm:ss / hh:mm:ss */
fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}
