
package androidx.media3.extractor.text

import androidx.media3.decoder.SimpleDecoder
import com.google.common.base.Preconditions
import java.nio.ByteBuffer

abstract class SimpleSubtitleDecoder
protected constructor(override val name: String) :
    SimpleDecoder<SubtitleInputBuffer, SubtitleOutputBuffer, SubtitleDecoderException>(
        2, 2
    ), SubtitleDecoder {
    init {
        setInitialInputBufferSize(1024)
    }

    override fun setPositionUs(positionUs: Long) {
        // Do nothing
    }

    override fun createInputBuffer(): SubtitleInputBuffer {
        return SubtitleInputBuffer()
    }

    override fun createOutputBuffer(): SubtitleOutputBuffer {
        return object : SubtitleOutputBuffer() {
            override fun release() {
                this@SimpleSubtitleDecoder.releaseOutputBuffer(this)
            }
        }
    }

    override fun createUnexpectedDecodeException(error: Throwable?): SubtitleDecoderException {
        return SubtitleDecoderException("Unexpected decode error", error)
    }

    override fun decode(
        inputBuffer: SubtitleInputBuffer,
        outputBuffer: SubtitleOutputBuffer,
        reset: Boolean
    ): SubtitleDecoderException? {
        try {
            val inputData = inputBuffer.data!!
            val subtitle = decode(inputData.array(), inputData.limit(), reset)
            outputBuffer.setContent(inputBuffer.timeUs, subtitle, inputBuffer.subsampleOffsetUs)
            outputBuffer.shouldBeSkipped = false // Skipping is handled by TextRenderer
            return null
        } catch (e: SubtitleDecoderException) {
            return e
        }
    }

    @Throws(SubtitleDecoderException::class)
    protected abstract fun decode(data: ByteArray, length: Int, reset: Boolean): Subtitle?
}
