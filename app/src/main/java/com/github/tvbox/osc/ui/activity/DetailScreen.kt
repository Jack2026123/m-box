@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.tvbox.osc.ui.activity

import com.github.tvbox.osc.util.FloatLog

import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.github.tvbox.osc.R
import com.github.tvbox.osc.ui.components.HomeBackdrop
import com.github.tvbox.osc.ui.components.ImagePalette
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.components.PosterBackdrop
import com.github.tvbox.osc.ui.components.rememberHasWallpaper
import com.github.tvbox.osc.ui.components.wallpaperAwarePageBackground
import com.github.tvbox.osc.ui.components.VodCardMenu
import com.github.tvbox.osc.ui.components.rememberVodCardMenuState
import com.github.tvbox.osc.ui.theme.AppThemeState
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.github.tvbox.osc.ui.page.jumpToSearch

private const val EntrySlideDurationMs = 300
private const val ContentSlideRatio = 0.2f

private const val FlatScrollScrimAlpha = 0.45f

private val FlatPageScrimStops = arrayOf(
    0f to Color.Black.copy(alpha = 0.05f),
    0.45f to Color.Black.copy(alpha = 0.28f),
    0.75f to Color.Black.copy(alpha = 0.48f),
    1f to Color.Black.copy(alpha = 0.62f),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(activity: DetailActivity, vm: DetailViewModel) {
    val menuContext = LocalContext.current
    val pageState by vm.pageState.collectAsState()
    val full by vm.fullScreen.collectAsState()
    val rotating by vm.rotating.collectAsState()
    val revision by vm.revision.collectAsState()
    val playSignal by vm.playSignal.collectAsState()
    val purePlayer by vm.purePlayerMode.collectAsState()
    val toast by vm.toastEvent.collectAsState()
    val finish by vm.finishEvent.collectAsState()
    val enteringFullscreen by vm.enteringFullscreen.collectAsState()
    val exitingFullscreen by vm.exitingFullscreen.collectAsState()
    val vodMenu = rememberVodCardMenuState()

    val listState = rememberLazyListState()
    val mergedScrollState = rememberScrollState()
    val density = LocalDensity.current
    val heroFadePx = with(density) { HeroScrollFadeDistance.roundToPx() }
    val heroProgress by remember(listState, heroFadePx) {
        derivedStateOf {
            heroScrollProgress(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                heroFadePx,
            )
        }
    }
    val heroGone by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }
    val configuration = LocalConfiguration.current
    val flatHero = flatHeroLayout(
        configuration.smallestScreenWidthDp,
        configuration.screenWidthDp,
        configuration.screenHeightDp,
    )
    LaunchedEffect(heroGone, flatHero) { activity.setStatusBarIconsLight(heroGone && !flatHero) }

    val isLandscapeNow = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fullBox = purePlayer || DetailFullScreenFrame.fullBox(rotating, full, isLandscapeNow)
    val inTransition = enteringFullscreen || exitingFullscreen
    var slideWidthPx by remember { mutableFloatStateOf(0f) }

    val container = activity.playContainer
    val scope = rememberCoroutineScope()
    var backdropPic by remember { mutableStateOf("") }
    var backdropSeed by remember { mutableStateOf<Int?>(null) }
    val pendingPic = vm.pendingPicture
    val backdropSource = backdropPic.ifEmpty { pendingPic }
    val backdropSeedArgb = backdropSeed ?: HomeBackdrop.seedOf(pendingPic)
    val darkTheme = AppThemeState.isDark(isSystemInDarkTheme()) || flatHero
    val schemeSeed = backdropSeedArgb ?: if (flatHero) AppThemeState.config.seedArgb else null
    val colorScheme = schemeSeed?.let { argb ->
        remember(argb, darkTheme) {
            AppThemeState.customScheme(argb, darkTheme, AppThemeState.config.style)
        }
    } ?: MaterialTheme.colorScheme

    LaunchedEffect(pageState) {
        if (pageState is DetailViewModel.PageState.Loading) {
            backdropPic = ""
            backdropSeed = null
        }
    }

    LaunchedEffect(full, playSignal) {
        if (activity.playContainer == null && (full || playSignal > 0)) activity.ensurePlayContainer()
    }

    LaunchedEffect(vm) {
        vm.playbackCommands.collect { command ->
            val c = activity.playContainer ?: return@collect
            when (command) {
                is PlaybackCommand.StopForContentSwitch -> c.stopForContentSwitch()
                is PlaybackCommand.StopForSourceSwitch -> c.stopForSourceSwitch(command.tip)
                is PlaybackCommand.ClearSourceSwitchTip -> c.clearSourceSwitchTip()
                is PlaybackCommand.SetEpisodeSheetOpen -> c.setEpisodeSheetOpen(command.open)
                is PlaybackCommand.SelectQuality -> c.selectQuality(command.position)
            }
        }
    }

    LaunchedEffect(playSignal) {
        if (playSignal > 0) {
            activity.playCurrent()
        }
    }

    // 直驱：详情数据到了就播，不设会话门、不设信号否决、不设意图。
    // 纯播放入口不走这里（ViewModel maybeAutoPlayAfterReady 已发一次），避免双发。
    LaunchedEffect(pageState) {
        if (pageState !is DetailViewModel.PageState.Ready) return@LaunchedEffect
        if (vm.vodInfo == null) return@LaunchedEffect
        FloatLog.append("app", null, "screenTrigger ready purePlayer=$purePlayer")
        if (purePlayer) return@LaunchedEffect
        vm.onPlayRequested(DetailPlaybackEntry.PlayCapsule)
    }

    var musicWatch by remember { mutableStateOf(false) }
    var musicArmed by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { musicWatch = true }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { musicWatch = false }
    LaunchedEffect(playSignal) {
        if (playSignal > 0) musicArmed = true
    }
    LaunchedEffect(musicWatch, musicArmed) {
        if (!musicWatch || !musicArmed) return@LaunchedEffect
        while (true) {
            delay(300)
            if (!activity.musicPlaybackDetected()) continue
            if (!activity.handOffToMusicPlayer()) continue
            musicArmed = false
            return@LaunchedEffect
        }
    }

    val slideAnim = remember { Animatable(0f) }

    fun slideProgress(): Float = if (inTransition) slideAnim.value else if (full) 1f else 0f

    LaunchedEffect(enteringFullscreen, exitingFullscreen) {
        when {
            exitingFullscreen -> {
                slideAnim.animateTo(0f, tween(EntrySlideDurationMs, easing = FastOutSlowInEasing))
                try {
                    vm.onExitSlideFinished()
                } finally {
                    activity.settleRotationAfterExit()
                }
            }

            enteringFullscreen -> {
                activity.settleRotationAfterExit()
                slideAnim.animateTo(1f, tween(EntrySlideDurationMs, easing = FastOutSlowInEasing))
                vm.onEntrySlideFinished()
            }
        }
    }

    LaunchedEffect(inTransition, container) {
        activity.setPlayerTouchBlocked(inTransition)
    }

    LaunchedEffect(full) {
        activity.applyFullscreen(full)
    }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(activity, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    LaunchedEffect(finish) {
        if (finish) {
            vm.consumeFinish()
            activity.finish()
        }
    }

    val pageBackdrop = rememberLayerBackdrop(onDraw = { drawContent() })
    // Global wallpaper (logtest42, model logtest43): when one is
    // set, the page root is fully transparent so the wallpaper
    // shows unmodified (40 = original photo colors) and the
    // full-screen hero backdrop is skipped (same rule as the
    // landscape home hero); card surfaces above still follow the
    // 透明背景 slider; the detail content itself is unchanged.
    val hasWallpaper = rememberHasWallpaper()
    val pageBackground = wallpaperAwarePageBackground(MaterialTheme.colorScheme.surface)
    MaterialTheme(colorScheme = colorScheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { slideWidthPx = it.width.toFloat() }
                .background(pageBackground),
        ) {
            if (!hasWallpaper) if (flatHero) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize().layerBackdrop(pageBackdrop)) {
                        val info = vm.vodInfo
                        if (info != null) {
                            DetailHeroPoster(
                                title = info.name ?: "TVBox",
                                altTitle = info.en,
                                picture = info.pic,
                                year = info.year,
                                onImage = { image ->
                                    scope.launch {
                                        val seed = withContext(Dispatchers.Default) { ImagePalette.seedOf(image) }
                                        if (seed != null && backdropSeed == null) backdropSeed = seed
                                    }
                                },
                                onBackdropPic = { backdropPic = it },
                                paused = full,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Brush.verticalGradient(*FlatPageScrimStops)),
                        )
                    }
                    FlatScrollScrim(progress = { heroProgress })
                }
            } else {
                PosterBackdrop(
                    pic = backdropSource,
                    onImage = { image ->
                        scope.launch {
                            val seed = withContext(Dispatchers.Default) { ImagePalette.seedOf(image) }
                            if (seed != null && backdropSeed == null) backdropSeed = seed
                        }
                    },
                )
            }

            // 合屏（1.28，1.35 扩展到纯播）：详情与播放同一画面，不再左右滑两块面板。
            // 竖屏：上面播放区（1/3，只放视频/转圈、不垫海报）+ 下面详情内容（2/3，
            // 纵向可滚）；全屏/画中画：只剩播放器画面。
            // 纯播模式也合屏（不再裸播）；播放自动播（触发逻辑不动），下面详情只管选集/换线/看简介，不挡播放。
            // 1.40：详情 LazyColumn 在 2/3 区内独立滚动（userScrollEnabled=true），
            // 播放区在上钉住不动、不被滑出屏幕；不改成独立详情页。
            // 注意：播放器 AndroidView 只有一个挂载点（改 modifier 不换父），切全屏不重建表面。
            val inPip = activity.inPipMode
            val playerContainer = container
            // 合屏布局判定：只看是否真的全屏/画中画，不看纯播（纯播竖屏也要合屏）。
            val mergedLayout = !inPip && !DetailFullScreenFrame.fullBox(rotating, full, isLandscapeNow)
            // 1.40 海报兜底（保留）：详情 pic 为空时用进页卡片图（pendingPic）；
            // 海报只用于详情海报头（唯一海报位），播放区与详情正文不再垫海报，
            // pic 与 pendingPic 皆空则无海报（详情海报头不占位）。
            // 1.50：背景海报改回 TMDB（DetailHeroPoster 解出来的 backdropPic，TMDB 图优先），
            // 拿不到（flatHero 关着或有壁纸时 DetailHeroPoster 不渲染）回落源站图，现况不变。
            val posterUrl = backdropPic.takeIf { it.isNotEmpty() } ?: vm.vodInfo?.pic?.takeIf { it.isNotEmpty() } ?: pendingPic.takeIf { it.isNotEmpty() }
            val posterSource = if (backdropPic.isNotEmpty() && posterUrl == backdropPic) "tmdb" else "source"
            Box(
                modifier = Modifier.fillMaxSize(),
            ) {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // 播放器固定占 30%（屏幕尺寸设置已去掉）。
                    val totalH = maxHeight
                    val totalW = maxWidth
                    val playerH = totalH * 0.26f
                    Column(
                        modifier = Modifier
                            .fillMaxSize(),
                    ) {
                        if (playerContainer != null) {
                            Box(
                                modifier = if (!mergedLayout) Modifier.fillMaxSize() else Modifier
                                    .fillMaxWidth()
                                    .height(playerH),
                            ) {
                                // 1.40：播放区不再垫海报（只放视频/转圈），一屏只见一次海报：详情海报头。
                                AndroidView(
                                    factory = { playerContainer },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        } else if (mergedLayout) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(playerH),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black),
                                )
                            }
                        }
                        if (mergedLayout) {
                            Box(
                                modifier = Modifier.weight(1f),
                            ) {
                            when (val state = pageState) {
                                is DetailViewModel.PageState.Loading -> {
                                    // 1.40：加载态不再垫海报（海报只在详情海报头出现一次），居中转圈。
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.surface),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator()
                                    }
                                }

                                is DetailViewModel.PageState.Empty -> {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        LoadStateBox(
                                            state = LoadState.Empty,
                                            emptyText = state.msg ?: stringResource(R.string.detail_empty_source),
                                            errorText = "",
                                            retryText = "",
                                            modifier = Modifier.weight(1f),
                                        )
                                        SourceSection(vm, currentSourceName = null, revision = revision)
                                    }
                                }

                                is DetailViewModel.PageState.Ready -> {
                                    // 1.43：详情区三层叠加（内容在上、棕色层在中、海报在后），
                                    // 见 MergedDetailContent 的背景层；不再有单独的海报块。
                                    MergedDetailContent(
                                        activity = activity,
                                        vm = vm,
                                        revision = revision,
                                        listState = listState,
                                        posterUrl = posterUrl,
                                        posterSource = posterSource,
                                    )
                                }
                            }
                        }
                    }
                }
                }
                if (mergedLayout) {
                    DetailTopScrim(
                        modifier = Modifier.align(Alignment.TopCenter),
                        alpha = if (flatHero) 1f else 1f - heroProgress,
                    )
                }
            }

            if (inTransition && !inPip) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            }
                        },
                )
            }
        }

        if (!activity.inPipMode) {
            EpisodeSheet(vm, revision, slideFromEnd = fullBox && isLandscapeNow)
            VodCardMenu(vodMenu) { menuContext.jumpToSearch(it) }
        }
    }
}

@Composable
private fun FlatScrollScrim(progress: () -> Float) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Color.Black.copy(
                    alpha = FlatScrollScrimAlpha * progress().coerceIn(0f, 1f),
                ),
            ),
    )
}
