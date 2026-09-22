package org.effekthasch.quickshopper3k.ui.common

import android.widget.TextView
import android.view.View
import org.effekthasch.quickshopper3k.util.ItemRowHeight
import kotlin.math.max
import kotlin.math.roundToInt

fun View.applyItemRowHeight(index: Int, textView: TextView): Int {
    val iconHeight = (ITEM_ROW_ICON_SIZE_DP * resources.displayMetrics.density).roundToInt()
    val fontMetrics = textView.paint.fontMetricsInt
    val textHeight = if (textView.includeFontPadding) {
        fontMetrics.bottom - fontMetrics.top
    } else {
        fontMetrics.descent - fontMetrics.ascent
    }
    val tightHeight = max(iconHeight, textHeight) + paddingTop + paddingBottom
    val height = ItemRowHeight.heightPx(tightHeight, index)
    minimumHeight = height
    layoutParams = layoutParams.apply {
        this.height = height
    }
    return height
}

private const val ITEM_ROW_ICON_SIZE_DP = 24f
