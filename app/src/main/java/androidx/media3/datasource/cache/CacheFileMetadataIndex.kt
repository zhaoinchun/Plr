package androidx.media3.datasource.cache

import android.content.ContentValues
import android.database.Cursor
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import androidx.annotation.WorkerThread
import androidx.media3.database.DatabaseIOException
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.VersionTable
import com.google.common.base.Preconditions

import kotlin.Array
import kotlin.String
import kotlin.Throws
import kotlin.arrayOf

internal class CacheFileMetadataIndex
(private val databaseProvider: DatabaseProvider) {
    private var tableName:String? = null

    @WorkerThread
    @Throws(DatabaseIOException::class)
    fun initialize(uid: Long) {
        try {
            val hexUid = uid.toHexString()
            tableName = getTableName(hexUid)
            val readableDatabase = databaseProvider.readableDatabase
            val version =
                VersionTable.getVersion(
                    readableDatabase!!, VersionTable.FEATURE_CACHE_FILE_METADATA, hexUid
                )
            if (version != TABLE_VERSION) {
                val writableDatabase = databaseProvider.writableDatabase
                writableDatabase!!.beginTransactionNonExclusive()
                try {
                    VersionTable.setVersion(
                        writableDatabase,
                        VersionTable.FEATURE_CACHE_FILE_METADATA,
                        hexUid,
                        TABLE_VERSION
                    )
                    Companion.dropTable(writableDatabase, tableName)
                    writableDatabase.execSQL("CREATE TABLE " + tableName + " " + TABLE_SCHEMA)
                    writableDatabase.setTransactionSuccessful()
                } finally {
                    writableDatabase.endTransaction()
                }
            }
        } catch (e: SQLException) {
            throw DatabaseIOException(e)
        }
    }

    @get:Throws(DatabaseIOException::class)
    @get:WorkerThread
    val all: MutableMap<String?, CacheFileMetadata?>
        get() {
            try {
                this.cursor.use { cursor ->
                    val fileMetadata: MutableMap<String?, CacheFileMetadata?> =
                        HashMap<String?, CacheFileMetadata?>(cursor.getCount())
                    while (cursor.moveToNext()) {
                        val name =cursor.getString(COLUMN_INDEX_NAME)!!
                        val length: kotlin.Long =
                            cursor.getLong(COLUMN_INDEX_LENGTH)
                        val lastTouchTimestamp: kotlin.Long =
                            cursor.getLong(COLUMN_INDEX_LAST_TOUCH_TIMESTAMP)
                        fileMetadata.put(name, CacheFileMetadata(length, lastTouchTimestamp))
                    }
                    return fileMetadata
                }
            } catch (e: SQLException) {
                throw DatabaseIOException(e)
            }
        }

    @WorkerThread
    @Throws(DatabaseIOException::class)
    fun set(name: String?, length: kotlin.Long, lastTouchTimestamp: kotlin.Long) {
        try {
            val writableDatabase = databaseProvider.writableDatabase
            val values = ContentValues()
            values.put(COLUMN_NAME, name)
            values.put(COLUMN_LENGTH, length)
            values.put(COLUMN_LAST_TOUCH_TIMESTAMP, lastTouchTimestamp)
            writableDatabase!!.replaceOrThrow(tableName!!,  null, values)
        } catch (e: SQLException) {
            throw DatabaseIOException(e)
        }
    }

    @WorkerThread
    @Throws(DatabaseIOException::class)
    fun remove(name: String?) {
        try {
            val writableDatabase = databaseProvider.writableDatabase
            writableDatabase!!.delete(tableName!!, WHERE_NAME_EQUALS, arrayOf<String?>(name))
        } catch (e: SQLException) {
            throw DatabaseIOException(e)
        }
    }

    @WorkerThread
    @Throws(DatabaseIOException::class)
    fun removeAll(names: MutableSet<String?>) {
        try {
            val writableDatabase = databaseProvider.writableDatabase
            writableDatabase!!.beginTransactionNonExclusive()
            try {
                for (name in names) {
                    writableDatabase.delete(
                        tableName!!,
                        WHERE_NAME_EQUALS,
                        arrayOf<String?>(name)
                    )
                }
                writableDatabase.setTransactionSuccessful()
            } finally {
                writableDatabase.endTransaction()
            }
        } catch (e: SQLException) {
            throw DatabaseIOException(e)
        }
    }

    private val cursor: Cursor
        get() {
            return databaseProvider
                .readableDatabase!!
                .query(
                    tableName!!,
                    COLUMNS,  
                    null,  
                    null,  
                    null,  
                    null,  
                    null
                )
        }

    companion object {
        private val TABLE_PREFIX = DatabaseProvider.TABLE_PREFIX + "CacheFileMetadata"
        private const val TABLE_VERSION = 1

        private const val COLUMN_NAME = "name"
        private const val COLUMN_LENGTH = "length"
        private const val COLUMN_LAST_TOUCH_TIMESTAMP = "last_touch_timestamp"

        private const val COLUMN_INDEX_NAME = 0
        private const val COLUMN_INDEX_LENGTH = 1
        private const val COLUMN_INDEX_LAST_TOUCH_TIMESTAMP = 2

        private val WHERE_NAME_EQUALS: String = COLUMN_NAME + " = ?"

        private val COLUMNS: Array<String> = arrayOf(
            COLUMN_NAME, COLUMN_LENGTH, COLUMN_LAST_TOUCH_TIMESTAMP,
        )
        private val TABLE_SCHEMA = ("("
                + COLUMN_NAME
                + " TEXT PRIMARY KEY NOT NULL,"
                + COLUMN_LENGTH
                + " INTEGER NOT NULL,"
                + COLUMN_LAST_TOUCH_TIMESTAMP
                + " INTEGER NOT NULL)")

        @WorkerThread
        @Throws(DatabaseIOException::class)
        fun delete(databaseProvider: DatabaseProvider, uid: kotlin.Long) {
            val hexUid = uid.toHexString()
            try {
                val tableName: String = getTableName(hexUid)
                val writableDatabase = databaseProvider.writableDatabase
                writableDatabase!!.beginTransactionNonExclusive()
                try {
                    VersionTable.removeVersion(
                        writableDatabase, VersionTable.FEATURE_CACHE_FILE_METADATA, hexUid
                    )
                    Companion.dropTable(writableDatabase, tableName)
                    writableDatabase.setTransactionSuccessful()
                } finally {
                    writableDatabase.endTransaction()
                }
            } catch (e: SQLException) {
                throw DatabaseIOException(e)
            }
        }

        private fun dropTable(writableDatabase: SQLiteDatabase, tableName: String?) {
            writableDatabase.execSQL("DROP TABLE IF EXISTS " + tableName)
        }

        private fun getTableName(hexUid: String): String {
            return TABLE_PREFIX + hexUid
        }
    }
}
