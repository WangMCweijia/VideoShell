#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑离线回归套件（`tools/verify/`），只打印每个套件的 pass/fail 汇总行。

用法：

    python tools/verify/runall.py                  # 离线套件（CI 用这个）
    python tools/verify/runall.py --net            # 追加 6 个联网套件（要真实网络）
    python tools/verify/runall.py runseed runbs4   # 只跑指定的几套

**联网套件默认不跑**（v1.0.53 起）：它们打的是活体站点，站点漂移会污染"离线总数"
变成假红 —— 这就是 `runbs3` 当初混在 `SUITES` 里造成的误报。要巡检活体站点请用
`probe_daily.py`（它同样从本文件解析 `NET_SUITES`，不另抄一份）。

**不在回归列表里的"工具"**（要参数、只编译不断言，放进 SUITES 只会得到一条假绿）：
`runsurvey.py`（播放页媒体候选勘察，用法 `runsurvey.py <pageUrl>`）。

**判据纪律：零断言不算过。** rc=0 但一条 PASS/FAIL 都没有的套件标 `WEAK`
并以退出码 1 结束 —— 否则「夹具没入仓 ⇒ 全部跳过」会伪装成全绿。
"""
import os, re, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
# CI 里 `sys.executable` 就是 setup-python 装的那个；
# 本地若默认解释器缺 `requests`，用 `VS_PYTHON=<路径>` 指一个带依赖的（见 PITFALLS E14）。
PY = os.environ.get('VS_PYTHON') or sys.executable

SUITES = [
    'runverify3', 'runlinefix', 'runretry', 'runenc',
    'runrank', 'runadb', 'runhdfix', 'runhdfix2', 'runhdfix3', 'runextract2', 'runbs', 'runbs4', 'runyg',
    'runpic', 'runresume', 'runhome', 'rungroups', 'runsearch', 'runygo',
    'runzqlines', 'runux', 'runmacplayer', 'runcalib', 'runzqshell', 'runshdyshell', 'runygimage',
    'runygcalib',
    'rununiversal',
    'runfamily',
    'runepname',
    'runagg37',
    'runagg38',
    'runagg39',
    'runredir40',
    'runui40',
    'runui47',
    'runui48',
    'runui49',
    'runui50',
    'runui51',
    'runui52',
    'runseed',
]
# 需要真实网络的；**默认不跑**（见文件头）。
# runbs3 是 v1.0.53 从 SUITES 挪过来的：它跑的是 `https://www.bolyship.com`，
# 断言的又是"详情页一共发了几条请求"—— 站点一改首页（多引两个 JS）就会红，
# 而那是**站点漂移**不是我们的回归（v1.0.53 已用"退回旧工厂再跑一遍"证实过）。
NET_SUITES = ['runlive2', 'runlivepic', 'runzqfix', 'runzqseg', 'runzqredir', 'runbs3']


def _targets():
    argv = sys.argv[1:]
    want_net = '--net' in argv
    only = [a for a in argv if not a.startswith('-')]
    alls = SUITES + NET_SUITES
    if only:
        unknown = [s for s in only if s not in alls]
        if unknown:
            print('未知套件：%s' % ', '.join(unknown))
            return []
        return only
    return SUITES + (NET_SUITES if want_net else [])


summary = re.compile(r'(pass\s*=\s*\d+.*?fail\s*=\s*\d+)|(失败)|(SystemExit)')
# 有些 Java 套件打的是 `  PASS  <标题>`（不带方括号，见 LineFix.java）。
# 只认 `[PASS]` 会把 15 条断言数成 0 ⇒ 汇总里的 PASS 列说谎。
BARE_PASS = re.compile(r'(?m)^\s*PASS\b')
total_ok = total_fail = bad = weak = 0
targets = _targets()
if not targets:
    sys.exit(2)
for s in targets:
    p = os.path.join(HERE, s + '.py')
    if not os.path.exists(p):
        print('%-14s SKIP(缺文件)' % s)
        continue
    r = subprocess.run([PY, p], capture_output=True, text=True,
                       encoding='utf-8', errors='replace', cwd=HERE, timeout=900)
    out = (r.stdout or '') + (r.stderr or '')
    lines = [l.strip() for l in out.splitlines() if summary.search(l)]
    f = out.count('[FAIL]')
    pt = out.count('[PASS]')
    if pt == 0:
        pt = len(BARE_PASS.findall(out))
    weak_run = (r.returncode == 0 and f == 0 and pt == 0)
    total_ok += pt
    total_fail += f
    ok_run = (r.returncode == 0 and f == 0 and not weak_run)
    if weak_run:
        weak += 1
    elif not ok_run:
        bad += 1
    print('%-14s %s  PASS=%-4d FAIL=%-3d rc=%d  %s'
          % (s, 'OK  ' if ok_run else ('WEAK' if weak_run else 'BAD '), pt, f, r.returncode,
             (lines[-1] if lines else '')[:70]))
    if not ok_run:
        # 只看汇总行在 CI 里查不出原因 ⇒ 落一份完整输出，并在这里回显失败点。
        open(os.path.join(HERE, '_fail_%s.txt' % s), 'w', encoding='utf-8').write(out)
        bad_lines = [l.strip() for l in out.splitlines() if '[FAIL]' in l]
        for bl in bad_lines[:5]:
            print('      | %s' % bl[:150])
        if len(bad_lines) > 5:
            print('      | …（共 %d 条 FAIL，全文见 _fail_%s.txt）' % (len(bad_lines), s))

print()
print('==== 合计 PASS=%d  FAIL=%d  BAD_SUITES=%d  WEAK_SUITES=%d / %d ===='
      % (total_ok, total_fail, bad, weak, len(targets)))
sys.exit(1 if (total_fail or bad or weak) else 0)
