package com.github.tvbox.osc.ui.page

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.github.tvbox.osc.R
import com.github.tvbox.osc.music.LxDiscover
import com.github.tvbox.osc.music.MusicPlatformNames
import com.github.tvbox.osc.music.MusicRepository
import com.github.tvbox.osc.music.MusicSession
import com.github.tvbox.osc.music.MusicSong
import com.github.tvbox.osc.music.MusicStore
import com.github.tvbox.osc.ui.components.SearchField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 音乐首页（对齐洛雪 / MusicFree 版式）。
 *
 * 一屏到底，从上到下：
 *   顶栏（源名 + 音源数 + 退出）
 *   搜索框（真输入框，回车即搜）
 *   源 chip（全部 + 每个启用的源，点哪个搜哪个）
 *   平台 chip（全部 + 当前源支持的平台，点哪个只搜哪个，也管发现区）
 *   内容区：四入口 / 推荐歌单 / 排行榜 / 搜索历史 / 我的歌单 / 结果列表
 *   底部迷你播放条（队列里有歌才出现，点它进播放详情）
 */
@Composable
fun MusicHomePage(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val sources by MusicStore.sources.collectAsState()
    val activeId by MusicStore.activeSourceId.collectAsState()
    val active = remember(activeId, sources) { sources.firstOrNull { it.id == activeId } }
    val searchable = remember(sources) { sources.filter { it.enabled && !it.codeMissing } }

    val searching by MusicRepository.searching.collectAsState()
    val searchError by MusicRepository.lastError.collectAsState()
    val historyList by MusicRepository.history.collectAsState()
    val queue by MusicSession.queue.collectAsState()
    val currentIndex by MusicSession.currentIndex.collectAsState()
    val current by MusicSession.current.collectAsState()
    val likedSet by MusicSession.liked.collectAsState()

    var query by remember { mutableStateOf("") }
    var scopeSourceId by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<MusicSong>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(Panel.HOME) }
    // 顶部「源管理」入口：打开后铺满，返回即关闭
    var manageOpen by remember { mutableStateOf(false) }

    // 平台切换：null = 全部；选中后搜索只搜该平台，发现区也跟着切
    var platformFilter by remember { mutableStateOf<String?>(null) }
    // 发现区平台 chip（2.95 用户口径）：列表代码写全五个，chip 只显示运行时
    // 能拉回非空数据的平台（榜单或推荐任一通即显示），不写死通/不通表；
    // 固定顺序：酷我/酷狗/QQ/网易云/咪咕
    var platforms by remember { mutableStateOf<List<String>>(emptyList()) }
    // 平台显示名：源自己报的 name 优先（小秋/小芸这类品牌名），再查正名表，最后写死表
    var platformLabels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // 发现区：推荐歌单 + 排行榜（直接调各平台榜单接口，脚本里没有）
    var charts by remember { mutableStateOf<List<LxDiscover.DiscoverChart>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<LxDiscover.DiscoverPlaylist>>(emptyList()) }
    var discoverLoading by remember { mutableStateOf(false) }
    // 五平台探测结果：平台 ->（榜单，推荐），切平台时直接取，不再重拉
    var discoverData by remember {
        mutableStateOf<Map<String, Pair<List<LxDiscover.DiscoverChart>, List<LxDiscover.DiscoverPlaylist>>>>(emptyMap())
    }
    // 点榜单/歌单后拉歌的加载态（复用结果页的加载行）
    var listLoading by remember { mutableStateOf(false) }
    // 结果页标题：搜索时是「搜索结果」，点榜单/歌单后是榜单/歌单名
    var resultTitle by remember { mutableStateOf("搜索结果") }

    // 发现区用哪个源：框了源就用那个，没框就用当前活动源
    val discoverSource = remember(scopeSourceId, active, searchable) {
        searchable.firstOrNull { it.id == scopeSourceId } ?: active
    }
    // 发现区用哪个平台：选了平台就用它，没选就用第一个支持的平台
    val discoverPlatform = platformFilter ?: platforms.firstOrNull().orEmpty()

    // 平台列表 + 发现区可用性探测（2.95 用户口径）：源一切换就重取（obtain 走 IO，
    // 脚本加载可能要等）。五个平台并行拉榜单 + 推荐，chip 只留至少一处有数据
    // 的平台；榜单/推荐各按自己的数据显隐，不写死通/不通表。
    LaunchedEffect(discoverSource) {
        val source = discoverSource
        if (source == null) {
            platforms = emptyList()
            platformLabels = emptyMap()
            discoverData = emptyMap()
            return@LaunchedEffect
        }
        val targets = if (scopeSourceId != null) {
            searchable.filter { it.id == scopeSourceId }
        } else {
            searchable
        }
        discoverLoading = true
        val loaded = withContext(Dispatchers.IO) {
            // 平台显示名：源自己报的 name 优先
            val names = LinkedHashMap<String, String>()
            for (s in targets) {
                val runtime = runCatching {
                    com.github.tvbox.osc.music.LxRuntime.obtain(s)
                }.getOrNull()
                if (runtime != null && runtime.isReady) {
                    for ((code, name) in runtime.platformNames) {
                        if (!names.containsKey(code)) names[code] = name
                    }
                }
            }
            val probed = coroutineScope {
                LxDiscover.ALL_PLATFORMS.map { p ->
                    async {
                        val c = runCatching { LxDiscover.charts(source, p) }.getOrNull().orEmpty()
                        val r = runCatching { LxDiscover.recommend(source, p) }.getOrNull().orEmpty()
                        p to (c to r)
                    }
                }.awaitAll().toMap()
            }
            names to probed
        }
        platformLabels = loaded.first
        val probed = loaded.second
        val available = LxDiscover.ALL_PLATFORMS.filter { p ->
            val pair = probed[p]
            pair != null && (pair.first.isNotEmpty() || pair.second.isNotEmpty())
        }
        discoverData = probed
        platforms = available
        // 当前选中的平台若探测不到数据，落回全部
        if (platformFilter != null && platformFilter !in available) platformFilter = null
        discoverLoading = false
    }

    // 发现区：切平台时直接取探测结果，不再重拉
    LaunchedEffect(discoverData, discoverPlatform, panel) {
        if (panel != Panel.HOME) return@LaunchedEffect
        val pair = discoverData[discoverPlatform]
        charts = pair?.first.orEmpty()
        playlists = pair?.second.orEmpty()
    }

    // 分页：洛雪每页 30 首，滚到底自动续
    var page by remember { mutableStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    fun doSearch(keyword: String) {
        val q = keyword.trim()
        if (q.isEmpty()) {
            toast("输入歌名或歌手")
            return
        }
        query = q
        panel = Panel.RESULT
        page = 1
        hasMore = false
        resultTitle = "搜索结果"
        scope.launch {
            val list = MusicRepository.search(q, scopeSourceId, 1, platformFilter)
            results = list
            // 满一页才认为还有下一页
            hasMore = list.size >= 30
            searched = true
        }
    }

    /** 续页：把第 page+1 页追加到结果后面 */
    fun loadMore() {
        if (loadingMore || !hasMore || searching) return
        val q = query.trim()
        if (q.isEmpty()) return
        loadingMore = true
        val next = page + 1
        scope.launch {
            val list = MusicRepository.search(q, scopeSourceId, next, platformFilter)
            if (list.isEmpty()) {
                hasMore = false
            } else {
                // 去掉和已有结果重复的
                val existing = results.map { it.dedupeKey }.toHashSet()
                val fresh = list.filter { existing.add(it.dedupeKey) }
                results = results + fresh
                page = next
                hasMore = fresh.isNotEmpty()
            }
            loadingMore = false
        }
    }

    /** 点榜单/歌单：拉歌进结果页（标题换成榜单/歌单名，不分页）。 */
    fun openDiscoverList(title: String, load: () -> List<MusicSong>) {
        listLoading = true
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { load() }.getOrNull().orEmpty()
            }
            results = list
            resultTitle = title
            panel = Panel.RESULT
            page = 1
            hasMore = false
            searched = true
            listLoading = false
        }
    }

    // 滚到底 3 项以内自动续页
    androidx.compose.runtime.LaunchedEffect(listState, hasMore, panel) {
        if (panel != Panel.RESULT || !hasMore) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last to info.totalItemsCount
        }.collect { (last, total) ->
            if (total > 0 && last >= total - 3) loadMore()
        }
    }

    /** 点一首：整批进队列并起播 */
    fun playSongs(songs: List<MusicSong>, index: Int) {
        if (songs.isEmpty()) return
        val vod = MusicSession.buildPlaybackVod(songs, index) ?: return
        // 这里不能靠 `context as? DetailActivity` 拿宿主：本页挂在 MainActivity 下，
        // 那个 cast 恒为 null，结果就是只弹 toast 不播放。
        // 直接走全局播放引擎，数据和起播都从单例进，绕开 Activity 依赖。
        val app = context.applicationContext
        runCatching {
            val engine = com.github.tvbox.osc.player.PlaybackService.engine(app)
            engine.setData(
                com.github.tvbox.osc.player.PlaybackSession(
                    vod,
                    MusicSession.MUSIC_SOURCE_KEY,
                    false,
                ),
            )
            runCatching { engine.play(true) }
        }.onFailure { com.github.tvbox.osc.util.LOG.e("music-play-failed " + it.message) }
        MusicSession.openPlayer(app)
        toast("正在播放：" + songs[index].title)
    }

    // 源管理页：铺满整屏，返回即关闭
    if (manageOpen) {
        MusicSourceManagePage(
            onDismiss = { manageOpen = false },
            onImported = { manageOpen = false },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        MusicTopBar(
            sourceName = active?.name.orEmpty(),
            sourceCount = searchable.size,
            onManage = { manageOpen = true },
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SearchField(
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = { doSearch(query) },
                        hint = "搜歌、歌手、专辑",
                    )
                }
            }

            item {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        SourceChip(
                            label = "全部",
                            selected = scopeSourceId == null,
                            onClick = { scopeSourceId = null },
                        )
                    }
                    items(items = searchable, key = { it.id }) { s ->
                        SourceChip(
                            label = s.name,
                            selected = scopeSourceId == s.id,
                            onClick = { scopeSourceId = s.id },
                        )
                    }
                }
            }

            // 平台 chip（2.95）：只显示运行时能拉回榜单或推荐数据的平台；点哪个，搜索与发现区都只走哪个
            if (platforms.isNotEmpty()) {
                item {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            SourceChip(
                                label = "全部",
                                selected = platformFilter == null,
                                onClick = {
                                    platformFilter = null
                                    if (panel == Panel.RESULT && searched && query.isNotBlank()) {
                                        doSearch(query)
                                    }
                                },
                            )
                        }
                        items(items = platforms, key = { it }) { p ->
                            SourceChip(
                                label = platformLabels[p] ?: MusicPlatformNames.properName(p) ?: LxDiscover.platformName(p),
                                selected = platformFilter == p,
                                onClick = {
                                    platformFilter = p
                                    if (panel == Panel.RESULT && searched && query.isNotBlank()) {
                                        doSearch(query)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            if (panel == Panel.HOME) {
                item {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        MusicEntry("热门", "🔥", Modifier.weight(1f)) { doSearch("热门") }
                        MusicEntry("新歌", "🆕", Modifier.weight(1f)) { doSearch("新歌") }
                        MusicEntry("我喜欢", "❤️", Modifier.weight(1f)) { panel = Panel.LIKED }
                        MusicEntry("队列", "📋", Modifier.weight(1f)) {
                            if (queue.isEmpty()) toast("队列是空的") else panel = Panel.QUEUE
                        }
                    }
                }

                // 发现区：推荐歌单 + 排行榜（跟着当前平台走）
                if (discoverLoading) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "榜单加载中…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else if (platforms.isEmpty()) {
                    // 2.95：五个平台都没拉回数据时才显示，不针对某个平台写死
                    item {
                        Text(
                            text = "榜单与推荐暂不可用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                } else {
                    if (playlists.isNotEmpty()) {
                        item {
                            Column {
                                Text(
                                    text = "推荐歌单",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                                Spacer(Modifier.height(8.dp))
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    items(items = playlists, key = { it.platform + "_" + it.id }) { pl ->
                                        PlaylistCard(
                                            playlist = pl,
                                            onClick = {
                                                openDiscoverList(pl.name) {
                                                    val s = discoverSource
                                                        ?: return@openDiscoverList emptyList()
                                                    LxDiscover.playlistSongs(s, pl.platform, pl.id)
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (charts.isNotEmpty()) {
                        item {
                            Text(
                                text = "排行榜",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                        items(items = charts, key = { it.platform + "_" + it.id }) { chart ->
                            ChartRow(
                                chart = chart,
                                onClick = {
                                    openDiscoverList(chart.name) {
                                        val s = discoverSource
                                            ?: return@openDiscoverList emptyList()
                                        LxDiscover.chartSongs(s, chart.platform, chart.id)
                                    }
                                },
                            )
                        }
                    }
                }

                if (historyList.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "搜索历史",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "清空",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        MusicRepository.clearHistory()
                                    },
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(historyList) { word ->
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        modifier = Modifier.clickable { doSearch(word) },
                                    ) {
                                        Text(
                                            text = word,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(
                                                horizontal = 12.dp,
                                                vertical = 6.dp,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            text = "我的歌单",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { panel = Panel.LIKED },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("我喜欢", style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = likedSet.size.toString() + " 首",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (searchable.isEmpty()) {
                    item { EmptySourceCard() }
                }
            }

            if (panel != Panel.HOME) {
                item {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = when (panel) {
                                Panel.RESULT -> resultTitle
                                Panel.LIKED -> "我喜欢"
                                else -> "播放队列"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "返回",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { panel = Panel.HOME },
                        )
                    }
                }

                val shown = when (panel) {
                    Panel.RESULT -> results
                    Panel.LIKED -> MusicSession.likedList()
                    else -> queue
                }

                if ((searching || listLoading) && panel == Panel.RESULT) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = if (listLoading && !searching) {
                                    "正在加载…"
                                } else {
                                    "正在搜 " + searchable.size + " 个源…"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (!searching && shown.isEmpty()) {
                    item {
                        Text(
                            text = when (panel) {
                                Panel.RESULT -> when {
                                    searchError.isNotBlank() -> searchError
                                    searched -> "没搜到结果，换个关键词或换音源试试"
                                    else -> ""
                                }

                                Panel.LIKED -> "还没收藏歌曲"
                                else -> "队列是空的"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }

                itemsIndexed(
                    items = shown,
                    key = { i, s -> MusicRepository.songKey(s) + "_" + i },
                ) { index, song ->
                    MusicRow(
                        song = song,
                        playing = panel == Panel.QUEUE && index == currentIndex,
                        liked = likedSet.contains(MusicRepository.songKey(song)),
                        onPlay = { playSongs(shown, index) },
                        onToggleLike = {
                            val now = MusicSession.toggleLiked(song)
                            toast(if (now) "已收藏" else "已取消收藏")
                        },
                    )
                }

                // 续页状态
                if (panel == Panel.RESULT) {
                    item {
                        when {
                            loadingMore -> Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "加载更多…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            !hasMore && shown.isNotEmpty() -> Text(
                                text = "— 共 ${shown.size} 首 —",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            )

                            hasMore -> Text(
                                text = "点击加载更多",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { loadMore() }
                                    .padding(16.dp),
                            )
                        }
                    }
                }
            }
        }

        current?.let { song ->
            MiniPlayer(song = song, onClick = { MusicSession.openPlayer(context) })
        }
    }
}

private enum class Panel { HOME, RESULT, LIKED, QUEUE }

// ============================================================ 顶栏

@Composable
private fun MusicTopBar(sourceName: String, sourceCount: Int, onManage: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // MainScreen 的 Scaffold 用了 contentWindowInsets = 0，
            // 内容会一直顶到屏幕最上面，被系统状态栏（时间/电量）压住。
            // 这里自己补一份状态栏高度，让标题和按钮落在状态栏下方。
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "音乐",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (sourceName.isBlank()) {
                    "$sourceCount 个音源"
                } else {
                    "$sourceName · $sourceCount 个音源"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.clickable(onClick = onManage),
        ) {
            Text(
                text = "源管理",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun SourceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun MusicEntry(
    title: String,
    emoji: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .height(80.dp)
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = emoji, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(text = title, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

// ============================================================ 歌曲行

@Composable
private fun MusicRow(
    song: MusicSong,
    playing: Boolean,
    liked: Boolean,
    onPlay: () -> Unit,
    onToggleLike: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (playing) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onPlay),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (playing) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = song.subtitle.ifBlank { song.platform.uppercase() },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (song.sourceName.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "· " + song.sourceName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (song.durationText.isNotBlank()) {
                Text(
                    text = song.durationText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
            }
            Icon(
                painter = painterResource(R.drawable.ic_music_heart),
                contentDescription = "收藏",
                tint = if (liked) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onToggleLike)
                    .padding(8.dp),
            )
        }
    }
}

// ============================================================ 发现区卡片

@Composable
private fun PlaylistCard(playlist: LxDiscover.DiscoverPlaylist, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable(onClick = onClick),
    ) {
        if (playlist.cover.isNotBlank()) {
            AsyncImage(
                model = playlist.cover,
                contentDescription = playlist.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "♪", style = MaterialTheme.typography.titleLarge)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (playlist.sub.isNotBlank()) {
            Text(
                text = playlist.sub,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ChartRow(chart: LxDiscover.DiscoverChart, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (chart.cover.isNotBlank()) {
                AsyncImage(
                    model = chart.cover,
                    contentDescription = chart.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "♪", style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chart.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (chart.sub.isNotBlank()) {
                    Text(
                        text = chart.sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ============================================================ 迷你条

@Composable
private fun MiniPlayer(song: MusicSong, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "♪", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "打开",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun EmptySourceCard() {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "当前没有可用音源",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "去「配置管理 → 音乐」导入一个洛雪音源脚本，导入后这里就能搜歌、播放。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}