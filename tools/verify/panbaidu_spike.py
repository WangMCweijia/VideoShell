#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""百度网盘（P1）spike —— PC 侧，**要真实网络**。

为什么先有它、才有 `PanBaidu.kt`
--------------------------------
方案 §10 写死了一条纪律：**不要先写 9 个 Provider 再验证**；P1 的交付判据是
"每个盘的登录与播放**各有一条实测记录**"。百度是那个最该先做的 —— 依据不是"它有名"，
而是**我们自己的样本里有它**：`tools/verify/samples/panshare/ky_detail.html`（快映）的
`data-clipboard-text` 里，夸克旁边挂的就是 `https://pan.baidu.com/s/1P4_20eORxopHzDUgW9weew?pwd=6107`。

⚠️ **但那条样本链接已经死了**（2026-09-26 实测：分享页是错误页 `errno=145`）。
它曾经导致一个**假结论**：错误页里也有 `"file_list":[]`，而旧判据是
`'"file_list"' in text` ⇒ 一条失效分享被报告成"页面内嵌了根目录，体验最好的一条路"。
现在判据改成"**非空** file_list + 页面不是错误页"，并且**默认链接换成了现网可用的一条**
（摘自玩偶站详情页 `data-clipboard-text`）。

**素材会腐化，一条链接不是长期资产。** 默认失效时不要怀疑脚本，去现网取一条：
打开 `https://www.wogg.live`（或任一 `csp_PanWebShare` 族站点）→ 任一详情页 →
`data-clipboard-text` 里的 `pan.baidu.com/s/…?pwd=…` —— 然后
`BAIDU_SHARE='<那条>' python tools/verify/panbaidu_spike.py`。

**已实测的结论（2026-09-26，匿名段）** —— 见方案 §6.3 的补充表：
`root=1` 是**必需**参数（缺它一律 `errno=-21`）；有了它，**匿名就能列目录**（含子目录）。
`dlink` 一段尚未实测（取样时 3 条分享根两层都只有目录，随后 IP 被限流）。

它与 `PanCloudDrive` 的离线断言是**互补**的，不是替代：
`android.jar` 是桩、`new JSONObject` 抛 `Stub!`（PITFALLS §4.58），所以"这个接口还活着吗 /
直链校验多严 / 该带哪几个 cookie"这类问题**只能问真网络**。本脚本只输出结论、
不产生 PASS/FAIL ⇒ 不进 SUITES（与 `panquark_spike.py` 同一定位）。

⚠️ 本脚本里的端点/参数**全部是待确认的假设**，不是实测结论
--------------------------------------------------------
写 `panquark_spike.py` 时那些形状是已经打通过的，脚本是"复验"；百度这边**还没有**实测记录，
所以这里每一段都并列候选形状、并把**原始信封逐字打出来**。它的作用就是把 §6.3 表格里
"百度：`s/1{code}?pwd=` → `dlink`，凭据 Cookie + `bdstoken`"这一行，一次问清楚。

**跑完请把结论段贴回来** —— 我按实测到的形状写 `PanBaidu.kt` + 源码级守卫，
而不是按记忆猜。猜出来的 Provider 在真机上只会表现为"点了没反应"，那是本项目最忌讳的形状。

三个必须回答的问题
------------------
1. **匿名段到哪一步为止？** —— ✅ **已答（2026-09-26）**：分享页匿名可取（`shareid`/`share_uk`
   都在 `window.yunData` 里），有提取码时先 `POST /share/verify` 换 `BDCLND`，之后
   **匿名列目录成立**（`root=1` 必需，含子目录）⇒ 详情页能像夸克/UC 一样匿名展开真实集数。
   ⚠️ 脚本里的 `dlink` 段仍是**并列候选**，还没有一次成功记录。
2. **取直链要什么？** 匿名能不能拿 `dlink`；不能的话，是 Cookie 就够，还是必须
   Cookie **+ `bdstoken`**。这决定它是"Cookie 类"（复用已建好的 WebView 登录 + 自动保存）
   还是"账密/token 类"（要另做一套）。—— ⏳ **待答**，需要 `BAIDU_COOKIE=` 才能跑。
3. **`dlink` 的校验强度如何？** 分片/整文件对 UA / Referer / Range / Cookie 各是什么反应。
   这决定 `MediaSource.headers` 里必须放什么，以及 P2 本地代理是否必需。—— ⏳ **待答**。

用法
----
    # 只跑匿名段（**不需要任何凭据**，可以先跑这一段）
    VS_PYTHON=<带 requests 的解释器> python tools/verify/panbaidu_spike.py

    # 完整链路（需要**已登录**的百度 Cookie）
    BAIDU_COOKIE="BDUSS=...; STOKEN=...; BAIDUID=..." python tools/verify/panbaidu_spike.py
    # 长凭据**别手抄进命令行**：单字符打错的表现是"突然未登录"，与会话失效一模一样。
    # 写成文件再喂进来：
    #   BAIDU_COOKIE="$(cat /path/to/baidu_cookie.txt)" python tools/verify/panbaidu_spike.py

    # 换分享链接 / 跳过 TLS 校验（抓包排错时用）
    BAIDU_SHARE="https://pan.baidu.com/s/1xxxx?pwd=abcd" python tools/verify/panbaidu_spike.py
    BAIDU_INSECURE=1 python tools/verify/panbaidu_spike.py

Cookie 从哪来（**必须是登录态的**）
----------------------------------
1. 用**电脑**浏览器打开 https://pan.baidu.com 并**登录**（页面上要能看到头像/昵称 ——
   仅仅打开过不算登录）；
2. F12 → Network → 刷新 → 点任一 `pan.baidu.com` 请求；
3. Headers → Request Headers → 复制 `Cookie:` 整行。
自查判据：里面**必须出现 `BDUSS`**（百度登录态的会话键；只有 `BAIDUID` 是游客）。
也可以在 App 里「我的 → 网盘账号 → 百度网盘」登录后取，但那要等 `PanBaidu.kt` 写出来 ——
本文档的时序就是"先有实测、再有 Provider"。

⚠️ 别在**手机**百度 App 里抓：App 的登录态不走 cookie，移动端与 PC 网页不是一套。
"""
import json
import os
import re
import sys
import time
from urllib.parse import quote

import requests

# --------------------------------------------------------------------- 配置

BAIDU = "https://pan.baidu.com"
WEB = BAIDU + "/"

# 默认取一条**现网可用**的分享（2026-09-26 从玩偶站 `www.wogg.live` 详情页的
# `data-clipboard-text` 里取）。旧的快映样本链接已失效（错误页 errno=145）——
# 它正是"空 file_list 被当成有内容"那个假结论的来源，见文件头。
SHARE = os.environ.get("BAIDU_SHARE") or \
    "https://pan.baidu.com/s/1kzwNTOAPLJqLbGvIU_e3Kg?pwd=wogg"

COOKIE = (os.environ.get("BAIDU_COOKIE") or "").strip()
VERIFY_TLS = os.environ.get("BAIDU_INSECURE") != "1"

# 与 PanCloudDrive.PAN_UA 逐字相同：用的是 **PC 接口**，就别装手机
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

# 百度要求 PC 网页的 XHR 带这几个身份参数（`app_id=250528` 是"网页版"这个产品）
APP_ID = "250528"
CHANNEL = "chunlei"

OUT = []          # 全量日志，最后落盘
FINDINGS = []     # 结论行

# 会话：`/share/verify` 会用 `Set-Cookie: BDCLND=<提取码换来的票据>` 放行，
# 后续列目录必须带上它 ⇒ 必须有一个跨请求保持 cookie 的会话。
S = requests.Session()


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
    """URL 编码 —— 对应 App 里 `PanCloudDrive.u()`。

    分享 id / 提取码里出现 `+` `/` `=` 一类字符时，手拼 URL 不编码就会被服务端
    当成另一个值（夸克那边的症状是 `400 code:14001 非法token`，见 PITFALLS §4.59）。
    """
    return quote(s or "", safe="")


def req(method, url, ck=None, body=None, extra=None, jar=True, allow_redirects=True):
    """返回 (status, headers, text, elapsed_ms)。

    `ck`   显式 Cookie 头（用来做"带了 / 不带"的**差分**；给了它就不再吃会话 cookie）
    `jar`  False ⇒ 连会话里的 cookie 也不带（真正裸请求，做对照用）
    """
    h = {
        "User-Agent": UA,
        "Referer": WEB,
        "Accept": "application/json, text/plain, */*",
        # `identity`：本脚本常在**带正向代理**的沙箱里跑，而代理会把 body 解压后
        # 仍保留 `Content-Encoding: gzip` ⇒ requests 二次解压报
        # `ContentDecodingError: incorrect header check`，表现成"每个请求都 HTTP 0"。
        # 明确不要压缩即可绕开（与站点行为无关，纯属跑测环境）。
        "Accept-Encoding": "identity",
    }
    if ck:
        h["Cookie"] = ck
    if extra:
        h.update(extra)
    cookies = None if (ck or not jar) else S.cookies
    t0 = time.time()
    try:
        r = S.request(method, url, headers=h, data=body,
                      cookies=cookies, timeout=25, verify=VERIFY_TLS,
                      allow_redirects=allow_redirects)
        return r.status_code, dict(r.headers), r.text, int((time.time() - t0) * 1000)
    except Exception as e:  # noqa: BLE001
        return 0, {}, "{}: {}".format(type(e).__name__, e), int((time.time() - t0) * 1000)


def env(text):
    """拆 JSON 信封（百度是 `{"errno":0,...}`，没有夸克那种 `{status,code,message}`）。"""
    try:
        o = json.loads(text)
        return o if isinstance(o, dict) else None
    except Exception:  # noqa: BLE001
        return None


def brief(o, keys=("errno", "errmsg", "error_code", "request_id")):
    if not isinstance(o, dict):
        return "-"
    return " ".join("{}={}".format(k, o.get(k)) for k in keys if k in o)


def show(tag, st, text, ms, keep=200):
    """打一行"这一步 HTTP 怎样 + 信封怎样"，并把信封返回给调用方判读。"""
    o = env(text)
    if o is None:
        finding("  [{}] HTTP {} ({}ms) — **不是 JSON**（{} 字符）：{}".format(
            tag, st, ms, len(text), text.strip().replace("\n", " ")[:keep]))
    else:
        finding("  [{}] HTTP {} ({}ms) — {}".format(tag, st, ms, brief(o)))
    return o


def setcookie_keys(headers):
    """把响应里下发的 cookie **键名**列出来（值脱敏）—— 登录标记键就是这么找出来的。"""
    raw = headers.get("Set-Cookie") or ""
    if not raw:
        return []
    keys = []
    for part in re.split(r",(?=[^;,=]+=)", raw):
        m = re.match(r"\s*([^=;]+)=", part)
        if m:
            keys.append(m.group(1).strip())
    return keys


# --------------------------------------------------------------------- 链接解析

def parse_share(u):
    """`pan.baidu.com/s/1{short}?pwd=xxxx` → (short, pwd)。

    ⚠️ 那个 `1` 是 surl 的**固定前缀**：网页路径里是 `/s/1XXXX`，而接口参数 `surl`
    要的是**去掉 1 的 `XXXX`** —— 这两者弄混过一次，症状是"分享明明活着却被报失效"。
    """
    m = re.search(r"pan\.baidu\.com/(?:s/1|share/init\?surl=)([0-9A-Za-z_\-]+)", u)
    if not m:
        raise SystemExit("无法从 BAIDU_SHARE 里认出分享 id：{}".format(u))
    mp = re.search(r"(?:pwd|password|passcode)=([0-9A-Za-z]{4})", u)
    return m.group(1), (mp.group(1) if mp else "")


def pick(text, *pats):
    """在页面里按候选正则找第一个命中 —— 命中**哪一个**也是结论（形状会变）。"""
    for p in pats:
        m = re.search(p, text)
        if m:
            return m.group(1), p
    return None, None


# --------------------------------------------------------------------- 主体

def main():
    hr("百度网盘 P1 spike")
    log("share   = {}".format(SHARE))
    log("cookie  = {}".format(
        "已提供（{} 字符）".format(len(COOKIE)) if COOKIE else "**未提供 → 只验匿名段**"))
    log("tls     = {}".format("校验" if VERIFY_TLS else "**跳过校验（BAIDU_INSECURE=1）**"))
    log("ua      = {}".format(UA))

    short, pwd = parse_share(SHARE)
    log("surl    = {}   pwd = {!r}".format(short, pwd))

    # ------------------------------------------------------- ① 分享页（匿名）
    hr("① GET /s/1{surl} （匿名，取分享页）".format(surl=short))
    url1 = "{}/s/1{}".format(BAIDU, short)
    st, hd, text, ms = req("GET", url1)
    finding("  [sharepage] HTTP {} ({}ms) — {} 字符；最终地址 {}".format(
        st, ms, len(text), hd.get("Content-Location") or url1))
    log("  下发 cookie 键名 = {}".format(", ".join(setcookie_keys(hd)) or "（无）"))

    # 页面级的四种"到此为止"，必须先分开 —— 它们的处置完全不同
    gate = None
    for tag, pat in (("分享已失效/被取消", r"分享的文件已经被取消|你访问的页面不存在|链接错误"),
                     ("需要提取码", r"请输入提取码|access_code|请输入密码"),
                     ("需要验证码", r"请输入验证码|vcode_str")):
        if re.search(pat, text):
            gate = tag
            break
    if gate:
        finding("  ⚠️ 页面看起来是【{}】".format(gate))
    else:
        finding("  ⇒ 页面没出现失效/提取码/验证码 的标记")

    # 提取码：`share/verify` 换 BDCLND，之后同一会话才能看到真实目录
    if gate == "需要提取码" or pwd:
        hr("①.5 POST /share/verify （用提取码换放行票据）")
        ts = int(time.time())
        vurl = ("{}/share/verify?surl={}&t={}&channel={}&web=1&app_id={}"
                "&clienttype=0").format(BAIDU, eq(short), ts, CHANNEL, APP_ID)
        vbody = "pwd={}&vcode=&vcode_str=".format(eq(pwd))
        stv, hdv, textv, msv = req("POST", vurl, body=vbody)
        ov = show("share/verify", stv, textv, msv)
        log("  下发 cookie 键名 = {}".format(", ".join(setcookie_keys(hdv)) or "（无）"))
        if isinstance(ov, dict) and ov.get("errno") == 0:
            finding("  ✅ 提取码通过（errno=0）⇒ 会话里应有放行票据")
            # 重新取一次分享页：这次才可能是带着目录的那一份
            st, hd, text, ms = req("GET", url1)
            log("  重新取分享页：HTTP {} / {} 字符".format(st, len(text)))
        else:
            finding("  ❌ 提取码没通过：{} —— 后面的展开结论都会是废的，先解决这一步".format(
                brief(ov) if isinstance(ov, dict) else "非 JSON"))

    # 页面里到底有没有可直接用的字段（这决定"匿名能不能展开集数"）
    hr("①.6 分享页里能直接抠到什么（决定匿名展开集数是否成立）")
    fields = {}
    for name, pats in (
        ("shareid", (r'"shareid"\s*:\s*"?(\d+)', r"shareid=(\d+)")),
        ("uk", (r'"share_uk"\s*:\s*"?(\d+)', r'"uk"\s*:\s*"?(\d+)',
                r'"uk"\s*:\s*(\d+)')),
        ("bdstoken", (r'"bdstoken"\s*:\s*"([0-9A-Za-z]+)"',)),
        ("sign", (r'"sign"\s*:\s*"([0-9A-Za-z]+)"',)),
        ("timestamp", (r'"timestamp"\s*:\s*(\d+)',)),
        ("isdir", (r'"isdir"\s*:\s*(\d+)',)),
        ("server_filename", (r'"server_filename"\s*:\s*"(.*?)"',)),
        ("errno(页面内)", (r'"errno"\s*:\s*(-?\d+)',)),
    ):
        v, p = pick(text, *pats)
        fields[name] = v
        log("  {:<16} = {}".format(name, ("{!r}  ← {}".format(v, p) if v else "**没抠到**")))

    log("  页面里有 `yunData`          = {}".format(bool(re.search(r"yunData\s*=", text))))
    # ⚠️ `"file_list":[` **也出现在错误页上，只是空数组** —— 2026-09-26 实测：
    # 样本那条分享早已失效，页面是 `share_page_type:"error" + errno:145`，而
    # `file_list":[]` 照样能被 `contains` 命中 ⇒ 旧写法把它读成"页面内嵌了根目录"，
    # 于是一条**已经死掉的**分享被报告成"体验最好的一条路"。判据必须要求**非空**。
    has_fl = bool(re.search(r'"file_list"\s*:\s*\[\s*\{', text))
    log("  页面里有**非空** file_list = {}（空数组不算）".format(has_fl))
    log("  页面里有 `share/list` 字样   = {}".format("share/list" in text))

    # 页面自己说它是什么（这一条比抠字段重要：错误页上 shareid/uk **照样在** yunData 里，
    # 所以"抠到了 shareid + uk"根本不构成"分享可用"的证据）
    page_err = bool(re.search(r'"share_page_type"\s*:\s*"error"', text))
    log("  页面是**错误页**           = {}".format(page_err))
    if page_err:
        finding("  ⛔ 页面是**错误页**（errno={}）⇒ 这条分享**已失效**".format(
            fields.get("errno(页面内)")))
        finding("     ⚠️ 下面关于分享内容的一切结论**全部作废** —— 请换一条**当前有效**的")
        finding("     分享：`BAIDU_SHARE='https://pan.baidu.com/s/1xxxx?pwd=xxxx' "
                "python tools/verify/panbaidu_spike.py`")
    if fields.get("shareid") and fields.get("uk"):
        finding("  ℹ️ 分享页给了 shareid + uk —— 但注意它对**错误页也成立**，"
                "所以它只是『构造得出请求』的前提，不是『分享可用』的判据")
    else:
        finding("  ❌ 分享页没给全 shareid/uk ⇒ 列目录要另找入口"
                "（可能得先调某个 init 接口）")
    if has_fl:
        finding("  ✅ 页面**内嵌了非空根目录 file_list** ⇒ 根目录连列目录接口都不用调"
                "（体验最好的一条路）")
    elif not page_err:
        finding("  ❌ 页面**没有**内嵌非空 file_list ⇒ 匿名展开根目录这条路不成立，"
                "只能走 `/share/list`")
    if fields.get("bdstoken"):
        finding("  ✅ 页面给了 `bdstoken` —— §6.3 说的『Cookie + bdstoken』里那个 token"
                "**匿名就能拿到**（它多半是防 CSRF 的页内票据，不是账号凭据）")

    shareid, uk = fields.get("shareid"), fields.get("uk")
    bdstoken = fields.get("bdstoken") or ""

    # ------------------------------------------------------- ② 列目录（匿名）
    hr("② 列目录（匿名先试）")
    root_items = []
    all_files = []          # 走到的**所有文件**（③ 要从这里挑一个测 dlink）

    def ls_dir(d, root):
        """列分享内的一个目录。**两套形状，别混**（2026-09-26 实测差分确认）：

        - 分享**根**：`root=1 & dir=/`
        - 分享**子目录**：**不能带 `root`**，`dir=` 取该项的 `path` 字段
          —— 那是**拥有者侧绝对路径**（如 `/电影/2026/X 消失-的-人呀`），
          不是分享相对路径；拿"按名字拼出来的 `/子目录`"去问只会得到 `errno=2`。

        ⚠️ **给子目录带 `root=1` 会静默返回"根目录的内容"**（`dir` 被忽略）。
        症状极具误导性：看起来"下钻成功"，其实每一层都在原地打转 ——
        这正是下钻循环 12 层全是同名目录的原因（见 PITFALLS §4.80）。
        """
        ts = int(time.time())
        u = ("{}/share/list?uk={}&shareid={}&order=other&desc=1&showempty=0"
             "&web=1&page=1&num=100&{}dir={}&t={}&channel={}&app_id={}"
             "&bdstoken={}&clienttype=0").format(
            BAIDU, eq(uk), eq(shareid), "root=1&" if root else "", eq(d),
            ts, CHANNEL, APP_ID, eq(bdstoken))
        return req("GET", u)          # (status, headers, text, ms) —— 调用方自己 env()

    if shareid and uk:
        # **根目录为什么必须 `root=1`**（2026-09-26 实测差分）：同一个 `/share/list`，
        # **带** `root=1` 返回分享根的真实内容（`errno=0`），**不带**一律 `errno=-21`。
        # ⚠️ `-21` 在**失效分享**上也会出现（PITFALLS §4.80）—— 所以下面报失败前先看
        # `page_err`，别把"分享没了"读成"这一步要登录"。
        stl, _, textl, msl = ls_dir("/", True)
        ol = show("share/list(匿名,根 root=1)", stl, textl, msl)
        if isinstance(ol, dict) and ol.get("errno") == 0:
            root_items = ol.get("list") or []
            dirs = [x for x in root_items if x.get("isdir")]
            files = [x for x in root_items if not x.get("isdir")]
            finding("  ✅ 匿名列目录**可用**（根形状 = `root=1&dir=/`）")
            finding("  ⇒ 详情页能像夸克/UC 一样匿名展开真实集数")
            finding("  ⇒ 根目录 {} 项：目录 {} / 文件 {}".format(
                len(root_items), len(dirs), len(files)))
            for x in root_items[:8]:
                finding("     {:<4} {:<46} {} B".format(
                    "DIR" if x.get("isdir") else "FILE",
                    (x.get("server_filename") or "")[:46], x.get("size")))
        else:
            # 对照：同一个端点**不带** root —— 预期 `errno=-21`，用来证明 `root=1` 是"必需"
            # 而不是"碰巧这次能过"（只动一个变量的差分，是这类问题的唯一判据）
            st2, _, tx2, ms2 = ls_dir("/", False)
            show("share/list(匿名,根 不带 root 对照)", st2, tx2, ms2)
            finding("  ❌ 根目录列表失败（{}）".format(
                brief(ol) if isinstance(ol, dict) else "非 JSON"))
        if not root_items:
            if page_err:
                finding("  ⛔ 两种形状都失败，且**页面本就是错误页** ⇒ 这条分享已失效，"
                        "`errno=-21` 在这里**不是**『匿名列目录不成立』的证据")
                finding("     ⇒ 换一条当前有效的分享再跑，才能回答 P1 真正的问题")
            else:
                finding("  ⛔ 两种形状都失败 ⇒ 匿名列目录**不成立**（这一步就要登录态）")
        else:
            # **往下走**：网盘分享几乎总是「根 → 剧名目录 → 剧名目录 → 集数文件」这种套娃
            # （本次取样 4 条全是这个形状，**根目录只有目录、没有文件**）。只列根的话 ③
            # 永远拿不到 `fs_id`、永远 SKIP ⇒ 脚本答不出 Q2「取直链要什么」。
            #
            # ⚠️ 下钻用的是**不带 `root`** 的那套形状，`dir` 取**该项自己的 `path`**
            # （拥有者绝对路径）—— 不是"按名字拼出来的相对路径"。这两个都对不上就是
            # 2026-09-26 那次"每层都回同一个同名目录、连走 12 层"的全部原因。
            #
            # 有界：目录数 / 文件数都封顶（手动工具，别把一次跑测打成爬虫 —— 2026-09-26
            # 就是因为连打了几十次请求被百度限流，之后整段时间都只回错误页）。
            all_files = [x for x in root_items if not x.get("isdir")]
            queue = [x for x in root_items if x.get("isdir")]
            seen = set()
            MAX_DIRS, MAX_FILES = 12, 60
            dirs_walked = 0
            while queue and dirs_walked < MAX_DIRS and len(all_files) < MAX_FILES:
                it = queue.pop(0)
                path = it.get("path") or ""
                if not path or path in seen:
                    continue
                seen.add(path)
                dirs_walked += 1
                st2, _, tx2, ms2 = ls_dir(path, False)
                o2 = env(tx2)
                if not isinstance(o2, dict) or o2.get("errno") != 0:
                    finding("     ⚠️ 下钻 {} 失败（{}）".format(
                        path, brief(o2) if isinstance(o2, dict) else "非 JSON"))
                    continue
                kids = o2.get("list") or []
                nf = sum(1 for x in kids if not x.get("isdir"))
                finding("     dirls {} → {} 项（文件 {}）".format(path, len(kids), nf))
                for x in kids[:4]:
                    finding("        {:<4} {:<44} {} B".format(
                        "DIR" if x.get("isdir") else "FILE",
                        (x.get("server_filename") or "")[:44], x.get("size")))
                for x in kids:
                    if x.get("isdir"):
                        queue.append(x)
                    else:
                        all_files.append(x)
            finding("  ⇒ 有界下钻：走过 {} 个目录，收集到 {} 个文件".format(
                dirs_walked, len(all_files)))
    else:
        finding("  SKIP：shareid/uk 没抠到，构造不出请求")

    # ------------------------------------------------------- ③ 取直链（匿名）
    target = all_files[0] if all_files else None
    hr("③ 取直链（匿名先试）")
    if not target:
        finding("  SKIP：没有可用的文件 fs_id（先解决 ②）")
    elif not shareid or not uk:
        finding("  SKIP：shareid/uk 缺失")
    else:
        log("  （目标文件：{}｜{} B｜fs_id={}）".format(
            (target.get("server_filename") or "")[:40], target.get("size"),
            target.get("fs_id")))
        log("  ⚠️ 这一步是**候选形状**并列探测：§6.3 只写了『→ dlink』，具体端点还没实测过。")
        ts = int(time.time())
        sign = fields.get("sign") or ""
        # 候选 A：/api/sharedownload（现代网页端取 dlink 的形状，需要页内的 sign+timestamp）
        aurl = ("{}/api/sharedownload?sign={}&timestamp={}&channel={}&web=1&app_id={}"
                "&clienttype=0").format(BAIDU, eq(sign), ts, CHANNEL, APP_ID)
        abody = ("encrypt=0&product=share&type=dlink&uk={}&primaryid={}&fid_list=[{}]").format(
            eq(uk), eq(shareid), target.get("fs_id"))
        sta, _, texta, msa = req("POST", aurl, body=abody,
                                 extra={"Content-Type": "application/x-www-form-urlencoded"})
        oa = show("api/sharedownload(匿名)", sta, texta, msa)
        dlink = None
        if isinstance(oa, dict):
            lst = oa.get("list") or []
            if lst and isinstance(lst[0], dict):
                dlink = lst[0].get("dlink")
            if oa.get("errno") != 0:
                log("      errmsg={!r}".format(oa.get("errmsg") or oa.get("show_msg")))
        # 候选 B：/share/download（老形状，作为对照）
        burl = ("{}/share/download?shareid={}&uk={}&fid_list=[{}]&channel={}&web=1"
                "&app_id={}&bdstoken={}&clienttype=0").format(
            BAIDU, eq(shareid), eq(uk), target.get("fs_id"), CHANNEL, APP_ID, eq(bdstoken))
        stb, _, textb, msb = req("GET", burl)
        ob = show("share/download(匿名，对照)", stb, textb, msb)
        if dlink:
            finding("  ✅ **匿名就能拿到 dlink** ⇒ 取流这条链路的凭据要求比夸克/UC 还松")
        else:
            finding("  ⚠️ 匿名没拿到 dlink（`sharedownload`={} / `share/download`={}）"
                    "⇒ 取流**需要登录态**".format(
                        brief(oa) if isinstance(oa, dict) else "非 JSON",
                        brief(ob) if isinstance(ob, dict) else "非 JSON"))
            finding("     ⭐ 2026-09-26 的实测值：`/api/sharedownload` 不给 `sign` 时 "
                    "`errno=2 请求失败`（页内 `sign` 匿名抠不到）；`/share/download` "
                    "匿名给 `errno=112`。两个端点都**没有**匿名成功记录。")
            finding("     ⇒ 『Cookie 就够 / 还要 `bdstoken`』这个判据只能靠 "
                    "`BAIDU_COOKIE=` 复跑，别据 112 去猜它的语义。")

    # ------------------------------------------------------- ④ 登录态体检
    hr("④ 登录态体检（决定 ⑤ 值不值得跑）")
    if not COOKIE:
        finding("  ⏭ 未提供 BAIDU_COOKIE ⇒ 只能验匿名段；⑤⑥ 跳过")
        log("  想验『登录后取直链 + dlink 校验强度』，按文件头取一份**登录态** Cookie。")
        return finish()
    names = sorted({p.split("=", 1)[0].strip() for p in COOKIE.split(";") if "=" in p})
    log("  Cookie 键数 = {}；键名 = {}".format(len(names), ", ".join(names)))
    # ⚠️ 判据不是"cookie 有多长"，而是**有没有 BDUSS** + **带与不带是否真的不同**。
    #    只有 BAIDUID 的是游客（那份 cookie 看着也很长）；两条一起做才既不会漏判、
    #    也说得清原因（这套差分判据是夸克那边踩过坑之后定下的，见 panquark_spike ④.5）。
    log("  百度登录态核心字段 BDUSS：{}".format(
        "命中" if "BDUSS" in names else "**缺失**（只有 BAIDUID 是游客态）"))

    def quota(ck):
        """`/api/quota` 是个便宜的"我是谁"探针：登录 errno=0，游客 errno=-6。"""
        stx, _, textx, _ = req("GET", "{}/api/quota?checkfree=1&checkexpire=1".format(BAIDU),
                               ck=ck, jar=(ck is None))
        oo = env(textx)
        return stx, (oo or {}).get("errno") if isinstance(oo, dict) else None

    st_ck, err_ck = quota(COOKIE)
    st_no, err_no = quota(None)
    log("  带这份 Cookie 打 /api/quota  → HTTP {} errno={}".format(st_ck, err_ck))
    log("  **不带** Cookie（对照）      → HTTP {} errno={}".format(st_no, err_no))
    if st_ck == st_no and err_ck == err_no:
        finding("  ❌ 这份 Cookie **不具备登录态**：有它与没它结果**完全相同**"
                "（HTTP {} errno={}）⇒ ⑤⑥ 跳过".format(st_ck, err_ck))
        log("  怎么拿到**真正**的登录态 Cookie：见本文件头『Cookie 从哪来』。")
        log("  ⚠️ 复制的 Cookie 里**必须出现 `BDUSS`**；只有 `BAIDUID` 是游客。")
        return finish()
    finding("  ✅ 带与不带结果不同 ⇒ 这份 Cookie 有登录增益，继续跑 ⑤")

    # ------------------------------------------------------- ⑤ 登录后取直链
    hr("⑤ 带 Cookie 重取直链")
    if not (shareid and uk and target):
        finding("  SKIP：② 没取到 shareid/uk/文件 ⇒ 无法构造")
        return finish()
    ts = int(time.time())
    sign = fields.get("sign") or ""
    aurl = ("{}/api/sharedownload?sign={}&timestamp={}&channel={}&web=1&app_id={}"
            "&clienttype=0").format(BAIDU, eq(sign), ts, CHANNEL, APP_ID)
    abody = ("encrypt=0&product=share&type=dlink&uk={}&primaryid={}&fid_list=[{}]").format(
        eq(uk), eq(shareid), target.get("fs_id"))
    sta, _, texta, msa = req("POST", aurl, ck=COOKIE, body=abody,
                             extra={"Content-Type": "application/x-www-form-urlencoded"})
    oa = show("api/sharedownload(已登录)", sta, texta, msa)
    dlink = None
    if isinstance(oa, dict):
        lst = oa.get("list") or []
        if lst and isinstance(lst[0], dict):
            dlink = lst[0].get("dlink")
    if not dlink:
        finding("  ❌ 登录后也没拿到 dlink ⇒ 端点/参数形状不对，把上面信封原文贴回来")
        return finish()
    finding("  ✅ 拿到 dlink：{} 字符，host={}".format(
        len(dlink), re.sub(r"^(https?://[^/]+).*", r"\1", dlink)))

    # ------------------------------------------------------- ⑥ dlink 校验强度
    hr("⑥ dlink 的校验强度（决定 MediaSource.headers 放什么）")
    log("  从哪读：能播是因为**带了什么**；下面的差分是唯一的判据（不是猜）")

    def fetch(u, tag, ck=None, ua=None, ref=None, rng=None):
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
            r = requests.get(u, headers=h, timeout=25, verify=VERIFY_TLS,
                             allow_redirects=True)
            ct = r.headers.get("Content-Type", "")
            finding("  [{:<30}] HTTP {:<4} {} {} B".format(
                tag, r.status_code, ct[:28], r.headers.get("Content-Length") or "-"))
            return r.status_code
        except Exception as e:  # noqa: BLE001
            finding("  [{:<30}] 异常 {}".format(tag, type(e).__name__))
            return 0

    for tag, kw in (("① 裸请求", {}),
                    ("② 仅 UA", dict(ua=UA)),
                    ("③ 仅 Referer", dict(ref=WEB)),
                    ("④ UA+Referer", dict(ua=UA, ref=WEB)),
                    ("⑤ 仅 Cookie", dict(ck=COOKIE)),
                    ("⑥ 全头", dict(ck=COOKIE, ua=UA, ref=WEB)),
                    ("⑦ 全头 + Range 0-1023", dict(ck=COOKIE, ua=UA, ref=WEB,
                                                 rng="bytes=0-1023"))):
        fetch(dlink, tag, **kw)
    finding("  ⇒ 判读：哪一档返回 200/206，`MediaSource.headers` 里就必须放哪些头；")
    finding("     Range 返回 **206** 才说明能拖进度条（否则只能顺序播）")
    return finish()


def finish():
    hr("结论汇总（**请把这一段贴回来**）")
    for f in FINDINGS:
        if any(m in f for m in ("✅", "❌", "⚠️", "⇒", "SKIP")):
            log(f)
    here = os.path.dirname(os.path.abspath(__file__))
    p = os.path.join(here, "_panbaiduspike.txt")
    with open(p, "w", encoding="utf-8") as fh:
        fh.write("\n".join(OUT))
    log("")
    log("全量日志 → {}".format(p))
    return 0


if __name__ == "__main__":
    sys.exit(main())
