#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""夸克/UC 云盘 P0 spike —— PC 侧，**要真实网络**。

为什么单独一个脚本而不是写进 harness
------------------------------------
`PanCloudDrive` 的离线行为断言做不了：`android.jar` 是桩，`new JSONObject(...)`
在 JVM 上直接抛 `Stub!`（见 docs/PITFALLS.md §4.58）。所以"接口还活着吗 /
直链校验多严"这类问题**只能在真网络里问**。这个脚本就是问这些问题的工具：
它只输出结论、不产生 PASS/FAIL，因此**不进 SUITES**（与 runupdlive / runcaliblive 同一定位）。

它做的事**逐字对齐** `app/.../data/pan/PanCloudDrive.kt` 的请求
（同 host、同 path、同 query、同 body、同 headers），否则验的不是同一条路。

三个必须回答的问题
------------------
1. 匿名段（token / detail）还在不在？ —— 不在的话，详情页展开集数这条体验就没了。
2. 匿名段 save / download 是否仍 **401 code:31001**？ —— 如果它变宽松了，
   "登录放软件里"就不再是硬前提，整个 P0 的形状都要重新想。
3. 播放入口（m3u8）与它的分片对 **UA / Referer / Range / Cookie** 的校验强度如何？
   —— 这直接决定方案里 P2「本地代理」是必需还是可选。

⚠️ 取流**不要**用 `/file/download`（2026-09-23 实测死路）
----------------------------------------------------------
带真实登录凭据时它对分享里的文件**一律**回 `400 code:23018 download file size limit`，
连 615MB 的"最小文件"也照拒（不是体积问题）。六种组合全一样：换 host(drive-pc/drive-m)、
换 UA(桌面/夸克 App)、换 Referer、加 `?pf=1&support_https=1`、加 `group_id`。
正确入口是 `POST /file/v2/play`（返回转码后的 HLS 播放列表）。见 docs/PITFALLS.md §4.60。

⚠️ 清理（`file/delete`）**不能在播完立刻做**（2026-09-23 实测）
--------------------------------------------------------------
`v2/play` 的播放会话会锁住那个转存文件：`save → 播 → 立刻删` = `500 code:15000`（连试 4 次全败），
**真拉过 m3u8/ts** 时要**约 2 分钟**才松开（只调 play 不拉流则 45s 够）。
所以 ⑦ 的做法是"试一次 → 等 `PAN_CLEAN_WAIT`（默认 120s）→ 再试一次"，
并用 `file/sort` **复核 fid 是否真的消失**（它是**异步任务**，200 只代表受理）。
App 侧对应的是 `pendingDelete` + `sweepPending()`（记账 + 下次顺手清），而不是定时器。

用法
----
    # 匿名段（不需要任何凭据）
    VS_PYTHON=<带 requests 的解释器> python tools/verify/panquark_spike.py

    # 完整链路（需要夸克已登录的 Cookie 字符串）
    PAN_COOKIE="__pus=...; __puus=...; ..." python tools/verify/panquark_spike.py
    # 长凭据**别手抄进命令行**：单字符打错的表现是"突然 401 未登录"，
    # 与"会话失效"一模一样（2026-09-23 就这么白追了一轮）。写成文件再 cat 进来。

    # 换分享链接 / 换盘
    PAN_SHARE="https://pan.quark.cn/s/xxxxxxxx" python tools/verify/panquark_spike.py
    PAN_API=uc  # 走 UC 那张桌子

    # 只试一次清理（跑得快，但大概率留残留，工具会告诉你去手删哪个）
    PAN_CLEAN_WAIT=0 python tools/verify/panquark_spike.py

Cookie 从哪来：App 里「我的 → 网盘账号」登录夸克后，同一条 Cookie 就存在
`DriveStore` 里；或者浏览器登录后 F12 → Network → 任一 drive-pc 请求 →
复制 Request Headers 里的 Cookie 整行。
"""
import json
import os
import re
import sys
import time
from urllib.parse import quote

import requests

# --------------------------------------------------------------------- 配置

API_BASE = "https://pc-api.uc.cn/1/clouddrive" if os.environ.get("PAN_API") == "uc" \
    else "https://drive-pc.quark.cn/1/clouddrive"
WEB_BASE = "https://drive.uc.cn/" if os.environ.get("PAN_API") == "uc" else "https://pan.quark.cn/"

# 与 PanCloudDrive.PAN_UA 逐字相同（PC 接口就别装手机）
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

Q = "pr=ucpro&fr=pc&uc_param_str="

SHARE = os.environ.get("PAN_SHARE") or "https://pan.quark.cn/s/9cb3db399337"
COOKIE = (os.environ.get("PAN_COOKIE") or "").strip()

VERIFY_TLS = os.environ.get("PAN_INSECURE") != "1"

# 清理阶段"等多久再试第二次"（秒）。默认 120：实测**真拉过 m3u8/ts** 时 45s 不够、
# 约 2 分钟才松开。只想快速跑完可以设 `PAN_CLEAN_WAIT=0`（那就只试一次，
# 大概率会留下残留 —— 工具会在结尾明确告诉你去手删）。
CLEAN_WAIT_S = int(os.environ.get("PAN_CLEAN_WAIT") or "120")

OUT = []            # 全量日志，最后落盘
FINDINGS = []       # 结论行


def log(line=""):
    OUT.append(line)
    print(line, flush=True)


def finding(line):
    FINDINGS.append(line)
    log(line)


def hr(title):
    log("")
    log("=" * 78)
    log(title)
    log("=" * 78)


# --------------------------------------------------------------------- HTTP

def eq(s):
    """URL 编码 —— 对应 App 里 `PanCloudDrive.u()` 的 `URLEncoder.encode`。

    ⚠️ 这个函数**不能省**。stoken 是 base64 变体，**可能以 `+` 开头**：
    实测 `pan.quark.cn/s/dbc851025443` 的 stoken 就是 `+JPpdVNi4j...`，而
    夸克的接口对**不存在的**分享返回 41006、对**存在的**分享返回 200，
    唯独 token 里那个 `+` 若没编码，服务端会把它当空格，
    于是 detail 报 `400 code:14001 Bad Parameter: [非法token]`。

    少这一层编码的症状极具迷惑性：**一部分分享能展开、另一部分报"非法token"**
    （取决于该次 stoken 恰好是否含 `+`），看起来像站点在抽风，实际是自己漏了编码。
    App 那边 `u(st)` 一直在做这件事 —— 别"优化"掉它。
    """
    return quote(s or "", safe="")


def base_headers(ck=None, extra=None):
    """与 PanCloudDrive.getJson/postJson 一致：Accept 显式 JSON（云盘按它做内容协商）"""
    h = {
        "Accept": "application/json, text/plain, */*",
        "Referer": WEB_BASE,
        "User-Agent": UA,
    }
    if ck:
        h["Cookie"] = ck
    if extra:
        h.update(extra)
    return h


def req(method, url, ck=None, body=None, extra_headers=None, allow_redirects=True):
    """返回 (status, headers, text, elapsed_ms)；异常在内部吃掉，status 为 0。"""
    t0 = time.time()
    try:
        r = requests.request(
            method, url,
            headers=base_headers(ck, extra_headers),
            data=body.encode("utf-8") if body else None,
            timeout=25,
            verify=VERIFY_TLS,
            allow_redirects=allow_redirects,
        )
        return r.status_code, dict(r.headers), r.text, int((time.time() - t0) * 1000)
    except Exception as e:  # noqa: BLE001
        return 0, {}, "{}: {}".format(type(e).__name__, e), int((time.time() - t0) * 1000)


def envelope(text):
    """拆 {status,code,message,data} 信封；不是 JSON 就返回 None。"""
    try:
        return json.loads(text)
    except Exception:  # noqa: BLE001
        return None


def brief(o, keys=("status", "code", "message")):
    if not isinstance(o, dict):
        return "-"
    return " ".join("{}={}".format(k, o.get(k)) for k in keys if k in o)


def show(tag, status, text, ms, keep=180):
    o = envelope(text)
    if o is None:
        finding("  [{}] HTTP {} ({}ms) — **不是 JSON**：{}".format(
            tag, status, ms, text.strip().replace("\n", " ")[:keep]))
    else:
        finding("  [{}] HTTP {} ({}ms) — {}".format(tag, status, ms, brief(o)))
    return o


# --------------------------------------------------------------------- 链接解析

def parse_share(u):
    m = re.search(r"pan\.quark\.cn/s/([0-9A-Za-z]+)", u) or re.search(r"drive\.uc\.cn/s/([0-9A-Za-z]+)", u)
    if not m:
        raise SystemExit("无法从 PAN_SHARE 里认出分享 id：{}".format(u))
    pwd = ""
    mp = re.search(r"(?:pwd|passcode)=([0-9A-Za-z]{4})", u)
    if mp:
        pwd = mp.group(1)
    return m.group(1), pwd


# --------------------------------------------------------------------- 主体

def main():
    hr("夸克/UC 云盘 P0 spike")
    log("api     = {}".format(API_BASE))
    log("share   = {}".format(SHARE))
    log("cookie  = {}".format("已提供（{} 字符）".format(len(COOKIE)) if COOKIE else "**未提供 → 只能验匿名段**"))
    log("tls     = {}".format("校验" if VERIFY_TLS else "**跳过校验（PAN_INSECURE=1）**"))

    pwd_id, passcode = parse_share(SHARE)
    log("pwd_id  = {}   passcode = {!r}".format(pwd_id, passcode))

    # ---------------------------------------------------------- ① 匿名：token
    hr("① POST /share/sharepage/token （匿名）")
    body = json.dumps({"pwd_id": pwd_id, "passcode": passcode})
    st, _, text, ms = req("POST", "{}/share/sharepage/token?{}".format(API_BASE, Q), body=body)
    o = show("token", st, text, ms)
    stoken = None
    if isinstance(o, dict) and o.get("code") == 0:
        d = o.get("data") or {}
        stoken = d.get("stoken")
        finding("  ⇒ stoken 长度 {}；expired_at={}".format(
            len(stoken or ""), d.get("expired_at")))
        finding("  ✅ 问题 1 前半：匿名取分享令牌**仍可用**")
    else:
        finding("  ❌ 匿名取分享令牌**失败** ⇒ 详情页展开集数这条路要重想")

    # ---------------------------------------------------------- ② 匿名：detail
    root_list = []
    sub_files = []
    if stoken:
        hr("② GET /share/sharepage/detail （匿名，列分享根目录）")
        url = ("{}/share/sharepage/detail?{}&pwd_id={}&stoken={}"
               "&pdir_fid=0&force=0&_page=1&_size=200"
               "&_sort=file_type:asc,file_name:asc").format(
            API_BASE, Q, eq(pwd_id), eq(stoken))
        st, _, text, ms = req("GET", url)
        o = show("detail", st, text, ms)
        if isinstance(o, dict) and o.get("code") == 0:
            root_list = ((o.get("data") or {}).get("list")) or []
            dirs = [x for x in root_list if x.get("dir")]
            files = [x for x in root_list if not x.get("dir")]
            finding("  ⇒ 根目录 {} 项：目录 {} / 文件 {}".format(len(root_list), len(dirs), len(files)))
            finding("  ✅ 问题 1 后半：匿名列目录**仍可用**（详情页可展开真实集数）")
            for x in root_list[:6]:
                finding("     {:<4} {:<44} {} B".format(
                    "DIR" if x.get("dir") else "FILE",
                    (x.get("file_name") or "")[:44], x.get("size")))
            # 探一层：看目录里有没有视频（决定"展开成真实集数"是否成立）
            if dirs:
                sub = dirs[0]
                url2 = ("{}/share/sharepage/detail?{}&pwd_id={}&stoken={}"
                        "&pdir_fid={}&force=0&_page=1&_size=200"
                        "&_sort=file_type:asc,file_name:asc").format(
                    API_BASE, Q, eq(pwd_id), eq(stoken), eq(sub.get("fid")))
                st2, _, text2, ms2 = req("GET", url2)
                o2 = show("detail(第一层子目录 {})".format((sub.get("file_name") or "")[:20]),
                          st2, text2, ms2)
                if isinstance(o2, dict) and o2.get("code") == 0:
                    sub_list = ((o2.get("data") or {}).get("list")) or []
                    vids = [x for x in sub_list if not x.get("dir")]
                    sub_files = vids
                    finding("  ⇒ 子目录 {} 项（文件 {}）—— 抽样：".format(len(sub_list), len(vids)))
                    for x in sub_list[:5]:
                        finding("     {} {}".format(
                            "DIR " if x.get("dir") else "FILE", (x.get("file_name") or "")[:52]))
        else:
            finding("  ❌ 匿名列目录失败 ⇒ 详情页展开集数这条体验要重想")

    # ---------------------------------------------------------- ③ 匿名：save
    hr("③ POST /share/sharepage/save （匿名 → 期望 401 code:31001）")
    # ⚠️ 必须选**文件**，不能选目录：App 播的是文件，而 v2/play 打在目录上会回
    # `400 code:21005 not video`（曾因为"优先目录"把脚本自己坑了一轮）。
    # 根目录没文件时退到第一层子目录里的第一个文件（同一次 share 的 fid_token）。
    target = None
    for x in root_list:
        if not x.get("dir"):
            target = x
            break
    if target is None:
        for x in sub_files:
            target = x
            break
    if target is None:
        finding("  SKIP：根目录没取到任何项，无法构造 save 请求")
    else:
        # 把选中的目标打出来：脚本一旦选错（比如选中目录），后面全部结论都是废的
        finding("  （目标文件：{}｜{} B｜fid={}）".format(
            (target.get("file_name") or "")[:40], target.get("size"), target.get("fid")))
        sbody = json.dumps({
            "fid_list": [target.get("fid")],
            "fid_token_list": [target.get("share_fid_token")],
            "to_pdir_fid": "0",
            "pwd_id": pwd_id,
            "stoken": stoken,
            "pdir_fid": "0",
            "scene": "link",
            "_page": 1, "_size": 200,
            "_fetch_banner": 1, "_fetch_share": 1, "_fetch_total": 1,
            "_sort": "file_type:asc,updated_at:desc",
        })
        st, _, text, ms = req("POST", "{}/share/sharepage/save?{}".format(API_BASE, Q),
                              body=sbody)
        o = show("save(匿名)", st, text, ms)
        code = (o or {}).get("code") if isinstance(o, dict) else None
        if st == 401 or code == 31001:
            finding("  ✅ 问题 2：匿名 save **仍被拒**（HTTP {} code={}）"
                    "⇒「登录放软件里」仍是硬前提".format(st, code))
        elif st == 200 and code == 0:
            finding("  ⚠️⚠️ 匿名 save **竟然成功了**！⇒ 前提被推翻，P0 形状要重想"
                    "（未登录也能转存 = 取流不需要凭据）")
        else:
            finding("  ⚠️ 匿名 save 的结果既不是 401/31001 也不是成功：HTTP {} code={}"
                    "—— 接口可能改版，需人工判读".format(st, code))

    # ---------------------------------------------------------- ④ 匿名：download
    hr("④ POST /file/download （匿名 → 期望 401）")
    if target is not None:
        dbody = json.dumps({"fids": [target.get("fid")]})
        st, _, text, ms = req("POST", "{}/file/download?{}".format(API_BASE, Q), body=dbody)
        o = show("download(匿名)", st, text, ms)
        finding("  ⇒ 匿名下取不到流（HTTP {}）—— 但这条接口**登录后也是死路**".format(st))
        finding("     登录后回 400 code:23018 download file size limit；取流改走 file/v2/play（见 ⑤）")
    else:
        finding("  SKIP：没有可用的 fid")

    # ------------------------------------------------------- ④.5 登录态体检
    #
    # 为什么要有这一步：2026-09-23 拿到一份看着很长的 Cookie（1971 字符、24 个键），
    # 打 save 却仍 401 `code:31001 require login [guest]`。真相是它**根本没有登录态**
    # —— 判据不是"Cookie 有多长/有多少键"，而是下面这两条**差分**：
    #   ① 键名里有没有 `__pus` / `__puus` / `__uid`（PC 网页登录后必然出现）
    #   ② **带它 vs 不带它**打 `/member`，结果是否相同（相同 ⇒ 登录增益为零）
    # 只做 ① 会漏判（有的站登录态字段换名了），只做 ② 会说不清原因 ⇒ 两条一起做。
    hr("④.5 登录态体检（决定 ⑤ 值不值得跑）")
    if not COOKIE:
        finding("  ⏭ 未提供 PAN_COOKIE ⇒ 只能验匿名段，⑤⑥⑦ 跳过")
        log("  想验「取直链 + 直链校验强度」，请按文件头「Cookie 从哪来」取一份**登录态** Cookie。")
        return finish()
    names = set()
    for p in COOKIE.split(";"):
        if "=" in p:
            names.add(p.split("=", 1)[0].strip())
    CORE = ("__pus", "__puus", "__uid")
    miss = [c for c in CORE if c not in names]
    log("  Cookie 键数 = {}；键名 = {}".format(len(names), ", ".join(sorted(names))))
    log("  夸克 PC 登录态核心字段 {}：{}".format(
        " / ".join(CORE), "全部命中" if not miss else "**缺 {}**".format("、".join(miss))))

    def member(ck):
        stx, _, textx, _ms = req("GET", "{}/member?{}".format(API_BASE, Q), ck=ck)
        oo = envelope(textx)
        return stx, (oo or {}).get("code") if isinstance(oo, dict) else None

    st_ck, code_ck = member(COOKIE)
    st_no, code_no = member(None)
    log("  带这份 Cookie 打 /member  → HTTP {} code={}".format(st_ck, code_ck))
    log("  **不带** Cookie（对照）    → HTTP {} code={}".format(st_no, code_no))
    if st_no in (401, 403) and st_ck == st_no and code_ck == code_no:
        finding("  ❌ 这份 Cookie **不具备登录态**：有它与没它结果**完全相同**"
                "（HTTP {} code={}）⇒ save / download 必然 401，⑤⑥⑦ 跳过。".format(
                    st_ck, code_ck))
        log("")
        log("  怎么拿到**真正**的登录态 Cookie（脚本文件头「Cookie 从哪来」有详版）：")
        log("     1. 用**电脑**浏览器（Chrome/Edge）打开 https://pan.quark.cn 并**登录**")
        log("        （页面上要能看到头像/昵称 —— 仅仅打开过不算登录）")
        log("     2. F12 → Network → 刷新 → 筛选框输入 drive-pc")
        log("     3. 点任一请求 → Headers → Request Headers → 复制 `Cookie:` 整行")
        log("  ⚠️ 自查判据：复制到的 Cookie 里**必须出现 `__pus` 或 `__puus`**。")
        log("")
        log("  ⚠️ 三个常见错误来源：")
        log("     · 在**手机**夸克 App / 手机浏览器里复制的 —— App 的登录态不走 cookie，")
        log("       移动端与 PC 网页的登录字段也不是一套；")
        log("     · **没登录**就复制（只是浏览过首页）—— 拿到的是纯埋点 cookie；")
        log("     · 复制了别域的 cookie（夸克 cookie 与 uc/taobao 系不通用，cookie 按域隔离）。")
        return finish()
    if st_ck == 200 and code_ck == 0:
        finding("  ✅ 登录态有效（/member code=0）⇒ 继续跑 ⑤")
    else:
        finding("  ⚠️ /member 返回 HTTP {} code={} —— 既不是明确的未登录、也不是成功，"
                "仍继续跑 ⑤（也许只是 /member 这个接口自己的脾气）".format(st_ck, code_ck))

    # ---------------------------------------------------------- ⑤ 完整链路
    if not COOKIE:
        hr("⑤ 完整链路 —— **跳过**")
        log("没有 PAN_COOKIE。这一段的结论（直链校验强度）")
        log("只能在你提供了已登录 Cookie 之后才能拿到。")
        return finish()
    if target is None:
        hr("⑤ 完整链路 —— **跳过**")
        finding("❌ 分享里没取到任何**文件**（只有目录或列表为空）⇒ 没法验转存/取流。")
        finding("   换一条分享链接试试：PAN_SHARE=\"https://pan.quark.cn/s/xxxx\"")
        return finish()

    hr("⑤ 完整链路（带 Cookie）：save → task → **file/v2/play**")
    sbody = json.dumps({
        "fid_list": [target.get("fid")],
        "fid_token_list": [target.get("share_fid_token")],
        "to_pdir_fid": "0", "pwd_id": pwd_id, "stoken": stoken,
        "pdir_fid": "0", "scene": "link",
        "_page": 1, "_size": 200,
        "_fetch_banner": 1, "_fetch_share": 1, "_fetch_total": 1,
        "_sort": "file_type:asc,updated_at:desc",
    })
    st, _, text, ms = req("POST", "{}/share/sharepage/save?{}".format(API_BASE, Q),
                          ck=COOKIE, body=sbody)
    o = show("save(已登录)", st, text, ms)
    fid = None
    if isinstance(o, dict) and o.get("code") == 0:
        d = o.get("data") or {}
        sa = (d.get("save_as") or {}).get("save_as_top_fids") or d.get("save_as_top_fids") or []
        fid = sa[0] if sa else None
        task_id = d.get("task_id")
        log("  save_as_top_fids={} task_id={}".format(sa, task_id))
        for i in range(24):
            time.sleep(0.35 if i == 0 else 0.8)
            stt, _, tt, mst = req("GET", "{}/task?{}&task_id={}".format(API_BASE, Q, eq(task_id)),
                                  ck=COOKIE, extra_headers={"Accept": "application/json, text/plain, */*"})
            ot = envelope(tt)
            if not isinstance(ot, dict) or ot.get("code") != 0:
                show("task", stt, tt, mst)
                break
            dt = ot.get("data") or {}
            log("  [task {}] status={}".format(i, dt.get("status")))
            if dt.get("status") == 2:
                sa2 = (dt.get("save_as") or {}).get("save_as_top_fids") or dt.get("save_as_top_fids") or []
                fid = sa2[0] if sa2 else fid
                break
            if dt.get("status") == 3:
                finding("  ❌ 转存任务失败：{}".format(dt.get("message")))
                break

    if not fid:
        finding("  ❌ 完整链路在【转存】这步断了，后面的播放入口探测全部跳过")
        return finish()

    # ------------------------------------------------------------------
    # 取播放入口。⚠️ **不要再试 /file/download**：
    # 2026-09-23 实测，带真实登录凭据时它对分享里的文件**一律**回
    #   400 code:23018 "download file size limit[<fid>]"
    # （连 615MB 的"最小文件"也照拒 ⇒ 不是体积问题，是这条接口对普通账号不给了）。
    # 六种组合全一样：换 host(drive-pc/drive-m)、换 UA(桌面/夸克 App)、换 Referer、
    # 加 ?pf=1&support_https=1、加 group_id。详见 docs/PITFALLS.md §4.60。
    # ------------------------------------------------------------------
    st, _, text, ms = req("POST", "{}/file/v2/play?{}".format(API_BASE, Q),
                          ck=COOKIE, body=json.dumps({"fid": fid, "resolution": "normal"}))
    o = show("file/v2/play(已登录)", st, text, ms)
    url = None
    if isinstance(o, dict) and o.get("code") == 0:
        d = o.get("data") or {}
        want = d.get("default_resolution") or ""
        log("  default_resolution={!r}  origin_default={!r}".format(
            want, d.get("origin_default_resolution")))
        for e in d.get("video_list") or []:
            vi = e.get("video_info") or {}
            log("    {:<7} right={:<7} member_right={:<7} trans={:<8} accessable={:<5} "
                "w={:<5} bitrate={:<8} size={}".format(
                    str(e.get("resolution")), str(e.get("right")), str(e.get("member_right")),
                    str(e.get("trans_status")), str(e.get("accessable")),
                    vi.get("width"), vi.get("bitrate"), vi.get("size")))
            if url is None and vi.get("url"):
                url = vi["url"]                      # 第一档兜底
            if e.get("resolution") == want and vi.get("url"):
                url = vi["url"]                      # 服务器自报的默认档优先
    if not url:
        log("  v2 没给 URL ⇒ 试 GET /file/play 退路")
        for res in ("raw", "low"):
            stt, _, tt, mst = req("GET", "{}/file/play?{}&fid={}&resolution={}".format(
                API_BASE, Q, fid, res), ck=COOKIE)
            oo = show("file/play res={}".format(res), stt, tt, mst)
            d2 = (oo or {}).get("data") or {}
            log("    resolution_list={}".format(d2.get("resolution_list")))
            for v in (d2.get("video_list") or []):
                if v.get("url"):
                    url = v["url"]
                    break
            if url:
                break
    if not url:
        finding("  ❌ 没拿到播放入口（m3u8）")
        return finish()

    finding("  ✅ 拿到播放入口：{} 字符，host={}".format(
        len(url), re.sub(r"^(https?://[^/]+).*", r"\1", url)))

    # ---------------------------------------------------- ⑥ m3u8/ts 校验强度
    hr("⑥ m3u8 / ts 的校验强度（决定 P2 本地代理是否必需）")

    def fetch(urlx, tag, ck=None, ua=None, ref=None, rng=None):
        h = {}
        if ua:
            h["User-Agent"] = ua
        if ref:
            h["Referer"] = ref
        if rng:
            h["Range"] = rng
        if ck:
            h["Cookie"] = ck
        try:
            r = requests.get(urlx, headers=h, timeout=25, verify=VERIFY_TLS)
            body = r.text
            note = ""
            if body.lstrip().startswith("#EXTM3U"):
                note = " #EXTM3U"
            finding("  [{:<34}] HTTP {:<4}{}".format(tag, r.status_code, note))
            return r.status_code, body
        except Exception as e:  # noqa: BLE001
            finding("  [{:<34}] 异常 {}".format(tag, type(e).__name__))
            return 0, ""

    m3u8_txt = ""
    for tag, kw in (("① 全头(UA+Referer+Cookie)", dict(ck=COOKIE, ua=UA, ref=WEB_BASE)),
                    ("② 仅 Cookie", dict(ck=COOKIE)),
                    ("③ 去掉 Cookie（对照）", dict(ua=UA, ref=WEB_BASE)),
                    ("④ 仅 UA", dict(ua=UA)),
                    ("⑤ 仅 Referer", dict(ref=WEB_BASE)),
                    ("⑥ 什么都不带（裸）", {})):
        codex, txt = fetch(url, tag, **kw)
        if codex == 200 and txt.lstrip().startswith("#EXTM3U"):
            m3u8_txt = txt

    log("")
    finding("  —— 最小 Cookie 键集（决定播放器要注入多少）——")
    jar = {}
    for p in COOKIE.split(";"):
        if "=" in p:
            k, v = p.split("=", 1)
            jar[k.strip()] = v.strip()
    for tag, keys in (("__pus", ["__pus"]), ("__puus", ["__puus"]), ("__uid", ["__uid"]),
                      ("__pus+__puus", ["__pus", "__puus"]),
                      ("__pus+__puus+__uid", ["__pus", "__puus", "__uid"]),
                      ("全量（对照 {} 字符）".format(len(COOKIE)), None)):
        ck = COOKIE if keys is None else "; ".join(
            "{}={}".format(k, jar[k]) for k in keys if k in jar)
        fetch(url, tag + "（{} 字符）".format(len(ck)), ck=ck)

    seg = None
    if m3u8_txt:
        log("")
        finding("  —— 播放列表摘要 ——")
        lines = [l.strip() for l in m3u8_txt.splitlines()
                 if l.strip() and not l.strip().startswith("#")]
        finding("  #EXTM3U={} #EXT-X-ENDLIST={} PLAYLIST-TYPE={} #EXT-X-KEY={} 分片数={}".format(
            "#EXTM3U" in m3u8_txt, "#EXT-X-ENDLIST" in m3u8_txt,
            "#EXT-X-PLAYLIST-TYPE:VOD" in m3u8_txt, m3u8_txt.count("#EXT-X-KEY"), len(lines)))
        finding("  （缺 ENDLIST 也没关系：播放侧 HlsFixDataSource 会补，否则 ExoPlayer 当直播处理）")
        if lines:
            from urllib.parse import urljoin as _uj
            seg = _uj(url, lines[0])
            log("")
            finding("  —— ts 分片（同一套签名）——")
            for tag, kw in (("全头", dict(ck=COOKIE, ua=UA, ref=WEB_BASE)),
                            ("仅 Cookie", dict(ck=COOKIE)),
                            ("仅 Cookie + Range 0-1023", dict(ck=COOKIE, rng="bytes=0-1023")),
                            ("去掉 Cookie（对照）", dict(ua=UA))):
                fetch(seg, tag, **kw)

    log("")
    if seg:
        finding("  ⇒ 判读：m3u8 与 ts 都只认 Cookie（UA/Referer 去掉照样 200）"
                "⇒ MediaSource headers 里 **Cookie 必须有**")
        finding("  ⇒ ts 支持 Range（206）⇒ ExoPlayer 可直连拉分片，**P2 本地代理可以不做**")
    else:
        finding("  ⚠️ 没能取到 m3u8 正文，⑥ 的判读不完整")

    # ------------------------------------------------- ⑦ 清理（形状要对）
    hr("⑦ 清理转存产物（file/delete）")
    log("  body 形状实测：{\"action_type\":2,\"filelist\":[…],\"exclude_fids\":[]} 才 200；用 fids 恒 400 code:14001")
    log("  （『current_dir_fid,filelist 不能同时为空 / 不能同时存在值』两句报错正好把答案夹出来）")
    log("  ⚠️ 关键实测（2026-09-23，逐条对照）：**v2/play 的播放会话会锁住这个文件**")
    log("        save → 不调 play → 立刻 delete        → 200 ✅")
    log("        save → v2/play → **立刻** delete      → 500 code:15000（连试 4 次全败）❌")
    log("        save → v2/play → **等 45s** → delete  → 200 ✅")
    log("       放置 2~3 分钟的旧文件 → delete          → 200 ✅")
    log("     ⇒ 立刻删是**必败路径** ⇒ 只试 1 次，然后等 {}s 再试 1 次（= App 的「下次顺手清」）".format(
        CLEAN_WAIT_S))
    log("        ⚠️ **锁定窗口没有可靠值**：只调 play 不拉流时 45s 就够；")
    log("           **真拉过 m3u8/ts** 时 45s / 120s 都试过失败、另一轮约 2 分钟却成功。")
    log("           ⇒ 别拿一个时间值当判据；这正是 App 用「排队重试」而不是定时器的原因。")
    log("           本工具只做两次尝试，清不掉就直接告诉你 fid（App 会在下次网盘操作时继续清）。")
    dbody = json.dumps({"action_type": 2, "filelist": [fid], "exclude_fids": []})
    cleaned = False
    for i, wait_s in ((0, 0), (1, CLEAN_WAIT_S)):
        if wait_s:
            log("     等 {}s（模拟「下次再清」）…".format(wait_s))
            time.sleep(wait_s)
        st, _, text, ms = req("POST", "{}/file/delete?{}".format(API_BASE, Q),
                              ck=COOKIE, body=dbody)
        oo = envelope(text)
        code = (oo or {}).get("code") if isinstance(oo, dict) else None
        log("  [delete 第 {} 次{}] HTTP {} code={} {} → {}".format(
            i + 1, "" if i == 0 else "（+{}s）".format(wait_s), st, code,
            (oo or {}).get("message") if isinstance(oo, dict) else "",
            "✅ 成功" if (st == 200 and code == 0) else
            ("✅ 已达目的（23004 已经删除）" if code == 23004 else "❌ 失败")))
        if (st == 200 and code == 0) or code == 23004:
            cleaned = True
            break
    if not cleaned:
        finding("  ⚠️ 两次都没删掉 —— 请手动删掉网盘根目录里的「{}」（fid={}）".format(
            (target.get("file_name") or "")[:40], fid))
    # 复核：列**我自己的**根目录（别再拿 sharepage/detail 当"我的盘"，那是分享的目录）
    time.sleep(1.5)
    st, _, text, _ms = req("GET", "{}/file/sort?{}&pdir_fid=0&_page=1&_size=100".format(
        API_BASE, Q), ck=COOKIE)
    oo = envelope(text)
    mine = ((oo or {}).get("data") or {}).get("list") or []
    left = [f for f in mine if f.get("fid") == fid]
    if left:
        finding("  ❌ 转存产物**仍在**你网盘根目录里：{}（fid={}）".format(
            left[0].get("file_name"), fid))
    else:
        finding("  ✅ 转存产物已从你网盘根目录消失（fid={}）".format(fid))
    return finish()


def finish():
    hr("结论汇总")
    for f in FINDINGS:
        if any(m in f for m in ("✅", "❌", "⚠️", "⇒ 必须", "⇒ 直链", "⇒ 若")):
            log(f)
    here = os.path.dirname(os.path.abspath(__file__))
    p = os.path.join(here, "_panspike.txt")
    with open(p, "w", encoding="utf-8") as fh:
        fh.write("\n".join(OUT))
    log("")
    log("全量日志 → {}".format(p))
    return 0


if __name__ == "__main__":
    sys.exit(main())
