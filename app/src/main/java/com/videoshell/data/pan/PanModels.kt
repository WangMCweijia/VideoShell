package com.videoshell.data.pan

/**
 * 网盘里的一个条目（文件或目录）。
 *
 * [token] 是**分享令牌**（夸克 `share_fid_token`）—— 取直链时必须连同 fid 一起提交，
 * 它是"这个文件确实来自那条分享"的凭据。**没有它就取不到流**，所以 [PanResolver] 展开时
 * 必须逐项带出来（只缓存 fid → name 是不够的，见 [PanTree]）。
 */
data class PanFile(
    val fid: String,
    val name: String,
    val dir: Boolean,
    val size: Long = 0L,
    val token: String = ""
) {
    /** 是不是"能播的视频"。判据是后缀 —— 网盘/分享站给的名字基本都带真实后缀。 */
    val isVideo: Boolean
        get() = !dir && VIDEO_EXT.any { name.endsWith(it, true) }

    companion object {
        val VIDEO_EXT = listOf(
            ".mp4", ".mkv", ".avi", ".ts", ".m2ts", ".mov", ".flv", ".wmv",
            ".rmvb", ".rm", ".webm", ".m4v", ".mpg", ".mpeg", ".vob", ".iso"
        )
    }
}

/**
 * 一条可播流的描述。
 *
 * [headers] 必须带上 `Cookie` —— 网盘直链的 CDN 会校验登录态，而**播放器发的分片请求
 * 走的是另一个 OkHttp 实例**（`Http.mediaClient`），不会自动带上我们在 App 里存的凭据。
 * 这是 P0 最容易漏、且症状最难查的一处（表现为"解析成功、一播就 403"）。
 *
 * [mime] 给 [com.videoshell.data.model.MediaSource.Direct] 用：网盘直链**常常没有扩展名**
 * （`…/file/download?fid=…`），media3 靠 URI 后缀推断内容类型会把 HLS 误判成 progressive，
 * 所以内容类型必须由取流方**自己报出来**。
 */
data class PanStream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val hls: Boolean = false,
    val mime: String? = null
)

/** 本地凭据状态（「网盘账号」页要能一眼看出是哪一种） */
enum class DriveState(val label: String) {
    /** 没登录过 */
    None("未登录"),

    /** 有凭据，且最近一次校验通过（或还没校验过） */
    Valid("已登录"),

    /** 有凭据但接口回了 401/需要登录 ⇒ 凭据过期，要重新登录 */
    Expired("登录已过期")
}

/**
 * 一个网盘 Provider 的**失败原因**。
 *
 * 必须能区分这几种，否则用户看到"解析失败"四个字无从下手（这是项目一贯的错误文案纪律）：
 *  - [NeedLogin]：没登录 / 凭据过期 ⇒ 可跳转「网盘账号」页；
 *  - [Dead]：分享链接失效 / 被删 ⇒ 换一条线路；
 *  - [Broken]：接口形状变了 / 服务端拒绝 ⇒ 这是我们的问题，要留痕；
 *  - [Net]：网络层失败 ⇒ 重试可能就好。
 */
sealed class PanError(val message: String) {
    class NeedLogin(message: String, val type: PanType) : PanError(message)
    class Dead(message: String) : PanError(message)
    class Broken(message: String) : PanError(message)
    class Net(message: String) : PanError(message)

    override fun toString(): String = message
}

/** 供 Provider 内部拼接错误文案：`未登录夸克网盘` */
internal fun needLoginMsg(t: PanType) = "未登录${t.label}（登录后可播放网盘直链）"

/**
 * 展开结果：分享文件夹 → 可播文件列表（**按名字自然序**）。
 *
 * 为什么要单独一个类型带上 [files] 与 [dirs] 计数：用户看到的可能是"0 集"，
 * 而原因有两种完全不同 —— 目录是空的 vs 递归深度被截断了。报告里要分得开。
 */
data class PanExpanded(
    val files: List<PanFile>,
    val dirsSeen: Int,
    val truncated: Boolean
)
