#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
核对两个 APK 的**签名证书是不是同一把钥匙**，以及 versionName。

为什么需要它（2026-09-17 真踩过，代价是一整轮误判）：
    `app/build.gradle` 里只给 release 配了签名，debug 走 Android **默认调试密钥**。
    两个包**互相覆盖安装会失败**（「应用未安装：签名冲突」）。
    于是用户以为装了新版、实际还停在旧版 —— 现象是「我明明装了，怎么还是老样子」，
    而代码层怎么查都解释不通（因为真的是对的行为，只是包没换成）。

    ⇒ **每次发布前跑一遍**。两个包指纹必须一致，否则一定有一半的包装不上去。

用法：
    python runsign.py                       # 默认查 app/build/outputs 下的 debug + release
    python runsign.py a.apk b.apk           # 指定两个包
    python runsign.py a.apk --expect 1.0.13 # 顺带断言 versionName
"""
import os
import re
import subprocess
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _cp  # noqa: E402
APKSIGNER = _cp.apksigner()
ROOT = _cp.project_root()
DEFAULT_APKS = [
    os.path.join(ROOT, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'),
    os.path.join(ROOT, 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk'),
]

fails = []


def check(ok, what, detail=''):
    print(('  [PASS] ' if ok else '  [FAIL] ') + what + (('  ' + detail) if detail else ''))
    if not ok:
        fails.append(what)


def cert_digests(apk):
    """返回 (证书 SHA-256 列表, 已验证通过的签名方案, 原始输出)

    注意：必须带 `-v` —— 只给 `--print-certs` 时 apksigner **不打印签名方案**，
    而"v1/v2 是否真的验过"恰恰是"包到底有没有被签名"的关键。
    还要注意 `SHA-256 digest:` 会同时匹配到**证书**和**公钥**两条，这里只取证书那条。
    """
    r = subprocess.run([APKSIGNER, 'verify', '-v', '--print-certs', apk],
                       capture_output=True, text=True, encoding='utf-8', errors='replace')
    out = (r.stdout or '') + (r.stderr or '')
    digests = re.findall(r'certificate SHA-256 digest:\s*([0-9a-fA-F]{64})', out)
    schemes = sorted(set(re.findall(r'Verified using (v[\d.]+) scheme[^:]*:\s*true', out)))
    return digests, schemes, out


def version_names(apk):
    """从二进制 AndroidManifest 的字符串池里捞 versionName（UTF-16LE）"""
    with zipfile.ZipFile(apk) as z:
        mf = z.read('AndroidManifest.xml')
    out = set()
    for c in re.findall(rb'(?:[0-9]\x00|\.\x00){3,}', mf):
        s = c.decode('utf-16-le', 'ignore')
        if re.fullmatch(r'\d+(?:\.\d+)+', s):
            out.add(s)
    return out


def main():
    argv = sys.argv[1:]
    expect = None
    if '--expect' in argv:
        i = argv.index('--expect')
        expect = argv[i + 1] if i + 1 < len(argv) else None
        argv = argv[:i] + argv[i + 2:]      # 选项与它的值一起摘掉，别当成 APK 路径
    apks = [a for a in argv if not a.startswith('--')]
    if not apks:
        apks = DEFAULT_APKS

    print('=' * 72)
    print('APK 签名 / 版本核对')
    print('=' * 72)

    infos = []
    for apk in apks:
        name = os.path.basename(apk)
        print('\n' + name + '   (' + apk + ')')
        if not os.path.exists(apk):
            check(False, name + ' 存在')
            continue
        check(os.path.getsize(apk) > 0, name + ' 存在',
              str(os.path.getsize(apk)) + ' B')
        digests, schemes, raw = cert_digests(apk)
        if not digests:
            print(raw[:1200])
        check(len(digests) >= 1, name + ' 已签名（有签名证书）')
        if not schemes:
            check(False, name + ' 有已验证的签名方案')
        else:
            print('         签名方案：' + ', '.join(schemes))
        vers = version_names(apk)
        check(len(vers) >= 1, name + ' 能读到 versionName', '、'.join(sorted(vers)))
        if expect:
            check(expect in vers, name + ' versionName == ' + expect, '实际 ' + '、'.join(sorted(vers)))
        infos.append((name, digests))

    print('\n' + '-' * 72)
    if len(infos) >= 2:
        base_name, base = infos[0]
        for name, d in infos[1:]:
            check(d[:1] == base[:1],
                  base_name + ' 与 ' + name + ' 签名证书一致（否则互装会失败）',
                  '同一把钥匙' if d[:1] == base[:1] else '❌ 不是同一把：装不上对方')
    print('-' * 72)
    print('\n' + ('ALL CHECKS PASSED' if not fails else 'FAILED: ' + str(len(fails)) + ' 项'))
    return 0 if not fails else 1


if __name__ == '__main__':
    sys.exit(main())
