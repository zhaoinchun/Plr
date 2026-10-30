package androidx.media3.exoplayer.audio

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor

import androidx.media3.common.util.Util
import androidx.media3.common.util.Util.constrainValue
import androidx.media3.common.util.Util.isEncodingLinearPcm
import androidx.media3.common.util.Util.sampleCountToDurationUs
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

class TrimmingAudioProcessor : BaseAudioProcessor() {
    private var trimStartFrames = 0
    private var trimEndFrames = 0
    private var reconfigurationPending = false

    private var pendingTrimStartBytes = 0
    private var endBuffer: ByteArray
    private var endBufferSize = 0

    var trimmedFrameCount: Long = 0
        private set

    init {
        endBuffer = Util.EMPTY_BYTE_ARRAY
    }

    fun setTrimFrameCount(trimStartFrames: Int, trimEndFrames: Int) {
        this.trimStartFrames = trimStartFrames
        this.trimEndFrames = trimEndFrames
    }

    fun resetTrimmedFrameCount() {
        trimmedFrameCount = 0
    }

    override fun getDurationAfterProcessorApplied(durationUs: Long): Long {
        return max(
            0,
            durationUs - sampleCountToDurationUs(
                (trimEndFrames + trimStartFrames).toLong(), inputAudioFormat.sampleRate
            )
        )
    }

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!isEncodingLinearPcm(inputAudioFormat.encoding)) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        reconfigurationPending = true
        return if (trimStartFrames != 0 || trimEndFrames != 0) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        var remaining = limit - position

        if (remaining == 0) {
            return
        }

        // Trim any pending start bytes from the input buffer.
        val trimBytes = min(remaining, pendingTrimStartBytes)
        trimmedFrameCount += (trimBytes / inputAudioFormat.bytesPerFrame).toLong()
        pendingTrimStartBytes -= trimBytes
        inputBuffer.position(position + trimBytes)
        if (pendingTrimStartBytes > 0) {
            // Nothing to output yet.
            return
        }
        remaining -= trimBytes

        // endBuffer must be kept as full as possible, so that we trim the right amount of media if we
        // don't receive any more input. After taking into account the number of bytes needed to keep
        // endBuffer as full as possible, the output should be any surplus bytes currently in endBuffer
        // followed by any surplus bytes in the new inputBuffer.
        var remainingBytesToOutput = endBufferSize + remaining - endBuffer.size
        val buffer = replaceOutputBuffer(remainingBytesToOutput)

        // Output from endBuffer.
        val endBufferBytesToOutput = constrainValue(remainingBytesToOutput, 0, endBufferSize)
        buffer.put(endBuffer, 0, endBufferBytesToOutput)
        remainingBytesToOutput -= endBufferBytesToOutput

        // Output from inputBuffer, restoring its limit afterwards.
        val inputBufferBytesToOutput = constrainValue(remainingBytesToOutput, 0, remaining)
        inputBuffer.limit(inputBuffer.position() + inputBufferBytesToOutput)
        buffer.put(inputBuffer)
        inputBuffer.limit(limit)
        remaining -= inputBufferBytesToOutput

        // Compact endBuffer, then repopulate it using the new input.
        endBufferSize -= endBufferBytesToOutput
        System.arraycopy(endBuffer, endBufferBytesToOutput, endBuffer, 0, endBufferSize)
        inputBuffer.get(endBuffer, endBufferSize, remaining)
        endBufferSize += remaining

        buffer.flip()
    }

    override val output: ByteBuffer
        get() {
            if (super.isEnded && endBufferSize > 0) {
                // Because audio processors may be drained in the middle of the stream we assume that the
                // contents of the end buffer need to be output. For gapless transitions, configure will
                // always be called, so the end buffer is cleared in onQueueEndOfStream.
                replaceOutputBuffer(endBufferSize).put(endBuffer, 0, endBufferSize).flip()
                endBufferSize = 0
            }
            return super.output!!
        }

    override val isEnded: Boolean
        get() = super.isEnded && endBufferSize == 0

    override fun onQueueEndOfStream() {
        if (reconfigurationPending) {
            // Trim audio in the end buffer.
            if (endBufferSize > 0) {
                trimmedFrameCount += (endBufferSize / inputAudioFormat.bytesPerFrame).toLong()
            }
            endBufferSize = 0
        }
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        if (reconfigurationPending) {
            // Flushing activates the new configuration, so prepare to trim bytes from the start/end.
            reconfigurationPending = false
            endBuffer = ByteArray(trimEndFrames * inputAudioFormat.bytesPerFrame)
            pendingTrimStartBytes = trimStartFrames * inputAudioFormat.bytesPerFrame
        }

        // Any data in the end buffer should no longer be output if we are playing from a different
        // position, so discard it and refill the buffer using new input.
        endBufferSize = 0
    }

    override fun onReset() {
        endBuffer = Util.EMPTY_BYTE_ARRAY
    }
}
