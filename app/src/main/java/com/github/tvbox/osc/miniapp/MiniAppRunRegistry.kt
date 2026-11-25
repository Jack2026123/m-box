package com.github.tvbox.osc.miniapp

import android.app.Activity
import java.lang.ref.WeakReference

object MiniAppRunRegistry {

    private val lock = Any()
    private val openByTitle = HashMap<String, MutableSet<WeakReference<Activity>>>()

    fun register(title: String, activity: Activity) {
        if (title.isBlank()) return
        synchronized(lock) {
            val set = openByTitle.getOrPut(title) { mutableSetOf() }
            set.removeAll { it.get() == null || it.get() == activity }
            set.add(WeakReference(activity))
        }
    }

    fun unregister(title: String, activity: Activity) {
        if (title.isBlank()) return
        synchronized(lock) {
            val set = openByTitle[title] ?: return
            set.removeAll { it.get() == null || it.get() == activity }
            if (set.isEmpty()) openByTitle.remove(title)
        }
    }

    fun finishAll(title: String) {
        if (title.isBlank()) return
        val targets = synchronized(lock) {
            val set = openByTitle.remove(title) ?: return
            set.mapNotNull { it.get() }
        }
        for (activity in targets) {
            try {
                if (!activity.isFinishing && !activity.isDestroyed) activity.finish()
            } catch (_: Throwable) {
            }
        }
    }
}
