package androidx.media3.exoplayer.source.preload

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.GuardedBy
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.Util.createHandler
import androidx.media3.common.util.Util.createHandlerForCurrentOrMainLooper
import androidx.media3.common.util.Util.newSingleThreadExecutor
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.util.ReleasableExecutor
import com.google.common.base.Preconditions
import com.google.common.base.Supplier

import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import kotlin.concurrent.Volatile
import kotlin.math.min

class PreCacheHelper internal constructor(
    private val mediaItem: MediaItem,
    private val testMediaSourceFactory: MediaSource.Factory?,
    private val downloadHelperFactory: DownloadHelper.Factory,
    private val downloaderFactory: DownloaderFactory,
    preCacheLooper: Looper,
    private val listener: Listener?
) {

    interface Listener {
        fun onPrepared(originalMediaItem: MediaItem, updatedMediaItem: MediaItem) {}
        fun onPreCacheProgress(
            mediaItem: MediaItem,
            contentLength: Long,
            bytesDownloaded: Long,
            percentageDownloaded: Float
        ) {
        }

        fun onPreCacheCompleted(mediaItem: MediaItem) {}
        fun onPrepareError(mediaItem: MediaItem, error: IOException) {}
        fun onDownloadError(mediaItem: MediaItem, error: IOException) {}
    }

    class Factory {
        private val cache: Cache
        private val preCacheLooper: Looper
        private val upstreamDataSourceFactory: DataSource.Factory
        private val renderersFactory: RenderersFactory
        private var trackSelectionParameters: TrackSelectionParameters
        private var downloadExecutor: Executor?
        private var listener: Listener? = null

        constructor(context: Context, cache: Cache, preCacheLooper: Looper) {
            this.cache = cache
            this.preCacheLooper = preCacheLooper
            this.upstreamDataSourceFactory = DefaultDataSource.Factory(context)
            this.trackSelectionParameters = TrackSelectionParameters.DEFAULT
            this.renderersFactory = DefaultRenderersFactory(context)
            this.downloadExecutor = Executor { obj: Runnable? -> obj!!.run() }
        }

        constructor(
            context: Context,
            cache: Cache,
            renderersFactory: RenderersFactory,
            preCacheLooper: Looper
        ) {
            this.cache = cache
            this.preCacheLooper = preCacheLooper
            this.upstreamDataSourceFactory = DefaultDataSource.Factory(context)
            this.trackSelectionParameters = TrackSelectionParameters.DEFAULT
            this.renderersFactory = renderersFactory
            this.downloadExecutor = Executor { obj: Runnable? -> obj!!.run() }
        }

        constructor(
            context: Context,
            cache: Cache,
            upstreamDataSourceFactory: DataSource.Factory,
            preCacheLooper: Looper
        ) {
            this.cache = cache
            this.preCacheLooper = preCacheLooper
            this.upstreamDataSourceFactory = upstreamDataSourceFactory
            this.trackSelectionParameters = TrackSelectionParameters.DEFAULT
            this.renderersFactory = DefaultRenderersFactory(context)
            this.downloadExecutor = Executor { obj: Runnable? -> obj!!.run() }
        }

        constructor(
            cache: Cache,
            upstreamDataSourceFactory: DataSource.Factory,
            renderersFactory: RenderersFactory,
            preCacheLooper: Looper
        ) {
            this.cache = cache
            this.preCacheLooper = preCacheLooper
            this.upstreamDataSourceFactory = upstreamDataSourceFactory
            this.trackSelectionParameters = TrackSelectionParameters.DEFAULT
            this.renderersFactory = renderersFactory
            this.downloadExecutor = Executor { obj: Runnable? -> obj!!.run() }
        }

        fun setTrackSelectionParameters(
            trackSelectionParameters: TrackSelectionParameters
        ): Factory {
            this.trackSelectionParameters = trackSelectionParameters
            return this
        }

        fun setDownloadExecutor(downloadExecutor: Executor?): Factory {
            this.downloadExecutor = downloadExecutor
            return this
        }

        fun setListener(listener: Listener?): Factory {
            this.listener = listener
            return this
        }

        fun create(mediaItem: MediaItem): PreCacheHelper {
            val cacheDataSourceFactory =
                CacheDataSource.Factory()
                    .setUpstreamDataSourceFactory(upstreamDataSourceFactory)
                    .setCache(cache)
            val downloadHelperFactory =
                DownloadHelper.Factory()
                    .setDataSourceFactory(cacheDataSourceFactory)
                    .setRenderersFactory(renderersFactory)
                    .setTrackSelectionParameters(trackSelectionParameters)
            val downloaderFactory: DownloaderFactory =
                DefaultDownloaderFactory(cacheDataSourceFactory, downloadExecutor!!)
            return PreCacheHelper(
                mediaItem,
                null,
                downloadHelperFactory,
                downloaderFactory,
                preCacheLooper,
                listener
            )
        }
    }

    private val preCacheHandler: Handler = createHandler(preCacheLooper, null)
    private val applicationHandler: Handler = createHandlerForCurrentOrMainLooper()
    private var currentDownloadCallback: DownloadCallback? = null

    fun preCache(startPositionMs: Long, durationMs: Long) {
        preCacheHandler.post(
            Runnable {
                if (currentDownloadCallback != null
                    && currentDownloadCallback!!.isReusable(startPositionMs, durationMs)
                ) {
                    return@Runnable
                } else if (currentDownloadCallback != null) {
                    currentDownloadCallback!!.cancel(false)
                }
                currentDownloadCallback = DownloadCallback(startPositionMs, durationMs)
            })
    }

    fun stop() {
        preCacheHandler.post {
            currentDownloadCallback?.cancel(false)
        }
    }

    fun release(removeCachedContent: Boolean) {
        preCacheHandler.post {
            currentDownloadCallback?.also {
                it.cancel(removeCachedContent)
                currentDownloadCallback = null
            }
            preCacheHandler.removeCallbacksAndMessages(null)
        }
    }

    private class ReleasableSingleThreadExecutor(private val releaseRunnable: Runnable?) :
        ReleasableExecutor {
        private val executor: ExecutorService? = newSingleThreadExecutor("PreCacheHelper:Loader")

        override fun release() {
            execute(releaseRunnable)
            executor!!.shutdown()
        }

        override fun execute(command: Runnable?) {
            executor!!.execute(command)
        }
    }

    private class ReleasableExecutorSupplier(private val preCacheHandler: Handler) :
        Supplier<ReleasableExecutor> {
        private var downloadCallback:DownloadCallback? = null

        @GuardedBy("this")
        private var executorCount = 0
        fun setDownloadCallback(downloadCallback: DownloadCallback?) {
            this.downloadCallback = downloadCallback
        }

        override fun get(): ReleasableSingleThreadExecutor {
            synchronized(this@ReleasableExecutorSupplier) {
                executorCount++
            }
            return ReleasableSingleThreadExecutor(Runnable { this.onExecutorReleased() })
        }

        fun onExecutorReleased() {
            synchronized(this@ReleasableExecutorSupplier) {
                Preconditions.checkState(executorCount > 0)
                executorCount--
                if (wereExecutorsReleased()) {
                    preCacheHandler.post {
                        Preconditions.checkState(wereExecutorsReleased())
                        if (downloadCallback != null) {
                            downloadCallback!!.maybeSubmitPendingDownloadRequest()
                        }
                    }
                }
            }
        }

        fun wereExecutorsReleased(): Boolean {
            synchronized(this@ReleasableExecutorSupplier) {
                return executorCount == 0
            }
        }
    }

    private inner class DownloadCallback(startPositionMs: Long, durationMs: Long) :
        DownloadHelper.Callback {
        private val lock: Any
        private val startPositionMs: Long
        private val durationMs: Long
        private val releasableExecutorSupplier: ReleasableExecutorSupplier?
        private val downloadHelper: DownloadHelper
        private var isPreparationOngoing: Boolean
        private var pendingDownloadRequest: DownloadRequest? = null
        private var downloader: Downloader? = null
        private var downloaderTask: Task? = null

        @GuardedBy("lock")
        private var isCanceled = false

        init {
            Preconditions.checkState(Looper.myLooper() == preCacheHandler.getLooper())
            this.lock = Any()
            this.startPositionMs = startPositionMs
            this.durationMs = durationMs
            if (testMediaSourceFactory != null) {
                this.releasableExecutorSupplier = null
                this.downloadHelper =
                    downloadHelperFactory.create(testMediaSourceFactory.createMediaSource(mediaItem)!!)
            } else {
                this.releasableExecutorSupplier = ReleasableExecutorSupplier(preCacheHandler)
                downloadHelperFactory.setLoadExecutor(releasableExecutorSupplier)
                this.downloadHelper = downloadHelperFactory.create(mediaItem)
                this.releasableExecutorSupplier.setDownloadCallback(this)
            }
            this.isPreparationOngoing = true
            this.downloadHelper.prepare(this)
        }

        override fun onPrepared(helper: DownloadHelper, tracksInfoAvailable: Boolean) {
            Preconditions.checkState(Looper.myLooper() == preCacheHandler.getLooper())
            Preconditions.checkState(helper == this.downloadHelper)
            isPreparationOngoing = false
            val downloadRequest =
                helper.getDownloadRequest(null, startPositionMs, durationMs)
            downloadHelper.release()
            val updatedMediaItem = downloadRequest.toMediaItem(mediaItem.buildUpon())
            notifyListeners { listener: Listener? ->
                listener!!.onPrepared(mediaItem, updatedMediaItem)
            }
            pendingDownloadRequest = downloadRequest
            if (releasableExecutorSupplier == null
                || releasableExecutorSupplier.wereExecutorsReleased()
            ) {
                maybeSubmitPendingDownloadRequest()
            }
        }

        override fun onPrepareError(helper: DownloadHelper, e: IOException) {
            Preconditions.checkState(Looper.myLooper() == preCacheHandler.looper)
            Preconditions.checkState(helper == this.downloadHelper)
            isPreparationOngoing = false
            downloadHelper.release()
            notifyListeners { listener: Listener? ->
                listener!!.onPrepareError(mediaItem, e)
            }
        }

        fun maybeSubmitPendingDownloadRequest() {
            Preconditions.checkState(Looper.myLooper() == preCacheHandler.looper)
            if (pendingDownloadRequest != null) {
                downloader = downloaderFactory.createDownloader(pendingDownloadRequest!!)
                downloaderTask = Task(
                    downloader!!,
                    false,
                    DEFAULT_MIN_RETRY_COUNT,
                    this
                )
                downloaderTask!!.start()
                pendingDownloadRequest = null
            }
        }

        fun onDownloadStopped(task: Task) {
            preCacheHandler.post(
                Runnable {
                    if (task !== downloaderTask) {
                        return@Runnable
                    }
                    downloaderTask = null
                    val finalException = task.finalException
                    if (!task.isRemove && finalException != null) {
                        notifyListeners { listener: Listener? ->
                            listener!!.onDownloadError(mediaItem, finalException)
                        }
                    } else if (!task.isRemove) {
                        notifyListeners { listener: Listener? ->
                            listener!!.onPreCacheCompleted(mediaItem)
                        }
                    }
                })
        }

        fun onDownloadProgress(task: Task) {
            preCacheHandler.post(
                Runnable {
                    if (task !== downloaderTask) {
                        return@Runnable
                    }
                    notifyListeners { listener: Listener? ->
                        listener!!.onPreCacheProgress(
                            mediaItem,
                            task.contentLength,
                            task.bytesDownloaded,
                            task.percentDownloaded
                        )
                    }
                })
        }

        fun cancel(removeCachedContent: Boolean) {
            Preconditions.checkState(Looper.myLooper() == preCacheHandler.looper)
            synchronized(lock) {
                isCanceled = true
            }
            pendingDownloadRequest = null
            downloadHelper.release()
            if (downloaderTask != null && downloaderTask!!.isRemove) {
                return
            } else if (downloaderTask != null) {
                downloaderTask!!.cancel()
            }
            if (removeCachedContent && downloader != null) {
                downloaderTask = Task(
                    downloader!!,
                    true,
                    DEFAULT_MIN_RETRY_COUNT,
                    this
                )
                downloaderTask!!.start()
            }
        }

        fun isReusable(startPositionMs: Long, durationMs: Long): Boolean {
            Preconditions.checkState(Looper.myLooper() == preCacheHandler.looper)
            synchronized(lock) {
                return !isCanceled && startPositionMs == this.startPositionMs && durationMs == this.durationMs && (isPreparationOngoing || (downloaderTask != null && !downloaderTask!!.isRemove))
            }
        }

        fun notifyListeners(callable: Consumer<Listener?>) {
            applicationHandler.post(
                Runnable {
                    synchronized(lock) {
                        if (isCanceled) {
                            return@Runnable
                        }
                        if (listener != null) {
                            callable.accept(listener)
                        }
                    }
                })
        }
    }

    private class Task(
        val downloader: Downloader,
        val isRemove: Boolean,
        val minRetryCount: Int,
        var downloadCallback: DownloadCallback?
    ) : Thread(), Downloader.ProgressListener {
        @Volatile
        var isCanceled = false

        @Volatile
        var finalException: IOException? = null

        @Volatile
        var contentLength: Long

        @Volatile
        var bytesDownloaded: Long = 0

        @Volatile
        var percentDownloaded = 0f

        init {
            this.contentLength = C.LENGTH_UNSET.toLong()
        }

        fun cancel() {
            downloadCallback = null
            if (!isCanceled) {
                isCanceled = true
                downloader.cancel()
                interrupt()
            }
        }

        // Methods running on download thread.
        override fun run() {
            try {
                if (isRemove) {
                    downloader.remove()
                } else {
                    var errorCount = 0
                    var errorPosition = C.LENGTH_UNSET.toLong()
                    while (!isCanceled) {
                        try {
                            downloader.download(this)
                            break
                        } catch (e: IOException) {
                            if (!isCanceled) {
                                if (this.bytesDownloaded != errorPosition) {
                                    errorPosition = this.bytesDownloaded
                                    errorCount = 0
                                }
                                if (++errorCount > minRetryCount) {
                                    throw e
                                }
                                sleep(getRetryDelayMillis(errorCount).toLong())
                            }
                        }
                    }
                }
            } catch (e: InterruptedException) {
                currentThread().interrupt()
            } catch (e: CancellationException) {
                // Do nothing.
            } catch (e: IOException) {
                finalException = e
            }
            if (downloadCallback != null) {
                downloadCallback!!.onDownloadStopped(this)
            }
        }

        override fun onProgress(
            contentLength: Long,
            bytesDownloaded: Long,
            percentDownloaded: Float
        ) {
            this.contentLength = contentLength
            this.bytesDownloaded = bytesDownloaded
            this.percentDownloaded = percentDownloaded
            downloadCallback?.onDownloadProgress(this)
        }

        companion object {
            private fun getRetryDelayMillis(errorCount: Int): Int {
                return min((errorCount - 1) * 1000, 5000)
            }
        }
    }

    companion object {
        const val DEFAULT_MIN_RETRY_COUNT: Int = 5
    }
}
