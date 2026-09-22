#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 Dom.java。用法：rundom.py <html> <href>..."""
import glob, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
import sys as _sys
_sys.path.insert(0, HERE)
import _cp  # noqa: E402

JAVAC = _cp.javac()
JAVA = _cp.java()
CACHE = _cp.gradle_cache()
LIBS = [
    'org.jetbrains.kotlin/kotlin-stdlib/1.9.22/*/kotlin-stdlib-1.9.22.jar',
    'org.jsoup/jsoup/1.17.1/*/jsoup-1.17.1.jar',
]
libs = []
for pat in LIBS:
    h = glob.glob(os.path.join(CACHE, pat))
    if not h:
        raise SystemExit('缺 jar: ' + pat)
    libs.append(h[0])
cp = os.pathsep.join(libs)
out = os.path.join(HERE, '_vh3')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-cp', cp, '-d', out,
                    os.path.join(HERE, 'Dom.java')], capture_output=True, text=True,
                   encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp, 'Dom'] + sys.argv[1:],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
txt = (r.stdout or '') + ('\n[stderr]\n' + r.stderr if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_dom.out.txt'), 'w', encoding='utf-8').write(txt)
