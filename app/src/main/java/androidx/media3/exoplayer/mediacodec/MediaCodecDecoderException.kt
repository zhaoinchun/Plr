package androidx.media3.exoplayer.mediacodec

import android.media.MediaCodec.CodecException
import androidx.media3.decoder.DecoderException

open class MediaCodecDecoderException(
    cause: Throwable?,
    val codecInfo: MediaCodecInfo?
) : DecoderException("Decoder failed: " + (codecInfo?.name), cause) {
    val diagnosticInfo: String? = if (cause is CodecException) cause.diagnosticInfo else null
    val errorCode = getErrorCode(cause)

    companion object {
        private fun getErrorCode(cause: Throwable?): Int {
            if (cause is CodecException) {
                return cause.errorCode
            }
            return 0
        }
    }
}
