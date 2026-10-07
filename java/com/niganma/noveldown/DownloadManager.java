package com.niganma.noveldown;

import android.content.Context;
import android.os.Build;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 后台下载管理器：任务表 + 状态机 + 界面通知。
 *
 * <p>下载本身由 {@link Downloader} 执行；本类负责把任务从界面里解耦出来，
 * 以及维护「暂停 / 继续 / 取消」这些生命周期动作。</p>
 *
 * <ul>
 *   <li>运行中的任务放在内存里，界面实时看到进度、速度与剩余时间</li>
 *   <li>暂停的任务保留已抓到的章节（{@link Downloader.Session}），
 *       继续时只补缺失的章节，不必从头再来</li>
 *   <li>完成后写入历史（SharedPreferences），重启应用仍在</li>
 *   <li>监听器只在主线程回调，界面可以放心直接改视图</li>
 * </ul>
 */
public final class DownloadManager {

    /** 任务状态。 */
    public static final int RUNNING = 0;
    public static final int PAUSED = 1;
    public static final int DONE = 2;
    public static final int FAILED = 3;
    public static final int CANCELLED = 4;

    private static final String PREF = "novel_down_history";
    private static final String KEY_JOBS = "jobs";
    private static final int MAX_HISTORY = 100;

    /** 同时在跑的整书下载数量上限。 */
    private static final int CONCURRENCY = 3;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(CONCURRENCY);

    /** 运行中 / 已暂停的任务，按 id 索引，保持插入顺序。 */
    private static final Map<String, Job> ACTIVE =
            Collections.synchronizedMap(new LinkedHashMap<String, Job>());

    /** 已完成 / 失败 / 取消的历史任务，最新的在前。 */
    private static final List<Job> HISTORY = new ArrayList<>();

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile boolean loaded = false;

    private DownloadManager() {}

    // ================= 数据模型 =================

    /** 一次整书下载任务。 */
    public static class Job {
        public String id;
        public String bookName = "";
        public String author = "";
        public String source = "";
        public String url = "";
        public int total;
        public int completed;
        public int failedChapters;
        public int status = RUNNING;
        public String location = "";
        public String message = "";
        public long startedAt;
        public int format = Exporter.FORMAT_TXT;
        public String nameTemplate;
        /** 繁简转换模式，继续下载时要沿用。 */
        public int conv = CharConv.NONE;
        /** 分章 TXT 时每个文件包含多少章。 */
        public int groupSize = 1;
        public int fromIndex;
        public int toIndex = -1;

        /** 每章的正文与失败标记；暂停后继续下载靠它复用已有内容。 */
        public Downloader.Session session;
        /** 暂停 / 取消控制位。 */
        public final Downloader.Control control = new Downloader.Control();
        /** 首轮之后仍然失败的章节下标，用于「重试失败章节」。 */
        public final List<Integer> failedIndices =
                Collections.synchronizedList(new ArrayList<Integer>());

        /** 速度统计。 */
        public final Downloader.Speed speed = new Downloader.Speed();

        public boolean isActive() {
            return status == RUNNING || status == PAUSED;
        }

        public boolean isRunning() {
            return status == RUNNING;
        }

        public boolean isPaused() {
            return status == PAUSED;
        }

        public int percent() {
            if (total <= 0) {
                return 0;
            }
            return (int) Math.min(100, completed * 100L / total);
        }

        /** 速度文本，形如「2.3 章/秒」，无法估算时为空串。 */
        public String speedText() {
            return status == RUNNING ? speed.rateText() : "";
        }

        /** 剩余时间文本，形如「约 1 分 20 秒」；无法估算时为空串。 */
        public String etaText() {
            if (status != RUNNING || total <= 0) {
                return "";
            }
            int remain = Math.max(0, total - completed);
            int sec = speed.etaSeconds(remain);
            if (sec < 0) {
                return "";
            }
            if (sec < 60) {
                return "约 " + sec + " 秒";
            }
            int min = sec / 60;
            int s = sec % 60;
            if (min < 60) {
                return "约 " + min + " 分 " + s + " 秒";
            }
            return "约 " + (min / 60) + " 小时 " + (min % 60) + " 分";
        }
    }

    /** 界面订阅下载变化。所有回调都在主线程。 */
    public interface Listener {
        void onJobStarted(Job job);

        void onJobProgress(Job job);

        void onJobFinished(Job job);
    }

    // ================= 对外接口 =================

    public static void addListener(Listener l) {
        if (l != null && !LISTENERS.contains(l)) {
            LISTENERS.add(l);
        }
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    /** 运行中或已暂停的任务快照。 */
    public static List<Job> active() {
        synchronized (ACTIVE) {
            return new ArrayList<>(ACTIVE.values());
        }
    }

    /** 兼容旧调用：仅返回正在运行的任务。 */
    public static List<Job> running() {
        List<Job> out = new ArrayList<>();
        for (Job j : active()) {
            if (j.isRunning()) {
                out.add(j);
            }
        }
        return out;
    }

    /** 历史任务快照（新 → 旧）。 */
    public static List<Job> history() {
        ensureLoaded();
        synchronized (HISTORY) {
            return new ArrayList<>(HISTORY);
        }
    }

    public static boolean hasRunning() {
        for (Job j : active()) {
            if (j.isRunning()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 提交一次整书下载（立即返回，后台执行）。
     *
     * @param from 起始章节下标（含）
     * @param to   结束章节下标（含）；-1 表示到末尾
     */
    public static Job start(final Context ctx, final BookSource source, final SearchBook book,
                            final List<Chapter> chapters, final int threads, final int conv,
                            final int format, final String nameTemplate, int from, int to) {
        return start(ctx, source, book, chapters, threads, conv, format, nameTemplate,
                from, to, null);
    }

    /**
     * 同上，但可以传入一份已有的会话状态。
     *
     * <p>「重试失败章节」靠这个复用上次成功章节的正文：只要把失败下标清空，
     * 下载器就只会去补这些缺口，相当于断点续传。</p>
     */
    public static Job start(final Context ctx, final BookSource source, final SearchBook book,
                            final List<Chapter> chapters, final int threads, final int conv,
                            final int format, final String nameTemplate, int from, int to,
                            Downloader.Session reuseSession) {
        return start(ctx, source, book, chapters, threads, conv, format, nameTemplate,
                from, to, reuseSession, 1);
    }

    /** 完整签名：可指定分章 TXT 的每文件章数。 */
    public static Job start(final Context ctx, final BookSource source, final SearchBook book,
                            final List<Chapter> chapters, final int threads, final int conv,
                            final int format, final String nameTemplate, int from, int to,
                            Downloader.Session reuseSession, int groupSize) {
        ensureLoaded();
        final Job job = new Job();
        job.id = "d" + System.currentTimeMillis() + "-"
                + Integer.toHexString((int) (Math.random() * 0xFFFF));
        job.bookName = book == null || book.name == null ? "未命名" : book.name;
        job.author = book == null || book.author == null ? "" : book.author;
        job.source = source == null || source.name == null ? "" : source.name;
        job.url = book == null || book.url == null ? "" : book.url;
        job.format = format;
        job.nameTemplate = nameTemplate;
        job.conv = conv;
        job.groupSize = groupSize < 1 ? 1 : groupSize;
        job.fromIndex = from;
        job.toIndex = to;
        job.total = chapters == null ? 0 : Math.max(0,
                Math.min(chapters.size() - 1, to < 0 ? chapters.size() - 1 : to) - from + 1);
        job.startedAt = System.currentTimeMillis();
        job.status = RUNNING;
        job.session = reuseSession != null
                ? reuseSession
                : new Downloader.Session(chapters == null ? 0 : chapters.size());

        ACTIVE.put(job.id, job);
        for (Listener l : LISTENERS) {
            l.onJobStarted(job);
        }

        if (chapters == null || chapters.isEmpty()) {
            job.status = FAILED;
            job.message = "没有可下载的章节";
            finish(job);
            return job;
        }
        runJob(ctx, job, source, book, chapters, threads);
        return job;
    }

    /** 兼容旧签名（整本、单文件 TXT）。 */
    public static Job start(Context ctx, BookSource source, SearchBook book,
                            List<Chapter> chapters, int threads, int conv) {
        return start(ctx, source, book, chapters, threads, conv, Exporter.FORMAT_TXT,
                Exporter.DEFAULT_TEMPLATE, 0, -1);
    }

    /** 继续一个已暂停的任务：只补尚未抓到的章节。 */
    public static void resume(final Context ctx, final Job job, final BookSource source,
                              final List<Chapter> chapters, final int threads) {
        if (job == null || !job.isPaused()) {
            return;
        }
        job.control.resume();
        job.status = RUNNING;
        for (Listener l : LISTENERS) {
            l.onJobProgress(job);
        }
        runJob(ctx, job, source,
                new SearchBook(job.source, job.bookName, job.author, job.url), chapters, threads);
    }

    /** 组装并启动一次下载（首次与「继续」共用）。 */
    private static void runJob(final Context ctx, final Job job, final BookSource source,
                               final SearchBook book, final List<Chapter> chapters,
                               final int threads) {
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                Downloader.Request req = new Downloader.Request();
                req.source = source;
                req.book = book;
                req.chapters = chapters;
                req.fromIndex = job.fromIndex;
                req.toIndex = job.toIndex;
                req.threads = threads;
                req.conv = job.conv;
                req.format = job.format;
                req.nameTemplate = job.nameTemplate;
                req.groupSize = job.groupSize;

                job.failedIndices.clear();
                job.failedChapters = 0;

                Downloader.start(ctx.getApplicationContext(), req, job.session, job.control,
                        new Downloader.Listener() {
                            @Override
                            public void onStart(int total) {
                                job.total = total;
                                notifyProgress(job);
                            }

                            @Override
                            public void onChapter(int done, int total, String title, boolean ok) {
                                job.completed = done;
                                job.total = total;
                                if (ok) {
                                    job.speed.tick();
                                } else {
                                    job.failedChapters++;
                                }
                                notifyProgress(job);
                            }

                            @Override
                            public void onRetrying(int failedCount, int round) {
                                job.message = "正在重试 " + failedCount + " 个失败章节（第 "
                                        + round + " 轮）";
                                notifyProgress(job);
                            }

                            @Override
                            public void onSuccess(String location, int okChapters, int failed) {
                                job.completed = job.total;
                                job.location = location;
                                job.failedChapters = failed;
                                job.status = DONE;
                                job.message = failed == 0
                                        ? ("成功 " + okChapters + " 章")
                                        : ("成功 " + okChapters + " 章，失败 " + failed + " 章");
                                // 记录真实失败下标，界面据此提供「重试失败章节」
                                syncFailed(job);
                                finish(job);
                            }

                            @Override
                            public void onError(String message) {
                                job.status = FAILED;
                                job.message = message;
                                // 失败也可能是「写文件」而非「抓章节」。这里照样把
                                // 真实失败下标对一遍：以前的实现只在成功路径里记，
                                // 于是失败卡片会显示失败章数 > 0，点「重试失败章节」
                                // 却提示「没有失败章节」。
                                syncFailed(job);
                                finish(job);
                            }

                            @Override
                            public void onPaused() {
                                job.status = PAUSED;
                                job.message = "已暂停，继续时只补缺失章节";
                                for (Listener l : LISTENERS) {
                                    l.onJobProgress(job);
                                }
                            }

                            @Override
                            public void onCancelled() {
                                job.status = CANCELLED;
                                job.message = "已取消";
                                finish(job);
                            }
                        });
            }
        });
    }

    private static void notifyProgress(Job job) {
        for (Listener l : LISTENERS) {
            l.onJobProgress(job);
        }
    }

    /** 任务收尾：移出活动表、写入历史、通知界面。 */
    private static void finish(final Job job) {
        ACTIVE.remove(job.id);
        synchronized (HISTORY) {
            HISTORY.add(0, job);
            while (HISTORY.size() > MAX_HISTORY) {
                HISTORY.remove(HISTORY.size() - 1);
            }
        }
        for (Listener l : LISTENERS) {
            l.onJobFinished(job);
        }
        persistAsync();
    }

    /** 请求暂停：已抓到的章节保留，继续时不必重下。 */
    public static void pause(Job job) {
        if (job == null || !job.isRunning()) {
            return;
        }
        job.control.pause();
        job.message = "正在暂停…";
        notifyProgress(job);
    }

    /** 取消并丢弃：任务进入历史，已抓内容不保留。 */
    public static void cancel(Job job) {
        if (job == null || !job.isActive()) {
            return;
        }
        if (job.isPaused()) {
            // 暂停中的任务没有任何线程在跑，也就没人去读取消标志：直接收尾，
            // 否则状态会永远停在「正在停止…」，卡片留在活动列表里出不去
            job.control.cancel();
            job.status = CANCELLED;
            job.message = "已取消";
            finish(job);
            return;
        }
        job.control.cancel();
        job.message = "正在停止…";
        notifyProgress(job);
    }

    /**
     * 按会话内容刷新失败下标与失败章数。
     *
     * <p>{@code onChapter} 只累加计数，真正的失败下标要等收尾时和 session 对一遍；
     * 以前只在成功路径里做，于是「写文件失败」的任务会显示失败章数 &gt; 0，
     * 点「重试失败章节」却提示「没有失败章节」。</p>
     */
    private static void syncFailed(Job job) {
        if (job.session == null) {
            // 重启后的历史任务没有会话数据，重试无从谈起，也就别显示入口了
            job.failedChapters = 0;
            return;
        }
        Downloader.Session s = job.session;
        int from = Math.max(0, job.fromIndex);
        int to = job.toIndex < 0 ? s.parts.length - 1
                : Math.min(s.parts.length - 1, job.toIndex);
        job.failedIndices.clear();
        for (int i = from; i <= to; i++) {
            if (s.failed[i] || s.parts[i] == null) {
                job.failedIndices.add(i);
            }
        }
        job.failedChapters = job.failedIndices.size();
    }

    /** 本次范围内的章节是否都已抓到——决定能不能「只重新保存」。 */
    public static boolean canResave(Job job) {
        if (job == null || job.status == DONE || job.session == null) {
            return false;
        }
        Downloader.Session s = job.session;
        int from = Math.max(0, job.fromIndex);
        int to = job.toIndex < 0 ? s.parts.length - 1
                : Math.min(s.parts.length - 1, job.toIndex);
        if (to < from) {
            return false;
        }
        for (int i = from; i <= to; i++) {
            if (s.parts[i] == null || s.failed[i]) {
                return false;
            }
        }
        return true;
    }

    /** {@link #resave} 的结果回调，都在主线程。 */
    public interface SaveCallback {
        void onSaved(Job job, String location);

        void onFailed(Job job, String message);
    }

    /**
     * 只重新拼装并保存，不重新下载。
     *
     * <p>用于「章节都下好了，写文件那一步失败」的补救（文件重名、空间不足、
     * 权限被回收……）。这种情况以前只能整本重下，几千章要几十分钟。</p>
     */
    public static void resave(final Context ctx, final Job job, final SaveCallback cb) {
        final Context c = ctx != null ? ctx : App.appContext();
        if (c == null || job == null || job.session == null) {
            if (cb != null) {
                cb.onFailed(job, "没有可用的会话数据，请重新下载");
            }
            return;
        }
        final Downloader.Session session = job.session;
        final int from = Math.max(0, job.fromIndex);
        final int to = job.toIndex < 0 ? session.parts.length - 1
                : Math.min(session.parts.length - 1, job.toIndex);
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                String loc = null;
                try {
                    SearchBook book = new SearchBook(job.source, job.bookName, job.author, job.url);
                    String text = Downloader.assembleText(c, book, job.source, session,
                            from, to, session.parts.length, job.conv);
                    loc = Exporter.save(c, book, job.source, job.format, job.nameTemplate,
                            text, job.groupSize);
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.toString() : t.getMessage();
                }
                final String floc = loc;
                final String ferr = err;
                App.UI.post(new Runnable() {
                    @Override
                    public void run() {
                        if (ferr != null) {
                            if (cb != null) {
                                cb.onFailed(job, ferr);
                            }
                            return;
                        }
                        job.status = DONE;
                        job.location = floc;
                        job.message = "已重新保存";
                        syncFailed(job);
                        persistAsync();
                        if (cb != null) {
                            cb.onSaved(job, floc);
                        }
                    }
                });
            }
        });
    }

    /** 失败章节的下标快照。 */
    public static List<Integer> failedIndices(Job job) {
        if (job == null) {
            return new ArrayList<>();
        }
        synchronized (job.failedIndices) {
            return new ArrayList<>(job.failedIndices);
        }
    }

    /** 从历史里移除一条记录（不删除已下载的文件）。 */
    public static void removeHistory(final String id) {
        synchronized (HISTORY) {
            for (int i = HISTORY.size() - 1; i >= 0; i--) {
                if (HISTORY.get(i).id.equals(id)) {
                    HISTORY.remove(i);
                }
            }
        }
        persistAsync();
    }

    /** 清空历史记录（不动文件）。 */
    public static void clearHistory() {
        synchronized (HISTORY) {
            HISTORY.clear();
        }
        persistAsync();
    }

    // ================= 持久化 =================

    private static SharedPreferences prefs(Context c) {
        Context use = c == null ? null : c.getApplicationContext();
        if (use == null) {
            use = c != null ? c : App.appContext();
        }
        return use == null ? null : use.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static void ensureLoaded() {
        ensureLoaded(App.appContext());
    }

    /**
     * 加载历史（只成功一次）。
     *
     * <p>拿不到 Context 时必须直接返回、<b>不能置位 loaded</b>：Application 在
     * {@code attachBaseContext} 阶段还取不到 applicationContext，若这时把 loaded
     * 置成 true，历史就永远不会加载，而之后任何一次 persist 又会把空列表写回去，
     * 等于把用户的历史记录清空。</p>
     */
    private static void ensureLoaded(Context c) {
        if (loaded) {
            return;
        }
        Context use = c != null ? c : App.appContext();
        if (use == null) {
            return;
        }
        synchronized (HISTORY) {
            if (loaded) {
                return;
            }
            loaded = true;
            HISTORY.clear();
            HISTORY.addAll(loadArray(use));
        }
    }

    /** 需要在有 Context 时提前加载历史。 */
    public static void init(Context c) {
        ensureLoaded(c);
    }

    private static List<Job> loadArray(Context c) {
        List<Job> out = new ArrayList<>();
        try {
            String s = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getString(KEY_JOBS, null);
            if (s == null) {
                return out;
            }
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Job j = new Job();
                j.id = o.optString("id");
                j.bookName = o.optString("bookName");
                j.author = o.optString("author");
                j.source = o.optString("source");
                j.url = o.optString("url");
                j.total = o.optInt("total");
                j.completed = o.optInt("completed");
                j.failedChapters = o.optInt("failed");
                j.status = o.optInt("status", DONE);
                j.location = o.optString("location");
                j.message = o.optString("message");
                j.startedAt = o.optLong("startedAt");
                j.format = o.optInt("format", Exporter.FORMAT_TXT);
                j.conv = o.optInt("conv", CharConv.NONE);
                j.groupSize = o.optInt("group", 1);
                j.nameTemplate = o.optString("template", Exporter.DEFAULT_TEMPLATE);
                j.fromIndex = o.optInt("from");
                j.toIndex = o.optInt("to", -1);
                JSONArray fi = o.optJSONArray("failedIdx");
                if (fi != null) {
                    for (int k = 0; k < fi.length(); k++) {
                        j.failedIndices.add(fi.optInt(k));
                    }
                }
                // 上次进程结束时还在跑或暂停的，标记为中断
                if (j.isActive()) {
                    j.status = FAILED;
                    j.message = "下载被中断";
                }
                out.add(j);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void persistAsync() {
        final Context c = App.appContext();
        if (c == null) {
            return;
        }
        final String json = toJson();
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    prefs(c).edit().putString(KEY_JOBS, json).apply();
                } catch (Exception ignored) {
                }
            }
        });
    }

    private static String toJson() {
        JSONArray arr = new JSONArray();
        synchronized (HISTORY) {
            for (Job j : HISTORY) {
                JSONObject o = new JSONObject();
                try {
                    o.put("id", j.id);
                    o.put("bookName", j.bookName);
                    o.put("author", j.author);
                    o.put("source", j.source);
                    o.put("url", j.url);
                    o.put("total", j.total);
                    o.put("completed", j.completed);
                    o.put("failed", j.failedChapters);
                    o.put("status", j.status);
                    o.put("location", j.location);
                    o.put("message", j.message);
                    o.put("startedAt", j.startedAt);
                    o.put("format", j.format);
                    o.put("conv", j.conv);
                    o.put("group", j.groupSize);
                    o.put("template", j.nameTemplate);
                    o.put("from", j.fromIndex);
                    o.put("to", j.toIndex);
                    JSONArray fi = new JSONArray();
                    synchronized (j.failedIndices) {
                        for (Integer idx : j.failedIndices) {
                            fi.put(idx);
                        }
                    }
                    o.put("failedIdx", fi);
                } catch (Exception ignored) {
                }
                arr.put(o);
            }
        }
        return arr.toString();
    }

    // ================= 文件定位 =================

    /** 导出目录（下载/小说下载器）。 */
    public static File exportDir() {
        return new File(android.os.Environment
                .getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS), Exporter.DIR_NAME);
    }

    /**
     * 在导出目录里找这本书的文件，用于分享 / 打开。
     * 会按模板生成的名字与实际目录内容各试一次。
     */
    public static File findExportedFile(Job job) {
        try {
            if (job == null) {
                return null;
            }
            if (job.location != null && job.location.startsWith("/")) {
                File direct = new File(job.location);
                if (direct.exists()) {
                    return direct;
                }
            }
            // 保存时回读的真实路径（形如「下载/小说下载器/书名.epub」，分章导出为
            // 「下载/小说下载器/目录/（N 个文件…）」）——直接用它定位，比按模板 +
            // 章数重新拼名字可靠：媒体库改名、章数口径不一致都不会再指错文件
            if (job.location != null && job.location.startsWith("下载/")) {
                String rest = job.location.substring("下载/".length());
                int cut = rest.indexOf("（");
                if (cut > 0) {
                    rest = rest.substring(0, cut);
                }
                while (rest.endsWith("/")) {
                    rest = rest.substring(0, rest.length() - 1);
                }
                File f = new File(android.os.Environment
                        .getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_DOWNLOADS), rest);
                if (f.exists()) {
                    return f;
                }
            }
            File dir = exportDir();
            String base = Exporter.buildFileName(job.nameTemplate,
                    new SearchBook(job.source, job.bookName, job.author, job.url),
                    job.source, job.total, job.format, job.format == Exporter.FORMAT_SPLIT);
            String ext = Exporter.extension(job.format);
            File f = new File(dir, base + ext);
            if (f.exists()) {
                return f;
            }
            // 回退：按书名匹配
            if (dir.isDirectory()) {
                File[] all = dir.listFiles();
                if (all != null) {
                    for (File c : all) {
                        if (c.getName().startsWith(job.bookName)) {
                            return c;
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 找到导出文件对应的 content Uri，用于分享 / 交给其它应用打开。
     *
     * <p>不能把 {@code file://} 交给别的应用——Android 7 起会直接抛
     * {@link android.os.FileUriExposedException}，所以必须走 MediaStore 的
     * content Uri（10+ 上本应用写入 Downloads 的文件都能查到）。</p>
     *
     * @return 查不到时返回 null
     */
    public static android.net.Uri findShareableUri(Context ctx, Job job) {
        if (ctx == null || job == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }
        try {
            File f = findExportedFile(job);
            if (f == null || !f.exists() || f.isDirectory()) {
                return null;
            }
            android.net.Uri base = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            String[] projection = {android.provider.MediaStore.MediaColumns._ID};
            String selection = android.provider.MediaStore.MediaColumns.DISPLAY_NAME + " = ?"
                    + " AND " + android.provider.MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
            String rel = f.getParentFile() == null ? "" : f.getParentFile().getName();
            String[] args = {f.getName(), "%" + rel + "%"};
            android.database.Cursor c = ctx.getContentResolver()
                    .query(base, projection, selection, args, null);
            if (c == null) {
                return null;
            }
            try {
                if (c.moveToFirst()) {
                    return android.content.ContentUris.withAppendedId(base, c.getLong(0));
                }
            } finally {
                c.close();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 兼容旧调用。 */
    public static File findExportedFile(String bookName) {
        try {
            File f = new File(exportDir(), Exporter.sanitize(bookName) + ".txt");
            return f.exists() ? f : null;
        } catch (Exception e) {
            return null;
        }
    }
}
