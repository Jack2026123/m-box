package com.github.tvbox.osc.ui.page

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.github.tvbox.osc.R
import com.github.tvbox.osc.reading.BookSource
import com.github.tvbox.osc.reading.LegadoBook
import com.github.tvbox.osc.reading.ReadingStore
import com.github.tvbox.osc.reading.WebBook
import com.github.tvbox.osc.ui.activity.ReadingBookActivity
import com.github.tvbox.osc.ui.activity.ReadingSearchActivity
import com.github.tvbox.osc.ui.activity.ReadingSourcesActivity
import com.github.tvbox.osc.ui.components.wallpaperAwareContainerColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/** Hands a book to the detail page across activities (in-process). */
object ReadingNavigator {
    var pendingBook: LegadoBook? = null

    fun openBook(context: Context, book: LegadoBook) {
        pendingBook = book
        ReadingBookActivity.start(context)
    }

    fun sourceFor(book: LegadoBook): BookSource? {
        for (sub in ReadingStore.subscriptions.value) {
            val found = ReadingStore.packSources(sub.url)
                .firstOrNull { it.sourceKey == book.sourceKey }
            if (found != null) return found
        }
        return null
    }
}

@Composable
fun ReadingTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                androidx.compose.foundation.layout.WindowInsets
                    .statusBarsIgnoringVisibility,
            )
            .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_left),
                contentDescription = stringResource(R.string.common_back),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ------------------------------------------------------------ home (shelf)

@Composable
fun ReadingHomePage(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val shelf by ReadingStore.shelf.collectAsState()
    val activeUrl by ReadingStore.activeUrl.collectAsState()
    val activeName = ReadingStore.subscriptions.collectAsState().value
        .firstOrNull { it.url == activeUrl }?.name.orEmpty()
    var removeTarget by remember { mutableStateOf<ReadingStore.ShelfEntry?>(null) }

    // The status bar stays hidden on reading pages, but the title row
    // reserves its height anyway so the shelf title sits exactly where
    // a normal page's top-bar title sits (status height + standard pad).
    val statusTop = with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.foundation.layout.WindowInsets
            .statusBarsIgnoringVisibility.getTop(this).toDp()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(statusTop + 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.reading_shelf),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { ReadingSearchActivity.start(context) },
                modifier = Modifier.height(48.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.reading_search))
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { ReadingSourcesActivity.start(context) },
                modifier = Modifier.height(48.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.reading_sources))
            }
        }
        if (activeName.isNotEmpty()) {
            Text(
                text = activeName + " · " + stringResource(R.string.reading_exit_mode_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        if (shelf.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.reading_shelf_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shelf, key = { it.book.bookKey }) { entry ->
                    ShelfBookCell(
                        entry = entry,
                        onClick = { ReadingNavigator.openBook(context, entry.book) },
                        onLongClick = { removeTarget = entry },
                    )
                }
            }
        }
    }

    removeTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(entry.book.name) },
            text = { Text(stringResource(R.string.reading_remove_shelf)) },
            confirmButton = {
                TextButton(onClick = {
                    ReadingStore.removeFromShelf(entry.book.bookKey)
                    removeTarget = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ShelfBookCell(
    entry: ReadingStore.ShelfEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            if (entry.book.coverUrl.isNotBlank()) {
                AsyncImage(
                    model = entry.book.coverUrl,
                    contentDescription = entry.book.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            text = entry.book.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (entry.chapterName.isNotBlank()) {
            Text(
                text = stringResource(R.string.reading_progress_at, entry.chapterName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Subtitle for a source row: type, then group, then the author comment —
 * but only when the comment is presentable. Exported packs sometimes
 * carry tool error residue in bookSourceComment (e.g. a leading
 * "// Error: ..." line); those are dropped so raw error strings never
 * reach the UI. Comment text is truncated to its first line.
 */
private fun sourceSubtitle(typeText: String, group: String, comment: String): String {
    val groupText = group.split(',', '，')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" · ")
    val commentText = comment.lineSequence()
        .firstOrNull()
        ?.trim()
        ?.removePrefix("//")
        ?.trim()
        .orEmpty()
    val cleanComment = when {
        commentText.isEmpty() -> ""
        commentText.contains("error", ignoreCase = true) -> ""
        commentText.contains("exception", ignoreCase = true) -> ""
        commentText.contains("timed out", ignoreCase = true) -> ""
        else -> commentText
    }
    return listOf(typeText, groupText, cleanComment)
        .filter { it.isNotEmpty() }
        .joinToString(" · ")
}

// ------------------------------------------------------------ search

@Composable
fun ReadingSearchScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var keyword by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<LegadoBook>>(emptyList()) }
    var failedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var noSources by remember { mutableStateOf(false) }
    var singleMode by remember { mutableStateOf(false) }
    var pickedKey by remember { mutableStateOf(ReadingStore.singleSourceKey()) }
    var showPicker by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var history by remember { mutableStateOf(ReadingStore.searchHistory()) }

    fun runSearch(keyOverride: String? = null) {
        val key = (keyOverride ?: keyword).trim()
        if (key.isEmpty()) return
        val picked = if (singleMode) {
            ReadingStore.activeSources().firstOrNull { it.sourceKey == pickedKey }
        } else {
            null
        }
        val sources = when {
            !singleMode -> ReadingStore.activeSources()
            picked != null -> listOf(picked)
            else -> {
                showPicker = true
                return
            }
        }
        searchJob?.cancel()
        noSources = sources.isEmpty()
        if (sources.isEmpty()) {
            results = emptyList()
            failedKeys = emptySet()
            searched = true
            searching = false
            done = true
            return
        }
        ReadingStore.pushSearchHistory(key)
        history = ReadingStore.searchHistory()
        results = emptyList()
        failedKeys = emptySet()
        searched = true
        searching = true
        done = false
        searchJob = scope.launch {
            WebBook.searchStream(sources, key)
                .flowOn(Dispatchers.IO)
                .collect { event ->
                    when (event) {
                        is WebBook.SearchStreamEvent.Batch ->
                            results = results + event.books
                        is WebBook.SearchStreamEvent.Failed ->
                            failedKeys = failedKeys + event.sourceKey
                        is WebBook.SearchStreamEvent.Done -> {
                            searching = false
                            done = true
                        }
                    }
                }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ReadingTopBar(stringResource(R.string.reading_search), onBack)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                placeholder = { Text(stringResource(R.string.reading_search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { runSearch() }) {
                Text(stringResource(R.string.common_search))
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(
                checked = singleMode,
                onCheckedChange = { on ->
                    singleMode = on
                    if (on && ReadingStore.activeSources()
                            .firstOrNull { it.sourceKey == pickedKey } == null
                    ) {
                        showPicker = true
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.reading_search_single_source))
            if (singleMode) {
                Spacer(Modifier.width(8.dp))
                val pickedName = ReadingStore.activeSources()
                    .firstOrNull { it.sourceKey == pickedKey }?.bookSourceName
                TextButton(onClick = { showPicker = true }) {
                    Text(pickedName ?: stringResource(R.string.reading_search_pick_source))
                }
            }
        }
        if (keyword.isBlank() && history.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(history) { h ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            keyword = h
                            runSearch(h)
                        },
                        label = { Text(h) },
                    )
                }
            }
        }
        if (searched) {
            if (noSources) {
                SearchNote(stringResource(R.string.reading_no_sources))
            } else if (results.isEmpty() && searching) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (results.isEmpty()) {
                SearchNote(stringResource(R.string.reading_no_results))
            } else {
                if (failedKeys.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.reading_search_failed_sources,
                            failedKeys.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(results, key = { it.bookKey }) { book ->
                        SearchResultRow(book = book) {
                            ReadingNavigator.openBook(context, book)
                        }
                    }
                }
                if (searching) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.reading_search_still_searching),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (done && failedKeys.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            failedKeys.forEach { ReadingStore.setSourceEnabled(it, false) }
                            failedKeys = emptySet()
                            Toast.makeText(
                                context,
                                context.getString(R.string.reading_search_failed_disabled),
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        Text(stringResource(R.string.reading_search_disable_failed))
                    }
                }
            }
        }
    }

    if (showPicker) {
        val pickerSources = remember(showPicker) { ReadingStore.activeSources() }
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(stringResource(R.string.reading_search_pick_source)) },
            text = {
                if (pickerSources.isEmpty()) {
                    Text(stringResource(R.string.reading_no_sources))
                } else {
                    LazyColumn(modifier = Modifier.height(320.dp)) {
                        items(pickerSources, key = { it.sourceKey }) { source ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        pickedKey = source.sourceKey
                                        ReadingStore.setSingleSourceKey(source.sourceKey)
                                        showPicker = false
                                    }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(
                                    text = source.bookSourceName,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = sourceSubtitle(
                                        typeText = stringResource(
                                            when (source.bookSourceType) {
                                                1 -> R.string.reading_source_type_audio
                                                2 -> R.string.reading_source_type_comic
                                                else -> R.string.reading_source_type_text
                                            },
                                        ),
                                        group = source.bookSourceGroup,
                                        comment = source.bookSourceComment,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun SearchNote(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SearchResultRow(book: LegadoBook, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        // logtest43: result row follows the 透明背景 slider.
        color = wallpaperAwareContainerColor(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .size(width = 52.dp, height = 70.dp)
                    .clip(RoundedCornerShape(8.dp))
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
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (book.author.isNotBlank()) {
                    Text(
                        text = book.author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (book.lastChapter.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.reading_last_chapter, book.lastChapter),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(R.string.reading_from_source, book.sourceName),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ------------------------------------------------------------ source management

@Composable
fun ReadingSourcesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var deleteTarget by remember { mutableStateOf<BookSource?>(null) }
    val sources = remember(tick) { ReadingStore.allActivePackSources() }
    val activeUrl by ReadingStore.activeUrl.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        ReadingTopBar(stringResource(R.string.reading_sources), onBack)
        if (sources.isEmpty()) {
            SearchNote(stringResource(R.string.reading_no_sources))
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(sources, key = { it.sourceKey }) { source ->
                val enabled = ReadingStore.isSourceEnabled(source.sourceKey)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    // logtest43: source row follows the 透明背景 slider.
                    color = wallpaperAwareContainerColor(MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = source.bookSourceName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = sourceSubtitle(
                                    typeText = stringResource(
                                        when (source.bookSourceType) {
                                            1 -> R.string.reading_source_type_audio
                                            2 -> R.string.reading_source_type_comic
                                            else -> R.string.reading_source_type_text
                                        },
                                    ),
                                    group = source.bookSourceGroup,
                                    comment = source.bookSourceComment,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { on ->
                                ReadingStore.setSourceEnabled(source.sourceKey, on)
                                tick++
                            },
                        )
                        IconButton(onClick = { deleteTarget = source }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = stringResource(R.string.reading_delete_source),
                            )
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { source ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(source.bookSourceName) },
            text = { Text(stringResource(R.string.reading_delete_source)) },
            confirmButton = {
                TextButton(onClick = {
                    if (activeUrl.isNotEmpty()) {
                        ReadingStore.removeSourceFromPack(activeUrl, source.sourceKey)
                    }
                    deleteTarget = null
                    tick++
                    Toast.makeText(
                        context,
                        context.getString(R.string.common_delete),
                        Toast.LENGTH_SHORT,
                    ).show()
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}
