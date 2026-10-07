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
 *                     静态 HTML 里拿不到章节链接的站点。
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
    public String userAgent = "";
    public String baseUrl = "";
    public String searchUrl = "";
    /** 搜索方式："get"（默认）或 "post"。 */
    public String searchMethod = "get";
    /** POST 搜索的表单体，支持 {{key}} / {{page}}，如 "searchkey={{key}}"。 */
    public String searchBody = "";
    /** 搜索前先 GET 一次这个地址（拿会话 Cookie），支持 {{book}}=搜索 URL。 */
    public String warmUp = "";
    public String listRule = "";
    public String chapterList = "";
    public String contentRule = "";
    public String tocUrl = "";
    public String tocPages = "";
    public String contentPages = "";
    public IndexChapters indexChapters;
    /** 列表式目录：在详情页用正则取出「章节ID + 标题」多项（组1=ID，组2=标题）。 */
    public String chapterIdRule = "";
    /** 章节 URL 模板，{{id}} 会被替换为 chapterIdRule 取到的章节 ID。 */
    public String chapterUrlTemplate = "";
    /** 搜索结果 URL 模板，{{id}} 会被替换为 listRule 第 1 组（用于接口返回书籍ID 的场景）。 */
    public String bookUrlTemplate = "";
    /** 正文是否需要「番茄式」PUA 字体解码。 */
    public boolean puaDecode = false;
    /** PUA 解码模式（对应 fanqie_charset.json 的下标），默认 0。 */
    public int puaMode = 0;
    /** 正文是否为 JSON 转义字符串（需先反转义再清洗）。 */
    public boolean jsonEscape = false;
    /** 导出时给每个非空段落添加的前缀（如两个全角空格「　　」）。 */
    public String paragraphIndent = "";
    public List<String[]> replace = new ArrayList<>();

    /** 是否为「索引式章节」书源（无需搜索、靠书籍链接直接打开）。 */
    public boolean isIndexed() {
        return indexChapters != null
                && !indexChapters.lastIdRule.isEmpty()
                && !indexChapters.catalogRule.isEmpty()
                && !indexChapters.urlTemplate.isEmpty();
    }

    /** 是否为「列表式章节」书源（详情页给出章节 ID 列表，无需搜索）。 */
    public boolean hasIdChapters() {
        return !chapterIdRule.isEmpty() && !chapterUrlTemplate.isEmpty();
    }

    /** 是否为「仅支持链接打开」的书源（无搜索能力）。 */
    public boolean isLinkOnly() {
        return isIndexed() || hasIdChapters();
    }

    /** 是否可用于关键字搜索。 */
    public boolean canSearch() {
        return !searchUrl.isEmpty() && !listRule.isEmpty();
    }

    /**
     * 抓搜索页（GET / POST 都支持）。
     *
     * <p>有些站点的搜索只收 POST，而且必须先访问一次首页拿到 WAF 会话 Cookie
     * （否则结果页永远是空的），这时用 {@link #warmUp} + {@link #searchMethod}
     * 描述即可。</p>
     */
    public String fetchSearchHtml(String key, int page) throws java.io.IOException {
        String url = Rules.fill(searchUrl, key, page);
        if (warmUp != null && !warmUp.isEmpty()) {
            try {
                Http.get(Rules.fillBook(warmUp, url), charset, null, userAgent);
            } catch (java.io.IOException ignored) {
                // 预热失败不致命，继续按原方式搜索
            }
        }
        if ("post".equalsIgnoreCase(searchMethod)) {
            String tpl = (searchBody == null || searchBody.isEmpty())
                    ? "searchkey={{key}}" : searchBody;
            return Http.post(url, Rules.fill(tpl, key, page), charset, url, userAgent);
        }
        return Http.get(url, charset, null, userAgent);
    }

    public static BookSource fromJson(JSONObject o) {
        BookSource s = new BookSource();
        s.name = o.optString("name", "未命名");
        s.charset = o.optString("charset", "utf-8");
        s.userAgent = o.optString("userAgent", "");
        s.baseUrl = o.optString("baseUrl", "");
        s.searchUrl = o.optString("searchUrl", "");
        s.searchMethod = o.optString("searchMethod", "get");
        s.searchBody = o.optString("searchBody", "");
        s.warmUp = o.optString("warmUp", "");
        s.listRule = o.optString("listRule", "");
        s.chapterList = o.optString("chapterList", "");
        s.contentRule = o.optString("contentRule", "");
        s.tocUrl = o.optString("tocUrl", "");
        s.tocPages = o.optString("tocPages", "");
        s.contentPages = o.optString("contentPages", "");
        s.chapterIdRule = o.optString("chapterIdRule", "");
        s.chapterUrlTemplate = o.optString("chapterUrlTemplate", "");
        s.bookUrlTemplate = o.optString("bookUrlTemplate", "");
        s.puaDecode = o.optBoolean("puaDecode", false);
        s.puaMode = o.optInt("puaMode", 0);
        s.jsonEscape = o.optBoolean("jsonEscape", false);
        s.paragraphIndent = o.optString("paragraphIndent", "");
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
            if (!userAgent.isEmpty()) {
                o.put("userAgent", userAgent);
            }
            if (!baseUrl.isEmpty()) {
                o.put("baseUrl", baseUrl);
            }
            o.put("searchUrl", searchUrl);
            if (!"get".equalsIgnoreCase(searchMethod)) {
                o.put("searchMethod", searchMethod);
            }
            if (!searchBody.isEmpty()) {
                o.put("searchBody", searchBody);
            }
            if (!warmUp.isEmpty()) {
                o.put("warmUp", warmUp);
            }
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
            if (!chapterIdRule.isEmpty()) {
                o.put("chapterIdRule", chapterIdRule);
                o.put("chapterUrlTemplate", chapterUrlTemplate);
            }
            if (puaDecode) {
                o.put("puaDecode", true);
                o.put("puaMode", puaMode);
            }
            if (!paragraphIndent.isEmpty()) {
                o.put("paragraphIndent", paragraphIndent);
            }
            if (!tocUrl.isEmpty()) {
                o.put("tocUrl", tocUrl);
            }
            // 这两个字段 fromJson 会读，导出时必须带上，否则「导出→导入」
            // 之后正文不再反转义、搜索链接模板也会丢
            if (!bookUrlTemplate.isEmpty()) {
                o.put("bookUrlTemplate", bookUrlTemplate);
            }
            if (jsonEscape) {
                o.put("jsonEscape", true);
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

    /** 检查必填项是否齐全。链接打开型书源（索引/列表）无需 searchUrl / listRule。 */
    public String validate() {
        if (!isLinkOnly()) {
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