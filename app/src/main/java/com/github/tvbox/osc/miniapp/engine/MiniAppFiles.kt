package com.github.tvbox.osc.miniapp.engine

import android.content.Context
import java.io.File

/**
 * 海阔 hiker://files 映射到 App 私有目录：
 * hiker://files/rules/xxx.js → context.filesDir/hiker_files/rules/xxx.js
 */
object MiniAppFiles {

    private const val PREFIX = "hiker://files"

    fun isHikerFilesUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.trim()
        return u.startsWith(PREFIX) || u.startsWith("file://hiker/files")
    }

    fun rootDir(context: Context): File {
        val dir = File(context.filesDir, "hiker_files")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 将 hiker://files/... 转为本地 File；非法路径返回 null */
    fun resolve(context: Context, pathOrUrl: String?): File? {
        if (pathOrUrl.isNullOrBlank()) return null
        var s = pathOrUrl.trim()
        when {
            s.startsWith(PREFIX) -> s = s.removePrefix(PREFIX)
            s.startsWith("file://hiker/files") -> s = s.removePrefix("file://hiker/files")
        }
        s = s.trimStart('/')
        // 防止路径穿越
        if (s.contains("..")) return null
        val root = rootDir(context)
        if (s.isEmpty()) return root
        val f = File(root, s)
        val rootPath = root.canonicalPath
        val filePath = try {
            f.canonicalPath
        } catch (_: Throwable) {
            return null
        }
        if (!filePath.startsWith(rootPath)) return null
        return f
    }

    fun exists(context: Context, pathOrUrl: String?): Boolean {
        val f = resolve(context, pathOrUrl) ?: return false
        return f.exists() && f.isFile
    }

    fun readText(context: Context, pathOrUrl: String?): String {
        val f = resolve(context, pathOrUrl) ?: return ""
        if (!f.exists() || !f.isFile) return ""
        return try {
            f.readText(Charsets.UTF_8)
        } catch (_: Throwable) {
            ""
        }
    }

    fun writeText(context: Context, pathOrUrl: String?, content: String?): Boolean {
        val f = resolve(context, pathOrUrl) ?: return false
        return try {
            f.parentFile?.mkdirs()
            f.writeText(content ?: "", Charsets.UTF_8)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun absolutePath(context: Context, pathOrUrl: String?): String {
        val f = resolve(context, pathOrUrl) ?: return pathOrUrl ?: ""
        return f.absolutePath
    }
}
