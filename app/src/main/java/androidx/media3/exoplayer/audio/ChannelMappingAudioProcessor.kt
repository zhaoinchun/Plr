package androidx.media3.exoplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor

import androidx.media3.common.util.Util.getByteDepth
import androidx.media3.common.util.Util.getInt24
import androidx.media3.common.util.Util.isEncodingLinearPcm
import androidx.media3.common.util.Util.putInt24
import com.google.common.primitives.ImmutableIntArray
import java.nio.ByteBuffer

class ChannelMappingAudioProcessor : BaseAudioProcessor() {
    private var pendingOutputChannels: ImmutableIntArray? = null
    private var outputChannels: ImmutableIntArray? = null

    @Deprecated("Use {@link #setChannelMap(ImmutableIntArray)} instead.")
    fun setChannelMap(outputChannels: IntArray?) {
        setChannelMap(if (outputChannels == null) null else ImmutableIntArray.copyOf(outputChannels))
    }

    fun setChannelMap(outputChannels: ImmutableIntArray?) {
        pendingOutputChannels = outputChannels
    }

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val outputChannels = pendingOutputChannels ?: return AudioProcessor.AudioFormat.NOT_SET

        if (!isEncodingLinearPcm(inputAudioFormat.encoding)) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        val outputLength = outputChannels.length()
        var active = inputAudioFormat.channelCount != outputLength
        for (i in 0..<outputLength) {
            val channelIndex = outputChannels[i]
            if (channelIndex >= inputAudioFormat.channelCount) {
                throw AudioProcessor.UnhandledAudioFormatException(
                    "Channel map ($outputChannels) trying to access non-existent input channel.",
                    inputAudioFormat
                )
            }
            active = active or (channelIndex != i)
        }
        return if (active)
            AudioProcessor.AudioFormat(
                inputAudioFormat.sampleRate,
                outputLength,
                inputAudioFormat.encoding
            )
        else
            AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val outputChannels = this.outputChannels!!
        var position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val frameCount = (limit - position) / inputAudioFormat.bytesPerFrame
        val outputSize = frameCount * outputAudioFormat.bytesPerFrame
        val buffer = replaceOutputBuffer(outputSize)
        while (position < limit) {
            for (i in 0..<outputChannels.length()) {
                val channelIndex = outputChannels[i]
                val inputIndex: Int =
                    position + getByteDepth(inputAudioFormat.encoding) * channelIndex
                when (inputAudioFormat.encoding) {
                    C.ENCODING_PCM_8BIT -> buffer.put(inputBuffer.get(inputIndex))
                    C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> buffer.putShort(
                        inputBuffer.getShort(inputIndex)
                    )

                    C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> putInt24(
                        buffer,
                        getInt24(inputBuffer, inputIndex)
                    )

                    C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> buffer.putInt(
                        inputBuffer.getInt(inputIndex)
                    )

                    C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT_BIG_ENDIAN -> buffer.putFloat(
                        inputBuffer.getFloat(inputIndex)
                    )

                    C.ENCODING_PCM_DOUBLE, C.ENCODING_PCM_DOUBLE_BIG_ENDIAN -> buffer.putDouble(
                        inputBuffer.getDouble(inputIndex)
                    )

                    else -> throw IllegalStateException("Unexpected encoding: " + inputAudioFormat.encoding)
                }
            }
            position += inputAudioFormat.bytesPerFrame
        }
        inputBuffer.position(limit)
        buffer.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        outputChannels = pendingOutputChannels
    }

    override fun onReset() {
        outputChannels = null
        pendingOutputChannels = null
    }
}
