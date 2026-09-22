import urllib.request, ssl

UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
REF = "https://www.cupfoxyy.com"
SEG = ("https://fengbao12.com/video/MISSIONgejubandeqianrusouchaguan/"
       "d577601c75a6/0000000.ts")

STREAM_TYPES = {
    0x01: "MPEG-1 Video", 0x02: "MPEG-2 Video", 0x03: "MPEG-1 Audio",
    0x04: "MPEG-2 Audio", 0x0F: "AAC ADTS", 0x11: "AAC LATM",
    0x1B: "H.264/AVC", 0x24: "H.265/HEVC", 0x42: "AVS",
    0x81: "AC-3", 0x87: "E-AC-3", 0x06: "PES private (AC3/DVB)", 0x15: "ID3",
}


def get(url, n=2 * 1024 * 1024):
    r = urllib.request.Request(url)
    r.add_header("User-Agent", UA)
    r.add_header("Referer", REF)
    resp = urllib.request.urlopen(r, timeout=40,
                                  context=ssl._create_unverified_context())
    return resp.read(n)


def main():
    b = get(SEG)
    print("下载字节:", len(b))
    # TS 每 188 字节一个同步字节 0x47
    ok = sum(1 for i in range(0, len(b) - 188, 188) if b[i] == 0x47)
    total = len(b) // 188
    print("同步字节命中: %d/%d" % (ok, total))
    # 找 PMT (PID 0 是 PAT)
    pids = {}
    for i in range(0, len(b) - 188, 188):
        if b[i] != 0x47:
            continue
        pid = ((b[i + 1] & 0x1F) << 8) | b[i + 2]
        pids[pid] = pids.get(pid, 0) + 1
    top = sorted(pids.items(), key=lambda x: -x[1])[:8]
    print("数据最多的 PID:", top)

    # PAT -> PMT PID
    pmt_pid = None
    for i in range(0, len(b) - 188, 188):
        if b[i] != 0x47:
            continue
        pid = ((b[i + 1] & 0x1F) << 8) | b[i + 2]
        if pid != 0:
            continue
        pusi = b[i + 1] & 0x40
        if not pusi:
            continue
        p = i + 4
        ptr = b[p]
        p += 1 + ptr
        if b[p] != 0x00:
            continue
        slen = ((b[p + 1] & 0x0F) << 8) | b[p + 2]
        q = p + 3
        end = q + slen - 4
        while q < end:
            prog = (b[q] << 8) | b[q + 1]
            pidv = ((b[q + 2] & 0x1F) << 8) | b[q + 3]
            print("  PAT: program=%d PMT pid=%d" % (prog, pidv))
            pmt_pid = pidv
            q += 4
        break

    if pmt_pid is None:
        print("!! 没找到 PAT")
        return
    # 解析 PMT
    for i in range(0, len(b) - 188, 188):
        if b[i] != 0x47:
            continue
        pid = ((b[i + 1] & 0x1F) << 8) | b[i + 2]
        if pid != pmt_pid:
            continue
        p = i + 4
        ptr = b[p]
        p += 1 + ptr
        if b[p] != 0x02:
            continue
        slen = ((b[p + 1] & 0x0F) << 8) | b[p + 2]
        q = p + 3 + 9  # 跳过 PCR_PID 等
        pilen = ((b[q - 2] & 0x0F) << 8) | b[q - 1]
        q += pilen
        end = p + 3 + slen - 4
        while q < end - 4:
            st = b[q]
            epid = ((b[q + 1] & 0x1F) << 8) | b[q + 2]
            eslen = ((b[q + 3] & 0x0F) << 8) | b[q + 4]
            print("  PMT: pid=%-5d stream_type=0x%02X %s" % (
                epid, st, STREAM_TYPES.get(st, "未知")))
            q += 5 + eslen
        break


if __name__ == "__main__":
    main()
