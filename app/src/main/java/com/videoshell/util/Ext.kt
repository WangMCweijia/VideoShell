package com.videoshell.util

import android.content.Context
import android.widget.Toast
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.nio.charset.Charset

/**
 * 按 声明编码 -> meta charset -> UTF-8/GBK 兜底 的顺序解码响应体（很多国内视频站是 GBK/GB2312），
 * 之后再过一道 [unwrapJsonHtml]（部分站点的反爬会把整页 HTML 当 JSON 字符串吐回来）。
 */
fun decodeBody(bytes: ByteArray, declared: String?): String =
    unwrapJsonHtml(decodeRawBody(bytes, declared))

private fun decodeRawBody(bytes: ByteArray, declared: String?): String {
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

/**
 * 解包「把整页 HTML 当 JSON 字符串返回」的响应体。
 *
 * ## 为什么需要它（2026-09-18 实测，zqkhmy.com）
 *
 * 部分站点的 nginx 会按 `Accept` 做内容协商/反爬：请求头里一旦出现 `application/json`，
 * 它就把整页 HTML **转义成 JSON 字符串**返回 —— body 形如
 * `"<!DOCTYPE html>…"`，中文全变成 `\uXXXX`、斜杠变成 `\/`。
 *
 * 这种体喂给 HTML 解析器等于喂噪声，而且**失败得很隐蔽**：页面其实 200、字节数也正常
 * （129158 字），但站型判据 `looksLikeVideoSite` 在转义文本上一个中文词都匹配不到
 * （`电影` 变成了 `\u7535\u5f71`），于是判「不是视频站」——
 * 用户看到的就是「添加之后识别不了」。
 *
 * 所以这里做一次通用解包，同时也**不要**依赖它：请求头本身要对齐真实浏览器
 * （见 `Http` 的 Accept），解包只是第二道保险。
 *
 * ## 判据为什么写这么保守
 *
 * 必须是**整个 body 就是一个 JSON 字符串**、且解出来确实含 HTML 标志才替换。
 * - JSON 接口（`{…}`）不以 `"` 开头 ⇒ 不受影响；
 * - 普通 HTML 不以 `"` 开头 ⇒ 不受影响；
 * - 万一某页正文真的以引号开头，解出来不含 `<html`/`<!DOCTYPE` ⇒ 原样返回。
 */
fun unwrapJsonHtml(body: String): String {
    val t = body.trim()
    if (t.length < 16 || t[0] != '"' || t[t.length - 1] != '"') return body
    if (!t.contains("<")) return body
    val un = unescapeJsonString(t.substring(1, t.length - 1))
    val looksHtml = un.contains("<!doctype", true) || un.contains("<html", true)
    return if (looksHtml) un else body
}

/**
 * JSON 字符串字面量（不含外层引号）的解转义。手写而不用 JSON 库，是因为
 * 本工程有「纯 Kotlin 逻辑要能在 JVM 上直接断言」的纪律（`org.json` 在离线
 * harness 上是 android.jar 的桩，调用即抛），且这里只需处理转义、不需要完整语法。
 * 不认识的转义序列**原样保留**，宁可少还原也不丢字节。
 */
fun unescapeJsonString(s: String): String {
    if (!s.contains('\\')) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c != '\\' || i == s.length - 1) {
            sb.append(c)
            i++
            continue
        }
        when (val e = s[i + 1]) {
            '"' -> { sb.append('"'); i += 2 }
            '\\' -> { sb.append('\\'); i += 2 }
            '/' -> { sb.append('/'); i += 2 }
            'b' -> { sb.append('\b'); i += 2 }
            'f' -> { sb.append('\u000C'); i += 2 }
            'n' -> { sb.append('\n'); i += 2 }
            'r' -> { sb.append('\r'); i += 2 }
            't' -> { sb.append('\t'); i += 2 }
            'u' -> {
                val hex = if (i + 6 <= s.length) s.substring(i + 2, i + 6) else ""
                val v = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                if (v != null) {
                    sb.append(v.toChar())
                    i += 6
                } else {
                    sb.append('\\'); sb.append(e); i += 2
                }
            }
            else -> { sb.append('\\'); sb.append(e); i += 2 }
        }
    }
    return sb.toString()
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
