#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 Redir40.java：v1.0.40 断言。

A baseFor 判定表（拿不到最终地址要退回请求地址）/ B 真地址回放（分片必须落在最终目录）/
C 反向断言（两种基准结果必须不同，防 baseFor 退化成恒等）/ D 源码守卫。
全离线，不需要网络。

入参：HERE = 夹具目录  PROJ = 工程根（D 段读源码）。
"""
import os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp  # noqa: E402
PROJ = _cp.project_root()

JAVAC = _cp.javac()
JAVA = _cp.java()

cp = _cp.classpath()
out = os.path.join(HERE, '_redir40_out')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn',
                    '-J-Duser.language=en', '-J-Duser.country=US',
                    '-cp', cp, '-d', out, os.path.join(HERE, 'Redir40.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    txt = '== javac failed ==\n' + (r.stdout or '') + (r.stderr or '')
    sys.stdout.write(txt)
    open(os.path.join(HERE, '_redir40.out.txt'), 'w', encoding='utf-8').write(txt)
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp, 'Redir40', HERE, PROJ],
                   capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=300)
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_redir40.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
