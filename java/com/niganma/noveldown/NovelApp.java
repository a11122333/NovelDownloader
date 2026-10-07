package com.niganma.noveldown;

import android.app.Application;
import android.content.Context;

/** 进程入口：保存全局 Context 并预加载下载历史。 */
public class NovelApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        App.init(this);
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // 尽早拿到 Context，后台下载即使界面已销毁也能用它保存文件
        App.init(base);
    }
}
