package androidx.media3.exoplayer.audio

import androidx.media3.common.C

import androidx.media3.common.util.Util.constrainValue
import java.lang.Double
import java.lang.Float
import java.lang.Long
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.IllegalStateException
import kotlin.Int

object PcmAudioUtil {
    fun rampUpVolume(
        buffer: ByteBuffer,
        pcmEncoding: @C.Encoding Int,
        pcmFrameSize: Int,
        startFrameIndex: Int,
        rampFrameCount: Int
    ): ByteBuffer {
        val outputBuffer =
            ByteBuffer.allocateDirect(buffer.remaining()).order(ByteOrder.nativeOrder())
        var frameIndex = startFrameIndex
        var frameStartPosition = buffer.position()
        while (buffer.hasRemaining() && frameIndex < rampFrameCount) {
            var pcm32Bit = readAs32BitIntPcm(buffer, pcmEncoding).toLong()
            pcm32Bit = pcm32Bit * frameIndex / rampFrameCount
            write32BitIntPcm(outputBuffer, pcm32Bit.toInt(), pcmEncoding)
            if (buffer.position() == frameStartPosition + pcmFrameSize) {
                frameIndex++
                frameStartPosition = buffer.position()
            }
        }
        outputBuffer.put(buffer)
        outputBuffer.flip()
        return outputBuffer
    }

    fun readAs32BitIntPcm(buffer: ByteBuffer, pcmEncoding: @C.Encoding Int): Int {
        when (pcmEncoding) {
            C.ENCODING_PCM_8BIT -> return (buffer.get().toInt() and 0xFF) shl 24
            C.ENCODING_PCM_16BIT -> return ((buffer.get()
                .toInt() and 0xFF) shl 16) or ((buffer.get().toInt() and 0xFF) shl 24)

            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> return ((buffer.get()
                .toInt() and 0xFF) shl 24) or ((buffer.get().toInt() and 0xFF) shl 16)

            C.ENCODING_PCM_24BIT -> return (((buffer.get().toInt() and 0xFF) shl 8)
                    or ((buffer.get().toInt() and 0xFF) shl 16)
                    or ((buffer.get().toInt() and 0xFF) shl 24))

            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> return (((buffer.get().toInt() and 0xFF) shl 24)
                    or ((buffer.get().toInt() and 0xFF) shl 16)
                    or ((buffer.get().toInt() and 0xFF) shl 8))

            C.ENCODING_PCM_32BIT -> return ((buffer.get().toInt() and 0xFF)
                    or ((buffer.get().toInt() and 0xFF) shl 8)
                    or ((buffer.get().toInt() and 0xFF) shl 16)
                    or ((buffer.get().toInt() and 0xFF) shl 24))

            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> return (((buffer.get().toInt() and 0xFF) shl 24)
                    or ((buffer.get().toInt() and 0xFF) shl 16)
                    or ((buffer.get().toInt() and 0xFF) shl 8)
                    or (buffer.get().toInt() and 0xFF))

            C.ENCODING_PCM_FLOAT -> {
                val floatValue = constrainValue(buffer.getFloat(),  -1f,  1f)
                if (floatValue < 0) {
                    return (-floatValue * Int.MIN_VALUE).toInt()
                } else {
                    return (floatValue * Int.MAX_VALUE).toInt()
                }
            }

            C.ENCODING_PCM_FLOAT_BIG_ENDIAN -> {
                val floatBeValue = constrainValue(
                    Float.intBitsToFloat(Integer.reverseBytes(buffer.getInt())), -1f, 1f
                )
                return if (floatBeValue < 0) {
                    (-floatBeValue * Int.MIN_VALUE).toInt()
                } else {
                    (floatBeValue * Int.MAX_VALUE).toInt()
                }
            }

            C.ENCODING_PCM_DOUBLE -> {
                val doubleValue =
                    constrainValue(buffer.getDouble(),  -1.0,  1.0)
                return if (doubleValue < 0) {
                    (-doubleValue * Int.MIN_VALUE).toInt()
                } else {
                    (doubleValue * Int.MAX_VALUE).toInt()
                }
            }

            C.ENCODING_PCM_DOUBLE_BIG_ENDIAN -> {
                val doubleBeValue =
                    constrainValue(
                        Double.longBitsToDouble(Long.reverseBytes(buffer.getLong())),  
                        -1.0,  
                        1.0
                    )
                return if (doubleBeValue < 0) {
                    (-doubleBeValue * Int.MIN_VALUE).toInt()
                } else {
                    (doubleBeValue * Int.MAX_VALUE).toInt()
                }
            }

            else -> throw IllegalStateException()
        }
    }

    fun write32BitIntPcm(
        buffer: ByteBuffer, pcm32bit: Int, pcmEncoding: @C.Encoding Int
    ) {
        when (pcmEncoding) {
            C.ENCODING_PCM_32BIT -> {
                buffer.put(pcm32bit.toByte())
                buffer.put((pcm32bit shr 8).toByte())
                buffer.put((pcm32bit shr 16).toByte())
                buffer.put((pcm32bit shr 24).toByte())
                return
            }

            C.ENCODING_PCM_24BIT -> {
                buffer.put((pcm32bit shr 8).toByte())
                buffer.put((pcm32bit shr 16).toByte())
                buffer.put((pcm32bit shr 24).toByte())
                return
            }

            C.ENCODING_PCM_16BIT -> {
                buffer.put((pcm32bit shr 16).toByte())
                buffer.put((pcm32bit shr 24).toByte())
                return
            }

            C.ENCODING_PCM_8BIT -> {
                buffer.put((pcm32bit shr 24).toByte())
                return
            }

            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> {
                buffer.put((pcm32bit shr 24).toByte())
                buffer.put((pcm32bit shr 16).toByte())
                buffer.put((pcm32bit shr 8).toByte())
                buffer.put(pcm32bit.toByte())
                return
            }

            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> {
                buffer.put((pcm32bit shr 24).toByte())
                buffer.put((pcm32bit shr 16).toByte())
                buffer.put((pcm32bit shr 8).toByte())
                return
            }

            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> {
                buffer.put((pcm32bit shr 24).toByte())
                buffer.put((pcm32bit shr 16).toByte())
                return
            }

            C.ENCODING_PCM_FLOAT -> {
                if (pcm32bit < 0) {
                    buffer.putFloat(-(pcm32bit.toFloat()) / Int.MIN_VALUE)
                } else {
                    buffer.putFloat(pcm32bit.toFloat() / Int.MAX_VALUE)
                }
                return
            }

            C.ENCODING_PCM_FLOAT_BIG_ENDIAN -> {
                val floatValue = if (pcm32bit < 0) {
                    -(pcm32bit.toFloat()) / Int.MIN_VALUE
                } else {
                    pcm32bit.toFloat() / Int.MAX_VALUE
                }
                buffer.putInt(Integer.reverseBytes(Float.floatToIntBits(floatValue)))
                return
            }

            C.ENCODING_PCM_DOUBLE -> {
                if (pcm32bit < 0) {
                    buffer.putDouble(-(pcm32bit.toDouble()) / Int.MIN_VALUE)
                } else {
                    buffer.putDouble(pcm32bit.toDouble() / Int.MAX_VALUE)
                }
                return
            }

            C.ENCODING_PCM_DOUBLE_BIG_ENDIAN -> {
                val doubleValue = if (pcm32bit < 0) {
                    -(pcm32bit.toDouble()) / Int.MIN_VALUE
                } else {
                    pcm32bit.toDouble() / Int.MAX_VALUE
                }
                buffer.putLong(Long.reverseBytes(Double.doubleToLongBits(doubleValue)))
                return
            }

            else -> throw IllegalStateException()
        }
    }
}
