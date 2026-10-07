package com.niganma.noveldown;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * 整书下载流程编排。
 *
 * <p>职责边界：{@link DownloadManager} 负责任务表、状态与界面通知；
 * 本类只负责「把章节抓下来 → 失败自动重试 → 按顺序拼装 → 交给
 * {@link Exporter} 写文件」这条流水线。所有回调都经主线程投递。</p>
 *
 * <p>相比早期版本新增：</p>
 * <ul>
 *   <li><b>失败自动重试</b>：首轮失败的章节会再跑几轮，避免个别章节网络抖动
 *       导致整本书带缺口</li>
 *   <li><b>暂停 / 继续</b>：暂停后已抓到的章节保留在内存里，继续时只补缺失的
 *       章节，不必从头再来（相当于断点续传）</li>
 *   <li><b>速度统计</b>：用滑动窗口估算每秒章节数与剩余时间</li>
 * </ul>
 */
public final class Downloader {

    /** 下载线程数上限。 */
    public static final int MAX_THREADS = 64;
    /** 下载线程数默认值。 */
    public static final int DEFAULT_THREADS = 8;
    /** 失败章节的最大重试轮数（首轮之外）。 */
    public static final int MAX_RETRY_ROUNDS = 2;

    /** 把任意输入夹到 [1, MAX_THREADS]。 */
    public static int clampThreads(int n) {
        if (n < 1) {
            return 1;
        }
        return Math.min(n, MAX_THREADS);
    }

    /**
     * 下载全程回调。默认实现为空，调用方只覆写关心的回调即可。
     */
    public static abstract class Listener {
        /** 开始，total = 本次要下载的章节数。 */
        public void onStart(int total) {
        }

        /** 每完成一章调用一次；done 为已完成数，ok 表示这一章是否成功。 */
        public void onChapter(int done, int total, String title, boolean ok) {
        }

        /** 一轮下载结束（可能仍有失败章节），retryRound 从 0 计。 */
        public void onRoundFinished(int retryRound, int failedInRound) {
        }

        /** 正在重试失败的章节。 */
        public void onRetrying(int failedCount, int round) {
        }

        /** 全部完成并写入文件成功。 */
        public void onSuccess(String location, int okChapters, int failedChapters) {
        }

        /** 保存失败等致命错误。 */
        public void onError(String message) {
        }

        /** 用户暂停。 */
        public void onPaused() {
        }

        /** 用户取消。 */
        public void onCancelled() {
        }
    }

    /**
     * 一次下载任务的全部输入。
     */
    public static class Request {
        public BookSource source;
        public SearchBook book;
        /** 整本书的完整章节表（继续下载时也用同一份，靠 doneFlags 跳过已完成）。 */
        public List<Chapter> chapters = new ArrayList<>();
        /** 本次要下载的下标范围（闭区间），用于「指定章节范围」。 */
        public int fromIndex = 0;
        public int toIndex = -1;
        public int threads = DEFAULT_THREADS;
        public int conv = CharConv.NONE;
        public int format = Exporter.FORMAT_TXT;
        /** 文件名模板，见 {@link Exporter#buildFileName}。 */
        public String nameTemplate;
        /** 分章 TXT 时每个文件包含多少章。 */
        public int groupSize = 1;

        public int endIndex() {
            return toIndex < 0 ? chapters.size() - 1 : toIndex;
        }
    }

    /**
     * 每个任务自己的运行时状态，由 {@link DownloadManager} 持有。
     *
     * <p>把已完成的正文放在这里而不是局部变量里，是为了让「暂停 → 继续」
     * 能复用已经抓到的内容。</p>
     */
    public static final class Session {
        /** 章节正文，按整本书下标存放；null 表示尚未抓到。 */
        public final String[] parts;
        /** 该下标对应章节是失败的。 */
        public final boolean[] failed;

        public Session(int totalChapters) {
            parts = new String[totalChapters];
            failed = new boolean[totalChapters];
        }
    }

    public static final int PAUSE = 0;
    public static final int CANCEL = 1;

    /** 下载 / 暂停控制位：0 = 正常运行，其它见 PAUSE / CANCEL 常量。 */
    public static class Control {
        private volatile int mode = -1;

        public void pause() {
            mode = PAUSE;
        }

        public void cancel() {
            mode = CANCEL;
        }

        /** 供「继续下载」调用，清除暂停状态。 */
        public void resume() {
            mode = -1;
        }

        public boolean paused() {
            return mode == PAUSE;
        }

        public boolean cancelled() {
            return mode == CANCEL;
        }

        public boolean stopped() {
            return mode >= 0;
        }
    }

    private Downloader() {}

    /** 下载用线程池：不占用全局 POOL，避免整书下载把搜索堵住。 */
    private static volatile java.util.concurrent.ExecutorService exec;

    private static java.util.concurrent.ExecutorService exec() {
        if (exec == null) {
            synchronized (Downloader.class) {
                if (exec == null) {
                    exec = java.util.concurrent.Executors.newCachedThreadPool();
                }
            }
        }
        return exec;
    }

    /**
     * 在后台执行一次完整下载（含重试）。立即返回，回调在主线程。
     *
     * @param session 复用的会话状态；继续下载时传入同一个对象即可跳过已完成章节
     */
    public static void start(final Context ctx, final Request req, final Session session,
                            final Control control, final Listener cb) {
        exec().execute(new Runnable() {
            @Override
            public void run() {
                // 顶层兜底：这里一旦漏出异常/Error，就再没有任何线程去消费
                // Control，任务会永远停在「进行中」，暂停和取消都会失灵
                try {
                    executeDownload(ctx.getApplicationContext(), req, session, control, cb);
                } catch (Throwable t) {
                    final String msg = t.getMessage() == null ? t.toString() : t.getMessage();
                    post(cb, new Runnable() {
                        @Override
                        public void run() {
                            cb.onError("内部错误：" + msg);
                        }
                    });
                }
            }
        });
    }

    private static void post(final Listener cb, final Runnable r) {
        App.UI.post(r);
    }

    private static void executeDownload(final Context ctx, final Request req,
                                        final Session session, final Control control,
                                        final Listener cb) {
        final int from = Math.max(0, req.fromIndex);
        final int to = Math.min(req.chapters.size() - 1, req.endIndex());
        final int total = Math.max(0, to - from + 1);
        if (total == 0) {
            post(cb, new Runnable() {
                @Override
                public void run() {
                    cb.onError("没有可下载的章节");
                }
            });
            return;
        }

        post(cb, new Runnable() {
            @Override
            public void run() {
                cb.onStart(total);
            }
        });

        // ===== 第 0 轮：本次范围内所有尚未抓到的章节 =====
        List<Integer> todo = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            // 失败章节的 parts 里存的是占位文本而不是 null，只判 null 会让
            // 「继续」和「重试失败章节」永远跳过它们
            if (session.parts[i] == null || session.failed[i]) {
                todo.add(i);
            }
        }

        int round = 0;
        while (true) {
            final Speed speed = new Speed();
            List<Integer> failedNow = fetchBatch(ctx, req, session, control, cb, todo, total,
                    speed);
            if (control.stopped()) {
                postStop(cb, control);
                return;
            }
            if (failedNow.isEmpty()) {
                break;
            }
            final int failedThisRound = failedNow.size();
            final int finishedRound = round;
            post(cb, new Runnable() {
                @Override
                public void run() {
                    cb.onRoundFinished(finishedRound, failedThisRound);
                }
            });
            if (round >= MAX_RETRY_ROUNDS) {
                break;
            }
            round++;
            final int r = round;
            final int n = failedNow.size();
            post(cb, new Runnable() {
                @Override
                public void run() {
                    cb.onRetrying(n, r);
                }
            });
            // 每次重试前稍等，给站点限流一点缓冲
            sleep(600L * round);
            if (control.stopped()) {
                postStop(cb, control);
                return;
            }
            todo = failedNow;
        }

        // ===== 拼装并写文件 =====
        int ok = 0;
        int failedCount = 0;
        for (int i = from; i <= to; i++) {
            if (session.failed[i] || session.parts[i] == null) {
                failedCount++;
            } else {
                ok++;
            }
        }
        final int okFinal = ok;
        final int failedFinal = failedCount;

        String content = assembleText(ctx, req.book,
                req.source == null ? "" : req.source.name, session, from, to,
                req.chapters.size(), req.conv);
        try {
            String sourceName = req.source == null ? "" : req.source.name;
            String loc = Exporter.save(ctx, req.book, sourceName, req.format,
                    req.nameTemplate, content, req.groupSize);
            post(cb, new Runnable() {
                @Override
                public void run() {
                    cb.onSuccess(loc, okFinal, failedFinal);
                }
            });
        } catch (final Throwable e) {
            // Throwable 也要接：写文件时 OOM 很常见，漏出去就没有任何回调了
            final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            post(cb, new Runnable() {
                @Override
                public void run() {
                    cb.onError("保存失败：" + msg);
                }
            });
        }
    }

    private static void postStop(final Listener cb, final Control control) {
        post(cb, new Runnable() {
            @Override
            public void run() {
                if (control.cancelled()) {
                    cb.onCancelled();
                } else {
                    cb.onPaused();
                }
            }
        });
    }

    /**
     * 并发抓取一批章节。
     *
     * @return 仍然失败的下标列表
     */
    private static List<Integer> fetchBatch(final Context ctx, final Request req,
                                           final Session session, final Control control,
                                           final Listener cb, List<Integer> batch, final int total,
                                           final Speed speed) {
        final List<Integer> failedNow =
                java.util.Collections.synchronizedList(new ArrayList<Integer>());
        if (batch.isEmpty()) {
            return failedNow;
        }
        final java.util.concurrent.atomic.AtomicInteger done =
                new java.util.concurrent.atomic.AtomicInteger(0);
        // 已完成总数（含之前会话留下的），用于进度显示
        final int alreadyDone = countDone(session, req);
        java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(batch.size());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors
                .newFixedThreadPool(Math.min(clampThreads(req.threads), batch.size()));

        for (final Integer idxObj : batch) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        final int idx = idxObj;
                        final Chapter ch = req.chapters.get(idx);
                        boolean ok = false;
                        if (!control.stopped()) {
                            try {
                                String text = fetchContent(ctx, req.source, ch.url, req.book.url);
                                text = stripRepeatedTitle(ch.title, text);
                                session.parts[idx] = ch.title + "\n\n" + text + "\n\n\n";
                                session.failed[idx] = false;
                                ok = true;
                            } catch (Exception e) {
                                session.failed[idx] = true;
                                session.parts[idx] = ch.title + "\n\n[本章下载失败："
                                        + e.getMessage() + "]\n\n\n";
                            }
                        }
                        if (!ok) {
                            failedNow.add(idx);
                        }
                        final boolean okFinal = ok;
                        final int d = alreadyDone + done.incrementAndGet();
                        // 每章都回报，调用方据 ok 统计失败数并更新速度
                        post(cb, new Runnable() {
                            @Override
                            public void run() {
                                cb.onChapter(d, total, ch.title, okFinal);
                            }
                        });
                    } finally {
                        // 必须在 finally 里：少一次 countDown，整个任务就永远
                        // 卡在 await 上，暂停和取消都没人响应
                        latch.countDown();
                    }
                }
            });
        }
        pool.shutdown();
        // 分段等待，好让暂停 / 取消能及时打断；不再无限期死等
        while (true) {
            try {
                if (latch.await(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    break;
                }
            } catch (InterruptedException e) {
                break;
            }
            if (control.stopped()) {
                break;
            }
        }
        return failedNow;
    }

    /**
     * 去掉正文开头重复的章节标题行。
     *
     * <p>不少站点（如晋江式页面）的正文区块里自带一遍标题，于是拼装出来是
     * 「目录标题 + 正文自带标题 + 正文」。导出时按标题行切章就会把这一章切成
     * 「空章 + 正文章」两个，分章 TXT 直接多出一倍文件、其中一半是 0 字节。</p>
     *
     * <p>只在「正文自带标题的章号与本章章号一致」时才剥，避免误删正文里提到
     * 别的章节的句子（例如「第100章 决战」这种正经引用）。</p>
     */
    static String stripRepeatedTitle(String title, String text) {
        if (text == null || text.isEmpty() || title == null || title.isEmpty()) {
            return text;
        }
        String first = null;
        int skip = 0;
        for (String line : text.split("\n", -1)) {
            if (line.trim().isEmpty()) {
                skip += line.length() + 1;
                continue;
            }
            first = line.trim();
            break;
        }
        if (first == null) {
            return text;
        }
        if (!first.equals(title.trim()) && !isSameChapterNumber(title, first)) {
            return text;
        }
        // 连同紧随其后的空行一起丢掉，避免正文开头多出一片空白
        String rest = text.substring(Math.min(skip + first.length(), text.length()));
        while (rest.startsWith("\n") || rest.startsWith("\r")) {
            rest = rest.substring(1);
        }
        return rest;
    }

    /** 两个标题行是否指向同一章（比对章号，而不是整行文字）。 */
    private static boolean isSameChapterNumber(String a, String b) {
        String na = chapterNumber(a);
        return na != null && na.equals(chapterNumber(b));
    }

    /** 取标题里的章号；中文数字会折算成阿拉伯数字，取不到返回 null。 */
    private static String chapterNumber(String line) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^\\s*第\\s*([0-9零一二三四五六七八九十百千万两]{1,12})"
                        + "\\s*[章节回卷篇]")
                .matcher(line);
        if (!m.find()) {
            return null;
        }
        String raw = m.group(1);
        if (raw.matches("[0-9]+")) {
            return String.valueOf(Integer.parseInt(raw));
        }
        return String.valueOf(cnNum(raw));
    }

    /** 中文数字转阿拉伯数字（支持到万位，足够章节序号使用）。 */
    private static int cnNum(String s) {
        String digits = "零一二三四五六七八九";
        int total = 0;
        int section = 0;
        int number = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int d = digits.indexOf(c);
            if (d >= 0) {
                number = d;
                continue;
            }
            int unit;
            switch (c) {
                case '十': unit = 10; break;
                case '百': unit = 100; break;
                case '千': unit = 1000; break;
                case '万': unit = 10000; break;
                default: unit = 1; break;
            }
            if (unit == 10000) {
                section = (section + number) * unit;
                total += section;
                section = 0;
                number = 0;
                continue;
            }
            section += (number == 0 ? 1 : number) * unit;
            number = 0;
        }
        return total + section + number;
    }

    private static int countDone(Session session, Request req) {
        int n = 0;
        int to = Math.min(req.chapters.size() - 1, req.endIndex());
        for (int i = Math.max(0, req.fromIndex); i <= to; i++) {
            if (session.parts[i] != null && !session.failed[i]) {
                n++;
            }
        }
        return n;
    }

    /**
     * 按顺序拼装全书文本（含书头信息与结尾署名，最后做繁简转换）。
     *
     * <p>对外公开是为了「重新保存」：章节都已经下好、只是写文件失败时，
     * 界面可以拿同一份 {@link Session} 重新拼一遍再存，不必重下整本。</p>
     *
     * @param totalChapters 全书章节数，用于标出本次范围
     */
    public static String assembleText(Context ctx, SearchBook book, String sourceName,
                                      Session session, int from, int to, int totalChapters,
                                      int conv) {
        StringBuilder sb = new StringBuilder();
        String title = book == null || book.name == null ? "未命名" : book.name;
        sb.append('《').append(title).append('》');
        if (book != null && book.author != null && !book.author.isEmpty()) {
            sb.append("  作者：").append(book.author);
        }
        sb.append("\n来源书源：").append(sourceName == null ? "" : sourceName)
                .append("\n\n------------------------------------------------\n\n");
        for (int i = from; i <= to; i++) {
            if (i == from && totalChapters > 1) {
                // 指定范围下载时标出实际范围，避免误以为整本
                sb.append("（本书共 ").append(totalChapters)
                        .append(" 章，本文件包含第 ").append(from + 1)
                        .append(" - ").append(to + 1).append(" 章）\n\n");
            }
            sb.append(session.parts[i] == null ? "" : session.parts[i]);
        }
        sb.append("------------------------------------------------\n");
        sb.append("本文件由「小说下载器 ").append(App.CREDIT).append("」下载生成\n");
        return CharConv.convert(ctx, sb.toString(), conv);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // ================= 速度估算 =================

    /** 滑动窗口速度估算：每秒章节数 + 剩余时间。 */
    public static class Speed {
        private long windowStart = System.currentTimeMillis();
        private int windowCount;
        private double chaptersPerSec;
        private static final long WINDOW_MS = 4000;

        /** 每完成一章调用一次。 */
        public synchronized void tick() {
            windowCount++;
            long now = System.currentTimeMillis();
            long elapsed = now - windowStart;
            if (elapsed >= WINDOW_MS) {
                chaptersPerSec = windowCount * 1000.0 / elapsed;
                windowCount = 0;
                windowStart = now;
            }
        }

        /** 每秒章节数；样本不足时返回 0。 */
        public synchronized double rate() {
            return chaptersPerSec;
        }

        /** 剩余秒数；无法估算时返回 -1。 */
        public synchronized int etaSeconds(int remaining) {
            if (chaptersPerSec <= 0.05 || remaining <= 0) {
                return -1;
            }
            return (int) Math.ceil(remaining / chaptersPerSec);
        }

        /** 形如「2.3 章/秒」。 */
        public synchronized String rateText() {
            if (chaptersPerSec <= 0) {
                return "";
            }
            return String.format(java.util.Locale.US, "%.1f 章/秒", chaptersPerSec);
        }
    }

    // ================= 章节抓取 =================

    /** 加载目录。若书源未配置 chapterList，则把详情页本身视为单章。 */
    public static List<Chapter> loadChapters(BookSource s, String bookUrl) throws Exception {
        if (s.isIndexed()) {
            return loadIndexedChapters(s, bookUrl);
        }
        if (s.hasIdChapters()) {
            return loadIdChapters(s, bookUrl);
        }
        List<Chapter> list = new ArrayList<>();
        if (s.chapterList == null || s.chapterList.isEmpty()) {
            list.add(new Chapter("正文", bookUrl));
            return list;
        }
        // 目录可能在单独的目录页（如详情页只列最新章节）
        String tocUrl = (s.tocUrl == null || s.tocUrl.isEmpty())
                ? bookUrl : Rules.fillBook(s.tocUrl, bookUrl);
        String html = Http.get(tocUrl, s.charset, bookUrl, s.userAgent);
        appendChapters(list, html, tocUrl, s.chapterList);

        // 目录分页：一次拿到所有分页 URL，逐页合并
        if (s.tocPages != null && !s.tocPages.isEmpty()) {
            List<String[]> pages = Rules.findAll(html, s.tocPages, 1);
            for (String[] p : pages) {
                if (p[0] == null || p[0].isEmpty()) {
                    continue;
                }
                String pageUrl = Rules.absUrl(tocUrl, p[0]);
                if (pageUrl.equals(tocUrl)) {
                    continue;
                }
                try {
                    String pageHtml = Http.get(pageUrl, s.charset, tocUrl, s.userAgent);
                    appendChapters(list, pageHtml, pageUrl, s.chapterList);
                } catch (Exception ignored) {
                    // 单页失败不影响整体
                }
            }
        }
        return list;
    }

    /**
     * 索引式章节：目录由 JS 动态渲染、静态 HTML 拿不到章节链接的站点。
     * 思路：详情页拿到「最新章节 ID」→ 打开该章阅读页拿到整本目录（下标+标题）
     * → 由「最新章节 ID - 最大下标」反推出基准 ID，再按序号生成每章 URL。
     */
    private static List<Chapter> loadIndexedChapters(BookSource s, String bookUrl) throws Exception {
        BookSource.IndexChapters ix = s.indexChapters;
        String bookHtml = Http.get(bookUrl, s.charset, null, s.userAgent);
        String lastIdStr = Rules.first(bookHtml, ix.lastIdRule);
        if (lastIdStr == null || lastIdStr.trim().isEmpty()) {
            throw new Exception("详情页解析不到最新章节 ID（lastIdRule）");
        }
        long lastId;
        try {
            lastId = Long.parseLong(lastIdStr.trim());
        } catch (NumberFormatException e) {
            throw new Exception("最新章节 ID 非法：" + lastIdStr);
        }
        String article = Rules.first(bookUrl, ix.articleRule);
        if (article == null) {
            article = "";
        }
        String readerUrl = ix.urlTemplate
                .replace("{{article}}", article)
                .replace("{{id}}", lastIdStr.trim());
        String readerHtml = Http.get(readerUrl, s.charset, bookUrl, s.userAgent);

        List<String[]> items = Rules.findAll(readerHtml, ix.catalogRule, 2);
        if (items.isEmpty()) {
            throw new Exception("解析不到章节目录（catalogRule）");
        }
        java.util.TreeMap<Integer, String> titles = new java.util.TreeMap<>();
        int maxIdx = -1;
        for (String[] g : items) {
            if (g[0] == null) {
                continue;
            }
            int idx;
            try {
                idx = Integer.parseInt(g[0].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            String title = (g[1] == null || g[1].trim().isEmpty())
                    ? ("第" + (idx + 1) + "章") : g[1].trim();
            titles.put(idx, title);
            if (idx > maxIdx) {
                maxIdx = idx;
            }
        }
        if (maxIdx < 0) {
            throw new Exception("目录为空");
        }
        long base = lastId - maxIdx;
        List<Chapter> list = new ArrayList<>();
        for (int i = 0; i <= maxIdx; i++) {
            String title = titles.get(i);
            if (title == null) {
                title = "第" + (i + 1) + "章";
            }
            String url = ix.urlTemplate
                    .replace("{{article}}", article)
                    .replace("{{id}}", String.valueOf(base + i));
            list.add(new Chapter(title, url));
        }
        return list;
    }

    /**
     * 列表式目录：详情页内嵌了「章节ID + 标题」的列表（如番茄书页的 chapterListWithVolume），
     * 用 chapterIdRule 取全部，再按 chapterUrlTemplate 生成每章 URL。
     */
    private static List<Chapter> loadIdChapters(BookSource s, String bookUrl) throws Exception {
        String html = Http.get(bookUrl, s.charset, null, s.userAgent);
        List<String[]> items = Rules.findAll(html, s.chapterIdRule, 2);
        List<Chapter> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String[] g : items) {
            if (g[0] == null || g[0].trim().isEmpty()) {
                continue;
            }
            String id = g[0].trim();
            String url = s.chapterUrlTemplate.replace("{{id}}", id);
            if (!seen.add(url)) {
                continue;
            }
            String title = (g[1] == null || g[1].trim().isEmpty())
                    ? ("第" + (list.size() + 1) + "章") : g[1].trim();
            list.add(new Chapter(title, url));
        }
        if (list.isEmpty()) {
            throw new Exception("解析不到章节目录（chapterIdRule）");
        }
        return list;
    }

    /** 从一页目录 HTML 中抽取章节并按 URL 去重追加。 */
    private static void appendChapters(List<Chapter> list, String html, String pageUrl,
                                       String chapterList) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Chapter c : list) {
            seen.add(c.url);
        }
        List<String[]> items = Rules.findAll(html, chapterList, 2);
        // 同一页里同一 URL 重复出现时，保留「最后一次」：很多站点的目录页顶部
        // 先来一段倒序的「最新章节」，真正的正序目录在后面——只留第一次会把
        // 大结局排到最前面。
        java.util.Map<String, Integer> lastPos = new java.util.HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            String[] g = items.get(i);
            if (g[0] == null || g[0].isEmpty()) {
                continue;
            }
            lastPos.put(Rules.absUrl(pageUrl, g[0]), i);
        }
        for (int i = 0; i < items.size(); i++) {
            String[] g = items.get(i);
            if (g[0] == null || g[0].isEmpty()) {
                continue;
            }
            String url = Rules.absUrl(pageUrl, g[0]);
            Integer last = lastPos.get(url);
            if (last != null && last != i) {
                continue;
            }
            if (!seen.add(url)) {
                continue;
            }
            String title = (g[1] == null || g[1].isEmpty())
                    ? ("第" + (list.size() + 1) + "章") : g[1];
            list.add(new Chapter(title, url));
        }
    }

    /**
     * 抓取一章正文。若书源配置了 contentPages，则把本章内部的分页（下一页）
     * 逐页抓取并合并，避免长篇章节被截断；最终统一按 replace 规则清洗。
     */
    static String fetchContent(Context ctx, BookSource s, String startUrl,
                               String referer) throws Exception {
        StringBuilder raw = new StringBuilder();
        java.util.Set<String> seen = new java.util.HashSet<>();
        String cur = startUrl;
        for (int i = 0; i < 50; i++) {
            if (cur == null || !seen.add(cur)) {
                break;
            }
            String html = Http.get(cur, s.charset, referer, s.userAgent);
            String one = Rules.first(html, s.contentRule);
            if (one != null) {
                raw.append(one);
            }
            if (s.contentPages == null || s.contentPages.isEmpty()) {
                break;
            }
            String next = Rules.first(html, s.contentPages);
            if (next == null || next.isEmpty()) {
                break;
            }
            String abs = Rules.absUrl(cur, next);
            if (abs.equals(cur)) {
                break;
            }
            cur = abs;
        }
        String text = raw.toString();
        // 番茄等站点：正文藏在 JSON 字段里（可能还叠加字体 PUA 反爬）
        if (s.puaDecode || s.jsonEscape) {
            text = Rules.jsonUnescape(text);
        }
        text = Rules.clean(text, s.replace);
        if (s.puaDecode) {
            text = FanqieCodec.decode(ctx, text, s.puaMode);
        }
        text = Rules.tidy(text);
        if (s.paragraphIndent != null && !s.paragraphIndent.isEmpty()) {
            text = indentParagraphs(text, s.paragraphIndent);
        }
        return text;
    }

    /** 给每个非空段落前加上缩进前缀（如两个全角空格）。 */
    private static String indentParagraphs(String text, String indent) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder(text.length() + lines.length * indent.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            if (!lines[i].isEmpty()) {
                sb.append(indent);
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }
}
