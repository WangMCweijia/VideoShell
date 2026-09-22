package com.videoshell.data.net

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException

/**
 * 应用内**自更新**：读一份公开的 `version.json`，比版本号，给出该下哪个包。
 *
 * ## 为什么要有它
 *
 * 在这之前，装新版只有一条路：拿数据线连电脑，或者把 APK 从聊天窗口里翻出来点安装。
 * 结果是「明明修好了，用户手上还是老版本」—— 而绝大多数"这个 bug 还在"的反馈，
 * 根因都在这（同一版本号的两代包也是同一个坑，见 PITFALLS §4.38）。
 *
 * ## 清单从哪来：**两条通道**，不是一条
 *
 * 最初的版本只有一条路：直达 `github.com/.../releases/latest/download/version.json`
 * （`MANIFEST_URL`）。它在能连上 `github.com` 的网络里最短 —— 一次请求就拿到清单。
 *
 * 但 2026-09-22 在开发机上量到一组对照数据（同一时刻、同一台机器）：
 *
 * | 主机 | 结果 |
 * |---|---|
 * | `github.com:443` | **连接超时（5s 打不通）** |
 * | `api.github.com:443` | 0.30s ✅ |
 * | `objects.githubusercontent.com:443` | 0.24s ✅ |
 * | `release-assets.githubusercontent.com:443` | 0.36s ✅ |
 *
 * 也就是说：更新检查的**第一跳**恰好落在唯一打不通的那个主机上 —— 清单取不到、
 * 后面的包更下不下来。用户看到的是「检查更新失败」或者干脆什么都没有，而代码看起来
 * 毫无问题（URL 是对的、清单内容是对的、sha256 是对的）。这类"每条规则都成立、
 * 组合起来却不可用"的故障，只能靠**对照实验**发现，读代码读不出来。
 *
 * 所以现在按顺序试两条通道（[fetchManifest]）：
 *
 * 1. **`api.github.com`**（[RELEASE_API]）—— 它给出 latest Release 的 assets 列表，
 *    再按资产地址取清单正文。它顺便还给了 GitHub 自己算的 `digest`，于是"清单与包
 *    是不是同一次发布"多了一道**独立来源**的交叉校验（见 [check]）。
 * 2. **裸链**（[MANIFEST_URL]）—— `api.github.com` 未认证限流（60 次/小时/IP）或
 *    不可用时兜底。这条同时也是"`github.com` 能通"的网络里最省事的那条。
 *
 * 两条都失败时，报错必须**把两条通道各自的错误都写上** —— 否则用户只知道"失败了"，
 * 而"换个网络/挂代理"这个唯一有效的动作就传不到他那儿。
 *
 * ## 第三层：加速镜像（v1.0.56）—— 但它**不扩展信任**
 *
 * v1.0.56 起还有第三条兜底（[UpdateMirror]）：8 个公共 URL 前缀反代。它们同时解决
 * 两个问题 —— `github.com` 整个被阻断时的**可达性**，以及国际出口只有几十 KB/s 时的
 * **速度**（实测直连 74 KB/s vs 镜像 1860 KB/s，8 MB 的包 110 秒 vs 5 秒）。
 *
 * 但镜像只是**字节搬运工**，不能成为信任源：清单里的 sha256 是整个自更新的信任锚点，
 * 而一个镜像想篡改清单是做得到的。所以镜像通道取清单受两道约束：
 *
 * 1. **双源一致才接受**：至少 2 个**不同**镜像返回的清单在 `versionCode / sha256 /
 *    apkUrl` 上完全一致（两个无关联的公共镜像合谋篡改，不在威胁模型内）；
 *    只有一个镜像应答 ⇒ **拒绝**，报错写明原因。
 * 2. 清单里的 `apkUrl` 白名单仍然只认 GitHub 本尊（镜像主机不进白名单）—— 镜像可以
 *    帮我们"搬字节"，但**指向哪儿**不由它决定。
 *
 * ## 清单里有什么、以及两条防线
 *
 * CI 在打 tag 发版时**顺手生成** `version.json`，和 APK 一起传进同一个 Release：
 * `.../releases/latest/download/version.json` 是个**固定地址**（latest 永远指向最新那个
 * 正式 Release），所以 App 里不需要跟着版本号改 URL。清单里的 `apkUrl` 则指向**带版本号**
 * 的那个 asset —— 这样清单和包永远成对，不会出现"清单说 1.0.55、下下来的却是 1.0.54"。
 *
 * ⚠️ 前提是仓库可被匿名读取（public）。private 仓库的 release asset 对未认证请求返回 404，
 * 这一点很容易被误读成"没发出去" —— 所以失败原因要分开报，别都说成"检查更新失败"。
 *
 * 1. **只收 https + GitHub 域**的下载地址：清单本身走 HTTPS，但把"任意 URL 也能装"
 *    这条路堵掉 —— 否则清单里一个笔误就能让 App 去下载第三方地址。
 * 2. **下载后校验 sha256**：清单里带摘要，包下完先算再比对，对不上直接丢弃。
 *    这一条才是真正防"下到一半/被替换"的判据（见 [UpdateDownloader]）。
 */
object UpdateChecker {

    /** 通道 2：固定地址，latest 永远指向最新的正式 Release，所以这里不用跟版本号 */
    const val MANIFEST_URL =
        "https://github.com/WangMCweijia/VideoShell/releases/latest/download/version.json"

    /**
     * 通道 1：`api.github.com` 的 latest Release。
     *
     * 为什么它能救场：`github.com` 与 `api.github.com` 是**两个不同的主机/边缘节点**，
     * 前者的可达性在部分地区/时段会单独变差（上表那组数据就是）。两者同时挂的概率远小于单点。
     */
    const val RELEASE_API =
        "https://api.github.com/repos/WangMCweijia/VideoShell/releases/latest"

    private const val ASSET_MANIFEST = "version.json"
    private const val ASSET_APK = "app-release.apk"

    /** 取 Release 元数据要显式要 JSON（`Http` 默认发的是浏览器文档 Accept） */
    private const val ACCEPT_JSON = "application/vnd.github+json"

    /**
     * 取**资产内容**必须用它。
     *
     * 不带的话 `api.github.com/.../releases/assets/<id>` 返回的是那段资产的 **JSON 元数据**
     * （名字、大小、digest……），不是资产本身 —— 于是"`version.json`"下下来是个 JSON 包 JSON，
     * "apk"下下来是个几百字节的文本。症状会被误读成"清单格式不对"。
     */
    private const val ACCEPT_OCTET = "application/octet-stream"

    /** 允许出现的下载域名 —— 少写一个是**故意的**：出错时要落到"失败"，不是"装了个别的" */
    private val ALLOWED_HOSTS = listOf("github.com", "api.github.com",
        "objects.githubusercontent.com", "github-releases.githubusercontent.com",
        "release-assets.githubusercontent.com")

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val size: Long,
        val notes: String,
        /**
         * 备用下载地址（`api.github.com` 的资产地址）。走裸链通道时为 null。
         *
         * 放在**候选列表的第一位**（见 [UpdateDownloader]）—— 不是因为 api 更"官方"，
         * 而是因为上表那组数据：这个主机在这条网络上真的通，而 `github.com` 真的不通。
         */
        val apkApiUrl: String? = null,
        /**
         * 清单是经哪条线路拿到的（`api.github.com` / `github.com` / 镜像短名）。
         *
         * 写进确认弹窗：镜像线路意味着"清单出自两个镜像的一致答案"而非 GitHub 本尊 ——
         * 这件事应当**可见**，而不是只在出错时才说。
         */
        val via: String = ""
    )

    sealed class State {
        /** 已是最新（或清单里的版本不比本机新） */
        object Latest : State()
        data class Newer(val info: UpdateInfo) : State()

        /**
         * 失败。`reason` 要能区分"没发版/仓库不可读/没网络/清单格式不对"以及**走了哪条通道**——
         * 一律说"检查更新失败"等于把排查成本全推给用户。
         */
        data class Failed(val reason: String) : State()
    }

    /** 清单是哪条通道取到的 —— 报错与自检都要能说清"走的哪条路" */
    enum class Route { Api, Direct, Mirror }

    /** 一次取到的清单，连同它的备用下载地址与"包摘要"（用于同源交叉校验） */
    class Manifest(
        val body: String,
        val route: Route,
        val apkApiUrl: String?,
        /** GitHub 给 APK 资产算的摘要（`sha256:` 前缀已去掉）；裸链通道拿不到，为 null */
        val apkDigest: String?,
        /** 线路说明（镜像通道 = "经 X、Y 双源一致"；其它通道为 null） */
        val viaNote: String? = null
    )

    /**
     * 检查更新。
     *
     * @param currentVersionCode 本机 `PackageManager` 里的 versionCode
     * @param manifestUrl        默认 [MANIFEST_URL]（测试可注入）
     */
    suspend fun check(
        currentVersionCode: Int,
        manifestUrl: String = MANIFEST_URL
    ): State {
        // 用会抛异常的 get（而不是 getOrNull）：失败原因要能报出来。
        // getOrNull 把异常吞成 null，那样"仓库是 private"和"DNS 挂了"会变成同一句话，
        // 而这两者的下一步动作完全不同（改仓库可见性 vs 检查网络）。
        val got = try {
            fetchManifest(manifestUrl)
        } catch (e: Exception) {
            return State.Failed(e.message.orEmpty().ifBlank {
                "网络异常：${e.javaClass.simpleName} ${e.message.orEmpty()}"
            })
        }

        val parsed = try {
            parse(got.body, got.apkApiUrl)
        } catch (e: Exception) {
            return State.Failed("清单格式不对（通道 ${got.route}）：${e.message.orEmpty()}")
        }
        if (parsed == null) {
            return State.Failed("清单里没有可用的版本号/下载地址（通道 ${got.route}）")
        }

        // 「清单与包必须是同一次发布」（PITFALLS §4.44）：api 通道顺手拿到了 GitHub
        // 给这个 APK 资产算的摘要 —— 它与清单里的 sha256 必须一致。
        // 这是**独立来源**的交叉校验：清单由我们的 CI 生成，digest 由 GitHub 自己算，
        // 两边对不上就说明"清单指向的包"和"这个 Release 里的包"根本不是同一个。
        got.apkDigest?.let { d ->
            if (parsed.sha256.isNotBlank() && !parsed.sha256.equals(d, ignoreCase = true)) {
                return State.Failed(
                    "清单与安装包不是同一次发布（清单 ${parsed.sha256.take(12)}… / " +
                            "资产 ${d.take(12)}…）—— 拒绝下载"
                )
            }
        }

        return when {
            parsed.versionCode <= currentVersionCode -> State.Latest
            else -> State.Newer(parsed.copy(via = viaLabel(got)))
        }
    }

    /** 线路标签：报给用户看的"这份清单从哪来" */
    private fun viaLabel(m: Manifest): String = when (m.route) {
        Route.Api -> "api.github.com"
        Route.Direct -> "github.com"
        Route.Mirror -> m.viaNote ?: "加速镜像"
    }

    /**
     * 按顺序试三层取清单。**任何一层成功就停**（别把几层的耗时叠起来）：
     * api → 裸链 → 镜像（双源一致）。
     *
     * 失败时抛出的异常消息里同时带上各层各自的错误 —— 这是用户唯一能据以行动的
     * 信息（"层层都不通 ⇒ 多半是本机网络到不了 GitHub"）。
     */
    internal suspend fun fetchManifest(directUrl: String): Manifest {
        val apiErr: String
        try {
            return viaApi()
        } catch (e: Exception) {
            apiErr = describe(e)
        }
        try {
            val body = Http.get(directUrl)
            if (body.isBlank()) throw IOException("正文为空（若仓库被改成 private，未认证就读不到 asset）")
            return Manifest(body, Route.Direct, null, null)
        } catch (e: Exception) {
            val directErr = describe(e)
            // 第三层：镜像。单镜像的清单不可信 ⇒ 必须双源一致（见类注释「不扩展信任」）。
            val mirrorErr: String
            try {
                return viaMirrors(directUrl)
            } catch (me: Exception) {
                mirrorErr = me.message.orEmpty()
            }
            throw IOException(
                "三条通道都取不到更新清单：\n" +
                        "① api.github.com —— $apiErr\n" +
                        "② github.com —— $directErr\n" +
                        "③ 加速镜像 —— $mirrorErr\n" +
                        "→ 层层都不通时，多半是本机网络到不了 GitHub（换个网络 / WiFi 再试）"
            )
        }
    }

    /**
     * 第三层：经公共镜像取清单，**≥2 个独立镜像一致才接受**。
     *
     * 为什么是"一致"而不是"第一个应答的"：镜像能看到并改写清单正文 —— 而清单里的
     * sha256 是整个自更新的信任锚点。两个互不相关的镜像给出完全相同的内容，
     * 同时被篡改的概率才可以忽略；只有一个应答时**宁可拒绝**（报错说清楚），
     * 也不把信任押在一个第三方身上。
     *
     * 每个镜像**只试一次**（`Http.getOnce` + 短超时）：走到这一层说明前两层都已失败，
     * 用户在等结果，这里再多花两倍时间就成"点了没反应"。
     */
    private suspend fun viaMirrors(directUrl: String): Manifest {
        data class Hit(val prefix: String, val body: String, val info: UpdateInfo)
        val hits = ArrayList<Hit>(2)
        val errs = ArrayList<String>(2)
        for (p in UpdateMirror.MIRROR_PREFIXES) {
            if (hits.size >= 2) break          // 双源一致已凑齐，后面不必再试
            try {
                val body = Http.getOnce(UpdateMirror.wrap(p, directUrl), fast = true)
                if (body.isBlank()) throw IOException("正文为空")
                val info = parse(body) ?: throw IOException("清单里没有可用的版本号/下载地址")
                hits += Hit(p, body, info)
            } catch (e: Exception) {
                errs += "${UpdateMirror.labelOf(p)}：${describe(e)}"
            }
        }
        if (hits.size >= 2) {
            val (a, b) = hits
            val same = a.info.versionCode == b.info.versionCode &&
                    a.info.apkUrl == b.info.apkUrl &&
                    a.info.sha256.equals(b.info.sha256, ignoreCase = true)
            if (!same) {
                throw IOException(
                    "两个镜像给出的清单互相矛盾（${a.info.versionName} vs ${b.info.versionName}）" +
                            "—— 有镜像在篡改内容，已拒绝"
                )
            }
            val note = "经 ${UpdateMirror.labelOf(a.prefix)}、${UpdateMirror.labelOf(b.prefix)} " +
                    "双源一致"
            return Manifest(a.body, Route.Mirror, null, null, note)
        }
        val seen = hits.joinToString("、") { UpdateMirror.labelOf(it.prefix).orEmpty() }
        throw IOException(
            (if (seen.isBlank()) "所有镜像都不应答" else "只有 1 个镜像（$seen）应答，" +
                    "单镜像清单不可信，已按安全规则拒绝") +
                    (if (errs.isEmpty()) "" else "\n" + errs.joinToString("\n"))
        )
    }

    /**
     * 通道 1：经 `api.github.com` 拿 latest Release，再按资产地址取清单正文。
     *
     * 多花一次请求，换到两样东西：**一个能通的主机**，以及**一个独立来源的包摘要**。
     */
    private suspend fun viaApi(): Manifest {
        val rel = Http.get(RELEASE_API, headers = mapOf("Accept" to ACCEPT_JSON))
        val root = JsonParser.parseString(rel).asJsonObject
        val arr = root.getAsJsonArray("assets") ?: throw IOException("release JSON 里没有 assets")
        val byName = HashMap<String, JsonObject>()
        for (el in arr) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            val n = str(o, "name")
            if (n.isNotEmpty()) byName[n] = o
        }
        val vj = byName[ASSET_MANIFEST] ?: throw IOException("latest Release 里没有 $ASSET_MANIFEST")
        val apk = byName[ASSET_APK] ?: throw IOException("latest Release 里没有 $ASSET_APK")
        val vjUrl = str(vj, "url")
        val apkUrl = str(apk, "url")
        if (vjUrl.isBlank() || apkUrl.isBlank()) throw IOException("资产地址为空")
        if (!apkUrl.startsWith("https://")) throw IOException("资产地址不是 https")

        val body = Http.get(vjUrl, headers = mapOf("Accept" to ACCEPT_OCTET))
        val digest = str(apk, "digest").removePrefix("sha256:").trim()
        return Manifest(body, Route.Api, apkUrl, digest.ifBlank { null })
    }

    private fun describe(e: Exception): String =
        "${e.javaClass.simpleName}: ${e.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(80)}"

    private fun str(o: JsonObject, k: String): String =
        o.get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    /**
     * 解析清单。**任何一项不成立都返回 null 或抛异常**，绝不"能凑合就凑合" ——
     * 一个半残的清单会导致装上一个来路不明的包，比"检查失败"糟得多。
     *
     * @param apkApiUrl 备用下载地址（`api.github.com` 的资产地址）；同样要过白名单
     */
    internal fun parse(body: String, apkApiUrl: String? = null): UpdateInfo? {
        val o = JsonParser.parseString(body).asJsonObject
        val code = o.get("versionCode")?.takeIf { it.isJsonPrimitive }?.asInt ?: return null
        val name = o.get("versionName")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val apk = o.get("apkUrl")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        if (code <= 0 || apk.isBlank()) return null
        if (!apk.startsWith("https://")) {
            throw IllegalArgumentException("下载地址不是 https（拒绝明文传输安装包）")
        }
        if (!hostAllowed(apk)) {
            throw IllegalArgumentException("下载地址的域名「${hostOf(apk)}」不在白名单里")
        }
        val sha = o.get("sha256")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().lowercase()
        if (sha.isNotEmpty() && !Regex("^[0-9a-f]{64}$").matches(sha)) {
            throw IllegalArgumentException("sha256 不是合法的 64 位十六进制")
        }
        val size = o.get("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
        val notes = o.get("notes")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        // 备用地址是我们自己从 api.github.com 拼出来的，但仍然过一遍白名单 ——
        // "构造出来的地址"不该有豁免权，否则哪天上游返回个别的域就直接下回来了。
        if (!apkApiUrl.isNullOrBlank()) {
            if (!apkApiUrl.startsWith("https://")) {
                throw IllegalArgumentException("备用下载地址不是 https")
            }
            if (!hostAllowed(apkApiUrl)) {
                throw IllegalArgumentException("备用下载地址的域名「${hostOf(apkApiUrl)}」不在白名单里")
            }
        }
        return UpdateInfo(code, name, apk, sha, size, notes, apkApiUrl)
    }

    private fun hostOf(url: String): String =
        Regex("^https://([^/]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
            ?.lowercase().orEmpty()

    /**
     * 白名单。**镜像地址先剥皮再判**：镜像主机本身不进 [ALLOWED_HOSTS]，
     * 合法形态只有"GitHub 本尊"和"`已知镜像前缀 + GitHub 本尊`"两种 ——
     * `wrap(镜像, https://evil.com/x.apk)` 在这里被剥出内层主机后照样拒绝。
     */
    private fun hostAllowed(url: String): Boolean {
        val inner = UpdateMirror.innerOf(url) ?: url
        if (UpdateMirror.isMirrored(url) && UpdateMirror.prefixOf(url) == null) return false
        val host = hostOf(inner)
        return ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }
    }
}
