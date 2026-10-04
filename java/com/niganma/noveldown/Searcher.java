package com.niganma.noveldown;

import android.os.Handler;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/** 并发在多个书源里搜索关键字。 */
public final class Searcher {

    public interface Callback {
        void onBook(SearchBook book);

        /** 某个书源搜索结束；count 为该源命中的书籍数（失败时为 -1）。 */
        void onSourceFinished(String source, int count);

        void onFinished(int total);
    }

    private Searcher() {}

    public static void search(final List<BookSource> sources, final String key, final int page,
                              ExecutorService pool, final Handler ui, final Callback cb) {
        if (sources.isEmpty()) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    cb.onFinished(0);
                }
            });
            return;
        }
        final AtomicInteger finished = new AtomicInteger(0);
        final AtomicInteger total = new AtomicInteger(0);
        final int n = sources.size();
        for (final BookSource s : sources) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    final List<SearchBook> found = new java.util.ArrayList<>();
                    boolean err = false;
                    try {
                        String url = Rules.fill(s.searchUrl, key, page);
                        String html = Http.get(url, s.charset, null, s.userAgent);
                        List<String[]> items = Rules.findAll(html, s.listRule, 3);
                        for (String[] g : items) {
                            if (g[0] == null || g[1] == null) {
                                continue;
                            }
                            String bookUrl = s.bookUrlTemplate.isEmpty()
                                    ? Rules.absUrl(url, g[0])
                                    : s.bookUrlTemplate.replace("{{id}}", g[0].trim());
                            found.add(new SearchBook(s.name, g[1],
                                    g.length > 2 ? g[2] : null, bookUrl));
                        }
                    } catch (Exception e) {
                        err = true;
                    }
                    final boolean failed = err;
                    for (final SearchBook b : found) {
                        total.incrementAndGet();
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                cb.onBook(b);
                            }
                        });
                    }
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onSourceFinished(s.name, failed ? -1 : found.size());
                        }
                    });
                    if (finished.incrementAndGet() == n) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                cb.onFinished(total.get());
                            }
                        });
                    }
                }
            });
        }
    }
}