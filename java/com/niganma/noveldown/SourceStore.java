package com.niganma.noveldown;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 书源仓库：
 *  · 内置书源：随 App 打包在 assets/builtin_sources.json，开箱即用（由原版 App 的书源适配而来）。
 *  · 自定义书源：用户从剪贴板导入，存 SharedPreferences。
 *  · 启用 / 停用：按书源名记录停用集合，搜索时自动跳过。
 */
public final class SourceStore {

    private static final String PREF = "novel_down";
    private static final String KEY_USER = "user_sources";
    private static final String KEY_DISABLED = "disabled_sources";
    private static final String ASSET = "builtin_sources.json";

    private SourceStore() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ---------------- 内置书源 ----------------

    private static String builtinJson(Context c) {
        try {
            InputStream in = c.getAssets().open(ASSET);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            return new String(bos.toByteArray(), "utf-8");
        } catch (Exception e) {
            return "[]";
        }
    }

    // ---------------- 自定义书源 ----------------

    public static String userRaw(Context c) {
        return sp(c).getString(KEY_USER, "[]");
    }

    public static void saveUserRaw(Context c, String json) {
        sp(c).edit().putString(KEY_USER, json).apply();
    }

    public static int userCount(Context c) {
        return parse(userRaw(c)).size();
    }

    /** 内置书源名称集合（用于区分内置 / 自定义）。 */
    public static Set<String> builtinNames(Context c) {
        Set<String> set = new HashSet<>();
        for (BookSource s : parse(builtinJson(c))) {
            set.add(s.name);
        }
        return set;
    }

    // ---------------- 启用 / 停用 ----------------

    public static Set<String> disabledNames(Context c) {
        Set<String> set = new HashSet<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString(KEY_DISABLED, "[]"));
            for (int i = 0; i < a.length(); i++) {
                set.add(a.optString(i));
            }
        } catch (Exception ignored) {
        }
        return set;
    }

    public static void setDisabledNames(Context c, Set<String> names) {
        JSONArray a = new JSONArray();
        for (String n : names) {
            a.put(n);
        }
        sp(c).edit().putString(KEY_DISABLED, a.toString()).apply();
    }

    public static boolean isEnabled(Context c, String name) {
        return !disabledNames(c).contains(name);
    }

    // ---------------- 解析与读取 ----------------

    private static List<BookSource> parse(String json) {
        List<BookSource> list = new ArrayList<>();
        if (json == null) {
            return list;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                BookSource s = BookSource.fromJson(o);
                if (!s.canSearch() && !s.isLinkOnly()) {
                    continue;
                }
                list.add(s);
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    /** 全部书源：内置 + 自定义，按名称去重（自定义覆盖同名内置）。 */
    public static List<BookSource> all(Context c) {
        Map<String, BookSource> map = new LinkedHashMap<>();
        for (BookSource s : parse(builtinJson(c))) {
            map.put(s.name, s);
        }
        for (BookSource s : parse(userRaw(c))) {
            map.put(s.name, s);
        }
        return new ArrayList<>(map.values());
    }

    /** 参与搜索的已启用书源。 */
    public static List<BookSource> load(Context c) {
        Set<String> disabled = disabledNames(c);
        List<BookSource> out = new ArrayList<>();
        for (BookSource s : all(c)) {
            if (!disabled.contains(s.name)) {
                out.add(s);
            }
        }
        return out;
    }

    /** 可参与关键字搜索的已启用书源（排除索引式等只支持链接打开的书源）。 */
    public static List<BookSource> searchable(Context c) {
        List<BookSource> out = new ArrayList<>();
        for (BookSource s : load(c)) {
            if (s.canSearch()) {
                out.add(s);
            }
        }
        return out;
    }

    /** 解析用户粘贴的 JSON：支持单个对象或数组，返回 [成功数, 报错信息]。 */
    public static Object[] importJson(Context c, String text) {
        try {
            JSONArray arr;
            String t = text == null ? "" : text.trim();
            if (t.startsWith("[")) {
                arr = new JSONArray(t);
            } else {
                arr = new JSONArray();
                arr.put(new JSONObject(t));
            }
            JSONArray merged = new JSONArray();
            try {
                JSONArray old = new JSONArray(userRaw(c));
                for (int i = 0; i < old.length(); i++) {
                    merged.put(old.get(i));
                }
            } catch (Exception ignored) {
            }
            int ok = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                BookSource s = BookSource.fromJson(o);
                if (s.contentRule.isEmpty()) {
                    continue;
                }
                if (!s.canSearch() && !s.isLinkOnly()) {
                    continue;
                }
                merged.put(o);
                ok++;
            }
            if (ok > 0) {
                saveUserRaw(c, merged.toString());
            }
            return new Object[]{ok, null};
        } catch (Exception e) {
            return new Object[]{0, "JSON 解析失败：" + e.getMessage()};
        }
    }

    /** 清空全部自定义书源。 */
    public static void clearUser(Context c) {
        saveUserRaw(c, "[]");
    }
}