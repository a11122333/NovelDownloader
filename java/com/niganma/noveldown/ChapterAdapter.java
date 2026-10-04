package com.niganma.noveldown;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 目录列表适配器（MD3 列表项 + 序号徽标）。 */
public class ChapterAdapter extends BaseAdapter {

    private final Context ctx;
    private final List<Chapter> data = new ArrayList<>();

    public ChapterAdapter(Context ctx) {
        this.ctx = ctx;
    }

    public void setData(List<Chapter> list) {
        data.clear();
        data.addAll(list);
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
        // 外层容器不设置 LayoutParams，交给 ListView 自行生成
        LinearLayout outer = new LinearLayout(ctx);
        outer.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(UiUtil.round(UiUtil.SURFACE_CONTAINER, 14, ctx));
        int p = UiUtil.dp(ctx, 12);
        row.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = UiUtil.dp(ctx, 4);
        lp.setMargins(UiUtil.dp(ctx, 16), m, UiUtil.dp(ctx, 16), m);
        row.setLayoutParams(lp);

        // 序号徽标：主色容器小圆角块
        TextView idx = UiUtil.text(ctx, String.valueOf(position + 1), 11, UiUtil.ON_PRIMARY_CONTAINER);
        idx.setGravity(Gravity.CENTER);
        idx.setBackground(UiUtil.round(UiUtil.PRIMARY_CONTAINER, 8, ctx));
        int ih = UiUtil.dp(ctx, 3);
        int iw = UiUtil.dp(ctx, 6);
        idx.setPadding(iw, ih, iw, ih);
        idx.setMinWidth(UiUtil.dp(ctx, 30));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.rightMargin = UiUtil.dp(ctx, 10);
        idx.setLayoutParams(ilp);
        row.addView(idx);

        TextView title = UiUtil.text(ctx, data.get(position).title, 14, UiUtil.ON_SURFACE);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(title);

        outer.addView(row);
        return outer;
    }
}