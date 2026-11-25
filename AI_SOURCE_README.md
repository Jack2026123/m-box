# AI 辅助写源：七要素核对机制

> 对应 api/ai/SiteStructureAudit.kt 与 ui/dialog/AiAssistPanel.kt 的 3.10 改动。
> 适用场景：给一个网址，让 AI 自动写出能跑的 Python 采集源（Spider）。

---

## 一、为什么以前老跑不起来

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

## 二、现在的做法：先核对，再写源

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

## 三、七要素与六个接口的对应关系

| # | 要素 | 对应接口 | 数据字段 | 写错会怎样 |
|---|---|---|---|---|
| ① | 首页列表 | homeContent() | list[].vod_id / vod_name / vod_pic | 首页空白 |
| ② | 分类路由 | categoryContent(tid, pg) | 拼 URL 用 | 分类点进去空 |
| ③ | 筛选选项 | categoryContent(extend) | class[].filter | 筛选项点了没反应 |
| ④ | 详情字段 | detailContent(ids) | vod_name / vod_pic / vod_content | 详情页空白 |
| ⑤ | 播放线路 | detailContent() | vod_play_from（$$$ 分隔） | 线路标签没有 |
| ⑥ | 播放集数 | detailContent() | vod_play_url（# 分集，$ 分名与地址） | 集数列表空 |
| ⑦ | 播放直链 | playerContent(flag, id) | url / parse | 点播放转圈或报错 |

### 三个分隔符（最容易搞错）

```python
# 线路之间用 $$$（三个美元）
vod_play_from = "线路1$$$线路2$$$线路3"

# 线路内，集与集之间用 #（一个井号）
# 每集，集名与地址之间用 $（一个美元）
vod_play_url = "第1集$https://a.com/1.m3u8#第2集$https://a.com/2.m3u8"
```

**搞混任何一个就是 0 条。** 提示词里已经把这一段单独列为"数据格式硬规定"。

---

## 四、每项怎么判、缺了怎么补

### ① 首页列表

**判定**：先查 10 个常见 class —— module-poster-item、module-card-item、stui-vodlist__box、vodlist__thumb、public-list-box、thumb-txt-cor、poster-item、card-item、video-item、list-item，命中 3 个以上算找到。兜底再找“同时含 class 和详情链接”的重复 li/div 块。

**缺了怎么办**

```
抓首页，找出包住每个影片卡片的重复 class，
形如 <div class="xxx"><a href="/vod/123.html">，写进 soup.select()
```

### ② 分类路由

**判定**：首页 href 含 /vodshow/、/vodtype/、/vod/show、/type/、/frim/、cates-N 之一。

**缺了怎么办**

```
抓首页导航栏，找「电影/电视剧/动漫/综艺」的真实 href，数字换 {tid}，页码换 {page}
```

### ③ 筛选选项

**判定**：从分类页提取筛选链接组。按“相对当前 URL 改动的字符区间”分组，不假设短横/斜杠/参数形状。

**缺了怎么办**

```
该站可能没筛选。class 里不要带 filter 字段，extend 参数忽略。不要编造筛选项
```

> 这一项**允许缺失**。很多站确实没有筛选栏，硬编就是假数据。

### ④ 详情字段

**判定**：同时检查 h1 标题、封面 img（data-original 或 src）。

**缺了怎么办**

```
抓一个真实详情页，确认 h1 标题与封面 img 的真实 class；
vod_id 从详情 URL 正则取数字，不要从页面文字取
```

> vod_id 一定要从 URL 取。从页面文字取，遇到同名影片就串了。

### ⑤ 播放线路

**判定**：找 .play-title、.nav-tabs、.module-tab-item、.play-from、.source、.line 容器里的文字；兜底找“线路1/线路2/wjm3u8”。

**缺了怎么办**

```
线路名一般在 .play-title / .nav-tabs / .module-tab-item 里。vod_play_from 用 $$$ 分隔
```

### ⑥ 播放集数

**判定**：统计详情页里 href 含 play、/p/、vodplay 的 a 标签数量。

**缺了怎么办**

```
剧集链接形如 <a href="/vod/play/123-1-1.html">第1集</a>。
拼成 第1集$地址#第2集$地址（# 分集，$ 分名与地址）
```

### ⑦ 播放直链

**判定**：播放页里正则找 m3u8 / mp4 / flv。

**缺了怎么办**（分两种）

| 情况 | 补法 |
|---|---|
| 播放页有 iframe | 再请求 iframe 的 src，从结果正则抠 m3u8；抠不到就 return parse=1 且 url 填 iframe 地址 |
| 播放页无直链 | playerContent 返回 parse=1 且 url 填播放页地址，交 App 内置解析器 |

> parse=1 表示“这是解析器页，让 App 自己去解”。很多站都靠这个，不是失败。

---

## 五、日志怎么看

全在 AI 辅助面板底部的“任务状态”里。

### 正常流程

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

### 核对没满时

```
结构核对：【结构核对 5/7】
```

说明有 2 项没找到，模型会按提示去补，日志里能看到它调 fetch_url。

### 核对数值对应什么排查方向

这是**最有用的一张表**。看到 `x/7` 里的 x 是多少，就知道该往哪里查。

| 核对结果 | 说明 | 该查哪里 |
|---|---|---|
| **0~2/7** | 几乎什么都没抓到 | **先查网络，不要改代码**。浏览器能打开吗？要代理吗？站点是不是 SPA？ |
| **3~4/7** | 抓到了首页，但分类/详情/播放打不开 | 查分类页和详情页的 URL 是否拼对（`absUrl()` 有没有漏） |
| **5~6/7** | 只差一两项 | 正常情况，模型能补上。若卡住不动，看它反复调 fetch_url 抓哪个页面 |
| **7/7** | 全齐 | 接下来若仍 0 条，是渲染 HTML ≠ Python 实际拿到的内容，见下方 |

#### 0~2/7：先去查网络，别改代码

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

#### 3~4/7：URL 拼错了

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

#### 5~6/7：模型在补，但可能补不到

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

#### 7/7 但仍然 0 条：渲染 HTML ≠ Python 实际拿到的

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

### 引擎问题（新加）

```
无法真跑验证：Android 16+ 的 32 位进程不支持 Python 引擎，无法真跑验证。
源码已生成（未真跑验证），保存到目标文件夹
可运行源码已存：/sdcard/.../xxx_未验证.py
```

**这类情况源码是好的，只是没法在本机验证。** 换 64 位设备或在电脑上跑就能确认。

### 值得警惕的信号

| 日志 | 含义 |
|---|---|
| 已修 3 轮仍未通过，仍保存最后一版源码 | 到熔断轮数了，源码在 xxx_未通过_第3轮.py |
| 第 N 轮修复未改动基线，停止修复 | AI 改不动了，通常是信息不足 |
| Agent 写源未产出可用代码，返回起点骨架 | 8 回合内没交出完整代码 |
| 同一错误连续 3 次，停止写源 | 错误指纹相同，再改也没用 |

---

## 六、静态校验现在给的提示

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

### 会被拦下的模块

cloudscraper、selenium、playwright、pyquery、scrapy、pandas、numpy

这些在 App 的 Python 环境里没有，import 就抛 ModuleNotFoundError。以前这个异常会被吞成“0 条”，现在提前拦下并说明替代方案。

---

## 七、真跑验证的四个阶段

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

### __ENGINE__ 前缀

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

## 八、给模型的要求（提示词核心）

### 强制执行顺序

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

### 数据格式硬规定

```python
# homeContent
{"class": [{"type_id": "1", "type_name": "电影"}],
 "list": [{"vod_id": "/voddetail/123.html", "vod_name": "片名", "vod_pic": "封面"}]}

# categoryContent
{"list": [...], "page": pg, "pagecount": 9999, "limit": 20, "total": 9999}

# detailContent
{"list": [{"vod_id": "id", "vod_name": "名", "vod_pic": "图", "vod_content": "简介",
           "vod_play_from": "线路1$$$线路2",
           "vod_play_url": "第1集$地址1#第2集$地址2$$$线路2第1集$地址"}]}

# playerContent —— 直接拿到直链
{"parse": 0, "playUrl": "", "url": "m3u8地址", "header": {}}

# playerContent —— 解析器页
{"parse": 1, "playUrl": "", "url": "播放页地址"}
```

---

## 九、已知限制

| 限制 | 说明 |
|---|---|
| 只支持 HTML 站和标准采集接口 | SPA 站靠 WebView 渲染兜底，渲染不出来就抓不到 |
| iframe 嵌套解析器拿不到直链 | 用 parse=1 交给 App 内置解析，成功率取决于内置解析器 |
| 七要素是静态正则判定 | 极端自定义结构的站可能判不出来，会标 [缺] 让模型去查 |
| 真跑依赖本机 Python 引擎 | Android 16+ 32 位进程不支持，只能生成不能验证 |
| 筛选栏判定不保证准确 | 只按“改动的字符区间”分组，复杂筛选可能分错组 |

---

## 十、代码位置

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
