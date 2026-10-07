# 小说下载器 (NovelDownloader)

一个轻量的 Android 小说下载器：从多个书源并发搜索小说，解析目录后按需下载正文，导出为 TXT / 分章 TXT / EPUB。

> 仅供学习与技术交流使用。所有书源均来自互联网，请勿用于任何商业用途，请在下载后 24 小时内删除。

## 功能特性

- **多书源并发搜索**：内置 12 个书源开箱即用（见下文「内置书源」），支持从剪贴板 / 文本框导入自定义书源，可单独启用 / 停用，可整包导出备份。
- **搜索方式 GET / POST**：书源可声明 `searchMethod` / `searchBody`，并带一个极简 Cookie 罐（`warmUp` 可先访问一次首页取会话 Cookie）。像「书海阁」这类**搜索必须 POST + 需要 WAF Cookie** 的站点因此也能用。单个书源第一次没出结果会自动重试一次，多书源并发时的偶发空页不再漏掉。
- **目录解析**：支持独立目录页（`tocUrl`）与目录分页（`tocPages`），自动合并、按 URL 去重；目录页顶部常见的倒序「最新章节」块会保留正序那一份，导出顺序仍是正文顺序。
- **指定章节范围下载**：下载时可指定起始 / 结束章节，只下载需要的部分。
- **多线程下载**：1 - 64 线程可选（默认 8），章节结果按下标归位，保证顺序不乱。
- **正文分页合并**：支持章内分页（`contentPages`）逐页抓取并合并，避免长章节被截断。
- **索引式章节**（`indexChapters`）：适配目录由 JS 动态渲染、静态 HTML 拿不到章节链接的站点，由「最新章节 ID + 目录下标」反推每章 URL。
- **链接直接打开（支持批量）**：首页搜索栏右侧的 🔗 可一次粘贴多行书籍详情页链接，解析后勾选（含全选）要打开的书，再逐个打开详情页处理；单个链接则直接打开。
- **番茄小说书源**：内置「番茄小说」，支持关键字搜索与整本下载（目录读取官方书页，正文经第三方接口获取，详见下方说明）。
- **繁简转换**：下载设置里可选「不转换 / 繁→简 / 简→繁」，导出前统一转换（基于 OpenCC 单字映射表）。
- **排版清洗**：去除 HTML 标签与站点广告文本，压缩多余空行（如 `&nbsp;` / 连续 `<br>` 造成的空行）；正文开头与目录重复的标题行会自动剥掉（只在章号一致时），分章导出不会再多出一半空章节。
- **三种导出格式**：单文件 TXT / **分章 TXT**（放进以书名命名的子目录）/ **EPUB**（带目录与元数据，自己按 EPUB 3 规范拼装，未引入第三方库），统一保存到 `下载/小说下载器/`，无任何加密。
- **文件名模板**：支持 `{title}` 书名、`{author}` 作者、`{source}` 书源、`{count}` 章节数、`{date}` 日期、`{format}` 格式，设置页可实时预览效果；文件名按 UTF-8 字节截断，超长模板不会写文件失败。
- **分章文件大小可调**：分章 TXT 可设「每章一个文件」或「每 N 章一个文件」（1 - 200，内置 5/10/20/50/100 预设）。几千章的书用每章一个文件会产出几千个文件，聚合成若干个更好管理；文件名会标出序号范围（如 `0001-0010_第1章….txt`）。同一目录重新导出前会先清掉上次的章节文件，不会新旧混在一起。
- **Material Design 3 风格 UI**：配色由品牌蓝经 HCT（CAM16 + L\*）色空间推导，组件按 MD3 规格实现（应用栏 56dp、按钮 40dp 胶囊、卡片 12dp 圆角、底栏 80dp）。
- **底部导航栏**：底栏分「搜索 / 下载 / 设置」三个板块，选中指示器在目标项中央展开；键盘弹起时底栏自动收起，不会顶到键盘上方。
- **后台下载**：整书下载在 `DownloadManager` 里进行，离开详情页或切到其它板块都不中断；并发上限 3 个任务。
- **失败自动重试**：首轮失败的章节会再跑最多 2 轮，避免个别章节网络抖动导致整本带缺口。
- **暂停 / 继续（断点续传）**：暂停时已抓到的正文保留在内存里，继续时只补缺失章节，不必从头再来；「重试失败章节」复用同一份会话，只重下失败的那几章（含上次失败的章节本身）。
- **下载页**：分「进行中」（实时进度条 + 每秒章节数 + 剩余时间 + 暂停/继续/取消）与「已下载」（分享文件 / 重试失败章节 / 重新保存 / 重新下载 / 删除记录）两组，记录持久化，重启应用仍在。
- **写文件失败可单独重试**：章节都抓好了、只是最后保存失败（文件重名、空间不足、媒体库冲突）时，卡片会多出「重新保存」——重新拼装再存一次，不必整本重下。同名文件会就地覆盖，不会再撞上 MediaStore 的 `UNIQUE constraint failed`。
- **按钮样式统一**：卡片内的操作按钮只有一份定义（`UiKit.actionButton`，36dp 高、全圆角、label-large），排布交给 `UiKit.ActionRow`——统一 8dp 间距、放不下自动换行，不会再出现两个按钮贴在一起或大小不一。
- **本地书库与阅读器**：书库双通道扫描（File + MediaStore）列出已下载的 TXT，可在应用内直接阅读——章节切分、目录跳转、字号 / 行距 / 四种底色、阅读进度记忆。
- **搜索页**：搜索栏与书源选择随结果列表同步滚动，小屏上也能直接翻到结果；离开顶部后右下角出现「回到顶部」悬浮按钮（位于底栏之上；顶栏同时出现搜索图标，一键回顶并聚焦输入框）。
- **设置页**：下载线程数、文字转换、导出格式、文件名模板、分章文件大小；书源导入 / 启用停用 / 连通性测试 / 导出备份 / 清空；本地书库与「关于」入口。
- **关于页**：应用信息、GitHub 开源地址与免责声明。

## 内置书源

| 书源 | 编码 | 搜索 | 实测（《斗破苍穹》） |
| --- | --- | --- | --- |
| 笔趣阁5200 `biquge5200.cc` | GBK | GET | 49 条 / 1676 章 |
| 笔趣阁666 `666biquge.com` | GBK | GET | 49 条 / 1676 章 |
| 笔趣阁b5200 `b5200.net` | GBK | GET | 49 条 / 1676 章 |
| 修文小说网 `xiunews.com` | GBK | GET | 49 条 / 1675 章 |
| 书海阁 `shuhaige.net` | UTF-8 | **POST + Cookie** | 20 条 / 1664 章 |
| 爱看书城 `m.2kk.la` | UTF-8 | GET | 100 条 |
| 零点看书 | GBK | GET | 50 条 |
| 爱下书网 `aixiashu.la` | UTF-8 | GET | 50 条 |
| 啃书小说 `kenshuzw.la` | GBK | GET | 50 条 |
| 书客吧 `shuke8.cc` | UTF-8 | GET | 5 条 / 150 章 |
| 独步小说 `dbxsn.com` | UTF-8 | GET | 3 条 / 78 章 |
| 番茄小说 | UTF-8 | GET（第三方接口） | 11 条 / 464 章 |

前 4 个是同属「笔趣阁 5200」镜像的站点，书库相同，价值在于**线路冗余**（某个域名被墙时可换另一条）。书源可在「设置 → 启用 / 停用已有书源」里随时开关。

## 项目结构

```
NovelDownloader/
├── AndroidManifest.xml              # 清单文件
├── build.sh                         # 无 Gradle 的手动构建脚本（aapt2 -> javac -> d8 -> apksigner）
├── build-local.sh                   # 本地/中文路径环境下的构建包装（UTF-8 环境变量 + 路径传入）
├── assets/
│   ├── builtin_sources.json         # 内置书源配置（12 个）
│   ├── fanqie_charset.json          # 番茄 PUA 字体解码表
│   ├── ts.txt                       # 繁→简单字映射（OpenCC）
│   └── st.txt                       # 简→繁单字映射（OpenCC）
├── res/                             # 资源（图标、字符串）
└── java/com/niganma/noveldown/
    ├── MainActivity.java            # 首页：底栏「搜索 / 下载 / 设置」
    ├── DetailActivity.java          # 详情：加载目录 + 选择章节范围后交给后台下载
    ├── AboutActivity.java           # 关于：应用信息 / GitHub 地址 / 免责声明
    ├── ImportSourcesActivity.java   # 导入书源：粘贴 JSON
    ├── LibraryActivity.java         # 本地书库：双通道扫描 + 删除
    ├── ReaderActivity.java          # 阅读器：章节切分 / 目录 / 字号行距底色 / 进度记忆
    ├── DownloadManager.java         # 后台下载管理器：任务状态机 + 进度通知 + 历史持久化
    ├── Exporter.java                # 三种导出格式（TXT / 分章 TXT / EPUB）+ 文件名模板
    ├── SourceTools.java             # 书源整包导出导入 + 连通性测试
    ├── DownloadPrefs.java           # 下载偏好（线程数 / 文字转换 / 格式 / 模板 / 章节范围 / 分章大小）
    ├── NovelApp.java                # Application：全局 Context + 预加载下载历史
    ├── BookSource.java              # 书源模型（正则规则格式）
    ├── SourceStore.java             # 书源仓库：内置 + 自定义 + 启停
    ├── Searcher.java                # 并发搜索（GET / POST、失败重试）
    ├── Downloader.java              # 目录加载 + 多线程抓取正文
    ├── Rules.java                   # 正则抽取 / 清洗 / 排版规范化引擎
    ├── CharConv.java                # 繁简转换（OpenCC 单字表）
    ├── FanqieCodec.java             # 番茄 PUA 字体解码
    ├── Http.java                    # 网络请求（gzip / 重定向 / POST / Cookie / 编码）
    ├── FileExport.java              # 早期版本的明文导出（保留以兼容旧代码路径）
    ├── UiUtil.java / UiKit.java     # MD3 设计系统（颜色 / 字体 / 形状 / 组件 / 底栏）
    ├── ResultAdapter.java           # 搜索结果列表（历史实现，当前首页用卡片内联渲染）
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

脚本流程：`aapt2 compile` -> `aapt2 link`（同时生成 `R.java`）-> `javac` -> `d8` -> 打包 `classes.dex` -> `zipalign` -> `apksigner`。
产物为 `NovelDownloader-<版本>.apk`（同时输出同名 `.zip` 以规避中文名传输导致的损坏）；版本号在 `build.sh` 顶部的 `VER_CODE` / `VER_NAME` 中维护。

### 特殊环境

- **中文 / 空格路径**：`javac` 在非 UTF-8 locale 下会报 `Malformed input`，用 `build-local.sh` 包装即可（它会设置 `LANG=C.UTF-8` 与 `JAVA_TOOL_OPTIONS`，并把项目路径传给 `build.sh`）。
- **aarch64 主机（Android 容器 / 树莓派等）**：官方 `aapt2`、`zipalign` 只有 x86_64 版本，可用 `qemu-x86_64-static` + `-L /opt/x86_64-root` 包装，真二进制放到 `build-tools/34.0.0/native/`。

签名说明：脚本在 `keystore.jks` 不存在时会自动生成一个测试用密钥库；该文件已在 `.gitignore` 中排除，**不会**随仓库公开。发布正式版本时应换成自己的签名密钥。注意：更换签名密钥后无法覆盖安装，需先卸载旧版本。

## 书源格式

书源为本项目自定义的简化规则格式，基于正则表达式抽取：

```json
{
  "name":         "示例书源",
  "charset":      "utf-8",
  "baseUrl":      "https://example.com",
  "searchUrl":    "https://example.com/search?q={{key}}&p={{page}}",
  "searchMethod": "post",
  "searchBody":   "searchkey={{key}}",
  "warmUp":       "https://example.com/",
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
| `searchMethod` | `get`（默认）或 `post` |
| `searchBody` | POST 搜索的表单体，支持 `{{key}}` / `{{page}}`，如 `searchkey={{key}}` |
| `warmUp` | 搜索前先 GET 一次这个地址（取会话 Cookie），通常是站点首页 |
| `listRule` | 列表规则，捕获组：1 = 书籍链接，2 = 书名，3 = 作者（可选） |
| `chapterList` | 目录规则，捕获组：1 = 章节链接，2 = 章节标题 |
| `contentRule` | 正文规则，第 1 组为正文 HTML |
| `replace` | 清洗规则数组，`[正则, 替换文本]` 依次执行（Java 里用 `$1` 反向引用） |
| `tocUrl` | 目录页模板，`{{book}}` 替换为书籍详情页 URL（详情页只列最新章节时使用） |
| `tocPages` | 目录分页规则，第 1 组为其余目录页 URL（可多个），逐页合并去重 |
| `contentPages` | 章内分页规则，第 1 组为「下一页」URL，逐页抓取合并同一章 |
| `indexChapters` | 索引式章节配置（见下），用于目录由 JS 渲染、页面无章节链接的站点 |
| `chapterIdRule` | 列表式目录：在详情页用正则取「章节ID + 标题」（组1=ID、组2=标题，可多项） |
| `chapterUrlTemplate` | 章节 URL 模板，`{{id}}` 替换为 `chapterIdRule` 取到的章节 ID |
| `bookUrlTemplate` | 搜索结果 URL 模板，`{{id}}` 替换为 `listRule` 第 1 组（接口只返回书籍ID 时使用） |
| `jsonEscape` | 正文是否为 JSON 转义字符串（需先反转义再清洗），默认 `false` |
| `paragraphIndent` | 导出时给每个非空段落添加的缩进前缀（如两个全角空格 `　　`） |
| `puaDecode` | 正文是否需要「番茄式」PUA 字体解码，默认 `false` |
| `puaMode` | PUA 解码模式（对应内置解码表下标），默认 `0` |

规则全部走 Java 的 `Pattern.compile(regex, DOTALL)`，所以 `.` 可以跨行、`<br\s*/?>` 这类写法都能用；`replace` 里至少有「`<br>` → 换行」「`<[^>]+>` → 空」「`&nbsp;` → 空格」三条才能得到干净正文。

若未配置 `chapterList`，则把搜索到的详情页当作单章处理。

### 书海阁（POST + Cookie 示例）

```json
{
  "name": "书海阁",
  "charset": "utf-8",
  "searchUrl": "https://www.shuhaige.net/search.html",
  "searchMethod": "post",
  "searchBody": "searchkey={{key}}",
  "warmUp": "https://www.shuhaige.net/",
  "listRule": "<dt><a href=\"(/[^\"]+)\">[\\s\\S]*?<dd><h3><a[^>]*>([^<]+)</a></h3></dd>[\\s\\S]*?作者：<span>([^<]+)</span>",
  "chapterList": "<dd><a href=\"(/\\d+/\\d+\\.html)\">([^<]+)</a></dd>",
  "contentRule": "<div[^>]+id=\"content\"[^>]*>([\\s\\S]*?)</div>",
  "contentPages": "<a[^>]+href=\"([^\"]+)\"[^>]*>\\s*下一页\\s*</a>"
}
```

该站搜索只收 POST，且必须先访问首页拿到 `waf_sc` 会话 Cookie，否则搜索页恒为空——`warmUp` 就是干这个的（Http 里有一个按主机记录的极简 Cookie 罐，不需要登录）。

### 番茄小说书源

内置的「番茄小说」书源使用如下组合（官方网页目录 + 第三方接口正文）：

```json
{
  "name": "番茄小说",
  "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
  "searchUrl": "http://101.35.133.34:5000/api/search?key={{key}}&offset=0",
  "listRule": "\"book_id\":\"(\\d+)\"[^{}]*?\"book_name\":\"([^\"]*)\"",
  "bookUrlTemplate": "https://fanqienovel.com/page/{{id}}",
  "chapterIdRule": "\"itemId\":\"(\\d+)\"[^{}]*?\"title\":\"([^\"]*)\"",
  "chapterUrlTemplate": "http://101.35.133.34:5000/api/raw_full?item_id={{id}}",
  "contentRule": "\"content\":\"((?:[^\"\\\\]|\\\\.)*)\"",
  "jsonEscape": true,
  "paragraphIndent": "　　",
  "replace": [["<header>[\\s\\S]*?</header>", ""], ["<footer>[\\s\\S]*?</footer>", ""], ["</p>", "\n"], ["<[^>]+>", ""]]
}
```

流程：搜索经第三方接口拿到 `book_id` → 用 `bookUrlTemplate` 打开官方书页 → `chapterIdRule` 取全部章节 → 每章 URL 指向第三方接口 `raw_full` → 反转义并清洗得到正文。

> 注：番茄正文在原文里就是「一句一段」（作者分段，段内多为 1 句），因此导出的 TXT 会呈现为每段一行；`paragraphIndent` 会给每段加上两个全角空格的段首缩进。

> 注：番茄书页对移动 UA 只返回「最后 50 章」且字段顺序不同，故本源用 `userAgent` 指定桌面 UA 以拿到完整目录；`chapterIdRule` 也写成与字段顺序无关。

> ⚠️ 说明：番茄官方 App 接口有签名校验（`X-Gorgon`/`X-Argus`），无法直接调用；官方网页对多数章节只给出约 300 字预览。故本内置书源借助第三方接口获取全文。该接口为**他人服务器（HTTP）**，可能随时失效或变更——失效时可在「书源管理」里把 `searchUrl`/`chapterUrlTemplate` 换成新的接口地址，或移除该书源。该接口响应偏慢（搜索可能 10s 以上），可在「设置 → 启用 / 停用已有书源」里单独关掉。

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

当前版本：**2.0**（versionCode 20）

- 最低系统版本：Android 7.0（API 24），目标 API 34
- 安装需在系统中允许「安装未知来源应用」
- 下载后可对照 Release 说明中的 SHA-256 校验文件完整性

也可以按上文「构建」章节自行编译。

## 更新日志

### 2.0

- **书源**：内置书源 7 → 12，新增 4 个「笔趣阁 5200」系镜像与**书海阁**；引擎支持 POST 搜索 + Cookie 预热（`searchMethod` / `searchBody` / `warmUp`），目录页倒序「最新章节」块自动去重，单源搜索失败自动重试一次。
- **下载**：新增「重新保存」（章节已下好、只是写文件失败时不必重下）、分章 TXT 每文件章数可调、分章导出前清理旧文件、文件名按字节截断；修复 MediaStore 同名文件的 `UNIQUE constraint failed`、暂停任务无法取消、异常导致的永久卡死、范围下载重试变成整本重下等问题。
- **界面**：全面改为 Material Design 3（HCT 推导的配色与 tonal palette）、底部导航栏（搜索 / 下载 / 设置）与展开式选中指示器、卡片按钮统一样式与自动换行、书源与下载设置页、回到顶部悬浮按钮、页面切换动画。
- **新增**：本地书库与应用内阅读器（章节切分 / 目录 / 字号行距底色 / 进度记忆）、书源连通性测试、EPUB 导出、文件名模板。

### 1.15

- 番茄小说书源：官方目录 + 第三方接口正文，支持 `paragraphIndent` 段首缩进。
- 链接打开支持批量解析与勾选。

## 免责声明

本项目仅用于 Android 开发与网络爬虫技术的学习研究。使用者需自行承担因使用本工具产生的一切后果，请遵守当地法律法规及各网站的服务条款。

## 第三方资源

- 繁简转换所用的单字映射表（`assets/ts.txt`、`assets/st.txt`）派生自 [OpenCC](https://github.com/BYVoid/OpenCC)（Apache-2.0 许可）。
- 界面图标（`res/drawable/ic_search.xml`、`ic_download.xml`、`ic_settings.xml`、`ic_arrow_up.xml`、
  `ic_link.xml`、`ic_open_in_new.xml`）取自
  [Google Material Design Icons](https://github.com/google/material-design-icons)（Apache-2.0 许可），
  已转换为 Android Vector Drawable，填充色由代码 tint 指定。
