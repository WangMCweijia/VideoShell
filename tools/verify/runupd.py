#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 Upd.java：v1.0.55 应用内自更新的**可达性**源码守卫（纯离线）。

入参：PROJ = 工程根（U 段读 UpdateChecker.kt / UpdateDownloader.kt）。

这个套件**不联网、也不要求先 build** —— 它只读源码文本，锁的是"两条通道还在不在、
取资产内容有没有带那个 Accept、白名单有没有被改宽"。所以它不该因为网络抖动变红。
"""
import os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp  # noqa: E402
PROJ = _cp.project_root()

JAVAC = _cp.javac()
JAVA = _cp.java()

out = os.path.join(HERE, '_upd_out')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn', '-d', out,
                    os.path.join(HERE, 'Upd.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out, 'Upd', HERE, PROJ],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_upd.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
