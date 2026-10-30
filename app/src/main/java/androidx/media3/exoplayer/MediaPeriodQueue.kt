package androidx.media3.exoplayer

import android.util.Log
import android.util.Pair
import androidx.annotation.IntDef
import androidx.media3.common.AdPlaybackState
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.HandlerWrapper
import androidx.media3.exoplayer.ExoPlayer.PreloadConfiguration
import androidx.media3.exoplayer.analytics.AnalyticsCollector
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.max

internal class MediaPeriodQueue(
    private val analyticsCollector: AnalyticsCollector,
    private val analyticsCollectorHandler: HandlerWrapper,
    private val mediaPeriodHolderFactory: MediaPeriodHolder.Factory,
    private var preloadConfiguration: PreloadConfiguration,
    rendererCount: Int
) {
    private val period: Timeline.Period
    private val window: Timeline.Window

    private var nextWindowSequenceNumber: Long = 0

    @Player.RepeatMode
    private var repeatMode: @Player.RepeatMode Int = 0
    private var shuffleModeEnabled = false
    var playingPeriod: MediaPeriodHolder? = null
        private set
    private val readingPeriods: Array<MediaPeriodHolder?>
    private val prewarmingPeriods: Array<MediaPeriodHolder?>
    var loadingPeriod: MediaPeriodHolder? = null
        private set
    var preloadingPeriod: MediaPeriodHolder? = null
        private set
    private var length = 0
    private var oldFrontPeriodUid: Any? = null
    private var oldFrontPeriodWindowSequenceNumber: Long = 0
    private var preloadPriorityList: MutableList<MediaPeriodHolder>

    fun updateRepeatMode(
        timeline: Timeline,
        @Player.RepeatMode repeatMode: @Player.RepeatMode Int
    ): Int {
        this.repeatMode = repeatMode
        return updateForPlaybackModeChange(timeline)
    }

    fun updateShuffleModeEnabled(
        timeline: Timeline, shuffleModeEnabled: Boolean
    ): @UpdatePeriodQueueResult Int {
        this.shuffleModeEnabled = shuffleModeEnabled
        return updateForPlaybackModeChange(timeline)
    }

    fun updatePreloadConfiguration(
        timeline: Timeline, preloadConfiguration: PreloadConfiguration
    ) {
        this.preloadConfiguration = preloadConfiguration
        invalidatePreloadPool(timeline)
    }

    fun isLoading(mediaPeriod: MediaPeriod?): Boolean {
        return this.loadingPeriod != null && loadingPeriod!!.mediaPeriod === mediaPeriod
    }

    fun isPreloading(mediaPeriod: MediaPeriod?): Boolean {
        return this.preloadingPeriod != null && preloadingPeriod!!.mediaPeriod === mediaPeriod
    }

    fun reevaluateBuffer(rendererPositionUs: Long) {
        if (this.loadingPeriod != null) {
            loadingPeriod!!.reevaluateBuffer(rendererPositionUs)
        }
    }

    fun shouldLoadNextMediaPeriod(): Boolean {
        return this.loadingPeriod == null
                || (!loadingPeriod!!.info.isFinal && loadingPeriod!!.isFullyBuffered
                && loadingPeriod!!.info.durationUs != C.TIME_UNSET && length < MAXIMUM_BUFFER_AHEAD_PERIODS)
    }

    fun getNextMediaPeriodInfo(
        rendererPositionUs: Long, playbackInfo: PlaybackInfo
    ): MediaPeriodInfo? {
        return if (this.loadingPeriod == null)
            getFirstMediaPeriodInfo(playbackInfo)
        else
            getFollowingMediaPeriodInfo(
                playbackInfo.timeline!!,
                this.loadingPeriod!!,
                rendererPositionUs
            )
    }

    fun enqueueNextMediaPeriodHolder(info: MediaPeriodInfo): MediaPeriodHolder? {
        val rendererPositionOffsetUs =
            if (this.loadingPeriod == null)
                INITIAL_RENDERER_POSITION_OFFSET_US
            else
                (loadingPeriod!!.rendererOffset + loadingPeriod!!.info.durationUs - info.startPositionUs)
        var newPeriodHolder = removePreloadedMediaPeriodHolder(info)
        if (newPeriodHolder == null) {
            newPeriodHolder = mediaPeriodHolderFactory.create(info, rendererPositionOffsetUs)
        } else {
            newPeriodHolder.info = info
            newPeriodHolder.rendererOffset = (rendererPositionOffsetUs)
        }
        if (this.loadingPeriod != null) {
            loadingPeriod!!.setNext(newPeriodHolder)
        } else {
            this.playingPeriod = newPeriodHolder
            Arrays.fill(readingPeriods, newPeriodHolder)
            Arrays.fill(prewarmingPeriods, newPeriodHolder)
        }
        oldFrontPeriodUid = null
        this.loadingPeriod = newPeriodHolder
        length++
        notifyQueueUpdate()
        return newPeriodHolder
    }

    fun invalidatePreloadPool(timeline: Timeline) {
        if (preloadConfiguration.targetPreloadDurationUs == C.TIME_UNSET || this.loadingPeriod == null) {
            releasePreloadPool()
            return
        }
        val loading = this.loadingPeriod
        val newPreloadPriorityList: MutableList<MediaPeriodHolder> = ArrayList()
        val defaultPositionOfNextWindow =
            getDefaultPeriodPositionOfNextWindow(timeline, loading!!.info.id.periodUid)
        if (defaultPositionOfNextWindow != null
            && !timeline
                .getWindow(
                    timeline.getPeriodByUid(defaultPositionOfNextWindow.first, period).windowIndex,
                    window
                )
                .isLive
        ) {
            var windowSequenceNumber =
                resolvePeriodUidToWindowSequenceNumberInPreloadPeriods(defaultPositionOfNextWindow.first)
            if (windowSequenceNumber == C.INDEX_UNSET.toLong()) {
                windowSequenceNumber = nextWindowSequenceNumber++
            }
            val nextInfo =
                getMediaPeriodInfoForPeriodPosition(
                    timeline,
                    defaultPositionOfNextWindow.first!!,
                    defaultPositionOfNextWindow.second!!,
                    windowSequenceNumber
                )
            var nextMediaPeriodHolder = removePreloadedMediaPeriodHolder(nextInfo)
            if (nextMediaPeriodHolder == null) {
                // The holder's renderer position offset may be different and is reset when enqueuing.
                val rendererPositionOffsetUs =
                    loading.rendererOffset + loading.info.durationUs - nextInfo.startPositionUs
                nextMediaPeriodHolder =
                    mediaPeriodHolderFactory.create(nextInfo, rendererPositionOffsetUs)
            }
            newPreloadPriorityList.add(nextMediaPeriodHolder!!)
        }
        releaseAndResetPreloadPriorityList(newPreloadPriorityList)
    }

    fun releasePreloadPool() {
        if (!preloadPriorityList.isEmpty()) {
            releaseAndResetPreloadPriorityList(ArrayList<MediaPeriodHolder>())
        }
    }

    private fun removePreloadedMediaPeriodHolder(info: MediaPeriodInfo): MediaPeriodHolder? {
        for (i in preloadPriorityList.indices) {
            val mediaPeriodHolder = preloadPriorityList[i]
            if (mediaPeriodHolder.canBeUsedForMediaPeriodInfo(info)) {
                return preloadPriorityList.removeAt(i)
            }
        }
        return null
    }

    private fun releaseAndResetPreloadPriorityList(newPriorityList: MutableList<MediaPeriodHolder>) {
        for (i in preloadPriorityList.indices) {
            preloadPriorityList[i].release()
        }
        preloadPriorityList = newPriorityList
        this.preloadingPeriod = null
        maybeUpdatePreloadMediaPeriodHolder()
    }

    private fun getMediaPeriodInfoForPeriodPosition(
        timeline: Timeline, periodUid: Any, positionUs: Long, windowSequenceNumber: Long
    ): MediaPeriodInfo {
        val mediaPeriodId: MediaPeriodId =
            resolveMediaPeriodIdForAds(
                timeline, periodUid, positionUs, windowSequenceNumber, window, period
            )
        return if (mediaPeriodId.isAd)
            getMediaPeriodInfoForAd(
                timeline,
                mediaPeriodId.periodUid,
                mediaPeriodId.adGroupIndex,
                mediaPeriodId.adIndexInAdGroup,  
                positionUs,
                mediaPeriodId.windowSequenceNumber
            )
        else
            getMediaPeriodInfoForContent(
                timeline,
                mediaPeriodId.periodUid,  
                positionUs,  
                C.TIME_UNSET,  
                C.TIME_UNSET,
                mediaPeriodId.windowSequenceNumber
            )
    }

    private fun getDefaultPeriodPositionOfNextWindow(
        timeline: Timeline, periodUid: Any
    ): Pair<Any, Long>? {
        val nextWindowIndex =
            timeline.getNextWindowIndex(
                timeline.getPeriodByUid(periodUid, period).windowIndex,
                repeatMode,
                shuffleModeEnabled
            )
        return if (nextWindowIndex != C.INDEX_UNSET)
            timeline.getPeriodPositionUs(
                window, period, nextWindowIndex,  C.TIME_UNSET
            )
        else
            null
    }

    fun getReadingPeriod(index: Int): MediaPeriodHolder? {
        return readingPeriods[index]
    }

    val earliestReadingPeriod: MediaPeriodHolder?
        get() {
            var periodHolder = this.playingPeriod
            while (periodHolder != null) {
                for (readingPeriod in readingPeriods) {
                    if (periodHolder == readingPeriod) {
                        return periodHolder
                    }
                }
                periodHolder = periodHolder.getNext()
            }
            return periodHolder
        }

    val latestReadingPeriod: MediaPeriodHolder?
        get() {
            if (readingPeriods.size == 0) {
                return null
            }
            var periodHolder = this.playingPeriod
            var latestReadingPeriod = readingPeriods[0]
            while (periodHolder != null) {
                for (readingPeriod in readingPeriods) {
                    if (periodHolder == readingPeriod) {
                        latestReadingPeriod = periodHolder
                        break
                    }
                }
                periodHolder = periodHolder.getNext()
            }
            return latestReadingPeriod
        }

    fun getPrewarmingPeriod(index: Int): MediaPeriodHolder? {
        return prewarmingPeriods[index]
    }

    val earliestPrewarmingPeriod: MediaPeriodHolder?
        get() {
            var periodHolder = this.playingPeriod
            while (periodHolder != null) {
                for (prewarmingPeriod in prewarmingPeriods) {
                    if (periodHolder == prewarmingPeriod) {
                        return periodHolder
                    }
                }
                periodHolder = periodHolder.getNext()
            }
            return periodHolder
        }

    fun advanceReadingPeriod(): MediaPeriodHolder {
        for (i in readingPeriods.indices) {
            val unused = advanceReadingPeriod(i)
        }
        return readingPeriods[0]!!
    }

    fun advanceReadingPeriod(index: Int): MediaPeriodHolder {
        if (prewarmingPeriods[index] == readingPeriods[index]) {
            prewarmingPeriods[index] =readingPeriods[index]!!.getNext()
        }
        readingPeriods[index] =readingPeriods[index]!! .getNext()
        notifyQueueUpdate()
        return readingPeriods[index]!!
    }

    fun advancePrewarmingPeriod() {
        for (i in prewarmingPeriods.indices) {
            advancePrewarmingPeriod(i)
        }
    }

    fun advancePrewarmingPeriod(index: Int) {
        prewarmingPeriods[index] =prewarmingPeriods[index]!! .getNext()
        notifyQueueUpdate()
    }

    fun advancePlayingPeriod(): MediaPeriodHolder? {
        if (this.playingPeriod == null) {
            return null
        }
        updateReadingMatchingPlayingPeriod()
        updatePrewarmingMatchingPlayingPeriod()
        val playingPeriod = this.playingPeriod
        playingPeriod!!.release()
        length--
        if (length == 0) {
            this.loadingPeriod = null
            oldFrontPeriodUid = playingPeriod!!.uid
            oldFrontPeriodWindowSequenceNumber = playingPeriod!!.info.id.windowSequenceNumber
        }
        this.playingPeriod = playingPeriod!!.getNext()
        notifyQueueUpdate()
        return this.playingPeriod
    }

    private fun updateReadingMatchingPlayingPeriod() {
        this.playingPeriod!!
        for (i in readingPeriods.indices) {
            if (this.playingPeriod == readingPeriods[i]) {
                readingPeriods[i] = playingPeriod!!.getNext()
            }
        }
    }

    private fun updatePrewarmingMatchingPlayingPeriod() {
        this.playingPeriod!!
        for (i in prewarmingPeriods.indices) {
            if (this.playingPeriod == prewarmingPeriods[i]) {
                prewarmingPeriods[i] = playingPeriod!!.getNext()
            }
        }
    }

    fun removeAfter(mediaPeriodHolder: MediaPeriodHolder?): Int {
        var mediaPeriodHolder = mediaPeriodHolder!!
        if (mediaPeriodHolder == this.loadingPeriod) {
            return 0
        }
        var removedResult = 0
        this.loadingPeriod = mediaPeriodHolder
        while (mediaPeriodHolder!!.getNext() != null) {
            mediaPeriodHolder =mediaPeriodHolder.getNext()!!
            for (i in readingPeriods.indices) {
                if (mediaPeriodHolder == readingPeriods[i]) {
                    readingPeriods[i] = this.playingPeriod
                    prewarmingPeriods[i] = this.playingPeriod
                    removedResult = removedResult or UPDATE_PERIOD_QUEUE_ALTERED_READING_PERIOD
                    removedResult = removedResult or UPDATE_PERIOD_QUEUE_ALTERED_PREWARMING_PERIOD
                }
            }
            for (i in prewarmingPeriods.indices) {
                if (mediaPeriodHolder == prewarmingPeriods[i]) {
                    prewarmingPeriods[i] = readingPeriods[i]
                    removedResult = removedResult or UPDATE_PERIOD_QUEUE_ALTERED_PREWARMING_PERIOD
                }
            }
            mediaPeriodHolder.release()
            length--
        }
        this.loadingPeriod!!.setNext(null)
        notifyQueueUpdate()
        return removedResult
    }

    fun maybeUpdatePreloadMediaPeriodHolder() {
        if (this.preloadingPeriod != null && !preloadingPeriod!!.isFullyPreloaded) {
            return
        }
        this.preloadingPeriod = null
        for (i in preloadPriorityList.indices) {
            val mediaPeriodHolder = preloadPriorityList[i]
            if (!mediaPeriodHolder.isFullyPreloaded) {
                this.preloadingPeriod = mediaPeriodHolder
                break
            }
        }
    }

    fun getPreloadHolderByMediaPeriod(mediaPeriod: MediaPeriod?): MediaPeriodHolder? {
        for (i in preloadPriorityList.indices) {
            val mediaPeriodHolder = preloadPriorityList[i]
            if (mediaPeriodHolder.mediaPeriod === mediaPeriod) {
                return mediaPeriodHolder
            }
        }
        return null
    }

    fun clear() {
        if (length == 0) {
            return
        }
        var front = this.playingPeriod
        oldFrontPeriodUid = front!!.uid
        oldFrontPeriodWindowSequenceNumber = front.info.id.windowSequenceNumber
        while (front != null) {
            front.release()
            front = front.getNext()
        }
        this.playingPeriod = null
        this.loadingPeriod = null
        Arrays.fill(readingPeriods, null)
        Arrays.fill(prewarmingPeriods, null)
        length = 0
        notifyQueueUpdate()
    }

    fun updateQueuedPeriods(
        timeline: Timeline,
        rendererPositionUs: Long,
        rendererReadingPositionsUs: LongArray,
        rendererPrewarmingPositionsUs: LongArray
    ): @UpdatePeriodQueueResult Int {
        // TODO: Merge this into setTimeline so that the queue gets updated as soon as the new timeline
        // is set, once all cases handled by ExoPlayerImplInternal.handleMediaSourceListInfoRefreshed
        // can be handled here.
        var previousPeriodHolder: MediaPeriodHolder? = null
        var periodHolder = this.playingPeriod
        while (periodHolder != null) {
            val oldPeriodInfo = periodHolder.info

            // Get period info based on new timeline.
            var newPeriodInfo: MediaPeriodInfo?
            if (previousPeriodHolder == null) {
                // The id and start position of the first period have already been verified by
                // ExoPlayerImplInternal.handleMediaSourceListInfoRefreshed. Just update duration,
                // isLastInTimeline and isLastInPeriod flags.
                newPeriodInfo = getUpdatedMediaPeriodInfo(timeline, oldPeriodInfo)
            } else {
                newPeriodInfo =
                    getFollowingMediaPeriodInfo(timeline, previousPeriodHolder, rendererPositionUs)
                if (newPeriodInfo == null || !canKeepMediaPeriodHolder(
                        oldPeriodInfo,
                        newPeriodInfo
                    )
                ) {
                    // We've loaded a next media period that is not in the new timeline
                    // or the new media period has a different id or start position.
                    return removeAfter(previousPeriodHolder)
                } else if (oldPeriodInfo.startPositionUs != newPeriodInfo.startPositionUs) {
                    // If we can keep it, but the calculated start position changed, update back to the
                    // previous value to ensure it matches the actual data being loaded.
                    newPeriodInfo =
                        newPeriodInfo.copyWithStartPositionUs(
                            oldPeriodInfo.startPositionUs,
                            oldPeriodInfo.liveStreamStartPositionProjectionUs
                        )
                }
            }

            // Use the new period info, but keep the old requested content position to avoid overriding it
            // by the default content position generated in getFollowingMediaPeriodInfo.
            periodHolder.info =
                newPeriodInfo.copyWithRequestedContentPositionUs(
                    oldPeriodInfo.requestedContentPositionUs
                )

            if (oldPeriodInfo.durationUs != newPeriodInfo.durationUs) {
                // Check whether we've read beyond the new duration.
                val newDurationInRendererTime =
                    if (newPeriodInfo.durationUs == C.TIME_UNSET) Long.MAX_VALUE else
                        periodHolder.toRendererTime(newPeriodInfo.durationUs)
                // Determine if any stream has over-read the boundary. Seamless transitions are exempt.
                val isReadingAndReadBeyondNewDuration =
                    !periodHolder.info.isFollowedByTransitionToSameStream
                            && hasAnyRendererReadBeyondDuration(
                        readingPeriods,
                        rendererReadingPositionsUs,
                        periodHolder,
                        newDurationInRendererTime
                    )

                val isPrewarmingAndReadBeyondNewDuration =
                    hasAnyRendererReadBeyondDuration(
                        prewarmingPeriods,
                        rendererPrewarmingPositionsUs,
                        periodHolder,
                        newDurationInRendererTime
                    )
                // Remove all subsequent periods.
                val removeAfterResult: @UpdatePeriodQueueResult Int = removeAfter(periodHolder)
                if (removeAfterResult != 0) {
                    return removeAfterResult
                }
                var result = 0
                if (isReadingAndReadBeyondNewDuration
                    && (oldPeriodInfo.durationUs != C.TIME_UNSET
                            || oldPeriodInfo.id.nextAdGroupIndex != C.INDEX_UNSET)
                ) {
                    // Duration changes from unset to set are usually acceptable, unless they involve a
                    // post-roll ad whose position was previously unknown (e.g., at the end of a live stream).
                    result = result or UPDATE_PERIOD_QUEUE_ALTERED_READING_PERIOD
                }
                if (isPrewarmingAndReadBeyondNewDuration) {
                    result = result or UPDATE_PERIOD_QUEUE_ALTERED_PREWARMING_PERIOD
                }
                return result
            }

            previousPeriodHolder = periodHolder
            periodHolder = periodHolder.getNext()
        }
        return 0
    }

    private fun hasAnyRendererReadBeyondDuration(
        rendererPeriods: Array<MediaPeriodHolder?>,
        positionsUs: LongArray,
        periodHolder: MediaPeriodHolder,
        durationInRendererTime: Long
    ): Boolean {
        for (i in rendererPeriods.indices) {
            val rendererPeriod = rendererPeriods[i]
            if (rendererPeriod == null) {
                continue
            }
            // A renderer is "beyond" if it moved to a later period or reached the boundary of this one.
            if (isPeriodAfter(rendererPeriod, periodHolder)
                || (rendererPeriod == periodHolder
                        && (positionsUs[i] == C.TIME_END_OF_SOURCE
                        || positionsUs[i] >= durationInRendererTime))
            ) {
                return true
            }
        }
        return false
    }

    private fun isPeriodAfter(current: MediaPeriodHolder?, target: MediaPeriodHolder): Boolean {
        var next = target.getNext()
        while (next != null) {
            if (next == current) {
                return true
            }
            next = next.getNext()
        }
        return false
    }

    fun getUpdatedMediaPeriodInfo(timeline: Timeline, info: MediaPeriodInfo): MediaPeriodInfo {
        val id = info.id
        val isLastInPeriod = isLastInPeriod(id)
        val isLastInWindow = isLastInWindow(timeline, id)
        val isLastInTimeline = isLastInTimeline(timeline, id, isLastInPeriod)
        val durationUs = getMediaPeriodDuration(timeline, info.id)
        timeline.getPeriodByUid(info.id.periodUid, period)
        val isFollowedByTransitionToSameStream =
            if (id.isAd)
                period.isServerSideInsertedAdGroup(id.adGroupIndex)
            else
                (id.nextAdGroupIndex != C.INDEX_UNSET && period.isServerSideInsertedAdGroup(id.nextAdGroupIndex)
                        && !period.isLivePostrollPlaceholder(id.nextAdGroupIndex))
        return MediaPeriodInfo(
            id,
            info.startPositionUs,
            info.liveStreamStartPositionProjectionUs,
            info.requestedContentPositionUs,
            durationUs,
            isFollowedByTransitionToSameStream,
            isLastInPeriod,
            isLastInWindow,  
            isLastInTimeline
        )
    }

    fun resolveMediaPeriodIdForAds(
        timeline: Timeline, periodUid: Any, positionUs: Long
    ): MediaPeriodId {
        val windowSequenceNumber = resolvePeriodUidToWindowSequenceNumber(timeline, periodUid)
        return resolveMediaPeriodIdForAds(
            timeline, periodUid, positionUs, windowSequenceNumber, window, period
        )
    }

    fun resolveMediaPeriodIdForAdsAfterPeriodPositionChange(
        playbackInfo: PlaybackInfo,
        timeline: Timeline,
        periodUid: Any,
        positionUs: Long,
        enforceAdPlayback: Boolean,
        transitionsFromPlaceholderPeriod: Boolean
    ): MediaPeriodId {
        val windowSequenceNumber = resolvePeriodUidToWindowSequenceNumber(timeline, periodUid)
        var periodUidToPlay: Any? = periodUid
        if (!enforceAdPlayback && !transitionsFromPlaceholderPeriod) {
            val oldPeriodId = playbackInfo.periodId
            // Ad playback not enforced. Check whether we are still playing on the same ad.
            val resolvedPeriodId: MediaPeriodId =
                Companion.resolveMediaPeriodIdForAds(
                    timeline, periodUidToPlay!!, positionUs, windowSequenceNumber, window, period
                )
            if (oldPeriodId.isAd && oldPeriodId.equals(resolvedPeriodId)) {
                // If we are still on the same ad and the ad is unavailable or available, we keep the old
                // period ID to continue playing the ad.
                return oldPeriodId
            }
            // If not enforced, we only make sure windowSequenceNumber and nextAdGroupIndex are correct.
            timeline.getPeriodByUid(periodUid, period)
            val nextAdGroupIndex = period.getAdGroupIndexAfterPositionUs(positionUs)
            return MediaPeriodId(periodUid, windowSequenceNumber, nextAdGroupIndex)
        }
        // Check for preceding ad periods in multi-period window.
        timeline.getPeriodByUid(periodUid, period)
        timeline.getWindow(period.windowIndex, window)
        var seenAdPeriod = false
        for (i in timeline.getIndexOfPeriod(periodUid) downTo window.firstPeriodIndex) {
            timeline.getPeriod( i, period,  true)
            val isAdPeriod = period.adGroupCount > 0
            seenAdPeriod = seenAdPeriod or isAdPeriod
            if (period.getAdGroupIndexForPositionUs(period.durationUs) != C.INDEX_UNSET) {
                // Roll forward to preceding un-played ad period.
                periodUidToPlay = period.uid!!
            }
            if (seenAdPeriod && (!isAdPeriod || period.durationUs != 0L)) {
                // Stop for any periods except un-played ads with no content.
                break
            }
        }
        // Find ad at or before the position.
        val mediaPeriodId: MediaPeriodId =
            Companion.resolveMediaPeriodIdForAds(
                timeline, periodUidToPlay!!, positionUs, windowSequenceNumber, window, period
            )
        if (mediaPeriodId.adGroupIndex != C.INDEX_UNSET && !enforceAdPlayback) {
            // If not enforced and transitioning from the placeholder, an exact match of the position to
            // the ad group start is still played (preroll or an ad at startPositionUs > 0).
            timeline.getPeriodByUid(mediaPeriodId.periodUid, period)
            if (period.adPlaybackState.getAdGroup(mediaPeriodId.adGroupIndex).timeUs != positionUs) {
                val nextAdGroupIndex = period.getAdGroupIndexAfterPositionUs(positionUs)
                return MediaPeriodId(
                    mediaPeriodId.periodUid, mediaPeriodId.windowSequenceNumber, nextAdGroupIndex
                )
            }
        }
        return mediaPeriodId
    }

    // Internal methods.
    private fun notifyQueueUpdate() {
        val builder = mutableListOf<MediaPeriodId>()
        var period = this.playingPeriod
        while (period != null) {
            builder.add(period.info.id)
            period = period.getNext()
        }
        // TODO: Update analytics post to provide correct info on current reading period state
        val readingPeriodId =
            if (readingPeriods.size == 0 || readingPeriods[0] == null)
                null
            else
                readingPeriods[0]!!.info.id
        analyticsCollectorHandler.post(
            Runnable {
                analyticsCollector.updateMediaPeriodQueueInfo(
                    builder.toList(),
                    readingPeriodId
                )
            })
    }

    private fun resolvePeriodUidToWindowSequenceNumber(timeline: Timeline, periodUid: Any): Long {
        val windowIndex = timeline.getPeriodByUid(periodUid, period).windowIndex
        if (oldFrontPeriodUid != null) {
            val oldFrontPeriodIndex = timeline.getIndexOfPeriod(oldFrontPeriodUid!!)
            if (oldFrontPeriodIndex != C.INDEX_UNSET) {
                val oldFrontWindowIndex =
                    timeline.getPeriod(oldFrontPeriodIndex, period).windowIndex
                if (oldFrontWindowIndex == windowIndex) {
                    // Try to match old front uid after the queue has been cleared.
                    return oldFrontPeriodWindowSequenceNumber
                }
            }
        }
        var mediaPeriodHolder = this.playingPeriod
        while (mediaPeriodHolder != null) {
            if (mediaPeriodHolder.uid == periodUid) {
                // Reuse window sequence number of first exact period match.
                return mediaPeriodHolder.info.id.windowSequenceNumber
            }
            mediaPeriodHolder = mediaPeriodHolder.getNext()
        }
        mediaPeriodHolder = this.playingPeriod
        while (mediaPeriodHolder != null) {
            val indexOfHolderInTimeline = timeline.getIndexOfPeriod(mediaPeriodHolder.uid)
            if (indexOfHolderInTimeline != C.INDEX_UNSET) {
                val holderWindowIndex =
                    timeline.getPeriod(indexOfHolderInTimeline, period).windowIndex
                if (holderWindowIndex == windowIndex) {
                    // As an alternative, try to match other periods of the same window.
                    return mediaPeriodHolder.info.id.windowSequenceNumber
                }
            }
            mediaPeriodHolder = mediaPeriodHolder.getNext()
        }

        var windowSequenceNumber = resolvePeriodUidToWindowSequenceNumberInPreloadPeriods(periodUid)
        if (windowSequenceNumber != C.INDEX_UNSET.toLong()) {
            return windowSequenceNumber
        }

        // If no match is found, create new sequence number.
        windowSequenceNumber = nextWindowSequenceNumber++
        if (this.playingPeriod == null) {
            // If the queue is empty, save it as old front uid to allow later reuse.
            oldFrontPeriodUid = periodUid
            oldFrontPeriodWindowSequenceNumber = windowSequenceNumber
        }
        return windowSequenceNumber
    }

    private fun resolvePeriodUidToWindowSequenceNumberInPreloadPeriods(periodUid: Any?): Long {
        for (i in preloadPriorityList.indices) {
            val preloadHolder = preloadPriorityList[i]
            if (preloadHolder.uid == periodUid) {
                // Found a match in the preload periods.
                return preloadHolder.info.id.windowSequenceNumber
            }
        }
        return C.INDEX_UNSET.toLong()
    }

    private fun canKeepMediaPeriodHolder(
        oldInfo: MediaPeriodInfo,
        newInfo: MediaPeriodInfo
    ): Boolean {
        if (!oldInfo.id.equals(newInfo.id)) {
            return false
        }
        if (oldInfo.startPositionUs == newInfo.startPositionUs) {
            return true
        }
        // The start position changed. If this is caused by an acceptable difference from the forward
        // projection into live streams, keep the old period.
        if (oldInfo.liveStreamStartPositionProjectionUs == C.TIME_UNSET
            || newInfo.liveStreamStartPositionProjectionUs == C.TIME_UNSET
        ) {
            return false
        }
        val oldEffectiveStartPositionUs =
            oldInfo.startPositionUs - oldInfo.liveStreamStartPositionProjectionUs
        val newEffectiveStartPositionUs =
            newInfo.startPositionUs - newInfo.liveStreamStartPositionProjectionUs
        return (abs(newEffectiveStartPositionUs - oldEffectiveStartPositionUs)
                < MAX_EFFECTIVE_START_POSITION_DIFF_WITH_PROJECTION_US)
    }

    private fun updateForPlaybackModeChange(timeline: Timeline): Int {
        // Find the last existing period holder that matches the new period order.
        var lastValidPeriodHolder = this.playingPeriod
        if (lastValidPeriodHolder == null) {
            return 0
        }
        var currentPeriodIndex = timeline.getIndexOfPeriod(lastValidPeriodHolder.uid)
        while (true) {
            val nextPeriodIndex =
                timeline.getNextPeriodIndex(
                    currentPeriodIndex, period, window, repeatMode, shuffleModeEnabled
                )
            while (lastValidPeriodHolder!!.getNext() != null
                && !lastValidPeriodHolder!!.info.isLastInTimelinePeriod
            ) {
                lastValidPeriodHolder = lastValidPeriodHolder.getNext()
            }

            val nextMediaPeriodHolder = lastValidPeriodHolder!!.getNext()
            if (nextPeriodIndex == C.INDEX_UNSET || nextMediaPeriodHolder == null) {
                break
            }
            val nextPeriodHolderPeriodIndex = timeline.getIndexOfPeriod(nextMediaPeriodHolder.uid)
            if (nextPeriodHolderPeriodIndex != nextPeriodIndex) {
                break
            }
            lastValidPeriodHolder = nextMediaPeriodHolder
            currentPeriodIndex = nextPeriodIndex
        }

        // Release any period holders that don't match the new period order.
        val removeAfterResult: @UpdatePeriodQueueResult Int = removeAfter(lastValidPeriodHolder)

        // Update the period info for the last holder, as it may now be the last period in the timeline.
        lastValidPeriodHolder.info =
            getUpdatedMediaPeriodInfo(timeline, lastValidPeriodHolder.info)
        // If renderers may have read from a period that's been removed, it is necessary to restart.
        return removeAfterResult
    }

    private fun getFirstMediaPeriodInfo(playbackInfo: PlaybackInfo): MediaPeriodInfo {
        return getMediaPeriodInfo(
            playbackInfo.timeline,
            playbackInfo.periodId,
            playbackInfo.requestedContentPositionUs,  
            playbackInfo.positionUs,  
            C.TIME_UNSET
        )
    }

    private fun getFollowingMediaPeriodInfo(
        timeline: Timeline, mediaPeriodHolder: MediaPeriodHolder, rendererPositionUs: Long
    ): MediaPeriodInfo? {
        // TODO: This method is called repeatedly from ExoPlayerImplInternal.maybeUpdateLoadingPeriod
        // but if the timeline is not ready to provide the next period it can't return a non-null value
        // until the timeline is updated. Store whether the next timeline period is ready when the
        // timeline is updated, to avoid repeatedly checking the same timeline.
        val mediaPeriodInfo = mediaPeriodHolder.info
        // The expected delay until playback transitions to the new period is equal the duration of
        // media that's currently buffered (assuming no interruptions). This is used to project forward
        // the start position for transitions to new windows.
        val bufferedDurationUs =
            mediaPeriodHolder.rendererOffset + mediaPeriodInfo.durationUs - rendererPositionUs
        return if (mediaPeriodInfo.isLastInTimelinePeriod)
            getFirstMediaPeriodInfoOfNextPeriod(timeline, mediaPeriodHolder, bufferedDurationUs)
        else
            getFollowingMediaPeriodInfoOfCurrentPeriod(
                timeline, mediaPeriodHolder, bufferedDurationUs
            )
    }

    private fun getFirstMediaPeriodInfoOfNextPeriod(
        timeline: Timeline, mediaPeriodHolder: MediaPeriodHolder, bufferedDurationUs: Long
    ): MediaPeriodInfo? {
        val mediaPeriodInfo = mediaPeriodHolder.info
        val currentPeriodIndex = timeline.getIndexOfPeriod(mediaPeriodInfo.id.periodUid)
        val nextPeriodIndex =
            timeline.getNextPeriodIndex(
                currentPeriodIndex, period, window, repeatMode, shuffleModeEnabled
            )
        if (nextPeriodIndex == C.INDEX_UNSET) {
            // We can't create a next period yet.
            return null
        }
        var startPositionUs: Long = 0
        var liveStreamStartPositionProjectionUs = C.TIME_UNSET
        var contentPositionUs: Long = 0
        val nextWindowIndex =
            timeline.getPeriod(nextPeriodIndex, period,  true).windowIndex
        var nextPeriodUid = period.uid!!
        var windowSequenceNumber = mediaPeriodInfo.id.windowSequenceNumber
        if (timeline.getWindow(nextWindowIndex, window).firstPeriodIndex == nextPeriodIndex) {
            // We're starting to buffer a new window.
            contentPositionUs = C.TIME_UNSET
            if (shouldUseLiveStartPositionProjection(
                    timeline, period.windowIndex, period.durationUs, window
                )
            ) {
                // When playback transitions to this window we'll want it to be from its default start
                // position, so project the default start position forward by the duration of the buffer,
                // and start buffering from this point.
                liveStreamStartPositionProjectionUs = max(0, bufferedDurationUs)
            }
            val defaultPositionUs: Pair<Any, Long>? =
                timeline.getPeriodPositionUs(
                    window,
                    period,
                    nextWindowIndex,  
                    C.TIME_UNSET,
                    liveStreamStartPositionProjectionUs
                )
            if (defaultPositionUs == null) {
                return null
            }
            nextPeriodUid = defaultPositionUs.first
            startPositionUs = defaultPositionUs.second!!
            val nextMediaPeriodHolder = mediaPeriodHolder.getNext()
            if (nextMediaPeriodHolder != null && nextMediaPeriodHolder.uid == nextPeriodUid) {
                windowSequenceNumber = nextMediaPeriodHolder.info.id.windowSequenceNumber
            } else {
                val windowSequenceNumberFromPreload =
                    resolvePeriodUidToWindowSequenceNumberInPreloadPeriods(nextPeriodUid)
                windowSequenceNumber =
                    if (windowSequenceNumberFromPreload == C.INDEX_UNSET.toLong())
                        nextWindowSequenceNumber++
                    else
                        windowSequenceNumberFromPreload
            }
        }

        val periodId: MediaPeriodId? =
            Companion.resolveMediaPeriodIdForAds(
                timeline, nextPeriodUid, startPositionUs, windowSequenceNumber, window, period
            )
        if (contentPositionUs != C.TIME_UNSET
            && mediaPeriodInfo.requestedContentPositionUs != C.TIME_UNSET
        ) {
            val precedingPeriodHasServerSideInsertedAds =
                hasServerSideInsertedAds(mediaPeriodInfo.id.periodUid, timeline)
            // Handle the requested content position for period transitions within the same window.
            if (periodId!!.isAd && precedingPeriodHasServerSideInsertedAds) {
                // Propagate the requested position to the following ad period in the same window.
                contentPositionUs = mediaPeriodInfo.requestedContentPositionUs
            } else if (precedingPeriodHasServerSideInsertedAds) {
                // Use the requested content position of the preceding ad period as the start position.
                startPositionUs = mediaPeriodInfo.requestedContentPositionUs
            }
        }
        return getMediaPeriodInfo(
            timeline,
            periodId!!,
            contentPositionUs,
            startPositionUs,
            liveStreamStartPositionProjectionUs
        )
    }

    private fun getFollowingMediaPeriodInfoOfCurrentPeriod(
        timeline: Timeline, mediaPeriodHolder: MediaPeriodHolder, bufferedDurationUs: Long
    ): MediaPeriodInfo? {
        val mediaPeriodInfo = mediaPeriodHolder.info
        val currentPeriodId = mediaPeriodInfo.id
        timeline.getPeriodByUid(currentPeriodId.periodUid, period)
        if (currentPeriodId.isAd) {
            val adGroupIndex = currentPeriodId.adGroupIndex
            val adCountInCurrentAdGroup = period.getAdCountInAdGroup(adGroupIndex)
            if (adCountInCurrentAdGroup == C.LENGTH_UNSET) {
                return null
            }
            val nextAdIndexInAdGroup =
                period.getNextAdIndexToPlay(adGroupIndex, currentPeriodId.adIndexInAdGroup)
            if (nextAdIndexInAdGroup < adCountInCurrentAdGroup) {
                // Play the next ad in the ad group if it's available.
                return getMediaPeriodInfoForAd(
                    timeline,
                    currentPeriodId.periodUid,
                    adGroupIndex,
                    nextAdIndexInAdGroup,
                    mediaPeriodInfo.requestedContentPositionUs,
                    currentPeriodId.windowSequenceNumber
                )
            } else {
                // Play content from the ad group position.
                var startPositionUs = mediaPeriodInfo.requestedContentPositionUs
                var liveStreamStartPositionProjectionUs = C.TIME_UNSET
                if (startPositionUs == C.TIME_UNSET) {
                    if (shouldUseLiveStartPositionProjection(
                            timeline, period.windowIndex, period.durationUs, window
                        )
                    ) {
                        // If we're transitioning from an ad group to content starting from its default
                        // position, project the start position forward as if transitioning to a new window.
                        liveStreamStartPositionProjectionUs = max(0, bufferedDurationUs)
                    }
                    val defaultPositionUs: Pair<Any, Long>? =
                        timeline.getPeriodPositionUs(
                            window,
                            period,
                            period.windowIndex,  
                            C.TIME_UNSET,
                            liveStreamStartPositionProjectionUs
                        )
                    if (defaultPositionUs == null) {
                        return null
                    }
                    startPositionUs = defaultPositionUs.second!!
                }
                val minStartPositionUs =
                    getMinStartPositionAfterAdGroupUs(
                        timeline, currentPeriodId.periodUid, currentPeriodId.adGroupIndex
                    )
                return getMediaPeriodInfoForContent(
                    timeline,
                    currentPeriodId.periodUid,
                    max(minStartPositionUs, startPositionUs),
                    liveStreamStartPositionProjectionUs,
                    mediaPeriodInfo.requestedContentPositionUs,
                    currentPeriodId.windowSequenceNumber
                )
            }
        } else if (currentPeriodId.nextAdGroupIndex != C.INDEX_UNSET
            && period.isLivePostrollPlaceholder(currentPeriodId.nextAdGroupIndex)
        ) {
            // The next ad group is the postroll placeholder. Ignore and try the next timeline period.
            return getFirstMediaPeriodInfoOfNextPeriod(
                timeline,
                mediaPeriodHolder,
                bufferedDurationUs
            )
        } else {
            // Play the next ad group if it's still available.
            val adIndexInAdGroup = period.getFirstAdIndexToPlay(currentPeriodId.nextAdGroupIndex)
            val isPlayedServerSideInsertedAd =
                period.isServerSideInsertedAdGroup(currentPeriodId.nextAdGroupIndex)
                        && (period.getAdState(currentPeriodId.nextAdGroupIndex, adIndexInAdGroup)
                        == AdPlaybackState.AD_STATE_PLAYED)
            if (adIndexInAdGroup == period.getAdCountInAdGroup(currentPeriodId.nextAdGroupIndex)
                || isPlayedServerSideInsertedAd
            ) {
                // The next ad group has no ads left to play or is a played SSAI ad group. Play content from
                // the end position instead.
                val startPositionUs =
                    getMinStartPositionAfterAdGroupUs(
                        timeline, currentPeriodId.periodUid, currentPeriodId.nextAdGroupIndex
                    )
                return getMediaPeriodInfoForContent(
                    timeline,
                    currentPeriodId.periodUid,
                    startPositionUs,  
                    C.TIME_UNSET,  
                    mediaPeriodInfo.durationUs,
                    currentPeriodId.windowSequenceNumber
                )
            }
            return getMediaPeriodInfoForAd(
                timeline,
                currentPeriodId.periodUid,  
                currentPeriodId.nextAdGroupIndex,
                adIndexInAdGroup,  
                mediaPeriodInfo.durationUs,
                currentPeriodId.windowSequenceNumber
            )
        }
    }

    private fun hasServerSideInsertedAds(periodUid: Any, timeline: Timeline): Boolean {
        val adGroupCount = timeline.getPeriodByUid(periodUid, period).adGroupCount
        val firstAdGroupIndex = period.removedAdGroupCount
        return adGroupCount > 0 && period.isServerSideInsertedAdGroup(firstAdGroupIndex)
                && (adGroupCount > 1 || period.getAdGroupTimeUs(firstAdGroupIndex) != C.TIME_END_OF_SOURCE)
    }

    private fun getMediaPeriodInfo(
        timeline: Timeline,
        id: MediaPeriodId,
        requestedContentPositionUs: Long,
        startPositionUs: Long,
        liveStreamStartPositionProjectionUs: Long
    ): MediaPeriodInfo {
        timeline.getPeriodByUid(id.periodUid, period)
        if (id.isAd) {
            return getMediaPeriodInfoForAd(
                timeline,
                id.periodUid,
                id.adGroupIndex,
                id.adIndexInAdGroup,
                requestedContentPositionUs,
                id.windowSequenceNumber
            )
        } else {
            return getMediaPeriodInfoForContent(
                timeline,
                id.periodUid,
                startPositionUs,
                liveStreamStartPositionProjectionUs,
                requestedContentPositionUs,
                id.windowSequenceNumber
            )
        }
    }

    private fun getMediaPeriodInfoForAd(
        timeline: Timeline,
        periodUid: Any,
        adGroupIndex: Int,
        adIndexInAdGroup: Int,
        contentPositionUs: Long,
        windowSequenceNumber: Long
    ): MediaPeriodInfo {
        val id =
            MediaPeriodId(periodUid, adGroupIndex, adIndexInAdGroup, windowSequenceNumber)
        val durationUs =
            timeline
                .getPeriodByUid(id.periodUid, period)
                .getAdDurationUs(id.adGroupIndex, id.adIndexInAdGroup)
        var startPositionUs =
            if (adIndexInAdGroup == period.getFirstAdIndexToPlay(adGroupIndex))
                period.adResumePositionUs
            else
                0
        val isFollowedByTransitionToSameStream =
            period.isServerSideInsertedAdGroup(id.adGroupIndex)
        if (durationUs != C.TIME_UNSET && startPositionUs >= durationUs) {
            // Ensure start position doesn't exceed duration.
            startPositionUs = max(0, durationUs - 1)
        }
        return MediaPeriodInfo(
            id,
            startPositionUs,  
            C.TIME_UNSET,  
            contentPositionUs,
            durationUs,
            isFollowedByTransitionToSameStream,  
            false,  
            false,  
            false
        )
    }

    private fun getMediaPeriodInfoForContent(
        timeline: Timeline,
        periodUid: Any,
        startPositionUs: Long,
        liveStreamStartPositionProjectionUs: Long,
        requestedContentPositionUs: Long,
        windowSequenceNumber: Long
    ): MediaPeriodInfo {
        var startPositionUs = startPositionUs
        timeline.getPeriodByUid(periodUid, period)
        var nextAdGroupIndex = period.getAdGroupIndexAfterPositionUs(startPositionUs)
        if (nextAdGroupIndex != C.INDEX_UNSET && period.isServerSideInsertedAdGroup(nextAdGroupIndex)
            && period.getAdGroupTimeUs(nextAdGroupIndex) == period.durationUs && period.hasPlayedAdGroup(
                nextAdGroupIndex
            )
        ) {
            // Ignore SSAI post-rolls if already played.
            nextAdGroupIndex = C.INDEX_UNSET
        }
        val isFollowedByTransitionToSameStream =
            nextAdGroupIndex != C.INDEX_UNSET && period.isServerSideInsertedAdGroup(nextAdGroupIndex)
                    && !period.isLivePostrollPlaceholder(nextAdGroupIndex)
        val id = MediaPeriodId(periodUid, windowSequenceNumber, nextAdGroupIndex)
        val isLastInPeriod = isLastInPeriod(id)
        val isLastInWindow = isLastInWindow(timeline, id)
        val isLastInTimeline = isLastInTimeline(timeline, id, isLastInPeriod)
        val durationUs = getMediaPeriodDuration(timeline, id)
        if (durationUs != C.TIME_UNSET && startPositionUs >= durationUs) {
            // Ensure start position doesn't exceed duration.
            startPositionUs = max(0, durationUs - 1)
        }
        return MediaPeriodInfo(
            id,
            startPositionUs,
            liveStreamStartPositionProjectionUs,
            requestedContentPositionUs,
            durationUs,
            isFollowedByTransitionToSameStream,
            isLastInPeriod,
            isLastInWindow,  
            isLastInTimeline
        )
    }

    private fun isLastInPeriod(id: MediaPeriodId): Boolean {
        return !id.isAd && id.nextAdGroupIndex == C.INDEX_UNSET
    }

    private fun isLastInWindow(timeline: Timeline, id: MediaPeriodId): Boolean {
        if (!isLastInPeriod(id)) {
            return false
        }
        val windowIndex = timeline.getPeriodByUid(id.periodUid, period).windowIndex
        val periodIndex = timeline.getIndexOfPeriod(id.periodUid)
        return timeline.getWindow(windowIndex, window).lastPeriodIndex == periodIndex
    }

    private fun isLastInTimeline(
        timeline: Timeline, id: MediaPeriodId, isLastMediaPeriodInPeriod: Boolean
    ): Boolean {
        val periodIndex = timeline.getIndexOfPeriod(id.periodUid)
        val windowIndex = timeline.getPeriod(periodIndex, period).windowIndex
        return !timeline.getWindow(windowIndex, window).isDynamic && timeline.isLastPeriod(
            periodIndex,
            period,
            window,
            repeatMode,
            shuffleModeEnabled
        )
                && isLastMediaPeriodInPeriod
    }

    private fun getMediaPeriodDuration(timeline: Timeline, id: MediaPeriodId): Long {
        timeline.getPeriodByUid(id.periodUid, period)
        if (id.isAd) {
            return period.getAdDurationUs(id.adGroupIndex, id.adIndexInAdGroup)
        } else if (id.nextAdGroupIndex != C.INDEX_UNSET) {
            val adGroupTimeUs = period.getAdGroupTimeUs(id.nextAdGroupIndex)
            return if (adGroupTimeUs == C.TIME_END_OF_SOURCE) period.durationUs else adGroupTimeUs
        } else {
            return period.durationUs
        }
    }

    private fun getMinStartPositionAfterAdGroupUs(
        timeline: Timeline, periodUid: Any, adGroupIndex: Int
    ): Long {
        timeline.getPeriodByUid(periodUid, period)
        val startPositionUs = period.getAdGroupTimeUs(adGroupIndex)
        if (startPositionUs == C.TIME_END_OF_SOURCE) {
            return period.durationUs
        }
        return startPositionUs + period.getContentResumeOffsetUs(adGroupIndex)
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        flag = true,
        value = [UPDATE_PERIOD_QUEUE_ALTERED_READING_PERIOD, UPDATE_PERIOD_QUEUE_ALTERED_PREWARMING_PERIOD]
    )
    internal annotation class UpdatePeriodQueueResult

    init {
        period = Timeline.Period()
        window = Timeline.Window()
        preloadPriorityList = ArrayList<MediaPeriodHolder>()
        readingPeriods = arrayOfNulls<MediaPeriodHolder>(rendererCount)
        prewarmingPeriods = arrayOfNulls<MediaPeriodHolder>(rendererCount)
    }

    companion object {
        const val INITIAL_RENDERER_POSITION_OFFSET_US: Long = 1000000000000L
        private const val MAXIMUM_BUFFER_AHEAD_PERIODS = 100
        private val MAX_EFFECTIVE_START_POSITION_DIFF_WITH_PROJECTION_US = 5 * C.MICROS_PER_SECOND

        private fun resolveMediaPeriodIdForAds(
            timeline: Timeline,
            periodUid: Any,
            positionUs: Long,
            windowSequenceNumber: Long,
            window: Timeline.Window,
            period: Timeline.Period
        ): MediaPeriodId {
            var periodUid = periodUid
            timeline.getPeriodByUid(periodUid, period)
            timeline.getWindow(period.windowIndex, window)
            // Skip ignorable server side inserted ad periods.
            var periodIndex = timeline.getIndexOfPeriod(periodUid)
            while (isSkippableAdPeriod(period) && periodIndex <= window.lastPeriodIndex
            ) {
                timeline.getPeriod(periodIndex, period,  true)
                periodUid = period.uid!!
                periodIndex++
            }
            timeline.getPeriodByUid(periodUid, period)
            val adGroupIndex = period.getAdGroupIndexForPositionUs(positionUs)
            if (adGroupIndex == C.INDEX_UNSET) {
                val nextAdGroupIndex = period.getAdGroupIndexAfterPositionUs(positionUs)
                return MediaPeriodId(periodUid, windowSequenceNumber, nextAdGroupIndex)
            } else {
                val adIndexInAdGroup = period.getFirstAdIndexToPlay(adGroupIndex)
                return MediaPeriodId(
                    periodUid,
                    adGroupIndex,
                    adIndexInAdGroup,
                    windowSequenceNumber
                )
            }
        }

        private fun isSkippableAdPeriod(period: Timeline.Period): Boolean {
            val adGroupCount = period.adGroupCount
            if (adGroupCount == 0 || (adGroupCount == 1 && period.isLivePostrollPlaceholder( 
                    0
                ))
                || !period.isServerSideInsertedAdGroup(period.removedAdGroupCount) || period.getAdGroupIndexForPositionUs(
                    0L
                ) != C.INDEX_UNSET
            ) {
                return false
            }
            if (period.durationUs == 0L) {
                return true
            }
            var contentResumeOffsetUs: Long = 0
            val lastIndexInclusive =
                adGroupCount - (if (period.isLivePostrollPlaceholder(adGroupCount - 1)) 2 else 1)
            for (i in 0..lastIndexInclusive) {
                contentResumeOffsetUs += period.getContentResumeOffsetUs( i)
            }
            return period.durationUs <= contentResumeOffsetUs
        }

        fun areDurationsCompatible(previousDurationUs: Long, newDurationUs: Long): Boolean {
            return previousDurationUs == C.TIME_UNSET || previousDurationUs == newDurationUs
        }

        private fun shouldUseLiveStartPositionProjection(
            timeline: Timeline, windowIndex: Int, periodDurationUs: Long, window: Timeline.Window
        ): Boolean {
            if (periodDurationUs != C.TIME_UNSET) {
                return false
            }
            timeline.getWindow(windowIndex, window)
            return window.isDynamic && !window.isPlaceholder
        }

        const val UPDATE_PERIOD_QUEUE_ALTERED_READING_PERIOD: Int = 1
        const val UPDATE_PERIOD_QUEUE_ALTERED_PREWARMING_PERIOD: Int = 1 shl 1
    }
}
