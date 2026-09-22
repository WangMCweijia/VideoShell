#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ui40 — 搜索态收掉站点分类条（rvCats + catHintRow）的源码守卫（v1.0.41 立，v1.0.50 重定向）。

纯 UI 改动，渲染效果离线造不出来 ⇒ 没有运行时断言；全部证据是源码守卫，
外加一条「判据一致性」守卫（收分类条的判据必须与 setSearchKeyword 一致）。

⚠️ v1.0.50 记录：本节原来的 3 条断言钉在 **reload() 的实现位置**上
（`val searching = mode == MODE_SEARCH` 加两行赋值），而 v1.0.50 把这三行抽成了
`applyCatVisibility()` 这个唯一出口 —— 断言因此全红，**功能却一直是好的**。
处理方式是把断言挪到新的唯一出口上（顺带把它升级成"可见性赋值只此一处"这种
真正防回归的形态），而不是把代码改回去、重新制造第二份判据。
教训：断言要钉**行为与唯一性**，不要钉某个函数里恰好长什么样。
"""
import io, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _cp  # noqa: E402
ROOT = _cp.project_root()
SRC = os.path.join(ROOT, "app", "src", "main", "java", "com", "videoshell")

ok_n = fail_n = 0
def ok(name, cond):
    global ok_n, fail_n
    if cond: ok_n += 1; print("  [PASS] " + name)
    else:    fail_n += 1; print("  [FAIL] " + name)

def read(p):
    with io.open(os.path.join(SRC, p), encoding="utf-8") as f:
        return f.read()

def body_of(src, signature):
    """取某个函数的函数体（按花括号配平）。

    ⚠️ v1.0.50 起必须用它，不能再用「从签名切到下一个 4 空格缩进的 }」那种粗切片：
    现在的函数体里有嵌套块，粗切片会截短或越界，断言就变成在猜边界。
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


print("== A. 分类条可见性收口（SiteBrowser.kt）==")
sb = read(os.path.join("ui", "SiteBrowser.kt"))
# ⚠️ v1.0.50 把「判据 + 两行可见性赋值」从 reload() 里抽成了 applyCatVisibility()。
# 本节原来的三条断言钉的是 reload() 里的**实现位置**，于是重构一次就全红，而功能一直是好的
# —— 这就是「把实现位置当判据」的代价。
# 修法是把断言**挪到新的唯一出口**上，而不是把代码改回去、重新制造第二份判据。
ok("★ 存在唯一出口 applyCatVisibility()", "private fun applyCatVisibility()" in sb)
acb = body_of(sb, "private fun applyCatVisibility()")
ok("★ 判据是 searchingNow()，不再散落各处", "searchingNow()" in acb)
ok("★ 搜索态 rvCats 隐藏（chips 行）",
   "b.rvCats.visibility = if (searching) View.GONE else View.VISIBLE" in acb)
ok("★ 搜索态 catHintRow 隐藏（「分类 N 个」提示行）",
   "b.catHintRow.visibility = if (searching) View.GONE else View.VISIBLE" in acb)
# 这一条是 v1.0.50 那个真回归的**根因断言**：判据只要有两份，晚到的那一份就会漏判。
ok("★ 可见性赋值只此一处（两处各判一次 = 必漏一处）",
   sb.count("b.rvCats.visibility =") == 1)
rv = body_of(sb, "fun reload()")
ok("reload() 仍然收掉分类条（改为调用那唯一出口）", "applyCatVisibility()" in rv)
ok("reload() 仍继续 load(1, false)（没把加载逻辑改丢）", "load(1, false)" in rv)
# v1.0.50 的回归恰恰出在**分类加载的异步尾巴**上：它没走 reload()，于是自己判了一次又判漏。
ok("★ 分类加载的异步尾巴也收分类条（回归就出在这一条路径上）",
   "applyCatVisibility()" in body_of(sb, "private fun loadCategories()"))
ok("★ 判据与 setSearchKeyword 一致（同一处 mode == MODE_SEARCH 比较）",
   "videoAdapter.setSearchKeyword(if (mode == MODE_SEARCH) keyword else \"\")" in sb)

print("== B. 「离开搜索」的三个入口汇到同一个方法 ==")
ok("doSearch 里用 mode = MODE_SEARCH 标记搜索态", "mode = MODE_SEARCH" in sb)
ok("doSearch() 在无二级页宿主时退回 reload()",
   "reload()" in body_of(sb, "private fun doSearch()"))
# v1.0.50：放大镜由「自己重排一遍状态」改为调 exitSearch()，与 ✕ / 返回键同源。
ok("★ 放大镜再点一次走 exitSearch（不再自己重排一份状态）",
   "exitSearch()" in body_of(sb, "b.btnSearchToggle.setOnClickListener"))
ok("★ exitSearch() 内部会 reload()（网格里留着搜索结果时退回分类）",
   "reload()" in body_of(sb, "fun exitSearch(): Boolean"))
ok("onCategory() 调用 reload()", "reload()" in body_of(sb, "private fun onCategory("))

print("== C. 回归守卫（v1.0.39 的成果不得回退）==")
ok("卡片副标题藏标签逻辑仍在（hideTags）", "hideTags" in read(os.path.join("ui", "adapter", "VideoAdapter.kt")))
ok("分组标题行仍在（VideoRow.Header）", "Header" in read(os.path.join("data", "model", "VideoRow.kt")))
ok("SpanSizeLookup 整行跨列仍在", "isHeader(position)" in sb)

print()
print("==== Ui40  pass=%d fail=%d ====" % (ok_n, fail_n))
sys.exit(1 if fail_n else 0)
