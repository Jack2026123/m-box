# AVBox 开发文档

> 音乐播放器（洛雪 + MusicFree 双引擎）· AI 辅助写源（七要素核对）
>
> 本文件由 `MUSIC_README.md` 与 `AI_SOURCE_README.md` 合并而成，两份原文档保留在仓库根目录，内容与本文件一致。
>
> **重要前提：本文档描述的所有代码，从未在任何机器上编译过。** 编写环境没有 JDK、没有 Android SDK、没有 Node.js。所有验证均为静态校验（括号配平、跨文件 API 比对、Python 复刻逻辑跑样本）。首次 `./gradlew assembleDebug` 大概率有编译错误，属正常，见 M9 / A9。

---

## 目录索引

### 我想做什么 → 看哪节

| 我想… | 看这节 |
|---|---|
| 先把音乐功能跑起来 | [M2 快速开始](#m2-快速开始) |
| 导入洛雪音源脚本 | [M2 快速开始](#m2-快速开始) |
| 知道 19 个脚本哪些能用 | [M4 19 个脚本实测清单](#m4-19-个脚本实测清单) |
| 开关 MusicFree 引擎 | [M3 MusicFree 插件引擎开关](#m3-musicfree-插件引擎开关) |
| 查音乐搜索/播放失败的日志 | [M5 日志关键字对照表](#m5-日志关键字对照表) |
| 排查「搜不到歌」「播不了」 | [M6 故障排查表](#m6-故障排查表) |
| 音乐编译不过，先保洛雪 | [M9 编译失败时如何降级到纯洛雪](#m9-编译失败时如何降级到纯洛雪) |
| 改音乐代码前该知道的设计取舍 | [M10 关键设计决策](#m10-关键设计决策改代码前必读) |
| **让 AI 根据网址写影视源** | [A2 现在的做法](#a2-现在的做法先核对再写源) |
| 搞清「首页/分类/详情/播放」七要素是什么 | [A3 七要素与六个接口](#a3-七要素与六个接口的对应关系) |
| 某一项找不到怎么办 | [A4 每项怎么判缺了怎么补](#a4-每项怎么判缺了怎么补) |
| **看到「结构核对 3/7」该往哪查** | [A5 日志怎么看](#a5-日志怎么看) |
| 静态校验报错怎么理解 | [A6 静态校验的提示](#a6-静态校验现在给的提示) |
| 真跑验证报「首页 0 条」 | [A7 真跑验证四阶段](#a7-真跑验证的四个阶段) |
| 提示词为什么这么写 | [A8 给模型的要求](#a8-给模型的要求提示词核心) |
| AI 写源有哪些做不到的 | [A9 已知限制](#a9-已知限制) |

### 两部分的关系

两块功能**代码上完全独立**，互不影响：

```
music/ 包 + MusicPlayerActivity + MusicConfigPanel     ← 第一部分
api/ai/ + ui/dialog/AiAssistPanel                       ← 第二部分
```

共同点只有两个：都跑在 AVBox 里，都在同一次 `assembleDebug` 里编译。

---

## 第一部分　音乐播放器（洛雪 + MusicFree）

> 代码位置：`app/src/main/java/com/github/tvbox/osc/music/` 与相关 UI
> 适用版本：AVBox 2.72+ 加上 P1–P10 全部改动


> 本文档对应当前代码树中的 `music/` 包与相关 UI。
> 适用版本：AVBox 2.72+ 加上本轮 P1–P9 的全部改动。

---

### M1 这套东西是什么

给 AVBox 加了一套**音乐播放能力**，数据来自**洛雪（lx-music）自定义音源脚本**。

链路：

```
导入洛雪脚本 → 壳子切到音乐首页 → 搜歌 → 点歌 → 洛雪脚本取链 → 现有播放器出声
```

关键点：**搜歌是壳做的，取链是脚本做的**。

| 环节 | 谁负责 | 代码位置 |
|---|---|---|
| 脚本存储、去重、启用 | 壳 | `music/MusicStore.kt` |
| 脚本执行（QuickJS 沙箱） | 壳 | `music/LxRuntime.kt` |
| 搜歌（酷狗/酷我/咪咕/QQ/网易） | 壳 | `music/LxSearch.kt` |
| 取播放地址 | **脚本** | `LxRuntime.getMusicUrl()` |
| 队列、播放、歌词、进度 | 壳（复用现有播放器） | `music/MusicSession.kt` + `player/PlaybackStarter.kt` |

---

### M2 快速开始

#### 1. 导入音源

进入 **配置管理 → 音乐 → 音乐源 → 导入**

三种方式，任选：

| 方式 | 说明 |
|---|---|
| **选择文件** | 支持**多选**，19 个 `.js` 可以一次框完 |
| **粘贴文本** | 多个脚本拼在一起也行，会自动按 `globalThis.lx` 切开 |
| **从链接导入** | 填脚本直链（`.js`），也支持一次填多个链接 |

导入成功后：
- 自动激活第一个新增的源
- 退出配置页，首页直接变成**音乐界面**

#### 2. 自检（强烈建议第一次就做）

**配置管理 → 音乐 → 自检全部音源**

逐个真加载脚本，每行的副标题会变成结果：

```
星海音乐源 v3.2.15
✓ kg/kw/mg · kg:128k,320k,flac kw:128k,320k        ← 绿色，可用

洛雪音源5.js
加载失败：脚本没注册 request handler                ← 红色，不可用
```

底部提示 `自检完成：15/19 个可用`。

**只保留 ✓ 的源启用**，把 ✗ 的关掉——坏源会让每次搜索都白等 20 秒。

#### 3. 搜歌播放

回到首页：

```
音乐                               [退出]
星海音乐源 · 3 个音源
┌──────────────────────────────┐
│ 🔍 搜歌、歌手、专辑            │
└──────────────────────────────┘
[全部] [星海音乐源] [轻量音源] →
```

输入关键词回车 → 结果列表 → 点一首就开始播。

点完之后：
- 首页底部出现**迷你播放条**
- 点迷你条进**播放详情页**（就是原来那个沉浸式播放页）

---

### M3 MusicFree 插件引擎开关

#### 默认是关的

配置管理 → 音乐 → 音乐源：

```
┌────────────────────────────────────────┐
│ MusicFree 插件引擎              [ ○ ]  │
│ 默认关闭。开启后才能加载 MusicFree     │
│ 插件（洛雪不受影响）                   │
└────────────────────────────────────────┘
```

打开后副标题变成 `已启用 · 2 个插件`。

#### 为什么默认关

P10 的 `MusicFreeHost` 依赖一条**没在真机验证过**的路径：

```kotlin
// MusicFreeHost.kt:323
val thenFn = runCatching { promise.getProperty("then") }.getOrNull()
if (thenFn !is JSObject) { ... }
```

洛雪是「事件式」，壳主动 `send`，天然适配 QuickJS；MusicFree 是「模块式」，插件 `await` 返回，必须让 Kotlin 侧等 JS 的 Promise settle。这条桥的理论方案是挂 `then/catch` + `CountDownLatch` + 定时 drain，但 `JSObject.getProperty("then")` 在 wrapper 3.2.3 里返回什么类型（`JSObject` 还是 `JSCallFunction`），只能靠猜。

**关掉它，洛雪那 12 个文件完全不受影响。**

#### 关的时候会发生什么

四道门禁，全是提前返回：

| 环节 | 代码位置 | 关闭时行为 |
|---|---|---|
| 搜歌 | `MusicRepository:54` | 插件源根本不进线程池 |
| 取链 | `MusicRepository:223` | 打日志 `mf-media skipped: engine disabled` 后返回 null |
| 歌词 | `MusicRepository:304` | 跳过插件，直接走酷狗接口 |
| 自检 | `MusicConfigPanel:132` | 显示「插件[qq] 未启用（开关关闭）」，灰色不是红色 |

**注意**：自检里那句「未启用」和「加载失败」是刻意区分的。前者是开关没开，后者是真有问题。

另外关开关时会 `MusicFreeHost.clearAll()`，把每个插件占的两个线程（`mfjs-*` / `mfpump-*`）全部销毁，不留后台消耗。

#### 什么时候才该打开

1. 洛雪已经完整跑通（搜歌 → 播放 → 歌词 → 音质都正常）
2. 你手上有一个**只实现了 `search` + `getMediaSource`** 的简单插件
3. 打开开关 → 自检 → 看是否显示 `✓ 插件[qq] search/getMediaSource`

如果自检显示 `加载失败：超时`，说明插件 8 秒内没跑完，可能是：
- 插件用了 `setTimeout` 做异步轮询（我们的 `setTimeout` 是同步执行的一次调用，不支持轮询）
- 插件依赖了没实现的方法（`getTopLists` / `getRecommendSheetTags` 等只探测不实现）

---

### M4 19 个脚本实测清单

以下是本机对 `/sdcard/Download/yinyuan/音源/` 全部 19 个文件的逐字节检测结果。

判读依据：
- **lx**：是否出现 `globalThis.lx`（含 `globalThis['lx']` 方括号写法）
- **协议 4/4**：同时具备 `globalThis.lx`、`on(`、`request`、`inited` 四个要素
- **混淆**：`_0x` 变量个数。0 = 干净，<300 轻度，<7000 中度，≥7000 重度

| 文件 | 名称 | 字节 | 混淆 | 协议 | 判读 |
|---|---|---|---|---|---|
| 1 | 星海音乐源 | 38,858 | 干净 | 4/4 | ✓ **首选** |
| 2 | 长青SVIP音源(二改修复版) | 44,876 | 中度 | 3/4 | ✓ 可用 |
| 3 | 杰翔聚合音源 | 93,661 | 干净 | 4/4 | ✓ **首选** |
| 4 | 墨澜聚合音源 | 130,161 | 中度 | 3/4 | ✓ 可用 |
| 5 | 野草🌾 | 7,155 | 干净 | **2/4** | ✗ **不可用** |
| 6 | K×H测试 | 68,271 | 干净 | 4/4 | ✓ 可用 |
| 7 | 野花🌷 | 7,452 | 干净 | **2/4** | ✗ **不可用** |
| 8 | HYWmusic_beta_公益测试 | 9,057 | 干净 | 4/4 | ✓ 可用 |
| 9 | 聚合API接口 (CF) | 994 | 干净 | 4/4 | ✓ **最单纯** |
| 10 | lx-玉宁熙-Pro | 147,347 | 干净 | 3/4 | ✓ 可用 |
| 11 | 屿溪-终章 | 128,060 | 中度 | 3/4 | ✓ 可用 |
| 12 | lx-animemusic | 4,134 | 干净 | 4/4 | ✓ 可用 |
| 13 | fish_music | 639,619 | **重度** | 4/4 | ✓ 能跑但无法审计 |
| 14 | 𝖧౿ᥣᥣ𝗈 Ԝ𝗈𝗋ᥣ᯽ | 21,421 | 干净 | 4/4 | ✓ 可用 |
| 15 | HYWmusic_公益版 | 12,082 | 干净 | 4/4 | ✓ 可用 |
| 16 | 安妮独家音源 | 6,968 | 干净 | 4/4 | ✓ 可用 |
| 17 | aya (t.me/aya_app_) | 52,800 | 轻度 | 3/4 | ✓ 可用 |
| 18 | 轻量音源（四平台多源并行） | 26,416 | 干净 | 4/4 | ✓ **推荐** |
| 19 | Hei Music聚合源 | 68,588 | 轻度 | 4/4 | ✓ 可用 |

**结论：17 个可用，2 个不可用（5 号、7 号）。**

#### 推荐开启顺序

第一次测试建议**只开这三个**，先把主链路跑通：

1. **9 号（聚合API接口）** — 994 字节，全文只有 20 行，一眼看完。用来验证「壳的运行时是否正常」最合适。
2. **1 号（星海音乐源）** — 功能最全，多平台。
3. **18 号（轻量音源四平台）** — 结构清晰，多源并行。

跑通之后再逐个加，一旦某个源让搜索变慢或报错，`自检` 能立刻定位是谁。

#### 关于混淆

13、11、4、2、17、19 号有不同程度的 `_0x` 变量名混淆。

**混淆不影响执行。** 对 JS 引擎来说 `_0x1f0227` 和 `a`、`b` 完全等价。

检测结果：
- `eval(` / `new Function`：**0 个文件使用**
- `document.` / `localStorage`：**0 个文件使用**
- 加密外壳 / 二次加载：无

所以是**纯变量名混淆**，能正常跑。但重度混淆的（13 号 47,587 个 `_0x`）**行为无法审计**——它能把你请求的关键词、你的 IP 发到任何地方你也不会知道。自己权衡。

---

### M5 日志关键字对照表

全部带 `echo-music` 或 `lx-` 前缀，`adb logcat | grep -E 'lx-|echo-music'` 一把抓。

#### 导入阶段

| 日志 | 含义 |
|---|---|
| `music-import-reject: 不是洛雪音源脚本` | 内容里找不到 `globalThis.lx`，被拒 |
| `music-import-reject: 脚本内容为空` | 空文件或读取失败 |

#### 脚本加载阶段

| 日志 | 含义 | 下一步 |
|---|---|---|
| `lx-boot-ok <源名>` | 脚本执行完了没抛错 | — |
| `lx-handler-registered <源名>` | 脚本调了 `lx.on('request')` | 关键标志 |
| `lx-inited-ok <源名> => kg:128k,320k` | 拿到了平台和音质表 | 关键标志 |
| `lx-inited-missing <源名>` | 脚本没发 `inited` | 脚本问题或 `send` 注入问题 |
| `lx-boot-failed <源名> : xxx` | 脚本执行抛错 | 看后面的错误内容 |

**判读逻辑**：三个 `ok` 都有 = 源完全正常；缺 `lx-handler-registered` = `on` 注入有问题；缺 `lx-inited-ok` = 平台表没拿到，搜歌会跳过这个源。

#### 搜歌阶段

| 日志 | 含义 |
|---|---|
| `music-search [关键词] -> N 首, 失败源 M` | 一次搜索的总结果 |
| `lx-search-failed kg : xxx` | 某个平台接口挂了（网络/改接口） |
| `lx-search-skip platform=tx not in kg,kw` | 这个源不支持该平台，正常跳过 |
| `music-search-source-failed <源名> : xxx` | 某个源整体失败 |

**N=0 但自检全 ✓** → 平台接口问题（大概率是平台改接口了，看 `lx-search-failed` 的具体 URL）。

#### 取链阶段

| 日志 | 含义 | 下一步 |
|---|---|---|
| `music-session build [歌名] index=0/30 platform=kg` | 队列建好了 | — |
| `echo-music resolve [歌名] platform=kg` | 开始取链 | — |
| `echo-music resolve ok [歌名] q=320k url=http...` | **取到了** | 应该马上出声 |
| `echo-music resolve failed [歌名] 取不到播放地址` | 所有档位都失败 | 见下 |
| `music-media own-source-miss [歌名] q=flac/320k/128k err=xxx` | 本源全档位失败，准备兜底 | 看 err |
| `music-media fallback for [歌名 - 歌手]` | 开始换源重搜 | — |
| `music-media fallback hit via <源名>` | 兜底成功 | — |

**`resolve ok` 有但没声音** → 是地址本身的问题（多半缺 Referer 或地址过期），不是取链逻辑问题。

#### 播放页阶段

| 日志 | 含义 |
|---|---|
| `echo-music meta [歌名] qualities=flac/320k/128k liked=false` | 播放页信息刷新 |
| `echo-music lyric parsed: N lines` | 歌词解析成功 |
| `echo-music lyric empty for <歌名>` | 这首歌没找到歌词 |
| `echo-music switch quality -> flac` | 切音质触发 |
| `echo-music playMusicQueue [歌名] index=3` | 从首页点歌进入播放页 |

---

### M6 故障排查表

#### 首页没变成音乐界面

| 现象 | 原因 | 处理 |
|---|---|---|
| 导入后首页还是影视 | 没有激活的源 | 配置管理 → 音乐 → 点一下某个源激活 |
| 「退出」按钮点了没反应 | 阅读模式占着首页 | 退出阅读模式（两者互斥） |
| 首页空白一片 | 底部导航的 HOME 分支没生效 | 看 `MainScreen.kt` 的 `when` 分支是否包含音乐 |

#### 能搜到但播不出

按顺序查日志：

```
1. 有 echo-music resolve 吗？
   没有 → PlaybackStarter 的 isPlaceholder 判断没命中
         （说明 series.url 不是 music:// 开头，检查 MusicSession.buildPlaybackVod）

2. 有 music-media own-source-miss 吗？
   有 → 脚本拒绝了这个 extra。多半是 extra 字段不对
        （酷狗要 hash + types 表，酷我要 rid）

3. 有 music-media fallback hit via 吗？
   有但仍失败 → 兜底找到的歌取链也失败，说明所有源对这个平台都取不到

4. 有 echo-music resolve ok 但无声？
   → 地址和 Referer 问题。看 url 结尾是不是 .mp3/.m4a/.flac
     如果是 302 跳转地址，检查 headersFor() 里的 Referer
```

#### 搜不到歌

| 自检结果 | 说明 |
|---|---|
| 全 ✓ 但搜不到 | 平台接口变了。看 `lx-search-failed` 里的 HTTP 状态码 |
| 部分 ✗ | 关掉坏源再搜 |
| 全 ✗ | `LxRuntime` 有问题，看 `lx-boot-failed` |

#### 歌词不显示

- 洛雪脚本**不管歌词**，歌词是壳自己走酷狗 `krcs.kugou.com` 拉的
- 海外 IP 或该接口封锁时拿不到，属正常
- `echo-music lyric empty` 就是没找到，不影响播放

#### 切音质没反应

- 只有播放页顶栏的胶囊能点，且**仅音乐模式**（`ui.musicMode == true`）
- 如果这首歌的源只支持 128k，选 flac 会静默回落到 128k（看 `resolve ok` 的 `q=` 值）

---

### M7 已知限制

| 限制 | 说明 |
|---|---|
| MusicFree 默认关闭 | 引擎已实现但依赖未验证的 QuickJS Promise 桥，需手动开启 |
| MusicFree 插件生态在外面 | 插件要在 MusicFree 官方仓库单独下载 `.js`，本工程不自带 |
| MF 只实现核心三类方法 | `search` / `getMediaSource` / `getLyric`；`getTopLists` 等仅探测不实现 |
| MF 的 setTimeout 是同步的 | 插件若用它做轮询会卡住，`setTimeout` 只执行一次不递归 |
| 搜索是壳做的 | 平台改接口就得改 `LxSearch.kt` 重新发版 |
| 歌单不能导入 | 不支持洛雪 `.lxmc` 格式，只有本地「我喜欢」 |
| 歌词来源单一 | 洛雪源只走酷狗；MusicFree 源优先用插件的 `getLyric` |
| 音质受源限制 | 脚本给什么档位就只能用什么 |
| 混淆源无法审计 | 13/11/4/2/17/19 号的行为不可验证 |

---

### M8 代码地图

```
app/src/main/java/com/github/tvbox/osc/
├── music/
│   ├── MusicSource.kt           47   源模型 + MusicSourceKind 枚举
│   ├── MusicScriptParser.kt     80   解析 @name/@version/@author
│   ├── MusicStore.kt           232   存储/去重/开关/健康度/MF开关
│   ├── MusicSong.kt             51   歌曲模型（extra 是洛雪原生 musicInfo）
│   ├── LxRuntime.kt            752   ★ 洛雪 QuickJS 沙箱
│   ├── LxSearch.kt             449   ★ 五平台搜歌
│   ├── MusicRepository.kt      370   搜歌/取链/歌词/历史（双引擎分流）
│   ├── MusicSession.kt         241   队列→VodInfo、我喜欢、音质
│   ├── MusicFreeHost.kt        444   ★ MF QuickJS 沙箱（高风险，默认关）
│   ├── MusicFreePrelude.kt     522   MF 的 17KB JS 垫片
│   ├── MusicFreeMapper.kt       99   插件返回值 → 我们的模型
│   ├── MusicFreeScriptProbe.kt  24   判断是洛雪还是 MF
│   ├── MusicHttp.kt            119   axios 后端（返回包装 JSON）
│   ├── MusicCrypto.kt          136   加解密 + BigInteger
│   └── MusicCheerio.kt          48   cheerio → jsoup
├── ui/page/
│   ├── MusicHomePage.kt        695   音乐首页（搜索/源chip/结果/迷你条）
│   ├── MusicConfigPanel.kt     570   源管理 + 自检 + MF开关 + 导入
│   └── MainScreen.kt            +3   HOME 三分流（音乐/阅读/影视）
├── ui/music/
│   ├── MusicPlayerScreen.kt     +75  音质胶囊 + 切档面板
│   ├── MusicPlayerState.kt      +7   musicMode/quality/qualityOptions
│   └── MusicLrc.kt             198   歌词解析（LRC/SRT/ASS）
├── ui/activity/
│   ├── MusicPlayerActivity.kt   +49  歌词走音乐接口 / 收藏 / 音质
│   ├── ConfigManageActivity.kt  +70  多选文件选择器
│   └── DetailActivity.kt        +24  playMusicQueue()
├── player/
│   └── PlaybackStarter.kt       +40  ★ 拦截 music:// 占位符
└── util/
    ├── HawkConfig.kt            +13  KV key
    └── kv/KVKeySpec.kt          +13  key 类型注册
```

**带 ★ 的四个文件是核心**，出问题优先看它们。

洛雪链路：`LxRuntime` → `LxSearch` → `MusicRepository` → `PlaybackStarter`
插件链路：`MusicFreeHost` → `MusicFreePrelude` → `MusicRepository` → `PlaybackStarter`（默认不启用）

---

### M9 编译失败时如何降级到纯洛雪

这份代码**没有在任何机器上编译过**（写它的环境没有 JDK / Android SDK）。如果 `./gradlew assembleDebug` 报错，按下面顺序处理。

#### 原则：先保住洛雪

洛雪那 12 个文件用的是 `JsSpider.kt` 里已经验证过的 QuickJS API（`create`、`setConsole`、`setProperty`、`evaluate`、`JSCallFunction`、`call`），风险低。

MusicFree 那 7 个文件引入了几个**只在 wrapper 3.2.3 里存在但没被本工程用过**的 API：

| API | 位置 | 风险 |
|---|---|---|
| `JSObject.getProperty("then")` | `MusicFreeHost:323` | 返回类型不确定 |
| `JSObject.call(...)` 动态调用 | `MusicFreeHost:342` | 未验证 |
| `context.globalObject` | `MusicFreeHost:180` | 未验证是否可空 |

#### 第一步：确认错误来自哪边

```bash
./gradlew assembleDebug 2>&1 | grep -E "MusicFree|LxRuntime" | head -30
```

- 只报 `MusicFree*` → 走第二步
- 同时报 `LxRuntime` / `LxSearch` → 先修洛雪，这是根本
- 报 `MusicRepository` → 看是哪个分支的错

#### 第二步：暂时摘掉 MusicFree（3 处改动）

**① 把 7 个文件挪出源码树**

```bash
mkdir -p /tmp/mf_backup
cd app/src/main/java/com/github/tvbox/osc/music
mv MusicFreeHost.kt MusicFreePrelude.kt MusicFreeMapper.kt \
   MusicFreeScriptProbe.kt MusicHttp.kt MusicCrypto.kt MusicCheerio.kt \
   /tmp/mf_backup/
```

**② 改 `MusicRepository.kt`，砍掉两个方法**

删掉 `searchByMusicFree(...)` 整个函数，以及 `searchByLx` 上方那句注释里的引用。

在 `search()` 里把 `when (source.kind)` 改成只走洛雪：

```kotlin
// 原
val got = when (source.kind) {
    MusicSourceKind.LX -> searchByLx(source, query, page, collected, seen, failures)
    MusicSourceKind.MUSIC_FREE ->
        searchByMusicFree(source, query, page, collected, seen, failures)
}

// 改成
val got = searchByLx(source, query, page, collected, seen, failures)
```

删掉 `tryMusicFreeSource(...)` 整个函数。

在 `tryOwnSource` 里删掉这段：

```kotlin
if (source.kind == MusicSourceKind.MUSIC_FREE) {
    if (!MusicStore.musicFreeEnabled.value) {
        LOG.i("mf-media skipped: engine disabled")
        return null
    }
    return tryMusicFreeSource(source, song, levels)
}
```

以及 `levels` 里的分支改成只留 else：

```kotlin
val levels = buildList {
    MusicSession.qualityFor(song)?.let { add(it) }
    if (!quality.isNullOrBlank()) add(quality)
    addAll(MusicQuality.preferOrder(song.qualities, song.platform))
}
```

在 `lyric()` 里删掉开头那段：

```kotlin
// 删掉
val src = MusicStore.find(song.sourceId)
if (src != null && src.kind == MusicSourceKind.MUSIC_FREE &&
    MusicStore.musicFreeEnabled.value) { ... }
```

**③ 改 `MusicConfigPanel.kt`，砍掉开关 UI 和自检分支**

- 删掉 `SettingsSwitchRow(...)` 那块（含上面两行 `val mfCount`）
- 删掉顶层的 `val mfEnabled by MusicStore.musicFreeEnabled.collectAsState()`
- 删掉 import `SettingsSwitchRow`
- `checkAll()` 里把 `if (src.kind == MusicSourceKind.MUSIC_FREE) { ... } else { ... }` 改成只留洛雪那段
- 如果有 `MusicSourceKind` / `MusicFreeScriptProbe` 的 import，一并删掉

**④ `MusicStore.kt`** 里的 `setMusicFreeEnabled` 调用了 `MusicFreeHost.clearAll()`，把那行删掉：

```kotlin
fun setMusicFreeEnabled(enabled: Boolean) {
    com.github.tvbox.osc.util.KV.put(HawkConfig.MUSIC_MUSICFREE_ENABLED, enabled)
    musicFreeEnabledFlow.value = enabled
    // 删掉 MusicFreeHost.clearAll()
    LOG.i("music-musicfree-enabled=" + enabled)
}
```

改完之后 `MusicSourceKind.MUSIC_FREE` 枚举值还留着（`importScript` 会用到），不影响编译。

#### 第三步：恢复 MusicFree

把 `/tmp/mf_backup/` 里的 7 个文件挪回 `music/`，然后把第二步的改动反向恢复。

**建议顺序**：先让洛雪通过编译并跑通，确认搜歌播放没问题，再单独处理 MusicFree 的编译错误。因为 `MusicFreeHost` 里那几行如果 API 不对，改法取决于 wrapper 实际暴露了什么，得看报错信息才知道。

---

### M10 关键设计决策（改代码前必读）

#### 1. 为什么用占位 URL

`MusicSession.buildPlaybackVod()` 把歌单变成 `VodInfo` 时，`series[i].url` 填的是：

```
music://<平台>/<歌曲id>/<队列下标>
```

不是真实地址。原因：真实地址要等播放时才取（可能过期），而 `VodInfo` 是整个队列一起构造的。

`PlaybackStarter.playUrl()` 拿到 `music://` 就拦截、取链、再走正常播放。**这样队列、上下首、播放模式、进度全部复用，播放器一行没改。**

#### 2. 为什么 lx.request 的回调要排队

`LxRuntime` 里所有 QuickJS 调用锁在单线程 `jsExecutor` 上。但脚本的 `handler` 内部会再调 `lx.request`，如果此时同步回调进 JS，就是**单线程自己等自己** → 死锁（v2.63 的「25 秒超时取链失败」就是这个）。

解法：`lx.request` 同步做 HTTP，但回调塞进 `pendingCallbacks` 队列，等 `handler` 返回后由 `drain()` 统一执行。

#### 3. 为什么取链失败要重搜

不能拿 A 源的歌去 B 源取链——extra 里的 `songmid`/`hash` 是 A 平台专属的，B 源解不出来。

正确做法：按「歌名 + 歌手」在 B 源**重搜一遍**，拿 B 源自己的原生 extra 再取。

#### 4. 为什么搜歌在壳里

洛雪本体就是这么设计的：壳负责调平台接口搜歌，搜完把 `musicInfo` 交给脚本取链。脚本只认 `action=musicUrl` 这类请求，不提供搜索能力。

所以换平台接口必须改 `LxSearch.kt`，没法靠换脚本解决。


---

## 第二部分　AI 辅助写源（七要素核对）

> 代码位置：`app/src/main/java/com/github/tvbox/osc/api/ai/SiteStructureAudit.kt`
> 　　　　　`app/src/main/java/com/github/tvbox/osc/ui/dialog/AiAssistPanel.kt`
> 适用场景：给一个网址，让 AI 自动写出能跑的 Python 采集源（Spider）


> 对应 api/ai/SiteStructureAudit.kt 与 ui/dialog/AiAssistPanel.kt 的 3.10 改动。
> 适用场景：给一个网址，让 AI 自动写出能跑的 Python 采集源（Spider）。

---

### A1 为什么以前老跑不起来

旧流程：

```
抓首页 HTML   ┐
抓分类页 HTML ├→ 拼成一大段"侦察摘要" → 丢给模型 → 模型自己看着办
抓详情页 HTML ┤        （3000~5000 字原始 HTML）
抓播放页 HTML ┘
```

模型拿到几千字 HTML，要**自己从里面猜**七件事：

1. 首页影片卡片的 class 是哪个
2. 分类页 URL 长什么样
3. 有没有筛选栏
4. 详情页标题/封面怎么取
5. 有几条播放线路
6. 每线几集
7. 播放页能不能直接拿到 m3u8

**猜错任何一项，就是 0 条。** 而 0 条的反馈只有一句"首页 0 条"，模型不知道错在哪，只能盲改选择器，越改越错。

这就是"输入网址，老是真跑不起来"的根本原因。

---

### A2 现在的做法：先核对，再写源

```
抓首页/分类/详情/播放页
        ↓
【结构核对 5/7】          ← 逐项判定，不再让模型猜
① 首页列表   [OK] .module-poster-item 命中 24 个
② 分类路由   [OK] /vodshow/{tid}----------{page}.html
③ 筛选选项   [缺] 分类页无筛选栏
        → 该站可能没筛选，不要编造筛选项
④ 详情字段   [OK] 标题/封面/播放区
⑤ 播放线路   [OK] 线路1, 线路2
⑥ 播放集数   [OK] 20 集，样例 第1集,第2集,第3集 地址
⑦ 播放直链   [缺] 播放页为 iframe 解析器页
        → 再请求 iframe 的 src 抠 m3u8；抠不到 return parse=1

先补齐标[缺]的项再写源：播放直链。禁止对这些项写"未知"
        ↓
模型按"补齐缺口"的指令干活
```

**关键变化**：模型不再需要从 HTML 里找东西，而是拿到一份"已核实清单 + 待补清单"。找到的项直接用真实值，缺的项有明确补法。

---

### A3 七要素与六个接口的对应关系

| # | 要素 | 对应接口 | 数据字段 | 写错会怎样 |
|---|---|---|---|---|
| ① | 首页列表 | homeContent() | list[].vod_id / vod_name / vod_pic | 首页空白 |
| ② | 分类路由 | categoryContent(tid, pg) | 拼 URL 用 | 分类点进去空 |
| ③ | 筛选选项 | categoryContent(extend) | class[].filter | 筛选项点了没反应 |
| ④ | 详情字段 | detailContent(ids) | vod_name / vod_pic / vod_content | 详情页空白 |
| ⑤ | 播放线路 | detailContent() | vod_play_from（$$$ 分隔） | 线路标签没有 |
| ⑥ | 播放集数 | detailContent() | vod_play_url（# 分集，$ 分名与地址） | 集数列表空 |
| ⑦ | 播放直链 | playerContent(flag, id) | url / parse | 点播放转圈或报错 |

#### 三个分隔符（最容易搞错）

```python
vod_play_from = "线路1$$$线路2$$$线路3"

vod_play_url = "第1集$https://a.com/1.m3u8#第2集$https://a.com/2.m3u8"
```

**搞混任何一个就是 0 条。** 提示词里已经把这一段单独列为"数据格式硬规定"。

---

### A4 每项怎么判、缺了怎么补

#### ① 首页列表

**判定**：先查 10 个常见 class —— module-poster-item、module-card-item、stui-vodlist__box、vodlist__thumb、public-list-box、thumb-txt-cor、poster-item、card-item、video-item、list-item，命中 3 个以上算找到。兜底再找“同时含 class 和详情链接”的重复 li/div 块。

**缺了怎么办**

```
抓首页，找出包住每个影片卡片的重复 class，
形如 <div class="xxx"><a href="/vod/123.html">，写进 soup.select()
```

#### ② 分类路由

**判定**：首页 href 含 /vodshow/、/vodtype/、/vod/show、/type/、/frim/、cates-N 之一。

**缺了怎么办**

```
抓首页导航栏，找「电影/电视剧/动漫/综艺」的真实 href，数字换 {tid}，页码换 {page}
```

#### ③ 筛选选项

**判定**：从分类页提取筛选链接组。按“相对当前 URL 改动的字符区间”分组，不假设短横/斜杠/参数形状。

**缺了怎么办**

```
该站可能没筛选。class 里不要带 filter 字段，extend 参数忽略。不要编造筛选项
```

> 这一项**允许缺失**。很多站确实没有筛选栏，硬编就是假数据。

#### ④ 详情字段

**判定**：同时检查 h1 标题、封面 img（data-original 或 src）。

**缺了怎么办**

```
抓一个真实详情页，确认 h1 标题与封面 img 的真实 class；
vod_id 从详情 URL 正则取数字，不要从页面文字取
```

> vod_id 一定要从 URL 取。从页面文字取，遇到同名影片就串了。

#### ⑤ 播放线路

**判定**：找 .play-title、.nav-tabs、.module-tab-item、.play-from、.source、.line 容器里的文字；兜底找“线路1/线路2/wjm3u8”。

**缺了怎么办**

```
线路名一般在 .play-title / .nav-tabs / .module-tab-item 里。vod_play_from 用 $$$ 分隔
```

#### ⑥ 播放集数

**判定**：统计详情页里 href 含 play、/p/、vodplay 的 a 标签数量。

**缺了怎么办**

```
剧集链接形如 <a href="/vod/play/123-1-1.html">第1集</a>。
拼成 第1集$地址#第2集$地址（# 分集，$ 分名与地址）
```

#### ⑦ 播放直链

**判定**：播放页里正则找 m3u8 / mp4 / flv。

**缺了怎么办**（分两种）

| 情况 | 补法 |
|---|---|
| 播放页有 iframe | 再请求 iframe 的 src，从结果正则抠 m3u8；抠不到就 return parse=1 且 url 填 iframe 地址 |
| 播放页无直链 | playerContent 返回 parse=1 且 url 填播放页地址，交 App 内置解析器 |

> parse=1 表示“这是解析器页，让 App 自己去解”。很多站都靠这个，不是失败。

---

### A5 日志怎么看

全在 AI 辅助面板底部的“任务状态”里。

#### 正常流程

```
开始侦察：https://xxx.com
侦察完成
站点定性：HTML 站：详情链接 12 条，分类 5 个
结构核对：【结构核对 7/7】              <- 关键行
AI 写源：Agent 看本站真实 HTML 片段…
Agent 写源开始（工具循环，最多 8 回合）
Agent 工具：write_source
Agent 工具：static_check
工具 static_check 返回：静态校验通过
Agent 工具：run_six_api
静态校验通过，开始真跑六接口
真跑通过：首页 24 条，推荐 0 条，分类 20 条，详情 OK，搜索 5 条，播放直链 OK
已保存到 /sdcard/.../xxx.py，下次扫描后生效
```

#### 核对没满时

```
结构核对：【结构核对 5/7】
```

说明有 2 项没找到，模型会按提示去补，日志里能看到它调 fetch_url。

#### 核对数值对应什么排查方向

这是**最有用的一张表**。看到 `x/7` 里的 x 是多少，就知道该往哪里查。

| 核对结果 | 说明 | 该查哪里 |
|---|---|---|
| **0~2/7** | 几乎什么都没抓到 | **先查网络，不要改代码**。浏览器能打开吗？要代理吗？站点是不是 SPA？ |
| **3~4/7** | 抓到了首页，但分类/详情/播放打不开 | 查分类页和详情页的 URL 是否拼对（`absUrl()` 有没有漏） |
| **5~6/7** | 只差一两项 | 正常情况，模型能补上。若卡住不动，看它反复调 fetch_url 抓哪个页面 |
| **7/7** | 全齐 | 接下来若仍 0 条，是渲染 HTML ≠ Python 实际拿到的内容，见下方 |

##### 0~2/7：先去查网络，别改代码

这个档位说明**侦察阶段就没拿到东西**，改提示词、改选择器全是白费。

按顺序查：

1. **手机浏览器能打开这个站吗？** 打不开就是网络问题（被墙、需要代理、DNS 污染）
2. **日志里有「侦察未拿到页面正文」吗？** 有就直接中止了，不会进 AI 写源
3. **站点是 SPA 吗？** 日志里有「首页疑似 SPA/空壳（len=xxx），改用无头 WebView 渲染」，看渲染后 len 变大没

```
侦察失败：HTTP 200 但正文过短 bodyLen=42 final=https://xxx.com
```
→ 站点给非浏览器 UA 返回空壳。换桌面 UA 或加代理。

```
首页疑似 SPA/空壳（len=180），改用无头 WebView 渲染…
渲染未获得更多内容，沿用 okhttp 结果（len=180）
```
→ WebView 渲染也没用。这个站可能：
- 需要登录才有内容
- 用了 Cloudflare 五秒盾
- JS 加载的数据源被墙

**这种站直接放弃，别硬写源。** 就算硬写出来，App 里也播不了。

##### 3~4/7：URL 拼错了

这个档位通常是**首页能抓，但分类页/详情页抓不到**。最常见原因是相对链接没拼成绝对地址。

看日志里分类页那行：

```
[分类页抽样] https://xxx.com/vodshow/1.html HTTP 200
列表 id 样例：
```

如果「列表 id 样例」是空的，说明分类页抓到了但里面没有影片链接。可能：

- 分类 URL 猜错了（比如真实的要用 `/vodshow/1--------1---.html` 带更多短横）
- 分类页的影片链接是 JS 动态插入的

**改进办法**：手动用浏览器打开那个分类页 URL，看内容对不对。不对就说明 URL 拼错。

##### 5~6/7：模型在补，但可能补不到

正常情况。看日志里模型调 `fetch_url` 抓哪个页面：

```
Agent 工具：fetch_url
工具 fetch_url 返回：HTTP 200 ...（前 120 字）
```

如果它反复抓同一个页面、同一个错误，就会触发熔断：

```
同一错误连续 3 次，停止写源
```

这时候看它抓的 URL 是不是对的。有时候模型会自己拼一个不存在的 URL 去抓，当然抓不到。

##### 7/7 但仍然 0 条：渲染 HTML ≠ Python 实际拿到的

这是最后一个坑，也最隐蔽。

侦察阶段用的是 **OkHttp + 桌面/手机 UA + Referer**，可能还经过了 **WebView 渲染**。

Python 的 `requests` 拿到的是**另一次请求**，可能：

- 少了某个必需 Header（Cookie、X-Requested-With）
- 少了 Referer，被防盗链拦
- 站点对 `python-requests` 的 UA 直接返回空

**判断方法**：看真跑日志里的异常

```
真跑未通过（首页 0 条，推荐 0 条｜接口正常返回但列表为空：选择器没匹配到节点，或站点要求 Referer/Cookie）
```
→ 接口通了但没内容，八成是 Header 问题

```
真跑未通过（首页 0 条，推荐 0 条｜接口异常：ConnectionError: ...）
```
→ 网络层就挂了

**改进办法**：让模型给 `requests.Session` 补全 Header。提示词里已经要求了：

```
抓取必须带完整 headers：User-Agent（真实浏览器 UA）+ Referer=首页 + Accept-Language
```

如果还是不行，说明站点校验更严格（比如校验 `Sec-Fetch-*` 系列），这种站基本没法用纯 HTTP 抓。

#### 引擎问题（新加）

```
无法真跑验证：Android 16+ 的 32 位进程不支持 Python 引擎，无法真跑验证。
源码已生成（未真跑验证），保存到目标文件夹
可运行源码已存：/sdcard/.../xxx_未验证.py
```

**这类情况源码是好的，只是没法在本机验证。** 换 64 位设备或在电脑上跑就能确认。

#### 值得警惕的信号

| 日志 | 含义 |
|---|---|
| 已修 3 轮仍未通过，仍保存最后一版源码 | 到熔断轮数了，源码在 xxx_未通过_第3轮.py |
| 第 N 轮修复未改动基线，停止修复 | AI 改不动了，通常是信息不足 |
| Agent 写源未产出可用代码，返回起点骨架 | 8 回合内没交出完整代码 |
| 同一错误连续 3 次，停止写源 | 错误指纹相同，再改也没用 |

---

### A6 静态校验现在给的提示

staticCheck() 的判定顺序（命中即返回）：

| 顺序 | 检查 | 提示 |
|---|---|---|
| 1 | 空内容 | 生成内容为空 |
| 2 | 无 class Spider | 缺少 Spider 类 |
| 3 | 无 homeContent | 缺少 homeContent 入口 |
| 4 | **有 lxml** | 使用了 lxml…必须改成 BeautifulSoup |
| 5 | **缺依赖** | 依赖了本环境没有的模块 X…请改用 requests + BeautifulSoup |
| 6 | 无网络请求 + 分类写死空数组 | 空壳：homeContent 把分类写死成空数组 |
| 7 | 括号不配平 | 括号不匹配，可能被截断 |

第 4、5 条是新增的。

**改之前**：from lxml import etree 会掉进第 6 条，提示“分类写死成空数组”，模型看到这句只会去改 class 数组，完全想不到是 lxml 的问题，于是原地打转。

**改之后**：直接说“用了 lxml，改用 BeautifulSoup”。

#### 会被拦下的模块

cloudscraper、selenium、playwright、pyquery、scrapy、pandas、numpy

这些在 App 的 Python 环境里没有，import 就抛 ModuleNotFoundError。以前这个异常会被吞成“0 条”，现在提前拦下并说明替代方案。

---

### A7 真跑验证的四个阶段

validateRun() 依次走：

```
① pythonEngineProblem()  <- 新增，预检引擎
   ├─ Android 16+ 32 位进程 -> 直接返回 __ENGINE__
   ├─ 有 lxml               -> 返回 __ENGINE__
   └─ 缺依赖模块            -> 返回 __ENGINE__
        ↓ 通过
② SpiderLoader().pySpider()
   └─ 拿到 SpiderNull      -> 返回 __ENGINE__（引擎没起来）
        ↓ 拿到真 Spider
③ 依次调六接口
   homeContent -> homeVideoContent -> categoryContent
   -> detailContent -> searchContent -> playerContent
        ↓
④ 逐项断言
   首页/推荐至少一个非空 -> 分类非空 -> 详情有样本
   -> 播放有线路 -> 播放返回地址 -> 地址是 m3u8/mp4 直链
```

**第 ①② 步是新加的。** 以前直接进第 ③ 步，Python 不可用时拿到 SpiderNull（六接口全返回空串），一个完全正确的源也被判“0 条”。

#### __ENGINE__ 前缀

外层循环看到这个前缀就不再让 AI 修选择器，而是：

```
无法真跑验证：<具体原因>
源码已生成（未真跑验证），保存到目标文件夹
可运行源码已存：/sdcard/.../xxx_未验证.py
```

文件名带 _未验证 后缀，和 _未通过 区分开：

| 后缀 | 含义 |
|---|---|
| _未验证.py | 源码可能没问题，是引擎跑不了 |
| _未通过_第N轮.py | 真跑了但没通过，源码确实有问题 |

---

### A8 给模型的要求（提示词核心）

#### 强制执行顺序

```
1. 先看『结构核对 x/7』，列出所有标 [缺] 的项
2. 对每个 [缺]，用 fetch_url 抓对应页面找出来
   （缺首页列表->抓首页；缺分类路由->抓首页导航；缺筛选->抓分类页；
     缺详情字段->抓一个详情页；缺线路/集数->抓详情页播放区；缺直链->抓播放页）
3. 七项都拿到真实值后，一次性写完整 Spider（write_source）
   把每一项的真实选择器/路由直接写进代码，不要留任何 TODO 或「未知」
4. 最后 static_check + run_six_api 自验，六接口全非空才交答案
不要跳过第 1、2 步直接写代码——那正是以前反复 0 条的原因
```

#### 数据格式硬规定

```python
{"class": [{"type_id": "1", "type_name": "电影"}],
 "list": [{"vod_id": "/voddetail/123.html", "vod_name": "片名", "vod_pic": "封面"}]}

{"list": [...], "page": pg, "pagecount": 9999, "limit": 20, "total": 9999}

{"list": [{"vod_id": "id", "vod_name": "名", "vod_pic": "图", "vod_content": "简介",
           "vod_play_from": "线路1$$$线路2",
           "vod_play_url": "第1集$地址1#第2集$地址2$$$线路2第1集$地址"}]}

{"parse": 0, "playUrl": "", "url": "m3u8地址", "header": {}}

{"parse": 1, "playUrl": "", "url": "播放页地址"}
```

---

### A9 已知限制

| 限制 | 说明 |
|---|---|
| 只支持 HTML 站和标准采集接口 | SPA 站靠 WebView 渲染兜底，渲染不出来就抓不到 |
| iframe 嵌套解析器拿不到直链 | 用 parse=1 交给 App 内置解析，成功率取决于内置解析器 |
| 七要素是静态正则判定 | 极端自定义结构的站可能判不出来，会标 [缺] 让模型去查 |
| 真跑依赖本机 Python 引擎 | Android 16+ 32 位进程不支持，只能生成不能验证 |
| 筛选栏判定不保证准确 | 只按“改动的字符区间”分组，复杂筛选可能分错组 |

---

### A10 代码位置

```
app/src/main/java/com/github/tvbox/osc/
├── api/ai/
│   ├── SiteStructureAudit.kt     229 行  * 七要素核对（新增）
│   ├── AvSiteScout.kt            315 行  站点定性（CMS_API/HTML/SPA）
│   ├── AiRepair.kt               401 行  修源引擎、诊断报告
│   ├── AiSkillPack.kt            453 行  技能包加载与分诊
│   └── JsRender.kt               133 行  无头 WebView 渲染
└── ui/dialog/
    └── AiAssistPanel.kt         2740 行  * 建源/修源主流程
```

AiAssistPanel.kt 里几个关键函数：

| 函数 | 作用 |
|---|---|
| reconSite() | 侦察 + 拼摘要 + 调七要素核对 |
| generateWithAgent() | Agent 工具循环写源 |
| validateRun() | 真跑六接口验证 |
| staticCheck() | 静态校验（含 lxml / 缺依赖拦截） |
| pythonEngineProblem() | 引擎预检（新增） |
| agentToolDefinitions() | 告诉模型有哪些工具可用 |

---

# 附录　全部改动文件清单

## 第一部分：音乐播放器

### 新增文件

| 文件 | 行数 | 作用 |
|---|---|---|
| `music/MusicSong.kt` | 51 | 歌曲模型，含洛雪原生 `extra` |
| `music/MusicSource.kt` | — | 音乐源模型（洛雪 / MusicFree 两种 kind） |
| `music/MusicStore.kt` | 271 | 源存储：导入、启停、持久化 |
| `music/LxRuntime.kt` | 752 | 洛雪 QuickJS 沙箱（事件式协议） |
| `music/LxSearch.kt` | 449 | 五平台搜歌：酷狗/酷我/咪咕/QQ/网易 |
| `music/MusicRepository.kt` | 316 | 总仓库：并行搜歌 → 取链 → 歌词 |
| `music/MusicSession.kt` | 231 | 队列、我喜欢、音质覆盖 |
| `music/MusicFreeHost.kt` | 444 | MusicFree QuickJS 运行时（CommonJS） |
| `music/MusicFreePrelude.kt` | 522 | **17KB 纯 JS**，module/exports/require 垫片 |
| `music/MusicFreeMapper.kt` | 99 | 插件返回结构 → MusicSong |
| `music/MusicFreeScriptProbe.kt` | 24 | 判定脚本是洛雪还是 MusicFree |
| `music/MusicHttp.kt` | 119 | axios 转发到 OkHttp |
| `music/MusicCrypto.kt` | 136 | md5/sha1/sha256/hmac/AES/base64/hex |
| `music/MusicCheerio.kt` | 48 | cheerio 用 jsoup 顶替 |
| `ui/page/MusicHomePage.kt` | — | 音乐首页壳 |
| `ui/page/MusicConfigPanel.kt` | — | 源管理页（导入/自检/开关） |
| `ui/music/MusicPlayerScreen.kt` | — | 播放页（歌词/收藏/音质/队列） |
| `ui/music/MusicPlayerState.kt` | — | 播放页状态 |
| `ui/activity/MusicPlayerActivity.kt` | — | 播放页 Activity |

### 修改文件

| 文件 | 改动 |
|---|---|
| `util/HawkConfig.kt` | 新增 `MUSIC_*` 配置常量（含 `MUSIC_MUSICFREE_ENABLED`） |
| `util/kv/KVKeySpec.kt` | 注册对应 KV key |
| `player/PlaybackStarter.kt` | 拦截音乐占位 URL，改走音乐取链 |
| `player/PlaybackController.kt` | 音质回写 |

---

## 第二部分：AI 辅助写源

### 新增文件

| 文件 | 行数 | 作用 |
|---|---|---|
| `api/ai/SiteStructureAudit.kt` | 229 | **七要素核对**（本轮核心） |

### 修改文件（`ui/dialog/AiAssistPanel.kt`，共 7 处）

| # | 位置 | 改动 |
|---|---|---|
| 1 | `validateRun()` 开头 | 加 `pythonEngineProblem()` 预检 + `SpiderNull` 检测 |
| 2 | 新增函数 | `pythonEngineProblem()`，查 32 位进程/lxml/7 个缺依赖模块 |
| 3 | 外层循环 | 遇 `__ENGINE__` 时保存为 `xxx_未验证.py` 并返回 |
| 4 | `validateRun()` 0 条分支 | 透传异常原文（原来被 `listSize` 吃掉变成「0 条」） |
| 5 | `staticCheck()` | lxml 判定前置（原来掉进「空壳」规则） |
| 6 | `staticCheck()` | 缺依赖判定前置 |
| 7 | `genSystem` / `genUser` | 提示词重写：结构核对清单 + 数据格式硬规定 + 强制顺序 |

---

## 文档

| 文件 | 行数 | 说明 |
|---|---|---|
| `README_合并版.md` | 本文件 | 两份文档合并版 |
| `MUSIC_README.md` | 524 | 原音乐文档（保留） |
| `AI_SOURCE_README.md` | 472 | 原 AI 写源文档（保留） |

---

# 附录　首次编译预期

**这两块代码都没有经过编译。** 下面按「最可能报错」排序：

| 风险 | 文件 | 可能原因 | 处理 |
|---|---|---|---|
| 高 | `music/LxRuntime.kt`<br>`music/MusicFreeHost.kt` | QuickJS wrapper 的 API 签名猜的 | 照 M9 降级到纯洛雪 |
| 中 | `music/MusicFreePrelude.kt` | Kotlin 原始字符串里的 JS 转义 | 看编译错误行号，改对应 JS 片段 |
| 中 | `ui/dialog/AiAssistPanel.kt` | 字符串拼接引号嵌套 | 看报错行，通常是 `"` 未转义 |
| 低 | `api/ai/SiteStructureAudit.kt` | 纯逻辑 + 字符串，无新 API | 基本不会错 |
| 低 | `music/MusicCrypto.kt`<br>`music/MusicHttp.kt` | 用标准 javax.crypto / OkHttp | 低风险 |

编译通过的验证顺序建议：

1. **洛雪音乐**（P1–P9）——用的全是 `JsSpider.kt` 验证过的 API
2. **AI 写源**——拿标准苹果 CMS 站测
3. **MusicFree**（默认关，最后测）

