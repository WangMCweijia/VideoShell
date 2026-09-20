package com.videoshell.data.site

import com.videoshell.data.model.SiteConfig

/**
 * 按站点配置造适配器。
 *
 * ⚠️ 判定顺序很关键：**`apiUrl` 为空一律走 HTML 适配**。
 * 原实现写成 `when (site.apiMode) { ... else -> MaccmsAdapter(site) }`，
 * 于是 `apiMode` 只要不是预期值（例如老配置被 Gson 反序列化成 null —— Gson 用 Unsafe
 * 分配对象，Kotlin 的字段默认值不会生效），就会掉进 maccms 分支；
 * 而 maccms 分支依赖 `apiUrl` 拼接采集接口 —— `apiUrl` 为空时每次请求都失败，
 * 表现为**分类/列表/详情/选集全部静默为空**，很难定位。
 *
 * ## v1.0.15：人工校准过的站点**优先于一切**
 *
 * 采集接口模式与网页模式是两条完全独立的路：maccms 分支根本不读「站点配方」。
 * 于是只要 `apiMode` 是采集接口，**用户在调试校准模式里点出来的规则会被整个绕过** ——
 * 他明明校准成功了（还试播成功），重载站源却"规则没生效"。
 *
 * 而「校准过」（`calibAt > 0`）意味着：用户已经在真实网页里点通了 分类 → 详情 → 分集，
 * 并且那次播放**真的成功了**。这是比 `apiMode` 强得多的证据，必须压过它。
 * 典型触发场景：站点先被识别成有采集接口、后来接口被站方关掉（返回 `closed`），
 * 或用户把站点删掉重加了一遍 —— 两种情况都会让 apiMode 变回 maccms。
 *
 * ## v1.0.25：加密接口白名单**最优先**
 *
 * 有一类站把接口响应整体 AES 加密（[CryptRecipes]），HTML 模式与采集模式**都取不到数据**：
 * 抓页面只有 SEO 占位、抓接口拿到密文。对这类站，我们手上的「密钥配方」是**实测出来的硬知识**，
 * 比形状识别和人工校准都更准确 —— 所以命中白名单就直接用专用适配器，其余路径全部让开。
 *
 * ## v1.0.35：白名单之外，补一个「家族自证」
 *
 * 白名单只认**域名**，而这一族恰恰最会换域名：野果从 `yeguodj.com` 换到
 * `agenda.fzchosdi.cc` 之后，白名单不再命中 ⇒ 悄悄退回 HTML 适配 ⇒
 * **搜索彻底失效**（这一族的搜索只在接口里，SSR 页面没有结果节点）。
 * 用户看到的就是「App 搜不到，但网页端能搜」。
 *
 * 所以现在多一条路：域名不认识时，返回 [FamilyRouter] —— 它在**第一次真正解析时**
 * （那时已经在 IO 协程里）用一次请求做自证：「拿我们的密钥去解它的接口响应，解得开」
 * ⇒ 全线换接口。判定结果（含否定）落盘，每个域名只发生一次。
 *
 * 顺序：**域名白名单 → 已自证的域名 → 人工校准（压过采集接口）→ 采集接口 →
 * 网页解析（外边套家族路由）**。
 */
object AdapterFactory {
    fun create(site: SiteConfig): SiteAdapter {
        // ① 加密接口站：白名单命中即生效（密钥与算法见 CryptRecipes 的实测表）
        CryptRecipes.forUrl(site.baseUrl)?.let { recipe ->
            return YeguoAdapter(site, recipe)
        }

        // ② 这个域名已经自证过（命中与否定都落盘）⇒ 命中直接上接口，不再包路由
        val fam = CryptFamily.cachedState(site.baseUrl)
        if (fam is CryptFamily.State.Hit) return YeguoAdapter(site, fam.recipe)

        // ③ 人工校准过 ⇒ 网页解析被**真实验证过**（用户点了分类→详情→分集还试播成功），
        //    不许被采集接口模式绕过 —— 这是 v1.0.15 定下的规则。
        //    ⚠️ 但**不能**因此跳过家族路由：加密接口族的搜索只在接口里，校准 HTML 救不了它
        //    （见第 ⑤ 步）。这里只压制「采集接口」这一条分支。
        val calibrated = RecipeStore.load(site.baseUrl)?.let { it.calibAt > 0L } ?: false

        // ④ 站点自己的采集接口：有自己的原生数据源，不参与家族探测
        //    （⚠️ `apiUrl` 为空一律走 HTML —— 见文件头的说明）
        if (!calibrated && site.apiUrl.isNotBlank()) {
            when (site.apiMode) {
                SiteConfig.MODE_MACCMS_XML -> return MaccmsXmlAdapter(site)
                SiteConfig.MODE_HTML -> Unit
                else -> return MaccmsAdapter(site)
            }
        }

        // ⑤ 未知域名 ⇒ 套一层延迟家族自证：命中就整程换接口，不命中就原样用网页解析
        //    （已判定"不是本族"的域名直接返回 HtmlAdapter，零成本）
        return if (fam is CryptFamily.State.Absent) HtmlAdapter(site) else FamilyRouter(site)
    }
}
