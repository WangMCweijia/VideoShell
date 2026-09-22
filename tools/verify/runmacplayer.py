#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 MacPlayerTest：maccms 第三方解析源（player_list / encrypt）离线断言（v1.0.29）。

A 段用真实播放页夹具 `_play/p1_line8.html` 等（由 runplayfetch.py 抓）；
C/D/F 段为合成形状，不需要网络。

依赖已编译的 debug 产物：python _build.py :app:assembleDebug
"""
import os, subprocess, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp  # noqa: E402
JAVAC = _cp.javac()
JAVA = _cp.java()

cp = _cp.classpath()
out = os.path.join(HERE, '_macp_out')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-cp', cp, '-d', out,
                    os.path.join(HERE, 'MacPlayerTest.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp,
                    'MacPlayerTest', os.path.join(HERE, '_play')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace', cwd=HERE)
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_macp.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
