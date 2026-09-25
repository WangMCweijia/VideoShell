package com.videoshell.data.pan

import com.videoshell.data.net.Http
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * ## 夸克 / UC 云盘（同一套后端的两个品牌）
 *
 * ### 为什么是一个类而不是两个文件
 *
 * 实测（2026-09-23）：
 *  - `https://drive-pc.quark.cn/1/clouddrive/share/sharepage/token`（夸克）
 *  - `https://pc-api.uc.cn/1/clouddrive/share/sharepage/token`（UC）
 *  两个 host 对同一个请求体返回**同样的信封**（`{status,code,message,data}`，
 *  `code:41006` = 分享不存在，`code:31001` = 需要登录）。UC 云盘就是夸克云盘换了品牌，
 *  路径、参数、`pr=ucpro&fr=pc` 全都一样。写成两份只会让"改一处漏一处"变成必然。
 *
 * ### 实测确认的接口事实（决定了整个 P0 的形状）
 *
 * | 步骤 | 端点 | 匿名 | 已登录 |
 * |---|---|---|---|
 * | 取分享令牌 | `POST /share/sharepage/token` | ✅ 200 + `stoken` | ✅ |
 * | 列分享目录 | `GET /share/sharepage/detail` | ✅ 200 + 文件树 | ✅ |
 * | 转存 | `POST /share/sharepage/save` | ❌ **401 `code:31001 require login [guest]`** | ✅ 200 |
 * | ~~取直链~~ | `POST /file/download` | ❌ 401 | ❌ **400 `code:23018 download file size limit`** |
 * | **取播放入口** | `POST /file/v2/play` | ❌ | ✅ **200 → m3u8** |
 *
 * ⇒ 「登录放软件里」不是附加功能，而是**这套方案能成立的前提**。
 * ⇒ 详情页可以匿名把整季集数展开出来（体验好），点播时才需要登录。
 *
 * ### ⚠️ `file/download` 是死路，别再回头看（2026-09-23 实测）
 *
 * 带**真实登录凭据**打 `file/download`，对分享里的文件**一律**回
 * `400 code:23018 download file size limit[<fid>]` —— 连 615MB 的那个"最小文件"也照拒，
 * 所以不是"文件太大"，是这条接口对普通账号已经不给了。六种组合全一样，别再试：
 * 换 host（`drive-pc` / `drive-m`）、换 UA（桌面 Chrome / 夸克 App UA）、
 * 换 Referer、加 `?pf=1&support_https=1`、加 `group_id`。
 *
 * 正确的入口是 `POST /file/v2/play`：它返回**转码后的 HLS 播放列表**
 * （`video_list[].video_info.url` → `video-play-*.drive.quark.cn/…/media.m3u8`）。
 * 文件多大都不影响 —— 接口给的是分片清单（2.9GB 的片子 = 1003 个分片），
 * 播放器按需拉，**不需要先把整个文件下下来**。这也是为什么 P2 的"本地代理"不必做：
 * 分片只认 Cookie（详见 [mediaHeaders]），ExoPlayer 自己就能直连拉。
 *
 * 另外三条被实测否掉的假设，别再试：
 *  - `GET /file/download` → **405**（它只收 POST）；
 *  - `share/sharepage/{download,file/download,video/play,play}` 四个"看起来该有"的
 *    分享取流端点 → 全部 **404**（不存在）。转存这一步**省不掉**（分享 fid 不能直接喂给
 *    `file/play`）；
 *  - `/file/v2/get_video_play_info`、`/file/transcode` → **404**（不存在）。
 *
 * ### 转存后的清理（必须做对，否则用户网盘会越积越多）
 *
 * 取播放入口必须先转存（接口强制），所以拿到 URL 后我们**立刻删掉**这份产物。
 * 实测**删除不影响已签发的 URL**（删完再取 m3u8 与 ts 都还是 200）⇒ 不必等播完，
 * 立刻清干净。删除本身失败不影响播放。
 *
 * ⚠️ body 形状是 `{"filelist":[fid]}`。原先用的 `{"fids":[…],"pdir_fid":"0",…}` 恒回
 * `400 code:14001 Bad Parameter: [current_dir_fid,filelist 不能同时为空]`
 * —— 因为是"尽力删除、不判返回值"，这个 bug 只会表现为**用户网盘里悄悄堆满转存产物**，
 * 不会有任何报错。见 [delete]。
 */
class PanCloudDrive private constructor(
    override val type: PanType,
    private val apiBase: String,
    private val webBase: String,
    private val pr: String
) : PanProvider {

    companion object {
        fun quark() = PanCloudDrive(
            PanType.QUARK, "https://drive-pc.quark.cn/1/clouddrive", "https://pan.quark.cn/", "ucpro"
        )

        fun uc() = PanCloudDrive(
            PanType.UC, "https://pc-api.uc.cn/1/clouddrive", "https://drive.uc.cn/", "ucpro"
        )

        /**
         * 云盘 PC 接口用**桌面 UA**。
         *
         * 不能用 [Http.UA]（Android 移动 UA）：PC 接口的 `pr=ucpro&fr=pc` 已经在自称
         * "PC 端"，UA 却自称安卓浏览器，是自相矛盾的身份，风控链路（`req_id`/metadata 那套）
         * 会更容易盯上。这不是猜测，是"PC 接口就别装手机"这条常识在实践里的做法。
         */
        private const val PAN_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** 分享令牌缓存 20 分钟（接口自己也会给 `expired_at`，取两者更早的那个） */
        private const val STOKEN_TTL_MS = 20 * 60_000L

        /** 转存任务轮询上限 */
        private const val TASK_TRIES = 24
        private const val TASK_INTERVAL_MS = 800L

        /** 待清理队列：一次最多清几个（每次清理约 0.3s，别把播放启动拖长） */
        private const val SWEEP_MAX = 3

        /** 待清理队列上限（正常只有 1 个；真堆起来说明链路有问题，只保最新几个） */
        private const val PENDING_MAX = 32

        /** 一个 fid 在队列里最多待多久（超过就放弃 —— 防止永远删不掉的条目无限重试） */
        private const val PENDING_TTL_MS = 6 * 60 * 60 * 1000L
        /**
         * 播放器的分片请求**只需要**这几个 Cookie 键。
         *
         * 实测（2026-09-23，在 `media.m3u8` 与 `.ts` 上逐个组合打）：
         * `__puus` 单独 → 200；`__pus` 单独 → **412**；`__uid` 单独 → **412**；
         * `__pus+__puus` → 200；全量 1281 字符 → 200。
         * ⇒ 真正的凭据是 `__puus`，另两个带着只为兼容将来可能的变动。
         */
        private val MEDIA_COOKIE_KEYS = setOf("__pus", "__puus", "__uid")

        /** HLS 的 MIME（= `MimeTypes.APPLICATION_M3U8`）。取流方自己报，别让 media3 猜后缀。 */
        private const val MIME_HLS = "application/x-mpegURL"

        /**
         * 取流请求体里点名的分辨率（**复数、逗号分隔** —— v1.0.71 纠正的形状）。
         *
         * `low/high/super` 是当前网页端的词汇表；`normal` 不是（旧代码发的是
         * `resolution:"normal"`，服务端匹配不到码流 ⇒ 404）。三档都点名，图的是
         * "哪档能出就给哪档"，最终取哪档由响应里的 `default_resolution` 决定。
         */
        const val DEFAULT_RESOLUTIONS = "low,high,super"

        /** 取流尝试次数与补一次的间隔（刚转存完的文件偶尔"还没就绪"） */
        private const val PLAY_TRIES = 2
        private val PLAY_RETRY_DELAY_MS = longArrayOf(900L)

        /**
         * 媒体 Cookie 合成（**纯函数**，离线 harness 断言的就是它 —— `PanMediaCookieTest`）。
         *
         * 落盘快照打底（白名单键），[fresh]（jar 最新值，见 [Http.cookieValuesFor]）
         * **覆盖**同名键 —— `__puus` 是滚动凭据，快照必然越来越旧（v1.0.69 修 403）。
         * 合成后为空时退回整份快照：宁可多带，也不能因为键名没见过就播不了。
         */
        @JvmStatic
        fun mediaCookie(snapshot: String, fresh: Map<String, String>): String {
            val jar = LinkedHashMap<String, String>()
            for (part in snapshot.split(';')) {
                val i = part.indexOf('=')
                if (i <= 0) continue
                val k = part.substring(0, i).trim()
                if (k in MEDIA_COOKIE_KEYS) jar[k] = part.substring(i + 1).trim()
            }
            for (k in MEDIA_COOKIE_KEYS) {
                val v = fresh[k]
                if (!v.isNullOrBlank()) jar[k] = v
            }
            if (jar.isEmpty()) return snapshot
            return jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        /**
         * 服务端用来表达「**这条分享 / 这个文件已经没了**」的信封码（2026-09-24 逐条实测）。
         *
         * | 服务端 `message` | HTTP | `code` | 说明 |
         * |---|---|---|---|
         * | `ok` | 200 | 0 | 分享活着 |
         * | **`分享地址已失效`** | **404** | **41011** | 分享被删/取消 |
         * | **`文件不存在`** | **404** | **41004** | 分享在，但分享根指向的东西没了 |
         * | `分享不存在` | 404 | 41006 | 分享 id 本身不存在（打错/伪造） |
         * | `分享不存在`（**UC 侧**） | **403** | **41027** | UC 用 403 说这件事 |
         *
         * ⚠️ 这份表**推翻了两条曾经写进代码的"结论"**（这就是 `PanMediaCookieTest` E 组
         * 曾经钉住的东西）：
         *  - 「分享被删时回的是 HTTP 200 + `code:41006`」—— 只对"id 不存在"成立；
         *    真正被删是 **404 + `41011`**，于是我们嘴里的"404 从来不是分享失效"变成了一次**漏报**：
         *    用户看到「不是分享失效，可重试」而重试永远失败（蜡笔「天赐的声音第二季」，E54）。
         *  - 「403 ⇒ 需要登录」—— UC 对**分享不存在**回的就是 403 ⇒ 用户被引导去白重登一次。
         *
         * 所以 Dead 的判据必须是**服务端自己说的那句**（[deadEnvelope]），既不是 HTTP 状态码，
         * 也不是我们记住的某一个码。码会加，话不会乱说。
         */
        private val DEAD_CODES = setOf(41004, 41006, 41011, 41027)

        /**
         * 这个信封是不是在说「东西没了」（**纯函数**，可离线断言，`PanMediaCookieTest` J 组钉着）。
         *
         * 两层：**先认码**（实测表，最可靠），**再认话**（码变了时的安全网 ——
         * "失效/不存在" + "分享/文件/地址" 同时出现才认，避免把一句普通报错判成终态）。
         *
         * 为什么非要单独一个函数：Dead 是**终态语义**（提示换线路、不重试），判错方向比判不出更糟；
         * 而"哪些码算 Dead"这件事必须只有一处定义 —— 它已经被改错过一次了。
         */
        @JvmStatic
        fun deadEnvelope(code: Int, message: String): Boolean {
            if (code in DEAD_CODES) return true
            if (message.isBlank()) return false
            val gone = message.contains("失效") || message.contains("不存在") ||
                    message.contains("已删除") || message.contains("已被删")
            val what = message.contains("分享") || message.contains("文件") || message.contains("地址")
            return gone && what
        }

        /**
         * 从响应体里抠出信封的 `code` / `message` / `status`（**纯函数**，离线可断言）。
         *
         * ### 为什么不能用 `JSONObject`（v1.0.73 的真凶）
         *
         * 失败响应体经 [com.videoshell.data.net.Http.HttpError] 只保留**前 200 字符**
         * （`snippetOf`：message 必须以 `HTTP <code> ` 开头、body 在 `" | "` 之后、≤200 字符）。
         * 而夸克的错误信封**约 335 字节** —— 大头是尾部的 `metadata._g_group`：
         *
         * ```
         * {"status":404,"code":41004,"message":"文件不存在","req_id":"…","timestamp":…,
         *  "metadata":{"_t_group":"…","_g_group":"3:_s_vtp:1;7:_s_goback_app_pop:1;…"}}
         * ```
         *
         * 截到 200 字符时 JSON 正好断在 `metadata` 里 ⇒ `JSONObject` **必定抛异常** ⇒
         * 归因处拿到的信封恒为 null ⇒ 落到 [errorForHttp] 的 404 兜底（Broken，"先重试，
         * 仍失败就换线路"）。于是 v1.0.72 精心写的 [deadEnvelope] **在真机上一次都没生效过**：
         * 用户被引导去"重试"，而重试永远失败（蜡笔「天赐的声音第二季」= 404 + `41004`）。
         *
         * 好在**协议把 code/message 放在信封最前面**，正则从截断片段里照样抠得到。
         * 所以这里不依赖 JSON 解析器，只按字段名取值 —— 顺带让这条判据**离线可断言**。
         *
         * @return 抠不出 `code` 时返回 null（不是信封 / HTML 错误页 / 空体）。
         */
        @JvmStatic
        fun envelopeFields(body: String): PanEnvelope? {
            if (body.isBlank()) return null
            val code = Regex("\"code\"\\s*:\\s*(-?\\d+)").find(body)
                ?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val msg = Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
                ?.groupValues?.get(1).orEmpty()
            val status = Regex("\"status\"\\s*:\\s*(\\d+)").find(body)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return PanEnvelope(code, msg, status)
        }

        /**
         * HTTP 状态码 → 失败归类（**纯函数**，可离线断言，`PanMediaCookieTest` E 组钉着）。
         *
         * ⚠️ 它的定位是**兜底**：只有在服务端**一个字都没说**（没有可解析的信封）时才轮到它。
         * 有信封时以 [com.videoshell.data.pan.PanCloudDrive.deadEnvelope] 与
         * `isNeedLogin` 为准（见 `classify` 的"信封优先"）。
         *
         * 历史上这里写过 `404 -> PanError.Dead("分享链接已失效")` —— 被真机证伪过一次
         * （活着的分享被判死），于是整条被反转成"404 永远不是分享失效"，**又被证伪了一次**
         * （夸克对已删分享就是回 404，见 [DEAD_CODES]）。两次错误的共同点是**想用一个状态码
         * 去回答一个状态码答不了的问题**。所以现在的 404 文案：
         *  - **不断言任何一端**（既不说"失效"、也不说"不是失效"）；
         *  - 给出**可执行动作**（先重试，仍失败就换线路），这才是没有归因时唯一诚实的话。
         *
         * @param step 失败发生在哪一步（`取分享令牌` / `列目录` / `取播放入口`…）。
         *   没有它，用户手里就只剩「接口回 HTTP 404」—— 而我们连**哪个接口**都不知道
         *   （v1.0.71 的真机诊断就是卡在这里，白追了一轮）。
         */
        @JvmStatic
        @JvmOverloads
        fun errorForHttp(code: Int, type: PanType, step: String = ""): PanError {
            val at = if (step.isBlank()) "" else "（${step}时）"
            return when {
                code == 401 -> PanError.NeedLogin(needLoginMsg(type), type)
                // 403 保持"要登录"的兜底语义（无害且保住了真实的凭据过期路径）；
                // 但**信封优先**会让 UC 的 403 + `41027 分享不存在` 走 Dead 而不是这里。
                code == 403 -> PanError.NeedLogin(needLoginMsg(type), type)
                code == 404 -> PanError.Broken(
                    "${type.label}接口回 HTTP 404$at（服务端没说原因；可能是风控拒绝，" +
                            "也可能这条分享/文件已经没了 —— 先重试一次，仍失败就换线路）"
                )
                code in 500..599 -> PanError.Broken(
                    "${type.label}服务端错误（HTTP $code）$at —— 稍后重试"
                )
                code > 0 -> PanError.Broken("${type.label}请求失败（HTTP $code）$at")
                else -> PanError.Net("${type.label}网络请求失败$at")
            }
        }

        /**
         * 取流请求体（v1.0.71）。
         *
         * 判据来自**当前网页端的实现本身** —— 2026-09-24 直接下载并读了
         * `cloud-drive-web/4.6.7/share.js`（`pan.quark.cn/s/<id>` 真正加载的那份）：
         *
         * ```js
         * POST <host>/1/clouddrive/file/v2/play
         * data: Object.assign({ fid, resolutions: (res||["low"]).join(","),
         *                       supports: "fmp4,m3u8" }, rest)
         * ```
         *
         * ⇒ 是 `resolutions`（**复数、逗号分隔**）+ `supports`；我们原先发的
         * `resolution`（单数）/ 值 `normal` 是过时形状，服务端匹配不到码流 ⇒ **HTTP 404**。
         *
         * ⚠️ 同一次下载还确认了另外两件事，别再回头试：
         *  - `file/delete` 的 `action_type:2`（= `E.ASYNC`）**是对的**（bundle 里 `SYNC=1/ASYNC=2`）；
         *  - 网页端**没有** `GET /file/play` 这条退路（bundle 里 0 次）⇒ 它已下架。
         *
         * 只声明 `m3u8`（HLS 清单，ExoPlayer 直接吃）与 `fmp4`；不声明 `preview_url`
         * 那条游客试看通道（它给的是预览图，见 [urlOf]）。
         */
        @JvmStatic
        @JvmOverloads
        fun playBody(fid: String, resolutions: String = DEFAULT_RESOLUTIONS): String =
            JSONObject()
                .put("fid", fid)
                .put("resolutions", resolutions)
                .put("supports", "m3u8,fmp4")
                .toString()

        /**
         * 取流失败值不值得补一次（**纯函数**，`PanMediaCookieTest` 钉着）。
         *
         * 判据就是 [isTerminal] —— **与目录展开的补一次共用同一条规则**（原先这里和
         * `PanResolver.expand` 各写一遍 `when`，正是"改一处漏一处"的形状）。
         */
        @JvmStatic
        fun retryablePlay(e: PanError?): Boolean = e != null && !e.isTerminal()
    }

    override val supported: Boolean get() = true

    @Volatile
    private var err: PanError? = null

    override val lastError: PanError? get() = err

    /** 分享令牌缓存：匿名可得、与登录态无关、且短时间内稳定 ⇒ 值得缓存（直链不缓存） */
    private val stokenCache = ConcurrentHashMap<String, Pair<String, Long>>()

    /**
     * 待清理的转存产物（fid → 首次记账时刻）。
     *
     * ### 为什么必须"排队"，而不是"删一次就算了"
     *
     * `file/delete` 对**刚被 `file/v2/play` 打开过播放会话**的文件会连续回
     * `500 code:15000 inner error`。2026-09-23 逐条实测（同一份凭据、同一个 body）：
     *
     * | 序列 | delete 结果 |
     * |---|---|
     * | save → **不调 play** → 立刻 delete | **200 code=0** ✅ |
     * | save → `v2/play` → **立刻** delete | **500 ×4** ❌（重试没用） |
     * | save → `v2/play`（只调 play、没拉流）→ 等 **45 秒** → delete | **200 code=0** ✅ |
     * | save → `v2/play` + **真拉 m3u8/ts** → 等 45 秒 → delete | **500** ❌ |
     * | 同上 → 等 **120 秒** → delete | **500** ❌ |
     * | 同上 → 约 **2 分钟** → delete | **200 code=0** ✅ |
     *
     * ⇒ 是**播放会话锁住了文件**（且**拉过流**会锁得更久），之后自己松开。
     * 不是"刚转存完数据没落盘"（分支 A：不播就能立刻删）。所以"拿到 URL 就立刻删"是
     * **必败路径** —— 硬删只会既留残留、又白等一轮重试。
     *
     * ⚠️ **窗口有抖动、没有可靠秒数**（120s 失败 / ~140s 成功 / 另一轮 ~120s 又成功）
     * ⇒ 别改成 `delay(120_000)` 这类"定时删"。**排队重试对任何窗口都成立**，定时器不是。
     *
     * 改成：本次先把 fid 记下来，**下次取流/列目录/看账号之前顺手清**（那时它已经"凉"了）。
     * 效果：盘里最多同时存在 **1** 个转存产物（上一次那集），并在下次网盘操作时被清掉。
     *
     * ⚠️ 只在内存里：进程被杀时队列会丢，于是"最后一次播放留下的那 1 个"就清不掉了。
     * 这是刻意的取舍 —— 落盘要碰存储层，而它换来的只是"少 1 个文件"。
     */
    private val pendingDelete = ConcurrentHashMap<String, Long>()

    // ------------------------------------------------------------------ 契约

    override suspend fun list(link: PanLink, fid: String?): List<PanFile> {
        err = null
        // 顺手清上次没删掉的转存产物（此刻它已经"凉"了）。没登录态时跳过 —— 没有凭据删不了。
        DriveStore.cookie(type)?.takeIf { it.isNotBlank() }?.let { sweepPending(it) }
        val st = stoken(link) ?: return emptyList()
        val url = buildString {
            append(apiBase).append("/share/sharepage/detail?").append(q())
            append("&pwd_id=").append(u(link.id))
            append("&stoken=").append(u(st))
            append("&pdir_fid=").append(u(fid ?: "0"))
            append("&force=0&_page=1&_size=200")
            append("&_sort=").append(u("file_type:asc,file_name:asc"))
        }
        val body = getJson(url, null, "列目录") ?: return emptyList()
        val o = json(body) ?: return emptyList()
        if (o.optInt("code", -1) != 0) {
            note(o)
            return emptyList()
        }
        val arr = o.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<PanFile>(arr.length())
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val id = it.optString("fid")
            if (id.isBlank()) continue
            out.add(
                PanFile(
                    fid = id,
                    name = it.optString("file_name"),
                    // 实测 `dir` 是布尔；`file` 是它的反向字段，两个都认更稳
                    dir = it.optBoolean("dir", false) || !it.optBoolean("file", true),
                    size = it.optLong("size", 0L),
                    token = it.optString("share_fid_token")
                )
            )
        }
        return out
    }

    override suspend fun stream(ref: PanRef): PanStream? {
        err = null
        val ck = DriveStore.cookie(type)
        if (ck.isNullOrBlank()) {
            err = PanError.NeedLogin(needLoginMsg(type), type)
            return null
        }
        // 先清上次留下的（此刻已"凉"）—— 效果是盘里最多只积压 1 个转存产物
        sweepPending(ck)
        val saved = saveToMyDrive(ref, ck) ?: return null
        // 转存产物只有一个用途：换一个**已签名的播放入口**。拿到 URL 之后就没用了
        // （实测：删掉转存文件后，已签发的 m3u8 与 ts 仍返回 200）。
        //
        // ⚠️ 但**不能立刻删** —— 见 [pendingDelete] 的实测表：`v2/play` 打开播放会话后，
        // 该文件在几十秒内删不掉（`500 code:15000`，连试 4 次全败）。
        // 所以这里只"记账"，等下次取流时再清。
        val url = playUrl(saved, ck)
        if (url == null) {
            // 没播成 ⇒ 没有播放会话锁着它 ⇒ 立刻删是可以成的（同 [pendingDelete] 分支 A）
            // 只有 5xx/网络才记账（4xx = 别再试，盘里要么干净要么参数错）
            if (delete(saved, ck) !in 200..499) rememberPending(saved)
            return null
        }
        rememberPending(saved)
        return PanStream(
            url = url,
            headers = mediaHeaders(ck),
            hls = true,
            mime = MIME_HLS
        )
    }

    override suspend fun verify(): DriveState {
        val ck = DriveStore.cookie(type)
        if (ck.isNullOrBlank()) return DriveState.None
        // 用户主动打开「网盘账号」时也顺手清一次（这里没有等待路径，清得掉就清）
        sweepPending(ck)
        return try {
            val body = Http.get("$apiBase/member?${q()}", referer = webBase, ua = PAN_UA,
                headers = cookie(ck))
            val o = runCatching { JSONObject(body) }.getOrNull()
            when {
                o == null -> DriveState.Valid                       // 不是 JSON：别乱判过期
                o.optInt("code", -1) == 0 -> {
                    DriveStore.clearExpired(type)
                    DriveState.Valid
                }
                isNeedLogin(o.optInt("code", 0), o.optString("message")) -> {
                    DriveStore.markExpired(type)
                    DriveState.Expired
                }
                // 别的错误码（未开通会员 / 接口小改）**不代表凭据无效** —— 判过期会让用户白重登
                else -> DriveState.Valid
            }
        } catch (e: Exception) {
            if (httpCode(e) == 401 || httpCode(e) == 403) {
                DriveStore.markExpired(type)
                DriveState.Expired
            } else {
                DriveState.Valid
            }
        }
    }

    // ------------------------------------------------------------------ 分享令牌

    private suspend fun stoken(link: PanLink): String? {
        val k = link.type.key + "|" + link.id + "|" + link.pwd
        stokenCache[k]?.let { (v, exp) ->
            if (System.currentTimeMillis() < exp) return v
        }
        val req = JSONObject().put("pwd_id", link.id).put("passcode", link.pwd).toString()
        val resp = postJson("$apiBase/share/sharepage/token?${q()}", req, null, "取分享令牌")
            ?: return null
        val o = json(resp) ?: return null
        if (o.optInt("code", -1) != 0) {
            note(o)
            return null
        }
        val d = o.optJSONObject("data") ?: return null
        val st = d.optString("stoken")
        if (st.isBlank()) {
            err = PanError.Broken("${type.label}没有返回分享令牌（接口可能变了）")
            return null
        }
        val expAt = d.optLong("expired_at", 0L)
        val ttl = minOf(
            System.currentTimeMillis() + STOKEN_TTL_MS,
            if (expAt > 0L) expAt else Long.MAX_VALUE
        )
        stokenCache[k] = st to ttl
        return st
    }

    // ------------------------------------------------------------------ 转存 → 直链

    /** @return 转存后**在用户网盘里**的 fid */
    private suspend fun saveToMyDrive(ref: PanRef, ck: String): String? {
        val st = stoken(ref.link) ?: return null
        val req = JSONObject().apply {
            put("fid_list", JSONArray().put(ref.fid))
            put("fid_token_list", JSONArray().put(ref.token))
            put("to_pdir_fid", "0")
            put("pwd_id", ref.link.id)
            put("stoken", st)
            put("pdir_fid", "0")
            put("scene", "link")
            put("_page", 1)
            put("_size", 200)
            put("_fetch_banner", 1)
            put("_fetch_share", 1)
            put("_fetch_total", 1)
            put("_sort", "file_type:asc,updated_at:desc")
        }.toString()
        val resp = postJson("$apiBase/share/sharepage/save?${q()}", req, ck, "转存到我的网盘")
            ?: return null
        val o = json(resp) ?: return null
        if (o.optInt("code", -1) != 0) {
            note(o)
            return null
        }
        val d = o.optJSONObject("data") ?: return null
        // 有的实现直接回文件 id，有的要轮询任务
        topFid(d)?.let { return it }
        val taskId = d.optString("task_id")
        if (taskId.isBlank()) {
            err = PanError.Broken("${type.label}转存没有返回任务 id（接口可能变了）")
            return null
        }
        return pollTask(taskId, ck)
    }

    private suspend fun pollTask(taskId: String, ck: String): String? {
        for (i in 0 until TASK_TRIES) {
            delay(if (i == 0) 350L else TASK_INTERVAL_MS)
            val body = getJson("$apiBase/task?${q()}&task_id=${u(taskId)}", ck, "等转存任务")
                ?: continue
            val o = json(body) ?: continue
            if (o.optInt("code", -1) != 0) {
                note(o)
                return null
            }
            val d = o.optJSONObject("data") ?: continue
            when (d.optInt("status", 0)) {
                2 -> {
                    topFid(d)?.let { return it }
                    err = PanError.Broken("${type.label}转存完成但没有返回文件 id")
                    return null
                }
                3 -> {
                    err = PanError.Broken(
                        "${type.label}转存失败：${d.optString("message").take(60)}"
                    )
                    return null
                }
                // 0/1 = 排队/进行中，继续等
            }
        }
        err = PanError.Broken("${type.label}转存超时（任务未在 ${TASK_TRIES * TASK_INTERVAL_MS / 1000} 秒内完成）")
        return null
    }

    private fun topFid(d: JSONObject): String? {
        val arr = d.optJSONObject("save_as")?.optJSONArray("save_as_top_fids")
            ?: d.optJSONArray("save_as_top_fids")
            ?: return null
        val f = arr.optString(0)
        return f.takeIf { it.isNotBlank() }
    }

    /**
     * 取**播放入口**（转码后的 m3u8）。
     *
     * 首选 `POST /file/v2/play` —— ⚠️ **请求体形状在 v1.0.71 纠正过，改之前先读 [playBody]**：
     * 旧代码发的是 `{"fid":…,"resolution":"normal"}`（**单数** + 值 `normal`），而当前网页端
     * （cloud-drive-web 4.6.7 的 `share.js`，2026-09-24 直接扒 bundle 得到的）发的是
     * `{"fid":…,"resolutions":"low","supports":"fmp4,m3u8"}` ⇒ 服务端找不到匹配的码流，
     * **回 HTTP 404**。真机症状：save/task 全 200，三个带 fid 的端点（v2/play、file/play、
     * file/delete）全 404/400 —— 看着像"fid 无效"，其实是请求体过时。
     *
     * 它还自报 `default_resolution` 与每档的 `right`/`member_right`/`trans_status`/
     * `accessable`/`width`/`bitrate`/`size` —— **按服务器自报的默认档取**，比我们猜可靠。
     *
     * 退路 `GET /file/play?resolution=raw|low`：v1.0.65 记的是「网页端已 0 引用、实测 404」，
     * 但 **v1.0.75 真机实测它活着**（回 HTTP 200 + 完整信封 `code 21001 file not found`）——
     * 旧结论对这个账号已不成立，别再当"死接口"看。它仍只是**兜底**：它的结论不许顶掉主端点
     * （见 [playUrlOnce] 的快照/还原，§4.75）。
     */
    private suspend fun playUrl(fid: String, ck: String): String? {
        var last: PanError? = null
        for (attempt in 0 until PLAY_TRIES) {
            if (attempt > 0) delay(PLAY_RETRY_DELAY_MS[attempt - 1])
            err = null
            playUrlOnce(fid, ck)?.let { return it }
            last = err ?: PanError.Broken("${type.label}没有返回播放入口（接口可能变了）")
            // 刚转存完就取流，服务端偶尔还没就绪 ⇒ 补一次是划算的；
            // 但要登录 / 分享真失效补多少次都一样 ⇒ 立刻收手（见 [retryablePlay]）
            if (!retryablePlay(last)) break
        }
        err = last
        return null
    }

    /** 单次取流（不重试）。失败原因写进 [err]。 */
    private suspend fun playUrlOnce(fid: String, ck: String): String? {
        val resp = postJson("$apiBase/file/v2/play?${q()}", playBody(fid), ck, "取播放入口")
        val o = resp?.let { json(it) }
        if (o != null) {
            if (o.optInt("code", -1) == 0) {
                urlOf(o)?.let { return it }
            } else {
                // 留痕但不提前返回 —— 下面还有退路（note 会把"需登录/分享失效"分类好）
                note(o)
            }
        }
        // ⚠️ 主端点已经说出的结论必须**留住**。退路是历史接口（见 [playUrl] 头注），它的失败
        //    只会把更有信息量的那句盖掉 —— v1.0.74 真机的 toast 只剩「·退路（code 21001…）」，
        //    而 `v2/play` 到底回了什么**一点都没留下**（和 §4.74 同一种病，换了个洞）。
        //    所以退路只在主端点**没给出结论**（err == null）时才补位；拿到 URL 仍照常返回。
        val primary = err
        for (res in arrayOf("raw", "low")) {
            val body = getJson(
                "$apiBase/file/play?${q()}&fid=${u(fid)}&resolution=$res", ck, "取播放入口·退路"
            ) ?: continue
            val g = json(body) ?: continue
            if (g.optInt("code", -1) != 0) {
                if (primary == null) note(g)
                continue
            }
            urlOf(g)?.let { return it }
        }
        // 退路的失败（无论来自 [note] 还是 [classify] 的异常）都不许顶掉主端点那句
        if (primary != null) err = primary
        if (err == null) err = PanError.Broken("${type.label}没有返回播放入口（接口可能变了）")
        return null
    }

    /**
     * 从取流响应里挑出播放入口。认两种形状（同一个信封）：
     *  ① `data.video_list[]` —— 每档一条，地址在 `video_info.url`（也见过平铺的 `url`）；
     *     有 `default_resolution` 就优先那一档，否则取第一条非空的；
     *  ② `data.url` / `data.play_url` —— 信封直接给地址的简化形状。
     *
     * ⚠️ **绝不要退到 `data.preview_url`**：那是"游客试看"通道给的**预览图**
     * （实测 HEAD 回来 `Content-Type: image/webp`、14KB），拿它当视频流只会黑屏。
     */
    private fun urlOf(o: JSONObject): String? {
        val d = o.optJSONObject("data") ?: return null
        val want = d.optString("default_resolution")
        val list = d.optJSONArray("video_list")
        var first: String? = null
        if (list != null) {
            for (i in 0 until list.length()) {
                val e = list.optJSONObject(i) ?: continue
                val url = e.optJSONObject("video_info")?.optString("url").orEmpty()
                    .ifBlank { e.optString("url") }
                if (url.isBlank()) continue
                if (first == null) first = url
                if (want.isNotBlank() && e.optString("resolution") == want) return url
            }
        }
        first?.let { return it }
        for (k in arrayOf("url", "play_url")) {
            val v = d.optString(k)
            if (v.startsWith("http")) return v
        }
        return null
    }

    /**
     * 删掉转存产物。单发请求（**不重试**），返回 HTTP 状态码（网络失败 `-1`）。
     *
     * 调用方按状态码三分派（见 [sweepPending]）：
     *  - `200` ⇒ 成了；
     *  - `4xx` ⇒ **别再试**（`23004 文件已经删除`=盘里本来就干净；`14001` 参数错=重试也不会变）；
     *  - 其余（5xx / -1）⇒ **稍后再试**（实测"刚播过"必定 500，是播放会话锁着）。
     *
     * ⚠️ body 必须是 `{"action_type":2,"filelist":[…],"exclude_fids":[]}` 这一族，
     * **不能叫 `fids`**，且**不能**同时带 `current_dir_fid`。实测三种形状的报错正好把答案夹出来了：
     *  - `{"fids":[…],"pdir_fid":"0"}` → `14001 [current_dir_fid,filelist 不能同时为空]`
     *    （=> 参数名被忽略，它只认 `filelist`）
     *  - `{"filelist":[…],"current_dir_fid":"0"}` → `14001 […不能同时存在值]`
     *    （=> 两者只能给一个）
     *  - `{"action_type":2,"filelist":[…],"exclude_fids":[]}` → **200 code=0** ✅
     *
     * ⚠️ 它是**异步任务**：200 时给的是 `{"task_id":…,"finish":false}`，文件随后才消失
     * ⇒ 不能"调完立刻查目录没少就判失败"。
     *
     * ⚠️ 用 [Http.postJsonOnceRaw]（单发、不重试、交出状态码）而不是 [Http.postJsonOrNull]：
     * 后者会走 5xx 退避重试（0/400/1200ms，最多 3 次 ≈ 2.4s），而清理接在**用户等待路径**上
     * —— 让它白自旋去撞一个**必定失败**的请求，纯亏。
     *
     * ⚠️ 失败**不写 [err]**：流已经拿到了，把清理失败报成"播放失败"是错的。
     * 每次请求仍由 Http 记进 [com.videoshell.data.net.NetLog]，诊断看得到。
     */
    private suspend fun delete(fid: String, ck: String): Int {
        val req = JSONObject().apply {
            put("action_type", 2)
            put("filelist", JSONArray().put(fid))
            put("exclude_fids", JSONArray())
        }.toString()
        val (code, body) = Http.postJsonOnceRaw(
            "$apiBase/file/delete?${q()}", req,
            referer = webBase, ua = PAN_UA, headers = cookie(ck)
        )
        // 200 但信封 code != 0（接口小改）⇒ 也当成"稍后再试"（返回 200 会让 d 被移除，太乐观）
        if (code == 200 && json(body)?.optInt("code", -1) == 0) return 200
        return if (code == 200) -1 else code
    }

    /** 记下待清理的转存产物（见 [pendingDelete]） */
    private fun rememberPending(fid: String) {
        if (fid.isBlank()) return
        pendingDelete.putIfAbsent(fid, System.currentTimeMillis())
        if (pendingDelete.size > PENDING_MAX) {
            // 正常只有 1 个。真堆到上限说明清理链路坏了 —— 只保最新的，别无限占内存
            pendingDelete.entries.sortedBy { it.value }.take(pendingDelete.size - PENDING_MAX)
                .forEach { pendingDelete.remove(it.key) }
        }
    }

    /**
     * 顺手清掉待清理队列（放在取流 / 列目录 / 看账号**之前**：此刻它们已经"凉"了，删得掉）。
     *
     * 三分派见 [delete]。只有 5xx/网络才留在队列里等下次；4xx 直接出队
     * （否则 `23004 已删除` 这种"本来就没问题"的条目会永远重试）。
     * 超过 [PENDING_TTL_MS] 的条目放弃 —— 防止真有删不掉的东西永远拖着。
     */
    private suspend fun sweepPending(ck: String) {
        if (pendingDelete.isEmpty()) return
        val now = System.currentTimeMillis()
        for (fid in pendingDelete.keys.take(SWEEP_MAX)) {
            val born = pendingDelete[fid] ?: now
            if (now - born > PENDING_TTL_MS) {
                pendingDelete.remove(fid)
                continue
            }
            val code = delete(fid, ck)
            if (code == 200 || (code in 400..499)) pendingDelete.remove(fid)
        }
    }

    /**
     * 播放器（含每个分片请求）要带的头 —— **只给 Cookie，且只给必需的键**。
     *
     * 实测（2026-09-23，在 `media.m3u8` 与首个 `.ts` 上）：
     *  - 裸请求 / 只带 UA / 只带 Referer → **412 Precondition Failed**
     *  - 带 Cookie → **200**；ts 带 `Range: bytes=0-1023` → **206**（所以拖进度条没问题）
     *  ⇒ 校验**只认 Cookie**，UA 与 Referer 与成败无关。
     *
     * ⚠️ 但上面"只认 Cookie"的实测用的是**登录当天的新鲜 cookie**。`__puus` 会滚动
     * （每次 API 响应 `Set-Cookie` 下发新值），旧值在媒体域会失效 ⇒ **403**（不是 412！
     * E37 的 412 是"完全没带凭据"的形状，403 是"带了过期凭据"的形状 —— 两个码别混着归因）。
     * 所以这里的值**不直接用落盘快照**：jar 里有最新下发的键值
     * （`Domain=.quark.cn` 宽域 + [cookieJar] 不分桶 ⇒ jar 与服务端同步），用 [mediaCookie]
     * 把它盖到快照上。只查 [apiBase]：滚动 `Set-Cookie` 全部来自网盘 API 域的响应。
     *
     * 白名单合成后一个键都没有时退回整份 Cookie：宁可多带，也不能因为键名没见过就播不了。
     *
     * 顺带记一笔：这个头是**播放器**在发，`Http.mediaClient` 是另一个 OkHttp 实例，
     * 不会自动带 App 里存的凭据 —— 少了它，症状是"解析成功、一播就黑屏"。
     */
    private fun mediaHeaders(ck: String): Map<String, String> {
        val merged = mediaCookie(ck, Http.cookieValuesFor("$apiBase/member"))
        return mapOf("Cookie" to merged)
    }

    // ------------------------------------------------------------------ 内部

    private fun q() = "pr=$pr&fr=pc&uc_param_str="

    /**
     * URL 编码 —— **不要删这个函数，也不要绕过它**。
     *
     * `stoken` 是 base64 变体，**可能以 `+` 开头**（实测 2026-09-23：
     * `pan.quark.cn/s/dbc851025443` 的 stoken 就长着 `+JPpdVNi4j…`）。URL 是手拼的，
     * 漏掉这一层的话 `+` 会被服务端当成空格 ⇒ `detail` 报
     * `400 code:14001 Bad Parameter: [非法token]`。
     *
     * 真正的可怕之处是它**间歇发作**：某条分享能不能展开，取决于那一次拿到的 stoken
     * 里恰好有没有 `+` / `/` / `=` —— 同一部剧今天能展开、明天报"非法token"，
     * 看起来完全像站点抽风或接口改版，而不是自己漏了编码。
     * （2026-09-23 的 PC spike 脚本自己就漏了这层，把三条分享里的 `dbc851025443`
     * 误判成"分享已失效"，白追了一轮 —— 见 docs/PITFALLS.md §4.59。）
     */
    private fun u(s: String): String =
        runCatching { URLEncoder.encode(s, "UTF-8") }.getOrDefault(s)

    private fun cookie(ck: String?) =
        if (ck.isNullOrBlank()) emptyMap() else mapOf("Cookie" to ck)

    /**
     * 当前这一步叫什么（`取分享令牌` / `列目录` / `取播放入口`…），只用于**失败文案归因**。
     *
     * 为什么值得单开一个字段：v1.0.71 的真机自检里只有一句「夸克网盘接口回 HTTP 404」，
     * 而夸克链路上有 5 个端点都会回 404 —— 用户手里有了服务端的原话（`41004 文件不存在`），
     * 我们却还是不知道**它出自哪一步**，只好又去扒一遍 bundle。加上这四个字，
     * 下一次自检截图就能直接定位。
     */
    @Volatile
    private var step: String = ""

    private fun stepAt(): String = if (step.isBlank()) "" else "·$step"

    /** JSON 请求一律显式声明 Accept —— 云盘接口按它做内容协商，用 HTML 的 Accept 会被回 HTML */
    private val jsonAccept = mapOf("Accept" to "application/json, text/plain, */*")

    private suspend fun getJson(url: String, ck: String?, what: String = ""): String? = try {
        if (what.isNotBlank()) step = what
        Http.get(url, referer = webBase, ua = PAN_UA, headers = jsonAccept + cookie(ck))
    } catch (e: Exception) {
        err = classify(e)
        null
    }

    private suspend fun postJson(url: String, body: String, ck: String?, what: String = ""): String? =
        try {
            if (what.isNotBlank()) step = what
            Http.postJson(url, body, referer = webBase, ua = PAN_UA, headers = cookie(ck))
        } catch (e: Exception) {
            err = classify(e)
            null
        }

    private fun json(s: String): JSONObject? {
        val o = runCatching { JSONObject(s) }.getOrNull()
        if (o == null) err = PanError.Broken("${type.label}返回的不是 JSON（接口可能变了）")
        return o
    }

    /** HTTP 200 的信封（body 完整、可 JSON 解析）⇒ 取出三字段交给 [note]。 */
    private fun note(o: JSONObject) =
        note(o.optInt("code", -1), o.optString("message"), o.optInt("status", 0))

    /**
     * 信封级的错误（HTTP 200 的完整信封，或非 2xx 但 body 是**被截断**的信封）
     * —— **归因的主入口**，判据**唯一出处**。
     *
     * 为什么要把参数从 [JSONObject] 剥成三个裸值（v1.0.73）：失败响应体在
     * [com.videoshell.data.net.Http.HttpError] 里只剩前 200 字符（见 [envelopeFields]），
     * JSON 解析必失败 —— 于是"HTTP 200 的完整信封"与"被截断的失败信封"必须能走
     * **同一条**判据，否则 [deadEnvelope] 只在 200 那条路上有效。
     *
     * 顺序即优先级，三条都别调换：
     *  ① 要登录（`31001` / `require login`）—— 语义最强，且要落"凭据过期"的痕迹；
     *  ② 东西没了（[deadEnvelope]，`41004/41006/41011/41027`）—— 终态，提示换线路；
     *  ③ 其余按"接口异常"处理，并把服务端的原话带出来。
     */
    private fun note(code: Int, rawMsg: String, status: Int) {
        val msg = rawMsg.take(60)
        err = when {
            isNeedLogin(code, msg) -> {
                DriveStore.markExpired(type)
                PanError.NeedLogin(needLoginMsg(type), type)
            }
            // ⚠️ Dead 的判据在 [deadEnvelope]（**唯一出处**）。这里曾经只认 `41006`，
            //    并且额外把"信封自称 404"也判成 Dead —— 那是两次方向相反的错：
            //    前者**漏报**（`41011 分享地址已失效` 被我们说成"不是分享失效"），
            //    后者**误报**（活着的分享被判死）。见 [DEAD_CODES] 与 docs/PITFALLS.md E54。
            // Dead 是**终态**（不重试、直接劝换线路），文案里必须能看出「哪一步 + 信封 code」。
            // §4.71 ④ 的教训只补到了 Broken / Net 两条路，这条漏了 —— 于是用户截回来
            // 只剩一句"分享已失效"，而链路上 5 个端点（取分享令牌/列目录/转存/等任务/取播放入口）
            // 都可能报它，等于又回到"只好再扒一遍 bundle"。
            deadEnvelope(code, msg) ->
                PanError.Dead("分享已失效${stepAt()}（code $code：$msg）")
            // 信封自称 404 却没说是"没了"：按"接口异常"处理，**不断言原因**，只给动作
            status == 404 ->
                PanError.Broken("${type.label}接口信封回 404 / code $code：$msg（未识别的 404，可重试）")
            else -> PanError.Broken("${type.label}接口返回 $code：$msg")
        }
    }

    private fun isNeedLogin(code: Int, msg: String): Boolean =
        code == 31001 || code == 401 || msg.contains("require login", true)

    private fun httpCode(e: Exception): Int =
        Regex("HTTP (\\d{3})").find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun classify(e: Exception): PanError {
        // ① **信封优先**（v1.0.71 引入，v1.0.72 起成为归因主入口）：能拿到响应体时，
        //    服务端自己说的那句话比 HTTP 状态码准得多。`41004 文件不存在` /
        //    `41011 分享地址已失效` / `41027 分享不存在`(UC 用 403) 全都只能从 body 读出来
        //    —— 只按状态码猜，就是这两轮返工的成因。
        val he = e as? Http.HttpError
        // ⚠️ 这里**必须**用 [envelopeFields]（正则），不能用 `JSONObject`：
        //    `he.body` 是 `snippetOf` 的产物，只有**前 200 字符**，而夸克错误信封约 335 字节
        //    ⇒ JSON 截断、`JSONObject` 必抛 ⇒ `env` 恒为 null ⇒ [deadEnvelope] 永远走不到，
        //    真失效被当成"可重试"（v1.0.72 的蜡笔「天赐的声音第二季」就是这样）。
        val env = he?.body?.takeIf { it.trimStart().startsWith("{") }
            ?.let { envelopeFields(it) }
        if (env != null && env.code != 0) {
            note(env.code, env.message, env.status)
            err?.let { return it }
        }
        val code = he?.code ?: httpCode(e)
        // 只有 401 直接落"凭据过期"的痕迹；**403 不落** —— UC 对「分享不存在」回的就是 403，
        // 落下去会让用户的「网盘账号」页凭空变成"登录已过期"，白重登一次（E54）。
        // 真正的"要登录"由信封判定（`31001` / `require login`），那条路在 [note] 里。
        if (code == 401) DriveStore.markExpired(type)
        // 网络层失败（code == 0）没有状态码可归类，必须带上**哪一步** + 异常细节，
        // 否则诊断只剩一句空话（"网络请求失败"）而用户已经提供了完整自检
        if (code <= 0) {
            return PanError.Net(
                "${type.label}${stepAt()}：网络请求失败（${e.javaClass.simpleName} " +
                        "${e.message.orEmpty().take(60)}）"
            )
        }
        // 状态码归类时把服务端的原话一并带上（只对 Broken 拼 —— NeedLogin 的语义不能被改掉，
        // 「网盘账号」页靠它提示重登）
        val base = errorForHttp(code, type, step)
        val said = env?.message.orEmpty().take(60)
        return if (said.isBlank() || base !is PanError.Broken) base
        else PanError.Broken("${base.message}｜服务端：$said")
    }
}

/**
 * [PanCloudDrive.envelopeFields] 抠出来的信封字段。
 *
 * 单独一个类型（而不是 `Triple`）是为了让**离线 harness 的断言可读** ——
 * `PanMediaCookieTest` L 组钉的就是"被截断的 404 信封仍要抠得出 `41004`"。
 */
data class PanEnvelope(val code: Int, val message: String, val status: Int)
