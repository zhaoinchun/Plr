package androidx.media3.common.audio

import android.util.SparseArray

import com.google.common.base.Preconditions
import java.nio.ByteBuffer

class ChannelMixingAudioProcessor : BaseAudioProcessor() {
    private val matrixByInputChannelCount: SparseArray<ChannelMixingMatrix?>

    init {
        matrixByInputChannelCount = SparseArray<ChannelMixingMatrix?>()
    }

    fun putChannelMixingMatrix(matrix: ChannelMixingMatrix) {
        val inputChannelCount = matrix.inputChannelCount
        matrixByInputChannelCount.put(inputChannelCount, matrix)
    }

    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!AudioMixingUtil.canMix(inputAudioFormat)) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        val channelMixingMatrix =
            matrixByInputChannelCount.get(inputAudioFormat.channelCount)
        if (channelMixingMatrix == null) {
            throw AudioProcessor.UnhandledAudioFormatException(
                String.format(
                    "No mixing matrix set for input channel count=%s. Please call"
                            + " putChannelMixingMatrix() with the appropriate matrix.",
                    inputAudioFormat.channelCount
                ),
                inputAudioFormat
            )
        }
        if (channelMixingMatrix.isIdentity) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate,
            channelMixingMatrix.outputChannelCount,
            inputAudioFormat.encoding
        )
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channelMixingMatrix = matrixByInputChannelCount.get(inputAudioFormat.channelCount)!!
        val framesToMix = inputBuffer.remaining() / inputAudioFormat.bytesPerFrame
        val outputBuffer = replaceOutputBuffer(framesToMix * outputAudioFormat.bytesPerFrame)
        AudioMixingUtil.mix(
            inputBuffer,
            inputAudioFormat,
            outputBuffer,
            outputAudioFormat,
            channelMixingMatrix,
            framesToMix,  
            false,  
            true
        )
        outputBuffer.flip()
    }
}
