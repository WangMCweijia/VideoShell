#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 Pic.java：封面（海报）加载故障 —— 全离线，用真实产品代码 + 真实站点样本。"""
import os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp  # noqa: E402

JAVAC = _cp.javac()
JAVA = _cp.java()
SAMPLES = os.path.join(HERE, '_yg')

cp = _cp.classpath()
out = os.path.join(HERE, '_pic_out')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn', '-cp', cp, '-d', out,
                    os.path.join(HERE, 'Pic.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp, 'Pic', SAMPLES] + sys.argv[1:],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr.strip() else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_pic.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
