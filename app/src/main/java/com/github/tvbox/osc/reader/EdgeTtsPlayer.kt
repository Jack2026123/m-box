package com.github.tvbox.osc.reader

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Network narration channel: synthesises speech through the public
 * Edge read-aloud endpoint (the same service Edge's "read aloud"
 * feature uses) and plays the returned mp3 segment by segment with
 * the app's media3 pipeline. The next segment is synthesised while
 * the current one plays, so long chapters narrate without gaps; a
 * chapter that finishes fires [onChapterEnd] so the caller can flow
 * into the next chapter.
 */
class EdgeTtsPlayer(private val appContext: Context) {

    var onChapterEnd: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var job: Job? = null
    @Volatile private var session = 0
    @Volatile private var currentRate: Float = 1f

    // Main-thread only below.
    private var player: ExoPlayer? = null
    private var endContinuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null

    fun speakChapter(text: String, rate: Float) {
        stop()
        val segments = splitSegments(text)
        if (segments.isEmpty()) {
            postMain { onError?.invoke("正文为空，无法朗读") } // i18n: keep
            return
        }
        currentRate = rate
        val mySession = ++session
        val dir = File(appContext.cacheDir, "edge_tts/s$mySession").apply { mkdirs() }
        job = scope.launch {
            try {
                var prefetch: Deferred<Result<File>>? = null
                for (i in segments.indices) {
                    if (!isActive) return@launch
                    val result = prefetch?.await()
                        ?: synthesizeToFile(segments[i], File(dir, "seg_$i.mp3"))
                    val file = result.getOrElse { throw it }
                    prefetch = if (i + 1 < segments.size) {
                        async { synthesizeToFile(segments[i + 1], File(dir, "seg_${i + 1}.mp3")) }
                    } else {
                        null
                    }
                    playAndAwait(file)
                }
                if (isActive && session == mySession) {
                    postMain { onChapterEnd?.invoke() }
                }
            } catch (e: CancellationException) {
                // stopped by the user / channel switch: stay silent
            } catch (e: Exception) {
                if (session == mySession) {
                    val reason = if (e is PlaybackException) {
                        "音频播放失败" // i18n: keep
                    } else {
                        e.message ?: "网络朗读失败" // i18n: keep
                    }
                    postMain { onError?.invoke(reason) }
                }
            } finally {
                scope.launch { runCatching { dir.deleteRecursively() } }
            }
        }
    }

    fun setRate(rate: Float) {
        currentRate = rate
        mainHandler.post {
            runCatching { player?.setPlaybackParameters(PlaybackParameters(rate)) }
        }
    }

    fun stop() {
        session++
        job?.cancel()
        job = null
        mainHandler.post {
            runCatching { player?.stop() }
        }
    }

    fun shutdown() {
        stop()
        scope.cancel()
        mainHandler.post {
            runCatching { player?.release() }
            player = null
        }
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
    }

    // --- playback ----------------------------------------------------

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val created = ExoPlayer.Builder(appContext).build()
        // Loudness is the phone's media volume, full stop: the
        // player volume stays pinned at 1.0 and the audio
        // attributes route output onto STREAM_MUSIC, so the reader
        // volume slider, the hardware keys and this channel all
        // agree — the network channel never keeps a volume of its
        // own.
        created.volume = 1f
        created.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            true,
        )
        created.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    endContinuation?.let { cont -> if (cont.isActive) cont.resume(Unit) }
                    endContinuation = null
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                endContinuation?.let { cont -> if (cont.isActive) cont.resumeWithException(error) }
                endContinuation = null
            }
        })
        player = created
        return created
    }

    private suspend fun playAndAwait(file: File) {
        withContext(Dispatchers.Main) {
            val exo = ensurePlayer()
            exo.setPlaybackParameters(PlaybackParameters(currentRate))
            suspendCancellableCoroutine<Unit> { cont ->
                endContinuation = cont
                exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                exo.prepare()
                exo.play()
                cont.invokeOnCancellation {
                    if (endContinuation === cont) endContinuation = null
                }
            }
        }
    }

    // --- synthesis ---------------------------------------------------

    private suspend fun synthesizeToFile(text: String, file: File): Result<File> {
        val bytes = withTimeoutOrNull(30_000) { synthesizeBytes(text) }
            ?: return Result.failure(Exception("网络语音合成超时")) // i18n: keep
        return try {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            if (bytes.size < 200) {
                Result.failure(Exception("网络语音合成无音频返回")) // i18n: keep
            } else {
                Result.success(file)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun synthesizeBytes(text: String): ByteArray =
        suspendCancellableCoroutine { cont ->
            val connectionId = UUID.randomUUID().toString().replace("-", "")
            val requestId = UUID.randomUUID().toString().replace("-", "")
            val url = "wss://speech.platform.bing.com/consumer/speech/synthesize" +
                "/readaloud/edge/v1?TrustedClientToken=$TRUSTED_TOKEN" +
                "&ConnectionId=$connectionId" +
                "&Sec-MS-GEC=${secMsGec()}&Sec-MS-GEC-Version=$GEC_VERSION"
            val request = Request.Builder().url(url)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("User-Agent", EDGE_USER_AGENT)
                .build()
            val audio = ByteArrayOutputStream()
            val webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val date = edgeDate()
                    webSocket.send(
                        "X-Timestamp:$date\r\n" +
                            "Content-Type:application/json; charset=utf-8\r\n" +
                            "Path:speech.config\r\n\r\n" +
                            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
                            "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                            "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
                    )
                    val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' " +
                        "xml:lang='zh-CN'><voice name='$VOICE_NAME'>" +
                        "<prosody pitch='+0Hz' rate='+0%' volume='+0%'>" +
                        escapeXml(text) +
                        "</prosody></voice></speak>"
                    webSocket.send(
                        "X-RequestId:$requestId\r\n" +
                            "Content-Type:application/ssml+xml\r\n" +
                            "X-Timestamp:$date\r\n" +
                            "Path:ssml\r\n\r\n" + ssml
                    )
                }

                override fun onMessage(webSocket: WebSocket, message: String) {
                    if (message.contains("Path:turn.end")) {
                        webSocket.close(1000, null)
                        if (cont.isActive) cont.resume(audio.toByteArray())
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val data = bytes.toByteArray()
                    if (data.size < 2) return
                    val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                    if (2 + headerLen > data.size) return
                    val header = String(data, 2, headerLen, Charsets.UTF_8)
                    if (header.contains("Path:audio")) {
                        audio.write(data, 2 + headerLen, data.size - 2 - headerLen)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (cont.isActive) cont.resumeWithException(t)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (cont.isActive) {
                        if (audio.size() > 0) {
                            cont.resume(audio.toByteArray())
                        } else {
                            cont.resumeWithException(Exception("网络语音连接被关闭")) // i18n: keep
                        }
                    }
                }
            })
            cont.invokeOnCancellation { webSocket.cancel() }
        }

    private fun secMsGec(): String {
        var ticksSeconds = System.currentTimeMillis() / 1000 + WINDOWS_EPOCH_OFFSET_SECONDS
        ticksSeconds -= ticksSeconds % 300
        val fileTime = ticksSeconds * 10_000_000L
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$fileTime$TRUSTED_TOKEN".toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun edgeDate(): String {
        val format = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'Z", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date()) + " (Coordinated Universal Time)"
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun splitSegments(text: String, maxLen: Int = 160): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) {
                out.add(sb.toString())
                sb.clear()
            }
        }
        for (raw in text.split(Regex("(?<=[。！？!?；;\\n])"))) {
            val sentence = raw.trim()
            if (sentence.isEmpty()) continue
            if (sentence.length > maxLen) {
                flush()
                var rest = sentence
                while (rest.length > maxLen) {
                    var cut = rest.lastIndexOf('，', maxLen - 1)
                    cut = if (cut < maxLen / 3) maxLen else cut + 1
                    out.add(rest.substring(0, cut).trim())
                    rest = rest.substring(cut).trim()
                }
                if (rest.isNotEmpty()) sb.append(rest)
                continue
            }
            if (sb.length + sentence.length > maxLen) flush()
            sb.append(sentence)
        }
        flush()
        return out.filter { it.isNotBlank() }
    }

    private fun postMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private companion object {
        const val TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        const val GEC_VERSION = "1-130.0.2849.68"
        const val VOICE_NAME = "zh-CN-XiaoxiaoNeural"
        const val WINDOWS_EPOCH_OFFSET_SECONDS = 11644473600L
        const val EDGE_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"
    }
}
