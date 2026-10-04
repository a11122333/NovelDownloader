package com.niganma.noveldown;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 繁简转换（字符级）。基于 OpenCC 的单字映射表（assets/ts.txt 繁→简、st.txt 简→繁）。
 * 首次使用时懒加载为 char[65536] 查表，转换即一次线性扫描。
 */
public final class CharConv {

    /** 不转换。 */
    public static final int NONE = 0;
    /** 繁体 → 简体。 */
    public static final int T2S = 1;
    /** 简体 → 繁体。 */
    public static final int S2T = 2;

    private static final String ASSET_T2S = "ts.txt";
    private static final String ASSET_S2T = "st.txt";

    private static char[] tsMap;
    private static char[] stMap;

    private CharConv() {}

    /** 按 mode 转换文本；mode 为 NONE 或表缺失时原样返回。 */
    public static String convert(Context c, String s, int mode) {
        if (s == null || s.isEmpty() || mode == NONE) {
            return s;
        }
        char[] map = map(c, mode);
        if (map == null) {
            return s;
        }
        char[] buf = s.toCharArray();
        for (int i = 0; i < buf.length; i++) {
            char d = map[buf[i]];
            if (d != 0 && d != buf[i]) {
                buf[i] = d;
            }
        }
        return new String(buf);
    }

    private static char[] map(Context c, int mode) {
        if (mode == T2S) {
            if (tsMap == null) {
                synchronized (CharConv.class) {
                    if (tsMap == null) {
                        tsMap = load(c, ASSET_T2S);
                    }
                }
            }
            return tsMap;
        }
        if (mode == S2T) {
            if (stMap == null) {
                synchronized (CharConv.class) {
                    if (stMap == null) {
                        stMap = load(c, ASSET_S2T);
                    }
                }
            }
            return stMap;
        }
        return null;
    }

    /** 读取资源文件，每行 "源字 目标字"，构建稀疏查表。 */
    private static char[] load(Context c, String asset) {
        char[] map = new char[0x10000];
        try {
            InputStream in = c.getAssets().open(asset);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) {
                bos.write(b, 0, n);
            }
            in.close();
            String[] lines = new String(bos.toByteArray(), "utf-8").split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                int sp = line.indexOf(' ');
                if (sp <= 0 || sp + 1 >= line.length()) {
                    continue;
                }
                String key = line.substring(0, sp);
                String val = line.substring(sp + 1).trim();
                if (key.length() == 1 && !val.isEmpty()) {
                    map[key.charAt(0)] = val.charAt(0);
                }
            }
        } catch (Exception e) {
            return null;
        }
        return map;
    }
}
