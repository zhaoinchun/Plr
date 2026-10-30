package androidx.media3.common.audio

import com.google.common.base.Preconditions
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.nio.ByteBuffer

class AudioProcessingPipeline(
    
    private val audioProcessors: List<AudioProcessor>
) {
    
    private val activeAudioProcessors: MutableList<AudioProcessor>

    private var outputBuffers: Array<ByteBuffer>

    var outputAudioFormat: AudioProcessor.AudioFormat?
        private set

    private var pendingOutputAudioFormat: AudioProcessor.AudioFormat

    private var inputEnded = false

    init {
        activeAudioProcessors = ArrayList<AudioProcessor>()
        outputBuffers = arrayOf()
        outputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        pendingOutputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
    }

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.equals(AudioProcessor.AudioFormat.NOT_SET)) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        var intermediateAudioFormat = inputAudioFormat

        for (i in audioProcessors.indices) {
            val audioProcessor = audioProcessors[i]
            val nextFormat: AudioProcessor.AudioFormat =
                audioProcessor.configure(intermediateAudioFormat)!!
            if (audioProcessor.isActive) {
                Preconditions.checkState(!nextFormat.equals(AudioProcessor.AudioFormat.NOT_SET))
                intermediateAudioFormat = nextFormat
            }
        }

        return intermediateAudioFormat.also { pendingOutputAudioFormat = it }
    }

    @Deprecated("Use {@link #flush(StreamMetadata)} instead.")
    fun flush() {
        flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    fun flush(streamMetadata: AudioProcessor.StreamMetadata) {
        activeAudioProcessors.clear()
        outputAudioFormat = pendingOutputAudioFormat
        inputEnded = false
        var previousStreamMetadata = streamMetadata

        for (i in audioProcessors.indices) {
            val audioProcessor = audioProcessors[i]
            audioProcessor.flush(previousStreamMetadata)
            if (!audioProcessor.isActive) {
                continue
            }
            previousStreamMetadata =
                previousStreamMetadata
                    .buildUpon()
                    .setPositionOffsetUs(
                        audioProcessor.getDurationAfterProcessorApplied(
                            previousStreamMetadata.positionOffsetUs
                        )
                    )
                    .build()
            activeAudioProcessors.add(audioProcessor)
        }

        outputBuffers = MutableList<ByteBuffer>(activeAudioProcessors.size){
            activeAudioProcessors.get(it).output!!
        }.toTypedArray()
    }

    val isOperational: Boolean
        
        get() = !activeAudioProcessors.isEmpty()

    fun queueInput(inputBuffer: ByteBuffer) {
        if (!this.isOperational || inputEnded) {
            return
        }
        processData(inputBuffer)
    }

    val output: ByteBuffer?
        
        get() {
            if (!this.isOperational) {
                return AudioProcessor.EMPTY_BUFFER
            }
            val outputBuffer = outputBuffers[this.finalOutputBufferIndex]
            if (outputBuffer.hasRemaining()) {
                return outputBuffer
            }

            processData(AudioProcessor.EMPTY_BUFFER)
            return outputBuffers[this.finalOutputBufferIndex]
        }

    fun queueEndOfStream() {
        if (!this.isOperational || inputEnded) {
            return
        }
        inputEnded = true
        activeAudioProcessors[0].queueEndOfStream()
    }

    val isEnded: Boolean
        
        get() = inputEnded
                && activeAudioProcessors.get(this.finalOutputBufferIndex).isEnded
                && !outputBuffers[this.finalOutputBufferIndex].hasRemaining()

    fun reset() {
        for (i in audioProcessors.indices) {
            val audioProcessor = audioProcessors[i]
            audioProcessor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            audioProcessor.reset()
        }
        activeAudioProcessors.clear()
        outputBuffers = arrayOf()
        outputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        pendingOutputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        inputEnded = false
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o !is AudioProcessingPipeline) {
            return false
        }
        val that = o
        if (this.audioProcessors.size != that.audioProcessors.size) {
            return false
        }
        for (i in this.audioProcessors.indices) {
            if (this.audioProcessors[i] !== that.audioProcessors[i]) {
                return false
            }
        }

        return true
    }

    override fun hashCode(): Int {
        return audioProcessors.hashCode()
    }

    private fun processData(inputBuffer: ByteBuffer) {
        var progressMade = true
        while (progressMade) {
            progressMade = false
            for (index in 0..this.finalOutputBufferIndex) {
                if (outputBuffers[index].hasRemaining()) {
                    // Processor at this index has output that has not been consumed. Do not queue input.
                    continue
                }

                val audioProcessor = activeAudioProcessors.get(index)

                if (audioProcessor.isEnded) {
                    if (!outputBuffers[index].hasRemaining() && index < this.finalOutputBufferIndex) {
                        activeAudioProcessors.get(index + 1).queueEndOfStream()
                    }
                    continue
                }

                val input =
                    if (index > 0)
                        outputBuffers[index - 1]
                    else
                        if (inputBuffer.hasRemaining()) inputBuffer else AudioProcessor.EMPTY_BUFFER
                val inputBytes = input.remaining().toLong()
                audioProcessor.queueInput(input)
                outputBuffers[index] = audioProcessor.output!!

                progressMade =
                    progressMade or ((inputBytes - input.remaining()) > 0 || outputBuffers[index].hasRemaining())
            }
        }
    }

    private val finalOutputBufferIndex: Int
        get() = outputBuffers.size - 1
}
