# -*- coding: utf-8 -*-
"""列出某个 .kt 里「类内成员」的边界：KDoc 起始行 / 声明行 / 结束行。

拆 god file 前先用它选出干净的切分区间 —— 尤其是**别把前置 KDoc 留在原地**
（搬走函数却留下注释，注释就变成了下一段代码的说明，读的人会被误导）。
用法：python tools/refactor/outline.py <文件名> [缩进阈值，默认 4]
"""
import io
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from repo_root import find  # noqa: E402  （必须先改 sys.path 才能 import 同目录模块）


def main():
    name = sys.argv[1]
    lvl = int(sys.argv[2]) if len(sys.argv) > 2 else 4
    pad = ' ' * lvl
    p = find(name)
    lines = io.open(p, encoding='utf-8').read().split('\n')

    decls = []
    for i, l in enumerate(lines):
        if l.startswith(pad) and not l.startswith(pad + ' ') \
                and re.match(r'^%s(private |internal |public |protected |override |suspend |operator )*'
                             r'(fun|val|var|const val|class|object|companion object)\b' % pad, l):
            decls.append(i)

    def dedent_ok(st):
        """KDoc/行注释块起始行"""
        j = st
        while j - 1 >= 0:
            prev = lines[j - 1].strip()
            if prev.endswith('*/') or prev.startswith('//') or prev.startswith('*'):
                j -= 1
            elif prev.startswith('/**') or prev.startswith('/*'):
                j -= 1
                break
            else:
                break
        return j

    def brace_end(st):
        depth = 0
        seen = False
        for k in range(st, len(lines)):
            for ch in lines[k]:
                if ch == '{':
                    depth += 1
                    seen = True
                elif ch == '}':
                    depth -= 1
                    if seen and depth == 0:
                        return k
        return len(lines) - 1

    print('=== %s（%d 行，缩进 %d）===' % (name, len(lines), lvl))
    for idx, s in enumerate(decls):
        m = re.search(r'(fun|val|var|class|object)\s+(\w+)', lines[s])
        nm = m.group(2) if m else '?'
        kd = dedent_ok(s)
        e = brace_end(s)
        nxt = decls[idx + 1] if idx + 1 < len(decls) else len(lines)
        print('%5d..%5d  KDoc@%5d  %-28s %s'
              % (s + 1, e + 1, kd + 1, nm, lines[s].strip()[:78]))
        print('        （到下一声明前 %d 行）' % nxt)


if __name__ == '__main__':
    main()
