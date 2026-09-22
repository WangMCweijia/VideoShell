package com.videoshell.data.site

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 「签名种子配置」族的**纯判据**（v1.0.53）。
 *
 * ## 这一族是什么
 *
 * 一堆短剧站共用一个前端模板（Next.js App Router），站点的**地址簿不写死在代码里**，
 * 而是运行时从一个签名配置里读：
 *
 * ```json
 * { "alg": "ed25519",
 *   "payload": "eyJhcGkiOlsiaHR0cHM6Ly9hcGkuaHVhbmdqdS5uZXQiXSwic2VlZCI6W\...",
 *   "signature": "Er1BMGl10lQM0hgobKmlfy8kDqNoRFloXQ61uNsqCv0kezifhZlDLusy6ERmVEvrvmo9CoHAcLbpcKQ5/SNaAA==" }
 * ```
 *
 * payload 是 base64 的 JSON：`{api:[…], seed:[…], track:[…], video:[…], version:N}`。
 * 设计意图很明确 —— **域名会换**，所以种子地址是一串（还有 jsDelivr / raw.githubusercontent
 * 两个公共镜像兜底），换域名只要重签一份配置。
 *
 * ## 为什么判据必须与域名无关
 *
 * 正因为"会换域名"，用域名/特征串认这一族等于立刻就过期（本项目在加密接口族上
 * 已经吃过这个亏，见 `capability-self-proof` 的结论：**身份要用能力自证**）。
 * 这里的自证是：**我能解开它的信封、并从 payload 里拿到它自己的 api 地址** ——
 * 这个能力与它叫什么域名无关。
 *
 * 判据很紧（三层都要过），所以对无关站**不会误判**：
 * `alg == ed25519` + 有非空 `signature` + `payload` 能 base64 解成 JSON 且带**至少一个 http 的 api 地址**。
 * 一个普通站的 `/config.json`（或 404 页面）绝无可能同时满足。
 *
 * ## 关于 `signature`：它是**识别标记**，不是安全边界
 *
 * 判据里对 `signature` 只用了一件事：**非空**。本文件从头到尾**没有验签**，
 * 也不打算验 —— 这是刻意的取舍，不是漏做：
 *
 * - 这一族的立意就是「**域名会换也要认得**」。真验签会在对方轮换密钥时让**全族立刻失效**，
 *   正好牺牲掉这一族唯一的强项；而它换来的"安全"在本场景是空的 —— 我们并不"信任"这个 api，
 *   只是拿它当适配入口，站点内容终归是对方提供的。
 * - minSdk 21 上 `java.security` 没有 Ed25519（要 API 33+），要验就得引 BouncyCastle
 *   或自实现曲线运算。为一个"识别标记"付这份代价不划算。
 *
 * 所以请按「**形状检查**」理解它：`signature` 字段存在 ⇒ 这是这一族的配置；
 * **不代表配置内容被任何人担保**。`apiBase()` 返回的只是"对方自己在配置里写的地址"，
 * 我们照它去请求。
 *
 * ⚠️ 若将来这个地址要承载**信任**语义（云端下发、用户间分享配方），必须补齐真验签
 * （内置公钥 + 自己的 Ed25519 实现）—— 否则任何人都能伪造一份配置把请求引到任意地址。
 * **那时候「识别」就不够用了，才需要「保证」。**详见 `docs/PITFALLS.md` E 层。
 *
 * ## 纯逻辑（离线可断言）
 *
 * 本文件**不 import 任何 Android 类**：base64 解用的是自己写的一小段（`java.util.Base64`
 * 要 API 26，本项目 minSdk 21；`android.util.Base64` 会把整个对象变成不可离线测的）。
 * 于是 `runseed.py` 能在普通 JVM 里对**真编译产物**逐条跑，包括拿真实 payload 当向量。
 */
object SeedConfig {

    /** 这一族的算法标记。只当**识别标记**用，不参与验签（理由见文件头「关于 signature」）。 */
    const val ALG = "ed25519"

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /**
     * 从配置体里取出 **api 基地址**；不是这一族返回 null。
     *
     * 取第一个 http(s) 的地址（payload 里 `api` 是数组，第一个就是官方线，
     * 后面的是本地开发兜底 —— 与 `AdapterFactory` 里"只挑第一个"的取舍一致）。
     *
     * 注意：**不验签**（`signature` 只判非空，理由见文件头）。返回的地址是"对方自己写的"，
     * 不是"被担保的"。
     */
    fun apiBase(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val env = objOf(body.trim()) ?: return null
        if (!env.str("alg").equals(ALG, true)) return null
        if (env.str("signature").isBlank()) return null
        val payloadB64 = env.str("payload")
        if (payloadB64.isBlank()) return null
        val json = decodeBase64(payloadB64) ?: return null
        val p = objOf(json) ?: return null
        val api = p.get("api")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        // 用 for-in 而不是 `size()` / `[i]`：gson 的 JsonArray 在不同版本间
        // 「方法还是属性、是否继承 ArrayList」不一致，迭代器两边都有。
        for (e in api) {
            val u = e.takeIf { it.isJsonPrimitive }?.asString.orEmpty().trim().trimEnd('/')
            if (u.startsWith("http://", true) || u.startsWith("https://", true)) return u
        }
        return null
    }

    /**
     * 标准 base64 解码（自己写的原因见文件头）。
     *
     * 容错三项，都是实测需要：① 去掉换行/回车/空格；② 同时接受 URL-safe 的 `-` `_`
     * （payload 走 URL 传递时会被换成这两个）；③ 允许**缺省补位**（长度不是 4 的倍数也照解）。
     * 遇到字母表外的字符直接返回 null —— 那说明这压根不是 base64，不该猜。
     *
     * 可见性为 public：离线 harness（`runseed.py` → `Seed.java`）要直接调它逐条对向量。
     * Kotlin 的 `internal` 成员在 JVM 上会被改名（`decodeBase64$模块名`），
     * 反射取不到 —— 这类纯函数留着 internal 只会让断言写不成，没有收益。
     */
    fun decodeBase64(src: String): String? {
        val s = src.trim().replace("\r", "").replace("\n", "").replace(" ", "")
            .replace('-', '+').replace('_', '/')
        if (s.isEmpty()) return null
        val bytes = ArrayList<Byte>(s.length * 3 / 4 + 3)
        var buf = 0
        var bits = 0
        for (ch in s) {
            if (ch == '=') break
            val v = B64.indexOf(ch)
            if (v < 0) return null
            buf = (buf shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                bytes.add(((buf shr bits) and 0xFF).toByte())
            }
        }
        if (bytes.isEmpty()) return null
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun objOf(json: String): JsonObject? =
        runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()

    private fun JsonObject.str(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
}
