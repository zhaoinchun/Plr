package androidx.media3.datasource.cache

import android.os.ConditionVariable
import androidx.annotation.WorkerThread
import androidx.media3.common.C
import androidx.media3.common.util.Log.e
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.Util.recursiveDelete
import androidx.media3.database.DatabaseIOException
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.cache.Cache.CacheException
import com.google.common.base.Preconditions

import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.NavigableSet
import java.util.Random
import java.util.TreeSet
import kotlin.math.abs

class SimpleCache internal constructor(
    cacheDir: File,
    evictor: CacheEvictor,
    contentIndex: CachedContentIndex,
    fileIndex: CacheFileMetadataIndex?
) : Cache {
    private val cacheDir: File
    private val evictor: CacheEvictor
    private val contentIndex: CachedContentIndex
    private val fileIndex: CacheFileMetadataIndex?
    private val listeners: HashMap<String?, ArrayList<Cache.Listener?>?>
    private val random: Random
    private val touchCacheSpans: Boolean

    override var uid: Long
    private var totalSpace: Long = 0
    private var released = false
    private var initializationException:CacheException? = null

    @Suppress("deprecation")
    @Deprecated("Use a constructor that takes a {@link DatabaseProvider} for improved performance.")
    constructor(cacheDir: File, evictor: CacheEvictor) : this(
        cacheDir,
        evictor,  
        null,  
        null,  
        false,  
        true
    )

    constructor(
        cacheDir: File,
        evictor: CacheEvictor,
        databaseProvider: DatabaseProvider?,
        legacyIndexSecretKey: ByteArray? = null,
        legacyIndexEncrypt: Boolean = false,
        preferLegacyIndex: Boolean = false
    ) : this(
        cacheDir,
        evictor,
        CachedContentIndex(
            databaseProvider,
            cacheDir,
            legacyIndexSecretKey,
            legacyIndexEncrypt,
            preferLegacyIndex
        ),
        if (databaseProvider != null && !preferLegacyIndex)
            CacheFileMetadataIndex(databaseProvider)
        else
            null
    )

    init {
        check(lockFolder(cacheDir)) { "Another SimpleCache instance uses the folder: " + cacheDir }

        this.cacheDir = cacheDir
        this.evictor = evictor
        this.contentIndex = contentIndex
        this.fileIndex = fileIndex
        listeners = HashMap<String?, ArrayList<Cache.Listener?>?>()
        random = Random()
        touchCacheSpans = evictor.requiresCacheSpanTouches()
        uid = Cache.UID_UNSET

        // Start cache initialization.
        val conditionVariable = ConditionVariable()
        object : Thread("ExoPlayer:SimpleCacheInit") {
            override fun run() {
                synchronized(this@SimpleCache) {
                    conditionVariable.open()
                    initialize()
                    this@SimpleCache.evictor.onCacheInitialized()
                }
            }
        }.start()
        conditionVariable.block()
    }

    @Synchronized
    @Throws(CacheException::class)
    fun checkInitialization() {
        initializationException?.also { throw it }
    }

    @Synchronized
    override fun release() {
        if (released) {
            return
        }
        listeners.clear()
        removeStaleSpans()
        try {
            contentIndex.store()
        } catch (e: IOException) {
            e(TAG, "Storing index file failed", e)
        } finally {
            unlockFolder(cacheDir)
            released = true
        }
    }

    @Synchronized
    override fun addListener(key: String, listener: Cache.Listener): NavigableSet<CacheSpan> {
        Preconditions.checkState(!released)
        var listenersForKey = listeners[key]
        if (listenersForKey == null) {
            listenersForKey = ArrayList<Cache.Listener?>()
            listeners[key] = listenersForKey
        }
        listenersForKey.add(listener)
        return getCachedSpans(key)
    }

    @Synchronized
    override fun removeListener(key: String, listener: Cache.Listener) {
        if (released) {
            return
        }
        val listenersForKey = listeners.get(key)
        if (listenersForKey != null) {
            listenersForKey.remove(listener)
            if (listenersForKey.isEmpty()) {
                listeners.remove(key)
            }
        }
    }

    @Synchronized
    override fun getCachedSpans(key: String): NavigableSet<CacheSpan> {
        Preconditions.checkState(!released)
        val cachedContent = contentIndex.get(key)
        return if (cachedContent == null || cachedContent.isEmpty)
            TreeSet()
        else
            TreeSet<CacheSpan>(cachedContent.spans)
    }

    override val keys: MutableSet<String?>?
        @Synchronized
        get() {
            Preconditions.checkState(!released)
            return HashSet<String?>(contentIndex.keys)
        }

    override val cacheSpace: Long
        @Synchronized
        get() {
            Preconditions.checkState(!released)
            return totalSpace
        }

    @Synchronized
    @Throws(InterruptedException::class, CacheException::class)
    override fun startReadWrite(key: String, position: Long, length: Long): CacheSpan {
        Preconditions.checkState(!released)
        checkInitialization()

        while (true) {
            val span = startReadWriteNonBlocking(key, position, length)
            if (span != null) {
                return span
            } else {
                // Lock not available. We'll be woken up when a span is added, or when a locked span is
                // released. We'll be able to make progress when either:
                // 1. A span is added for the requested key that covers the requested position, in which
                //    case a read can be started.
                // 2. The lock for the requested key is released, in which case a write can be started.
                (this as Object).wait()
            }
        }
    }

    @Synchronized
    @Throws(CacheException::class)
    override fun startReadWriteNonBlocking(key: String, position: Long, length: Long): CacheSpan? {
        Preconditions.checkState(!released)
        checkInitialization()

        val span = getSpan(key, position, length)

        if (span.isCached) {
            // Read case.
            return touchSpan(key, span)
        }

        val cachedContent = contentIndex.getOrAdd(key)
        if (cachedContent.lockRange(position, span.length)) {
            // Write case.
            return span
        }

        // Lock not available.
        return null
    }

    @Synchronized
    @Throws(CacheException::class)
    override fun startFile(key: String, position: Long, length: Long): File {
        Preconditions.checkState(!released)
        checkInitialization()

        val cachedContent = contentIndex.get(key)!!
        Preconditions.checkState(cachedContent.isFullyLocked(position, length))
        if (!cacheDir.exists()) {
            // The cache directory has been deleted from underneath us. Recreate it, and remove in-memory
            // spans corresponding to cache files that no longer exist.
            createCacheDirectories(cacheDir)
            removeStaleSpans()
        }
        evictor.onStartFile(this, key, position, length)
        // Randomly distribute files into subdirectories with a uniform distribution.
        val cacheSubDir = File(cacheDir, random.nextInt(SUBDIRECTORY_COUNT).toString())
        if (!cacheSubDir.exists()) {
            createCacheDirectories(cacheSubDir)
        }
        val lastTouchTimestamp = System.currentTimeMillis()
        return SimpleCacheSpan.getCacheFile(
            cacheSubDir, cachedContent.id, position, lastTouchTimestamp
        )
    }

    @Synchronized
    @Throws(CacheException::class)
    override fun commitFile(file: File, length: Long) {
        Preconditions.checkState(!released)
        if (!file.exists()) {
            return
        }
        if (length == 0L) {
            file.delete()
            return
        }

        val span = SimpleCacheSpan.createCacheEntry(file, length, contentIndex)!!
        val cachedContent = contentIndex.get(span.key)!!
        Preconditions.checkState(cachedContent.isFullyLocked(span.position, span.length))

        // Check if the span conflicts with the set content length
        val contentLength: Long =
            ContentMetadata.getContentLength(cachedContent.metadata)
        if (contentLength != C.LENGTH_UNSET.toLong()) {
            Preconditions.checkState((span.position + span.length) <= contentLength)
        }

        if (fileIndex != null) {
            val fileName = file.getName()
            try {
                fileIndex.set(fileName, span.length, span.lastTouchTimestamp)
            } catch (e: IOException) {
                throw CacheException(e)
            }
        }
        addSpan(span)
        try {
            contentIndex.store()
        } catch (e: IOException) {
            throw CacheException(e)
        }
        (this as Object).notifyAll()
    }

    @Synchronized
    override fun releaseHoleSpan(holeSpan: CacheSpan) {
        Preconditions.checkState(!released)
        val cachedContent = contentIndex.get(holeSpan.key)!!
        cachedContent.unlockRange(holeSpan.position)
        contentIndex.maybeRemove(cachedContent.key)
        (this as Object).notifyAll()
    }

    @Synchronized
    override fun removeResource(key: String) {
        Preconditions.checkState(!released)
        for (span in getCachedSpans(key)) {
            removeSpanInternal(span)
        }
    }

    @Synchronized
    override fun removeSpan(span: CacheSpan) {
        Preconditions.checkState(!released)
        removeSpanInternal(span)
    }

    @Synchronized
    override fun isCached(key: String, position: Long, length: Long): Boolean {
        Preconditions.checkState(!released)
        val cachedContent = contentIndex.get(key)
        return cachedContent != null && cachedContent.getCachedBytesLength(
            position,
            length
        ) >= length
    }

    @Synchronized
    override fun getCachedLength(key: String, position: Long, length: Long): Long {
        var length = length
        Preconditions.checkState(!released)
        if (length == C.LENGTH_UNSET.toLong()) {
            length = Long.MAX_VALUE
        }
        val cachedContent = contentIndex.get(key)
        return if (cachedContent != null) cachedContent.getCachedBytesLength(
            position,
            length
        ) else -length
    }

    @Synchronized
    override fun getCachedBytes(key: String, position: Long, length: Long): Long {
        var endPosition =
            if (length == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else position + length
        if (endPosition < 0) {
            // The calculation rolled over (length is probably Long.MAX_VALUE).
            endPosition = Long.MAX_VALUE
        }
        var currentPosition = position
        var cachedBytes: Long = 0
        while (currentPosition < endPosition) {
            val maxRemainingLength = endPosition - currentPosition
            var blockLength = getCachedLength(key, currentPosition, maxRemainingLength)
            if (blockLength > 0) {
                cachedBytes += blockLength
            } else {
                // There's a hole of length -blockLength.
                blockLength = -blockLength
            }
            currentPosition += blockLength
        }
        return cachedBytes
    }

    @Synchronized
    @Throws(CacheException::class)
    override fun applyContentMetadataMutations(
        key: String, mutations: ContentMetadataMutations
    ) {
        Preconditions.checkState(!released)
        checkInitialization()

        contentIndex.applyContentMetadataMutations(key, mutations)
        try {
            contentIndex.store()
        } catch (e: IOException) {
            throw CacheException(e)
        }
    }

    @Synchronized
    override fun getContentMetadata(key: String): ContentMetadata {
        Preconditions.checkState(!released)
        return contentIndex.getContentMetadata(key)
    }

    private fun initialize() {
        if (!cacheDir.exists()) {
            try {
                createCacheDirectories(cacheDir)
            } catch (e: CacheException) {
                initializationException = e
                return
            }
        }

        val files = cacheDir.listFiles()
        if (files == null) {
            val message = "Failed to list cache directory files: " + cacheDir
            e(TAG, message)
            initializationException = CacheException(message)
            return
        }

        uid = loadUid(files)
        if (uid == Cache.UID_UNSET) {
            try {
                uid = createUid(cacheDir)
            } catch (e: IOException) {
                val message = "Failed to create cache UID: " + cacheDir
                e(TAG, message, e)
                initializationException = CacheException(message, e)
                return
            }
        }

        try {
            contentIndex.initialize(uid)
            if (fileIndex != null) {
                fileIndex.initialize(uid)
                val fileMetadata = fileIndex.all
                loadDirectory(cacheDir,  true, files, fileMetadata)
                fileIndex.removeAll(fileMetadata.keys)
            } else {
                loadDirectory(cacheDir,  true, files,  null)
            }
        } catch (e: IOException) {
            val message = "Failed to initialize cache indices: " + cacheDir
            e(TAG, message, e)
            initializationException = CacheException(message, e)
            return
        }

        contentIndex.removeEmpty()
        try {
            contentIndex.store()
        } catch (e: IOException) {
            e(TAG, "Storing index file failed", e)
        }
    }

    private fun loadDirectory(
        directory: File,
        isRoot: Boolean,
        files: Array<File>?,
        fileMetadata: MutableMap<String?, CacheFileMetadata?>?
    ) {
        if (files == null || files.size == 0) {
            // Either (a) directory isn't really a directory (b) it's empty, or (c) listing files failed.
            if (!isRoot) {
                // For (a) and (b) deletion is the desired result. For (c) it will be a no-op if the
                // directory is non-empty, so there's no harm in trying.
                directory.delete()
            }
            return
        }
        for (file in files) {
            val fileName = file.getName()
            if (isRoot && fileName.indexOf('.') == -1) {
                loadDirectory(file,  false, file.listFiles(), fileMetadata)
            } else {
                if (isRoot
                    && (CachedContentIndex.isIndexFile(fileName) || fileName.endsWith(
                        UID_FILE_SUFFIX
                    ))
                ) {
                    // Skip expected UID and index files in the root directory.
                    continue
                }
                var length = C.LENGTH_UNSET.toLong()
                var lastTouchTimestamp = C.TIME_UNSET
                val metadata = if (fileMetadata != null) fileMetadata.remove(fileName) else null
                if (metadata != null) {
                    length = metadata.length
                    lastTouchTimestamp = metadata.lastTouchTimestamp
                }
                val span: SimpleCacheSpan? =
                    SimpleCacheSpan.createCacheEntry(
                        file,
                        length,
                        lastTouchTimestamp,
                        contentIndex
                    )
                if (span != null) {
                    addSpan(span)
                } else {
                    file.delete()
                }
            }
        }
    }

    private fun touchSpan(key: String?, span: SimpleCacheSpan): SimpleCacheSpan {
        if (!touchCacheSpans) {
            return span
        }
        val fileName = span.file!!.getName()
        val length = span.length
        val lastTouchTimestamp = System.currentTimeMillis()
        var updateFile = false
        if (fileIndex != null) {
            try {
                fileIndex.set(fileName, length, lastTouchTimestamp)
            } catch (e: IOException) {
                w(TAG, "Failed to update index with new touch timestamp.")
            }
        } else {
            // Updating the file itself to incorporate the new last touch timestamp is much slower than
            // updating the file index. Hence we only update the file if we don't have a file index.
            updateFile = true
        }
        val newSpan =
            contentIndex.get(key)!!.setLastTouchTimestamp(span, lastTouchTimestamp, updateFile)
        notifySpanTouched(span, newSpan)
        return newSpan
    }

    private fun getSpan(key: String, position: Long, length: Long): SimpleCacheSpan {
        val cachedContent = contentIndex.get(key)
        if (cachedContent == null) {
            return SimpleCacheSpan.createHole(key, position, length)
        }
        while (true) {
            val span = cachedContent.getSpan(position, length)
            if (span.isCached && span.file!!.length() != span.length) {
                // The file has been modified or deleted underneath us. It's likely that other files will
                // have been modified too, so scan the whole in-memory representation.
                removeStaleSpans()
                continue
            }
            return span
        }
    }

    private fun addSpan(span: SimpleCacheSpan) {
        contentIndex.getOrAdd(span.key).addSpan(span)
        totalSpace += span.length
        notifySpanAdded(span)
    }

    private fun removeSpanInternal(span: CacheSpan) {
        val cachedContent = contentIndex.get(span.key)
        if (cachedContent == null || !cachedContent.removeSpan(span)) {
            return
        }
        totalSpace -= span.length
        if (fileIndex != null) {
            val fileName = span.file!!.name
            try {
                fileIndex.remove(fileName)
            } catch (e: IOException) {
                // This will leave a stale entry in the file index. It will be removed next time the cache
                // is initialized.
                w(TAG, "Failed to remove file index entry for: " + fileName)
            }
        }
        contentIndex.maybeRemove(cachedContent.key)
        notifySpanRemoved(span)
    }

    private fun removeStaleSpans() {
        val spansToBeRemoved = ArrayList<CacheSpan>()
        for (cachedContent in contentIndex.all) {
            for (span in cachedContent.spans) {
                if (span.file!!.length() != span.length) {
                    spansToBeRemoved.add(span)
                }
            }
        }
        for (i in spansToBeRemoved.indices) {
            removeSpanInternal(spansToBeRemoved[i]!!)
        }
    }

    private fun notifySpanRemoved(span: CacheSpan) {
        val keyListeners = listeners.get(span.key)
        if (keyListeners != null) {
            for (i in keyListeners.indices.reversed()) {
                keyListeners[i]!!.onSpanRemoved(this, span)
            }
        }
        evictor.onSpanRemoved(this, span)
    }

    private fun notifySpanAdded(span: SimpleCacheSpan) {
        val keyListeners = listeners.get(span.key)
        if (keyListeners != null) {
            for (i in keyListeners.indices.reversed()) {
                keyListeners[i]!!.onSpanAdded(this, span)
            }
        }
        evictor.onSpanAdded(this, span)
    }

    private fun notifySpanTouched(oldSpan: SimpleCacheSpan, newSpan: CacheSpan) {
        val keyListeners = listeners.get(oldSpan.key)
        if (keyListeners != null) {
            for (i in keyListeners.indices.reversed()) {
                keyListeners[i]!!.onSpanTouched(this, oldSpan, newSpan)
            }
        }
        evictor.onSpanTouched(this, oldSpan, newSpan)
    }

    companion object {
        private const val TAG = "SimpleCache"

        private const val SUBDIRECTORY_COUNT = 10

        private const val UID_FILE_SUFFIX = ".uid"

        private val lockedCacheDirs = HashSet<File?>()

        @Synchronized
        fun isCacheFolderLocked(cacheFolder: File): Boolean {
            return lockedCacheDirs.contains(cacheFolder.getAbsoluteFile())
        }

        @WorkerThread
        fun delete(cacheDir: File, databaseProvider: DatabaseProvider?) {
            if (!cacheDir.exists()) {
                return
            }

            val files = cacheDir.listFiles()
            if (files == null) {
                cacheDir.delete()
                return
            }

            if (databaseProvider != null) {
                // Make a best effort to read the cache UID and delete associated index data before deleting
                // cache directory itself.
                val uid: Long = loadUid(files)
                if (uid != Cache.UID_UNSET) {
                    try {
                        CacheFileMetadataIndex.delete(databaseProvider, uid)
                    } catch (e: DatabaseIOException) {
                        w(TAG, "Failed to delete file metadata: " + uid)
                    }
                    try {
                        CachedContentIndex.delete(databaseProvider, uid)
                    } catch (e: DatabaseIOException) {
                        w(TAG, "Failed to delete file metadata: " + uid)
                    }
                }
            }

            recursiveDelete(cacheDir)
        }

        private fun loadUid(files: Array<File>): Long {
            for (file in files) {
                val fileName = file.getName()
                if (fileName.endsWith(UID_FILE_SUFFIX)) {
                    try {
                        return parseUid(fileName)
                    } catch (e: NumberFormatException) {
                        // This should never happen, but if it does delete the malformed UID file and continue.
                        e(TAG, "Malformed UID file: " + file)
                        file.delete()
                    }
                }
            }
            return Cache.UID_UNSET
        }

        @Throws(IOException::class)
        private fun createUid(directory: File?): Long {
            // Generate a non-negative UID.
            var uid = SecureRandom().nextLong()
            uid = if (uid == Long.MIN_VALUE) 0 else abs(uid)
            // Persist it as a file.
            val hexUid = uid.toString(16)
            val hexUidFile = File(directory, hexUid + UID_FILE_SUFFIX)
            if (!hexUidFile.createNewFile()) {
                // False means that the file already exists, so this should never happen.
                throw IOException("Failed to create UID file: " + hexUidFile)
            }
            return uid
        }

        private fun parseUid(fileName: String): Long {
            return fileName.substring(0, fileName.indexOf('.'.code.toChar()))
                .toLong( 16)
        }

        @Throws(CacheException::class)
        private fun createCacheDirectories(cacheDir: File) {
            // If mkdirs() returns false, double check that the directory doesn't exist before throwing.
            if (!cacheDir.mkdirs() && !cacheDir.isDirectory()) {
                val message = "Failed to create cache directory: " + cacheDir
                e(TAG, message)
                throw CacheException(message)
            }
        }

        @Synchronized
        private fun lockFolder(cacheDir: File): Boolean {
            return lockedCacheDirs.add(cacheDir.getAbsoluteFile())
        }

        @Synchronized
        private fun unlockFolder(cacheDir: File) {
            lockedCacheDirs.remove(cacheDir.getAbsoluteFile())
        }
    }
}
