#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 CalibMerge：校准「跳过某一步」的归并语义 + 源码守卫（v1.0.29）。

不联网。依赖已编译的 debug 产物：python tools/build.py :app:assembleDebug
"""
import os, subprocess, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp  # noqa: E402
ROOT = _cp.project_root()
JAVAC = _cp.javac()
JAVA = _cp.java()

cp = _cp.classpath()
out = os.path.join(HERE, '_calibmerge_out')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-cp', cp, '-d', out,
                    os.path.join(HERE, 'CalibMerge.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp,
                    'CalibMerge', ROOT],
                   capture_output=True, text=True, encoding='utf-8', errors='replace', cwd=HERE)
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_calibmerge.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
