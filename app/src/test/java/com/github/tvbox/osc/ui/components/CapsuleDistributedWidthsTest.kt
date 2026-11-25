package com.github.tvbox.osc.ui.components

import androidx.compose.ui.unit.Constraints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Measure-contract tests for the distributeLeftover tab row: the
 * reported row width must never exceed the incoming max width. The
 * overflow case is the one that crashed config-manage on first layout
 * in logtest36-38 (row reported maxOf(maxWidth, contentTotal)).
 */
class CapsuleDistributedWidthsTest {

    @Test
    fun narrowContent_leftoverSharedEquallyAndSumsExactly() {
        val gap = 8
        val content = listOf(100, 120, 80, 100)
        val (targets, row) = computeDistributedWidths(content, gap, maxWidth = 1000)
        val leftover = 1000 - (content.sum() + gap * (content.size - 1))
        val share = leftover / content.size
        assertEquals(content.map { it + share }, targets)
        assertEquals(1000, targets.sum() + gap * (content.size - 1))
        assertEquals(1000, row)
    }

    @Test
    fun narrowContent_divisionRemainderGoesToLastChild() {
        val gap = 8
        val content = listOf(100, 120, 80, 100)
        // leftover = 1001 - 424 = 577 = 4 * 144 + 1
        val (targets, row) = computeDistributedWidths(content, gap, maxWidth = 1001)
        assertEquals(listOf(244, 264, 224, 245), targets)
        assertEquals(1001, targets.sum() + gap * (content.size - 1))
        assertEquals(1001, row)
    }

    @Test
    fun overflowingContent_shrunkProportionallyAndRowNeverExceedsMax() {
        val gap = 8
        // The crash case: hug widths sum to 1176, +24 gaps = 1200 > 1000.
        val content = listOf(294, 294, 294, 294)
        val (targets, row) = computeDistributedWidths(content, gap, maxWidth = 1000)
        assertEquals(1000, targets.sum() + gap * (content.size - 1))
        assertEquals(1000, row)
        targets.forEachIndexed { index, target ->
            assertTrue("target $target must not exceed content ${content[index]}", target <= content[index])
            assertTrue("target $target must stay non-negative", target >= 0)
        }
    }

    @Test
    fun overflowingUnevenContent_keepsProportionsAndExactSum() {
        val gap = 10
        val content = listOf(500, 300, 200, 100)
        val (targets, row) = computeDistributedWidths(content, gap, maxWidth = 640)
        assertEquals(640, targets.sum() + gap * (content.size - 1))
        assertEquals(640, row)
        targets.forEachIndexed { index, target ->
            assertTrue(target <= content[index])
            assertTrue(target >= 0)
        }
        // Widest content stays widest after scaling.
        assertEquals(targets.max(), targets[0])
        assertEquals(targets.min(), targets[content.size - 1])
    }

    @Test
    fun singleChild_getsWholeRow() {
        val (targets, row) = computeDistributedWidths(listOf(150), gapPx = 8, maxWidth = 400)
        assertEquals(listOf(400), targets)
        assertEquals(400, row)
    }

    @Test
    fun singleChild_overflowShrinksToRow() {
        val (targets, row) = computeDistributedWidths(listOf(900), gapPx = 8, maxWidth = 400)
        assertEquals(listOf(400), targets)
        assertEquals(400, row)
    }

    @Test
    fun emptyList_reportsZeroWidth() {
        val (targets, row) = computeDistributedWidths(emptyList(), gapPx = 8, maxWidth = 1000)
        assertEquals(emptyList<Int>(), targets)
        assertEquals(0, row)
    }

    @Test
    fun exactFit_leftoverZeroKeepsContentWidths() {
        // logtest40 boundary: leftover exactly 0 must return the
        // content widths unchanged with row == maxWidth (this is the
        // branch the old double-measure policy happened to survive,
        // since probe widths already equalled the targets).
        val gap = 8
        val content = listOf(100, 200, 150)
        val maxWidth = content.sum() + gap * (content.size - 1)
        val (targets, row) = computeDistributedWidths(content, gap, maxWidth = maxWidth)
        assertEquals(content, targets)
        assertEquals(maxWidth, row)
    }

    @Test
    fun unbounded_keepsContentWidths() {
        val gap = 8
        val content = listOf(100, 120, 80, 100)
        val (targets, row) = computeDistributedWidths(content, gap, maxWidth = Constraints.Infinity)
        assertEquals(content, targets)
        assertEquals(content.sum() + gap * (content.size - 1), row)
    }
}
