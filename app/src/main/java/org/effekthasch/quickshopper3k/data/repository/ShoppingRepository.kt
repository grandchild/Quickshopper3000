package org.effekthasch.quickshopper3k.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.effekthasch.quickshopper3k.data.local.AppDatabase
import org.effekthasch.quickshopper3k.data.local.ItemAddHistoryEntity
import org.effekthasch.quickshopper3k.data.local.ItemEntity
import org.effekthasch.quickshopper3k.data.local.StoreEntity
import org.effekthasch.quickshopper3k.domain.model.Item
import org.effekthasch.quickshopper3k.domain.model.ItemState
import org.effekthasch.quickshopper3k.domain.model.Store
import org.effekthasch.quickshopper3k.domain.model.StoreItem

class ShoppingRepository(private val database: AppDatabase) {
    private val itemDao = database.itemDao()
    private val storeDao = database.storeDao()

    companion object {
        const val DEFAULT_STORE_ID = 0L
        const val DEFAULT_HISTORY_DAYS = 183
        const val DEFAULT_MISTAKE_DONE_SECONDS = 20
        const val DEFAULT_REACTIVATE_MIN_SECONDS = 3600
    }

    fun getAllItems(): Flow<List<Item>> {
        return itemDao.getAllItemsWithState().map { items ->
            items.map { itemWithState ->
                Item(
                    id = itemWithState.id,
                    name = itemWithState.name,
                    state = ItemState.fromString(itemWithState.state),
                    createdAt = itemWithState.created_at,
                    stateChangedAt = itemWithState.state_changed_at
                )
            }
        }
    }

    suspend fun getAllItemNames(): Result<List<String>> {
        return try {
            Result.success(itemDao.getAllItemNames())
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
                        name = itemWithStoreInfo.name,
                        state = ItemState.fromString(itemWithStoreInfo.state),
                        createdAt = itemWithStoreInfo.created_at,
                        stateChangedAt = itemWithStoreInfo.state_changed_at
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
            val id = itemDao.insertItem(
                ItemEntity(
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

            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateItemName(itemId: Long, newName: String): Result<Unit> {
        return try {
            val item = itemDao.getItemById(itemId)
            if (item != null) {
                itemDao.updateItem(item.copy(name = newName))
                Result.success(Unit)
            } else {
                Result.failure(IllegalArgumentException("Item not found"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteItem(itemId: Long): Result<Unit> {
        return try {
            itemDao.deleteItemAddHistoryByItemId(itemId)
            itemDao.deleteItemById(itemId)
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
            when (state) {
                ItemState.ACTIVATED -> {
                    val existingState = itemDao.getItemState(itemId)
                    val lastDoneAt = if (existingState?.state == ItemState.DEACTIVATED.toString()) {
                        existingState.stateChangedAt
                    } else {
                        null
                    }

                    itemDao.setItemState(itemId, state.toString())

                    val shouldRecord = existingState?.state == ItemState.DEACTIVATED.toString() &&
                            (lastDoneAt == null || now - lastDoneAt > reactivateMinSeconds * 1000L)

                    if (shouldRecord) {
                        recordItemAdded(itemId, now, historyDays)
                    }
                }

                ItemState.DEACTIVATED -> {
                    itemDao.setItemState(itemId, state.toString())
                    val latestHistory = itemDao.getLatestItemAddHistory(itemId)
                    val mistakeThresholdMs = mistakeDoneSeconds * 1000L
                    if (latestHistory != null && now - latestHistory.addedAt <= mistakeThresholdMs) {
                        itemDao.deleteItemAddHistoryById(latestHistory.id)
                    }
                }

                ItemState.POSTPONED -> {
                    itemDao.setItemState(itemId, state.toString())
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun recordItemAdded(itemId: Long, addedAt: Long, historyDays: Int) {
        itemDao.insertItemAddHistory(ItemAddHistoryEntity(itemId = itemId, addedAt = addedAt))
        val safeDays = if (historyDays <= 0) 1 else historyDays
        val cutoff = addedAt - safeDays * 24 * 60 * 60 * 1000L
        itemDao.deleteItemAddHistoryOlderThan(cutoff)
    }

    fun getAllStores(): Flow<List<Store>> {
        return storeDao.getAllStores().map { stores ->
            stores.map { Store(it.id, it.name, it.specialtyStore) }
        }
    }

    suspend fun getStoreById(storeId: Long): Store? {
        return storeDao.getStoreById(storeId)?.let { Store(it.id, it.name, it.specialtyStore) }
    }

    suspend fun addStore(name: String, specialtyStore: Boolean = false): Result<Long> {
        return try {
            val id = storeDao.insertStore(StoreEntity(name = name, specialtyStore = specialtyStore))

            if (specialtyStore) {
                val allItems = itemDao.getAllItems()
                allItems.forEach { item ->
                    storeDao.setItemAvailability(id, item.id, false)
                }
            }

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
            storeDao.deleteStoreWithRelations(storeId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setItemAvailability(storeId: Long, itemId: Long, isAvailable: Boolean): Result<Unit> {
        return try {
            storeDao.setItemAvailability(storeId, itemId, isAvailable)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateStoreItemOrders(storeId: Long, orders: List<Pair<Long, Int>>): Result<Unit> {
        return try {
            storeDao.updateStoreItemOrders(storeId, orders)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun bulkAddItems(
        names: List<String>,
        historyDays: Int = DEFAULT_HISTORY_DAYS
    ): Result<Int> {
        return try {
            val timestamp = System.currentTimeMillis()
            val entities = names.map { name ->
                ItemEntity(
                    name = name,
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
            when (state) {
                ItemState.ACTIVATED -> {
                    allItems.forEach { item ->
                        val existingState = itemDao.getItemState(item.id)
                        val lastDoneAt = if (existingState?.state == ItemState.DEACTIVATED.toString()) {
                            existingState.stateChangedAt
                        } else {
                            null
                        }

                        itemDao.setItemState(item.id, state.toString())

                        val shouldRecord = existingState?.state == ItemState.DEACTIVATED.toString() &&
                                (lastDoneAt == null || now - lastDoneAt > reactivateMinSeconds * 1000L)

                        if (shouldRecord) {
                            recordItemAdded(item.id, now, historyDays)
                        }
                    }
                }

                ItemState.DEACTIVATED -> {
                    val mistakeThresholdMs = mistakeDoneSeconds * 1000L
                    allItems.forEach { item ->
                        itemDao.setItemState(item.id, state.toString())
                        val latestHistory = itemDao.getLatestItemAddHistory(item.id)
                        if (latestHistory != null && now - latestHistory.addedAt <= mistakeThresholdMs) {
                            itemDao.deleteItemAddHistoryById(latestHistory.id)
                        }
                    }
                }

                ItemState.POSTPONED -> {
                    itemDao.setAllItemsToState(state.toString())
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
