#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""CI 一致性仿真：只铺 **git 已跟踪** 的文件，在那里跑一遍离线回归。

为什么需要它（见 docs/PITFALLS.md §4.35）：
    本机跑绿**不构成**"CI 会绿"的证据。开发机上多出来的东西（工程根的 `_bs/`、
    `_s3~_s6/`、随手抓的 `_*.html`、`app/build/` 产物）恰恰是"缺了就会红"的那些 ——
    于是"某个套件其实一直在读一个没入仓的文件"这种问题只会在 CI 日志里现形。

    `git archive HEAD` 与 `git status` 的差别，正好就是"入仓了没有"。
    本脚本把这棵树单独铺出来、把 CI 上由 gradle 现造的东西补上，再跑 `runall.py`。

用法：
    python tools/verify/ci_sim.py            # 铺树 + 跑全量离线回归
    python tools/verify/ci_sim.py --suite runygo   # 只跑一个套件（快速自查）

它证明的东西只说一半，别当全保：**只能证明"文件层面等价"**。
平台差异（Linux 的 `\\`、大小写敏感、没有 `local.properties`）它抓不到 ——
那部分要靠"路径一律 `/`、工具链一律问 `_cp`"来保证。
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp                                            # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--suite', default=None, help='只跑指定套件（不含 .py）')
    ap.add_argument('--jobs-quiet', action='store_true', help='留给将来并行用')
    a = ap.parse_args()

    root = _cp.project_root()
    sim = tempfile.mkdtemp(prefix='videoshell_ci_sim_')
    print('工程根   =', root)
    print('仿真树   =', sim)

    # 1) 只导出已跟踪文件 —— 这就是 CI checkout 后会看到的东西
    zp = os.path.join(sim, '_src.zip')
    r = subprocess.run(['git', 'archive', '--format=zip', '-o', zp, 'HEAD'],
                       cwd=root, capture_output=True)
    if r.returncode != 0:
        sys.exit('git archive 失败：' + r.stderr.decode('utf-8', 'replace')[:300])
    with zipfile.ZipFile(zp) as z:
        z.extractall(sim)
    n_tracked = sum(len(f) for _, _, f in os.walk(sim)) - 1

    # 2) 补两样「CI 上由构建步骤现造、本地靠副作用存在」的东西
    copied = []

    def cp_into(src, dst_rel):
        if not os.path.exists(src):
            print('  !! 缺 %s（先跑 ./gradlew :app:assembleDebug）' % src)
            return
        dst = os.path.join(sim, dst_rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if os.path.isdir(src):
            shutil.copytree(src, dst, dirs_exist_ok=True)
        else:
            shutil.copy2(src, dst)
        copied.append(dst_rel)

    cp_into(os.path.join(root, 'app', 'build', 'tmp', 'kotlin-classes'),
            os.path.join('app', 'build', 'tmp', 'kotlin-classes'))
    cp_into(os.path.join(root, 'app', 'build', 'intermediates',
                         'compile_and_runtime_not_namespaced_r_class_jar', 'debug', 'R.jar'),
            os.path.join('app', 'build', 'intermediates',
                         'compile_and_runtime_not_namespaced_r_class_jar', 'debug', 'R.jar'))
    cp_into(os.path.join(root, 'local.properties'), 'local.properties')

    print('已跟踪文件 %d 个；另补入 %s' % (n_tracked, ', '.join(copied) or '（无）'))

    # 3) 说清"本地有、这棵树里没有"的样本，避免把结论读错
    missing = [d for d in ('_bs', '_s3', '_s4', '_s5', '_s6')
               if not os.path.exists(os.path.join(sim, d))]
    print('本地样本目录在这棵树里缺失（CI 同此）：%s' % (', '.join(missing) or '无'))
    print('')

    # 4) 跑
    cmd = [sys.executable, '-X', 'utf8', os.path.join('tools', 'verify', 'runall.py')]
    if a.suite:
        p = os.path.join(sim, 'tools', 'verify', a.suite + '.py')
        cmd = [sys.executable, '-X', 'utf8', p]
    env = dict(os.environ)
    env['PYTHONIOENCODING'] = 'utf-8'
    r = subprocess.run(cmd, cwd=sim, env=env)
    print('')
    print('rc=%d   仿真树保留在 %s（可随时删掉）' % (r.returncode, sim))
    sys.exit(r.returncode)


main()
