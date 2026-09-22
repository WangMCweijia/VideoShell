import urllib.request, ssl, sys

UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")


def get(url, referer=None, rng=None, timeout=15):
    req = urllib.request.Request(url)
    req.add_header("User-Agent", UA)
    if referer:
        req.add_header("Referer", referer)
    if rng:
        req.add_header("Range", rng)
    try:
        r = urllib.request.urlopen(req, timeout=timeout,
                                   context=ssl._create_unverified_context())
        return r.status, dict(r.headers), r.read()
    except Exception as e:
        return -1, {}, str(e).encode()


def main():
    url = "https://fengbao12.com/video/MISSIONgejubandeqianrusouchaguan/d577601c75a6/index.m3u8"
    ref = "https://www.cupfoxyy.com"
    for tag, rng in [("no-range", None), ("range-0-1023", "bytes=0-1023")]:
        st, h, b = get(url, ref, rng)
        print("====", tag, "HTTP", st)
        for k in ("Content-Type", "Content-Length", "Server", "Accept-Ranges",
                  "Content-Range"):
            if k in h:
                print("    ", k, "=", h[k])
        txt = b.decode("utf-8", "replace")
        lines = [l for l in txt.split("\n") if l.strip()]
        print("     raw bytes:", len(b), " lines:", len(lines))
        print("     head:", repr(txt[:180]))
        segs = [l for l in lines if not l.startswith("#")]
        print("     segments:", len(segs), "first:", segs[:2])
        print("     adjump count:", sum(1 for l in lines if "/adjump/" in l))
        print("     has ENDLIST:", "#EXT-X-ENDLIST" in txt,
              " STREAM-INF:", "#EXT-X-STREAM-INF" in txt)
        if segs:
            from urllib.parse import urljoin
            s0 = urljoin(url, segs[0])
            st2, h2, b2 = get(s0, ref, "bytes=0-1023")
            print("     first seg:", s0)
            print("     seg HTTP", st2, h2.get("Content-Type"), h2.get("Content-Range"))


if __name__ == "__main__":
    main()
