#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 PlayFetch：抓真实播放页 → 跑 Media.extractFromHtml → 统计抠链成功率。

用于定位「点某一集不能直接播、必须靠嗅探」的形态。（需要网络）
"""
import os, subprocess, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp  # noqa: E402
JAVAC = _cp.javac()
JAVA = _cp.java()

cp = _cp.classpath()
out = os.path.join(HERE, '_play_out')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-cp', cp, '-d', out,
                    os.path.join(HERE, 'PlayFetch.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp, 'PlayFetch'],
                   capture_output=True, text=True, encoding='utf-8', errors='replace', cwd=HERE)
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_playfetch.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
