package com.wol.remote;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 界面构造工具。这个 App 不用布局 XML，界面全是代码搭的 ——
 * 一个 Activity 的界面用 XML 反而要在两处来回看，而且这套控件
 * （圆角卡片、渐变按钮）没有现成的样式可用。
 *
 * 抽出来的直接收益：原来 dp()、sectionTitle()、addField()、addButton()
 * 和业务逻辑混在一个类里，改一下按钮高度要在一个 1100 行的文件里搜。
 *
 * 作者：神仙道  QQ 1974147300
 */
final class UiKit {

    private final Context ctx;
    private final float density;

    UiKit(Context c) {
        ctx = c;
        density = c.getResources().getDisplayMetrics().density;
    }

    int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    /**
     * 取颜色。getColor(int) 从 API 23 起弃用，但 getColor(int, Theme) 在
     * API 21/22 上不存在 —— 直接用后者会在 minSdk=21 的旧机上
     * NoSuchMethodError 崩掉。所以按版本分两路。
     */
    @SuppressWarnings("deprecation")
    int color(int resId) {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            return ctx.getResources().getColor(resId, ctx.getTheme());
        }
        return ctx.getResources().getColor(resId);
    }

    String str(int resId) {
        return ctx.getString(resId);
    }

    /** 分区标题，字重加粗，上方留白比下方多，让视觉上它是"下一块的开始"。 */
    TextView sectionTitle(String t) {
        TextView tv = new TextView(ctx);
        tv.setText(t);
        tv.setTextColor(color(R.color.text));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(0, dp(26), 0, dp(8));
        return tv;
    }

    /** 说明文字。行距 1.15 倍，因为这些话通常是两三行长句，行距太挤不好读。 */
    TextView hint(String t) {
        TextView tv = new TextView(ctx);
        tv.setText(t);
        tv.setTextColor(color(R.color.text_sub));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setLineSpacing(dp(2), 1.15f);
        tv.setPadding(0, 0, 0, dp(10));
        return tv;
    }

    /** 输入框：圆角卡片 + 细描边。 */
    EditText field(LinearLayout parent, String label, String def, int inputType) {
        TextView lb = new TextView(ctx);
        lb.setText(label);
        lb.setTextColor(color(R.color.text_sub));
        lb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        lb.setPadding(dp(2), dp(8), 0, dp(6));
        parent.addView(lb);

        EditText et = new EditText(ctx);
        et.setText(def == null ? "" : def);
        et.setHint(label);
        et.setInputType(inputType);
        et.setTextColor(color(R.color.text));
        et.setHintTextColor(color(R.color.text_dim));
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        et.setSingleLine(true);
        et.setPadding(dp(14), dp(12), dp(14), dp(12));

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color(R.color.card));
        gd.setCornerRadius(dp(10));
        gd.setStroke(dp(1), color(R.color.line));
        et.setBackground(gd);

        parent.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return et;
    }

    /** 主按钮：左右渐变 + 大圆角。高度固定 56dp，是拇指舒服点按的下限。 */
    Button button(LinearLayout parent, String text, int bgRes) {
        int c = color(bgRes);
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextColor(color(R.color.white));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setAllCaps(false);
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{c, darken(c, 0.72f)});
        gd.setCornerRadius(dp(14));
        b.setBackground(gd);
        b.setPadding(dp(8), 0, dp(8), 0);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        lp.topMargin = dp(14);
        parent.addView(b, lp);
        return b;
    }

    /** 次要按钮：只有描边，不抢主按钮的注意力。 */
    Button ghostButton(LinearLayout parent, String text) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextColor(color(R.color.text));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setAllCaps(false);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color(R.color.card));
        gd.setCornerRadius(dp(12));
        gd.setStroke(dp(1), color(R.color.line));
        b.setBackground(gd);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        lp.topMargin = dp(10);
        parent.addView(b, lp);
        return b;
    }

    /** 圆角卡片容器，用来装结果、设备列表这类会变的内容。 */
    LinearLayout card() {
        LinearLayout ll = new LinearLayout(ctx);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color(R.color.card));
        gd.setCornerRadius(dp(12));
        ll.setBackground(gd);
        return ll;
    }

    /** 一行设备：左边名字，右边打码 MAC；选中时描边高亮。 */
    View deviceRow(String label, String macMasked, boolean selected, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(13), dp(14), dp(13));

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(selected ? color(R.color.card2) : color(R.color.card));
        gd.setCornerRadius(dp(10));
        gd.setStroke(dp(selected ? 2 : 1),
                selected ? color(R.color.blue) : color(R.color.line));
        row.setBackground(gd);

        TextView name = new TextView(ctx);
        name.setText(label);
        name.setTextColor(color(R.color.text));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        if (selected) name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView mac = new TextView(ctx);
        mac.setText(macMasked);
        mac.setTextColor(color(R.color.text_sub));
        mac.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mac.setTypeface(android.graphics.Typeface.MONOSPACE);
        row.addView(mac);

        if (onClick != null) {
            row.setOnClickListener(onClick);
            row.setClickable(true);
        }
        return row;
    }

    /**
     * 给可点元素加按下反馈。
     *
     * 这些 View 用的是 GradientDrawable，一设 background 就没有系统默认的
     * 水波纹和按下变暗了。用户点了没反应会以为卡住，尤其唤醒要等好几秒。
     */
    void pressable(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View view, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        view.setAlpha(0.72f);
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        view.setAlpha(1f);
                        break;
                }
                // 返回 false 让 click 监听照常收到事件
                return false;
            }
        });
    }

    /** 一个横向排列、等宽的按钮行。 */
    LinearLayout row() {
        LinearLayout ll = new LinearLayout(ctx);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        return ll;
    }

    /** 把行内按钮设成等宽。 */
    void equalWeight(Button b) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
        lp.width = 0;
        lp.weight = 1f;
        if (lp.leftMargin == 0 && lp.rightMargin == 0) {
            lp.leftMargin = dp(5);
            lp.rightMargin = dp(5);
        }
        b.setLayoutParams(lp);
    }

    static int darken(int c, float f) {
        int r = (int) (Color.red(c) * f);
        int g = (int) (Color.green(c) * f);
        int b = (int) (Color.blue(c) * f);
        return Color.rgb(r, g, b);
    }

    /** 半透明版本，用于进度条这类叠加元素。 */
    static int withAlpha(int c, int alpha) {
        return Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c));
    }
}
