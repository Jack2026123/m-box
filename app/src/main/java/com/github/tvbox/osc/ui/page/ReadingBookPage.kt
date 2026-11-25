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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.github.tvbox.osc.R
import com.github.tvbox.osc.reader.ComicReaderActivity
import com.github.tvbox.osc.reader.NovelReaderActivity
import com.github.tvbox.osc.reader.ReaderContent
import com.github.tvbox.osc.reader.ReaderEpisode
import com.github.tvbox.osc.reader.ReaderSession
import com.github.tvbox.osc.reader.ReaderSessions
import com.github.tvbox.osc.reader.ReaderStore
import com.github.tvbox.osc.reader.WebComicReaderActivity
import com.github.tvbox.osc.reader.WebNovelReaderActivity
import com.github.tvbox.osc.reading.BookSource
import com.github.tvbox.osc.reading.LegadoBook
import com.github.tvbox.osc.reading.LegadoChapter
import com.github.tvbox.osc.reading.LegadoChapterProvider
import com.github.tvbox.osc.reading.ReadingStore
import com.github.tvbox.osc.reading.WebBook
import com.github.tvbox.osc.ui.activity.LegadoAudioActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Book detail: info header + full chapter list. Opening a chapter
 * registers the (source, book, chapters) triple with
 * [LegadoChapterProvider] and routes by content kind — audio sources go
 * straight to the audio page, text/image chapters are classified by
 * their first fetched body and handed to the existing readers.
 */
@Composable
fun ReadingBookScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val initialBook = remember { ReadingNavigator.pendingBook }
    val source = remember(initialBook) {
        initialBook?.let { ReadingNavigator.sourceFor(it) }
    }
    if (initialBook == null || source == null) {
        Column(modifier = Modifier.fillMaxSize()) {
            ReadingTopBar("", onBack)
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.reading_load_failed, ""))
            }
        }
        return
    }
    BookDetailContent(book = initialBook, source = source, onBack = onBack)
}

@Composable
private fun BookDetailContent(book: LegadoBook, source: BookSource, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var displayBook by remember { mutableStateOf(book) }
    var chapters by remember { mutableStateOf<List<LegadoChapter>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reversed by remember { mutableStateOf(false) }
    var openingIndex by remember { mutableStateOf(-1) }
    val shelf by ReadingStore.shelf.collectAsState()
    val onShelf = shelf.any { it.book.bookKey == book.bookKey }
    val savedIndex = shelf.firstOrNull { it.book.bookKey == book.bookKey }?.chapterIndex ?: 0

    LaunchedEffect(book.bookKey) {
        loading = true
        error = null
        try {
            val webBook = WebBook(source)
            val (loadedBook, loadedChapters) = withContext(Dispatchers.IO) {
                val working = book.copy()
                webBook.loadBookInfo(working)
                val toc = webBook.loadToc(working)
                working to toc
            }
            displayBook = loadedBook
            chapters = loadedChapters
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
        loading = false
    }

    fun openChapter(index: Int) {
        if (openingIndex >= 0 || chapters.isEmpty()) return
        openingIndex = index
        ReadingStore.addToShelf(displayBook)
        ReadingStore.updateProgress(
            displayBook.bookKey,
            index,
            chapters.getOrNull(index)?.name.orEmpty(),
        )
        LegadoChapterProvider.register(displayBook.bookKey, source, displayBook, chapters)
        if (source.bookSourceType == 1) {
            openingIndex = -1
            LegadoAudioActivity.start(context, displayBook.bookKey, index)
            return
        }
        scope.launch {
            val content = withContext(Dispatchers.IO) {
                LegadoChapterProvider.loadChapterContent(displayBook.bookKey, index)
            }
            openingIndex = -1
            when (content) {
                is ReaderContent.Novel -> {
                    val token = UUID.randomUUID().toString()
                    ReaderSessions.put(
                        ReaderSession(
                            token = token,
                            sourceKey = displayBook.sourceKey,
                            vodId = displayBook.bookUrl,
                            title = displayBook.name,
                            picture = displayBook.coverUrl.ifBlank { null },
                            playFlag = null,
                            episodes = chapters.map { ReaderEpisode(it.name, it.url) },
                            startIndex = index,
                            firstContent = content,
                            firstResult = null,
                            legadoBookKey = displayBook.bookKey,
                        ),
                    )
                    if (ReaderStore.useWebReader()) {
                        WebNovelReaderActivity.start(context, token)
                    } else {
                        NovelReaderActivity.start(context, token)
                    }
                }
                is ReaderContent.Comic -> {
                    val token = UUID.randomUUID().toString()
                    ReaderSessions.put(
                        ReaderSession(
                            token = token,
                            sourceKey = displayBook.sourceKey,
                            vodId = displayBook.bookUrl,
                            title = displayBook.name,
                            picture = displayBook.coverUrl.ifBlank { null },
                            playFlag = null,
                            episodes = chapters.map { ReaderEpisode(it.name, it.url) },
                            startIndex = index,
                            firstContent = content,
                            firstResult = null,
                            legadoBookKey = displayBook.bookKey,
                        ),
                    )
                    if (ReaderStore.useWebReader()) {
                        WebComicReaderActivity.start(context, token)
                    } else {
                        ComicReaderActivity.start(context, token)
                    }
                }
                else -> {
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.reading_load_failed,
                            content?.javaClass?.simpleName ?: "empty",
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ReadingTopBar(displayBook.name, onBack)
        Row(modifier = Modifier.padding(horizontal = 16.dp)) {
            Box(
                modifier = Modifier
                    .size(width = 92.dp, height = 124.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                if (displayBook.coverUrl.isNotBlank()) {
                    AsyncImage(
                        model = displayBook.coverUrl,
                        contentDescription = displayBook.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayBook.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (displayBook.author.isNotBlank()) {
                    Text(
                        text = displayBook.author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.reading_from_source, displayBook.sourceName),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(
                        onClick = {
                            openChapter(savedIndex.coerceIn(0, maxOf(0, chapters.size - 1)))
                        },
                        enabled = !loading && chapters.isNotEmpty() && openingIndex < 0,
                    ) {
                        Text(
                            stringResource(
                                if (source.bookSourceType == 1) {
                                    R.string.reading_listen
                                } else if (onShelf && savedIndex > 0) {
                                    R.string.reading_continue
                                } else {
                                    R.string.reading_start
                                },
                            ),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            if (onShelf) {
                                ReadingStore.removeFromShelf(displayBook.bookKey)
                            } else {
                                ReadingStore.addToShelf(displayBook)
                            }
                        },
                    ) {
                        Text(
                            stringResource(
                                if (onShelf) R.string.reading_in_shelf
                                else R.string.reading_add_shelf,
                            ),
                        )
                    }
                }
            }
        }
        if (displayBook.intro.isNotBlank()) {
            Text(
                text = displayBook.intro,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.reading_chapters) +
                    if (chapters.isNotEmpty()) {
                        " · " + stringResource(R.string.reading_chapter_count, chapters.size)
                    } else {
                        ""
                    },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { reversed = !reversed }) {
                Text(stringResource(R.string.reading_reverse))
            }
        }
        when {
            loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            error != null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.reading_load_failed, error ?: ""),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            else -> {
                val shown = if (reversed) chapters.reversed() else chapters
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(shown, key = { it.index }) { chapter ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = openingIndex < 0) {
                                    openChapter(chapter.index)
                                },
                            shape = RoundedCornerShape(10.dp),
                            color = if (chapter.index == savedIndex && onShelf) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = chapter.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (openingIndex == chapter.index) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
