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
    public static final String VERSION = "1.6";

    private App() {}
}