package com.github.tvbox.osc.miniapp.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * logtest终极版: SchedulePool 纯逻辑单测 (无 Android、无 QuickJS).
 * Due 语义对标海阔 SchedulePool: now - start >= 间隔 且没在跑才触发,
 * 触发后 running 复位, 下个周期可再触发.
 */
class MiniAppSchedulePoolTest {

    @Test
    fun fireDue_notBeforeInterval_firesAtInterval() {
        val fired = AtomicInteger(0)
        val pool = MiniAppSchedulePool({ fired.incrementAndGet() }, enableTimer = false)
        val task = MiniAppTask(id = "t1", intervalMs = 10000L, js = "1", startMs = 0L)
        pool.add("page", task)
        pool.fireDue(9999L)
        assertEquals(0, fired.get())
        pool.fireDue(10000L)
        var tries = 0
        while (fired.get() < 1 && tries < 100) {
            Thread.sleep(20)
            tries++
        }
        assertEquals(1, fired.get())
        pool.destroy()
    }

    @Test
    fun fireDue_resetsRunning_soNextCycleCanFireAgain() {
        val latch = CountDownLatch(1)
        val pool = MiniAppSchedulePool({ latch.countDown() }, enableTimer = false)
        val task = MiniAppTask(id = "t1", intervalMs = 1000L, js = "1", startMs = 0L)
        pool.add("page", task)
        pool.fireDue(1000L)
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        var tries = 0
        while (task.running && tries < 100) {
            Thread.sleep(20)
            tries++
        }
        assertEquals(false, task.running)
        pool.fireDue(2000L)
        assertEquals(1, pool.taskCount())
        pool.destroy()
    }

    @Test
    fun add_sameIdReplaces_removeByIdAndClearPage() {
        val pool = MiniAppSchedulePool({ }, enableTimer = false)
        pool.add("p1", MiniAppTask(id = "a", intervalMs = 1000L, js = "1", startMs = 0L))
        pool.add("p1", MiniAppTask(id = "a", intervalMs = 2000L, js = "2", startMs = 0L))
        assertEquals(1, pool.taskCount())
        pool.add("p2", MiniAppTask(id = "b", intervalMs = 1000L, js = "3", startMs = 0L))
        assertEquals(2, pool.taskCount())
        pool.removeById("a")
        assertEquals(1, pool.taskCount())
        pool.clearPage("p2")
        assertEquals(0, pool.taskCount())
        pool.destroy()
    }

    @Test
    fun destroy_clearsAll() {
        val pool = MiniAppSchedulePool({ }, enableTimer = false)
        pool.add("p1", MiniAppTask(id = "a", intervalMs = 1000L, js = "1", startMs = 0L))
        pool.add("p2", MiniAppTask(id = "b", intervalMs = 1000L, js = "2", startMs = 0L))
        assertEquals(2, pool.taskCount())
        pool.destroy()
        assertEquals(0, pool.taskCount())
    }
}
