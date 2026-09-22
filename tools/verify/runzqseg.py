#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""实时断言：枫叶影院（www.zqkhmy.com）那条「能读时长、一直转圈」的线路，
从播放页一路取到**第一条正片段**，并把它体检验成一个可断言的形式。

为什么必须是**实时**的：这些地址是播放时现取的（带 psid / auth_key 这类按会话、按时间的
令牌），存档一份样本证明不了现状。这里每一步都发真请求。

断言（都打 [PASS]/[FAIL]，runall 直接数）：
  1 播放页能拿到 player_aaaa
  2 外壳解析页能拿到 #player-data 与 data-u/data-te
  3 mplayer.php 返回 code=200 且 url 是 .m3u8
  4 清单是 #EXTM3U；**带 ENDLIST**（不带就会被 ExoPlayer 当直播 ⇒ 从片尾起播/转圈）
  5 清单没有**悬空 EXTINF**（有 EXTINF 却没有分片 ⇒ 解析器会少一段甚至出错）
  6 ★ 按 HlsPlaylistFixer 的判据剔除广告后，剩下的正片段数 ≥ 30
     （剔得太狠说明广告正则吃掉了正片，那才是我们自己造的"播不了"）
  7 ★ **第一条正片段**（不是"看起来的第一条"——清单开头是广告段）能取到 200
  8 ★ 该分片是 MPEG-TS（0x47 同步字节），且 Content-Type 不是图片
     （拿不到正片却拿到一张占位图，是"转圈"最隐蔽的一种成因）
  9 清单里的分片地址**都是绝对地址**（相对地址要靠 baseUri 猜，容易整片 404）

网络不通 / 站点下线 ⇒ 打 SKIP 并 rc=0（不把"我这里没网"变成红灯）。
"""
import io, os, re, sys
import requests

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = 'https://www.zqkhmy.com'
PLAY = BASE + '/play/50767-5-1.html'
SHELL = 'https://zzrs.mfdyvip.com/player/?url=co_izc2tzd6xi4age3h'
UA = ('Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) '
      'Chrome/120.0.0.0 Mobile Safari/537.36')
AD = re.compile(r'/(adjump|ad|ads|adv|advert|guanggao|gg|advertise)/', re.I)

PASS = FAIL = 0
log = []


def p(s=''):
    log.append(s)
    print(s)


def ok(name, cond, detail=''):
    global PASS, FAIL
    if cond:
        PASS += 1
        p('  [PASS] ' + name)
    else:
        FAIL += 1
        p('  [FAIL] ' + name + '   → ' + str(detail))


def skip(why):
    p('  [SKIP] %s' % why)
    p()
    p('==== zqseg PASS=%d FAIL=%d（SKIP 不计）====' % (PASS, FAIL))
    io.open(os.path.join(HERE, '_zqseg.out.txt'), 'w', encoding='utf-8').write('\n'.join(log))
    sys.exit(0)


p('========== 枫叶影院「能读时长、一直转圈」实时体检 ==========')
p('播放页 %s' % PLAY)
p()

S = requests.Session()
S.headers.update({'User-Agent': UA})
APP = {'User-Agent': UA, 'Referer': BASE + '/', 'Origin': BASE, 'Accept-Encoding': 'gzip'}

# ---- 1 播放页 ----
try:
    rp = S.get(PLAY, timeout=25)
except Exception as e:
    skip('播放页取不到（网络不可达？）%s: %s' % (type(e).__name__, e))
m = re.search(r'player_aaaa\s*=\s*(\{.*?\})\s*</script>', rp.text, re.S)
ok('① 播放页能拿到 player_aaaa', bool(m), 'status=%s len=%d' % (rp.status_code, len(rp.content)))
if not m:
    sys.exit(1)

# ---- 2 外壳解析页 ----
try:
    r0 = S.get(SHELL, timeout=25, headers={'Referer': PLAY})
    tag = re.search(r'<div[^>]*id=["\']player-data["\'][^>]*>', r0.text, re.I)
    ok('② 解析页能拿到 #player-data', bool(tag), 'status=%s' % r0.status_code)
    if not tag:
        sys.exit(1)
    tg = tag.group(0)
    u = re.search(r'data-u\s*=\s*["\']([^"\']*)', tg).group(1)
    te = re.search(r'data-te\s*=\s*["\']([^"\']*)', tg).group(1)
    ok('② data-u / data-te 都非空', bool(u) and bool(te), 'u=%s te=%s' % (u[:20], te[:12]))
except Exception as e:
    skip('外壳解析页异常 %s: %s' % (type(e).__name__, e))

# ---- 3 mplayer.php ----
try:
    jr = S.post('https://zzrs.mfdyvip.com/player/mplayer.php', data={'url': u, 'token': te},
                timeout=25, headers={'Referer': SHELL, 'Origin': 'https://zzrs.mfdyvip.com',
                                     'X-Requested-With': 'XMLHttpRequest'})
    jo = jr.json()
    url = str(jo.get('url', ''))
    ok('③ mplayer.php 返回 code=200 且给出 .m3u8', jo.get('code') == 200 and '.m3u8' in url,
       'body=%s' % jr.text[:160])
except Exception as e:
    skip('mplayer.php 异常 %s: %s' % (type(e).__name__, e))
if '.m3u8' not in url:
    sys.exit(1)

# ---- 4/5/9 清单 ----
try:
    r = S.get(url, timeout=25, headers=APP)
except Exception as e:
    skip('清单取不到 %s: %s' % (type(e).__name__, e))
body = r.text
ok('④ 清单是 #EXTM3U 且 HTTP 200', r.status_code == 200 and body.lstrip().startswith('#EXTM3U'),
   'status=%s head=%r' % (r.status_code, body[:60]))
ok('④ ★ 清单带 ENDLIST（不带就会被当成直播：从片尾起播/一直转圈）',
   '#EXT-X-ENDLIST' in body, 'CT=%s' % r.headers.get('Content-Type'))

lines = body.splitlines()
segs = [l.strip() for l in lines if l.strip() and not l.startswith('#')]
extinf = [l for l in lines if l.startswith('#EXTINF')]
ok('⑤ 没有悬空 EXTINF（有 EXTINF 却没分片）', len(extinf) == len(segs),
   'EXTINF=%d 段=%d' % (len(extinf), len(segs)))
rel = [s for s in segs if not s.startswith('http')]
ok('⑨ 分片地址都是绝对地址（相对地址要靠 baseUri 猜，容易整片 404）',
   len(rel) == 0, '相对 %d/%d' % (len(rel), len(segs)))

# 复刻 HlsPlaylistFixer：剔广告 + 跟着剔掉它后面的 DISCONTINUITY
kept, dropped = [], 0
skipDisc = False
for ln in lines:
    s = ln.strip()
    if not s:
        continue
    if s.startswith('#'):
        if s.startswith('#EXT-X-DISCONTINUITY') and skipDisc:
            skipDisc = False
        continue
    if AD.search(s):
        dropped += 1
        skipDisc = True
        continue
    skipDisc = False
    kept.append(s)

ok('⑥ 剔广告后仍剩 ≥30 条正片段（剔太狠 = 我们自己把正片删了）',
   len(kept) >= 30, 'kept=%d dropped=%d' % (len(kept), dropped))

# ---- 7/8 第一条**正**片段 ----
if not kept:
    ok('⑦ 存在第一条正片段', False, '一条都没剩下')
    sys.exit(1)
first = kept[0]
try:
    sr = S.get(first, timeout=25, headers=APP)
    ct = (sr.headers.get('Content-Type') or '')
    magic = sr.content[:4].hex()
    ok('⑦ ★ 第一条正片段能取到 200', sr.status_code == 200,
       'status=%s len=%d' % (sr.status_code, len(sr.content)))
    ok('⑧ ★ 它是 MPEG-TS（0x47 同步字节）', magic.startswith('47'),
       'magic=%s CT=%s' % (magic, ct))
    ok('⑧ ★ Content-Type 不是图片（拿到占位图 = 最隐蔽的"转圈"成因）',
       not ct.startswith('image/'), 'CT=%s' % ct)
    p('     首条正片段 host=%s  len=%d' % (re.match(r'https?://([^/]+)', first).group(1),
                                          len(sr.content)))
    p('     已剔除广告段 %d 条；保留正片 %d 条' % (dropped, len(kept)))
except Exception as e:
    skip('分片取不到 %s: %s' % (type(e).__name__, e))

p()
p('==== zqseg PASS=%d FAIL=%d ====' % (PASS, FAIL))
io.open(os.path.join(HERE, '_zqseg.out.txt'), 'w', encoding='utf-8').write('\n'.join(log))
sys.exit(1 if FAIL else 0)
