package com.github.tvbox.osc.ui.page

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.github.catvod.utils.Prefers
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ai.PublishPageResolver
import com.github.tvbox.osc.api.loader.WebSourceStore
import com.github.tvbox.osc.ui.activity.BrowserActivity
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.TopBarActionBox
import com.github.tvbox.osc.ui.components.wallpaperAwarePageBackground
import com.github.tvbox.osc.ui.dialog.AiAssistPanel
import com.github.tvbox.osc.util.Notify
import com.github.tvbox.osc.util.WebSourceProbe
import java.io.File

/** 3.38 网页源管理页：列表点名称进网页，长按编辑/删除/刷新；顶部＋添加。 */
@Composable
fun WebSourceManageScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    var items by remember { mutableStateOf(WebSourceStore.list()) }
    var menuItem by remember { mutableStateOf<Array<String>?>(null) }
    var editItem by remember { mutableStateOf<Array<String>?>(null) }
    var deleteItem by remember { mutableStateOf<Array<String>?>(null) }
    var probing by remember { mutableStateOf(false) }
    // 1.27：列表列数（1/2/3 列，跟全部分类弹层一样的切换），默认 1 列
    var columns by remember { mutableStateOf(Prefers.getInt(PREF_WEBSOURCE_COLUMNS, 1).coerceIn(1, 3)) }
    fun reload() {
        items = WebSourceStore.list()
    }
    // 1.27：默认全选 HTML——首次进入把已有源全部启用 HTML 首页（只做一次）
    LaunchedEffect(Unit) {
        WebSourceStore.applyAllWebHomeDefault()
        reload()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AppTopBarScaffold(
        containerColor = wallpaperAwarePageBackground(MaterialTheme.colorScheme.surfaceContainer),
        titleContent = {
            Text(
                text = "网页源",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        actions = {
            TopBarActionBox(
                iconRes = R.drawable.ic_subscribe_add,
                contentDescription = "添加网页源",
                onClick = { AiAssistPanel.showAddWebSource(context, onAdded = { reload() }) },
            )
        },
    ) { topPad, _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(topPad))
            if (items.isEmpty()) {
                Text(
                    text = "还没有网页源，点上面添加",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                // 1.23：全选 HTML：勾上全部启用 HTML 首页，取消勾选全部停用
                val allOn = items.isNotEmpty() && items.all { WebSourceStore.isWebHome(it[1]) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = allOn,
                        onCheckedChange = { on ->
                            WebSourceStore.setAllWebHome(on)
                            reload()
                        },
                    )
                    Text(
                        text = "全选 HTML",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    // 1.23：导出全部网页源（名称，网址，一行一个），格式跟批量添加一样，导出的文件可直接再批量导入
                    TextButton(onClick = {
                        try {
                            val dir = context.getExternalFilesDir(null)
                            if (dir != null) {
                                val file = File(dir, "网页源.txt")
                                file.writeText(items.joinToString("\n") { it[0] + "，" + it[1] })
                                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(share, "导出网页源"))
                            } else {
                                Notify.show("导出失败")
                            }
                        } catch (e: Throwable) {
                            Notify.show("导出失败")
                        }
                    }) { Text("导出") }
                    // 1.27：列数切换（1/2/3 列，跟全部分类弹层一样）
                    IconButton(onClick = {
                        val next = when (columns) { 3 -> 1; 1 -> 2; else -> 3 }
                        columns = next
                        Prefers.put(PREF_WEBSOURCE_COLUMNS, next)
                    }) {
                        Icon(
                            imageVector = when (columns) {
                                1 -> Icons.AutoMirrored.Filled.List
                                2 -> Icons.Filled.ViewColumn
                                else -> Icons.Filled.GridView
                            },
                            contentDescription = "切换列数",
                        )
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items, key = { it[1] }) { item ->
                        val name = item[0]
                        val url = item[1]
                        val dead = item.size > 3 && item[3] == "1"
                        Box {
                            // 1.27：每行后面的 HTML 开关去掉了，默认全部用 HTML 首页
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .combinedClickable(
                                        onClick = { activity?.let { BrowserActivity.start(it, url, name, WebSourceStore.toSite(name, url)?.key ?: "") } },
                                        onLongClick = { menuItem = item },
                                    )
                                    .padding(12.dp),
                            ) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (dead) {
                                    Text(
                                        text = "已失效",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = menuItem === item,
                                onDismissRequest = { menuItem = null },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("编辑") },
                                    onClick = { menuItem = null; editItem = item },
                                )
                                DropdownMenuItem(
                                    text = { Text("删除") },
                                    onClick = { menuItem = null; deleteItem = item },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (probing) "探测中…" else "刷新") },
                                    onClick = {
                                        menuItem = null
                                        if (probing) return@DropdownMenuItem
                                        probing = true
                                        Notify.show("正在探测 " + name)
                                        WebSourceProbe.start(listOf(item), object : WebSourceProbe.Listener {
                                            override fun onTick(done: Int, total: Int) {}
                                            override fun onDone(deadUrls: List<String>) {
                                                probing = false
                                                val isDead = deadUrls.any { it == url }
                                                WebSourceStore.markDead(listOf(url), isDead)
                                                reload()
                                                Notify.show(if (isDead) name + " 打不开，已标失效" else name + " 能打开")
                                            }
                                        })
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("AI 处理") },
                                    onClick = {
                                        menuItem = null
                                        Notify.show("正在找真站…")
                                        PublishPageResolver.resolve(url, object : PublishPageResolver.Callback {
                                            override fun onResult(result: PublishPageResolver.PublishResolveResult?) {
                                                val finalUrl = result?.finalUrl?.takeIf { it.isNotBlank() } ?: url
                                                if (result != null && result.isPublishPage && finalUrl != url) {
                                                    Notify.show("已找到真站，正在打开工坊…")
                                                }
                                                AiAssistPanel.showWorkshopFor(context, finalUrl, name)
                                            }
                                        })
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editItem?.let { target ->
        var newName by remember(target) { mutableStateOf(target[0]) }
        var newUrl by remember(target) { mutableStateOf(target[1]) }
        AlertDialog(
            onDismissRequest = { editItem = null },
            title = { Text("编辑网页源") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newUrl,
                        onValueChange = { newUrl = it },
                        label = { Text("网址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val group = if (target.size > 2) target[2] else ""
                    val ok = WebSourceStore.updateUrl(target[1], newUrl.trim(), newName.trim(), group)
                    if (ok) {
                        AppBootstrap.onApiUrlChanged()
                        reload()
                        Notify.show("已保存")
                        editItem = null
                    } else {
                        Notify.show("保存失败")
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editItem = null }) { Text("取消") }
            },
        )
    }

    deleteItem?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteItem = null },
            title = { Text("删除网页源") },
            text = { Text("删除「" + target[0] + "」？同时从首页源列表移除。") },
            confirmButton = {
                TextButton(onClick = {
                    WebSourceStore.remove(target[1])
                    AppBootstrap.onApiUrlChanged()
                    reload()
                    Notify.show("已删除")
                    deleteItem = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteItem = null }) { Text("取消") }
            },
        )
    }
}

/** 1.27：网页源列表列数（1/2/3），存在 Prefers。 */
private const val PREF_WEBSOURCE_COLUMNS = "websource_columns"
