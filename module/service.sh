#!/system/bin/sh
# ============================================================
#  WOL Relay · Magisk / KernelSU 模块启动脚本
#  由 magiskd 以 root 身份执行，不经过 App 沙箱
#
#  作者：神仙道  QQ 1974147300
# ============================================================
#
#  这个脚本里有三处地方踩过坑，写在这里免得后来人重踩：
#
#  【坑 1】不要用 pkill -f "wolrelay"
#      本脚本自己的路径是 /data/adb/modules/wol-relay/service.sh，
#      pkill -f 会把自己的命令行也匹配上，直接把脚本杀掉，
#      后面所有代码一行都不会执行，表现是"模块装了但没反应"。
#      → 正确做法：写 pidfile，按 pid 精确 kill。
#
#  【坑 2】root shell 里看不到 tailscale 的网卡
#      Tailscale App 的 VPN 跑在自己的网络命名空间，
#      从 magiskd 的 shell 里执行 `ip addr show tailscale0` 永远是空的。
#      用它判断连接状态会一直判失败。
#      → 正确做法：查路由表有没有 100.x 的 tun 路由，或者直接 ping 对端。
#
#  【坑 3】拉起 Tailscale 后不要固定 sleep 几秒就判断
#      冷启动到真正能通要 40~70 秒（受机型和运营商影响）。
#      固定 sleep 8 秒必然误判成"没连上"。
#      → 正确做法：轮询，等到连上或超时为止。

MODDIR=${0%/*}
LOG=/data/local/tmp/wolrelay.log
PIDF=/data/local/tmp/wolrelay.pid

mkdir -p /data/local/tmp 2>/dev/null
rotate_log() {
    # 日志超过 1MB 就换一份，避免长期运行把 /data 撑满
    if [ -f "$LOG" ]; then
        SZ=$(wc -c < "$LOG" 2>/dev/null | tr -d ' ')
        if [ -n "$SZ" ] && [ "$SZ" -gt 1048576 ]; then
            mv -f "$LOG" "$LOG.old" 2>/dev/null
        fi
    fi
}

log() { echo "$(date '+%F %T') $*" >> "$LOG"; }

rotate_log
log "service.sh 启动，模块目录 $MODDIR"

# ---------- 0. 配置定位 ----------
# 配置不放在模块目录里。
# Magisk 升级模块时会把整个 $MODDIR 换掉，放里面的 config.json 会一起丢，
# 用户改好的 MAC 就白填了。所以主配置放 /data/adb/wol-relay/，
# 模块目录里的那份只作为首次安装时的种子。
DATA=/data/adb/wol-relay
CONF="$DATA/config.json"
CONF_OLD="$MODDIR/config.json"

mkdir -p "$DATA" 2>/dev/null

# 从旧版本升级上来：模块目录里已经有 config.json，搬到新位置
if [ ! -f "$CONF" ] && [ -f "$CONF_OLD" ]; then
    cp -f "$CONF_OLD" "$CONF" 2>/dev/null
    log "已把配置从模块目录迁到 $CONF"
fi

# 全新安装：用示例配置起个头
if [ ! -f "$CONF" ] && [ -f "$MODDIR/config.example.json" ]; then
    cp -f "$MODDIR/config.example.json" "$CONF" 2>/dev/null
    log "首次运行，已从示例生成 $CONF"
fi

chmod 755 "$MODDIR/wolrelay" 2>/dev/null

# ---------- 1. 等系统启动完成 ----------
i=0
while [ $i -lt 60 ]; do
    [ "$(getprop sys.boot_completed)" = "1" ] && break
    sleep 2
    i=$((i + 1))
done
log "系统启动完成 (boot_completed=$(getprop sys.boot_completed))"

# ---------- 2. 等 Wi-Fi 就绪 ----------
# 有网才谈得上广播。这里不硬编码网段，只要能拿到默认路由就够了。
i=0
while [ $i -lt 60 ]; do
    if ip route 2>/dev/null | grep -qE "^default via"; then
        break
    fi
    sleep 2
    i=$((i + 1))
done
log "当前路由: $(ip route 2>/dev/null | tr '\n' ';')"

# ---------- 3. 清理上一轮的残留进程 ----------
if [ -f "$PIDF" ]; then
    OLD=$(cat "$PIDF" 2>/dev/null)
    if [ -n "$OLD" ]; then
        kill "$OLD" 2>/dev/null
        log "已结束旧进程 $OLD"
    fi
    sleep 1
fi

# ---------- 4. 确保 Tailscale 在线 ----------
# 只有需要"从外网访问中继"时才要这一步。
# 如果你的备用机只在家里 WiFi 下用，可以把 ENABLE_TAILSCALE 改成 0。
ENABLE_TAILSCALE=1

start_tailscale() {
    log "尝试拉起 Tailscale"
    am start -n com.tailscale.ipn/.MainActivity >/dev/null 2>&1
    sleep 5
    am startservice -n com.tailscale.ipn/.IPNService >/dev/null 2>&1
}

ts_ok() {
    # 判据一：路由表里出现 100.64.0.0/10 段的 tun 路由
    ip route show table all 2>/dev/null | grep -qE "100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\." && return 0
    # 判据二：本机已分配 tailscale 地址
    ip -4 addr 2>/dev/null | grep -qE "inet 100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\." && return 0
    return 1
}

wait_ts() {
    n=0
    while [ $n -lt "$1" ]; do
        ts_ok && return 0
        sleep 3
        n=$((n + 1))
    done
    ts_ok
}

if [ "$ENABLE_TAILSCALE" = "1" ]; then
    if ts_ok; then
        log "Tailscale 已在线"
    else
        start_tailscale
        if wait_ts 30; then
            log "Tailscale 已连上"
        else
            log "Tailscale 暂未连上，交给巡护循环继续处理"
        fi
    fi
else
    log "ENABLE_TAILSCALE=0，跳过 Tailscale"
fi

# ---------- 5. 中继进程守护 ----------
# 进程退出就 3 秒后重来，永不放弃。
(
while true; do
    log "启动 wolrelay"
    if [ -f "$CONF" ]; then
        "$MODDIR/wolrelay" -config "$CONF" >> "$LOG" 2>&1 &
    else
        log "缺少 $CONF，先用默认参数启动（MAC 需要在配置里填）"
        "$MODDIR/wolrelay" >> "$LOG" 2>&1 &
    fi
    CHILD=$!
    echo "$CHILD" > "$PIDF"
    wait "$CHILD"
    log "wolrelay 退出了（pid $CHILD），3 秒后重启"
    sleep 3
done
) &

# ---------- 6. Tailscale 巡护 ----------
# 每 2 分钟看一眼，掉线就连着重试。只在状态变化时写日志，
# 免得日志被刷成一片。
if [ "$ENABLE_TAILSCALE" = "1" ]; then
(
    LAST=ok
    while true; do
        sleep 120

        if ts_ok; then
            if [ "$LAST" != "ok" ]; then
                log "巡护：Tailscale 恢复在线"
                LAST=ok
            fi
            continue
        fi

        log "巡护：Tailscale 掉线，开始重连"
        LAST=down
        round=0
        ok_now=0
        while [ $round -lt 10 ]; do
            round=$((round + 1))
            start_tailscale
            if wait_ts 30; then
                log "巡护：第 $round 轮连上了"
                LAST=ok
                ok_now=1
                break
            fi
            log "巡护：第 $round 轮还是没连上"
        done
        if [ "$ok_now" = "0" ]; then
            log "巡护：连续 10 轮失败，5 分钟后再试"
            sleep 300
        fi
    done
) &
fi

log "service.sh 执行完毕，守护已拉起"
