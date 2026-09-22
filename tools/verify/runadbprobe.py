#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 AdbProbe.java：对**真实页面**勘察去广告的覆盖缺口。

    python tools/verify/runadbprobe.py [页面地址]

不传页面就用默认那个（与 CalibLive 同一个站点，方便对照）。

⚠️ 这是**诊断工具**，不是断言套件：它只输出清单，不产生 PASS/FAIL。
所以它**不进** `runall.py` 的 SUITES（零断言会被标 WEAK），也**不进** NET_SUITES
（那一列是给"有断言但依赖真网络"的套件用的）。
文件头的「不在回归列表里的『工具』」一节里登记了它 —— 见 runall.py 顶部。
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp  # noqa: E402

ROOT = _cp.project_root()
JAVAC = _cp.javac()
JAVA = _cp.java()
cp = _cp.classpath()

classes = os.path.join(ROOT, 'app', 'build', 'tmp', 'kotlin-classes', 'debug')
if not os.path.isdir(classes):
    raise SystemExit('先编译：python tools/build.py :app:assembleDebug')

out = os.path.join(HERE, '_adbprobe')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn', '-cp', cp, '-d', out,
                    os.path.join(HERE, 'AdbProbe.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp,
                    'AdbProbe'] + sys.argv[1:],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr.strip() else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_adbprobe.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
