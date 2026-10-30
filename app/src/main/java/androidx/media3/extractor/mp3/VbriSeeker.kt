package androidx.media3.extractor.mp3

import androidx.media3.common.C
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Util.binarySearchFloor
import androidx.media3.common.util.Util.sampleCountToDurationUs
import androidx.media3.extractor.MpegAudioUtil
import androidx.media3.extractor.SeekMap.SeekPoints
import androidx.media3.extractor.SeekPoint
import kotlin.math.max

internal class VbriSeeker private constructor(
    private val timesUs: LongArray,
    private val positions: LongArray,
    override val durationUs: Long,
    override val dataStartPosition: Long,
    override val dataEndPosition: Long
) : Seeker {
    override val averageBitrate: Int

    init {
        this.averageBitrate = Mp3Util.computeAverageBitrate(
            dataEndPosition - dataStartPosition,
            durationUs
        )
    }

    override val isSeekable: Boolean
        get() = true

    override fun getSeekPoints(timeUs: Long): SeekPoints {
        val tableIndex = binarySearchFloor(timesUs, timeUs, true, true)
        val seekPoint = SeekPoint(timesUs[tableIndex], positions[tableIndex])
        if (seekPoint.timeUs >= timeUs || tableIndex == timesUs.size - 1) {
            return SeekPoints(seekPoint)
        } else {
            val nextSeekPoint = SeekPoint(timesUs[tableIndex + 1], positions[tableIndex + 1])
            return SeekPoints(seekPoint, nextSeekPoint)
        }
    }

    override fun getTimeUs(position: Long): Long {
        return timesUs[binarySearchFloor(positions, position, true, true)]
    }

    companion object {
        private const val TAG = "VbriSeeker"

        fun create(
            inputLength: Long,
            position: Long,
            mpegAudioHeader: MpegAudioUtil.Header,
            frame: ParsableByteArray
        ): VbriSeeker? {
            var position = position
            frame.skipBytes(6)
            val bytes = frame.readInt()
            val startOfMp3Data = position + mpegAudioHeader.frameSize
            var endOfMp3Data = startOfMp3Data + bytes
            val numFrames = frame.readInt()
            if (numFrames <= 0) {
                return null
            }
            val sampleRate = mpegAudioHeader.sampleRate
            val durationUs =
                sampleCountToDurationUs(
                    (numFrames.toLong() * mpegAudioHeader.samplesPerFrame) - 1, sampleRate
                )
            val entryCount = frame.readUnsignedShort()
            val scale = frame.readUnsignedShort()
            val entrySize = frame.readUnsignedShort()
            frame.skipBytes(2)

            position += mpegAudioHeader.frameSize.toLong()
            // Read table of contents entries.
            val timesUs = LongArray(entryCount)
            val positions = LongArray(entryCount)
            for (index in 0..<entryCount) {
                timesUs[index] = (index * durationUs) / entryCount
                positions[index] = position
                val segmentSize: Int
                when (entrySize) {
                    1 -> segmentSize = frame.readUnsignedByte()
                    2 -> segmentSize = frame.readUnsignedShort()
                    3 -> segmentSize = frame.readUnsignedInt24()
                    4 -> segmentSize = frame.readUnsignedIntToInt()
                    else -> return null
                }
                position += segmentSize * (scale.toLong())
            }
            if (inputLength != C.LENGTH_UNSET.toLong() && inputLength != endOfMp3Data) {
                w(TAG, "VBRI data size mismatch: " + inputLength + ", " + endOfMp3Data)
            }
            if (endOfMp3Data != position) {
                w(
                    TAG,
                    ("VBRI bytes and ToC mismatch (using max): "
                            + endOfMp3Data
                            + ", "
                            + position
                            + "\nSeeking will be inaccurate.")
                )
                endOfMp3Data = max(endOfMp3Data, position)
            }

            return VbriSeeker(timesUs, positions, durationUs, startOfMp3Data, endOfMp3Data)
        }
    }
}
