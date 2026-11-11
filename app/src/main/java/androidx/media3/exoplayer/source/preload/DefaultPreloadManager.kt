package androidx.media3.exoplayer.source.preload

import android.content.Context
import android.os.HandlerThread
import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.HandlerWrapper
import androidx.media3.common.util.Util.msToUs
import androidx.media3.common.util.Util.usToMs
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRendererCapabilitiesList
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.PlaybackLooperProvider
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager.PreloadStatus
import androidx.media3.exoplayer.source.preload.PreloadMediaSource.PreloadControl
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.google.common.base.Preconditions
import com.google.common.base.Predicate
import com.google.common.base.Supplier
import com.google.common.base.Suppliers
import java.io.IOException
import java.util.concurrent.Executor
import kotlin.math.abs

class DefaultPreloadManager(
    context: Context,
    rankingDataComparator: RankingDataComparator<Int>,
    targetPreloadStatusControl: TargetPreloadStatusControl<Int, PreloadStatus>,
    mediaSourceFactory: MediaSource.Factory,
    var dataSourceFactory: DataSource.Factory? = null,
    var preloadLooperProvider: PlaybackLooperProvider= PlaybackLooperProvider(),
    var trackSelectorFactory: TrackSelector.Factory
      =TrackSelector.Factory { context: Context? -> DefaultTrackSelector(context) },
    var bandwidthMeterSupplier: BandwidthMeter = DefaultBandwidthMeter.getSingletonInstance(
        context
    ),
    var renderersFactorySupplier: Supplier<RenderersFactory>,
    var loadControlSupplier: LoadControl = DefaultLoadControl(),
    var cache: Cache? = null,
    var cachingExecutor: Executor = Executor { obj: Runnable? -> obj!!.run() },
    var clock: Clock = Clock.DEFAULT,
    var buildCalled: Boolean = false,
    var buildExoPlayerCalled: Boolean = false,
) :
    BasePreloadManager<Int, PreloadStatus>(
        rankingDataComparator,
        targetPreloadStatusControl,
        mediaSourceFactory
    ) {

    class PreloadStatus private constructor(
        stage: Int,
        startPositionMs: Long,
        durationMs: Long
    ) {
        val stage: Int

        val startPositionMs: Long
        val durationMs: Long

        init {
            Preconditions.checkArgument(startPositionMs == C.TIME_UNSET || startPositionMs >= 0)
            Preconditions.checkArgument(durationMs == C.TIME_UNSET || durationMs >= 0)
            this.stage = stage
            this.startPositionMs = startPositionMs
            this.durationMs = durationMs
        }

        val isPreloadingCategory: Boolean
            get() = stage == STAGE_SOURCE_PREPARED || stage == STAGE_TRACKS_SELECTED || stage == STAGE_SPECIFIED_RANGE_LOADED

        val isPreCachingCategory: Boolean
            get() = stage == STAGE_SPECIFIED_RANGE_CACHED

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj == null || javaClass != obj.javaClass) {
                return false
            }
            val other = obj as PreloadStatus
            return stage == other.stage && startPositionMs == other.startPositionMs && durationMs == other.durationMs
        }

        override fun hashCode(): Int {
            var result = 17
            result = 31 * result + stage
            result = 31 * result + startPositionMs.toInt()
            result = 31 * result + durationMs.toInt()
            return result
        }

        companion object {
            const val STAGE_NOT_PRELOADED: Int = Int.MIN_VALUE
            const val STAGE_SPECIFIED_RANGE_CACHED: Int = -1
            const val STAGE_SOURCE_PREPARED: Int = 0
            const val STAGE_TRACKS_SELECTED: Int = 1
            const val STAGE_SPECIFIED_RANGE_LOADED: Int = 2
            val PRELOAD_STATUS_NOT_PRELOADED: PreloadStatus = PreloadStatus(
                STAGE_NOT_PRELOADED, C.TIME_UNSET, 0
            )
            val PRELOAD_STATUS_SOURCE_PREPARED: PreloadStatus = PreloadStatus(
                STAGE_SOURCE_PREPARED, C.TIME_UNSET, 0
            )
            val PRELOAD_STATUS_TRACKS_SELECTED: PreloadStatus = PreloadStatus(
                STAGE_TRACKS_SELECTED, C.TIME_UNSET, 0
            )

            fun specifiedRangeLoaded(durationMs: Long): PreloadStatus {
                return PreloadStatus(
                    STAGE_SPECIFIED_RANGE_LOADED, C.TIME_UNSET, durationMs
                )
            }

            fun specifiedRangeLoaded(startPositionMs: Long, durationMs: Long): PreloadStatus {
                return PreloadStatus(STAGE_SPECIFIED_RANGE_LOADED, startPositionMs, durationMs)
            }

            fun specifiedRangeCached(durationMs: Long): PreloadStatus {
                return PreloadStatus(
                    STAGE_SPECIFIED_RANGE_CACHED, C.TIME_UNSET, durationMs
                )
            }

            fun specifiedRangeCached(startPositionMs: Long, durationMs: Long): PreloadStatus {
                return PreloadStatus(STAGE_SPECIFIED_RANGE_CACHED, startPositionMs, durationMs)
            }
        }
    }

    class SimpleRankingDataComparator : RankingDataComparator<Int> {
        private var currentPlayingIndex: Int
        private var invalidationListener: RankingDataComparator.InvalidationListener? = null

        init {
            this.currentPlayingIndex = C.INDEX_UNSET
        }

        override fun setInvalidationListener(invalidationListener: RankingDataComparator.InvalidationListener?) {
            this.invalidationListener = invalidationListener
        }

        fun setCurrentPlayingIndex(currentPlayingIndex: Int) {
            if (currentPlayingIndex != this.currentPlayingIndex) {
                this.currentPlayingIndex = currentPlayingIndex
                if (invalidationListener != null) {
                    invalidationListener!!.onRankingDataComparatorInvalidated()
                }
            }
        }

        override fun compare(o1: Int, o2: Int): Int {
            return Integer.compare(abs(o1 - currentPlayingIndex), abs(o2 - currentPlayingIndex))
        }

        protected fun getCurrentPlayingIndex(): Int {
            return currentPlayingIndex
        }
    }

    private val rendererCapabilitiesList =
        DefaultRendererCapabilitiesList.Factory(renderersFactorySupplier.get())
            .createRendererCapabilitiesList()
    private val trackSelector: TrackSelector =
        trackSelectorFactory.createTrackSelector(context)
    private val preloadMediaSourceFactory: PreloadMediaSource.Factory
    private val preCacheThread: HandlerThread?
    private val preCacheHelperFactory: PreCacheHelper.Factory?
    private val preloadHandler: HandlerWrapper?
    private var releaseCalled = false

    init {
        trackSelector.init({ parameters: TrackSelectionParameters? ->
            trackSelector.onParametersActivated(
                parameters
            )
        }, bandwidthMeterSupplier)
        val preloadLooper = preloadLooperProvider.obtainLooper()
        preloadMediaSourceFactory =
            PreloadMediaSource.Factory(
                mediaSourceFactory,
                PreloadMediaSourceControl(),
                trackSelector,
                bandwidthMeterSupplier,
                rendererCapabilitiesList.rendererCapabilities,
                loadControlSupplier,
                preloadLooper
            )
                .setClock(clock)
        val cache = cache
        if (cache != null) {
            preCacheThread = HandlerThread("DefaultPreloadManager:PreCacheHelper")
            preCacheThread.start()
            val upstreamDataSourceFactory: DataSource.Factory =
                (if (dataSourceFactory != null)
                    dataSourceFactory
                else
                    androidx.media3.datasource.DefaultDataSource.Factory(context))!!
            preCacheHelperFactory =
                PreCacheHelper.Factory(
                   context, cache, upstreamDataSourceFactory, preCacheThread.getLooper()
                )
                    .setDownloadExecutor(cachingExecutor)
                    .setListener(PreCacheHelperListener())
        } else {
            preCacheThread = null
            preCacheHelperFactory = null
        }
        preloadHandler = clock.createHandler(preloadLooper, null)
    }

    fun setCurrentPlayingIndex(currentPlayingIndex: Int) {
        val rankingDataComparator =
            this.rankingDataComparator as SimpleRankingDataComparator
        rankingDataComparator.setCurrentPlayingIndex(currentPlayingIndex)
    }

    override fun createMediaSourceHolder(
        mediaItem: MediaItem, mediaSource: MediaSource?, rankingData: Int
    ): MediaSourceHolder {
        val preloadMediaSource: PreloadMediaSource =
            (if (mediaSource != null)
                preloadMediaSourceFactory.createMediaSource(mediaSource)
            else
                preloadMediaSourceFactory.createMediaSource(mediaItem))
        return PreloadMediaSourceHolder(mediaItem, preloadMediaSource, rankingData)
    }

    override fun preloadMediaSourceHolderInternal(
        mediaSourceHolder: MediaSourceHolder, targetPreloadStatus: PreloadStatus
    ) {
        if (releaseCalled) {
            return
        }
        Preconditions.checkArgument(mediaSourceHolder is PreloadMediaSourceHolder)
        val preloadMediaSourceHolder: PreloadMediaSourceHolder =
            mediaSourceHolder as PreloadMediaSourceHolder
        val preloadMediaSource = preloadMediaSourceHolder.mediaSource as PreloadMediaSource
        maybeClearPreloadMediaSource(preloadMediaSource, targetPreloadStatus)
        if (targetPreloadStatus == PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED) {
            onSkipped(
                preloadMediaSource
            ) { preloadStatus: PreloadStatus? -> preloadStatus!!.stage == PreloadStatus.STAGE_NOT_PRELOADED }
        } else if (targetPreloadStatus.stage == PreloadStatus.STAGE_SPECIFIED_RANGE_CACHED) {
            if (preloadMediaSourceHolder.preCacheHelper == null) {
                val preCacheHelperFactory =
                    Preconditions.checkNotNull<PreCacheHelper.Factory>(
                        this.preCacheHelperFactory,
                        "DefaultPreloadManager wasn't configured with a Cache"
                    )
                preloadMediaSourceHolder.preCacheHelper =
                    preCacheHelperFactory.create(mediaSourceHolder.mediaItem)
            }
            preloadMediaSourceHolder.preCacheHelper!!.preCache(
                targetPreloadStatus.startPositionMs,
                targetPreloadStatus.durationMs
            )
        } else {
            preloadMediaSource.preload(msToUs(targetPreloadStatus.startPositionMs))
        }
    }

    private fun maybeClearPreloadMediaSource(
        preloadMediaSource: PreloadMediaSource, targetPreloadStatus: PreloadStatus
    ) {
        if (targetPreloadStatus.stage == PreloadStatus.STAGE_NOT_PRELOADED || targetPreloadStatus.stage == PreloadStatus.STAGE_SPECIFIED_RANGE_CACHED || targetPreloadStatus.stage == PreloadStatus.STAGE_SOURCE_PREPARED) {
            // For simplicity, we only attempt to clear the in-memory sample data when the apps
            // temporarily don't require the current media to be loaded, which means that the cached
            // content on disk may still persist. All data will be removed only when this media is
            // removed from DefaultPreloadManager.
            preloadMediaSource.clear()
        }
    }

    override fun releaseMediaSourceHolderInternal(mediaSourceHolder: MediaSourceHolder) {
        if (releaseCalled) {
            return
        }
        super.releaseMediaSourceHolderInternal(mediaSourceHolder)
        Preconditions.checkArgument(mediaSourceHolder is PreloadMediaSourceHolder)
        val preloadMediaSourceHolder: PreloadMediaSourceHolder =
            mediaSourceHolder as PreloadMediaSourceHolder
        (preloadMediaSourceHolder.mediaSource as PreloadMediaSource).releasePreloadMediaSource()
        if (preloadMediaSourceHolder.preCacheHelper != null) {
            preloadMediaSourceHolder.preCacheHelper!!.release(true)
            preloadMediaSourceHolder.preCacheHelper = null
        }
    }

    override fun releaseInternal() {
        releaseCalled = true
        releasePreloadUtils()
        releasePreCacheUtils()
    }

    private fun releasePreloadUtils() {
        preloadHandler!!.post(
            Runnable {
                rendererCapabilitiesList.release()
                trackSelector.release()
                preloadLooperProvider.releaseLooper()
            })
    }

    private fun releasePreCacheUtils() {
        preCacheThread?.quit()
    }

    private inner class PreCacheHelperListener : PreCacheHelper.Listener {
        override fun onPrepared(originalMediaItem: MediaItem, updatedMediaItem: MediaItem) {
            val targetPreloadStatus =
                getTargetPreloadStatusIfCurrentlyPreloading(originalMediaItem)
            if (targetPreloadStatus == null || !targetPreloadStatus.isPreCachingCategory) {
                // If the originalMediaItem is not the currently pre-caching, skip silently as invalidate()
                // must have called.
                return
            }
            val updatedMediaSource: MediaSource =
                preloadMediaSourceFactory.createMediaSource(updatedMediaItem)!!
            onMediaSourceUpdated(originalMediaItem, updatedMediaSource)
        }

        override fun onPreCacheCompleted(mediaItem: MediaItem) {
            val targetPreloadStatus = getTargetPreloadStatusIfCurrentlyPreloading(mediaItem)
            if (targetPreloadStatus == null || !targetPreloadStatus.isPreCachingCategory) {
                // If the mediaItem is not the currently caching, skip silently as invalidate() must have
                // been called, and a new sequence of preloading must have started.
                return
            }
            this@DefaultPreloadManager.onCompleted(
                mediaItem,
                Predicate { preloadStatus: PreloadStatus? -> preloadStatus == targetPreloadStatus })
        }

        override fun onPrepareError(mediaItem: MediaItem, error: IOException) {
            val targetPreloadStatus = getTargetPreloadStatusIfCurrentlyPreloading(mediaItem)
            if (targetPreloadStatus == null || !targetPreloadStatus.isPreCachingCategory) {
                // If the mediaItem is not the currently caching, skip silently as invalidate() must have
                // been called, and a new sequence of preloading must have started.
                return
            }
            val preloadException =
                PreloadException(mediaItem, null, error)
            this@DefaultPreloadManager.onError(
                preloadException,
                mediaItem
            ) { preloadStatus: PreloadStatus? -> preloadStatus == targetPreloadStatus }
        }

        override fun onDownloadError(mediaItem: MediaItem, error: IOException) {
            val targetPreloadStatus = getTargetPreloadStatusIfCurrentlyPreloading(mediaItem)
            if (targetPreloadStatus == null || !targetPreloadStatus.isPreCachingCategory) {
                // If the mediaItem is not the currently caching, skip silently as invalidate() must have
                // been called, and a new sequence of preloading must have started.
                return
            }
            val preloadException =
                PreloadException(mediaItem, null, error)
            this@DefaultPreloadManager.onError(
                preloadException,
                mediaItem
            ) { preloadStatus: PreloadStatus? -> preloadStatus == targetPreloadStatus }
        }
    }

    private inner class PreloadMediaSourceControl : PreloadControl {
        override fun onSourcePrepared(mediaSource: PreloadMediaSource): Boolean {
            return continueOrCompletePreloading(
                mediaSource
            ) { status: PreloadStatus? -> status!!.stage > PreloadStatus.STAGE_SOURCE_PREPARED }
        }

        override fun onTracksSelected(mediaSource: PreloadMediaSource): Boolean {
            return continueOrCompletePreloading(
                mediaSource
            ) { status: PreloadStatus? -> status!!.stage > PreloadStatus.STAGE_TRACKS_SELECTED }
        }

        override fun onContinueLoadingRequested(
            mediaSource: PreloadMediaSource, bufferedDurationUs: Long
        ): Boolean {
            return continueOrCompletePreloading(
                mediaSource
            ) { status: PreloadStatus? ->
                status!!.stage == PreloadStatus.STAGE_SPECIFIED_RANGE_LOADED && status.durationMs != C.TIME_UNSET && status.durationMs > usToMs(
                    bufferedDurationUs
                )
            }
        }

        override fun onUsedByPlayer(mediaSource: PreloadMediaSource) {
            val targetPreloadStatus = getTargetPreloadStatusIfCurrentlyPreloading(mediaSource)
            if (targetPreloadStatus == null || !targetPreloadStatus.isPreloadingCategory) {
                // If the mediaSource is not the currently loading, skip silently as invalidate() must have
                // been called, and a new sequence of preloading must have started.
                return
            }
            this@DefaultPreloadManager.onSkipped(
                mediaSource,
                Predicate { preloadStatus: PreloadStatus? -> preloadStatus == targetPreloadStatus })
        }

        override fun onLoadedToTheEndOfSource(mediaSource: PreloadMediaSource) {
            val targetPreloadStatus = getTargetPreloadStatusIfCurrentlyPreloading(mediaSource)
            if (targetPreloadStatus == null || !targetPreloadStatus.isPreloadingCategory) {
                // If the mediaSource is not the currently loading, skip silently as invalidate() must have
                // been called, and a new sequence of preloading must have started.
                return
            }
            this@DefaultPreloadManager.onCompleted(
                mediaSource
            ) { preloadStatus: PreloadStatus? -> preloadStatus == targetPreloadStatus }
        }

        override fun onPreloadError(error: PreloadException, mediaSource: PreloadMediaSource) {
            val targetPreloadStatus = getTargetPreloadStatusIfCurrentlyPreloading(mediaSource)
            if (targetPreloadStatus == null || !targetPreloadStatus.isPreloadingCategory) {
                // If the mediaSource is not the currently loading, skip silently as invalidate() must have
                // been called, and a new sequence of preloading must have started.
                return
            }
            this@DefaultPreloadManager.onError(
                error,
                mediaSource
            ) { preloadStatus: PreloadStatus? -> preloadStatus == targetPreloadStatus }
        }

        override fun onLoadingUnableToContinue(mediaSource: PreloadMediaSource): Boolean {
            val sourceHolder = this@DefaultPreloadManager.mediaSourceHolderToClear
            if (sourceHolder != null) {
                val lowestPriorityPreloadMediaSource =
                    sourceHolder.mediaSource as PreloadMediaSource
                lowestPriorityPreloadMediaSource.clear()
                this@DefaultPreloadManager.onSourceCleared()
                return true
            }
            return false
        }

        fun continueOrCompletePreloading(
            mediaSource: PreloadMediaSource?, continueLoadingPredicate: Predicate<PreloadStatus>
        ): Boolean {
            val targetPreloadStatus = getTargetPreloadStatusIfCurrentlyPreloading(mediaSource)
            if (targetPreloadStatus != null && targetPreloadStatus.isPreloadingCategory) {
                if (continueLoadingPredicate.apply(targetPreloadStatus)) {
                    return true
                }
                this@DefaultPreloadManager.onCompleted(
                    mediaSource,
                    Predicate { preloadStatus: PreloadStatus -> preloadStatus == targetPreloadStatus })
            }
            // If the mediaSource is not the currently loading, skip silently as invalidate() must have
            // been called, and a new sequence of preloading must have started.
            return false
        }
    }

    private class DefaultMediaSourceFactorySupplier(private val context: Context) :
        MediaSourceFactorySupplier {
        private val defaultMediaSourceFactorySupplier: Supplier<DefaultMediaSourceFactory> =
            Suppliers.memoize<DefaultMediaSourceFactory> {
                DefaultMediaSourceFactory(
                    context
                )
            }
        private var cache: Cache? = null
        private var dataSourceFactory: DataSource.Factory? = null

        override fun setCache(cache: Cache?): DefaultMediaSourceFactorySupplier {
            this.cache = cache
            return this
        }

        override fun setDataSourceFactory(
            dataSourceFactory: DataSource.Factory?
        ): DefaultMediaSourceFactorySupplier {
            this.dataSourceFactory = dataSourceFactory
            return this
        }

        override fun get(): MediaSource.Factory {
            val defaultMediaSourceFactory = defaultMediaSourceFactorySupplier.get()
            val dataSourceFactory: DataSource.Factory =
                (if (this.dataSourceFactory != null)
                    this.dataSourceFactory
                else
                    androidx.media3.datasource.DefaultDataSource.Factory(context))!!
            val cache = this.cache
            if (cache != null) {
                val cacheDataSourceFactory =
                    CacheDataSource.Factory()
                        .setUpstreamDataSourceFactory(dataSourceFactory)
                        .setCache(cache)
                        .setCacheWriteDataSinkFactory(null)
                defaultMediaSourceFactory.setDataSourceFactory(cacheDataSourceFactory)
            } else {
                defaultMediaSourceFactory.setDataSourceFactory(dataSourceFactory)
            }
            return defaultMediaSourceFactory
        }
    }

    private inner class PreloadMediaSourceHolder(
        mediaItem: MediaItem,
        mediaSource: PreloadMediaSource,
        rankingData: Int
    ) : MediaSourceHolder(mediaItem, rankingData, mediaSource) {
        var preCacheHelper: PreCacheHelper? = null

        @get:Synchronized
        @set:Synchronized
        override var mediaSource: MediaSource
            get() = super.mediaSource as PreloadMediaSource
            set(value) {
                (mediaSource as PreloadMediaSource).releasePreloadMediaSource()
                super.mediaSource = (value)
            }
    }
}
