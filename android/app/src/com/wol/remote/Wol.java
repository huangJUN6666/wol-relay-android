package com.wol.remote;

/**
 * MAC 地址与魔术包：全是纯函数，没有状态、不碰网络。
 *
 * 单独拎出来的理由不是"分层好看"，是这里每个函数都同时被三处用到
 * （直连发包、中继比对、界面显示），散在 Activity 里的话，
 * 改一处漏一处 —— 比如打码规则改了但比对规则没改，就会天天误报"MAC 对不上"。
 *
 * 作者：神仙道  QQ 1974147300
 */
final class Wol {

    /** 魔术包长度：6 个 0xFF + 16 遍 MAC。多一点少一点，主板就不认。 */
    private static final int PACKET_LEN = 102;

    private Wol() {
    }

    /**
     * 把用户随手填的 MAC 规整成 12 位大写十六进制。
     * 大小写、冒号、短横线、空格、点分（Cisco 写法）全认。
     */
    static String norm(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ':' || c == '-' || c == ' ' || c == '.') continue;
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    /** 是不是一个能用的 MAC。 */
    static boolean ok(String mac) {
        String n = norm(mac);
        if (n.length() != 12) return false;
        for (int i = 0; i < 12; i++) {
            char c = n.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    /** 打上冒号的展示形式：AA:BB:CC:DD:EE:FF。 */
    static String pretty(String mac) {
        String n = norm(mac);
        if (n.length() != 12) return mac == null ? "" : mac;
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) sb.append(':');
            sb.append(n, i, i + 2);
        }
        return sb.toString();
    }

    /**
     * 报告里不打印完整 MAC，前 3 段够认人了。
     *
     * 为什么只留前 3 段：前 3 字节是厂商 OUI，全世界几百万台机器都一样，
     * 泄露它没有任何信息量；后 3 字节才是这台机器的唯一标识，必须遮住。
     */
    static String mask(String mac) {
        String n = norm(mac);
        if (n.length() != 12) return mac == null ? "" : mac;
        return n.substring(0, 2) + ":" + n.substring(2, 4) + ":" + n.substring(4, 6)
                + ":XX:XX:XX";
    }

    /**
     * FNV-1a 32 位指纹，跟中继 relay/util.go 里的 macFingerprint() 必须一致。
     *
     * 为什么要它：中继对未鉴权的请求只返回打码 MAC，App 拿不到完整值就没法比对
     * "中继配的是不是我要开的那台"。指纹是单向的，泄露了指纹也反推不出 MAC，
     * 而两边算出来一样就说明是同一台 —— 这是唯一能同时满足"能比对"和"不泄露"的做法。
     */
    static String fingerprint(String mac) {
        String n = norm(mac);
        if (n.length() != 12) return "";
        int h = 0x811c9dc5;
        for (int i = 0; i < 12; i++) {
            h ^= n.charAt(i);
            h *= 0x01000193;
        }
        // 用 & 0xFFFFFFFFL 兜住符号位，不然 0x80000000 以上会打出 "-7fffffff"
        return String.format("%08x", h & 0xFFFFFFFFL);
    }

    /** 拼出 102 字节的魔术包。MAC 不合法会抛 IllegalArgumentException。 */
    static byte[] magic(String mac) {
        String n = norm(mac);
        if (n.length() != 12) {
            throw new IllegalArgumentException("MAC 长度不对：" + mac);
        }
        byte[] m = new byte[6];
        for (int i = 0; i < 6; i++) {
            int hi = Character.digit(n.charAt(i * 2), 16);
            int lo = Character.digit(n.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("MAC 里有非十六进制字符：" + mac);
            }
            m[i] = (byte) ((hi << 4) | lo);
        }
        byte[] p = new byte[PACKET_LEN];
        for (int i = 0; i < 6; i++) p[i] = (byte) 0xFF;
        for (int i = 1; i <= 16; i++) System.arraycopy(m, 0, p, i * 6, 6);
        return p;
    }
}
