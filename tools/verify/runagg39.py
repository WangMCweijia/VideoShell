#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 Agg39.java：v1.0.39 断言。

A ★分组标题行（站名+条数 / 空块不出标题）/ B ★行视图与卡片视图同源 /
C ★PlayRetry 瞬时判据（402/429/5xx 重试，403/404 不重试，最坏代价有界）/
D 搜索展示不显示分类标签（一个入口）/ E ★卡住自愈（重新解析本集 + 计数不被自己清零）/
F 源码守卫（含"必须真的被调用"与"不能再有第二个写入点"）。
全离线，不需要网络。

入参：HERE = 夹具目录  PROJ = 工程根（E/F 段读源码）。
"""
import os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp  # noqa: E402
PROJ = _cp.project_root()

JAVAC = _cp.javac()
JAVA = _cp.java()

cp = _cp.classpath()
out = os.path.join(HERE, '_agg39_out')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn',
                    '-J-Duser.language=en', '-J-Duser.country=US',
                    '-cp', cp, '-d', out, os.path.join(HERE, 'Agg39.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    txt = '== javac failed ==\n' + (r.stdout or '') + (r.stderr or '')
    sys.stdout.write(txt)
    open(os.path.join(HERE, '_agg39.out.txt'), 'w', encoding='utf-8').write(txt)
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp, 'Agg39', HERE, PROJ],
                   capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=300)
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_agg39.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
