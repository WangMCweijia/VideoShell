#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 Bs4.java：v1.0.12 回归 —— 分集名去剧名前缀 + 校准模式纯逻辑（全离线）。"""
import os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp  # noqa: E402

JAVAC = _cp.javac()
JAVA = _cp.java()
SAMPLES = _cp.samples('_bs')

cp = _cp.classpath()
out = os.path.join(HERE, '_bs4')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn', '-cp', cp, '-d', out,
                    os.path.join(HERE, 'Chains.java'),
                    os.path.join(HERE, 'Bs4.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp, 'Bs4', SAMPLES] + sys.argv[1:],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr.strip() else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_bs4.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
