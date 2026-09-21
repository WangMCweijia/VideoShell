package com.videoshell

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.videoshell.data.net.Http

class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 深色模式三态（UI-4）：system = 跟随系统；light = 固定浅色；dark = 固定深色
        applyDarkMode(
            getSharedPreferences("videoshell", MODE_PRIVATE)
                .getString(KEY_DARK_MODE, "system").orEmpty()
        )
    }

    companion object {
        const val KEY_DARK_MODE = "setting_dark_mode"
        /** 后台播放开关（FN-4）：开 = 切到后台继续放，通知栏可控制 */
        const val KEY_BG_PLAY = "setting_bg_play"
        lateinit var instance: App
            private set

        /** 把偏好字符串映射到 AppCompatDelegate 的夜间模式 */
        fun applyDarkMode(mode: String) {
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }

    /**
     * 全局图片加载器：封面请求统一走 [Http.client]
     * （浏览器 UA 兜底 + CookieJar + IPv4 优先 DNS）。
     *
     * 背景：Coil 默认用自建的裸 OkHttp（UA=okhttp/4.x、无 Cookie、DNS 按系统
     * 顺序），部分图床 CDN 会按 UA/环境拒绝 —— 表现为"别的站封面都正常，
     * 偏偏某个站整页没图"（野果案例）。让封面和站点解析共用一套网络栈，
     * 两边行为一致，问题也查得清。
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .callFactory(Http.client)
            .crossfade(true)
            // 封面磁盘缓存（FN-8）：Coil 默认只有内存缓存，进程一退封面就没了 ——
            // 弱网/断网重进列表会整页空白。落一份到磁盘（100MB 上限）后，
            // 已经看过的封面就能直接命中，浏览体验和"内容缓存"是一回事。
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .build()
}
