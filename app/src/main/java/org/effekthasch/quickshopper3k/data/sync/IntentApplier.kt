package org.effekthasch.quickshopper3k.data.sync

import org.effekthasch.quickshopper3k.data.history.PurchaseHistoryRecorder
import org.effekthasch.quickshopper3k.data.history.PurchaseHistorySettings
import org.effekthasch.quickshopper3k.data.history.RoomPurchaseHistoryRecorder
import org.effekthasch.quickshopper3k.data.local.ItemDao
import org.effekthasch.quickshopper3k.data.local.ItemEntity
import org.effekthasch.quickshopper3k.data.local.StoreDao
import org.effekthasch.quickshopper3k.data.local.StoreEntity
import org.effekthasch.quickshopper3k.data.local.StoreItemOrderEntity
import org.effekthasch.quickshopper3k.data.local.SyncDao
import org.effekthasch.quickshopper3k.data.local.SyncTombstoneEntity
import org.effekthasch.quickshopper3k.data.repository.ShoppingRepository
import org.effekthasch.quickshopper3k.domain.model.ItemState
import org.effekthasch.quickshopper3k.util.Ulid

class IntentApplier(
    private val itemDao: ItemDao,
    private val storeDao: StoreDao,
    private val syncDao: SyncDao,
    private val historyDaysProvider: () -> Int = { ShoppingRepository.DEFAULT_HISTORY_DAYS },
    private val mistakeDoneSecondsProvider: () -> Int = { ShoppingRepository.DEFAULT_MISTAKE_DONE_SECONDS },
    private val reactivateMinSecondsProvider: () -> Int = { ShoppingRepository.DEFAULT_REACTIVATE_MIN_SECONDS }
) {

    private val historyRecorder: PurchaseHistoryRecorder = RoomPurchaseHistoryRecorder(itemDao)

    private val statePriority = mapOf(
        "DEACTIVATED" to 0,
        "ACTIVATED" to 1,
        "POSTPONED" to 2
    )

    suspend fun recoverMissingItems(intents: List<SyncIntent>) {
        val metadataByUuid = linkedMapOf<String, MissingItemMetadata>()

        fun collect(intent: SyncIntent) {
            when (intent) {
                is SyncIntent.AddItem -> {
                    if (intent.name.isNotBlank()) {
                        metadataByUuid[intent.itemUuid] = MissingItemMetadata(
                            name = intent.name,
                            description = intent.description,
                            synonyms = intent.synonyms
                        )
                    }
                }

                is SyncIntent.RenameItem -> {
                    if (intent.name.isNotBlank()) {
                        val current = metadataByUuid[intent.itemUuid]
                        metadataByUuid[intent.itemUuid] = (current ?: MissingItemMetadata(
                            name = intent.name
                        )).copy(name = intent.name)
                    }
                }

                is SyncIntent.BulkAddItems -> intent.items.forEach(::collect)
                else -> Unit
            }
        }

        intents.forEach(::collect)
        for ((itemUuid, metadata) in metadataByUuid) {
            createMissingItem(itemUuid, metadata)
        }
    }

    suspend fun apply(intent: SyncIntent, serverUlid: String) {
        when (intent) {
            is SyncIntent.AddItem -> addItem(intent)
            is SyncIntent.DeleteItem -> deleteItem(intent, serverUlid)
            is SyncIntent.RenameItem -> renameItem(intent)
            is SyncIntent.SetItemDescription -> setItemDescription(intent)
            is SyncIntent.SetItemSynonyms -> setItemSynonyms(intent)
            is SyncIntent.SetItemState -> setItemState(intent)
            is SyncIntent.SetItemAmount -> setItemAmount(intent)
            is SyncIntent.AddStore -> addStore(intent)
            is SyncIntent.DeleteStore -> deleteStore(intent, serverUlid)
            is SyncIntent.UpdateStore -> updateStore(intent)
            is SyncIntent.SetAvailability -> setAvailability(intent)
            is SyncIntent.SetItemOrder -> setItemOrder(intent)
            is SyncIntent.BulkAddItems -> intent.items.forEach { item ->
                apply(item, serverUlid)
            }

            is SyncIntent.BulkSetState -> intent.itemUuids.forEach { uuid ->
                apply(SyncIntent.SetItemState(uuid, intent.state, ""), serverUlid)
            }
        }
    }

    private suspend fun addItem(intent: SyncIntent.AddItem) {
        createMissingItem(
            itemUuid = intent.itemUuid,
            metadata = MissingItemMetadata(intent.name, intent.description, intent.synonyms)
        )
    }

    private suspend fun createMissingItem(itemUuid: String, metadata: MissingItemMetadata) {
        if (metadata.name.isBlank() || syncDao.isTombstoned(itemUuid) != null) return
        if (itemDao.getItemByUuid(itemUuid) != null) return

        val createdAt = if (Ulid.isValid(itemUuid)) {
            Ulid.timestamp(itemUuid)
        } else {
            System.currentTimeMillis()
        }
        val itemId = itemDao.insertItem(
            ItemEntity(
                uuid = itemUuid,
                name = metadata.name,
                description = metadata.description,
                synonyms = metadata.synonyms,
                createdAt = createdAt
            )
        )
        recordItemAdded(itemId, createdAt)
    }

    private suspend fun deleteItem(intent: SyncIntent.DeleteItem, serverUlid: String) {
        syncDao.insertTombstone(SyncTombstoneEntity(intent.itemUuid, serverUlid))
        val item = itemDao.getItemByUuid(intent.itemUuid) ?: return
        itemDao.deleteItemAddHistoryByItemId(item.id)
        itemDao.deleteItemById(item.id)
    }

    private suspend fun renameItem(intent: SyncIntent.RenameItem) {
        if (syncDao.isTombstoned(intent.itemUuid) != null) return
        val item = itemDao.getItemByUuid(intent.itemUuid)
        if (item == null) {
            createMissingItem(intent.itemUuid, MissingItemMetadata(name = intent.name))
        } else {
            itemDao.updateItem(item.copy(name = intent.name))
        }
    }

    private suspend fun setItemDescription(intent: SyncIntent.SetItemDescription) {
        if (syncDao.isTombstoned(intent.itemUuid) != null) return
        val item = itemDao.getItemByUuid(intent.itemUuid) ?: return
        itemDao.updateItem(item.copy(description = intent.description))
    }

    private suspend fun setItemSynonyms(intent: SyncIntent.SetItemSynonyms) {
        if (syncDao.isTombstoned(intent.itemUuid) != null) return
        val item = itemDao.getItemByUuid(intent.itemUuid) ?: return
        itemDao.updateItem(item.copy(synonyms = intent.synonyms))
    }

    private suspend fun setItemState(intent: SyncIntent.SetItemState) {
        if (syncDao.isTombstoned(intent.itemUuid) != null) return
        val item = itemDao.getItemByUuid(intent.itemUuid) ?: return

        val currentStateEntity = itemDao.getItemState(item.id)
        val currentState = currentStateEntity?.state ?: "ACTIVATED"

        val isConcurrent = intent.previousState.isNotEmpty() && intent.previousState != currentState
        if (isConcurrent) {
            val incomingPriority = statePriority[intent.state] ?: return
            val currentPriority = statePriority[currentState] ?: return
            if (incomingPriority >= currentPriority) return
        }

        val now = System.currentTimeMillis()
        val state = ItemState.fromString(intent.state)
        itemDao.setItemState(item.id, intent.state)
        historyRecorder.recordStateChange(
            itemId = item.id,
            previousState = ItemState.fromString(currentState),
            previousStateChangedAt = currentStateEntity?.stateChangedAt,
            state = state,
            at = now,
            settings = historySettings()
        )
    }

    private suspend fun recordItemAdded(itemId: Long, addedAt: Long) {
        historyRecorder.recordActivation(itemId, addedAt, historySettings())
    }

    private data class MissingItemMetadata(
        val name: String,
        val description: String = "",
        val synonyms: String = ""
    )

    private fun historySettings() = PurchaseHistorySettings(
        retentionDays = historyDaysProvider(),
        mistakeDoneSeconds = mistakeDoneSecondsProvider(),
        reactivateMinSeconds = reactivateMinSecondsProvider()
    )

    private suspend fun setItemAmount(intent: SyncIntent.SetItemAmount) {
        if (syncDao.isTombstoned(intent.itemUuid) != null) return
        val item = itemDao.getItemByUuid(intent.itemUuid) ?: return
        itemDao.updateItemAmount(item.id, intent.amount)
    }

    private suspend fun addStore(intent: SyncIntent.AddStore) {
        if (syncDao.isTombstoned(intent.storeUuid) != null) return
        if (storeDao.getStoreByUuid(intent.storeUuid) != null) return
        storeDao.insertStore(
            StoreEntity(uuid = intent.storeUuid, name = intent.name, specialtyStore = intent.specialty)
        )
    }

    private suspend fun deleteStore(intent: SyncIntent.DeleteStore, serverUlid: String) {
        syncDao.insertTombstone(SyncTombstoneEntity(intent.storeUuid, serverUlid))
        val store = storeDao.getStoreByUuid(intent.storeUuid) ?: return
        storeDao.deleteStoreWithRelations(store.id)
    }

    private suspend fun updateStore(intent: SyncIntent.UpdateStore) {
        if (syncDao.isTombstoned(intent.storeUuid) != null) return
        val store = storeDao.getStoreByUuid(intent.storeUuid) ?: return
        storeDao.updateStore(store.copy(name = intent.name, specialtyStore = intent.specialty))
    }

    private suspend fun setAvailability(intent: SyncIntent.SetAvailability) {
        if (syncDao.isTombstoned(intent.storeUuid) != null) return
        if (syncDao.isTombstoned(intent.itemUuid) != null) return
        val store = storeDao.getStoreByUuid(intent.storeUuid) ?: return
        val item = itemDao.getItemByUuid(intent.itemUuid) ?: return
        storeDao.setItemAvailability(store.id, item.id, intent.available)
    }

    private suspend fun setItemOrder(intent: SyncIntent.SetItemOrder) {
        if (syncDao.isTombstoned(intent.storeUuid) != null) return
        if (syncDao.isTombstoned(intent.itemUuid) != null) return
        val store = storeDao.getStoreByUuid(intent.storeUuid) ?: return
        val item = itemDao.getItemByUuid(intent.itemUuid) ?: return
        storeDao.insertOrUpdateItemOrder(
            StoreItemOrderEntity(storeId = store.id, itemId = item.id, sortOrder = intent.sortOrder)
        )
    }
}
