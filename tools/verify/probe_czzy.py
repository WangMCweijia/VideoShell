import urllib.request, ssl, re, gzip, io, sys

UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
BASE = "https://czzy.app"


def get(url, referer=None, save=None):
    req = urllib.request.Request(url)
    req.add_header("User-Agent", UA)
    req.add_header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
    req.add_header("Accept-Language", "zh-CN,zh;q=0.9")
    if referer:
        req.add_header("Referer", referer)
    try:
        r = urllib.request.urlopen(req, timeout=25,
                                   context=ssl._create_unverified_context())
        raw = r.read()
        if r.headers.get("Content-Encoding") == "gzip":
            raw = gzip.decompress(raw)
        txt = raw.decode("utf-8", "replace")
        if save:
            open(save, "w", encoding="utf-8").write(txt)
        return r.status, dict(r.headers), txt
    except Exception as e:
        return -1, {}, str(e)


def main():
    print("=== 首页 ===")
    st, h, html = get(BASE + "/", save="cz_home.html")
    print("HTTP", st, len(html), h.get("Content-Type"))
    hrefs = re.findall(r'href="(/[a-z]+/\d+\.html)"', html)
    print("详情页链接样本:", list(dict.fromkeys(hrefs))[:8])
    play = re.findall(r'href="(/v_play/[^"]+)"', html)
    print("首页 v_play 链接:", list(dict.fromkeys(play))[:5])
    if not hrefs:
        print(html[:600])
        return

    det = BASE + list(dict.fromkeys(hrefs))[0]
    print("\n=== 详情页", det, "===")
    st, h, dhtml = get(det, BASE + "/", save="cz_detail.html")
    print("HTTP", st, len(dhtml))
    print("含 noplay:", "noplay" in dhtml, " 含 player_aaaa:", "player_aaaa" in dhtml)
    pl = list(dict.fromkeys(re.findall(r'href="(/v_play/[^"]+)"', dhtml)))
    print("v_play 数量:", len(pl), pl[:5])
    print("含 m3u8:", "m3u8" in dhtml, " 含 .mp4:", ".mp4" in dhtml)
    for m in re.findall(r'[^"\']{0,60}m3u8[^"\']{0,40}', dhtml)[:6]:
        print("   m3u8 片段:", m)

    if pl:
        pu = BASE + pl[0]
        print("\n=== 播放页", pu, "===")
        st, h, phtml = get(pu, det, save="cz_play.html")
        print("HTTP", st, len(phtml))
        for kw in ["noplay", "登录", "player_aaaa", "m3u8", "mp4", "axios", "api/", "vod_play"]:
            print("   含 %-14s : %s" % (kw, kw in phtml))
        for m in re.findall(r'[^"\']{0,70}(?:m3u8|\.mp4)[^"\']{0,50}', phtml)[:10]:
            print("   媒体片段:", m)
        # 打印 noplay / 播放器容器附近
        for kw in ["noplay", "player", "video", "script src"]:
            i = phtml.find(kw)
            if i > 0:
                print("\n   [%s] 上下文: %s" % (kw, phtml[max(0, i - 200):i + 400].replace("\n", " ")))


if __name__ == "__main__":
    main()
