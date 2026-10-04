package com.niganma.noveldown;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** 书籍详情：加载目录 + 按章节范围下载（导出明文 txt）。 */
public class DetailActivity extends Activity {

    private static final int REQ_WRITE = 1001;
    private static final String PREF = "novel_down";
    private static final String KEY_THREADS = "download_threads";
    private static final String KEY_FROM = "download_from";
    private static final String KEY_TO = "download_to";

    private BookSource source;
    private String bookName, author, bookUrl;
    private int threads = Downloader.DEFAULT_THREADS;

    private TextView status;
    private ProgressBar bar;
    private TextView downloadBtn;
    private ChapterAdapter adapter;
    private List<Chapter> chapters;
    private List<Chapter> pendingSelection;
    private int pendingThreads;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.applyStatusBar(this);
        bookName = getIntent().getStringExtra("name");
        author = getIntent().getStringExtra("author");
        bookUrl = getIntent().getStringExtra("url");
        String sourceName = getIntent().getStringExtra("source");
        source = findSource(sourceName);
        threads = Downloader.clampThreads(
                prefs().getInt(KEY_THREADS, Downloader.DEFAULT_THREADS));
        setContentView(buildUi());
        startLoadChapters();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREF, Context.MODE_PRIVATE);
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
        clp.setMargins(UiUtil.dp(this, 16), UiUtil.dp(this, 14), UiUtil.dp(this, 16), UiUtil.dp(this, 6));
        card.setLayoutParams(clp);

        TextView title = UiUtil.text(this, bookName == null ? "" : bookName, 19, UiUtil.ON_SURFACE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        String sub = "作者：" + (author == null || author.isEmpty() ? "佚名" : author)
                + "   ·   来源：" + (source == null ? "未知" : source.name);
        TextView s = UiUtil.text(this, sub, 12, UiUtil.ON_SURFACE_VARIANT);
        s.setPadding(0, UiUtil.dp(this, 6), 0, 0);
        card.addView(s);

        TextView url = UiUtil.text(this, bookUrl == null ? "" : bookUrl, 11, UiUtil.PRIMARY);
        url.setSingleLine(true);
        url.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        url.setPadding(0, UiUtil.dp(this, 6), 0, 0);
        card.addView(url);
        root.addView(card);

        downloadBtn = UiKit.button(this, "下载（导出明文 TXT）", new Runnable() {
            @Override
            public void run() {
                confirmDownload();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(UiUtil.dp(this, 16), UiUtil.dp(this, 6), UiUtil.dp(this, 16), UiUtil.dp(this, 6));
        downloadBtn.setLayoutParams(blp);
        UiKit.setEnabledText(downloadBtn, false);
        root.addView(downloadBtn);

        status = UiUtil.text(this, "正在加载目录…", 12, UiUtil.ON_SURFACE_VARIANT);
        status.setPadding(UiUtil.dp(this, 20), UiUtil.dp(this, 4), UiUtil.dp(this, 20), UiUtil.dp(this, 4));
        root.addView(status);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        bar.setProgressTintList(ColorStateList.valueOf(UiUtil.PRIMARY));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(UiUtil.PRIMARY_CONTAINER));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiUtil.dp(this, 6));
        plp.setMargins(UiUtil.dp(this, 20), UiUtil.dp(this, 2), UiUtil.dp(this, 20), UiUtil.dp(this, 6));
        bar.setLayoutParams(plp);
        bar.setVisibility(View.GONE);
        root.addView(bar);

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

    /** 下载设置：可指定章节范围（起-止）+ 线程数。 */
    private void confirmDownload() {
        if (chapters == null || chapters.isEmpty()) {
            Toast.makeText(this, "目录还没准备好", Toast.LENGTH_SHORT).show();
            return;
        }
        final int total = chapters.size();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiUtil.dp(this, 4), UiUtil.dp(this, 4), UiUtil.dp(this, 4), 0);

        TextView tip = UiUtil.text(this, "共 " + total + " 章，可指定要下载的章节范围。\n"
                + "将保存为明文 TXT 到「下载/小说下载器」目录。", 13, UiUtil.ON_SURFACE_VARIANT);
        box.addView(tip);

        int defFrom = clampRange(prefs().getInt(KEY_FROM, 1), 1, total);
        int defTo = clampRange(prefs().getInt(KEY_TO, total), 1, total);

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

        final TextView preview = UiUtil.text(this, "", 12, UiUtil.PRIMARY);
        preview.setTypeface(Typeface.DEFAULT_BOLD);
        preview.setPadding(0, UiUtil.dp(this, 10), 0, 0);
        box.addView(preview);

        box.addView(UiKit.fieldLabel(this, "下载线程数（1 - " + Downloader.MAX_THREADS
                + "，默认 " + Downloader.DEFAULT_THREADS + "）"));
        final EditText etThreads = UiKit.textField(this, String.valueOf(threads),
                InputType.TYPE_CLASS_NUMBER);
        etThreads.setText(String.valueOf(threads));
        etThreads.setSelection(etThreads.getText().length());
        box.addView(etThreads);

        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                int f = clampRange(parseInt(etFrom.getText().toString(), 1), 1, total);
                int t2 = clampRange(parseInt(etTo.getText().toString(), total), 1, total);
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
                    int from = clampRange(parseInt(etFrom.getText().toString(), 1), 1, total);
                    int to = clampRange(parseInt(etTo.getText().toString(), total), 1, total);
                    if (from > to) {
                        Toast.makeText(this, "起始章节不能大于结束章节", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int th = Downloader.clampThreads(
                            parseInt(etThreads.getText().toString(), threads));
                    threads = th;
                    prefs().edit()
                            .putInt(KEY_THREADS, th)
                            .putInt(KEY_FROM, from)
                            .putInt(KEY_TO, to)
                            .apply();
                    doDownload(chapters.subList(from - 1, to), th);
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        tintDialogButtons(dlg);
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

    private static int clampRange(int v, int min, int max) {
        if (v < min) {
            return min;
        }
        return Math.min(v, max);
    }

    private void doDownload(final List<Chapter> selected, final int threadCount) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingSelection = selected;
            pendingThreads = threadCount;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            return;
        }
        UiKit.setEnabledText(downloadBtn, false);
        bar.setVisibility(View.VISIBLE);
        bar.setProgress(0);
        SearchBook book = new SearchBook(source.name, bookName,
                author == null ? "" : author, bookUrl);
        Downloader.download(this, source, book, selected, threadCount, App.POOL, App.UI,
                new Downloader.Progress() {
                    @Override
                    public void onStart(int total) {
                        status.setText("开始下载，共 " + total + " 章 · " + threadCount + " 线程");
                    }

                    @Override
                    public void onChapter(int index, int total, String title) {
                        int pct = total == 0 ? 0 : (int) (index * 100L / total);
                        bar.setProgress(pct);
                        status.setText("下载中 " + index + "/" + total + "：" + title);
                    }

                    @Override
                    public void onSuccess(String location, int words) {
                        UiKit.setEnabledText(downloadBtn, true);
                        bar.setProgress(100);
                        status.setText("完成，成功 " + words + " 章 → " + location);
                        Toast.makeText(DetailActivity.this,
                                "已保存：" + location, Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onError(String message) {
                        UiKit.setEnabledText(downloadBtn, true);
                        status.setText(message);
                        Toast.makeText(DetailActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_WRITE && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (pendingSelection != null) {
                doDownload(pendingSelection, pendingThreads);
                pendingSelection = null;
            }
        } else if (requestCode == REQ_WRITE) {
            Toast.makeText(this, "需要存储权限才能保存文件", Toast.LENGTH_LONG).show();
        }
    }
}