package com.github.tvbox.osc.ui.page

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.github.tvbox.osc.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import java.util.concurrent.atomic.AtomicInteger

sealed interface SmartDownloadState {
    val id: Int
    data class Downloading(override val id: Int, val fileName: String, val downloaded: Long, val total: Long, val speedBps: Long) : SmartDownloadState
    data class Paused(override val id: Int, val fileName: String, val downloaded: Long, val total: Long) : SmartDownloadState
    data class Done(override val id: Int, val fileName: String) : SmartDownloadState
    data class Failed(override val id: Int, val fileName: String, val error: String) : SmartDownloadState
}

private class SmartDownloadTask(
    val id: Int,
    val url: String,
    val fileName: String,
    val treeUri: Uri?,
    var target: ResumeTarget?,
    var downloaded: Long,
    var total: Long,
    var job: Job? = null,
    /** r479：多线程分段下载时有多个并发连接，暂停/取消要全部取消。 */
    val calls: MutableList<Call> = mutableListOf(),
    var pauseRequested: Boolean = false,
    var cancelRequested: Boolean = false,
    var notifierId: Int = 0,
    var lastProgressAt: Long = 0L,
    var lastProgressBytes: Long = 0L,
)

object SmartDownloadManager {

    const val MAX_TASKS = 5

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<List<SmartDownloadState>>(emptyList())
    val state: StateFlow<List<SmartDownloadState>> = _state.asStateFlow()

    private val tasks = LinkedHashMap<Int, SmartDownloadTask>()
    private val nextId = AtomicInteger(1)
    private val nextNotifierId = AtomicInteger(2000)

    fun activeCount(): Int {
        return _state.value.count { it is SmartDownloadState.Downloading || it is SmartDownloadState.Paused }
    }

    private fun updateSnapshot(s: SmartDownloadState) {
        val cur = _state.value
        _state.value = if (cur.any { it.id == s.id }) {
            cur.map { if (it.id == s.id) s else it }
        } else {
            cur + s
        }
    }

    private fun removeSnapshot(id: Int) {
        _state.value = _state.value.filterNot { it.id == id }
    }

    fun start(context: Context, url: String, fileName: String, treeUri: Uri?) {
        val appContext = context.applicationContext
        if (activeCount() >= MAX_TASKS) {
            Toast.makeText(appContext, appContext.getString(R.string.settings_smart_download_limit), Toast.LENGTH_LONG).show()
            return
        }
        val id = nextId.getAndIncrement()
        val t = SmartDownloadTask(id, url, fileName, treeUri, null, 0, -1)
        t.notifierId = nextNotifierId.getAndIncrement()
        tasks[id] = t
        _state.value = _state.value + SmartDownloadState.Downloading(id, fileName, 0, -1, 0)
        launchDownload(appContext, t, 0, null)
    }

    fun pause(id: Int) {
        val t = tasks[id] ?: return
        if (_state.value.any { it.id == id && it is SmartDownloadState.Downloading }) {
            t.pauseRequested = true
            t.calls.forEach { it.cancel() }
        }
    }

    fun resume(context: Context, id: Int) {
        val t = tasks[id] ?: return
        if (_state.value.none { it.id == id && it is SmartDownloadState.Paused }) return
        t.pauseRequested = false
        t.cancelRequested = false
        t.lastProgressAt = 0L
        t.lastProgressBytes = 0L
        launchDownload(context.applicationContext, t, t.downloaded, t.target)
    }

    fun cancel(context: Context, id: Int) {
        val appContext = context.applicationContext
        val t = tasks[id] ?: return
        when (val s = _state.value.firstOrNull { it.id == id }) {
            is SmartDownloadState.Downloading -> {
                t.cancelRequested = true
                t.calls.forEach { it.cancel() }
            }
            is SmartDownloadState.Paused -> {
                t.target?.let { SmartDownload.deleteTarget(appContext, it) }
                tasks.remove(id)
                removeSnapshot(id)
                SmartDownloadNotifier(appContext).cancel(t.notifierId)
            }
            is SmartDownloadState.Done, is SmartDownloadState.Failed -> {
                tasks.remove(id)
                removeSnapshot(id)
            }
            null -> {
                tasks.remove(id)
            }
        }
    }

    fun clear(id: Int) {
        tasks.remove(id)
        removeSnapshot(id)
    }

    private fun launchDownload(appContext: Context, t: SmartDownloadTask, resumeFrom: Long, resumeTarget: ResumeTarget?) {
        val notifier = SmartDownloadNotifier(appContext)
        val nid = t.notifierId
        t.job = scope.launch {
            notifier.start(nid, t.fileName)
            val result = SmartDownload.download(
                appContext,
                t.url,
                t.fileName,
                t.treeUri,
                resumeFrom = resumeFrom,
                resumeTarget = resumeTarget,
                onTargetCreated = { created -> t.target = created },
                onCallCreated = { call -> t.calls.add(call) },
                isPauseRequested = { t.pauseRequested },
            ) { downloaded, total ->
                t.downloaded = downloaded
                t.total = total
                val now = System.currentTimeMillis()
                var speed = 0L
                // r480：多线程 4 路并发同时回调进度，lastProgressAt 会被别的线程改写；
                // 先把时间差快照到局部变量再除，否则分母可能在判断通过后被改成 0（divide by zero）。
                val lastAt = t.lastProgressAt
                val dt = now - lastAt
                if (lastAt > 0 && dt > 0) {
                    speed = (downloaded - t.lastProgressBytes) * 1000 / dt
                }
                t.lastProgressAt = now
                t.lastProgressBytes = downloaded
                updateSnapshot(SmartDownloadState.Downloading(t.id, t.fileName, downloaded, total, speed))
                notifier.progress(nid, t.id, t.fileName, downloaded, total, speed)
            }
            t.calls.clear()
            withContext(Dispatchers.Main) {
                result.onSuccess { outcome ->
                    updateSnapshot(SmartDownloadState.Done(t.id, outcome.fileName))
                    notifier.done(nid, outcome.fileName, true, null)
                    Toast.makeText(
                        appContext,
                        appContext.getString(R.string.settings_smart_download_done, outcome.fileName),
                        Toast.LENGTH_LONG,
                    ).show()
                }.onFailure { error ->
                    when {
                        error is SmartDownloadPaused -> {
                            updateSnapshot(SmartDownloadState.Paused(t.id, t.fileName, t.downloaded, t.total))
                            notifier.paused(nid, t.id, t.fileName, t.downloaded, t.total)
                        }
                        t.cancelRequested -> {
                            tasks.remove(t.id)
                            removeSnapshot(t.id)
                            notifier.cancel(nid)
                        }
                        else -> {
                            updateSnapshot(SmartDownloadState.Failed(t.id, t.fileName, error.message ?: ""))
                            notifier.done(nid, t.fileName, false, error.message)
                            Toast.makeText(
                                appContext,
                                appContext.getString(R.string.settings_smart_download_failed, error.message ?: ""),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
        }
    }
}

class SmartDownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(SmartDownloadNotifier.EXTRA_TASK_ID, -1)
        if (id < 0) return
        when (intent.action) {
            SmartDownloadNotifier.ACTION_PAUSE -> SmartDownloadManager.pause(id)
            SmartDownloadNotifier.ACTION_RESUME -> SmartDownloadManager.resume(context, id)
        }
    }
}
