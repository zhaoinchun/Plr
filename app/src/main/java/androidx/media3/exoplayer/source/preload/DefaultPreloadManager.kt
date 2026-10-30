package androidx.media3.exoplayer.source.preload

import android.content.Context
import android.os.HandlerThread
import android.os.Looper
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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
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

class DefaultPreloadManager private constructor(builder: Builder) :
    BasePreloadManager<Int, PreloadStatus>(
        builder.rankingDataComparator,
        builder.targetPreloadStatusControl,
        builder.mediaSourceFactorySupplier.get()
    ) {

    class Builder(
        val context: Context,
        rankingDataComparator: SimpleRankingDataComparator,
        targetPreloadStatusControl: TargetPreloadStatusControl<Int, PreloadStatus>
    ) : BuilderBase<Int, PreloadStatus>(
        rankingDataComparator,
        targetPreloadStatusControl,
        DefaultMediaSourceFactorySupplier(context)
    ) {
        var dataSourceFactory: DataSource.Factory? = null
        var preloadLooperProvider: PlaybackLooperProvider
        var trackSelectorFactory: TrackSelector.Factory
        var bandwidthMeterSupplier: Supplier<BandwidthMeter>
        var renderersFactorySupplier: Supplier<RenderersFactory>
        var loadControlSupplier: Supplier<LoadControl>
        var cache: Cache? = null
        var cachingExecutor: Executor
        var clock: Clock
        var buildCalled = false
        var buildExoPlayerCalled = false

        constructor(
            context: Context,
            targetPreloadStatusControl: TargetPreloadStatusControl<Int, PreloadStatus>
        ) : this(context, SimpleRankingDataComparator(), targetPreloadStatusControl)

        init {
            this.preloadLooperProvider = PlaybackLooperProvider()
            this.trackSelectorFactory =
                TrackSelector.Factory { context: Context? -> DefaultTrackSelector(context) }
            this.bandwidthMeterSupplier = Supplier {
                DefaultBandwidthMeter.getSingletonInstance(
                    context
                )
            }
            this.renderersFactorySupplier = Suppliers.memoize<RenderersFactory>(Supplier {
                DefaultRenderersFactory(
                    context
                )
            })
            this.loadControlSupplier =
                Suppliers.memoize<LoadControl>(Supplier { DefaultLoadControl() })
            this.cachingExecutor = Executor { obj: Runnable? -> obj!!.run() }
            this.clock = Clock.DEFAULT
        }

        @Deprecated(
            """Use {@link #setMediaSourceFactorySupplier(MediaSourceFactorySupplier)} instead.
          If an app still uses this method, it should be aware that the set {@link
     *     MediaSource.Factory} may not have the {@link #setCache(Cache) set Cache} and {@link
     *     #setDataSourceFactory(DataSource.Factory) set DataSource.Factory} injected."""
        )
        fun setMediaSourceFactory(mediaSourceFactory: MediaSource.Factory): Builder {
            Preconditions.checkState(!buildCalled && !buildExoPlayerCalled)
            this.mediaSourceFactorySupplier =
                object : MediaSourceFactorySupplier {

                    override fun setCache(cache: Cache?): MediaSourceFactorySupplier {
                        return this
                    }

                    override fun setDataSourceFactory(
                        dataSourceFactory: DataSource.Factory?
                    ): MediaSourceFactorySupplier {
                        return this
                    }

                    override fun get(): MediaSource.Factory {
                        return mediaSourceFactory
                    }
                }
            return this
        }

        fun setMediaSourceFactorySupplier(
            mediaSourceFactorySupplier: MediaSourceFactorySupplier
        ): Builder {
            Preconditions.checkState(!buildCalled && !buildExoPlayerCalled)
            this.mediaSourceFactorySupplier =
                mediaSourceFactorySupplier.setCache(cache).setDataSourceFactory(dataSourceFactory)
            return this
        }

        fun setDataSourceFactory(dataSourceFactory: DataSource.Factory?): Builder {
            Preconditions.checkState(!buildCalled && !buildExoPlayerCalled)
            this.dataSourceFactory = dataSourceFactory
            this.mediaSourceFactorySupplier.setDataSourceFactory(dataSourceFactory)
            return this
        }

        fun setRenderersFactory(renderersFactory: RenderersFactory): Builder {
            Preconditions.checkState(!buildCalled && !buildExoPlayerCalled)
            this.renderersFactorySupplier = Supplier { renderersFactory }
            return this
        }

        fun setTrackSelectorFactory(trackSelectorFactory: TrackSelector.Factory): Builder {
            Preconditions.checkState(!buildCalled && !buildExoPlayerCalled)
            this.trackSelectorFactory = trackSelectorFactory
            return this
        }

        fun setLoadControl(loadControl: LoadControl): Builder {
            Preconditions.checkState(!buildCalled && !buildExoPlayerCalled)
            this.loadControlSupplier = Supplier { loadControl }
            return this
        }

        fun setBandwidthMeter(bandwidthMeter: BandwidthMeter): Builder {
            Preconditions.checkState(!buildCalled && !buildExoPlayerCalled)
            Preconditions.checkArgument(bandwidthMeter !== BandwidthMeter.NO_OP)
            this.bandwidthMeterSupplier = Supplier { bandwidthMeter }
            return this
        }

        fun setPreloadLooper(preloadLooper: Looper): Builder {
            Preconditions.checkState(!buildCalled && !buildExoPlayerCalled && preloadLooper != Looper.getMainLooper())
            this.preloadLooperProvider = PlaybackLooperProvider(preloadLooper)
            return this
        }

        fun setCache(cache: Cache?): Builder {
            Preconditions.checkState(!buildCalled && !buildExoPlayerCalled)
            this.cache = cache
            this.mediaSourceFactorySupplier.setCache(cache)
            return this
        }

        fun setCachingExecutor(executor: Executor): Builder {
            Preconditions.checkState(!buildCalled && !buildExoPlayerCalled)
            this.cachingExecutor = executor
            return this
        }

        fun setClock(clock: Clock): Builder {
            this.clock = clock
            return this
        }

        fun buildExoPlayer(exoPlayerBuilder: ExoPlayer.Builder = ExoPlayer.Builder(context)): ExoPlayer {
            buildExoPlayerCalled = true
            return exoPlayerBuilder
                .setMediaSourceFactory(mediaSourceFactorySupplier.get())
                .setBandwidthMeter(bandwidthMeterSupplier.get())
                .setRenderersFactory(renderersFactorySupplier.get())
                .setLoadControl(loadControlSupplier.get())
                .setPlaybackLooperProvider(preloadLooperProvider)
                .setTrackSelector(trackSelectorFactory.createTrackSelector(context))
                .build()
        }

        override fun build(): DefaultPreloadManager {
            Preconditions.checkState(!buildCalled)
            buildCalled = true
            return DefaultPreloadManager(this)
        }
    }

    class PreloadStatus private constructor(
        stage: @Stage Int,
        startPositionMs: Long,
        durationMs: Long
    ) {

        @MustBeDocumented
        @Retention(AnnotationRetention.SOURCE)
        @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
        @IntDef(
            value = [STAGE_NOT_PRELOADED, STAGE_SPECIFIED_RANGE_CACHED, STAGE_SOURCE_PREPARED, STAGE_TRACKS_SELECTED, STAGE_SPECIFIED_RANGE_LOADED]
        )
        annotation class Stage

        val stage: @Stage Int

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
        DefaultRendererCapabilitiesList.Factory(builder.renderersFactorySupplier.get())
            .createRendererCapabilitiesList()
    private val trackSelector: TrackSelector = builder.trackSelectorFactory.createTrackSelector(builder.context)
    private val preloadLooperProvider: PlaybackLooperProvider
    private val preloadMediaSourceFactory: PreloadMediaSource.Factory
    private val preCacheThread: HandlerThread?
    private val preCacheHelperFactory: PreCacheHelper.Factory?
    private val preloadHandler: HandlerWrapper?
    private var releaseCalled = false

    init {
        preloadLooperProvider = builder.preloadLooperProvider
        val bandwidthMeter = builder.bandwidthMeterSupplier.get()
        trackSelector.init({ parameters: TrackSelectionParameters? ->
            trackSelector.onParametersActivated(
                parameters
            )
        }, bandwidthMeter)
        val preloadLooper = preloadLooperProvider.obtainLooper()
        preloadMediaSourceFactory =
            PreloadMediaSource.Factory(
                builder.mediaSourceFactorySupplier.get(),
                PreloadMediaSourceControl(),
                trackSelector,
                bandwidthMeter,
                rendererCapabilitiesList.rendererCapabilities,
                builder.loadControlSupplier.get(),
                preloadLooper
            )
                .setClock(builder.clock)
        val cache = builder.cache
        if (cache != null) {
            preCacheThread = HandlerThread("DefaultPreloadManager:PreCacheHelper")
            preCacheThread.start()
            val upstreamDataSourceFactory: DataSource.Factory =
                (if (builder.dataSourceFactory != null)
                    builder.dataSourceFactory
                else
                    androidx.media3.datasource.DefaultDataSource.Factory(builder.context))!!
            preCacheHelperFactory =
                PreCacheHelper.Factory(
                    builder.context, cache, upstreamDataSourceFactory, preCacheThread.getLooper()
                )
                    .setDownloadExecutor(builder.cachingExecutor)
                    .setListener(PreCacheHelperListener())
        } else {
            preCacheThread = null
            preCacheHelperFactory = null
        }
        preloadHandler = builder.clock.createHandler(preloadLooper, null)
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
