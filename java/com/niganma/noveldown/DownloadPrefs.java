package com.niganma.noveldown;

import android.content.Context;
import android.content.SharedPreferences;

/** 下载相关偏好的统一读写，避免各界面各写一份 key 导致不同步。 */
public final class DownloadPrefs {

    private static final String PREF = "novel_down";

    public static final String KEY_THREADS = "download_threads";
    public static final String KEY_CONV = "download_conv";
    public static final String KEY_FROM = "download_from";
    public static final String KEY_TO = "download_to";
    /** 导出格式（见 Exporter.FORMAT_*）。 */
    public static final String KEY_FORMAT = "export_format";
    /** 文件名模板，支持 {title} / {author} / {source} / {count} / {date} / {format}。 */
    public static final String KEY_TEMPLATE = "export_template";
    /** 分章 TXT 每个文件包含多少章。 */
    public static final String KEY_SPLIT_GROUP = "export_split_group";

    /** 每个文件章数的取值范围与默认值。 */
    public static final int SPLIT_GROUP_MIN = 1;
    public static final int SPLIT_GROUP_MAX = 200;
    public static final int SPLIT_GROUP_DEFAULT = 1;

    /** 同时下载的整书任务数上限（与 DownloadManager 的并发数一致）。 */
    public static final int MAX_JOBS = 3;

    private DownloadPrefs() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static int threads(Context c) {
        return Downloader.clampThreads(
                prefs(c).getInt(KEY_THREADS, Downloader.DEFAULT_THREADS));
    }

    public static void setThreads(Context c, int n) {
        prefs(c).edit().putInt(KEY_THREADS, Downloader.clampThreads(n)).apply();
    }

    public static int conv(Context c) {
        return clampConv(prefs(c).getInt(KEY_CONV, CharConv.NONE));
    }

    public static void setConv(Context c, int mode) {
        prefs(c).edit().putInt(KEY_CONV, clampConv(mode)).apply();
    }

    public static int from(Context c, int total) {
        return clampRange(prefs(c).getInt(KEY_FROM, 1), 1, total);
    }

    public static int to(Context c, int total) {
        return clampRange(prefs(c).getInt(KEY_TO, total), 1, total);
    }

    public static void setRange(Context c, int from, int to) {
        prefs(c).edit().putInt(KEY_FROM, from).putInt(KEY_TO, to).apply();
    }

    public static int clampConv(int v) {
        if (v < CharConv.NONE || v > CharConv.S2T) {
            return CharConv.NONE;
        }
        return v;
    }

    public static int clampRange(int v, int min, int max) {
        if (v < min) {
            return min;
        }
        return Math.min(v, max);
    }

    public static String convName(int mode) {
        if (mode == CharConv.T2S) {
            return "繁 → 简";
        }
        if (mode == CharConv.S2T) {
            return "简 → 繁";
        }
        return "不转换";
    }

    // ================= 导出格式与文件名模板 =================

    public static int format(Context c) {
        return clampFormat(prefs(c).getInt(KEY_FORMAT, Exporter.FORMAT_TXT));
    }

    public static void setFormat(Context c, int f) {
        prefs(c).edit().putInt(KEY_FORMAT, clampFormat(f)).apply();
    }

    public static int clampFormat(int v) {
        if (v < Exporter.FORMAT_TXT || v > Exporter.FORMAT_EPUB) {
            return Exporter.FORMAT_TXT;
        }
        return v;
    }

    public static String template(Context c) {
        String t = prefs(c).getString(KEY_TEMPLATE, Exporter.DEFAULT_TEMPLATE);
        return (t == null || t.trim().isEmpty()) ? Exporter.DEFAULT_TEMPLATE : t;
    }

    public static void setTemplate(Context c, String t) {
        String v = (t == null || t.trim().isEmpty()) ? Exporter.DEFAULT_TEMPLATE : t.trim();
        prefs(c).edit().putString(KEY_TEMPLATE, v).apply();
    }

    public static int splitGroup(Context c) {
        return clampSplitGroup(prefs(c).getInt(KEY_SPLIT_GROUP, SPLIT_GROUP_DEFAULT));
    }

    public static void setSplitGroup(Context c, int n) {
        prefs(c).edit().putInt(KEY_SPLIT_GROUP, clampSplitGroup(n)).apply();
    }

    public static int clampSplitGroup(int v) {
        if (v < SPLIT_GROUP_MIN) {
            return SPLIT_GROUP_MIN;
        }
        return Math.min(v, SPLIT_GROUP_MAX);
    }

    /** 「每文件 N 章」的可读描述。 */
    public static String splitGroupName(int n) {
        return n <= 1 ? "每章一个文件" : ("每 " + n + " 章一个文件");
    }

    /** 按当前模板预测一个书名的输出文件名，用于设置页展示效果。 */
    public static String previewName(Context c, String bookName) {
        SearchBook b = new SearchBook("", bookName, "作者", "");
        return Exporter.buildFileName(template(c), b, "书源", 100, format(c), false)
                + Exporter.extension(format(c));
    }
}
