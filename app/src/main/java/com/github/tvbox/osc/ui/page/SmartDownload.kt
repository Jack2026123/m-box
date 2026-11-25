package com.github.tvbox.osc.ui.page

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import com.github.tvbox.osc.R
import com.github.tvbox.osc.reading.LegadoFetcher
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request

class SmartDownloadPaused : IOException("paused")

data class SmartDownloadOutcome(val fileName: String, val downloaded: Long, val total: Long)

sealed class ResumeTarget(val name: String) {
    class MediaStore(val uri: Uri, name: String) : ResumeTarget(name)
    class Saf(val docUri: Uri, name: String) : ResumeTarget(name)
    class File(val file: java.io.File) : ResumeTarget(file.name)
}

object SmartDownload {

    private const val BUFFER_SIZE = 8192
    private const val PROGRESS_EMIT_MIN_MS = 300L
    /** r479：IDM 式多线程分段下载 —— 文件 ≥2MB 且服务器支持 Range 时启用，默认 4 线程并发。 */
    private const val MULTI_THREAD_MIN_BYTES = 2L * 1024 * 1024
    private const val MULTI_THREAD_COUNT = 4

    fun sanitizeFileName(name: String): String {
        val cleaned = name.map { c ->
            if (c.code < 0x20 || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') '_' else c
        }.joinToString("").trim().trim('.')
        return cleaned
    }

    fun guessFileName(rawUrl: String): String {
        val segment = try {
            Uri.parse(rawUrl).lastPathSegment
        } catch (e: Exception) {
            null
        }.orEmpty()
        val decoded = try {
            URLDecoder.decode(segment, "UTF-8")
        } catch (e: Exception) {
            segment
        }
        val cleaned = sanitizeFileName(decoded)
        if (cleaned.isNotEmpty()) return cleaned
        return "download_" + System.currentTimeMillis()
    }

    fun extractUrls(text: String): List<String> {
        val pattern = Regex("https?://[^\\s]+")
        val trailing = setOf('.', ',', ';', ':', '!', '?', '，', '。', '；', '：', '！', '？', '、', ')', ']', '}', '"', '\'', '》', '〉')
        return pattern.findAll(text).map { match ->
            match.value.trimEnd(*trailing.toCharArray())
        }.filter { it.length > 10 }.distinct().toList()
    }

    fun uniqueName(existing: Set<String>, wanted: String): String {
        if (!existing.contains(wanted)) return wanted
        val dot = wanted.lastIndexOf('.')
        val base = if (dot > 0) wanted.substring(0, dot) else wanted
        val ext = if (dot > 0) wanted.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = base + " (" + index + ")" + ext
            if (!existing.contains(candidate)) return candidate
            index++
        }
    }

    fun mimeTypeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    fun resolveFolderLabel(context: Context, treeUriString: String): String {
        if (treeUriString.isBlank()) return "Download/"
        val uri = try {
            Uri.parse(treeUriString)
        } catch (e: Exception) {
            return "Download/"
        }
        val name = try {
            val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(uri)
            val treeDocUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, treeDocId)
            context.contentResolver.query(
                treeDocUri,
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (e: Exception) {
            null
        }
        if (name.isNullOrBlank()) return "Download/"
        return name
    }

    private fun listTreeNames(context: Context, treeUri: Uri): Set<String> {
        return try {
            val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
            val names = mutableSetOf<String>()
            context.contentResolver.query(
                childrenUri,
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (index >= 0) {
                    while (cursor.moveToNext()) {
                        cursor.getString(index)?.let { names.add(it) }
                    }
                }
            }
            names
        } catch (e: Exception) {
            emptySet()
        }
    }

    suspend fun download(
        context: Context,
        url: String,
        wantedName: String,
        treeUri: Uri?,
        resumeFrom: Long = 0,
        resumeTarget: ResumeTarget? = null,
        onTargetCreated: (ResumeTarget) -> Unit = {},
        onCallCreated: (Call) -> Unit = {},
        isPauseRequested: () -> Boolean = { false },
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Result<SmartDownloadOutcome> = withContext(Dispatchers.IO) {
        try {
            val appContext = context.applicationContext
            var resumeFromEff = if (resumeFrom > 0 && resumeTarget != null) resumeFrom else 0
            var resumeTargetEff = if (resumeFromEff > 0) resumeTarget else null
            // r479：新下载先试 IDM 式多线程分段（≥2MB 且服务器支持 Range）；不满足条件回退下面单线程。
            if (resumeFromEff == 0L) {
                val multi = tryDownloadMultiThread(
                    context, url, wantedName, treeUri,
                    onTargetCreated, onCallCreated, isPauseRequested, onProgress,
                )
                if (multi != null) return@withContext multi
            }
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", LegadoFetcher.DEFAULT_UA)
            if (resumeFromEff > 0) builder.header("Range", "bytes=" + resumeFromEff + "-")
            val call = LegadoFetcher.client.newCall(builder.build())
            onCallCreated(call)
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP " + response.code))
                }
                val body = response.body ?: return@withContext Result.failure(IOException("empty body"))
                if (resumeFromEff > 0 && resumeTargetEff != null && response.code == 200) {
                    deleteTarget(appContext, resumeTargetEff)
                    resumeFromEff = 0
                    resumeTargetEff = null
                }
                if (resumeTargetEff != null && resumeFromEff > 0) {
                    val total = parseContentRangeTotal(response.header("Content-Range"))
                        ?: (resumeFromEff + body.contentLength())
                    val out = openAppend(appContext, resumeTargetEff)
                        ?: return@withContext Result.failure(IOException("open output failed"))
                    val name = resumeTargetEff.name
                    try {
                        val downloaded = copyStream(body, out, total, resumeFromEff, onProgress)
                        return@withContext Result.success(SmartDownloadOutcome(name, downloaded, total))
                    } catch (e: Exception) {
                        if (isPauseRequested()) throw SmartDownloadPaused()
                        deleteTarget(appContext, resumeTargetEff)
                        throw e
                    }
                }
                val fresh = createFreshTarget(appContext, treeUri, wantedName, onTargetCreated)
                val total = body.contentLength()
                try {
                    val downloaded = copyStream(body, fresh.second, total, 0, onProgress)
                    return@withContext Result.success(SmartDownloadOutcome(fresh.third, downloaded, total))
                } catch (e: Exception) {
                    if (isPauseRequested()) throw SmartDownloadPaused()
                    deleteTarget(appContext, fresh.first)
                    throw e
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 新建一个空的下载目标（MediaStore / SAF 目录 / 旧版文件），失败抛 IOException。单线程与多线程合并共用。 */
    private fun createFreshTarget(
        appContext: Context,
        treeUri: Uri?,
        wantedName: String,
        onTargetCreated: (ResumeTarget) -> Unit,
    ): Triple<ResumeTarget, java.io.OutputStream, String> {
        val resolver = appContext.contentResolver
        if (treeUri != null) {
            val treeDocId = try {
                android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            } catch (e: Exception) {
                throw IOException(appContext.getString(R.string.settings_smart_download_folder_unavailable))
            }
            val treeDocUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
            val existing = listTreeNames(appContext, treeUri)
            val finalName = uniqueName(existing, wantedName)
            val docUri = try {
                android.provider.DocumentsContract.createDocument(resolver, treeDocUri, mimeTypeFor(finalName), finalName)
            } catch (e: SecurityException) {
                throw IOException(appContext.getString(R.string.settings_smart_download_folder_unavailable))
            } catch (e: Exception) {
                throw IOException("create file failed")
            } ?: throw IOException("create file failed")
            val target = ResumeTarget.Saf(docUri, finalName)
            onTargetCreated(target)
            val out = resolver.openOutputStream(docUri)
                ?: throw IOException("open output failed")
            return Triple(target, out, finalName)
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val existing = dir.list()?.toSet() ?: emptySet()
            val finalName = uniqueName(existing, wantedName)
            val targetFile = java.io.File(dir, finalName)
            val target = ResumeTarget.File(targetFile)
            onTargetCreated(target)
            return Triple(target, targetFile.outputStream(), finalName)
        } else {
            val existing = queryDownloadNames(appContext)
            val finalName = uniqueName(existing, wantedName)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, finalName)
                put(MediaStore.Downloads.MIME_TYPE, mimeTypeFor(finalName))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("insert download failed")
            val target = ResumeTarget.MediaStore(uri, finalName)
            onTargetCreated(target)
            val out = resolver.openOutputStream(uri)
                ?: throw IOException("open output failed")
            return Triple(target, out, finalName)
        }
    }

    /** r479：IDM 式多线程分段下载。仅新下载（resumeFrom==0）且服务器支持 Range、文件 ≥2MB 时走这里；
     *  不满足条件返回 null，调用方回退单线程。返回非 null 即本次下载的最终结果（成功或失败）。 */
    private suspend fun tryDownloadMultiThread(
        context: Context,
        url: String,
        wantedName: String,
        treeUri: Uri?,
        onTargetCreated: (ResumeTarget) -> Unit,
        onCallCreated: (Call) -> Unit,
        isPauseRequested: () -> Boolean,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Result<SmartDownloadOutcome>? = withContext(Dispatchers.IO) {
        // 1) 探针：Range: bytes=0-0，服务器回 206 + Content-Range 才算支持分段。
        val total = probeRangeTotal(url, onCallCreated) ?: return@withContext null
        if (total < MULTI_THREAD_MIN_BYTES) return@withContext null
        val appContext = context.applicationContext
        // 2) 按字节均分 N 段，每段一个临时文件（cacheDir），断点续传按段恢复。
        val partDir = File(appContext.cacheDir, "smartdl").apply { if (!exists()) mkdirs() }
        val base = sanitizeFileName(wantedName).ifEmpty { "download" } + "." + System.currentTimeMillis()
        val partSize = total / MULTI_THREAD_COUNT
        val downloadedAll = AtomicLong(0L)
        val lastEmit = AtomicLong(0L)
        val emitProgress = {
            val now = System.currentTimeMillis()
            if (now - lastEmit.get() >= PROGRESS_EMIT_MIN_MS || downloadedAll.get() >= total) {
                lastEmit.set(now)
                onProgress(downloadedAll.get(), total)
            }
        }
        try {
            val jobs = (0 until MULTI_THREAD_COUNT).map { i ->
                val start = i * partSize
                val end = if (i == MULTI_THREAD_COUNT - 1) total - 1 else (i + 1) * partSize - 1
                val partFile = File(partDir, base + ".part" + i)
                async {
                    downloadSegment(url, start, end, partFile, onCallCreated, isPauseRequested, downloadedAll, emitProgress)
                }
            }
            jobs.awaitAll()
            // 3) 全部段完成 → 新建目标，按顺序把各段拼进去。
            val fresh = createFreshTarget(appContext, treeUri, wantedName, onTargetCreated)
            try {
                fresh.second.use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    for (i in 0 until MULTI_THREAD_COUNT) {
                        File(partDir, base + ".part" + i).inputStream().use { input ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                            }
                        }
                    }
                    out.flush()
                }
            } catch (e: Exception) {
                if (isPauseRequested()) throw SmartDownloadPaused()
                deleteTarget(appContext, fresh.first)
                throw e
            }
            onProgress(total, total)
            Result.success(SmartDownloadOutcome(fresh.third, total, total))
        } catch (e: Exception) {
            if (e is SmartDownloadPaused) throw e
            Result.failure(e)
        } finally {
            // 清临时分段文件（成功、失败、暂停都清；暂停后恢复走单线程续传，不依赖分段文件）。
            for (i in 0 until MULTI_THREAD_COUNT) {
                runCatching { File(partDir, base + ".part" + i).delete() }
            }
        }
    }

    /** 探针：Range: bytes=0-0 请求，206 + Content-Range 里带出文件总大小即支持分段；否则 null。 */
    private fun probeRangeTotal(url: String, onCallCreated: (Call) -> Unit): Long? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", LegadoFetcher.DEFAULT_UA)
                .header("Range", "bytes=0-0")
                .build()
            val call = LegadoFetcher.client.newCall(req)
            onCallCreated(call)
            call.execute().use { resp ->
                if (resp.code != 206) return null
                val acceptRanges = resp.header("Accept-Ranges")?.lowercase()
                val total = parseContentRangeTotal(resp.header("Content-Range"))
                if (total == null || total <= 0) return null
                if (acceptRanges != null && acceptRanges != "bytes") return null
                total
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 下一段：Range: bytes=start-end；partFile 已有部分内容时从该处续传（单段内断点续传）。 */
    private suspend fun downloadSegment(
        url: String,
        start: Long,
        end: Long,
        partFile: File,
        onCallCreated: (Call) -> Unit,
        isPauseRequested: () -> Boolean,
        downloadedAll: AtomicLong,
        emitProgress: () -> Unit,
    ) = withContext(Dispatchers.IO) {
        var from = start + if (partFile.exists()) partFile.length() else 0L
        if (from > end) return@withContext
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", LegadoFetcher.DEFAULT_UA)
            .header("Range", "bytes=" + from + "-" + end)
            .build()
        val call = LegadoFetcher.client.newCall(req)
        onCallCreated(call)
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP " + resp.code + " segment " + start + "-" + end)
            val body = resp.body ?: throw IOException("empty body")
            body.byteStream().use { input ->
                java.io.FileOutputStream(partFile, true).use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (isPauseRequested()) throw SmartDownloadPaused()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        downloadedAll.addAndGet(read.toLong())
                        emitProgress()
                    }
                    out.flush()
                }
            }
        }
    }

    private fun parseContentRangeTotal(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val slash = header.lastIndexOf('/')
        if (slash < 0 || slash + 1 >= header.length) return null
        return header.substring(slash + 1).trim().toLongOrNull()
    }

    private fun openAppend(context: Context, target: ResumeTarget): java.io.OutputStream? {
        return try {
            when (target) {
                is ResumeTarget.MediaStore -> context.contentResolver.openOutputStream(target.uri, "wa")
                is ResumeTarget.Saf -> context.contentResolver.openOutputStream(target.docUri, "wa")
                is ResumeTarget.File -> java.io.FileOutputStream(target.file, true)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun deleteTarget(context: Context, target: ResumeTarget) {
        runCatching {
            when (target) {
                is ResumeTarget.MediaStore -> context.contentResolver.delete(target.uri, null, null)
                is ResumeTarget.Saf -> android.provider.DocumentsContract.deleteDocument(context.contentResolver, target.docUri)
                is ResumeTarget.File -> target.file.delete()
            }
        }
    }

    private fun queryDownloadNames(context: Context): Set<String> {
        return try {
            val names = mutableSetOf<String>()
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                if (index >= 0) {
                    while (cursor.moveToNext()) {
                        cursor.getString(index)?.let { names.add(it) }
                    }
                }
            }
            names
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun copyStream(
        body: okhttp3.ResponseBody,
        out: java.io.OutputStream,
        total: Long,
        startAt: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Long {
        var downloaded = startAt
        var lastEmit = 0L
        body.byteStream().use { input ->
            out.use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    val now = System.currentTimeMillis()
                    if (now - lastEmit >= PROGRESS_EMIT_MIN_MS) {
                        lastEmit = now
                        onProgress(downloaded, total)
                    }
                }
                output.flush()
            }
        }
        onProgress(downloaded, total)
        return downloaded
    }
}

class SmartDownloadNotifier(context: Context) {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val ids = AtomicInteger(1000)

    fun newId(): Int = ids.incrementAndGet()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.settings_smart_download),
            NotificationManager.IMPORTANCE_LOW,
        )
        runCatching { manager.createNotificationChannel(channel) }
    }

    private fun builder(fileName: String): NotificationCompat.Builder {
        ensureChannel()
        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(fileName)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
    }

    fun start(id: Int, fileName: String) {
        try {
            manager.notify(
                id,
                builder(fileName)
                    .setContentText(appContext.getString(R.string.settings_smart_download_downloading))
                    .setProgress(0, 0, true)
                    .build(),
            )
        } catch (e: Exception) {
        }
    }

    fun progress(id: Int, taskId: Int, fileName: String, downloaded: Long, total: Long, speedBps: Long = 0L) {
        try {
            val progressText = if (total > 0) {
                val pct = ((downloaded * 100) / total).toInt()
                pct.toString() + "% · " + formatSmartDownloadMB(downloaded) + " / " + formatSmartDownloadMB(total) + " · " + formatSmartDownloadSpeed(speedBps)
            } else {
                formatSmartDownloadMB(downloaded) + " · " + formatSmartDownloadSpeed(speedBps)
            }
            val b = builder(fileName)
                .setContentText(progressText)
                .addAction(
                    android.R.drawable.ic_media_pause,
                    appContext.getString(R.string.settings_smart_download_pause),
                    actionIntent(ACTION_PAUSE, taskId),
                )
            if (total > 0) {
                b.setProgress(100, ((downloaded * 100) / total).toInt().coerceIn(0, 100), false)
            } else {
                b.setProgress(0, 0, true)
            }
            manager.notify(id, b.build())
        } catch (e: Exception) {
        }
    }

    fun paused(id: Int, taskId: Int, fileName: String, downloaded: Long, total: Long) {
        try {
            val b = builder(fileName)
                .setContentText(appContext.getString(R.string.settings_smart_download_paused))
                .addAction(
                    android.R.drawable.ic_media_play,
                    appContext.getString(R.string.settings_smart_download_resume),
                    actionIntent(ACTION_RESUME, taskId),
                )
            if (total > 0) {
                b.setProgress(100, ((downloaded * 100) / total).toInt().coerceIn(0, 100), false)
            } else {
                b.setProgress(0, 0, true)
            }
            manager.notify(id, b.build())
        } catch (e: Exception) {
        }
    }

    fun cancel(id: Int) {
        try {
            manager.cancel(id)
        } catch (e: Exception) {
        }
    }

    private fun actionIntent(action: String, taskId: Int): PendingIntent {
        val intent = Intent(appContext, SmartDownloadActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_TASK_ID, taskId)
        return PendingIntent.getBroadcast(
            appContext,
            action.hashCode() + taskId * 31,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun done(id: Int, fileName: String, ok: Boolean, error: String?) {
        try {
            val text = if (ok) {
                appContext.getString(R.string.settings_smart_download_complete)
            } else {
                appContext.getString(R.string.settings_smart_download_failed, error ?: "")
            }
            manager.notify(
                id,
                builder(fileName)
                    .setSmallIcon(
                        if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_sys_download,
                    )
                    .setContentText(text)
                    .setProgress(0, 0, false)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (e: Exception) {
        }
    }

    companion object {
        private const val CHANNEL_ID = "smart_download"
        const val ACTION_PAUSE = "com.github.tvbox.osc.action.SMART_DOWNLOAD_PAUSE"
        const val ACTION_RESUME = "com.github.tvbox.osc.action.SMART_DOWNLOAD_RESUME"
        const val EXTRA_TASK_ID = "smart_download_task_id"
    }
}
