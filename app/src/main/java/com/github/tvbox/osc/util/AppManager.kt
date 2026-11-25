package com.github.tvbox.osc.util

import android.app.Activity

import com.github.tvbox.osc.base.App

import java.util.Stack

class AppManager private constructor() {

    fun addActivity(activity: Activity) {
        val stack = activityStack ?: Stack<Activity>()
        activityStack = stack
        stack.add(activity)
    }

    fun isActivity(): Boolean {
        return activityStack?.isEmpty() == false
    }

    fun currentActivity(): Activity {
        return requireNotNull(activityStack) { "activity stack not initialized" }.lastElement()
    }

    fun finishActivity() {
        val activity = requireNotNull(activityStack) { "activity stack not initialized" }.lastElement()
        if (!activity.isFinishing()) {
            activity.finish()
        }
    }

    fun finishActivity(activity: Activity) {
        activityStack?.remove(activity)
    }

    fun finishActivity(cls: Class<*>) {
        for (activity in activityStack.orEmpty()) {
            if (activity.javaClass.equals(cls)) {
                if (!activity.isFinishing()) {
                    activity.finish()
                }
                break
            }
        }
    }

    /** Finishes EVERY stacked instance of [cls], not just the first. */
    fun finishActivities(cls: Class<*>) {
        for (activity in activityStack.orEmpty().toList()) {
            if (activity.javaClass.equals(cls)) {
                if (!activity.isFinishing()) {
                    activity.finish()
                }
            }
        }
    }

    fun backActivity(cls: Class<*>) {
        val stack = activityStack ?: return
        while (!stack.empty()) {
            val activity = stack.pop()
            if (activity.javaClass.equals(cls)) {
                stack.push(activity)
                break
            } else {
                activity.finish()
            }
        }
    }

    fun finishAllActivity() {
        // 退出软件的统一入口（首页双击返回退出、appExit 都经这里）：先把 xg 的
        // P2P 引擎关死并摘单例，保证退出后进程即便留在后台也不跑引擎/上传。
        // shutdownP2p 幂等、未起引擎时零动作、内部自带兜底。
        App.shutdownP2p()
        val stack = activityStack
        if (stack != null && stack.size > 0) {
            for (i in 0 until stack.size) {
                val activity = stack[i]
                if (null != activity) {
                    if (!activity.isFinishing()) {
                        activity.finish()
                    }
                }
            }
            stack.clear()
        }
    }

    fun getActivity(cls: Class<*>): Activity? {
        for (activity in activityStack.orEmpty()) {
            if (activity.javaClass.equals(cls)) {
                return activity
            }
        }
        return null
    }

    fun appExit(code: Int) {
        try {
            finishAllActivity()
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(code)
        } catch (e: Exception) {
            activityStack?.clear()
            LOG.e("AppManager", e)
        }
    }

    companion object {
        private var activityStack: Stack<Activity>? = null

        private val instance = AppManager()

        @JvmStatic
        fun getInstance(): AppManager {
            return instance
        }
    }
}
