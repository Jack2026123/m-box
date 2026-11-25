@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.github.tvbox.osc.ui.page

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.catvod.utils.Prefers
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.api.LocalSource
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.MovieSort
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.net.SiteSearch
import com.github.tvbox.osc.ui.activity.ConfigManageActivity
import com.github.tvbox.osc.ui.activity.PartitionListActivity
import com.github.tvbox.osc.ui.activity.SearchActivity
import com.github.tvbox.osc.ui.activity.SearchViewModel
import com.github.tvbox.osc.ui.components.AVBoxBottomSheet
import com.github.tvbox.osc.ui.components.LocalSheetDismissThen
import com.github.tvbox.osc.ui.components.ControlButton
import com.github.tvbox.osc.ui.components.ControlEditState
import com.github.tvbox.osc.ui.components.ControlElementKind
import com.github.tvbox.osc.ui.components.ControlSurface
import com.github.tvbox.osc.ui.components.controlContentColor
import com.github.tvbox.osc.ui.components.capsuleGroupBorderColor
import com.github.tvbox.osc.ui.components.controlSurfaceModifier
import com.github.tvbox.osc.ui.components.filterChipElementId
import com.github.tvbox.osc.ui.components.posterElementId
import com.github.tvbox.osc.ui.components.posterElementIdByPosition
import com.github.tvbox.osc.ui.components.sourceCapsuleElementId
import com.github.tvbox.osc.ui.components.AVBoxOptionMenuAction
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.wallpaperCardBackground
import com.github.tvbox.osc.ui.components.HeroSpotlight
import com.github.tvbox.osc.ui.components.HomeBackdrop
import com.github.tvbox.osc.ui.components.HomeHeroBackdrop
import com.github.tvbox.osc.ui.components.fxGroupBevel
import com.github.tvbox.osc.ui.components.resolveElementEffects
import com.github.tvbox.osc.ui.components.wallpaperAwarePageBackground
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.homeHeroBackdropSupported
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.components.LocalSheetDismiss
import com.github.tvbox.osc.ui.components.SearchField
import com.github.tvbox.osc.ui.components.SearchSettingsSheet
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.SettingsOptionRow
import com.github.tvbox.osc.ui.components.SettingsRow
import com.github.tvbox.osc.ui.components.SkeletonBox
import com.github.tvbox.osc.ui.components.VodCard
import com.github.tvbox.osc.ui.components.VodCardMenu
import com.github.tvbox.osc.ui.components.VodCardStyle
import com.github.tvbox.osc.ui.components.glassTopBarSurface
import com.github.tvbox.osc.ui.components.heroSpotlightHeight
import com.github.tvbox.osc.ui.components.rememberVodCardMenuState
import com.github.tvbox.osc.ui.isLandscapeOrientation
import com.github.tvbox.osc.ui.page.jumpToSearch
import com.github.tvbox.osc.ui.theme.AppThemeState
import com.github.tvbox.osc.ui.theme.ControlThemeStore
import com.github.tvbox.osc.ui.theme.EffectsState
import com.github.tvbox.osc.ui.theme.cardContainer
import com.github.tvbox.osc.util.HomeSettings
import com.kyant.capsule.ContinuousCapsule
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val HomeSourceCapsuleMaxWidth = 240.dp

private const val CapsuleLogoZoom = 1.5f

private val HomeTopBarControlSpacing = 8.dp

private val HomeCapsuleArrowInset = 5.dp

@Composable
fun HomePage(
    vm: HomeViewModel,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val navStart = contentPadding.calculateStartPadding(LocalLayoutDirection.current)
    val navBottom = contentPadding.calculateBottomPadding()
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val context = LocalContext.current
    val currentSource by vm.currentSource.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val rec by vm.rec.collectAsStateWithLifecycle()
    val partitions by vm.partitions.collectAsStateWithLifecycle()
    // 3.07 第二阶段 C-edit：首页分区重排——按设置页存的顺序排分区
    // （没存过 = 原顺序）；当前出现过的分区 id 记进 KV，供设置页重排列表用。
    val sectionOrder = ControlThemeStore.getHomeSectionOrder()
    val orderedPartitions = remember(partitions, sectionOrder) {
        val ids = partitions.map { it.sort.id.orEmpty() }
        val orderedIds = ControlThemeStore.orderedHomeSectionIds(ids)
        val byId = partitions.associateBy { it.sort.id.orEmpty() }
        orderedIds.mapNotNull { byId[it] }
    }
    LaunchedEffect(orderedPartitions) {
        ControlThemeStore.setKnownHomeSections(orderedPartitions.map { it.sort.id.orEmpty() })
    }
    val sorts by vm.sorts.collectAsStateWithLifecycle()
    val vodMenu = rememberVodCardMenuState()

    val listState = rememberLazyListState()

    val pullState = rememberPullToRefreshState()

    val pageLoading by vm.pageLoading.collectAsStateWithLifecycle()
    val sortLoadFailed by vm.sortLoadFailed.collectAsStateWithLifecycle()
    val homeLayoutSetting by HomeSettings.layoutFlow.collectAsStateWithLifecycle()
    // Landscape is the TV form: the horizontal home layout takes over
    // regardless of the stored portrait preference; portrait keeps the
    // user's own setting untouched.
    val homeLayout = if (isLandscapeOrientation()) {
        HomeSettings.HomeLayout.Horizontal
    } else {
        homeLayoutSetting
    }
    LaunchedEffect(homeLayout) { vm.onLayoutChanged() }
    LaunchedEffect(vm) {
        vm.pageErrorEvents.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(vm) {
        vm.actionMessages.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            vm.refreshPartitions()
        }
    }

    var showSourceSheet by remember { mutableStateOf(false) }
    var showSearchSettings by remember { mutableStateOf(false) }
    var backdropSeed by remember { mutableStateOf<Int?>(null) }
    var backdropPic by remember { mutableStateOf("") }
    // 1.15：全部分类弹层状态放首页层（搜索 + 列数 3→1→2→3）。
    var showCatsSheet by remember { mutableStateOf(false) }
    var catsColumns by remember { mutableStateOf(3) }
    // Publish the hero seed for the global 氛围背景 effect (MainScreen).
    LaunchedEffect(backdropSeed) {
        com.github.tvbox.osc.ui.theme.AmbientState.seedArgb = backdropSeed
    }
    val heroBackdropEnabled = homeLayout == HomeSettings.HomeLayout.Horizontal &&
        homeHeroBackdropSupported
    val darkTheme = AppThemeState.isDark(isSystemInDarkTheme())
    val backdropContainer by animateColorAsState(
        targetValue = backdropSeed
            ?.takeIf { homeLayout == HomeSettings.HomeLayout.Horizontal }
            ?.let { Color(HomeBackdrop.surfaceOf(it, darkTheme)) }
            ?: MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = tween(200),
        label = "homeBackdrop",
    )

    AppTopBarScaffold(
        collapseEnabled = false,
        topBarStartInset = navStart,
        topScrimEnabled = homeLayout != HomeSettings.HomeLayout.Horizontal,
        // logtest43: the wallpaper layer lives in MainScreen UNDER
        // this page; the container follows the 透明背景 slider
        // while a wallpaper file exists (0 = opaque, 40 = default,
        // 100 = fully transparent); the hero backdropContainer
        // itself is untouched.
        containerColor = wallpaperAwarePageBackground(backdropContainer),
        titleContent = {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .widthIn(
                            max = minOf(
                                HomeSourceCapsuleMaxWidth,
                                maxWidth - HomeTopBarControlSpacing,
                            ),
                        )
                        .glassTopBarSurface(ContinuousCapsule, MaterialTheme.colorScheme.cardContainer)
                        .border(1.dp, capsuleGroupBorderColor(), ContinuousCapsule)
                        .heightIn(min = 40.dp)
                        .clickable {
                            vm.refreshSubscribes()
                            showSourceSheet = true
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val capsuleLogo = remember { ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground) }
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .drawBehind {
                                capsuleLogo?.let { drawable ->
                                    val w = size.width
                                    val h = size.height
                                    drawable.setBounds(
                                        (w / 2 - w * CapsuleLogoZoom / 2).roundToInt(),
                                        (h / 2 - h * CapsuleLogoZoom / 2).roundToInt(),
                                        (w / 2 + w * CapsuleLogoZoom / 2).roundToInt(),
                                        (h / 2 + h * CapsuleLogoZoom / 2).roundToInt(),
                                    )
                                    drawable.draw(drawContext.canvas.nativeCanvas)
                                }
                            },
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = currentSource?.name?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.home_subscription_source),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
        actions = {
            // 3.14：无限墙已删，顶栏经典↔无限快捷切换一并删掉。
            Spacer(Modifier.width(HomeTopBarControlSpacing))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .glassTopBarSurface(CircleShape, MaterialTheme.colorScheme.surfaceBright)
                    .border(1.dp, capsuleGroupBorderColor(), CircleShape)
                    .clickable { showSearchSettings = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.search_settings),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(HomeTopBarControlSpacing))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .glassTopBarSurface(CircleShape, MaterialTheme.colorScheme.surfaceBright)
                    .border(1.dp, capsuleGroupBorderColor(), CircleShape)
                    .clickable {
                        context.startActivity(Intent(context, SearchActivity::class.java))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = stringResource(R.string.common_search),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
    ) { topPad, _ ->
        // logtest41 landscape parity: the full-screen hero backdrop
        // (blurred poster + heavy scrim) would bury a linked wallpaper
        // in landscape, so it is skipped while a wallpaper file exists
        // — same rule as the transparent scaffold container above.
        // fadeToBackdrop stays on: its mask fade reveals the wallpaper
        // beneath the spotlight instead of painting opaque.
        if (heroBackdropEnabled && backdropPic.isNotEmpty() &&
            !com.github.tvbox.osc.ui.theme.WallpaperStore.hasWallpaper()
        ) {
            HomeHeroBackdrop(pic = backdropPic, scrimColor = backdropContainer)
        }
        when {
            pageLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    ContainedLoadingIndicator(Modifier.size(64.dp))
                }
            }
            sources.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = topPad, bottom = navBottom),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painter = painterResource(R.drawable.ic_empty_record),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(64.dp),
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(
                            text = stringResource(R.string.home_no_source),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 3.07 百变控件：配置管理钮接控件主题，长按调编辑。
                        // 3.07 第二阶段 C-edit：隐藏后不渲染（设置页可恢复）。
                        if (!ControlThemeStore.isElementHidden("home_config_manage")) {
                            ControlButton(
                                buttonId = "home_config_manage",
                                onClick = { ConfigManageActivity.start(context) },
                                onLongPress = { ControlEditState.requestEdit("home_config_manage") },
                            ) {
                                Text(stringResource(R.string.config_add_subscribe))
                            }
                        }
                    }
                }
            }
            else -> {
            if (homeLayout == HomeSettings.HomeLayout.Vertical) {
                HomeGridLayout(
                    vm = vm,
                    topPadding = topPad,
                    contentPadding = contentPadding,
                    pullState = pullState,
                    onCardClick = { video -> handleCardClick(vm, video, context) },
                    onCardLongClick = { video -> vodMenu.show(video) },
                )
            } else LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .pullToRefresh(
                        isRefreshing = false,
                        state = pullState,
                        onRefresh = { vm.reload() },
                    ),
                contentPadding = PaddingValues(
                    start = 0.dp,
                    top = 0.dp,
                    bottom = 88.dp + navBottom,
                ),
            ) {
                item(key = "hero") {
                    when {
                        rec.state == HomeViewModel.PartitionState.Ready && rec.videos.isNotEmpty() -> {
                            HeroSpotlight(
                                videos = rec.videos.take(HomeViewModel.HERO_VIDEO_COUNT),
                                backdropColor = backdropContainer,
                                onBackdropSeed = { argb -> backdropSeed = argb },
                                onPosterPic = { pic -> backdropPic = pic },
                                onCardClick = { video -> handleCardClick(vm, video, context) },
                                fadeToBackdrop = heroBackdropEnabled,
                            )
                        }
                        rec.state == HomeViewModel.PartitionState.Loading -> {
                            SkeletonBox(
                                modifier = Modifier
                                    .fillParentMaxWidth()
                                    .height(heroSpotlightHeight(screenHeightDp)),
                            )
                        }
                        rec.state == HomeViewModel.PartitionState.Error -> {
                            Box(
                                modifier = Modifier
                                    .fillParentMaxWidth()
                                    .height(heroSpotlightHeight(screenHeightDp))
                                    .background(MaterialTheme.colorScheme.surfaceContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                LoadStateBox(
                                    state = LoadState.Error(),
                                    emptyText = "",
                                    errorText = stringResource(R.string.home_load_timeout),
                                    retryText = stringResource(R.string.common_retry),
                                    onRetry = { vm.loadHome() },
                                )
                            }
                        }
                        else -> {
                            Spacer(modifier = Modifier.height(topPad + 8.dp))
                        }
                    }
                }
                item(key = "cats") {
                    HomeCategoryChips(
                        sorts = sorts,
                        onOpenSort = { sort -> PartitionListActivity.startForPartition(context, sort) },
                        modifier = Modifier.padding(start = navStart),
                        onExpandClick = { showCatsSheet = true },
                    )
                }
                if (rec.state != HomeViewModel.PartitionState.Empty &&
                    (rec.state == HomeViewModel.PartitionState.Loading ||
                        rec.videos.size > HomeViewModel.HERO_VIDEO_COUNT)
                ) {
                    item(key = "rec") {
                        PartitionSection(
                            title = stringResource(R.string.home_recommend),
                            state = rec.state,
                            videos = rec.videos.drop(HomeViewModel.HERO_VIDEO_COUNT),
                            onLoadMore = {},
                            onCardClick = { video -> handleCardClick(vm, video, context) },
                            onCardLongClick = { video -> vodMenu.show(video) },
                            modifier = Modifier.padding(start = navStart),
                        )
                    }
                }
                if (sortLoadFailed) {
                    item(key = "sort_error") {
                        Row(
                            modifier = Modifier
                                .fillParentMaxWidth()
                                .padding(start = navStart)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.common_load_failed_network),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            // 3.07 百变控件：分类加载失败重试钮接控件主题，长按调编辑。
                            // 3.07 第二阶段 C-edit：隐藏后不渲染（设置页可恢复）。
                            if (!ControlThemeStore.isElementHidden("home_retry_sort")) {
                                ControlButton(
                                    buttonId = "home_retry_sort",
                                    onClick = { vm.retrySort() },
                                    onLongPress = { ControlEditState.requestEdit("home_retry_sort") },
                                ) {
                                    Text(text = stringResource(R.string.common_retry))
                                }
                            }
                        }
                    }
                }
                // 3.07 第二阶段 C-edit：分区按设置页存的顺序排（orderedPartitions）。
                items(orderedPartitions, key = { it.sort.id.orEmpty() }) { p ->
                    PartitionSection(
                        title = p.sort.name ?: "",
                        state = p.state,
                        videos = p.videos,
                        onLoadMore = { vm.loadMorePartition(p) },
                        onCardClick = { video -> handleCardClick(vm, video, context) },
                        onCardLongClick = { video -> vodMenu.show(video) },
                        onOpenAll = {
                            PartitionListActivity.startForPartition(context, p.sort)
                        },
                        onRetry = { vm.retryPartition(p) },
                        modifier = Modifier.padding(start = navStart),
                    )
                }
            }
        }
        }

        if (sources.isNotEmpty() && homeLayout == HomeSettings.HomeLayout.Horizontal) {
            HomePullRefreshIndicator(
                state = pullState,
                isRefreshing = false,
                topPadding = topPad,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }

    if (showSourceSheet) {
        AVBoxBottomSheet(
            onDismissRequest = { showSourceSheet = false },
            title = stringResource(R.string.home_subscription_source),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            isScrollable = false,
        ) {
            SourcePickerContent(
                vm = vm,
                sources = sources,
                currentSource = currentSource,
            )
        }
    }

    if (showCatsSheet) {
        HomeCategorySheet(
            sorts = sorts,
            columns = catsColumns,
            onColumnsChange = { catsColumns = it },
            onDismissRequest = { showCatsSheet = false },
            onOpenSort = { sort -> PartitionListActivity.startForPartition(context, sort) },
        )
    }

    if (showSearchSettings) {
        SearchSettingsSheet(
            onDismiss = { showSearchSettings = false },
            onSelectionChanged = { SearchViewModel.loadCheckedSources() },
        )
    }

    val menuContext = LocalContext.current
    VodCardMenu(vodMenu) { menuContext.jumpToSearch(it) }
}

@Composable
fun HomePullRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    topPadding: Dp,
    modifier: Modifier = Modifier,
) {
    PullToRefreshDefaults.IndicatorBox(
        state = state,
        isRefreshing = isRefreshing,
        modifier = modifier
            .offset(y = topPadding)
            .size(48.dp),
        shape = RectangleShape,
        containerColor = Color.Transparent,
        elevation = 0.dp,
    ) {
        Crossfade(targetState = isRefreshing) { refreshing ->
            if (refreshing) {
                ContainedLoadingIndicator(modifier = Modifier.size(48.dp))
            } else {
                ContainedLoadingIndicator(
                    progress = { state.distanceFraction },
                    modifier = Modifier
                        .size(48.dp)
                        .drawWithContent {
                            val progress = state.distanceFraction
                            if (progress > 1f) {
                                rotate(degrees = -(progress - 1f) * 180f) {
                                    this@drawWithContent.drawContent()
                                }
                            } else {
                                drawContent()
                            }
                        },
                )
            }
        }
    }
}

private fun handleCardClick(vm: HomeViewModel, video: Movie.Video, context: android.content.Context) {
    context.dispatchVodCardClick(video, onAction = { vm.handleAction(it) })
}

@Composable
private fun PartitionSection(
    title: String,
    state: HomeViewModel.PartitionState,
    videos: List<Movie.Video>,
    onLoadMore: () -> Unit,
    onCardClick: (Movie.Video) -> Unit,
    onCardLongClick: (Movie.Video) -> Unit,
    onOpenAll: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    cardWidth: Dp = 110.dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (onOpenAll != null) {
                HomeCapsule(
                    text = stringResource(R.string.common_all),
                    onClick = onOpenAll,
                    trailingArrow = true,
                )
            }
        }
        when (state) {
            HomeViewModel.PartitionState.Idle -> Unit

            HomeViewModel.PartitionState.Loading -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    repeat(3) {
                        SkeletonBox(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(16.dp)),
                            shape = RoundedCornerShape(16.dp),
                        )
                    }
                }
            }

            HomeViewModel.PartitionState.Empty -> Text(
                text = stringResource(R.string.common_empty_content),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            HomeViewModel.PartitionState.Error -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.common_load_failed_network),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    // 3.07 百变控件：分区加载失败重试钮接控件主题，长按调编辑。
                    // 3.07 第二阶段 C-edit：隐藏后不渲染（设置页可恢复）。
                    if (!ControlThemeStore.isElementHidden("home_retry_partition")) {
                        ControlButton(
                            buttonId = "home_retry_partition",
                            onClick = { onRetry?.invoke() },
                            onLongPress = { ControlEditState.requestEdit("home_retry_partition") },
                        ) {
                            Text(text = stringResource(R.string.common_retry))
                        }
                    }
                }
            }

            HomeViewModel.PartitionState.Ready -> LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(videos) { index, video ->
                    // 3.07 百变控件：海报卡只套表面外观（kind=POSTER，id=内容 id，
                    // 无 id 时用位置兜底 poster_pos_，覆盖可能串位已注明）；
                    // 长按不接——VodCard 长按已有功能（开卡片菜单），劫持会破坏功能，故只做外观。
                    val posterId = video.id?.takeIf { it.isNotEmpty() }?.let { posterElementId(it) }
                        ?: posterElementIdByPosition(index)
                    // 3.07 第二阶段 C-edit：隐藏后不渲染（设置页可恢复）。
                    // 3.07 第二阶段 C-edit（海报裁标题）：表面 modifier 改套到
                    // PressableCard 层（VodCard.posterSurfaceModifier），多边形 /
                    // 心形只裁海报图，下方标题字不再被裁（见 VodCard.kt）。
                    if (!ControlThemeStore.isElementHidden(posterId)) {
                        VodCard(
                            video = video,
                            onClick = { onCardClick(video) },
                            onLongClick = { onCardLongClick(video) },
                            modifier = Modifier.width(cardWidth),
                            posterSurfaceModifier = controlSurfaceModifier(posterId, ControlElementKind.POSTER),
                            // 3.08：该海报由百变控件接管（原浮雕按双开关决定画不画，见 VodCard）。
                            controlThemeManaged = true,
                            // 3.10：标题字长按出小编辑框（与表面 modifier 同一个 id）；
                            // 海报图长按仍是海报菜单，不动。
                            posterElementId = posterId,
                            style = VodCardStyle.Stacked,
                        )
                    }
                }
                item(key = "more_$title") {
                    LaunchedEffect(videos.size) { onLoadMore() }
                }
            }
        }
    }
}

@Composable
private fun HomeCategoryChips(
    sorts: List<MovieSort.SortData>,
    onOpenSort: (MovieSort.SortData) -> Unit,
    modifier: Modifier = Modifier,
    onExpandClick: () -> Unit = {},
) {
    if (sorts.isEmpty()) return
    Column(modifier = modifier.padding(top = 24.dp, bottom = 12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_all_categories),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            // 1.15：点展开弹出分类弹层（搜索 + 列数切换，见 HomeCategorySheet）。
            Row(
                modifier = Modifier.clickable { onExpandClick() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.detail_expand),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(sorts) { sort ->
                HomeCapsule(
                    text = sort.name ?: "",
                    onClick = { onOpenSort(sort) },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

// 1.15：全部分类底部弹层（AVBoxBottomSheet）：顶部搜索过滤分类，
// 右边按钮切列数 3→1→2→3；胶囊仍用 AVBox 样式（HomeCapsule，字居中）。
@Composable
private fun HomeCategorySheet(
    sorts: List<MovieSort.SortData>,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    onDismissRequest: () -> Unit,
    onOpenSort: (MovieSort.SortData) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val dismissThen = LocalSheetDismissThen.current
    val shown = if (query.isBlank()) sorts else sorts.filter { (it.name ?: "").contains(query, ignoreCase = true) }
    val cols = columns.coerceIn(1, 3)
    val columnsIcon = when (cols) {
        1 -> Icons.AutoMirrored.Filled.List
        2 -> Icons.Filled.ViewColumn
        else -> Icons.Filled.GridView
    }
    AVBoxBottomSheet(
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.home_all_categories),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        isScrollable = false,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(text = "搜索分类...") },
                leadingIcon = { Icon(imageVector = Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { onColumnsChange(when (cols) { 3 -> 1; 1 -> 2; else -> 3 }) },
            ) {
                Icon(imageVector = columnsIcon, contentDescription = null)
            }
        }
        if (shown.isEmpty()) {
            Text(
                text = "没有匹配的分类",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        } else {
            // 1.16：按视频来：1 列整行宽、2 列半行宽、3 列三分之一宽；
            // 字只在 3 列时居中，1/2 列靠左。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                shown.chunked(cols).forEach { rowSorts ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowSorts.forEach { sort ->
                            HomeCapsule(
                                text = sort.name ?: "",
                                onClick = { dismissThen { onOpenSort(sort) } },
                                modifier = Modifier.weight(1f),
                                // 1.16：胶囊改小（上下内边距 12→8dp，跟首页那行一样高）。
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                centerText = cols == 3,
                            )
                        }
                        repeat(cols - rowSorts.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeCapsule(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 5.dp, vertical = 4.dp),
    trailingArrow: Boolean = false,
    fontWeight: FontWeight = FontWeight.Medium,
    centerText: Boolean = false,
) {
    // 3.07 百变控件：分类 chip 接控件主题（kind=CHIP，id=筛选值原文）；
    // combinedClickable 放最外层（点按区含表面内边距），长按调编辑。
    // 3.09 更正（总开关分流）：总开关开时水胶囊底 / 壁纸底 / 原浮雕一律不画（只留随心所欲层，单框）；
    // 总开关关时画原样式（恢复最初的 AVBox）。
    // 3.07 第二阶段 C-edit：隐藏后不渲染（设置页可恢复）。
    if (ControlThemeStore.isElementHidden(filterChipElementId(text))) return
    val controlOn = ControlThemeStore.isControlThemeEnabled()
    val drawOriginal = ControlThemeStore.shouldDrawOriginalStyle()
    // 3.10：单元素浮雕 / 光感覆盖（分类 chip）：注意总开关开时下面那行
    // fxGroupBevel 根本不挂（drawOriginal 为 false），覆盖今天没有画的
    // 地方、无可见效果；关时挂的是原样式，解析回全局，也不动。先把参数
    // 接上，画的地方一改门控就生效，不用再改调用点。
    val chipFx = resolveElementEffects(filterChipElementId(text), ControlElementKind.CHIP)
    Row(
        modifier = modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = { ControlEditState.requestEdit(filterChipElementId(text)) },
            )
            .then(controlSurfaceModifier(filterChipElementId(text), ControlElementKind.CHIP))
            .then(
                if (!drawOriginal) Modifier
                else Modifier.clip(RoundedCornerShape(18.dp))
                    .background(wallpaperCardBackground(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), highTransparent = true))
            )
            // Small-tier relief: bevel + sheen only, no platform halo.
            .then(if (drawOriginal) Modifier.fxGroupBevel(RoundedCornerShape(18.dp), effects = chipFx) else Modifier)
            .padding(contentPadding),
        horizontalArrangement = if (centerText) Arrangement.Center else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = fontWeight,
            // 随心所欲层是深 slate 底，字走胶囊组字体颜色（默认白字，可改）；
            // 总开关关时走原字色（onSurfaceVariant）。
            color = if (controlOn) controlContentColor(filterChipElementId(text), ControlElementKind.CHIP) else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        if (trailingArrow) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = if (controlOn) controlContentColor(filterChipElementId(text), ControlElementKind.CHIP) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .offset(x = HomeCapsuleArrowInset),
            )
        }
    }
}

private enum class PanelAction { NONE, ACTIONS, NEW_CATEGORY, MOVE_CATEGORY, DELETE_CONFIRM }

@Composable
private fun SourcePickerContent(
    vm: HomeViewModel,
    sources: List<SourceBean>,
    currentSource: SourceBean?,
) {
    val context = LocalContext.current
    val dismissAnimated = LocalSheetDismiss.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val subscribes by vm.subscribeItems.collectAsStateWithLifecycle()
    val activeSubscribeIndex by vm.activeSubscribeIndex.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var columns by remember { mutableStateOf(Prefers.getInt(PREF_SOURCE_COLUMNS, 1).coerceIn(1, 3)) }
    var blockedFolders by remember { mutableStateOf(loadBlockedFolders()) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var folderBlockOpen by remember { mutableStateOf(false) }
    var multiSelect by remember { mutableStateOf(false) }
    var checkedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var fileByKey by remember { mutableStateOf<Map<String, File>>(emptyMap()) }
    var locationReady by remember { mutableStateOf(false) }
    var infoSite by remember { mutableStateOf<SourceBean?>(null) }
    var panelAction by remember { mutableStateOf(PanelAction.NONE) }
    var newCategoryName by remember { mutableStateOf("") }

    LaunchedEffect(sources) {
        locationReady = false
        fileByKey = withContext(Dispatchers.IO) { resolveLocalSourceFiles(sources) }
        locationReady = true
    }
    LaunchedEffect(blockedFolders) {
        val current = selectedFolder
        if (current != null && current in blockedFolders) selectedFolder = null
    }

    val searched = remember(sources, query) { SiteSearch.filter(sources, query) }
    val folderCounts = remember(sources) { folderCountsOf(sources) }
    val filtered = remember(searched, selectedFolder, blockedFolders) {
        searched.filter { bean ->
            val folder = sourceFolderTag(bean.name)
            (folder == null || folder !in blockedFolders) &&
                (selectedFolder == null || folder == selectedFolder)
        }
    }
    val subscribeOptions = remember(subscribes) { subscribes.map { it.name.ifEmpty { it.url } } }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val selectedIndex = filtered.indexOfFirst { it.key == currentSource?.key }
    LaunchedEffect(columns, filtered) {
        if (query.isEmpty() && selectedIndex > 0) {
            if (columns == 1) listState.scrollToItem(selectedIndex) else gridState.scrollToItem(selectedIndex)
        }
    }

    fun switchSubscribe(item: SubscribeSource) {
        if (vm.isSubscribeDisabled(item)) {
            Toast.makeText(context, R.string.toast_source_auto_disabled, Toast.LENGTH_LONG).show()
        } else {
            dismissAnimated()
            vm.switchSubscribe(item)
        }
    }

    fun toggleChecked(bean: SourceBean) {
        val key = bean.key.orEmpty()
        if (!fileByKey.containsKey(key)) return
        checkedKeys = if (key in checkedKeys) checkedKeys - key else checkedKeys + key
    }

    fun onSiteClick(bean: SourceBean) {
        if (multiSelect) {
            toggleChecked(bean)
        } else {
            if (bean.key != currentSource?.key) vm.switchSource(bean)
            dismissAnimated()
        }
    }

    fun onSiteLongClick(bean: SourceBean) {
        if (multiSelect || !locationReady) return
        val key = bean.key.orEmpty()
        if (fileByKey.containsKey(key)) {
            multiSelect = true
            checkedKeys = setOf(key)
        } else {
            infoSite = bean
        }
    }

    fun exitMultiSelect() {
        multiSelect = false
        checkedKeys = emptySet()
    }

    fun visibleCheckableKeys(): Set<String> =
        filtered.mapNotNull { bean -> bean.key?.takeIf { fileByKey.containsKey(it) } }.toSet()

    fun checkedFiles(): List<File> = checkedKeys.mapNotNull { fileByKey[it] }

    fun runMove(category: String) {
        val files = checkedFiles()
        if (files.isEmpty()) return
        scope.launch {
            val result = withContext(Dispatchers.IO) { batchMoveFiles(files, category) }
            when {
                result.done > 0 && result.skipped > 0 -> Toast.makeText(
                    context,
                    context.getString(R.string.home_toast_moved_skip, result.done, result.skipped),
                    Toast.LENGTH_LONG,
                ).show()
                result.done > 0 -> Toast.makeText(
                    context,
                    context.getString(R.string.home_toast_moved, result.done),
                    Toast.LENGTH_SHORT,
                ).show()
                else -> Toast.makeText(context, R.string.home_toast_op_failed, Toast.LENGTH_LONG).show()
            }
            if (result.done > 0) {
                exitMultiSelect()
                refreshLocalConfig(vm)
            }
        }
    }

    fun runDelete() {
        val files = checkedFiles()
        if (files.isEmpty()) return
        scope.launch {
            val result = withContext(Dispatchers.IO) { batchDeleteFiles(files) }
            when {
                result.done > 0 && result.skipped > 0 -> Toast.makeText(
                    context,
                    context.getString(R.string.home_toast_delete_skip, result.done, result.skipped),
                    Toast.LENGTH_LONG,
                ).show()
                result.done > 0 -> Toast.makeText(
                    context,
                    context.getString(R.string.home_toast_deleted, result.done),
                    Toast.LENGTH_SHORT,
                ).show()
                else -> Toast.makeText(context, R.string.home_toast_op_failed, Toast.LENGTH_LONG).show()
            }
            if (result.done > 0) {
                exitMultiSelect()
                refreshLocalConfig(vm)
            }
        }
    }

    val moveOptions = remember(checkedKeys, fileByKey) {
        if (checkedKeys.isEmpty()) {
            emptyList()
        } else {
            try {
                val settings = LocalSource.loadSettings()
                val roots = LocalSource.enabledDirs(settings)
                checkedFiles().mapNotNull { scanRootOf(it, roots) }.toSet()
                    .flatMap { categoryFoldersOf(it) }
                    .toSet()
                    .sorted()
            } catch (t: Throwable) {
                emptyList()
            }
        }
    }

    SearchField(
        query = query,
        onQueryChange = { query = it },
        onSearch = { keyboard?.hide() },
        hint = stringResource(R.string.home_site_search_hint),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        trailing = if (subscribeOptions.isEmpty()) {
            null
        } else {
            {
                AVBoxOptionMenuAction(
                    options = subscribeOptions,
                    selectedIndex = activeSubscribeIndex,
                    onSelect = { index -> subscribes.getOrNull(index)?.let(::switchSubscribe) },
                    contentDescription = stringResource(R.string.home_subscription_source),
                )
            }
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SourceFolderChip(
                    text = stringResource(R.string.home_chip_all),
                    selected = selectedFolder == null,
                    onClick = { selectedFolder = null },
                )
            }
            item {
                SourceFolderChip(
                    text = stringResource(R.string.home_chip_settings),
                    selected = folderBlockOpen,
                    onClick = {
                        exitMultiSelect()
                        folderBlockOpen = true
                    },
                )
            }
            items(folderCounts.filter { it.first !in blockedFolders }) { entry ->
                SourceFolderChip(
                    text = entry.first,
                    selected = selectedFolder == entry.first,
                    onClick = { selectedFolder = if (selectedFolder == entry.first) null else entry.first },
                )
            }
        }
        IconButton(
            onClick = {
                val next = if (columns >= 3) 1 else columns + 1
                columns = next
                Prefers.put(PREF_SOURCE_COLUMNS, next)
            },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_pref_collect_columns),
                contentDescription = stringResource(R.string.home_layout_columns),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }

    // Sub-panels (folder blocking, source info, multi-select actions) render
    // inline in place of the list: this content lives inside a sheet hosted
    // by the single-slot SheetHost, so opening another OverlayRequest from
    // here replaces (and thereby destroys) this sheet and the new request
    // with it. Inline rendering is the only stable way to stack them.
    val subPanelActive = folderBlockOpen || infoSite != null || panelAction != PanelAction.NONE
    if (!subPanelActive && columns == 1) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            if (filtered.isEmpty()) {
                item {
                    LoadStateBox(
                        state = LoadState.Empty,
                        emptyText = stringResource(
                            if (query.isEmpty()) R.string.config_empty_subscribe else R.string.home_site_search_empty,
                        ),
                        errorText = "",
                        retryText = "",
                        emptyIconRes = R.drawable.ic_empty_record,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                    )
                }
            }
            itemsIndexed(filtered) { index, bean ->
                val selected = bean.key == currentSource?.key
                SettingsCard(
                    position = when {
                        filtered.size == 1 -> SettingsCardPosition.SINGLE
                        index == 0 -> SettingsCardPosition.FIRST
                        index == filtered.size - 1 -> SettingsCardPosition.LAST
                        else -> SettingsCardPosition.MIDDLE
                    },
                    modifier = Modifier.padding(bottom = if (index == filtered.lastIndex) 0.dp else 2.dp),
                    color = MaterialTheme.colorScheme.surfaceBright,
                ) {
                    if (multiSelect) {
                        SourceMultiRow(
                            title = bean.name ?: bean.key.orEmpty(),
                            checkable = fileByKey.containsKey(bean.key.orEmpty()),
                            checked = bean.key in checkedKeys,
                            onClick = { onSiteClick(bean) },
                        )
                    } else {
                        SettingsOptionRow(
                            title = bean.name ?: bean.key.orEmpty(),
                            selected = selected,
                            onClick = { onSiteClick(bean) },
                            onLongClick = { onSiteLongClick(bean) },
                        )
                    }
                }
            }
            if (!multiSelect) {
                item {
                    SettingsGroup(title = null, modifier = Modifier.padding(top = 20.dp)) {
                        SettingsCard(
                            position = SettingsCardPosition.SINGLE,
                            color = MaterialTheme.colorScheme.surfaceBright,
                        ) {
                            SettingsRow(
                                title = stringResource(R.string.settings_config_manage),
                                onClick = {
                                    dismissAnimated()
                                    ConfigManageActivity.start(context)
                                },
                            )
                        }
                    }
                }
            }
        }
    } else if (!subPanelActive) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (filtered.isEmpty()) {
                item(span = { GridItemSpan(columns) }) {
                    LoadStateBox(
                        state = LoadState.Empty,
                        emptyText = stringResource(
                            if (query.isEmpty()) R.string.config_empty_subscribe else R.string.home_site_search_empty,
                        ),
                        errorText = "",
                        retryText = "",
                        emptyIconRes = R.drawable.ic_empty_record,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                    )
                }
            }
            gridItems(filtered) { bean ->
                SourceCapsule(
                    name = bean.name ?: bean.key.orEmpty(),
                    sourceKey = bean.key.orEmpty(),
                    selected = bean.key == currentSource?.key,
                    multiSelect = multiSelect,
                    checkable = fileByKey.containsKey(bean.key.orEmpty()),
                    checked = bean.key in checkedKeys,
                    onClick = { onSiteClick(bean) },
                )
            }
            if (!multiSelect) {
                item(span = { GridItemSpan(columns) }) {
                    SettingsGroup(title = null, modifier = Modifier.padding(top = 12.dp)) {
                        SettingsCard(
                            position = SettingsCardPosition.SINGLE,
                            color = MaterialTheme.colorScheme.surfaceBright,
                        ) {
                            SettingsRow(
                                title = stringResource(R.string.settings_config_manage),
                                onClick = {
                                    dismissAnimated()
                                    ConfigManageActivity.start(context)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (!subPanelActive && multiSelect) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.home_multi_selected, checkedKeys.size),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { checkedKeys = checkedKeys + visibleCheckableKeys() },
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) { Text(stringResource(R.string.home_multi_all)) }
                TextButton(
                    onClick = {
                        val visible = visibleCheckableKeys()
                        checkedKeys = (checkedKeys - visible) + (visible - checkedKeys)
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) { Text(stringResource(R.string.home_multi_invert)) }
                TextButton(
                    onClick = { checkedKeys = checkedKeys - visibleCheckableKeys() },
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) { Text(stringResource(R.string.home_multi_none)) }
                TextButton(
                    onClick = { exitMultiSelect() },
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) { Text(stringResource(R.string.common_cancel)) }
            }
            Button(
                onClick = { panelAction = PanelAction.ACTIONS },
                enabled = checkedKeys.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.home_multi_target))
            }
        }
    }

    if (folderBlockOpen) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.home_blocked_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { folderBlockOpen = false }) {
                    Text(stringResource(R.string.dialog_positive))
                }
            }
            Text(
                text = stringResource(R.string.home_blocked_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                items(folderCounts) { entry ->
                    val blocked = entry.first in blockedFolders
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val updated = if (blocked) blockedFolders - entry.first else blockedFolders + entry.first
                                blockedFolders = updated
                                saveBlockedFolders(updated)
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = blocked,
                            onCheckedChange = { checked ->
                                val updated = if (checked) blockedFolders + entry.first else blockedFolders - entry.first
                                blockedFolders = updated
                                saveBlockedFolders(updated)
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = entry.first,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.home_blocked_count, entry.second),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    infoSite?.let { bean ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Text(
                text = bean.name ?: bean.key.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(
                text = "Key：${bean.key.orEmpty()}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "API：${bean.api.orEmpty()}",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = { infoSite = null },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.dialog_positive))
            }
        }
    }

    if (panelAction == PanelAction.ACTIONS) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            SettingsRow(
                title = stringResource(R.string.home_action_new_category),
                onClick = {
                    newCategoryName = ""
                    panelAction = PanelAction.NEW_CATEGORY
                },
            )
            SettingsRow(
                title = stringResource(R.string.home_action_move_category),
                onClick = { panelAction = PanelAction.MOVE_CATEGORY },
            )
            SettingsRow(
                title = stringResource(R.string.home_action_delete_files),
                onClick = { panelAction = PanelAction.DELETE_CONFIRM },
            )
            TextButton(
                onClick = { panelAction = PanelAction.NONE },
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(end = 8.dp),
            ) { Text(stringResource(R.string.common_cancel)) }
        }
    }

    if (panelAction == PanelAction.NEW_CATEGORY) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.home_new_category_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            OutlinedTextField(
                value = newCategoryName,
                onValueChange = { newCategoryName = it },
                singleLine = true,
                label = { Text(stringResource(R.string.home_new_category_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { panelAction = PanelAction.NONE }) {
                    Text(stringResource(R.string.common_cancel))
                }
                TextButton(
                    onClick = {
                        val name = newCategoryName.trim()
                        if (name.isEmpty() || name.contains('/') || name.contains('\\') ||
                            name == "." || name == ".."
                        ) {
                            Toast.makeText(context, R.string.home_toast_invalid_category, Toast.LENGTH_SHORT).show()
                        } else {
                            panelAction = PanelAction.NONE
                            runMove(name)
                        }
                    },
                ) { Text(stringResource(R.string.dialog_positive)) }
            }
        }
    }

    if (panelAction == PanelAction.MOVE_CATEGORY) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.home_move_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
            )
            if (moveOptions.isEmpty()) {
                Text(
                    text = stringResource(R.string.home_move_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                ) {
                    items(moveOptions) { category ->
                        SettingsOptionRow(
                            title = category,
                            selected = false,
                            onClick = {
                                panelAction = PanelAction.NONE
                                runMove(category)
                            },
                        )
                    }
                }
            }
            TextButton(
                onClick = { panelAction = PanelAction.NONE },
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(end = 8.dp, bottom = 8.dp),
            ) { Text(stringResource(R.string.common_cancel)) }
        }
    }

    if (panelAction == PanelAction.DELETE_CONFIRM) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.home_delete_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Text(
                text = stringResource(R.string.home_delete_message, checkedKeys.size),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { panelAction = PanelAction.NONE }) {
                    Text(stringResource(R.string.common_cancel))
                }
                TextButton(
                    onClick = {
                        panelAction = PanelAction.NONE
                        runDelete()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.common_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceFolderChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // 3.07 百变控件：源文件夹筛选 chip 接控件主题（kind=CHIP，id=筛选值原文）；
    // ControlSurface 不带 onLongPress：调用处 combinedClickable 同时处理点按与长按，
    // ControlSurface 自身 onClick 置空，避免点按被处理两次。
    // 3.09 更正（总开关分流）：总开关开时字走 slate 底白字、原底与浮雕不画；
    // 总开关关时画原样式（与分类 chip 同款水胶囊底 / 壁纸底 + 浮雕）。
    // 3.07 第二阶段 C-edit：隐藏后不渲染（设置页可恢复）。
    if (ControlThemeStore.isElementHidden(filterChipElementId(text))) return
    val controlOn = ControlThemeStore.isControlThemeEnabled()
    val drawOriginal = ControlThemeStore.shouldDrawOriginalStyle()
    // 3.10：单元素浮雕 / 光感覆盖（源文件夹 chip）：与分类 chip 同款门控——
    // 总开关开时 fxGroupBevel 不挂、无可见效果，关时解析回全局；先接参数。
    val folderChipFx = resolveElementEffects(filterChipElementId(text), ControlElementKind.CHIP)
    ControlSurface(
        elementId = filterChipElementId(text),
        kind = ControlElementKind.CHIP,
        selected = selected,
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = { ControlEditState.requestEdit(filterChipElementId(text)) },
        )
            .then(
                if (!drawOriginal) Modifier
                else Modifier.clip(RoundedCornerShape(18.dp))
                    .background(wallpaperCardBackground(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), highTransparent = true))
            )
            .then(if (drawOriginal) Modifier.fxGroupBevel(RoundedCornerShape(18.dp), effects = folderChipFx) else Modifier),
        onClick = null,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (controlOn) controlContentColor(filterChipElementId(text), ControlElementKind.CHIP) else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun SourceMultiRow(
    title: String,
    checkable: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = checkable, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checkable) {
            Checkbox(checked = checked, onCheckedChange = null)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = if (checkable) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceCapsule(
    name: String,
    sourceKey: String,
    selected: Boolean,
    multiSelect: Boolean,
    checkable: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
) {
    val highlighted = if (multiSelect) checked else selected
    // 3.10 小编辑框：长按出编辑框（kind=CHIP，id=源 key）；点按切源不动。
    // 3.09 更正（总开关分流）：总开关开时 Surface 自身底色会盖住随心所欲 slate 底，
    // 故原底 / 水胶囊一律不画、字走白字，只留随心所欲层；关时画原样式（与同文件
    // SourceFolderChip / HomeCapsule 同款做法）。
    val elementId = sourceCapsuleElementId(sourceKey)
    val controlOn = ControlThemeStore.isControlThemeEnabled()
    val drawOriginal = ControlThemeStore.shouldDrawOriginalStyle()
    // 69: 高透明 fades the capsule base (fully transparent at 100).
    val htFactor = EffectsState.config.highTransparencyFactor
    fun faded(color: Color): Color =
        color.copy(alpha = color.alpha * htFactor)
    Surface(
        shape = RoundedCornerShape(50),
        color = if (!drawOriginal) {
            Color.Transparent
        } else if (highlighted) {
            faded(MaterialTheme.colorScheme.primaryContainer)
        } else {
            faded(MaterialTheme.colorScheme.surfaceBright)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { ControlEditState.requestEdit(elementId) },
            )
            .then(controlSurfaceModifier(elementId, ControlElementKind.CHIP, selected = highlighted)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (multiSelect && checkable) {
                Checkbox(checked = checked, onCheckedChange = null)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (controlOn) {
                    controlContentColor(elementId, ControlElementKind.CHIP)
                } else when {
                    highlighted -> MaterialTheme.colorScheme.onPrimaryContainer
                    !multiSelect || checkable -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val PREF_SOURCE_COLUMNS = "home_source_columns"
private const val PREF_BLOCKED_FOLDERS = "home_source_blocked_folders"

private val SourceFolderTagRegex = Regex("""\[([^\]]+)\]\([^)]*\)\s*$""")

// Folder of a source, by the same marker the local-source generator writes:
// the trailing "[folder](type)" part of the display name. Sources without
// that marker have no folder and only show under the "all" chip.
private fun sourceFolderTag(name: String?): String? {
    if (name.isNullOrEmpty()) return null
    val match = SourceFolderTagRegex.find(name) ?: return null
    return match.groupValues[1].trim().takeIf { it.isNotEmpty() }
}

private fun folderCountsOf(sources: List<SourceBean>): List<Pair<String, Int>> =
    sources.mapNotNull { sourceFolderTag(it.name) }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(compareBy({ it.value }, { it.key }))
        .map { it.key to it.value }

// Panel-level folder hiding only: stored in the panel's own Prefers keys,
// never touches the generator's settings or the config itself.
private fun loadBlockedFolders(): Set<String> =
    Prefers.getString(PREF_BLOCKED_FOLDERS)
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

private fun saveBlockedFolders(folders: Set<String>) {
    Prefers.put(PREF_BLOCKED_FOLDERS, folders.joinToString("\n"))
}

private fun canonPathOf(file: File): String =
    try {
        file.canonicalPath
    } catch (t: Throwable) {
        file.absolutePath
    }

private fun decodePath(value: String): String =
    try {
        android.net.Uri.decode(value)
    } catch (t: Throwable) {
        value
    }

// One raw site field (api/ext/jar) as it arrives after whichever loader ran,
// resolved to a real file when possible. Loaders leave these in several
// shapes: plain absolute paths and file:// URLs (hook expansion), http
// 127.0.0.1 /file/ proxy URLs (the native path fix for local configs),
// clan://localhost/ links, and untouched relative paths (resolved against
// the generated config's directory). Anything else (remote URLs, csp_ class
// names) resolves to nothing.
private fun siteFileCandidate(raw: String?, baseDir: File?): File? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    val path: String = when {
        value.startsWith("file://") -> decodePath(value.substring("file://".length))
        value.startsWith("clan://localhost/") -> {
            val rest = decodePath(value.substring("clan://localhost/".length))
            if (rest.startsWith("/")) {
                rest
            } else {
                android.os.Environment.getExternalStorageDirectory().absolutePath + "/" + rest
            }
        }
        value.startsWith("http://127.0.0.1") && value.contains("/file/") -> {
            val rest = decodePath(value.substring(value.indexOf("/file/") + "/file/".length))
            if (rest.startsWith("/")) rest else "/$rest"
        }
        value.startsWith("/") -> value
        value.contains("://") -> return null
        else -> {
            val base = baseDir ?: return null
            val file = File(base, value)
            return file.takeIf { it.isFile }
        }
    }
    return File(path).takeIf { it.isFile }
}

// A panel source counts as a local-source file when the local source feature
// is on and one of its api/ext/jar fields resolves to a real file inside one
// of the enabled scan folders. Anything else stays hands-off.
private fun resolveLocalSourceFiles(sources: List<SourceBean>): Map<String, File> {
    if (!Prefers.getBoolean("local_source_enabled", false)) return emptyMap()
    val settings = try {
        LocalSource.loadSettings()
    } catch (t: Throwable) {
        return emptyMap()
    }
    val roots = try {
        LocalSource.enabledDirs(settings)
    } catch (t: Throwable) {
        emptyList()
    }
    if (roots.isEmpty()) return emptyMap()
    val rootCanons = roots.map { canonPathOf(File(it)) }
    val baseDir = listOf(
        Prefers.getString("local_source_file"),
        try {
            LocalSource.outputPath(settings)
        } catch (t: Throwable) {
            null
        },
    ).firstNotNullOfOrNull { path ->
        path?.takeIf { it.isNotEmpty() }?.let { File(it).parentFile }
    }
    val out = LinkedHashMap<String, File>()
    for (bean in sources) {
        val key = bean.key.orEmpty()
        if (key.isEmpty() || out.containsKey(key)) continue
        for (raw in listOf(bean.ext, bean.api, bean.jar)) {
            val file = siteFileCandidate(raw, baseDir) ?: continue
            val canon = canonPathOf(file)
            if (rootCanons.any { canon == it || canon.startsWith(it + "/") }) {
                out[key] = file
                break
            }
        }
    }
    return out
}

private fun scanRootOf(file: File, roots: List<String>): String? {
    val canon = canonPathOf(file)
    return roots
        .map { canonPathOf(File(it)) }
        .filter { canon == it || canon.startsWith(it + "/") }
        .maxByOrNull { it.length }
}

private fun categoryFoldersOf(root: String): List<String> =
    try {
        File(root).listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted().orEmpty()
    } catch (t: Throwable) {
        emptyList()
    }

private data class BatchResult(val done: Int, val skipped: Int)

private fun moveFile(from: File, to: File): Boolean {
    if (from.renameTo(to)) return true
    return try {
        from.inputStream().use { input -> to.outputStream().use { output -> input.copyTo(output) } }
        if (to.isFile && to.length() == from.length()) {
            from.delete()
            true
        } else {
            to.delete()
            false
        }
    } catch (t: Throwable) {
        false
    }
}

// jar_bindings is keyed by source file path: follow the file on a move,
// drop the record on a delete. excluded_files is name-based and unaffected.
private fun remapJarBinding(oldFile: File, newFile: File?) {
    try {
        val settings = LocalSource.loadSettings()
        if (!settings.has("jar_bindings") || !settings.get("jar_bindings").isJsonObject) return
        val obj = settings.getAsJsonObject("jar_bindings")
        val oldCanon = canonPathOf(oldFile)
        val hits = obj.entrySet().map { it.key }.filter { canonPathOf(File(it)) == oldCanon }
        if (hits.isEmpty()) return
        for (key in hits) {
            val value = obj.get(key)
            obj.remove(key)
            if (newFile != null && value != null && value.isJsonPrimitive) {
                val newKey = if (key == oldCanon) canonPathOf(newFile) else newFile.absolutePath
                obj.add(newKey, value)
            }
        }
        LocalSource.updateSetting(obj, "jar_bindings")
    } catch (t: Throwable) {
    }
}

private fun batchMoveFiles(files: List<File>, category: String): BatchResult {
    val roots = try {
        LocalSource.enabledDirs(LocalSource.loadSettings())
    } catch (t: Throwable) {
        emptyList<String>()
    }
    var done = 0
    var skipped = 0
    for (file in files) {
        val root = scanRootOf(file, roots)
        if (root == null) {
            skipped++
            continue
        }
        val dir = File(root, category)
        if (!dir.isDirectory && !dir.mkdirs()) {
            skipped++
            continue
        }
        val target = File(dir, file.name)
        val ok = when {
            canonPathOf(target) == canonPathOf(file) -> true
            target.exists() -> false
            else -> moveFile(file, target)
        }
        if (ok) {
            remapJarBinding(file, target)
            done++
        } else {
            skipped++
        }
    }
    return BatchResult(done, skipped)
}

private fun batchDeleteFiles(files: List<File>): BatchResult {
    var done = 0
    var skipped = 0
    for (file in files) {
        if (file.isFile && file.delete()) {
            remapJarBinding(file, null)
            done++
        } else {
            skipped++
        }
    }
    return BatchResult(done, skipped)
}

private fun refreshLocalConfig(vm: HomeViewModel) {
    LocalSource.requestRefresh()
    Handler(Looper.getMainLooper()).post {
        try {
            ApiConfig.get().loadConfig(false, object : ApiConfig.LoadConfigCallback {
                override fun success() {
                    Handler(Looper.getMainLooper()).post { vm.reload() }
                }

                override fun error(msg: String?) {
                }

                override fun notice(msg: String?) {
                }
            }, null)
        } catch (t: Throwable) {
        }
    }
}
