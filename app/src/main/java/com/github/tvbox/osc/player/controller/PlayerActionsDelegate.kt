package com.github.tvbox.osc.player.controller
import com.github.tvbox.osc.util.FloatLog

import com.github.tvbox.osc.player.FongmiKernelPlayer
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.ParseBean
import com.github.tvbox.osc.player.AppPlayerView
import com.github.tvbox.osc.player.PlayerCodecStats
import com.github.tvbox.osc.player.PlayerHelper
import com.github.tvbox.osc.player.imagesearch.ImageSearchApi
import com.github.tvbox.osc.player.imagesearch.ImageSearchPlay
import com.github.tvbox.osc.player.state.ImageSearchSheetState
import com.github.tvbox.osc.player.state.LockVisibility
import com.github.tvbox.osc.player.state.PlayerActions
import com.github.tvbox.osc.player.state.SelectDialogState
import com.github.tvbox.osc.ui.page.openVodCardOrDetail
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.PlayerUtils
import org.json.JSONException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SEEK_MAX = 1000

internal class PlayerActionsDelegate(private val host: VideoPlayerController) : PlayerActions {

    private val idleHideMillis = 10000L

    private var keySeekProgress = 0

    private val keySeekCommitRunnable by lazy { Runnable { commitKeySeek() } }

    fun cancelKeySeekCommit() {
        host.uiHandler.removeCallbacks(keySeekCommitRunnable)
    }

    override fun toggleControls() {
        if (!host.state.controlsVisible) showBottom() else hideBottom()
    }

    private fun showBottom() {
        applyShowBottom()
    }

    fun applyShowBottom() {
        host.updateDanmuSearchBtnState()
        host.state.controlsVisible = true
        host.state.topLeftVisible = true
        host.state.topRightVisible = true
        host.state.netSpeedTopRightVisible = true
        host.state.sysTimeVisible = true
        host.state.backVisible = !host.state.isPortrait
        host.showLockView()
        keepControlsAlive()
    }

    fun hideBottom() {
        host.uiHandler.removeCallbacks(host.idleHideRunnable)
        if (host.state.dragging) onSeekCancelled()
        host.state.controlsVisible = false
        host.state.topLeftVisible = false
        host.state.topRightVisible = false
        host.state.netSpeedTopRightVisible = false
        host.state.sysTimeVisible = false
        host.state.backVisible = false
        host.uiHandler.removeCallbacks(host.lockHideRunnable)
        if (host.state.lockState != LockVisibility.GONE) {
            host.state.lockState = LockVisibility.HIDDEN
        }
    }

    override fun keepControlsAlive() {
        if (host.state.controlsVisible) {
            host.uiHandler.removeCallbacks(host.idleHideRunnable)
            host.uiHandler.postDelayed(host.idleHideRunnable, idleHideMillis)
        }
    }

    override fun onNextClicked() {
        FloatLog.append("app", null, "tap next")
        host.listener?.playNext(false)
        keepControlsAlive()
    }

    override fun onPreClicked() {
        FloatLog.append("app", null, "tap pre")
        host.listener?.playPre()
        keepControlsAlive()
    }

    override fun onPlayPauseClicked() {
        if (host.state.tipVisible && !host.isInPlaybackState()) return
        val wasPlaying = host.videoView?.isPlaying == true
        FloatLog.append("app", null, "tap playPause wasPlaying=$wasPlaying action=" + if (wasPlaying) "pause" else "play")
        host.videoView?.togglePlay()
        keepControlsAlive()
    }

    override fun onScaleClicked() {
        FloatLog.append("app", null, "tap scale")
        keepControlsAlive()
        host.config.showScaleDialog()
    }

    override fun onScaleLongClicked() {
        keepControlsAlive()
        host.config.applyScale(0)
    }

    override fun onSpeedClicked() {
        FloatLog.append("app", null, "tap speed")
        keepControlsAlive()
        host.config.showSpeedDialog()
    }

    override fun onSpeedLongClicked() {
        keepControlsAlive()
        host.config.applySpeed(1.0f)
    }

    override fun onPlayerClicked() {
        FloatLog.append("app", null, "tap player")
        keepControlsAlive()
        if (host.state.selectDialog != null) return
        try {
            val cfg = host.playerConfig ?: return
            val playerType = cfg.optInt("pl", 2)
            var defaultPos = 0
            val players = PlayerHelper.getExistPlayerTypes()
            if (players.isEmpty()) return
            val names = ArrayList<String>()
            for (p in players.indices) {
                names.add(PlayerHelper.getPlayerName(players[p]))
                if (players[p] == playerType) {
                    defaultPos = p
                }
            }
            host.state.selectDialog = SelectDialogState(
                tip = host.context.getString(R.string.player_select_player),
                items = names,
                defaultIndex = defaultPos,
                onSelected = { pos ->
                    if (players[pos] != playerType) {
                        host.config.applyPlayer(players[pos])
                        hideBottom()
                    }
                },
            )
        } catch (e: JSONException) {
            LOG.e("VideoPlayerController", e)
        }
    }

    override fun onPlayerLongClicked() {
        keepControlsAlive()
        try {
            val cfg = host.playerConfig ?: return
            val playerType = cfg.getInt("pl")
            var defaultPos = 0
            val players = PlayerHelper.getExistPlayerTypes()
            val names = ArrayList<String>()
            for (p in players.indices) {
                names.add(PlayerHelper.getPlayerName(players[p]))
                if (players[p] == playerType) {
                    defaultPos = p
                }
            }
            host.state.selectDialog = SelectDialogState(
                tip = host.context.getString(R.string.player_select_player),
                items = names,
                defaultIndex = defaultPos,
                onSelected = { pos ->
                    if (players[pos] != playerType) {
                        host.config.applyPlayer(players[pos])
                        hideBottom()
                    }
                },
            )
        } catch (e: JSONException) {
            LOG.e("VideoPlayerController", e)
        }
    }

    override fun onTimeStartClicked() {
        FloatLog.append("app", null, "tap timeStart")
        keepControlsAlive()
        val snapshot = host.progressSnapshot() ?: return
        host.config.setTimeStartNow()
        Toast.makeText(host.context, "片头已设为 " + PlayerUtils.stringForTime(snapshot.positionMs), Toast.LENGTH_SHORT).show()
    }

    override fun onTimeStartLongClicked() {
        host.config.setTimeMark("st", 0)
        Toast.makeText(host.context, "片头已清除", Toast.LENGTH_SHORT).show()
    }

    override fun onTimeEndClicked() {
        FloatLog.append("app", null, "tap timeEnd")
        keepControlsAlive()
        val snapshot = host.progressSnapshot() ?: return
        if (snapshot.durationMs <= 0) {
            Toast.makeText(host.context, "无总时长，无法设置片尾", Toast.LENGTH_SHORT).show()
            return
        }
        host.config.setTimeEndNow()
        if (snapshot.positionMs <= 0) {
            // 0 秒处设片尾：et=全长，起播后首次进度推进即触发下一集，仅提示不拦截
            Toast.makeText(host.context, "片尾已设为 " + PlayerUtils.stringForTime(snapshot.durationMs) + "，当前位置为 0，起播后将立即下一集", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(host.context, "片尾已设为 " + PlayerUtils.stringForTime(snapshot.durationMs - snapshot.positionMs), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onTimeEndLongClicked() {
        host.config.setTimeMark("et", 0)
        Toast.makeText(host.context, "片尾已清除", Toast.LENGTH_SHORT).show()
    }

    override fun onTimeResetClicked() {
        keepControlsAlive()
        try {
            val cfg = host.playerConfig ?: return
            cfg.put("st", 0)
            cfg.put("et", 0)
            host.config.updatePlayerCfgState()
            host.listener?.updatePlayerCfg()
        } catch (e: JSONException) {
            LOG.e("VideoPlayerController", e)
        }
    }

    override fun onEpisodeClicked() {
        FloatLog.append("app", null, "tap episode")
        host.listener?.showEpisodes()
        keepControlsAlive()
    }

    override fun onCastClicked() {
        host.listener?.clickCast()
    }

    override fun onPipClicked() {
        host.listener?.clickPip()
    }

    override fun onSubtitleClicked() {
        FloatLog.append("app", null, "tap subtitle")
        host.listener?.selectSubtitle()
        keepControlsAlive()
    }

    override fun onSubtitleLongClicked() {
        val opened = host.listener?.toggleSubtitles() ?: false
        hideBottom()
        Toast.makeText(host.context, host.context.getString(if (opened) R.string.player_subtitle_opened else R.string.player_subtitle_closed), Toast.LENGTH_SHORT).show()
    }

    override fun onAudioTrackClicked() {
        FloatLog.append("app", null, "tap audioTrack")
        host.listener?.selectAudioTrack()
        keepControlsAlive()
    }

    override fun onVideoTrackClicked() {
        FloatLog.append("app", null, "tap videoTrack")
        host.listener?.selectVideoTrack()
        keepControlsAlive()
    }

    override fun onDanmuSettingClicked() {
        host.listener?.showDanmuSetting()
    }

    override fun onDanmuSettingLongClicked() {
        val opened = host.listener?.toggleDanmu() ?: false
        hideBottom()
        Toast.makeText(host.context, host.context.getString(if (opened) R.string.player_danmu_opened else R.string.player_danmu_temp_closed), Toast.LENGTH_SHORT).show()
    }

    override fun onDanmuSearchClicked() {
        host.listener?.searchDanmuUi(false)
        hideBottom()
    }

    override fun onDanmuSearchLongClicked() {
        host.listener?.searchDanmuUi(true)
        hideBottom()
    }

    override fun onParamsClicked() {
        FloatLog.append("app", null, "tap params")
        keepControlsAlive()
        host.state.paramsSheet = host.config.buildParamsSheet()
    }

    override fun onInfoOsdClicked() {
        host.state.infoOsdVisible = !host.state.infoOsdVisible
        val kernel = host.videoView?.mediaPlayer as? FongmiKernelPlayer
        if (host.state.infoOsdVisible) {
            kernel?.setFrameRateTracking(true)
            InfoOsdText.refreshInfoOsd(
                host.context, host.state, host.videoView, host.playerActivity(),
                runCatching { host.videoView?.tcpSpeed ?: 0L }.getOrDefault(0L),
            )
        } else {
            kernel?.setFrameRateTracking(false)
        }
        if (host.state.overlayPanelOpen) keepControlsAlive() else hideBottom()
    }

    override fun onBackClicked() {
        host.isClickBackBtn = host.state.controlsVisible && !host.previewMode
        (host.playerActivity() as? ComponentActivity)?.onBackPressedDispatcher?.onBackPressed()
    }

    override fun onCloseClicked() {
        // Distinct from back: the host page stops playback and closes
        // the player entirely (see VodControlListener.clickClose).
        host.listener?.clickClose()
    }

    override fun onFullscreenClicked() {
        FloatLog.append("app", null, "tap fullscreen")
        host.listener?.clickFullscreen()
        hideBottom()
    }

    override fun onLockClicked() {
        val newLocked = !host.state.locked
        host.state.locked = newLocked
        if (newLocked) hideBottom()
        host.showLockView()
    }

    override fun onRotateClicked() {
        keepControlsAlive()
        val activity = host.playerActivity() ?: return
        val isLand = host.context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        activity.requestedOrientation = if (isLand) {
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    /**
     * 识图搜片：先开结果弹窗（loading 态），再截当前帧上传。
     * TextureView 主线程直接拿图；SurfaceView（默认）主线程拿不到，后台线程
     * 再调一次走 PixelCopy；都拿不到报“截不到当前帧”。压图与上传照抄 py：
     * 长边 1024、JPEG q0.85，POST avscan.cc /search。
     */
    override fun onImageSearchClicked() {
        keepControlsAlive()
        // 3.65：点 🔍 先暂停再搜（用户 2026-11-20 06:06 记：原来点 🔍 是播放暂停然后
        // 搜索，现在不暂停了）。暂停后截帧才定格在要搜的那一帧上；关面板不自动续播，
        // 点播放键再续。
        if (host.videoView?.isPlaying == true) host.videoView?.togglePlay()
        // 横屏点 🔍：先退全屏，再开竖屏那种搜索面板（用户 2026-11-19 14:57 定）。
        // Activity 接了 configChanges，旋转不重建，弹窗会自己从横屏居中切到竖屏贴底。
        if (host.context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            host.listener?.clickFullscreen()
        }
        val state = host.state
        val seconds = state.position / 1000
        val positionText = String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
        state.imageSearchSheet = ImageSearchSheetState(
            title = state.title,
            positionText = positionText,
            currentSourceKey = state.sessionVod?.sourceKey,
            onPlayCode = { code, done ->
                val mainHandler = Handler(Looper.getMainLooper())
                ImageSearchPlay.playFirst(code, state.sessionVod?.sourceKey) { video, sourcesEmpty ->
                    mainHandler.post {
                        if (video != null) {
                            state.imageSearchSheet = null
                            try {
                                host.context.openVodCardOrDetail(video)
                            } catch (e: Throwable) {
                                LOG.e("avscan", e)
                            }
                            done(true)
                        } else {
                            Toast.makeText(host.context, if (sourcesEmpty) "源还没加载出来，退出重进一次" else "都没搜到这个编号", Toast.LENGTH_SHORT).show()
                            done(false)
                        }
                    }
                }
            },
            onSearch = { callback ->
                val direct = try {
                    host.videoView?.doScreenShot()
                } catch (e: Throwable) {
                    LOG.e("avscan", e)
                    null
                }
                if (direct != null) {
                    val jpg = ImageSearchApi.compressFrame(direct)
                    if (jpg != null) ImageSearchApi.search(jpg, callback)
                    else callback.onError("图片太小或读不出来")
                } else {
                    Thread({
                        val shot = try {
                            host.videoView?.doScreenShot()
                        } catch (e: Throwable) {
                            LOG.e("avscan", e)
                            null
                        }
                        if (shot != null) {
                            val jpg = ImageSearchApi.compressFrame(shot)
                            if (jpg != null) ImageSearchApi.search(jpg, callback)
                            else callback.onError("图片太小或读不出来")
                        } else {
                            callback.onError("截不到当前帧")
                        }
                    }, "avscan-capture").start()
                }
            },
        )
    }

    private var lastDecodeToggleMs = 0L

    override fun onDecodeToggleClicked() {
        FloatLog.append("app", null, "tap decode")
        keepControlsAlive()
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastDecodeToggleMs < 300) return
        lastDecodeToggleMs = nowMs
        val cfg = host.playerConfig ?: return
        val displayed = PlayerHelper.decodeDisplayIndex(
            PlayerCodecStats.videoDecodeKind,
            cfg.optString("exo", "硬解码"),
        )
        host.config.applyDecode(if (displayed == 0) "软解码" else "硬解码")
    }

    override fun onRefreshClicked() {
        FloatLog.append("app", null, "tap refresh")
        keepControlsAlive()
        host.listener?.replay(false)
    }

    override fun onReplayClicked() {
        FloatLog.append("app", null, "tap replay")
        keepControlsAlive()
        host.videoView?.seekTo(0L)
        if (!host.state.playbackActive) host.videoView?.togglePlay()
    }

    private var loopEnabled = false

    override fun onLoopToggleClicked() {
        FloatLog.append("app", null, "tap loop")
        keepControlsAlive()
        loopEnabled = !loopEnabled
        host.videoView?.mediaPlayer?.setLooping(loopEnabled)
        Toast.makeText(host.context, if (loopEnabled) "循环已开" else "循环已关", Toast.LENGTH_SHORT).show()
    }

    override fun onOriginalClicked() {
        FloatLog.append("app", null, "tap scale")
        keepControlsAlive()
        host.config.cycleScale()
    }

    override fun onParseSelected(position: Int) {
        FloatLog.append("app", null, "tap parse index=$position")
        val parseBeanList = ApiConfig.get().parseBeanList
        if (position < 0 || position >= parseBeanList.size) return
        val parseBean: ParseBean = parseBeanList[position]
        ApiConfig.get().setDefaultParse(parseBean)
        host.state.parseListVersion++
        host.listener?.changeParse(parseBean)
        hideBottom()
    }

    override fun onSeekStarted() {
        if (!host.state.controlsVisible) applyShowBottom()
        if (host.state.dragging) return
        host.state.dragging = true
        host.stopProgress()
        host.uiHandler.removeCallbacks(host.idleHideRunnable)
        keepControlsAlive()
    }

    override fun onSeekPreview(progress: Int) {
        val snapshot = host.progressSnapshot() ?: return
        host.state.seekPreviewPositionMs = seekBarToPosition(progress, snapshot.durationMs)
    }

    override fun onSeekFinished(progress: Int) {
        keepControlsAlive()
        val view = host.videoView
        val snapshot = host.progressSnapshot()
        var seekTarget = -1
        if (view != null && snapshot != null) {
            seekTarget = seekBarToPosition(progress, snapshot.durationMs).toInt()
            view.seekTo(seekTarget.toLong())
            FloatLog.append("app", null, "tap seekBar to=$seekTarget duration=${snapshot.durationMs}")
            view.saveCurrentProgress()
        }
        if (seekTarget >= 0) host.state.position = seekTarget
        host.state.dragging = false
        keySeekProgress = 0
        host.startProgress()
        keepControlsAlive()
    }

    override fun onSeekCancelled() {
        host.state.dragging = false
        keySeekProgress = 0
        host.startProgress()
        keepControlsAlive()
    }

    override fun onSeekStep(dir: Int) {
        val snapshot = host.progressSnapshot() ?: return
        val duration = snapshot.durationMs
        if (duration <= 0) return
        if (!host.state.controlsVisible) applyShowBottom()
        if (!host.state.dragging) {
            host.state.dragging = true
            host.stopProgress()
            host.uiHandler.removeCallbacks(host.idleHideRunnable)
        }
        keySeekProgress = (keySeekProgress + keySeekIncrement(duration) * dir).coerceIn(0, SEEK_MAX)
        host.state.seekPreviewPositionMs = seekBarToPosition(keySeekProgress, duration)
        host.updateSeekUiHint(
            snapshot.positionMs,
            host.state.seekPreviewPositionMs.toInt(),
        )
        host.uiHandler.removeCallbacks(keySeekCommitRunnable)
        host.uiHandler.postDelayed(keySeekCommitRunnable, 400)
    }

    override fun onSeekRelative(deltaMs: Long) {
        val view = host.videoView ?: return
        val snapshot = host.progressSnapshot() ?: return
        val duration = snapshot.durationMs
        if (duration <= 0) return
        if (!host.state.controlsVisible) applyShowBottom()
        val current = snapshot.positionMs
        val target = (current + deltaMs).coerceIn(0L, duration.toLong())
        host.state.dragging = false
        keySeekProgress = 0
        view.seekTo(target)
        FloatLog.append("app", null, "tap seekRelative delta=$deltaMs from=$current to=$target")
        host.state.position = target.toInt()
        host.state.seekPreviewPositionMs = target
        host.updateSeekUiHint(current, target.toInt())
        view.saveCurrentProgress()
        keepControlsAlive()
    }

    private fun commitKeySeek() {
        if (!host.state.dragging) return
        onSeekFinished(keySeekProgress)
    }

    private fun seekBarToPosition(progress: Int, duration: Int): Long {
        if (duration <= 0) return 0L
        return duration.toLong() * progress / SEEK_MAX
    }

    private fun keySeekIncrement(duration: Int): Int {
        val increment: Long = when {
            duration > 3 * 60 * 60 * 1000 -> 5 * 60 * 1000L
            duration > 30 * 60 * 1000 -> 60 * 1000L
            duration > 15 * 60 * 1000 -> 30 * 1000L
            duration > 10 * 60 * 1000 -> 15 * 1000L
            else -> 10 * 1000L
        }
        return maxOf(1, (increment * SEEK_MAX / duration).toInt())
    }

    override fun refreshSystemInfo() {
        val view = host.videoView ?: return
        host.state.sysTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        InfoOsdText.readBattery(host.context, host.state)
        val speed = runCatching { view.tcpSpeed }.getOrDefault(0L)
        host.state.netSpeedTopRight = PlayerHelper.getDisplaySpeed(speed, true)
        host.state.netSpeedCenter = PlayerHelper.getDisplaySpeed(speed, false)
        val size = runCatching { view.videoSize }.getOrDefault(intArrayOf(0, 0))
        host.state.videoSize = host.videoSizeGate.textFor(size[0], size[1])
        host.state.videoQuality = host.videoSizeGate.qualityFor(size[0], size[1])
        if (host.state.infoOsdVisible) InfoOsdText.refreshInfoOsd(host.context, host.state, host.videoView, host.playerActivity(), speed)
    }

    override fun hideSeekHint() {
        host.state.seekHintVisible = false
    }

    override fun hideSlideHint() {
        host.state.slideHintVisible = false
    }
}
