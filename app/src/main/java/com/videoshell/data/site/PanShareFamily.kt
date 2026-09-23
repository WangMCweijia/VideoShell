package com.videoshell.data.site

import android.content.Context
import android.content.SharedPreferences
import com.videoshell.App

/**
 * ## 「网盘分享站族」的形状自证（v1.0.65）
 *
 * 这一族的判据**只能是形状**，不能是域名 —— 实测（2026-09-23）：
 * 快映 `http://xsayang.fun` 会 302 到 `http://43.248.128.118:12512/`，
 * 同站在配置里还挂着 `38.76.197.172:12521`。域名就是跳板，任何白名单都会在下一次轮换时失效。
 *
 * 与 [CryptFamily] / [SeedFamily] 同型：**判定结果（命中与否定都）落盘**，
 * 每个 host 只判一次，之后无论命中与否都是零成本。
 *
 * ## 判据（都在**同一份详情页 HTML** 上判定，不额外发请求）
 *
 * | # | 判据 | 必需 |
 * |---|---|---|
 * | D1 | 详情页存在 `.module-tab-item.downtab-item [data-dropdown-value]`（线路名） | 必需 |
 * | D2 | 详情页存在 ≥1 个 `[data-clipboard-text]`，且其值能被 [com.videoshell.data.pan.PanLink] 认出来 | 必需 |
 * | D3 | `player_aaaa` 出现 **0 次**（把它与 maccms 站区分开） | 加分 |
 * | D4 | 存在 `.module-item` 卡片（标准 maccms 模板） | 加分 |
 *
 * D1 + D2 同时成立 ⇒ 判定命中。这两条一起才够：只有 D1 会误伤"带下载面板的普通站"，
 * 只有 D2 会把任何贴了网盘链接的页面都算进来。**D2 的"值能被 PanLink 认出来"是关键** ——
 * 它把判据锚在"这条链接真的指向一个我们认识的网盘"，而不是"页面上有个复制按钮"。
 *
 * 实测这两条在**两个不同的 TVBox 实现类**（快映 `csp_PanWebShare` / 玩偶 `csp_Wogg`）上
 * 同时成立 —— HTML 形状完全同构 ⇒ 一个适配器吃两族，不必为每站写实现。
 */
object PanShareFamily {

    private const val SP = "videoshell_panshare_family"

    private const val HIT = "1"
    private const val ABSENT = "!"

    private val mem = HashMap<String, Boolean>()

    sealed class State {
        /** 还没判定过 —— 首次访问该 host 时会现场判一次（不用额外请求，详情页本来就要抓） */
        object Unknown : State()
        /** 已判定「不是这一族」 */
        object Absent : State()
        /** 已自证命中 */
        object Hit : State()
    }

    fun cachedState(baseUrl: String): State {
        val h = hostOf(baseUrl)
        if (h.isBlank()) return State.Unknown
        val v = mem[h] ?: load(h) ?: return State.Unknown
        return if (v) State.Hit else State.Absent
    }

    /** 自检用：人话 */
    fun describe(baseUrl: String): String = when (cachedState(baseUrl)) {
        is State.Unknown -> "未判定（首次进详情页时会现场判一次，不额外发请求）"
        is State.Absent -> "已判定**不是**网盘分享站族"
        is State.Hit -> "已自证命中**网盘分享站族**（详情页线路 = 网盘类型，点集走网盘直链）"
    }

    fun markHit(baseUrl: String) = save(baseUrl, true)

    fun markAbsent(baseUrl: String) = save(baseUrl, false)

    /** 站点配方重置时一起清掉，否则用户没有回头路 */
    fun forget(baseUrl: String) {
        val h = hostOf(baseUrl)
        if (h.isBlank()) return
        mem.remove(h)
        runCatching { spOrNull()?.edit()?.remove(h)?.apply() }
    }

    // ------------------------------------------------------------------ 内部

    private fun save(baseUrl: String, hit: Boolean) {
        val h = hostOf(baseUrl)
        if (h.isBlank()) return
        mem[h] = hit
        runCatching { spOrNull()?.edit()?.putString(h, if (hit) HIT else ABSENT)?.apply() }
    }

    private fun load(h: String): Boolean? {
        val s = runCatching { spOrNull()?.getString(h, null) }.getOrNull() ?: return null
        val v = s == HIT
        mem[h] = v
        return v
    }

    private fun spOrNull(): SharedPreferences? =
        runCatching { App.instance.getSharedPreferences(SP, Context.MODE_PRIVATE) }.getOrNull()

    private fun hostOf(baseUrl: String): String =
        Regex("^https?://([^/]+)", RegexOption.IGNORE_CASE)
            .find(baseUrl.trim())?.groupValues?.get(1)?.lowercase().orEmpty()
}
