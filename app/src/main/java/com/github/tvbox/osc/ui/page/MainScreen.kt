@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.tvbox.osc.ui.page

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.request.ImageRequest
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.rememberLifecycleOwner
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.ui.findActivityOrNull
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.ui.activity.BrowserActivity
import com.github.tvbox.osc.ui.activity.LivePlayActivity
import com.github.tvbox.osc.ui.components.AVBoxAlertDialog
import com.github.tvbox.osc.ui.components.ControlButton
import com.github.tvbox.osc.ui.components.capsuleGroupBorderColor
import com.github.tvbox.osc.ui.components.ControlEditHost
import com.github.tvbox.osc.ui.components.ControlEditState
import com.github.tvbox.osc.ui.components.LocalTopBarGlassPauseRecording
import com.github.tvbox.osc.ui.components.LocalSheetDismissThen
import com.github.tvbox.osc.ui.components.LocalSheetHost
import com.github.tvbox.osc.ui.components.VideoWallpaperPlayer
import com.github.tvbox.osc.ui.components.SheetHost
import com.github.tvbox.osc.ui.components.SheetHostState
import com.github.tvbox.osc.ui.components.wallpaperHighlightColorFilter
import com.github.tvbox.osc.ui.currentWindowWidthClass
import com.github.tvbox.osc.ui.isLandscapeOrientation
import com.github.tvbox.osc.ui.navbar.FloatingNavBar
import com.github.tvbox.osc.ui.navbar.GlassTabItem
import com.github.tvbox.osc.ui.navbar.NavAxis
import com.github.tvbox.osc.ui.navbar.NavMetrics
import com.github.tvbox.osc.ui.theme.ControlThemeStore
import com.github.tvbox.osc.ui.theme.LiquidGlassState
import com.github.tvbox.osc.util.AppManager
import com.github.tvbox.osc.util.BootGuard
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HomeSettings
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.PageJournal
import com.github.tvbox.osc.util.SiteHomePageResolver
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch

/**
 * Set by MainScreen while the reading shelf is the visible home page.
 * MainActivity consults it from keepStatusBarHidden() so BaseActivity's
 * resume/focus status-bar re-assertions keep the bar hidden on the
 * shelf and restore it everywhere else.
 */
object ReadingShelfStatusBar {
    @Volatile
    var wantsHidden: Boolean = false
}

private enum class AppTab(@StringRes val labelRes: Int, @DrawableRes val icon: Int) {
    HOME(R.string.tab_home, R.drawable.ic_tab_home),
    RECORDS(R.string.tab_records, R.drawable.ic_tab_records),
    WEB(R.string.tab_web, R.drawable.ic_web_source),
    SETTINGS(R.string.tab_mine, R.drawable.ic_tab_mine),
}

/** 底部导航键（与 ControlThemeStore.DEFAULT_HOME_NAV_KEYS 一一对应）。3.73：追剧槽位换成网页 tab，键仍用 "following"，存过的导航顺序/隐藏态继续对得上这个槽位。 */
private fun navKey(tab: AppTab): String = when (tab) {
    AppTab.HOME -> "home"
    AppTab.RECORDS -> "records"
    AppTab.WEB -> "following"
    AppTab.SETTINGS -> "settings"
}

@Composable
private fun WebTabRedirect(
    isCurrent: Boolean,
    onOpen: () -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(isCurrent) {
        if (isCurrent) {
            onOpen()
            onBack()
        }
    }
    Box(modifier = Modifier.fillMaxSize())
}

@Composable
fun MainScreen() {
    LaunchedEffect(Unit) { AppBootstrap.start() }
    val boot by AppBootstrap.state.collectAsState()
    Box(modifier = Modifier.fillMaxSize()) {
        MainContent()
        if (boot is AppBootstrap.Boot.Error) {
            BootErrorDialog((boot as AppBootstrap.Boot.Error).msg)
        }
        // 3.07 第二阶段 C-edit：长按编辑弹窗宿主（首页在 MainScreen 的
        // pager 里，根部一个宿主即覆盖首页与启动报错对话框，不另加）。
        ControlEditHost()
    }
}

@Composable
private fun BootErrorDialog(msg: String) {
    AVBoxAlertDialog(
        onDismissRequest = {},
        dismissible = false,
        title = { Text(stringResource(R.string.config_load_failed)) },
        text = { Text(msg) },
        confirmButton = {
            val dismissThen = LocalSheetDismissThen.current
            // 3.07 百变控件：启动报错重试钮接控件主题，长按调编辑。
            // 3.07 第二阶段 C-edit：隐藏后不渲染（3.18 起设置页“已隐藏的元素”恢复卡已删，藏了只能清应用数据回来）。
            if (!ControlThemeStore.isElementHidden("home_boot_retry")) {
                ControlButton(
                    buttonId = "home_boot_retry",
                    onClick = { dismissThen { AppBootstrap.retry() } },
                    onLongPress = { ControlEditState.requestEdit("home_boot_retry") },
                ) {
                    Text(stringResource(R.string.common_retry))
                }
            }
        },
        dismissButton = {
            val dismissThen = LocalSheetDismissThen.current
            // 3.07 百变控件：离线继续钮接控件主题，长按调编辑。
            // 3.07 第二阶段 C-edit：隐藏后不渲染（设置页可恢复）。
            if (!ControlThemeStore.isElementHidden("home_boot_offline")) {
                ControlButton(
                    buttonId = "home_boot_offline",
                    onClick = { dismissThen { AppBootstrap.continueOffline() } },
                    onLongPress = { ControlEditState.requestEdit("home_boot_offline") },
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        },
    )
}

@Composable
private fun MainContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { AppTab.entries.size })
    val homeViewModel: HomeViewModel = viewModel()

    // 1.50 当前页面记录：点首页记首页（当前首页源）。
    val homeSource by homeViewModel.currentSource.collectAsState()
    LaunchedEffect(homeSource) {
        PageJournal.home(homeSource?.name)
    }

    LaunchedEffect(Unit) {
        if (homeViewModel.defaultLiveLaunched) return@LaunchedEffect
        AppBootstrap.state.collect { boot ->
            if (boot is AppBootstrap.Boot.Ready && !homeViewModel.defaultLiveLaunched) {
                homeViewModel.defaultLiveLaunched = true
                val disabled = BootGuard.takeSafeDisabledNotice()
                if (disabled.isNotEmpty()) {
                    Toast.makeText(context, context.getString(R.string.toast_source_auto_disabled), Toast.LENGTH_LONG).show()
                }
                if (KV.get(HawkConfig.DEFAULT_LOAD_LIVE, false)) {
                    context.startActivity(Intent(context, LivePlayActivity::class.java))
                }
            }
        }
    }

    LaunchedEffect(pagerState) {
        withFrameNanos { }
        withFrameNanos { }
        pagerState.scrollBy(1f)
        pagerState.scrollBy(-1f)
    }

    BackHandler {
        val now = System.currentTimeMillis()
        if (now - homeViewModel.lastBackTime < 2000) {
            AppManager.getInstance().finishAllActivity()
            ControlManager.get().stopServer()
            (context as? Activity)?.finishAffinity()
        } else {
            homeViewModel.lastBackTime = now
            Toast.makeText(context, context.getString(R.string.toast_press_again_to_exit), Toast.LENGTH_SHORT).show()
        }
    }

    val sheetHost = remember { SheetHostState() }
    var navAnimationEnabled by remember {
        mutableStateOf(!KV.get(HawkConfig.NAV_ANIMATION_DISABLED, false))
    }
    var navLiveHidden by remember {
        mutableStateOf(KV.get(HawkConfig.NAV_LIVE_HIDDEN, false))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        navAnimationEnabled = !KV.get(HawkConfig.NAV_ANIMATION_DISABLED, false)
        navLiveHidden = KV.get(HawkConfig.NAV_LIVE_HIDDEN, false)
    }

    val selectTab: (Int) -> Unit = { index ->
        scope.launch {
            if (navAnimationEnabled) {
                pagerState.animateScrollToPage(index)
            } else {
                pagerState.scrollToPage(index)
            }
        }
    }
    var lastNonWebTab by remember { mutableIntStateOf(AppTab.HOME.ordinal) }
    LaunchedEffect(pagerState.targetPage) {
        if (pagerState.targetPage != AppTab.WEB.ordinal) lastNonWebTab = pagerState.targetPage
    }
    val openWebTab: () -> Unit = remember(homeSource) {
        {
            val source = homeSource
            val homePage = SiteHomePageResolver.resolve(source)
            val activity = context as? Activity
            if (!homePage.isNullOrBlank() && activity != null) {
                BrowserActivity.start(activity, homePage, source?.name ?: "", source?.key ?: "")
            }
            // 没主页就不出网页界面，也不提示：直接什么都不做（WebTabRedirect 会把 pager 弹回上一页）。
            // 之前这里弹"当前源没有主页地址"，用户说不需要。
        }
    }
    val liquidGlassConfig = LiquidGlassState.config
    val effectsConfig = com.github.tvbox.osc.ui.theme.EffectsState.config
    val liquidGlassEnabled = liquidGlassConfig.navbarEnabled &&
        (!com.github.tvbox.osc.ui.theme.WallpaperStore.hasWallpaper() || !effectsConfig.globalTransparent) &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // 透明背景 wallpaper (main interface only): the downloaded image
    // sits at the very bottom of the main content, always drawn
    // unobstructed (logtest43: the old white wash is removed); the
    // 透明背景 slider instead controls PAGE transparency (page/card
    // alpha = 1 - level/100: 0 = fully opaque page, 100 = fully
    // transparent). A dedicated ImageLoader
    // with an animated decoder handles GIF wallpapers; the global
    // poster pipeline is untouched.
    val wallpaperFile = com.github.tvbox.osc.ui.theme.WallpaperStore.file()
    val wallpaperVersion = com.github.tvbox.osc.ui.theme.WallpaperStore.version
    // r481：视频壁纸与图片壁纸二选一；下面透明/底栏/氛围的判断都按任一在位算。
    val videoWallpaperFile = com.github.tvbox.osc.ui.theme.WallpaperStore.videoFile()
    val hasWallpaper = wallpaperFile != null || videoWallpaperFile != null
    val wallpaperLoader = remember(context) {
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }
    val ambientSeed = com.github.tvbox.osc.ui.theme.AmbientState.seedArgb
    val ambientDark = com.github.tvbox.osc.ui.theme.AppThemeState.isDark(
        androidx.compose.foundation.isSystemInDarkTheme(),
    )
    val ambientColor = ambientSeed
        ?.let {
            Color(
                com.github.tvbox.osc.ui.components.HomeBackdrop.surfaceOf(it, ambientDark),
            )
        }
        ?: MaterialTheme.colorScheme.primary
    val liquidBackdropBgColor = MaterialTheme.colorScheme.surfaceContainer
    val liquidBackdropOnDraw: ContentDrawScope.() -> Unit =
        remember(liquidBackdropBgColor) {
            { drawRect(liquidBackdropBgColor); drawContent() }
        }
    val liquidBackdrop = rememberLayerBackdrop(onDraw = liquidBackdropOnDraw)
    val pauseGlassRecording: () -> Boolean = remember(pagerState, sheetHost) {
        { pagerState.isScrollInProgress || sheetHost.request != null }
    }
    val pauseTopBarRecording: () -> Boolean = remember(pagerState) {
        { pagerState.isScrollInProgress }
    }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    val navAxis = NavMetrics.axisFor(currentWindowWidthClass())
    val railMode = navAxis == NavAxis.Vertical
    val surfaceNavVisible = !liquidGlassEnabled
    val navBarsPadding = WindowInsets.navigationBars.asPaddingValues()
    val navBandExtent = NavMetrics.BAND_EXTENT_DP.dp
    val liquidBackdropBounds: (Size) -> Rect? = remember(density, navBandExtent, navAxis) {
        { size ->
            val extentPx = with(density) { navBandExtent.toPx() }
            if (navAxis == NavAxis.Horizontal) {
                Rect(0f, size.height - extentPx, size.width, size.height)
            } else {
                Rect(0f, 0f, extentPx, size.height)
            }
        }
    }
    val navReserve = NavMetrics.reserveDp(liquidGlassEnabled, navAxis).dp
    val pageContentPadding: PaddingValues = when {
        railMode -> PaddingValues(
            start = navReserve + navBarsPadding.calculateStartPadding(layoutDirection),
            bottom = navBarsPadding.calculateBottomPadding(),
        )

        liquidGlassEnabled -> PaddingValues(
            bottom = navBarsPadding.calculateBottomPadding() + navReserve,
        )

        else -> PaddingValues(0.dp)
    }
    // Reading shelf is a reading-side page: while it is the visible
    // home page the status bar stays hidden (reader rule). The flag is
    // published for MainActivity.keepStatusBarHidden() so the host's
    // own resume/focus re-assertions honour it too; the SideEffect
    // applies transitions (tab swipe, mode enter/exit) immediately.
    val readingActiveUrl by
        com.github.tvbox.osc.reading.ReadingStore.activeUrl.collectAsState()
    val shelfWantsStatusBarHidden = readingActiveUrl.isNotEmpty() &&
        pagerState.targetPage == AppTab.HOME.ordinal
    val hostActivity = remember(context) { context.findActivityOrNull() }
    SideEffect {
        ReadingShelfStatusBar.wantsHidden = shelfWantsStatusBarHidden
        val window = hostActivity?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (shelfWantsStatusBarHidden) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            ReadingShelfStatusBar.wantsHidden = false
            hostActivity?.window?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.statusBars())
            }
        }
    }
    val tabLabels = AppTab.entries.map { stringResource(it.labelRes) }
    // 3.07 第二阶段 C-edit：底部导航重排——显示顺序按存的键排（没存过 =
    // 原顺序）；pager 页序不动，点按仍按原始下标选页，选中态也按原始下标。
    val navOrder = ControlThemeStore.getHomeNavOrder()
    val orderedNavIndices: List<Int> = remember(navOrder) {
        val byKey = AppTab.entries.mapIndexed { index, tab -> navKey(tab) to index }.toMap()
        val ordered = navOrder.mapNotNull { byKey[it] }
        ordered + AppTab.entries.indices.filter { it !in ordered }
    }
    val orderedGlassTabs = remember(orderedNavIndices, tabLabels) {
        orderedNavIndices.map { index -> GlassTabItem(AppTab.entries[index].icon, tabLabels[index]) }
    }
    val liveActionLabel = stringResource(R.string.common_live)
    val liveActionItem = remember(liveActionLabel) { GlassTabItem(R.drawable.ic_live_fab, liveActionLabel) }
    val openLive: () -> Unit = remember(context) {
        { context.startActivity(Intent(context, LivePlayActivity::class.java)) }
    }
    CompositionLocalProvider(
        LocalSheetHost provides sheetHost,
        LocalTopBarGlassPauseRecording provides pauseTopBarRecording,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = if (effectsConfig.ambientLevel > 0 || hasWallpaper) {
                    Color.Transparent
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    if (surfaceNavVisible && !railMode) {
                        // 3.27：底栏描边色先在可组合上下文里取好（drawBehind 里调不了可组合函数）。
                        val bottomBorderColor = capsuleGroupBorderColor().copy(alpha = 0.5f)
                        ShortNavigationBar(
                            // 3.23：底栏整条描边加框——跟胶囊同一条描边颜色（capsuleGroupBorderColor，
                            // 跟主题/金边/银边/自定义四档），上沿两角圆 16dp，底边贴屏沿不圆。
                            // 3.24：底栏再高 2dp（用户 2026-11-16 定）——ShortNavigationBar 默认 80dp，
                            // 加到 82dp。
                            // 3.26：描边太扎眼（用户 2026-11-16 截图反馈「很突兀」）——只底栏这一处
                            // alpha 降到 0.3，变一条很淡的线；形状/圆角/高度/按钮下移 2dp 都不动。
                            // 3.27：最下面一层描边去掉（用户 2026-11-16 定）——drawBehind 手画上沿 +
                            // 两侧竖边，不画底边；描边仍是 0.3 alpha，形状/高度/按钮下移 2dp 不动。
                            modifier = Modifier.height(82.dp).drawBehind {
                                val stroke = 1.dp.toPx()
                                val half = stroke / 2f
                                val r = 16.dp.toPx()
                                val path = Path().apply {
                                    moveTo(half, size.height)
                                    lineTo(half, half + r)
                                    arcTo(Rect(half, half, half + 2 * r, half + 2 * r), 180f, 90f, false)
                                    lineTo(size.width - half - r, half)
                                    arcTo(Rect(size.width - half - 2 * r, half, size.width - half, half + 2 * r), 270f, 90f, false)
                                    lineTo(size.width - half, size.height)
                                }
                                drawPath(path, bottomBorderColor, style = Stroke(width = stroke))
                            },
                            containerColor = if (hasWallpaper && effectsConfig.globalTransparent) {
                                Color.Transparent
                            } else if (effectsConfig.frostedLevel > 0) {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                                    .copy(alpha = 1f - 0.22f * effectsConfig.frostedFraction)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        ) {
                            // 3.07 第二阶段 C-edit：导航显示顺序按重排走（原始下标选页不变）。
                            orderedNavIndices.forEach { index ->
                                val tab = AppTab.entries[index]
                                if (!navLiveHidden && index == NavMetrics.actionSlotFor(AppTab.entries.size)) {
                                    ShortNavigationBarItem(
                                        // 3.25：导航项整体往下 2dp（首页选中图标不再贴着上沿），
                                        // 底栏高度保持 82dp 不动；所有项一起挪，不错位。
                                        modifier = Modifier.offset(y = 2.dp),
                                        selected = false,
                                        onClick = openLive,
                                        icon = {
                                            Icon(
                                                painterResource(liveActionItem.iconRes),
                                                contentDescription = null,
                                            )
                                        },
                                        label = null,
                                    )
                                }
                                val selected = pagerState.targetPage == index
                                ShortNavigationBarItem(
                                    // 3.25：导航项整体往下 2dp（首页选中图标不再贴着上沿），
                                    // 底栏高度保持 82dp 不动；所有项一起挪，不错位。
                                    modifier = Modifier.offset(y = 2.dp),
                                    selected = selected,
                                    onClick = { if (tab == AppTab.WEB) openWebTab() else selectTab(index) },
                                    icon = {
                                        Icon(
                                            painterResource(tab.icon),
                                            contentDescription = stringResource(tab.labelRes),
                                        )
                                    },
                                    label = if (selected) {
                                        { Text(stringResource(tab.labelRes)) }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                },
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (liquidGlassEnabled) {
                                Modifier.layerBackdrop(liquidBackdrop, liquidBackdropBounds, pauseGlassRecording)
                            } else {
                                Modifier
                            }
                        ),
                ) {
                    // r481：视频壁纸与图片壁纸二选一；视频用 VideoWallpaperPlayer
                    // （ExoPlayer + TextureView，循环静音，center-crop），blur/scrim
                    // 与图片同一套（highlight 提亮只对图片生效，视频不提亮）。
                    if (videoWallpaperFile != null) {
                        VideoWallpaperPlayer(
                            file = videoWallpaperFile,
                            version = wallpaperVersion,
                            blurDp = effectsConfig.wallpaperBlurDp.dp,
                            scrimAlpha = effectsConfig.wallpaperScrimAlpha,
                        )
                    } else if (wallpaperFile != null) {
                        val wallpaperRequest = remember(wallpaperFile, wallpaperVersion) {
                            ImageRequest.Builder(context)
                                .data(wallpaperFile)
                                .memoryCacheKey("wallpaper-$wallpaperVersion")
                                .diskCacheKey("wallpaper-$wallpaperVersion")
                                .build()
                        }
                        AsyncImage(
                            model = wallpaperRequest,
                            imageLoader = wallpaperLoader,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            colorFilter = wallpaperHighlightColorFilter(effectsConfig.wallpaperHighlightT),
                            modifier = Modifier.fillMaxSize()
                                .blur(
                                    effectsConfig.wallpaperBlurDp.dp,
                                    BlurredEdgeTreatment.Unbounded,
                                ),
                        )
                        if (effectsConfig.wallpaperScrimAlpha > 0f) {
                            Box(
                                modifier = Modifier.fillMaxSize()
                                    .background(
                                        Color.Black.copy(alpha = effectsConfig.wallpaperScrimAlpha),
                                    ),
                            )
                        }
                        // logtest43: NO white wash here anymore. The
                        // old wash (alpha = level/100, all-white at
                        // 100) was inverted: the 透明背景 slider now
                        // controls PAGE transparency (page/card alpha
                        // = 1 - level/100) plus backdrop blur (0dp up
                        // to level 40, 32dp at 100) and dark scrim
                        // (0 up to level 40, 0.50 black at 100), and
                        // the wallpaper itself is always drawn
                        // unobstructed. Layer order: wallpaper ->
                        // scrim -> atmosphere -> pager.
                    }
                    if (effectsConfig.ambientLevel > 0 && effectsConfig.ambientEnabled && (!hasWallpaper || !effectsConfig.globalTransparent)) {
                        val ambientT = effectsConfig.ambientFraction
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0f to ambientColor.copy(alpha = 0.85f * ambientT),
                                        0.5f to ambientColor.copy(alpha = 0.38f * ambientT),
                                        0.75f to MaterialTheme.colorScheme.surfaceContainer
                                            .copy(alpha = 0.35f),
                                        1f to MaterialTheme.colorScheme.surfaceContainer,
                                    ),
                                ),
                        )
                    }
                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = navAnimationEnabled,
                        beyondViewportPageCount = 3,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (liquidGlassEnabled) Modifier else Modifier.padding(innerPadding)),
                    ) { page ->
                        val pageLifecycleOwner = rememberLifecycleOwner(
                            maxLifecycle = if (page == pagerState.targetPage) {
                                Lifecycle.State.RESUMED
                            } else {
                                Lifecycle.State.STARTED
                            }
                        )
                        CompositionLocalProvider(LocalLifecycleOwner provides pageLifecycleOwner) {
                            when (AppTab.entries[page]) {
                                AppTab.HOME -> {
                                    val readingActive by
                                        com.github.tvbox.osc.reading.ReadingStore.activeUrl
                                            .collectAsState()
                                    val musicActive by
                                        com.github.tvbox.osc.music.MusicStore.activeSourceId
                                            .collectAsState()
                                    if (musicActive.isNotEmpty()) {
                                        MusicHomePage(pageContentPadding)
                                    } else if (readingActive.isNotEmpty()) {
                                        ReadingHomePage(pageContentPadding)
                                    } else {
                                        HomePage(homeViewModel, pageContentPadding)
                                    }
                                }
                                AppTab.RECORDS -> RecordsPage(contentPadding = pageContentPadding)
                                AppTab.WEB -> WebTabRedirect(
                                    // 修复：只有真正停在网页页才打开浏览器。之前只看 currentPage，
                                    // 从首页滑到“我的”时会经过网页页，currentPage 短暂变成 WEB 就误触发。
                                    // 加 targetPage 条件：滑过时目标页不是 WEB，不触发。
                                    isCurrent = pagerState.currentPage == AppTab.WEB.ordinal && pagerState.targetPage == AppTab.WEB.ordinal,
                                    onOpen = openWebTab,
                                    onBack = { selectTab(lastNonWebTab) },
                                )
                                AppTab.SETTINGS -> SettingsPage(contentPadding = pageContentPadding)
                            }
                        }
                    }
                }
            }
            if (liquidGlassEnabled) {
                // Same derivation as HomePage: landscape renders the
                // horizontal home layout regardless of the stored
                // portrait preference, so the nav-band scrim must
                // follow the RENDERED layout, not the stored one.
                val homeHorizontal = isLandscapeOrientation() ||
                    HomeSettings.layoutFlow.collectAsStateWithLifecycle().value ==
                    HomeSettings.HomeLayout.Horizontal
                if (!homeHorizontal) {
                    val scrimColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f)
                    val (scrimStart, scrimEnd) = if (NavMetrics.scrimOpaqueAtStart(navAxis)) {
                        scrimColor to Color.Transparent
                    } else {
                        Color.Transparent to scrimColor
                    }
                    Box(
                        modifier = Modifier
                            .then(
                                if (navAxis == NavAxis.Horizontal) {
                                    Modifier
                                        .fillMaxWidth()
                                        .height(navBandExtent)
                                        .align(Alignment.BottomCenter)
                                } else {
                                    Modifier
                                        .fillMaxHeight()
                                        .width(navBandExtent)
                                        .align(Alignment.CenterStart)
                                }
                            )
                            .background(
                                if (navAxis == NavAxis.Horizontal) {
                                    Brush.verticalGradient(0f to scrimStart, 1f to scrimEnd)
                                } else {
                                    Brush.horizontalGradient(0f to scrimStart, 1f to scrimEnd)
                                }
                            ),
                    )
                }
                Box(
                    modifier = if (navAxis == NavAxis.Horizontal) {
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(horizontal = 16.dp)
                            .padding(bottom = NavMetrics.MARGIN_DP.dp)
                    } else {
                        Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxHeight()
                            .windowInsetsPadding(WindowInsets.systemBars)
                            .padding(vertical = 16.dp)
                            .padding(start = NavMetrics.MARGIN_DP.dp)
                    },
                ) {
                    FloatingNavBar(
                        backdrop = liquidBackdrop,
                        axis = navAxis,
                        // 3.07 第二阶段 C-edit：悬浮导航同样按重排显示——选中态是
                        // 显示位，点按把显示位换算回原始下标再选页。
                        selectedTabIndex = { orderedNavIndices.indexOf(pagerState.targetPage) },
                        onTabSelected = { displayed ->
                            val index = orderedNavIndices[displayed]
                            if (AppTab.entries[index] == AppTab.WEB) openWebTab() else selectTab(index)
                        },
                        tabs = orderedGlassTabs,
                        config = liquidGlassConfig,
                        interactive = { true },
                        actionItem = if (navLiveHidden) null else liveActionItem,
                        onActionClick = openLive,
                    )
                }
            }
            if (surfaceNavVisible && railMode) {
                NavigationRail(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.systemBars),
                    containerColor = if (hasWallpaper && effectsConfig.globalTransparent) {
                        Color.Transparent
                    } else if (effectsConfig.frostedLevel > 0) {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                            .copy(alpha = 1f - 0.22f * effectsConfig.frostedFraction)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                ) {
                    // 3.07 第二阶段 C-edit：侧边导航同样按重排显示（原始下标选页不变）。
                    orderedNavIndices.forEach { index ->
                        val tab = AppTab.entries[index]
                        if (!navLiveHidden && index == NavMetrics.actionSlotFor(AppTab.entries.size)) {
                            NavigationRailItem(
                                selected = false,
                                onClick = openLive,
                                icon = {
                                    Icon(
                                        painterResource(liveActionItem.iconRes),
                                        contentDescription = null,
                                    )
                                },
                                label = { Text(liveActionItem.label) },
                            )
                        }
                        NavigationRailItem(
                            selected = pagerState.currentPage == index,
                            onClick = { if (tab == AppTab.WEB) openWebTab() else selectTab(index) },
                            icon = {
                                Icon(
                                    painterResource(tab.icon),
                                    contentDescription = stringResource(tab.labelRes),
                                )
                            },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
            SheetHost(sheetHost)
        }
    }
}
