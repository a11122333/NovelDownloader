package com.niganma.noveldown;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 搜索结果列表适配器（MD3 卡片式列表项）。 */
public class ResultAdapter extends BaseAdapter {

    private final Context ctx;
    private final List<SearchBook> data = new ArrayList<>();

    public ResultAdapter(Context ctx) {
        this.ctx = ctx;
    }

    public void add(SearchBook b) {
        data.add(b);
        notifyDataSetChanged();
    }

    public void clear() {
        data.clear();
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public Object getItem(int position) {
        return data.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        SearchBook b = data.get(position);

        // 外层容器不设置 LayoutParams，交给 ListView 自行生成，避免类型转换崩溃
        LinearLayout outer = new LinearLayout(ctx);
        outer.setOrientation(LinearLayout.VERTICAL);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiUtil.ripple(UiUtil.ON_SURFACE,
                UiUtil.round(UiUtil.SURFACE_CONTAINER, 18, ctx), ctx));
        card.setElevation(UiUtil.dp(ctx, 1));
        int p = UiUtil.dp(ctx, 14);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int m = UiUtil.dp(ctx, 5);
        lp.setMargins(UiUtil.dp(ctx, 16), m, UiUtil.dp(ctx, 16), m);
        card.setLayoutParams(lp);

        TextView name = UiUtil.text(ctx, b.name, 16, UiUtil.ON_SURFACE);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(name);

        String sub = (b.author == null || b.author.isEmpty() ? "佚名" : b.author)
                + "   ·   " + b.sourceName;
        TextView s = UiUtil.text(ctx, sub, 12, UiUtil.ON_SURFACE_VARIANT);
        s.setPadding(0, UiUtil.dp(ctx, 6), 0, 0);
        card.addView(s);

        TextView url = UiUtil.text(ctx, b.url, 11, UiUtil.PRIMARY);
        url.setSingleLine(true);
        url.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        url.setPadding(0, UiUtil.dp(ctx, 4), 0, 0);
        card.addView(url);

        outer.addView(card);
        return outer;
    }
}