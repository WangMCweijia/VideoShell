package com.videoshell.player

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL

/**
 * DLNA / UPnP 投屏（局域网发现 + 控制），新增功能。
 *
 * 不依赖任何第三方库：SSDP 用原生 UDP，设备描述与 SOAP 控制用 OkHttp（和解析同栈）。
 *
 * 流程：
 * 1. [discover] 发 M-SEARCH 到 239.255.255.250:1900，收集 `MediaRenderer` 的 LOCATION；
 * 2. 拉设备描述 XML，找出 `AVTransport` 服务的 controlURL；
 * 3. [play] 对选中的渲染器发 `SetAVTransportURI` + `Play`，把播放地址推过去。
 *
 * ⚠️ 这是 v1：只覆盖标准 DLNA 渲染器（大部分智能电视 / 盒子 / 投影）。
 * 个别设备用非标准命名空间或需要额外 `GetProtocolInfo` 握手，可能推不动 —— 那种情况
 * 会返回明确错误而不是静默失败。
 */
object CastManager {

    private const val TAG = "VideoShell.Cast"
    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val ST = "urn:schemas-upnp-org:device:MediaRenderer:1"

    /**
     * AVTransport 服务类型。
     *
     * ⚠️ SOAPAction 头与 XML 里的 `u:` 命名空间**都必须是** `service-AVTransport`。
     * 写成 `control-AVTransport` 时请求能发出去、渲染器也回话，但报 **HTTP 500**
     * （它不认识这个动作）—— 看错误码根本联想不到是命名空间，是这块最容易踩的坑。
     */
    private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"

    /** 一个可投屏的渲染器 */
    data class Renderer(
        val name: String,
        val controlUrl: String,   // 绝对地址，直接 POST
        val location: String
    )

    /** 局域网发现（阻塞，调用方自行切 IO 协程）。[timeoutMs] 内没回应的设备不再等 */
    suspend fun discover(timeoutMs: Int = 3000): List<Renderer> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, Renderer>()   // 用 location 去重
        runCatching outer@{
            DatagramSocket().use { sock ->
                sock.soTimeout = timeoutMs
                sock.reuseAddress = true
                val msg = buildMsearch()
                val data = msg.toByteArray(Charsets.UTF_8)
                val group = InetAddress.getByName(SSDP_ADDR)
                sock.send(DatagramPacket(data, data.size, group, SSDP_PORT))

                val buf = ByteArray(2048)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    // 内层 onepkt@：收一个包就跳出去，超时/坏包被吞掉继续等。
                    // 显式命名避免 return@runCatching 指到外层（外层同名标签曾是隐患）。
                    runCatching onepkt@{
                        val pkt = DatagramPacket(buf, buf.size)
                        sock.receive(pkt)
                        val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                        val loc = header(text, "LOCATION") ?: return@onepkt
                        if (loc in found) return@onepkt
                        val r = parseRenderer(loc)
                        if (r != null) found[loc] = r
                    }
                }
            }
        }.onFailure { Log.w(TAG, "discover 异常：${it.message}") }
        found.values.toList()
    }

    /** 推一个播放地址到渲染器 */
    suspend fun play(renderer: Renderer, url: String, title: String = ""): String = withContext(Dispatchers.IO) {
        try {
            setUri(renderer.controlUrl, url, title)
            play(renderer.controlUrl)
            "ok"
        } catch (e: Exception) {
            Log.e(TAG, "play 失败", e)
            e.message ?: "投屏失败"
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun buildMsearch(): String = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: $SSDP_ADDR:$SSDP_PORT\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: 3\r\n")
        append("ST: $ST\r\n")
        append("\r\n")
    }

    private fun header(block: String, key: String): String? {
        return block.lines().firstOrNull { l ->
            l.startsWith(key, ignoreCase = true)
        }?.substringAfter(':')?.trim()
    }

    /** 拉设备描述，提取 AVTransport 控制地址 */
    private fun parseRenderer(location: String): Renderer? {
        val xml = runCatching {
            HttpNoBody.get(location)
        }.getOrElse { return null }
        val ctrl = avTransportControlUrl(xml, location) ?: return null
        val name = Regex("<friendlyName>(.*?)</friendlyName>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()?.ifBlank { null }
            ?: runCatching { URL(location).host }.getOrNull() ?: location
        return Renderer(name, ctrl, location)
    }

    private fun avTransportControlUrl(xml: String, base: String): String? {
        // 找到 AVTransport 服务块，取其 controlURL
        val svc = Regex(
            "<service>(.*?serviceType>urn:schemas-upnp-org:service:AVTransport:1.*?)</service>",
            RegexOption.DOT_MATCHES_ALL
        ).find(xml)?.groupValues?.get(1) ?: return null
        val raw = Regex("<controlURL>(.*?)</controlURL>", RegexOption.DOT_MATCHES_ALL)
            .find(svc)?.groupValues?.get(1)?.trim() ?: return null
        return absolutize(base, raw)
    }

    private fun absolutize(base: String, path: String): String {
        if (path.startsWith("http")) return path
        val u = runCatching { URL(base) }.getOrNull() ?: return path
        if (path.startsWith("/")) return "${u.protocol}://${u.authority}$path"
        return "${u.protocol}://${u.authority}${u.path.substringBeforeLast('/')}/$path"
    }

    private fun setUri(controlUrl: String, url: String, title: String) {
        val meta = didlLite(title, url)
        val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:SetAVTransportURI xmlns:u="$AVT">
<InstanceID>0</InstanceID>
<CurrentURI>${esc(url)}</CurrentURI>
<CurrentURIMetaData>${esc(meta)}</CurrentURIMetaData>
</u:SetAVTransportURI>
</s:Body>
</s:Envelope>"""
        post(controlUrl, "$AVT#SetAVTransportURI", body)
    }

    private fun play(controlUrl: String) {
        val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Play xmlns:u="$AVT">
<InstanceID>0</InstanceID>
<Speed>1</Speed>
</u:Play>
</s:Body>
</s:Envelope>"""
        post(controlUrl, "$AVT#Play", body)
    }

    /**
     * 随 SetAVTransportURI 一起送出的 DIDL-Lite 元数据。
     *
     * 规范里这个字段是可选的（空串也能过），但相当一部分渲染器（含 Macast 这类
     * 桌面端接收端）会据此判断「这是不是一个能播的媒体项」，空着常常直接失败。
     * 给全 title / class / res 是最省事的兼容做法。
     */
    private fun didlLite(title: String, url: String): String {
        val t = title.ifBlank { "VideoShell" }
        // HLS 与普通点播的 MIME 不一样，写错有的设备会拒收
        val mime = if (url.contains(".m3u8", ignoreCase = true)) {
            "application/vnd.apple.mpegurl"
        } else {
            "video/mp4"
        }
        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
            """<item id="0" parentID="-1" restricted="1">""" +
            """<dc:title>$t</dc:title>""" +
            """<upnp:class>object.item.videoItem</upnp:class>""" +
            """<res protocolInfo="http-get:*:$mime:*">$url</res>""" +
            """</item></DIDL-Lite>"""
    }

    /** XML 文本转义。`&` 必须最先换，否则会把后面替换出的实体再转一遍。 */
    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun post(controlUrl: String, soapAction: String, body: String) {
        val req = Request.Builder()
            .url(controlUrl)
            .addHeader("Content-Type", "text/xml; charset=\"utf-8\"")
            .addHeader("SOAPACTION", "\"$soapAction\"")
            .post(body.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()
        HttpNoBody.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                // UPnP 的失败原因在 SOAP Fault 的 <errorCode> 里（401 无效动作 / 402 参数错 /
                // 501 动作未实现…）。只报 HTTP 500 没法定位，把它一并带出来。
                val text = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                val code = Regex("<errorCode>\\s*(\\d+)\\s*</errorCode>").find(text)
                    ?.groupValues?.get(1)
                throw RuntimeException(
                    buildString {
                        append("控制请求返回 ${resp.code}")
                        if (code != null) append("（UPnP 错误码 $code）")
                    }
                )
            }
        }
    }

    /** 复用一个极简的 OkHttp（不走播放器那套 HlsFix，投屏控制用普通客户端即可） */
    private object HttpNoBody {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        fun get(url: String): String {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("设备描述 ${resp.code}")
                return resp.body?.string().orEmpty()
            }
        }
    }
}
