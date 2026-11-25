package com.github.tvbox.osc.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.tvbox.osc.reading.LegadoFetcher
import com.github.tvbox.osc.util.AppContextHolder
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import okhttp3.Request
import java.io.File

/**
 * 透明背景 wallpaper storage: the user pastes an image URL, the image
 * is downloaded once into an app-private file, and the main interface
 * draws it under the page (see EffectsState.wallpaperLevel). The
 * URL persists in KV; [version] bumps on every content change so the
 * UI re-keys its image request instead of showing a stale cache hit.
 */
object WallpaperStore {

    var url by mutableStateOf(KV.get(HawkConfig.FX_WALLPAPER_URL, ""))
        private set

    /** 本地壁纸在位：当前壁纸来自本地选图，而非在线链接。 */
    var local by mutableStateOf(KV.get(HawkConfig.FX_WALLPAPER_LOCAL, false))
        private set

    /** r481：视频壁纸在位（应用内背景播视频，非手机桌面）。与图片壁纸二选一，同时只生效一个。 */
    var video by mutableStateOf(KV.get(HawkConfig.FX_WALLPAPER_VIDEO, false))
        private set

    var version by mutableStateOf(0)
        private set

    /** 恢复后用：把内存里的 url/local 跟 KV 重新对齐（恢复只写 KV，不动这个单例）。 */
    fun syncFromKv() {
        val restored = KV.get(HawkConfig.FX_WALLPAPER_URL, "")
        val restoredLocal = KV.get(HawkConfig.FX_WALLPAPER_LOCAL, false)
        val restoredVideo = KV.get(HawkConfig.FX_WALLPAPER_VIDEO, false)
        if (restored != url || restoredLocal != local || restoredVideo != video) {
            url = restored
            local = restoredLocal
            video = restoredVideo
            version++
        }
    }

    private fun targetFile(): File? {
        val context = AppContextHolder.context() ?: return null
        return File(File(context.filesDir, "wallpaper"), "current")
    }

    /** 视频壁纸文件：本地选视频原样存一份（current_video），循环静音播放。 */
    private fun videoTargetFile(): File? {
        val context = AppContextHolder.context() ?: return null
        return File(File(context.filesDir, "wallpaper"), "current_video")
    }

    /** 可用的视频壁纸文件，缺文件或未标记时为 null（恢复到新机、文件不在时自动失效）。 */
    fun videoFile(): File? {
        if (!video) return null
        val file = videoTargetFile() ?: return null
        return if (file.exists() && file.length() > 0) file else null
    }

    /** 图片或视频壁纸任一在位（页面透明、液态玻璃、首页 hero 背景都按这个判）。 */
    fun hasWallpaper(): Boolean = file() != null || videoFile() != null

    /** 本地壁纸的留存源文件：重置只删 current，不动它，刷新可按它找回来。 */
    private fun localSourceFile(): File? {
        val context = AppContextHolder.context() ?: return null
        return File(File(context.filesDir, "wallpaper"), "local_source")
    }

    /** The downloaded wallpaper file, or null when none is usable. */
    fun file(): File? {
        if (url.isBlank() && !local) return null
        val file = targetFile() ?: return null
        return if (file.exists() && file.length() > 0) file else null
    }

    /**
     * Apply a locally picked image as the current wallpaper.
     * Blocking — call from an IO dispatcher. The picked stream
     * is copied verbatim (original bytes, no recompression, GIF
     * stays a GIF) to the preserved local source and to the
     * display file, so the choice survives restarts after the
     * picker's temporary content:// grant expires. Replaces any
     * online link: one active source at a time.
     */
    fun applyLocal(input: java.io.InputStream): Boolean {
        val target = targetFile() ?: return false
        val source = localSourceFile() ?: return false
        return try {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "local_source.tmp")
            input.use { src ->
                temp.outputStream().use { out -> src.copyTo(out) }
            }
            if (temp.length() == 0L) {
                temp.delete()
                return false
            }
            if (!temp.renameTo(source)) {
                temp.copyTo(source, overwrite = true)
                temp.delete()
            }
            source.copyTo(target, overwrite = true)
            KV.put(HawkConfig.FX_WALLPAPER_URL, "")
            KV.put(HawkConfig.FX_WALLPAPER_LOCAL, true)
            url = ""
            local = true
            // 图片与视频二选一：设图片时撤掉视频壁纸。
            KV.put(HawkConfig.FX_WALLPAPER_VIDEO, false)
            video = false
            runCatching { videoTargetFile()?.delete() }
            version++
            true
        } catch (e: Exception) {
            LOG.d("WallpaperStore", "apply local failed: ${e.message}")
            false
        }
    }

    /**
     * Apply a locally picked video as the app-background wallpaper.
     * Blocking — call from an IO dispatcher. The picked stream is
     * copied verbatim to the video file; one active source at a
     * time, so any image wallpaper is cleared. Plays looped and
     * muted behind the UI (see VideoWallpaperPlayer).
     */
    fun applyLocalVideo(input: java.io.InputStream): Boolean {
        val target = videoTargetFile() ?: return false
        return try {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "current_video.tmp")
            input.use { src -> temp.outputStream().use { out -> src.copyTo(out) } }
            if (temp.length() == 0L) {
                temp.delete()
                return false
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            activateVideo()
            true
        } catch (e: Exception) {
            LOG.d("WallpaperStore", "apply local video failed: ${e.message}")
            false
        }
    }

    /**
     * r482：把已转好的视频文件收为壁纸（转码产物）。Blocking — 调用方在
     * IO 线程转好码后把临时文件交过来，这里挪进 current_video 并激活。
     */
    fun applyLocalVideoFile(source: File): Boolean {
        val target = videoTargetFile() ?: return false
        return try {
            if (!source.exists() || source.length() == 0L) return false
            target.parentFile?.mkdirs()
            if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = true)
                source.delete()
            }
            activateVideo()
            true
        } catch (e: Exception) {
            LOG.d("WallpaperStore", "apply local video file failed: ${e.message}")
            false
        }
    }

    /** 转码用的临时文件路径（wallpaper/transcode.tmp，用完由 applyLocalVideoFile 挪走）。 */
    fun transcodeTempFile(): File? {
        val target = videoTargetFile() ?: return null
        target.parentFile?.mkdirs()
        return File(target.parentFile, "transcode.tmp")
    }

    /** 视频壁纸生效：记 KV、清掉图片壁纸（一个生效源）、刷版本号。 */
    private fun activateVideo() {
        KV.put(HawkConfig.FX_WALLPAPER_VIDEO, true)
        KV.put(HawkConfig.FX_WALLPAPER_URL, "")
        KV.put(HawkConfig.FX_WALLPAPER_LOCAL, false)
        video = true
        url = ""
        local = false
        runCatching { targetFile()?.delete() }
        runCatching { localSourceFile()?.delete() }
        version++
    }

    /** 撤掉视频壁纸（图片壁纸不动——设视频时图片已被清空，所以一般就是回到无壁纸）。 */
    fun clearVideo() {
        KV.put(HawkConfig.FX_WALLPAPER_VIDEO, false)
        video = false
        runCatching { videoTargetFile()?.delete() }
        version++
    }
    fun download(newUrl: String): Boolean {
        val target = targetFile() ?: return false
        return try {
            val request = Request.Builder()
                .url(newUrl)
                .header("User-Agent", LegadoFetcher.DEFAULT_UA)
                .build()
            LegadoFetcher.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val bytes = response.body?.bytes() ?: return false
                if (bytes.isEmpty()) return false
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, "current.tmp")
                temp.writeBytes(bytes)
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            }
            KV.put(HawkConfig.FX_WALLPAPER_URL, newUrl)
            KV.put(HawkConfig.FX_WALLPAPER_LOCAL, false)
            url = newUrl
            local = false
            KV.put(HawkConfig.FX_WALLPAPER_VIDEO, false)
            video = false
            runCatching { videoTargetFile()?.delete() }
            runCatching { localSourceFile()?.delete() }
            version++
            // The transparency level is left exactly as the user
            // set it: a freshly linked wallpaper shows at the
            // default level (40, normal wallpaper look).
            true
        } catch (e: Exception) {
            LOG.d("WallpaperStore", "download failed: ${e.message}")
            false
        }
    }

    /** Re-apply from the stored source （刷新）. Blocking. */
    fun refresh(): Boolean {
        if (video) {
            // 视频壁纸没有可重下的源：文件在就重 key 一次（播放器重建），不在就失败。
            if (videoFile() == null) return false
            version++
            return true
        }
        if (local) {
            val source = localSourceFile() ?: return false
            if (!source.exists() || source.length() == 0L) return false
            val target = targetFile() ?: return false
            return try {
                source.copyTo(target, overwrite = true)
                version++
                true
            } catch (e: Exception) {
                LOG.d("WallpaperStore", "refresh local failed: ${e.message}")
                false
            }
        }
        val current = url
        if (current.isBlank()) return false
        return download(current)
    }

    /**
     * Delete the cached image and forget the URL （清除）. Also
     * resets the transparency level to the default (40), so no
     * state survives in which a later wallpaper (or the settings
     * slider) inherits a stale level with no image attached.
     */
    /** r486：清除只清图片壁纸（链接 + 本地源 + 图片文件），视频壁纸不动——视频只用它自己那行的删除键清。 */
    fun clear() {
        KV.put(HawkConfig.FX_WALLPAPER_URL, "")
        KV.put(HawkConfig.FX_WALLPAPER_LOCAL, false)
        url = ""
        local = false
        runCatching { targetFile()?.delete() }
        runCatching { localSourceFile()?.delete() }
        version++
        EffectsState.setWallpaperLevel(EffectsState.DEFAULT_WALLPAPER_LEVEL)
    }

    /**
     * Reset to the no-wallpaper state （重置，69, distinct from
     * 清除）: cached image deleted, level back to 40, every
     * wallpaper-adjacent effect back to its default (wallpaper
     * effect on, global transparency on, default background
     * showing). Always available (unlike 清除/刷新, which need a
     * stored URL), because resetting a default state must work
     * even when the URL is already blank but a level was dragged.
     * 71 correction (user): reset does NOT forget the URL — the
     * link stays, so 刷新 can bring the wallpaper back from the
     * kept URL. Only 清除 forgets the URL. (69 wrongly called
     * clear() here, deleting the link too.) 81: the same rule
     * covers a local wallpaper — reset deletes only current,
     * the preserved local source stays, so 刷新 re-applies it.
     * r486：重置也不动视频壁纸（视频只用它自己那行的删除键清）；只清图片 + 回默认效果。
     */
    fun reset() {
        runCatching { targetFile()?.delete() }
        version++
        EffectsState.setWallpaperLevel(EffectsState.DEFAULT_WALLPAPER_LEVEL)
        EffectsState.setWallpaperEffectEnabled(true)
        EffectsState.setGlobalTransparent(true)
    }
}
