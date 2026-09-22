#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 YgoItem.java：野果搜索相关性取证（matched_fields / 命中来源）。"""
import os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
import sys as _sys
_sys.path.insert(0, HERE)
import _cp  # noqa: E402

sys.path.insert(0, HERE)
from _cp import classpath

JAVAC = _cp.javac()
JAVA = _cp.java()

cp = classpath()
out = os.path.join(HERE, '_ygoitem_out')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-cp', cp, '-d', out,
                    os.path.join(HERE, 'YgoItem.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-Dsun.stdout.encoding=UTF-8',
                    '-cp', out + os.pathsep + cp, 'YgoItem'],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_ygoitem.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
