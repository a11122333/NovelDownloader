package com.niganma.noveldown;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 本地书库：列出「下载/小说下载器」里已导出的 TXT，点一本交给 {@link ReaderActivity} 阅读。
 *
 * <p>为什么扫描要走两条通道：Android 10 起应用默认处于分区存储，自己用 MediaStore
 * 写入的文件对 {@code File.listFiles()} 可能根本不可见（目录能列出但读不到，甚至
 * 直接返回 null）；而 Android 9 及以下又没有 Downloads 这个集合。两条通道各自拿
 * 自己拿得到的信息，按文件名合并成一条记录，读取时逐个尝试，哪条通就用哪条。</p>
 */
public class LibraryActivity extends Activity {

    /** 相对 Download 目录的导出子目录，与 {@link FileExport} / {@link DownloadManager} 保持一致。 */
    private static final String DIR_NAME = "小说下载器";

    /** 递归查找的层数上限：导出模板允许带子目录（如「作者/书名.txt」），但不做无限递归。 */
    private static final int MAX_DEPTH = 3;

    /** 删除他方文件时向系统申请授权的请求码。 */
    private static final int REQ_DELETE = 2001;

    /** 等待系统授权后再删的条目（Android 11+ 删除非本应用文件时必须走这一步）。 */
    private Entry pendingDelete;

    /** 一本书的两种可读方式，两条通道各填一半。 */
    private static final class Entry {
        String fileName;
        String title;
        /** File 通道拿到的绝对路径，可能为 null。 */
        String path;
        /** MediaStore 通道拿到的 content:// 地址，可能为 null。 */
        String uri;
        /** 字节数，拿不到时为 -1。 */
        long size = -1;
    }

    private final List<Entry> entries = new ArrayList<>();

    private ScrollView listScroll;
    private LinearLayout listCol;
    private View hintBox;
    private TextView hint;

    /** 后台扫描进行中，避免连点刷新并发扫描。 */
    private boolean scanning;
    /** onCreate 之后的第一轮 onResume 不需要重扫（onCreate 里已经扫过了）。 */
    private boolean firstResume = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.applyStatusBar(this);
        setContentView(buildUi());
        scan();
        applyTransitions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从阅读器返回时文件可能已经被别处改动，重新扫一遍
        if (firstResume) {
            firstResume = false;
        } else {
            scan();
        }
    }

    // ================= 界面 =================

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiUtil.SURFACE);

        LinearLayout header = UiKit.backHeader(this, "本地书库", new Runnable() {
            @Override
            public void run() {
                finish();
            }
        });
        header.addView(UiKit.textButton(this, "刷新", new Runnable() {
            @Override
            public void run() {
                scan();
            }
        }));
        root.addView(header);

        FrameLayout content = new FrameLayout(this);
        content.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        listScroll = new ScrollView(this);
        listScroll.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        listScroll.setVerticalScrollBarEnabled(false);
        listCol = new LinearLayout(this);
        listCol.setOrientation(LinearLayout.VERTICAL);
        listCol.setPadding(UiUtil.dp(this, 8), UiUtil.dp(this, 4),
                UiUtil.dp(this, 8), UiUtil.dp(this, 24));
        listScroll.addView(listCol);
        content.addView(listScroll);

        hintBox = buildHintBox();
        content.addView(hintBox);

        root.addView(content);
        return root;
    }

    /** 空状态 / 扫描中的居中提示。 */
    private View buildHintBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        int p = UiUtil.dp(this, 32);
        box.setPadding(p, p, p, p);
        box.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        box.setVisibility(View.GONE);

        hint = UiUtil.text(this, "", UiUtil.TYPE_BODY_MEDIUM, UiUtil.ON_SURFACE_VARIANT);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(UiUtil.dp(this, 4), 1f);
        box.addView(hint);
        return box;
    }

    /** 按当前数据重建列表；列表非空时保持可见，重扫不会闪成空白。 */
    private void render() {
        if (entries.isEmpty()) {
            return; // 交给 showHint 决定提示什么
        }
        listCol.removeAllViews();
        for (int i = 0; i < entries.size(); i++) {
            final Entry e = entries.get(i);
            LinearLayout row = UiKit.settingRow(this, e.title, sizeText(e.size), new Runnable() {
                @Override
                public void run() {
                    open(e);
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    confirmDelete(e);
                    return true;
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = UiUtil.dp(this, 4);
            row.setLayoutParams(lp);
            listCol.addView(row);
        }
        listScroll.setVisibility(View.VISIBLE);
        hintBox.setVisibility(View.GONE);
    }

    private void showHint(String text) {
        hint.setText(text);
        listScroll.setVisibility(View.GONE);
        hintBox.setVisibility(View.VISIBLE);
    }

    // ================= 扫描 =================

    private void scan() {
        if (scanning) {
            return;
        }
        scanning = true;
        // 已有内容时不清空，避免刷新闪一下白屏；只有空列表才显示「正在扫描」
        if (entries.isEmpty()) {
            showHint("正在扫描…");
        }
        App.POOL.execute(new Runnable() {
            @Override
            public void run() {
                final List<Entry> found = scanBlocking();
                App.UI.post(new Runnable() {
                    @Override
                    public void run() {
                        scanning = false;
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        entries.clear();
                        entries.addAll(found);
                        if (entries.isEmpty()) {
                            showHint("还没有已下载的小说。到搜索页搜一本书，下载完成后会出现在这里。");
                        } else {
                            render();
                        }
                    }
                });
            }
        });
    }

    /** 两条通道合并后的结果（后台线程调用）。 */
    private List<Entry> scanBlocking() {
        Map<String, Entry> map = new LinkedHashMap<>();
        scanFiles(map);
        scanMediaStore(map);
        List<Entry> out = new ArrayList<>(map.values());
        Collections.sort(out, (a, b) -> a.fileName.compareTo(b.fileName));
        return out;
    }

    /** 通道一：直接列目录（Android 9 及以下，或用户手动放进去的文件）。 */
    private void scanFiles(Map<String, Entry> out) {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), DIR_NAME);
            collectFiles(dir, out, 0);
        } catch (Exception ignored) {
            // 分区存储下这里可能抛错或被系统过滤，交给 MediaStore 通道
        }
    }

    private void collectFiles(File dir, Map<String, Entry> out, int depth) {
        File[] files = dir.listFiles();
        if (files == null) {
            return; // 目录不存在或没有读取权限
        }
        for (File f : files) {
            if (f.isDirectory()) {
                if (depth + 1 < MAX_DEPTH) {
                    collectFiles(f, out, depth + 1);
                }
                continue;
            }
            if (!isTxt(f.getName())) {
                continue;
            }
            Entry e = entryOf(out, f.getName());
            e.path = f.getAbsolutePath();
            e.size = f.length();
        }
    }

    /** 通道二：查 MediaStore 的 Downloads 集合（Android 10 起，按相对路径含导出目录筛选）。 */
    private void scanMediaStore(Map<String, Entry> out) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return; // RELATIVE_PATH 与 Downloads 集合都是 Q 才有的
        }
        Cursor c = null;
        try {
            Uri base = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            String[] projection = {
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
            };
            String selection = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?"
                    + " AND " + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?"
                    + " AND " + MediaStore.MediaColumns.IS_PENDING + " = 0";
            String[] args = {"%" + DIR_NAME + "%", "%.txt"};
            c = getContentResolver().query(base, projection, selection, args, null);
            while (c != null && c.moveToNext()) {
                String name = c.getString(1);
                if (name == null || !isTxt(name)) {
                    continue;
                }
                Entry e = entryOf(out, name);
                e.uri = ContentUris.withAppendedId(base, c.getLong(0)).toString();
                if (e.size < 0) {
                    e.size = c.getLong(2);
                }
            }
        } catch (Exception ignored) {
            // 查询被系统拒绝时保持 File 通道的结果
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    private Entry entryOf(Map<String, Entry> out, String fileName) {
        Entry e = out.get(fileName);
        if (e == null) {
            e = new Entry();
            e.fileName = fileName;
            e.title = titleOf(fileName);
            out.put(fileName, e);
        }
        return e;
    }

    private static boolean isTxt(String name) {
        return name != null && name.toLowerCase(Locale.US).endsWith(".txt");
    }

    /** 去掉扩展名当书名，和阅读器里的主键保持一致。 */
    private static String titleOf(String fileName) {
        return fileName.substring(0, fileName.length() - 4);
    }

    private static String sizeText(long bytes) {
        if (bytes < 0) {
            return "大小未知";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    // ================= 打开与删除 =================

    private void open(Entry e) {
        Intent it = new Intent(this, ReaderActivity.class);
        it.putExtra("name", e.title);
        it.putExtra("path", e.path == null ? "" : e.path);
        it.putExtra("uri", e.uri == null ? "" : e.uri);
        startActivity(it);
    }

    private void confirmDelete(final Entry e) {
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("删除《" + e.title + "》")
                .setMessage("将从「下载/" + DIR_NAME + "」里永久删除 " + e.fileName
                        + "，删除后无法恢复。")
                .setPositiveButton("删除", (d, w) -> delete(e))
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(UiUtil.ERROR);
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(UiUtil.ON_SURFACE_VARIANT);
    }

    /** 删除也放后台：MediaStore 删除要跨进程，放主线程会卡顿。 */
    private void delete(final Entry e) {
        final boolean hasUri = e.uri != null && !e.uri.isEmpty();
        final boolean hasPath = e.path != null && !e.path.isEmpty();
        App.POOL.execute(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String error = null;
                if (hasUri) {
                    try {
                        ok = getContentResolver().delete(Uri.parse(e.uri), null, null) > 0;
                    } catch (android.app.RecoverableSecurityException rse) {
                        // Android 11+：这个文件是别的应用创建的，必须由用户点确认才能删
                        pendingDelete = e;
                        final android.content.IntentSender sender =
                                rse.getUserAction().getActionIntent().getIntentSender();
                        App.UI.post(new Runnable() {
                            @Override
                            public void run() {
                                requestDeletePermission(sender, e.title);
                            }
                        });
                        return;
                    } catch (Exception ex) {
                        error = "系统不允许删除：" + ex.getMessage();
                    }
                }
                if (!ok && hasPath) {
                    try {
                        ok = new File(e.path).delete();
                    } catch (Exception ex) {
                        error = "删除失败：" + ex.getMessage();
                    }
                }
                final boolean deleted = ok;
                final String reason = error;
                App.UI.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (deleted) {
                            Toast.makeText(LibraryActivity.this,
                                    "已删除《" + e.title + "》", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(LibraryActivity.this,
                                    reason == null ? "删除失败，文件可能已经不在了" : reason,
                                    Toast.LENGTH_LONG).show();
                        }
                        scan();
                    }
                });
            }
        });
    }

    /** 把系统的删除授权弹窗拉起来；用户同意后再删一次。 */
    private void requestDeletePermission(android.content.IntentSender sender, String title) {
        // RecoverableSecurityException 从 Android 10(Q) 就有了，卡在 R 会让
        // Android 10 上的用户永远删不掉非本应用创建的文件
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "系统不允许删除《" + title + "》", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startIntentSenderForResult(sender, REQ_DELETE, null, 0, 0, 0);
        } catch (Exception e) {
            Toast.makeText(this, "无法请求删除授权：" + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            pendingDelete = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_DELETE) {
            return;
        }
        final Entry e = pendingDelete;
        pendingDelete = null;
        if (e == null) {
            return;
        }
        if (resultCode == RESULT_OK) {
            // 授权通过后重试一次删除
            delete(e);
        } else {
            Toast.makeText(this, "已取消删除", Toast.LENGTH_SHORT).show();
        }
    }

    /** 进入 / 退出本页时的转场动画（与其它页面保持一致）。 */
    private void applyTransitions() {
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    public void finish() {
        super.finish();
        applyTransitions();
    }
}
