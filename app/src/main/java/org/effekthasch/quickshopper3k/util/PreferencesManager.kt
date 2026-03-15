package org.effekthasch.quickshopper3k.util

import android.content.Context
import android.content.SharedPreferences
import org.effekthasch.quickshopper3k.util.Ulid

enum class DoneVisibilityMode {
    RECENT,
    ALL;

    fun next(): DoneVisibilityMode = when (this) {
        RECENT -> ALL
        ALL -> RECENT
    }

    fun label(): String = when (this) {
        RECENT -> "recent"
        ALL -> "all"
    }

    companion object {
        fun fromString(value: String): DoneVisibilityMode = when (value.lowercase()) {
            "recent" -> RECENT
            "all" -> ALL
            else -> RECENT
        }
    }
}

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

    var selectedShopStoreIds: Set<Long>
        get() {
            val idsString = prefs.getString(KEY_SELECTED_SHOP_STORE_IDS, null)
            return if (idsString.isNullOrEmpty()) {
                emptySet()
            } else {
                idsString.split(",").mapNotNull { it.toLongOrNull() }.toSet()
            }
        }
        set(value) {
            prefs.edit().apply {
                if (value.isEmpty()) {
                    remove(KEY_SELECTED_SHOP_STORE_IDS)
                } else {
                    putString(KEY_SELECTED_SHOP_STORE_IDS, value.joinToString(","))
                }
                apply()
            }
        }

    var isLeftHanded: Boolean
        get() = prefs.getBoolean(KEY_IS_LEFT_HANDED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_IS_LEFT_HANDED, value).apply()
        }

    var doneVisibilityMode: DoneVisibilityMode
        get() {
            val value = prefs.getString(KEY_DONE_VISIBILITY_MODE, "recent") ?: "recent"
            return DoneVisibilityMode.fromString(value)
        }
        set(value) {
            prefs.edit().putString(KEY_DONE_VISIBILITY_MODE, value.name.lowercase()).apply()
        }

    var recentDoneMinutes: Int
        get() = prefs.getInt(KEY_RECENT_DONE_MINUTES, 60)
        set(value) {
            prefs.edit().putInt(KEY_RECENT_DONE_MINUTES, value).apply()
        }

    var historyDays: Int
        get() = prefs.getInt(KEY_HISTORY_DAYS, 183)
        set(value) {
            prefs.edit().putInt(KEY_HISTORY_DAYS, value).apply()
        }

    var mistakeDoneSeconds: Int
        get() = prefs.getInt(KEY_MISTAKE_DONE_SECONDS, 20)
        set(value) {
            prefs.edit().putInt(KEY_MISTAKE_DONE_SECONDS, value).apply()
        }

    var reactivateMinSeconds: Int
        get() = prefs.getInt(KEY_REACTIVATE_MIN_SECONDS, 3600)
        set(value) {
            prefs.edit().putInt(KEY_REACTIVATE_MIN_SECONDS, value).apply()
        }

    var syncServerUri: String?
        get() = prefs.getString(KEY_SYNC_SERVER_URI, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    remove(KEY_SYNC_SERVER_URI)
                } else {
                    putString(KEY_SYNC_SERVER_URI, value)
                }
                apply()
            }
        }

    val deviceId: String
        get() {
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (existing != null) return existing
            val id = Ulid.generate()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            return id
        }

    val listId: String
        get() {
            val existing = prefs.getString(KEY_LIST_ID, null)
            if (existing != null) return existing
            val id = Ulid.generate()
            prefs.edit().putString(KEY_LIST_ID, id).apply()
            return id
        }

    var flushRecentlyDoneAt: Long?
        get() {
            val value = prefs.getLong(KEY_FLUSH_RECENTLY_DONE_AT, -1L)
            return if (value == -1L) null else value
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    remove(KEY_FLUSH_RECENTLY_DONE_AT)
                } else {
                    putLong(KEY_FLUSH_RECENTLY_DONE_AT, value)
                }
                apply()
            }
        }

    companion object {
        private const val KEY_CURRENT_STORE_ID = "current_store_id"
        private const val KEY_SELECTED_SHOP_STORE_IDS = "selected_shop_store_ids"
        private const val KEY_IS_LEFT_HANDED = "is_left_handed"

        private const val KEY_DONE_VISIBILITY_MODE = "done_visibility_mode"
        private const val KEY_RECENT_DONE_MINUTES = "recent_done_minutes"
        private const val KEY_FLUSH_RECENTLY_DONE_AT = "flush_recently_done_at"
        private const val KEY_HISTORY_DAYS = "history_days"
        private const val KEY_MISTAKE_DONE_SECONDS = "mistake_done_seconds"
        private const val KEY_REACTIVATE_MIN_SECONDS = "reactivate_min_seconds"
        private const val KEY_SYNC_SERVER_URI = "sync_server_uri"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_LIST_ID = "list_id"
    }
}
