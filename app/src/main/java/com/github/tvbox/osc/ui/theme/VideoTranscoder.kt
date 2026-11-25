package com.github.tvbox.osc.ui.theme

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import com.github.tvbox.osc.util.LOG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * r482：视频转码（视频壁纸用）。AVBox 播放器是 ExoPlayer，认的容器很多
 * （mp4/mkv/webm/mov/avi/m3u8…），但能不能播看的是里面的编码：H.264
 * 人人能播，H.265/AV1/VP9/10bit 在有些机器上硬解不了就黑屏——用户报的
 * 「AVBox 不识别 mp4」多半就是这个。所以壁纸视频统一转成 H.264 MP4
 * （720p、30fps、静音），这是设备自己编码器产出的，同一台机子一定能解。
 * 只用系统 MediaCodec/MediaMuxer，不引第三方库。
 * m3u8 是在线流的播放列表、不是视频文件，转不了也不接；GIF 是图片，
 * 不走这里（由主题设置页直接当图片壁纸设，Coil 会动）。
 */
object VideoTranscoder {

    private const val TAG = "VideoTranscoder"
    private const val MAX_W = 1280
    private const val MAX_H = 720
    private const val BIT_RATE = 4_000_000
    private const val FRAME_RATE = 30
    private const val TIMEOUT_US = 10_000L
    /** 60 秒没有任何缓冲推进就当卡死，放弃。 */
    private const val STALL_MS = 60_000L

    data class Probe(
        val mime: String,
        val width: Int,
        val height: Int,
        val durationUs: Long,
    )

    /** 读视频轨信息；没有视频轨或打不开返回 null。Blocking（只读文件头，很快）。 */
    fun probe(context: Context, uri: Uri): Probe? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                val width = if (format.containsKey(MediaFormat.KEY_WIDTH)) {
                    format.getInteger(MediaFormat.KEY_WIDTH)
                } else 0
                val height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) {
                    format.getInteger(MediaFormat.KEY_HEIGHT)
                } else 0
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else 0L
                return Probe(mime, width, height, durationUs)
            }
            null
        } catch (e: Exception) {
            LOG.d(TAG, "probe failed: ${e.message}")
            null
        } finally {
            extractor.release()
        }
    }

    /** 已是 H.264 就不用转，ExoPlayer + 本机硬解一定能播，直接拷。 */
    fun isDirectlyPlayable(probe: Probe): Boolean = probe.mime == "video/avc"

    /**
     * 把 [uri] 的视频转成 H.264 MP4 写到 [outFile]（静音，只留视频轨）。
     * [onProgress] 0..1，回调在转码线程上（调用方自行切主线程更新 UI）。
     * 成功返回 true；失败返回 false（outFile 会删掉）。
     */
    suspend fun transcode(
        context: Context,
        uri: Uri,
        outFile: File,
        onProgress: (Float) -> Unit,
    ): Boolean = withContext(Dispatchers.Default) {
        outFile.parentFile?.mkdirs()
        if (outFile.exists()) outFile.delete()
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var succeeded = false
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            var videoTrack = -1
            var srcFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoTrack = i
                    srcFormat = format
                    break
                }
            }
            if (videoTrack < 0 || srcFormat == null) return@withContext false
            val srcMime = srcFormat.getString(MediaFormat.KEY_MIME)!!
            val srcW = if (srcFormat.containsKey(MediaFormat.KEY_WIDTH)) {
                srcFormat.getInteger(MediaFormat.KEY_WIDTH)
            } else 0
            val srcH = if (srcFormat.containsKey(MediaFormat.KEY_HEIGHT)) {
                srcFormat.getInteger(MediaFormat.KEY_HEIGHT)
            } else 0
            if (srcW <= 0 || srcH <= 0) return@withContext false
            val durationUs = if (srcFormat.containsKey(MediaFormat.KEY_DURATION)) {
                srcFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            val rotation = if (srcFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                srcFormat.getInteger(MediaFormat.KEY_ROTATION)
            } else 0
            // 等比缩到 720p 内，宽高取偶数（H.264 要求）。
            val scale = minOf(MAX_W.toFloat() / srcW, MAX_H.toFloat() / srcH, 1f)
            var outW = ((srcW * scale).toInt() / 2) * 2
            var outH = ((srcH * scale).toInt() / 2) * 2
            if (outW < 2) outW = 2
            if (outH < 2) outH = 2

            extractor.selectTrack(videoTrack)

            decoder = MediaCodec.createDecoderByType(srcMime)
            encoder = MediaCodec.createEncoderByType("video/avc")
            val encFormat = MediaFormat.createVideoFormat("video/avc", outW, outH).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
            }
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()
            decoder.configure(srcFormat, inputSurface, null, 0)
            decoder.start()

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (rotation != 0) muxer.setOrientationHint(rotation)

            val info = MediaCodec.BufferInfo()
            var muxerStarted = false
            var muxerTrack = -1
            var extractorDone = false
            var decoderDone = false
            var encoderDone = false
            var encoderEosSignalled = false
            var lastPercent = -1
            var lastActivityMs = SystemClock.uptimeMillis()
            while (!encoderDone) {
                if (SystemClock.uptimeMillis() - lastActivityMs > STALL_MS) {
                    LOG.d(TAG, "transcode stalled, abort")
                    return@withContext false
                }
                // 1. 喂解码器。
                if (!extractorDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)
                        if (buffer != null) {
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inIndex, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                extractorDone = true
                            } else {
                                decoder.queueInputBuffer(
                                    inIndex, 0, size, extractor.sampleTime, 0,
                                )
                                extractor.advance()
                            }
                            lastActivityMs = SystemClock.uptimeMillis()
                        }
                    }
                }
                // 解码器播完后通知编码器收尾（surface 模式下 EOS 不自动传过去）。
                if (decoderDone && !encoderEosSignalled) {
                    encoder.signalEndOfInputStream()
                    encoderEosSignalled = true
                }
                // 2. 取解码器输出，渲染到编码器的输入 surface。
                val decStatus = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    decStatus >= 0 -> {
                        decoder.releaseOutputBuffer(decStatus, info.size > 0)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decoderDone = true
                        }
                        lastActivityMs = SystemClock.uptimeMillis()
                    }
                    decStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    }
                }
                // 3. 取编码器输出，写进 MP4。
                val encStatus = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    encStatus >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                            info.size > 0 && muxerStarted
                        ) {
                            val outBuffer = encoder.getOutputBuffer(encStatus)
                            if (outBuffer != null) {
                                muxer.writeSampleData(muxerTrack, outBuffer, info)
                            }
                        }
                        if (durationUs > 0) {
                            val p = (info.presentationTimeUs.toFloat() / durationUs)
                                .coerceIn(0f, 1f)
                            val percent = (p * 100).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(p)
                            }
                        }
                        encoder.releaseOutputBuffer(encStatus, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoderDone = true
                        }
                        lastActivityMs = SystemClock.uptimeMillis()
                    }
                    encStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            muxerTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                }
            }
            if (!muxerStarted) return@withContext false
            succeeded = true
            onProgress(1f)
            true
        } catch (e: Exception) {
            LOG.d(TAG, "transcode failed: ${e.message}")
            false
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { extractor?.release() }
            // 失败时删掉半成品，不留坏文件。
            if (!succeeded) runCatching { outFile.delete() }
        }
    }
}
