package com.niganma.noveldown;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * 导出：把拼装好的全书文本写成文件。
 *
 * <p>支持三种形态：</p>
 * <ul>
 *   <li>{@link #FORMAT_TXT} 单文件 TXT（原有行为）</li>
 *   <li>{@link #FORMAT_SPLIT} 分章 TXT：每章一个文件，放进以书名命名的子目录</li>
 *   <li>{@link #FORMAT_EPUB} EPUB 电子书：带目录与元数据，多数阅读器可识别</li>
 * </ul>
 *
 * <p>EPUB 是自己按 EPUB 3 规范拼 ZIP 的，没有引入任何第三方库。注意其中的
 * {@code mimetype} 条目必须是第一个、且不压缩——{@code ZipOutputStream} 无法
 * 保证这一点（它总给 STORED 条目写数据描述符），所以这里直接手写 ZIP 结构。</p>
 */
public final class Exporter {

    public static final int FORMAT_TXT = 0;
    public static final int FORMAT_SPLIT = 1;
    public static final int FORMAT_EPUB = 2;

    /** 导出根目录（相对外部存储 Download）。 */
    public static final String DIR_NAME = "小说下载器";

    /** 默认文件名模板。 */
    public static final String DEFAULT_TEMPLATE = "{title}";

    private Exporter() {}

    // ================= 格式元信息 =================

    public static String formatName(int format) {
        if (format == FORMAT_SPLIT) {
            return "分章 TXT";
        }
        if (format == FORMAT_EPUB) {
            return "EPUB";
        }
        return "单文件 TXT";
    }

    public static String[] formatNames() {
        return new String[]{formatName(FORMAT_TXT), formatName(FORMAT_SPLIT),
                formatName(FORMAT_EPUB)};
    }

    public static String extension(int format) {
        return format == FORMAT_EPUB ? ".epub" : ".txt";
    }

    // ================= 文件名 =================

    /** 模板占位符说明，用于设置页提示。 */
    public static final String TEMPLATE_HINT =
            "可用占位符：{title} 书名、{author} 作者、{source} 书源、{count} 章节数、"
                    + "{date} 日期、{format} 格式";

    /**
     * 按模板生成文件名（不含扩展名）。
     *
     * @param template 为空时使用 {@link #DEFAULT_TEMPLATE}
     */
    public static String buildFileName(String template, SearchBook book, String sourceName,
                                       int chapterCount, int format, boolean withDate) {
        String t = (template == null || template.trim().isEmpty())
                ? DEFAULT_TEMPLATE : template;
        String title = book == null || book.name == null || book.name.isEmpty()
                ? "未命名" : book.name;
        String author = book == null || book.author == null ? "" : book.author;
        String date = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());

        String out = t
                .replace("{title}", title)
                .replace("{author}", author)
                .replace("{source}", sourceName == null ? "" : sourceName)
                .replace("{count}", String.valueOf(chapterCount))
                .replace("{date}", date)
                .replace("{format}", formatName(format));
        if (withDate) {
            // 分章目录需要一个唯一名字，避免与上次导出混在一起
            out = out + "_" + date;
        }
        return clipBytes(sanitize(out), MAX_NAME_BYTES);
    }

    /**
     * 文件名安全长度：Linux 单个文件名上限 255 字节，UTF-8 下中文一个字 3 字节。
     *
     * <p>模板里塞了书名 + 作者 + 书源 + 日期的时候很容易超，超了 insert / 建文件
     * 会直接失败——而那时章节已经全下完了，用户只能整本重下，代价极高。</p>
     */
    private static final int MAX_NAME_BYTES = 180;

    /** 按 UTF-8 字节数截断，避免超长文件名被系统拒绝。 */
    static String clipBytes(String name, int maxBytes) {
        if (name == null) {
            return "";
        }
        try {
            if (name.getBytes("utf-8").length <= maxBytes) {
                return name;
            }
            String s = name;
            while (s.length() > 1 && s.getBytes("utf-8").length > maxBytes) {
                s = s.substring(0, s.length() - 1);
            }
            return s;
        } catch (UnsupportedEncodingException e) {
            return name.length() > 60 ? name.substring(0, 60) : name;
        }
    }

    /** 去掉文件名里不允许的字符，并避免空名。 */
    public static String sanitize(String name) {
        String n = name == null ? "" : name;
        n = n.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        while (n.endsWith(".") || n.endsWith(" ")) {
            n = n.substring(0, n.length() - 1);
        }
        if (n.isEmpty()) {
            n = "novel";
        }
        return n;
    }

    // ================= 保存入口 =================

    /**
     * 保存导出内容。
     *
     * @param assembled 已拼装（并完成繁简转换）的全书文本
     * @return 保存位置的可读描述
     */
    public static String save(Context ctx, SearchBook book, String sourceName, int format,
                              String template, String assembled) throws IOException {
        return save(ctx, book, sourceName, format, template, assembled, 1);
    }

    /**
     * 保存导出内容。
     *
     * @param groupSize 分章 TXT 时每个文件包含多少章（1 表示每章一个文件）
     */
    public static String save(Context ctx, SearchBook book, String sourceName, int format,
                              String template, String assembled, int groupSize)
            throws IOException {
        String baseName = buildFileName(template, book, sourceName,
                countChapters(assembled), format, format == FORMAT_SPLIT);
        if (format == FORMAT_EPUB) {
            byte[] data = buildEpub(book, sourceName, assembled);
            return writeFile(ctx, baseName + ".epub", "application/epub+zip", data);
        }
        if (format == FORMAT_SPLIT) {
            return saveSplit(ctx, baseName, assembled, Math.max(1, groupSize));
        }
        return writeFile(ctx, baseName + ".txt", "text/plain",
                assembled.getBytes("utf-8"));
    }

    /**
     * 分章导出。
     *
     * <p>{@code groupSize} 决定每个文件装多少章：1 是每章一个文件；大于 1 时按
     * 连续 N 章合并成一个文件（几千章的书用 1 会产出几千个文件，很不好管理）。</p>
     */
    private static String saveSplit(Context ctx, String dirName, String assembled, int groupSize)
            throws IOException {
        List<String[]> chapters = splitChapters(assembled);
        int written = 0;
        String dir = clipBytes(sanitize(dirName), MAX_NAME_BYTES);
        // 同一天重复下载会落到同一个目录，先把上次的章节文件清掉再写，
        // 否则旧的空章与新的正文混在一起，看起来像「下载了两遍」
        cleanSplitDir(ctx, DIR_NAME + "/" + dir);
        for (int i = 0; i < chapters.size(); i += groupSize) {
            int end = Math.min(i + groupSize, chapters.size());
            String head = clipBytes(sanitize(chapters.get(i)[0]), 150);
            String name;
            if (groupSize == 1) {
                name = String.format(Locale.US, "%04d_%s.txt", i + 1, head);
            } else {
                // 多章合并时文件名标出「序号范围_首章标题」，一眼能看出装了几章
                name = String.format(Locale.US, "%04d-%04d_%s.txt", i + 1, end, head);
            }
            StringBuilder sb = new StringBuilder();
            for (int k = i; k < end; k++) {
                sb.append(chapters.get(k)[0]).append("\n\n")
                        .append(chapters.get(k)[1]).append("\n\n\n");
            }
            // 分章文件统一放进以书名命名的子目录，避免几百个文件散在下载目录里
            writeFile(ctx, DIR_NAME + "/" + dir + "/" + name, "text/plain",
                    sb.toString().getBytes("utf-8"));
            written++;
        }
        String unit = groupSize == 1
                ? (written + " 个章节文件")
                : (written + " 个文件，每个含 " + groupSize + " 章");
        return "下载/" + DIR_NAME + "/" + dir + "/（" + unit + "）";
    }

    // ================= 写文件 =================

    /**
     * 写文件到「下载/{@link #DIR_NAME}/」下的相对路径。
     *
     * <p>Android 10+ 走 MediaStore（分区存储），更低版本直接写外部存储目录。</p>
     */
    public static String writeFile(Context ctx, String relativeName, String mime, byte[] data)
            throws IOException {
        String rel = relativeName.startsWith(DIR_NAME + "/")
                ? relativeName : DIR_NAME + "/" + relativeName;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return writeViaMediaStore(ctx, rel, mime, data);
        }
        File dir = new File(Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                parentOf(rel));
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建目录：" + dir);
        }
        File f = new File(dir, fileNameOf(rel));
        FileOutputStream fo = new FileOutputStream(f);
        try {
            fo.write(data);
            fo.flush();
        } finally {
            fo.close();
        }
        return f.getAbsolutePath();
    }

    /**
     * 走 MediaStore 写文件（Android 10+ 分区存储）。
     *
     * <p>直接 insert 同名文件会撞上 MediaProvider 在 {@code files._data} 上的
     * UNIQUE 约束，报「保存失败：UNIQUE constraint failed」——典型场景是上次
     * 导出的文件已被删除、但媒体库里那条记录还在，于是新记录算出的磁盘路径
     * 与旧记录完全相同。</p>
     *
     * <p>所以先按「相对目录 + 文件名」找同名记录：找得到就复用它并覆盖内容
     * （等于就地覆盖，不会在下载目录里留下一堆「书名 (1).epub」）；找不到或
     * 覆盖不了（记录属于别的应用）就删掉旧记录再 insert，仍然失败就退化成
     * 带序号的新名字。<b>总之不能因为写文件失败让整本书白下。</b></p>
     */
    private static String writeViaMediaStore(Context ctx, String rel, String mime, byte[] data)
            throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String display = fileNameOf(rel);
        String parent = Environment.DIRECTORY_DOWNLOADS + "/" + parentOf(rel);

        Uri existing = findRow(cr, collection, parent, display);
        if (existing != null) {
            if (writeInto(ctx, existing, data)) {
                return locationOf(ctx, existing, rel);
            }
            try {
                cr.delete(existing, null, null);
            } catch (Exception ignored) {
                // 别的应用建的记录删不掉，下面换个名字写
            }
        }

        Uri uri;
        try {
            uri = insertRow(cr, collection, parent, display, mime);
        } catch (IOException first) {
            String alt = uniqueName(cr, collection, parent, display);
            try {
                uri = insertRow(cr, collection, parent, alt, mime);
            } catch (IOException second) {
                throw new IOException(first.getMessage());
            }
        }
        if (!writeInto(ctx, uri, data)) {
            throw new IOException("无法写入文件：" + rel);
        }
        return locationOf(ctx, uri, rel);
    }

    /** 按相对目录 + 文件名查同名记录；没有就返回 null。 */
    private static Uri findRow(ContentResolver cr, Uri collection, String parent, String display) {
        String withSlash = parent.endsWith("/") ? parent : parent + "/";
        String sel = MediaStore.MediaColumns.RELATIVE_PATH + " IN (?,?) AND "
                + MediaStore.MediaColumns.DISPLAY_NAME + " = ?";
        String[] args = {withSlash, parent, display};
        Cursor c = null;
        try {
            c = cr.query(collection, new String[]{MediaStore.MediaColumns._ID}, sel, args, null);
            if (c != null && c.moveToFirst()) {
                return ContentUris.withAppendedId(collection, c.getLong(0));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return null;
    }

    /**
     * 分章导出前先清掉上一次留下的章节文件。
     *
     * <p>目录名带日期，同一天重新下载会复用同一个目录；不清理的话新旧两份会
     * 混在一起（上一版就产出过 206 个文件、其中一半是 0 字节的空章）。</p>
     */
    private static void cleanSplitDir(Context ctx, String relDir) {
        final Pattern p = Pattern.compile("^\\d{4}(-\\d{4})?_.*\\.txt$");
        String parent = Environment.DIRECTORY_DOWNLOADS + "/" + relDir;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentResolver cr = ctx.getContentResolver();
                Uri col = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                List<Uri> stale = new ArrayList<>();
                Cursor c = cr.query(col, new String[]{MediaStore.MediaColumns._ID,
                                MediaStore.MediaColumns.DISPLAY_NAME},
                        MediaStore.MediaColumns.RELATIVE_PATH + " IN (?,?)",
                        new String[]{parent + "/", parent}, null);
                if (c != null) {
                    try {
                        while (c.moveToNext()) {
                            String name = c.getString(1);
                            if (name != null && p.matcher(name).matches()) {
                                stale.add(ContentUris.withAppendedId(col, c.getLong(0)));
                            }
                        }
                    } finally {
                        c.close();
                    }
                }
                for (Uri u : stale) {
                    try {
                        cr.delete(u, null, null);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
            return;
        }
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), relDir);
            File[] all = dir.listFiles();
            if (all != null) {
                for (File f : all) {
                    if (p.matcher(f.getName()).matches()) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static Uri insertRow(ContentResolver cr, Uri collection, String parent,
                                 String display, String mime) throws IOException {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, display);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                parent.endsWith("/") ? parent : parent + "/");
        try {
            Uri uri = cr.insert(collection, cv);
            if (uri == null) {
                throw new IOException("系统拒绝创建文件：" + display);
            }
            return uri;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /**
     * 覆盖写入：先试 {@code "wt"}（明确截断），个别 Provider 不认这个 mode，
     * 再退回默认 {@code "w"}——对 MediaStore 同样是截断写。
     */
    private static boolean writeInto(Context ctx, Uri uri, byte[] data) {
        return writeStream(ctx, uri, "wt", data) || writeStream(ctx, uri, "w", data);
    }

    private static boolean writeStream(Context ctx, Uri uri, String mode, byte[] data) {
        OutputStream os = null;
        try {
            os = ctx.getContentResolver().openOutputStream(uri, mode);
            if (os == null) {
                return false;
            }
            os.write(data);
            os.flush();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 「书名 (2).epub」式的新名字，避免与已有记录相撞。 */
    private static String uniqueName(ContentResolver cr, Uri collection, String parent,
                                     String display) {
        String base = display;
        String ext = "";
        int dot = display.lastIndexOf('.');
        if (dot > 0) {
            base = display.substring(0, dot);
            ext = display.substring(dot);
        }
        for (int i = 2; i < 1000; i++) {
            String cand = base + " (" + i + ")" + ext;
            if (findRow(cr, collection, parent, cand) == null) {
                return cand;
            }
        }
        return base + " (" + System.currentTimeMillis() + ")" + ext;
    }

    /**
     * 写完再回读真实路径。
     *
     * <p>MediaProvider 有可能改名（例如「书名 (1).epub」），只按请求的名字拼
     * location 会让「分享文件」拿到旧文件，所以以库里记录为准。</p>
     */
    private static String locationOf(Context ctx, Uri uri, String fallback) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, new String[]{
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String rel = c.getString(0);
                String name = c.getString(1);
                if (rel != null && name != null) {
                    if (rel.endsWith("/")) {
                        rel = rel.substring(0, rel.length() - 1);
                    }
                    if (rel.startsWith(Environment.DIRECTORY_DOWNLOADS)) {
                        rel = rel.substring(Environment.DIRECTORY_DOWNLOADS.length());
                        if (rel.startsWith("/")) {
                            rel = rel.substring(1);
                        }
                    }
                    return "下载/" + (rel.isEmpty() ? "" : rel + "/") + name;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return "下载/" + fallback;
    }

    private static String fileNameOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    private static String parentOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    // ================= 章节切分 =================

    /**
     * 章节标题行：{@code 第X章/节/卷/回/篇} 或 {@code Chapter N}。
     *
     * <p>标题长度限制在 25 字以内，并且不能紧跟「的/了/是/在/有」这类助词——
     * 否则正文里「第一章讲的是什么呢……」这种句子会被当成标题，目录立刻被撑爆。
     * 这些限制都是实测出来的：放开到 60 字时，一段 48 字的普通正文就会误判。</p>
     */
    private static final Pattern CHAPTER_LINE = Pattern.compile(
            "^\\s*(?:第\\s*[0-9零一二三四五六七八九十百千万两]{1,10}\\s*"
                    + "[章节回卷篇](?!\\s*[、，,：:．.]?\\s*[的了是在有和与])"
                    + "\\s*[^\\n]{0,25}"
                    + "|Chapter\\s+\\d{1,5}[^\\n]{0,25})\\s*$");

    private static boolean isChapterLine(String line) {
        return line != null && CHAPTER_LINE.matcher(line).matches();
    }

    /**
     * 把全书文本切成章节列表，每项为 {标题, 正文}。
     * 识别不到章节标题时，整本作为单章返回。
     */
    public static List<String[]> splitChapters(String text) {
        List<String[]> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String[] lines = text.split("\n", -1);
        String curTitle = null;
        StringBuilder cur = new StringBuilder();
        for (String line : lines) {
            if (isChapterLine(line)) {
                if (curTitle != null) {
                    out.add(new String[]{curTitle, cur.toString().trim()});
                }
                curTitle = line.trim();
                cur.setLength(0);
            } else if (curTitle != null) {
                cur.append(line).append('\n');
            }
            // 第一章之前的版权/来源头部直接丢弃
        }
        if (curTitle != null) {
            out.add(new String[]{curTitle, cur.toString().trim()});
        }
        if (out.isEmpty()) {
            out.add(new String[]{"正文", text.trim()});
        }
        return out;
    }

    private static int countChapters(String text) {
        return splitChapters(text).size();
    }

    // ================= EPUB =================

    private static final String XHTML_HEAD =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<!DOCTYPE html>\n"
                    + "<html xmlns=\"http://www.w3.org/1999/xhtml\">\n<head>\n"
                    + "<meta charset=\"utf-8\"/>\n<title>%s</title>\n"
                    + "<style>body{line-height:1.7;margin:1em;}h1{font-size:1.3em;"
                    + "margin:1.2em 0 0.8em;}p{text-indent:2em;margin:0 0 0.6em;}</style>\n"
                    + "</head>\n<body>\n";

    /**
     * 生成 EPUB 3 电子书。
     *
     * @param book   书名 / 作者
     * @param source 书源名，写进描述
     */
    public static byte[] buildEpub(SearchBook book, String source, String assembled)
            throws IOException {
        String title = book == null || book.name == null || book.name.isEmpty()
                ? "未命名" : book.name;
        String author = book == null || book.author == null || book.author.isEmpty()
                ? "佚名" : book.author;
        String uuid = "urn:uuid:" + java.util.UUID.randomUUID();
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());

        List<String[]> chapters = splitChapters(assembled);
        ZipWriter zip = new ZipWriter();
        // mimetype 必须是第一个条目且不压缩
        zip.add("mimetype", "application/epub+zip".getBytes("utf-8"), false);
        zip.add("META-INF/container.xml",
                ("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<container version=\"1.0\" "
                        + "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n"
                        + "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" "
                        + "media-type=\"application/oebps-package+xml\"/></rootfiles>\n"
                        + "</container>").getBytes("utf-8"), true);

        StringBuilder manifest = new StringBuilder();
        StringBuilder spine = new StringBuilder();
        StringBuilder nav = new StringBuilder();
        StringBuilder playOrder = new StringBuilder();

        for (int i = 0; i < chapters.size(); i++) {
            String[] ch = chapters.get(i);
            String file = String.format(Locale.US, "chap%04d.xhtml", i + 1);
            String body = XHTML_HEAD.replace("%s", esc(ch[0]))
                    + "<h1>" + esc(ch[0]) + "</h1>\n"
                    + paragraphs(ch[1])
                    + "</body>\n</html>\n";
            zip.add("OEBPS/" + file, body.getBytes("utf-8"), true);

            manifest.append("<item id=\"c").append(i + 1).append("\" href=\"").append(file)
                    .append("\" media-type=\"application/xhtml+xml\"/>\n");
            spine.append("<itemref idref=\"c").append(i + 1).append("\"/>\n");
            nav.append("<li><a href=\"").append(file).append("\">")
                    .append(esc(ch[0])).append("</a></li>\n");
        }

        String opf = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" "
                + "unique-identifier=\"bookid\">\n"
                + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n"
                + "<dc:identifier id=\"bookid\">" + uuid + "</dc:identifier>\n"
                + "<dc:title>" + esc(title) + "</dc:title>\n"
                + "<dc:creator>" + esc(author) + "</dc:creator>\n"
                + "<dc:language>zh</dc:language>\n"
                + "<dc:date>" + date + "</dc:date>\n"
                + "<dc:description>" + esc("由小说下载器导出"
                + (source == null || source.isEmpty() ? "" : "，书源：" + source))
                + "</dc:description>\n"
                + "<meta property=\"dcterms:modified\">" + date + "T00:00:00Z</meta>\n"
                + "</metadata>\n<manifest>\n"
                + "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" "
                + "properties=\"nav\"/>\n"
                + manifest
                + "</manifest>\n<spine>\n"
                + "<itemref idref=\"nav\"/>\n"
                + spine
                + "</spine>\n</package>\n";
        zip.add("OEBPS/content.opf", opf.getBytes("utf-8"), true);

        String navDoc = XHTML_HEAD.replace("%s", esc(title))
                + "<nav xmlns:epub=\"http://www.idpf.org/2007/ops\" epub:type=\"toc\" "
                + "id=\"toc\"><h1>目录</h1>\n<ol>\n" + nav + "</ol>\n</nav>\n"
                + "</body>\n</html>\n";
        zip.add("OEBPS/nav.xhtml", navDoc.getBytes("utf-8"), true);

        return zip.finish();
    }

    /** 把纯文本段落转成 XHTML 段落。 */
    private static String paragraphs(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            sb.append("<p>").append(esc(t)).append("</p>\n");
        }
        if (sb.length() == 0) {
            sb.append("<p>（本章无内容）</p>\n");
        }
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    // ================= 最小 ZIP 写入器 =================

    /**
     * 手写 ZIP：为的是满足 EPUB 对 mimetype 条目的严格要求
     * （必须是第一个条目、必须 STORED、且没有 extra field）。
     */
    private static final class ZipWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        private final List<byte[]> names = new ArrayList<>();
        private final List<Long> crcs = new ArrayList<>();
        private final List<Integer> sizes = new ArrayList<>();
        private final List<Integer> offsets = new ArrayList<>();
        private final List<Integer> compressedSizes = new ArrayList<>();
        private final List<Integer> methods = new ArrayList<>();

        void add(String name, byte[] data, boolean compress) throws IOException {
            byte[] nameBytes = name.getBytes("utf-8");
            CRC32 crc = new CRC32();
            crc.update(data);
            long crcValue = crc.getValue();
            byte[] payload = data;
            int method = 0;
            if (compress) {
                payload = deflate(data);
                method = 8;
                if (payload.length >= data.length) {
                    payload = data;
                    method = 0;
                }
            }
            int offset = out.size();
            writeInt(0x04034b50);
            writeShort(20);              // version needed
            writeShort(0);               // flags
            writeShort(method);
            writeShort(0);               // mod time
            writeShort(0x21);            // mod date（1980-01-01，保证可复现）
            writeInt((int) crcValue);
            writeInt(payload.length);
            writeInt(data.length);
            writeShort(nameBytes.length);
            writeShort(0);               // extra length
            out.write(nameBytes);
            out.write(payload);

            names.add(nameBytes);
            crcs.add(crcValue);
            sizes.add(data.length);
            offsets.add(offset);
            compressedSizes.add(payload.length);
            methods.add(method);
        }

        byte[] finish() throws IOException {
            int cdStart = out.size();
            for (int i = 0; i < names.size(); i++) {
                writeInt(0x02014b50);
                writeShort(20);          // version made by
                writeShort(20);          // version needed
                writeShort(0);
                writeShort(methods.get(i));
                writeShort(0);
                writeShort(0x21);
                writeInt((int) (long) crcs.get(i));
                writeInt(compressedSizes.get(i));
                writeInt(sizes.get(i));
                writeShort(names.get(i).length);
                writeShort(0);           // extra
                writeShort(0);           // comment
                writeShort(0);           // disk
                writeShort(0);           // internal attrs
                writeInt(0);             // external attrs
                writeInt(offsets.get(i));
                out.write(names.get(i));
            }
            int cdSize = out.size() - cdStart;
            writeInt(0x06054b50);
            writeShort(0);
            writeShort(0);
            writeShort(names.size());
            writeShort(names.size());
            writeInt(cdSize);
            writeInt(cdStart);
            writeShort(0);
            return out.toByteArray();
        }

        private static byte[] deflate(byte[] data) {
            Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
            d.setInput(data);
            d.finish();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length);
            byte[] buf = new byte[8192];
            while (!d.finished()) {
                int n = d.deflate(buf);
                bos.write(buf, 0, n);
            }
            d.end();
            return bos.toByteArray();
        }

        private void writeShort(int v) {
            out.write(v & 0xFF);
            out.write((v >>> 8) & 0xFF);
        }

        private void writeInt(int v) {
            out.write(v & 0xFF);
            out.write((v >>> 8) & 0xFF);
            out.write((v >>> 16) & 0xFF);
            out.write((v >>> 24) & 0xFF);
        }
    }
}
