package com.videoshell.data.site

import com.videoshell.App
import com.videoshell.data.model.MediaSource
import com.videoshell.data.model.SiteConfig
import com.videoshell.data.net.Http
import com.videoshell.data.net.NetLog
import com.videoshell.player.HlsFixDataSource
import com.videoshell.player.OkHttpDataSource
import com.videoshell.player.PlayLog
import com.videoshell.util.resolveUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 站点自检：把整条链路在当前网络下跑一遍，每步都带上 **HTTP 状态码**。
 *
 * 为什么需要它：App 里所有失败都被 `getOrNull` 吞成空列表，用户看到的就是"空"，
 * 无法判断是 DNS/超时/403 还是解析不出东西。这份报告把判断依据直接摆出来，
 * 用户截个图就能定位 —— 不用再靠"猜哪一环断了"。
 *
 * 跑的顺序：首页 → 分类 → 列表 → 详情 → 选集 → 播放地址 → 媒体请求 → 首个分片。
 */
object SiteDoctor {

    suspend fun run(site: SiteConfig): String {
        NetLog.clear()
        NetLog.verbose = true
        val sb = StringBuilder()
        val L: (String) -> Unit = { sb.append(it).append('\n') }

        L("========== 站点自检 ==========")
        L("版本：v${appVersion()}")
        L("名称：${site.name.ifBlank { "(未命名)" }}")
        L("首页：${site.baseUrl}")
        L("模式：${site.apiMode}" + if (site.apiUrl.isBlank()) "（无采集接口 → HTML 适配）" else "，接口 ${site.apiUrl}")
        L("时间：" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date()))

        val a = AdapterFactory.create(site)
        L("适配器：${a.javaClass.simpleName}")
        L("")

        // 1) 首页
        val (hst, hinfo) = Http.probe(site.baseUrl, site.baseUrl)
        L("[1] 首页请求")
        L("    HTTP $hst")
        if (hinfo.isNotBlank()) L("    $hinfo")
        if (hst < 200 || hst > 399) {
            L("    → 这一步就没通，后面的分类/列表必然为空；先解决这一环（网络/DNS/WAF）")
        }

        // 2) 分类
        L("")
        L("[2] 分类解析")
        val catRes = runCatching { a.categories() }
        val cats = catRes.getOrElse { emptyList() }
        catRes.exceptionOrNull()?.let {
            L("    异常：${it.javaClass.simpleName}: ${it.message}")
        }
        L("    结果：${cats.size} 个分类")
        cats.take(12).forEach { L("      · ${it.name}   ${it.id}") }
        if (cats.isEmpty()) {
            val d = a.lastDiag
            L("    原因：" + d.ifBlank { "（未给出，可能是解析不出结构）" })
        }

        // [2a] 校准规则生效性 —— 直接回答「校准走完了为什么还按原规则显示」（v1.0.32）。
        // 以前报告里只有 [配方] 那一段的"来源：调试校准（人工）"，那只能证明**写盘了**，
        // 证明不了**用上了**；中间还夹着"命中加密白名单被跳过""形状只命中 1 个静默退回"
        // 这两条完全不同的路径。这里把适配器这一刻的真实决策摊开。
        L("")
        L("[2a] 校准规则是否生效")
        val recipeNow = RecipeStore.load(site.baseUrl)
        if (CryptRecipes.forUrl(site.baseUrl) != null && (recipeNow?.calibAt ?: 0L) > 0L) {
            L("    ⚠️ 本站命中了**加密接口白名单**，适配器固定是 ${a.javaClass.simpleName}：")
            L("       校准规则**按设计被跳过**（白名单优先级最高，见 AdapterFactory 的注释）。")
            L("       这是「校准了但没生效」的一种**确定**成因，不是 bug。")
        }
        val cd = a.calibDiag
        if (cd.isNotBlank()) {
            L("    " + cd.replace("\n", "\n    "))
            L(
                "    结论：" + if (a.calibApplied) "校准规则**已生效**（上面 [2] 那栏就是它收的）"
                else "校准规则**没有生效** —— 按上面 ⚠️ 指的方向处理（改点法 / 重新校准 / 用「站点配方重置」重来）"
            )
        } else {
            L("    （本适配器不参与网页校准 —— 接口型 / 加密型适配器没有「分类形状」这回事）")
        }

        // [2b] 搜索路由自证（v1.0.34）—— **对照组**
        //
        // 「能出结果」不是证据：「错误的输入也应该出零结果」才是。
        // 野果实测 `/?s=庆余年` 与 `/?s=zzzq不存在的词` 返回**与首页 sha256 完全相同**的页面
        // （246747 B），首页自带的 58 条详情链接里恰好有 3 条含「庆余年」——
        // 于是"严格遍"判定命中，把首页推荐位当成了搜索结果。所有常规判据全绿。
        //
        // 所以这里不数结果，而是**拿同一个模板发两次请求**（一个像样的关键词 + 一个不可能存在的词），
        // 比这两份响应是否同一个页面 —— 两个方向都不会错：
        //   · 两次一致 ⇒ 路由忽略关键词（软 404 回首页）⇒ 搜索不可用；
        //   · 对照词零条目、真词有条目 ⇒ 路由确实按关键词取结果 ⇒ 搜索正常。
        // 这对客户端渲染页同样成立（两次都是同一个空壳 ⇒ 也判"不可用"，而那是事实）。
        L("")
        L("[2b] 搜索路由自证（对照组）")
        val searchTplNow = RecipeStore.load(site.baseUrl)?.searchTpl
        if (searchTplNow.isNullOrBlank()) {
            L("    本地还没有搜索模板 —— 先搜索一次（模板在那次学习中固化），再回来跑自检")
        } else {
            L("    被测模板：$searchTplNow")
            val realKw = site.name.trim().take(2).takeIf { it.length >= 2 } ?: "电影"
            val fakeKw = "zzq9xk3"
            val u1 = searchTplNow.replace("{kw}", java.net.URLEncoder.encode(realKw, "UTF-8"))
            val u2 = searchTplNow.replace("{kw}", java.net.URLEncoder.encode(fakeKw, "UTF-8"))
            val h1 = runCatching { Http.getOrNull(u1, referer = site.baseUrl) }.getOrNull()
            val h2 = runCatching { Http.getOrNull(u2, referer = site.baseUrl) }.getOrNull()
            if (h1 == null || h2 == null) {
                L("    取不到页面（${if (h1 == null) "真关键词" else "对照词"}那一侧请求失败）—— 本次无法判定")
            } else {
                val s1 = com.videoshell.data.net.SoftMiss.sigOf(h1)
                val s2 = com.videoshell.data.net.SoftMiss.sigOf(h2)
                L("    真关键词「$realKw」：HTTP，${h1.length} B，唯一链接 ${s1.links} 条，链接集 ${s1.linkHash}")
                L("    对照词「$fakeKw」：HTTP，${h2.length} B，唯一链接 ${s2.links} 条，链接集 ${s2.linkHash}")
                L(
                    "    → " + when {
                        s1.len == s2.len && s1.linkHash == s2.linkHash ->
                            "两次响应**完全一致** ⇒ 该路由**忽略关键词**（软 404 回首页）" +
                                    "⇒ **本站的 URL 搜索不可用**，搜索会退回「学站点搜索表单」或需要 JS 渲染"
                        s2.links == 0 && s1.links > 0 ->
                            "真关键词有条目、对照词**零条目** ⇒ 路由**确实按关键词取结果**，搜索链路正常"
                        else ->
                            "两次响应不同，但对照词也出了 ${s2.links} 条 ⇒ 结果页可能混着推荐位，" +
                                    "搜索结果不保证精确（留意是否强相关）"
                    }
                )
            }
        }

        // 3) 列表
        val firstType = cats.firstOrNull()?.id ?: ""
        L("")
        L("[3] 列表解析" + if (firstType.isBlank()) "（首页/最新）" else "（分类：${cats.first().name}）")
        val listRes = runCatching { a.browse(firstType, 1) }
        val items = listRes.getOrElse { emptyList() }
        listRes.exceptionOrNull()?.let {
            L("    异常：${it.javaClass.simpleName}: ${it.message}")
        }
        L("    结果：${items.size} 条")
        items.take(3).forEach { L("      · ${it.name}  id=${it.id}") }

        if (items.isEmpty()) {
            L("")
            L("→ 列表为空，后续步骤无法继续。请把以上内容截图反馈。")
            appendTail(L, site)
            return sb.toString()
        }

        // [3a] 封面取图实测 —— "列表有数据但没图"时，这里直接给出图床的真实反应
        //（状态码 / DNS 对照），不再停留在"解析正常，图就是不出来"的悬案上。
        L("")
        L("[3a] 封面取图实测（列表首条）")
        val cover = items.firstOrNull { it.pic.isNotBlank() }?.pic
        if (cover.isNullOrBlank()) {
            L("    列表条目本身没带封面地址 —— 这是解析层的问题，不是图片加载问题")
        } else {
            L("    封面：${cover.take(160)}")
            // ⚠️ 加密图床必须**整段取**：Range 拿到的是密文的一段，AES-CBC 解不出来
            //（旧版固定用 bytes=0-1023，于是永远只看到"206 + 一坨乱码"，
            //  分不清是图床拒绝还是图片本来就是密的 —— 野果封面就卡在这里）
            val mediaRecipe = CryptRecipes.mediaRecipeFor(cover)
            if (mediaRecipe != null) {
                L("    图床形态：**加密图片**（AES-${mediaRecipe.mediaMode} 密文，不是 JPEG/PNG）")
                // 必须**分层取两次**，否则两层会被混成一句自相矛盾的话。
                //
                // v1.0.32 就是这么错的：那次只走 `fetchBytes`（它挂在带解密拦截器的
                // `Http.client` 上）⇒ 拿到手的字节**已经是解密的明文**，于是报告里写着
                // 「字节判定：已是明文图片（图床改回明文了，解密层会自动跳过）」，
                // 而同一份报告的 HTTP 记录却写着「[加密图已解密 65472->65471B image/jpeg]」。
                // 用户据此以为图床改版了、甚至可能去删掉那对 media_key/media_iv。
                //
                // 现在：① 原样字节（绕过解密层）= CDN 真正发来的东西；
                //       ② 取图链路（含解密层）= App 界面上真正会发生的事。
                // 两者一对照，"是不是密文""解密层有没有生效"就都成了可证伪的事实。
                val (rawCode, rawBytes, rawWhy) = Http.fetchBytesRaw(cover, site.baseUrl)
                val (appCode, appBytes, appWhy) = Http.fetchBytes(cover, site.baseUrl)
                val rawIsImg = AesCipher.isImage(rawBytes)
                val appIsImg = AesCipher.isImage(appBytes)
                val cdnLine = if (rawBytes == null || rawBytes.isEmpty()) "取不到（${rawWhy.take(60)}）"
                else "${rawBytes.size} B，" + (if (rawIsImg) "本身就是图片" else "**不是图片**")
                val appLine = if (appBytes == null || appBytes.isEmpty()) "取不到（${appWhy.take(60)}）"
                else "${appBytes.size} B，" + (if (appIsImg) "**是真图片**" else "**不是图片**")
                L("    ① CDN 原样响应（绕过解密层）：HTTP $rawCode，$cdnLine")
                L("    ② 取图链路（App 真实路径，含解密层）：HTTP $appCode，$appLine")
                L("    " + when {
                    appIsImg && rawIsImg ->
                        "判定：**图床本来就是明文图片** —— 解密层会自动跳过，封面链路正常"
                    appIsImg ->
                        "判定：密文 → **解密后是真图片** ⇒ 封面链路 OK（App 已自动解密，media_key 有效）"
                    rawIsImg ->
                        "判定：原样是图片、链路反而解不出 ⇒ 解密层误伤明文，请反馈这一条"
                    else ->
                        "判定：**解密失败** ⇒ 原样与解密后都不是图片。图床多半换了密钥，" +
                                "配方 media_key=${mediaRecipe.mediaKeySpec}"
                })
            } else {
                // 未收录的站：用**整段取图**走一遍 App 真实的图片链路。
                //
                // 这一步不只是"看一眼字节" —— 它让 [com.videoshell.data.net.ImageCipher]
                // 的拦截器有机会认出「图片路径上返回了一坨不是图片的东西」这种形态
                //（疑似加密图床），于是**任何新站**的加密图床都能在报告里被点名，
                // 不必等我们先把密钥逆向出来才知道问题在哪。
                //
                // ⚠️ 必须整段取、不能 Range：206 分片在密文上永远看不出魔数（见 ImageCipher 的门 2）。
                val (bcode, bbytes, bwhy) = Http.fetchBytes(cover, site.baseUrl)
                val bIsImg = AesCipher.isImage(bbytes)
                val bodyLine = when {
                    bbytes == null || bbytes.isEmpty() -> "取不到（${bwhy.take(50)}）"
                    else -> "${bbytes.size} B，" + (if (bIsImg) "**是图片**" else "**不是图片**")
                }
                L("    整段取图（App 真实链路）：HTTP $bcode，$bodyLine")
                if (!CryptRecipes.looksLikeImagePath(cover)) {
                    L("    路径判定：不像图片地址（无图片后缀）—— 可疑，请反馈这个地址")
                }
            }
            val host = runCatching { java.net.URI(cover).host }.getOrNull().orEmpty()
            com.videoshell.data.net.ImageCipher.suspectedBed(host)?.let { L("    ⚠️ $it") }
            if (host.isNotBlank()) {
                L("    ${Http.dnsReport(host)}")
                L("    解读：")
                L("      · 系统 DNS 为空/异常 ⇒ 污染；「有答案但连不上」同样算污染 —— 两种都已自动改走 DoH；")
                L("      · ① 不是图片、而 ② 是**真图片** ⇒ 加密图床，App 已在图片层解开（属正常，别去删 media_key）；")
                L("      · 带 ⚠️ 疑似加密图床 ⇒ 本站**未收录密钥**，封面一定出不来；把这几行反馈即可收录；")
                L("      · 整段取图**不是图片**且没有 ⚠️ ⇒ 图床本身异常（错误页 / WAF 截断），把这一行反馈；")
                L("      · 两边都正常但 HTTP 非 2xx ⇒ 图床按 UA/Referer/IP 拒绝，把状态码发回定位；")
                L("      · ② 已是真图片、界面仍无图 ⇒ 问题在图片加载层（Coil），请连同机型一起反馈。")
            }
        }

        // 4) 详情 + 选集
        val id = items.first().id
        L("")
        L("[4] 详情 + 选集（用第一条 id=$id）")
        val dRes = runCatching { a.detail(id) }
        val d = dRes.getOrNull()
        dRes.exceptionOrNull()?.let {
            // 只报异常第一行；详细清单由下面的「解析过程」给出，避免同一份内容出现两遍
            L("    异常：${it.javaClass.simpleName}: " +
                it.message.orEmpty().lineSequence().firstOrNull().orEmpty())
        }
        // 适配器记下的「试过哪些地址、各自什么结果」—— 详情失败时这一节就是答案本身
        val dTrace = a.lastDiag
        if (dTrace.isNotBlank()) {
            L("    解析过程：")
            dTrace.lines().take(24).forEach { line -> L("      $line") }
        }
        if (d == null) {
            L("    结果：失败")
            L("    → 把这份报告整体发回即可定位（上面已列出每个试过的地址与结果）")
            appendTail(L, site)
            return sb.toString()
        }
        L("    名称：${d.name}")
        L("    线路：${d.groups.size} 条")
        d.groups.take(6).forEach { g ->
            L("      · ${g.name}   ${g.episodes.size} 集"
                + (g.episodes.firstOrNull()?.let { "   首集 ${it.name} → ${it.url}" } ?: ""))
        }

        val ep = d.groups.firstOrNull()?.episodes?.firstOrNull()
        if (ep == null) {
            L("")
            L("→ 没有可用剧集，后续步骤无法继续。")
            appendTail(L, site)
            return sb.toString()
        }

        // 5) 播放地址解析
        L("")
        L("[5] 播放地址解析")
        val r = runCatching { a.resolve(ep) }
        r.exceptionOrNull()?.let {
            L("    异常：${it.javaClass.simpleName}: ${it.message}")
        }
        when (val ms = r.getOrNull()) {
            is MediaSource.Direct -> {
                L("    直链：${ms.url}")
                L("    HLS：${ms.isHls}    请求头：${ms.headers.keys.joinToString(" ")}")
                // 把「编码前长什么样」也摆出来：媒体路径常含中文，
                // 一眼就能看出这条地址有没有踩到"播放器不编码"那个坑。
                val rawUrl = percentDecode(ms.url)
                val hasNonAscii = rawUrl != ms.url
                if (hasNonAscii) {
                    L("    编码前：$rawUrl")
                    L("    ⚠️ 原地址含中文等非 ASCII 字符，已自动百分号编码")
                }

                // 6) 媒体请求 —— 直接看 CDN 给什么状态码
                L("")
                L("[6] 媒体请求")
                val (mst, minfo) = Http.probe(ms.url, site.baseUrl, "bytes=0-1023")
                L("    HTTP $mst")
                if (minfo.isNotBlank()) L("    $minfo")

                // 7) 首个分片 —— "能解析却放不出来"的最后一个盲区：
                //    playlist 拿 200 不代表分片也能下（分片常被单独做防盗链、或落在另一个 CDN）。
                //    这里**真的下一段**（512KB）并计时：旧实现只取 `bytes=0-1023`，
                //    1KB 在任何链路上都是秒回，只能证明"地址存在"，证明不了"扛得住播放"。
                L("")
                L("[7] 首个分片（真下 512KB 测速）")
                val seg = runCatching { firstSegment(ms.url, site.baseUrl, 0) }.getOrNull()
                if (seg == null) {
                    L("    没能从 playlist 里解析出分片地址")
                } else {
                    L("    ${seg.take(140)}")
                    val (sst, sbytes, sms) = Http.sample(seg, site.baseUrl, 512L * 1024)
                    val kbps = if (sms > 0) sbytes * 1000.0 / sms / 1024.0 else 0.0
                    L("    HTTP $sst   实际下载 ${sbytes / 1024}KB / ${sms}ms ≈ ${"%.0f".format(kbps)} KB/s")
                    L("    " + speedVerdict(kbps, sbytes))
                }

                // 8) 请求栈对照 —— 本次问题的关键证据。
                //    自检与抓页面走 OkHttp；而 ExoPlayer 的 DefaultHttpDataSource 底层是
                //    HttpURLConnection，**它不会自动对非 ASCII 路径做百分号编码**。
                //    同一个地址、同一个 CDN，两套栈结果可能完全不同 —— 这就是
                //    "自检全绿、播放全挂"唯一说得通的解释，而且能在你的设备上直接复现出来。
                L("")
                L("[8] 请求栈对照（播放器 vs 自检）")
                if (!hasNonAscii) {
                    L("    本地址是纯 ASCII，两套栈发出的字节一致 —— 播放失败不是这个原因")
                } else {
                    val okSt = Http.probe(ms.url, site.baseUrl, "bytes=0-1023").first
                    val hucEnc = withContext(Dispatchers.IO) { hucStatus(ms.url, site.baseUrl) }
                    val hucRaw = withContext(Dispatchers.IO) { hucStatus(rawUrl, site.baseUrl) }
                    L("    OkHttp（自检栈）        : HTTP $okSt")
                    L("    HttpURLConnection 编码后: HTTP $hucEnc")
                    L("    HttpURLConnection 未编码: HTTP $hucRaw   ← 修复前播放器发的就是这个形式")
                    L("    解读：未编码那一行若是 404/-1，说明中文被原样塞进请求行，CDN 找不到资源。")
                }

                // 9) 播放器栈实测 —— 直接用播放器那套 DataSource 跑一遍。
                //    "自检绿、播放挂"本质上是"两条栈不一样"。v1.0.9 起播放器也走 OkHttp，
                //    所以这里的结果**就是**播放器会看到的结果，两边再也不会分叉。
                L("")
                L("[9] 播放器栈实测（与播放器同一个 OkHttpDataSource）")
                val head = ms.headers
                val pl = withContext(Dispatchers.IO) { playStackProbe(ms.url, head, 0L) }
                L("    playlist : ${pl.first}   ${pl.second}")
                if (seg != null) {
                    val sg = withContext(Dispatchers.IO) {
                        playStackProbe(seg, head, 512L * 1024)
                    }
                    L("    分片     : ${sg.first}   ${sg.second}")
                }
                L("    说明：上面两行若是 200/206，说明**播放器那一侧的网络层是通的**；")
                L("         若这样还播不出来，原因就在解码/格式，而不是地址或链路。")
            }
            is MediaSource.Sniff -> {
                L("    HTML 抠不到直链 → 需要网页嗅探（第 6 步跳过）")
                L("    嗅探页：${ms.pageUrl}")
                // v1.0.31：先把"它是哪一种壳"说清楚。
                // 写「哪种壳都没认出」⇒ 这是新壳，抓一页补 [PlayerShell] 的分支即可；
                // 写了具体某种 ⇒ 是分支里的判据没生效，不是新壳。别再抓包猜。
                runCatching {
                    val ph = Http.getOrNull(ms.pageUrl, referer = site.baseUrl)
                    L("    外壳判定：${PlayerShell.describe(ph, ms.pageUrl)}")
                }
            }
            is MediaSource.Error -> L("    失败：${ms.message}")
            null -> L("    失败：未返回结果")
        }

        appendTail(L, site)
        return sb.toString()
    }

    /** 报告头带上版本号：免得"装的到底是哪个包"来回扯不清 */
    private fun appVersion(): String = runCatching {
        val c = App.instance
        c.packageManager.getPackageInfo(c.packageName, 0).versionName.orEmpty()
    }.getOrDefault("?")

    /**
     * 速度判语。
     * 经验阈值：单码率 HLS 的分片约 3~5 秒、300KB~1MB，要跟上播放至少得 ~100KB/s。
     */
    private fun speedVerdict(kbps: Double, bytes: Long): String = when {
        bytes <= 0L -> "→ 一个字节都没下下来：分片 CDN 可能被单独做了防盗链"
        kbps >= 800 -> "→ 链路充裕"
        kbps >= 300 -> "→ 够播（高清单码率约需 150~300KB/s）"
        kbps >= 100 -> "→ 偏慢，高码率片源会卡"
        kbps >= 30 -> "→ 很慢，播放大概率一直转圈"
        else -> "→ 极慢，基本等于连不通"
    }

    /**
     * 用**播放器同一套 DataSource**（`OkHttpDataSource` + `HlsFixDataSource`）真开一次地址。
     *
     * 这一步的意义：以前自检走 OkHttp、播放器走 HttpURLConnection，"自检绿、播放挂"
     * 无法在报告里复现。现在两边同栈，这里的结果就是播放器会看到的结果。
     */
    private fun playStackProbe(
        url: String,
        headers: Map<String, String>,
        maxBytes: Long
    ): Pair<String, String> {
        val ds = HlsFixDataSource(OkHttpDataSource(Http.mediaClient, Http.UA, headers))
        return try {
            val t0 = System.currentTimeMillis()
            val reported = ds.open(androidx.media3.datasource.DataSpec(android.net.Uri.parse(url)))
            // playlist 给 64KB 上限就够（够看全整份清单结构）；分片按调用方给的上限
            val cap = if (maxBytes > 0L) maxBytes else 64L * 1024
            val buf = ByteArray(32 * 1024)
            var n = 0L
            while (n < cap) {
                val want = minOf(buf.size.toLong(), cap - n).toInt()
                if (want <= 0) break
                val r = ds.read(buf, 0, want)
                if (r < 0) break
                n += r
            }
            val ms = System.currentTimeMillis() - t0
            runCatching { ds.close() }
            "OK（open 报 ${reported}B）" to "读到 ${n / 1024}KB / ${ms}ms"
        } catch (e: Exception) {
            val code = generateSequence<Throwable>(e) { it.cause }
                .filterIsInstance<androidx.media3.datasource.HttpDataSource
                    .InvalidResponseCodeException>()
                .firstOrNull()?.responseCode
            val msg = (if (code != null) "HTTP $code " else "") +
                e.javaClass.simpleName + ": " + (e.message ?: "").take(150)
            msg to "（未读到数据）"
        }
    }

    private fun appendNetLog(L: (String) -> Unit) {
        L("")
        L("---------- HTTP 记录 ----------")
        L(NetLog.report())
    }

    /**
     * 报告尾部：HTTP 记录 + 播放记录。
     *
     * 播放记录是这一版新加的通道 —— 播放器/嗅探自己失败的细节以前带不回来
     * （错误面板只能截图，嗅探页报告又复制不出来），现在随自检报告一起走。
     */
    private fun appendTail(L: (String) -> Unit, site: SiteConfig) {
        appendNetLog(L)
        L("")
        L("---------- 站点配方（已固化到本地，跨页面 / 跨启动复用） ----------")
        L(RecipeStore.describe(site.baseUrl))
        L("")
        L("---------- 播放记录（播放器 / 嗅探自己写的） ----------")
        L(PlayLog.report())
    }

    /** 把 URL 里的 `%XX` 还原回字节再按 UTF-8 解释：用于看"这条地址原本长什么样" */
    private fun percentDecode(url: String): String {
        if (!url.contains('%')) return url
        val out = StringBuilder(url.length)
        val buf = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < url.length) {
            val c = url[i]
            if (c == '%' && i + 2 < url.length) {
                val v = url.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    buf.write(v)
                    i += 3
                    continue
                }
            }
            if (buf.size() > 0) {
                out.append(String(buf.toByteArray(), Charsets.UTF_8))
                buf.reset()
            }
            out.append(c)
            i++
        }
        if (buf.size() > 0) out.append(String(buf.toByteArray(), Charsets.UTF_8))
        return out.toString()
    }

    /**
     * 用 `HttpURLConnection` 取状态码 —— 这就是 ExoPlayer `DefaultHttpDataSource` 的底层。
     * 用来在**真机上直接对照**两套请求栈对同一地址的反应，不用再靠推断。
     * 失败（含 URL 非法/连不通）返回 -1。
     */
    private fun hucStatus(url: String, referer: String): Int = runCatching {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.setRequestProperty("User-Agent", Http.UA)
        if (referer.isNotBlank()) c.setRequestProperty("Referer", referer)
        c.setRequestProperty("Range", "bytes=0-1023")
        c.connectTimeout = 8_000
        c.readTimeout = 8_000
        val st = c.responseCode
        runCatching { c.inputStream?.close() }
        runCatching { c.errorStream?.close() }
        c.disconnect()
        st
    }.getOrDefault(-1)

    /**
     * 从 playlist 里取**第一个分片**地址；遇到 master 清单（`#EXT-X-STREAM-INF`）先下沉一层。
     * 返回 null 表示拿不到（网络失败 / 不是 m3u8 / 只有标签没有分片）。
     */
    private suspend fun firstSegment(url: String, referer: String, depth: Int): String? {
        if (depth > 2) return null
        val text = Http.getOrNull(url, referer) ?: return null
        if (!text.contains("#EXTM3U")) return null
        val first = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            ?: return null
        val child = resolveUrl(url, first)
        if (child.isBlank()) return null
        return if (text.contains("#EXT-X-STREAM-INF")) firstSegment(child, url, depth + 1) else child
    }
}
