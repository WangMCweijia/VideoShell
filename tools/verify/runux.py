#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
跑 EpOrd + Ins 两个小套件（剧集顺序 / 覆盖层方位规则），并做一条**源码守卫**。

为什么带源码守卫：`PlayerInsets.pad` 只管算，顶栏不再被挤扁还依赖布局文件把它写成
`wrap_content + minHeight`（高度写死 + 补 padding = 内容被压扁，就是 v1.0.26 的事故）。
这类"跨文件才成立"的约束只能靠读源码来守，跟 runzqfix 守 Http.kt 的 Accept 是同一个套路。
"""
import os, re, subprocess, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp  # noqa: E402
ROOT = _cp.project_root()
JAVAC = _cp.javac()
JAVA = _cp.java()

cp = _cp.classpath()
out = os.path.join(HERE, '_ux_out')
os.makedirs(out, exist_ok=True)

srcs = [os.path.join(HERE, f) for f in ('EpOrd.java', 'Ins.java')]
r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-cp', cp, '-d', out] + srcs,
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

buf = []
extra_fail = 0
for cls in ('EpOrd', 'Ins'):
    r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp, cls],
                       capture_output=True, text=True, encoding='utf-8', errors='replace')
    buf.append((r.stdout or '') + (r.stderr or ''))
    extra_fail += (r.stdout or '').count('[FAIL]')

# ---- 源码守卫：顶栏必须能被 padding 撑高，而不是被压扁 ----
xml = open(os.path.join(ROOT, 'app', 'src', 'main', 'res', 'layout', 'activity_player.xml'),
           encoding='utf-8').read()
m = re.search(r'<LinearLayout\s+android:id="@\+id/topBar".*?>', xml, re.S)
guard = []
if not m:
    guard.append(('能找到 topBar 节点', False))
else:
    node = m.group(0)
    guard.append(('topBar 高度是 wrap_content（不许写死，否则补 padding 会挤内容）',
                  'android:layout_height="wrap_content"' in node))
    guard.append(('topBar 有 minHeight（补 0 的机型也保持原高度）',
                  'android:minHeight=' in node))
for what, ok in guard:
    print(('[PASS] ' if ok else '[FAIL] ') + '守卫：' + what)
    if not ok:
        extra_fail += 1
print()
for t in buf:
    sys.stdout.write(t)
print('==== runux  PASS=%d FAIL=%d ====' % (sum(t.count('[PASS]') for t in buf) + sum(1 for _, o in guard if o),
                                            sum(t.count('[FAIL]') for t in buf) + extra_fail))
open(os.path.join(HERE, '_ux.out.txt'), 'w', encoding='utf-8').write(
    '\n'.join(buf) + '\n' + str(guard))
raise SystemExit(1 if extra_fail else 0)
