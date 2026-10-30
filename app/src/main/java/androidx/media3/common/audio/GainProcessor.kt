package androidx.media3.common.audio

import androidx.annotation.IntRange
import androidx.media3.common.C

import androidx.media3.common.util.Util.durationUsToSampleCount
import com.google.common.base.Preconditions
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.nio.ByteBuffer
import kotlin.math.min

class GainProcessor(private val gainProvider: GainProvider) : BaseAudioProcessor() {

    interface GainProvider {
        fun getGainFactorAtSamplePosition(
            @IntRange(from = 0) samplePosition: Long, @IntRange(from = 1) sampleRate: Int
        ): Float

        fun isUnityUntil(
            @IntRange(from = 0) samplePosition: Long,
            @IntRange(from = 1) sampleRate: Int
        ): Long
    }

    private var readFrames: Long = 0

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(
                "Invalid PCM encoding. Expected 16 bit PCM or float PCM.", inputAudioFormat
            )
        }
        return inputAudioFormat
    }

    override val isActive: Boolean
        get() = super.isActive
                && (inputAudioFormat != AudioProcessor.AudioFormat.NOT_SET) && (gainProvider.isUnityUntil( 
            0,
            inputAudioFormat.sampleRate
        )
                != C.TIME_END_OF_SOURCE)

    override fun queueInput(inputBuffer: ByteBuffer) {
        Preconditions.checkState(
            inputAudioFormat != AudioProcessor.AudioFormat.NOT_SET,
            "Audio processor must be configured and flushed before calling queueInput()."
        )

        if (!inputBuffer.hasRemaining()) {
            return
        }

        Preconditions.checkArgument(
            inputBuffer.remaining() % inputAudioFormat.bytesPerFrame == 0,
            "Queued an incomplete frame."
        )

        val buffer = replaceOutputBuffer(inputBuffer.remaining())

        // Each iteration handles one frame.
        while (inputBuffer.hasRemaining()) {
            val gain =
                gainProvider.getGainFactorAtSamplePosition(
                    readFrames,
                    inputAudioFormat.sampleRate
                )
            if (gain == 1f) {
                val oldLimit = inputBuffer.limit()

                val regionEnd = gainProvider.isUnityUntil(readFrames, inputAudioFormat.sampleRate)
                Preconditions.checkState(
                    regionEnd != C.TIME_UNSET,
                    "Expected a valid end boundary for unity region."
                )

                // Only set limit if unity does not last until EoS.
                if (regionEnd != C.TIME_END_OF_SOURCE) {
                    val limitOffsetBytes =
                        (regionEnd - readFrames) * inputAudioFormat.bytesPerFrame
                    inputBuffer.limit(
                        min(
                            oldLimit,
                            limitOffsetBytes.toInt() + inputBuffer.position()
                        )
                    )
                }

                readFrames += (inputBuffer.remaining() / inputAudioFormat.bytesPerFrame).toLong()
                buffer.put(inputBuffer)
                inputBuffer.limit(oldLimit)
            } else {
                for (i in 0..<inputAudioFormat.channelCount) {
                    when (inputAudioFormat.encoding) {
                        C.ENCODING_PCM_16BIT -> buffer.putShort(
                            (inputBuffer.getShort() * gain).toInt().toShort()
                        )

                        C.ENCODING_PCM_FLOAT -> buffer.putFloat(inputBuffer.getFloat() * gain)
                        else -> throw IllegalStateException(
                            "Unexpected PCM encoding: " + inputAudioFormat.encoding
                        )
                    }
                }
                readFrames++
            }
        }
        buffer.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        readFrames =
            durationUsToSampleCount(streamMetadata.positionOffsetUs, inputAudioFormat.sampleRate)
    }

    override fun onReset() {
        readFrames = 0
    }
}
