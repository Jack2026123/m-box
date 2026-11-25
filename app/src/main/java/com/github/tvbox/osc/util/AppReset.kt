package com.github.tvbox.osc.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Process
import android.webkit.CookieManager
import android.webkit.WebStorage
import com.github.tvbox.osc.data.AppDataManager
import com.tencent.mmkv.MMKV
import java.io.File

object AppReset {

    fun wipeAndRestart(context: Context) {
        val appContext = context.applicationContext ?: context
        wipeMmkv()
        try {
            AppDataManager.deleteAllData()
        } catch (t: Throwable) {
        }
        wipeSharedPreferences(appContext)
        wipeWebViewData()
        wipeCaches(appContext)
        // Relaunch FIRST, die SECOND (the FongMi/MuseTV AppResetter
        // order): the NEW_TASK|CLEAR_TASK launcher intent is handed to
        // the system while this process is still alive and foreground,
        // then the process kills itself. The queued intent is already
        // in AMS hands, so the fresh home is created in a new process
        // straight over the dying one — no launcher gap, no splash
        // trampoline, no timer. Two earlier mechanisms failed exactly
        // on the hand-off: an AlarmManager PendingIntent scheduled by
        // the dying process never fired on the user's Meizu ROM, and a
        // separate-process trampoline activity worked but was
        // transparent, so the desktop showed through for its whole
        // restart delay.
        //
        // AppManager.finishAllActivity() is deliberately NOT called:
        // CLEAR_TASK already tears down the whole task stack, and
        // every remaining activity dies with the process a line later
        // anyway — finishing them first would only add a beat where
        // the task is empty but the process is still alive.
        relaunch(context, appContext)
        Process.killProcess(Process.myPid())
    }

    private fun relaunch(context: Context, appContext: Context) {
        try {
            val intent = appContext.packageManager
                .getLaunchIntentForPackage(appContext.packageName) ?: return
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
            // Prefer the foreground Activity as the starter, exactly
            // like the reference implementation; fall back to the
            // application context (the NEW_TASK flag above is what
            // makes a non-Activity starter legal).
            val starter = if (context is Activity) context else appContext
            starter.startActivity(intent)
        } catch (t: Throwable) {
        }
    }

    private fun wipeMmkv() {
        try {
            MMKV.mmkvWithID("avbox_kv", MMKV.SINGLE_PROCESS_MODE)?.clearAll()
        } catch (t: Throwable) {
        }
        try {
            MMKV.defaultMMKV()?.clearAll()
        } catch (t: Throwable) {
        }
    }

    private fun wipeSharedPreferences(context: Context) {
        try {
            val dir = File(context.applicationInfo.dataDir, "shared_prefs")
            val files = dir.listFiles() ?: return
            for (file in files) {
                if (file.isFile && file.name.endsWith(".xml")) {
                    val name = file.name.removeSuffix(".xml")
                    try {
                        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
                    } catch (t: Throwable) {
                    }
                    try {
                        file.delete()
                    } catch (t: Throwable) {
                    }
                }
            }
        } catch (t: Throwable) {
        }
    }

    private fun wipeWebViewData() {
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
        } catch (t: Throwable) {
        }
        try {
            WebStorage.getInstance().deleteAllData()
        } catch (t: Throwable) {
        }
    }

    private fun wipeCaches(context: Context) {
        deleteChildren(context.cacheDir)
        deleteChildren(context.codeCacheDir)
        try {
            deleteChildren(context.externalCacheDir)
        } catch (t: Throwable) {
        }
    }

    private fun deleteChildren(dir: File?) {
        try {
            val children = dir?.listFiles() ?: return
            for (child in children) {
                try {
                    child.deleteRecursively()
                } catch (t: Throwable) {
                }
            }
        } catch (t: Throwable) {
        }
    }
}
