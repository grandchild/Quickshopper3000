package org.effekthasch.quickshopper3k.data.sync

import org.effekthasch.quickshopper3k.data.local.ItemDao
import org.effekthasch.quickshopper3k.data.local.ItemEntity
import org.effekthasch.quickshopper3k.data.local.StoreDao
import org.effekthasch.quickshopper3k.data.local.StoreEntity
import org.effekthasch.quickshopper3k.data.local.StoreItemOrderEntity
import org.effekthasch.quickshopper3k.data.local.SyncDao
import org.effekthasch.quickshopper3k.data.local.SyncTombstoneEntity
import org.json.JSONArray
import org.json.JSONObject

class SnapshotApplier(
    private val itemDao: ItemDao,
    private val storeDao: StoreDao,
    private val syncDao: SyncDao,
) {
    suspend fun apply(snapshot: JSONObject) {
        val items = snapshot.optJSONArray("items")
        for (i in 0 until (items?.length() ?: 0)) {
            val obj = items!!.getJSONObject(i)
            val uuid = obj.getString("item_uuid")
            val existing = itemDao.getItemByUuid(uuid)
            val snapshotItem = ItemEntity(
                id = existing?.id ?: 0,
                uuid = uuid,
                name = obj.getString("name"),
                description = obj.optString("description"),
                synonyms = obj.optString("synonyms"),
                createdAt = obj.getLong("created_at"),
                amount = obj.getInt("amount")
            )
            val id = if (existing == null) {
                itemDao.insertItem(snapshotItem)
            } else {
                itemDao.updateItem(snapshotItem)
                existing.id
            }
            val state = obj.getString("state")
            if (state != "ACTIVATED") itemDao.setItemState(id, state)

        }

        val stores = snapshot.optJSONArray("stores")
        for (i in 0 until (stores?.length() ?: 0)) {
            val obj = stores!!.getJSONObject(i)
            val uuid = obj.getString("store_uuid")
            if (storeDao.getStoreByUuid(uuid) == null)
                storeDao.insertStore(
                    StoreEntity(
                        uuid = uuid,
                        name = obj.getString("name"),
                        specialtyStore = obj.getBoolean("specialty")
                    )
                )
        }

        val availability = snapshot.optJSONArray("availability")
        for (i in 0 until (availability?.length() ?: 0)) {
            val obj = availability!!.getJSONObject(i)
            val store = storeDao.getStoreByUuid(obj.getString("store_uuid")) ?: continue
            val item = itemDao.getItemByUuid(obj.getString("item_uuid")) ?: continue
            storeDao.setItemAvailability(store.id, item.id, obj.getBoolean("available"))
        }

        val itemOrder = snapshot.optJSONArray("item_order")
        for (i in 0 until (itemOrder?.length() ?: 0)) {
            val obj = itemOrder!!.getJSONObject(i)
            val store = storeDao.getStoreByUuid(obj.getString("store_uuid")) ?: continue
            val item = itemDao.getItemByUuid(obj.getString("item_uuid")) ?: continue
            storeDao.insertOrUpdateItemOrder(
                StoreItemOrderEntity(storeId = store.id, itemId = item.id, sortOrder = obj.getInt("sort_order"))
            )
        }

        for (arr in listOf(snapshot.optJSONArray("deleted_items"), snapshot.optJSONArray("deleted_stores"))) {
            for (i in 0 until (arr?.length() ?: 0)) {
                val uuid = arr!!.getString(i)
                syncDao.insertTombstone(SyncTombstoneEntity(entityUuid = uuid, deletedAtUlid = "snapshot"))
                val item = itemDao.getItemByUuid(uuid)
                if (item != null) {
                    itemDao.deleteItemAddHistoryByItemId(item.id)
                    itemDao.deleteItemById(item.id)
                }
                val store = storeDao.getStoreByUuid(uuid)
                if (store != null) storeDao.deleteStoreWithRelations(store.id)
            }
        }
    }

    suspend fun parseResponse(body: String): Pair<JSONArray, String?> {
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("{")) return JSONArray(body) to null
        val obj = JSONObject(trimmed)
        obj.optJSONObject("snapshot")?.let { apply(it) }
        val snapshotUlid = obj.optString("snapshot_ulid", "").ifEmpty { null }
        return (obj.optJSONArray("intents") ?: JSONArray()) to snapshotUlid
    }
}
