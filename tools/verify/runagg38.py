#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 Agg38.java：v1.0.38 断言。

A 搜索引擎枚举（认名字不认下标 + 域名归属）/ B 关键词后缀边界 /
C AggSearch.block 与 merge 同源 / D ★流式插入位置（任意到达顺序都等于全量 merge）/
E mergeArrived / progress / F ★HlsFix（带参数地址 / 静态整集 ENDLIST / 协议相对）/
G 源码守卫（含"必须真的被调用"）。
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
out = os.path.join(HERE, '_agg38_out')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn',
                    '-J-Duser.language=en', '-J-Duser.country=US',
                    '-cp', cp, '-d', out, os.path.join(HERE, 'Agg38.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    txt = '== javac failed ==\n' + (r.stdout or '') + (r.stderr or '')
    sys.stdout.write(txt)
    open(os.path.join(HERE, '_agg38.out.txt'), 'w', encoding='utf-8').write(txt)
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp, 'Agg38', HERE, PROJ],
                   capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=300)
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_agg38.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
