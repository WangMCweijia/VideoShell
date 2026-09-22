#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ui52 — v1.0.52 两条需求的离线守卫。

需求原话：
  1. 「搜索结果可以将已经有结果的站源的结果先展示，而不是等所有站源全部搜索结束再统一展示」
  2. 「网页嗅探模式需内置去广告，否则校准时容易被广告干扰」

两条都属于**"结果看不出来"**的改动：第一条只改"什么时候出现"，第二条只改
"什么东西不出现"。所以断言要打在**结构**上：

  ① 聚合第 1 页必须走流式，而且**不许整表重铺**（重铺会让封面集体闪一下）；
     收尾必须走 commitChrome 而不是 render。
  ② 流式的多次到达属于**同一轮**（paintSeq 认领），中途切走再回来不许重发请求
     —— 这一条是 v1.0.51「每点一个站源都要重新搜」的直接延续，不能因为改成流式而回退。
  ③ 去广告：拦下的资源**不许再进候选清单**（两条路都要挡：shouldInterceptRequest
     与 onLoadResource）；校准页的跳转守卫必须排在 `pageUrl = 写` **之前**
     （否则学到的结果页地址是广告页的）；开关默认开且**关得掉**。

⚠️ 判据摆放纪律（v1.0.50/1.0.51 两次教训）：能钉"行为 + 唯一性"就别钉"某个函数里
恰好长什么样"。下面少数几条位置判据，都是因为**先后顺序本身就是那个行为**。
"""
import io, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _cp  # noqa: E402
ROOT = _cp.project_root()
GRADLE = os.path.join(ROOT, "app", "build.gradle")
VERIFY = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "com", "videoshell")
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


def rd(p):
    with io.open(p, encoding="utf-8") as f:
        return f.read()


def rs(*parts):
    return rd(os.path.join(SRC, *parts))


def body_of(src, signature):
    """按花括号配平取**有函数体**的函数的正文（同 runui51 的实现）。

    ⚠️ 坑（v1.0.52 亲历）：遇到 Kotlin **表达式体**（`fun f() = if (...) ... else null`）
    时没有花括号，本函数会一路滑到**下一个**带花括号的函数上去，于是断言拿到的是别人
    的正文 —— 断言会红，但红的原因是"取错了对象"，不是代码有问题。
    对表达式体的函数：要么直接钉那一行（见下面校准页的写法），要么先确认 src 里
    该签名的正文确实带 `{`。
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


SA = rs("ui", "SearchActivity.kt")
SN = rs("player", "SniffActivity.kt")
CA = rs("ui", "CalibrateActivity.kt")
AB = rs("data", "net", "AdBlock.kt")
WB = rs("data", "net", "WebAdBlock.kt")
ST = rs("data", "Store.kt")
WR = rs("ui", "WebRender.kt")
AGG = rs("data", "site", "AggSearch.kt")

B_STREAM = body_of(SA, "private suspend fun streamAggregate(")
B_ARRIVED = body_of(SA, "private fun commitArrived(")
B_PARTIAL = body_of(SA, "private fun partialRail(")
B_FILL = body_of(SA, "private fun fillRailCount(")
B_LOAD = body_of(SA, "private fun load(")
B_SELECT = body_of(SA, "private fun selectRail(")

print("== A. 需求1：聚合第 1 页走流式（先出结果的先显示）==")
ok("streamAggregate() 取到了函数体", len(B_STREAM) > 200)
ok("★ 用了 runStreaming（不是等齐的 run）", "AggSearch.runStreaming(sites, q, 1)" in B_STREAM)
ok("★ 第 1 页才流式（p == 1 分支），第 2 页起仍是等齐的 run",
   "else if (p == 1)" in B_LOAD and "AggSearch.run(sites, q, p)" in B_LOAD)
ok("★ 逐块插进网格（insertBlock + 按行的 insertAt），不整表重铺",
   "videoAdapter.insertBlock(" in B_ARRIVED and "AggSearch.insertAt(slots.toList(), i)" in B_ARRIVED)
ok("★ 插入位置与最终结果同源（用的是 AggSearch 的纯函数，不在界面里自己算）",
   "AggSearch.rows(h)" in B_ARRIVED)
ok("★ 收尾**不整表重铺**：streamAggregate 里不许出现 render(index,",
   "render(index," not in B_STREAM)
ok("★ 收尾走 commitChrome（只补字）", "commitChrome(index, st)" in B_STREAM)
ok("完成时收掉转圈（finishLoad）", "finishLoad()" in B_STREAM)
ok("中途那一份用 mergeRowsArrived（与首页流式同一判据）",
   "AggSearch.mergeRowsArrived(slots.toList())" in B_PARTIAL)
ok("★ 进度句带分母，且与首页共用同一个字符串（同一句话只有一份）",
   "R.string.scope_agg_streaming, arrived, total, n" in B_PARTIAL)
ok("中途不报错误状态（还没结束，说'失败'是撒谎）", "state = null" in B_PARTIAL)

print()
print("== B. 一个站到达时的三件事：① 回填 ② 预填 ③ 插块 ==")
i_fill = B_STREAM.find("fillRailCount(h)")
i_pre = B_STREAM.find("prefill(h, q)")
i_arr = B_STREAM.find("commitArrived(")
ok("★ 回填排在预填之前（反了的话预填过的站没有徽标 —— v1.0.51 的等待又回来了）",
   0 <= i_fill < i_pre)
ok("★ 插块排在最后（前两件是数据，最后一件才是界面）", 0 <= i_pre < i_arr)
ok("★ 回填跳过已有自己结果的站（聚合那份可能被截到 24 条，真搜的是整页）",
   "!cache.containsKey(h.key)" in B_FILL)
ok("聚合自己那一栏的徽标不动（它写的是「共 N 个站」）", "if (i > 0 &&" in B_FILL)
ok("★ commitArrived 里：先落缓存、再判归属（切走了也不白跑）",
   0 <= B_ARRIVED.find("remember(key, st)") < B_ARRIVED.find("if (seq != paintSeq) return"))
ok("★ 归属不是这一轮时**一个字都不改界面**（早退在插块之前）",
   B_ARRIVED.find("if (seq != paintSeq) return") < B_ARRIVED.find("videoAdapter.insertBlock("))
# ★ v1.0.52 自证缺陷：流式那条路一次 paintAll 都不走（它只在缓存命中的 render 里），
#   于是适配器一直是默认态 —— 第一屏**不藏标签、不高亮关键词**（v1.0.39 的行为丢了）。
#   编译绿、自检绿、只是少了个行为，正是本项目最忌讳的静默失效。
#   守它：铺块之前必须自己把搜索态设上，不能指望别处。
ok("★ 流式铺块前自己设搜索态（不指望 paintAll —— 第一屏也要藏标签/高亮）",
   0 <= B_ARRIVED.find("videoAdapter.setSearchKeyword(keyword)") <
   B_ARRIVED.find("videoAdapter.insertBlock("))

print()
print("== C. 流式必须和 v1.0.51 的缓存/归属机制接上，不许回退 ==")
ok("★ 有界面归属变量 paintSeq", "private var paintSeq = 0" in SA)
ok("★ 有「这一栏还在跑」的表 inflight", "private val inflight = HashMap<String, Int>()" in SA)
ok("★ 发起时两样都认领（paintSeq = seq 与 inflight[key] = seq）",
   "paintSeq = seq" in B_LOAD and "inflight[key] = seq" in B_LOAD)
ok("★ 切回一栏时**认领**而不是重发（paintSeq = run）", "paintSeq = run" in B_SELECT)
ok("★ 换栏路径上仍然不许有请求", B_SELECT.count("load(1, false)") == 1)
for token in ["search(", "adapterFor(", "AggSearch.run"]:
    ok("★ 换栏不触发 [%s]" % token, token not in B_SELECT)
ok("★ 旧的 loadSeq 判据已全部换掉（否则流式第二次到达就再也改不了界面）",
   "seq == loadSeq" not in SA and SA.count("seq == paintSeq") >= 3)
ok("★ render() 推归属（缓存命中时让别的栏在飞的结果作废）", "paintSeq = ++loadSeq" in SA)
ok("★ 这一轮结束时按编号摘 inflight（旧轮不许摘掉新的那一轮）",
   "if (inflight[key] == seq) inflight.remove(key)" in B_LOAD)
ok("★ 缓存只有一个写入口 remember（界面与缓存不许各说各话）",
   SA.count("cache[key] = st") == 1 and SA.count("remember(") == 4)
ok("流式那一趟也要落缓存（中途切走再回来是零请求）", "remember(key, st)" in B_ARRIVED)
ok("条数一次写两处、同取 st.count（顶栏与徽标不许各自记账）",
   'binding.tvCount.text = if (st.cards == 0) "" else st.count' in SA and
   "railAdapter.setCount(index, st.count)" in SA)

print()
print("== D. 需求2：去广告的判据层是纯逻辑（离线可断言）==")
ok("AdBlock 存在", "object AdBlock {" in AB)
ok("★ AdBlock 不 import 任何 Android 类（所以 runadb.py 能在普通 JVM 里跑真产物）",
   "import android" not in AB)
ok("★ 媒体安全阀排在广告判据之前（漏拦一个广告 vs 误拦一个分片，代价不对称）",
   0 <= body_of(AB, "fun blockedResource(").find("isMedia(u)") <
   body_of(AB, "fun blockedResource(").find("looksLikeAd(u)"))
ok("★ 复用 Media.looksLikeMedia（媒体判据只有一份，不另抄一个扩展名表）",
   "Media.looksLikeMedia(url)" in AB)
ok("★ 主机标签级与路径段级是**两个**集合（共用一定会有一边是错的）",
   "private val AD_LABELS" in AB and "private val AD_SEG" in AB)
ok("★ 代码里没有广谱 CSS 选择器（注释里提到不算）", '"[class*=' not in AB)
ok("hideCss 的选择器由 AD_HOSTS 生成（判据与产出同源）",
   'AD_HOSTS.forEach { sel += "iframe[src*=' in AB or "AD_HOSTS.forEach" in AB)
ok("hideJs 带幂等闸", "window.__vsAdCss" in AB)
ok("跳转判据四条规则的顺序：跳App → 广告目标 → 有手势/重定向放行 → 跨站无手势",
   0 <= body_of(AB, "fun blockNav(").find("isAppJump(t)") <
   body_of(AB, "fun blockNav(").find("looksLikeAd(t)") <
   body_of(AB, "fun blockNav(").find("if (hasGesture || isRedirect) return false") <
   body_of(AB, "fun blockNav(").find("return crossSite(from, t)"))

print()
print("== E. 需求2：接线（拦在哪一层、什么顺序）==")
B_INT = body_of(SN, "override fun shouldInterceptRequest(")
# 判据：拦在前、上报在后（顺序本身就是"被拦的不进候选"这个行为）。
# 写法不绑 `offer(u)` / `offer(it)`：命令式与 `?.let { offer(it) }` 两种写法都认。
i_blk = B_INT.find("WebAdBlock.intercept(u)")
i_off = B_INT.find("offer(")
ok("★ 嗅探页：先拦（WebAdBlock.intercept）再上报候选（offer）—— 被拦的不进候选",
   0 <= i_blk < i_off)
B_OLR = body_of(SN, "override fun onLoadResource(")
ok("★ 嗅探页：onLoadResource 那条路也挡（否则被拦的地址从另一条路又进候选）",
   "AdBlock.blockedResource(url)" in B_OLR)
ok("★ 嗅探页：两个 shouldOverrideUrlLoading 重载都接了守卫",
   SN.count("override fun shouldOverrideUrlLoading") == 2 and SN.count("guardNav(") == 3)
ok("嗅探页：拦下跳转时说出来（toast + 留痕），不是静默丢弃",
   "R.string.adblock_nav_blocked" in SN and "NetLog.record(to, 0, 0" in WB)
i_guard = CA.find("if (guardNav(to, request)) return true")
seg = CA[i_guard:i_guard + 240] if i_guard > 0 else ""
ok("★ 校准页：拦截在前、记录在后（被拦的广告页不许写进 pageUrl）",
   i_guard > 0 and 0 <= seg.find("pageUrl = to") and seg.find("return true") < seg.find("pageUrl = to"))
ok("★ 校准页：两个重载都接了守卫",
   CA.count("override fun shouldOverrideUrlLoading") == 2 and CA.count("guardNav(") == 3)
# ⚠️ 校准页这里是**表达式体**（`= if (...) ... else null`），没有花括号 ⇒ body_of 取不到
#    （它会滑到下一个带花括号的函数上去）。所以改为直接钉那一行的三元语义：
#    「开关开 ⇒ 拦、否则放行」。判据落在行为上，比钉"某个函数体里恰好长什么样"更稳。
ok("★ 校准页也拦子资源（少几个浮层就少几次点错）",
   re.search(r"if \(adBlockOn\)\s+WebAdBlock\.intercept\(", CA) is not None)
ok("★ 校准页的说明点明了「不看渲染后的 DOM」这条前提",
   "Http.getOrNull" in CA and "原始 HTML" in CA)
ok("WebRender 也接了拦截（DOM 更接近正文），且受同一个开关控制",
   "WebAdBlock.intercept(r?.url?.toString())" in WR and "Store.adBlock(act)" in WR)
ok("★ WebAdBlock.intercept 只在判据通过时才拦（不是无脑拦）",
   "if (!AdBlock.blockedResource(u)) return null" in WB)
ok("★ 拦下的地址进 NetLog（问题可粘贴，不靠猜）", '"已拦截（去广告）"' in WB or "已拦截（去广告）" in WB)
ok("★ 开关默认开（getBoolean(KEY_ADBLOCK, true)）", "getBoolean(KEY_ADBLOCK, true)" in ST)
ok("★ 开关能关（setAdBlock 写 SharedPreferences）",
   "fun setAdBlock(ctx: Context, on: Boolean)" in ST)
ok("★ 嗅探页切换开关后重载（只改判据不重载 = '关掉了广告还在'这种中间态）",
   "binding.webView.reload()" in body_of(SN, "private fun toggleAdBlock("))
ok("★ 校准页切换开关后也重载", "binding.webView.reload()" in body_of(CA, "private fun toggleAdBlock("))
ok("两处开关都写回 Store", SN.count("WebAdBlock.setOn(this, adBlockOn)") == 1 and
   CA.count("WebAdBlock.setOn(this, adBlockOn)") == 1)
ok("嗅探报告里带一行去广告统计（拦了多少条，可粘贴）",
   "WebAdBlock.reportLine(adBlockOn)" in SN)

print()
print("== F. 界面：开关摆在'出事时够得着'的地方 ==")
LS = rd(os.path.join(RES, "layout", "activity_sniff.xml"))
LC = rd(os.path.join(RES, "layout", "activity_calibrate.xml"))
ok("嗅探页有开关", 'android:id="@+id/btnAdBlock"' in LS)
ok("校准页有开关", 'android:id="@+id/btnAdBlock"' in LC)
ok("★ 嗅探页的开关在**手柄行**（浮窗收着时也够得着）",
   LS.find('android:id="@+id/btnAdBlock"') < LS.find('android:id="@+id/panelBody"'))
STR = rd(os.path.join(RES, "values", "strings.xml"))
for s in ("adblock_on", "adblock_off", "adblock_on_toast", "adblock_off_toast",
          "adblock_nav_blocked"):
    ok("string %s 存在" % s, ('name="%s"' % s) in STR)

print()
print("== G. 版本与注册 ==")
import re
# ⚠️ 别钉死精确版本 —— runui51 在 v1.0.52 已经把这条教训写下来了（"每次 bump 都会假红"），
#    runui52 又犯了一遍。口径统一成：**只守"没被回退" + 形状对**，
#    精确值交给 `runsign.py --expect 1.0.X`（它读的是真 APK 里的 versionName）。
_vc = re.search(r"versionCode (\d+)", rd(GRADLE))
ok("versionCode ≥ 52（v1.0.52 的机制没有被回退到更早的版本线）",
   _vc is not None and int(_vc.group(1)) >= 52)
ok("versionName 形如 1.0.x（精确值交给 runsign.py --expect）",
   re.search(r'versionName "1\.0\.\d+"', rd(GRADLE)) is not None)
R = rd(os.path.join(VERIFY, "runall.py"))
ok("runadb（纯逻辑 harness）已注册进 runall.py", "'runadb'" in R)
ok("runui52 已注册进 runall.py", "'runui52'" in R)
ok("runui51 仍在（没被顶掉）", "'runui51'" in R)
ok("Adb.java / runadb.py 都在", os.path.exists(os.path.join(VERIFY, "Adb.java")) and
   os.path.exists(os.path.join(VERIFY, "runadb.py")))

print()
print("=" * 72)
print("Ui52  pass=%d fail=%d" % (ok_n, fail_n))
print("=" * 72)
sys.exit(1 if fail_n else 0)
