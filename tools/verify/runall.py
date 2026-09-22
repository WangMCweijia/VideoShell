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

**怎么判一个套件该不该进 NET_SUITES（v1.0.53 实测得出的唯一可靠判据）**：看它**有没有
对真实域名发起读请求**。两个看似合理、实测都错的判据，别再用：

| 错误判据 | 反例 |
|---|---|
| "源码里出现了真实域名" | `HdFix2.java` / `LineFix.java` 都写着 `czzy.app` / `fengkbao` 字样的 Referer，但它们只在**本地夹具**上做文本处理 —— CI 上通过 |
| "源码里出现了 `Http.INSTANCE` / `newCall` 调用点" | `Agg37.java` 有调用点，但走的是未触发的分支 —— CI 上通过 |

⇒ **静态分析判不出来，只能靠真 CI 观测**（开发机的网络与 runner 的地理位置不同，
只有 CI 会红）。第一次把 harness 接进 CI 时，红的多半是**分类错误**而不是代码错。

**不在回归列表里的"工具"**（要参数、只编译不断言，放进 SUITES 只会得到一条假绿）：
`runsurvey.py`（播放页媒体候选勘察，用法 `runsurvey.py <pageUrl>`）；
`runcaliblive.py`（校准矩阵端到端重放，用法 `runcaliblive.py [-v]`，站点用 `-Dvs.base=` 换）；
`runadbprobe.py`（去广告覆盖勘察，用法 `runadbprobe.py <pageUrl>`）。

⚠️ 登记这一节不是形式主义：这三个都是**排查工具**而不是断言套件，它们不进 SUITES 是对的
（零断言会被标 WEAK），但**不登记就会被误以为"跑过了"** —— 本项目已经栽过一次同型的
坑（v1.0.53 迁移后，每日探针一直在跑旧目录的旧副本，不报错、只安静给出过期结论）。

**第三类：混合套件**（离线主体 + 一小段需要真网络，如 `runepname` 的 F 段）。
它**留在 SUITES 里**，因为那一段只是补充、离线部分必须在 CI 上跑；但它的 PASS 数会随网络
浮动，所以本文件把 `[SKIP]` 计数打进每一行、并在合计里单列 `SKIP段=`。
**不要**为了"让数字稳定"把它整体挪进 NET_SUITES —— 那等于把它的离线断言从 CI 上摘掉。

**判据纪律：零断言不算过。** rc=0 但一条 PASS/FAIL 都没有的套件标 `WEAK`
并以退出码 1 结束 —— 否则「夹具没入仓 ⇒ 全部跳过」会伪装成全绿。
"""
import os, re, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
# CI 里 `sys.executable` 就是 setup-python 装的那个；
# 本地若默认解释器缺 `requests`，用 `VS_PYTHON=<路径>` 指一个带依赖的（见 PITFALLS E14）。
PY = os.environ.get('VS_PYTHON') or sys.executable

SUITES = [
    'runverify3', 'runlinefix', 'runadb',
    'runhdfix2', 'runhdfix3', 'runbs', 'runbs4', 'runyg',
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
#
# 下面 5 个是 v1.0.53 接进 CI 后第一次真跑才暴露出来的 —— 它们在本机全绿
# （本机能连上那几个站），在 runner 上 20 条断言全红：
#   runretry     Retry.java 直接 checkSite("茶杯狐") / checkSite("厂长资源")，
#                还打 `https://czzy.app/__videoshell_probe_missing_page__` 试 404 行为
#   runenc       Enc.java 里有一步"CDN 可达（404）"的真实请求
#   runhdfix     HdFix.java `get(client, master/flat)` 读的是**带签名的短效 CDN 地址**
#                （cdn.yzzy31-play.com / fengbao12.com）—— 会过期，且与出口地区有关
#   runrank      Rank.java 同样 `get(client, real1/real2)` 读那两个 CDN 直链
#   runextract2  Extract2.java 的 realCase 要真抓 `https://czzy.app/v_play/...`
# 共同点：**它们对真实域名发起了读请求**。这是唯一可靠的判据（文件头有反例说明）。
NET_SUITES = ['runlive2', 'runlivepic', 'runzqfix', 'runzqseg', 'runzqredir', 'runbs3',
              'runretry', 'runenc', 'runhdfix', 'runrank', 'runextract2']


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
# 活体依赖的"味道"。**只是提示，不是判据** —— 静态判不出"该不该进 NET"
# （见文件头），但把日志里的网络痕迹指出来，能把一次令人困惑的红变成一条可执行的结论。
NET_SMELL = re.compile(r'UnknownHost|ConnectException|SocketTimeout|SocketTimeoutException|'
                       r'ETIMEDOUT|SSLHandshake|Connection reset|Read timed out|'
                       r'HTTP\s*5\d\d|HTTP\s*40[34]|不可达|抓取失败', re.I)
total_ok = total_fail = bad = weak = 0
total_skip = 0
skip_suites = []
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
    # ★ 段级跳过必须**可见**（v1.0.54）。
    #
    # 原来只判"零断言 ⇒ WEAK"，那挡得住"整个套件没跑"，挡不住**部分跳过**：
    # 实测 `runepname`（在 SUITES 离线列里）带一段需要真网络的 F 段，
    # 网络一旦抖，7 条断言无声消失、套件照样报 `OK  PASS=41 FAIL=0` ——
    # 而 PASS 从 48 掉到 41 这件事，不去手动逐套件对比是看不出来的。
    # 这正是本项目最忌讳的"真回归被藏起来"：**覆盖变少 ≠ 通过**。
    #
    # 所以只做一件事：把 SKIP 数**打出来**。不判失败 —— 段级跳过（离线时跳活体段）
    # 是合理设计；要的是让它出现在日志里，而不是消失。
    sk = out.count('[SKIP]')
    weak_run = (r.returncode == 0 and f == 0 and pt == 0)
    total_ok += pt
    total_fail += f
    total_skip += sk
    if sk:
        skip_suites.append('%s(%d)' % (s, sk))
    ok_run = (r.returncode == 0 and f == 0 and not weak_run)
    if weak_run:
        weak += 1
    elif not ok_run:
        bad += 1
    print('%-14s %s  PASS=%-4d FAIL=%-3d rc=%d%s  %s'
          % (s, 'OK  ' if ok_run else ('WEAK' if weak_run else 'BAD '), pt, f, r.returncode,
             ('  SKIP=%d' % sk) if sk else '', (lines[-1] if lines else '')[:70]))
    if not ok_run:
        # 只看汇总行在 CI 里查不出原因 ⇒ 落一份完整输出，并在这里回显失败点。
        open(os.path.join(HERE, '_fail_%s.txt' % s), 'w', encoding='utf-8').write(out)
        bad_lines = [l.strip() for l in out.splitlines() if '[FAIL]' in l]
        for bl in bad_lines[:5]:
            print('      | %s' % bl[:150])
        if len(bad_lines) > 5:
            print('      | …（共 %d 条 FAIL，全文见 _fail_%s.txt）' % (len(bad_lines), s))
        # 活体依赖提示：输出里有网络痕迹 ⇒ 这套件很可能该归 NET_SUITES。
        hits = sorted(set(m.group(0) for m in NET_SMELL.finditer(out)))
        if hits:
            print('      | 提示：输出里有网络痕迹（%s）⇒ 这套件若不该连外网，'
                  '应归 NET_SUITES（判据见文件头）' % ', '.join(hits[:4]))

print()
print('==== 合计 PASS=%d  FAIL=%d  BAD_SUITES=%d  WEAK_SUITES=%d  SKIP段=%d / %d ===='
      % (total_ok, total_fail, bad, weak, total_skip, len(targets)))
if skip_suites:
    # 有段级跳过时**必须多打这一行**：它解释"为什么这次 PASS 比上次少"。
    # 没有它的话，一次网络抖动看起来就像一次断言被删。
    print('     ⚠️ 有段级跳过（离线时跳活体段属正常，但覆盖确实变少了）：%s'
          % ', '.join(skip_suites))
sys.exit(1 if (total_fail or bad or weak) else 0)
