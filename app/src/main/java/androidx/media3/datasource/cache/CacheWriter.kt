package androidx.media3.datasource.cache

import androidx.annotation.WorkerThread
import androidx.media3.common.C
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.concurrent.Volatile

class CacheWriter(
    private val dataSource: CacheDataSource,
    dataSpec: DataSpec,
    temporaryBuffer: ByteArray?,
    progressListener: ProgressListener?
) {
    fun interface ProgressListener {

        fun onProgress(requestLength: Long, bytesCached: Long, newBytesCached: Long)
    }

    private val cache: Cache
    private val dataSpec: DataSpec
    private val cacheKey: String
    private val temporaryBuffer: ByteArray
    private val progressListener: ProgressListener?

    private var nextPosition: Long
    private var endPosition: Long = 0
    private var bytesCached: Long = 0

    @Volatile
    private var isCanceled = false

    init {
        this.cache = dataSource.cache
        this.dataSpec = dataSpec
        this.temporaryBuffer =
            if (temporaryBuffer == null) ByteArray(DEFAULT_BUFFER_SIZE_BYTES) else temporaryBuffer
        this.progressListener = progressListener
        cacheKey = dataSource.cacheKeyFactory.buildCacheKey(dataSpec)
        nextPosition = dataSpec.position
    }

    fun cancel() {
        isCanceled = true
    }

    @WorkerThread
    @Throws(IOException::class)
    fun cache() {
        throwIfCanceled()

        bytesCached = cache.getCachedBytes(cacheKey, dataSpec.position, dataSpec.length)
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            endPosition = dataSpec.position + dataSpec.length
        } else {
            val contentLength: Long =
                ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
            endPosition =
                if (contentLength == C.LENGTH_UNSET.toLong()) C.INDEX_UNSET.toLong() else contentLength
        }
        if (progressListener != null) {
            progressListener.onProgress(this.length, bytesCached,  0)
        }

        while (endPosition == C.INDEX_UNSET.toLong() || nextPosition < endPosition) {
            throwIfCanceled()
            val maxRemainingLength =
                if (endPosition == C.INDEX_UNSET.toLong()) Long.MAX_VALUE else endPosition - nextPosition
            var blockLength = cache.getCachedLength(cacheKey, nextPosition, maxRemainingLength)
            if (blockLength > 0) {
                nextPosition += blockLength
            } else {
                // There's a hole of length -blockLength.
                blockLength = -blockLength
                val nextRequestLength =
                    if (blockLength == Long.MAX_VALUE) C.LENGTH_UNSET.toLong() else blockLength
                nextPosition += readBlockToCache(nextPosition, nextRequestLength)
            }
        }
    }

    @Throws(IOException::class)
    private fun readBlockToCache(position: Long, length: Long): Long {
        val isLastBlock = position + length == endPosition || length == C.LENGTH_UNSET.toLong()

        var resolvedLength = C.LENGTH_UNSET.toLong()
        var isDataSourceOpen = false
        if (length != C.LENGTH_UNSET.toLong()) {
            // If the length is specified, try to open the data source with a bounded request to avoid
            // the underlying network stack requesting more data than required.
            val boundedDataSpec: DataSpec =
                dataSpec.buildUpon().setPosition(position).setLength(length).build()
            try {
                resolvedLength = dataSource.open(boundedDataSpec)
                isDataSourceOpen = true
            } catch (e: Exception) {
                DataSourceUtil.closeQuietly(dataSource)
            }
        }

        if (!isDataSourceOpen) {
            // Either the length was unspecified, or we allow short content and our attempt to open the
            // DataSource with the specified length failed.
            throwIfCanceled()
            val unboundedDataSpec: DataSpec =
                dataSpec.buildUpon().setPosition(position).setLength(C.LENGTH_UNSET.toLong())
                    .build()
            try {
                resolvedLength = dataSource.open(unboundedDataSpec)
            } catch (e: Exception) {
                DataSourceUtil.closeQuietly(dataSource)
                throw e
            }
        }

        var totalBytesRead = 0
        try {
            if (isLastBlock && resolvedLength != C.LENGTH_UNSET.toLong()) {
                onRequestEndPosition(position + resolvedLength)
            }
            var bytesRead = 0
            while (bytesRead != C.RESULT_END_OF_INPUT) {
                throwIfCanceled()
                bytesRead = dataSource.read(temporaryBuffer,  0, temporaryBuffer.size)
                if (bytesRead != C.RESULT_END_OF_INPUT) {
                    onNewBytesCached(bytesRead.toLong())
                    totalBytesRead += bytesRead
                }
            }
            if (isLastBlock) {
                onRequestEndPosition(position + totalBytesRead)
            }
        } catch (e: Exception) {
            DataSourceUtil.closeQuietly(dataSource)
            throw e
        }

        // Util.closeQuietly(dataSource) is not used here because it's important that an exception is
        // thrown if DataSource.close fails. This is because there's no way of knowing whether the block
        // was successfully cached in this case.
        dataSource.close()
        return totalBytesRead.toLong()
    }

    private fun onRequestEndPosition(endPosition: Long) {
        if (this.endPosition == endPosition) {
            return
        }
        this.endPosition = endPosition
        if (progressListener != null) {
            progressListener.onProgress(this.length, bytesCached,  0)
        }
    }

    private fun onNewBytesCached(newBytesCached: Long) {
        bytesCached += newBytesCached
        if (progressListener != null) {
            progressListener.onProgress(this.length, bytesCached, newBytesCached)
        }
    }

    private val length: Long
        get() = if (endPosition == C.INDEX_UNSET.toLong()) C.LENGTH_UNSET.toLong() else endPosition - dataSpec.position

    @Throws(InterruptedIOException::class)
    private fun throwIfCanceled() {
        if (isCanceled) {
            throw InterruptedIOException()
        }
    }

    companion object {
        
        val DEFAULT_BUFFER_SIZE_BYTES: Int = 128 * 1024
    }
}
