package com.wol.remote;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据模型：一台设备、一次探测、一次唤醒请求、一份确认进度。
 *
 * 全是哑数据类，不含逻辑 —— 放一起是因为它们描述的是同一件事
 * （"我要开的那台机器，现在是什么状态"），拆成四个文件反而要多跳四次。
 *
 * 作者：神仙道  QQ 1974147300
 */
final class Devices {

    private Devices() {
    }

    /**
     * App 里的一台设备：中继上的设备名 + MAC + 可选固定地址。
     *
     * 为什么 App 也要存 MAC：中继可能配了好几台，App 要能自己指出
     * "我点的是书房电脑，中继上叫 pc" 这件事，否则用户在中继的网页上
     * 看到 pc / nas 两个名字，在 App 里只看到 MAC，就对不上了。
     */
    static final class Device {
        /** 中继上的设备名，/wake?name= 用的就是它。空表示中继只有一台、不用指定。 */
        String relayName = "";
        /** 电脑有线网卡的 MAC，12 位大写十六进制。 */
        String mac = "";
        /** 人看的名字，允许中文。默认跟着 relayName 或 MAC 后两位走。 */
        String label = "";
        /** 可选：这台机器的固定 IP / DDNS。跨网段部署时填，唤醒后直接探它。 */
        String host = "";
        /** 可选：只对这台设备生效的确认端口。空表示用中继的全局设置。 */
        String ports = "";

        Device() {
        }

        Device(String relayName, String mac, String label) {
            this.relayName = relayName == null ? "" : relayName;
            this.mac = Wol.norm(mac);
            this.label = label == null ? "" : label;
        }

        /** 界面上显示什么。没起名字就退到 MAC 后两字节，比显示一整串 MAC 好认。 */
        String display() {
            if (label != null && label.length() > 0) return label;
            if (relayName != null && relayName.length() > 0) return relayName;
            String n = Wol.norm(mac);
            if (n.length() == 12) return "设备 " + n.substring(8, 12);
            return "未命名设备";
        }

        boolean usable() {
            return Wol.ok(mac) || (host != null && host.length() > 0);
        }

        Device copy() {
            Device d = new Device();
            d.relayName = relayName;
            d.mac = mac;
            d.label = label;
            d.host = host;
            d.ports = ports;
            return d;
        }

        @Override
        public String toString() {
            return display();
        }
    }

    /** 一次 /status 探测的结果。 */
    static final class Probe {
        String addr = "";
        boolean reach;
        String version;
        /** 中继返回的 MAC，可能已打码。 */
        String mac;
        /** 中继返回的 MAC 指纹，用来在拿不到完整 MAC 时比对。 */
        String macFp;
        /** 中继是否返回了完整 MAC（只有带对 token 才是 true）。 */
        boolean macFull;
        boolean auth;
        String targets;
        /** 中继上配了几台设备。老版本中继没有这个字段，为 0。 */
        int deviceCount;
        String err;
    }

    /** /devices 返回的一台中继侧设备。 */
    static final class RemoteDevice {
        String name = "";
        String mac = "";
        String macFp = "";
        String host = "";
    }

    /** 一次 /wake 的结果。 */
    static final class WakeResult {
        static final int OK = 0;
        /** 中继连上了但令牌不对。 */
        static final int TOKEN = 1;
        /** 中继返回了 HTTP 错误，msg 里带中继自己说的原因。 */
        static final int HTTP = 2;
        /** 网络层就没通。 */
        static final int IO = 3;
        /** 中继收到了请求，但一个包都没发出去 —— 问题在中继侧。 */
        static final int ZERO_SENT = 4;

        int kind = IO;
        int sent;
        int total;
        String msg = "";
        String device = "";
        /** 确认任务 ID，拿它去轮询 /confirm。空表示这个中继不支持确认。 */
        String confirmId = "";
        int confirmTimeout;

        boolean sentOk() {
            return kind == OK && sent > 0;
        }

        String shortMsg() {
            switch (kind) {
                case IO:
                    return "连不上";
                case ZERO_SENT:
                    return "中继一个包都没发出去";
                default:
                    return msg == null || msg.length() == 0 ? "失败" : msg;
            }
        }
    }

    /**
     * 一次唤醒确认的进度。对应中继 /confirm 的 result 对象。
     *
     * 关键字段只有四个：
     *   state     —— waiting / online / timeout / retry，界面按它走分支
     *   onlineIn  —— 第几秒确认通了，给"已开机 · 23 秒"用
     *   arpIn     —— 第几秒网卡上线了，给"网卡已上线，等系统起来"用
     *   already   —— 3 秒内就通了，说明它本来就开着，不能报"已开机 · 1 秒"
     *
     * 关于 retry 这个第四态：
     * 原来只有三态，于是"这一秒没问到中继"只能塞进 timeout。后果是一次
     * WiFi 抖动、一个瞬时 502，界面就直接宣布"等了 90 秒，没等到它起来"，
     * 而中继那边可能下一秒就报 online 了。用户看到的是项目最重要的一句
     * 结论被一次网络毛刺推翻 —— 这比慢三秒糟得多。
     * 所以把"没问到"和"问到了、答案是没醒"分开：retry 不是终点，
     * 它只是说这一轮没有信息，下轮继续问。
     */
    static final class ConfirmState {
        static final String WAITING = "waiting";
        static final String ONLINE = "online";
        static final String TIMEOUT = "timeout";
        /** 这一轮没问到中继。不是结论，下轮重试。 */
        static final String RETRY = "retry";

        /** 超时归因：ARP 里从头到尾没见过它的网卡 —— 网络侧。 */
        static final String CAUSE_NO_ARP = "no_arp";
        /** 超时归因：网卡上电了，但系统端口没起 —— 电脑侧。 */
        static final String CAUSE_ARP_ONLY = "arp_only";
        /** 超时归因：只配了 host，没有 MAC 可查。 */
        static final String CAUSE_NO_MAC = "no_mac";

        String id = "";
        String device = "";
        String state = WAITING;
        double elapsed;
        double onlineIn;
        double arpIn;
        String ip = "";
        int port;
        int attempts;
        boolean scanning;
        boolean already;
        String note = "";

        /**
         * 超时的机器可读归因，由中继给出：no_arp / arp_only / no_mac。
         *
         * 之前这里是拿 note 的中文文案做关键词匹配（contains("ARP")
         * || contains("网卡")）。两条归因文案里恰好都带"网卡"二字，于是
         * "网卡 3.2 秒时上电了，但系统端口一直没起来"（电脑侧）会被判成
         * 网络侧，反过来建议用户去查网线。文案一改就错，改了也不知道。
         * 现在只认这个字段；老中继没有它，走 u 兜底。
         */
        String cause = "";

        boolean done() {
            return ONLINE.equals(state) || TIMEOUT.equals(state);
        }

        boolean online() {
            return ONLINE.equals(state);
        }

        /** 这一轮没问到中继，等会儿再问一次。不是结论。 */
        boolean transientError() {
            return RETRY.equals(state);
        }

        /**
         * 浅拷贝一份。
         *
         * WakeWatcher 在"这一轮没问到中继"时要拿上一次的进度继续显示，
         * 但会改其中的 state / note。如果直接改原对象，被改的就是它自己存的
         * 那份"已知进度" —— 第一次还算对，之后每次都在改一个已经被改过的值，
         * 属于那种现在不出错、加个字段就炸的写法。拷一份最省心。
         */
        ConfirmState copy() {
            ConfirmState c = new ConfirmState();
            c.id = id;
            c.device = device;
            c.state = state;
            c.elapsed = elapsed;
            c.onlineIn = onlineIn;
            c.arpIn = arpIn;
            c.ip = ip;
            c.port = port;
            c.attempts = attempts;
            c.scanning = scanning;
            c.already = already;
            c.note = note;
            c.cause = cause;
            return c;
        }

        /** 当前该显示的那句话。界面不自己拼字符串，全在这里收口。 */
        String headline(int limitSeconds) {
            if (ONLINE.equals(state)) {
                if (already) return "它本来就是开着的";
                return "已开机 · " + secs(onlineIn);
            }
            if (TIMEOUT.equals(state)) {
                return "等了 " + limitSeconds + " 秒，没等到它起来";
            }
            if (scanning) return "网卡还没出现，正在扫网段…";
            if (arpIn > 0) return "网卡已上线（" + secs(arpIn) + "），等系统起来";
            return "正在确认…";
        }

        /**
         * 副标题：超时的归因，或者正在等的时候的补充信息。
         *
         * 这段文字是整个功能里最值钱的部分 —— 它把"没醒"这个模糊结论
         * 劈成"网络侧"和"电脑侧"两半，用户不用挨个猜。
         */
        String detail() {
            if (RETRY.equals(state)) {
                // 不把网络错误写成结论。这里只交代"这一轮没问到"，
                // 后面紧跟的话一定是"继续问"，而不是"它没醒"。
                if (note != null && note.length() > 0) {
                    return note + "\n中继暂时没应答，继续重试…";
                }
                return "中继暂时没应答，继续重试…";
            }
            if (TIMEOUT.equals(state)) {
                // 归因只用 cause 判断，绝不解析 note 的措辞。
                // 两条 note 里都有"网卡"二字，关键词匹配必定误判其中一条。
                if (CAUSE_NO_ARP.equals(cause)) {
                    return note + "\n先查网络这一侧：中继和电脑是不是同一个网段、"
                            + "网线/交换机通不通、路由器有没有隔离广播。";
                }
                if (CAUSE_ARP_ONLY.equals(cause)) {
                    return note + "\n问题在电脑这一侧：BIOS 的网络唤醒开关、"
                            + "Windows 快速启动、硬盘或内存自检。";
                }
                if (note != null && note.length() > 0) return note;
                return "包发出去了，但没等到它起来";
            }
            if (ONLINE.equals(state)) {
                if (already) return "中继连上它的时候，它已经开着了 —— 这次没走到唤醒那一步。";
                String s = "中继确认它能连上了";
                if (port > 0) s += "（端口 " + port;
                if (ip != null && ip.length() > 0) s += (port > 0 ? "，" : "（") + ip;
                if (port > 0 || (ip != null && ip.length() > 0)) s += "）";
                return s;
            }
            if (note != null && note.length() > 0) return note;
            return "包已经发出去了";
        }

        private static String secs(double v) {
            if (v <= 0) return "0 秒";
            if (v < 10) return String.format(java.util.Locale.US, "%.1f 秒", v);
            return Math.round(v) + " 秒";
        }
    }

    /** 从多行文本里解析设备列表（App 里手填的那种，"名字=MAC" 一行一个）。 */
    static List<Device> parseLines(String raw) {
        List<Device> out = new ArrayList<Device>();
        if (raw == null) return out;
        for (String line : raw.split("\\r?\\n")) {
            String s = line.trim();
            if (s.length() == 0 || s.startsWith("#")) continue;
            String name = "";
            String rest = s;
            int eq = s.indexOf('=');
            if (eq > 0) {
                name = s.substring(0, eq).trim();
                rest = s.substring(eq + 1).trim();
            }
            // 剩下可能有 MAC、host、端口，空格分开
            String[] parts = rest.split("[\\s,;]+");
            Device d = new Device();
            d.label = name;
            d.relayName = name;
            for (String p : parts) {
                if (p.length() == 0) continue;
                if (Wol.ok(p)) d.mac = Wol.norm(p);
                else if (p.matches("\\d{1,5}(,\\d{1,5})*")) d.ports = p;
                else if (p.matches("[0-9A-Za-z.:\\-]+")) d.host = p;
            }
            if (d.usable()) out.add(d);
        }
        return out;
    }
}
