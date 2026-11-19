package main

// 配置读取。
//
// 配置格式从 v2.2.0 起变成"设备列表"，一个中继可以开好几台机器：
//
//	{
//	  "devices": [
//	    {"name": "书房电脑", "mac": "AA:BB:CC:DD:EE:FF"},
//	    {"name": "NAS",      "mac": "11:22:33:44:55:66", "host": "192.168.1.20"}
//	  ]
//	}
//
// 老的单 mac 格式继续认 —— 用户升级模块之后不应该因为配置文件"过时"就跑不起来。
// 读到 mac 字段而 devices 为空，就当成只有一台设备，名字叫 default。

import (
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"os"
	"regexp"
	"strings"
)

// Device 是一台可以唤醒的机器。
type Device struct {
	// Name 是给人和 App 看的名字，也是 /wake?name= 的取值。
	// 限制成 [A-Za-z0-9_-] 是有意的：这个名字会出现在 URL 查询串里，
	// 允许中文和空格就得处理百分号编码，一旦哪一端漏了就会"名字明明对却找不到设备"。
	// 中文名放在 App 里显示，不影响这里的标识名。
	Name string `json:"name"`
	MAC  string `json:"mac"`

	// Host 可选。知道这台机器固定 IP 或 DDNS 的话填上，
	// 唤醒后就能直接探它，不用等 ARP 表；跨网段（VLAN）的时候这也是唯一办法。
	Host string `json:"host,omitempty"`

	// Ports 可选，覆盖全局的 confirm_ports。
	Ports []int `json:"ports,omitempty"`
}

// Config 是中继的全部可调项。
type Config struct {
	Devices []Device `json:"devices"`

	// 旧字段，只用于兼容 v2.1.x 的 config.json。新配置不要写它。
	MAC string `json:"mac,omitempty"`

	UDPPort    int      `json:"udp_port"`
	Listen     string   `json:"listen"`
	Targets    []string `json:"targets"`
	Token      string   `json:"token"`
	Repeat     int      `json:"repeat"`
	IntervalMS int      `json:"interval_ms"`

	// AutoBroadcast 打开后会根据当前网卡地址自动算出本网段的广播地址，
	// 这样用户搬了家、换了路由器也不用改配置。
	AutoBroadcast *bool `json:"auto_broadcast"`

	// ConfirmTimeout 是"发完包之后等多久算没醒"的默认秒数。
	// 90 秒是照最慢的情况留的：开了 RAID 卡的机器自检就能磨掉一分钟。
	ConfirmTimeout int `json:"confirm_timeout"`

	// ConfirmPorts 是判断"这台机器起来了"的 TCP 端口，任一通即算醒。
	// 不 ICMP 是因为 ICMP 要 raw socket，非 root 跑不起来；
	// TCP 连一下谁都能做，而且"端口能连上"比"能 ping 通"更接近"能用了"。
	ConfirmPorts []int `json:"confirm_ports"`
}

func defConfig() Config {
	t := true
	return Config{
		UDPPort:        9,
		Listen:         ":8080",
		Targets:        []string{"255.255.255.255"},
		Repeat:         3,
		IntervalMS:     120,
		AutoBroadcast:  &t,
		ConfirmTimeout: 90,
		ConfirmPorts:   []int{445, 3389, 22, 80, 443},
	}
}

// deviceNameOK 判定设备名是否合法。
var deviceNameOK = regexp.MustCompile(`^[A-Za-z0-9_-]{1,32}$`)

// macNorm 去掉分隔符并转大写，方便比较。
func macNorm(s string) string {
	return strings.ToUpper(strings.NewReplacer(":", "", "-", "", " ", "", ".", "").Replace(s))
}

func macValid(s string) bool {
	n := macNorm(s)
	if len(n) != 12 {
		return false
	}
	for i := 0; i < 12; i++ {
		c := n[i]
		if !(c >= '0' && c <= '9' || c >= 'A' && c <= 'F') {
			return false
		}
	}
	return true
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
	c.sanitize()
	return c, nil
}

// sanitize 把用户手写的配置收敛成程序能安全使用的形态。
//
// 原则是"尽量救"而不是"一条不对就全挂"：
// 配置里有一台设备的 MAC 写错了，另外九台还是该能开。
// 实在一台都不剩，才由 main 里报错退出。
func (c *Config) sanitize() {
	if c.UDPPort <= 0 || c.UDPPort > 65535 {
		c.UDPPort = 9
	}
	if c.Listen == "" {
		c.Listen = ":8080"
	}
	if c.Repeat <= 0 {
		c.Repeat = 1
	}
	if c.Repeat > 10 {
		// 上限不是技术限制，是防手滑：repeat=100 会在一瞬间打出几百个广播包。
		c.Repeat = 10
	}
	if c.IntervalMS <= 0 {
		c.IntervalMS = 120
	}
	if c.ConfirmTimeout <= 0 {
		c.ConfirmTimeout = 90
	}
	if c.ConfirmTimeout > 300 {
		c.ConfirmTimeout = 300
	}
	c.ConfirmPorts = cleanPorts(c.ConfirmPorts)
	if len(c.ConfirmPorts) == 0 {
		c.ConfirmPorts = []int{445, 3389, 22, 80, 443}
	}

	// 旧格式迁移
	if len(c.Devices) == 0 && strings.TrimSpace(c.MAC) != "" {
		c.Devices = []Device{{Name: "default", MAC: strings.TrimSpace(c.MAC)}}
		log.Printf("配置用的是旧的单 mac 格式，已按一台设备加载。想开多台机器请看 config.example.json")
	}

	kept := make([]Device, 0, len(c.Devices))
	seen := map[string]bool{}
	for i, d := range c.Devices {
		d.Name = strings.TrimSpace(d.Name)
		d.MAC = strings.TrimSpace(d.MAC)
		d.Host = strings.TrimSpace(d.Host)

		if d.MAC == "" && d.Host == "" {
			log.Printf("第 %d 台设备既没有 mac 也没有 host，跳过", i+1)
			continue
		}
		if d.MAC != "" && !macValid(d.MAC) {
			log.Printf("设备 %q 的 MAC %q 不是 12 位十六进制，跳过", d.Name, d.MAC)
			continue
		}
		if !deviceNameOK.MatchString(d.Name) {
			old := d.Name
			d.Name = fmt.Sprintf("dev%d", i+1)
			log.Printf("设备名 %q 只能用字母数字和 _-，已改名为 %q", old, d.Name)
		}
		// 重名会让 /wake?name= 指代不明，加后缀区分
		if seen[strings.ToLower(d.Name)] {
			base := d.Name
			for n := 2; ; n++ {
				cand := fmt.Sprintf("%s-%d", base, n)
				if !seen[strings.ToLower(cand)] {
					d.Name = cand
					log.Printf("设备名 %q 重复，已改名为 %q", base, cand)
					break
				}
			}
		}
		seen[strings.ToLower(d.Name)] = true
		d.Ports = cleanPorts(d.Ports)
		kept = append(kept, d)
	}
	c.Devices = kept
}

func cleanPorts(in []int) []int {
	out := make([]int, 0, len(in))
	for _, p := range in {
		if p > 0 && p <= 65535 {
			out = append(out, p)
		}
	}
	return out
}

// Find 按名字找设备。name 为空时：只有一台就直接用它，多台则要求明确指定。
//
// 多台时为什么不默认"全部唤醒"：
// 用户点一下把家里三台机器全开了，这不是他要的结果，而且很费电。
// 宁可报错把可选名字列出来。
func (c Config) Find(name string) (Device, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		switch len(c.Devices) {
		case 0:
			return Device{}, errors.New("还没有配置任何设备")
		case 1:
			return c.Devices[0], nil
		default:
			names := make([]string, 0, len(c.Devices))
			for _, d := range c.Devices {
				names = append(names, d.Name)
			}
			return Device{}, fmt.Errorf("配了 %d 台设备，要用 name 指定唤醒哪台：%s",
				len(c.Devices), strings.Join(names, " / "))
		}
	}
	for _, d := range c.Devices {
		if strings.EqualFold(d.Name, name) {
			return d, nil
		}
	}
	// 也允许直接拿 MAC 当名字用，省得用户为了开一台机器去查配置里叫什么
	if macValid(name) {
		n := macNorm(name)
		for _, d := range c.Devices {
			if macNorm(d.MAC) == n {
				return d, nil
			}
		}
	}
	names := make([]string, 0, len(c.Devices))
	for _, d := range c.Devices {
		names = append(names, d.Name)
	}
	return Device{}, fmt.Errorf("没有叫 %q 的设备，可选：%s", name, strings.Join(names, " / "))
}

// ports 返回这台设备该探的端口：设备自己写了就用设备的，否则用全局的。
func (c Config) ports(d Device) []int {
	if len(d.Ports) > 0 {
		return d.Ports
	}
	return c.ConfirmPorts
}
