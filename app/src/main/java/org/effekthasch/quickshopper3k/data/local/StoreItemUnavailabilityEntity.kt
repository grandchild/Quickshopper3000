package org.effekthasch.quickshopper3k.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "store_item_unavailability", primaryKeys = ["store_id", "item_id"])
data class StoreItemUnavailabilityEntity(
    @ColumnInfo(name = "store_id") val storeId: Long,
    @ColumnInfo(name = "item_id") val itemId: Long
)
