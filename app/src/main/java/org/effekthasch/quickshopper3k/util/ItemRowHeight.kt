package org.effekthasch.quickshopper3k.util

import kotlin.math.roundToInt

object ItemRowHeight {
    private val scales = floatArrayOf(1f, 1.2f, 1.4f, 1.6f, 1.8f)

    val lastIndex: Int
        get() = scales.lastIndex

    val defaultIndex: Int
        get() = lastIndex / 2

    fun index(value: Int): Int = value.coerceIn(0, lastIndex)

    fun heightPx(tightHeightPx: Int, index: Int): Int =
        (tightHeightPx * scales[this.index(index)]).roundToInt()
}
