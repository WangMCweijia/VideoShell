#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""GitHub 加速镜像**活性实测**（诊断工具，不产生 PASS/FAIL，不进 SUITES）。

    python tools/verify/runmirrorprobe.py

镜像清单**从 UpdateMirror.kt 里解析**（判据只有一份，不另抄）——
镜像来去很快，App 里有运行时探测兜底，但这份表本身腐化时要靠本工具发现。
对每个前缀：取 version.json（判通不通）→ 对 APK 发 Range 探测（判吞吐 +
是否真 ZIP，识破"200 + HTML"的说谎镜像）。输出可直接用来更新 MIRROR_PREFIXES。
"""
import concurrent.futures as cf
import os
import re
import ssl
import sys
import time
import urllib.request
import urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(os.path.dirname(HERE))
KT = os.path.join(PROJ, 'app', 'src', 'main', 'java', 'com', 'videoshell',
                  'data', 'net', 'UpdateMirror.kt')

REPO = 'WangMCweijia/VideoShell'
MANIFEST = f'https://github.com/{REPO}/releases/latest/download/version.json'

CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE


def load_prefixes():
    src = open(KT, encoding='utf-8').read()
    m = re.search(r'MIRROR_PREFIXES[^=]*=\s*listOf\(([^)]*)\)', src, re.S)
    if not m:
        raise SystemExit('从 UpdateMirror.kt 解析 MIRROR_PREFIXES 失败')
    return re.findall(r'"(https://[^"]+/)"', m.group(1))


def fetch(url, headers=None, timeout=10, maxbytes=65536):
    h = {'User-Agent': 'Mozilla/5.0', 'Accept': '*/*'}
    h.update(headers or {})
    req = urllib.request.Request(url, headers=h)
    op = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                     urllib.request.HTTPSHandler(context=CTX))
    t0 = time.time()
    try:
        with op.open(req, timeout=timeout) as r:
            head = r.read(4)
            rest = r.read(maxbytes - 4)
            return True, r.status, len(head) + len(rest), int((time.time() - t0) * 1000), head, ''
    except urllib.error.HTTPError as e:
        return False, e.code, 0, int((time.time() - t0) * 1000), b'', f'HTTP {e.code}'
    except Exception as e:
        return False, 0, 0, int((time.time() - t0) * 1000), b'', type(e).__name__


def probe(prefix):
    name = prefix if prefix else '(直连)'
    ok1, st1, n1, ms1, _, err1 = fetch((prefix or '') + MANIFEST, timeout=10)
    small = f"{'OK ' if ok1 else 'FAIL'} {st1 or '-':>4} {n1:>5}B {ms1:>6}ms {err1[:30]}"
    if not ok1:
        return name, small, '            --'
    # 找一个线上 APK 地址做 Range（用 latest 的 version.json 里给的，探测时先打 1MiB）
    try:
        body = fetch((prefix or '') + MANIFEST, timeout=10, maxbytes=65536)
    except Exception:
        body = (False, 0, 0, 0, b'', '')
    ok2, st2, n2, ms2, head, err2 = fetch(
        (prefix or '') + f'https://github.com/{REPO}/releases/download/v1.0.55/app-release.apk',
        headers={'Range': 'bytes=0-1048575'}, timeout=25, maxbytes=1048576)
    if ok2 and n2 > 0:
        zipped = head[:4] == b'PK\x03\x04'
        kbs = n2 / 1024 / max(ms2 / 1000, 0.001)
        big = f"{'OK ' if zipped else '说谎!'} {st2:>4} {n2:>7}B {ms2:>6}ms {kbs:>6.0f}KB/s" + \
              ('' if zipped else '（200 但不是 ZIP）')
    else:
        big = f'FAIL {st2 or "-":>4} {"-":>7} {ms2:>6}ms {err2[:34]}'
    return name, small, big


def main():
    prefixes = load_prefixes()
    print(f'从 UpdateMirror.kt 解析到 {len(prefixes)} 个镜像\n')
    print(f"{'镜像':<34} {'清单':<44} 大文件(Range 1MiB)")
    print('-' * 104)
    with cf.ThreadPoolExecutor(max_workers=10) as ex:
        futs = {ex.submit(probe, p): p for p in [''] + prefixes}
        out = [f.result() for f in cf.as_completed(futs)]
    out.sort(key=lambda r: (r[0] != '(直连)', r[1]))
    for name, small, big in out:
        print(f'{name:<34} {small:<44} {big}')
    print('\n★ 更新 MIRROR_PREFIXES 的依据：存活 + 真 ZIP + 吞吐降序。'
          '\n  「说谎!」= 返回 200 但不是 ZIP（实测 ghps.cc / gh-proxy.net 这类），别加回表里。')


if __name__ == '__main__':
    sys.exit(main())
