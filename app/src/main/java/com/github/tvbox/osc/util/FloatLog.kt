package com.github.tvbox.osc.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import com.github.tvbox.osc.ui.floatlog.FloatLogView
import java.io.File
import java.lang.ref.WeakReference
import java.util.ArrayDeque

/**
 * 悬浮日志：内存环形缓冲 + 宿主挂载管理。
 *
 * 日志来源在各门面处单点接入（DebugLogStore / SpiderDebug / LOG），
 * 统一 append 到这里；面板 FloatLogView 只读这里的快照。
 * 关闭时 append 只做一次 volatile 判断，不进锁、不攒数据。
 */
object FloatLog {

    const val KEY_ENABLED = "float_log_enabled"

    private const val MAX_ENTRIES = 1500
    private const val MAX_BYTES = 512 * 1024

    /** 落盘文件：日志开着就不丢（重启不截断、条数/字节数不顶掉）；只有显式 clear() 才删。 */
    private const val PERSIST_FILE_NAME = "avbox_floatlog.txt"

    class Entry(
        val seq: Long,
        val timeMillis: Long,
        val thread: String,
        val source: String,
        val tag: String?,
        val text: String,
    ) {
        /** 搜索匹配面：来源 + 标签 + 全文（含多行 JSON）。 */
        val searchText: String by lazy(LazyThreadSafetyMode.NONE) {
            val sb = StringBuilder(text.length + 32)
            sb.append(source)
            if (!tag.isNullOrEmpty()) sb.append(' ').append(tag)
            sb.append('\n').append(text)
            sb.toString().lowercase()
        }

        fun matches(lowerQuery: String): Boolean = searchText.contains(lowerQuery)
    }

    private val lock = Any()
    private val deque = ArrayDeque<Entry>()
    private var totalBytes = 0
    private var seqCounter = 0L

    /** 缓冲被清空时递增，面板据此整页重渲染。 */
    @Volatile
    var clearGeneration = 0L
        private set

    @Volatile
    private var enabled = false

    private var appRef: Application? = null
    private var hostRegistered = false

    // 跨 Activity 保留的 UI 状态（球位置 / 是否展开 / 搜索词 / 面板位置）
    var uiExpanded = false

    /** 页签面板当前页签（0 控制台 / 1 接口 / 2 系统 / 3 Logcat），跨 Activity 复用；默认接口（与录像打开态一致）。 */
    var debugLogTab = 1
    var uiQuery: String? = null
    var ballOnLeft = false
    var ballTopPx = -1

    /** 1.43 球两者合一：松手停留的左边缘（-1 表示未保存过，走默认右边缘；左右半隐藏时可为负或超右界，至少露 16dp）；与 ballTopPx 一起跨 Activity 复用（纵向恒全露）。 */
    var ballLeftPx = -1

    /** 球贴边半隐藏（只露约 1/3）；仅左右边缘，上下不半隐藏。 */
    var ballHalfHidden = false
    var panelLeftPx = -1
    var panelTopPx = -1

    /** 「只看新增」锚点：只显示 seq 大于此值的条目；-1 表示看全部。 */
    var uiAnchorSeq = -1L

    private val overlays = HashMap<Activity, WeakReference<FloatLogView>>()

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (enabled) {
                attach(activity)
                scheduleRecheck(activity)
            }
        }

        // 不在 pause 时摘除：窗口还活着，摘它没道理；且摘除后恢复只剩 resume 里
        // 单发一次的 attach，瞬时 pause/resume 抖动（如首页内点分类 tab）一旦让
        // 那次 attach 落空，悬浮层就永久回不来。摘除只发生在 Activity 销毁时
        // （或功能被关掉走 detachAll）。
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
        if (enabled) registerHost()
    }

    fun isEnabled(): Boolean = enabled

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
            if (!enabled) return
            val decor = activity.window?.decorView as? ViewGroup ?: return
            val existing = overlays[activity]?.get()
            if (existing != null && existing.parent === decor) return
            val view = FloatLogView(activity)
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
     * FloatLogView 就补挂。全程自吞异常，不进生命周期回调的异常通道。
     */
    private fun scheduleRecheck(activity: Activity) {
        try {
            val decor = activity.window?.decorView as? ViewGroup ?: return
            decor.post {
                try {
                    if (!enabled || activity.isFinishing) return@post
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

    /** 仅在 synchronized(lock) 内使用：SimpleDateFormat 非线程安全。 */
    private val persistDateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault())

    private fun persistFile(): File? {
        return try {
            val app = appRef ?: return null
            File(app.filesDir, PERSIST_FILE_NAME)
        } catch (ignored: Throwable) {
            null
        }
    }

    /** 仅在 synchronized(lock) 内调用：把一条记录追到落盘文件（开着就不丢，重启不截断）。 */
    private fun writePersistLine(entry: Entry) {
        try {
            val file = persistFile() ?: return
            val sb = StringBuilder()
            sb.append(persistDateFormat.format(java.util.Date(entry.timeMillis)))
            sb.append(" [").append(entry.source).append("]")
            if (!entry.tag.isNullOrEmpty()) sb.append(" ").append(entry.tag)
            sb.append(" ").append(entry.text).append("\n")
            java.io.FileOutputStream(file, true).use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
        } catch (ignored: Throwable) {
        }
    }

    @JvmStatic
    fun append(source: String, tag: String?, text: String?) {
        if (!enabled) return
        val body = text ?: return
        synchronized(lock) {
            val entry = Entry(++seqCounter, System.currentTimeMillis(), Thread.currentThread().name, source, tag, body)
            deque.addLast(entry)
            totalBytes += body.length + (tag?.length ?: 0) + 48
            while (deque.size > MAX_ENTRIES || totalBytes > MAX_BYTES) {
                val old = deque.pollFirst() ?: break
                totalBytes -= old.text.length + (old.tag?.length ?: 0) + 48
            }
            writePersistLine(entry)
        }
    }

    fun clear() {
        synchronized(lock) {
            deque.clear()
            totalBytes = 0
            clearGeneration++
            try {
                persistFile()?.delete()
            } catch (ignored: Throwable) {
            }
        }
    }

    fun snapshot(): List<Entry> {
        synchronized(lock) {
            return ArrayList(deque)
        }
    }

    fun exportText(): String {
        val file = try {
            persistFile()
        } catch (ignored: Throwable) {
            null
        }
        if (file != null && file.exists() && file.length() > 0L) {
            try {
                val text = file.readText(Charsets.UTF_8)
                var count = 0
                val stamp = Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} ")
                for (line in text.lines()) {
                    if (stamp.containsMatchIn(line)) count++
                }
                if (count > 0) {
                    return StringBuilder().append("AVBox运行日志，共").append(count).append("条\n").append(text).toString()
                }
            } catch (ignored: Throwable) {
            }
        }
        val entries = snapshot()
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault())
        val sb = StringBuilder()
        sb.append("AVBox运行日志，共").append(entries.size).append("条\n")
        for (entry in entries) {
            sb.append(format.format(java.util.Date(entry.timeMillis)))
            sb.append(" [").append(entry.source).append("]")
            if (!entry.tag.isNullOrEmpty()) sb.append(" ").append(entry.tag)
            sb.append(" ").append(entry.text).append("\n")
        }
        return sb.toString()
    }

    fun currentSeq(): Long = synchronized(lock) { seqCounter }

    /** 落盘文件字节数（页签面板底部“共 N 条 · M B”里的 M）；文件不存在时为 0。 */
    fun persistBytes(): Long {
        return try {
            val f = persistFile()
            if (f != null && f.exists()) f.length() else 0L
        } catch (ignored: Throwable) {
            0L
        }
    }

    /** 内存缓冲字节数（落盘文件不存在时的回退）。 */
    fun memoryBytes(): Long = synchronized(lock) { totalBytes.toLong() }

    /**
     * 从外部（设置“日志调试”入口）打开页签式日志面板。
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
