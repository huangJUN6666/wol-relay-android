#!/system/bin/sh
# ============================================================
#  doctor.sh —— 环境自检
#
#  作者：神仙道  QQ 1974147300
#
#  在备用机上跑，用 su 执行：
#      su -c sh /data/adb/modules/wol-relay/doctor.sh
#
#  它会把"能不能唤得醒"拆成五层，一层一层告诉你卡在哪。
#  输出可以直接贴到 issue 里，里面的 MAC 和 IP 已经自动打码。
# ============================================================

MODDIR=${0%/*}
# 主配置在 /data/adb/wol-relay/，不在模块目录里 —— 升级模块不会丢配置。
# 但兼容老版本：模块目录里那份如果还在，也认。
CONF=/data/adb/wol-relay/config.json
[ -f "$CONF" ] || CONF="$MODDIR/config.json"
LOG=/data/local/tmp/wolrelay.log

pass() { echo "  [OK]   $*"; }
fail() { echo "  [X]    $*"; }
info() { echo "         $*"; }
head2() { echo ""; echo "== $* =="; }

echo "==============================================="
echo " WOL Relay 环境自检"
echo " 作者：神仙道  QQ 1974147300"
echo " $(date '+%F %T')"
echo "==============================================="

# MAC / IP 打码，方便直接贴出去
mask_mac() { echo "$1" | sed 's/\(..:..:..\):..:..:../\1:XX:XX:XX/'; }
mask_ip()  { echo "$1" | sed 's/\([0-9]*\.[0-9]*\)\.[0-9]*\.[0-9]*/\1.x.x/'; }

# ---------- 第一层：root 与模块 ----------
head2 "第 1 层 · root 与模块"

if [ "$(id -u)" = "0" ]; then
    pass "当前是 root 身份"
else
    fail "不是 root。请用 su -c 执行本脚本"
    info "普通 shell 读不到 /data/adb，后面全是假结果"
fi

if [ -d "$MODDIR" ]; then
    pass "模块目录存在：$MODDIR"
else
    fail "找不到模块目录 $MODDIR"
fi

for f in wolrelay service.sh module.prop; do
    if [ -f "$MODDIR/$f" ]; then
        pass "文件在：$f"
    else
        fail "缺文件：$f（重刷一次模块）"
    fi
done

if [ -f "$CONF" ]; then
    pass "配置文件在：config.json"
else
    fail "没有 config.json"
    info "复制一份：cp $MODDIR/config.example.json $CONF"
fi

# ---------- 第二层：配置内容 ----------
head2 "第 2 层 · 配置"

MAC=""
if [ -f "$CONF" ]; then
    MAC=$(grep -o '"mac"[^,]*' "$CONF" 2>/dev/null | sed 's/.*"\([^"]*\)"$/\1/')
fi

if [ -z "$MAC" ] || [ "$MAC" = "AA:BB:CC:DD:EE:FF" ]; then
    fail "MAC 还没填（现在还是示例值）"
    info "把 config.json 里的 mac 改成目标电脑有线网卡的物理地址"
else
    pass "MAC 已设置：$(mask_mac "$MAC")"
fi

TOKEN=$(grep -o '"token"[^,]*' "$CONF" 2>/dev/null | sed 's/.*: *"\([^"]*\)".*/\1/')
if [ -z "$TOKEN" ]; then
    fail "没设 token，任何知道地址的人都能触发唤醒"
    info "同网段自用问题不大，但接了公网就建议加一个"
else
    pass "已设访问令牌"
fi

# ---------- 第三层：网络 ----------
head2 "第 3 层 · 备用机网络"

WLAN=$(ip -4 addr show wlan0 2>/dev/null | grep -o 'inet [0-9.]*' | awk '{print $2}' | head -1)
if [ -n "$WLAN" ]; then
    pass "WiFi 已连接：$(mask_ip "$WLAN")"
else
    fail "wlan0 上没有 IPv4 地址"
    info "没连 WiFi 就没法往局域网广播，中继等于废的"
fi

GW=$(ip route 2>/dev/null | grep '^default' | head -1 | awk '{print $3}')
if [ -n "$GW" ]; then
    pass "默认网关：$(mask_ip "$GW")"
    if ping -c 1 -W 2 "$GW" >/dev/null 2>&1; then
        pass "网关 ping 得通"
    else
        fail "网关 ping 不通，网络本身有问题"
    fi
else
    fail "没有默认路由"
fi

# 算本网段的广播地址，这决定了魔术包能不能发到电脑那里
if [ -n "$WLAN" ]; then
    MASK=$(ip -4 addr show wlan0 2>/dev/null | grep -o 'inet [0-9./]*' | awk '{print $2}' | head -1)
    CIDR=${MASK#*/}
    if [ -n "$CIDR" ] && [ "$CIDR" -ge 1 ] && [ "$CIDR" -le 32 ]; then
        if command -v python3 >/dev/null 2>&1; then
            BC=$(python3 - "$MASK" <<'PY'
import sys, ipaddress
print(ipaddress.ip_interface(sys.argv[1]).network.broadcast_address)
PY
)
        else
            # 没有 python 时就按最常用的 /24 估一个
            BC=$(echo "$WLAN" | awk -F. '{print $1"."$2"."$3".255"}')
            info "（没装 python3，按 /24 估算）"
        fi
        pass "本网段广播地址：$(mask_ip "$BC")"
        info "中继的 auto_broadcast 打开时会自动用这个地址"
    fi
fi

# 隧道网卡会抢默认路由，广播包从隧道发出去电脑永远收不到。
# 这里单独立一条，因为它在日志里表现为"发送成功"，最难查。
TUNNEL_IF=$(ip -o link show 2>/dev/null | awk -F': ' '{print $2}' | grep -E '^(tun|tap|tailscale|wg|ppp|ipsec)' | head -3)
if [ -n "$TUNNEL_IF" ]; then
    info "检测到隧道网卡：$(echo "$TUNNEL_IF" | tr '\n' ' ')"
    info "中继已会自动跳过隧道网卡算广播地址，不用手动处理"
    # 看默认路由是不是落在隧道上
    DEF_IF=$(ip route 2>/dev/null | grep '^default' | head -1 | sed 's/.*dev \([^ ]*\).*/\1/')
    if echo "$DEF_IF" | grep -qE '^(tun|tap|tailscale|wg|ppp|ipsec)'; then
        info "当前默认路由走的是 $DEF_IF（隧道）。v2.1.1 起已不受影响，"
        info "老版本中继会把魔术包发进隧道，表现为【日志说发送成功、电脑不醒】"
    fi
fi

# ---------- 第四层：中继进程 ----------
head2 "第 4 层 · 中继进程"

RUN=$(ps -A 2>/dev/null | grep -c "[w]olrelay")
if [ "$RUN" -ge 1 ]; then
    pass "wolrelay 正在运行（$RUN 个进程）"
else
    fail "wolrelay 没在跑"
    info "看日志末尾：tail -20 $LOG"
fi

if [ -f "$LOG" ]; then
    pass "日志文件在（$(wc -c < "$LOG" | tr -d ' ') 字节）"
    echo ""
    info "最近 8 行："
    tail -8 "$LOG" 2>/dev/null | sed 's/^/         | /'
else
    fail "没有日志文件，模块可能根本没执行"
fi

# 自己打自己一枪，看 HTTP 通不通
head2 "第 5 层 · 中继端口"

if command -v curl >/dev/null 2>&1; then
    R=$(curl -s -m 4 http://127.0.0.1:8080/status 2>/dev/null)
    if echo "$R" | grep -q '"ok"'; then
        pass "本机 8080 有响应"
        info "$(echo "$R" | head -c 160)"
    else
        fail "本机 8080 没响应"
        info "中继进程可能在，但监听失败了，看日志"
    fi
else
    info "没有 curl，跳过端口测试"
fi

TS=$(ps -A 2>/dev/null | grep -c "[t]ailscale")
head2 "Tailscale（只在需要外网访问时才用）"
if [ "$TS" -ge 1 ]; then
    pass "Tailscale 进程在（$TS 个）"
    if ip route show table all 2>/dev/null | grep -qE "100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\."; then
        pass "有 100.x 的 tun 路由，隧道是通的"
    else
        fail "有进程但没有 100.x 路由，隧道没建立"
        info "打开 Tailscale App 看一眼是不是掉登录了"
    fi
else
    fail "Tailscale 没在跑"
    info "只在家里 WiFi 用就无所谓；要外网访问就必须有它"
fi

echo ""
echo "==============================================="
echo " 自检结束。上面出现 [X] 的那一层就是卡点。"
echo " 报告问题时把这段贴出来即可，MAC 和 IP 已打码。"
echo "==============================================="
