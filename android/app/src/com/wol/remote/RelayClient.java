package com.wol.remote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * 中继 HTTP 客户端：/status、/devices、/wake、/confirm。
 *
 * 为什么要把 HTTP 单独拆出来：
 * 原来这部分和界面搅在一个 1100 行的 Activity 里，导致一个很实际的问题 ——
 * 中继返回 400 时（比如"配了 2 台设备，要用 name 指定唤醒哪台"），
 * 界面只显示 "HTTP 400"，把中继特意写好的那句人话丢了。
 * 多设备上线以后，400 会变成最常见的响应，那句话就是用户唯一能看到的线索。
 *
 * 作者：神仙道  QQ 1974147300
 */
final class RelayClient {

    /** Tailscale 打洞首包慢，超时给宽一些。 */
    static final int CONNECT_TIMEOUT = 9000;
    static final int READ_TIMEOUT = 11000;

    /** 探测 /status 用的超时。这个很快，不走隧道也不慢。 */
    private static final int PROBE_CONNECT_TIMEOUT = 2500;
    private static final int PROBE_READ_TIMEOUT = 2500;

    private RelayClient() {
    }

    /**
     * 把用户随手填的东西补成完整 URL：host、host:8080、http://host:8080 都认。
     *
     * 注意 host 里带冒号的情况要先排除（IPv6 写成 [::1]:8080），
     * 否则 "::1" 会被当成"有端口"。家用场景极少，但不能写出会崩的代码。
     */
    static String normalize(String base) {
        if (base == null) return "";
        String url = base.trim();
        if (url.length() == 0) return "";
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        int schemeEnd = url.indexOf("//") + 2;
        if (schemeEnd < 2) return url;
        int slash = url.indexOf('/', schemeEnd);

        // 去掉尾部斜杠，方便后面直接拼路径
        if (slash == url.length() - 1) {
            url = url.substring(0, url.length() - 1);
            slash = -1;
        }

        String head = slash < 0 ? url.substring(schemeEnd) : url.substring(schemeEnd, slash);
        String tail = slash < 0 ? "" : url.substring(slash);

        String hostPart = head;
        String portPart = "";
        int colon = head.lastIndexOf(':');
        // IPv6 字面量以 ] 结尾，那种情况 lastIndexOf(':') 落在括号里，不算端口
        if (colon > 0 && !head.endsWith("]") && head.indexOf(']') < colon) {
            hostPart = head.substring(0, colon);
            portPart = head.substring(colon + 1);
        }

        if (portPart.length() == 0) {
            portPart = String.valueOf(Lan.RELAY_PORT);
        }
        return url.substring(0, schemeEnd) + hostPart + ":" + portPart + tail;
    }

    /** 只留着 host 和端口，用于日志和"哪个中继成功了"的提示。 */
    static String hostOf(String addr) {
        try {
            URL u = new URL(normalize(addr));
            int p = u.getPort();
            if (p > 0 && p != Lan.RELAY_PORT) return u.getHost() + ":" + p;
            return u.getHost();
        } catch (Throwable e) {
            return addr == null ? "" : addr;
        }
    }

    // ============================================================
    //  /status
    // ============================================================

    /** 探一个中继的 /status。任何失败都体现在 Probe.reach / err 上，不抛异常。 */
    static Devices.Probe probe(String addr, String token) {
        Devices.Probe p = new Devices.Probe();
        p.addr = addr == null ? "" : addr;

        String url = normalize(addr);
        if (url.length() == 0) {
            p.err = "地址是空的";
            return p;
        }
        String full = url + "/status" + tokenQuery(token);

        HttpResult r = get(full, token, PROBE_CONNECT_TIMEOUT, PROBE_READ_TIMEOUT);
        if (r.error != null) {
            p.reach = false;
            p.err = r.error;
            return p;
        }
        if (r.code != 200) {
            p.reach = false;
            p.err = "HTTP " + r.code;
            return p;
        }
        try {
            JSONObject j = new JSONObject(r.body);
            p.reach = true;
            p.version = optStringOrNull(j, "version");
            p.mac = optStringOrNull(j, "mac");
            p.macFp = optStringOrNull(j, "macFp");
            p.macFull = j.optBoolean("macFull", false);
            p.auth = j.optBoolean("auth", false);
            p.deviceCount = j.optInt("devices", 0);
            Object t = j.opt("targets");
            if (t instanceof JSONArray) {
                p.targets = joinArray((JSONArray) t, "  ");
            } else if (t != null) {
                p.targets = String.valueOf(t);
            }
        } catch (Throwable e) {
            p.reach = false;
            p.err = "端口开着，但返回的不是 wolrelay 的状态页";
            Diag.w("RelayClient.probe", e);
        }
        return p;
    }

    // ============================================================
    //  /devices
    // ============================================================

    /**
     * 拉中继上的设备列表。
     *
     * 返回空表示失败或老版本中继不支持 —— 两种情况下 App 都退回"手动填 MAC"，
     * 所以调用方不用区分。失败原因写进 outErr[0]（如果给了的话）。
     */
    static List<Devices.RemoteDevice> devices(String addr, String token, String[] outErr) {
        List<Devices.RemoteDevice> out = new ArrayList<Devices.RemoteDevice>();
        String url = normalize(addr);
        if (url.length() == 0) {
            if (outErr != null && outErr.length > 0) outErr[0] = "地址是空的";
            return out;
        }
        HttpResult r = get(url + "/devices" + tokenQuery(token), token,
                PROBE_CONNECT_TIMEOUT, PROBE_READ_TIMEOUT);
        if (r.error != null) {
            if (outErr != null && outErr.length > 0) outErr[0] = r.error;
            return out;
        }
        if (r.code == 404) {
            // 老中继（v2.1.x）没有这个接口，是正常情况，不当错误
            if (outErr != null && outErr.length > 0) outErr[0] = "中继版本较老，没有设备列表接口";
            return out;
        }
        if (r.code != 200) {
            String msg = errorOf(r);
            if (outErr != null && outErr.length > 0) outErr[0] = msg;
            return out;
        }
        try {
            JSONObject j = new JSONObject(r.body);
            JSONArray arr = j.optJSONArray("devices");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Devices.RemoteDevice d = new Devices.RemoteDevice();
                d.name = o.optString("name", "");
                d.mac = o.optString("mac", "");
                d.macFp = o.optString("macFp", "");
                d.host = o.optString("host", "");
                out.add(d);
            }
        } catch (Throwable e) {
            Diag.w("RelayClient.devices", e);
            if (outErr != null && outErr.length > 0) outErr[0] = "设备列表解析失败";
        }
        return out;
    }

    // ============================================================
    //  /wake
    // ============================================================

    /**
     * 请求中继发魔术包。
     *
     * @param name   中继上的设备名；空表示"中继只有一台，不用指定"
     * @param wait   是否让中继等到确认结果再返回（App 不用，会给 curl 留）
     */
    static Devices.WakeResult wake(String addr, String token, String name, boolean wait) {
        Devices.WakeResult r = new Devices.WakeResult();
        String url = normalize(addr);
        if (url.length() == 0) {
            r.kind = Devices.WakeResult.IO;
            r.msg = "地址是空的";
            return r;
        }

        StringBuilder q = new StringBuilder();
        if (token != null && token.length() > 0) {
            q.append("token=").append(enc(token));
        }
        if (name != null && name.length() > 0) {
            if (q.length() > 0) q.append('&');
            q.append("name=").append(enc(name));
        }
        if (wait) {
            if (q.length() > 0) q.append('&');
            q.append("wait=1");
        }
        String full = url + "/wake" + (q.length() > 0 ? "?" + q : "");

        HttpResult hr = post(full, token);
        if (hr.error != null) {
            r.kind = Devices.WakeResult.IO;
            r.msg = hr.error;
            return r;
        }

        // 中继的错误原因优先于状态码。
        // v2.1.x 的 App 在这里只报 "HTTP 400"，把中继写的
        // "配了 2 台设备，要用 name 指定唤醒哪台：pc / nas" 丢掉了 ——
        // 多设备以后这是最常见的响应，那句话就是用户唯一的线索。
        String relayError = errorOf(hr);

        if (hr.code == 401 || hr.code == 403) {
            r.kind = Devices.WakeResult.TOKEN;
            r.msg = relayError.length() > 0 ? relayError : "令牌不对";
            return r;
        }
        if (hr.code != 200) {
            r.kind = Devices.WakeResult.HTTP;
            r.msg = relayError.length() > 0 ? relayError : ("HTTP " + hr.code);
            return r;
        }

        try {
            JSONObject j = new JSONObject(hr.body);
            boolean ok = j.optBoolean("ok", false);
            r.sent = j.optInt("sent", 0);
            r.total = j.optInt("total", 0);
            r.device = j.optString("device", "");
            r.confirmId = j.optString("confirm", "");
            r.confirmTimeout = j.optInt("confirmTimeout", 0);

            if (!ok) {
                String e = j.optString("error", "");
                r.kind = Devices.WakeResult.HTTP;
                r.msg = e.length() > 0 ? e : "中继返回 ok=false";
                return r;
            }
            // ok=true 但 sent=0：中继收到了，却一个包都没发出去。
            // 这是中继侧的问题（广播地址算错、网卡没连上），必须和"发出去了"分开
            r.kind = r.sent > 0 ? Devices.WakeResult.OK : Devices.WakeResult.ZERO_SENT;
            r.msg = r.kind == Devices.WakeResult.OK ? "OK" : "一个包都没发出去";
            return r;
        } catch (Throwable e) {
            // 老版本中继（v2.0.x）只回 200 不回 JSON，当成功处理
            Diag.w("RelayClient.wake", e);
            r.kind = Devices.WakeResult.OK;
            r.sent = 1;
            r.total = 1;
            r.msg = "OK";
            return r;
        }
    }

    // ============================================================
    //  /confirm
    // ============================================================

    /**
     * 查一次唤醒确认进度。
     * @param id   任务 ID；空则按 name 找最近一次
     */
    static Devices.ConfirmState confirm(String addr, String token, String id, String name) {
        Devices.ConfirmState st = new Devices.ConfirmState();
        String url = normalize(addr);
        if (url.length() == 0) {
            // 地址填空是配置问题，不会自己好，直接给结论。
            st.state = Devices.ConfirmState.TIMEOUT;
            st.note = "地址是空的";
            return st;
        }
        StringBuilder q = new StringBuilder();
        if (token != null && token.length() > 0) q.append("token=").append(enc(token));
        if (id != null && id.length() > 0) {
            if (q.length() > 0) q.append('&');
            q.append("id=").append(enc(id));
        } else if (name != null && name.length() > 0) {
            if (q.length() > 0) q.append('&');
            q.append("name=").append(enc(name));
        }
        if (q.length() > 0) q.append('&');
        q.append("_=").append(System.currentTimeMillis()); // 防代理缓存

        HttpResult hr = get(url + "/confirm?" + q, token,
                PROBE_CONNECT_TIMEOUT, PROBE_READ_TIMEOUT);
        if (hr.error != null || hr.code != 200) {
            // 这里是最容易被写错的一处：网络不通 ≠ 电脑没醒。
            // 中继可能压根没收到这个请求，也可能它已经回了而回程丢了。
            // 早先的写法一律当成 TIMEOUT，结果一次 WiFi 抖动就让界面
            // 盖棺定论"等了 90 秒，没等到它起来" —— 用户唯一在意的结论
            // 被一次毛刺推翻。改成分三路：
            //
            //   404  —— 中继说"没有这个确认任务"（进程重启、任务过期）。
            //            重问也不会出现，是结论。
            //   401/403 —— 令牌不对。重问还是不对，是结论。
            //   其它（连不上、超时、5xx）—— 这一轮没拿到信息，重试。
            if (hr.code == 404) {
                st.state = Devices.ConfirmState.TIMEOUT;
                String e = hr.error != null ? hr.error : "";
                st.note = e.length() > 0 ? e : "中继说没有这个确认任务（可能重启过）";
            } else if (hr.code == 401 || hr.code == 403) {
                st.state = Devices.ConfirmState.TIMEOUT;
                st.note = "令牌不对，中继不接受这次查询";
            } else {
                st.state = Devices.ConfirmState.RETRY;
                st.note = hr.error != null ? hr.error : ("HTTP " + hr.code);
            }
            return st;
        }
        try {
            JSONObject j = new JSONObject(hr.body);
            JSONObject o = j.optJSONObject("result");
            if (o == null) {
                st.state = Devices.ConfirmState.RETRY;
                st.note = "中继没返回确认结果";
                return st;
            }
            st.id = o.optString("id", id == null ? "" : id);
            st.device = o.optString("device", "");
            st.state = o.optString("state", Devices.ConfirmState.WAITING);
            st.elapsed = o.optDouble("elapsed", 0);
            st.onlineIn = o.optDouble("onlineIn", 0);
            st.arpIn = o.optDouble("arpIn", 0);
            st.ip = o.optString("ip", "");
            st.port = o.optInt("port", 0);
            st.attempts = o.optInt("attempts", 0);
            st.scanning = o.optBoolean("scanning", false);
            st.already = o.optBoolean("already", false);
            st.note = o.optString("note", "");
            // v2.2.0 起中继会给出机器可读的归因，优先用它。
            // 老中继没这个字段就是空串，detail() 里自然走"只有 note"那一路。
            st.cause = o.optString("cause", "");
        } catch (Throwable e) {
            Diag.w("RelayClient.confirm", e);
            // 解析失败通常是中继回了个 HTML 错误页（反代、登录拦截）。
            // 这也不是"电脑没醒"，一样归到重试。
            st.state = Devices.ConfirmState.RETRY;
            st.note = "确认结果解析失败";
        }
        return st;
    }

    // ============================================================
    //  HTTP 底层
    // ============================================================

    private static final class HttpResult {
        int code;
        String body = "";
        /** 网络层错误，非空表示请求根本没走通。 */
        String error;
    }

    private static HttpResult get(String url, String token, int connMs, int readMs) {
        HttpResult r = new HttpResult();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(connMs);
            c.setReadTimeout(readMs);
            c.setRequestProperty("Accept", "application/json");
            if (token != null && token.length() > 0) {
                c.setRequestProperty("X-Wol-Token", token);
            }
            r.code = c.getResponseCode();
            r.body = readBody(c, r.code);
            return r;
        } catch (java.net.SocketTimeoutException e) {
            r.error = "超时";
            return r;
        } catch (Throwable e) {
            r.error = netErrText(e);
            return r;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static HttpResult post(String url, String token) {
        HttpResult r = new HttpResult();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(READ_TIMEOUT);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setRequestProperty("Accept", "application/json");
            if (token != null && token.length() > 0) {
                c.setRequestProperty("X-Wol-Token", token);
            }
            OutputStream os = c.getOutputStream();
            os.write("wake=1".getBytes("UTF-8"));
            os.flush();
            os.close();

            r.code = c.getResponseCode();
            r.body = readBody(c, r.code);
            return r;
        } catch (java.net.SocketTimeoutException e) {
            r.error = "超时";
            return r;
        } catch (Throwable e) {
            r.error = netErrText(e);
            return r;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readBody(HttpURLConnection c, int code) {
        BufferedReader br = null;
        try {
            java.io.InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (is == null) return "";
            br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Throwable e) {
            Diag.w("RelayClient.readBody", e);
            return "";
        } finally {
            if (br != null) try {
                br.close();
            } catch (Throwable ignore) {
            }
        }
    }

    /**
     * 从错误响应里挖出中继自己写的原因。
     *
     * 中继的错误一律是 {"ok":false,"error":"……"}，body 里可能还套着
     * http.Error 的纯文本。两种都试，优先 JSON 里的 error 字段。
     */
    private static String errorOf(HttpResult r) {
        if (r == null || r.body == null) return "";
        String body = r.body.trim();
        if (body.length() == 0) return "";
        if (body.startsWith("{")) {
            try {
                JSONObject j = new JSONObject(body);
                String e = j.optString("error", "");
                if (e.length() > 0) return e;
            } catch (Throwable ignore) {
            }
        }
        // 纯文本错误，去掉 Go http.Error 自带的行尾换行
        String s = body.replaceAll("\\s+$", "");
        if (s.length() > 160) s = s.substring(0, 160) + "…";
        return s;
    }

    private static String netErrText(Throwable e) {
        String n = e == null ? "" : e.getClass().getSimpleName();
        if (n.contains("UnknownHost")) return "域名解析不出来";
        if (n.contains("ConnectException")) return "连不上（端口没开或被防火墙挡了）";
        if (n.contains("NoRouteToHost")) return "路由不到（多半不在同一个网）";
        if (n.contains("EHOSTUNREACH")) return "路由不到";
        if (n.contains("NetworkOnMainThread")) return "内部错误：在主线程发了网络请求";
        String m = e == null ? "" : e.getMessage();
        return n + (m == null || m.length() == 0 ? "" : " " + trim(m, 50));
    }

    private static String tokenQuery(String token) {
        if (token == null || token.length() == 0) return "";
        return "?token=" + enc(token);
    }

    private static String optStringOrNull(JSONObject j, String key) {
        if (!j.has(key) || j.isNull(key)) return null;
        String v = j.optString(key, null);
        return v == null || v.length() == 0 ? null : v;
    }

    private static String joinArray(JSONArray arr, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(arr.optString(i, ""));
        }
        return sb.toString();
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Throwable e) {
            return s;
        }
    }

    private static String trim(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
