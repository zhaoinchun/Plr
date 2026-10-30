package androidx.media3.datasource.cache

import android.content.ContentValues
import android.database.Cursor
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.SparseArray
import android.util.SparseBooleanArray
import androidx.annotation.WorkerThread
import androidx.media3.common.util.AtomicFile
import androidx.media3.common.util.Util
import androidx.media3.database.DatabaseIOException
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.VersionTable
import com.google.common.base.Preconditions

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.NoSuchPaddingException
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

internal class CachedContentIndex  constructor(
    databaseProvider: DatabaseProvider?,
    legacyStorageDir: File? = null,
    legacyStorageSecretKey: ByteArray? = null,
    legacyStorageEncrypt: Boolean = false,
    preferLegacyStorage: Boolean = false
) {
    private val keyToContent: HashMap<String, CachedContent>
    private val idToKey: SparseArray<String>
    private val removedIds: SparseBooleanArray
    private val newIds: SparseBooleanArray

    private var storage: Storage? = null
    private var previousStorage: Storage? = null

    init {
        Preconditions.checkState(databaseProvider != null || legacyStorageDir != null)
        keyToContent = HashMap()
        idToKey = SparseArray<String>()
        removedIds = SparseBooleanArray()
        newIds = SparseBooleanArray()
        val databaseStorage: Storage? =
            if (databaseProvider != null) DatabaseStorage(databaseProvider) else null
        val legacyStorage: Storage? =
            if (legacyStorageDir != null)
                LegacyStorage(
                    File(legacyStorageDir, FILE_NAME_ATOMIC),
                    legacyStorageSecretKey,
                    legacyStorageEncrypt
                )
            else
                null
        if (databaseStorage == null || (legacyStorage != null && preferLegacyStorage)) {
            storage = legacyStorage
            previousStorage = databaseStorage
        } else {
            storage = databaseStorage
            previousStorage = legacyStorage
        }
    }

    @WorkerThread
    @Throws(IOException::class)
    fun initialize(uid: Long) {
        storage!!.initialize(uid)
        if (previousStorage != null) {
            previousStorage!!.initialize(uid)
        }
        if (!storage!!.exists() && previousStorage != null && previousStorage!!.exists()) {
            // Copy from previous storage into current storage.
            previousStorage!!.load(keyToContent, idToKey)
            storage!!.storeFully(keyToContent)
        } else {
            // Load from the current storage.
            storage!!.load(keyToContent, idToKey)
        }
        if (previousStorage != null) {
            previousStorage!!.delete()
            previousStorage = null
        }
    }

    @WorkerThread
    @Throws(IOException::class)
    fun store() {
        storage!!.storeIncremental(keyToContent)
        // Make ids that were removed since the index was last stored eligible for re-use.
        val removedIdCount = removedIds.size()
        for (i in 0..<removedIdCount) {
            idToKey.remove(removedIds.keyAt(i))
        }
        removedIds.clear()
        newIds.clear()
    }

    fun getOrAdd(key: String): CachedContent {
        val cachedContent = keyToContent.get(key)
        return if (cachedContent == null) addNew(key) else cachedContent
    }

    fun get(key: String?): CachedContent? {
        return keyToContent.get(key)
    }

    val all: MutableCollection<CachedContent>
        get() = Collections.unmodifiableCollection(keyToContent.values)

    fun assignIdForKey(key: String): Int {
        return getOrAdd(key).id
    }

    fun getKeyForId(id: Int): String? {
        return idToKey.get(id)
    }

    fun maybeRemove(key: String?) {
        val cachedContent = keyToContent.get(key)
        if (cachedContent != null && cachedContent.isEmpty && cachedContent.isFullyUnlocked) {
            keyToContent.remove(key)
            val id = cachedContent.id
            val neverStored = newIds.get(id)
            storage!!.onRemove(cachedContent, neverStored)
            if (neverStored) {
                // The id can be reused immediately.
                idToKey.remove(id)
                newIds.delete(id)
            } else {
                // Keep an entry in idToKey to stop the id from being reused until the index is next stored,
                // and add an entry to removedIds to track that it should be removed when this does happen.
                idToKey.put(id,  null)
                removedIds.put(id,  true)
            }
        }
    }

    fun removeEmpty() {
        // Create a copy of the keys as the underlying map is modified by maybeRemove(key).
        for (key in keyToContent.keys.toSet()) {
            maybeRemove(key)
        }
    }

    val keys: MutableSet<String>
        get() = keyToContent.keys

    fun applyContentMetadataMutations(key: String, mutations: ContentMetadataMutations) {
        val cachedContent = getOrAdd(key)
        if (cachedContent.applyMetadataMutations(mutations)) {
            storage!!.onUpdate(cachedContent)
        }
    }

    fun getContentMetadata(key: String): ContentMetadata {
        val cachedContent = get(key)
        return cachedContent?.metadata ?: DefaultContentMetadata.EMPTY
    }

    private fun addNew(key: String): CachedContent {
        val id: Int = getNewId(idToKey)
        val cachedContent = CachedContent(id, key)
        keyToContent.put(key, cachedContent)
        idToKey.put(id, key)
        newIds.put(id, true)
        storage!!.onUpdate(cachedContent)
        return cachedContent
    }

    private interface Storage {
        fun initialize(uid: Long)

        @Throws(IOException::class)
        fun exists(): Boolean

        @Throws(IOException::class)
        fun delete()

        @Throws(IOException::class)
        fun load(content: HashMap<String, CachedContent>, idToKey: SparseArray<String>)

        @Throws(IOException::class)
        fun storeFully(content: HashMap<String, CachedContent>)

        @Throws(IOException::class)
        fun storeIncremental(content: HashMap<String, CachedContent>)

        fun onUpdate(cachedContent: CachedContent)

        fun onRemove(cachedContent: CachedContent, neverStored: Boolean)
    }

    private class LegacyStorage(file: File, secretKey: ByteArray?, encrypt: Boolean) : Storage {
        private val encrypt: Boolean
        private val cipher: Cipher?
        private val secretKeySpec: SecretKeySpec?
        private val random: SecureRandom?
        private val atomicFile: AtomicFile

        private var changed = false
        private var bufferedOutputStream: ReusableBufferedOutputStream? = null

        init {
            Preconditions.checkState(secretKey != null || !encrypt)
            var cipher: Cipher? = null
            var secretKeySpec: SecretKeySpec? = null
            if (secretKey != null) {
                Preconditions.checkArgument(secretKey.size == 16)
                try {
                    cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
                    secretKeySpec = SecretKeySpec(secretKey, "AES")
                } catch (e: NoSuchAlgorithmException) {
                    throw IllegalStateException(e) // Should never happen.
                } catch (e: NoSuchPaddingException) {
                    throw IllegalStateException(e)
                }
            } else {
                Preconditions.checkArgument(!encrypt)
            }
            this.encrypt = encrypt
            this.cipher = cipher
            this.secretKeySpec = secretKeySpec
            random = if (encrypt) SecureRandom() else null
            atomicFile = AtomicFile(file)
        }

        override fun initialize(uid: Long) {
            // Do nothing. Legacy storage uses a separate file for each cache.
        }

        override fun exists(): Boolean {
            return atomicFile.exists()
        }

        override fun delete() {
            atomicFile.delete()
        }

        override fun load(
            content: HashMap<String, CachedContent>, idToKey: SparseArray<String>
        ) {
            Preconditions.checkState(!changed)
            if (!readFile(content, idToKey)) {
                content.clear()
                idToKey.clear()
                atomicFile.delete()
            }
        }

        @Throws(IOException::class)
        override fun storeFully(content: HashMap<String, CachedContent>) {
            writeFile(content)
            changed = false
        }

        @Throws(IOException::class)
        override fun storeIncremental(content: HashMap<String, CachedContent>) {
            if (!changed) {
                return
            }
            storeFully(content)
        }

        override fun onUpdate(cachedContent: CachedContent) {
            changed = true
        }

        override fun onRemove(cachedContent: CachedContent, neverStored: Boolean) {
            changed = true
        }

        fun readFile(
            content: HashMap<String, CachedContent>, idToKey: SparseArray<String>
        ): Boolean {
            if (!atomicFile.exists()) {
                return true
            }

            var input: DataInputStream? = null
            try {
                val inputStream: InputStream = BufferedInputStream(atomicFile.openRead())
                input = DataInputStream(inputStream)
                val version = input.readInt()
                if (version < 0 || version > VERSION) {
                    return false
                }

                val flags = input.readInt()
                if ((flags and FLAG_ENCRYPTED_INDEX) != 0) {
                    if (cipher == null) {
                        return false
                    }
                    val initializationVector = ByteArray(16)
                    input.readFully(initializationVector)
                    val ivParameterSpec = IvParameterSpec(initializationVector)
                    try {
                        cipher.init(
                            Cipher.DECRYPT_MODE,
                            secretKeySpec,
                            ivParameterSpec
                        )
                    } catch (e: InvalidKeyException) {
                        throw IllegalStateException(e)
                    } catch (e: InvalidAlgorithmParameterException) {
                        throw IllegalStateException(e)
                    }
                    input = DataInputStream(CipherInputStream(inputStream, cipher))
                } else if (encrypt) {
                    changed = true // Force index to be rewritten encrypted after read.
                }

                val count = input.readInt()
                var hashCode = 0
                for (i in 0..<count) {
                    val cachedContent = readCachedContent(version, input)
                    content.put(cachedContent.key, cachedContent)
                    idToKey.put(cachedContent.id, cachedContent.key)
                    hashCode += hashCachedContent(cachedContent, version)
                }
                val fileHashCode = input.readInt()
                val isEOF = input.read() == -1
                if (fileHashCode != hashCode || !isEOF) {
                    return false
                }
            } catch (e: IOException) {
                return false
            } finally {
                if (input != null) {
                    Util.closeQuietly(input)
                }
            }
            return true
        }

        @Throws(IOException::class)
        fun writeFile(content: HashMap<String, CachedContent>) {
            var output: DataOutputStream? = null
            try {
                val outputStream: OutputStream? = atomicFile.startWrite()
                if (bufferedOutputStream == null) {
                    bufferedOutputStream = ReusableBufferedOutputStream(outputStream)
                } else {
                    bufferedOutputStream!!.reset(outputStream)
                }
                val bufferedOutputStream = this.bufferedOutputStream
                output = DataOutputStream(bufferedOutputStream)
                output.writeInt(VERSION)

                val flags = if (encrypt) FLAG_ENCRYPTED_INDEX else 0
                output.writeInt(flags)

                if (encrypt) {
                    val initializationVector = ByteArray(16)
                    random!!.nextBytes(initializationVector)
                    output.write(initializationVector)
                    val ivParameterSpec = IvParameterSpec(initializationVector)
                    try {
                        cipher!!.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivParameterSpec)
                    } catch (e: InvalidKeyException) {
                        throw IllegalStateException(e) // Should never happen.
                    } catch (e: InvalidAlgorithmParameterException) {
                        throw IllegalStateException(e)
                    }
                    output.flush()
                    output = DataOutputStream(CipherOutputStream(bufferedOutputStream, cipher))
                }

                output.writeInt(content.size)
                var hashCode = 0
                for (cachedContent in content.values) {
                    writeCachedContent(cachedContent, output)
                    hashCode += hashCachedContent(cachedContent, VERSION)
                }
                output.writeInt(hashCode)
                atomicFile.endWrite(output)
                // Avoid calling close twice. Duplicate CipherOutputStream.close calls did
                // not used to be no-ops: https://android-review.googlesource.com/#/c/272799/
                output = null
            } finally {
                Util.closeQuietly(output)
            }
        }

        fun hashCachedContent(cachedContent: CachedContent, version: Int): Int {
            var result = cachedContent.id
            result = 31 * result + cachedContent.key.hashCode()
            if (version < VERSION_METADATA_INTRODUCED) {
                val length: Long =
                    ContentMetadata.getContentLength(cachedContent.metadata)
                result = 31 * result + (length xor (length ushr 32)).toInt()
            } else {
                result = 31 * result + cachedContent.metadata.hashCode()
            }
            return result
        }

        @Throws(IOException::class)
        fun readCachedContent(version: Int, input: DataInputStream): CachedContent {
            val id = input.readInt()
            val key = input.readUTF()
            val metadata: DefaultContentMetadata?
            if (version < VERSION_METADATA_INTRODUCED) {
                val length = input.readLong()
                val mutations = ContentMetadataMutations()
                ContentMetadataMutations.setContentLength(mutations, length)
                metadata =
                    DefaultContentMetadata.EMPTY.copyWithMutationsApplied(mutations)
            } else {
                metadata = readContentMetadata(input)
            }
            return CachedContent(id, key, metadata)
        }

        @Throws(IOException::class)
        fun writeCachedContent(cachedContent: CachedContent, output: DataOutputStream) {
            output.writeInt(cachedContent.id)
            output.writeUTF(cachedContent.key)
            writeContentMetadata(cachedContent.metadata, output)
        }

        companion object {
            private const val VERSION = 2
            private const val VERSION_METADATA_INTRODUCED = 2
            private const val FLAG_ENCRYPTED_INDEX = 1
        }
    }

    private class DatabaseStorage(databaseProvider: DatabaseProvider) : Storage {
        private val databaseProvider: DatabaseProvider
        private val pendingUpdates: SparseArray<CachedContent?>

        private var hexUid:String? = null
        private var tableName:String? = null

        init {
            this.databaseProvider = databaseProvider
            pendingUpdates = SparseArray<CachedContent?>()
        }

        override fun initialize(uid: Long) {
            hexUid = uid.toHexString()
            tableName = getTableName(hexUid!!)
        }

        @Throws(DatabaseIOException::class)
        override fun exists(): Boolean {
            try {
                return (VersionTable.getVersion(
                    databaseProvider.readableDatabase!!,
                    VersionTable.FEATURE_CACHE_CONTENT_METADATA,
                    hexUid!!
                )
                        != VersionTable.VERSION_UNSET)
            } catch (e: SQLException) {
                throw DatabaseIOException(e)
            }
        }

        @Throws(DatabaseIOException::class)
        override fun delete() {
            delete(databaseProvider, hexUid!!)
        }

        @Throws(IOException::class)
        override fun load(
            content: HashMap<String, CachedContent>, idToKey: SparseArray<String>
        ) {
            Preconditions.checkState(pendingUpdates.size() == 0)
            try {
                val version: Int =
                    VersionTable.getVersion(
                        databaseProvider.readableDatabase!!,
                        VersionTable.FEATURE_CACHE_CONTENT_METADATA,
                        hexUid!!
                    )
                if (version != TABLE_VERSION) {
                    val writableDatabase = databaseProvider.writableDatabase!!
                    writableDatabase.beginTransactionNonExclusive()
                    try {
                        initializeTable(writableDatabase)
                        writableDatabase.setTransactionSuccessful()
                    } finally {
                        writableDatabase.endTransaction()
                    }
                }

                this.cursor.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id: Int = cursor.getInt(COLUMN_INDEX_ID)
                        val key = cursor.getString(COLUMN_INDEX_KEY)!!
                        val metadataBytes: ByteArray? = cursor.getBlob(COLUMN_INDEX_METADATA)

                        val inputStream = ByteArrayInputStream(metadataBytes)
                        val input = DataInputStream(inputStream)
                        val metadata: DefaultContentMetadata = readContentMetadata(input)

                        val cachedContent = CachedContent(id, key, metadata)
                        content.put(cachedContent.key, cachedContent)
                        idToKey.put(cachedContent.id, cachedContent.key)
                    }
                }
            } catch (e: SQLiteException) {
                content.clear()
                idToKey.clear()
                throw DatabaseIOException(e)
            }
        }

        @Throws(IOException::class)
        override fun storeFully(content: HashMap<String, CachedContent>) {
            try {
                val writableDatabase = databaseProvider.writableDatabase!!
                writableDatabase.beginTransactionNonExclusive()
                try {
                    initializeTable(writableDatabase)
                    for (cachedContent in content.values) {
                        addOrUpdateRow(writableDatabase, cachedContent)
                    }
                    writableDatabase.setTransactionSuccessful()
                    pendingUpdates.clear()
                } finally {
                    writableDatabase.endTransaction()
                }
            } catch (e: SQLException) {
                throw DatabaseIOException(e)
            }
        }

        @Throws(IOException::class)
        override fun storeIncremental(content: HashMap<String, CachedContent>) {
            if (pendingUpdates.size() == 0) {
                return
            }
            try {
                val writableDatabase = databaseProvider.writableDatabase!!
                writableDatabase.beginTransactionNonExclusive()
                try {
                    for (i in 0..<pendingUpdates.size()) {
                        val cachedContent = pendingUpdates.valueAt(i)
                        if (cachedContent == null) {
                            deleteRow(writableDatabase, pendingUpdates.keyAt(i))
                        } else {
                            addOrUpdateRow(writableDatabase, cachedContent)
                        }
                    }
                    writableDatabase.setTransactionSuccessful()
                    pendingUpdates.clear()
                } finally {
                    writableDatabase.endTransaction()
                }
            } catch (e: SQLException) {
                throw DatabaseIOException(e)
            }
        }

        override fun onUpdate(cachedContent: CachedContent) {
            pendingUpdates.put(cachedContent.id, cachedContent)
        }

        override fun onRemove(cachedContent: CachedContent, neverStored: Boolean) {
            if (neverStored) {
                pendingUpdates.delete(cachedContent.id)
            } else {
                pendingUpdates.put(cachedContent.id, null)
            }
        }

        val cursor: Cursor
            get() = databaseProvider
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

        @Throws(DatabaseIOException::class)
        fun initializeTable(writableDatabase: SQLiteDatabase) {
            VersionTable.setVersion(
                writableDatabase,
                VersionTable.FEATURE_CACHE_CONTENT_METADATA,
                hexUid!!,
                TABLE_VERSION
            )
            dropTable(writableDatabase, tableName!!)
            writableDatabase.execSQL("CREATE TABLE " + tableName + " " + TABLE_SCHEMA)
        }

        fun deleteRow(writableDatabase: SQLiteDatabase, key: Int) {
            writableDatabase.delete(
                tableName!!,
                WHERE_ID_EQUALS,
                arrayOf<String>(key.toString())
            )
        }

        @Throws(IOException::class)
        fun addOrUpdateRow(writableDatabase: SQLiteDatabase, cachedContent: CachedContent) {
            val outputStream = ByteArrayOutputStream()
            writeContentMetadata(cachedContent.metadata, DataOutputStream(outputStream))
            val data = outputStream.toByteArray()

            val values: ContentValues = ContentValues()
            values.put(COLUMN_ID, cachedContent.id)
            values.put(COLUMN_KEY, cachedContent.key)
            values.put(COLUMN_METADATA, data)
            writableDatabase.replaceOrThrow(tableName!!, null, values
            )
        }

        companion object {
            private val TABLE_PREFIX: String = DatabaseProvider.TABLE_PREFIX + "CacheIndex"
            private const val TABLE_VERSION = 1

            private const val COLUMN_ID = "id"
            private const val COLUMN_KEY = "key"
            private const val COLUMN_METADATA = "metadata"

            private const val COLUMN_INDEX_ID = 0
            private const val COLUMN_INDEX_KEY = 1
            private const val COLUMN_INDEX_METADATA = 2

            private val WHERE_ID_EQUALS = COLUMN_ID + " = ?"

            private val COLUMNS: Array<String> =
                arrayOf<String>(COLUMN_ID, COLUMN_KEY, COLUMN_METADATA)
            private val TABLE_SCHEMA = ("("
                    + COLUMN_ID
                    + " INTEGER PRIMARY KEY NOT NULL,"
                    + COLUMN_KEY
                    + " TEXT NOT NULL,"
                    + COLUMN_METADATA
                    + " BLOB NOT NULL)")

            @Throws(DatabaseIOException::class)
            fun delete(databaseProvider: DatabaseProvider, uid: kotlin.Long) {
                delete(databaseProvider,uid.toHexString())
            }

            @Throws(DatabaseIOException::class)
            private fun delete(databaseProvider: DatabaseProvider, hexUid: String) {
                try {
                    val tableName = getTableName(hexUid)
                    val writableDatabase = databaseProvider.writableDatabase!!
                    writableDatabase.beginTransactionNonExclusive()
                    try {
                        VersionTable.removeVersion(
                            writableDatabase, VersionTable.FEATURE_CACHE_CONTENT_METADATA, hexUid
                        )
                        dropTable(writableDatabase, tableName)
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

    companion object {
        
        const val FILE_NAME_ATOMIC: String = "cached_content_index.exi"

        private val INCREMENTAL_METADATA_READ_LENGTH = 10 * 1024 * 1024

        fun isIndexFile(fileName: String): Boolean {
            // Atomic file backups add additional suffixes to the file name.
            return fileName.startsWith(FILE_NAME_ATOMIC)
        }

        @WorkerThread
        @Throws(DatabaseIOException::class)
        fun delete(databaseProvider: DatabaseProvider, uid: kotlin.Long) {
            DatabaseStorage.delete(databaseProvider, uid)
        }
        fun getNewId(idToKey: SparseArray<String>): Int {
            val size = idToKey.size()
            var id = if (size == 0) 0 else (idToKey.keyAt(size - 1) + 1)
            if (id < 0) { // In case if we pass max int value.
                // TODO optimization: defragmentation or binary search?
                id = 0
                while (id < size) {
                    if (id != idToKey.keyAt(id)) {
                        break
                    }
                    id++
                }
            }
            return id
        }

        @Throws(IOException::class)
        private fun readContentMetadata(input: DataInputStream): DefaultContentMetadata {
            val size = input.readInt()
            val metadata = HashMap<String?, ByteArray?>()
            for (i in 0..<size) {
                val name = input.readUTF()
                val valueSize = input.readInt()
                if (valueSize < 0) {
                    throw IOException("Invalid value size: " + valueSize)
                }
                // Grow the array incrementally to avoid OutOfMemoryError in the case that a corrupt (and very
                // large) valueSize was read. In such cases the implementation below is expected to throw
                // IOException from one of the readFully calls, due to the end of the input being reached.
                var bytesRead = 0
                var nextBytesToRead = min(valueSize, INCREMENTAL_METADATA_READ_LENGTH)
                var value = Util.EMPTY_BYTE_ARRAY
                while (bytesRead != valueSize) {
                    value = value.copyOf(bytesRead + nextBytesToRead)
                    input.readFully(value, bytesRead, nextBytesToRead)
                    bytesRead += nextBytesToRead
                    nextBytesToRead = min(valueSize - bytesRead, INCREMENTAL_METADATA_READ_LENGTH)
                }
                metadata.put(name, value)
            }
            return DefaultContentMetadata(metadata)
        }

        @Throws(IOException::class)
        private fun writeContentMetadata(
            metadata: DefaultContentMetadata,
            output: DataOutputStream
        ) {
            val entrySet = metadata.entrySet()
            output.writeInt(entrySet.size)
            for (entry in entrySet) {
                output.writeUTF(entry.key)
                val value: ByteArray = entry.value!!
                output.writeInt(value.size)
                output.write(value)
            }
        }
    }
}
