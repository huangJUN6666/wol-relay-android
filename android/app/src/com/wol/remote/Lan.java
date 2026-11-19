package com.wol.remote;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * 本机网络这一层：我有哪些局域网地址、某个地址通不通、一个网段里谁在跑中继。
 *
 * 从 MainActivity 里剥出来的直接原因是一个真 bug：
 * 原来扫描用 f.get(2, TimeUnit.SECONDS) 等结果，而单次探测内部
 * connect 2500 + read 2500 毫秒，最多能跑 5 秒 ——
 * 也就是说扫到了也会被 Future 的超时直接丢掉，自动发现经常"少报"。
 * 这类"超时值和实际耗时对不上"的问题，只有把探测逻辑收在一个地方才能一眼看全。
 *
 * 作者：神仙道  QQ 1974147300
 */
final class Lan {

    /** 局域网扫描时的连接超时。局域网内 RTT 通常 <5ms，320ms 已经非常宽裕。 */
    static final int SCAN_CONNECT_TIMEOUT = 320;

    /** 并发数。32 是安卓上比较稳的值：再多会被部分 ROM 的 fd 限制卡住。 */
    static final int SCAN_THREADS = 32;

    /** 中继默认监听端口。 */
    static final int RELAY_PORT = 8080;

    private Lan() {
    }

    /** 一个本机可用的 IPv4 网段。 */
    static final class Subnet {
        final InetAddress addr;
        final int prefix;
        final String ifName;

        Subnet(InetAddress a, int p, String n) {
            addr = a;
            prefix = p;
            ifName = n;
        }

        /** 前三段 + 点，例如 "192.168.1."。IPv6 或异常地址返回 null。 */
        String base24() {
            byte[] b = addr.getAddress();
            if (b == null || b.length != 4) return null;
            return (b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF) + ".";
        }

        int selfLast() {
            byte[] b = addr.getAddress();
            return b == null || b.length != 4 ? -1 : (b[3] & 0xFF);
        }

        @Override
        public String toString() {
            return addr.getHostAddress() + "/" + prefix + (ifName == null ? "" : "  " + ifName);
        }
    }

    /**
     * 本机所有能用的局域网 IPv4 地址。
     *
     * 排除的东西每一个都有理由：
     *   - 回环、链路本地（169.254.x.x）—— 连不通任何别人
     *   - 隧道网卡（tun/tap/tailscale/wg/ppp/utun）—— 那是 VPN 的虚拟网段，
     *     在上面扫 /24 只会浪费十几秒，而且扫出来的地址手机根本直连不到
     *   - 100.64.0.0/10 —— Tailscale 用的 CGNAT 段，同上
     */
    static List<Subnet> localSubnets() {
        List<Subnet> out = new ArrayList<Subnet>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                if (looksLikeTunnel(ni.getName())) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address)) continue;
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    if (isCgnat(a)) continue;
                    int prefix = ia.getNetworkPrefixLength();
                    if (prefix <= 0 || prefix > 32) continue;
                    out.add(new Subnet(a, prefix, ni.getDisplayName()));
                }
            }
        } catch (Throwable e) {
            Diag.w("Lan.localSubnets", e);
        }
        return out;
    }

    private static boolean looksLikeTunnel(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        String[] bad = {"tun", "tap", "tailscale", "wg", "ppp", "ipsec", "utun", "rmnet"};
        for (String b : bad) {
            if (n.startsWith(b) || n.contains(b)) return true;
        }
        return false;
    }

    /** 100.64.0.0/10，运营商级 NAT，Tailscale 默认也在这个段里。 */
    private static boolean isCgnat(InetAddress a) {
        byte[] b = a.getAddress();
        if (b == null || b.length != 4) return false;
        int a0 = b[0] & 0xFF;
        int a1 = b[1] & 0xFF;
        return a0 == 100 && a1 >= 64 && a1 <= 127;
    }

    /** 试连一个 TCP 端口。不读不写，连上就算通。 */
    static boolean tcpAlive(String host, int port, int timeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Throwable e) {
            // 连不上是常态（网段里 250 台机器绝大多数没开这个端口），
            // 打日志会把 logcat 刷爆，这里刻意静默。
            return false;
        } finally {
            try {
                s.close();
            } catch (Throwable ignore) {
            }
        }
    }

    /**
     * 本机每个网段的定向广播地址，例如 192.168.1.255。
     *
     * 只在"用户填的地址就在本机网段"时才有意义，由调用方判断。
     * 算的时候要按掩码来，不能一律 .255 —— 用户家里如果用 /16，
     * 192.168.255.255 是错的，正确值是 192.168.255.255（/16 恰好也是这个），
     * 但 /22 就会算出 192.168.3.255 这种值，写死 .255 就发错网段了。
     */
    static List<String> broadcastsOfLocal() {
        List<String> out = new ArrayList<String>();
        for (Subnet s : localSubnets()) {
            byte[] ip = s.addr.getAddress();
            if (ip == null || ip.length != 4) continue;
            int prefix = s.prefix;
            if (prefix < 8 || prefix > 30) continue; // /31 /32 没有广播地址
            int ipInt = ((ip[0] & 0xFF) << 24) | ((ip[1] & 0xFF) << 16)
                    | ((ip[2] & 0xFF) << 8) | (ip[3] & 0xFF);
            int mask = prefix == 0 ? 0 : (0xFFFFFFFF << (32 - prefix));
            int bcast = ipInt | ~mask;
            String addr = ((bcast >>> 24) & 0xFF) + "." + ((bcast >>> 16) & 0xFF)
                    + "." + ((bcast >>> 8) & 0xFF) + "." + (bcast & 0xFF);
            if (!out.contains(addr)) out.add(addr);
        }
        return out;
    }

    /** 这个地址是否落在本机某个网段里（用来判断"要不要顺带发广播"）。 */
    static boolean isLocalPeer(String host) {
        if (host == null || host.length() == 0) return false;
        try {
            InetAddress a = InetAddress.getByName(host);
            for (Subnet s : localSubnets()) {
                byte[] ip = s.addr.getAddress();
                byte[] want = a.getAddress();
                if (ip == null || want == null || ip.length != 4 || want.length != 4) continue;
                int mask = s.prefix == 0 ? 0 : (0xFFFFFFFF << (32 - s.prefix));
                int x = ((ip[0] & 0xFF) << 24) | ((ip[1] & 0xFF) << 16)
                        | ((ip[2] & 0xFF) << 8) | (ip[3] & 0xFF);
                int y = ((want[0] & 0xFF) << 24) | ((want[1] & 0xFF) << 16)
                        | ((want[2] & 0xFF) << 8) | (want[3] & 0xFF);
                if ((x & mask) == (y & mask)) return true;
            }
        } catch (Throwable e) {
            Diag.w("Lan.isLocalPeer", e);
        }
        return false;
    }

    /**
     * 扫一个 /24，把"端口开着并且确实是 wolrelay"的地址挑出来。
     *
     * 返回的每个地址都已经验证过 /status 里带 version 字段 ——
     * 只看端口通是不行的：8080 上跑着路由器管理页、群晖 DSM、各种 IoT 面板的情况太常见了，
     * 拿它们当"找到中继"填进配置，用户点唤醒只会得到一堆莫名其妙的结果。
     *
     * @param skip 已经验证过的地址，不用再扫（避免自检时同一台机器报两遍）
     */
    static List<String> scan(Subnet net, String token, String[] skip) {
        List<String> out = new ArrayList<String>();
        String base = net.base24();
        if (base == null) return out;

        int self = net.selfLast();
        List<String> candidates = new ArrayList<String>(254);
        for (int i = 1; i <= 254; i++) {
            if (i == self) continue;
            String host = base + i;
            if (contains(skip, host)) continue;
            candidates.add(host);
        }
        if (candidates.isEmpty()) return out;

        // 自己实现固定线程池 + 结果回收，不用 Future：Future.get 的超时
        // 和内部 socket 超时是两套时钟，一旦对不上就会静默丢结果（v2.1.x 的 bug）。
        // 这里每个任务只做一件事、超时由 socket 自己控制，收结果用同步队列，
        // 时间关系永远是清楚的。
        final java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(SCAN_THREADS);
        final java.util.concurrent.BlockingQueue<String> done =
                new java.util.concurrent.LinkedBlockingQueue<String>();
        final int total = candidates.size();

        try {
            for (final String host : candidates) {
                pool.execute(new Runnable() {
                    public void run() {
                        try {
                            if (tcpAlive(host, RELAY_PORT, SCAN_CONNECT_TIMEOUT)) {
                                done.put(host);
                            }
                        } catch (Throwable ignore) {
                        }
                    }
                });
            }
            pool.shutdown();

            // 等所有任务结束。shutdown 后再 awaitTermination 是标准做法，
            // 但这里再叠一个硬上限：万一某个 socket 卡住不返回，
            // 也不能让整个扫描永远不结束（用户会以为 App 死了）。
            boolean finished;
            try {
                finished = pool.awaitTermination(8, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                finished = false;
            }

            // 端口开着的一律拉出来验 /status。这一步比较慢，但数量很少（通常 0~3 个）。
            List<String> open = new ArrayList<String>();
            String h;
            while ((h = done.poll()) != null) open.add(h);
            if (!finished) {
                Diag.w("Lan.scan", "扫描超时，只收到 " + open.size() + "/" + total + " 个结果");
            }

            for (String host : open) {
                if (isRelay(host, token)) out.add(host);
            }
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    /**
     * 这个地址上跑的是不是 wolrelay。
     * 判据只有一个：/status 是 JSON 且带 version 字段。
     * 别用 HTTP 200 来判断 —— 路由器管理页也是 200。
     */
    private static boolean isRelay(String host, String token) {
        Devices.Probe p = RelayClient.probe(host, token);
        return p.reach && p.version != null && p.version.length() > 0;
    }

    private static boolean contains(String[] arr, String v) {
        if (arr == null) return false;
        for (String a : arr) {
            if (a != null && a.equals(v)) return true;
        }
        return false;
    }
}
