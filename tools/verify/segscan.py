# -*- coding: utf-8 -*-
"""GET+Range 并发探测 HLS 分片：HEAD 在该 CDN 上不可靠，必须用 GET。"""
import io, re, sys, time
import urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor
from collections import Counter

M3U8 = sys.argv[1] if len(sys.argv) > 1 else 'https://bfeng11.com/video/zaijianheise/195098e2e23e/index.m3u8'
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36'
BASE = M3U8.rsplit('/', 1)[0] + '/'


def fetch_text(url):
    req = urllib.request.Request(url, headers={'User-Agent': UA})
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.read().decode('utf-8', 'replace')


def resolve(seg, base):
    if seg.startswith('http'):
        return seg
    if seg.startswith('/'):
        return re.match(r'(https?://[^/]+)', base).group(1) + seg
    return base + seg


def probe(item):
    i, u = item
    req = urllib.request.Request(u, headers={'User-Agent': UA, 'Range': 'bytes=0-0'})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return (i, 200)
    except urllib.error.HTTPError as e:
        return (i, e.code)
    except Exception:
        return (i, -1)


def main():
    txt = fetch_text(M3U8)
    segs = re.findall(r'^(\S+\.ts)\s*$', txt, re.M)
    urls = [resolve(s, BASE) for s in segs]
    print('total segments:', len(urls), flush=True)
    t0 = time.time()
    res = []
    with ThreadPoolExecutor(max_workers=24) as ex:
        for r in ex.map(probe, list(enumerate(urls))):
            res.append(r)
    c = Counter(code for _, code in res)
    print('status counts:', dict(c), flush=True)
    bad = [(i, urls[i], code) for i, code in res if code != 200]
    print('bad count:', len(bad))
    if bad:
        idxs = sorted(i for i, _, _ in bad)
        print('bad index min=%d max=%d' % (idxs[0], idxs[-1]))
        print('bad per-100 bucket:', dict(sorted(Counter(i // 100 for i in idxs).items())))
        print('--- first 30 bad ---')
        for i, u, code in bad[:30]:
            print('  idx=%-5d %-6s %s' % (i, code, u))
    durs = [float(x) for x in re.findall(r'#EXTINF:([\d.]+),', txt)]
    td = re.search(r'#EXT-X-TARGETDURATION:(\d+)', txt)
    print('TARGETDURATION=%s max EXTINF=%.3f -> %s' % (
        td.group(1) if td else '?', max(durs),
        'VIOLATION' if td and max(durs) > int(td.group(1)) else 'ok'))
    print('elapsed %.1fs' % (time.time() - t0))


if __name__ == '__main__':
    main()
