#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 PanBaiduTest.java：百度网盘 Provider 的**离线**回归（纯函数 + 源码级守卫，无需网络）。

百度那一半"必须问真网络"的问题（Cookie 够不够、dlink 对 UA/Referer/Range 多严）不在这里，
在 `tools/verify/panbaidu_spike.py`；本套件只守**纯函数**与**接线**——它们各自都有一个
真机上表现为"点了没反应"的失败方式，见 PanBaiduTest 的类文档。

真跑 `PanBaidu.shareFields/shareIsDead/useRoot/errnoOf/dlinkOf`，classpath 走 `_cp.classpath()`。
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
out = os.path.join(HERE, '_pbd')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn', '-cp', cp, '-d', out,
                    os.path.join(HERE, 'PanBaiduTest.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-Dvs.root=' + ROOT,
                    '-cp', out + os.pathsep + cp, 'tools.verify.PanBaiduTest'],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_pbd.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
