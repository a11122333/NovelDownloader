package com.niganma.noveldown;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 阅读器：把已导出的 TXT 读进来按章展示，可调字号 / 行距 / 底色，并记住上次读到哪。
 *
 * <p>整本书始终只保留一份字符串，章节只记在正文里的起止下标，显示时才截取。
 * 一本几百万字的小说如果按章切成上千个 String，内存要翻一倍，低端机上很容易
 * 被系统直接杀掉。</p>
 *
 * <p>读取先走 content Uri 再走文件路径：Android 10 起书库里的记录可能只有
 * MediaStore 那条通道能打开；反过来老系统上 Uri 又不一定存在。</p>
 */
public class ReaderActivity extends Activity {

    private static final String PREF = "reader";
    /** 阅读偏好在同一份 SharedPreferences 里，全局共用（不分书）。 */
    private static final String KEY_FONT = "font_size";
    private static final String KEY_LINE = "line_tenths";
    private static final String KEY_THEME = "theme";
    /** 进度键前缀，完整键是 "pos:" + 书名。 */
    private static final String KEY_POS = "pos:";

    private static final int FONT_MIN = 12;
    private static final int FONT_MAX = 30;
    private static final int FONT_DEFAULT = 18;

    /** 行距倍数放大 10 倍存整数，避免浮点累积误差：10 表示 1.0 倍。 */
    private static final int LINE_MIN = 10;
    private static final int LINE_MAX = 20;
    private static final int LINE_DEFAULT = 15;

    /** 识别不到章节时按这个字数切块。 */
    private static final int BLOCK_CHARS = 3000;
    /** 切块时最多往后多带这么多字符去找换行，避免把一行从中间劈开。 */
    private static final int BLOCK_TAIL = 500;

    /**
     * 章节标题行：第X章 / 第X节 / 第X卷 / 第X回 / 第X篇，或 Chapter N。
     *
     * <p>整行匹配（首尾锚定 + 行尾最多 50 字）是刻意的：正文里常出现
     * 「第一章讲的是……」这种句子，不限制长度会把普通段落当成章节标题，
     * 目录立刻就被撑爆。</p>
     */
    private static final Pattern CHAPTER_LINE = Pattern.compile(
            "^[\\s\\u3000]*(?:第[\\s\\u3000]*[0-9零一二三四五六七八九十百千万两〇]{1,12}"
            + "[\\s\\u3000]*[章节卷回篇]"
            + "|Chapter[\\s\\u3000]*[0-9]{1,5})[^\\n]{0,50}$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    /** 一章在正文里的位置；标题行本身就属于这一章。 */
    private static final class Section {
        final String title;
        /** 是否要在显示时补上标题（原文没有标题行的自动分块、前言）。 */
        final boolean needsTitle;
        final int start;
        final int end;

        Section(String title, boolean needsTitle, int start, int end) {
            this.title = title;
            this.needsTitle = needsTitle;
            this.start = start;
            this.end = end;
        }
    }

    /** 阅读底色。深色底必须配浅色字，所以文字色也一起定义在主题里。 */
    private static final class Theme {
        final String name;
        final int bg;
        final int fg;
        final int sub;
        final int accent;
        final boolean dark;

        Theme(String name, int bg, int fg, int sub, int accent, boolean dark) {
            this.name = name;
            this.bg = bg;
            this.fg = fg;
            this.sub = sub;
            this.accent = accent;
            this.dark = dark;
        }
    }

    private static final Theme[] THEMES = {
            new Theme("白", 0xFFFFFFFF, 0xFF1B1B1F, 0xFF45464F, UiUtil.PRIMARY, false),
            new Theme("米黄", 0xFFF5EFDC, 0xFF3B3325, 0xFF6B5F49, 0xFF8A6A2F, false),
            new Theme("绿", 0xFFCCE8CF, 0xFF1E3320, 0xFF3F5B42, 0xFF2E6B36, false),
            new Theme("深色", 0xFF121212, 0xFFE6E1E5, 0xFFB0AAB2, 0xFFB5C2FF, true),
    };

    private String bookName;
    private String path;
    private String uri;

    /** 全文只留一份，章节用下标引用它。 */
    private String text = "";
    private List<Section> sections = new ArrayList<>();
    private int current;

    private int fontSize = FONT_DEFAULT;
    private int lineTenths = LINE_DEFAULT;
    private int themeIndex;

    private LinearLayout root;
    private LinearLayout header;
    private TextView tocBtn;
    private TextView aaBtn;
    private ScrollView scroll;
    private TextView content;
    private LinearLayout bottom;
    private TextView prevBtn;
    private TextView nextBtn;
    private TextView progress;
    private View centerBox;
    private TextView centerText;
    private TextView centerBtn;

    private TextView fontValue;
    private TextView lineValue;
    private final List<TextView> themeChips = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.applyStatusBar(this);

        bookName = getIntent().getStringExtra("name");
        path = getIntent().getStringExtra("path");
        uri = getIntent().getStringExtra("uri");
        if (bookName == null || bookName.isEmpty()) {
            bookName = "未命名";
        }

        loadSettings();
        setContentView(buildUi());
        applyTheme();
        load();
        applyTransitions();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 离开页面时记下精确的滚动位置，下次接着读
        saveProgress(current, scroll == null ? 0 : scroll.getScrollY());
    }

    // ================= 界面 =================

    private View buildUi() {
        Theme t = THEMES[themeIndex];

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(t.bg);

        header = UiKit.backHeader(this, bookName, new Runnable() {
            @Override
            public void run() {
                finish();
            }
        });
        tocBtn = UiKit.textButton(this, "目录", new Runnable() {
            @Override
            public void run() {
                showToc();
            }
        });
        aaBtn = UiKit.textButton(this, "Aa", new Runnable() {
            @Override
            public void run() {
                showSettings();
            }
        });
        header.addView(tocBtn);
        header.addView(aaBtn);
        root.addView(header);

        FrameLayout stage = new FrameLayout(this);
        stage.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        scroll = new ScrollView(this);
        scroll.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(t.bg);
        content = UiUtil.text(this, "", fontSize, t.fg);
        content.setLineSpacing(0, lineTenths / 10f);
        content.setPadding(UiUtil.dp(this, 20), UiUtil.dp(this, 16),
                UiUtil.dp(this, 20), UiUtil.dp(this, 32));
        scroll.addView(content);
        stage.addView(scroll);

        stage.addView(buildCenterBox());

        root.addView(stage);

        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setBackgroundColor(t.bg);
        bottom.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UiUtil.dp(this, 56)));

        prevBtn = UiKit.textButton(this, "上一章", new Runnable() {
            @Override
            public void run() {
                jump(current - 1);
            }
        });
        nextBtn = UiKit.textButton(this, "下一章", new Runnable() {
            @Override
            public void run() {
                jump(current + 1);
            }
        });
        progress = UiUtil.text(this, "", UiUtil.TYPE_LABEL_MEDIUM, t.sub);
        progress.setGravity(Gravity.CENTER);
        progress.setSingleLine(true);
        progress.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        bottom.addView(prevBtn);
        bottom.addView(progress);
        bottom.addView(nextBtn);
        root.addView(bottom);

        return root;
    }

    /** 居中提示区：加载中 / 读取失败都复用它。 */
    private View buildCenterBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        int p = UiUtil.dp(this, 32);
        box.setPadding(p, p, p, p);
        box.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        box.setVisibility(View.GONE);

        centerText = UiUtil.text(this, "", UiUtil.TYPE_BODY_MEDIUM, UiUtil.ON_SURFACE_VARIANT);
        centerText.setGravity(Gravity.CENTER);
        centerText.setLineSpacing(UiUtil.dp(this, 4), 1f);
        box.addView(centerText);

        centerBtn = UiKit.textButton(this, "返回", new Runnable() {
            @Override
            public void run() {
                finish();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = UiUtil.dp(this, 12);
        centerBtn.setLayoutParams(blp);
        box.addView(centerBtn);

        centerBox = box;
        return box;
    }

    private void showCenter(String message, boolean withButton) {
        centerText.setText(message);
        centerBtn.setVisibility(withButton ? View.VISIBLE : View.GONE);
        centerBox.setVisibility(View.VISIBLE);
        scroll.setVisibility(View.GONE);
        updateBottom();
    }

    // ================= 读取与切章 =================

    /** 读取结果：text 非空表示成功；失败时 error 是给用户看的原因。 */
    private static final class LoadResult {
        String text;
        String error;
    }

    private void load() {
        showCenter("正在加载…", false);
        App.POOL.execute(new Runnable() {
            @Override
            public void run() {
                final LoadResult r = readText();
                final List<Section> secs = r.text == null ? null : splitSections(r.text);
                App.UI.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (r.text == null) {
                            showCenter(r.error == null ? "读取失败。" : r.error, true);
                            return;
                        }
                        if (secs == null || secs.isEmpty()) {
                            showCenter("这个文件里没有正文。", true);
                            return;
                        }
                        text = r.text;
                        sections = secs;
                        scroll.setVisibility(View.VISIBLE);
                        centerBox.setVisibility(View.GONE);
                        int[] pos = loadProgress();
                        showChapter(pos[0], pos[1]);
                    }
                });
            }
        });
    }

    private LoadResult readText() {
        LoadResult r = new LoadResult();
        byte[] data = null;
        String error = null;

        if (uri != null && !uri.isEmpty()) {
            InputStream in = null;
            try {
                in = getContentResolver().openInputStream(Uri.parse(uri));
                if (in != null) {
                    data = readAll(in);
                }
            } catch (Exception e) {
                error = "系统媒体库读不到这个文件：" + e.getMessage();
            } finally {
                closeQuietly(in);
            }
        }

        // Uri 通道也可能给出一个空流（占位条目），这种情况同样换路径再试
        if ((data == null || data.length == 0) && path != null && !path.isEmpty()) {
            InputStream in = null;
            try {
                in = new FileInputStream(path);
                data = readAll(in);
                error = null;
            } catch (Exception e) {
                error = "读不到文件：" + e.getMessage();
            } finally {
                closeQuietly(in);
            }
        }

        if (data == null) {
            r.error = error == null ? "这本书没有可用的文件位置（可能已被删除）。" : error;
            return r;
        }
        if (data.length == 0) {
            r.error = "文件是空的。";
            return r;
        }
        r.text = decode(data);
        if (r.text.isEmpty()) {
            r.error = "文件里没有可显示的文本。";
            r.text = null;
        }
        return r;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 按 UTF-8 解码；出现替换字符说明多半是 GBK 的老 TXT，换 GBK 再比一次，
     * 谁的坏字符少用谁。最后统一换行符并去掉 BOM，后面的正则才好写。
     */
    private static String decode(byte[] data) {
        String best;
        try {
            best = new String(data, "UTF-8");
        } catch (Exception e) {
            best = new String(data);
        }
        int bad = badChars(best);
        if (bad > 0) {
            try {
                String gbk = new String(data, "GBK");
                if (badChars(gbk) < bad) {
                    best = gbk;
                }
            } catch (Exception ignored) {
            }
        }
        if (!best.isEmpty() && best.charAt(0) == '\uFEFF') {
            best = best.substring(1);
        }
        return best.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** 只统计开头两万字，足够判断编码是否选错。 */
    private static int badChars(String s) {
        int n = Math.min(s.length(), 20000);
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (s.charAt(i) == '\uFFFD') {
                count++;
            }
        }
        return count;
    }

    /**
     * 按标题行切章；一行都认不出来时按 {@link #BLOCK_CHARS} 字左右自动分块，
     * 保证任何一本 TXT 都还能翻章。
     */
    private static List<Section> splitSections(String text) {
        List<Section> out = new ArrayList<>();
        List<int[]> marks = new ArrayList<>();
        List<String> titles = new ArrayList<>();

        Matcher m = CHAPTER_LINE.matcher(text);
        while (m.find()) {
            marks.add(new int[]{m.start(), m.end()});
            titles.add(trimTitle(m.group()));
        }

        if (marks.isEmpty()) {
            int n = text.length();
            int pos = 0;
            int index = 1;
            while (pos < n) {
                int end = Math.min(n, pos + BLOCK_CHARS);
                if (end < n) {
                    int nl = text.indexOf('\n', end);
                    if (nl > 0 && nl - end <= BLOCK_TAIL) {
                        end = nl + 1; // 往后挪到换行处，别把一行劈成两半
                    }
                }
                out.add(new Section("第 " + index + " 节", true, pos, end));
                pos = end;
                index++;
            }
            return out;
        }

        // 首个标题之前的内容（简介 / 前言）单独成章，否则它永远看不到
        int first = marks.get(0)[0];
        if (first > 0 && text.substring(0, first).trim().length() > 0) {
            out.add(new Section("前言", true, 0, first));
        }

        // 有些站点的正文区块自带一遍标题，于是同一个章号会出现两个标题行。
        // 第一个标题行到下个标题之间只有空白，直接跳过它，让后一个（含正文的）
        // 标题行成为这一章的标题；否则目录里会凭空多出一倍的空白章节。
        for (int i = 0; i < marks.size(); i++) {
            int start = marks.get(i)[0];
            int nextStart = i + 1 < marks.size() ? marks.get(i + 1)[0] : text.length();
            String between = text.substring(Math.min(marks.get(i)[1], nextStart),
                    Math.max(marks.get(i)[1], nextStart));
            if (between.trim().isEmpty() && i + 1 < marks.size()) {
                continue;
            }
            out.add(new Section(titles.get(i), false, start, nextStart));
        }
        return out;
    }

    /** 标题里可能混着全角空格，显示前统一清掉。 */
    private static String trimTitle(String s) {
        String t = s.replace('\u3000', ' ').trim();
        return t.isEmpty() ? "未命名章节" : t;
    }

    // ================= 翻章 =================

    private void jump(int index) {
        if (sections.isEmpty()) {
            return;
        }
        if (index < 0 || index >= sections.size()) {
            return;
        }
        showChapter(index, 0);
    }

    private void showChapter(int index, int scrollY) {
        if (sections.isEmpty()) {
            return;
        }
        current = Math.max(0, Math.min(index, sections.size() - 1));
        Section s = sections.get(current);
        int end = Math.min(s.end, text.length());
        int start = Math.min(s.start, end);
        String body = text.substring(start, end);
        if (s.needsTitle) {
            body = s.title + "\n\n" + body;
        }
        content.setText(body.trim());

        // 换章后内容要重新测量，滚动位置得等这一帧布局完再落
        final int y = Math.max(0, scrollY);
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.scrollTo(0, y);
            }
        });
        updateBottom();
        saveProgress(current, y);
    }

    private void updateBottom() {
        if (progress == null) {
            return;
        }
        if (sections.isEmpty()) {
            progress.setText("");
            UiKit.setEnabledText(prevBtn, false);
            UiKit.setEnabledText(nextBtn, false);
            return;
        }
        progress.setText((current + 1) + " / " + sections.size() + " 章");
        UiKit.setEnabledText(prevBtn, current > 0);
        UiKit.setEnabledText(nextBtn, current < sections.size() - 1);
    }

    // ================= 目录 =================

    private void showToc() {
        if (sections.isEmpty()) {
            Toast.makeText(this, "还没有章节", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> titles = new ArrayList<>();
        for (Section s : sections) {
            titles.add(s.title);
        }

        // 章节可能上千条，用 ListView 复用行视图，别一次性把所有 TextView 建出来
        final ListView list = new ListView(this);
        list.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_activated_1, titles));
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.6f);
        list.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h));

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("目录（共 " + sections.size() + " 章）")
                .setView(list)
                .setNegativeButton("关闭", null)
                .create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            showChapter(position, 0);
            dlg.dismiss();
        });
        dlg.setOnShowListener(d -> {
            // 打开目录时定位到当前章，长书不用自己翻
            list.setItemChecked(current, true);
            list.setSelection(current);
        });
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(UiUtil.ON_SURFACE_VARIANT);
    }

    // ================= 阅读设置 =================

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiUtil.dp(this, 20), UiUtil.dp(this, 4),
                UiUtil.dp(this, 20), UiUtil.dp(this, 4));

        fontValue = UiUtil.text(this, "", UiUtil.TYPE_BODY_LARGE, UiUtil.ON_SURFACE);
        fontValue.setGravity(Gravity.CENTER);
        box.addView(stepperRow("字号", fontValue, new Runnable() {
            @Override
            public void run() {
                setFont(fontSize - 1);
            }
        }, new Runnable() {
            @Override
            public void run() {
                setFont(fontSize + 1);
            }
        }));

        lineValue = UiUtil.text(this, "", UiUtil.TYPE_BODY_LARGE, UiUtil.ON_SURFACE);
        lineValue.setGravity(Gravity.CENTER);
        box.addView(stepperRow("行距", lineValue, new Runnable() {
            @Override
            public void run() {
                setLine(lineTenths - 1);
            }
        }, new Runnable() {
            @Override
            public void run() {
                setLine(lineTenths + 1);
            }
        }));

        box.addView(themeRow());
        box.addView(UiKit.spacer(this, 4));

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("阅读设置")
                .setView(box)
                .setPositiveButton("完成", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(UiUtil.PRIMARY);

        refreshSettingValues();
    }

    /** 「标签 + − 值 ＋」一行；加减各 48dp 触摸区。 */
    private View stepperRow(String label, TextView value, Runnable minus, Runnable plus) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiUtil.dp(this, 56));

        TextView l = UiUtil.text(this, label, UiUtil.TYPE_BODY_LARGE, UiUtil.ON_SURFACE);
        l.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(l);
        row.addView(stepButton("−", minus));

        value.setMinWidth(UiUtil.dp(this, 64));
        row.addView(value);

        row.addView(stepButton("＋", plus));
        return row;
    }

    private TextView stepButton(String glyph, final Runnable onClick) {
        TextView b = UiUtil.text(this, glyph, 20, UiUtil.PRIMARY);
        b.setGravity(Gravity.CENTER);
        int s = UiUtil.dp(this, 48);
        b.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        b.setBackground(UiUtil.circleRipple(UiUtil.PRIMARY, this));
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    /** 底色用色块本身表示，当前项加一圈 primary 描边。 */
    private View themeRow() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setMinimumHeight(UiUtil.dp(this, 56));

        TextView label = UiUtil.text(this, "背景", UiUtil.TYPE_BODY_LARGE, UiUtil.ON_SURFACE);
        col.addView(label);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = UiUtil.dp(this, 8);
        row.setLayoutParams(rlp);

        themeChips.clear();
        for (int i = 0; i < THEMES.length; i++) {
            final int index = i;
            Theme t = THEMES[i];
            TextView chip = UiUtil.text(this, t.name, UiUtil.TYPE_LABEL_LARGE, t.fg);
            chip.setGravity(Gravity.CENTER);
            chip.setMinHeight(UiUtil.dp(this, 40));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) {
                lp.leftMargin = UiUtil.dp(this, 8);
            }
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> selectTheme(index));
            themeChips.add(chip);
            row.addView(chip);
        }
        col.addView(row);
        updateChips();
        return col;
    }

    private void selectTheme(int index) {
        themeIndex = Math.max(0, Math.min(index, THEMES.length - 1));
        updateChips();
        applyTheme();
        saveSettings();
    }

    private void updateChips() {
        for (int i = 0; i < themeChips.size(); i++) {
            Theme t = THEMES[i];
            boolean on = i == themeIndex;
            themeChips.get(i).setBackground(UiUtil.ripple(UiUtil.ON_SURFACE,
                    UiUtil.roundStroke(t.bg, UiUtil.SHAPE_SMALL,
                            on ? UiUtil.PRIMARY : UiUtil.OUTLINE_VARIANT, on ? 2 : 1, this),
                    this));
        }
    }

    private void refreshSettingValues() {
        if (fontValue != null) {
            fontValue.setText(fontSize + " sp");
        }
        if (lineValue != null) {
            lineValue.setText(String.format(Locale.US, "%.1f 倍", lineTenths / 10f));
        }
    }

    private void setFont(int size) {
        fontSize = Math.max(FONT_MIN, Math.min(FONT_MAX, size));
        content.setTextSize(fontSize);
        refreshSettingValues();
        saveSettings();
    }

    private void setLine(int tenths) {
        lineTenths = Math.max(LINE_MIN, Math.min(LINE_MAX, tenths));
        content.setLineSpacing(0, lineTenths / 10f);
        refreshSettingValues();
        saveSettings();
    }

    /** 把主题刷到所有视图上；标题栏是 UiKit 建的，只能按子视图回刷颜色。 */
    private void applyTheme() {
        Theme t = THEMES[themeIndex];

        root.setBackgroundColor(t.bg);
        header.setBackgroundColor(t.bg);
        for (int i = 0; i < header.getChildCount(); i++) {
            View c = header.getChildAt(i);
            if (c instanceof TextView) {
                ((TextView) c).setTextColor(t.fg);
            }
        }
        styleAction(tocBtn, t);
        styleAction(aaBtn, t);

        scroll.setBackgroundColor(t.bg);
        content.setTextColor(t.fg);

        bottom.setBackgroundColor(t.bg);
        styleAction(prevBtn, t);
        styleAction(nextBtn, t);
        progress.setTextColor(t.sub);

        centerText.setTextColor(t.dark ? t.sub : UiUtil.ON_SURFACE_VARIANT);
        styleAction(centerBtn, t);

        // 状态栏跟随阅读底色：深色底要点亮浅色图标，浅色底反之。
        // onCreate 里已经调过 UiKit.applyStatusBar，这里只是按主题把颜色改准。
        getWindow().setStatusBarColor(t.bg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(
                    t.dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    private void styleAction(TextView b, Theme t) {
        b.setTextColor(t.accent);
        b.setBackground(UiUtil.ripple(t.accent,
                UiUtil.round(Color.TRANSPARENT, UiUtil.SHAPE_FULL, this), this));
    }

    // ================= 偏好 =================

    private SharedPreferences prefs() {
        return getSharedPreferences(PREF, MODE_PRIVATE);
    }

    private void loadSettings() {
        fontSize = Math.max(FONT_MIN, Math.min(FONT_MAX,
                prefs().getInt(KEY_FONT, FONT_DEFAULT)));
        lineTenths = Math.max(LINE_MIN, Math.min(LINE_MAX,
                prefs().getInt(KEY_LINE, LINE_DEFAULT)));
        themeIndex = Math.max(0, Math.min(THEMES.length - 1,
                prefs().getInt(KEY_THEME, 0)));
    }

    private void saveSettings() {
        prefs().edit()
                .putInt(KEY_FONT, fontSize)
                .putInt(KEY_LINE, lineTenths)
                .putInt(KEY_THEME, themeIndex)
                .apply();
    }

    /** 进度存成 "<章序号>:<章内滚动像素>"，键是 "pos:" + 书名。 */
    private void saveProgress(int chapter, int scrollY) {
        if (sections.isEmpty() || bookName == null || bookName.isEmpty()) {
            return;
        }
        prefs().edit()
                .putString(KEY_POS + bookName, chapter + ":" + Math.max(0, scrollY))
                .apply();
    }

    /** @return {章序号, 滚动像素}；没存过或格式坏了都回到开头 */
    private int[] loadProgress() {
        int[] none = {0, 0};
        String s = prefs().getString(KEY_POS + bookName, null);
        if (s == null || s.isEmpty()) {
            return none;
        }
        int sep = s.indexOf(':');
        try {
            int chapter = Integer.parseInt(sep < 0 ? s : s.substring(0, sep));
            int y = sep < 0 ? 0 : Integer.parseInt(s.substring(sep + 1));
            return new int[]{chapter, Math.max(0, y)};
        } catch (Exception e) {
            return none;
        }
    }

    /** 进入 / 退出本页时的转场动画（与其它页面保持一致）。 */
    private void applyTransitions() {
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    public void finish() {
        super.finish();
        applyTransitions();
    }
}
