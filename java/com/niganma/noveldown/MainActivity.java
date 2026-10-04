package com.niganma.noveldown;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 首页：关键字搜索 + 书源选择（可单选某源，也可多选聚合）+ 结果列表。 */
public class MainActivity extends Activity {

    private static final String PREF = "novel_down";
    private static final String KEY_LAST = "last_keyword";
    private static final String KEY_SEL = "selected_sources";

    private EditText searchBox;
    private TextView status;
    private ResultAdapter adapter;

    private LinearLayout chipWrap;
    private TextView selectInfo;
    private TextView selectAllBtn;

    private List<BookSource> enabledSources = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();

    private int totalSources;
    private int doneSources;
    private final List<String> failedSources = new ArrayList<>();

    /** 批量「链接打开」的待处理队列（每项 = {url, 书源名, charset}）。 */
    private final List<String[]> batchQueue = new ArrayList<>();
    private boolean batchActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.applyStatusBar(this);
        setContentView(buildUi());
        refreshSources();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiUtil.SURFACE);

        root.addView(UiKit.header(this, App.APP_NAME, "书源", new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(MainActivity.this, AboutActivity.class));
            }
        }));

        root.addView(searchBar());
        root.addView(sourceCard());

        status = UiUtil.text(this, "", 12, UiUtil.ON_SURFACE_VARIANT);
        status.setPadding(UiUtil.dp(this, 20), UiUtil.dp(this, 4), UiUtil.dp(this, 20), UiUtil.dp(this, 8));
        root.addView(status);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setBackgroundColor(UiUtil.SURFACE);
        list.setPadding(0, 0, 0, UiUtil.dp(this, 8));
        list.setClipToPadding(false);
        list.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        adapter = new ResultAdapter(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            SearchBook b = (SearchBook) adapter.getItem(position);
            Intent it = new Intent(MainActivity.this, DetailActivity.class);
            it.putExtra("name", b.name);
            it.putExtra("author", b.author);
            it.putExtra("url", b.url);
            it.putExtra("source", b.sourceName);
            startActivity(it);
        });
        root.addView(list);

        return root;
    }

    /** MD3 搜索栏（胶囊形 surface-container-high）+ filled 搜索按钮。 */
    private View searchBar() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.setMargins(UiUtil.dp(this, 16), UiUtil.dp(this, 14), UiUtil.dp(this, 16), UiUtil.dp(this, 6));
        outer.setLayoutParams(olp);

        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.HORIZONTAL);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setBackground(UiUtil.round(UiUtil.SURFACE_CONTAINER_HIGH, 28, this));
        int h = UiUtil.dp(this, 6);
        field.setPadding(UiUtil.dp(this, 16), h, UiUtil.dp(this, 6), h);
        field.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView icon = UiUtil.text(this, "🔍", 14, UiUtil.ON_SURFACE_VARIANT);
        icon.setPadding(0, 0, UiUtil.dp(this, 8), 0);
        field.addView(icon);

        searchBox = new EditText(this);
        searchBox.setHint("输入书名 / 作者");
        searchBox.setTextSize(15);
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

        TextView clear = UiUtil.text(this, "✕", 14, UiUtil.ON_SURFACE_VARIANT);
        int cp = UiUtil.dp(this, 8);
        clear.setGravity(Gravity.CENTER);
        clear.setPadding(cp, cp, cp, cp);
        clear.setBackground(UiUtil.ripple(UiUtil.ON_SURFACE_VARIANT,
                UiUtil.round(UiUtil.SURFACE_CONTAINER_HIGH, 20, this), this));
        clear.setOnClickListener(v -> searchBox.setText(""));
        field.addView(clear);
        outer.addView(field);

        TextView link = UiUtil.text(this, "🔗", 16, UiUtil.PRIMARY);
        int lp2 = UiUtil.dp(this, 9);
        link.setGravity(Gravity.CENTER);
        link.setPadding(lp2, lp2, lp2, lp2);
        link.setBackground(UiUtil.ripple(UiUtil.PRIMARY,
                UiUtil.round(UiUtil.SURFACE_CONTAINER_HIGH, 22, this), this));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.leftMargin = UiUtil.dp(this, 10);
        link.setLayoutParams(llp);
        link.setOnClickListener(v -> openByLink());
        outer.addView(link);

        TextView btn = UiKit.button(this, "搜索", new Runnable() {
            @Override
            public void run() {
                doSearch();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = UiUtil.dp(this, 10);
        btn.setLayoutParams(blp);
        outer.addView(btn);
        return outer;
    }

    /** 书源选择卡片：列出每个书源，点按切换，长按仅搜此源。 */
    private View sourceCard() {
        LinearLayout sCard = UiKit.card(this);
        int sp = UiUtil.dp(this, 14);
        sCard.setPadding(sp, UiUtil.dp(this, 12), sp, UiUtil.dp(this, 12));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(UiUtil.dp(this, 16), UiUtil.dp(this, 4), UiUtil.dp(this, 16), UiUtil.dp(this, 4));
        sCard.setLayoutParams(slp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = UiUtil.text(this, "搜索范围", 15, UiUtil.ON_SURFACE);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(label);

        selectAllBtn = UiUtil.text(this, "全选", 13, UiUtil.PRIMARY);
        selectAllBtn.setTypeface(Typeface.DEFAULT_BOLD);
        int bp = UiUtil.dp(this, 8);
        selectAllBtn.setPadding(bp, bp, bp, bp);
        selectAllBtn.setBackground(UiUtil.ripple(UiUtil.PRIMARY,
                UiUtil.round(UiUtil.SURFACE_CONTAINER, 20, this), this));
        selectAllBtn.setOnClickListener(v -> toggleSelectAll());
        head.addView(selectAllBtn);
        sCard.addView(head);

        chipWrap = new LinearLayout(this);
        chipWrap.setOrientation(LinearLayout.VERTICAL);
        chipWrap.setPadding(0, UiUtil.dp(this, 2), 0, 0);
        sCard.addView(chipWrap);

        selectInfo = UiUtil.text(this, "", 11, UiUtil.ON_SURFACE_VARIANT);
        selectInfo.setPadding(0, UiUtil.dp(this, 10), 0, 0);
        sCard.addView(selectInfo);
        return sCard;
    }

    /** MD3 筛选芯片：选中为主色容器，未选中为白底描边。 */
    private TextView chip(String name, boolean on) {
        TextView t = UiUtil.text(this, name, 13,
                on ? UiUtil.ON_PRIMARY_CONTAINER : UiUtil.ON_SURFACE_VARIANT);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        int h = UiUtil.dp(this, 9);
        int w = UiUtil.dp(this, 12);
        t.setPadding(w, h, w, h);
        if (on) {
            t.setBackground(UiUtil.ripple(UiUtil.ON_PRIMARY_CONTAINER,
                    UiUtil.round(UiUtil.PRIMARY_CONTAINER, 10, this), this));
        } else {
            t.setBackground(UiUtil.ripple(UiUtil.ON_SURFACE_VARIANT,
                    UiUtil.roundStroke(UiUtil.SURFACE_CONTAINER, 10,
                            UiUtil.OUTLINE_VARIANT, 1, this), this));
        }
        return t;
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
            chipWrap.addView(UiUtil.text(this, "没有可搜索的书源。可点右上角「书源」开启或导入，"
                    + "或用搜索栏右侧 🔗 粘贴书籍链接直接打开", 13, UiUtil.ON_SURFACE_VARIANT));
        } else {
            final int perRow = 3;
            LinearLayout row = null;
            int col = 0;
            for (final BookSource s : enabledSources) {
                if (col == 0) {
                    row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    rlp.topMargin = UiUtil.dp(this, 8);
                    row.setLayoutParams(rlp);
                    chipWrap.addView(row);
                }
                final String name = s.name;
                TextView c = chip(name, selected.contains(name));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.rightMargin = UiUtil.dp(this, 8);
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
                col++;
                if (col == perRow) {
                    col = 0;
                }
            }
            // 最后一行不足时用占位补齐，避免单个书源被拉满整行
            if (row != null && col != 0) {
                for (int i = col; i < perRow; i++) {
                    View pad = new View(this);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
                    lp.rightMargin = UiUtil.dp(this, 8);
                    pad.setLayoutParams(lp);
                    row.addView(pad);
                }
            }
        }
        // 提示仅支持链接打开的书源（索引式，不进搜索范围）
        int linkOnly = SourceStore.load(this).size() - enabledSources.size();
        if (linkOnly > 0) {
            TextView hint = UiUtil.text(this, "另有 " + linkOnly
                    + " 个仅支持链接打开的书源（索引式），用搜索栏右侧 🔗 粘贴书籍链接使用",
                    11, UiUtil.ON_SURFACE_VARIANT);
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
        selectInfo.setText("已选 " + selected.size() + "/" + total + " 个书源"
                + (total > 1 ? " · 点按切换，长按仅搜此源" : ""));
        boolean all = total > 0 && selected.size() >= total;
        selectAllBtn.setText(all ? "全不选" : "全选");
        selectAllBtn.setVisibility(total > 0 ? View.VISIBLE : View.GONE);
    }

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

    /** 返回上次选择；从未选过时返回 null（表示默认全选）。 */
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

    /** 粘贴书籍详情页链接（支持多行），用匹配的书源打开；多本时先勾选再逐个打开。 */
    private void openByLink() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = UiUtil.dp(this, 4);
        box.setPadding(p, UiUtil.dp(this, 4), p, 0);
        box.addView(UiUtil.text(this, "粘贴书籍详情页链接，可一次粘贴多行（每行一个），"
                + "解析后勾选要打开的书。\n例：\nhttps://…/novel.html?articleid=…",
                13, UiUtil.ON_SURFACE_VARIANT));
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

    /** 拆分多行文本里的链接，按域名匹配书源。 */
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

    /** 批量勾选列表（支持全选 / 全不选），确认后逐个打开。 */
    private void showBatchPicker(final List<String[]> items, int unmatched) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(UiUtil.dp(this, 4), UiUtil.dp(this, 4), UiUtil.dp(this, 4), 0);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView info = UiUtil.text(this, "识别到 " + items.size() + " 个链接"
                + (unmatched > 0 ? "（另有 " + unmatched + " 个未匹配书源）" : ""),
                13, UiUtil.ON_SURFACE_VARIANT);
        info.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(info);
        final TextView selAll = UiUtil.text(this, "全不选", 13, UiUtil.PRIMARY);
        selAll.setTypeface(Typeface.DEFAULT_BOLD);
        int bp = UiUtil.dp(this, 8);
        selAll.setPadding(bp, bp, bp, bp);
        selAll.setBackground(UiUtil.ripple(UiUtil.PRIMARY,
                UiUtil.round(UiUtil.SURFACE_CONTAINER, 20, this), this));
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
            cb.setTextSize(14);
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

        // 后台抓取书名，替换原始链接标签（失败则保留）
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

    /** 开始批量：入队并打开第一本，之后每返回首页自动打开下一本。 */
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

    /** 链接的简短标签：优先取 articleid，否则取末尾片段。 */
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

    /** 取网页 <title> 作为书名（去掉站点后缀）。 */
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
                    ? "没有启用的书源，请点右上角「书源」开启或导入"
                    : "请至少选择一个要搜索的书源");
            return;
        }
        prefs().edit().putString(KEY_LAST, key).apply();
        // 收起键盘
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
        }
        adapter.clear();
        failedSources.clear();
        totalSources = pool.size();
        doneSources = 0;
        status.setText(pool.size() == 1
                ? "正在「" + pool.get(0).name + "」中搜索…"
                : "正在 " + totalSources + " 个书源中搜索…");
        Searcher.search(pool, key, 1, App.POOL, App.UI, new Searcher.Callback() {
            @Override
            public void onBook(SearchBook book) {
                adapter.add(book);
                status.setText(progress());
            }

            @Override
            public void onSourceFinished(String source, int count) {
                doneSources++;
                if (count < 0) {
                    failedSources.add(source);
                }
                status.setText(progress());
            }

            @Override
            public void onFinished(int total) {
                String msg;
                if (pool.size() == 1) {
                    msg = "「" + pool.get(0).name + "」搜索完成，共 "
                            + total + " 条结果";
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
                status.setText(msg);
            }
        });
    }

    private String progress() {
        return "已完成 " + doneSources + "/" + totalSources
                + " 个书源 · 找到 " + adapter.getCount() + " 条";
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSources();
        if (batchActive) {
            // 从详情页返回时，继续批量打开下一本
            openNextBatch();
            return;
        }
        if (status.getText().length() == 0) {
            status.setText("已启用 " + enabledSources.size() + " 个书源，已选 "
                    + selected.size() + " 个");
        }
    }
}