package com.wol.remote;

import java.util.ArrayList;
import java.util.List;

/**
 * 一键自检：把"为什么唤不醒"能在这里查的都查一遍，给一份人能看懂的结论。
 *
 * 这个类的核心不是检测逻辑（那些散在 Lan / RelayClient 里），
 * 而是**结论怎么下**。自检失败时的价值全在"卡在哪一层"：
 *
 *   本机没网            -> 先连 WiFi
 *   网段里没中继        -> 备用机没开 / 不在同一网 / 中继没跑
 *   中继通了但 MAC 不对  -> 改一边的配置
 *   中继开了鉴权没填令牌 -> 抄 token
 *   全都对             -> 可以发
 *
 * 之前这一步是在 Activity 里用一长串 if 拼出来的，而且判断条件读的是
 * 界面控件（etToken.getText()），在后台线程里跑就会崩。现在所有输入
 * 都通过参数传进来，不碰任何 View。
 *
 * 作者：神仙道  QQ 1974147300
 */
final class SelfCheck {

    /** 自检结果。 */
    static final class Report {
        /** 结论一句话（多行），放最上面。 */
        String verdict = "";
        /** 结论的颜色资源。 */
        int verdictColor = R.color.red;
        /** 详细过程。 */
        String detail = "";
        /** 扫到并验证通过的中继地址。 */
        List<String> foundRelays = new ArrayList<String>();
        /** 已经配置并且能通的。 */
        List<String> aliveConfigured = new ArrayList<String>();
        /** 中继上配的设备（如果读过 /devices）。 */
        List<Devices.RemoteDevice> relayDevices = new ArrayList<Devices.RemoteDevice>();
        /** App 里的 MAC 和中继是否一致。 */
        boolean macMismatch;
        /** 中继开了鉴权但 App 没填令牌。 */
        boolean tokenMissing;
        /** 中继版本，用来提示升级。 */
        String relayVersion;
    }

    private SelfCheck() {
    }

    /**
     * 跑一次自检。这个方法是阻塞的，必须在后台线程调用。
     *
     * @param appMac   App 里当前设备的 MAC，可能为空
     * @param token    访问令牌，可能为空
     * @param publicIp 方式一填的公网地址，可能为空
     * @param relayAddrs 已配置的中继地址
     */
    static Report run(String appMac, String token, String publicIp, String[] relayAddrs) {
        Report r = new Report();
        StringBuilder sb = new StringBuilder();
        List<Devices.Probe> probes = new ArrayList<Devices.Probe>();

        // ---- 1. 本机网络 ----
        List<Lan.Subnet> nets = Lan.localSubnets();
        if (nets.isEmpty()) {
            sb.append("本机  没有拿到局域网地址\n");
        } else {
            for (Lan.Subnet s : nets) {
                sb.append("本机  ").append(s.toString()).append("\n");
            }
        }

        // ---- 2. 逐个验证已配置的中继 ----
        sb.append("\n");
        if (relayAddrs == null || relayAddrs.length == 0) {
            sb.append("中继  没有配置\n");
        } else {
            for (String a : relayAddrs) {
                Devices.Probe p = RelayClient.probe(a, token);
                probes.add(p);
                sb.append(fmtProbe(p, appMac));
            }
        }

        // ---- 3. 扫网段 ----
        if (!nets.isEmpty()) {
            sb.append("\n扫描  ");
            String[] skip = relayAddrs;
            int scannedCount = 0;
            for (Lan.Subnet s : nets) {
                for (String hit : Lan.scan(s, token, skip)) {
                    if (r.foundRelays.contains(hit)) continue;
                    r.foundRelays.add(hit);
                    scannedCount++;
                    Devices.Probe p = RelayClient.probe(hit, token);
                    probes.add(p);
                    sb.append("\n").append(fmtProbe(p, appMac));
                }
            }
            if (scannedCount == 0) {
                sb.append("本网段没找到在跑的中继\n");
            } else {
                sb.append("\n      共找到 ").append(scannedCount).append(" 个\n");
            }
        }

        // ---- 4. 方式一 ----
        sb.append("\n");
        if (publicIp == null || publicIp.length() == 0) {
            sb.append("方式一  没有配置\n");
        } else {
            sb.append("方式一  ").append(publicIp).append("\n");
            // UDP 无连接，能做的只有"域名解析得出来吗"。
            // 这里不假装能测通 —— v2.1.x 曾经用 TCP 探测结果去暗示
            // "端口不响应"，那条提示误导过很多人（UDP 本来就不响应）。
            boolean resolved = resolvable(publicIp);
            if (resolved) {
                sb.append("  域名解析正常。UDP 无法在这里验证，只能实际发一次看电脑醒不醒\n");
            } else {
                sb.append("  这个地址解析不出来，检查拼写，或者 DDNS 是不是过期了\n");
            }
        }

        // ---- 5. 汇总那些"需要用户动手"的判断 ----
        r.aliveConfigured = new ArrayList<String>();
        for (Devices.Probe p : probes) {
            if (p.reach) {
                r.aliveConfigured.add(p.addr);
                if (r.relayVersion == null && p.version != null) r.relayVersion = p.version;
                if (p.auth && (token == null || token.length() == 0)) r.tokenMissing = true;
                if (appMac != null && appMac.length() > 0 && !macMatches(p, appMac)) {
                    r.macMismatch = true;
                }
            }
        }

        // ---- 6. 结论 ----
        Object[] v = verdict(r, nets.isEmpty(), appMac, publicIp);
        r.verdict = (String) v[0];
        r.verdictColor = (Integer) v[1];
        r.detail = sb.toString().trim();
        return r;
    }

    /** 返回 {结论文字, 颜色资源}。 */
    private static Object[] verdict(Report r, boolean noNet, String appMac, String publicIp) {
        if (noNet) {
            return new Object[]{"结论：手机没连上局域网\n先连 WiFi，再点一次自检。", R.color.red};
        }
        if (r.aliveConfigured.isEmpty()) {
            if (publicIp != null && publicIp.length() > 0) {
                return new Object[]{
                        "结论：没找到中继，方式一也没法在这里验证\n"
                                + "可以先用「发送魔术包」试一次，UDP 只能实测。",
                        R.color.amber};
            }
            return new Object[]{
                    "结论：没找到中继\n"
                            + "备用机是不是开着机、连着同一个 WiFi？中继服务是不是在跑？",
                    R.color.red};
        }
        if (r.tokenMissing) {
            return new Object[]{
                    "结论：中继开了鉴权，但 App 里没填令牌\n"
                            + "令牌在中继的 config.json 里，抄到「访问令牌」那一栏。",
                    R.color.amber};
        }
        if (appMac == null || appMac.length() == 0) {
            return new Object[]{
                    "结论：中继通了，但还没选设备\n先添加一台设备，把电脑网卡的 MAC 填上。",
                    R.color.amber};
        }
        if (r.macMismatch) {
            return new Object[]{
                    "结论：中继里配的 MAC 跟这台电脑对不上\n"
                            + "改中继的 config.json，或者在 App 里换成一致的设备。\n"
                            + "（如果中继配了好几台机器，点「从中继读取设备」会自动对好）",
                    R.color.amber};
        }
        return new Object[]{
                "结论：可以发\n点「唤醒」，然后看着它起来 —— 会告诉你第几秒醒的。",
                R.color.green};
    }

    private static String fmtProbe(Devices.Probe p, String appMac) {
        StringBuilder s = new StringBuilder();
        s.append("  ").append(p.addr);
        if (!p.reach) {
            s.append("  连不上\n");
            if (p.err != null && p.err.length() > 0) {
                s.append("    ").append(p.err).append("\n");
            }
            return s.toString();
        }
        s.append("  通\n");
        if (p.version != null) s.append("    服务  wolrelay ").append(p.version).append("\n");
        if (p.deviceCount > 0) s.append("    设备  ").append(p.deviceCount).append(" 台\n");
        if (p.mac != null && p.mac.length() > 0) {
            s.append("    MAC   ").append(Wol.pretty(p.mac));
            if (appMac != null && appMac.length() > 0) {
                s.append(macMatches(p, appMac) ? "  与 App 一致" : "  与 App 不一致");
            }
            s.append("\n");
        }
        s.append("    鉴权  ").append(p.auth ? "已开启" : "关闭").append("\n");
        if (p.targets != null && p.targets.length() > 0) {
            s.append("    广播  ").append(p.targets).append("\n");
        }
        return s.toString();
    }

    /**
     * 判断中继配的 MAC 跟 App 里选的是不是同一台。
     *
     * 中继对未鉴权请求只返回打码 MAC（AA:BB:CC:XX:XX:XX），
     * 直接字符串比对必然不等 —— 那会让 App 每次都误报"MAC 对不上"。
     * 按证据强度依次取：
     *   1. 有完整 MAC         -> 直接比
     *   2. 只有打码 MAC + 指纹 -> 比指纹（中继用同一套 FNV-1a）
     *   3. 只有打码 MAC        -> 只比前 3 段（厂商 OUI），能排除大部分配错
     *   4. 什么都没有          -> 不判断，当成一致，别误报
     */
    static boolean macMatches(Devices.Probe p, String appMac) {
        if (appMac == null || appMac.length() == 0) return true;
        String normApp = Wol.norm(appMac);
        if (normApp.length() != 12) return true;

        if (p.macFull && p.mac != null && p.mac.length() > 0) {
            return Wol.norm(p.mac).equals(normApp);
        }
        if (p.macFp != null && p.macFp.length() == 8) {
            return p.macFp.equalsIgnoreCase(Wol.fingerprint(appMac));
        }
        String relay = Wol.norm(p.mac);
        if (relay.length() == 12 && relay.contains("X")) {
            return relay.substring(0, 6).equals(normApp.substring(0, 6));
        }
        if (relay.length() == 12) {
            return relay.equals(normApp);
        }
        return true;
    }

    /** 域名能不能解析。纯 UDP 目标没法验证连通性，只能验证这一步。 */
    private static boolean resolvable(String host) {
        try {
            java.net.InetAddress.getByName(host);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
