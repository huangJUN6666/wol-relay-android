// wolrelay - 家庭网络内的 WOL 中继
//
// 跑在一台常开的安卓设备上，收到 HTTP 请求后向局域网广播魔术包。
// 交叉编译成静态二进制，无运行时依赖，由 Magisk 模块守护。
//
// 作者：神仙道  QQ 1974147300
//
// 配置优先级：命令行 flag > config.json > 默认值。
// MAC 没有默认值，必须由用户填写，程序不会替你猜。
package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"syscall"
	"time"
)

// ---- 配置 ----

type Config struct {
	MAC        string   `json:"mac"`
	UDPPort    int      `json:"udp_port"`
	Listen     string   `json:"listen"`
	Targets    []string `json:"targets"`
	Token      string   `json:"token"`
	Repeat     int      `json:"repeat"`
	IntervalMS int      `json:"interval_ms"`
	// AutoBroadcast 打开后会根据当前网卡地址自动算出本网段的广播地址，
	// 这样用户搬了家、换了路由器也不用改配置。
	AutoBroadcast *bool `json:"auto_broadcast"`
}

func defConfig() Config {
	t := true
	return Config{
		UDPPort:       9,
		Listen:        ":8080",
		Targets:       []string{"255.255.255.255"},
		Repeat:        3,
		IntervalMS:    120,
		AutoBroadcast: &t,
	}
}

func loadConfig(path string) (Config, error) {
	c := defConfig()
	if path == "" {
		return c, nil
	}
	data, err := os.ReadFile(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return c, nil
		}
		return c, err
	}
	if err := json.Unmarshal(data, &c); err != nil {
		return c, fmt.Errorf("config.json 格式错误: %w", err)
	}
	if c.UDPPort <= 0 {
		c.UDPPort = 9
	}
	if c.Listen == "" {
		c.Listen = ":8080"
	}
	if c.Repeat <= 0 {
		c.Repeat = 1
	}
	return c, nil
}

// ---- 魔术包 ----

func magicPacket(mac string) ([]byte, error) {
	hex := strings.NewReplacer(":", "", "-", "", " ", "", ".", "").Replace(mac)
	if len(hex) != 12 {
		return nil, fmt.Errorf("MAC 必须是 12 位十六进制，收到的是 %q", mac)
	}
	hw := make([]byte, 6)
	for i := 0; i < 6; i++ {
		var b int
		if _, err := fmt.Sscanf(hex[i*2:i*2+2], "%02x", &b); err != nil {
			return nil, fmt.Errorf("MAC 第 %d 段解析失败: %v", i+1, err)
		}
		hw[i] = byte(b)
	}
	pkt := make([]byte, 102)
	for i := 0; i < 6; i++ {
		pkt[i] = 0xFF
	}
	for i := 1; i <= 16; i++ {
		copy(pkt[i*6:], hw)
	}
	return pkt, nil
}

// ---- 广播目标 ----

// vpnLike 判断这块网卡是不是"看起来像隧道"。
//
// 为什么一定要排掉：
// 这个项目推荐用 Tailscale 做外网入口。Tailscale 起来之后会抢到默认路由，
// 如果还按"默认出口网卡"去算广播地址，算出来的是 100.x 隧道网段的广播，
// 魔术包会从隧道里发出去，家里的电脑一个字节都收不到。
// 这是最容易踩、又最难查的一类问题：日志显示"发送成功"，电脑就是不醒。
var vpnLike = []string{"tun", "tap", "tailscale", "wg", "ppp", "ipsec", "utun"}

func looksLikeVPN(name string) bool {
	n := strings.ToLower(name)
	for _, p := range vpnLike {
		if strings.HasPrefix(n, p) {
			return true
		}
	}
	return false
}

// allSubnetBroadcasts 列出本机所有物理网卡对应的定向广播地址。
//
// 不再只认"默认出口"那一块网卡，而是全都算一遍：
// 备机可能同时连着 WiFi 和 USB 网络共享，也可能插了 USB 网卡接有线。
// 全部发一遍成本极低（几个 UDP 包），但能省掉大量"为什么不醒"的排查。
func allSubnetBroadcasts() []string {
	ifaces, err := net.Interfaces()
	if err != nil {
		return nil
	}
	var out []string
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
			if ip4 == nil || ip4.IsLoopback() {
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
			s := bcast.String()
			if !seen[s] {
				seen[s] = true
				out = append(out, s)
				log.Printf("网卡 %s（%s）-> 广播地址 %s", ifi.Name, ipnet.String(), s)
			}
		}
	}
	return out
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
			var serr error
			if err := c.Control(func(fd uintptr) {
				setBroadcast(fd)
			}); err != nil {
				return err
			}
			return serr
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

// ---- HTTP ----

func authorized(c Config, r *http.Request) bool {
	if c.Token == "" {
		return true
	}
	if r.URL.Query().Get("token") == c.Token {
		return true
	}
	return r.Header.Get("X-Wol-Token") == c.Token
}

const pageHTML = `<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>WOL 中继</title><style>
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
background:#0f1115;color:#e8eaed;font-family:-apple-system,"PingFang SC",sans-serif}
.c{width:100%;max-width:420px;padding:32px 24px;text-align:center}
h1{font-size:20px;margin:0 0 6px}.s{font-size:13px;color:#8b93a1;margin-bottom:28px}
button{width:100%;padding:20px;font-size:19px;font-weight:600;color:#fff;border:0;
border-radius:16px;background:linear-gradient(135deg,#31c48d,#1e8f66);cursor:pointer}
button:active{transform:scale(.97)}button:disabled{opacity:.6}
#m{margin-top:20px;font-size:14px;min-height:22px;color:#8b93a1}
.ok{color:#31c48d}.bad{color:#f05252}
footer{margin-top:36px;font-size:11px;color:#4b515c}</style></head><body>
<div class="c"><h1>WOL 中继</h1><div class="s">这台设备会向家里局域网广播魔术包</div>
<button id="b">唤 醒 电 脑</button><div id="m"></div>
<footer>神仙道 · QQ 1974147300</footer></div>
<script>
var b=document.getElementById('b'),m=document.getElementById('m');
b.onclick=async function(){
 b.disabled=true;b.textContent='发送中…';m.textContent='';m.className='';
 try{var r=await fetch('/wake',{method:'POST'});
  var j=await r.json();
  if(j.ok){m.textContent='已广播，电脑大概 10~20 秒起来';m.className='ok';}
  else{m.textContent='没发出去：'+(j.error||'未知原因');m.className='bad';}
 }catch(e){m.textContent='连不上中继：'+e.message;m.className='bad';}
 b.disabled=false;b.textContent='再发一次';};
</script></body></html>`

func main() {
	var (
		cfgPath = flag.String("config", "", "配置文件路径，默认 ./config.json")
		mac     = flag.String("mac", "", "目标网卡 MAC，覆盖配置文件")
		port    = flag.Int("port", 0, "魔术包 UDP 端口")
		listen  = flag.String("listen", "", "HTTP 监听地址")
		targets = flag.String("targets", "", "额外广播目标，逗号分隔")
		token   = flag.String("token", "", "访问令牌，填了就要求带上")
		repeat  = flag.Int("repeat", 0, "重复发送轮数")
		autoBC  = flag.String("auto-broadcast", "", "true/false，是否自动算网段广播")
		showVer = flag.Bool("version", false, "打印版本后退出")
	)
	flag.Parse()

	if *showVer {
		fmt.Printf("wolrelay %s\n", version)
		return
	}

	c, err := loadConfig(*cfgPath)
	if err != nil {
		log.Fatalf("读配置失败: %v", err)
	}
	if *mac != "" {
		c.MAC = *mac
	}
	if *port > 0 {
		c.UDPPort = *port
	}
	if *listen != "" {
		c.Listen = *listen
	}
	if *targets != "" {
		c.Targets = append(c.Targets, strings.Split(*targets, ",")...)
	}
	if *token != "" {
		c.Token = *token
	}
	if *repeat > 0 {
		c.Repeat = *repeat
	}
	if *autoBC != "" {
		v := *autoBC == "true" || *autoBC == "1"
		c.AutoBroadcast = &v
	}
	if c.MAC == "" {
		log.Fatal("还没设置 MAC。请在 config.json 里填 mac 字段，或用 -mac 传入。")
	}

	pkt, err := magicPacket(c.MAC)
	if err != nil {
		log.Fatalf("MAC 无效: %v", err)
	}
	targets2 := resolveTargets(c)

	started := time.Now()
	var mu sync.Mutex

	mux := http.NewServeMux()

	mux.HandleFunc("/wake", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost && r.Method != http.MethodGet {
			http.Error(w, "方法不支持", http.StatusMethodNotAllowed)
			return
		}
		if !authorized(c, r) {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusForbidden)
			fmt.Fprint(w, `{"ok":false,"error":"token 不对"}`)
			return
		}
		all := []targetResult{}
		for i := 0; i < c.Repeat; i++ {
			all = append(all, blast(c, pkt, targets2)...)
			if i < c.Repeat-1 {
				time.Sleep(time.Duration(c.IntervalMS) * time.Millisecond)
			}
		}
		ok, okCount := false, 0
		for _, x := range all {
			if x.OK {
				ok = true
				okCount++
			}
		}
		mu.Lock()
		log.Printf("唤醒请求 %s -> %d/%d 个目标成功", r.RemoteAddr, okCount, len(all))
		mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		json.NewEncoder(w).Encode(map[string]any{
			"ok": ok, "sent": okCount, "total": len(all),
			"mac": c.MAC, "detail": all,
		})
	})

	mux.HandleFunc("/status", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"ok": true, "version": version, "mac": c.MAC,
			"udpPort": c.UDPPort, "targets": targets2,
			"uptime": time.Since(started).Round(time.Second).String(),
			"auth":   c.Token != "",
		})
	})

	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		fmt.Fprint(w, pageHTML)
	})

	log.Printf("wolrelay %s 启动于 %s，MAC=%s，UDP=%d，目标=%s",
		version, c.Listen, c.MAC, c.UDPPort, strings.Join(targets2, ","))

	srv := &http.Server{
		Addr:              c.Listen,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
	}
	if err := srv.ListenAndServe(); err != nil {
		log.Fatal(err)
	}
}

// 版本号要跟 module.prop 的 version 对上。
// App 会拿 /status 返回的 version 判断中继是不是太老，
// 两边不一致就会出现"明明刷的是新版，App 说过期了"。
const version = "2.1.1"
