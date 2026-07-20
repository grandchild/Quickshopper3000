package org.effekthasch.quickshopper3k.ui.common

import android.view.View
import android.widget.TextView
import org.effekthasch.quickshopper3k.R

fun TextView.bindItemAmountBadge(amount: Int, hideWhenOne: Boolean = true) {
    text = amount.toString()
    setBackgroundResource(R.drawable.bg_amount_badge)
    visibility = if (hideWhenOne && amount <= 1) {
        View.GONE
    } else {
        View.VISIBLE
    }
}
