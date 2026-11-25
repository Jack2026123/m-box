package com.github.tvbox.osc.ui.components

import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.request.ImageRequest
import com.github.tvbox.osc.ui.theme.EffectsState
import com.github.tvbox.osc.ui.theme.WallpaperStore
import java.io.File

/**
 * Global wallpaper backdrop (logtest42, model corrected in logtest43):
 * the same layer MainScreen draws under the four main tabs, reusable
 * by sub-page activities so the wallpaper is a truly global
 * background. Drawn first (bottom) inside the activity root; page
 * roots above are fully transparent while a wallpaper is linked
 * (see [wallpaperAwarePageBackground]) so the wallpaper shows
 * unobstructed — the old double veil (page root AND card each
 * carrying pageAlpha, 0.60 x 0.60 = 0.16 showing at 40) is removed.
 * Card surfaces still follow the 透明背景 slider (see
 * [wallpaperAwareContainerColor]): page alpha 0 = 0.75, 40 = 0.60,
 * 100 = 0. Backdrop blur and dark scrim follow the same slider:
 * both 0 up to level 40 (sharp, vivid wallpaper), then blur grows
 * to 32dp and scrim to 0.50 black at 100, so at 100 the backdrop is
 * a dark, blurred, translucent wash. Below 40 the wallpaper itself
 * gets a highlight lift (see [wallpaperHighlightColorFilter]): 40 =
 * original, 0 = brightest, deepest, most vivid. No white veil is
 * drawn here — the old wash (alpha growing with level, all-white
 * at 100) was inverted and is removed.
 * No-op when no wallpaper file exists. Same rules as MainScreen:
 * version-keyed Coil request (immediate switch on re-download) and
 * animated decoder for GIFs. No URL validation anywhere in this
 * chain (download validation lives in WallpaperStore only as
 * HTTP-success + non-empty body).
 */
@Composable
fun WallpaperBackdrop(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val wallpaperVersion = WallpaperStore.version
    val blurDp = EffectsState.config.wallpaperBlurDp
    val scrimAlpha = EffectsState.config.wallpaperScrimAlpha
    // r481：视频壁纸优先（与图片二选一）。播放器见下面的 VideoWallpaperPlayer。
    val videoFile = WallpaperStore.videoFile()
    if (videoFile != null) {
        VideoWallpaperPlayer(
            file = videoFile,
            version = wallpaperVersion,
            blurDp = blurDp.dp,
            scrimAlpha = scrimAlpha,
            modifier = modifier,
        )
        return
    }
    val wallpaperFile = WallpaperStore.file()
    if (wallpaperFile == null) return
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
    val wallpaperRequest = remember(wallpaperFile, wallpaperVersion) {
        ImageRequest.Builder(context)
            .data(wallpaperFile)
            .memoryCacheKey("wallpaper-$wallpaperVersion")
            .diskCacheKey("wallpaper-$wallpaperVersion")
            .build()
    }
    Box(modifier = modifier.fillMaxSize()) {
        AsyncImage(
            model = wallpaperRequest,
            imageLoader = wallpaperLoader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = wallpaperHighlightColorFilter(EffectsState.config.wallpaperHighlightT),
            modifier = Modifier.fillMaxSize()
                .blur(blurDp.dp, BlurredEdgeTreatment.Unbounded),
        )
        if (scrimAlpha > 0f) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha)),
            )
        }
    }
}

/**
 * r481：应用内视频壁纸（非手机桌面）。ExoPlayer + TextureView，循环静音，
 * center-crop 铺满（与图片壁纸的 ContentScale.Crop 一致）。用 TextureView
 * 不用 SurfaceView：TextureView 在视图层级内渲染，透明背景的 blur/scrim
 * 才能盖到视频上（SurfaceView 是独立图层，blur 盖不上）。播放器随组合
 * 创建、离开时释放；切视频时 version 变化，remember 重新建播放器。
 * 画面比例：TextureView 默认把画面拉伸铺满，这里按视频尺寸算裁剪矩阵，
 * 铺满且不变形（见 applyVideoCropTransform）。
 */
@Composable
fun VideoWallpaperPlayer(
    file: File,
    version: Int,
    blurDp: Dp,
    scrimAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember(file, version) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            play()
        }
    }
    // 视频尺寸（state 对象本身被 layout 监听闭包引用，值变化时 update 重算矩阵）。
    val videoSize = remember { mutableStateOf(0 to 0) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                videoSize.value = size.width to size.height
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    player.setVideoTextureView(this)
                    addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                        val (vw, vh) = videoSize.value
                        applyVideoCropTransform(v as TextureView, vw, vh)
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
                .blur(blurDp, BlurredEdgeTreatment.Unbounded),
            update = { textureView ->
                val (vw, vh) = videoSize.value
                applyVideoCropTransform(textureView, vw, vh)
            },
        )
        if (scrimAlpha > 0f) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha)),
            )
        }
    }
}

/**
 * TextureView center-crop 矩阵：画面先被拉伸铺满整个 view，再按视频
 * 比例各向异性缩放（k = max(viewW/videoW, viewH/videoH)，sx/sy 其一
 * 为 1、另一大于等于 1），居中裁剪，铺满且不变形。
 */
private fun applyVideoCropTransform(textureView: TextureView, videoW: Int, videoH: Int) {
    val viewW = textureView.width.toFloat()
    val viewH = textureView.height.toFloat()
    if (videoW <= 0 || videoH <= 0 || viewW <= 0f || viewH <= 0f) return
    val k = maxOf(viewW / videoW, viewH / videoH)
    val sx = k * videoW / viewW
    val sy = k * videoH / viewH
    val matrix = Matrix()
    matrix.setScale(sx, sy, viewW / 2f, viewH / 2f)
    textureView.setTransform(matrix)
}

/**
 * Frosted settings root (logtest49): records the wallpaper layer into a
 * backdrop that settings cards blur behind themselves, so sub-page cards
 * read as frosted glass like the home frosted bars instead of the flat
 * bright surfaceBright veil. The provider only exists in activities that
 * use this root (settings sub-pages); main tabs see null and keep the
 * flat card color untouched.
 */
val LocalWallpaperBackdrop = compositionLocalOf<Backdrop?> { null }

@Composable
fun WallpaperBackdropPage(content: @Composable () -> Unit) {
    val backdrop = rememberLayerBackdrop(onDraw = { drawContent() })
    CompositionLocalProvider(LocalWallpaperBackdrop provides backdrop) {
        Box(modifier = Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            WallpaperBackdrop()
        }
        content()
    }
}

/**
 * logtest50: card background under the 全局透明 master. With a wallpaper
 * linked and the master on, every card is fully transparent (wallpaper
 * original out); otherwise the caller's own color stands (individual
 * sliders/switches untouched).
 * 68: content call sites pass highTransparent=true to fade the base
 * with 高透明 (69: fully transparent at 100); settings call sites keep the default
 * false and stay exactly as before.
 */
@Composable
fun wallpaperCardBackground(default: Color, highTransparent: Boolean = false): Color {
    val hasWallpaper = rememberHasWallpaper()
    val globalTransparent = EffectsState.config.globalTransparent
    if (hasWallpaper && globalTransparent) return Color.Transparent
    return if (highTransparent) {
        default.copy(
            alpha = default.alpha * EffectsState.config.highTransparencyFactor,
        )
    } else default
}

/**
 * True when a usable wallpaper file exists. Reads [WallpaperStore.version]
 * so callers recompose on set/clear, not just on first composition.
 * r483：图片或视频任一在位都算（之前只认图片，视频壁纸会被不透明的页面底色盖住）。
 */
@Composable
fun rememberHasWallpaper(): Boolean {
    val version = WallpaperStore.version
    return version >= 0 && WallpaperStore.hasWallpaper()
}

/**
 * Card/surface color under the logtest43 wallpaper model: while a
 * wallpaper exists the surface follows the 透明背景 slider (page
 * alpha: 0 = 0.75, 40 = 0.60, 100 = 0); without a wallpaper the
 * opaque default is used. Page roots must NOT use this — see
 * [wallpaperAwarePageBackground] — or the wallpaper ends up under
 * two veils. Reads the version state so set/clear recomposes, and
 * the level state so slider drags recompose.
 */
@Composable
fun wallpaperAwareContainerColor(default: Color): Color {
    val hasWallpaper = rememberHasWallpaper()
    if (!hasWallpaper) return default
    return default.copy(alpha = EffectsState.config.wallpaperPageAlpha)
}


/**
 * Page-root background under the wallpaper model: fully
 * transparent while a wallpaper is linked, so the wallpaper layer
 * itself shows unmodified (40 = original photo colors, the top
 * acceptance); the opaque default returns without a wallpaper.
 * Only the card surfaces above keep a pageAlpha veil — one veil,
 * never two.
 */
@Composable
fun wallpaperAwarePageBackground(default: Color): Color {
    val hasWallpaper = rememberHasWallpaper()
    if (!hasWallpaper) return default
    return Color.Transparent
}

/**
 * Highlight lift for the wallpaper layer below level 40 ("打高光"):
 * 40 = identity (original colors), 0 = brightest, deepest, most
 * vivid. Translation-free saturation + brightness scale matrix, so
 * it is exact at both ends and smooth in between; null at t <= 0 so
 * level 40+ draws the image untouched.
 */
fun wallpaperHighlightColorFilter(t: Float): ColorFilter? {
    if (t <= 0f) return null
    val s = 1f + 0.30f * t
    val k = 1f + 0.12f * t
    val lr = 0.213f
    val lg = 0.715f
    val lb = 0.072f
    val m = floatArrayOf(
        k * (lr + (1f - lr) * s), k * (lg * (1f - s)), k * (lb * (1f - s)), 0f, 0f,
        k * (lr * (1f - s)), k * (lg + (1f - lg) * s), k * (lb * (1f - s)), 0f, 0f,
        k * (lr * (1f - s)), k * (lg * (1f - s)), k * (lb + (1f - lb) * s), 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
    return ColorFilter.colorMatrix(ColorMatrix(m))
}
