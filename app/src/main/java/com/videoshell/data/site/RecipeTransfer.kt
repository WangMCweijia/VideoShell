package com.videoshell.data.site

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 站点配方的**导出 / 导入**（v1.0.54）。
 *
 * ## 为什么要有它
 *
 * 配方（[SiteRecipe]）是"这台机器在这个站上学到的东西"—— 它最贵的部分不是代码能算出来的，
 * 而是**一次真人操作**：进调试校准模式、点分类、点影片、点分集。同一类站（比如一波 maccms
 * 换皮站）往往结构完全一样，第二台设备却要从零再点一遍。
 *
 * 导出 = 把这份学习结果变成一段可粘贴的文本；导入 = 让另一台设备直接拿到它。
 *
 * ## 三条纪律（都是踩过的坑，不是洁癖）
 *
 * 1. **导入的是外部输入，不能自带信任。** 所以导入时 [SiteRecipe.calibAt] 一律**归零**：
 *    该字段 > 0 表示"人工校准过"，而 [AdapterFactory] 会因此把它排在 apiMode 之前 ——
 *    让一段来路不明的文本获得最高优先级，等于把站型判定交给外部。
 *    归零后它退回"自动学习"这一档，能用、但不会压过现场证据。
 *
 * 2. **必须自描述 + 双向校验。** 只校验"是不是 JSON"远远不够：随手粘一段别的 JSON
 *    也会被当成配方。所以格式里带 `kind` 与两层版本号，且导入端逐条验。
 *
 * 3. **失败必须说清是哪一条不成立。** 「导入失败」四个字等于没报错 ——
 *    用户唯一能做的事就是重试一遍。每种拒绝都给出具体原因与下一步。
 */
object RecipeTransfer {

    /** 载荷标记：不是这个值的一律不当配方收 */
    const val KIND = "videoshell.recipe"

    /** 载荷结构版本（外层信封的版本，与 [SiteRecipe.VER] 各管一件事） */
    const val PAYLOAD_VER = 1

    private val gson = Gson()

    /**
     * 导出。没有配方（或配方是空的）返回 null —— 导出一段空配方只会让接收端困惑。
     */
    fun export(baseUrl: String): String? {
        val r = RecipeStore.load(baseUrl) ?: return null
        if (r.isEmpty) return null
        val host = RecipeStore.hostOf(baseUrl)
        if (host.isBlank()) return null
        val o = JsonObject()
        o.addProperty("kind", KIND)
        o.addProperty("ver", PAYLOAD_VER)
        o.addProperty("host", host)
        o.addProperty("exportedAt", System.currentTimeMillis())
        o.add("recipe", gson.toJsonTree(r))
        return gson.toJson(o)
    }

    /**
     * 导入结果。
     *
     * @param ok      是否写入成功
     * @param host    这份配方所属的 host（成功时非空）
     * @param reason  失败原因（**具体到哪一条不成立**，见类注释第 3 条）
     * @param note    成功时的提示（例如"已按自动学习级别生效"）
     */
    data class Result(
        val ok: Boolean,
        val host: String = "",
        val reason: String = "",
        val note: String = ""
    )

    /**
     * 导入一段文本。
     *
     * @param expectHost 当前站的 host。给了就**必须一致** —— 把 A 站的配方装到 B 站上
     *                   只会让 B 站解析变得更糟（而且很难查：看起来"套用成功"了）。
     *                   传 null 表示"按载荷自带的 host 落库"。
     */
    fun import(text: String, expectHost: String? = null): Result {
        val t = text.trim()
        if (t.isEmpty()) return Result(false, reason = "内容为空（剪贴板里没有文本）")

        val o = runCatching { JsonParser.parseString(t).asJsonObject }.getOrNull()
            ?: return Result(false, reason = "不是合法的 JSON —— 请确认复制的是完整配方文本")

        val kind = o.get("kind")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        if (kind.isBlank()) {
            return Result(false, reason = "缺少 kind 标记 —— 这不像是本 App 导出的配方")
        }
        if (kind != KIND) {
            return Result(false, reason = "kind = \"$kind\"，本 App 的配方是 \"$KIND\"")
        }

        val pv = o.get("ver")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        if (pv <= 0) return Result(false, reason = "缺少载荷版本号 ver")
        if (pv > PAYLOAD_VER) {
            return Result(false, reason = "载荷版本 $pv 比本机（$PAYLOAD_VER）新 —— 请先更新 App 再导入")
        }

        val host = o.get("host")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().trim().lowercase()
        if (host.isBlank()) return Result(false, reason = "缺少 host —— 不知道这份配方属于哪个站")
        if (host.contains('/') || host.contains(' ') || !host.contains('.')) {
            return Result(false, reason = "host「$host」不像一个域名")
        }

        val rj = o.get("recipe")
        if (rj == null || !rj.isJsonObject) return Result(false, reason = "缺少 recipe 主体")

        val r = runCatching { gson.fromJson(rj, SiteRecipe::class.java) }.getOrNull()
            ?: return Result(false, reason = "recipe 字段无法解析")
        if (r.ver != SiteRecipe.VER) {
            return Result(false, reason = "配方版本 ${r.ver} 与本机（${SiteRecipe.VER}）不同 —— " +
                    "模板语义已经变过，旧配方必须重新学（这是**故意**作废旧配方的机制）")
        }
        if (r.isEmpty) return Result(false, reason = "配方里没有任何模板/形状，导入它没有意义")

        val cur = expectHost?.trim()?.lowercase().orEmpty()
        if (cur.isNotBlank() && !sameHost(cur, host)) {
            return Result(false, reason = "这份配方属于「$host」，当前站是「$cur」—— " +
                    "套用到别的站只会让它解析得更糟，已拒绝")
        }

        // 纪律 1：外部输入不带信任
        val safe = r.copy(calibAt = 0L)
        val target = "https://" + host
        RecipeStore.save(target, safe)
        return Result(
            ok = true,
            host = host,
            note = "已导入（来源：外部文本，按「自动学习」级别生效，不覆盖现场证据）"
        )
    }

    /** 同 host 判定：忽略前缀 www.（两边都去，避免 www.x.com 与 x.com 互相认不出） */
    private fun sameHost(a: String, b: String): Boolean {
        val na = a.removePrefix("www.")
        val nb = b.removePrefix("www.")
        return na == nb
    }
}
