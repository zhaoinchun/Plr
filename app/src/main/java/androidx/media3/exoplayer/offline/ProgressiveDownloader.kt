
package androidx.media3.exoplayer.offline

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PriorityTaskManager
import androidx.media3.common.util.RunnableFutureTask
import androidx.media3.common.util.Util.percentFloat
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter

import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.concurrent.Volatile

class ProgressiveDownloader(
    mediaItem: MediaItem,
    cacheDataSourceFactory: CacheDataSource.Factory,
    executor: Executor = Executor { obj: Runnable? -> obj!!.run() },
    position: Long = 0,
    length: Long = C.LENGTH_UNSET.toLong()
) : Downloader {
    private val executor: Executor = executor
    val dataSpec: DataSpec = DataSpec.Builder()
        .setUri(mediaItem.localConfiguration!!.uri)
        .setKey(mediaItem.localConfiguration.customCacheKey)
        .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
        .setPosition(position)
        .setLength(length)
        .build()

    private val dataSource = cacheDataSourceFactory.createDataSourceForDownloading()
    private val cacheWriter: CacheWriter
    private val priorityTaskManager: PriorityTaskManager?
    private var progressListener: Downloader.ProgressListener? = null

    @Volatile
    private var downloadRunnable:RunnableFutureTask<Void?, IOException?>? = null

    @Volatile
    private var isCanceled = false

    constructor(
        mediaItem: MediaItem,
        cacheDataSourceFactory: CacheDataSource.Factory,
        position: Long,
        length: Long
    ) : this(
        mediaItem, cacheDataSourceFactory,  
        Executor { obj: Runnable? -> obj!!.run() }, position, length
    )

    init {
        val progressListener: CacheWriter.ProgressListener =
            CacheWriter.ProgressListener { contentLength: Long, bytesCached: Long, newBytesCached: Long ->
                this.onProgress(
                    contentLength,
                    bytesCached,
                    newBytesCached
                )
            }
        cacheWriter =
            CacheWriter(dataSource, dataSpec,  null, progressListener)
        priorityTaskManager = cacheDataSourceFactory.getUpstreamPriorityTaskManager()
    }

    @Throws(IOException::class, InterruptedException::class)
    override fun download(progressListener: Downloader.ProgressListener?) {
        this.progressListener = progressListener
        priorityTaskManager?.add(C.PRIORITY_DOWNLOAD)
        try {
            var finished = false
            while (!finished && !isCanceled) {
                // Recreate downloadRunnable on each loop iteration to avoid rethrowing a previous error.
                downloadRunnable =
                    object : RunnableFutureTask<Void?, IOException?>() {
                        @Throws(IOException::class)
                        override fun doWork(): Void? {
                            cacheWriter.cache()
                            return null
                        }

                        override fun cancelWork() {
                            cacheWriter.cancel()
                        }
                    }
                priorityTaskManager?.proceed(C.PRIORITY_DOWNLOAD)
                executor.execute(downloadRunnable)
                try {
                    downloadRunnable!!.get()
                    finished = true
                } catch (e: ExecutionException) {
                    val cause = e.cause!!
                    if (cause is PriorityTaskManager.PriorityTooLowException) {
                        // The next loop iteration will block until the task is able to proceed.
                    } else if (cause is IOException) {
                        throw cause
                    } else {
                        // The cause must be an uncaught Throwable type.
                       throw  cause
                    }
                }
            }
        } finally {
            // If the main download thread was interrupted as part of cancelation, then it's possible that
            // the runnable is still doing work. We need to wait until it's finished before returning.
            downloadRunnable!!.blockUntilFinished()
            priorityTaskManager?.remove(C.PRIORITY_DOWNLOAD)
        }
    }

    override fun cancel() {
        isCanceled = true
        val downloadRunnable = this.downloadRunnable
        downloadRunnable?.cancel( true)
    }

    override fun remove() {
        dataSource.cache.removeResource(dataSource.cacheKeyFactory.buildCacheKey(dataSpec))
    }

    private fun onProgress(contentLength: Long, bytesCached: Long, newBytesCached: Long) {
        if (progressListener == null) {
            return
        }
        val percentDownloaded: Float =
            if (contentLength == C.LENGTH_UNSET.toLong() || contentLength == 0L)
                C.PERCENTAGE_UNSET.toFloat()
            else
                percentFloat(bytesCached, contentLength)
        progressListener!!.onProgress(contentLength, bytesCached, percentDownloaded)
    }
}
