package org.effekthasch.quickshopper3k.util

object StaleDoneTimeout {
    val valuesSeconds = intArrayOf(0, 3, 10, 20, 60, 180, 600, 1200, 3600)

    val defaultSeconds: Int
        get() = valuesSeconds.last()

    fun value(seconds: Int): Int = valuesSeconds.minByOrNull { kotlin.math.abs(it - seconds) } ?: defaultSeconds

    fun index(seconds: Int): Int = valuesSeconds.indexOf(value(seconds))
}
