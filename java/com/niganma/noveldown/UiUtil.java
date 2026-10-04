package com.niganma.noveldown;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

/**
 * Material Design 3（MD3）风格的配色与基础图形工具。
 *
 * 采用 MD3 浅色主题色角色（color roles），以品牌蓝为种子色：
 *  primary / on-primary / primary-container / surface / surface-container /
 *  on-surface-variant / outline / outline-variant 等。
 */
public final class UiUtil {

    // ---------- MD3 颜色角色（浅色主题） ----------
    /** 主色（品牌蓝）。 */
    public static final int PRIMARY = Color.parseColor("#4C6FFF");
    public static final int ON_PRIMARY = Color.parseColor("#FFFFFF");
    /** 主色容器（浅蓝，用于顶部栏、选中态）。 */
    public static final int PRIMARY_CONTAINER = Color.parseColor("#DCE1FF");
    public static final int ON_PRIMARY_CONTAINER = Color.parseColor("#001452");

    /** 次级容器（用于 tonal 按钮 / 未强调的选中态）。 */
    public static final int SECONDARY_CONTAINER = Color.parseColor("#E0E1F9");
    public static final int ON_SECONDARY_CONTAINER = Color.parseColor("#171B2C");

    /** 背景 surface 与卡片 surface-container。 */
    public static final int SURFACE = Color.parseColor("#F4F4FB");
    public static final int ON_SURFACE = Color.parseColor("#1B1B21");
    public static final int SURFACE_CONTAINER = Color.parseColor("#FFFFFF");
    public static final int SURFACE_CONTAINER_HIGH = Color.parseColor("#E7E8F2");
    /** 次要文字 / 未选中内容。 */
    public static final int ON_SURFACE_VARIANT = Color.parseColor("#5A5B66");

    /** 描边色。 */
    public static final int OUTLINE = Color.parseColor("#767780");
    public static final int OUTLINE_VARIANT = Color.parseColor("#C7C8D2");

    public static final int ERROR = Color.parseColor("#BA1A1A");
    public static final int ON_ERROR = Color.parseColor("#FFFFFF");

    /** 状态栏 / 顶部栏底色。 */
    public static final int TOP_BAR = PRIMARY_CONTAINER;

    private UiUtil() {}

    // ---------- 尺寸 ----------

    public static int dp(View v, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                v.getResources().getDisplayMetrics());
    }

    public static int dp(Context c, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                c.getResources().getDisplayMetrics());
    }

    public static float sp(Context c, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                c.getResources().getDisplayMetrics());
    }

    // ---------- 图形 ----------

    public static GradientDrawable round(int color, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable roundStroke(int color, float radiusDp, int strokeColor,
                                               float strokeDp, Context c) {
        GradientDrawable d = round(color, radiusDp, c);
        d.setStroke(dp(c, strokeDp), strokeColor);
        return d;
    }

    /** 圆角矩形 + MD3 涟漪按压反馈，用于按钮、列表项、芯片等可点元素。 */
    public static Drawable ripple(int contentColor, GradientDrawable shape, Context c) {
        int r = Color.argb(38, Color.red(contentColor), Color.green(contentColor),
                Color.blue(contentColor));
        return new RippleDrawable(ColorStateList.valueOf(r), shape, null);
    }

    public static int rippleColorFor(int bg) {
        return isLight(bg) ? ON_SURFACE : ON_PRIMARY;
    }

    private static boolean isLight(int color) {
        double lum = 0.299 * Color.red(color) + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color);
        return lum > 160;
    }

    // ---------- 文本 ----------

    public static TextView text(Context c, String s, float sizeSp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        return t;
    }
}