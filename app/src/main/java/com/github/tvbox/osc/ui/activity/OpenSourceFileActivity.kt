package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.lifecycleScope
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.LocalSource
import com.github.tvbox.osc.api.LocalSourceSingle
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.ui.dialog.LocalSourceDialog
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OpenSourceFileActivity : BaseActivity() {

    private var sourceUri: Uri? = null
    private var sourceName: String = ""
    private var roots by mutableStateOf<List<File>>(emptyList())
    private var fallbackRoots by mutableStateOf<List<File>>(emptyList())
    private var stack by mutableStateOf<List<File>>(emptyList())
    private var busy by mutableStateOf(false)
    private var showNewFolder by mutableStateOf(false)
    private var showRename by mutableStateOf(false)
    private var pendingFile by mutableStateOf<File?>(null)
    private var renameConsumed by mutableStateOf(false)
    private var listTick by mutableStateOf(0)
    private var dirty by mutableStateOf(false)
    private var closing by mutableStateOf(false)
    private var clipboardFile by mutableStateOf<File?>(null)
    private var clipboardCut by mutableStateOf(false)
    private var fileMenuFile by mutableStateOf<File?>(null)
    private var deleteTarget by mutableStateOf<File?>(null)
    private var fileRenameTarget by mutableStateOf<File?>(null)

    override fun init() {
        parseIntent()
        roots = LocalSource.enabledDirs(LocalSource.loadSettings()).mapNotNull { path ->
            val file = File(path)
            if (file.isDirectory) file else null
        }
        if (roots.size == 1) {
            stack = listOf(roots[0])
        } else if (roots.isEmpty()) {
            fallbackRoots = LocalSource.STORAGE_ROOTS.mapNotNull { path ->
                val file = File(path)
                if (file.isDirectory) file else null
            }
        }
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                PickerDialog()
            }
        }
    }

    private fun parseIntent() {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            else -> null
        }
        sourceUri = uri
        sourceName = if (uri == null) "" else queryName(uri)
    }

    private fun queryName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        val name = cursor.getString(index)
                        if (!name.isNullOrBlank()) return sanitize(name)
                    }
                }
            }
        } catch (ignored: Throwable) {
        }
        val last = uri.lastPathSegment?.substringAfterLast("/") ?: ""
        return sanitize(last)
    }

    private fun sanitize(name: String): String {
        return name.replace("/", "_").replace("\\", "_").trim()
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (candidate.exists()) {
            n++
            candidate = File(dir, base + "_" + n + ext)
        }
        return candidate
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024f)
        return String.format("%.1f MB", bytes / (1024f * 1024f))
    }

    private fun toast(msg: String) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    private fun rebuildGenerated() {
        try {
            val generated = LocalSource.buildCached(true)
            LocalSource.writeGenerated(generated)
            LocalSource.cache = null
        } catch (ignored: Throwable) {
        }
    }

    private fun performSave() {
        val uri = sourceUri ?: return
        val dir = stack.lastOrNull() ?: return
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                var errorMsg: String? = null
                val result = withContext(Dispatchers.IO) {
                    try {
                        val target = uniqueFile(dir, sourceName.ifBlank { "source_file" })
                        contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        } ?: throw IllegalStateException(getString(R.string.open_source_read_failed))
                        if (!LocalSourceSingle.isUnderEnabledDir(dir, LocalSource.loadSettings())) {
                            LocalSourceSingle.addScanDir(dir.absolutePath)
                        }
                        target
                    } catch (th: Throwable) {
                        errorMsg = th.message
                        null
                    }
                }
                busy = false
                if (isFinishing || isDestroyed) return@launch
                if (result == null) {
                    toast(getString(R.string.open_source_save_failed, errorMsg ?: ""))
                } else {
                    dirty = true
                    toast(getString(R.string.open_source_saved, result.absolutePath))
                    pendingFile = result
                    renameConsumed = false
                    showRename = true
                }
            } catch (th: Throwable) {
                busy = false
            }
        }
    }

    private fun finishSave(finalFile: File, oldFile: File?) {
        showRename = false
        lifecycleScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        if (oldFile != null) LocalSourceSingle.moveFileEntry(oldFile, finalFile)
                        else LocalSourceSingle.registerFile(finalFile)
                    } catch (th: Throwable) {
                        false
                    }
                }
                if (!ok) withContext(Dispatchers.IO) { rebuildGenerated() }
                dirty = true
                listTick++
                if (!isFinishing && !isDestroyed) {
                    toast(getString(if (ok) R.string.open_source_registered else R.string.open_source_saved, finalFile.absolutePath))
                }
            } catch (th: Throwable) {
            }
        }
    }

    private fun renameAndFinish(input: String) {
        if (renameConsumed) return
        renameConsumed = true
        val saved = pendingFile ?: return
        pendingFile = null
        val dir = saved.parentFile ?: run {
            showRename = false
            return
        }
        var finalName = input.trim()
        if (finalName.isBlank()) finalName = saved.name
        val dot = finalName.lastIndexOf('.')
        val origDot = saved.name.lastIndexOf('.')
        if (dot <= 0 && origDot > 0) {
            finalName = finalName + saved.name.substring(origDot)
        }
        if (finalName == saved.name) {
            finishSave(saved, null)
            return
        }
        val target = uniqueFile(dir, finalName)
        val renamed = try {
            if (saved.renameTo(target)) true else LocalSource.moveFile(saved, target)
        } catch (th: Throwable) {
            false
        }
        if (!renamed || !target.exists()) {
            toast(getString(R.string.open_source_save_failed, ""))
            finishSave(saved, null)
            return
        }
        toast(getString(R.string.open_source_saved, target.absolutePath))
        finishSave(target, saved)
    }

    private fun cancelRename() {
        if (renameConsumed) return
        renameConsumed = true
        val saved = pendingFile
        pendingFile = null
        showRename = false
        if (saved != null) finishSave(saved, null)
    }

    private fun createFolder(name: String) {
        val dir = stack.lastOrNull() ?: return
        val clean = name.trim().replace("/", "_").replace("\\", "_")
        if (clean.isBlank()) return
        val created = File(dir, clean)
        if (created.exists() && created.isDirectory) {
            stack = stack + created
            return
        }
        if (created.mkdir()) {
            toast(getString(R.string.open_source_folder_created, created.absolutePath))
            stack = stack + created
        } else {
            toast(getString(R.string.open_source_folder_create_failed, created.absolutePath))
        }
    }

    private fun closeSave() {
        if (closing) return
        closing = true
        showRename = false
        showNewFolder = false
        fileMenuFile = null
        deleteTarget = null
        fileRenameTarget = null
        LocalSourceDialog.reloadOnly(this) {
            if (!isFinishing && !isDestroyed) finish()
        }
    }

    private fun deleteOp(file: File) {
        lifecycleScope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) {
                    try {
                        val gone = try {
                            file.delete()
                        } catch (th: Throwable) {
                            false
                        }
                        if (gone && !LocalSourceSingle.removeFile(file)) rebuildGenerated()
                        gone
                    } catch (th: Throwable) {
                        false
                    }
                }
                if (deleted) dirty = true
                listTick++
                if (!isFinishing && !isDestroyed) {
                    toast(getString(if (deleted) R.string.open_source_deleted else R.string.open_source_delete_failed, file.name))
                }
            } catch (th: Throwable) {
            }
        }
    }

    private fun renameFileOp(old: File, input: String) {
        val dir = old.parentFile ?: return
        var finalName = input.trim()
        if (finalName.isBlank()) finalName = old.name
        val dot = finalName.lastIndexOf('.')
        val origDot = old.name.lastIndexOf('.')
        if (dot <= 0 && origDot > 0) {
            finalName = finalName + old.name.substring(origDot)
        }
        if (finalName == old.name) return
        lifecycleScope.launch {
            try {
                val target = uniqueFile(dir, finalName)
                val ok = withContext(Dispatchers.IO) {
                    try {
                        val moved = try {
                            old.renameTo(target)
                        } catch (th: Throwable) {
                            false
                        } || LocalSource.moveFile(old, target)
                        if (moved && target.exists()) {
                            if (!LocalSourceSingle.moveFileEntry(old, target)) rebuildGenerated()
                            true
                        } else {
                            false
                        }
                    } catch (th: Throwable) {
                        false
                    }
                }
                if (ok) dirty = true
                listTick++
                if (!isFinishing && !isDestroyed) {
                    toast(getString(if (ok) R.string.open_source_renamed else R.string.open_source_rename_failed, old.name))
                }
            } catch (th: Throwable) {
            }
        }
    }

    private fun pasteOp() {
        val src = clipboardFile ?: return
        val cut = clipboardCut
        val dir = stack.lastOrNull() ?: return
        lifecycleScope.launch {
            try {
                val target = withContext(Dispatchers.IO) {
                    try {
                        val dest = uniqueFile(dir, src.name)
                        if (cut) {
                            val moved = try {
                                src.renameTo(dest)
                            } catch (th: Throwable) {
                                false
                            } || LocalSource.moveFile(src, dest)
                            if (moved && dest.exists()) {
                                if (!LocalSourceSingle.moveFileEntry(src, dest)) rebuildGenerated()
                                dest
                            } else {
                                null
                            }
                        } else {
                            try {
                                src.inputStream().use { input ->
                                    dest.outputStream().use { output -> input.copyTo(output) }
                                }
                            } catch (th: Throwable) {
                                return@withContext null
                            }
                            if (dest.exists()) {
                                if (!LocalSourceSingle.registerFile(dest)) rebuildGenerated()
                                dest
                            } else {
                                null
                            }
                        }
                    } catch (th: Throwable) {
                        null
                    }
                }
                if (target != null) {
                    dirty = true
                    if (cut) clipboardFile = null
                    listTick++
                    if (!isFinishing && !isDestroyed) {
                        toast(getString(R.string.open_source_pasted, target.absolutePath))
                    }
                } else {
                    if (!isFinishing && !isDestroyed) {
                        toast(getString(R.string.open_source_paste_failed, src.name))
                    }
                }
            } catch (th: Throwable) {
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun PickerDialog() {
        val bump = 1.5.sp
        val titleStyle = MaterialTheme.typography.titleMedium.copy(fontSize = (MaterialTheme.typography.titleMedium.fontSize.value + bump.value).sp)
        val bodyStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = (MaterialTheme.typography.bodyMedium.fontSize.value + bump.value).sp)
        val smallStyle = MaterialTheme.typography.bodySmall.copy(fontSize = (MaterialTheme.typography.bodySmall.fontSize.value + bump.value).sp)
        val inputStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = (MaterialTheme.typography.bodyLarge.fontSize.value + bump.value).sp)
        val labelStyle = MaterialTheme.typography.labelLarge.copy(fontSize = (MaterialTheme.typography.labelLarge.fontSize.value + bump.value).sp)
        val topNameStyle = bodyStyle.copy(fontSize = (bodyStyle.fontSize.value + 0.5f).sp)
        val topPathStyle = smallStyle.copy(fontSize = (smallStyle.fontSize.value + 0.5f).sp)
        val hintStyle = smallStyle.copy(fontSize = (smallStyle.fontSize.value - 0.5f).sp)
        val current = stack.lastOrNull()
        val entries = remember(current, listTick) {
            if (current == null) {
                emptyList()
            } else {
                current.listFiles()
                    ?.filter { !it.name.startsWith(".") }
                    ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    ?: emptyList()
            }
        }
        val rootRows = if (current == null) {
            if (roots.isNotEmpty()) roots else fallbackRoots
        } else {
            emptyList()
        }
        Dialog(
            onDismissRequest = { closeSave() },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.92f),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text(
                        stringResource(R.string.open_source_title),
                        style = titleStyle,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        sourceName.ifBlank { stringResource(R.string.open_source_no_file) },
                        style = topNameStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (sourceUri != null) {
                        if (roots.isEmpty() && current == null) {
                            Text(
                                stringResource(R.string.open_source_fallback_note),
                                style = topPathStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (current == null) {
                            Text(
                                stringResource(R.string.open_source_pick_dir),
                                style = topPathStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                current.absolutePath,
                                style = topPathStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        if (current != null) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = {
                                    stack = if (stack.size > 1) stack.dropLast(1) else emptyList()
                                }) {
                                    Text(stringResource(R.string.open_source_up), style = labelStyle)
                                }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { showNewFolder = true }) {
                                    Text(stringResource(R.string.open_source_new_folder), style = labelStyle)
                                }
                            }
                        }
                        val clip = clipboardFile
                        if (clip != null) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    stringResource(if (clipboardCut) R.string.open_source_clipboard_cut else R.string.open_source_clipboard_copy, clip.name),
                                    style = smallStyle,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f),
                                )
                                if (current != null) {
                                    TextButton(onClick = { pasteOp() }) {
                                        Text(stringResource(R.string.open_source_paste), style = labelStyle)
                                    }
                                }
                            }
                        }
                        if (current == null) {
                            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                                items(rootRows) { dir ->
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable { stack = stack + dir }
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                        ) {
                                            Column {
                                                Text(dir.name, style = bodyStyle)
                                                Text(
                                                    stringResource(R.string.open_source_folder_label),
                                                    style = smallStyle,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                                items(entries) { entry ->
                                    if (entry.isDirectory) {
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 3.dp),
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainer,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth()
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .clickable { stack = stack + entry }
                                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                            ) {
                                                Column {
                                                    Text(entry.name, style = bodyStyle)
                                                    Text(
                                                        stringResource(R.string.open_source_folder_label),
                                                        style = smallStyle,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 3.dp),
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainer,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth()
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .combinedClickable(
                                                        onClick = {},
                                                        onLongClick = { fileMenuFile = entry },
                                                    )
                                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                            ) {
                                                Column {
                                                    Text(entry.name, style = bodyStyle)
                                                    val ext = entry.name.substringAfterLast('.', "")
                                                    Text(
                                                        if (ext.isEmpty()) formatSize(entry.length()) else formatSize(entry.length()) + " · " + ext.uppercase(),
                                                        style = smallStyle,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (sourceUri != null && current != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.open_source_file_hint),
                            style = hintStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { closeSave() }) {
                            Text(stringResource(R.string.open_source_close), style = labelStyle)
                        }
                        if (sourceUri != null) {
                            Button(enabled = current != null && !busy, onClick = { performSave() }) {
                                Text(stringResource(R.string.open_source_save_here), style = labelStyle)
                            }
                        }
                    }
                }
            }
        }
        if (showNewFolder) {
            var folderName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showNewFolder = false },
                title = { Text(stringResource(R.string.open_source_new_folder), style = titleStyle) },
                text = {
                    TextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        singleLine = true,
                        textStyle = inputStyle,
                        placeholder = { Text(stringResource(R.string.open_source_folder_name_hint), style = inputStyle) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showNewFolder = false
                        createFolder(folderName)
                    }) {
                        Text(stringResource(R.string.dialog_positive), style = labelStyle)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNewFolder = false }) {
                        Text(stringResource(R.string.common_cancel), style = labelStyle)
                    }
                },
            )
        }
        if (showRename) {
            var renameText by remember(pendingFile) { mutableStateOf(pendingFile?.name ?: "") }
            AlertDialog(
                onDismissRequest = { renameAndFinish(renameText) },
                title = { Text(stringResource(R.string.open_source_rename_title), style = titleStyle) },
                text = {
                    TextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        textStyle = inputStyle,
                        placeholder = { Text(stringResource(R.string.open_source_rename_hint), style = inputStyle) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { renameAndFinish(renameText) }) {
                        Text(stringResource(R.string.dialog_positive), style = labelStyle)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { cancelRename() }) {
                        Text(stringResource(R.string.common_cancel), style = labelStyle)
                    }
                },
            )
        }
        val menuFile = fileMenuFile
        if (menuFile != null) {
            AlertDialog(
                onDismissRequest = { fileMenuFile = null },
                title = { Text(menuFile.name, style = titleStyle) },
                text = {
                    Column {
                        TextButton(
                            onClick = {
                                fileMenuFile = null
                                deleteTarget = menuFile
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(R.string.open_source_delete),
                                style = labelStyle,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start,
                            )
                        }
                        TextButton(
                            onClick = {
                                fileMenuFile = null
                                fileRenameTarget = menuFile
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(R.string.open_source_menu_rename),
                                style = labelStyle,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start,
                            )
                        }
                        TextButton(
                            onClick = {
                                clipboardFile = menuFile
                                clipboardCut = false
                                fileMenuFile = null
                                toast(getString(R.string.open_source_copied, menuFile.name))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(R.string.open_source_copy),
                                style = labelStyle,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start,
                            )
                        }
                        TextButton(
                            onClick = {
                                clipboardFile = menuFile
                                clipboardCut = true
                                fileMenuFile = null
                                toast(getString(R.string.open_source_cut_done, menuFile.name))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(R.string.open_source_cut),
                                style = labelStyle,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start,
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { fileMenuFile = null }) {
                        Text(stringResource(R.string.common_cancel), style = labelStyle)
                    }
                },
            )
        }
        val delFile = deleteTarget
        if (delFile != null) {
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text(stringResource(R.string.open_source_delete), style = titleStyle) },
                text = {
                    Text(stringResource(R.string.open_source_delete_file_confirm, delFile.name), style = bodyStyle)
                },
                confirmButton = {
                    TextButton(onClick = {
                        deleteTarget = null
                        deleteOp(delFile)
                    }) {
                        Text(stringResource(R.string.open_source_delete), style = labelStyle)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) {
                        Text(stringResource(R.string.common_cancel), style = labelStyle)
                    }
                },
            )
        }
        val renameTarget = fileRenameTarget
        if (renameTarget != null) {
            var renameText by remember(renameTarget) { mutableStateOf(renameTarget.name) }
            AlertDialog(
                onDismissRequest = { fileRenameTarget = null },
                title = { Text(stringResource(R.string.open_source_rename_title), style = titleStyle) },
                text = {
                    TextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        textStyle = inputStyle,
                        placeholder = { Text(stringResource(R.string.open_source_rename_hint), style = inputStyle) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        fileRenameTarget = null
                        renameFileOp(renameTarget, renameText)
                    }) {
                        Text(stringResource(R.string.dialog_positive), style = labelStyle)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { fileRenameTarget = null }) {
                        Text(stringResource(R.string.common_cancel), style = labelStyle)
                    }
                },
            )
        }
    }
}
