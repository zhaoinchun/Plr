package androidx.media3.common.util

import androidx.media3.common.C
import androidx.media3.common.C.PcmEncoding
import androidx.media3.common.Format
import androidx.media3.common.util.Util.getFloatPcmEncoding
import androidx.media3.common.util.Util.getPcmEncoding

object WavUtil {
    const val RIFF_FOURCC: Int = 0x52494646
    const val WAVE_FOURCC: Int = 0x57415645
    const val FMT_FOURCC: Int = 0x666d7420
    const val FACT_FOURCC: Int = 0x66616374
    const val DATA_FOURCC: Int = 0x64617461

    const val RF64_FOURCC: Int = 0x52463634

    const val DS64_FOURCC: Int = 0x64733634

    const val TYPE_PCM: Int = 0x0001

    const val TYPE_FLOAT: Int = 0x0003

    const val TYPE_ALAW: Int = 0x0006

    const val TYPE_MLAW: Int = 0x0007

    const val TYPE_IMA_ADPCM: Int = 0x0011

    const val TYPE_WAVE_FORMAT_EXTENSIBLE: Int = 0xFFFE

    // TODO: b/474575207 - Consider returning short value.
    fun getTypeForPcmEncoding(pcmEncoding: @PcmEncoding Int): Int {
        when (pcmEncoding) {
            C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT -> return TYPE_PCM
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_DOUBLE -> return TYPE_FLOAT
            C.ENCODING_PCM_16BIT_BIG_ENDIAN, C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT_BIG_ENDIAN, C.ENCODING_PCM_DOUBLE_BIG_ENDIAN, C.ENCODING_INVALID, Format.NO_VALUE -> throw IllegalArgumentException()
            else -> throw IllegalArgumentException()
        }
    }

    fun getPcmEncodingForType(type: Int, bitsPerSample: Int): @PcmEncoding Int {
        when (type) {
            TYPE_PCM, TYPE_WAVE_FORMAT_EXTENSIBLE -> return getPcmEncoding(bitsPerSample)
            TYPE_FLOAT -> return getFloatPcmEncoding(bitsPerSample)
            else -> return C.ENCODING_INVALID
        }
    }

    fun isChannelMaskValid(channelMask: Int, channelCount: Int): Boolean {
        return (channelMask shr 18) == 0
                && (channelMask == 0 || Integer.bitCount(channelMask) == channelCount)
    }

    fun mapToAudioFormatChannelMask(wavChannelMask: Int): Int {
        if (wavChannelMask == 0) {
            return Format.NO_VALUE
        }
        // AudioFormat channel mask constants are equal to the corresponding WAV channel mask constants
        // shifted by 2, leaving the lowest two bits 0.
        return wavChannelMask shl 2
    }
}
