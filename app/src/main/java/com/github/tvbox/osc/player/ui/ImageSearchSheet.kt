package com.github.tvbox.osc.player.ui

import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fongmi.android.tv.utils.Util
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.imagesearch.ImageSearchApi
import com.github.tvbox.osc.player.imagesearch.ImageSearchPlay
import com.github.tvbox.osc.player.state.ImageSearchSheetState

/**
 * 识图搜片结果弹窗，仿 DanmuSearchSheet：PlayerDialog + SheetPanel，
 * 回调式 API + mainHandler 回主线程 + LaunchedEffect 自动搜。
 * 只显示 py 的分档（>=85 可信 / >=70 参考 / 其余碰运气），低分不写成搜到。
 * 点编号复制编号；缩略图不走 Coil（Coil 3 没有 per-request 请求头），照 py 的
 * fetch_thumb 走 OkHttp 内存直取（带 UA/Referer、进程内缓存 240 条、不落盘）。
 * 3.65：缩略图也能点——跟编号钮一个行为，竖屏点缩略图找片播，横屏点缩略图
 * 复制编号（之前缩略图点不动，用户点它以为不播）。
 * 竖屏（用户 2026-11-19 14:26 定）：结果面板不悬在中间，贴在播放器下面、
 * 盖住线路/选集/换源那片区域（播放器上面照常播）；点一条结果就直接播
 * （先当前源、再别的源，delegate 里做），横屏点编号仍是复制。
 */
@Composable
fun ImageSearchSheet(sheet: ImageSearchSheetState, onDismiss: () -> Unit) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    var results by remember { mutableStateOf<List<ImageSearchApi.VideoResult>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var playingCode by remember { mutableStateOf<String?>(null) }
    // 3.63：每条结果属于哪个源——avscan 结果里没有源，现搜（跟点编号播放同一套
    // 搜法，先当前源、再按序），后台并行跑。map 里没 key = 正在找，"" = 哪个源都
    // 没有，名字 = 找到。回调在后台线程，写 map 前 post 回主线程。
    val sourceNames = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(results) {
        val list = results ?: return@LaunchedEffect
        for (video in list) {
            if (!sourceNames.containsKey(video.code)) {
                ImageSearchPlay.findSourceName(video.code, sheet.currentSourceKey) { name ->
                    mainHandler.post { sourceNames[video.code] = name ?: "" }
                }
            }
        }
    }

    val search: () -> Unit = {
        loading = true
        results = null
        error = null
        sheet.onSearch(object : ImageSearchApi.SearchCallback {
            override fun onSuccess(list: List<ImageSearchApi.VideoResult>, ms: Long) {
                mainHandler.post {
                    loading = false
                    results = list
                    elapsedMs = ms
                }
            }

            override fun onError(message: String) {
                mainHandler.post {
                    loading = false
                    error = message
                }
            }
        })
    }

    LaunchedEffect(Unit) { search() }

    val onPlayClick: (String) -> Unit = { code ->
        if (playingCode == null) {
            playingCode = code
            sheet.onPlayCode?.invoke(code) { playingCode = null }
        }
    }

    PlayerDialog(onDismiss = onDismiss) {
        if (isPortrait) {
            // 竖屏：面板贴底，盖住播放器下面的线路/选集/换源区（播放器占 26%，面板占 74%）。
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.74f),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shadowElevation = 6.dp,
                ) {
                    Column {
                        ImageSearchPanelContent(
                            sheet = sheet,
                            isPortrait = true,
                            loading = loading,
                            error = error,
                            results = results,
                            elapsedMs = elapsedMs,
                            playingCode = playingCode,
                            sourceNames = sourceNames,
                            onPlayClick = onPlayClick,
                            onRetry = search,
                        )
                    }
                }
            }
        } else {
            SheetPanel(
                width = playerDim(R.dimen.vs_960),
                modifier = Modifier.height(playerDim(R.dimen.vs_480)),
            ) {
                ImageSearchPanelContent(
                    sheet = sheet,
                    isPortrait = false,
                    loading = loading,
                    error = error,
                    results = results,
                    elapsedMs = elapsedMs,
                    playingCode = playingCode,
                    sourceNames = sourceNames,
                    onPlayClick = onPlayClick,
                    onRetry = search,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.ImageSearchPanelContent(
    sheet: ImageSearchSheetState,
    isPortrait: Boolean,
    loading: Boolean,
    error: String?,
    results: List<ImageSearchApi.VideoResult>?,
    elapsedMs: Long,
    playingCode: String?,
    sourceNames: Map<String, String>,
    onPlayClick: (String) -> Unit,
    onRetry: () -> Unit,
) {
    Spacer(Modifier.height(playerDim(R.dimen.vs_30)))
    SheetTitle("识图搜片")
    Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
    Text(
        text = sheet.title + " · " + sheet.positionText,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = playerTextSize(R.dimen.ts_20),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = playerDim(R.dimen.vs_30)),
    )
    Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
    Box(
        Modifier
            .weight(1f)
            .padding(horizontal = playerDim(R.dimen.vs_30)),
    ) {
        if (loading) {
            SheetLoading(size = playerDim(R.dimen.vs_50))
        } else if (error != null) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = playerTextSize(R.dimen.ts_24),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(playerDim(R.dimen.vs_24)))
                SheetButton(text = "重新搜索", onClick = { onRetry() })
            }
        } else {
            val list = results ?: emptyList()
            if (list.isEmpty()) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "没搜到",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = playerTextSize(R.dimen.ts_24),
                    )
                }
            } else {
                Column {
                    Text(
                        text = "共 " + list.size + " 条 · 用时 " + (elapsedMs / 1000) + " 秒",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = playerTextSize(R.dimen.ts_20),
                    )
                    Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_10))) {
                        itemsIndexed(list) { _, video ->
                            Column {
                                // 3.64：找源中的“正在找源…”不要了（用户 2026-11-19 21:03 定），
                                // 没查出来先空着，找到直接显示源名。
                                Text(
                                    text = when {
                                        !sourceNames.containsKey(video.code) -> ""
                                        sourceNames[video.code].isNullOrEmpty() -> "都没搜到这个编号"
                                        else -> sourceNames[video.code]!!
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = playerTextSize(R.dimen.ts_20),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    SheetButton(
                                        text = video.code,
                                        onClick = {
                                            if (isPortrait && sheet.onPlayCode != null) {
                                                onPlayClick(video.code)
                                            } else {
                                                Util.copy(video.code)
                                            }
                                        },
                                    )
                                    Spacer(Modifier.width(playerDim(R.dimen.vs_10)))
                                    Text(
                                        text = if (playingCode == video.code) {
                                            "正在找片…"
                                        } else {
                                            ImageSearchApi.tierText(video.tier) + " " + video.sim + " 分"
                                        },
                                        color = when (video.tier) {
                                            ImageSearchApi.Tier.HIGH -> MaterialTheme.colorScheme.primary
                                            ImageSearchApi.Tier.MID -> MaterialTheme.colorScheme.tertiary
                                            ImageSearchApi.Tier.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        fontSize = playerTextSize(R.dimen.ts_20),
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                                Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_10)),
                                ) {
                                    video.frames.forEach { frame ->
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.clickable {
                                                if (isPortrait && sheet.onPlayCode != null) {
                                                    onPlayClick(video.code)
                                                } else {
                                                    Util.copy(video.code)
                                                }
                                            },
                                        ) {
                                            ThumbImage(frame.thumb)
                                            Text(
                                                text = frame.time + " · " + frame.sim + " 分",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = playerTextSize(R.dimen.ts_20),
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
    }
    Spacer(Modifier.height(playerDim(R.dimen.vs_30)))
}

/**
 * 缩略图：不用 Coil——Coil 3 的 ImageRequest 没有 per-request 请求头，
 * 而 py 的 fetch_thumb 是带 UA + Referer 取的；这里照 py 的路子走 OkHttp
 * 内存直取（ImageSearchApi.fetchThumb，不落盘），取到 Bitmap 直接画。
 */
@Composable
private fun ThumbImage(path: String) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var bitmap by remember(path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(path) {
        ImageSearchApi.fetchThumb(path) { bmp ->
            mainHandler.post { bitmap = bmp }
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(160.dp, 90.dp),
        )
    } else {
        Box(Modifier.size(160.dp, 90.dp))
    }
}
