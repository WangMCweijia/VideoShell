#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ui50 — v1.0.50「边缘折射 + 搜索可退出 + 分类行回归 + 搜索结果二级页」的离线守卫。

这一版四类改动，全都属于**编译绿、自检绿、装上机才发现不对**：

  1) 边缘折射是纯绘制层：画在哪儿、亮在哪条边、四边亮度是否分档，编译器一无所知。
     最容易出的错是「四边一样亮」—— 那看起来是一条白框，不是折射。
  2) 搜索退出：以前唯一的开关是标题栏那枚放大镜（在搜索态下它看起来还是「搜索」），
     所以「退不出去」是**交互语义**问题。必须同时守住 ✕ 与返回键两条路。
  3) 分类行回归的真因是**异步尾巴**（分类重试最坏 4.5s，晚到的那一轮把搜索覆盖掉），
     它只在「用户搜得快」时出现，手工测试极难复现 —— 只能靠代码结构断言。
  4) 二级搜索页：左栏 0 号位必须是「聚合」、列数必须跟着左栏重算、
     旋转不重建就得自己重排列数 —— 三条都是错一次就长期错下去的东西。

断言尽量打在**物理量**（alpha、明度、带宽、列数、seq 检查的先后位置）上，
而不是「某个词在不在」。
"""
import io, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _cp  # noqa: E402
ROOT = _cp.project_root()
RES = os.path.join(ROOT, "app", "src", "main", "res")
SRC = os.path.join(ROOT, "app", "src", "main", "java", "com", "videoshell")
MANIFEST = os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml")

ok_n = fail_n = 0


def ok(name, cond):
    global ok_n, fail_n
    if cond:
        ok_n += 1
        print("  [PASS] " + name)
    else:
        fail_n += 1
        print("  [FAIL] " + name)


def rd(*parts):
    with io.open(os.path.join(RES, *parts), encoding="utf-8") as f:
        return f.read()


def rs(*parts):
    # 主文件 + 同主名的拆分子文件一起拼（见 _cp.kt）：守卫锁**代码文本**，不锁文件布局。
    return _cp.kt(os.path.join(SRC, *parts))


def unqual(text, owner="SiteBrowser"):
    """剥掉拆分带来的 `SiteBrowser.` 限定名再比字符串。

    companion 成员在类外**必须**写 `SiteBrowser.MODE_SEARCH`（语法要求：不能裸写、不能 import），
    所以「把 god file 拆成扩展文件」这种纯搬运会把这些裸名变成带限定名的样子 ——
    那是**位置**变了，不是行为变了。见 docs/PITFALLS.md §4.41。
    """
    return text.replace(owner + ".", "")


def num(text, pattern, default=None):
    m = re.search(pattern, text)
    return float(m.group(1)) if m else default


def argb(text, name):
    """<color name="x">#AARRGGBB</color> → (a, r, g, b)"""
    m = re.search(r'<color name="%s">#([0-9A-Fa-f]{8})</color>' % re.escape(name), text)
    if not m:
        return None
    h = m.group(1)
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), int(h[6:8], 16)


def alpha_of(text, name):
    v = argb(text, name)
    return v[0] / 255.0 if v else None


def luma(text, name):
    """粗略明度 = RGB 最大值。用来判这支配色是暗棱还是亮棱"""
    v = argb(text, name)
    return max(v[1], v[2], v[3]) if v else None


def code(src):
    """剥掉注释行 —— 断言只看代码，注释里提一句历史写法不该把它判红"""
    return "\n".join(
        l for l in src.splitlines()
        if not l.strip().startswith(("//", "*", "/*"))
    )


def body_of(src, signature):
    """取某个函数（或代码块）的函数体文本，按花括号配平。

    比「在整份源码里 grep」精确：本文件好几条断言要的是「这行代码在这个函数里」，
    散在整份文件里搜会把别处的同名调用也算进来。
    """
    i = src.find(signature)
    if i < 0:
        return ""
    j = src.find("{", i)
    if j < 0:
        return ""
    depth = 0
    for k in range(j, len(src)):
        if src[k] == "{":
            depth += 1
        elif src[k] == "}":
            depth -= 1
            if depth == 0:
                return src[j:k + 1]
    return src[j:]


light = rd("values", "colors.xml")
dark = rd("values-night", "colors.xml")
dims = rd("values", "dimens.xml")
strings = rd("values", "strings.xml")
browser_xml = rd("layout", "view_site_browser.xml")
nav_xml = rd("drawable", "bg_glass_nav.xml")
search_xml = rd("layout", "activity_search.xml")
rail_xml = rd("layout", "item_search_rail.xml")
manifest = io.open(MANIFEST, encoding="utf-8").read()

edge_kt = rs("ui", "GlassEdgeDrawable.kt")
main_kt = rs("ui", "MainActivity.kt")
browser_kt = rs("ui", "SiteBrowser.kt")
site_kt = rs("ui", "SiteActivity.kt")
search_kt = rs("ui", "SearchActivity.kt")
rail_kt = rs("ui", "adapter", "SearchRailAdapter.kt")

print("== A. 边缘折射：叠一层，不是换掉原材质 ==")
ok("GlassEdgeDrawable 带两条构造入口 forNav / of",
# ⚠️ 源码判据**不带 `private ` 前缀**（v1.0.54 统一改过）。
#    原来写的是 "private fun xxx("，那是把「可见性修饰符」也钉进了判据 ——
#    而 god file 拆分时被搬到扩展文件里的函数一律变 "internal fun Owner.xxx("
#    （扩展函数访问不了 private），于是"功能一行没改、只是搬了家"也会判红。
#    判据要表达的是「这个签名的声明存在 / 这个函数体在这里」，可见性不是它要说的东西。
#    见 docs/PITFALLS.md §4.24 与 §4.41。
   "fun forNav(" in edge_kt and "fun of(" in edge_kt)
ok("★ 折射是叠在 bg_glass_nav 之上（LayerDrawable 两层，后画的在上）",
   "LayerDrawable(arrayOf(base, GlassEdgeDrawable.forNav(this)))" in main_kt)
ok("底基仍是 bg_glass_nav（填充/砂质/描边四层不能被顶掉）",
   "R.drawable.bg_glass_nav" in main_kt)
ok("bg_glass_nav 不再带砂质层（v1.0.60 实底材质退役颗粒）",
   "@drawable/glass_grain" not in nav_xml)
# v1.0.63：这里原来写的是 `"glass_stroke" in nav_xml` —— 那是**弱断言**：
#   文件里早已改引用 @color/dock_stroke，全靠注释里提了一句 "glass_stroke" 才蒙对。
#   包含 ≠ 等于（§4.54）。现在改成锁「存在 stroke 元素 + 走 Dock 专属 Token」。
ok("bg_glass_nav 仍带描边，且走 dock_stroke（v1.0.63 起描边极性随主题反转）",
   "<stroke" in nav_xml and "@color/dock_stroke" in nav_xml)


def _lum(text, name):
    """相对亮度（WCAG）。argb 拿到的是 ARGB，这里只看 RGB 通道。

    带 alpha 的 Dock 底（暗色 #F212161D）按 95% 压在画布上估算时差异 < 1%，
    直接当不透明处理不会影响判据，故不额外做合成。
    """
    v = argb(text, name)
    if not v:
        return None

    def ch(c):
        c = c / 255.0
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4

    return 0.2126 * ch(v[1]) + 0.7152 * ch(v[2]) + 0.0722 * ch(v[3])


def _ratio(text, fg, bg):
    a, b = _lum(text, fg), _lum(text, bg)
    if a is None or b is None:
        return None
    hi, lo = max(a, b), min(a, b)
    return (hi + 0.05) / (lo + 0.05)


print("== A2. Dock 材质必须与主题同向（锁机制，不锁色值 · v1.0.63 教训）==")
# v1.0.62 把亮色 Dock 做成深墨 #1B2028，这是**主题反转** —— 用户一眼否掉。
# 判据表达的本质是「不管哪个主题，Dock 都得从画布里浮起来」：
#   亮色靠抬亮一档（白面板压在暖纸上）、暗色靠提亮一档（黑场上再压暗就没了）。
# 所以同一条断言对两个主题都成立 —— 而 v1.0.62 的墨岛会被它当场抓住。
for _t, _tag in ((light, "亮色"), (dark, "暗色")):
    _df, _bg = _lum(_t, "dock_fill"), _lum(_t, "bg")
    ok("★ %s Dock 底比自家画布亮（浮起来；反过来就是主题反转）" % _tag,
       _df is not None and _bg is not None and _df > _bg)

# 描边极性随主题反转：材质换了必须同步换描边色，否则要么看不见、要么一道突兀亮边。
ok("亮色 Dock 描边比 Dock 底暗（浅墨发丝线画在白面板上）",
   _ratio(light, "dock_stroke", "dock_fill") is not None
   and _lum(light, "dock_stroke") < _lum(light, "dock_fill"))
ok("暗色 Dock 描边比 Dock 底亮（黑场上只有白发丝线勾得出上棱）",
   _lum(dark, "dock_stroke") > _lum(dark, "dock_fill"))

# 选中/未选中色必须按**自家 Dock 底色**取：11sp 文字 + 22dp 图标都不算大字，
# 一律按 4.5:1（AA）要求，不拿"图形门槛 3:1"凑。
for _t, _tag in ((light, "亮色"), (dark, "暗色")):
    for _name, _what in (("dock_active", "选中"), ("dock_inactive", "未选中")):
        _r = _ratio(_t, _name, "dock_fill")
        ok("★ %s Dock %s色在自家底上过 AA 4.5:1（实测 %.2f:1）" % (_tag, _what, _r or 0),
           _r is not None and _r >= 4.5)

print("== B. 折射配色（UI 2.0「暗场 Spotlight」起退役）：三支棱色双主题全透明 ==")
la, lb, lc = alpha_of(light, "glass_edge_a"), alpha_of(light, "glass_edge_b"), alpha_of(light, "glass_edge_c")
da, db, dc = alpha_of(dark, "glass_edge_a"), alpha_of(dark, "glass_edge_b"), alpha_of(dark, "glass_edge_c")
ok("★ 三支棱色双主题全透明（实底材质里边缘折射退役；GlassEdgeDrawable 画出空操作，代码保留）",
   all(x == 0.0 for x in (la, lb, lc, da, db, dc)))
ok("三支颜色两套主题都**还有定义**（退役 = 置透明，删名会让引用它的 drawable 全线编译炸）",
   all(x is not None for x in (la, lb, lc, da, db, dc)))

print("== C. 折射的画法：逐边衰减带 + 内外双棱 ==")
top_h = num(edge_kt, r"topH = ([\d.]+)f \* density")
bot_h = num(edge_kt, r"botH = ([\d.]+)f \* density")
side_h = num(edge_kt, r"sideW = ([\d.]+)f \* density")
ok("★ 顶缘带最厚、底缘次之、侧棱最薄（带宽就是亮度分档的载体）",
   bool(top_h and bot_h and side_h) and top_h > bot_h > side_h)
ok("★ 边缘带必须裁到圆角矩形内（不裁会从圆角外侧漏出去）",
   "canvas.clipPath(" in edge_kt)
ok("边缘带是向内衰减到透明的渐变（硬边 = 描边，不是折射）",
   edge_kt.count("LinearGradient(") >= 3 and "transparent(color)" in edge_kt)
ok("有外侧主棱（沿周长的渐变：顶左最亮 → 底右次亮）",
   "intArrayOf(edgeA, edgeB, edgeA)" in edge_kt)
ok("有内侧那一道（玻璃厚度）", "canvas.drawPath(inner, p)" in edge_kt)
inset = num(edge_kt, r"INNER_INSET_DP = ([\d.]+)f")
ok("内侧那一道落在顶缘带内部（厚度 < 顶缘带宽）",
   bool(inset and top_h) and inset < top_h)
gain = num(edge_kt, r"SIDE_GAIN = ([\d.]+)f")
ok("侧棱强度 < 1（否则四边同亮）", gain is not None and gain < 1.0)
ok("★ setColor 会覆盖 alpha —— 内线必须放在 setColor 之后",
   re.search(r"p\.color = innerLine\s*\n\s*(//[^\n]*\n\s*)?p\.alpha = drawAlpha", edge_kt) is not None)

print("== D. 折射半径必须与导航栏自身的圆角同源 ==")
ok("bg_glass_nav 的圆角是 radius_xl", "@dimen/radius_xl" in nav_xml)
ok("★ forNav 用同一个 radius_xl（不同源 ⇒ 棱画在另一条曲线上，肉眼可见错位）",
   "of(ctx, R.dimen.radius_xl)" in edge_kt)

print("== E. 搜索可退出：✕ 与返回键共用同一个入口 ==")
ok("搜索行里有退出按钮", "@+id/btnExitSearch" in browser_xml)
ok("退出按钮用 ic_close（不让人去猜放大镜的第二种含义）",
   'android:src="@drawable/ic_close"' in browser_xml)
ok("退出按钮有 contentDescription", 'android:contentDescription="@string/search_exit"' in browser_xml)
ok("SiteBrowser 提供 exitSearch()", "fun exitSearch(): Boolean" in browser_kt)
ok("btnExitSearch 的点击进 exitSearch",
   "b.btnExitSearch.setOnClickListener { exitSearch() }" in browser_kt)
exit_body = body_of(code(browser_kt), "fun exitSearch(): Boolean")
ok("★ 退出判据是「搜索区开着」，不是「网格里搜过」（结果已在二级页）",
   "b.searchRow.visibility != View.VISIBLE" in exit_body)
ok("退出时清掉关键词", 'keyword = ""' in exit_body)
ok("退出时收起搜索行 / 范围行 / 引擎行（三行同生共死）",
   exit_body.count("visibility = View.GONE") >= 3)
ok("★ 网格没内容时退出会重拉一次（首屏分类被搜索打断的那条路径）",
   "videoAdapter.itemCount == 0" in exit_body)

ok("★ MainActivity 用 OnBackPressedCallback（覆写 onBackPressed 做不到先摘掉自己再转交）",
   "onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true)" in main_kt)
ok("★ MainActivity 返回键第一优先是退出搜索",
   "if (currentPage == PAGE_HOME && browserHome.exitSearch()) return" in main_kt)
ok("MainActivity 非首页时返回键回首页", "currentPage != PAGE_HOME" in main_kt)
# v1.0.62：底栏从 BottomNavigationView 换成手写 Dock，menu 的 checked 状态机随之消失，
# 选中态的唯一定义处变成 selectTab。判据跟着改锁**机制**（一个入口管两件事），
# 而不是原来那行 setter —— 锁 setter 等于锁实现，机制一换就红（见 PITFALLS §4.41）。
ok("★ 回首页经 selectTab 同步底栏选中态（避免内容回首页、底栏还亮着站源）",
   "selectTab(PAGE_HOME)" in main_kt)
ok("★ selectTab 同时刷三格选中态并切页（手写 Dock 后没有 menu 状态机代管）",
   "tab.isSelected = p == page" in main_kt and "showPage(page)" in main_kt)
ok("★ SiteActivity 返回键也先退搜索", "if (browser.exitSearch()) return" in site_kt)
ok("★ 二级站源页标题栏的返回也先退搜索（界面上有返回、按了却整页退掉最迷惑）",
   "if (!browser.exitSearch()) finish()" in site_kt)

print("== F. 分类行回归：异步尾巴不许覆盖搜索态 ==")
ok("★ 有代次守卫 catSeq", "var catSeq = 0" in browser_kt)
load_body = body_of(code(browser_kt), "fun loadCategories()")
ok("★ 重试每一轮开始前都查代次", load_body.count("if (seq != catSeq) return@launch") >= 3)
# 位置判据（与上面那条**不是**同一件事）：光有"三个检查点"不够，
# 关键是其中至少有一个落在 delay **之后** —— 退避 1.5s / 3s 正是用户切站或去搜索的窗口，
# 睡醒才检查等于把窗口白留。
_di = load_body.find("delay(")
ok("★ 至少一次代次检查落在 delay 之后（退避睡醒必须再查）",
   _di >= 0 and "if (seq != catSeq) return@launch" in load_body[_di:])
ok("★ 收尾时按「搜索发起过」决定要不要 onCategory(0)",
   "if (searchingNow()) return@launch" in load_body)
ok("★ 判据是 mode==SEARCH 或关键词非空，不是只看搜索区可见",
   "mode == MODE_SEARCH || keyword.isNotBlank()" in browser_kt)
ok("★ 分类条可见性只有一份出口 applyCatVisibility",
   "fun applyCatVisibility()" in browser_kt)
rev = body_of(code(browser_kt), "fun reload()")
ok("reload() 调 applyCatVisibility（不再自带第二份判据）",
   "applyCatVisibility()" in rev and "b.rvCats.visibility" not in rev)
ok("★ loadCategories 里不再出现旧的无条件拉回 VISIBLE 写法",
   "b.catHintRow.visibility = View.VISIBLE" not in load_body)
ok("★ rvCats 可见性赋值只在 applyCatVisibility 里出现",
   code(browser_kt).count("b.rvCats.visibility =") == 1)
ok("退出搜索后可见性会被拨回来", "applyCatVisibility()" in exit_body)

print("== G. 搜索结果二级页：左栏 0 号位必须是「聚合」 ==")
ok("SearchActivity 存在", "class SearchActivity : AppCompatActivity()" in search_kt)
ok("★ 已在 manifest 注册", 'android:name=".ui.SearchActivity"' in manifest)
seg = manifest[manifest.index('android:name=".ui.SearchActivity"'):][:400]
ok("★ 旋转不重建（声明 configChanges）", "configChanges" in seg)
ok("★ 因此必须在 onConfigurationChanged 里重排列数",
   "override fun onConfigurationChanged(newConfig: Configuration)" in search_kt
   and "setupGrid()" in body_of(code(search_kt), "override fun onConfigurationChanged"))
ok("★ railSites[0] == null 就是聚合（约定只在这一处建立）",
   "railSites.add(null)" in search_kt)
ok("左栏第一项文案是聚合", "railNames.add(getString(R.string.search_rail_agg))" in search_kt)
ok("起始选中：全站范围进来则取 0 号（聚合）", "wantAgg -> 0" in search_kt)
ok("起始选中：否则定位到来时的那个站", "found > 0 -> found" in search_kt)

print("== H. 二级页列数（左栏吃掉宽度，不能沿用首页的 3 列） ==")
cols = re.search(r"val cols = if \(land\) (\d+) else (\d+)", search_kt)
ok("★ 列数写明是 4（横）/ 2（竖）",
   cols is not None and cols.group(1) == "4" and cols.group(2) == "2")
ok("★ 结果区列数 < 首页的 3 列（否则每张卡片只剩 ~70dp，封面明显变小）",
   cols is not None and int(cols.group(2)) < 3)
ok("网格有 spanSizeLookup（聚合的分组标题要独占整行）",
   "spanSizeLookup" in search_kt and "videoAdapter.isHeader(position)" in search_kt)
rail_w = num(dims, r'<dimen name="search_rail_w">([\d.]+)dp</dimen>')
ok("★ 左栏宽度在 60~88dp 之间（更窄站名读不全，更宽把卡片压小）",
   rail_w is not None and 60 <= rail_w <= 88)
ok("左栏宽度从 dimens 取（不写死在 layout 里）",
   'android:layout_width="@dimen/search_rail_w"' in search_xml)

print("== I. 左栏格子：选中态必须真的看得见 ==")
ok("★ 两个 TextView 都 duplicateParentState（chip_text 认的是控件自己的 selected）",
   rail_xml.count('android:duplicateParentState="true"') == 2)
ok("选中态复用 bg_chip（它已有 state_selected = 品牌实心那一支）",
   'android:background="@drawable/bg_chip"' in rail_xml)
ok("selected 设在根布局上", "b.root.isSelected = index == selected" in rail_kt)
ok("★ 计数用 notifyItemChanged（聚合逐站到达，整表刷新会闪）",
   "notifyItemChanged(index)" in body_of(code(rail_kt), "fun setCount("))
ok("点击用 bindingAdapterPosition 并挡 NO_POSITION",
   "bindingAdapterPosition" in rail_kt and "RecyclerView.NO_POSITION" in rail_kt)

print("== J. 接线：首页与二级站源页都把搜索交给二级页 ==")
ok("★ SiteBrowser 有 openSearch 回调",
   "val openSearch: ((keyword: String, aggregate: Boolean) -> Unit)? = null" in browser_kt)
bc = code(browser_kt)
ds = body_of(bc, "fun doSearch()")
# ⚠️ 判据必须落在 doSearch **内部**：`val page = openSearch` 在 setScope 里也有一份，
# 在整份源码里比下标会把 setScope 的位置当成 doSearch 的，断言就永远绿（vacuous）。
ok("★ WEB 范围不被 openSearch 截走（全网仍要开网页）",
   "openWebSearch(kw)" in ds and "val page = openSearch" in ds
   and ds.index("openWebSearch(kw)") < ds.index("val page = openSearch"))
ok("doSearch 里确实用 openSearch 分流", "page(kw, scope == SearchScope.ALL)" in browser_kt)
ok("★ openSearch 为 null 时保留旧的「本页搜索」兜底（别的宿主不被打断）",
   "mode = MODE_SEARCH" in unqual(ds))
ok("MainActivity 传了 openSearch", "openSearch = { kw, agg ->" in main_kt)
ok("SiteActivity 传了 openSearch", "openSearch = { kw, agg ->" in site_kt)
ok("换搜索范围会重开那一页（否则改成全站了没反应）",
   "page(keyword, s == SearchScope.ALL)" in browser_kt)
ok("★「已经在搜」的判据跟着改成搜索区可见 + 关键词非空（mode 恒为 CATEGORY 了）",
   "b.searchRow.visibility != View.VISIBLE || keyword.isBlank()" in browser_kt)

print("== K. 顺带修掉：搜索历史以前从来没被写盘 ==")
ok("★ doSearch 里记一笔搜索历史（Store.addSearchHistory 以前全工程零调用）",
   "Store.addSearchHistory(act, kw)" in browser_kt)

print("== L. 状态位复用必须摘监听（看不见但能点是最忌讳的 bug） ==")
st = body_of(code(search_kt), "fun showState(")
ok("★ showState 先无条件摘掉监听", "binding.tvState.setOnClickListener(null)" in st)
ok("★ 只有存在失败清单时才重新挂上", "failures.isNotEmpty()" in st)
ok("★ 换站不再误报别的站的错（失败清单跟着栏走，不由 selectRail 兜底清空）",
   # ⚠️ v1.0.51 改了口径：不再"换站时清空"，改成**失败清单跟着栏走**（每栏一份 RailState
   # 自带 failures）。意图没变（点开状态行不许弹出别的站报的错），但判据从"清空"升级成
   # "还原自己那一份" —— 更强：切回聚合还能看到聚合那一次的失败原因。
   # v1.0.52 又把这段挪进了 commitChrome（流式那条路的收尾不能整表重铺，只能走它）。
   # 所以断言跟着机制走，而不是把代码改回去（同 PITFALLS 4.24 那条教训）——
   # 顺手加上"只此一处"，比原来那条位置判据更强。
   "failures = emptyList()" not in body_of(code(search_kt), "fun selectRail(") and
   "failures = st.failures" in body_of(code(search_kt), "fun commitChrome(") and
   search_kt.count("failures = st.failures") == 1)
ok("结果页支持单站翻页", "load(page + 1, true)" in search_kt)
ok("★ 聚合不翻页（每站已限 24 条，重复铺分组标题反而更难读）",
   "if (selected == 0) return" in search_kt)

print("== M. 资源齐备 ==")
for s in ("search_exit", "search_result_title", "search_rail_agg", "search_count",
          "search_rail_tip", "search_no_site", "search_site_empty", "search_site_failed",
          "search_agg_failed", "search_fail_title", "search_rail_sites"):
    ok("strings 有 %s" % s, ('name="%s"' % s) in strings)

print("== N. 源码卫生：Kotlin 里不能出现 XML 风格的资源引用 ==")
bad = []
for _root, _dirs, _files in os.walk(SRC):
    for _fn in _files:
        if not _fn.endswith(".kt"):
            continue
        with io.open(os.path.join(_root, _fn), encoding="utf-8") as f:
            _src = f.read()
        for _m in re.finditer(r"R\.(dimen|color|drawable|layout|string|id|style|menu)/", _src):
            bad.append("%s → %s" % (_fn, _m.group(0)))
ok("★ Kotlin 里没有 R.dimen/xxx 这类 XML 写法（应为 R.dimen.xxx）", not bad)
for b in bad[:5]:
    print("     " + b)

print("\n-- runui50: PASS=%d FAIL=%d" % (ok_n, fail_n))
sys.exit(1 if fail_n else 0)
