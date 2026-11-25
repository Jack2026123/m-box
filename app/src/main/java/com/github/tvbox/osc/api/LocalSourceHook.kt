package com.github.tvbox.osc.api

import android.os.Environment
import com.github.catvod.utils.Prefers
import com.github.tvbox.osc.io.toClanApi
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.google.gson.JsonObject

object LocalSourceHook {

    @JvmStatic
    fun inject(configUrl: String, obj: JsonObject) {
        try {
            if (!Prefers.getBoolean("local_source_enabled", false)) return
            val settings = LocalSource.loadSettings()
            if (LocalSource.enabledDirs(settings).isEmpty()) return
            if (isUserOwnedLocalConfig(configUrl)) return
            val generated = LocalSource.buildCached(Prefers.getBoolean("local_source_auto", true)) ?: return
            LocalSource.expandConfigPaths(obj, configUrl)
            LocalSource.pruneForeignLocal(obj, configUrl)
            // 2.01 起彻底分开：不再把本地生成的站追加进在线接口，
            // 每个接口只看自己的站（a 接口不出现 b 订阅接口的东西）。
            LocalSource.writeBack(generated, false)
        } catch (ignored: Throwable) {
        }
    }

    // 扫描后自动建本地源订阅：订阅点播里保证有一条指向生成文件的条目，
    // 条目名取生成文件名（去 .json 后缀，默认即 自动本地源；自定义文件名同样生效）；
    // 已存在同 URL 的条目时不重复建、不改用户改过的名字。
    @JvmStatic
    fun ensureLocalSubscription(generatedPath: String?) {
        try {
            if (generatedPath.isNullOrEmpty()) return
            val storageRoot = Environment.getExternalStorageDirectory().absolutePath
            val url = toClanApi(generatedPath, storageRoot) ?: return
            val list = ArrayList(KV.get(HawkConfig.SUBSCRIBE_LIST, ArrayList<String>()))
            val exists = list.any { it.substringAfter("\t", "") == url }
            if (!exists) {
                var entryName = java.io.File(generatedPath).name
                if (entryName.endsWith(".json")) entryName = entryName.dropLast(5)
                if (entryName.isEmpty()) entryName = "自动本地源"
                list.add("$entryName\t$url")
                KV.put(HawkConfig.SUBSCRIBE_LIST, list)
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun isUserOwnedLocalConfig(configUrl: String?): Boolean {
        val url = configUrl?.trim().orEmpty()
        if (url.isEmpty()) return false
        val path = LocalSource.configFilePath(url)
        if (path.isEmpty()) return false
        val generated = LocalSource.outputPath(LocalSource.loadSettings())
        if (generated.isEmpty()) return true
        return LocalSource.normalizePath(path) != LocalSource.normalizePath(generated)
    }
}
