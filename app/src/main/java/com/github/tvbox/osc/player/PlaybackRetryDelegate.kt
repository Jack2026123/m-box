package com.github.tvbox.osc.player

import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.widget.Toast
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.loader.BrowserPlaySpider
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.data.EpisodeMatcher
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import org.json.JSONObject
import java.util.HashMap

class PlaybackRetryDelegate(private val host: Host) {

    interface Host {
        fun attemptState(): PlaybackAttemptState

        fun view(): PlaybackViewBridge?

        fun playerCfg(): JSONObject?

        fun vod(): VodInfo?

        fun currentSeries(flag: String?, index: Int): VodInfo.VodSeries?

        fun progressKey(): String?

        suspend fun getSavedProgress(url: String): Long

        fun inheritProgressFrom(key: String?, position: Long)

        fun webPlayUrl(): String?

        fun webHeaderMap(): HashMap<String, String>?

        fun resolverHasFoundUrls(): Boolean

        fun resolverConsumeFoundUrl()

        fun play(reset: Boolean)

        fun playUrl(url: String, headers: HashMap<String, String>?)

        fun stopParse()

        fun initParseLoadFound()

        fun cancelPlayRequest()

        fun cancelPlayTimeout()

        fun isPlaybackStarted(): Boolean

        fun isCrossContentReuseAllowed(): Boolean

        fun stopMusicSessionForFailedPlayback()

        fun closeCastPrepare()
    }

    private fun restoreAutoSwitchedPlayer() {
        val st = host.attemptState()
        if (st.autoSwitchedPlayerType < 0) return
        st.setReleaseIntent(true)
        try {
            val cfg = requireNotNull(host.playerCfg())
            LOG.i("echo-autoRetry restore player: " + cfg.optInt("pl", -1) + " -> " + st.autoSwitchedPlayerType)
            cfg.put("pl", st.autoSwitchedPlayerType)
            host.view()?.applyPlayerConfig(cfg)
        } catch (th: Throwable) {
            LOG.e("PlaybackController", th)
        } finally {
            st.autoSwitchedPlayerType = -1
        }
    }

    private fun restoreAutoSwitchedDecode() {
        val st = host.attemptState()
        if (st.autoSwitchedDecodeOld == null) return
        st.hasAutoSwitchedDecode = false
        try {
            val cfg = host.playerCfg()
            if (cfg != null) {
                LOG.i("echo-autoRetry restore decode: " + cfg.optString(st.autoSwitchedDecodeKey, "") + " -> " + st.autoSwitchedDecodeOld)
                cfg.put(st.autoSwitchedDecodeKey, st.autoSwitchedDecodeOld)
                host.view()?.applyPlayerConfig(cfg)
            }
        } catch (th: Throwable) {
            LOG.e("PlaybackController", th)
        } finally {
            st.autoSwitchedDecodeOld = null
        }
    }





    private fun dropRetryIfCastAborted(reason: String): Boolean {
        val st = host.attemptState()
        if (!st.castAborted) return false
        LOG.i("echo-cast abort: drop retry ($reason)")
        host.cancelPlayRequest()
        host.stopParse()
        host.cancelPlayTimeout()
        return true
    }

    fun retryAfterStartedError(): Boolean {
        if (dropRetryIfCastAborted("retryAfterStartedError")) return false
        val st = host.attemptState()
        if (st.hasRetriedAfterStart) return false
        val webUrl = host.webPlayUrl() ?: return false
        if (TextUtils.isEmpty(webUrl)) return false
        st.hasRetriedAfterStart = true
        st.hasRetriedSameUrlOnBoot = true
        LOG.i("echo-autoRetry retry after started error: " + host.webPlayUrl())
        val view = host.view()
        if (view != null && view.isPageAlive()) {
            view.runOnUi { view.toast(PlaybackController.str(R.string.player_play_error_retry)) }
        }
        host.stopParse()
        host.initParseLoadFound()
        st.playbackStarted = false
        if (view != null && !host.isCrossContentReuseAllowed()) view.releasePlayer()
        if (view != null) host.playUrl(webUrl, host.webHeaderMap())
        return true
    }

    private fun retryWithFreshResolve(reason: String): Boolean {
        val st = host.attemptState()
        if (!st.usedPreloadedResult) return false
        st.usedPreloadedResult = false
        LOG.i("echo-preload-stale: re-resolve current episode ($reason)")
        st.playbackStarted = false
        host.stopParse()
        host.initParseLoadFound()
        if (!host.isCrossContentReuseAllowed()) host.view()?.releasePlayer()
        host.play(false)
        return true
    }



    private fun tryAutoSoftDecode(): Boolean {
        val st = host.attemptState()
        if (st.hasTriedAutoSoftDecode) return false
        val cfg = host.playerCfg() ?: return false
        if (cfg.optString("exo", "硬解码") == "软解码") return false
        val kernel = host.view()?.mediaPlayer() as? FongmiKernelPlayer ?: return false
        if (kernel.videoDecodeKind() == PlayerDecodeKind.SOFTWARE) return false
        if (FongmiKernelPlayer.isPreferSoftwareDecode()) return false
        val webUrl = host.webPlayUrl() ?: return false
        if (TextUtils.isEmpty(webUrl)) return false
        try {
            st.hasTriedAutoSoftDecode = true
            st.hasRetriedSameUrlOnBoot = true
            LOG.i("echo-manager auto soft decode: hard -> soft replay same url")
            cfg.put("exo", "软解码")
            cfg.put("exoSet", 1)
            FongmiKernelPlayer.setPreferSoftwareDecode(true)
            VesperEnginePolicy.mpvDecodeOverride = false
            host.view()?.applyPlayerConfig(cfg)
            host.stopParse()
            host.initParseLoadFound()
            val view = host.view()
            if (view != null && !host.isCrossContentReuseAllowed()) view.releasePlayer()
            if (view != null) host.playUrl(webUrl, host.webHeaderMap())
            return true
        } catch (th: Throwable) {
            LOG.e("PlaybackController", th)
            return false
        }
    }

    suspend fun autoRetry(): Boolean {
        if (dropRetryIfCastAborted("autoRetry")) return false
        val st = host.attemptState()
        val kernelClass = host.view()?.mediaPlayer()?.javaClass?.simpleName ?: "null"
        LOG.i("echo-manager autoRetry: kernel=" + kernelClass)
        if (retryWithFreshResolve("autoRetry")) return true
        val currentTime = System.currentTimeMillis()
        if (currentTime - st.lastRetryTime > 60_000) {
            LOG.i("echo-reset-autoRetryCount")
            st.resetAutoRetryLadder()
        }
        st.lastRetryTime = currentTime
        if (host.resolverHasFoundUrls()) {
            host.resolverConsumeFoundUrl()
            return true
        }
        if (tryAutoSoftDecode()) return true
        if (tryNextBrowserPlayEpisode()) return true
        val webUrl = host.webPlayUrl()
        if (!st.hasRetriedSameUrlOnBoot && !TextUtils.isEmpty(webUrl)) {
            st.hasRetriedSameUrlOnBoot = true
            LOG.i("echo-autoRetry replay same url before player switch: " + webUrl)
            host.stopParse()
            host.initParseLoadFound()
            val view = host.view()
            if (view != null && !host.isCrossContentReuseAllowed()) view.releasePlayer()
            if (view != null) host.playUrl(requireNotNull(webUrl), host.webHeaderMap())
            return true
        }
        if (webUrl != null) {
            if (st.allowSwitchPlayer && !st.hasAutoSwitchedPlayer) {
                LOG.i("echo-autoRetry switch player and replay current url")
                val playerType = requireNotNull(host.playerCfg()).optInt("pl", -1)
                val view = host.view()
                val switchSkipped = view != null && view.switchPlayerKernel()
                st.hasAutoSwitchedPlayer = true
                st.allowSwitchPlayer = false
                if (!switchSkipped) {
                    st.autoSwitchedPlayerType = playerType
                    st.vesperDecodeRetried = false
                    host.stopParse()
                    host.initParseLoadFound()
                    if (view != null && !host.isCrossContentReuseAllowed()) view.releasePlayer()
                    if (view != null) host.playUrl(webUrl, host.webHeaderMap())
                    return true
                }
            }
            LOG.i("echo-autoRetry decode and player switch exhausted, terminal error")
            return false
        }
        return false
    }

    /**
     * r488：browser_play 只有一条线路，播错时“换线路”是空的。仿 muse 播错换线路：
     * 单线路时改走下一集（集列表里下一条 URL），播完所有集才继续往下走阶梯。
     */
    private fun tryNextBrowserPlayEpisode(): Boolean {
        val vod = host.vod() ?: return false
        if (vod.sourceKey != BrowserPlaySpider.KEY) return false
        val series = vod.seriesMap?.get(vod.playFlag) ?: return false
        val nextIndex = vod.playIndex + 1
        if (nextIndex >= series.size) return false
        LOG.i("echo-autoRetry browser_play try next episode: " + vod.playIndex + " -> " + nextIndex)
        val view = host.view()
        if (view != null && view.isPageAlive()) {
            view.runOnUi { view.toast(PlaybackController.str(R.string.player_try_next_episode)) }
        }
        vod.playIndex = nextIndex
        host.play(false)
        return true
    }

    suspend fun tryNextLineIfEnabled(): Boolean {
        restoreAutoSwitchedPlayer()
        restoreAutoSwitchedDecode()
        if (host.attemptState().allowAutoSwitchLine && (KV.get(HawkConfig.AUTO_SWITCH_LINE, HawkConfig.AUTO_SWITCH_LINE_DEFAULT) as Boolean)) {
            return tryNextLine()
        }
        LOG.i("echo-autoRetry line switching disabled")
        host.attemptState().resetAutoRetryLadder()
        return false
    }

    suspend fun tryNextLine(): Boolean {
        val st = host.attemptState()
        val vod = host.vod()
        val seriesMap = vod?.seriesMap
        if (vod == null || seriesMap == null || seriesMap.isEmpty()) {
            st.linesExhausted()
            return false
        }
        val currentFlag = vod.playFlag
        val currentIndex = Math.max(vod.playIndex, 0)
        val currentSeries = host.currentSeries(currentFlag, currentIndex)
        if (!currentFlag.isNullOrEmpty()) {
            st.triedLineFlags.add(currentFlag)
        }
        val lineFlags = EpisodeMatcher.lineFlagsInDisplayOrder(vod)
        val currentLineIndex = EpisodeMatcher.lineFlagIndex(lineFlags, currentFlag)
        val startLineIndex = if (currentLineIndex >= 0) currentLineIndex + 1 else 0
        var nextFlag: String? = null
        var nextIndex = 0
        for (i in startLineIndex until lineFlags.size) {
            val flag = lineFlags[i]
            val seriesList = seriesMap[flag]
            if (!st.triedLineFlags.contains(flag) && seriesList != null && seriesList.isNotEmpty()) {
                nextFlag = flag
                nextIndex = EpisodeMatcher.sameEpisodeIndex(currentSeries, seriesList, currentIndex)
                break
            }
        }
        val view = host.view()
        if (nextFlag == null) {
            LOG.i("echo-autoRetry all lines exhausted")
            st.linesExhausted()
            return view != null && view.onLinesExhausted()
        }
        val flagToSwitch = nextFlag
        val preProgressKey = host.progressKey()
        val savedProgress = if (preProgressKey.isNullOrEmpty()) 0L else host.getSavedProgress(preProgressKey)
        val preProgress = Math.max(savedProgress, if (view == null) 0L else view.currentPosition())
        LOG.i("echo-autoRetry switch line: " + vod.playFlag + " -> " + flagToSwitch)
        if (view != null && view.isPageAlive()) {
            view.runOnUi { view.toast(PlaybackController.str(R.string.player_switch_line, flagToSwitch)) }
        }
        vod.playFlag = flagToSwitch
        vod.playIndex = nextIndex
        st.onLineSwitched()
        host.inheritProgressFrom(preProgressKey, preProgress)
        host.play(false)
        return true
    }

    private fun abortIfCastPrepare(reason: String): Boolean {
        val st = host.attemptState()
        if (!st.castPrepareOnly) return false
        LOG.i("echo-cast prepare aborted: $reason")
        host.cancelPlayRequest()
        host.stopParse()
        host.cancelPlayTimeout()
        host.closeCastPrepare()
        return true
    }

    suspend fun handleResolvePlayUrlTimeout() {
        if (dropRetryIfCastAborted("resolveTimeout")) {
            host.stopMusicSessionForFailedPlayback()
            showErrorTip(PlaybackController.str(R.string.player_get_url_timeout))
            return
        }
        val st = host.attemptState()
        if (abortIfCastPrepare("resolveTimeout")) return
        if (retryWithFreshResolve("resolveTimeout")) return
        LOG.i("echo-resolvePlayUrl timeout, try next line")
        host.cancelPlayRequest()
        host.stopParse()
        if (st.userPickedLine) {
            st.userPickedLine = false
            host.stopMusicSessionForFailedPlayback()
            showTerminalError(PlaybackController.str(R.string.player_get_url_timeout))
            return
        }
        if (!tryNextLineIfEnabled()) {
            host.stopMusicSessionForFailedPlayback()
            showTerminalError(PlaybackController.str(R.string.player_get_url_timeout))
        }
    }

    suspend fun handleResolvePlayUrlFailed(err: String) {
        if (dropRetryIfCastAborted("resolveFailed")) {
            host.stopMusicSessionForFailedPlayback()
            showErrorTip(err)
            return
        }
        val st = host.attemptState()
        LOG.i("echo-resolvePlayUrl failed, try next line: $err")
        if (abortIfCastPrepare(err)) return
        host.cancelPlayRequest()
        host.stopParse()
        if (st.userPickedLine) {
            st.userPickedLine = false
            host.cancelPlayTimeout()
            host.stopMusicSessionForFailedPlayback()
            showTerminalError(err)
            return
        }
        if (tryNextLineIfEnabled()) return
        host.cancelPlayTimeout()
        host.stopMusicSessionForFailedPlayback()
        showTerminalError(err)
    }

    suspend fun handleSwitchLinePlayTimeout() {
        if (dropRetryIfCastAborted("switchLineTimeout")) {
            host.stopMusicSessionForFailedPlayback()
            showErrorTip(PlaybackController.str(R.string.player_play_timeout))
            return
        }
        val st = host.attemptState()
        val view = host.view()
        val state = view?.playState()
        LOG.i("echo-switchLinePlay timeout state: $state, started: " + st.playbackStarted)
        if (abortIfCastPrepare("switchLineTimeout")) return
        if (host.isPlaybackStarted()) {
            host.cancelPlayTimeout()
            view?.hideTipOnUiThread()
            return
        }
        LOG.i("echo-switchLinePlay timeout, terminal error (no line switch)")
        host.stopParse()
        host.stopMusicSessionForFailedPlayback()
        host.cancelPlayTimeout()
        showTerminalError(PlaybackController.str(R.string.player_play_timeout))
    }

    private fun showErrorTip(err: String) {
        host.view()?.showTip(err, false, true)
    }

    /**
     * 终局失败：播放入口提示随播放页弹出一起死，用户看不见；再补一个系统 Toast，
     * 退回搜索页后照样看得到。投屏准备中止那几条路仍只走 showErrorTip，不弹。
     */
    private fun showTerminalError(err: String) {
        showErrorTip(err)
        val ctx = host.view()?.context() ?: return
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, err, Toast.LENGTH_LONG).show()
        }
    }
}
