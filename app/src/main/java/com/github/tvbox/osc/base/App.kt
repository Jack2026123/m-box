package com.github.tvbox.osc.base

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.StrictMode
import android.os.SystemClock

import com.github.catvod.crawler.JsLoader
import com.github.tvbox.osc.BuildConfig
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.data.AppDataManager
import com.github.tvbox.osc.data.PlaybackPorts
import com.github.tvbox.osc.io.FileUtils
import com.github.tvbox.osc.net.OkGoHelper
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.util.AppContextHolder
import com.github.tvbox.osc.util.AppManager
import com.github.tvbox.osc.util.EpgUtil
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.LanguageManager
import com.github.tvbox.osc.util.Probe
import com.p2p.P2PClass
import com.whl.quickjs.android.QuickJSLoader

import com.github.tvbox.osc.player.engine.PlayerCache
import me.jessyan.autosize.AutoSizeConfig
import me.jessyan.autosize.unit.Subunits

class App : Application() {
    private var vodInfo: VodInfo? = null

    override fun attachBaseContext(base: Context) {
        Probe.step("kvInit") { KV.init(base) }
        super.attachBaseContext(LanguageManager.wrap(base))
    }

    private var bootLastMs = 0L

    // 退出兜底：统计存活 Activity 数，归零（最后一个 Activity 销毁）时关
    // P2P 引擎——覆盖不走 AppManager.finishAllActivity() 的离开方式（逐页
    // 返回退完、finishAffinity 等）。只认销毁归零，不挂前后台切换：切后台
    // 不关。shutdownP2p 幂等、未起引擎时零动作。
    private var liveActivityCount = 0

    private val exitGuardCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            this@App.liveActivityCount++
        }

        override fun onActivityDestroyed(activity: Activity) {
            this@App.liveActivityCount--
            if (this@App.liveActivityCount <= 0) {
                this@App.liveActivityCount = 0
                App.shutdownP2p()
            }
        }

        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    }

    private fun bootMark(step: String) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        LOG.i("echo-boot $step dt=" + (now - bootLastMs) + "ms")
        bootLastMs = now
    }

    override fun onCreate() {
        super.onCreate()
        bootLastMs = SystemClock.elapsedRealtime()
        installStrictMode()
        bootMark("installStrictMode")
        instance = this
        AppContextHolder.install(this)
        com.github.catvod.Init.set(this)
        com.github.tvbox.osc.util.FloatLog.init(this)
        com.github.tvbox.osc.util.FloatControl.init(this)
        com.github.tvbox.osc.music.FloatLyric.init(this)
        registerActivityLifecycleCallbacks(exitGuardCallbacks)
        PlaybackPorts.currentVod = { getInstance()?.getVodInfo() }
        com.github.tvbox.osc.util.BootGuard.install()
        bootMark("bootGuard")
        initParams()
        bootMark("initParams")
        Probe.trace("boot:okGoHelper") { OkGoHelper.init() }
        bootMark("okGoHelper")
        Probe.trace("boot:epgUtil") { EpgUtil.init() }
        bootMark("epgUtil")
        Probe.trace("boot:controlManager") { ControlManager.init(this) }
        bootMark("controlManager")
        Probe.trace("boot:appDataManager") { AppDataManager.init() }
        bootMark("appDataManager")
        Probe.trace("boot:autoSize") {
            AutoSizeConfig.getInstance().setCustomFragment(true).unitsManager
                .setSupportDP(false)
                .setSupportSP(false)
                .setSupportSubunits(Subunits.MM)
        }
        bootMark("autoSize")
        Probe.trace("boot:playerCacheSize") {
            val exoCacheBytes = Math.max(128, KV.get(HawkConfig.EXO_CACHE_SIZE_MB, HawkConfig.EXO_CACHE_SIZE_MB_DEFAULT)) * 1024L * 1024L
            bootMark("playerCacheSize:kv")
            PlayerCache.setSharedCacheSizeBytes(exoCacheBytes)
        }
        bootMark("playerCacheSize")
        Probe.trace("boot:quickJSLoader") { loadQuickJs() }
        bootMark("quickJSLoader")
        Probe.trace("boot:vodImages") { com.github.tvbox.osc.ui.components.VodImages.init() }
        bootMark("vodImages")
        Thread({
            FileUtils.purgeExoCacheIfPending()
            PlayerCache.warmup(this)
            Probe.trace("boot:cleanPlayerCache") { FileUtils.cleanPlayerCache() }
        }, "exo-cache-boot").start()
        bootMark("onCreateDone")
    }

    private fun loadQuickJs() {
        val previous = StrictMode.allowThreadDiskReads()
        try {
            QuickJSLoader.init()
        } finally {
            StrictMode.setThreadPolicy(previous)
        }
    }

    private fun initParams() {
        KV.put(HawkConfig.PLAYER_IS_LIVE, false)
        if (!KV.contains(HawkConfig.PLAY_TYPE)) {
            KV.put(HawkConfig.PLAY_TYPE, 2)
        } else {
            val playType = KV.get(HawkConfig.PLAY_TYPE, 2)
            if (playType == 0 || playType == 1 || (playType > 2 && playType != 3 && playType != 13)) {
                KV.put(HawkConfig.PLAY_TYPE, 2)
            }
        }
    }

    private fun installStrictMode() {
        if (!BuildConfig.DEBUG) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .detectFileUriExposure()
                .penaltyLog()
                .build()
        )
    }

    override fun onTerminate() {
        super.onTerminate()
        JsLoader.destroy()
    }

    fun setVodInfo(vodinfo: VodInfo?) {
        this.vodInfo = vodinfo
    }

    fun getVodInfo(): VodInfo? {
        return this.vodInfo
    }

    fun getCurrentActivity(): Activity {
        return AppManager.getInstance().currentActivity()
    }

    fun setDashData(data: String?) {
        dashData = data
    }

    fun getDashData(): String? {
        return dashData
    }

    companion object {
        private var instance: App? = null

        private var p: P2PClass? = null

        @JvmField
        var burl: String? = null

        private var dashData: String? = null

        @JvmStatic
        fun getInstance(): App? {
            return instance
        }

        @JvmStatic
        fun getp2p(): P2PClass? {
            try {
                if (p == null) {
                    p = P2PClass(FileUtils.getExternalCachePath())
                }
                return p
            } catch (e: Exception) {
                LOG.e(e.toString())
                return null
            }
        }

        /** 只窥视懒单例，绝不触发实例化（关闭路径专用：没起过引擎时不许为了关而把它起出来）。 */
        @JvmStatic
        fun peekP2p(): P2PClass? {
            return p
        }

        /**
         * 关闭 p2p 引擎并摘掉懒单例引用：先摘引用（后续 getp2p() 会懒重建新引擎，
         * 不会拿到将死实例），再对旧实例依次 terminate + endhttpd，各自独立兜底，
         * 任一 native 调用失败都不影响调用方的退出/切换流程。单例不存在时直接返回。
         */
        @JvmStatic
        fun shutdownP2p() {
            val inst = p ?: return
            p = null
            try {
                inst.P2Pdoxterminate()
            } catch (th: Throwable) {
                LOG.e("echo-p2p terminate failed", th)
            }
            try {
                inst.P2Pdoxendhttpd()
            } catch (th: Throwable) {
                LOG.e("echo-p2p endhttpd failed", th)
            }
        }
    }
}
