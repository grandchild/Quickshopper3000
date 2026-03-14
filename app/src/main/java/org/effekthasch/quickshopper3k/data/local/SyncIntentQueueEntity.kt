package org.effekthasch.quickshopper3k.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_intent_queue")
data class SyncIntentQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "ulid") val ulid: String,
    @ColumnInfo(name = "intent_json") val intentJson: String,
    @ColumnInfo(name = "synced", defaultValue = "0") val synced: Int = 0
)
