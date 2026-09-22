# -*- coding: utf-8 -*-
"""视频壳 构建入口：用 Python 直接调 gradlew.bat，并把结果压成**可判读**的四行。

为什么要包一层（而不是直接 `./gradlew`）：
- 本机 Git Bash 的 coreutils 是坏的（`ls/dirname/tail` 全 127），shell 里读日志不可靠；
- gradle 的输出有几千行，人只看"哪一行是 `e: `"和"有没有 BUILD SUCCESSFUL"；
- PowerShell 会吞输出 ⇒ 统一**落盘再读**，结论只打印那四行。

输出契约（别的脚本/自动化可能按这四行解析，改动要同步）：
    EXIT <rc>
    KOTLIN_ERRORS <n>      # 以 `e: ` 开头的编译错误条数
    BUILD_OK True|False
    ---- tail ----         # 最后 40 行
日志固定落在工程根 `_build.log`（已被 `.gitignore` 的 `_*.log` 覆盖）。

用法：
    python tools/build.py                       # 默认 :app:assembleDebug
    python tools/build.py :app:assembleDebug :app:assembleRelease
    python tools/verify/runall.py               # 回归是另一个入口，不在这里
"""
import io
import os
import subprocess
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), 'refactor'))
from repo_root import ROOT  # noqa: E402  （sys.path 先改，才能 import 同工程模块）

LOG = os.path.join(ROOT, '_build.log')
TASKS = sys.argv[1:] or [':app:assembleDebug']

# JAVA_HOME 优先用环境里已有的；没有才去猜本机常见位置。
# **不要**只写死一个带版本号的路径：JDK 一升级，脚本会在"看起来很健康"的状态下构建失败。
JAVA_HOME = os.environ.get('JAVA_HOME', '')


def guess_java_home():
    import glob
    for pat in (r'C:\Program Files\Microsoft\jdk-*',
                r'C:\Program Files\Eclipse Adoptium\jdk-*',
                r'C:\Program Files\Java\jdk-*'):
        hits = sorted(glob.glob(pat))
        if hits:
            return hits[-1]
    return ''


if not JAVA_HOME:
    JAVA_HOME = guess_java_home()

env = dict(os.environ)
if JAVA_HOME:
    env['JAVA_HOME'] = JAVA_HOME
    env['PATH'] = os.path.join(JAVA_HOME, 'bin') + os.pathsep + env.get('PATH', '')

gradlew = os.path.join(ROOT, 'gradlew.bat')
if not os.path.exists(gradlew):
    raise SystemExit('✗ 找不到 %s —— 先确认工程根（可用 VS_ROOT 指定）。' % gradlew)

with io.open(LOG, 'w', encoding='utf-8', errors='replace') as f:
    p = subprocess.Popen(['cmd', '/c', gradlew, '--console=plain'] + TASKS,
                         cwd=ROOT, stdout=f, stderr=subprocess.STDOUT, env=env)
    rc = p.wait()

txt = io.open(LOG, encoding='utf-8', errors='replace').read()
errs = [l for l in txt.splitlines() if l.startswith('e: ')]
print('EXIT', rc)
print('KOTLIN_ERRORS', len(errs))
print('BUILD_OK', 'BUILD SUCCESSFUL' in txt)
print('---- tail ----')
print('\n'.join(txt.splitlines()[-40:]))
