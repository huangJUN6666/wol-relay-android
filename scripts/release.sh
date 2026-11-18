#!/bin/bash
# ============================================================
#  一键发版：打 tag → 推送到 GitHub → 触发 CI 出包
#
#  作者：神仙道vq  QQ 1974147300
#
#  用法：
#    bash scripts/release.sh v2.1.0
#
#  为什么要有这个脚本：
#    发布流程里最容易错的两件事，一是 tag 和 module.prop 版本号对不上，
#    二是推了代码忘了推 tag，CI 不出包。这里都检查一遍。
# ============================================================
set -e

HERE="$(cd "$(dirname "$0")/.." && pwd)"
cd "$HERE"

TAG="$1"
if [ -z "$TAG" ]; then
    echo "用法：bash scripts/release.sh v2.1.0"
    exit 1
fi

case "$TAG" in
    v*) ;;
    *)
        echo "tag 要以 v 开头，比如 v2.1.0"
        exit 1
        ;;
esac

# tag 必须和 module.prop 里的 version 一致，否则 Release 上的版本号是假的
PROP_VER=$(grep -E '^version=' module/module.prop | cut -d= -f2)
if [ "$PROP_VER" != "$TAG" ]; then
    echo "版本号对不上："
    echo "  module/module.prop : $PROP_VER"
    echo "  要打的 tag         : $TAG"
    echo ""
    echo "先把 module.prop 改成 $TAG 再发版，或者换个 tag 名。"
    exit 1
fi

# App 的 versionName 也要跟上，不然装到手机上看到的还是旧版本
APP_VER=$(grep -oE 'android:versionName="[^"]*"' android/app/AndroidManifest.xml | cut -d'"' -f2)
if [ "v$APP_VER" != "$TAG" ]; then
    echo "App 版本号对不上："
    echo "  AndroidManifest.xml : $APP_VER"
    echo "  要打的 tag          : $TAG"
    exit 1
fi

if [ -n "$(git status --porcelain)" ]; then
    echo "还有没提交的改动："
    git status --short
    echo ""
    echo "先 commit 完再发版。"
    exit 1
fi

REMOTE="${REMOTE:-origin}"
BRANCH="${BRANCH:-main}"

echo "版本号检查通过：$TAG"
echo ""

echo "[1/3] 推送到 $REMOTE/$BRANCH"
git push "$REMOTE" "$BRANCH"

echo "[2/3] 打 tag"
# 已存在就覆盖，方便改完重推
git tag -f -a "$TAG" -m "release $TAG"
git push -f "$REMOTE" "refs/tags/$TAG"

echo "[3/3] 完成"
echo ""
echo "CI 会自己编译模块 zip 和 APK，然后挂到这个 Release："
echo "  https://github.com/$(git remote get-url "$REMOTE" | sed -E 's#.*[:/]([^/]+/[^/]+?)(\.git)?$#\1#')/releases/tag/$TAG"
echo ""
echo "约两分钟后回来看："
echo "  - wol-relay-$TAG.zip        Magisk 模块（root 方案）"
echo "  - 远程开机.apk              客户端 App"
