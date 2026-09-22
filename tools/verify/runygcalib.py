#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 YgCalib.java：v1.0.33 「校准走完了却按原规则显示」断言（A 数量自证 / C 探针不成立 / D 拒收语义 / E 源码守卫）。

入参：HERE = 夹具目录（_shell/yg_home.html 在这里）  PROJ = 工程根（E 段要读源码）。
全部断言都是离线的（夹具 + 源码），所以不需要 SKIP 分支 —— 结果必须确定。
"""
import os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp  # noqa: E402
PROJ = _cp.project_root()

JAVAC = _cp.javac()
JAVA = _cp.java()

cp = _cp.classpath()
out = os.path.join(HERE, '_ygcalib_out')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn', '-cp', cp, '-d', out,
                    os.path.join(HERE, 'YgCalib.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp, 'YgCalib', HERE, PROJ],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_ygcalib.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
