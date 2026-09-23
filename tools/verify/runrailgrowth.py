#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 RailGrowthTest.java：聚合搜索左栏"随结果长出来"的离线回归（无需网络）。

真跑 `com.videoshell.data.site.RailGrowth`（判据里真的走 `AggSearch.block()`），
所以 classpath 要走 `_cp.classpath()`（含 kotlin-stdlib / R.jar）。
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
out = os.path.join(HERE, '_rg')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn', '-cp', cp, '-d', out,
                    os.path.join(HERE, 'RailGrowthTest.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-Dvs.root=' + ROOT,
                    '-cp', out + os.pathsep + cp, 'RailGrowthTest'],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_rg.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
