package com.github.tvbox.osc.ui.music

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.annotation.DrawableRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.tvbox.osc.R
import com.github.tvbox.osc.music.AiDualTheme
import com.github.tvbox.osc.music.BuiltinDualTheme
import com.github.tvbox.osc.music.FloatLyric
import com.github.tvbox.osc.music.MusicAiTheme
import com.github.tvbox.osc.music.MusicBuiltinTheme
import com.github.tvbox.osc.music.MusicLyricOffset
import com.github.tvbox.osc.music.MusicLyricSearch
import com.github.tvbox.osc.music.MusicRepository
import com.github.tvbox.osc.music.MusicSong
import com.github.tvbox.osc.music.MusicVisualizer
import com.github.tvbox.osc.player.ui.CastSheet
import com.github.tvbox.osc.ui.components.AVBoxBottomSheet
import com.github.tvbox.osc.ui.components.ControlButton
import com.github.tvbox.osc.ui.components.ControlEditState
import com.github.tvbox.osc.ui.components.ControlElementKind
import com.github.tvbox.osc.ui.components.controlButtonModifier
import com.github.tvbox.osc.ui.components.controlContentColor
import com.github.tvbox.osc.ui.components.controlSurfaceModifier
import com.github.tvbox.osc.ui.components.ImagePalette
import com.github.tvbox.osc.ui.components.LocalTopBarGlassBackdrop
import com.github.tvbox.osc.ui.components.PosterBackdrop
import com.github.tvbox.osc.ui.components.ScallopShape
import com.github.tvbox.osc.ui.components.SettingsSwitchRow
import com.github.tvbox.osc.ui.components.TopBarActionBox
import com.github.tvbox.osc.ui.components.glassTopBarSurface
import com.github.tvbox.osc.ui.player.PlayerTipBridge
import com.github.tvbox.osc.ui.theme.AppThemeState
import com.github.tvbox.osc.ui.theme.ControlThemeStore
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.PermissionHelper
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

@Composable
fun MusicPlayerScreen(
    state: MusicPlayerState,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onSelectQueue: (Int) -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggleCollect: () -> Unit,
    onCast: () -> Unit,
    onSelectQuality: (String) -> Unit,
    // 2.95：搜歌词面板应用/导入/清除锁定后，强制重拉当前歌的歌词
    onReloadLyric: () -> Unit,
) {
    var queueVisible by remember { mutableStateOf(false) }
    var qualitySheet by remember { mutableStateOf(false) }
    // 2.95：搜歌词面板开关
    var lyricSearchSheet by remember { mutableStateOf(false) }
    val songKey = state.title + "|" + state.subtitle + "|" + state.queueIndex
    var seed by remember(songKey) { mutableStateOf<Int?>(null) }
    var lyricVisual by remember { mutableStateOf(KV.get(HawkConfig.MUSIC_LYRIC_VISUALIZER, true)) }
    // 辉光包络（2.90）：0 自动（按行时长选档）、1 柔和、2 快速、3 脉冲，默认自动
    var lyricGlowMode by remember { mutableStateOf(KV.get(HawkConfig.MUSIC_LYRIC_GLOW_MODE, 0).coerceIn(0, 3)) }
    var lyricFull by remember { mutableStateOf(false) }
    // 2.90 第 5 项 5B：全屏列表“回到当前行”按钮的触发计数（+1 即让全屏 MusicLyrics 跳回当前行）
    var lyricJumpTick by remember { mutableStateOf(0) }
    var settingsSheet by remember { mutableStateOf(false) }
    // 播放钮大小 + 呼吸方式（2.82）：状态提到播放页，长按手势和设置面板共用，改了立刻生效
    var buttonSizeIndex by remember { mutableStateOf(KV.get(HawkConfig.MUSIC_PLAY_BUTTON_SIZE, 1).coerceIn(0, PlayButtonSizes.lastIndex)) }
    var breathMode by remember { mutableStateOf(KV.get(HawkConfig.MUSIC_BREATH_MODE, 0).coerceIn(0, BreathModeNames.lastIndex)) }
    // 动效字体颜色（2.83）：0 自动（背景浅黑字、深白字）、1 白、2 黑、3 主题色，默认自动
    var lyricTextColorIndex by remember { mutableStateOf(KV.get(HawkConfig.MUSIC_LYRIC_TEXT_COLOR, 0).coerceIn(0, LyricTextColorNames.lastIndex)) }
    // 动效模板（2.90 第 5 项 5A）：存 String 短 id，默认流光（现状原样）；切歌不丢，点选即存即生效
    var lyricTemplateId by remember { mutableStateOf(KV.get(HawkConfig.MUSIC_LYRIC_TEMPLATE, "liuguang")) }
    // 2.98 舞台钮：进舞台前记下非舞台模板，退出舞台时回它（默认流光）
    var prevTemplateId by remember { mutableStateOf("liuguang") }
    // 2.98 舞台弹窗：点舞台钮打开，选哪个舞台就进哪个
    var stagePickerOpen by remember { mutableStateOf(false) }
    // 2.99 退出舞台：回进舞台前记下的非舞台模板并存 KV；全屏舞台 Dialog 的返回键/退出钮、舞台弹窗的退出舞台共用
    val exitStage: () -> Unit = {
        lyricTemplateId = prevTemplateId
        KV.put(HawkConfig.MUSIC_LYRIC_TEMPLATE, lyricTemplateId)
    }
    // 歌词浮窗（2.87）：系统悬浮窗，歌词浮在别的 App 上面；开要先给悬浮窗权限
    var floatLyricEnabled by remember { mutableStateOf(FloatLyric.isEnabled()) }
    val floatLyricContext = LocalContext.current
    // 2.92 歌词按歌校准：偏移按歌存（切歌回零、回来还记得）；key 用平台+id，悬浮窗共用同一把
    val lyricSongKey = remember(state.title, state.subtitle, state.musicPlatform, state.musicId) {
        MusicLyricOffset.songKey(
            state.musicPlatform.ifBlank { null },
            state.musicId.ifBlank { null },
            state.title,
            state.subtitle,
        )
    }
    var lyricFineMs by remember(lyricSongKey) { mutableStateOf(MusicLyricOffset.get(lyricSongKey).fineOffsetMs) }
    var lyricScale by remember(lyricSongKey) { mutableStateOf(MusicLyricOffset.get(lyricSongKey).scale) }
    var lyricShiftMs by remember(lyricSongKey) { mutableStateOf(MusicLyricOffset.get(lyricSongKey).shiftMs) }
    var twoPointArmed by remember(lyricSongKey) { mutableStateOf(false) }
    var twoPointPending by remember(lyricSongKey) { mutableStateOf(MusicLyricOffset.hasPending(lyricSongKey)) }
    LaunchedEffect(lyricSongKey) {
        val o = MusicLyricOffset.get(lyricSongKey)
        lyricFineMs = o.fineOffsetMs
        lyricScale = o.scale
        lyricShiftMs = o.shiftMs
        twoPointArmed = false
        twoPointPending = MusicLyricOffset.hasPending(lyricSongKey)
    }
    val lyricOff = MusicLyricOffset.Offsets(lyricFineMs, lyricScale, lyricShiftMs)
    // 歌词轴位置：音频时间 = scale * 歌词时间 + shift，fine 加在播放位置上；
    // 列表与动效都按歌词轴算，逐字填充不再漂。
    val lyricPositionMs = ((state.positionMs + lyricFineMs - lyricShiftMs) / lyricScale).toLong()
    val darkTheme = AppThemeState.isDark(isSystemInDarkTheme())
    // 每次打开换主题（2.90）：默认关 = 同一首歌每次打开主题相同（roll 按歌确定）；
    // 开 = 每次打开重抽（roll 与内置主题生成都用新随机数）。
    var themeReroll by remember { mutableStateOf(KV.get(HawkConfig.MUSIC_THEME_REROLL, false)) }
    val themeRoll = remember(songKey, themeReroll) {
        if (themeReroll) Random.nextInt() else songKey.hashCode()
    }
    var aiTheme by remember(songKey) { mutableStateOf<AiDualTheme?>(MusicAiTheme.cached(songKey)) }
    LaunchedEffect(songKey) {
        if (aiTheme != null) return@LaunchedEffect
        val snippet = state.lyrics.take(8).joinToString("\n") { it.text }.take(600)
        val generated = MusicAiTheme.generate(state.title, state.subtitle, snippet)
        if (generated != null) {
            MusicAiTheme.putCache(songKey, generated)
            aiTheme = generated
        }
    }
    // 内置双主题（2.90）：封面色一到即在 Dispatchers.Default 上同步纯计算生成，
    // 按歌缓存；无封面/封面加载失败时 coverSwatches 为空列表，走随机取色回落。
    var coverSwatches by remember(songKey) { mutableStateOf<List<Int>?>(null) }
    var builtinTheme by remember(songKey) {
        mutableStateOf(if (themeReroll) null else MusicBuiltinTheme.cached(songKey))
    }
    LaunchedEffect(songKey) {
        if (state.artwork.isEmpty()) coverSwatches = emptyList()
    }
    LaunchedEffect(songKey, coverSwatches, themeReroll) {
        val colors = coverSwatches ?: return@LaunchedEffect
        val generated = withContext(Dispatchers.Default) {
            if (themeReroll) {
                MusicBuiltinTheme.generateBuiltinDualTheme(colors, Random(Random.nextInt()))
            } else {
                MusicBuiltinTheme.cached(songKey)
                    ?: MusicBuiltinTheme.generateBuiltinDualTheme(colors, Random(songKey.hashCode().toLong()))
                        .also { MusicBuiltinTheme.putCache(songKey, it) }
            }
        }
        builtinTheme = generated
    }
    val builtinModeTheme = builtinTheme?.let { if (darkTheme) it.dark else it.light }
    val aiPrimary = aiTheme?.let { if (darkTheme) it.dark.primaryColor else it.light.primaryColor }
    val aiWordColors = aiTheme?.let { if (darkTheme) it.dark.wordColors else it.light.wordColors }.orEmpty()
    // 回落链：AI 主题 → 内置双主题 → 旧 rollSeed（封面主色/随机）。
    // 内置主题的 wordColors 为空，歌词动效仍用既有 themeSeed/themeRoll 配色。
    val themeSeed = if (aiPrimary != null) {
        MusicPlayerTheme.rollSeed(aiPrimary, themeRoll)
    } else if (builtinModeTheme != null) {
        MusicPlayerTheme.rollSeed(builtinModeTheme.primaryColor, themeRoll)
    } else {
        MusicPlayerTheme.rollSeed(seed, themeRoll)
    }
    val currentThemeName = aiTheme?.let { if (darkTheme) it.dark.name else it.light.name }
        ?: builtinModeTheme?.name.orEmpty()
    val colorScheme = remember(themeSeed, darkTheme, builtinModeTheme, aiTheme) {
        val base = AppThemeState.customScheme(themeSeed, darkTheme, AppThemeState.config.style)
        // 无 AI 且有内置主题时，四色直拼 ColorScheme（方案 B）；其余走旧链路。
        if (aiTheme == null && builtinModeTheme != null) {
            MusicBuiltinTheme.colorScheme(builtinModeTheme, darkTheme, base)
        } else {
            base
        }
    }

    MaterialTheme(colorScheme = colorScheme) {
        val backdrop = rememberLayerBackdrop(onDraw = { drawContent() })
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            Box(modifier = Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                PosterBackdrop(state.artwork)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                CompositionLocalProvider(LocalTopBarGlassBackdrop provides backdrop) {
                    MusicTopBar(
                        sourceName = state.sourceName,
                        qualityLabel = if (state.musicMode && state.quality.isNotBlank()) {
                            com.github.tvbox.osc.music.MusicQuality.label(state.quality)
                        } else {
                            ""
                        },
                        onBack = onBack,
                        onQualityClick = { qualitySheet = true },
                    )
                }

                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val side = minOf(maxWidth * 0.84f, maxHeight)
                    MusicCover(
                        artwork = state.artwork,
                        side = side,
                        playing = state.playing,
                        // 2.90：无条件赋值（含 null），否则切到无封面歌时主题串台。
                        onSeed = { argb -> seed = argb },
                        onSwatches = { coverSwatches = it },
                    )
                }

                MusicInfo(title = state.title, subtitle = state.subtitle, themeName = currentThemeName)

                if (state.lyrics.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            // 2.82：歌词框加大（封面收一点，歌词区多占高度），跳动的字不全屏也能全显示
                            .weight(1.6f)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                    ) {
                        LyricControlRow(
                            visual = lyricVisual,
                            onToggleVisual = {
                                lyricVisual = !lyricVisual
                                KV.put(HawkConfig.MUSIC_LYRIC_VISUALIZER, lyricVisual)
                            },
                            onFullscreen = { lyricFull = true },
                            // 2.98 舞台钮：点一下出弹窗，选哪个舞台就进哪个（不再循环切换）
                            isStage = lyricTemplateId in StageTemplateIds,
                            onOpenStagePicker = { stagePickerOpen = true },
                            offsetMs = lyricFineMs,
                            totalOffsetMs = lyricOff.totalApprox,
                            scale = lyricScale,
                            twoPointArmed = twoPointArmed,
                            onToggleTwoPoint = {
                                twoPointArmed = !twoPointArmed
                                if (!twoPointArmed) {
                                    MusicLyricOffset.clearPending(lyricSongKey)
                                    twoPointPending = false
                                } else {
                                    Toast.makeText(floatLyricContext, "点两句正在唱的歌词", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onOffsetChange = {
                                lyricFineMs = it
                                MusicLyricOffset.setFine(lyricSongKey, it)
                            },
                            onResetAll = {
                                MusicLyricOffset.reset(lyricSongKey)
                                lyricFineMs = 0
                                lyricScale = 1.0
                                lyricShiftMs = 0
                                twoPointArmed = false
                                twoPointPending = false
                                Toast.makeText(floatLyricContext, "已回零", Toast.LENGTH_SHORT).show()
                            },
                        )
                        if (lyricVisual) {
                            MusicLyricVisualizer(
                                lines = state.lyrics,
                                positionMs = lyricPositionMs,
                                durationMs = state.durationMs,
                                seedArgb = themeSeed,
                                themeRoll = themeRoll,
                                playing = state.playing,
                                wordColors = aiWordColors,
                                textColorMode = lyricTextColorIndex,
                                template = LyricVisualTemplate.fromId(lyricTemplateId),
                                songKey = songKey,
                                glowMode = lyricGlowMode,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            )
                        } else {
                            MusicLyrics(
                                lines = state.lyrics,
                                positionMs = lyricPositionMs,
                                onSeekLine = { lyricMs ->
                                    if (twoPointArmed) {
                                        val r = MusicLyricOffset.calibrateTwoPointStep(lyricSongKey, lyricMs, state.positionMs)
                                        lyricFineMs = r.offsets.fineOffsetMs
                                        lyricScale = r.offsets.scale
                                        lyricShiftMs = r.offsets.shiftMs
                                        twoPointPending = r.pending
                                        if (r.done) twoPointArmed = false
                                        Toast.makeText(floatLyricContext, r.message, Toast.LENGTH_SHORT).show()
                                    } else {
                                        onSeek(lyricOff.mapLyricTime(lyricMs))
                                    }
                                },
                                onCalibrateLine = { line ->
                                    val o = MusicLyricOffset.calibrateOnePoint(lyricSongKey, line.timeMs, state.positionMs)
                                    lyricFineMs = o.fineOffsetMs
                                    lyricScale = o.scale
                                    lyricShiftMs = o.shiftMs
                                    twoPointArmed = false
                                    twoPointPending = false
                                    Toast.makeText(floatLyricContext, "已按本歌对齐", Toast.LENGTH_SHORT).show()
                                },
                                centered = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            )
                        }
                    }
                } else if (state.musicMode && state.lyricEmpty != LyricEmptyState.NONE) {
                    // 2.95 歌词空态：纯音乐 / 暂无歌词（歌词区同一高度，不挤播放控制）
                    Column(
                        modifier = Modifier
                            .weight(1.6f)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = if (state.lyricEmpty == LyricEmptyState.INSTRUMENTAL) "纯音乐，请欣赏" else "暂无歌词",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // 3.07 百变控件：无歌词时搜歌词钮接控件主题，长按调编辑。
                            // 3.07 第二阶段 C-edit：隐藏后不渲染（设置页可恢复）。
                            if (!ControlThemeStore.isElementHidden("music_lyric_search")) {
                                ControlButton(
                                    buttonId = "music_lyric_search",
                                    onClick = { lyricSearchSheet = true },
                                    onLongPress = { ControlEditState.requestEdit("music_lyric_search") },
                                ) {
                                    Text("搜歌词", style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                            if (state.lyricEmpty == LyricEmptyState.NOT_FOUND) {
                                // 3.07 百变控件：导入歌词钮接控件主题，长按调编辑。
                                // 3.07 第二阶段 C-edit：隐藏后不渲染（设置页可恢复）。
                                if (!ControlThemeStore.isElementHidden("music_lyric_import")) {
                                    ControlButton(
                                        buttonId = "music_lyric_import",
                                        onClick = { lyricSearchSheet = true },
                                        onLongPress = { ControlEditState.requestEdit("music_lyric_import") },
                                    ) {
                                        Text("导入歌词", style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                            }
                        }
                    }
                }

                MusicWaveProgress(
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    waveSeed = state.waveSeed,
                    playing = state.playing,
                    onSeek = onSeek,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 16.dp)
                        .padding(top = 12.dp),
                )

                MusicControls(
                    playing = state.playing,
                    buffering = state.buffering,
                    onTogglePlay = onTogglePlay,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    buttonSizeIndex = buttonSizeIndex,
                    breathMode = breathMode,
                    onSizeChange = {
                        buttonSizeIndex = it
                        KV.put(HawkConfig.MUSIC_PLAY_BUTTON_SIZE, it)
                    },
                    onBreathChange = {
                        breathMode = it
                        KV.put(HawkConfig.MUSIC_BREATH_MODE, it)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                )

                Spacer(Modifier.height(22.dp))

                CompositionLocalProvider(LocalTopBarGlassBackdrop provides backdrop) {
                    MusicBottomActions(
                        playMode = state.playMode,
                        collected = state.collected,
                        onCyclePlayMode = onCyclePlayMode,
                        onToggleCollect = onToggleCollect,
                        onCast = onCast,
                        onOpenQueue = { queueVisible = true },
                        onOpenSettings = { settingsSheet = true },
                    )
                }

                Spacer(Modifier.height(16.dp))
            }

            MusicTipOverlay()
        }
        // 音质切档面板
        if (qualitySheet) {
            AVBoxBottomSheet(
                onDismissRequest = { qualitySheet = false },
                title = "音质",
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                isScrollable = false,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    state.qualityOptions.forEach { q ->
                        val current = q == state.quality
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    qualitySheet = false
                                    onSelectQuality(q)
                                }
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = com.github.tvbox.osc.music.MusicQuality.label(q),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (current) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = q,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (current) {
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "✓",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
        if (queueVisible) {
            AVBoxBottomSheet(
                onDismissRequest = { queueVisible = false },
                title = stringResource(R.string.music_queue),
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                isScrollable = false,
            ) {
                val queueListState = rememberLazyListState()
                LaunchedEffect(Unit) {
                    if (state.queueIndex in state.queue.indices) {
                        queueListState.scrollToItem(state.queueIndex)
                    }
                }
                LazyColumn(
                    state = queueListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    itemsIndexed(state.queue) { index, name ->
                        val current = index == state.queueIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    queueVisible = false
                                    onSelectQueue(index)
                                }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = name.ifEmpty { stringResource(R.string.music_song_index, index + 1) },
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (current) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (current) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // 歌词全屏（2.82）：点歌词行的「全屏」直接出来，当前是动效就全屏动效、是列表就全屏列表；点关闭或返回收回
        if (lyricFull && state.lyrics.isNotEmpty()) {
            Dialog(
                onDismissRequest = { lyricFull = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                ) {
                    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 3.05：歌词全屏只留歌手，不要歌名——音乐源歌 title=歌名/subtitle=歌手，py 歌 title=歌手/subtitle=歌名，按 musicMode 取歌手那一边。
                            Text(
                                text = if (state.musicMode) state.subtitle else state.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            // 3.07 百变控件：歌词全屏“回到当前行”钮接控件主题，长按调编辑。
                            // 3.07 第二阶段 C-edit：隐藏后不渲染（设置页可恢复）。
                            if (!ControlThemeStore.isElementHidden("music_lyric_jump_current")) {
                                ControlButton(
                                    buttonId = "music_lyric_jump_current",
                                    onClick = { lyricJumpTick++ },
                                    onLongPress = { ControlEditState.requestEdit("music_lyric_jump_current") },
                                ) {
                                    Text(text = "回到当前行", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            // 3.07 百变控件：歌词全屏关闭钮接控件主题，长按调编辑。
                            // 3.07 第二阶段 C-edit：隐藏后不渲染（设置页可恢复）。
                            if (!ControlThemeStore.isElementHidden("music_lyric_close")) {
                                ControlButton(
                                    buttonId = "music_lyric_close",
                                    onClick = { lyricFull = false },
                                    onLongPress = { ControlEditState.requestEdit("music_lyric_close") },
                                ) {
                                    Text(text = "关闭", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (lyricVisual) {
                            MusicLyricVisualizer(
                                lines = state.lyrics,
                                positionMs = lyricPositionMs,
                                durationMs = state.durationMs,
                                seedArgb = themeSeed,
                                themeRoll = themeRoll,
                                playing = state.playing,
                                wordColors = aiWordColors,
                                textColorMode = lyricTextColorIndex,
                                template = LyricVisualTemplate.fromId(lyricTemplateId),
                                songKey = songKey,
                                glowMode = lyricGlowMode,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                        } else {
                            MusicLyrics(
                                lines = state.lyrics,
                                positionMs = lyricPositionMs,
                                onSeekLine = { lyricMs ->
                                    if (twoPointArmed) {
                                        val r = MusicLyricOffset.calibrateTwoPointStep(lyricSongKey, lyricMs, state.positionMs)
                                        lyricFineMs = r.offsets.fineOffsetMs
                                        lyricScale = r.offsets.scale
                                        lyricShiftMs = r.offsets.shiftMs
                                        twoPointPending = r.pending
                                        if (r.done) twoPointArmed = false
                                        Toast.makeText(floatLyricContext, r.message, Toast.LENGTH_SHORT).show()
                                    } else {
                                        onSeek(lyricOff.mapLyricTime(lyricMs))
                                    }
                                },
                                onCalibrateLine = { line ->
                                    val o = MusicLyricOffset.calibrateOnePoint(lyricSongKey, line.timeMs, state.positionMs)
                                    lyricFineMs = o.fineOffsetMs
                                    lyricScale = o.scale
                                    lyricShiftMs = o.shiftMs
                                    twoPointArmed = false
                                    twoPointPending = false
                                    Toast.makeText(floatLyricContext, "已按本歌对齐", Toast.LENGTH_SHORT).show()
                                },
                                jumpToCurrentTick = lyricJumpTick,
                                centered = true,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        // 2.98 舞台弹窗：点舞台钮出来，选哪个舞台就进哪个；排数按名字长短走（字多 2–3 排，字少 3–4 排）
        // 2.99：三款舞台全是镜台式全屏 3D 舞台，选中即进全屏 Dialog
        if (stagePickerOpen) {
            Dialog(onDismissRequest = { stagePickerOpen = false }) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("舞台", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))
                        // 选项：三款舞台；在舞台上时多一个退出舞台
                        // 3.00：舞台名用 StageTemplateNames——动效模板表已不含舞台，LyricTemplateNames 里查不到舞台 id
                        val options = buildList {
                            StageTemplateIds.forEachIndexed { i, id ->
                                add(StageTemplateNames[i] to id)
                            }
                            if (lyricTemplateId in StageTemplateIds) add("退出舞台" to "")
                        }
                        val maxLen = options.maxOf { it.first.length }
                        val columns = when {
                            maxLen > 4 -> 2
                            maxLen > 3 -> 3
                            else -> 4
                        }
                        options.chunked(columns).forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                rowItems.forEach { (name, id) ->
                                    val selected = lyricTemplateId == id
                                    Button(
                                        onClick = {
                                            stagePickerOpen = false
                                            if (id.isEmpty()) {
                                                exitStage()
                                            } else {
                                                if (lyricTemplateId !in StageTemplateIds) prevTemplateId = lyricTemplateId
                                                lyricTemplateId = id
                                                lyricFull = false
                                            }
                                            KV.put(HawkConfig.MUSIC_LYRIC_TEMPLATE, lyricTemplateId)
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        ),
                                    ) {
                                        Text(name, style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                                // 补齐空位，让每行等宽
                                repeat(columns - rowItems.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }

        // 2.99 全屏 3D 舞台（镜台）：三款舞台（演唱会/剧院/星空舞台）统一走全屏 Dialog——黑底星空 + 飘的立体点块 +
        // 3D 透视歌词 + 只留退出与进度。配色/扫速沿用各模板原参数；返回键与退出钮都回 prevTemplateId。
        if (lyricTemplateId in StageTemplateIds) {
            FullscreenStageDialog(
                template = LyricVisualTemplate.fromId(lyricTemplateId),
                lines = state.lyrics,
                lyricPositionMs = lyricPositionMs,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                playing = state.playing,
                songKey = songKey,
                onSeek = onSeek,
                onExit = exitStage,
                onStageChange = { id ->
                    lyricTemplateId = id
                    KV.put(HawkConfig.MUSIC_LYRIC_TEMPLATE, id)
                },
            )
        }

        // 播放设置（2.82）：底栏齿轮点开，播放按钮大小四档 + 呼吸方式四档，点了立刻生效，面板不关可以两样一起改
        // 2.83：第三组动效字体颜色（自动/白/黑/主题色）
        // 2.84：面板能滑（isScrollable=true）、组名加大字、四组选项全改成胶囊一排（选中主题主色底）
        // 2.89：变声整组删了（真机上变声不行），面板剩四组
        if (settingsSheet) {
            AVBoxBottomSheet(
                onDismissRequest = { settingsSheet = false },
                title = "播放设置",
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                // 2.84：面板能滑了——之前 isScrollable=false，四组 20 行顶出屏，底下的变声/字体颜色点不到
                isScrollable = true,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 2.90：当前主题 + 每次打开换主题开关，放在面板最顶上。
                    Text(
                        text = "当前主题：" + currentThemeName.ifBlank { "默认" },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                    SettingsSwitchRow(
                        title = "每次打开换主题",
                        subtitle = "关：同一首歌每次打开主题相同；开：每次打开重新抽取",
                        checked = themeReroll,
                        onCheckedChange = { checked ->
                            themeReroll = checked
                            KV.put(HawkConfig.MUSIC_THEME_REROLL, checked)
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    // 2.84：组名加大字（titleLarge 加粗），选项改成胶囊一排——选中那个是主题主色底，一眼认得出
                    Text(
                        text = "播放胶囊大小",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PlayButtonSizeNames.forEachIndexed { index, name ->
                            SettingsChip(
                                text = name,
                                selected = index == buttonSizeIndex,
                                onClick = {
                                    buttonSizeIndex = index
                                    KV.put(HawkConfig.MUSIC_PLAY_BUTTON_SIZE, index)
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "呼吸方式",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BreathModeNames.forEachIndexed { index, name ->
                            SettingsChip(
                                text = name,
                                selected = index == breathMode,
                                onClick = {
                                    breathMode = index
                                    KV.put(HawkConfig.MUSIC_BREATH_MODE, index)
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "动效字体颜色",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LyricTextColorNames.forEachIndexed { index, name ->
                            SettingsChip(
                                text = name,
                                selected = index == lyricTextColorIndex,
                                onClick = {
                                    lyricTextColorIndex = index
                                    KV.put(HawkConfig.MUSIC_LYRIC_TEXT_COLOR, index)
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    // 2.90 第 5 项 5A：动效模板——流光（默认，现状原样）/ 青澜 / 星图 / 霓虹（2.91 替换第 4 款）/ 演唱会 / 剧院 / 星空舞台（2.98 舞台三款）/ 气泡 / 云阶 / 空间画布 / 雨天 / 四季 / 雾笺 / 町景 / 商籁（2.99 补 ICHIGOMUSIC 11 款里缺的 8 款）；动效关时也不置灰（与“动效字体颜色”组同待遇）
                    Text(
                        text = "动效模板",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LyricTemplateNames.forEachIndexed { index, name ->
                            SettingsChip(
                                text = name,
                                selected = index == LyricTemplateIds.indexOf(lyricTemplateId),
                                onClick = {
                                    val id = LyricTemplateIds[index]
                                    // 3.00：动效模板里已无舞台——这里只会点到 12 款动效模板，舞台只在舞台弹窗里进
                                    lyricTemplateId = id
                                    KV.put(HawkConfig.MUSIC_LYRIC_TEMPLATE, lyricTemplateId)
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "辉光包络",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LyricGlowModeNames.forEachIndexed { index, name ->
                            SettingsChip(
                                text = name,
                                selected = index == lyricGlowMode,
                                onClick = {
                                    lyricGlowMode = index
                                    KV.put(HawkConfig.MUSIC_LYRIC_GLOW_MODE, index)
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "歌词浮窗",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SettingsChip(
                            text = "开",
                            selected = floatLyricEnabled,
                            onClick = {
                                val ctx = floatLyricContext
                                if (!FloatLyric.hasPermission(ctx)) {
                                    try {
                                        ctx.startActivity(FloatLyric.requestPermissionIntent(ctx))
                                    } catch (ignored: Throwable) {
                                    }
                                    return@SettingsChip
                                }
                                if (FloatLyric.setEnabled(ctx, true)) floatLyricEnabled = true
                            },
                        )
                        SettingsChip(
                            text = "关",
                            selected = !floatLyricEnabled,
                            onClick = {
                                FloatLyric.setEnabled(floatLyricContext, false)
                                floatLyricEnabled = false
                            },
                        )
                    }
                    // 2.95：歌词组——有词时也能打开搜歌词面板（重搜/清除锁定）
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "歌词",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SettingsChip(
                            text = "搜歌词",
                            selected = false,
                            onClick = {
                                settingsSheet = false
                                lyricSearchSheet = true
                            },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        // 2.95：搜歌词面板（无歌词空态 + 设置面板“歌词”组都进这里）
        if (lyricSearchSheet) {
            LyricSearchSheet(
                songKey = lyricSongKey,
                title = state.lyricSearchTitle,
                artist = state.lyricSearchArtist,
                platform = state.musicPlatform,
                musicId = state.musicId,
                durationSec = (state.durationMs / 1000L).toInt(),
                onClose = { lyricSearchSheet = false },
                onReloadLyric = onReloadLyric,
            )
        }

        state.castSheet?.let { sheet ->
            CastSheet(sheet) { state.castSheet = null }
        }
    }
}

@Composable
private fun MusicTopBar(
    sourceName: String,
    qualityLabel: String,
    onBack: () -> Unit,
    onQualityClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopBarActionBox(R.drawable.ic_arrow_left, stringResource(R.string.common_back), onClick = onBack)
        Spacer(Modifier.weight(1f))
        // 音质胶囊：点开切档（音乐模式才有）
        // 3.07 第二阶段 C-edit：隐藏后不渲染（设置页可恢复）。
        if (qualityLabel.isNotEmpty() && !ControlThemeStore.isElementHidden("music_quality")) {
            Box(
                modifier = Modifier
                    .height(40.dp)
                    // 3.07 百变控件：音质胶囊接控件主题（kind=CHIP）；combinedClickable 放
                    // 最外层（点按区含表面内边距），长按调编辑。
                    // 3.13：总开关开时玻璃面不画，只留控制层；关时原样保留。
                    .combinedClickable(
                        onClick = onQualityClick,
                        onLongClick = { ControlEditState.requestEdit("music_quality") },
                    )
                    .then(controlSurfaceModifier("music_quality", ControlElementKind.CHIP))
                    .clip(CapsuleShape)
                    .then(
                        if (ControlThemeStore.shouldDrawOriginalStyle()) {
                            Modifier.glassTopBarSurface(CapsuleShape, MaterialTheme.colorScheme.tertiaryContainer)
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = qualityLabel,
                    style = MaterialTheme.typography.labelLarge,
                    // 3.11 字体颜色：总开关开时字走胶囊组字体颜色（默认白字），
                    // 关时走原字色 onTertiaryContainer。
                    color = if (ControlThemeStore.isControlThemeEnabled()) {
                        controlContentColor("music_quality", ControlElementKind.CHIP)
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    },
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        if (sourceName.isNotEmpty()) {
            MusicSourcePill(sourceName)
        }
    }
}

@Composable
private fun MusicSourcePill(sourceName: String) {
    Box(
        modifier = Modifier
            .height(40.dp)
            .glassTopBarSurface(CapsuleShape, MaterialTheme.colorScheme.surfaceBright)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = sourceName,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 170.dp),
        )
    }
}

@Composable
private fun MusicBottomActions(
    playMode: MusicPlayMode,
    collected: Boolean,
    onCyclePlayMode: () -> Unit,
    onToggleCollect: () -> Unit,
    onCast: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val modeIcon = when (playMode) {
        MusicPlayMode.SINGLE -> R.drawable.ic_repeat_one
        MusicPlayMode.LIST -> R.drawable.ic_repeat
        MusicPlayMode.ORDER -> R.drawable.ic_order_play
    }
    // 3.07 第二阶段 C-edit：底部操作重排 + 隐藏——五段先按
    // 设置页存的顺序排，再去掉已隐藏的（分段形状按渲染后的位置取，
    // 不跟原下标）；全藏了整行不渲染；3.17：三档位置已删，恒居中。
    val bottomOrder = ControlThemeStore.getMusicBottomOrder()
    data class BottomSegment(
        val id: String,
        val iconRes: Int,
        val label: String,
        val active: Boolean,
        val activeColor: Color,
        val onActiveColor: Color,
        val onClick: () -> Unit,
    )
    val allSegments = listOf(
        BottomSegment(
            id = "music_bottom_playmode",
            iconRes = modeIcon,
            label = stringResource(playMode.labelRes),
            active = playMode != MusicPlayMode.ORDER,
            activeColor = MaterialTheme.colorScheme.primary,
            onActiveColor = MaterialTheme.colorScheme.onPrimary,
            onClick = onCyclePlayMode,
        ),
        BottomSegment(
            id = "music_bottom_collect",
            iconRes = if (collected) R.drawable.ic_tab_collect_filled else R.drawable.ic_tab_collect,
            label = stringResource(if (collected) R.string.detail_uncollect else R.string.common_collect),
            active = collected,
            activeColor = MaterialTheme.colorScheme.tertiary,
            onActiveColor = MaterialTheme.colorScheme.onTertiary,
            onClick = onToggleCollect,
        ),
        BottomSegment(
            id = "music_bottom_cast",
            iconRes = R.drawable.ic_detail_cast,
            label = stringResource(R.string.common_cast),
            active = false,
            activeColor = MaterialTheme.colorScheme.primary,
            onActiveColor = MaterialTheme.colorScheme.onPrimary,
            onClick = onCast,
        ),
        BottomSegment(
            id = "music_bottom_queue",
            iconRes = R.drawable.ic_music_queue,
            label = stringResource(R.string.music_episodes),
            active = false,
            activeColor = MaterialTheme.colorScheme.primary,
            onActiveColor = MaterialTheme.colorScheme.onPrimary,
            onClick = onOpenQueue,
        ),
        BottomSegment(
            id = "music_bottom_settings",
            iconRes = R.drawable.player_ic_gear,
            label = "设置",
            active = false,
            activeColor = MaterialTheme.colorScheme.primary,
            onActiveColor = MaterialTheme.colorScheme.onPrimary,
            onClick = onOpenSettings,
        ),
    )
    val byId = allSegments.associateBy { it.id }
    val visibleSegments = bottomOrder
        .mapNotNull { byId[it] }
        .filter { !ControlThemeStore.isElementHidden(it.id) }
    if (visibleSegments.isEmpty()) return
    val segmentCount = visibleSegments.size
    Box(
        modifier = Modifier.fillMaxWidth(),
        // 3.17：三档位置已删，音乐底栏恒居中。
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                // 2.82：多一个设置钮，胶囊加宽一段
                .width(PlaybackControlsWidth + 62.dp)
                .height(66.dp)
                .clip(CapsuleShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // for 循环（不用 forEachIndexed）：weight 是 RowScope 扩展，
                // 必须留在 Row 的接收者作用域里，forEach 闭包会丢掉它。
                for ((index, segment) in visibleSegments.withIndex()) {
                    BottomActionItem(
                        iconRes = segment.iconRes,
                        label = segment.label,
                        active = segment.active,
                        activeColor = segment.activeColor,
                        onActiveColor = segment.onActiveColor,
                        shape = segmentShape(index = index, count = segmentCount),
                        onClick = segment.onClick,
                        // 3.07 百变控件：播放模式段 / 收藏段 / 投屏段 / 队列段 / 设置段。
                        controlId = segment.id,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomActionItem(
    @DrawableRes iconRes: Int,
    label: String,
    active: Boolean,
    activeColor: Color,
    onActiveColor: Color,
    shape: Shape,
    onClick: () -> Unit,
    // 3.07 百变控件：稳定元素 id（调用处写死，如 "music_bottom_cast"）。
    controlId: String,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow),
        label = "bottomActionScale",
    )
    val background by animateColorAsState(
        targetValue = if (active) activeColor else MaterialTheme.colorScheme.surfaceBright,
        animationSpec = tween(durationMillis = 250),
        label = "bottomActionColor",
    )
    Box(
        modifier = modifier
            .fillMaxHeight()
            .scale(scale)
            .clip(shape)
            // 3.18：总开关开时宿主原底不画——controlButtonModifier 已画统一底
            // （水滴 / 轻透按风格开关），surfaceBright / activeColor 再铺一层
            // 就是两层底；关时走原样。激活信号改走控制层 selected（提亮底 +
            // 选中描边），不丢。
            .then(if (ControlThemeStore.shouldDrawOriginalStyle()) Modifier.background(background) else Modifier)
            // 3.07 百变控件：combinedClickable 同时处理点按与长按（长按调编辑，不吞点按）；
            // 原有按压缩放（interactionSource）与水波纹保留。
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = onClick,
                onLongClick = { ControlEditState.requestEdit(controlId) },
            )
            // 3.07：只套轻量外观（形状裁剪 + slate 淡底 + 强调色描边），放点按内侧，
            // 不缩点按区；分段形状（segmentShape）仍由外层 clip 保留。
            // 3.19：底栏钮描边恢复——颜色走胶囊描边颜色卡（组 borderColorArgb，0 = 跟随主题
            // 强调色），仍在 controlButtonModifier 同一链里与底同一 bounds、同一形状画，不另起一层。
            .then(controlButtonModifier(controlId, selected = active)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = if (active) onActiveColor else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

private val SegmentCornerLarge = CornerSize(percent = 50)

private val SegmentCornerSmall = CornerSize(8.dp)

private fun segmentShape(index: Int, count: Int): Shape = when (index) {
    0 -> RoundedCornerShape(
        topStart = SegmentCornerLarge,
        topEnd = SegmentCornerSmall,
        bottomEnd = SegmentCornerSmall,
        bottomStart = SegmentCornerLarge,
    )

    count - 1 -> RoundedCornerShape(
        topStart = SegmentCornerSmall,
        topEnd = SegmentCornerLarge,
        bottomEnd = SegmentCornerLarge,
        bottomStart = SegmentCornerSmall,
    )

    else -> RoundedCornerShape(SegmentCornerSmall)
}

@Composable
private fun MusicCover(artwork: String, side: Dp, playing: Boolean, onSeed: (Int?) -> Unit, onSwatches: (List<Int>) -> Unit) {
    val scope = rememberCoroutineScope()
    val hasArtwork = artwork.isNotEmpty()
    var rotation by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing, hasArtwork) {
        if (!playing || !hasArtwork) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dtMs = (now - last) / 1_000_000f
            last = now
            rotation = (rotation + dtMs * 360f / 20000f) % 360f
        }
    }
    Box(
        modifier = Modifier
            .size(side)
            .clip(if (hasArtwork) CircleShape else RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (hasArtwork) {
            AsyncImage(
                model = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationZ = rotation },
                onSuccess = { result ->
                    val image = result.result.image
                    scope.launch {
                        // 同一份图上既算单主色（旧链路），也算多色（内置主题）。
                        val (argb, swatches) = withContext(Dispatchers.Default) {
                            ImagePalette.seedOf(image) to ImagePalette.swatchesOf(image, 5)
                        }
                        onSeed(argb)
                        onSwatches(swatches)
                    }
                },
                onError = {
                    onSeed(null)
                    onSwatches(emptyList())
                },
            )
        } else {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(72.dp),
            )
        }
    }
}

@Composable
private fun MusicInfo(title: String, subtitle: String, themeName: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp)
            .padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // 2.90：当前主题名（内置如“绯色子夜 Built-in”，AI 按 AiTheme.name 原值）。
        if (themeName.isNotEmpty()) {
            Text(
                text = themeName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// 2.99 全屏 3D 舞台（镜台）：三款舞台共用的全屏 Dialog。
// 画布：黑底星空（确定性种子，亮度不一、少量闪烁、轻微漂移）+ 射灯光锥（沿用模板 spotlightColors/sweepAmp/sweepPeriodSec/spotlightAlpha：
// 演唱会三色快扫 4s / 剧院暖金慢扫 9s / 星空舞台冷白中速 6s）+ 飘的立体点块（点阵线框立方体，确定性种子，缓慢漂移/旋转）。
// 歌词：3D 透视——当前句大（40–56sp 按句长）、粗体、居中、轻微倾斜；上下句按距离缩小变淡、纵深位移；行切换淡入+滑动/缩放
// （AnimatedContent，复用 lyricIndex 与平滑时钟，校准偏移照常用——lyricPositionMs 已含校准）。
// 3.05：六款舞台各不相同——前景/光束/点块/歌词排版全分开，不再共用同一套（见 FullscreenStageDialog 内分发与 StageLyricLine）：
// 演唱会 LED 巨幕当歌词底 + 三道彩色扫射 + 亮白/亮青大字；剧院单束暖金追光 + 红幕框住 + 暖金大字平视；
// 星空舞台 3D 透视大字 + 冷白细光束 + 点块只留这里；沙画亮暖沙底 + 160 颗金沙 + 手划亮痕 + 金沙斜体字；
// 游子信纸衬字 + 墨色字 + 台灯暖光晕；武侠竹林两侧框字 + 一道加粗剑气寒光斜扫 + 冷白带青字。
// 当前句颜色按款给定（StageLyricLine 内 currentColor），歌词同步、切行、底部进度一律不动；暂停时 timeSec 停住、景也停住。
// 3.06：六款按用户看 3.05 截图的意见重做——沙画真的画出完整的画（三幅轮廓画循环：爱心/飞鸟/花朵，50 秒一幅：画 22 秒、
// 停留 20 秒、化 8 秒，手跟着画笔尖走）；演唱会巨幕放大到 0.08–0.78 高、歌词贴在屏内、彩带不再贴左边缘；星空点块收拢到四角/边缘、
// 星轨与银河做淡一半；剧院整体提亮（底色/幕布/追光）；游子信纸缩到 0.78×0.44、月亮 ×1.5、台灯 ×1.3；武侠剑气收细收暗（半宽 8dp）、
// 竹子提亮成看得清的青绿。歌词同步、切行、底部进度、退出舞台一律不动。
// 星空舞台星光点缀：星数比另两款多（decor 7 点、alpha 0.35 的气质落到星空密度上）。
private data class StageStar(
    val x: Float,
    val y: Float,
    val radiusDp: Float,
    val baseAlpha: Float,
    val twinkleSpeed: Float,
    val twinklePhase: Float,
    val driftSpeed: Float,
    val driftPhase: Float,
)

@Composable
private fun FullscreenStageDialog(
    template: LyricVisualTemplate,
    lines: List<LyricLine>,
    lyricPositionMs: Long,
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    songKey: String,
    onSeek: (Long) -> Unit,
    onExit: () -> Unit,
    onStageChange: (String) -> Unit,
) {
    // 平滑时钟（复用 visualizer 的做法）：400ms tick 之间用帧时钟自己推，暂停停住，切歌/Seek 重锚
    var anchorPos by remember { mutableStateOf(lyricPositionMs) }
    var anchorNanos by remember { mutableStateOf(0L) }
    var displayPos by remember { mutableStateOf(lyricPositionMs) }
    LaunchedEffect(lyricPositionMs) {
        anchorPos = lyricPositionMs
        anchorNanos = System.nanoTime()
        displayPos = lyricPositionMs
    }
    LaunchedEffect(playing) {
        anchorNanos = 0L
        anchorPos = lyricPositionMs
        displayPos = lyricPositionMs
        if (!playing) return@LaunchedEffect
        while (true) {
            withFrameNanos { nanos ->
                if (anchorNanos == 0L) {
                    anchorNanos = nanos
                    anchorPos = lyricPositionMs
                }
                displayPos = anchorPos + (nanos - anchorNanos) / 1_000_000
            }
        }
    }
    val current = remember(lines, displayPos) { lyricIndex(lines, displayPos) }
    val timeSec = displayPos / 1000f

    // 3.01：舞台底色按舞台全分开——用户定「只有全部不一样，才会知道不同」，细微差别没人分辨。
    // 演唱会暗红紫底、剧院暖金棕底、星空舞台留黑底；星星也按舞台分（剧院无星、演唱会 60 颗、星空舞台 150 颗），
    // 不再三款全是黑星空只换字色。
    // 3.03：新增三款——沙画暖沙底、游子归家深暖棕底、武侠冷青底；星星：沙画 0、游子 0、武侠 40（江湖夜空，比星空舞台少）。
    val stageBackground = when (template.id) {
        4 -> Color(0xFF1E0A1E) // 演唱会：暗红紫
        5 -> Color(0xFF3A2A12) // 剧院：暖金棕（3.06：提亮一档，不再死黑）
        6 -> Color.Black // 星空舞台：黑
        15 -> Color(0xFF6B4F2A) // 沙画：亮暖沙（3.05：原来 #2A1F0D 太暗、手机上看着就是黑，改亮）
        16 -> Color(0xFF1E1206) // 游子归家：深暖棕
        17 -> Color(0xFF0A1A20) // 武侠：冷青
        else -> Color.Black
    }
    // 星空：确定性种子（同首歌每次打开布局一致）；星空舞台星光密（150 颗）、演唱会 60 颗、武侠 40 颗、剧院/沙画/游子无星
    val starCount = when (template.id) {
        5 -> 0
        15 -> 0
        16 -> 0
        4 -> 60
        17 -> 40
        6 -> 36 // 视频款留白多，星点只作细小空气颗粒，不抢歌词
        else -> 150
    }
    val stars = remember(songKey, starCount) {
        val rnd = Random(songKey.hashCode().toLong())
        List(starCount) {
            StageStar(
                x = rnd.nextFloat(),
                y = rnd.nextFloat(),
                radiusDp = 0.8f + rnd.nextFloat() * 1.8f,
                baseAlpha = 0.35f + rnd.nextFloat() * 0.65f,
                twinkleSpeed = 0.6f + rnd.nextFloat() * 1.8f,
                twinklePhase = rnd.nextFloat() * 6.28f,
                driftSpeed = 0.05f + rnd.nextFloat() * 0.15f,
                driftPhase = rnd.nextFloat() * 6.28f,
            )
        }
    }
    Dialog(
        onDismissRequest = onExit,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(stageBackground)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // 3.48 星空舞台暖光晕：视频里的大团暖橙光（左上、柔和、慢漂），画在星空之前
                if (template.id == 6) {
                    val blobCx = size.width * (0.32f + sin(timeSec * 0.10f) * 0.03f)
                    val blobCy = size.height * (0.30f + cos(timeSec * 0.08f) * 0.02f)
                    val blobR = minOf(size.width, size.height) * 0.62f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFFFB74D).copy(alpha = 0.39f),
                                Color(0xFFFF8A3D).copy(alpha = 0.17f),
                                Color.Transparent,
                            ),
                            center = Offset(blobCx, blobCy),
                            radius = blobR,
                        ),
                        radius = blobR,
                        center = Offset(blobCx, blobCy),
                    )
                    // 第二团弱暖光压在右下，像参考视频的摄影棚柔光；不画实体星体。
                    val blob2Cx = size.width * (0.82f + sin(timeSec * 0.07f) * 0.025f)
                    val blob2Cy = size.height * 0.76f
                    val blob2R = minOf(size.width, size.height) * 0.48f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFFFFC47A).copy(alpha = 0.13f), Color.Transparent),
                            center = Offset(blob2Cx, blob2Cy), radius = blob2R,
                        ),
                        radius = blob2R,
                        center = Offset(blob2Cx, blob2Cy),
                    )
                }
                // 近景光粒：金白双层，缓慢上浮并左右漂移；只在星空舞台绘制。
                if (template.id == 6) {
                    val rnd = Random(songKey.hashCode().toLong() xor 0x45AB12L)
                    repeat(42) { i ->
                        val x0 = rnd.nextFloat()
                        val y0 = rnd.nextFloat()
                        val speed = 0.008f + rnd.nextFloat() * 0.018f
                        val y = ((y0 - timeSec * speed) % 1f + 1f) % 1f
                        val x = x0 + sin(timeSec * (0.14f + i % 4 * 0.035f) + i) * 0.018f
                        val fade = (sin(y * 3.14159f) * 0.58f).coerceIn(0f, 0.58f)
                        val center = Offset(size.width * x, size.height * y)
                        val radius = (if (i % 5 == 0) 3.2f else 1.6f).dp.toPx()
                        if (i % 5 == 0) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(Color(0xFFFFC16E).copy(alpha = fade * 0.48f), Color.Transparent),
                                    center = center, radius = radius * 5f,
                                ),
                                radius = radius * 5f, center = center,
                            )
                        }
                        drawCircle(
                            color = (if (i % 3 == 0) Color.White else Color(0xFFFFCA81)).copy(alpha = fade),
                            radius = radius, center = center,
                        )
                    }
                }
                // 星空
                stars.forEach { star ->
                    val twinkle = 0.65f + 0.35f * sin(timeSec * star.twinkleSpeed + star.twinklePhase)
                    val dx = sin(timeSec * star.driftSpeed + star.driftPhase) * 4.dp.toPx()
                    val dy = cos(timeSec * star.driftSpeed * 0.8f + star.driftPhase) * 3.dp.toPx()
                    drawCircle(
                        color = Color.White.copy(alpha = (star.baseAlpha * twinkle).coerceIn(0f, 1f)),
                        radius = star.radiusDp.dp.toPx(),
                        center = Offset(size.width * star.x + dx, size.height * star.y + dy),
                    )
                }
                // 舞台布景（3.01）：按舞台画景，星空之后、光束之前；暂停时 timeSec 停住、景也停住
                // 3.03：新增三款——沙画 / 游子归家 / 武侠
                when (template.id) {
                    4 -> drawConcertScenery(size.width, size.height, timeSec)
                    5 -> drawTheaterScenery(size.width, size.height, timeSec)
                    6 -> drawStarScenery(size.width, size.height, timeSec, template, songKey, current)
                    15 -> drawSandPaintingScenery(size.width, size.height, timeSec)
                    16 -> drawWandererScenery(size.width, size.height, timeSec)
                    17 -> drawWuxiaScenery(size.width, size.height, timeSec)
                }
                // 射灯光锥（3.05：不再六款通用，按款分流——演唱会保留模板扫射光束；
                // 3.50：星空舞台撤掉光束（视频里没有光锥，只有暖光晕，3.48 已画）；
                // 剧院改一道暖金追光（单束慢摆）；沙画/游子/武侠不要通用光束，各靠自己的氛围光/台灯/剑气）
                when (template.id) {
                    4 -> if (template.spotlightColors.isNotEmpty()) {
                        drawStageBeams(template, size.width, size.height, timeSec)
                    }
                    5 -> drawSingleSpotlight(size.width, size.height, timeSec)
                }
            }
            // 歌词（居中；3.05：每款排版/衬底不同——演唱会贴在布景 LED 巨幕上、剧院落在舞台上、星空漂浮 3D、
            // 沙画写在沙上、游子写在信纸上、武侠刻在竹林框住的石碑上；衬底只给游子加信纸，其余靠布景）
            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                // 游子归家：信纸衬在字下（浅色信纸矩形、微斜 2°、圆角；左侧一道暖黄台灯光晕照在信纸上）
                // 3.06：信纸 0.88×0.52 太大太空，缩到 0.78×0.44
                if (template.id == 16) {
                    Box(
                        modifier = Modifier.fillMaxWidth(0.78f).fillMaxHeight(0.44f)
                            .graphicsLayer { rotationZ = 2f }
                            .background(Color(0xFFF5EAD0), RoundedCornerShape(12.dp)),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize()
                                .background(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(
                                            Color(0xFFFFE9B8).copy(alpha = 0.45f),
                                            Color.Transparent,
                                        ),
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                ),
                        )
                    }
                }
                AnimatedContent(
                    targetState = current,
                    transitionSpec = {
                        // 3.48：星空舞台散词只淡入淡出（整块滑动会把散开的词一起拖走），其余五款照旧淡入+滑动+缩放
                        if (template.id == 6) {
                            fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(250))
                        } else {
                            (fadeIn(animationSpec = tween(350)) + slideInVertically(animationSpec = tween(350)) { it / 4 } + scaleIn(animationSpec = tween(350), initialScale = 0.92f)) togetherWith
                                (fadeOut(animationSpec = tween(250)) + slideOutVertically(animationSpec = tween(250)) { -it / 4 } + scaleOut(animationSpec = tween(250), targetScale = 0.92f))
                        }
                    },
                    label = "stage-lyrics",
                ) { idx ->
                    if (template.id == 6) {
                        // 3.48 星空舞台散词动力学排版（视频同款）：只排当前句，词散开、各跳各的
                        StageScatterLyrics(
                            lines = lines,
                            currentIndex = idx,
                            songKey = songKey,
                            timeSec = timeSec,
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            // 3.06：演唱会 5 行歌词要全落在 LED 巨幕内，行距 20dp→12dp；其余五款不动
                            verticalArrangement = Arrangement.spacedBy(if (template.id == 4) 12.dp else 20.dp),
                        ) {
                            for (offset in -2..2) {
                                val line = lines.getOrNull(idx + offset) ?: continue
                                StageLyricLine(
                                    line = line,
                                    template = template,
                                    isCurrent = offset == 0,
                                    dist = abs(offset),
                                    isAbove = offset < 0,
                                )
                            }
                        }
                    }
                }
            }
            // 3.48 星空舞台左侧竖排 THEME（视频同款，左缘竖字）
            if (template.id == 6) {
                Column(
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    "[THEME]".forEach { ch ->
                        Text(
                            text = ch.toString(),
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 11.sp,
                        )
                    }
                }
            }
            // 退出舞台（右上）
            // 3.07 百变控件：退出舞台钮接控件主题，长按调编辑。
            // 3.07 第二阶段 C-edit：隐藏后不渲染（设置页可恢复；舞台内仍可按返回键退出）。
            if (!ControlThemeStore.isElementHidden("music_stage_exit")) {
                ControlButton(
                    buttonId = "music_stage_exit",
                    onClick = onExit,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 16.dp),
                    onLongPress = { ControlEditState.requestEdit("music_stage_exit") },
                ) {
                    Text(text = "退出舞台", style = MaterialTheme.typography.bodyLarge, color = Color.White)
                }
            }
            // 3.47 舞台内切舞台：右上角列出全部六款舞台，点哪个就换成哪个（不退出舞台、不动 prevTemplateId，退出仍回进舞台前的模板）。
            if (template.id != 6) Column(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 68.dp, end = 16.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StageTemplateIds.forEachIndexed { i, id ->
                    val selected = template.kvId == id
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(14.dp),
                            )
                            .clickable { if (!selected) onStageChange(id) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = StageTemplateNames[i],
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                        )
                    }
                }
            }
            // 底部细进度（复用波形进度条）
            MusicWaveProgress(
                positionMs = positionMs,
                durationMs = durationMs,
                waveSeed = songKey.hashCode(),
                playing = playing,
                onSeek = onSeek,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
    }
}

// 3.48 星空舞台散词动力学排版（视频同款）：当前句的词散在全屏，各自大小/位置/微转都不同；
// 词一个一个弹进来（spring 回弹、每词晚 70ms），进来后一直跟着节拍跳（正弦上下跳 + 轻微缩放 + 慢速横漂 + 微转晃动）。
// 无真节拍检测：节拍周期固定 0.55 秒，词与词之间相位错开，读起来像跟着节拍跳；暂停时 timeSec 停住、跳也停住。
// 3.49 防堆叠：词不再纯随机撒点，改网格分格——每词一个格子（打乱顺序）、格内居中 + ±6% 抖动，字号夹在格内（宽/高各留 30%），
// 保证相邻词不重叠；颜色亮白暖金轮换（用户 2026-11-18 定），挨近的词颜色不同也分得开。
// 只改星空舞台（id 6），其余五款仍走 StageLyricLine 的 5 行排版。
private data class StageScatterSlot(
    val xFrac: Float,
    val yFrac: Float,
    val sizeScale: Float,
    val rotDeg: Float,
    val phase: Float,
)

private fun estimateEmWidth(token: String): Float {
    var em = 0f
    for (ch in token) {
        em += when {
            ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF' ||
                ch in '\u3040'..'\u30FF' || ch in '\uAC00'..'\uD7A3' -> 1.0f
            ch in '\u3000'..'\u303F' || ch in '\uFF00'..'\uFFEF' -> 1.0f
            ch.isWhitespace() -> 0.5f
            else -> 0.55f
        }
    }
    return em.coerceAtLeast(0.5f)
}

private fun stageLyricTokens(line: LyricLine): List<String> {
    // 视频款不是把中文逐字撒满屏，而是“二字 / 三字短词组”错落排版。
    // 即使 LRC words 已逐字切开，也从完整行重新组词；英文仍按单词。
    val runs = ArrayList<String>()
    val buf = StringBuilder()
    fun flush() {
        if (buf.isNotEmpty()) { runs.add(buf.toString()); buf.clear() }
    }
    for (ch in line.text.trim()) {
        val cjk = ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF'
        val punct = ch in '\u3000'..'\u303F' || ch in '\uFF00'..'\uFFEF'
        when {
            ch.isWhitespace() -> flush()
            punct -> { flush(); if (runs.isNotEmpty()) runs[runs.lastIndex] = runs.last() + ch }
            cjk -> {
                if (buf.isNotEmpty() && buf.last() !in '\u4E00'..'\u9FFF' && buf.last() !in '\u3400'..'\u4DBF') flush()
                buf.append(ch)
            }
            else -> {
                if (buf.isNotEmpty() && (buf.last() in '\u4E00'..'\u9FFF' || buf.last() in '\u3400'..'\u4DBF')) flush()
                buf.append(ch)
            }
        }
    }
    flush()
    val out = ArrayList<String>()
    for (run in runs) {
        val isCjk = run.all { it in '\u4E00'..'\u9FFF' || it in '\u3400'..'\u4DBF' }
        if (!isCjk) { out.add(run); continue }
        if (run.length <= 2) { out.add(run); continue }
        if (run.length == 3) {
            out.add(run.substring(0, 1))
            out.add(run.substring(1))
            continue
        }
        val target = if (run.length <= 10) 2 else 3
        val count = (run.length + target - 1) / target
        val short = run.length / count
        val extra = run.length % count
        var at = 0
        for (i in 0 until count) {
            val len = short + if (i < extra) 1 else 0
            out.add(run.substring(at, at + len)); at += len
        }
    }
    return out
}

@Composable
private fun StageScatterLyrics(
    lines: List<LyricLine>,
    currentIndex: Int,
    songKey: String,
    timeSec: Float,
) {
    val line = lines.getOrNull(currentIndex) ?: return
    val tokens = remember(line) { stageLyricTokens(line) }
    if (tokens.isEmpty()) return

    // 全屏锚点不是行列网格：每句按歌词时间重新抽取并打散，形状始终不完全对称。
    val anchors = listOf(
        0.16f to 0.18f, 0.66f to 0.14f, 0.40f to 0.29f, 0.75f to 0.36f,
        0.18f to 0.46f, 0.55f to 0.51f, 0.29f to 0.64f, 0.72f to 0.69f,
        0.13f to 0.79f, 0.47f to 0.82f, 0.83f to 0.55f, 0.48f to 0.41f,
    )
    fun slotsFor(target: LyricLine, count: Int, ghost: Boolean): List<StageScatterSlot> {
        val rnd = Random(songKey.hashCode().toLong() * 31L + target.timeMs + if (ghost) 991L else 0L)
        val shuffled = anchors.shuffled(rnd)
        val bigIdx = if (count > 2) rnd.nextInt(count) else -1
        return List(count) { i ->
            val anchor = shuffled[i % shuffled.size]
            var scale = 0.78f + rnd.nextFloat() * 0.70f
            if (i == bigIdx) scale *= 1.35f
            StageScatterSlot(
                xFrac = (anchor.first + (rnd.nextFloat() - 0.5f) * 0.09f).coerceIn(0.06f, 0.90f),
                yFrac = (anchor.second + (rnd.nextFloat() - 0.5f) * 0.075f).coerceIn(0.10f, 0.84f),
                sizeScale = scale,
                rotDeg = -9f + rnd.nextFloat() * 18f,
                phase = rnd.nextFloat() * 6.283f,
            )
        }
    }
    val slots = remember(line, songKey) { slotsFor(line, tokens.size, false) }
    val enters = remember(line) { tokens.map { Animatable(0f) } }
    LaunchedEffect(line) {
        enters.forEachIndexed { i, anim ->
            launch {
                delay(i * 65L)
                anim.animateTo(1f, spring(dampingRatio = 0.43f, stiffness = 220f))
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val w = maxWidth.value
        val h = maxHeight.value

        // 短句只有 1–2 个视觉词时，用前后句的淡色残影补足空间，而不是硬拉一个巨大词。
        if (tokens.size <= 2) {
            listOfNotNull(
                lines.getOrNull(currentIndex - 1)?.let { Triple(it, -1, 0.12f) },
                lines.getOrNull(currentIndex + 1)?.let { Triple(it, 1, 0.15f) },
            ).forEach { (ghostLine, direction, alpha) ->
                val ghostTokens = remember(ghostLine) { stageLyricTokens(ghostLine).take(5) }
                val ghostSlots = remember(ghostLine, songKey) { slotsFor(ghostLine, ghostTokens.size, true) }
                ghostTokens.forEachIndexed { i, tok ->
                    val slot = ghostSlots[i]
                    val gx = if (direction < 0) slot.xFrac * 0.72f else 0.28f + slot.xFrac * 0.66f
                    val gy = if (direction < 0) 0.07f + slot.yFrac * 0.22f else 0.72f + slot.yFrac * 0.17f
                    val drift = sin(timeSec * 0.22f + slot.phase) * 10f
                    Text(
                        text = tok,
                        fontSize = (22f + slot.sizeScale * 7f).sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = alpha),
                        modifier = Modifier
                            .offset(x = (gx * w + drift).dp, y = (gy * h).dp)
                            .graphicsLayer { rotationZ = slot.rotDeg * 0.5f },
                    )
                }
            }
        }

        tokens.forEachIndexed { i, tok ->
            val slot = slots[i]
            val enter = enters[i].value
            val baseSize = when {
                line.text.length <= 6 -> 58f
                line.text.length <= 10 -> 50f
                line.text.length <= 14 -> 44f
                else -> 38f
            }
            val desired = baseSize * slot.sizeScale
            val maxByWidth = (w * 0.72f) / estimateEmWidth(tok)
            val fontSize = minOf(desired, maxByWidth).coerceIn(24f, 76f)
            val beat = max(0f, sin(timeSec * 2f * PI.toFloat() / 0.62f + slot.phase))
            val driftX = sin(timeSec * 0.42f + slot.phase) * w * 0.035f +
                cos(timeSec * 0.17f + i) * 12f
            val driftY = cos(timeSec * 0.36f + slot.phase) * h * 0.024f - beat * 12f
            val enterX = (if (slot.xFrac < 0.5f) -90f else 90f) * (1f - enter)
            val enterY = (55f + i * 8f) * (1f - enter)
            val estimatedTextW = fontSize * estimateEmWidth(tok)
            val x = (slot.xFrac * w - estimatedTextW * 0.42f + driftX + enterX)
                .coerceIn(-w * 0.03f, w * 0.88f)
            val y = slot.yFrac * h + driftY + enterY
            val color = if (i % 3 == 1) Color(0xFFFFD98A) else Color.White
            Text(
                text = tok,
                style = TextStyle(
                    fontSize = fontSize.sp,
                    fontWeight = if (i % 3 == 0) FontWeight.ExtraBold else FontWeight.Bold,
                    color = color,
                    shadow = Shadow(color = color.copy(alpha = 0.65f), blurRadius = 28f),
                ),
                modifier = Modifier
                    .offset(x = x.dp, y = y.dp)
                    .graphicsLayer {
                        rotationZ = slot.rotDeg + sin(timeSec * 0.55f + slot.phase) * 3.5f
                        val sc = (0.30f + enter * 0.70f) * (1f + beat * 0.075f)
                        scaleX = sc
                        scaleY = sc
                        alpha = enter
                    },
            )
        }
    }
}

@Composable
private fun StageLyricLine(
    line: LyricLine,
    template: LyricVisualTemplate,
    isCurrent: Boolean,
    dist: Int,
    isAbove: Boolean,
) {
    // 3.05：六款歌词排版各不同——字号：剧院/演唱会大字（×1.08/×1.05），其余照旧；
    // 透视倾斜只留给星空舞台（漂浮 3D），其余五款一律平视、不倾斜；沙画斜体、像手写在沙上
    val sizeScale = when (template.id) {
        5 -> 1.08f // 剧院：大字
        4 -> 1.05f // 演唱会：大字
        else -> 1f
    }
    // 字号：当前句按句长 40–56sp，上下句按距离缩小
    val baseSizeSp = when {
        line.text.length <= 6 -> 56f
        line.text.length <= 10 -> 48f
        line.text.length <= 14 -> 44f
        else -> 40f
    } * sizeScale
    val sizeSp = when (dist) {
        0 -> baseSizeSp
        1 -> baseSizeSp * 0.55f
        else -> baseSizeSp * 0.4f
    }
    val alpha = when (dist) {
        0 -> 1f
        1 -> 0.5f
        else -> 0.28f
    }
    // 透视：只有星空舞台保留 3D 倾斜（上行顶边后仰、下行底边后仰）；其余五款平视
    val rotationX = if (template.id == 6) {
        when {
            isCurrent -> 0f
            isAbove -> 16f * dist
            else -> -16f * dist
        }
    } else 0f
    val rotationZ = if (isCurrent && template.id == 6) -2f else 0f
    // 当前句字色（3.05 各款给定）：演唱会亮白/亮青轮换、剧院暖金、星空冷白、沙画金沙、游子墨色、武侠冷白带青
    val currentColor = when (template.id) {
        5 -> Color(0xFFFFD98A)
        6 -> Color(0xFFE8F4FF)
        15 -> Color(0xFFE8C878).copy(alpha = 0.9f)
        16 -> Color(0xFF3A2A1A)
        17 -> Color(0xFFD0E8E0)
        else -> template.glowColors.firstOrNull() ?: Color.White
    }
    // 邻行字色：游子写在浅色信纸上，邻行也用墨色（白字在信纸上看不见）；其余白字淡化
    val neighborBase = if (template.id == 16) Color(0xFF3A2A1A) else Color.White
    val fontStyle = if (template.id == 15) FontStyle.Italic else FontStyle.Normal
    if (isCurrent) {
        // 演唱会：当前句按词轮换亮白/亮青（LED 巨幕上的大字）；其余各款单色当前句
        if (template.id == 4) {
            val annotated = buildAnnotatedString {
                val words = line.words
                val concertColors = listOf(Color.White, Color(0xFF7DF9FF))
                if (words.isEmpty()) {
                    pushStyle(SpanStyle(color = Color.White))
                    append(line.text)
                    pop()
                } else {
                    words.forEachIndexed { i, word ->
                        pushStyle(SpanStyle(color = concertColors[i % 2]))
                        append(word.text)
                        pop()
                    }
                }
            }
            Text(
                text = annotated,
                fontSize = sizeSp.sp,
                fontWeight = FontWeight.Bold,
                fontStyle = fontStyle,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer {
                    this.rotationX = rotationX
                    this.rotationZ = rotationZ
                },
            )
        } else {
            Text(
                text = line.text,
                fontSize = sizeSp.sp,
                fontWeight = FontWeight.Bold,
                fontStyle = fontStyle,
                color = currentColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer {
                    this.rotationX = rotationX
                    this.rotationZ = rotationZ
                },
            )
        }
    } else {
        Text(
            text = line.text,
            fontSize = sizeSp.sp,
            fontWeight = FontWeight.Normal,
            fontStyle = fontStyle,
            color = neighborBase.copy(alpha = alpha),
            textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer {
                this.rotationX = rotationX
                this.alpha = alpha
            },
        )
    }
}

// 3.05 剧院单束追光：一道暖金追光从顶部打下来、慢摆（周期 9 秒、幅度 0.10 宽），不再是三道彩色扫射；
// 光源横向位置按正弦慢摆，暂停时 timeSec 停住、光也停住。只在剧院（id 5）画。
private fun DrawScope.drawSingleSpotlight(
    width: Float,
    height: Float,
    timeSec: Float,
) {
    if (width <= 0f || height <= 0f) return
    val sweep = sin(timeSec * (2f * Math.PI.toFloat()) / 9f) * 0.10f
    val sx = width * (0.5f + sweep)
    val baseHalf = width * 0.14f // 3.06：追光做亮做宽（半宽 0.10→0.14 宽）
    val path = Path().apply {
        moveTo(sx, 0f)
        lineTo(sx - baseHalf, height)
        lineTo(sx + baseHalf, height)
        close()
    }
    val brush = Brush.linearGradient(
        colors = listOf(
            Color(0xFFFFD98A).copy(alpha = 0.34f), // 3.06：追光顶端 0.22→0.34
            Color(0xFFFFD98A).copy(alpha = 0.14f), // 3.06：中段 0.08→0.14
            Color.Transparent,
        ),
        start = Offset(sx, 0f),
        end = Offset(sx, height),
    )
    drawPath(path, brush)
}

// 2.99 镜台光束：复用 2.98 drawSpotlights 的画法（每色一道，从顶部窄点打下来、向底部张开，顶端亮、向底部渐隐；
// 光源横向位置按正弦扫动，周期与幅度走模板，暂停时 timeSec 停住、光也停住）。
// 3.05：只在演唱会（id 4，三道红/蓝/紫 4 秒扫）与星空舞台（id 6，两道冷白淡蓝 6 秒扫）画；其余四款不再走这里。
private fun DrawScope.drawStageBeams(
    template: LyricVisualTemplate,
    width: Float,
    height: Float,
    timeSec: Float,
) {
    val colors = template.spotlightColors
    if (colors.isEmpty() || width <= 0f || height <= 0f) return
    val count = colors.size
    for (i in colors.indices) {
        val baseX = (i + 0.5f) / count
        val phase = i * 2.1f
        val sweep = sin(timeSec * (2f * Math.PI.toFloat()) / template.spotlightSweepPeriodSec + phase) * template.spotlightSweepAmp
        val sx = width * (baseX + sweep)
        val baseHalf = width * 0.16f
        val path = Path().apply {
            moveTo(sx, 0f)
            lineTo(sx - baseHalf, height)
            lineTo(sx + baseHalf, height)
            close()
        }
        val brush = Brush.linearGradient(
            colors = listOf(
                colors[i].copy(alpha = template.spotlightAlpha),
                colors[i].copy(alpha = template.spotlightAlpha * 0.35f),
                Color.Transparent,
            ),
            start = Offset(sx, 0f),
            end = Offset(sx, height),
        )
        drawPath(path, brush)
    }
}

// 3.01 舞台布景——用户定：三款各有自己的景，随着唱歌在变，不是只有歌词在变；背景舞台效果千变万化。
// 演唱会：LED 巨幕（顶部均衡器条，随时间跳动）+ 灯柱（两侧竖光柱，亮度脉动）+ 观众剪影（底部人头/举手，左右轻摇）。
// 3.05：巨幕放大成歌词的底——歌词贴在巨幕上（屏从 0.12 盖到 0.72 高，歌词居中正好落在屏里）；
// 均衡器条只留在屏顶一带（屏顶 30% 内跳动），不再占满整屏，免得跟歌词打架。
private fun DrawScope.drawConcertScenery(width: Float, height: Float, timeSec: Float) {
    // LED 巨幕：顶部一块屏，16 根竖条按时间跳动，红蓝紫轮换
    // 3.06：巨幕放大成歌词的底（0.12–0.72→0.08–0.78 高），歌词 5 行贴在屏内、当前句在屏中央，不再悬在屏下
    val screenTop = height * 0.08f
    val screenBottom = height * 0.78f
    val screenLeft = width * 0.07f
    val screenRight = width * 0.93f
    val screenH = screenBottom - screenTop
    drawRoundRect(
        color = Color(0xFF0D0610),
        topLeft = Offset(screenLeft, screenTop),
        size = Size(screenRight - screenLeft, screenH),
        cornerRadius = CornerRadius(12.dp.toPx()),
    )
    val barColors = listOf(Color(0xFFFF3131), Color(0xFF2979FF), Color(0xFFB967FF))
    val barCount = 16
    val barW = (screenRight - screenLeft) / barCount
    val barBottom = screenTop + screenH * 0.30f
    for (i in 0 until barCount) {
        val pulse = 0.35f + 0.65f * abs(sin(timeSec * 2.2f + i * 0.9f))
        val bh = screenH * 0.22f * pulse
        val bx = screenLeft + i * barW + barW * 0.15f
        drawRoundRect(
            color = barColors[i % 3].copy(alpha = 0.85f),
            topLeft = Offset(bx, barBottom - bh),
            size = Size(barW * 0.7f, bh),
            cornerRadius = CornerRadius(4.dp.toPx()),
        )
    }
    // 灯柱：两侧竖光柱，下亮上暗，亮度随时间脉动
    for (side in 0..1) {
        val cx = if (side == 0) width * 0.03f else width * 0.97f
        val glow = 0.10f + 0.08f * sin(timeSec * 1.6f + side * 2.0f)
        val brush = Brush.verticalGradient(
            colors = listOf(Color(0xFFFF3131).copy(alpha = glow), Color.Transparent),
            startY = height,
            endY = height * 0.25f,
        )
        drawRect(
            brush = brush,
            topLeft = Offset(cx - 14.dp.toPx(), height * 0.25f),
            size = Size(28.dp.toPx(), height * 0.75f),
        )
    }
    // 观众剪影：底部一排人头 + 几只举手，左右轻摇
    val baseY = height * 0.92f
    val headR = 14.dp.toPx()
    for (i in 0 until 14) {
        val fx = (i + 0.5f) / 14f
        val sway = sin(timeSec * 0.9f + i * 1.3f) * 4.dp.toPx()
        val hx = width * fx + sway
        drawCircle(
            color = Color.Black.copy(alpha = 0.6f),
            radius = headR,
            center = Offset(hx, baseY),
        )
        if (i % 3 == 1) {
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.6f),
                topLeft = Offset(hx + headR * 0.4f, baseY - headR * 3.2f),
                size = Size(7.dp.toPx(), headR * 2.2f),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }
    }
    // 3.02 新颖：彩带雨——24 片彩色纸片从顶部飘落、旋转（确定性位置，同首歌一致）；观众手机灯——观众区一排小亮点明灭，像举起的手机灯
    val confettiColors = listOf(Color(0xFFFF3131), Color(0xFF2979FF), Color(0xFFB967FF), Color(0xFFFFD98A), Color(0xFF39FF14))
    for (i in 0 until 24) {
        val seed = i * 37.7f
        val fx = 0.08f + abs(sin(seed)) * 0.84f // 3.06：彩带横向收进 0.08–0.92，左边缘不再有孤立彩色小方块
        val fallSpeed = 0.05f + abs(sin(seed * 1.3f)) * 0.06f // 每秒下落的高度分数
        val fy = ((timeSec * fallSpeed + abs(cos(seed * 2.1f))) % 1f) * height
        val swayX = sin(timeSec * 1.1f + seed) * 12.dp.toPx()
        val pieceW = 8.dp.toPx()
        val pieceH = 5.dp.toPx()
        drawRect(
            color = confettiColors[i % 5].copy(alpha = 0.85f),
            topLeft = Offset(width * fx + swayX - pieceW / 2f, fy),
            size = Size(pieceW, pieceH),
        )
    }
    for (i in 0 until 10) {
        val fx = (i + 0.5f) / 10f
        val twinkle = 0.35f + 0.65f * abs(sin(timeSec * 1.8f + i * 2.4f))
        drawCircle(
            color = Color(0xFFFFF6D8).copy(alpha = twinkle),
            radius = 3.dp.toPx(),
            center = Offset(width * fx, height * 0.86f + sin(timeSec * 0.8f + i) * 3.dp.toPx()),
        )
    }
}

// 剧院：红色帷幕（两侧垂褶，轻摆）+ 暖金追光（顶部中央一大团暖光，左右轻摇）+ 舞台地板（底部木纹横板）。
private fun DrawScope.drawTheaterScenery(width: Float, height: Float, timeSec: Float) {
    // 舞台地板：底部一块暖棕木板，横纹
    val floorTop = height * 0.80f
    drawRect(
        color = Color(0xFF4A3018), // 3.06：地板提亮
        topLeft = Offset(0f, floorTop),
        size = Size(width, height - floorTop),
    )
    for (i in 1..3) {
        val fy = floorTop + (height - floorTop) * i / 4f
        drawLine(
            color = Color(0xFF241505),
            start = Offset(0f, fy),
            end = Offset(width, fy),
            strokeWidth = 2.dp.toPx(),
        )
    }
    drawLine(
        color = Color(0xFFFFD98A).copy(alpha = 0.5f),
        start = Offset(0f, floorTop),
        end = Offset(width, floorTop),
        strokeWidth = 3.dp.toPx(),
    )
    // 红色帷幕：左右两幅，垂褶（深浅红竖条交替），轻摆
    val curtainW = width * 0.13f
    for (side in 0..1) {
        val sway = sin(timeSec * 0.7f + side * 1.5f) * 3.dp.toPx()
        val cx0 = if (side == 0) 0f + sway else width - curtainW + sway
        val folds = 5
        for (f in 0 until folds) {
            val fx = cx0 + curtainW * f / folds
            drawRect(
                color = if (f % 2 == 0) Color(0xFF8A1414) else Color(0xFFC22E2E), // 3.06：幕布暗红→亮一点的红
                topLeft = Offset(fx, 0f),
                size = Size(curtainW / folds, height * 0.82f),
            )
        }
    }
    // 暖金追光：顶部中央一大团暖光，左右轻摇
    val spotX = width * 0.5f + sin(timeSec * 0.5f) * width * 0.12f
    val glowBrush = Brush.radialGradient(
        colors = listOf(Color(0xFFFFD98A).copy(alpha = 0.50f), Color.Transparent), // 3.06：暖金光晕 0.35→0.50
        center = Offset(spotX, height * 0.12f),
        radius = width * 0.55f, // 3.06：光晕范围加大
    )
    drawRect(brush = glowBrush, size = Size(width, height))
    // 3.02 新颖：脚灯——舞台前沿一排暖黄小灯，亮度逐个明灭，像真正的剧院脚灯（floorTop 是本函数开头已定义的地板顶线）
    for (i in 0 until 9) {
        val fx = (i + 0.5f) / 9f
        val glow = 0.55f + 0.45f * sin(timeSec * 1.4f + i * 1.9f)
        drawCircle(
            color = Color(0xFFFFD98A).copy(alpha = 0.25f * glow),
            radius = 10.dp.toPx(),
            center = Offset(width * fx, floorTop),
        )
        drawCircle(
            color = Color(0xFFFFE9B8).copy(alpha = glow),
            radius = 4.dp.toPx(),
            center = Offset(width * fx, floorTop),
        )
    }
}

// 星空舞台：星轨（三条椭圆星轨，缓慢旋转）+ 月光（右上月盘 + 光晕，光晕轻脉动）。
// 3.50 星空舞台背景按视频走：视频里没有月亮/星轨/银河/彗星/点块/光束，只有暗底 + 暖橙光晕 + 细网格 + 细线装饰 + 小星点。
// 月亮、星轨、银河、彗星、点块、光束全部撤掉（只改星空舞台 id 6）；暖光晕（3.48，已在画布里画）与小星点（150 颗）留着。
// 细网格：全屏 40dp 间距、alpha 0.05；细线装饰：复用视觉器的 drawDecorations（模板 decor 参数，3.47 已设全 6 种、12 个、alpha 0.30、16–36dp）。
private fun DrawScope.drawStarScenery(
    width: Float,
    height: Float,
    timeSec: Float,
    template: LyricVisualTemplate,
    songKey: String,
    lineIndex: Int,
) {
    // 细网格：全屏淡格子
    val gridStep = 40.dp.toPx()
    val gridColor = Color.White.copy(alpha = 0.05f)
    var gx = 0f
    while (gx <= width) {
        drawLine(gridColor, Offset(gx, 0f), Offset(gx, height), strokeWidth = 1f)
        gx += gridStep
    }
    var gy = 0f
    while (gy <= height) {
        drawLine(gridColor, Offset(0f, gy), Offset(width, gy), strokeWidth = 1f)
        gy += gridStep
    }
    // 细线装饰：爱心/花/格/圆/加号/六边形（复用视觉器，与模板 decor 参数一致）
    drawDecorations(lineIndex, songKey.hashCode(), width, height, template)
}

// 3.03 沙画：暖沙底 + 沙粒流动成画 + 沙画手。
// 3.05：底色改亮（#6B4F2A，见 stageBackground）；暖沙氛围光做亮。3.06 已废弃 3.05 的 160 颗随机流动沙粒与手尖亮痕，见下。
// 3.06 沙画重做：沙子真的画出完整的画——三幅画循环（爱心 → 飞鸟 → 花朵），每幅 50 秒：画 22 秒、停留 20 秒、化 8 秒。
// 一首歌（3 分钟）能画完 3 幅以上。轮廓点列预先按参数 t 均匀采样；画的进度 p = 画内时间 / 22 秒，t <= p 的沙粒显示，
// 靠近画笔尖的几颗更亮、稍大；停留期全显；化解期按 t 倒序淡出。160 颗随机流动沙粒全部撤掉，只留约 20 颗极淡背景沙尘。
// 手：手臂（右下斜伸的深色粗条 + 暖色内芯）+ 手掌椭圆 + 指尖亮沙，跟着画笔尖（t = p 处）走，朝向沿轮廓切线；
// 化解期手淡出、下一幅开始时在新起点淡入。时间基准 timeSec（暂停时停住）。
// 画的序号 = (timeSec / 50f).toInt() % 3，画内时间 = timeSec % 50f。
private fun sandShapePoints(shape: Int, width: Float, height: Float): List<Offset> {
    val cx = width * 0.5f
    val cy = height * 0.42f
    val points = ArrayList<Offset>()
    when (shape) {
        0 -> {
            // 爱心：参数方程心形轮廓，x = 16sin³t、y = 13cos t − 5cos2t − 2cos3t − cos4t；约 120 颗。
            // 实测 y 取值约 −17..11.9（跨度 29，中点 −2.5），x 跨度 32；用统一缩放（取宽/高方向的较小值）
            // 居中放到宽约 55%、高约 40% 的中央区域，心形比例不拉长。
            val scale = minOf(width * 0.55f / 32f, height * 0.40f / 29f)
            for (i in 0 until 120) {
                val t = i / 120f * 2f * PI.toFloat()
                val x = 16f * sin(t) * sin(t) * sin(t)
                val y = 13f * cos(t) - 5f * cos(2f * t) - 2f * cos(3f * t) - cos(4f * t)
                points.add(Offset(cx + x * scale, cy - (y + 2.5f) * scale))
            }
            // 内部小弧线：约 20 颗，爱心内部上半一道小弧
            val arcCx = cx
            val arcCy = cy - height * 0.02f
            val arcRx = width * 0.09f
            val arcRy = height * 0.05f
            for (i in 0 until 20) {
                val a = (200f + i / 20f * 140f) * PI.toFloat() / 180f
                points.add(Offset(arcCx + cos(a) * arcRx, arcCy + sin(a) * arcRy))
            }
        }
        1 -> {
            // 飞鸟：左右翼弧各约 40 颗（上弯拱形，像张开的翅膀）+ 身体短弧约 20 颗，共约 100 颗
            val wingRy = height * 0.12f
            for (side in listOf(-1f, 1f)) {
                val wingCx = cx + side * width * 0.11f
                val wingCy = cy + height * 0.06f
                val wingRx = width * 0.15f
                for (i in 0 until 40) {
                    val a = (200f + i / 40f * 140f) * PI.toFloat() / 180f
                    points.add(Offset(wingCx + cos(a) * wingRx, wingCy + sin(a) * wingRy))
                }
            }
            val bodyCx = cx
            val bodyCy = cy + height * 0.03f
            for (i in 0 until 20) {
                val a = (20f + i / 20f * 140f) * PI.toFloat() / 180f
                points.add(Offset(bodyCx + cos(a) * width * 0.055f, bodyCy + sin(a) * height * 0.04f))
            }
        }
        else -> {
            // 花朵：中心小圆约 25 颗 + 5 片花瓣（每片椭圆弧轮廓约 15 颗）+ 一根茎（竖弧约 15 颗），共约 115 颗
            val centerR = width * 0.05f
            for (i in 0 until 25) {
                val a = i / 25f * 2f * PI.toFloat()
                points.add(Offset(cx + cos(a) * centerR, cy + sin(a) * centerR))
            }
            for (petal in 0 until 5) {
                val petalAngle = petal / 5f * 2f * PI.toFloat() - PI.toFloat() / 2f
                val petalCx = cx + cos(petalAngle) * width * 0.11f
                val petalCy = cy + sin(petalAngle) * height * 0.11f
                val rx = width * 0.065f
                val ry = height * 0.09f
                for (i in 0 until 15) {
                    val a = i / 15f * 2f * PI.toFloat()
                    val lx = cos(a) * rx
                    val ly = sin(a) * ry
                    val px = petalCx + lx * cos(petalAngle) - ly * sin(petalAngle)
                    val py = petalCy + lx * sin(petalAngle) + ly * cos(petalAngle)
                    points.add(Offset(px, py))
                }
            }
            for (i in 0 until 15) {
                val t = i / 15f
                val sx = cx + sin(t * PI.toFloat()) * width * 0.01f
                val sy = cy + height * 0.16f + t * height * 0.16f
                points.add(Offset(sx, sy))
            }
        }
    }
    return points
}

private fun DrawScope.drawSandPaintingScenery(width: Float, height: Float, timeSec: Float) {
    // 暖沙氛围光：中央一大团暖金光晕，照亮整幅沙画（沙画不再用通用光束，靠这团光）
    val sandGlow = Brush.radialGradient(
        colors = listOf(Color(0xFFF0C878).copy(alpha = 0.20f), Color.Transparent),
        center = Offset(width * 0.5f, height * 0.42f),
        radius = width * 0.62f,
    )
    drawRect(brush = sandGlow, size = Size(width, height))
    // 背景沙尘：约 20 颗极淡，缓慢漂移做氛围，不抢戏
    for (i in 0 until 20) {
        val seed = i * 17.31f
        val px = width * abs(sin(seed)) + sin(timeSec * 0.10f + seed) * 6.dp.toPx()
        val py = height * abs(cos(seed * 1.3f)) + cos(timeSec * 0.08f + seed) * 5.dp.toPx()
        drawCircle(
            color = Color(0xFFE8C878).copy(alpha = 0.10f + 0.05f * abs(sin(seed * 2.7f))),
            radius = (1.2f + abs(sin(seed * 3.1f)) * 1.0f).dp.toPx(),
            center = Offset(px, py),
        )
    }
    // 三幅画循环：画 22 秒、停留 20 秒、化 8 秒
    val shape = (timeSec / 50f).toInt() % 3
    val inCycle = timeSec % 50f
    val points = sandShapePoints(shape, width, height)
    val total = points.size
    val center = Offset(width * 0.5f, height * 0.42f)
    val maxDist = width * 0.30f
    var tipIndex = total - 1
    var tipPoint: Offset = points.last()
    when {
        inCycle < 22f -> {
            // 画：进度 p = 画内时间 / 22 秒，t <= p 的沙粒显示，靠近画笔尖的几颗更亮、稍大
            val p = inCycle / 22f
            tipIndex = (total * p).toInt().coerceIn(0, total - 1)
            tipPoint = points[tipIndex]
            for (i in 0..tipIndex) {
                val dx = points[i].x - center.x
                val dy = points[i].y - center.y
                val dist = sqrt(dx * dx + dy * dy)
                val centerFade = 1f - (dist / maxDist).coerceIn(0f, 1f) * 0.25f
                val nearTip = (tipIndex - i).coerceAtMost(8)
                val tipBoost = (1f - nearTip / 8f) * 0.25f
                val radiusDp = 1.6f + abs(sin(i * 3.1f)) * 1.2f + if (tipIndex - i <= 3) 1.0f else 0f
                drawCircle(
                    color = Color(0xFFE8C878).copy(alpha = ((0.60f + tipBoost) * centerFade).coerceIn(0f, 1f)),
                    radius = radiusDp.dp.toPx(),
                    center = points[i],
                )
            }
        }
        inCycle < 42f -> {
            // 停留：整幅画完整可见 20 秒
            tipIndex = total - 1
            tipPoint = points.last()
            for (i in points.indices) {
                val dx = points[i].x - center.x
                val dy = points[i].y - center.y
                val dist = sqrt(dx * dx + dy * dy)
                val centerFade = 1f - (dist / maxDist).coerceIn(0f, 1f) * 0.25f
                drawCircle(
                    color = Color(0xFFE8C878).copy(alpha = (0.85f * centerFade).coerceIn(0f, 1f)),
                    radius = (1.6f + abs(sin(i * 3.1f)) * 2.2f).dp.toPx(),
                    center = points[i],
                )
            }
        }
        else -> {
            // 化：8 秒按 t 倒序淡出（后画的先化）
            val dissolveP = (inCycle - 42f) / 8f
            tipIndex = total - 1
            tipPoint = points.last()
            for (i in points.indices) {
                val t = i / total.toFloat()
                val start = (1f - t) * 0.5f
                val remain = 1f - ((dissolveP - start) / 0.5f).coerceIn(0f, 1f)
                if (remain <= 0f) continue
                val dx = points[i].x - center.x
                val dy = points[i].y - center.y
                val dist = sqrt(dx * dx + dy * dy)
                val centerFade = 1f - (dist / maxDist).coerceIn(0f, 1f) * 0.25f
                drawCircle(
                    color = Color(0xFFE8C878).copy(alpha = (0.85f * remain * centerFade).coerceIn(0f, 1f)),
                    radius = (1.6f + abs(sin(i * 3.1f)) * 2.2f).dp.toPx(),
                    center = points[i],
                )
            }
        }
    }
    // 沙画手：跟着画笔尖走，朝向沿轮廓切线；化解期淡出、下一幅开始时在新起点淡入
    val handAlpha = when {
        inCycle < 22f -> (inCycle / 1.5f).coerceIn(0f, 1f)
        inCycle < 42f -> 0.9f
        else -> (1f - (inCycle - 42f) / 8f).coerceIn(0f, 1f)
    }
    if (handAlpha > 0.01f) {
        val tip = tipPoint
        val prev = points[(tipIndex - 1).coerceAtLeast(0)]
        val dx = tip.x - prev.x
        val dy = tip.y - prev.y
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val tx = dx / len
        val ty = dy / len
        // 手掌在指尖后方 24dp 处（沿切线反方向）
        val palm = Offset(tip.x - tx * 24.dp.toPx(), tip.y - ty * 24.dp.toPx())
        // 手臂：从画面右下斜伸出的深色粗条 + 暖色内芯（不再是一道黑斜条）
        drawLine(
            color = Color(0xFF2A1A08).copy(alpha = 0.95f * handAlpha),
            start = Offset(width * 0.96f, height * 1.02f),
            end = palm,
            strokeWidth = 20.dp.toPx(),
        )
        drawLine(
            color = Color(0xFF8A5A30).copy(alpha = 0.85f * handAlpha),
            start = Offset(width * 0.96f, height * 1.02f),
            end = palm,
            strokeWidth = 13.dp.toPx(),
        )
        // 手掌：椭圆轮廓
        drawOval(
            color = Color(0xFF3A2410).copy(alpha = 0.95f * handAlpha),
            topLeft = Offset(palm.x - 17.dp.toPx(), palm.y - 13.dp.toPx()),
            size = Size(34.dp.toPx(), 26.dp.toPx()),
        )
        // 手指：手掌到指尖，肤色/暖色
        drawLine(
            color = Color(0xFFC68A4A).copy(alpha = 0.95f * handAlpha),
            start = palm,
            end = tip,
            strokeWidth = 9.dp.toPx(),
        )
        // 指尖亮沙：一团亮金沙跟着画笔尖走
        drawCircle(
            color = Color(0xFFF0C878).copy(alpha = 0.35f * handAlpha),
            radius = 18.dp.toPx(),
            center = tip,
        )
        drawCircle(
            color = Color(0xFFF0C878).copy(alpha = 0.65f * handAlpha),
            radius = 10.dp.toPx(),
            center = tip,
        )
        drawCircle(
            color = Color(0xFFFFE9B8).copy(alpha = 0.95f * handAlpha),
            radius = 5.dp.toPx(),
            center = tip,
        )
    }
}

// 3.03 游子归家：暖黄灯下书桌——底部书桌剪影 + 桌上暖黄台灯（光晕轻脉动）+ 信纸与笔的剪影；窗外一轮月光。
private fun DrawScope.drawWandererScenery(width: Float, height: Float, timeSec: Float) {
    // 窗外月光：右上淡黄月盘 + 光晕（轻脉动）
    val moonC = Offset(width * 0.82f, height * 0.10f)
    val moonGlow = 0.10f + 0.04f * sin(timeSec * 0.6f)
    drawCircle(
        color = Color(0xFFFFF6D8).copy(alpha = moonGlow),
        radius = 78.dp.toPx(), // 3.06：月亮光晕 52→78dp（月亮 ×1.5）
        center = moonC,
    )
    drawCircle(
        color = Color(0xFFFFF6D8),
        radius = 39.dp.toPx(), // 3.06：月盘 26→39dp（×1.5）
        center = moonC,
    )
    // 书桌剪影：底部一张书桌（桌面横条 + 桌身）
    val deskTop = height * 0.82f
    drawRect(
        color = Color(0xFF3A2410),
        topLeft = Offset(0f, deskTop),
        size = Size(width, height * 0.04f),
    )
    drawRect(
        color = Color(0xFF241505),
        topLeft = Offset(0f, deskTop + height * 0.04f),
        size = Size(width, height - deskTop - height * 0.04f),
    )
    drawLine(
        color = Color(0xFFFFD98A).copy(alpha = 0.35f),
        start = Offset(0f, deskTop),
        end = Offset(width, deskTop),
        strokeWidth = 2.dp.toPx(),
    )
    // 台灯：左桌角，灯座 + 灯杆 + 灯罩 + 暖光晕（轻脉动；3.05：光晕做亮做大——游子不要通用光束，靠这盏台灯照亮信纸）
    val lampX = width * 0.20f
    val lampGlow = 0.28f + 0.06f * sin(timeSec * 0.8f)
    drawCircle(
        color = Color(0xFFFFD98A).copy(alpha = lampGlow),
        radius = 143.dp.toPx(), // 3.06：台灯光晕 110→143dp（×1.3）
        center = Offset(lampX, deskTop - 46.dp.toPx()),
    )
    drawRoundRect(
        color = Color(0xFF1A0F02),
        topLeft = Offset(lampX - 23.dp.toPx(), deskTop - 10.dp.toPx()),
        size = Size(46.dp.toPx(), 10.dp.toPx()),
        cornerRadius = CornerRadius(5.dp.toPx()),
    )
    drawLine(
        color = Color(0xFF1A0F02),
        start = Offset(lampX, deskTop - 10.dp.toPx()),
        end = Offset(lampX, deskTop - 62.dp.toPx()),
        strokeWidth = 5.dp.toPx(),
    )
    val shadePath = Path().apply {
        moveTo(lampX - 34.dp.toPx(), deskTop - 62.dp.toPx())
        lineTo(lampX + 34.dp.toPx(), deskTop - 62.dp.toPx())
        lineTo(lampX + 21.dp.toPx(), deskTop - 94.dp.toPx())
        lineTo(lampX - 21.dp.toPx(), deskTop - 94.dp.toPx())
        close()
    } // 3.06：灯罩/灯杆 ×1.3（26→34、72→94、48→62）
    drawPath(shadePath, Color(0xFFFFD98A))
    // 信纸：桌上中央偏右一封白信纸（微斜），上面两道灰字行；信纸边一支笔的剪影
    val paperPath = Path().apply {
        val px = width * 0.55f
        val py = deskTop - 58.dp.toPx()
        val pw = 90.dp.toPx()
        val ph = 58.dp.toPx()
        moveTo(px, py)
        lineTo(px + pw, py - 6.dp.toPx())
        lineTo(px + pw + 4.dp.toPx(), py + ph)
        lineTo(px + 4.dp.toPx(), py + ph + 6.dp.toPx())
        close()
    }
    drawPath(paperPath, Color(0xFFF5EFE0).copy(alpha = 0.92f))
    drawLine(
        color = Color(0xFF8A7A5A).copy(alpha = 0.6f),
        start = Offset(width * 0.57f, deskTop - 44.dp.toPx()),
        end = Offset(width * 0.63f, deskTop - 46.dp.toPx()),
        strokeWidth = 1.5.dp.toPx(),
    )
    drawLine(
        color = Color(0xFF8A7A5A).copy(alpha = 0.6f),
        start = Offset(width * 0.57f, deskTop - 32.dp.toPx()),
        end = Offset(width * 0.64f, deskTop - 34.dp.toPx()),
        strokeWidth = 1.5.dp.toPx(),
    )
    drawLine(
        color = Color(0xFF1A0F02),
        start = Offset(width * 0.66f, deskTop - 12.dp.toPx()),
        end = Offset(width * 0.71f, deskTop - 38.dp.toPx()),
        strokeWidth = 3.dp.toPx(),
    )
}

// 3.03 武侠：江湖意境——冷青底 + 明月 + 竹林剪影（竹叶轻摆）+ 剑气寒光（斜斜一道寒光缓慢扫动）。
private fun DrawScope.drawWuxiaScenery(width: Float, height: Float, timeSec: Float) {
    // 明月：右上大月盘 + 光晕（轻脉动）
    val moonC = Offset(width * 0.80f, height * 0.12f)
    val moonGlow = 0.12f + 0.04f * sin(timeSec * 0.5f)
    drawCircle(
        color = Color(0xFFF0F8FF).copy(alpha = moonGlow),
        radius = 72.dp.toPx(),
        center = moonC,
    )
    drawCircle(
        color = Color(0xFFF0F8FF),
        radius = 36.dp.toPx(),
        center = moonC,
    )
    // 剑气寒光：斜斜一道寒光，从左上到右下，缓慢扫动（位置随时间左右漂移），像剑气
    // 3.05：加粗、加亮、扫幅做大——武侠不要通用光束，只留这一道剑气，斜扫过歌词区域（字层不动，布景层画剑气）
    // 3.06：收细收暗（见下：半宽 8dp、alpha 0.20–0.28、亮核 1.5dp），3.05 的加粗加亮已废弃
    val swordX = width * (0.30f + 0.45f * sin(timeSec * 0.14f))
    val swordBrush = Brush.linearGradient(
        colors = listOf(Color.Transparent, Color(0xFFBFE8FF).copy(alpha = 0.20f), Color(0xFFE8F8FF).copy(alpha = 0.28f), Color(0xFFBFE8FF).copy(alpha = 0.20f), Color.Transparent), // 3.06：剑气 0.42–0.55→0.20–0.28
        start = Offset(swordX - 90.dp.toPx(), 0f),
        end = Offset(swordX + 90.dp.toPx(), 0f),
    )
    val swordPath = Path().apply {
        val topX = swordX
        val botX = swordX + width * 0.22f
        moveTo(topX - 8.dp.toPx(), 0f) // 3.06：剑气半宽 16→8dp
        lineTo(topX + 8.dp.toPx(), 0f)
        lineTo(botX + 8.dp.toPx(), height * 0.85f)
        lineTo(botX - 8.dp.toPx(), height * 0.85f)
        close()
    }
    drawPath(swordPath, swordBrush)
    // 剑气亮核：中央一道细亮线，剑气最亮处
    drawLine(
        color = Color(0xFFFFFFFF).copy(alpha = 0.22f + 0.08f * sin(timeSec * 1.1f)), // 3.06：亮核 0.55→0.22–0.30
        start = Offset(swordX, 0f),
        end = Offset(swordX + width * 0.22f, height * 0.85f),
        strokeWidth = 1.5.dp.toPx(), // 3.06：亮核 3→1.5dp
    )
    // 竹林剪影：3.05 收拢到两侧、框住字区——左 4 根、右 4 根（原来 7 根横贯全屏，会穿过歌词）
    val bambooCount = 8
    for (i in 0 until bambooCount) {
        val fx = if (i < 4) 0.04f + i * 0.055f else 0.78f + (i - 4) * 0.055f
        val topY = height * (0.52f + abs(sin(i * 7.3f)) * 0.18f)
        val bx = width * fx
        drawRect(
            color = Color(0xFF2A7A55), // 3.06：竹干深青黑→看得清的青绿
            topLeft = Offset(bx - 3.dp.toPx(), topY),
            size = Size(6.dp.toPx(), height - topY),
        )
        // 竹叶：竹顶两片细叶，角度随时间轻摆
        val sway = sin(timeSec * 0.7f + i * 1.9f) * 0.12f
        drawLine(
            color = Color(0xFF4CAF7D), // 3.06：竹叶提亮成青绿
            start = Offset(bx, topY + 6.dp.toPx()),
            end = Offset(bx + (14.dp.toPx() * cos(0.6f + sway)), topY - 10.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
        )
        drawLine(
            color = Color(0xFF4CAF7D), // 3.06：竹叶提亮成青绿
            start = Offset(bx, topY + 10.dp.toPx()),
            end = Offset(bx - (14.dp.toPx() * cos(0.6f - sway)), topY - 8.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
        )
    }
}

@Composable
private fun LyricControlRow(
    visual: Boolean,
    onToggleVisual: () -> Unit,
    onFullscreen: () -> Unit,
    offsetMs: Int,
    totalOffsetMs: Int,
    onOffsetChange: (Int) -> Unit,
    onResetAll: () -> Unit,
    modifier: Modifier = Modifier,
    scale: Double = 1.0,
    twoPointArmed: Boolean = false,
    onToggleTwoPoint: () -> Unit = {},
    // 2.98 舞台钮：当前是否在舞台模板上（高亮），点一下出弹窗选舞台
    isStage: Boolean = false,
    onOpenStagePicker: () -> Unit = {},
) {
    // 2.94 回零不显示：回零钮删了，长按校准就是回零（onResetAll）。
    // TextButton 默认最小点按 48dp，2.93 只收内边距没用；整行去掉最小点按，
    // 钮按字 + 内边距走，− 0.00s + 那组收紧。
    // 3.00：不要居中那套——2.99 的三段式/Spacer 居中把按钮挤折行（全屏折成 全/屏、校准折成 校/准）。
    // 回到 2.98 单行 SpaceBetween（动效/全屏/舞台/校准/偏移组），只把间距收紧：
    // 钮横向内边距 4dp→2dp、−/+ 2dp→1dp、校准 4dp→2dp，一行放得下，不折行。
    // 数字仍在 − 和 + 中间，颜色仍是 onSurface 白。
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 3.07 第二阶段 C-edit（歌词控制行收窄）：三项由 ControlSurface
        // （kind=CHIP，横向内边距 12dp + 最小高 32dp）改成 controlButtonModifier
        // （横向 8dp / 纵向 4dp，无最小高），行总宽约 326dp → 约 266dp，
        // 320dp 窄屏不再折行；combinedClickable 仍同时处理点按与长按
        // （长按调编辑，不吞点按）；3.17：校准与 −/0.00s/+ 组也加胶囊框。
        // 隐藏后不渲染（设置页可恢复）。
        if (!ControlThemeStore.isElementHidden("music_lyric_visual")) {
            Text(
                text = if (visual) "动效" else "列表",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .combinedClickable(
                        onClick = onToggleVisual,
                        onLongClick = { ControlEditState.requestEdit("music_lyric_visual") },
                    )
                    .then(controlButtonModifier("music_lyric_visual", kind = ControlElementKind.CHIP))
                    .padding(horizontal = 3.dp)
                    .heightIn(min = 32.dp)
                    .wrapContentHeight(align = Alignment.CenterVertically),
            )
        }
        // 3.07 百变控件：全屏项只套表面外观（kind=CHIP），接法同上。
        if (!ControlThemeStore.isElementHidden("music_lyric_fullscreen")) {
            Text(
                text = "全屏",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .combinedClickable(
                        onClick = onFullscreen,
                        onLongClick = { ControlEditState.requestEdit("music_lyric_fullscreen") },
                    )
                    .then(controlButtonModifier("music_lyric_fullscreen", kind = ControlElementKind.CHIP))
                    .padding(horizontal = 3.dp)
                    .heightIn(min = 32.dp)
                    .wrapContentHeight(align = Alignment.CenterVertically),
            )
        }
        // 2.98 舞台钮：点一下出弹窗，选哪个舞台就进哪个；
        // 在舞台模板上时字变主题主色加粗（跟校准待命同款高亮）。回零已由长按校准替代，收紧间距后这里有位置。
        // 3.07 百变控件：舞台项只套表面外观（kind=CHIP），接法同上。
        if (!ControlThemeStore.isElementHidden("music_lyric_stage")) {
            Text(
                text = "舞台",
                style = MaterialTheme.typography.bodyLarge,
                color = if (isStage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isStage) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .combinedClickable(
                        onClick = onOpenStagePicker,
                        onLongClick = { ControlEditState.requestEdit("music_lyric_stage") },
                    )
                    .then(controlButtonModifier("music_lyric_stage", kind = ControlElementKind.CHIP))
                    .padding(horizontal = 3.dp)
                    .heightIn(min = 32.dp)
                    .wrapContentHeight(align = Alignment.CenterVertically),
            )
        }
        // 2.92 校准待命：点一下进两点校准，再点取消；待命时点行是校准点，不跳播
        // 2.94 回零不显示：回零钮删了，长按校准就是回零
        Text(
            text = "校准",
            style = MaterialTheme.typography.bodyLarge,
            color = if (twoPointArmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (twoPointArmed) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier
                .combinedClickable(
                    onClick = onToggleTwoPoint,
                    onLongClick = { onResetAll() },
                )
                .then(controlButtonModifier("music_lyric_calibrate", kind = ControlElementKind.CHIP))
                .padding(horizontal = 3.dp)
                .heightIn(min = 32.dp)
                .wrapContentHeight(align = Alignment.CenterVertically),
        )
        // 3.06：− / + 收紧——之前是 TextButton，钮内边距再小也有空荡，0.00s 离 − 太远；
        // 改成跟“校准”一样的纯 Text + clickable，横向只留 2dp，0.00s 往前贴近 −。
        // 3.17：−/0.00s/+ 整组一个胶囊框（id music_lyric_offset），与校准同款。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .then(controlButtonModifier("music_lyric_offset", kind = ControlElementKind.CHIP))
                .padding(horizontal = 3.dp)
                .heightIn(min = 32.dp)
                .wrapContentHeight(align = Alignment.CenterVertically),
        ) {
            Text(
                text = "−",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .clickable { onOffsetChange((offsetMs - 500).coerceAtLeast(-5000)) }
                    .padding(horizontal = 2.dp, vertical = 2.dp),
            )
            Text(
                text = formatOffset(totalOffsetMs),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 2.dp),
                // 2.99：偏移数字跟 − / + 同色（onSurface）——之前 onSurfaceVariant 在棕色主题上显脏绿，跟两边白色不搭
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (scale != 1.0) {
                Text(
                    text = "×" + "%.2f".format(scale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = "+",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .clickable { onOffsetChange((offsetMs + 500).coerceAtMost(5000)) }
                    .padding(horizontal = 2.dp, vertical = 2.dp),
            )
        }
    }
    }
}

private fun formatOffset(ms: Int): String {
    val absMs = abs(ms)
    val sign = if (ms > 0) "+" else if (ms < 0) "−" else ""
    return sign + (absMs / 1000) + "." + (absMs % 1000 / 10).toString().padStart(2, '0') + "s"
}

// 2.90 第 5 项 5B：行级全屏列表——只有错落横排一种布局（plan.md §0.4 纠正：其余三款行级变体与 MUSIC_LYRIC_SCATTER_TEMPLATE 作废，不做）。
// 主区小列表：每行横向错开 x = (index % 3) × 24dp、左对齐，行间距 10dp，偶行 −2°、奇行 +2°。
// 2.91 第 3 项：全屏列表居中（centered = true）——错开 x 偏移删除、行文本 TextAlign.Center；其余不动。
// 2.94：主区小列表也居中（调用处 centered = true），与全屏一致；倾斜保留。
// 当前行主题主色 + Bold + ×1.12，非当前行 onSurfaceVariant。主区小列表与全屏列表共用同一 composable，两边同步。点行跳播、长按校准不动。
@Composable
private fun MusicLyrics(
    lines: List<LyricLine>,
    positionMs: Long,
    onSeekLine: (Long) -> Unit,
    onCalibrateLine: (LyricLine) -> Unit,
    modifier: Modifier = Modifier,
    jumpToCurrentTick: Int = 0,
    centered: Boolean = false,
) {
    val listState = rememberLazyListState()
    val current = remember(lines, positionMs) { lyricIndex(lines, positionMs) }
    // 手动滚动保护：用户手指拖动结束时记时间，5 秒内不自动跟随（5 秒无操作后下一行切换恢复跟随）；
    // 只认 DragInteraction（手指拖动），程序滚动（自动跟随/回到当前行）不触发。
    var userScrolledAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collectLatest { interaction ->
            if (interaction is DragInteraction.Stop || interaction is DragInteraction.Cancel) {
                userScrolledAt = System.currentTimeMillis()
            }
        }
    }
    LaunchedEffect(current) {
        if (current >= 0 && System.currentTimeMillis() - userScrolledAt >= 5000L) {
            listState.animateScrollToItem((current - 1).coerceAtLeast(0))
        }
    }
    // 全屏标题行“回到当前行”：清保护、立刻跳回当前行
    LaunchedEffect(jumpToCurrentTick) {
        if (jumpToCurrentTick > 0 && current >= 0) {
            userScrolledAt = 0L
            listState.animateScrollToItem((current - 1).coerceAtLeast(0))
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        itemsIndexed(lines) { index, line ->
            val active = index == current
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { rotationZ = if (index % 2 == 0) -2f else 2f }
                    .padding(
                        start = if (centered) 0.dp else ((index % 3) * 24).dp,
                        top = 10.dp,
                        bottom = 10.dp,
                    ),
            ) {
                Text(
                    text = line.text,
                    style = if (active) {
                        MaterialTheme.typography.titleMedium.copy(
                            fontSize = MaterialTheme.typography.titleMedium.fontSize * 1.12f,
                        )
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onSeekLine(line.timeMs) },
                            onLongClick = { onCalibrateLine(line) },
                        ),
                )
            }
        }
    }
}

@Composable
private fun MusicWaveProgress(
    positionMs: Long,
    durationMs: Long,
    waveSeed: Int,
    playing: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val amplitudes = remember { WaveAmplitudeCache() }
    val duration = durationMs.coerceAtLeast(1L)
    val fraction = if (dragging) dragFraction else (positionMs.toFloat() / duration).coerceIn(0f, 1f)

    val phaseState = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        if (!playing) {
            phaseState.value = 0f
            return@LaunchedEffect
        }
        var startNanos = 0L
        var lastEmitNanos = 0L
        while (true) {
            withFrameNanos { now ->
                if (startNanos == 0L) {
                    startNanos = now
                    lastEmitNanos = now
                }
                if (now - lastEmitNanos >= WAVE_FRAME_INTERVAL_NANOS) {
                    lastEmitNanos = now
                    val elapsed = (now - startNanos) % WAVE_PERIOD_NANOS
                    phaseState.value = elapsed.toFloat() / WAVE_PERIOD_NANOS.toFloat() * TWO_PI
                }
            }
        }
    }

    val playedColor = MaterialTheme.colorScheme.onSurface
    val idleColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(durationMs) {
                    detectTapGestures { offset ->
                        if (durationMs > 0) {
                            val target = (offset.x / size.width).coerceIn(0f, 1f)
                            onSeek((target * durationMs).toLong())
                        }
                    }
                }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        dragFraction = (dragFraction + delta / widthPx).coerceIn(0f, 1f)
                    },
                    onDragStarted = { offset ->
                        dragging = true
                        dragFraction = (offset.x / widthPx).coerceIn(0f, 1f)
                    },
                    onDragStopped = {
                        dragging = false
                        if (durationMs > 0) onSeek((dragFraction * durationMs).toLong())
                    },
                ),
        ) {
            val barWidth = 3.dp.toPx()
            val step = barWidth + 1.5.dp.toPx()
            val count = (size.width / step).toInt().coerceAtLeast(1)
            val centerY = size.height / 2f
            val minHeight = 4.dp.toPx()
            val maxHeight = (size.height - 2.dp.toPx()).coerceAtLeast(minHeight)
            val playedX = size.width * fraction
            val phase = phaseState.value
            val amplitudeValues = amplitudes.get(count, waveSeed)

            for (index in 0 until count) {
                val amplitude = amplitudeValues[index] *
                    (0.84f + 0.16f * sin(phase + index * 0.35f))
                val barHeight = (minHeight + (maxHeight - minHeight) * amplitude)
                    .coerceIn(minHeight, maxHeight)
                val x = index * step + barWidth / 2f
                drawRoundRect(
                    color = if (x <= playedX) playedColor else idleColor,
                    topLeft = Offset(x - barWidth / 2f, centerY - barHeight / 2f),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatDuration(if (dragging) (dragFraction * durationMs).toLong() else positionMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val SkipButtonSize = 64.dp

private val PlayButtonWidth = 84.dp

private val PlayButtonHeight = 76.dp

// 播放钮三档（2.82 四档，2.91 去掉特大）：小/中/大，12dp 一档；中档就是现在的 84×76。长按播放钮切换，存 KV。
// 2.84：设置面板里的胶囊选项——选中是主题主色底 + 白字，没选中是灰底 + 黑字，一眼认得出
/**
 * 搜歌词面板（2.95）：预填可编辑的“歌名 歌手”，候选逐词优先；点候选先预览，
 * 点“应用”按歌锁定；面板里也能导入本地 .lrc 文件或粘贴 lrc 文本。
 */
@Composable
private fun LyricSearchSheet(
    songKey: String,
    title: String,
    artist: String,
    platform: String,
    musicId: String,
    durationSec: Int,
    onClose: () -> Unit,
    onReloadLyric: () -> Unit,
) {
    var queryTitle by remember { mutableStateOf(title) }
    var queryArtist by remember { mutableStateOf(artist) }
    var candidates by remember { mutableStateOf<List<MusicLyricSearch.LyricCandidate>>(emptyList()) }
    var selected by remember { mutableStateOf<MusicLyricSearch.LyricCandidate?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchDone by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val hasLocked = MusicLyricSearch.Lock.getLocked(songKey) != null
    /**
     * 导入歌词：先按原样解析；纯文本（无时间标签）按时长均匀转成定时 LRC 再存，
     * 存的是能解析的版本，仍读不到有效行返回 false。
     */
    fun importText(raw: String): Boolean {
        if (raw.isBlank()) return false
        val timed = if (MusicLrc.parse(raw).isEmpty()) MusicLyricSearch.toTimedLrc(raw, durationSec) else raw
        if (MusicLrc.parse(timed).isEmpty()) return false
        MusicLyricSearch.Lock.saveLocked(songKey, timed, "导入")
        return true
    }
    val lrcLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                }.getOrNull()
            }.orEmpty()
            if (!importText(text)) {
                Toast.makeText(context, "没有读到有效歌词行", Toast.LENGTH_SHORT).show()
                return@launch
            }
            onReloadLyric()
            Toast.makeText(context, "已导入歌词", Toast.LENGTH_SHORT).show()
            onClose()
        }
    }
    fun doSearch() {
        val song = MusicSong(
            id = musicId,
            title = queryTitle.trim().ifBlank { title },
            artist = queryArtist.trim().ifBlank { artist },
            platform = platform,
            durationSec = durationSec,
        )
        searching = true
        searchDone = false
        selected = null
        scope.launch {
            val list = runCatching {
                MusicRepository.searchLyricCandidates(song, queryTitle, queryArtist)
            }.getOrElse { emptyList() }
            candidates = list
            searching = false
            searchDone = true
        }
    }
    AVBoxBottomSheet(
        onDismissRequest = onClose,
        title = "搜歌词",
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        isScrollable = true,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            OutlinedTextField(
                value = queryTitle,
                onValueChange = { queryTitle = it },
                label = { Text("歌名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = queryArtist,
                onValueChange = { queryArtist = it },
                label = { Text("歌手") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { doSearch() }) {
                    Text("搜索", style = MaterialTheme.typography.bodyLarge)
                }
                if (hasLocked) {
                    TextButton(onClick = {
                        MusicLyricSearch.Lock.clearLocked(songKey)
                        onReloadLyric()
                        Toast.makeText(context, "已清除锁定", Toast.LENGTH_SHORT).show()
                        onClose()
                    }) {
                        Text("清除锁定", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (searching) {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator()
            }
            if (searchDone && candidates.isEmpty() && !searching) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "没找到匹配的歌词，试试改歌名/歌手，或导入本地歌词",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            candidates.forEach { candidate ->
                Spacer(Modifier.height(8.dp))
                LyricCandidateRow(
                    candidate = candidate,
                    selected = selected == candidate,
                    onClick = { selected = candidate },
                )
            }
            if (selected != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = selected!!.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    val c = selected ?: return@TextButton
                    MusicLyricSearch.Lock.saveLocked(songKey, c.lrc, c.source)
                    onReloadLyric()
                    Toast.makeText(context, "已锁定歌词（" + c.source + "）", Toast.LENGTH_SHORT).show()
                    onClose()
                }) {
                    Text("应用", style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "导入歌词",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pasteText,
                onValueChange = { pasteText = it },
                label = { Text("粘贴 lrc 文本") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                minLines = 4,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = {
                    if (!importText(pasteText)) {
                        Toast.makeText(context, "没有读到有效歌词行", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    onReloadLyric()
                    Toast.makeText(context, "已导入歌词", Toast.LENGTH_SHORT).show()
                    onClose()
                }) {
                    Text("导入粘贴的歌词", style = MaterialTheme.typography.bodyLarge)
                }
                TextButton(onClick = { lrcLauncher.launch("*/*") }) {
                    Text("选择 .lrc 文件", style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 搜歌词候选行：歌名/歌手/来源/时长/逐词或逐行标记/前两行预览；选中态用主题主色底。 */
@Composable
private fun LyricCandidateRow(
    candidate: MusicLyricSearch.LyricCandidate,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceContainer,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = candidate.title + " - " + candidate.artist,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = candidate.source +
                (if (candidate.durationSec > 0) " · " + candidate.durationText else "") +
                " · " + if (candidate.wordTimed) "逐词" else "逐行",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (candidate.preview.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = candidate.preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SettingsChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

private val PlayButtonSizes = listOf(72.dp to 64.dp, 84.dp to 76.dp, 96.dp to 88.dp)
private val PlayButtonSizeNames = listOf("小", "中", "大")

// 呼吸方式四档（2.82）：跟音乐/慢呼吸/心跳/涟漪。长按涟漪环（按钮外圈）切换，存 KV。
private val BreathModeNames = listOf("跟音乐", "慢呼吸", "涟漪")
private val LyricTextColorNames = listOf("自动", "白色", "黑色", "主题色")
private val LyricGlowModeNames = listOf("自动", "柔和", "快速", "脉冲")

// 2.98 舞台钮：六款舞台模板的 id（演唱会 / 剧院 / 星空舞台 / 沙画 / 游子归家 / 武侠），点舞台钮按这个顺序循环
private val StageTemplateIds = listOf("yanchanghui", "juyuan", "xingkongwutai", "shahua", "youzi", "wuxia")
// 3.00：舞台名单独列——动效模板里不要舞台三款，舞台只在舞台弹窗里；LyricTemplateNames/Ids 从此不含舞台，舞台弹窗用这张名表
// 3.03：新增三款艺术的、讲故事的舞台——沙画 / 游子归家 / 武侠
private val StageTemplateNames = listOf("演唱会", "剧院", "星空舞台", "沙画", "游子归家", "武侠")

// 2.90 第 5 项 5A：词级动效模板——流光（默认）/ 青澜 / 星图 / 霓虹（2.91 替换第 4 款）/ 气泡 / 云阶 / 空间画布 / 雨天 / 四季 / 雾笺 / 町景 / 商籁（2.99 补 ICHIGOMUSIC 11 款里缺的 8 款）；3.00 起不含舞台三款（舞台只在舞台弹窗）
private val LyricTemplateNames = listOf("流光", "青澜", "星图", "霓虹", "气泡", "云阶", "空间画布", "雨天", "四季", "雾笺", "町景", "商籁")
private val LyricTemplateIds = listOf("liuguang", "qinglan", "xingtu", "nihong", "qipao", "yunjie", "kongjian", "xiayu", "siji", "wujian", "tingjing", "shanglai")

private val SkipToPlayGap = 20.dp

private val PlayToSkipGap = 22.dp

private val PlaybackControlsWidth = SkipButtonSize * 2 + PlayButtonWidth + SkipToPlayGap + PlayToSkipGap

@Composable
private fun MusicControls(
    playing: Boolean,
    buffering: Boolean,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    buttonSizeIndex: Int,
    breathMode: Int,
    onSizeChange: (Int) -> Unit,
    onBreathChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val visualizer = remember { MusicVisualizer() }
    var audioGranted by remember { mutableStateOf(PermissionHelper.isRecordAudioGranted(context)) }
    val activity = context as? Activity

    // 播放钮大小四档（2.82）：长按播放钮切换；呼吸方式四档（2.82）：长按涟漪环切换。状态在播放页，设置面板共用。
    val buttonSize = PlayButtonSizes[buttonSizeIndex.coerceIn(0, PlayButtonSizes.lastIndex)]
    val buttonWidth = buttonSize.first
    val buttonHeight = buttonSize.second
    val buttonBoxSize = buttonWidth + 48.dp
    val tipScope = rememberCoroutineScope()
    var tipHideJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    LaunchedEffect(playing) {
        if (playing && !audioGranted) {
            PermissionHelper.requestRecordAudioAuto(activity) { _, denied ->
                audioGranted = denied.isEmpty()
            }
        }
    }
    LaunchedEffect(playing, buffering, audioGranted) {
        if (playing && !buffering && audioGranted) visualizer.start() else visualizer.stop()
    }
    DisposableEffect(Unit) {
        onDispose { visualizer.stop() }
    }

    // 静默检测：Visualizer 建成功就置 available，哪怕采到的是静音（蓝牙/设备拦会话 0），
    // level 会一直卡 0，环不画、兜底又被 available 挡住，等于彻底静止。播着但连续 2 秒没声，
    // 就切固定呼吸，不再看 available。
    var silentForMs by remember { mutableStateOf(0) }
    LaunchedEffect(playing, buffering) {
        if (!playing || buffering) {
            silentForMs = 0
            return@LaunchedEffect
        }
        while (true) {
            delay(200)
            silentForMs = if (visualizer.available && visualizer.level <= 0.01f) silentForMs + 200 else 0
        }
    }
    val useFallback = !visualizer.available || silentForMs > 2000

    var fallbackLevel by remember { mutableStateOf(0.4f) }
    LaunchedEffect(useFallback, playing, buffering) {
        if (!useFallback || !playing || buffering) return@LaunchedEffect
        var t = 0f
        while (true) {
            delay(33)
            t += 0.033f
            fallbackLevel = 0.35f + 0.35f * sin(t * 2f * PI.toFloat() / 1.4f)
        }
    }

    val liveLevel = if (useFallback) fallbackLevel else visualizer.level
    val targetLevel = if (playing && !buffering) liveLevel else 0f
    val smoothLevel by animateFloatAsState(
        targetValue = targetLevel,
        animationSpec = tween(120),
        label = "playRippleLevel",
    )

    // 呼吸方式（2.82）：0 跟音乐（按钮跟着音量胀缩）、1 慢呼吸（3 秒一呼一吸，不跟音乐）、
    // 2 涟漪（1.8 秒一胀一缩）。2.84 起不画圈了，三档全是按钮自己放大缩小。2.85：心跳去掉了（用户 2026-11-13 06:50），只剩三档。
    var modeTime by remember { mutableStateOf(0f) }
    LaunchedEffect(breathMode, playing, buffering) {
        if (!playing || buffering || breathMode == 0) return@LaunchedEffect
        while (true) {
            delay(33)
            modeTime += 0.033f
        }
    }
    val breathActive = playing && !buffering
    val breathLevel = when (breathMode) {
        1 -> if (!breathActive) 0f else 0.5f + 0.5f * sin(modeTime * 2f * PI.toFloat() / 3f)
        2 -> if (!breathActive) 0f else 0.5f + 0.5f * sin(modeTime * 2f * PI.toFloat() / 1.8f)
        else -> smoothLevel
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScallopControlButton(
            iconRes = R.drawable.player_ic_prev,
            label = stringResource(R.string.music_previous),
            onClick = onPrevious,
            // 3.07 百变控件：上一曲钮。
            controlId = "music_prev",
        )
        Spacer(Modifier.width(SkipToPlayGap))
        Box(
            modifier = Modifier.size(buttonBoxSize)
                // 2.82：长按按钮外圈区域切换呼吸方式；长按按钮本身是换大小。点外圈不做事，不出水波纹。
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClick = {
                        val next = (breathMode + 1) % BreathModeNames.size
                        onBreathChange(next)
                        PlayerTipBridge.setTip("呼吸：" + BreathModeNames[next], false, false)
                        tipHideJob?.cancel()
                        tipHideJob = tipScope.launch {
                            delay(1500)
                            PlayerTipBridge.hide()
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            // 2.84：外圈去掉了，不画环了——呼吸就是按钮自己放大缩小，胀幅加大到 0.25 才看得出
            Box(
                modifier = Modifier
                    .size(width = buttonWidth, height = buttonHeight)
                    .graphicsLayer {
                        val scale = 1f + 0.25f * breathLevel
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(PlayButtonShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .combinedClickable(
                        onClick = onTogglePlay,
                        onLongClick = {
                            val next = (buttonSizeIndex + 1) % PlayButtonSizes.size
                            onSizeChange(next)
                            PlayerTipBridge.setTip("播放胶囊：" + PlayButtonSizeNames[next], false, false)
                            tipHideJob?.cancel()
                            tipHideJob = tipScope.launch {
                                delay(1500)
                                PlayerTipBridge.hide()
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (buffering) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(34.dp),
                    )
                } else {
                    Icon(
                        painter = painterResource(
                            if (playing) R.drawable.player_ic_pause else R.drawable.player_ic_play,
                        ),
                        contentDescription = stringResource(if (playing) R.string.common_pause else R.string.common_play),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(PlayToSkipGap))
        ScallopControlButton(
            iconRes = R.drawable.player_ic_next,
            label = stringResource(R.string.music_next),
            onClick = onNext,
            // 3.07 百变控件：下一曲钮。
            controlId = "music_next",
        )
    }
}

@Composable
private fun ScallopControlButton(
    @DrawableRes iconRes: Int,
    label: String,
    onClick: () -> Unit,
    // 3.07 百变控件：稳定元素 id（调用处写死，如 "music_prev"）。
    controlId: String,
) {
    // 3.07 第二阶段 C-edit：隐藏后不渲染（设置页可恢复）；上一曲 / 下一曲同进同出于此。
    if (ControlThemeStore.isElementHidden(controlId)) return
    Box(
        modifier = Modifier
            .size(SkipButtonSize)
            // 3.07 百变控件：combinedClickable 放尺寸之后、表面之前（点按区含表面内边距），
            // 长按调编辑，不吞点按；扇贝形状仍由内层 clip 保留。
            .combinedClickable(
                onClick = onClick,
                onLongClick = { ControlEditState.requestEdit(controlId) },
            )
            // 3.07：只套轻量外观（形状裁剪 + slate 淡底 + 强调色描边）。
            // 3.25：扇贝回来——surfaceBright 0.5 的扇贝底不再按总开关关掉，
            // 画在 controlButtonModifier 的胶囊底上面（clip 成扇贝形），
            // 就跟参考图一样（白扇贝在灰圆角方块上）；扇贝形底与圆角矩形底
            // 轮廓不同是正常的，参考图就是这样叠的。
            // 3.19：上一曲 / 下一曲描边恢复——与底栏同一道描边（controlButtonModifier 的 1dp
            // 边，颜色走胶囊描边颜色卡），仍在同一链里画；扇贝 clip 与底色不动。
            .then(controlButtonModifier(controlId))
            .clip(ScallopIconShape)
            .background(MaterialTheme.colorScheme.surfaceBright.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(28.dp),
        )
    }
}

private val CapsuleShape = RoundedCornerShape(percent = 50)

private val PlayButtonShape: Shape = EggShape()

private class EggShape(
    private val topControl: Float = 0.45f,
    private val bottomControl: Float = 0.60f,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val rx = w / 2f
        val path = Path().apply {
            moveTo(cx, 0f)
            cubicTo(cx + rx * topControl, 0f, cx + rx, h * 0.18f, cx + rx, h * 0.55f)
            cubicTo(cx + rx, h * 0.85f, cx + rx * bottomControl, h, cx, h)
            cubicTo(cx - rx * bottomControl, h, cx - rx, h * 0.85f, cx - rx, h * 0.55f)
            cubicTo(cx - rx, h * 0.18f, cx - rx * topControl, 0f, cx, 0f)
            close()
        }
        return Outline.Generic(path)
    }
}

private val ScallopIconShape: Shape = ScallopShape()

private val TWO_PI = (2f * PI).toFloat()

private const val WAVE_PERIOD_NANOS = 2_000_000_000L

private const val WAVE_FRAME_INTERVAL_NANOS = 1_000_000_000L / 30

@Composable
private fun MusicTipOverlay() {
    val tip = PlayerTipBridge.state
    if (tip.msg.isEmpty()) return
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (tip.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = tip.msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (tip.err) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private class WaveAmplitudeCache {
    private var seed = Int.MIN_VALUE
    private var count = -1
    private var values = FloatArray(0)

    fun get(count: Int, seed: Int): FloatArray {
        if (count != this.count || seed != this.seed) {
            this.count = count
            this.seed = seed
            values = FloatArray(count) { waveAmplitude(it, seed) }
        }
        return values
    }
}

private fun waveAmplitude(index: Int, seed: Int): Float {
    val fine = waveNoise(index, seed)
    val coarse = waveNoise(index / 5, seed + 17)
    return (0.35f + 0.65f * (0.6f * fine + 0.4f * coarse)).coerceIn(0.15f, 1f)
}

private fun waveNoise(value: Int, seed: Int): Float {
    var x = value * 374761393 + seed * 668265263
    x = (x xor (x shr 13)) * 1274126177
    x = x xor (x shr 16)
    return (x and 0x7FFFFFFF).toFloat() / 0x7FFFFFFF.toFloat()
}

internal fun lyricIndex(lines: List<LyricLine>, positionMs: Long): Int {
    if (lines.isEmpty()) return -1
    var low = 0
    var high = lines.size - 1
    var result = -1
    while (low <= high) {
        val mid = (low + high) / 2
        if (lines[mid].timeMs <= positionMs) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}

internal fun formatDuration(ms: Long): String {
    val safe = ms.coerceAtLeast(0L) / 1000
    val minute = safe / 60
    val second = safe % 60
    return "%d:%02d".format(minute, second)
}
