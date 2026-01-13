package org.effekthasch.quickshopper3k.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "items")
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name", collate = ColumnInfo.NOCASE) val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
