package androidx.media3.extractor.mp3

import androidx.media3.common.C
import androidx.media3.common.util.LongArray
import androidx.media3.extractor.IndexSeekMap
import androidx.media3.extractor.SeekMap.SeekPoints

internal class IndexSeeker(durationUs: Long, dataStartPosition: Long, dataEndPosition: Long) :
    Seeker {
    override val dataStartPosition: Long
    override val dataEndPosition: Long
    override val averageBitrate: Int
    private val indexSeekMap: IndexSeekMap

    init {
        this.indexSeekMap =
            IndexSeekMap(
                listOf(dataStartPosition),
                listOf(0L),
                durationUs
            )
        this.dataStartPosition = dataStartPosition
        this.dataEndPosition = dataEndPosition
        this.averageBitrate =
            Mp3Util.computeAverageBitrate(dataEndPosition - dataStartPosition, durationUs)
    }

    override fun getTimeUs(position: Long): Long {
        return indexSeekMap.getTimeUs(position)
    }

    override val isSeekable: Boolean
        get() = indexSeekMap.isSeekable

    override var durationUs: Long
        get() = indexSeekMap.durationUs
        
        set(durationUs) {
            indexSeekMap.durationUs=(durationUs)
        }

    override fun getSeekPoints(timeUs: Long): SeekPoints {
        return indexSeekMap.getSeekPoints(timeUs)
    }

    fun maybeAddSeekPoint(timeUs: Long, position: Long) {
        if (isTimeUsInIndex(timeUs)) {
            return
        }
        indexSeekMap.addSeekPoint(timeUs, position)
    }

    fun isTimeUsInIndex(timeUs: Long): Boolean {
        return indexSeekMap.isTimeUsInIndex(timeUs, MIN_TIME_BETWEEN_POINTS_US)
    }

    companion object {
        val MIN_TIME_BETWEEN_POINTS_US: Long = C.MICROS_PER_SECOND / 10
    }
}
