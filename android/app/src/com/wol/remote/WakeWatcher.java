package com.wol.remote;

import android.os.Handler;
import android.os.Looper;

/**
 * 唤醒观察器：发完包之后，每秒问一次中继"醒了没有"，直到出结果。
 *
 * 这是整个项目在 App 侧唯一的闭环。用户真正关心的不是"包发没发出去"，
 * 是"到底醒没醒"。所以这里的默认行为是：发完立刻显示进度，
 * 每秒刷新一次，一旦确认就报"已开机 · 23 秒"，超时就报归因。
 *
 * 三个设计取舍：
 *
 * 1. 每秒轮询而不是长轮询。中继是 Go 写的单文件服务，长轮询要多占一个
 *    goroutine 和一条连接；而手机端每秒一个几十字节的 GET，
 *    在局域网里连电都算不上。简单可靠的方案优先。
 *
 * 2. 到点立刻停。中继的 confirm_timeout 是它自己的上限，但 App 这边
 *    可能更早失去兴趣（用户按了返回键、切到后台）。stop() 要能
 *    在任何时刻被安全调用，且不会在 Activity 销毁后再回调界面。
 *
 * 3. 界面回调一律 post 到主线程。原来有一段代码在后台线程直接读
 *    EditText，Activity 一销毁就空指针 —— 这里的回调只传数据，
 *    碰界面的事全交给调用方在主线程做。
 *
 * 作者：神仙道  QQ 1974147300
 */
final class WakeWatcher {

    /** 每秒一次。再密没有信息增量，中继自己的探测周期就是 1.5 秒。 */
    private static final int POLL_MS = 1000;

    /**
     * 连续这么多次没问到中继就收尾。
     *
     * 为什么不干脆一直轮询到 limitSeconds：WiFi 断了/中继挂了的时候，
     * 再问 90 次也不会有答案，只是把"连接失败"重复 90 遍。但也不能第一次
     * 失败就收 —— 那正是我们要修的 bug。8 秒是个折中：单次毛刺扛得住，
     * 真断线也不至于让用户盯着转圈一分半。
     */
    private static final int MAX_CONSECUTIVE_FAILS = 8;

    /** 回调统一在主线程。 */
    interface Listener {
        void onTick(Devices.ConfirmState st);

        /** 出最终结果（online / timeout）后调用一次，之后不会再有不回调。 */
        void onDone(Devices.ConfirmState st);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final String addr;
    private final String token;
    private final String jobId;
    private final String deviceName;
    private final int limitSeconds;
    private final Listener listener;

    private volatile boolean stopped;
    private long startedAt;

    /** 连续"没问到中继"的次数，只在这个类自己的线程里读写（都在主线程回调里改）。 */
    private int consecutiveFails;

    /** 最后一次成功从中继拿到的进度，用来在失联时保留已知信息。 */
    private Devices.ConfirmState lastKnown;

    WakeWatcher(String addr, String token, String jobId, String deviceName,
                int limitSeconds, Listener listener) {
        this.addr = addr;
        this.token = token;
        this.jobId = jobId;
        this.deviceName = deviceName;
        this.limitSeconds = limitSeconds > 0 ? limitSeconds : 90;
        this.listener = listener;
    }

    /** 开始轮询。同一个实例只调一次。 */
    void start() {
        startedAt = System.currentTimeMillis();
        ui.postDelayed(poll, 0);
    }

    /**
     * 停止轮询。可以在任意线程、任意次数调用。
     * 停止后所有待执行的回调都会自己退出，不会碰到已销毁的界面。
     */
    void stop() {
        stopped = true;
        ui.removeCallbacks(poll);
    }

    private final Runnable poll = new Runnable() {
        public void run() {
            if (stopped) return;

            // 兜底：中继那边彻底失联（比如 WiFi 断了）时，
            // 不能无限轮询下去，到 limit 就自己收尾。
            long elapsedMs = System.currentTimeMillis() - startedAt;
            if (elapsedMs > (limitSeconds + 5) * 1000L) {
                finish(disconnected("等了 " + limitSeconds + " 秒还没有结果"));
                return;
            }

            new Thread(new Runnable() {
                public void run() {
                    final Devices.ConfirmState st =
                            RelayClient.confirm(addr, token, jobId, deviceName);
                    ui.post(new Runnable() {
                        public void run() {
                            if (stopped) return;

                            if (st.transientError()) {
                                // 这一轮没问到中继。不是结论，下轮继续。
                                // 但如果连着好几轮都问不到，说明是真断了，
                                // 这时候收尾要给"断在哪"，而不是"它没醒"。
                                consecutiveFails++;
                                if (consecutiveFails >= MAX_CONSECUTIVE_FAILS) {
                                    finish(disconnected(st.note));
                                    return;
                                }
                                Devices.ConfirmState shown = st;
                                if (lastKnown != null) {
                                    // 保留上一次已知的进度（比如"网卡已上线"），
                                    // 只把 note 换成当前的连接问题，
                                    // 免得界面上"网卡已上线"这条有效信息被冲掉。
                                    // 拷一份再改：直接改 lastKnown 会把"已知进度"本身改坏。
                                    shown = lastKnown.copy();
                                    shown.state = Devices.ConfirmState.RETRY;
                                    shown.note = st.note;
                                }
                                listener.onTick(shown);
                                ui.postDelayed(poll, POLL_MS);
                                return;
                            }

                            consecutiveFails = 0;
                            lastKnown = st;
                            listener.onTick(st);
                            if (st.done()) {
                                finish(st);
                            } else {
                                ui.postDelayed(poll, POLL_MS);
                            }
                        }
                    });
                }
            }).start();
        }
    };

    /**
     * 造一个"确认中断"的结论。
     *
     * 注意这里的措辞：它说的是"我没能问到中继"，不是"你的电脑没醒"。
     * 前者是 App 这一侧的问题，用户该去查 WiFi / 中继；后者是电脑侧的问题，
     * 要查 BIOS / 网线。把这两件事混成一句话，等于把这个功能最值钱的部分
     * （失败归因）毁掉。
     */
    private Devices.ConfirmState disconnected(String why) {
        Devices.ConfirmState st = new Devices.ConfirmState();
        st.state = Devices.ConfirmState.TIMEOUT;
        st.elapsed = (System.currentTimeMillis() - startedAt) / 1000.0;
        if (lastKnown != null) {
            // 已经拿到的进度要留着，它是判断"到底是哪一段断了"的依据。
            st.arpIn = lastKnown.arpIn;
            st.onlineIn = lastKnown.onlineIn;
            st.attempts = lastKnown.attempts;
            st.scanning = lastKnown.scanning;
        }
        String w = why != null && why.length() > 0 ? why : "连接失败";
        st.note = "和中断的联系断了（" + w + "）";
        return st;
    }

    private void finish(Devices.ConfirmState st) {
        stopped = true;
        ui.removeCallbacks(poll);
        listener.onDone(st);
    }
}
