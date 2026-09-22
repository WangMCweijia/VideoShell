package com.videoshell.data.net

import android.content.Context
import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.videoshell.data.Store
import java.io.ByteArrayInputStream

/**
 * [AdBlock] 的**运行时外壳**（v1.0.52）：把纯判据接到 WebView 与 SharedPreferences 上。
 *
 * 拆成两个文件是为了让判据可测：`AdBlock` 不 import 任何 Android 类，于是整套规则
 * 能在离线 harness（`runadb.py`）里对**真编译产物**逐条断言；这里则只做三件事 ——
 * 读开关、造空响应、留痕。
 *
 * ## 拦下来的东西必须留痕
 *
 * 被拦的地址全部进 [NetLog]（tag = 去广告）。嗅探报告里本来就带 `NetLog.report()`，
 * 于是"这个页面为什么和浏览器里长得不一样"有一条可粘贴的答案 —— 这类"看不见的过滤"
 * 如果只体现在结果上，事后根本分不清是站点的问题还是我们的问题（本项目吃过太多次）。
 */
object WebAdBlock {

    private const val TAG = "去广告"

    @Volatile
    private var resources = 0

    @Volatile
    private var navs = 0

    /** DOM 清扫累计隐藏的节点数（v1.0.57） */
    @Volatile
    private var domKills = 0

    fun on(ctx: Context): Boolean = Store.adBlock(ctx)

    fun setOn(ctx: Context, on: Boolean) = Store.setAdBlock(ctx, on)

    /** 这一页拦下的子资源数 */
    fun blockedResources(): Int = resources

    /** 这一页拦下的顶层跳转数 */
    fun blockedNavs(): Int = navs

    /** 这一页 DOM 清扫隐藏的广告节点数 */
    fun domKilled(): Int = domKills

    /** 换页 / 重新嗅探时清零（报告里的数字要是"这一页"的） */
    fun reset() {
        resources = 0
        navs = 0
        domKills = 0
    }

    /**
     * `shouldInterceptRequest` 里那一句：**非 null = 已拦下，直接返回给 WebView**。
     *
     * 返回值是空响应而不是"不发请求"：WebView 只有拿到响应才会认为这次请求结束了，
     * 否则脚本会一直等（表现为页面半死不活）。空响应让被拦的脚本立刻"加载完成"。
     */
    fun intercept(url: String?): WebResourceResponse? {
        val u = url ?: return null
        if (!AdBlock.blockedResource(u)) return null
        resources++
        NetLog.record(u, 0, 0, "已拦截（去广告）", TAG)
        return EMPTY
    }

    /**
     * 顶层跳转要不要拦。手势 / 重定向在 API 24 才有，老设备上按"有手势"处理 ——
     * 那一边只会少拦（少拦的代价是弹窗漏过去一次，多拦的代价是用户点不动页面）。
     */
    fun navBlocked(from: String, to: String, request: WebResourceRequest?): Boolean {
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
        val gesture = if (modern) (request?.hasGesture() ?: true) else true
        val redirect = if (modern) (request?.isRedirect() ?: false) else false
        val hit = AdBlock.blockNav(from, to, gesture, redirect)
        if (hit) {
            navs++
            NetLog.record(to, 0, 0, "已拦截跳转（去广告）", TAG)
        }
        return hit
    }

    /** 把"隐藏广告容器"的样式注入当前页（幂等；失败静默 —— 它只是锦上添花） */
    fun injectCss(web: WebView) {
        runCatching { web.evaluateJavascript(AdBlock.hideJs(), null) }
    }

    /**
     * 注入 **DOM 清扫**（v1.0.57，[AdBlock.sweepJs]）：CSS 与资源拦截之外的第三层，
     * 专收"运行时才插进来的宽幅图幅 / 大浮层"。回调里把新杀节点数留进
     * [NetLog] —— 和资源拦截一样，"看不见的过滤"必须留痕。
     */
    fun injectSweep(web: WebView) {
        runCatching {
            web.evaluateJavascript(AdBlock.sweepJs()) { v ->
                val n = v?.trim()?.removeSurrounding("\"")?.toIntOrNull() ?: 0
                if (n > 0) {
                    domKills += n
                    NetLog.record("(页面内 DOM)", 0, 0, "已隐藏 $n 个广告节点（清扫）", TAG)
                }
            }
        }
    }

    /** 报告里那一行 */
    fun reportLine(on: Boolean): String =
        "去广告：${if (on) "开" else "关"}：已拦 $resources 条资源 / $navs 次跳转" +
                (if (domKills > 0) " / 隐藏 $domKills 个广告节点" else "")

    private val EMPTY: WebResourceResponse by lazy {
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }
}
