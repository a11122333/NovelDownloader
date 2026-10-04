package com.niganma.noveldown;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 书源管理 + 关于 / 署名。 */
public class AboutActivity extends Activity {

    private TextView count;

    private static final String FORMAT_DOC =
            "自定义书源格式（JSON 数组，每项一个书源）\n\n"
            + "{\n"
            + "  \"name\": \"书源名\",\n"
            + "  \"charset\": \"utf-8\",\n"
            + "  \"searchUrl\": \"https://站点/search?q={{key}}&p={{page}}\",\n"
            + "  \"listRule\": \"搜索页正则，组1=书籍链接，组2=书名，组3=作者(可选)\",\n"
            + "  \"chapterList\": \"目录页正则，组1=章节链接，组2=章节标题\",\n"
            + "  \"contentRule\": \"正文页正则，组1=正文HTML\",\n"
            + "  \"tocUrl\": \"可选，目录页模板，{{book}} 换成书籍详情页地址\",\n"
            + "  \"tocPages\": \"可选，目录分页正则，组1=其余目录页地址\",\n"
            + "  \"contentPages\": \"可选，正文分页正则，组1=本章下一页地址\",\n"
            + "  \"replace\": [[\"<br\\\\s*/?>\",\"\\n\"],[\"&nbsp;\",\" \"],[\"<[^>]+>\",\"\"]]\n"
            + "}\n\n"
            + "说明：\n"
            + "· {{key}} 替换为搜索关键字，{{page}} 替换为页码。\n"
            + "· 相对链接会自动补全为绝对链接。\n"
            + "· 正文会依次执行 replace 里的正则替换清洗成纯文本。\n"
            + "· 配置 tocPages / contentPages 可自动翻页合并目录与正文分页。\n"
            + "· 若省略 chapterList，则把详情页当作单章下载。\n"
            + "· 下载支持指定章节范围，并可调节多线程数 1 - " + Downloader.MAX_THREADS
            + "（默认 " + Downloader.DEFAULT_THREADS + "）。\n"
            + "· 导出的 TXT 为明文，不含任何加密。";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.applyStatusBar(this);
        setContentView(buildUi());
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiUtil.SURFACE);

        root.addView(UiKit.backHeader(this, "书源 / 关于", new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }));

        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(UiUtil.dp(this, 16), UiUtil.dp(this, 14), UiUtil.dp(this, 16), UiUtil.dp(this, 28));
        sv.addView(col);

        col.addView(cardSources());
        col.addView(UiKit.spacer(this, 12));
        col.addView(cardAbout());
        col.addView(UiKit.spacer(this, 12));
        col.addView(cardDoc());

        root.addView(sv);
        return root;
    }

    private View cardAbout() {
        LinearLayout card = UiKit.card(this);
        TextView name = UiUtil.text(this, App.APP_NAME, 22, UiUtil.ON_SURFACE);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(name);

        TextView credit = UiUtil.text(this, App.CREDIT, 16, UiUtil.PRIMARY);
        credit.setTypeface(Typeface.DEFAULT_BOLD);
        credit.setPadding(0, UiUtil.dp(this, 8), 0, 0);
        card.addView(credit);

        TextView v = UiUtil.text(this, "版本 " + App.VERSION + "  ·  纯本地运行，无广告、无联网上报",
                12, UiUtil.ON_SURFACE_VARIANT);
        v.setPadding(0, UiUtil.dp(this, 8), 0, 0);
        card.addView(v);
        return card;
    }

    private View cardSources() {
        LinearLayout card = UiKit.card(this);
        card.addView(UiKit.sectionTitle(this, "书源管理"));

        count = UiUtil.text(this, "", 12, UiUtil.ON_SURFACE_VARIANT);
        count.setPadding(0, UiUtil.dp(this, 6), 0, UiUtil.dp(this, 10));
        card.addView(count);
        refreshCount();

        card.addView(UiKit.listRow(this, "启用 / 停用书源", new Runnable() {
            @Override
            public void run() {
                manageSources();
            }
        }));
        card.addView(UiKit.spacer(this, 8));
        card.addView(UiKit.listRow(this, "从剪贴板导入书源", new Runnable() {
            @Override
            public void run() {
                importFromClipboard();
            }
        }));
        card.addView(UiKit.spacer(this, 8));
        card.addView(UiKit.listRow(this, "清空自定义书源", new Runnable() {
            @Override
            public void run() {
                confirmClearUser();
            }
        }));
        return card;
    }

    private View cardDoc() {
        LinearLayout card = UiKit.card(this);
        card.addView(UiKit.sectionTitle(this, "书源规则说明"));
        TextView doc = UiUtil.text(this, FORMAT_DOC, 12, UiUtil.ON_SURFACE_VARIANT);
        doc.setPadding(0, UiUtil.dp(this, 8), 0, 0);
        doc.setTextIsSelectable(true);
        doc.setLineSpacing(UiUtil.dp(this, 2), 1f);
        card.addView(doc);
        return card;
    }

    private void refreshCount() {
        int all = SourceStore.all(this).size();
        int enabled = SourceStore.load(this).size();
        int custom = SourceStore.userCount(this);
        count.setText("共 " + all + " 个书源（内置 " + (all - custom) + " · 自定义 " + custom
                + "）  ·  已启用 " + enabled + " 个");
    }

    /** 弹出书源勾选列表，保存启用 / 停用状态。 */
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
            cb.setTextSize(15);
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
                    refreshCount();
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
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipData cd = cm.getPrimaryClip();
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
        refreshCount();
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
                    refreshCount();
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(UiUtil.ERROR);
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(UiUtil.ON_SURFACE_VARIANT);
    }
}