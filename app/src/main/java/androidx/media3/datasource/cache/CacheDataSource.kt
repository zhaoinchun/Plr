package androidx.media3.datasource.cache

import android.net.Uri
import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PriorityTaskManager
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.PlaceholderDataSource
import androidx.media3.datasource.PriorityDataSource
import androidx.media3.datasource.TeeDataSource
import androidx.media3.datasource.TransferListener
import com.google.common.base.Preconditions
import com.google.errorprone.annotations.CanIgnoreReturnValue

import java.io.IOException
import java.io.InterruptedIOException
import kotlin.math.min

class CacheDataSource private constructor(
    cache: Cache,
    upstreamDataSource: DataSource?,
    cacheReadDataSource: DataSource,
    cacheWriteDataSink: DataSink?,
    cacheKeyFactory: CacheKeyFactory?,
    flags: @Flags Int,
    upstreamPriorityTaskManager: PriorityTaskManager?,
    upstreamPriority: @C.Priority Int,
    eventListener: EventListener?
) : DataSource {
    class Factory : DataSource.Factory {
        var cache:Cache? = null
            private set
        private var cacheReadDataSourceFactory: DataSource.Factory=FileDataSource.Factory()
        private var cacheWriteDataSinkFactory: DataSink.Factory? = null
        var cacheKeyFactory: CacheKeyFactory =  CacheKeyFactory.DEFAULT
            private set
        private var cacheIsReadOnly = false
        private var upstreamDataSourceFactory: DataSource.Factory? = null
        private var upstreamPriorityTaskManager: PriorityTaskManager? = null
        private var upstreamPriority: @C.Priority Int = 0
        private var flags: @Flags Int = 0
        private var eventListener: EventListener? = null

        fun setCache(cache: Cache): Factory {
            this.cache = cache
            return this
        }

        fun setCacheReadDataSourceFactory(cacheReadDataSourceFactory: DataSource.Factory): Factory {
            this.cacheReadDataSourceFactory = cacheReadDataSourceFactory
            return this
        }

        fun setCacheWriteDataSinkFactory(
            cacheWriteDataSinkFactory: DataSink.Factory?
        ): Factory {
            this.cacheWriteDataSinkFactory = cacheWriteDataSinkFactory
            this.cacheIsReadOnly = cacheWriteDataSinkFactory == null
            return this
        }

        fun setCacheKeyFactory(cacheKeyFactory: CacheKeyFactory): Factory {
            this.cacheKeyFactory = cacheKeyFactory
            return this
        }

        fun setUpstreamDataSourceFactory(
            upstreamDataSourceFactory: DataSource.Factory?
        ): Factory {
            this.upstreamDataSourceFactory = upstreamDataSourceFactory
            return this
        }

        fun setUpstreamPriorityTaskManager(
            upstreamPriorityTaskManager: PriorityTaskManager?
        ): Factory {
            this.upstreamPriorityTaskManager = upstreamPriorityTaskManager
            return this
        }

        fun getUpstreamPriorityTaskManager(): PriorityTaskManager? {
            return upstreamPriorityTaskManager
        }
        fun setUpstreamPriority(upstreamPriority: @C.Priority Int): Factory {
            this.upstreamPriority = upstreamPriority
            return this
        }

        fun setFlags(flags: @Flags Int): Factory {
            this.flags = flags
            return this
        }

        fun setEventListener(eventListener: EventListener?): Factory {
            this.eventListener = eventListener
            return this
        }

        override fun createDataSource(): CacheDataSource {
            return createDataSourceInternal(
                if (upstreamDataSourceFactory != null) upstreamDataSourceFactory!!.createDataSource() else null,
                flags,
                upstreamPriority
            )
        }

        fun createDataSourceForDownloading(): CacheDataSource {
            return createDataSourceInternal(
                if (upstreamDataSourceFactory != null) upstreamDataSourceFactory!!.createDataSource() else null,
                flags or FLAG_BLOCK_ON_CACHE,
                C.PRIORITY_DOWNLOAD
            )
        }

        fun createDataSourceForRemovingDownload(): CacheDataSource {
            return createDataSourceInternal( 
                null, flags or FLAG_BLOCK_ON_CACHE, C.PRIORITY_DOWNLOAD
            )
        }

        private fun createDataSourceInternal(
            upstreamDataSource: DataSource?,
            flags: @Flags Int,
            upstreamPriority: @C.Priority Int
        ): CacheDataSource {
            val cache = this.cache!!
            val cacheWriteDataSink: DataSink?
            if (cacheIsReadOnly || upstreamDataSource == null) {
                cacheWriteDataSink = null
            } else if (cacheWriteDataSinkFactory != null) {
                cacheWriteDataSink = cacheWriteDataSinkFactory!!.createDataSink()
            } else {
                cacheWriteDataSink = CacheDataSink.Factory().setCache(cache).createDataSink()
            }
            return CacheDataSource(
                cache,
                upstreamDataSource,
                cacheReadDataSourceFactory.createDataSource(),
                cacheWriteDataSink,
                cacheKeyFactory,
                flags,
                upstreamPriorityTaskManager,
                upstreamPriority,
                eventListener
            )
        }
    }

    interface EventListener {
        fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long)
        fun onCacheIgnored(@CacheIgnoredReason reason: @CacheIgnoredReason Int)
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        flag = true,
        value = [FLAG_BLOCK_ON_CACHE, FLAG_IGNORE_CACHE_ON_ERROR, FLAG_IGNORE_CACHE_FOR_UNSET_LENGTH_REQUESTS]
    )
    annotation class Flags

    // @Target list includes both 'default' targets and TYPE_USE, to ensure backwards compatibility
    // with Kotlin usages from before TYPE_USE was added.
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(value = [CACHE_IGNORED_REASON_ERROR, CACHE_IGNORED_REASON_UNSET_LENGTH])
    annotation class CacheIgnoredReason

    val cache: Cache
    private val cacheReadDataSource: DataSource
    private val cacheWriteDataSource: DataSource?
    private val upstreamDataSource: DataSource?

    val cacheKeyFactory: CacheKeyFactory
    private val eventListener: EventListener?

    private val blockOnCache: Boolean
    private val ignoreCacheOnError: Boolean
    private val ignoreCacheForUnsetLengthRequests: Boolean

    private var actualUri: Uri? = null
    private var requestDataSpec: DataSpec? = null
    private var currentDataSpec: DataSpec? = null
    private var currentDataSource: DataSource? = null
    private var currentDataSourceBytesRead: Long = 0
    private var readPosition: Long = 0
    private var bytesRemaining: Long = 0
    private var currentHoleSpan: CacheSpan? = null
    private var seenCacheError = false
    private var currentRequestIgnoresCache = false
    private var totalCachedBytesRead: Long = 0
    private var checkCachePosition: Long = 0

    constructor(cache: Cache, upstreamDataSource: DataSource?, flags: @Flags Int = 0) : this(
        cache,
        upstreamDataSource,
        FileDataSource(),
        CacheDataSink(cache, CacheDataSink.DEFAULT_FRAGMENT_SIZE),
        flags,  
        null
    )

    constructor(
        cache: Cache,
        upstreamDataSource: DataSource?,
        cacheReadDataSource: DataSource,
        cacheWriteDataSink: DataSink?,
        flags: @Flags Int,
        eventListener: EventListener?,
        cacheKeyFactory: CacheKeyFactory? = null
    ) : this(
        cache,
        upstreamDataSource,
        cacheReadDataSource,
        cacheWriteDataSink,
        cacheKeyFactory,
        flags,  
        null,  
        C.PRIORITY_PLAYBACK,
        eventListener
    )

    init {
        var upstreamDataSource = upstreamDataSource
        this.cache = cache
        this.cacheReadDataSource = cacheReadDataSource
        this.cacheKeyFactory =
            if (cacheKeyFactory != null) cacheKeyFactory else CacheKeyFactory.DEFAULT
        this.blockOnCache = (flags and FLAG_BLOCK_ON_CACHE) != 0
        this.ignoreCacheOnError = (flags and FLAG_IGNORE_CACHE_ON_ERROR) != 0
        this.ignoreCacheForUnsetLengthRequests =
            (flags and FLAG_IGNORE_CACHE_FOR_UNSET_LENGTH_REQUESTS) != 0
        if (upstreamDataSource != null) {
            if (upstreamPriorityTaskManager != null) {
                upstreamDataSource =
                    PriorityDataSource(
                        upstreamDataSource, upstreamPriorityTaskManager, upstreamPriority
                    )
            }
            this.upstreamDataSource = upstreamDataSource
            this.cacheWriteDataSource =
                if (cacheWriteDataSink != null)
                    TeeDataSource(upstreamDataSource, cacheWriteDataSink)
                else
                    null
        } else {
            this.upstreamDataSource = PlaceholderDataSource.INSTANCE
            this.cacheWriteDataSource = null
        }
        this.eventListener = eventListener
    }

    override fun addTransferListener(transferListener: TransferListener) {
        cacheReadDataSource.addTransferListener(transferListener)
        upstreamDataSource!!.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        try {
            val key = cacheKeyFactory.buildCacheKey(dataSpec)
            val requestDataSpec: DataSpec = dataSpec.buildUpon().setKey(key).build()
            this.requestDataSpec = requestDataSpec
            actualUri = getRedirectedUriOrDefault(cache, key,  requestDataSpec.uri)
            readPosition = dataSpec.position

            val reason = shouldIgnoreCacheForRequest(dataSpec)
            currentRequestIgnoresCache = reason != CACHE_NOT_IGNORED
            if (currentRequestIgnoresCache) {
                notifyCacheIgnored(reason)
            }

            if (currentRequestIgnoresCache) {
                bytesRemaining = C.LENGTH_UNSET.toLong()
            } else {
                bytesRemaining =
                    ContentMetadata.getContentLength(cache.getContentMetadata(key))
                if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                    bytesRemaining -= dataSpec.position
                    if (bytesRemaining < 0) {
                        throw DataSourceException(
                            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
                        )
                    }
                }
            }
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                bytesRemaining =
                    if (bytesRemaining == C.LENGTH_UNSET.toLong())
                        dataSpec.length
                    else
                        min(bytesRemaining, dataSpec.length)
            }
            if (bytesRemaining > 0 || bytesRemaining == C.LENGTH_UNSET.toLong()) {
                openNextSource(requestDataSpec, false)
            }
            return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else bytesRemaining
        } catch (e: Throwable) {
            handleBeforeThrow(e)
            throw e
        }
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) {
            return 0
        }
        if (bytesRemaining == 0L) {
            return C.RESULT_END_OF_INPUT
        }
        val requestDataSpec: DataSpec = this.requestDataSpec!!
        val currentDataSpec: DataSpec = this.currentDataSpec!!
        try {
            if (readPosition >= checkCachePosition) {
                openNextSource(requestDataSpec, true)
            }
            val bytesRead: Int = currentDataSource!!
                .read(buffer, offset, length)
            if (bytesRead != C.RESULT_END_OF_INPUT) {
                if (this.isReadingFromCache) {
                    totalCachedBytesRead += bytesRead.toLong()
                }
                readPosition += bytesRead.toLong()
                currentDataSourceBytesRead += bytesRead.toLong()
                if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                    bytesRemaining -= bytesRead.toLong()
                }
            } else if (this.isReadingFromUpstream
                && (currentDataSpec.length == C.LENGTH_UNSET.toLong()
                        || currentDataSourceBytesRead < currentDataSpec.length)
            ) {
                // We've encountered RESULT_END_OF_INPUT from the upstream DataSource at a position not
                // imposed by the current DataSpec. This must mean that we've reached the end of the
                // resource.
                setNoBytesRemainingAndMaybeStoreLength(requestDataSpec.key!!)
            } else if (bytesRemaining > 0 || bytesRemaining == C.LENGTH_UNSET.toLong()) {
                closeCurrentSource()
                openNextSource(requestDataSpec, false)
                return read(buffer, offset, length)
            }
            return bytesRead
        } catch (e: Throwable) {
            handleBeforeThrow(e)
            throw e
        }
    }

    override val uri: Uri?
        get() = actualUri

    override val responseHeaders: MutableMap<String, List<String>>
        get() {
            return if (this.isReadingFromUpstream)
                upstreamDataSource!!.responseHeaders
            else mutableMapOf()
        }

    @Throws(IOException::class)
    override fun close() {
        requestDataSpec = null
        actualUri = null
        readPosition = 0
        notifyBytesRead()
        try {
            closeCurrentSource()
        } catch (e: Throwable) {
            handleBeforeThrow(e)
            throw e
        }
    }

    @Throws(IOException::class)
    private fun openNextSource(requestDataSpec: DataSpec, checkCache: Boolean) {
        var nextSpan: CacheSpan?
        val key = requestDataSpec.key!!
        if (currentRequestIgnoresCache) {
            nextSpan = null
        } else if (blockOnCache) {
            try {
                nextSpan = cache.startReadWrite(key, readPosition, bytesRemaining)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException()
            }
        } else {
            nextSpan = cache.startReadWriteNonBlocking(key, readPosition, bytesRemaining)
        }

        val nextDataSpec: DataSpec?
        val nextDataSource: DataSource?
        if (nextSpan == null) {
            // The data is locked in the cache, or we're ignoring the cache. Bypass the cache and read
            // from upstream.
            nextDataSource = upstreamDataSource
            nextDataSpec =
                requestDataSpec.buildUpon().setPosition(readPosition).setLength(bytesRemaining)
                    .build()
        } else if (nextSpan.isCached) {
            // Data is cached in a span file starting at nextSpan.position.
            val fileUri = Uri.fromFile(nextSpan.file)
            val filePositionOffset = nextSpan.position
            val positionInFile = readPosition - filePositionOffset
            var length = nextSpan.length - positionInFile
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                length = min(length, bytesRemaining)
            }
            nextDataSpec =
                requestDataSpec
                    .buildUpon()
                    .setUri(fileUri)
                    .setUriPositionOffset(filePositionOffset)
                    .setPosition(positionInFile)
                    .setLength(length)
                    .build()
            nextDataSource = cacheReadDataSource
        } else {
            // Data is not cached, and data is not locked, read from upstream with cache backing.
            var length: Long
            if (nextSpan.isOpenEnded) {
                length = bytesRemaining
            } else {
                length = nextSpan.length
                if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                    length = min(length, bytesRemaining)
                }
            }
            nextDataSpec =
                requestDataSpec.buildUpon().setPosition(readPosition).setLength(length).build()
            if (cacheWriteDataSource != null) {
                nextDataSource = cacheWriteDataSource
            } else {
                nextDataSource = upstreamDataSource
                cache.releaseHoleSpan(nextSpan)
                nextSpan = null
            }
        }

        checkCachePosition =
            if (!currentRequestIgnoresCache && nextDataSource === upstreamDataSource)
                readPosition + MIN_READ_BEFORE_CHECKING_CACHE
            else Long.MAX_VALUE
        if (checkCache) {
            Preconditions.checkState(this.isBypassingCache)
            if (nextDataSource === upstreamDataSource) {
                // Continue reading from upstream.
                return
            }
            // We're switching to reading from or writing to the cache.
            try {
                closeCurrentSource()
            } catch (e: Throwable) {
                if (nextSpan!!.isHoleSpan) {
                    // Release the hole span before throwing, else we'll hold it forever.
                    cache.releaseHoleSpan(nextSpan)
                }
                throw e
            }
        }

        if (nextSpan != null && nextSpan.isHoleSpan) {
            currentHoleSpan = nextSpan
        }
        currentDataSource = nextDataSource
        currentDataSpec = nextDataSpec
        currentDataSourceBytesRead = 0
        val resolvedLength = nextDataSource!!.open(nextDataSpec)

        // Update bytesRemaining, actualUri and (if writing to cache) the cache metadata.
        val mutations = ContentMetadataMutations()
        if (nextDataSpec.length == C.LENGTH_UNSET.toLong() && resolvedLength != C.LENGTH_UNSET.toLong()) {
            bytesRemaining = resolvedLength
            ContentMetadataMutations.setContentLength(
                mutations,
                readPosition + bytesRemaining
            )
        }
        if (this.isReadingFromUpstream) {
            actualUri = nextDataSource.uri
            val isRedirected = requestDataSpec.uri != actualUri
            ContentMetadataMutations.setRedirectedUri(
                mutations,
                if (isRedirected) actualUri else null
            )
        }
        if (this.isWritingToCache) {
            cache.applyContentMetadataMutations(key, mutations)
        }
    }

    @Throws(IOException::class)
    private fun setNoBytesRemainingAndMaybeStoreLength(key: String) {
        bytesRemaining = 0
        if (this.isWritingToCache) {
            val mutations = ContentMetadataMutations()
            ContentMetadataMutations.setContentLength(mutations, readPosition)
            cache.applyContentMetadataMutations(key, mutations)
        }
    }

    private val isReadingFromUpstream: Boolean
        get() = !this.isReadingFromCache

    private val isBypassingCache: Boolean
        get() = currentDataSource === upstreamDataSource

    private val isReadingFromCache: Boolean
        get() = currentDataSource === cacheReadDataSource

    private val isWritingToCache: Boolean
        get() = currentDataSource === cacheWriteDataSource

    @Throws(IOException::class)
    private fun closeCurrentSource() {
        if (currentDataSource == null) {
            return
        }
        try {
            currentDataSource!!.close()
        } finally {
            currentDataSpec = null
            currentDataSource = null
            if (currentHoleSpan != null) {
                cache.releaseHoleSpan(currentHoleSpan!!)
                currentHoleSpan = null
            }
        }
    }

    private fun handleBeforeThrow(exception: Throwable?) {
        if (this.isReadingFromCache || exception is Cache.CacheException) {
            seenCacheError = true
        }
    }

    private fun shouldIgnoreCacheForRequest(dataSpec: DataSpec): Int {
        if (ignoreCacheOnError && seenCacheError) {
            return CACHE_IGNORED_REASON_ERROR
        } else if (ignoreCacheForUnsetLengthRequests && dataSpec.length == C.LENGTH_UNSET.toLong()) {
            return CACHE_IGNORED_REASON_UNSET_LENGTH
        } else {
            return CACHE_NOT_IGNORED
        }
    }

    private fun notifyCacheIgnored(@CacheIgnoredReason reason: @CacheIgnoredReason Int) {
        if (eventListener != null) {
            eventListener.onCacheIgnored(reason)
        }
    }

    private fun notifyBytesRead() {
        if (eventListener != null && totalCachedBytesRead > 0) {
            eventListener.onCachedBytesRead(cache.cacheSpace, totalCachedBytesRead)
            totalCachedBytesRead = 0
        }
    }

    companion object {
        const val FLAG_BLOCK_ON_CACHE: Int = 1
        const  val FLAG_IGNORE_CACHE_ON_ERROR: Int = 1 shl 1 // 2
        const  val FLAG_IGNORE_CACHE_FOR_UNSET_LENGTH_REQUESTS: Int = 1 shl 2 // 4
        private const val CACHE_NOT_IGNORED = -1
        const val CACHE_IGNORED_REASON_ERROR: Int = 0
        const val CACHE_IGNORED_REASON_UNSET_LENGTH: Int = 1
        private const val MIN_READ_BEFORE_CHECKING_CACHE = (100 * 1024).toLong()

        private fun getRedirectedUriOrDefault(cache: Cache, key: String, defaultUri: Uri?): Uri? {
            val redirectedUri: Uri? =
                ContentMetadata.getRedirectedUri(cache.getContentMetadata(key))
            return redirectedUri ?: defaultUri
        }
    }
}
