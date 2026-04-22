package org.effekthasch.quickshopper3k.data.sync

import android.content.Context
import android.content.SharedPreferences

object SyncStats {
    private var prefs: SharedPreferences? = null

    data class SyncStatEntry(
        val pushBytes: Long,
        val pushCount: Long,
        val pullBytes: Long,
        val pullCount: Long
    )

    fun init(context: Context) {
        prefs = context.getSharedPreferences("sync_stats", Context.MODE_PRIVATE)
    }

    fun recordPush(intentType: String, byteCount: Long) {
        val p = prefs ?: return
        val key = intentType.lowercase()
        p.edit()
            .putLong("push_bytes_$key", p.getLong("push_bytes_$key", 0) + byteCount)
            .putLong("push_count_$key", p.getLong("push_count_$key", 0) + 1)
            .apply()
    }

    fun recordPull(intentType: String, byteCount: Long) {
        val p = prefs ?: return
        val key = intentType.lowercase()
        p.edit()
            .putLong("pull_bytes_$key", p.getLong("pull_bytes_$key", 0) + byteCount)
            .putLong("pull_count_$key", p.getLong("pull_count_$key", 0) + 1)
            .apply()
    }

    fun recordPollOverhead(byteCount: Long) {
        val p = prefs ?: return
        p.edit()
            .putLong("poll_overhead_bytes", p.getLong("poll_overhead_bytes", 0) + byteCount)
            .putLong("poll_overhead_count", p.getLong("poll_overhead_count", 0) + 1)
            .apply()
    }

    fun getSummary(): Map<String, SyncStatEntry> {
        val p = prefs ?: return emptyMap()
        val types = mutableSetOf<String>()
        for (key in p.all.keys) {
            val suffix = when {
                key.startsWith("push_bytes_") -> key.removePrefix("push_bytes_")
                key.startsWith("push_count_") -> key.removePrefix("push_count_")
                key.startsWith("pull_bytes_") -> key.removePrefix("pull_bytes_")
                key.startsWith("pull_count_") -> key.removePrefix("pull_count_")
                else -> null
            }
            if (suffix != null) types.add(suffix)
        }
        return types.associateWith { type ->
            SyncStatEntry(
                pushBytes = p.getLong("push_bytes_$type", 0),
                pushCount = p.getLong("push_count_$type", 0),
                pullBytes = p.getLong("pull_bytes_$type", 0),
                pullCount = p.getLong("pull_count_$type", 0)
            )
        }
    }

    fun getTotals(): Triple<Long, Long, Long> {
        val p = prefs ?: return Triple(0L, 0L, 0L)
        val summary = getSummary()
        val pushBytes = summary.values.sumOf { it.pushBytes }
        val pullBytes = summary.values.sumOf { it.pullBytes }
        val pollBytes = p.getLong("poll_overhead_bytes", 0)
        return Triple(pushBytes, pullBytes, pollBytes)
    }

    fun getPollCount(): Long {
        return prefs?.getLong("poll_overhead_count", 0) ?: 0
    }

    fun reset() {
        prefs?.edit()?.clear()?.apply()
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        val gb = mb / 1024.0
        return "%.2f GB".format(gb)
    }
}
