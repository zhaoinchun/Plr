
package androidx.media3.extractor

import androidx.media3.common.C

import androidx.media3.extractor.SeekMap.SeekPoints
import kotlin.math.max
import kotlin.math.min

open class ConstantBitrateSeekMap protected constructor(
     val inputLength: Long,
     val firstFramePosition: Long,
     val bitrate: Int,
    frameSize: Int,
     val allowSeeksIfLengthUnknown: Boolean,
    override val isEstimated: Boolean
) : SeekMap {

     val frameSize: Int
     val dataSize: Long
    override var durationUs: Long = C.TIME_UNSET

    constructor(
        inputLength: Long,
        firstFrameBytePosition: Long,
        bitrate: Int,
        frameSize: Int,
        allowSeeksIfLengthUnknown: Boolean = false
    ) : this(
        inputLength,
        firstFrameBytePosition,
        bitrate,
        frameSize,
        allowSeeksIfLengthUnknown,  
        true
    )

    init {
        this.frameSize = if (frameSize == C.LENGTH_UNSET) 1 else frameSize

        if (inputLength == C.LENGTH_UNSET.toLong()) {
            dataSize = C.LENGTH_UNSET.toLong()
            durationUs = C.TIME_UNSET
        } else {
            dataSize = inputLength - firstFramePosition
            durationUs = getTimeUsAtPosition(
                inputLength,
                firstFramePosition,
                bitrate
            )
        }
    }

    override val isSeekable: Boolean
        get() = dataSize != C.LENGTH_UNSET.toLong() || allowSeeksIfLengthUnknown

    override fun getSeekPoints(timeUs: Long): SeekPoints {
        if (dataSize == C.LENGTH_UNSET.toLong() && !allowSeeksIfLengthUnknown) {
            return SeekPoints(SeekPoint(0, this.firstFramePosition))
        }
        val seekFramePosition = getFramePositionForTimeUs(timeUs)
        val seekTimeUs = getTimeUsAtPosition(seekFramePosition)
        val seekPoint = SeekPoint(seekTimeUs, seekFramePosition)
        // We only return a single seek point if the length is unknown, to avoid generating a second
        // seek point beyond the end of the data in the case that the requested seek position is valid,
        // but very close to the end of the content.
        if (dataSize == C.LENGTH_UNSET.toLong() || seekTimeUs >= timeUs || seekFramePosition + frameSize >= inputLength) {
            return SeekPoints(seekPoint)
        } else {
            val secondSeekPosition = seekFramePosition + frameSize
            val secondSeekTimeUs = getTimeUsAtPosition(secondSeekPosition)
            val secondSeekPoint = SeekPoint(secondSeekTimeUs, secondSeekPosition)
            return SeekPoints(seekPoint, secondSeekPoint)
        }
    }

    fun getTimeUsAtPosition(position: Long): Long {
        return getTimeUsAtPosition(
            position,
            this.firstFramePosition, bitrate
        )
    }

    protected fun shouldAllowSeeksIfLengthUnknown(): Boolean {
        return allowSeeksIfLengthUnknown
    }

    private fun getFramePositionForTimeUs(timeUs: Long): Long {
        var positionOffset = (timeUs * bitrate) / (C.MICROS_PER_SECOND * C.BITS_PER_BYTE)
        // Constrain to nearest preceding frame offset.
        positionOffset = (positionOffset / frameSize) * frameSize
        if (dataSize != C.LENGTH_UNSET.toLong()) {
            positionOffset = min(positionOffset, dataSize - frameSize)
        }
        positionOffset = max(positionOffset, 0)
        return this.firstFramePosition + positionOffset
    }

    companion object {
        private fun getTimeUsAtPosition(
            position: Long,
            firstFrameBytePosition: Long,
            bitrate: Int
        ): Long {
            return ((max(0, position - firstFrameBytePosition)
                    * C.BITS_PER_BYTE
                    * C.MICROS_PER_SECOND)
                    / bitrate)
        }
    }
}
