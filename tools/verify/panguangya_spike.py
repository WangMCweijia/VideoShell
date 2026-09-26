#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""光鸭云盘（P1）spike —— PC 侧，**要真实网络**。

为什么是它（而不是阿里/123/天翼/迅雷）
--------------------------------------
方案 §10 的纪律：**不要先写 9 个 Provider 再验证**；P1 的交付判据是"每个盘的登录与播放
**各有一条实测记录**"。选盘的唯一依据是**我们够得着的真样本里出现过谁**：

- 快映 / 玩偶站（`samples/panshare/*`、`samples/pandrift/*`）的详情页
  `data-clipboard-text` 扫下来，只有 **夸克 / UC / 百度**（2026-09-26 在 `www.wogg.live`
  连扫 6 个详情页，一条阿里/123/天翼/迅雷都没有）；
- 只有拉站（`samples/pandrift/la_detail.html`）在夸克/百度旁边多挂了一家：
  `https://www.guangyapan.com/s/1929454930666201095_al-RP8u9gxLNGGHy`。

⇒ 排序上"名气压倒一切"会把力气花在一个**我们的站点里根本不出现**的盘上。
光鸭是唯一一个"真样本里出现过、又还没做"的盘。

**已实测的结论（2026-09-26，匿名段）—— 一好一坏**
------------------------------------------------
✅ 匿名可拿：`get_share_access_token`（分享会话票）、`get_share_summary`（标题/文件数/大小）。
⛔ **匿名列目录不成立，而且是"静默空表"**：

    POST /userres/v1/get_share_page_files_list  →  HTTP 200
    {"msg":"success","data":{"cursor":100}}      ← 没有 list，不是错误码

诱发这个结论的不是"接口没通"，而是**官方页面自己**：同一条分享，页面顶部写着
`共12个文件 / 16.8GB`（正是 summary 给的 `totalFileNum`/`totalFileSize`），
文件区却写着 **暂无文件**，旁边一个 **立即登录**。用页面自己的上下文（带着它自己生成的
`smid`/`did`）原样复打那个请求，返回的仍是同一个空表 —— 所以缺的不是某个指纹头，
**是登录态**（网页在 `Authorization: Bearer <登录 token>` 这一格上）。

⚠️ **这一条是本脚本存在的最大理由**：`success + 空 list` 这种形状，
在 Provider 里会被读成"分享是空的"，用户看到的是"0 集"——而真相是"没登录"。
判据必须是"**summary 说有 N 个文件 但 list 为空 ⇒ 需要登录**"这条**交叉验证**，
不能只看 list 自己。光鸭与夸克/UC/百度在这里**形状相反**：那三家的匿名列目录是成立的
（百度见 §6.3 补充表），所以"匿名能展开集数"这条老经验**不能外推**到光鸭。

与离线断言的关系（与 `panbaidu_spike.py` 同一定位）
--------------------------------------------------
`android.jar` 是桩、`new JSONObject` 抛 `Stub!`（PITFALLS §4.58），所以"接口还活着吗 /
登录后能不能列 / 直链校验多严"这类问题**只能问真网络**。本脚本只输出结论、不产生
PASS/FAIL ⇒ **不进 SUITES**（与 `panquark_spike.py` 一致）。

三个必须回答的问题
------------------
1. **匿名段到哪一步为止？** —— ✅ **已答（2026-09-26）**：分享会话票与 summary 匿名可得；
   **列目录不行**（静默空表）。⇒ 详情页**不能**像夸克/UC/百度那样匿名展开集数。
2. **登录后怎么列、怎么取直链？** —— ⏳ **待答**，需要 `GUANGYA_TOKEN=` 才能跑。
   候选形状已从线上 JS bundle 里读出来（不是猜的，见下），脚本负责一次性钉死：
   - 列目录：`get_share_page_files_list {accessToken, parentId, pageSize, orderBy, sortType}`
     + 头 `Authorization: Bearer <登录 token>`；
   - 取流（**单文件直取，不用先转存**）：`get_share_download_url {fileId, accessToken}`
     → `data.signedURL`（网页端"下载"走的就是它，`mode:"single"` 那条分支）；
   - 对照：`get_res_download_url {fileId}` → `data.signedURL`（那是**自己网盘**里的文件，
     分享文件走它应当不成立 —— 留着做差分，正好印证"分享要 accessToken 那一份"）；
   - 转存（P2 才会用到，先只记录）：`restore_share {accessToken, fileIds[], parentId, shareCode?}`
     → `data.taskId`，再轮询任务状态。
3. **`signedURL` 的校验强度？** 对 UA / Referer / Range / Cookie 各是什么反应 —— 决定
   `PanStream.headers` 里必须放什么。—— ⏳ **待答**（要先过第 2 问）。

登录 token 从哪来（**必须是真实账号的** —— 2026-09-26 从线上 bundle 读出来的，不是猜的）
----------------------------------------------------------------------------------
光鸭的网页登录**不写 cookie**：登录态只有一个 `Authorization: Bearer <access_token>`，
由站点自己的 OAuth2 客户端（`account.guangyapan.com`，RS256）持有并注入每个
`api.guangyapan.com` 请求。所以"抓 cookie"那招在这里**拿不到任何东西**。

⚠️ **但"内嵌 WebView 登录"这条路本身没被否决** —— 变的只是**取哪一个东西**：
夸克/UC/百度是从 `CookieManager` 取 cookie 快照，光鸭要从请求头里截 `Authorization`。
两者都是"打开官方登录页 → 等登录完成 → 取一次凭据快照"，只是快照的来源不同。
（本文件早先写的"那套对它不适用"只对"取 cookie"这一步成立，对整条登录路径不成立。）

**⚠️ 有一个陷阱特别值得单独说：光鸭会给匿名访客发一张"客票"。**
`POST /misc/v1/anonymous_signup {deviceId}`（`deviceId` 只是 localStorage 里一个随机
UUID，网页端自己就是这么领的）会回一张**合法 JWT**，payload 里 `scope=anonymous`。
本脚本 ③b 专门验过：**带上它也列不出文件**。⇒ 它是最容易被误当成"我们已经登录了"
的东西 —— 谁拿它去填凭据，症状就是 ③ 那一整节：HTTP 200、`msg=success`、0 集。

目前最省事的一条（手工取）：
1. 用**电脑**浏览器打开 https://www.guangyapan.com 并**登录**（能看到头像/昵称）；
2. F12 → Network → 刷新 → 点任一 `api.guangyapan.com` 请求；
3. Request Headers → 复制 `Authorization:` 里的那串 **Bearer 后面**的值（很长，JWT 形状）。
自查判据：那串里有**两段 `.`**（JWT），且带它打 `get_share_page_files_list` **能列出文件**。

    GUANGYA_TOKEN="$(cat /path/to/guangya_token.txt)" python tools/verify/panguangya_spike.py
    # 长凭据**别手抄进命令行**：打错一个字符的表现是"突然未登录"，与"会话失效"一模一样。

⚠️ 别在**手机 App** 里抓：App 的登录态不走网页这套 OAuth2，两边不是一套凭据。

用法
----
    # 只跑匿名段（**不需要任何凭据**）
    VS_PYTHON=<带 requests 的解释器> python tools/verify/panguangya_spike.py

    # 完整链路（需要上面那份登录 token）
    GUANGYA_TOKEN="eyJhbGciOi…" python tools/verify/panguangya_spike.py

    # 换分享 / 跳过 TLS 校验（抓包排错时用）
    GUANGYA_SHARE="https://www.guangyapan.com/s/xxxx" python tools/verify/panguangya_spike.py
    GUANGYA_INSECURE=1 python tools/verify/panguangya_spike.py

**素材会腐化，一条链接不是长期资产。** 默认那条取自拉站详情页；失效时不要怀疑脚本，
去现网取一条：任一 `csp_PanWebShare` 族站点的详情页 → `data-clipboard-text` 里的
`guangyapan.com/s/…`（目前只在拉站见到过）。
"""
import json
import os
import re
import secrets
import sys
import time
import uuid

import requests

# --------------------------------------------------------------------- 配置

API = "https://api.guangyapan.com"
WEB = "https://www.guangyapan.com"

# 默认取拉站（`samples/pandrift/la_detail.html`）里那条 —— 它是**真样本**里唯一的光鸭分享。
SHARE = os.environ.get("GUANGYA_SHARE") or \
    "https://www.guangyapan.com/s/1929454930666201095_al-RP8u9gxLNGGHy"

TOKEN = (os.environ.get("GUANGYA_TOKEN") or "").strip()
VERIFY_TLS = os.environ.get("GUANGYA_INSECURE") != "1"

# 与网页端逐字相同：它是 **PC 接口**，就别装手机
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

OUT = []          # 全量日志，最后落盘
FINDINGS = []     # 结论行


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

def headers(auth=None, referer=None, smid=None):
    """网页端 XHR 的身份头。线上 bundle 里的拦截器只加这四样（`did`/`dt`/`traceparent`/`smid`），
    登录后再多一个 `Authorization`：

        {dt:"4", traceparent:…, did:s}), n?{smid:n}:{}, + Authorization: Bearer <token>

    - `dt:4`            固定值（产品/协议版本位）
    - `did`             **客户端自己随机生成的设备号**（`crypto.randomUUID()`，存在
                        localStorage `swangpan_web_device_id`）。所以每次跑随机一个就行 ——
                        实测随机 uuid 完全可用，它**不是**服务端签发的。
    - `traceparent`     W3C 链路追踪串，随便造（`00-<32hex>-<16hex>-01`）
    - `smid`            数美风控指纹（`window.SMSdk` 产出，localStorage `shumei_id:v1`）。
                        **匿名复打时带上与不带都一样是空表**（2026-09-26 实测）⇒ 它不是这里
                        缺的那一块；脚本不伪造它（伪造风控指纹既不可靠也不体面）。
    """
    h = {
        "User-Agent": UA,
        "Content-Type": "application/json",
        "Accept": "application/json, text/plain, */*",
        "Origin": WEB,
        "Referer": referer or (WEB + "/"),
        "dt": "4",
        "did": str(uuid.uuid4()),
        "traceparent": "00-{}-{}-01".format(secrets.token_hex(16), secrets.token_hex(8)),
        # `identity`：本脚本常在**带正向代理**的沙箱里跑，而代理会把 body 解压后仍保留
        # `Content-Encoding: gzip` ⇒ requests 二次解压报 `ContentDecodingError`，
        # 表现成"每个请求都 HTTP 0"。明确不要压缩即可绕开（与站点行为无关，纯跑测环境）。
        "Accept-Encoding": "identity",
    }
    if smid:
        h["smid"] = smid
    if auth:
        h["Authorization"] = "Bearer " + auth
    return h


def post(path, body, auth=None, referer=None):
    url = API + path
    t0 = time.time()
    try:
        r = requests.post(url, headers=headers(auth, referer), data=json.dumps(body),
                          timeout=25, verify=VERIFY_TLS)
        return r.status_code, r.text, int((time.time() - t0) * 1000)
    except Exception as e:  # noqa: BLE001
        return 0, "{}: {}".format(type(e).__name__, e), int((time.time() - t0) * 1000)


def env(text):
    """光鸭的信封是 `{"msg":…,"data":…}`，失败体是 `{"code":…,"msg":…}` —— 都在 200 里。"""
    try:
        o = json.loads(text)
        return o if isinstance(o, dict) else None
    except Exception:  # noqa: BLE001
        return None


def brief(o):
    if not isinstance(o, dict):
        return "非 JSON"
    return "code={} msg={!r}".format(o.get("code"), o.get("msg"))


def data_keys(o, keep=260):
    d = (o or {}).get("data")
    if d is None:
        return "data=**缺失**"
    if isinstance(d, list):
        return "data=list(len={})".format(len(d))
    if isinstance(d, dict):
        return "data 键 = {}｜{}".format(sorted(d.keys()), json.dumps(d, ensure_ascii=False)[:keep])
    return "data={!r}".format(d)


def show(tag, st, text, ms):
    o = env(text)
    if o is None:
        finding("  [{}] HTTP {} ({}ms) — **不是 JSON**：{}".format(
            tag, st, ms, text.strip().replace("\n", " ")[:240]))
    else:
        finding("  [{}] HTTP {} ({}ms) — {}｜{}".format(tag, st, ms, brief(o), data_keys(o)))
    return o


# --------------------------------------------------------------------- 链接

def parse_share(u):
    """`www.guangyapan.com/s/1929454930666201095_al-RP8u9gxLNGGHy` → 整段分享 id。

    ⚠️ 光鸭的分享 id **不是纯数字**：`{纯数字}_{user_id}`（下划线把 owner 也编码进去了）。
    按"纯数字"的惯性去截，会把 `_al-RP8u9gxLNGGHy` 切掉，服务端回的是一句
    `code:112 参数错误` —— 看起来像"接口坏了"，其实是 id 被截断了。
    """
    m = re.search(r"guangyapan\.com/s/([0-9A-Za-z_\-]+)", u)
    if not m:
        raise SystemExit("无法从 GUANGYA_SHARE 里认出分享 id：{}".format(u))
    return m.group(1)


def pick(o, *path):
    cur = o
    for k in path:
        if not isinstance(cur, dict):
            return None
        cur = cur.get(k)
    return cur


# --------------------------------------------------------------------- 主体

def main():
    hr("光鸭云盘 P1 spike")
    log("share   = {}".format(SHARE))
    log("token   = {}".format(
        "已提供（{} 字符）".format(len(TOKEN)) if TOKEN else "**未提供 → 只验匿名段**"))
    log("tls     = {}".format("校验" if VERIFY_TLS else "**跳过校验（GUANGYA_INSECURE=1）**"))
    log("ua      = {}".format(UA))

    sid = parse_share(SHARE)
    referer = "{}/s/{}".format(WEB, sid)
    log("shareId = {}".format(sid))

    # ------------------------------------------------------- ① 分享会话票（匿名）
    hr("① POST /userres/v1/get_share_access_token （匿名，换分享会话票）")
    st, text, ms = post("/userres/v1/get_share_access_token", {"shareId": sid}, referer=referer)
    o1 = show("get_share_access_token", st, text, ms)
    access_token = pick(o1, "data", "accessToken") or ""
    if access_token:
        finding("  ✅ 匿名拿到分享会话票（{} 字符）。⚠️ 它是**分享级**票，不是账号登录态 —— "
                "payload 里只有 share_id/user_id/expire_time，没有用户身份。".format(
                    len(access_token)))
        log("     ⚠️ 提取码分享要再带 `code`（线上调用是 `get_share_access_token({shareId, code})`）。")
    else:
        finding("  ❌ 匿名拿不到分享会话票 ⇒ 这条分享本身有问题（失效 / 要提取码 / 已封），"
                "后面的结论都会是废的")

    # ------------------------------------------------------- ② summary（匿名）
    hr("② POST /userres/v1/get_share_summary （匿名，标题与文件数）")
    st, text, ms = post("/userres/v1/get_share_summary", {"shareId": sid}, referer=referer)
    o2 = show("get_share_summary", st, text, ms)
    total_num = pick(o2, "data", "totalFileNum")
    total_size = pick(o2, "data", "totalFileSize")
    title = pick(o2, "data", "title")
    if total_num is not None:
        finding("  ✅ 匿名能读到 `title={!r}`、`totalFileNum={}`、`totalFileSize={} B`".format(
            title, total_num, human(total_size)))

    # ------------------------------------------------------- ③ 列目录（匿名 —— **就是这里不成立**）
    hr("③ POST /userres/v1/get_share_page_files_list （匿名 —— ⛔ 静默空表）")
    body = {"accessToken": access_token, "parentId": "", "pageSize": 100,
            "orderBy": 0, "sortType": 0}
    st, text, ms = post("/userres/v1/get_share_page_files_list", body, referer=referer)
    o3 = show("share_page_files_list(匿名)", st, text, ms)
    items = pick(o3, "data", "list")
    is_err = not (isinstance(o3, dict) and o3.get("code") in (None, 0))
    if is_err:
        finding("  ❌ 匿名列目录**报错**（{}）⇒ 这是个「诚实」的失败，反而是好事".format(brief(o3)))
    elif items:
        finding("  ❗ **匿名竟然列出了 {} 项** —— 与 2026-09-26 的实测结论不同，"
                "优先相信眼前这一次：请把下面的原始信封贴回来".format(len(items)))
    else:
        finding("  ⛔ 匿名列目录**返回 success 但 list 为空**（`data.cursor={}`）—— "
                "这是**静默失败**，不是空分享".format(pick(o3, "data", "cursor")))
        if total_num:
            finding("     ⭐ 判据是**交叉验证**：summary 说这条分享有 {} 个文件，list 却是空的 ⇒ "
                    "缺的是**登录态**，不是内容。".format(total_num))
            finding("     ⚠️ 只看 list 自己会把它读成『分享是空的』，用户看到的是『0 集』—— "
                    "这正是光鸭与夸克/UC/百度**形状相反**的地方。")
        finding("     ⇒ 「匿名能展开真实集数」这条经验**不能外推到光鸭**："
                "详情页必须写『光鸭网盘需登录后才能浏览』，而不是显示 0 集。")

    # 对照组：把 parentId 填一个不存在的值。它**会**报错（code:143 文件不存在）——
    # 这条对照证明"静默空表"不是"参数全都被忽略了"，而是服务端**有意**对匿名返回空。
    st, text, ms = post("/userres/v1/get_share_page_files_list",
                        dict(body, parentId="*"), referer=referer)
    show("share_page_files_list(匿名, parentId=* 对照)", st, text, ms)
    log("  （对照的意义：同一个端点、同一个身份，只动 parentId —— 它会明确报错；"
        "说明空表是服务端对匿名的处置，不是我们的请求没被解析。）")

    # ------------------------------------------------------- ③b 客票（最容易误认成"已登录"）
    hr("③b POST /misc/v1/anonymous_signup （客票 —— 换个身份再试，仍旧不放行）")
    log("  它的存在方式读自线上 bundle：`hT({deviceId})` → `data.accessToken`，")
    log("  而 `deviceId` 只是 localStorage 里一个随机 UUID。⇒ 任何人都能领一张**合法** token，")
    log("  这正是它危险的地方：形状像登录态，权限却不是。")
    st, text, ms = post("/misc/v1/anonymous_signup", {"deviceId": str(uuid.uuid4())},
                        referer=referer)
    o3b = show("anonymous_signup(客票)", st, text, ms)
    guest = pick(o3b, "data", "accessToken") or ""
    if not guest:
        finding("  ⚠️ 领不到客票（接口变了 / 要别的字段）⇒ 这一格跳过，不影响其它结论")
    else:
        finding("  ✅ 匿名能领到一张客票（{} 字符，JWT）—— 网页端自己也在领它".format(len(guest)))
        st, text, ms = post("/userres/v1/get_share_page_files_list", body,
                            auth=guest, referer=referer)
        o3b2 = show("share_page_files_list(带客票)", st, text, ms)
        if pick(o3b2, "data", "list"):
            finding("  ❗ **客票竟然能列目录** —— 与 2026-09-26 的结论不同，"
                    "优先相信眼前这一次：请把原始信封贴回来")
        else:
            finding("  ⛔ 带**客票**列表依旧为空 ⇒ 光鸭要的不是「任何一张 token」，"
                    "而是**真实账号**的 token")
            finding("     ⭐ 这一格值得独立存在：客票是**最容易被误填进 `DriveStore`** 的东西，"
                    "而它的症状与本文件 ③ 完全一样（HTTP 200 / success / 0 集），"
                    "没有任何一处会报错。")

    # ------------------------------------------------------- ③c "诚实"的那一个端点
    hr("③c POST /userres/v1/get_share_list （同一个身份换个端点 —— 它**明确**报错）")
    st, text, ms = post("/userres/v1/get_share_list",
                        {"shareId": sid, "accessToken": access_token, "parentId": "",
                         "pageSize": 100}, referer=referer)
    o3c = show("get_share_list(匿名)", st, text, ms)
    finding("  ⭐ 差别本身就是证据：`get_share_list` 回 `code:117 无效token`（401）——**它不装**；"
            "而列目录那个端点回 success + 空表。⇒ 空表不是这个站对匿名的统一处置，"
            "而是**列目录那一个端点特有的静默**。判据只能落在交叉验证（③ 的 summary↔list）上。")
    log("  ⚠️ 别把这条误读成「换个端点就能列目录」：它是 bundle 里登记、分享页这一版不用的")
    log("     一个端点，本格的作用**只是**当对照 —— 证明服务端想说\"你没身份\"时是会说清楚的。")

    # ------------------------------------------------------- ④ 登录闸门
    hr("④ 登录态（决定 ⑤⑥ 值不值得跑）")
    if not TOKEN:
        finding("  ⏭ 未提供 GUANGYA_TOKEN ⇒ 只能验到这里。**光鸭的可用性完全取决于这一份 token**")
        log("  怎么拿：见本文件头『登录 token 从哪来』。⚠️ 光鸭网页登录**不写 cookie**，")
        log("  所以要截的不是 cookie 而是请求头里的 `Authorization` —— 「内嵌 WebView 登录」")
        log("  这条路仍然成立，只是**快照的来源**换了（见文件头）。")
        log("  ⚠️ 也别拿 ③b 那张 `scope=anonymous` 的客票来试 —— 本脚本已经验过它不放行。")
        return finish()

    jwt = TOKEN.count(".")
    log("  token 形状：{} 段落（JWT 通常 2 个点 = 3 段）".format(jwt + 1))

    # 登录判据不能只看"token 有没有值"，得看**它真的改变了服务端的回答**：
    # 同一个请求，带 token / 不带 token，list 从空变非空才是证据。
    st, text, ms = post("/userres/v1/get_share_page_files_list", body,
                        auth=TOKEN, referer=referer)
    o5 = show("share_page_files_list(已登录)", st, text, ms)
    items = pick(o5, "data", "list")
    if not items:
        finding("  ❌ 带上这份 token 也列不出文件 ⇒ 两种可能，先别往下走：")
        finding("     ① token 不是**登录态**的（复制成了游客/分享票）；② 请求还缺东西")
        finding("     差分判据：与 ③ 的空表**完全一样**的话，多半是① ——"
                "该有的差异一点都没出现")
        log("  ⚠️ 也别忘了提取码分享要带 `code`；本脚本目前只覆盖无码分享。")
        return finish()
    finding("  ✅ 带 token 能列出 {} 项 ⇒ 这份 token **确实具备登录增益**".format(len(items)))
    for x in items[:10]:
        log("     {:<4} {:<44} {}".format(
            "DIR" if is_dir(x) else "FILE", (name_of(x) or "")[:44], size_of(x)))

    # ------------------------------------------------------- ⑤ 取直链
    hr("⑤ POST /userres/v1/get_share_download_url （取分享文件直链）")
    # 线上形状：`hW({fileId, accessToken, orderId})` → `data.signedURL`（mode:"single" 分支）。
    # `orderId` 是**付费分享**的订单号；免费分享是否必需，正是这一步要问的。
    target = next((x for x in items if not is_dir(x)), None)
    if not target:
        finding("  SKIP：根目录里没有文件（分享很可能又是『根只有目录』那种套娃形状，"
                "需要下钻 —— 先把④跑通，下钻形状我再补）")
        return finish()
    fid = file_id(target)
    log("  （目标文件：{}｜{} B｜fileId={}）".format(
        (name_of(target) or "")[:40], size_of(target), fid))
    log("  ⚠️ 这一步是**候选形状**：端点/字段读自线上 bundle，但**还没有一次成功记录**。")

    st, text, ms = post("/userres/v1/get_share_download_url",
                        {"fileId": fid, "accessToken": access_token},
                        auth=TOKEN, referer=referer)
    o6 = show("get_share_download_url(无 orderId)", st, text, ms)
    signed = pick(o6, "data", "signedURL")
    if not signed:
        # 对照 A：带上 orderId（空串 / 0 两种都可能被当成"没给"）
        st, text, ms = post("/userres/v1/get_share_download_url",
                            {"fileId": fid, "accessToken": access_token, "orderId": ""},
                            auth=TOKEN, referer=referer)
        show("get_share_download_url(orderId=\"\")", st, text, ms)
        # 对照 B：自己网盘里的那个端点，用来印证"分享文件走它不成立"
        st, text, ms = post("/userres/v1/get_res_download_url", {"fileId": fid},
                            auth=TOKEN, referer=referer)
        show("get_res_download_url(对照：自己网盘的端点)", st, text, ms)
        finding("  ❌ 三种形状都没拿到 signedURL ⇒ 把上面**原始信封**贴回来，"
                "端点/字段按实测改，别猜")
        return finish()
    finding("  ✅ 拿到 signedURL：{} 字符，host={}".format(
        len(signed), re.sub(r"^(https?://[^/]+).*", r"\1", signed)))

    hr("⑥ signedURL 的校验强度（决定 PanStream.headers 放什么）")
    log("  判据是**差分**：能取到是因为带了什么，拿不准的档位每一档都试一遍")

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
            finding("  [{:<30}] HTTP {:<4} {} {} B".format(
                tag, r.status_code, (r.headers.get("Content-Type") or "")[:28],
                r.headers.get("Content-Length") or "-"))
            return r.status_code
        except Exception as e:  # noqa: BLE001
            finding("  [{:<30}] 异常 {}".format(tag, type(e).__name__))
            return 0

    for tag, kw in (("① 裸请求", {}),
                    ("② 仅 UA", dict(ua=UA)),
                    ("③ 仅 Referer", dict(ref=referer)),
                    ("④ UA+Referer", dict(ua=UA, ref=referer)),
                    ("⑤ 仅 Authorization", dict(ck="Authorization=Bearer " + TOKEN)),
                    ("⑥ UA+Referer+Auth", dict(ck="Authorization=Bearer " + TOKEN,
                                               ua=UA, ref=referer)),
                    ("⑦ 全头 + Range 0-1023", dict(ck="Authorization=Bearer " + TOKEN,
                                                   ua=UA, ref=referer,
                                                   rng="bytes=0-1023"))):
        fetch(signed, tag, **kw)
    finding("  ⇒ 哪一档返回 200/206，`PanStream.headers` 里就必须放哪些头；")
    finding("     Range 返回 **206** 才说明能拖进度条")
    return finish()


# --------------------------------------------------------------------- 字段容错
#
# 文件项的字段名**还没实测到**（④ 通了才看得见）。线上 JS 里构造目录项用的是
# `{fileId, fileName, fileSize, resType, dirType, …}`（`fb` 那个映射），但**响应**里
# 可能又是另一套名字（`resType===2` 是目录、`===1` 是文件 —— 与 `PanFile.isDir` 相反，
# 别直接照抄）。这里做容错并**把原始项打出来**，一次跑完就能定名。

def _first(d, *names, default=None):
    if not isinstance(d, dict):
        return default
    for n in names:
        if d.get(n) is not None:
            return d[n]
    return default


def file_id(x):
    return str(_first(x, "fileId", "file_id", "id", default="") or "")


def name_of(x):
    return _first(x, "fileName", "file_name", "name", default="") or ""


def size_of(x):
    return _first(x, "fileSize", "file_size", "size", default=0) or 0


def is_dir(x):
    """线上把 `resType` **2 = 目录、1 = 文件**（`ep.VIDEO`/`ep.UNKNOWN` 那套枚举）。
    ⇒ 与 `PanFile.isDir` 的语义**相反**，照抄必错。这里两种命名都认。"""
    rt = _first(x, "resType", "res_type")
    if rt is not None:
        return rt == 2
    d = _first(x, "isDir", "isdir", "dir")
    return bool(d)


def human(n):
    try:
        n = float(n)
    except (TypeError, ValueError):
        return n
    for u in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024:
            return "{:.1f}{}".format(n, u)
        n /= 1024
    return "{:.1f}PB".format(n)


def finish():
    hr("结论汇总（**请把这一段贴回来**）")
    for f in FINDINGS:
        if any(m in f for m in ("✅", "❌", "⚠️", "❗", "⇒", "SKIP", "⭐", "⏭")):
            log(f)
    here = os.path.dirname(os.path.abspath(__file__))
    p = os.path.join(here, "_panguangyaspike.txt")
    with open(p, "w", encoding="utf-8") as fh:
        fh.write("\n".join(OUT))
    log("")
    log("全量日志 → {}".format(p))
    return 0


if __name__ == "__main__":
    sys.exit(main())
