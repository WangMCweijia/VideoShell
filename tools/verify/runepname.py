#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 EpName.java：v1.0.36「野果分集名全是剧名」的断言
（A 集号判据 / B 整列同源 / C 真实夹具 / D 排序 / E 判据只有一份 / F 真网络端到端）。

入参：HERE = 夹具目录  PROJ = 工程根（E 段读源码）。
A~E 全离线且确定；F 段需要真网络（抓不到会打印 SKIP，不影响 A~E）。
"""
import os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp  # noqa: E402
PROJ = _cp.project_root()

JAVAC = _cp.javac()
JAVA = _cp.java()

cp = _cp.classpath()
out = os.path.join(HERE, '_epname_out')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn',
                    '-J-Duser.language=en', '-J-Duser.country=US',
                    '-cp', cp, '-d', out, os.path.join(HERE, 'EpName.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp, 'EpName', HERE, PROJ],
                   capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=300)
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_epname.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
