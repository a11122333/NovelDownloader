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
 * 若未配置 chapterList，则把搜索到的详情页当作单章处理。
 */
public class BookSource {

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
    public List<String[]> replace = new ArrayList<>();

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

    /** 检查必填项是否齐全。 */
    public String validate() {
        if (searchUrl.isEmpty()) {
            return "缺少 searchUrl";
        }
        if (listRule.isEmpty()) {
            return "缺少 listRule";
        }
        if (contentRule.isEmpty()) {
            return "缺少 contentRule";
        }
        return null;
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