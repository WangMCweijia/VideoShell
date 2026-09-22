#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
跑 ZqLines：**多条播放源被合成一条线路**的离线回归（v1.0.28）。

- A 段：真实页面夹具 `_zq/zq_detail_*.html`（先用 runzqfetch.py 抓）
- B/C 段：合成形状，无需网络

依赖已编译的 debug 产物：python tools/build.py :app:assembleDebug
"""
import os, subprocess, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp  # noqa: E402
JAVAC = _cp.javac()
JAVA = _cp.java()

cp = _cp.classpath()
out = os.path.join(HERE, '_zqlines_out')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-cp', cp, '-d', out,
                    os.path.join(HERE, 'ZqLines.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp, 'ZqLines'],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_zqlines.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
