package androidx.media3.exoplayer.source

import android.os.Looper
import androidx.annotation.GuardedBy
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.Util.msToUs
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.util.ReleasableExecutor
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.SeekMap
import com.google.common.base.Preconditions
import com.google.common.base.Supplier
import java.util.concurrent.Executor

class ProgressiveMediaSource private constructor(
    @field:GuardedBy("this") override var mediaItem: MediaItem,
    private val dataSourceFactory: DataSource.Factory,
    private val progressiveMediaExtractorFactory: ProgressiveMediaExtractor.Factory,
    private val drmSessionManager: DrmSessionManager,
    private val loadableLoadErrorHandlingPolicy: LoadErrorHandlingPolicy,
    private val continueLoadingCheckIntervalBytes: Int,
    private val loadOnlySelectedTracks: Boolean,
    private val experimentalEnableHagcPlayback: Boolean,
    private val singleTrackId: Int,
    private val singleTrackFormat: Format?,
    private val downloadExecutorSupplier: Supplier<ReleasableExecutor>?
) : BaseMediaSource(), ProgressiveMediaPeriod.Listener {
    interface Listener {
        fun onSeekMap(source: MediaSource, seekMap: SeekMap)
    }

    @Suppress("deprecation") // Implement deprecated type for backwards compatibility.
    class Factory(
        private val dataSourceFactory: DataSource.Factory,
        private var progressiveMediaExtractorFactory: ProgressiveMediaExtractor.Factory,
        private var drmSessionManagerProvider: DrmSessionManagerProvider = DefaultDrmSessionManagerProvider(),
        private var loadErrorHandlingPolicy: LoadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(),
        private var continueLoadingCheckIntervalBytes: Int = DEFAULT_LOADING_CHECK_INTERVAL_BYTES
    ) : MediaSourceFactory {
        private var downloadExecutorSupplier: Supplier<ReleasableExecutor>? = null
        private var singleTrackId = 0
        private var singleTrackFormat: Format? = null
        private var loadOnlySelectedTracks = false
        private var experimentalEnableHagcPlayback = true

        constructor(
            dataSourceFactory: DataSource.Factory,
            extractorsFactory: ExtractorsFactory = DefaultExtractorsFactory()
        ) : this(
            dataSourceFactory,
            ProgressiveMediaExtractor.Factory { playerId: PlayerId ->
                BundledExtractorsAdapter(extractorsFactory)
            })

        fun setExperimentalEnableHagcPlayback(enableHagcPlayback: Boolean): Factory {
            this.experimentalEnableHagcPlayback = enableHagcPlayback
            return this
        }

        override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): Factory {
            this.loadErrorHandlingPolicy = loadErrorHandlingPolicy
            return this
        }

        fun setContinueLoadingCheckIntervalBytes(continueLoadingCheckIntervalBytes: Int): Factory {
            this.continueLoadingCheckIntervalBytes = continueLoadingCheckIntervalBytes
            return this
        }

        fun enableLazyLoadingWithSingleTrack(trackId: Int, format: Format): Factory {
            this.singleTrackId = trackId
            this.singleTrackFormat = format
            return this
        }

        override fun setDrmSessionManagerProvider(
            drmSessionManagerProvider: DrmSessionManagerProvider
        ): Factory {
            this.drmSessionManagerProvider = drmSessionManagerProvider
            return this
        }

        fun <T : Executor?> setDownloadExecutor(
            downloadExecutor: Supplier<T>, downloadExecutorReleaser: Consumer<T>
        ): Factory {
            setDownloadExecutor {
                ReleasableExecutor.from<T>(
                    downloadExecutor.get(),
                    downloadExecutorReleaser
                )
            }
            return this
        }

        override fun setDownloadExecutor(downloadExecutor: Supplier<ReleasableExecutor>): MediaSource.Factory {
            this.downloadExecutorSupplier = downloadExecutor
            return this
        }

        fun setLoadOnlySelectedTracks(loadOnlySelectedTracks: Boolean): Factory {
            this.loadOnlySelectedTracks = loadOnlySelectedTracks
            return this
        }

        override fun createMediaSource(mediaItem: MediaItem): ProgressiveMediaSource {
            mediaItem.localConfiguration!!
            return ProgressiveMediaSource(
                mediaItem,
                dataSourceFactory,
                progressiveMediaExtractorFactory,
                drmSessionManagerProvider.get(mediaItem)!!,
                loadErrorHandlingPolicy,
                continueLoadingCheckIntervalBytes,
                loadOnlySelectedTracks,
                experimentalEnableHagcPlayback,
                singleTrackId,
                singleTrackFormat,
                downloadExecutorSupplier
            )
        }

        override val supportedTypes: IntArray
            get() = intArrayOf(C.CONTENT_TYPE_OTHER)

    }

    private var timelineIsPlaceholder = true
    private var timelineDurationUs = C.TIME_UNSET
    private var timelineIsSeekable = false
    private var timelineIsLive = false
    private var hasSeenNonEstimatedSeekMap = false
    private var transferListener: TransferListener? = null
    private var listener: Listener? = null

    override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean {
        val existingConfiguration =
            this.localConfiguration
        val newConfiguration = mediaItem.localConfiguration
        return newConfiguration != null && newConfiguration.uri == existingConfiguration.uri
                && newConfiguration.imageDurationMs == existingConfiguration.imageDurationMs && newConfiguration.customCacheKey == existingConfiguration.customCacheKey
    }

    @Synchronized
    override fun updateMediaItem(mediaItem: MediaItem) {
        this.mediaItem = mediaItem
    }

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        transferListener = mediaTransferListener
        drmSessionManager.setPlayer(
            Looper.myLooper()!!, getPlayerId()
        )
        drmSessionManager.prepare()
        notifySourceInfoRefreshed()
    }

    override fun maybeThrowSourceInfoRefreshError() {
        // Do nothing.
    }

    override fun createPeriod(
        id: MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod {
        val dataSource = dataSourceFactory.createDataSource()
        transferListener?.also { dataSource.addTransferListener(it) }
        val localConfiguration = localConfiguration
        return ProgressiveMediaPeriod(
            localConfiguration.uri,
            dataSource,
            progressiveMediaExtractorFactory.createProgressiveMediaExtractor(getPlayerId()),
            drmSessionManager,
            createDrmEventDispatcher(id),
            loadableLoadErrorHandlingPolicy,
            createEventDispatcher(id),
            this,
            allocator,
            localConfiguration.customCacheKey,
            continueLoadingCheckIntervalBytes,
            loadOnlySelectedTracks,
            experimentalEnableHagcPlayback,
            singleTrackId,
            singleTrackFormat,
            msToUs(localConfiguration.imageDurationMs),
            downloadExecutorSupplier?.get()
        )
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        (mediaPeriod as ProgressiveMediaPeriod).release()
    }

    override fun releaseSourceInternal() {
        drmSessionManager.release()
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun clearListener() {
        this.listener = null
    }

    // ProgressiveMediaPeriod.Listener implementation.
    override fun onSourceInfoRefreshed(durationUs: Long, seekMap: SeekMap, isLive: Boolean) {
        var durationUs = durationUs
        if (hasSeenNonEstimatedSeekMap && seekMap.isEstimated) {
            // If we've seen a non-estimated seekMap and the new seekMap is estimated, then we are
            // receiving the out-of-date source info from the period, and we should suppress it.
            return
        }
        hasSeenNonEstimatedSeekMap = !seekMap.isEstimated
        // If we already have the duration from a previous source info refresh, use it.
        durationUs = if (durationUs == C.TIME_UNSET) timelineDurationUs else durationUs
        val isSeekable = seekMap.isSeekable
        if (!timelineIsPlaceholder && timelineDurationUs == durationUs && timelineIsSeekable == isSeekable && timelineIsLive == isLive) {
            // Suppress no-op source info changes.
            return
        }
        timelineDurationUs = durationUs
        timelineIsSeekable = isSeekable
        timelineIsLive = isLive
        timelineIsPlaceholder = false
        notifySourceInfoRefreshed()
        listener?.onSeekMap(this, seekMap)
    }

    private val localConfiguration: MediaItem.LocalConfiguration
        get() = mediaItem.localConfiguration!!

    private fun notifySourceInfoRefreshed() {
        var timeline: Timeline =
            SinglePeriodTimeline(
                timelineDurationUs,
                timelineIsSeekable,
                false,
                timelineIsLive,
                null,
                mediaItem
            )
        if (timelineIsPlaceholder) {
            timeline =
                object : ForwardingTimeline(timeline) {
                    override fun getWindow(
                        windowIndex: Int, window: Window, defaultPositionProjectionUs: Long
                    ): Window {
                        super.getWindow(windowIndex, window, defaultPositionProjectionUs)
                        window.isPlaceholder = true
                        return window
                    }

                    override fun getPeriod(
                        periodIndex: Int,
                        period: Period,
                        setIds: Boolean
                    ): Period {
                        super.getPeriod(periodIndex, period, setIds)
                        period.isPlaceholder = true
                        return period
                    }
                }
        }
        refreshSourceInfo(timeline)
    }

    companion object {
        const val DEFAULT_LOADING_CHECK_INTERVAL_BYTES: Int = 1024 * 1024
    }
}
