package com.github.tvbox.osc.ui.page

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import com.github.catvod.utils.Prefers
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.ui.findActivityOrNull
import com.github.tvbox.osc.reading.ReadingStore
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.music.MusicStore
import com.github.tvbox.osc.ui.activity.ConfigManageActivity
import com.github.tvbox.osc.ui.components.AVBoxAlertDialog
import com.github.tvbox.osc.ui.components.AVBoxBottomSheet
import com.github.tvbox.osc.ui.components.CapsuleSegmentedButton
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.wallpaperAwareContainerColor
import com.github.tvbox.osc.ui.components.wallpaperAwarePageBackground
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.components.LocalSheetDismiss
import com.github.tvbox.osc.ui.components.LocalSheetDismissThen
import com.github.tvbox.osc.ui.components.SegmentOption
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.RowLeadingIcon
import com.github.tvbox.osc.ui.components.SettingsOptionRow
import com.github.tvbox.osc.ui.components.SettingsSwitch
import com.github.tvbox.osc.ui.components.SettingsSwitchRow
import com.github.tvbox.osc.ui.components.TopBarActionBox
import com.github.tvbox.osc.ui.components.animSpeedScale
import com.github.tvbox.osc.ui.components.appCardShape
import com.github.tvbox.osc.ui.components.glassSurface
import com.github.tvbox.osc.ui.theme.LocalAnimFactor
import com.github.tvbox.osc.ui.theme.cardContainer
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.TmdbApi
import androidx.compose.foundation.BorderStroke
import com.github.tvbox.osc.ui.components.capsuleGroupAccentColor
import com.github.tvbox.osc.ui.components.capsuleGroupBaseColor
import com.github.tvbox.osc.ui.components.capsuleGroupWaterGloss
import com.github.tvbox.osc.ui.components.waterGlossSheen
import com.github.tvbox.osc.ui.theme.ControlThemeStore

private fun badgeText(name: String, url: String, emptyText: String): String = when {
    name.isNotEmpty() -> name
    url.isEmpty() -> emptyText
    else -> url.substringAfter("://").substringBefore('/').ifEmpty { url }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConfigManageScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    // 音乐导入结果汇报：逐字复用 MusicConfigPanel.report 的口径
    // （新增 / 补全 / 已存在 / 失败），只把面板内局部 toast 改成直接 Toast。
    fun reportMusicImport(results: List<MusicStore.ImportResult>) {
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
        Toast.makeText(
            context,
            if (parts.isEmpty()) "没有可导入的内容" else parts.joinToString("，"),
            Toast.LENGTH_SHORT,
        ).show()
        bad.firstOrNull()?.let { LOG.i("music-import-reject: " + it.reason) }
    }
    val vm: ConfigManageViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ConfigManageViewModel { TmdbApi.DefaultTmdbClient() } }
        },
    )
    var mode by rememberSaveable { mutableStateOf(ConfigMode.Vod) }
    val hiddenModes by SubscribeVisibility.hiddenModes.collectAsState()
    val visibleModes = ConfigMode.entries.filterNot { it in hiddenModes }
    val displayMode = if (mode in visibleModes) mode else visibleModes.firstOrNull() ?: ConfigMode.Vod
    var addDialogOpen by remember { mutableStateOf(false) }
    var readingAddOpen by remember { mutableStateOf(false) }
    var miniAppAddOpen by remember { mutableStateOf(false) }
    var musicAddOpen by remember { mutableStateOf(false) }
    var repoSheetOpen by remember { mutableStateOf(false) }
    var visibilitySheetOpen by remember { mutableStateOf(false) }
    val vodItems by vm.vodItems.collectAsState()
    val liveItems by vm.liveItems.collectAsState()
    val activeUrl by vm.activeUrl.collectAsState()
    val liveActiveUrl by vm.liveActiveUrl.collectAsState()
    val liveFollow by vm.liveFollow.collectAsState()
    val fallbackLiveUrl by vm.fallbackLiveUrl.collectAsState()
    var fallbackDialogOpen by remember { mutableStateOf(false) }
    val disabledUrls by vm.disabledUrls.collectAsState()
    // Display-only: while a reading pack is active, vod/live cards render
    // as unselected (single global selection). The stored vod/live
    // selection data is untouched; tapping a card still switches to it
    // and clears reading mode via the view model.
    val readingActiveUrl by ReadingStore.activeUrl.collectAsState()
    val readingActive = readingActiveUrl.isNotEmpty()
    val pendingSwitch by vm.pendingSwitch.collectAsState()
    val selected by vm.selected.collectAsState()
    val manageMode by vm.manageMode.collectAsState()
    val readingSelected by vm.readingSelected.collectAsState()
    val readingSubs by ReadingStore.subscriptions.collectAsState()
    val editTarget by vm.editTarget.collectAsState()
    val toastEvent by vm.toastEvent.collectAsState()
    val isVod = displayMode == ConfigMode.Vod
    val isMiniApp = displayMode == ConfigMode.MiniApp
    val isReading = displayMode == ConfigMode.Reading
    val isMusic = displayMode == ConfigMode.Music
    val currentItems = if (isVod) vodItems else liveItems

    // Global 动画速度 effect: shared transition springs for this screen.
    // Factor 0 (关) must be a true snap, not just a very stiff spring.
    val animFactor = LocalAnimFactor.current
    val fxSpringF = if (animFactor <= 0f) {
        androidx.compose.animation.core.snap<Float>()
    } else {
        spring<Float>(
            stiffness = Spring.StiffnessMedium * animSpeedScale(animFactor),
        )
    }
    val fxSpringO = if (animFactor <= 0f) {
        androidx.compose.animation.core.snap<androidx.compose.ui.unit.IntOffset>()
    } else {
        spring<androidx.compose.ui.unit.IntOffset>(
            stiffness = Spring.StiffnessMedium * animSpeedScale(animFactor),
        )
    }

    LaunchedEffect(toastEvent) {
        toastEvent?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    LaunchedEffect(displayMode) {
        vm.onModeChanged()
        vm.refreshTabData(displayMode)
        repoSheetOpen = false
    }

    BackHandler(enabled = manageMode) { vm.exitManageMode() }

    val canSwitchRepo = if (isVod) {
        HistoryHelper.isApiLineUrl(activeUrl)
    } else {
        ApiConfig.get().isLiveApiLineMode() && HistoryHelper.isLiveApiLineUrl(liveActiveUrl)
    }

    val repoEntries = if (isVod) HistoryHelper.getApiLines() else HistoryHelper.getLiveApiLines()

    val repoActiveUrl = if (isVod) activeUrl else liveActiveUrl

    val noSourceText = stringResource(R.string.config_no_source)
    val vodBadge = remember(vodItems, activeUrl, noSourceText) {
        badgeText(
            vodItems.firstOrNull { parseSubscribe(it).url == activeUrl }?.let { parseSubscribe(it).name }.orEmpty(),
            activeUrl,
            noSourceText,
        )
    }
    AppTopBarScaffold(
        containerColor = wallpaperAwarePageBackground(MaterialTheme.colorScheme.surfaceContainer),
        titleContent = {
            Text(
                text = stringResource(R.string.settings_config_manage),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        navigationIcon = {
            TopBarActionBox(
                R.drawable.ic_arrow_left,
                stringResource(R.string.common_back),
                onClick = { if (manageMode) vm.exitManageMode() else onNavigateBack() },
            )
        },
        actions = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 73 订阅显示搬到配置管理: a permanent header entry
                // (the top-bar empty space the user boxed) opening the
                // visibility sheet. It lives in the fixed top bar, never
                // inside tab content, so the take-back path stays
                // reachable even when all four tabs are hidden.
                TopBarActionBox(
                    iconRes = R.drawable.ic_filter,
                    contentDescription = stringResource(R.string.sub_visibility_title),
                    onClick = { visibilitySheetOpen = true },
                )
                AnimatedContent(
                    targetState = manageMode &&
                        (if (isReading) readingSubs.isNotEmpty()
                            else currentItems.isNotEmpty()),
                transitionSpec = {
                    (fadeIn(animationSpec = fxSpringF) +
                        scaleIn(initialScale = 0.8f, animationSpec = fxSpringF))
                        .togetherWith(fadeOut(animationSpec = fxSpringF))
                },
                label = "configTopAction",
            ) { managing ->
                if (managing) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ManageActionIcon(
                            iconRes = R.drawable.ic_edit,
                            contentDescription = stringResource(R.string.common_edit),
                            enabled = if (isReading) {
                                readingSelected.size == 1
                            } else {
                                selected.size == 1
                            },
                            onClick = {
                                if (isReading) {
                                    vm.requestReadingEdit()
                                } else {
                                    vm.editTarget.value =
                                        selected.firstOrNull()?.let { parseSubscribe(it) }
                                }
                            },
                        )
                        ManageActionIcon(
                            iconRes = R.drawable.ic_delete,
                            contentDescription = stringResource(R.string.common_delete),
                            enabled = if (isReading) {
                                readingSelected.isNotEmpty()
                            } else {
                                selected.isNotEmpty()
                            },
                            onClick = {
                                if (isReading) {
                                    vm.deleteSelectedReading()
                                } else {
                                    vm.deleteSelected(
                                        if (isVod) SubscribeMode.Vod else SubscribeMode.Live,
                                    )
                                }
                            },
                        )
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isMiniApp) {
                            TopBarActionBox(
                                iconRes = R.drawable.ic_subscribe_add,
                                contentDescription = stringResource(R.string.miniapp_import_rule),
                                onClick = { miniAppAddOpen = true },
                            )
                        } else {
                            if (canSwitchRepo && !isReading) {
                                TopBarActionBox(
                                    iconRes = R.drawable.ic_switch_repo,
                                    contentDescription = stringResource(R.string.config_switch_repo),
                                    onClick = { repoSheetOpen = true },
                                )
                            }
                            TopBarActionBox(
                                iconRes = R.drawable.ic_subscribe_add,
                                contentDescription = if (isReading) {
                                    stringResource(R.string.reading_add_subscribe)
                                } else if (isVod) {
                                    stringResource(R.string.config_add_subscribe)
                                } else if (isMusic) {
                                    stringResource(R.string.music_add_subscribe)
                                } else {
                                    stringResource(R.string.config_add_live_source)
                                },
                                onClick = {
                                    when {
                                        isReading -> readingAddOpen = true
                                        isMusic -> musicAddOpen = true
                                        else -> addDialogOpen = true
                                    }
                                },
                            )
                        }
                    }
                }
                }
            }
        },
    ) { topPad, _ ->
        Column(modifier = Modifier.fillMaxSize()) {
            CapsuleSegmentedButton(
                options = listOf(
                    SegmentOption(
                        label = stringResource(R.string.common_vod),
                        value = ConfigMode.Vod,
                        iconPainter = painterResource(R.drawable.ic_config_vod),
                        hugContent = true,
                    ),
                    SegmentOption(
                        label = stringResource(R.string.common_live),
                        value = ConfigMode.Live,
                        iconPainter = painterResource(R.drawable.ic_config_live),
                        hugContent = true,
                    ),
                    SegmentOption(
                        label = stringResource(R.string.reading_tab),
                        value = ConfigMode.Reading,
                        iconPainter = painterResource(R.drawable.ic_config_reading),
                        hugContent = true,
                    ),
                    SegmentOption(
                        label = stringResource(R.string.miniapp_tab),
                        value = ConfigMode.MiniApp,
                        iconPainter = painterResource(R.drawable.ic_config_miniapp),
                        hugContent = true,
                    ),
                    SegmentOption(
                        label = stringResource(R.string.music_tab),
                        value = ConfigMode.Music,
                        hugContent = true,
                    ),
                ).filter { it.value in visibleModes },
                selectedValue = displayMode,
                onOptionSelected = { mode = it },
                distributeLeftover = true,
                // logtest43: tab strip follows the 透明背景 slider
                // like every other card surface on this page.
                containerColor = wallpaperAwareContainerColor(MaterialTheme.colorScheme.surfaceBright),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                iconSize = 20.dp,
                iconSpacing = 4.dp,
                labelStyle = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = topPad + 8.dp),
            )
            AnimatedContent(
                targetState = displayMode,
                transitionSpec = {
                    val toRight = targetState.ordinal > initialState.ordinal
                    (
                        slideInHorizontally(fxSpringO) { full ->
                            if (toRight) full / 4 else -full / 4
                        } + fadeIn(fxSpringF)
                        ).togetherWith(
                        slideOutHorizontally(fxSpringO) { full ->
                            if (toRight) -full / 4 else full / 4
                        } + fadeOut(fxSpringF)
                    )
                },
                label = "configSegment",
            ) { m ->
                val mIsVod = m == ConfigMode.Vod
                val mItems = if (mIsVod) vodItems else liveItems
                if (m == ConfigMode.Reading) {
                    ReadingConfigPanel(vm = vm)
                } else if (m == ConfigMode.MiniApp) {
                    MiniAppConfigPanel(
                        importDialogOpen = miniAppAddOpen,
                        onImportDialogChange = { miniAppAddOpen = it },
                    )
                } else if (m == ConfigMode.Music) {
                    MusicConfigPanel()
                } else if (mItems.isEmpty()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (!mIsVod) {
                            FollowVodCard(
                                checked = liveFollow && !readingActive,
                                subtitle = if (activeUrl.isEmpty()) {
                                    stringResource(R.string.config_no_vod_source)
                                } else {
                                    stringResource(R.string.config_current_vod_source, vodBadge)
                                },
                                onFollow = { vm.followLiveNow() },
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
                            )
                            FallbackLiveCard(
                                url = fallbackLiveUrl,
                                onClick = { fallbackDialogOpen = true },
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            )
                        }
                        LoadStateBox(
                            state = LoadState.Empty,
                            emptyText = stringResource(
                                if (mIsVod) R.string.config_empty_subscribe
                                else R.string.config_empty_live_source
                            ),
                            errorText = "",
                            retryText = "",
                            emptyIconRes = R.drawable.ic_empty_record,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    }
                } else {
                    val mOrdered = remember(mItems, activeUrl, liveActiveUrl, liveFollow, mIsVod) {
                        mItems.sortedByDescending {
                            val url = parseSubscribe(it).url
                            if (mIsVod) url == activeUrl else !liveFollow && url == liveActiveUrl
                        }
                    }
                    LazyColumn(
                        state = rememberLazyListState(),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 24.dp,
                            bottom = 8.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (!mIsVod) {
                            item(key = "Live#follow") {
                                FollowVodCard(
                                    checked = liveFollow && !readingActive,
                                    subtitle = if (activeUrl.isEmpty()) {
                                        stringResource(R.string.config_no_vod_source)
                                    } else {
                                        stringResource(R.string.config_current_vod_source, vodBadge)
                                    },
                                    onFollow = { vm.followLiveNow() },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                            item(key = "Live#fallback") {
                                FallbackLiveCard(
                                    url = fallbackLiveUrl,
                                    onClick = { fallbackDialogOpen = true },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                        items(mOrdered, key = { "${m.name}#$it" }) { value ->
                            val item = parseSubscribe(value)
                            val inUse = if (mIsVod) {
                                item.url == activeUrl || HistoryHelper.isApiLineSourceOf(item.url, activeUrl)
                            } else {
                                !liveFollow && (
                                    item.url == liveActiveUrl ||
                                        HistoryHelper.isLiveApiLineSourceOf(item.url, liveActiveUrl)
                                    )
                            }
                            SubscribeCard(
                                modifier = Modifier.animateItem(),
                                item = item,
                                active = inUse && !readingActive,
                                disabled = item.url in disabledUrls,
                                manageMode = manageMode,
                                selected = value in selected,
                                onClick = {
                                    if (manageMode) {
                                        vm.toggleSelected(value)
                                    } else {
                                        vm.requestSwitch(item, mIsVod)
                                    }
                                },
                                onLongClick = {
                                    vm.longPressSelect(value)
                                },
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        vm.requestSwitch(item, mIsVod)
                                    } else if (!mIsVod) {
                                        vm.followLiveNow()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    val editing = editTarget
    // 音乐 tab 不走点播 / 直播订阅弹窗：导入入口是下面的 MusicImportDialog，
    // 绝不能再把音乐源写进 LIVE_SUBSCRIBE_LIST。
    if ((addDialogOpen || editing != null) && !isMusic) {
        AddSubscribeDialog(
            title = if (editing != null) {
                if (isVod) stringResource(R.string.config_edit_subscribe) else stringResource(R.string.config_edit_live_source)
            } else {
                if (isVod) stringResource(R.string.config_add_subscribe) else stringResource(R.string.config_add_live_source)
            },
            urlSupportingText = if (isVod) "" else stringResource(R.string.config_live_source_hint),
            initialName = editing?.name.orEmpty(),
            initialUrl = editing?.url.orEmpty(),
            onDismiss = {
                addDialogOpen = false
                vm.editTarget.value = null
            },
            onSave = { name, url ->
                if (!isMusic) {
                    if (editing != null) vm.commitEdit(isVod, editing, name, url) else vm.commitAdd(isVod, name, url)
                }
                addDialogOpen = false
            },
            onPickFile = { onPicked ->
                val host = context.findActivityOrNull() as? ConfigManageActivity
                if (host != null) {
                    host.launchLocalConfig { api -> onPicked(api) }
                } else {
                    LOG.e(
                        "ConfigManage",
                        "pick local: host activity missing, context=" + context.javaClass.name,
                        null,
                    )
                    Toast.makeText(context, R.string.config_pick_local_unavailable, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    if (readingAddOpen) {
        ReadingAddDialog(
            onDismiss = { readingAddOpen = false },
            onAdded = { },
        )
    }

    // 音乐 tab 顶栏 + 号：直接打开音乐导入入口。MusicImportDialog 是同包
    // internal（MusicConfigPanel.kt），标题由调用处传「添加音乐源」；
    // 接线逐字复用 MusicConfigPanel 内 addOpen 那段（批量选文件切脚本 +
    // 粘贴切脚本，均走 importWithLxRetry，不碰直播订阅）。
    if (musicAddOpen) {
        MusicImportDialog(
            title = "添加音乐源",
            onDismiss = { musicAddOpen = false },
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
                    reportMusicImport(importWithLxRetry(all, kind))
                }
            },
            onPaste = { text, kind ->
                musicAddOpen = false
                reportMusicImport(importWithLxRetry(splitScripts(text), kind))
            },
        )
    }

    if (fallbackDialogOpen) {
        LiveFallbackDialog(
            initialUrl = fallbackLiveUrl,
            onDismiss = { fallbackDialogOpen = false },
            onSave = { url ->
                vm.setFallbackLiveUrl(url)
                fallbackDialogOpen = false
            },
        )
    }

    val pending = pendingSwitch
    if (pending != null) {
        AVBoxAlertDialog(
            onDismissRequest = { vm.cancelPendingSwitch() },
            title = { Text(stringResource(R.string.dialog_source_disabled_title)) },
            text = {
                Text(stringResource(R.string.dialog_source_disabled_message, pending.item.name))
            },
            confirmButton = {
                val dismissThen = LocalSheetDismissThen.current
                TextButton(onClick = { dismissThen { vm.enableAndSwitch() } }) {
                    Text(stringResource(R.string.dialog_source_disabled_confirm))
                }
            },
            dismissButton = {
                val dismissAnimated = LocalSheetDismiss.current
                TextButton(onClick = { dismissAnimated() }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (repoSheetOpen) {
        RepoSwitchSheet(
            entries = repoEntries,
            activeUrl = repoActiveUrl,
            disabledUrls = disabledUrls,
            onDismiss = { repoSheetOpen = false },
            onSelect = { url ->
                val name = HistoryHelper.getApiLineName(
                    repoEntries.firstOrNull { HistoryHelper.getApiLineUrl(it) == url }.orEmpty(),
                )
                vm.requestSwitch(SubscribeSource(name, url), isVod)
                if (vm.pendingSwitch.value != null) repoSheetOpen = false
            },
        )
    }

    if (visibilitySheetOpen) {
        SubVisibilitySheet(onDismiss = { visibilitySheetOpen = false })
    }

}

@Composable
private fun RepoSwitchSheet(
    entries: List<String>,
    activeUrl: String,
    disabledUrls: Set<String>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val dismissAnimated = LocalSheetDismiss.current
    var accepted by remember { mutableStateOf(false) }
    AVBoxBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.config_switch_repo),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        SettingsGroup(
            title = null,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            entries.forEachIndexed { index, entry ->
                val url = HistoryHelper.getApiLineUrl(entry)
                SettingsCard(
                    position = when {
                        entries.size <= 1 -> SettingsCardPosition.SINGLE
                        index == 0 -> SettingsCardPosition.FIRST
                        index == entries.size - 1 -> SettingsCardPosition.LAST
                        else -> SettingsCardPosition.MIDDLE
                    },
                    color = MaterialTheme.colorScheme.surfaceBright,
                ) {
                    SettingsOptionRow(
                        title = HistoryHelper.getApiLineName(entry),
                        selected = url == activeUrl,
                        onClick = onClick@{
                            if (accepted) return@onClick
                            accepted = true
                            if (url.isNotEmpty() && url != activeUrl) onSelect(url)
                            dismissAnimated()
                        },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (url in disabledUrls) {
                                    DisabledSourceTag()
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    text = url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 180.dp),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SubVisibilitySheet(onDismiss: () -> Unit) {
    // 73: the 72 settings-page "订阅显示" group, moved verbatim into
    // config management. Same KV keys (SubscribeVisibility), same
    // defaults (miniapp hidden, the rest shown), same semantics
    // (hide is not delete); only the location changed, so a 72
    // user's stored switch states survive the upgrade untouched.
    val hiddenModes by SubscribeVisibility.hiddenModes.collectAsState()
    AVBoxBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.sub_visibility_title),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        SettingsGroup(
            title = null,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            SettingsCard(SettingsCardPosition.FIRST) {
                SettingsSwitchRow(
                    title = stringResource(R.string.common_vod),
                    checked = ConfigMode.Vod !in hiddenModes,
                    onCheckedChange = { SubscribeVisibility.setHidden(ConfigMode.Vod, !it) },
                    subtitle = stringResource(R.string.sub_visibility_hint),
                    leadingIconRes = R.drawable.ic_config_vod,
                )
            }
            SettingsCard(SettingsCardPosition.MIDDLE) {
                SettingsSwitchRow(
                    title = stringResource(R.string.common_live),
                    checked = ConfigMode.Live !in hiddenModes,
                    onCheckedChange = { SubscribeVisibility.setHidden(ConfigMode.Live, !it) },
                    subtitle = stringResource(R.string.sub_visibility_hint),
                    leadingIconRes = R.drawable.ic_config_live,
                )
            }
            SettingsCard(SettingsCardPosition.MIDDLE) {
                SettingsSwitchRow(
                    title = stringResource(R.string.reading_tab),
                    checked = ConfigMode.Reading !in hiddenModes,
                    onCheckedChange = { SubscribeVisibility.setHidden(ConfigMode.Reading, !it) },
                    subtitle = stringResource(R.string.sub_visibility_hint),
                    leadingIconRes = R.drawable.ic_config_reading,
                )
            }
            SettingsCard(SettingsCardPosition.MIDDLE) {
                SettingsSwitchRow(
                    title = stringResource(R.string.miniapp_tab),
                    checked = ConfigMode.MiniApp !in hiddenModes,
                    onCheckedChange = { SubscribeVisibility.setHidden(ConfigMode.MiniApp, !it) },
                    subtitle = stringResource(R.string.sub_visibility_hint),
                    leadingIconRes = R.drawable.ic_config_miniapp,
                )
            }
            SettingsCard(SettingsCardPosition.LAST) {
                SettingsSwitchRow(
                    title = stringResource(R.string.music_tab),
                    checked = ConfigMode.Music !in hiddenModes,
                    onCheckedChange = { SubscribeVisibility.setHidden(ConfigMode.Music, !it) },
                    subtitle = stringResource(R.string.sub_visibility_hint),
                    leadingIconRes = R.drawable.ic_config_music,
                )
            }
        }
    }
}

@Composable
private fun FollowVodCard(
    checked: Boolean,
    subtitle: String,
    onFollow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsCard(position = SettingsCardPosition.SINGLE, modifier = modifier) {
        SettingsSwitchRow(
            title = stringResource(R.string.live_follow_vod_source),
            leadingIconRes = R.drawable.ic_subscribe_source,
            subtitle = subtitle,
            checked = checked,
            onCheckedChange = { next -> if (next) onFollow() },
        )
    }
}

@Composable
private fun SubscribeCard(
    item: SubscribeSource,
    active: Boolean,
    disabled: Boolean,
    manageMode: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
) {
    val animFactor = LocalAnimFactor.current
    val shape = appCardShape(28.dp)
    // 3.17：订阅卡跟胶囊主题走（28dp 圆角不变；总开关关时回原版）。
    val capsuleOn = !ControlThemeStore.shouldDrawOriginalStyle()
    val capGloss = capsuleGroupWaterGloss()
    Surface(
        modifier = modifier.fillMaxWidth().then(
            if (capsuleOn && capGloss > 0) Modifier.waterGlossSheen(shape, capGloss / 200f) else Modifier,
        ),
        shape = shape,
        // logtest43: subscription card follows the 透明背景 slider.
        color = if (capsuleOn) capsuleGroupBaseColor().copy(alpha = 0.15f) else wallpaperAwareContainerColor(MaterialTheme.colorScheme.cardContainer),
        border = if (capsuleOn) BorderStroke(1.dp, capsuleGroupAccentColor().copy(alpha = 0.35f)) else null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowLeadingIcon(R.drawable.ic_subscribe_source, enabled = true)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (disabled) {
                        Spacer(Modifier.width(8.dp))
                        DisabledSourceTag()
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.url,
                    style = MaterialTheme.typography.bodyMedium,
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
                label = "configRowControl",
            ) { managing ->
                if (managing) {
                    Checkbox(checked = selected, onCheckedChange = { onClick() })
                } else {
                    SettingsSwitch(checked = active, onCheckedChange = onCheckedChange)
                }
            }
        }
    }
}

@Composable
private fun DisabledSourceTag() {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            text = stringResource(R.string.config_source_disabled_tag),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun AddSubscribeDialog(
    title: String,
    urlSupportingText: String,
    initialName: String,
    initialUrl: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onPickFile: (onPicked: (String) -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var url by remember { mutableStateOf(initialUrl) }
    val urlHint: (@Composable () -> Unit)? = if (urlSupportingText.isEmpty()) {
        null
    } else {
        {
            Text(
                text = urlSupportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    text = title,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .glassSurface(CircleShape, MaterialTheme.colorScheme.surfaceBright)
                        .clickable { onPickFile { picked -> url = picked } },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_file_choose),
                        contentDescription = stringResource(R.string.config_pick_local),
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
                    supportingText = urlHint,
                )
            }
        },
        confirmButton = {
            val dismissThen = LocalSheetDismissThen.current
            TextButton(
                onClick = { dismissThen { onSave(name.trim(), url.trim()) } },
                enabled = url.isNotBlank(),
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = null,
    )
}

@Composable
private fun FallbackLiveCard(
    url: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = appCardShape(28.dp)
    // 3.17：直播卡跟胶囊主题走（28dp 圆角不变；总开关关时回原版）。
    val capsuleOn = !ControlThemeStore.shouldDrawOriginalStyle()
    val capGloss = capsuleGroupWaterGloss()
    Surface(
        modifier = modifier.fillMaxWidth().then(
            if (capsuleOn && capGloss > 0) Modifier.waterGlossSheen(shape, capGloss / 200f) else Modifier,
        ),
        shape = shape,
        // logtest43: fallback live card follows the 透明背景 slider.
        color = if (capsuleOn) capsuleGroupBaseColor().copy(alpha = 0.15f) else wallpaperAwareContainerColor(MaterialTheme.colorScheme.cardContainer),
        border = if (capsuleOn) BorderStroke(1.dp, capsuleGroupAccentColor().copy(alpha = 0.35f)) else null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowLeadingIcon(R.drawable.ic_config_live, enabled = true)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.config_live_fallback_url),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = url.ifEmpty { stringResource(R.string.config_live_fallback_unset) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LiveFallbackDialog(
    initialUrl: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var url by remember { mutableStateOf(initialUrl) }
    AVBoxAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.config_live_fallback_edit)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.config_live_fallback_url)) },
                    supportingText = {
                        Text(
                            text = stringResource(R.string.config_live_fallback_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        },
        confirmButton = {
            val dismissThen = LocalSheetDismissThen.current
            TextButton(onClick = { dismissThen { onSave(url.trim()) } }) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            val dismissAnimated = LocalSheetDismiss.current
            TextButton(onClick = { dismissAnimated() }) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
