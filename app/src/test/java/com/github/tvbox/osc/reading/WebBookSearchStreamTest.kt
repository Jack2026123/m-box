package com.github.tvbox.osc.reading

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Streaming search (reading search speedup): batches must arrive per
 * source instead of waiting for the slowest source, one failing source
 * must not sink the rest, and the final Done carries the failure map.
 * All sources here are synthetic fixtures; no real source content.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = NoBootApplication::class)
class WebBookSearchStreamTest {

    private fun fakeSource(name: String): BookSource =
        BookSource(
            JSONObject()
                .put("bookSourceName", name)
                .put("bookSourceUrl", "https://" + name + ".example"),
        )

    private fun fakeBook(source: BookSource, title: String): LegadoBook =
        LegadoBook(
            sourceKey = source.sourceKey,
            sourceName = source.bookSourceName,
            sourceType = 0,
            bookUrl = "https://" + source.bookSourceName + ".example/" + title,
            name = title,
        )

    @Test
    fun stream_emitsBatchesOnArrival_failureDoesNotSinkOthers() = runBlocking {
        val fast = fakeSource("fast")
        val fail = fakeSource("fail")
        val slow = fakeSource("slow")
        val events = withTimeout(10_000) {
            WebBook.searchStreamWith(listOf(fast, fail, slow)) { src ->
                when (src.bookSourceName) {
                    "fail" -> {
                        delay(150)
                        throw RuntimeException("boom")
                    }
                    "slow" -> {
                        delay(300)
                        listOf(fakeBook(src, "Slow Book"))
                    }
                    else -> listOf(fakeBook(src, "Fast Book"))
                }
            }.toList()
        }
        assertEquals(4, events.size)
        val first = events[0] as WebBook.SearchStreamEvent.Batch
        assertEquals("fast", first.sourceName)
        assertEquals("https://fast.example", first.sourceKey)
        assertEquals(listOf("Fast Book"), first.books.map { it.name })
        val second = events[1] as WebBook.SearchStreamEvent.Failed
        assertEquals("fail", second.sourceName)
        assertEquals("boom", second.error)
        val third = events[2] as WebBook.SearchStreamEvent.Batch
        assertEquals("slow", third.sourceName)
        val done = events[3] as WebBook.SearchStreamEvent.Done
        assertEquals(mapOf("fail" to "boom"), done.failures)
    }

    @Test
    fun stream_emptySources_onlyDone() = runBlocking {
        val events = withTimeout(10_000) {
            WebBook.searchStreamWith(emptyList()) { emptyList() }.toList()
        }
        assertEquals(1, events.size)
        assertEquals(
            emptyMap<String, String>(),
            (events[0] as WebBook.SearchStreamEvent.Done).failures,
        )
    }
}
