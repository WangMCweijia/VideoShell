package com.videoshell.data.site

import com.videoshell.data.net.Http
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ## ③ 已知加密接口白名单（v1.0.25）
 *
 * 一类站点把接口响应整体 AES 加密（响应体 `data` 是一段 base64 密文），
 * 采集接口模式与 HTML 模式**都拿不到任何数据** —— HTML 里没有内容，
 * 采集接口返回的是密文。壳子此前对这类站完全无解：搜索「搜什么都一样」、
 * 播放地址只能靠 SSR 页内 JSON 硬抠（脆弱且被时效签名卡住）。
 *
 * 这一层的定位很明确：**把我们逆向出来的密钥/算法固化成「配方」，
 * 命中即用、用不上就当没有**。它不是通用破解器，只覆盖我们亲手验证过的站。
 *
 * ## 落地纪律（和站点配方同一条）
 *
 * - **密钥是站点自己的客户端密钥**（前端 bundle 里明文躺着），不是我们破的密码；
 *   配方记录的是"用哪个 key 解"，站点换 key 时这一项会失效 —— 失效要能看出来，
 *   不能静默返回空列表（见 [CryptApi.call] 的 `lastError`）。
 * - **时效签名绝不固化**：野果的 m3u8 带 `auth_key`（小时级），
 *   分集列表里存的是**伪地址**，播放时才现取现用（见 [PseudoPlayUrl]）。
 */
data class CryptRecipe(
    /** 生效的站点根域（去掉 www，匹配时按同根域 / 子域比较） */
    val hosts: List<String>,
    /** 接口前缀，形如 `https://www.yeguodj.com/api.php` */
    val apiBase: String,
    /** 前端 bundle 里的密钥字面量 */
    val keySpec: String,
    /** 前端 bundle 里的 IV 字面量 */
    val ivSpec: String,
    val mode: String = "CBC",
    val padding: String = "Pkcs7",
    /**
     * ## 加密图床（v1.0.32）
     *
     * 一类站把**图片本身**也 AES 加密后再放到 CDN 上（前端拿到 `arrayBuffer`
     * 自行解密再 `URL.createObjectURL` 显示）。壳子此前只当普通图片交给 Coil，
     * 拿到的是**密文** ⇒ 状态码 200、字节数正常、界面一片空白。
     *
     * 这种故障最迷惑人：网络全绿、解析全对、图床可达，唯独没有图 ——
     * 所以它必须有自己的判据，见 [CryptRecipes.mediaRecipeFor]。
     *
     * [mediaHosts] 命中的图片域名才走解密；不命中零开销、一个字节都不多读。
     */
    val mediaHosts: List<String> = emptyList(),
    /** 图片解密的密钥（野果是前端 bundle 里的 `media_key`） */
    val mediaKeySpec: String? = null,
    /** 图片解密的 IV（野果是 `media_iv`） */
    val mediaIvSpec: String? = null,
    val mediaMode: String = "CBC",
    val mediaPadding: String = "Pkcs7",
    /**
     * ## 域名会轮换，所以 apiBase 不该是唯一入口（v1.0.35）
     *
     * [apiBase] 是站点的**官方线路**（它自己的客户端用的那个）。实测站方会把同一个
     * `api.php` **反代到自己当前的前端域名**下：`https://agenda.fzchosdi.cc/api.php/...`
     * 与 `https://www.yeguodj.com/api.php/...` 返回**同一个信封**（`errcode` + 密文 `data`）。
     *
     * 这一条是野果「搜索无效」的根因所在：站点把前端域名从 `yeguodj.com` 换到
     * `agenda.fzchosdi.cc` 之后，[CryptRecipes.forUrl] 的域名白名单不再命中 ⇒
     * 悄悄退回 HTML 适配 ⇒ 而本站搜索**只走接口**（页面里根本没有结果节点）
     * ⇒ 用户看到的就是「App 搜不出东西，但网页端能搜」。
     *
     * 所以运行时**优先用 `{当前站点 origin}/api.php`**，[apiBase] 退成备选。
     * 见 [CryptRecipes.apiBasesFor] 与 [CryptApi.probe]。
     */
    /** 家族自证探测用的接口路径（配置接口返回 49 KB JSON，最适合当指纹） */
    val probePath: String = "/api/home/config",
    /** 握手参数（前端 bundle 的 `Sl` 对象；实测服务端不校验签名，一起带上更保险） */
    val baseParams: Map<String, String> = emptyMap()
) {
    /** 这条配方是否带「加密图床」能力 */
    val hasMedia: Boolean get() = mediaKeySpec != null && mediaIvSpec != null && mediaHosts.isNotEmpty()
}

object CryptRecipes {

    /**
     * 白名单。**加一个站 = 加一条**，不要再往适配器里塞 if。
     *
     * 野果（yeguodj.com）实测结论（2026-09-18，线上抓包 + 本地 Python 复现解密）：
     *
     * | 项 | 值 |
     * |---|---|
     * | 密钥来源 | `_nuxt/BIgYouz_.js` 的 `key` / `iv` 字段 |
     * | 算法 | AES-128-CBC / Pkcs7 |
     * | 解密函数 | `_nuxt/DDI21eCO.js` 的 `te()` / `J()` |
     * | 请求姿态 | **POST + `application/x-www-form-urlencoded`**（GET 一律回「关键词无效」） |
     * | 密文传参 | 响应 JSON 的 `data` 字段，**`+` 可能被空格化，须还原** |
     *
     * 官方线路有多个域名（`general` 接口下发 `latest_url` / `permanent_url`），
     * 全部列进 [hosts] —— 用户换备用域名后不必改配方。
     *
     * ## 加密图床（v1.0.32 补）
     *
     * 同一份前端 bundle（`_nuxt/BIgYouz_.js`）里除了 `key`/`iv` 还有一组 `media_key`/`media_iv`，
     * 由 `_nuxt/DDI21eCO.js` 的 `ae()`（导出名 `t`，函数体里叫 `DecryptImageBuffer`）使用：
     *
     * | 项 | 值 |
     * |---|---|
     * | 算法 | AES-128-CBC / Pkcs7，**整段一次性解密**（不是分段） |
     * | 密钥 | `media_key = f5d965df75336270`（ASCII 16 字节） |
     * | IV | `media_iv = 97b60394abc2fbe1` |
     * | 图床 | `pic.ndhixj.cn`（CloudFront，`Content-Type: binary/octet-stream`） |
     *
     * 实测：`.../2026091823370540510.jpeg` 密文 68720 B ⇒ 明文 JPEG 68707 B（`FFD8FFE0…JFIF`），
     * 用 `key`/`iv`（接口那组）解出来是乱码 —— **两组密钥不能混用**。
     *
     * ⚠️ 图片解密**按图床域名匹配**（[mediaRecipeFor]），**不按站点域名**：
     * 野果换过多次前端域名（`yeguodj.com` → `capable.fzchosdi.cc` → `agenda.fzchosdi.cc`），
     * 而图床一直是 `pic.ndhixj.cn`。挂在站点域名上会一换域名就失效。
     */
    private val ALL = listOf(
        CryptRecipe(
            hosts = listOf("yeguodj.com", "ygdj1.com", "ygdj2.com", "ygdj3.com"),
            apiBase = "https://www.yeguodj.com/api.php",
            keySpec = "2acf7e91e9864673",
            ivSpec = "1c29882d3ddfcfd6",
            mediaHosts = listOf("ndhixj.cn"),
            mediaKeySpec = "f5d965df75336270",
            mediaIvSpec = "97b60394abc2fbe1"
        )
    )

    /** 图片扩展名 —— 只有这些路径才值得过一遍「要不要解密」 */
    private val IMG_EXT = Regex(
        "\\.(jpe?g|png|gif|webp|bmp|avif)(?:$|[?#])",
        RegexOption.IGNORE_CASE
    )

    /** 这条 URL 看起来是不是一张图（按路径后缀判，够用且不会误伤接口） */
    fun looksLikeImagePath(url: String): Boolean = IMG_EXT.containsMatchIn(url.trim())

    /**
     * 找一个能解这条**图片 URL** 的配方；没有返回 null。
     *
     * 两道门（任何一道不过 ⇒ 零开销，连响应体都不用读）：
     * 1. 路径得像图片（[looksLikeImagePath]）；
     * 2. 主机得命中某条配方的 [CryptRecipe.mediaHosts]（同根域或子域）。
     */
    fun mediaRecipeFor(url: String): CryptRecipe? {
        if (!looksLikeImagePath(url)) return null
        val h = hostOf(url)
        if (h.isBlank()) return null
        return ALL.firstOrNull { r ->
            r.hasMedia && r.mediaHosts.any { it == h || h.endsWith(".$it") }
        }
    }

    /** 自检/诊断用：这条图片 URL 归哪条配方解（人话） */
    fun mediaHostsOfAll(): List<String> = ALL.flatMap { it.mediaHosts }.distinct()

    /** 按主机名找配方；支持同根域与子域（`www.` / `staff-` 等前缀都算） */
    fun forHost(host: String): CryptRecipe? {
        val h = host.trim().lowercase().removePrefix("www.")
        if (h.isBlank()) return null
        return ALL.firstOrNull { r -> r.hosts.any { it == h || h.endsWith(".$it") } }
    }

    fun forUrl(url: String): CryptRecipe? = forHost(hostOf(url))

    fun hostOf(url: String): String =
        Regex("^https?://([^/]+)", RegexOption.IGNORE_CASE)
            .find(url.trim())?.groupValues?.get(1)?.lowercase().orEmpty()

    fun isCryptSite(url: String): Boolean = forUrl(url) != null

    // ------------------------------------------------------------------ v1.0.35 家族自证

    /** 白名单里的默认模板：自证命中 / 挖到新密钥时都以它为底（媒体域名等静态信息都在上面） */
    fun template(): CryptRecipe = ALL.first()

    /**
     * 家族握手参数 —— 前端 bundle 里 `Sl` 对象原样搬过来。
     *
     * 实测服务端**不校验签名**（正确的 sign / 故意错的 sign / 完全不带的 sign，
     * 三次都返回**同一份** 49499 B 配置），所以这一条**不作为判据**，
     * 带上只是对齐官方客户端姿态。这也意味着：将来站点轮换 `sign_key` 不会伤到我们。
     */
    val HANDSHAKE: Map<String, String> = mapOf(
        "bundleId" to "com.pwa.mater",
        "version" to "1.3.2",
        "oauth_type" to "web",
        "language" to "zh",
        "via" to "pwa",
        "oauth_id" to "7d05538c4b8a5e74e82f93c0dab0163c",
        "token" to "",
        "trace_id" to "7d05538c4b8a5e74e82f93c0dab0163c"
    )

    /**
     * 给一个站点 origin，列出**自证**该试的 API 基址 —— v1.0.35 起**只有本站自己**。
     *
     * ## 为什么官方线路必须从这里拿掉（实测出来的，不是洁癖）
     *
     * 这里曾经把 [ALL] 的官方线路也排在候选里，当作"站点没反代 `api.php` 时的退路"。
     * 那是**把两件不同的事混成了一件**：
     *
     * - 白名单站点的**取数**基址 —— 已经确定是本族，只是找一个连得上的入口 ⇒ 官方线合理；
     * - 未知域名的**血缘自证** —— 现场判定"这个站到底是不是本族" ⇒ 官方线**致命**。
     *
     * 因为自证的判据是"我们的密钥解得开它的响应"，而官方线**今天仍然解得开**。
     * 实测（`_yg_family_probe.py`）：`www.yeguodj.com`、`yeguodj.com`、`www.ygdj2.com`、
     * `www.ygdj3.com` 四个基址都回同一份密文（77248 B → 明文 57932 B）。
     *
     * 于是任何一个**毫不相干**的站：自己域名的 `api.php` 一试就失败 → 接着试官方线 →
     * **解得开** → 判成"本族自证命中" ⇒ 被路由到 [YeguoAdapter] ⇒
     * **用户输入的是 A 站，看到的却是野果的内容**。这类"错收"比"漏收"严重得多
     * （同 [CryptRecipes] 里 HTML 判据那条取舍）。
     *
     * 所以自证候选**只能**从本站 origin 推导，判据收紧成"**本站自己**提供本族接口" ——
     * 这样"是我族"和"数据从哪来"才是同一句话。
     * 站点没在自己域名下反代 `api.php` 时退化成"判不出来 → 走网页解析"（漏收），
     * 这是刻意接受的代价。
     *
     * 入参宽容：传整条地址（`https://a.cc/x/y/z`）也**只取 origin** —— 换域名时调用方
     * 拿到的常常是**当时正在看的那个地址**（可能带路径），不剥离就会去试
     * `https://a.cc/x/y/z/api.php` 这种不存在的位置，白等一轮超时。
     */
    private val ORIGIN = Regex("^(https?://[^/]+)", RegexOption.IGNORE_CASE)

    fun apiBasesFor(origin: String): List<String> {
        val o = ORIGIN.find(origin.trim())?.groupValues?.get(1)?.trimEnd('/')
            ?: origin.trim().trimEnd('/')
        if (!o.startsWith("http", true)) return emptyList()
        return listOf("$o/api.php")
    }
}

/**
 * 播放地址占位符。
 *
 * ## 为什么列表里不能直接放直链
 *
 * 野果的 m3u8 形如
 * `.../x.m3u8?auth_key=1789720426-6aacf76ae41aa-0-54db...`，其中 `1789720426`
 * 是 **unix 秒**，实测约 1 小时过期。详情接口一次能把 22 集的直链全给出来，
 * 但用户往往是"今天列表里存着，明天接着看" —— 固化直链等于第二天必然 403。
 *
 * 所以分集列表里放**伪地址**（带 videoId + episodeId），播放前一刻才去取真链。
 * 顺带解决了"同一集重播时链接已过期"的问题：每次播放都是新签的 key。
 *
 * 伪地址形如 `yeguo://play/2275/186514`。
 */
object PseudoPlayUrl {
    const val SCHEME = "yeguo"

    fun build(videoId: String, episodeId: String): String =
        "$SCHEME://play/$videoId/$episodeId"

    /** -> `(videoId, episodeId)`；不是伪地址返回 null */
    fun parse(url: String): Pair<String, String>? {
        if (!url.startsWith("$SCHEME://")) return null
        val seg = url.removePrefix("$SCHEME://").trimStart('/')
        val parts = seg.split('/')
        if (parts.size < 3) return null
        val v = parts[1].substringBefore('?')
        val e = parts[2].substringBefore('?')
        if (v.isBlank() || e.isBlank()) return null
        return v to e
    }

    fun isPseudo(url: String): Boolean = url.startsWith("$SCHEME://")
}

/**
 * 宽松 base64 解码（纯 Kotlin）。
 *
 * **刻意不用 `android.util.Base64`**：离线校验套件是 JVM 直跑编译产物，
 * android.jar 里那些方法是 `Stub!`（一调就抛）。自己实现这 30 行，
 * 解密链路就能在没有真机的情况下跑真实断言 —— 这是本项目一贯的排查纪律。
 *
 * 容忍：首尾空白、换行、URL-safe 字母表（`-` `_`）、缺省 `=` 填充。
 */
object Base64Lite {
    private val STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val REV = IntArray(128) { -1 }.also { m ->
        for (i in STD.indices) m[STD[i].code] = i
    }

    fun decode(input: String): ByteArray? {
        val s = input.trim()
        if (s.isEmpty()) return null
        // 只保留有效字符：空白与非字母表字符（含 URL-safe 的 - _）都归一化
        val norm = StringBuilder(s.length)
        for (c in s) when {
            c == '-' -> norm.append('+')
            c == '_' -> norm.append('/')
            c.isWhitespace() -> Unit
            c == '=' -> Unit
            c.code < 128 && REV[c.code] >= 0 -> norm.append(c)
            else -> return null
        }
        val t = norm.toString()
        if (t.isEmpty()) return null
        val out = java.io.ByteArrayOutputStream(t.length * 3 / 4 + 3)
        var buf = 0
        var bits = 0
        for (c in t) {
            buf = (buf shl 6) or REV[c.code]
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buf shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}

/** AES 解密（JVM 原生，离线可测） */
object AesCipher {

    /**
     * 把前端 bundle 里的 key/iv 字面量还原成字节。
     *
     * 野果前端的写法是「字符码用 `_` 连接」，形如
     * `t = e => /^\d+(?:_\d+)+$/.test(e) ? e : [...e].map(c=>c.charCodeAt(0)).join('_')`，
     * 解密时再 `split('_').map(c=>String.fromCharCode(+c)).join('')` 还原回来。
     * 对**含字母**的字面量（`2acf7e91e9864673`）这一来一回是**恒等**的，
     * 所以直接取 ASCII 就对了；纯数字+下划线的形态才是真的字符码表，两种都认。
     */
    fun bytesOf(spec: String): ByteArray {
        val s = spec.trim()
        val codeTable = Regex("^\\d+(?:_\\d+)+$")
        if (!codeTable.matches(s)) return s.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        for (seg in s.split('_')) {
            val n = seg.toIntOrNull() ?: return s.toByteArray(Charsets.UTF_8)
            if (n !in 0..0xFFFF) return s.toByteArray(Charsets.UTF_8)
            sb.append(n.toChar())
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * 解一段 base64 密文，返回明文；任何一步不成立都返回 null（**不抛**）。
     *
     * `Pkcs7` 解填充做了范围校验：解错 key 时最后一字节是随机的，
     * 不校验就会返回一坨乱码让上层"以为成功了"。宁可为 null 让它去报错。
     */
    fun decrypt(
        cipherB64: String,
        keySpec: String,
        ivSpec: String,
        mode: String = "CBC",
        padding: String = "Pkcs7"
    ): String? {
        val raw = Base64Lite.decode(restorePlus(cipherB64)) ?: return null
        if (raw.isEmpty() || raw.size % 16 != 0) return null
        val key = bytesOf(keySpec)
        val iv = bytesOf(ivSpec)
        if (key.size != 16 && key.size != 24 && key.size != 32) return null
        // ⚠️ 必须分三段拼。写成 `"AES/" + if (..) "CBC" else "ECB" + "/" + if (..) ..`
        // 会因 `+` 与 `if` 的优先级踩坑：else 分支把 `+ "/" + ...` 一起吃掉了，
        // CBC 分支只剩 `"AES/CBC"`（两段）—— `Cipher.getInstance` 当场抛异常被 catch 成
        // null，**整条解密链路恒为 null**，而表面症状只是"搜索没结果"。
        // 这个坑就是被离线套件 runygo 用真实密文夹具抓出来的。
        val modePart = if (mode.equals("CBC", true)) "CBC" else "ECB"
        val padPart = if (padding.equals("Pkcs7", true)) "PKCS5Padding" else "NoPadding"
        val transform = "AES/$modePart/$padPart"
        return runCatching {
            val c = Cipher.getInstance(transform)
            c.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                if (modePart == "CBC") IvParameterSpec(iv) else null
            )
            val pt = c.doFinal(raw)
            val s = String(pt, Charsets.UTF_8)
            if (s.indexOf('\uFFFD') >= 0) null else s
        }.getOrNull()
    }

    /**
     * 解一段**二进制**密文（图片用），返回明文；任何一步不成立都返回 null（**不抛**）。
     *
     * 与 [decrypt] 的区别只有「输入不是 base64」这一点：加密图床返回的是裸字节
     * （`Content-Type: binary/octet-stream`），前端是 `arrayBuffer` 直接喂进
     * `crypto.subtle.decrypt` 的 —— 所以这边也不能先 base64 解一次。
     *
     * Pkcs7 解填充做了**范围校验**（这一条比 [decrypt] 那边更要紧）：
     * 密文长度不对齐、或站点换了密钥时，最后一字节是随机的，不校验就会返回
     * "长度正常的一坨乱码"，上层以为成功了，界面上还是没图 —— 又变成悬案。
     * 校验后返回 null，调用方原样放行原始字节。
     */
    fun decryptBytes(
        raw: ByteArray,
        keySpec: String,
        ivSpec: String,
        mode: String = "CBC",
        padding: String = "Pkcs7"
    ): ByteArray? {
        if (raw.isEmpty() || raw.size % 16 != 0) return null
        val key = bytesOf(keySpec)
        val iv = bytesOf(ivSpec)
        if (key.size != 16 && key.size != 24 && key.size != 32) return null
        val modePart = if (mode.equals("CBC", true)) "CBC" else "ECB"
        val padPart = if (padding.equals("Pkcs7", true)) "PKCS5Padding" else "NoPadding"
        return runCatching {
            val c = Cipher.getInstance("AES/$modePart/$padPart")
            c.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                if (modePart == "CBC") IvParameterSpec(iv) else null
            )
            c.doFinal(raw)
        }.getOrNull()
    }

    /**
     * 这段字节是不是一张**真图片**。
     *
     * 判据照抄站点前端 `CEpbvVnF.js` 里那六条（JPEG / PNG / GIF / BMP / TIFF / WEBP）——
     * 前端就是靠它决定"要不要解密"和"解密算不算成功"的，我们跟着它走，
     * 两边对"什么算图片"的定义才一致。
     *
     * 用途：① 已经是明文图就别解密（幂等）；② 解出来不是图 ⇒ 判定失败，别污染响应。
     */
    fun isImage(b: ByteArray?): Boolean {
        if (b == null || b.size < 12) return false
        fun m(vararg v: Int) = v.indices.all { (b[it].toInt() and 0xFF) == v[it] }
        return m(0xFF, 0xD8, 0xFF) ||                         // JPEG
                m(0x89, 0x50, 0x4E, 0x47) ||                  // PNG
                m(0x47, 0x49, 0x46, 0x38) ||                  // GIF8
                m(0x42, 0x4D) ||                              // BMP
                m(0x00, 0x00, 0x01, 0x00) ||                  // TIFF(LE 序，前端也这么判)
                m(0x49, 0x49, 0x2A, 0x00) ||                  // TIFF(II)
                m(0x4D, 0x4D, 0x00, 0x2A) ||                  // TIFF(MM)
                (b[0].toInt() == 0x52 && b[1].toInt() == 0x49 && b[2].toInt() == 0x46 &&
                        b[3].toInt() == 0x46 && b[8].toInt() == 0x57 && b[9].toInt() == 0x45 &&
                        b[10].toInt() == 0x42 && b[11].toInt() == 0x50)   // WEBP
    }

    /**
     * 还原被表单编码吃掉的 `+`。
     *
     * 服务端把 base64 密文塞进 `application/x-www-form-urlencoded` 响应时，
     * `+` 会按表单规则变成空格（前端 `J()` 里那句 `.replace(/ /g,'+')` 就是在补这个）。
     * 不还原 → base64 解出的字节整体偏移 → 解密静默失败。
     */
    fun restorePlus(s: String): String = s.replace(' ', '+')
}

/**
 * 加密接口调用器：发请求 → 解 `data` → 还你一个能直接读的 [JsonElement]。
 */
object CryptApi {

    /** 最近一次失败原因（UI 诊断用；成功时清空） */
    var lastError: String = ""
        private set

    /** 最近一次家族自证的过程说明（每个候选基址试出来什么；自检原样打印） */
    var lastProbeNote: String = ""
        private set

    /**
     * ## 家族自证（v1.0.35）
     *
     * 判定一个站是不是**用我们这套密钥的那一族**，判据只有一条：
     *
     * > 拿密钥去解它的接口响应，**解得开**。
     *
     * 为什么别的判据都不行：
     * - **域名不可靠** —— 野果换过 3 次前端域名（见 [CryptRecipes.apiBasesFor]）；
     * - **状态码不可靠** —— 站方对未知路径也回 200（实测 `/api/zzz/nothing` 也是 200）；
     * - **外层形状不可靠** —— 别的家族也可能长成 `{errcode,data}`。
     *
     * 只有"解得开"这道门过不去一半：里面是 AES-CBC + Pkcs7，密钥错一位就解不出 JSON，
     * 也过不了 `JsonParser`。所以它同时是**充分**且几乎不会假阳性的判据。
     *
     * @return 自证成功时返回**已按该站 origin 修正过 apiBase** 的配方；否则 null
     */
    suspend fun probe(recipe: CryptRecipe, origin: String, referer: String): CryptRecipe? {
        val log = ArrayList<String>()
        for (base in CryptRecipes.apiBasesFor(origin)) {
            val r = recipe.copy(apiBase = base, baseParams = CryptRecipes.HANDSHAKE)
            val resp = call(r, r.probePath, r.baseParams, referer)
            val short = base.removePrefix("https://").removePrefix("http://").trimEnd('/')
            if (resp != null) {
                log += "$short → 解得开✅"
                lastProbeNote = log.joinToString("；")
                return r
            }
            log += "$short → ${lastError.take(48)}"
        }
        lastProbeNote = log.joinToString("；")
        return null
    }

    /**
     * 调一个加密接口，返回**解密后**的 JSON（通常是 `{data:..., status:1, msg:"ok"}`）。
     *
     * 失败一律返回 null 并把原因写进 [lastError] —— 绝不"失败返回空列表"，
     * 那种静默失败是小站解析里最难查的一类问题（本项目的白纸黑字纪律）。
     */
    suspend fun call(
        recipe: CryptRecipe,
        path: String,
        params: Map<String, String>,
        referer: String
    ): JsonObject? {
        lastError = ""
        val url = recipe.apiBase.trimEnd('/') + path
        val body = Http.postFormOrNull(url, params, referer = referer)
        if (body.isNullOrBlank()) {
            lastError = "接口无响应：$path"
            return null
        }
        val root = runCatching { JsonParser.parseString(body) }.getOrNull() as? JsonObject
        if (root == null) {
            lastError = "接口返回非 JSON（${body.take(60)}）：$path"
            return null
        }
        val err = root.get("errcode")?.asIntOrNull()
        if (err != null && err != 0) {
            lastError = "接口 errcode=$err：$path"
            return null
        }
        val cipher = root.get("data")?.takeIf { it.isJsonPrimitive }?.asString
        if (cipher.isNullOrBlank()) {
            lastError = "接口未返回加密数据：$path"
            return null
        }
        val plain = AesCipher.decrypt(
            cipher, recipe.keySpec, recipe.ivSpec, recipe.mode, recipe.padding
        )
        if (plain.isNullOrBlank()) {
            // 这一条是"站点换了密钥"的唯一信号 —— 必须说清楚，别让用户以为是网络问题
            lastError = "解密失败（站点可能已更换密钥）：$path"
            return null
        }
        val obj = runCatching { JsonParser.parseString(plain) }.getOrNull() as? JsonObject
        if (obj == null) {
            lastError = "解密后不是 JSON：$path"
            return null
        }
        return obj
    }

    /** 业务数据在 `data` 里；有的接口 `data` 直接是数组/空数组，统一用这个读 */
    fun dataOf(resp: JsonObject?): JsonElement? = resp?.get("data")

    /** `status != 1` 视为业务失败，返回站点的 msg（如「关键词无效」「参数错误」） */
    fun businessError(resp: JsonObject?): String? {
        if (resp == null) return lastError
        val st = resp.get("status")?.asIntOrNull() ?: return null
        if (st == 1) return null
        return resp.get("msg")?.asString?.takeIf { it.isNotBlank() } ?: "status=$st"
    }
}

internal fun JsonElement?.asIntOrNull(): Int? =
    this?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }

internal fun JsonElement?.str(): String =
    this?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }.orEmpty()

internal fun JsonObject?.obj(key: String): JsonObject? =
    this?.get(key)?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonObject?.arr(key: String): List<JsonElement> =
    this?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
