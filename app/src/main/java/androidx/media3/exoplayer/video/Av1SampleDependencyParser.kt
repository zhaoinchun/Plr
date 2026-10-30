package androidx.media3.exoplayer.video

import androidx.media3.container.ObuParser
import androidx.media3.container.ObuParser.split
import java.nio.ByteBuffer
import kotlin.math.min

class Av1SampleDependencyParser {
    private val delayedKeyFrameTruncatedSample = ByteBuffer.allocateDirect(MAX_BYTES_FROM_KEYFRAME_TO_READ)
    private var sequenceHeader: ObuParser.SequenceHeader? = null

    fun sampleLimitAfterSkippingNonReferenceFrame(
        sample: ByteBuffer, skipFrameHeaders: Boolean
    ): Int {
        if (delayedKeyFrameTruncatedSample.hasRemaining()) {
            updateSequenceHeaders(split(delayedKeyFrameTruncatedSample))
            emptyDelayedKeyFrameTruncatedSample()
        }
        val obuList = split(sample)
        updateSequenceHeaders(obuList)
        var skippedFramesCount = 0
        var last = obuList.size - 1
        while (last >= 0 && canSkipObu(obuList[last], skipFrameHeaders)) {
            if (obuList[last].type == ObuParser.OBU_FRAME || obuList[last].type == ObuParser.OBU_FRAME_HEADER) {
                skippedFramesCount++
            }
            last--
        }
        if (skippedFramesCount > 1 || last + 1 >= MAX_OBU_COUNT_FOR_PARTIAL_SKIP) {
            return sample.limit()
        }
        if (last >= 0) {
            return obuList[last].payload.limit()
        }
        return sample.position()
    }

    fun queueInputBuffer(sample: ByteBuffer) {
        // Copy a prefix of sample data into delayedKeyFrameTruncatedSample, preserving the sample
        // position and limit.
        val samplePosition = sample.position()
        val sampleLimit = sample.limit()
        sample.limit(min(sampleLimit, samplePosition + MAX_BYTES_FROM_KEYFRAME_TO_READ))

        delayedKeyFrameTruncatedSample.clear()
        delayedKeyFrameTruncatedSample.put(sample)
        delayedKeyFrameTruncatedSample.flip()

        sample.position(samplePosition)
        sample.limit(sampleLimit)
    }

    fun reset() {
        sequenceHeader = null
        emptyDelayedKeyFrameTruncatedSample()
    }

    private fun canSkipObu(obu: ObuParser.Obu, skipFrameHeaders: Boolean): Boolean {
        if (obu.type == ObuParser.OBU_TEMPORAL_DELIMITER || obu.type == ObuParser.OBU_PADDING) {
            return true
        }
        if (obu.type == ObuParser.OBU_FRAME_HEADER && !skipFrameHeaders) {
            return false
        }
        if ((obu.type == ObuParser.OBU_FRAME || obu.type == ObuParser.OBU_FRAME_HEADER) && sequenceHeader != null) {
            val frameHeader = ObuParser.FrameHeader.parse(sequenceHeader!!, obu)
            return frameHeader != null && !frameHeader.isDependedOn
        }
        return false
    }

    private fun updateSequenceHeaders(obuList: MutableList<ObuParser.Obu>) {
        for (i in obuList.indices) {
            if (obuList[i].type ==ObuParser. OBU_SEQUENCE_HEADER) {
                sequenceHeader = ObuParser.SequenceHeader.parse(obuList[i])
            }
        }
    }

    private fun emptyDelayedKeyFrameTruncatedSample() {
        delayedKeyFrameTruncatedSample.position(delayedKeyFrameTruncatedSample.limit())
    }

    companion object {
        private const val MAX_OBU_COUNT_FOR_PARTIAL_SKIP = 8
        private const val MAX_BYTES_FROM_KEYFRAME_TO_READ = 500
    }
}
