package main

// 唤醒确认：广播出去之后，到底醒没醒。
//
// 为什么需要这一层：
// 所有 WOL 工具都停在"魔术包已发送"。用户看到的是一句"已广播，大概 10~20 秒起来"，
// 然后盯着黑屏幕自己猜。真正的信息是"23 秒后连上了 445"。这中间差的不是功能，
// 是用户唯一真正关心的那个答案。
//
// 判定顺序（从便宜到贵）：
//   1. 读本机 ARP 表找目标 MAC 当前的 IP —— 机器一上电网卡就开始响应，这是最早的信号
//   2. 拿这个 IP 去连 TCP 端口 —— 能连上才说明系统起来了，不是只有网卡上电
//   3. ARP 表里一直没出现 -> 扫本网段，但**扫到的结果必须回查 ARP 核验 MAC**
//
// 第 3 步的核验是必须的，不是保险：
// 家里网段上随便就有几台开着 445 的 Windows。扫到"某台机器的 445 通了"就报
// "已开机"，是把自己家的打印机、别人的笔记本当成目标机 —— 这种假阳性比超时更糟，
// 因为用户会相信它，然后发现电脑根本没开。
//
// 为什么不 ping：
//   ICMP 要 raw socket，非 root 跑不起来。这个中继现在要支持路由器和 NAS 上的
//   普通用户部署，用不了特权操作。TCP 连一下是普通权限就能做的事，
//   而且"445 能连上"比"能 ping 通"更接近"这台机器能用了"。

import (
	"bufio"
	"fmt"
	"net"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// 确认任务的状态。App 就靠这三个值决定显示什么。
const (
	stateWaiting = "waiting" // 还在等
	stateOnline  = "online"  // 通了
	stateTimeout = "timeout" // 到点没等到
)

// 超时的归因。客户端（网页 / App）要按这个字段决定"该查哪一头"，
// 而不是拿 note 的文案去猜。
//
// 为什么单独给字段：note 是给人看的中文，改一个措辞就会让客户端的
// 关键词匹配失准 —— 老的 App 里就出过这个错，"网卡 X 秒时上电了"
// 含"网卡"二字，被当成了"网卡没出现"，于是把电脑侧的问题
// 建议成去查网线。语义不该靠字符串传递。
const (
	causeNone    = ""         // 没超时 / 说不上来
	causeNoARP   = "no_arp"   // ARP 里从头到尾没见过这台设备的网卡 -> 网络侧
	causePortsUp = "arp_only" // 网卡上电了，但系统端口没起 -> 电脑侧
	causeNoMAC   = "no_mac"   // 只配了 host，没 MAC 可查
)

type confirmJob struct {
	ID       string
	Device   string
	MAC      string
	Host     string
	Ports    []int
	Started  time.Time
	Deadline time.Duration

	mu sync.Mutex
	// 下面这些字段都要持锁读，/confirm 和探测协程会同时碰
	State    string
	IP       string
	Port     int
	ARPSeen  time.Duration // 第一次在 ARP 表里看到它的时刻（-1 = 没看到）
	OnlineAt time.Duration // 确认能连上的时刻（-1 = 还没）
	Attempts int
	Scanning bool
	LastNote string
	// LastCause 是超时的机器可读归因，取值见 cause* 常量。
	// 和 LastNote 一起写：note 给眼睛看，cause 给代码判断。
	LastCause string
	Done      chan struct{}
}

// JobView 是给 JSON 用的只读视图。
type JobView struct {
	ID       string  `json:"id"`
	Device   string  `json:"device"`
	MAC      string  `json:"mac"`
	State    string  `json:"state"`
	Elapsed  float64 `json:"elapsed"`
	OnlineIn float64 `json:"onlineIn,omitempty"`
	ARPIn    float64 `json:"arpIn,omitempty"`
	IP       string  `json:"ip,omitempty"`
	Port     int     `json:"port,omitempty"`
	Attempts int     `json:"attempts"`
	Scanning bool    `json:"scanning,omitempty"`
	Note     string  `json:"note,omitempty"`
	// Cause 是超时归因的机器可读版本（no_arp / arp_only / no_mac）。
	// 客户端拿它决定"建议用户查网络还是查电脑"，不要解析 Note 的措辞。
	Cause string `json:"cause,omitempty"`
	// 本来就是开着的（3 秒内就通了），不该报"已开机 · 1 秒"
	Already bool `json:"already,omitempty"`
}

func (j *confirmJob) view() JobView {
	j.mu.Lock()
	defer j.mu.Unlock()
	v := JobView{
		ID: j.ID, Device: j.Device, MAC: maskMAC(j.MAC), State: j.State,
		Elapsed: round1(time.Since(j.Started).Seconds()),
		IP:      j.IP, Port: j.Port, Attempts: j.Attempts,
		Scanning: j.Scanning, Note: j.LastNote, Cause: j.LastCause,
	}
	if j.ARPSeen >= 0 {
		v.ARPIn = round1(j.ARPSeen.Seconds())
	}
	if j.OnlineAt >= 0 {
		v.OnlineIn = round1(j.OnlineAt.Seconds())
		v.Already = j.OnlineAt < 3*time.Second
	}
	return v
}

func round1(f float64) float64 {
	return float64(int(f*10+0.5)) / 10
}

// ---- 任务表 ----

type jobStore struct {
	mu   sync.Mutex
	jobs map[string]*confirmJob
	seq  int
}

var jobs = &jobStore{jobs: map[string]*confirmJob{}}

func (s *jobStore) new(c Config, d Device) *confirmJob {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.seq++
	id := fmt.Sprintf("%d-%d", time.Now().UnixNano()%1e9, s.seq)
	j := &confirmJob{
		ID:       id,
		Device:   d.Name,
		MAC:      normMAC(d.MAC),
		Host:     d.Host,
		Ports:    c.ports(d),
		Started:  time.Now(),
		Deadline: time.Duration(c.ConfirmTimeout) * time.Second,
		State:    stateWaiting,
		ARPSeen:  -1,
		OnlineAt: -1,
		Done:     make(chan struct{}),
	}
	s.jobs[id] = j
	s.gcLocked()
	return j
}

// gcLocked 清掉 10 分钟前的老任务。
// 不清的话，一个长期运行的中继每唤醒一次就留一条，几个月就是几千条。
func (s *jobStore) gcLocked() {
	cut := time.Now().Add(-10 * time.Minute)
	for id, j := range s.jobs {
		if j.Started.Before(cut) {
			delete(s.jobs, id)
		}
	}
}

func (s *jobStore) get(id string) *confirmJob {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.jobs[id]
}

// latestFor 找这台设备最近的一个任务，让 App 只给 name 就能问到进度。
func (s *jobStore) latestFor(name string) *confirmJob {
	s.mu.Lock()
	defer s.mu.Unlock()
	var best *confirmJob
	for _, j := range s.jobs {
		if strings.EqualFold(j.Device, name) {
			if best == nil || j.Started.After(best.Started) {
				best = j
			}
		}
	}
	return best
}

// ---- ARP 表 ----

type arpEntry struct {
	IP  string
	MAC string
}

// readARPTable 读本机的 ARP 缓存。
//
// 安卓和 OpenWrt 上这个文件都可读，不用 root。
// 读不到（某些容器环境、或 busybox 精简过）就让调用方走网段扫描那条路。
func readARPTable() []arpEntry {
	f, err := os.Open("/proc/net/arp")
	if err != nil {
		return nil
	}
	defer f.Close()
	var out []arpEntry
	sc := bufio.NewScanner(f)
	sc.Scan() // 表头
	for sc.Scan() {
		fields := strings.Fields(sc.Text())
		if len(fields) < 4 {
			continue
		}
		mac := normMAC(fields[3])
		if len(mac) != 12 {
			continue
		}
		out = append(out, arpEntry{IP: fields[0], MAC: mac})
	}
	return out
}

// arpLookup 在 ARP 表里找某个 MAC 当前的 IP。
//
// 表里可能有同一个 MAC 的多条记录（换过 IP、多网卡），全都返回，
// 由调用方逐个探 —— 挑错了就是白等一轮。
func arpLookup(mac string) []string {
	want := normMAC(mac)
	if want == "" {
		return nil
	}
	var out []string
	for _, e := range readARPTable() {
		if e.MAC == want {
			out = append(out, e.IP)
		}
	}
	return out
}

// macAtIP 反查：这个 IP 在 ARP 表里的 MAC 是不是目标那个。
//
// 这是扫网段之后唯一可信的核验手段。
// 刚连过的 IP 一定会进 ARP 表（TCP 握手就要走 ARP），所以连完再查是可靠的。
func macAtIP(ip, wantMac string) bool {
	want := normMAC(wantMac)
	if want == "" {
		return false
	}
	for _, e := range readARPTable() {
		if e.IP == ip {
			return e.MAC == want
		}
	}
	return false
}

func normMAC(s string) string {
	return strings.ToUpper(strings.NewReplacer(":", "", "-", "", " ", "", ".", "").Replace(s))
}

// ---- TCP 探测 ----

// tcpProbe 试连一个端口。
func tcpProbe(host string, port int, timeout time.Duration) bool {
	c, err := net.DialTimeout("tcp4", net.JoinHostPort(host, strconv.Itoa(port)), timeout)
	if err != nil {
		return false
	}
	c.Close()
	return true
}

// firstOpenPort 按顺序试端口，返回第一个通的。
//
// 顺序有意义：445（Windows 共享）/ 3389（远程桌面）说明系统起来了；
// 22 说明 Linux 起来了；80/443 只能说明某个服务在听，可信度低一些，
// 所以放在后面。
func firstOpenPort(host string, ports []int, timeout time.Duration) int {
	for _, p := range ports {
		if tcpProbe(host, p, timeout) {
			return p
		}
	}
	return 0
}

// ---- 网段扫描（兜底）----

// scanCandidates 扫本网段，返回"端口开着且 MAC 核验通过"的 IP。
//
// MAC 核验这一步不能省，理由见文件头。
// 落在一台不是目标的机器上就当没找到，绝不放宽条件 —— 宁可超时，
// 也不能报一个假的"已开机"。
//
// 这里用 done channel + 独立的 found 变量，而不是拿 found 本身当"已找到"的信号：
// 用 `case <-found:` 判断会让**任何**一个协程把通道里的值取走，
// 后面真正 wait 完去读的时候通道已经空了。实测并发 64 时 200 次只有 2 次
// 能返回结果 —— 兜底扫描等于全程失效，而且不会报错，只是安静地"没找到"。
func (j *confirmJob) scanCandidates(port int, timeout time.Duration) string {
	hosts := subnetHosts()
	if len(hosts) == 0 {
		return ""
	}
	const parallel = 64
	sem := make(chan struct{}, parallel)
	var wg sync.WaitGroup

	// 找到的目标放这里，用互斥变量而不是通道做"已找到"的信号，
	// 这样查询状态和传值两件事不会互相踩。
	var mu sync.Mutex
	found := ""

	isFound := func() bool {
		mu.Lock()
		defer mu.Unlock()
		return found != ""
	}

	for _, h := range hosts {
		wg.Add(1)
		sem <- struct{}{}
		go func(host string) {
			defer wg.Done()
			defer func() { <-sem }()
			if isFound() {
				return
			}
			if !tcpProbe(host, port, timeout) {
				return
			}
			// 通了，但必须先确认这是目标那台机器
			if !macAtIP(host, j.MAC) {
				return
			}
			mu.Lock()
			if found == "" {
				found = host
			}
			mu.Unlock()
		}(h)
	}
	wg.Wait()

	mu.Lock()
	defer mu.Unlock()
	return found
}

// subnetHosts 列出本机各网段里可以扫的地址。
//
// 只扫 /24（最多 254 个）。用户的网是 /16 的话全扫是六万多个，
// 手机和路由器都扛不住，而且家用网络几乎不会有 /16 里只醒一台的情况。
func subnetHosts() []string {
	var out []string
	for _, n := range enumerateNets() {
		ip4 := n.IP.To4()
		if ip4 == nil {
			continue
		}
		prefix := fmt.Sprintf("%d.%d.%d.", ip4[0], ip4[1], ip4[2])
		self := int(ip4[3])
		for i := 1; i <= 254; i++ {
			if i == self {
				continue
			}
			out = append(out, prefix+strconv.Itoa(i))
		}
	}
	sort.Strings(out)
	return out
}

// ---- 探测主循环 ----

// start 启动一个确认任务，立刻返回任务本身（调用方不等结果）。
func (s *jobStore) start(c Config, d Device) *confirmJob {
	j := s.new(c, d)
	go j.run(c)
	return j
}

func (j *confirmJob) run(c Config) {
	defer close(j.Done)

	j.setNote("正在确认")

	scanned := false
	tick := time.NewTicker(1500 * time.Millisecond)
	defer tick.Stop()

	for {
		elapsed := time.Since(j.Started)

		if ip, port := j.tryOnce(c, scanned); ip != "" {
			j.mu.Lock()
			j.State = stateOnline
			j.IP = ip
			j.Port = port
			j.OnlineAt = elapsed
			j.LastNote = ""
			j.Scanning = false
			j.mu.Unlock()
			return
		}

		if elapsed >= j.Deadline {
			j.finishTimeout()
			return
		}

		// ARP 表一直看不到、到第 10 秒才开始扫网段。
		// 为什么等 10 秒：机器上电到网卡响应 DHCP/ARP 通常 3~8 秒，
		// 一上来就扫是纯浪费，还会让同网段的设备察觉到一片异常连接。
		if !scanned && elapsed >= 10*time.Second {
			scanned = true
			j.mu.Lock()
			j.Scanning = true
			j.LastNote = "ARP 里没看到，改为扫描网段"
			j.mu.Unlock()
		}

		remain := j.Deadline - time.Since(j.Started)
		if remain <= 0 {
			break
		}
		select {
		case <-tick.C:
		case <-time.After(remain):
			// 到点了，回到循环顶部走超时分支
		}
	}
	j.finishTimeout()
}

// finishTimeout 给超时补上归因。
//
// 这是整个功能里信息密度最高的一句话：
//
//	ARP 里从头到尾没出现 -> 包没到这个网段，或者网卡根本没上电 -> 查网络/路由器
//	ARP 里看到了但端口没通 -> 网卡上电了但系统没起来 -> 查 BIOS/快速启动/硬盘
//
// 它把"没醒"这个模糊结论劈成两半，用户不用再挨个猜。
func (j *confirmJob) finishTimeout() {
	j.mu.Lock()
	defer j.mu.Unlock()
	if j.State == stateOnline {
		return
	}
	j.State = stateTimeout
	j.Scanning = false
	switch {
	case j.MAC == "":
		j.LastNote = "只配了 host，连不上它"
		j.LastCause = causeNoMAC
	case j.ARPSeen < 0:
		j.LastNote = "从头到尾没在 ARP 表里看到这台设备的网卡"
		j.LastCause = causeNoARP
	default:
		j.LastNote = fmt.Sprintf("网卡 %.1f 秒时上电了，但系统端口一直没起来",
			j.ARPSeen.Seconds())
		j.LastCause = causePortsUp
	}
}

// tryOnce 做一轮探测，通了返回 (ip, port)。
func (j *confirmJob) tryOnce(c Config, scanned bool) (string, int) {
	j.mu.Lock()
	j.Attempts++
	j.mu.Unlock()

	const timeout = 700 * time.Millisecond
	ports := j.Ports

	// --- 优先级 0：用户直接配了 host ---
	// 跨网段部署时 ARP 表根本看不见目标，这条是唯一可行路径。
	// 配了 host 就不需要用 MAC 核验 —— 地址是用户自己指定的，可信。
	if j.Host != "" {
		if p := firstOpenPort(j.Host, ports, timeout); p > 0 {
			j.mu.Lock()
			if j.ARPSeen < 0 {
				j.ARPSeen = time.Since(j.Started)
			}
			j.mu.Unlock()
			return j.Host, p
		}
	}

	// --- 优先级 1：ARP 表 ---
	if j.MAC != "" {
		ips := arpLookup(j.MAC)
		if len(ips) > 0 {
			j.mu.Lock()
			if j.ARPSeen < 0 {
				j.ARPSeen = time.Since(j.Started)
			}
			// 已经扫起来了就不用再提扫描，避免把用户注意力带偏
			if j.LastNote != "" && !j.Scanning {
				j.LastNote = "已看到网卡，等系统起来"
			}
			j.mu.Unlock()
		}
		for _, ip := range ips {
			if p := firstOpenPort(ip, ports, timeout); p > 0 {
				return ip, p
			}
		}
	}

	// --- 优先级 2：扫网段（含 MAC 核验）---
	// 只探第一个端口：真的到了这一步，能连上任意一个就足以让用户看到结果，
	// 剩下几个端口由上面的 ARP 分支在下一轮里补。
	if scanned && len(ports) > 0 {
		if ip := j.scanCandidates(ports[0], 400*time.Millisecond); ip != "" {
			if p := firstOpenPort(ip, ports, timeout); p > 0 {
				return ip, p
			}
		}
	}

	_ = c
	return "", 0
}

func (j *confirmJob) setNote(s string) {
	j.mu.Lock()
	j.LastNote = s
	j.mu.Unlock()
}
