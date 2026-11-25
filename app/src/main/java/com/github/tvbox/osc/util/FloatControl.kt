package com.github.tvbox.osc.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.ViewGroup
import com.github.tvbox.osc.ui.floatcontrol.FloatControlView
import com.github.tvbox.osc.ui.theme.ControlThemeStore
import java.lang.ref.WeakReference

/**
 * 随心所欲悬浮球：宿主挂载管理（照 FloatLog 的结构抄）。
 *
 * 只有球 + 面板的挂载 / 开关 / 位置状态，不要日志条目、落盘、搜索那些。
 * 面板内容是设置页「随心所欲」组抽出来的 ControlThemeSettingsContent，
 * 由 FloatControlView 里的 ComposeView 承载，与设置页共用同一份。
 * 开关默认关（跟悬浮日志一样），设置页「随心所欲」组里开了才有球。
 */
object FloatControl {

    const val KEY_ENABLED = "control_float_ball_enabled"

    @Volatile
    private var enabled = false

    private var appRef: Application? = null
    private var hostRegistered = false

    // 跨 Activity 保留的 UI 状态（球位置 / 是否展开 / 面板位置）
    var uiExpanded = false

    /** 松手停留的球左边缘（-1 表示未保存过，走默认右边缘；左右半隐藏时可为负或超右界，至少露 16dp）；与 ballTopPx 一起跨 Activity 复用（纵向恒全露）。 */
    var ballLeftPx = -1
    var ballTopPx = -1

    /** 面板左上角（-1 表示未拖动过，走默认居中）；按住标题栏拖动即保存，下次打开还在上次停的位置。 */
    var panelLeftPx = -1
    var panelTopPx = -1

    private val overlays = HashMap<Activity, WeakReference<FloatControlView>>()

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (isEffectivelyEnabled()) {
                attach(activity)
                scheduleRecheck(activity)
            }
        }

        // 不在 pause 时摘除：窗口还活着，摘它没道理；且摘除后恢复只剩 resume 里
        // 单发一次的 attach，瞬时 pause/resume 抖动一旦让那次 attach 落空，
        // 悬浮层就永久回不来。摘除只发生在 Activity 销毁时
        // （或功能被关掉走 detachAll）。与 FloatLog 同款。
        override fun onActivityPaused(activity: Activity) {}

        override fun onActivityDestroyed(activity: Activity) {
            detach(activity)
            overlays.remove(activity)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    }

    fun init(app: Application) {
        appRef = app
        enabled = KV.get(KEY_ENABLED, false)
        if (isEffectivelyEnabled()) registerHost()
    }

    fun isEnabled(): Boolean = enabled

    // 1.14：随心所欲悬浮球已去掉——永不挂球（之前开过球的用户也不再显示；设置里的球开关已删）。
    private fun isEffectivelyEnabled(): Boolean = false

    /** 总开关变化时重算显隐，由随心所欲总开关的切换点调用（设置页与浮窗面板共用同一行）。 */
    fun refreshVisibility() {
        if (isEffectivelyEnabled()) {
            registerHost()
            attachToCurrent()
        } else {
            detachAll()
        }
    }

    fun setEnabled(value: Boolean) {
        if (enabled == value && appRef != null) {
            if (value) attachToCurrent()
            return
        }
        enabled = value
        KV.put(KEY_ENABLED, value)
        if (value) {
            registerHost()
            attachToCurrent()
        } else {
            unregisterHost()
            detachAll()
        }
    }

    private fun registerHost() {
        val app = appRef ?: return
        if (hostRegistered) return
        hostRegistered = true
        app.registerActivityLifecycleCallbacks(callbacks)
    }

    private fun unregisterHost() {
        val app = appRef ?: return
        if (!hostRegistered) return
        hostRegistered = false
        try {
            app.unregisterActivityLifecycleCallbacks(callbacks)
        } catch (ignored: Throwable) {
        }
    }

    private fun attachToCurrent() {
        try {
            val activity = AppManager.getInstance().currentActivity()
            if (activity != null && !activity.isFinishing) {
                attach(activity)
                scheduleRecheck(activity)
            }
        } catch (ignored: Throwable) {
        }
    }

    @Synchronized
    private fun attach(activity: Activity) {
        try {
            if (!isEffectivelyEnabled()) return
            val decor = activity.window?.decorView as? ViewGroup ?: return
            val existing = overlays[activity]?.get()
            if (existing != null && existing.parent === decor) return
            val view = FloatControlView(activity)
            decor.addView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            overlays[activity] = WeakReference(view)
        } catch (ignored: Throwable) {
            // 挂载失败不许抛进生命周期回调；scheduleRecheck 会再补一次
        }
    }

    /**
     * attach 自愈：resume 时的那次 attach 若因 decor 未就绪/异常落空，
     * 等 decor 贴上窗口后再复检一次——该 Activity 的 decor 里没有活着的
     * FloatControlView 就补挂。全程自吞异常，不进生命周期回调的异常通道。
     */
    private fun scheduleRecheck(activity: Activity) {
        try {
            val decor = activity.window?.decorView as? ViewGroup ?: return
            decor.post {
                try {
                    if (!isEffectivelyEnabled() || activity.isFinishing) return@post
                    val current = activity.window?.decorView as? ViewGroup ?: return@post
                    val existing = overlays[activity]?.get()
                    if (existing == null || existing.parent !== current) attach(activity)
                } catch (ignored: Throwable) {
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    @Synchronized
    private fun detach(activity: Activity) {
        val view = overlays[activity]?.get() ?: return
        (view.parent as? ViewGroup)?.removeView(view)
    }

    @Synchronized
    private fun detachAll() {
        for ((_, ref) in overlays) {
            val view = ref.get() ?: continue
            (view.parent as? ViewGroup)?.removeView(view)
        }
        overlays.clear()
    }

    /**
     * 从外部打开随心所欲面板。
     * 已挂载时直接展开；未挂载时先挂载（新建视图按 uiExpanded=true 直接展开）。
     */
    fun openPanel() {
        uiExpanded = true
        try {
            val activity = AppManager.getInstance().currentActivity() ?: return
            if (activity.isFinishing) return
            val view = overlays[activity]?.get()
            if (view != null && view.parent != null) {
                view.showPanel()
            } else {
                attach(activity)
            }
        } catch (ignored: Throwable) {
        }
    }
}
