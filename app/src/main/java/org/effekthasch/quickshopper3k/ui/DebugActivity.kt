package org.effekthasch.quickshopper3k.ui

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.effekthasch.quickshopper3k.R
import org.effekthasch.quickshopper3k.data.local.AppDatabase
import org.effekthasch.quickshopper3k.data.sync.SyncHttpClient
import org.effekthasch.quickshopper3k.data.sync.SyncStats
import org.effekthasch.quickshopper3k.util.PreferencesManager
import android.widget.Button
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

class DebugActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager
    private lateinit var adapter: DebugAdapter
    private var fetchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Debug"

        prefs = PreferencesManager(this)

        val listIdInput = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.debugListIdInput)
        val statusText = findViewById<TextView>(R.id.debugStatusText)
        val recyclerView = findViewById<RecyclerView>(R.id.debugRecyclerView)

        adapter = DebugAdapter()
        val layoutManager = LinearLayoutManager(this)
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = adapter

        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.scrollTopButton).setOnClickListener {
            recyclerView.scrollToPosition(0)
        }
        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.scrollBottomButton).setOnClickListener {
            val last = adapter.itemCount - 1
            if (last >= 0) recyclerView.scrollToPosition(last)
        }

        listIdInput.setText(prefs.listId)

        listIdInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim() ?: return
                if (text.length < 10) return
                fetchJob?.cancel()
                fetchJob = lifecycleScope.launch {
                    delay(400)
                    loadDebug(text, statusText)
                }
            }
        })

        val syncStatsText = findViewById<TextView>(R.id.debugSyncStatsText)
        val resetStatsButton = findViewById<Button>(R.id.debugResetStatsButton)
        refreshSyncStats(syncStatsText, resetStatsButton)

        resetStatsButton.setOnClickListener {
            SyncStats.reset()
            refreshSyncStats(syncStatsText, resetStatsButton)
        }

        loadDebug(prefs.listId, statusText)
    }

    private fun refreshSyncStats(statsText: TextView, resetButton: Button) {
        val summary = SyncStats.getSummary()
        if (summary.isEmpty()) {
            statsText.visibility = View.GONE
            resetButton.visibility = View.GONE
            return
        }

        val (pushTotal, pullTotal, pollTotal) = SyncStats.getTotals()
        val pollCount = SyncStats.getPollCount()
        val sb = StringBuilder()
        sb.appendLine("── Sync Data Usage ──")
        sb.appendLine("Push: ${SyncStats.formatBytes(pushTotal)}  Pull: ${SyncStats.formatBytes(pullTotal)}")
        sb.appendLine("Poll overhead: ${SyncStats.formatBytes(pollTotal)} ($pollCount polls)")
        sb.appendLine()

        val sorted = summary.entries.sortedByDescending { it.value.pushBytes + it.value.pullBytes }
        for ((type, e) in sorted) {
            sb.appendLine("$type")
            sb.appendLine("  ↑ ${e.pushCount}× ${SyncStats.formatBytes(e.pushBytes)}  ↓ ${e.pullCount}× ${SyncStats.formatBytes(e.pullBytes)}")
        }

        statsText.text = sb.toString().trimEnd()
        statsText.visibility = View.VISIBLE
        resetButton.visibility = View.VISIBLE
    }

    private fun loadDebug(listId: String, statusText: TextView) {
        val baseUrl = prefs.syncServerUri
        if (baseUrl == null) {
            statusText.text = "No sync server configured"
            statusText.visibility = View.VISIBLE
            adapter.submitRows(emptyList())
            return
        }

        statusText.text = "Loading…"
        statusText.visibility = View.VISIBLE
        adapter.submitRows(emptyList())

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { SyncHttpClient(baseUrl).getDebug(listId).getOrThrow() }
            }
            result.onSuccess { json ->
                val (itemNames, storeNames) = resolveNames(json)
                val rows = buildRows(json, prefs.deviceId, itemNames, storeNames)
                adapter.submitRows(rows)
                statusText.visibility = View.GONE
            }.onFailure { err ->
                statusText.text = "Error: ${err.message}"
                statusText.visibility = View.VISIBLE
            }
        }
    }

    private suspend fun resolveNames(json: JSONObject): Pair<Map<String, String>, Map<String, String>> {
        val itemUuids = mutableSetOf<String>()
        val storeUuids = mutableSetOf<String>()
        val intents = json.optJSONArray("intents")
            ?: return emptyMap<String, String>() to emptyMap()
        for (i in 0 until intents.length()) {
            val payload = intents.optJSONObject(i)?.optJSONObject("payload") ?: continue
            payload.optString("item_uuid").takeIf { it.isNotEmpty() }?.let { itemUuids.add(it) }
            payload.optString("store_uuid").takeIf { it.isNotEmpty() }?.let { storeUuids.add(it) }
        }
        return withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@DebugActivity)
            val itemNames = if (itemUuids.isEmpty()) emptyMap()
            else db.itemDao().getNamesByUuids(itemUuids.toList()).associate { it.uuid to it.name }
            val storeNames = if (storeUuids.isEmpty()) emptyMap()
            else db.storeDao().getNamesByUuids(storeUuids.toList()).associate { it.uuid to it.name }
            itemNames to storeNames
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

private sealed class DebugRow {
    data class SnapshotHeader(val ulid: String, val agoText: String) : DebugRow()
    data class IntentRow(
        val serverUlid: String?,
        val deviceId: String,
        val type: String,
        val payload: JSONObject?,
        val resolvedName: String?,
        val resolvedStoreName: String?,
        val compacted: Boolean,
    ) : DebugRow()

    data class DeviceSeparator(
        val deviceId: String,
        val isThis: Boolean,
        val lastSyncedUlid: String?,
    ) : DebugRow()
}

private fun buildRows(
    json: JSONObject,
    thisDeviceId: String,
    itemNames: Map<String, String>,
    storeNames: Map<String, String>,
): List<DebugRow> {
    val rows = mutableListOf<DebugRow>()

    val snapshotUlid = json.optString("snapshot_ulid").takeIf { it.isNotEmpty() }
    if (snapshotUlid != null) {
        rows.add(DebugRow.SnapshotHeader(snapshotUlid, ulidAgo(snapshotUlid)))
    }

    val intents = json.optJSONArray("intents") ?: org.json.JSONArray()
    val devices = buildList {
        val array = json.optJSONArray("devices") ?: return@buildList
        for (i in 0 until array.length()) {
            val device = array.optJSONObject(i) ?: continue
            val deviceId = device.optString("device_id")
            if (deviceId.isEmpty()) continue
            add(
                DebugRow.DeviceSeparator(
                    deviceId = deviceId,
                    isThis = deviceId == thisDeviceId,
                    lastSyncedUlid = device.optString("last_synced_ulid").takeIf { it.isNotEmpty() },
                )
            )
        }
    }.sortedBy { it.deviceId }

    val snapshotDevices = devices.filter { device ->
        val lastSyncedUlid = device.lastSyncedUlid
        lastSyncedUlid == null || snapshotUlid != null && lastSyncedUlid <= snapshotUlid
    }
    rows.addAll(snapshotDevices)
    val devicesByUlid = devices
        .filterNot { it in snapshotDevices }
        .groupBy { it.lastSyncedUlid }
        .toMutableMap()

    for (i in 0 until intents.length()) {
        val obj = intents.optJSONObject(i) ?: continue
        val payload = obj.optJSONObject("payload")
        val itemUuid = payload?.optString("item_uuid")?.takeIf { it.isNotEmpty() }
        val storeUuid = payload?.optString("store_uuid")?.takeIf { it.isNotEmpty() }
        val serverUlid = obj.optString("server_ulid").takeIf { it.isNotEmpty() }
        rows.add(
            DebugRow.IntentRow(
                serverUlid = serverUlid,
                deviceId = obj.optString("device_id"),
                type = obj.optString("intent_type").takeIf { it.isNotEmpty() }
                    ?: obj.optString("type"),
                payload = payload,
                resolvedName = itemUuid?.let { itemNames[it] },
                resolvedStoreName = storeUuid?.let { storeNames[it] },
                compacted = false,
            )
        )
        if (serverUlid != null) rows.addAll(devicesByUlid.remove(serverUlid).orEmpty())
    }
    rows.addAll(devicesByUlid.values.flatten())

    return rows
}

private fun ulidAgo(ulid: String): String {
    return try {
        val crockford = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        var ms = 0L
        for (c in ulid.take(10)) {
            val idx = crockford.indexOf(c.uppercaseChar())
            if (idx < 0) return ulid.take(8)
            ms = ms * 32 + idx
        }
        val minutes = ChronoUnit.MINUTES.between(Instant.ofEpochMilli(ms), Instant.now())
        when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 60 * 24 -> "${minutes / 60} h ago"
            else -> "${minutes / (60 * 24)} days ago"
        }
    } catch (_: Exception) {
        ulid.take(8)
    }
}

private enum class StateIcon { NEED, DONE, NA, NEW, REORDER, AVAILABLE, UNAVAILABLE, NONE }

private fun intentIcon(type: String, payload: JSONObject?): StateIcon = when (type) {
    "add_item", "bulk_add_items" -> StateIcon.NEW

    "set_item_state" -> when (payload?.optString("state")) {
        "ACTIVATED" -> StateIcon.NEED
        "DEACTIVATED" -> StateIcon.DONE
        "POSTPONED" -> StateIcon.NA
        else -> StateIcon.NONE
    }

    "bulk_set_state" -> when (payload?.optString("state")) {
        "ACTIVATED" -> StateIcon.NEED
        "DEACTIVATED" -> StateIcon.DONE
        "POSTPONED" -> StateIcon.NA
        else -> StateIcon.NONE
    }

    "set_item_order" -> StateIcon.REORDER

    "set_availability" -> if (payload?.optBoolean("available", true) == false)
        StateIcon.UNAVAILABLE else StateIcon.AVAILABLE

    else -> StateIcon.NONE
}

private fun intentLabel(type: String, payload: JSONObject?, resolvedName: String?, resolvedStoreName: String?): String {
    val payloadName = payload?.optString("name")?.takeIf { it.isNotEmpty() }
    val name = resolvedName ?: payloadName

    return when (type) {
        "add_item" -> if (name != null) "$name" else "add item"
        "delete_item" -> if (name != null) "delete $name" else "delete item"
        "rename_item" -> if (name != null) "rename $name" else "rename item"
        "set_item_state" -> {
            val state = payload?.optString("state")
            val n = name ?: payload?.optString("item_uuid")?.takeLast(8) ?: "?"
            when (state) {
                "ACTIVATED" -> n
                "DEACTIVATED" -> n
                "POSTPONED" -> n
                else -> if (state != null) "$state  $n" else n
            }
        }

        "set_item_amount" -> {
            val amount = payload?.optInt("amount", -1)?.takeIf { it >= 0 }
            val n = name ?: payload?.optString("item_uuid")?.takeLast(8) ?: "?"
            if (amount != null) "${amount}×  $n" else "amount  $n"
        }

        "add_store" -> if (name != null) "+ $name" else "add store"
        "update_store" -> if (name != null) "update $name" else "update store"
        "delete_store" -> if (name != null) "delete $name" else "delete store"
        "set_availability" -> {
            val n = name ?: payload?.optString("item_uuid")?.takeLast(8) ?: "?"
            if (resolvedStoreName != null) "$n @ $resolvedStoreName" else n
        }

        "set_item_order" -> {
            val n = name ?: payload?.optString("item_uuid")?.takeLast(8) ?: "?"
            n
        }

        "bulk_add_items" -> {
            val count = payload?.optJSONArray("items")?.length()
            if (count != null) "bulk + $count" else "bulk add"
        }

        "bulk_set_state" -> {
            val state = payload?.optString("state") ?: ""
            val count = payload?.optJSONArray("item_uuids")?.length()
            val suffix = if (count != null) " ($count)" else ""
            when (state) {
                "ACTIVATED" -> "all$suffix"
                "DEACTIVATED" -> "all$suffix"
                "POSTPONED" -> "all$suffix"
                else -> "bulk state$suffix"
            }
        }

        else -> type.take(40)
    }
}

private fun intentColors(type: String): Pair<Int, Int> = when (type) {
    "add_item", "bulk_add_items" -> R.color.debug_bg_add to R.color.debug_fg_add
    "delete_item" -> R.color.debug_bg_remove to R.color.debug_fg_remove
    "rename_item" -> R.color.debug_bg_rename to R.color.debug_fg_rename
    "set_item_state", "bulk_set_state" -> R.color.debug_bg_state to R.color.debug_fg_state
    "set_item_amount" -> R.color.debug_bg_amount to R.color.debug_fg_amount
    "add_store", "update_store", "delete_store", "set_availability", "set_item_order" ->
        R.color.debug_bg_store to R.color.debug_fg_store

    else -> R.color.debug_bg_other to R.color.debug_fg_other
}

private fun tintedDrawable(source: Drawable, color: Int): Drawable {
    val d = DrawableCompat.wrap(
        (source.constantState?.newDrawable() ?: source).mutate()
    )
    DrawableCompat.setTint(d, color)
    return d
}

private class DebugAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<DebugRow> = emptyList()

    fun submitRows(newRows: List<DebugRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is DebugRow.SnapshotHeader -> 0
        is DebugRow.IntentRow -> 1
        is DebugRow.DeviceSeparator -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> SnapshotVH(inf.inflate(R.layout.item_debug_snapshot, parent, false))
            1 -> IntentVH(inf.inflate(R.layout.item_debug_intent, parent, false))
            2 -> DeviceVH(inf.inflate(R.layout.item_debug_device, parent, false))
            else -> throw IllegalStateException("unknown viewType $viewType")
        }
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is DebugRow.SnapshotHeader -> (holder as SnapshotVH).bind(row)
            is DebugRow.IntentRow -> (holder as IntentVH).bind(row)
            is DebugRow.DeviceSeparator -> (holder as DeviceVH).bind(row)
        }
    }

    private class SnapshotVH(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.snapshotLabel)
        fun bind(row: DebugRow.SnapshotHeader) {
            label.text = "snapshot, ${row.agoText}"
        }
    }

    private class IntentVH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.intentIcon)
        val label: TextView = view.findViewById(R.id.intentLabel)
        val deviceLabel: TextView = view.findViewById(R.id.intentDevice)

        fun bind(row: DebugRow.IntentRow) {
            val ctx = itemView.context
            val (bgRes, fgRes) = intentColors(row.type)
            val fgColor = ContextCompat.getColor(ctx, fgRes)

            itemView.setBackgroundColor(ContextCompat.getColor(ctx, bgRes))
            itemView.alpha = if (row.compacted) 0.55f else 1.0f

            label.text = intentLabel(row.type, row.payload, row.resolvedName, row.resolvedStoreName)
            label.setTextColor(fgColor)
            deviceLabel.text = row.deviceId.takeLast(8)
            deviceLabel.setTextColor(fgColor)

            val stateIcon = intentIcon(row.type, row.payload)
            if (stateIcon == StateIcon.NONE) {
                icon.visibility = View.GONE
            } else {
                icon.visibility = View.VISIBLE
                val drawableRes = when (stateIcon) {
                    StateIcon.NEED -> R.drawable.ic_state_need
                    StateIcon.DONE -> R.drawable.ic_state_done
                    StateIcon.NA -> R.drawable.ic_state_na
                    StateIcon.NEW -> R.drawable.ic_intent_new
                    StateIcon.REORDER -> R.drawable.ic_intent_reorder
                    StateIcon.AVAILABLE -> R.drawable.ic_intent_available
                    StateIcon.UNAVAILABLE -> R.drawable.ic_intent_unavailable
                    StateIcon.NONE -> R.drawable.ic_state_need
                }
                val src = ContextCompat.getDrawable(ctx, drawableRes) ?: return
                icon.setImageDrawable(tintedDrawable(src, fgColor))
            }
        }
    }

    private class DeviceVH(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.deviceLabel)
        fun bind(row: DebugRow.DeviceSeparator) {
            val deviceId = row.deviceId.takeLast(12)
            val suffix = if (row.isThis) "  (this)" else ""
            val status = if (row.lastSyncedUlid == null) " · not yet synced" else ""
            label.text = "$deviceId$suffix$status"
        }
    }
}
