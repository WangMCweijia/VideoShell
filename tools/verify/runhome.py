#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 HomeCover.java：v1.0.18 的「首页（最新 tab）封面/剧名」离线校验。

样本取自 `_yg/*.html`（野果短剧真页面存档），不联网。
"""
import os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp  # noqa: E402

JAVAC = _cp.javac()
JAVA = _cp.java()
SAMPLES = os.path.join(HERE, '_yg')

cp = _cp.classpath()
out = os.path.join(HERE, '_homecover')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn', '-cp', cp, '-d', out,
                    os.path.join(HERE, 'HomeCover.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    print('[FAIL] javac 编译 HomeCover 失败')
    raise SystemExit(1)

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp,
                    'HomeCover', SAMPLES],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
txt = (r.stdout or '') + ('\n[stderr]\n' + r.stderr if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_homecover.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
