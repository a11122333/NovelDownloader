package com.niganma.noveldown;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 全局共享的线程池与主线程 Handler。 */
public final class App {

    public static final ExecutorService POOL = Executors.newFixedThreadPool(6);
    public static final Handler UI = new Handler(Looper.getMainLooper());

    public static final String APP_NAME = "小说下载器";
    public static final String CREDIT = "by泥甘麻 qq2211927635";
    /** 兜底版本号；实际显示以 APK 的 versionName 为准（见 {@link #version(android.content.Context)}）。 */
    public static final String VERSION = "1.10";

    /** 读取 APK 实际的 versionName，避免硬编码版本号忘记更新。 */
    public static String version(android.content.Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return VERSION;
        }
    }

    private App() {}
}