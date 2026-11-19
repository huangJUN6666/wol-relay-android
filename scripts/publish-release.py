#!/usr/bin/env python3
# ============================================================
#  发 Release：把 dist/ 里的产物传上去，标注「Root 版 / Magisk 模块」
#
#  作者：神仙道  1974147300
#
#  前提：仓库已经有至少一个 commit（先跑 push-via-api.py）
#
#  用法：
#    export GH_TOKEN=ghp_xxxx
#    python scripts/publish-release.py
#    python scripts/publish-release.py v2.1.2
# ============================================================
import json
import mimetypes
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.github.com"
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def token():
    t = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not t:
        sys.exit("缺少 GH_TOKEN。先 export GH_TOKEN=ghp_xxxx 再跑。")
    return t.strip()


def req(method, path, tok, body=None, ok=(200, 201, 204), raw=False, ctype=None):
    url = path if path.startswith("http") else API + path
    data = body if raw else (json.dumps(body).encode() if body is not None else None)
    r = urllib.request.Request(url, data=data, method=method)
    r.add_header("Authorization", "Bearer " + tok)
    r.add_header("Accept", "application/vnd.github+json")
    r.add_header("X-GitHub-Api-Version", "2022-11-28")
    r.add_header("User-Agent", "wol-relay-release")
    if data:
        r.add_header("Content-Type", ctype or "application/json")
    try:
        with urllib.request.urlopen(r, timeout=180) as resp:
            b = resp.read()
            return resp.status, (json.loads(b) if b and not raw else {})
    except urllib.error.HTTPError as e:
        b = e.read()
        try:
            d = json.loads(b)
        except Exception:
            d = {"raw": b[:400].decode("utf-8", "replace")}
        if e.code in ok:
            return e.code, d
        sys.exit("%s %s -> %s %s" % (method, path, e.code,
                                     d.get("message", "")[:300]))


def read_version():
    p = os.path.join(HERE, "module", "module.prop")
    v = "v2.1.2"
    for line in open(p, encoding="utf-8"):
        if line.startswith("version="):
            v = line.split("=", 1)[1].strip()
    return v


def body_text(tag):
    return """## WOL 远程开机 %s

### 【Root 版 / Magisk 模块】—— 主推方案
`wol-relay-%s.zip`

刷入方式：Magisk / KernelSU / APatch 里「从本地安装」选这个 zip，重启生效。

带 root 的实际意义（不吹，就这三条）：
1. **进程活得久**。模块由 `magiskd` 以 root 身份拉起，不归 App 沙箱管，
   厂商省电策略清不到它；进程退出会被 `service.sh` 重新拉起，连续崩按 6→12→…→300 秒退避。
2. **开机就跑**。`service.sh` 在 `post-fs-data` 之后执行，不用等用户解锁、不用先进 App。
3. **首发包就能发出去**。root 身份下打开 `SO_BROADCAST` 不受厂商 SELinux 策略干扰。

技术实现是标准 UDP 魔术包（102 字节，6×FF + 16 次 MAC），走 `255.255.255.255`
或各网卡的定向广播地址，**没有用 raw socket、不做抓包**。这一点写清楚，
免得有人以为是底层注入。

装完必做：
1. 编辑 `/data/adb/wol-relay/config.json`，把 `mac` 改成你要唤醒的网卡
2. 重启手机
3. 跑自检：`su -c 'sh /data/adb/modules/wol-relay/doctor.sh'`

### 【配套 App】WOL-Relay-App-<版本>.apk
不是必须装。装了方便：一键自检、扫局域网找中继、失败时直接告诉你卡在哪一层。
不装也行，浏览器打开中继地址就是同一个按钮。

### 支持设备
- 手机端（中继）：arm64 / armeabi-v7a，装的时候按机型自动挑，包内两个二进制都带了
- 电脑端：任何支持 Wake-on-LAN 的**有线**网卡（WiFi 网卡关机后断电，唤不醒）
- 路由器：理论上能跑任意静态 Go 二进制，但**没实测**，别当官方支持

详细步骤见仓库 `docs/` 目录，六篇，从电脑设置到排错都有。

---
作者：神仙道　1974147300
""" % (tag, tag)


def main():
    tok = token()
    tag = sys.argv[1] if len(sys.argv) > 1 else read_version()

    _, me = req("GET", "/user", tok)
    login = me["login"]
    repo = os.environ.get("REPO", "wol-relay-android")
    full = "%s/%s" % (login, repo)
    print("仓库: %s   版本: %s\n" % (full, tag))

    # ---------- 打 tag ----------
    _, ref = req("GET", "/repos/%s/git/ref/heads/main" % full, tok)
    sha = ref["object"]["sha"]
    status, _ = req("POST", "/repos/%s/git/refs" % full, tok,
                    {"ref": "refs/tags/" + tag, "sha": sha}, ok=(201, 422))
    print("[1/3] tag %s -> %s%s" % (tag, sha[:8], "（已存在）" if status == 422 else ""))

    # ---------- Release ----------
    status, rel = req("GET", "/repos/%s/releases/tags/%s" % (full, tag), tok, ok=(200, 404))
    if status == 404:
        _, rel = req("POST", "/repos/%s/releases" % full, tok, {
            "tag_name": tag,
            "name": "WOL 远程开机 %s（Root 版 / Magisk 模块 + App）" % tag,
            "body": body_text(tag),
            "draft": False,
            "prerelease": False,
        })
        print("[2/3] Release 已创建")
    else:
        print("[2/3] Release 已存在，复用")
    upload_url = rel["upload_url"].split("{")[0]

    # ---------- 上传产物 ----------
    dist = os.path.join(HERE, "dist")
    wanted = []
    for f in sorted(os.listdir(dist)) if os.path.isdir(dist) else []:
        # .idsig 是 apksigner 生成的签名附属文件，装应用用不上，别传到 Release 里
        if f.endswith((".zip", ".apk")) and not f.endswith(".idsig"):
            wanted.append(f)
    if not wanted:
        sys.exit("dist/ 里没有 zip 或 apk，先跑 build 脚本")

    # 只传纯 ASCII 文件名。中文名传上去会被 GitHub 换成 default.apk 或被过滤掉，
    # 与其传完再改名，不如在这里直接拦下来。
    bad = [f for f in wanted if not f.isascii()]
    if bad:
        sys.exit("这些产物名不是纯 ASCII，先改名再传：\n  " + "\n  ".join(bad))

    print("[3/3] 上传 %d 个产物" % len(wanted))
    for f in wanted:
        path = os.path.join(dist, f)
        label = "Root 版 / Magisk 模块" if f.endswith(".zip") else "配套 App"
        _, assets = req("GET", "/repos/%s/releases/%d/assets" % (full, rel["id"]), tok)
        if any(a["name"] == f for a in assets):
            print("      %-32s 已存在，跳过" % f)
            continue
        with open(path, "rb") as fh:
            data = fh.read()
        ctype = mimetypes.guess_type(f)[0] or "application/octet-stream"
        req("POST", upload_url + "?name=" + urllib.parse.quote(f), tok,
            data, raw=True, ctype=ctype, ok=(200, 201))
        print("      %-32s %8.1f KB  [%s]" % (f, len(data) / 1024, label))

    print("\n=== 完成 ===")
    print("https://github.com/%s/releases/tag/%s" % (full, tag))


if __name__ == "__main__":
    main()
