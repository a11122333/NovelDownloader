package com.niganma.noveldown;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 关于：应用信息、GitHub 地址与免责声明（力求简洁）。 */
public class AboutActivity extends Activity {

    private static final String GITHUB = "https://github.com/a11122333/NovelDownloader";

    private static final String DESCRIPTION =
            "从多个书源并发搜索小说，解析目录后按需下载正文，导出为明文 TXT。";


    private static final String FORMAT_DOC =
            "自定义书源格式（JSON 数组，每项一个书源）\n\n"
            + "{\n"
            + "  \"name\": \"书源名\",\n"
            + "  \"charset\": \"utf-8\",\n"
            + "  \"searchUrl\": \"https://站点/search?q={{key}}&p={{page}}\",\n  \"searchMethod\": \"post\", \"searchBody\": \"searchkey={{key}}\", \"warmUp\": \"https://站点/\",\n"
            + "  \"listRule\": \"搜索页正则，组1=书籍链接，组2=书名，组3=作者(可选)\",\n"
            + "  \"chapterList\": \"目录页正则，组1=章节链接，组2=章节标题\",\n"
            + "  \"contentRule\": \"正文页正则，组1=正文HTML\",\n"
            + "  \"tocUrl\": \"可选，目录页模板，{{book}} 换成书籍详情页地址\",\n"
            + "  \"tocPages\": \"可选，目录分页正则，组1=其余目录页地址\",\n"
            + "  \"contentPages\": \"可选，正文分页正则，组1=本章下一页地址\",\n"
            + "  \"indexChapters\": \"可选，索引式章节(目录由JS渲染、无链接的站点)对象\",\n"
            + "  \"replace\": [[\"<br\\\\s*/?>\",\"\\n\"],[\"&nbsp;\",\" \"],[\"<[^>]+>\",\"\"]]\n"
            + "}\n\n"
            + "说明：\n"
            + "· {{key}} 替换为搜索关键字，{{page}} 替换为页码。\n"
            + "· 相对链接会自动补全为绝对链接。\n"
            + "· 正文会依次执行 replace 里的正则替换清洗成纯文本。\n"
            + "· 配置 tocPages / contentPages 可自动翻页合并目录与正文分页。\n"
            + "· indexChapters 内含 lastIdRule / catalogRule / urlTemplate，"
            + "用于目录由 JS 生成、页面无章节链接的站点。\n"
            + "· 若省略 chapterList，则把详情页当作单章下载。\n"
            + "· 下载支持指定章节范围，并可调节多线程数 1 - " + Downloader.MAX_THREADS
            + "（默认 " + Downloader.DEFAULT_THREADS + "）。\n"
            + "· 导出的 TXT 为明文，不含任何加密。";

    private static final String DISCLAIMER =
            "仅供学习与技术交流使用，请勿用于商业用途，下载后请在 24 小时内删除。\n"
            + "书籍版权归原作者及发布站点所有。\n"
            + "应用完全在本地运行，不收集、不上传任何数据。";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.applyStatusBar(this);
        setContentView(buildUi());
        applyTransitions();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiUtil.SURFACE);

        root.addView(UiKit.backHeader(this, "关于", new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }));

        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(UiUtil.dp(this, 16), UiUtil.dp(this, 12),
                UiUtil.dp(this, 16), UiUtil.dp(this, 24));
        sv.addView(col);

        col.addView(cardApp());
        col.addView(UiKit.spacer(this, 12));
        col.addView(cardLinks());
        col.addView(UiKit.spacer(this, 12));
        col.addView(cardDoc());
        col.addView(UiKit.spacer(this, 12));
        col.addView(cardDisclaimer());

        root.addView(sv);
        return root;
    }

    /** 应用信息：名称、版本、署名与一句话简介。 */
    private View cardApp() {
        LinearLayout card = UiKit.card(this);

        TextView name = UiUtil.text(this, App.APP_NAME, UiUtil.TYPE_HEADLINE_SMALL, UiUtil.ON_SURFACE);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(name);

        TextView ver = UiUtil.text(this, "版本 " + App.version(this),
                UiUtil.TYPE_BODY_MEDIUM, UiUtil.PRIMARY);
        ver.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        ver.setPadding(0, UiUtil.dp(this, 4), 0, 0);
        card.addView(ver);

        TextView credit = UiUtil.text(this, App.CREDIT, UiUtil.TYPE_BODY_SMALL,
                UiUtil.ON_SURFACE_VARIANT);
        credit.setPadding(0, UiUtil.dp(this, 4), 0, 0);
        card.addView(credit);

        TextView desc = UiUtil.text(this, DESCRIPTION, UiUtil.TYPE_BODY_MEDIUM,
                UiUtil.ON_SURFACE_VARIANT);
        desc.setPadding(0, UiUtil.dp(this, 12), 0, 0);
        desc.setLineSpacing(UiUtil.dp(this, 4), 1f);
        card.addView(desc);
        return card;
    }

    /** 链接：GitHub 仓库地址，点击用浏览器打开。 */
    private View cardLinks() {
        LinearLayout card = UiKit.card(this);
        card.setPadding(UiUtil.dp(this, 8), UiUtil.dp(this, 8),
                UiUtil.dp(this, 8), UiUtil.dp(this, 8));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiUtil.dp(this, 56));
        int h = UiUtil.dp(this, 12);
        row.setPadding(h, h, h, h);
        row.setBackground(UiUtil.ripple(UiUtil.PRIMARY,
                UiUtil.round(UiUtil.SURFACE_CONTAINER_HIGH, UiUtil.SHAPE_MEDIUM, this), this));
        row.setOnClickListener(v -> openGithub());

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = UiUtil.text(this, "GitHub 开源地址", UiUtil.TYPE_TITLE_MEDIUM,
                UiUtil.ON_SURFACE);
        info.addView(title);

        TextView link = UiUtil.text(this, GITHUB, UiUtil.TYPE_BODY_SMALL, UiUtil.PRIMARY);
        link.setSingleLine(true);
        link.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        link.setPadding(0, UiUtil.dp(this, 3), 0, 0);
        info.addView(link);
        row.addView(info);

        TextView arrow = UiUtil.text(this, "›", 20, UiUtil.ON_SURFACE_VARIANT);
        row.addView(arrow);
        card.addView(row);
        return card;
    }

    /** 书源规则说明：默认折叠成一行，点开在可滚动弹窗里展示完整文档。 */
    private View cardDoc() {
        LinearLayout card = UiKit.card(this);
        card.setPadding(UiUtil.dp(this, 8), UiUtil.dp(this, 8),
                UiUtil.dp(this, 8), UiUtil.dp(this, 8));
        card.addView(UiKit.listRow(this, "书源规则说明", new Runnable() {
            @Override
            public void run() {
                showDocDialog();
            }
        }));
        return card;
    }

    /** 展示完整书源规则文档（内容较长，放在可滚动弹窗里）。 */
    private void showDocDialog() {
        ScrollView sv = new ScrollView(this);
        int pad = UiUtil.dp(this, 16);
        sv.setPadding(pad, UiUtil.dp(this, 8), pad, pad);

        TextView doc = UiUtil.text(this, FORMAT_DOC, UiUtil.TYPE_BODY_SMALL,
                UiUtil.ON_SURFACE_VARIANT);
        doc.setTextIsSelectable(true);
        doc.setLineSpacing(UiUtil.dp(this, 3), 1f);
        sv.addView(doc);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("书源规则说明")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(UiUtil.PRIMARY);
    }

    private View cardDisclaimer() {
        LinearLayout card = UiKit.card(this);
        card.addView(UiKit.sectionTitle(this, "免责声明"));
        TextView body = UiUtil.text(this, DISCLAIMER, UiUtil.TYPE_BODY_SMALL,
                UiUtil.ON_SURFACE_VARIANT);
        body.setPadding(0, UiUtil.dp(this, 8), 0, 0);
        body.setLineSpacing(UiUtil.dp(this, 4), 1f);
        card.addView(body);
        return card;
    }

    private void openGithub() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB)));
        } catch (Exception e) {
            Toast.makeText(this, GITHUB, Toast.LENGTH_LONG).show();
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
