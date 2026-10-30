package androidx.media3.extractor

import androidx.media3.common.C
import androidx.media3.common.util.Util.binarySearchFloor
import androidx.media3.extractor.SeekMap.SeekPoints
import com.google.common.base.Preconditions

class IndexSeekMap(positions: List<Long>, timesUs: List<Long>, durationUs: Long) : SeekMap {
    private val positions: MutableList<Long>
    private val timesUs: MutableList<Long>
    override var durationUs: Long

    init {
        Preconditions.checkArgument(positions.size == timesUs.size)
        val length = timesUs.size
        if (length > 0 && timesUs[0] > 0) {
            // Add (position = 0, timeUs = 0) as first entry.
            this.positions = MutableList<Long>(length + 1){0}
            this.timesUs = MutableList<Long>(length + 1){0}
            this.positions.add(0L)
            this.timesUs.add(0L)
        } else {
            this.positions = MutableList<Long>(length){0}
            this.timesUs = MutableList<Long>(length){0}
        }
        this.positions.addAll(positions)
        this.timesUs.addAll(timesUs)
        this.durationUs = durationUs
    }

    override val isSeekable: Boolean
        get() = timesUs.size > 0

    override fun getSeekPoints(timeUs: Long): SeekPoints {
        if (timesUs.size == 0) {
            return SeekPoints(SeekPoint.START)
        }
        val targetIndex =
            binarySearchFloor(timesUs, timeUs,  true,  true)
        val leftSeekPoint = SeekPoint(timesUs.get(targetIndex), positions.get(targetIndex))
        if (leftSeekPoint.timeUs == timeUs || targetIndex == timesUs.size - 1) {
            return SeekPoints(leftSeekPoint)
        } else {
            val rightSeekPoint =
                SeekPoint(timesUs.get(targetIndex + 1), positions.get(targetIndex + 1))
            return SeekPoints(leftSeekPoint, rightSeekPoint)
        }
    }

    fun addSeekPoint(timeUs: Long, position: Long) {
        if (timesUs.size == 0 && timeUs > 0) {
            // Add (position = 0, timeUs = 0) as first entry.
            this.positions.add(0L)
            this.timesUs.add(0L)
        }
        positions.add(position)
        timesUs.add(timeUs)
    }

    fun getTimeUs(position: Long): Long {
        if (timesUs.size == 0) {
            return C.TIME_UNSET
        }
        val targetIndex =
            binarySearchFloor(
                positions, position,  true,  true
            )
        return timesUs.get(targetIndex)
    }

    fun isTimeUsInIndex(timeUs: Long, minTimeBetweenPointsUs: Long): Boolean {
        if (timesUs.size == 0) {
            return false
        }
        return timeUs - timesUs.get(timesUs.size - 1) < minTimeBetweenPointsUs
    }

}
