# -*- coding: utf-8 -*-
"""拆 god file 的收尾器：编译 → 自动修可见性/限定名 → 再编译，直到没有这两类错误。

处理几类「纯搬运」必然撞上的编译错（都是同一件事的不同措辞：**谁看得见谁**）：
  A) `Cannot access 'x': it is private in 'Xxx'` —— Kotlin 扩展函数访问不了 private 成员，
     把声明放宽成 internal 即可（不动逻辑）。
  A') `Cannot access 'x': it is private in file` —— 同一件事，但声明是**顶层** private，
     报错里不带所有者类名。认不出来就会每轮都出现在"其他"里、循环永不收敛。
  A'') `'internal' function exposes its 'private-in-class' return type Card` —— 不是"访问不到"，
     而是 internal 函数把 private 类型泄露出去了（函数签名里的 Card / LineBlock）。
     语义一样：放宽那个类型。以上三条共用 bump_visibility。
  B) `Unresolved reference: KEY_XXX` —— companion object 的常量在类外必须写
     `Xxx.KEY_XXX`（不能裸写名字，也不能 import），把声明放宽 + 补限定名。

刻意**只**自动处理这几类：它们在语义上是恒等变换。其他错误（类型不匹配、签名对不上）
一律原样打印出来 —— 那种错说明搬运真的改变了行为，必须人看，不能顺手"修好"。

⚠️ **本脚本第一版有个假绿缺陷**（必须记着）：它把「`e:` 行里没有这两类错」当成通过，
于是**编译根本没跑起来**（比如资源链接失败、Manifest 引用了还不存在的 @xml/…）
也会打出"这两类错误已清空"。判据必须带上"编译本身到了哪一步"：
资源/Manifest 这一类失败发生在 Kotlin 编译**之前**，此时两类错误当然是 0 条，
但那不是"修好了"，是"没轮到"。所以每轮都要单独报 FLOW 状态，并且只有在
真的 BUILD SUCCESSFUL（或至少跑到了 compileDebugKotlin 且无 e:）时才敢 break。

用法：python tools/refactor/fix_visibility.py PlayerActivity [最多轮数]
"""
import io
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from repo_root import ROOT, src_root  # noqa: E402  （必须先改 sys.path 才能 import 同目录模块）

LOG = os.path.join(ROOT, '_build.log')
OWNER = sys.argv[1] if len(sys.argv) > 1 else 'PlayerActivity'
ROUNDS = int(sys.argv[2]) if len(sys.argv) > 2 else 6
SRC = src_root()

files = {}
for dp, dn, fn in os.walk(SRC):
    for f in fn:
        if f.endswith('.kt'):
            files[f] = os.path.join(dp, f)

def companion_members(owner_file):
    """解析 Xxx.kt 里 companion object 的 val/const val 成员名。"""
    if owner_file not in files:
        return set()
    lines = io.open(files[owner_file], encoding='utf-8').read().split('\n')
    depth, inside, names = 0, False, []
    for l in lines:
        if not inside and re.search(r'\bcompanion object\b', l):
            inside = True
        if inside:
            depth += l.count('{') - l.count('}')
            m = re.match(r'^ +(?:private |internal |public )?(?:const )?val (\w+)', l)
            if m:
                names.append(m.group(1))
            if depth <= 0 and '{' not in l and 'companion' not in l:
                break
    return set(names)


def nested_types(owner):
    """owner 主文件里声明的**嵌套类型**名（class / data class / inner class / object / interface）。

    为什么需要它：嵌套类型**不会**自动进入别的文件的作用域 —— 被搬运的扩展函数一旦把它们
    写进签名（`: Pick?` / `: Card?` / `: List<LineBlock>?`），顶层作用域里就解析不到，
    编译器报 `Unresolved reference: Pick`。补一行同包 import 即可（同包内 import 是合法的）。

    这正是 _split.py 的 spec 头部可以手写 import 的那件事；放在这里是为了**不依赖人记住** ——
    漏写时循环会一直"没进展"，比直接红更难查。
    """
    path = files.get(owner + '.kt')
    if not path:
        return {}
    txt = io.open(path, encoding='utf-8').read()
    pkg = ''
    m = re.search(r'^package ([\w.]+)', txt, re.M)
    if m:
        pkg = m.group(1)
    out = {}
    for m in re.finditer(r'^ +(?:private |internal |protected )?'
                         r'(?:inner |data |sealed |enum |annotation |value )*'
                         r'(?:class|interface|object) (\w+)', txt, re.M):
        out[m.group(1)] = pkg + '.' + owner + '.' + m.group(1)
    return out

# 声明形态必须**全部**覆盖，否则会出现"每轮都报放宽 0 处，错误却一直在"（第一版就栽在这两次：
# 先是只写了 `private val`，而这批字段几乎都是 `private var` / `private lateinit var`；
# 再是漏了 `private suspend fun` —— 搬运时 suspend 是必须一起带走的，这里也一样不能漏）。
# 第三次栽在**嵌套类型**上：`private data class Card` 被扩展函数当返回类型（HtmlExtractor），
# 编译器报的是 `Cannot access 'Card': it is private` —— 名字对得上、正则却认不出 class。
# 第四次栽在 **`inner class`** 上：`private inner class Bridge` 同理，
# 而修饰符列表里漏了 `inner `，于是每轮都"放宽 0 处"、错误一直在（症状一模一样）。
DECL = (r'(?:(?:const |lateinit )?(?:val|var)'
        r'|(?:suspend )?fun'
        r'|(?:inner |data |sealed |enum |annotation |value )*(?:class|object|interface))')

def bump_visibility(text, names):
    """把 `private X name` 放宽成 `internal X name`。

    indentation 用 `( *)` 而不是 `( +)`：**类成员**是缩进的（4 空格），但拆出去的子文件里
    被搬走的声明已经**去掉一层缩进、落在第 0 列**（`private class LineBlock(...)`）——
    只认带缩进的话就会对子文件"放宽 0 处"。
    """
    n = 0
    for name in names:
        pat = re.compile(r'^( *)private (' + DECL + r' )' + re.escape(name) + r'\b', re.M)
        text, k = pat.subn(lambda m: m.group(1) + 'internal ' + m.group(2) + name, text)
        n += k
    return text, n

def decl_files(owner):
    """声明可能住的地方 = `Owner.kt` **以及**它的拆分子文件 `Owner_*.kt`。

    为什么不能只看主文件：搬迁把声明一起带走了（`LineBlock`、`Card`），而报错信息里的
    "'it is private in 'HtmlExtractor''" 说的是**所有者类名**，不是文件。只看主文件就会
    每轮"放宽 0 处"，错误却一直在 —— 与第一版栽的坑同型。
    """
    out = []
    if owner + '.kt' in files:
        out.append(files[owner + '.kt'])
    pre = owner + '_'
    for f in sorted(files):
        if f.startswith(pre) and f.endswith('.kt'):
            out.append(files[f])
    return out

def qualify(text, name):
    """给裸引用补上 `Owner.` 前缀（跳过注释行；已限定过的不重复补）。"""
    out, n = [], 0
    pat = re.compile(r'(?<![\w.])' + re.escape(name) + r'\b')
    for l in text.split('\n'):
        st = l.lstrip()
        if st.startswith(('//', '*', '/*')):
            out.append(l)
            continue
        l2, k = pat.subn(OWNER + '.' + name, l)
        n += k
        out.append(l2)
    return '\n'.join(out), n

def static_widen(owner):
    """把「拆分子文件里**被引用**、但主文件里还是 private」的声明先放宽成 internal。

    ⚠️ 为什么不能只靠编译错误驱动（本脚本前 4 次栽的都是同一类：看不见就当没有）：
    **类内 private 字段的名字一旦撞上「继承自父类的属性」，搬出类之后编译器不会报
    "访问不到"，而是静默改用父类那一个。**

    实例（SniffActivity）：
      * 类内 `private var title: String` 遮蔽 `Activity.getTitle(): CharSequence!`；
      * 搬到 `internal fun SniffActivity.xxx()` 后，private 字段看不见了，
        Kotlin 不报错，改用父类的 `title`（CharSequence）；
      * 表现是 `Type mismatch: inferred type is CharSequence! but String was expected`
        —— 这还算幸运的。**更坏的情况**：那一处本来就收 CharSequence，
        于是静默变成了「Activity 的标题」，行为已经变了而编译全绿。

    所以必须在编译**之前**按"引用"静态放宽一遍：凡是子文件里出现过的标识符，
    只要主文件里是 private 类级声明，就放宽。放宽可见性不改行为，多放宽几个只是难看。
    """
    subs = decl_files(owner)[1:]
    path = files.get(owner + '.kt')
    if not subs or not path:
        return []
    words = set()
    for p in subs:
        words |= set(re.findall(r'\w+', io.open(p, encoding='utf-8').read()))
    txt = io.open(path, encoding='utf-8').read()
    names = sorted({m.group(1) for m in re.finditer(
        r'^ {4,}private (?:' + DECL + r' )(\w+)', txt, re.M)})
    hit = [n for n in names if n in words]
    if hit:
        txt, _ = bump_visibility(txt, hit)
        io.open(path, 'w', encoding='utf-8', newline='').write(txt)
    return hit


def flow_of(txt):
    """这一轮编译**走到哪儿了**。返回 (状态字符串, 是否到达 Kotlin 编译)。

    processDebugResources / processDebugManifest 失败 ⇒ 根本没轮到 Kotlin，
    此时"两类错误 0 条"是**假绿**，必须单独报出来，绝不能当通过。
    """
    if 'BUILD SUCCESSFUL' in txt:
        return 'BUILD SUCCESSFUL', True
    if 'BUILD FAILED' not in txt:
        return '未完成（日志里既没成功也没失败——多半被超时杀掉）', False
    m = re.search(r'^> Task :(\S+) FAILED', txt, re.M)
    task = m.group(1) if m else '(未知 task)'
    if 'compileDebugKotlin' in task or 'compileDebugJavaWithJavac' in task:
        return 'BUILD FAILED @ ' + task + '（已到 Kotlin，下面按错误分类）', True
    return 'BUILD FAILED @ ' + task + '（**在 Kotlin 之前**，本轮两类错误 0 条无意义）', False

pre = static_widen(OWNER)
if pre:
    print('== 编译前静态放宽（子文件里被引用到的 private 类级声明）==')
    print('   %s' % ','.join(pre))

for rnd in range(1, ROUNDS + 1):
    subprocess.run([sys.executable, os.path.join(ROOT, '_build.py'),
                    ':app:compileDebugKotlin'],
                   capture_output=True, text=True, encoding='utf-8',
                   errors='replace', cwd=ROOT)
    txt = io.open(LOG, encoding='utf-8', errors='replace').read()
    flow, reached_kotlin = flow_of(txt)

    priv, unres, need, others = {}, {}, {}, []
    for line in txt.splitlines():
        if not line.startswith('e: '):
            continue
        m = re.search(r"([\w]+\.kt):\d+:\d+ Cannot access '(\w+)': it is private in '(\w+)'", line)
        if m:
            priv.setdefault((m.group(1), m.group(3)), set()).add(m.group(2))
            continue
        # 顶层 `private` 的报错**不写所有者类名**（`it is private in file`）—— 上面那条正则
        # 认不出，于是"其他"里每轮都有它，循环永远不收敛。TAB_BAR_TEXT 就是这一型。
        m = re.search(r"([\w]+\.kt):\d+:\d+ Cannot access '(\w+)': it is private in file", line)
        if m:
            need.setdefault(m.group(2), set()).add(m.group(1))
            continue
        # 第三型：不是"访问不到"，而是 **internal 函数把 private 类型泄露出去了**
        # （`exposes its 'private-in-class' return type Card` / `'private-in-file' return type
        #  argument LineBlock`）。语义上同样只是可见性问题，放宽声明即可。
        # ⚠️ 备选顺序必须**长的在前** —— 写成 `return type|return type argument` 时，
        # `return type` 先命中，剩下的 ` argument LineBlock` 会被当成名字捕获成 `argument`。
        m = re.search(r"([\w]+\.kt):\d+:\d+ 'internal' function exposes its '[^']*' "
                      r"(?:return type argument|return type|parameter type|property type|type argument) (\w+)",
                      line)
        if m:
            need.setdefault(m.group(2), set()).add(m.group(1))
            continue
        m = re.search(r"([\w]+\.kt):\d+:\d+ Unresolved reference: (\w+)", line)
        if m:
            unres.setdefault(m.group(1), set()).add(m.group(2))
            continue
        others.append(line)

    print('=== 第 %d 轮：%s' % (rnd, flow))
    print('    private %d 组 / 顶层·泄露 %d 个 / unresolved %d 组 / 其他 %d 条'
          % (len(priv), len(need), len(unres), len(others)))
    for line in others[:25]:
        print('    ', line[:210])

    if not reached_kotlin:
        print('==== 停在 Kotlin 之前，先修上面那个 task（不是本脚本的活）====')
        break

    if not priv and not unres and not need:
        print('==== 这几类错误已清空（且确实到了 Kotlin 编译阶段）====')
        if 'BUILD SUCCESSFUL' not in txt:
            print('     注意：仍非 BUILD SUCCESSFUL，见上面的 task（可能是 processResources 之类）')
        break

    # 这一轮到底改动了几个字符？0 ⇒ 上面那些"放宽 0 处"就是空转，必须停下来喊。
    changed = 0

    if need:
        # 报错里没有所有者类名 ⇒ 只能按**声明本身**找：在 owner 主文件 + 拆分子文件里搜
        # `private X <name>`。搜到两个以上同名的（比如与别处的 public Card 撞名）也不要紧 ——
        # 正则是 `private` 开头，public 的那个不会命中。
        scanned = decl_files(OWNER)
        for name, where in sorted(need.items()):
            done = []
            for path in scanned:
                t = io.open(path, encoding='utf-8').read()
                t, n = bump_visibility(t, [name])
                if n:
                    io.open(path, 'w', encoding='utf-8', newline='').write(t)
                    done.append('%s×%d' % (os.path.basename(path), n))
                    changed += n
            print('    %-32s 顶层/泄露放宽：%s'
                  % (name, ','.join(done) if done else '**一处都没放宽** ← 名字对不上，人工看'))

    for (ktfile, owner), names in priv.items():
        # ⚠️ 声明在 **owner** 那个类里，报错却在**调用方**文件里 —— 必须按 owner 找文件，
        # 在报错文件里找 `private var xxx` 是永远找不到的（第一版就栽在这，每轮都报"放宽 0 处"）。
        # 而且声明可能已经随函数搬进了 `Owner_*.kt`（LineBlock / Card），所以两个地方都要找。
        cands = decl_files(owner) or [files.get(ktfile)]
        total = 0
        for path in cands:
            if not path:
                continue
            t = io.open(path, encoding='utf-8').read()
            t, n = bump_visibility(t, names)
            if n:
                io.open(path, 'w', encoding='utf-8', newline='').write(t)
            total += n
        changed += total
        print('    %-32s 放宽 private %d 处（%s）'
              % (owner + '.kt', total, ','.join(sorted(names))[:90]))

    comp = companion_members(OWNER + '.kt')
    nested = nested_types(OWNER)
    for ktfile, names in sorted(unres.items()):
        # (a) companion 常量：类外必须写限定名（不能裸写、不能 import）⇒ 在调用点补前缀。
        hits = sorted(n for n in names if n in comp)
        if hits:
            path = files.get(ktfile)
            t = io.open(path, encoding='utf-8').read()
            tot = 0
            for name in hits:
                t, n = qualify(t, name)
                tot += n
            io.open(path, 'w', encoding='utf-8', newline='').write(t)
            # companion 声明同步放宽
            op = files[OWNER + '.kt']
            ot = io.open(op, encoding='utf-8').read()
            ot, m2 = bump_visibility(ot, hits)
            io.open(op, 'w', encoding='utf-8', newline='').write(ot)
            print('    %-32s 补 %s 限定名 %d 处（companion 放宽 %d）'
                  % (ktfile, ','.join(hits), tot, m2))
            changed += max(tot, m2, 1)

        # (b) 嵌套类型：补一行同包 import（并把它放宽成 internal，否则 import 本身就被拒）。
        nest = sorted(n for n in names if n in nested)
        if nest:
            path = files.get(ktfile)
            if not path:
                continue
            t = io.open(path, encoding='utf-8').read()
            added = []
            for name in nest:
                imp = 'import ' + nested[name]
                if imp in t:
                    continue
                m = re.search(r'^package [\w.]+\n', t, re.M)
                if not m:
                    continue
                t = t[:m.end()] + '\n' + imp + '\n' + t[m.end():]
                added.append(name)
            if added:
                io.open(path, 'w', encoding='utf-8', newline='').write(t)
            # 嵌套类型的声明放宽（可能已随函数搬进子文件，所以扫一遍 owner 的全部文件）
            m2 = 0
            for p2 in decl_files(OWNER):
                t2 = io.open(p2, encoding='utf-8').read()
                t2, n2 = bump_visibility(t2, nest)
                if n2:
                    io.open(p2, 'w', encoding='utf-8', newline='').write(t2)
                m2 += n2
            print('    %-32s 补嵌套类型 import %s（声明放宽 %d）'
                  % (ktfile, ','.join(added) if added else '(已存在)', m2))
            changed += max(len(added), m2, 1)

    # 无进展就必须停下来喊 —— 否则循环 8 轮、每轮都说"处理了 0 处"，
            # 看起来像在干活，实际上一个字没改。这正是第一版"假绿"的同一类毛病。
    left = sorted(n for ns in unres.values() for n in ns
                  if n not in comp and n not in nested)
    if changed == 0:
        print('==== 本轮**一处都没改**，但还剩 %d 组错误 —— 停下来人工看，别空转 ====' % len(others))
        break
    if left:
        print('    ⚠️ 未处理的 Unresolved reference（既不是 companion 常量，也不是 owner 的嵌套类型）：%s'
              % ','.join(left[:12]))
        print('       ⇒ 多半是搬运把某个声明留在了原文件、而判据/调用点已经搬走；人工确认。')
