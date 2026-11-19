package com.wol.remote;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * 直连发包：不经过中继，直接往公网 IP 或本网段广播地址扔魔术包。
 *
 * 改掉了 v2.1.x 一个纯噪音的行为：
 * 原来除用户填的地址外，还会往 255.255.255.255 补发一份。理由是"万一呢"，
 * 实际结果只有两种 —— 要么手机压根不在那个网段的链路层广播域里，
 * 包被系统直接丢弃；要么真发出去了，也只是一份没人认领的垃圾。
 * 而用户看到的结果行里会多一条"255.255.255.255:9  OK"，
 * 让他以为有两条路都通了，排查时会走错方向。
 *
 * 现在改成：用户填什么就发什么；除此之外只在"填的地址是本机同网段"时
 * 才补发该网段的定向广播地址（这是真的有用的一条：路由器做了 ARP 静态绑定时，
 * 定向广播比单播更容易穿越）。
 *
 * 作者：神仙道  QQ 1974147300
 */
final class WolSender {

    /** 一次唤醒发几轮。太密没意义 —— 主板收一个包就够了，重复只是对抗丢包。 */
    private static final int ROUNDS = 3;
    private static final int ROUND_GAP_MS = 120;

    /** 单条结果。 */
    static final class Sent {
        final String target;
        final boolean ok;
        final String error;

        Sent(String t, boolean o, String e) {
            target = t;
            ok = o;
            error = e == null ? "" : e;
        }
    }

    private WolSender() {
    }

    /**
     * 往列表里的每个目标发魔术包。
     *
     * @param host 用户填的公网 IP / DDNS
     * @param port UDP 端口
     * @param mac  MAC
     * @param alsoSubnetBroadcast 是否顺带发本网段定向广播
     */
    static List<Sent> send(String host, int port, String mac, boolean alsoSubnetBroadcast) {
        List<Sent> out = new ArrayList<Sent>();

        byte[] pkt;
        try {
            pkt = Wol.magic(mac);
        } catch (Throwable e) {
            Diag.w("WolSender.send", e);
            out.add(new Sent(host, false, "MAC 不合法，拼不出魔术包"));
            return out;
        }

        List<String> targets = new ArrayList<String>();
        if (host != null && host.trim().length() > 0) targets.add(host.trim());

        if (alsoSubnetBroadcast) {
            for (String b : Lan.broadcastsOfLocal()) {
                if (!targets.contains(b)) targets.add(b);
            }
        }

        if (targets.isEmpty()) {
            out.add(new Sent("", false, "没有可发的目标地址"));
            return out;
        }

        for (String t : targets) {
            out.add(sendOne(t, port, pkt));
        }
        return out;
    }

    private static Sent sendOne(String host, int port, byte[] pkt) {
        DatagramSocket s = null;
        String label = host + ":" + port;
        try {
            InetAddress addr = InetAddress.getByName(host);
            s = new DatagramSocket();
            // 不设这个，Linux/Android 内核会直接返回 EACCES（Permission denied）。
            // 这是"包发出去了但电脑不醒"最常见的一个原因，而且异常信息完全不直观。
            s.setBroadcast(true);
            DatagramPacket dp = new DatagramPacket(pkt, pkt.length, addr, port);

            // 记一下到底成功送出几个包。
            //
            // 早先是 send 抛异常就直接 return 失败 —— 但"第一轮发成功、第二轮
            // 抛 ENETUNREACH"这种瞬时抖动下，包其实已经出去了，电脑照样会醒，
            // 界面却报"没发出去"。用户于是去查路由器端口映射，查一个不存在的问题。
            // 发出去一个就算发出去，后面几轮只是对抗丢包，不是判断依据。
            int sent = 0;
            Throwable lastErr = null;
            for (int i = 0; i < ROUNDS; i++) {
                try {
                    s.send(dp);
                    sent++;
                } catch (Throwable e) {
                    lastErr = e;
                    Diag.w("WolSender.round" + i + " " + host, e);
                    break;
                }
                if (i < ROUNDS - 1) {
                    try {
                        Thread.sleep(ROUND_GAP_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (sent > 0) {
                // 部分成功也报成功，但把"只发出去 1 个"这件事说清楚，
                // 免得用户以为三轮全通。
                String note = sent < ROUNDS ? ("只送出了 " + sent + "/" + ROUNDS + " 个包") : "";
                return new Sent(label, true, note);
            }
            return new Sent(label, false, describe(lastErr));
        } catch (Throwable e) {
            Diag.w("WolSender.sendOne " + host, e);
            return new Sent(label, false, describe(e));
        } finally {
            if (s != null) s.close();
        }
    }

    private static String describe(Throwable e) {
        String n = e.getClass().getSimpleName();
        if (n.contains("UnknownHost")) return "域名解析不出来";
        if (n.contains("SocketException")) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.contains("EACCES") || m.contains("Permission denied")) {
                return "系统不允许广播（需要 setBroadcast）";
            }
            if (m.contains("Network is unreachable")) return "没有可用网络";
            return "Socket 错误：" + m;
        }
        String m = e.getMessage();
        return n + (m == null || m.length() == 0 ? "" : " " + m);
    }
}
