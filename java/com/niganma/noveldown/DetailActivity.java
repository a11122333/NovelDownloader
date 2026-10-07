package com.niganma.noveldown;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 书籍详情：加载目录 + 指定章节范围后交给 {@link DownloadManager} 后台下载。
 *
 * <p>线程数与文字转换属于全局下载偏好（在设置页调整），这里只负责选择章节
 * 范围。点「开始下载」后即可返回，下载不会因为关闭本页面而中断。</p>
 */
public class DetailActivity extends Activity {

    private static final int REQ_WRITE = 1001;

    private BookSource source;
    private String bookName, author, bookUrl;
    private int threads = Downloader.DEFAULT_THREADS;
    private int convMode = CharConv.NONE;

    private TextView status;
    private TextView downloadBtn;
    /** 当前选中的章节范围（用于在按钮上显示「已选 N 章」）。 */
    private int selFrom = 1;
    private int selTo = 0;
    private ChapterAdapter adapter;
    private List<Chapter> chapters;
    private List<Chapter> pendingSelection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.applyStatusBar(this);
        bookName = getIntent().getStringExtra("name");
        author = getIntent().getStringExtra("author");
        bookUrl = getIntent().getStringExtra("url");
        String sourceName = getIntent().getStringExtra("source");
        source = findSource(sourceName);
        setContentView(buildUi());
        startLoadChapters();
        applyTransitions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 设置页可能刚改过下载偏好
        threads = DownloadPrefs.threads(this);
        convMode = DownloadPrefs.conv(this);
    }

    private BookSource findSource(String name) {
        List<BookSource> list = SourceStore.all(this);
        for (BookSource s : list) {
            if (s.name.equals(name)) {
                return s;
            }
        }
        return list.isEmpty() ? null : list.get(0);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiUtil.SURFACE);

        root.addView(UiKit.backHeader(this, bookName == null ? "详情" : bookName,
                new Runnable() {
                    @Override
                    public void run() {
                        finish();
                    }
                }));

        LinearLayout card = UiKit.card(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(UiUtil.dp(this, 16), UiUtil.dp(this, 12),
                UiUtil.dp(this, 16), UiUtil.dp(this, 6));
        card.setLayoutParams(clp);

        TextView title = UiUtil.text(this, bookName == null ? "" : bookName,
                UiUtil.TYPE_TITLE_MEDIUM, UiUtil.ON_SURFACE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        String sub = "作者：" + (author == null || author.isEmpty() ? "佚名" : author)
                + "   ·   来源：" + (source == null ? "未知" : source.name);
        TextView s = UiUtil.text(this, sub, UiUtil.TYPE_BODY_SMALL, UiUtil.ON_SURFACE_VARIANT);
        s.setPadding(0, UiUtil.dp(this, 6), 0, 0);
        card.addView(s);

        // 书籍原始链接：点击后用浏览器打开
        TextView url = UiUtil.text(this, bookUrl == null ? "" : bookUrl,
                UiUtil.TYPE_LABEL_SMALL, UiUtil.PRIMARY);
        url.setSingleLine(true);
        url.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        url.setPadding(UiUtil.dp(this, 10), UiUtil.dp(this, 8),
                UiUtil.dp(this, 10), UiUtil.dp(this, 8));
        url.setBackground(UiUtil.ripple(UiUtil.PRIMARY, UiUtil.round(
                UiUtil.SURFACE_CONTAINER_HIGH, UiUtil.SHAPE_SMALL, this), this));
        url.setOnClickListener(v -> openBookUrl());
        url.setContentDescription("在浏览器中打开书籍页面");
        card.addView(url);

        TextView urlHint = UiUtil.text(this, "点击链接可用浏览器打开原页面",
                UiUtil.TYPE_LABEL_SMALL, UiUtil.ON_SURFACE_VARIANT);
        urlHint.setPadding(0, UiUtil.dp(this, 4), 0, 0);
        card.addView(urlHint);
        root.addView(card);

        downloadBtn = UiKit.button(this, "选择章节并下载", new Runnable() {
            @Override
            public void run() {
                confirmDownload();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(UiUtil.dp(this, 16), UiUtil.dp(this, 6),
                UiUtil.dp(this, 16), UiUtil.dp(this, 6));
        downloadBtn.setLayoutParams(blp);
        UiKit.setEnabledText(downloadBtn, false);
        root.addView(downloadBtn);

        status = UiUtil.text(this, "正在加载目录…", UiUtil.TYPE_BODY_SMALL,
                UiUtil.ON_SURFACE_VARIANT);
        status.setPadding(UiUtil.dp(this, 20), UiUtil.dp(this, 4),
                UiUtil.dp(this, 20), UiUtil.dp(this, 6));
        root.addView(status);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setBackgroundColor(UiUtil.SURFACE);
        list.setPadding(0, 0, 0, UiUtil.dp(this, 8));
        list.setClipToPadding(false);
        list.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        adapter = new ChapterAdapter(this);
        list.setAdapter(adapter);
        root.addView(list);

        return root;
    }

    /** 用系统浏览器打开书籍详情页原链接。 */
    private void openBookUrl() {
        if (bookUrl == null || bookUrl.isEmpty()) {
            Toast.makeText(this, "这本书没有可跳转的链接", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(bookUrl)));
        } catch (Exception e) {
            Toast.makeText(this, "没有可打开链接的应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void startLoadChapters() {
        if (source == null) {
            status.setText("找不到对应书源，请回首页重新搜索");
            return;
        }
        App.POOL.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Chapter> list = Downloader.loadChapters(source, bookUrl);
                    App.UI.post(new Runnable() {
                        @Override
                        public void run() {
                            chapters = list;
                            adapter.setData(list);
                            if (list.isEmpty()) {
                                status.setText("未解析到章节目录（请检查 chapterList 规则）");
                            } else {
                                status.setText("共 " + list.size() + " 章");
                                selFrom = 1;
                                selTo = list.size();
                                updateDownloadBtn();
                                UiKit.setEnabledText(downloadBtn, true);
                            }
                        }
                    });
                } catch (final Exception e) {
                    App.UI.post(new Runnable() {
                        @Override
                        public void run() {
                            status.setText("目录加载失败：" + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    /** 按当前选中范围刷新下载按钮文案（主标题 + 小一号的范围说明）。 */
    private void updateDownloadBtn() {
        if (downloadBtn == null) {
            return;
        }
        int total = chapters == null ? 0 : chapters.size();
        String main = "选择章节并下载";
        if (total <= 0 || selTo <= 0) {
            downloadBtn.setText(main);
            return;
        }
        int n = Math.max(0, selTo - selFrom + 1);
        String sub = "（已选 " + n + " 章：" + selFrom + " - " + selTo + "）";
        android.text.SpannableString sp = new android.text.SpannableString(main + sub);
        sp.setSpan(new android.text.style.RelativeSizeSpan(0.78f), main.length(),
                sp.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        downloadBtn.setText(sp);
    }

    /** 下载设置：指定章节范围；线程数与转换沿用设置页的全局偏好。 */
    private void confirmDownload() {
        if (chapters == null || chapters.isEmpty()) {
            Toast.makeText(this, "目录还没准备好", Toast.LENGTH_SHORT).show();
            return;
        }
        threads = DownloadPrefs.threads(this);
        convMode = DownloadPrefs.conv(this);
        final int total = chapters.size();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiUtil.dp(this, 4), UiUtil.dp(this, 4), UiUtil.dp(this, 4), 0);

        box.addView(UiUtil.text(this, "共 " + total + " 章，可指定要下载的章节范围。\n"
                + "保存为明文 TXT 到「下载/小说下载器」，下载过程可在下载页查看。",
                UiUtil.TYPE_BODY_MEDIUM, UiUtil.ON_SURFACE_VARIANT));

        int defFrom = DownloadPrefs.from(this, total);
        int defTo = DownloadPrefs.to(this, total);

        LinearLayout rangeRow = new LinearLayout(this);
        rangeRow.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        left.addView(UiKit.fieldLabel(this, "起始章节（1 - " + total + "）"));
        final EditText etFrom = UiKit.textField(this, "1", InputType.TYPE_CLASS_NUMBER);
        etFrom.setText(String.valueOf(defFrom));
        etFrom.setSelection(etFrom.getText().length());
        left.addView(etFrom);
        rangeRow.addView(left);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rlp.leftMargin = UiUtil.dp(this, 10);
        right.setLayoutParams(rlp);
        right.addView(UiKit.fieldLabel(this, "结束章节（1 - " + total + "）"));
        final EditText etTo = UiKit.textField(this, String.valueOf(total),
                InputType.TYPE_CLASS_NUMBER);
        etTo.setText(String.valueOf(defTo));
        etTo.setSelection(etTo.getText().length());
        right.addView(etTo);
        rangeRow.addView(right);
        box.addView(rangeRow);

        final TextView preview = UiUtil.text(this, "", UiUtil.TYPE_BODY_SMALL, UiUtil.PRIMARY);
        preview.setTypeface(Typeface.DEFAULT_BOLD);
        preview.setPadding(0, UiUtil.dp(this, 10), 0, 0);
        box.addView(preview);

        box.addView(UiKit.fieldLabel(this, "导出格式"));
        final int[] fmt = {DownloadPrefs.format(this)};
        final String[] fmtNames = Exporter.formatNames();
        final int[] fmtValues = {Exporter.FORMAT_TXT, Exporter.FORMAT_SPLIT,
                Exporter.FORMAT_EPUB};
        final TextView[] fmtChips = new TextView[fmtValues.length];
        LinearLayout fmtRow = new LinearLayout(this);
        fmtRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < fmtValues.length; i++) {
            final int idx = i;
            TextView chip = UiKit.choiceChip(this, fmtNames[i], fmt[0] == fmtValues[i]);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            clp.rightMargin = UiUtil.dp(this, 6);
            chip.setLayoutParams(clp);
            chip.setOnClickListener(v -> {
                fmt[0] = fmtValues[idx];
                for (int k = 0; k < fmtChips.length; k++) {
                    restyleChip(fmtChips[k], fmtNames[k], fmt[0] == fmtValues[k]);
                }
            });
            fmtChips[i] = chip;
            fmtRow.addView(chip);
        }
        box.addView(fmtRow);

        final TextView preview2 = UiUtil.text(this, "文件名："
                        + DownloadPrefs.previewName(this, bookName == null ? "书名" : bookName),
                UiUtil.TYPE_LABEL_SMALL, UiUtil.ON_SURFACE_VARIANT);
        preview2.setPadding(0, UiUtil.dp(this, 6), 0, 0);
        box.addView(preview2);

        TextView current = UiUtil.text(this, "当前线程 " + threads + " · "
                + DownloadPrefs.convName(convMode) + "（在设置页修改）",
                UiUtil.TYPE_LABEL_SMALL, UiUtil.ON_SURFACE_VARIANT);
        current.setPadding(0, UiUtil.dp(this, 8), 0, 0);
        box.addView(current);

        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                int f = DownloadPrefs.clampRange(
                        parseInt(etFrom.getText().toString(), 1), 1, total);
                int t2 = DownloadPrefs.clampRange(
                        parseInt(etTo.getText().toString(), total), 1, total);
                if (f > t2) {
                    preview.setText("起始不能大于结束");
                } else {
                    preview.setText("将下载第 " + f + " - " + t2 + " 章，共 " + (t2 - f + 1) + " 章");
                }
            }
        };
        etFrom.addTextChangedListener(watcher);
        etTo.addTextChangedListener(watcher);
        watcher.afterTextChanged(null);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("下载设置")
                .setView(box)
                .setPositiveButton("开始下载", (d, w) -> {
                    int from = DownloadPrefs.clampRange(
                            parseInt(etFrom.getText().toString(), 1), 1, total);
                    int to = DownloadPrefs.clampRange(
                            parseInt(etTo.getText().toString(), total), 1, total);
                    if (from > to) {
                        Toast.makeText(this, "起始章节不能大于结束章节", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    DownloadPrefs.setRange(this, from, to);
                    DownloadPrefs.setFormat(this, fmt[0]);
                    selFrom = from;
                    selTo = to;
                    updateDownloadBtn();
                    doDownload(chapters.subList(from - 1, to));
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        tintDialogButtons(dlg);
    }

    /** 切换选中态后重建芯片外观（背景与文字色都随选中态变）。 */
    private void restyleChip(TextView chip, String text, boolean on) {
        chip.setTextColor(on ? UiUtil.ON_SECONDARY_CONTAINER : UiUtil.ON_SURFACE_VARIANT);
        if (on) {
            chip.setBackground(UiUtil.ripple(UiUtil.ON_SECONDARY_CONTAINER,
                    UiUtil.round(UiUtil.SECONDARY_CONTAINER, UiUtil.SHAPE_SMALL, this), this));
        } else {
            chip.setBackground(UiUtil.ripple(UiUtil.ON_SURFACE_VARIANT,
                    UiUtil.roundStroke(android.graphics.Color.TRANSPARENT, UiUtil.SHAPE_SMALL,
                            UiUtil.OUTLINE_VARIANT, 1, this), this));
        }
    }

    /** MD3 风格的对话框按钮配色。 */
    private void tintDialogButtons(AlertDialog dlg) {
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(UiUtil.PRIMARY);
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(UiUtil.ON_SURFACE_VARIANT);
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private void doDownload(final List<Chapter> selected) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingSelection = selected;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_WRITE);
            return;
        }
        if (DownloadManager.running().size() >= DownloadPrefs.MAX_JOBS) {
            Toast.makeText(this, "同时下载的任务已达上限（" + DownloadPrefs.MAX_JOBS
                    + " 个），请等前面的完成", Toast.LENGTH_LONG).show();
            return;
        }
        SearchBook book = new SearchBook(source.name, bookName,
                author == null ? "" : author, bookUrl);
        int format = DownloadPrefs.format(this);
        String template = DownloadPrefs.template(this);
        // 用「整本书的章节表 + 下标范围」表达章节选择，便于暂停后按原范围继续
        int from = chapters.indexOf(selected.get(0));
        int to = from + selected.size() - 1;
        if (from < 0) {
            from = 0;
            to = selected.size() - 1;
        }
        DownloadManager.start(this, source, book, chapters, threads, convMode,
                format, template, from, to, null, DownloadPrefs.splitGroup(this));
        Toast.makeText(this, "已加入后台下载（" + Exporter.formatName(format)
                        + "），可在「下载」页查看进度",
                Toast.LENGTH_LONG).show();
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_WRITE && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (pendingSelection != null) {
                doDownload(pendingSelection);
                pendingSelection = null;
            }
        } else if (requestCode == REQ_WRITE) {
            Toast.makeText(this, "需要存储权限才能保存文件", Toast.LENGTH_LONG).show();
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
