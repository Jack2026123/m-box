package com.github.tvbox.osc.ui.music

import androidx.compose.ui.graphics.Color

// 2.90 第 5 项 5A：词级动效模板（见 research_notes/folia-290-implementation-plan-20261113/parts/07-word-level-templates.md）
// 15 款：流光（默认，2.80 现状原样）/ 青澜（青绿流体 + 半调圆点）/ 星图（深色 + 几何线条）/ 霓虹（无底色、已唱字赤橙黄绿青蓝紫七色轮换，2.96 去黑框底色）/
// 演唱会 / 剧院 / 星空舞台（2.98 舞台三款；2.99 改成镜台式全屏 3D 舞台：黑底星空 + 立体点块 + 透视歌词，歌词区不再画光束）/
// 气泡 / 云阶 / 空间画布 / 雨天 / 四季 / 雾笺 / 町景 / 商籁（2.99 补 ICHIGOMUSIC 11 款里缺的 8 款，各做可认的简化版，见各款注释）。
// 模板只参数化“背景色块、装饰、字号/旋转、填色与辉光”四层；切词、扫字填充、平滑时钟一律不动。
// 辉光不在这里画：模板只提供 glowColors/glowBlurPx，辉光由独立第三遍辉光层画（字本身永远 shadow = null，字一律不加阴影）。
// 2.99：layout 版式字段——"scatter" 散词（流光/星图/霓虹）/ "waves" 青澜有序行（3.01，不再散词）/ "bubble" 气泡 / "steps" 云阶 / "depth" 空间画布 / "rain" 雨天 /
// "siji" 四季 / "mist" 雾笺 / "sign" 町景 / "magazine" 商籁；行版式 8 款 + 青澜走 lineSlotsFor 横排词流，不走散词格子。
// 3.01：12 款各有不同的排版或运动逻辑——词级动画（光流/水波/星点连线/整词点亮/分段通电）与换行位移（阶梯上滑/纵深推拉/翻页横滑）
// 由渲染器按模板 id 分支画，不再共用一套逐词填色只换背景；舞台三款（id 4–6）不动。

enum class DecorKind { HEART, FLOWER, GRID, CIRCLE, PLUS, HEXAGON }

data class LyricVisualTemplate(
    val id: Int, // 0 流光 / 1 青澜 / 2 星图 / 3 霓虹 / 4 演唱会 / 5 剧院 / 6 星空舞台 / 7 气泡 / 8 云阶 / 9 空间画布 / 10 雨天 / 11 四季 / 12 雾笺 / 13 町景 / 14 商籁
    val name: String, // 设置面板显示名
    val kvId: String, // KV 短 id：liuguang / qinglan / xingtu / nihong / yanchanghui / juyuan / xingkongwutai / qipao / yunjie / kongjian / xiayu / siji / wujian / tingjing / shanglai
    // 版式（2.99）：散词 "scatter"（流光/星图/霓虹）；行版式——"waves" 青澜有序行（3.01，不再散词）/ "bubble" 气泡 / "steps" 云阶 / "depth" 空间画布 / "rain" 雨天 / "siji" 四季 / "mist" 雾笺 / "sign" 町景 / "magazine" 商籁
    val layout: String = "scatter",
    // 背景色块
    val backgroundWash: Color?, // 底色洗白：2.96 起全模板 null（霓虹的近黑底已去掉，不再盖实心黑框）
    val blobCount: Int, // 流光/青澜/星图 3 团；霓虹 0 团（近黑底干净，不画色块）
    val blobHueShifts: List<Float>, // 每团相对基准的色相偏移（度）；固定色相模板里基准就是 blobFixedHue
    val blobFixedHue: Float?, // 非空 = 强制色相段中心（青澜 160 青绿），空 = 跟 seed 走
    val blobAlphas: List<Float>,
    val blobRadii: List<Float>, // 相对 r（min(宽, 高)）的倍数
    val blobFlow: Boolean, // 流动开关（圆心正弦漂移）
    val blobFlowAmplitude: Float, // 漂移振幅（宽/高分数）
    val blobFlowPeriodSec: Float, // 漂移周期（秒）
    // 3.01：色块圆心区域——每团 [x 起点, x 跨度, y 起点, y 跨度]；默认 2.82 三区（左上/右下/中上），
    // 8 款新模板各配不同区域，圆圈位置不再全一样；blobCount 超出区域数时取最后一区
    val blobZones: List<FloatArray> = listOf(
        floatArrayOf(0.20f, 0.15f, 0.20f, 0.15f),
        floatArrayOf(0.60f, 0.20f, 0.55f, 0.20f),
        floatArrayOf(0.45f, 0.20f, 0.10f, 0.12f),
    ),
    // 半调圆点
    val halftone: Boolean,
    val halftoneSpacingDp: Float, // 12f
    val halftoneDotMinDp: Float, // 1f
    val halftoneDotMaxDp: Float, // 3.5f
    val halftoneAlpha: Float, // 上限 0.18f（下限 0.10f，见 drawHalftone）
    // 装饰
    val decorKinds: Set<DecorKind>,
    val decorCount: Int, // 取 spots 前 N 个
    val decorAlpha: Float,
    val decorSizeMinDp: Int,
    val decorSizeMaxDp: Int,
    // 词（字号/旋转由 slotsFor 消费，Item 3；这里只定表）
    val sizeMinSp: Float,
    val sizeMaxSp: Float,
    val rotMaxDeg: Float,
    val fillAlpha: Float, // 已唱字 0.95
    val baseAlpha: Float, // 未唱字
    val glowColors: List<Color>, // 辉光字色表：空 = 无辉光；青澜 = [绿, 粉]；星图 = [暖金]；霓虹 = 赤橙黄绿青蓝紫七色（按词下标轮换，2.96）
    val glowBlurPx: Float, // 0 / 14–24（霓虹 28 强辉光）
    val fillBold: Boolean, // 已唱字是否全程粗体（星图/霓虹 true，其余 false）
    // 舞台射灯（2.98 三款舞台模板）：光束色表，空 = 无射灯（其余四款）；每色一道光锥，从顶部打下来、左右扫动
    val spotlightColors: List<Color> = emptyList(),
    val spotlightSweepAmp: Float = 0.08f, // 扫动幅度（宽分数）
    val spotlightSweepPeriodSec: Float = 6f, // 扫动周期（秒）
    val spotlightAlpha: Float = 0.16f, // 光束顶端透明度（向底部渐隐）
) {
    /** 装饰线条颜色：霓虹一律青（#00E5FF），剧院暖金，其余白色（alpha 另由 decorAlpha 定）。 */
    val decorColor: Color
        get() = when (id) {
            3 -> Color(0xFF00E5FF)
            5 -> Color(0xFFFFD98A)
            else -> Color.White
        }

    /**
     * 模板填色（自动档，已唱字）：返回 null 表示走流光原有逻辑（词色/主色 + 撞色回退）。
     * 手动档（白/黑/主题色）永远优先于模板；AI wordColors 只在流光生效。
     */
    fun templateFillColor(wordIndex: Int, surfaceLight: Boolean, lineIndex: Int = 0): Color? = when (id) {
        // 青澜：已唱字按词下标奇偶交替取绿/粉
        1 -> glowColors.getOrNull(wordIndex % 2)?.copy(alpha = fillAlpha)
        // 星图：深底暖金、浅底白
        2 -> (if (surfaceLight) Color.White else glowColors.firstOrNull() ?: Color(0xFFFFD98A)).copy(alpha = fillAlpha)
        // 霓虹（3.01）：七色按行轮换（同行一色），不同时闪烁；整词点亮由渲染器做
        3 -> glowColors.getOrNull(lineIndex % glowColors.size)?.copy(alpha = fillAlpha)
        // 演唱会（2.98）：已唱字按词下标轮换红/蓝/紫三色射灯色，配辉光
        4 -> glowColors.getOrNull(wordIndex % glowColors.size)?.copy(alpha = fillAlpha)
        // 剧院（2.98）：已唱字暖金
        5 -> (glowColors.firstOrNull() ?: Color(0xFFFFD98A)).copy(alpha = fillAlpha)
        // 星空舞台（2.98）：已唱字冷白
        6 -> (glowColors.firstOrNull() ?: Color(0xFFE8F4FF)).copy(alpha = fillAlpha)
        // 町景（2.99）：已唱字按词下标轮换霓虹粉/青/金三色，配辉光（LED 招牌感）
        13 -> glowColors.getOrNull(wordIndex % glowColors.size)?.copy(alpha = fillAlpha)
        else -> null
    }

    /** 模板底色（自动档，未唱字）：返回 null 表示走原有逻辑（浅底黑/深底白，alpha 取 baseAlpha）。 */
    fun templateBaseColor(surfaceLight: Boolean): Color? = when (id) {
        // 霓虹：淡青白，不跟主题 surface 深浅走——底色是近黑洗白，浅底黑字会糊在黑底上
        3 -> Color(0xFFD6F6FF).copy(alpha = baseAlpha)
        // 雾笺（2.99）：未唱字低对比柔和（像写在雾气上），深浅底都只用 baseAlpha（0.42）
        12 -> (if (surfaceLight) Color.Black else Color.White).copy(alpha = baseAlpha)
        else -> null
    }

    companion object {
        // 0 流光：2.80 现状原样——三团 alpha 0.15/0.12/0.08、半径 0.55/0.45/0.40r、色相 +40°/+180°、
        // 无流动、无半调、5 种装饰 7 点、alpha 0.16、20–47dp、18–34sp、±12°、无辉光。
        val LiuGuang = LyricVisualTemplate(
            id = 0,
            name = "流光",
            kvId = "liuguang",
            backgroundWash = null,
            blobCount = 3,
            blobHueShifts = listOf(0f, 40f, 180f),
            blobFixedHue = null,
            blobAlphas = listOf(0.15f, 0.12f, 0.08f),
            blobRadii = listOf(0.55f, 0.45f, 0.40f),
            blobFlow = false,
            blobFlowAmplitude = 0.04f,
            blobFlowPeriodSec = 8f,
            halftone = false,
            halftoneSpacingDp = 12f,
            halftoneDotMinDp = 1f,
            halftoneDotMaxDp = 3.5f,
            halftoneAlpha = 0.18f,
            decorKinds = setOf(DecorKind.HEART, DecorKind.FLOWER, DecorKind.GRID, DecorKind.CIRCLE, DecorKind.PLUS),
            decorCount = 7,
            decorAlpha = 0.16f,
            decorSizeMinDp = 20,
            decorSizeMaxDp = 47,
            sizeMinSp = 18f,
            sizeMaxSp = 34f,
            rotMaxDeg = 12f,
            fillAlpha = 0.95f,
            baseAlpha = 0.55f,
            glowColors = emptyList(),
            glowBlurPx = 0f,
            fillBold = false,
        )

        // 1 青澜（3.01 重构）：不再散词——改成有序的两三行歌词（layout "waves"，行版式横排词流）；当前词唱到时从下方扩散一圈浅青水波（渲染器画，整行水波）。
        // 色相强制落青绿段（160°±15° 抖动），偏移 +25°/+60°（无 +180° 补色），alpha 0.22/0.18/0.12、半径 0.60/0.50/0.45r、流动开；半调点阵在两角区；装饰只留圆圈 + 少量十字，4 点；
        // 20–38sp、不旋转；已唱字绿/粉交替 + 辉光（blur 18–24px）；未唱字白 0.55。
        val QingLan = LyricVisualTemplate(
            id = 1,
            name = "青澜",
            kvId = "qinglan",
            layout = "waves",
            backgroundWash = null,
            blobCount = 3,
            blobHueShifts = listOf(0f, 25f, 60f),
            blobFixedHue = 160f,
            blobAlphas = listOf(0.22f, 0.18f, 0.12f),
            blobRadii = listOf(0.60f, 0.50f, 0.45f),
            blobFlow = true,
            blobFlowAmplitude = 0.04f,
            blobFlowPeriodSec = 8f,
            halftone = true,
            halftoneSpacingDp = 12f,
            halftoneDotMinDp = 1f,
            halftoneDotMaxDp = 3.5f,
            halftoneAlpha = 0.18f,
            decorKinds = setOf(DecorKind.CIRCLE, DecorKind.PLUS),
            decorCount = 4,
            decorAlpha = 0.14f,
            decorSizeMinDp = 20,
            decorSizeMaxDp = 36,
            sizeMinSp = 20f,
            sizeMaxSp = 38f,
            rotMaxDeg = 14f,
            fillAlpha = 0.95f,
            baseAlpha = 0.55f,
            glowColors = listOf(Color(0xFF7CFFB2), Color(0xFFFF9ECF)),
            glowBlurPx = 21f,
            fillBold = false,
        )

        // 2 星图（3.01 重构）：词语对应小星点，按阅读顺序逐个点亮，细线逐段连接（渲染器画，文字始终在连线前方）；移除爱心、花朵装饰（只留圆圈/十字/网格/六边形）。
        // 深色 + 几何线条——色块压暗 alpha 0.08/0.06/0.05，色相 +40°/+180° 跟种子走，半径同现状，无流动；
        // 装饰 7 点，4 种（圆圈/十字/网格/六边形），白 alpha 0.20、24–56dp；22–40sp、±12°；已唱字全程 Bold，
        // 深底暖金 / 浅底白 + 辉光（blur 14–20px）；未唱字白 0.50。
        val XingTu = LyricVisualTemplate(
            id = 2,
            name = "星图",
            kvId = "xingtu",
            backgroundWash = null,
            blobCount = 3,
            blobHueShifts = listOf(0f, 40f, 180f),
            blobFixedHue = null,
            blobAlphas = listOf(0.08f, 0.06f, 0.05f),
            blobRadii = listOf(0.55f, 0.45f, 0.40f),
            blobFlow = false,
            blobFlowAmplitude = 0.04f,
            blobFlowPeriodSec = 8f,
            halftone = false,
            halftoneSpacingDp = 12f,
            halftoneDotMinDp = 1f,
            halftoneDotMaxDp = 3.5f,
            halftoneAlpha = 0.18f,
            decorKinds = setOf(DecorKind.CIRCLE, DecorKind.PLUS, DecorKind.GRID, DecorKind.HEXAGON),
            decorCount = 7,
            decorAlpha = 0.20f,
            decorSizeMinDp = 24,
            decorSizeMaxDp = 56,
            sizeMinSp = 22f,
            sizeMaxSp = 40f,
            rotMaxDeg = 12f,
            fillAlpha = 0.95f,
            baseAlpha = 0.50f,
            glowColors = listOf(Color(0xFFFFD98A)),
            glowBlurPx = 17f,
            fillBold = true,
        )

        // 3 霓虹（2.91 新增，替换 2.90 第 4 款；2.96 去黑框底色；3.01 重构：整词点亮——当前词整体渐亮，经过时短促辉光，唱完回稳定亮度；七色按行轮换（同行一色），不同时闪烁）。
        // 无底色（backgroundWash null，字直接浮在播放页底上）；0 团色块（底干净，不画色块）；装饰取前 6 点，3 种（圆圈描边/十字/细线网格），霓虹青 alpha 0.30、24–56dp；
        // 20–38sp、±10°；已唱字全程 Bold，强辉光（blur 28px）；未唱字淡青白 0.55。
        val NiHong = LyricVisualTemplate(
            id = 3,
            name = "霓虹",
            kvId = "nihong",
            backgroundWash = null,
            blobCount = 0,
            blobHueShifts = emptyList(),
            blobFixedHue = null,
            blobAlphas = emptyList(),
            blobRadii = emptyList(),
            blobFlow = false,
            blobFlowAmplitude = 0.04f,
            blobFlowPeriodSec = 8f,
            halftone = false,
            halftoneSpacingDp = 12f,
            halftoneDotMinDp = 1f,
            halftoneDotMaxDp = 3.5f,
            halftoneAlpha = 0.18f,
            decorKinds = setOf(DecorKind.CIRCLE, DecorKind.PLUS, DecorKind.GRID),
            decorCount = 6,
            decorAlpha = 0.30f,
            decorSizeMinDp = 24,
            decorSizeMaxDp = 56,
            sizeMinSp = 20f,
            sizeMaxSp = 38f,
            rotMaxDeg = 10f,
            fillAlpha = 0.95f,
            baseAlpha = 0.55f,
            glowColors = listOf(
                Color(0xFFFF3131),
                Color(0xFFFF8C00),
                Color(0xFFFFE600),
                Color(0xFF39FF14),
                Color(0xFF00E5FF),
                Color(0xFF2979FF),
                Color(0xFFB967FF),
            ),
            glowBlurPx = 28f,
            fillBold = true,
        )

        // 4 演唱会（2.98 舞台三款之首，对标网易云神光的彩色射灯）：三道彩色射灯（红/蓝/紫）从顶部打下来、快扫（周期 4 秒、幅度 0.12 宽）；
        // 3.06：LED 巨幕放大到 0.08–0.78 高、歌词贴在屏内（行距收紧），彩带横向收进 0.08–0.92、不再贴左边缘。
        // 0 团色块、无半调、无装饰（光束就是装饰）；20–38sp、±10°；已唱字按词下标轮换红/蓝/紫三色全程 Bold，辉光（blur 24px）；未唱字白 0.55。
        val YanChangHui = LyricVisualTemplate(
            id = 4,
            name = "演唱会",
            kvId = "yanchanghui",
            backgroundWash = null,
            blobCount = 0,
            blobHueShifts = emptyList(),
            blobFixedHue = null,
            blobAlphas = emptyList(),
            blobRadii = emptyList(),
            blobFlow = false,
            blobFlowAmplitude = 0.04f,
            blobFlowPeriodSec = 8f,
            halftone = false,
            halftoneSpacingDp = 12f,
            halftoneDotMinDp = 1f,
            halftoneDotMaxDp = 3.5f,
            halftoneAlpha = 0.18f,
            decorKinds = emptySet(),
            decorCount = 0,
            decorAlpha = 0.16f,
            decorSizeMinDp = 20,
            decorSizeMaxDp = 47,
            sizeMinSp = 20f,
            sizeMaxSp = 38f,
            rotMaxDeg = 10f,
            fillAlpha = 0.95f,
            baseAlpha = 0.55f,
            glowColors = listOf(
                Color(0xFFFF3131),
                Color(0xFF2979FF),
                Color(0xFFB967FF),
            ),
            glowBlurPx = 24f,
            fillBold = true,
            spotlightColors = listOf(
                Color(0xFFFF3131),
                Color(0xFF2979FF),
                Color(0xFFB967FF),
            ),
            spotlightSweepAmp = 0.12f,
            spotlightSweepPeriodSec = 4f,
            spotlightAlpha = 0.16f,
        )

        // 5 剧院（2.98 舞台三款之二）：3.05 改成一道暖金追光从顶部打下来、慢摆（周期 9 秒、幅度 0.10 宽），稳重优雅，不再是两道扫射；
        // 3.06：整体提亮——底色/幕布/地板/追光光晕全部提亮一档。
        // 0 团色块、无半调；装饰只留暖金圆圈 4 点、alpha 0.20；20–38sp、±10°；已唱字暖金全程 Bold，辉光（blur 20px）；未唱字白 0.55。
        // 布景：红幕布两边框住 + 舞台地板在字下 + 脚灯；歌词平视大字（×1.08），不再 3D 倾斜。
        val JuYuan = LyricVisualTemplate(
            id = 5,
            name = "剧院",
            kvId = "juyuan",
            backgroundWash = null,
            blobCount = 0,
            blobHueShifts = emptyList(),
            blobFixedHue = null,
            blobAlphas = emptyList(),
            blobRadii = emptyList(),
            blobFlow = false,
            blobFlowAmplitude = 0.04f,
            blobFlowPeriodSec = 8f,
            halftone = false,
            halftoneSpacingDp = 12f,
            halftoneDotMinDp = 1f,
            halftoneDotMaxDp = 3.5f,
            halftoneAlpha = 0.18f,
            decorKinds = setOf(DecorKind.CIRCLE),
            decorCount = 4,
            decorAlpha = 0.20f,
            decorSizeMinDp = 20,
            decorSizeMaxDp = 36,
            sizeMinSp = 20f,
            sizeMaxSp = 38f,
            rotMaxDeg = 10f,
            fillAlpha = 0.95f,
            baseAlpha = 0.55f,
            glowColors = listOf(Color(0xFFFFD98A)),
            glowBlurPx = 20f,
            fillBold = true,
            spotlightColors = listOf(
                Color(0xFFFFD98A),
                Color(0xFFFFF6E0),
            ),
            spotlightSweepAmp = 0.05f,
            spotlightSweepPeriodSec = 9f,
            spotlightAlpha = 0.28f, // 3.06：追光做亮（0.18→0.28）
        )

        // 6 星空舞台（2.98 舞台三款之三）：两道冷白射灯（冷白/浅青）从顶部打下来、中速扫（周期 6 秒、幅度 0.08 宽）；
        // 3.06：点块收拢到四角/边缘、星轨与银河 alpha 降一半，不再压歌词。
        // 3.47：按用户发的歌词视频改成暖光歌词夜——暗底 + 3 团暖橙光晕（固定色相 35°，慢漂）；
        // 装饰全 6 种细线（爱心/花/格/圆/加号/六边形，12 点）；大号亮白发光字（24–46sp，辉光 24px）；
        // 射灯改暖金/暖橙；未唱字白 0.55。
        val XingKongWuTai = LyricVisualTemplate(
            id = 6,
            name = "星空舞台",
            kvId = "xingkongwutai",
            backgroundWash = null,
            blobCount = 3,
            blobHueShifts = listOf(0f, 0f, 0f),
            blobFixedHue = 35f,
            blobAlphas = listOf(0.20f, 0.15f, 0.10f),
            blobRadii = listOf(0.60f, 0.50f, 0.42f),
            blobFlow = true,
            blobFlowAmplitude = 0.05f,
            blobFlowPeriodSec = 10f,
            halftone = false,
            halftoneSpacingDp = 12f,
            halftoneDotMinDp = 1f,
            halftoneDotMaxDp = 3.5f,
            halftoneAlpha = 0.18f,
            decorKinds = setOf(DecorKind.HEART, DecorKind.FLOWER, DecorKind.GRID, DecorKind.CIRCLE, DecorKind.PLUS, DecorKind.HEXAGON),
            decorCount = 12,
            decorAlpha = 0.30f,
            decorSizeMinDp = 16,
            decorSizeMaxDp = 36,
            sizeMinSp = 24f,
            sizeMaxSp = 46f,
            rotMaxDeg = 10f,
            fillAlpha = 0.95f,
            baseAlpha = 0.55f,
            glowColors = listOf(Color(0xFFFFFFFF)),
            glowBlurPx = 24f,
            fillBold = true,
            spotlightColors = listOf(
                Color(0xFFFFD98A),
                Color(0xFFFFB74D),
            ),
            spotlightSweepAmp = 0.08f,
            spotlightSweepPeriodSec = 6f,
            spotlightAlpha = 0.16f,
        )

        // 7 气泡（3.01 重构）：背景是满屏上升的气泡群——许多大小不一的透明气泡从底部缓缓上升、左右轻摆、到顶渐隐；
        // 歌词直接排在背景上，不套气泡框；偶数行靠左、奇数行靠右（像聊天），字放大 1.08 倍。0 团色块、无半调、无装饰；20–32sp、不旋转；已唱字走默认（主色），无词辉光。
        val QiPao = LyricVisualTemplate(
            id = 7,
            name = "气泡",
            kvId = "qipao",
            backgroundWash = null,
            blobCount = 0,
            blobHueShifts = listOf(0f, 30f, 60f),
            blobFixedHue = null,
            blobAlphas = listOf(0.14f, 0.11f, 0.08f),
            blobRadii = listOf(0.55f, 0.45f, 0.40f),
            blobFlow = true,
            blobFlowAmplitude = 0.04f,
            blobFlowPeriodSec = 8f,
            blobZones = listOf(floatArrayOf(0.65f, 0.15f, 0.15f, 0.15f), floatArrayOf(0.10f, 0.15f, 0.60f, 0.15f)),
            halftone = false,
            halftoneSpacingDp = 12f,
            halftoneDotMinDp = 1f,
            halftoneDotMaxDp = 3.5f,
            halftoneAlpha = 0.18f,
            decorKinds = emptySet(),
            decorCount = 0,
            decorAlpha = 0.16f,
            decorSizeMinDp = 20,
            decorSizeMaxDp = 47,
            sizeMinSp = 20f,
            sizeMaxSp = 32f,
            rotMaxDeg = 0f,
            fillAlpha = 0.95f,
            baseAlpha = 0.55f,
            glowColors = emptyList(),
            glowBlurPx = 0f,
            fillBold = false,
            layout = "bubble",
        )

        // 8 云阶（3.01 重构）：背景是横向漂移的云团——几团由椭圆拼成的云形左右缓慢漂移；歌词按行组成阶梯（行按 (index % 4) * 24dp 逐级右缩进，字号随阶梯略降），切行时视觉重心沿阶梯平稳上移；
        // 0 团色块、无半调、无装饰；20–34sp、不旋转；已唱字走默认（主色），无词辉光。
        val YunJie = LyricVisualTemplate(
            id = 8,
            name = "云阶",
            kvId = "yunjie",
            backgroundWash = null,
            blobCount = 0,
            blobHueShifts = listOf(180f, 210f, 240f, 270f),
            blobFixedHue = null,
            blobAlphas = listOf(0.13f, 0.10f, 0.07f, 0.06f),
            blobRadii = listOf(0.55f, 0.45f, 0.40f, 0.35f),
            blobFlow = true,
            blobFlowAmplitude = 0.04f,
            blobFlowPeriodSec = 10f,
            blobZones = listOf(floatArrayOf(0.10f, 0.15f, 0.10f, 0.12f), floatArrayOf(0.65f, 0.15f, 0.10f, 0.12f), floatArrayOf(0.10f, 0.15f, 0.65f, 0.12f), floatArrayOf(0.65f, 0.15f, 0.65f, 0.12f)),
            halftone = false,
            halftoneSpacingDp = 12f,
            halftoneDotMinDp = 1f,
            halftoneDotMaxDp = 3.5f,
            halftoneAlpha = 0.18f,
            decorKinds = emptySet(),
            decorCount = 0,
            decorAlpha = 0.16f,
            decorSizeMinDp = 20,
            decorSizeMaxDp = 47,
            sizeMinSp = 20f,
            sizeMaxSp = 34f,
            rotMaxDeg = 0f,
            fillAlpha = 0.95f,
            baseAlpha = 0.55f,
            glowColors = emptyList(),
            glowBlurPx = 0f,
            fillBold = false,
            layout = "steps",
        )

        // 9 空间画布（3.01 重构）：背景是闪烁的星空——满天小星点明灭闪烁，偶尔一道流星拖着细线划过；歌词景深透视——当前行最大居中（字号取上限 ×1.05、全程 Bold），上下行按距离缩小变淡，切行时平滑推进；
        // 0 团色块、无半调、无装饰；24–40sp、不旋转；已唱字走默认（主色），无词辉光。
        val KongJian = LyricVisualTemplate(
            id = 9,
            name = "空间画布",
            kvId = "kongjian",
            backgroundWash = null,
            blobCount = 0,
            blobHueShifts = listOf(240f, 270f, 300f, 330f, 0f),
            blobFixedHue = null,
            blobAlphas = listOf(0.10f, 0.08f, 0.06f, 0.05f, 0.05f),
            blobRadii = listOf(0.60f, 0.50f, 0.40f, 0.35f, 0.30f),
            blobFlow = true,
            blobFlowAmplitude = 0.04f,
            blobFlowPeriodSec = 12f,
            blobZones = listOf(floatArrayOf(0.40f, 0.20f, 0.05f, 0.08f), floatArrayOf(0.15f, 0.15f, 0.22f, 0.08f), floatArrayOf(0.60f, 0.15f, 0.40f, 0.08f), floatArrayOf(0.20f, 0.15f, 0.58f, 0.08f), floatArrayOf(0.55f, 0.15f, 0.75f, 0.08f)),
            halftone = false,
            halftoneSpacingDp = 12f,
            halftoneDotMinDp = 1f,
            halftoneDotMaxDp = 3.5f,
            halftoneAlpha = 0.18f,
            decorKinds = emptySet(),
            decorCount = 0,
            decorAlpha = 0.16f,
            decorSizeMinDp = 20,
            decorSizeMaxDp = 47,
            sizeMinSp = 24f,
            sizeMaxSp = 40f,
            rotMaxDeg = 0f,
            fillAlpha = 0.95f,
            baseAlpha = 0.50f,
            glowColors = emptyList(),
            glowBlurPx = 0f,
            fillBold = true,
            layout = "depth",
        )

        // 10 雨天（3.01 重构，黑胶整个换掉）：雨天的氛围感——阴天灰蓝色调，偏冷、偏暗、柔和，不亮、不艳；
        // 背景是雨：细雨丝斜落（带风斜角、速度各不同），落到底部化成小涟漪；再加一层雨打窗玻璃——几颗水珠贴在玻璃上缓缓下滑、拖出水痕；
        // 安静、微凉、有点忧郁的雨天，不是热闹的雨；整体对比度低一点，字用亮色保证可读。歌词横排居中，不套框。
        // 0 团色块、无半调、无装饰；20–34sp、不旋转；已唱字走默认（主色），无词辉光。
        val XiaYu = LyricVisualTemplate(
            id = 10,
            name = "雨天",
            kvId = "xiayu",
            backgroundWash = null,
            blobCount = 0,
            blobHueShifts = listOf(0f),
            blobFixedHue = null,
            blobAlphas = listOf(0.1f),
            blobRadii = listOf(0.4f),
            blobFlow = false,
            blobFlowAmplitude = 0.04f,
            blobFlowPeriodSec = 8f,
            halftone = false,
            halftoneSpacingDp = 12f,
            halftoneDotMinDp = 1f,
            halftoneDotMaxDp = 3.5f,
            halftoneAlpha = 0.18f,
            decorKinds = emptySet(),
            decorCount = 0,
            decorAlpha = 0.16f,
            decorSizeMinDp = 20,
            decorSizeMaxDp = 47,
            sizeMinSp = 20f,
            sizeMaxSp = 34f,
            rotMaxDeg = 0f,
            fillAlpha = 0.95f,
            baseAlpha = 0.60f,
            glowColors = emptyList(),
            glowBlurPx = 0f,
            fillBold = false,
            layout = "rain",
        )

        // 11 四季（3.01 重构，胶片整个换掉）：四季轮回——背景随时间循环春夏秋冬（30 秒一季），季与季之间淡入淡出、自由搭配（过渡时两季同屏）；
        // 春粉色花瓣飘落、夏阳光绿叶、秋金红枫叶飘落、冬雪花纷飞；歌词横排居中，不套框。
        // 0 团色块、无半调、无装饰；20–34sp、不旋转；已唱字走默认（主色），无词辉光。
        val SiJi = LyricVisualTemplate(
            id = 11,
            name = "四季",
            kvId = "siji",
            backgroundWash = null,
            blobCount = 0,
            blobHueShifts = listOf(0f),
            blobFixedHue = null,
            blobAlphas = listOf(0.1f),
            blobRadii = listOf(0.4f),
            blobFlow = false,
            blobFlowAmplitude = 0.04f,
            blobFlowPeriodSec = 8f,
            halftone = false,
            halftoneSpacingDp = 12f,
            halftoneDotMinDp = 1f,
            halftoneDotMaxDp = 3.5f,
            halftoneAlpha = 0.18f,
            decorKinds = emptySet(),
            decorCount = 0,
            decorAlpha = 0.16f,
            decorSizeMinDp = 20,
            decorSizeMaxDp = 47,
            sizeMinSp = 20f,
            sizeMaxSp = 34f,
            rotMaxDeg = 0f,
            fillAlpha = 0.95f,
            baseAlpha = 0.55f,
            glowColors = emptyList(),
            glowBlurPx = 0f,
            fillBold = false,
            layout = "siji",
        )

        // 12 雾笺（3.01 重构）：背景是横向漂移的雾带——几条半透明横向雾带缓慢左右漂移，字始终清晰；当前词经过时局部变清晰（词后柔和亮斑随词走），唱完保持可读；
        // 0 团色块、无半调、无装饰；20–34sp、不旋转；无词辉光。
        val WuJian = LyricVisualTemplate(
            id = 12,
            name = "雾笺",
            kvId = "wujian",
            backgroundWash = null,
            blobCount = 0,
            blobHueShifts = listOf(200f, 210f, 220f),
            blobFixedHue = null,
            blobAlphas = listOf(0.08f, 0.06f, 0.05f),
            blobRadii = listOf(0.60f, 0.50f, 0.40f),
            blobFlow = true,
            blobFlowAmplitude = 0.04f,
            blobFlowPeriodSec = 12f,
            blobZones = listOf(floatArrayOf(0.30f, 0.40f, 0.05f, 0.10f), floatArrayOf(0.30f, 0.40f, 0.75f, 0.10f)),
            halftone = false,
            halftoneSpacingDp = 12f,
            halftoneDotMinDp = 1f,
            halftoneDotMaxDp = 3.5f,
            halftoneAlpha = 0.18f,
            decorKinds = emptySet(),
            decorCount = 0,
            decorAlpha = 0.16f,
            decorSizeMinDp = 20,
            decorSizeMaxDp = 47,
            sizeMinSp = 20f,
            sizeMaxSp = 34f,
            rotMaxDeg = 0f,
            fillAlpha = 0.95f,
            baseAlpha = 0.42f,
            glowColors = emptyList(),
            glowBlurPx = 0f,
            fillBold = false,
            layout = "mist",
        )

        // 13 町景（3.01 重构）：招牌框去掉；背景是夜间街景灯光——底部一排临街店铺小窗光/霓虹竖条，亮度逐个明灭；歌词不再套招牌框，已唱字按词下标轮换霓虹粉/青/金三色全程 Bold + 辉光（blur 18px），当前词分三段逐段通电；
        // 0 团色块、无半调、无装饰；20–32sp、不旋转。
        val TingJing = LyricVisualTemplate(
            id = 13,
            name = "町景",
            kvId = "tingjing",
            backgroundWash = null,
            blobCount = 0,
            blobHueShifts = listOf(300f, 180f, 45f),
            blobFixedHue = null,
            blobAlphas = listOf(0.16f, 0.13f, 0.10f),
            blobRadii = listOf(0.55f, 0.45f, 0.40f),
            blobFlow = true,
            blobFlowAmplitude = 0.04f,
            blobFlowPeriodSec = 6f,
            blobZones = listOf(floatArrayOf(0.10f, 0.15f, 0.10f, 0.12f), floatArrayOf(0.70f, 0.15f, 0.10f, 0.12f), floatArrayOf(0.40f, 0.20f, 0.70f, 0.12f)),
            halftone = false,
            halftoneSpacingDp = 12f,
            halftoneDotMinDp = 1f,
            halftoneDotMaxDp = 3.5f,
            halftoneAlpha = 0.18f,
            decorKinds = emptySet(),
            decorCount = 0,
            decorAlpha = 0.16f,
            decorSizeMinDp = 20,
            decorSizeMaxDp = 47,
            sizeMinSp = 20f,
            sizeMaxSp = 32f,
            rotMaxDeg = 0f,
            fillAlpha = 0.95f,
            baseAlpha = 0.55f,
            glowColors = listOf(
                Color(0xFFFF8FD0),
                Color(0xFF7FE3FF),
                Color(0xFFFFCF7A),
            ),
            glowBlurPx = 18f,
            fillBold = true,
            layout = "sign",
        )

        // 14 商籁（3.01 重构）：背景是飘落的纸片——几片小矩形纸片缓慢旋转飘落，留白感；歌词杂志排版——当前句大标题、邻句小号旁注，切句时像翻过一页；
        // 0 团色块、无半调、无装饰；26–44sp、不旋转；已唱字走默认（主色），无词辉光。
        val ShangLai = LyricVisualTemplate(
            id = 14,
            name = "商籁",
            kvId = "shanglai",
            backgroundWash = null,
            blobCount = 0,
            blobHueShifts = listOf(0f, 120f, 240f, 60f),
            blobFixedHue = null,
            blobAlphas = listOf(0.14f, 0.11f, 0.08f, 0.07f),
            blobRadii = listOf(0.55f, 0.45f, 0.40f, 0.35f),
            blobFlow = true,
            blobFlowAmplitude = 0.04f,
            blobFlowPeriodSec = 8f,
            blobZones = listOf(floatArrayOf(0.05f, 0.12f, 0.25f, 0.15f), floatArrayOf(0.35f, 0.12f, 0.15f, 0.12f), floatArrayOf(0.60f, 0.12f, 0.45f, 0.12f), floatArrayOf(0.30f, 0.15f, 0.70f, 0.12f)),
            halftone = false,
            halftoneSpacingDp = 12f,
            halftoneDotMinDp = 1f,
            halftoneDotMaxDp = 3.5f,
            halftoneAlpha = 0.18f,
            decorKinds = emptySet(),
            decorCount = 0,
            decorAlpha = 0.16f,
            decorSizeMinDp = 20,
            decorSizeMaxDp = 47,
            sizeMinSp = 26f,
            sizeMaxSp = 44f,
            rotMaxDeg = 0f,
            fillAlpha = 0.95f,
            baseAlpha = 0.55f,
            glowColors = emptyList(),
            glowBlurPx = 0f,
            fillBold = true,
            layout = "magazine",
        )

        // 15 沙画（3.03 新增舞台之一，艺术的、讲故事的）：3.05 不再画通用光束，靠布景暖沙氛围光；
        // 0 团色块、无半调、无装饰；20–38sp、±10°；已唱字金沙色（#E8C878，alpha 0.9）斜体 Bold；未唱字白 0.55。
        // 3.06 布景重做：亮暖沙底（#6B4F2A）+ 三幅画循环（爱心/飞鸟/花朵，50 秒一幅：画 22 秒、停留 20 秒、化 8 秒）+
        // 手跟着画笔尖走（手臂 + 手掌椭圆 + 指尖亮沙）；160 颗随机流动沙粒全部撤掉，只留约 20 颗极淡背景沙尘。
        val ShaHua = LyricVisualTemplate(
            id = 15,
            name = "沙画",
            kvId = "shahua",
            backgroundWash = null,
            blobCount = 0,
            blobHueShifts = emptyList(),
            blobFixedHue = null,
            blobAlphas = emptyList(),
            blobRadii = emptyList(),
            blobFlow = false,
            blobFlowAmplitude = 0.04f,
            blobFlowPeriodSec = 8f,
            halftone = false,
            halftoneSpacingDp = 12f,
            halftoneDotMinDp = 1f,
            halftoneDotMaxDp = 3.5f,
            halftoneAlpha = 0.18f,
            decorKinds = emptySet(),
            decorCount = 0,
            decorAlpha = 0.16f,
            decorSizeMinDp = 20,
            decorSizeMaxDp = 47,
            sizeMinSp = 20f,
            sizeMaxSp = 38f,
            rotMaxDeg = 10f,
            fillAlpha = 0.95f,
            baseAlpha = 0.55f,
            glowColors = listOf(Color(0xFFE8C878)),
            glowBlurPx = 20f,
            fillBold = true,
            spotlightColors = listOf(
                Color(0xFFE8C878),
                Color(0xFFF0C878),
            ),
            spotlightSweepAmp = 0.07f,
            spotlightSweepPeriodSec = 7f,
            spotlightAlpha = 0.15f,
        )

        // 16 游子归家（3.03 新增舞台之二，讲故事的）：3.05 不再画通用光束，靠布景台灯暖光晕；
        // 0 团色块、无半调、无装饰；20–38sp、±10°；已唱字墨色偏暖（#3A2A1A）写在信纸上（浅色信纸矩形 #F5EAD0、微斜 2°）；未唱字墨色淡化。
        // 3.06 布景：深暖棕底 + 书桌台灯信纸 + 窗外月光；信纸衬底缩到 0.78×0.44、月亮 ×1.5、台灯 ×1.3；台灯光晕照在信纸区域。
        val YouZi = LyricVisualTemplate(
            id = 16,
            name = "游子归家",
            kvId = "youzi",
            backgroundWash = null,
            blobCount = 0,
            blobHueShifts = emptyList(),
            blobFixedHue = null,
            blobAlphas = emptyList(),
            blobRadii = emptyList(),
            blobFlow = false,
            blobFlowAmplitude = 0.04f,
            blobFlowPeriodSec = 8f,
            halftone = false,
            halftoneSpacingDp = 12f,
            halftoneDotMinDp = 1f,
            halftoneDotMaxDp = 3.5f,
            halftoneAlpha = 0.18f,
            decorKinds = emptySet(),
            decorCount = 0,
            decorAlpha = 0.16f,
            decorSizeMinDp = 20,
            decorSizeMaxDp = 47,
            sizeMinSp = 20f,
            sizeMaxSp = 38f,
            rotMaxDeg = 10f,
            fillAlpha = 0.95f,
            baseAlpha = 0.55f,
            glowColors = listOf(Color(0xFFFFD98A)),
            glowBlurPx = 20f,
            fillBold = true,
            spotlightColors = listOf(
                Color(0xFFFFD98A),
                Color(0xFFFFF6D8),
            ),
            spotlightSweepAmp = 0.06f,
            spotlightSweepPeriodSec = 8f,
            spotlightAlpha = 0.16f,
        )

        // 17 武侠（3.03 新增舞台之三，江湖意境）：3.05 不再画通用光束，只留布景里一道加粗加亮的剑气寒光斜扫；
        // 0 团色块、无半调、无装饰；20–38sp、±10°；已唱字冷白带青（#D0E8E0）全程 Bold；未唱字白 0.55。
        // 3.06 布景：冷青底 + 明月 + 竹林收拢到两侧框住字区 + 剑气寒光收细收暗（半宽 16→8dp、alpha 0.42–0.55→0.20–0.28、亮核 3→1.5dp）、竹子提亮成青绿。
        val WuXia = LyricVisualTemplate(
            id = 17,
            name = "武侠",
            kvId = "wuxia",
            backgroundWash = null,
            blobCount = 0,
            blobHueShifts = emptyList(),
            blobFixedHue = null,
            blobAlphas = emptyList(),
            blobRadii = emptyList(),
            blobFlow = false,
            blobFlowAmplitude = 0.04f,
            blobFlowPeriodSec = 8f,
            halftone = false,
            halftoneSpacingDp = 12f,
            halftoneDotMinDp = 1f,
            halftoneDotMaxDp = 3.5f,
            halftoneAlpha = 0.18f,
            decorKinds = emptySet(),
            decorCount = 0,
            decorAlpha = 0.16f,
            decorSizeMinDp = 20,
            decorSizeMaxDp = 47,
            sizeMinSp = 20f,
            sizeMaxSp = 38f,
            rotMaxDeg = 10f,
            fillAlpha = 0.95f,
            baseAlpha = 0.55f,
            glowColors = listOf(Color(0xFFD0E8E0)),
            glowBlurPx = 20f,
            fillBold = true,
            spotlightColors = listOf(
                Color(0xFFE8F4FF),
                Color(0xFF9ED8FF),
            ),
            spotlightSweepAmp = 0.10f,
            spotlightSweepPeriodSec = 5f,
            spotlightAlpha = 0.15f,
        )

        fun fromId(id: String?): LyricVisualTemplate = when (id) {
            "qinglan" -> QingLan
            "xingtu" -> XingTu
            "nihong" -> NiHong
            "yanchanghui" -> YanChangHui
            "juyuan" -> JuYuan
            "xingkongwutai" -> XingKongWuTai
            "shahua" -> ShaHua
            "youzi" -> YouZi
            "wuxia" -> WuXia
            "qipao" -> QiPao
            "yunjie" -> YunJie
            "kongjian" -> KongJian
            "xiayu" -> XiaYu
            "siji" -> SiJi
            "wujian" -> WuJian
            "tingjing" -> TingJing
            "shanglai" -> ShangLai
            // 旧存值（2.90 第 4 款的 KV id，2.91 已删）无分支，落默认流光，不崩 UI
            else -> LiuGuang
        }
    }
}
