#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 PanDrift.java：网盘站源「跑路了/分类取不到/播放不了」的离线定性套件（无需网络）。

用真样本（samples/pandrift/）驱动本仓自己的解析代码：
HtmlTemplates 形状识别 → categoriesFrom → parseList → PanShareExtract。
对照组（玩偶老形式）混在里面，防止修好新形式弄坏老形式。
判据 = FAIL=0。
"""
import os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp  # noqa: E402

ROOT = _cp.project_root()
JAVAC = _cp.javac()
JAVA = _cp.java()

classes = os.path.join(ROOT, 'app', 'build', 'tmp', 'kotlin-classes', 'debug')
if not os.path.isdir(classes):
    raise SystemExit('先编译：python tools/build.py :app:assembleDebug')

cp = _cp.classpath()
out = os.path.join(HERE, '_pandrift')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn', '-cp', cp, '-d', out,
                    os.path.join(HERE, 'PanDrift.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8',
                    '-cp', out + os.pathsep + cp, 'PanDrift',
                    os.path.join(HERE, 'samples', 'pandrift')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_pandrift.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
