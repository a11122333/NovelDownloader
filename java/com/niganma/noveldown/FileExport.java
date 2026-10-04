package com.niganma.noveldown;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** 把下载好的正文保存为明文 .txt（无任何加密）。 */
public final class FileExport {

    private FileExport() {}

    public static String fileName(String bookName) {
        String n = bookName == null ? "novel" : bookName;
        n = n.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        if (n.isEmpty()) {
            n = "novel";
        }
        return n + ".txt";
    }

    /** @return 保存位置的可读描述 */
    public static String save(Context ctx, String displayName, String content) throws IOException {
        byte[] data = content.getBytes("utf-8");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/小说下载器");
            Uri uri = ctx.getContentResolver()
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) {
                throw new IOException("系统拒绝创建文件");
            }
            OutputStream os = ctx.getContentResolver().openOutputStream(uri);
            if (os == null) {
                throw new IOException("无法打开输出流");
            }
            os.write(data);
            os.flush();
            os.close();
            return "下载/小说下载器/" + displayName;
        } else {
            File dir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "小说下载器");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("无法创建目录：" + dir);
            }
            File f = new File(dir, displayName);
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(data);
            fo.flush();
            fo.close();
            return f.getAbsolutePath();
        }
    }
}