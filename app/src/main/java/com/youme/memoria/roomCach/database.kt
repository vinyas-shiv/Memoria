package com.youme.memoria.roomCach

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.youme.memoria.Album.data.AlbumDao
import com.youme.memoria.search.data.SearchHistoryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Database(
    entities = [
        PhotoEntity::class,
        AlbumPhotoEntity::class,
        AlbumEntity::class,
        SearchHistoryEntity::class
    ],
    version = 5
)
abstract class PhotosDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
    abstract fun AlbumDao(): AlbumDao
    abstract fun SearchHistoryDao(): SearchHistoryDao

    companion object {
        const val DB_NAME = "photo_db"
        @Volatile private var INSTANCE: PhotosDatabase? = null

        fun getInstance(context: Context): PhotosDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PhotosDatabase::class.java,
                    DB_NAME
                ).addMigrations(MIGRATION_3_4, MIGRATION_4_5).build().also { INSTANCE = it }
            }
        }

        fun closeInstance() {
            synchronized(this) {
                runCatching { INSTANCE?.close() }
                INSTANCE = null
            }
        }
    }
}

object IndexBackupManager {
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    suspend fun exportIndex(context: Context, destUri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val appCtx = context.applicationContext
            val db = PhotosDatabase.getInstance(appCtx)
            val count = db.photoDao().count()

            // Flush all WAL frames into the main photo_db file so a single-file backup is complete
            val writableDb = db.openHelper.writableDatabase
            writableDb.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                cursor.moveToFirst()
            }

            val dbFile = appCtx.getDatabasePath(PhotosDatabase.DB_NAME)
            if (!dbFile.exists() || dbFile.length() == 0L) {
                throw IllegalStateException("Index database is empty or has not been created yet.")
            }

            val outStream = appCtx.contentResolver.openOutputStream(destUri, "wt")
                ?: throw IllegalStateException("Unable to open destination file for writing.")
            outStream.use { output ->
                dbFile.inputStream().use { input ->
                    input.copyTo(output)
                }
                output.flush()
            }
            count
        }
    }

    suspend fun importIndex(context: Context, srcUri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val tmpFile = File(appCtx.cacheDir, "restore_photo_db.tmp")
        try {
            val inStream = appCtx.contentResolver.openInputStream(srcUri)
                ?: return@withContext Result.failure(IllegalStateException("Unable to read selected backup file."))
            inStream.use { input ->
                tmpFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (!tmpFile.exists() || tmpFile.length() < SQLITE_MAGIC.size) {
                return@withContext Result.failure(IllegalArgumentException("Selected file is too small to be a valid index backup."))
            }

            val header = ByteArray(SQLITE_MAGIC.size)
            tmpFile.inputStream().use { it.read(header) }
            if (!header.contentEquals(SQLITE_MAGIC)) {
                return@withContext Result.failure(IllegalArgumentException("Selected file is not a valid SQLite database backup."))
            }

            // Validate integrity and verify the photo table exists before touching live DB
            val restoredCount = SQLiteDatabase.openDatabase(
                tmpFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { checkDb ->
                val integrityOk = checkDb.rawQuery("PRAGMA integrity_check", null).use { c ->
                    c.moveToFirst() && c.getString(0).equals("ok", ignoreCase = true)
                }
                if (!integrityOk) {
                    return@withContext Result.failure(IllegalArgumentException("Backup file failed SQLite integrity check."))
                }
                checkDb.rawQuery("SELECT COUNT(*) FROM photo", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
            }

            PhotosDatabase.closeInstance()

            val liveDbFile = appCtx.getDatabasePath(PhotosDatabase.DB_NAME)
            liveDbFile.parentFile?.mkdirs()
            File("${liveDbFile.path}-wal").delete()
            File("${liveDbFile.path}-shm").delete()

            tmpFile.copyTo(liveDbFile, overwrite = true)
            Result.success(restoredCount)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tmpFile.delete()
        }
    }
}
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.youme.memoria.Album.data.AlbumDao
import com.youme.memoria.roomCach.MIGRATION_3_4
import com.youme.memoria.roomCach.MIGRATION_4_5
import com.youme.memoria.search.data.SearchHistoryDao

@Database(entities = [PhotoEntity::class, AlbumPhotoEntity::class, AlbumEntity::class,SearchHistoryEntity::class], version = 5)
abstract class PhotosDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
    abstract fun AlbumDao(): AlbumDao
    abstract fun SearchHistoryDao(): SearchHistoryDao
    companion object {
        @Volatile private var INSTANCE: PhotosDatabase? = null

        fun getInstance(context: Context): PhotosDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    PhotosDatabase::class.java,
                    "photo_db"
                ).addMigrations(MIGRATION_3_4, MIGRATION_4_5).build().also { INSTANCE = it }
            }
        }
    }
}
