package com.github.tvbox.osc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.util.TmdbApi
import com.github.tvbox.osc.util.TmdbPoster
import com.github.tvbox.osc.ui.theme.EffectsConfig
import coil3.Image
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transitionFactory
import coil3.transition.Transition

private const val UnnamedPosterKey = "！"

internal enum class PosterSize { THUMB, LARGE, WIDE, FULL }

internal const val HERO_IMAGE_LARGE_MAX_WIDTH_PX = 800

internal const val HERO_IMAGE_WIDE_MAX_WIDTH_PX = 1400

internal data class HeroImageSizes(val poster: PosterSize, val backdrop: PosterSize)

internal fun heroPosterSize(widthPx: Int): PosterSize =
    if (widthPx <= HERO_IMAGE_LARGE_MAX_WIDTH_PX) PosterSize.LARGE else PosterSize.FULL

internal fun heroBackdropSize(widthPx: Int): PosterSize = when {
    widthPx <= HERO_IMAGE_LARGE_MAX_WIDTH_PX -> PosterSize.LARGE
    widthPx <= HERO_IMAGE_WIDE_MAX_WIDTH_PX -> PosterSize.WIDE
    else -> PosterSize.FULL
}

@Composable
internal fun rememberHeroImageSizes(): HeroImageSizes {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val widthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    return remember(widthPx) {
        HeroImageSizes(heroPosterSize(widthPx), heroBackdropSize(widthPx))
    }
}

private const val POSTER_CHAR_WIDTH_RATIO = 0.46f

private val PosterPalette = intArrayOf(
    0xFFEF5350.toInt(),
    0xFFEC407A.toInt(),
    0xFFAB47BC.toInt(),
    0xFF7E57C2.toInt(),
    0xFF5C6BC0.toInt(),
    0xFF42A5F5.toInt(),
    0xFF29B6F6.toInt(),
    0xFF26C6DA.toInt(),
    0xFF26A69A.toInt(),
    0xFF66BB6A.toInt(),
    0xFF9CCC65.toInt(),
    0xFFD4E157.toInt(),
    0xFFFFEE58.toInt(),
    0xFFFFCA28.toInt(),
    0xFFFFA726.toInt(),
    0xFFFF7043.toInt(),
    0xFF8D6E63.toInt(),
    0xFFBDBDBD.toInt(),
    0xFF78909C.toInt(),
)

internal fun posterSeedColor(name: String?): Int {
    val key = posterFirstChar(name)
    return PosterPalette[(key.hashCode() and Int.MAX_VALUE) % PosterPalette.size]
}

internal fun posterFirstChar(name: String?): String {
    val text = name?.trim().orEmpty()
    if (text.isEmpty()) return UnnamedPosterKey
    return text.take(if (text[0].isHighSurrogate()) 2 else 1)
}

@Composable
internal fun VodPoster(
    name: String?,
    pic: String?,
    modifier: Modifier = Modifier,
    size: PosterSize = PosterSize.THUMB,
    year: Int = 0,
    sourceKey: String? = null,
    resolveTmdb: Boolean = true,
    tmdbCacheOnly: Boolean = false,
    altName: String? = null,
    onImage: ((Image) -> Unit)? = null,
    // 2.12 球体真变形：海报卡片才开（VodCard）；warpDegree = 0 时与原来完全一致。
    warp: Boolean = false,
    // 3.10：单元素曲面覆盖解析后的配置（VodCard 从 posterElementId 解析后
    // 传进来）；null = 全局 EffectsState.config。只影响 warpDegree（曲面
    // 开关 + 滑杆）与 curvatureOutward（内外方向）；浮雕 / 光感不在这里画，
    // 由外层 PressableCard 的 fxCardShadow 管。
    effects: EffectsConfig? = null,
) {
    val preferLarge = size != PosterSize.THUMB
    val tmdbUrl = if (resolveTmdb) tmdbPosterUrl(name, year, sourceKey, size, tmdbCacheOnly, altName) else null
    val siteUrl = if (preferLarge) VodImages.largePosterUrl(pic) else pic
    var showFallback by remember(pic) { mutableStateOf(false) }
    var posterUrl by remember(pic, siteUrl, tmdbUrl) {
        mutableStateOf(tmdbUrl ?: siteUrl)
    }
    // 2.12 球体：AsyncImage 加载到的位图（成功时存，失败/换图时清），
    // warp 时由 WarpedPosterCanvas 按球面网格画出来。
    var loadedImage by remember(posterUrl) { mutableStateOf<Image?>(null) }
    val context = LocalPlatformContext.current
    val request = remember(context, posterUrl, preferLarge) {
        ImageRequest.Builder(context)
            .data(posterUrl)
            .apply {
                if (preferLarge) crossfade(true) else transitionFactory(Transition.Factory.NONE)
            }
            .build()
    }
    val baseRequest = remember(context, pic) {
        ImageRequest.Builder(context)
            .data(pic)
            .transitionFactory(Transition.Factory.NONE)
            .build()
    }
    // 2.12：warp 时不画平铺底图——球体比矩形小，底图会从球边露出来。
    val cfg = effects ?: com.github.tvbox.osc.ui.theme.EffectsState.config
    val warpDegree = if (warp) cfg.warpDegree else 0f
    val baseUnderlay = preferLarge && !pic.isNullOrEmpty() && posterUrl != pic && !showFallback && warpDegree <= 0f

    Box(modifier = modifier) {
        if (showFallback) PosterFallback(name)
        if (baseUnderlay) {
            AsyncImage(
                model = baseRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        val posterState: (AsyncImagePainter.State) -> Unit = { state ->
            if (state is AsyncImagePainter.State.Error) {
                loadedImage = null
                val fallback = when {
                    tmdbUrl != null && posterUrl == tmdbUrl -> siteUrl
                    posterUrl != pic -> pic
                    else -> null
                }
                if (fallback != null && fallback != posterUrl) posterUrl = fallback else showFallback = true
            } else {
                showFallback = false
                if (state is AsyncImagePainter.State.Success) {
                    loadedImage = state.result.image
                    onImage?.invoke(state.result.image)
                }
            }
        }
        // 2.12：warp 且位图已到手时画球面网格；还在加载/失败/非位图时
        // 仍走 AsyncImage 平铺（兜底逻辑不变）。
        val warpBitmap = if (warp && warpDegree > 0f) {
            (loadedImage as? coil3.BitmapImage)?.bitmap?.asImageBitmap()
        } else null
        if (warpBitmap != null) {
            WarpedPosterCanvas(
                bitmap = warpBitmap,
                warpDegree = warpDegree,
                modifier = Modifier.fillMaxSize(),
                outward = cfg.curvatureOutward,
            )
        } else {
            AsyncImage(
                model = request,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                onState = posterState,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun tmdbPosterPath(
    name: String?,
    year: Int,
    sourceKey: String?,
    cacheOnly: Boolean = false,
    altName: String? = null,
): String? {
    if (name.isNullOrBlank() || sourceKey == TmdbApi.SOURCE_KEY || !TmdbPoster.isActive()) return ""
    val epoch by TmdbPoster.configEpoch.collectAsState()
    // 列表场景只读缓存不解析（进过详情页的片才有 TMDB 图），滚动期零网络请求
    if (cacheOnly) return TmdbPoster.cachedPoster(name, year, altName)
    var posterPath by remember(name, altName, year, epoch) {
        mutableStateOf(TmdbPoster.cachedPoster(name, year, altName))
    }
    LaunchedEffect(name, altName, year, epoch) {
        if (posterPath == null) posterPath = TmdbPoster.resolvePoster(name, year, altName) ?: ""
    }
    return posterPath
}

@Composable
internal fun tmdbPosterUrl(
    name: String?,
    year: Int,
    sourceKey: String?,
    size: PosterSize,
    cacheOnly: Boolean = false,
    altName: String? = null,
): String? =
    tmdbPosterPath(name, year, sourceKey, cacheOnly, altName)
        ?.takeIf { it.isNotEmpty() }
        ?.let { tmdbUrlOf(it, size) }

internal fun tmdbUrlOf(posterPath: String, size: PosterSize): String = when (size) {
    PosterSize.THUMB -> TmdbPoster.imageUrl(posterPath, false)
    PosterSize.LARGE -> TmdbPoster.imageUrl(posterPath, true)
    PosterSize.WIDE -> TmdbPoster.backdropUrl(posterPath)
    PosterSize.FULL -> TmdbPoster.fullPosterUrl(posterPath)
}

@Composable
private fun PosterFallback(name: String?) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(posterSeedColor(name))),
        contentAlignment = Alignment.Center,
    ) {
        val charHeight = minOf(maxWidth, maxHeight) * POSTER_CHAR_WIDTH_RATIO
        val charSize = with(LocalDensity.current) { charHeight.toSp() }
        Text(
            text = posterFirstChar(name),
            style = TextStyle(
                color = Color.White,
                fontSize = charSize,
                lineHeight = charSize,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            maxLines = 1,
            modifier = Modifier.padding(2.dp),
        )
    }
}
