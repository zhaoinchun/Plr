package androidx.media3.common.audio

import androidx.media3.common.C
import androidx.media3.common.Format

import androidx.media3.common.util.Util.constrainValue
import androidx.media3.common.util.Util.isEncodingLinearPcm
import java.lang.Double
import java.lang.Float
import java.lang.Long
import java.nio.ByteBuffer
import kotlin.IllegalStateException
import kotlin.Int
import kotlin.Short
import kotlin.Throws

class ToInt16PcmAudioProcessor : BaseAudioProcessor() {
    @Throws(AudioProcessor.UnhandledAudioFormatException::class)
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding: @C.PcmEncoding Int = inputAudioFormat.encoding
        if (!isEncodingLinearPcm(encoding)) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return if (encoding != C.ENCODING_PCM_16BIT)
            AudioProcessor.AudioFormat(
                inputAudioFormat.sampleRate, inputAudioFormat.channelCount, C.ENCODING_PCM_16BIT
            )
        else
            AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Prepare the output buffer.
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - position
        val resampledSize: Int
        resampledSize = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_8BIT -> size * 2
            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> size
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> (size / 3) * 2
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT_BIG_ENDIAN -> size / 2

            C.ENCODING_PCM_DOUBLE, C.ENCODING_PCM_DOUBLE_BIG_ENDIAN -> size / 4
            C.ENCODING_PCM_16BIT, C.ENCODING_INVALID, Format.NO_VALUE -> throw IllegalStateException()
            else -> throw IllegalStateException()
        }

        // Resample the little endian input and update the input/output buffers.
        val buffer = replaceOutputBuffer(resampledSize)
        when (inputAudioFormat!!.encoding) {
            C.ENCODING_PCM_8BIT ->         // 8 -> 16 bit resampling. Shift each byte from [0, 256) to [-128, 128) and scale up.
            {
                var i = position
                while (i < limit) {
                    buffer.put(0.toByte())
                    buffer.put(((inputBuffer[i].toInt() and 0xFF) - 128).toByte())
                    i++
                }
            }

            C.ENCODING_PCM_16BIT_BIG_ENDIAN ->         // Big endian to little endian resampling. Swap the byte order.
            {
                var i = position
                while (i < limit) {
                    buffer.put(inputBuffer.get(i + 1))
                    buffer.put(inputBuffer[i])
                    i += 2
                }
            }

            C.ENCODING_PCM_24BIT ->         // 24 -> 16 bit resampling. Drop the least significant byte.
            {
                var i = position
                while (i < limit) {
                    buffer.put(inputBuffer.get(i + 1))
                    buffer.put(inputBuffer.get(i + 2))
                    i += 3
                }
            }

            C.ENCODING_PCM_24BIT_BIG_ENDIAN ->         // 24 BE -> 16 bit resampling. Drop the least significant byte.
            {
                var i = position
                while (i < limit) {
                    buffer.put(inputBuffer.get(i + 1))
                    buffer.put(inputBuffer[i])
                    i += 3
                }
            }

            C.ENCODING_PCM_32BIT ->         // 32 -> 16 bit resampling. Drop the two least significant bytes.
            {
                var i = position
                while (i < limit) {
                    buffer.put(inputBuffer.get(i + 2))
                    buffer.put(inputBuffer.get(i + 3))
                    i += 4
                }
            }

            C.ENCODING_PCM_32BIT_BIG_ENDIAN ->         // 32 BE -> 16 bit resampling. Drop the two least significant bytes.
            {
                var i = position
                while (i < limit) {
                    buffer.put(inputBuffer.get(i + 1))
                    buffer.put(inputBuffer[i])
                    i += 4
                }
            }

            C.ENCODING_PCM_FLOAT ->         // 32 bit floating point -> 16 bit resampling. Floating point values are in the range
                // [-1.0, 1.0], so need to be scaled by Short.MAX_VALUE.
            {
                var i = position
                while (i < limit) {
                    // Clamp to avoid integer overflow if the floating point values exceed their nominal range
                    // [Internal ref: b/161204847].
                    val floatValue =
                        constrainValue(inputBuffer.getFloat(i),  -1f,  1f)
                    val shortValue = (floatValue * Short.MAX_VALUE).toInt().toShort()
                    buffer.put((shortValue.toInt() and 0xFF).toByte())
                    buffer.put(((shortValue.toInt() shr 8) and 0xFF).toByte())
                    i += 4
                }
            }

            C.ENCODING_PCM_FLOAT_BIG_ENDIAN ->         // 32 bit floating point -> 16 bit resampling. Floating point values are in the range
                // [-1.0, 1.0], so need to be scaled by Short.MAX_VALUE.
            {
                var i = position
                while (i < limit) {
                    // Clamp to avoid integer overflow if the floating point values exceed their nominal range
                    // [Internal ref: b/161204847].
                    val floatValue =
                        constrainValue(
                            Float.intBitsToFloat(Integer.reverseBytes(inputBuffer.getInt(i))),  
                            -1f,  
                            1f
                        )
                    val shortValue = (floatValue * Short.MAX_VALUE).toInt().toShort()
                    buffer.put((shortValue.toInt() and 0xFF).toByte())
                    buffer.put(((shortValue.toInt() shr 8) and 0xFF).toByte())
                    i += 4
                }
            }

            C.ENCODING_PCM_DOUBLE ->         // 64 bit floating point -> 16 bit resampling. Floating point values are in the range
                // [-1.0, 1.0], so need to be scaled by Short.MAX_VALUE.
            {
                var i = position
                while (i < limit) {
                    // Clamp to avoid integer overflow if the floating point values exceed their nominal range
                    // [Internal ref: b/161204847].
                    val doubleValue =
                        constrainValue(inputBuffer.getDouble(i),  -1.0,  1.0)
                    val shortValue = (doubleValue * Short.MAX_VALUE).toInt().toShort()
                    buffer.put((shortValue.toInt() and 0xFF).toByte())
                    buffer.put(((shortValue.toInt() shr 8) and 0xFF).toByte())
                    i += 8
                }
            }

            C.ENCODING_PCM_DOUBLE_BIG_ENDIAN ->         // 64 bit floating point -> 16 bit resampling. Floating point values are in the range
                // [-1.0, 1.0], so need to be scaled by Short.MAX_VALUE.
            {
                var i = position
                while (i < limit) {
                    // Clamp to avoid integer overflow if the floating point values exceed their nominal range
                    // [Internal ref: b/161204847].
                    val doubleValue =
                        constrainValue(
                            Double.longBitsToDouble(Long.reverseBytes(inputBuffer.getLong(i))),  
                            -1.0,  
                            1.0
                        )
                    val shortValue = (doubleValue * Short.MAX_VALUE).toInt().toShort()
                    buffer.put((shortValue.toInt() and 0xFF).toByte())
                    buffer.put(((shortValue.toInt() shr 8) and 0xFF).toByte())
                    i += 8
                }
            }

            C.ENCODING_PCM_16BIT, C.ENCODING_INVALID, Format.NO_VALUE ->         // Never happens.
                throw IllegalStateException()

            else ->
                throw IllegalStateException()
        }
        inputBuffer.position(inputBuffer.limit())
        buffer.flip()
    }
}
