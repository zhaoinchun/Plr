package androidx.media3.exoplayer.source.ads

import android.os.Handler
import android.util.Pair
import androidx.annotation.GuardedBy
import androidx.annotation.VisibleForTesting
import androidx.media3.common.AdPlaybackState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.StreamKey
import androidx.media3.common.Timeline

import androidx.media3.common.util.Util.createHandlerForCurrentLooper
import androidx.media3.common.util.Util.msToUs
import androidx.media3.common.util.Util.postOrRun
import androidx.media3.common.util.Util.usToMs
import androidx.media3.datasource.TransferListener
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.KeyRequestInfo
import androidx.media3.exoplayer.source.BaseMediaSource
import androidx.media3.exoplayer.source.EmptySampleStream
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.MediaSource.MediaSourceCaller
import androidx.media3.exoplayer.source.MediaSourceEventListener
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.SampleStream.ReadDataResult
import androidx.media3.exoplayer.source.SampleStream.ReadFlags
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import com.google.common.base.Preconditions
import com.google.common.collect.ArrayListMultimap
import com.google.common.collect.Iterables
import com.google.common.collect.ListMultimap

import java.io.IOException

class ServerSideAdInsertionMediaSource(
    private val mediaSource: MediaSource,
    private val adPlaybackStateUpdater: AdPlaybackStateUpdater?
) : BaseMediaSource(), MediaSourceCaller, MediaSourceEventListener, DrmSessionEventListener {
    interface AdPlaybackStateUpdater {
        fun onAdPlaybackStateUpdateRequested(contentTimeline: Timeline?): Boolean
    }

    private val mediaPeriods: ListMultimap<Pair<Long, Any>, SharedMediaPeriod> =
        ArrayListMultimap.create<Pair<Long, Any>, SharedMediaPeriod>()
    private val mediaSourceEventDispatcherWithoutId: MediaSourceEventListener.EventDispatcher? =
        createEventDispatcher(null)
    private val drmEventDispatcherWithoutId: DrmSessionEventListener.EventDispatcher =
        createDrmEventDispatcher(null)
    private val period = Timeline.Period()

    @GuardedBy("this")
    private var playbackHandler: Handler? = null
    private var lastUsedMediaPeriod: SharedMediaPeriod? = null
    private var adPlaybackStates = mapOf<Any?, AdPlaybackState?>()
    private var sourceTimeline: Timeline? = Timeline.EMPTY
    private var splitTimeline: Timeline = Timeline.EMPTY

    fun setAdPlaybackStates(
        adPlaybackStates: Map<Any?, AdPlaybackState?>, contentTimeline: Timeline
    ) {
        Preconditions.checkArgument(!adPlaybackStates.isEmpty())
        val adsId = adPlaybackStates.values.toList()[0]!!.adsId!!
        for (entry in adPlaybackStates.entries) {
            val periodUid = entry.key
            val adPlaybackState: AdPlaybackState = entry.value!!
            Preconditions.checkArgument(adsId == adPlaybackState.adsId)
            val oldAdPlaybackState = this.adPlaybackStates[periodUid]
            if (oldAdPlaybackState != null) {
                for (adGroupIndex in adPlaybackState.removedAdGroupCount..<adPlaybackState.adGroupCount) {
                    val adGroup = adPlaybackState.getAdGroup(adGroupIndex)
                    Preconditions.checkArgument(adGroup.isServerSideInserted)
                    if (adGroupIndex < oldAdPlaybackState.adGroupCount
                        && (ServerSideAdInsertionUtil.getAdCountInGroup(
                            adPlaybackState,
                            adGroupIndex
                        )
                                < ServerSideAdInsertionUtil.getAdCountInGroup(
                            oldAdPlaybackState,
                            adGroupIndex
                        ))
                    ) {
                        // Removing ads from an ad group is only allowed when the group has been split.
                        val nextAdGroup = adPlaybackState.getAdGroup(adGroupIndex + 1)
                        val sumOfSplitContentResumeOffsetUs =
                            adGroup.contentResumeOffsetUs + nextAdGroup.contentResumeOffsetUs
                        val oldAdGroup = oldAdPlaybackState.getAdGroup(adGroupIndex)
                        Preconditions.checkArgument(sumOfSplitContentResumeOffsetUs == oldAdGroup.contentResumeOffsetUs)
                        Preconditions.checkArgument(adGroup.timeUs + adGroup.contentResumeOffsetUs == nextAdGroup.timeUs)
                    }
                    if (adGroup.timeUs == C.TIME_END_OF_SOURCE) {
                        Preconditions.checkArgument(
                            ServerSideAdInsertionUtil.getAdCountInGroup(
                                adPlaybackState,
                                adGroupIndex
                            ) == 0
                        )
                    }
                }
            }
        }
        val playbackHandler: Handler?
        synchronized(this) {
            playbackHandler = this.playbackHandler
        }
        if (playbackHandler == null) {
            this.adPlaybackStates = adPlaybackStates
            return
        }
        postOrRun(
            playbackHandler,
            Runnable {
                if (contentTimeline != sourceTimeline) {
                    // Stale update, ignore.
                    return@Runnable
                }
                this.adPlaybackStates = adPlaybackStates
                splitTimeline = ServerSideAdInsertionTimeline(sourceTimeline!!, adPlaybackStates)
                for (mediaPeriod in mediaPeriods.values()) {
                    val adPlaybackState = adPlaybackStates[mediaPeriod.periodUid]
                    if (adPlaybackState != null) {
                        mediaPeriod.updateAdPlaybackState(splitTimeline, adPlaybackState, period)
                    }
                }
                if (lastUsedMediaPeriod != null) {
                    val adPlaybackState = adPlaybackStates[lastUsedMediaPeriod!!.periodUid]
                    if (adPlaybackState != null) {
                        lastUsedMediaPeriod!!.updateAdPlaybackState(
                            splitTimeline,
                            adPlaybackState,
                            period
                        )
                    }
                }
                refreshSourceInfo(splitTimeline)
            })
    }

    override val mediaItem: MediaItem
        get() = mediaSource.mediaItem

    override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean {
        return mediaSource.canUpdateMediaItem(mediaItem)
    }

    override fun updateMediaItem(mediaItem: MediaItem) {
        mediaSource.updateMediaItem(mediaItem)
    }

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        val handler = createHandlerForCurrentLooper()
        synchronized(this) {
            playbackHandler = handler
        }
        mediaSource.addEventListener(handler, this)
        mediaSource.addDrmEventListener(handler, this)
        mediaSource.prepareSource(this, getPlayerId(), getBandwidthMeter())
    }

    @Throws(IOException::class)
    override fun maybeThrowSourceInfoRefreshError() {
        mediaSource.maybeThrowSourceInfoRefreshError()
    }

    override fun enableInternal() {
        mediaSource.enable(this)
    }

    override fun disableInternal() {
        releaseLastUsedMediaPeriod()
        mediaSource.disable(this)
    }

    override fun onSourceInfoRefreshed(source: MediaSource, timeline: Timeline) {
        sourceTimeline = timeline
        if ((adPlaybackStateUpdater == null
                    || !adPlaybackStateUpdater.onAdPlaybackStateUpdateRequested(timeline))
            && !adPlaybackStates.isEmpty()
        ) {
            setAdPlaybackStates(adPlaybackStates, timeline)
        }
    }

    override fun releaseSourceInternal() {
        releaseLastUsedMediaPeriod()
        synchronized(this) {
            playbackHandler = null
        }
        mediaSource.releaseSource(this)
        mediaSource.removeEventListener(this)
        mediaSource.removeDrmEventListener(this)
    }

    override fun createPeriod(
        id: MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod {
        var sharedPeriod: SharedMediaPeriod? = null
        val sharedMediaPeriodKey = Pair<Long, Any>(id.windowSequenceNumber, id.periodUid)
        var reusedSharedPeriod = false
        if (lastUsedMediaPeriod != null) {
            if (lastUsedMediaPeriod!!.periodUid == id.periodUid) {
                sharedPeriod = lastUsedMediaPeriod
                mediaPeriods.put(sharedMediaPeriodKey, sharedPeriod)
                reusedSharedPeriod = true
            } else {
                lastUsedMediaPeriod!!.release(mediaSource)
            }
            lastUsedMediaPeriod = null
        }
        if (sharedPeriod == null) {
            val lastExistingPeriod: SharedMediaPeriod? =
                Iterables.getLast<SharedMediaPeriod?>(
                    mediaPeriods.get(sharedMediaPeriodKey),
                    null
                )
            if (lastExistingPeriod != null
                && lastExistingPeriod.canReuseMediaPeriod(id, startPositionUs)
            ) {
                sharedPeriod = lastExistingPeriod
            } else {
                val adPlaybackState = adPlaybackStates[id.periodUid]!!
                val streamPositionUs = ServerSideAdInsertionUtil.getStreamPositionUs(
                    startPositionUs,
                    id,
                    adPlaybackState
                )
                sharedPeriod =
                    SharedMediaPeriod(
                        mediaSource.createPeriod(
                            MediaPeriodId(id.periodUid, id.windowSequenceNumber),
                            allocator,
                            streamPositionUs
                        ),
                        id.periodUid,
                        adPlaybackState
                    )
                mediaPeriods.put(sharedMediaPeriodKey, sharedPeriod)
            }
        }
        val endPositionUs: Long = getEndPositionInSplitPeriodUs(splitTimeline, id, period)
        val mediaPeriod =
            MediaPeriodImpl(
                sharedPeriod,
                id,
                endPositionUs,
                createEventDispatcher(id),
                createDrmEventDispatcher(id)
            )
        sharedPeriod.add(mediaPeriod)
        if (reusedSharedPeriod && sharedPeriod.trackSelections.isNotEmpty()) {
            mediaPeriod.seekToUs(startPositionUs)
        }
        return mediaPeriod
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        val mediaPeriodImpl = mediaPeriod as MediaPeriodImpl
        mediaPeriodImpl.sharedPeriod.remove(mediaPeriodImpl)
        if (mediaPeriodImpl.sharedPeriod.isUnused) {
            mediaPeriods.remove(
                Pair<Long?, Any?>(
                    mediaPeriodImpl.mediaPeriodId.windowSequenceNumber,
                    mediaPeriodImpl.mediaPeriodId.periodUid
                ),
                mediaPeriodImpl.sharedPeriod
            )
            if (mediaPeriods.isEmpty) {
                // Keep until disabled.
                lastUsedMediaPeriod = mediaPeriodImpl.sharedPeriod
            } else {
                mediaPeriodImpl.sharedPeriod.release(mediaSource)
            }
        }
    }

    override fun onDrmSessionAcquired(
        windowIndex: Int,
        mediaPeriodId: MediaPeriodId?,
        @DrmSession.State state: @DrmSession.State Int
    ) {
        val mediaPeriod =
            getMediaPeriodForEvent(
                mediaPeriodId, null, true
            )
        if (mediaPeriod == null) {
            drmEventDispatcherWithoutId.drmSessionAcquired(state)
        } else {
            mediaPeriod.drmEventDispatcher.drmSessionAcquired(state)
        }
    }

    override fun onDrmKeysLoaded(
        windowIndex: Int, mediaPeriodId: MediaPeriodId?, keyRequestInfo: KeyRequestInfo
    ) {
        val mediaPeriod =
            getMediaPeriodForEvent(
                mediaPeriodId, null, false
            )
        if (mediaPeriod == null) {
            drmEventDispatcherWithoutId.drmKeysLoaded(keyRequestInfo)
        } else {
            mediaPeriod.drmEventDispatcher.drmKeysLoaded(keyRequestInfo)
        }
    }

    override fun onDrmSessionManagerError(
        windowIndex: Int, mediaPeriodId: MediaPeriodId?, error: Exception
    ) {
        val mediaPeriod =
            getMediaPeriodForEvent(
                mediaPeriodId, null, false
            )
        if (mediaPeriod == null) {
            drmEventDispatcherWithoutId.drmSessionManagerError(error)
        } else {
            mediaPeriod.drmEventDispatcher.drmSessionManagerError(error)
        }
    }

    override fun onDrmKeysRestored(windowIndex: Int, mediaPeriodId: MediaPeriodId?) {
        val mediaPeriod =
            getMediaPeriodForEvent(
                mediaPeriodId, null, false
            )
        if (mediaPeriod == null) {
            drmEventDispatcherWithoutId.drmKeysRestored()
        } else {
            mediaPeriod.drmEventDispatcher.drmKeysRestored()
        }
    }

    override fun onDrmKeysRemoved(windowIndex: Int, mediaPeriodId: MediaPeriodId?) {
        val mediaPeriod =
            getMediaPeriodForEvent(
                mediaPeriodId, null, false
            )
        if (mediaPeriod == null) {
            drmEventDispatcherWithoutId.drmKeysRemoved()
        } else {
            mediaPeriod.drmEventDispatcher.drmKeysRemoved()
        }
    }

    override fun onDrmSessionReleased(windowIndex: Int, mediaPeriodId: MediaPeriodId?) {
        val mediaPeriod =
            getMediaPeriodForEvent(
                mediaPeriodId, null, false
            )
        if (mediaPeriod == null) {
            drmEventDispatcherWithoutId.drmSessionReleased()
        } else {
            mediaPeriod.drmEventDispatcher.drmSessionReleased()
        }
    }

    override fun onLoadStarted(
        windowIndex: Int,
        mediaPeriodId: MediaPeriodId?,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        retryCount: Int
    ) {
        if (retryCount == 0) {
            val mediaPeriod =
                getMediaPeriodForEvent(mediaPeriodId, mediaLoadData, true)
            if (mediaPeriod == null) {
                mediaSourceEventDispatcherWithoutId!!.loadStarted(
                    loadEventInfo, mediaLoadData, 0
                )
            } else {
                mediaPeriod.sharedPeriod.onLoadStarted(loadEventInfo, mediaLoadData)
                mediaPeriod.mediaSourceEventDispatcher.loadStarted(
                    loadEventInfo,
                    correctMediaLoadData(
                        mediaPeriod,
                        mediaLoadData,
                        adPlaybackStates[mediaPeriod.mediaPeriodId.periodUid]!!
                    ),
                    0
                )
            }
        }
    }

    override fun onLoadCompleted(
        windowIndex: Int,
        mediaPeriodId: MediaPeriodId?,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        val mediaPeriod =
            getMediaPeriodForEvent(mediaPeriodId, mediaLoadData, true)
        if (mediaPeriod == null) {
            mediaSourceEventDispatcherWithoutId!!.loadCompleted(loadEventInfo, mediaLoadData)
        } else {
            mediaPeriod.sharedPeriod.onLoadFinished(loadEventInfo)
            mediaPeriod.mediaSourceEventDispatcher.loadCompleted(
                loadEventInfo,
                correctMediaLoadData(
                    mediaPeriod,
                    mediaLoadData,
                    adPlaybackStates[mediaPeriod.mediaPeriodId.periodUid]!!
                )
            )
        }
    }

    override fun onLoadCanceled(
        windowIndex: Int,
        mediaPeriodId: MediaPeriodId?,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        val mediaPeriod =
            getMediaPeriodForEvent(mediaPeriodId, mediaLoadData, true)
        if (mediaPeriod == null) {
            mediaSourceEventDispatcherWithoutId!!.loadCanceled(loadEventInfo, mediaLoadData)
        } else {
            mediaPeriod.sharedPeriod.onLoadFinished(loadEventInfo)
            mediaPeriod.mediaSourceEventDispatcher.loadCanceled(
                loadEventInfo,
                correctMediaLoadData(
                    mediaPeriod,
                    mediaLoadData,
                    adPlaybackStates[mediaPeriod.mediaPeriodId.periodUid]!!
                )
            )
        }
    }

    override fun onLoadError(
        windowIndex: Int,
        mediaPeriodId: MediaPeriodId?,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean
    ) {
        val mediaPeriod =
            getMediaPeriodForEvent(mediaPeriodId, mediaLoadData, true)
        if (mediaPeriod == null) {
            mediaSourceEventDispatcherWithoutId!!.loadError(
                loadEventInfo, mediaLoadData, error, wasCanceled
            )
        } else {
            if (wasCanceled) {
                mediaPeriod.sharedPeriod.onLoadFinished(loadEventInfo)
            }
            mediaPeriod.mediaSourceEventDispatcher.loadError(
                loadEventInfo,
                correctMediaLoadData(
                    mediaPeriod,
                    mediaLoadData,
                    adPlaybackStates[mediaPeriod.mediaPeriodId.periodUid]!!
                ),
                error,
                wasCanceled
            )
        }
    }

    override fun onUpstreamDiscarded(
        windowIndex: Int, mediaPeriodId: MediaPeriodId?, mediaLoadData: MediaLoadData
    ) {
        val mediaPeriod =
            getMediaPeriodForEvent(mediaPeriodId, mediaLoadData, false)
        if (mediaPeriod == null) {
            mediaSourceEventDispatcherWithoutId!!.upstreamDiscarded(mediaLoadData)
        } else {
            mediaPeriod.mediaSourceEventDispatcher.upstreamDiscarded(
                correctMediaLoadData(
                    mediaPeriod,
                    mediaLoadData,
                    adPlaybackStates[mediaPeriod.mediaPeriodId.periodUid]!!
                )
            )
        }
    }

    override fun onDownstreamFormatChanged(
        windowIndex: Int, mediaPeriodId: MediaPeriodId?, mediaLoadData: MediaLoadData
    ) {
        val mediaPeriod =
            getMediaPeriodForEvent(mediaPeriodId, mediaLoadData, false)
        if (mediaPeriod == null) {
            mediaSourceEventDispatcherWithoutId!!.downstreamFormatChanged(mediaLoadData)
        } else {
            mediaPeriod.sharedPeriod.onDownstreamFormatChanged(mediaPeriod, mediaLoadData)
            mediaPeriod.mediaSourceEventDispatcher.downstreamFormatChanged(
                correctMediaLoadData(
                    mediaPeriod,
                    mediaLoadData,
                    adPlaybackStates[mediaPeriod.mediaPeriodId.periodUid]!!
                )
            )
        }
    }

    private fun releaseLastUsedMediaPeriod() {
        if (lastUsedMediaPeriod != null) {
            lastUsedMediaPeriod!!.release(mediaSource)
            lastUsedMediaPeriod = null
        }
    }

    private fun getMediaPeriodForEvent(
        mediaPeriodId: MediaPeriodId?,
        mediaLoadData: MediaLoadData?,
        useLoadingPeriod: Boolean
    ): MediaPeriodImpl? {
        if (mediaPeriodId == null) {
            return null
        }
        val periods =
            mediaPeriods.get(
                Pair<Long, Any>(mediaPeriodId.windowSequenceNumber, mediaPeriodId.periodUid)
            )
        if (periods.isEmpty()) {
            return null
        }
        if (useLoadingPeriod) {
            val loadingPeriod: SharedMediaPeriod = Iterables.getLast<SharedMediaPeriod>(periods)
            return if (loadingPeriod.loadingPeriod != null)
                loadingPeriod.loadingPeriod
            else
                Iterables.getLast<MediaPeriodImpl?>(loadingPeriod.mediaPeriods)
        }
        for (i in periods.indices) {
            val period = periods[i]!!.getMediaPeriodForEvent(mediaLoadData)
            if (period != null) {
                return period
            }
        }
        return periods[0].mediaPeriods[0]
    }

    class SharedMediaPeriod(
        val actualMediaPeriod: MediaPeriod,
        val periodUid: Any,
        var adPlaybackState: AdPlaybackState
    ) : MediaPeriod.Callback {
        val mediaPeriods = mutableListOf<MediaPeriodImpl>()
        val activeLoads = mutableMapOf<Long, Pair<LoadEventInfo, MediaLoadData>>()
        var loadingPeriod: MediaPeriodImpl? = null
        var hasStartedPreparing = false
        var isPrepared = false
        var trackSelections = arrayOfNulls<ExoTrackSelection>(0)
        var sampleStreams = arrayOfNulls<SampleStream>(0)
        var lastDownstreamFormatChangeData = arrayOfNulls<MediaLoadData>(0)

        fun updateAdPlaybackState(
            splitTimeline: Timeline, adPlaybackState: AdPlaybackState, period: Timeline.Period
        ) {
            this.adPlaybackState = adPlaybackState
            for (i in mediaPeriods.indices) {
                val mediaPeriod = mediaPeriods[i]
                mediaPeriod.updateEndPositionUs(
                    getEndPositionInSplitPeriodUs(splitTimeline, mediaPeriod.mediaPeriodId, period)
                )
            }
        }

        fun add(mediaPeriod: MediaPeriodImpl?) {
            mediaPeriods.add(mediaPeriod!!)
        }

        fun remove(mediaPeriod: MediaPeriodImpl) {
            if (mediaPeriod == loadingPeriod) {
                loadingPeriod = null
                activeLoads.clear()
            }
            mediaPeriods.remove(mediaPeriod)
        }

        val isUnused: Boolean
            get() = mediaPeriods.isEmpty()

        fun release(mediaSource: MediaSource) {
            mediaSource.releasePeriod(actualMediaPeriod)
        }

        fun canReuseMediaPeriod(id: MediaPeriodId, positionUs: Long): Boolean {
            if (mediaPeriods.isEmpty()) {
                return false
            }
            val previousPeriod = Iterables.getLast<MediaPeriodImpl>(mediaPeriods)
            if (previousPeriod.endPositionUs == C.TIME_UNSET) {
                return false
            }
            val previousEndPositionUs =
                ServerSideAdInsertionUtil.getStreamPositionUs(
                    previousPeriod.endPositionUs, previousPeriod.mediaPeriodId, adPlaybackState
                )
            val startPositionUs =
                ServerSideAdInsertionUtil.getStreamPositionUs(positionUs, id, adPlaybackState)
            return startPositionUs == previousEndPositionUs
        }

        fun getMediaPeriodForEvent(mediaLoadData: MediaLoadData?): MediaPeriodImpl? {
            if (mediaLoadData != null && mediaLoadData.mediaStartTimeMs != C.TIME_UNSET) {
                for (i in mediaPeriods.indices) {
                    val mediaPeriod = mediaPeriods[i]
                    if (!mediaPeriod.isPrepared) {
                        continue
                    }
                    val startTimeInPeriodUs =
                        ServerSideAdInsertionUtil.getMediaPeriodPositionUs(
                            msToUs(mediaLoadData.mediaStartTimeMs),
                            mediaPeriod.mediaPeriodId,
                            adPlaybackState
                        )
                    if (startTimeInPeriodUs >= 0
                        && (mediaPeriod.endPositionUs == C.TIME_UNSET
                                || startTimeInPeriodUs < mediaPeriod.endPositionUs)
                    ) {
                        return mediaPeriod
                    }
                }
            }
            return null
        }

        fun prepare(mediaPeriod: MediaPeriodImpl, positionUs: Long) {
            mediaPeriod.lastStartPositionUs = positionUs
            if (hasStartedPreparing) {
                if (isPrepared) {
                    mediaPeriod.onPrepared()
                }
                return
            }
            hasStartedPreparing = true
            val preparePositionUs =
                ServerSideAdInsertionUtil.getStreamPositionUs(
                    positionUs,
                    mediaPeriod.mediaPeriodId,
                    adPlaybackState
                )
            actualMediaPeriod.prepare(this, preparePositionUs)
        }

        @Throws(IOException::class)
        fun maybeThrowPrepareError() {
            actualMediaPeriod.maybeThrowPrepareError()
        }

        val trackGroups: TrackGroupArray
            get() = actualMediaPeriod.trackGroups

        fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> {
            return actualMediaPeriod.getStreamKeys(trackSelections)
        }

        fun continueLoading(mediaPeriod: MediaPeriodImpl, loadingInfo: LoadingInfo): Boolean {
            val loadingPeriod = this.loadingPeriod
            if (loadingPeriod != null && mediaPeriod != loadingPeriod) {
                for (loadData in activeLoads.values) {
                    loadingPeriod.mediaSourceEventDispatcher.loadCompleted(
                        loadData.first,
                        correctMediaLoadData(
                            loadingPeriod,
                            loadData.second!!,
                            adPlaybackState
                        )
                    )
                    mediaPeriod.mediaSourceEventDispatcher.loadStarted(
                        loadData.first,
                        correctMediaLoadData(
                            mediaPeriod,
                            loadData.second!!,
                            adPlaybackState
                        ),
                        0
                    )
                }
            }
            this.loadingPeriod = mediaPeriod
            val actualPlaybackPositionUs =
                getStreamPositionUsWithNotYetStartedHandling(
                    mediaPeriod,
                    loadingInfo.playbackPositionUs
                )
            return actualMediaPeriod.continueLoading(
                loadingInfo.buildUpon().setPlaybackPositionUs(actualPlaybackPositionUs).build()
            )
        }

        fun isLoading(mediaPeriod: MediaPeriodImpl): Boolean {
            return mediaPeriod == loadingPeriod && actualMediaPeriod.isLoading
        }

        fun getBufferedPositionUs(mediaPeriod: MediaPeriodImpl): Long {
            return getMediaPeriodPositionUsWithEndOfSourceHandling(
                mediaPeriod, actualMediaPeriod.bufferedPositionUs
            )
        }

        fun getNextLoadPositionUs(mediaPeriod: MediaPeriodImpl): Long {
            return getMediaPeriodPositionUsWithEndOfSourceHandling(
                mediaPeriod, actualMediaPeriod.nextLoadPositionUs
            )
        }

        fun seekToUs(mediaPeriod: MediaPeriodImpl, positionUs: Long): Long {
            val actualRequestedPositionUs =
                ServerSideAdInsertionUtil.getStreamPositionUs(
                    positionUs,
                    mediaPeriod.mediaPeriodId,
                    adPlaybackState
                )
            val newActualPositionUs = actualMediaPeriod.seekToUs(actualRequestedPositionUs)
            return ServerSideAdInsertionUtil.getMediaPeriodPositionUs(
                newActualPositionUs, mediaPeriod.mediaPeriodId, adPlaybackState
            )
        }

        fun getAdjustedSeekPositionUs(
            mediaPeriod: MediaPeriodImpl, positionUs: Long, seekParameters: SeekParameters
        ): Long {
            val actualRequestedPositionUs =
                ServerSideAdInsertionUtil.getStreamPositionUs(
                    positionUs,
                    mediaPeriod.mediaPeriodId,
                    adPlaybackState
                )
            val adjustedActualPositionUs =
                actualMediaPeriod.getAdjustedSeekPositionUs(
                    actualRequestedPositionUs,
                    seekParameters
                )
            return ServerSideAdInsertionUtil.getMediaPeriodPositionUs(
                adjustedActualPositionUs, mediaPeriod.mediaPeriodId, adPlaybackState
            )
        }

        fun discardBuffer(mediaPeriod: MediaPeriodImpl, positionUs: Long, toKeyframe: Boolean) {
            val actualPositionUs =
                ServerSideAdInsertionUtil.getStreamPositionUs(
                    positionUs,
                    mediaPeriod.mediaPeriodId,
                    adPlaybackState
                )
            actualMediaPeriod.discardBuffer(actualPositionUs, toKeyframe)
        }

        fun reevaluateBuffer(mediaPeriod: MediaPeriodImpl, positionUs: Long) {
            actualMediaPeriod.reevaluateBuffer(
                getStreamPositionUsWithNotYetStartedHandling(mediaPeriod, positionUs)
            )
        }

        fun readDiscontinuity(mediaPeriod: MediaPeriodImpl): Long {
            if (mediaPeriod != mediaPeriods[0]) {
                return C.TIME_UNSET
            }
            val actualDiscontinuityPositionUs = actualMediaPeriod.readDiscontinuity()
            return if (actualDiscontinuityPositionUs == C.TIME_UNSET)
                C.TIME_UNSET
            else
                ServerSideAdInsertionUtil.getMediaPeriodPositionUs(
                    actualDiscontinuityPositionUs, mediaPeriod.mediaPeriodId, adPlaybackState
                )
        }

        fun selectTracks(
            mediaPeriod: MediaPeriodImpl,
            selections: Array<ExoTrackSelection?>,
            mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>,
            streamResetFlags: BooleanArray,
            positionUs: Long
        ): Long {
            mediaPeriod.lastStartPositionUs = positionUs
            if (mediaPeriod == mediaPeriods[0]) {
                // Do the real selection for the current first period in the list.
                trackSelections =
                    selections.copyOf<ExoTrackSelection?>(selections.size)
                val requestedPositionUs =
                    ServerSideAdInsertionUtil.getStreamPositionUs(
                        positionUs,
                        mediaPeriod.mediaPeriodId,
                        adPlaybackState
                    )
                val realStreams =
                    if (sampleStreams.isEmpty())
                        arrayOfNulls(selections.size)
                    else sampleStreams.copyOf()
                val startPositionUs =
                    actualMediaPeriod.selectTracks(
                        selections,
                        mayRetainStreamFlags,
                        realStreams,
                        streamResetFlags,
                        requestedPositionUs
                    )
                this.sampleStreams = realStreams.copyOf()
                lastDownstreamFormatChangeData =
                    lastDownstreamFormatChangeData.copyOf(realStreams.size)
                for (i in realStreams.indices) {
                    if (realStreams[i] == null) {
                        streams[i] = null
                        lastDownstreamFormatChangeData[i] = null
                    } else if (streams[i] == null || streamResetFlags[i]) {
                        streams[i] = SampleStreamImpl(mediaPeriod, i)
                        lastDownstreamFormatChangeData[i] = null
                    }
                }
                return ServerSideAdInsertionUtil.getMediaPeriodPositionUs(
                    startPositionUs, mediaPeriod.mediaPeriodId, adPlaybackState
                )
            }
            // All subsequent periods need to have the same selection. Ignore tracks or add empty tracks
            // if this isn't the case.
            for (i in selections.indices) {
                if (selections[i] != null) {
                    streamResetFlags[i] = !mayRetainStreamFlags[i] || streams[i] == null
                    if (streamResetFlags[i]) {
                        streams[i] =
                            if (trackSelections[i] == selections[i])
                                SampleStreamImpl(mediaPeriod, i)
                            else
                                EmptySampleStream()
                    }
                } else {
                    streams[i] = null
                    streamResetFlags[i] = true
                }
            }
            return positionUs
        }

        fun readData(
            mediaPeriod: MediaPeriodImpl,
            streamIndex: Int,
            formatHolder: FormatHolder,
            buffer: DecoderInputBuffer,
            readFlags: @ReadFlags Int
        ): @ReadDataResult Int {
            val peekingFlags: @ReadFlags Int =
                readFlags or SampleStream.FLAG_PEEK or SampleStream.FLAG_OMIT_SAMPLE_DATA
            val bufferedPositionUs = getBufferedPositionUs(mediaPeriod)
            val result: @ReadDataResult Int =
                sampleStreams[streamIndex]!!.readData(formatHolder, buffer, peekingFlags)
            val adjustedTimeUs =
                getMediaPeriodPositionUsWithEndOfSourceHandling(mediaPeriod, buffer.timeUs)
            if ((result == C.RESULT_BUFFER_READ && adjustedTimeUs == C.TIME_END_OF_SOURCE)
                || (result == C.RESULT_NOTHING_READ && bufferedPositionUs == C.TIME_END_OF_SOURCE && !buffer.waitingForKeys)
            ) {
                maybeNotifyDownstreamFormatChanged(mediaPeriod, streamIndex)
                buffer.clear()
                buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
                return C.RESULT_BUFFER_READ
            }
            if (result == C.RESULT_BUFFER_READ) {
                maybeNotifyDownstreamFormatChanged(mediaPeriod, streamIndex)
                sampleStreams[streamIndex]!!.readData(formatHolder, buffer, readFlags)
                buffer.timeUs = adjustedTimeUs
            }
            return result
        }

        fun skipData(mediaPeriod: MediaPeriodImpl, streamIndex: Int, positionUs: Long): Int {
            val actualPositionUs =
                ServerSideAdInsertionUtil.getStreamPositionUs(
                    positionUs,
                    mediaPeriod.mediaPeriodId,
                    adPlaybackState
                )
            return sampleStreams[streamIndex]!!.skipData(actualPositionUs)
        }

        fun isReady(streamIndex: Int): Boolean {
            return sampleStreams[streamIndex]!!.isReady
        }

        @Throws(IOException::class)
        fun maybeThrowError(streamIndex: Int) {
            sampleStreams[streamIndex]!!.maybeThrowError()
        }

        fun onDownstreamFormatChanged(
            mediaPeriod: MediaPeriodImpl, mediaLoadData: MediaLoadData
        ) {
            val streamIndex = findMatchingStreamIndex(mediaLoadData)
            if (streamIndex != C.INDEX_UNSET) {
                lastDownstreamFormatChangeData[streamIndex] = mediaLoadData
                mediaPeriod.hasNotifiedDownstreamFormatChange[streamIndex] = true
            }
        }

        fun onLoadStarted(loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData?) {
            activeLoads[loadEventInfo.loadTaskId] =
                Pair.create<LoadEventInfo, MediaLoadData>(loadEventInfo, mediaLoadData)
        }

        fun onLoadFinished(loadEventInfo: LoadEventInfo) {
            activeLoads.remove(loadEventInfo.loadTaskId)
        }

        override fun onPrepared(actualMediaPeriod: MediaPeriod) {
            isPrepared = true
            for (i in mediaPeriods.indices) {
                mediaPeriods[i].onPrepared()
            }
        }

        override fun onContinueLoadingRequested(source: MediaPeriod) {
            if (loadingPeriod == null) {
                return
            }
            loadingPeriod!!.callback!!.onContinueLoadingRequested(loadingPeriod!!)
        }

        fun setUsesStreamPrerollFlags() {
            actualMediaPeriod.setUsesStreamPrerollFlags()
        }

        fun getStreamPositionUsWithNotYetStartedHandling(
            mediaPeriod: MediaPeriodImpl, positionUs: Long
        ): Long {
            if (positionUs < mediaPeriod.lastStartPositionUs) {
                val actualStartPositionUs =
                    ServerSideAdInsertionUtil.getStreamPositionUs(
                        mediaPeriod.lastStartPositionUs, mediaPeriod.mediaPeriodId, adPlaybackState
                    )
                return actualStartPositionUs - (mediaPeriod.lastStartPositionUs - positionUs)
            }
            return ServerSideAdInsertionUtil.getStreamPositionUs(
                positionUs,
                mediaPeriod.mediaPeriodId,
                adPlaybackState
            )
        }

        fun getMediaPeriodPositionUsWithEndOfSourceHandling(
            mediaPeriod: MediaPeriodImpl, positionUs: Long
        ): Long {
            if (positionUs == C.TIME_END_OF_SOURCE) {
                return C.TIME_END_OF_SOURCE
            }
            val mediaPeriodPositionUs =
                ServerSideAdInsertionUtil.getMediaPeriodPositionUs(
                    positionUs,
                    mediaPeriod.mediaPeriodId,
                    adPlaybackState
                )
            return if (mediaPeriod.endPositionUs != C.TIME_UNSET
                && mediaPeriodPositionUs >= mediaPeriod.endPositionUs
            )
                C.TIME_END_OF_SOURCE
            else
                mediaPeriodPositionUs
        }

        fun findMatchingStreamIndex(mediaLoadData: MediaLoadData): Int {
            if (mediaLoadData.trackFormat == null) {
                return C.INDEX_UNSET
            }
            for (i in trackSelections.indices) {
                if (trackSelections[i] != null) {
                    val trackGroup = trackSelections[i]!!.trackGroup
                    // Muxed primary track group should be the first in the list. We need to match Formats on
                    // their id only as the muxed format and the format in the track group won't match.
                    val isPrimaryTrackGroup =
                        mediaLoadData.trackType == C.TRACK_TYPE_DEFAULT
                                && trackGroup == this.trackGroups.get(0)
                    for (j in 0..<trackGroup.length) {
                        val format = trackGroup.getFormat(j)
                        if (format == mediaLoadData.trackFormat
                            || (isPrimaryTrackGroup
                                    && format.id != null && format.id == mediaLoadData.trackFormat.id)
                        ) {
                            return i
                        }
                    }
                }
            }
            return C.INDEX_UNSET
        }

        fun maybeNotifyDownstreamFormatChanged(mediaPeriod: MediaPeriodImpl, streamIndex: Int) {
            if (!mediaPeriod.hasNotifiedDownstreamFormatChange[streamIndex]
                && lastDownstreamFormatChangeData[streamIndex] != null
            ) {
                mediaPeriod.hasNotifiedDownstreamFormatChange[streamIndex] = true
                mediaPeriod.mediaSourceEventDispatcher.downstreamFormatChanged(
                    correctMediaLoadData(
                        mediaPeriod, lastDownstreamFormatChangeData[streamIndex]!!, adPlaybackState
                    )
                )
            }
        }
    }

    private class ServerSideAdInsertionTimeline(
        contentTimeline: Timeline,
        adPlaybackStates: Map<Any?, AdPlaybackState?>
    ) : ForwardingTimeline(contentTimeline) {
        private val adPlaybackStates: Map<Any?, AdPlaybackState?>

        init {
            Preconditions.checkState(contentTimeline.windowCount == 1)
            val period = Period()
            for (i in 0..<contentTimeline.periodCount) {
                contentTimeline.getPeriod(i, period, true)
                Preconditions.checkState(
                    adPlaybackStates.containsKey(
                        period.uid!!
                    )
                )
            }
            this.adPlaybackStates = adPlaybackStates
        }

        override fun getWindow(
            windowIndex: Int,
            window: Window,
            defaultPositionProjectionUs: Long
        ): Window {
            super.getWindow(windowIndex, window, defaultPositionProjectionUs)
            val period = Period()
            val firstPeriodUid =
                getPeriod(
                    window.firstPeriodIndex,
                    period,
                    true
                ).uid!!
            val firstAdPlaybackState = adPlaybackStates[firstPeriodUid]!!
            val positionInPeriodUs =
                ServerSideAdInsertionUtil.getMediaPeriodPositionUsForContent(
                    window.positionInFirstPeriodUs,
                    C.INDEX_UNSET,
                    firstAdPlaybackState
                )
            if (window.durationUs == C.TIME_UNSET) {
                if (firstAdPlaybackState.contentDurationUs != C.TIME_UNSET) {
                    window.durationUs = firstAdPlaybackState.contentDurationUs - positionInPeriodUs
                }
            } else {
                val originalLastPeriod =
                    super.getPeriod(
                        window.lastPeriodIndex,
                        period,
                        true
                    )
                val originalLastPeriodPositionInWindowUs = originalLastPeriod.positionInWindowUs
                val lastAdPlaybackState = adPlaybackStates[originalLastPeriod.uid]!!
                val adjustedLastPeriod =
                    getPeriod(window.lastPeriodIndex, period)
                val originalWindowDurationInLastPeriodUs =
                    window.durationUs - originalLastPeriodPositionInWindowUs
                val adjustedWindowDurationInLastPeriodUs =
                    ServerSideAdInsertionUtil.getMediaPeriodPositionUsForContent(
                        originalWindowDurationInLastPeriodUs,
                        C.INDEX_UNSET,
                        lastAdPlaybackState
                    )
                window.durationUs =
                    adjustedLastPeriod.positionInWindowUs + adjustedWindowDurationInLastPeriodUs
            }
            window.positionInFirstPeriodUs = positionInPeriodUs
            return window
        }

        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
            super.getPeriod(periodIndex, period, true)
            val adPlaybackState = adPlaybackStates[period.uid]!!
            var durationUs = period.durationUs
            if (durationUs == C.TIME_UNSET) {
                durationUs = adPlaybackState.contentDurationUs
            } else {
                durationUs =
                    ServerSideAdInsertionUtil.getMediaPeriodPositionUsForContent(
                        durationUs, C.INDEX_UNSET, adPlaybackState
                    )
            }
            var positionInWindowUs: Long = 0
            val innerPeriod = Period()
            for (i in 0..<periodIndex + 1) {
                timeline.getPeriod(i, innerPeriod, true)
                val innerAdPlaybackState = adPlaybackStates[innerPeriod.uid]!!
                if (i == 0) {
                    positionInWindowUs =
                        -ServerSideAdInsertionUtil.getMediaPeriodPositionUsForContent(
                            -innerPeriod.positionInWindowUs,
                            C.INDEX_UNSET,
                            innerAdPlaybackState
                        )
                }
                if (i != periodIndex) {
                    positionInWindowUs +=
                        ServerSideAdInsertionUtil.getMediaPeriodPositionUsForContent(
                            innerPeriod.durationUs,
                            C.INDEX_UNSET,
                            innerAdPlaybackState
                        )
                }
            }
            period.set(
                period.id,
                period.uid,
                period.windowIndex,
                durationUs,
                positionInWindowUs,
                adPlaybackState,
                period.isPlaceholder
            )
            return period
        }
    }

    @VisibleForTesting
    class MediaPeriodImpl(
        val sharedPeriod: SharedMediaPeriod,
        val mediaPeriodId: MediaPeriodId,
        var endPositionUs: Long,
        val mediaSourceEventDispatcher: MediaSourceEventListener.EventDispatcher,
        val drmEventDispatcher: DrmSessionEventListener.EventDispatcher
    ) : MediaPeriod {
        var callback:MediaPeriod.Callback? = null
        var lastStartPositionUs: Long = 0
        var hasNotifiedDownstreamFormatChange= BooleanArray(0)
        var isPrepared: Boolean = false

        fun onPrepared() {
            if (callback != null) {
                callback!!.onPrepared(this)
            }
            isPrepared = true
        }

        fun updateEndPositionUs(endPositionUs: Long) {
            this.endPositionUs = endPositionUs
        }

        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
            this.callback = callback
            sharedPeriod.prepare(this, positionUs)
        }

        @Throws(IOException::class)
        override fun maybeThrowPrepareError() {
            sharedPeriod.maybeThrowPrepareError()
        }

        override val trackGroups: TrackGroupArray
            get() = sharedPeriod.trackGroups

        override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> {
            return sharedPeriod.getStreamKeys(trackSelections)
        }

        override fun selectTracks(
            selections: Array<ExoTrackSelection?>,
            mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>,
            streamResetFlags: BooleanArray,
            positionUs: Long
        ): Long {
            if (hasNotifiedDownstreamFormatChange.isEmpty()) {
                hasNotifiedDownstreamFormatChange = BooleanArray(streams.size)
            }
            return sharedPeriod.selectTracks(
                this,
                selections,
                mayRetainStreamFlags,
                streams,
                streamResetFlags,
                positionUs
            )
        }

        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
            sharedPeriod.discardBuffer(this, positionUs, toKeyframe)
        }

        override fun setUsesStreamPrerollFlags() {
            sharedPeriod.setUsesStreamPrerollFlags()
        }

        override fun readDiscontinuity(): Long {
            return sharedPeriod.readDiscontinuity(this)
        }

        override fun seekToUs(positionUs: Long): Long {
            return sharedPeriod.seekToUs(this, positionUs)
        }

        override fun getAdjustedSeekPositionUs(
            positionUs: Long,
            seekParameters: SeekParameters
        ): Long {
            return sharedPeriod.getAdjustedSeekPositionUs(
                this, positionUs, seekParameters
            )
        }

        override val bufferedPositionUs: Long
            get() = sharedPeriod.getBufferedPositionUs(this)

        override val nextLoadPositionUs: Long
            get() = sharedPeriod.getNextLoadPositionUs(this)

        override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
            return sharedPeriod.continueLoading(this, loadingInfo)
        }

        override val isLoading: Boolean
            get() = sharedPeriod.isLoading(this)

        override fun reevaluateBuffer(positionUs: Long) {
            sharedPeriod.reevaluateBuffer(this, positionUs)
        }

        override fun setEndPositionUs(endPositionUs: Long): Long {
            return C.TIME_END_OF_SOURCE
        }
    }

    private class SampleStreamImpl(
        private val mediaPeriod: MediaPeriodImpl,
        private val streamIndex: Int
    ) : SampleStream {
        override val isReady: Boolean
            get() = mediaPeriod.sharedPeriod.isReady(streamIndex)

        @Throws(IOException::class)
        override fun maybeThrowError() {
            mediaPeriod.sharedPeriod.maybeThrowError(streamIndex)
        }

        override fun readData(
            formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: @ReadFlags Int
        ): @ReadDataResult Int {
            return mediaPeriod.sharedPeriod.readData(
                mediaPeriod, streamIndex, formatHolder, buffer, readFlags
            )
        }

        override fun skipData(positionUs: Long): Int {
            return mediaPeriod.sharedPeriod.skipData(mediaPeriod, streamIndex, positionUs)
        }

        override val flags: @SampleStream.Flags Int
            get() {
                val childStream = mediaPeriod.sharedPeriod.sampleStreams[streamIndex]
                val childFlags: @SampleStream.Flags Int = childStream?.flags ?: 0
                // The strict duration flag should only be applied to the very last stream of the content,
                // not for in-stream transitions (e.g. ad to content or content to ad).
                val isAtEndOfSourceStream =
                    mediaPeriod.mediaPeriodId.nextAdGroupIndex == C.INDEX_UNSET
                            && !mediaPeriod.mediaPeriodId.isAd
                return if (isAtEndOfSourceStream) {
                    childFlags
                } else {
                    childFlags and SampleStream.FLAG_STRICT_DURATION.inv()
                }
            }
    }

    companion object {
        private fun getEndPositionInSplitPeriodUs(
            splitTimeline: Timeline, id: MediaPeriodId, period: Timeline.Period
        ): Long {
            splitTimeline.getPeriodByUid(id.periodUid, period)
            if (id.isAd) {
                val adGroup = period.adPlaybackState.getAdGroup(id.adGroupIndex)
                return if (adGroup.count == C.LENGTH_UNSET) 0 else adGroup.durationsUs[id.adIndexInAdGroup]
            }
            val nextAdGroupTimeUs =
                if (id.nextAdGroupIndex == C.INDEX_UNSET)
                    C.TIME_END_OF_SOURCE
                else
                    period.getAdGroupTimeUs(id.nextAdGroupIndex)
            if (nextAdGroupTimeUs != C.TIME_END_OF_SOURCE) {
                return nextAdGroupTimeUs
            }
            return period.durationUs
        }

        private fun correctMediaLoadData(
            mediaPeriod: MediaPeriodImpl,
            mediaLoadData: MediaLoadData,
            adPlaybackState: AdPlaybackState
        ): MediaLoadData {
            return MediaLoadData(
                mediaLoadData.dataType,
                mediaLoadData.trackType,
                mediaLoadData.trackFormat,
                mediaLoadData.trackSelectionReason,
                mediaLoadData.trackSelectionData,
                correctMediaLoadDataPositionMs(
                    mediaLoadData.mediaStartTimeMs, mediaPeriod, adPlaybackState
                ),
                correctMediaLoadDataPositionMs(
                    mediaLoadData.mediaEndTimeMs,
                    mediaPeriod,
                    adPlaybackState
                )
            )
        }

        private fun correctMediaLoadDataPositionMs(
            mediaPositionMs: Long, mediaPeriod: MediaPeriodImpl, adPlaybackState: AdPlaybackState
        ): Long {
            if (mediaPositionMs == C.TIME_UNSET) {
                return C.TIME_UNSET
            }
            val mediaPositionUs = msToUs(mediaPositionMs)
            val id = mediaPeriod.mediaPeriodId
            val correctedPositionUs =
                if (id.isAd)
                    ServerSideAdInsertionUtil.getMediaPeriodPositionUsForAd(
                        mediaPositionUs, id.adGroupIndex, id.adIndexInAdGroup, adPlaybackState
                    ) // Ignore nextAdGroupIndex for content ids to correct timestamps that fall into future
                // content pieces (beyond nextAdGroupIndex).
                else
                    ServerSideAdInsertionUtil.getMediaPeriodPositionUsForContent(
                        mediaPositionUs, C.INDEX_UNSET, adPlaybackState
                    )
            return usToMs(correctedPositionUs)
        }
    }
}
