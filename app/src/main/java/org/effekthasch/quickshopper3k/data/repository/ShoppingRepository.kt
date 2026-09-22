package org.effekthasch.quickshopper3k.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.effekthasch.quickshopper3k.data.history.PurchaseHistorySettings
import org.effekthasch.quickshopper3k.data.history.PurchaseHistoryRecorder
import org.effekthasch.quickshopper3k.data.history.RoomPurchaseHistoryRecorder
import org.effekthasch.quickshopper3k.data.local.AppDatabase
import org.effekthasch.quickshopper3k.data.local.ItemEntity
import org.effekthasch.quickshopper3k.data.local.StoreEntity
import org.effekthasch.quickshopper3k.data.sync.IntentEmitter
import org.effekthasch.quickshopper3k.data.sync.SyncIntent
import org.effekthasch.quickshopper3k.domain.model.Item
import org.effekthasch.quickshopper3k.domain.statistics.PurchaseHistory
import org.effekthasch.quickshopper3k.domain.model.ItemInput
import org.effekthasch.quickshopper3k.domain.model.ItemState
import org.effekthasch.quickshopper3k.domain.model.ItemText
import org.effekthasch.quickshopper3k.domain.model.Store
import org.effekthasch.quickshopper3k.domain.model.StoreItem
import org.effekthasch.quickshopper3k.util.Ulid

class ShoppingRepository(private val database: AppDatabase) {
    private val itemDao = database.itemDao()
    private val storeDao = database.storeDao()
    private val historyRecorder: PurchaseHistoryRecorder = RoomPurchaseHistoryRecorder(itemDao)
    private val emitter = IntentEmitter(database.syncDao())

    companion object {
        const val DEFAULT_STORE_ID = 0L
        const val DEFAULT_HISTORY_DAYS = 1095
        const val DEFAULT_MISTAKE_DONE_SECONDS = 20
        const val DEFAULT_REACTIVATE_MIN_SECONDS = 3600
    }

    fun getAllItems(): Flow<List<Item>> {
        return itemDao.getAllItemsWithState().map { items ->
            items.map { itemWithState ->
                Item(
                    id = itemWithState.id,
                    uuid = itemWithState.uuid,
                    name = itemWithState.name,
                    description = itemWithState.description,
                    synonyms = itemWithState.synonyms,
                    state = ItemState.fromString(itemWithState.state),
                    createdAt = itemWithState.created_at,
                    stateChangedAt = itemWithState.state_changed_at,
                    amount = itemWithState.amount
                )
            }
        }
    }

    fun getPurchaseHistories(): Flow<List<PurchaseHistory>> {
        return combine(itemDao.getAllItemsFlow(), itemDao.getAllItemAddHistory()) { items, history ->
            val historyByItemId = history.groupBy { it.itemId }
            items.map { item ->
                val itemHistory = historyByItemId[item.id].orEmpty()
                val firstHistoryAt = itemHistory.firstOrNull()?.addedAt
                val observedFrom = if (
                    firstHistoryAt != null && firstHistoryAt - item.createdAt > 24 * 60 * 60 * 1000L
                ) {
                    firstHistoryAt
                } else {
                    item.createdAt
                }
                PurchaseHistory(
                    itemId = item.id,
                    observedFrom = observedFrom,
                    activations = itemHistory.map { it.addedAt }
                )
            }
        }
    }

    suspend fun getAllItemInputs(): Result<List<ItemInput>> {
        return try {
            Result.success(itemDao.getAllItems().map { ItemInput(it.name, it.description) })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getItemIdsByIdentity(inputs: List<ItemInput>): Result<List<Long>> {
        return try {
            val identities = inputs.map(ItemText::identity).toSet()
            val ids = itemDao.getAllItems()
                .filter { ItemText.identity(ItemInput(it.name, it.description)) in identities }
                .map { it.id }
            Result.success(ids)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getItemsForStore(storeId: Long): Flow<List<StoreItem>> {
        return itemDao.getItemsForStore(storeId).map { items ->
            items.map { itemWithStoreInfo ->
                StoreItem(
                    item = Item(
                        id = itemWithStoreInfo.id,
                        uuid = itemWithStoreInfo.uuid,
                        name = itemWithStoreInfo.name,
                        description = itemWithStoreInfo.description,
                        synonyms = itemWithStoreInfo.synonyms,
                        state = ItemState.fromString(itemWithStoreInfo.state),
                        createdAt = itemWithStoreInfo.created_at,
                        stateChangedAt = itemWithStoreInfo.state_changed_at,
                        amount = itemWithStoreInfo.amount
                    ),
                    sortOrder = itemWithStoreInfo.sort_order,
                    isAvailable = itemWithStoreInfo.is_available == 1
                )
            }
        }
    }

    suspend fun addItem(
        name: String,
        historyDays: Int = DEFAULT_HISTORY_DAYS
    ): Result<Long> {
        return try {
            val now = System.currentTimeMillis()
            val uuid = Ulid.generate()
            val id = itemDao.insertItem(
                ItemEntity(
                    uuid = uuid,
                    name = name,
                    createdAt = now
                )
            )

            val maxOrder = storeDao.getMaxSortOrderForStore(DEFAULT_STORE_ID) ?: 0
            storeDao.insertOrUpdateItemOrder(
                org.effekthasch.quickshopper3k.data.local.StoreItemOrderEntity(
                    storeId = DEFAULT_STORE_ID,
                    itemId = id,
                    sortOrder = maxOrder + 1
                )
            )

            recordItemAdded(id, now, historyDays)

            emitter.emit(SyncIntent.AddItem(uuid, name))

            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateItemName(itemId: Long, newName: String): Result<Unit> {
        return try {
            val item = itemDao.getItemById(itemId)
                ?: return Result.failure(IllegalArgumentException("Item not found"))
            updateItem(itemId, newName, item.description, item.synonyms)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateItem(
        itemId: Long,
        name: String,
        description: String,
        synonyms: String
    ): Result<Unit> {
        return try {
            val item = itemDao.getItemById(itemId)
                ?: return Result.failure(IllegalArgumentException("Item not found"))
            itemDao.updateItem(
                item.copy(
                    name = name,
                    description = description,
                    synonyms = synonyms
                )
            )
            if (item.name != name) {
                emitter.emit(SyncIntent.RenameItem(item.uuid, name))
            }
            if (item.description != description) {
                emitter.emit(SyncIntent.SetItemDescription(item.uuid, description))
            }
            if (item.synonyms != synonyms) {
                emitter.emit(SyncIntent.SetItemSynonyms(item.uuid, synonyms))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun recordPurchase(
        itemId: Long,
        historyDays: Int = DEFAULT_HISTORY_DAYS
    ): Result<Unit> {
        return try {
            if (itemDao.getItemById(itemId) == null) {
                return Result.failure(IllegalArgumentException("Item not found"))
            }
            recordItemAdded(itemId, System.currentTimeMillis(), historyDays)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteItem(itemId: Long): Result<Unit> {
        return try {
            val uuid = itemDao.getUuidById(itemId)
            itemDao.deleteItemAddHistoryByItemId(itemId)
            itemDao.deleteItemById(itemId)
            if (uuid != null) {
                emitter.emit(SyncIntent.DeleteItem(uuid))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setItemState(
        itemId: Long,
        state: ItemState,
        historyDays: Int = DEFAULT_HISTORY_DAYS,
        mistakeDoneSeconds: Int = DEFAULT_MISTAKE_DONE_SECONDS,
        reactivateMinSeconds: Int = DEFAULT_REACTIVATE_MIN_SECONDS
    ): Result<Unit> {
        return try {
            val now = System.currentTimeMillis()
            val existingState = itemDao.getItemState(itemId)
            val previousState = existingState?.state ?: ItemState.ACTIVATED.toString()

            itemDao.setItemState(itemId, state.toString())
            historyRecorder.recordStateChange(
                itemId = itemId,
                previousState = ItemState.fromString(previousState),
                previousStateChangedAt = existingState?.stateChangedAt,
                state = state,
                at = now,
                settings = PurchaseHistorySettings(
                    retentionDays = historyDays,
                    mistakeDoneSeconds = mistakeDoneSeconds,
                    reactivateMinSeconds = reactivateMinSeconds
                )
            )

            val uuid = itemDao.getUuidById(itemId)
            if (uuid != null) {
                emitter.emit(SyncIntent.SetItemState(uuid, state.toString(), previousState))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun recordItemAdded(itemId: Long, addedAt: Long, historyDays: Int) {
        historyRecorder.recordActivation(
            itemId,
            addedAt,
            PurchaseHistorySettings(
                retentionDays = historyDays,
                mistakeDoneSeconds = DEFAULT_MISTAKE_DONE_SECONDS,
                reactivateMinSeconds = DEFAULT_REACTIVATE_MIN_SECONDS
            )
        )
    }

    fun getAllStores(): Flow<List<Store>> {
        return storeDao.getAllStores().map { stores ->
            stores.map { Store(it.id, it.uuid, it.name, it.specialtyStore) }
        }
    }

    suspend fun getStoreById(storeId: Long): Store? {
        return storeDao.getStoreById(storeId)?.let { Store(it.id, it.uuid, it.name, it.specialtyStore) }
    }

    suspend fun addStore(name: String, specialtyStore: Boolean = false): Result<Long> {
        return try {
            val uuid = Ulid.generate()
            val id = storeDao.insertStore(
                StoreEntity(
                    uuid = uuid,
                    name = name,
                    specialtyStore = specialtyStore
                )
            )

            if (specialtyStore) {
                val allItems = itemDao.getAllItems()
                allItems.forEach { item ->
                    storeDao.setItemAvailability(id, item.id, false)
                }
            }

            emitter.emit(SyncIntent.AddStore(uuid, name, specialtyStore))

            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateStoreName(storeId: Long, newName: String): Result<Unit> {
        return try {
            val store = storeDao.getStoreById(storeId)
            if (store != null) {
                storeDao.updateStore(store.copy(name = newName))
                emitter.emit(SyncIntent.UpdateStore(store.uuid, newName, store.specialtyStore))
                Result.success(Unit)
            } else {
                Result.failure(IllegalArgumentException("Store not found"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateStore(storeId: Long, newName: String, specialtyStore: Boolean): Result<Unit> {
        return try {
            val store = storeDao.getStoreById(storeId)
            if (store != null) {
                storeDao.updateStore(store.copy(name = newName, specialtyStore = specialtyStore))
                emitter.emit(SyncIntent.UpdateStore(store.uuid, newName, specialtyStore))
                Result.success(Unit)
            } else {
                Result.failure(IllegalArgumentException("Store not found"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteStore(storeId: Long): Result<Unit> {
        return try {
            val uuid = storeDao.getUuidById(storeId)
            storeDao.deleteStoreWithRelations(storeId)
            if (uuid != null) {
                emitter.emit(SyncIntent.DeleteStore(uuid))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateItemAmount(itemId: Long, amount: Int): Result<Unit> {
        return try {
            itemDao.updateItemAmount(itemId, amount)
            val uuid = itemDao.getUuidById(itemId)
            if (uuid != null) {
                emitter.emit(SyncIntent.SetItemAmount(uuid, amount))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun resetStaleAmounts(cutoffMs: Long): Result<Unit> {
        return try {
            val ids = itemDao.findStaleDoneItemIdsWithNonDefaultAmount(cutoffMs)
            if (ids.isEmpty()) return Result.success(Unit)

            itemDao.resetAmountsByIds(ids)

            val uuids = itemDao.getUuidsByIds(ids)
            uuids.forEach { uuid ->
                emitter.emit(SyncIntent.SetItemAmount(uuid, 1))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setItemAvailability(storeId: Long, itemId: Long, isAvailable: Boolean): Result<Unit> {
        return try {
            storeDao.setItemAvailability(storeId, itemId, isAvailable)
            val storeUuid = storeDao.getUuidById(storeId)
            val itemUuid = itemDao.getUuidById(itemId)
            if (storeUuid != null && itemUuid != null) {
                emitter.emit(SyncIntent.SetAvailability(storeUuid, itemUuid, isAvailable))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateStoreItemOrders(storeId: Long, orders: List<Pair<Long, Int>>): Result<Unit> {
        return try {
            storeDao.updateStoreItemOrders(storeId, orders)
            val storeUuid = storeDao.getUuidById(storeId) ?: return Result.success(Unit)
            orders.forEach { (itemId, sortOrder) ->
                val itemUuid = itemDao.getUuidById(itemId)
                if (itemUuid != null) {
                    emitter.emit(SyncIntent.SetItemOrder(storeUuid, itemUuid, sortOrder))
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun bulkAddItems(
        items: List<ItemInput>,
        historyDays: Int = DEFAULT_HISTORY_DAYS
    ): Result<Int> {
        return try {
            val timestamp = System.currentTimeMillis()
            val uuids = items.map { Ulid.generate() }
            val entities = items.mapIndexed { index, item ->
                ItemEntity(
                    uuid = uuids[index],
                    name = item.name,
                    description = item.description,
                    createdAt = timestamp
                )
            }

            val ids = itemDao.bulkInsertItems(entities)

            val maxOrder = storeDao.getMaxSortOrderForStore(DEFAULT_STORE_ID) ?: 0
            val orderEntities = ids.mapIndexed { index, id ->
                org.effekthasch.quickshopper3k.data.local.StoreItemOrderEntity(
                    storeId = DEFAULT_STORE_ID,
                    itemId = id,
                    sortOrder = maxOrder + index + 1
                )
            }

            orderEntities.forEach { orderEntity ->
                storeDao.insertOrUpdateItemOrder(orderEntity)
            }

            ids.forEach { id ->
                recordItemAdded(id, timestamp, historyDays)
            }

            emitter.emit(
                SyncIntent.BulkAddItems(
                    uuids.zip(items).map { (uuid, item) ->
                        SyncIntent.AddItem(uuid, item.name, item.description)
                    }
                )
            )

            Result.success(ids.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setAllItemsToState(
        state: ItemState,
        historyDays: Int = DEFAULT_HISTORY_DAYS,
        mistakeDoneSeconds: Int = DEFAULT_MISTAKE_DONE_SECONDS,
        reactivateMinSeconds: Int = DEFAULT_REACTIVATE_MIN_SECONDS
    ): Result<Unit> {
        return try {
            val now = System.currentTimeMillis()
            val allItems = itemDao.getAllItems()
            val allStatesMap = itemDao.getAllItemStates().associateBy { it.itemId }
            val settings = PurchaseHistorySettings(
                retentionDays = historyDays,
                mistakeDoneSeconds = mistakeDoneSeconds,
                reactivateMinSeconds = reactivateMinSeconds
            )

            itemDao.setAllItemsToState(state.toString())
            allItems.forEach { item ->
                val previousState = allStatesMap[item.id]
                historyRecorder.recordStateChange(
                    itemId = item.id,
                    previousState = ItemState.fromString(previousState?.state ?: ItemState.ACTIVATED.toString()),
                    previousStateChangedAt = previousState?.stateChangedAt,
                    state = state,
                    at = now,
                    settings = settings
                )
            }

            val uuids = allItems.map { it.uuid }
            if (uuids.isNotEmpty()) {
                emitter.emit(SyncIntent.BulkSetState(uuids, state.toString()))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun activateAllPostponed(): Result<Unit> {
        return try {
            val postponedStates = itemDao.getAllPostponedStates()
            if (postponedStates.isEmpty()) return Result.success(Unit)

            val postponedItemIds = postponedStates.map { it.itemId }
            itemDao.deleteAllPostponedStates()

            val uuids = itemDao.getUuidsByIds(postponedItemIds)
            if (uuids.isNotEmpty()) {
                emitter.emit(SyncIntent.BulkSetState(uuids, ItemState.ACTIVATED.toString()))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
