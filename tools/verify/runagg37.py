#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 Agg37.java：v1.0.37「搜索范围（本站/全站/全网）+ 网页嗅探模式识别与校准」的断言。

A 搜索范围契约 / B 全网地址编码 / C 聚合 merge / D 聚合 summary /
E WebSiteKit 纯函数 / F VideoItem.siteKey / G 源码守卫。
全离线，不需要网络。

入参：HERE = 夹具目录  PROJ = 工程根（G 段读源码）。
"""
import os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp  # noqa: E402
PROJ = _cp.project_root()

JAVAC = _cp.javac()
JAVA = _cp.java()

cp = _cp.classpath()
out = os.path.join(HERE, '_agg37_out')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn',
                    '-J-Duser.language=en', '-J-Duser.country=US',
                    '-cp', cp, '-d', out, os.path.join(HERE, 'Agg37.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    txt = '== javac failed ==\n' + (r.stdout or '') + (r.stderr or '')
    sys.stdout.write(txt)
    open(os.path.join(HERE, '_agg37.out.txt'), 'w', encoding='utf-8').write(txt)
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp, 'Agg37', HERE, PROJ],
                   capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=300)
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_agg37.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
