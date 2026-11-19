package com.wol.remote;

import android.util.Log;

/**
 * 日志出口。
 *
 * 为什么单独包一层，而不是直接调 android.util.Log：
 * 这个 App 里大量 catch 都是"失败的正常情况"（网段里 250 台机器连不上是常态），
 * 那种不该打日志；但真正异常的情况（配置解析失败、socket 被系统拦掉）
 * 一律要留一行，否则用户在论坛上说"点不动"，我们连个线索都没有。
 *
 * 用 Diag 而不是 Log，是为了把"该打"和"不该打"在代码里显式区分开：
 * 调 Diag.w 就必须先想一下这句是不是诊断信息。
 * 同时它在单元测试（没有 Android 运行时）里也不会炸。
 *
 * 作者：神仙道  QQ 1974147300
 */
final class Diag {

    /** logcat 过滤用：adb logcat -s WolRelay */
    private static final String TAG = "WolRelay";

    private Diag() {
    }

    static void w(String where, String msg) {
        safe(Log.WARN, where + ": " + msg, null);
    }

    static void w(String where, Throwable e) {
        safe(Log.WARN, where + ": " + describe(e), e);
    }

    static void i(String msg) {
        safe(Log.INFO, msg, null);
    }

    /** 异常转一句话，带类型和消息，够定位就行，不要堆栈。 */
    static String describe(Throwable e) {
        if (e == null) return "null";
        String n = e.getClass().getSimpleName();
        String m = e.getMessage();
        if (m == null || m.length() == 0) return n;
        return n + " " + (m.length() > 80 ? m.substring(0, 80) + "…" : m);
    }

    private static void safe(int level, String msg, Throwable e) {
        try {
            Log.println(level, TAG, msg);
            if (e != null) Log.println(Log.WARN, TAG, Log.getStackTraceString(e));
        } catch (Throwable ignore) {
            // 没有 Android 运行时（本地跑纯逻辑测试）时走这里。
            // 日志本身失败不能再抛，否则会盖掉真正要报的错。
        }
    }
}
