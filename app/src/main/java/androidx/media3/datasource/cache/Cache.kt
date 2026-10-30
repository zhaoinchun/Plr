package androidx.media3.datasource.cache

import androidx.annotation.WorkerThread
import java.io.File
import java.io.IOException
import java.util.NavigableSet

interface Cache {
    interface Listener {
        fun onSpanAdded(cache: Cache, span: CacheSpan)

        fun onSpanRemoved(cache: Cache, span: CacheSpan)

        fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan)
    }

    open class CacheException : IOException {
        constructor(message: String?) : super(message)

        constructor(cause: Throwable?) : super(cause)

        constructor(message: String?, cause: Throwable?) : super(message, cause)
    }

    val uid: Long
    @WorkerThread
    fun release()

    fun addListener(key: String, listener: Listener): NavigableSet<CacheSpan>

    fun removeListener(key: String, listener: Listener)

    fun getCachedSpans(key: String): NavigableSet<CacheSpan>

    val keys: MutableSet<String?>?

    val cacheSpace: Long

    @WorkerThread
    @Throws(InterruptedException::class, CacheException::class)
    fun startReadWrite(key: String, position: Long, length: Long): CacheSpan?

    @WorkerThread
    @Throws(CacheException::class)
    fun startReadWriteNonBlocking(key: String, position: Long, length: Long): CacheSpan?

    @WorkerThread
    @Throws(CacheException::class)
    fun startFile(key: String, position: Long, length: Long): File?

    @WorkerThread
    @Throws(CacheException::class)
    fun commitFile(file: File, length: Long)

    fun releaseHoleSpan(holeSpan: CacheSpan)

    @WorkerThread
    fun removeResource(key: String)

    @WorkerThread
    fun removeSpan(span: CacheSpan)

    fun isCached(key: String, position: Long, length: Long): Boolean

    fun getCachedLength(key: String, position: Long, length: Long): Long

    fun getCachedBytes(key: String, position: Long, length: Long): Long

    @WorkerThread
    @Throws(CacheException::class)
    fun applyContentMetadataMutations(key: String, mutations: ContentMetadataMutations)

    fun getContentMetadata(key: String): ContentMetadata

    companion object {
        val UID_UNSET: Long = -1
    }
}
