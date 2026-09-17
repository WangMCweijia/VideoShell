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
| 播放解析 | 三级策略：① 本身就是 m3u8/mp4 → 直用；② 播放页 → 从 HTML 里抠 `player_aaaa` 的真实地址（不用开网页）；③ 抠不到 → 网页嗅探。抠取时会**完整还原 JSON 转义**（`\uXXXX` 等），中文路径（`…/第01集/index.m3u8`）不会再变成 `\u7b2c01\u96c6` 而 404；抠到的地址还会**剥掉代理播放器外壳**（见下文「嵌套地址」） |
| HLS 兼容层 | 拦截 m3u8 做**规范化**再喂播放器：分片路径绝对化、`#EXT-X-TARGETDURATION` 按实际最大分片修正、剔除跨目录插播广告分片（`/adjump/` 等）及其 `#EXT-X-DISCONTINUITY`。解决"网页能播、壳里播不了"的扁平 TS 清单（见下文说明） |
| 网页嗅探 | WebView 钩住 XHR/fetch/video 事件 + `shouldInterceptRequest` 拦截媒体请求。**候选不再靠文件名猜**：先按 URL 段级剔除广告/埋点，再把候选的 playlist 真的拉下来看内容 —— 正片几百上千个分片（几十分钟），广告十几个（几十秒），据此判正片/疑似广告；同目录下被大量 `.ts` 请求打中的清单额外加分。**只有"首选明确是正片"或"只有一个候选"才自动播**，判不出来就停在列表让你点（列表里直接写着时长/分片数与判定）。候选清单会交给播放器，**播不出来自动换下一个源**。主文档加载失败、长时间抓不到、或页面要求登录，状态栏都会直接说明 |
| 内置播放器 | Media3 ExoPlayer，支持 HLS；自定义请求头（User-Agent / Referer）。**数据源用 OkHttp（`OkHttpDataSource`），不用 ExoPlayer 自带的 `DefaultHttpDataSource`** —— 与自检/解析/嗅探同一条网络栈，见下文 |
| 播放器 · 画面 | **默认横屏**，一键循环「横屏 → 竖屏 → 跟随系统」并记住选择；画面比例循环（自适应 / 16:9 / 4:3 / 铺满 / 裁剪铺满）；**锁屏**（防误触，左上角解锁） |
| 播放器 · 操作 | 播放/暂停、**上一集 / 下一集**、快退 / 快进 10 秒、倍速循环（1.0/1.25/1.5/2.0/0.75/0.5）、**长按屏幕临时 2.5x 加速**（松手复原）、静音、选集面板、自动下一集 |
| 播放器 · 手势 | 单击显隐控件、双击播放暂停、横向拖动快进退、左半屏上下调亮度、右半屏上下调音量 |
| 播放器 · 续播 | **进度记忆**：退出时记住位置，下次进同一集自动续播并提示 |
| 播放器 · 诊断 | 失败时弹出错误码 / HTTP 状态 / 根因 / 地址，并提供「复制诊断」「重试」（更大重试次数）与「改用网页嗅探」几条出路，而不是只闪一个 toast。**直链因 CDN 404/超时等网络原因播不了时会自动降级到网页嗅探**（每集只自动降一次，防跳转循环）。**来自嗅探的播放失败会先自动换下一个候选源**，标题栏显示「源 n/N」，另有「换下一个源」「返回候选列表」两个按钮 |
| 播放记录 | 播放器的每次动作（开始播放 / 解析出的地址 / 每个媒体请求的状态码与耗时 / 失败的错误码与根因）都会写进一条**持久化记录**，并随「站点自检」报告一起输出。这是把"播放器自己说的那句话"带回开发侧的通道 —— 错误面板只能截图，又长又碎 |
| 站点自检 | 站点页标题栏「自检」：把整条链路在当前网络下重跑一遍 —— 首页 → 分类 → 列表 → 详情 → 选集 → 播放地址 → **媒体请求** → **首个分片（真下 512KB 测速）** → **请求栈对照** → **播放器栈实测**，每步都带 **HTTP 状态码**，并附完整请求记录 + 播放记录，可一键复制。**报告头带 App 版本号**，免得"装的到底是哪个包"来回扯。**第 7 步会真下一段分片并算 KB/s**（旧版只取 1KB，任何链路都秒回，证明不了扛得住播放）；**第 9 步直接用播放器那套 DataSource 开一次 playlist 与分片**，两边同栈之后"自检绿、播放挂"再也不可能分叉 |
| 网络抗抖动 | **每个 URL 最多自动重试 3 次**（退避 400ms / 1200ms；4xx 不重试，5xx 与 429 重试），连接超时收到 8s 以便快速失败再换一次；DNS **优先 IPv4**（有些网络里 IPv6 能解析却连不通，会让"每个新域名的第一次请求"一直卡到超时）。手机网络下"首次请求抖一下就整条分类栏消失"由此消除 |
| 网络留痕 | 每次 HTTP 的 URL / 状态码 / 耗时 / 异常都记进环形缓冲，失败原因直接拼进界面提示（不再把一切失败静默吞成空列表）；两个客户端共用一个内存 `CookieJar`，首屏拿到的 cookie 会带到后续请求（防盗链 / WAF 场景） |
| 播放地址编码 | 交给播放器前把非 ASCII（中文等）路径**百分号编码**成纯 ASCII。**这是「自检全绿、播放全挂」的根因**：自检走 OkHttp（会自动编码），而 ExoPlayer 的 `DefaultHttpDataSource` 底层 `HttpURLConnection` **不会** —— 同一地址，前者 200、后者 404 |
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

### 为什么还要给播放地址做百分号编码

上面的 JSON 还原把 `\u7b2c01\u96c6` 还原成了真正的中文 `第01集` —— 但问题只解决了一半，
因为这个地址会经过**两套完全不同的请求栈**：

| 谁 | 底层 | 对非 ASCII 路径的处理 | 结果 |
|---|---|---|---|
| 自检 / 抓页面 | OkHttp | **自动百分号编码** | CDN 200 |
| ExoPlayer 播放 | `DefaultHttpDataSource` → `HttpURLConnection` | **不编码**，把原始字节塞进请求行 | CDN 404 |

本机回环抓到的原始请求行——两套栈发出的根本不是同一个请求：

```
OkHttp            → GET /video/%E7%AC%AC01%E9%9B%86/index.m3u8 HTTP/1.1
HttpURLConnection → GET /video/ç¬¬01é/index.m3u8             HTTP/1.1
```

这就是「自检全绿、播放全挂」唯一说得通的解释。它也说明此前那次「排除请求栈差异」的实验
为什么给出错误结论：那个对照跑在 **JDK** 的 `HttpURLConnection` 上，而 JDK 与 Android 不是一回事。

`Media.encodeUrl()` 在地址交给播放器之前把非 ASCII 统一编码为 `%XX`（已存在的 `%XX` 原样保留，
避免二次编码），两套栈发出的字节从此一致。修复同时挂在 `SiteAdapter.resolve()`（唯一出口）
与 `PlayerActivity.playUrl()`（兜底入口）。

### 为什么播放器也改用 OkHttp 数据源

把「播放地址编码」修好之后，`茶杯狐` 依然播不出来，而自检报告显示：

```
[5] 直链：https://fengbao12.com/video/…/index.m3u8     （纯 ASCII，不含中文）
[6] 媒体请求 → HTTP 206
[7] 首个分片 → HTTP 206
[8] 本地址是纯 ASCII，两套栈发出的字节一致 —— 播放失败不是这个原因
```

网络层全绿、地址合法、分片能下，可播放器就是打不开。进一步把 playlist 拉下来在本机跑
`HlsPlaylistFixer`：2572 个分片、剔除 27 个 `/adjump/` 广告后 2545 个，结构校验 12/12 通过；分片本身
也是合法 TS（同步字节 2678/2679、PAT/PMT 齐全、无加密）。**内容侧全部排除。**

剩下的唯一变量就是**播放器用的那条网络栈**：自检/解析/嗅探走 OkHttp（IPv4 优先解析、内存 CookieJar、
三类重试、统一超时），而 ExoPlayer 的 `DefaultHttpDataSource` 底层是 `HttpURLConnection` ——
**两套栈，没有任何一处是共享的**。想靠"自检绿"推断"播放也能成"，前提是两条栈行为一致，而这个前提
从来没被验证过，也不成立。

所以 v1.0.9 起播放器改用 `OkHttpDataSource`（自己实现的 Media3 `HttpDataSource`，底层就是 `Http.mediaClient`）：

- 与自检**同一条栈**，差异不再是"藏在实现里"的东西；
- 白捡 IPv4 优先解析、CookieJar、`retryOnConnectionFailure`；
- 关掉 `callTimeout`（它管整场播放的总时长，会把正常下载掐死），读超时 20s；
- 播放器发出的每个请求都写进 `NetLog`（标注 `[播放器]`），看得见。

配合自检新增的 **第 9 步「播放器栈实测」**（用同一个 `OkHttpDataSource` 真开一次 playlist 与分片）：
报告里这两行若是 200/206，播放器的网络层就是通的，问题必在解码/格式，不用再猜地址与链路。

### 为什么地址要「取最内层」

不少站的播放页不直接写 m3u8，而是套一层**代理播放器**，把真地址当参数塞进去：

```html
<iframe src="https://plaa.py1080p.com:8181/player/py.php?code=cs&if=1&url=https://m3hlsm3.py1080p.com:907/hls3/hls/峡谷.m3u8">
```

旧的正则 `https?://[^"'<>\s]+?\.(?:m3u8|mp4…)` 从**第一个** `https://` 开始匹配，而这一整串里
没有空白和引号，于是它一路吃到结尾的 `.m3u8`，把**外层代理页**当成了媒体地址。后果很隐蔽：

- `isHls()` 因为串里有 "m3u8" 返回 true，于是按 HLS 去播；
- 播放器请求 `py.php?...`，拿回来的是 `text/html`（3495 字节），解析失败 → "播放失败"；
- 而自检 [5] 打印出来的地址看着"完全正常"，于是前面几轮一直在错误的方向上找原因
  （甚至得出过"厂长资源必须登录"的结论 —— 实际上它的播放页里 m3u8 就明明白白写着，**不用登录**）。

`Media.innermost()` 现在会剥壳：遇到 `?url=` / `&url=`（含 URL 编码形式 `%3A%2F%2F`）就递归取最内层，
且只在内层**仍然像媒体地址**时才采用，避免误伤正常 URL。厂长资源由此从"只能嗅探"变成**直链可播**。

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
│   ├── OkHttpMediaSource.kt         ★ 用 OkHttp 实现的 Media3 HttpDataSource（播放器与自检同栈）
│   ├── HlsFix.kt                    ★ HLS playlist 规范化 + 数据源包装
│   ├── PlayLog.kt                   播放记录（持久化，随自检报告输出）
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
cd D:/TRAE/releases/.tools/videoshell_verify && python runall.py
# 期望末行：==== 合计 PASS=205  FAIL=0 ====
```

各套件：`runverify3`（HTML 适配 54 条）、`runlive2`（真实 suspend 链路 + SiteDoctor 20 条）、
`runlinefix`（线路/分集判定）、`runretry`（网络重试 17 条）、`runenc`（`encodeUrl` 14 条）、
`runrank`（嗅探排序 43 条）、`runhdfix` / `runhdfix2`（HLS 规范化 11 + 12 条）、
`runextract2`（**取最内层地址，19 条**：合成用例 + 真实播放页）、
`runbs`（**金牌影视 15 条**：分类判据 / 线路名 / 详情与播放页选集）。

`_cp.py` 是公共 classpath 构造器：因为 `SiteDoctor` 现在会调用播放器那套 DataSource，
任何跑自检的 harness 都需要 media3 / androidx / guava / `android.jar` 在 classpath 上。

> 调试 HLS 时注意：该 CDN 对 `HEAD` 一律返回 200，**必须用 `GET`（可带 `Range: bytes=0-0`）** 才能得到真实状态码。
> 另外：**1KB 的 `Range` 探活不能代表"能播"** —— 它只证明地址存在，必须真下一段（几百 KB）才能看出链路够不够快。

## 通配性：为什么有的站一粘就通、有的站要改代码

先把"视频站"分成三类，**这个分类直接决定上限**：

| 类型 | 特征 | 现在能不能自动适配 |
|---|---|---|
| A. 开放采集接口的 maccms | `/api.php/provide/vod/` 有响应 | 能，全自动（走 `MaccmsAdapter`） |
| B. maccms 系但关了接口 / 改了 URL | 8 路探测全 `closed`，目录名自定义 | 能，走 HTML 适配（**通配性的主战场**） |
| C. JS 解密 / 专用解析接口（jx） / 登录墙 | 地址运行时算出来 | 不能，原理上做不到 |

`SiteDetector` 会先并发探 8 个采集接口路径；全不通就落到 HTML 适配。**这一步不要怀疑它判错** ——
探测失败的站点本来就应该走 HTML 适配。

### 三条原则（都是从踩坑里换来的）

**1. 认形状，不认目录名。**

同一个 maccms，后台可以把 URL 目录名全改掉。金牌影视把四个目录都换了：

| 语义 | maccms 默认 | 金牌影视 |
|---|---|---|
| 分类 | `/vodtype/{id}.html` | `/bspvt/{别名}.html` |
| 详情 | `/voddetail/{id}.html` | `/bspvd/{id}.html` |
| 列表 | `/vodshow/{id}--------{页}---.html` | `/bspvs/{别名}-----------.html` |
| 播放 | `/vodplay/{id}-{sid}-{nid}.html` | `/bspvp/{id}-{sid}-{nid}.html` |

所以判据要写成**形状**：`/{任意目录}/{id}-{sid}-{nid}.html` 就是播放页 ——
列举 `play` / `v_play` / `vodplay` / `watch` 永远举不全。

**2. 用形状本身消歧，不靠白名单。**

- 分类 = `/{目录}/{**字母开头**的别名}.html`
- 详情 = `/{目录}/{**数字开头的 id**}.html`

两者天然可分（见 `HtmlTemplates.isSlugDirCategory`），不需要给每个新站补一条规则。
同理：卡片**必须自带图片**，就是"导航链接 vs 影片卡片"的消歧手段。

**3. 能从页面上学的就别硬编码。**

`HtmlExtractor.detailTplHint()` 会从列表页第一张真实卡片反推详情模板并记住
（`/movie/23804.html` → `/movie/{id}.html`）。新增站点时优先补"怎么学"，而不是补"猜哪个模板"。

### 加一个新站的最短路径

1. 用 App 同款 OkHttp 抓真实页面（**不要用 Python urllib** —— 雷池 WAF 那类对请求姿态敏感，
   urllib 403 / OkHttp 200，结论会跑偏）：

   ```bash
   cd D:/TRAE/releases/.tools/videoshell_verify
   python runnet.py D:/TRAE/视频壳/_bs "https://站点/#home" "https://站点/分类页#cat" "https://站点/详情页#detail"
   python rundetect.py https://站点          # 确认 8 路采集接口到底通不通
   ```

2. 复制 `Bs.java` 改成新站的断言（对照它的真实 URL 形态），跑 `python runbs.py`。
   **先让它失败** —— 失败的地方就是断点，别凭代码猜。
3. 改判据 → 编译 → 复跑，直到 `ALL CHECKS PASSED`，再跑 `runall.py` 确认没有回归。

### 学到的模板要落盘：站点配方（SiteRecipe）

**「能学到」还不够，得存下来。**

App 里每个页面都是独立 Activity，**各自 new 一个 Adapter**：

| 页面 | Adapter 实例 | 手上有什么 |
|---|---|---|
| 站点页 `SiteActivity` | 第 1 个 | 边浏览边学（分类、详情模板…） |
| 详情页 `DetailActivity` | 第 2 个（全新） | **什么都没有** ← 断在这 |

于是症状就是「能出分类、能出列表，一点进详情就失败」。
详情页那个实例只能穷举 `/voddetail/`、`/detail/`、`/movie/`…，
而金牌影视用 `/bspvd/{id}.html` —— 穷举里根本没有，必然 404。

`SiteRecipe` + `RecipeStore`（`data/site/SiteRecipe.kt`）把学到的模板写进 SharedPreferences
（key = `r_<host>`），任何 Activity 新建 Adapter 时自动载入：

| 字段 | 学自 | 作用 |
|---|---|---|
| `detailTpl` | 列表页卡片链接 / 详情页命中 | 详情页直取，**1 次请求命中** |
| `playTpl` | 详情页里的播放链接 | 分集只在播放页时的兜底 |
| `listTpl` / `searchTpl` | 翻页 / 搜索命中 | 分页与搜索 |
| `vodIsCategory` | 首页结构 | 判断 `/vod/{id}.html` 是分类还是详情 |

配套两条自愈机制：

1. **回首页现学**：手上没模板时，`detail()` 先抓一次首页学一条再试。
   盲试 8 个必然 404 的候选要 8 次请求，学一条只要 1 次。
2. **手动重学**：自检报告上的「重学本站」= 清掉配方 + 丢掉旧 Adapter，
   下一次解析等同首次访问（站点改版、或某次学歪了时用）。

> **固化的是「怎么找」，不是「找到的直链」。**
> m3u8 直链多带时效签名，几小时后就失效，存下来只会得到死链；
> 能长期复用的永远是**规则**（模板 / 选择器 / 形状判据）。
>
> 分类列表**刻意不固化**：它就 1 次首页请求，而且每次进站点页都该看到最新导航 ——
> 缓存它省不了什么，却会在站点改版后给出过期分类。

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
- HTML 通用适配靠 **URL 形状 + DOM 语义**推断（而不是穷举模板），对 maccms 系
  （含把目录名全改过的那批）覆盖较好；对**完全自研前端**（Vue/React 运行时渲染、接口加密）的站
  仍然要看具体情况，这类站优先用嗅探播放。见上面「通配性」一节。
- 需要 JS 解密 / 对接专门解析接口（jx）的站不在当前支持范围。
- 嗅探依赖页面真实发出媒体请求：若页面需要点击、或走加密接口，需在嗅探页手动操作一下。
- **确实要求登录的站播不了**，这不是适配问题：页面正文就是「登录后即可观看」、
  整页**没有任何媒体地址**，嗅探再怎么等也抓不到东西。这类站会在嗅探状态栏明确提示
  "播放页要求登录"，而不是让人干等。
  （注：`czzy.app`／厂长资源**不属于**这一类 —— 它播放页里的 m3u8 是公开可见的，
  之前播不了是因为抠地址时把外层代理页当成了媒体地址，v1.0.9 已修。）
- 嗅探的"正片/广告"判定基于 **playlist 内容长度**（分片数、总时长），
  对"广告也很长"或"正片极短（短视频、花絮站）"的站会判不准；此时列表会保持展开让你手选，
  选错了也能在播放器里一键换源。
- 自检第 9 步（播放器栈实测）能证明**网络层**是通的，但证明不了**解码**没问题：
  若某种编码设备硬解不支持，仍会播不出来，这种情况请把播放器错误面板的「复制诊断」内容发回来。
