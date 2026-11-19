// wolrelay - 家庭网络内的 WOL 中继
//
// 跑在一台常开的设备上（安卓手机 / 路由器 / NAS / 树莓派 / 旧笔记本），
// 收到 HTTP 请求后向局域网广播魔术包，然后盯着目标到底醒没醒。
//
// 作者：神仙道  QQ 1974147300
//
// 配置优先级：命令行 flag > config.json > 默认值。
//
// 文件分工：
//
//	config.go   配置读取与设备表
//	net.go      广播地址计算、魔术包发送
//	confirm.go  唤醒确认（ARP -> TCP -> 扫网段）
//	http.go     HTTP 接口
//	page.go     浏览器单页
//	util.go     魔术包拼装、MAC 打码与指纹
package main

import (
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
	"time"
)

// 版本号要跟 module.prop 的 version 对上。
// App 会拿 /status 返回的 version 判断中继是不是太老，
// 两边不一致就会出现"明明刷的是新版，App 说过期了"。
const version = "2.2.1"

func main() {
	var (
		cfgPath = flag.String("config", "", "配置文件路径，默认 ./config.json")
		macFlag = flag.String("mac", "", "目标网卡 MAC（单设备模式）")
		nameFlg = flag.String("name", "", "设备名（配合 -mac 用）")
		port    = flag.Int("port", 0, "魔术包 UDP 端口")
		listen  = flag.String("listen", "", "HTTP 监听地址")
		targets = flag.String("targets", "", "额外广播目标，逗号分隔")
		token   = flag.String("token", "", "访问令牌，填了就要求带上")
		repeat  = flag.Int("repeat", 0, "重复发送轮数")
		autoBC  = flag.String("auto-broadcast", "", "true/false，是否自动算网段广播")
		timeout = flag.Int("confirm-timeout", 0, "唤醒确认最长等待秒数")
		showVer = flag.Bool("version", false, "打印版本后退出")
		doctor  = flag.Bool("doctor", false, "自检：打印环境与配置诊断后退出")
	)
	flag.Parse()

	if *showVer {
		fmt.Printf("wolrelay %s\n", version)
		return
	}

	log.SetFlags(log.LstdFlags)

	c, err := loadConfig(*cfgPath)
	if err != nil {
		log.Fatalf("读配置失败: %v", err)
	}

	// 命令行 -mac 覆盖配置文件，用于临时调试。
	// 给了就当成唯一设备，不然会出现"配置里三台 + 命令行一台"的混乱。
	if *macFlag != "" {
		n := *nameFlg
		if n == "" {
			n = "default"
		}
		c.Devices = []Device{{Name: n, MAC: *macFlag}}
		c.MAC = ""
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
	if *timeout > 0 {
		c.ConfirmTimeout = *timeout
	}
	if *autoBC != "" {
		v := *autoBC == "true" || *autoBC == "1"
		c.AutoBroadcast = &v
	}

	if *doctor {
		runDoctor(c)
		return
	}

	if len(c.Devices) == 0 {
		log.Fatal("还没有可唤醒的设备。请在 config.json 的 devices 里至少填一台，" +
			"格式见 config.example.json。")
	}

	// 启动横幅只打一次。之前每个请求都打一行"网卡 X -> 广播地址 Y"，
	// 日志几小时就被刷满了，真正出问题的时候翻不到有用信息。
	log.Printf("wolrelay %s 启动于 %s", version, c.Listen)
	log.Printf("UDP 端口 %d，重复 %d 轮，确认超时 %d 秒，端口 %v",
		c.UDPPort, c.Repeat, c.ConfirmTimeout, c.ConfirmPorts)
	for _, d := range c.Devices {
		log.Printf("设备 %-12s MAC=%s%s", d.Name, maskMAC(d.MAC), hostNote(d))
	}
	for _, t := range resolveTargets(c) {
		log.Printf("广播目标 %s", t)
	}
	if c.Token == "" {
		log.Printf("注意：没设 token，同一网段任何人都能调这个接口（只影响 /wake 和 /confirm）")
	}

	srv := &http.Server{
		Addr:              c.Listen,
		Handler:           routes(c),
		ReadHeaderTimeout: 5 * time.Second,
		// 不设 WriteTimeout：/wake?wait=1 要挂到确认超时，
		// 设了 WriteTimeout 会先把连接掐掉。
		IdleTimeout: 60 * time.Second,
	}
	if err := srv.ListenAndServe(); err != nil {
		log.Fatal(err)
	}
}

func hostNote(d Device) string {
	if d.Host == "" {
		return ""
	}
	return "  host=" + d.Host
}

// runDoctor 是中继侧的 -doctor：把"为什么唤不醒"能查的都查一遍。
//
// 放在二进制里而不是单独的脚本，是因为路由器上的用户不会去翻 shell 脚本，
// 但 `wolrelay -doctor` 他有可能会试一次。
func runDoctor(c Config) {
	fmt.Printf("wolrelay %s 自检\n\n", version)

	// 1. 配置
	fmt.Printf("[1] 配置\n")
	fmt.Printf("    监听      %s\n", c.Listen)
	fmt.Printf("    UDP 端口  %d\n", c.UDPPort)
	fmt.Printf("    设备数    %d\n", len(c.Devices))
	for _, d := range c.Devices {
		ok := "OK"
		if !macValid(d.MAC) && d.MAC != "" {
			ok = "MAC 格式不对"
		}
		if d.MAC == "" && d.Host == "" {
			ok = "mac 和 host 都空着"
		}
		fmt.Printf("    %-12s %s  %s\n", d.Name, maskMAC(d.MAC), ok)
	}

	// 2. 网卡
	fmt.Printf("\n[2] 网卡与广播目标\n")
	nets := enumerateNets()
	if len(nets) == 0 {
		fmt.Printf("    !! 没有可用的物理网卡。Wi-Fi 没连上，或者只有隧道网卡。\n")
	} else {
		for _, n := range nets {
			fmt.Printf("    %-10s %-15s -> %s\n", n.Iface, n.IP.String(), n.Broadcast)
		}
	}

	// 3. ARP 表
	fmt.Printf("\n[3] ARP 表\n")
	arp := readARPTable()
	if arp == nil {
		fmt.Printf("    读不到 /proc/net/arp（容器环境常见），唤醒确认会退回扫网段\n")
	} else {
		fmt.Printf("    共 %d 条\n", len(arp))
		for _, d := range c.Devices {
			ips := arpLookup(d.MAC)
			if len(ips) == 0 {
				fmt.Printf("    %-12s 现在不在 ARP 表里（关机状态是正常的）\n", d.Name)
			} else {
				fmt.Printf("    %-12s 现在就在线：%s\n", d.Name, strings.Join(ips, ", "))
			}
		}
	}

	// 4. 实发一次
	fmt.Printf("\n[4] 试发一个包\n")
	// 没有设备时不能直接取 c.Devices[0] —— 会 panic。
	// 而这个命令的用处恰好是"配置有问题时诊断配置"，
	// 在最该它工作的时候崩掉是最糟的。
	if len(c.Devices) == 0 {
		fmt.Printf("    跳过：没有可试发的设备\n")
	} else {
		d := c.Devices[0]
		pkt, err := magicPacket(d.MAC)
		if err != nil {
			fmt.Printf("    !! %v\n", err)
		} else {
			all, n := sendMagic(c, pkt)
			fmt.Printf("    %s: %d/%d 个目标成功\n", d.Name, n, len(all))
			for _, r := range all {
				st := "OK"
				if !r.OK {
					st = "失败 " + r.Error
				}
				fmt.Printf("      %-18s %s\n", r.Target, st)
			}
			if n == 0 {
				fmt.Printf("    !! 一个都没发出去。检查网卡是否连上局域网，\n"+
					"       以及是否有防火墙拦 UDP %d。\n", c.UDPPort)
			}
		}
	}

	fmt.Printf("\n[5] 结论\n")
	if n := len(c.Devices); n == 0 {
		fmt.Printf("    配置里没有设备，先填 config.json\n")
	} else if len(nets) == 0 {
		fmt.Printf("    网卡没就绪，先联网\n")
	} else {
		fmt.Printf("    环境看起来正常。如果还是唤不醒，问题在目标机器那头：\n")
		fmt.Printf("    · BIOS 里关闭 ErP/EuP，开启 Wake on LAN\n")
		fmt.Printf("    · Windows 执行 powercfg /h off 关掉快速启动\n")
		fmt.Printf("    · 必须是网线连接，Wi-Fi 网卡断电后唤不醒\n")
	}

	_ = os.Stdout
}
