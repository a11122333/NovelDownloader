package com.niganma.noveldown;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 书源定义（本 App 自有的简化规则格式，基于正则抽取）。
 *
 * <pre>
 * {
 *   "name":         "示例书源",
 *   "charset":      "utf-8",                       // 可选，默认 utf-8（gbk 亦可）
 *   "searchUrl":    "https://site/search?q={{key}}&p={{page}}",
 *   "listRule":     "book\\.aspx\\?id=(\\d+)[^>]*>\\s*([^<]+)\\s*</a>(?:\\s*作者[:：]\\s*([^<\\s]+))?",
 *   "chapterList":  "<a[^>]*href=\"([^\"]+)\"[^>]*>\\s*(第[^<]*章[^<]*)\\s*</a>",
 *   "contentRule":  "&lt;div[^&gt;]*id=\"content\"[^&gt;]*&gt;(.*?)&lt;/div&gt;",
 *   "replace":      [["&lt;br&gt;", "\n"], ["&amp;nbsp;", " "], ["&lt;[^&gt;]+&gt;", ""]]
 * }
 * </pre>
 *
 * listRule 的捕获组依次为：1=书籍链接  2=书名  3=作者(可选)。
 * chapterList 的捕获组依次为：1=章节链接  2=章节标题。
 * contentRule 的第 1 组为正文 HTML，之后按 replace 规则清洗成纯文本。
 * tocUrl(可选): 目录页模板，{{book}} 会被替换成书籍详情页 URL；
 *              有些站点的详情页只列出最新章节，完整目录在单独页面。
 * tocPages(可选): 目录分页规则，第 1 组为其余目录页 URL（可匹配多个），
 *                 会逐页抓取并合并章节（按 URL 去重）。
 * contentPages(可选): 章节正文分页规则，第 1 组为本章「下一页」URL，
 *                     会逐页抓取同一章内容并合并（如零点看书的正文分页）。
 * indexChapters(可选): 「索引式章节」配置。适用于目录由 JS 动态渲染、
 *                     静态 HTML 里拿不到章节链接的站点（如小说阅读 cooks.tw）。
 *                     这类站点的章节 ID 往往连续递增，可从「最新章节 ID」+「目录下标」
 *                     反推出每章 URL。配置字段：
 *                       · lastIdRule : 在详情页取「最新章节 ID」（第 1 组）
 *                       · catalogRule: 在任一章节阅读页取「目录项」（第 1 组=下标，2=标题）
 *                       · urlTemplate: 章节 URL 模板，{{article}}=书籍ID，{{id}}=章节ID
 *                       · articleRule: 可选，从详情页 URL 取书籍ID（默认 articleid=(\d+)）
 * 若未配置 chapterList，则把搜索到的详情页当作单章处理。
 */
public class BookSource {

    /** 索引式章节配置（见类注释）。 */
    public static class IndexChapters {
        public String lastIdRule = "";
        public String catalogRule = "";
        public String urlTemplate = "";
        public String articleRule = "articleid=(\\d+)";
    }

    public String name = "未命名";
    public String charset = "utf-8";
    public String baseUrl = "";
    public String searchUrl = "";
    public String listRule = "";
    public String chapterList = "";
    public String contentRule = "";
    public String tocUrl = "";
    public String tocPages = "";
    public String contentPages = "";
    public IndexChapters indexChapters;
    public List<String[]> replace = new ArrayList<>();

    /** 是否为「索引式章节」书源（无需搜索、靠书籍链接直接打开）。 */
    public boolean isIndexed() {
        return indexChapters != null
                && !indexChapters.lastIdRule.isEmpty()
                && !indexChapters.catalogRule.isEmpty()
                && !indexChapters.urlTemplate.isEmpty();
    }

    /** 是否可用于关键字搜索。 */
    public boolean canSearch() {
        return !searchUrl.isEmpty() && !listRule.isEmpty();
    }

    public static BookSource fromJson(JSONObject o) {
        BookSource s = new BookSource();
        s.name = o.optString("name", "未命名");
        s.charset = o.optString("charset", "utf-8");
        s.baseUrl = o.optString("baseUrl", "");
        s.searchUrl = o.optString("searchUrl", "");
        s.listRule = o.optString("listRule", "");
        s.chapterList = o.optString("chapterList", "");
        s.contentRule = o.optString("contentRule", "");
        s.tocUrl = o.optString("tocUrl", "");
        s.tocPages = o.optString("tocPages", "");
        s.contentPages = o.optString("contentPages", "");
        JSONObject idx = o.optJSONObject("indexChapters");
        if (idx != null) {
            IndexChapters ix = new IndexChapters();
            ix.lastIdRule = idx.optString("lastIdRule", "");
            ix.catalogRule = idx.optString("catalogRule", "");
            ix.urlTemplate = idx.optString("urlTemplate", "");
            ix.articleRule = idx.optString("articleRule", "articleid=(\\d+)");
            s.indexChapters = ix;
        }
        JSONArray rep = o.optJSONArray("replace");
        if (rep != null) {
            for (int i = 0; i < rep.length(); i++) {
                JSONArray pair = rep.optJSONArray(i);
                if (pair != null && pair.length() >= 2) {
                    s.replace.add(new String[]{pair.optString(0), pair.optString(1)});
                }
            }
        }
        return s;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("charset", charset);
            if (!baseUrl.isEmpty()) {
                o.put("baseUrl", baseUrl);
            }
            o.put("searchUrl", searchUrl);
            o.put("listRule", listRule);
            o.put("chapterList", chapterList);
            o.put("contentRule", contentRule);
            if (isIndexed()) {
                JSONObject ix = new JSONObject();
                ix.put("lastIdRule", indexChapters.lastIdRule);
                ix.put("catalogRule", indexChapters.catalogRule);
                ix.put("urlTemplate", indexChapters.urlTemplate);
                ix.put("articleRule", indexChapters.articleRule);
                o.put("indexChapters", ix);
            }
            if (!tocUrl.isEmpty()) {
                o.put("tocUrl", tocUrl);
            }
            if (!tocPages.isEmpty()) {
                o.put("tocPages", tocPages);
            }
            if (!contentPages.isEmpty()) {
                o.put("contentPages", contentPages);
            }
            if (!replace.isEmpty()) {
                JSONArray rep = new JSONArray();
                for (String[] p : replace) {
                    JSONArray pair = new JSONArray();
                    pair.put(p[0]);
                    pair.put(p.length > 1 ? p[1] : "");
                    rep.put(pair);
                }
                o.put("replace", rep);
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 检查必填项是否齐全。索引式书源无需 searchUrl / listRule。 */
    public String validate() {
        if (!isIndexed()) {
            if (searchUrl.isEmpty()) {
                return "缺少 searchUrl";
            }
            if (listRule.isEmpty()) {
                return "缺少 listRule";
            }
        }
        if (contentRule.isEmpty()) {
            return "缺少 contentRule";
        }
        return null;
    }

    /** 判断给定 URL 是否属于本书源（用于「粘贴书籍链接」直接打开）。 */
    public boolean matches(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String host = null;
        try {
            host = java.net.URI.create(url).getHost();
        } catch (Exception ignored) {
        }
        if (host == null) {
            return false;
        }
        String base = baseUrl;
        if (base.isEmpty()) {
            try {
                base = java.net.URI.create(searchUrl).getScheme()
                        + "://" + java.net.URI.create(searchUrl).getAuthority();
            } catch (Exception ignored) {
            }
        }
        String bHost = null;
        try {
            bHost = java.net.URI.create(base).getHost();
        } catch (Exception ignored) {
        }
        if (bHost != null && host.equalsIgnoreCase(bHost)) {
            return true;
        }
        return !baseUrl.isEmpty() && url.startsWith(baseUrl);
    }

    public String base() {
        if (!baseUrl.isEmpty()) {
            return baseUrl;
        }
        try {
            java.net.URI u = java.net.URI.create(searchUrl);
            return u.getScheme() + "://" + u.getAuthority() + "/";
        } catch (Exception e) {
            return searchUrl;
        }
    }
}