package com.niganma.noveldown;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * MD3 组件库：按 Material Design 3 规格实现顶部应用栏、按钮、卡片、列表项、
 * 搜索栏、设置行与底部导航栏。
 *
 * <p>关键规格（均取自 MD3 组件规范）：small top app bar 高 64dp、标题
 * title-large 22sp；filled / tonal / outlined 按钮高 40dp、label-large
 * 14sp、全圆角；卡片用 surface-container-lowest、圆角 12dp、无阴影；
 * 底部导航栏高 80dp，图标容器 32dp（选中时胶囊 64×32）、label-medium
 * 12sp；列表项最小高 56dp。</p>
 */
public final class UiKit {

    /** 顶部应用栏高度（MD3 small top app bar 规范是 64dp，这里收紧到 56dp 更紧凑）。 */
    private static final float APP_BAR_H = 56f;
    /** 底部导航栏高度（MD3 navigation bar = 80dp）。 */
    private static final float NAV_BAR_H = 80f;
    /** 导航项图标容器（32dp 图标 / 64dp 选中胶囊）。 */
    private static final float NAV_ICON_H = 32f;
    private static final float NAV_PILL_W = 64f;
    /** 按钮高度（MD3 按钮 = 40dp）。 */
    private static final float BUTTON_H = 40f;
    /** 卡片内操作按钮高度（暂停 / 取消 / 重试……）：全应用统一走 actionButton。 */
    private static final float ACTION_H = 36f;
    /** 图标按钮最小触摸区（48dp）。 */
    private static final float ICON_TOUCH = 48f;

    private UiKit() {}

    /** 应用 MD3 顶部栏配色到状态栏，并让状态栏图标变深色。 */
    public static void applyStatusBar(Activity a) {
        a.getWindow().setStatusBarColor(UiUtil.SURFACE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            a.getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    // ================= 顶部应用栏 =================

    /**
     * MD3 small top app bar：surface 底、on-surface 标题（title-large 22sp）、
     * 起始边距 16dp。右侧可放一个图标动作。
     */
    public static LinearLayout headerFlat(Activity a, String title, View rightAction) {
        LinearLayout bar = bar(a, APP_BAR_H);
        bar.setBackgroundColor(UiUtil.SURFACE);
        bar.setPadding(UiUtil.dp(a, 16), 0,
                rightAction == null ? UiUtil.dp(a, 16) : UiUtil.dp(a, 4), 0);

        TextView t = UiUtil.text(a, title, UiUtil.TYPE_TITLE_LARGE, UiUtil.ON_SURFACE);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(t);

        if (rightAction != null) {
            bar.addView(rightAction);
        }
        return bar;
    }

    /** 带返回箭头的顶部应用栏（返回图标 48dp 触摸区）。 */
    public static LinearLayout backHeader(Activity a, String title, Runnable onBack) {
        LinearLayout bar = bar(a, APP_BAR_H);
        bar.setBackgroundColor(UiUtil.SURFACE);
        bar.setPadding(UiUtil.dp(a, 4), 0, UiUtil.dp(a, 16), 0);

        TextView back = iconAction(a, "‹", 26, UiUtil.ON_SURFACE, onBack);
        back.setPadding(0, 0, 0, UiUtil.dp(a, 4));
        bar.addView(back);

        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = UiUtil.dp(a, 4);
        TextView t = UiUtil.text(a, title, UiUtil.TYPE_TITLE_LARGE, UiUtil.ON_SURFACE);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setLayoutParams(tlp);
        bar.addView(t);
        return bar;
    }

    private static LinearLayout bar(Activity a, float heightDp) {
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(UiUtil.SURFACE);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiUtil.dp(a, heightDp)));
        return bar;
    }

    /** 顶部栏图标动作：48dp 触摸区 + 圆形状态层。 */
    public static TextView iconAction(Activity a, String glyph, float sizeSp,
                                      int color, Runnable onClick) {
        TextView t = UiUtil.text(a, glyph, sizeSp, color);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        int s = UiUtil.dp(a, ICON_TOUCH);
        t.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        t.setBackground(UiUtil.circleRipple(color, a));
        if (onClick != null) {
            t.setOnClickListener(v -> onClick.run());
        }
        return t;
    }

    /** 顶部栏的矢量图标动作：48dp 触摸区 + 圆形状态层，图标着色。 */
    public static ImageView iconViewAction(Activity a, int resId, int color, Runnable onClick) {
        ImageView iv = new ImageView(a);
        iv.setImageResource(resId);
        iv.setImageTintList(ColorStateList.valueOf(color));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int s = UiUtil.dp(a, ICON_TOUCH);
        int pad = UiUtil.dp(a, 12);
        iv.setPadding(pad, pad, pad, pad);
        iv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        iv.setBackground(UiUtil.circleRipple(color, a));
        if (onClick != null) {
            iv.setOnClickListener(v -> onClick.run());
        }
        return iv;
    }

    /**
     * 圆形图标按钮（矢量图标 + 圆形状态层 + 48dp 触摸区）。
     *
     * <p>用于搜索栏的「链接打开」这类独立图标动作：图标按 tint 着色，
     * 状态层只画在圆形形状内，符合 MD3 对图标按钮的要求。</p>
     */
    public static View iconButton(Activity a, int resId, int iconColor, int bgColor,
                                  Runnable onClick) {
        int size = UiUtil.dp(a, 48);
        int iconSize = UiUtil.dp(a, 22);
        int pad = (size - iconSize) / 2;
        FrameLayout holder = new FrameLayout(a);
        holder.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        holder.setBackground(UiUtil.ripple(iconColor,
                UiUtil.round(bgColor, UiUtil.SHAPE_FULL, a), a));
        if (onClick != null) {
            holder.setOnClickListener(v -> onClick.run());
        }

        ImageView iv = new ImageView(a);
        iv.setImageResource(resId);
        iv.setImageTintList(ColorStateList.valueOf(iconColor));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(iconSize, iconSize);
        lp.gravity = Gravity.CENTER;
        holder.addView(iv, lp);
        holder.setPadding(pad, pad, pad, pad);
        return holder;
    }

    // ================= 底部导航栏 =================

    /**
     * 导航栏选中指示器（胶囊）。
     *
     * <p>关键性能取舍：位置与宽度都在 drawable 内部按像素画出来，动画时只改
     * {@link #setBounds} 并自我重绘——<b>不触碰任何 LayoutParams</b>。如果改
     * layoutParams，每帧都会触发整条导航栏（7 个项 + 图标 + 文字）重新测量与
     * 布局，每秒 60 次，帧率会被压得很低，还会拖累同时播放的页面切换动画。</p>
     */
    private static final class PillDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float radius;

        PillDrawable(int color, float radiusPx) {
            paint.setColor(color);
            this.radius = radiusPx;
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) {
                return;
            }
            canvas.drawRoundRect(b.left, b.top, b.right, b.bottom,
                    Math.min(radius, b.height() / 2f), Math.min(radius, b.height() / 2f), paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            paint.setColorFilter(cf);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /** 底部导航栏构建结果：nav 为整条栏，update 切换选中态。 */
    public static final class BottomNav {
        public final LinearLayout nav;
        private final ImageView indicator;
        private final PillDrawable pill;
        private final ImageView[] icons;
        private final TextView[] labels;
        private final int pillW;
        private int current;
        private boolean placed;
        private float curLeft;
        private float curWidth;

        private final Runnable frame = new Runnable() {
            @Override
            public void run() {
                if (anim == null) {
                    return;
                }
                long elapsed = android.os.SystemClock.uptimeMillis() - anim.startAt;
                float t = Math.min(1f, elapsed / (float) anim.duration);
                float f = 1f - (1f - t) * (1f - t); // 减速曲线
                float fromCenter = anim.fromLeft + anim.fromW / 2f;
                float toCenter = anim.toLeft + anim.toW / 2f;
                float center = fromCenter + (toCenter - fromCenter) * f;
                curWidth = anim.fromW + (anim.toW - anim.fromW) * f;
                curLeft = center - curWidth / 2f;
                paint(curLeft, curWidth);
                if (t < 1f) {
                    App.UI.postDelayed(this, 16);
                } else {
                    curWidth = anim.toW;
                    curLeft = anim.toLeft;
                    paint(curLeft, curWidth);
                    anim = null;
                }
            }
        };

        private NavAnim anim;

        private static class NavAnim {
            float fromLeft;
            float toLeft;
            float fromW;
            float toW;
            long startAt;
            int duration;
        }

        BottomNav(LinearLayout nav, ImageView indicator, PillDrawable pill,
                  ImageView[] icons, TextView[] labels, int pillW) {
            this.nav = nav;
            this.indicator = indicator;
            this.pill = pill;
            this.icons = icons;
            this.labels = labels;
            this.pillW = pillW;
        }

        /**
         * 把胶囊画在 stage 坐标系下的 (left, width) 处。
         *
         * <p>只改 drawable 的 bounds 与一个 translationX，两者都不触发
         * requestLayout，因此每帧开销只有一次自绘。</p>
         */
        private void paint(float left, float width) {
            View stage = (View) indicator.getParent();
            if (stage == null) {
                return;
            }
            int x = Math.round(left) - stage.getPaddingLeft();
            indicator.setTranslationX(x);
            int w = Math.max(1, Math.round(width));
            int h = indicator.getHeight() > 0 ? indicator.getHeight() : UiUtil.dp(
                    indicator.getContext(), NAV_ICON_H);
            pill.setBounds(0, 0, w, h);
        }

        private ImageView iconOf(int index) {
            return icons[index];
        }

        /** 目标项的「相对 stage 的左边界」与图标中心。 */
        private float[] targetOf(int index) {
            ImageView icon = iconOf(index);
            View item = (View) icon.getParent();
            if (item == null || item.getWidth() == 0) {
                return null;
            }
            View stage = (View) indicator.getParent();
            int stagePad = stage == null ? 0 : stage.getPaddingLeft();
            float itemLeft = item.getLeft() + stagePad;
            return new float[]{itemLeft, itemLeft + item.getWidth() / 2f,
                    icon.getTop() + item.getTop()};
        }

        /** 立即把胶囊放到指定项的完整宽度（无动画）。 */
        private void snapTo(int index) {
            App.UI.removeCallbacks(frame);
            anim = null;
            current = index;
            float[] t = targetOf(index);
            if (t == null) {
                return;
            }
            curWidth = pillW;
            curLeft = t[1] - pillW / 2f;
            verticalAlignTo((int) t[2]);
            paint(curLeft, curWidth);
            placed = true;
        }

        /** 胶囊纵向对齐图标（只在布局变化时调用，不在动画中调用）。 */
        private void verticalAlignTo(int iconTop) {
            int h = UiUtil.dp(indicator.getContext(), NAV_ICON_H);
            indicator.setY(iconTop);
            android.view.ViewGroup.LayoutParams lp = indicator.getLayoutParams();
            if (lp != null && lp.height != h) {
                lp.height = h;
                indicator.setLayoutParams(lp);
            }
        }

        /**
         * 切换选中项：胶囊在目标项中央从一小段展开成完整胶囊。
         *
         * <p>自驱动帧循环，不依赖系统的动画缩放设置；每帧只重绘，
         * 不触发布局，因此在低端机上也能保持顺滑。</p>
         */
        public void update(int index) {
            if (index == current && placed) {
                return;
            }
            current = index;
            for (int i = 0; i < icons.length; i++) {
                boolean on = i == index;
                Context c = icons[i].getContext();
                // 选中项也要有点击反馈：胶囊形状的涟漪（和指示器同一形状）
                icons[i].setBackground(UiUtil.ripple(UiUtil.ON_SURFACE_VARIANT,
                        UiUtil.round(android.graphics.Color.TRANSPARENT,
                                UiUtil.SHAPE_FULL, c), c));
                icons[i].setImageTintList(ColorStateList.valueOf(
                        on ? UiUtil.ON_SECONDARY_CONTAINER : UiUtil.ON_SURFACE_VARIANT));
                labels[i].setTextColor(on ? UiUtil.ON_SURFACE : UiUtil.ON_SURFACE_VARIANT);
                labels[i].setTypeface(Typeface.DEFAULT, on ? Typeface.BOLD : Typeface.NORMAL);
            }

            float[] t = targetOf(index);
            if (t == null) {
                return; // 还没布局完，等布局回调里落位
            }
            verticalAlignTo((int) t[2]);

            NavAnim a = new NavAnim();
            a.toW = pillW;
            a.fromW = Math.max(1f, pillW * 0.22f);
            a.toLeft = t[1] - a.toW / 2f;
            a.fromLeft = t[1] - a.fromW / 2f;
            a.duration = 240;
            a.startAt = android.os.SystemClock.uptimeMillis();
            anim = a;
            App.UI.post(frame);
        }

        /** 布局完成后落位；旋转屏幕等导致位置变化时重新贴合。 */
        public void syncAfterLayout() {
            if (!placed) {
                snapTo(current);
            } else if (anim == null) {
                float[] t = targetOf(current);
                if (t != null) {
                    verticalAlignTo((int) t[2]);
                    curLeft = t[1] - curWidth / 2f;
                    paint(curLeft, curWidth);
                }
            }
        }
    }

    /**
     * 构建 MD3 底部导航栏：高 80dp，选中项下方是一个 64×32 的胶囊指示器，
     * 切换时在目标项中央展开；图标与标签随选中态换色。
     *
     * @param iconRes 每个目的地的矢量图标资源
     */
    public static BottomNav bottomNav(Activity a, int[] iconRes, String[] labels,
                                     Runnable[] actions) {
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(UiUtil.SURFACE_CONTAINER);
        wrap.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final FrameLayout stage = new FrameLayout(a);
        stage.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiUtil.dp(a, NAV_BAR_H)));

        final int pillW = UiUtil.dp(a, NAV_PILL_W);
        final int pillH = UiUtil.dp(a, NAV_ICON_H);

        final LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        ImageView[] icons = new ImageView[iconRes.length];
        TextView[] labelViews = new TextView[iconRes.length];

        for (int i = 0; i < iconRes.length; i++) {
            final int index = i;
            LinearLayout item = new LinearLayout(a);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            item.setPadding(UiUtil.dp(a, 4), UiUtil.dp(a, 12),
                    UiUtil.dp(a, 4), UiUtil.dp(a, 12));
            item.setOnClickListener(v -> {
                if (actions[index] != null) {
                    actions[index].run();
                }
            });

            ImageView icon = new ImageView(a);
            icon.setImageResource(iconRes[i]);
            icon.setImageTintList(ColorStateList.valueOf(UiUtil.ON_SURFACE_VARIANT));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int ip = UiUtil.dp(a, 4);
            icon.setPadding(ip, ip, ip, ip);
            icon.setLayoutParams(new LinearLayout.LayoutParams(pillW, pillH));
            item.addView(icon);

            TextView label = UiUtil.text(a, labels[i], UiUtil.TYPE_LABEL_MEDIUM,
                    UiUtil.ON_SURFACE_VARIANT);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = UiUtil.dp(a, 4);
            label.setLayoutParams(tlp);
            item.addView(label);

            // 点击反馈：把按下状态转给图标上的胶囊状涟漪。图标尺寸就是胶囊
            // 区域，涟漪自然只覆盖图标那一块，而且完全不碰布局。
            item.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, android.view.MotionEvent e) {
                    int act = e.getActionMasked();
                    if (act == android.view.MotionEvent.ACTION_DOWN) {
                        icon.setPressed(true);
                    } else if (act == android.view.MotionEvent.ACTION_UP
                            || act == android.view.MotionEvent.ACTION_CANCEL) {
                        icon.setPressed(false);
                    }
                    return false;
                }
            });

            icons[i] = icon;
            labelViews[i] = label;
            row.addView(item);
        }

        // 指示器：固定 64×32 的容器，内部胶囊由 drawable 按 bounds 绘制
        final PillDrawable pill = new PillDrawable(UiUtil.SECONDARY_CONTAINER,
                UiUtil.dp(a, NAV_ICON_H / 2f));
        final ImageView indicator = new ImageView(a);
        indicator.setImageDrawable(pill);
        indicator.setClickable(false);
        indicator.setFocusable(false);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(pillW, pillH);
        ilp.gravity = Gravity.TOP | Gravity.START;
        indicator.setLayoutParams(ilp);

        // 指示器放在最底层，图标与文字画在它上面；点击仍能落到各导航项
        stage.addView(row);
        stage.addView(indicator);
        stage.removeView(indicator);
        stage.addView(indicator, 0);

        final BottomNav nav = new BottomNav(wrap, indicator, pill, icons, labelViews, pillW);

        stage.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                nav.syncAfterLayout();
            }
        });

        wrap.addView(stage);
        return nav;
    }

    // ================= 按键 =================

    /** MD3 filled 按钮：primary 底、on-primary 文字、高 40dp、全圆角。 */
    public static TextView button(Activity a, String text, Runnable onClick) {
        return styledButton(a, text, UiUtil.PRIMARY, UiUtil.ON_PRIMARY, onClick);
    }

    /** MD3 tonal 按钮：secondary-container 底、on-secondary-container 文字。 */
    public static TextView tonalButton(Activity a, String text, Runnable onClick) {
        return styledButton(a, text, UiUtil.SECONDARY_CONTAINER,
                UiUtil.ON_SECONDARY_CONTAINER, onClick);
    }

    /** MD3 outlined 按钮：surface 底 + outline 描边 + primary 文字。 */
    public static TextView outlinedButton(Activity a, String text, Runnable onClick) {
        TextView b = UiUtil.text(a, text, UiUtil.TYPE_LABEL_LARGE, UiUtil.PRIMARY);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(UiUtil.dp(a, BUTTON_H));
        b.setPadding(UiUtil.dp(a, 24), 0, UiUtil.dp(a, 24), 0);
        b.setBackground(UiUtil.ripple(UiUtil.PRIMARY,
                UiUtil.roundStroke(android.graphics.Color.TRANSPARENT, UiUtil.SHAPE_FULL,
                        UiUtil.OUTLINE, 1, a), a));
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    private static TextView styledButton(Activity a, String text, int bg, int fg,
                                        Runnable onClick) {
        TextView b = UiUtil.text(a, text, UiUtil.TYPE_LABEL_LARGE, fg);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(UiUtil.dp(a, BUTTON_H));
        b.setPadding(UiUtil.dp(a, 24), 0, UiUtil.dp(a, 24), 0);
        b.setBackground(UiUtil.ripple(fg, UiUtil.round(bg, UiUtil.SHAPE_FULL, a), a));
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    /** MD3 文字按钮：primary 文字、无底色、状态层涟漪。 */
    public static TextView textButton(Activity a, String text, Runnable onClick) {
        TextView b = UiUtil.text(a, text, UiUtil.TYPE_LABEL_LARGE, UiUtil.PRIMARY);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(UiUtil.dp(a, BUTTON_H));
        int h = UiUtil.dp(a, 12);
        int w = UiUtil.dp(a, 12);
        b.setPadding(w, h, w, h);
        b.setBackground(UiUtil.ripple(UiUtil.PRIMARY, UiUtil.round(
                android.graphics.Color.TRANSPARENT, UiUtil.SHAPE_FULL, a), a));
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    /**
     * 卡片内的操作按钮：暂停 / 继续 / 取消 / 重试失败章节 / 重新保存 / 删除记录……
     *
     * <p>这些按钮以前各写各的尺寸：有的 label-medium + 上下 7dp 内边距，有的
     * label-large + 上下 8dp，于是同一屏里「暂停」看着就是比「取消」矮一截。
     * 现在只有这一份定义，谁加按钮都走这里，风格和大小才对得上。</p>
     */
    public static TextView actionButton(Activity a, String text, int color, Runnable onClick) {
        TextView b = UiUtil.text(a, text, UiUtil.TYPE_LABEL_LARGE, color);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setMinHeight(UiUtil.dp(a, ACTION_H));
        b.setMinWidth(UiUtil.dp(a, 56));
        int w = UiUtil.dp(a, 16);
        b.setPadding(w, 0, w, 0);
        b.setBackground(UiUtil.ripple(color, UiUtil.round(
                UiUtil.SURFACE_CONTAINER_HIGH, UiUtil.SHAPE_FULL, a), a));
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    /**
     * 操作按钮行：统一 8dp 间距，一行放不下自动换行。
     *
     * <p>以前靠每个调用点自己给第二个按钮设 leftMargin，漏一个就「贴到一块」
     * （少设一个，两个按钮就会贴在一起）；三个中文
     * 按钮在窄屏上还会顶出卡片被裁掉，所以排布也统一收到这里。</p>
     */
    public static ActionRow actionRow(Activity a) {
        return new ActionRow(a);
    }

    /** 见 {@link #actionRow(Activity)}。 */
    public static final class ActionRow extends LinearLayout {

        private final Activity act;
        private final int gap;
        private final int avail;
        private LinearLayout row;
        private int used;

        private ActionRow(Activity a) {
            super(a);
            this.act = a;
            setOrientation(VERTICAL);
            setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            gap = UiUtil.dp(a, 8);
            // 卡片左右各 16dp 外边距 + 16dp 内边距，剩下的才是可用宽度
            avail = Math.max(UiUtil.dp(a, 160),
                    a.getResources().getDisplayMetrics().widthPixels - UiUtil.dp(a, 64));
        }

        /** 追加一个按钮，按自身宽度决定是否换行。 */
        public void add(TextView b) {
            b.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int w = b.getMeasuredWidth();
            if (row == null || (used > 0 && used + gap + w > avail)) {
                row = new LinearLayout(act);
                row.setOrientation(HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = gap;
                row.setLayoutParams(rlp);
                addView(row);
                used = 0;
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (used > 0) {
                lp.leftMargin = gap;
            }
            b.setLayoutParams(lp);
            row.addView(b);
            used = used == 0 ? w : used + gap + w;
        }
    }

    // ================= 容器 =================
    /**
     * MD3 卡片：surface-container-lowest 底、圆角 12dp、无阴影（MD3 用色调而非影子分层），
     * 内边距 16dp。
     */
    public static LinearLayout card(Activity a) {
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiUtil.round(UiUtil.SURFACE_CONTAINER_LOWEST,
                UiUtil.SHAPE_MEDIUM, a));
        int p = UiUtil.dp(a, 16);
        card.setPadding(p, p, p, p);
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    /** MD3 可点列表项：最小高 56dp、圆角 12dp、状态层涟漪。 */
    public static TextView listRow(Activity a, String text, Runnable onClick) {
        TextView b = UiUtil.text(a, text, UiUtil.TYPE_BODY_LARGE, UiUtil.ON_SURFACE);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setMinHeight(UiUtil.dp(a, 56));
        b.setBackground(UiUtil.ripple(UiUtil.ON_SURFACE,
                UiUtil.round(UiUtil.SURFACE_CONTAINER_HIGH, UiUtil.SHAPE_MEDIUM, a), a));
        int h = UiUtil.dp(a, 16);
        b.setPadding(h, 0, h, 0);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    /** 带副标题的设置行：标题 title-medium + 说明 body-small，整行可点。 */
    public static LinearLayout settingRow(Activity a, String title, String subtitle,
                                         Runnable onClick) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiUtil.dp(a, 64));
        row.setBackground(UiUtil.ripple(UiUtil.ON_SURFACE,
                UiUtil.round(UiUtil.SURFACE_CONTAINER_HIGH, UiUtil.SHAPE_MEDIUM, a), a));
        int h = UiUtil.dp(a, 16);
        int v = UiUtil.dp(a, 12);
        row.setPadding(h, v, h, v);
        row.setOnClickListener(x -> onClick.run());

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = UiUtil.text(a, title, UiUtil.TYPE_TITLE_MEDIUM, UiUtil.ON_SURFACE);
        col.addView(t);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = UiUtil.text(a, subtitle, UiUtil.TYPE_BODY_SMALL,
                    UiUtil.ON_SURFACE_VARIANT);
            s.setPadding(0, UiUtil.dp(a, 2), 0, 0);
            s.setLineSpacing(UiUtil.dp(a, 1), 1f);
            col.addView(s);
        }
        row.addView(col);

        TextView arrow = UiUtil.text(a, "›", 20, UiUtil.ON_SURFACE_VARIANT);
        arrow.setPadding(UiUtil.dp(a, 8), 0, 0, 0);
        row.addView(arrow);
        return row;
    }

    /**
     * 可选中的筛选芯片（MD3 filter chip）：选中为 secondary-container，
     * 未选中为描边。用于「导出格式」「文字转换」这类少量互斥选项。
     */
    public static TextView choiceChip(Activity a, String text, boolean on) {
        TextView t = UiUtil.text(a, text, UiUtil.TYPE_LABEL_LARGE,
                on ? UiUtil.ON_SECONDARY_CONTAINER : UiUtil.ON_SURFACE_VARIANT);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        int h = UiUtil.dp(a, 8);
        int w = UiUtil.dp(a, 6);
        t.setPadding(w, h, w, h);
        t.setMinHeight(UiUtil.dp(a, 38));
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (on) {
            t.setBackground(UiUtil.ripple(UiUtil.ON_SECONDARY_CONTAINER,
                    UiUtil.round(UiUtil.SECONDARY_CONTAINER, UiUtil.SHAPE_SMALL, a), a));
        } else {
            t.setBackground(UiUtil.ripple(UiUtil.ON_SURFACE_VARIANT,
                    UiUtil.roundStroke(android.graphics.Color.TRANSPARENT, UiUtil.SHAPE_SMALL,
                            UiUtil.OUTLINE_VARIANT, 1, a), a));
        }
        return t;
    }

    /** 分组标题（label-large + primary，MD3 列表分组惯例）。 */
    public static TextView sectionTitle(Activity a, String text) {
        TextView t = UiUtil.text(a, text, UiUtil.TYPE_TITLE_SMALL, UiUtil.PRIMARY);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** 细分隔线（outline-variant）。 */
    public static View divider(Activity a) {
        View v = new View(a);
        v.setBackgroundColor(UiUtil.OUTLINE_VARIANT);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, UiUtil.dp(a, 0.6f))));
        return v;
    }

    // ================= 输入 =================

    /** MD3 填充式文本输入框：surface-container-high 底、圆角 12dp、高 56dp。 */
    public static EditText textField(Activity a, String hint, int inputType) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setTextSize(UiUtil.TYPE_BODY_LARGE);
        e.setTextColor(UiUtil.ON_SURFACE);
        e.setHintTextColor(UiUtil.ON_SURFACE_VARIANT);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setMinHeight(UiUtil.dp(a, 56));
        e.setBackground(UiUtil.round(UiUtil.SURFACE_CONTAINER_HIGH, UiUtil.SHAPE_MEDIUM, a));
        int h = UiUtil.dp(a, 16);
        e.setPadding(h, UiUtil.dp(a, 12), h, UiUtil.dp(a, 12));
        return e;
    }

    /** 表单字段标签（body-small）。 */
    public static TextView fieldLabel(Activity a, String text) {
        TextView t = UiUtil.text(a, text, UiUtil.TYPE_BODY_SMALL, UiUtil.ON_SURFACE_VARIANT);
        t.setPadding(0, UiUtil.dp(a, 12), 0, UiUtil.dp(a, 4));
        return t;
    }

    /** 可着色矢量图标视图。 */
    public static ImageView iconView(Activity a, int resId, int tint, float sizeDp) {
        ImageView iv = new ImageView(a);
        iv.setImageResource(resId);
        iv.setImageTintList(ColorStateList.valueOf(tint));
        int s = UiUtil.dp(a, sizeDp);
        iv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return iv;
    }

    // ================= 键盘 =================

    /** 收起软键盘（用 v.getWindowToken()，要求 v 已附加到窗口）。 */
    public static void hideKeyboard(Activity a, View v) {
        if (a == null || v == null || v.getWindowToken() == null) {
            return;
        }
        InputMethodManager imm =
                (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        }
    }

    /** 弹出软键盘（用 v.post 保证焦点已建立）。 */
    public static void showKeyboard(Activity a, final EditText v) {
        if (a == null || v == null) {
            return;
        }
        v.requestFocus();
        v.post(new Runnable() {
            @Override
            public void run() {
                InputMethodManager imm = (InputMethodManager) v.getContext()
                        .getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
                }
            }
        });
    }

    // ================= 其它 =================

    public static void setEnabledText(TextView b, boolean enabled) {
        b.setEnabled(enabled);
        b.setAlpha(enabled ? 1f : 0.38f);
    }

    public static View spacer(Activity a, float dp) {
        View v = new View(a);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiUtil.dp(a, dp)));
        return v;
    }
}
