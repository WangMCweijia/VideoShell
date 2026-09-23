#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ui49 — v1.0.49「分集长名方案 3 + 底部导航真正浮起 + 磨砂通透化」的离线守卫。

这一版有三类改动，恰好对应三类**编译绿、装上机才发现不对**的问题：

  1) 分集长名：拆名与降列都作用在**显示**上，一旦有人在 bind 里顺手改了
     `Episode.name`，进度身份（站点|剧名|集名#组内序号）就整体错位 ——
     丢进度不会有任何报错，只会"看着看着回到第一集"。
  2) 底部导航：只要 bottomNav 还在外层 LinearLayout 里（流式占位），
     内容就永远延伸不到它底下，玻璃再透也无物可透。这个错误**肉眼只能靠
     "药丸旁边还有一层遮罩"来察觉**，所以必须由断言守住位置。
  3) 磨砂：玻璃透明度一旦被"手滑调回去"（0.85 那种几乎不透明的值），
     磨砂就退化成一块灰板，但编译、自检、截图都不会报任何东西。

每条断言都尽量对**物理量**（alpha 数值、列数、sp 差、元素在 XML 里的先后位置）
而不是对"某个字符串在不在"，这样它才挡得住真正的回归。
"""
import io, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _cp  # noqa: E402
ROOT = _cp.project_root()
RES = os.path.join(ROOT, "app", "src", "main", "res")
SRC = os.path.join(ROOT, "app", "src", "main", "java", "com", "videoshell")

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


def num(text, pattern, default=None):
    m = re.search(pattern, text)
    return float(m.group(1)) if m else default


def alpha(text, name):
    """#AARRGGBB 的前两位 → 0.0~1.0；取不到返回 None"""
    m = re.search(r'<color name="%s">#([0-9A-Fa-f]{8})</color>' % name, text)
    return int(m.group(1)[:2], 16) / 255.0 if m else None


light = rd("values", "colors.xml")
dark = rd("values-night", "colors.xml")
dims = rd("values", "dimens.xml")
main = rd("layout", "activity_main.xml")
episode = rd("layout", "item_episode.xml")
video = rd("layout", "item_video.xml")
nav = rd("drawable", "bg_glass_nav.xml")

cell = rs("util", "EpisodeCell.kt")
order = rs("util", "EpisodeOrder.kt")
adapter = rs("ui", "adapter", "EpisodeAdapter.kt")
detail = rs("ui", "DetailActivity.kt")
player = rs("player", "PlayerActivity.kt")
main_kt = rs("ui", "MainActivity.kt")
browser_kt = rs("ui", "SiteBrowser.kt")
models = rs("data", "model", "Models.kt")

print("== A. 拆名只发生在渲染期：数据一个字都不能改 ==")
# 这是本版最贵的坑：改了 name，进度、上一集/下一集、自动连播全部错位，且不报错。
ok("Episode.name 是 val（改名字从类型上就不可能）",
   re.search(r"data class Episode\(\s*\n\s*val name: String", models) is not None)
ok("★ EpisodeAdapter 里没有任何对 .name 的赋值",
   re.search(r"\.name\s*=[^=]", adapter) is None)
ok("拆名只出现在适配器里（EpisodeCell.of 的调用点）",
   adapter.count("EpisodeCell.of(") == 1)
ok("EpisodeCell 有 of(name) 与 plan(list, landscape)",
   "fun of(" in cell and "fun plan(" in cell)
ok("拆名判据复用 EpisodeOrder（不新写第二份正则）",
   "EpisodeOrder.markerInTitle(" in cell)

print("== B. 集号判据仍然只有一份 ==")
ok("EP_MARKER 增加了单位字捕获组",
   "([集话期回部])" in order)
ok("noInTitle 委托 markerInTitle（判据唯一）",
   "fun noInTitle(title: String): Int? = markerInTitle(title)?.no" in order)

def code(src):
    """剥掉注释行。

    「不再写死列数」这类断言必须只看代码 —— 否则注释里提一句"以前是写死的
    `if (isLandscape()) 8 else 5`"就会把断言判红，而它其实是对的。
    反过来，如果为了迁就断言而不敢在注释里写历史写法，那注释就残废了。
    """
    return "\n".join(
        l for l in src.splitlines()
        if not l.strip().startswith(("//", "*", "/*"))
    )


print("== C. 降列：整列决策、两页共用 ==")
base_port = num(cell, r"BASE_PORT = (\d+)")
base_land = num(cell, r"BASE_LAND = (\d+)")
red_port = num(cell, r"REDUCED_PORT = (\d+)")
red_land = num(cell, r"REDUCED_LAND = (\d+)")
ok("竖屏降列后列数更少", red_port is not None and base_port is not None and red_port < base_port)
ok("横屏降列后列数更少", red_land is not None and base_land is not None and red_land < base_land)
ok("★ 用中位数判长名（个别超长不该拖累整列）",
   "sorted()" in cell and "size / 2" in cell)
ok("★ 详情页用 EpisodeCell.plan", "EpisodeCell.plan(" in detail)
ok("★ 播放页用 EpisodeCell.plan（同一个剧两页必须长得一样）",
   "EpisodeCell.plan(" in player)
ok("★ 详情页不在代码里写死列数", ") 8 else 5" not in code(detail))
ok("★ 播放页不在代码里写死列数（同一个剧两页必须长得一样）",
   "isLandscape()) 8 else 5" not in code(player))

print("== D. 分集格子：两行布局 + 整列等高 ==")
ok("格子有副行 tvSub", '@+id/tvSub' in episode)
ok("副行有 minHeight（拆不出内容时也占住高度）",
   'android:minHeight="15dp"' in episode)
ok("★ 两行模式下副行始终 VISIBLE（靠它有/无控制会参差不齐）",
   "b.tvSub.visibility = View.VISIBLE" in adapter)
ok("submit 接受 twoLine（整列统一切换）",
   "twoLine: Boolean = false" in adapter)
ok("分集缝用 episode_gap 而不是 grid_gap", "@dimen/episode_gap" in episode)
gap_ep = num(dims, r'name="episode_gap">([\d.]+)dp')
gap_card = num(dims, r'name="grid_gap">([\d.]+)dp')
ok("★ 分集缝比卡片缝小（分集是 5 列密排，缝每多 1dp 吃掉 2dp 文字区）",
   gap_ep is not None and gap_card is not None and gap_ep < gap_card)

print("== E. 底部导航：必须是内容之上的浮层 ==")
# 判据用**位置**：bottomNav 要在最后一个 </FrameLayout> 之前，才说明它进了内容层。
ok("★ bottomNav 在内容 FrameLayout 内部（流式占位 = 玻璃无物可透）",
   main.index("@+id/bottomNav") < main.rindex("</FrameLayout>"))
seg = main[main.index("@+id/bottomNav"):main.index("@+id/bottomNav") + 800]
ok("bottomNav 用 layout_gravity=bottom 浮起",
   'android:layout_gravity="bottom"' in seg)
ok("bottomNav 三边 margin 齐全（脱边浮岛）",
   seg.count("android:layout_margin") >= 3)
ok("MainActivity 有导航让位逻辑", "applyNavClearance" in main_kt)
ok("★ 让位高度是**实测**的（M2 56dp / M3 80dp，写死哪个都会算错）",
   "doOnLayout" in main_kt and "nav.height" in main_kt)
ok("让位同时发给 rvVideos / rvSites / mineContent",
   "browserHome.setBottomInset" in main_kt
   and "rvSites.setPadding" in main_kt
   and "mineContent.setPadding" in main_kt)
ok("SiteBrowser 提供 setBottomInset", "fun setBottomInset" in browser_kt)
ok("★ 让位加在 rvVideos 自己的 paddingBottom 上（加在容器上内容不会从玻璃下穿过）",
   "b.rvVideos.setPadding" in browser_kt)

print("== F. 材质（UI 2.0「暗场 Spotlight」）：实底面板 + 玻璃层退役 ==")
lf, lf2 = alpha(light, "glass_fill"), alpha(light, "glass_fill_2")
df, df2 = alpha(dark, "glass_fill"), alpha(dark, "glass_fill_2")
ok("★ 亮色 glass_fill 是实底面板（≥0.95；磨砂已退役，不许手滑调回半透明）",
   lf is not None and lf >= 0.95)
ok("★ 暗色 glass_fill 是实底面板（e1 档 0.85~0.92，Dock 压滚动内容仍留一线暗影）",
   df is not None and 0.85 <= df <= 0.92)
ok("fill_2 同为实底（双主题 ≥0.85）",
   lf2 is not None and df2 is not None and lf2 >= 0.85 and df2 >= 0.85)
ls_, ds_ = alpha(light, "glass_stroke"), alpha(dark, "glass_stroke")
ok("★ 发丝线极性：亮色实描边（≥0.9）/ 暗色弱白（0<alpha≤0.2）",
   ls_ is not None and ds_ is not None and ls_ >= 0.9 and 0.0 < ds_ <= 0.2)
for name in ("glass_glow", "glass_edge_a", "glass_edge_b", "glass_edge_c",
             "ambient_1", "ambient_2", "ambient_3"):
    la, da = alpha(light, name), alpha(dark, name)
    ok("%s 已退役（双主题全透明）" % name, la == 0.0 and da == 0.0)


def val(text, name):
    m = re.search(r'<color name="%s">#([0-9A-Fa-f]{8})</color>' % name, text)
    return m.group(1).upper() if m else None


for name in ("amber", "amber_hi", "amber_ink"):
    ok("★ 琥珀 %s 双主题同值（唯一同值 Token：白天黑夜都是那束灯）" % name,
       val(light, name) is not None and val(light, name) == val(dark, name))
ok("★ 亮色 text_hint 达 AA（#6E7888 档，不许回退 #7C8797 的 3.6:1）",
   val(light, "text_hint") == "FF6E7888")
ok("★ 暗色 text_hint 达 AA（#7E8794 档）", val(dark, "text_hint") == "FF7E8794")
ok("主题主强调指向琥珀（colorPrimary=@color/amber）",
   'colorPrimary">@color/amber' in rd("values", "themes.xml"))

print("== G. 砂质层：UI 2.0 实底材质下退役（v1.0.60） ==")
# 玻璃时代的"颗粒=材质"判据已随材质更换作废：实底下颗粒只会把面板糊脏（亮色尤甚）。
# 守卫改为锁"退役"不变量：六块玻璃片一律不得再引用 grain。
for f in ("bg_glass.xml", "bg_glass_card.xml", "bg_glass_nav.xml", "bg_surface.xml",
          "bg_glass_dark_bar.xml", "bg_glass_dark_panel.xml"):
    ok("%s 不再带砂质层（实底下颗粒只会糊脏面板，亮色尤甚）" % f,
       "@drawable/glass_grain" not in rd("drawable", f))

print("== H. tab 切换动效 ==")
ok("showPage 里有属性动画", ".animate()" in main_kt and "translationY" in main_kt)
ok("动效 220ms + 减速插值", "setDuration(220L)" in main_kt and "DecelerateInterpolator" in main_kt)
ok("★ 起动画前先 cancel（连点 tab 否则两段叠加，会卡在半透明）",
   ".animate().cancel()" in main_kt)
ok("首次进入不播动画（否则等于开屏抖一下）", "currentPage == -1" in main_kt)
ok("同一个 tab 重复点不重播", "if (!first && page == currentPage) return" in main_kt)

print("== I. 观感：字号层级 / 海报圆角 / 间距 ==")
body = num(dims, r'<dimen name="text_body">([\d.]+)sp</dimen>')
badge = num(dims, r'<dimen name="text_badge">([\d.]+)sp</dimen>')
ok("★ 卡片标题与副标层级 ≥ 3sp（原来 13/12 只差 1sp = 读不出主次）",
   body is not None and badge is not None and body - badge >= 3)
ok("卡片标题用 text_body", 'textSize="@dimen/text_body"' in video)
ok("★ 海报帧裁圆角（否则封面方角溢出 16dp 卡片圆角）",
   'android:clipToOutline="true"' in video)
ok("bg_glass_nav 不再引用不存在的 radius_hero", "@dimen/radius_hero" not in nav)

print("== J. 源码卫生：Kotlin 里不能出现 XML 风格的资源引用 ==")
# 这一条是给"自己刚犯过的错"立的桩：写 dimen 时顺手把 XML 的 @dimen/island_gap
# 搬到 Kotlin 里（应为 R.dimen.island_gap），编译期只报
# "Classifier 'dimen' does not have a companion object"，一眼看不出是斜杠问题。
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

print("\n-- runui49: PASS=%d FAIL=%d" % (ok_n, fail_n))
sys.exit(1 if fail_n else 0)
