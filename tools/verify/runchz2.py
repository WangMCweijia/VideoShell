#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 Cz2.java：用 App 同一套 OkHttp 头复核厂长资源是否需要登录才能播。"""
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
    'com.squareup.okhttp3/okhttp/4.12.0/*/okhttp-4.12.0.jar',
    'com.squareup.okio/okio-jvm/3.6.0/*/okio-jvm-3.6.0.jar',
    'org.jsoup/jsoup/1.17.1/*/jsoup-1.17.1.jar',
]

libs = []
for pat in LIBS:
    h = glob.glob(os.path.join(CACHE, pat))
    if not h:
        raise SystemExit('缺 jar: ' + pat)
    libs.append(h[0])
cp = os.pathsep.join(libs)
out = os.path.join(HERE, '_cz2')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn', '-cp', cp, '-d', out,
                    os.path.join(HERE, 'Cz2.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp, 'Cz2'] + sys.argv[1:],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(out, '_cz2.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
