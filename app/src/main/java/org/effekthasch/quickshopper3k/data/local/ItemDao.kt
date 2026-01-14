package org.effekthasch.quickshopper3k.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {
    @Query(
        """
        SELECT i.*, COALESCE(s.state, 'ACTIVATED') as state, o.sort_order
        FROM items i
        LEFT JOIN item_states s ON i.id = s.item_id
        LEFT JOIN store_item_order o ON i.id = o.item_id AND o.store_id = 0
        ORDER BY
            CASE WHEN o.sort_order IS NULL THEN 0 ELSE 1 END ASC,
            COALESCE(o.sort_order, 0) ASC,
            i.id ASC
    """
    )
    fun getAllItemsWithState(): Flow<List<ItemWithState>>

    @Query(
        """
        SELECT i.*, COALESCE(s.state, 'ACTIVATED') as state,
               COALESCE(shop_o.sort_order, default_o.sort_order) as sort_order,
               CASE WHEN u.item_id IS NULL THEN 1 ELSE 0 END as is_available
        FROM items i
        LEFT JOIN item_states s ON i.id = s.item_id
        LEFT JOIN store_item_order shop_o ON i.id = shop_o.item_id AND shop_o.store_id = :storeId
        LEFT JOIN store_item_order default_o ON i.id = default_o.item_id AND default_o.store_id = 0
        LEFT JOIN store_item_unavailability u ON i.id = u.item_id AND u.store_id = :storeId
        ORDER BY
            CASE WHEN shop_o.sort_order IS NULL THEN 0 ELSE 1 END ASC,
            COALESCE(shop_o.sort_order, default_o.sort_order, 0) ASC,
            i.id ASC
    """
    )
    fun getItemsForStore(storeId: Long): Flow<List<ItemWithStoreInfo>>

    @Query("SELECT * FROM items")
    suspend fun getAllItems(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE id = :itemId")
    suspend fun getItemById(itemId: Long): ItemEntity?

    @Insert
    suspend fun insertItem(item: ItemEntity): Long

    @Insert
    suspend fun insertItems(items: List<ItemEntity>): List<Long>

    @Update
    suspend fun updateItem(item: ItemEntity)

    @Delete
    suspend fun deleteItem(item: ItemEntity)

    @Query("DELETE FROM items WHERE id = :itemId")
    suspend fun deleteItemById(itemId: Long)

    @Query("SELECT * FROM item_states WHERE item_id = :itemId")
    suspend fun getItemState(itemId: Long): ItemStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateItemState(state: ItemStateEntity)

    @Query("DELETE FROM item_states WHERE item_id = :itemId")
    suspend fun deleteItemState(itemId: Long)

    @Transaction
    suspend fun setItemState(itemId: Long, state: String) {
        if (state == "ACTIVATED") {
            deleteItemState(itemId)
        } else {
            insertOrUpdateItemState(ItemStateEntity(itemId, state))
        }
    }

    @Transaction
    suspend fun bulkInsertItems(items: List<ItemEntity>): List<Long> {
        return insertItems(items)
    }

    @Query("DELETE FROM item_states")
    suspend fun deleteAllItemStates()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItemStates(states: List<ItemStateEntity>)

    @Transaction
    suspend fun setAllItemsToState(state: String) {
        if (state == "ACTIVATED") {
            deleteAllItemStates()
        } else {
            val allItems = getAllItems()
            val states = allItems.map { ItemStateEntity(it.id, state) }
            deleteAllItemStates()
            insertItemStates(states)
        }
    }
}

data class ItemWithState(
    val id: Long,
    val name: String,
    val created_at: Long,
    val state: String,
    val sort_order: Int?
)

data class ItemWithStoreInfo(
    val id: Long,
    val name: String,
    val created_at: Long,
    val state: String,
    val sort_order: Int?,
    val is_available: Int
)
