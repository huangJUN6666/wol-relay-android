package com.wol.remote;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 远程开机
 *
 * 三种发包方式：
 *   方式一 · 直连公网 UDP —— 电脑侧已经做好端口映射或 DMZ
 *   方式二 · 备用机中继 —— 家里一台常开设备代为广播，不依赖公网映射
 *   自动发现 · 扫描本网段 —— 不知道备用机 IP 时，App 自己去网里找
 *
 * 作者：神仙道  QQ 1974147300
 *
 * 这个文件不保留任何真实 IP、MAC、账号。首次打开是空的，
 * 由使用者自己填，填完存在 SharedPreferences 里。
 */
public class MainActivity extends Activity {

    private static final String PREF = "wol_pref";
    private static final String K_MAC = "mac";
    private static final String K_IP = "ip";
    private static final String K_PORT = "port";
    private static final String K_RELAY = "relay";
    private static final String K_TOKEN = "token";

    private static final String DEF_PORT = "9";
    private static final String DEF_MAC_HINT = "AA:BB:CC:DD:EE:FF";
    private static final int DEF_RELAY_PORT = 8080;

    // Tailscale 打洞和中继节点首次握手比较慢，超时给宽一些
    private static final int CONNECT_TIMEOUT = 9000;
    private static final int READ_TIMEOUT = 11000;

    // 局域网扫描：连接超时要短，否则扫一个 /24 要等半分钟
    private static final int SCAN_CONNECT_TIMEOUT = 320;
    private static final int SCAN_THREADS = 32;

    private SharedPreferences sp;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText etMac, etIp, etPort, etRelay, etToken;
    private TextView tvResult;
    private Button btnDirect, btnRelay, btnCheck, btnDiscover;

    private final int C_BG = Color.parseColor("#0F1115");
    private final int C_CARD = Color.parseColor("#1A1E26");
    private final int C_LINE = Color.parseColor("#2A2F3A");
    private final int C_TEXT = Color.parseColor("#E8EAED");
    private final int C_SUB = Color.parseColor("#8B93A1");
    private final int C_BLUE = Color.parseColor("#2F7CF6");
    private final int C_GREEN = Color.parseColor("#31C48D");
    private final int C_RED = Color.parseColor("#F05252");
    private final int C_AMBER = Color.parseColor("#E3A008");

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences(PREF, Context.MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(C_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(24), dp(22), dp(28));
        scroll.addView(root);

        // ---------- 标题 ----------
        TextView title = new TextView(this);
        title.setText("远程开机");
        title.setTextColor(C_TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("先点「一键自检」，它会把能走的路找出来");
        sub.setTextColor(C_SUB);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setPadding(0, dp(6), 0, dp(2));
        root.addView(sub);

        // ---------- 结果框 ----------
        tvResult = new TextView(this);
        tvResult.setTextColor(C_SUB);
        tvResult.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvResult.setLineSpacing(dp(3), 1f);
        tvResult.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable gdR = new GradientDrawable();
        gdR.setColor(C_CARD);
        gdR.setCornerRadius(dp(12));
        tvResult.setBackground(gdR);
        LinearLayout.LayoutParams lpR = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpR.topMargin = dp(14);
        tvResult.setVisibility(View.GONE);
        root.addView(tvResult, lpR);

        // ---------- 自检 ----------
        btnCheck = addButton(root, "一键自检", C_AMBER);
        btnCheck.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { doSelfCheck(); }
        });

        // ---------- 目标设备 ----------
        root.addView(sectionTitle("目标设备"));
        etMac = addField(root, "网卡 MAC", "", InputType.TYPE_CLASS_TEXT);
        root.addView(hint("填电脑有线网卡的物理地址。Windows 下用 ipconfig /all "
                + "看「物理地址」，形如 " + DEF_MAC_HINT + "。"));

        // ---------- 方式二 ----------
        root.addView(sectionTitle("方式二 · 备用机中继"));
        root.addView(hint("家里一台常开的设备跑中继服务，收到请求后在局域网内广播。"
                + "跟公网 IP、端口映射都没关系，出门在外用这条最省事。"));
        etRelay = addField(root, "中继地址（可填多个，空格分隔）", "", InputType.TYPE_CLASS_TEXT);
        etToken = addField(root, "访问令牌（中继没设就留空）", "", InputType.TYPE_CLASS_TEXT);

        btnDiscover = addButton(root, "自动发现备用机", C_CARD);
        btnDiscover.setTextColor(C_TEXT);
        btnDiscover.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { doDiscover(); }
        });

        btnRelay = addButton(root, "通过备用机唤醒", C_GREEN);
        btnRelay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { doRelay(); }
        });

        // ---------- 方式一 ----------
        root.addView(sectionTitle("方式一 · 直连公网 UDP"));
        root.addView(hint("需要光猫或路由器把 UDP 9 端口映射到电脑，或者把电脑设成 DMZ 主机。"
                + "公网 IP 变了就要改，适合有固定 IP 或 DDNS 的情况。"));
        etIp = addField(root, "公网 IP / DDNS 域名", "", InputType.TYPE_CLASS_TEXT);
        etPort = addField(root, "UDP 端口", DEF_PORT, InputType.TYPE_CLASS_NUMBER);
        btnDirect = addButton(root, "发送魔术包", C_BLUE);
        btnDirect.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { doDirect(); }
        });

        TextView foot = new TextView(this);
        foot.setText("神仙道 · QQ 1974147300");
        foot.setTextColor(Color.parseColor("#4B515C"));
        foot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        foot.setGravity(android.view.Gravity.CENTER);
        foot.setPadding(0, dp(34), 0, 0);
        root.addView(foot);

        scroll.setFillViewport(true);
        setContentView(scroll);
        loadPrefs();
        firstRunGuide();
    }

    /** 第一次打开、什么都没填的时候，直接告诉用户下一步点哪儿，别让他对着空输入框发愣。 */
    private void firstRunGuide() {
        if (etMac.getText().toString().trim().length() > 0) return;
        String s = "第一次用，按这个顺序来：\n\n"
                + "1  填上面那个网卡 MAC\n"
                + "2  电脑和备用机都开机，连同一个 WiFi\n"
                + "3  点「一键自检」\n\n"
                + "自检会自己扫一遍局域网，把备用机找出来。找不到也没关系，"
                + "结果里会说卡在哪一层。";
        tvResult.setVisibility(View.VISIBLE);
        tvResult.setText(s);
        tvResult.setTextColor(C_SUB);
    }

    // ============================================================
    //  一键自检
    // ============================================================

    private void doSelfCheck() {
        final String mac = etMac.getText().toString().trim();
        final String token = etToken.getText().toString().trim();
        final String ip = etIp.getText().toString().trim();
        final int port = parseInt(etPort.getText().toString().trim(), 9);

        if (mac.length() > 0 && !macOk(mac)) {
            show("MAC 格式不对，应该像 " + DEF_MAC_HINT, C_RED);
            return;
        }

        savePrefs();
        setBusy(true);
        show("正在自检…\n\n先扫本机所在的网段，再逐个验证配置的地址", C_SUB);

        new Thread(new Runnable() {
            public void run() {
                StringBuilder sb = new StringBuilder();
                List<Probe> found = new ArrayList<Probe>();

                // ---- 1. 本机网络 ----
                List<Subnet> nets = localSubnets();
                if (nets.isEmpty()) {
                    sb.append("本机没有拿到局域网地址。\n先连上 WiFi，或者确认手机没开飞行模式。\n");
                } else {
                    for (Subnet s : nets) {
                        sb.append("本机  ").append(s.addr).append("/").append(s.prefix);
                        if (s.ifName != null) sb.append("  ").append(s.ifName);
                        sb.append("\n");
                    }
                }

                // ---- 2. 逐个验证已配置的中继 ----
                String[] configured = relayList();
                sb.append("\n");
                if (configured.length == 0) {
                    sb.append("中继  没有配置\n");
                } else {
                    for (String a : configured) {
                        Probe p = probe(a, token);
                        found.add(p);
                        sb.append(fmtProbe(p, mac)).append("\n");
                    }
                }

                // ---- 3. 网段里扫一遍 ----
                List<String> scanned = new ArrayList<String>();
                if (!nets.isEmpty()) {
                    sb.append("扫描  ");
                    for (Subnet s : nets) {
                        List<Probe> hit = scanSubnet(s, token, configured);
                        for (Probe p : hit) {
                            if (!hasAddr(found, p.addr)) {
                                found.add(p);
                                scanned.add(p.addr);
                            }
                        }
                    }
                    if (scanned.isEmpty()) {
                        sb.append("本网段没找到在跑的中继\n");
                    } else {
                        sb.append("找到 ").append(scanned.size()).append(" 个\n");
                        for (String a : scanned) {
                            Probe p = findByAddr(found, a);
                            sb.append(fmtProbe(p, mac)).append("\n");
                        }
                    }
                }

                // ---- 4. 方式一 ----
                sb.append("\n");
                if (ip.length() == 0) {
                    sb.append("方式一  没有配置\n");
                } else {
                    boolean tcp = tcpAlive(ip, port, 1500);
                    sb.append("方式一  ").append(ip).append(":").append(port).append("\n");
                    // UDP 无连接，能做的只是解析域名、看路由通不通。
                    // 这里不假装能测通，只说明它没法在这里验证。
                    sb.append("  UDP 无法在这里验证，只能实际发一次看电脑醒不醒\n");
                    if (!tcp) sb.append("  域名解析正常，但端口不响应（UDP 本来就不响应，属于正常）\n");
                }

                // ---- 5. 结论 ----
                String verdict = verdict(found, mac, ip, scanned);
                final String text = verdict + "\n\n" + sb.toString().trim();
                final int color = verdictColor(found, mac, ip);

                ui.post(new Runnable() {
                    public void run() {
                        setBusy(false);
                        showLong(text, color);
                        // 扫出来的中继直接回填，省得用户自己抄 IP
                        if (!scanned.isEmpty() && relayList().length == 0) {
                            StringBuilder all = new StringBuilder();
                            for (String a : scanned) {
                                if (all.length() > 0) all.append(" ");
                                all.append(a);
                            }
                            etRelay.setText(all.toString());
                            savePrefs();
                            Toast.makeText(MainActivity.this,
                                    "已把找到的中继填进方式二", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }).start();
    }

    /** 结论只讲一件事：你现在该点哪个按钮；点不了的话，卡在哪。 */
    private String verdict(List<Probe> found, String appMac, String ip, List<String> scanned) {
        List<Probe> alive = new ArrayList<Probe>();
        for (Probe p : found) if (p.reach) alive.add(p);
        if (alive.isEmpty()) {
            if (ip.length() > 0) return "结论：两条路都没验证通\n先发一次方式一试试，UDP 只能实测。";
            return "结论：没找到中继\n检查备用机是不是开着机、连着同一个 WiFi，中继服务是不是在跑。";
        }
        if (appMac.length() == 0) {
            return "结论：中继通了，但你没填 MAC\n把电脑网卡的 MAC 填上，就能发了。";
        }
        for (Probe p : alive) {
            if (p.mac != null && p.mac.length() > 0 && !sameMac(p.mac, appMac)) {
                return "结论：中继里的 MAC 跟这台电脑对不上\n"
                        + "中继是 " + maskMac(p.mac) + "，App 填的是 " + maskMac(appMac) + "。\n"
                        + "改备用机上的 config.json，或者把 App 里的 MAC 改对。";
            }
        }
        for (Probe p : alive) {
            if (p.auth && etToken.getText().toString().trim().length() == 0) {
                return "结论：中继开了鉴权，但 App 里没填令牌\n"
                        + "令牌在备用机的 config.json 里，抄到「访问令牌」那一栏。";
            }
        }
        return "结论：可以发，用方式二\n下面点绿色那个按钮。";
    }

    private int verdictColor(List<Probe> found, String appMac, String ip) {
        for (Probe p : found) if (p.reach) {
            if (appMac.length() == 0) return C_AMBER;
            if (p.mac != null && p.mac.length() > 0 && !sameMac(p.mac, appMac)) return C_AMBER;
            if (p.auth && etToken.getText().toString().trim().length() == 0) return C_AMBER;
            return C_GREEN;
        }
        return ip.length() > 0 ? C_AMBER : C_RED;
    }

    private String fmtProbe(Probe p, String appMac) {
        StringBuilder s = new StringBuilder();
        s.append("  ").append(p.addr);
        if (!p.reach) {
            s.append("  连不上\n");
            if (p.err != null) s.append("    ").append(p.err).append("\n");
            return s.toString();
        }
        s.append("  通\n");
        if (p.version != null) s.append("    服务  wolrelay ").append(p.version).append("\n");
        if (p.mac != null && p.mac.length() > 0) {
            s.append("    MAC   ").append(maskMac(p.mac));
            if (appMac.length() > 0) {
                s.append(sameMac(p.mac, appMac) ? "  与 App 一致" : "  与 App 不一致");
            }
            s.append("\n");
        }
        s.append("    鉴权  ").append(p.auth ? "已开启" : "关闭").append("\n");
        if (p.targets != null && p.targets.length() > 0) {
            s.append("    广播  ").append(p.targets).append("\n");
        }
        return s.toString();
    }

    // ============================================================
    //  自动发现
    // ============================================================

    private void doDiscover() {
        setBusy(true);
        show("正在扫本机网段…\n\n每个地址都会试一次，大概十几秒", C_SUB);

        new Thread(new Runnable() {
            public void run() {
                List<Subnet> nets = localSubnets();
                if (nets.isEmpty()) {
                    ui.post(new Runnable() {
                        public void run() {
                            setBusy(false);
                            show("没拿到本机局域网地址\n先连上 WiFi 再试", C_RED);
                        }
                    });
                    return;
                }
                final List<String> hits = new ArrayList<String>();
                for (Subnet s : nets) {
                    for (Probe p : scanSubnet(s, etToken.getText().toString().trim(), null)) {
                        if (!hits.contains(p.addr)) hits.add(p.addr);
                    }
                }
                ui.post(new Runnable() {
                    public void run() {
                        setBusy(false);
                        if (hits.isEmpty()) {
                            show("本网段没找到中继\n\n"
                                    + "可能的原因：\n"
                                    + "· 备用机没连同一个 WiFi\n"
                                    + "· 中继服务没在跑（模块没刷上，或者 config.json 没填 MAC）\n"
                                    + "· 路由器开了 AP 隔离，设备之间互相看不见\n"
                                    + "· 中继端口不是 " + DEF_RELAY_PORT, C_RED);
                            return;
                        }
                        StringBuilder all = new StringBuilder();
                        for (String a : hits) {
                            if (all.length() > 0) all.append(" ");
                            all.append(a);
                        }
                        etRelay.setText(all.toString());
                        savePrefs();
                        show("找到 " + hits.size() + " 个中继，已填进方式二\n\n" + all, C_GREEN);
                    }
                });
            }
        }).start();
    }

    /**
     * 扫一个网段。
     * 只扫 /24 —— 家用网络没有比这更小的了，扫 /16 是六万多个地址，手机上得跑几分钟。
     */
    private List<Probe> scanSubnet(Subnet net, String token, String[] skip) {
        List<Probe> out = new ArrayList<Probe>();
        List<String> candidates = new ArrayList<String>();

        byte[] base = net.ipBytes();
        if (base == null) return out;
        int selfLast = base[3] & 0xFF;
        base[3] = 0;
        String prefix = (base[0] & 0xFF) + "." + (base[1] & 0xFF) + "."
                + (base[2] & 0xFF) + ".";
        for (int i = 1; i <= 254; i++) {
            if (i == selfLast) continue;
            candidates.add(prefix + i);
        }

        ExecutorService pool = Executors.newFixedThreadPool(SCAN_THREADS);
        List<Future<Probe>> futures = new ArrayList<Future<Probe>>();
        try {
            for (final String host : candidates) {
                futures.add(pool.submit(new Callable<Probe>() {
                    public Probe call() {
                        String addr = host + ":" + DEF_RELAY_PORT;
                        if (!tcpAlive(host, DEF_RELAY_PORT, SCAN_CONNECT_TIMEOUT)) {
                            return null;
                        }
                        Probe p = probe(addr, token);
                        // 端口开着但不是我们的服务，就丢掉
                        return p.version != null ? p : null;
                    }
                }));
            }
            for (Future<Probe> f : futures) {
                try {
                    Probe p = f.get(2, TimeUnit.SECONDS);
                    if (p != null && !containsAddr(out, p.addr)) out.add(p);
                } catch (Throwable ignore) {
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    // ============================================================
    //  探测单个中继
    // ============================================================

    private Probe probe(String addr, String token) {
        Probe p = new Probe();
        p.addr = addr;
        String body = httpGet(addr, "/status");
        if (body == null) {
            p.reach = false;
            p.err = "TCP 连不上，检查这台设备是否在线、" + DEF_RELAY_PORT + " 端口是否在监听";
            return p;
        }
        try {
            JSONObject j = new JSONObject(body);
            p.reach = true;
            p.version = j.optString("version", null);
            p.mac = j.optString("mac", null);
            p.auth = j.optBoolean("auth", false);
            Object t = j.opt("targets");
            if (t != null) {
                String s = String.valueOf(t).replace("[", "").replace("]", "").replace("\"", "");
                p.targets = s.replace(",", "  ");
            }
        } catch (Throwable e) {
            p.reach = false;
            p.err = "端口开着，但返回的不是 wolrelay 的状态页";
        }
        return p;
    }

    private String httpGet(String base, String path) {
        HttpURLConnection c = null;
        try {
            String url = normalize(base);
            if (url.endsWith("/wake")) url = url.substring(0, url.length() - 5);
            if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            url = url + path;
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
            if (c.getResponseCode() != 200) return null;
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } catch (Throwable e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ============================================================
    //  方式一 · 直连
    // ============================================================

    private void doDirect() {
        final String ip = etIp.getText().toString().trim();
        final int port = parseInt(etPort.getText().toString().trim(), 9);
        final String mac = etMac.getText().toString().trim();

        if (mac.length() == 0) { show("先把网卡 MAC 填上", C_RED); return; }
        if (!macOk(mac)) { show("MAC 格式不对，应该像 " + DEF_MAC_HINT, C_RED); return; }
        if (ip.length() == 0) { show("填一下公网 IP 或 DDNS 域名", C_RED); return; }

        savePrefs();
        setBusy(true);
        show("正在发送…", C_SUB);

        new Thread(new Runnable() {
            public void run() {
                StringBuilder sb = new StringBuilder();
                boolean any = false;
                String[] targets = new String[]{ip, "255.255.255.255"};
                for (String t : targets) {
                    if (t == null || t.length() == 0) continue;
                    boolean ok = sendUdp(t, port, mac);
                    sb.append(t).append(":").append(port).append(ok ? "  OK" : "  失败").append("\n");
                    if (ok) any = true;
                }
                final boolean ok = any;
                final String log = sb.toString();
                ui.post(new Runnable() {
                    public void run() {
                        setBusy(false);
                        if (ok) {
                            show("魔术包已发出\n\n" + log
                                    + "\n电脑大约 10~20 秒起来。没起来就按 docs/04 的 D 层查。", C_GREEN);
                        } else {
                            show("没发出去\n\n" + log
                                    + "\n通常是没联网，或者域名解析不出来。", C_RED);
                        }
                    }
                });
            }
        }).start();
    }

    // ============================================================
    //  方式二 · 中继
    // ============================================================

    private void doRelay() {
        final String[] relays = relayList();
        final String mac = etMac.getText().toString().trim();
        final String token = etToken.getText().toString().trim();

        if (relays.length == 0) { show("请填写中继地址，或者点「自动发现备用机」", C_RED); return; }
        if (mac.length() == 0) { show("先把网卡 MAC 填上", C_RED); return; }
        if (!macOk(mac)) { show("MAC 格式不对，应该像 " + DEF_MAC_HINT, C_RED); return; }

        savePrefs();
        setBusy(true);
        show("正在联系备用机…", C_SUB);

        new Thread(new Runnable() {
            public void run() {
                StringBuilder failLog = new StringBuilder();
                boolean sentAny = false;
                String okHost = null;
                int okSent = 0, okTotal = 0, tokenBad = 0;

                for (String url : relays) {
                    WakeResult r = httpWake(url, token);
                    if (r.kind == WakeResult.OK) {
                        // 中继收到了请求，但一个包都没发出去 —— 这是中继侧的问题
                        if (r.sent > 0) {
                            okHost = hostOf(url);
                            okSent = r.sent;
                            okTotal = r.total;
                            sentAny = true;
                            break;
                        }
                        okHost = hostOf(url);
                        okSent = 0;
                        okTotal = r.total;
                        continue;
                    }
                    if (r.kind == WakeResult.TOKEN) tokenBad++;
                    failLog.append(hostOf(url)).append("  ").append(r.shortMsg()).append("\n");
                }

                final boolean fOk = sentAny;
                final boolean fZero = okHost != null && !sentAny;
                final String fHost = okHost;
                final int fSent = okSent, fTotal = okTotal, fTokenBad = tokenBad;
                final String fFail = failLog.toString().trim();

                ui.post(new Runnable() {
                    public void run() {
                        setBusy(false);
                        if (fOk) {
                            show("备用机已代为发包\n\n" + fHost + "  发出 " + fSent + "/" + fTotal + " 个\n"
                                    + "电脑大约 10~20 秒起来。\n\n"
                                    + "没起来的话，问题在电脑那头，不在手机上：\n"
                                    + "· 快速启动没关  powercfg /h off\n"
                                    + "· BIOS 里 ErP 开着，关机把网卡断电了\n"
                                    + "· 网线没插，或者插在千兆以下的旧交换机上", C_GREEN);
                            return;
                        }
                        if (fZero) {
                            show("中继收到了，但一个包都没发出去\n\n"
                                    + fHost + "  发出 0/" + fTotal + "\n\n"
                                    + "中继自己广播不出去。上备用机跑一遍：\n"
                                    + "  sh /data/adb/modules/wol-relay/doctor.sh\n"
                                    + "看第 5 段「中继进程」的广播目标对不对。", C_RED);
                            return;
                        }
                        if (fTokenBad > 0) {
                            show("中继连上了，但令牌不对\n\n"
                                    + "令牌在备用机的 config.json 里，抄到「访问令牌」那一栏。"
                                    + (fFail.length() > 0 ? "\n\n" + fFail : ""), C_RED);
                            return;
                        }
                        boolean isTs = fFail.contains("100.");
                        if (isTs) wakeTailscaleApp();
                        show("备用机连不上\n\n" + fFail
                                + (isTs ? "\n已尝试唤起 Tailscale，等 10 秒再点一次。" : "")
                                + "\n\n先确认备用机开着机、连着家里的 WiFi。"
                                + "\n不确定就点「一键自检」，它会扫一遍网段。", C_RED);
                    }
                });
            }
        }).start();
    }

    /** 用普通 Intent 把 Tailscale 拉起来，让它自己恢复隧道。不需要 root。 */
    private void wakeTailscaleApp() {
        try {
            Intent it = getPackageManager().getLaunchIntentForPackage("com.tailscale.ipn");
            if (it != null) {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
            }
        } catch (Throwable ignore) {
        }
    }

    // ============================================================
    //  底层
    // ============================================================

    private boolean sendUdp(String host, int port, String mac) {
        try {
            byte[] pkt = magic(mac);
            InetAddress addr = InetAddress.getByName(host);
            DatagramSocket s = new DatagramSocket();
            s.setBroadcast(true);
            s.send(new DatagramPacket(pkt, pkt.length, addr, port));
            s.close();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 把用户随手填的东西补成完整 URL：host、host:8080、http://host:8080 都认。 */
    private static String normalize(String base) {
        String url = base;
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        // 没写端口就补默认端口
        int slash = url.indexOf('/', url.indexOf("//") + 2);
        String head = slash < 0 ? url : url.substring(0, slash);
        if (head.lastIndexOf(':') < head.indexOf("//") + 2) {
            url = head + ":" + DEF_RELAY_PORT + (slash < 0 ? "" : url.substring(slash));
        }
        return url;
    }

    private WakeResult httpWake(String base, String token) {
        WakeResult r = new WakeResult();
        HttpURLConnection c = null;
        try {
            String url = normalize(base);
            if (!url.contains("/wake")) {
                url = (url.endsWith("/") ? url + "wake" : url + "/wake");
            }
            if (token.length() > 0) {
                url = url + (url.contains("?") ? "&" : "?") + "token=" + urlEncode(token);
            }
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(READ_TIMEOUT);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            if (token.length() > 0) c.setRequestProperty("X-Wol-Token", token);
            c.getOutputStream().write("wake=1".getBytes("UTF-8"));

            int code = c.getResponseCode();
            StringBuilder sb = new StringBuilder();
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(
                        code >= 400 ? c.getErrorStream() : c.getInputStream(), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
            } catch (Throwable ignore) {
            }

            if (code == 403 || code == 401) {
                r.kind = WakeResult.TOKEN;
                r.msg = "令牌不对";
                return r;
            }
            if (code != 200) {
                r.kind = WakeResult.HTTP;
                r.msg = "HTTP " + code;
                return r;
            }
            try {
                JSONObject j = new JSONObject(sb.toString());
                r.kind = j.optBoolean("ok", false) ? WakeResult.OK : WakeResult.HTTP;
                r.sent = j.optInt("sent", 0);
                r.total = j.optInt("total", 0);
                r.msg = r.kind == WakeResult.OK ? "OK" : "中继返回 ok=false";
            } catch (Throwable e) {
                // 老版本中继不回 JSON，只要 200 就当成功
                r.kind = WakeResult.OK;
                r.sent = 1;
                r.total = 1;
                r.msg = "OK";
            }
            return r;
        } catch (java.net.SocketTimeoutException e) {
            r.kind = WakeResult.IO;
            r.msg = "超时";
            return r;
        } catch (Throwable e) {
            r.kind = WakeResult.IO;
            String n = e.getClass().getSimpleName();
            String m = e.getMessage();
            r.msg = n + (m == null ? "" : " " + trim(m, 40));
            return r;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Throwable e) {
            return s;
        }
    }

    /** 本机所有能用的局域网 IPv4 地址。有线、WiFi、热点都算。 */
    private List<Subnet> localSubnets() {
        List<Subnet> out = new ArrayList<Subnet>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address)) continue;
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    int prefix = ia.getNetworkPrefixLength();
                    if (prefix <= 0 || prefix > 32) continue;
                    out.add(new Subnet(a, prefix, ni.getDisplayName()));
                }
            }
        } catch (Throwable ignore) {
        }
        return out;
    }

    private static boolean tcpAlive(String host, int port, int timeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            try { s.close(); } catch (Throwable ignore) {}
        }
    }

    // ============================================================
    //  数据结构
    // ============================================================

    private static class Subnet {
        final InetAddress addr;
        final int prefix;
        final String ifName;

        Subnet(InetAddress a, int p, String n) { addr = a; prefix = p; ifName = n; }

        byte[] ipBytes() {
            byte[] b = addr.getAddress();
            return b != null && b.length == 4 ? b : null;
        }
    }

    private static class Probe {
        String addr;
        boolean reach;
        String version;
        String mac;
        boolean auth;
        String targets;
        String err;
    }

    private static class WakeResult {
        static final int OK = 0;
        static final int TOKEN = 1;
        static final int HTTP = 2;
        static final int IO = 3;

        int kind = IO;
        int sent, total;
        String msg = "";

        String shortMsg() {
            if (kind == IO) return "连不上";
            return msg == null || msg.length() == 0 ? "失败" : msg;
        }
    }

    private static boolean containsAddr(List<Probe> l, String a) {
        for (Probe p : l) if (p.addr != null && p.addr.equals(a)) return true;
        return false;
    }

    private static boolean hasAddr(List<Probe> l, String a) {
        return containsAddr(l, a);
    }

    private static Probe findByAddr(List<Probe> l, String a) {
        for (Probe p : l) if (p.addr != null && p.addr.equals(a)) return p;
        return null;
    }

    private String[] relayList() {
        String raw = etRelay.getText().toString().trim();
        if (raw.length() == 0) return new String[0];
        List<String> out = new ArrayList<String>();
        for (String t : raw.split("[\\s,;]+")) {
            String s = t.trim();
            if (s.length() > 0) out.add(s);
        }
        return out.toArray(new String[0]);
    }

    private static String hostOf(String url) {
        try {
            String s = normalize(url);
            URL u = new URL(s);
            int p = u.getPort();
            String h = u.getHost();
            if (p > 0 && p != DEF_RELAY_PORT) return h + ":" + p;
            return h;
        } catch (Throwable e) {
            return url;
        }
    }

    // ============================================================
    //  工具
    // ============================================================

    private static byte[] magic(String mac) {
        String h = mac.replace(":", "").replace("-", "").replace(" ", "");
        byte[] m = new byte[6];
        for (int i = 0; i < 6; i++) {
            m[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        byte[] p = new byte[102];
        for (int i = 0; i < 6; i++) p[i] = (byte) 0xFF;
        for (int i = 1; i <= 16; i++) System.arraycopy(m, 0, p, i * 6, 6);
        return p;
    }

    private static boolean macOk(String mac) {
        return mac.replace(":", "").replace("-", "").replace(" ", "")
                .matches("[0-9A-Fa-f]{12}");
    }

    private static String normMac(String s) {
        return s == null ? "" : s.replace(":", "").replace("-", "").replace(" ", "").toUpperCase();
    }

    private static boolean sameMac(String a, String b) {
        return normMac(a).equals(normMac(b));
    }

    /** 报告里不打印完整 MAC，前六位够认人了。 */
    private static String maskMac(String s) {
        String n = normMac(s);
        if (n.length() < 12) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) sb.append(":");
            sb.append(i < 6 ? n.substring(i, i + 2) : "XX");
        }
        return sb.toString();
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Throwable e) { return def; }
    }

    private static String trim(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private void setBusy(boolean busy) {
        btnDirect.setEnabled(!busy);
        btnRelay.setEnabled(!busy);
        btnCheck.setEnabled(!busy);
        btnDiscover.setEnabled(!busy);
    }

    private void show(final String msg, final int color) {
        tvResult.setVisibility(View.VISIBLE);
        tvResult.setText(msg);
        tvResult.setTextColor(color);
        String first = msg.split("\n")[0];
        Toast.makeText(this, first, color == C_RED ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
    }

    /** 自检报告很长，不弹 Toast，靠结果框自己撑开。 */
    private void showLong(String msg, int color) {
        tvResult.setVisibility(View.VISIBLE);
        tvResult.setText(msg);
        tvResult.setTextColor(color);
    }

    private void loadPrefs() {
        etMac.setText(sp.getString(K_MAC, ""));
        etIp.setText(sp.getString(K_IP, ""));
        etPort.setText(sp.getString(K_PORT, DEF_PORT));
        etRelay.setText(sp.getString(K_RELAY, ""));
        etToken.setText(sp.getString(K_TOKEN, ""));
    }

    private void savePrefs() {
        sp.edit()
                .putString(K_MAC, etMac.getText().toString().trim())
                .putString(K_IP, etIp.getText().toString().trim())
                .putString(K_PORT, etPort.getText().toString().trim())
                .putString(K_RELAY, etRelay.getText().toString().trim())
                .putString(K_TOKEN, etToken.getText().toString().trim())
                .apply();
    }

    // ---------------- UI ----------------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView sectionTitle(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(C_TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTypeface(null, 1);
        tv.setPadding(0, dp(26), 0, dp(8));
        return tv;
    }

    private TextView hint(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(C_SUB);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setLineSpacing(dp(3), 1f);
        tv.setPadding(0, 0, 0, dp(10));
        return tv;
    }

    private EditText addField(LinearLayout parent, String label, String def, int inputType) {
        TextView lb = new TextView(this);
        lb.setText(label);
        lb.setTextColor(C_SUB);
        lb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        lb.setPadding(dp(2), dp(8), 0, dp(6));
        parent.addView(lb);

        EditText et = new EditText(this);
        et.setText(def);
        et.setHint(label);
        et.setInputType(inputType);
        et.setTextColor(C_TEXT);
        et.setHintTextColor(Color.parseColor("#4B515C"));
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        et.setSingleLine(true);
        et.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(C_CARD);
        gd.setCornerRadius(dp(10));
        gd.setStroke(dp(1), C_LINE);
        et.setBackground(gd);
        parent.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return et;
    }

    private Button addButton(LinearLayout parent, String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setAllCaps(false);
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{color, darken(color)});
        gd.setCornerRadius(dp(14));
        b.setBackground(gd);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        lp.topMargin = dp(16);
        parent.addView(b, lp);
        return b;
    }

    private static int darken(int c) {
        int r = (int) (Color.red(c) * 0.72f);
        int g = (int) (Color.green(c) * 0.72f);
        int b = (int) (Color.blue(c) * 0.72f);
        return Color.rgb(r, g, b);
    }
}
