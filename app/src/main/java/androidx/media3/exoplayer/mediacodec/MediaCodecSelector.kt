
package androidx.media3.exoplayer.mediacodec

import androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException

fun interface MediaCodecSelector {
    @Throws(DecoderQueryException::class)
    fun getDecoderInfos(
        mimeType: String, requiresSecureDecoder: Boolean, requiresTunnelingDecoder: Boolean
    ): List<MediaCodecInfo>

    companion object {
        val DEFAULT: MediaCodecSelector =
            MediaCodecSelector { mimeType: String, requiresSecureDecoder: Boolean, requiresTunnelingDecoder: Boolean ->
                MediaCodecUtil.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder
                )
            }
        val PREFER_SOFTWARE: MediaCodecSelector =
            MediaCodecSelector { mimeType: String, requiresSecureDecoder: Boolean, requiresTunnelingDecoder: Boolean ->
                MediaCodecUtil.getDecoderInfosSortedBySoftwareOnly(
                    DEFAULT.getDecoderInfos(
                        mimeType,
                        requiresSecureDecoder,
                        requiresTunnelingDecoder
                    )
                )
            }
    }
}
