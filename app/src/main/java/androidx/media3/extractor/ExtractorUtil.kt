
package androidx.media3.extractor

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ParserException

import androidx.media3.container.OpusUtil
import org.checkerframework.dataflow.qual.Pure
import java.io.EOFException
import java.io.IOException

object ExtractorUtil {
    @Pure
    @Throws(ParserException::class)
    fun checkContainerInput(expression: Boolean, message: String?) {
        if (!expression) {
            throw ParserException.createForMalformedContainer(message,  null)
        }
    }

    @Throws(IOException::class)
    fun peekToLength(input: ExtractorInput, target: ByteArray, offset: Int, length: Int): Int {
        var totalBytesPeeked = 0
        while (totalBytesPeeked < length) {
            val bytesPeeked =
                input.peek(target, offset + totalBytesPeeked, length - totalBytesPeeked)
            if (bytesPeeked == C.RESULT_END_OF_INPUT) {
                break
            }
            totalBytesPeeked += bytesPeeked
        }
        return totalBytesPeeked
    }

    @Throws(IOException::class)
    fun readFullyQuietly(
        input: ExtractorInput, output: ByteArray, offset: Int, length: Int
    ): Boolean {
        try {
            input.readFully(output, offset, length)
        } catch (e: EOFException) {
            return false
        }
        return true
    }

    @Throws(IOException::class)
    fun skipFullyQuietly(input: ExtractorInput, length: Int): Boolean {
        try {
            input.skipFully(length)
        } catch (e: EOFException) {
            return false
        }
        return true
    }

    @Throws(IOException::class)
    fun peekFullyQuietly(
        input: ExtractorInput,
        output: ByteArray,
        offset: Int,
        length: Int,
        allowEndOfInput: Boolean
    ): Boolean {
        try {
            return input.peekFully(output, offset, length,  allowEndOfInput)
        } catch (e: EOFException) {
            if (allowEndOfInput) {
                return false
            } else {
                throw e
            }
        }
    }

    fun getMaximumEncodedRateBytesPerSecond(encoding: @C.Encoding Int): Int {
        when (encoding) {
            C.ENCODING_MP3 -> return MpegAudioUtil.MAX_RATE_BYTES_PER_SECOND
            C.ENCODING_AAC_LC -> return AacUtil.AAC_LC_MAX_RATE_BYTES_PER_SECOND
            C.ENCODING_AAC_HE_V1 -> return AacUtil.AAC_HE_V1_MAX_RATE_BYTES_PER_SECOND
            C.ENCODING_AAC_HE_V2 -> return AacUtil.AAC_HE_V2_MAX_RATE_BYTES_PER_SECOND
            C.ENCODING_AAC_XHE -> return AacUtil.AAC_XHE_MAX_RATE_BYTES_PER_SECOND
            C.ENCODING_AAC_ELD -> return AacUtil.AAC_ELD_MAX_RATE_BYTES_PER_SECOND
            C.ENCODING_AC3 -> return Ac3Util.AC3_MAX_RATE_BYTES_PER_SECOND
            C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC -> return Ac3Util.E_AC3_MAX_RATE_BYTES_PER_SECOND
            C.ENCODING_AC4 -> return Ac4Util.MAX_RATE_BYTES_PER_SECOND
            C.ENCODING_DTS -> return DtsUtil.DTS_MAX_RATE_BYTES_PER_SECOND
            C.ENCODING_DTS_HD, C.ENCODING_DTS_UHD_P2 -> return DtsUtil.DTS_HD_MAX_RATE_BYTES_PER_SECOND
            C.ENCODING_DOLBY_TRUEHD -> return Ac3Util.TRUEHD_MAX_RATE_BYTES_PER_SECOND
            C.ENCODING_OPUS -> return OpusUtil.MAX_BYTES_PER_SECOND
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_8BIT, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT_BIG_ENDIAN, C.ENCODING_PCM_DOUBLE, C.ENCODING_PCM_DOUBLE_BIG_ENDIAN, C.ENCODING_AAC_ER_BSAC, C.ENCODING_DSD, C.ENCODING_INVALID, Format.NO_VALUE -> return C.RATE_UNSET_INT
            else -> return C.RATE_UNSET_INT
        }
    }
}
