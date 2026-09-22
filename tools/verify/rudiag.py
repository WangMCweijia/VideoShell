#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 Diag.java：用编译产物 + 现场样本复现分类/分集/播放地址问题。"""
import glob, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
import sys as _sys
_sys.path.insert(0, HERE)
import _cp  # noqa: E402

SAMPLES = _cp.samples('_s3')
ROOT = _cp.project_root()

JAVAC = _cp.javac()
JAVA = _cp.java()
GRADLE_CACHE = _cp.gradle_cache()

LIBS = [
    'org.jetbrains.kotlin/kotlin-stdlib/1.9.22/*/kotlin-stdlib-1.9.22.jar',
    'org.jsoup/jsoup/1.17.1/*/jsoup-1.17.1.jar',
    'com.squareup.okhttp3/okhttp/4.12.0/*/okhttp-4.12.0.jar',
    'com.squareup.okio/okio-jvm/3.6.0/*/okio-jvm-3.6.0.jar',
]

classes = os.path.join(ROOT, 'app', 'build', 'tmp', 'kotlin-classes', 'debug')
libs = []
for pat in LIBS:
    hits = glob.glob(os.path.join(GRADLE_CACHE, pat))
    if not hits:
        raise SystemExit('缺 jar: ' + pat)
    libs.append(hits[0])

cp = os.pathsep.join([classes] + libs)
out = os.path.join(HERE, '_vh2')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-cp', cp, '-d', out,
                    os.path.join(HERE, 'Diag.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write(r.stdout or '')
    sys.stderr.write(r.stderr or '')
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp,
                    'Diag', SAMPLES],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
open(os.path.join(HERE, '_diag.out.txt'), 'w', encoding='utf-8').write(
    (r.stdout or '') + '\n[stderr]\n' + (r.stderr or ''))
print('exit', r.returncode)
