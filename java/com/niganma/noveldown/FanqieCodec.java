package com.niganma.noveldown;

import android.content.Context;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 番茄小说正文的「字体反爬」解码。
 *
 * 番茄阅读页正文里会把一部分字替换成私有区（PUA）码点，靠自定义字体渲染。
 * 该字体的字形顺序对应一份固定的字符表（assets/fanqie_charset.json），
 * 因此可用「PUA 码点 - 基准码点」作下标查表还原真字。
 *
 * 基准码点（CODE）与字符表取自番茄网页当前的混淆字体，字体一旦轮换需同步更新本表。
 */
public final class FanqieCodec {

    /** 各模式 PUA 码点范围（含端点）。 */
    private static final int[][] CODE = {{58344, 58715}, {58345, 58716}};

    private static final String ASSET = "fanqie_charset.json";

    private static String[][] table;

    private FanqieCodec() {}

    /** 按 mode 解码整段文本；表缺失时原样返回。 */
    public static String decode(Context c, String s, int mode) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        String[][] t = table(c);
        if (t == null || mode < 0 || mode >= t.length) {
            return s;
        }
        int[] range = CODE[mode];
        char[] buf = s.toCharArray();
        for (int i = 0; i < buf.length; i++) {
            char ch = buf[i];
            if (ch < range[0] || ch > range[1]) {
                continue;
            }
            int bias = ch - range[0];
            if (bias >= range[1] - range[0] + 1 || bias >= t[mode].length) {
                continue;
            }
            String rep = t[mode][bias];
            if (rep != null && !rep.isEmpty() && !"?".equals(rep)) {
                buf[i] = rep.charAt(0);
            }
        }
        return new String(buf);
    }

    private static String[][] table(Context c) {
        if (table != null) {
            return table;
        }
        synchronized (FanqieCodec.class) {
            if (table != null) {
                return table;
            }
            try {
                InputStream in = c.getAssets().open(ASSET);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) {
                    bos.write(b, 0, n);
                }
                in.close();
                JSONArray arr = new JSONArray(new String(bos.toByteArray(), "utf-8"));
                String[][] t = new String[arr.length()][];
                for (int i = 0; i < arr.length(); i++) {
                    JSONArray row = arr.optJSONArray(i);
                    if (row == null) {
                        t[i] = new String[0];
                        continue;
                    }
                    String[] line = new String[row.length()];
                    for (int j = 0; j < row.length(); j++) {
                        line[j] = row.optString(j, "?");
                    }
                    t[i] = line;
                }
                table = t;
            } catch (Exception e) {
                table = new String[0][];
            }
        }
        return table;
    }
}
