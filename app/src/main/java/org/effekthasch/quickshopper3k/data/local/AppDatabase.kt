package org.effekthasch.quickshopper3k.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.effekthasch.quickshopper3k.util.Ulid

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE stores ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")

        val itemCursor = db.query("SELECT id FROM items")
        while (itemCursor.moveToNext()) {
            val id = itemCursor.getLong(0)
            db.execSQL("UPDATE items SET uuid = ? WHERE id = ?", arrayOf<Any>(Ulid.generate(), id))
        }
        itemCursor.close()

        val storeCursor = db.query("SELECT id FROM stores")
        while (storeCursor.moveToNext()) {
            val id = storeCursor.getLong(0)
            db.execSQL("UPDATE stores SET uuid = ? WHERE id = ?", arrayOf<Any>(Ulid.generate(), id))
        }
        storeCursor.close()

        db.execSQL("CREATE UNIQUE INDEX index_items_uuid ON items(uuid)")
        db.execSQL("CREATE UNIQUE INDEX index_stores_uuid ON stores(uuid)")

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS sync_intent_queue (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                ulid TEXT NOT NULL,
                intent_json TEXT NOT NULL,
                synced INTEGER NOT NULL DEFAULT 0
            )"""
        )

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS sync_state (
                key TEXT NOT NULL PRIMARY KEY,
                value TEXT NOT NULL
            )"""
        )

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS sync_tombstones (
                entity_uuid TEXT NOT NULL PRIMARY KEY,
                deleted_at_ulid TEXT NOT NULL
            )"""
        )
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS sync_tombstones (
                entity_uuid TEXT NOT NULL PRIMARY KEY,
                deleted_at_ulid TEXT NOT NULL
            )"""
        )
    }
}

@Database(
    entities = [
        ItemEntity::class,
        ItemStateEntity::class,
        StoreEntity::class,
        StoreItemUnavailabilityEntity::class,
        StoreItemOrderEntity::class,
        ItemAddHistoryEntity::class,
        SyncIntentQueueEntity::class,
        SyncStateEntity::class,
        SyncTombstoneEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun storeDao(): StoreDao
    abstract fun syncDao(): SyncDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "quickshopper3k_database"
                )
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        fun closeDatabase() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
