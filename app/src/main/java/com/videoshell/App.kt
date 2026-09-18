package com.videoshell

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.videoshell.data.net.Http

class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 暗色模式（默认开，跟随「我的」里的开关）
        val dark = getSharedPreferences("videoshell", MODE_PRIVATE)
            .getBoolean("setting_dark", true)
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
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
            .build()

    companion object {
        lateinit var instance: App
            private set
    }
}
