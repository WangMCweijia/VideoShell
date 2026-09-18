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
    val padding: String = "Pkcs7"
)

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
     */
    private val ALL = listOf(
        CryptRecipe(
            hosts = listOf("yeguodj.com", "ygdj1.com", "ygdj2.com", "ygdj3.com"),
            apiBase = "https://www.yeguodj.com/api.php",
            keySpec = "2acf7e91e9864673",
            ivSpec = "1c29882d3ddfcfd6"
        )
    )

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
