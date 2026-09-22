# -*- coding: utf-8 -*-
"""把 god file 的若干区段**纯搬运**成「扩展函数文件」——可复用，不针对某一个类。
## 为什么要有这个脚本

拆 god file 的价值不是"行数变小"，而是"改一处逻辑时不用再在 1700 行里穿越四段互不
相干的代码"。搬运动作本身必须**一行逻辑都不改**，否则它就从"重构"变成了"改动"
—— 而改动需要重新证明（见 PITFALLS §4.31 的 A/B 定位法）。

手工搬运 1700 行 × 6 个文件必然出错（第一遍就把一个 `private var currentEpKey`
连同它周围的函数一起卷走、还降成了文件级 → 三处 Unresolved reference）。
所以这一步交给脚本：**按行号切区间、去缩进、改签名**，全是机械变换。

## 变换规则（只有三条）

1. **去一层缩进**（类内成员是 4 空格，顶层扩展函数不能再缩进）；
2. `private fun name(` → `internal fun <Owner>.name(` ——
   扩展函数访问不了 `.kt` 同文件的 `private`，所以被搬走的函数一律 internal；
3. 原文件里这些行**删除**（不是复制）。守卫读源码时会把 `Owner_*.kt` 拼在主文件后面
   （见 _cp.py 的 kt()），所以删掉的行仍能被"锁代码文本"的断言看到 —— 拼接**不复制**，
   `count(x) == 1` 这类计数断言语义不变。

## ⚠️ 动手前必须先查：有没有被**当成员**调用

`internal fun Owner.x()` 之后，类外的 Java/Kotlin 调用点 `ad.x()` 仍然能编译（顶层
扩展函数对同包可见，`ad.x()` 会解析成它）—— **但 Java 不行**：Java 里没有扩展函数，
`ad.x(...)` 会直接编译不过。本工程恰好有一批 Java 守卫在**直接调用** Kotlin 类的成员
（`Verify.java`、`Bs.java`、`Yg.java`… 里的 `ad.categoriesFrom(home)`），
判据是：

    grep -rn "[.]<方法名>(" tools/verify/*.java

命中的方法**必须留在原类里**（HtmlAdapter 的 `categoriesFrom` 就是这样被钉住的）。

## 用法（spec 驱动，避免多行注释被 shell 吃掉）

    python tools/refactor/split_god_file.py spec.json

（先跑 `python tools/refactor/outline.py <文件名>` 拿每个成员的边界行号，
再据此填 spec 的 zones。包名/工作区不必写死 —— 见 `repo_root.py`。）

spec.json（**所有行号都是"动手前"的原始行号**，脚本一次算完再统一删，
不要求你按从后往前的顺序写）：
    {
      "target": "HtmlAdapter.kt",
      "owner":  "HtmlAdapter",
      "groups": [
        {"suffix": "Cats",   "zones": [[584, 765]], "header": ["…"]},
        {"suffix": "Browse", "zones": [[882, 958], [1227, 1266]], "header": ["…"]}
      ]
    }

生成后会跑一遍自检：把"顶层但不是 internal fun Owner.*"的行全列出来
—— 那些就是被误卷进来的字段/常量，必须人工搬回原类（或改判据）。
"""
import io
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from repo_root import find  # noqa: E402  （必须先改 sys.path 才能 import 同目录模块）


def main():
    spec = json.load(io.open(sys.argv[1], encoding='utf-8'))
    target = spec['target']
    owner = spec.get('owner') or target[:-3]
    groups = spec['groups']

    path = find(target)
    if not path:
        print('✗ 找不到 %s' % target)
        return 1
    text = io.open(path, encoding='utf-8').read()
    lines = text.split('\n')
    io.open(path + '.bak', 'w', encoding='utf-8', newline='').write(text)

    pkg = next((l for l in lines if l.startswith('package ')), '')
    imports = [l for l in lines if l.startswith('import ')]

    def dedent(seg):
        out = []
        for l in seg:
            if l.startswith('    '):
                out.append(l[4:])
            elif l.strip() == '':
                out.append('')
            else:
                out.append(l)  # 顶层行（区间里夹着的类级注释等）保持原样
        return out

    def convert(seg, stat):
        """把去缩进后的顶层 `... fun xxx(...)` 行改写成 `internal fun Owner.xxx(...)`。

        只认**去缩进后不带前导空格**的行（= 原来是类内一层缩进的成员）。
        `override fun` 不该出现在任何区间里 —— 覆盖方法必须留在类里，它一出现
        就是区间选错了，直接打出来让人看见，别悄悄搬。

        ⚠️ **`suspend` 必须原样带走**。第一版只取了 `fun ` 之后的部分，于是
        `private suspend fun browseCat(` 变成了 `internal fun HtmlAdapter.browseCat(` ——
        函数体里那几个挂起调用立刻全部编译不过（症状是成串的
        "Suspend function 'X' should be called only from a coroutine"）。
        `suspend` 是签名的一部分，丢掉它等于把函数改成阻塞语义。
        """
        out = []
        cur = None  # 当前正在搬运的函数名（下面要把 `this@Owner` 改写成 `this@函数名`）
        for l in seg:
            if not l.startswith(' ') and re.match(r'^(private |internal |public )?(suspend )?fun \w+', l):
                if l.startswith('override '):
                    print('  ⚠️ 区间里有 `override fun`（必须留在原类）：%s' % l[:80])
                    out.append(l)
                    continue
                sus = 'suspend ' if re.match(r'^(private |internal |public )?suspend fun ', l) else ''
                # l.index('fun ') 拿到的是 `fun 名字(...)` 这一段，要**跳过 `fun ` 四个字符**
                # 再接名字，否则会写出 `internal fun Owner.fun xxx(`。
                body = l[l.index('fun ') + 4:]
                cur = body.split('(')[0].strip()
                out.append('internal %sfun %s.%s' % (sus, owner, body))
                stat['fun'] += 1
                continue
            if cur:
                # ⚠️ `this@Owner` 在扩展函数里**不成立** —— 隐式接收者的标签是**函数名**，
                # 所以必须改写成 `this@函数名`。不改的话编译器报 `Unresolved reference: @Owner`。
                # 踩过的地方：SniffActivity.recognizeAndAdd（`this@SniffActivity` 3 处，
                # 全在 `lifecycleScope.launch { }` 里面）—— 那里**不能**直接写 `this`，
                # 因为 `this` 已经变成协程作用域了。见 PITFALLS §4.42。
                l = l.replace('this@' + owner, 'this@' + cur)
            out.append(l)
        return out

    all_zones = []
    for g in groups:
        stat = {'fun': 0}
        segs = []
        for a, b in g['zones']:
            segs += convert(dedent(lines[a - 1:b]), stat)
            all_zones.append((a, b))
        body = '\n'.join(segs).strip('\n') + '\n'
        head = pkg + '\n\n'
        if g.get('header'):
            head += '\n'.join(g['header']) + '\n\n'
        head += '\n'.join(imports) + '\n\n'

        out_name = '%s_%s.kt' % (target[:-3], g['suffix'])
        out_path = os.path.join(os.path.dirname(path), out_name)
        io.open(out_path, 'w', encoding='utf-8', newline='').write(head + body)

        print('✓ %-22s ← %s   %d 行，扩展函数 %d 个'
              % (out_name, g['zones'], len((head + body).split('\n')), stat['fun']))

        bad = []
        for i, l in enumerate(body.split('\n'), 1):
            s = l.rstrip()
            if not s or s.startswith((' ', '\t', '//', '/*', '*', '@')):
                continue
            # ⚠️ 这里的 `(?:suspend )?` 不能漏：漏掉的话 `internal suspend fun Owner.x(`
            # 会被当成"误搬进来的顶层函数"报出来 —— 而它是**合法**的扩展函数声明。
            # 假红比漏报更坏：真被卷错的字段会淹没在噪声里没人看。见 PITFALLS §4.41。
            if re.match(r'^internal (?:suspend )?fun %s\.' % re.escape(owner), s):
                continue
            # 多行签名的续行（`) {` / `): List<X> {` / `}` 收尾）不算"声明"：
            # 它们必然以括号/花括号开头，没有标识符开头。
            if re.match(r'^[)}]', s):
                continue
            bad.append((i, s))
        if bad:
            print('  ⚠️ %d 行"顶层但不是扩展函数"——逐行确认是不是被误搬的字段/常量：' % len(bad))
            for i, s in bad:
                print('       %5d| %s' % (i, s[:120]))
        else:
            print('  自检：没有误搬的顶层声明')

    killed = set()
    for a, b in all_zones:
        for i in range(a, b + 1):
            killed.add(i)
    rest = [l for i, l in enumerate(lines, 1) if i not in killed]
    io.open(path, 'w', encoding='utf-8', newline='').write('\n'.join(rest))
    print('✓ %s：%d → %d 行' % (target, len(lines), len(rest)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
