#!/bin/bash
# ============================================================
#  版本号一致性检查
#
#  作者：神仙道  QQ 1974147300
#
#  为什么需要这个：
#    版本号散在四个地方 —— module.prop、relay/main.go、AndroidManifest.xml、
#    README/CHANGELOG。改的时候漏一个，就会出现下面这种情况：
#      * 中继 /status 报旧版本 → App 判断「中继过旧」→ 明明能用却提示升级
#      * 模块 versionCode 比 App 低 → 商店/ROM 的升级判断乱掉
#    v2.1.0 那次就是这么踩的，所以固化成脚本，提交前跑一下。
#
#  用法：
#    bash scripts/check-version.sh
#  退出码 0 = 一致；1 = 有不一致
# ============================================================
set -u

HERE="$(cd "$(dirname "$0")/.." && pwd)"
cd "$HERE"

fail=0
say() { printf '%-34s %s\n' "$1" "$2"; }
bad() { say "$1" "$2"; fail=1; }

# ---- 1. module.prop：唯一权威来源 ----
PROP="module/module.prop"
MP_VER=$(grep '^version=' "$PROP" | cut -d= -f2 | tr -d 'v' | tr -d ' \r')
MP_CODE=$(grep '^versionCode=' "$PROP" | cut -d= -f2 | tr -d ' \r')
[ -z "$MP_VER" ] && { bad "module.prop version" "读取失败"; MP_VER="?"; }
say "module.prop version" "$MP_VER  (code $MP_CODE)"

# ---- 2. relay/main.go 里的 const version ----
GO_VER=$(grep -oE 'const version = "[^"]+"' relay/main.go | sed 's/.*"\(.*\)"/\1/')
[ -z "$GO_VER" ] && bad "relay/main.go version" "没找到 const version" \
                 || { [ "$GO_VER" = "$MP_VER" ] && say "relay/main.go version" "$GO_VER" \
                      || bad "relay/main.go version" "$GO_VER  ← 应为 $MP_VER"; }

# ---- 3. AndroidManifest 的 versionName / versionCode ----
MAN="android/app/AndroidManifest.xml"
AN_NAME=$(grep -oE 'android:versionName="[^"]+"' "$MAN" | sed 's/.*"\(.*\)"/\1/')
AN_CODE=$(grep -oE 'android:versionCode="[^"]+"' "$MAN" | sed 's/.*"\(.*\)"/\1/')
[ "$AN_NAME" = "$MP_VER" ] && say "AndroidManifest versionName" "$AN_NAME" \
                           || bad "AndroidManifest versionName" "$AN_NAME  ← 应为 $MP_VER"

# versionCode 只有一条硬要求：两边都要随版本单调递增。
# 历史上 App 是 21001、模块是 20101，数字不同但都对应 2.1.x，
# 强行改成一样会让已装 21001 的用户被判成降级、装不上，
# 所以这里只提示不报错。
if [ "$AN_CODE" = "$MP_CODE" ]; then
    say "versionCode（App / 模块）" "$AN_CODE / $MP_CODE"
else
    say "versionCode（App / 模块）" "$AN_CODE / $MP_CODE  （不同但同版本，允许）"
fi

case "$MP_CODE" in
    ''|*[!0-9]*) bad "module.prop versionCode" "不是纯数字：$MP_CODE" ;;
    *)           [ "$MP_CODE" -gt 0 ] || bad "module.prop versionCode" "必须 > 0" ;;
esac
case "$AN_CODE" in
    ''|*[!0-9]*) bad "AndroidManifest versionCode" "不是纯数字：$AN_CODE" ;;
    *)           [ "$AN_CODE" -gt 0 ] || bad "AndroidManifest versionCode" "必须 > 0" ;;
esac

# ---- 4. README 里写死的产物名 ----
grep -q "wol-relay-v$MP_VER.zip" README.md \
    && say "README 模块产物名" "wol-relay-v$MP_VER.zip" \
    || bad "README 模块产物名" "没提到 wol-relay-v$MP_VER.zip"

grep -q "WOL-Relay-App-.*\.apk" README.md \
    && say "README APK 名" "ASCII 命名 ✅" \
    || say "README APK 名" "未使用 ASCII 名（建议改）"

# ---- 5. CHANGELOG 是否有这一版 ----
grep -q "^## v$MP_VER" CHANGELOG.md \
    && say "CHANGELOG" "有 v$MP_VER 章节" \
    || bad "CHANGELOG" "缺少 v$MP_VER 章节"

echo
if [ "$fail" -eq 0 ]; then
    echo "✅ 版本号全部一致：v$MP_VER"
else
    echo "❌ 有版本号不一致，按上面 ← 提示改"
fi
exit "$fail"
