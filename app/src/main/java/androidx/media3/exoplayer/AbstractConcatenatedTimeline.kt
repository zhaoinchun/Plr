package androidx.media3.exoplayer

import android.util.Pair
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline

import androidx.media3.exoplayer.source.ShuffleOrder
import com.google.common.base.Preconditions

abstract class AbstractConcatenatedTimeline(
    private val isAtomic: Boolean,
    private val shuffleOrder: ShuffleOrder
) : Timeline() {
    private val childCount = shuffleOrder.length

    override fun getNextWindowIndex(
        windowIndex: Int,
        @Player.RepeatMode repeatMode: @Player.RepeatMode Int,
        shuffleModeEnabled: Boolean
    ): Int {
        var repeatMode = repeatMode
        var shuffleModeEnabled = shuffleModeEnabled
        if (isAtomic) {
            // Adapt repeat and shuffle mode to atomic concatenation.
            repeatMode =
                if (repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ALL else repeatMode
            shuffleModeEnabled = false
        }
        // Find next window within current child.
        val childIndex = getChildIndexByWindowIndex(windowIndex)
        val firstWindowIndexInChild = getFirstWindowIndexByChildIndex(childIndex)
        val nextWindowIndexInChild =
            getTimelineByChildIndex(childIndex)!!
                .getNextWindowIndex(
                    windowIndex - firstWindowIndexInChild,
                    if (repeatMode == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_OFF else repeatMode,
                    shuffleModeEnabled
                )
        if (nextWindowIndexInChild != C.INDEX_UNSET) {
            return firstWindowIndexInChild + nextWindowIndexInChild
        }
        // If not found, find first window of next non-empty child.
        var nextChildIndex = getNextChildIndex(childIndex, shuffleModeEnabled)
        while (nextChildIndex != C.INDEX_UNSET && getTimelineByChildIndex(nextChildIndex)!!.isEmpty) {
            nextChildIndex = getNextChildIndex(nextChildIndex, shuffleModeEnabled)
        }
        if (nextChildIndex != C.INDEX_UNSET) {
            return (getFirstWindowIndexByChildIndex(nextChildIndex)
                    + getTimelineByChildIndex(nextChildIndex)!!.getFirstWindowIndex(
                shuffleModeEnabled
            ))
        }
        // If not found, this is the last window.
        if (repeatMode == Player.REPEAT_MODE_ALL) {
            return getFirstWindowIndex(shuffleModeEnabled)
        }
        return C.INDEX_UNSET
    }

    override fun getPreviousWindowIndex(
        windowIndex: Int,
        @Player.RepeatMode repeatMode: @Player.RepeatMode Int,
        shuffleModeEnabled: Boolean
    ): Int {
        var repeatMode = repeatMode
        var shuffleModeEnabled = shuffleModeEnabled
        if (isAtomic) {
            // Adapt repeat and shuffle mode to atomic concatenation.
            repeatMode =
                if (repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ALL else repeatMode
            shuffleModeEnabled = false
        }
        // Find previous window within current child.
        val childIndex = getChildIndexByWindowIndex(windowIndex)
        val firstWindowIndexInChild = getFirstWindowIndexByChildIndex(childIndex)
        val previousWindowIndexInChild =
            getTimelineByChildIndex(childIndex)!!
                .getPreviousWindowIndex(
                    windowIndex - firstWindowIndexInChild,
                    if (repeatMode == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_OFF else repeatMode,
                    shuffleModeEnabled
                )
        if (previousWindowIndexInChild != C.INDEX_UNSET) {
            return firstWindowIndexInChild + previousWindowIndexInChild
        }
        // If not found, find last window of previous non-empty child.
        var previousChildIndex = getPreviousChildIndex(childIndex, shuffleModeEnabled)
        while (previousChildIndex != C.INDEX_UNSET
            && getTimelineByChildIndex(previousChildIndex)!!.isEmpty
        ) {
            previousChildIndex = getPreviousChildIndex(previousChildIndex, shuffleModeEnabled)
        }
        if (previousChildIndex != C.INDEX_UNSET) {
            return (getFirstWindowIndexByChildIndex(previousChildIndex)
                    + getTimelineByChildIndex(previousChildIndex)!!.getLastWindowIndex(
                shuffleModeEnabled
            ))
        }
        // If not found, this is the first window.
        if (repeatMode == Player.REPEAT_MODE_ALL) {
            return getLastWindowIndex(shuffleModeEnabled)
        }
        return C.INDEX_UNSET
    }

    override fun getLastWindowIndex(shuffleModeEnabled: Boolean): Int {
        var shuffleModeEnabled = shuffleModeEnabled
        if (childCount == 0) {
            return C.INDEX_UNSET
        }
        if (isAtomic) {
            shuffleModeEnabled = false
        }
        // Find last non-empty child.
        var lastChildIndex = if (shuffleModeEnabled) shuffleOrder.lastIndex else childCount - 1
        while (getTimelineByChildIndex(lastChildIndex)!!.isEmpty) {
            lastChildIndex = getPreviousChildIndex(lastChildIndex, shuffleModeEnabled)
            if (lastChildIndex == C.INDEX_UNSET) {
                // All children are empty.
                return C.INDEX_UNSET
            }
        }
        return (getFirstWindowIndexByChildIndex(lastChildIndex)
                + getTimelineByChildIndex(lastChildIndex)!!.getLastWindowIndex(shuffleModeEnabled))
    }

    override fun getFirstWindowIndex(shuffleModeEnabled: Boolean): Int {
        var shuffleModeEnabled = shuffleModeEnabled
        if (childCount == 0) {
            return C.INDEX_UNSET
        }
        if (isAtomic) {
            shuffleModeEnabled = false
        }
        // Find first non-empty child.
        var firstChildIndex = if (shuffleModeEnabled) shuffleOrder.firstIndex else 0
        while (getTimelineByChildIndex(firstChildIndex)!!.isEmpty) {
            firstChildIndex = getNextChildIndex(firstChildIndex, shuffleModeEnabled)
            if (firstChildIndex == C.INDEX_UNSET) {
                // All children are empty.
                return C.INDEX_UNSET
            }
        }
        return (getFirstWindowIndexByChildIndex(firstChildIndex)
                + getTimelineByChildIndex(firstChildIndex)!!.getFirstWindowIndex(shuffleModeEnabled))
    }

    override fun getWindow(
        windowIndex: Int,
        window: Window,
        defaultPositionProjectionUs: Long
    ): Window {
        val childIndex = getChildIndexByWindowIndex(windowIndex)
        val firstWindowIndexInChild = getFirstWindowIndexByChildIndex(childIndex)
        val firstPeriodIndexInChild = getFirstPeriodIndexByChildIndex(childIndex)
        getTimelineByChildIndex(childIndex)!!
            .getWindow(windowIndex - firstWindowIndexInChild, window, defaultPositionProjectionUs)
        val childUid = getChildUidByChildIndex(childIndex)
        // Don't create new objects if the child is using SINGLE_WINDOW_UID.
        window.uid =
            (if (androidx.media3.common.Timeline.Window.SINGLE_WINDOW_UID == window.uid)
                childUid
            else
                AbstractConcatenatedTimeline.getConcatenatedUid(childUid, window.uid))!!
        window.firstPeriodIndex += firstPeriodIndexInChild
        window.lastPeriodIndex += firstPeriodIndexInChild
        return window
    }

    override fun getPeriodByUid(periodUid: Any, period: Period): Period {
        val childUid: Any? = getChildTimelineUidFromConcatenatedUid(periodUid)
        val childPeriodUid = getChildPeriodUidFromConcatenatedUid(periodUid)
        val childIndex = getChildIndexByChildUid(childUid)
        val firstWindowIndexInChild = getFirstWindowIndexByChildIndex(childIndex)
        getTimelineByChildIndex(childIndex)!!.getPeriodByUid(childPeriodUid, period)
        period.windowIndex += firstWindowIndexInChild
        period.uid = periodUid
        return period
    }

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
        val childIndex = getChildIndexByPeriodIndex(periodIndex)
        val firstWindowIndexInChild = getFirstWindowIndexByChildIndex(childIndex)
        val firstPeriodIndexInChild = getFirstPeriodIndexByChildIndex(childIndex)
        getTimelineByChildIndex(childIndex)!!
            .getPeriod(periodIndex - firstPeriodIndexInChild, period, setIds)
        period.windowIndex += firstWindowIndexInChild
        if (setIds) {
            period.uid =
                getConcatenatedUid(
                    getChildUidByChildIndex(childIndex),
                    period.uid!!
                )
        }
        return period
    }

    override fun getIndexOfPeriod(uid: Any): Int {
        if (uid !is Pair<*, *>) {
            return C.INDEX_UNSET
        }
        val childUid = getChildTimelineUidFromConcatenatedUid(uid)
        val childPeriodUid = getChildPeriodUidFromConcatenatedUid(uid)
        val childIndex = getChildIndexByChildUid(childUid)
        if (childIndex == C.INDEX_UNSET) {
            return C.INDEX_UNSET
        }
        val periodIndexInChild =
            getTimelineByChildIndex(childIndex)!!.getIndexOfPeriod(childPeriodUid)
        return if (periodIndexInChild == C.INDEX_UNSET)
            C.INDEX_UNSET
        else
            getFirstPeriodIndexByChildIndex(childIndex) + periodIndexInChild
    }

    override fun getUidOfPeriod(periodIndex: Int): Any {
        val childIndex = getChildIndexByPeriodIndex(periodIndex)
        val firstPeriodIndexInChild = getFirstPeriodIndexByChildIndex(childIndex)
        val periodUidInChild =
            getTimelineByChildIndex(childIndex)!!.getUidOfPeriod(periodIndex - firstPeriodIndexInChild)
        return getConcatenatedUid(getChildUidByChildIndex(childIndex), periodUidInChild)
    }

    protected abstract fun getChildIndexByPeriodIndex(periodIndex: Int): Int

    protected abstract fun getChildIndexByWindowIndex(windowIndex: Int): Int

    protected abstract fun getChildIndexByChildUid(childUid: Any?): Int

    protected abstract fun getTimelineByChildIndex(childIndex: Int): Timeline?

    protected abstract fun getFirstPeriodIndexByChildIndex(childIndex: Int): Int

    protected abstract fun getFirstWindowIndexByChildIndex(childIndex: Int): Int

    protected abstract fun getChildUidByChildIndex(childIndex: Int): Any?

    private fun getNextChildIndex(childIndex: Int, shuffleModeEnabled: Boolean): Int {
        return if (shuffleModeEnabled)
            shuffleOrder.getNextIndex(childIndex)
        else
            if (childIndex < childCount - 1) childIndex + 1 else C.INDEX_UNSET
    }

    private fun getPreviousChildIndex(childIndex: Int, shuffleModeEnabled: Boolean): Int {
        return if (shuffleModeEnabled)
            shuffleOrder.getPreviousIndex(childIndex)
        else
            if (childIndex > 0) childIndex - 1 else C.INDEX_UNSET
    }

    companion object {
        fun getChildTimelineUidFromConcatenatedUid(concatenatedUid: Any): Any? {
            return (concatenatedUid as Pair<*, *>).first
        }

        fun getChildPeriodUidFromConcatenatedUid(concatenatedUid: Any): Any {
            return (concatenatedUid as Pair<*, *>).second
        }

        fun getConcatenatedUid(childTimelineUid: Any?, childPeriodOrWindowUid: Any?): Any {
            return Pair.create<Any?, Any?>(childTimelineUid, childPeriodOrWindowUid)
        }
    }
}
