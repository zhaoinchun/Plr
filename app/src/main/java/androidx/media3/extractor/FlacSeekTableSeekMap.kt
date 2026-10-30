
package androidx.media3.extractor

import androidx.media3.common.C

import androidx.media3.common.util.Util.binarySearchFloor
import androidx.media3.extractor.FlacStreamMetadata.SeekTable
import androidx.media3.extractor.SeekMap.SeekPoints
import com.google.common.base.Preconditions

class FlacSeekTableSeekMap(private val flacStreamMetadata: FlacStreamMetadata, private val firstFrameOffset: Long) :
    SeekMap {
    override val isSeekable: Boolean=true

    override val durationUs: Long
        get() = flacStreamMetadata.durationUs

    override fun getSeekPoints(timeUs: Long): SeekPoints {
        val pointSampleNumbers = flacStreamMetadata.seekTable!!.pointSampleNumbers!!
        val pointOffsets = flacStreamMetadata.seekTable.pointOffsets!!

        val targetSampleNumber = flacStreamMetadata.getSampleNumber(timeUs)
        val index =
            binarySearchFloor(
                pointSampleNumbers,
                targetSampleNumber,  
                true,  
                false
            )

        val seekPointSampleNumber = if (index == -1) 0 else pointSampleNumbers[index]
        val seekPointOffsetFromFirstFrame = if (index == -1) 0 else pointOffsets[index]
        val seekPoint = getSeekPoint(seekPointSampleNumber, seekPointOffsetFromFirstFrame)
        if (seekPoint.timeUs == timeUs || index == pointSampleNumbers.size - 1) {
            return SeekPoints(seekPoint)
        } else {
            val secondSeekPoint =
                getSeekPoint(pointSampleNumbers[index + 1], pointOffsets[index + 1])
            return SeekPoints(seekPoint, secondSeekPoint)
        }
    }

    private fun getSeekPoint(sampleNumber: Long, offsetFromFirstFrame: Long): SeekPoint {
        val seekTimeUs = sampleNumber * C.MICROS_PER_SECOND / flacStreamMetadata.sampleRate
        val seekPosition = firstFrameOffset + offsetFromFirstFrame
        return SeekPoint(seekTimeUs, seekPosition)
    }
}
