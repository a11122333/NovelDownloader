package com.niganma.noveldown;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 导入书源：可直接粘贴 JSON 导入，也可从剪贴板一键读取。
 *
 * <p>书源规则字段较多，完整的字段说明放在「关于 → 书源规则说明」里，
 * 本页保持简洁：一个输入框 + 两个导入入口。</p>
 */
public class ImportSourcesActivity extends Activity {

    private EditText input;
    private TextView stat;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.applyStatusBar(this);
        setContentView(buildUi());
        refreshStat();
        applyTransitions();
    }

    /** 进入 / 退出本页时的转场动画。 */
    private void applyTransitions() {
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    public void finish() {
        super.finish();
        applyTransitions();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiUtil.SURFACE);

        root.addView(UiKit.backHeader(this, "导入书源", new Runnable() {
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
        int side = UiUtil.dp(this, 16);
        col.setPadding(side, UiUtil.dp(this, 12), side, UiUtil.dp(this, 24));
        sv.addView(col);

        LinearLayout card = UiKit.card(this);
        card.addView(UiKit.sectionTitle(this, "粘贴书源 JSON"));

        TextView tip = UiUtil.text(this,
                "把书源 JSON 粘到下面的输入框，然后点「导入」。\n"
                        + "也可以先在别处复制好，再点「从剪贴板读取」。\n"
                        + "字段含义见「关于 → 书源规则说明」。",
                UiUtil.TYPE_BODY_SMALL, UiUtil.ON_SURFACE_VARIANT);
        tip.setPadding(0, UiUtil.dp(this, 8), 0, UiUtil.dp(this, 10));
        tip.setLineSpacing(UiUtil.dp(this, 3), 1f);
        card.addView(tip);

        input = UiKit.textField(this, "[{\"name\": \"书源名\", \"contentRule\": \"…\"}]",
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setSingleLine(false);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(6);
        input.setMaxLines(12);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        input.setLayoutParams(ilp);
        card.addView(input);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = UiUtil.dp(this, 12);
        actions.setLayoutParams(alp);

        TextView importBtn = UiKit.button(this, "导入", new Runnable() {
            @Override
            public void run() {
                apply(input.getText().toString());
            }
        });
        importBtn.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        actions.addView(importBtn);

        TextView clipBtn = UiKit.tonalButton(this, "从剪贴板读取", new Runnable() {
            @Override
            public void run() {
                String text = readClipboard();
                if (text == null || text.trim().isEmpty()) {
                    Toast.makeText(ImportSourcesActivity.this, "剪贴板为空",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                input.setText(text);
                input.setSelection(input.getText().length());
                Toast.makeText(ImportSourcesActivity.this, "已读取剪贴板内容，可核对后点「导入」",
                        Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = UiUtil.dp(this, 10);
        clipBtn.setLayoutParams(clp);
        actions.addView(clipBtn);
        card.addView(actions);

        col.addView(card);
        col.addView(UiKit.spacer(this, 12));

        LinearLayout statCard = UiKit.card(this);
        stat = UiUtil.text(this, "", UiUtil.TYPE_BODY_SMALL, UiUtil.ON_SURFACE_VARIANT);
        stat.setLineSpacing(UiUtil.dp(this, 3), 1f);
        statCard.addView(stat);
        col.addView(statCard);

        root.addView(sv);
        return root;
    }

    private String readClipboard() {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            return null;
        }
        android.content.ClipData cd = cm.getPrimaryClip();
        if (cd == null || cd.getItemCount() == 0) {
            return null;
        }
        return String.valueOf(cd.getItemAt(0).coerceToText(this));
    }

    /** 解析并导入；成功或失败都给出明确提示并刷新统计。 */
    private void apply(String text) {
        if (text == null || text.trim().isEmpty()) {
            Toast.makeText(this, "还没有内容可导入", Toast.LENGTH_SHORT).show();
            return;
        }
        Object[] res = SourceStore.importJson(this, text);
        int ok = (Integer) res[0];
        String err = (String) res[1];
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_LONG).show();
        } else if (ok == 0) {
            Toast.makeText(this,
                    "没有解析到有效书源（需含 contentRule，且含 searchUrl+listRule 或 indexChapters）",
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "成功导入 " + ok + " 个书源", Toast.LENGTH_LONG).show();
            input.setText("");
        }
        refreshStat();
    }

    private void refreshStat() {
        int all = SourceStore.all(this).size();
        int custom = SourceStore.userCount(this);
        int enabled = SourceStore.load(this).size();
        if (stat != null) {
            stat.setText("当前共 " + all + " 个书源（内置 " + (all - custom) + " · 自定义 "
                    + custom + "），已启用 " + enabled + " 个。\n"
                    + "启用 / 停用请到「设置 → 启用 / 停用书源」。");
        }
    }
}
