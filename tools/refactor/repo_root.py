# -*- coding: utf-8 -*-
"""tools/refactor 三个脚本共用的「工程根 / 源根 / 按名找文件」。

⚠️ **文件名不能叫 `_root.py`**：本工程 `.gitignore` 有一条 `_*.py`（用来屏蔽根目录
那些临时脚本），于是这个共享模块会被**静默**地排除在提交之外 —— 症状是"另两个脚本
import 失败"，而 `git status` 一片干净、什么都不会提示。踩过一次。

为什么要抽出来：这三个脚本上一轮是**临时件**（`_split.py` / `_fixvis.py` / `_outline.py`），
里面写死了 `ROOT = r'D:\\TRAE\\视频壳'`。那只在一台机器上跑得通 —— 一旦入仓，
写死的路径就意味着"换个人 clone 下来直接报错"，等于把垃圾换个地方放。

定位顺序：
  1. 环境变量 `VS_ROOT`（CI 或多工作区时用）；
  2. 从**本文件**所在目录向上找 `settings.gradle` / `settings.gradle.kts`。

按名找文件是**全量 walk**，不假设包名 —— 上一轮换过一次包路径，脚本不该跟着改。
"""
import os

_MARKERS = ('settings.gradle', 'settings.gradle.kts')


def find_root(start=None):
    env = os.environ.get('VS_ROOT')
    if env and os.path.isdir(env):
        return os.path.abspath(env)
    cur = os.path.abspath(start or os.path.dirname(os.path.abspath(__file__)))
    while True:
        for m in _MARKERS:
            if os.path.exists(os.path.join(cur, m)):
                return cur
        parent = os.path.dirname(cur)
        if parent == cur:
            raise SystemExit('✗ 找不到工程根（没有 settings.gradle）。用 VS_ROOT=<路径> 指定。')
        cur = parent


ROOT = find_root()


def src_root():
    """Kotlin 源根。`app/src/main/java` 与 `.../kotlin` 都认（AGP 两者皆可）。"""
    for rel in (('app', 'src', 'main', 'java'), ('app', 'src', 'main', 'kotlin')):
        p = os.path.join(ROOT, *rel)
        if os.path.isdir(p):
            return p
    raise SystemExit('✗ %s 下找不到 app/src/main/java（或 kotlin）。' % ROOT)


def find(name, base=None):
    """按**文件名**找 .kt / .java，返回首个命中路径，找不到返回 None。"""
    for dp, _dn, fn in os.walk(base or src_root()):
        if name in fn:
            return os.path.join(dp, name)
    return None
