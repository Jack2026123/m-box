package com.github.tvbox.osc.base

import com.github.tvbox.osc.util.LOG
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroup

import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.PermissionChecker
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

import com.github.tvbox.osc.R
import com.github.tvbox.osc.util.AppManager
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LanguageManager
import com.github.tvbox.osc.util.OrientationSetting
import com.github.tvbox.osc.util.Probe
import com.github.tvbox.osc.util.SpiderStorageNotice

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

import me.jessyan.autosize.AutoSizeCompat
import me.jessyan.autosize.AutoSizeConfig
import me.jessyan.autosize.internal.CustomAdapt
import com.github.tvbox.osc.util.CutoutUtil

abstract class BaseActivity : AppCompatActivity(), CustomAdapt {

    @JvmField
    protected var mContext: Context? = null

    private var orientationPolicy = Int.MIN_VALUE

    private val refreshAutoSizeRunnable = Runnable {
        if (shouldRefreshAutoSize()) {
            refreshAutoSize()
        }
    }

    private val hideSysBarRunnable = Runnable {
        hideSysBar()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The orientation policy must reach the window manager before
        // anything else runs: a fresh activity record starts
        // UNSPECIFIED, so the launch configuration is resolved from
        // the physical sensor (portrait, phone in hand) and the page
        // composes portrait; a policy issued only at the end of
        // onCreate then rotates it back — the visible
        // portrait-then-landscape flip on every sub-screen launch
        // while an orientation is locked. Issuing it first lets the
        // launch land on the policy value directly. The policy cache
        // keeps every later call write-free.
        applyOrientationPolicy()
        try {
            if (screenRatio < 0) {
                val dm = DisplayMetrics()
                windowManager.defaultDisplay.getMetrics(dm)
                updateScreenRatio(dm)
            }
        } catch (th: Throwable) {
            LOG.e("BaseActivity", th)
        }
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false)
            window.setStatusBarContrastEnforced(false)
        }
        setContentView(ComposeView(this).apply {
            id = R.id.compose_view
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        })
        mContext = this
        initSystemUiListener()
        CutoutUtil.adaptCutoutAboveAndroidP(this, true)
        AppManager.getInstance().addActivity(this)
        applyOrientationPolicy()
        init()
    }

    override fun onResume() {
        Probe.measure(this, "onResume") {
            super.onResume()
            SpiderStorageNotice.onHostResumed(this)
            applyOrientationPolicy()
            applyHideStatusBarPref()
            hideSysBar()
            if (shouldRefreshAutoSize()) {
                refreshAutoSize()
                scheduleRefreshAutoSize()
            }
        }
    }

    internal fun applyHideStatusBarPref() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (KV.get(HawkConfig.HIDE_STATUS_BAR, false) || keepStatusBarHidden()) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    protected open fun keepStatusBarHidden(): Boolean = false

    open fun hideSysBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            var uiOptions = window.decorView.systemUiVisibility
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_FULLSCREEN
            uiOptions = uiOptions or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            window.decorView.systemUiVisibility = uiOptions
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun initSystemUiListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val decorView = window.decorView
            decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                val hiddenBars = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
                if ((visibility and hiddenBars) != hiddenBars) {
                    decorView.removeCallbacks(hideSysBarRunnable)
                    decorView.postDelayed(hideSysBarRunnable, SYSBAR_REHIDE_DELAY_MS)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        SpiderStorageNotice.onHostPaused(this)
        window.decorView.removeCallbacks(refreshAutoSizeRunnable)
        window.decorView.removeCallbacks(hideSysBarRunnable)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        Probe.measure(this, "onWindowFocusChanged") {
            super.onWindowFocusChanged(hasFocus)
            if (hasFocus) {
                applyHideStatusBarPref()
                hideSysBar()
                if (shouldRefreshAutoSize()) {
                    scheduleRefreshAutoSize()
                }
            }
        }
    }

    protected open fun shouldRefreshAutoSize(): Boolean {
        return false
    }

    open fun applyOrientationPolicy() {
        try {
            val desired = orientationPolicyValue()
            if (orientationPolicy == desired) {
                return
            }
            orientationPolicy = desired
            requestedOrientation = desired
        } catch (th: Throwable) {
            LOG.e("BaseActivity", th)
        }
    }

    open fun orientationPolicyValue(): Int {
        try {
            // The screen-orientation preference IS the policy now:
            // follow-system (the default) leaves the device fully in
            // charge — no lock at all, so rotating a phone into
            // landscape actually reaches the TV form; portrait /
            // landscape lock their orientation family. This replaces
            // the legacy size-based portrait lock on small screens,
            // which would otherwise make the landscape form
            // unreachable on phones. Playback pages are NOT
            // second-guessed here: Detail/Live re-read this value
            // when entering/leaving fullscreen and keep their own
            // video-driven overrides while fullscreen.
            return when (OrientationSetting.mode()) {
                OrientationSetting.PORTRAIT ->
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                OrientationSetting.LANDSCAPE ->
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        } catch (th: Throwable) {
            LOG.e("BaseActivity", th)
            return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationPolicy()
    }

    private fun scheduleRefreshAutoSize() {
        val decorView = window.decorView
        decorView.removeCallbacks(refreshAutoSizeRunnable)
        decorView.postDelayed(refreshAutoSizeRunnable, 300)
    }

    private fun refreshAutoSize() {
        try {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(dm)
            if (dm.widthPixels <= 0 || dm.heightPixels <= 0) {
                return
            }
            updateScreenRatio(dm)
            AutoSizeConfig.getInstance()
                .setScreenWidth(dm.widthPixels)
                .setScreenHeight(dm.heightPixels)
            AutoSizeCompat.autoConvertDensityOfCustomAdapt(super.getResources(), this)
            window.decorView.requestLayout()
        } catch (th: Throwable) {
            LOG.e("BaseActivity", th)
        }
    }

    private fun updateScreenRatio(dm: DisplayMetrics) {
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels
        val min = Math.min(screenWidth, screenHeight)
        if (min > 0) {
            screenRatio = Math.max(screenWidth, screenHeight).toFloat() / min.toFloat()
        }
    }

    override fun getResources(): Resources {
        if (Looper.myLooper() === Looper.getMainLooper()) {
            AutoSizeCompat.autoConvertDensityOfCustomAdapt(super.getResources(), this)
        }
        return super.getResources()
    }

    fun hasPermission(permission: String): Boolean {
        var has = true
        try {
            has = PermissionChecker.checkSelfPermission(this, permission) == PermissionChecker.PERMISSION_GRANTED
        } catch (e: Exception) {
            LOG.e("BaseActivity", e)
        }
        return has
    }

    protected abstract fun init()

    override fun onDestroy() {
        super.onDestroy()
        AppManager.getInstance().finishActivity(this)
    }

    protected fun getAssetText(fileName: String): String {
        val stringBuilder = StringBuilder()
        try {
            val assets = assets
            val bf = BufferedReader(InputStreamReader(assets.open(fileName)))
            var line = bf.readLine()
            while (line != null) {
                stringBuilder.append(line)
                line = bf.readLine()
            }
            return stringBuilder.toString()
        } catch (e: IOException) {
            LOG.e("BaseActivity", e)
        }
        return ""
    }

    override fun getSizeInDp(): Float {
        return if (isBaseOnWidth()) 1280f else 720f
    }

    override fun isBaseOnWidth(): Boolean {
        return !(screenRatio >= 4.0f)
    }

    companion object {
        private const val SYSBAR_REHIDE_DELAY_MS = 100L

        private var screenRatio = -100.0f
    }
}
