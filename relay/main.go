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

// subnetBroadcast 取本机默认出口网卡的地址和掩码，算出定向广播地址。
// 比如 192.168.1.23/24 -> 192.168.1.255
// 这样换路由器、换网段都不用改配置。
func subnetBroadcast() (string, string, error) {
	conn, err := net.Dial("udp4", "8.8.8.8:80")
	if err != nil {
		return "", "", err
	}
	defer conn.Close()

	local := conn.LocalAddr().(*net.UDPAddr).IP
	ifaces, err := net.Interfaces()
	if err != nil {
		return "", "", err
	}
	for _, ifi := range ifaces {
		if ifi.Flags&net.FlagUp == 0 || ifi.Flags&net.FlagLoopback != 0 {
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
			if ip4 == nil || !ip4.Equal(local) {
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
			return bcast.String(), ipnet.String(), nil
		}
	}
	return "", "", fmt.Errorf("没找到与 %s 匹配的网卡", local)
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
		if b, cidr, err := subnetBroadcast(); err == nil {
			add(b)
			log.Printf("自动探测网段 %s，广播地址 %s", cidr, b)
		} else {
			log.Printf("自动探测网段失败（%v），只用配置里的目标", err)
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

func blast(c Config, pkt []byte, targets []string) []targetResult {
	res := make([]targetResult, 0, len(targets))
	for _, t := range targets {
		r := targetResult{Target: t}
		var lastErr error
		for attempt := 0; attempt < 3; attempt++ {
			conn, err := net.DialTimeout("udp4", net.JoinHostPort(t, fmt.Sprint(c.UDPPort)), 2*time.Second)
			if err == nil {
				_, err = conn.Write(pkt)
				conn.Close()
			}
			if err == nil {
				r.OK = true
				lastErr = nil
				break
			}
			lastErr = err
			time.Sleep(80 * time.Millisecond)
		}
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
button{width:100%%;padding:20px;font-size:19px;font-weight:600;color:#fff;border:0;
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

const version = "2.0.0"
