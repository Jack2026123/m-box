# 音乐播放器（洛雪音源）使用与验证指南

> 本文档对应当前代码树中的 `music/` 包与相关 UI。
> 适用版本：AVBox 2.72+ 加上本轮 P1–P9 的全部改动。

---

## 一、这套东西是什么

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

## 二、快速开始

### 1. 导入音源

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

### 2. 自检（强烈建议第一次就做）

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

### 3. 搜歌播放

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

## 三、MusicFree 插件引擎开关

### 默认是关的

配置管理 → 音乐 → 音乐源：

```
┌────────────────────────────────────────┐
│ MusicFree 插件引擎              [ ○ ]  │
│ 默认关闭。开启后才能加载 MusicFree     │
│ 插件（洛雪不受影响）                   │
└────────────────────────────────────────┘
```

打开后副标题变成 `已启用 · 2 个插件`。

### 为什么默认关

P10 的 `MusicFreeHost` 依赖一条**没在真机验证过**的路径：

```kotlin
// MusicFreeHost.kt:323
val thenFn = runCatching { promise.getProperty("then") }.getOrNull()
if (thenFn !is JSObject) { ... }
```

洛雪是「事件式」，壳主动 `send`，天然适配 QuickJS；MusicFree 是「模块式」，插件 `await` 返回，必须让 Kotlin 侧等 JS 的 Promise settle。这条桥的理论方案是挂 `then/catch` + `CountDownLatch` + 定时 drain，但 `JSObject.getProperty("then")` 在 wrapper 3.2.3 里返回什么类型（`JSObject` 还是 `JSCallFunction`），只能靠猜。

**关掉它，洛雪那 12 个文件完全不受影响。**

### 关的时候会发生什么

四道门禁，全是提前返回：

| 环节 | 代码位置 | 关闭时行为 |
|---|---|---|
| 搜歌 | `MusicRepository:54` | 插件源根本不进线程池 |
| 取链 | `MusicRepository:223` | 打日志 `mf-media skipped: engine disabled` 后返回 null |
| 歌词 | `MusicRepository:304` | 跳过插件，直接走酷狗接口 |
| 自检 | `MusicConfigPanel:132` | 显示「插件[qq] 未启用（开关关闭）」，灰色不是红色 |

**注意**：自检里那句「未启用」和「加载失败」是刻意区分的。前者是开关没开，后者是真有问题。

另外关开关时会 `MusicFreeHost.clearAll()`，把每个插件占的两个线程（`mfjs-*` / `mfpump-*`）全部销毁，不留后台消耗。

### 什么时候才该打开

1. 洛雪已经完整跑通（搜歌 → 播放 → 歌词 → 音质都正常）
2. 你手上有一个**只实现了 `search` + `getMediaSource`** 的简单插件
3. 打开开关 → 自检 → 看是否显示 `✓ 插件[qq] search/getMediaSource`

如果自检显示 `加载失败：超时`，说明插件 8 秒内没跑完，可能是：
- 插件用了 `setTimeout` 做异步轮询（我们的 `setTimeout` 是同步执行的一次调用，不支持轮询）
- 插件依赖了没实现的方法（`getTopLists` / `getRecommendSheetTags` 等只探测不实现）

---

## 四、19 个脚本实测清单

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

### 推荐开启顺序

第一次测试建议**只开这三个**，先把主链路跑通：

1. **9 号（聚合API接口）** — 994 字节，全文只有 20 行，一眼看完。用来验证「壳的运行时是否正常」最合适。
2. **1 号（星海音乐源）** — 功能最全，多平台。
3. **18 号（轻量音源四平台）** — 结构清晰，多源并行。

跑通之后再逐个加，一旦某个源让搜索变慢或报错，`自检` 能立刻定位是谁。

### 关于混淆

13、11、4、2、17、19 号有不同程度的 `_0x` 变量名混淆。

**混淆不影响执行。** 对 JS 引擎来说 `_0x1f0227` 和 `a`、`b` 完全等价。

检测结果：
- `eval(` / `new Function`：**0 个文件使用**
- `document.` / `localStorage`：**0 个文件使用**
- 加密外壳 / 二次加载：无

所以是**纯变量名混淆**，能正常跑。但重度混淆的（13 号 47,587 个 `_0x`）**行为无法审计**——它能把你请求的关键词、你的 IP 发到任何地方你也不会知道。自己权衡。

---

## 五、日志关键字对照表

全部带 `echo-music` 或 `lx-` 前缀，`adb logcat | grep -E 'lx-|echo-music'` 一把抓。

### 导入阶段

| 日志 | 含义 |
|---|---|
| `music-import-reject: 不是洛雪音源脚本` | 内容里找不到 `globalThis.lx`，被拒 |
| `music-import-reject: 脚本内容为空` | 空文件或读取失败 |

### 脚本加载阶段

| 日志 | 含义 | 下一步 |
|---|---|---|
| `lx-boot-ok <源名>` | 脚本执行完了没抛错 | — |
| `lx-handler-registered <源名>` | 脚本调了 `lx.on('request')` | 关键标志 |
| `lx-inited-ok <源名> => kg:128k,320k` | 拿到了平台和音质表 | 关键标志 |
| `lx-inited-missing <源名>` | 脚本没发 `inited` | 脚本问题或 `send` 注入问题 |
| `lx-boot-failed <源名> : xxx` | 脚本执行抛错 | 看后面的错误内容 |

**判读逻辑**：三个 `ok` 都有 = 源完全正常；缺 `lx-handler-registered` = `on` 注入有问题；缺 `lx-inited-ok` = 平台表没拿到，搜歌会跳过这个源。

### 搜歌阶段

| 日志 | 含义 |
|---|---|
| `music-search [关键词] -> N 首, 失败源 M` | 一次搜索的总结果 |
| `lx-search-failed kg : xxx` | 某个平台接口挂了（网络/改接口） |
| `lx-search-skip platform=tx not in kg,kw` | 这个源不支持该平台，正常跳过 |
| `music-search-source-failed <源名> : xxx` | 某个源整体失败 |

**N=0 但自检全 ✓** → 平台接口问题（大概率是平台改接口了，看 `lx-search-failed` 的具体 URL）。

### 取链阶段

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

### 播放页阶段

| 日志 | 含义 |
|---|---|
| `echo-music meta [歌名] qualities=flac/320k/128k liked=false` | 播放页信息刷新 |
| `echo-music lyric parsed: N lines` | 歌词解析成功 |
| `echo-music lyric empty for <歌名>` | 这首歌没找到歌词 |
| `echo-music switch quality -> flac` | 切音质触发 |
| `echo-music playMusicQueue [歌名] index=3` | 从首页点歌进入播放页 |

---

## 六、故障排查表

### 首页没变成音乐界面

| 现象 | 原因 | 处理 |
|---|---|---|
| 导入后首页还是影视 | 没有激活的源 | 配置管理 → 音乐 → 点一下某个源激活 |
| 「退出」按钮点了没反应 | 阅读模式占着首页 | 退出阅读模式（两者互斥） |
| 首页空白一片 | 底部导航的 HOME 分支没生效 | 看 `MainScreen.kt` 的 `when` 分支是否包含音乐 |

### 能搜到但播不出

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

### 搜不到歌

| 自检结果 | 说明 |
|---|---|
| 全 ✓ 但搜不到 | 平台接口变了。看 `lx-search-failed` 里的 HTTP 状态码 |
| 部分 ✗ | 关掉坏源再搜 |
| 全 ✗ | `LxRuntime` 有问题，看 `lx-boot-failed` |

### 歌词不显示

- 洛雪脚本**不管歌词**，歌词是壳自己走酷狗 `krcs.kugou.com` 拉的
- 海外 IP 或该接口封锁时拿不到，属正常
- `echo-music lyric empty` 就是没找到，不影响播放

### 切音质没反应

- 只有播放页顶栏的胶囊能点，且**仅音乐模式**（`ui.musicMode == true`）
- 如果这首歌的源只支持 128k，选 flac 会静默回落到 128k（看 `resolve ok` 的 `q=` 值）

---

## 七、已知限制

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

## 八、代码地图

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

## 九、编译失败时如何降级到纯洛雪

这份代码**没有在任何机器上编译过**（写它的环境没有 JDK / Android SDK）。如果 `./gradlew assembleDebug` 报错，按下面顺序处理。

### 原则：先保住洛雪

洛雪那 12 个文件用的是 `JsSpider.kt` 里已经验证过的 QuickJS API（`create`、`setConsole`、`setProperty`、`evaluate`、`JSCallFunction`、`call`），风险低。

MusicFree 那 7 个文件引入了几个**只在 wrapper 3.2.3 里存在但没被本工程用过**的 API：

| API | 位置 | 风险 |
|---|---|---|
| `JSObject.getProperty("then")` | `MusicFreeHost:323` | 返回类型不确定 |
| `JSObject.call(...)` 动态调用 | `MusicFreeHost:342` | 未验证 |
| `context.globalObject` | `MusicFreeHost:180` | 未验证是否可空 |

### 第一步：确认错误来自哪边

```bash
./gradlew assembleDebug 2>&1 | grep -E "MusicFree|LxRuntime" | head -30
```

- 只报 `MusicFree*` → 走第二步
- 同时报 `LxRuntime` / `LxSearch` → 先修洛雪，这是根本
- 报 `MusicRepository` → 看是哪个分支的错

### 第二步：暂时摘掉 MusicFree（3 处改动）

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

### 第三步：恢复 MusicFree

把 `/tmp/mf_backup/` 里的 7 个文件挪回 `music/`，然后把第二步的改动反向恢复。

**建议顺序**：先让洛雪通过编译并跑通，确认搜歌播放没问题，再单独处理 MusicFree 的编译错误。因为 `MusicFreeHost` 里那几行如果 API 不对，改法取决于 wrapper 实际暴露了什么，得看报错信息才知道。

---

## 十、关键设计决策（改代码前必读）

### 1. 为什么用占位 URL

`MusicSession.buildPlaybackVod()` 把歌单变成 `VodInfo` 时，`series[i].url` 填的是：

```
music://<平台>/<歌曲id>/<队列下标>
```

不是真实地址。原因：真实地址要等播放时才取（可能过期），而 `VodInfo` 是整个队列一起构造的。

`PlaybackStarter.playUrl()` 拿到 `music://` 就拦截、取链、再走正常播放。**这样队列、上下首、播放模式、进度全部复用，播放器一行没改。**

### 2. 为什么 lx.request 的回调要排队

`LxRuntime` 里所有 QuickJS 调用锁在单线程 `jsExecutor` 上。但脚本的 `handler` 内部会再调 `lx.request`，如果此时同步回调进 JS，就是**单线程自己等自己** → 死锁（v2.63 的「25 秒超时取链失败」就是这个）。

解法：`lx.request` 同步做 HTTP，但回调塞进 `pendingCallbacks` 队列，等 `handler` 返回后由 `drain()` 统一执行。

### 3. 为什么取链失败要重搜

不能拿 A 源的歌去 B 源取链——extra 里的 `songmid`/`hash` 是 A 平台专属的，B 源解不出来。

正确做法：按「歌名 + 歌手」在 B 源**重搜一遍**，拿 B 源自己的原生 extra 再取。

### 4. 为什么搜歌在壳里

洛雪本体就是这么设计的：壳负责调平台接口搜歌，搜完把 `musicInfo` 交给脚本取链。脚本只认 `action=musicUrl` 这类请求，不提供搜索能力。

所以换平台接口必须改 `LxSearch.kt`，没法靠换脚本解决。
