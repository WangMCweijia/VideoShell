# -*- coding: utf-8 -*-
"""生成应用内自更新用的 `version.json`（CI 在打 tag 发版时跑）。

## 这份清单是给谁看的

App 里的 `UpdateChecker` 每启动一次去读 `.../releases/latest/download/version.json`
（latest 永远指向最新正式 Release，所以 App 侧不用跟版本号）。清单里同时带上
**带版本号的 APK 直链**与 **sha256**，两者必须同源 —— 见下面"两条纪律"。

## 两条纪律（不是形式主义）

1. **版本号必须从 APK 里读，不能从源码读。**
   源码里的 `versionCode` 只说明"打算出什么"，而发出去的包才是事实。
   这两者分叉过一次（v1.0.53 有过四个同名包、分两代，见 PITFALLS §4.38），
   从那时起这条就定死了：**优先 aapt dump badging 读 APK**，读不到才退到合并后的
   Manifest，最后才退到 `app/build.gradle`。退到哪一级都要打出来。

2. **sha256 必须来自刚落盘的那个 APK 字节**，且 apkUrl 的 tag 与本次 tag 相同。
   清单说 1.0.55、链接指向 v1.0.54 的包，是这类机制最典型的故障 ——
   而它**不会报错**，只会让所有人装了个旧版还以为更新成功了。

用法（CI）：
    python tools/ci/make_version_json.py \
        --apk app/build/outputs/apk/release/app-release.apk \
        --tag v1.0.55 --repo owner/name --notes "..." \
        --out app/build/outputs/apk/release/version.json
"""
import argparse
import glob
import hashlib
import io
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def find_aapt():
    """CI（ubuntu-latest）带 Android SDK，build-tools 下有 aapt。找不到就返回 None。"""
    roots = [os.environ.get('ANDROID_HOME'), os.environ.get('ANDROID_SDK_ROOT')]
    for r in roots:
        if not r:
            continue
        cands = sorted(glob.glob(os.path.join(r, 'build-tools', '*', 'aapt')))
        cands += sorted(glob.glob(os.path.join(r, 'build-tools', '*', 'aapt.exe')))
        if cands:
            return cands[-1]  # 版本号最大的那个
    return None


def from_aapt(apk):
    aapt = find_aapt()
    if not aapt:
        return None, 'aapt 不可用'
    try:
        out = subprocess.run([aapt, 'dump', 'badging', apk],
                             capture_output=True, text=True, timeout=120)
    except Exception as e:
        return None, 'aapt 执行失败：%s' % e
    if out.returncode != 0:
        return None, 'aapt 返回 %d' % out.returncode
    m = re.search(r"versionCode='(\d+)'\s+versionName='([^']*)'", out.stdout)
    if not m:
        return None, 'aapt 输出里没有 versionCode/versionName'
    return (int(m.group(1)), m.group(2)), 'aapt dump badging（APK 里的事实）'


def from_manifest():
    for rel in ('app/build/intermediates/packaged_manifests/release/AndroidManifest.xml',
                'app/build/intermediates/packaged_manifests/release/processReleaseManifest/AndroidManifest.xml',
                'app/build/intermediates/merged_manifests/release/AndroidManifest.xml'):
        p = os.path.join(ROOT, rel)
        if not os.path.exists(p):
            continue
        t = io.open(p, encoding='utf-8', errors='ignore').read()
        c = re.search(r'android:versionCode="(\d+)"', t)
        n = re.search(r'android:versionName="([^"]*)"', t)
        if c and n:
            return (int(c.group(1)), n.group(1)), '合并后的 Manifest（%s）' % rel
    return None, '找不到合并 Manifest'


def from_gradle():
    p = os.path.join(ROOT, 'app', 'build.gradle')
    t = io.open(p, encoding='utf-8', errors='ignore').read()
    c = re.search(r'versionCode\s+(\d+)', t)
    n = re.search(r'versionName\s+"([^"]*)"', t)
    if c and n:
        return (int(c.group(1)), n.group(1)), 'app/build.gradle（**兜底**：源码说的，不是包里的）'
    return None, 'app/build.gradle 里读不到'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--apk', required=True, help='release APK 路径')
    ap.add_argument('--tag', required=True, help='本次 tag，如 v1.0.55')
    ap.add_argument('--repo', required=True, help='owner/name')
    ap.add_argument('--notes', default='', help='更新说明（一般取本次提交标题）')
    ap.add_argument('--out', required=True, help='输出路径')
    a = ap.parse_args()

    if not os.path.exists(a.apk):
        print('✗ APK 不存在：%s' % a.apk)
        return 1

    ver, how = from_aapt(a.apk)
    if ver is None:
        print('· aapt 读不到（%s），退到合并 Manifest' % how)
        ver, how = from_manifest()
    if ver is None:
        print('· 合并 Manifest 也读不到，退到 build.gradle')
        ver, how = from_gradle()
    if ver is None:
        print('✗ 三级兜底都读不到版本号：%s' % how)
        return 1

    code, name = ver

    # tag 与版本名必须一致。不一致说明"打了 v1.0.55 的 tag，包里却是 1.0.54"——
    # 这种清单一旦发出去，App 会永远认为"已是最新"（versionCode 没涨），
    # 而用户看到 Release 明明更新了。宁可这一步红。
    tagver = a.tag[1:] if a.tag.startswith('v') else a.tag
    if tagver != name:
        print('✗ tag「%s」与包里的 versionName「%s」不一致 —— 拒绝生成清单' % (a.tag, name))
        return 1

    apk_name = os.path.basename(a.apk)
    digest = sha256_of(a.apk)
    size = os.path.getsize(a.apk)

    manifest = {
        'versionCode': code,
        'versionName': name,
        'apkUrl': 'https://github.com/%s/releases/download/%s/%s' % (a.repo, a.tag, apk_name),
        'apkName': apk_name,
        'sha256': digest,
        'size': size,
        'tag': a.tag,
        'notes': a.notes.strip(),
    }

    os.makedirs(os.path.dirname(os.path.abspath(a.out)) or '.', exist_ok=True)
    with io.open(a.out, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write('\n')

    print('✓ 版本号来源：%s' % how)
    print('  versionCode=%d  versionName=%s' % (code, name))
    print('  sha256=%s…  size=%d' % (digest[:16], size))
    print('  apkUrl=%s' % manifest['apkUrl'])
    print('  写出 %s' % a.out)
    return 0


if __name__ == '__main__':
    sys.exit(main())
