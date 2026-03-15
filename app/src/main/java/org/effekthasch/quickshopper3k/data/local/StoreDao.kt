package org.effekthasch.quickshopper3k.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface StoreDao {
    @Query("SELECT * FROM stores ORDER BY name ASC")
    fun getAllStores(): Flow<List<StoreEntity>>

    @Query("SELECT * FROM stores WHERE id = :storeId")
    suspend fun getStoreById(storeId: Long): StoreEntity?

    @Query("SELECT uuid FROM stores WHERE id = :storeId")
    suspend fun getUuidById(storeId: Long): String?

    @Query("SELECT * FROM stores WHERE uuid = :uuid")
    suspend fun getStoreByUuid(uuid: String): StoreEntity?

    @Insert
    suspend fun insertStore(store: StoreEntity): Long

    @Update
    suspend fun updateStore(store: StoreEntity)

    @Delete
    suspend fun deleteStore(store: StoreEntity)

    @Query("DELETE FROM stores WHERE id = :storeId")
    suspend fun deleteStoreById(storeId: Long)

    @Query("SELECT * FROM store_item_unavailability WHERE store_id = :storeId")
    suspend fun getUnavailableItemsForStore(storeId: Long): List<StoreItemUnavailabilityEntity>

    @Query("SELECT * FROM store_item_order WHERE store_id = :storeId ORDER BY sort_order ASC")
    suspend fun getItemOrderForStore(storeId: Long): List<StoreItemOrderEntity>

    @Query("SELECT MAX(sort_order) FROM store_item_order WHERE store_id = :storeId")
    suspend fun getMaxSortOrderForStore(storeId: Long): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateItemUnavailability(unavailability: StoreItemUnavailabilityEntity)

    @Query("DELETE FROM store_item_unavailability WHERE store_id = :storeId AND item_id = :itemId")
    suspend fun deleteItemUnavailability(storeId: Long, itemId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateItemOrder(order: StoreItemOrderEntity)

    @Query("DELETE FROM store_item_order WHERE store_id = :storeId AND item_id = :itemId")
    suspend fun deleteItemOrder(storeId: Long, itemId: Long)

    @Transaction
    suspend fun setItemAvailability(storeId: Long, itemId: Long, isAvailable: Boolean) {
        if (isAvailable) {
            deleteItemUnavailability(storeId, itemId)
        } else {
            insertOrUpdateItemUnavailability(StoreItemUnavailabilityEntity(storeId, itemId))
        }
    }

    @Transaction
    suspend fun updateStoreItemOrders(storeId: Long, orders: List<Pair<Long, Int>>) {
        orders.forEach { (itemId, sortOrder) ->
            insertOrUpdateItemOrder(StoreItemOrderEntity(storeId, itemId, sortOrder))
        }
    }

    @Transaction
    suspend fun deleteStoreWithRelations(storeId: Long) {
        deleteStoreById(storeId)
    }
}
