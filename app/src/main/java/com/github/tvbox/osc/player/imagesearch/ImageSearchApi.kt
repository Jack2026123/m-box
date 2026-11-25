package com.github.tvbox.osc.player.imagesearch

import android.graphics.Bitmap
import com.github.catvod.net.OkHttp
import com.github.tvbox.osc.util.LOG
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 播放页识图搜片：avscan.cc 原生版，照抄 py 的识图搜片（________1_4mh6.py）。
 *
 * py 是 spider + 本地 127.0.0.1 服务 + 浏览器页选图；壳子里改成点 🔍 直接截
 * 当前帧上传，不再走 py、不开本地服务、不跳浏览器页。站点行为照抄 py 实测：
 * 首次 POST 极慢（90~120s），先 GET /stats/indexed 预热；POST 超时 150s、
 * 失败重试一次；站点对任何图都回 Top-5，纯色图也能刷出 70 分，必须按相似度
 * 分档显示，不许假装搜到了；时间不是字段，是 image_name 最后一段；
 * 缩略图路径返回体里已带全，不用自己拼；不落任何文件。
 */
object ImageSearchApi {

    const val API_BASE = "https://avscan.cc"
    private const val SEARCH_URL = "$API_BASE/search"
    private const val WARM_URL = "$API_BASE/stats/indexed"

    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private const val READ_TIMEOUT_MS = 150_000L
    private const val WARM_TIMEOUT_MS = 40_000L

    /** py 的压缩参数：长边 1024、JPEG q0.85，照抄。 */
    const val MAX_SIDE = 1024
    const val JPEG_QUALITY = 85

    /** py 的分档：>=85 可信 / >=70 参考 / 其余碰运气。 */
    const val TIER_HIGH = 85.0
    const val TIER_MID = 70.0

    enum class Tier { HIGH, MID, LOW }

    data class Frame(
        val time: String,
        val sim: Double,
        val thumb: String,
        val name: String,
    )

    data class VideoResult(
        val code: String,
        val sim: Double,
        val tier: Tier,
        val frames: List<Frame>,
    )

    /** 回调跑在后台线程，调用方自己 post 回主线程（仿 DanmakuApi 的回调套路）。 */
    interface SearchCallback {
        fun onSuccess(results: List<VideoResult>, ms: Long)
        fun onError(message: String)
    }

    @Volatile
    private var warmed = false

    private val warmLock = Any()

    /** 首次开播时后台预热一次，别等点 🔍 才预热。 */
    fun warmAsync() {
        if (warmed) return
        Thread({ warm(false) }, "avscan-warm").start()
    }

    private fun warm(force: Boolean): Boolean {
        synchronized(warmLock) {
            if (warmed && !force) return true
        }
        val t0 = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url(WARM_URL)
                .get()
                .addHeader("User-Agent", UA)
                .addHeader("Referer", "$API_BASE/")
                .addHeader("Origin", API_BASE)
                .addHeader("Accept", "application/json, text/plain, */*")
                .build()
            OkHttp.client(WARM_TIMEOUT_MS).newCall(request).execute().use { res ->
                val ok = res.code == 200
                synchronized(warmLock) { warmed = ok }
                LOG.i("[avscan] warm " + (if (ok) "ok" else "HTTP ${res.code}") + " " + (System.currentTimeMillis() - t0) + "ms")
                return ok
            }
        } catch (e: Exception) {
            synchronized(warmLock) { warmed = false }
            LOG.i("[avscan] warm fail " + (System.currentTimeMillis() - t0) + "ms " + e.javaClass.simpleName + ": " + (e.message ?: "").take(80))
            return false
        }
    }

    /**
     * 上传一张 JPEG 搜片。照抄 py 的 search_image：图片 <100 字节直接回错；
     * 最多 2 次尝试，每次先看要不要预热（第 2 次强制预热）；200 解析 JSON，
     * 429 回限流文案，其他状态码回「服务器返回 %s %s」，异常回「类型：消息」。
     * 整段跑在新起的后台线程，回调也在后台线程。
     */
    fun search(jpgBytes: ByteArray, callback: SearchCallback) {
        if (jpgBytes.size < 100) {
            callback.onError("图片太小或读不出来")
            return
        }
        Thread({
            var lastErr = ""
            var totalMs = 0L
            var ok = false
            for (attempt in 0..1) {
                if (ok) break
                val needWarm = synchronized(warmLock) { !warmed }
                if (needWarm) warm(attempt > 0)
                val t0 = System.currentTimeMillis()
                try {
                    val body = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", "shot.jpg", jpgBytes.toRequestBody("image/jpeg".toMediaTypeOrNull()))
                        .build()
                    val request = Request.Builder()
                        .url(SEARCH_URL)
                        .post(body)
                        .addHeader("User-Agent", UA)
                        .addHeader("Referer", "$API_BASE/")
                        .addHeader("Origin", API_BASE)
                        .addHeader("Accept", "application/json, text/plain, */*")
                        .build()
                    OkHttp.client(READ_TIMEOUT_MS).newCall(request).execute().use { res ->
                        val cost = System.currentTimeMillis() - t0
                        totalMs += cost
                        if (res.code == 200) {
                            val text = res.body.string()
                            try {
                                val data = JSONObject(text)
                                val results = shapeResults(data.optJSONArray("results"))
                                callback.onSuccess(results, totalMs)
                                ok = true
                            } catch (e: Exception) {
                                lastErr = "服务器回的不是 JSON"
                                LOG.i("[avscan] post attempt${attempt + 1} $lastErr ${cost}ms")
                            }
                        } else if (res.code == 429) {
                            lastErr = "被限流了（429），等一下再试"
                            LOG.i("[avscan] post attempt${attempt + 1} $lastErr ${cost}ms")
                        } else {
                            var detail = ""
                            try {
                                detail = JSONObject(res.body.string()).optString("detail", "").take(80)
                            } catch (ignored: Exception) {
                                detail = ""
                            }
                            lastErr = "服务器返回 ${res.code} $detail"
                            LOG.i("[avscan] post attempt${attempt + 1} $lastErr ${cost}ms")
                        }
                    }
                } catch (e: Exception) {
                    val cost = System.currentTimeMillis() - t0
                    totalMs += cost
                    lastErr = e.javaClass.simpleName + "：" + (e.message ?: "").take(80)
                    LOG.i("[avscan] post attempt${attempt + 1} EXC $lastErr ${cost}ms")
                }
            }
            if (!ok) callback.onError(if (lastErr.isEmpty()) "未知失败" else lastErr)
        }, "avscan-search").start()
    }

    /**
     * 照抄 py 的 fetch_thumb：只认 /thumb/ 开头；进程内内存缓存（超 240 条清空）；
     * 每张独立请求，不共用 Session（py 的教训：共享 Session 并发会静默失败，
     * 只出一两张、其余全黑）；不落盘。回调跑在后台线程。
     */
    private val thumbCache = LinkedHashMap<String, Bitmap>()

    fun fetchThumb(path: String, callback: (Bitmap?) -> Unit) {
        if (!path.startsWith("/thumb/")) {
            callback(null)
            return
        }
        synchronized(thumbCache) {
            thumbCache[path]?.let {
                callback(it)
                return
            }
        }
        Thread({
            try {
                val request = Request.Builder()
                    .url(API_BASE + path)
                    .get()
                    .addHeader("User-Agent", UA)
                    .addHeader("Referer", "$API_BASE/")
                    .build()
                var done = false
                OkHttp.client(WARM_TIMEOUT_MS).newCall(request).execute().use { res ->
                    if (res.code == 200) {
                        val bytes = res.body.bytes()
                        if (bytes.isNotEmpty()) {
                            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) {
                                synchronized(thumbCache) {
                                    if (thumbCache.size > 240) thumbCache.clear()
                                    thumbCache[path] = bmp
                                }
                                callback(bmp)
                                done = true
                            }
                        }
                    }
                    if (!done) callback(null)
                }
            } catch (e: Exception) {
                LOG.e("avscan", e)
                callback(null)
            }
        }, "avscan-thumb").start()
    }

    /** 照抄 py 的 tier_of：float 转失败按 0.0。 */
    fun tierOf(sim: Double): Tier {
        return when {
            sim >= TIER_HIGH -> Tier.HIGH
            sim >= TIER_MID -> Tier.MID
            else -> Tier.LOW
        }
    }

    fun tierText(tier: Tier): String {
        return when (tier) {
            Tier.HIGH -> "可信"
            Tier.MID -> "参考"
            Tier.LOW -> "碰运气"
        }
    }

    private val tsRe = Regex("_(\\d{1,2})-(\\d{1,2})-(\\d{1,2})$")
    private val tailRe = Regex("^\\d{1,2}(-\\d{1,2}){2}$")

    /** 照抄 py 的 frame_time：xxx_00-09-08.jpg ⇒ 00:09:08，认不出回 --:--:--。 */
    fun frameTime(imageName: String?): String {
        if (imageName.isNullOrEmpty()) return "--:--:--"
        val base = imageName.substringBeforeLast(".", imageName)
        val m = tsRe.find(base)
        if (m != null) {
            return "%02d:%02d:%02d".format(
                m.groupValues[1].toIntOrNull() ?: 0,
                m.groupValues[2].toIntOrNull() ?: 0,
                m.groupValues[3].toIntOrNull() ?: 0,
            )
        }
        val tail = base.substringAfterLast("_", "")
        if (tailRe.matches(tail)) return tail.replace("-", ":")
        return "--:--:--"
    }

    /** 照抄 py 的 shape_results：Top-5，每条前 4 帧。 */
    fun shapeResults(results: org.json.JSONArray?): List<VideoResult> {
        val out = ArrayList<VideoResult>()
        if (results == null) return out
        for (i in 0 until minOf(results.length(), 5)) {
            val item = results.optJSONObject(i) ?: continue
            val code = item.optString("video_code", "").trim()
            val best = item.optDouble("best_similarity", 0.0)
            val frames = ArrayList<Frame>()
            val framesJson = item.optJSONArray("frames")
            if (framesJson != null) {
                for (j in 0 until minOf(framesJson.length(), 4)) {
                    val fr = framesJson.optJSONObject(j) ?: continue
                    frames.add(
                        Frame(
                            time = frameTime(fr.optString("image_name", "")),
                            sim = fr.optDouble("similarity", 0.0),
                            thumb = fr.optString("thumb", ""),
                            name = fr.optString("image_name", ""),
                        ),
                    )
                }
            }
            out.add(
                VideoResult(
                    code = if (code.isEmpty()) "—" else code,
                    sim = (kotlin.math.round(best * 100) / 100.0),
                    tier = tierOf(best),
                    frames = frames,
                ),
            )
        }
        return out
    }

    /**
     * 照抄 py 的压缩参数：只缩小不放大，长边 1024、JPEG q0.85。
     * 入参 bitmap 用完即 recycle（TextureView/PixelCopy 给的都是新图）。
     */
    fun compressFrame(bitmap: Bitmap): ByteArray? {
        try {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) return null
            val scale = MAX_SIDE.toFloat() / maxOf(w, h)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
            } else {
                bitmap
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            val bytes = out.toByteArray()
            return if (bytes.size < 100) null else bytes
        } catch (e: Exception) {
            LOG.e("avscan", e)
            return null
        }
    }
}
