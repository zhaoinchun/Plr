package androidx.media3.exoplayer.source.preload

import android.os.Looper
import android.util.Pair
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.Clock
import androidx.media3.common.util.HandlerWrapper
import androidx.media3.common.util.Log.e
import androidx.media3.common.util.Util.postOrRun
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelectorResult
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.PlayerIdAwareAllocator
import com.google.common.base.Preconditions
import java.io.IOException

class PreloadMediaSource private constructor(
    mediaSource: MediaSource,
    private val preloadControl: PreloadControl,
    private val trackSelector: TrackSelector,
    private val bandwidthMeter: BandwidthMeter,
    private val rendererCapabilities: Array<RendererCapabilities>,
    private val loadControl: LoadControl,
    preloadLooper: Looper,
    clock: Clock
) : WrappingMediaSource(mediaSource) {
    interface PreloadControl {
        fun onSourcePrepared(mediaSource: PreloadMediaSource): Boolean
        fun onTracksSelected(mediaSource: PreloadMediaSource): Boolean
        fun onContinueLoadingRequested(
            mediaSource: PreloadMediaSource,
            bufferedDurationUs: Long
        ): Boolean
        fun onUsedByPlayer(mediaSource: PreloadMediaSource)
        fun onLoadedToTheEndOfSource(mediaSource: PreloadMediaSource) {}
        fun onLoadingUnableToContinue(mediaSource: PreloadMediaSource): Boolean {
            return false
        }

        fun onPreloadError(error: PreloadException, mediaSource: PreloadMediaSource)
    }

    class Factory(
        private val mediaSourceFactory: MediaSource.Factory,
        private val preloadControl: PreloadControl,
        private val trackSelector: TrackSelector,
        bandwidthMeter: BandwidthMeter,
        rendererCapabilities: Array<RendererCapabilities>,
        loadControl: LoadControl,
        preloadLooper: Looper
    ) : MediaSource.Factory {
        private val preloadLooper: Looper
        private val loadControl: LoadControl
        private val bandwidthMeter: BandwidthMeter
        private val rendererCapabilities: Array<RendererCapabilities>
        private var clock: Clock

        init {
            Preconditions.checkArgument(bandwidthMeter !== BandwidthMeter.NO_OP)
            this.bandwidthMeter = bandwidthMeter
            this.rendererCapabilities =rendererCapabilities.copyOf()
            this.loadControl = loadControl
            this.preloadLooper = preloadLooper
            this.clock = Clock.DEFAULT
        }

        override fun setCmcdConfigurationFactory(cmcdConfigurationFactory: CmcdConfiguration.Factory): Factory {
            this.mediaSourceFactory.setCmcdConfigurationFactory(cmcdConfigurationFactory)
            return this
        }

        override fun setDrmSessionManagerProvider(
            drmSessionManagerProvider: DrmSessionManagerProvider
        ): Factory {
            this.mediaSourceFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
            return this
        }

        override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): Factory {
            this.mediaSourceFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            return this
        }

        fun setClock(clock: Clock): Factory {
            this.clock = clock
            return this
        }

        override val supportedTypes: IntArray
            get() = this.mediaSourceFactory.supportedTypes

        override fun createMediaSource(mediaItem: MediaItem): PreloadMediaSource {
            return PreloadMediaSource(
                mediaSourceFactory.createMediaSource(mediaItem),
                preloadControl,
                trackSelector,
                bandwidthMeter,
                rendererCapabilities,
                loadControl,
                preloadLooper,
                clock
            )
        }

        fun createMediaSource(mediaSource: MediaSource): PreloadMediaSource {
            return PreloadMediaSource(
                mediaSource,
                preloadControl,
                trackSelector,
                bandwidthMeter,
                rendererCapabilities,
                loadControl,
                preloadLooper,
                clock
            )
        }
    }

    private val allocator: Allocator = loadControl.getAllocator(PlayerId.PRELOAD)
    private val preloadHandler: HandlerWrapper = clock.createHandler(preloadLooper,  null)
    private val releaseHandler: HandlerWrapper = clock.createHandler(preloadLooper,  null)
    private var preloadCalled = false
    private var prepareChildSourceCalled = false
    private var releasePreloadMediaSourceCalled = false
    private var startPositionUs= C.TIME_UNSET
    private var timeline: Timeline? = null
    private var preloadingMediaPeriodAndKey: Pair<PreloadMediaPeriod, MediaPeriodKey>? = null
    private var playingPreloadedMediaPeriodAndId: Pair<PreloadMediaPeriod, MediaPeriodId>? = null
    private var onSourcePreparedNotified = false
    private var onUsedByPlayerNotified = false

    fun preload(startPositionUs: Long) {
        preloadHandler.post {
            onSourcePreparedNotified = false
            if (this.isUsedByPlayer) {
                onUsedByPlayer()
            } else {
                this.preloadCalled = true
                this.startPositionUs = startPositionUs
                setPlayerId(PlayerId.PRELOAD)
                setBandwidthMeter(bandwidthMeter)
                loadControl.onPrepared(PlayerId.PRELOAD)
                prepareSourceInternal(bandwidthMeter.transferListener)
                checkForPreloadError()
            }
        }
    }

    fun clear() {
        postOrRun(preloadHandler) {
            if (preloadingMediaPeriodAndKey != null) {
                mediaSource.releasePeriod(preloadingMediaPeriodAndKey!!.first.mediaPeriod)
                preloadingMediaPeriodAndKey = null
            }
        }
    }

    override fun prepareSourceInternal() {
        Preconditions.checkState(Looper.myLooper() == preloadHandler.looper)
        if (this.isUsedByPlayer && !onUsedByPlayerNotified) {
            onUsedByPlayer()
        }
        maybeSetPlayerIdForAllocator()
        if (timeline != null) {
            onChildSourceInfoRefreshed(timeline!!)
        } else if (!prepareChildSourceCalled) {
            prepareChildSourceCalled = true
            prepareChildSource()
        }
    }

    override fun onChildSourceInfoRefreshed(newTimeline: Timeline) {
        this.timeline = newTimeline
        refreshSourceInfo(newTimeline)
        preloadHandler.post(
            Runnable {
                if (this.isUsedByPlayer || onSourcePreparedNotified) {
                    return@Runnable
                }
                onSourcePreparedNotified = true
                if (!preloadControl.onSourcePrepared(this)) {
                    stopPreloading()
                    return@Runnable
                }
                val periodPosition =
                    newTimeline.getPeriodPositionUs(
                        Timeline.Window(),
                        Timeline.Period(),  
                        0,  
                        startPositionUs
                    )
                val mediaPeriodId = MediaPeriodId(periodPosition.first!!)
                val mediaPeriod =
                    this@PreloadMediaSource.createPeriod(
                        mediaPeriodId, allocator, periodPosition.second!!
                    ) as PreloadMediaPeriod?
                mediaPeriod!!.preload(
                    PreloadMediaPeriodCallback(periodPosition.second!!),  
                    periodPosition.second!!
                )
            })
    }

    override fun createPeriod(
        id: MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod {
        val key = MediaPeriodKey(id, startPositionUs)
        if (preloadingMediaPeriodAndKey != null && key == preloadingMediaPeriodAndKey!!.second) {
            val mediaPeriod =preloadingMediaPeriodAndKey!!.first
            if (this.isUsedByPlayer) {
                preloadingMediaPeriodAndKey = null
                playingPreloadedMediaPeriodAndId =
                    Pair<PreloadMediaPeriod, MediaPeriodId>(mediaPeriod, id)
            }
            return mediaPeriod
        } else if (preloadingMediaPeriodAndKey != null) {
            mediaSource.releasePeriod(preloadingMediaPeriodAndKey!!.first!!.mediaPeriod
            )
            preloadingMediaPeriodAndKey = null
        }

        val mediaPeriod =
            PreloadMediaPeriod(mediaSource.createPeriod(id, allocator, startPositionUs))
        if (!this.isUsedByPlayer) {
            preloadingMediaPeriodAndKey =
                Pair<PreloadMediaPeriod, MediaPeriodKey>(mediaPeriod, key)
        }
        return mediaPeriod
    }

    override fun getMediaPeriodIdForChildMediaPeriodId(mediaPeriodId: MediaPeriodId): MediaPeriodId? {
        if (playingPreloadedMediaPeriodAndId != null
            && mediaPeriodIdEqualsWithoutWindowSequenceNumber(
                mediaPeriodId, playingPreloadedMediaPeriodAndId!!.second!!
            )
        ) {
            return playingPreloadedMediaPeriodAndId!!.second
        }
        return mediaPeriodId
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        val preloadMediaPeriod = (mediaPeriod as PreloadMediaPeriod)
        if (preloadingMediaPeriodAndKey != null
            && preloadMediaPeriod == preloadingMediaPeriodAndKey!!.first
        ) {
            preloadingMediaPeriodAndKey = null
        } else if (playingPreloadedMediaPeriodAndId != null
            && preloadMediaPeriod == playingPreloadedMediaPeriodAndId!!.first
        ) {
            playingPreloadedMediaPeriodAndId = null
        }
        mediaSource.releasePeriod(preloadMediaPeriod.mediaPeriod)
    }

    protected override fun releaseSourceInternal() {
        if (!this.isUsedByPlayer) {
            onUsedByPlayerNotified = false
            if (preloadCalled && !releasePreloadMediaSourceCalled) {
                // PlayerId.PRELOAD takes over this source again.
                setPlayerId(PlayerId.PRELOAD)
                maybeSetPlayerIdForAllocator()
            } else {
                timeline = null
                prepareChildSourceCalled = false
                super.releaseSourceInternal()
            }
        }
    }

    fun releasePreloadMediaSource() {
        releaseHandler.post {
            if (preloadCalled) {
                loadControl.onReleased(PlayerId.PRELOAD)
            }
            releasePreloadMediaSourceCalled = true
            preloadCalled = false
            startPositionUs = C.TIME_UNSET
            onSourcePreparedNotified = false
            if (preloadingMediaPeriodAndKey != null) {
                mediaSource.releasePeriod(preloadingMediaPeriodAndKey!!.first.mediaPeriod)
                preloadingMediaPeriodAndKey = null
            }
            releaseSourceInternal()
            preloadHandler.removeCallbacksAndMessages(null)
            releaseHandler.removeCallbacksAndMessages(null)
        }
    }

    private val isUsedByPlayer: Boolean
        get() = prepareSourceCalled()

    private fun onUsedByPlayer() {
        preloadControl.onUsedByPlayer(this)
        stopPreloading()
        onUsedByPlayerNotified = true
    }

    private fun checkForPreloadError() {
        try {
            maybeThrowSourceInfoRefreshError()
            if (preloadingMediaPeriodAndKey != null) {
                val preloadingMediaPeriod = preloadingMediaPeriodAndKey!!.first
                if (!preloadingMediaPeriod.prepared) {
                    preloadingMediaPeriod.maybeThrowPrepareError()
                } else {
                    preloadingMediaPeriod.maybeThrowStreamError()
                }
            }
            preloadHandler.postDelayed(
                { this.checkForPreloadError() },
                CHECK_FOR_PRELOAD_ERROR_INTERVAL_MS
            )
        } catch (e: IOException) {
            preloadControl.onPreloadError(
                PreloadException(this.mediaItem,  null, e), this
            )
            stopPreloading()
        }
    }

    private fun stopPreloading() {
        preloadHandler.removeCallbacksAndMessages(null)
    }

    private fun maybeSetPlayerIdForAllocator() {
        if (allocator is PlayerIdAwareAllocator) {
            allocator.setPlayerId(getPlayerId())
        }
    }

    private inner class PreloadMediaPeriodCallback(private val periodStartPositionUs: Long) :
        MediaPeriod.Callback {
        private var prepared = false
        private var continueLoadingRetryCountBeforeCallingPreloadControlAgain= C.LENGTH_UNSET
        private var maybeContinueLoadingRunnable: Runnable? = null
        private var onLoadToTheEndOfSourceNotified = false

        override fun onPrepared(mediaPeriod: MediaPeriod) {
            prepared = true
            preloadHandler.post(
                Runnable {
                    if (preloadingMediaPeriodAndKey == null || this@PreloadMediaSource.isUsedByPlayer) {
                        return@Runnable
                    }
                    val preloadMediaPeriod = mediaPeriod as PreloadMediaPeriod
                    val trackGroups = preloadMediaPeriod.trackGroups
                    var trackSelectorResult: TrackSelectorResult? = null
                    val key: MediaPeriodKey =
                        preloadingMediaPeriodAndKey!!.second
                    val mediaPeriodId = key.mediaPeriodId
                    try {
                        trackSelectorResult =
                            trackSelector.selectTracks(
                                rendererCapabilities,
                                trackGroups,
                                mediaPeriodId,
                                timeline!!
                            )
                    } catch (e: ExoPlaybackException) {
                        e(TAG, "Failed to select tracks", e)
                    }
                    if (trackSelectorResult == null) {
                        stopPreloading()
                        return@Runnable
                    }
                    preloadMediaPeriod.selectTracksForPreloading(
                        trackSelectorResult.selections, periodStartPositionUs
                    )
                    if (!preloadControl.onTracksSelected(this@PreloadMediaSource)) {
                        stopPreloading()
                        return@Runnable
                    }
                    loadControl.onTracksSelected(
                        createLoadControlParameters(
                            getPlayerId(),
                            timeline!!,
                            mediaPeriodId,
                            mediaPeriod.bufferedPositionUs
                        ),
                        trackGroups,
                        trackSelectorResult.selections
                    )
                    continueLoadingRetryCountBeforeCallingPreloadControlAgain = C.LENGTH_UNSET
                    maybeContinueLoading(preloadMediaPeriod, mediaPeriodId)
                })
        }

        override fun onContinueLoadingRequested(mediaPeriod: MediaPeriod) {
            preloadHandler.post(
                Runnable {
                    if (preloadingMediaPeriodAndKey == null || this@PreloadMediaSource.isUsedByPlayer) {
                        return@Runnable
                    }
                    val preloadMediaPeriod = mediaPeriod as PreloadMediaPeriod
                    if (prepared) {
                        val bufferedPositionUs = mediaPeriod.bufferedPositionUs
                        if (bufferedPositionUs == C.TIME_END_OF_SOURCE) {
                            onLoadToTheEndOfSource()
                            return@Runnable
                        }
                        if (!preloadControl.onContinueLoadingRequested(
                                this@PreloadMediaSource, bufferedPositionUs - periodStartPositionUs
                            )
                        ) {
                            stopPreloading()
                            return@Runnable
                        }
                    }
                    val key: MediaPeriodKey =preloadingMediaPeriodAndKey!!.second
                    continueLoadingRetryCountBeforeCallingPreloadControlAgain = C.LENGTH_UNSET
                    maybeContinueLoading(preloadMediaPeriod, key.mediaPeriodId)
                })
        }

        fun maybeContinueLoading(mediaPeriod: MediaPeriod, mediaPeriodId: MediaPeriodId?) {
            if (preloadingMediaPeriodAndKey == null || preloadingMediaPeriodAndKey!!.first != mediaPeriod) {
                return
            }
            if (maybeContinueLoadingRunnable != null) {
                // Remove other pending maybeContinueLoadingRunnable that are handled by this invocation.
                preloadHandler.removeCallbacks(maybeContinueLoadingRunnable!!)
                maybeContinueLoadingRunnable = null
            }

            val parameters =
                createLoadControlParameters(
                    getPlayerId(),
                    timeline!!,
                    mediaPeriodId,
                    if (prepared) mediaPeriod.bufferedPositionUs else 0L
                )
            if (loadControl.shouldContinueLoading(parameters)) {
                continueLoadingRetryCountBeforeCallingPreloadControlAgain = C.LENGTH_UNSET
                mediaPeriod.continueLoading(
                    LoadingInfo.Builder().setPlaybackPositionUs(periodStartPositionUs).build()
                )
                if (prepared && mediaPeriod.bufferedPositionUs == C.TIME_END_OF_SOURCE) {
                    onLoadToTheEndOfSource()
                }
                return
            }

            // LoadControl refuses loading.
            if (continueLoadingRetryCountBeforeCallingPreloadControlAgain != C.LENGTH_UNSET
                && (continueLoadingRetryCountBeforeCallingPreloadControlAgain
                        < CONTINUE_LOADING_RETRY_COUNT)
            ) {
                // The continueLoadingRetryCountBeforeCallingPreloadControlAgain still hasn't exhausted the
                // allowed times, we will keep retrying maybeContinueLoading before letting PreloadControl
                // know to take more actions.
                continueLoadingRetryCountBeforeCallingPreloadControlAgain++
            } else if (preloadControl.onLoadingUnableToContinue(this@PreloadMediaSource)) {
                // The continueLoadingRetryCountBeforeCallingPreloadControlAgain has exhausted the allowed
                // times before continue loading, and we have let the PreloadControl know to make more
                // allocations available for this PreloadMediaSource, then we reset the retry count and
                // start to retry again.
                continueLoadingRetryCountBeforeCallingPreloadControlAgain = 0
            } else {
                // The PreloadControl cannot do more to make allocations available for this
                // PreloadMediaSource. Then we will still keep retrying maybeContinueLoading just in case
                // some allocations become available by any other means.
                continueLoadingRetryCountBeforeCallingPreloadControlAgain = C.LENGTH_UNSET
            }
            maybeContinueLoadingRunnable = Runnable {
                maybeContinueLoading(
                    mediaPeriod,
                    mediaPeriodId
                )
            }
            preloadHandler.postDelayed(
                maybeContinueLoadingRunnable!!,
                MAYBE_CONTINUE_LOADING_INTERVAL_MS
            )
        }

        fun createLoadControlParameters(
            playerId: PlayerId?,
            timeline: Timeline?,
            mediaPeriodId: MediaPeriodId?,
            bufferedDurationUs: Long
        ): LoadControl.Parameters {
            return LoadControl.Parameters(
                playerId,
                timeline,
                mediaPeriodId,  
                0L,
                bufferedDurationUs,  
                1f,  
                false,  
                false,  
                C.TIME_UNSET,  
                C.TIME_UNSET
            )
        }

        fun onLoadToTheEndOfSource() {
            if (!onLoadToTheEndOfSourceNotified) {
                preloadControl.onLoadedToTheEndOfSource(this@PreloadMediaSource)
                onLoadToTheEndOfSourceNotified = true
            }
            stopPreloading()
        }
    }

    private class MediaPeriodKey(
        val mediaPeriodId: MediaPeriodId,
        private val startPositionUs: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other !is MediaPeriodKey) {
                return false
            }
            val mediaPeriodKey: MediaPeriodKey = other
            // The MediaPeriodId.windowSequenceNumber is intentionally left out of equals to ensure we
            // detect the "same" media even if it's used with a different sequence number.
            return mediaPeriodIdEqualsWithoutWindowSequenceNumber(
                this.mediaPeriodId, mediaPeriodKey.mediaPeriodId
            )
                    && startPositionUs == mediaPeriodKey.startPositionUs
        }

        override fun hashCode(): Int {
            // The MediaPeriodId.windowSequenceNumber is intentionally left out of hashCode to ensure we
            // detect the "same" media even if it's used with a different sequence number.
            var result = 17
            result = 31 * result + mediaPeriodId.periodUid.hashCode()
            result = 31 * result + mediaPeriodId.adGroupIndex
            result = 31 * result + mediaPeriodId.adIndexInAdGroup
            result = 31 * result + mediaPeriodId.nextAdGroupIndex
            result = 31 * result + startPositionUs.toInt()
            return result
        }
    }

    companion object {
        private const val TAG = "PreloadMediaSource"
        private const val CHECK_FOR_PRELOAD_ERROR_INTERVAL_MS: Long = 100
        private const val CONTINUE_LOADING_RETRY_COUNT = 10
        private const val MAYBE_CONTINUE_LOADING_INTERVAL_MS: Long = 100

        private fun mediaPeriodIdEqualsWithoutWindowSequenceNumber(
            firstPeriodId: MediaPeriodId, secondPeriodId: MediaPeriodId
        ): Boolean {
            return firstPeriodId.periodUid == secondPeriodId.periodUid
                    && firstPeriodId.adGroupIndex == secondPeriodId.adGroupIndex && firstPeriodId.adIndexInAdGroup == secondPeriodId.adIndexInAdGroup && firstPeriodId.nextAdGroupIndex == secondPeriodId.nextAdGroupIndex
        }
    }
}
