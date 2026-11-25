package com.github.tvbox.osc.util.thunder

import android.content.Context
import android.net.Uri
import android.text.TextUtils

import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.io.FileUtils
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.LanguageManager
import com.github.tvbox.osc.util.RegexUtils
import com.xunlei.downloadlib.XLDownloadManager
import com.xunlei.downloadlib.XLTaskHelper
import com.xunlei.downloadlib.parameter.GetTaskId
import com.xunlei.downloadlib.parameter.TorrentFileInfo
import com.xunlei.downloadlib.parameter.XLTaskInfo

import com.github.tvbox.osc.util.AppContextHolder
import java.io.File
import java.util.ArrayList
import java.util.HashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object Thunder {

    private fun str(resId: Int, vararg args: Any?): String {
        val app = AppContextHolder.context()
        return if (app == null) "" else LanguageManager.localized(app).getString(resId, *args)
    }

    private var cacheRoot = ""
    private var localPath = ""
    private var name = ""
    private var task_url = ""
    private var currentTask = 0L
    private var currentTaskId: GetTaskId? = null
    private var torrentFileInfoArrayList: ArrayList<TorrentFileInfo>? = null
    private var threadPool: ExecutorService? = null

    private fun init(context: Context) {
        XLTaskHelper.get()
        cacheRoot = context.cacheDir.absolutePath + File.separator + "thunder"
    }

    private fun parseThunderUrl(url: String): String {
        XLTaskHelper.get()
        return XLDownloadManager().parserThunderUrl(url)
    }

    private fun fileNameOf(url: String): String {
        if (url.lowercase().startsWith("ed2k:")) {
            val parts = url.split("|")
            if (parts.size > 2) return parts[2]
        }
        return runCatching { XLDownloadManager().getFileNameFromUrl(url) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: Uri.parse(url).lastPathSegment.orEmpty()
    }

    @JvmStatic
    fun stop(bool: Boolean) {
        currentTaskId?.let { XLTaskHelper.get().stopTask(it) }
        currentTaskId = null
        currentTask = 0L
        if (bool) {
            torrentFileInfoArrayList = null
            val cache = File(if (task_url.isEmpty()) cacheRoot else localPath)
            recursiveDelete(cache)
            if (!cache.exists())
                cache.mkdirs()
            if (threadPool != null) {
                try {
                    threadPool?.shutdownNow()
                    threadPool = null
                } catch (th: Throwable) {
                    LOG.e("Thunder", "thread pool shutdown failed", th)
                }
            }
        }
    }

    interface ThunderCallback {

        fun status(code: Int, info: String)

        fun list(urlMap: MutableMap<Int, String>)

        fun play(url: String)
    }

    private var playList: ArrayList<String>? = null
    private var ed2kList: ArrayList<String>? = null
    @JvmStatic
    fun parse(context: Context, urlBean: Movie.Video.UrlBean?, callback: ThunderCallback) {
        init(context)
        stop(true)
        threadPool = Executors.newSingleThreadExecutor()
        torrentFileInfoArrayList = ArrayList()
        playList = ArrayList()
        ed2kList = ArrayList()
        val urlMap = HashMap<Int, String>()
        threadPool?.execute(ParseTask(urlBean, urlMap, callback))
    }

    private class ParseTask(
        private val urlBean: Movie.Video.UrlBean?,
        private val urlMap: MutableMap<Int, String>,
        private val callback: ThunderCallback
    ) : Runnable {

        override fun run() {
            val infoList = urlBean?.infoList ?: return
            val playList = requireNotNull(Thunder.playList) { "thunder play list missing" }
            for (idx in 0 until infoList.size) {
                val urlInfo: Movie.Video.UrlBean.UrlInfo? = infoList[idx]
                if (urlInfo != null) {
                    var url = ""
                    for (infoBean in urlInfo.beanList.orEmpty()) {
                        var isParse = false
                        url = infoBean.url.orEmpty()
                        if (Thunder.isMagnet(url) || Thunder.isThunder(url) || Thunder.isTorrent(url)) {
                            if (Thunder.isThunder(url)) url = parseThunderUrl(url)
                            try {
                                currentTaskId?.let { XLTaskHelper.get().stopTask(it) }
                                currentTaskId = null
                                currentTask = 0L
                                val taskId = if (Thunder.isMagnet(url))
                                    XLTaskHelper.get().parse(url, File(Thunder.cacheRoot))
                                else
                                    XLTaskHelper.get().addThunderTask(url, File(Thunder.cacheRoot))
                                currentTaskId = taskId
                                currentTask = taskId.taskId
                            } catch (exception: Exception) {
                                LOG.e("Thunder", exception)
                                currentTaskId = null
                                currentTask = 0L
                            }
                            if (Thunder.currentTask <= 0 || currentTaskId == null) {
                                continue
                            }
                            var count = 30
                            outerLoop@ while (true) {
                                count--
                                if (count <= 0) {
                                    break
                                }
                                val taskInfo = currentTaskId?.let { XLTaskHelper.get().getTaskInfo(it) }
                                if (taskInfo != null) {
                                    when (taskInfo.mTaskStatus) {
                                        2 -> {
                                            try {
                                                val torrentFile = currentTaskId?.saveFile
                                                val torrentInfo = if (torrentFile != null) XLTaskHelper.get().getTorrentInfo(torrentFile) else null
                                                if (torrentInfo == null || TextUtils.isEmpty(torrentInfo.mInfoHash)) {

                                                } else {
                                                    val mSubFileInfo = torrentInfo.mSubFileInfo
                                                    if (mSubFileInfo != null) {
                                                        for (sub in mSubFileInfo) {
                                                            if (Thunder.isMedia(sub.mFileName) && sub.mFileSize > 1048576L * 30) {
                                                                sub.mFile = torrentFile
                                                                playList.add(sub.mFileName + "\$tvbox-torrent:" + (Thunder.torrentFileInfoArrayList?.size ?: 0))
                                                                Thunder.torrentFileInfoArrayList?.add(sub)
                                                            }
                                                        }
                                                        isParse = true
                                                        break@outerLoop
                                                    }
                                                }
                                            } catch (throwable: Throwable) {
                                                LOG.e("Thunder", throwable)
                                            }
                                            break@outerLoop
                                        }
                                        3 -> {
                                            break@outerLoop
                                        }
                                        else -> {
                                        }
                                    }
                                }
                                try {
                                    Thread.sleep(100)
                                } catch (e: InterruptedException) {
                                    LOG.e("Thunder", e)
                                }
                            }
                        } else {
                            url = infoBean.url.orEmpty()
                            if (Thunder.isThunder(url)) url = parseThunderUrl(url)
                            if (Thunder.isNetworkDownloadTask(url)) {
                                Thunder.task_url = url
                                if (TextUtils.isEmpty(Thunder.task_url)) {
                                    continue
                                }
                                Thunder.name = fileNameOf(Thunder.task_url)
                                playList.add(Thunder.name + "\$tvbox-oth:" + (Thunder.ed2kList?.size ?: 0))
                                Thunder.ed2kList?.add(Thunder.task_url)
                                isParse = true
                            }
                        }
                        if (!isParse) playList.add(infoBean.name + "\$" + infoBean.url)
                    }
                    if (playList.isNotEmpty()) {
                        urlMap.put(idx, TextUtils.join("#", playList))
                        playList.clear()
                    }
                }
            }

            if (urlMap.size > 0) {
                callback.list(urlMap)
            } else {
                callback.status(-1, Thunder.str(R.string.thunder_error_parse))
            }
        }
    }

    @JvmStatic
    fun play(url: String, callback: ThunderCallback): Boolean {
        if (url.startsWith("tvbox-torrent:")) {
            val idx = Integer.parseInt(url.substring(14))
            val info = requireNotNull(torrentFileInfoArrayList?.getOrNull(idx)) { "thunder torrent info missing" }
            currentTaskId?.let { XLTaskHelper.get().stopTask(it) }
            currentTaskId = null
            currentTask = 0L
            threadPool?.execute(object : Runnable {
                override fun run() {
                    val torrentFile = info.mFile
                    if (torrentFile == null) {
                        callback.status(-1, str(R.string.thunder_error_download))
                        return
                    }
                    val torrentName = torrentFile.name
                    val cache = cacheRoot + File.separator + torrentName.substring(0, torrentName.lastIndexOf("."))
                    val taskId = XLTaskHelper.get().addTorrentTask(torrentFile, File(cache), info.mFileIndex)
                    currentTaskId = taskId
                    currentTask = taskId.taskId
                    if (currentTask < 0)
                        callback.status(-1, str(R.string.thunder_error_download))
                    var count = 30
                    while (true) {
                        count--
                        if (count <= 0) {
                            callback.status(-1, str(R.string.thunder_error_timeout))
                            break
                        }
                        val taskInfo = currentTaskId?.let { XLTaskHelper.get().getBtSubTaskInfo(it, info.mFileIndex).mTaskInfo }
                        if (taskInfo != null) {
                            when (taskInfo.mTaskStatus) {
                                3 -> {
                                    callback.status(-1, errorInfo(taskInfo.mErrorCode))
                                    return
                                }
                                1, 4, 2 -> {
                                    val pUrl = XLTaskHelper.get().getLocalUrl(File(cache + File.separator + info.mFileName))
                                    callback.play(pUrl)
                                    return
                                }
                            }
                        }
                        try {
                            Thread.sleep(1000)
                        } catch (e: InterruptedException) {
                            LOG.e("Thunder", e)
                        }
                    }
                }
            })
            return true
        }
        if (url.startsWith("tvbox-oth:")) {
            stop(false)
            val idx = Integer.parseInt(url.substring(10))
            task_url = requireNotNull(ed2kList?.getOrNull(idx)) { "thunder ed2k url missing" }
            name = fileNameOf(task_url)
            localPath = (File(cacheRoot + File.separator + "temp", FileUtils.getFileNameWithoutExt(name))).toString() + "/"
            val taskId = XLTaskHelper.get().addThunderTask(task_url, File(localPath))
            currentTaskId = taskId
            currentTask = taskId.taskId

            threadPool?.execute(object : Runnable {
                override fun run() {

                    var count = 20
                    while (true) {
                        count--
                        if (count <= 0) {
                            callback.status(-1, str(R.string.thunder_error_timeout))
                            break
                        }
                        val playUrl = getPlayUrl()
                        if (playUrl != null && !playUrl.isEmpty()) {
                            callback.play(playUrl)
                            return
                        }
                        try {
                            Thread.sleep(1000)
                        } catch (e: InterruptedException) {
                            LOG.e("Thunder", e)
                        }
                    }
                }
            })
            return true
        }
        if (isEd2k(url) || isFtp(url)) {
            if (threadPool == null) {
                init(requireNotNull(AppContextHolder.context()) { "app context not initialized" })
                threadPool = Executors.newSingleThreadExecutor()
            }
            currentTaskId?.let { XLTaskHelper.get().stopTask(it) }
            currentTaskId = null
            currentTask = 0L
            task_url = url
            name = fileNameOf(task_url)
            localPath = (File(cacheRoot + File.separator + "temp", FileUtils.getFileNameWithoutExt(name))).toString() + "/"
            val taskId = XLTaskHelper.get().addThunderTask(task_url, File(localPath))
            currentTaskId = taskId
            currentTask = taskId.taskId

            threadPool?.execute(object : Runnable {
                override fun run() {

                    var count = 20
                    while (true) {
                        count--
                        if (count <= 0) {
                            callback.status(-1, str(R.string.thunder_error_timeout))
                            break
                        }
                        val playUrl = getPlayUrl()
                        if (!playUrl.isNullOrEmpty()) {
                            callback.play(playUrl)
                            return
                        }
                        try {
                            Thread.sleep(1000)
                        } catch (e: InterruptedException) {
                            LOG.e("Thunder", e)
                        }
                    }
                }
            })
            return true
        }
        return false
    }

    private fun errorInfo(code: Int): String {
        return when (code) {
            9125 ->
                str(R.string.thunder_error_name_too_long)
            111120 ->
                str(R.string.thunder_error_path_too_long)
            111142 ->
                str(R.string.thunder_error_file_too_small)
            111085 ->
                str(R.string.thunder_error_no_space)
            111171 ->
                str(R.string.thunder_error_network_refused)
            9301 ->
                str(R.string.thunder_error_buffer)
            114001, 114004, 114005, 114006, 114007, 114011, 9304, 111154 ->
                str(R.string.thunder_error_copyright)
            114101 ->
                str(R.string.thunder_error_invalid_url)
            else ->
                "ErrorCode=" + code
        }
    }

    @JvmStatic
    fun isSupportUrl(url: String?): Boolean {
        val u = url ?: return false
        return isMagnet(u) || isThunder(u) || isTorrent(u)
    }

    private fun isMagnet(url: String): Boolean {
        return url.lowercase().startsWith("magnet:")
    }

    private fun isThunder(url: String): Boolean {
        return url.lowercase().startsWith("thunder")
    }

    private fun isTorrent(url: String): Boolean {
        return RegexUtils.getPattern(";").split(url.lowercase())[0].endsWith(".torrent")
    }

    private fun isEd2k(url: String): Boolean {
        return url.lowercase().startsWith("ed2k:")
    }

    @JvmStatic
    fun isFtp(url: String): Boolean {
        return url.lowercase().startsWith("ftp://")
    }

    private fun recursiveDelete(file: File) {
        if (!file.exists())
            return
        if (file.isDirectory) {
            for (f in file.listFiles()) {
                recursiveDelete(f)
            }
        }
        file.delete()
    }

    private val formats: ArrayList<String> = ArrayList()

    private fun isMedia(name: String): Boolean {
        if (formats.size == 0) {
            formats.add(".rmvb")
            formats.add(".avi")
            formats.add(".mkv")
            formats.add(".flv")
            formats.add(".mp4")
            formats.add(".rm")
            formats.add(".vob")
            formats.add(".wmv")
            formats.add(".mov")
            formats.add(".3gp")
            formats.add(".asf")
            formats.add("mpg")
            formats.add("mpeg")
            formats.add("mpe")
        }
        for (f in formats) {
            if (name.lowercase().endsWith(f))
                return true

        }
        return false
    }

    @JvmStatic
    fun isNetworkDownloadTask(url: String): Boolean {
        if (TextUtils.isEmpty(url)) return false
        if (isFtp(url) || isEd2k(url)) {
            return true
        } else {
            return false
        }
    }

    @JvmStatic
    fun stopTask() {
        currentTaskId?.let { XLTaskHelper.get().deleteTask(it) }
        currentTaskId = null
        currentTask = 0L
    }
    @JvmStatic
    fun getTaskInfo(): XLTaskInfo {
        return currentTaskId?.let { XLTaskHelper.get().getTaskInfo(it) } ?: XLTaskInfo()
    }

    @JvmStatic
    fun getPlayUrl(): String? {
        if (currentTask != 0L && currentTaskId != null) {
            if (isNetworkDownloadTask(task_url)) {
                return XLTaskHelper.get().getLocalUrl(File(localPath + name))
            }
        }
        return null
    }

}
