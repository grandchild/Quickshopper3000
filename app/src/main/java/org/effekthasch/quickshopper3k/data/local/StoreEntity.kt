package org.effekthasch.quickshopper3k.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "stores", indices = [Index(value = ["uuid"], unique = true)])
data class StoreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "uuid") val uuid: String,
    val name: String,
    val specialtyStore: Boolean = false
)
