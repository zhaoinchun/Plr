package androidx.media3.exoplayer.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor

import androidx.media3.common.util.Util.isEncodingLinearPcm
import com.google.common.primitives.Ints
import java.lang.Double
import java.lang.Float
import java.lang.Long
import java.lang.Short
import java.nio.ByteBuffer
import kotlin.IllegalStateException
import kotlin.Int
import kotlin.Throws

class ToFloatPcmAudioProcessor : BaseAudioProcessor() {
    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding: @C.PcmEncoding Int = inputAudioFormat.encoding
        if (!isEncodingLinearPcm(encoding)) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return if (encoding != C.ENCODING_PCM_FLOAT)
            AudioProcessor.AudioFormat(
                inputAudioFormat.sampleRate, inputAudioFormat.channelCount, C.ENCODING_PCM_FLOAT
            )
        else
            AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - position

        val buffer: ByteBuffer?
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_8BIT -> {
                buffer = replaceOutputBuffer(size * 4)
                var i = position
                while (i < limit) {
                    val pcm32BitInteger = (((inputBuffer[i].toInt() and 0xFF) - 128) shl 24)
                    writePcm32BitFloat(pcm32BitInteger, buffer)
                    i++
                }
            }

            C.ENCODING_PCM_16BIT -> {
                buffer = replaceOutputBuffer(size * 2)
                var i = position
                while (i < limit) {
                    val pcm32BitInteger = inputBuffer.getShort(i).toInt() shl 16
                    writePcm32BitFloat(pcm32BitInteger, buffer)
                    i += 2
                }
            }

            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> {
                buffer = replaceOutputBuffer(size * 2)
                var i = position
                while (i < limit) {
                    val pcm32BitInteger = Short.reverseBytes(inputBuffer.getShort(i)).toInt() shl 16
                    writePcm32BitFloat(pcm32BitInteger, buffer)
                    i += 2
                }
            }

            C.ENCODING_PCM_24BIT -> {
                buffer = replaceOutputBuffer((size / 3) * 4)
                var i = position
                while (i < limit) {
                    val pcm32BitInteger =
                        Ints.fromBytes(
                            inputBuffer.get(i + 2),
                            inputBuffer.get(i + 1),
                            inputBuffer[i],
                            0.toByte()
                        )
                    writePcm32BitFloat(pcm32BitInteger, buffer)
                    i += 3
                }
            }

            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> {
                buffer = replaceOutputBuffer((size / 3) * 4)
                var i = position
                while (i < limit) {
                    val pcm32BitInteger =
                        Ints.fromBytes(
                            inputBuffer[i],
                            inputBuffer.get(i + 1),
                            inputBuffer.get(i + 2),
                            0.toByte()
                        )
                    writePcm32BitFloat(pcm32BitInteger, buffer)
                    i += 3
                }
            }

            C.ENCODING_PCM_32BIT -> {
                buffer = replaceOutputBuffer(size)
                var i = position
                while (i < limit) {
                    val pcm32BitInteger = inputBuffer.getInt(i)
                    writePcm32BitFloat(pcm32BitInteger, buffer)
                    i += 4
                }
            }

            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> {
                buffer = replaceOutputBuffer(size)
                var i = position
                while (i < limit) {
                    val pcm32BitInteger = Integer.reverseBytes(inputBuffer.getInt(i))
                    writePcm32BitFloat(pcm32BitInteger, buffer)
                    i += 4
                }
            }

            C.ENCODING_PCM_FLOAT_BIG_ENDIAN -> {
                buffer = replaceOutputBuffer(size)
                var i = position
                while (i < limit) {
                    buffer.putFloat(Float.intBitsToFloat(Integer.reverseBytes(inputBuffer.getInt(i))))
                    i += 4
                }
            }

            C.ENCODING_PCM_DOUBLE -> {
                buffer = replaceOutputBuffer(size / 2)
                var i = position
                while (i < limit) {
                    buffer.putFloat(inputBuffer.getDouble(i).toFloat())
                    i += 8
                }
            }

            C.ENCODING_PCM_DOUBLE_BIG_ENDIAN -> {
                buffer = replaceOutputBuffer(size / 2)
                var i = position
                while (i < limit) {
                    buffer.putFloat(
                        Double.longBitsToDouble(Long.reverseBytes(inputBuffer.getLong(i))).toFloat()
                    )
                    i += 8
                }
            }

            C.ENCODING_PCM_FLOAT, C.ENCODING_INVALID, Format.NO_VALUE ->         // Never happens.
                throw IllegalStateException()

            else ->
                throw IllegalStateException()
        }

        inputBuffer.position(inputBuffer.limit())
        buffer.flip()
    }

    companion object {
        private const val PCM_32_BIT_INT_TO_PCM_32_BIT_FLOAT_FACTOR = 1.0 / 0x7FFFFFFF

        private fun writePcm32BitFloat(pcm32BitInt: Int, buffer: ByteBuffer) {
            val pcm32BitFloat = (PCM_32_BIT_INT_TO_PCM_32_BIT_FLOAT_FACTOR * pcm32BitInt).toFloat()
            buffer.putInt(if (Float.isNaN(pcm32BitFloat)) 0 else Float.floatToIntBits(pcm32BitFloat))
        }
    }
}
