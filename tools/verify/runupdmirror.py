#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 UpdMirror.java：自更新高速镜像层守卫（行为断言 + 源码守卫，纯离线）。

行为断言要加载**真编译产物**（UpdateMirror），所以必须先：
    python tools/build.py :app:assembleDebug
"""
import os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp  # noqa: E402
PROJ = _cp.project_root()

JAVAC = _cp.javac()
JAVA = _cp.java()
CP = _cp.classpath()

classes = os.path.join(PROJ, 'app', 'build', 'tmp', 'kotlin-classes', 'debug')
if not os.path.isdir(classes):
    raise SystemExit('先编译：python tools/build.py :app:assembleDebug')

out = os.path.join(HERE, '_updmirror_out')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn', '-cp', CP, '-d', out,
                    os.path.join(HERE, 'UpdMirror.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp',
                    out + os.pathsep + classes + os.pathsep + CP,
                    'UpdMirror', classes, PROJ, CP],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_updmirror.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
