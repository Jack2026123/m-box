package com.github.tvbox.osc.ui.page

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.github.tvbox.osc.R
import com.github.tvbox.osc.miniapp.DiagReport
import com.github.tvbox.osc.miniapp.DiagStatus
import com.github.tvbox.osc.miniapp.DiagStepResult
import com.github.tvbox.osc.miniapp.MiniAppDiagnoser
import com.github.tvbox.osc.miniapp.MiniAppImporter
import com.github.tvbox.osc.miniapp.MiniAppItem
import com.github.tvbox.osc.miniapp.MiniAppRuleValidator
import com.github.tvbox.osc.miniapp.MiniAppStore
import com.github.tvbox.osc.ui.components.AVBoxAlertDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MiniAppConfigPanel(
    importDialogOpen: Boolean,
    onImportDialogChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by MiniAppStore.items.collectAsState()
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<MiniAppItem?>(null) }
    var actionTarget by remember { mutableStateOf<MiniAppItem?>(null) }
    var editTarget by remember { mutableStateOf<MiniAppItem?>(null) }
    var editJsonText by remember { mutableStateOf("") }
    var editNameText by remember { mutableStateOf("") }
    var validateReport by remember { mutableStateOf<MiniAppRuleValidator.Report?>(null) }
    var validateTitle by remember { mutableStateOf("") }
    var diagnoseTarget by remember { mutableStateOf<MiniAppItem?>(null) }
    var diagnoseSteps by remember { mutableStateOf<List<DiagStepResult>>(emptyList()) }
    var diagnoseReport by remember { mutableStateOf<DiagReport?>(null) }
    var diagnoseRunning by remember { mutableStateOf(false) }
    var diagnoseJob by remember { mutableStateOf<Job?>(null) }
    var importText by remember { mutableStateOf("") }
    var importCategory by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    val gridState = rememberLazyGridState()
    var dragSource by remember { mutableStateOf<String?>(null) }
    var dragTotal by remember { mutableStateOf(Offset.Zero) }
    var dragOrigOffset by remember { mutableStateOf<IntOffset?>(null) }
    var dragOrigCenter by remember { mutableStateOf<Offset?>(null) }
    var dragStartOrder by remember { mutableStateOf<List<MiniAppItem>>(emptyList()) }
    var dragMoved by remember { mutableStateOf(false) }
    var dragToastShown by remember { mutableStateOf(false) }

    val categories = remember(items) {
        items.map { it.category.trim() }.filter { it.isNotEmpty() }.distinct()
    }
    val visible = remember(items, query, selectedCategory) {
        items.filter { item ->
            (selectedCategory == null || item.category == selectedCategory) &&
                (query.isBlank() || item.name.contains(query.trim(), ignoreCase = true))
        }
    }
    val reorderEnabled = query.isBlank() && selectedCategory == null

    fun openItem(item: MiniAppItem) {
        val intent = Intent(
            context,
            com.github.tvbox.osc.miniapp.engine.MiniAppRunActivity::class.java,
        )
        intent.putExtra(
            com.github.tvbox.osc.miniapp.engine.MiniAppRunActivity.EXTRA_RULE_JSON,
            item.ruleJson,
        )
        intent.putExtra(
            com.github.tvbox.osc.miniapp.engine.MiniAppRunActivity.EXTRA_NAME,
            item.name,
        )
        context.startActivity(intent)
    }

    fun doImport() {
        val text = importText.trim()
        if (text.isEmpty() || importing) return
        importing = true
        importError = null
        scope.launch {
            when (val result = MiniAppImporter.resolve(text)) {
                is MiniAppImporter.Result.Ok -> {
                    MiniAppStore.add(result.item.copy(category = importCategory.trim()))
                    Toast.makeText(
                        context,
                        context.getString(R.string.miniapp_imported, result.item.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                    importText = ""
                    importCategory = ""
                    onImportDialogChange(false)
                }
                is MiniAppImporter.Result.Err -> {
                    importError = context.getString(
                        when (result.kind) {
                            MiniAppImporter.Kind.EXPIRED -> R.string.miniapp_err_expired
                            MiniAppImporter.Kind.NETWORK -> R.string.miniapp_err_network
                            MiniAppImporter.Kind.PARSE -> R.string.miniapp_err_parse
                            MiniAppImporter.Kind.TITLE_EMPTY -> R.string.miniapp_err_empty_title
                        },
                    )
                }
            }
            importing = false
        }
    }

    fun startDiagnose(item: MiniAppItem) {
        diagnoseTarget = item
        diagnoseReport = null
        diagnoseRunning = true
        diagnoseSteps = MiniAppDiagnoser.STEP_NAMES
            .map { DiagStepResult(it, DiagStatus.PENDING, "", 0L) }
        diagnoseJob = scope.launch(Dispatchers.IO) {
            val report = MiniAppDiagnoser.runDiagnose(context, item) { steps ->
                diagnoseSteps = steps
            }
            diagnoseReport = report
            diagnoseRunning = false
        }
    }

    fun closeDiagnose() {
        diagnoseJob?.cancel()
        diagnoseJob = null
        diagnoseRunning = false
        diagnoseTarget = null
    }

    fun shareDiagnoseReport(report: DiagReport) {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                try {
                    val base = context.getExternalFilesDir(null) ?: return@withContext null
                    val dir = File(base, "logs")
                    if (!dir.exists()) dir.mkdirs()
                    val name = "miniapp_diag_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".txt"
                    val out = File(dir, name)
                    out.writeText(report.toText(), Charsets.UTF_8)
                    out
                } catch (_: Throwable) {
                    null
                }
            }
            if (file == null) {
                Toast.makeText(context, context.getString(R.string.miniapp_diagnose_failed), Toast.LENGTH_LONG).show()
            } else {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(share, context.getString(R.string.miniapp_diagnose_share)))
                Toast.makeText(context, context.getString(R.string.miniapp_diagnose_shared, file.absolutePath), Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.miniapp_search_hint)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            val chips = listOf<String?>(null) + categories
            items(chips) { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = cat },
                    label = { Text(cat ?: stringResource(R.string.miniapp_all)) },
                )
            }
        }
        if (visible.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.miniapp_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                items(visible, key = { it.source }) { item ->
                    val isDragging = dragSource == item.source
                    val currentOffset = gridState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.key == item.source }?.offset
                    val translation = if (isDragging) {
                        val orig = dragOrigOffset
                        if (orig != null && currentOffset != null) {
                            Offset(
                                dragTotal.x + (orig.x - currentOffset.x),
                                dragTotal.y + (orig.y - currentOffset.y),
                            )
                        } else {
                            dragTotal
                        }
                    } else {
                        Offset.Zero
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .graphicsLayer {
                                translationX = translation.x
                                translationY = translation.y
                                scaleX = if (isDragging) 1.05f else 1f
                                scaleY = if (isDragging) 1.05f else 1f
                                shadowElevation = if (isDragging) 8f else 0f
                            }
                            .zIndex(if (isDragging) 1f else 0f)
                            .pointerInput(item.source) {
                                // 88：统一手势检测，替代原来的 detectTapGestures +
                                // detectDragGesturesAfterLongPress 双检测器。
                                // 旧逻辑只有 onDragEnd 且 !dragMoved 时才出菜单：
                                // 长按超时前手指稍动超出 touchSlop 即取消长按、
                                // 网格滚动接管或 onDragCancel 时一律不出菜单，
                                // 这就是长按时有时无的根因；默认长按超时约
                                // 400ms 也让弹窗感觉慢。
                                // 新逻辑：300ms 短阈值 + 24dp 抖动容忍：
                                // 300ms 内抬起且位移在 touchSlop 内 = 点按打开；
                                // 300ms 未抬起 = 已触发长按（给拖动项 1.05 倍反馈）；
                                // 触发后抬起且总位移在 24dp 内 = 出菜单；
                                // 触发后位移超 24dp = 进拖动排序；
                                // 网格滚动接管（move 被消费）= 直接放弃，不出菜单；
                                // 触发后系统取消且未移动 = 容错出菜单。
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    val downPos = down.position
                                    val touchSlop = viewConfiguration.touchSlop
                                    val dragThresholdPx = 24.dp.toPx()
                                    var scrollAborted = false
                                    val upBeforeTimeout = withTimeoutOrNull(300L) {
                                        var result: PointerInputChange? = null
                                        while (result == null) {
                                            val event = awaitPointerEvent(PointerEventPass.Final)
                                            val change = event.changes.firstOrNull { it.id == down.id }
                                                ?: continue
                                            if (!change.pressed) {
                                                result = change
                                            } else if (change.isConsumed) {
                                                scrollAborted = true
                                                result = change
                                            } else if ((change.position - downPos).getDistance() > dragThresholdPx * 2) {
                                                scrollAborted = true
                                                result = change
                                            }
                                        }
                                        result
                                    }
                                    if (scrollAborted) return@awaitEachGesture
                                    if (upBeforeTimeout != null) {
                                        val up = upBeforeTimeout
                                        if (!up.isConsumed &&
                                            (up.position - downPos).getDistance() <= touchSlop
                                        ) {
                                            openItem(item)
                                        }
                                        return@awaitEachGesture
                                    }
                                    // 长按已触发：与旧 onDragStart 同样的建拖动状态，
                                    // 菜单在抬起时才决定出不出。
                                    actionTarget = null
                                    dragStartOrder = items
                                    val startInfo = gridState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.key == item.source }
                                    dragOrigOffset = startInfo?.offset
                                    dragOrigCenter = startInfo?.let {
                                        Offset(
                                            (it.offset.x + it.size.width / 2).toFloat(),
                                            (it.offset.y + it.size.height / 2).toFloat(),
                                        )
                                    }
                                    dragTotal = Offset.Zero
                                    dragMoved = false
                                    dragToastShown = false
                                    dragSource = item.source
                                    var totalDrag = Offset.Zero
                                    var inDragMode = false
                                    var lifted = false
                                    var systemCancelled = false
                                    var scrollTaken = false
                                    while (!lifted && !systemCancelled && !scrollTaken) {
                                        val event = awaitPointerEvent(PointerEventPass.Final)
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                            ?: continue
                                        if (!change.pressed) {
                                            if (change.isConsumed) {
                                                systemCancelled = true
                                            } else {
                                                lifted = true
                                            }
                                            break
                                        }
                                        if (change.isConsumed) {
                                            scrollTaken = true
                                            break
                                        }
                                        val delta = change.position - change.previousPosition
                                        if (delta == Offset.Zero) continue
                                        totalDrag += delta
                                        if (!inDragMode) {
                                            if (totalDrag.getDistance() > dragThresholdPx) {
                                                inDragMode = true
                                            } else {
                                                continue
                                            }
                                        }
                                        change.consume()
                                        dragMoved = true
                                        if (!reorderEnabled) {
                                            if (!dragToastShown) {
                                                dragToastShown = true
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.miniapp_reorder_filtered),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                            continue
                                        }
                                        dragTotal = totalDrag
                                        val center = dragOrigCenter?.plus(totalDrag)
                                            ?: continue
                                        val target = gridState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { info ->
                                                info.key != item.source &&
                                                    center.x >= info.offset.x &&
                                                    center.x < info.offset.x + info.size.width &&
                                                    center.y >= info.offset.y &&
                                                    center.y < info.offset.y + info.size.height
                                            } ?: continue
                                        val targetSource = target.key as? String
                                            ?: continue
                                        MiniAppStore.move(item.source, targetSource)
                                    }
                                    val wasMoved = dragMoved
                                    val src = dragSource
                                    dragSource = null
                                    dragTotal = Offset.Zero
                                    dragOrigOffset = null
                                    dragOrigCenter = null
                                    if (scrollTaken) {
                                        return@awaitEachGesture
                                    }
                                    if (systemCancelled) {
                                        if (!wasMoved && totalDrag.getDistance() <= dragThresholdPx && src != null) {
                                            val target = items.firstOrNull { it.source == src }
                                            if (target != null) actionTarget = target
                                        } else if (wasMoved) {
                                            MiniAppStore.setOrder(dragStartOrder)
                                        }
                                        return@awaitEachGesture
                                    }
                                    if (!wasMoved && src != null) {
                                        val target = items.firstOrNull { it.source == src }
                                        if (target != null) actionTarget = target
                                    }
                                }
                            },
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (item.icon.isBlank()) {
                                Text(
                                    text = item.name.firstOrNull()?.toString().orEmpty(),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            } else {
                                AsyncImage(
                                    model = item.icon,
                                    contentDescription = item.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                        )
                        // Haikuo card layout (logtest43 design
                        // reference): a small tag under the name —
                        // here the stored category, shown only when
                        // one was set at import.
                        if (item.category.isNotBlank()) {
                            Text(
                                text = item.category,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
        Button(
            onClick = { onImportDialogChange(true) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Text(stringResource(R.string.miniapp_import_rule))
        }
    }

    val menuTarget = actionTarget
    if (menuTarget != null) {
        Dialog(
            onDismissRequest = { actionTarget = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(32.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                    modifier = Modifier.width(minOf(400.dp, maxWidth * 0.80f)),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = menuTarget.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 12.dp),
                        )
                        TextButton(
                            onClick = {
                                actionTarget = null
                                val report = MiniAppRuleValidator.validateItem(menuTarget)
                                validateTitle = menuTarget.name
                                validateReport = report
                            },
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(R.string.miniapp_action_validate),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        TextButton(
                            onClick = {
                                actionTarget = null
                                startDiagnose(menuTarget)
                            },
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(R.string.miniapp_action_diagnose),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        TextButton(
                            onClick = {
                                actionTarget = null
                                editTarget = menuTarget
                                editNameText = menuTarget.name
                                editJsonText = try {
                                    val j = org.json.JSONObject(menuTarget.ruleJson)
                                    j.toString(2)
                                } catch (_: Throwable) {
                                    menuTarget.ruleJson
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(R.string.miniapp_action_edit),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        TextButton(
                            onClick = {
                                actionTarget = null
                                deleteTarget = menuTarget
                            },
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(R.string.common_delete),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 12.dp, bottom = 12.dp),
                        ) {
                            TextButton(
                                onClick = { actionTarget = null },
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.common_cancel),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (importDialogOpen) {
        AVBoxAlertDialog(
            onDismissRequest = { if (!importing) onImportDialogChange(false) },
            title = { Text(stringResource(R.string.miniapp_import_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        placeholder = { Text(stringResource(R.string.miniapp_import_hint)) },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = importCategory,
                        onValueChange = { importCategory = it },
                        placeholder = { Text(stringResource(R.string.miniapp_category_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (importError != null) {
                        Text(
                            text = importError.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { doImport() }, enabled = !importing) {
                    Text(stringResource(R.string.miniapp_import_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { onImportDialogChange(false) }, enabled = !importing) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (deleteTarget != null) {
        val target = deleteTarget
        AVBoxAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.miniapp_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.miniapp_delete_message,
                        target?.name.orEmpty(),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (target != null) {
                            if (diagnoseTarget?.source == target.source) closeDiagnose()
                            MiniAppStore.delete(target)
                        }
                        deleteTarget = null
                    },
                ) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    editTarget?.let { target ->
        Dialog(
            onDismissRequest = { editTarget = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize().imePadding(),
                contentAlignment = BiasAlignment(0f, -0.33f),
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .width(minOf(440.dp, maxWidth * 0.82f))
                        .heightIn(max = maxHeight * 0.62f),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.miniapp_edit_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        OutlinedTextField(
                            value = editNameText,
                            onValueChange = { editNameText = it },
                            label = { Text(stringResource(R.string.miniapp_edit_name), style = MaterialTheme.typography.bodyMedium) },
                            textStyle = MaterialTheme.typography.bodyLarge,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = editJsonText,
                            onValueChange = { editJsonText = it },
                            label = { Text(stringResource(R.string.miniapp_edit_json), style = MaterialTheme.typography.bodyMedium) },
                            textStyle = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            minLines = 8,
                            maxLines = 12,
                        )
                        TextButton(
                            onClick = {
                                val report = MiniAppRuleValidator.validate(editJsonText)
                                validateTitle = editNameText.ifBlank { target.name }
                                validateReport = report
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.miniapp_action_validate), style = MaterialTheme.typography.titleMedium)
                        }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        TextButton(onClick = { editTarget = null }) {
                            Text(stringResource(R.string.common_cancel), style = MaterialTheme.typography.titleMedium)
                        }
                        TextButton(
                            onClick = {
                                val raw = editJsonText.trim()
                        if (raw.isEmpty()) {
                            Toast.makeText(context, context.getString(R.string.miniapp_edit_empty), Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        val parsed = try {
                            org.json.JSONObject(raw)
                        } catch (e: Throwable) {
                            Toast.makeText(context, context.getString(R.string.miniapp_edit_bad_json, e.message.orEmpty()), Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        val compact = parsed.toString()
                        val newName = editNameText.trim().ifBlank {
                            parsed.optString("title").ifBlank { target.name }
                        }
                        val icon = parsed.optString("icon").ifBlank { target.icon }
                        val category = parsed.optString("group").ifBlank {
                            parsed.optString("type").ifBlank { target.category }
                        }
                        val updated = target.copy(
                            name = newName,
                            icon = icon,
                            category = category,
                            ruleJson = compact,
                        )
                        if (MiniAppStore.update(updated)) {
                            Toast.makeText(context, context.getString(R.string.miniapp_edit_saved), Toast.LENGTH_SHORT).show()
                            editTarget = null
                        } else {
                            Toast.makeText(context, context.getString(R.string.miniapp_edit_failed), Toast.LENGTH_SHORT).show()
                        }
                    },
                        ) {
                            Text(stringResource(R.string.common_save), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
    }

    validateReport?.let { report ->
        AVBoxAlertDialog(
            onDismissRequest = { validateReport = null },
            title = { Text(stringResource(R.string.miniapp_validate_title, validateTitle)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = report.summary,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (report.ok) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                    report.details.forEach { line ->
                        Text(
                            text = "• " + line,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { validateReport = null }) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {},
        )
    }

    diagnoseTarget?.let { target ->
        val report = diagnoseReport
        AVBoxAlertDialog(
            onDismissRequest = { closeDiagnose() },
            title = { Text(stringResource(R.string.miniapp_diagnose_title, target.name)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    if (diagnoseRunning) {
                        Text(
                            text = stringResource(R.string.miniapp_diagnose_running),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (report != null) {
                        Text(
                            text = if (report.cancelled) {
                                stringResource(R.string.miniapp_diagnose_cancelled)
                            } else {
                                stringResource(R.string.miniapp_diagnose_done) +
                                    "：通过 " + report.passCount +
                                    " / 失败 " + report.failCount +
                                    " / 跳过 " + report.skipCount
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = if (report.failCount > 0) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        )
                    }
                    diagnoseSteps.forEachIndexed { index, step ->
                        val mark = when (step.status) {
                            DiagStatus.PASS -> "✓"
                            DiagStatus.FAIL -> "✗"
                            DiagStatus.SKIP -> "–"
                            DiagStatus.RUNNING -> "…"
                            DiagStatus.PENDING -> "○"
                            DiagStatus.CANCELLED -> "✗"
                        }
                        val color = when (step.status) {
                            DiagStatus.PASS -> MaterialTheme.colorScheme.primary
                            DiagStatus.FAIL, DiagStatus.CANCELLED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(
                            text = (index + 1).toString() + ". " + mark + " " + step.name +
                                if (step.millis > 0) {
                                    "（" + String.format(Locale.getDefault(), "%.1f", step.millis / 1000.0) + "秒）"
                                } else {
                                    ""
                                },
                            style = MaterialTheme.typography.titleSmall,
                            color = color,
                        )
                        if (step.detail.isNotBlank()) {
                            Text(
                                text = step.detail,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (diagnoseRunning) {
                        TextButton(onClick = { closeDiagnose() }) {
                            Text(stringResource(R.string.miniapp_diagnose_cancel))
                        }
                    } else if (report != null) {
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("miniapp_diag", report.toText()))
                                Toast.makeText(context, context.getString(R.string.miniapp_diagnose_copied), Toast.LENGTH_SHORT).show()
                            },
                        ) {
                            Text(stringResource(R.string.miniapp_diagnose_copy))
                        }
                        TextButton(onClick = { shareDiagnoseReport(report) }) {
                            Text(stringResource(R.string.miniapp_diagnose_share))
                        }
                    }
                }
            },
            dismissButton = if (diagnoseRunning) {
                null
            } else {
                {
                    TextButton(onClick = { closeDiagnose() }) {
                        Text(stringResource(R.string.miniapp_diagnose_close))
                    }
                }
            },
        )
    }
}
