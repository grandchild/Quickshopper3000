package org.effekthasch.quickshopper3k.data.sync

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.effekthasch.quickshopper3k.data.local.AppDatabase
import org.effekthasch.quickshopper3k.data.local.SyncStateEntity

class SyncManager(
    private val database: AppDatabase,
    private val serverUrlProvider: () -> String?,
    private val deviceIdProvider: () -> String,
    private val listIdProvider: () -> String
) {
    private val syncDao = database.syncDao()
    private val applier = IntentApplier(database.itemDao(), database.storeDao(), syncDao)

    private var pollJob: Job? = null

    @Volatile
    private var syncing = false

    fun start(scope: CoroutineScope) {
        stop()
        pollJob = scope.launch {
            syncOnce()
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                syncOnce()
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    fun triggerSync(scope: CoroutineScope) {
        scope.launch { syncOnce() }
    }

    private suspend fun syncOnce() {
        if (syncing) return
        syncing = true
        try {
            val baseUrl = serverUrlProvider()
            if (baseUrl == null) return
            val listId = listIdProvider()
            val deviceId = deviceIdProvider()
            val client = SyncHttpClient(baseUrl)

            withContext(Dispatchers.IO) {
                push(client, listId, deviceId)
                pull(client, listId, deviceId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed", e)
        } finally {
            syncing = false
        }
    }

    private suspend fun push(client: SyncHttpClient, listId: String, deviceId: String) {
        val queued = syncDao.getUnsynced()
        if (queued.isEmpty()) return

        val envelopes = JSONArray()
        for (entry in queued) {
            val stored = JSONObject(entry.intentJson)
            val envelope = JSONObject().apply {
                put("device_ulid", stored.getString("device_ulid"))
                put("server_ulid", JSONObject.NULL)
                put("device_id", deviceId)
                put("list_id", listId)
                put("type", stored.getString("type"))
                put("payload", stored.getJSONObject("payload"))
            }
            envelopes.put(envelope)
        }

        client.postIntents(listId, envelopes).onSuccess {
            syncDao.markSynced(queued.map { it.id })
            syncDao.purgeSynced()
        }.onFailure { e ->
            Log.w(TAG, "Push failed", e)
        }
    }

    private suspend fun pull(client: SyncHttpClient, listId: String, deviceId: String) {
        val sinceUlid = syncDao.getSyncState(KEY_LAST_SYNCED_ULID)

        client.getIntents(listId, sinceUlid).onSuccess { array ->
            var latestUlid = sinceUlid
            for (i in 0 until array.length()) {
                val json = array.getJSONObject(i)
                val envelope = SyncIntentSerializer.fromJson(json) ?: continue
                val serverUlid = envelope.serverUlid ?: continue
                if (envelope.deviceId == deviceId) {
                    latestUlid = serverUlid
                    continue
                }
                applier.apply(envelope.intent, serverUlid)
                latestUlid = serverUlid
            }
            if (latestUlid != null && latestUlid != sinceUlid) {
                syncDao.setSyncState(SyncStateEntity(KEY_LAST_SYNCED_ULID, latestUlid))
            }
        }.onFailure { e ->
            Log.w(TAG, "Pull failed", e)
        }
    }

    companion object {
        private const val TAG = "SyncManager"
        private const val POLL_INTERVAL_MS = 5_000L
        private const val KEY_LAST_SYNCED_ULID = "last_synced_ulid"
    }
}
