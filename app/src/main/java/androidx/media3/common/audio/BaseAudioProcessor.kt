package androidx.media3.common.audio

import androidx.annotation.CallSuper
import java.nio.ByteBuffer
import java.nio.ByteOrder

abstract class BaseAudioProcessor : AudioProcessor {
    protected var inputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
    protected var outputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var pendingInputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var pendingOutputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var buffer= AudioProcessor.EMPTY_BUFFER
    private var outputBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat? {
        pendingInputAudioFormat = inputAudioFormat
        pendingOutputAudioFormat = onConfigure(inputAudioFormat)
        return if (isActive) pendingOutputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    @get:CallSuper
    override val isActive: Boolean
        get() = pendingOutputAudioFormat !== AudioProcessor.AudioFormat.NOT_SET

    override fun queueEndOfStream() {
        inputEnded = true
        onQueueEndOfStream()
    }

    @get:CallSuper
    override val output: ByteBuffer?
        get() {
            val outputBuffer: ByteBuffer? = this.outputBuffer
            this.outputBuffer = AudioProcessor.EMPTY_BUFFER
            return outputBuffer
        }

    @get:CallSuper
    override val isEnded: Boolean
        get() = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    @Deprecated("")
    override fun flush() {
        flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    override fun flush(streamMetadata: AudioProcessor.StreamMetadata) {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        inputAudioFormat = pendingInputAudioFormat
        outputAudioFormat = pendingOutputAudioFormat
        onFlush(streamMetadata)
    }

    override fun reset() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        buffer =AudioProcessor. EMPTY_BUFFER
        pendingInputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        pendingOutputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        inputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        outputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        onReset()
    }

    protected fun replaceOutputBuffer(size: Int): ByteBuffer {
        if (buffer.capacity() < size) {
            buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }
        outputBuffer = buffer
        return buffer
    }

    protected fun hasPendingOutput(): Boolean {
        return outputBuffer.hasRemaining()
    }

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    protected open fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return AudioProcessor.AudioFormat.NOT_SET
    }

    protected open fun onQueueEndOfStream() {
        // Do nothing.
    }

    @Deprecated("Use {@link #onFlush(StreamMetadata)} instead.")
    protected fun onFlush() {
        // Do nothing.
    }

    protected open fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        onFlush()
    }

    protected open fun onReset() {
        // Do nothing.
    }
}
