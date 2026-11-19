# 更新记录

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
