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
import org.effekthasch.quickshopper3k.data.repository.ShoppingRepository

class SyncManager(
    private val database: AppDatabase,
    private val serverUrlProvider: () -> String?,
    private val deviceIdProvider: () -> String,
    private val listIdProvider: () -> String,
    private val initializeEmptyList: suspend () -> Unit = {},
    private val historyDaysProvider: () -> Int = { ShoppingRepository.DEFAULT_HISTORY_DAYS },
    private val mistakeDoneSecondsProvider: () -> Int = { ShoppingRepository.DEFAULT_MISTAKE_DONE_SECONDS },
    private val reactivateMinSecondsProvider: () -> Int = { ShoppingRepository.DEFAULT_REACTIVATE_MIN_SECONDS }
) {
    private val syncDao = database.syncDao()
    private val applier = IntentApplier(
        database.itemDao(),
        database.storeDao(),
        syncDao,
        historyDaysProvider,
        mistakeDoneSecondsProvider,
        reactivateMinSecondsProvider
    )

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

    suspend fun syncNow(replayOwnIntents: Boolean = false): Boolean {
        if (!syncOnce(replayOwnIntents)) return false
        return syncOnce()
    }

    private suspend fun syncOnce(replayOwnIntents: Boolean = false): Boolean {
        if (syncing) return false
        syncing = true
        return try {
            val baseUrl = serverUrlProvider()
            if (baseUrl == null) {
                initializeEmptyList()
                return true
            }
            val listId = listIdProvider()
            val deviceId = deviceIdProvider()
            val client = SyncHttpClient(baseUrl)

            withContext(Dispatchers.IO) {
                val hasWatermark = syncDao.getSyncState(KEY_LAST_SYNCED_ULID) != null
                val hasQueued = syncDao.getUnsyncedCount() > 0

                when {
                    hasWatermark || hasQueued -> {
                        pull(client, listId, deviceId, replayOwnIntents) && push(client, listId, deviceId)
                    }

                    else -> initialSync(client, listId, deviceId)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed", e)
            false
        } finally {
            syncing = false
        }
    }

    private suspend fun push(client: SyncHttpClient, listId: String, deviceId: String): Boolean {
        var totalPushed = 0
        while (true) {
            val batch = syncDao.getUnsyncedBatch(BATCH_SIZE)
            if (batch.isEmpty()) return true

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
                return false
            }

            syncDao.markSynced(batch.map { it.id })
            syncDao.purgeSynced()
            totalPushed += batch.size
            Log.d(TAG, "Pushed batch of ${batch.size} (total $totalPushed)")
        }
    }

    private suspend fun pull(
        client: SyncHttpClient,
        listId: String,
        deviceId: String,
        replayOwnIntents: Boolean,
    ): Boolean {
        val sinceUlid = syncDao.getSyncState(KEY_LAST_SYNCED_ULID)
        val response = client.getIntents(listId, sinceUlid, deviceId).getOrElse { e ->
            Log.w(TAG, "Pull failed", e)
            return false
        }

        val parsed = parseIntentsResponse(response.body)
        val envelopes = parseEnvelopes(parsed.intents)
        val latestUlid = applyEnvelopes(
            envelopes = envelopes,
            deviceId = deviceId,
            skipOwnIntents = !replayOwnIntents && !parsed.snapshotApplied
        ) ?: sinceUlid
        if (latestUlid != null && latestUlid != sinceUlid) {
            syncDao.setSyncState(SyncStateEntity(KEY_LAST_SYNCED_ULID, latestUlid))
        }
        return true
    }

    private suspend fun initialSync(client: SyncHttpClient, listId: String, deviceId: String): Boolean {
        val remoteResult = client.getIntents(listId, null, deviceId).getOrElse { e ->
            Log.w(TAG, "Initial sync pull failed", e)
            return false
        }

        val parsed = parseIntentsResponse(remoteResult.body)
        val hasRemoteState = parsed.snapshotApplied || parsed.intents.length() > 0 ||
                (syncDao.getSyncState(KEY_LAST_SYNCED_ULID) != null &&
                        database.itemDao().getAllItems().isNotEmpty())
        if (hasRemoteState) {
            if (parsed.intents.length() > 0) {
                applyEnvelopes(parseEnvelopes(parsed.intents), deviceId, skipOwnIntents = false)
            }
            if (database.itemDao().getAllItems().isNotEmpty()) {
                initializeEmptyList()
            }
            return true
        }

        initializeEmptyList()
        enqueueFullState()
        return push(client, listId, deviceId) && pull(client, listId, deviceId, false)
    }

    private fun parseEnvelopes(array: JSONArray): List<SyncIntentEnvelope> =
        (0 until array.length()).mapNotNull { index ->
            SyncIntentSerializer.fromJson(array.getJSONObject(index))
        }

    private suspend fun applyEnvelopes(
        envelopes: List<SyncIntentEnvelope>,
        deviceId: String,
        skipOwnIntents: Boolean,
    ): String? {
        applier.recoverMissingItems(envelopes.map { it.intent })
        var latestUlid: String? = null
        for (envelope in envelopes) {
            val serverUlid = envelope.serverUlid ?: continue
            latestUlid = serverUlid
            if (skipOwnIntents && envelope.deviceId == deviceId) continue
            applier.apply(envelope.intent, serverUlid)
        }
        if (latestUlid != null) {
            syncDao.setSyncState(SyncStateEntity(KEY_LAST_SYNCED_ULID, latestUlid))
        }
        return latestUlid
    }

    private suspend fun enqueueFullState() {
        val itemDao = database.itemDao()
        val storeDao = database.storeDao()
        val emitter = IntentEmitter(syncDao)

        val items = itemDao.getAllItems()
        for (item in items) {
            emitter.emit(
                SyncIntent.AddItem(
                    item.uuid,
                    item.name,
                    item.description,
                    item.synonyms
                )
            )
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

    private suspend fun parseIntentsResponse(body: String): ParsedSyncResponse {
        val snapshotApplier = SnapshotApplier(database.itemDao(), database.storeDao(), syncDao)
        val parsed = snapshotApplier.parseResponse(body)
        parsed.snapshotUlid?.let { snapshotUlid ->
            val current = syncDao.getSyncState(KEY_LAST_SYNCED_ULID)
            if (current == null || current < snapshotUlid) {
                syncDao.setSyncState(SyncStateEntity(KEY_LAST_SYNCED_ULID, snapshotUlid))
            }
        }
        return parsed
    }

    companion object {
        private const val TAG = "SyncManager"
        private const val POLL_INTERVAL_MS = 5_000L
        private const val BATCH_SIZE = 5000
        private const val KEY_LAST_SYNCED_ULID = "last_synced_ulid"
    }
}
