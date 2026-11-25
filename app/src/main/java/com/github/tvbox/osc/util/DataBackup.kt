package com.github.tvbox.osc.util

import android.os.Environment
import android.system.Os
import com.github.catvod.utils.Prefers
import com.github.tvbox.osc.data.AppDataManager
import com.github.tvbox.osc.data.VodCollect
import com.github.tvbox.osc.data.VodRecord
import com.github.tvbox.osc.ui.theme.WallpaperStore
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 数据备份（格式参照 Vesper/FongMi 经典版规格）：
 * 备份文件为 gzip 压缩的 JSON，存放在外部存储 /TV/backup/ 目录，
 * 文件名带 AVBox 专属前缀加备份当时的日期与时分秒（如 AVBox-2026-10-21-143052.tv），
 * 同日多次备份各存一份互不覆盖，最多保留最近 7 份；旧的纯日期命名备份同样可列出与恢复。
 * 该目录与 Vesper 共用且其备份同为日期命名的 .tv 文件，靠专属前缀互相区分：
 * 列表、清理、恢复都只认带前缀的文件，绝不触碰对方的备份。
 * JSON 顶层键：config（接口配置）、history（观看记录）、keep（收藏）、prefers（KV 原始快照）、
 * webSources（网页源清单原文；r481：网页源存在 Prefers 单独文件 avbox_prefers 里，
 * 不在 KV（MMKV）里，所以 prefers 快照一直漏了它，备份恢复后网页源会丢）。
 */
object DataBackup {

    enum class Failure { NO_CONFIG, INVALID, NOT_OURS, IO }

    class BackupException(val failure: Failure, cause: Throwable? = null) :
        Exception(cause?.message ?: failure.name, cause)

    private const val MAX_BACKUPS = 7
    private const val MAX_BACKUP_BYTES = 64L * 1024 * 1024
    private const val PREFIX = "AVBox-"
    private const val SUFFIX = ".tv"
    private const val TAG = "DataBackup"

    private val gson = Gson()

    private fun sharedDir(): File =
        File(File(Environment.getExternalStorageDirectory(), "TV"), "backup")

    private fun appDir(): File? {
        val ctx = AppContextHolder.context() ?: return null
        val base = ctx.getExternalFilesDir(null) ?: File(ctx.filesDir, "external")
        return File(File(base, "TV"), "backup")
    }

    private fun candidateDirs(): List<File> {
        val dirs = LinkedHashMap<String, File>()
        val shared = sharedDir()
        dirs[shared.absolutePath] = shared
        val app = appDir()
        if (app != null) dirs.putIfAbsent(app.absolutePath, app)
        return ArrayList(dirs.values)
    }

    private fun probeWritable(dir: File): Boolean {
        if (!dir.exists()) dir.mkdirs()
        if (!dir.isDirectory) return false
        return try {
            val probe = File.createTempFile(".probe-", ".tmp", dir)
            probe.delete()
            true
        } catch (e: Throwable) {
            false
        }
    }

    fun backupDir(): File {
        val shared = sharedDir()
        if (probeWritable(shared)) return shared
        val app = appDir()
        if (app != null) {
            if (!app.exists()) app.mkdirs()
            LOG.e(TAG, "shared backup dir not writable: " + shared.absolutePath +
                ", fallback to " + app.absolutePath, null)
            return app
        }
        return shared
    }

    fun todayFileName(): String =
        PREFIX + SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.ROOT).format(Date()) + SUFFIX

    private fun hasOwnName(file: File): Boolean =
        file.name.startsWith(PREFIX) && file.name.endsWith(SUFFIX)

    /** 是否为 AVBox 专属命名的备份文件（同目录里 Vesper 等软件的备份不带此前缀）。 */
    fun isOwnBackup(file: File): Boolean = file.isFile && hasOwnName(file)

    /** 落盘文件是否位于共享目录 /sdcard/TV/backup（否 = 回落到了应用专属目录）。 */
    fun isSharedBackup(file: File): Boolean =
        file.parentFile?.absolutePath == sharedDir().absolutePath

    fun listBackups(): List<File> {
        val byName = LinkedHashMap<String, File>()
        for (dir in candidateDirs()) {
            if (!dir.isDirectory) continue
            val files = dir.listFiles() ?: continue
            for (file in files) {
                if (isOwnBackup(file)) {
                    byName.putIfAbsent(file.name, file)
                }
            }
        }
        return byName.values.sortedByDescending { it.lastModified() }
    }

    fun delete(file: File): Boolean = try {
        file.delete()
    } catch (e: Throwable) {
        false
    }

    suspend fun create(): File {
        val bytes = try {
            val config = buildConfigSection()
            if (config.size() == 0) throw BackupException(Failure.NO_CONFIG)
            val db = AppDataManager.get()
            val root = JsonObject()
            root.add("config", config)
            root.add("history", gson.toJsonTree(db.getVodRecordDao().getAllForBackup()))
            root.add("keep", gson.toJsonTree(db.getVodCollectDao().getAll()))
            val prefers = JsonObject()
            for ((key, value) in KV.rawEntries()) prefers.addProperty(key, value)
            root.add("prefers", prefers)
            // r481：网页源（Prefers 单独文件 avbox_prefers，键 web_sources）不在 KV 里，单独存一份。
            root.addProperty("webSources", Prefers.getString("web_sources", ""))
            gson.toJson(root).toByteArray(Charsets.UTF_8)
        } catch (e: BackupException) {
            throw e
        } catch (e: Throwable) {
            LOG.e(TAG, "collect backup data failed", e)
            throw BackupException(Failure.IO, e)
        }
        val primary = backupDir()
        val target = try {
            writeToDir(primary, bytes)
        } catch (e: Throwable) {
            val fallback = appDir()
            if (fallback == null || fallback.absolutePath == primary.absolutePath) {
                LOG.e(TAG, "write backup failed, dir=" + primary.absolutePath, e)
                throw BackupException(Failure.IO, e)
            }
            LOG.e(TAG, "write backup failed in " + primary.absolutePath +
                ", retry in app dir " + fallback.absolutePath, e)
            try {
                if (!fallback.exists()) fallback.mkdirs()
                writeToDir(fallback, bytes)
            } catch (e2: Throwable) {
                LOG.e(TAG, "write backup retry failed, dir=" + fallback.absolutePath, e2)
                throw BackupException(Failure.IO, e2)
            }
        }
        cleanOld()
        LOG.i(TAG + ": backup ok -> " + target.absolutePath + " (" + target.length() + " bytes)")
        return target
    }

    private fun writeToDir(dir: File, bytes: ByteArray): File {
        val target = File(dir, todayFileName())
        val temp = try {
            File.createTempFile("copy-", ".tmp", dir)
        } catch (e: Throwable) {
            LOG.e(TAG, "create temp file failed, dir=" + dir.absolutePath, e)
            throw e
        }
        try {
            FileOutputStream(temp).use { fos ->
                GZIPOutputStream(fos).use { gz -> gz.write(bytes) }
                // sync 只保证断电耐久性，数据在流 close 时已全部写入；
                // 共享存储 FUSE 路径上 fsync 常被拒，失败只记日志、不判备份失败。
                try {
                    fos.fd.sync()
                } catch (e: Throwable) {
                    LOG.i(TAG + ": fd sync failed, ignored: " + e.message)
                }
            }
            move(temp, target)
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
        return target
    }

    suspend fun restore(file: File) {
        // 写入前双重兜底：文件名必须是 AVBox 专属命名（列表已过滤，这里防其他入口），
        // 且内容须为可解 gzip + 顶层结构齐全，任一不过都不动任何数据。
        if (!hasOwnName(file)) throw BackupException(Failure.NOT_OURS)
        val text = readBackupText(file)
        val root = parseRoot(text)
        validateStructure(root)
        val config = root.get("config")
        if (config == null || !config.isJsonArray || validConfigCount(config.asJsonArray) == 0) {
            throw BackupException(Failure.INVALID)
        }
        val history = parseList(root.get("history"), VodRecord::class.java)
        val keep = parseList(root.get("keep"), VodCollect::class.java)
        val prefers = root.get("prefers")?.takeIf { it.isJsonObject }?.asJsonObject
        val db = AppDataManager.get()
        db.getVodCollectDao().deleteAll()
        for (item in keep) db.getVodCollectDao().insert(item)
        db.getVodRecordDao().clearForBackup()
        for (item in history) db.getVodRecordDao().insert(item)
        if (prefers != null) {
            for ((key, value) in prefers.entrySet()) {
                if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    KV.putRaw(key, value.asString)
                }
            }
            // 壁纸：链接已随 prefers 写回 KV，内存里的 url 须同步；链接在、本地图没了时按链接重下一张。
            try {
                WallpaperStore.syncFromKv()
                if (KV.get(HawkConfig.FX_WALLPAPER_URL, "").isNotBlank() && WallpaperStore.file() == null) {
                    withContext(Dispatchers.IO) {
                        WallpaperStore.refresh()
                    }
                }
            } catch (e: Throwable) {
                LOG.e(TAG, "wallpaper restore refresh failed", e)
            }
        }
        // r481：网页源写回 Prefers（avbox_prefers 文件）。旧备份没有 webSources 键时不动现有清单；
        // 有键时全量覆盖（空串 = 备份时就没有网页源）。恢复后 reloadAfterDataRestore 会重载接口，
        // ApiConfig.parseJson 重新注入网页源，不用这里额外处理。
        val webSources = root.get("webSources")
        if (webSources != null && webSources.isJsonPrimitive && webSources.asJsonPrimitive.isString) {
            Prefers.put("web_sources", webSources.asString)
        }
    }

    private fun move(from: File, to: File) {
        try {
            Os.rename(from.absolutePath, to.absolutePath)
            return
        } catch (e: Throwable) {
            // fall through to copy fallback
        }
        if (to.exists()) to.delete()
        if (!from.renameTo(to)) throw IOException("rename failed")
    }

    private fun cleanOld() {
        val items = listBackups()
        if (items.size > MAX_BACKUPS) {
            items.drop(MAX_BACKUPS).forEach { it.delete() }
        }
    }

    private fun readBackupText(file: File): String {
        try {
            GZIPInputStream(FileInputStream(file).buffered()).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_BACKUP_BYTES) throw BackupException(Failure.INVALID)
                }
                return String(out.toByteArray(), Charsets.UTF_8)
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: Throwable) {
            throw BackupException(Failure.INVALID)
        }
    }

    /** 顶层必须齐备 config/history（数组）、keep（数组）、prefers（对象）四键且类型相符。 */
    private fun validateStructure(root: JsonObject) {
        val config = root.get("config")
        val history = root.get("history")
        val keep = root.get("keep")
        val prefers = root.get("prefers")
        if (config == null || !config.isJsonArray ||
            history == null || !history.isJsonArray ||
            keep == null || !keep.isJsonArray ||
            prefers == null || !prefers.isJsonObject
        ) {
            throw BackupException(Failure.INVALID)
        }
    }

    private fun parseRoot(text: String): JsonObject {
        val element: JsonElement = try {
            JsonParser.parseString(text)
        } catch (e: Throwable) {
            throw BackupException(Failure.INVALID)
        }
        if (!element.isJsonObject) throw BackupException(Failure.INVALID)
        return element.asJsonObject
    }

    private fun validConfigCount(array: JsonArray): Int {
        var count = 0
        for (element in array) {
            if (!element.isJsonObject) continue
            val url = element.asJsonObject.get("url")
            if (url != null && url.isJsonPrimitive && url.asJsonPrimitive.isString &&
                url.asString.isNotBlank()
            ) {
                count++
            }
        }
        return count
    }

    private fun <T> parseList(element: JsonElement?, type: Class<T>): List<T> {
        if (element == null || !element.isJsonArray) return emptyList()
        val result = ArrayList<T>()
        for (item in element.asJsonArray) {
            if (!item.isJsonObject) continue
            try {
                val parsed = gson.fromJson(item, type)
                if (parsed != null) result.add(parsed)
            } catch (e: Throwable) {
                // skip malformed rows, mirroring the tolerant full-backup restore
            }
        }
        return result
    }

    private fun buildConfigSection(): JsonArray {
        val array = JsonArray()
        val activeVod = KV.get(HawkConfig.API_URL, "")
        val activeLive = KV.get(HawkConfig.LIVE_API_URL, "")
        val vodList: ArrayList<String> = KV.get(HawkConfig.SUBSCRIBE_LIST, ArrayList<String>())
        for (raw in vodList) {
            val (name, url) = parseSubscribeEntry(raw)
            if (url.isEmpty()) continue
            array.add(configEntry(name, url, 0, url == activeVod))
        }
        val liveList: ArrayList<String> = KV.get(HawkConfig.LIVE_SUBSCRIBE_LIST, ArrayList<String>())
        for (raw in liveList) {
            val (name, url) = parseSubscribeEntry(raw)
            if (url.isEmpty()) continue
            array.add(configEntry(name, url, 1, activeLive.isNotEmpty() && url == activeLive))
        }
        return array
    }

    private fun configEntry(name: String, url: String, type: Int, use: Boolean): JsonObject {
        val entry = JsonObject()
        entry.addProperty("name", name)
        entry.addProperty("url", url)
        entry.addProperty("type", type)
        entry.addProperty("use", use)
        return entry
    }

    private fun parseSubscribeEntry(value: String): Pair<String, String> {
        val index = value.indexOf('\t')
        return if (index < 0) {
            value.trim() to value.trim()
        } else {
            value.substring(0, index).trim() to value.substring(index + 1)
        }
    }
}
