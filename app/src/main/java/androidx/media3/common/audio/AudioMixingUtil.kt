package androidx.media3.common.audio

import androidx.media3.common.C
import androidx.media3.common.Format

import androidx.media3.common.util.Util.constrainValue
import java.nio.ByteBuffer

object AudioMixingUtil {
    // Float PCM samples are zero-centred within the [-1.0, 1.0] range.
    private val FLOAT_PCM_MIN_VALUE = -1.0f
    private const val FLOAT_PCM_MAX_VALUE = 1.0f

    fun canMix(audioFormat: AudioProcessor.AudioFormat): Boolean {
        if (audioFormat.sampleRate == Format.NO_VALUE) {
            return false
        }
        if (audioFormat.channelCount == Format.NO_VALUE) {
            return false
        }
        return audioFormat.encoding == C.ENCODING_PCM_16BIT
                || audioFormat.encoding == C.ENCODING_PCM_FLOAT
    }

    fun canMix(
        inputAudioFormat: AudioProcessor.AudioFormat,
        outputAudioFormat: AudioProcessor.AudioFormat
    ): Boolean {
        if (inputAudioFormat.sampleRate != outputAudioFormat.sampleRate) {
            return false
        }
        if (!canMix(inputAudioFormat)) {
            return false
        }
        if (!canMix(outputAudioFormat)) {
            return false
        }
        return true
    }
    fun mix(
        inputBuffer: ByteBuffer,
        inputAudioFormat: AudioProcessor.AudioFormat,
        mixingBuffer: ByteBuffer,
        mixingAudioFormat: AudioProcessor.AudioFormat,
        matrix: ChannelMixingMatrix,
        framesToMix: Int,
        accumulate: Boolean,
        clipFloatOutput: Boolean
    ): ByteBuffer {
        val int16Input = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        val int16Output = mixingAudioFormat.encoding == C.ENCODING_PCM_16BIT
        val inputChannels = matrix.inputChannelCount
        val outputChannels = matrix.outputChannelCount
        val inputFrame = FloatArray(inputChannels)
        val outputFrame = FloatArray(outputChannels)

        for (i in 0..<framesToMix) {
            if (accumulate) {
                val position = mixingBuffer.position()
                for (outputChannel in 0..<outputChannels) {
                    outputFrame[outputChannel] =
                        getPcmSample(mixingBuffer,  int16Output, int16Output)
                }
                mixingBuffer.position(position)
            }

            for (inputChannel in 0..<inputChannels) {
                inputFrame[inputChannel] =
                    getPcmSample(inputBuffer,  int16Input, int16Output)
            }

            for (outputChannel in 0..<outputChannels) {
                for (inputChannel in 0..<inputChannels) {
                    outputFrame[outputChannel] +=
                        inputFrame[inputChannel] * matrix.getMixingCoefficient(
                            inputChannel,
                            outputChannel
                        )
                }

                if (int16Output) {
                    mixingBuffer.putShort(
                        constrainValue(
                            outputFrame[outputChannel],
                            Short.MIN_VALUE.toFloat(),
                            Short.MAX_VALUE.toFloat()
                        ) as Short
                    )
                } else {
                    mixingBuffer.putFloat(
                        if (clipFloatOutput)
                            constrainValue(
                                outputFrame[outputChannel], FLOAT_PCM_MIN_VALUE, FLOAT_PCM_MAX_VALUE
                            )
                        else
                            outputFrame[outputChannel]
                    )
                }

                outputFrame[outputChannel] = 0f
            }
        }
        return mixingBuffer
    }

    private fun getPcmSample(
        buffer: ByteBuffer,
        int16Buffer: Boolean,
        int16Output: Boolean
    ): Float {
        if (int16Output) {
            return if (int16Buffer) buffer.getShort()
                .toFloat() else floatSampleToInt16Pcm(buffer.getFloat())
        } else {
            return if (int16Buffer) int16SampleToFloatPcm(buffer.getShort()) else buffer.getFloat()
        }
    }

    private fun floatSampleToInt16Pcm(floatPcmValue: Float): Float {
        return constrainValue(
            floatPcmValue * (if (floatPcmValue < 0) -Short.MIN_VALUE else Short.MAX_VALUE.toInt()),
            Short.MIN_VALUE.toFloat(),
            Short.MAX_VALUE.toFloat()
        )
    }

    private fun int16SampleToFloatPcm(shortPcmValue: Short): Float {
        // Short.MIN_VALUE != -Short.MAX_VALUE, so use different conversion for positive and negative.
        return shortPcmValue / (if (shortPcmValue < 0) -Short.MIN_VALUE else Short.MAX_VALUE.toInt()).toFloat()
    }
}
