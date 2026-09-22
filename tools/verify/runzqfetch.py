#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
抓 zqkhmy 的真实详情页落盘到 `_zq/zq_detail_<id>.html`（分集列表取证用）。

必须用 OkHttp（ZqFetch.java）—— 该站 WAF 挑请求姿态，urllib 会被挡。
用法： python runzqfetch.py [id ...]
"""
import glob, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
import sys as _sys
_sys.path.insert(0, HERE)
import _cp  # noqa: E402

CACHE = _cp.gradle_cache()
JAVAC = _cp.javac()
JAVA = _cp.java()
LIBS = [
    'org.jetbrains.kotlin/kotlin-stdlib/1.9.22/*/kotlin-stdlib-1.9.22.jar',
    'com.squareup.okhttp3/okhttp/4.12.0/*/okhttp-4.12.0.jar',
    'com.squareup.okio/okio-jvm/3.6.0/*/okio-jvm-3.6.0.jar',
]
libs = []
for pat in LIBS:
    h = glob.glob(os.path.join(CACHE, pat))
    if not h:
        raise SystemExit('缺 jar: ' + pat)
    libs.append(h[0])
cp = os.pathsep.join(libs)
out = os.path.join(HERE, '_zqbuild')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-cp', cp, '-d', out,
                    os.path.join(HERE, 'ZqFetch.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp, 'ZqFetch']
                   + sys.argv[1:],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
sys.stdout.write((r.stdout or '') + (r.stderr or ''))
sys.exit(r.returncode)
