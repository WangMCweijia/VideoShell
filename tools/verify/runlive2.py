#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 Live2.java：v1.0.4 真实 suspend 链路回归（含 SiteDoctor / NetLog / CookieJar / AdapterFactory）。"""
import os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp  # noqa: E402  —— 统一的 classpath（含 media3/android.jar，SiteDoctor 会用到）

ROOT = _cp.project_root()
OUT = os.path.join(ROOT, '_s4')
JAVAC = _cp.javac()
JAVA = _cp.java()

cp = _cp.classpath()
out = os.path.join(HERE, '_live2')
os.makedirs(out, exist_ok=True)
os.makedirs(OUT, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn', '-cp', cp, '-d', out,
                    os.path.join(HERE, 'Chains.java'),
                    os.path.join(HERE, 'Live2.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp, 'Live2', OUT],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_live2.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
