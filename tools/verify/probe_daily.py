#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""VideoShell 站点探针（每日）：只跑**活体联网套件**，产出「今天哪个站、哪一步挂了」。

和 runall.py 的分工
------------------
`runall.py` 跑「离线回归」——读固化样本与源码文本，证明**我们的代码**没退化。
本脚本跑 `runall.py` 里单列的 `NET_SUITES`（活体探针）——现发请求打真实站点，
证明**站点侧此刻**还成立。两者混进一个总数就会互相污染（v1.0.53 的教训：
runbs3 因站点首页多引两个 JS 而红，那是漂移不是回归）。

定性四类（处置完全不同，所以必须分开）
------------------------------------
  · 我方链路    —— 需要改代码。唯一真正要行动的。
  · 站点失联    —— 样本站自己挂了/域名轮换了（野果那族最常发生）。要换样本域名，
                   不是代码回归。**报红不报绿**，否则探针会长期空转。
  · 第三方依赖  —— 失败发生在**别人的域名**上：解析外壳（zzrs.mfdyvip.com）或
                   线路 CDN 自己停用（`DEPLOYMENT_DISABLED` + 402）。我们改不了。
  · 疑似不可达  —— DNS/连接/超时，多半是网络抖动；重跑一次再看。

三条判定纪律（都踩过坑才加的）
  (1) **先预检样本站存活**，且必须**直连**（不继承 HTTP(S)_PROXY）——Java 套件是
      直连的，若预检走代理就会出现"套件说站活着、预检说站死了"的自相矛盾。
  (2) 失败上下文取**所在小节**（最近一条 `====` 分隔线之后），不是只取失败那一行。
      理由：`[FAIL] 清单最终 200 → status=402` 里根本没有主机名，只看这一行会把
      第三方 CDN 的锅算到我们头上。
  (3) 级联失败随**首发**定性：后面的红多半是第一条的后果（清单取回 402 的 HTML，
      自然"不含 #EXTM3U"）。逐条也给出各自定性，摆在明细里，不藏。

探针**只报告、不自动改**：站点变了，是它挂了还是我们该改适配，必须人判断
（这正是 PITFALLS 里「A/B 定位法」那条教训）。

用法
----
    python probe_daily.py              # 编译（增量）+ 跑全部探针 + 出报告
    python probe_daily.py --no-build   # 跳过编译，直接用现有产物
    python probe_daily.py runlive2     # 只跑指定套件

退出码：0 = 无需我方行动；1 = 需我方行动（我方链路 / 样本站失联）。
"""
import datetime
import io
import os
import re
import ssl
import subprocess
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
import sys as _sys
_sys.path.insert(0, HERE)
import _cp  # noqa: E402

PROJ = _cp.project_root()
PY = sys.executable
OUTDIR = os.path.join(HERE, 'probe')
HIST = os.path.join(OUTDIR, 'history.tsv')
BUILD_CLASSES = os.path.join(PROJ, 'app', 'build', 'tmp', 'kotlin-classes', 'debug')

UA = ('Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 '
      'Chrome/120 Mobile Safari/537.36')

# 套件 → 它在打谁（给人看的）
SITE = {
    'runlive2':   '茶杯狐 cupfoxyy.com —— v1.0.4 真实 suspend 链路',
    'runlivepic': '野果 capable.fzchosdi.cc —— 封面/取图线上链路',
    'runzqfix':   '枫叶 zqkhmy.com —— 识别率（Accept 协商 + 通用解包）',
    'runzqseg':   '枫叶 zqkhmy.com（第 3 线路）—— 首片 / m3u8 合规',
    'runzqredir': '枫叶 zqkhmy.com（第 3 线路）—— 302 后的相对基准',
    'runbs3':     '金牌 bolyship.com —— 跨 Activity / 请求条数',
}

# 套件 → 它**自己的**站（后缀匹配）。用来干两件事：
#   ① 预检样本站是否还活着（野果那族域名会轮换，样本会过期）
#   ② 判"失败是否发生在别人的域名上" ⇒ 第三方依赖
SITE_HOST = {
    'runlive2':   ['cupfoxyy.com'],
    'runlivepic': ['fzchosdi.cc'],
    'runzqfix':   ['zqkhmy.com'],
    'runzqseg':   ['zqkhmy.com'],
    'runzqredir': ['zqkhmy.com'],
    'runbs3':     ['bolyship.com'],
}

# 已知第三方链路的字面标记（宿主不在上下文里时的兜底；遇到新的就往这里加一行）
THIRD = ('mfdyvip.com', 'mplayer.php', 'zzrs.', 'DEPLOYMENT_DISABLED')
# 网络/站点不可达特征
UNREACH = (
    'UnknownHostException', 'ConnectException', 'Connection refused',
    'SocketTimeoutException', 'SSLHandshakeException', 'Unable to resolve host',
    'NoRouteToHostException', 'timed out', 'Network is unreachable',
)
# 需要我方行动的两类（第三方依赖与疑似不可达照报、但不进 needs_action）
ACTIONABLE = ('我方链路', '站点失联')
# 级联优先级：只在同一套件内多条失败**都是同源**时用不到；
# 这里保留给"首条失败没定性成功"的兜底排序。
SEV = {'我方链路': 3, '站点失联': 2, '疑似不可达': 1, '第三方依赖': 0}


def net_suites():
    """从 runall.py 解析 NET_SUITES —— 判据只有一份，绝不在本文件另抄一份。

    抄一份的后果：以后往 runall.py 加了新探针，探针脚本不知道，默默漏跑。
    """
    src = io.open(os.path.join(HERE, 'runall.py'), encoding='utf-8').read()
    m = re.search(r'NET_SUITES\s*=\s*\[(.*?)\]', src, re.S)
    if not m:
        raise SystemExit('runall.py 里找不到 NET_SUITES —— 单一定义被破坏了，先修 runall.py')
    names = re.findall(r"'([A-Za-z0-9_]+)'", m.group(1))
    if len(names) < 3:
        raise SystemExit('NET_SUITES 解析结果可疑：%r（防"解析成空表却一路绿"）' % (names,))
    return names


# ------------------------------------------------------------------ 预检

def direct_get(url, timeout=15):
    """直连取一次（**不继承代理**）。返回 (status|None, note)。"""
    op = urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        urllib.request.HTTPSHandler(context=ssl._create_unverified_context()))
    op.addheaders = [('User-Agent', UA), ('Accept', '*/*')]   # 绝不带 application/json
    try:
        r = op.open(url, timeout=timeout)
        r.read(64)
        return r.status, ''
    except Exception as e:                                     # noqa: BLE001
        return None, repr(e)


def precheck(suites):
    """样本站存活预检。返回 {suite: (ok, host, note)}。"""
    out = {}
    for s in suites:
        hosts = SITE_HOST.get(s) or []
        if not hosts:
            continue
        h = hosts[0]
        st, note = direct_get('https://%s/' % h)
        out[s] = (st is not None and 200 <= st < 400, h, ('' if st else note))
    return out


# ------------------------------------------------------------------ 定性

def hosts_in(text):
    return [x.lower() for x in re.findall(r'https?://([A-Za-z0-9._-]+)', text)]


def is_own(host, own):
    return any(host == o or host.endswith('.' + o) for o in own)


def section_of(lines, i):
    """失败行所在的小节：最近一条 `====` 分隔线之后到失败行。

    为什么不能只看失败那一行：`[FAIL] 清单最终 200 → status=402` 里没有主机名，
    只看它就会把第三方 CDN 的锅算到我们头上。
    """
    j = i - 1
    while j >= 0:
        s = lines[j].strip()
        if len(s) >= 10 and set(s) <= {'='}:
            break
        j -= 1
    return lines[j + 1:i]


def classify(lines, i, suite, site_down):
    if site_down:
        return '站点失联'
    ctx = ' '.join(section_of(lines, i) + lines[i:i + 3])
    own = SITE_HOST.get(suite) or []
    foreign = [h for h in hosts_in(ctx) if own and not is_own(h, own)]
    if foreign or any(k in ctx for k in THIRD):
        return '第三方依赖'
    if any(k in ctx for k in UNREACH):
        return '疑似不可达'
    return '我方链路'


# ------------------------------------------------------------------ 构建

def build():
    """增量编译 debug。失败**不阻断**探针（用现有产物跑，并在报告里如实写明）。"""
    try:
        p = subprocess.run(
            [PY, os.path.join(PROJ, '_build.py'), ':app:assembleDebug'],
            cwd=PROJ, capture_output=True, text=True,
            encoding='utf-8', errors='replace', timeout=1800)
    except Exception as e:                                     # noqa: BLE001
        return False, -1, 'build 抛异常：%r' % (e,)
    out = (p.stdout or '') + (p.stderr or '')
    ok = 'BUILD_OK True' in out
    m = re.search(r'KOTLIN_ERRORS (\d+)', out)
    return ok, (int(m.group(1)) if m else -1), '\n'.join(out.splitlines()[-15:])


def head_info():
    ver = code = '?'
    try:
        g = io.open(os.path.join(PROJ, 'app', 'build.gradle'), encoding='utf-8').read()
        mv = re.search(r'versionName\s+"([^"]+)"', g)
        mc = re.search(r'versionCode\s+(\d+)', g)
        if mv:
            ver = mv.group(1)
        if mc:
            code = mc.group(1)
    except Exception:                                          # noqa: BLE001
        pass
    sha = '?'
    try:
        r = subprocess.run(['git', 'rev-parse', '--short', 'HEAD'], cwd=PROJ,
                           capture_output=True, text=True, encoding='utf-8',
                           errors='replace', timeout=30)
        if r.returncode == 0:
            sha = (r.stdout or '').strip() or '?'
    except Exception:                                          # noqa: BLE001
        pass
    return ver, code, sha


# ------------------------------------------------------------------ 主流程

def main():
    argv = sys.argv[1:]
    do_build = '--no-build' not in argv
    picked = [a for a in argv if not a.startswith('-')]
    os.makedirs(OUTDIR, exist_ok=True)

    suites = picked or net_suites()
    now = datetime.datetime.now()
    ver, code, sha = head_info()
    log = []

    def say(s=''):
        log.append(s)
        print(s)

    def flush():
        io.open(os.path.join(OUTDIR, 'probe_%s.md' % now.strftime('%Y-%m-%d')),
                'w', encoding='utf-8').write('\n'.join(log) + '\n')

    say('# VideoShell 站点探针 %s' % now.strftime('%Y-%m-%d %H:%M'))
    say()
    say('- 版本：%s（versionCode %s）· HEAD `%s`' % (ver, code, sha))

    if do_build:
        ok, kerr, tail = build()
        say('- 编译：%s（KOTLIN_ERRORS=%d）' % ('成功' if ok else '**失败**', kerr))
        if not ok:
            say('  - 编译失败 → 本次探针用**现有产物**跑，结论可能对应旧代码，别当准。')
            for l in tail.splitlines()[-8:]:
                say('  - `%s`' % l.strip()[:150])
    else:
        say('- 编译：已跳过（--no-build）')

    if not os.path.isdir(BUILD_CLASSES):
        say()
        say('**探针无法执行**：编译产物不存在（`%s`）。先跑 `python _build.py :app:assembleDebug`。'
            % BUILD_CLASSES)
        flush()
        return 1

    # ---- 样本站存活预检（直连，与 Java 套件同视角）
    pre = precheck(suites)
    if pre:
        parts = []
        for s in suites:
            if s in pre:
                ok, h, note = pre[s]
                parts.append('%s %s%s' % (h, 'OK' if ok else '**失联**',
                                          '' if ok else '（%s）' % note[:60]))
        say('- 样本站：' + ' · '.join(parts))

    say('- 套件：%d 个（%s）' % (len(suites), ', '.join(suites)))
    say()

    rows, detail = [], []
    total_p = total_f = 0
    need = []

    for s in suites:
        path = os.path.join(HERE, s + '.py')
        if not os.path.exists(path):
            # 不静默跳过：runall.py 点了名却没有实现 ⇒ 新探针漏写了，属「要人处理」。
            rows.append((s, '—', 'MISSING', 0, 0, '我方链路',
                         'runall.py 的 NET_SUITES 里有、目录里没有：%s.py' % s))
            need.append(s)
            detail.append((s, '我方链路', [('我方链路', '套件文件缺失：%s.py' % s)]))
            continue

        down = s in pre and not pre[s][0]
        try:
            r = subprocess.run([PY, path], capture_output=True, text=True,
                               encoding='utf-8', errors='replace', cwd=HERE, timeout=900)
        except subprocess.TimeoutExpired:
            rows.append((s, SITE.get(s, '—'), 'TIMEOUT', 0, 0, '疑似不可达',
                         '超过 900s 未结束'))
            detail.append((s, '疑似不可达', [('疑似不可达', '超过 900s 未结束')]))
            continue

        out = (r.stdout or '') + (('\n' + r.stderr) if r.stderr else '')
        lines = out.splitlines()
        pt, f = out.count('[PASS]'), out.count('[FAIL]')
        total_p += pt
        total_f += f
        idxs = [i for i, l in enumerate(lines) if '[FAIL]' in l]

        if f == 0 and r.returncode == 0:
            rows.append((s, SITE.get(s, '—'), 'OK', pt, 0, 'OK', ''))
            continue

        pairs = [(classify(lines, i, s, down), lines[i].strip()[:200]) for i in idxs]
        if not pairs:
            pairs = [('我方链路', 'rc=%d（无 [FAIL] 行）' % r.returncode)]
        # 级联失败随**首发**定性：这些套件是线性链，第 1 条挂了后面自然跟着挂
        # （清单取回 402 的 HTML ⇒ 自然"不含 #EXTM3U"）。若首条是**非我方**原因，
        # 后面的"我方链路"一律标成「续发」并**不计入 needs_action** ——
        # 否则 runzqredir 这种会每天误报，探针很快就没人看了。
        verdict = pairs[0][0]
        if verdict in ('第三方依赖', '疑似不可达', '站点失联'):
            pairs = [pairs[0]] + [('续发', l) for _, l in pairs[1:]]
        rows.append((s, SITE.get(s, '—'), 'BAD', pt, f, verdict, pairs[0][1][:160]))
        detail.append((s, verdict, pairs))
        if verdict in ACTIONABLE:
            need.append(s)

    say('| 套件 | 站点 | 结果 | PASS | FAIL | 定性 | 首条失败 |')
    say('|---|---|---|---|---|---|---|')
    for s, site, st, pt, f, vd, first in rows:
        say('| %s | %s | %s | %d | %d | %s | `%s` |'
            % (s, site, st, pt, f, vd, first.replace('|', '\\|')))

    say()
    say('**合计 PASS=%d FAIL=%d**；需我方行动：%s' % (total_p, total_f, '、'.join(need) or '无'))

    if detail:
        say()
        say('## 失败明细（逐条定性）')
        for s, vd, pairs in detail:
            say()
            say('### %s —— 首发定性：%s' % (s, vd))
            say('站点：%s' % SITE.get(s, '—'))
            for k, line in pairs:
                say('- **[%s]** `%s`' % (k, line))
    else:
        say()
        say('全部套件通过，站点侧未见漂移。')

    say()
    say('> 探针只报告、不自动改。判「站点漂移 vs 我方回归」用 A/B 定位法：把可疑的那层'
        '换回上一版、重编译、重跑，若同样失败 ⇒ 漂移。')

    flush()
    with io.open(HIST, 'a', encoding='utf-8') as fh:
        if not os.path.exists(HIST) or os.path.getsize(HIST) == 0:
            fh.write('date\ttime\tversion\tpass\tfail\tbad_suites\tneeds_action\n')
        fh.write('%s\t%s\t%s\t%d\t%d\t%s\t%s\n'
                 % (now.strftime('%Y-%m-%d'), now.strftime('%H:%M'), ver,
                    total_p, total_f,
                    ','.join(r[0] for r in rows if r[2] != 'OK') or '-',
                    ','.join(need) or '-'))

    return 1 if need else 0


if __name__ == '__main__':
    sys.exit(main())
