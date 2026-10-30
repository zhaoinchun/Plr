package androidx.media3.exoplayer.source.mediaparser

import android.media.MediaFormat
import android.media.MediaParser
import android.media.metrics.LogSessionId
import androidx.annotation.RequiresApi
import androidx.media3.common.Format

import androidx.media3.exoplayer.analytics.PlayerId

object MediaParserUtil {
    const val PARAMETER_IN_BAND_CRYPTO_INFO: String = "android.media.mediaparser.inBandCryptoInfo"
    const val PARAMETER_INCLUDE_SUPPLEMENTAL_DATA: String =
        "android.media.mediaparser.includeSupplementalData"
    const val PARAMETER_EAGERLY_EXPOSE_TRACK_TYPE: String =
        "android.media.mediaparser.eagerlyExposeTrackType"
    const val PARAMETER_EXPOSE_DUMMY_SEEK_MAP: String =
        "android.media.mediaparser.exposeDummySeekMap"
    const val PARAMETER_EXPOSE_CHUNK_INDEX_AS_MEDIA_FORMAT: String =
        "android.media.mediaParser.exposeChunkIndexAsMediaFormat"
    const val PARAMETER_OVERRIDE_IN_BAND_CAPTION_DECLARATIONS: String =
        "android.media.mediaParser.overrideInBandCaptionDeclarations"
    const val PARAMETER_EXPOSE_CAPTION_FORMATS: String =
        "android.media.mediaParser.exposeCaptionFormats"
    const val PARAMETER_IGNORE_TIMESTAMP_OFFSET: String =
        "android.media.mediaparser.ignoreTimestampOffset"

    fun toCaptionsMediaFormat(format: Format): MediaFormat {
        val mediaFormat = MediaFormat()
        mediaFormat.setString(MediaFormat.KEY_MIME, format.sampleMimeType)
        if (format.accessibilityChannel != Format.NO_VALUE) {
            mediaFormat.setInteger(
                MediaFormat.KEY_CAPTION_SERVICE_NUMBER,
                format.accessibilityChannel
            )
        }
        return mediaFormat
    }

    @RequiresApi(31)
    fun setLogSessionIdOnMediaParser(mediaParser: MediaParser, playerId: PlayerId) {
        Api31.setLogSessionIdOnMediaParser(mediaParser, playerId)
    }

    @RequiresApi(31)
    private object Api31 {
        fun setLogSessionIdOnMediaParser(mediaParser: MediaParser, playerId: PlayerId) {
            val logSessionId = playerId.logSessionId
            if (logSessionId != LogSessionId.LOG_SESSION_ID_NONE) {
                mediaParser.setLogSessionId(logSessionId)
            }
        }
    }
}
