import urllib.request, ssl, re, collections, os

UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
OUT = os.path.dirname(os.path.abspath(__file__))


def get(url, referer=None, rng=None, timeout=20):
    req = urllib.request.Request(url)
    req.add_header("User-Agent", UA)
    if referer:
        req.add_header("Referer", referer)
    if rng:
        req.add_header("Range", rng)
    r = urllib.request.urlopen(req, timeout=timeout,
                               context=ssl._create_unverified_context())
    return r.status, r.read()


def main():
    url = "https://fengbao12.com/video/MISSIONgejubandeqianrusouchaguan/d577601c75a6/index.m3u8"
    ref = "https://www.cupfoxyy.com"
    st, b = get(url, ref)
    txt = b.decode("utf-8", "replace")
    p = os.path.join(OUT, "cupfox_playlist.m3u8")
    open(p, "w", encoding="utf-8").write(txt)
    print("saved", p, st, len(txt))

    lines = txt.split("\n")
    tags = collections.Counter()
    for l in lines:
        l = l.strip()
        if l.startswith("#"):
            tags[l.split(":")[0]] += 1
        elif l:
            tags["<segment>"] += 1
    for k, v in tags.items():
        print("  ", k, v)

    # 找出 adjump 附近的上下文
    idx = [i for i, l in enumerate(lines) if "adjump" in l]
    print("adjump lines:", len(idx))
    if idx:
        i = idx[0]
        print("--- 第一处 adjump 上下文 ---")
        for j in range(max(0, i - 6), min(len(lines), i + 6)):
            print("   %4d | %s" % (j, lines[j]))
    # 分片命名样式
    segs = [l.strip() for l in lines if l.strip() and not l.strip().startswith("#")]
    print("segment name samples:", segs[:3], segs[len(segs) // 2:len(segs) // 2 + 3])
    adsegs = [s for s in segs if "adjump" in s]
    print("ad segments sample:", adsegs[:3], " total", len(adsegs))
    # 不重复的分片目录
    dirs = collections.Counter(s.rsplit("/", 1)[0] for s in segs if "/" in s)
    for d, c in dirs.most_common(6):
        print("   dir", c, d)


if __name__ == "__main__":
    main()
