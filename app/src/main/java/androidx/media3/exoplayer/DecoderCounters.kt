package androidx.media3.exoplayer

import androidx.media3.common.util.Util.formatInvariant
import kotlin.math.max

class DecoderCounters {
    var decoderInitCount: Int = 0
    var decoderReleaseCount: Int = 0
    var queuedInputBufferCount: Int = 0
    var skippedInputBufferCount: Int = 0
    var renderedOutputBufferCount: Int = 0
    var skippedOutputBufferCount: Int = 0
    var droppedBufferCount: Int = 0
    var droppedInputBufferCount: Int = 0
    var maxConsecutiveDroppedBufferCount: Int = 0
    var droppedToKeyframeCount: Int = 0
    var totalVideoFrameProcessingOffsetUs: Long = 0
    var videoFrameProcessingOffsetCount: Int = 0

    @Synchronized
    fun ensureUpdated() {
        // Do nothing. The use of synchronized ensures a memory barrier should another thread also
        // call this method.
    }

    fun merge(other: DecoderCounters) {
        decoderInitCount += other.decoderInitCount
        decoderReleaseCount += other.decoderReleaseCount
        queuedInputBufferCount += other.queuedInputBufferCount
        skippedInputBufferCount += other.skippedInputBufferCount
        renderedOutputBufferCount += other.renderedOutputBufferCount
        skippedOutputBufferCount += other.skippedOutputBufferCount
        droppedBufferCount += other.droppedBufferCount
        droppedInputBufferCount += other.droppedInputBufferCount
        maxConsecutiveDroppedBufferCount =
            max(maxConsecutiveDroppedBufferCount, other.maxConsecutiveDroppedBufferCount)
        droppedToKeyframeCount += other.droppedToKeyframeCount
        addVideoFrameProcessingOffsets(
            other.totalVideoFrameProcessingOffsetUs, other.videoFrameProcessingOffsetCount
        )
    }

    fun addVideoFrameProcessingOffset(processingOffsetUs: Long) {
        addVideoFrameProcessingOffsets(processingOffsetUs,  1)
    }

    private fun addVideoFrameProcessingOffsets(totalProcessingOffsetUs: Long, count: Int) {
        totalVideoFrameProcessingOffsetUs += totalProcessingOffsetUs
        videoFrameProcessingOffsetCount += count
    }

    override fun toString(): String {
        return formatInvariant(
            ("DecoderCounters {\n "
                    + "decoderInits=%s,\n "
                    + "decoderReleases=%s\n "
                    + "queuedInputBuffers=%s\n "
                    + "skippedInputBuffers=%s\n "
                    + "renderedOutputBuffers=%s\n "
                    + "skippedOutputBuffers=%s\n "
                    + "droppedBuffers=%s\n "
                    + "droppedInputBuffers=%s\n "
                    + "maxConsecutiveDroppedBuffers=%s\n "
                    + "droppedToKeyframeEvents=%s\n "
                    + "totalVideoFrameProcessingOffsetUs=%s\n "
                    + "videoFrameProcessingOffsetCount=%s\n}"),
            decoderInitCount,
            decoderReleaseCount,
            queuedInputBufferCount,
            skippedInputBufferCount,
            renderedOutputBufferCount,
            skippedOutputBufferCount,
            droppedBufferCount,
            droppedInputBufferCount,
            maxConsecutiveDroppedBufferCount,
            droppedToKeyframeCount,
            totalVideoFrameProcessingOffsetUs,
            videoFrameProcessingOffsetCount
        )
    }
}
