package androidx.media3.extractor.mp3

import androidx.media3.common.C
import androidx.media3.common.util.Log.i
import androidx.media3.common.util.Util.binarySearchFloor
import androidx.media3.common.util.Util.constrainValue
import androidx.media3.extractor.SeekMap.SeekPoints
import androidx.media3.extractor.SeekPoint
import com.google.common.base.Preconditions
import kotlin.math.min

internal class XingSeeker private constructor(
    override val dataStartPosition: Long,
    private val xingFrameSize: Int,
    override val durationUs: Long,
    private val dataSize: Long,
    private val tableOfContents: LongArray?
) : Seeker {
    override val averageBitrate: Int
    override val dataEndPosition: Long

    init {
        this.averageBitrate = Mp3Util.computeAverageBitrate(dataSize - xingFrameSize, durationUs)
        dataEndPosition =
            if (dataSize == C.LENGTH_UNSET.toLong()) C.INDEX_UNSET.toLong() else dataStartPosition + dataSize
    }

    override val isSeekable: Boolean
        get() = tableOfContents != null

    override fun getSeekPoints(timeUs: Long): SeekPoints {
        var timeUs = timeUs
        if (!isSeekable) {
            return SeekPoints(SeekPoint(0, dataStartPosition + xingFrameSize))
        }
        timeUs = constrainValue(timeUs, 0, durationUs)
        val percent = (timeUs * 100.0) / durationUs
        val scaledPosition: Double
        if (percent <= 0) {
            scaledPosition = 0.0
        } else if (percent >= 100) {
            scaledPosition = 256.0
        } else {
            val prevTableIndex = percent.toInt()
            val tableOfContents = this.tableOfContents!!
            val prevScaledPosition = tableOfContents[prevTableIndex].toDouble()
            val nextScaledPosition =
                (if (prevTableIndex == 99) 256 else tableOfContents[prevTableIndex + 1]).toDouble()
            // Linearly interpolate between the two scaled positions.
            val interpolateFraction = percent - prevTableIndex
            scaledPosition =
                prevScaledPosition + (interpolateFraction * (nextScaledPosition - prevScaledPosition))
        }
        var positionOffset = Math.round((scaledPosition / 256) * dataSize)
        // Ensure returned positions skip the frame containing the XING header.
        positionOffset = constrainValue(positionOffset, xingFrameSize.toLong(), dataSize - 1)
        return SeekPoints(SeekPoint(timeUs, dataStartPosition + positionOffset))
    }

    override fun getTimeUs(position: Long): Long {
        val positionOffset = position - dataStartPosition
        if (!isSeekable || positionOffset <= xingFrameSize) {
            return 0L
        }
        val tableOfContents = this.tableOfContents!!
        val scaledPosition = (positionOffset * 256.0) / dataSize
        val prevTableIndex = binarySearchFloor(tableOfContents, scaledPosition.toLong(), true, true)
        val prevTimeUs = getTimeUsForTableIndex(prevTableIndex)
        val prevScaledPosition = tableOfContents[prevTableIndex]
        val nextTimeUs = getTimeUsForTableIndex(prevTableIndex + 1)
        val nextScaledPosition =
            if (prevTableIndex == 99) 256 else tableOfContents[prevTableIndex + 1]
        // Linearly interpolate between the two table entries.
        val interpolateFraction =
            if (prevScaledPosition == nextScaledPosition)
                0.0
            else
                ((scaledPosition - prevScaledPosition) / (nextScaledPosition - prevScaledPosition))
        return prevTimeUs + Math.round(interpolateFraction * (nextTimeUs - prevTimeUs))
    }

    private fun getTimeUsForTableIndex(tableIndex: Int): Long {
        return (durationUs * tableIndex) / 100
    }

    companion object {
        private const val TAG = "XingSeeker"

        fun create(xingFrame: XingFrame, position: Long, streamLength: Long): XingSeeker? {
            val durationUs = xingFrame.computeDurationUs()
            if (durationUs == C.TIME_UNSET) {
                return null
            }
            val dataSize: Long
            if (xingFrame.dataSize != C.LENGTH_UNSET.toLong() && streamLength != C.LENGTH_UNSET.toLong() && position + xingFrame.dataSize != streamLength) {
                val dataSizeFromStreamLength = streamLength - position
                i(
                    TAG,
                    ("Data size mismatch between stream ("
                            + dataSizeFromStreamLength
                            + ") and Xing frame ("
                            + xingFrame.dataSize
                            + "), using smaller value.")
                )
                dataSize = min(xingFrame.dataSize, dataSizeFromStreamLength)
            } else {
                dataSize = xingFrame.dataSize
            }
            return XingSeeker(
                position,
                xingFrame.header.frameSize,
                durationUs,
                dataSize,
                xingFrame.tableOfContents
            )
        }
    }
}
