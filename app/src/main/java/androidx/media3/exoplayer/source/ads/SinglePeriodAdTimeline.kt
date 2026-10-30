package androidx.media3.exoplayer.source.ads

import androidx.media3.common.AdPlaybackState
import androidx.media3.common.C
import androidx.media3.common.Timeline

import androidx.media3.exoplayer.source.ForwardingTimeline
import com.google.common.base.Preconditions

class SinglePeriodAdTimeline(
    contentTimeline: Timeline,
    private val adPlaybackState: AdPlaybackState
) : ForwardingTimeline(contentTimeline) {

    init {
        Preconditions.checkState(contentTimeline.periodCount == 1)
        Preconditions.checkState(contentTimeline.windowCount == 1)
    }

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
        timeline.getPeriod(periodIndex, period, setIds)
        val durationUs =
            if (period.durationUs == C.TIME_UNSET) adPlaybackState.contentDurationUs else period.durationUs
        period.set(
            period.id,
            period.uid,
            period.windowIndex,
            durationUs,
            period.positionInWindowUs,
            adPlaybackState,
            period.isPlaceholder
        )
        return period
    }
}
