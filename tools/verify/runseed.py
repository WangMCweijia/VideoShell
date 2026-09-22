#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 Seed.java：v1.0.53「签名种子配置族」判据断言（纯离线，不打网络）。

对的是**真编译产物**：SeedConfig 刻意不 import 任何 Android 类（base64 自己写），
所以它能在普通 JVM 里跑，断言直接打在 App 用的那一份代码上。

需要 gson（SeedConfig 用它解信封）—— 与 adb 那条 harness 的唯一差别就是多一个 jar。
"""
import glob, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
import sys as _sys
_sys.path.insert(0, HERE)
import _cp  # noqa: E402

ROOT = _cp.project_root()
JAVAC = _cp.javac()
JAVA = _cp.java()
CACHE = _cp.gradle_cache()
LIBS = [
    'org.jetbrains.kotlin/kotlin-stdlib/1.9.22/*/kotlin-stdlib-1.9.22.jar',
    'com.google.code.gson/gson/2.10.1/*/gson-2.10.1.jar',
]

classes = os.path.join(ROOT, 'app', 'build', 'tmp', 'kotlin-classes', 'debug')
if not os.path.isdir(classes):
    raise SystemExit('先编译：python tools/build.py :app:assembleDebug')

libs = []
for pat in LIBS:
    h = glob.glob(os.path.join(CACHE, pat))
    if not h:
        raise SystemExit('缺 jar: ' + pat)
    libs.append(h[0])
cp = os.pathsep.join([classes] + libs)
out = os.path.join(HERE, '_seedcls')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn', '-cp', cp, '-d', out,
                    os.path.join(HERE, 'Seed.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp, 'Seed'],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_seed.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
