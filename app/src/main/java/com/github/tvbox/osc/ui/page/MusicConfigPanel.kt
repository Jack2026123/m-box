package com.github.tvbox.osc.ui.page

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.music.MusicScriptParser
import com.github.tvbox.osc.music.MusicFreeScriptProbe
import com.github.tvbox.osc.music.MusicSourceKind
import com.github.tvbox.osc.music.MusicSource
import com.github.tvbox.osc.music.MusicStore
import com.github.tvbox.osc.music.MusicSubscriptions
import com.github.tvbox.osc.player.ui.findActivityOrNull
import com.github.tvbox.osc.ui.activity.ConfigManageActivity
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsRow
import com.github.tvbox.osc.ui.components.SettingsSwitch
import com.github.tvbox.osc.ui.components.SettingsSwitchRow
import com.github.tvbox.osc.util.LOG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 音乐源管理（P1）。
 *
 * 三个动作：
 *  1. 导入洛雪脚本 —— 本地文件 / 粘贴内容 / 链接
 *  2. 启用停用、删除
 *  3. 点一行 = 激活该源 → 退出配置页即进音乐界面
 *
 * 设计原则（对照 2.68 之前的教训）：
 *  - 脚本全文存 KV，不依赖下载，导入成功必定还在
 *  - 每一行都显示"名字 + 版本 + 作者"，用户能分清哪个是哪个
 *  - 脚本为空的行标红提示，而不是静默变成"搜不到"
 *  - 只有"启用且脚本在"的源能激活
 */
@Composable
fun MusicConfigPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources by MusicStore.sources.collectAsState()
    val activeId by MusicStore.activeSourceId.collectAsState()

    var addOpen by remember { mutableStateOf(false) }
    var urlOpen by remember { mutableStateOf(false) }
    var confirmClearOpen by remember { mutableStateOf(false) }
    var subOpen by remember { mutableStateOf(false) }
    // MusicFree 插件数量提到顶层：订阅入口要用，原来它定义在 item lambda 内部
    val mfCount = sources.count { it.kind == MusicSourceKind.MUSIC_FREE }
    var busy by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    // 源 id -> 自检结果文字
    var healthMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // MusicFree 引擎开关（提到顶层：Composable 的 collectAsState 不能放在 item lambda 里）
    val mfEnabled by MusicStore.musicFreeEnabled.collectAsState()

    val enabledCount = sources.count { it.enabled && !it.codeMissing }

    fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    fun report(results: List<MusicStore.ImportResult>) {
        if (results.isEmpty()) return
        val added = results.count { it is MusicStore.ImportResult.Added }
        val updated = results.count { it is MusicStore.ImportResult.Updated }
        val dup = results.count { it is MusicStore.ImportResult.Duplicated }
        val bad = results.filterIsInstance<MusicStore.ImportResult.Invalid>()

        val parts = ArrayList<String>(3)
        if (added > 0) parts.add("新增 $added")
        if (updated > 0) parts.add("补全 $updated")
        if (dup > 0) parts.add("已存在 $dup")
        if (bad.isNotEmpty()) parts.add("失败 ${bad.size}")

        toast(if (parts.isEmpty()) "没有可导入的内容" else parts.joinToString("，"))

        bad.firstOrNull()?.let { LOG.i("music-import-reject: " + it.reason) }
    }

    /**
     * 一键自检：逐个源真加载一遍脚本，报告平台和音质。
     *
     * 这是排查「导入成功但搜不到歌」的关键工具——
     * 能明确区分「脚本加载失败」和「脚本活着但搜不到」。
     */
    fun checkAll() {
        if (checking) return
        checking = true
        healthMap = emptyMap()
        scope.launch {
            val result = HashMap<String, String>()
            val list = MusicStore.searchable()
            withContext(Dispatchers.IO) {
                for (src in list) {
                    val text = try {
                        if (src.kind == MusicSourceKind.MUSIC_FREE) {
                            if (!MusicStore.musicFreeEnabled.value) {
                                val plat = MusicFreeScriptProbe.guessPlatform(src.code)
                                val platName = com.github.tvbox.osc.music.MusicPlatformNames.properName(plat)
                                    ?: plat.ifBlank { src.name }
                                "插件[" + platName + "] 未启用（开关关闭）"
                            } else {
                                val host = com.github.tvbox.osc.music.MusicFreeHost.obtain(src)
                                // 插件启动是异步的，这里等一下
                                var waited = 0
                                while (!host.isReady && host.lastError.isBlank() && waited < 8_000) {
                                    Thread.sleep(50)
                                    waited += 50
                                }
                                when {
                                    !host.isReady -> "加载失败：" + host.lastError.ifBlank { "超时" }
                                    host.supportedMethods.isEmpty() -> "已加载，但没导出方法"
                                    else -> "✓ 插件[" + host.platform.ifBlank { "?" } + "] " +
                                        host.supportedMethods.joinToString("/")
                                }
                            }
                        } else {
                            val rt = com.github.tvbox.osc.music.LxRuntime.obtain(src)
                            when {
                                rt == null -> "加载失败(null)"
                                !rt.isReady -> "加载失败：" + rt.lastError.ifBlank { "未知" }
                                rt.sourcePlatforms.isEmpty() -> "已加载，但没声明平台"
                                else -> "✓ 洛雪 " + rt.sourcePlatforms.joinToString("/") +
                                    " · " + rt.qualityMap.entries.joinToString(" ") {
                                        it.key + ":" + it.value.joinToString(",")
                                    }
                            }
                        }
                    } catch (th: Throwable) {
                        "异常：" + (th.message ?: th.javaClass.simpleName)
                    }
                    synchronized(result) { result[src.id] = text }
                }
            }
            healthMap = result
            checking = false
            val ok = result.values.count { it.startsWith("✓") }
            toast("自检完成：$ok/${list.size} 个可用")
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        item {
            SettingsCard(SettingsCardPosition.SINGLE) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { addOpen = true },
                    ) {
                        Text(
                            text = "音乐源",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (sources.isEmpty()) {
                                "还没有音源，点这里导入洛雪脚本"
                            } else {
                                "共 ${sources.size} 个 · 可用 $enabledCount 个"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    SettingsSwitch(
                        checked = activeId.isNotEmpty(),
                        onCheckedChange = { on ->
                            if (on) {
                                val target = sources.firstOrNull { it.enabled }
                                if (target == null) {
                                    toast("先启用一个音源")
                                } else {
                                    MusicStore.setActive(target.id)
                                }
                            } else {
                                MusicStore.clearActive()
                            }
                        },
                    )
                }
                if (sources.isNotEmpty()) {
                    SettingsRow(
                        title = "自检全部音源",
                        subtitle = "逐个加载脚本，看平台和音质（排查搜不到歌）",
                        enabled = !checking,
                        valueText = if (checking) "检测中…" else "开始",
                        onClick = { checkAll() },
                    )
                }
                // MusicFree 引擎开关：默认关，开了才加载插件
                SettingsSwitchRow(
                    title = "MusicFree 插件引擎",
                    checked = mfEnabled,
                    onCheckedChange = { next ->
                        MusicStore.setMusicFreeEnabled(next)
                        toast(if (next) "MusicFree 已启用" else "MusicFree 已关闭")
                    },
                    subtitle = if (mfEnabled) {
                        "已启用 · $mfCount 个插件"
                    } else {
                        "默认关闭。开启后才能加载 MusicFree 插件（洛雪不受影响）"
                    },
                )
                SettingsRow(
                    title = "从链接导入",
                    subtitle = "支持洛雪脚本的直链（.js）",
                    enabled = !busy,
                    valueText = if (busy) "导入中…" else "链接",
                    onClick = { urlOpen = true },
                )
                SettingsRow(
                    title = "MusicFree 插件市场",
                    subtitle = if (mfCount > 0) {
                        "已装 $mfCount 个插件 · 点这里浏览订阅源"
                    } else {
                        "从订阅源批量安装插件（网易/酷狗/酷我/QQ/哔哩哔哩等）"
                    },
                    enabled = !busy,
                    valueText = "浏览",
                    onClick = { subOpen = true },
                )
                if (sources.isNotEmpty()) {
                    SettingsRow(
                        title = "清空全部音源",
                        subtitle = "会同时退出音乐模式",
                        valueText = "清空",
                        onClick = { confirmClearOpen = true },
                    )
                }
            }
        }

        if (sources.isEmpty()) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "导入一个洛雪音源脚本，壳子就会变成音乐播放器",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "推荐先试：星海音乐源 / 聚合API接口 / 轻量音源",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        items(items = sources, key = { it.id }) { source ->
            MusicSourceRow(
                source = source,
                active = source.id == activeId,
                health = healthMap[source.id].orEmpty(),
                onActivate = {
                    when {
                        source.codeMissing -> toast("脚本为空，请重新导入")
                        !source.enabled -> toast("先启用这个源")
                        else -> {
                            MusicStore.setActive(source.id)
                            toast("已切换：退出配置页进入音乐界面")
                            context.findActivityOrNull()?.finish()
                        }
                    }
                },
                onToggle = { enabled ->
                    MusicStore.setEnabled(source.id, enabled)
                    if (enabled && MusicStore.isMusicMode && !source.codeMissing) {
                        MusicStore.setActive(source.id)
                    }
                },
                onRemove = {
                    MusicStore.remove(source.id)
                    toast("已删除")
                },
            )
        }
    }

    if (addOpen) {
        MusicImportDialog(
            onDismiss = { addOpen = false },
            onPickFiles = { kind ->
                val host = context.findActivityOrNull() as? ConfigManageActivity
                if (host == null) {
                    Toast.makeText(
                        context,
                        R.string.config_pick_local_unavailable,
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@MusicImportDialog
                }
                // 批量选：19 个脚本一次框完，不用点 19 次
                host.launchMusicImportMany { items ->
                    val all = ArrayList<Pair<String, String>>()
                    for ((name, text) in items) {
                        // 单个文件里也可能拼了好几个脚本，切开一起导
                        val chunks = splitScripts(text)
                        if (chunks.isEmpty()) {
                            all.add(name to text)
                        } else if (chunks.size == 1) {
                            all.add(name to chunks[0].second)
                        } else {
                            chunks.forEachIndexed { i, (n, c) ->
                                all.add((name + "#" + (i + 1)).ifBlank { n } to c)
                            }
                        }
                    }
                    report(importWithLxRetry(all, kind))
                }
            },
            onPaste = { text, kind ->
                addOpen = false
                report(importWithLxRetry(splitScripts(text), kind))
            },
        )
    }

    if (urlOpen) {
        MusicUrlImportDialog(
            onDismiss = { urlOpen = false },
            onSubmit = { raw, kind ->
                urlOpen = false
                busy = true
                scope.launch {
                    val result = runCatching { withContext(Dispatchers.IO) { fetchScripts(raw) } }
                    busy = false
                    result.fold(
                        onSuccess = { items -> report(importWithLxRetry(items, kind)) },
                        onFailure = { th ->
                            LOG.e("music-import-url-failed: " + th.message)
                            toast("下载失败：" + (th.message ?: th.javaClass.simpleName))
                        },
                    )
                }
            },
        )
    }

    if (subOpen) {
        MusicSourceManagePage(
            onDismiss = { subOpen = false },
            onImported = { results ->
                report(results.filterNotNull())
                subOpen = false
            },
        )
    }
    if (confirmClearOpen) {
        AlertDialog(
            onDismissRequest = { confirmClearOpen = false },
            title = { Text("清空全部音源") },
            text = { Text("将删除 ${sources.size} 个音源并退出音乐模式，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    MusicStore.removeAll()
                    confirmClearOpen = false
                    toast("已清空")
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearOpen = false }) { Text("取消") }
            },
        )
    }
}

// ==================================================================== 行

@Composable
private fun MusicSourceRow(
    source: MusicSource,
    active: Boolean,
    health: String,
    onActivate: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val errorColor = MaterialTheme.colorScheme.error
    val broken = source.codeMissing

    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onActivate),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = source.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (active) {
                        Spacer(Modifier.size(6.dp))
                        Box(modifier = Modifier.size(6.dp)) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(6.dp),
                            ) {}
                        }
                    }
                }
                val subtitle = when {
                    health.isNotBlank() -> health
                    broken -> "脚本为空，请重新导入"
                    source.subtitle.isNotBlank() -> source.subtitle
                    else -> "洛雪音源"
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        broken -> errorColor
                        health.startsWith("✓") -> MaterialTheme.colorScheme.primary
                        health.isNotBlank() -> errorColor
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Icon(
                painter = painterResource(R.drawable.ic_delete),
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(40.dp)
                    .clickable(onClick = onRemove)
                    .padding(10.dp),
            )
            SettingsSwitch(
                checked = source.enabled,
                onCheckedChange = onToggle,
            )
        }
    }
}

// ==================================================================== 导入弹窗

@Composable
internal fun MusicImportDialog(
    title: String = "导入音乐源",
    onDismiss: () -> Unit,
    onPickFiles: (MusicSourceKind?) -> Unit,
    onPaste: (String, MusicSourceKind?) -> Unit,
) {
    var pasteOpen by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf<MusicSourceKind?>(null) }

    if (pasteOpen) {
        val count = remember(text) { if (text.isBlank()) 0 else splitScripts(text).size }
        AlertDialog(
            onDismissRequest = { pasteOpen = false },
            title = { Text("粘贴脚本内容") },
            text = {
                Column {
                    MusicKindSelector(kind) { kind = it }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("脚本内容") },
                        minLines = 6,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (count > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "识别到 $count 个脚本",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { if (text.isNotBlank()) onPaste(text, kind) },
                    enabled = text.isNotBlank(),
                ) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { pasteOpen = false }) { Text("取消") }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                MusicKindSelector(kind) { kind = it }
                Text(
                    text = "支持洛雪自定义音源（含 globalThis.lx）与 MusicFree 插件。混淆过的洛雪脚本自动识别不出来时会按洛雪再试一次；还不行再手动选种类。导入后会自动识别名字、版本、作者。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                FilledTonalButton(
                    onClick = { onPickFiles(kind) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("选择本地 .js 文件") }
                FilledTonalButton(
                    onClick = { pasteOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("粘贴脚本内容") }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun MusicKindSelector(
    selected: MusicSourceKind?,
    onSelect: (MusicSourceKind?) -> Unit,
) {
    com.github.tvbox.osc.ui.components.CapsuleSegmentedButton(
        options = listOf(
            com.github.tvbox.osc.ui.components.SegmentOption(
                label = "自动识别",
                value = null,
            ),
            com.github.tvbox.osc.ui.components.SegmentOption(
                label = "洛雪",
                value = MusicSourceKind.LX,
            ),
            com.github.tvbox.osc.ui.components.SegmentOption(
                label = "MusicFree",
                value = MusicSourceKind.MUSIC_FREE,
            ),
        ),
        selectedValue = selected,
        onOptionSelected = onSelect,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MusicUrlImportDialog(
    onDismiss: () -> Unit,
    onSubmit: (String, MusicSourceKind?) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf<MusicSourceKind?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从链接导入") },
        text = {
            Column {
                MusicKindSelector(kind) { kind = it }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("脚本直链（http/https）") },
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "多个链接可用换行分隔",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onSubmit(text, kind) },
                enabled = text.isNotBlank(),
            ) { Text("下载并导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ==================================================================== 工具

/**
 * 本地 / 粘贴 / 链接导入的统一入口：先按所选种类（默认自动识别）调 importMany，
 * 自动识别下仍失败的项再以强制洛雪（forcedKind=LX）重试一次——混淆过的洛雪脚本
 * （如 globalThis['\x6c\x78']，字面量里没有 globalThis.lx）自动识别判非法，
 * 但强制种类能直接注册；去重 / 同名替换 / 激活平移全部复用 importMany。
 * 已手动选种类的不再重试（用户已定种类，失败就是真的不对）。
 * 文件级：MusicConfigPanel 与 MusicSourceManagePage 两处共用。
 */
internal fun importWithLxRetry(items: List<Pair<String, String>>, kind: MusicSourceKind?): List<MusicStore.ImportResult> {
    val first = MusicStore.importMany(items, kind)
    if (kind != null) return first
    val failed = ArrayList<Pair<String, String>>()
    for (i in items.indices) {
        if (first.getOrNull(i) is MusicStore.ImportResult.Invalid) failed.add(items[i])
    }
    if (failed.isEmpty()) return first
    val retry = MusicStore.importMany(failed, MusicSourceKind.LX)
    val merged = first.toMutableList()
    var retryIndex = 0
    for (i in items.indices) {
        if (first.getOrNull(i) is MusicStore.ImportResult.Invalid) {
            merged[i] = retry[retryIndex]
            retryIndex++
        }
    }
    return merged
}

/**
 * 把粘贴/拼在一起的多份脚本切开。
 *
 * 判据：每个洛雪脚本都会取 `globalThis.lx`，按它第一次出现的位置切分，
 * 并往前退到最近的空行之后，保证脚本头注释（@name/@version）跟正文在一起。
 * 只有一份时原样返回。
 */
internal fun splitScripts(text: String): List<Pair<String, String>> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return emptyList()

    val markers = ArrayList<Int>()
    var index = trimmed.indexOf("globalThis")
    while (index >= 0) {
        val slice = trimmed.substring(index, minOf(index + 24, trimmed.length))
        if (slice.contains("lx")) {
            var start = index
            val boundary = trimmed.lastIndexOf("\n\n", index)
            if (boundary >= 0 && index - boundary < 4096) start = boundary + 2
            if (markers.isEmpty() || start > markers.last()) markers.add(start)
        }
        index = trimmed.indexOf("globalThis", index + 1)
    }

    fun nameOf(chunk: String): String = MusicScriptParser.parseMeta(chunk).displayName

    if (markers.size <= 1) {
        return listOf(nameOf(trimmed) to trimmed)
    }

    val chunks = ArrayList<Pair<String, String>>(markers.size)
    for (i in markers.indices) {
        val start = markers[i]
        val end = if (i + 1 < markers.size) markers[i + 1] else trimmed.length
        if (end - start < 16) continue
        val chunk = trimmed.substring(start, end).trim()
        if (chunk.isEmpty()) continue
        chunks.add(nameOf(chunk) to chunk)
    }
    return chunks.ifEmpty { listOf(nameOf(trimmed) to trimmed) }
}

/** 阻塞下载脚本全文（调用方负责放到 IO 线程） */
private fun fetchScripts(raw: String): List<Pair<String, String>> {
    val urls = raw.split('\n', '\r')
        .map { it.trim() }
        .filter { it.startsWith("http://") || it.startsWith("https://") }
    if (urls.isEmpty()) throw IllegalArgumentException("没有有效链接")

    val results = ArrayList<Pair<String, String>>(urls.size)
    var lastError: Throwable? = null
    for (url in urls) {
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                // 不少源站会拦默认 UA
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
                )
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                if (body.isBlank()) throw IllegalStateException("内容为空")
                val fileName = url.substringAfterLast('/').substringBefore('?').substringBefore('#')
                val itemName = if (fileName.contains(".plugin.", ignoreCase = true)) fileName
                    else MusicScriptParser.parseMeta(body).displayName
                results.add(itemName to body)
            } finally {
                conn.disconnect()
            }
        } catch (th: Throwable) {
            lastError = th
            LOG.e("music-fetch-failed " + url + " : " + th.message)
        }
    }
    if (results.isEmpty()) {
        throw lastError ?: IllegalStateException("下载失败")
    }
    return results
}


// ---------------------------------------------------------------------------
// 源管理页（订阅 + 已装源）
// ---------------------------------------------------------------------------

/**
 * 源管理页。
 *
 * 结构按宿主 TVBox 的源管理习惯来：
 *
 *   一、已装音源
 *      · MusicFree 插件（按种类分组，每行带版本 + 删除）
 *      · 洛雪音源
 *   二、可安装插件（订阅清单，勾选批量装）
 *
 * 订阅状态放在顶部卡片里，一眼能看出订阅是否生效、拉到了多少个。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
internal fun MusicSourceManagePage(
    onDismiss: () -> Unit,
    onImported: (List<MusicStore.ImportResult>) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val sources by MusicStore.sources.collectAsState()
    val mfEnabled by MusicStore.musicFreeEnabled.collectAsState()

    var loading by remember { mutableStateOf(true) }
    var catalog by remember { mutableStateOf<MusicSubscriptions.Catalog?>(null) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var installing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var subUrl by remember { mutableStateOf(MusicSubscriptions.url()) }
    // 手动导入（本地文件 / 粘贴脚本）。gitee 被网络阻断时这是唯一能装上插件的路。
    var importOpen by remember { mutableStateOf(false) }

    // 已装源：按种类分组
    val lxSources = remember(sources) { sources.filter { it.kind == MusicSourceKind.LX } }
    val mfSources = remember(sources) { sources.filter { it.kind == MusicSourceKind.MUSIC_FREE } }
    val installedNames = remember(mfSources) { mfSources.map { it.name }.toSet() }

    LaunchedEffect(Unit) {
        loading = true
        catalog = MusicSubscriptions.fetch(force = false)
        loading = false
    }

    fun reload(force: Boolean) {
        scope.launch {
            loading = true
            catalog = MusicSubscriptions.fetch(force = force)
            loading = false
        }
    }

    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { androidx.compose.material3.Text("源管理") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onDismiss) {
                        androidx.compose.material3.Icon(
                            painter = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_close_clear_cancel),
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    androidx.compose.material3.TextButton(onClick = { importOpen = true }) {
                        androidx.compose.material3.Text("导入")
                    }
                    androidx.compose.material3.Text(
                        text = "共 " + sources.size + " 个源",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        modifier = androidx.compose.ui.Modifier.padding(end = 12.dp),
                    )
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = androidx.compose.ui.Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        ) {
            // ---------------- 订阅状态卡片 ----------------
            item {
                androidx.compose.material3.Card(
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                ) {
                    androidx.compose.foundation.layout.Column(
                        modifier = androidx.compose.ui.Modifier.padding(14.dp),
                    ) {
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Text(
                                text = "MusicFree 插件",
                                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                                modifier = androidx.compose.ui.Modifier.weight(1f),
                            )
                            TextButton(onClick = { reload(true) }, enabled = !loading) {
                                androidx.compose.material3.Text(if (loading) "拉取中" else "刷新")
                            }
                        }
                        androidx.compose.material3.Text(
                            text = "接口音乐站点自动加载",
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        androidx.compose.material3.Text(
                            text = "插件正文托管在 gitee，部分网络下会被阻断。拉不到时用右上角「导入」粘贴脚本。",
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        androidx.compose.foundation.layout.Spacer(
                            modifier = androidx.compose.ui.Modifier.height(6.dp),
                        )
                        val cat = catalog
                        androidx.compose.material3.Text(
                            text = if (cat == null || cat.isEmpty) {
                                "当前订阅：" + subUrl + "\n（未拉到清单，检查网络或订阅地址）"
                            } else {
                                "当前订阅：" + subUrl + "\n共 " + cat.entries.size + " 个可选插件"
                            },
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // ---------------- 已装：MusicFree ----------------
            item {
                SourceSectionHeader(
                    "MusicFree 插件（已装 " + mfSources.size + "）",
                    actionText = if (mfEnabled) "已启用" else "未启用",
                )
            }
            if (mfSources.isEmpty()) {
                item { SourceEmptyHint("还没有插件，从下面的列表里勾选安装") }
            } else {
                items(mfSources, key = { it.id }) { src ->
                    InstalledSourceRow(
                        name = src.name,
                        version = src.version,
                        subtitle = src.subtitle,
                        enabled = src.enabled,
                        onToggle = { MusicStore.setEnabled(src.id, !src.enabled) },
                        onDelete = { MusicStore.remove(src.id) },
                    )
                }
            }

            // ---------------- 已装：洛雪 ----------------
            item {
                SourceSectionHeader("洛雪音源（已装 " + lxSources.size + "）")
            }
            if (lxSources.isEmpty()) {
                item { SourceEmptyHint("还没有洛雪音源，点右上角「导入」") }
            } else {
                items(lxSources, key = { it.id }) { src ->
                    InstalledSourceRow(
                        name = src.name,
                        version = src.version,
                        subtitle = src.subtitle,
                        enabled = src.enabled,
                        onToggle = { MusicStore.setEnabled(src.id, !src.enabled) },
                        onDelete = { MusicStore.remove(src.id) },
                    )
                }
            }

            // ---------------- 可安装插件 ----------------
            item {
                SourceSectionHeader(
                    "可安装插件",
                    actionText = if (picked.isEmpty()) "" else "已选 " + picked.size,
                )
            }

            if (loading) {
                item {
                    androidx.compose.foundation.layout.Box(
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = androidx.compose.ui.Modifier.size(26.dp),
                        )
                    }
                }
            } else if (installing) {
                item {
                    androidx.compose.material3.Text(
                        text = progress,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        modifier = androidx.compose.ui.Modifier.padding(vertical = 12.dp),
                    )
                }
            } else {
                val cat = catalog
                if (cat != null && !cat.isEmpty) {
                    item {
                        androidx.compose.foundation.layout.Row {
                            TextButton(onClick = {
                                picked = cat.entries.map { it.url }.toSet()
                            }) { androidx.compose.material3.Text("全选") }
                            TextButton(onClick = { picked = emptySet() }) {
                                androidx.compose.material3.Text("清空")
                            }
                            androidx.compose.foundation.layout.Spacer(
                                modifier = androidx.compose.ui.Modifier.weight(1f),
                            )
                            TextButton(
                                enabled = picked.isNotEmpty(),
                                onClick = {
                                    val targets = cat.entries.filter { it.url in picked }
                                    if (targets.isEmpty()) return@TextButton
                                    scope.launch {
                                        installing = true
                                        progress = "准备安装 " + targets.size + " 个插件…"
                                        val results = MusicSubscriptions.install(targets) { done, total, name ->
                                            progress = if (name.isBlank()) {
                                                "完成 " + done + "/" + total
                                            } else {
                                                "下载 " + name + "（" + (done + 1) + "/" + total + "）"
                                            }
                                        }
                                        installing = false
                                        picked = emptySet()
                                        progress = ""
                                        val okCount = results.count { it != null }
                                        val failCount = results.size - okCount
                                        if (okCount == 0 && failCount > 0) {
                                            // 典型原因：插件正文托管在 gitee，部分网络下会被重置连接。
                                            // 必须说清楚，否则用户只看到「点了没反应」。
                                            Toast.makeText(
                                                context,
                                                "下载失败：" + failCount + " 个插件都取不到脚本。\n" +
                                                    "插件正文在 gitee 上，当前网络访问不了。\n" +
                                                    "请用右上角「导入」粘贴脚本，或换网络后重试。",
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        } else if (failCount > 0) {
                                            Toast.makeText(
                                                context,
                                                "成功 " + okCount + " 个，失败 " + failCount + " 个",
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                        onImported(results.filterNotNull())
                                    }
                                },
                            ) { androidx.compose.material3.Text("安装 " + picked.size + " 个") }
                        }
                    }
                    itemsIndexed(cat.entries) { _, entry ->
                        val checked = entry.url in picked
                        val already = entry.name in installedNames
                        androidx.compose.material3.Card(
                            modifier = androidx.compose.ui.Modifier
                                .fillMaxWidth()
                                .clickable {
                                    picked = if (checked) picked - entry.url else picked + entry.url
                                },
                        ) {
                            androidx.compose.foundation.layout.Row(
                                modifier = androidx.compose.ui.Modifier.padding(
                                    horizontal = 12.dp,
                                    vertical = 10.dp,
                                ),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.Checkbox(
                                    checked = checked,
                                    onCheckedChange = { next ->
                                        picked = if (next) picked + entry.url else picked - entry.url
                                    },
                                )
                                androidx.compose.foundation.layout.Column(
                                    modifier = androidx.compose.ui.Modifier.weight(1f),
                                ) {
                                    androidx.compose.material3.Text(
                                        text = entry.name,
                                        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                                    )
                                    val line = buildList {
                                        if (entry.version.isNotBlank()) add("v" + entry.version)
                                        if (already) add("已安装")
                                    }.joinToString(" · ")
                                    if (line.isNotBlank()) {
                                        androidx.compose.material3.Text(
                                            text = line,
                                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 手动导入弹窗：本地 .js 文件 / 直接粘贴脚本正文。
    // 订阅源里的插件正文都放在 gitee 上，gitee 在当前网络下连接会被重置，
    // 所以这条路必须留着，否则订阅列表点「安装」只会静默失败。
    if (importOpen) {
        MusicImportDialog(
            onDismiss = { importOpen = false },
            onPickFiles = { kind ->
                importOpen = false
                val host = context.findActivityOrNull() as? ConfigManageActivity
                if (host == null) {
                    Toast.makeText(
                        context,
                        R.string.config_pick_local_unavailable,
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@MusicImportDialog
                }
                // 批量选：多文件一次框完；zip 在回调里已按 extractMusicItems 解包。
                // 接线逐字复用 MusicConfigPanel 内 addOpen 那段（批量选文件切脚本 +
                // 强制洛雪重试），结果走 onImported 汇报并关闭源管理页。
                host.launchMusicImportMany { items ->
                    val all = ArrayList<Pair<String, String>>()
                    for ((name, text) in items) {
                        // 单个文件里也可能拼了好几个脚本，切开一起导
                        val chunks = splitScripts(text)
                        if (chunks.isEmpty()) {
                            all.add(name to text)
                        } else if (chunks.size == 1) {
                            all.add(name to chunks[0].second)
                        } else {
                            chunks.forEachIndexed { i, (n, c) ->
                                all.add((name + "#" + (i + 1)).ifBlank { n } to c)
                            }
                        }
                    }
                    onImported(importWithLxRetry(all, kind))
                }
            },
            onPaste = { text, kind ->
                importOpen = false
                if (text.isBlank()) return@MusicImportDialog
                scope.launch {
                    installing = true
                    progress = "导入中…"
                    val results = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        splitScripts(text).map { (name, code) ->
                            MusicStore.importScript(code, name, kind)
                        }
                    }
                    installing = false
                    progress = ""
                    onImported(results)
                }
            },
        )
    }
}

/** 分组标题 */
@androidx.compose.runtime.Composable
private fun SourceSectionHeader(title: String, actionText: String = "") {
    androidx.compose.foundation.layout.Row(
        modifier = androidx.compose.ui.Modifier.padding(top = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Text(
            text = title,
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
            modifier = androidx.compose.ui.Modifier.weight(1f),
        )
        if (actionText.isNotBlank()) {
            androidx.compose.material3.Text(
                text = actionText,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 空分组提示 */
@androidx.compose.runtime.Composable
private fun SourceEmptyHint(text: String) {
    androidx.compose.material3.Text(
        text = text,
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = androidx.compose.ui.Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
    )
}

/** 已装源一行：名称 + 版本 + 启用开关 + 删除 */
@androidx.compose.runtime.Composable
private fun InstalledSourceRow(
    name: String,
    version: String,
    subtitle: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = androidx.compose.ui.Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = androidx.compose.ui.Modifier.weight(1f),
            ) {
                androidx.compose.material3.Text(
                    text = name,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                )
                val line = subtitle.ifBlank {
                    if (version.isBlank()) "" else version
                }
                if (line.isNotBlank()) {
                    androidx.compose.material3.Text(
                        text = line,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            androidx.compose.material3.Switch(
                checked = enabled,
                onCheckedChange = { onToggle() },
            )
            androidx.compose.material3.IconButton(onClick = onDelete) {
                androidx.compose.material3.Icon(
                    painter = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_delete),
                    contentDescription = "删除",
                )
            }
        }
    }
}
