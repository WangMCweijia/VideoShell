#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 Family.java：v1.0.35「三笔债 + 野果搜索」的断言
（A 密钥自动发现 / B API 基址候选 / C 配方常量 / D 选型与固化守卫 / E 端到端真网络）。

入参：HERE = 夹具目录（_shell/yg_config.js 在这里）  PROJ = 工程根（D 段读源码）。
A~D 全离线且确定；E 段需要真网络（抓不到会打印 SKIP，不影响 A~D 的结论）。
"""
import os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp  # noqa: E402
PROJ = _cp.project_root()

JAVAC = _cp.javac()
JAVA = _cp.java()

cp = _cp.classpath()
out = os.path.join(HERE, '_family_out')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn',
                    '-J-Duser.language=en', '-J-Duser.country=US',
                    '-cp', cp, '-d', out, os.path.join(HERE, 'Family.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp, 'Family', HERE, PROJ],
                   capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=300)
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_family.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
