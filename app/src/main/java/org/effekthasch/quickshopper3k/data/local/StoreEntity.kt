package org.effekthasch.quickshopper3k.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stores")
data class StoreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)
