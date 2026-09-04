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
            val baseUrl = serverUrlProvider() ?: return
            val listId = listIdProvider()
            val deviceId = deviceIdProvider()
            val client = SyncHttpClient(baseUrl)

            withContext(Dispatchers.IO) {
                val hasWatermark = syncDao.getSyncState(KEY_LAST_SYNCED_ULID) != null
                val queuedCount = syncDao.getUnsyncedCount()
                val hasQueued = queuedCount > 0

                when {
                    hasWatermark || hasQueued -> {
                        pull(client, listId, deviceId)
                        push(client, listId, deviceId)
                    }

                    else -> initialSync(client, listId, deviceId)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed", e)
        } finally {
            syncing = false
        }
    }

    private suspend fun push(client: SyncHttpClient, listId: String, deviceId: String) {
        var totalPushed = 0
        while (true) {
            val batch = syncDao.getUnsyncedBatch(BATCH_SIZE)
            if (batch.isEmpty()) break

            val envelopes = JSONArray()
            for (entry in batch) {
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

            val result = client.postIntents(listId, envelopes)
            if (result.isFailure) {
                Log.w(TAG, "Push failed", result.exceptionOrNull())
                return
            }

            syncDao.markSynced(batch.map { it.id })
            syncDao.purgeSynced()
            totalPushed += batch.size
            Log.d(TAG, "Pushed batch of ${batch.size} (total $totalPushed)")
        }
    }

    private suspend fun pull(client: SyncHttpClient, listId: String, deviceId: String) {
        val sinceUlid = syncDao.getSyncState(KEY_LAST_SYNCED_ULID)

        client.getIntents(listId, sinceUlid, deviceId).onSuccess { response ->
            val array = parseIntentsResponse(response.body)
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

    private suspend fun initialSync(client: SyncHttpClient, listId: String, deviceId: String) {
        val remoteResult = client.getIntents(listId, null, deviceId).getOrElse { e ->
            Log.w(TAG, "Initial sync pull failed", e)
            return
        }

        val remoteArray = parseIntentsResponse(remoteResult.body)
        if (remoteArray.length() > 0) {
            applyRemoteIntents(remoteArray, deviceId)
        } else {
            enqueueFullState()
            push(client, listId, deviceId)
            pull(client, listId, deviceId)
        }
    }

    private suspend fun applyRemoteIntents(array: JSONArray, deviceId: String) {
        var latestUlid: String? = null
        for (i in 0 until array.length()) {
            val json = array.getJSONObject(i)
            val envelope = SyncIntentSerializer.fromJson(json) ?: continue
            val serverUlid = envelope.serverUlid ?: continue
            if (envelope.deviceId != deviceId) {
                applier.apply(envelope.intent, serverUlid)
            }
            latestUlid = serverUlid
        }
        if (latestUlid != null) {
            syncDao.setSyncState(SyncStateEntity(KEY_LAST_SYNCED_ULID, latestUlid))
        }
    }

    private suspend fun enqueueFullState() {
        val itemDao = database.itemDao()
        val storeDao = database.storeDao()
        val emitter = IntentEmitter(syncDao)

        val items = itemDao.getAllItems()
        for (item in items) {
            emitter.emit(SyncIntent.AddItem(item.uuid, item.name))
            if (item.amount != 1) {
                emitter.emit(SyncIntent.SetItemAmount(item.uuid, item.amount))
            }
            val state = itemDao.getItemState(item.id)
            if (state != null && state.state != "ACTIVATED") {
                emitter.emit(SyncIntent.SetItemState(item.uuid, state.state, "ACTIVATED"))
            }
        }

        val stores = storeDao.getAllStoresList()
        for (store in stores) {
            emitter.emit(SyncIntent.AddStore(store.uuid, store.name, store.specialtyStore))
        }

        val itemUuidById = items.associate { it.id to it.uuid }
        val storeUuidById = stores.associate { it.id to it.uuid }

        val unavailability = storeDao.getAllUnavailability()
        for (entry in unavailability) {
            val storeUuid = storeUuidById[entry.storeId] ?: continue
            val itemUuid = itemUuidById[entry.itemId] ?: continue
            emitter.emit(SyncIntent.SetAvailability(storeUuid, itemUuid, false))
        }

        val orders = storeDao.getAllItemOrders()
        for (order in orders) {
            val storeUuid = storeUuidById[order.storeId] ?: continue
            val itemUuid = itemUuidById[order.itemId] ?: continue
            emitter.emit(SyncIntent.SetItemOrder(storeUuid, itemUuid, order.sortOrder))
        }
    }

    private suspend fun parseIntentsResponse(body: String): JSONArray {
        val snapshotApplier = SnapshotApplier(database.itemDao(), database.storeDao(), syncDao)
        val (intents, snapshotUlid) = snapshotApplier.parseResponse(body)
        if (snapshotUlid != null) {
            val current = syncDao.getSyncState(KEY_LAST_SYNCED_ULID)
            if (current == null || current < snapshotUlid)
                syncDao.setSyncState(SyncStateEntity(KEY_LAST_SYNCED_ULID, snapshotUlid))
        }
        return intents
    }

    companion object {
        private const val TAG = "SyncManager"
        private const val POLL_INTERVAL_MS = 5_000L
        private const val BATCH_SIZE = 5000
        private const val KEY_LAST_SYNCED_ULID = "last_synced_ulid"
    }
}
