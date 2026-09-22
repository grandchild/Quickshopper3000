package org.effekthasch.quickshopper3k.util

import kotlin.math.roundToInt

object ItemRowHeight {
    private val heightsDp = intArrayOf(28, 32, 36, 40, 45)

    val lastIndex: Int
        get() = heightsDp.lastIndex

    val defaultIndex: Int
        get() = lastIndex / 2

    fun index(value: Int): Int = value.coerceIn(0, lastIndex)

    fun heightDp(index: Int): Int = heightsDp[this.index(index)]

    fun heightPx(density: Float, index: Int): Int = (heightDp(index) * density).roundToInt()
}
