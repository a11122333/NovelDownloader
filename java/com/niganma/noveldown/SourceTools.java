package com.niganma.noveldown;

import android.content.Context;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * 书源的批量操作：整包导出 / 导入、以及单个书源的连通性测试。
 *
 * <p>导出用的是各书源自己的 {@link BookSource#toJson()}，所以导出结果可以直接
 * 被 {@link SourceStore#importJson} 读回来——真正意义上的备份与迁移。</p>
 */
public final class SourceTools {

    private SourceTools() {}

    // ================= 导出 / 导入 =================

    /** 把当前所有书源（内置 + 自定义）序列化成 JSON 数组文本。 */
    public static String exportJson(Context c) {
        JSONArray arr = new JSONArray();
        for (BookSource s : SourceStore.all(c)) {
            try {
                arr.put(s.toJson());
            } catch (Exception ignored) {
            }
        }
        try {
            // 缩进输出便于用户直接查看与手工编辑
            return arr.toString(2);
        } catch (org.json.JSONException e) {
            return arr.toString();
        }
    }

    /** 导出时的建议文件名。 */
    public static String exportFileName() {
        String date = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                .format(new java.util.Date());
        return "书源备份_" + date + ".json";
    }

    /**
     * 导入并合并书源（不覆盖已有，同名会一并追加，去重交给后续解析）。
     *
     * @return {成功数 String, 附加说明 String}；第二个元素为 null 表示没有额外说明
     */
    public static String[] importJson(Context c, String text) {
        if (text == null || text.trim().isEmpty()) {
            return new String[]{"0", "内容为空"};
        }
        Object[] res = SourceStore.importJson(c, text);
        int ok = (Integer) res[0];
        String err = (String) res[1];
        if (err != null) {
            return new String[]{"0", err};
        }
        if (ok == 0) {
            return new String[]{"0",
                    "没有解析到有效书源（需含 contentRule，且含 searchUrl+listRule 或 indexChapters）"};
        }
        return new String[]{String.valueOf(ok), null};
    }

    /** 统计信息文本。 */
    public static String statText(Context c) {
        int all = SourceStore.all(c).size();
        int custom = SourceStore.userCount(c);
        int enabled = SourceStore.load(c).size();
        return "共 " + all + " 个（内置 " + (all - custom) + " · 自定义 " + custom
                + "） · 已启用 " + enabled + " 个";
    }

    // ================= 连通性测试 =================

    /** 单个书源的测试结果。 */
    public static class TestResult {
        public boolean ok;
        /** 失败原因或成功说明。 */
        public String message = "";
        /** 搜到的条目数。 */
        public int count;
        /** 前几条书名，用于确认规则是否真的抓对了。 */
        public final List<String> sample = new ArrayList<>();
    }

    /** 测试回调，主线程。 */
    public interface TestCallback {
        void onResult(TestResult result);
    }

    /**
     * 用一个关键字试搜一次，验证书源规则是否仍然可用。
     *
     * <p>不启用链接式/索引式书源（它们没有搜索入口），会直接给出说明。</p>
     */
    public static void test(final BookSource s, final String keyword, final TestCallback cb) {
        final TestResult r = new TestResult();
        if (s == null) {
            r.message = "书源不存在";
            post(cb, r);
            return;
        }
        if (!s.canSearch()) {
            r.message = s.isLinkOnly()
                    ? "该来源仅支持链接打开，无法搜索"
                    : "该来源未配置搜索规则";
            post(cb, r);
            return;
        }
        final String key = (keyword == null || keyword.trim().isEmpty())
                ? "剑" : keyword.trim();
        App.POOL.execute(new Runnable() {
            @Override
            public void run() {
                long t0 = System.currentTimeMillis();
                try {
                    String url = Rules.fill(s.searchUrl, key, 1);
                    // 走书源自己的搜索入口：POST 型书源（书海阁等）也能测
                    String html = s.fetchSearchHtml(key, 1);
                    if (html == null || html.isEmpty()) {
                        throw new Exception("返回内容为空");
                    }
                    List<String[]> items = Rules.findAll(html, s.listRule, 3);
                    long ms = System.currentTimeMillis() - t0;
                    r.count = items.size();
                    if (items.isEmpty()) {
                        r.message = "规则可用但没匹配到结果（" + ms + "ms），"
                                + "可能关键字无书或列表规则失效";
                    } else {
                        r.ok = true;
                        int n = Math.min(3, items.size());
                        for (int i = 0; i < n; i++) {
                            String name = items.get(i)[1];
                            r.sample.add(name == null || name.trim().isEmpty()
                                    ? "（未取到书名）" : name.trim());
                        }
                        r.message = "连通正常，" + ms + "ms 内匹配到 " + items.size() + " 条";
                    }
                } catch (Exception e) {
                    r.message = "失败：" + (e.getMessage() == null
                            ? e.getClass().getSimpleName() : e.getMessage());
                }
                post(cb, r);
            }
        });
    }

    private static void post(final TestCallback cb, final TestResult r) {
        App.UI.post(new Runnable() {
            @Override
            public void run() {
                cb.onResult(r);
            }
        });
    }
}
