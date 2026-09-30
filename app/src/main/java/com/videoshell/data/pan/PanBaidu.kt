package com.videoshell.data.pan

import com.videoshell.data.net.Http
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * ## 百度网盘（P1 的第一个）
 *
 * ### 为什么是它先做
 *
 * 依据不是"它有名"，而是**我们自己的样本里有它**：`tools/verify/samples/panshare/ky_detail.html`
 * （快映）的 `data-clipboard-text` 里，夸克旁边挂的就是一条 `pan.baidu.com/s/1…?pwd=…`。
 * 九个盘里只有夸克/UC 有实现，而**百度是唯一一条在真样本里出现过的空白**。
 *
 * ### 实测确认的接口事实（匿名段，2026-09-26，`tools/verify/panbaidu_spike.py`）
 *
 * | 步骤 | 端点 | 匿名 | 已登录 |
 * |---|---|---|---|
 * | 分享页 | `GET /s/1{surl}` | ✅ 200 + `yunData`（含 `shareid` / `share_uk`） | ✅ |
 * | 换放行票据 | `POST /share/verify` | ✅ `errno=0` + `Set-Cookie: BDCLND` | ✅ |
 * | 列**根**目录 | `GET /share/list?…&root=1&dir=/` | ✅ `errno=0` + `list[]` | ✅ |
 * | 列**子**目录 | `GET /share/list?…&dir={path}`（⚠️ **不带 `root`**） | ✅ `errno=0` | ✅ |
 * | 取直链 | `POST /api/sharedownload` | ❌ `errno=113 验证码签名错误` | ⏳ **未实测** |
 * | 分享签名 | `GET /share/tplconfig?fields=sign,timestamp&share_id=&uk=&surl=` | ❌ `errno=2`（2026-09-30 复验） | ⏳ **未实测**（`sign` 的第一来源，见 [tplSign]） |
 * | 账号签名 | `GET /api/gettemplatevariable?fields=["sign","timestamp","bdstoken"]` | ❌ `errno=-6`（2026-09-30 复验） | ⏳ **未实测**（`sign` 的第二来源，见 [accountSign]） |
 *
 * ### v1.0.85：`errno=113` 的正解只有一个 —— `sign` 拿不到，而**三条来源一条都不能删**
 *
 * 2026-09-30 直接打现网复核了匿名段（`curl` + 桌面 UA，三条真实样本链接）：
 *
 * ```
 * GET /s/1NkV…            → 302 → /share/init，yunData 里**没有** sign/timestamp
 *                             （只有 `{bdstoken:'', uk:'0', loginstate:'0', share_uk, shareid}`）
 * POST /share/verify…     → errno=0 + Set-Cookie: BDCLND（放行票据到手）
 * GET /s/1NkV…（带票据）   → 仍是 `bdstoken:''`，`sign` **一次都没出现过**（连字样都没有）
 * GET /share/tplconfig…   → {"errno":2,"show_msg":"啊哦，链接出错了"}
 * GET /api/gettemplatevariable… → {"errno":-6,"result":[]}   ← -6 = 未登录
 * ```
 *
 * ⇒ 匿名**拿不到** `sign`（三条路都断），所以"匿名取直链"恒 `errno=113` —— 与"参数写错"
 * 无关。登录态下哪一条能给，**只能靠一次 `BAIDU_COOKIE=` 复跑**（工具已就绪，见下）。
 *
 * ⚠️ 同一次复核还钉住了一件事：页面**确实会内联** locals —— 匿名页里就写着
 * `locals.set('servertime', 1790747838499)`（**毫秒**）与 `locals.mset({…})`。但那一批
 * 键里**没有** `sign`/`timestamp`，只有 `csrf`/`uk`/`username`/`loginstate`/`bdstoken`/`public`/…
 * ⇒ 不能指望"从 HTML 里抠 sign"这条兜底（它只在页面把它内联出来时才成立）。
 * 另外这份样本（快映那条 `?pwd=6107`）现网已回 `share_page_type:"error"`+`errno:145`，
 * 即**分享已失效** —— 它只配当"形状样本"，不能当"可用样本"（[shareIsDead] 认的正是这一段）。
 *
 * ⚠️ v1.0.83 那次改动的教训：它把 [accountSign]（`/api/gettemplatevariable`）**整个删掉**，
 * 换成了一条**未实测**的 [tplSign]（`/share/tplconfig`）。而 §4.84 读百度下载 bundle 得到的
 * 原始出处恰恰是前者（`locals.get("public","share_uk","shareid","sign","timestamp",…)` 读的
 * `locals` 就是 `gettemplatevariable` 填的）。**删掉一条来源 = 把一个独立答案从报告里抹掉**，
 * 而三条来源失败的症状**完全相同**（`sign` 空 ⇒ `113`）⇒ 事后分不出是哪一条断的。
 * ⇒ 现在的纪律是：**三条都试，谁先给出非空 `sign` 就用谁，并把用了哪条写进报告**。
 *
 * ⇒ **详情页能像夸克/UC 一样匿名展开真实集数**（[list] 一整条都实测过），
 * 只有"点开某一集"需要登录 —— 与 P0 两盘的形状一致。
 *
 * ### ⚠️ [stream] 是**按文档形状写的、还没实测**的那一半
 *
 * 取 dlink 需要的 `sign` **匿名拿不到**：2026-09-26 直接把放行后的分享页落盘看过
 * （`/tmp` 级的一次性探针），`yunData` 的字面量是
 * `{… bdstoken:'', uk:'0', loginstate:'0', share_uk:"…", shareid:"…"}` —— 没有 `sign`，
 * 也没有 `timestamp`；`sign`/`sign1..3` 只出现在 webpack 模块的 `locals` **元数据**里
 * （那是"这个模块要消费哪些变量"，不是值）。所以匿名打 `/api/sharedownload` 恒
 * `errno=113`，不是我们参数写错。
 *
 * #### v1.0.81 补的一课：`errno=113` 有两个成因，一个已经能在我们这一侧堵住（§4.84）
 *
 * 2026-09-29 把百度**自己的**下载 bundle（`function-widget-1/pkg/download-all_*.js`）读了
 * 一遍，`ajaxGetDlinkShare` 的形状是：
 *
 * ```js
 * a().locals.get("public","share_uk","shareid","sign","timestamp", function (l,p,f,g,y) {
 *     if (0 === l) {                                  // ← `public` 为 0 = **加密分享**
 *         n.extra = JSON.stringify({sekey: decodeURIComponent(BDCLND)});
 *     }
 *     …POST url + "?sign=" + g + "&timestamp=" + y …
 * });
 * ```
 *
 * 四条从这段里钉住了（都写进了 [sekey] / [extraOf] / [pageStamp] / [templateSign]）：
 *
 * 1. **加密分享必须回显放行票据**：`extra={"sekey":<解码后的 BDCLND>}`。我们之前**一个
 *    字段都没发** —— 而我们的样本（玩偶站那条 `?pwd=`）页面里正是 `"public":0`。
 * 2. `sign`/`timestamp` 是**页面变量**（`locals.get(...)`）：匿名页没有 ⇒ 空 ⇒ `113`。
 * 3. 页面的签名时间是 `servertime`（`locals.set('servertime', 1790669409761)`，**毫秒**），
 *    而请求参数是秒 —— 混用就是"看起来有值、其实差 1000 倍"，症状同样是 `113`。
 * 4. 那些 `locals` 由 `/api/gettemplatevariable` 填 ⇒ 页面**没内联**它们时走 [accountSign]
 *    那条兜底（与页面同源）。四条都已**接进 [stream]**（`sekey`→`extra`、`servertime`→秒、
 *    `sign` 空则兜底、错误串 [unescape]），守卫是 `PanBaiduTest` H1~H16。
 *
 * ⇒ "Cookie 就够、还是必须再带 `bdstoken`"这个判据，**仍然只能靠一份真实登录态跑一次**
 * 才能钉住（文件和工具都已就绪）：
 *
 * ```
 * BAIDU_COOKIE="$(cat /path/to/baidu_cookie.txt)" python tools/verify/panbaidu_spike.py
 * ```
 *
 * 跑出来的 ⑤⑥ 两段就是本类 [stream] 的验收依据。在那之前它的行为是**可预期地失败**：
 * 失败文案一定带**哪一步 + 服务端 errno 原话**（[note]，且 [unescape] 过），
 * 而不是"点了没反应"。
 *
 * ### 两个与夸克/UC 相反的形状（容易抄错的地方）
 *
 * 1. **子目录不能用 fs_id 去列**。`/share/list` 的 `dir` 要的是**拥有者侧绝对路径**
 *    （返回项自己的 `path`，如 `/2026-YIN/出入`）—— 拿 fs_id 或"按名字拼出来的
 *    `/子目录`"去问只会得到 `errno=2`。所以目录项的 [PanFile.fid] 存的是它的 `path`。
 * 2. **子目录不能带 `root=1`**：它会让服务端**静默忽略 `dir`**、把根目录的内容再发一遍。
 *    症状极具误导性 —— 看起来每层都"下钻成功"，其实是原地打转（PITFALLS §4.80）。
 *    [useRoot] 是这条判据的唯一出处。
 */
class PanBaidu private constructor() : PanProvider {

    companion object {
        fun baidu() = PanBaidu()

        /** PC 接口用桌面 UA —— 与 [PanCloudDrive] 同一条理由（PC 接口就别装手机） */
        private const val PAN_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** 拼接口地址用这个：**不带尾斜杠**（见 [sharePageUrl] 那条事故） */
        private const val API = "https://pan.baidu.com"

        /**
         * ⚠️ **只当 `Referer` 用**。它带尾斜杠，**绝不能**再拼 `"/…"` ——
         * 那样会造出 `pan.baidu.com//s/…`（见 [sharePageUrl]）。
         */
        private const val WEB = "https://pan.baidu.com/"

        /** `app_id=250528` = 网页版这个产品；`channel=chunlei` 是网页端一直用的值 */
        private const val APP_ID = "250528"
        private const val CHANNEL = "chunlei"

        /** 一次列目录要多少项（分享根通常只有几个目录，200 足够且省流量） */
        private const val PAGE_SIZE = 200

        /** 分享页结构信息（`shareid`/`share_uk`）缓存时长 —— 与夸克的 stoken 同一个量级 */
        private const val SHARE_TTL_MS = 20 * 60_000L

        /**
         * 从分享页 HTML 里抠 `yunData` 的结构字段（**纯函数**，可离线断言）。
         *
         * ⚠️ 它必须用**正则**、不能用 `JSONObject`：`yunData` 是 **JS 字面量**
         * （键名**不带引号**：`yunData={skinName:'white', …, shareid:"51458864580"}`），
         * 不是 JSON —— `JSONObject` 上去必抛。这与夸克那边 [PanCloudDrive.envelopeFields]
         * 不能依赖 JSON 解析是同一类问题。
         *
         * 只取**与登录态无关**的那几个；`bdstoken`/`sign` 顺带取（匿名页上是空串），
         * 调用方要用它们的场景（[stream]）必须**带 cookie 重新取一次页面**。
         *
         * @return 缺 `shareid` 或 `share_uk` 时返回 null（列目录至少要这两个）
         */
        @JvmStatic
        fun shareFields(html: String): BaiduShare? {
            if (html.isBlank()) return null
            // ⚠️ 每个键名后面都要跟一个 `["']?`（键名**带引号**也要认）。这不是"顺手宽松"，
            //    而是 v1.0.84 找到的 113 根因（§4.86）：`yunData` 有两种写法 ——
            //      · 键名不带引号的 JS 字面量：`shareid:"…"`（匿名页实测的形状）
            //      · **带引号**的 JSON 形状：`"shareid":"…"`（页面被序列化时）
            //    只认第一种时，`shareid` 恰好**另有一个 URL 参数副本**（页内链接里的
            //    `…&shareid=123`）兜住了它，所以列目录一直是好的；而 `sign` 没有副本
            //    ⇒ 恒为空 ⇒ 取直链恒 `errno=113`。三处正则只差这一个 `["']?`，
            //    症状却分散在"列目录正常 / 取直链失败"两件看似无关的事上。
            val sid = Regex("\\bshareid\\b[\"']?\\s*[:=]\\s*[\"']?(\\d{5,})")
                .find(html)?.groupValues?.get(1)
            // `share_uk` 与 `uk` 都在页面上；只认前者 —— 匿名页的 `uk` 是 `'0'`（游客 id）
            val uk = Regex("\\bshare_uk\\b[\"']?\\s*[:=]\\s*[\"']?(\\d{5,})")
                .find(html)?.groupValues?.get(1)
            if (sid.isNullOrBlank() || uk.isNullOrBlank()) return null
            val tok = Regex("\\bbdstoken\\b[\"']?\\s*[:=]\\s*[\"']([^\"']*)[\"']")
                .find(html)?.groupValues?.get(1).orEmpty()
            // `\bsign\b` 才不会连 `sign1`/`sign2` 一起吃掉（那三个是另一套签名材料），
            // 而 webpack 元数据里的 `"sign","servertime"` 后面跟的是逗号、不是 `:`/`=` ⇒ 不命中。
            val sign = Regex("\\bsign\\b[\"']?\\s*[:=]\\s*[\"']([^\"']{4,200})[\"']")
                .find(html)?.groupValues?.get(1).orEmpty()
            return BaiduShare(sid, uk, tok, sign, pageStamp(html))
        }

        /**
         * 页面上的"签名时间"，单位统一成**秒**（**纯函数**，`PanBaiduTest` H1~H4 钉着）。
         *
         * 两个名字都要认，因为页面在**两个地方**各写一份、形状还不一样：
         *  - `timestamp:1790252372` —— JSON 老形状（**秒**）；
         *  - `locals.set('servertime', 1790669409761)` —— 现网页面（**毫秒**）。
         *
         * ⚠️ 它和 `sign` 是**一对**：差 1000 倍与服务端记的对不上，回的就是 `errno=113`
         * （与"sign 为空"同一个码）。所以单位归一不能省 —— 详见 §4.84。
         *
         * 认不出回 `0`：调用方（[stream]）据此判断"页面没给"，而不是拿 0 当有效值发出去。
         */
        @JvmStatic
        fun pageStamp(html: String): Long {
            if (html.isBlank()) return 0L
            val v = Regex("\\btimestamp\\b[\"']?\\s*[,:=]\\s*[\"']?(\\d{9,13})")
                .find(html)?.groupValues?.get(1)?.toLongOrNull()
                ?: Regex("\\bservertime\\b[\"']?\\s*[,:=]\\s*[\"']?(\\d{9,13})")
                    .find(html)?.groupValues?.get(1)?.toLongOrNull()
                ?: return 0L
            return if (v > 100_000_000_000L) v / 1000 else v
        }

        /**
         * 从一份 `Cookie` 头里取 `BDCLND`（放行票据）并**按 URL 解码**（**纯函数**）。
         *
         * 为什么要它：加密分享（页面里 `"public":0`）取 dlink 时**必须**把票据回显进表单的
         * `extra` 字段（见 [extraOf]）—— 百度自己的客户端就是这么发的（§4.84）。
         * 票据在 cookie 里是**百分号编码**的（`j%2FIw…%3D`），请求体里要的是**解码后**的值
         * （`decodeURIComponent` 那一步）。
         *
         * 没有这一项就回空串：**公开分享本来就没有它**，所以空不等于出错。
         */
        @JvmStatic
        fun sekey(cookie: String?): String {
            if (cookie.isNullOrBlank()) return ""
            for (part in cookie.split(';')) {
                val i = part.indexOf('=')
                if (i <= 0 || part.substring(0, i).trim() != "BDCLND") continue
                val v = part.substring(i + 1).trim()
                return runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
            }
            return ""
        }

        /** `extra` 字段的形状（**纯函数**）：`{"sekey":"<解码后的 BDCLND>"}`，见 [sekey] */
        @JvmStatic
        fun extraOf(sekey: String): String =
            "{\"sekey\":\"" + sekey.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"

        /**
         * 把信封里的 JSON 转义还原成**人话**（**纯函数**）。
         *
         * 百度把错误串写成 `\u9a8c\u8bc1\u7801\u7b7e\u540d\u9519\u8bef`（= "验证码签名错误"）。
         * 旧 [msgOf] 把**转义后的原样**塞进文案，用户在气泡里看到的是一串 `\uXXXX`
         * —— 等于没有文案，还把 60 字符的额度浪费在 6 个反斜杠转义上（§4.84）。
         */
        @JvmStatic
        fun unescape(s: String): String {
            if (s.indexOf('\\') < 0) return s
            val b = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c != '\\' || i + 1 >= s.length) {
                    b.append(c)
                    i++
                    continue
                }
                val n = s[i + 1]
                val hex = if (n == 'u' && i + 6 <= s.length) s.substring(i + 2, i + 6) else null
                val cp = hex?.toIntOrNull(16)
                when {
                    cp != null -> { b.append(cp.toChar()); i += 6 }
                    n == 'n' -> { b.append('\n'); i += 2 }
                    n == 't' -> { b.append('\t'); i += 2 }
                    n == 'r' -> { b.append('\r'); i += 2 }
                    n == '/' || n == '"' || n == '\\' -> { b.append(n); i += 2 }
                    else -> { b.append(c); i++ }
                }
            }
            return b.toString()
        }

        /**
         * 从 `/api/gettemplatevariable` 的信封里抠签名字段（**纯函数**）。
         *
         * 形状（2026-09-29 匿名实测到的后半段是 `"result":[]` + `errno:-6`）：
         *
         * ```json
         * {"errno":0,"result":{"sign":"…","timestamp":1790252372,"bdstoken":"…"}}
         * ```
         *
         * 认不出 `sign` 就返回 null —— 调用方据此判"这一步没拿到签名"，而不是拿空串去发。
         */
        @JvmStatic
        fun templateSign(body: String): BaiduSign? {
            if (body.isBlank()) return null
            val sign = Regex("\"sign\"\\s*:\\s*\"([^\"]{4,200})\"")
                .find(body)?.groupValues?.get(1).orEmpty()
            if (sign.isBlank()) return null
            val rawTs = Regex("\"timestamp\"\\s*:\\s*\"?(\\d{9,13})")
                .find(body)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            // ⚠️ 单位跟 [pageStamp] **同一条规矩**：这一路填的就是页面上的 `locals`，而页面的
            //    `servertime` 是**毫秒**（见 [pageStamp] 的实测值）。13 位照发 = 差 1000 倍
            //    ⇒ `errno=113`（与"sign 为空"同码）。归一一次，两条签名路径对服务端才是同一种时间。
            val ts = if (rawTs > 100_000_000_000L) rawTs / 1000 else rawTs
            val tok = Regex("\"bdstoken\"\\s*:\\s*\"([^\"]*)\"")
                .find(body)?.groupValues?.get(1).orEmpty()
            return BaiduSign(sign, ts, tok)
        }

        /**
         * 这个分享页是不是**错误页**（**纯函数**，可离线断言）。
         *
         * 判据是服务端自己在页面上写的 `share_page_type:"error"` —— **不是抠不到字段**。
         * 实测（PITFALLS §4.80）：分享已失效时页面照样 `200`、照样有完整的
         * `yunData`（`shareid`/`share_uk` 都在），只是 `share_page_type` 变成 `error`
         * 且 `errno=145`。所以"抠到了 shareid + uk"**不构成**"这条分享可用"的证据 ——
         * 这一条正是当初把一条死链报告成"体验最好的一条路"的原因。
         */
        @JvmStatic
        fun shareIsDead(html: String): Boolean =
            html.contains("share_page_type", ignoreCase = false) &&
                    Regex("share_page_type\\s*[:=]\\s*[\"']error[\"']").containsMatchIn(html) ||
                    Regex("\"errno\"\\s*:\\s*145\\b").containsMatchIn(html)

        /**
         * 从信封里抠 `errno`（**纯函数**）。百度用 `{"errno":0,…}`，
         * 没有夸克那种 `{status,code,message}`。
         *
         * 用正则而不是 `JSONObject`：失败响应体经 [Http.HttpError] 只留前 200 字符，
         * 可能截断（与 [PanCloudDrive.envelopeFields] 同一个理由）。
         */
        @JvmStatic
        fun errnoOf(body: String): Int? {
            if (body.isBlank()) return null
            return Regex("\"errno\"\\s*:\\s*(-?\\d+)").find(body)
                ?.groupValues?.get(1)?.toIntOrNull()
        }

        /** 从 `sharedownload` 的响应里抠 `dlink`（**纯函数**，`\/` 是 JSON 里的转义斜杠） */
        @JvmStatic
        fun dlinkOf(body: String): String? {
            if (body.isBlank()) return null
            val v = Regex("\"dlink\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
                ?.groupValues?.get(1) ?: return null
            val u = v.replace("\\/", "/").replace("&amp;", "&")
            return u.takeIf { it.startsWith("http") }
        }

        /**
         * 列这个目录要不要带 `root=1`（**纯函数**，可离线断言）。
         *
         * **只有分享根**要带：`root=1 & dir=/`。子目录**必须不带** —— 带了服务端会
         * 静默忽略 `dir`、把根目录再发一遍，于是"下钻"看起来成功却永远在原地（§4.80）。
         * 缺 `root=1` 的根目录请求恒 `errno=-21`，所以这一条不能反过来。
         */
        @JvmStatic
        fun useRoot(fid: String?): Boolean = fid.isNullOrBlank() || fid == "/"

        /**
         * 分享页地址（**纯函数**，可离线断言）。
         *
         * ⚠️ 必须拼在 [API]（**不带尾斜杠**）上，**不能**拼 [WEB]（自带尾斜杠）。
         * 这一条是 v1.0.80 的真事故，症状与原因完全对不上（PITFALLS §4.83）：
         *
         * ```
         * 错： "$WEB/s/${id}"  → https://pan.baidu.com//s/1abc   （双斜杠）
         * 对： "$API/s/${id}"  → https://pan.baidu.com/s/1abc
         * ```
         *
         * 双斜杠那条路径服务端回 **HTTP 404**，而那个 404 的响应头**谎称**
         * `Content-Encoding: gzip`、正文却是**明文 HTML** ⇒ OkHttp 解 gzip 时抛
         * `ZipException`（不是 [com.videoshell.data.net.Http.HttpError]，没有状态码）
         * ⇒ [classify] 落到 `code <= 0` 分支，报成「**网络请求失败**」——
         * 指向一个根本不存在的网络问题。真机上的现象就是 BD 线路永远「未展开」。
         */
        @JvmStatic
        fun sharePageUrl(id: String): String = "$API/s/$id"
    }

    override val type: PanType get() = PanType.BAIDU

    /**
     * ⚠️ `true` 的含义是"这个盘能取直链"。[list] 那条链路**已实测**，
     * [stream] 按文档形状实现、**待一次真实登录态复跑**（见类文档）。
     * 之所以不先压成 false：那会让百度整条线路显示成"暂不支持"，
     * 而"匿名能展开真实集数"这件事是实测成立的——那半本来就该给用户。
     */
    override val supported: Boolean get() = true

    @Volatile
    private var err: PanError? = null

    override val lastError: PanError? get() = err

    @Volatile
    private var step: String = ""

    /** 分享页结构信息缓存（`shareid`/`share_uk`）—— 匿名可得、与登录态无关、短时间内稳定 */
    private val shareCache = ConcurrentHashMap<String, Pair<BaiduShare, Long>>()

    // ------------------------------------------------------------------ 契约

    override suspend fun list(link: PanLink, fid: String?): List<PanFile> {
        err = null
        val sh = shareOf(link) ?: return emptyList()
        val root = useRoot(fid)
        val dir = if (root) "/" else fid!!
        val url = buildString {
            append(API).append("/share/list?uk=").append(u(sh.uk))
            append("&shareid=").append(u(sh.shareid))
            append("&order=other&desc=1&showempty=0&web=1&page=1&num=").append(PAGE_SIZE)
            // ⚠️ 顺序即语义：`root=1` 只对分享根成立，见 [useRoot]
            if (root) append("&root=1")
            append("&dir=").append(u(dir))
            append("&t=").append(nowSec())
            append("&channel=").append(CHANNEL).append("&app_id=").append(APP_ID)
            if (sh.bdstoken.isNotBlank()) append("&bdstoken=").append(u(sh.bdstoken))
            append("&clienttype=0")
        }
        val body = getText(url, null, "列目录") ?: return emptyList()
        val e = errnoOf(body)
        if (e == null) {
            err = PanError.Broken("${type.label}列目录返回的不是信封（接口可能变了）")
            return emptyList()
        }
        if (e != 0) {
            note(e, msgOf(body))
            return emptyList()
        }
        val arr = runCatching { JSONObject(body).optJSONArray("list") }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<PanFile>(arr.length())
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val isDir = it.optInt("isdir", 0) == 1
            val fsId = it.optLong("fs_id", 0L)
            // 目录用 `path`（拥有者侧绝对路径），文件用 `fs_id` —— 见类文档第 1 条
            val id = if (isDir) it.optString("path") else if (fsId > 0L) fsId.toString() else ""
            if (id.isBlank()) continue
            out.add(
                PanFile(
                    fid = id,
                    name = it.optString("server_filename"),
                    dir = isDir,
                    size = it.optLong("size", 0L)
                )
            )
        }
        return out
    }

    /**
     * 取直链（dlink）。⚠️ **本方法是待实测的那一半**（见类文档）。
     *
     * 形状来自方案 §6.3 与 spike 的候选并列探测：带登录态**重取一次分享页**拿
     * `sign`/`timestamp`/`bdstoken`（匿名页上没有 `sign`），再表单 POST
     * `/api/sharedownload`。
     *
     * 失败**绝不静默**：[note] 会把"哪一步 + errno 原话"写进 [lastError]，
     * 上层（[PanResolver.failure]）据此给出可执行文案。
     */
    override suspend fun stream(ref: PanRef): PanStream? {
        err = null
        val ck = DriveStore.cookie(type)
        // ⚠️ 判据与 [verify] **逐字一致**（同一个盘、同一件事，不能两处两套）：百度登录态的
        //    会话键就是 `BDUSS`。只有 `BAIDUID`（游客）/`BDCLND`（提取码票据）的 cookie
        //    同样"非空"，但它**签不了名** —— 2026-09-30 直接打现网复核：匿名（含换取票据
        //    之后）分享页里 `sign`/`timestamp`/`servertime` **一个都没有**，`/share/tplconfig`
        //    回 `errno=2`、`/api/gettemplatevariable` 回 `errno=-6`（未登录）。
        //    不在这里拦住，就等于拿一份游客 cookie 去空打一次 `/api/sharedownload`，
        //    把它必然回的那句 **`errno=113 验证码签名错误`** 当成结论抛给用户 ——
        //    而真因是"没登录"。用户看到的是玄学错误码，不是"去登录"。
        if (ck.isNullOrBlank() || !ck.contains("BDUSS=")) {
            if (!ck.isNullOrBlank()) DriveStore.markExpired(type)
            err = PanError.NeedLogin(needLoginMsg(type), type)
            return null
        }
        val sh = cachedShare(ref.link) ?: shareOf(ref.link) ?: return null
        // 带 cookie 重取：`sign` 只在登录态下才有（匿名页的字面量里根本没有它 —— 2026-09-30 实测）
        val page = pageOf(ref.link, ck, "取直链·读分享页")
        // 签名有**三个**来源，按证据强度依次试，谁先给出非空 `sign` 就用谁，并且
        // **整份带走**（`sign` 与 `timestamp` 必须同源：混用另一份的时间戳 = 没签名，§4.84）：
        //   ① `GET /share/tplconfig`        —— 分享级模板变量（分享页自己的 `locals`）；
        //   ② `GET /api/gettemplatevariable` —— 账号级模板变量，**这是 §4.84 读百度下载
        //      bundle 得到的原始出处**（`locals.get(…"sign","timestamp"…)` 读的 `locals` 就是它填的）；
        //   ③ 分享页 HTML 里内联的变量（页面把 `locals` 直接写出来时的兜底）。
        // ⚠️ 三条都要试：任一条都可能因为**端点/参数形状变了**而恒空，而它们失败的症状
        //    **完全相同**（`sign` 空 ⇒ `errno=113`）—— 只留一条就等于把"这条断没断"这个问题
        //    埋进 113 里（v1.0.83 删掉 ② 就是这么发生的）。
        var sign = ""
        var ts = 0L
        // 签名的**来源**必须留痕：`113` 一个码分不出"签名为空 / 签了但与时间戳不配套 /
        // 加密票据没回显"，而"这几个字段各自从哪来"正是唯一能把它分开的证据（§4.84）。
        var signSrc = ""
        var bdstoken = page?.bdstoken.orEmpty().ifBlank { sh.bdstoken }
        val fromShare = tplSign(ref.link, sh, ck)
        val fromAccount = if (fromShare?.sign.isNullOrBlank()) accountSign(ref.link, ck) else null
        val picked = when {
            !fromShare?.sign.isNullOrBlank() -> fromShare to "分享级 tplconfig"
            !fromAccount?.sign.isNullOrBlank() -> fromAccount to "账号级 gettemplatevariable"
            else -> null
        }
        if (picked != null) {
            sign = picked.first.sign
            if (picked.first.timestamp > 0L) ts = picked.first.timestamp
            if (bdstoken.isBlank()) bdstoken = picked.first.bdstoken
            signSrc = picked.second
        }
        if (sign.isBlank()) {
            sign = page?.sign.orEmpty()
            if (sign.isNotBlank()) {
                ts = page?.timestamp ?: 0L
                signSrc = "分享页内联"
            }
        }
        if (signSrc.isBlank()) signSrc = "空（tplconfig / gettemplatevariable / 分享页都没给）"
        // ⚠️ 时间戳的兜底只能在"连 sign 都没有"时发生，并且**必须在报告里写明**：
        //    `sign` 是按页面那一刻的时间戳签发的，混一个 now() 进去 ⇒ 签名必然对不上
        //    （回 113，与"根本没签名"同一个码）。"有 sign 却读不到 ts"是页面形状变了，
        //    这一句留痕就是下一轮唯一能指出它的东西。
        val tsFallback = ts <= 0L
        if (tsFallback) ts = nowSec()
        // 留痕（见 [PanDiag]）：这一步的答案全在"发出去的那几个字段"里。
        // ⚠️ 只记长度与来源，不记明文 —— 这份报告会被用户贴出来。
        PanDiag.record(
            "百度·取直链参数：sign=" + PanDiag.brief(sign) + "（来源=" + signSrc + "）" +
                    " ts=" + ts + (if (tsFallback) "·⚠️兜底成当前时间" else "·随 sign 同源") +
                    " bdstoken=" + PanDiag.brief(bdstoken) +
                    " 分享页=" + (if (page == null) "取不到" else "已取到") +
                    " shareid=" + sh.shareid
        )
        // 三条来源都没给出 `sign` ⇒ 这一次 `/api/sharedownload` **必然**回 `errno=113`
        // （服务端拿它当"验证码签名错误"，而真因是没拿到签名 —— 2026-09-30 现网复核：
        //  匿名三条路全断）。空打一次只会把一句玄学错误码交给用户，所以就地收手，
        // 按"需要登录"上报（[PanError.NeedLogin] 是终态，上层会给出「去登录」入口）。
        // ⚠️ 留痕不可省：报告里"sign=空 + 来源=…"这一行才是下一轮唯一能指出是谁断了的证据。
        if (sign.isBlank()) {
            // `bdstoken` 是**账号级**令牌：登录态的分享页必然带着它（`yunData` 里那个
            // `bdstoken:"…"`），而游客页永远是空串。所以"sign 空 **且** bdstoken 空"
            // 基本可以断定这份 cookie 已经不是有效登录态 ⇒ 标失效，让账号页如实显示
            // "需重新登录"；反过来（bdstoken 有值）就只是这一次取不到签名，
            // 不该把一份还能用的凭据标成过期。
            if (bdstoken.isBlank()) DriveStore.markExpired(type)
            err = PanError.NeedLogin(needLoginMsg(type), type)
            PanDiag.record("百度·取直链中止：sign 为空（来源=$signSrc）⇒ 不再空打接口，按「需要登录」上报")
            return null
        }
        // 放行票据（BDCLND）在 Http 的 CookieJar 里、账号凭据在 DriveStore 里，
        // 两边都要带上 —— 合并用的是 [PanCloudDrive.mediaCookie]（纯函数、离线有断言，
        // `keys = null` = 不筛键）。不复用就只能再抄一份合并逻辑，早晚改一处漏一处。
        val merged = PanCloudDrive.mediaCookie(
            ck, Http.cookieValuesFor("$API/api/sharedownload"), null
        )
        val url = buildString {
            append(API).append("/api/sharedownload?sign=").append(u(sign))
            append("&timestamp=").append(ts)
            append("&channel=").append(CHANNEL).append("&web=1&app_id=").append(APP_ID)
            if (bdstoken.isNotBlank()) append("&bdstoken=").append(u(bdstoken))
            append("&clienttype=0")
        }
        // 加密分享（页面里 `"public":0`）取 dlink 时**必须**把放行票据回显进表单的
        // `extra` 字段：百度自己的客户端只在 `public === 0` 时发它（§4.84）。
        // 票据（`BDCLND`）在 cookie 里是百分号编码的、请求体里要**解码后**的值 ——
        // [sekey] 一并做了。没有票据（公开分享）就**不发这一个字段**：
        // 百度那边它是 `undefined`，发个空串会让服务端按"加密"去校验。
        val ticket = sekey(merged)
        PanDiag.record(
            "百度·加密票据 BDCLND=${if (ticket.isBlank()) "无（按公开分享发）" else "有·${ticket.length}字符"}"
        )
        val form = LinkedHashMap<String, String>().apply {
            put("encrypt", "0")
            put("product", "share")
            put("type", "dlink")
            put("uk", sh.uk)
            put("primaryid", sh.shareid)
            put("fid_list", "[${ref.fid}]")
            if (ticket.isNotBlank()) put("extra", extraOf(ticket))
        }
        val body = postForm(
            url, form, merged, "取直链",
            // ⚠️ `Referer` 必须是**分享页**，不是站根 [WEB]。理由：`sign` 是**按页面签发**的，
            // 而百度自己的下载 bundle 是在分享页里发这个请求的（浏览器同源会把 `Referer`
            // 补成完整的分享页地址）。用站根等于换了一个来路，服务端判"签名对不上"——
            // 症状与"根本没签名"**同一个码**（`errno=113`）。§4.84 那条"同一个错误码不止
            // 一个成因"在这里又长出一条。
            referer = sharePageUrl(ref.link.id)
        ) ?: return null
        val e = errnoOf(body)
        if (e == null) {
            err = PanError.Broken("${type.label}取直链返回的不是信封（接口可能变了）")
            return null
        }
        if (e != 0) {
            note(e, msgOf(body))
            PanDiag.record("百度·取直链响应：errno=$e ${msgOf(body)}")
            return null
        }
        val dlink = dlinkOf(body)
        if (dlink.isNullOrBlank()) {
            err = PanError.Broken("${type.label}取直链没有返回 dlink（接口可能变了）")
            return null
        }
        return PanStream(url = dlink, headers = mediaHeaders(merged), hls = false, mime = null)
    }

    /**
     * 校验本地凭据。
     *
     * 两段判据，**都不依赖未实测的 errno 语义**：
     * 1. **结构判据**：百度登录态的会话键就是 `BDUSS`。没有它，这份 cookie 不可能是
     *    一个登录会话（只有 `BAIDUID` 是游客）⇒ 直接判过期，连请求都不用打。
     * 2. **网络探针**：`/api/quota`（便宜的"我是谁"）。`errno=0` ⇒ 确证有效；
     *    其余错误码 ⇒ **判有效**（未开通会员 / 接口小改都不代表凭据无效）。
     *
     * ⚠️ 第 2 条刻意保守：把"认不出的码"判成过期，就是 UC §4.79 那个"刚登录就过期"
     * 的复现路径（用户会白重登一次）。宁可漏判，不可误判 —— 真过期时 [stream] 会
     * 以 `NeedLogin` 的形式告诉用户。
     */
    override suspend fun verify(): DriveState {
        val ck = DriveStore.cookie(type)
        if (ck.isNullOrBlank()) return DriveState.None
        if (!ck.contains("BDUSS=")) {
            DriveStore.markExpired(type)
            return DriveState.Expired
        }
        return try {
            val body = Http.get(
                "$API/api/quota?checkfree=1&checkexpire=1",
                referer = WEB, ua = PAN_UA, headers = cookie(ck)
            )
            when (errnoOf(body)) {
                null -> DriveState.Valid
                0 -> {
                    DriveStore.clearExpired(type)
                    DriveState.Valid
                }
                // 别的 errno 不判过期（见上面的注释）
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

    // ------------------------------------------------------------------ 分享页 / 提取码

    private fun cachedShare(link: PanLink): BaiduShare? {
        val hit = shareCache[keyOf(link)] ?: return null
        return if (System.currentTimeMillis() < hit.second) hit.first else null
    }

    private fun keyOf(link: PanLink) = link.type.key + "|" + link.id + "|" + link.pwd

    /**
     * 取分享页的结构信息（[list] 的唯一前置）。
     *
     * 顺序照 spike：**先取一次分享页**（它给会话下发 `BAIDUID`，让后面那次
     * `share/verify` 落在同一个会话里）→ 有提取码就换放行票据 → **再取一次**分享页。
     */
    private suspend fun shareOf(link: PanLink): BaiduShare? {
        cachedShare(link)?.let { return it }
        val first = pageOf(link, null, "读分享页") ?: return null
        val sh = if (link.pwd.isBlank()) {
            first
        } else {
            if (!verifyPwd(link)) return null
            pageOf(link, null, "读分享页（换票据后）") ?: return null
        }
        shareCache[keyOf(link)] = sh to System.currentTimeMillis() + SHARE_TTL_MS
        return sh
    }

    /**
     * 取分享页并解析；页面自称是错误页 ⇒ [PanError.Dead]（这是"分享没了"的**唯一判据**）。
     *
     * ⚠️ 带 cookie 取页面时**必须合并 jar**：`BDCLND`（提取码换来的放行票据）是[verifyPwd]
     * 那一次响应 `Set-Cookie` 下来的、只活在 `Http` 的 CookieJar 里；而 OkHttp 的
     * `BridgeInterceptor` 会被显式 `Cookie` 头**顶掉** jar（见 [com.videoshell.data.net.Http]
     * 的 `ExplicitCookie` 说明）⇒ 只发账号 cookie 的话，有提取码的分享会停在
     * "请输入提取码"那一页，`sign` 抠不到、取直链必失败。
     */
    private suspend fun pageOf(link: PanLink, ck: String?, what: String): BaiduShare? {
        // ⚠️ 地址必须走 [sharePageUrl]（拼 [API]，不带尾斜杠）—— 这里曾经写成
        //    "$WEB/s/…"，于是每次请求都是 `//s/…`：404 + 假 gzip，被报成"网络请求失败"（§4.83）
        val url = sharePageUrl(link.id)
        val jar = Http.cookieValuesFor(url)
        val merged = if (ck.isNullOrBlank()) null else PanCloudDrive.mediaCookie(ck, jar, null)
        val html = getText(url, merged, what, json = false) ?: return null
        if (shareIsDead(html)) {
            err = PanError.Dead("分享已失效（${type.label}分享页自称 share_page_type=error）")
            return null
        }
        return shareFields(html) ?: run {
            err = PanError.Broken("${type.label}分享页里没有 shareid/share_uk（页面形状可能变了）")
            null
        }
    }

    /**
     * 用提取码换放行票据。`surl` 是**去掉那个固定 `1` 前缀**的短码
     * （页面路径是 `/s/1XXXX`，而接口要 `XXXX` —— 弄混的症状是"分享明明活着却被报失效"）。
     *
     * ⚠️ 这里**不能**显式带 `Cookie`：放行票据（`BDCLND`）正是这次响应 `Set-Cookie` 下发的，
     * 而显式 `Cookie` 头会让 OkHttp **跳过 CookieJar** ⇒ 票据根本进不来、下一步列目录必空。
     */
    private suspend fun verifyPwd(link: PanLink): Boolean {
        val surl = link.id.removePrefix("1")
        val url = "$API/share/verify?surl=${u(surl)}&t=${nowSec()}" +
                "&channel=$CHANNEL&web=1&app_id=$APP_ID&clienttype=0"
        val body = postForm(
            url,
            linkedMapOf("pwd" to link.pwd, "vcode" to "", "vcode_str" to ""),
            null, "换放行票据"
        ) ?: return false
        val e = errnoOf(body)
        if (e == null) {
            err = PanError.Broken("${type.label}换放行票据返回的不是信封（接口可能变了）")
            return false
        }
        if (e != 0) {
            note(e, msgOf(body))
            return false
        }
        return true
    }

    /**
     * **分享签名（来源①）**：`GET /share/tplconfig?fields=sign,timestamp&…&share_id=&uk=&surl=`。
     *
     * 一个**分享级**模板变量接口，要 `share_id`/`uk`/`surl` 三个分享标识
     * （`surl` = [sharePageUrl] 里那个 id 去掉固定前缀 `1`，与 [verifyPwd] 同规矩）。
     *
     * ⚠️ **未实测**：2026-09-30 匿名打它就是 `{"errno":2,"show_msg":"啊哦，链接出错了"}`
     * （登录态下是什么，要一次 `BAIDU_COOKIE=` 复跑才知道）。所以它只是**来源之一**，
     * 不是"正路"—— 拿不到就让 [accountSign] 接着试（见类文档 v1.0.85 那条教训）。
     *
     * 两条路的 `sign`/`timestamp` 都**整份带走、不拆开**（混用另一份的时间戳 = 没签名，
     * 回 `errno=113`，§4.84）。
     *
     * ⚠️ 它**不覆盖** [err]：这是"多试一个来源"，本身失败不代表这一步失败 ——
     * 真失败由随后的表单 POST 用服务端原话（[note]）说清楚，别让兜底把主路的错误顶掉。
     * 这也是为什么它返回 `null` 而不是 `Unit`：调用方只在该更新字段时更新。
     */
    private suspend fun tplSign(link: PanLink, sh: BaiduShare, ck: String): BaiduSign? =
        signAt("取直链·取分享签名", "$API/share/tplconfig?fields=${u("sign,timestamp")}" +
                "&channel=$CHANNEL&web=1&clienttype=0&app_id=$APP_ID" +
                "&share_id=${u(sh.shareid)}&uk=${u(sh.uk)}" +
                "&surl=${u(link.id.removePrefix("1"))}", link, ck)

    /**
     * **账号签名（来源②）**：`GET /api/gettemplatevariable?fields=["sign","timestamp","bdstoken"]`。
     *
     * 这是 §4.84 读百度下载 bundle 得到的**原始出处** —— bundle 里
     * `locals.get("public","share_uk","shareid","sign","timestamp", …)` 读的 `locals`
     * 就是它填的。匿名打它回 `errno=-6`（= 未登录，2026-09-30 复验），所以只在带 cookie 时才有意义。
     *
     * ⚠️ v1.0.83 曾把它整个删掉、只留 [tplSign]（而那一条**未实测**）。三条来源失败的症状
     * 完全相同（`sign` 空 ⇒ `errno=113`）⇒ 删掉一条就是把一个独立答案从报告里抹掉。v1.0.85 补回。
     */
    private suspend fun accountSign(link: PanLink, ck: String): BaiduSign? =
        signAt("取直链·取账号签名", "$API/api/gettemplatevariable?fields=" +
                "${u("[\"sign\",\"timestamp\",\"bdstoken\"]")}" +
                "&channel=$CHANNEL&web=1&app_id=$APP_ID&clienttype=0", link, ck)

    /**
     * 两条签名接口的**共用那一半**：合并 jar 里的 `BDCLND` → 带 cookie 发 → 抠 `sign`。
     *
     * ⚠️ 显式 `Cookie` 头会**顶掉** jar（§4.81）：`BDCLND`（提取码换来的放行票据）只活在
     * jar 里，加密分享少了它这一步也会失败。所以与 [pageOf] 同一条规矩 —— 先合 jar 再发。
     *
     * `Referer` 用**分享页**：`sign` 是按页面签发的，而这两步都是"站在分享页上"做的 ——
     * 站根（[WEB]）等于换了个来路，与 §4.84 那条 `113`（"签名按页面签发"）同源。
     *
     * 任何异常都吞成 `null`：这是"多试一个来源"，不许把主路的错误顶掉（见 [tplSign] 头注）。
     */
    private suspend fun signAt(what: String, url: String, link: PanLink, ck: String): BaiduSign? = try {
        step = what
        val merged = PanCloudDrive.mediaCookie(ck, Http.cookieValuesFor(url), null)
        templateSign(
            Http.get(
                url, referer = sharePageUrl(link.id), ua = PAN_UA,
                headers = jsonAccept + cookie(merged)
            )
        )
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------ 内部

    private fun u(s: String): String =
        runCatching { URLEncoder.encode(s, "UTF-8") }.getOrDefault(s)

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    private fun cookie(ck: String?) =
        if (ck.isNullOrBlank()) emptyMap() else mapOf("Cookie" to ck)

    /** JSON 接口一律显式声明 Accept —— 百度按它做内容协商，用 HTML 的 Accept 会回 HTML */
    private val jsonAccept = mapOf("Accept" to "application/json, text/plain, */*")

    private fun stepAt(): String = if (step.isBlank()) "" else "·$step"

    private suspend fun getText(
        url: String,
        ck: String?,
        what: String,
        json: Boolean = true
    ): String? = try {
        step = what
        Http.get(
            url, referer = WEB, ua = PAN_UA,
            headers = (if (json) jsonAccept else emptyMap()) + cookie(ck)
        )
    } catch (e: Exception) {
        err = classify(e)
        null
    }

    /** 表单 POST（带可选 `Cookie`）。`ck = null` 表示**走 CookieJar**（`share/verify` 要这样） */
    private suspend fun postForm(
        url: String,
        params: Map<String, String>,
        ck: String?,
        what: String,
        /**
         * `Referer`。默认是站根（[WEB]）—— 但 `/api/sharedownload` 必须用**分享页地址**，
         * 理由见 [stream] 里那一处调用点的注释。
         */
        referer: String = WEB
    ): String? = try {
        step = what
        Http.postForm(url, params, referer = referer, ua = PAN_UA, headers = cookie(ck))
    } catch (e: Exception) {
        err = classify(e)
        null
    }

    /** 从后端的原话里取名（`errmsg` / `error_msg` / `show_msg` 三种都见过） */
    private fun msgOf(body: String): String {
        for (k in arrayOf("errmsg", "error_msg", "show_msg", "error")) {
            val v = Regex("\"$k\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
                ?.groupValues?.get(1)
            // ⚠️ 百度把中文写成 `\uXXXX` 转义（`errmsg:"\u9a8c\u8bc1\u7801\u7b7e\u540d\u9519\u8bef"`）。
            // 不还原的话，气泡里显示的是一串反斜杠转义 = 等于没有文案（§4.84）。
            if (!v.isNullOrBlank()) return unescape(v).take(60)
        }
        return ""
    }

    /**
     * 信封级的失败归类（判据**唯一出处**）。
     *
     * ⚠️ 这里的两类判据**分两种成分**：
     *  - `-21`（缺 `root=1`）与 `-6`（游客）是**实测/文档见过**的码，但**都不能当死链**：
     *    `-21` 在**失效分享**上也会出现（§4.80）⇒ 死链判据只认**分享页自己说**的
     *    `share_page_type=error`（见 [pageOf]），不认这个码。
     *  - `需要登录` 的文案匹配是**保守兜底**：取直链那一段还没有实测记录，
     *    真拿到错误码后应当回来收紧 [isNeedLogin]。
     */
    private fun note(errno: Int, rawMsg: String) {
        val msg = rawMsg.take(60)
        err = when {
            isNeedLogin(errno, msg) -> {
                DriveStore.markExpired(type)
                PanError.NeedLogin(needLoginMsg(type), type)
            }
            else -> PanError.Broken("${type.label}${stepAt()}接口返回 errno $errno：$msg")
        }
    }

    private fun isNeedLogin(errno: Int, msg: String): Boolean =
        errno == -6 || msg.contains("登录")

    private fun httpCode(e: Exception): Int =
        Regex("HTTP (\\d{3})").find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun classify(e: Exception): PanError {
        // ① 信封优先：服务端自己说的那句 `errno`/`errmsg` 比 HTTP 状态码准得多
        val he = e as? Http.HttpError
        val raw = he?.body.orEmpty()
        if (raw.trimStart().startsWith("{")) {
            val en = errnoOf(raw)
            if (en != null && en != 0) {
                note(en, msgOf(raw))
                err?.let { return it }
            }
        }
        val code = he?.code ?: httpCode(e)
        if (code <= 0) {
            return PanError.Net(
                "${type.label}${stepAt()}：网络请求失败（${e.javaClass.simpleName} " +
                        "${e.message.orEmpty().take(60)}）"
            )
        }
        return PanCloudDrive.errorForHttp(code, type, step)
    }

    /**
     * 播放器分片要带的头。
     *
     * ⚠️ **这一段是待实测的**（spike ⑥）：夸克那边实测"只认 Cookie"，
     * 而百度的 dlink 对 UA / Referer / Range 的校验强度**还没测过**。
     * 先按"带全"发（Cookie 是确定的，UA/Referer 是与页面对齐的常规身份），
     * 实测后按结论**收紧** —— 多带的头只会宽松，不会让本来能播的变成不能播。
     *
     * 直链**不落盘、不缓存**（§6.4 的纪律）：这里只当次交给播放器。
     */
    private fun mediaHeaders(ck: String): Map<String, String> = mapOf(
        "Cookie" to ck,
        "Referer" to WEB,
        "User-Agent" to PAN_UA
    )
}

/**
 * 百度分享页 `yunData` 里我们关心的那几个字段。
 *
 * [bdstoken] / [sign] / [timestamp] 在**匿名**页面上是空的（或没有）——
 * 只有 [shareid] / [uk] 是匿名可得的。`sign` 必须在登录态下重取页面才有。
 */
data class BaiduShare(
    val shareid: String,
    val uk: String,
    val bdstoken: String = "",
    val sign: String = "",
    val timestamp: Long = 0L
)

/**
 * `/api/gettemplatevariable` 里抠出来的**账号级签名**（[templateSign] 的返回）。
 *
 * 三样是**一份**：`sign` 与 `timestamp` 配套签发，混用另一份的时间戳会被服务端判成
 * 「验证码签名错误」（`errno=113`，§4.84）—— 所以这里整份带走，不拆开发。
 */
data class BaiduSign(
    val sign: String,
    val timestamp: Long = 0L,
    val bdstoken: String = ""
)
