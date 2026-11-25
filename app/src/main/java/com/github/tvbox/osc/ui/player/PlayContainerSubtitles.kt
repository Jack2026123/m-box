package com.github.tvbox.osc.ui.player

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.View
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.SubtitleAutoMatch
import com.github.tvbox.osc.data.AppGraph
import com.github.tvbox.osc.player.FongmiKernelPlayer
import com.github.tvbox.osc.player.KernelPlayer
import com.github.tvbox.osc.player.TrackInfo
import com.github.tvbox.osc.player.state.SelectDialogState
import com.github.tvbox.osc.player.state.SubtitleSearchSheetState
import com.github.tvbox.osc.player.state.SubtitleSheetState
import com.github.tvbox.osc.sourcedata.SubtitleViewModel
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.MD5
import com.github.tvbox.osc.util.SubtitleHelper
import com.github.tvbox.osc.util.TrackMemory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

internal class PlayContainerSubtitles(private val host: PlayContainer) {

    private val exoCues: MutableList<Cue> = ArrayList()

    private var exoInternalSubtitle: Boolean = false

    private var subtitleDecisionSeq: Int = 0

    private var subtitleInitSeq: Int = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun setSubtitle(path: String?) {
        if (path != null && path.length > 0) {
            KV.put(HawkConfig.SUBTITLE_ENABLED, true)
            applySubtitleTrackState()
            subtitleDecisionSeq++
            hideExoInternalSubtitle()
            host.mController.getSubtitleView().setVisibility(View.GONE)
            host.mController.getSubtitleView().setSubtitlePath(path)
            setSubtitleViewTextStyle(KV.get(HawkConfig.SUBTITLE_TEXT_STYLE, 0))
            host.mController.getSubtitleView().setVisibility(View.VISIBLE)
        }
    }

    fun selectMySubtitle() {
        try {
            if (!host.isAttached()) return
            val view = host.mVideoView ?: return
            val uiState = host.mController.getUiState()
            val mediaPlayer = view.mediaPlayer
            val hasInternal = host.mController.getSubtitleView().hasInternal || hasExoInternalSubtitle(mediaPlayer)
            val exoInternal = mediaPlayer is FongmiKernelPlayer && !mediaPlayer.isMpvKernel() && exoInternalSubtitle
            uiState.subtitleSheet = SubtitleSheetState(
                exoInternal,
                hasInternal,
                {
                    selectMyInternalSubtitle()
                },
                {
                    openLocalSubtitleChooser()
                },
                {
                    openSubtitleSearchSheet()
                },
                { style ->
                    KV.put(HawkConfig.SUBTITLE_TEXT_STYLE, style)
                    setSubtitleViewTextStyle(style)
                },
                {
                    applySubtitleTextSize()
                },
                {
                    SubtitleHelper.reset()
                    setSubtitleViewTextStyle(0)
                    applySubtitleTextSize()
                },
            )
        } catch (e: Exception) {
            LOG.e("PlayContainer", e)
        }
    }

    private fun openLocalSubtitleChooser() {
        host.mPageHost?.launchLocalSubtitlePicker()
    }

    fun onLocalSubtitlePicked(uri: Uri) {
        val activity = host.mActivity
        if (activity == null || activity.isFinishing) return
        Thread {
            try {
                var name = queryDisplayName(activity, uri)
                if (name == null || !name.contains(".")) name = "local_subtitle.srt"
                name = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val dst = File(activity.cacheDir, "subtitle_" + System.currentTimeMillis() + "_" + name)
                activity.contentResolver.openInputStream(uri).use { input ->
                    java.io.FileOutputStream(dst).use { out ->
                        val buf = ByteArray(8192)
                        var len = 0
                        requireNotNull(input) { "subtitle input stream is null" }
                        while (input.read(buf).also { len = it } > 0) out.write(buf, 0, len)
                    }
                }
                val path = dst.absolutePath
                activity.runOnUiThread {
                    if (!host.isAttached()) return@runOnUiThread
                    LOG.i("echo-Local Subtitle Path: " + path)
                    TrackMemory.saveSubtitle(trackMemoryKey(), TrackMemory.subtitleLocal(path))
                    setSubtitle(path)
                }
            } catch (e: Exception) {
                LOG.e("echo-Local Subtitle copy err: " + e)
                activity.runOnUiThread {
                    if (host.isAttached()) {
                        Toast.makeText(activity, activity.getString(R.string.toast_subtitle_read_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    private fun queryDisplayName(activity: Activity, uri: Uri): String? {
        try {
            activity.contentResolver.query(uri, null, null, null, null).use { c ->
                if (c != null && c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx)
                }
            }
        } catch (ignored: Exception) {
            LOG.d("PlayContainer", "query display name failed, keep null")
        }
        return null
    }

    private fun openSubtitleSearchSheet() {
        if (!host.isAttached()) return
        val vod = host.scheduler.vod() ?: return
        val playFlag = vod.playFlag ?: ""
        val word = if (playFlag.contains("Ali") || playFlag.contains("parse")) {
            vod.playNote
        } else {
            vod.name
        }
        val uiState = host.mController.getUiState()
        uiState.subtitleSearchSheet = SubtitleSearchSheetState(word ?: "") { subtitle, releaseUrl ->
            if (host.isAttached()) {
                runOnUi {
                    val zimuUrl = subtitle.url
                    LOG.i("echo-Remote Subtitle Url: " + zimuUrl)
                    TrackMemory.saveSubtitle(
                        trackMemoryKey(),
                        TrackMemory.subtitleOnline(releaseUrl, subtitle.name),
                    )
                    setSubtitle(zimuUrl)
                }
            }
        }
    }

    @SuppressLint("UseCompatLoadingForColorStateLists")
    fun setSubtitleViewTextStyle(style: Int) {
        if (style == 0) {
            host.mController.getSubtitleView().setTextColor(host.context.resources.getColorStateList(R.color.color_FFFFFF))
        } else if (style == 1) {
            host.mController.getSubtitleView().setTextColor(host.context.resources.getColorStateList(R.color.color_FFB6C1))
        }
        applyExoSubtitleStyle()
    }

    fun selectMyInternalSubtitle() {
        val view = host.mVideoView ?: return
        val mediaPlayer = view.mediaPlayer
        var trackInfo: TrackInfo? = null
        if (mediaPlayer is FongmiKernelPlayer && !mediaPlayer.isMpvKernel()) {
            trackInfo = mediaPlayer.getTrackInfo()
        }
        if (trackInfo == null) {
            Toast.makeText(host.context, host.context.getString(R.string.player_no_internal_subtitle), Toast.LENGTH_SHORT).show()
            return
        }
        val bean = trackInfo.getSubtitle()
        if (bean.size < 1) return
        val names = ArrayList<String>()
        for (item in bean) names.add(item.name ?: "")
        host.mController.getUiState().selectDialog = SelectDialogState(
            host.context.getString(R.string.player_switch_internal_subtitle),
            names,
            trackInfo.getSubtitleSelected(false),
        ) { pos ->
            if (pos >= 0 && pos < bean.size) {
                val value = bean[pos]
                try {
                    subtitleDecisionSeq++
                    for (subtitle in bean) {
                        subtitle.selected = TrackSelectorDelegate.isSameTrack(subtitle, value)
                    }
                    if (mediaPlayer is FongmiKernelPlayer && !mediaPlayer.isMpvKernel()) {
                        host.mController.getSubtitleView().setVisibility(View.GONE)
                        host.mController.getSubtitleView().destroy()
                        host.mController.getSubtitleView().clearSubtitleCache()
                        host.mController.getSubtitleView().isInternal = false
                        exoInternalSubtitle = true
                        mediaPlayer.setTrack(value)
                        mediaPlayer.setInternalSubtitleDelay(SubtitleHelper.getTimeDelay())
                        host.mController.getExoSubtitleView().setVisibility(View.VISIBLE)
                        applyExoSubtitleSettings()
                    }
                } catch (e: Exception) {
                    LOG.e("echo-switch-internal-subtitle-error:" + e.message)
                }
            }
        }
    }

    private fun hasExoInternalSubtitle(mediaPlayer: KernelPlayer?): Boolean {
        val kernel = mediaPlayer as? FongmiKernelPlayer ?: return false
        if (kernel.isMpvKernel()) return false
        val trackInfo = kernel.getTrackInfo()
        return !trackInfo.getSubtitle().isEmpty()
    }

    private fun hideExoInternalSubtitle() {
        exoInternalSubtitle = false
        exoCues.clear()
        if (host.mController != null && host.mController.getExoSubtitleView() != null) {
            host.mController.getExoSubtitleView().setCues(exoCues)
            host.mController.getExoSubtitleView().setVisibility(View.GONE)
        }
    }

    private fun onExoCues(cues: List<Cue>?) {
        if (!host.isAttached() || !exoInternalSubtitle) return
        exoCues.clear()
        if (cues != null) exoCues.addAll(cues)
        runOnUi {
            applyExoSubtitleSettings()
        }
    }

    private fun applyExoSubtitleSettings() {
        if (!exoInternalSubtitle || host.mController == null || host.mController.getExoSubtitleView() == null) return
        applyExoSubtitleStyle()
        val scale = SubtitleHelper.getExoSubtitleScale() / 100f
        val position = SubtitleHelper.getExoSubtitlePosition()
        host.mController.getExoSubtitleView().setFractionalTextSize(0.0533f * scale)
        host.mController.getExoSubtitleView().setBottomPaddingFraction(limit(0.08f + position / 100f, 0f, 0.9f))

        val displayCues = ArrayList<Cue>()
        for (cue in exoCues) {
            if (cue.bitmap == null) {
                displayCues.add(cue)
                continue
            }
            val builder = cue.buildUpon()
            if (cue.size != Cue.DIMEN_UNSET) {
                builder.setSize(limit(cue.size * scale, 0f, 1f))
            }
            if (cue.bitmapHeight != Cue.DIMEN_UNSET) {
                builder.setBitmapHeight(limit(cue.bitmapHeight * scale, 0f, 1f))
            }
            if (cue.line != Cue.DIMEN_UNSET) {
                builder.setLine(limit(cue.line - position / 100f, 0f, 1f), cue.lineType)
            }
            displayCues.add(builder.build())
        }
        host.mController.getExoSubtitleView().setCues(displayCues)
    }

    private fun applyExoSubtitleStyle() {
        if (host.mController == null || host.mController.getExoSubtitleView() == null) return
        val style = KV.get(HawkConfig.SUBTITLE_TEXT_STYLE, 0)
        val textColor = host.context.resources.getColorStateList(
            if (style == 1) R.color.color_FFB6C1 else R.color.color_FFFFFF,
        ).defaultColor
        host.mController.getExoSubtitleView().setStyle(
            CaptionStyleCompat(
                textColor,
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK,
                Typeface.DEFAULT_BOLD,
            ),
        )
    }

    private fun limit(value: Float, min: Float, max: Float): Float {
        return Math.max(min, Math.min(max, value))
    }

    fun initSubtitleView() {
        val seq = ++subtitleInitSeq
        scope.launch {
            val view = host.mVideoView ?: return@launch
            if (!KV.get(HawkConfig.SUBTITLE_ENABLED, false)) {
                hideExoInternalSubtitle()
                host.mController.getSubtitleView().setVisibility(View.GONE)
                host.mController.getLyricView().setVisibility(View.GONE)
                applySubtitleTrackState()
                return@launch
            }
            val lyricKey = host.scheduler.lyricCacheKey()
            val subtitleKey = host.scheduler.subtitleCacheKey()
            val cachedPaths = async(Dispatchers.IO) {
                cachedPlayPath(lyricKey) to cachedPlayPath(subtitleKey)
            }
            var trackInfo: TrackInfo? = null
            val mediaPlayer = view.mediaPlayer
            applySubtitleTrackState()
            host.mController.getLyricView().setTextSize(if (host.previewMode) 16f else 24f)
            applySubtitleTextSize()
            host.mController.getLyricView().setVisibility(View.GONE)
            host.mController.getLyricView().reset()
            host.mController.getLyricView().bindToMediaPlayer(mediaPlayer)
            host.mController.getLyricView().setMergeSameTime(true)
            host.mController.getLyricView().setLyricMode(true)
            host.mController.getLyricView().setPlaySubtitleCacheKey(lyricKey)
            host.mController.getSubtitleView().hasInternal = false
            host.mController.getSubtitleView().isInternal = false
            hideExoInternalSubtitle()
            val memoryKey = trackMemoryKey()
            if (mediaPlayer is FongmiKernelPlayer && !mediaPlayer.isMpvKernel()) {
                mediaPlayer.setContentKey(memoryKey)
                trackInfo = mediaPlayer.getTrackInfo()
                if (trackInfo != null && !trackInfo.getSubtitle().isEmpty()) {
                    host.mController.getSubtitleView().hasInternal = true
                    exoInternalSubtitle = true
                    host.mController.getExoSubtitleView().setVisibility(View.VISIBLE)
                    mediaPlayer.setInternalSubtitleDelay(SubtitleHelper.getTimeDelay())
                    mediaPlayer.setOnCuesListener { cues -> onExoCues(cues) }
                    applyExoSubtitleSettings()
                }
                mediaPlayer.restoreTracks()
            }
            val cached = cachedPaths.await()
            if (seq != subtitleInitSeq) return@launch
            var lyricPath = host.scheduler.playLyric()
            if (lyricPath.isNullOrEmpty() || !lyricPath.startsWith("data:")) {
                if (!TextUtils.isEmpty(cached.first)) lyricPath = cached.first
            }
            if (!TextUtils.isEmpty(lyricPath)) {
                host.mController.getLyricView().setSubtitlePath(lyricPath)
                host.mController.getLyricView().setVisibility(View.VISIBLE)
            }
            host.mController.getSubtitleView().bindToMediaPlayer(view.mediaPlayer)
            host.mController.getSubtitleView().setPlaySubtitleCacheKey(subtitleKey)
            applySubtitleDecision(mediaPlayer, trackInfo, cached.second)
        }
    }

    private fun applySubtitleDecision(mediaPlayer: KernelPlayer?, trackInfo: TrackInfo?, cachedSubtitlePath: String) {
        val memoryKey = trackMemoryKey()
        subtitleDecisionSeq++
        if (!KV.get(HawkConfig.SUBTITLE_ENABLED, false)) {
            closeSubtitleViews()
            host.mController.getLyricView().setVisibility(View.GONE)
            return
        }
        val record = TrackMemory.loadSubtitle(memoryKey)
        if (TrackMemory.isSubtitleOff(record)) {
            closeSubtitleViews()
            return
        }
        if (TrackMemory.isSubtitleLocal(record)) {
            val path = TrackMemory.localPath(record)
            if (!TextUtils.isEmpty(path) && File(path).exists()) {
                setSubtitle(path)
                return
            }
            LOG.i("echo-track-memory local subtitle gone, fallback: " + path)
        } else if (TrackMemory.isSubtitleOnline(record)) {
            val player = mediaPlayer
            val info = trackInfo
            resolveRememberedOnlineSubtitle(memoryKey, record) {
                applyDefaultSubtitle(player, info, cachedSubtitlePath)
            }
            return
        } else if (TrackMemory.isSubtitleTrack(record) && host.mController.getSubtitleView().hasInternal) {
            showInternalSubtitle(mediaPlayer)
            return
        }
        applyDefaultSubtitle(mediaPlayer, trackInfo, cachedSubtitlePath)
    }

    private fun applyDefaultSubtitle(mediaPlayer: KernelPlayer?, trackInfo: TrackInfo?, subtitlePathCache: String) {
        if (!subtitlePathCache.isEmpty()) {
            hideExoInternalSubtitle()
            host.mController.getSubtitleView().setSubtitlePath(subtitlePathCache)
            return
        }
        val playSubtitle = host.scheduler.playSubtitle()
        if (!playSubtitle.isNullOrEmpty()) {
            hideExoInternalSubtitle()
            host.mController.getSubtitleView().setSubtitlePath(playSubtitle)
            return
        }
        if (!host.mController.getSubtitleView().hasInternal) {
            // 1.45 自动匹配：本集无任何字幕时才试一次（集初始化时一次，
            // 同片 6 小时只试一次；挂不上不打断播放；命中弹 Toast）。
            val title = try {
                host.scheduler.vod()?.name
            } catch (e: Throwable) {
                null
            }
            if (!title.isNullOrEmpty()) {
                val activity = host.mActivity
                SubtitleAutoMatch.maybeLoad(title) { path, name ->
                    if (!host.isAttached()) return@maybeLoad
                    setSubtitle(path)
                    try {
                        activity?.let { Toast.makeText(it, "已自动加载字幕：" + name, Toast.LENGTH_SHORT).show() }
                    } catch (e: Throwable) {
                    }
                }
            }
            return
        }
        ensureInternalSubtitleTrackSelected(mediaPlayer, trackInfo)
        showInternalSubtitle(mediaPlayer)
    }

    private fun showInternalSubtitle(mediaPlayer: KernelPlayer?) {
        val kernel = mediaPlayer as? FongmiKernelPlayer
        if (kernel != null && !kernel.isMpvKernel()) {
            KV.put(HawkConfig.SUBTITLE_ENABLED, true)
            kernel.setInternalSubtitleDelay(SubtitleHelper.getTimeDelay())
            exoInternalSubtitle = true
            host.mController.getExoSubtitleView().setVisibility(View.VISIBLE)
            applyExoSubtitleSettings()
        }
    }

    private fun ensureInternalSubtitleTrackSelected(mediaPlayer: KernelPlayer?, trackInfo: TrackInfo?) {
        val kernel = mediaPlayer as? FongmiKernelPlayer
        if (kernel != null && !kernel.isMpvKernel()) {
            kernel.ensureSubtitleTrackSelected()
        }
    }

    private fun resolveRememberedOnlineSubtitle(memoryKey: String, record: String?, fallback: Runnable) {
        val releaseUrl = TrackMemory.onlineRelease(record)
        if (TextUtils.isEmpty(releaseUrl) || host.mActivity !is ViewModelStoreOwner) {
            runOnUi(fallback)
            return
        }
        val vod = host.scheduler.vod()
        val series = if (vod == null) null else host.scheduler.currentSeries(vod.playFlag, vod.playIndex)
        val episodeName = series?.name ?: ""
        val fileNameHint = TrackMemory.onlineFileName(record)
        val episodeKey = host.scheduler.progressKey()
        val decisionSeq = subtitleDecisionSeq
        LOG.i("echo-track-memory online subtitle: release=" + releaseUrl + " episode=" + episodeName)
        ViewModelProvider(host.mActivity as ViewModelStoreOwner).get(SubtitleViewModel::class.java).pickEpisodeSubtitle(
            releaseUrl, episodeName, fileNameHint,
            { subtitle ->
                runOnUi {
                    if (!isSubtitleResultCurrent(memoryKey, episodeKey, decisionSeq)) return@runOnUi
                    val url = subtitle?.url
                    if (TextUtils.isEmpty(url)) {
                        LOG.i("echo-track-memory online subtitle empty url, fallback")
                        fallback.run()
                        return@runOnUi
                    }
                    LOG.i("echo-track-memory online subtitle picked: " + subtitle.name)
                    setSubtitle(url)
                }
            },
            {
                runOnUi {
                    if (!isSubtitleResultCurrent(memoryKey, episodeKey, decisionSeq)) return@runOnUi
                    LOG.i("echo-track-memory online subtitle miss, fallback")
                    fallback.run()
                }
            },
        )
    }

    private fun isSubtitleResultCurrent(memoryKey: String, episodeKey: String?, decisionSeq: Int): Boolean {
        if (!host.isAttached() || subtitleDecisionSeq != decisionSeq) return false
        if (!TextUtils.equals(memoryKey, trackMemoryKey())) return false
        return TextUtils.equals(episodeKey, host.scheduler.progressKey())
    }

    private fun runOnUi(action: Runnable) {
        val activity = host.mActivity
        if (activity == null) return
        activity.runOnUiThread(action)
    }

    private fun closeSubtitleViews() {
        try {
            hideExoInternalSubtitle()
            host.mController.getLyricView().setVisibility(View.GONE)
            host.mController.getSubtitleView().setVisibility(View.GONE)
            host.mController.getSubtitleView().destroy()
            host.mController.getSubtitleView().clearSubtitleCache()
            host.mController.getSubtitleView().isInternal = false
        } catch (e: Exception) {
            LOG.e("echo-close-subtitle-error:" + e.message)
        }
    }

    private fun applySubtitleTrackState() {
        val player = host.mVideoView?.mediaPlayer as? FongmiKernelPlayer ?: return
        player.setTextTrackDisabled(!KV.get(HawkConfig.SUBTITLE_ENABLED, false))
    }

    fun closeSubtitles() {
        if (host.mVideoView == null) return
        closeSubtitleViews()
        subtitleDecisionSeq++
        KV.put(HawkConfig.SUBTITLE_ENABLED, false)
        TrackMemory.saveSubtitle(trackMemoryKey(), TrackMemory.SUBTITLE_OFF)
        applySubtitleTrackState()
    }

    fun toggleSubtitles(): Boolean {
        if (host.mVideoView == null) return KV.get(HawkConfig.SUBTITLE_ENABLED, false)
        if (KV.get(HawkConfig.SUBTITLE_ENABLED, false)) {
            closeSubtitles()
            host.danmuLoadController?.setTemporarilyClosed(true)
            return false
        }
        KV.put(HawkConfig.SUBTITLE_ENABLED, true)
        TrackMemory.clearSubtitle(trackMemoryKey())
        initSubtitleView()
        host.danmuLoadController?.setTemporarilyClosed(false)
        return true
    }

    fun trackMemoryKey(): String {
        val vod = host.scheduler?.vod()
        if (vod == null) return ""
        return TrackMemory.contentKey(vod.sourceKey, vod.id)
    }

    private suspend fun cachedPlayPath(cacheKey: String?): String {
        if (TextUtils.isEmpty(cacheKey)) return ""
        val cached = AppGraph.cacheRepository.get(MD5.string2MD5(cacheKey))
        if (cached !is String) return ""
        val path = cached
        if (TextUtils.isEmpty(path)) return ""
        if (path.startsWith("data:")) return path
        return if (File(path).exists()) path else ""
    }

    fun clearLyricView() {
        if (host.mController == null || host.mController.getLyricView() == null) return
        host.mController.getLyricView().setVisibility(View.GONE)
        host.mController.getLyricView().destroy()
        host.mController.getLyricView().setText("")
    }

    fun applySubtitleTextSize() {
        if (host.mController == null || host.mController.getSubtitleView() == null) return
        val size = SubtitleHelper.getTextSize(host.mActivity)
        host.mController.getSubtitleView().setTextSize(if (host.previewMode) size * 0.6f else size.toFloat())
    }
}
