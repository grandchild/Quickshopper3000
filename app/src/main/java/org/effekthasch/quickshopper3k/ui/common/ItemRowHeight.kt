package org.effekthasch.quickshopper3k.ui.common

import android.view.View
import org.effekthasch.quickshopper3k.util.ItemRowHeight

fun View.applyItemRowHeight(index: Int) {
    layoutParams = layoutParams.apply {
        height = ItemRowHeight.heightPx(resources.displayMetrics.density, index)
    }
}
