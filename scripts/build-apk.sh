#!/bin/bash
# ============================================================
#  打包 App（不依赖 Gradle，手工调用 aapt2 / javac / d8 / apksigner）
#
#  作者：神仙道  QQ 1974147300
#
#  为什么要手工打包：
#    Gradle 首次构建要下几百 MB 依赖，还要联网。
#    这个 App 就一个 Activity，用 build-tools 里的工具直接编，
#    整个流程 10 秒内跑完，离线也能用。
#
#  用法：
#    1. 复制 build.env.example 为 build.env，填上本机 SDK 路径
#    2. bash scripts/build-apk.sh
# ============================================================
set -e

HERE="$(cd "$(dirname "$0")/.." && pwd)"
cd "$HERE"

if [ -f build.env ]; then
    . ./build.env
else
    echo "缺少 build.env"
    echo "先执行：cp build.env.example build.env，然后填上 SDK 路径"
    exit 1
fi

: "${SDK:?build.env 里要设置 SDK（Android SDK 根目录）}"
: "${JDK:?build.env 里要设置 JDK（JDK 11 或 17 的根目录）}"
: "${KEYSTORE:=$HERE/android/app/keystore/wol.jks}"
: "${KEY_ALIAS:=wol}"

# 版本号只有一个来源：module.prop。不在这里再写一份，
# 否则模块和 App 迟早对不上（v2.1.0 时就吃过这个亏）。
# 保留 v 前缀，跟模块 zip 的 wol-relay-vX.Y.Z.zip 保持同一套命名。
VER=$(grep '^version=' "$HERE/module/module.prop" | cut -d= -f2 | tr -d ' \r')
case "$VER" in v*) ;; *) VER="v$VER" ;; esac

# 产物名必须是纯 ASCII。
# 中文文件名传到 GitHub Release 会被替换成 default.apk，
# 或者被过滤成 `-v2.1.1.apk`，下载的人根本不知道自己拿到的是什么。
: "${OUT_APK:=$HERE/dist/WOL-Relay-App-$VER.apk}"

# build-tools 和 platforms 的版本号不写死 —— 每台机器装的版本都不一样。
# 没指定就挑目录里最大的那个（版本号按数字排序，不是按字符串）。
pick_latest() {
    # $1 = 目录；输出其中数字版本号最大的那个子目录名
    ls -1 "$1" 2>/dev/null \
        | grep -E '^[0-9]+(\.[0-9]+)*$' \
        | sort -t. -k1,1n -k2,2n -k3,3n \
        | tail -1
}

if [ -z "$BUILD_TOOLS_VERSION" ]; then
    BUILD_TOOLS_VERSION=$(pick_latest "$SDK/build-tools")
fi
if [ -z "$PLATFORM_VERSION" ]; then
    # 优先 android-33 及以上，太老的 android.jar 缺新 API
    PLATFORM_VERSION=$(ls -1 "$SDK/platforms" 2>/dev/null \
        | grep -E '^android-[0-9]+$' \
        | sed 's/android-//' \
        | sort -n \
        | awk '$1>=33' \
        | tail -1)
    [ -z "$PLATFORM_VERSION" ] && PLATFORM_VERSION=$(ls -1 "$SDK/platforms" 2>/dev/null \
        | grep -E '^android-[0-9]+$' | sed 's/android-//' | sort -n | tail -1)
fi

if [ -z "$BUILD_TOOLS_VERSION" ] || [ -z "$PLATFORM_VERSION" ]; then
    echo "SDK 目录里找不到 build-tools 或 platforms：$SDK"
    echo "用 Android Studio 的 SDK Manager 装一个 build-tools 和任一 platform 即可。"
    exit 1
fi

BT="$SDK/build-tools/$BUILD_TOOLS_VERSION"
ANDROID_JAR="$SDK/platforms/android-$PLATFORM_VERSION/android.jar"
APP="$HERE/android/app"
WORK="$HERE/build/apk"

for p in "$BT/aapt2" "$ANDROID_JAR" "$JDK/bin/javac"; do
    if [ ! -e "$p" ] && [ ! -e "$p.exe" ]; then
        echo "找不到：$p"
        echo "检查 build.env 里的 SDK / JDK / BUILD_TOOLS_VERSION 对不对"
        exit 1
    fi
done

EXE=""
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) EXE=".exe" ;;
esac

echo "SDK        : $SDK"
echo "build-tools: $BUILD_TOOLS_VERSION"
echo "platform   : android-$PLATFORM_VERSION"
echo "JDK        : $JDK"

rm -rf "$WORK"
mkdir -p "$WORK/res" "$WORK/gen" "$WORK/classes" "$WORK/dex" "$(dirname "$OUT_APK")"

echo "[1/5] 编译资源"
"$BT/aapt2$EXE" compile --dir "$APP/res" -o "$WORK/res.zip"

echo "[2/5] 链接资源，生成 R.java"
"$BT/aapt2$EXE" link \
    -o "$WORK/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$APP/AndroidManifest.xml" \
    --java "$WORK/gen" \
    --min-sdk-version 21 \
    --target-sdk-version 33 \
    --auto-add-overlay \
    "$WORK/res.zip"

echo "[3/5] 编译 Java"
# javac 是 Windows 程序，不认 MSYS 的 /c/... 路径，得先转成 c:/...
if command -v cygpath >/dev/null 2>&1; then
    towin() { cygpath -m "$1"; }
else
    towin() { echo "$1"; }
fi
find "$APP/src" "$WORK/gen" -name "*.java" | while read -r f; do towin "$f"; done > "$WORK/sources.txt"
"$JDK/bin/javac$EXE" -source 8 -target 8 -nowarn -encoding UTF-8 \
    -bootclasspath "$(towin "$ANDROID_JAR")" \
    -classpath "$(towin "$ANDROID_JAR")" \
    -d "$(towin "$WORK/classes")" \
    @"$WORK/sources.txt"
echo "      class 文件：$(find "$WORK/classes" -name '*.class' | wc -l) 个"

echo "[4/5] 转成 dex"
find "$WORK/classes" -name "*.class" | while read -r f; do towin "$f"; done > "$WORK/classes.txt"
"$JDK/bin/java$EXE" -cp "$(towin "$BT/lib/d8.jar")" com.android.tools.r8.D8 \
    --min-api 21 --lib "$(towin "$ANDROID_JAR")" --output "$(towin "$WORK/dex")" @"$WORK/classes.txt"

echo "[5/5] 打包并签名"
cp "$WORK/base.apk" "$WORK/unsigned.apk"
python - "$WORK/unsigned.apk" "$WORK/dex/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
PY

# 签名密钥要放在固定位置。如果每次构建都重新生成，
# 覆盖安装必然报 INSTALL_FAILED_UPDATE_INCOMPATIBLE。
if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    if [ -z "$KEYSTORE_PASS" ]; then
        echo ""
        echo "还没有签名密钥，而且没设置 KEYSTORE_PASS。"
        echo "在 build.env 里加一行：KEYSTORE_PASS=你的密码"
        echo "（这个文件已经在 .gitignore 里，不会被提交）"
        exit 1
    fi
    echo "     首次生成签名密钥"
    "$JDK/bin/keytool$EXE" -genkeypair -v -keystore "$KEYSTORE" -alias "$KEY_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 36500 \
        -storepass "$KEYSTORE_PASS" -keypass "$KEYSTORE_PASS" \
        -dname "CN=WOL Remote, OU=Dev, O=Personal, C=CN" >/dev/null 2>&1
else
    echo "     复用已有签名密钥"
fi

"$BT/zipalign$EXE" -f 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"

# 有些 build-tools 包里 apksigner 只有 .bat，没有 .exe（Windows 版常见）
SIGNER="$BT/apksigner$EXE"
if [ ! -f "$SIGNER" ]; then
    if [ -f "$BT/apksigner.bat" ]; then
        SIGNER="$BT/apksigner.bat"
        # .bat 内部靠 JAVA_HOME 找 java，必须显式设过去
        export JAVA_HOME="$(towin "$JDK")"
        export PATH="$(towin "$JDK/bin"):$PATH"
    else
        echo "找不到 apksigner，检查 build-tools 目录：$BT"
        exit 1
    fi
fi

"$SIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-pass "pass:$KEYSTORE_PASS" \
    --key-pass "pass:$KEYSTORE_PASS" \
    --out "$OUT_APK" \
    "$WORK/aligned.apk"

echo ""
echo "=== 打包完成 ==="
ls -lh "$OUT_APK"
