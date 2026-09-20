package com.videoshell.data.net

import com.videoshell.data.site.AesCipher
import com.videoshell.data.site.CryptRecipes
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * ## 加密图床的图片层（v1.0.32）
 *
 * 有一类站的**图片本身是被 AES 加密过的**：CDN 上存的是密文，前端拿到
 * `arrayBuffer` 自行解密再 `URL.createObjectURL` 塞进 `<img>`。
 *
 * 对壳子来说这是最阴的一类故障 —— **所有常规判据都是绿的**：
 *
 * | 检查项 | 结果 |
 * |---|---|
 * | 列表解析出 `pic` | ✅ 20/20 条都有绝对地址 |
 * | 域名解析 / DNS | ✅ 正常 |
 * | 带 Referer 请求 | ✅ `HTTP 200/206` |
 * | 字节数 | ✅ 68720 B，不像错误页 |
 * | 界面 | ❌ **一片空白** |
 *
 * 只有把字节摊开看才会发现：**它不是图片**。实测野果的
 * `pic.ndhixj.cn/upload_01/.../xxx.jpeg` 前四字节是 `3E AA 70 8E`，
 * 熵 7.96 bits/byte（全 256 个字节值都出现过）—— 是密文，不是 `FF D8 FF`。
 *
 * 所以判据不能只看状态码，得看**魔数**：这也正是站点前端
 * `CEpbvVnF.js` 里那六条 `h/g/_/v/y/b` 判据在干的事 ——
 * "能认出图片魔数就直接用，认不出才解密"。这里跟着它走。
 *
 * ## 为什么放在 OkHttp 拦截器里
 *
 * 封面走 Coil（`App.newImageLoader()` 里 `callFactory(Http.client)`），
 * 与站点解析**共用同一套网络栈**。放在拦截器里意味着：
 *
 * - Coil / 列表 / 详情页海报 / 自检取图**一次修好**，不用改每个 `load()` 调用点；
 * - 域名白名单放在 [CryptRecipes]，加一个新站 = 加一行，不在 UI 层撒 if。
 *
 * ## 代价控制（三道门，任一不过就原样放行）
 *
 * 1. [CryptRecipes.mediaRecipeFor] 按**路径后缀 + 图床域名**匹配，不命中直接 `proceed`
 *    —— 普通站、普通图片、接口请求**一个字节都不多读**；
 * 2. `Content-Type` 已经是 `image/…` ⇒ 明文图，不读体、不解密（幂等）；
 * 3. 解密结果认不出图片魔数 ⇒ 原样返回原始字节（站点换密钥/换成明文图都不至于更糟）。
 *
 * ⚠️ **206 分片不解密**：Range 响应是密文的一段，AES-CBC 脱离块边界解不出来。
 * 自检取图因此刻意对加密图床改用整段请求，见 `SiteDoctor` 的 `[3a]`。
 */
object ImageCipher {

    /**
     * ## 未收录站的「疑似加密图床」登记（v1.0.34）
     *
     * 加密图床是踩过的最阴的一类坑（见文件头）：**状态码 200、字节数正常、界面全空**。
     * 我们自己的解法是往 [CryptRecipes] 加一条配方 —— 但那**只覆盖亲手逆向过的站**。
     * 新站撞上同一类图床时，用户看到的还是"没有封面"，而且**没有任何地方会说出来**，
     * 于是又变成一轮"抓包—猜—再抓包"。
     *
     * 这一层让**任何站**的加密图床都能被认出来并落到自检报告里：`图床 host -> 字节数`。
     *
     * 这是"通配性"最实在的一条：**新站不需要我们先逆向，也能自己说出问题在哪**。
     */
    private val suspected = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 自检报告用：这个图床被认出是疑似加密图床了吗；没有返回 null */
    fun suspectedBed(host: String): String? {
        val n = suspected[host.trim().lowercase()] ?: return null
        return "图床 $host 返回的**不是图片**（$n B，认不出任何图片魔数）⇒ **疑似加密图床**，" +
                "本站未收录密钥 —— 把这条连同 [3a] 的字节一起反馈即可收录"
    }

    /**
     * 未收录站的探针：**只用响应头做门控**，三道都过了才读体。
     *
     * | 门 | 判据 | 不过时 |
     * |---|---|---|
     * | 1 | 路径得像图片（[CryptRecipes.looksLikeImagePath]） | 直接放行，零成本 |
     * | 2 | 2xx 且不是 206 分片（密文截断判不出魔数） | 直接放行 |
     * | 3 | `Content-Type` 是 `*octet-stream` 或空（图床"声称这是二进制"） | 直接放行 |
     *
     * 正常站、正常图片、接口请求**一个字节都不多读** —— 真正会走到读体这一步的，
     * 只有"图片路径上返回了一坨声称二进制的数据"这种本身就异常的组合。
     *
     * 读体后：
     * - 认得出图片魔数 ⇒ 就是普通图片，放行（不改行为）；
     * - 认不出 ⇒ 登记进 [suspected] + 走 [NetLog] 的 `tag` 留痕 + **原样放行原始字节**。
     *
     * ⚠️ 留痕只能走 [NetLog]，**绝不能写自定义响应头** —— HTTP 头值只能是 ASCII，
     * 中文会抛 `Unexpected char`，而这条路上每一次封面请求都会炸（见 [rebuild] 的注释）。
     *
     * @return 需要替换响应时返回重建后的 Response；无需替换返回 null（调用方原样返回）
     */
    private fun probeUnknownBed(url: String, resp: Response): Response? {
        if (!CryptRecipes.looksLikeImagePath(url)) return null
        if (!resp.isSuccessful || resp.code == 206) return null
        val ct = resp.header("Content-Type").orEmpty().lowercase()
        if (ct.startsWith("image/")) return null
        if (ct.isNotBlank() && !ct.contains("octet-stream")) return null

        // ⚠️ 读体之前必须先用 `peekBody` 备份，读完要把字节原样还回去 —— 否则 Coil 拿到空体。
        val raw = runCatching { resp.body?.bytes() }.getOrNull() ?: return null
        if (raw.isEmpty() || AesCipher.isImage(raw)) return null

        val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty().lowercase()
        if (host.isNotBlank()) suspected.putIfAbsent(host, raw.size)
        NetLog.record(
            url, resp.code, 0,
            tag = "疑似加密图床（本站未收录密钥，已原样放行 ${raw.size}B）"
        )
        return rebuild(resp, raw, ct.takeIf { it.isNotBlank() } ?: "application/octet-stream")
    }

    val interceptor: Interceptor = Interceptor { chain ->
        val req = chain.request()
        val url = req.url.toString()
        val recipe = CryptRecipes.mediaRecipeFor(url)
        if (recipe == null) {
            val resp = chain.proceed(req)
            return@Interceptor probeUnknownBed(url, resp) ?: resp
        }

        val resp = chain.proceed(req)
        if (!resp.isSuccessful) return@Interceptor resp
        // 分片响应：密文的一段，解不了。原样走（调用方自己决定怎么处理）
        if (resp.code == 206) return@Interceptor resp

        val ct = resp.header("Content-Type").orEmpty()
        if (ct.startsWith("image/", true)) return@Interceptor resp

        val body = resp.body ?: return@Interceptor resp
        val raw = runCatching { body.bytes() }.getOrNull() ?: return@Interceptor resp

        // 已是明文图（站点改回明文 / 白名单里混着明文图）—— 保留原始字节，只把类型说清楚
        if (AesCipher.isImage(raw)) return@Interceptor rebuild(resp, raw, ct)

        val t0 = System.currentTimeMillis()
        val plain = AesCipher.decryptBytes(raw, recipe.mediaKeySpec!!, recipe.mediaIvSpec!!, recipe.mediaMode, recipe.mediaPadding)
        if (!AesCipher.isImage(plain)) {
            // 用 tag 而不是 err：这不算"请求失败"（状态码是 200，图片也确实放行了），
            // 塞进 err 会让 NetLog.lastFailure() 把它当成故障抛到界面上（"暂无数据（…解密失败）"）。
            NetLog.record(
                url, resp.code, System.currentTimeMillis() - t0,
                tag = "加密图解密失败（站点可能已换密钥）"
            )
            return@Interceptor rebuild(resp, raw, ct)
        }
        val mime = mimeOf(plain!!) ?: "image/jpeg"
        NetLog.record(
            url, resp.code, System.currentTimeMillis() - t0,
            tag = "加密图已解密 ${raw.size}->${plain.size}B $mime"
        )
        rebuild(resp, plain, mime)
    }

    /**
     * 用新字节重建响应。
     *
     * 必须同时清掉 `Content-Length` / `Content-Encoding`：原来的长度是密文的，
     * 解密后长度变了（还要扣掉 PKCS7 填充），留着会让下游按错的长度读。
     *
     * ⚠️ **不要在这里加自定义响应头**。曾经加过一个"解密成功"的标记头用于排查，
     * 结果 OkHttp 抛 `IllegalArgumentException: Unexpected char 0x89e3 at 0 in ... value`：
     * HTTP 头值只能是 **ASCII**。而这个拦截器在 Coil 取封面这条路上 ——
     * 一抛就是**每一张封面都失败**，症状和修复前一模一样（还是没图），
     * 白改一场还多背一个异常。排查信息走 [NetLog]（`tag` 字段），别走响应头。
     */
    private fun rebuild(resp: Response, bytes: ByteArray, mime: String?): Response {
        val type = mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        return resp.newBuilder()
            .body(bytes.toResponseBody(type.toMediaTypeOrNull()))
            .header("Content-Type", type)
            .removeHeader("Content-Length")
            .removeHeader("Content-Encoding")
            .build()
    }

    /** 按魔数给一个准确的 MIME（Coil 主要靠嗅探，但给对总比给错好） */
    private fun mimeOf(b: ByteArray?): String? {
        if (b == null || b.size < 12) return null
        fun m(vararg v: Int) = v.indices.all { (b[it].toInt() and 0xFF) == v[it] }
        return when {
            m(0xFF, 0xD8, 0xFF) -> "image/jpeg"
            m(0x89, 0x50, 0x4E, 0x47) -> "image/png"
            m(0x47, 0x49, 0x46, 0x38) -> "image/gif"
            m(0x42, 0x4D) -> "image/bmp"
            (m(0x52, 0x49, 0x46, 0x46) && m(0x57, 0x45, 0x42, 0x50)) -> "image/webp"
            else -> null
        }
    }
}
