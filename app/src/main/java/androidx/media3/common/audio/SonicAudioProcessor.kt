package androidx.media3.common.audio

import androidx.annotation.FloatRange
import androidx.media3.common.C
import androidx.media3.common.Format

import androidx.media3.common.util.Util.scaleLargeTimestamp
import com.google.common.base.Preconditions
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class SonicAudioProcessor internal constructor(keepActiveWithDefaultParameters: Boolean) :
    AudioProcessor {
    private val shouldBeActiveWithDefaultParameters = keepActiveWithDefaultParameters
    private var pendingOutputSampleRate = SAMPLE_RATE_NO_CHANGE
    private var speed = 1f
    private var pitch = 1f

    private var pendingInputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var pendingOutputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var inputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outputAudioFormat = AudioProcessor.AudioFormat.NOT_SET

    private var pendingSonicRecreation = false
    private var sonic: Sonic? = null
    private var buffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer? = AudioProcessor.EMPTY_BUFFER
    private var inputBytes: Long = 0
    private var outputBytes: Long = 0
    private var inputEnded = false

    constructor() : this( false)

    fun setSpeed(@FloatRange(from = 0.0, fromInclusive = false) speed: Float) {
        Preconditions.checkArgument(speed > 0f)
        if (this.speed != speed) {
            this.speed = speed
            pendingSonicRecreation = true
        }
    }

    fun setPitch(@FloatRange(from = 0.0, fromInclusive = false) pitch: Float) {
        Preconditions.checkArgument(pitch > 0f)
        if (this.pitch != pitch) {
            this.pitch = pitch
            pendingSonicRecreation = true
        }
    }

    fun setOutputSampleRateHz(sampleRateHz: Int) {
        Preconditions.checkArgument(sampleRateHz == SAMPLE_RATE_NO_CHANGE || sampleRateHz > 0)
        pendingOutputSampleRate = sampleRateHz
    }

    fun getMediaDuration(playoutDuration: Long): Long {
        if (outputBytes >= MIN_BYTES_FOR_DURATION_SCALING_CALCULATION) {
            val processedInputBytes = inputBytes - sonic!!.pendingInputBytes
            return if (outputAudioFormat.sampleRate == inputAudioFormat.sampleRate)
                scaleLargeTimestamp(playoutDuration, processedInputBytes, outputBytes)
            else
                scaleLargeTimestamp(
                    playoutDuration,
                    processedInputBytes * outputAudioFormat.sampleRate,
                    outputBytes * inputAudioFormat.sampleRate
                )
        } else {
            return (speed.toDouble() * playoutDuration).toLong()
        }
    }

    fun getPlayoutDuration(mediaDuration: Long): Long {
        if (outputBytes >= MIN_BYTES_FOR_DURATION_SCALING_CALCULATION) {
            val processedInputBytes = inputBytes - sonic!!.pendingInputBytes
            return if (outputAudioFormat.sampleRate == inputAudioFormat.sampleRate)
                scaleLargeTimestamp(mediaDuration, outputBytes, processedInputBytes)
            else
                scaleLargeTimestamp(
                    mediaDuration,
                    outputBytes * inputAudioFormat.sampleRate,
                    processedInputBytes * outputAudioFormat.sampleRate
                )
        } else {
            return (mediaDuration / speed.toDouble()).toLong()
        }
    }

    val processedInputBytes: Long
        get() = inputBytes - sonic!!.pendingInputBytes

    override fun getDurationAfterProcessorApplied(durationUs: Long): Long {
        return getPlayoutDuration(durationUs)
    }

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT
            && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        val outputSampleRateHz =
            if (pendingOutputSampleRate == SAMPLE_RATE_NO_CHANGE)
                inputAudioFormat.sampleRate
            else
                pendingOutputSampleRate
        pendingInputAudioFormat = inputAudioFormat
        pendingOutputAudioFormat =
            AudioProcessor.AudioFormat(
                outputSampleRateHz, inputAudioFormat.channelCount, inputAudioFormat.encoding
            )
        pendingSonicRecreation = true
        return pendingOutputAudioFormat
    }

    override val isActive: Boolean
        get() = pendingOutputAudioFormat.sampleRate != Format.NO_VALUE
                && (shouldBeActiveWithDefaultParameters || !areParametersSetToDefaultValues())

    private fun areParametersSetToDefaultValues(): Boolean {
        return abs(speed - 1f) < CLOSE_THRESHOLD && abs(pitch - 1f) < CLOSE_THRESHOLD && pendingOutputAudioFormat.sampleRate == pendingInputAudioFormat.sampleRate
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) {
            return
        }
        val inputSize = inputBuffer.remaining()
        inputBytes += inputSize.toLong()
        sonic!!.queueInput(inputBuffer)
    }

    override fun queueEndOfStream() {
        sonic?.queueEndOfStream()
        inputEnded = true
    }

    override val output: ByteBuffer?
        get() {
            val sonic = this.sonic
            if (sonic != null) {
                val outputSize = sonic.outputSize
                if (outputSize > 0) {
                    if (buffer.capacity() < outputSize) {
                        buffer = ByteBuffer.allocateDirect(outputSize)
                            .order(ByteOrder.nativeOrder())
                    } else {
                        buffer.clear()
                    }
                    sonic.getOutput(buffer)
                    buffer.flip()
                    outputBytes += outputSize.toLong()
                    outputBuffer = buffer
                }
            }
            val outputBuffer: ByteBuffer? = this.outputBuffer
            this.outputBuffer = AudioProcessor.EMPTY_BUFFER
            return outputBuffer
        }

    override val isEnded: Boolean
        get() = inputEnded && (sonic == null || sonic!!.outputSize == 0)

    override fun flush(streamMetadata: AudioProcessor.StreamMetadata) {
        if (isActive) {
            inputAudioFormat = pendingInputAudioFormat
            outputAudioFormat = pendingOutputAudioFormat
            if (pendingSonicRecreation) {
                sonic =
                    Sonic(
                        inputAudioFormat.sampleRate,
                        inputAudioFormat.channelCount,
                        speed,
                        pitch,
                        outputAudioFormat.sampleRate,
                        inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
                    )
            } else  {
                sonic?.flush()
            }
        }
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputBytes = 0
        outputBytes = 0
        inputEnded = false
    }

    override fun reset() {
        speed = 1f
        pitch = 1f
        pendingInputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        pendingOutputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        inputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        outputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        buffer = AudioProcessor.EMPTY_BUFFER
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        pendingOutputSampleRate = SAMPLE_RATE_NO_CHANGE
        pendingSonicRecreation = false
        sonic = null
        inputBytes = 0
        outputBytes = 0
        inputEnded = false
    }

    companion object {

        val SAMPLE_RATE_NO_CHANGE: Int = -1

        private const val CLOSE_THRESHOLD = 0.0001f

        private const val MIN_BYTES_FOR_DURATION_SCALING_CALCULATION = 1024
    }
}
