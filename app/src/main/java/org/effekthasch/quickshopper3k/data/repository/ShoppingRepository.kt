package org.effekthasch.quickshopper3k.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.effekthasch.quickshopper3k.data.local.AppDatabase
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
    }

    fun getAllItems(): Flow<List<Item>> {
        return itemDao.getAllItemsWithState().map { items ->
            items.map { itemWithState ->
                Item(
                    id = itemWithState.id,
                    name = itemWithState.name,
                    state = ItemState.fromString(itemWithState.state),
                    createdAt = itemWithState.created_at
                )
            }
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
                        createdAt = itemWithStoreInfo.created_at
                    ),
                    sortOrder = itemWithStoreInfo.sort_order,
                    isAvailable = itemWithStoreInfo.is_available == 1
                )
            }
        }
    }

    suspend fun addItem(name: String): Result<Long> {
        return try {
            val id = itemDao.insertItem(
                ItemEntity(
                    name = name,
                    createdAt = System.currentTimeMillis()
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
            itemDao.deleteItemById(itemId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setItemState(itemId: Long, state: ItemState): Result<Unit> {
        return try {
            itemDao.setItemState(itemId, state.toString())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
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
}
