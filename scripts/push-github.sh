#!/bin/bash
# ============================================================
#  一键推送：建仓库 → 推代码 → 发 Release
#
#  作者：神仙道  QQ 1974147300
#
#  为什么需要这个脚本：
#    首次上传要做三件事，顺序错了就得重来：
#    1. 在 GitHub 上建一个空仓库（不要勾 README，否则 push 会冲突）
#    2. 推送代码
#    3. 打 tag 触发 CI 出包
#    这个脚本按顺序做完，中间任何一步失败都会停下来告诉你原因。
#
#  用法：
#    bash scripts/push-github.sh huangJUN6666 wol-relay-android
#
#  需要先登录一次（浏览器会弹出来）：
#    gh auth login
#  ============================================================
set -e

HERE="$(cd "$(dirname "$0")/.." && pwd)"
cd "$HERE"

OWNER="$1"
REPO="$2"
BRANCH="${BRANCH:-main}"

if [ -z "$OWNER" ] || [ -z "$REPO" ]; then
    echo "用法：bash scripts/push-github.sh <用户名> <仓库名>"
    echo "例如：bash scripts/push-github.sh huangJUN6666 wol-relay-android"
    exit 1
fi

# ---------- 前置检查 ----------
if ! command -v gh >/dev/null 2>&1; then
    GH="$HOME/.local/gh/bin/gh.exe"
    if [ -x "$GH" ]; then
        gh() { "$GH" "$@"; }
    else
        echo "找不到 gh（GitHub CLI）。"
        echo "去 https://cli.github.com 装一个，或者用 winget install GitHub.cli"
        exit 1
    fi
fi

if ! gh auth status >/dev/null 2>&1; then
    echo "还没登录 GitHub。现在弹浏览器，登完再跑一次这个脚本。"
    echo ""
    gh auth login --hostname github.com --git-protocol https --web
    exit 0
fi

echo "已登录：$(gh api user --jq .login 2>/dev/null || echo '(读不到账号)')"
echo ""

# ---------- 1. 建仓库 ----------
if gh repo view "$OWNER/$REPO" >/dev/null 2>&1; then
    echo "[1/3] 仓库已存在，跳过创建：https://github.com/$OWNER/$REPO"
else
    echo "[1/3] 创建仓库 $OWNER/$REPO（公开）"
    gh repo create "$OWNER/$REPO" \
        --public \
        --description "把一台闲置的 root 安卓机变成开机跳板：Magisk 模块守护中继，HTTP 触发局域网 WOL 魔术包。作者：神仙道"
fi

# ---------- 2. 推代码 ----------
echo "[2/3] 推送代码到 $BRANCH"
if git remote get-url origin >/dev/null 2>&1; then
    git remote set-url origin "https://github.com/$OWNER/$REPO.git"
else
    git remote add origin "https://github.com/$OWNER/$REPO.git"
fi

# 本地分支可能叫 master，统一成 main
CUR=$(git branch --show-current)
if [ "$CUR" != "$BRANCH" ]; then
    git branch -M "$BRANCH"
fi

git push -u origin "$BRANCH"

# ---------- 3. 发版 ----------
TAG=$(grep -E '^version=' module/module.prop | cut -d= -f2)
echo "[3/3] 打 tag $TAG，触发 CI 出包"
bash scripts/release.sh "$TAG"

echo ""
echo "=== 全部完成 ==="
echo "仓库  : https://github.com/$OWNER/$REPO"
echo "Release: https://github.com/$OWNER/$REPO/releases/tag/$TAG"
echo ""
echo "CI 大约两分钟出包。第一次跑如果失败，基本是这两个原因："
echo "  1. KEYSTORE_B64 没配 —— 能用，但签名是临时的，用户无法覆盖安装"
echo "  2. build-tools 版本对不上 —— 看 CI 日志里 build.env 那几行"
