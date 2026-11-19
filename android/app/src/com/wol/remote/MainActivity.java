package com.wol.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 远程开机。
 *
 * 三条路：
 *   方式一 · 直连公网 UDP —— 电脑侧已经做好端口映射或 DMZ
 *   方式二 · 备用机中继 —— 家里一台常开设备代为广播，不依赖公网映射
 *   自动发现 · 扫描本网段 —— 不知道备用机 IP 时，App 自己去网里找
 *
 * 作者：神仙道  QQ 1974147300
 *
 * 这个文件里不保留任何真实 IP、MAC、账号。首次打开是空的，
 * 由使用者自己填，填完存在 SharedPreferences 里。
 *
 * ---- 关于这个类的边界 ----
 * 它只做三件事：搭界面、把用户操作转给下层、把下层结果画出来。
 * 所有网络、协议、判定逻辑都在别的类里（见同目录 Wol / Lan / RelayClient /
 * SelfCheck / WakeWatcher / Prefs）。这样拆不是为了好看，是因为原来的
 * 1100 行单文件里，后台线程直接读 EditText、setBusy 之后没有 try-finally
 * 这类问题根本看不出来 —— 它们都藏在"业务逻辑和 View 互相引用"里。
 */
public class MainActivity extends Activity {

    private static final String K_SELECTED_RESTORE = "sel";

    private UiKit ui;
    private final Handler main = new Handler(Looper.getMainLooper());

    // 数据
    private List<Devices.Device> devices = new ArrayList<Devices.Device>();
    private int selected = 0;

    // 界面
    private EditText etRelay, etToken, etPublicIp, etPublicPort;
    private LinearLayout deviceBox, resultBox;
    private TextView tvVerdict, tvDetail, tvProgressTitle, tvProgressDetail;
    private ProgressBar progress;
    private LinearLayout progressBox;
    private Button btnWake, btnSelfCheck, btnDiscover, btnAdd, btnLoad, btnDirect;

    /** 正在跑的唤醒观察器。切后台/退出时要能停掉。 */
    private WakeWatcher watcher;
    /** 界面是否还活着。后台线程回主线程前必须查一下，否则会碰已销毁的 View。 */
    private boolean alive;

    // ============================================================
    //  生命周期
    // ============================================================

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        alive = true;
        ui = new UiKit(this);

        devices = Prefs.loadDevices(this);
        selected = Prefs.loadSelected(this, devices.size());
        if (b != null) {
            int s = b.getInt(K_SELECTED_RESTORE, selected);
            if (s >= 0 && s < devices.size()) selected = s;
        }

        setContentView(buildUi());
        fillInputs();
        renderDevices();

        // 配置里一台设备都没有时才显示引导，别在用户已经配好之后还挡着。
        if (devices.isEmpty()) {
            showResult(ui.str(R.string.first_run), R.color.text_sub);
        } else {
            hideResult();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(K_SELECTED_RESTORE, selected);
    }

    /**
     * 切后台就存配置。
     *
     * v2.1.x 只在点按钮时才 savePrefs()，用户填完中继地址直接按返回键退出，
     * 配置就丢了 —— 下次打开又是空的，用户会以为这 App 记不住东西。
     * 现在 onPause 也存一次，代价是几十微秒。
     */
    @Override
    protected void onPause() {
        super.onPause();
        persist();
        // 观察器继续跑没意义（用户已经看不见进度了），停掉省电。
        // 中继侧的确认任务不受影响，回来之后还能用 /confirm 查到结果。
        stopWatching();
    }

    @Override
    protected void onResume() {
        super.onResume();
        alive = true;
    }

    @Override
    protected void onDestroy() {
        alive = false;
        stopWatching();
        super.onDestroy();
    }

    // ============================================================
    //  界面搭建
    // ============================================================

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(ui.color(R.color.bg));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ui.dp(20), ui.dp(26), ui.dp(20), ui.dp(30));
        scroll.addView(root);

        // 标题
        TextView title = new TextView(this);
        title.setText(ui.str(R.string.title));
        title.setTextColor(ui.color(R.color.text));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText(ui.str(R.string.subtitle));
        sub.setTextColor(ui.color(R.color.text_sub));
        sub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setPadding(0, ui.dp(6), 0, 0);
        root.addView(sub);

        // 结果区
        resultBox = ui.card();
        LinearLayout.LayoutParams lpR = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpR.topMargin = ui.dp(14);
        resultBox.setVisibility(View.GONE);

        tvVerdict = new TextView(this);
        tvVerdict.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        tvVerdict.setLineSpacing(ui.dp(2), 1.15f);
        resultBox.addView(tvVerdict);

        tvDetail = new TextView(this);
        tvDetail.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        tvDetail.setTextColor(ui.color(R.color.text_sub));
        tvDetail.setLineSpacing(ui.dp(2), 1.2f);
        tvDetail.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvDetail.setPadding(0, ui.dp(10), 0, 0);
        resultBox.addView(tvDetail);

        root.addView(resultBox, lpR);

        // 进度区 —— 唤醒后在这里显示「已开机 · 23 秒」
        progressBox = ui.card();
        LinearLayout.LayoutParams lpP = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpP.topMargin = ui.dp(14);
        progressBox.setVisibility(View.GONE);

        tvProgressTitle = new TextView(this);
        tvProgressTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20);
        tvProgressTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        progressBox.addView(tvProgressTitle);

        tvProgressDetail = new TextView(this);
        tvProgressDetail.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        tvProgressDetail.setTextColor(ui.color(R.color.text_sub));
        tvProgressDetail.setLineSpacing(ui.dp(2), 1.2f);
        tvProgressDetail.setPadding(0, ui.dp(6), 0, 0);
        progressBox.addView(tvProgressDetail);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        LinearLayout.LayoutParams lpBar = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(4));
        lpBar.topMargin = ui.dp(12);
        progressBox.addView(progress, lpBar);

        root.addView(progressBox, lpP);

        // 自检
        btnSelfCheck = ui.button(root, ui.str(R.string.btn_selfcheck), R.color.amber);
        ui.pressable(btnSelfCheck);
        btnSelfCheck.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                doSelfCheck();
            }
        });

        // ---- 设备列表 ----
        root.addView(ui.sectionTitle(ui.str(R.string.sec_devices)));
        deviceBox = new LinearLayout(this);
        deviceBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(deviceBox);

        LinearLayout devRow = ui.row();
        LinearLayout.LayoutParams lpRow = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpRow.topMargin = ui.dp(6);
        root.addView(devRow, lpRow);

        btnAdd = ui.ghostButton(devRow, ui.str(R.string.btn_add_device));
        ui.equalWeight(btnAdd);
        btnAdd.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                addDeviceDialog();
            }
        });

        btnLoad = ui.ghostButton(devRow, ui.str(R.string.btn_load_devices));
        ui.equalWeight(btnLoad);
        btnLoad.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                loadDevicesFromRelay();
            }
        });

        // ---- 中继 ----
        root.addView(ui.sectionTitle(ui.str(R.string.sec_relay)));
        root.addView(ui.hint(ui.str(R.string.hint_relay)));
        etRelay = ui.field(root, ui.str(R.string.lbl_relay), "", InputType.TYPE_CLASS_TEXT);
        etToken = ui.field(root, ui.str(R.string.lbl_token), "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        btnDiscover = ui.ghostButton(root, ui.str(R.string.btn_discover));
        btnDiscover.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                doDiscover();
            }
        });

        btnWake = ui.button(root, ui.str(R.string.btn_wake), R.color.green);
        ui.pressable(btnWake);
        btnWake.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                doWake();
            }
        });

        // ---- 直连 ----
        root.addView(ui.sectionTitle(ui.str(R.string.sec_direct)));
        root.addView(ui.hint(ui.str(R.string.hint_direct)));
        etPublicIp = ui.field(root, ui.str(R.string.lbl_public_ip), "",
                InputType.TYPE_CLASS_TEXT);
        etPublicPort = ui.field(root, ui.str(R.string.lbl_public_port), Prefs.DEF_PORT,
                InputType.TYPE_CLASS_NUMBER);

        btnDirect = ui.button(root, ui.str(R.string.btn_send_direct), R.color.blue);
        ui.pressable(btnDirect);
        btnDirect.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                doDirect();
            }
        });

        // 页脚
        TextView foot = new TextView(this);
        foot.setText(ui.str(R.string.footer));
        foot.setTextColor(ui.color(R.color.text_dim));
        foot.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        foot.setGravity(android.view.Gravity.CENTER);
        foot.setPadding(0, ui.dp(34), 0, 0);
        root.addView(foot);

        return scroll;
    }

    private void fillInputs() {
        etRelay.setText(Prefs.loadRelay(this));
        etToken.setText(Prefs.loadToken(this));
        etPublicIp.setText(Prefs.loadPublicIp(this));
        etPublicPort.setText(Prefs.loadPublicPort(this));
    }

    /** 重画设备列表。设备数量变化、选中项变化时调用。 */
    private void renderDevices() {
        if (deviceBox == null) return;
        deviceBox.removeAllViews();

        if (devices.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(ui.str(R.string.hint_no_device));
            empty.setTextColor(ui.color(R.color.text_sub));
            empty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
            empty.setLineSpacing(ui.dp(2), 1.15f);
            empty.setPadding(ui.dp(2), ui.dp(4), ui.dp(2), ui.dp(8));
            deviceBox.addView(empty);
            return;
        }

        for (int i = 0; i < devices.size(); i++) {
            final int idx = i;
            Devices.Device d = devices.get(i);
            View row = ui.deviceRow(d.display(), Wol.mask(d.mac), i == selected,
                    new View.OnClickListener() {
                        public void onClick(View v) {
                            selected = idx;
                            Prefs.saveSelected(MainActivity.this, idx);
                            renderDevices();
                        }
                    });
            // 长按删除，避免多一个按钮占位置
            row.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    removeDeviceDialog(idx);
                    return true;
                }
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = ui.dp(8);
            deviceBox.addView(row, lp);
        }
    }

    // ============================================================
    //  设备管理
    // ============================================================

    private void addDeviceDialog() {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = ui.dp(20);
        box.setPadding(pad, ui.dp(8), pad, 0);

        final EditText etMac = ui.field(box, ui.str(R.string.lbl_mac), "",
                InputType.TYPE_CLASS_TEXT);
        final EditText etLabel = ui.field(box, ui.str(R.string.lbl_label), "",
                InputType.TYPE_CLASS_TEXT);
        final EditText etHost = ui.field(box, ui.str(R.string.lbl_host), "",
                InputType.TYPE_CLASS_TEXT);

        new AlertDialog.Builder(this)
                .setTitle(ui.str(R.string.btn_add_device))
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String mac = etMac.getText().toString().trim();
                        String label = etLabel.getText().toString().trim();
                        String host = etHost.getText().toString().trim();

                        if (!Wol.ok(mac) && host.length() == 0) {
                            toast("MAC 要填对，或者填一个固定 IP");
                            return;
                        }
                        Devices.Device dev = new Devices.Device();
                        dev.mac = Wol.norm(mac);
                        dev.label = label.length() > 0 ? label
                                : (Wol.ok(mac) ? "设备 " + dev.mac.substring(8) : host);
                        dev.relayName = label.length() > 0 ? label : "";
                        dev.host = host;
                        devices.add(dev);
                        selected = devices.size() - 1;
                        Prefs.saveDevices(MainActivity.this, devices);
                        Prefs.saveSelected(MainActivity.this, selected);
                        renderDevices();
                        hideResult();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void removeDeviceDialog(final int idx) {
        if (idx < 0 || idx >= devices.size()) return;
        final Devices.Device d = devices.get(idx);
        new AlertDialog.Builder(this)
                .setTitle("删掉「" + d.display() + "」？")
                .setMessage("只是从 App 里移除，中继那边的配置不受影响。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dlg, int w) {
                        devices.remove(idx);
                        if (selected >= devices.size()) {
                            selected = Math.max(0, devices.size() - 1);
                        }
                        Prefs.saveDevices(MainActivity.this, devices);
                        Prefs.saveSelected(MainActivity.this, selected);
                        renderDevices();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 从中继拉设备列表并填进 App。
     *
     * 这是多设备场景下最省事的一步：中继上叫 pc / nas，用户不用自己
     * 一个个抄 MAC，点一下就都有了。中继对未鉴权请求只给打码 MAC，
     * 所以拿不到完整值时保留占位（只存指纹信息），唤醒时仍然靠 name 指定，
     * 不会影响实际功能。
     */
    private void loadDevicesFromRelay() {
        final String[] relays = relayList();
        if (relays.length == 0) {
            showResult("先填中继地址，或者点「自动发现备用机」。", R.color.amber);
            return;
        }
        final String token = etToken.getText().toString().trim();
        persist();
        setBusy(true);
        showResult("正在从中继读取设备列表…", R.color.text_sub);

        runInBackground(new Runnable() {
            public void run() {
                final List<Devices.RemoteDevice> found = new ArrayList<Devices.RemoteDevice>();
                String lastErr = null;
                String usedRelay = null;

                for (String a : relays) {
                    String[] err = new String[1];
                    List<Devices.RemoteDevice> list = RelayClient.devices(a, token, err);
                    if (list.size() > 0) {
                        found.addAll(list);
                        usedRelay = a;
                        break;
                    }
                    if (err[0] != null) lastErr = err[0];
                }

                final String fErr = lastErr;
                final String fRelay = usedRelay;
                main.post(new Runnable() {
                    public void run() {
                        // 回到主线程先确认界面还在 —— 用户可能已经退出了
                        if (!alive) return;
                        setBusy(false);
                        if (found.isEmpty()) {
                            showResult("没读到设备列表\n\n"
                                    + (fErr != null ? fErr + "\n\n" : "")
                                    + "可能的原因：\n"
                                    + "· 中继版本低于 v2.2.0（那个版本才有 /devices）\n"
                                    + "· 中继的 config.json 里 devices 是空的\n"
                                    + "· 令牌没填对", R.color.amber);
                            return;
                        }
                        applyRemoteDevices(found, fRelay);
                    }
                });
            }
        });
    }

    /** 把中继上的设备合并进 App 的设备表。同名同 MAC 的不重复添加。 */
    private void applyRemoteDevices(List<Devices.RemoteDevice> remote, String relay) {
        int added = 0;
        for (Devices.RemoteDevice rd : remote) {
            // 用 name + 指纹 判断是否已经在列表里。
            // 中继返回的 mac 可能是打码的，不能直接拿来比。
            boolean exists = false;
            for (Devices.Device d : devices) {
                boolean sameName = d.relayName != null && d.relayName.equalsIgnoreCase(rd.name);
                boolean sameFp = rd.macFp != null && rd.macFp.length() > 0
                        && rd.macFp.equalsIgnoreCase(Wol.fingerprint(d.mac));
                if (sameName || sameFp) {
                    exists = true;
                    // 顺手补上完整 MAC（如果这次带了 token，中继会给明文）
                    if (Wol.ok(rd.mac) && !Wol.ok(d.mac)) d.mac = Wol.norm(rd.mac);
                    if (d.label == null || d.label.length() == 0) d.label = rd.name;
                    break;
                }
            }
            if (exists) continue;

            Devices.Device d = new Devices.Device();
            d.relayName = rd.name == null ? "" : rd.name;
            d.label = rd.name == null ? "" : rd.name;
            d.mac = Wol.ok(rd.mac) ? Wol.norm(rd.mac) : "";
            d.host = rd.host == null ? "" : rd.host;
            devices.add(d);
            added++;
        }
        Prefs.saveDevices(this, devices);
        renderDevices();
        hideResult();

        String msg = "从 " + RelayClient.hostOf(relay) + " 读到 " + remote.size() + " 台设备";
        if (added > 0) msg += "，新增 " + added + " 台";
        else msg += "，都已经在列表里了";
        toast(msg);

        // 有些中继不给完整 MAC（没带 token），这时候提醒一句，
        // 否则用户看到设备都加进来了，却不知道为什么有个别显示"设备 XXXX"没有 MAC。
        boolean missing = false;
        for (Devices.Device d : devices) if (!Wol.ok(d.mac)) missing = true;
        if (missing) {
            showResult("设备已经加进来了，但有的没拿到完整 MAC。\n\n"
                    + "中继对未鉴权的请求只返回打码 MAC（前 3 段 + 指纹）。"
                    + "这不影响唤醒 —— 唤醒靠设备名指定，中继自己知道 MAC。\n"
                    + "想让 App 里也显示完整 MAC，把令牌填上再读一次。", R.color.text_sub);
        }
    }

    // ============================================================
    //  唤醒（核心闭环）
    // ============================================================

    private void doWake() {
        final String[] relays = relayList();
        if (relays.length == 0) {
            showResult("先填中继地址，或者点「自动发现备用机」。", R.color.red);
            return;
        }
        final Devices.Device dev = currentDevice();
        if (dev == null) {
            showResult("还没有设备。先点「添加设备」填一个 MAC。", R.color.red);
            return;
        }
        if (!dev.usable()) {
            showResult("这台设备的 MAC 不完整。\n"
                    + "它可能是从没带令牌的中继读来的 —— 填上令牌再读一次，"
                    + "或者直接在这台设备上手填 MAC。", R.color.red);
            return;
        }

        final String token = etToken.getText().toString().trim();
        persist();
        setBusy(true);
        showProgress("正在联系备用机…", "", R.color.text_sub, true);

        runInBackground(new Runnable() {
            public void run() {
                Devices.WakeResult best = null;
                String bestRelay = null;
                StringBuilder failLog = new StringBuilder();
                boolean sawTokenError = false;

                for (String a : relays) {
                    Devices.WakeResult r = RelayClient.wake(a, token, dev.relayName, false);
                    if (r.kind == Devices.WakeResult.OK) {
                        best = r;
                        bestRelay = a;
                        break;
                    }
                    if (r.kind == Devices.WakeResult.ZERO_SENT) {
                        // 中继收下了但没发出去，比"连不上"更值得报出来，
                        // 但也继续试下一个中继 —— 也许另一个是好的
                        if (best == null) {
                            best = r;
                            bestRelay = a;
                        }
                        failLog.append(RelayClient.hostOf(a)).append("  ")
                                .append(r.shortMsg()).append("\n");
                        continue;
                    }
                    if (r.kind == Devices.WakeResult.TOKEN) sawTokenError = true;
                    failLog.append(RelayClient.hostOf(a)).append("  ")
                            .append(r.shortMsg()).append("\n");
                }

                final Devices.WakeResult fBest = best;
                final String fRelay = bestRelay;
                final String fFail = failLog.toString().trim();
                final boolean fTokenErr = sawTokenError;
                final String fToken = token;

                main.post(new Runnable() {
                    public void run() {
                        if (!alive) return;
                        setBusy(false);
                        handleWakeResult(fBest, fRelay, fFail, fTokenErr, fToken, dev);
                    }
                });
            }
        });
    }

    private void handleWakeResult(Devices.WakeResult r, String relay, String failLog,
                                  boolean tokenErr, String token, Devices.Device dev) {
        if (r == null) {
            if (tokenErr) {
                showProgress("中继连上了，但令牌不对", ""
                        + "令牌在中继的 config.json 里，抄到「访问令牌」那一栏。\n\n"
                        + failLog, R.color.red, false);
                return;
            }
            // 100.64.x.x 是 Tailscale 的段，提示唤起它重连隧道
            boolean viaTailscale = failLog.contains("100.");
            if (viaTailscale) wakeTailscale();
            showProgress("备用机连不上", failLog
                    + (viaTailscale ? "\n\n已尝试唤起 Tailscale，等 10 秒再点一次。" : "")
                    + "\n\n先确认备用机开着机、连着家里的 WiFi。"
                    + "\n不确定就点「一键自检」，它会扫一遍网段。", R.color.red, false);
            return;
        }

        if (r.kind == Devices.WakeResult.ZERO_SENT) {
            showProgress("中继收到了，但一个包都没发出去",
                    RelayClient.hostOf(relay) + "  发出 0/" + r.total + "\n\n"
                            + "中继自己广播不出去。上那台设备跑一遍：\n"
                            + "  wolrelay -doctor\n"
                            + "看「网卡与广播目标」那段对不对。", R.color.red, false);
            return;
        }

        // 发出去了，进入确认阶段 —— 这里是"发出去了"变成"开机了"的地方
        if (r.confirmId == null || r.confirmId.length() == 0) {
            // 老版本中继没有 /confirm
            showProgress("魔术包已发出", "这台中继版本较老，没法确认到底醒没醒。\n"
                    + "升级到 v2.2.0 之后就能看到「已开机 · N 秒」。", R.color.green, false);
            return;
        }
        startWatching(relay, token, r, dev);
    }

    /** 开始每秒轮询确认进度，并把结果画到进度区。 */
    private void startWatching(String relay, String token, Devices.WakeResult r,
                               Devices.Device dev) {
        stopWatching();

        int limit = r.confirmTimeout > 0 ? r.confirmTimeout : 90;
        showProgress("魔术包已发出，正在确认…",
                "盯着 " + RelayClient.hostOf(relay) + "，看它第几秒能连上 " + dev.display(),
                R.color.text_sub, true);

        watcher = new WakeWatcher(relay, token, r.confirmId, dev.relayName, limit,
                new WakeWatcher.Listener() {
                    public void onTick(Devices.ConfirmState st) {
                        if (!alive) return;
                        renderProgress(st, limit, false);
                    }

                    public void onDone(Devices.ConfirmState st) {
                        if (!alive) return;
                        watcher = null;
                        renderProgress(st, limit, true);
                    }
                });
        watcher.start();
    }

    private void renderProgress(Devices.ConfirmState st, int limit, boolean done) {
        if (progressBox == null) return;
        progressBox.setVisibility(View.VISIBLE);
        resultBox.setVisibility(View.GONE);

        tvProgressTitle.setText(st.headline(limit));
        tvProgressDetail.setText(st.detail());

        int c;
        if (st.online()) {
            c = R.color.green;
        } else if (done) {
            c = R.color.red;
        } else {
            c = R.color.text;
        }
        tvProgressTitle.setTextColor(ui.color(c));

        // 计时器：等待中把已用秒数带上，让用户知道它在动
        if (!done && !st.online()) {
            tvProgressTitle.setText(st.headline(limit) + "  " + Math.round(st.elapsed) + "s");
        }

        if (done) {
            progress.setVisibility(View.GONE);
            setBusy(false);
            if (st.online() && !st.already) {
                // 成功了给个振动级别的反馈：Toast 一句话
                toast("已开机 · " + Math.round(st.onlineIn) + " 秒");
            }
        } else {
            progress.setVisibility(View.VISIBLE);
            setBusy(true);
        }
    }

    private void stopWatching() {
        if (watcher != null) {
            watcher.stop();
            watcher = null;
        }
    }

    /** 用普通 Intent 把 Tailscale 拉起来，让它自己恢复隧道。不需要 root。 */
    private void wakeTailscale() {
        try {
            Intent it = getPackageManager().getLaunchIntentForPackage("com.tailscale.ipn");
            if (it != null) {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
            }
        } catch (Throwable e) {
            Diag.w("MainActivity.wakeTailscale", e);
        }
    }

    // ============================================================
    //  直连
    // ============================================================

    private void doDirect() {
        final Devices.Device dev = currentDevice();
        if (dev == null || !Wol.ok(dev.mac)) {
            showResult("先选一台有 MAC 的设备（或者添加一台）。", R.color.red);
            return;
        }
        final String ip = etPublicIp.getText().toString().trim();
        if (ip.length() == 0) {
            showResult("填一下公网 IP 或 DDNS 域名。", R.color.red);
            return;
        }
        final int port = parseInt(etPublicPort.getText().toString().trim(), 9);

        persist();
        setBusy(true);
        showResult("正在发送…", R.color.text_sub);

        runInBackground(new Runnable() {
            public void run() {
                // 只在"填的地址就在本机网段"时才补发定向广播。
                // v2.1.x 无条件往 255.255.255.255 补发一份，那是纯噪音：
                // 手机不在那个广播域里时包直接被内核丢掉，
                // 而结果里多一条"OK"会让用户以为有两条路都通。
                boolean sameNet = Lan.isLocalPeer(ip);
                final List<WolSender.Sent> results =
                        WolSender.send(ip, port, dev.mac, sameNet);

                main.post(new Runnable() {
                    public void run() {
                        if (!alive) return;
                        setBusy(false);
                        StringBuilder sb = new StringBuilder();
                        boolean any = false;
                        for (WolSender.Sent s : results) {
                            // 成功时 error 字段可能装着"只送出了 1/3 个包"这种提示，
                            // 不能因为 ok 就把它吞掉 —— 那正是排查丢包时唯一的信息。
                            String tail = s.error.length() == 0 ? "" : "  " + s.error;
                            sb.append(s.target).append(s.ok ? "  OK" : "  " + s.error)
                                    .append(s.ok ? tail : "").append("\n");
                            if (s.ok) any = true;
                        }
                        if (any) {
                            showResult("魔术包已发出\n\n" + sb.toString()
                                    + "\n电脑大约 10~20 秒起来。\n"
                                    + "直连方式没法确认到底醒没醒（那要电脑侧配合）。"
                                    + "想要「已开机 · N 秒」这种反馈，用上面的中继方式。",
                                    R.color.green);
                        } else {
                            showResult("没发出去\n\n" + sb.toString()
                                    + "\n通常是没联网、域名解析不出来，"
                                    + "或者路由器没做端口映射。", R.color.red);
                        }
                    }
                });
            }
        });
    }

    // ============================================================
    //  自检 / 发现
    // ============================================================

    private void doSelfCheck() {
        final Devices.Device dev = currentDevice();
        final String appMac = dev == null ? "" : dev.mac;
        final String token = etToken.getText().toString().trim();
        final String ip = etPublicIp.getText().toString().trim();
        final String[] relays = relayList();

        persist();
        setBusy(true);
        showResult(ui.str(R.string.sc_connecting), R.color.text_sub);

        runInBackground(new Runnable() {
            public void run() {
                final SelfCheck.Report rep =
                        SelfCheck.run(appMac, token, ip, relays);
                main.post(new Runnable() {
                    public void run() {
                        if (!alive) return;
                        setBusy(false);
                        showResult(rep.verdict + "\n\n" + rep.detail, rep.verdictColor);

                        // 扫到中继但用户没配，直接回填，省得自己抄 IP
                        if (!rep.foundRelays.isEmpty() && relayList().length == 0) {
                            StringBuilder all = new StringBuilder();
                            for (String a : rep.foundRelays) {
                                if (all.length() > 0) all.append(" ");
                                all.append(a);
                            }
                            etRelay.setText(all.toString());
                            persist();
                            toast("已把找到的中继填进方式二");
                        }
                    }
                });
            }
        });
    }

    private void doDiscover() {
        final String token = etToken.getText().toString().trim();
        setBusy(true);
        showResult("正在扫本机网段…\n\n每个地址都会试一次，大概十几秒", R.color.text_sub);

        runInBackground(new Runnable() {
            public void run() {
                final List<String> hits = new ArrayList<String>();
                List<Lan.Subnet> nets = Lan.localSubnets();
                if (nets.isEmpty()) {
                    main.post(new Runnable() {
                        public void run() {
                            if (!alive) return;
                            setBusy(false);
                            showResult("手机没拿到局域网地址\n先连上 WiFi 再试。", R.color.red);
                        }
                    });
                    return;
                }
                for (Lan.Subnet s : nets) {
                    for (String h : Lan.scan(s, token, null)) {
                        if (!hits.contains(h)) hits.add(h);
                    }
                }
                main.post(new Runnable() {
                    public void run() {
                        if (!alive) return;
                        setBusy(false);
                        if (hits.isEmpty()) {
                            showResult("本网段没找到中继\n\n"
                                    + "可能的原因：\n"
                                    + "· 备用机没连同一个 WiFi\n"
                                    + "· 中继服务没在跑\n"
                                    + "· 路由器开了 AP 隔离，设备之间互相看不见\n"
                                    + "· 中继端口不是 " + Lan.RELAY_PORT, R.color.red);
                            return;
                        }
                        StringBuilder all = new StringBuilder();
                        for (String a : hits) {
                            if (all.length() > 0) all.append(" ");
                            all.append(a);
                        }
                        etRelay.setText(all.toString());
                        persist();
                        showResult("找到 " + hits.size() + " 个中继，已填进方式二\n\n"
                                + all.toString().replace(" ", "\n"), R.color.green);
                    }
                });
            }
        });
    }

    // ============================================================
    //  状态
    // ============================================================

    /**
     * 统一切换"忙/闲"。
     *
     * v2.1.x 的写法是 setBusy(true) 之后在后台线程末尾 setBusy(false)，
     * 中间没有任何异常保护 —— 线程里一旦抛异常，四个按钮就永久灰掉，
     * 用户只能杀进程重开。现在所有后台任务都在 try/finally 里收尾
     * （见 setBusy 的调用点和各 runnable 的 finally）。
     */
    private void setBusy(boolean busy) {
        if (btnWake != null) btnWake.setEnabled(!busy);
        if (btnSelfCheck != null) btnSelfCheck.setEnabled(!busy);
        if (btnDiscover != null) btnDiscover.setEnabled(!busy);
        if (btnDirect != null) btnDirect.setEnabled(!busy);
        if (btnAdd != null) btnAdd.setEnabled(!busy);
        if (btnLoad != null) btnLoad.setEnabled(!busy);
        if (btnWake != null) btnWake.setAlpha(busy ? 0.5f : 1f);
        if (btnSelfCheck != null) btnSelfCheck.setAlpha(busy ? 0.5f : 1f);
        if (btnDirect != null) btnDirect.setAlpha(busy ? 0.5f : 1f);
    }

    private void showResult(String msg, int colorRes) {
        if (resultBox == null) return;
        progressBox.setVisibility(View.GONE);
        resultBox.setVisibility(View.VISIBLE);
        tvVerdict.setText(msg);
        tvVerdict.setTextColor(ui.color(colorRes));
        // 自检报告很长，第二段（明细）只在报告里出现。
        // 判断方式：第一行以"结论"开头就把剩下的拆到明细区
        int nl = msg.indexOf("\n\n");
        if (msg.startsWith("结论") && nl > 0) {
            tvVerdict.setText(msg.substring(0, nl));
            tvVerdict.setTextColor(ui.color(colorRes));
            tvDetail.setText(msg.substring(nl + 2));
            tvDetail.setVisibility(View.VISIBLE);
        } else {
            tvDetail.setVisibility(View.GONE);
        }
    }

    private void showProgress(String title, String detail, int colorRes, boolean busyBar) {
        if (progressBox == null) return;
        resultBox.setVisibility(View.GONE);
        progressBox.setVisibility(View.VISIBLE);
        tvProgressTitle.setText(title);
        tvProgressTitle.setTextColor(ui.color(colorRes));
        tvProgressDetail.setText(detail == null ? "" : detail);
        progress.setIndeterminate(true);
        progress.setVisibility(busyBar ? View.VISIBLE : View.GONE);
    }

    private void hideResult() {
        if (resultBox != null) resultBox.setVisibility(View.GONE);
        if (progressBox != null) progressBox.setVisibility(View.GONE);
    }

    /**
     * 后台任务包装器：保证任何异常都不会把界面永久卡在"忙"状态。
     *
     * 这是对 v2.1.x 一个真 bug 的修法。原来的写法是
     *
     *     setBusy(true);
     *     new Thread(() -> { ...干活...; ui.post(() -> setBusy(false)); }).start();
     *
     * 中间没有任何保护。后台线程只要抛一次异常（DNS 解析出奇怪的值、
     * JSON 里冒出个意料之外的类型），setBusy(false) 就永远不会执行，
     * 六个按钮全部灰掉，用户唯一的出路是杀掉 App 重开 ——
     * 而且他自己完全不知道发生了什么。
     *
     * 现在所有后台任务都从这里走：正常路径自己 setBusy(false)，
     * 出异常由这里兜底并把原因显示出来。两种路径都不会漏。
     */
    private void runInBackground(final Runnable work) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    work.run();
                } catch (final Throwable e) {
                    Diag.w("MainActivity.bg", e);
                    main.post(new Runnable() {
                        public void run() {
                            if (!alive) return;
                            setBusy(false);
                            showResult("出错了：" + Diag.describe(e)
                                    + "\n\n这不该发生。把这句话发到 issue 里就能定位。",
                                    R.color.red);
                        }
                    });
                }
            }
        }).start();
    }

    private void toast(String msg) {
        if (!alive) return;
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ============================================================
    //  输入
    // ============================================================

    /** 当前选中的设备。没有设备时返回 null。 */
    private Devices.Device currentDevice() {
        if (devices.isEmpty()) return null;
        if (selected < 0 || selected >= devices.size()) selected = 0;
        return devices.get(selected);
    }

    /** 中继地址列表。空格、逗号、分号都当分隔符。 */
    private String[] relayList() {
        String raw = etRelay == null ? "" : etRelay.getText().toString().trim();
        if (raw.length() == 0) return new String[0];
        List<String> out = new ArrayList<String>();
        for (String t : raw.split("[\\s,;]+")) {
            String s = t.trim();
            if (s.length() > 0) out.add(s);
        }
        return out.toArray(new String[0]);
    }

    /**
     * 存配置。
     *
     * 注意这里读控件是安全的 —— 它只在主线程被调用（onPause、按钮回调）。
     * 后台线程要用任何输入，必须在启动线程前就读出来传进去，这是硬规矩：
     * v2.1.x 有几处后台线程直接 getText()，Activity 一销毁就是空指针。
     */
    private void persist() {
        if (etRelay == null) return;
        Prefs.saveRelay(this, etRelay.getText().toString().trim());
        Prefs.saveToken(this, etToken.getText().toString().trim());
        Prefs.savePublic(this,
                etPublicIp.getText().toString().trim(),
                etPublicPort.getText().toString().trim());
        Prefs.saveDevices(this, devices);
        Prefs.saveSelected(this, selected);
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable e) {
            return def;
        }
    }
}
