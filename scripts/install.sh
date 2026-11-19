#!/bin/sh
# ============================================================
#  中继一键安装脚本（不需要 root，只要 sudo 或已经是 root）
#
#  作者：神仙道  QQ 1974147300
#
#  解决的问题：
#    这个项目原来的门槛是"得有一台 root 过的安卓机"。实际上中继只是一个
#    30KB 的纯静态 Go 程序，唯一的硬要求是"有一台常开的 Linux 且能开机自启"。
#    路由器、NAS、树莓派、旧笔记本、群晖、OpenWrt、Docker 宿主机全都满足。
#    这道门槛是发布方式造成的，不是技术造成的 —— 所以改发布方式。
#
#  用法：
#    # 最省事：直接跑
#    curl -fsSL https://raw.githubusercontent.com/huangJUN6666/wol-relay-android/main/scripts/install.sh | sudo sh
#
#    # 或者先下载再跑（想看清内容的话推荐这个）
#    sudo sh install.sh
#
#    # 指定版本和安装目录
#    sudo VERSION=v2.2.1 PREFIX=/opt/wolrelay sh install.sh
#
#    # 只打印将要做什么，不实际改动系统
#    sh install.sh --dry-run
#
#  卸载：
#    sudo sh install.sh --uninstall
# ============================================================
set -eu

REPO="${REPO:-huangJUN6666/wol-relay-android}"
VERSION="${VERSION:-latest}"
PREFIX="${PREFIX:-/opt/wolrelay}"
CONF_DIR="${CONF_DIR:-/etc/wolrelay}"
SERVICE_NAME="${SERVICE_NAME:-wolrelay}"

DRY=0
UNINSTALL=0
for a in "$@"; do
    case "$a" in
        --dry-run)   DRY=1 ;;
        --uninstall) UNINSTALL=1 ;;
        -h|--help)
            sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *) echo "不认识的参数：$a" >&2; exit 2 ;;
    esac
done

# ---------- 输出 ----------
# 不用彩色：这个脚本会跑在 OpenWrt 的 ash 里，也可能被 `curl | sh` 调用，
# 输出重定向到文件时转义序列会变成乱码。
say()  { printf '%s\n' "$*"; }
step() { printf '\n[%s] %s\n' "$1" "$2"; }
warn() { printf '    注意：%s\n' "$*" >&2; }
die()  { printf '\n出错了：%s\n' "$*" >&2; exit 1; }

run() {
    if [ "$DRY" = 1 ]; then
        printf '    (dry-run) %s\n' "$*"
    else
        "$@"
    fi
}

# ---------- 环境判断 ----------
need_root() {
    # dry-run 不碰系统，不需要 root —— 不然"先看看它会做什么"这件事
    # 反而要先交出权限，那这个开关就没意义了。
    if [ "$DRY" = 1 ]; then
        return 0
    fi
    # 已经是 root 就直接过；能 sudo 就用 sudo 重跑自己。
    # 不假设 sudo 存在 —— OpenWrt 上通常只有 root，没有 sudo。
    if [ "$(id -u)" = 0 ]; then
        return 0
    fi
    if command -v sudo >/dev/null 2>&1; then
        say "需要用 root 权限安装，正在通过 sudo 重新执行…"
        exec sudo -E sh "$0" "$@"
    fi
    die "请用 root 运行：su -c 'sh $0'"
}

detect_arch() {
    # uname -m 在各平台上的写法不统一，宁多勿少。
    m=$(uname -m 2>/dev/null || echo unknown)
    case "$m" in
        x86_64|amd64)      echo amd64 ;;
        aarch64|arm64)     echo arm64 ;;
        armv7l|armv7|armhf|arm) echo armv7 ;;
        armv6l|armv6)      echo armv6 ;;
        i386|i486|i586|i686|x86) echo 386 ;;
        mips64)            echo mips64 ;;
        mips)              echo mips ;;
        mipsel|mipsle)     echo mipsle ;;
        riscv64)           echo riscv64 ;;
        ppc64le)           echo ppc64le ;;
        s390x)             echo s390x ;;
        *)                 echo "" ;;
    esac
}

detect_init() {
    # 顺序有讲究：有 systemctl 不代表能用（容器里常常是假的），
    # 所以还要确认 PID 1 真的是 systemd。
    if [ -d /run/systemd/system ] && command -v systemctl >/dev/null 2>&1; then
        echo systemd; return
    fi
    # OpenWrt / 部分嵌入式
    if [ -f /etc/openwrt_release ] || command -v procd >/dev/null 2>&1 \
       || [ -x /sbin/procd ]; then
        echo procd; return
    fi
    # 群晖 DSM —— 它有自己的 synology 服务框架，但 upstart/init.d 也能用
    if [ -f /etc/synoinfo.conf ]; then
        echo synology; return
    fi
    # 通用 SysV / BusyBox init.d
    if [ -d /etc/init.d ]; then
        echo initd; return
    fi
    # 容器里常常什么都没有
    if [ -f /.dockerenv ]; then
        echo container; return
    fi
    echo unknown
}

have() { command -v "$1" >/dev/null 2>&1; }

# ---------- 卸载 ----------
do_uninstall() {
    need_root
    step 1 "停止并移除服务"
    init=$(detect_init)
    case "$init" in
        systemd)
            run systemctl stop  "$SERVICE_NAME" 2>/dev/null || true
            run systemctl disable "$SERVICE_NAME" 2>/dev/null || true
            run rm -f "/etc/systemd/system/$SERVICE_NAME.service"
            run systemctl daemon-reload 2>/dev/null || true
            ;;
        procd)
            run /etc/init.d/"$SERVICE_NAME" stop  2>/dev/null || true
            run /etc/init.d/"$SERVICE_NAME" disable 2>/dev/null || true
            run rm -f "/etc/init.d/$SERVICE_NAME"
            ;;
        *)
            run /etc/init.d/"$SERVICE_NAME" stop 2>/dev/null || true
            run rm -f "/etc/init.d/$SERVICE_NAME"
            ;;
    esac

    step 2 "删除程序文件"
    run rm -rf "$PREFIX"

    if [ -d "$CONF_DIR" ]; then
        say "    配置目录 $CONF_DIR 保留着（里面可能有你的 MAC 和令牌）"
        say "    确认不要了就自己删：rm -rf $CONF_DIR"
    fi
    say ""
    say "已卸载。"
}

# ---------- 主流程 ----------
main() {
    if [ "$UNINSTALL" = 1 ]; then
        do_uninstall
        return
    fi

    need_root

    arch=$(detect_arch)
    [ -n "$arch" ] || die "认不出这台机器的架构（uname -m = $(uname -m)）。
可以自己编译：cd relay && GOOS=linux GOARCH=... CGO_ENABLED=0 go build -o wolrelay ."

    init=$(detect_init)
    osname=$(uname -s)

    step 1 "看看这台机器"
    say "    系统    : $osname"
    say "    架构    : $arch ($(uname -m))"
    say "    启动方式: $init"
    say "    装到    : $PREFIX"
    say "    配置    : $CONF_DIR/config.json"

    if [ "$init" = "container" ]; then
        warn "看起来在容器里。容器没有 init，装不了自启服务。"
        warn "建议用 docker run 挂载 $CONF_DIR 并设 --restart=always，"
        warn "或者直接在宿主机上跑这个脚本。"
    elif [ "$init" = "unknown" ]; then
        warn "认不出启动方式，会退化成 SysV init.d。"
        warn "如果重启之后服务没起来，用 nohup 手动跑："
        warn "  nohup $PREFIX/wolrelay -config $CONF_DIR/config.json &"
    fi

    # ---- 2. 准备好目录 ----
    step 2 "准备目录"
    run mkdir -p "$PREFIX" "$CONF_DIR"

    # ---- 3. 拿到二进制 ----
    step 3 "获取中继程序"
    dl=""
    if have curl; then dl="curl -fsSL"
    elif have wget; then dl="wget -qO-"
    else die "没有 curl 也没有 wget，装一个再来（apt install curl / opkg install curl）" ; fi

    if [ "$VERSION" = "latest" ]; then
        base="https://github.com/$REPO/releases/latest/download"
    else
        base="https://github.com/$REPO/releases/download/$VERSION"
    fi
    url="$base/wolrelay-linux-$arch"

    say "    $url"
    tmp="$PREFIX/.wolrelay.new"
    if [ "$DRY" = 1 ]; then
        say "    (dry-run) 下载并校验"
    else
        if ! $dl "$url" > "$tmp" 2>/dev/null; then
            rm -f "$tmp"
            die "下载失败。可能的原因：
  · 这个版本没有 $arch 的产物（看 Releases 页面的文件名）
  · 机器连不上 GitHub（国内常见，可以换成 VERSION= 加镜像地址）
  · 仓库名或版本号写错了（当前：$REPO $VERSION）"
        fi
        # 下载回来的东西必须能跑。这个校验不是形式主义：
        # GitHub 在出错时会返回一个 HTML 页面而不是 404，curl -f 拦不住所有情况，
        # 存成二进制再 chmod +x 之后执行就报 "Exec format error"，很难定位。
        if [ ! -s "$tmp" ]; then
            rm -f "$tmp"; die "下载到的文件是空的"
        fi
        chmod 755 "$tmp"
        if ! "$tmp" -version >/dev/null 2>&1; then
            head -c 200 "$tmp" | grep -qi '<!doctype\|<html' \
                && { rm -f "$tmp"; die "下载到的是网页不是程序，多半是版本号不存在"; }
            rm -f "$tmp"
            die "下载到的程序跑不起来，可能是架构不对（当前识别为 $arch）"
        fi
        mv "$tmp" "$PREFIX/wolrelay"
        say "    大小：$(ls -lh "$PREFIX/wolrelay" | awk '{print $5}')"
    fi

    # ---- 4. 写配置 ----
    step 4 "写配置"
    if [ -f "$CONF_DIR/config.json" ]; then
        say "    已存在配置，保留不动（升级不会冲掉你的 MAC）"
    else
        # 这里不写占位 MAC。写个 AA:BB:CC:DD:EE:FF 进去，
        # 用户直接启动会"成功"但永远唤不醒，而日志里一片正常 ——
        # 这种假成功比报错难查得多。
        if [ "$DRY" = 1 ]; then
            say "    (dry-run) 生成默认配置"
        else
            cat > "$CONF_DIR/config.json" <<'JSON'
{
  "devices": [
    {"name": "pc", "mac": "在这里填你的网卡 MAC，例如 1A:2B:3C:4D:5E:6F"}
  ],
  "udp_port": 9,
  "listen": ":8080",
  "targets": ["255.255.255.255"],
  "token": "",
  "auto_broadcast": true,
  "confirm_timeout": 90,
  "confirm_ports": [445, 3389, 22, 80, 443]
}
JSON
            chmod 600 "$CONF_DIR/config.json"
            say "    已生成 $CONF_DIR/config.json"
            say ""
            say "    >>> 装完必须改这里，把 MAC 换成你要唤醒的那台电脑的 <<<"
        fi
    fi

    # ---- 5. 装自启 ----
    step 5 "装开机自启（$init）"
    case "$init" in
        systemd)  install_systemd  ;;
        procd)    install_procd    ;;
        synology) install_synology ;;
        initd)    install_initd    ;;
        *)        install_initd    ;;   # 认不出时按 SysV 试一把
    esac

    # ---- 6. 起服务 + 自检 ----
    step 6 "启动"
    if [ "$DRY" = 1 ]; then
        say "    (dry-run) 启动服务"
    else
        start_service "$init" || warn "启动失败，看看日志：$LOG_HINT"
        sleep 1
        if "$PREFIX/wolrelay" -config "$CONF_DIR/config.json" -doctor 2>/dev/null | head -30; then
            :
        else
            say "    自检跑不起来，手动试试："
            say "      $PREFIX/wolrelay -config $CONF_DIR/config.json -doctor"
        fi
    fi

    ip=$(host_ip)
    if [ -n "$ip" ]; then
        open_line="http://$ip:8080/"
    else
        open_line="http://<这台机器的IP>:8080/   （用 ip a 查一下它的地址）"
    fi

    cat <<EOF

=== 装好了 ===

下一步（必须做）：
  1. 改 MAC
       vi $CONF_DIR/config.json
     把 devices[0].mac 换成电脑网卡的 MAC（有线网卡！WiFi 网卡关机断电唤不醒）

  2. 重启服务让配置生效
       $(restart_hint "$init")

  3. 自检，看第一个 [X] 卡在哪
       $PREFIX/wolrelay -config $CONF_DIR/config.json -doctor

  4. 从手机浏览器试试（同一个 WiFi 下）
       $open_line

日志：
  $LOG_HINT

有问题看 docs/04-排错.md。
EOF
}

host_ip() {
    # 不追求准确，只是给用户一个能点的地址。
    # 拿不到就返回空，调用方会退化成文字提示 —— 绝不能拼出 "http://:8080/"
    # 这种看起来像 bug 的地址。
    if have ip; then
        ip route get 1.1.1.1 2>/dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p' | head -1
    elif have ifconfig; then
        ifconfig 2>/dev/null | sed -n 's/.*inet addr:\([0-9.]*\).*/\1/p' | grep -v '^127' | head -1
    fi
}

start_service() {
    case "$1" in
        systemd) systemctl enable --now "$SERVICE_NAME" >/dev/null 2>&1 ;;
        procd)   /etc/init.d/"$SERVICE_NAME" enable >/dev/null 2>&1
                 /etc/init.d/"$SERVICE_NAME" start  >/dev/null 2>&1 ;;
        synology) /etc/init.d/"$SERVICE_NAME" start >/dev/null 2>&1 ;;
        *)       /etc/init.d/"$SERVICE_NAME" start >/dev/null 2>&1 ;;
    esac
}

restart_hint() {
    case "$1" in
        systemd) echo "systemctl restart $SERVICE_NAME" ;;
        procd)   echo "/etc/init.d/$SERVICE_NAME restart" ;;
        *)       echo "/etc/init.d/$SERVICE_NAME restart" ;;
    esac
}

# ---------- 各 init 的安装 ----------

install_systemd() {
    LOG_HINT="journalctl -u $SERVICE_NAME -f"
    # DynamicUser 不用，因为中继要读 /proc/net/arp —— 那个文件普通用户可读，
    # 但用 DynamicUser 会让 Restart 之后 UID 变化，排查时不好认。
    # 明文 token 在配置文件里，靠 chmod 600 + 单独的运行用户保护。
    run tee "/etc/systemd/system/$SERVICE_NAME.service" >/dev/null <<EOF
[Unit]
Description=WOL Relay (远程唤醒中继)
Documentation=https://github.com/$REPO
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=$PREFIX/wolrelay -config $CONF_DIR/config.json
Restart=always
RestartSec=3
# 中继要往广播地址发包，且要读 ARP 表。这两件事普通用户都能做，
# 所以不提权、不加 AmbientCapabilities —— 少一分权限少一分风险。
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ReadWritePaths=$CONF_DIR

[Install]
WantedBy=multi-user.target
EOF
    run systemctl daemon-reload
    say "    已写 /etc/systemd/system/$SERVICE_NAME.service"
}

install_procd() {
    LOG_HINT="logread -e wolrelay"
    run tee "/etc/init.d/$SERVICE_NAME" >/dev/null <<EOF
#!/bin/sh /etc/rc.common
# WOL 中继 —— OpenWrt procd 服务
# 由 install.sh 生成，作者：神仙道 QQ 1974147300
START=95
STOP=10
USE_PROCD=1

start_service() {
    procd_open_instance
    procd_set_param command $PREFIX/wolrelay -config $CONF_DIR/config.json
    procd_set_param respawn 3600 5 5
    procd_set_param stdout 1
    procd_set_param stderr 1
    procd_close_instance
}
EOF
    run chmod 755 "/etc/init.d/$SERVICE_NAME"
    say "    已写 /etc/init.d/$SERVICE_NAME（procd）"
}

install_synology() {
    # 群晖的 /etc/init.d 会被 DSM 升级覆盖，但 upstart 的 conf 能活下来。
    # 这里两个都放，能用的那个先起作用。
    install_initd
    if [ -d /usr/local/etc/rc.d ]; then
        run cp "/etc/init.d/$SERVICE_NAME" "/usr/local/etc/rc.d/$SERVICE_NAME.sh"
        run chmod 755 "/usr/local/etc/rc.d/$SERVICE_NAME.sh"
        say "    已额外放到 /usr/local/etc/rc.d/（群晖开机执行这里）"
    fi
    LOG_HINT="/var/log/messages 里搜 wolrelay"
}

install_initd() {
    LOG_HINT="$PREFIX/wolrelay.log（或系统 syslog）"
    run tee "/etc/init.d/$SERVICE_NAME" >/dev/null <<EOF
#!/bin/sh
### BEGIN INIT INFO
# Provides:          $SERVICE_NAME
# Required-Start:    \$network \$remote_fs
# Required-Stop:     \$network
# Default-Start:     2 3 4 5
# Default-Stop:      0 1 6
# Short-Description: WOL 远程唤醒中继
### END INIT INFO
#
# 由 install.sh 生成，作者：神仙道 QQ 1974147300
# 同时兼容 SysV 和 BusyBox 的写法：不用 start-stop-daemon（不是所有系统都有），
# 用 pidfile 自己管。

PIDFILE=/var/run/$SERVICE_NAME.pid
BIN=$PREFIX/wolrelay
CONF=$CONF_DIR/config.json
LOG=$PREFIX/wolrelay.log

is_running() {
    [ -f "\$PIDFILE" ] && kill -0 "\$(cat \$PIDFILE)" 2>/dev/null
}

start() {
    if is_running; then echo "$SERVICE_NAME 已经在跑"; return 0; fi
    echo "启动 $SERVICE_NAME"
    # setsid 让子进程脱离当前会话，否则退出 shell 时会被 SIGHUP 带走
    if command -v setsid >/dev/null 2>&1; then
        setsid "\$BIN" -config "\$CONF" >>"\$LOG" 2>&1 &
    else
        "\$BIN" -config "\$CONF" >>"\$LOG" 2>&1 &
    fi
    echo \$! > "\$PIDFILE"
    sleep 1
    is_running || { echo "启动失败，看 \$LOG"; return 1; }
}

stop() {
    if ! is_running; then echo "$SERVICE_NAME 没在跑"; rm -f "\$PIDFILE"; return 0; fi
    echo "停止 $SERVICE_NAME"
    kill "\$(cat \$PIDFILE)" 2>/dev/null
    for i in 1 2 3 4 5; do
        is_running || break
        sleep 1
    done
    is_running && kill -9 "\$(cat \$PIDFILE)" 2>/dev/null
    rm -f "\$PIDFILE"
}

case "\$1" in
    start)   start ;;
    stop)    stop ;;
    restart) stop; start ;;
    status)  is_running && echo "running" || echo "stopped" ;;
    enable)  : ;;
    disable) : ;;
    *)       echo "用法：\$0 {start|stop|restart|status}"; exit 1 ;;
esac
EOF
    run chmod 755 "/etc/init.d/$SERVICE_NAME"
    say "    已写 /etc/init.d/$SERVICE_NAME（SysV / BusyBox）"

    # 有些系统要显式建 rc*.d 的软链接才会开机执行
    if have update-rc.d; then
        run update-rc.d "$SERVICE_NAME" defaults >/dev/null 2>&1 || true
    elif have chkconfig; then
        run chkconfig --add "$SERVICE_NAME" >/dev/null 2>&1 || true
    elif [ -d /etc/rc.d ]; then
        # OpenWrt 在 /etc/rc.d 下放 S95xxx 这样的链接
        run ln -sf "/etc/init.d/$SERVICE_NAME" "/etc/rc.d/S95$SERVICE_NAME" 2>/dev/null || true
    fi
}

# LOG_HINT 由各 install_* 设置，先给个默认值免得 set -u 报错
LOG_HINT="$PREFIX/wolrelay.log"

main
