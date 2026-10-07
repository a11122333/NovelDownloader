package com.niganma.noveldown;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/**
 * 极简 HTTP 工具，支持 gzip、重定向、POST、Cookie 与自定义 Referer / 编码。
 *
 * <p>Cookie 按主机存在内存里：不少站点（例如书海阁）的搜索是 POST，而且必须先
 * 访问一次首页拿到 WAF 会话 Cookie，否则搜索页永远是空的。所以这里做了个极简
 * Cookie 罐——不需要登录，只是把服务端下发的 Cookie 带回去。</p>
 */
public final class Http {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    /** 主机 → Cookie 串（"a=1; b=2"）。 */
    private static final java.util.Map<String, String> COOKIES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private Http() {}

    public static String get(String url) throws IOException {
        return get(url, "utf-8", null);
    }

    public static String get(String url, String charset) throws IOException {
        return get(url, charset, null, null);
    }

    public static String get(String url, String charset, String referer) throws IOException {
        return get(url, charset, referer, null);
    }

    public static String get(String url, String charset, String referer, String userAgent)
            throws IOException {
        return request(url, null, charset, referer, userAgent);
    }

    /** POST application/x-www-form-urlencoded。 */
    public static String post(String url, String body, String charset, String referer,
                              String userAgent) throws IOException {
        return request(url, body == null ? "" : body, charset, referer, userAgent);
    }

    private static String request(String url, String postBody, String charset, String referer,
                                  String userAgent) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent",
                (userAgent == null || userAgent.isEmpty()) ? UA : userAgent);
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");
        c.setRequestProperty("Accept-Encoding", "gzip");
        if (referer != null && !referer.isEmpty()) {
            c.setRequestProperty("Referer", referer);
        }
        String cookie = COOKIES.get(host(url));
        if (cookie != null && !cookie.isEmpty()) {
            c.setRequestProperty("Cookie", cookie);
        }
        c.setInstanceFollowRedirects(true);
        if (postBody != null) {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            java.io.OutputStream os = c.getOutputStream();
            try {
                os.write(postBody.getBytes("utf-8"));
                os.flush();
            } finally {
                os.close();
            }
        }
        int code = c.getResponseCode();
        storeCookies(url, c);
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) {
            throw new IOException("HTTP " + code);
        }
        String enc = c.getContentEncoding();
        if (enc != null && enc.toLowerCase().contains("gzip")) {
            in = new GZIPInputStream(in);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        c.disconnect();
        String cs = (charset == null || charset.isEmpty()) ? "utf-8" : charset;
        return new String(bos.toByteArray(), cs);
    }

    /** 记住服务端下发的 Cookie（同名覆盖）。 */
    private static void storeCookies(String url, HttpURLConnection c) {
        try {
            java.util.List<String> set = c.getHeaderFields().get("Set-Cookie");
            if (set == null) {
                for (java.util.Map.Entry<String, java.util.List<String>> e
                        : c.getHeaderFields().entrySet()) {
                    if (e.getKey() != null && "set-cookie".equalsIgnoreCase(e.getKey())) {
                        set = e.getValue();
                        break;
                    }
                }
            }
            if (set == null || set.isEmpty()) {
                return;
            }
            String key = host(url);
            StringBuilder sb = new StringBuilder();
            String old = COOKIES.get(key);
            if (old != null && !old.isEmpty()) {
                sb.append(old).append("; ");
            }
            for (String one : set) {
                if (one == null) {
                    continue;
                }
                int semi = one.indexOf(';');
                String pair = (semi > 0 ? one.substring(0, semi) : one).trim();
                if (!pair.isEmpty() && pair.indexOf('=') > 0) {
                    sb.append(pair).append("; ");
                }
            }
            COOKIES.put(key, sb.toString());
        } catch (Exception ignored) {
        }
    }

    private static String host(String url) {
        try {
            String h = new URL(url).getHost();
            return h == null ? url : h;
        } catch (Exception e) {
            return url;
        }
    }
}