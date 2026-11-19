#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用 GitHub 设备授权流程（device flow）拿一个临时 token，然后推代码。

为什么要这个：PAT 要手动勾 scope、容易漏掉 workflow 权限；
设备流只要在浏览器里输一串码点一下同意，不会漏。

用法：
    python scripts/auth-device-flow.py            # 生成码，等你授权（已有未过期的码就复用）
    python scripts/auth-device-flow.py --poll     # 只轮询上一次生成的码
    python scripts/auth-device-flow.py --new      # 强制换一个新码（旧的立即作废）

安全：
    - token 只写到 .git/gh-device-token（已被 .gitignore 忽略），不落别处
    - 用完 `git push` 之后建议删掉：rm .git/gh-device-token
    - 所有输出里都不打印 token 全文
"""
import json
import os
import subprocess
import sys
import time
import urllib.request
import urllib.error

CLIENT_ID = "178c6fc778ccc68e1d6a"  # gh CLI 的公开 client_id
SCOPE = "repo workflow"
STATE = os.path.join(os.path.expanduser("~"), ".wol-device-flow.json")
TOKEN_FILE = ".git/gh-device-token"


def post(url, payload):
    body = json.dumps(payload).encode()
    req = urllib.request.Request(
        url,
        data=body,
        headers={
            "Accept": "application/json",
            "Content-Type": "application/json",
            "User-Agent": "wol-relay-android",
        },
        method="POST",
    )
    # 明确不走代理：这台机器的代理对 github.com 返回 502
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=30) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode())


def remaining(d):
    """这个码还剩几秒可用，0 表示已过期。"""
    born = d.get("created_at")
    if not born:
        # 老版本写下的状态文件没记时间，按已过期处理，免得用户白等
        return 0
    return int(born + d.get("expires_in", 900) - time.time())


def start(force_new=False):
    # 关键：不带 --poll 再跑一次，不能默默换掉已经发出去的码。
    # 之前就是这样——第一次给了码 A，用户去浏览器输，这边又跑了一次生成码 B，
    # 用户输 A 永远 pending，看着像"授权没生效"，其实是自己把 A 作废了。
    if not force_new and os.path.exists(STATE):
        try:
            with open(STATE, encoding="utf-8") as f:
                old = json.load(f)
        except (ValueError, OSError):
            old = {}
        if old.get("user_code") and remaining(old) > 60:
            print()
            print("  已经有一个没过期的码，继续用它（不重新生成）：")
            print("  在浏览器打开：  " + old.get("verification_uri", "https://github.com/login/device"))
            print("  输入这个码：    " + old["user_code"])
            print("  还剩：          %d 分钟" % (remaining(old) // 60))
            print()
            print("  想换一个新码，加 --new")
            print()
            return old

    d = post("https://github.com/login/device/code",
             {"client_id": CLIENT_ID, "scope": SCOPE})
    if "device_code" not in d:
        print("生成授权码失败：", d)
        sys.exit(1)
    d["created_at"] = int(time.time())
    with open(STATE, "w", encoding="utf-8") as f:
        json.dump(d, f)
    print()
    print("  在浏览器打开：  " + d["verification_uri"])
    print("  输入这个码：    " + d["user_code"])
    print("  有效期：        %d 分钟" % (d["expires_in"] // 60))
    print()
    print("  输完点 Continue → Authorize 即可，然后回来告诉我。")
    print()
    return d


def poll():
    if not os.path.exists(STATE):
        print("没有待授权的码，先不带 --poll 跑一次")
        sys.exit(1)
    with open(STATE, encoding="utf-8") as f:
        d = json.load(f)
    interval = d.get("interval", 5)
    # 按"这个码还剩多久"算截止，不是按"从现在起再等 15 分钟"
    left = remaining(d)
    if left <= 0:
        print("这个码已经过期了，运行 python scripts/auth-device-flow.py --new 换一个")
        sys.exit(1)
    deadline = time.time() + left
    print("等待授权…（码 %s，还剩 %d 分钟）" % (d["user_code"], left // 60))
    while time.time() < deadline:
        r = post("https://github.com/login/oauth/access_token", {
            "client_id": CLIENT_ID,
            "device_code": d["device_code"],
            "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
        })
        if r.get("access_token"):
            tok = r["access_token"]
            with open(TOKEN_FILE, "w", encoding="utf-8") as f:
                f.write(tok)
            print("✅ 授权成功，token 已存到 %s（%s…%s）"
                  % (TOKEN_FILE, tok[:4], tok[-4:]))
            os.remove(STATE)
            return tok
        err = r.get("error")
        if err == "authorization_pending":
            time.sleep(interval)
            continue
        if err == "slow_down":
            interval += 5
            time.sleep(interval)
            continue
        print("授权失败：", err, r.get("error_description", ""))
        sys.exit(1)
    print("码过期了，重新生成一个")
    sys.exit(1)


def main():
    if "--poll" not in sys.argv:
        start(force_new="--new" in sys.argv)
        print("授权完成后运行：python scripts/auth-device-flow.py --poll")
        return
    if not os.path.isdir(".git"):
        print("请在仓库根目录运行")
        sys.exit(1)
    tok = poll()
    print()
    print("接下来推送（token 只出现在内存里的环境变量，不写进 remote）：")
    print("  GH_TOKEN=<上面那个> bash scripts/push-with-token.sh")


if __name__ == "__main__":
    main()
