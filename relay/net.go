package main

// 广播地址计算与发包。
//
// 这里有一个 v2.1.x 留下的隐患：广播目标是在启动时算一次、之后一直用同一份。
// 备用机换 WiFi（2.4G 换 5G、或者白天回家晚上在别处）、拔插网线之后，
// 算出来的广播地址就指向一个已经不存在的网段，日志照样写"发送成功"，
// 电脑永远不醒。现在改成每次发包前重算，代价是几十微秒。

import (
	"fmt"
	"log"
	"net"
	"strings"
	"sync"
	"syscall"
	"time"
)

// vpnLike 判断这块网卡是不是"看起来像隧道"。
//
// 为什么一定要排掉：
// 这个项目推荐用 Tailscale 做外网入口。Tailscale 起来之后会抢到默认路由，
// 如果还按"默认出口网卡"去算广播地址，算出来的是 100.x 隧道网段的广播，
// 魔术包会从隧道里发出去，家里的电脑一个字节都收不到。
// 这是最容易踩、又最难查的一类问题：日志显示"发送成功"，电脑就是不醒。
var vpnLike = []string{"tun", "tap", "tailscale", "wg", "ppp", "ipsec", "utun", "docker", "br-", "veth"}

func looksLikeVPN(name string) bool {
	n := strings.ToLower(name)
	for _, p := range vpnLike {
		if strings.HasPrefix(n, p) {
			return true
		}
	}
	return false
}

// localNets 返回本机所有物理网卡的 (网卡名, IP, 广播地址)。
// 唤醒确认也靠它把网卡和网段对起来。
type localNet struct {
	Iface     string
	IP        net.IP
	Mask      net.IP
	Prefix    int
	Broadcast string
}

func enumerateNets() []localNet {
	ifaces, err := net.Interfaces()
	if err != nil {
		return nil
	}
	var out []localNet
	seen := map[string]bool{}
	for _, ifi := range ifaces {
		if ifi.Flags&net.FlagUp == 0 || ifi.Flags&net.FlagLoopback != 0 {
			continue
		}
		if looksLikeVPN(ifi.Name) {
			continue
		}
		addrs, err := ifi.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			ipnet, ok := a.(*net.IPNet)
			if !ok {
				continue
			}
			ip4 := ipnet.IP.To4()
			if ip4 == nil || ip4.IsLoopback() || ip4.IsLinkLocalUnicast() {
				continue
			}
			// 隧道地址段（Tailscale 的 100.64/10）也要排掉：
			// 有些机型上隧道网卡名字看不出来，只能靠地址段判断。
			if ip4[0] == 100 && ip4[1] >= 64 && ip4[1] <= 127 {
				continue
			}
			mask := net.IP(ipnet.Mask).To4()
			if mask == nil {
				continue
			}
			bcast := make(net.IP, 4)
			for i := 0; i < 4; i++ {
				bcast[i] = ip4[i] | ^mask[i]
			}
			key := bcast.String()
			if seen[key] {
				continue
			}
			seen[key] = true
			ones, _ := ipnet.Mask.Size()
			out = append(out, localNet{
				Iface: ifi.Name, IP: ip4, Mask: mask, Prefix: ones,
				Broadcast: key,
			})
		}
	}
	return out
}

// allSubnetBroadcasts 列出本机所有物理网卡对应的定向广播地址。
//
// 不再只认"默认出口"那一块网卡，而是全都算一遍：
// 备机可能同时连着 WiFi 和 USB 网络共享，也可能插了 USB 网卡接有线。
// 全部发一遍成本极低（几个 UDP 包），但能省掉大量"为什么不醒"的排查。
//
// 这里走了 2 秒的缓存：发包路径用它算广播目标，而 /status 每秒被 App
// 轮询一次、每次都要这份数据。不加缓存等于每秒枚举一遍网卡，
// 在树莓派/路由器上是不必要的系统调用开销。
// 2 秒这个粒度是刻意的 —— 换 WiFi 的场景最多晚 2 秒跟上，
// 但绝不会出现"用了一个已经不存在的网段还报发送成功"。
func allSubnetBroadcasts() []string {
	if v, ok := broadcastCache.get(); ok {
		return v
	}
	nets := enumerateNets()
	out := make([]string, 0, len(nets))
	for _, n := range nets {
		out = append(out, n.Broadcast)
	}
	broadcastCache.put(out)
	return out
}

// broadcastCache 是一个极简的 TTL 缓存。零值可直接用。
type bcastCache struct {
	mu   sync.Mutex
	at   time.Time
	val  []string
	life time.Duration
}

var broadcastCache = &bcastCache{life: 2 * time.Second}

func (c *bcastCache) get() ([]string, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.val == nil || time.Since(c.at) > c.life {
		return nil, false
	}
	return c.val, true
}

func (c *bcastCache) put(v []string) {
	c.mu.Lock()
	c.val = v
	c.at = time.Now()
	c.mu.Unlock()
}

func resolveTargets(c Config) []string {
	seen := map[string]bool{}
	out := []string{}
	add := func(s string) {
		s = strings.TrimSpace(s)
		if s == "" || seen[s] {
			return
		}
		seen[s] = true
		out = append(out, s)
	}
	for _, t := range c.Targets {
		add(t)
	}
	if c.AutoBroadcast != nil && *c.AutoBroadcast {
		b := allSubnetBroadcasts()
		if len(b) == 0 {
			log.Printf("没探测到可用网卡，只用配置里的目标（多半是 WiFi 没连上）")
		}
		for _, x := range b {
			add(x)
		}
	}
	return out
}

// ---- 发包 ----

type targetResult struct {
	Target string `json:"target"`
	OK     bool   `json:"ok"`
	Error  string `json:"error,omitempty"`
}

// broadcastConn 建一个允许发广播的 UDP 连接。
//
// 之前直接用 net.DialTimeout 建连，在 Linux / Android 上发到 x.x.x.255
// 这类广播地址会被内核拒掉（EACCES），日志里全是失败但看不出原因。
// 这里通过 Dialer.Control 在 socket 建好、还没开始发之前把
// SO_BROADCAST 打开，255.255.255.255 和定向广播才能发出去。
func broadcastConn(target string, port int) (net.Conn, error) {
	d := &net.Dialer{
		Timeout: 2 * time.Second,
		Control: func(network, address string, c syscall.RawConn) error {
			if err := c.Control(func(fd uintptr) {
				setBroadcast(fd)
			}); err != nil {
				return err
			}
			// setBroadcast 故意不返回错误：有些平台/权限下 SO_BROADCAST
			// 会返回 ENOPROTOOPT，但 socket 本身可能已经允许广播，
			// 不该因为一个可选项失败就整条唤醒链路中断。
			return nil
		},
	}
	return d.Dial("udp4", net.JoinHostPort(target, fmt.Sprint(port)))
}

func blast(c Config, pkt []byte, targets []string) []targetResult {
	res := make([]targetResult, 0, len(targets))
	for _, t := range targets {
		r := targetResult{Target: t}
		var lastErr error
		// 一个目标只建一次连接，重试时复用。
		// 原来每轮都重新 Dial，广播场景下白白多两次握手。
		conn, err := broadcastConn(t, c.UDPPort)
		if err != nil {
			r.Error = err.Error()
			res = append(res, r)
			continue
		}
		for attempt := 0; attempt < 3; attempt++ {
			_, werr := conn.Write(pkt)
			if werr == nil {
				r.OK = true
				lastErr = nil
				break
			}
			lastErr = werr
			time.Sleep(80 * time.Millisecond)
		}
		conn.Close()
		if lastErr != nil {
			r.Error = lastErr.Error()
		}
		res = append(res, r)
	}
	return res
}

// sendMagic 按配置把魔术包打出去，返回每个目标的成败。
// targets 每次现算，理由见文件头。
var sendMu sync.Mutex

func sendMagic(c Config, pkt []byte) ([]targetResult, int) {
	targets := resolveTargets(c)
	all := []targetResult{}
	sendMu.Lock()
	defer sendMu.Unlock()
	for i := 0; i < c.Repeat; i++ {
		all = append(all, blast(c, pkt, targets)...)
		if i < c.Repeat-1 {
			time.Sleep(time.Duration(c.IntervalMS) * time.Millisecond)
		}
	}
	n := 0
	for _, x := range all {
		if x.OK {
			n++
		}
	}
	return all, n
}
