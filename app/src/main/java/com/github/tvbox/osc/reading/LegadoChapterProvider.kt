package com.github.tvbox.osc.reading

import com.github.tvbox.osc.reader.ReaderContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Bridges Legado books into the existing readers.
 *
 * When a text/image book is opened, its (source, book, chapters) triple
 * is registered here under the bookKey; a ReaderSession carrying that
 * key routes its chapter loads to [loadChapterContent] instead of the
 * spider pipeline. One [WebBook] per book keeps the @put variable state
 * alive across chapters, exactly like Legado's session model.
 */
object LegadoChapterProvider {

    class Entry(
        val source: BookSource,
        val book: LegadoBook,
        val chapters: List<LegadoChapter>,
    ) {
        val webBook: WebBook = WebBook(source)
        val mutex = Mutex()
    }

    private val entries = HashMap<String, Entry>()

    @Synchronized
    fun register(bookKey: String, source: BookSource, book: LegadoBook, chapters: List<LegadoChapter>) {
        entries[bookKey] = Entry(source, book, chapters)
    }

    @Synchronized
    fun entry(bookKey: String): Entry? = entries[bookKey]

    @Synchronized
    fun clear() {
        entries.clear()
    }

    suspend fun loadChapterContent(bookKey: String, index: Int): ReaderContent? {
        val entry = entry(bookKey) ?: return null
        val chapter = entry.chapters.getOrNull(index) ?: return null
        return withContext(Dispatchers.IO) {
            entry.mutex.withLock {
                runCatching { entry.webBook.loadContent(entry.book, chapter) }
                    .getOrNull()
                    ?.let { content ->
                        when (content) {
                            is LegadoContent.Text -> ReaderContent.Novel(
                                inlineText = content.body,
                                textUrl = null,
                                headers = emptyMap(),
                            )
                            is LegadoContent.Images -> {
                                val decodeJs = content.imageDecodeJs.ifBlank {
                                    entry.source.contentRule("imageDecode")
                                }
                                LegadoImageDecode.register(entry.source.sourceKey, decodeJs)
                                val baseHeaders = content.headers.ifEmpty {
                                    val base = entry.source.bookSourceUrl.trimEnd('/')
                                    if (base.startsWith("http")) mapOf("Referer" to "$base/") else emptyMap()
                                }
                                val headers = if (decodeJs.isNotBlank()) {
                                    baseHeaders + mapOf("X-Legado-Source-Key" to entry.source.sourceKey)
                                } else baseHeaders
                                ReaderContent.Comic(images = content.urls, headers = headers)
                            }
                            is LegadoContent.Audio -> ReaderContent.Novel(
                                inlineText = "",
                                textUrl = null,
                                headers = emptyMap(),
                            )
                        }
                    }
            }
        }
    }
}
