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
 * 签名种子配置族（外边套种子路由）→ 加密接口族 → 网页解析**。
 *
 * ## v1.0.53：种子路由在最外层
 *
 * 默认兜底从 [FamilyRouter] 换成了 [SeedRouter]，它是**再包一层**的延迟路由：
 * 先判「签名种子配置族」，不命中就原样交给 [FamilyRouter]（原有链路一字未改）。
 * 放在最外层是因为它的判据与域名无关（解开站点自己的 `{origin}/config.json` 信封），
 * 而这一族恰恰**最会换域名**（配置文件里就带着一串种子地址互相兜底）。
 * 详见 [SeedFamily] / [SeedRouter]。
 *
 * ⚠️ 换默认兜底时**不能顺手丢掉第 ② 步那条规矩**：已判定「不是加密接口族」的域名
 * （`CryptFamily.State.Absent` 落盘）不该再被包一层只为读缓存的外壳 ——
 * 该规矩仍然生效，只是现在由 [SeedRouter] 的 `familyAbsent` 落点体现（Absent ⇒ 直接从 [HtmlAdapter] 起）。
 * v1.0.53 第一次改这一步时把它删掉了，是 `Family.java` 的 D4 源码守卫把它抓回来的。
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

        // ⑤ 未知域名 ⇒ 套一层延迟路由。
        //    顺序是 **种子配置族 → 加密接口族 → 网页解析**：
        //    种子族最先判，因为它的判据是"解开 {origin}/config.json 的信封"，
        //    与域名无关、且命中后能一次解决分类/列表/搜索/详情/播放全部五件事；
        //    没命中就原样交给 [FamilyRouter]（它再判加密族、再退回 HtmlAdapter）。
        //    两层各一次探测，都在 IO 协程里（本函数保持同步，理由见文件头）。
        //
        //    ⚠️ **已判定「不是加密接口族」的域名不再套 [FamilyRouter]** —— 这是 v1.0.35 定下的
        //    「零成本」规矩（缓存里已有否定结论，就不该再包一层只为读缓存的外壳）。
        //    但 `Absent` 只说明它**不是加密族**，种子配置族是**另一个族**，
        //    所以那一层探测仍然要过；只是它跑完之后的落点直接从 [HtmlAdapter] 起。
        return if (fam is CryptFamily.State.Absent) SeedRouter(site, familyAbsent = true)
        else SeedRouter(site)
    }
}
