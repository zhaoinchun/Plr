
package androidx.media3.exoplayer

import androidx.media3.common.AdPlaybackState
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.Util.binarySearchFloor
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.ShuffleOrder
import java.util.Arrays

internal class PlaylistTimeline private constructor(
    timelines: Array<Timeline?>,
    uids: Array<Any?>,
    shuffleOrder: ShuffleOrder
) : AbstractConcatenatedTimeline( false, shuffleOrder) {
    override val windowCount: Int
    override val periodCount: Int
    private val firstPeriodInChildIndices: IntArray
    private val firstWindowInChildIndices: IntArray
    private val timelines: Array<Timeline?>
    private val uids: Array<Any?>
    private val childIndexByUid: HashMap<Any?, Int?>

    constructor(
        mediaSourceInfoHolders: List<out MediaSourceInfoHolder>,
        shuffleOrder: ShuffleOrder
    ) : this(getTimelines(mediaSourceInfoHolders), getUids(mediaSourceInfoHolders), shuffleOrder)

    init {
        val childCount = timelines.size
        this.timelines = timelines
        firstPeriodInChildIndices = IntArray(childCount)
        firstWindowInChildIndices = IntArray(childCount)
        this.uids = uids
        childIndexByUid = HashMap<Any?, Int?>()
        var index = 0
        var windowCount = 0
        var periodCount = 0
        for (timeline in timelines) {
            this.timelines[index] = timeline
            firstWindowInChildIndices[index] = windowCount
            firstPeriodInChildIndices[index] = periodCount
            windowCount += this.timelines[index]!!.windowCount
            periodCount += this.timelines[index]!!.periodCount
            childIndexByUid.put(uids[index], index++)
        }
        this.windowCount = windowCount
        this.periodCount = periodCount
    }

    val childTimelines: List<Timeline>
        get() = Arrays.asList<Timeline>(*timelines)

    override fun getChildIndexByPeriodIndex(periodIndex: Int): Int {
        return binarySearchFloor(firstPeriodInChildIndices, periodIndex + 1, false, false)
    }

    override fun getChildIndexByWindowIndex(windowIndex: Int): Int {
        return binarySearchFloor(firstWindowInChildIndices, windowIndex + 1, false, false)
    }

    override fun getChildIndexByChildUid(childUid: Any?): Int {
        val index = childIndexByUid.get(childUid)
        return if (index == null) C.INDEX_UNSET else index
    }

    override fun getTimelineByChildIndex(childIndex: Int): Timeline? {
        return timelines[childIndex]
    }

    override fun getFirstPeriodIndexByChildIndex(childIndex: Int): Int {
        return firstPeriodInChildIndices[childIndex]
    }

    override fun getFirstWindowIndexByChildIndex(childIndex: Int): Int {
        return firstWindowInChildIndices[childIndex]
    }

    override fun getChildUidByChildIndex(childIndex: Int): Any? {
        return uids[childIndex]
    }

    fun copyWithPlaceholderTimeline(shuffleOrder: ShuffleOrder): PlaylistTimeline {
        val newTimelines = arrayOfNulls<Timeline>(timelines.size)
        for (i in timelines.indices) {
            newTimelines[i] =
                object : ForwardingTimeline(timelines[i]!!) {
                    private val window = Window()

                    override fun getPeriod(
                        periodIndex: Int,
                        period: Period,
                        setIds: Boolean
                    ): Period {
                        val superPeriod = super.getPeriod(periodIndex, period, setIds)
                        if (super.getWindow(superPeriod.windowIndex, window).isLive) {
                            // Reset the ad playback state for placeholder period of a live streams.
                            superPeriod.set(
                                period.id,
                                period.uid,
                                period.windowIndex,
                                period.durationUs,
                                period.positionInWindowUs,
                                AdPlaybackState.NONE,  
                                true
                            )
                        } else {
                            superPeriod.isPlaceholder = true
                        }
                        return superPeriod
                    }
                }
        }
        return PlaylistTimeline(newTimelines, uids, shuffleOrder)
    }

    companion object {
        private fun getUids(
            mediaSourceInfoHolders: List<MediaSourceInfoHolder>
        ): Array<Any?> {
            val uids = arrayOfNulls<Any>(mediaSourceInfoHolders.size)
            var i = 0
            for (holder in mediaSourceInfoHolders) {
                uids[i++] = holder.uid
            }
            return uids
        }

        private fun getTimelines(
            mediaSourceInfoHolders: List<MediaSourceInfoHolder>
        ): Array<Timeline?> {
            val timelines = arrayOfNulls<Timeline>(mediaSourceInfoHolders.size)
            var i = 0
            for (holder in mediaSourceInfoHolders) {
                timelines[i++] = holder.timeline
            }
            return timelines
        }
    }
}
