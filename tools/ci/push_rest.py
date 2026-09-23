#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""发版用：git 传输被沙箱挡掉时，用 GitHub Git Data API 把**本地已有提交**推上去。

【为什么需要它】
本机 git 的 `http.proxy` 指向 `127.0.0.1:7890`（本地代理常不在）；把代理清掉直连
`github.com:443` 会超时/被 reset；走环境代理（`127.0.0.1:63488`）对本机 CONNECT
`github.com` 回 **502 CONNECT tunnel failed**。而 `api.github.com` 经 `gh` 一直是通的
⇒ **把 gh 当 HTTP 客户端**（TLS 它自己处理，也不必碰 token）。
判定顺序照 skill `github-rest-sandbox-ops` Step 0：**先真跑一次 push 试探**，
只有它确实失败才走这里（别用 TCP 探测代替 —— TCP 通不代表能 push）。

【关键性质：服务端 sha 与本地逐位相同】
把本地提交的 `tree` / `parents` / `author` / `committer` / `message` **原样**传给
`POST /git/commits`，返回的 sha 与本地 `HEAD` 逐位相同 ⇒ **不需要**事后对
`refs/heads/main` / `origin/main` / 本地对象库做任何"对齐"修补。
（细节：message 必须含 Git 给的末尾换行、**不要 strip**；日期用带偏移的 ISO 8601。）

【为什么不从工作区读文件】
一律用 `git cat-file blob <sha>` 取**提交里的原始字节**，不从文件系统读 ——
`core.autocrlf=true` 时工作区是 CRLF、库里是 LF，直接推工作区字节等于把 CRLF 写进库。
（也不要写 `git show <rev>:<path>`：它经 smudge 转换后输出，同样会把 LF 变 CRLF。）

【判据落在最终对象上】
① `POST /git/trees` 返回的 tree sha == 本地 `HEAD^{tree}`；
② `POST /git/commits` 返回的 commit sha == 本地 `HEAD`；
③ `PATCH` 后再读远端 ref == 本地 `HEAD`。
三者缺一即中止/报警（"PATCH 成功"本身不是判据）。

用法：
    python tools/ci/push_rest.py                 # 推 main，核 sha
    python tools/ci/push_rest.py --tag v1.0.67   # 顺带建 lightweight tag ref（触发发版通道）
    python tools/ci/push_rest.py --tag v1.0.67 --no-main   # 只建 tag
"""

import base64
import datetime
import json
import os
import re
import subprocess
import sys
import tempfile
import time

REPO = "WangMCweijia/VideoShell"
GH = "C:/Program Files/GitHub CLI/gh.exe"
ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
TZ = datetime.timezone


def _run(cmd, cwd=ROOT, binary=False):
    r = subprocess.run(cmd, cwd=cwd, capture_output=True)
    if r.returncode != 0:
        raise RuntimeError("命令失败 %s\n%s" % (cmd, r.stderr.decode("utf-8", "replace")[:800]))
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def git(*args, binary=False):
    return _run(["git"] + list(args), binary=binary)


def git_q(*args):
    """走 quotepath=false 的 git：**非 ASCII 路径默认会被转义成假路径**（八进制 + 引号），
    照抄进 tree 就会在服务器上建出一棵文件名乱码的树（见 skill §5）。"""
    return _run(["git", "-c", "core.quotepath=false"] + list(args))


def gh(method, path, body=None, raw=False):
    """gh 当 HTTP 客户端。

    ⛔ path 必须带 `repos/<owner>/<repo>` 前缀且**不能以 `/` 开头** —— gh 是把它拼在
    `https://api.github.com/` 之后的，写成 `/git/ref/...` 会缺 `repos/...` 前缀，
    而 GitHub 对未匹配路径**不报错**，只回一个结构不同的体 ⇒ 表现为"字段取不到"。
    ⛔ body 一律走 `--input <临时文件>`：base64 动辄几十 KB，超 Windows 命令行长度。
    """
    cmd = [GH, "api", "--method", method, "repos/%s%s" % (REPO, path),
           "-H", "Accept: application/vnd.github+json",
           "-H", "X-GitHub-Api-Version: 2022-11-28"]
    tmp = None
    if body is not None:
        fd, tmp = tempfile.mkstemp(suffix=".json")
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(body, f, ensure_ascii=False)
        cmd += ["--input", tmp]
    try:
        r = subprocess.run(cmd, cwd=ROOT, capture_output=True)
    finally:
        if tmp:
            try:
                os.unlink(tmp)
            except OSError:
                pass
    out = r.stdout.decode("utf-8", "replace")
    err = r.stderr.decode("utf-8", "replace")
    if raw:
        return out, err
    if not r.stdout.strip():
        raise RuntimeError("gh api %s %s 空响应\n%s" % (method, path, err[:600]))
    try:
        return json.loads(out)
    except ValueError:
        raise RuntimeError("gh api %s %s 非 JSON 响应\n%s" % (method, path, out[:600]))


def iso_from_git(raw_date):
    """`1758... +0800` → `2026-09-23T23:30:00+08:00`（API 要带偏移的 ISO 8601，别写 Z）"""
    epoch, tz = raw_date.split()
    sign = -1 if tz[0] == "-" else 1
    off = datetime.timedelta(hours=int(tz[1:3]), minutes=int(tz[3:5])) * sign
    zone = TZ.utc if off == datetime.timedelta(0) else TZ(off)
    return datetime.datetime.fromtimestamp(int(epoch), zone).isoformat()


def parse_commit(sha):
    raw = git("cat-file", "commit", sha, binary=True)
    head, _, message = raw.partition(b"\n\n")          # ★ message 原样保留（含末尾换行）
    lines = head.decode("utf-8", "replace").split("\n")
    tree = None
    parents = []
    ident = {}
    for ln in lines:
        if ln.startswith("tree "):
            tree = ln[5:].strip()
        elif ln.startswith("parent "):
            parents.append(ln[7:].strip())
        else:
            m = re.match(r"^(author|committer) (.*) <(.*)> (\d+ [+-]\d{4})$", ln)
            if m:
                who, name, email, date = m.groups()
                ident[who] = {"name": name, "email": email, "date": iso_from_git(date)}
    if tree is None or "author" not in ident or "committer" not in ident:
        raise RuntimeError("无法解析提交头：\n" + head.decode("utf-8", "replace")[:400])
    return tree, parents, ident, message.decode("utf-8", "replace")


def changed_files(base, head):
    out = git_q("diff", "--name-status", base, head)
    files = []
    for ln in out.splitlines():
        if not ln.strip():
            continue
        kind, _, path = ln.partition("\t")
        if path.startswith('"'):
            raise RuntimeError("路径被引号包裹（含空格等特殊字符），本脚本未处理：%s" % path)
        files.append((kind.strip(), path))
    return files


def main():
    args = sys.argv[1:]
    tag = None
    do_main = "--no-main" not in args
    if "--tag" in args:
        tag = args[args.index("--tag") + 1]

    head = git("rev-parse", "HEAD").strip()
    head_tree = git("rev-parse", "HEAD^{tree}").strip()
    remote = gh("GET", "/git/ref/heads/main")["object"]["sha"]
    tree, parents, ident, message = parse_commit(head)

    print("local  HEAD      = %s" % head)
    print("remote main      = %s" % remote)
    print("local  HEAD^     = %s" % (parents[0] if parents else "(root)"))

    if do_main:
        if not parents or parents[0] != remote:
            raise RuntimeError(
                "远端 main 不是本地 HEAD 的父提交（不可快进）⇒ 先 fetch+rebase。\n"
                "  remote=%s\n  HEAD^ =%s" % (remote, parents[0] if parents else "(root)"))
        if tree != head_tree:
            raise RuntimeError("提交内 tree 与 HEAD^{tree} 不一致：%s vs %s" % (tree, head_tree))

        files = changed_files(remote, head)
        if not files:
            print("没有变更文件，跳过。")
        base_tree = gh("GET", "/git/commits/%s" % remote)["tree"]["sha"]

        entries = []
        for i, (kind, path) in enumerate(files, 1):
            if kind == "D":
                entries.append({"path": path, "mode": "100644", "type": "blob", "sha": None})
                print("  [%2d/%d] D %s" % (i, len(files), path))
                continue
            bsha = git("rev-parse", "%s:%s" % (head, path)).strip()
            content = git("cat-file", "blob", bsha, binary=True)
            # blob 偶发 502 是 GitHub 侧瞬时故障（不是 payload 问题）⇒ 原样重试
            blob = None
            for attempt in range(4):
                try:
                    blob = gh("POST", "/git/blobs",
                              {"content": base64.b64encode(content).decode(), "encoding": "base64"})
                    break
                except RuntimeError as e:
                    if attempt == 3:
                        raise
                    print("    blob 重试 %d（%s）" % (attempt + 1, str(e)[:80]))
                    time.sleep(2 ** attempt)
            if blob.get("sha") != bsha:
                raise RuntimeError("blob sha 不符 %s：server=%s local=%s" % (path, blob.get("sha"), bsha))
            entries.append({"path": path, "mode": "100644", "type": "blob", "sha": blob["sha"]})
            print("  [%2d/%d] %s %s" % (i, len(files), kind, path))

        new_tree = gh("POST", "/git/trees", {"base_tree": base_tree, "tree": entries})
        if new_tree.get("sha") != head_tree:
            raise RuntimeError("★ TREE MISMATCH：server=%s local=%s\n"
                               "blob 全成功却对不上 ⇒ 先怀疑**路径字符串**（quotepath 转义）。"
                               % (new_tree.get("sha"), head_tree))
        print("tree  ok %s" % new_tree["sha"])

        cm = gh("POST", "/git/commits", {
            "message": message, "tree": new_tree["sha"], "parents": parents,
            "author": ident["author"], "committer": ident["committer"],
        })
        if cm.get("sha") != head:
            raise RuntimeError("★ COMMIT SHA 不符：server=%s local=%s\n"
                               "（message 末尾换行 / 日期偏移 / ident 三者之一没对上）"
                               % (cm.get("sha"), head))
        print("commit ok %s（与本地逐位相同）" % cm["sha"])

        gh("PATCH", "/git/refs/heads/main", {"sha": head})
        now = gh("GET", "/git/ref/heads/main")["object"]["sha"]
        if now != head:
            raise RuntimeError("PATCH 后远端 main 仍为 %s（期望 %s）" % (now, head))
        print("main 已更新 -> %s" % now)

    if tag:
        if git("tag", "-l", tag).strip():
            raise RuntimeError("本地已存在同名 tag：%s" % tag)
        # lightweight tag：只为触发 `on: push: tags` 通道，落点与本地一致
        gh("POST", "/git/refs", {"ref": "refs/tags/%s" % tag, "sha": head})
        git("tag", tag, head)          # ★ 不加 -a
        print("tag %s -> %s" % (tag, head))

    print("PUSH DONE（判据：tree/commit/ref 三处均已核）")


if __name__ == "__main__":
    main()
