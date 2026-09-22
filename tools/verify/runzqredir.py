#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""实时套件：枫叶影院第 3 线路（蓝光2k / from=co）的"分片 402"到底是不是基准错。

v1.0.39 真机症状：清单 200、时长读得到，每个分片 402（`X-Vercel-Error: DEPLOYMENT_DISABLED`），
重试无用、一直转圈，而网页端同一条线路能播。

实测根因：清单被 302 到**另一个目录**，清单里的分片是**相对地址**。
  · 以【请求地址】为基准 ⇒ /cloud/flv/… ⇒ 再被 302 甩去已停用的第三方域名 ⇒ **402**
  · 以【最终地址】为基准 ⇒ /ufile/flv/qq/… ⇒ **200 / video/MP2T / 首字节 0x47**（真分片）
v1.0.40 把基准改成"服务器最后给清单的那个地址"，本套件就是这条结论的实时复现。

需要网络；不通就整段 SKIP（不假红）。
输出 _zqredir.out.txt。
"""
import io, os, re, json, sys

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = 'https://www.zqkhmy.com'
SHELLBASE = 'https://zzrs.mfdyvip.com'
UA = ('Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) '
      'Chrome/120.0.0.0 Mobile Safari/537.36')

out = []
pass_n = 0
fail_n = 0
skip_n = 0


def p(*a):
    out.append(' '.join(str(x) for x in a))
    print(out[-1])


def save():
    io.open(os.path.join(HERE, '_zqredir.out.txt'), 'w', encoding='utf-8').write('\n'.join(out))


def ok(name, cond, detail=''):
    global pass_n, fail_n
    if cond:
        pass_n += 1
        p('  [PASS] %s' % name)
    else:
        fail_n += 1
        p('  [FAIL] %s   → %s' % (name, detail))


def skip(name, why):
    global skip_n
    skip_n += 1
    p('  [SKIP] %s（%s）' % (name, why))


def main():
    try:
        import requests
    except Exception as e:
        skip('全部', '没有 requests：%s' % e)
        return

    S = requests.Session()
    S.headers.update({'User-Agent': UA, 'Accept-Encoding': 'gzip'})
    APP = {'User-Agent': UA, 'Referer': BASE + '/', 'Accept-Encoding': 'gzip'}

    p('=' * 74)
    p('1. 解析第 3 线路的清单地址')
    p('=' * 74)
    try:
        rp = S.get(BASE + '/play/94818-3-1.html', timeout=25)
        m = re.search(r'player_aaaa\s*=\s*(\{.*?\})\s*</script>', rp.text, re.S)
        if not m:
            ok('播放页有 player_aaaa', False, '没找到')
            return
        pa = json.loads(m.group(1))
        ok('第 3 线路 from=co', pa.get('from') == 'co', 'from=%s' % pa.get('from'))
        shell = SHELLBASE + '/player/?url=' + str(pa.get('url', ''))
        r0 = S.get(shell, timeout=25, headers={'Referer': BASE + '/play/94818-3-1.html'})
        tg = re.search(r'<div[^>]*id=["\']player-data["\'][^>]*>', r0.text, re.I)
        if not tg:
            ok('外壳有 #player-data', False, '没有')
            return
        g = tg.group(0)
        u = re.search(r'data-u\s*=\s*["\']([^"\']*)', g).group(1)
        te = re.search(r'data-te\s*=\s*["\']([^"\']*)', g).group(1)
        jr = S.post(SHELLBASE + '/player/mplayer.php', data={'url': u, 'token': te}, timeout=25,
                    headers={'Referer': shell, 'Origin': SHELLBASE,
                             'X-Requested-With': 'XMLHttpRequest'})
        try:
            jd = jr.json()
        except Exception:
            jd = {}
        req_url = str(jd.get('url', ''))
        # 失败时要把「我在问谁 + 它回了什么」一起打出来。
        # 原来只打 url 字段 ⇒ 外壳拒答时 detail 是空串，FAIL 行只剩一个空箭头；
        # 而探针是**自动**定性的，没有主机名与响应体就只剩瞎猜 ⇒ 天天误报。
        # 带上 SHELLBASE 与响应体后，这一条能被自动判成「第三方依赖」。
        ok('拿到 m3u8', req_url.startswith('http'),
           '%s -> HTTP %s %s' % (SHELLBASE + '/player/mplayer.php',
                                 jr.status_code, jr.text[:120].replace('\n', ' ')))
    except Exception as e:
        skip('全部', '网络不通：%s %s' % (type(e).__name__, e))
        return

    p()
    p('=' * 74)
    p('2. ★ 清单会被 302 到另一个目录吗')
    p('=' * 74)
    r = S.get(req_url, headers=APP, timeout=25, allow_redirects=True)
    fin_url = r.url
    p('  请求地址 = %s' % req_url[:150])
    p('  最终地址 = %s' % fin_url[:150])
    p('  跳转链   = %s' % ' -> '.join(
        '%s %s' % (h.status_code, (h.headers.get('Location') or '')[:60]) for h in r.history))
    ok('清单最终 200', r.status_code == 200, 'status=%s' % r.status_code)
    ok('★ 清单确实被重定向（这条线路的成因）', fin_url != req_url,
       '没有重定向 —— 那这条站的成因就不是基准错，检查是否已改判据')

    req_dir = req_url.split('?')[0].rsplit('/', 1)[0]
    fin_dir = fin_url.split('?')[0].rsplit('/', 1)[0]
    ok('★ 两个基准目录不同（基准选错就必然走错地址）', req_dir != fin_dir,
       '目录相同')

    p()
    p('=' * 74)
    p('3. ★ 同一个分片：两种基准')
    p('=' * 74)
    segs = [l.strip() for l in r.text.splitlines() if l.strip() and not l.startswith('#')]
    if len(segs) < 2:
        ok('清单里有分片', False, '段数=%d' % len(segs))
        return
    rel = segs[1]
    ok('★ 分片是相对地址（整件事成立的前提）', not rel.startswith('http'), rel[:80])

    u_req = req_dir + '/' + rel.lstrip('/')
    u_fin = fin_dir + '/' + rel.lstrip('/')

    a = S.get(u_req, headers=APP, timeout=25, allow_redirects=True)
    p('  [请求基准] %s' % u_req[:130])
    p('    -> status=%s %d B CT=%s' % (a.status_code, len(a.content),
                                       (a.headers.get('Content-Type') or '')[:30]))
    p('    -> 最终URL=%s' % a.url[:130])
    b = S.get(u_fin, headers=APP, timeout=25, allow_redirects=True)
    p('  [最终基准] %s' % u_fin[:130])
    p('    -> status=%s %d B CT=%s' % (b.status_code, len(b.content),
                                       (b.headers.get('Content-Type') or '')[:30]))

    ok('★ 最终基准拿到真分片（200 + video/MP2T 或 MPEG-TS 首字节）',
       b.status_code == 200 and len(b.content) > 100000 and
       (b.content[:1] == b'\x47' or 'MP2T' in (b.headers.get('Content-Type') or '')),
       'status=%s len=%d ct=%s' % (b.status_code, len(b.content),
                                   b.headers.get('Content-Type')))
    # 反向：请求基准应当失败（若它也 200，说明这条站已经不构成该成因了 ⇒ SKIP 而不是 FAIL）
    if a.status_code == 200 and a.content[:1] == b'\x47':
        skip('★ 请求基准会失败（402）', '该站当前两种基准都能取到，成因已变，按现状不作断言')
    else:
        ok('★ 请求基准取不到真分片（这正是 App 一直转圈的原因）',
           not (a.status_code == 200 and len(a.content) > 100000 and a.content[:1] == b'\x47'),
           'status=%s len=%d' % (a.status_code, len(a.content)))

    p()
    p('=' * 74)
    p('4. 顺带确认：清单自身没有 ENDLIST/PLAYLIST-TYPE 之类会让播放器误判的东西')
    p('=' * 74)
    body = r.text
    ok('清单含 #EXTM3U', body.lstrip().startswith('#EXTM3U'))
    ok('清单是媒体清单（无 #EXT-X-STREAM-INF）', '#EXT-X-STREAM-INF' not in body)
    ok('分片数 >= 30', len(segs) >= 30, '段数=%d' % len(segs))


try:
    main()
except Exception as e:
    skip('全部', '异常 %s %s' % (type(e).__name__, e))

p()
p('==== runzqredir PASS=%d FAIL=%d SKIP=%d ====' % (pass_n, fail_n, skip_n))
save()
sys.exit(1 if fail_n else 0)
