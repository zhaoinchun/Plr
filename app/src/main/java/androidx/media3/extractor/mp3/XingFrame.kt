package androidx.media3.extractor.mp3

import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Util.sampleCountToDurationUs
import androidx.media3.extractor.MpegAudioUtil
import kotlin.math.max

internal class XingFrame private constructor(
    header: MpegAudioUtil.Header,
    
    val frameCount: Long,
    
    val dataSize: Long,
    
    val tableOfContents: LongArray?,
    val replayGain: Mp3InfoReplayGain?,
    
    val encoderDelay: Int,
    
    val encoderPadding: Int
) {
    
    val header: MpegAudioUtil.Header

    init {
        this.header = MpegAudioUtil.Header(header)
    }

    fun computeDurationUs(): Long {
        if (frameCount == C.LENGTH_UNSET.toLong() || frameCount == 0L) {
            // If the frame count is missing/invalid, the header can't be used to determine the duration.
            return C.TIME_UNSET
        }
        var sampleCount = frameCount * header.samplesPerFrame
        if (encoderDelay != C.LENGTH_UNSET && encoderPadding != C.LENGTH_UNSET) {
            sampleCount -= (encoderDelay + encoderPadding).toLong()
        }
        if (sampleCount <= 0) {
            return C.TIME_UNSET
        }
        // Audio requires both a start and end PCM sample, so subtract one from the sample count before
        // calculating the duration.
        return sampleCountToDurationUs(sampleCount - 1, header.sampleRate)
    }

    val metadata: androidx.media3.common.Metadata?
        get() {
            if (replayGain != null) {
                return androidx.media3.common.Metadata(listOf(replayGain))
            }
            return null
        }

    companion object {
        private val LAME_TO_DECODED_PCM_TRIM_OFFSET_SAMPLES = 528 + 1

        fun parse(mpegAudioHeader: MpegAudioUtil.Header, frame: ParsableByteArray): XingFrame {
            val flags = frame.readInt()
            val frameCount =
                if ((flags and 0x01) != 0) frame.readUnsignedIntToInt() else C.LENGTH_UNSET
            val dataSize =
                if ((flags and 0x02) != 0) frame.readUnsignedInt() else C.LENGTH_UNSET.toLong()

            val tableOfContents: LongArray?
            if ((flags and 0x04) == 0x04) {
                tableOfContents = LongArray(100)
                for (i in 0..99) {
                    tableOfContents[i] = frame.readUnsignedByte().toLong()
                }
            } else {
                tableOfContents = null
            }

            if ((flags and 0x8) != 0) {
                frame.skipBytes(4) // Quality indicator
            }

            val replayGain: Mp3InfoReplayGain?
            var encoderDelay: Int
            var encoderPadding: Int
            // Skip: revision & VBR method (1), lowpass filter (1).
            val bytesToSkipBeforeReplayGain = 1 + 1
            // Skip: encoding flags & ATH type (1), bitrate (1).
            val bytesToSkipAfterReplayGain = 1 + 1
            // And account for values we parse: version string (9), ReplayGain (8) and encoder delay &
            // padding (3).
            if (frame.bytesLeft() >= 9 + bytesToSkipBeforeReplayGain + 8 + bytesToSkipAfterReplayGain + 3) {
                val encoderIdentifier = frame.readString(9)
                frame.skipBytes(bytesToSkipBeforeReplayGain)
                val peak = frame.readFloat()
                val field1 = frame.readUnsignedShort()
                val field2 = frame.readUnsignedShort()
                replayGain = Mp3InfoReplayGain.parse(peak, field1, field2)

                frame.skipBytes(bytesToSkipAfterReplayGain)
                val encoderDelayAndPadding = frame.readUnsignedInt24()
                encoderDelay = (encoderDelayAndPadding and 0xFFF000) shr 12
                encoderPadding = (encoderDelayAndPadding and 0xFFF)
                if (usesLameGaplessEncoding(encoderIdentifier)) {
                    encoderDelay += LAME_TO_DECODED_PCM_TRIM_OFFSET_SAMPLES
                    encoderPadding =
                        max(0, encoderPadding - LAME_TO_DECODED_PCM_TRIM_OFFSET_SAMPLES)
                }
            } else {
                replayGain = null
                encoderDelay = C.LENGTH_UNSET
                encoderPadding = C.LENGTH_UNSET
            }

            return XingFrame(
                mpegAudioHeader,
                frameCount.toLong(),
                dataSize,
                tableOfContents,
                replayGain,
                encoderDelay,
                encoderPadding
            )
        }

        private fun usesLameGaplessEncoding(encoderIdentifier: String): Boolean {
            return encoderIdentifier.startsWith("LAME")
                    || encoderIdentifier.startsWith("Lavf")
                    || encoderIdentifier.startsWith("Lavc")
        }
    }
}
