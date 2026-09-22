#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 ZqLive29.java：枫叶影视（zqkhmy）点某一集能否直接播的现场复核。

用法： python runzqlive29.py [baseUrl]
"""
import os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp  # noqa: E402

JAVAC = _cp.javac()
JAVA = _cp.java()

cp = _cp.classpath()
out = os.path.join(HERE, '_zqlive29')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn', '-cp', cp, '-d', out,
                    os.path.join(HERE, 'ZqLive29.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

cmd = [JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp, 'ZqLive29']
if len(sys.argv) > 1:
    cmd.insert(1, '-Dvs.base=' + sys.argv[1])
r = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace')
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_zqlive29.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
