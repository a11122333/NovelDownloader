package com.niganma.noveldown;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** MD3 风格的复用组件：顶部应用栏、按钮、卡片、列表项。 */
public final class UiKit {

    /** 顶部应用栏高度（MD3 small top app bar 为 64dp）。 */
    private static final float BAR_H = 60f;

    private UiKit() {}

    /** 应用 MD3 顶部栏配色到状态栏，并让状态栏图标变深色。 */
    public static void applyStatusBar(Activity a) {
        a.getWindow().setStatusBarColor(UiUtil.TOP_BAR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            a.getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    /** MD3 顶部应用栏（primary-container 底 + 深色标题）；rightText 非空时右侧显示文字按钮。 */
    public static LinearLayout header(Activity a, String title,
                                      String rightText, Runnable onRight) {
        LinearLayout bar = bar(a);
        bar.setPadding(UiUtil.dp(a, 20), 0, UiUtil.dp(a, 8), 0);

        TextView t = UiUtil.text(a, title, 21, UiUtil.ON_PRIMARY_CONTAINER);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(t);

        if (rightText != null) {
            TextView r = textButton(a, rightText, onRight);
            bar.addView(r);
        }
        return bar;
    }

    /** 带返回箭头的 MD3 顶部应用栏。 */
    public static LinearLayout backHeader(Activity a, String title, Runnable onBack) {
        LinearLayout bar = bar(a);
        bar.setPadding(UiUtil.dp(a, 8), 0, UiUtil.dp(a, 16), 0);

        TextView back = UiUtil.text(a, "‹", 30, UiUtil.ON_PRIMARY_CONTAINER);
        back.setGravity(Gravity.CENTER);
        back.setPadding(UiUtil.dp(a, 12), 0, UiUtil.dp(a, 8), UiUtil.dp(a, 4));
        back.setBackground(UiUtil.ripple(UiUtil.ON_PRIMARY_CONTAINER,
                UiUtil.round(android.graphics.Color.TRANSPARENT, 20, a), a));
        back.setOnClickListener(v -> onBack.run());
        bar.addView(back);

        TextView t = UiUtil.text(a, title, 19, UiUtil.ON_PRIMARY_CONTAINER);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(t);
        return bar;
    }

    private static LinearLayout bar(Activity a) {
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(UiUtil.TOP_BAR);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiUtil.dp(a, BAR_H)));
        return bar;
    }

    /** MD3 文字按钮（无底、主色文字、涟漪）。 */
    public static TextView textButton(Activity a, String text, Runnable onClick) {
        TextView b = UiUtil.text(a, text, 14, UiUtil.PRIMARY);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        int h = UiUtil.dp(a, 9);
        int w = UiUtil.dp(a, 14);
        b.setPadding(w, h, w, h);
        b.setBackground(UiUtil.ripple(UiUtil.PRIMARY, UiUtil.round(UiUtil.TOP_BAR, 20, a), a));
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    /** MD3 filled 按钮：主色底、白字、胶囊形。 */
    public static TextView button(Activity a, String text, Runnable onClick) {
        TextView b = UiUtil.text(a, text, 15, UiUtil.ON_PRIMARY);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(UiUtil.ripple(UiUtil.ON_PRIMARY,
                UiUtil.round(UiUtil.PRIMARY, 24, a), a));
        int h = UiUtil.dp(a, 14);
        int w = UiUtil.dp(a, 22);
        b.setPadding(w, h, w, h);
        b.setElevation(UiUtil.dp(a, 1));
        b.setOnClickListener(x -> onClick.run());
        return b;
    }

    /** MD3 tonal 按钮：次级容器底、深色字、胶囊形。 */
    public static TextView tonalButton(Activity a, String text, Runnable onClick) {
        TextView b = UiUtil.text(a, text, 14, UiUtil.ON_SECONDARY_CONTAINER);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setBackground(UiUtil.ripple(UiUtil.ON_SECONDARY_CONTAINER,
                UiUtil.round(UiUtil.SECONDARY_CONTAINER, 16, a), a));
        int h = UiUtil.dp(a, 13);
        int w = UiUtil.dp(a, 16);
        b.setPadding(w, h, w, h);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    /** MD3 卡片容器：surface-container 底、圆角 20dp、轻微高度。 */
    public static LinearLayout card(Activity a) {
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiUtil.round(UiUtil.SURFACE_CONTAINER, 20, a));
        card.setElevation(UiUtil.dp(a, 1));
        int p = UiUtil.dp(a, 16);
        card.setPadding(p, p, p, p);
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    /** MD3 列表项 / 可点行：surface-container 底、圆角 16dp、涟漪。 */
    public static TextView listRow(Activity a, String text, Runnable onClick) {
        TextView b = UiUtil.text(a, text, 15, UiUtil.ON_SURFACE);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setBackground(UiUtil.ripple(UiUtil.ON_SURFACE,
                UiUtil.round(UiUtil.SURFACE_CONTAINER_HIGH, 16, a), a));
        int h = UiUtil.dp(a, 15);
        int w = UiUtil.dp(a, 16);
        b.setPadding(w, h, w, h);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    /** 分组标题（MD3 title-small）。 */
    public static TextView sectionTitle(Activity a, String text) {
        TextView t = UiUtil.text(a, text, 16, UiUtil.ON_SURFACE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** 细分隔线（outline-variant）。 */
    public static View divider(Activity a) {
        View v = new View(a);
        v.setBackgroundColor(UiUtil.OUTLINE_VARIANT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, UiUtil.dp(a, 0.6f)));
        v.setLayoutParams(lp);
        return v;
    }

    /** MD3 文本输入框：surface-container-high 底、圆角 12dp、无下划线。 */
    public static android.widget.EditText textField(Activity a, String hint, int inputType) {
        android.widget.EditText e = new android.widget.EditText(a);
        e.setHint(hint);
        e.setTextSize(15);
        e.setTextColor(UiUtil.ON_SURFACE);
        e.setHintTextColor(UiUtil.ON_SURFACE_VARIANT);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setBackground(UiUtil.round(UiUtil.SURFACE_CONTAINER_HIGH, 12, a));
        int h = UiUtil.dp(a, 12);
        int w = UiUtil.dp(a, 14);
        e.setPadding(w, h, w, h);
        return e;
    }

    /** 表单字段标签（MD3 body-small）。 */
    public static TextView fieldLabel(Activity a, String text) {
        TextView t = UiUtil.text(a, text, 12, UiUtil.ON_SURFACE_VARIANT);
        t.setPadding(0, UiUtil.dp(a, 12), 0, UiUtil.dp(a, 5));
        return t;
    }

    public static void setEnabledText(TextView b, boolean enabled) {
        b.setEnabled(enabled);
        b.setAlpha(enabled ? 1f : 0.4f);
    }

    public static View spacer(Activity a, float dp) {
        View v = new View(a);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiUtil.dp(a, dp)));
        return v;
    }
}