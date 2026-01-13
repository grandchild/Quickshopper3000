package org.effekthasch.quickshopper3k.util

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "quickshopper3k_prefs",
        Context.MODE_PRIVATE
    )

    var currentStoreId: Long?
        get() {
            val id = prefs.getLong(KEY_CURRENT_STORE_ID, -1L)
            return if (id == -1L) null else id
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    remove(KEY_CURRENT_STORE_ID)
                } else {
                    putLong(KEY_CURRENT_STORE_ID, value)
                }
                apply()
            }
        }

    var isLeftHanded: Boolean
        get() = prefs.getBoolean(KEY_IS_LEFT_HANDED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_IS_LEFT_HANDED, value).apply()
        }

    var textScale: TextScale
        get() {
            val value = prefs.getString(KEY_TEXT_SCALE, "normal") ?: "normal"
            return when (value) {
                "compact" -> TextScale.COMPACT
                "large" -> TextScale.LARGE
                else -> TextScale.NORMAL
            }
        }
        set(value) {
            prefs.edit().putString(KEY_TEXT_SCALE, value.name.lowercase()).apply()
        }

    var showDoneItems: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DONE_ITEMS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_DONE_ITEMS, value).apply()
        }

    companion object {
        private const val KEY_CURRENT_STORE_ID = "current_store_id"
        private const val KEY_IS_LEFT_HANDED = "is_left_handed"
        private const val KEY_TEXT_SCALE = "text_scale"
        private const val KEY_SHOW_DONE_ITEMS = "show_done_items"
    }
}

enum class TextScale(val multiplier: Float) {
    COMPACT(0.9f),
    NORMAL(1.0f),
    LARGE(1.2f)
}
