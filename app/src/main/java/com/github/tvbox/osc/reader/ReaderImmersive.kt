package com.github.tvbox.osc.reader

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Immersive status-bar guard for the reader activities.
 *
 * BaseActivity hides the system bars on resume, but on device the
 * status bar still leaks back while reading — most visibly in the
 * vertical-scroll body, and around reader dialogs. The legacy
 * system-ui visibility listener BaseActivity relies on does not
 * fire for insets-controller driven changes on modern Android, so
 * once the bar is back nothing takes it away again.
 *
 * The guard polls the activity window's root insets while the
 * reader is resumed: the moment the status bar is observed visible
 * a grace timer starts, and if it is still visible
 * [REHIDE_AFTER_MS] later it is hidden again. Menus, dialogs and
 * system transients therefore get at most about three seconds of
 * status bar, and reading itself stays immersive. Only the status
 * bar is touched here; the navigation bar keeps BaseActivity's
 * policy, and window transitions / the reader bars' own animations
 * are unaffected.
 */
class ReaderStatusBarGuard(private val activity: Activity) {

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var visibleSinceMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            enforce()
            handler.postDelayed(this, POLL_MS)
        }
    }

    /** Hide immediately and start watching; call from onResume. */
    fun start() {
        hideNow()
        if (running) return
        running = true
        handler.postDelayed(tick, POLL_MS)
    }

    /** Stop watching; call from onPause. */
    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        visibleSinceMs = 0L
    }

    /** Stop watching and put the status bar back; call on exit. */
    fun restore() {
        stop()
        try {
            controller().show(WindowInsetsCompat.Type.statusBars())
        } catch (th: Throwable) {
            // The window may already be tearing down; the host
            // activity re-applies its own bar policy on resume anyway.
        }
    }

    private fun controller(): WindowInsetsControllerCompat =
        WindowCompat.getInsetsController(activity.window, activity.window.decorView)

    private fun hideNow() {
        try {
            val controller = controller()
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } catch (th: Throwable) {
            // Best effort only; the poll keeps enforcing while the
            // bar stays visible.
        }
        visibleSinceMs = 0L
    }

    private fun enforce() {
        val insets = try {
            ViewCompat.getRootWindowInsets(activity.window.decorView)
        } catch (th: Throwable) {
            null
        } ?: return
        if (!insets.isVisible(WindowInsetsCompat.Type.statusBars())) {
            visibleSinceMs = 0L
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (visibleSinceMs == 0L) {
            visibleSinceMs = now
        } else if (now - visibleSinceMs >= REHIDE_AFTER_MS) {
            hideNow()
        }
    }

    private companion object {
        const val POLL_MS = 400L
        const val REHIDE_AFTER_MS = 3000L
    }
}

/**
 * Hides the status bar inside a reader dialog window as well.
 * Compose dialogs live in their own window, which does not inherit
 * the activity's immersive state; without this the bar would sit
 * over the catalog / bookmarks / settings dialogs for as long as
 * they are open. Place inside the dialog's content composition.
 */
@Composable
internal fun HideStatusBarInDialogWindow() {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        try {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } catch (th: Throwable) {
            // Best effort only; the activity guard still applies
            // its three-second rule once the dialog is gone.
        }
    }
}
