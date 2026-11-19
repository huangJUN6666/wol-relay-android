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
import time
import urllib.error
import urllib.request

API = "https://api.github.com"
DEFAULT_REPO = "wol-relay-android"
DEFAULT_BRANCH = "main"

# 脚本可能从任意目录被调用（比如 python scripts/push-via-api.py），
# 兜底分支里要读 module/module.prop，就必须按脚本自身位置算，不能用 cwd。
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

# 这个项目定位是 root 版块。GitHub 上没有"版块"这个概念，靠的是 topics：
# 别人逛 github.com/topics/magisk-module、/kernel-su、/wake-on-lan 才翻得到。
TOPICS = [
    "magisk", "magisk-module", "magisk-modules", "root",
    "kernelsu", "apatch", "android-root",
    "wake-on-lan", "wol", "wakeonlan", "remote-wake",
    "android", "golang", "self-hosted", "home-automation", "tailscale",
]


def token():
    t = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not t:
        sys.exit("缺少 GH_TOKEN。先 export GH_TOKEN=ghp_xxxx 再跑。")
    return t.strip()


def req(method, path, tok, body=None, ok=(200, 201, 204), tries=6):
    """发一个 API 请求。

    带重试：这台机器到 GitHub 的链路不稳，同一条请求可能 3 次里失败 2 次
    （代理会在多个 IP 之间挑，其中有些不可达）。推送一次要发十几个请求，
    不重试的话失败概率叠起来几乎必然中断，而且会停在"传了一半"的中间态。
    只重试网络错误和 5xx —— 4xx 是请求本身的问题，重试也不会变好。
    """
    url = path if path.startswith("http") else API + path
    data = json.dumps(body).encode() if body is not None else None
    last = None
    for attempt in range(1, tries + 1):
        r = urllib.request.Request(url, data=data, method=method)
        r.add_header("Authorization", "Bearer " + tok)
        r.add_header("Accept", "application/vnd.github+json")
        r.add_header("X-GitHub-Api-Version", "2022-11-28")
        r.add_header("User-Agent", "wol-relay-push")
        if data:
            r.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(r, timeout=45) as resp:
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
            if e.code >= 500 and attempt < tries:
                last = "%s %s" % (e.code, detail.get("message", ""))
                print("      (%s 次失败 %s，重试)" % (attempt, last))
                time.sleep(min(2 * attempt, 10))
                continue
            msg = detail.get("message", "")
            errs = detail.get("errors")
            sys.exit("%s %s -> %s %s\n%s" % (method, path, e.code, msg,
                                             json.dumps(errs, ensure_ascii=False) if errs else ""))
        except (urllib.error.URLError, OSError) as e:
            last = str(e)
            if attempt < tries:
                print("      (第 %d 次网络失败，%ds 后重试)" % (attempt, min(2 * attempt, 10)))
                time.sleep(min(2 * attempt, 10))
                continue
            sys.exit("%s %s -> 网络不通，已重试 %d 次：%s" % (method, path, tries, last))


def tracked_files():
    """只上传 git 追踪的文件，保证 dist/、build/、keystore 不会被带上去。"""
    out = subprocess.check_output(
        ["git", "ls-files", "-z"], stderr=subprocess.STDOUT)
    return [p for p in out.decode("utf-8").split("\0") if p]


def git(*args):
    # core.quotepath=false 关掉 git 对非 ASCII 路径的转义。
    # 默认行为会把 docs/03-备用机部署.md 输出成 "docs/03-\345\244\207\347\224\250..."，
    # 带着引号和八进制转义，直接拿来 open() 必然 OSError。
    return subprocess.check_output(
        ["git", "-c", "core.quotepath=false"] + list(args)).decode("utf-8").strip()


def tree_sha_of(commit_sha):
    """把 commit sha 换成它的 tree sha。

    建 tree 时的 base_tree 字段要的是 tree 的 sha，喂 commit sha 进去不会报错，
    但建出来的树是错的 —— 只能靠后面和本地比对才发现，属于很难查的坑。
    """
    return git("rev-parse", commit_sha + "^{tree}")


def mode_of(path):
    """取文件在本地索引里的真实 mode，不要按扩展名猜。

    Windows 上 checkout 出来的脚本通常是 100644，我原来按 .sh/.py 硬判 100755，
    结果建出来的树和本地差一个 mode 位，校验能拦住但白跑一趟。
    """
    out = git("ls-files", "-s", "--", path)
    return out.split()[0] if out else "100644"


def changed_against(remote_sha):
    """远端已有的提交之后，本地改了哪些文件。

    返回 (changed, deleted, base_ok)：
      changed —— [(path, status)]，status 为 M / A
      deleted —— [path]
      base_ok —— 远端那个 sha 本地有没有（没有就没法算 diff、也没法复现提交）

    只传改动是必须的：整仓 63 个文件全传一遍要 63 次 blob 请求，
    每次都要重新走一遍抖动不稳的 TLS，失败概率叠 63 次约等于必然失败。
    """
    has = subprocess.run(["git", "cat-file", "-e", remote_sha + "^{commit}"],
                         capture_output=True)
    if has.returncode != 0:
        return [], [], False
    out = git("diff", "--name-status", remote_sha, "HEAD")
    changed, deleted = [], []
    for line in out.splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        st = parts[0][:1]
        if st == "D":
            deleted.append(parts[1])
        elif st == "R":
            # 重命名：旧路径删掉，新路径上传
            deleted.append(parts[1])
            changed.append((parts[2], "M"))
        else:
            changed.append((parts[1], st))
    return changed, deleted, True


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

    # ---------- 2. 找出要不要增量 ----------
    # 先看远端分支现在停在哪，如果那个提交本地也有，就只传 diff；
    # 否则退回全量上传（比如本地历史被改过、或换了台机器）。
    remote_head = None
    _, ref = req("GET", "/repos/%s/git/ref/heads/%s" % (full, branch), tok, ok=(200, 404))
    if isinstance(ref, dict) and "object" in ref:
        remote_head = ref["object"]["sha"]

    changed, deleted, can_incr = ([], [], False)
    if remote_head:
        changed, deleted, can_incr = changed_against(remote_head)

    # ---------- 3. 上传 blob ----------
    if can_incr:
        print("[2/5] 增量：远端停在 %s，本地领先 %d 个提交"
              % (remote_head[:8],
                 int(git("rev-list", "--count", "%s..HEAD" % remote_head))))
        print("      改动 %d 个文件，删除 %d 个" % (len(changed), len(deleted)))
        if not changed and not deleted:
            print("\n远端已经是最新，无需推送。")
            return
        entries = []
        for i, (rel, st) in enumerate(changed, 1):
            with open(rel, "rb") as fh:
                content = fh.read()
            _, blob = req("POST", "/repos/%s/git/blobs" % full, tok,
                          {"content": base64.b64encode(content).decode(),
                           "encoding": "base64"})
            mode = mode_of(rel)
            entries.append({"path": rel, "mode": mode, "type": "blob",
                            "sha": blob["sha"]})
            print("      [%d/%d] %s %-50s %6d B"
                  % (i, len(changed), st, rel, len(content)))
        for rel in deleted:
            # sha 传 null 就是删掉这个路径
            entries.append({"path": rel, "mode": "100644", "type": "blob", "sha": None})
            print("      删除 %s" % rel)
        base_tree = tree_sha_of(remote_head)
    else:
        files = tracked_files()
        print("[2/5] 全量上传 %d 个文件（远端提交本地没有，无法算 diff）" % len(files))
        entries = []
        for i, rel in enumerate(files, 1):
            with open(rel, "rb") as fh:
                content = fh.read()
            # 文本和二进制都走 base64：省得判断编码，代价只是体积大 1/3
            _, blob = req("POST", "/repos/%s/git/blobs" % full, tok,
                          {"content": base64.b64encode(content).decode(),
                           "encoding": "base64"})
            entries.append({"path": rel,
                            "mode": mode_of(rel),
                            "type": "blob", "sha": blob["sha"]})
            print("      [%2d/%d] %-52s %6d B" % (i, len(files), rel, len(content)))
        base_tree = None

    # ---------- 4. 建 tree ----------
    print("[3/5] 建 tree")
    tree_body = {"tree": entries}
    if base_tree:
        tree_body["base_tree"] = base_tree
    _, new_tree = req("POST", "/repos/%s/git/trees" % full, tok, tree_body)

    # 校验：API 建出来的 tree 必须和本地 commit 的 tree 一模一样。
    # 不一样就说明有文件被漏掉或模式不对，这时候推上去会得到一棵残缺的树 ——
    # 而远端看起来"推送成功"，问题要等到有人 clone 下来才发现。
    local_tree = git("rev-parse", "HEAD^{tree}")
    if new_tree["sha"] != local_tree:
        sys.exit("tree 不一致：远端 %s ≠ 本地 %s\n"
                 "多半是有文件没传上去或 mode 不对，已中止，远端未改动。"
                 % (new_tree["sha"], local_tree))
    print("      与本地 tree 一致：%s" % local_tree[:12])

    # ---------- 5. 建 commit ----------
    print("[4/5] 建 commit")
    parents = [remote_head] if remote_head else []

    # 用本地提交的元数据（作者、提交者、时间、消息），好让 SHA 也对得上。
    # 对不上的话远端会生成一个新 SHA，本地和远端从此各走一条历史，
    # 下次 push 就得 force，很容易把别人的东西冲掉。
    msg = git("log", "-1", "--pretty=%B")
    an = git("log", "-1", "--pretty=%an")
    ae = git("log", "-1", "--pretty=%ae")
    ad = git("log", "-1", "--pretty=%aI")
    cn = git("log", "-1", "--pretty=%cn")
    ce = git("log", "-1", "--pretty=%ce")
    cd = git("log", "-1", "--pretty=%cI")

    body = {
        "message": msg,
        "tree": new_tree["sha"],
        "parents": parents,
        "author": {"name": an, "email": ae, "date": ad},
        "committer": {"name": cn, "email": ce, "date": cd},
    }
    _, commit = req("POST", "/repos/%s/git/commits" % full, tok, body)
    local_head = git("rev-parse", "HEAD")
    if commit["sha"] == local_head:
        print("      commit %s（与本地一致）" % commit["sha"][:12])
    else:
        print("      commit %s（本地 %s，SHA 不同但内容相同）"
              % (commit["sha"][:12], local_head[:12]))

    # ---------- 6. 更新或创建分支 ----------
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

    # ---------- 7. tag ----------
    # 只推 main 不会触发 CI：workflow 的 on.push.tags 只认 v* tag。
    # tag 这里单独处理，因为 git push 不通就只能靠 API 建。
    tag_name = os.environ.get("TAG", "").strip()
    if tag_name:
        print("[+] 建 tag %s" % tag_name)
        # annotated tag 更规范，release 页才会显示打 tag 的时间
        target = git("rev-parse", "%s^{commit}" % tag_name)
        tag_msg = git("tag", "-l", "--format=%(contents)", tag_name) or tag_name
        try:
            sha, _ = req("POST", "/repos/%s/git/tags" % full, tok, {
                "tag": tag_name, "message": tag_msg,
                "object": target, "type": "commit",
            })
            tag_sha = sha["sha"]
        except SystemExit:
            print("      建 tag 对象失败，退回轻量 tag（不影响 Release）")
            tag_sha = target
        status, _ = req("POST", "/repos/%s/git/refs" % full, tok,
                        {"ref": "refs/tags/" + tag_name, "sha": tag_sha},
                        ok=(200, 201, 422))
        if status == 422:
            print("      tag 已存在，跳过")
        else:
            print("      tag %s -> %s" % (tag_name, target[:12]))

    # ---------- 8. topics ----------
    # root 圈子基本靠 topic 页逛（github.com/topics/magisk-module 这种），
    # 不打标签的话没人搜得到，等于白传。
    print("[+] 打 topics")
    try:
        req("PUT", "/repos/%s/topics" % full, tok, {"names": TOPICS}, ok=(200, 422))
        print("      " + " ".join(TOPICS))
    except SystemExit:
        print("      topics 设置失败（token 可能没有 administration 权限），不影响代码")
    except Exception as e:
        print("      topics 设置失败：%s，不影响代码" % e)

    print()
    print("=== 完成 ===")
    print("仓库：https://github.com/%s" % full)
    print("提交：https://github.com/%s/commit/%s" % (full, commit["sha"]))
    print("Root 话题页：https://github.com/topics/magisk-module")


def _iso_now():
    import datetime
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


if __name__ == "__main__":
    main()
