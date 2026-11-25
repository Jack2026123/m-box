package com.github.tvbox.osc.ui.activity

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ComposeView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import coil3.compose.AsyncImage
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.reading.LegadoChapterProvider
import com.github.tvbox.osc.reading.LegadoContent
import com.github.tvbox.osc.reading.ReadingStore
import com.github.tvbox.osc.ui.components.SheetHostScaffold
import com.github.tvbox.osc.ui.page.ReadingTopBar
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Audio-book playback for Legado sources (chapter = one episode).
 *
 * Take note: the vod detail play chain and MusicPlayerActivity both
 * require a spider-backed VodInfo in the shared playback service, which
 * a Legado chapter URL is not; this page therefore drives the app's own
 * media3 ExoPlayer kernel directly with prev/next = adjacent chapters.
 */
class LegadoAudioActivity : BaseActivity() {

    companion object {
        private const val EXTRA_BOOK_KEY = "legado_book_key"
        private const val EXTRA_INDEX = "legado_index"

        fun start(context: Context, bookKey: String, index: Int) {
            val intent = Intent(context, LegadoAudioActivity::class.java)
            intent.putExtra(EXTRA_BOOK_KEY, bookKey)
            intent.putExtra(EXTRA_INDEX, index)
            context.startActivity(intent)
        }
    }

    override fun shouldRefreshAutoSize(): Boolean = true

    // No hideSysBar() override: BaseActivity's default immersive
    // behaviour applies (system bars hidden, like the readers).
    override fun init() {
        enableTransparentEdgeToEdge()
        val bookKey = intent.getStringExtra(EXTRA_BOOK_KEY).orEmpty()
        val startIndex = intent.getIntExtra(EXTRA_INDEX, 0)
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                SheetHostScaffold {
                    LegadoAudioScreen(
                        bookKey = bookKey,
                        startIndex = startIndex,
                        onBack = { finish() },
                    )
                }
            }
        }
    }
}

@Composable
private fun LegadoAudioScreen(bookKey: String, startIndex: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entry = remember(bookKey) { LegadoChapterProvider.entry(bookKey) }
    if (entry == null) {
        Column(modifier = Modifier.fillMaxSize()) {
            ReadingTopBar("", onBack)
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.reading_load_failed, ""))
            }
        }
        return
    }
    val book = entry.book
    val chapters = entry.chapters

    val player = remember {
        ExoPlayer.Builder(context).build()
    }
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, maxOf(0, chapters.size - 1))) }
    var loading by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadChapter(target: Int, autoplay: Boolean = true) {
        val chapter = chapters.getOrNull(target) ?: return
        index = target
        loading = true
        error = null
        ReadingStore.updateProgress(bookKey, target, chapter.name)
        scope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching { entry.webBook.loadContent(book, chapter) }.getOrNull()
            }
            val audio = content as? LegadoContent.Audio
            if (audio == null) {
                loading = false
                error = content?.javaClass?.simpleName ?: "empty"
                return@launch
            }
            val factory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
            if (audio.headers.isNotEmpty()) {
                factory.setDefaultRequestProperties(audio.headers)
            }
            val mediaSource = ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(audio.url))
            player.setMediaSource(mediaSource)
            player.prepare()
            player.playWhenReady = autoplay
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        loading = false
                        durationMs = player.duration.coerceAtLeast(0L)
                    }
                    Player.STATE_ENDED -> {
                        if (index < chapters.size - 1) loadChapter(index + 1)
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                loading = false
                error = e.message ?: e.errorCodeName
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(bookKey) { loadChapter(index) }

    LaunchedEffect(playing) {
        while (playing) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ReadingTopBar(book.name, onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                if (book.coverUrl.isNotBlank()) {
                    AsyncImage(
                        model = book.coverUrl,
                        contentDescription = book.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = chapters.getOrNull(index)?.name.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (book.author.isNotBlank()) {
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            if (loading) {
                CircularProgressIndicator()
            } else if (error != null) {
                Text(
                    text = stringResource(R.string.reading_load_failed, error ?: ""),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Slider(
                value = if (durationMs > 0) {
                    (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                },
                onValueChange = { fraction ->
                    if (durationMs > 0) {
                        player.seekTo((fraction * durationMs).toLong())
                        positionMs = player.currentPosition
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = formatTime(positionMs),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatTime(durationMs),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = { if (index > 0) loadChapter(index - 1) },
                    enabled = index > 0,
                ) { Text(stringResource(R.string.reading_audio_prev)) }
                Spacer(Modifier.width(16.dp))
                Button(
                    onClick = {
                        if (player.isPlaying) player.pause() else player.play()
                    },
                    modifier = Modifier.size(64.dp),
                    shape = RoundedCornerShape(50),
                ) {
                    Icon(
                        painter = painterResource(
                            if (playing) R.drawable.player_ic_pause else R.drawable.player_ic_play,
                        ),
                        contentDescription = null,
                    )
                }
                Spacer(Modifier.width(16.dp))
                OutlinedButton(
                    onClick = { if (index < chapters.size - 1) loadChapter(index + 1) },
                    enabled = index < chapters.size - 1,
                ) { Text(stringResource(R.string.reading_audio_next)) }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
