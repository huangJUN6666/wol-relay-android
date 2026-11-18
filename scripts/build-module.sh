#!/bin/bash
# ============================================================
#  打包 Magisk 模块 zip
#
#  作者：神仙道  QQ 1974147300
#
#  做的事：
#    1. 把 Go 中继交叉编译成 android/arm64 静态二进制
#    2. 组装模块目录（module.prop / service.sh / customize.sh / config.example.json）
#    3. 打成 zip，文件名带版本号
#
#  用法：
#    bash scripts/build-module.sh              # 只出 arm64
#    ARCHS="arm64 arm" bash scripts/build-module.sh
# ============================================================
set -e

HERE="$(cd "$(dirname "$0")/.." && pwd)"
cd "$HERE"

: "${GO:?请设置 GO 环境变量指向 go 可执行文件，或在 PATH 里放 go}"
: "${ARCHS:=arm64}"

VERSION=$(grep '^version=' module/module.prop | cut -d= -f2)
VERSION_CODE=$(grep '^versionCode=' module/module.prop | cut -d= -f2)
OUT_DIR="$HERE/dist"
STAGE="$HERE/build/module"

echo "版本      : $VERSION ($VERSION_CODE)"
echo "架构      : $ARCHS"

rm -rf "$STAGE"
mkdir -p "$STAGE" "$OUT_DIR"

# ---- 1. 编译中继 ----
# 为什么用 GOOS=linux 而不是 GOOS=android：
#   Go 1.22 之后 android/arm（32 位）必须开 cgo，本机没装 NDK 就编不过；
#   android/arm64 虽然能编，但和 linux/arm64 出来的二进制没有区别。
#   统一用 linux + CGO_ENABLED=0 + netgo，纯静态、无动态库依赖，
#   放到 Android 上直接跑。/proc、socket、ioctl 这些它用到的接口两边一致。
for arch in $ARCHS; do
    TARGET="linux/$arch"
    EXTRA=""
    case "$arch" in
        arm|armv7)  TARGET="linux/arm"; EXTRA="GOARM=7" ;;
        arm64|aarch64) TARGET="linux/arm64" ;;
        amd64|x86_64)  TARGET="linux/amd64" ;;
    esac
    echo "[1/3] 编译中继 -> $TARGET"
    ( cd relay && \
      env GOOS=linux GOARCH="$arch" $EXTRA CGO_ENABLED=0 \
      "$GO" build -trimpath -tags netgo,osusergo -ldflags "-s -w" \
          -o "$STAGE/wolrelay.$arch" . )
    echo "      $(ls -lh "$STAGE/wolrelay.$arch" | awk '{print $5}')"
done

# ---- 2. 组装模块 ----
echo "[2/3] 组装模块"
cp module/module.prop              "$STAGE/"
cp module/service.sh               "$STAGE/"
cp module/customize.sh             "$STAGE/"
cp module/doctor.sh                "$STAGE/"
cp module/config.example.json      "$STAGE/"
cp -r module/META-INF              "$STAGE/" 2>/dev/null || {
    mkdir -p "$STAGE/META-INF/com/google/android"
    cp module/update-binary "$STAGE/META-INF/com/google/android/update-binary"
    echo "#MAGISK" > "$STAGE/META-INF/com/google/android/updater-script"
}

chmod 755 "$STAGE/service.sh" "$STAGE/customize.sh" "$STAGE/doctor.sh"

# 多架构：默认放 arm64，其余架构作为附加文件，
# 由 customize.sh 按 ABI 挑选（见脚本里的 ABI 处理逻辑）
PRIMARY=$(echo "$ARCHS" | awk '{print $1}')
mv "$STAGE/wolrelay.$PRIMARY" "$STAGE/wolrelay"
chmod 755 "$STAGE/wolrelay"
for arch in $ARCHS; do
    [ "$arch" = "$PRIMARY" ] && continue
    mv "$STAGE/wolrelay.$arch" "$STAGE/wolrelay.$arch.bin"
    # 权限也要给 —— customize.sh 是 mv 改名，权限跟着文件走。
    # 这里若是 644，装到 32 位设备上换了名还是不能执行。
    chmod 755 "$STAGE/wolrelay.$arch.bin"
done

chmod -R 755 "$STAGE"

# ---- 3. 打 zip ----
# 不依赖 zip 命令 —— Windows 上默认没有，用 python 打更稳
echo "[3/3] 打包 zip"
ZIP="$OUT_DIR/wol-relay-$VERSION.zip"
rm -f "$ZIP"
python - "$STAGE" "$ZIP" <<'PY'
import os, sys, zipfile
stage, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(stage):
        dirs.sort(); files.sort()
        for f in files:
            full = os.path.join(root, f)
            rel = os.path.relpath(full, stage).replace('\\', '/')
            # 只有需要执行的文件给 755：
            #   *.sh（customize / service / doctor）
            #   wolrelay / wolrelay.<架构>.bin（中继二进制，含备用架构那份）
            #   update-binary（Magisk 安装入口）
            # 其余给 644，配置和 prop 不需要执行权限
            need_x = (rel.endswith('.sh')
                      or rel == 'wolrelay'
                      or rel.startswith('wolrelay.') and rel.endswith('.bin')
                      or rel.endswith('update-binary'))
            info = zipfile.ZipInfo(rel)
            info.external_attr = (0o755 if need_x else 0o644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            with open(full, 'rb') as fh:
                z.writestr(info, fh.read())
print("    zipfile 打包完成")
PY

echo ""
echo "=== 打包完成 ==="
ls -lh "$ZIP"
echo ""
echo "校验内容："
python - "$ZIP" <<'PY'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
for i in z.infolist():
    print("  %-48s %10d" % (i.filename, i.file_size))
PY
