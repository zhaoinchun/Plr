
package androidx.media3.extractor.text.cea

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.decoder.DecoderOutputBuffer
import androidx.media3.extractor.text.Subtitle
import androidx.media3.extractor.text.SubtitleDecoder
import androidx.media3.extractor.text.SubtitleDecoderException
import androidx.media3.extractor.text.SubtitleInputBuffer
import androidx.media3.extractor.text.SubtitleOutputBuffer
import com.google.common.base.Preconditions
import java.util.ArrayDeque

 abstract class CeaDecoder : SubtitleDecoder {
    private val availableInputBuffers: ArrayDeque<CeaInputBuffer?>
    private val availableOutputBuffers: ArrayDeque<SubtitleOutputBuffer?>
    private val queuedInputBuffers: ArrayDeque<CeaInputBuffer?>

    private var dequeuedInputBuffer: CeaInputBuffer? = null
    private var playbackPositionUs: Long = 0
    private var queuedInputBufferCount: Long = 0
    private var outputStartTimeUs: Long

    init {
        availableInputBuffers = ArrayDeque<CeaInputBuffer?>()
        for (i in 0..<NUM_INPUT_BUFFERS) {
            availableInputBuffers.add(CeaInputBuffer())
        }
        availableOutputBuffers = ArrayDeque<SubtitleOutputBuffer?>()
        for (i in 0..<NUM_OUTPUT_BUFFERS) {
            availableOutputBuffers.add(CeaOutputBuffer(object :DecoderOutputBuffer.Owner<CeaOutputBuffer> {
                override fun releaseOutputBuffer(outputBuffer: CeaOutputBuffer?) {
                    this.releaseOutputBuffer(
                        outputBuffer!!
                    )
                }
            }))
        }
        queuedInputBuffers = ArrayDeque<CeaInputBuffer?>()
        outputStartTimeUs = C.TIME_UNSET
    }

    abstract override val name: String

    override fun setOutputStartTimeUs(outputStartTimeUs: Long) {
        this.outputStartTimeUs = outputStartTimeUs
    }

    override fun setPositionUs(positionUs: Long) {
        playbackPositionUs = positionUs
    }

    @Throws(SubtitleDecoderException::class)
    override fun dequeueInputBuffer(): SubtitleInputBuffer? {
        Preconditions.checkState(dequeuedInputBuffer == null)
        if (availableInputBuffers.isEmpty()) {
            return null
        }
        dequeuedInputBuffer = availableInputBuffers.pollFirst()
        return dequeuedInputBuffer
    }

    @Throws(SubtitleDecoderException::class)
    override fun queueInputBuffer(inputBuffer: SubtitleInputBuffer) {
        Preconditions.checkArgument(inputBuffer === dequeuedInputBuffer)
        val ceaInputBuffer: CeaInputBuffer = inputBuffer as CeaInputBuffer
        if (!ceaInputBuffer.isEndOfStream && ceaInputBuffer.timeUs != C.TIME_END_OF_SOURCE && outputStartTimeUs != C.TIME_UNSET && ceaInputBuffer.timeUs < outputStartTimeUs) {
            // We can start decoding anywhere in CEA formats, so discarding on the input side is fine.
            releaseInputBuffer(ceaInputBuffer)
        } else {
            ceaInputBuffer.queuedInputBufferCount = queuedInputBufferCount++
            queuedInputBuffers.add(ceaInputBuffer)
        }
        dequeuedInputBuffer = null
    }

    @Throws(SubtitleDecoderException::class)
    override fun dequeueOutputBuffer(): SubtitleOutputBuffer? {
        if (availableOutputBuffers.isEmpty()) {
            return null
        }
        while (!queuedInputBuffers.isEmpty()
            && queuedInputBuffers.peek()!!.timeUs <= playbackPositionUs
        ) {
            val inputBuffer: CeaInputBuffer = queuedInputBuffers.poll()!!

            if (inputBuffer.isEndOfStream) {
                // availableOutputBuffers.isEmpty() is checked at the top of the method, so this is safe.
                val outputBuffer: SubtitleOutputBuffer =
                    availableOutputBuffers.pollFirst()!!
                outputBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
                releaseInputBuffer(inputBuffer)
                return outputBuffer
            }

            decode(inputBuffer)

            if (this.isNewSubtitleDataAvailable) {
                val subtitle = createSubtitle()
                // availableOutputBuffers.isEmpty() is checked at the top of the method, so this is safe.
                val outputBuffer: SubtitleOutputBuffer =availableOutputBuffers.pollFirst()!!
                outputBuffer.setContent(inputBuffer.timeUs, subtitle, Format.OFFSET_SAMPLE_RELATIVE)
                releaseInputBuffer(inputBuffer)
                return outputBuffer
            }

            releaseInputBuffer(inputBuffer)
        }
        return null
    }

    private fun releaseInputBuffer(inputBuffer: CeaInputBuffer) {
        inputBuffer.clear()
        availableInputBuffers.add(inputBuffer)
    }

    protected fun releaseOutputBuffer(outputBuffer: SubtitleOutputBuffer) {
        outputBuffer.clear()
        availableOutputBuffers.add(outputBuffer)
    }

    override fun flush() {
        queuedInputBufferCount = 0
        playbackPositionUs = 0
        while (!queuedInputBuffers.isEmpty()) {
            releaseInputBuffer(queuedInputBuffers.poll()!!)
        }
        if (dequeuedInputBuffer != null) {
            releaseInputBuffer(dequeuedInputBuffer!!)
            dequeuedInputBuffer = null
        }
    }

    override fun release() {
        // Do nothing.
    }

    protected abstract val isNewSubtitleDataAvailable: Boolean

    protected abstract fun createSubtitle(): Subtitle?

    protected abstract fun decode(inputBuffer: SubtitleInputBuffer)

    protected val availableOutputBuffer: SubtitleOutputBuffer?
        get() = availableOutputBuffers.pollFirst()

    protected fun getPositionUs(): Long {
        return playbackPositionUs
    }

    private class CeaInputBuffer : SubtitleInputBuffer(), Comparable<CeaInputBuffer> {
         var queuedInputBufferCount: Long = 0

        override fun compareTo(other: CeaInputBuffer): Int {
            if (isEndOfStream != other.isEndOfStream) {
                return if (isEndOfStream) 1 else -1
            }
            var delta = timeUs - other.timeUs
            if (delta == 0L) {
                delta = queuedInputBufferCount - other.queuedInputBufferCount
                if (delta == 0L) {
                    return 0
                }
            }
            return if (delta > 0) 1 else -1
        }
    }

    private class CeaOutputBuffer(private val owner: Owner<CeaOutputBuffer>) :
        SubtitleOutputBuffer() {
        override fun release() {
            owner.releaseOutputBuffer(this)
        }
    }

    companion object {
        private const val NUM_INPUT_BUFFERS = 10
        private const val NUM_OUTPUT_BUFFERS = 2
    }
}
