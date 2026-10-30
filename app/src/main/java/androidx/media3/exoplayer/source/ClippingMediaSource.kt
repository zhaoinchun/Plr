package androidx.media3.exoplayer.source

import androidx.annotation.IntDef
import androidx.media3.common.AdPlaybackState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.Util.msToUs
import androidx.media3.common.util.Util.usToMs
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.upstream.Allocator
import com.google.common.base.Preconditions
import org.checkerframework.checker.nullness.qual.RequiresNonNull
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

class ClippingMediaSource private constructor(builder: Builder) :
    WrappingMediaSource(builder.mediaSource) {
    class Builder(val mediaSource: MediaSource) {
        var config = MediaItem.ClippingConfiguration.Builder()
        var enableClippingInMediaPeriod = false
        var buildCalled = false

        fun setClippingConfiguration(clippingConfiguration: MediaItem.ClippingConfiguration): Builder {
            Preconditions.checkState(!buildCalled)
            this.config = clippingConfiguration.buildUpon()
            return this
        }

        fun setStartPositionMs(startPositionMs: Long): Builder {
            return setStartPositionUs(msToUs(startPositionMs))
        }

        fun setStartPositionUs(startPositionUs: Long): Builder {
            Preconditions.checkState(!buildCalled)
            config.setStartPositionUs(startPositionUs)
            return this
        }

        fun setEndPositionMs(endPositionMs: Long): Builder {
            return setEndPositionUs(msToUs(endPositionMs))
        }

        fun setEndPositionUs(endPositionUs: Long): Builder {
            Preconditions.checkState(!buildCalled)
            config.setEndPositionUs(endPositionUs)
            return this
        }

        fun setEnableInitialDiscontinuity(enableInitialDiscontinuity: Boolean): Builder {
            Preconditions.checkState(!buildCalled)
            config.setStartsAtKeyFrame(!enableInitialDiscontinuity)
            return this
        }

        fun setAllowDynamicClippingUpdates(allowDynamicClippingUpdates: Boolean): Builder {
            Preconditions.checkState(!buildCalled)
            config.setRelativeToLiveWindow(allowDynamicClippingUpdates)
            return this
        }

        fun setRelativeToDefaultPosition(relativeToDefaultPosition: Boolean): Builder {
            Preconditions.checkState(!buildCalled)
            config.setRelativeToDefaultPosition(relativeToDefaultPosition)
            return this
        }

        fun setAllowUnseekableMedia(allowUnseekableMedia: Boolean): Builder {
            Preconditions.checkState(!buildCalled)
            config.setAllowUnseekableMedia(allowUnseekableMedia)
            return this
        }

        fun setEnableClippingInMediaPeriod(enableClippingInMediaPeriod: Boolean): Builder {
            Preconditions.checkState(!buildCalled)
            this.enableClippingInMediaPeriod = enableClippingInMediaPeriod
            return this
        }

        fun build(): ClippingMediaSource {
            buildCalled = true
            return ClippingMediaSource(this)
        }
    }

    class IllegalClippingException(
        val reason: @Reason Int, startUs: Long = C.TIME_UNSET, endUs: Long = C.TIME_UNSET
    ) : IOException(
        "Illegal clipping: " + getReasonDescription(reason, startUs, endUs)
    ) {
        @MustBeDocumented
        @Retention(AnnotationRetention.SOURCE)
        @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
        @IntDef(value = [REASON_INVALID_PERIOD_COUNT, REASON_NOT_SEEKABLE_TO_START, REASON_START_EXCEEDS_END])
        annotation class Reason

        companion object {
            const val REASON_INVALID_PERIOD_COUNT: Int = 0
            const val REASON_NOT_SEEKABLE_TO_START: Int = 1
            const val REASON_START_EXCEEDS_END: Int = 2

            private fun getReasonDescription(
                reason: @Reason Int,
                startUs: Long,
                endUs: Long
            ): String {
                when (reason) {
                    REASON_INVALID_PERIOD_COUNT -> return "invalid period count"
                    REASON_NOT_SEEKABLE_TO_START -> return "not seekable to start"
                    REASON_START_EXCEEDS_END -> {
                        Preconditions.checkState(startUs != C.TIME_UNSET && endUs != C.TIME_UNSET)
                        return "start exceeds end. Start time: $startUs, End time: $endUs"
                    }

                    else -> return "unknown"
                }
            }
        }
    }

    private val enableClippingInMediaPeriod = builder.enableClippingInMediaPeriod
    private val mediaPeriods = mutableListOf<ClippingMediaPeriod>()
    private val window: Timeline.Window = Timeline.Window()
    private var config: MediaItem.ClippingConfiguration = builder.config.build()
    private var clippingTimeline: ClippingTimeline? = null
    private var clippingError: IllegalClippingException? = null
    private var periodStartUs: Long = 0
    private var periodEndUs: Long = 0

    override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean {
        if (!mediaSource.canUpdateMediaItem(mediaItem)) {
            return false
        }
        return enableClippingInMediaPeriod
                || this.mediaItem.clippingConfiguration == mediaItem.clippingConfiguration
    }

    override fun updateMediaItem(mediaItem: MediaItem) {
        if (enableClippingInMediaPeriod) {
            val oldConfig = config
            config = mediaItem.clippingConfiguration
            if (clippingTimeline != null && shouldKeepWindowFixed()) {
                // If the window is not fixed, refreshClippedTimeline re-calculates the period clipping.
                // If the window is fixed, we need to make adjustments for the new config directly.
                updatePeriodClippingToNewConfig(oldConfig)
            }
        }
        val oldTimeline: Timeline? = clippingTimeline
        // Let the wrapped source update its internal state, which usually triggers a timeline refresh.
        super.updateMediaItem(mediaItem)
        if (enableClippingInMediaPeriod
            && clippingTimeline != null && clippingTimeline === oldTimeline
        ) {
            // If the timeline is unchanged, refresh it manually.
            refreshClippedTimeline(clippingTimeline!!.timeline)
        }
    }

    @Throws(IOException::class)
    override fun maybeThrowSourceInfoRefreshError() {
        clippingError?.also { throw  it }
        super.maybeThrowSourceInfoRefreshError()
    }

    override fun createPeriod(
        id: MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod {
        val mediaPeriod =
            ClippingMediaPeriod(
                mediaSource.createPeriod(
                    id,
                    allocator,
                    startPositionUs
                ),
                !config.startsAtKeyFrame,
                periodStartUs,
                periodEndUs,
                enableClippingInMediaPeriod
            )
        mediaPeriods.add(mediaPeriod)
        return mediaPeriod
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        Preconditions.checkState(mediaPeriods.remove(mediaPeriod))
        mediaSource.releasePeriod((mediaPeriod as ClippingMediaPeriod).mediaPeriod)
        if (mediaPeriods.isEmpty() && !config.relativeToLiveWindow) {
            refreshClippedTimeline(clippingTimeline!!.timeline)
        }
    }

    override fun releaseSourceInternal() {
        super.releaseSourceInternal()
        clippingError = null
        clippingTimeline = null
    }

    override fun onChildSourceInfoRefreshed(newTimeline: Timeline) {
        if (clippingError != null) {
            return
        }
        refreshClippedTimeline(newTimeline)
    }

    private fun refreshClippedTimeline(timeline: Timeline) {
        var windowStartUs: Long
        var windowEndUs: Long
        timeline.getWindow( 0, window)
        val windowPositionInPeriodUs = window.positionInFirstPeriodUs
        if (!shouldKeepWindowFixed()) {
            windowStartUs = config.startPositionUs
            windowEndUs = config.endPositionUs
            if (config.relativeToDefaultPosition) {
                val windowDefaultPositionUs = window.defaultPositionUs
                windowStartUs += windowDefaultPositionUs
                if (windowEndUs != C.TIME_END_OF_SOURCE) {
                    windowEndUs += windowDefaultPositionUs
                }
            }
            periodStartUs = windowPositionInPeriodUs + windowStartUs
            periodEndUs =
                if (config.endPositionUs == C.TIME_END_OF_SOURCE)
                    C.TIME_END_OF_SOURCE
                else
                    windowPositionInPeriodUs + windowEndUs
            val count = mediaPeriods.size
            for (i in 0..<count) {
                mediaPeriods[i].updateClipping(periodStartUs, periodEndUs)
            }
        } else {
            // Keep window fixed at previous period position.
            windowStartUs = periodStartUs - windowPositionInPeriodUs
            windowEndUs =
                if (periodEndUs == C.TIME_END_OF_SOURCE)
                    C.TIME_END_OF_SOURCE
                else
                    periodEndUs - windowPositionInPeriodUs
        }
        try {
            clippingTimeline =
                ClippingTimeline(timeline, windowStartUs, windowEndUs, config.allowUnseekableMedia)
        } catch (e: IllegalClippingException) {
            clippingError = e
            // The clipping error won't be propagated while we have existing MediaPeriods. Setting the
            // error at the MediaPeriods ensures it will be thrown as soon as possible.
            var i = 0
            while (i < mediaPeriods.size) {
                mediaPeriods[i].setClippingError(e)
                i++
            }
            return
        }
        refreshSourceInfo(clippingTimeline!!)
    }

    @RequiresNonNull("clippingTimeline")
    private fun updatePeriodClippingToNewConfig(oldConfig: MediaItem.ClippingConfiguration) {
        val windowDefaultOffset =
            clippingTimeline!!.timeline.getWindow(0, window).defaultPositionUs
        val defaultOffsetChange =
            ((if (oldConfig.relativeToDefaultPosition) -windowDefaultOffset else 0)
                    + (if (config.relativeToDefaultPosition) windowDefaultOffset else 0))
        periodStartUs += defaultOffsetChange + config.startPositionUs - oldConfig.startPositionUs
        periodEndUs =
            if (config.endPositionUs == C.TIME_END_OF_SOURCE)
                C.TIME_END_OF_SOURCE
            else
                periodStartUs + config.endPositionUs - config.startPositionUs
        val count = mediaPeriods.size
        for (i in 0..<count) {
            mediaPeriods[i].updateClipping(periodStartUs, periodEndUs)
        }
    }

    private fun shouldKeepWindowFixed(): Boolean {
        return !mediaPeriods.isEmpty() && clippingTimeline != null && !config.relativeToLiveWindow
    }

    private class ClippingTimeline(
        timeline: Timeline,
        startUs: Long,
        endUs: Long,
        allowUnseekableMedia: Boolean
    ) : ForwardingTimeline(timeline) {
        private val startUs: Long
        private val endUs: Long
        private val durationUs: Long
        private val isDynamic: Boolean

        init {
            var startUs = startUs
            var endUs = endUs
            if (endUs != C.TIME_END_OF_SOURCE && endUs < startUs) {
                throw IllegalClippingException(
                    IllegalClippingException.REASON_START_EXCEEDS_END, startUs, endUs
                )
            }
            if (timeline.periodCount != 1) {
                throw IllegalClippingException(IllegalClippingException.REASON_INVALID_PERIOD_COUNT)
            }
            val window = timeline.getWindow(0, Window())
            startUs = max(0, startUs)
            if (!allowUnseekableMedia && !window.isPlaceholder && startUs != 0L && !window.isSeekable) {
                throw IllegalClippingException(IllegalClippingException.REASON_NOT_SEEKABLE_TO_START)
            }
            endUs = if (endUs == C.TIME_END_OF_SOURCE) window.durationUs else max(0, endUs)
            if (window.durationUs != C.TIME_UNSET) {
                if (endUs > window.durationUs) {
                    endUs = window.durationUs
                }
                if (startUs > endUs) {
                    startUs = endUs
                }
            }
            this.startUs = startUs
            this.endUs = endUs
            durationUs = if (endUs == C.TIME_UNSET) C.TIME_UNSET else (endUs - startUs)
            isDynamic =
                window.isDynamic
                        && (endUs == C.TIME_UNSET
                        || (window.durationUs != C.TIME_UNSET && endUs == window.durationUs))
        }

        override fun getWindow(
            windowIndex: Int,
            window: Window,
            defaultPositionProjectionUs: Long
        ): Window {
            timeline.getWindow( 0, window,  0)
            window.positionInFirstPeriodUs += startUs
            window.durationUs = durationUs
            window.isDynamic = isDynamic
            if (window.defaultPositionUs != C.TIME_UNSET) {
                window.defaultPositionUs = max(window.defaultPositionUs, startUs)
                window.defaultPositionUs =
                    if (endUs == C.TIME_UNSET) window.defaultPositionUs else min(
                        window.defaultPositionUs,
                        endUs
                    )
                window.defaultPositionUs -= startUs
            }
            val startMs = usToMs(startUs)
            if (window.presentationStartTimeMs != C.TIME_UNSET) {
                window.presentationStartTimeMs += startMs
            }
            if (window.windowStartTimeMs != C.TIME_UNSET) {
                window.windowStartTimeMs += startMs
            }
            return window
        }

        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
            timeline.getPeriod( 0, period, setIds)
            val positionInClippedWindowUs = period.positionInWindowUs - startUs
            val periodDurationUs =
                if (durationUs == C.TIME_UNSET) C.TIME_UNSET else durationUs - positionInClippedWindowUs
            return period.set(
                period.id,
                period.uid,  
                0,
                periodDurationUs,
                positionInClippedWindowUs,
                AdPlaybackState.NONE,
                period.isPlaceholder
            )
        }
    }
}
