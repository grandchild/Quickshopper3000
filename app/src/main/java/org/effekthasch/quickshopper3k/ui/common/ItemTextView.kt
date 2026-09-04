package org.effekthasch.quickshopper3k.ui.common

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.widget.TextView
import org.effekthasch.quickshopper3k.domain.model.Item

fun TextView.bindItemText(item: Item) {
    if (item.description.isBlank()) {
        text = item.name
        return
    }

    val descriptionStart = item.name.length + 2
    text = SpannableString("${item.name}  ${item.description}").apply {
        setSpan(
            StyleSpan(Typeface.ITALIC),
            descriptionStart,
            length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
}
