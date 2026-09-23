#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 MirrorRaceTest.java：「一个站多个网址，哪个快用哪个」的离线回归（无需网络）。

两层：A~C 是**真跑逻辑**（`race` 的探针是注入的 ⇒ 调度真的在跑，
"先到先赢"和"不等最慢的"是**按耗时**断言的，不是读源码猜）；
G 组读源码，锁接线顺序（那些改动编译也过、只是功能静默失效）。

classpath 要走 `_cp.classpath()`：判据里真的调 `com.videoshell.data.site.MirrorRace`
（引用了 Http/SiteConfig ⇒ 需要 kotlin-stdlib 等）。
`-Dvs.root=` 必须传 —— G 组靠它定位仓库根去读源码。
"""
import os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp  # noqa: E402

ROOT = _cp.project_root()
JAVAC = _cp.javac()
JAVA = _cp.java()

classes = os.path.join(ROOT, 'app', 'build', 'tmp', 'kotlin-classes', 'debug')
if not os.path.isdir(classes):
    raise SystemExit('先编译：python tools/build.py :app:assembleDebug')

cp = _cp.classpath()
out = os.path.join(HERE, '_mr')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn', '-cp', cp, '-d', out,
                    os.path.join(HERE, 'MirrorRaceTest.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-Dvs.root=' + ROOT,
                    '-cp', out + os.pathsep + cp, 'MirrorRaceTest'],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_mr.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
