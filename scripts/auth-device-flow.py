#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用 GitHub 设备授权流程（device flow）拿一个临时 token，然后推代码。

为什么要这个：PAT 要手动勾 scope、容易漏掉 workflow 权限；
设备流只要在浏览器里输一串码点一下同意，不会漏。

用法：
    python scripts/auth-device-flow.py            # 生成码，等你授权
    python scripts/auth-device-flow.py --poll     # 只轮询上一次生成的码

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


def start():
    d = post("https://github.com/login/device/code",
             {"client_id": CLIENT_ID, "scope": SCOPE})
    if "device_code" not in d:
        print("生成授权码失败：", d)
        sys.exit(1)
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
    deadline = time.time() + d.get("expires_in", 900)
    print("等待授权…（码 %s）" % d["user_code"])
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
        start()
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
