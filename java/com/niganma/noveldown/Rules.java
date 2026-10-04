package com.niganma.noveldown;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 基于正则的规则抽取引擎。书源的一切抽取（列表/目录/正文）都通过它完成。 */
public final class Rules {

    private static final int DOTALL = Pattern.DOTALL;

    private Rules() {}

    /** 在 text 中查找所有匹配，返回每组捕获（组数固定为 groups，缺失补 null）。 */
    public static List<String[]> findAll(String text, String regex, int groups) {
        List<String[]> out = new ArrayList<>();
        if (text == null || regex == null || regex.isEmpty()) {
            return out;
        }
        try {
            Matcher m = Pattern.compile(regex, DOTALL).matcher(text);
            while (m.find()) {
                String[] g = new String[groups];
                for (int i = 1; i <= groups; i++) {
                    g[i - 1] = m.groupCount() >= i ? trim(m.group(i)) : null;
                }
                out.add(g);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 返回第一个匹配的第 1 组（无组则返回整体）。 */
    public static String first(String text, String regex) {
        if (text == null || regex == null || regex.isEmpty()) {
            return null;
        }
        try {
            Matcher m = Pattern.compile(regex, DOTALL).matcher(text);
            if (m.find()) {
                return m.groupCount() >= 1 ? m.group(1) : m.group(0);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 依次执行替换规则清洗文本。 */
    public static String clean(String s, List<String[]> replace) {
        if (s == null) {
            return "";
        }
        if (replace != null) {
            for (String[] r : replace) {
                if (r == null || r.length < 2) {
                    continue;
                }
                try {
                    s = s.replaceAll(r[0], r[1] == null ? "" : r[1]);
                } catch (Exception ignored) {
                }
            }
        }
        return s.trim();
    }

    /**
     * 规范化正文排版：统一换行为 \n、去掉每行首尾空白（含 &nbsp; / 全角空格），
     * 并把连续多行空行压缩成最多一个空行，同时去掉正文首尾的空行。
     * 用于解决部分书源（如爱下书网）正文因 &lt;br&gt; 与 &nbsp; 堆积导致空行过多的问题。
     */
    public static String tidy(String s) {
        if (s == null) {
            return "";
        }
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = s.split("\n", -1);
        StringBuilder sb = new StringBuilder(s.length());
        boolean hasContent = false;
        boolean pendingBlank = false;
        for (String raw : lines) {
            String line = stripSpace(raw);
            if (line.isEmpty()) {
                // 只有前面已有内容时才允许保留一个空行
                pendingBlank = hasContent;
                continue;
            }
            if (pendingBlank) {
                sb.append('\n');
                pendingBlank = false;
            }
            if (hasContent) {
                sb.append('\n');
            }
            sb.append(line);
            hasContent = true;
        }
        return sb.toString();
    }

    /** 去掉字符串首尾的空白字符（含普通空格、制表符、&nbsp; 与全角空格）。 */
    private static String stripSpace(String s) {
        int n = s.length();
        int start = 0;
        while (start < n && isSpace(s.charAt(start))) {
            start++;
        }
        int end = n;
        while (end > start && isSpace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r'
                || c == '\u00A0' || c == '\u3000' || c == '\u000B' || c == '\f';
    }

    /** 把相对链接解析成绝对链接。 */
    public static String absUrl(String base, String href) {
        if (href == null || href.isEmpty()) {
            return base;
        }
        href = href.trim();
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return href;
        }
        try {
            return URI.create(base).resolve(href).toString();
        } catch (Exception e) {
            // 手动兜底
            try {
                URI b = URI.create(base);
                String scheme = b.getScheme();
                String host = b.getHost();
                int port = b.getPort();
                String p = href.startsWith("/") ? href : "/" + href;
                String authority = host + (port > 0 ? ":" + port : "");
                return scheme + "://" + authority + p;
            } catch (Exception e2) {
                return href;
            }
        }
    }

    /** 用关键字替换 {{key}} 与 {{page}}。 */
    public static String fill(String template, String key, int page) {
        if (template == null) {
            return null;
        }
        String k;
        try {
            k = java.net.URLEncoder.encode(key, "utf-8");
        } catch (Exception e) {
            k = key;
        }
        return template.replace("{{key}}", k).replace("{{page}}", String.valueOf(page));
    }

    /** 用书籍详情页 URL 替换 {{book}}。 */
    public static String fillBook(String template, String bookUrl) {
        if (template == null) {
            return null;
        }
        return template.replace("{{book}}", bookUrl == null ? "" : bookUrl);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}