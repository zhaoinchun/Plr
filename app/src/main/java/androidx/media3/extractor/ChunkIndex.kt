
package androidx.media3.extractor

import androidx.media3.common.util.Util.binarySearchFloor
import androidx.media3.extractor.SeekMap.SeekPoints

class ChunkIndex(sizes: IntArray, offsets: LongArray, durationsUs: LongArray, timesUs: LongArray) :
    SeekMap {
    val length: Int
    val sizes: IntArray?
    val offsets: LongArray
    val durationsUs: LongArray?
    val timesUs: LongArray

    override val durationUs: Long

    init {
        this.sizes = sizes
        this.offsets = offsets
        this.durationsUs = durationsUs
        this.timesUs = timesUs
        length = sizes.size
        if (length > 0) {
            durationUs = durationsUs[length - 1] + timesUs[length - 1]
        } else {
            durationUs = 0
        }
    }

    fun getChunkIndex(timeUs: Long): Int {
        return binarySearchFloor(timesUs, timeUs,  true,  true)
    }

    override val isSeekable=true

    override fun getSeekPoints(timeUs: Long): SeekPoints {
        val chunkIndex = getChunkIndex(timeUs)
        val seekPoint = SeekPoint(timesUs[chunkIndex], offsets[chunkIndex])
        if (seekPoint.timeUs >= timeUs || chunkIndex == length - 1) {
            return SeekPoints(seekPoint)
        } else {
            val nextSeekPoint = SeekPoint(timesUs[chunkIndex + 1], offsets[chunkIndex + 1])
            return SeekPoints(seekPoint, nextSeekPoint)
        }
    }

    override fun toString(): String {
        return ("ChunkIndex("
                + "length="
                + length
                + ", sizes="
                + sizes.contentToString() + ", offsets="
                + offsets.contentToString() + ", timeUs="
                + timesUs.contentToString() + ", durationsUs="
                + durationsUs.contentToString() + ")")
    }
}
