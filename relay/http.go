package main

// HTTP 接口。
//
//   GET  /               浏览器单页，点一下就能开
//   POST /wake           发魔术包；?name=pc 指定设备，?wait=1 等结果再返回
//   GET  /confirm?id=    查唤醒确认进度
//   GET  /devices        设备列表（给 App 用，不泄露完整 MAC）
//   GET  /status         自检信息
//
// 全部返回 JSON，只有 / 是 HTML。

import (
	"encoding/json"
	"fmt"
	"net/http"
	"time"
)

func authorized(c Config, r *http.Request) bool {
	if c.Token == "" {
		return true
	}
	if tokenEqual(r.URL.Query().Get("token"), c.Token) {
		return true
	}
	return tokenEqual(r.Header.Get("X-Wol-Token"), c.Token)
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(v)
}

func routes(c Config) *http.ServeMux {
	mux := http.NewServeMux()

	// ---- 发魔术包 ----
	mux.HandleFunc("/wake", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost && r.Method != http.MethodGet {
			http.Error(w, "方法不支持", http.StatusMethodNotAllowed)
			return
		}
		if !authorized(c, r) {
			writeJSON(w, http.StatusForbidden, map[string]any{"ok": false, "error": "token 不对"})
			return
		}

		name := r.URL.Query().Get("name")
		dev, err := c.Find(name)
		if err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		// Host-only 的设备（没配 MAC）没法发魔术包，但可以只做状态确认。
		if dev.MAC == "" {
			writeJSON(w, http.StatusBadRequest, map[string]any{
				"ok": false, "error": "设备 " + dev.Name + " 只配了 host，没有 MAC，发不了魔术包",
			})
			return
		}

		pkt, err := magicPacket(dev.MAC)
		if err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": err.Error()})
			return
		}

		all, okCount := sendMagic(c, pkt)
		sent := okCount > 0

		// 确认任务无条件启动 —— 就算一个包都没发出去，探一下"是不是本来就开着"
		// 也是有价值的信息：能立刻告诉用户"机器没关，问题不在唤醒"。
		job := jobs.start(c, dev)

		resp := map[string]any{
			"ok": sent, "sent": okCount, "total": len(all),
			"device": dev.Name, "mac": dev.MAC, "detail": all,
			"confirm":        job.ID,
			"confirmTimeout": c.ConfirmTimeout,
		}

		// ?wait=1 同步等到出结果再返回。
		// 默认不开：手机端要的是"立刻看到已发送 + 进度条在动"，
		// 让它卡 30 秒等 JSON 反而像死机。给 curl 用户留了这个开关。
		if v := r.URL.Query().Get("wait"); v == "1" || v == "true" {
			select {
			case <-job.Done:
			case <-time.After(time.Duration(c.ConfirmTimeout)*time.Second + 3*time.Second):
			}
			resp["result"] = job.view()
		}

		logLine("唤醒 %s（%s）-> %d/%d 个目标，确认任务 %s",
			dev.Name, maskMAC(dev.MAC), okCount, len(all), job.ID)
		writeJSON(w, http.StatusOK, resp)
	})

	// ---- 查确认进度 ----
	mux.HandleFunc("/confirm", func(w http.ResponseWriter, r *http.Request) {
		if !authorized(c, r) {
			writeJSON(w, http.StatusForbidden, map[string]any{"ok": false, "error": "token 不对"})
			return
		}
		var job *confirmJob
		if id := r.URL.Query().Get("id"); id != "" {
			job = jobs.get(id)
		} else if name := r.URL.Query().Get("name"); name != "" {
			job = jobs.latestFor(name)
		}
		if job == nil {
			writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "error": "没有这个确认任务"})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "result": job.view()})
	})

	// ---- 设备列表 ----
	mux.HandleFunc("/devices", func(w http.ResponseWriter, r *http.Request) {
		full := c.Token != "" && authorized(c, r)
		list := make([]map[string]any, 0, len(c.Devices))
		for _, d := range c.Devices {
			item := map[string]any{
				"name": d.Name,
				// 默认打码。App 靠 macFp 比对，不需要明文。
				"mac":   maskMAC(d.MAC),
				"macFp": macFingerprint(d.MAC),
			}
			if full {
				item["mac"] = d.MAC
			}
			if d.Host != "" {
				item["host"] = d.Host
			}
			list = append(list, item)
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "devices": list, "macFull": full,
			"version": version,
		})
	})

	// ---- 自检 ----
	// /status 故意不要求鉴权 —— App 自检就是靠它扫网段找中继的，
	// 一上来就要 token 的话扫不出任何东西。
	// 但完整 MAC 是设备指纹，不能白给：默认只返回前 3 字节 + 指纹，
	// 带对了 token 才返回完整值。
	mux.HandleFunc("/status", func(w http.ResponseWriter, r *http.Request) {
		// 只有"确实配了 token 而且带对了"才给完整 MAC。
		// 没配 token 时 authorized() 恒为 true，那种情况下同样要打码 ——
		// 否则默认配置一装上，同一网段任何人都能读到完整 MAC。
		full := c.Token != "" && authorized(c, r)

		first := ""
		firstFp := ""
		macs := make([]map[string]any, 0, len(c.Devices))
		if len(c.Devices) > 0 {
			d := c.Devices[0]
			first = maskMAC(d.MAC)
			if full {
				first = d.MAC
			}
			firstFp = macFingerprint(d.MAC)
			for _, x := range c.Devices {
				m := maskMAC(x.MAC)
				if full {
					m = x.MAC
				}
				macs = append(macs, map[string]any{
					"name": x.Name, "mac": m, "macFp": macFingerprint(x.MAC),
				})
			}
		}

		writeJSON(w, http.StatusOK, map[string]any{
			"ok": true, "version": version,
			// 老字段，v2.1.x 的 App 只认 mac / macFp / macFull。留着是为了
			// 新中继能被旧 App 正常认出，不至于升级后 App 一片红。
			// 注意这里用 first / firstFp 而不是 c.Devices[0]：正常启动时
			// main 会拦住零设备，但 -doctor 和手动 curl 都可能走到这里，
			// 一次越界会让整个 handler panic，客户端只看到 500 没有原因。
			"mac": first, "macFp": firstFp, "macFull": full,
			"macs": macs, "devices": len(c.Devices),
			"udpPort": c.UDPPort, "targets": resolveTargets(c),
			"uptime": time.Since(startedAt).Round(time.Second).String(),
			"auth":   c.Token != "",
		})
	})

	// ---- 首页 ----
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		fmt.Fprint(w, pageHTML)
	})

	return mux
}

var startedAt = time.Now()

func logLine(format string, args ...any) {
	fmt.Printf(format+"\n", args...)
}
