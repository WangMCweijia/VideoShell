package com.videoshell.data.site

import com.videoshell.data.net.Http

/**
 * ## 从站点自己的 JS 里**挖出密钥**（v1.0.35，通用化的第一笔债）
 *
 * 加密接口站的密钥不是我们破出来的 —— 它明文躺在站点自己的打包产物里
 * （野果：`_nuxt/BIgYouz_.js` 的 `{key,iv,sign_key,media_key,media_iv}`）。
 * 以前这一步要人工做一次、写进 [CryptRecipes] 硬编码，于是：
 *
 * - 站点**换密钥** ⇒ 全站失效，必须等我们发版；
 * - 站点**换域名** ⇒ 白名单不命中，用户以为软件坏了；
 * - 出现同族的**新站** ⇒ 同样要等发版。
 *
 * 这一层把「找密钥」变成运行时能力：**抓首页 → 找出它引用的 JS → 在里面盲搜配置对象**。
 * 盲搜不依赖文件名（`BIgYouz_` 这种后缀每次构建都会变）、也不依赖压缩形式。
 *
 * ### 三条纪律
 *
 * 1. **只提名、不拍板**：[parseConfig] 是纯函数，它给出的是**候选**；
 *    谁能用由 [CryptApi.probe] 说了算（真拿它去解服务端响应）。
 *    「挖到像样的 32 位 hex」不是证据，「解得开」才是。
 * 2. **不为此多发一个请求**：只有已经判定「是这一族但内置密钥解不开」时才调用
 *    （见 [CryptFamily.resolve]）—— 正常站点一个字节都不会多抓。
 * 3. **键长必须合法**：候选先过 [AesCipher.bytesOf]，长度不是 16/24/32 的直接丢掉 ——
 *    这一条能筛掉绝大多数「恰好 16 个字符」的噪声（版本号、构建 id、路由名）。
 */
object CryptDiscovery {

    /** 最多看多少个分包。Nuxt 站首页引用 30 个左右，40 够用且封顶 */
    private const val MAX_CHUNKS = 40

    /** 字段名 → 值的盲搜。刻意不要求引号配对（压缩器三种引号混用），够用且不脆 */
    private val FIELD = Regex(
        "(?<![\\w$])" +
                "(media_padding|media_key|media_iv|sign_key|key|iv)" +
                "\\s*:\\s*" +
                "(?:[A-Za-z_$][\\w$.]*\\s*\\(\\s*)?" +      // 可能被 `t(...)` 这类包装函数裹一层
                "[\"'`]([A-Za-z0-9_]{16,80})[\"'`]"
    )

    /** 首页里引用的同源 JS（`<script src>`、`<link rel=modulepreload href>` 都算） */
    private val JS_REF = Regex(
        "(?:src|href)\\s*=\\s*[\"']([^\"']+\\.js(?:\\?[^\"']*)?)[\"']",
        RegexOption.IGNORE_CASE
    )

    /** 同一份配置文件里 key 和 iv 一定挨得很近；这个窗口够松弛也够准 */
    private const val WINDOW = 800

    data class Config(
        val key: String,
        val iv: String,
        val signKey: String? = null,
        val mediaKey: String? = null,
        val mediaIv: String? = null
    )

    /** 最近一次挖掘的结论（自检用） */
    var lastNote: String = ""
        private set

    // ------------------------------------------------------------------ 纯函数（可离线断言）

    /**
     * 从一段 JS 里抽出**所有** `{key,iv,...}` 候选。
     *
     * 做法：把 `字段名: 字面量` 全找出来，再以每个 `key` 为锚、在它后面 [WINDOW] 个字符内
     * 找同组的 `iv` / `sign_key` / `media_key` / `media_iv`。
     *
     * 为什么以 `key` 为锚而不是「找对象字面量」：压缩后的对象没有可靠边界
     * （可能整段是一个 `var n={...}`，也可能被拆进参数里），
     * 而 `key` + `iv` 必须成对出现才能用来解密 —— 用这个「必须成对」的约束当锚更稳。
     */
    fun parseConfig(js: String): List<Config> {
        val hits = FIELD.findAll(js)
            .map { Triple(it.groupValues[1], it.groupValues[2], it.range.first) }
            .toList()
        val out = ArrayList<Config>()
        for ((i, h) in hits.withIndex()) {
            if (h.first != "key") continue
            if (!okAesKey(h.second)) continue
            val group = HashMap<String, String>()
            for (j in i + 1 until hits.size) {
                val n = hits[j]
                if (n.third - h.third > WINDOW) break
                group.putIfAbsent(n.first, n.second)
            }
            val iv = group["iv"] ?: continue
            if (!okAesKey(iv)) continue
            val mediaKey = group["media_key"]?.takeIf { okAesKey(it) }
            val mediaIv = group["media_iv"]?.takeIf { okAesKey(it) }
            out += Config(
                key = h.second,
                iv = iv,
                signKey = group["sign_key"],
                // 图片那组是成对的，缺一个就当没有（半对没法用）
                mediaKey = if (mediaIv != null) mediaKey else null,
                mediaIv = if (mediaKey != null) mediaIv else null
            )
        }
        return out.distinctBy { it.key to it.iv }
    }

    /** 首页里同源的 JS 地址（相对路径按 origin 补全；绝对地址只收同源的，避免抓 CDN 白跑） */
    fun jsUrlsOf(html: String, origin: String): List<String> {
        val host = Regex("^https?://([^/]+)", RegexOption.IGNORE_CASE)
            .find(origin)?.groupValues?.get(1)?.lowercase().orEmpty()
        val out = LinkedHashSet<String>()
        for (m in JS_REF.findAll(html)) {
            val raw = m.groupValues[1].trim()
            val abs = when {
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("http") -> raw
                raw.startsWith("/") -> origin.trimEnd('/') + raw
                else -> origin.trimEnd('/') + "/" + raw
            }
            val h = Regex("^https?://([^/]+)", RegexOption.IGNORE_CASE)
                .find(abs)?.groupValues?.get(1)?.lowercase().orEmpty()
            if (h != host) continue                       // 只跟同源：CDN/统计脚本里没有站点配置
            out += abs
        }
        return out.take(MAX_CHUNKS)
    }

    /** AES 密钥/IV 的合法长度只有三种。这一条是主要的降噪手段 */
    fun okAesKey(s: String): Boolean = AesCipher.bytesOf(s).let {
        it.size == 16 || it.size == 24 || it.size == 32
    }

    // ------------------------------------------------------------------ 抓取

    /**
     * 去站点上把密钥挖出来。**只在 [CryptFamily.resolve] 判定「像这一族但解不开」时才调**。
     *
     * 找不到返回 null，并把原因写进 [lastNote] —— 绝不"返回一个猜的密钥"。
     */
    suspend fun fromSite(origin: String, referer: String = origin): Config? {
        val home = Http.getOrNull(origin, referer = referer)
        if (home.isNullOrBlank()) {
            lastNote = "首页抓不到（$origin）"
            return null
        }
        val urls = jsUrlsOf(home, origin)
        if (urls.isEmpty()) {
            lastNote = "首页没有引用同源 JS（不是打包式前端？）"
            return null
        }
        var scanned = 0
        for (u in urls) {
            val js = Http.getOrNull(u, referer = origin) ?: continue
            scanned++
            val c = parseConfig(js).firstOrNull() ?: continue
            lastNote = "在 ${u.substringAfterLast('/')} 里挖到候选密钥（key=${c.key.take(4)}…）"
            return c
        }
        lastNote = "扫了 $scanned 个分包，没有找到成对的 key/iv"
        return null
    }
}
