package org.effekthasch.quickshopper3k.util

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.effekthasch.quickshopper3k.data.local.AppDatabase
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupManager(private val context: Context) {

    suspend fun exportBackup(): Result<File> = withContext(Dispatchers.IO) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US).format(Date())
            val backupFileName = "qs3k-$timestamp.backup"
            val backupFile = File(context.getExternalFilesDir(null), backupFileName)

            ZipOutputStream(FileOutputStream(backupFile)).use { zip ->
                val dbFile = context.getDatabasePath("quickshopper3k_database")
                if (dbFile.exists()) {
                    zip.putNextEntry(ZipEntry("database.db"))
                    FileInputStream(dbFile).use { input ->
                        input.copyTo(zip)
                    }
                    zip.closeEntry()

                    val shmFile = File(dbFile.absolutePath + "-shm")
                    if (shmFile.exists()) {
                        zip.putNextEntry(ZipEntry("database.db-shm"))
                        FileInputStream(shmFile).use { input ->
                            input.copyTo(zip)
                        }
                        zip.closeEntry()
                    }

                    val walFile = File(dbFile.absolutePath + "-wal")
                    if (walFile.exists()) {
                        zip.putNextEntry(ZipEntry("database.db-wal"))
                        FileInputStream(walFile).use { input ->
                            input.copyTo(zip)
                        }
                        zip.closeEntry()
                    }
                }

                val settingsJson = exportSettings()
                zip.putNextEntry(ZipEntry("settings.json"))
                zip.write(settingsJson.toByteArray())
                zip.closeEntry()
            }

            Result.success(backupFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun importBackup(backupFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val tempDir = File(context.cacheDir, "backup_temp")
            tempDir.mkdirs()

            ZipInputStream(FileInputStream(backupFile)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val file = File(tempDir, entry.name)
                    if (!entry.isDirectory) {
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { output ->
                            zip.copyTo(output)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val dbFile = File(tempDir, "database.db")
            val settingsFile = File(tempDir, "settings.json")

            if (!dbFile.exists() || !settingsFile.exists()) {
                tempDir.deleteRecursively()
                return@withContext Result.failure(IllegalArgumentException("Invalid backup file"))
            }

            val dbVersion = readDatabaseUserVersion(dbFile)
            if (dbVersion != null && dbVersion !in 7..8) {
                tempDir.deleteRecursively()
                return@withContext Result.failure(LegacyBackupException())
            }

            AppDatabase.closeDatabase()
            val targetDbFile = context.getDatabasePath("quickshopper3k_database")
            dbFile.copyTo(targetDbFile, overwrite = true)

            val shmFile = File(tempDir, "database.db-shm")
            val targetShmFile = File(targetDbFile.absolutePath + "-shm")
            if (shmFile.exists()) {
                shmFile.copyTo(targetShmFile, overwrite = true)
            } else if (targetShmFile.exists()) {
                targetShmFile.delete()
            }

            val walFile = File(tempDir, "database.db-wal")
            val targetWalFile = File(targetDbFile.absolutePath + "-wal")
            if (walFile.exists()) {
                walFile.copyTo(targetWalFile, overwrite = true)
            } else if (targetWalFile.exists()) {
                targetWalFile.delete()
            }

            val settingsJson = settingsFile.readText()
            importSettings(settingsJson)

            tempDir.deleteRecursively()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun readDatabaseUserVersion(dbFile: File): Int? {
        var database: SQLiteDatabase? = null
        return try {
            database = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            database.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else null
            }
        } catch (e: Exception) {
            null
        } finally {
            database?.close()
        }
    }

    class LegacyBackupException : IllegalArgumentException("Legacy backup")

    private fun exportSettings(): String {
        val prefs = context.getSharedPreferences("quickshopper3k_prefs", Context.MODE_PRIVATE)
        val json = JSONObject()

        prefs.all.forEach { (key, value) ->
            when (value) {
                is Boolean -> json.put(key, value)
                is Int -> json.put(key, value)
                is Long -> json.put(key, value)
                is Float -> json.put(key, value)
                is String -> json.put(key, value)
                else -> json.put(key, value.toString())
            }
        }

        return json.toString(2)
    }

    private fun importSettings(jsonString: String) {
        val json = JSONObject(jsonString)
        val prefs = context.getSharedPreferences("quickshopper3k_prefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.clear()

        json.keys().forEach { key ->
            when (val value = json.get(key)) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putLong(key, value.toLong())
                is Long -> editor.putLong(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                is String -> editor.putString(key, value)
            }
        }

        editor.apply()
    }
}
