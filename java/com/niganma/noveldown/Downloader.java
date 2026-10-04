package com.niganma.noveldown;

import android.content.Context;
import android.os.Handler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** 章节加载 + 全书下载（导出为明文 txt）。 */
public final class Downloader {

    /** 下载线程数上限。 */
    public static final int MAX_THREADS = 64;
    /** 下载线程数默认值。 */
    public static final int DEFAULT_THREADS = 8;

    /** 把任意输入夹到 [1, MAX_THREADS]。 */
    public static int clampThreads(int n) {
        if (n < 1) {
            return 1;
        }
        return Math.min(n, MAX_THREADS);
    }

    public interface Progress {
        void onStart(int totalChapters);

        void onChapter(int index, int total, String title);

        void onSuccess(String location, int words);

        void onError(String message);
    }

    private Downloader() {}

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
        for (String[] g : items) {
            if (g[0] == null || g[0].isEmpty()) {
                continue;
            }
            String url = Rules.absUrl(pageUrl, g[0]);
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
    private static String fetchContent(Context ctx, BookSource s, String startUrl,
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

    /** 后台下载整本书并保存为明文 txt；chapters 用 threads 个线程并发下载。回调均在主线程。 */
    public static void download(final Context ctx, final BookSource s, final SearchBook book,
                               final List<Chapter> chapters, final int threads, final int conv,
                               ExecutorService pool, final Handler ui, final Progress cb) {
        pool.execute(new Runnable() {
            @Override
            public void run() {
                final int total = chapters.size();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onStart(total);
                    }
                });
                // 每章的结果先落到各自的下标位置，最后再按顺序拼装，保证章节顺序不乱
                final String[] parts = new String[total];
                final boolean[] failed = new boolean[total];
                final AtomicInteger done = new AtomicInteger(0);
                final CountDownLatch latch = new CountDownLatch(total);
                final int nThreads = clampThreads(threads);
                ExecutorService exec = Executors.newFixedThreadPool(nThreads);
                for (int i = 0; i < total; i++) {
                    final int idx = i;
                    exec.execute(new Runnable() {
                        @Override
                        public void run() {
                            final Chapter ch = chapters.get(idx);
                            try {
                                String text = fetchContent(ctx, s, ch.url, book.url);
                                parts[idx] = ch.title + "\n\n" + text + "\n\n\n";
                            } catch (Exception e) {
                                failed[idx] = true;
                                parts[idx] = ch.title + "\n\n[本章下载失败：" + e.getMessage() + "]\n\n\n";
                            } finally {
                                final int d = done.incrementAndGet();
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        cb.onChapter(d, total, ch.title);
                                    }
                                });
                                latch.countDown();
                            }
                        }
                    });
                }
                exec.shutdown();
                try {
                    latch.await();
                } catch (InterruptedException ignored) {
                }

                StringBuilder sb = new StringBuilder();
                sb.append('《').append(book.name).append('》');
                if (book.author != null && !book.author.isEmpty()) {
                    sb.append("  作者：").append(book.author);
                }
                sb.append("\n来源书源：").append(s.name)
                        .append("\n\n------------------------------------------------\n\n");
                int ok = 0;
                for (int i = 0; i < total; i++) {
                    sb.append(parts[i] == null ? "" : parts[i]);
                    if (!failed[i]) {
                        ok++;
                    }
                }
                sb.append("------------------------------------------------\n");
                sb.append("本文件由「小说下载器 by泥甘麻 qq2211927635」下载生成\n");
                final String content = CharConv.convert(ctx, sb.toString(), conv);
                final int okFinal = ok;
                try {
                    String loc = FileExport.save(ctx, FileExport.fileName(book.name), content);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onSuccess(loc, okFinal);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onError("保存失败：" + e.getMessage());
                        }
                    });
                }
            }
        });
    }
}