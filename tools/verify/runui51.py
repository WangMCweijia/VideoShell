#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ui51 — v1.0.51「左栏每栏各自留一份结果，切回去不再重搜」的离线守卫。

用户的原话是「**每点一个站源都需要重新搜索 请优化**」。这条抱怨的难点在于：
它**不在任何一条报错里**，也不改变任何一次搜索的结果 —— 只是把同一份数据反复要一遍。
编译器、自检、甚至"点一下有东西出来"都是绿的，只有**次数**不对。

所以这一版断言打在三件事上：

  1. **换栏这条路上不许有请求**（结构性：`selectRail` 的函数体里不能出现 `search(` /
     `adapterFor(` / `AggSearch.run`）。
  2. **迟到的结果不许改界面，但必须写缓存**（位置判据：`cache[key] = st` 必须排在
     收尾那处 `if (seq == loadSeq)` **之前**；`render()` 必须把代次往前推一格）。
     反过来说，`load()` 里不许再有 v1.0.50 那个 `if (loading) return` ——
     它会把用户点的那一栏**静默丢弃**，而左栏高亮已经移过去了，界面在撒谎。
  3. **预填的三条规矩**（失败站不收 / 真搜过的站不覆盖 / 被截断的块不收）——
     每一条漏掉都会把"省一次请求"变成"显示错的东西"，而且都是**只在特定站点上复现**的那种错。

⚠️ 断言尽量钉**行为 + 唯一性**，不钉"某个函数里恰好长什么样"（v1.0.50 的教训）。
真有位置判据的地方（②的两条与⑨的回填顺序），是因为**先后顺序本身就是那个行为**。
"""
import io, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _cp  # noqa: E402
ROOT = _cp.project_root()
GRADLE = os.path.join(ROOT, "app", "build.gradle")
VERIFY = os.path.dirname(os.path.abspath(__file__))
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


def rd(p):
    with io.open(p, encoding="utf-8") as f:
        return f.read()


def rs(*parts):
    return rd(os.path.join(SRC, *parts))


def body_of(src, signature):
    """按花括号配平取**有函数体**的函数的正文。

    ⚠️ 不能用"从签名切到下一个 4 空格缩进的 }"那种粗切片：`load()` 里有 `when`、
    嵌套的 `if/else` 与 lambda，粗切片会截短，断言就变成在猜边界。
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


def expr_of(src, signature):
    """取**表达式体 / 无花括号声明**的正文：从签名切到下一个空行。

    `fun complete(...): Boolean = block(h, perSite).size == ...` 与
    `private class RailState(` 这类都**没有函数体花括号**。对它们用 body_of() 会去找
    **别处**的 `{`，取回来一段毫不相干的代码 —— 症状是断言莫名其妙地红（本套件第一版
    就踩了三条：complete 的两条 + railKey 一条）。这不是特例处理，而是"取正文"的边界。
    """
    i = src.find(signature)
    if i < 0:
        return ""
    j = src.find("\n\n", i)
    return src[i:j] if j > 0 else src[i:]


SA = rs("ui", "SearchActivity.kt")
AGG = rs("data", "site", "AggSearch.kt")

# 反复要用的正文，取一次
B_SELECT = body_of(SA, "private fun selectRail(")
B_LOAD = body_of(SA, "private fun load(")
B_RENDER = body_of(SA, "private fun render(")
B_PREFILL = body_of(SA, "private fun prefill(")
B_RAILKEY = expr_of(SA, "private fun railKey(")
B_COMPLETE = expr_of(AGG, "fun complete(")
B_RAILSTATE = expr_of(SA, "private class RailState(")
# v1.0.52 抽出来的三个：流式那一趟（streamAggregate）、它的到达动作（commitArrived）、
# 周边文字的收口（commitChrome）。下面几条位置判据跟着它们走 —— 不是把判据放宽，
# 而是 v1.0.51 那几条钉的"某个函数里恰好长什么样"本来就该钉在**收口点**上。
B_STREAM51 = body_of(SA, "private suspend fun streamAggregate(")
B_CHROME = body_of(SA, "private fun commitChrome(")
B_FILL51 = body_of(SA, "private fun fillRailCount(")

print("== A. 换栏路径上不许有网络请求（用户抱怨的就是这件事）==")
ok("selectRail() 取到了函数体", len(B_SELECT) > 40)
ok("★ 换栏命中缓存就整份还原（cache[key] → render()）",
   "cache[key]" in B_SELECT and "render(index, hit)" in B_SELECT)
ok("★ v1.0.52：这一栏还在跑时**只认领、不重发**（认领那一轮，让它接着铺）",
   "paintSeq = run" in B_SELECT)
ok("★ 未命中才 load(1, false)（且只有这一处）",
   B_SELECT.count("load(1, false)") == 1)
ok("★ render / load 互斥（命中缓存那条路上不发起请求）",
   re.search(r"if\s*\(hit != null\)\s*\{[^}]*render\(index, hit\)[^}]*\}\s*else\s*\{"
             r"[^}]*load\(1, false\)", B_SELECT, re.S) is not None)
# 结构性断言：这三样是"发起一次搜索"的全部途径，一个都不许出现在换栏路径里
for token in ["search(", "adapterFor(", "AggSearch.run"]:
    ok("★ 换栏不触发 [%s]" % token, token not in B_SELECT)
ok("换栏时把选中项同步给左栏（高亮与内容不许脱节）",
   "railAdapter.select(index)" in B_SELECT)

print()
print("== B. 缓存写入只有两个出口（load 收尾 / 聚合预填）==")
ok("cache[key] = st 出现且仅出现一次", SA.count("cache[key] = st") == 1)
ok("prefill 写缓存的形态唯一（cache[h.key] = RailState(）",
   SA.count("cache[h.key] = RailState(") == 1)
ok("★ 缓存字段存在（railKey → RailState）",
   "private val cache = HashMap<String, RailState>()" in SA)

print()
print("== C. 迟到的结果：只许写缓存，不许改界面 ==")
ok("load() 里取了轮次编号 seq = ++loadSeq", "val seq = ++loadSeq" in B_LOAD)
i_cache = B_LOAD.find("remember(key, st)")
# ⚠️ 必须找**收尾那一处** guard。body 里前面还有一处同形状的判断（翻页翻到空页的早退），
# find() 会先撞上它，位置判据就整个反过来了 —— 这套件第一版正是这样红了一条。
i_guard = B_LOAD.find("if (seq == paintSeq) render(index, st)")
ok("★ 先写缓存、再判归属（切走了的那次请求不白跑）",
   i_cache >= 0 and i_guard >= 0 and i_cache < i_guard)
ok("★ 归属不符时不 render（只落缓存）",
   "if (seq == paintSeq) render(index, st)" in B_LOAD)
# ★ 这一条是 v1.0.50 的坑：布尔量 loading 拦不住"哪一轮是旧的"，只能静默丢弃新请求
ok("★ 不再有 `if (loading) return`（换栏的点击不许被静默丢弃）",
   "if (loading) return" not in B_LOAD)
ok("★ render() 顺手推归属（否则缓存命中后，还在飞的聚合结果会把界面盖回去）",
   "paintSeq = ++loadSeq" in B_RENDER)
# 位置判据：render 里先把归属推掉，再铺界面 —— 推晚了，中途 return 就漏了
i_bump = B_RENDER.find("paintSeq = ++loadSeq")
i_put = B_RENDER.find("paintAll(")
ok("★ 推归属在铺界面之前（render 开头就宣告归属）", 0 <= i_bump < i_put)
ok("翻页的基准是**这一栏**的页码（page 跟随 RailState）", "page = st.page" in B_CHROME)
ok("★ v1.0.52：这一轮结束时按编号摘 inflight（旧轮不许摘掉新那一轮）",
   "if (inflight[key] == seq) inflight.remove(key)" in B_LOAD)

print()
print("== D. 聚合预填：三条规矩，缺一条就显示错的东西 ==")
ok("prefill() 取到了函数体", len(B_PREFILL) > 40)
ok("★ 规矩1 失败的站不收（!h.ok 早退）",
   re.search(r"if\s*\(h\.key\.isBlank\(\)\s*\|\|\s*!h\.ok\)\s*return", B_PREFILL) is not None)
ok("★ 规矩2 真搜过的站不覆盖（cache.containsKey 早退）",
   "if (cache.containsKey(h.key)) return" in B_PREFILL)
ok("★ 规矩3 被截断的块不收（AggSearch.complete 早退）",
   "if (!AggSearch.complete(h)) return" in B_PREFILL)
# 位置判据：三条早退必须在真正写入之前，否则就是"判了但还是写进去了"
i_write = B_PREFILL.find("cache[h.key] = RailState(")
i_rules = [B_PREFILL.find(t) for t in
           ("!h.ok", "cache.containsKey(h.key)", "AggSearch.complete(h)")]
ok("★ 三条早退都排在写入之前",
   i_write > 0 and all(0 <= r < i_write for r in i_rules))
# v1.0.52 起第 1 页走 streamAggregate：分派点在 load（else if (p == 1)），
# 预填发生在流式那一趟里（每到达一个站就试一次）—— 两条一起才算"只对第 1 页做"
ok("预填只对**聚合的第 1 页**做（p == 1）",
   "else if (p == 1) {" in B_LOAD and "prefill(h, q)" in B_STREAM51)
ok("预填进去的空块带状态文案（否则点进去是个像在加载的空网格）",
   "search_site_empty" in B_PREFILL)
ok("预填复用 AggSearch.block（判据与产出同源，不自己再过滤一遍）",
   "AggSearch.block(h)" in B_PREFILL)

print()
print("== E. AggSearch.complete：这块能不能当「整页」用 ==")
ok("complete() 存在且取到了声明", len(B_COMPLETE) > 20)
ok("★ 判据是「一个都没丢」（block 条数 == 原始条数）",
   "block(h, perSite).size == h.items.size" in B_COMPLETE)
ok("★ 复用 block() 判，不另写一套过滤规则（自己数 24 / 自己滤空名 = 第二份判据）",
   B_COMPLETE.count("block(h, perSite)") == 1)
ok("★ 只看条数、不看 error（失败站在这里恒为 true ⇒ 判 ok 是调用方的事）",
   "error" not in B_COMPLETE and "h.ok" not in B_COMPLETE)
ok("complete() 的文档点明了这条陷阱", "调用方必须自己先判" in AGG)

print()
print("== F. 整栏状态（RailState）：换栏只有一个动作 ==")
fields = re.findall(r"val (\w+):", B_RAILSTATE)
ok("RailState 字段齐全（rows/page/tip/count/state/failures + 派生 cards）",
   fields == ["rows", "page", "tip", "count", "state", "failures", "cards"])
ok("★ cards 是**派生值**不是第二个字段（两处各自记数就一定会不一致）",
   "val cards: Int get()" in B_RAILSTATE)
ok("★ failures 跟着栏走（切回聚合还能点开失败原因）",
   "failures = st.failures" in B_CHROME and SA.count("failures = st.failures") == 1)
ok("★ 换栏时不再无条件清空失败清单（旧实现是 failures = emptyList()）",
   "failures = emptyList()" not in B_SELECT)
# 关键字的快照：协程跑起来后 selected 可能已经变了
i_launch = B_LOAD.find("lifecycleScope.launch")
for snap in ["val index = selected", "val q = keyword",
             "val railName = railNames.getOrNull(index)"]:
    ok("★ [%s] 在 launch 之前取（否则结果会归到别的栏名下）" % snap,
       0 <= B_LOAD.find(snap) < i_launch)
i_base = B_LOAD.find("val base = if (append)")
ok("★ 翻页的基底在 launch 之前取（同上）", 0 <= i_base < i_launch)

print()
print("== G. 缓存键不能是左栏下标 ==")
ok("★ 有独立的聚合键，且形状不会与站点 key 撞（@ 开头）",
   'private const val KEY_AGG = "@agg"' in SA)
ok("railKey() 兜底成聚合键（0 号位那一项站点为 null）",
   "railSites.getOrNull(index)?.key?.takeIf { it.isNotBlank() } ?: KEY_AGG" in B_RAILKEY)
ok("★ 缓存键由 railKey() 统一给出（KEY_AGG 只在它那里兜底一次）",
   SA.count("?: KEY_AGG") == 1 and SA.count("railKey(") >= 2)

print()
print("== H. 版本与既有守卫不回归 ==")
# ⚠️ v1.0.52 改口径：原来这里钉死 `versionName "1.0.51"` —— 每次 bump 都会**假红**，
# 而它想守的东西（"交出去的包是哪个版本"）由 `runsign.py --expect <ver>` 在发布动作里管。
# 行为套件只该钉"这一版立的东西还在"：版本线不许倒退。
_vc = re.search(r"versionCode (\d+)", rd(GRADLE))
ok("versionCode ≥ 51（v1.0.51 的机制没有被回退到更早的版本线）",
   _vc is not None and int(_vc.group(1)) >= 51)
ok("versionName 形如 1.0.x（精确值交给 runsign.py --expect）",
   re.search(r'versionName "1\.0\.\d+"', rd(GRADLE)) is not None)
B_ONCFG = body_of(SA, "override fun onConfigurationChanged(")
ok("旋转不重建 ⇒ 仍然重排列数（v1.0.50 立的守卫没被这次改动吃掉）",
   "setupGrid()" in B_ONCFG)
ok("单站翻页仍只对非聚合栏开放（selected == 0 直接返回）",
   "if (selected == 0) return" in body_of(SA, "onScrolled("))
R = rd(os.path.join(VERIFY, "runall.py"))
ok("runui51 已注册进 runall.py", "runui51" in R)
ok("runui50 仍在 runall.py 里（没被顶掉）", "runui50" in R)

print()
print("== I. 条数只有一个来源（顶栏与左栏徽标不许各自记账）==")
ok("★ 收口处同时写顶栏与左栏徽标，数字都取自 st.count",
   'binding.tvCount.text = if (st.cards == 0) "" else st.count' in B_CHROME and
   "railAdapter.setCount(index, st.count)" in B_CHROME)
ok("★ 聚合那一栏的徽标不动（它写的是「共 N 个站」，比条数有用）",
   "if (index > 0) railAdapter.setCount(" in B_CHROME)
ok("★ 聚合回填条数时跳过已有自己结果的站（真搜回来的是整页，聚合那份可能截到 24 条）",
   "!cache.containsKey(h.key)" in B_FILL51)
# 位置判据：回填必须排在预填**之前** —— 反了的话，预填刚写下的站会被回填跳过，
# 徽标要等用户点进去才有数字（那正是这一版要消灭的等待）
i_fill = B_STREAM51.find("fillRailCount(h)")
i_pre = B_STREAM51.find("prefill(h, q)")
ok("★ 回填排在预填之前（否则预填过的站没有徽标）", 0 <= i_fill < i_pre)

print()
print("=" * 72)
print("Ui51  pass=%d fail=%d" % (ok_n, fail_n))
print("=" * 72)
sys.exit(1 if fail_n else 0)
