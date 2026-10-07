package com.niganma.noveldown;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 首页：底栏三个目的地（搜索 / 下载 / 设置）。
 *
 * <p>搜索页把「搜索栏 + 书源选择 + 结果」放进同一条滚动流，小屏上向下翻结果时
 * 上方内容一起滚走；滚离顶部后右下角出现「回到顶部」悬浮按钮——它放在底栏
 * 之上的内容层里，所以不会挡住底栏。下载页展示进行中与已完成的下载，实际下载
 * 在 {@link DownloadManager} 中后台进行，离开页面或退出界面都不会中断。</p>
 */
public class MainActivity extends Activity implements DownloadManager.Listener {

    private static final String PREF = "novel_down";
    private static final String KEY_LAST = "last_keyword";
    private static final String KEY_SEL = "selected_sources";

    /** 悬浮按钮出现的滚动阈值（dp）。 */
    private static final int FAB_SHOW_AT_DP = 160;
    /** 单次最多渲染的结果卡片数。 */
    private static final int MAX_RENDER = 600;

    private static final int TAB_SEARCH = 0;
    private static final int TAB_DOWNLOAD = 1;
    private static final int TAB_SETTINGS = 2;

    // ---- 搜索页 ----
    private EditText searchBox;
    private TextView status;
    private LinearLayout resultCol;
    private LinearLayout chipWrap;
    private TextView selectInfo;
    private TextView selectAllBtn;
    private ScrollView scroll;
    private View searchAction;
    private View fab;
    private View searchContent;

    private int lastShown = 0;
    private final List<SearchBook> lastResults = new ArrayList<>();

    // ---- 下载页 ----
    private View downloadContent;
    private LinearLayout downloadCol;
    private TextView downloadSummary;
    /** 进度回调合帧：一次布局内只重建一次下载页。 */
    private final Runnable rebuildTick = new Runnable() {
        @Override
        public void run() {
            pendingRebuild = false;
            rebuildDownloadPage();
        }
    };
    private boolean pendingRebuild = false;

    // ---- 设置页 ----
    private View settingsContent;
    private TextView sourcesInfo;
    private TextView settingsVersion;
    private TextView threadsValue;
    private TextView convValue;
    private TextView formatValue;
    private TextView templateValue;
    private TextView splitGroupValue;

    // ---- 书源状态 ----
    private List<BookSource> enabledSources = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();

    private int totalSources;
    private int doneSources;
    private final List<String> failedSources = new ArrayList<>();

    private int searchGen = 0;
    private volatile boolean searchCancelled = false;

    private UiKit.BottomNav bottomNav;
    /** 最近一次下载的章节总数，用于在分章设置里给出文件数量预警。 */
    private int lastDownloadTotal;
    /** 当前显示的页，用于判断切换方向（决定动画从哪一侧滑入）。 */
    private int currentTab = TAB_SEARCH;

    private final List<String[]> batchQueue = new ArrayList<>();
    private boolean batchActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.applyStatusBar(this);
        setContentView(buildUi());
        App.init(this);
        refreshSources();
        refreshSettingsInfo();
        rebuildDownloadPage();
        applyTransitions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        DownloadManager.addListener(this);
        refreshSources();
        refreshSettingsInfo();
        rebuildDownloadPage();
        if (batchActive) {
            openNextBatch();
            return;
        }
        if (status.getText().length() == 0) {
            // 数量和操作提示统一放在「搜索范围」卡片里（updateSelectInfo），
            // 这里只留一句用法提示，避免两行都在说「已选 N 个」
            status.setText("输入书名或作者，点「搜索」");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        DownloadManager.removeListener(this);
    }

    // ==================================================================
    //  界面骨架
    // ==================================================================

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(UiUtil.SURFACE);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        root.addView(shell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶栏
        searchAction = UiKit.iconViewAction(this, R.drawable.ic_search, UiUtil.ON_SURFACE,
                new Runnable() {
                    @Override
                    public void run() {
                        scrollToTop(true);
                    }
                });
        searchAction.setContentDescription("回到顶部并聚焦搜索");
        searchAction.setVisibility(View.GONE);
        shell.addView(UiKit.headerFlat(this, App.APP_NAME, searchAction));

        // 内容区：三页叠放；悬浮按钮放在这一层，正好位于底栏之上
        FrameLayout content = new FrameLayout(this);
        content.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        shell.addView(content);

        searchContent = buildSearchContent();
        downloadContent = buildDownloadContent();
        settingsContent = buildSettingsContent();
        FrameLayout.LayoutParams pageLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        content.addView(searchContent, pageLp);
        content.addView(downloadContent, pageLp);
        content.addView(settingsContent, pageLp);
        downloadContent.setVisibility(View.GONE);
        settingsContent.setVisibility(View.GONE);

        fab = buildFab();
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                UiUtil.dp(this, 52), UiUtil.dp(this, 52));
        flp.gravity = Gravity.BOTTOM | Gravity.END;
        flp.rightMargin = UiUtil.dp(this, 16);
        flp.bottomMargin = UiUtil.dp(this, 16);
        content.addView(fab, flp);

        // 底栏：搜索 / 下载 / 设置
        bottomNav = UiKit.bottomNav(this,
                new int[]{R.drawable.ic_search, R.drawable.ic_download, R.drawable.ic_settings},
                new String[]{"搜索", "下载", "设置"},
                new Runnable[]{
                        new Runnable() {
                            @Override
                            public void run() {
                                showTab(TAB_SEARCH);
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                showTab(TAB_DOWNLOAD);
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                showTab(TAB_SETTINGS);
                            }
                        }
                });
        shell.addView(bottomNav.nav);

        // 键盘弹起时把底栏收起来。否则窗口被键盘压扁（adjustResize），底栏会
        // 「飞」到键盘上方、还跟输入区抢高度；收起来最干净，键盘一关就回来。
        root.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        android.graphics.Rect r = new android.graphics.Rect();
                        root.getWindowVisibleDisplayFrame(r);
                        int covered = root.getRootView().getHeight() - r.bottom;
                        boolean keyboardOpen = covered > UiUtil.dp(MainActivity.this, 120);
                        int want = keyboardOpen ? View.GONE : View.VISIBLE;
                        if (bottomNav.nav.getVisibility() != want) {
                            bottomNav.nav.setVisibility(want);
                        }
                    }
                });

        return root;
    }

    private View buildSearchContent() {
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(UiUtil.SURFACE);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, UiUtil.dp(this, 12));
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int x, int y, int oldX, int oldY) {
                updateScrollAffordances(y);
            }
        });

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.addView(col);

        col.addView(searchBar());
        col.addView(sourceCard());

        status = UiUtil.text(this, "", UiUtil.TYPE_BODY_SMALL, UiUtil.ON_SURFACE_VARIANT);
        status.setPadding(UiUtil.dp(this, 20), UiUtil.dp(this, 6),
                UiUtil.dp(this, 20), UiUtil.dp(this, 6));
        col.addView(status);

        resultCol = new LinearLayout(this);
        resultCol.setOrientation(LinearLayout.VERTICAL);
        resultCol.setPadding(0, UiUtil.dp(this, 2), 0, UiUtil.dp(this, 8));
        col.addView(resultCol);

        return scroll;
    }

    private View buildFab() {
        FrameLayout holder = new FrameLayout(this);
        holder.setBackground(UiUtil.round(UiUtil.PRIMARY_CONTAINER, UiUtil.SHAPE_LARGE, this));
        holder.setElevation(UiUtil.dp(this, 3));
        holder.setVisibility(View.GONE);

        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.ic_arrow_up);
        iv.setImageTintList(ColorStateList.valueOf(UiUtil.ON_PRIMARY_CONTAINER));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                UiUtil.dp(this, 24), UiUtil.dp(this, 24));
        ilp.gravity = Gravity.CENTER;
        holder.addView(iv, ilp);

        holder.setOnClickListener(v -> scrollToTop(false));
        holder.setContentDescription("回到顶部");
        return holder;
    }

    private void updateScrollAffordances(int scrollY) {
        if (searchContent.getVisibility() != View.VISIBLE) {
            return;
        }
        boolean show = scrollY > UiUtil.dp(this, FAB_SHOW_AT_DP);
        if (show && fab.getVisibility() != View.VISIBLE) {
            fab.setVisibility(View.VISIBLE);
            fab.setAlpha(0f);
            fab.animate().alpha(1f).setDuration(160).start();
            searchAction.setVisibility(View.VISIBLE);
        } else if (!show && fab.getVisibility() == View.VISIBLE) {
            searchAction.setVisibility(View.GONE);
            fab.animate().alpha(0f).setDuration(120).withEndAction(new Runnable() {
                @Override
                public void run() {
                    fab.setVisibility(View.GONE);
                }
            }).start();
        }
    }

    private void scrollToTop(boolean focus) {
        if (scroll != null) {
            scroll.smoothScrollTo(0, 0);
        }
        if (focus && searchBox != null) {
            searchBox.requestFocus();
            UiKit.showKeyboard(this, searchBox);
        }
    }

    private View pageOf(int tab) {
        if (tab == TAB_DOWNLOAD) {
            return downloadContent;
        }
        if (tab == TAB_SETTINGS) {
            return settingsContent;
        }
        return searchContent;
    }

    /**
     * 切换板块并播放过渡动画。
     *
     * <p>动画由 {@link #startTabTransition} 自己按帧驱动，不依赖系统的
     * animator/transition 缩放设置——部分机型（尤其定制 ROM）会把它关掉，
     * 那样 ViewPropertyAnimator 与 overridePendingTransition 都会直接跳过。</p>
     */
    private void showTab(int tab) {
        if (tab == currentTab) {
            return;
        }
        int old = currentTab;
        currentTab = tab;
        View in = pageOf(tab);
        View out = pageOf(old);
        startTabTransition(in, out, tab > old ? 1f : -1f);

        if (tab == TAB_SEARCH) {
            updateScrollAffordances(scroll.getScrollY());
        } else {
            fab.animate().cancel();
            fab.setVisibility(View.GONE);
            searchAction.setVisibility(View.GONE);
            UiKit.hideKeyboard(this, searchBox);
        }
        if (tab == TAB_DOWNLOAD) {
            // 延后到切换动画结束后再重建，避免动画期间插入一次重布局
            App.UI.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (currentTab == TAB_DOWNLOAD) {
                        rebuildDownloadPage();
                    }
                }
            }, 300);
        }
        bottomNav.update(tab);
    }

    /** 上一帧回调，用于在切页时取消正在进行的动画。 */
    private final Runnable tabFrame = new Runnable() {
        @Override
        public void run() {
            if (tabAnim == null) {
                return;
            }
            long elapsed = android.os.SystemClock.uptimeMillis() - tabAnim.startAt;
            float t = Math.min(1f, elapsed / (float) tabAnim.duration);
            // DecelerateInterpolator 的等价曲线
            float f = 1f - (1f - t) * (1f - t);
            tabAnim.in.setTranslationX(tabAnim.inFrom * (1f - f));
            tabAnim.in.setAlpha(f);
            tabAnim.out.setTranslationX(tabAnim.outTo * f);
            tabAnim.out.setAlpha(1f - f * 0.9f);
            if (t < 1f) {
                App.UI.postDelayed(this, 16);
            } else {
                tabAnim.in.setTranslationX(0f);
                tabAnim.in.setAlpha(1f);
                tabAnim.in.setLayerType(View.LAYER_TYPE_NONE, null);
                tabAnim.out.setVisibility(View.GONE);
                tabAnim.out.setAlpha(1f);
                tabAnim.out.setTranslationX(0f);
                tabAnim.out.setLayerType(View.LAYER_TYPE_NONE, null);
                tabAnim = null;
            }
        }
    };

    /** 正在播放的切页动画状态。 */
    private static class TabAnim {
        View in;
        View out;
        float inFrom;
        float outTo;
        long startAt;
        int duration;
    }



    private TabAnim tabAnim;

    private void startTabTransition(View in, View out, float dir) {
        App.UI.removeCallbacks(tabFrame);
        if (tabAnim != null) {
            // 收尾上一次动画，避免残留位移，并释放硬件层
            tabAnim.in.setTranslationX(0f);
            tabAnim.in.setAlpha(1f);
            tabAnim.in.setLayerType(View.LAYER_TYPE_NONE, null);
            tabAnim.out.setVisibility(View.GONE);
            tabAnim.out.setAlpha(1f);
            tabAnim.out.setTranslationX(0f);
            tabAnim.out.setLayerType(View.LAYER_TYPE_NONE, null);
            tabAnim = null;
        }
        // 位移取屏幕宽度的 1/3，过渡足够明显；
        // 用像素值而非 dp，宽屏上同样醒目
        float dx = Math.max(UiUtil.dp(this, 120),
                getResources().getDisplayMetrics().widthPixels / 3f) * dir;

        TabAnim a = new TabAnim();
        a.in = in;
        a.out = out;
        a.inFrom = dx;
        a.outTo = -dx * 0.4f;
        a.duration = 280;
        a.startAt = android.os.SystemClock.uptimeMillis();
        tabAnim = a;

        // 页面里可能有成百上千个子视图（搜索结果就是），位移+透明度动画
        // 若每帧重绘整棵子树会明显掉帧；用硬件层缓存成纹理，动画结束即释放。
        in.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        out.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        in.setVisibility(View.VISIBLE);
        in.setAlpha(0f);
        in.setTranslationX(dx);
        out.setAlpha(1f);
        out.setTranslationX(0f);
        App.UI.post(tabFrame);
    }

    // ==================================================================
    //  搜索页
    // ==================================================================

    private View searchBar() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int side = UiUtil.dp(this, 16);
        olp.setMargins(side, UiUtil.dp(this, 8), side, UiUtil.dp(this, 4));
        outer.setLayoutParams(olp);

        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.HORIZONTAL);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setBackground(UiUtil.round(UiUtil.SURFACE_CONTAINER_HIGH, UiUtil.SHAPE_FULL, this));
        int h = UiUtil.dp(this, 6);
        field.setPadding(UiUtil.dp(this, 14), h, UiUtil.dp(this, 6), h);
        field.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView searchIcon = new ImageView(this);
        searchIcon.setImageResource(R.drawable.ic_search);
        searchIcon.setImageTintList(ColorStateList.valueOf(UiUtil.ON_SURFACE_VARIANT));
        searchIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams silp = new LinearLayout.LayoutParams(
                UiUtil.dp(this, 20), UiUtil.dp(this, 20));
        silp.rightMargin = UiUtil.dp(this, 10);
        searchIcon.setLayoutParams(silp);
        field.addView(searchIcon);

        searchBox = new EditText(this);
        searchBox.setHint("输入书名 / 作者");
        searchBox.setTextSize(UiUtil.TYPE_BODY_LARGE);
        searchBox.setSingleLine(true);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBox.setInputType(InputType.TYPE_CLASS_TEXT);
        searchBox.setBackground(null);
        searchBox.setTextColor(UiUtil.ON_SURFACE);
        searchBox.setHintTextColor(UiUtil.ON_SURFACE_VARIANT);
        searchBox.setText(prefs().getString(KEY_LAST, ""));
        searchBox.setPadding(0, UiUtil.dp(this, 10), 0, UiUtil.dp(this, 10));
        searchBox.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        searchBox.setOnEditorActionListener((v, actionId, event) -> {
            doSearch();
            return true;
        });
        field.addView(searchBox);

        TextView clear = UiUtil.text(this, "✕", UiUtil.TYPE_BODY_MEDIUM, UiUtil.ON_SURFACE_VARIANT);
        int cp = UiUtil.dp(this, 8);
        clear.setGravity(Gravity.CENTER);
        clear.setPadding(cp, cp, cp, cp);
        clear.setBackground(UiUtil.ripple(UiUtil.ON_SURFACE_VARIANT,
                UiUtil.round(UiUtil.SURFACE_CONTAINER_HIGH, UiUtil.SHAPE_FULL, this), this));
        clear.setOnClickListener(v -> searchBox.setText(""));
        field.addView(clear);
        outer.addView(field);

        View link = UiKit.iconButton(this, R.drawable.ic_link, UiUtil.PRIMARY,
                UiUtil.SURFACE_CONTAINER_HIGH, new Runnable() {
                    @Override
                    public void run() {
                        openByLink();
                    }
                });
        link.setContentDescription("粘贴书籍链接");
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.leftMargin = UiUtil.dp(this, 8);
        link.setLayoutParams(llp);
        outer.addView(link);

        TextView btn = UiKit.button(this, "搜索", new Runnable() {
            @Override
            public void run() {
                doSearch();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = UiUtil.dp(this, 8);
        btn.setLayoutParams(blp);
        outer.addView(btn);
        return outer;
    }

    private View sourceCard() {
        LinearLayout sCard = UiKit.card(this);
        int sp = UiUtil.dp(this, 14);
        sCard.setPadding(sp, UiUtil.dp(this, 12), sp, UiUtil.dp(this, 12));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(UiUtil.dp(this, 16), UiUtil.dp(this, 4),
                UiUtil.dp(this, 16), UiUtil.dp(this, 4));
        sCard.setLayoutParams(slp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = UiUtil.text(this, "搜索范围", UiUtil.TYPE_TITLE_SMALL, UiUtil.ON_SURFACE);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(label);

        // 与卡片内其它操作按钮同一套样式（以前这里单独写内边距，大小对不上）
        selectAllBtn = smallAction("全选", UiUtil.PRIMARY, new Runnable() {
            @Override
            public void run() {
                toggleSelectAll();
            }
        });
        selectAllBtn.setMinWidth(UiUtil.dp(this, 72));
        head.addView(selectAllBtn);
        sCard.addView(head);

        chipWrap = new LinearLayout(this);
        chipWrap.setOrientation(LinearLayout.VERTICAL);
        chipWrap.setPadding(0, UiUtil.dp(this, 2), 0, 0);
        sCard.addView(chipWrap);

        selectInfo = UiUtil.text(this, "", UiUtil.TYPE_LABEL_SMALL, UiUtil.ON_SURFACE_VARIANT);
        selectInfo.setPadding(0, UiUtil.dp(this, 10), 0, 0);
        sCard.addView(selectInfo);
        return sCard;
    }

    /**
     * MD3 筛选芯片：选中为 secondary-container，未选中为描边。
     *
     * <p>尺寸完全交给 wrap_content，这里只负责配色与点击，不做任何宽度限制，
     * 因此不存在文字被固定宽度挤掉的可能。</p>
     */
    private TextView chip(String name, boolean on) {
        TextView t = UiUtil.text(this, name, UiUtil.TYPE_LABEL_LARGE,
                on ? UiUtil.ON_SECONDARY_CONTAINER : UiUtil.ON_SURFACE_VARIANT);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        int hp = UiUtil.dp(this, 14);
        int vp = UiUtil.dp(this, 9);
        t.setPadding(hp, vp, hp, vp);
        t.setMinHeight(UiUtil.dp(this, 40));
        if (on) {
            t.setBackground(UiUtil.ripple(UiUtil.ON_SECONDARY_CONTAINER,
                    UiUtil.round(UiUtil.SECONDARY_CONTAINER, UiUtil.SHAPE_SMALL, this), this));
        } else {
            t.setBackground(UiUtil.ripple(UiUtil.ON_SURFACE_VARIANT,
                    UiUtil.roundStroke(android.graphics.Color.TRANSPARENT, UiUtil.SHAPE_SMALL,
                            UiUtil.OUTLINE_VARIANT, 1, this), this));
        }
        return t;
    }

    /**
     * 按芯片自身宽度流式排布：一行放不下就换行。芯片保持 wrap_content，
     * 所以书源名长短都不影响文字显示。
     */
    private void layoutChips(java.util.List<BookSource> sources) {
        final int gap = UiUtil.dp(this, 8);
        final int avail = Math.max(UiUtil.dp(this, 160),
                getResources().getDisplayMetrics().widthPixels
                        - UiUtil.dp(this, 32 + 28 + 4));

        LinearLayout row = null;
        int used = 0;
        for (final BookSource bs : sources) {
            final String name = bs.name;
            TextView c = chip(name, selected.contains(name));

            // 量出芯片自身宽度（含内边距），用于判断是否换行
            c.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int w = c.getMeasuredWidth();

            if (row == null || (used > 0 && used + gap + w > avail)) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = UiUtil.dp(this, 8);
                row.setLayoutParams(rlp);
                chipWrap.addView(row);
                used = 0;
            }

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (used > 0) {
                lp.leftMargin = gap;
            }
            c.setLayoutParams(lp);
            c.setOnClickListener(v -> {
                if (selected.contains(name)) {
                    selected.remove(name);
                } else {
                    selected.add(name);
                }
                saveSelected();
                rebuildChips();
            });
            c.setOnLongClickListener(v -> {
                selected.clear();
                selected.add(name);
                saveSelected();
                rebuildChips();
                Toast.makeText(this, "仅搜索：" + name, Toast.LENGTH_SHORT).show();
                return true;
            });
            row.addView(c);
            used = used == 0 ? w : used + gap + w;
        }
    }

    private void refreshSources() {
        enabledSources = SourceStore.searchable(this);
        Set<String> saved = loadSelected();
        selected.clear();
        for (BookSource s : enabledSources) {
            if (saved == null || saved.contains(s.name)) {
                selected.add(s.name);
            }
        }
        rebuildChips();
    }

    private void rebuildChips() {
        chipWrap.removeAllViews();
        if (enabledSources.isEmpty()) {
            chipWrap.addView(UiUtil.text(this,
                    "没有可搜索的书源。可到「设置 → 导入书源」导入或启用，"
                            + "或用搜索栏右侧的链接按钮粘贴书籍链接直接打开",
                    UiUtil.TYPE_BODY_MEDIUM, UiUtil.ON_SURFACE_VARIANT));
        } else {
            layoutChips(enabledSources);
        }
        // 提示仅支持链接打开的书源（索引式，不进搜索范围）
        int linkOnly = SourceStore.load(this).size() - enabledSources.size();
        if (linkOnly > 0) {
            TextView hint = UiUtil.text(this, "另有 " + linkOnly
                    + " 个仅支持链接打开的书源（索引式），用搜索栏右侧的链接按钮粘贴书籍链接使用",
                    UiUtil.TYPE_LABEL_SMALL, UiUtil.ON_SURFACE_VARIANT);
            hint.setPadding(0, UiUtil.dp(this, 8), 0, 0);
            chipWrap.addView(hint);
        }
        updateSelectInfo();
    }

    private void toggleSelectAll() {
        if (!enabledSources.isEmpty() && selected.size() >= enabledSources.size()) {
            selected.clear();
        } else {
            selected.clear();
            for (BookSource s : enabledSources) {
                selected.add(s.name);
            }
        }
        saveSelected();
        rebuildChips();
    }

    private void updateSelectInfo() {
        int total = enabledSources.size();
        selectInfo.setText("已启用 " + total + " 个 · 已选 " + selected.size()
                + " 个" + (total > 1 ? " · 点按切换，长按仅搜此源" : ""));
        boolean all = total > 0 && selected.size() >= total;
        selectAllBtn.setText(all ? "全不选" : "全选");
        selectAllBtn.setVisibility(total > 0 ? View.VISIBLE : View.GONE);
    }

    private void doSearch() {
        String key = searchBox.getText().toString().trim();
        if (key.isEmpty()) {
            status.setText("请输入关键字");
            return;
        }
        final List<BookSource> pool = new ArrayList<>();
        for (BookSource s : enabledSources) {
            if (selected.contains(s.name)) {
                pool.add(s);
            }
        }
        if (pool.isEmpty()) {
            status.setText(enabledSources.isEmpty()
                    ? "没有启用的书源，请到「设置 → 书源管理」开启或导入"
                    : "请至少选择一个要搜索的书源");
            return;
        }
        prefs().edit().putString(KEY_LAST, key).apply();
        UiKit.hideKeyboard(this, searchBox);

        searchCancelled = true;
        final int gen = ++searchGen;
        searchCancelled = false;

        lastResults.clear();
        lastShown = 0;
        resultCol.removeAllViews();
        failedSources.clear();
        totalSources = pool.size();
        doneSources = 0;
        status.setText(pool.size() == 1
                ? "正在「" + pool.get(0).name + "」中搜索…"
                : "正在 " + totalSources + " 个书源中搜索…");

        Searcher.search(pool, key, 1, App.POOL, App.UI, new Searcher.Callback() {
            @Override
            public void onBook(SearchBook book) {
                if (gen != searchGen || searchCancelled) {
                    return;
                }
                lastResults.add(book);
                renderResults();
            }

            @Override
            public void onSourceFinished(String source, int count) {
                if (gen != searchGen || searchCancelled) {
                    return;
                }
                doneSources++;
                if (count < 0) {
                    failedSources.add(source);
                }
                status.setText(progress());
            }

            @Override
            public void onFinished(int total) {
                if (gen != searchGen || searchCancelled) {
                    return;
                }
                showSearchSummary(pool, total);
            }
        });
    }

    private void showSearchSummary(List<BookSource> pool, int total) {
        String msg;
        if (pool.size() == 1) {
            msg = "「" + pool.get(0).name + "」搜索完成，共 " + total + " 条结果";
            if (total == 0) {
                msg = "「" + pool.get(0).name + "」没有找到结果";
            }
        } else {
            msg = "搜索完成，共 " + total + " 条结果";
            if (total == 0) {
                msg = "没有找到结果（可换关键字或勾选更多书源）";
            }
        }
        if (!failedSources.isEmpty()) {
            msg += " · " + failedSources.size() + " 个书源连接失败";
        }
        if (total > MAX_RENDER) {
            msg += " · 仅显示前 " + MAX_RENDER + " 条";
        }
        status.setText(msg);
    }

    private String progress() {
        return "已完成 " + doneSources + "/" + totalSources
                + " 个书源 · 找到 " + lastResults.size() + " 条";
    }

    private void renderResults() {
        int limit = Math.min(lastResults.size(), MAX_RENDER);
        for (int i = lastShown; i < limit; i++) {
            resultCol.addView(resultCard(lastResults.get(i)));
        }
        lastShown = limit;
    }

    private View resultCard(final SearchBook b) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiUtil.ripple(UiUtil.ON_SURFACE,
                UiUtil.round(UiUtil.SURFACE_CONTAINER_LOWEST, UiUtil.SHAPE_MEDIUM, this), this));
        int p = UiUtil.dp(this, 14);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = UiUtil.dp(this, 4);
        lp.setMargins(UiUtil.dp(this, 16), m, UiUtil.dp(this, 16), m);
        card.setLayoutParams(lp);
        card.setClickable(true);
        card.setFocusable(true);

        TextView name = UiUtil.text(this, b.name, UiUtil.TYPE_TITLE_MEDIUM, UiUtil.ON_SURFACE);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(name);

        String sub = (b.author == null || b.author.isEmpty() ? "佚名" : b.author)
                + "   ·   " + b.sourceName;
        TextView s = UiUtil.text(this, sub, UiUtil.TYPE_BODY_SMALL, UiUtil.ON_SURFACE_VARIANT);
        s.setPadding(0, UiUtil.dp(this, 6), 0, 0);
        card.addView(s);

        TextView url = UiUtil.text(this, b.url, UiUtil.TYPE_LABEL_SMALL, UiUtil.PRIMARY);
        url.setSingleLine(true);
        url.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        url.setPadding(0, UiUtil.dp(this, 4), 0, 0);
        card.addView(url);

        card.setOnClickListener(v -> {
            Intent it = new Intent(MainActivity.this, DetailActivity.class);
            it.putExtra("name", b.name);
            it.putExtra("author", b.author);
            it.putExtra("url", b.url);
            it.putExtra("source", b.sourceName);
            startActivity(it);
        });
        return card;
    }

    // ==================================================================
    //  下载页
    // ==================================================================

    private View buildDownloadContent() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiUtil.SURFACE);
        sv.setClipToPadding(false);
        sv.setPadding(0, 0, 0, UiUtil.dp(this, 16));
        sv.setVerticalScrollBarEnabled(false);

        downloadCol = new LinearLayout(this);
        downloadCol.setOrientation(LinearLayout.VERTICAL);
        downloadCol.setLayoutParams(new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int side = UiUtil.dp(this, 16);
        downloadCol.setPadding(0, UiUtil.dp(this, 14), 0, 0);
        sv.addView(downloadCol);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(side, 0, side, UiUtil.dp(this, 6));

        TextView title = UiUtil.text(this, "下载", UiUtil.TYPE_TITLE_MEDIUM, UiUtil.ON_SURFACE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(title);

        TextView refresh = smallAction("刷新", UiUtil.PRIMARY, new Runnable() {
            @Override
            public void run() {
                rebuildDownloadPage();
            }
        });
        head.addView(refresh);
        downloadCol.addView(head);

        downloadSummary = UiUtil.text(this, "", UiUtil.TYPE_BODY_SMALL, UiUtil.ON_SURFACE_VARIANT);
        downloadSummary.setPadding(side, 0, side, UiUtil.dp(this, 10));
        downloadCol.addView(downloadSummary);

        return sv;
    }

    /** 重建下载页（进行中 + 已完成）。 */
    private void rebuildDownloadPage() {
        if (downloadCol == null) {
            return;
        }
        // 头部两行固定保留，其余为列表
        while (downloadCol.getChildCount() > 2) {
            downloadCol.removeViewAt(2);
        }

        List<DownloadManager.Job> active = DownloadManager.active();
        List<DownloadManager.Job> history = DownloadManager.history();

        int doneCount = 0;
        for (DownloadManager.Job j : history) {
            if (j.status == DownloadManager.DONE) {
                doneCount++;
            }
        }
        downloadSummary.setText(active.isEmpty()
                ? ("已下载 " + doneCount + " 本 · 下载在后台进行，离开此页不会中断")
                : ("进行中 " + active.size() + " 本 · 已下载 " + doneCount + " 本"));

        if (!active.isEmpty()) {
            downloadCol.addView(groupLabel("进行中"));
            for (DownloadManager.Job j : active) {
                downloadCol.addView(runningCard(j));
            }
        }

        downloadCol.addView(groupLabel("已下载"));
        if (history.isEmpty()) {
            TextView empty = UiUtil.text(this, "还没有下载记录。\n在书源搜索结果里点开一本书，"
                    + "选择章节范围后即可开始下载。", UiUtil.TYPE_BODY_MEDIUM,
                    UiUtil.ON_SURFACE_VARIANT);
            empty.setPadding(UiUtil.dp(this, 4), UiUtil.dp(this, 4), UiUtil.dp(this, 4), 0);
            empty.setLineSpacing(UiUtil.dp(this, 4), 1f);
            downloadCol.addView(empty);
        } else {
            for (DownloadManager.Job j : history) {
                downloadCol.addView(historyCard(j));
            }
        }
    }

    private TextView groupLabel(String text) {
        TextView t = UiUtil.text(this, text, UiUtil.TYPE_LABEL_LARGE, UiUtil.PRIMARY);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        int side = UiUtil.dp(this, 16);
        t.setPadding(side, UiUtil.dp(this, 14), side, UiUtil.dp(this, 6));
        return t;
    }

    private View runningCard(final DownloadManager.Job job) {
        LinearLayout card = UiKit.card(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int side = UiUtil.dp(this, 16);
        clp.setMargins(side, 0, side, UiUtil.dp(this, 8));
        card.setLayoutParams(clp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = UiUtil.text(this, job.bookName, UiUtil.TYPE_TITLE_MEDIUM, UiUtil.ON_SURFACE);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(name);

        String sub = job.source
                + (job.author.isEmpty() ? "" : " · " + job.author)
                + " · " + Exporter.formatName(job.format);
        TextView subView = UiUtil.text(this, sub, UiUtil.TYPE_BODY_SMALL,
                UiUtil.ON_SURFACE_VARIANT);
        subView.setPadding(0, UiUtil.dp(this, 2), 0, 0);
        subView.setSingleLine(true);
        info.addView(subView);
        top.addView(info);

        if (job.isRunning()) {
            addGap(top, smallAction("暂停", UiUtil.PRIMARY, new Runnable() {
                @Override
                public void run() {
                    DownloadManager.pause(job);
                    rebuildDownloadPage();
                }
            }));
        } else {
            addGap(top, smallAction("继续", UiUtil.PRIMARY, new Runnable() {
                @Override
                public void run() {
                    resumeJob(job);
                }
            }));
        }

        // 与「暂停/继续」同一套样式，之前这里手写的内边距让两个按钮高低不一
        addGap(top, smallAction("取消", UiUtil.ERROR, new Runnable() {
            @Override
            public void run() {
                confirmCancel(job);
            }
        }));
        card.addView(top);

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(job.percent());
        bar.setProgressTintList(ColorStateList.valueOf(UiUtil.PRIMARY));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(UiUtil.SURFACE_CONTAINER_HIGHEST));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiUtil.dp(this, 6));
        plp.topMargin = UiUtil.dp(this, 12);
        bar.setLayoutParams(plp);
        card.addView(bar);

        // 进度 + 速度 + 剩余时间
        StringBuilder line = new StringBuilder();
        line.append(job.completed).append("/").append(job.total).append(" 章 · ")
                .append(job.percent()).append("%");
        if (job.isRunning()) {
            String sp = job.speedText();
            String eta = job.etaText();
            if (!sp.isEmpty()) {
                line.append(" · ").append(sp);
            }
            if (!eta.isEmpty()) {
                line.append(" · ").append(eta);
            }
        }
        if (job.failedChapters > 0) {
            line.append(" · 失败 ").append(job.failedChapters);
        }
        TextView pct = UiUtil.text(this, line.toString(), UiUtil.TYPE_BODY_SMALL,
                job.isPaused() ? UiUtil.ON_SURFACE_VARIANT : UiUtil.ON_SURFACE_VARIANT);
        pct.setPadding(0, UiUtil.dp(this, 6), 0, 0);
        card.addView(pct);

        if (job.isPaused() && job.message != null && !job.message.isEmpty()) {
            TextView hint = UiUtil.text(this, job.message, UiUtil.TYPE_LABEL_SMALL,
                    UiUtil.ON_SURFACE_VARIANT);
            hint.setPadding(0, UiUtil.dp(this, 4), 0, 0);
            card.addView(hint);
        }
        return card;
    }

    /** 继续一个已暂停的任务：重新载入目录后只补缺失的章节。 */
    private void resumeJob(final DownloadManager.Job job) {
        Toast.makeText(this, "正在恢复《" + job.bookName + "》…", Toast.LENGTH_SHORT).show();
        App.POOL.execute(new Runnable() {
            @Override
            public void run() {
                final BookSource src = findSourceByName(job.source);
                final List<Chapter> chapters;
                try {
                    chapters = src == null ? null : Downloader.loadChapters(src, job.url);
                } catch (final Exception e) {
                    App.UI.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this,
                                    "恢复失败，目录加载出错：" + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                    return;
                }
                App.UI.post(new Runnable() {
                    @Override
                    public void run() {
                        if (chapters == null || chapters.isEmpty()) {
                            Toast.makeText(MainActivity.this, "恢复失败：找不到可用的书源或目录",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (chapters.size() != job.session.parts.length) {
                            Toast.makeText(MainActivity.this,
                                    "目录已变化（" + job.session.parts.length + " → "
                                            + chapters.size() + " 章），请重新下载",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        DownloadManager.resume(MainActivity.this, job, src, chapters,
                                DownloadPrefs.threads(MainActivity.this));
                        rebuildDownloadPage();
                    }
                });
            }
        });
    }

    private BookSource findSourceByName(String name) {
        for (BookSource s : SourceStore.all(this)) {
            if (s.name.equals(name)) {
                return s;
            }
        }
        return null;
    }

    private View historyCard(final DownloadManager.Job job) {
        LinearLayout card = UiKit.card(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int side = UiUtil.dp(this, 16);
        clp.setMargins(side, 0, side, UiUtil.dp(this, 8));
        card.setLayoutParams(clp);

        TextView name = UiUtil.text(this, job.bookName, UiUtil.TYPE_TITLE_MEDIUM, UiUtil.ON_SURFACE);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(name);

        String sub = job.source
                + (job.author.isEmpty() ? "" : " · " + job.author)
                + " · " + job.total + " 章 · " + Exporter.formatName(job.format);
        TextView s = UiUtil.text(this, sub, UiUtil.TYPE_BODY_SMALL, UiUtil.ON_SURFACE_VARIANT);
        s.setPadding(0, UiUtil.dp(this, 4), 0, 0);
        card.addView(s);

        boolean ok = job.status == DownloadManager.DONE;
        String tail;
        if (ok) {
            tail = "已完成 · " + (job.location.isEmpty() ? "" : job.location);
        } else if (job.status == DownloadManager.CANCELLED) {
            tail = "已取消";
        } else {
            tail = "失败" + (job.message.isEmpty() ? "" : "：" + job.message);
        }
        TextView st = UiUtil.text(this, tail, UiUtil.TYPE_LABEL_SMALL,
                ok ? UiUtil.PRIMARY : UiUtil.ERROR);
        st.setPadding(0, UiUtil.dp(this, 4), 0, 0);
        card.addView(st);

        UiKit.ActionRow actions = UiKit.actionRow(this);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = UiUtil.dp(this, 10);
        actions.setLayoutParams(alp);

        if (ok) {
            actions.add(smallAction("分享文件", UiUtil.PRIMARY, new Runnable() {
                @Override
                public void run() {
                    shareFile(job);
                }
            }));
        } else if (job.failedChapters > 0 && job.session != null) {
            // 没有会话数据（重启后的历史记录）就没法只补那几章，
            // 这种情况下不显示入口，免得点了才发现「没有失败章节」
            actions.add(smallAction("重试失败章节", UiUtil.PRIMARY, new Runnable() {
                @Override
                public void run() {
                    retryFailed(job);
                }
            }));
        }
        // 章节其实都下好了、只是写文件失败（同名文件冲突、空间不足……）：
        // 这时没有「失败章节」可重试，但也不必重下，重新拼装再存一次就行
        if (!ok && DownloadManager.canResave(job)) {
            actions.add(smallAction("重新保存", UiUtil.PRIMARY, new Runnable() {
                @Override
                public void run() {
                    resave(job);
                }
            }));
        }

        actions.add(smallAction("重新下载", UiUtil.PRIMARY, new Runnable() {
            @Override
            public void run() {
                Intent it = new Intent(MainActivity.this, DetailActivity.class);
                it.putExtra("name", job.bookName);
                it.putExtra("author", job.author);
                it.putExtra("url", job.url);
                it.putExtra("source", job.source);
                startActivity(it);
            }
        }));

        actions.add(smallAction("删除记录", UiUtil.ON_SURFACE_VARIANT, new Runnable() {
            @Override
            public void run() {
                DownloadManager.removeHistory(job.id);
                rebuildDownloadPage();
            }
        }));
        card.addView(actions);
        return card;
    }

    /** 卡片内操作按钮：统一走 UiKit，保证全应用尺寸一致。 */
    private TextView smallAction(String text, int color, Runnable onClick) {
        return UiKit.actionButton(this, text, color, onClick);
    }

    /** 往横向容器里追加按钮：除第一个外统一留 8dp 间距。 */
    private void addGap(LinearLayout row, View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (row.getChildCount() > 0) {
            lp.leftMargin = UiUtil.dp(this, 8);
        }
        v.setLayoutParams(lp);
        row.addView(v);
    }

    private void confirmCancel(final DownloadManager.Job job) {
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("取消下载")
                .setMessage("取消《" + job.bookName + "》的下载？已下载的章节会丢弃。")
                .setPositiveButton("取消下载", (d, w) -> {
                    DownloadManager.cancel(job);
                    Toast.makeText(this, "将在当前章节完成后停止", Toast.LENGTH_SHORT).show();
                    rebuildDownloadPage();
                })
                .setNegativeButton("继续下载", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(UiUtil.ERROR);
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(UiUtil.ON_SURFACE_VARIANT);
    }

    /**
     * 只重下上次失败的章节。
     *
     * <p>上一次任务的 {@code session} 里保留着成功章节的正文，这里复用它、
     * 只补失败下标，相当于断点续传；完成后会覆盖写出完整文件。</p>
     */
    private void retryFailed(final DownloadManager.Job job) {
        List<Integer> failed = DownloadManager.failedIndices(job);
        if (failed.isEmpty()) {
            // 章节都在、只是写文件失败时，这里以前只会甩一句「没有失败章节」，
            // 用户完全不知道该干什么
            Toast.makeText(this, DownloadManager.canResave(job)
                    ? "章节都已下好，只是保存失败——请点「重新保存」"
                    : "没有失败章节", Toast.LENGTH_SHORT).show();
            return;
        }
        if (job.session == null) {
            Toast.makeText(this, "会话数据已失效，请重新下载整本", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "正在重试 " + failed.size() + " 个失败章节…",
                Toast.LENGTH_SHORT).show();
        App.POOL.execute(new Runnable() {
            @Override
            public void run() {
                final BookSource src = findSourceByName(job.source);
                final List<Chapter> chapters;
                try {
                    chapters = src == null ? null : Downloader.loadChapters(src, job.url);
                } catch (final Exception e) {
                    App.UI.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this,
                                    "目录加载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                    return;
                }
                App.UI.post(new Runnable() {
                    @Override
                    public void run() {
                        if (chapters == null || chapters.isEmpty()
                                || chapters.size() != job.session.parts.length) {
                            Toast.makeText(MainActivity.this,
                                    "目录已变化，无法续传，请重新下载整本",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        // 用同一份 session 续跑：成功章节不再重下
                        Downloader.Session reuse = job.session;
                        for (Integer idx : DownloadManager.failedIndices(job)) {
                            if (idx >= 0 && idx < reuse.parts.length) {
                                reuse.parts[idx] = null;
                                reuse.failed[idx] = false;
                            }
                        }
                        // 沿用原来的章节范围与分章设置：以前固定传 0/-1，
                        // 只下过一部分的书会被悄悄重下整本并改写成整本文件
                        DownloadManager.start(
                                MainActivity.this, src,
                                new SearchBook(job.source, job.bookName, job.author, job.url),
                                chapters, DownloadPrefs.threads(MainActivity.this),
                                job.conv, job.format, job.nameTemplate,
                                job.fromIndex, job.toIndex, reuse, job.groupSize);
                        Toast.makeText(MainActivity.this,
                                "已加入续传任务，仅重下 " + failed.size() + " 章",
                                Toast.LENGTH_LONG).show();
                        rebuildDownloadPage();
                    }
                });
            }
        });
    }

    /**
     * 章节都下好了、只是写文件失败时的补救：重新拼装再存一次，不重下。
     *
     * <p>典型触发是「保存失败：UNIQUE constraint failed」这类媒体库冲突，
     * 或者存储空间一时不足。整本重下代价太大（几千章可能几十分钟）。</p>
     */
    private void resave(final DownloadManager.Job job) {
        Toast.makeText(this, "正在重新保存《" + job.bookName + "》…", Toast.LENGTH_SHORT).show();
        DownloadManager.resave(this, job, new DownloadManager.SaveCallback() {
            @Override
            public void onSaved(DownloadManager.Job j, String location) {
                Toast.makeText(MainActivity.this,
                        "已保存到 " + location, Toast.LENGTH_LONG).show();
                rebuildDownloadPage();
            }

            @Override
            public void onFailed(DownloadManager.Job j, String message) {
                Toast.makeText(MainActivity.this,
                        "仍然保存失败：" + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** 分享已导出的文件；找不到文件时退化为展示路径。 */
    private void shareFile(DownloadManager.Job job) {
        boolean epub = job.format == Exporter.FORMAT_EPUB;
        String mime = epub ? "application/epub+zip" : "text/plain";
        Intent it = new Intent(Intent.ACTION_SEND);
        it.setType(mime);

        // file:// 不能交给别的应用（Android 7+ 抛 FileUriExposedException），
        // 所以只认 MediaStore 的 content Uri；拿不到就退化为分享文字说明。
        Uri shareUri = DownloadManager.findShareableUri(this, job);
        if (shareUri != null) {
            it.putExtra(Intent.EXTRA_STREAM, shareUri);
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        it.putExtra(Intent.EXTRA_TEXT, job.location.isEmpty()
                ? ("《" + job.bookName + "》已导出到「下载/小说下载器/」")
                : job.location);
        try {
            startActivity(Intent.createChooser(it, "分享文件"));
        } catch (Exception e) {
            Toast.makeText(this, job.location, Toast.LENGTH_LONG).show();
        }
    }

    // ---- DownloadManager.Listener ----

    @Override
    public void onJobStarted(DownloadManager.Job job) {
        lastDownloadTotal = Math.max(lastDownloadTotal, job.total);
        rebuildDownloadPage();
        Toast.makeText(this, "开始下载《" + job.bookName + "》", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onJobProgress(DownloadManager.Job job) {
        // 进度回调很密集，合帧后再重建，避免每个章节都重铺一遍列表
        if (downloadContent.getVisibility() != View.VISIBLE || pendingRebuild) {
            return;
        }
        pendingRebuild = true;
        App.UI.postDelayed(rebuildTick, 120);
    }

    @Override
    public void onJobFinished(DownloadManager.Job job) {
        pendingRebuild = false;
        App.UI.removeCallbacks(rebuildTick);
        rebuildDownloadPage();
        if (job.status == DownloadManager.DONE) {
            String extra = job.failedChapters > 0
                    ? ("（" + job.failedChapters + " 章失败，可在列表里重试）") : "";
            Toast.makeText(this, "《" + job.bookName + "》下载完成" + extra
                    + "，已保存到 " + job.location, Toast.LENGTH_LONG).show();
        } else if (job.status == DownloadManager.FAILED) {
            Toast.makeText(this, "《" + job.bookName + "》下载失败："
                    + job.message, Toast.LENGTH_LONG).show();
        }
    }

    // ==================================================================
    //  设置页
    // ==================================================================

    private View buildSettingsContent() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiUtil.SURFACE);
        sv.setClipToPadding(false);
        sv.setVerticalScrollBarEnabled(false);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int side = UiUtil.dp(this, 16);
        sv.addView(col);

        // ---- 下载设置 ----
        col.addView(groupLabel("下载"));
        LinearLayout cardDownload = UiKit.card(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.setMargins(side, 0, side, 0);
        cardDownload.setLayoutParams(dlp);

        threadsValue = valueText();
        cardDownload.addView(settingValueRow("下载线程数",
                "单个任务并发抓取的章数，1 - " + Downloader.MAX_THREADS,
                threadsValue, new Runnable() {
                    @Override
                    public void run() {
                        pickThreads();
                    }
                }));
        cardDownload.addView(UiKit.spacer(this, 8));

        convValue = valueText();
        cardDownload.addView(settingValueRow("文字转换",
                "导出前统一转换繁简",
                convValue, new Runnable() {
                    @Override
                    public void run() {
                        pickConv();
                    }
                }));
        cardDownload.addView(UiKit.spacer(this, 8));

        formatValue = valueText();
        cardDownload.addView(settingValueRow("导出格式",
                "单文件 TXT / 分章 TXT / EPUB",
                formatValue, new Runnable() {
                    @Override
                    public void run() {
                        pickFormat();
                    }
                }));
        cardDownload.addView(UiKit.spacer(this, 8));

        templateValue = valueText();
        cardDownload.addView(settingValueRow("文件名模板",
                "支持 {title} {author} {source} {count} {date} {format}",
                templateValue, new Runnable() {
                    @Override
                    public void run() {
                        editTemplate();
                    }
                }));
        cardDownload.addView(UiKit.spacer(this, 8));

        splitGroupValue = valueText();
        cardDownload.addView(settingValueRow("分章文件大小",
                "选择分章 TXT 时，每个文件装多少章",
                splitGroupValue, new Runnable() {
                    @Override
                    public void run() {
                        pickSplitGroup();
                    }
                }));
        col.addView(cardDownload);

        // ---- 书源 ----
        col.addView(groupLabel("书源"));
        LinearLayout cardSource = UiKit.card(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(side, 0, side, 0);
        cardSource.setLayoutParams(clp);

        sourcesInfo = UiUtil.text(this, "", UiUtil.TYPE_BODY_SMALL, UiUtil.ON_SURFACE_VARIANT);
        sourcesInfo.setPadding(0, 0, 0, UiUtil.dp(this, 10));
        cardSource.addView(sourcesInfo);

        cardSource.addView(UiKit.settingRow(this, "导入书源",
                "粘贴 JSON 或从剪贴板读取", new Runnable() {
                    @Override
                    public void run() {
                        startActivity(new Intent(MainActivity.this,
                                ImportSourcesActivity.class));
                    }
                }));
        cardSource.addView(UiKit.spacer(this, 8));
        cardSource.addView(UiKit.settingRow(this, "启用 / 停用书源",
                "勾选参与搜索的书源，未勾选的仍可用于链接打开", new Runnable() {
                    @Override
                    public void run() {
                        manageSources();
                    }
                }));
        cardSource.addView(UiKit.spacer(this, 8));
        cardSource.addView(UiKit.settingRow(this, "书源连通性测试",
                "逐个试搜一次，看规则是否仍然可用", new Runnable() {
                    @Override
                    public void run() {
                        testSources();
                    }
                }));
        cardSource.addView(UiKit.spacer(this, 8));
        cardSource.addView(UiKit.settingRow(this, "导出书源备份",
                "把全部书源导出成 JSON 存到下载目录", new Runnable() {
                    @Override
                    public void run() {
                        exportSources();
                    }
                }));
        cardSource.addView(UiKit.spacer(this, 8));
        cardSource.addView(UiKit.settingRow(this, "清空自定义书源",
                "只删除导入的书源，内置书源保留", new Runnable() {
                    @Override
                    public void run() {
                        confirmClearUser();
                    }
                }));
        col.addView(cardSource);

        // ---- 应用 ----
        col.addView(groupLabel("应用"));
        LinearLayout cardAbout = UiKit.card(this);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.setMargins(side, 0, side, UiUtil.dp(this, 20));
        cardAbout.setLayoutParams(alp);

        settingsVersion = UiUtil.text(this, "", UiUtil.TYPE_BODY_SMALL, UiUtil.ON_SURFACE_VARIANT);
        settingsVersion.setPadding(0, 0, 0, UiUtil.dp(this, 10));
        cardAbout.addView(settingsVersion);

        cardAbout.addView(UiKit.settingRow(this, "本地书库",
                "查看已下载的小说并直接阅读", new Runnable() {
                    @Override
                    public void run() {
                        startActivity(new Intent(MainActivity.this, LibraryActivity.class));
                    }
                }));
        cardAbout.addView(UiKit.spacer(this, 8));
        cardAbout.addView(UiKit.settingRow(this, "关于",
                App.APP_NAME + " · " + App.CREDIT, new Runnable() {
                    @Override
                    public void run() {
                        startActivity(new Intent(MainActivity.this, AboutActivity.class));
                    }
                }));
        col.addView(cardAbout);

        return sv;
    }

    /** 带右侧当前值的设置行。 */
    private LinearLayout settingValueRow(String title, String subtitle, TextView value,
                                        Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiUtil.dp(this, 64));
        row.setBackground(UiUtil.ripple(UiUtil.ON_SURFACE,
                UiUtil.round(UiUtil.SURFACE_CONTAINER_HIGH, UiUtil.SHAPE_MEDIUM, this), this));
        int h = UiUtil.dp(this, 16);
        int v = UiUtil.dp(this, 12);
        row.setPadding(h, v, h, v);
        row.setOnClickListener(x -> onClick.run());

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(UiUtil.text(this, title, UiUtil.TYPE_TITLE_MEDIUM, UiUtil.ON_SURFACE));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = UiUtil.text(this, subtitle, UiUtil.TYPE_BODY_SMALL,
                    UiUtil.ON_SURFACE_VARIANT);
            s.setPadding(0, UiUtil.dp(this, 2), 0, 0);
            col.addView(s);
        }
        row.addView(col);

        value.setTypeface(Typeface.DEFAULT_BOLD);
        value.setPadding(UiUtil.dp(this, 8), 0, 0, 0);
        row.addView(value);
        return row;
    }

    /** 导出格式单选。 */
    private void pickFormat() {
        final int[] values = {Exporter.FORMAT_TXT, Exporter.FORMAT_SPLIT,
                Exporter.FORMAT_EPUB};
        String[] names = Exporter.formatNames();
        int cur = DownloadPrefs.format(this);
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == cur) {
                checked = i;
            }
        }
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("导出格式")
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    DownloadPrefs.setFormat(this, values[which]);
                    refreshSettingsInfo();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(UiUtil.ON_SURFACE_VARIANT);
    }

    /**
     * 选择分章 TXT 每个文件包含多少章。
     *
     * <p>只对「分章 TXT」格式有意义，所以顶部会顺手提示当前导出格式。</p>
     */
    private void pickSplitGroup() {
        final int[] presets = {1, 5, 10, 20, 50, 100};
        String[] names = new String[presets.length];
        for (int i = 0; i < presets.length; i++) {
            names[i] = DownloadPrefs.splitGroupName(presets[i]);
        }
        int cur = DownloadPrefs.splitGroup(this);
        int checked = 0;
        for (int i = 0; i < presets.length; i++) {
            if (presets[i] == cur) {
                checked = i;
            }
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiUtil.dp(this, 4), UiUtil.dp(this, 4), UiUtil.dp(this, 4), 0);
        box.addView(UiUtil.text(this,
                "当前导出格式：" + Exporter.formatName(DownloadPrefs.format(this))
                        + "。这项设置只在「分章 TXT」下生效。\n"
                        + "一本两千章的书，选「每章一个文件」会产出两千个文件，"
                        + "建议 10 - 50 章合并成一个。",
                UiUtil.TYPE_BODY_SMALL, UiUtil.ON_SURFACE_VARIANT));

        // 超长书选「每章一个文件」会产出几千个文件，先给出预警而不是直接照做
        final int lastTotal = lastDownloadTotal;
        if (lastTotal > 300 && cur <= 1) {
            TextView warn = UiUtil.text(this,
                    "提示：最近一次下载共 " + lastTotal + " 章，"
                            + "按当前设置会产出约 " + lastTotal + " 个文件。",
                    UiUtil.TYPE_LABEL_SMALL, UiUtil.ERROR);
            warn.setPadding(0, UiUtil.dp(this, 8), 0, 0);
            box.addView(warn);
        }

        final EditText et = UiKit.textField(this, "10", InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(cur));
        et.setSelection(et.getText().length());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiUtil.dp(this, 10);
        et.setLayoutParams(lp);
        box.addView(et);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("分章文件大小")
                .setView(box)
                .setSingleChoiceItems(names, checked, null)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(UiUtil.PRIMARY);
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(UiUtil.ON_SURFACE_VARIANT);
        // 单选列表与输入框二选一：点了列表就用列表值，没点就用输入框的值
        final int[] picked = {-1};
        dlg.getListView().setOnItemClickListener((parent, view, position, id) -> {
            picked[0] = presets[position];
            et.setText(String.valueOf(presets[position]));
        });
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int n = picked[0];
            if (n < 0) {
                try {
                    n = Integer.parseInt(et.getText().toString().trim());
                } catch (Exception e) {
                    n = DownloadPrefs.SPLIT_GROUP_DEFAULT;
                }
            }
            DownloadPrefs.setSplitGroup(this, n);
            refreshSettingsInfo();
            Toast.makeText(this, "已设为" + DownloadPrefs.splitGroupName(
                    DownloadPrefs.splitGroup(this)), Toast.LENGTH_SHORT).show();
            dlg.dismiss();
        });
    }

    /** 编辑文件名模板，并实时预览效果。 */
    private void editTemplate() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiUtil.dp(this, 4), UiUtil.dp(this, 4), UiUtil.dp(this, 4), 0);
        box.addView(UiUtil.text(this, Exporter.TEMPLATE_HINT, UiUtil.TYPE_BODY_SMALL,
                UiUtil.ON_SURFACE_VARIANT));

        final EditText et = UiKit.textField(this, Exporter.DEFAULT_TEMPLATE,
                InputType.TYPE_CLASS_TEXT);
        et.setText(DownloadPrefs.template(this));
        et.setSelection(et.getText().length());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiUtil.dp(this, 10);
        et.setLayoutParams(lp);
        box.addView(et);

        final TextView preview = UiUtil.text(this, "", UiUtil.TYPE_BODY_SMALL, UiUtil.PRIMARY);
        preview.setPadding(0, UiUtil.dp(this, 10), 0, 0);
        box.addView(preview);
        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                SearchBook demo = new SearchBook("", "剑帝", "捕快A", "");
                String name = Exporter.buildFileName(et.getText().toString(), demo, "书客吧",
                        150, DownloadPrefs.format(MainActivity.this), false);
                preview.setText("预览：" + name + Exporter.extension(
                        DownloadPrefs.format(MainActivity.this)));
            }
        };
        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                refresh.run();
            }
        });
        refresh.run();

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("文件名模板")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    DownloadPrefs.setTemplate(this, et.getText().toString());
                    refreshSettingsInfo();
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(UiUtil.PRIMARY);
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(UiUtil.ON_SURFACE_VARIANT);
    }

    /** 把全部书源导出成 JSON 写到下载目录，并提示位置。 */
    private void exportSources() {
        App.POOL.execute(new Runnable() {
            @Override
            public void run() {
                final String json = SourceTools.exportJson(MainActivity.this);
                String msg;
                try {
                    String loc = Exporter.writeFile(MainActivity.this,
                            SourceTools.exportFileName(), "application/json",
                            json.getBytes("utf-8"));
                    msg = "已导出到 " + loc;
                } catch (Exception e) {
                    msg = "导出失败：" + e.getMessage();
                }
                final String m = msg;
                App.UI.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    /** 逐个测试书源连通性，结果显示在一个可滚动弹窗里。 */
    private void testSources() {
        final List<BookSource> sources = SourceStore.searchable(this);
        if (sources.isEmpty()) {
            Toast.makeText(this, "没有可测试的书源", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(UiUtil.dp(this, 4), UiUtil.dp(this, 4), UiUtil.dp(this, 4), 0);
        col.addView(UiUtil.text(this, "用关键字「剑」逐个试搜一次，"
                        + "若显示规则可用但无结果，可能是该书源已被站点改版。",
                UiUtil.TYPE_BODY_SMALL, UiUtil.ON_SURFACE_VARIANT));

        ScrollView sv = new ScrollView(this);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiUtil.dp(this, 320));
        slp.topMargin = UiUtil.dp(this, 10);
        sv.setLayoutParams(slp);
        col.addView(sv);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("书源连通性测试")
                .setView(col)
                .setPositiveButton("关闭", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(UiUtil.PRIMARY);

        for (final BookSource src : sources) {
            final TextView row = UiUtil.text(this, src.name + " · 等待中…",
                    UiUtil.TYPE_BODY_SMALL, UiUtil.ON_SURFACE_VARIANT);
            row.setPadding(0, UiUtil.dp(this, 6), 0, UiUtil.dp(this, 6));
            list.addView(row);
            SourceTools.test(src, "剑", new SourceTools.TestCallback() {
                @Override
                public void onResult(SourceTools.TestResult r) {
                    String text = src.name + " · " + r.message;
                    if (r.ok && !r.sample.isEmpty()) {
                        StringBuilder sb = new StringBuilder(text).append("（");
                        for (int i = 0; i < r.sample.size(); i++) {
                            if (i > 0) {
                                sb.append("、");
                            }
                            sb.append(r.sample.get(i));
                        }
                        text = sb.append("）").toString();
                    }
                    row.setText(text);
                    row.setTextColor(r.ok ? UiUtil.PRIMARY : UiUtil.ERROR);
                }
            });
        }
    }

    private TextView valueText() {
        return UiUtil.text(this, "", UiUtil.TYPE_BODY_MEDIUM, UiUtil.PRIMARY);
    }

    private void pickThreads() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiUtil.dp(this, 4), UiUtil.dp(this, 4), UiUtil.dp(this, 4), 0);
        box.addView(UiUtil.text(this, "并发抓取章节的线程数。数值越大越快，但也更容易被站点限流（1 - "
                + Downloader.MAX_THREADS + "）。", UiUtil.TYPE_BODY_MEDIUM,
                UiUtil.ON_SURFACE_VARIANT));
        final EditText et = UiKit.textField(this, String.valueOf(Downloader.DEFAULT_THREADS),
                InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(DownloadPrefs.threads(this)));
        et.setSelection(et.getText().length());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiUtil.dp(this, 10);
        et.setLayoutParams(lp);
        box.addView(et);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("下载线程数")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    int n;
                    try {
                        n = Integer.parseInt(et.getText().toString().trim());
                    } catch (Exception e) {
                        n = Downloader.DEFAULT_THREADS;
                    }
                    DownloadPrefs.setThreads(this, n);
                    refreshSettingsInfo();
                    Toast.makeText(this, "已设为 " + DownloadPrefs.threads(this) + " 线程",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(UiUtil.PRIMARY);
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(UiUtil.ON_SURFACE_VARIANT);
    }

    private void pickConv() {
        final int[] modes = {CharConv.NONE, CharConv.T2S, CharConv.S2T};
        String[] names = {"不转换", "繁 → 简", "简 → 繁"};
        int cur = DownloadPrefs.conv(this);
        int checked = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] == cur) {
                checked = i;
            }
        }
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("文字转换")
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    DownloadPrefs.setConv(this, modes[which]);
                    refreshSettingsInfo();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(UiUtil.ON_SURFACE_VARIANT);
    }

    private void refreshSettingsInfo() {
        if (sourcesInfo == null) {
            return;
        }
        int all = SourceStore.all(this).size();
        int enabled = SourceStore.load(this).size();
        int custom = SourceStore.userCount(this);
        sourcesInfo.setText("共 " + all + " 个（内置 " + (all - custom) + " · 自定义 " + custom
                + "） · 已启用 " + enabled + " 个");
        if (settingsVersion != null) {
            settingsVersion.setText("版本 " + App.version(this)
                    + " · 纯本地运行，无广告、无联网上报");
        }
        if (threadsValue != null) {
            threadsValue.setText(DownloadPrefs.threads(this) + " 线程");
        }
        if (convValue != null) {
            convValue.setText(DownloadPrefs.convName(DownloadPrefs.conv(this)));
        }
        if (formatValue != null) {
            formatValue.setText(Exporter.formatName(DownloadPrefs.format(this)));
        }
        if (templateValue != null) {
            templateValue.setText(DownloadPrefs.template(this));
            templateValue.setSingleLine(true);
            templateValue.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        }
        if (splitGroupValue != null) {
            splitGroupValue.setText(DownloadPrefs.splitGroupName(
                    DownloadPrefs.splitGroup(this)));
        }
    }

    // ==================================================================
    //  导入书源
    // ==================================================================

    // ==================================================================
    //  书源管理
    // ==================================================================

    private void manageSources() {
        final List<BookSource> all = SourceStore.all(this);
        if (all.isEmpty()) {
            Toast.makeText(this, "暂无书源", Toast.LENGTH_SHORT).show();
            return;
        }
        final Set<String> builtin = SourceStore.builtinNames(this);
        final Set<String> disabled = SourceStore.disabledNames(this);

        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(UiUtil.dp(this, 4), UiUtil.dp(this, 4), UiUtil.dp(this, 4), 0);

        final List<CheckBox> boxes = new ArrayList<>();
        for (BookSource s : all) {
            CheckBox cb = new CheckBox(this);
            cb.setText(s.name + (builtin.contains(s.name) ? "（内置）" : "（自定义）"));
            cb.setChecked(!disabled.contains(s.name));
            cb.setTextSize(UiUtil.TYPE_BODY_LARGE);
            cb.setTextColor(UiUtil.ON_SURFACE);
            cb.setButtonTintList(ColorStateList.valueOf(UiUtil.PRIMARY));
            int vp = UiUtil.dp(this, 6);
            cb.setPadding(0, vp, 0, vp);
            boxes.add(cb);
            col.addView(cb);
        }
        sv.addView(col);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("启用 / 停用书源")
                .setView(sv)
                .setPositiveButton("保存", (d, w) -> {
                    Set<String> off = new HashSet<>();
                    for (int i = 0; i < boxes.size(); i++) {
                        if (!boxes.get(i).isChecked()) {
                            off.add(all.get(i).name);
                        }
                    }
                    SourceStore.setDisabledNames(this, off);
                    refreshSettingsInfo();
                    refreshSources();
                    Toast.makeText(this, "已保存，当前启用 " + (boxes.size() - off.size())
                            + " 个书源", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(UiUtil.PRIMARY);
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(UiUtil.ON_SURFACE_VARIANT);
    }

    private void importFromClipboard() {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.ClipData cd = cm.getPrimaryClip();
        if (cd == null || cd.getItemCount() == 0) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show();
            return;
        }
        String text = String.valueOf(cd.getItemAt(0).coerceToText(this));
        Object[] res = SourceStore.importJson(this, text);
        int ok = (Integer) res[0];
        String err = (String) res[1];
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_LONG).show();
        } else if (ok == 0) {
            Toast.makeText(this, "没有解析到有效书源（需含 contentRule，且含 searchUrl+listRule 或 indexChapters）",
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "成功导入 " + ok + " 个书源", Toast.LENGTH_LONG).show();
        }
        refreshSettingsInfo();
        refreshSources();
    }

    private void confirmClearUser() {
        if (SourceStore.userCount(this) == 0) {
            Toast.makeText(this, "没有自定义书源", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("清空自定义书源")
                .setMessage("将删除所有导入的书源（内置书源保留）。")
                .setPositiveButton("清空", (d, w) -> {
                    SourceStore.clearUser(this);
                    refreshSettingsInfo();
                    refreshSources();
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(UiUtil.ERROR);
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(UiUtil.ON_SURFACE_VARIANT);
    }

    // ==================================================================
    //  偏好
    // ==================================================================

    private SharedPreferences prefs() {
        return getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private void saveSelected() {
        JSONArray a = new JSONArray();
        for (String n : selected) {
            a.put(n);
        }
        prefs().edit().putString(KEY_SEL, a.toString()).apply();
    }

    private Set<String> loadSelected() {
        String s = prefs().getString(KEY_SEL, null);
        if (s == null) {
            return null;
        }
        Set<String> set = new LinkedHashSet<>();
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                set.add(a.optString(i));
            }
        } catch (Exception ignored) {
        }
        return set;
    }

    // ==================================================================
    //  链接打开
    // ==================================================================

    private void openByLink() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = UiUtil.dp(this, 4);
        box.setPadding(p, UiUtil.dp(this, 4), p, 0);
        box.addView(UiUtil.text(this, "粘贴书籍详情页链接，可一次粘贴多行（每行一个），"
                + "解析后勾选要打开的书。\n例：\nhttps://…/novel.html?articleid=…",
                UiUtil.TYPE_BODY_MEDIUM, UiUtil.ON_SURFACE_VARIANT));
        final EditText input = UiKit.textField(this,
                "https://…/novel.html?articleid=…", InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(false);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(3);
        input.setMaxLines(6);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = UiUtil.dp(this, 8);
        input.setLayoutParams(ilp);
        box.addView(input);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("链接打开")
                .setView(box)
                .setPositiveButton("解析", (d, w) -> parseLinks(input.getText().toString()))
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(UiUtil.PRIMARY);
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(UiUtil.ON_SURFACE_VARIANT);
    }

    private void parseLinks(String text) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (String tok : text.split("\\s+")) {
            String t = tok.trim();
            if (t.startsWith("http://") || t.startsWith("https://")) {
                urls.add(t);
            }
        }
        if (urls.isEmpty()) {
            status.setText("没有识别到 http(s) 链接");
            return;
        }
        List<BookSource> sources = SourceStore.all(this);
        final List<String[]> matched = new ArrayList<>();
        int unmatched = 0;
        for (String u : urls) {
            BookSource hit = null;
            for (BookSource s : sources) {
                if (s.matches(u)) {
                    hit = s;
                    break;
                }
            }
            if (hit == null) {
                unmatched++;
                continue;
            }
            matched.add(new String[]{u, hit.name, hit.charset});
        }
        if (matched.isEmpty()) {
            status.setText("没有匹配的书源（" + unmatched + " 个链接的域名不在内置/自定义书源中）");
            return;
        }
        if (matched.size() == 1) {
            launchDetail(matched.get(0)[0], matched.get(0)[1], matched.get(0)[2]);
            return;
        }
        showBatchPicker(matched, unmatched);
    }

    private void showBatchPicker(final List<String[]> items, int unmatched) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(UiUtil.dp(this, 4), UiUtil.dp(this, 4), UiUtil.dp(this, 4), 0);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView info = UiUtil.text(this, "识别到 " + items.size() + " 个链接"
                + (unmatched > 0 ? "（另有 " + unmatched + " 个未匹配书源）" : ""),
                UiUtil.TYPE_BODY_MEDIUM, UiUtil.ON_SURFACE_VARIANT);
        info.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(info);
        final TextView selAll = smallAction("全不选", UiUtil.PRIMARY, new Runnable() {
            @Override
            public void run() {
            }
        });
        head.addView(selAll);
        col.addView(head);

        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        final List<CheckBox> boxes = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            final String[] it = items.get(i);
            CheckBox cb = new CheckBox(this);
            cb.setText((i + 1) + ". " + it[1] + " · " + shortUrl(it[0]));
            cb.setChecked(true);
            cb.setTextSize(UiUtil.TYPE_BODY_MEDIUM);
            cb.setTextColor(UiUtil.ON_SURFACE);
            cb.setButtonTintList(ColorStateList.valueOf(UiUtil.PRIMARY));
            int vp = UiUtil.dp(this, 6);
            cb.setPadding(0, vp, 0, vp);
            boxes.add(cb);
            list.addView(cb);
        }
        col.addView(sv);

        selAll.setOnClickListener(v -> {
            boolean allChecked = true;
            for (CheckBox b : boxes) {
                if (!b.isChecked()) {
                    allChecked = false;
                    break;
                }
            }
            boolean target = !allChecked;
            for (CheckBox b : boxes) {
                b.setChecked(target);
            }
            selAll.setText(target ? "全不选" : "全选");
        });

        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            final String[] it = items.get(i);
            App.POOL.execute(new Runnable() {
                @Override
                public void run() {
                    final String title = fetchTitle(it[0], it[2]);
                    if (title == null || title.isEmpty()) {
                        return;
                    }
                    App.UI.post(new Runnable() {
                        @Override
                        public void run() {
                            if (idx < boxes.size()) {
                                boxes.get(idx).setText((idx + 1) + ". " + title);
                            }
                        }
                    });
                }
            });
        }

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("选择要打开的书")
                .setView(col)
                .setPositiveButton("打开", (d, w) -> {
                    List<String[]> chosen = new ArrayList<>();
                    for (int i = 0; i < boxes.size(); i++) {
                        if (boxes.get(i).isChecked()) {
                            chosen.add(items.get(i));
                        }
                    }
                    if (chosen.isEmpty()) {
                        Toast.makeText(this, "请至少勾选一本", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (chosen.size() == 1) {
                        launchDetail(chosen.get(0)[0], chosen.get(0)[1], chosen.get(0)[2]);
                    } else {
                        startBatch(chosen);
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(UiUtil.PRIMARY);
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(UiUtil.ON_SURFACE_VARIANT);
    }

    private void startBatch(List<String[]> items) {
        batchQueue.clear();
        batchQueue.addAll(items);
        batchActive = true;
        openNextBatch();
    }

    private void openNextBatch() {
        if (batchQueue.isEmpty()) {
            batchActive = false;
            status.setText("批量打开完成");
            return;
        }
        String[] it = batchQueue.remove(0);
        int remain = batchQueue.size();
        status.setText("批量打开中，还剩 " + remain + " 本待打开");
        launchDetail(it[0], it[1], it[2]);
    }

    private void launchDetail(final String url, final String sourceName, final String charset) {
        status.setText("正在打开链接…");
        App.POOL.execute(new Runnable() {
            @Override
            public void run() {
                final String title = fetchTitle(url, charset);
                App.UI.post(new Runnable() {
                    @Override
                    public void run() {
                        Intent it = new Intent(MainActivity.this, DetailActivity.class);
                        it.putExtra("name", title);
                        it.putExtra("author", "");
                        it.putExtra("url", url);
                        it.putExtra("source", sourceName);
                        startActivity(it);
                    }
                });
            }
        });
    }

    private static String shortUrl(String url) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("articleid=(\\d+)").matcher(url);
            if (m.find()) {
                return "id=" + m.group(1);
            }
        } catch (Exception ignored) {
        }
        if (url.length() <= 28) {
            return url;
        }
        return "…" + url.substring(url.length() - 26);
    }

    private static String fetchTitle(String url, String charset) {
        try {
            String html = Http.get(url, charset);
            String t = Rules.first(html, "<title[^>]*>([^<]*)</title>");
            if (t == null) {
                return "";
            }
            t = t.trim();
            for (String sep : new String[]{"_", "|", " - ", " – ", " — ", "-", "—"}) {
                int i = t.indexOf(sep);
                if (i > 0) {
                    t = t.substring(0, i).trim();
                    break;
                }
            }
            return t;
        } catch (Exception e) {
            return "";
        }
    }

    /** 进入 / 退出本页时的转场动画（淡入淡出，避免生硬跳变）。 */
    private void applyTransitions() {
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    public void finish() {
        super.finish();
        applyTransitions();
    }
}
