AVBox 2.38 源码（已修 v2.42 — 建 source 改为真·AI 驱动）
========================================================

本包是完整可编译源码，解压后直接 ./gradlew assembleDebug 编译。

核心改动：写源工坊不再套模板，改为 AI 看本站真实 HTML 写源
----------------------------------------------------------

旧行为（你批评的问题）：
  命中 MacCMS -> 直接套写死的模板（myui/module/stui）
  模板是"猜的结构"，遇到对不上的站就 0 条。

新行为（本次）：
  配了 AI 模型 -> Agent 直接读本站真实 HTML 片段写源
    - reconSite 抓首页/分类/详情/播放页真实 HTML
    - JS 渲染站自动用无头 WebView 执行 JS 后再抓
    - Agent 照真实 class/路由写选择器，禁止套固定模板
    - 写完 run_six_api() 真跑六接口验证
    - 某接口 0 条 -> 带实际抓到的 HTML 回喂 AI 只改那一项 -> 重跑
    - 最多 3 轮，仍失败输出阻塞报告（不伪造数据）
  没配模型 -> 退回模板兜底（module/stui/myui/骨架），聊胜于无

已验证分诊（5 个站，配模型时全部走 AI 路径）：
  fmria.com   (module-item)      -> AI 写源
  wangfei.tv  (module-poster)    -> AI 写源
  cnsq114.com (stui-vodlist)     -> AI 写源
  典型 myui 站                   -> AI 写源
  完全未知站                     -> AI 写源

模板仍保留，但降为「没配模型」时的兜底。

改动文件（相对原始 2.38）
------------------------
  ui/dialog/AiAssistPanel.kt   建源改 AI 驱动 + 主题分诊 + 三个模板
  api/ai/JsRender.kt           新增：无头 WebView 渲染器（JS 站）
  其余文件未动

编译
----
JDK 21 + Android SDK(compileSdk 37/NDK 27.2) + Kotlin 2.4。
签名密码是占位符，签名文件未含：debug 可直接编，
release 需自补 .key/app-release.jks 并改 gradle.properties 密码。

    ./gradlew assembleDebug     # 先编 debug
    ./gradlew assembleRelease   # 正式包

前提
----
AI 写源必须先配模型：App 内 AI辅助 -> AI模型 -> 填 Base URL / Key / 模型。
支持 OpenAI 兼容接口（可填国内中转）。默认 gpt-4o-mini。

使用
----
写源工坊 -> 填网址 -> 选文件夹 -> 点「生成 py 源」。
日志会显示：AI 写源模式 -> Agent 工具调用 -> 真跑六接口 -> 已保存。

JS 渲染站自动走无头 WebView（日志显示"改用无头 WebView 渲染"）。

轮次自定义（v2.43 新增）
------------------------
AI辅助 -> AI模型 -> 修复轮次：1-20 可自定义，默认 3。
控制 Agent 写源工具循环 + 真跑失败后的修复轮数。
代码：AiConfig.kt MAX_ROUNDS=20，AiRepairSettingsDialog 输入框。

生成的 py 源自带 __main__ 测试块
----------------------------------------
每个生成的 .py 末尾都带 if __name__ == "__main__": 测试块，
依次打印六接口结果。你可以把单个 .py 拷到 PC 上独立运行验证，
不用依赖 App。运行前装依赖：pip install requests lxml

真跑验证
--------
wangfei.tv（改后模板抽出 py 源实跑）：
  首页 96 / 分类 64 / 翻页 64 / 详情正常 / 搜索 9 全 PASS。
fmria.com（你给的源风格）：侦察识别为 module-item，走 AI 路径。

注意
----
1. AI 写源质量取决于模型能力，弱模型可能仍写错选择器（但会真跑暴露）。
2. JS 站渲染慢 1.5~3 秒（只 SPA 站触发）。
3. 需登录/滑块/强加密站可能失败，参考技能包 shield_routing 等策略。
4. 语法校验用 kotlinc 1.3，对 Kotlin 2.x 报 1 个误报（原始同样报）。

回退
----
本包已剔除 .bak 备份（省体积）。需要原始未改的 AiAssistPanel.kt，
可从官方 2.38 原包取。本次改动集中在 AiAssistPanel.kt + 新增 JsRender.kt。
