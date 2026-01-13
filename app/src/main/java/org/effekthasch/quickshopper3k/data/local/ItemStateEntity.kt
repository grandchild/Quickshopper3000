package org.effekthasch.quickshopper3k.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "item_states", primaryKeys = ["item_id"])
data class ItemStateEntity(
    @ColumnInfo(name = "item_id") val itemId: Long,
    val state: String
)
