package com.niganma.noveldown;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/** 极简 HTTP GET 工具，支持 gzip、重定向、自定义 Referer 与编码。 */
public final class Http {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

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
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
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
}