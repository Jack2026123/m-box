package com.github.tvbox.osc.ui.music

// 2.90 第 3 项：确定性种子——Folia visualizerSeedHash.ts 的 Kotlin 移植（FNV-1a + mulberry32）。
// 各子系统（布局/色块/装饰/逐词抖动）用不同盐混合，互不相关；不依赖全局随机状态，seek、重建、
// 预热都得到同一帧。同一首歌每次打开布局都一样（种子由 songKey 派生，不再每次重抽 themeRoll）。

/** 布局种子盐（Folia 未规定具体值，只要求不同子系统不同盐；本项定死这四个小质数）。 */
const val LAYOUT_SALT = 11
const val BLOBS_SALT = 23
const val DECOR_SALT = 37
const val JITTER_SALT = 53
const val BG_SALT = 71 // 后 8 款新背景动效（气泡/云团/星空/下雨/四季/雾带/街灯/纸片）的种子盐

/**
 * FNV-1a 32 位字符串散列：offset basis 2166136261，prime 16777619。
 * UInt 运算逐位等价于 JS 的 Math.imul + >>> 0；返回的 Int 是位模式（可为负），
 * hashVisualizerSeed("").toUInt() == 2166136261u。
 */
fun hashVisualizerSeed(value: String): Int {
    var hash = 2166136261u
    for (ch in value) {
        hash = (hash xor ch.code.toUInt()) * 16777619u
    }
    return hash.toInt()
}

/** 盐混合：不同子系统互不相关。2654435761u = 0x9E3779B1（黄金比例散列常数）。 */
fun mixVisualizerSeed(seed: Int, salt: Int): Int =
    ((seed xor salt).toUInt() * 2654435761u).toInt()

/** 按元素下标取确定性的 0..1 抖动。 */
fun visualizerHash01(seed: Int, index: Int, salt: Int): Float =
    mixVisualizerSeed(seed + (index + 1) * 97, salt).toUInt().toFloat() / 4294967296f

/**
 * mulberry32 随机流：32 位状态，周期 2^32。
 * 注意：Folia 原文第二项是 61 | t（标准 mulberry32）；plan 04 章的 Kotlin 抄录写成 61 | state 是笔误，
 * 这里按 Folia 原文（61 | t）实现，与 Node 跑 Folia 原函数的结果逐位一致。
 */
fun createVisualizerRng(seed: Int): () -> Float {
    var state = seed.toUInt()
    return {
        state += 0x6D2B79F5u
        var t = (state xor (state shr 15)) * (1u or state)
        t = (t + ((t xor (t shr 7)) * (61u or t))) xor t
        (t xor (t shr 14)).toFloat() / 4294967296f
    }
}

/** 同一条流从第 skip 个值之后开始：O(1) 跳过（mulberry32 每取一次状态加一个常数）。 */
fun createVisualizerRngAt(seed: Int, skip: Int): () -> Float =
    createVisualizerRng((seed.toUInt() + skip.toUInt() * 0x6D2B79F5u).toInt())
