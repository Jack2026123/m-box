package com.github.tvbox.osc.miniapp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniAppGridRowTest {

    private fun item(type: String): MiniAppListItem {
        val item = MiniAppListItem()
        item.type = type
        return item
    }

    private fun itemWithUrl(type: String, url: String): MiniAppListItem {
        val item = MiniAppListItem()
        item.type = type
        item.url = url
        return item
    }

    @Test
    fun duplicateUrlKeepsLastOccurrence() {
        val rows = groupIntoRows(
            listOf(
                itemWithUrl("movie_1", "hiker://same"),
                itemWithUrl("movie_3", "hiker://same"),
                itemWithUrl("movie_3", "hiker://other"),
            ),
        )
        assertEquals(1, rows.size)
        assertEquals(2, rows[0].size)
        assertEquals("hiker://same", rows[0][0].first.url)
        assertEquals(20, rows[0][0].second)
    }

    @Test
    fun emptyUrlItemsAreNeverDeduped() {
        val rows = groupIntoRows(listOf(item("text_1"), item("text_1")))
        assertEquals(2, rows.size)
    }

    @Test
    fun fullWidthItemsEachOwnRow() {
        val rows = groupIntoRows(listOf(item("text_1"), item("movie_1"), item("text_1")))
        assertEquals(3, rows.size)
        assertEquals(60, rows[0][0].second)
        assertEquals(60, rows[1][0].second)
        assertEquals(60, rows[2][0].second)
    }

    @Test
    fun threeSpan20ItemsShareOneRow() {
        val rows = groupIntoRows(listOf(item("movie_3"), item("movie_3"), item("movie_3")))
        assertEquals(1, rows.size)
        assertEquals(3, rows[0].size)
        assertEquals(60, rows[0].sumOf { it.second })
    }

    @Test
    fun overflowStartsNewRow() {
        val rows = groupIntoRows(
            listOf(item("movie_3"), item("movie_3"), item("movie_3"), item("movie_3")),
        )
        assertEquals(2, rows.size)
        assertEquals(3, rows[0].size)
        assertEquals(1, rows[1].size)
    }

    @Test
    fun emptyOrUnknownTypeDefaultsToMovie3Span20() {
        val rows = groupIntoRows(listOf(item(""), item("no_such_type")))
        assertEquals(1, rows.size)
        assertEquals(20, rows[0][0].second)
        assertEquals(20, rows[0][1].second)
    }

    @Test
    fun mixedSpansFillToSixty() {
        val rows = groupIntoRows(listOf(item("movie_2"), item("movie_2"), item("text_1")))
        assertEquals(2, rows.size)
        assertEquals(2, rows[0].size)
        assertEquals(60, rows[0].sumOf { it.second })
        assertEquals(1, rows[1].size)
    }

    private fun chipItem(type: String, title: String, desc: String = ""): MiniAppListItem {
        val item = MiniAppListItem()
        item.type = type
        item.title = title
        item.desc = desc
        return item
    }

    @Test
    fun headerItemsAggregateIntoOneChipRow() {
        val blocks = groupIntoBlocks(
            listOf(
                chipItem("header", "全部", "class"),
                chipItem("header", "动作片", "class"),
                chipItem("header", "喜剧片", "class"),
            ),
        )
        assertEquals(1, blocks.size)
        val chips = blocks[0] as MiniAppBlock.Chips
        assertEquals(3, chips.items.size)
    }

    @Test
    fun allBoundarySplitsTextChipsIntoTwoRows() {
        val blocks = groupIntoBlocks(
            listOf(
                chipItem("text_1", "全部"),
                chipItem("text_1", "动作片"),
                chipItem("text_1", "全部"),
                chipItem("text_1", "2026"),
            ),
        )
        assertEquals(2, blocks.size)
        assertEquals(2, (blocks[0] as MiniAppBlock.Chips).items.size)
        assertEquals(2, (blocks[1] as MiniAppBlock.Chips).items.size)
    }

    @Test
    fun chipRowsAndGridKeepOrder() {
        val blocks = groupIntoBlocks(
            listOf(
                chipItem("header", "全部", "class"),
                chipItem("header", "动作片", "class"),
                item("movie_3"),
                item("movie_3"),
            ),
        )
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MiniAppBlock.Chips)
        assertTrue(blocks[1] is MiniAppBlock.Grid)
        assertEquals(1, (blocks[1] as MiniAppBlock.Grid).rows.size)
        assertEquals(2, (blocks[1] as MiniAppBlock.Grid).rows[0].size)
    }

    @Test
    fun longTextStaysGrid() {
        val long = chipItem("text_1", "输入影片名或演员名，也可直接搜年份")
        val blocks = groupIntoBlocks(listOf(long))
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MiniAppBlock.Grid)
    }

    @Test
    fun tabRowItemStandsAlone() {
        val tab = MiniAppListItem()
        tab.type = "header"
        tab.title = "全部&动作片"
        tab.url = "all&action"
        tab.desc = "class"
        val blocks = groupIntoBlocks(
            listOf(chipItem("header", "全部", "class"), tab),
        )
        assertEquals(2, blocks.size)
        assertEquals(1, (blocks[1] as MiniAppBlock.Chips).items.size)
    }
}
