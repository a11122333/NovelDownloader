# 小说下载器 (NovelDownloader)

一个轻量的 Android 小说下载器：多书源并发搜索，解析目录后按需下载正文，导出为 TXT / 分章 TXT / EPUB。

> 仅供学习与技术交流使用。所有书源均来自互联网，请勿用于商业用途。

## 功能

- **多书源并发搜索**：内置 12 个书源开箱即用；支持导入自定义书源、单独启用 / 停用、整包导出备份、逐个连通性测试。
- **搜索方式 GET / POST**：书源可声明 `searchMethod` / `searchBody`，并带 Cookie 预热（`warmUp`），兼容「搜索只收 POST + 需要会话 Cookie」的站点。
- **下载**：1–64 线程（默认 8）、可指定章节范围、章内分页自动合并；离开页面或切板块不中断，可暂停 / 继续（断点续传）。
- **失败处理**：失败章节自动重试最多 2 轮；可在下载页单独「重试失败章节」；章节已下好、仅写文件失败时用「重新保存」，不必重下整本。
- **三种导出格式**：单文件 TXT / 分章 TXT / EPUB，统一存到 `下载/小说下载器/`，无加密。分章可设「每 N 章一个文件」（1–200），文件名支持 `{title}`、`{author}`、`{source}`、`{count}`、`{date}`、`{format}` 模板并实时预览。
- **正文处理**：去标签与站点广告、压缩多余空行、剥掉与目录重复的章节标题；导出前可选繁简转换（OpenCC 单字表）。
- **本地书库与阅读器**：扫描已下载的 TXT，可在应用内阅读（目录跳转、字号、行距、四种底色、进度记忆）。
- **Material Design 3 界面**：配色由品牌蓝经 HCT 色空间推导；底栏分「搜索 / 下载 / 设置」，键盘弹起时自动收起。
- **书源规则**：正则抽取，支持独立目录页 / 目录分页 / 章内分页 / 列表式目录 / 索引式章节，兼容 GBK 站点与 JSON 正文。

## 内置书源

| 书源 | 编码 | 搜索 | 实测（《斗破苍穹》） |
| --- | --- | --- | --- |
| 笔趣阁5200 `biquge5200.cc`、笔趣阁666 `666biquge.com`、笔趣阁b5200 `b5200.net`、修文小说网 `xiunews.com` | GBK | GET | 各 49 条 / 1676 章 |
| 书海阁 `shuhaige.net` | UTF-8 | POST + Cookie | 20 条 / 1664 章 |
| 爱看书城 `m.2kk.la` | UTF-8 | GET | 100 条 |
| 零点看书、爱下书网 `aixiashu.la`、啃书小说 `kenshuzw.la` | GBK / UTF-8 | GET | 各 50 条 |
| 书客吧 `shuke8.cc`、独步小说 `dbxsn.com` | UTF-8 | GET | 5 条 / 150 章、3 条 / 78 章 |
| 番茄小说 | UTF-8 | GET（第三方接口） | 11 条 / 464 章 |

前四个是同一镜像站群，书库相同，价值在于线路冗余；番茄书源依赖第三方接口，响应偏慢、可能失效，可在设置里单独关掉。

## 构建

不使用 Gradle，脚本手动构建。需要 JDK 17 与 Android SDK（`build-tools;34.0.0`、`platforms;android-34`）：

```bash
bash build.sh
```

流程：`aapt2 compile` → `aapt2 link`（生成 `R.java`）→ `javac` → `d8` → 打包 `classes.dex` → `zipalign` → `apksigner`。版本号在 `build.sh` 顶部的 `VER_CODE` / `VER_NAME`，产物为 `NovelDownloader-<版本>.apk`（另出一份同名 `.zip`，避免中文名传输损坏）。

- 中文 / 空格路径下 `javac` 会因 locale 报错，用 `build-local.sh` 包装（设置 UTF-8 环境变量并传入项目路径）。
- aarch64 主机上官方 `aapt2` / `zipalign` 是 x86_64 二进制，可用 `qemu-x86_64-static -L <rootfs>` 包装，真二进制放在 `build-tools/34.0.0/native/`。

签名：脚本在 `keystore.jks` 缺失时自动生成测试密钥；该文件已被 `.gitignore` 排除，不随仓库公开。更换密钥后无法覆盖安装，需先卸载旧版本。

## 书源格式

基于正则抽取的简化规则格式：

```json
{
  "name": "示例书源", "charset": "utf-8", "baseUrl": "https://example.com",
  "searchUrl": "https://example.com/search?q={{key}}&p={{page}}",
  "listRule": "book\\.aspx\\?id=(\\d+)[^>]*>\\s*([^<]+)\\s*</a>(?:\\s*作者[:：]\\s*([^<\\s]+))?",
  "chapterList": "<a[^>]*href=\"([^\"]+)\"[^>]*>\\s*(第[^<]*章[^<]*)\\s*</a>",
  "contentRule": "<div[^>]*id=\"content\"[^>]*>(.*?)</div>",
  "replace": [["<br>", "\n"], ["&nbsp;", " "], ["<[^>]+>", ""]]
}
```

| 字段 | 说明 |
| --- | --- |
| `name` / `charset` / `baseUrl` | 名称 / 网页编码（`utf-8`、`gbk`）/ 主域（可选） |
| `searchUrl` | 搜索地址，`{{key}}` 关键字、`{{page}}` 页码 |
| `searchMethod` / `searchBody` / `warmUp` | `get`（默认）或 `post`；POST 表单体；搜索前先访问一次以取 Cookie |
| `listRule` | 列表规则，组 1 = 书籍链接，2 = 书名，3 = 作者（可选） |
| `chapterList` | 目录规则，组 1 = 章节链接，2 = 章节标题 |
| `contentRule` | 正文规则，组 1 = 正文 HTML，再按 `replace` 清洗（Java 用 `$1` 反向引用） |
| `tocUrl` / `tocPages` | 独立目录页模板（`{{book}}` = 详情页 URL）/ 目录分页规则，逐页合并去重 |
| `contentPages` | 章内分页规则，组 1 = 「下一页」URL，逐页抓取合并同一章 |
| `chapterIdRule` + `chapterUrlTemplate` | 列表式目录：详情页取「章节 ID + 标题」，用模板拼章节 URL（`{{id}}`） |
| `bookUrlTemplate` | 搜索结果只返回书籍 ID 时，用它拼详情页 URL |
| `indexChapters` | 索引式章节（`lastIdRule` / `catalogRule` / `urlTemplate` / `articleRule`）：目录由 JS 渲染时，用「最新章节 ID + 目录下标」反推每章 URL；此类书源不可搜索，需用首页 🔗 粘贴详情页链接打开 |
| `jsonEscape` / `paragraphIndent` | 正文为 JSON 转义字符串时先反转义；导出时给每段加缩进前缀（如 `　　`） |
| `puaDecode` / `puaMode` | 「番茄式」PUA 字体解码 |

规则使用 `Pattern.compile(regex, DOTALL)`，`.` 可跨行；`replace` 至少需要「`<br>` → 换行」「`<[^>]+>` → 空」「`&nbsp;` → 空格」三条才能得到干净正文。未配置 `chapterList` 时，搜索到的详情页会被当作单章。

**POST + Cookie 示例**（书海阁：搜索只收 POST，且需先访问首页取得 `waf_sc` 会话 Cookie，否则结果页恒为空）：

```json
{
  "name": "书海阁", "charset": "utf-8",
  "searchUrl": "https://www.shuhaige.net/search.html",
  "searchMethod": "post", "searchBody": "searchkey={{key}}",
  "warmUp": "https://www.shuhaige.net/"
}
```

在「书源管理」页可粘贴上述 JSON（单个对象或数组）导入自定义书源，同名自定义书源会覆盖内置书源。

> 番茄书源说明：番茄官方 App 接口有签名校验（`X-Gorgon`/`X-Argus`）无法直接调用，官方网页对多数章节只给约 300 字预览，因此内置书源采用「官方网页目录 + 第三方接口正文」的组合（全文取自他人服务器，可能随时失效，失效时替换 `searchUrl` / `chapterUrlTemplate` 或移除该书源）。正文原文是「一句一段」，导出后每段一行，`paragraphIndent` 会补上段首缩进。

## 下载

- 当前版本：**2.0**（versionCode 20），APK 见 [Releases](https://github.com/a11122333/NovelDownloader/releases)
- 最低 Android 7.0（API 24），目标 API 34；安装需允许「未知来源应用」
- 也可按上文「构建」自行编译

## 更新日志

### 2.0

- 内置书源 7 → 12，新增书海阁；书源规则支持 POST 搜索与 Cookie 预热。
- 新增导出格式 EPUB 与「分章 TXT 每文件章数」，文件名模板支持实时预览。
- 新增本地书库与应用内阅读器（目录、字号、行距、底色、进度记忆）。
- 界面全面改为 Material Design 3，改为底栏三板块布局。

### 1.15

- 新增番茄小说书源；链接打开支持批量解析与勾选。

## 第三方资源

- 繁简转换单字表（`assets/ts.txt`、`assets/st.txt`）派生自 [OpenCC](https://github.com/BYVoid/OpenCC)（Apache-2.0）。
- 界面图标取自 [Google Material Design Icons](https://github.com/google/material-design-icons)（Apache-2.0），
  已转为 Android Vector Drawable，填充色由代码 tint 指定。
