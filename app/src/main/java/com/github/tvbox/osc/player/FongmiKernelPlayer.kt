package com.github.tvbox.osc.player

import android.content.Context
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.fongmi.android.tv.App
import com.fongmi.android.tv.player.PlayerManager
import com.fongmi.android.tv.player.engine.PlayerEngine
import com.fongmi.android.tv.player.media.PlaySpec
import com.fongmi.android.tv.player.mpv.MpvErrorMessageProvider
import com.fongmi.android.tv.player.track.TrackUtil
import com.fongmi.android.tv.setting.DecodeSetting
import com.fongmi.android.tv.setting.PlayerSetting
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.engine.AudioCodecChoice
import com.github.tvbox.osc.player.engine.NetworkSpeed
import com.github.tvbox.osc.player.state.PlayState
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.FloatLog
import com.github.tvbox.osc.util.TrackMemory
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FongmiKernelPlayer(
    context: Context,
    private val preferMpv: Boolean,
) : KernelPlayer() {

    fun interface OnCuesListener {
        fun onCues(cues: List<Cue>)
    }

    companion object {
        @Volatile
        private var preferSoftwareDecode = false

        @JvmStatic
        fun isPreferSoftwareDecode(): Boolean = preferSoftwareDecode

        @JvmStatic
        fun setPreferSoftwareDecode(prefer: Boolean) {
            preferSoftwareDecode = prefer
        }

        private val LANG_MAP = hashMapOf(
            "zh" to "国语",
            "zh-cn" to "国语",
            "cmn" to "国语",
            "chi" to "国语",
            "zho" to "国语",
            "chs" to "国语",
            "yue" to "粤语",
            "zh-hk" to "粤语",
            "zh-yue" to "粤语",
            "en" to "英语",
            "en-us" to "英语",
            "eng" to "英语",
            "ja" to "日语",
            "jpn" to "日语",
            "ko" to "韩语",
            "kor" to "韩语",
            "th" to "泰语",
            "tha" to "泰语",
        )
    }

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mpvErrorMessageProvider = MpvErrorMessageProvider()

    private var manager: PlayerManager? = null
    private var managerGeneration = 0
    private var attachedPlayer: Player? = null
    private var rebuildingManager = false
    private var cachedSpec: PlaySpec? = null

    /** 2.0 直驱：状态只如实上报，不推导、不闩锁、不补播。 */
    private val _stateFlow = MutableStateFlow(PlayState.IDLE)
    private var lastReportedState: PlayState = PlayState.IDLE
    private var preparedNotified = false
    private var startAborted = false
    private var wasBuffering = false

    private var pendingSpeed: Float? = null
    private var pendingVolume: Float? = null
    private var pendingLooping: Boolean? = null
    private var pendingDecodeHard: Boolean? = null
    private var pendingOutputKind = 0
    private var pendingOutputSurface: Surface? = null
    private var pendingOutputHolder: SurfaceHolder? = null
    private var pendingOutputView: SurfaceView? = null

    private var audioOnlyRequested = false
    private var useDiskCacheFlag = false
    private var contentKey = ""
    private var defaultSubtitleSelected = false
    private var cuesListener: OnCuesListener? = null
    private var subtitleDelayMs = 0
    private var frameTracking = false
    private var frameWindowStartMs = 0L
    private var renderedFrameCount = 0
    private var measuredFrameRateValue = 0f
    private var droppedFramesCount = 0L
    private var rebufferCountValue = 0

    fun isMpvKernel(): Boolean = manager?.getEngine() == PlayerSetting.ENGINE_MPV

    /**
     * PlayerManager 下的 Exo/MPV 都走 Media3 焦点；壳子再 requestAudioFocus 会挤掉引擎焦点，
     * 日志里表现为 playWhenReady=false reason=2（AUDIO_FOCUS_LOSS），切 MPV 后尤甚。
     */
    fun isFocusManagedByEngine(): Boolean = manager != null

    /** 焦点丢失单发自救已用过（每轮起播一次）；置位后 genuine 占焦点不再拨回，不循环。 */
    private var focusLossResumeDone = false

    fun mediaPlayer(): Player? = manager?.player

    private fun newManagerCallback(generation: Int) = object : PlayerManager.Callback {
        private fun alive(): Boolean {
            if (generation != managerGeneration) {
                LOG.i("echo-manager stale callback ignored generation=$generation current=$managerGeneration")
                return false
            }
            return true
        }

        override fun onPrepare() {
            if (!alive()) return
            LOG.i("echo-manager callback onPrepare")
        }

        override fun onTracksChanged() {
            if (!alive()) return
            restoreTracks()
        }

        override fun onDecodeChanged() {
            if (!alive()) return
            val current = manager ?: return
            val soft = !current.isHardDecode()
            pendingDecodeHard = !soft
            VesperEnginePolicy.mpvDecodeOverride = !soft
            setPreferSoftwareDecode(soft)
            PlayerCodecStats.videoDecodeKind =
                if (soft) PlayerDecodeKind.SOFTWARE else PlayerDecodeKind.HARDWARE
            LOG.i("echo-manager decode sync: soft=$soft")
        }

        override fun onMediaOptionsChanged() {
            if (!alive()) return
            LOG.i("echo-manager callback onMediaOptionsChanged")
        }

        override fun onError(msg: String) {
            if (!alive()) return
            LOG.i("echo-manager callback onError: $msg")
            FloatLog.append("app", null, "manager callback onError: $msg")
            setState(PlayState.ERROR)
            mPlayerEventListener?.onError()
        }

        override fun onPlayerRebuild(newPlayer: Player) {
            if (!alive()) return
            LOG.i("echo-manager kernel switch player rebuild")
            attachPlayer(newPlayer)
            // 直驱：重建时新实例沿用当前播/停状态（单状态、无意图位）。
            if (lastReportedState == PlayState.PREPARING || lastReportedState == PlayState.PREPARED || lastReportedState == PlayState.PLAYING || lastReportedState == PlayState.BUFFERING || lastReportedState == PlayState.BUFFERED) {
                if (!newPlayer.isPlaying) {
                    newPlayer.playWhenReady = true
                    FloatLog.append("app", null, "rebuild pwr=true state=" + lastReportedState)
                    runCatching { manager?.play() }
                }
            }
        }

        override fun onDanmakuSourceChanged(uri: android.net.Uri?) {
            if (!alive()) return
            LOG.i("echo-manager callback onDanmakuSourceChanged")
        }

        override fun onDanmakuConfigChanged(config: androidx.media3.ui.danmaku.DanmakuConfig) {
            if (!alive()) return
            LOG.i("echo-manager callback onDanmakuConfigChanged")
        }

        override fun onDanmakuEnabledChanged(enabled: Boolean) {
            if (!alive()) return
            LOG.i("echo-manager callback onDanmakuEnabledChanged: $enabled")
        }

        override fun onDanmakuSent(text: String) {
            if (!alive()) return
            LOG.i("echo-manager callback onDanmakuSent")
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            dispatchPlaybackState(state)
            reportState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            reportState()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            FloatLog.append("app", null, "playWhenReady=$playWhenReady reason=$reason")
            // 切 Exo→MPV 后常见：首帧已出 → reason=2 焦点丢失 → 停在暂停，需点两次。
            // 起播/缓冲/播放意图明确时拨回一次（不限 lastState==PLAYING，避免 BUFFERING 窗口漏掉）。
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                val player = manager?.player
                val want = lastReportedState == PlayState.PLAYING
                    || lastReportedState == PlayState.BUFFERING
                    || lastReportedState == PlayState.BUFFERED
                    || lastReportedState == PlayState.PREPARING
                    || lastReportedState == PlayState.PREPARED
                if (player != null && want && !focusLossResumeDone) {
                    focusLossResumeDone = true
                    player.playWhenReady = true
                    runCatching { manager?.play() }
                    FloatLog.append("app", null, "focus loss auto resume state=" + lastReportedState)
                }
            }
            reportState()
        }

        override fun onRenderedFirstFrame() {
            reportState()
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            mPlayerEventListener?.onVideoSizeChanged(videoSize.width, videoSize.height)
            val rotation = selectedTrackFormat(C.TRACK_TYPE_VIDEO)?.rotationDegrees ?: 0
            if (rotation > 0) {
                mPlayerEventListener?.onInfo(MEDIA_INFO_VIDEO_ROTATION_CHANGED, rotation)
            }
        }

        override fun onCues(cues: List<Cue>) {
            val target = cuesListener ?: return
            if (subtitleDelayMs > 0) {
                mainHandler.postDelayed({ target.onCues(cues) }, subtitleDelayMs.toLong())
            } else {
                target.onCues(cues)
            }
        }
    }

    private val frameListener = VideoFrameMetadataListener { _, _, _, _ ->
        renderedFrameCount++
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long,
        ) {
            droppedFramesCount += droppedFrames
        }
    }


    private fun dispatchPlaybackState(state: Int) {
        if (!preparedNotified) {
            if (state == Player.STATE_READY) {
                preparedNotified = true
                val readyPlayer = manager?.player
                LOG.i("echo-firstframe: pwr=" + readyPlayer?.playWhenReady + " playing=" + readyPlayer?.isPlaying)
                FloatLog.append("app", null, "firstframe pwr=" + readyPlayer?.playWhenReady + " playing=" + readyPlayer?.isPlaying)
                // 切内核后首帧若仍 pause，强制开播（避免 UI 已出画却停在暂停）
                if (readyPlayer != null && readyPlayer.playWhenReady != true) {
                    readyPlayer.playWhenReady = true
                    runCatching { manager?.play() }
                    FloatLog.append("app", null, "firstframe force pwr=true")
                }
                val listener = mPlayerEventListener ?: return
                listener.onPrepared()
                listener.onInfo(MEDIA_INFO_RENDERING_START, 0)
            }
            return
        }
        val player = manager?.player
        when (state) {
            Player.STATE_BUFFERING -> {
                wasBuffering = true
                mPlayerEventListener?.onInfo(MEDIA_INFO_BUFFERING_START, player?.bufferedPercentage ?: 0)
            }

            Player.STATE_READY -> {
                if (wasBuffering) {
                    wasBuffering = false
                    rebufferCountValue++
                }
                mPlayerEventListener?.onInfo(MEDIA_INFO_BUFFERING_END, player?.bufferedPercentage ?: 0)
            }

            Player.STATE_ENDED -> {
                mPlayerEventListener?.onCompletion()
            }
        }
    }

    /** 2.0 直驱状态上报：只按引擎当前事实映射，不推导、不闩锁、不补播。 */
    private fun reportState() {
        if (startAborted) {
            setState(PlayState.START_ABORT)
            return
        }
        val player = manager?.player ?: return
        val next = when (player.playbackState) {
            Player.STATE_IDLE -> PlayState.IDLE
            Player.STATE_BUFFERING -> PlayState.BUFFERING
            Player.STATE_ENDED -> PlayState.COMPLETED
            Player.STATE_READY -> when {
                player.isPlaying -> PlayState.PLAYING
                !player.playWhenReady -> PlayState.PAUSED
                else -> PlayState.BUFFERED
            }
            else -> PlayState.IDLE
        }
        setState(next)
    }

    private fun setState(next: PlayState) {
        if (next == lastReportedState) return
        FloatLog.append("app", null, "state " + next + " playWhenReady=" + (manager?.player?.playWhenReady) + " isPlaying=" + (manager?.player?.isPlaying) + " playbackState=" + (manager?.player?.playbackState))
        lastReportedState = next
        _stateFlow.value = next
        mPlayerEventListener?.onKernelPlayStateChanged()
    }

    private fun attachPlayer(player: Player) {
        FloatLog.append("app", null, "attachPlayer prevNull=" + (attachedPlayer == null) + " pwr=" + player.playWhenReady)
        val previous = attachedPlayer
        if (previous != null && previous !== player) {
            detachPlayer(previous)
        }
        attachedPlayer = player
        player.addListener(playerListener)
        if (player is ExoPlayer) {
            player.addAnalyticsListener(analyticsListener)
            if (frameTracking) player.setVideoFrameMetadataListener(frameListener)
        }
        applyPendingOutputs(player)
        pendingSpeed?.let { manager?.setSpeed(it) }
        pendingVolume?.let { setPlayerVolume(player, it) }
        pendingLooping?.let { setPlayerLooping(player, it) }
    }

    private fun detachPlayer(player: Player?) {
        if (player == null) return
        player.removeListener(playerListener)
        if (player is ExoPlayer) {
            player.removeAnalyticsListener(analyticsListener)
            if (frameTracking) player.clearVideoFrameMetadataListener(frameListener)
        }
        if (attachedPlayer === player) {
            attachedPlayer = null
        }
    }

    private fun applyPendingOutputs(player: Player) {
        when (pendingOutputKind) {
            1 -> player.setVideoSurface(pendingOutputSurface)
            2 -> player.setVideoSurfaceHolder(pendingOutputHolder)
            3 -> player.setVideoSurfaceView(pendingOutputView)
        }
    }

    private fun setPlayerVolume(player: Player, volume: Float) {
        if (player.isCommandAvailable(Player.COMMAND_SET_VOLUME)) player.volume = volume
    }

    private fun setPlayerLooping(player: Player, looping: Boolean) {
        if (player.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE)) {
            player.repeatMode = if (looping) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        }
    }

    private fun rebuildManagerForExo() {
        if (rebuildingManager) {
            LOG.i("echo-manager kernel switch rebuild ignored reentrant")
            return
        }
        rebuildingManager = true
        try {
            LOG.i("echo-manager kernel switch rebuild start")
            val old = manager
            if (old != null) {
                detachPlayer(old.player)
                old.release()
                LOG.i("echo-manager kernel switch old manager released")
            }
            PlayerSetting.setPreferredMpv(false)
            val generation = ++managerGeneration
            val fresh = PlayerManager(newManagerCallback(generation))
            PlayerSetting.setPreferredMpv(preferMpv)
            manager = fresh
            val hard = pendingDecodeHard ?: !preferSoftwareDecode
            if (!hard) fresh.setDecodeMode(PlayerEngine.SOFT)
            attachPlayer(fresh.player)
            LOG.i("echo-manager kernel switch rebuild done engine=${fresh.getEngine()}")
        } finally {
            rebuildingManager = false
        }
        LOG.i("echo-manager rebuild for exo: smb/dash-data url forced EXO")
    }

    override fun initPlayer() {
        App.init(appContext)
        PlayerSetting.setPreferredMpv(preferMpv)
        val generation = ++managerGeneration
        val fresh = PlayerManager(newManagerCallback(generation))
        manager = fresh
        val hard = pendingDecodeHard ?: !preferSoftwareDecode
        if (!hard) fresh.setDecodeMode(PlayerEngine.SOFT)
        attachPlayer(fresh.player)
        LOG.i("echo-manager fongmi kernel init: preferMpv=$preferMpv engine=${fresh.getEngine()}")
        LOG.i("echo-manager kernel switch new manager bound engine=${fresh.getEngine()}")
    }

    override fun setDataSource(path: String, headers: Map<String, String>?) {
        defaultSubtitleSelected = false
        val current = manager
        if (current != null && current.getEngine() == PlayerSetting.ENGINE_MPV &&
            (path.startsWith("smb://") || path.startsWith("data:application/dash+xml;base64,"))
        ) {
            rebuildManagerForExo()
        }
        cachedSpec = PlaySpec.from(contentKey, path, headers, null)
        LOG.i("echo-manager fongmi setDataSource: $path")
    }

    override fun prepareAsync() {
        FloatLog.append("app", null, "prepareAsync specNull=" + (cachedSpec == null) + " managerNull=" + (manager == null))
        val spec = cachedSpec ?: return
        val current = manager ?: return
        preparedNotified = false
        startAborted = false
        wasBuffering = false
        focusLossResumeDone = false
        setState(PlayState.PREPARING)
        com.github.tvbox.osc.player.effect.PictureEffects.onPrepare(this, isTunnelingEnabled)
        // MPV 换集若不卸旧 item，易继续解上一集或卡死；Exo 相对能扛
        if (current.getEngine() == PlayerSetting.ENGINE_MPV) {
            runCatching { current.stop() }
            runCatching { current.clearMediaItems() }
        }
        current.browse(spec, startPosition)
        setOptions()
        focusLossResumeDone = false
        current.player?.playWhenReady = true
        current.play()
        FloatLog.append("app", null, "prepareAsync started engine=" + (if (current.getEngine() == PlayerSetting.ENGINE_MPV) "MPV" else "EXO") + " pwr=" + current.player?.playWhenReady + " playing=" + current.isPlaying)
        markStartPositionApplied()
    }

    override fun start(): Boolean {
        val current = manager ?: return false
        startAborted = false
        focusLossResumeDone = false
        current.player?.playWhenReady = true
        current.play()
        FloatLog.append("app", null, "start started pwr=" + current.player?.playWhenReady + " playing=" + current.isPlaying)
        setState(PlayState.PLAYING)
        return true
    }

    override fun pause(): Boolean {
        val current = manager ?: return false
        FloatLog.append("app", null, "pause playing=" + current.isPlaying + " playWhenReady=" + current.player?.playWhenReady + " state=" + lastReportedState)
        if (!current.isPlaying && current.player?.playWhenReady != true) return false
        current.pause()
        current.player?.playWhenReady = false
        setState(PlayState.PAUSED)
        return true
    }

    override fun stop(): Boolean {
        val current = manager ?: return false
        if (lastReportedState == PlayState.PAUSED) return false
        current.stop()
        setState(PlayState.IDLE)
        return true
    }

    override fun abortStart() {
        startAborted = true
        setState(PlayState.START_ABORT)
    }

    override fun reset() {
        defaultSubtitleSelected = false
        preparedNotified = false
        startAborted = false
        wasBuffering = false
        manager?.stop()
        manager?.clearMediaItems()
        setState(PlayState.IDLE)
    }

    override fun keepRenderViewOnReset(): Boolean = true

    override fun resetTrackSelection() {
        val player = manager?.player ?: return
        TrackUtil.reset(player)
        LOG.i("echo-setTrack: clear stale overrides on content switch")
    }

    override fun getTrackInfo(): TrackInfo {
        val data = TrackInfo()
        val player = manager?.player ?: return data
        val tracks = player.currentTracks
        for (groupIndex in 0 until tracks.groups.size) {
            val group = tracks.groups[groupIndex]
            val type = group.type
            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_VIDEO && type != C.TRACK_TYPE_TEXT) continue
            for (trackIndex in 0 until group.length) {
                val fmt = group.getTrackFormat(trackIndex)
                if (type == C.TRACK_TYPE_TEXT && isUndeclaredClosedCaptionTrack(fmt)) continue
                val language = getLanguage(fmt)
                val detail = if (type == C.TRACK_TYPE_VIDEO) getVideoName(fmt) else getName(fmt)
                val bean = TrackInfoBean()
                bean.language = language
                bean.name = buildDisplayName(
                    if (type == C.TRACK_TYPE_AUDIO) appContext.getString(R.string.player_menu_audio_track)
                    else if (type == C.TRACK_TYPE_VIDEO) appContext.getString(R.string.player_menu_video_track)
                    else appContext.getString(R.string.player_menu_subtitle),
                    if (type == C.TRACK_TYPE_AUDIO) data.getAudio().size + 1
                    else if (type == C.TRACK_TYPE_VIDEO) data.getVideo().size + 1
                    else data.getSubtitle().size + 1,
                    language,
                    detail,
                )
                bean.renderId = groupIndex
                bean.trackGroupId = 0
                bean.groupIndex = groupIndex
                bean.index = trackIndex
                bean.trackId = trackIndex
                bean.selected = group.isSelected && group.isTrackSelected(trackIndex)
                bean.bitmapSubtitle = type == C.TRACK_TYPE_TEXT && isBitmapSubtitle(fmt)
                bean.type = type
                bean.formatKey = formatKey(fmt, type)
                if (type == C.TRACK_TYPE_AUDIO) {
                    data.addAudio(bean)
                } else if (type == C.TRACK_TYPE_VIDEO) {
                    data.addVideo(bean)
                } else {
                    data.addSubtitle(bean)
                }
            }
        }
        return data
    }

    override fun setTrack(track: TrackInfoBean?) {
        if (track == null) return
        if (!applyTrack(track.renderId, track.trackId)) return
        val fmt = trackFormat(track.renderId, track.trackId)
        FloatLog.append("app", "TrackDialog", "track type=" + track.type + " id=" + fmt?.id + " label=" + fmt?.label + " lang=" + fmt?.language + " codec=" + fmt?.codecs + " mime=" + fmt?.sampleMimeType + " name=" + track.name)
        val key = formatKey(fmt, track.type)
        if (track.type == C.TRACK_TYPE_TEXT) {
            TrackMemory.saveSubtitle(contentKey, key)
        } else {
            TrackMemory.saveTrack(contentKey, track.type, key)
        }
    }

    fun selectTrack(track: TrackInfoBean?) {
        if (track == null) return
        applyTrack(track.renderId, track.trackId)
    }

    fun restoreTracks() {
        restoreByMemory(C.TRACK_TYPE_AUDIO)
        restoreByMemory(C.TRACK_TYPE_VIDEO)
        restoreSubtitleByMemory()
    }

    private fun restoreByMemory(trackType: Int) {
        val remembered = TrackMemory.loadTrack(contentKey, trackType) ?: return
        val position = locate(trackType, remembered) ?: run {
            LOG.i("echo-track-memory miss type=$trackType key=$contentKey fp=$remembered")
            return
        }
        if (applyTrack(position[0], position[1])) {
            LOG.i("echo-track-memory restore type=$trackType fp=$remembered")
        }
    }

    private fun restoreSubtitleByMemory() {
        val record = TrackMemory.loadSubtitle(contentKey) ?: return
        if (!TrackMemory.isSubtitleTrack(record)) return
        val position = locate(C.TRACK_TYPE_TEXT, record)
        if (position == null) {
            LOG.i("echo-track-memory text miss, use default: $record")
            selectDefaultSubtitlePick()
            return
        }
        if (applyTrack(position[0], position[1])) {
            LOG.i("echo-track-memory restore text fp=$record")
        }
    }

    private fun locate(trackType: Int, fingerprint: String): IntArray? {
        val player = manager?.player ?: return null
        val tracks = player.currentTracks
        val keys = ArrayList<String>()
        val positions = ArrayList<IntArray>()
        for (groupIndex in 0 until tracks.groups.size) {
            val group = tracks.groups[groupIndex]
            if (group.type != trackType) continue
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                if (trackType == C.TRACK_TYPE_TEXT && isUndeclaredClosedCaptionTrack(format)) continue
                keys.add(formatKey(format, trackType))
                positions.add(intArrayOf(groupIndex, trackIndex))
            }
        }
        val index = TrackMemory.pick(keys, fingerprint)
        return if (index < 0) null else positions[index]
    }

    private fun applyTrack(groupIndex: Int, trackIndex: Int): Boolean {
        try {
            val player = manager?.player ?: return false
            val tracks = player.currentTracks
            if (groupIndex < 0 || groupIndex >= tracks.groups.size) {
                LOG.i("echo-setTrack: No group found")
                return false
            }
            val group = tracks.groups[groupIndex]
            if (trackIndex < 0 || trackIndex >= group.length) {
                LOG.i("echo-setTrack: Invalid track index - group:$groupIndex track:$trackIndex")
                return false
            }
            val override = TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
            val params = player.trackSelectionParameters.buildUpon().setOverrideForType(override).build()
            player.trackSelectionParameters = params
            val applied = group.getTrackFormat(trackIndex)
            LOG.i(
                "echo-setTrack applied: group=$groupIndex track=$trackIndex " +
                    "mime=${applied?.sampleMimeType} channels=${applied?.channelCount ?: -1} codecs=${applied?.codecs} trackKey=$contentKey",
            )
            return true
        } catch (e: Exception) {
            LOG.i("echo-setTrack error: ${e.message}")
            return false
        }
    }

    fun setTextTrackDisabled(disabled: Boolean) {
        try {
            val player = manager?.player ?: return
            val params = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, disabled).build()
            player.trackSelectionParameters = params
        } catch (e: Exception) {
            LOG.i("echo-setTextTrackDisabled error: ${e.message}")
        }
    }

    private fun trackFormat(groupIndex: Int, trackIndex: Int): Format? {
        val player = manager?.player ?: return null
        val tracks = player.currentTracks
        if (groupIndex < 0 || groupIndex >= tracks.groups.size) return null
        val group = tracks.groups[groupIndex]
        if (trackIndex < 0 || trackIndex >= group.length) return null
        return group.getTrackFormat(trackIndex)
    }

    private fun formatKey(fmt: Format?, trackType: Int): String {
        if (fmt == null) return ""
        val codec = firstNonEmpty(fmt.codecs, mimeSubtype(fmt))
        if (trackType == C.TRACK_TYPE_AUDIO) {
            return TrackMemory.audioFingerprint(getLanguage(fmt), codec, fmt.channelCount)
        }
        if (trackType == C.TRACK_TYPE_VIDEO) {
            return TrackMemory.videoFingerprint(codec, fmt.width, fmt.height)
        }
        return TrackMemory.textFingerprint(getLanguage(fmt), fmt.id, fmt.label, codec)
    }

    private fun mimeSubtype(fmt: Format?): String {
        val mime = fmt?.sampleMimeType ?: return ""
        if (!mime.contains("/")) return ""
        return mime.substring(mime.indexOf('/') + 1)
    }

    private fun firstNonEmpty(first: String?, second: String?): String =
        if (first != null && first.isNotEmpty()) first else (second ?: "")

    fun loadDefaultSubtitleTrack() {
        if (defaultSubtitleSelected) return
        if (TrackMemory.loadSubtitle(contentKey) != null) {
            LOG.i("echo-track-memory subtitle decision exists, skip default")
            defaultSubtitleSelected = true
            return
        }
        selectDefaultSubtitlePick()
    }

    fun ensureSubtitleTrackSelected() {
        val subtitles = getTrackInfo().getSubtitle()
        if (subtitles.isEmpty()) return
        for (subtitle in subtitles) {
            if (subtitle.selected) return
        }
        selectDefaultSubtitlePick()
    }

    private fun selectDefaultSubtitlePick() {
        val subtitles = getTrackInfo().getSubtitle()
        if (subtitles.isEmpty()) return
        defaultSubtitleSelected = true
        var target = subtitles[0]
        for (subtitle in subtitles) {
            if ("国语" == subtitle.language) {
                target = subtitle
                break
            }
        }
        selectTrack(target)
    }

    private fun isBitmapSubtitle(format: Format?): Boolean {
        val mimeType = format?.sampleMimeType ?: return false
        val lower = mimeType.lowercase(Locale.getDefault())
        return lower.contains("pgs") || lower.contains("dvb") || lower.contains("vobsub")
    }

    private fun isUndeclaredClosedCaptionTrack(format: Format?): Boolean {
        if (format == null || format.accessibilityChannel != Format.NO_VALUE) return false
        return MimeTypes.APPLICATION_CEA608 == format.sampleMimeType ||
            MimeTypes.APPLICATION_CEA708 == format.sampleMimeType
    }

    private fun getLanguage(fmt: Format): String {
        val language = matchLanguage(fmt.language)
        if (language.isNotEmpty()) {
            return language
        }
        return matchLanguage(
            (fmt.label ?: "") + " " + (fmt.id ?: "") + " " + (fmt.codecs ?: ""),
        )
    }

    private fun matchLanguage(text: String?): String {
        if (text == null) return ""
        val value = text.lowercase(Locale.getDefault())
        LANG_MAP[value]?.let { return it }
        if (value.contains("yue") || value.contains("cantonese") || value.contains("粤") || value.contains("广东")) {
            return "粤语"
        }
        if (value.contains("zh") || value.contains("chi") || value.contains("zho") || value.contains("chs") ||
            value.contains("cht") || value.contains("cmn") || value.contains("中") ||
            value.contains("国语") || value.contains("普通话")
        ) {
            return "国语"
        }
        if (value.contains("en") || value.contains("eng") || value.contains("english") || value.contains("英")) {
            return "英语"
        }
        if (value.contains("ja") || value.contains("jpn") || value.contains("japanese") || value.contains("日")) {
            return "日语"
        }
        if (value.contains("ko") || value.contains("kor") || value.contains("korean") || value.contains("韩")) {
            return "韩语"
        }
        if (value.contains("tha") || value.contains("thai") || value.contains("th")) {
            return "泰语"
        }
        return ""
    }

    private fun getName(fmt: Format): String {
        val channelLabel: String = if (fmt.channelCount <= 0) {
            ""
        } else if (fmt.channelCount == 1) {
            appContext.getString(R.string.player_channel_mono)
        } else if (fmt.channelCount == 2) {
            appContext.getString(R.string.player_channel_stereo)
        } else {
            appContext.getString(R.string.player_channel_count, fmt.channelCount)
        }
        var codec = ""
        val codecs = fmt.codecs
        if (!codecs.isNullOrEmpty()) {
            codec = codecs.uppercase(Locale.getDefault())
        }
        val mime = fmt.sampleMimeType
        if (mime != null && mime.contains("/")) {
            if (codec.isEmpty()) {
                codec = mime.substring(mime.indexOf('/') + 1).uppercase(Locale.getDefault())
            }
        }
        val builder = StringBuilder()
        appendPart(builder, fmt.label)
        appendPart(builder, codec)
        appendPart(builder, channelLabel)
        return builder.toString()
    }

    private fun getVideoName(fmt: Format): String {
        val builder = StringBuilder()
        appendPart(builder, fmt.label)
        if (fmt.width > 0 && fmt.height > 0) {
            appendPart(builder, "${fmt.width}x${fmt.height}")
        }
        val codecs = fmt.codecs
        val mime = fmt.sampleMimeType
        if (!codecs.isNullOrEmpty()) {
            appendPart(builder, codecs.uppercase(Locale.getDefault()))
        } else if (mime != null && mime.contains("/")) {
            appendPart(builder, mime.substring(mime.indexOf('/') + 1).uppercase(Locale.getDefault()))
        }
        return builder.toString()
    }

    private fun buildDisplayName(prefix: String, number: Int, language: String?, detail: String?): String {
        val builder = StringBuilder(prefix).append(number)
        if (!language.isNullOrEmpty()) {
            builder.append(" - ").append(language)
        }
        if (!detail.isNullOrEmpty()) {
            builder.append(" ").append(detail)
        }
        return builder.toString()
    }

    private fun appendPart(builder: StringBuilder, value: String?) {
        if (value.isNullOrEmpty()) return
        if (builder.isNotEmpty()) builder.append(" ")
        builder.append(value)
    }

    override val isPlaying: Boolean
        get() = manager?.isPlaying ?: false

    override val playState: PlayState
        get() = lastReportedState

    override val stateFlow: StateFlow<PlayState>
        get() = _stateFlow

    override fun seekTo(time: Long) {
        manager?.seekTo(time)
    }

    override fun release() {
        managerGeneration++
        rebuildingManager = false
        val current = manager
        if (current != null) {
            detachPlayer(current.player)
            current.release()
            LOG.i("echo-manager kernel switch old manager released")
        }
        manager = null
        cachedSpec = null
        mainHandler.removeCallbacksAndMessages(null)
        com.github.tvbox.osc.player.effect.PictureEffects.onPlayerReleased(this)
        setState(PlayState.IDLE)
    }

    override val currentPosition: Long
        get() = manager?.position ?: 0L

    override val duration: Long
        get() {
            val d = manager?.duration ?: return 0L
            return if (d == C.TIME_UNSET) 0L else d
        }

    override val bufferedPercentage: Int
        get() = manager?.player?.bufferedPercentage ?: 0

    override fun setSurface(surface: Surface?) {
        pendingOutputKind = 1
        pendingOutputSurface = surface
        pendingOutputHolder = null
        pendingOutputView = null
        manager?.player?.setVideoSurface(surface)
    }

    override fun setDisplay(holder: SurfaceHolder?) {
        pendingOutputKind = 2
        pendingOutputHolder = holder
        pendingOutputSurface = null
        pendingOutputView = null
        manager?.player?.setVideoSurfaceHolder(holder)
    }

    override fun bindSurfaceView(view: SurfaceView?) {
        pendingOutputKind = 3
        pendingOutputView = view
        pendingOutputSurface = null
        pendingOutputHolder = null
        manager?.player?.setVideoSurfaceView(view)
    }

    override fun clearDisplay() {
        setSurface(null)
    }

    override fun detachVideoSurface() {
        pendingOutputKind = 0
        pendingOutputSurface = null
        pendingOutputHolder = null
        pendingOutputView = null
        manager?.player?.clearVideoSurface()
    }

    override fun setVolume(leftVolume: Float, rightVolume: Float) {
        val volume = (leftVolume + rightVolume) / 2f
        pendingVolume = volume
        val player = manager?.player ?: return
        setPlayerVolume(player, volume)
    }

    override fun setLooping(isLooping: Boolean) {
        pendingLooping = isLooping
        val player = manager?.player ?: return
        setPlayerLooping(player, isLooping)
    }

    override fun setOptions() {
        manager?.player?.playWhenReady = true
        FloatLog.append("app", null, "setOptions pwr=" + manager?.player?.playWhenReady)
    }

    override fun setSpeed(speed: Float) {
        pendingSpeed = speed
        manager?.setSpeed(speed)
    }

    override val speed: Float
        get() = manager?.speed ?: pendingSpeed ?: 1f

    override val tcpSpeed: Long
        get() = NetworkSpeed.getNetSpeed(appContext)

    fun setDecodeMode(hardware: Boolean) {
        pendingDecodeHard = hardware
        manager?.setDecodeMode(if (hardware) PlayerEngine.HARD else PlayerEngine.SOFT)
    }

    fun setOnCuesListener(listener: OnCuesListener?) {
        cuesListener = listener
    }

    fun setInternalSubtitleDelay(milliseconds: Int) {
        subtitleDelayMs = milliseconds
    }

    fun setAudioOnlyMode(audioOnly: Boolean) {
        if (audioOnly == audioOnlyRequested) return
        audioOnlyRequested = audioOnly
        val player = manager?.player ?: return
        val params = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly).build()
        player.trackSelectionParameters = params
    }

    fun isAudioOnlyMode(): Boolean = audioOnlyRequested

    fun stopForFrameClear() {
        manager?.stop()
    }

    fun setContentKey(key: String?) {
        contentKey = key ?: ""
    }

    fun setUseDiskCache(enabled: Boolean) {
        useDiskCacheFlag = enabled
    }

    fun sampleFrameRate() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (frameWindowStartMs == 0L) {
            frameWindowStartMs = now
            renderedFrameCount = 0
            return
        }
        val elapsed = now - frameWindowStartMs
        if (elapsed < 1000) return
        measuredFrameRateValue = renderedFrameCount * 1000f / elapsed
        frameWindowStartMs = now
        renderedFrameCount = 0
    }

    fun setFrameRateTracking(enabled: Boolean) {
        frameTracking = enabled
        frameWindowStartMs = 0
        renderedFrameCount = 0
        if (enabled) measuredFrameRateValue = 0f
        val player = manager?.player
        if (player is ExoPlayer) {
            if (enabled) {
                player.setVideoFrameMetadataListener(frameListener)
            } else {
                player.clearVideoFrameMetadataListener(frameListener)
            }
        }
    }

    fun measuredFrameRate(): Float = measuredFrameRateValue

    val selectedVideoFormat: Format?
        get() = selectedTrackFormat(C.TRACK_TYPE_VIDEO)

    val selectedAudioFormat: Format?
        get() = selectedTrackFormat(C.TRACK_TYPE_AUDIO)

    private fun selectedTrackFormat(trackType: Int): Format? {
        val player = manager?.player ?: return null
        val tracks = player.currentTracks
        for (group in tracks.groups) {
            if (group.type != trackType) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) return group.getTrackFormat(i)
            }
        }
        return null
    }

    fun videoDecoderName(): String = ""

    fun videoDecodeKind(): PlayerDecodeKind {
        val current = manager ?: return PlayerDecodeKind.UNKNOWN
        return if (current.isHardDecode()) PlayerDecodeKind.HARDWARE else PlayerDecodeKind.SOFTWARE
    }

    fun audioCodecChoice(): AudioCodecChoice? = null

    fun audioRendererName(): String? = null

    fun hwdecCurrent(): String = ""

    fun videoCodecName(): String = ""

    fun audioCodecName(): String = ""

    fun droppedFrames(): Long = droppedFramesCount

    fun rebufferCount(): Int = rebufferCountValue

    val isTunnelingEnabled: Boolean
        get() = !isMpvKernel() && DecodeSetting.isTunnelingEnabled()

    fun mpvErrorMessage(error: androidx.media3.common.PlaybackException): String {
        return mpvErrorMessageProvider.get(error)
    }

    fun isPictureHdrSource(): Boolean {
        val transfer = selectedTrackFormat(C.TRACK_TYPE_VIDEO)?.colorInfo?.colorTransfer ?: return false
        return transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG
    }

    fun isPictureEffectsActive(): Boolean = false

    fun notifyVideoOutputResolution(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        com.github.tvbox.osc.player.effect.PictureEffects.setOutputCanvas(width, height)
    }

    fun redrawVideoFrame() {
    }

    fun applyVideoEffects(effects: List<androidx.media3.common.Effect>) {
    }

}
