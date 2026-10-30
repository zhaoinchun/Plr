package androidx.media3.exoplayer.source

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Message
import android.util.Pair
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.Util
import androidx.media3.common.util.Util.msToUs
import androidx.media3.common.util.Util.usToMs
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.upstream.Allocator
import com.google.common.base.Preconditions
import java.util.IdentityHashMap

class ConcatenatingMediaSource2 private constructor(
    var _mediaItem: MediaItem?,
    private val mediaSourceHolders: List<MediaSourceHolder>
) : CompositeMediaSource<Int>() {
    class Builder {
        private val mediaSourceHoldersBuilder: MutableList<MediaSourceHolder> = mutableListOf()
        private var index = 0
        private var mediaItem: MediaItem? = null
        private var mediaSourceFactory: MediaSource.Factory? = null

        fun useDefaultMediaSourceFactory(context: Context): Builder {
            return setMediaSourceFactory(DefaultMediaSourceFactory(context))
        }

        fun setMediaSourceFactory(mediaSourceFactory: MediaSource.Factory): Builder {
            this.mediaSourceFactory = mediaSourceFactory
            return this
        }

        fun setMediaItem(mediaItem: MediaItem?): Builder {
            this.mediaItem = mediaItem
            return this
        }

        fun add(mediaItem: MediaItem): Builder {
            return add(mediaItem, C.TIME_UNSET)
        }

        fun add(mediaItem: MediaItem, initialPlaceholderDurationMs: Long): Builder {
            var initialPlaceholderDurationMs = initialPlaceholderDurationMs
            if (initialPlaceholderDurationMs == C.TIME_UNSET
                && mediaItem.clippingConfiguration.endPositionMs != C.TIME_END_OF_SOURCE
            ) {
                // If the item is going to be clipped, we can provide a placeholder duration automatically.
                initialPlaceholderDurationMs =
                    usToMs(
                        mediaItem.clippingConfiguration.endPositionUs
                                - mediaItem.clippingConfiguration.startPositionUs
                    )
            }
            Preconditions.checkNotNull<MediaSource.Factory?>(
                mediaSourceFactory,
                "Must use useDefaultMediaSourceFactory or setMediaSourceFactory first."
            )
            return add(
                mediaSourceFactory!!.createMediaSource(mediaItem),
                initialPlaceholderDurationMs
            )
        }

        fun add(mediaSource: MediaSource): Builder {
            return add(mediaSource, C.TIME_UNSET)
        }

        fun add(mediaSource: MediaSource, initialPlaceholderDurationMs: Long): Builder {
            Preconditions.checkState(
                mediaSource !is ProgressiveMediaSource || initialPlaceholderDurationMs != C.TIME_UNSET,
                "Progressive media source must define an initial placeholder duration."
            )
            mediaSourceHoldersBuilder.add(
                MediaSourceHolder(mediaSource, index++, msToUs(initialPlaceholderDurationMs))
            )
            return this
        }

        fun build(): ConcatenatingMediaSource2 {
            Preconditions.checkArgument(
                index > 0,
                "Must add at least one source to the concatenation."
            )
            if (mediaItem == null) {
                mediaItem = MediaItem.fromUri(Uri.EMPTY)
            }
            return ConcatenatingMediaSource2(mediaItem, mediaSourceHoldersBuilder.toList())
        }
    }

    override val mediaItem: MediaItem
        @Synchronized
        get() = _mediaItem!!
    private val mediaSourceByMediaPeriod: IdentityHashMap<MediaPeriod?, MediaSourceHolder?>
    private var playbackThreadHandler: Handler? = null
    private var timelineUpdateScheduled = false

    init {
        mediaSourceByMediaPeriod = IdentityHashMap<MediaPeriod?, MediaSourceHolder?>()
    }

    override val initialTimeline: Timeline?
        get() = maybeCreateConcatenatedTimeline()

    override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean {
        return true
    }

    @Synchronized
    override fun updateMediaItem(mediaItem: MediaItem) {
        this._mediaItem = mediaItem
    }

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        playbackThreadHandler =
            Handler( Handler.Callback { msg: Message? -> this.handleMessage(msg!!) })
        for (i in mediaSourceHolders.indices) {
            val holder = mediaSourceHolders[i]
            prepareChildSource( i, holder.mediaSource)
        }
        scheduleTimelineUpdate()
    }

    @SuppressWarnings("MissingSuperCall")
    override fun enableInternal() {
        // Suppress enabling all child sources here as they can be lazily enabled when creating periods.
    }

    override fun createPeriod(
        id: MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod {
        val holderIndex: Int = getChildIndex(id.periodUid)
        val holder = mediaSourceHolders[holderIndex]
        val childMediaPeriodId =
            id.copyWithPeriodUid(getChildPeriodUid(id.periodUid))
                .copyWithWindowSequenceNumber(
                    getChildWindowSequenceNumber(
                        id.windowSequenceNumber, mediaSourceHolders.size, holder.index
                    )
                )
        enableChildSource(holder.index)
        holder.activeMediaPeriods++
        val timeOffsetUs =
            if (id.isAd)
                0
            else
                holder.periodTimeOffsetsByUid.get(childMediaPeriodId.periodUid)!!
        val mediaPeriod: MediaPeriod =
            TimeOffsetMediaPeriod(
                holder.mediaSource.createPeriod(
                    childMediaPeriodId, allocator, startPositionUs - timeOffsetUs
                ),
                timeOffsetUs
            )
        mediaSourceByMediaPeriod[mediaPeriod] = holder
        disableUnusedMediaSources()
        return mediaPeriod
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        val holder = mediaSourceByMediaPeriod.remove(mediaPeriod)!!
        holder.mediaSource.releasePeriod((mediaPeriod as TimeOffsetMediaPeriod).wrappedMediaPeriod)
        holder.activeMediaPeriods--
        if (!mediaSourceByMediaPeriod.isEmpty()) {
            disableUnusedMediaSources()
        }
    }

    override fun releaseSourceInternal() {
        super.releaseSourceInternal()
        if (playbackThreadHandler != null) {
            playbackThreadHandler!!.removeCallbacksAndMessages(null)
            playbackThreadHandler = null
        }
        timelineUpdateScheduled = false
    }

    override fun onChildSourceInfoRefreshed(
        childSourceId: Int?, mediaSource: MediaSource, newTimeline: Timeline
    ) {
        scheduleTimelineUpdate()
    }

    override fun getMediaPeriodIdForChildMediaPeriodId(
        childSourceId: Int?, mediaPeriodId: MediaPeriodId
    ): MediaPeriodId? {
        val childIndex: Int =
            getChildIndexFromChildWindowSequenceNumber(
                mediaPeriodId.windowSequenceNumber, mediaSourceHolders.size
            )
        if (childSourceId != childIndex) {
            // Ensure the reported media period id has the expected window sequence number. Otherwise it
            // does not belong to this child source.
            return null
        }
        val windowSequenceNumber: Long =
            getWindowSequenceNumberFromChildWindowSequenceNumber(
                mediaPeriodId.windowSequenceNumber, mediaSourceHolders.size
            )
        val periodUid = getPeriodUid(childSourceId, mediaPeriodId.periodUid)
        return mediaPeriodId
            .copyWithPeriodUid(periodUid)
            .copyWithWindowSequenceNumber(windowSequenceNumber)
    }

    override fun getWindowIndexForChildWindowIndex(childSourceId: Int?, windowIndex: Int): Int {
        return 0
    }

    override fun getMediaTimeForChildMediaTime(
        childSourceId: Int?, mediaTimeMs: Long, mediaPeriodId: MediaPeriodId?
    ): Long {
        if (mediaTimeMs == C.TIME_UNSET || mediaPeriodId == null || mediaPeriodId.isAd) {
            return mediaTimeMs
        }
        val timeOffsetUs =
            mediaSourceHolders[childSourceId!!].periodTimeOffsetsByUid[mediaPeriodId.periodUid]
                ?: return mediaTimeMs
        return mediaTimeMs + usToMs(timeOffsetUs)
    }

    private fun handleMessage(msg: Message): Boolean {
        if (msg.what == MSG_UPDATE_TIMELINE) {
            updateTimeline()
        }
        return true
    }

    private fun scheduleTimelineUpdate() {
        if (!timelineUpdateScheduled) {
            playbackThreadHandler!!.obtainMessage(MSG_UPDATE_TIMELINE).sendToTarget()
            timelineUpdateScheduled = true
        }
    }

    private fun updateTimeline() {
        timelineUpdateScheduled = false
        val timeline = maybeCreateConcatenatedTimeline()
        if (timeline != null) {
            refreshSourceInfo(timeline)
        }
    }

    private fun disableUnusedMediaSources() {
        for (i in mediaSourceHolders.indices) {
            val holder = mediaSourceHolders[i]
            if (holder.activeMediaPeriods == 0) {
                disableChildSource(holder.index)
            }
        }
    }

    private fun maybeCreateConcatenatedTimeline(): ConcatenatedTimeline? {
        val window = Timeline.Window()
        val period = Timeline.Period()
        val timelinesBuilder = mutableListOf<Timeline>()
        val firstPeriodIndicesBuilder = mutableListOf<Int>()
        val periodOffsetsInWindowUsBuilder = mutableListOf<Long>()
        var periodCount = 0
        var isSeekable = true
        var isDynamic = false
        var durationUs: Long = 0
        var defaultPositionUs: Long = 0
        var nextPeriodOffsetInWindowUs: Long = 0
        var manifestsAreIdentical = true
        var hasInitialManifest = false
        var initialManifest: Any? = null
        val mediaSourceHoldersCount = mediaSourceHolders.size
        for (i in 0..<mediaSourceHoldersCount) {
            val holder = mediaSourceHolders[i]
            val timeline = holder.mediaSource.getTimeline()
            Preconditions.checkArgument(
                !timeline.isEmpty,
                "Can't concatenate empty child Timeline."
            )
            timelinesBuilder.add(timeline)
            firstPeriodIndicesBuilder.add(periodCount)
            val periodCountInMediaSourceHolder = timeline.periodCount
            periodCount += periodCountInMediaSourceHolder
            for (j in 0..<timeline.windowCount) {
                timeline.getWindow( j, window)
                if (!hasInitialManifest) {
                    initialManifest = window.manifest
                    hasInitialManifest = true
                }
                manifestsAreIdentical =
                    manifestsAreIdentical && initialManifest == window.manifest

                var windowDurationUs = window.durationUs
                if (windowDurationUs == C.TIME_UNSET) {
                    if (holder.initialPlaceholderDurationUs == C.TIME_UNSET) {
                        // Source duration isn't known yet and we have no placeholder duration.
                        return null
                    }
                    windowDurationUs = holder.initialPlaceholderDurationUs
                }
                durationUs += windowDurationUs
                if (holder.index == 0 && j == 0) {
                    defaultPositionUs = window.defaultPositionUs
                    nextPeriodOffsetInWindowUs = -window.positionInFirstPeriodUs
                }
                // Assume placeholder windows are seekable to not prevent seeking in other periods.
                isSeekable = isSeekable and (window.isSeekable || window.isPlaceholder)
                isDynamic = isDynamic or window.isDynamic

                for (k in window.firstPeriodIndex..window.lastPeriodIndex) {
                    periodOffsetsInWindowUsBuilder.add(nextPeriodOffsetInWindowUs)
                    timeline.getPeriod( k, period,  true)
                    var periodDurationUs = period.durationUs
                    if (periodDurationUs == C.TIME_UNSET) {
                        Preconditions.checkArgument(
                            window.firstPeriodIndex == window.lastPeriodIndex,
                            "Can't apply placeholder duration to multiple periods with unknown duration "
                                    + "in a single window."
                        )
                        periodDurationUs = windowDurationUs + window.positionInFirstPeriodUs
                    }
                    var timeOffsetUsForPeriod: Long = 0
                    val isFirstPeriodInNonFirstWindow =
                        k == window.firstPeriodIndex && (holder.index != 0 || j != 0)
                    if (isFirstPeriodInNonFirstWindow && periodDurationUs != C.TIME_UNSET) {
                        timeOffsetUsForPeriod = -window.positionInFirstPeriodUs
                        periodDurationUs += timeOffsetUsForPeriod
                    }
                    val periodUid = period.uid!!
                    Preconditions.checkArgument(
                        holder.activeMediaPeriods == 0 || !holder.periodTimeOffsetsByUid.containsKey(
                            periodUid
                        ) || holder.periodTimeOffsetsByUid[periodUid] == timeOffsetUsForPeriod,
                        "Can't handle windows with changing offset in first period."
                    )
                    holder.periodTimeOffsetsByUid[periodUid] = timeOffsetUsForPeriod
                    nextPeriodOffsetInWindowUs += periodDurationUs
                }
            }
        }

        return ConcatenatingMediaSource2.ConcatenatedTimeline(
            mediaItem,
            timelinesBuilder.toList(),
            firstPeriodIndicesBuilder.toList(),
            periodOffsetsInWindowUsBuilder.toList(),
            isSeekable,
            isDynamic,
            durationUs,
            defaultPositionUs,
            if (manifestsAreIdentical) initialManifest else null
        )
    }

    internal class MediaSourceHolder(
        mediaSource: MediaSource,
        val index: Int,
        val initialPlaceholderDurationUs: Long
    ) {
        val mediaSource: MaskingMediaSource = MaskingMediaSource(mediaSource,  false)
        val periodTimeOffsetsByUid: MutableMap<Any, Long> = HashMap()
        var activeMediaPeriods: Int = 0
    }

    private class ConcatenatedTimeline(
        private val mediaItem: MediaItem,
        private val timelines: List<Timeline>,
        private val firstPeriodIndices: List<Int>,
        private val periodOffsetsInWindowUs: List<Long>,
        private val isSeekable: Boolean,
        private val isDynamic: Boolean,
        private val durationUs: Long,
        private val defaultPositionUs: Long,
        private val manifest: Any?
    ) : Timeline() {
        override val windowCount: Int=1

        override val periodCount: Int
            get() = periodOffsetsInWindowUs.size

        override fun getWindow(
            windowIndex: Int,
            window: Window,
            defaultPositionProjectionUs: Long
        ): Window {
            return window.set(
                Window.SINGLE_WINDOW_UID,
                mediaItem,
                manifest,  
                C.TIME_UNSET,  
                C.TIME_UNSET,  
                C.TIME_UNSET,
                isSeekable,
                isDynamic,  
                null,
                defaultPositionUs,
                durationUs,  
                0,  
                periodCount - 1,  
                -periodOffsetsInWindowUs[0]
            )
        }

        override fun getPeriodByUid(periodUid: Any, period: Period): Period {
            val childIndex: Int = getChildIndex(periodUid)
            val childPeriodUid = getChildPeriodUid(periodUid)
            val timeline = timelines[childIndex]
            val periodIndex =
                firstPeriodIndices[childIndex] + timeline.getIndexOfPeriod(childPeriodUid)
            timeline.getPeriodByUid(childPeriodUid, period)
            period.windowIndex = 0
            period.positionInWindowUs = periodOffsetsInWindowUs[periodIndex]
            period.durationUs = getPeriodDurationUs(period, periodIndex)
            period.uid = periodUid
            return period
        }

        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
            val childIndex = getChildIndexByPeriodIndex(periodIndex)
            val firstPeriodIndexInChild = firstPeriodIndices[childIndex]
            timelines[childIndex].getPeriod(periodIndex - firstPeriodIndexInChild, period, setIds)
            period.windowIndex = 0
            period.positionInWindowUs = periodOffsetsInWindowUs[periodIndex]
            period.durationUs = getPeriodDurationUs(period, periodIndex)
            if (setIds) {
                period.uid = getPeriodUid(childIndex, period.uid!!)
            }
            return period
        }

        override fun getIndexOfPeriod(uid: Any): Int {
            if (uid !is Pair<*, *> || uid.first !is Int) {
                return C.INDEX_UNSET
            }
            val childIndex: Int = getChildIndex(uid)
            val periodUid= getChildPeriodUid(uid)
            val periodIndexInChild = timelines[childIndex].getIndexOfPeriod(periodUid)
            return if (periodIndexInChild == C.INDEX_UNSET)
                C.INDEX_UNSET
            else
                firstPeriodIndices[childIndex] + periodIndexInChild
        }

        override fun getUidOfPeriod(periodIndex: Int): Any {
            val childIndex = getChildIndexByPeriodIndex(periodIndex)
            val firstPeriodIndexInChild = firstPeriodIndices[childIndex]
            val periodUidInChild =
                timelines[childIndex].getUidOfPeriod(periodIndex - firstPeriodIndexInChild)
            return getPeriodUid(childIndex, periodUidInChild)
        }

        fun getChildIndexByPeriodIndex(periodIndex: Int): Int {
            return Util.binarySearchFloor(
                firstPeriodIndices.toIntArray(),
                periodIndex + 1,  
                false,  
                false
            )
        }

        fun getPeriodDurationUs(childPeriod: Period, periodIndex: Int): Long {
            // Keep unset duration, but force duration to match offset of next period otherwise.
            if (childPeriod.durationUs == C.TIME_UNSET) {
                return C.TIME_UNSET
            }
            val periodStartTimeInWindowUs = periodOffsetsInWindowUs.get(periodIndex)!!
            val periodEndTimeInWindowUs =
                (if (periodIndex == periodOffsetsInWindowUs.size - 1)
                    durationUs
                else
                    periodOffsetsInWindowUs.get(periodIndex + 1))!!
            return periodEndTimeInWindowUs - periodStartTimeInWindowUs
        }
    }

    companion object {
        private const val MSG_UPDATE_TIMELINE = 1
        private fun getPeriodUid(childIndex: Int, childPeriodUid: Any): Any {
            return Pair.create<Int, Any>(childIndex, childPeriodUid)
        }

        private fun getChildIndex(periodUid: Any): Int {
            return (periodUid as Pair<Int, Any>).first
        }

        private fun getChildPeriodUid(periodUid: Any): Any {
            return (periodUid as Pair<Int, Any>).second
        }

        private fun getChildWindowSequenceNumber(
            windowSequenceNumber: Long, childCount: Int, childIndex: Int
        ): Long {
            return windowSequenceNumber * childCount + childIndex
        }

        private fun getChildIndexFromChildWindowSequenceNumber(
            childWindowSequenceNumber: Long, childCount: Int
        ): Int {
            return (childWindowSequenceNumber % childCount).toInt()
        }

        private fun getWindowSequenceNumberFromChildWindowSequenceNumber(
            childWindowSequenceNumber: Long, childCount: Int
        ): Long {
            return childWindowSequenceNumber / childCount
        }
    }
}
