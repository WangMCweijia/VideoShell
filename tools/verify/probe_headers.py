import urllib.request, ssl

UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
REF = "https://www.cupfoxyy.com"
PL = ("https://fengbao12.com/video/MISSIONgejubandeqianrusouchaguan/"
      "d577601c75a6/index.m3u8")
SEG = ("https://fengbao12.com/video/MISSIONgejubandeqianrusouchaguan/"
       "d577601c75a6/0000000.ts")

# 两组请求头：一组是 OkHttp 自检栈实际发的，一组是 ExoPlayer DefaultHttpDataSource 实际发的
OKHTTP = {
    "User-Agent": UA,
    "Accept": "*/*",
    "Accept-Encoding": "gzip",
    "Referer": REF,
}
EXO = {
    "User-Agent": UA,
    "Accept-Encoding": "identity",
    "Referer": REF,
}

CASES = [
    ("playlist  ExoPlayer栈(identity,无Accept,无Range)", PL, EXO, None),
    ("playlist  OkHttp栈(gzip,Accept:*,Range)", PL, OKHTTP, "bytes=0-1023"),
    ("segment   ExoPlayer栈(identity,无Accept,无Range)", SEG, EXO, None),
    ("segment   OkHttp栈(gzip,Accept:*,Range)", SEG, OKHTTP, "bytes=0-1023"),
    ("playlist  ExoPlayer栈 + Range(seek 时)", PL, EXO, "bytes=0-1023"),
]


def req(url, headers, rng):
    r = urllib.request.Request(url)
    for k, v in headers.items():
        r.add_header(k, v)
    if rng:
        r.add_header("Range", rng)
    try:
        resp = urllib.request.urlopen(r, timeout=20,
                                      context=ssl._create_unverified_context())
        body = resp.read(2048)
        return resp.status, dict(resp.headers), body
    except Exception as e:
        return -1, {}, str(e).encode()


for name, url, hdrs, rng in CASES:
    st, h, b = req(url, hdrs, rng)
    print("%-52s HTTP %-5s ct=%s len=%s" % (
        name, st, h.get("Content-Type"), h.get("Content-Length")))
    if st < 0:
        print("      ->", b[:200])
