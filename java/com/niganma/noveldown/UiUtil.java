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
 * Material Design 3 设计系统：颜色、字体、形状与状态层。
 *
 * <p>配色不是手挑的，而是以品牌蓝 <b>#4C6FFF</b> 为种子色，经 HCT
 * （CAM16 色相/彩度 + L* 明度）色空间推导出各 tonal palette 后，按 MD3
 * 规范映射到 color roles。surface 系列因此都带有轻微的种子色调，而不是纯灰。
 * 浅色主题的 tone 对应：primary=40、primary-container=90、on-surface=10、
 * surface=98、surface-container=94、surface-container-high=92、
 * outline=50、outline-variant=80、on-surface-variant=30。</p>
 */
public final class UiUtil {

    // ================= MD3 颜色角色（浅色主题） =================

    /** 主色（primary，tone 40）。 */
    public static final int PRIMARY = Color.parseColor("#2E50D1");
    public static final int ON_PRIMARY = Color.parseColor("#FEFDFF");
    public static final int PRIMARY_CONTAINER = Color.parseColor("#D8E0FF");
    public static final int ON_PRIMARY_CONTAINER = Color.parseColor("#000071");

    public static final int SECONDARY = Color.parseColor("#565C7D");
    public static final int ON_SECONDARY = Color.parseColor("#FEFDFF");
    public static final int SECONDARY_CONTAINER = Color.parseColor("#CFD8FF");
    public static final int ON_SECONDARY_CONTAINER = Color.parseColor("#131936");

    public static final int TERTIARY = Color.parseColor("#705569");
    public static final int ON_TERTIARY = Color.parseColor("#FFFDFF");
    public static final int TERTIARY_CONTAINER = Color.parseColor("#F6D9EC");
    public static final int ON_TERTIARY_CONTAINER = Color.parseColor("#291325");

    public static final int ERROR = Color.parseColor("#BA1A1A");
    public static final int ON_ERROR = Color.parseColor("#FFFFFF");
    public static final int ERROR_CONTAINER = Color.parseColor("#FFDAD6");
    public static final int ON_ERROR_CONTAINER = Color.parseColor("#410002");

    // ---- surface 层级（tone 98 / 96 / 94 / 92 / 90） ----
    /** 页面底色（surface，tone 98）。 */
    public static final int SURFACE = Color.parseColor("#FBF8FD");
    public static final int ON_SURFACE = Color.parseColor("#1B1B1F");
    /** surface-variant（tone 90）。 */
    public static final int SURFACE_VARIANT = Color.parseColor("#E2E1EC");
    public static final int ON_SURFACE_VARIANT = Color.parseColor("#45464F");

    /** surface-container-lowest（tone 100）：列表卡片。 */
    public static final int SURFACE_CONTAINER_LOWEST = Color.parseColor("#FFFEFF");
    /** surface-container-low（tone 96）。 */
    public static final int SURFACE_CONTAINER_LOW = Color.parseColor("#F5F2F7");
    /** surface-container（tone 94）：导航栏。 */
    public static final int SURFACE_CONTAINER = Color.parseColor("#EFEDF1");
    /** surface-container-high（tone 92）：搜索栏等强调容器。 */
    public static final int SURFACE_CONTAINER_HIGH = Color.parseColor("#EAE7EB");
    /** surface-container-highest（tone 90）。 */
    public static final int SURFACE_CONTAINER_HIGHEST = Color.parseColor("#E4E1E6");

    public static final int OUTLINE = Color.parseColor("#767680");
    public static final int OUTLINE_VARIANT = Color.parseColor("#C6C5D0");

    public static final int INVERSE_SURFACE = Color.parseColor("#303034");
    public static final int INVERSE_ON_SURFACE = Color.parseColor("#F2EFF4");
    public static final int INVERSE_PRIMARY = Color.parseColor("#B5C2FF");

    // ================= 状态层不透明度 =================
    // MD3 规定：hover 8% / focus 10% / pressed 10% / dragged 16%

    public static final float STATE_PRESSED = 0.10f;
    public static final float STATE_HOVER = 0.08f;

    // ================= 形状（shape scale） =================

    public static final int SHAPE_NONE = 0;
    public static final int SHAPE_EXTRA_SMALL = 4;
    public static final int SHAPE_SMALL = 8;
    public static final int SHAPE_MEDIUM = 12;
    public static final int SHAPE_LARGE = 16;
    public static final int SHAPE_EXTRA_LARGE = 28;
    /** 全圆角（胶囊 / 圆形图标按钮）。 */
    public static final int SHAPE_FULL = 999;

    // ================= 字体（type scale，单位 sp） =================

    public static final float TYPE_HEADLINE_SMALL = 24;
    public static final float TYPE_TITLE_LARGE = 22;
    public static final float TYPE_TITLE_MEDIUM = 16;
    public static final float TYPE_TITLE_SMALL = 14;
    public static final float TYPE_BODY_LARGE = 16;
    public static final float TYPE_BODY_MEDIUM = 14;
    public static final float TYPE_BODY_SMALL = 12;
    public static final float TYPE_LABEL_LARGE = 14;
    public static final float TYPE_LABEL_MEDIUM = 12;
    public static final float TYPE_LABEL_SMALL = 11;

    private UiUtil() {}

    // ================= 尺寸 =================

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

    // ================= 图形 =================

    public static GradientDrawable round(int color, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp >= SHAPE_FULL
                ? dp(c, SHAPE_FULL / 2f) : dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable roundStroke(int color, float radiusDp, int strokeColor,
                                               float strokeDp, Context c) {
        GradientDrawable d = round(color, radiusDp, c);
        d.setStroke(dp(c, strokeDp), strokeColor);
        return d;
    }

    /**
     * 圆角矩形 + MD3 状态层涟漪，用于按钮、列表项、芯片等可点元素。
     *
     * @param contentColor 状态层着色来源（通常是容器上的前景色）
     */
    public static Drawable ripple(int contentColor, GradientDrawable shape, Context c) {
        return ripple(contentColor, shape, STATE_PRESSED, c);
    }

    /** 指定状态层不透明度的涟漪。 */
    public static Drawable ripple(int contentColor, GradientDrawable shape,
                                  float alpha, Context c) {
        int r = Color.argb((int) (alpha * 255),
                Color.red(contentColor), Color.green(contentColor), Color.blue(contentColor));
        return new RippleDrawable(ColorStateList.valueOf(r), shape, null);
    }

    /** 圆形状态层：满足 MD3「状态层只画在形状内」的要求。 */
    public static Drawable circleRipple(int contentColor, Context c) {
        return circleRipple(contentColor, STATE_PRESSED, c);
    }

    public static Drawable circleRipple(int contentColor, float alpha, Context c) {
        int r = Color.argb((int) (alpha * 255),
                Color.red(contentColor), Color.green(contentColor), Color.blue(contentColor));
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setColor(Color.TRANSPARENT);
        return new RippleDrawable(ColorStateList.valueOf(r), oval, oval);
    }

    /**
     * 指定遮罩的涟漪：状态层只画在 {@code mask} 覆盖的范围内。
     *
     * <p>底部导航栏用它把涟漪限制在图标胶囊区域——整条导航项都铺满涟漪不像 MD3。</p>
     */
    public static Drawable rippleMasked(int contentColor, android.graphics.drawable.Drawable mask,
                                        Context c) {
        int r = Color.argb((int) (STATE_PRESSED * 255),
                Color.red(contentColor), Color.green(contentColor), Color.blue(contentColor));
        return new RippleDrawable(ColorStateList.valueOf(r), null, mask);
    }

    public static int rippleColorFor(int bg) {
        return isLight(bg) ? ON_SURFACE : ON_PRIMARY;
    }

    private static boolean isLight(int color) {
        double lum = 0.299 * Color.red(color) + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color);
        return lum > 160;
    }

    /** 水平细分割线（outline-variant）。 */
    public static View hairline(Context c, int color) {
        View v = new View(c);
        v.setBackgroundColor(color);
        return v;
    }

    // ================= 文本 =================

    public static TextView text(Context c, String s, float sizeSp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        return t;
    }

    /**
     * 按 MD3 type scale 创建文本。
     *
     * @param emphasize true 时使用 medium/bold 字重（用于 title、label 与强调正文）
     */
    public static TextView styled(Context c, String s, float sizeSp, int color,
                                  boolean emphasize) {
        TextView t = text(c, s, sizeSp, color);
        if (emphasize) {
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    /**
     * 正文行高（MD3：body-large 24sp、body-medium 20sp、body-small 16sp）。
     */
    public static float lineHeightFor(float sizeSp) {
        if (sizeSp >= 16) {
            return 24;
        }
        if (sizeSp >= 14) {
            return 20;
        }
        return 16;
    }
}
