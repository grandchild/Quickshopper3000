package org.effekthasch.quickshopper3k.data.local

import androidx.room.*

@Dao
interface SyncDao {
    @Insert
    suspend fun enqueueIntent(intent: SyncIntentQueueEntity): Long

    @Query("SELECT * FROM sync_intent_queue WHERE synced = 0 ORDER BY id ASC")
    suspend fun getUnsynced(): List<SyncIntentQueueEntity>

    @Query("UPDATE sync_intent_queue SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("DELETE FROM sync_intent_queue WHERE synced = 1")
    suspend fun purgeSynced()

    @Query("SELECT value FROM sync_state WHERE key = :key")
    suspend fun getSyncState(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSyncState(state: SyncStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstone(tombstone: SyncTombstoneEntity)

    @Query("SELECT 1 FROM sync_tombstones WHERE entity_uuid = :entityUuid LIMIT 1")
    suspend fun isTombstoned(entityUuid: String): Int?

    @Query("DELETE FROM sync_tombstones WHERE entity_uuid IN (:entityUuids)")
    suspend fun deleteTombstones(entityUuids: List<String>)
}
