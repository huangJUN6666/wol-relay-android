package com.wol.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置存储。
 *
 * v2.2.0 的两个变化：
 *
 * 1. 设备从一台变成多台。原来只有 mac 一个字段，想换台电脑就得把旧的覆盖掉。
 *    现在存成 JSON 数组，用户可以存"书房电脑 / NAS / 客厅 HTPC"，界面上直接选。
 *
 * 2. 每个设备带上 relayName。这是关键的一处 —— 中继上叫 pc 和 nas，
 *    App 里如果只存 MAC，用户看到中继网页里两个名字、App 里两串 MAC，
 *    就对不上哪个是哪个。所以 App 必须记住"我这台，在中继上叫 pc"，
 *    唤醒时用 /wake?name=pc 精确指定。
 *
 * 旧配置（v2.1.x 只有单个 mac 字符串）自动迁移成一台设备，
 * 用户升级 App 之后不会发现自己填的东西没了。
 *
 * 作者：神仙道  QQ 1974147300
 */
final class Prefs {

    private static final String FILE = "wol_pref";

    private static final String K_DEVICES = "devices_json";
    private static final String K_SELECTED = "selected_index";
    private static final String K_RELAY = "relay";
    private static final String K_TOKEN = "token";
    private static final String K_PUBLIC_IP = "public_ip";
    private static final String K_PUBLIC_PORT = "public_port";
    private static final String K_LAST_SELFCHECK = "last_selfcheck";

    // 旧键，只用于迁移
    private static final String K_OLD_MAC = "mac";
    private static final String K_OLD_IP = "ip";
    private static final String K_OLD_PORT = "port";

    static final String DEF_PORT = "9";

    private Prefs() {
    }

    // ============================================================
    //  设备
    // ============================================================

    static List<Devices.Device> loadDevices(Context ctx) {
        SharedPreferences sp = sp(ctx);
        List<Devices.Device> out = new ArrayList<Devices.Device>();

        String raw = sp.getString(K_DEVICES, "");
        if (!TextUtils.isEmpty(raw)) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    Devices.Device d = new Devices.Device();
                    d.relayName = o.optString("relayName", "");
                    d.mac = Wol.norm(o.optString("mac", ""));
                    d.label = o.optString("label", "");
                    d.host = o.optString("host", "");
                    d.ports = o.optString("ports", "");
                    if (d.usable() || d.label.length() > 0) out.add(d);
                }
            } catch (Throwable e) {
                // 配置坏了不能让 App 打不开。丢掉坏数据，退回旧字段或空表。
                Diag.w("Prefs.loadDevices", e);
            }
        }

        // 旧版本迁移：只有 mac 一个字符串
        if (out.isEmpty()) {
            String oldMac = sp.getString(K_OLD_MAC, "");
            if (!TextUtils.isEmpty(oldMac) && Wol.ok(oldMac)) {
                Devices.Device d = new Devices.Device();
                d.mac = Wol.norm(oldMac);
                d.label = "我的电脑";
                out.add(d);
                // 迁移完立刻写回新格式，但旧键先留着 ——
                // 万一用户装了新版又退回旧版，旧版还能读到自己的配置。
                saveDevices(ctx, out);
                Diag.i("已把旧版单设备配置迁移到设备列表");
            }
        }
        return out;
    }

    static void saveDevices(Context ctx, List<Devices.Device> list) {
        JSONArray arr = new JSONArray();
        for (Devices.Device d : list) {
            if (d == null) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("relayName", d.relayName == null ? "" : d.relayName);
                o.put("mac", d.mac == null ? "" : d.mac);
                o.put("label", d.label == null ? "" : d.label);
                o.put("host", d.host == null ? "" : d.host);
                o.put("ports", d.ports == null ? "" : d.ports);
                arr.put(o);
            } catch (Throwable e) {
                Diag.w("Prefs.saveDevices", e);
            }
        }
        sp(ctx).edit().putString(K_DEVICES, arr.toString()).apply();
    }

    static int loadSelected(Context ctx, int count) {
        if (count <= 0) return 0;
        int i = sp(ctx).getInt(K_SELECTED, 0);
        return i >= 0 && i < count ? i : 0;
    }

    static void saveSelected(Context ctx, int i) {
        sp(ctx).edit().putInt(K_SELECTED, Math.max(0, i)).apply();
    }

    // ============================================================
    //  中继
    // ============================================================

    /** 中继地址，可能多个（空格/逗号分隔）。保持字符串形式，因为它本来就是用户手填的。 */
    static String loadRelay(Context ctx) {
        SharedPreferences sp = sp(ctx);
        String v = sp.getString(K_RELAY, "");
        // 旧版把这个信息拆成了 relay + 老 ip 两个地方，新装用户就是空
        return v == null ? "" : v;
    }

    static void saveRelay(Context ctx, String v) {
        sp(ctx).edit().putString(K_RELAY, v == null ? "" : v).apply();
    }

    static String loadToken(Context ctx) {
        String v = sp(ctx).getString(K_TOKEN, "");
        return v == null ? "" : v;
    }

    static void saveToken(Context ctx, String v) {
        sp(ctx).edit().putString(K_TOKEN, v == null ? "" : v).apply();
    }

    // ============================================================
    //  方式一 · 直连公网
    // ============================================================

    static String loadPublicIp(Context ctx) {
        SharedPreferences sp = sp(ctx);
        String v = sp.getString(K_PUBLIC_IP, "");
        if (TextUtils.isEmpty(v)) v = sp.getString(K_OLD_IP, "");
        return v == null ? "" : v;
    }

    static String loadPublicPort(Context ctx) {
        SharedPreferences sp = sp(ctx);
        String v = sp.getString(K_PUBLIC_PORT, "");
        if (TextUtils.isEmpty(v)) v = sp.getString(K_OLD_PORT, "");
        return TextUtils.isEmpty(v) ? DEF_PORT : v;
    }

    static void savePublic(Context ctx, String ip, String port) {
        sp(ctx).edit()
                .putString(K_PUBLIC_IP, ip == null ? "" : ip)
                .putString(K_PUBLIC_PORT, port == null ? DEF_PORT : port)
                .apply();
    }

    static String loadLastSelfCheck(Context ctx) {
        String v = sp(ctx).getString(K_LAST_SELFCHECK, "");
        return v == null ? "" : v;
    }

    static void saveLastSelfCheck(Context ctx, String v) {
        sp(ctx).edit().putString(K_LAST_SELFCHECK, v == null ? "" : v).apply();
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
