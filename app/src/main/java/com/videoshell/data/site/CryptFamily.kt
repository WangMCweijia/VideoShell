package com.videoshell.data.site

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.videoshell.App
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ## 加密接口站的「家族自证」（v1.0.35）
 *
 * 以前白名单只认**域名**（[CryptRecipes.forUrl]）。这类站最要命的特点恰恰是
 * **域名会轮换**：野果实测换过 `yeguodj.com` → `capable.fzchosdi.cc` →
 * `agenda.fzchosdi.cc`。每换一次，用户手里的旧域名失效、新域名又不在白名单里
 * ⇒ 静默退回 HTML 适配。
 *
 * 而它在 HTML 适配下**必然搜不出东西**：本站的搜索是纯接口的，SSR 页面里根本没有结果节点。
 * 这就是用户报的「野果搜索无效，但网页端能搜索」——网页端能搜，是因为它在浏览器里
 * 跑完了 JS、调的就是那个接口。
 *
 * 所以判据不能再是「域名对不对」，必须是**能自证的事实**：
 *
 * > 拿我们手上的密钥去解它的接口响应，**解得开** ⇒ 它就是这一族。
 *
 * 这条判据有三个好性质：
 *
 * 1. **域名无关** —— 站方在自己的域名上反代了 `api.php`，所以 apiBase 直接取
 *    `{origin}/api.php`（实测 HTTP 200、`errcode:0`、解出 49499 B 配置）；
 * 2. **密钥轮换不会假装成功** —— 解不开就是解不开（[CryptApi.call] 的 `lastError`
 *    会写「解密失败（站点可能已更换密钥）」），于是可以顺势切到 [CryptDiscovery]；
 * 3. **判据只有一份** —— 走的就是运行时那条 [CryptApi.call] 解密链路，
 *    不存在「自检说能通、真跑不通」。
 *
 * 结论（**含否定结论**）会落盘：探测只在一个陌生域名上发生一次，
 * 之后无论命中与否都是零成本。否定也要落盘 —— 否则每个 HTML 站每次启动都要多一次请求。
 */
object CryptFamily {

    private const val SP = "videoshell_crypt_family"
    private const val PREFIX = "c_"

    /** 否定结论的占位值（存字符串比存 JSON 省事，且一眼能看懂） */
    private const val ABSENT = "!"

    private val gson = Gson()

    /** 进程内缓存（磁盘不可用的离线 harness 靠它） */
    private val mem = HashMap<String, Rec>()

    /** 同一个 host 只探一次：并发调用排队而不是各探一遍 */
    private val lock = Mutex()

    /**
     * 一条落地记录。
     *
     * 密钥字段可空 —— 正常情况用内置密钥（[CryptRecipes.template]），
     * 只有 [CryptDiscovery] 挖到**新密钥**时才写进来（站点换密钥后的自愈路径）。
     */
    private data class Rec(
        val apiBase: String? = null,
        val absent: Boolean = false,
        val key: String? = null,
        val iv: String? = null,
        val mediaKey: String? = null,
        val mediaIv: String? = null
    )

    /** 判定结果的三态。**必须区分 Unknown 和 Absent**：前者该去探，后者不该再探 */
    sealed class State {
        /** 还没判定过 */
        object Unknown : State()
        /** 已判定「不是这一族」（或够不着） */
        object Absent : State()
        /** 已自证命中 */
        data class Hit(val recipe: CryptRecipe) : State()
    }

    // ------------------------------------------------------------------ 读

    /** 只读缓存，零网络。命中白名单的站根本不用走到这里 */
    fun cachedState(baseUrl: String): State {
        val h = hostOf(baseUrl)
        if (h.isBlank()) return State.Unknown
        val rec = mem[h] ?: loadRec(h) ?: return State.Unknown
        return toState(rec)
    }

    /** 自检/诊断用：这个域名现在是三态里的哪一种（人话） */
    fun describe(baseUrl: String): String = when (val s = cachedState(baseUrl)) {
        is State.Unknown -> "未判定（首次访问该域名时会现场自证一次）"
        is State.Absent -> "已判定**不是**本族（不会重复探测）"
        is State.Hit -> "已自证命中，接口基址 ${s.recipe.apiBase}"
    }

    /** 站点配方重置时一起清掉，否则用户没有回头路 */
    fun forget(baseUrl: String) {
        val h = hostOf(baseUrl)
        if (h.isBlank()) return
        mem.remove(h)
        runCatching { spOrNull()?.edit()?.remove(PREFIX + h)?.apply() }
    }

    // ------------------------------------------------------------------ 写

    /**
     * 现场自证（**每个域名只跑一次**）。命中返回按该站 origin 修正过 apiBase 的配方。
     *
     * 顺序刻意是「先证明、再登记」：探到不算数，**解得开才算数**。
     * 否定结论同样落盘，并且**只把内置密钥这条否定掉** —— 挖到新密钥的站
     * 由 [rememberDiscovered] 单独登记，不受这里影响。
     */
    suspend fun resolve(baseUrl: String, referer: String = baseUrl): CryptRecipe? {
        val h = hostOf(baseUrl)
        if (h.isBlank()) return null
        cachedState(baseUrl).let { if (it is State.Hit) return it.recipe }

        return lock.withLock {
            // 排队期间别人可能已经探完了
            (cachedState(baseUrl) as? State.Hit)?.let { return@withLock it.recipe }
            val origin = originOf(baseUrl)
            val tpl = CryptRecipes.template()
            CryptApi.probe(tpl, origin, referer)?.let { hit ->
                save(h, Rec(apiBase = hit.apiBase))
                return@withLock hit
            }
            // 解不开：先看是不是"密钥换了"。挖得到就自愈，挖不到就记否定。
            CryptDiscovery.fromSite(origin, referer)?.let { cfg ->
                val fresh = tpl.copy(
                    apiBase = "$origin/api.php",
                    keySpec = cfg.key,
                    ivSpec = cfg.iv,
                    mediaKeySpec = cfg.mediaKey ?: tpl.mediaKeySpec,
                    mediaIvSpec = cfg.mediaIv ?: tpl.mediaIvSpec
                )
                CryptApi.probe(fresh, origin, referer)?.let { hit ->
                    save(h, Rec(apiBase = hit.apiBase, key = cfg.key, iv = cfg.iv,
                        mediaKey = cfg.mediaKey, mediaIv = cfg.mediaIv))
                    return@withLock hit
                }
            }
            save(h, Rec(absent = true))
            lastProbeNote = CryptApi.lastProbeNote +
                    CryptDiscovery.lastNote.takeIf { it.isNotBlank() }?.let { "｜挖掘：$it" }.orEmpty()
            null
        }
    }

    /** 自检报告文案：这一族为什么没生效（把「够不着」「解不开」「没挖到」分开说） */
    var lastProbeNote: String = ""
        private set

    // ------------------------------------------------------------------ 内部

    private fun toState(rec: Rec): State {
        if (rec.absent) return State.Absent
        val base = rec.apiBase ?: return State.Unknown
        val tpl = CryptRecipes.template()
        return State.Hit(
            tpl.copy(
                apiBase = base,
                keySpec = rec.key ?: tpl.keySpec,
                ivSpec = rec.iv ?: tpl.ivSpec,
                mediaKeySpec = rec.mediaKey ?: tpl.mediaKeySpec,
                mediaIvSpec = rec.mediaIv ?: tpl.mediaIvSpec
            )
        )
    }

    private fun save(h: String, rec: Rec) {
        mem[h] = rec
        runCatching { spOrNull()?.edit()?.putString(PREFIX + h, gson.toJson(rec))?.apply() }
    }

    private fun loadRec(h: String): Rec? {
        val s = runCatching { spOrNull()?.getString(PREFIX + h, null) }.getOrNull() ?: return null
        if (s == ABSENT) return Rec(absent = true)      // 兼容手写的否定值
        val rec = runCatching { gson.fromJson(s, Rec::class.java) }.getOrNull() ?: return null
        mem[h] = rec
        return rec
    }

    private fun spOrNull(): SharedPreferences? =
        runCatching { App.instance.getSharedPreferences(SP, Context.MODE_PRIVATE) }.getOrNull()

    fun hostOf(baseUrl: String): String =
        Regex("^https?://([^/]+)", RegexOption.IGNORE_CASE)
            .find(baseUrl.trim())?.groupValues?.get(1)?.lowercase().orEmpty()

    /** `https://a.b.cc/x/y` → `https://a.b.cc`（站点自己的域名，反代 api.php 的那个） */
    fun originOf(baseUrl: String): String =
        Regex("^(https?://[^/]+)", RegexOption.IGNORE_CASE)
            .find(baseUrl.trim())?.groupValues?.get(1)?.trimEnd('/')
            ?: baseUrl.trim().trimEnd('/')
}
