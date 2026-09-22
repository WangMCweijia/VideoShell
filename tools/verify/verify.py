#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
视频壳 —— HTML 适配离线校验。

做什么：
  把抓下来的真实页面当输入，直接调用**刚编译出的 Kotlin 类**
  （app/build/tmp/kotlin-classes/debug）跑分类 / 列表 / 选集 / 播放地址抽取，
  不需要真机、不需要网络。改了 HtmlAdapter / HtmlExtractor / HtmlTemplates 之后先跑它。

用法：
  # 1) 先编译一次（生成 class）
  cd D:/TRAE/视频壳 && python tools/build.py :app:assembleDebug
  # 2) 校验
  python verify.py                       # 默认工程目录 D:/TRAE/视频壳
  python verify.py D:/TRAE/视频壳
  python verify.py --refresh             # 重新抓样本（需要能访问目标站）

样本：samples/*.html，来自 https://www.cupfoxyy.com（maccms + shoutu38 主题）。
退出码 0 = ALL CHECKS PASSED。
"""
import glob
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
import sys as _sys
_sys.path.insert(0, HERE)
import _cp  # noqa: E402

SAMPLES = os.path.join(HERE, 'samples')
BASE = 'https://www.cupfoxyy.com'
UA = 'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36'

JAVAC = _cp.javac()
JAVA = _cp.java()
GRADLE_CACHE = _cp.gradle_cache()

LIBS = [
    'org.jetbrains.kotlin/kotlin-stdlib/1.9.22/*/kotlin-stdlib-1.9.22.jar',
    'org.jsoup/jsoup/1.17.1/*/jsoup-1.17.1.jar',
    'com.squareup.okhttp3/okhttp/4.12.0/*/okhttp-4.12.0.jar',
    'com.squareup.okio/okio-jvm/3.6.0/*/okio-jvm-3.6.0.jar',
]

# 样本名 -> 线上地址（--refresh 用）
REFRESH = {
    '_site_home.html': BASE + '/',
    '_p85221f.html': BASE + '/vod/1.html',
    '_p11061a.html': BASE + '/detail/127825.html',
    '_series.html': BASE + '/detail/125679.html',
    '_search.html': BASE + '/vodsearch/wd/%E7%BA%AA%E5%BD%95.html',
    '_play.html': BASE + '/play/127825-1-1.html',
}


def find_libs():
    out = []
    for pat in LIBS:
        hits = glob.glob(os.path.join(GRADLE_CACHE, pat))
        if not hits:
            raise SystemExit('找不到依赖 jar: %s' % pat)
        out.append(hits[0])
    return out


def refresh_samples():
    import urllib.request
    for name, url in REFRESH.items():
        try:
            req = urllib.request.Request(url, headers={'User-Agent': UA})
            with urllib.request.urlopen(req, timeout=25) as r:
                data = r.read()
            with open(os.path.join(SAMPLES, name), 'wb') as f:
                f.write(data)
            print('  refreshed %-20s %7d bytes' % (name, len(data)))
        except Exception as e:
            print('  ! 刷新失败 %s: %s' % (name, e))


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    root = args[0] if args else _cp.project_root()
    root = os.path.abspath(root)

    if '--refresh' in sys.argv:
        print('== 重新抓取样本 ==')
        refresh_samples()

    classes = os.path.join(root, 'app', 'build', 'tmp', 'kotlin-classes', 'debug')
    if not os.path.isdir(classes):
        raise SystemExit('没找到 %s\n先跑: cd %s && python tools/build.py :app:assembleDebug' % (classes, root))

    cp = os.pathsep.join([classes] + find_libs())
    build_dir = os.path.join(HERE, '_vh')
    os.makedirs(build_dir, exist_ok=True)

    src = os.path.join(HERE, 'Verify.java')
    r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-cp', cp, '-d', build_dir, src],
                       capture_output=True)
    if r.returncode != 0:
        sys.stdout.write(r.stdout.decode('utf-8', 'replace'))
        sys.stderr.write(r.stderr.decode('utf-8', 'replace'))
        raise SystemExit('javac 失败')

    r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', build_dir + os.pathsep + cp,
                        'Verify', SAMPLES], capture_output=True)
    sys.stdout.write(r.stdout.decode('utf-8', 'replace'))
    err = r.stderr.decode('utf-8', 'replace').strip()
    if err:
        sys.stdout.write(err + '\n')
    return r.returncode


if __name__ == '__main__':
    sys.exit(main())
