
package androidx.media3.extractor.wav

import androidx.media3.common.C
import androidx.media3.common.util.Util.constrainValue
import androidx.media3.common.util.Util.scaleLargeTimestamp
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekMap.SeekPoints
import androidx.media3.extractor.SeekPoint

internal class WavSeekMap(
    private val wavFormat: WavFormat,
    private val framesPerBlock: Int,
    private val firstBlockPosition: Long,
    dataEndPosition: Long
) : SeekMap {
    private val blockCount: Long
    override val durationUs: Long

    init {
        this.blockCount = (dataEndPosition - firstBlockPosition) / wavFormat.blockSize
        durationUs = blockIndexToTimeUs(blockCount)
    }

    override val isSeekable: Boolean
        get() = true

    override fun getSeekPoints(timeUs: Long): SeekPoints {
        // Calculate the containing block index, constraining to valid indices.
        var blockIndex = (timeUs * wavFormat.frameRateHz) / (C.MICROS_PER_SECOND * framesPerBlock)
        blockIndex = constrainValue(blockIndex, 0, blockCount - 1)

        val seekPosition = firstBlockPosition + (blockIndex * wavFormat.blockSize)
        val seekTimeUs = blockIndexToTimeUs(blockIndex)
        val seekPoint = SeekPoint(seekTimeUs, seekPosition)
        if (seekTimeUs >= timeUs || blockIndex == blockCount - 1) {
            return SeekPoints(seekPoint)
        } else {
            val secondBlockIndex = blockIndex + 1
            val secondSeekPosition = firstBlockPosition + (secondBlockIndex * wavFormat.blockSize)
            val secondSeekTimeUs = blockIndexToTimeUs(secondBlockIndex)
            val secondSeekPoint = SeekPoint(secondSeekTimeUs, secondSeekPosition)
            return SeekPoints(seekPoint, secondSeekPoint)
        }
    }

    private fun blockIndexToTimeUs(blockIndex: Long): Long {
        return scaleLargeTimestamp(
            blockIndex * framesPerBlock, C.MICROS_PER_SECOND, wavFormat.frameRateHz.toLong()
        )
    }
}
