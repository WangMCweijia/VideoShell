package com.videoshell.data.net

import com.google.gson.JsonParser

/**
 * 应用内**自更新**：读一份公开的 `version.json`，比版本号，给出该下哪个包。
 *
 * ## 为什么要有它
 *
 * 在这之前，装新版只有一条路：拿数据线连电脑，或者把 APK 从聊天窗口里翻出来点安装。
 * 结果是「明明修好了，用户手上还是老版本」—— 而绝大多数"这个 bug 还在"的反馈，
 * 根因都在这（同一版本号的两代包也是同一个坑，见 PITFALLS §4.38）。
 *
 * ## 清单从哪来
 *
 * CI 在打 tag 发版时**顺手生成** `version.json`，和 APK 一起传进同一个 Release：
 * `.../releases/latest/download/version.json` 是个**固定地址**（latest 永远指向最新那个
 * 正式 Release），所以 App 里不需要跟着版本号改 URL。清单里的 `apkUrl` 则指向**带版本号**
 * 的那个 asset —— 这样清单和包永远成对，不会出现"清单说 1.0.55、下下来的却是 1.0.54"。
 *
 * ⚠️ 前提是仓库可被匿名读取（public）。private 仓库的 release asset 对未认证请求返回 404，
 * 这一点很容易被误读成"没发出去" —— 所以失败原因要分开报，别都说成"检查更新失败"。
 *
 * ## 两条防线
 *
 * 1. **只收 https + GitHub 域**的 `apkUrl`：清单本身走 HTTPS，但把"任意 URL 也能装"
 *    这条路堵掉 —— 否则清单里一个笔误就能让 App 去下载第三方地址。
 * 2. **下载后校验 sha256**：清单里带摘要，包下完先算再比对，对不上直接丢弃。
 *    这一条才是真正防"下到一半/被替换"的判据（见 [UpdateDownloader]）。
 */
object UpdateChecker {

    /** 固定地址：latest 永远指向最新的正式 Release，所以这里不用跟版本号 */
    const val MANIFEST_URL =
        "https://github.com/WangMCweijia/VideoShell/releases/latest/download/version.json"

    /** 清单里允许出现的下载域名 —— 少写一个是**故意的**：出错时要落到"失败"，不是"装了个别的" */
    private val ALLOWED_HOSTS = listOf("github.com", "objects.githubusercontent.com",
        "github-releases.githubusercontent.com")

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val size: Long,
        val notes: String
    )

    sealed class State {
        /** 已是最新（或清单里的版本不比本机新） */
        object Latest : State()
        data class Newer(val info: UpdateInfo) : State()

        /**
         * 失败。`reason` 要能区分"没发版/仓库不可读/没网络/清单格式不对"——
         * 一律说"检查更新失败"等于把排查成本全推给用户。
         */
        data class Failed(val reason: String) : State()
    }

    /**
     * 检查更新。
     *
     * @param currentVersionCode 本机 `BuildConfig`/PackageInfo 里的 versionCode
     * @param manifestUrl        默认 [MANIFEST_URL]（测试可注入）
     */
    suspend fun check(
        currentVersionCode: Int,
        manifestUrl: String = MANIFEST_URL
    ): State {
        // 用会抛异常的 get（而不是 getOrNull）：失败原因要能报出来。
        // getOrNull 把异常吞成 null，那样"仓库是 private"和"DNS 挂了"会变成同一句话，
        // 而这两者的下一步动作完全不同（改仓库可见性 vs 检查网络）。
        val body = try {
            Http.get(manifestUrl)
        } catch (e: Exception) {
            return State.Failed("网络异常：${e.javaClass.simpleName} ${e.message.orEmpty()}")
        }
        if (body.isBlank()) return State.Failed(
            "取不到 version.json —— 可能是：① 还没打 tag 发过版；② 仓库是 private（未认证读不到）；" +
                    "③ 本机网络不通"
        )

        val parsed = try {
            parse(body)
        } catch (e: Exception) {
            return State.Failed("清单格式不对：${e.message.orEmpty()}")
        }
        return when {
            parsed == null -> State.Failed("清单里没有可用的版本号/下载地址")
            parsed.versionCode <= currentVersionCode -> State.Latest
            else -> State.Newer(parsed)
        }
    }

    /**
     * 解析清单。**任何一项不成立都返回 null 或抛异常**，绝不"能凑合就凑合" ——
     * 一个半残的清单会导致装上一个来路不明的包，比"检查失败"糟得多。
     */
    internal fun parse(body: String): UpdateInfo? {
        val o = JsonParser.parseString(body).asJsonObject
        val code = o.get("versionCode")?.takeIf { it.isJsonPrimitive }?.asInt ?: return null
        val name = o.get("versionName")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val apk = o.get("apkUrl")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        if (code <= 0 || apk.isBlank()) return null
        if (!apk.startsWith("https://")) {
            throw IllegalArgumentException("下载地址不是 https（拒绝明文传输安装包）")
        }
        val host = Regex("^https://([^/]+)", RegexOption.IGNORE_CASE)
            .find(apk)?.groupValues?.get(1)?.lowercase().orEmpty()
        if (ALLOWED_HOSTS.none { host == it || host.endsWith(".$it") }) {
            throw IllegalArgumentException("下载地址的域名「$host」不在白名单里")
        }
        val sha = o.get("sha256")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().lowercase()
        if (sha.isNotEmpty() && !Regex("^[0-9a-f]{64}$").matches(sha)) {
            throw IllegalArgumentException("sha256 不是合法的 64 位十六进制")
        }
        val size = o.get("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
        val notes = o.get("notes")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        return UpdateInfo(code, name, apk, sha, size, notes)
    }
}
