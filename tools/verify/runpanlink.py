#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 PanLinkTest.java：v1.0.65「网盘分享站族 + 网盘层」的**离线**断言（不打网络）。

判据全在纯函数与真实样本上：

  A PanLink.parse 的 9 类分享链接 + 负例（自门控入口的唯一判据）
  B refOf → parseRef 往返（base64 令牌里的 `/` `+` `=` 必须原样回来）
  C PanShareExtract 打在真样本上（samples/panshare/{ky,wg}_detail.html，快映 / 玩偶）
  D PanResolver 的自然序（第100集 < 第10集 是选集乱序的唯一成因）
  E 接线 / 顺序 / 纪律的源码守卫（顺序、白名单、直链不落盘、NeedLogin 接线）

**不碰 PanCloudDrive 的行为** —— 它用 `org.json`，而离线 harness 挂的是 android.jar 的
桩，`new JSONObject(…)` 在 JVM 上直接抛 `Stub!`。那一层的判据只能落在源码上（E 段）。

入参：SAMPLES = 样本目录（`<repo>/tools/verify/samples/panshare`）  PROJ = 工程根。
"""
import os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp  # noqa: E402

PROJ = _cp.project_root()
SAMPLES = _cp.samples('panshare')

if not os.path.isdir(SAMPLES):
    raise SystemExit('缺样本目录：%s（要么补 samples/panshare/，要么这份断言会全红）' % SAMPLES)

JAVAC = _cp.javac()
JAVA = _cp.java()

cp = _cp.classpath()
out = os.path.join(HERE, '_panlink_out')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn',
                    '-J-Duser.language=en', '-J-Duser.country=US',
                    '-cp', cp, '-d', out, os.path.join(HERE, 'PanLinkTest.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp,
                    'PanLinkTest', SAMPLES, PROJ],
                   capture_output=True, text=True, encoding='utf-8', errors='replace',
                   timeout=300)
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_panlink.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(r.returncode)
