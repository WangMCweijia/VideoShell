package com.videoshell.data.pan

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * ## 网盘凭据的本地存储
 *
 * 这是「登录放软件里，而不是放源里」这句话的落地处。与 TVBox 的区别是**刻意**的：
 * TVBox 把 cookie 写进站点配置的 `ext`（夸父站那种做法，一份明文 cookie 跟着源到处传），
 * 我们把它放在 App 自己的加密存储里，且**与站点解耦** —— 站挂了、换了域名，登录态还在。
 *
 * 三条设计决定：
 *
 * 1. **key 与 TVBox 的凭据文件一一对应**（`quark.cookie` / `uc.cookie` / `ali.token` …）。
 *    排查时能直接对照 TVBox 那边的 `quark_cookie.txt`，少一层翻译。
 * 2. **minSdk 21 用不了 `androidx.security:security-crypto`（它要 23）**，
 *    所以直接走 `AndroidKeyStore` 的 AES/GCM（API 23+）；21/22 退化为 `MODE_PRIVATE` 明文
 *    并在值前缀里**标明**（`0:`）—— 宁可如实降级并留下痕迹，也不要假装加密了。
 * 3. 落盘值一律带算法前缀（`1:` = AES/GCM，`0:` = 明文），所以**换实现不必清数据**：
 *    读到旧前缀就按旧方式解。
 *
 * ⚠️ 凭据**只在这里落地**。网盘直链（带时效签名）绝不落盘，见 [PanResolver] 的缓存纪律。
 */
object DriveStore {

    private const val SP = "videoshell_drives"
    private const val PREFIX_KEY = "s_"
    private const val PREFIX_AT = "at_"
    private const val PREFIX_BAD = "bad_"

    /** 进程内 front cache；磁盘不可用（离线 harness）时它就是唯一存储 */
    private val mem = HashMap<String, String>()

    private fun keyOf(t: PanType) = t.key + ".cookie"
    private fun tokOf(t: PanType) = t.key + ".token"

    private fun spOrNull(): SharedPreferences? =
        runCatching {
            com.videoshell.App.instance.getSharedPreferences(SP, Context.MODE_PRIVATE)
        }.getOrNull()

    // ------------------------------------------------------------------ 通用读写

    fun get(key: String): String? {
        mem[key]?.let { return it }
        val raw = runCatching { spOrNull()?.getString(PREFIX_KEY + key, null) }.getOrNull()
            ?: return null
        val plain = Sealer.open(raw)
        if (plain != null) mem[key] = plain
        return plain
    }

    fun put(key: String, value: String?) {
        if (value.isNullOrBlank()) {
            mem.remove(key)
            runCatching { spOrNull()?.edit()?.remove(PREFIX_KEY + key)?.apply() }
            return
        }
        mem[key] = value
        runCatching {
            spOrNull()?.edit()?.putString(PREFIX_KEY + key, Sealer.seal(value))?.apply()
        }
    }

    /** 只给「网盘账号」页显示"什么时候更新的"，不代表凭据有效性 */
    fun updatedAt(t: PanType): Long =
        runCatching { spOrNull()?.getLong(PREFIX_AT + t.key, 0L) }.getOrNull() ?: 0L

    // ------------------------------------------------------------------ 凭据（按网盘）

    fun cookie(t: PanType): String? = get(keyOf(t))

    fun token(t: PanType): String? = get(tokOf(t))

    /**
     * 落一份 WebView 登录后的 cookie 快照。
     *
     * 只取一次快照、不长期依赖系统 CookieManager：WebView 的 cookie 是**全局共享**的，
     * 站点页面的 WebView 与网盘登录的 WebView 会互相污染；把它取出来放进我们自己的存储，
     * 之后所有网盘请求只带这一份（也便于用户"退出登录"时真的清掉）。
     */
    fun setCookie(t: PanType, cookie: String) {
        put(keyOf(t), cookie.trim())
        clearExpired(t)
        runCatching { spOrNull()?.edit()?.putLong(PREFIX_AT + t.key, System.currentTimeMillis())?.apply() }
    }

    fun setToken(t: PanType, token: String) {
        put(tokOf(t), token.trim())
        clearExpired(t)
        runCatching { spOrNull()?.edit()?.putLong(PREFIX_AT + t.key, System.currentTimeMillis())?.apply() }
    }

    fun clear(t: PanType) {
        put(keyOf(t), null)
        put(tokOf(t), null)
        clearExpired(t)
        runCatching { spOrNull()?.edit()?.remove(PREFIX_AT + t.key)?.apply() }
    }

    /** 有没有任何凭据 */
    fun has(t: PanType): Boolean = !cookie(t).isNullOrBlank() || !token(t).isNullOrBlank()

    /**
     * 凭据状态。
     *
     * `Expired` **只在真收到过 401/需要登录之后**才会成立（[markExpired] 写盘）——
     * 不能只凭"时间久了"就猜过期：网盘 cookie 的有效期动辄数月，猜错会让用户
     * 白白重登一次（这是"登录明明还有效却让我重登"的成因）。
     */
    fun state(t: PanType): DriveState = when {
        !has(t) -> DriveState.None
        (runCatching { spOrNull()?.getBoolean(PREFIX_BAD + t.key, false) }.getOrNull() ?: false) ->
            DriveState.Expired
        else -> DriveState.Valid
    }

    /** 接口回了 401/31001 之类的"需要登录"时调用 —— 下一次状态查询就会显示"已过期" */
    fun markExpired(t: PanType) {
        runCatching { spOrNull()?.edit()?.putBoolean(PREFIX_BAD + t.key, true)?.apply() }
    }

    fun clearExpired(t: PanType) {
        runCatching { spOrNull()?.edit()?.putBoolean(PREFIX_BAD + t.key, false)?.apply() }
    }

    // ------------------------------------------------------------------ 脱敏

    /**
     * 把凭据抹成 `**`，给日志/自检用。
     *
     * 为什么必须有它：`NetLog` 会把请求 URL 原样写进报告，而网盘接口的 URL 里
     * **带着 `stoken`**（夸克分享令牌）；一旦用户把自检报告贴出来求助，
     * 就等于把自己的分享令牌公开了。所有对外输出的凭据一律走这里。
     */
    fun mask(s: String?): String {
        if (s.isNullOrBlank()) return ""
        if (s.length <= 8) return "**"
        return s.take(4) + "**" + s.takeLast(2) + "(${s.length})"
    }

    /** 从一段 cookie 里拼出可直接用的请求头（失败/为空返回 null） */
    fun cookieHeader(t: PanType): String? = cookie(t)?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------ 加密层

    /**
     * `AndroidKeyStore` + AES/GCM。
     *
     * GCM 的 IV **每次加密随机生成**并前置在密文里（`iv || ct`），所以同一个明文每次
     * 落盘结果都不同 —— 这是必要的：固定 IV 下 GCM 会直接失去安全性（同密钥同 IV
     * 复用是 GCM 的致命用法）。
     *
     * API 21/22 没有 KeyStore 的 AES（`KeyGenParameterSpec` 要 23），如实退化为明文并打上
     * `0:` 前缀 —— 用户能看到自己是降级的那一档，而不是被"加密"两个字误导。
     */
    private object Sealer {

        private const val ALIAS = "videoshell_drive_key"
        private const val STORE = "AndroidKeyStore"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_LEN = 12
        private const val TAG_BITS = 128

        private val available: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

        private fun key(): SecretKey? {
            if (!available) return null
            return runCatching {
                val ks = KeyStore.getInstance(STORE).apply { load(null) }
                (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            }.getOrNull() ?: runCatching {
                val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
                gen.init(
                    KeyGenParameterSpec.Builder(
                        ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                gen.generateKey()
            }.getOrNull()
        }

        /** `1:` + base64(iv||ct) ；不可用时 `0:` + 原文 */
        fun seal(plain: String): String {
            val k = key() ?: return "0:" + plain
            return runCatching {
                val iv = ByteArray(IV_LEN).also { java.security.SecureRandom().nextBytes(it) }
                val c = Cipher.getInstance(TRANSFORM)
                c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv))
                val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
                "1:" + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
            }.getOrDefault("0:" + plain)
        }

        /** 解不出来返回 null（**不要**抛：一条坏记录不该让整个账号页起不来） */
        fun open(sealed: String): String? {
            if (sealed.startsWith("0:")) return sealed.substring(2)
            if (!sealed.startsWith("1:")) return sealed        // 兼容没有前缀的旧值
            val k = key() ?: return null
            return runCatching {
                val all = Base64.decode(sealed.substring(2), Base64.NO_WRAP)
                if (all.size <= IV_LEN) return null
                val iv = all.copyOfRange(0, IV_LEN)
                val ct = all.copyOfRange(IV_LEN, all.size)
                val c = Cipher.getInstance(TRANSFORM)
                c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv))
                String(c.doFinal(ct), Charsets.UTF_8)
            }.getOrNull()
        }
    }
}
