package androidx.media3.exoplayer.source

import androidx.media3.common.Player
import androidx.media3.common.Timeline

abstract class ForwardingTimeline(val timeline: Timeline) : Timeline() {
    override val windowCount: Int
        get() = timeline.windowCount

    override fun getNextWindowIndex(
        windowIndex: Int,
        repeatMode: @Player.RepeatMode Int,
        shuffleModeEnabled: Boolean
    ): Int {
        return timeline.getNextWindowIndex(windowIndex, repeatMode, shuffleModeEnabled)
    }

    override fun getPreviousWindowIndex(
        windowIndex: Int,
        repeatMode: @Player.RepeatMode Int,
        shuffleModeEnabled: Boolean
    ): Int {
        return timeline.getPreviousWindowIndex(windowIndex, repeatMode, shuffleModeEnabled)
    }

    override fun getLastWindowIndex(shuffleModeEnabled: Boolean): Int {
        return timeline.getLastWindowIndex(shuffleModeEnabled)
    }

    override fun getFirstWindowIndex(shuffleModeEnabled: Boolean): Int {
        return timeline.getFirstWindowIndex(shuffleModeEnabled)
    }

    override fun getWindow(
        windowIndex: Int,
        window: Window,
        defaultPositionProjectionUs: Long
    ): Window {
        return timeline.getWindow(windowIndex, window, defaultPositionProjectionUs)
    }

    override val periodCount: Int
        get() = timeline.periodCount

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
        return timeline.getPeriod(periodIndex, period, setIds)
    }

    override fun getIndexOfPeriod(uid: Any): Int {
        return timeline.getIndexOfPeriod(uid)
    }

    override fun getUidOfPeriod(periodIndex: Int): Any {
        return timeline.getUidOfPeriod(periodIndex)
    }
}
