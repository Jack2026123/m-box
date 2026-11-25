package com.github.tvbox.osc.ui.activity

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.io.handleLocalConfigResult
import com.github.tvbox.osc.io.handleLocalSourceTreeResult
import com.github.tvbox.osc.io.startLocalConfig
import com.github.tvbox.osc.ui.components.SheetHostScaffold
import com.github.tvbox.osc.ui.components.WallpaperBackdropPage
import com.github.tvbox.osc.ui.page.ConfigManageScreen
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.PermissionHelper
import com.github.tvbox.osc.util.SpiderStorageNotice
import java.util.zip.ZipInputStream

class ConfigManageActivity : BaseActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ConfigManageActivity::class.java))
        }
    }

    private val localConfigLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                handleLocalConfigResult(this, uri) { needTree -> if (needTree) settleUnreachableSource(uri) }
            }
        }

    private val sourceTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            handleLocalSourceTreeResult(this, uri)
        }

    private var pendingReadingImport: ((String, String) -> Unit)? = null

    private var pendingMusicImport: ((String, String) -> Unit)? = null

    /** 批量导入回调：一次拿回多个文件 */
    private var pendingMusicImportMany: ((List<Pair<String, String>>) -> Unit)? = null

    private val musicImportManyLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val callback = pendingMusicImportMany
            pendingMusicImportMany = null
            if (uris.isNullOrEmpty() || callback == null) return@registerForActivityResult
            Thread {
                val items = ArrayList<Pair<String, String>>()
                val failures = ArrayList<String>()
                for (uri in uris) {
                    runCatching {
                        val name = queryDisplayName(uri)
                            ?: uri.lastPathSegment?.substringAfterLast('/')
                            ?: "music"
                        val bytes = contentResolver.openInputStream(uri)?.use { input ->
                            input.readBytes()
                        } ?: throw IllegalStateException("empty file")
                        val extracted = extractMusicItems(name, bytes)
                        if (extracted.isEmpty()) throw IllegalStateException("zip 里没有 .js：" + name)
                        items.addAll(extracted)
                    }.onFailure { th ->
                        LOG.e("ConfigManage-music-import-many", th)
                        failures.add(th.message ?: th.javaClass.simpleName)
                    }
                }
                runOnUiThread {
                    if (items.isEmpty()) {
                        Toast.makeText(
                            this,
                            "一个文件都没读成功" + if (failures.isNotEmpty()) "：" + failures.first() else "",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        callback(items)
                    }
                }
            }.start()
        }

    /**
     * 批量导入音源脚本：一次选多个 .js。
     *
     * 原来的 launchMusicImport 只能单选，19 个脚本要点 19 次；
     * 这个方法让用户一次框选全部。
     */
    fun launchMusicImportMany(onResult: (List<Pair<String, String>>) -> Unit) {
        pendingMusicImportMany = onResult
        try {
            musicImportManyLauncher.launch(
                arrayOf(
                    "application/javascript",
                    "application/zip",
                    "text/*",
                    "application/octet-stream",
                    "*/*",
                ),
            )
        } catch (th: Throwable) {
            pendingMusicImportMany = null
            LOG.e("ConfigManage-music-import-many", th)
            Toast.makeText(
                this,
                getString(
                    R.string.config_pick_local_launch_failed,
                    th.message ?: th.javaClass.simpleName,
                ),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private val musicImportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val callback = pendingMusicImport
            pendingMusicImport = null
            if (uri == null || callback == null) return@registerForActivityResult
            Thread {
                val result = runCatching {
                    val name = queryDisplayName(uri)
                        ?: uri.lastPathSegment?.substringAfterLast('/')
                        ?: "music"
                    val bytes = contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes()
                    } ?: throw IllegalStateException("empty file")
                    val extracted = extractMusicItems(name, bytes)
                    if (extracted.isEmpty()) throw IllegalStateException("zip 里没有 .js：" + name)
                    extracted
                }
                runOnUiThread {
                    result.fold(
                        onSuccess = { extracted -> extracted.forEach { (name, text) -> callback(name, text) } },
                        onFailure = { th ->
                            LOG.e("ConfigManage-music-import", th)
                            Toast.makeText(
                                this,
                                getString(
                                    R.string.reading_fetch_failed,
                                    th.message ?: th.javaClass.simpleName,
                                ),
                                Toast.LENGTH_LONG,
                            ).show()
                        },
                    )
                }
            }.start()
        }

    private val readingImportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val callback = pendingReadingImport
            pendingReadingImport = null
            if (uri == null || callback == null) return@registerForActivityResult
            Thread {
                val result = runCatching {
                    val name = queryDisplayName(uri)
                        ?: uri.lastPathSegment?.substringAfterLast('/')
                        ?: "reading"
                    val text = contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    } ?: throw IllegalStateException("empty file")
                    name to text
                }
                runOnUiThread {
                    result.fold(
                        onSuccess = { (name, text) -> callback(name, text) },
                        onFailure = { th ->
                            LOG.e("ConfigManage", th)
                            Toast.makeText(
                                this,
                                getString(
                                    R.string.reading_fetch_failed,
                                    th.message ?: th.javaClass.simpleName,
                                ),
                                Toast.LENGTH_LONG,
                            ).show()
                        },
                    )
                }
            }.start()
        }

    private fun queryDisplayName(uri: Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }

    /**
     * 音乐源文件解包：.zip 按文件名后缀或 PK magic（0x50 0x4B）识别，ZipInputStream 逐条目读 UTF-8 文本，
     * 只取 .js 条目；条目名带目录前缀的取最后一段。非 zip 原样当单个 .js 文本返回。
     * 与 AiSkillPack.extractText 同一套 zip 判定（后缀或 PK magic + ZipInputStream 逐条目读文本）。
     */
    private fun extractMusicItems(fileName: String, bytes: ByteArray): List<Pair<String, String>> {
        val isZip = fileName.endsWith(".zip", ignoreCase = true) ||
            (bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte())
        if (!isZip) return listOf(fileName to bytes.toString(Charsets.UTF_8))
        val out = ArrayList<Pair<String, String>>()
        ZipInputStream(bytes.inputStream()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val entryName = entry.name.substringAfterLast('/')
                    val text = zin.readBytes().toString(Charsets.UTF_8)
                    if (entryName.lowercase().endsWith(".js") && text.isNotBlank()) {
                        out.add(entryName to text)
                    }
                }
                entry = zin.nextEntry
            }
        }
        return out
    }

    fun launchMusicImport(onResult: (name: String, text: String) -> Unit) {
        pendingMusicImport = onResult
        try {
            // .js 的 MIME 在部分机型上报 text/plain，有的报 application/javascript，
            // 还有的干脆 octet-stream，所以不限定类型，只靠后缀和内容校验；
            // zip 也放行，回调里按 extractMusicItems 解包，包内多脚本逐个回调。
            musicImportLauncher.launch(arrayOf("application/javascript", "application/zip", "text/*", "application/octet-stream", "*/*"))
        } catch (th: Throwable) {
            pendingMusicImport = null
            LOG.e("ConfigManage-music-import", th)
            Toast.makeText(
                this,
                getString(
                    R.string.config_pick_local_launch_failed,
                    th.message ?: th.javaClass.simpleName,
                ),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun launchReadingImport(onResult: (name: String, text: String) -> Unit) {
        pendingReadingImport = onResult
        try {
            readingImportLauncher.launch(arrayOf("application/json", "text/plain", "text/*"))
        } catch (th: Throwable) {
            pendingReadingImport = null
            LOG.e("ConfigManage", th)
            Toast.makeText(
                this,
                getString(
                    R.string.config_pick_local_launch_failed,
                    th.message ?: th.javaClass.simpleName,
                ),
                Toast.LENGTH_LONG,
            ).show()
        }
    }


    fun launchLocalConfig(onResult: (api: String) -> Unit) {
        // 与设置页备份同款时机：点选本地文件前先查「所有文件访问」授权，
        // 未授权先跳系统授权页，授权返回后自动继续打开文件选择器；
        // 被拒绝时明确提示，不静默结束。
        if (!PermissionHelper.isStorageGranted(this)) {
            SpiderStorageNotice.suppress()
            PermissionHelper.requestStorage(this) { _, _ ->
                if (PermissionHelper.isStorageGranted(this)) {
                    launchLocalConfigPicker(onResult)
                } else {
                    Toast.makeText(
                        this,
                        R.string.config_pick_local_permission_denied,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            return
        }
        launchLocalConfigPicker(onResult)
    }

    private fun launchLocalConfigPicker(onResult: (api: String) -> Unit) {
        try {
            startLocalConfig(localConfigLauncher) { api -> onResult(api) }
        } catch (th: Throwable) {
            LOG.e("ConfigManage", th)
            Toast.makeText(
                this,
                getString(
                    R.string.config_pick_local_launch_failed,
                    th.message ?: th.javaClass.simpleName,
                ),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun launchSourceTree() {
        SpiderStorageNotice.suppress()
        sourceTreeLauncher.launch(null)
    }

    private fun settleUnreachableSource(uri: Uri) {
        if (PermissionHelper.isStorageGranted(this)) {
            launchSourceTree()
            return
        }
        PermissionHelper.requestStorage(this) { granted, _ ->
            if (granted.isNullOrEmpty()) {
                launchSourceTree()
            } else {
                handleLocalConfigResult(this, uri) { needTree -> if (needTree) launchSourceTree() }
            }
        }
    }

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                SheetHostScaffold {
                    WallpaperBackdropPage {
                        ConfigManageScreen(onNavigateBack = { finish() })
                    }
                }
            }
        }
    }
}
