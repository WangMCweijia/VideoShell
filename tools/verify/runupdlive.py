#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 UpdLive.java：应用内自更新的端到端可达性实测（**要真实网络**）。

对照实验：
  A（对照）直接用 Http.get 打 MANIFEST_URL（App 原来那条路）
  B（实验）UpdateChecker.check()（现在双通道）

只有 A 红、B 绿才能证明这次改的通道是**必要的**（见 CalibLive 同一条纪律）。
所以它**不进 SUITES**（要网络）、也**不进 NET_SUITES**（那里是"有断言"的套件，
这个是排查工具：只输出结论、不产生 PASS/FAIL）。已登记在 runall.py 头部。
"""
import os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp  # noqa: E402

JAVAC = _cp.javac()
JAVA = _cp.java()
cp = _cp.classpath()

out = os.path.join(HERE, '_updlive_out')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn', '-cp', cp, '-d', out,
                    os.path.join(HERE, 'UpdLive.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp, 'UpdLive'],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_updlive.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
