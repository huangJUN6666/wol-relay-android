#!/usr/bin/env python3
# ============================================================
#  用 GitHub REST API 上传整个仓库（不依赖 git push / 网页登录）
#
#  作者：神仙道  1974147300
#
#  为什么要有这个：
#    走 git push 得先过 github.com 主站，代理一抽就挂；
#    gh auth login 的设备码流程也要连 github.com/login/device。
#    而 api.github.com 是另一条线路，通常不受影响。
#    这个脚本只发 HTTPS 请求到 api.github.com，把本地文件
#    直接建成远端仓库的 blobs/tree/commit。
#
#  用法：
#    export GH_TOKEN=ghp_xxxxxxxx
#    python scripts/push-via-api.py                  # 推到默认仓库
#    python scripts/push-via-api.py owner repo       # 指定
#
#  需要的 token 权限：
#    classic PAT 勾 repo（或 fine-grained 给 Contents:Read+Write、
#    Administration:Read+Write）。用完记得撤销。
# ============================================================
import base64
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"
DEFAULT_REPO = "wol-relay-android"
DEFAULT_BRANCH = "main"


def token():
    t = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not t:
        sys.exit("缺少 GH_TOKEN。先 export GH_TOKEN=ghp_xxxx 再跑。")
    return t.strip()


def req(method, path, tok, body=None, ok=(200, 201, 204)):
    url = path if path.startswith("http") else API + path
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    r.add_header("Authorization", "Bearer " + tok)
    r.add_header("Accept", "application/vnd.github+json")
    r.add_header("X-GitHub-Api-Version", "2022-11-28")
    r.add_header("User-Agent", "wol-relay-push")
    if data:
        r.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            detail = json.loads(raw)
        except Exception:
            detail = {"raw": raw[:400].decode("utf-8", "replace")}
        if e.code in ok:
            return e.code, detail
        msg = detail.get("message", "")
        errs = detail.get("errors")
        sys.exit("%s %s -> %s %s\n%s" % (method, path, e.code, msg,
                                         json.dumps(errs, ensure_ascii=False) if errs else ""))


def tracked_files():
    """只上传 git 追踪的文件，保证 dist/、build/、keystore 不会被带上去。"""
    out = subprocess.check_output(
        ["git", "ls-files", "-z"], stderr=subprocess.STDOUT)
    return [p for p in out.decode("utf-8").split("\0") if p]


def git(*args):
    return subprocess.check_output(["git"] + list(args)).decode().strip()


def main():
    tok = token()
    owner = sys.argv[1] if len(sys.argv) > 1 else None
    repo = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_REPO
    branch = os.environ.get("BRANCH", DEFAULT_BRANCH)

    status, me = req("GET", "/user", tok)
    login = me["login"]
    owner = owner or login
    print("账号      : %s" % login)
    print("目标仓库  : %s/%s" % (owner, repo))
    print("分支      : %s" % branch)
    print()

    # ---------- 1. 仓库 ----------
    status, info = req("GET", "/repos/%s/%s" % (owner, repo), tok, ok=(200, 404))
    if status == 404:
        print("[1/5] 创建仓库")
        _, info = req("POST", "/user/repos", tok, {
            "name": repo,
            "description": "WOL 远程开机：root 方案（Magisk 模块 + 中继服务 + Android App）。"
                           "作者 神仙道 1974147300",
            "private": False,
            "has_issues": True,
            "has_wiki": False,
            "auto_init": False,
        })
        print("      已创建：%s" % info["html_url"])
    else:
        print("[1/5] 仓库已存在：%s" % info["html_url"])
    full = info["full_name"]

    # ---------- 2. 上传文件 ----------
    files = tracked_files()
    print("[2/5] 上传 %d 个文件" % len(files))
    tree = []
    for i, rel in enumerate(files, 1):
        with open(rel, "rb") as fh:
            content = fh.read()
        is_binary = b"\0" in content[:8000]
        if is_binary:
            # 二进制走 base64，文本也走 base64 —— 统一，省得编码踩坑
            payload = {"content": base64.b64encode(content).decode(), "encoding": "base64"}
        else:
            payload = {"content": base64.b64encode(content).decode(), "encoding": "base64"}
        _, blob = req("POST", "/repos/%s/git/blobs" % full, tok, payload)
        tree.append({"path": rel, "mode": "100755" if rel.endswith(".sh") else "100644",
                     "type": "blob", "sha": blob["sha"]})
        print("      [%2d/%d] %-52s %6d B" % (i, len(files), rel, len(content)))

    # ---------- 3. 建 tree ----------
    print("[3/5] 建 tree")
    _, new_tree = req("POST", "/repos/%s/git/trees" % full, tok, {"tree": tree})

    # ---------- 4. 建 commit ----------
    print("[4/5] 建 commit")
    head = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True)
    parents = []
    _, ref = req("GET", "/repos/%s/git/ref/heads/%s" % (full, branch), tok, ok=(200, 404))
    if isinstance(ref, dict) and "object" in ref:
        parents = [ref["object"]["sha"]]
        print("      接到已有分支 %s（%s）" % (branch, parents[0][:8]))

    msg = head.stdout.strip()
    if msg:
        msg = git("log", "-1", "--pretty=%B")
    else:
        msg = "WOL Relay v2.1.0"

    body = {"message": msg, "tree": new_tree["sha"], "parents": parents}
    if not parents:
        body["author"] = {"name": "神仙道", "email": "1974147300@qq.com",
                          "date": _iso_now()}
    _, commit = req("POST", "/repos/%s/git/commits" % full, tok, body)
    print("      commit %s" % commit["sha"][:8])

    # ---------- 5. 更新或创建分支 ----------
    print("[5/5] 更新分支 %s" % branch)
    if parents:
        req("PATCH", "/repos/%s/git/refs/heads/%s" % (full, branch), tok,
            {"sha": commit["sha"], "force": False})
    else:
        status, _ = req("POST", "/repos/%s/git/refs" % full, tok,
                        {"ref": "refs/heads/" + branch, "sha": commit["sha"]},
                        ok=(200, 201, 422))
        if status == 422:
            req("PATCH", "/repos/%s/git/refs/heads/%s" % (full, branch), tok,
                {"sha": commit["sha"], "force": True})

    # 默认分支对齐（仓库可能是用别的默认名建的）
    req("PATCH", "/repos/%s" % full, tok, {"default_branch": branch}, ok=(200, 422))

    print()
    print("=== 完成 ===")
    print("仓库：https://github.com/%s" % full)
    print("提交：https://github.com/%s/commit/%s" % (full, commit["sha"]))


def _iso_now():
    import datetime
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


if __name__ == "__main__":
    main()
