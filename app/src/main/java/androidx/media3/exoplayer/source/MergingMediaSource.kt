package androidx.media3.exoplayer.source

import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline

import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.upstream.Allocator
import com.google.common.base.Preconditions
import com.google.common.collect.Multimap
import com.google.common.collect.MultimapBuilder
import java.io.IOException
import java.util.Arrays
import java.util.Collections
import kotlin.math.min

class MergingMediaSource(
    private val adjustPeriodTimeOffsets: Boolean,
    private val clipDurations: Boolean,
    private val compositeSequenceableLoaderFactory: CompositeSequenceableLoaderFactory,
    private val mediaSources: Array<MediaSource>
) : CompositeMediaSource<Int>() {
    class IllegalMergeException(val reason: @Reason Int) : IOException() {
        @MustBeDocumented
        @Retention(AnnotationRetention.SOURCE)
        @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
        @IntDef(value = [REASON_PERIOD_COUNT_MISMATCH])
        annotation class Reason

        companion object {
            const val REASON_PERIOD_COUNT_MISMATCH: Int = 0
        }
    }

    private val mediaPeriods: MutableList<MutableList<MediaPeriodAndId>>
    private val timelines: Array<Timeline?>
    private val pendingTimelineSources: MutableList<MediaSource> = mediaSources.toMutableList()
    private val clippedDurationsUs: MutableMap<Any, Long> = mutableMapOf()
    private val clippedMediaPeriods: Multimap<Any, ClippingMediaPeriod>
    private var periodCount: Int
    private var periodTimeOffsetsUs: Array<LongArray>
    private var mergeError: IllegalMergeException? = null
    private var enableClippingInMediaPeriod = false

    constructor(mediaSources: Array<MediaSource>) : this(false, mediaSources)

    constructor(adjustPeriodTimeOffsets: Boolean, mediaSources: Array<MediaSource>) :
            this(adjustPeriodTimeOffsets, false, mediaSources)

    constructor(
        adjustPeriodTimeOffsets: Boolean,
        clipDurations: Boolean,
        mediaSources: Array<MediaSource>
    ) : this(
        adjustPeriodTimeOffsets,
        clipDurations,
        DefaultCompositeSequenceableLoaderFactory(),
        mediaSources
    )

    init {
        periodCount = PERIOD_COUNT_UNSET
        this.mediaPeriods = ArrayList(mediaSources.size)
        for (i in mediaSources.indices) {
            mediaPeriods.add(ArrayList())
        }
        timelines = arrayOfNulls(mediaSources.size)
        periodTimeOffsetsUs = arrayOf()
        clippedMediaPeriods =
            MultimapBuilder.hashKeys().arrayListValues().build<Any, ClippingMediaPeriod>()
    }

     // TODO: b/474538573 - Remove once clipping in media period is default.
    fun setEnableClippingInMediaPeriod(enableClippingInMediaPeriod: Boolean) {
        this.enableClippingInMediaPeriod = enableClippingInMediaPeriod
    }

    override val mediaItem: MediaItem
        get() = if (mediaSources.isNotEmpty()) mediaSources[0].mediaItem else PLACEHOLDER_MEDIA_ITEM

    override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean {
        return mediaSources.isNotEmpty() && mediaSources[0].canUpdateMediaItem(mediaItem)
    }

    override fun updateMediaItem(mediaItem: MediaItem) {
        mediaSources[0].updateMediaItem(mediaItem)
    }

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        for (i in mediaSources.indices) {
            prepareChildSource(i, mediaSources[i])
        }
    }

    @Throws(IOException::class)
    override fun maybeThrowSourceInfoRefreshError() {
        mergeError?.also { throw it }
        super.maybeThrowSourceInfoRefreshError()
    }

    override fun createPeriod(
        id: MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod {
        val periodIndex = timelines[0]!!.getIndexOfPeriod(id.periodUid)
        val periods = Array(mediaSources.size) { i ->
            val mediaPeriodId = id.copyWithPeriodUid(timelines[i]!!.getUidOfPeriod(periodIndex))
            mediaSources[i].createPeriod(
                mediaPeriodId,
                allocator,
                startPositionUs - periodTimeOffsetsUs[periodIndex][i]
            ).also {
                mediaPeriods[i].add(MediaPeriodAndId(mediaPeriodId, it))
            }
        }
        var mediaPeriod: MediaPeriod =
            MergingMediaPeriod(
                compositeSequenceableLoaderFactory, periodTimeOffsetsUs[periodIndex], periods
            )
        if (clipDurations) {
            mediaPeriod =
                ClippingMediaPeriod(
                    mediaPeriod,  
                    false,  
                    0,  
                    clippedDurationsUs.get(id.periodUid)!!,
                    enableClippingInMediaPeriod
                )
            clippedMediaPeriods.put(id.periodUid, mediaPeriod)
        }
        return mediaPeriod
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        var mediaPeriod = mediaPeriod
        if (clipDurations) {
            val clippingMediaPeriod = mediaPeriod as ClippingMediaPeriod
            for (entry in clippedMediaPeriods.entries()) {
                if (entry.value == clippingMediaPeriod) {
                    clippedMediaPeriods.remove(entry.key, entry.value)
                    break
                }
            }
            mediaPeriod = clippingMediaPeriod.mediaPeriod
        }
        val mergingPeriod = mediaPeriod as MergingMediaPeriod
        for (i in mediaSources.indices) {
            val mediaPeriodsForSource = mediaPeriods[i]
            val childPeriod = mergingPeriod.getChildPeriod(i)
            for (j in mediaPeriodsForSource.indices) {
                if (mediaPeriodsForSource[j].mediaPeriod == childPeriod) {
                    mediaPeriodsForSource.removeAt(j)
                    break
                }
            }
            mediaSources[i].releasePeriod(mergingPeriod.getChildPeriod(i))
        }
    }

    override fun releaseSourceInternal() {
        super.releaseSourceInternal()
        Arrays.fill(timelines, null)
        periodCount = PERIOD_COUNT_UNSET
        mergeError = null
        pendingTimelineSources.clear()
        Collections.addAll(pendingTimelineSources, *mediaSources)
    }

    override fun onChildSourceInfoRefreshed(
        childSourceId: Int?, mediaSource: MediaSource, newTimeline: Timeline
    ) {
        if (mergeError != null) {
            return
        }
        if (periodCount == PERIOD_COUNT_UNSET) {
            periodCount = newTimeline.periodCount
        } else if (newTimeline.periodCount != periodCount) {
            mergeError = IllegalMergeException(IllegalMergeException.REASON_PERIOD_COUNT_MISMATCH)
            return
        }
        if (periodTimeOffsetsUs.isEmpty()) {
            periodTimeOffsetsUs = Array(periodCount) { LongArray(timelines.size) }
        }
        pendingTimelineSources.remove(mediaSource)
        timelines[childSourceId!!] = newTimeline
        if (pendingTimelineSources.isEmpty()) {
            if (adjustPeriodTimeOffsets) {
                computePeriodTimeOffsets()
            }
            var mergedTimeline = timelines[0]!!
            if (clipDurations) {
                updateClippedDuration()
                mergedTimeline = ClippedTimeline(mergedTimeline, clippedDurationsUs)
            }
            refreshSourceInfo(mergedTimeline)
        }
    }

    override fun getMediaPeriodIdForChildMediaPeriodId(
        childSourceId: Int?, mediaPeriodId: MediaPeriodId
    ): MediaPeriodId? {
        val childMediaPeriodIds = mediaPeriods[childSourceId!!]
        for (i in childMediaPeriodIds.indices) {
            if (childMediaPeriodIds[i].mediaPeriodId == mediaPeriodId) {
                return mediaPeriods[0][i].mediaPeriodId
            }
        }
        return null
    }

    private fun computePeriodTimeOffsets() {
        val period = Timeline.Period()
        for (periodIndex in 0..<periodCount) {
            val primaryWindowOffsetUs =
                -timelines[0]!!.getPeriod(periodIndex, period).positionInWindowUs
            for (timelineIndex in 1..<timelines.size) {
                val secondaryWindowOffsetUs =
                    -timelines[timelineIndex]!!.getPeriod(periodIndex, period).positionInWindowUs
                periodTimeOffsetsUs[periodIndex][timelineIndex] =
                    primaryWindowOffsetUs - secondaryWindowOffsetUs
            }
        }
    }

    private fun updateClippedDuration() {
        val period = Timeline.Period()
        for (periodIndex in 0..<periodCount) {
            var minDurationUs = C.TIME_END_OF_SOURCE
            for (timelineIndex in timelines.indices) {
                val durationUs =
                    timelines[timelineIndex]!!.getPeriod(periodIndex, period).durationUs
                if (durationUs == C.TIME_UNSET) {
                    continue
                }
                val adjustedDurationUs =
                    durationUs + periodTimeOffsetsUs[periodIndex][timelineIndex]
                if (minDurationUs == C.TIME_END_OF_SOURCE || adjustedDurationUs < minDurationUs) {
                    minDurationUs = adjustedDurationUs
                }
            }
            val periodUid = timelines[0]!!.getUidOfPeriod(periodIndex)
            clippedDurationsUs[periodUid] = minDurationUs
            for (clippingMediaPeriod in clippedMediaPeriods.get(periodUid)) {
                clippingMediaPeriod.updateClipping( 0,  minDurationUs)
            }
        }
    }

    private class ClippedTimeline(timeline: Timeline, clippedDurationsUs: MutableMap<Any, Long>) :
        ForwardingTimeline(timeline) {
        private val periodDurationsUs: LongArray
        private val windowDurationsUs: LongArray

        init {
            val windowCount = timeline.windowCount
            windowDurationsUs = LongArray(timeline.windowCount)
            val window = Window()
            for (i in 0..<windowCount) {
                windowDurationsUs[i] = timeline.getWindow(i, window).durationUs
            }
            val periodCount = timeline.periodCount
            periodDurationsUs = LongArray(periodCount)
            val period = Period()
            for (i in 0..<periodCount) {
                timeline.getPeriod(i, period,  true)
                val clippedDurationUs =clippedDurationsUs.get(period.uid)!!
                periodDurationsUs[i] =
                    if (clippedDurationUs != C.TIME_END_OF_SOURCE) clippedDurationUs else period.durationUs
                if (period.durationUs != C.TIME_UNSET) {
                    windowDurationsUs[period.windowIndex] -= period.durationUs - periodDurationsUs[i]
                }
            }
        }

        override fun getWindow(
            windowIndex: Int,
            window: Window,
            defaultPositionProjectionUs: Long
        ): Window {
            super.getWindow(windowIndex, window, defaultPositionProjectionUs)
            window.durationUs = windowDurationsUs[windowIndex]
            window.defaultPositionUs =
                if (window.durationUs == C.TIME_UNSET || window.defaultPositionUs == C.TIME_UNSET)
                    window.defaultPositionUs
                else
                    min(window.defaultPositionUs, window.durationUs)
            return window
        }

        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
            super.getPeriod(periodIndex, period, setIds)
            period.durationUs = periodDurationsUs[periodIndex]
            return period
        }
    }

    private class MediaPeriodAndId(
        val mediaPeriodId: MediaPeriodId,
        val mediaPeriod: MediaPeriod
    )

    companion object {
        private val PERIOD_COUNT_UNSET = -1
        private val PLACEHOLDER_MEDIA_ITEM =
            MediaItem.Builder().setMediaId("MergingMediaSource").build()
    }
}
