package androidx.media3.exoplayer.offline

import android.content.ContentValues
import android.database.Cursor
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.text.TextUtils
import androidx.annotation.GuardedBy
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.common.util.Util
import androidx.media3.common.util.Util.tableExists
import androidx.media3.database.DatabaseIOException
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.VersionTable
import com.google.common.base.Preconditions
import androidx.core.net.toUri

class DefaultDownloadIndex  constructor(
    private val databaseProvider: DatabaseProvider,
    private val name: String = ""
) : WritableDownloadIndex {
    private val tableName = TABLE_PREFIX + name
    private val initializationLock = Any()

    @GuardedBy("initializationLock")
    private var initialized = false

    @Throws(DatabaseIOException::class)
    override fun getDownload(id: String?): Download? {
        ensureInitialized()
        try {
            getCursor(WHERE_ID_EQUALS, arrayOf(id)).use { cursor ->
                if (cursor.count == 0) {
                    return null
                }
                cursor.moveToNext()
                return getDownloadForCurrentRow(cursor)
            }
        } catch (e: SQLiteException) {
            throw DatabaseIOException(e)
        }
    }

    @Throws(DatabaseIOException::class)
    override fun getDownloads(@Download.State vararg states: Int): DownloadCursor {
        ensureInitialized()
        val cursor = getCursor(getStateQuery(*states), null)
        return DownloadCursorImpl(cursor)
    }

    @Throws(DatabaseIOException::class)
    override fun putDownload(download: Download) {
        ensureInitialized()
        try {
            val writableDatabase = databaseProvider.writableDatabase
            putDownloadInternal(download, writableDatabase!!)
        } catch (e: SQLiteException) {
            throw DatabaseIOException(e)
        }
    }

    @Throws(DatabaseIOException::class)
    override fun removeDownload(id: String) {
        ensureInitialized()
        try {
            databaseProvider.writableDatabase!!.delete(
                tableName,
                WHERE_ID_EQUALS,
                arrayOf<String?>(id)
            )
        } catch (e: SQLiteException) {
            throw DatabaseIOException(e)
        }
    }

    @Throws(DatabaseIOException::class)
    override fun setDownloadingStatesToQueued() {
        ensureInitialized()
        try {
            val values = ContentValues()
            values.put(COLUMN_STATE, Download.STATE_QUEUED)
            val writableDatabase = databaseProvider.writableDatabase
            writableDatabase!!.update(
                tableName,
                values,
                WHERE_STATE_IS_DOWNLOADING,
                null
            )
        } catch (e: SQLException) {
            throw DatabaseIOException(e)
        }
    }

    @Throws(DatabaseIOException::class)
    override fun setStatesToRemoving() {
        ensureInitialized()
        try {
            val values = ContentValues()
            values.put(COLUMN_STATE, Download.STATE_REMOVING)
            // Only downloads in STATE_FAILED are allowed a failure reason, so we need to clear it here in
            // case we're moving downloads from STATE_FAILED to STATE_REMOVING.
            values.put(COLUMN_FAILURE_REASON, Download.FAILURE_REASON_NONE)
            val writableDatabase = databaseProvider.writableDatabase
            writableDatabase!!.update(
                tableName,
                values,
                null,
                null
            )
        } catch (e: SQLException) {
            throw DatabaseIOException(e)
        }
    }

    @Throws(DatabaseIOException::class)
    override fun setStopReason(stopReason: Int) {
        ensureInitialized()
        try {
            val values = ContentValues()
            values.put(COLUMN_STOP_REASON, stopReason)
            val writableDatabase = databaseProvider.writableDatabase
            writableDatabase!!.update(
                tableName,
                values,
                WHERE_STATE_IS_TERMINAL,
                null
            )
        } catch (e: SQLException) {
            throw DatabaseIOException(e)
        }
    }

    @Throws(DatabaseIOException::class)
    override fun setStopReason(id: String, stopReason: Int) {
        ensureInitialized()
        try {
            val values = ContentValues()
            values.put(COLUMN_STOP_REASON, stopReason)
            val writableDatabase = databaseProvider.writableDatabase
            writableDatabase!!.update(
                tableName,
                values,
                "$WHERE_STATE_IS_TERMINAL AND $WHERE_ID_EQUALS",
                arrayOf<String?>(id)
            )
        } catch (e: SQLException) {
            throw DatabaseIOException(e)
        }
    }

    @Throws(DatabaseIOException::class)
    private fun ensureInitialized() {
        synchronized(initializationLock) {
            if (initialized) {
                return
            }
            try {
                val readableDatabase = databaseProvider.readableDatabase
                val version =
                    VersionTable.getVersion(readableDatabase!!, VersionTable.FEATURE_OFFLINE, name)
                if (version != TABLE_VERSION) {
                    val writableDatabase = databaseProvider.writableDatabase
                    writableDatabase!!.beginTransactionNonExclusive()
                    try {
                        VersionTable.setVersion(
                            writableDatabase, VersionTable.FEATURE_OFFLINE, name, TABLE_VERSION
                        )
                        val upgradedDownloads =
                            if (version == 2) loadDownloadsFromVersion2(writableDatabase) else ArrayList<Download>()
                        writableDatabase.execSQL("DROP TABLE IF EXISTS $tableName")
                        writableDatabase.execSQL("CREATE TABLE $tableName $TABLE_SCHEMA")
                        for (download in upgradedDownloads) {
                            putDownloadInternal(download, writableDatabase)
                        }
                        writableDatabase.setTransactionSuccessful()
                    } finally {
                        writableDatabase.endTransaction()
                    }
                }
                initialized = true
            } catch (e: SQLException) {
                throw DatabaseIOException(e)
            }
        }
    }

    private fun putDownloadInternal(download: Download, database: SQLiteDatabase) {
        val keySetId = download.request.keySetId ?: Util.EMPTY_BYTE_ARRAY
        val values = ContentValues()
        values.put(COLUMN_ID, download.request.id)
        values.put(COLUMN_MIME_TYPE, download.request.mimeType)
        values.put(COLUMN_URI, download.request.uri.toString())
        values.put(COLUMN_STREAM_KEYS, encodeStreamKeys(download.request.streamKeys))
        values.put(COLUMN_CUSTOM_CACHE_KEY, download.request.customCacheKey)
        values.put(COLUMN_DATA, download.request.data)
        values.put(COLUMN_STATE, download.state)
        values.put(COLUMN_START_TIME_MS, download.startTimeMs)
        values.put(COLUMN_UPDATE_TIME_MS, download.updateTimeMs)
        values.put(COLUMN_CONTENT_LENGTH, download.contentLength)
        values.put(COLUMN_STOP_REASON, download.stopReason)
        values.put(COLUMN_FAILURE_REASON, download.failureReason)
        values.put(COLUMN_PERCENT_DOWNLOADED, download.percentDownloaded)
        values.put(COLUMN_BYTES_DOWNLOADED, download.bytesDownloaded)
        values.put(COLUMN_KEY_SET_ID, keySetId)
        database.replaceOrThrow(tableName, null, values)
    }

    private fun loadDownloadsFromVersion2(database: SQLiteDatabase): List<Download> {
        val downloads: MutableList<Download> = ArrayList()
        if (!tableExists(database, tableName)) {
            return downloads
        }
        val columnsV2: Array<String> =
            arrayOf(
                "id",
                "title",
                "uri",
                "stream_keys",
                "custom_cache_key",
                "data",
                "state",
                "start_time_ms",
                "update_time_ms",
                "content_length",
                "stop_reason",
                "failure_reason",
                "percent_downloaded",
                "bytes_downloaded"
            )
        database.query(
            tableName,
            columnsV2,
            null,
            null,
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                downloads.add(getDownloadForCurrentRowV2(cursor))
            }
            return downloads
        }
    }

    @Throws(DatabaseIOException::class)
    private fun getCursor(selection: String?, selectionArgs: Array<String?>?): Cursor {
        try {
            val sortOrder = "$COLUMN_START_TIME_MS ASC"
            return databaseProvider
                .readableDatabase!!
                .query(
                    tableName,
                    COLUMNS,
                    selection,
                    selectionArgs,
                    null,
                    null,
                    sortOrder
                )
        } catch (e: SQLiteException) {
            throw DatabaseIOException(e)
        }
    }

    private class DownloadCursorImpl(private val cursor: Cursor) : DownloadCursor {
        override val download: Download
            get() = getDownloadForCurrentRow(cursor)

        override val count: Int
            get() = cursor.count
        override val position: Int
            get() = cursor.position

        override fun moveToPosition(position: Int): Boolean {
            return cursor.moveToPosition(position)
        }

        override fun close() {
            cursor.close()
        }

        override val isClosed: Boolean
            get() = cursor.isClosed
    }

    companion object {
        private const val TABLE_PREFIX = DatabaseProvider.TABLE_PREFIX + "Downloads"
        const val TABLE_VERSION: Int = 3
        private const val COLUMN_ID = "id"
        private const val COLUMN_MIME_TYPE = "mime_type"
        private const val COLUMN_URI = "uri"
        private const val COLUMN_STREAM_KEYS = "stream_keys"
        private const val COLUMN_CUSTOM_CACHE_KEY = "custom_cache_key"
        private const val COLUMN_DATA = "data"
        private const val COLUMN_STATE = "state"
        private const val COLUMN_START_TIME_MS = "start_time_ms"
        private const val COLUMN_UPDATE_TIME_MS = "update_time_ms"
        private const val COLUMN_CONTENT_LENGTH = "content_length"
        private const val COLUMN_STOP_REASON = "stop_reason"
        private const val COLUMN_FAILURE_REASON = "failure_reason"
        private const val COLUMN_PERCENT_DOWNLOADED = "percent_downloaded"
        private const val COLUMN_BYTES_DOWNLOADED = "bytes_downloaded"
        private const val COLUMN_KEY_SET_ID = "key_set_id"
        private const val COLUMN_INDEX_ID = 0
        private const val COLUMN_INDEX_MIME_TYPE = 1
        private const val COLUMN_INDEX_URI = 2
        private const val COLUMN_INDEX_STREAM_KEYS = 3
        private const val COLUMN_INDEX_CUSTOM_CACHE_KEY = 4
        private const val COLUMN_INDEX_DATA = 5
        private const val COLUMN_INDEX_STATE = 6
        private const val COLUMN_INDEX_START_TIME_MS = 7
        private const val COLUMN_INDEX_UPDATE_TIME_MS = 8
        private const val COLUMN_INDEX_CONTENT_LENGTH = 9
        private const val COLUMN_INDEX_STOP_REASON = 10
        private const val COLUMN_INDEX_FAILURE_REASON = 11
        private const val COLUMN_INDEX_PERCENT_DOWNLOADED = 12
        private const val COLUMN_INDEX_BYTES_DOWNLOADED = 13
        private const val COLUMN_INDEX_KEY_SET_ID = 14

        private const val WHERE_ID_EQUALS: String = "$COLUMN_ID = ?"
        private const val WHERE_STATE_IS_DOWNLOADING: String =
            COLUMN_STATE + " = " + Download.STATE_DOWNLOADING
        private val WHERE_STATE_IS_TERMINAL: String =
            getStateQuery(Download.STATE_COMPLETED, Download.STATE_FAILED)

        private val COLUMNS: Array<String> = arrayOf(
            COLUMN_ID,
            COLUMN_MIME_TYPE,
            COLUMN_URI,
            COLUMN_STREAM_KEYS,
            COLUMN_CUSTOM_CACHE_KEY,
            COLUMN_DATA,
            COLUMN_STATE,
            COLUMN_START_TIME_MS,
            COLUMN_UPDATE_TIME_MS,
            COLUMN_CONTENT_LENGTH,
            COLUMN_STOP_REASON,
            COLUMN_FAILURE_REASON,
            COLUMN_PERCENT_DOWNLOADED,
            COLUMN_BYTES_DOWNLOADED,
            COLUMN_KEY_SET_ID
        )

        private val TABLE_SCHEMA = ("("
                + COLUMN_ID
                + " TEXT PRIMARY KEY NOT NULL,"
                + COLUMN_MIME_TYPE
                + " TEXT,"
                + COLUMN_URI
                + " TEXT NOT NULL,"
                + COLUMN_STREAM_KEYS
                + " TEXT NOT NULL,"
                + COLUMN_CUSTOM_CACHE_KEY
                + " TEXT,"
                + COLUMN_DATA
                + " BLOB NOT NULL,"
                + COLUMN_STATE
                + " INTEGER NOT NULL,"
                + COLUMN_START_TIME_MS
                + " INTEGER NOT NULL,"
                + COLUMN_UPDATE_TIME_MS
                + " INTEGER NOT NULL,"
                + COLUMN_CONTENT_LENGTH
                + " INTEGER NOT NULL,"
                + COLUMN_STOP_REASON
                + " INTEGER NOT NULL,"
                + COLUMN_FAILURE_REASON
                + " INTEGER NOT NULL,"
                + COLUMN_PERCENT_DOWNLOADED
                + " REAL NOT NULL,"
                + COLUMN_BYTES_DOWNLOADED
                + " INTEGER NOT NULL,"
                + COLUMN_KEY_SET_ID
                + " BLOB NOT NULL)")

        private const val TRUE = "1"

        private fun inferMimeType(downloadType: String?): String {
            return when (downloadType) {
                "dash" -> MimeTypes.APPLICATION_MPD
                "hls" -> MimeTypes.APPLICATION_M3U8
                "ss" -> MimeTypes.APPLICATION_SS
                else -> MimeTypes.VIDEO_UNKNOWN
            }
        }

        fun encodeStreamKeys(streamKeys: List<StreamKey>): String {
            val stringBuilder = StringBuilder()
            for (i in streamKeys.indices) {
                val streamKey = streamKeys[i]
                stringBuilder
                    .append(streamKey.periodIndex)
                    .append('.')
                    .append(streamKey.groupIndex)
                    .append('.')
                    .append(streamKey.streamIndex)
                    .append(',')
            }
            if (stringBuilder.isNotEmpty()) {
                stringBuilder.setLength(stringBuilder.length - 1)
            }
            return stringBuilder.toString()
        }

        private fun getStateQuery(@Download.State vararg states: Int): String {
            if (states.isEmpty()) {
                return TRUE
            }
            val selectionBuilder = StringBuilder()
            selectionBuilder.append(COLUMN_STATE).append(" IN (")
            for (i in states.indices) {
                if (i > 0) {
                    selectionBuilder.append(',')
                }
                selectionBuilder.append(states[i])
            }
            selectionBuilder.append(')')
            return selectionBuilder.toString()
        }

        private fun getDownloadForCurrentRow(cursor: Cursor): Download {
            val keySetId = cursor.getBlob(COLUMN_INDEX_KEY_SET_ID)
            val request =
                DownloadRequest.Builder(
                    cursor.getString(COLUMN_INDEX_ID)!!,
                    (cursor.getString(COLUMN_INDEX_URI)!!).toUri()
                )
                    .setMimeType(cursor.getString(COLUMN_INDEX_MIME_TYPE))
                    .setStreamKeys(decodeStreamKeys(cursor.getString(COLUMN_INDEX_STREAM_KEYS)))
                    .setKeySetId(if (keySetId.size > 0) keySetId else null)
                    .setCustomCacheKey(cursor.getString(COLUMN_INDEX_CUSTOM_CACHE_KEY))
                    .setData(cursor.getBlob(COLUMN_INDEX_DATA))
                    .build()
            val downloadProgress = DownloadProgress()
            downloadProgress.bytesDownloaded = cursor.getLong(COLUMN_INDEX_BYTES_DOWNLOADED)
            downloadProgress.percentDownloaded = cursor.getFloat(COLUMN_INDEX_PERCENT_DOWNLOADED)
            @Download.State val state: @Download.State Int = cursor.getInt(COLUMN_INDEX_STATE)
            // It's possible the database contains failure reasons for non-failed downloads, which is
            // invalid. Clear them here. See https://github.com/google/ExoPlayer/issues/6785.
            @Download.FailureReason val failureReason: @Download.FailureReason Int =
                if (state == Download.STATE_FAILED)
                    cursor.getInt(COLUMN_INDEX_FAILURE_REASON)
                else
                    Download.FAILURE_REASON_NONE
            return Download(
                request,
                state,
                cursor.getLong(COLUMN_INDEX_START_TIME_MS),
                cursor.getLong(COLUMN_INDEX_UPDATE_TIME_MS),
                cursor.getLong(COLUMN_INDEX_CONTENT_LENGTH),
                cursor.getInt(COLUMN_INDEX_STOP_REASON),
                failureReason,
                downloadProgress
            )
        }

        private fun getDownloadForCurrentRowV2(cursor: Cursor): Download {
            /*
     * Version 2 schema
     * Index  Column              Type
     * 0      id                  string
     * 1      type                string
     * 2      uri                 string
     * 3      stream_keys         string
     * 4      custom_cache_key    string
     * 5      data                blob
     * 6      state               integer
     * 7      start_time_ms       integer
     * 8      update_time_ms      integer
     * 9      content_length      integer
     * 10     stop_reason         integer
     * 11     failure_reason      integer
     * 12     percent_downloaded  real
     * 13     bytes_downloaded    integer
     */
            val request =
                DownloadRequest.Builder(
                    cursor.getString(0)!!,
                    (cursor.getString(2)!!).toUri()
                )
                    .setMimeType(inferMimeType(cursor.getString(1)))
                    .setStreamKeys(decodeStreamKeys(cursor.getString(3)))
                    .setCustomCacheKey(cursor.getString(4))
                    .setData(cursor.getBlob(5))
                    .build()
            val downloadProgress = DownloadProgress()
            downloadProgress.bytesDownloaded = cursor.getLong(13)
            downloadProgress.percentDownloaded = cursor.getFloat(12)
            @Download.State val state: @Download.State Int = cursor.getInt(6)
            // It's possible the database contains failure reasons for non-failed downloads, which is
            // invalid. Clear them here. See https://github.com/google/ExoPlayer/issues/6785.
            @Download.FailureReason val failureReason: @Download.FailureReason Int =
                if (state == Download.STATE_FAILED) cursor.getInt(11) else Download.FAILURE_REASON_NONE
            return Download(
                request,
                state,
                cursor.getLong(7),
                cursor.getLong(8),
                cursor.getLong(9),
                cursor.getInt(10),
                failureReason,
                downloadProgress
            )
        }

        private fun decodeStreamKeys(encodedStreamKeys: String): List<StreamKey> {
            val streamKeys = ArrayList<StreamKey>()
            if (TextUtils.isEmpty(encodedStreamKeys)) {
                return streamKeys
            }
            val streamKeysStrings = Util.split(encodedStreamKeys, ",")
            for (streamKeysString in streamKeysStrings) {
                val indices = Util.split(streamKeysString, "\\.")
                Preconditions.checkState(indices.size == 3)
                streamKeys.add(
                    StreamKey(
                        indices[0].toInt(),
                        indices[1].toInt(),
                        indices[2].toInt()
                    )
                )
            }
            return streamKeys
        }
    }
}
