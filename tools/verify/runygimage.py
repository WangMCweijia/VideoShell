#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 YgImage.java：v1.0.32 野果加密图床断言（A/B/C 纯逻辑 + E 源码守卫 + F 真网络端到端）。

入参：HERE = 夹具目录（_ygmedia/ 在这里）  PROJ = 工程根（E 段要读源码）。
F 段拿不到网络时打印 [SKIP] 而不是 FAIL —— 离线跑 runall 不该因为断网变红。
"""
import os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp  # noqa: E402
PROJ = _cp.project_root()

JAVAC = _cp.javac()
JAVA = _cp.java()

cp = _cp.classpath()
out = os.path.join(HERE, '_ygimg_out')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn', '-cp', cp, '-d', out,
                    os.path.join(HERE, 'YgImage.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp, 'YgImage', HERE, PROJ],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_ygimage.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
