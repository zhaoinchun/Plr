package androidx.media3.extractor.mp3

import android.util.Pair
import androidx.media3.common.C
import androidx.media3.common.util.Util
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.metadata.id3.MlltFrame

internal class MlltSeeker private constructor(
    private val referencePositions: LongArray,
    private val referenceTimesMs: LongArray,
    durationUs: Long
) : Seeker {
    override val durationUs: Long

    init {
        // Use the last reference point as the duration if it is unknown, as extrapolating variable
        // bitrate at the end of the stream may give a large error.
        this.durationUs =
            if (durationUs != C.TIME_UNSET)
                durationUs
            else
                Util.msToUs(referenceTimesMs[referenceTimesMs.size - 1])
    }

    override val isSeekable: Boolean
        get() = true

    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        var timeUs = timeUs
        timeUs = Util.constrainValue(timeUs, 0, durationUs)
        val timeMsAndPosition: Pair<Long?, Long?> =
            linearlyInterpolate(Util.usToMs(timeUs), referenceTimesMs, referencePositions)
        timeUs = Util.msToUs(timeMsAndPosition.first!!)
        val position: Long = timeMsAndPosition.second!!
        return SeekMap.SeekPoints(SeekPoint(timeUs, position))
    }

    override fun getTimeUs(position: Long): Long {
        val positionAndTimeMs: Pair<Long?, Long?> =
            linearlyInterpolate(position, referencePositions, referenceTimesMs)
        return Util.msToUs(positionAndTimeMs.second!!)
    }

    override val dataStartPosition: Long=0

    override val dataEndPosition: Long=C.INDEX_UNSET.toLong()

    override val averageBitrate: Int= C.RATE_UNSET_INT

    companion object {
        fun create(firstFramePosition: Long, mlltFrame: MlltFrame, durationUs: Long): MlltSeeker {
            val referenceCount: Int = mlltFrame.bytesDeviations.size
            val referencePositions = LongArray(1 + referenceCount)
            val referenceTimesMs = LongArray(1 + referenceCount)
            referencePositions[0] = firstFramePosition
            referenceTimesMs[0] = 0
            var position = firstFramePosition
            var timeMs: Long = 0
            for (i in 1..referenceCount) {
                position += mlltFrame.bytesBetweenReference + mlltFrame.bytesDeviations[i - 1]
                timeMs += mlltFrame.millisecondsBetweenReference + mlltFrame.millisecondsDeviations[i - 1]
                referencePositions[i] = position
                referenceTimesMs[i] = timeMs
            }
            return MlltSeeker(referencePositions, referenceTimesMs, durationUs)
        }

        private fun linearlyInterpolate(
            x: Long, xReferences: LongArray, yReferences: LongArray
        ): Pair<Long?, Long?> {
            val previousReferenceIndex =
                Util.binarySearchFloor(
                    xReferences,
                    x,  
                    true,  
                    true
                )
            val xPreviousReference = xReferences[previousReferenceIndex]
            val yPreviousReference = yReferences[previousReferenceIndex]
            val nextReferenceIndex = previousReferenceIndex + 1
            if (nextReferenceIndex == xReferences.size) {
                return Pair.create<Long?, Long?>(xPreviousReference, yPreviousReference)
            } else {
                val xNextReference = xReferences[nextReferenceIndex]
                val yNextReference = yReferences[nextReferenceIndex]
                val proportion =
                    if (xNextReference == xPreviousReference)
                        0.0
                    else
                        (x.toDouble() - xPreviousReference) / (xNextReference - xPreviousReference)
                val y =
                    (proportion * (yNextReference - yPreviousReference)).toLong() + yPreviousReference
                return Pair.create<Long?, Long?>(x, y)
            }
        }
    }
}
