package org.effekthasch.quickshopper3k.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_tombstones")
data class SyncTombstoneEntity(
    @PrimaryKey @ColumnInfo(name = "entity_uuid") val entityUuid: String,
    @ColumnInfo(name = "deleted_at_ulid") val deletedAtUlid: String
)
