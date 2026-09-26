package com.videoshell.data.pan

import android.content.Context

/**
 * ## 网盘 Provider 的统一契约
 *
 * 与站层的 [com.videoshell.data.site.SiteAdapter] 是**两件事**，刻意分开：
 * 站点会换域名、会挂，而"夸克分享链接怎么变成直链"这件事不变。分开之后，
 * 站挂了不影响网盘层；某个盘的接口变了只改它自己那一个 Provider。
 *
 * 三条约定：
 * 1. **[list] 不需要登录**（实测夸克/UC 的 `sharepage/token` + `sharepage/detail` 匿名可调）；
 *    **[stream] 需要登录**（转存与取直链都必须带 cookie）—— 这不是设计选择，是接口事实。
 * 2. 每个 Provider **独立降级**：失败返回 null / 空表，并把原因写进 [lastError]，
 *    **绝不抛异常给上层**（上层是播放链路，抛出去就是一个无信息的崩溃）。
 * 3. 直链**绝不落盘**。Provider 只负责当次取用；缓存纪律见 [PanResolver]。
 */
interface PanProvider {

    val type: PanType

    /** 这个盘能不能**取直链**（未实现的盘返回 false ⇒ 详情页会明确写"暂不支持"） */
    val supported: Boolean get() = false

    /** 最近一次失败的原因（可能为 null = 没有失败）。每次调用入口先清空。 */
    val lastError: PanError?

    /**
     * 列目录。[fid] == null 表示**分享根**。
     *
     * 匿名即可调用。失败返回空表（不是 null）—— 调用方不需要区分"空目录"和"取不到"，
     * 但要能从 [lastError] 读到原因。
     */
    suspend fun list(link: PanLink, fid: String?): List<PanFile>

    /** 取直链。未登录 / 失败返回 null，原因在 [lastError] */
    suspend fun stream(ref: PanRef): PanStream?

    /** 校验本地凭据（「网盘账号」页用）。**网络失败不判过期** */
    suspend fun verify(): DriveState
}

/**
 * 占位 Provider：这一族里 P0 之外的那些盘。
 *
 * 存在的意义不是"留个坑"，而是**让"这个盘还不支持"成为一句明确的话**，
 * 而不是静默空表 —— 用户点进去看到"0 集"会以为是站的问题。
 */
class UnsupportedPan(override val type: PanType) : PanProvider {
    override val supported: Boolean get() = false
    override val lastError: PanError? get() = null
    override suspend fun list(link: PanLink, fid: String?): List<PanFile> = emptyList()
    override suspend fun stream(ref: PanRef): PanStream? = null
    override suspend fun verify(): DriveState = DriveState.None
}

object PanProviders {

    private val impls: Map<PanType, PanProvider> by lazy {
        val m = HashMap<PanType, PanProvider>()
        for (t in PanType.values()) m[t] = UnsupportedPan(t)
        // P0：夸克 + UC。实测这两站的公开资源只用这两家（快映另加百度）。
        // ⚠️ 这两个**必须**用同一个实现：夸克与 UC 是同一套云盘后端
        //（`/1/clouddrive/**` 同路径、同 `code`/`message` 信封，只有 host 与品牌不同）。
        PanCloudDrive.quark().let { m[it.type] = it }
        PanCloudDrive.uc().let { m[it.type] = it }
        // P1 的第一个：百度。选它不是因为"它有名"，而是**我们自己的样本里有它**
        //（`samples/panshare/ky_detail.html` 的 `data-clipboard-text` 里，
        //  夸克旁边挂的就是 `pan.baidu.com/s/1…?pwd=…`）。
        // ⚠️ 它只**实测了一半**：匿名列目录（含子目录）已确认，取直链那一半按文档形状实现、
        //    待一次真实登录态复跑（见 [PanBaidu] 类文档）。所以 `supported` 是 true ——
        //    "匿名能展开真实集数"这件事成立，那半本来就该给用户；取流失败时给的是
        //    **带 errno 原话的**文案，而不是"点了没反应"。
        PanBaidu.baidu().let { m[it.type] = it }
        m
    }

    fun of(t: PanType): PanProvider = impls[t] ?: UnsupportedPan(t)

    /** 已实现取直链的盘（「网盘账号」页按这个顺序排版，未支持的排在后面） */
    fun ordered(): List<PanProvider> = PanType.values().map { of(it) }
        .sortedByDescending { it.supported }

    /** 供 [DriveStore] 之外的调用方拿 Context（目前只有账号页需要） */
    fun appContext(): Context = com.videoshell.App.instance
}
