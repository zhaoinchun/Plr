package androidx.media3.database

import android.content.ContentValues
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import androidx.annotation.IntDef
import androidx.media3.common.MediaLibraryInfo.registerModule
import androidx.media3.common.util.Util.tableExists

object VersionTable {
    init {
        registerModule("media3.database")
    }

    val VERSION_UNSET: Int = -1
    const val FEATURE_OFFLINE: Int = 0
    const val FEATURE_CACHE_CONTENT_METADATA: Int = 1
    const val FEATURE_CACHE_FILE_METADATA: Int = 2
    const val FEATURE_EXTERNAL: Int = 1000
    private val TABLE_NAME = DatabaseProvider.TABLE_PREFIX + "Versions"

    private const val COLUMN_FEATURE = "feature"
    private const val COLUMN_INSTANCE_UID = "instance_uid"
    private const val COLUMN_VERSION = "version"
    private val WHERE_FEATURE_AND_INSTANCE_UID_EQUALS =
        COLUMN_FEATURE + " = ? AND " + COLUMN_INSTANCE_UID + " = ?"

    private val PRIMARY_KEY = "PRIMARY KEY (" + COLUMN_FEATURE + ", " + COLUMN_INSTANCE_UID + ")"
    private val SQL_CREATE_TABLE_IF_NOT_EXISTS = ("CREATE TABLE IF NOT EXISTS "
            + TABLE_NAME
            + " ("
            + COLUMN_FEATURE
            + " INTEGER NOT NULL,"
            + COLUMN_INSTANCE_UID
            + " TEXT NOT NULL,"
            + COLUMN_VERSION
            + " INTEGER NOT NULL,"
            + PRIMARY_KEY
            + ")")

    @Throws(DatabaseIOException::class)
    fun setVersion(
        writableDatabase: SQLiteDatabase, feature: @Feature Int, instanceUid: String?, version: Int
    ) {
        try {
            writableDatabase.execSQL(SQL_CREATE_TABLE_IF_NOT_EXISTS)
            val values = ContentValues()
            values.put(COLUMN_FEATURE, feature)
            values.put(COLUMN_INSTANCE_UID, instanceUid)
            values.put(COLUMN_VERSION, version)
            writableDatabase.replaceOrThrow(TABLE_NAME,  null, values)
        } catch (e: SQLException) {
            throw DatabaseIOException(e)
        }
    }

    @Throws(DatabaseIOException::class)
    fun removeVersion(
        writableDatabase: SQLiteDatabase, feature: @Feature Int, instanceUid: String?
    ) {
        try {
            if (!tableExists(writableDatabase, TABLE_NAME)) {
                return
            }
            writableDatabase.delete(
                TABLE_NAME,
                WHERE_FEATURE_AND_INSTANCE_UID_EQUALS,
                featureAndInstanceUidArguments(feature, instanceUid)
            )
        } catch (e: SQLException) {
            throw DatabaseIOException(e)
        }
    }

    @Throws(DatabaseIOException::class)
    fun getVersion(database: SQLiteDatabase, feature: @Feature Int, instanceUid: String?): Int {
        try {
            if (!tableExists(database, TABLE_NAME)) {
                return VERSION_UNSET
            }
            database.query(
                TABLE_NAME,
                arrayOf<String>(COLUMN_VERSION),
                WHERE_FEATURE_AND_INSTANCE_UID_EQUALS,
                featureAndInstanceUidArguments(feature, instanceUid),  
                null,  
                null,  
                null
            ).use { cursor ->
                if (cursor.getCount() == 0) {
                    return VERSION_UNSET
                }
                cursor.moveToNext()
                return cursor.getInt( 0)
            }
        } catch (e: SQLException) {
            throw DatabaseIOException(e)
        }
    }

    private fun featureAndInstanceUidArguments(feature: Int, instance: String?): Array<String?> {
        return arrayOf<String?>(feature.toString(), instance)
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [FEATURE_OFFLINE, FEATURE_CACHE_CONTENT_METADATA, FEATURE_CACHE_FILE_METADATA, FEATURE_EXTERNAL]
    )
    private annotation class Feature
}
