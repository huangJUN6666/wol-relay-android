# 更新记录

## v2.2.0

这一版想清楚了一件事：**用户要的不是「包发出去了」，是「到底醒没醒」。**

所有 WOL 工具都停在「已广播，大概 10~20 秒起来」，因为发完包就没事可做了。
但发包从来不是难点，等它起来才是。所以这一版做的是那个闭环，
顺带把「必须有 root 过的安卓机」这道人为门槛拆了。

### 一、唤醒确认：把「发出去了」变成「开机了」

发完包之后，中继开始轮询，App 每秒问一次，界面从「正在确认…」变成
**「已开机 · 23 秒」**。三个层次的信号依次用上：

1. **ARP**（2~5 秒就有）—— `/proc/net/arp` 里出现那个 MAC，说明网卡已经上电。
   这是最早的信号，比系统起来早十几秒。
2. **TCP 探测**（端口 445 / 3389 / 22 / 80 / 443，任一通即算醒）——
   说明系统真的起来了，不只是网卡通了。
3. **扫网段**（前两步都没结果时的兜底）—— 逐个探本网段，但**必须回查 ARP 核验 MAC**。
   少了这一步，同网段任何一台开着 445 的 Windows 都会被误判成「目标机已醒」，
   假阳性比超时更糟：用户以为开好了，回家发现是关的。

**为什么是 TCP 不是 ping**：ICMP 要 raw socket，非 root 跑不了。
这一条直接决定了下面第三件事能不能做。

**失败时给出归因**，而不是干巴巴一句「超时」：

- ARP 里从头到尾没出现那个 MAC → 网络侧的问题（不同网段、网线没插、路由器没转发）
- ARP 出现了但端口一直连不上 → 电脑侧的问题（BIOS 设置、Windows 快速启动）
- 3 秒内就通了 → 它本来就开着，不会谎报「已开机 · 1 秒」

中继除了给人看的中文 `note`，还返回机器可读的 `cause`（`no_arp` / `arp_only` / `no_mac`）。
网页和 App 按字段决定「建议你查网络还是查电脑」，**不解析文案**——
中文措辞改一个字，关键词匹配就静默失效，而失效的表现是「建议指错方向」，
比不说更害人。

### 二、多设备：从「能开我的电脑」到「能开我家」

`config.json` 的 `devices` 变成数组，一台中继能管好几台机器：

```json
{"devices": [
  {"name": "pc",  "mac": "AA:BB:CC:DD:EE:FF"},
  {"name": "nas", "mac": "11:22:33:44:55:66", "host": "192.168.1.20"}
]}
```

`/wake?name=nas` 单独开某一台。App 里也是列表，可以「从中继读取设备列表」。
老的单个 `mac` 格式继续认，升级不会因为配置「过时」就跑不起来。

多台设备时不指定 `name` 会报错并列出可选名字，**不会默认全开**——
用户点一下把家里三台机器全开了不是他要的结果。

### 三、拆掉 root 门槛

原来只有 Magisk 模块，用的人必须有一台 root 过的安卓机。
但中继只是个 30KB 的纯静态 Go 程序，唯一的硬要求是「有一台常开的 Linux」。
**这是发布方式造成的门槛，不是技术造成的。**

现在 Release 里多了 7 个架构的裸二进制（amd64 / arm64 / armv7 / armv6 / 386 /
mipsle / mips），配一个安装脚本：

```bash
curl -fsSL https://raw.githubusercontent.com/huangJUN6666/wol-relay-android/main/scripts/install.sh | sudo sh
```

- 自动识别架构、自动识别启动方式（systemd / OpenWrt procd / 群晖 / SysV / BusyBox）
- 下载后先跑一次 `-version` 验证能执行，避免「下到 HTML 页面却 chmod +x」这种坑
- **不覆盖已有配置**（升级时你的 MAC 不会丢）
- 默认配置里**不写占位 MAC**——写个 `AA:BB:CC:DD:EE:FF` 进去会「启动成功但永远唤不醒」，
  这种假成功比报错难查得多
- `--dry-run` 可以只看它要做什么，`--uninstall` 干净卸载

受众从「有闲置 root 安卓机的人」变成「有任意一台常开 Linux 设备的人」。

### 四、修掉的 bug

| 位置 | 问题 | 后果 |
|---|---|---|
| `confirm.go` | `case <-found:` 用通道当「已找到」的信号 | 任何协程都会把值取走。**实测并发 64 时 200 次只有 2 次返回结果**，兜底扫描 99% 失效且不报错 |
| `main.go` | `-doctor` 里直接取 `c.Devices[0]` | 零设备时 panic。而这个命令的用处恰好是「配置有问题时诊断配置」 |
| `http.go` | `/status` 里无保护取 `c.Devices[0]` | 零设备 + 一次 curl 就 panic，客户端只看到 500 没有原因 |
| App `RelayClient.confirm` | 任何网络错误都当成 `TIMEOUT` | 一次 WiFi 抖动就宣布「没等到它起来」，而中继可能 8 秒前已确认。新增 `retry` 中间态，只有中继明确说超时才收尾 |
| App `MainActivity` | `runInBackground()` 定义了但从未调用，5 处仍是手写 `new Thread` | 后台线程抛一次异常，6 个按钮永久灰掉，注释里承诺的兜底是空的 |
| App `WolSender` | 第 1 轮发成功、第 2 轮抛异常 → 整体报「没发出去」 | 包其实已经出去了，电脑照样会醒。用户于是去查一个不存在的路由器问题 |
| `net.go` | 每次 `/status` 都枚举一遍所有网卡 | App 每秒轮询一次，在树莓派/路由器上是白费的系统调用。加 2 秒 TTL 缓存，换 WiFi 场景最多晚 2 秒跟上 |
| `confirm.go` + App + 网页 | **归因靠中文文案做关键词匹配** | 两条归因文案里都含「网卡」二字，`note.contains("网卡")` 把「网卡 3.2 秒时上电了，但系统端口一直没起来」（**电脑侧**）判成了网络侧，反过来建议用户去查网线。网页更糟：超时一律甩给「快速启动没关 / BIOS 关了网络唤醒」，ARP 从没出现时也不改口。现在中继给结构化 `cause`（`no_arp` / `arp_only` / `no_mac`），两端按字段分支，不再看文案 |

| CI `build.yml` | `download-artifact` 用了 `merge-multiple: true` | 每个 artifact 被平铺进 `dist/` 根目录，`native/` 这层被抹掉，`files:` 里的 `dist/native/...` 全部匹配不到。**而 `action-gh-release` 对没匹配到的通配符不报错，只是静默不上传** —— 首发那次四个 job 全绿、下载页只有 zip 和 apk，七个裸二进制一个都没进去。改成按 artifact 名分目录，并在上传前显式点数，少于 7 个直接红叉 |

### 五、顺手清的

- 删掉 `resolveTargetsCached`（定义了从未被调用）
- `config.example.json` 更新成多设备格式（原来还是单 mac，新用户照抄会困惑）
- `-doctor` 的结论更明确：零设备时说「先填 config.json」而不是崩
- Release job 在上传前先 `find dist` 打印完整产物树，并校验数量与关键文件；
  以前是「CI 绿了就当发好了」，产物少了要等用户来报才知道

### 六、发完之后补的：文档追上了，自检也补了两个洞

代码是多设备了，文档还停在单 mac —— 这是 v2.2.0 发出去之后才发现的。
`docs/03` 整篇写着 `wol-relay-v2.1.2.zip` 和旧配置格式，照着做的人刷进去的是上一版模块，
填的 MAC 也进不了 `devices` 数组。App 那边早就有「从中继读取设备」了，文档还在教人手抄 MAC。

| 位置 | 问题 | 后果 |
|---|---|---|
| `docs/03` | 全篇 v2.1.2 产物名 + 单 `mac` 配置格式 | 照文档操作刷进旧版模块；填的配置读不到设备 |
| `docs/03` App 段 | 教用户手抄 MAC、按旧三栏填 | 多设备时抄错一位就唤不醒，中继改名后两边对不上 |
| `module/customize.sh` | 装完提示「把 mac 改成…」 | 用户去找一个不存在的 `mac` 字段 |
| `module/doctor.sh` | **只校验第一个 MAC** | 加第二台时忘改占位值，自检照样全绿。现在逐条数占位值，报「3 台里还有 1 台是示例 MAC」 |
| `module/doctor.sh` | `mask_mac` 只认冒号 | 用户写 `AA-BB-CC-DD-EE-FF`（Windows `ipconfig` 就是这个格式）时**完全不打码**，截图求助等于把网卡地址公开。现在三种写法都认 |
| `scripts/check-version.sh` | 只查 README，不查 `docs/` | `docs/03` 落后一个大版本两个月没人发现。现在扫 `docs/*.md` 里的产物版本，旧了就红叉并给出行号 |

最后一条是这轮最值得留的：**检查写在脚本里，但它只覆盖了写脚本时想到的地方**。
漏了目录的检查比没有检查更危险 —— 它会给你一个绿色的错觉。

---

## v2.1.2

这一版没有改唤醒逻辑，修的是「发出去之后」和「别人来用」这两类问题。

**中继**

- **`/status` 不再无条件吐完整 MAC**。原来任何能访问到 8080 的人请求 `/status`
  都会拿到完整网卡 MAC，等于把家里电脑的设备指纹公开在同一网段上。
  现在默认只返回 `AA:BB:CC:XX:XX:XX`（前三段是厂商 OUI，不敏感），
  同时给一个 `macFp` 指纹；只有配置了 token 并且带对 token，才返回完整值。
  App 的自检改用指纹比对，所以「中继配的 MAC 跟 App 填的对不上」这个提示照旧能用。

**App**

- 跟着上面的改动走：新增 `macFp` / `macFull` 两个字段的解析，
  MAC 比对改成三级回退（完整 MAC → 指纹 → 前三段 OUI），
  避免因为中继打码而误报「MAC 不一致」。
  FNV-1a 的实现跟 `relay/main.go` 严格一致，两边算出来是同一个值。

**构建 / 发布**

- **APK 产物名改成纯 ASCII**：`远程开机.apk` → `WOL-Relay-App-v2.1.1.apk`。
  中文名上传到 GitHub Release 会被换成 `default.apk` 或被过滤成 `-v2.1.1.apk`，
  上一版只能传完再手工 PATCH 改名。现在从构建脚本产出就是 ASCII，一次到位。
  `publish-release.py` 里也加了拦截：非 ASCII 的文件名直接报错，不再上传。
- 加上 `.gitattributes` 强制 `*.sh` / `update-binary` 用 LF。
  这两个文件一旦被 Windows 存成 CRLF，刷进 Magisk 会报
  `not found` 或 `syntax error: unexpected end of file`，且极难定位。
- 新增 `scripts/check-version.sh`：一次检查 `module.prop`、`relay/main.go`、
  `AndroidManifest.xml`、README、CHANGELOG 五处版本号是否一致。
  v2.1.0 那次「中继 /status 报旧版本、App 误判要升级」就是漏改其中一处导致的。
- CI 去掉 `android-actions/setup-android@v3`。这步会去下载 cmdline-tools，
  超时或 429 时直接把 apk job 拖挂，连带 release job 被 skip
  （v2.1.1 的 tag 构建就是这么失败的：relay job 成功，apk job 在第 4 步失败，
  release 整个跳过，所以 Release 里的产物是本地手工传的）。
  ubuntu-latest runner 本来就带 SDK，直接用它。
- CI 写 `build.env` 改用逐行 echo。原来的 heredoc 会把 YAML 缩进原样写进文件，
  `build.env` 每行带前导空格，脚本 `source` 时报 `command not found`。

**文案**

- 删掉 Release 说明里「抓包能直连网卡驱动层发原始以太网帧」这句。
  代码里没有 `AF_PACKET`、没有 raw socket，就是标准 UDP 魔术包，
  写成那样会被人当场指出来。改成如实描述 root 到底带来什么：
  进程活得久、开机就跑、不受厂商省电策略干扰。

## v2.1.1

这一版全是在真机上跑出来才发现的坑，都属于「日志说一切正常、但就是唤不醒」那一类。

**中继**

- **修掉广播发不出去的问题**：Linux / Android 内核默认不允许往广播地址发包，
  原来的代码没开 `SO_BROADCAST`，`sendto` 直接返回 `permission denied`。
  现在在 socket 建好之后、开始发之前显式打开。这个 bug 会让**所有设备**都异常，
  之前能用的情况是恰好只往 `255.255.255.255` 且路由器宽松。
- **修掉隧道抢默认路由导致发错网段**：原来按「默认出口网卡」算广播地址。
  用 Tailscale 从外网连进来时，默认路由会落在 `100.x` 隧道上，
  算出来的广播地址是隧道网段的，魔术包从隧道出去，家里电脑一个包都收不到。
  现在改成遍历所有物理网卡、各自算定向广播全部发一遍，并排除
  `tun` / `tap` / `tailscale` / `wg` / `ppp` 这类网卡和 `100.64/10` 地址段。
- 一个目标只建一次 UDP 连接，重试时复用，少两次握手。
- 版本号从 `2.0.0` 修正为 `2.1.0`，和 `module.prop` 对齐。
  之前 /status 报的是旧版本，App 会误判中继过旧。
- 修掉首页 CSS 里 `width:100%%` 的笔误（写成了两个字面量百分号，按钮宽度失效）。

**模块**

- **守护循环加重试退避**：MAC 还没填时中继会立刻退出，原来固定 3 秒重启，
  等于每分钟 20 次、每小时 1200 次，日志几小时上万行还费电。
  现在连续快速失败就按 6/12/24/48/96/192/300 秒退避，跑稳 2 分钟以上自动复位。
- 日志轮转从「只在开机时跑一次」改成「每次重启进程前也看一眼」。

**App**

- 加 `network_security_config.xml` 并把 `versionCode` 从 3 改成 21000（跟版本号对齐）。
  部分国内 ROM 上即使 `usesCleartextTraffic="true"` 也会拦明文 HTTP，
  会报 `Cleartext HTTP traffic not permitted`，双保险。
- 之前 `targetSdk 33` 配 `versionCode 3`，应用商店和一些 ROM 的升级判断会乱。

**自检脚本**

- `doctor.sh` 加一条隧道网卡检测：如果默认路由落在 `tun/tap/tailscale/wg` 上，
  直接提示「老版本会把包发进隧道，表现为日志说成功、电脑不醒」。

## v2.1.0

**模块**

- **配置移出模块目录**：主配置改到 `/data/adb/wol-relay/config.json`。
  之前放在 `/data/adb/modules/wol-relay/` 里，升级模块时会被整个换掉，
  用户填好的 MAC 白填。服务启动时会自动把旧位置的文件迁过来。
- 模块 zip 里补上 `doctor.sh`（v2.0.0 漏了）。

**App**

- **一键自检**：扫本机网段找出在跑的中继，逐个验证连通性、MAC 一致性、鉴权状态，
  最后给一句结论告诉你该点哪个按钮。找不到时说明卡在哪一层。
- **自动发现备用机**：不知道备用机 IP 时，App 自己扫 `/24` 网段
  （32 线程并发，单地址 320ms 超时），找到后自动回填。
- **首次启动引导**：什么都没填时直接给三步操作顺序。
- **发包结果分层诊断**：中继不通 / 中继通了但零发包 / 令牌不对 /
  发包成功但电脑没醒 —— 四种情况给不同的排查方向。
- 中继地址输入更宽容：`host`、`host:8080`、`http://host:8080`、
  带不带 `/wake` 都认。
- 中继返回 JSON 时解析 `sent`/`total`，据此区分「中继广播失败」和「网络失败」。
- 报告里 MAC 打码（只留前六位），截图发群里不会泄露。

**构建**

- 修复 Windows 下 `javac` 报「找不到文件」—— MSYS 的 `/c/...` 要转成 `c:/...`。
- 修复 `apksigner` 只有 `.bat` 没有 `.exe` 时的打包失败，现在自动回退并设 `JAVA_HOME`。
- 打包不再依赖 `zip` 命令（Windows 默认没有），改用 python，并精确设文件权限。

## v2.0.0

开源化重构，为发布准备。

**源码脱敏**
- 删除所有硬编码的 MAC、公网 IP、Tailscale 地址、keystore 密码。
- 配置全部移出代码，改由 `config.json` 提供，命令行 flag 可覆盖。
- App 里所有输入框默认值清空，由使用者自己填。

**新增**
- `auto_broadcast`：按本机默认出口网卡的地址和掩码自动算定向广播地址，
  换路由器、换网段都不用改配置。
- `token` 鉴权，支持 query 参数和 `X-Wol-Token` 头两种传法。
- `/status` 接口返回版本、MAC、广播目标、鉴权状态，供 App 自检和 doctor 使用。
- `doctor.sh` 五层诊断脚本，输出自动打码，可直接贴 issue。
- `ReadHeaderTimeout` 防慢速攻击。

**文档**
- README + 六篇文档：电脑端设置、路由器设置、备用机部署、排错、
  没有 root 怎么办、适合哪些设备。
- 全部配 mermaid 流程图。

## v1.3

能跑通的版本，但所有配置写死在代码里，只在我自己的机器上能用。

**踩过的坑（都记在文档里了）**
- `pkill -f "wolrelay"` 会匹配到脚本自己的路径，把自己杀掉。
- `customize.sh` 里写 `MODPATH=${0%/*}` 会覆盖 Magisk 预设的正确路径，
  模块装完是空壳。
- root shell 看不到 Tailscale App 的 `tailscale0` 网卡（独立网络命名空间）。
- 拉起 Tailscale 后固定 `sleep 8` 判断，实测冷启动要 40~70 秒。
- 在自家网络里往自己公网 IP 发包，运营商 SNAT 回来，产生「假成功」。
- Windows 快速启动让关机变成休眠，网卡断电，WOL 必然失败。
