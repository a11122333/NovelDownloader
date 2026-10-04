# 小说下载器 (NovelDownloader)

一个轻量的 Android 小说下载器：从多个书源并发搜索小说，解析目录后按需下载正文，导出为明文 TXT。

> 仅供学习与技术交流使用。所有书源均来自互联网，请勿用于任何商业用途，请在下载后 24 小时内删除。

## 功能特性

- **多书源并发搜索**：内置正则书源开箱即用，支持从剪贴板导入自定义书源，可单独启用 / 停用。
- **目录解析**：支持独立目录页（`tocUrl`）与目录分页（`tocPages`），自动合并、按 URL 去重。
- **指定章节范围下载**：下载时可指定起始 / 结束章节，只下载需要的部分。
- **多线程下载**：1 - 64 线程可选（默认 8），章节结果按下标归位，保证顺序不乱。
- **正文分页合并**：支持章内分页（`contentPages`）逐页抓取并合并，避免长章节被截断。
- **索引式章节**（`indexChapters`）：适配目录由 JS 动态渲染、静态 HTML 拿不到章节链接的站点，由「最新章节 ID + 目录下标」反推每章 URL。
- **链接直接打开（支持批量）**：首页搜索栏右侧的 🔗 可一次粘贴多行书籍详情页链接，解析后勾选（含全选）要打开的书，再逐个打开详情页处理；单个链接则直接打开。
- **繁简转换**：下载设置里可选「不转换 / 繁→简 / 简→繁」，导出前统一转换（基于 OpenCC 单字映射表）。
- **排版清洗**：去除 HTML 标签与站点广告文本，压缩多余空行（如 `&nbsp;` / 连续 `<br>` 造成的空行）。
- **明文 TXT 导出**：保存到 `下载/小说下载器/` 目录，无任何加密。
- **Material Design 3 风格 UI**：统一配色（主色 / 容器色 / 表面色）、圆角、阴影与涟漪反馈。

## 项目结构

```
NovelDownloader/
├── AndroidManifest.xml              # 清单文件
├── build.sh                         # 无 Gradle 的手动构建脚本（aapt2 -> javac -> d8 -> apksigner）
├── assets/
│   ├── builtin_sources.json         # 内置书源配置
│   ├── ts.txt                       # 繁→简单字映射（OpenCC）
│   └── st.txt                       # 简→繁单字映射（OpenCC）
├── res/                             # 资源（图标、字符串）
└── java/com/niganma/noveldown/
    ├── MainActivity.java            # 首页：搜索 / 🔗 链接打开
    ├── DetailActivity.java          # 详情：加载目录 + 下载设置（范围 / 线程 / 繁简）
    ├── AboutActivity.java           # 书源管理：启用停用 / 导入 / 清空
    ├── BookSource.java              # 书源模型（正则规则格式）
    ├── SourceStore.java             # 书源仓库：内置 + 自定义 + 启停
    ├── Searcher.java                # 并发搜索
    ├── Downloader.java              # 目录加载 + 多线程下载
    ├── Rules.java                   # 正则抽取 / 清洗 / 排版规范化引擎
    ├── CharConv.java                # 繁简转换（OpenCC 单字表）
    ├── Http.java                    # 网络请求（含编码处理）
    ├── FileExport.java              # 明文 TXT 导出
    ├── UiUtil.java / UiKit.java     # MD3 设计系统（颜色 / 组件）
    ├── ResultAdapter.java           # 搜索结果列表
    ├── ChapterAdapter.java          # 目录列表
    ├── Chapter.java / SearchBook.java
    └── App.java                     # 全局线程池 / 主线程 Handler
```

## 构建

本工程不使用 Gradle，采用脚本手动构建，需要：

- JDK 17
- Android SDK：`build-tools;34.0.0`、`platforms;android-34`

```bash
bash build.sh
```

脚本流程：`aapt2 compile` -> `aapt2 link` -> `javac` -> `d8` -> 打包 `classes.dex` -> `zipalign` -> `apksigner`。
产物为 `NovelDownloader-<版本>.apk`（同时输出同名 `.zip` 以规避中文名传输导致的损坏）。

签名说明：脚本在 `keystore.jks` 不存在时会自动生成一个测试用密钥库；该文件已在 `.gitignore` 中排除，**不会**随仓库公开。发布正式版本时请替换为你自己的签名密钥。

## 书源格式

书源为本项目自定义的简化规则格式，基于正则表达式抽取：

```json
{
  "name":         "示例书源",
  "charset":      "utf-8",
  "baseUrl":      "https://example.com",
  "searchUrl":    "https://example.com/search?q={{key}}&p={{page}}",
  "listRule":     "book\\.aspx\\?id=(\\d+)[^>]*>\\s*([^<]+)\\s*</a>(?:\\s*作者[:：]\\s*([^<\\s]+))?",
  "chapterList":  "<a[^>]*href=\"([^\"]+)\"[^>]*>\\s*(第[^<]*章[^<]*)\\s*</a>",
  "contentRule":  "<div[^>]*id=\"content\"[^>]*>(.*?)</div>",
  "replace":      [["<br>", "\n"], ["&nbsp;", " "], ["<[^>]+>", ""]],
  "tocUrl":       "{{book}}all.html",
  "tocPages":     "<option value=\"([^\"]+)\"",
  "contentPages": "<a[^>]*href=\"([^\"]+)\"[^>]*>下一页</a>"
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `name` | 书源名称 |
| `charset` | 网页编码，默认 `utf-8`，亦可 `gbk` |
| `baseUrl` | 站点主域（可选，用于参考） |
| `searchUrl` | 搜索地址，`{{key}}` 为关键字、`{{page}}` 为页码 |
| `listRule` | 列表规则，捕获组：1 = 书籍链接，2 = 书名，3 = 作者（可选） |
| `chapterList` | 目录规则，捕获组：1 = 章节链接，2 = 章节标题 |
| `contentRule` | 正文规则，第 1 组为正文 HTML |
| `replace` | 清洗规则数组，`[正则, 替换文本]` 依次执行 |
| `tocUrl` | 目录页模板，`{{book}}` 替换为书籍详情页 URL（详情页只列最新章节时使用） |
| `tocPages` | 目录分页规则，第 1 组为其余目录页 URL（可多个），逐页合并去重 |
| `contentPages` | 章内分页规则，第 1 组为「下一页」URL，逐页抓取合并同一章 |
| `indexChapters` | 索引式章节配置（见下），用于目录由 JS 渲染、页面无章节链接的站点 |

若未配置 `chapterList`，则把搜索到的详情页当作单章处理。

### 索引式章节（indexChapters）

部分站点的目录由 JS 动态生成，静态 HTML 里既没有 `href` 也没有章节 ID，常规 `chapterList` 无法枚举章节。若该站章节 ID 连续递增，可用如下配置：

```json
{
  "name": "示例站点",
  "baseUrl": "https://example.com",
  "contentRule": "<div class=\"chapter-content\">([\\s\\S]*?)</div>",
  "indexChapters": {
    "lastIdRule":  "reader\\.html\\?articleid=\\d+&(?:amp;)?chapterid=(\\d+)",
    "catalogRule": "data-idx=\"(\\d+)\"[^>]*>\\s*<span class=\"chapter-name\">([^<]*)</span>",
    "urlTemplate": "https://example.com/reader.html?articleid={{article}}&chapterid={{id}}",
    "articleRule": "articleid=(\\d+)"
  }
}
```

| 字段 | 说明 |
| --- | --- |
| `lastIdRule` | 在书籍详情页取「最新章节 ID」，第 1 组为 ID |
| `catalogRule` | 在任一章节阅读页取目录项，第 1 组 = 章节目录下标，第 2 组 = 章节标题（可匹配多项） |
| `urlTemplate` | 章节 URL 模板，`{{article}}` 为书籍 ID，`{{id}}` 为章节 ID |
| `articleRule` | 可选，从详情页 URL 取书籍 ID，默认 `articleid=(\d+)` |

原理：详情页拿到最新章 ID = `lastId`；阅读页目录给出全部下标（最大值 `maxIdx`）；则基准 ID = `lastId - maxIdx`，第 `i` 章 ID = 基准 ID + `i`。因为无法搜索，此类书源需用首页的 🔗「链接打开」粘贴详情页链接使用。

在 App 的「书源管理」页可粘贴上述 JSON（单个对象或数组）导入自定义书源；同名自定义书源会覆盖内置书源。

## 下载

最新 APK 通过 [Releases](https://github.com/a11122333/NovelDownloader/releases) 页面分发，请前往下载对应版本的 `NovelDownloader-<版本>.apk`。

当前版本：**1.12**

- 最低系统版本：Android 7.0（API 24），目标 API 34
- 安装需在系统中允许「安装未知来源应用」
- 下载后可对照 Release 说明中的 SHA-256 校验文件完整性

也可以按上文「构建」章节自行编译。

## 免责声明

本项目仅用于 Android 开发与网络爬虫技术的学习研究。使用者需自行承担因使用本工具产生的一切后果，请遵守当地法律法规及各网站的服务条款。

## 第三方资源

繁简转换所用的单字映射表（`assets/ts.txt`、`assets/st.txt`）派生自 [OpenCC](https://github.com/BYVoid/OpenCC)（Apache-2.0 许可）。
