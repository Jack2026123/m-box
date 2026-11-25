package com.github.tvbox.osc.miniapp.engine

import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.thread

/**
 * logtest终极版: 海阔 SchedulePool 的 AVBox 对标版.
 *
 * 海阔实现 (JSEngine.schedulePool, SchedulePool.kt):
 * - Task = id + 间隔毫秒 + js 代码串; id 为空自动 UUID, 间隔默认 10000、最小 500.
 * - add 时按当前 Activity.hashCode 分桶 (不是列表/播放页则 fallback homeActivity);
 *   离页时 clearSchedulePool(activity) 整桶删, unRegisterTask(id) 按 id 单删.
 * - 一个 Timer 每 500ms 扫一遍, 到期 (now - start >= 间隔) 且没在跑的,
 *   异步调 JSEngine.evalJS(js串, id) 重跑一遍, 跑完 running=false, 下个周期再跑
 *   (周期性任务; 500ms 一扫就是 help 里"最大 500ms 误差"的来源).
 *
 * AVBox 对标差异 (有意, 非缺口):
 * - 分桶键用规则 title (小程序会话), 不用 Activity.hashCode: AVBox 的小程序页
 *   是单个 MiniAppRunActivity 内压栈, 内页不是 Activity, 按会话分桶是最接近的
 *   等价物; 会话结束 (ViewModel.onCleared) 时整池 destroy.
 * - 触发同样走宿主 evalJs (一次一 QuickJS 上下文 + prelude + 插件): 任务串本身是
 *   自包含的序列化函数+参数, 不需要保活上下文, 与海阔 Rhino 一次一 Context 同构.
 * - 任务里的 setHomeResult 只写进当次 eval 的 Execution, 不推 UI (AVBox 引擎
 *   本来就是按次返回结果, 没有海阔那种常驻页面 item 状态); 任务的 vars/fetch/log
 *   照常走 (与宿主同 varMap).
 */
data class MiniAppTask(
    val id: String,
    val intervalMs: Long,
    val js: String,
    var startMs: Long = System.currentTimeMillis(),
    var running: Boolean = false,
)

class MiniAppSchedulePool(
    private val fire: (MiniAppTask) -> Unit,
    private val enableTimer: Boolean = true,
) {
    private val lock = Any()
    private val buckets = HashMap<String, HashMap<String, MiniAppTask>>()
    private var timer: Timer? = null

    fun add(pageKey: String, task: MiniAppTask) {
        synchronized(lock) {
            removeByIdLocked(task.id)
            val bucket = buckets.getOrPut(pageKey) { HashMap() }
            bucket[task.id] = task
            ensureTimerLocked()
        }
    }

    fun removeById(id: String) {
        synchronized(lock) { removeByIdLocked(id) }
    }

    fun clearPage(pageKey: String) {
        synchronized(lock) {
            buckets.remove(pageKey)
            if (buckets.isEmpty()) stopTimerLocked()
        }
    }

    fun destroy() {
        synchronized(lock) {
            buckets.clear()
            stopTimerLocked()
        }
    }

    fun taskCount(): Int {
        synchronized(lock) { return buckets.values.sumOf { it.size } }
    }

    private fun removeByIdLocked(id: String) {
        for (bucket in buckets.values) bucket.remove(id)
    }

    private fun ensureTimerLocked() {
        if (!enableTimer || timer != null) return
        val t = Timer("miniapp-schedule", true)
        t.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                try {
                    fireDue(System.currentTimeMillis())
                } catch (e: Throwable) {
                }
            }
        }, 50L, 500L)
        timer = t
    }

    private fun stopTimerLocked() {
        try {
            timer?.cancel()
        } catch (e: Throwable) {
        }
        timer = null
    }

    /** Due-check + async fire; [nowMs] is injectable so unit tests need no waiting. */
    internal fun fireDue(nowMs: Long) {
        val due = ArrayList<MiniAppTask>()
        synchronized(lock) {
            for (bucket in buckets.values) {
                for (task in bucket.values) {
                    if (!task.running && nowMs - task.startMs >= task.intervalMs) {
                        task.running = true
                        task.startMs = nowMs
                        due.add(task)
                    }
                }
            }
        }
        for (task in due) {
            thread(start = true, isDaemon = true, name = "miniapp-task-${task.id}") {
                try {
                    fire(task)
                } catch (e: Throwable) {
                } finally {
                    synchronized(lock) { task.running = false }
                }
            }
        }
    }
}
