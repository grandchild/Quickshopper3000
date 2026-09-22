package org.effekthasch.quickshopper3k.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ItemRowHeightTest {
    @Test
    fun heightDp_hasFiveEvenlySpacedStops() {
        assertEquals(listOf(28, 32, 36, 40, 45), (0..ItemRowHeight.lastIndex).map(ItemRowHeight::heightDp))
    }

    @Test
    fun index_staysWithinRange() {
        assertEquals(0, ItemRowHeight.index(-1))
        assertEquals(ItemRowHeight.lastIndex, ItemRowHeight.index(Int.MAX_VALUE))
    }
}
