# 小说下载器 (NovelDownloader)

一个轻量的 Android 小说下载器：从多个书源并发搜索小说，解析目录后按需下载正文，导出为明文 TXT。

> 仅供学习与技术交流使用。所有书源均来自互联网，请勿用于任何商业用途，请在下载后 24 小时内删除。

## 功能特性

- **多书源并发搜索**：内置正则书源开箱即用，支持从剪贴板导入自定义书源，可单独启用 / 停用。
- **目录解析**：支持独立目录页（`tocUrl`）与目录分页（`tocPages`），自动合并、按 URL 去重。
- **指定章节范围下载**：下载时可指定起始 / 结束章节，只下载需要的部分。
- **多线程下载**：1 - 64 线程可选（默认 8），章节结果按下标归位，保证顺序不乱。
- **正文分页合并**：支持章内分页（`contentPages`）逐页抓取并合并，避免长章节被截断。
- **排版清洗**：去除 HTML 标签与站点广告文本，压缩多余空行（如 `&nbsp;` / 连续 `<br>` 造成的空行）。
- **明文 TXT 导出**：保存到 `下载/小说下载器/` 目录，无任何加密。
- **Material Design 3 风格 UI**：统一配色（主色 / 容器色 / 表面色）、圆角、阴影与涟漪反馈。

## 项目结构

```
NovelDownloader/
├── AndroidManifest.xml              # 清单文件
├── build.sh                         # 无 Gradle 的手动构建脚本（aapt2 -> javac -> d8 -> apksigner）
├── assets/
│   └── builtin_sources.json         # 内置书源配置
├── res/                             # 资源（图标、字符串）
└── java/com/niganma/noveldown/
    ├── MainActivity.java            # 首页：搜索
    ├── DetailActivity.java          # 详情：加载目录 + 下载设置（章节范围 / 线程数）
    ├── AboutActivity.java           # 书源管理：启用停用 / 导入 / 清空
    ├── BookSource.java              # 书源模型（正则规则格式）
    ├── SourceStore.java             # 书源仓库：内置 + 自定义 + 启停
    ├── Searcher.java                # 并发搜索
    ├── Downloader.java              # 目录加载 + 多线程下载
    ├── Rules.java                   # 正则抽取 / 清洗 / 排版规范化引擎
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

若未配置 `chapterList`，则把搜索到的详情页当作单章处理。

在 App 的「书源管理」页可粘贴上述 JSON（单个对象或数组）导入自定义书源；同名自定义书源会覆盖内置书源。

## 下载

最新构建版 APK 已随仓库提供：[NovelDownloader-1.7.apk](./NovelDownloader-1.7.apk)（versionCode 8 / versionName 1.7）。

- SHA-256：`6dff84cf2575abca09147fba428434a112940a2f329d0e6e0945aed9a010ad1d`
- 最低系统版本：Android 7.0（API 24），目标 API 34
- 安装需在系统中允许「安装未知来源应用」

也可以按上文「构建」章节自行编译。

## 免责声明

本项目仅用于 Android 开发与网络爬虫技术的学习研究。使用者需自行承担因使用本工具产生的一切后果，请遵守当地法律法规及各网站的服务条款。

## 许可证

本项目采用 **署名—非商业性使用—相同方式共享 4.0 国际（CC BY-NC-SA 4.0）** 协议，全文见 [LICENSE](./LICENSE)。

你可以自由地：

- **共享** —— 以任何媒介或格式复制、发行本作品；
- **演绎** —— 修改、转换或以本作品为基础进行创作。

惟须遵守以下条件：

- **署名** —— 必须给出适当的署名，提供指向本协议的链接，并标明是否作出了修改；
- **非商业性使用** —— 不得将本作品用于商业目的；
- **相同方式共享（传递性）** —— 若你修改、转换或以本作品为基础进行创作，必须按照与本协议相同的协议分发你的作品。

完整条款以 [LICENSE](./LICENSE) 中的官方协议原文为准。
