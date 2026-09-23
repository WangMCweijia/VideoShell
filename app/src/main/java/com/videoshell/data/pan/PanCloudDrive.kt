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
        val body = getJson(url, null) ?: return emptyList()
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
        val resp = postJson("$apiBase/share/sharepage/token?${q()}", req, null) ?: return null
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
        val resp = postJson("$apiBase/share/sharepage/save?${q()}", req, ck) ?: return null
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
            val body = getJson("$apiBase/task?${q()}&task_id=${u(taskId)}", ck) ?: continue
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
     * 首选 `POST /file/v2/play`：它除了给 URL，还自报 `default_resolution`
     * （实测 `"super"`）以及每档的 `right`/`member_right`/`trans_status`/`accessable`/
     * `width`/`bitrate`/`size`。**按服务器自报的默认档取**，比我们猜"哪档能用"可靠得多；
     * 将来若要做"会员过期就降档"，判据也就在这几个字段里。
     *
     * 退路 `GET /file/play?resolution=raw|low`：实测两档都能出 URL（`raw` 是原画那份，
     * 与 v2 里的 `super` 指向同一个 m3u8 路径）。**只在前者拿不到 URL 时走**。
     */
    private suspend fun playUrl(fid: String, ck: String): String? {
        val req = JSONObject().put("fid", fid).put("resolution", "normal").toString()
        val resp = postJson("$apiBase/file/v2/play?${q()}", req, ck)
        val o = resp?.let { json(it) }
        if (o != null) {
            if (o.optInt("code", -1) == 0) {
                val d = o.optJSONObject("data")
                val want = d?.optString("default_resolution").orEmpty()
                val list = d?.optJSONArray("video_list")
                var first: String? = null
                if (list != null) {
                    for (i in 0 until list.length()) {
                        val e = list.optJSONObject(i) ?: continue
                        val url = e.optJSONObject("video_info")?.optString("url").orEmpty()
                        if (url.isBlank()) continue
                        if (first == null) first = url
                        if (want.isNotBlank() && e.optString("resolution") == want) return url
                    }
                }
                first?.let { return it }
            } else {
                // 留痕但不提前返回 —— 下面还有退路（note 会把"需登录/分享失效"分类好）
                note(o)
            }
        }
        for (res in arrayOf("raw", "low")) {
            val body = getJson("$apiBase/file/play?${q()}&fid=${u(fid)}&resolution=$res", ck)
                ?: continue
            val g = json(body) ?: continue
            if (g.optInt("code", -1) != 0) {
                note(g)
                continue
            }
            val vl = g.optJSONObject("data")?.optJSONArray("video_list") ?: continue
            for (i in 0 until vl.length()) {
                val url = vl.optJSONObject(i)?.optString("url").orEmpty()
                if (url.isNotBlank()) return url
            }
        }
        if (err == null) err = PanError.Broken("${type.label}没有返回播放入口（接口可能变了）")
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
     * 键也不必全给（见 [MEDIA_COOKIE_KEYS]）：`__puus` 单键就够。
     * 只发必需键有两个好处 —— 不依赖用户 Cookie 里其它键是否出现，
     * 也不把一份完整账号凭据平铺到 1000+ 个分片请求上。
     *
     * 白名单一个都没命中时**退回整份 Cookie**：宁可多带，也不能因为键名没见过就播不了。
     *
     * 顺带记一笔：这个头是**播放器**在发，`Http.mediaClient` 是另一个 OkHttp 实例，
     * 不会自动带 App 里存的凭据 —— 少了它，症状是"解析成功、一播就黑屏"。
     */
    private fun mediaHeaders(ck: String): Map<String, String> {
        val jar = LinkedHashMap<String, String>()
        for (part in ck.split(';')) {
            val i = part.indexOf('=')
            if (i <= 0) continue
            val k = part.substring(0, i).trim()
            if (k in MEDIA_COOKIE_KEYS) jar[k] = part.substring(i + 1).trim()
        }
        if (jar.isEmpty()) return mapOf("Cookie" to ck)
        return mapOf("Cookie" to jar.entries.joinToString("; ") { "${it.key}=${it.value}" })
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

    /** JSON 请求一律显式声明 Accept —— 云盘接口按它做内容协商，用 HTML 的 Accept 会被回 HTML */
    private val jsonAccept = mapOf("Accept" to "application/json, text/plain, */*")

    private suspend fun getJson(url: String, ck: String?): String? = try {
        Http.get(url, referer = webBase, ua = PAN_UA, headers = jsonAccept + cookie(ck))
    } catch (e: Exception) {
        err = classify(e)
        null
    }

    private suspend fun postJson(url: String, body: String, ck: String?): String? = try {
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

    /** 200 但 `code != 0` 的情况（云盘接口的常态错误，HTTP 状态是 200） */
    private fun note(o: JSONObject) {
        val code = o.optInt("code", -1)
        val msg = o.optString("message").take(60)
        err = when {
            isNeedLogin(code, msg) -> {
                DriveStore.markExpired(type)
                PanError.NeedLogin(needLoginMsg(type), type)
            }
            // 41006 = 分享不存在；404 也可能是"分享被删/过期"
            code == 41006 || o.optInt("status", 0) == 404 ->
                PanError.Dead("分享链接已失效（$msg）")
            else -> PanError.Broken("${type.label}接口返回 $code：$msg")
        }
    }

    private fun isNeedLogin(code: Int, msg: String): Boolean =
        code == 31001 || code == 401 || msg.contains("require login", true)

    private fun httpCode(e: Exception): Int =
        Regex("HTTP (\\d{3})").find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun classify(e: Exception): PanError {
        val code = httpCode(e)
        return when {
            code == 401 || code == 403 -> {
                DriveStore.markExpired(type)
                PanError.NeedLogin(needLoginMsg(type), type)
            }
            code == 404 -> PanError.Dead("分享链接已失效（HTTP 404）")
            code in 500..599 -> PanError.Broken("${type.label}服务端错误（HTTP $code）—— 稍后重试")
            code > 0 -> PanError.Broken("${type.label}请求失败（HTTP $code）")
            else -> PanError.Net("网络请求失败：${e.javaClass.simpleName} ${e.message.orEmpty().take(60)}")
        }
    }
}
