package com.github.tvbox.osc.ui.page

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.ui.findActivityOrNull
import com.github.tvbox.osc.reading.ReadingStore
import com.github.tvbox.osc.ui.activity.ConfigManageActivity
import com.github.tvbox.osc.ui.components.AVBoxAlertDialog
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.components.RowLeadingIcon
import com.github.tvbox.osc.ui.components.wallpaperAwareContainerColor
import com.github.tvbox.osc.ui.components.SettingsSwitch
import com.github.tvbox.osc.ui.components.animSpeedScale
import com.github.tvbox.osc.ui.components.appCardShape
import com.github.tvbox.osc.ui.components.fxCardShadow
import com.github.tvbox.osc.ui.components.fxEngravedTextStyle
import com.github.tvbox.osc.ui.components.glassSurface
import com.github.tvbox.osc.ui.theme.cardContainer
import com.github.tvbox.osc.util.LOG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The 配置管理 "阅读" tab: reading subscriptions (a pack of Legado book
 * sources each), stored fully apart from the vod/live chains. The cards
 * mirror the vod/live SubscribeCard one-to-one: same face (icon, name,
 * address, capsule switch on the right — on = this pack is the active
 * reading config, off = leave reading mode) and the same long-press
 * multi-select manage mode driven by ConfigManageViewModel (top-bar
 * edit/delete, checkboxes instead of switches). Deleting runs through
 * the toolbar like vod/live — no confirm dialog, no per-card buttons.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReadingConfigPanel(vm: ConfigManageViewModel) {
    val subscriptions by ReadingStore.subscriptions.collectAsState()
    val activeUrl by ReadingStore.activeUrl.collectAsState()
    val manageMode by vm.manageMode.collectAsState()
    val readingSelected by vm.readingSelected.collectAsState()
    val editTarget by vm.readingEditTarget.collectAsState()

    if (subscriptions.isEmpty()) {
        Column(modifier = Modifier.fillMaxSize()) {
            LoadStateBox(
                state = LoadState.Empty,
                emptyText = stringResource(R.string.reading_empty),
                errorText = "",
                retryText = "",
                emptyIconRes = R.drawable.ic_empty_record,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(subscriptions, key = { it.url }) { item ->
            val count = remember(item.url) {
                runCatching { ReadingStore.packSources(item.url).size }.getOrDefault(0)
            }
            val active = item.url == activeUrl
            val animFactor = com.github.tvbox.osc.ui.theme.LocalAnimFactor.current
            val shape = appCardShape(28.dp)
            Surface(
                modifier = Modifier.fillMaxWidth().fxCardShadow(shape),
                shape = shape,
                // logtest43: reading card follows the 透明背景 slider
                // (relief call left untouched).
                color = wallpaperAwareContainerColor(MaterialTheme.colorScheme.cardContainer),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .combinedClickable(
                            onClick = {
                                if (manageMode) {
                                    vm.toggleReadingSelected(item.url)
                                } else {
                                    ReadingStore.setActive(item.url)
                                }
                            },
                            onLongClick = {
                                if (!manageMode) vm.longPressSelectReading(item.url)
                            },
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RowLeadingIcon(R.drawable.ic_config_reading, enabled = true)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.reading_sources_count, count),
                            style = fxEngravedTextStyle(MaterialTheme.typography.bodyMedium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = item.url,
                            style = fxEngravedTextStyle(MaterialTheme.typography.bodySmall),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    AnimatedContent(
                        targetState = manageMode,
                        transitionSpec = {
                            val rowSpring = spring<Float>(
                                stiffness = Spring.StiffnessMedium *
                                    animSpeedScale(animFactor),
                            )
                            (fadeIn(animationSpec = rowSpring) +
                                scaleIn(initialScale = 0.7f, animationSpec = rowSpring))
                                .togetherWith(fadeOut(animationSpec = rowSpring))
                        },
                        label = "readingRowControl",
                    ) { managing ->
                        if (managing) {
                            Checkbox(
                                checked = item.url in readingSelected,
                                onCheckedChange = { vm.toggleReadingSelected(item.url) },
                            )
                        } else {
                            SettingsSwitch(
                                checked = active,
                                onCheckedChange = { next ->
                                    if (next) {
                                        ReadingStore.setActive(item.url)
                                    } else if (active) {
                                        ReadingStore.clearActive()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    editTarget?.let { item ->
        ReadingEditDialog(
            item = item,
            onDismiss = { vm.clearReadingEditTarget() },
            onSave = { name, url ->
                ReadingStore.editSubscription(item, name, url)
                vm.clearReadingEditTarget()
                vm.exitManageMode()
            },
        )
    }
}

/** Add-by-URL / import-local dialog for the reading tab. */
@Composable
fun ReadingAddDialog(
    onDismiss: () -> Unit,
    onAdded: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun addRemote() {
        val target = url.trim()
        if (target.isEmpty() || busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = ReadingStore.fetchPackText(target)
                    val sources = com.github.tvbox.osc.reading.BookSource.parsePack(text)
                    if (sources.isEmpty()) {
                        throw IllegalStateException(
                            context.getString(R.string.reading_pack_invalid),
                        )
                    }
                    val entry = ReadingStore.addSubscription(name.trim(), target)
                    ReadingStore.savePack(target, text)
                    entry
                }
            }
            busy = false
            result.fold(
                onSuccess = { entry ->
                    ReadingStore.setActive(entry.url)
                    onAdded()
                    onDismiss()
                },
                onFailure = { e ->
                    LOG.e("ReadingConfig", e)
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.reading_fetch_failed,
                            e.message ?: e.javaClass.simpleName,
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    AVBoxAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.reading_add_subscribe),
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .glassSurface(CircleShape, MaterialTheme.colorScheme.surfaceBright)
                        .clickable {
                            val host = context.findActivityOrNull() as? ConfigManageActivity
                            if (host != null) {
                                host.launchReadingImport { fileName, text ->
                                    val entry = ReadingStore.importLocal(
                                        name.trim().ifBlank { fileName },
                                        text,
                                    )
                                    ReadingStore.setActive(entry.url)
                                    onAdded()
                                    onDismiss()
                                }
                            } else {
                                Toast.makeText(
                                    context,
                                    R.string.config_pick_local_unavailable,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_file_choose),
                        contentDescription = stringResource(R.string.reading_add_local),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.config_field_name)) },
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.config_field_url)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { addRemote() },
                enabled = url.isNotBlank() && !busy,
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/** Edit name / URL of an existing reading subscription (same fields as the vod edit dialog). */
@Composable
fun ReadingEditDialog(
    item: SubscribeSource,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember(item.url) { mutableStateOf(item.name) }
    var url by remember(item.url) { mutableStateOf(item.url) }
    val localOnly = item.url.startsWith("local://")
    AVBoxAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.config_edit_subscribe)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.config_field_name)) },
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !localOnly,
                    label = { Text(stringResource(R.string.config_field_url)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), url.trim()) },
                enabled = url.isNotBlank(),
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
