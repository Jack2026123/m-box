@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.github.tvbox.osc.ui.page

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.FileProvider
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.miniapp.engine.MiniAppPlayerActivity
import com.github.tvbox.osc.util.thunder.Thunder
import com.github.tvbox.osc.io.FileUtils
import com.github.tvbox.osc.net.OkGoHelper
import com.github.tvbox.osc.player.effect.anime4k.Anime4kTier
import com.github.tvbox.osc.ui.activity.ConfigManageActivity
import com.github.tvbox.osc.ui.activity.PlaySettingsActivity
import com.github.tvbox.osc.ui.activity.PreferenceSettingsActivity
import com.github.tvbox.osc.ui.dialog.AiAssistPanel
import com.github.tvbox.osc.ui.dialog.LocalSourceDialog
import com.github.tvbox.osc.api.LocalSource
import com.github.tvbox.osc.util.Notify
import com.github.tvbox.osc.ui.activity.ThemeSettingsActivity
import com.github.tvbox.osc.ui.activity.TmdbConfigActivity
import com.github.tvbox.osc.ui.activity.WebSourceManageActivity
import com.github.tvbox.osc.ui.components.AVBoxAlertDialog
import com.github.tvbox.osc.ui.components.AVBoxBottomSheet
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.LocalSheetDismissThen
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.SettingsIconBadge
import com.github.tvbox.osc.ui.components.wallpaperAwarePageBackground
import com.github.tvbox.osc.ui.components.SettingsOptionMenuRow
import com.github.tvbox.osc.ui.components.SettingsRow
import com.github.tvbox.osc.ui.components.SettingsSwitch
import com.github.tvbox.osc.ui.components.SettingsSwitchRow
import com.github.tvbox.osc.util.AppReset
import com.github.tvbox.osc.util.DataBackup
import com.github.tvbox.osc.util.FloatLog
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.HistoryMerge
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.MusicSettings
import com.github.tvbox.osc.util.PermissionHelper
import com.github.tvbox.osc.util.SpiderStorageNotice
import com.github.tvbox.osc.util.TmdbPoster
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CACHE_SIZE_REFRESH_MIN_INTERVAL_MS = 60_000L

data class SettingsState(
    val playType: Int,
    val playRender: Int,
    val playScale: Int,
    val exoDecode: String,
    val anime4kTier: Anime4kTier,
    val kernelPrewarm: Boolean,
    val playTunnel: Boolean,
    val preferAac: Boolean,
    val musicPlayerPage: Boolean,
    val autoSwitchLine: Boolean,
    val m3u8Purify: Boolean,
    val incognito: Boolean,
    val hideStatusBar: Boolean,
    val gestureControlDisabled: Boolean,
    val navAnimationDisabled: Boolean,
    val navLiveHidden: Boolean,
    val collectColumns: Int,
    val screenOrientation: Int,
    val danmuOpen: Boolean,
    val subtitleEnabled: Boolean,
    val subtitleSource: String,
    val subtitleAssrtToken: String,
    val subtitleAutoMatch: Boolean,
    val subtitleAutoLang: String,
    val danmuApi: String,
    val defaultLoadLive: Boolean,
    val historyNumIndex: Int,
    val historyMerge: Boolean,
    val searchThreads: Int,
    val longPressSpeed: Int,
    val bufferTimes: Int,
    val preloadNextEpisode: Boolean,
    val preloadDuration: Int,
    val playCache: Boolean,
    val exoCacheSizeMb: Int,
    val dohIndex: Int,
    val cacheSizeText: String = "",
)

class SettingsViewModel : ViewModel() {
    private var cacheSizeText: String = ""
    private var cacheSizeRefreshedAt = 0L

    private val _state = mutableStateOf(loadState())

    init {
        refresh()
    }

    val state: androidx.compose.runtime.State<SettingsState> = _state

    fun refresh() {
        _state.value = loadState()
        refreshCacheSize()
    }

    fun refreshState() {
        _state.value = loadState()
    }

    fun refreshCacheSize() {
        cacheSizeRefreshedAt = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { FileUtils.formatCacheSize(FileUtils.getCacheSize()) }
            applyCacheSizeText(text)
        }
    }

    fun refreshCacheSizeIfStale() {
        if (SystemClock.elapsedRealtime() - cacheSizeRefreshedAt < CACHE_SIZE_REFRESH_MIN_INTERVAL_MS) return
        refreshCacheSize()
    }

    fun clearCache(onCleared: () -> Unit = {}) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                FileUtils.clearCache()
                TmdbPoster.clearCachedPosters()
                FileUtils.formatCacheSize(FileUtils.getCacheSize())
            }
            applyCacheSizeText(text)
            onCleared()
        }
    }

    fun createDataBackup(onResult: (DataBackup.Failure?, String?, File?) -> Unit) {
        viewModelScope.launch {
            var detail: String? = null
            var saved: File? = null
            val failure = withContext(Dispatchers.IO) {
                try {
                    saved = DataBackup.create()
                    null
                } catch (e: DataBackup.BackupException) {
                    detail = e.cause?.message ?: e.message
                    e.failure
                } catch (e: Throwable) {
                    LOG.e("SettingsBackup", "create backup failed", e)
                    detail = e.message ?: e.javaClass.simpleName
                    DataBackup.Failure.IO
                }
            }
            onResult(failure, detail, saved)
        }
    }

    fun restoreDataBackup(file: File, onResult: (DataBackup.Failure?) -> Unit) {
        viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) {
                try {
                    DataBackup.restore(file)
                    null
                } catch (e: DataBackup.BackupException) {
                    LOG.e("SettingsBackup", "restore rejected, file=" + file.absolutePath, e)
                    e.failure
                } catch (e: Throwable) {
                    LOG.e("SettingsBackup", "restore failed, file=" + file.absolutePath, e)
                    DataBackup.Failure.INVALID
                }
            }
            if (failure == null) {
                reloadAfterDataRestore()
                refresh()
            }
            onResult(failure)
        }
    }

    fun deleteDataBackup(file: File, onResult: (List<File>) -> Unit) {
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) {
                DataBackup.delete(file)
                DataBackup.listBackups()
            }
            onResult(files)
        }
    }

    private fun reloadAfterDataRestore() {
        val api = KV.get(HawkConfig.API_URL, "")
        ApiConfig.get().clearLiveHosts()
        ApiConfig.get().invalidateLiveConfig()
        if (api.isNotEmpty()) AppBootstrap.onApiUrlChanged() else AppBootstrap.retry()
    }

    private fun applyCacheSizeText(text: String) {
        if (text != cacheSizeText) {
            cacheSizeText = text
            _state.value = _state.value.copy(cacheSizeText = text)
        }
    }

    private fun loadState(): SettingsState = SettingsState(
        playType = KV.get(HawkConfig.PLAY_TYPE, 2),
        playRender = KV.get(HawkConfig.PLAY_RENDER, 1),
        playScale = KV.get(HawkConfig.PLAY_SCALE, 0),
        exoDecode = KV.get(HawkConfig.EXO_DECODE, "硬解码"), // i18n: keep
        anime4kTier = Anime4kTier.current(),
        kernelPrewarm = KV.get(HawkConfig.KERNEL_PREWARM, HawkConfig.KERNEL_PREWARM_DEFAULT),
        playTunnel = KV.get(HawkConfig.PLAY_TUNNEL, false),
        preferAac = KV.get(HawkConfig.PLAY_PREFER_AAC, false),
        musicPlayerPage = MusicSettings.autoOpenPage(),
        autoSwitchLine = KV.get(HawkConfig.AUTO_SWITCH_LINE, HawkConfig.AUTO_SWITCH_LINE_DEFAULT),
        m3u8Purify = KV.get(HawkConfig.M3U8_PURIFY, false),
        incognito = KV.get(HawkConfig.INCOGNITO, false),
        hideStatusBar = KV.get(HawkConfig.HIDE_STATUS_BAR, false),
        gestureControlDisabled = KV.get(HawkConfig.GESTURE_CONTROL_DISABLED, false),
        navAnimationDisabled = KV.get(HawkConfig.NAV_ANIMATION_DISABLED, false),
        navLiveHidden = KV.get(HawkConfig.NAV_LIVE_HIDDEN, false),
        collectColumns = KV.get(HawkConfig.COLLECT_COLUMNS, 3),
        screenOrientation = KV.get(
            HawkConfig.SCREEN_ORIENTATION,
            com.github.tvbox.osc.util.OrientationSetting.FOLLOW_SYSTEM,
        ),
        danmuOpen = KV.get(HawkConfig.DANMU_OPEN, false),
        subtitleEnabled = KV.get(HawkConfig.SUBTITLE_ENABLED, false),
        subtitleSource = KV.get(HawkConfig.SUBTITLE_SOURCE, "assrt"),
        subtitleAssrtToken = KV.get(HawkConfig.SUBTITLE_ASSRT_TOKEN, ""),
        subtitleAutoMatch = KV.get(HawkConfig.SUBTITLE_AUTO_MATCH, true),
        subtitleAutoLang = KV.get(HawkConfig.SUBTITLE_AUTO_LANG, ""),
        danmuApi = KV.get(HawkConfig.DANMU_API, ""),
        defaultLoadLive = KV.get(HawkConfig.DEFAULT_LOAD_LIVE, false),
        historyNumIndex = KV.get(HawkConfig.HISTORY_NUM, 0),
        historyMerge = HistoryMerge.isEnabled(),
        searchThreads = KV.get(HawkConfig.SEARCH_THREADS, HawkConfig.SEARCH_THREADS_DEFAULT),
        longPressSpeed = KV.get(HawkConfig.LONG_PRESS_SPEED, HawkConfig.LONG_PRESS_SPEED_DEFAULT),
        bufferTimes = KV.get(HawkConfig.BUFFER_TIMES, HawkConfig.BUFFER_TIMES_DEFAULT),
        preloadNextEpisode = KV.get(HawkConfig.PRELOAD_NEXT_EPISODE, false),
        preloadDuration = KV.get(HawkConfig.PRELOAD_DURATION, HawkConfig.PRELOAD_DURATION_DEFAULT),
        playCache = KV.get(HawkConfig.PLAY_CACHE, false),
        exoCacheSizeMb = KV.get(HawkConfig.EXO_CACHE_SIZE_MB, HawkConfig.EXO_CACHE_SIZE_MB_DEFAULT),
        dohIndex = KV.get(HawkConfig.DOH_URL, 0),
        cacheSizeText = cacheSizeText,
    )

    fun <T> put(key: String, value: T) {
        KV.put(key, value)
        refresh()
    }
}

@Composable
fun SettingsPage(
    vm: SettingsViewModel = viewModel(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val navStart = contentPadding.calculateStartPadding(LocalLayoutDirection.current)
    val navBottom = contentPadding.calculateBottomPadding()
    val state by vm.state
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refreshState()
        vm.refreshCacheSizeIfStale()
    }
    val context = LocalContext.current
    val versionName = remember { DefaultConfig.getAppVersionName(context) ?: "" }
    var restoreSheet by remember { mutableStateOf(false) }
    var smartDownloadOpen by remember { mutableStateOf(false) }
    var linkPlayOpen by remember { mutableStateOf(false) }
    var linkPlayPicks by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    val downloadTasks by SmartDownloadManager.state.collectAsState()
    // 心情便签: the bottom slogan line is user-editable; the custom text
    // persists in KV. Mod 1.0.1: the install-package default is blank,
    // so a fresh install shows an empty line until the user types one;
    // a previously saved custom value is shown as-is and never overwritten.
    var sloganCustom by remember { mutableStateOf(KV.get(HawkConfig.SETTINGS_SLOGAN_TEXT, "")) }
    var sloganEditOpen by remember { mutableStateOf(false) }
    val sloganText = sloganCustom
    var floatLogEnabled by remember { mutableStateOf(FloatLog.isEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        floatLogEnabled = FloatLog.isEnabled()
    }
    var showFloatLogExport by remember { mutableStateOf(false) }
    val floatLogExportScope = rememberCoroutineScope()
    var backupFiles by remember { mutableStateOf<List<File>>(emptyList()) }

    LaunchedEffect(restoreSheet) {
        if (restoreSheet) {
            backupFiles = withContext(Dispatchers.IO) { DataBackup.listBackups() }
        }
    }

    fun withStoragePermission(action: (granted: Boolean) -> Unit) {
        // 与 Vesper 同款时机：点击时先查「所有文件访问」授权（SDK>=R 即
        // Environment.isExternalStorageManager()），未授权由 XXPermissions
        // 跳本包的系统授权页；返回后把结果交给调用方，未授权不再直接
        // 拒绝结束，调用方走应用专属目录兜底并提示用户。
        if (PermissionHelper.isStorageGranted(context)) {
            action(true)
            return
        }
        val activity = context as? Activity
        if (activity == null) {
            action(false)
            return
        }
        PermissionHelper.requestStorage(activity) { _, _ ->
            action(PermissionHelper.isStorageGranted(context))
        }
    }

    val listState = rememberScrollState()

    AppTopBarScaffold(
        topBarStartInset = navStart,
        // logtest43: page alpha follows the 透明背景 slider while a
        // wallpaper is linked (0 = opaque, 40 = default, 100 =
        // fully transparent); opaque default without a wallpaper.
        containerColor = wallpaperAwarePageBackground(MaterialTheme.colorScheme.surfaceContainer),
        titleContent = {
            Text(
                text = stringResource(R.string.tab_mine),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
    ) { topPad, _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(listState)
                .padding(horizontal = 16.dp)
                .padding(start = navStart, bottom = 8.dp + navBottom),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Spacer(Modifier.height(topPad - 20.dp))

            AppInfoHeaderCard(versionName = versionName)

            SettingsGroup(title = null) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsRow(
                        title = stringResource(R.string.settings_config_manage),
                        subtitle = stringResource(R.string.settings_config_manage_subtitle),
                        iconRes = R.drawable.ic_settings_api,
                        onClick = { ConfigManageActivity.start(context) },
                    )
                }
                // logtest43: TMDB moved out of the ConfigManage tabs
                // into its own row directly below 配置管理.
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = stringResource(R.string.common_tmdb),
                        subtitle = stringResource(R.string.tmdb_row_subtitle),
                        iconRes = R.drawable.ic_config_tmdb,
                        onClick = { TmdbConfigActivity.start(context) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = stringResource(R.string.settings_theme),
                        subtitle = stringResource(R.string.settings_theme_subtitle),
                        iconRes = R.drawable.ic_settings_theme,
                        onClick = { ThemeSettingsActivity.start(context) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = stringResource(R.string.settings_play),
                        subtitle = stringResource(R.string.settings_play_subtitle),
                        iconRes = R.drawable.ic_settings_play,
                        onClick = { PlaySettingsActivity.start(context) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = stringResource(R.string.settings_preference_title),
                        subtitle = stringResource(R.string.settings_preference_subtitle),
                        iconRes = R.drawable.ic_settings_preference,
                        onClick = { PreferenceSettingsActivity.start(context) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = stringResource(R.string.local_source_title),
                        subtitle = stringResource(R.string.local_source_desc),
                        iconRes = R.drawable.ic_settings_api,
                        onClick = { LocalSourceDialog.show(context) { } },
                        onIconDoubleClick = {
                            if (LocalSource.stringList(LocalSource.loadSettings(), "scan_dirs").isEmpty()) {
                                Notify.show(context.getString(R.string.local_source_dir_required))
                            } else {
                                LocalSourceDialog.scanAndLoad(context) { }
                            }
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = "网页源",
                        subtitle = "输入网址直接打开网页，同时存成源",
                        iconRes = R.drawable.ic_web_source,
                        onClick = {
                            WebSourceManageActivity.start(context)
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsRow(
                        title = "AI辅助",
                        subtitle = "写源、修源、技能包",
                        iconRes = R.drawable.ic_edit,
                        onClick = {
                            AiAssistPanel.show(context)
                        },
                    )
                }
            }

            // 73: 订阅显示 moved to ConfigManagePage (top-bar sheet);
            // nothing remains here, so the switches live in one place only.

            SettingsGroup(title = null) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.settings_default_page),
                        subtitle = stringResource(R.string.settings_default_page_subtitle),
                        iconRes = R.drawable.ic_settings_start,
                        valueText = stringResource(if (state.defaultLoadLive) R.string.common_live else R.string.common_vod),
                        options = listOf(
                            stringResource(R.string.common_vod),
                            stringResource(R.string.common_live),
                        ),
                        selectedIndex = if (state.defaultLoadLive) 1 else 0,
                        onSelect = { idx -> vm.put(HawkConfig.DEFAULT_LOAD_LIVE, idx == 1) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.settings_history_limit),
                        subtitle = stringResource(R.string.settings_history_limit_subtitle),
                        iconRes = R.drawable.ic_settings_history,
                        valueText = stringResource(
                            R.string.settings_history_limit_value,
                            HistoryHelper.getHisNum(state.historyNumIndex),
                        ),
                        options = listOf(0, 1, 2).map {
                            stringResource(R.string.settings_history_limit_value, HistoryHelper.getHisNum(it))
                        },
                        selectedIndex = state.historyNumIndex,
                        onSelect = { idx -> vm.put(HawkConfig.HISTORY_NUM, idx) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    var showClearCacheDialog by rememberSaveable { mutableStateOf(false) }
                    SettingsRow(
                        title = stringResource(R.string.settings_clear_cache),
                        subtitle = stringResource(R.string.settings_clear_cache_subtitle),
                        iconRes = R.drawable.ic_delete,
                        valueText = state.cacheSizeText,
                        onClick = { showClearCacheDialog = true },
                    )
                    if (showClearCacheDialog) {
                        val dismissThen = LocalSheetDismissThen.current
                        AVBoxAlertDialog(
                            onDismissRequest = { showClearCacheDialog = false },
                            title = { Text(stringResource(R.string.settings_clear_cache)) },
                            text = { Text(stringResource(R.string.settings_clear_cache_confirm_text)) },
                            dismissButton = {
                                TextButton(onClick = { showClearCacheDialog = false }) {
                                    Text(stringResource(R.string.common_cancel))
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showClearCacheDialog = false
                                    dismissThen {
                                        vm.clearCache {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.toast_cache_cleared),
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    }
                                }) {
                                    Text(stringResource(R.string.common_confirm))
                                }
                            },
                        )
                    }
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsOptionMenuRow(
                        title = "DOH",
                        subtitle = stringResource(R.string.settings_doh_subtitle),
                        iconRes = R.drawable.ic_settings_doh,
                        valueText = if (state.dohIndex == 0) stringResource(R.string.common_off)
                        else OkGoHelper.dnsHttpsList.getOrNull(state.dohIndex)
                            ?: stringResource(R.string.common_off),
                        options = OkGoHelper.dnsHttpsList.mapIndexed { index, name ->
                            if (index == 0) context.getString(R.string.common_off) else name
                        },
                        selectedIndex = state.dohIndex,
                        onSelect = { idx -> vm.put(HawkConfig.DOH_URL, idx) },
                    )
                }
            }

            SettingsGroup(title = null) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsRow(
                        title = stringResource(R.string.setting_backup),
                        subtitle = stringResource(R.string.settings_backup_subtitle),
                        iconRes = R.drawable.ic_settings_backup,
                        onClick = {
                            withStoragePermission { granted ->
                                SpiderStorageNotice.suppress()
                                vm.createDataBackup { failure, detail, saved ->
                                    var text = if (failure == null &&
                                        !granted &&
                                        saved != null &&
                                        !DataBackup.isSharedBackup(saved)
                                    ) {
                                        // 用户没给「所有文件访问」授权，备份回落到了
                                        // 应用专属目录，要明确告诉用户存去了哪里。
                                        context.getString(R.string.backup_success_no_permission)
                                    } else {
                                        val msg = when (failure) {
                                            null -> R.string.backup_success
                                            DataBackup.Failure.NO_CONFIG -> R.string.backup_no_config
                                            else -> R.string.backup_fail
                                        }
                                        context.getString(msg)
                                    }
                                    if (failure != null &&
                                        failure != DataBackup.Failure.NO_CONFIG &&
                                        !detail.isNullOrBlank()
                                    ) {
                                        text += "：" + detail
                                    }
                                    Toast.makeText(
                                        context,
                                        text,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = stringResource(R.string.setting_restore),
                        subtitle = stringResource(R.string.settings_restore_subtitle),
                        iconRes = R.drawable.ic_settings_restore,
                        onClick = {
                            withStoragePermission {
                                SpiderStorageNotice.suppress()
                                restoreSheet = true
                            }
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .combinedClickable(
                                onClick = {
                                    // 1.38：点行开页签式日志面板（未开启时先开启再开面板）；
                                    // 开关本身仍是开关（点开关只切换，不开面板）。
                                    if (!floatLogEnabled) {
                                        floatLogEnabled = true
                                        FloatLog.setEnabled(true)
                                    }
                                    FloatLog.openPanel()
                                },
                                onLongClick = { showFloatLogExport = true },
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsIconBadge(R.drawable.ic_settings_log, stringResource(R.string.setting_float_log))
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.setting_float_log),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.settings_float_log_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        SettingsSwitch(
                            checked = floatLogEnabled,
                            onCheckedChange = { next ->
                                floatLogEnabled = next
                                FloatLog.setEnabled(next)
                            },
                        )
                    }
                    if (showFloatLogExport) {
                        val exportCount = FloatLog.snapshot().size
                        AVBoxAlertDialog(
                            onDismissRequest = { showFloatLogExport = false },
                            title = { Text(stringResource(R.string.float_log_export_title)) },
                            text = { Text(stringResource(R.string.float_log_export_text, exportCount)) },
                            dismissButton = {
                                TextButton(onClick = { showFloatLogExport = false }) {
                                    Text(stringResource(R.string.common_cancel))
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showFloatLogExport = false
                                    floatLogExportScope.launch {
                                        val file = withContext(Dispatchers.IO) {
                                            try {
                                                val base = context.getExternalFilesDir(null) ?: return@withContext null
                                                val dir = File(base, "logs")
                                                if (!dir.exists()) dir.mkdirs()
                                                val name = "avbox_log_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".txt"
                                                val out = File(dir, name)
                                                out.writeText(FloatLog.exportText(), Charsets.UTF_8)
                                                out
                                            } catch (th: Throwable) {
                                                null
                                            }
                                        }
                                        if (file == null) {
                                            Toast.makeText(context, context.getString(R.string.float_log_export_failed), Toast.LENGTH_LONG).show()
                                        } else {
                                            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                                            val share = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(share, context.getString(R.string.float_log_share_title)))
                                            Toast.makeText(context, context.getString(R.string.float_log_exported, file.absolutePath), Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }) {
                                    Text(stringResource(R.string.float_log_export_action))
                                }
                            },
                        )
                    }
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    var showResetDialog by rememberSaveable { mutableStateOf(false) }
                    SettingsRow(
                        title = stringResource(R.string.setting_reset_app),
                        iconRes = R.drawable.ic_delete,
                        valueText = stringResource(R.string.setting_reset_action),
                        onClick = { showResetDialog = true },
                    )
                    if (showResetDialog) {
                        AVBoxAlertDialog(
                            onDismissRequest = { showResetDialog = false },
                            title = { Text(stringResource(R.string.setting_reset_app)) },
                            text = { Text(stringResource(R.string.settings_reset_app_confirm_text)) },
                            dismissButton = {
                                TextButton(onClick = { showResetDialog = false }) {
                                    Text(stringResource(R.string.common_cancel))
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showResetDialog = false
                                    AppReset.wipeAndRestart(context)
                                }) {
                                    Text(stringResource(R.string.setting_reset_confirm))
                                }
                            },
                        )
                    }
                }
            }

            SettingsGroup(title = null) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    val downloadingItems = downloadTasks.filterIsInstance<SmartDownloadState.Downloading>()
                    val downloadingCount = downloadingItems.size
                    val pausedCount = downloadTasks.count { it is SmartDownloadState.Paused }
                    val totalDownloaded = downloadingItems.sumOf { it.downloaded }
                    val totalSize = downloadingItems.sumOf { it.total }
                    val downloadValueText = when {
                        downloadingCount > 0 -> {
                            stringResource(R.string.settings_smart_download_downloading_count, downloadingCount)
                        }
                        pausedCount > 0 -> stringResource(R.string.settings_smart_download_paused_count, pausedCount)
                        else -> null
                    }
                    val downloadProgress = if (downloadingCount > 0) {
                        if (totalSize > 0) {
                            totalDownloaded.toFloat() / totalSize.toFloat()
                        } else {
                            0f
                        }
                    } else {
                        null
                    }
                    SettingsRow(
                        title = stringResource(R.string.settings_smart_download),
                        subtitle = stringResource(R.string.settings_smart_download_subtitle),
                        valueText = downloadValueText,
                        valueSingleLine = true,
                        valueWeight = 1.5f,
                        valueProgress = downloadProgress,
                        iconRes = R.drawable.ic_file_choose,
                        onClick = { smartDownloadOpen = true },
                    )
                }

                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = stringResource(R.string.settings_link_play),
                        subtitle = stringResource(R.string.settings_link_play_subtitle),
                        iconRes = R.drawable.ic_order_play,
                        onClick = { linkPlayOpen = true },
                    )
                }

                SettingsCard(SettingsCardPosition.LAST) {
                    // 心情便签：标题固定“心情便签”，副标题“自定义心情标语”，
                    // 图标用 ✍️ emoji（SettingsEmojiBadge，同款圆角底）；
                    // 点开编辑标语（2.69）。
                    SettingsRow(
                        title = stringResource(R.string.settings_slogan_title),
                        subtitle = stringResource(R.string.settings_slogan_subtitle),
                        emojiIcon = "✍️",
                        onClick = { sloganEditOpen = true },
                    )
                }
            }
        }
    }

    val linkPlayMainHandler = remember { Handler(Looper.getMainLooper()) }
    fun linkPlayToast(msg: String) {
        linkPlayMainHandler.post { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    }
    fun linkPlayLaunch(playUrl: String, title: String) {
        linkPlayMainHandler.post {
            val intent = Intent(context, MiniAppPlayerActivity::class.java)
            intent.putExtra(MiniAppPlayerActivity.EXTRA_URL, playUrl)
            intent.putExtra(MiniAppPlayerActivity.EXTRA_TITLE, title)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
    fun linkPlayThunderCallback(title: String) = object : Thunder.ThunderCallback {
        override fun status(code: Int, info: String) {
            if (code < 0) linkPlayToast(info)
        }

        override fun list(urlMap: MutableMap<Int, String>) {
        }

        override fun play(url: String) {
            linkPlayLaunch(url, title)
        }
    }
    fun playLink(raw: String) {
        val url = raw.trim()
        if (url.isEmpty()) {
            linkPlayToast(context.getString(R.string.settings_link_play_invalid))
            return
        }
        val lower = url.lowercase()
        val isDirectMedia = listOf(".m3u8", ".mp4", ".mkv", ".mov", ".webm", ".m4v", ".avi", ".flv")
            .any { lower.contains(it) }
        val thunderUrl = Thunder.isSupportUrl(url) || Thunder.isFtp(url) || lower.startsWith("ed2k:")
        if (!thunderUrl && !isDirectMedia) {
            linkPlayToast(context.getString(R.string.settings_link_play_invalid))
            return
        }
        if (isDirectMedia && !thunderUrl) {
            linkPlayLaunch(url, "")
            return
        }
        if (Thunder.isFtp(url) || lower.startsWith("ed2k:")) {
            if (!Thunder.play(url, linkPlayThunderCallback(""))) {
                linkPlayToast(context.getString(R.string.settings_link_play_invalid))
            }
            return
        }
        linkPlayToast(context.getString(R.string.settings_link_play_resolving))
        val bean = Movie.Video.UrlBean.UrlInfo.InfoBean("", url)
        val info = Movie.Video.UrlBean.UrlInfo()
        info.beanList = mutableListOf(bean)
        val urlBean = Movie.Video.UrlBean()
        urlBean.infoList = mutableListOf(info)
        Thunder.parse(context, urlBean, object : Thunder.ThunderCallback {
            override fun status(code: Int, info: String) {
                if (code < 0) linkPlayToast(info)
            }

            override fun list(urlMap: MutableMap<Int, String>) {
                val entries = urlMap.values.firstOrNull()?.split("#").orEmpty().mapNotNull { entry ->
                    val idx = entry.lastIndexOf("$")
                    if (idx < 0) null else Pair(entry.substring(0, idx), entry.substring(idx + 1))
                }
                if (entries.isEmpty()) {
                    linkPlayToast(context.getString(R.string.settings_link_play_invalid))
                    return
                }
                if (entries.size == 1) {
                    Thunder.play(entries[0].second, linkPlayThunderCallback(entries[0].first))
                } else {
                    linkPlayMainHandler.post { linkPlayPicks = entries }
                }
            }

            override fun play(url: String) {
            }
        })
    }

    if (smartDownloadOpen) {
        SmartDownloadDialog(
            onDismiss = { smartDownloadOpen = false },
            tasks = downloadTasks,
            onStartDownload = { url, fileName, treeUri ->
                SmartDownloadManager.start(context, url, fileName, treeUri)
            },
            onPauseDownload = { id -> SmartDownloadManager.pause(id) },
            onResumeDownload = { id -> SmartDownloadManager.resume(context, id) },
            onCancelDownload = { id -> SmartDownloadManager.cancel(context, id) },
            onClearDownload = { id -> SmartDownloadManager.clear(id) },
        )
    }

    if (linkPlayOpen) {
        LinkPlayDialog(
            onDismiss = { linkPlayOpen = false },
            onPlay = { url -> playLink(url) },
        )
    }

    val picks = linkPlayPicks
    if (picks != null) {
        AVBoxAlertDialog(
            onDismissRequest = { linkPlayPicks = null },
            title = { Text(stringResource(R.string.settings_link_play_pick)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    picks.forEach { (name, playUrl) ->
                        TextButton(onClick = {
                            linkPlayPicks = null
                            Thunder.play(playUrl, linkPlayThunderCallback(name))
                        }) {
                            Text(name)
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { linkPlayPicks = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            confirmButton = {
            },
        )
    }

    if (sloganEditOpen) {
        TextEditDialog(
            title = stringResource(R.string.settings_slogan_edit),
            initialText = sloganText,
            multiLine = true,
            onDismiss = { sloganEditOpen = false },
            onConfirm = { value ->
                KV.put(HawkConfig.SETTINGS_SLOGAN_TEXT, value)
                sloganCustom = value
                sloganEditOpen = false
            },
        )
    }

    if (restoreSheet) {
        AVBoxBottomSheet(
            onDismissRequest = { restoreSheet = false },
            title = stringResource(R.string.restore_select),
        ) {
            SettingsGroup(
                title = null,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                if (backupFiles.isEmpty()) {
                    SettingsCard(SettingsCardPosition.SINGLE) {
                        Text(
                            text = stringResource(R.string.backup_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                        )
                    }
                } else {
                    backupFiles.forEachIndexed { index, file ->
                        SettingsCard(
                            position = when {
                                backupFiles.size == 1 -> SettingsCardPosition.SINGLE
                                index == 0 -> SettingsCardPosition.FIRST
                                index == backupFiles.lastIndex -> SettingsCardPosition.LAST
                                else -> SettingsCardPosition.MIDDLE
                            },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        restoreSheet = false
                                        vm.restoreDataBackup(file) { failure ->
                                            Toast.makeText(
                                                context,
                                                context.getString(
                                                    when (failure) {
                                                        null -> R.string.restore_success
                                                        DataBackup.Failure.NOT_OURS ->
                                                            R.string.restore_not_avbox
                                                        else -> R.string.restore_fail
                                                    },
                                                ),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = file.name.removeSuffix(".tv"),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                    )
                                    Text(
                                        text = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
                                            .format(Date(file.lastModified())),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = {
                                    vm.deleteDataBackup(file) { files -> backupFiles = files }
                                }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_delete),
                                        contentDescription = stringResource(R.string.common_delete),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
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

fun formatSmartDownloadMB(bytes: Long): String {
    return "%.1f MB".format(java.util.Locale.US, bytes / 1048576.0)
}

fun formatSmartDownloadSpeed(bps: Long): String {
    return if (bps >= 1048576) {
        "%.1f MB/s".format(java.util.Locale.US, bps / 1048576.0)
    } else {
        "%.0f KB/s".format(java.util.Locale.US, bps / 1024.0)
    }
}

@Composable
private fun SmartDownloadDialog(
    onDismiss: () -> Unit,
    tasks: List<SmartDownloadState>,
    onStartDownload: (url: String, fileName: String, treeUri: Uri?) -> Unit,
    onPauseDownload: (id: Int) -> Unit,
    onResumeDownload: (id: Int) -> Unit,
    onCancelDownload: (id: Int) -> Unit,
    onClearDownload: (id: Int) -> Unit,
) {
    val context = LocalContext.current
    var link by rememberSaveable { mutableStateOf("") }
    var fileName by rememberSaveable { mutableStateOf("") }
    var fileNameEdited by rememberSaveable { mutableStateOf(false) }
    var treeUriString by rememberSaveable {
        mutableStateOf(KV.get(HawkConfig.SMART_DOWNLOAD_TREE_URI, ""))
    }
    var folderLabel by remember { mutableStateOf("Download/") }
    LaunchedEffect(treeUriString) {
        folderLabel = SmartDownload.resolveFolderLabel(context, treeUriString)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            KV.put(HawkConfig.SMART_DOWNLOAD_TREE_URI, uri.toString())
            treeUriString = uri.toString()
        }
    }
    val detectedUrls = SmartDownload.extractUrls(link)
    AVBoxAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_smart_download)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = link,
                    onValueChange = { value ->
                        link = value
                        val urls = SmartDownload.extractUrls(value)
                        if (urls.size == 1 && !fileNameEdited) {
                            fileName = SmartDownload.guessFileName(urls[0])
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    label = { Text(stringResource(R.string.settings_smart_download_link)) },
                    placeholder = { Text(stringResource(R.string.settings_smart_download_link_hint)) },
                )
                if (detectedUrls.size >= 2) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_smart_download_detected_count, detectedUrls.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = folderLabel,
                        onValueChange = {},
                        modifier = Modifier.weight(1f),
                        readOnly = true,
                        singleLine = true,
                        label = { Text(stringResource(R.string.settings_smart_download_folder)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { folderPicker.launch(null) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_file_choose),
                            contentDescription = stringResource(R.string.settings_smart_download_folder_pick),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    if (treeUriString.isNotEmpty()) {
                        TextButton(onClick = {
                            KV.delete(HawkConfig.SMART_DOWNLOAD_TREE_URI)
                            treeUriString = ""
                        }) {
                            Text(stringResource(R.string.settings_smart_download_folder_default))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { value ->
                        fileName = value
                        fileNameEdited = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_smart_download_filename)) },
                )
                if (tasks.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    tasks.forEach { item ->
                        SmartDownloadTaskRow(
                            item = item,
                            onPause = onPauseDownload,
                            onResume = onResumeDownload,
                            onCancel = onCancelDownload,
                            onClear = onClearDownload,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val urls = SmartDownload.extractUrls(link)
                if (urls.isEmpty()) {
                    Toast.makeText(context, context.getString(R.string.settings_smart_download_invalid_url), Toast.LENGTH_LONG).show()
                    return@TextButton
                }
                val active = tasks.count { it is SmartDownloadState.Downloading || it is SmartDownloadState.Paused }
                val room = (SmartDownloadManager.MAX_TASKS - active).coerceAtLeast(0)
                val toAdd = urls.take(room)
                if (toAdd.isEmpty()) {
                    Toast.makeText(context, context.getString(R.string.settings_smart_download_limit), Toast.LENGTH_LONG).show()
                    return@TextButton
                }
                val tree = treeUriString.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
                toAdd.forEach { url ->
                    val name = if (urls.size == 1) {
                        SmartDownload.sanitizeFileName(fileName.trim()).ifBlank { SmartDownload.guessFileName(url) }
                    } else {
                        SmartDownload.sanitizeFileName(SmartDownload.guessFileName(url))
                    }
                    onStartDownload(url, name, tree)
                }
                if (toAdd.size < urls.size) {
                    Toast.makeText(context, context.getString(R.string.settings_smart_download_limit), Toast.LENGTH_LONG).show()
                }
                link = ""
                fileName = ""
                fileNameEdited = false
            }) {
                Text(stringResource(R.string.settings_smart_download_save))
            }
        },
    )
}

@Composable
private fun SmartDownloadTaskRow(
    item: SmartDownloadState,
    onPause: (id: Int) -> Unit,
    onResume: (id: Int) -> Unit,
    onCancel: (id: Int) -> Unit,
    onClear: (id: Int) -> Unit,
) {
    val fileName = when (item) {
        is SmartDownloadState.Downloading -> item.fileName
        is SmartDownloadState.Paused -> item.fileName
        is SmartDownloadState.Done -> item.fileName
        is SmartDownloadState.Failed -> item.fileName
    }
    val downloaded = when (item) {
        is SmartDownloadState.Downloading -> item.downloaded
        is SmartDownloadState.Paused -> item.downloaded
        else -> 0L
    }
    val total = when (item) {
        is SmartDownloadState.Downloading -> item.total
        is SmartDownloadState.Paused -> item.total
        else -> -1L
    }
    val speedBps = when (item) {
        is SmartDownloadState.Downloading -> item.speedBps
        else -> 0L
    }
    val isPaused = item is SmartDownloadState.Paused
    val pct = if (total > 0) ((downloaded * 100) / total).toInt() else -1
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(fileName, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        if (pct >= 0 && (item is SmartDownloadState.Downloading || item is SmartDownloadState.Paused)) {
            LinearProgressIndicator(
                progress = { pct / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            when (item) {
                is SmartDownloadState.Downloading -> if (total > 0) {
                    pct.toString() + "% · " + formatSmartDownloadMB(downloaded) + " / " + formatSmartDownloadMB(total) + " · " + formatSmartDownloadSpeed(speedBps)
                } else {
                    formatSmartDownloadMB(downloaded) + " · " + formatSmartDownloadSpeed(speedBps)
                }
                is SmartDownloadState.Paused -> stringResource(R.string.settings_smart_download_paused) + if (total > 0) {
                    " " + pct.toString() + "% · " + formatSmartDownloadMB(downloaded) + " / " + formatSmartDownloadMB(total)
                } else {
                    ""
                }
                is SmartDownloadState.Done -> stringResource(R.string.settings_smart_download_row_done)
                is SmartDownloadState.Failed -> stringResource(R.string.settings_smart_download_row_failed)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (item is SmartDownloadState.Failed && item.error.isNotEmpty()) {
            Text(
                item.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            when (item) {
                is SmartDownloadState.Downloading -> {
                    TextButton(onClick = { onPause(item.id) }) {
                        Text(stringResource(R.string.settings_smart_download_pause))
                    }
                }
                is SmartDownloadState.Paused -> {
                    TextButton(onClick = { onResume(item.id) }) {
                        Text(stringResource(R.string.settings_smart_download_resume))
                    }
                    TextButton(onClick = { onCancel(item.id) }) {
                        Text(stringResource(R.string.settings_smart_download_cancel))
                    }
                }
                is SmartDownloadState.Done, is SmartDownloadState.Failed -> {
                    TextButton(onClick = { onClear(item.id) }) {
                        Text(stringResource(R.string.settings_smart_download_clear))
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkPlayDialog(
    onDismiss: () -> Unit,
    onPlay: (url: String) -> Unit,
) {
    var link by rememberSaveable { mutableStateOf("") }
    AVBoxAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_link_play)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    label = { Text(stringResource(R.string.settings_smart_download_link)) },
                    placeholder = { Text(stringResource(R.string.settings_link_play_hint)) },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val url = link.trim()
                if (url.isEmpty()) return@TextButton
                onPlay(url)
                onDismiss()
            }) {
                Text(stringResource(R.string.common_play))
            }
        },
    )
}

@Composable
fun TextEditDialog(
    title: String,
    initialText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    multiLine: Boolean = false,
    trailingAction: (@Composable () -> Unit)? = null,
) {
    var text by rememberSaveable { mutableStateOf(initialText) }
    AVBoxAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (trailingAction == null) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = !multiLine,
                    minLines = if (multiLine) 3 else 1,
                    textStyle = if (multiLine) {
                        MaterialTheme.typography.titleLarge
                    } else {
                        LocalTextStyle.current
                    },
                    placeholder = { Text(stringResource(R.string.dialog_api_url_hint)) },
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.weight(1f),
                        singleLine = !multiLine,
                        minLines = if (multiLine) 3 else 1,
                        textStyle = if (multiLine) {
                            MaterialTheme.typography.titleLarge
                        } else {
                            LocalTextStyle.current
                        },
                        placeholder = { Text(stringResource(R.string.dialog_api_url_hint)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    trailingAction()
                }
            }
        },
        confirmButton = {
            val dismissThen = LocalSheetDismissThen.current
            TextButton(onClick = { dismissThen { onConfirm(text.trim()) } }) {
                Text(stringResource(R.string.common_confirm))
            }
        },
    )
}
