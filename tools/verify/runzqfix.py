#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 ZqFix.java：站点识别率修复（Accept 内容协商 + 通用解包）的回归断言。

注意 `_zq/zq_home.html` 是**用旧请求头抓下来的「转义响应」**，是这条 bug 的物证，
**不要**用新请求头重新抓它覆盖 —— 那会把离线断言的前提抹掉。
"""
import io, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
import sys as _sys
_sys.path.insert(0, HERE)
import _cp  # noqa: E402

sys.path.insert(0, HERE)
from _cp import classpath

JAVAC = _cp.javac()
JAVA = _cp.java()
ROOT = _cp.project_root()
HTTP_KT = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'videoshell', 'data', 'net', 'Http.kt')

phpass = phfail = 0


def check(ok, what, detail=''):
    global phpass, phfail
    if ok:
        phpass += 1
    else:
        phfail += 1
    print('  [%s] %s%s' % ('PASS' if ok else 'FAIL', what, ('   ' + detail) if detail else ''))


# ---------------------------------------------------------------- 源码守卫
print('=' * 74)
print('D. 源码守卫：网页请求的 Accept 不得广告 application/json')
print('=' * 74)
if not os.path.exists(HTTP_KT):
    check(False, 'Http.kt 存在', HTTP_KT)
else:
    lines = io.open(HTTP_KT, encoding='utf-8').read().splitlines()
    acc = [(i, l.strip()) for i, l in enumerate(lines) if '.header("Accept"' in l]
    check(len(acc) >= 2, '找到多处 Accept 设置', '共 %d 处' % len(acc))
    if acc:
        first = acc[0][1]
        check('application/json' not in first,
              'GET 网页的 Accept 不含 application/json', first[:96])
        check('text/html' in first, 'GET 网页的 Accept 含 text/html')
        check('*/*' in first, 'GET 网页的 Accept 保留 */*（JSON 接口才照常返回 JSON）')
        json_acc = [(i, l) for i, l in acc if 'application/json' in l]
        # v1.0.31 起是两处：表单 POST + JSON POST（播放器换链接口只认 JSON 体）。
        # 真正不能碰的那条铁律没变：**网页 GET 绝不广告 application/json**
        # （那会把整页 HTML 转义成 JSON，站点识别率全崩）；两处 POST 都是接口请求，
        # 广告 JSON 是应当的。
        check(len(json_acc) == 2,
              '只有两处 Accept 广告 application/json（表单 POST + JSON POST：接口，不是网页）',
              '共 %d 处' % len(json_acc))
        if json_acc:
            check(all(i > acc[0][0] for i, _ in json_acc),
                  '这两处都在 GET 之后（属于 POST 接口，不是网页请求）',
                  ','.join('line %d' % (i + 1) for i, _ in json_acc))
        # 相邻的注释里必须写清原因，避免以后被"顺手统一"改回去
        ctx = '\n'.join(lines[max(0, acc[0][0] - 8):acc[0][0] + 1])
        check('application/json' in ctx and ('绝不能' in ctx or '不能' in ctx),
              'GET 的 Accept 上方有"为什么不能用 application/json"的注释')

# ---------------------------------------------------------------- Java 部分
cp = classpath()
out = os.path.join(HERE, '_zqfix_out')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-cp', cp, '-d', out,
                    os.path.join(HERE, 'Chains.java'),
                    os.path.join(HERE, 'ZqFix.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    raise SystemExit('javac 失败')

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-Dsun.stdout.encoding=UTF-8',
                    '-cp', out + os.pathsep + cp, 'ZqFix', os.path.join(HERE, '_zq'),
                    'https://www.zqkhmy.com/'],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
txt = (r.stdout or '') + (('\n[stderr]\n' + r.stderr) if r.stderr else '')
sys.stdout.write(txt)

jpass = txt.count('[PASS]')
jfail = txt.count('[FAIL]')
allpass = jpass + phpass
allfail = jfail + phfail
print()
print('==== runzqfix  pass=%d fail=%d ====' % (allpass, allfail))
open(os.path.join(HERE, '_zqfix.out.txt'), 'w', encoding='utf-8').write(txt)
sys.exit(1 if allfail else 0)
