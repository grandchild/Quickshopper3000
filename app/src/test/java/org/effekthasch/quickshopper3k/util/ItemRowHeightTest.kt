package org.effekthasch.quickshopper3k.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ItemRowHeightTest {
    @Test
    fun heightPx_scalesFromTightToSpacious() {
        assertEquals(listOf(28, 34, 39, 45, 50), (0..ItemRowHeight.lastIndex).map {
            ItemRowHeight.heightPx(28, it)
        })
    }

    @Test
    fun index_staysWithinRange() {
        assertEquals(0, ItemRowHeight.index(-1))
        assertEquals(ItemRowHeight.lastIndex, ItemRowHeight.index(Int.MAX_VALUE))
    }

    @Test
    fun heightPx_preservesTheTightHeightAtTheFirstStop() {
        assertEquals(24, ItemRowHeight.heightPx(24, 0))
        assertEquals(43, ItemRowHeight.heightPx(24, ItemRowHeight.lastIndex))
    }
}
