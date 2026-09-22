package org.effekthasch.quickshopper3k.ui.common

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.util.TypedValue
import android.text.style.StyleSpan
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.effekthasch.quickshopper3k.domain.model.Item

fun TextView.setItemTextOpacity(opacity: Float) {
    alpha = 1f
    val color = resolveThemeColor(android.R.attr.textColorPrimary) ?: return
    setTextColor(
        Color.argb(
            (Color.alpha(color) * opacity.coerceIn(0f, 1f)).toInt(),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    )
}

fun TextView.bindItemText(item: Item) {
    text = item.displayText()
}

fun TextView.bindItemTextHalo(item: Item) {
    val surfaceColor = resolveThemeColor(com.google.android.material.R.attr.colorSurface) ?: return
    text = item.displayText()
    setTextColor(surfaceColor)
    setShadowLayer(
        resources.displayMetrics.density * ITEM_TEXT_HALO_RADIUS_DP,
        0f,
        0f,
        surfaceColor
    )
}

private fun Item.displayText(): CharSequence {
    if (description.isBlank()) return name

    val descriptionStart = name.length + 2
    return SpannableString("$name  $description").apply {
        setSpan(
            StyleSpan(Typeface.ITALIC),
            descriptionStart,
            length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
}

private fun TextView.resolveThemeColor(attribute: Int): Int? {
    val value = TypedValue()
    if (!context.theme.resolveAttribute(attribute, value, true)) return null
    return if (value.resourceId == 0) {
        value.data
    } else {
        ContextCompat.getColor(context, value.resourceId)
    }
}

private const val ITEM_TEXT_HALO_RADIUS_DP = 3f
