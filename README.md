# 视频壳 VideoShell

一个**空壳视频客户端**：给它一个网址，它自动判断这个站是不是视频站、属于哪种 CMS/接口类型，然后**自动适配**成这个站的原生客户端 —— 可以搜索、分类浏览、选集、播放。

播放走**内置播放器（Media3 / ExoPlayer）**，不是网页播放；遇到需要嗅探的站，用 **WebView 嗅探**抓到真实 m3u8/mp4 地址后交给内置播放器。

## 功能

| 模块 | 说明 |
|---|---|
| 站点识别 | 输入网址 → 并发探测苹果CMS/海洋CMS 的 JSON、XML 采集接口（`/api.php/provide/vod/` 等 8 个候选路径）；都不通则启用通用 HTML 适配 |
| 自动适配 | 识别结果落成一份 `SiteConfig`（站点类型 / 接口地址 / 固定参数），下次直接可用；同一个站只需适配一次 |
| 分类浏览 | 分类项直接携带站点自己的真实 URL（不再靠猜模板），横向标签栏 + 网格列表 + 滚动翻页（按已见 id 去重，站点无分页时自动收尾）。分类识别覆盖四层：导航容器的模板式分类（`/vodshow/id/6.html` 等）→ **目录式分类**（WordPress 系的 `/meijutt`、`/riju`，且会扫遍顶部/二级/底部**所有**导航容器）→ 全文档兜底 → 「把 `/vod/{id}.html` 强行当分类」的安全网 |
| 列表卡片 | 详情链接支持 `/detail/{id}.html`、`/vod/{id}.html`，以及通用的 `/{单段}/{id}.html`（`/movie/`、`/film/`、`/watch/`…）；海报支持 `data-original` / `data-src` / `background-image` 等**懒加载**写法（占位 `blank.gif` 不会被当海报） |
| 详情页模板 | **从列表页学一条**：拿第一张真实卡片的链接反推 `{id}` 模板（`/movie/{id}.html`），比穷举模板可靠；学不到才回落到内置候选列表 |
| 搜索 | 走接口 `ac=detail&wd=`；HTML 站逐个试常见搜索模板（含 `/vodsearch/wd/{kw}.html`） |
| 选集 | 解析 `vod_play_from` / `vod_play_url`；HTML 站支持 `.tab-content > .tab-pane`、`.paly_list_btn` 等结构，线路名按 tab 的 `href="#playlistN"` 映射（不靠 DOM 顺序猜），播放页链接覆盖 `/play/`、`/v_play/`、`/watch/`、`vodplay/` 等写法，支持多线路（播放源）+ 剧集网格。**会区分「线路按钮」与「分集」**：厂长资源那类详情页的播放区每个 `<a>` 是一条线路（标签还都叫「线路1080P」），按分集理解会把电影显示成「2 集」，因此命中「标签是线路/清晰度词汇」或「多个锚点标签完全相同」时，改为**每条线路一组、各 1 集** |
| 播放解析 | 三级策略：① 本身就是 m3u8/mp4 → 直用；② 播放页 → 从 HTML 里抠 `player_aaaa` 的真实地址（不用开网页）；③ 抠不到 → 网页嗅探。抠取时会**完整还原 JSON 转义**（`\uXXXX` 等），中文路径（`…/第01集/index.m3u8`）不会再变成 `\u7b2c01\u96c6` 而 404 |
| HLS 兼容层 | 拦截 m3u8 做**规范化**再喂播放器：分片路径绝对化、`#EXT-X-TARGETDURATION` 按实际最大分片修正、剔除跨目录插播广告分片（`/adjump/` 等）及其 `#EXT-X-DISCONTINUITY`。解决"网页能播、壳里播不了"的扁平 TS 清单（见下文说明） |
| 网页嗅探 | WebView 钩住 XHR/fetch/video 事件 + `shouldInterceptRequest` 拦截媒体请求，按 HLS>DASH>MP4>FLV 打分排序，捕获到 HLS 自动跳转播放 |
| 内置播放器 | Media3 ExoPlayer，支持 HLS；自定义请求头（User-Agent / Referer） |
| 播放器 · 画面 | **默认横屏**，一键循环「横屏 → 竖屏 → 跟随系统」并记住选择；画面比例循环（自适应 / 16:9 / 4:3 / 铺满 / 裁剪铺满）；**锁屏**（防误触，左上角解锁） |
| 播放器 · 操作 | 播放/暂停、**上一集 / 下一集**、快退 / 快进 10 秒、倍速循环（1.0/1.25/1.5/2.0/0.75/0.5）、**长按屏幕临时 2.5x 加速**（松手复原）、静音、选集面板、自动下一集 |
| 播放器 · 手势 | 单击显隐控件、双击播放暂停、横向拖动快进退、左半屏上下调亮度、右半屏上下调音量 |
| 播放器 · 续播 | **进度记忆**：退出时记住位置，下次进同一集自动续播并提示 |
| 播放器 · 诊断 | 失败时弹出错误码 / HTTP 状态 / 根因 / 地址，并提供「重试」（更大重试次数）与「改用网页嗅探」两条出路，而不是只闪一个 toast。**直链因 CDN 404/超时等网络原因播不了时会自动降级到网页嗅探**（每集只自动降一次，防跳转循环） |
| 站点自检 | 站点页标题栏「自检」：把整条链路在当前网络下重跑一遍 —— 首页 → 分类 → 列表 → 详情 → 选集 → 播放地址 → **媒体请求** → **首个分片**，每步都带 **HTTP 状态码**，并附完整请求记录，可一键复制。定位"为什么这个站用不了"不用再靠猜。**最后一步「首个分片」是关键**：playlist 拿 200 不代表分片也能下（分片常被单独做防盗链或落在另一个 CDN），这正是"能解析却放不出来"的唯一盲区 |
| 网络留痕 | 每次 HTTP 的 URL / 状态码 / 耗时 / 异常都记进环形缓冲，失败原因直接拼进界面提示（不再把一切失败静默吞成空列表）；两个客户端共用一个内存 `CookieJar`，首屏拿到的 cookie 会带到后续请求（防盗链 / WAF 场景） |
| 直链播放 | 输入框粘 m3u8/mp4 直链，直接内置播放器播放 |

### 为什么需要 HLS 兼容层

一类"极速播放"源给的是**扁平 TS 清单**：几千个 1 秒分片、分片用相对路径，并且中间用
`#EXT-X-DISCONTINUITY` 插播几段跨目录的广告分片（形如 `/video/adjump/time/xxx.ts`），
同时 `#EXT-X-TARGETDURATION` 与真实分片时长对不上。

浏览器的 hls.js 对这些都是"能忍则忍"，ExoPlayer 更严格，于是出现**网页能播、壳子里播不了**。
`player/HlsFix.kt` 在数据源层面拦下 playlist 响应并规范化（分片绝对化 + TARGETDURATION 修正 +
剔除插播广告段），分片请求则原样透传、不缓冲。master playlist 与直播流会被识别并跳过处理。

### 为什么播放地址要做 JSON 转义还原

很多站的播放页把真实地址放在 `player_aaaa={...}` 里，那是**序列化过的 JSON**：
斜杠写成 `\/`，中文写成 `\uXXXX`。例如 `…/video/bianshuiwangshi/第01集/index.m3u8`
在源码里是 `…\/video\/bianshuiwangshi\/\u7b2c01\u96c6\/index.m3u8`。

只还原 `\/` 而不管 `\uXXXX`，就会把 `\u7b2c01\u96c6` 原样丢给播放器 —— CDN 自然回 404。
`Media.unescape()` 现在按 JSON 字符串规则完整还原（`\uXXXX` 含代理对，以及 `\n`/`\t`/`\"` 等），
于是「同一个站有些集能播、有些集 404」这类问题会消失（能不能播取决于该集路径里有没有中文）。

### 为什么需要「站点自检」

同一份代码在不同网络下表现可以完全不同（DNS 污染、运营商劫持、WAF 挑战、CDN 分地域拒绝、
IPv6 不可达…）。而当所有失败都被 `getOrNull` 吞成 `null` 时，界面上**只剩一个"空"**，
用户和开发者都无法判断断在哪一环 —— 是首页就没拿到，还是拿到了但解析不出来？是 403 还是超时？

所以站点页提供了「自检」：把链路逐环跑一遍，每环都打印 **HTTP 状态码 + 耗时**，
最后附上完整请求记录。看到 `[2] 分类解析 → 结果：0 个 / 原因：首页请求失败：HTTP 403`
和看到 `[6] 媒体请求 → HTTP 404` 完全是两回事，处理方向也完全不同。

顺带修掉的两个隐患：

- `AdapterFactory` 原本写作 `when (site.apiMode) { … else -> MaccmsAdapter }`。一旦 `apiMode`
  不是预期值就会掉进 maccms 分支；而该分支依赖 `apiUrl` 拼采集接口 —— `apiUrl` 为空时**每一步都失败**，
  表现为分类/列表/详情/选集全空且毫无线索。现在 `apiUrl` 为空一律走 HTML 适配。
  （`apiMode` 变 null 是真实可能的：Gson 用 Unsafe 分配对象、不走构造函数，Kotlin 的字段默认值不会生效。）
- 分类抓取多了多地址重试（`http`/`https`、`www`/裸域各试一遍），异常文本会写进提示条。


## 技术栈

- Kotlin 1.9.22 / AGP 8.1.4 / Gradle 8.5 / JDK 17
- compileSdk 34，minSdk 21，targetSdk 34
- Media3 (ExoPlayer + HLS + UI) 1.2.1
- OkHttp 4.11 / Jsoup 1.17 / Gson / Coil
- ViewBinding，无第三方 UI 框架

## 工程结构

```
app/src/main/java/com/videoshell/
├── App.kt
├── data/
│   ├── Store.kt                     已适配站点的本地存储
│   ├── model/Models.kt              VideoItem / Episode / PlayGroup / SiteConfig / MediaSource
│   ├── net/Http.kt                  OkHttp 封装（含 GBK/GB2312 自动解码、探测用短超时客户端）
│   └── site/
│       ├── SiteDetector.kt          ★ 站点识别：接口探测 → HTML 兜底
│       ├── AdapterFactory.kt        按 apiMode 造适配器
│       ├── SiteAdapter.kt           适配器基类 + 播放地址三级解析
│       ├── MaccmsAdapter.kt         苹果CMS/海洋CMS JSON 接口
│       ├── MaccmsXmlAdapter.kt      苹果CMS/海洋CMS XML 接口
│       ├── MaccmsKit.kt             URL 拼装 / 剧集串解析 / 分类层级
│       ├── HtmlAdapter.kt           通用 HTML 适配（运行时试模板，命中即记住）
│       ├── HtmlTemplates.kt         URL 模板候选 + 链接 ID 提取
│       ├── HtmlExtractor.kt         列表 / 播放列表抽取
│       └── Media.kt                 媒体地址识别 + 播放页地址抠取
├── player/
│   ├── PlayerActivity.kt            内置播放器（横竖屏 / 锁屏 / 比例 / 上下集 / 续播 / 失败诊断）
│   ├── PlayerGestureLayout.kt       手势层（含长按加速与锁定）
│   ├── HlsFix.kt                    ★ HLS playlist 规范化 + 数据源包装
│   ├── SniffActivity.kt             网页嗅探
│   └── PlayQueue.kt                 播放队列
└── ui/
    ├── MainActivity.kt              输入网址 / 识别 / 站点列表
    ├── SiteActivity.kt              分类 + 搜索
    ├── DetailActivity.kt            详情 + 选集
    └── adapter/                     4 个 RecyclerView 适配器
```

## 构建

本地（Windows，需 JDK 17 + Android SDK 34）：

```bash
./gradlew :app:assembleDebug
# 产物 app/build/outputs/apk/debug/app-debug.apk
```

CI（GitHub Actions）：推 `main` 自动编译 debug + release 并上传 artifact；推 `v*` tag 会自动创建 Release 并附上 release APK。

> 本机 `git push` 走不通（直连 TLS reset / 代理不可用），所以推送与打 tag 都走 GitHub REST：

```bash
python _push_rest.py            # 推 main（服务端 sha 与本地逐位一致才算成功）
python _tag.py v1.0.1          # 建 refs/tags/v1.0.1，触发 CI 建 Release
python _ci_wait.py v1.0.1      # 轮询到 Release 挂上非空 APK
```

## 离线校验（HTML 适配 + HLS 规范化回归）

HTML 适配是「按主题猜 DOM」，HLS 规范化又直接决定播不播得出来，两者改动都容易波及其它站。
`D:\TRAE\releases\.tools\videoshell_verify\` 里有两套离线校验：把抓下来的**真实页面/真实 playlist** 当输入，
直接调用**刚编译出的 Kotlin 类**跑分类 / 列表 / 选集 / 播放地址抽取 / playlist 规范化，无需真机与网络。

```bash
cd D:/TRAE/视频壳 && python _build.py :app:assembleDebug
cd D:/TRAE/releases/.tools/videoshell_verify && python verify.py D:/TRAE/视频壳
# 期望末行：ALL CHECKS PASSED
```

HLS 部分的断言在 `_verify2.java`（分片绝对化 / TARGETDURATION 合规 / 广告分片剔除 / master 与直播流不受影响）。

> 调试 HLS 时注意：该 CDN 对 `HEAD` 一律返回 200，**必须用 `GET`（可带 `Range: bytes=0-0`）** 才能得到真实状态码。

## 签名

`keystore/videoshell.jks` 与 `keystore.properties` 随仓库提交（私有库），所以 CI 与本地都能直接出**已签名**的 release APK。

> 口令：`videoshell2026`，alias：`videoshell`。若仓库将来转为公开，请务必先换掉这对密钥。

## 使用

1. 打开 App，输入视频站网址（如 `https://www.xxxx.com`），点「识别并适配」。
2. 识别成功会自动保存并进入站点页 → 分类浏览 / 搜索 → 点影片进详情 → 选集播放。
3. 识别失败时点「用网页嗅探打开」：在网页里点一下播放按钮，App 抓到 m3u8 后自动跳内置播放器。
4. 手上有 m3u8/mp4 直链时，直接粘进输入框，点「直接播放该直链」。

## 已知边界

- **只做适配，不提供任何内容源**。站点可用性、内容合法性由使用者自行判断。
- HTML 通用适配是**尽力而为**（按 maccms 系常见模板猜），不如标准接口稳；这类站优先用嗅探播放。
- 需要 JS 解密 / 对接专门解析接口（jx）的站不在当前支持范围。
- 嗅探依赖页面真实发出媒体请求：若页面需要点击、或走加密接口，需在嗅探页手动操作一下。
