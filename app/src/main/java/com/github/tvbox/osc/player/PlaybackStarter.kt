package com.github.tvbox.osc.player

import android.text.TextUtils
import com.github.catvod.crawler.DebugLogStore
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.data.AppGraph
import com.github.tvbox.osc.data.PlaybackProgress
import com.github.tvbox.osc.data.WatchProgressStore
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.player.state.PlayState
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.sourcedata.SourceViewModel
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.ImgUtil
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.FloatLog
import com.github.tvbox.osc.util.MD5
import com.github.tvbox.osc.util.thunder.Jianpian
import com.github.tvbox.osc.util.thunder.Thunder
import org.greenrobot.eventbus.EventBus
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder
import java.util.HashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PlaybackStarter(private val host: Host) {

    interface Host {
        fun attemptState(): PlaybackAttemptState

        fun view(): PlaybackViewBridge?

        fun vod(): VodInfo?

        fun playerCfg(): JSONObject?

        fun sourceKey(): String

        fun currentSeries(flag: String?, index: Int): VodInfo.VodSeries?

        fun playSubtitle(): String?

        fun progressKey(): String?

        fun progressOwner(): String?

        fun startedProgressKey(): String?

        fun isSameContentRestart(): Boolean

        fun subtitleCacheKey(): String?

        fun setProgressKey(key: String?)

        fun setSubtitleCacheKey(key: String?)

        suspend fun getSavedProgress(url: String?): Long

        fun inheritProgressFrom(key: String?, position: Long)

        fun inheritProgressIfNeeded()

        fun migrateProgressOnLineSwitch()

        fun clearInheritProgress()

        fun handleResolvePlayUrlFailed(err: String)

        fun publishTitle()

        fun beginNewPlay()

        fun consumeReusePlayerOnSwitch(): Boolean

        fun stopParse()

        fun initParseLoadFound()

        fun setWebPlayUrl(url: String?)

        fun setWebHeaderMap(headers: HashMap<String, String>?)

        fun startResolvePlayUrlTimeout()

        fun startSwitchLinePlayTimeout()

        fun cancelPlayTimeout()

        fun closeCastPrepare()

        fun setPlayTimeoutBasePosition(position: Long)

        fun invalidatePreload()

        fun syncDecodeFromGlobal()

        fun preloadConsumeResult(key: String?): Boolean

        fun sourceViewModel(): SourceViewModel?

        fun resolverNextGen()

        fun resolverCurrentGen(): Int

        fun resolverIsParseResultCurrent(gen: Int): Boolean

        fun resolverCancelParseTimeout()
    }

    private var playUrlGeneration: Int = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun play(reset: Boolean) {
        val st = host.attemptState()
        val prepareOnly = st.castPrepareOnly
        if (prepareOnly) LOG.i("echo-cast prepare: resolve only, no playback side effects")
        st.switchStopPending = false
        host.resolverNextGen()
        host.invalidatePreload()
        host.view()?.hidePreloadReadyTip()
        val vod = host.vod() ?: return
        val kernelPresent = host.view()?.mediaPlayer() != null
        val reusePlayer = if (prepareOnly) {
            false
        } else {
            val idleKernelReused = isIdleKernelReusable(kernelPresent)
            val crossContentReuseAllowed = isCrossContentReuseAllowed()
            val reuseAllowed = host.consumeReusePlayerOnSwitch() || idleKernelReused || crossContentReuseAllowed
            KernelReusePolicy.decide(kernelPresent, false, false, reuseAllowed) == KernelDecision.REUSE
        }
        if (!prepareOnly) {
            st.switchingPlayback = true
            st.audioPlayback = false
            val sameContentRestart = !reset && host.isSameContentRestart()
            host.view()?.onNewPlayStarted(sameContentRestart)
            host.view()?.clearArtwork()
        }
        val vs = host.currentSeries(vod.playFlag, vod.playIndex)
        if (vs == null) {
            host.handleResolvePlayUrlFailed(PlaybackController.str(R.string.player_get_info_error))
            return
        }
        if (!prepareOnly) {
            val startedKey = host.startedProgressKey()
            val sameContentReuse = startedKey != null
                && !KernelReusePolicy.isCrossContentSwitch(startedKey, host.progressKey())
            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_REFRESH, host.vod()))
            if (sameContentReuse) {
                host.view()?.showTip("", true, false)
            } else {
                host.view()?.showTip("", true, false)
            }
            host.publishTitle()
        }

        host.stopParse()
        host.beginNewPlay()
        host.syncDecodeFromGlobal()
        host.setWebPlayUrl(null)
        host.setWebHeaderMap(null)
        host.initParseLoadFound()

        if (!prepareOnly) {
            host.view()?.stopOtherPlayers()
            host.view()?.resetDanmu()
            host.view()?.clearLyric()
            if (reusePlayer) {
                savePreviousContentProgress()
                host.view()?.clearVideoFrame()
            } else if (kernelPresent) {
                host.view()?.releasePlayer()
            }
            ImgUtil.clearMemoryCache()
        }
        host.setSubtitleCacheKey(
            vod.sourceKey + "-" + vod.id + "-" + vod.playFlag + "-"
                + vod.playIndex + "-" + vs.name + "-subt"
        )
        host.setProgressKey(vod.sourceKey + vod.id + vod.playFlag + vod.playIndex + vs.name)
        if (!prepareOnly) {
            PlaybackProgress.onEpisodeStart(host.vod())
        }
        host.startResolvePlayUrlTimeout()
        if (st.pendingInheritProgress > 0 && !TextUtils.isEmpty(st.pendingInheritKey)) {
            if (!prepareOnly) host.inheritProgressFrom(st.pendingInheritKey, st.pendingInheritProgress)
            LOG.i("echo-switchSource inherit progress " + st.pendingInheritProgress + "ms from " + st.pendingInheritKey)
        }
        st.pendingInheritKey = null
        st.pendingInheritProgress = 0
        if (reset && !prepareOnly) {
            host.clearInheritProgress()
            WatchProgressStore.clear(host.progressOwner(), host.progressKey())
            val subtitleCacheKey = MD5.string2MD5(host.subtitleCacheKey())
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                AppGraph.cacheRepository.delete(subtitleCacheKey, 0)
            }
        } else if (!prepareOnly) {
            host.migrateProgressOnLineSwitch()
            host.inheritProgressIfNeeded()
            host.view()?.setSubtitleViewVisible(false)
        }

        val seriesUrl = requireNotNull(vs.url)
        // 音乐模式：series.url 是占位符 music://...，这里同步换成真实播放地址。
        // 走这条分支就不进后面的 Jianpian/Thunder/svm.getPlay 解析链，
        // 因为音乐地址由洛雪脚本直接给出，不需要任何解析器。
        if (com.github.tvbox.osc.music.MusicSession.isPlaceholder(seriesUrl)) {
            val song = com.github.tvbox.osc.music.MusicSession.songFromPlaceholder(seriesUrl)
            if (song == null) {
                host.handleResolvePlayUrlFailed("音乐队列已失效，请重新搜索")
                return
            }
            com.github.tvbox.osc.music.MusicSession.syncIndexFrom(vod)
            val musicResolveStart = System.currentTimeMillis()
            LOG.i("echo-music resolve [" + song.title + "] platform=" + song.platform)
            DebugLogStore.add("music", "开始取地址 [" + song.title + "] 平台=" + song.platform)
            host.view()?.showParse(false)
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
            ).launch {
                val media = runCatching {
                    com.github.tvbox.osc.music.MusicRepository.media(song)
                }.getOrNull()
                val resolveMs = System.currentTimeMillis() - musicResolveStart
                if (media == null) {
                    val reason = com.github.tvbox.osc.music.MusicRepository.lastError.value
                        .ifBlank { "取不到播放地址" }
                    LOG.i("echo-music resolve failed [" + song.title + "] " + reason)
                    DebugLogStore.add("music", "取地址失败 [" + song.title + "] 用时=" + resolveMs + "ms 原因=" + reason)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        host.handleResolvePlayUrlFailed(reason)
                    }
                    return@launch
                }
                LOG.i(
                    "echo-music resolve ok [" + song.title + "] q=" + media.quality +
                        " url=" + media.url.take(80),
                )
                DebugLogStore.add("music", "取地址成功 [" + song.title + "] 用时=" + resolveMs + "ms 画质=" + media.quality + " 已交给播放器")
                com.github.tvbox.osc.music.MusicSession.noteResolvedQuality(media.quality)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    playUrl(
                        media.url,
                        if (media.headers.isEmpty()) null else HashMap(media.headers),
                    )
                }
            }
            return
        }
        if (Jianpian.isJpUrl(seriesUrl)) {
            host.view()?.showParse(false)
            if (seriesUrl.startsWith("tvbox-xg:")) {
                playUrl(requireNotNull(Jianpian.JPUrlDec(seriesUrl.substring(9))), null)
            } else {
                playUrl(requireNotNull(Jianpian.JPUrlDec(seriesUrl)), null)
            }
            return
        }
        val thunderGen = host.resolverCurrentGen()
        if (Thunder.play(seriesUrl, object : Thunder.ThunderCallback {
                override fun status(code: Int, info: String) {
                    host.view()?.showTip(info, code >= 0, code < 0)
                }

                override fun list(urlMap: MutableMap<Int, String>) {
                }

                override fun play(url: String) {
                    playUrl(thunderGen, url, null)
                }
            })
        ) {
            host.view()?.showParse(false)
            return
        }

        if (host.preloadConsumeResult(host.progressKey())) return
        val svm = host.sourceViewModel()
        if (svm != null) {
            svm.getPlay(host.sourceKey(), vod.playFlag, host.progressKey(), vs.url, host.subtitleCacheKey())
        }
    }

    private fun isIdleKernelReusable(kernelPresent: Boolean): Boolean {
        if (!kernelPresent) return false
        val bridge = host.view() ?: return false
        return bridge.playState() == PlayState.IDLE
    }

    fun isCrossContentReuseAllowed(): Boolean {
        val bridge = host.view() ?: return false
        if (bridge.mediaPlayer() == null) return false
        return !bridge.isKernelErrored()
    }

    private fun savePreviousContentProgress() {
        val bridge = host.view() ?: return
        if (TextUtils.isEmpty(host.progressKey())) return
        val position = bridge.currentPosition()
        if (position <= 0) return
        WatchProgressStore.save(host.progressOwner(), host.progressKey(), position, bridge.duration())
    }

    fun playUrl(gen: Int, url: String, headers: HashMap<String, String>?) {
        if (!host.resolverIsParseResultCurrent(gen)) {
            LOG.i("echo-ignore stale parse result")
            return
        }
        playUrlGeneration = gen
        playUrl(url, headers)
    }

    fun playUrl(url: String, headers: HashMap<String, String>?) {
        host.syncDecodeFromGlobal()
        if (TextUtils.isEmpty(url)) {
            host.cancelPlayTimeout()
            host.handleResolvePlayUrlFailed(PlaybackController.str(R.string.player_play_url_empty))
            return
        }
        host.startSwitchLinePlayTimeout()
        val target = attachProxySiteKey(url)
        if (!target.startsWith("data:application")) {
            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_REFRESH, target))
        }
        if (!KV.get(HawkConfig.M3U8_PURIFY, false)) {
            goPlayUrl(target, headers)
            return
        }
        if (target.startsWith("http://127.0.0.1") || !target.contains(".m3u8")) {
            goPlayUrl(target, headers)
            return
        }
        val vod = host.vod()
        if (vod != null && DefaultConfig.noAd(vod.playFlag)) {
            goPlayUrl(target, headers)
            return
        }
        LOG.i("echo-playM3u8:" + target)
        host.view()?.playM3u8(target, headers, playUrlGeneration)
        host.setWebPlayUrl(target)
    }

    fun goPlayUrl(url: String, headers: HashMap<String, String>?) {
        val st = host.attemptState()
        if (st.castAborted) {
            LOG.i("echo-cast abort: drop late play url")
            host.cancelPlayTimeout()
            return
        }
        LOG.i("echo-goPlayUrl:" + url)
        FloatLog.append("app", null, "goPlayUrl urlEmpty=" + TextUtils.isEmpty(url))
        if (TextUtils.isEmpty(url)) {
            host.handleResolvePlayUrlFailed(PlaybackController.str(R.string.player_play_url_empty))
            return
        }
        val bridge = host.view()
        if (bridge == null || !bridge.isPageAlive()) {
            LOG.e("echo-manager goPlayUrl drop: bridge null or page dead, url=" + url)
            return
        }
        playUrlGeneration = host.resolverCurrentGen()
        val finalUrl = url
        scope.launch {
            val live = host.view()
            if (live == null || !live.isPageAlive()) {
                LOG.e("echo-manager goPlayUrl drop: bridge dead in launch, url=" + finalUrl)
                return@launch
            }
            if (st.switchStopPending) {
                LOG.i("echo-ignore goPlayUrl while source switching")
                return@launch
            }
            if (st.castAborted) {
                LOG.i("echo-cast abort: drop late play url")
                host.cancelPlayTimeout()
                return@launch
            }
            if (playUrlGeneration != host.resolverCurrentGen()) {
                LOG.i("echo-ignore goPlayUrl of stale parse result")
                host.resolverCancelParseTimeout()
                return@launch
            }
            host.setWebPlayUrl(finalUrl)
            host.stopParse()
            if (st.castPrepareOnly) {
                host.closeCastPrepare()
                host.cancelPlayTimeout()
                LOG.i("echo-cast prepare: url resolved, keep playback off")
                return@launch
            }
            if (host.view() == null) {
                LOG.e("echo-manager goPlayUrl drop: view null after parse, url=" + finalUrl)
                return@launch
            }
            var targetUrl = finalUrl
            try {
                val playerType = host.playerCfg()?.optInt("pl", 2) ?: 2
                if (playerType >= 10) {
                    LOG.i("echo-manager external player removed: type=" + playerType + " -> local EXO, url=" + targetUrl)
                    if (host.view()?.isPageAlive() == true) {
                        host.view()?.showTip(PlaybackController.str(R.string.player_external_removed_local_exo), false, false)
                    }
                }
            } catch (e: JSONException) {
                LOG.e("PlaybackController", e)
            }
            host.setPlayTimeoutBasePosition(host.getSavedProgress(host.progressKey()))
            val forceExoPlayer = targetUrl.startsWith("data:application/dash+xml;base64,")
                || targetUrl.contains(".mpd") || targetUrl.contains("type=mpd")
            if (targetUrl.startsWith("data:application/dash+xml;base64,")) {
                host.view()?.applyPlayerConfigToView(2)
                requireNotNull(App.getInstance()) { "app instance not ready" }.setDashData(targetUrl.split("base64,")[1])
                targetUrl = ControlManager.get().getAddress(true) + "dash/proxy.mpd"
            } else if (targetUrl.contains(".mpd") || targetUrl.contains("type=mpd")) {
                host.view()?.applyPlayerConfigToView(2)
            } else {
                host.view()?.applyPlayerConfigToView(0)
            }
            if (PlaybackController.looksLikeAudioUrl(targetUrl)) {
                host.view()?.useTextureRenderForAudio()
            }
            host.view()?.startVideoPlayback(targetUrl, headers, forceExoPlayer)
        }
    }

    private fun attachProxySiteKey(url: String): String {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(host.sourceKey())) return url
        if (!url.startsWith(ControlManager.get().getAddress(true) + "proxy?")) return url
        if (url.contains("siteKey=")) return url
        return try {
            url + (if (url.contains("?")) "&" else "?") + "siteKey=" + URLEncoder.encode(host.sourceKey(), "UTF-8")
        } catch (th: Throwable) {
            url + (if (url.contains("?")) "&" else "?") + "siteKey=" + host.sourceKey()
        }
    }
}
