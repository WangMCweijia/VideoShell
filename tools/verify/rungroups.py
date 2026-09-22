#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 Groups.java：v1.0.18 的「播放源线路分组」离线校验（纯合成 HTML，不联网）。"""
import os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp  # noqa: E402

JAVAC = _cp.javac()
JAVA = _cp.java()

cp = _cp.classpath()
out = os.path.join(HERE, '_groups')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn', '-cp', cp, '-d', out,
                    os.path.join(HERE, 'Groups.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    print('[FAIL] javac 编译 Groups 失败')
    raise SystemExit(1)

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp, 'Groups'],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
txt = (r.stdout or '') + ('\n[stderr]\n' + r.stderr if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_groups.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
