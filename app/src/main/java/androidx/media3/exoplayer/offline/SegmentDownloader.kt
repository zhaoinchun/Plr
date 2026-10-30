package androidx.media3.exoplayer.offline

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PriorityTaskManager
import androidx.media3.common.StreamKey
import androidx.media3.common.util.RunnableFutureTask
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.upstream.ParsingLoadable
import com.google.common.base.Supplier
import java.io.IOException
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.concurrent.Volatile

abstract class SegmentDownloader<M : FilterableManifest<M>>(
    mediaItem: MediaItem,
    private val manifestParser: ParsingLoadable.Parser<M>,
    private val cacheDataSourceFactory: CacheDataSource.Factory,
    private val executor: Executor,
    maxMergedSegmentStartTimeDiffMs: Long,
    private val startPositionUs: Long,
    private val durationUs: Long
) : Downloader {
    protected abstract class BaseFactory<M : FilterableManifest<M>>(
        protected val cacheDataSourceFactory: CacheDataSource.Factory,
        protected var manifestParser: ParsingLoadable.Parser<M>
    ) : SegmentDownloaderFactory {
        protected var executor: Executor?
        protected var maxMergedSegmentStartTimeDiffMs: Long
        protected var startPositionUs: Long = 0
        protected var durationUs: Long

        init {
            this.executor = Executor { obj: Runnable? -> obj!!.run() }
            this.maxMergedSegmentStartTimeDiffMs = DEFAULT_MAX_MERGED_SEGMENT_START_TIME_DIFF_MS
            this.durationUs = C.TIME_UNSET
        }

        override fun setExecutor(executor: Executor): BaseFactory<M> {
            this.executor = executor
            return this
        }

        override fun setMaxMergedSegmentStartTimeDiffMs(maxMergedSegmentStartTimeDiffMs: Long): BaseFactory<M> {
            this.maxMergedSegmentStartTimeDiffMs = maxMergedSegmentStartTimeDiffMs
            return this
        }

        override fun setStartPositionUs(startPositionUs: Long): BaseFactory<M> {
            this.startPositionUs = startPositionUs
            return this
        }

        override fun setDurationUs(durationUs: Long): BaseFactory<M> {
            this.durationUs = durationUs
            return this
        }
    }

    protected class Segment(
        val startTimeUs: Long,
        val dataSpec: DataSpec
    ) : Comparable<Segment> {
        override fun compareTo(other: Segment): Int {
            return startTimeUs.compareTo(other.startTimeUs)
        }
    }

    private val manifestDataSpec = getCompressibleDataSpec(mediaItem.localConfiguration!!.uri)
    private val streamKeys = ArrayList<StreamKey>(mediaItem.localConfiguration!!.streamKeys)
    private val cache: Cache = cacheDataSourceFactory.cache!!
    private val cacheKeyFactory = cacheDataSourceFactory.cacheKeyFactory
    private val priorityTaskManager: PriorityTaskManager? =
        cacheDataSourceFactory.getUpstreamPriorityTaskManager()
    private val maxMergedSegmentStartTimeDiffUs = Util.msToUs(maxMergedSegmentStartTimeDiffMs)
    private val activeRunnables: ArrayList<RunnableFutureTask<*, *>> =
        ArrayList<RunnableFutureTask<*, *>>()

    @Volatile
    private var isCanceled = false

    protected constructor(
        mediaItem: MediaItem,
        manifestParser: ParsingLoadable.Parser<M>,
        cacheDataSourceFactory: CacheDataSource.Factory,
        executor: Executor,
        maxMergedSegmentStartTimeDiffMs: Long
    ) : this(
        mediaItem,
        manifestParser,
        cacheDataSourceFactory,
        executor,
        maxMergedSegmentStartTimeDiffMs,
        0,
        C.TIME_UNSET
    )

    @Throws(IOException::class, InterruptedException::class)
    override fun download(progressListener: Downloader.ProgressListener?) {
        val pendingSegments = ArrayDeque<Segment>()
        val recycledRunnables: ArrayDeque<SegmentDownloadRunnable> =
            ArrayDeque<SegmentDownloadRunnable>()
        priorityTaskManager?.add(C.PRIORITY_DOWNLOAD)
        try {
            val dataSource = cacheDataSourceFactory.createDataSourceForDownloading()
            // Get the manifest and all of the segments.
            var manifest = getManifest(dataSource, manifestDataSpec, false)
            if (!streamKeys.isEmpty()) {
                manifest = manifest.copy(streamKeys)
            }
            val segments = getSegments(dataSource, manifest, false)

            // Sort the segments so that we download media in the right order from the start of the
            // content, and merge segments where possible to minimize the number of server round trips.
            Collections.sort<Segment?>(segments)
            Companion.mergeSegments(segments, cacheKeyFactory, maxMergedSegmentStartTimeDiffUs)

            // Scan the segments, removing any that are fully downloaded.
            val totalSegments = segments.size
            var segmentsDownloaded = 0
            var contentLength: Long = 0
            var bytesDownloaded: Long = 0
            for (i in segments.indices.reversed()) {
                val dataSpec = segments[i].dataSpec
                val cacheKey = cacheKeyFactory.buildCacheKey(dataSpec)
                var segmentLength = dataSpec.length
                if (segmentLength == C.LENGTH_UNSET.toLong()) {
                    val resourceLength =
                        ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
                    if (resourceLength != C.LENGTH_UNSET.toLong()) {
                        segmentLength = resourceLength - dataSpec.position
                    }
                }
                val segmentBytesDownloaded =
                    cache.getCachedBytes(cacheKey, dataSpec.position, segmentLength)
                bytesDownloaded += segmentBytesDownloaded
                if (segmentLength != C.LENGTH_UNSET.toLong()) {
                    if (segmentLength == segmentBytesDownloaded) {
                        // The segment is fully downloaded.
                        segmentsDownloaded++
                        segments.removeAt(i)
                    }
                    if (contentLength != C.LENGTH_UNSET.toLong()) {
                        contentLength += segmentLength
                    }
                } else {
                    contentLength = C.LENGTH_UNSET.toLong()
                }
            }

            // Download the segments.
            val progressNotifier =
                if (progressListener != null)
                    ProgressNotifier(
                        progressListener,
                        contentLength,
                        totalSegments,
                        bytesDownloaded,
                        segmentsDownloaded
                    )
                else
                    null
            pendingSegments.addAll(segments)
            while (!isCanceled && !pendingSegments.isEmpty()) {
                // Block until there aren't any higher priority tasks.
                priorityTaskManager?.proceed(C.PRIORITY_DOWNLOAD)

                // Create and execute a runnable to download the next segment.
                val segmentDataSource: CacheDataSource
                val temporaryBuffer: ByteArray?
                if (!recycledRunnables.isEmpty()) {
                    val recycledRunnable = recycledRunnables.removeFirst()
                    segmentDataSource = recycledRunnable.dataSource
                    temporaryBuffer = recycledRunnable.temporaryBuffer
                } else {
                    segmentDataSource = cacheDataSourceFactory.createDataSourceForDownloading()
                    temporaryBuffer = ByteArray(BUFFER_SIZE_BYTES)
                }
                val segment = pendingSegments.removeFirst()
                val downloadRunnable =
                    SegmentDownloadRunnable(
                        segment, segmentDataSource, progressNotifier, temporaryBuffer
                    )
                addActiveRunnable<Void?>(downloadRunnable)
                executor.execute(downloadRunnable)

                // Clean up runnables that have finished.
                for (j in activeRunnables.indices.reversed()) {
                    val activeRunnable: SegmentDownloadRunnable =
                        activeRunnables[j] as SegmentDownloadRunnable
                    // Only block until the runnable has finished if we don't have any more pending segments
                    // to start. If we do have pending segments to start then only process the runnable if
                    // it's already finished.
                    if (pendingSegments.isEmpty() || activeRunnable.isDone) {
                        try {
                            activeRunnable.get()
                            removeActiveRunnable(j)
                            recycledRunnables.addLast(activeRunnable)
                        } catch (e: ExecutionException) {
                            val cause = e.cause!!
                            if (cause is PriorityTaskManager.PriorityTooLowException) {
                                // We need to schedule this segment again in a future loop iteration.
                                pendingSegments.addFirst(activeRunnable.segment)
                                removeActiveRunnable(j)
                                recycledRunnables.addLast(activeRunnable)
                            } else if (cause is IOException) {
                                throw cause
                            } else {
                                // The cause must be an uncaught Throwable type.
                                throw cause
                            }
                        }
                    }
                }

                // Don't move on to the next segment until the runnable for this segment has started. This
                // drip feeds runnables to the executor, rather than providing them all up front.
                downloadRunnable.blockUntilStarted()
            }
        } finally {
            // If one of the runnables has thrown an exception, then it's possible there are other active
            // runnables still doing work. We need to wait until they finish before exiting this method.
            // Cancel them to speed this up.
            for (i in activeRunnables.indices) {
                activeRunnables[i].cancel(true)
            }
            // Wait until the runnables have finished. In addition to the failure case, we also need to
            // do this for the case where the main download thread was interrupted as part of cancelation.
            for (i in activeRunnables.indices.reversed()) {
                activeRunnables[i].blockUntilFinished()
                removeActiveRunnable(i)
            }
            priorityTaskManager?.remove(C.PRIORITY_DOWNLOAD)
        }
    }

    override fun cancel() {
        synchronized(activeRunnables) {
            isCanceled = true
            for (i in activeRunnables.indices) {
                activeRunnables[i].cancel(true)
            }
        }
    }

    override fun remove() {
        val dataSource = cacheDataSourceFactory.createDataSourceForRemovingDownload()
        try {
            val manifest = getManifest(dataSource, manifestDataSpec, true)
            val segments = getSegments(dataSource, manifest, true)
            for (i in segments.indices) {
                cache.removeResource(cacheKeyFactory.buildCacheKey(segments[i].dataSpec))
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            // Ignore exceptions when removing.
        } finally {
            // Always attempt to remove the manifest.
            cache.removeResource(cacheKeyFactory.buildCacheKey(manifestDataSpec))
        }
    }

    // Internal methods.

    @Throws(InterruptedException::class, IOException::class)
    protected fun getManifest(dataSource: DataSource, dataSpec: DataSpec, removing: Boolean): M {
        return getManifest(dataSource, manifestParser, dataSpec, removing)
    }

    @Throws(InterruptedException::class, IOException::class)
    protected fun getManifest(
        dataSource: DataSource,
        manifestParser: ParsingLoadable.Parser<M>,
        dataSpec: DataSpec,
        removing: Boolean
    ): M {
        return execute<M>(
            {
                object : RunnableFutureTask<M, IOException>() {
                    @Throws(IOException::class)
                    override fun doWork(): M {
                        return ParsingLoadable.load<M>(
                            dataSource, manifestParser, dataSpec, C.DATA_TYPE_MANIFEST
                        )
                    }
                }
            },
            removing
        )
    }

    @Throws(InterruptedException::class, IOException::class)
    protected fun <T> execute(
        runnable: Supplier<RunnableFutureTask<T, *>>,
        removing: Boolean
    ): T {
        if (removing) {
            val task: RunnableFutureTask<T, *> = runnable.get()!!
            task.run()
            try {
                return task.get()
            } catch (e: ExecutionException) {
                val cause = e.cause!!
                if (cause is IOException) {
                    throw cause
                } else {
                    // The cause must be an uncaught Throwable type.
                    throw cause
                }
            }
        }
        while (true) {
            if (isCanceled) {
                throw InterruptedException()
            }
            // Block until there aren't any higher priority tasks.
            priorityTaskManager?.proceed(C.PRIORITY_DOWNLOAD)
            val task: RunnableFutureTask<T, *> = runnable.get()!!
            addActiveRunnable<T>(task)
            executor.execute(task)
            try {
                return task.get()
            } catch (e: ExecutionException) {
                val cause = e.cause!!
                if (cause is PriorityTaskManager.PriorityTooLowException) {
                    // The next loop iteration will block until the task is able to proceed.
                } else if (cause is IOException) {
                    throw cause
                } else {
                    // The cause must be an uncaught Throwable type.
                    throw e
                }
            } finally {
                // We don't want to return for as long as the runnable might still be doing work.
                task.blockUntilFinished()
                removeActiveRunnable(task)
            }
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    protected abstract fun getSegments(
        dataSource: DataSource?,
        manifest: M,
        removing: Boolean
    ): MutableList<Segment>

    @Throws(InterruptedException::class)
    private fun <T> addActiveRunnable(runnable: RunnableFutureTask<T, *>) {
        synchronized(activeRunnables) {
            if (isCanceled) {
                throw InterruptedException()
            }
            activeRunnables.add(runnable)
        }
    }

    private fun removeActiveRunnable(runnable: RunnableFutureTask<*, *>?) {
        synchronized(activeRunnables) {
            activeRunnables.remove(runnable)
        }
    }

    private fun removeActiveRunnable(index: Int) {
        synchronized(activeRunnables) {
            activeRunnables.removeAt(index)
        }
    }

    private class SegmentDownloadRunnable(
        val segment: Segment,
        val dataSource: CacheDataSource,
        private val progressNotifier: ProgressNotifier?,
        val temporaryBuffer: ByteArray?
    ) : RunnableFutureTask<Void?, IOException?>() {
        private val cacheWriter: CacheWriter =
            CacheWriter(dataSource, segment.dataSpec, temporaryBuffer, progressNotifier)

        @Throws(IOException::class)
        override fun doWork(): Void? {
            cacheWriter.cache()
            progressNotifier?.onSegmentDownloaded()
            return null
        }

        override fun cancelWork() {
            cacheWriter.cancel()
        }
    }

    private class ProgressNotifier(
        private val progressListener: Downloader.ProgressListener,
        private val contentLength: Long,
        private val totalSegments: Int,
        private var bytesDownloaded: Long,
        private var segmentsDownloaded: Int
    ) : CacheWriter.ProgressListener {
        override fun onProgress(
            requestLength: Long,
            bytesCached: Long,
            newBytesCached: Long
        ) {
            bytesDownloaded += newBytesCached
            progressListener.onProgress(contentLength, bytesDownloaded, this.percentDownloaded)
        }

        fun onSegmentDownloaded() {
            segmentsDownloaded++
            progressListener.onProgress(contentLength, bytesDownloaded, this.percentDownloaded)
        }

        val percentDownloaded: Float
            get() {
                return if (contentLength != C.LENGTH_UNSET.toLong() && contentLength != 0L) {
                    Util.percentFloat(bytesDownloaded, contentLength)
                } else if (totalSegments != 0) {
                    Util.percentFloat(segmentsDownloaded.toLong(), totalSegments.toLong())
                } else {
                    C.PERCENTAGE_UNSET.toFloat()
                }
            }
    }

    companion object {
        const val DEFAULT_MAX_MERGED_SEGMENT_START_TIME_DIFF_MS: Long = 20 * C.MILLIS_PER_SECOND
        private const val BUFFER_SIZE_BYTES = 128 * 1024

        protected fun getCompressibleDataSpec(uri: Uri?): DataSpec {
            return DataSpec.Builder().setUri(uri).setFlags(DataSpec.FLAG_ALLOW_GZIP).build()
        }

        private fun mergeSegments(
            segments: MutableList<Segment>,
            keyFactory: CacheKeyFactory,
            maxMergedSegmentStartTimeDiffUs: Long
        ) {
            val lastIndexByCacheKey = HashMap<String?, Int?>()
            var nextOutIndex = 0
            for (i in segments.indices) {
                val segment = segments[i]
                val cacheKey = keyFactory.buildCacheKey(segment.dataSpec)
                val lastIndex = lastIndexByCacheKey.get(cacheKey)
                val lastSegment = if (lastIndex == null) null else segments.get(lastIndex)
                if (lastSegment == null || segment.startTimeUs > lastSegment.startTimeUs + maxMergedSegmentStartTimeDiffUs || !canMergeSegments(
                        lastSegment.dataSpec,
                        segment.dataSpec
                    )
                ) {
                    lastIndexByCacheKey.put(cacheKey, nextOutIndex)
                    segments.set(nextOutIndex, segment)
                    nextOutIndex++
                } else {
                    val mergedLength =
                        if (segment.dataSpec.length == C.LENGTH_UNSET.toLong())
                            C.LENGTH_UNSET
                                .toLong()
                        else
                            lastSegment.dataSpec.length + segment.dataSpec.length
                    val mergedDataSpec =
                        lastSegment.dataSpec.subrange(0, mergedLength)
                    segments[lastIndex!!] = Segment(lastSegment.startTimeUs, mergedDataSpec)
                }
            }
            Util.removeRange(segments, nextOutIndex, segments.size)
        }

        private fun canMergeSegments(dataSpec1: DataSpec, dataSpec2: DataSpec): Boolean {
            return dataSpec1.uri == dataSpec2.uri
                    && dataSpec1.length != C.LENGTH_UNSET.toLong() && (dataSpec1.position + dataSpec1.length == dataSpec2.position)
                    && dataSpec1.key == dataSpec2.key
                    && dataSpec1.flags == dataSpec2.flags && dataSpec1.httpMethod == dataSpec2.httpMethod && dataSpec1.httpRequestHeaders == dataSpec2.httpRequestHeaders
        }
    }
}
