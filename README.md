# 视频壳 VideoShell

一个**空壳视频客户端**：给它一个网址，它自动判断这个站是不是视频站、属于哪种 CMS/接口类型，然后**自动适配**成这个站的原生客户端 —— 可以搜索、分类浏览、选集、播放。

播放走**内置播放器（Media3 / ExoPlayer）**，不是网页播放；遇到需要嗅探的站，用 **WebView 嗅探**抓到真实 m3u8/mp4 地址后交给内置播放器。

## 功能

| 模块 | 说明 |
|---|---|
| 站点识别 | 输入网址 → 并发探测苹果CMS/海洋CMS 的 JSON、XML 采集接口（`/api.php/provide/vod/` 等 8 个候选路径）；都不通则启用通用 HTML 适配 |
| 自动适配 | 识别结果落成一份 `SiteConfig`（站点类型 / 接口地址 / 固定参数），下次直接可用；同一个站只需适配一次 |
| 分类浏览 | 分类项直接携带站点自己的真实 URL（不再靠猜模板），横向标签栏 + 网格列表 + 滚动翻页（按已见 id 去重，站点无分页时自动收尾） |
| 搜索 | 走接口 `ac=detail&wd=`；HTML 站逐个试常见搜索模板（含 `/vodsearch/wd/{kw}.html`） |
| 选集 | 解析 `vod_play_from` / `vod_play_url`；HTML 站支持 `.tab-content > .tab-pane` 等结构，线路名按 tab 的 `href="#playlistN"` 映射（不靠 DOM 顺序猜），支持多线路（播放源）+ 剧集网格 |
| 播放解析 | 三级策略：① 本身就是 m3u8/mp4 → 直用；② 播放页 → 从 HTML 里抠 `player_aaaa` 的真实地址（不用开网页）；③ 抠不到 → 网页嗅探 |
| 网页嗅探 | WebView 钩住 XHR/fetch/video 事件 + `shouldInterceptRequest` 拦截媒体请求，按 HLS>DASH>MP4>FLV 打分排序，捕获到 HLS 自动跳转播放 |
| 内置播放器 | Media3 ExoPlayer，支持 HLS；自定义请求头（User-Agent / Referer）；手势：单击显隐控件、双击播放暂停、横向拖动快进退、左半屏调亮度、右半屏调音量；倍速、选集面板、自动下一集、吸附式横竖屏切换 |
| 直链播放 | 输入框粘 m3u8/mp4 直链，直接内置播放器播放 |

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
│   ├── PlayerActivity.kt            内置播放器
│   ├── PlayerGestureLayout.kt       手势层
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

```bash
git tag v1.0.1 && git push origin v1.0.1
```

## 离线校验（HTML 适配回归）

HTML 适配是「按主题猜 DOM」，改动容易踩到别的站。`D:\TRAE\releases\.tools\videoshell_verify\` 里有一套
离线校验：把抓下来的真实页面当输入，直接调用**刚编译出的 Kotlin 类**跑分类 / 列表 / 选集 / 播放地址抽取，
无需真机与网络。改了 `Html*` 之后先跑它。

```bash
cd D:/TRAE/视频壳 && python _build.py :app:assembleDebug
cd D:/TRAE/releases/.tools/videoshell_verify && python verify.py D:/TRAE/视频壳
# 期望末行：ALL CHECKS PASSED
```

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
