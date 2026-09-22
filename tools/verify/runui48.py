#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ui48 — v1.0.48「方案 A 浮岛分层 + 半透磨砂玻璃」的离线守卫。

这一版几乎全是**材质与尺寸**改动。它最容易出的不是编译错（编译期能挡住的都不算坑），
而是三类**静默失败**——编译绿、自检绿、装到机器上才发现不对：

  1) 亮色模式下的玻璃描边写成白色 ⇒ 浅底上玻璃片没有边界，整块糊成一片；
  2) 深色浮层（播放页面板 / 嗅探浮窗 / 校准引导卡）里用了跟随主题的文字色
     ⇒ 亮色模式下变成"深玻璃 + 深字"；
  3) 浮岛给底栏加了 10dp margin，却没重算 player_panel_clearance
     ⇒ 分集面板被底栏压住一截。

第 3 条做成**算术断言**：底栏高度从 layout 里现算（margin + padding + 三行），
再和 dimens 里的让位高度比 —— 以后谁改了行高却忘了改 dimen，这里会红。
"""
import io, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _cp  # noqa: E402
ROOT = _cp.project_root()
RES = os.path.join(ROOT, "app", "src", "main", "res")

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


def num(text, pattern, default=None):
    m = re.search(pattern, text)
    return float(m.group(1)) if m else default


light = rd("values", "colors.xml")
dark = rd("values-night", "colors.xml")
dims = rd("values", "dimens.xml")
land = rd("values-land", "dimens.xml")
main = rd("layout", "activity_main.xml")
browser = rd("layout", "view_site_browser.xml")
player = rd("layout", "activity_player.xml")
calib = rd("layout", "activity_calibrate.xml")
cand = rd("layout", "item_candidate.xml")
video = rd("layout", "item_video.xml")
ambient = rd("drawable", "bg_ambient.xml")
glass = rd("drawable", "bg_glass.xml")

print("== A. 设计令牌：磨砂玻璃的一套色，亮/暗必须成对定义 ==")
TOKENS = ["glass_fill", "glass_fill_2", "glass_stroke", "glass_glow",
          "glass_pressed", "ambient_1", "ambient_2", "ambient_3", "bg_deep"]
for t in TOKENS:
    ok("亮色定义了 %s" % t, ('name="%s"' % t) in light)
    ok("暗色定义了 %s" % t, ('name="%s"' % t) in dark)

# ★ 最容易犯的一条：亮色下白玻璃 + 白描边 = 没有边界
m = re.search(r'<color name="glass_stroke">#([0-9A-Fa-f]{8})</color>', light)
rgb_light = m.group(1)[2:].upper() if m else ""
ok("★ 亮色 glass_stroke 是深色系（白描边压在浅底上等于没有边界）",
   rgb_light not in ("", "FFFFFF"))
m = re.search(r'<color name="glass_stroke">#([0-9A-Fa-f]{8})</color>', dark)
rgb_dark = m.group(1)[2:].upper() if m else ""
ok("★ 暗色 glass_stroke 是白色系（深底上只有浅描边看得见）",
   rgb_dark == "FFFFFF")

print("== B. 材质层：三层玻璃与环境光底的结构 ==")
ok("bg_glass 是 layer-list（主体 + 高光 + 描边三层叠）", "<layer-list" in glass)
ok("bg_glass 层数 = 3", glass.count("<item>") == 3)
ok("bg_glass 用 @dimen/radius_lg 统一圆角（三层同值，否则描边与填色错位）",
   glass.count("@dimen/radius_lg") == 3)
ok("bg_glass 含顶部高光层（glass_glow）", "@color/glass_glow" in glass)
ok("bg_glass 含 1px 描边（@dimen/glass_stroke_w）", "@dimen/glass_stroke_w" in glass)

ok("bg_ambient 引用颗粒图 glass_noise", "@drawable/glass_noise" in ambient)
ok("★ 颗粒层用 tileMode repeat 平铺（不 repeat 会被拉伸成整屏一块糊）",
   'android:tileModeX="repeat"' in ambient and 'android:tileModeY="repeat"' in ambient)
ok("bg_ambient 有 bg → bg_deep 的竖向渐变", "@color/bg_deep" in ambient)
ok("bg_ambient 至少 2 团环境光斑（只有一团会显得像贴纸）",
   ambient.count("radial") >= 2)
ok("bg_ambient 用负 inset 把光斑圆心推到屏幕外",
   re.search(r'android:top="-\d+dp"', ambient) is not None)

print("== C. 深色浮层材质必须与主题无关（浅色模式下播放页也得能用）==")
for f in ("bg_glass_dark_bar", "bg_glass_dark_panel", "bg_glass_dark_round"):
    src = rd("drawable", f + ".xml")
    ok("%s 写死色值、不引用 @color/glass_*" % f, "@color/glass_" not in src)
    ok("%s 有不透明度 ≥ 60%% 的底（压在视频画面上）" % f,
       re.search(r'#[0-9A-Fa-f]{2}(9[0-9A-Fa-f]|[A-Fa-f][0-9A-Fa-f])', src) is not None)

print("== D. 浮岛化：脱边 + 圆角 + 玻璃 ==")
j = main.find('android:id="@+id/bottomNav"')
seg = main[j:j + 800] if j >= 0 else ""
ok("首页底部导航用玻璃浮岛底（bg_glass_nav）", "@drawable/bg_glass_nav" in seg)
ok("★ 底部导航三边都脱边（只做圆角不脱边 = 仍是贴边的廉价感）",
   all(('android:layout_margin%s="@dimen/island_gap"' % s) in seg
       for s in ("Start", "End", "Bottom")))
ok("activity_main 根背景 = bg_ambient", "@drawable/bg_ambient" in main)

ok("view_site_browser 标题行浮岛化", browser.count("@drawable/bg_glass") >= 2)
ok("标题行仍是 wrap_content + minHeight（applyOverlayInsets 会补 padding）",
   'android:minHeight="@dimen/island_min_h"' in browser)
ok("标题行/搜索行都脱边（@dimen/island_gap）",
   browser.count("@dimen/island_gap") >= 4)

for tag in ("topBar", "bottomBar"):
    j = player.find('android:id="@+id/%s"' % tag)
    seg = player[j:j + 900] if j >= 0 else ""
    ok("播放页 %s 用固定深色玻璃（bg_glass_dark_bar）" % tag,
       "@drawable/bg_glass_dark_bar" in seg)
    ok("★ 播放页 %s 不得用跟随主题的 bg_glass（浅色模式下会白玻璃白字）" % tag,
       '@drawable/bg_glass"' not in seg)
    ok("播放页 %s 已脱边（@dimen/island_gap）" % tag,
       "@dimen/island_gap" in seg)
ok("播放页顶栏仍为 wrap_content + minHeight（写死高度会挤扁内容）",
   'android:minHeight="@dimen/island_min_h"' in player)
ok("底栏离底 margin 走 island_gap", player.count('android:layout_marginBottom="@dimen/island_gap"') >= 1)

print("== E. 让位尺寸：算术断言（改行高忘改 dimen 会在这里红）==")
gap = num(dims, r'<dimen name="island_gap">([\d.]+)dp</dimen>')
clr_p = num(dims, r'<dimen name="player_panel_clearance">([\d.]+)dp</dimen>')
clr_l = num(land, r'<dimen name="player_panel_clearance">([\d.]+)dp</dimen>')


def seg_of(src, start_id, end_id):
    a = src.find('android:id="@+id/%s"' % start_id)
    b = src.find('android:id="@+id/%s"' % end_id)
    return src[a:b] if a >= 0 and b > a else ""


bb = seg_of(player, "bottomBar", "btnRowMain")
pad_t = num(bb, r'android:paddingTop="([\d.]+)dp"')
pad_b = num(bb, r'android:paddingBottom="([\d.]+)dp"')
seek_h = num(player, r'android:id="@\+id/seekBar"[\s\S]{0,500}?android:layout_height="([\d.]+)dp"')
main_h = num(player, r'android:id="@\+id/btnRowMain"[\s\S]{0,400}?android:layout_height="([\d.]+)dp"')
tool_h = num(player, r'android:id="@\+id/btnRowTool"[\s\S]{0,400}?android:layout_height="([\d.]+)dp"')

ok("能从 layout 里解出底栏各行高度（margin/padding/进度行/主行/工具行）",
   None not in (gap, pad_t, pad_b, seek_h, main_h, tool_h))
if None not in (gap, pad_t, pad_b, seek_h, main_h, tool_h):
    h_port = gap + pad_t + pad_b + seek_h + main_h + tool_h
    h_land = gap + pad_t + pad_b + seek_h + main_h
    print("    竖屏底栏算得 %.0fdp（margin %.0f + padding %.0f + %.0f + %.0f + %.0f）"
          % (h_port, gap, pad_t + pad_b, seek_h, main_h, tool_h))
    print("    横屏底栏算得 %.0fdp（工具行并进主行）" % h_land)
    ok("★ 竖屏让位 %.0fdp ≥ 底栏 %.0fdp（否则选集面板被压住）" % (clr_p, h_port),
       clr_p >= h_port)
    ok("★ 横屏让位 %.0fdp ≥ 底栏 %.0fdp（values-land 漏改过一次）" % (clr_l, h_land),
       clr_l >= h_land)
    ok("让位高度留了呼吸（不多不少，超出 24dp 说明 dimen 没跟着收）",
       (clr_p - h_port) <= 24 and (clr_l - h_land) <= 24)

print("== F. 深底上的文字必须写死浅色（防「深玻璃 + 深字」）==")
for vid in ("tvStep", "tvHint", "tvState"):
    i = calib.find('android:id="@+id/%s"' % vid)
    seg = calib[i:i + 400] if i >= 0 else ""
    m = re.search(r'android:textColor="([^"]+)"', seg)
    tc = m.group(1) if m else ""
    ok("校准引导卡 %s 不跟随主题（当前 %s）" % (vid, tc or "无"),
       tc not in ("@color/text_primary", "@color/text_hint", "@color/text_secondary"))
i = cand.find('android:id="@+id/tvName"')
seg = cand[i:i + 400] if i >= 0 else ""
ok("嗅探浮窗候选名不跟随主题（它坐在深色浮窗里）",
   "@color/chip_text" not in seg)

# 反向断言：深色面板里出现「亮片 + 白字」的组合
bad = []
for m in re.finditer(r'@drawable/bg_chip"', player):
    tail = player[m.end():m.end() + 300]
    if 'android:textColor="@color/white"' in tail:
        bad.append(player[max(0, m.start() - 120):m.start()])
ok("★ 播放页面板内没有「bg_chip（跟随主题）+ 白字」的组合",
   len(bad) == 0)
ok("btnDiagSniff / btnDiagRetry 改用 bg_chip_dark",
   player.count("@drawable/bg_chip_dark") >= 2)

print("== G. 回归守卫（不得把 v1.0.44~47 的成果碰掉）==")
ok("卡片材质 bg_surface 本身已玻璃化（一处改，全站卡片跟着变）",
   "@color/glass_fill" in rd("drawable", "bg_surface.xml"))
ok("item_video 用玻璃卡片底", "@drawable/bg_glass_card" in video)
ok("页面基底不再有裸 @color/bg 的根布局",
   all("@drawable/bg_ambient" in rd("layout", f) for f in
       ("activity_main.xml", "activity_site.xml", "activity_detail.xml",
        "activity_history.xml", "activity_fav.xml", "activity_cast.xml",
        "activity_calibrate.xml", "activity_sniff.xml")))
ok("播放页 HUD 仍是深色玻璃（压在画面上）",
   "@color/glass_" not in rd("drawable", "bg_hud.xml"))

print()
print("==== Ui48  pass=%d fail=%d ====" % (ok_n, fail_n))
sys.exit(1 if fail_n else 0)
