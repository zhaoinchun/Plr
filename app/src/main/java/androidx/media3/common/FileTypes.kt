package androidx.media3.common

import android.net.Uri
import androidx.annotation.IntDef

object FileTypes {
    const val UNKNOWN: Int = -1
    const val AC3: Int = 0
    const val AC4: Int = 1
    const val ADTS: Int = 2
    const val AMR: Int = 3
    const val FLAC: Int = 4
    const val FLV: Int = 5
    const val MATROSKA: Int = 6
    const val MP3: Int = 7
    const val MP4: Int = 8
    const val OGG: Int = 9
    const val PS: Int = 10
    const val TS: Int = 11
    const val WAV: Int = 12
    const val WEBVTT: Int = 13
    const val JPEG: Int = 14
    const val MIDI: Int = 15
    const val AVI: Int = 16
    const val PNG: Int = 17
    const val WEBP: Int = 18
    const val BMP: Int = 19
    const val HEIF: Int = 20
    const val AVIF: Int = 21

    const val HEADER_CONTENT_TYPE: String = "Content-Type"

    private const val EXTENSION_AC3 = ".ac3"
    private const val EXTENSION_EC3 = ".ec3"
    private const val EXTENSION_AC4 = ".ac4"
    private const val EXTENSION_ADTS = ".adts"
    private const val EXTENSION_AAC = ".aac"
    private const val EXTENSION_AMR = ".amr"
    private const val EXTENSION_FLAC = ".flac"
    private const val EXTENSION_FLV = ".flv"
    private const val EXTENSION_MID = ".mid"
    private const val EXTENSION_MIDI = ".midi"
    private const val EXTENSION_SMF = ".smf"
    private const val EXTENSION_PREFIX_MK = ".mk"
    private const val EXTENSION_WEBM = ".webm"
    private const val EXTENSION_PREFIX_OG = ".og"
    private const val EXTENSION_OPUS = ".opus"
    private const val EXTENSION_MP3 = ".mp3"
    private const val EXTENSION_MP4 = ".mp4"
    private const val EXTENSION_PREFIX_M4 = ".m4"
    private const val EXTENSION_PREFIX_MP4 = ".mp4"
    private const val EXTENSION_PREFIX_CMF = ".cmf"
    private const val EXTENSION_PS = ".ps"
    private const val EXTENSION_MPEG = ".mpeg"
    private const val EXTENSION_MPG = ".mpg"
    private const val EXTENSION_M2P = ".m2p"
    private const val EXTENSION_TS = ".ts"
    private const val EXTENSION_PREFIX_TS = ".ts"
    private const val EXTENSION_WAV = ".wav"
    private const val EXTENSION_WAVE = ".wave"
    private const val EXTENSION_VTT = ".vtt"
    private const val EXTENSION_WEBVTT = ".webvtt"
    private const val EXTENSION_JPG = ".jpg"
    private const val EXTENSION_JPEG = ".jpeg"
    private const val EXTENSION_AVI = ".avi"
    private const val EXTENSION_PNG = ".png"
    private const val EXTENSION_WEBP = ".webp"
    private const val EXTENSION_BMP = ".bmp"
    private const val EXTENSION_DIB = ".dib"
    private const val EXTENSION_HEIC = ".heic"
    private const val EXTENSION_HEIF = ".heif"
    private const val EXTENSION_AVIF = ".avif"

    fun inferFileTypeFromResponseHeaders(
        responseHeaders: MutableMap<String, List<String>>
    ): @Type Int {
        val contentTypes = responseHeaders[HEADER_CONTENT_TYPE]
        val mimeType = contentTypes?.firstOrNull()
        return inferFileTypeFromMimeType(mimeType)
    }

    fun inferFileTypeFromMimeType(mimeType: String?): @Type Int {
        var mimeType: String? = mimeType ?: return UNKNOWN
        mimeType = MimeTypes.normalizeMimeType(mimeType)
        when (mimeType) {
            MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> return AC3
            MimeTypes.AUDIO_AC4 -> return AC4
            MimeTypes.AUDIO_AMR, MimeTypes.AUDIO_AMR_NB, MimeTypes.AUDIO_AMR_WB -> return AMR
            MimeTypes.AUDIO_FLAC -> return FLAC
            MimeTypes.VIDEO_FLV -> return FLV
            MimeTypes.AUDIO_MIDI -> return MIDI
            MimeTypes.VIDEO_MATROSKA, MimeTypes.AUDIO_MATROSKA, MimeTypes.VIDEO_WEBM, MimeTypes.AUDIO_WEBM, MimeTypes.APPLICATION_WEBM -> return MATROSKA
            MimeTypes.AUDIO_MPEG -> return MP3
            MimeTypes.VIDEO_MP4, MimeTypes.AUDIO_MP4, MimeTypes.APPLICATION_MP4 -> return MP4
            MimeTypes.AUDIO_OGG -> return OGG
            MimeTypes.VIDEO_PS -> return PS
            MimeTypes.VIDEO_MP2T -> return TS
            MimeTypes.AUDIO_WAV -> return WAV
            MimeTypes.TEXT_VTT -> return WEBVTT
            MimeTypes.IMAGE_JPEG -> return JPEG
            MimeTypes.VIDEO_AVI -> return AVI
            MimeTypes.IMAGE_PNG -> return PNG
            MimeTypes.IMAGE_WEBP -> return WEBP
            MimeTypes.IMAGE_BMP -> return BMP
            MimeTypes.IMAGE_HEIF, MimeTypes.IMAGE_HEIC -> return HEIF
            MimeTypes.IMAGE_AVIF -> return AVIF
            else -> return UNKNOWN
        }
    }

    fun inferFileTypeFromUri(uri: Uri): @Type Int {
        val filename = uri.lastPathSegment
        if (filename == null) {
            return UNKNOWN
        } else if (filename.endsWith(EXTENSION_AC3) || filename.endsWith(EXTENSION_EC3)) {
            return AC3
        } else if (filename.endsWith(EXTENSION_AC4)) {
            return AC4
        } else if (filename.endsWith(EXTENSION_ADTS) || filename.endsWith(EXTENSION_AAC)) {
            return ADTS
        } else if (filename.endsWith(EXTENSION_AMR)) {
            return AMR
        } else if (filename.endsWith(EXTENSION_FLAC)) {
            return FLAC
        } else if (filename.endsWith(EXTENSION_FLV)) {
            return FLV
        } else if (filename.endsWith(EXTENSION_MID)
            || filename.endsWith(EXTENSION_MIDI)
            || filename.endsWith(EXTENSION_SMF)
        ) {
            return MIDI
        } else if (filename.startsWith(
                EXTENSION_PREFIX_MK,  
                filename.length - (EXTENSION_PREFIX_MK.length + 1)
            )
            || filename.endsWith(EXTENSION_WEBM)
        ) {
            return MATROSKA
        } else if (filename.endsWith(EXTENSION_MP3)) {
            return MP3
        } else if (filename.endsWith(EXTENSION_MP4)
            || filename.startsWith(
                EXTENSION_PREFIX_M4,  
                filename.length - (EXTENSION_PREFIX_M4.length + 1)
            )
            || filename.startsWith(
                EXTENSION_PREFIX_MP4,  
                filename.length - (EXTENSION_PREFIX_MP4.length + 1)
            )
            || filename.startsWith(
                EXTENSION_PREFIX_CMF,  
                filename.length - (EXTENSION_PREFIX_CMF.length + 1)
            )
        ) {
            return MP4
        } else if (filename.startsWith(
                EXTENSION_PREFIX_OG,  
                filename.length - (EXTENSION_PREFIX_OG.length + 1)
            )
            || filename.endsWith(EXTENSION_OPUS)
        ) {
            return OGG
        } else if (filename.endsWith(EXTENSION_PS)
            || filename.endsWith(EXTENSION_MPEG)
            || filename.endsWith(EXTENSION_MPG)
            || filename.endsWith(EXTENSION_M2P)
        ) {
            return PS
        } else if (filename.endsWith(EXTENSION_TS)
            || filename.startsWith(
                EXTENSION_PREFIX_TS,  
                filename.length - (EXTENSION_PREFIX_TS.length + 1)
            )
        ) {
            return TS
        } else if (filename.endsWith(EXTENSION_WAV) || filename.endsWith(EXTENSION_WAVE)) {
            return WAV
        } else if (filename.endsWith(EXTENSION_VTT) || filename.endsWith(EXTENSION_WEBVTT)) {
            return WEBVTT
        } else if (filename.endsWith(EXTENSION_JPG) || filename.endsWith(EXTENSION_JPEG)) {
            return JPEG
        } else if (filename.endsWith(EXTENSION_AVI)) {
            return AVI
        } else if (filename.endsWith(EXTENSION_PNG)) {
            return PNG
        } else if (filename.endsWith(EXTENSION_WEBP)) {
            return WEBP
        } else if (filename.endsWith(EXTENSION_BMP) || filename.endsWith(EXTENSION_DIB)) {
            return BMP
        } else if (filename.endsWith(EXTENSION_HEIC) || filename.endsWith(EXTENSION_HEIF)) {
            return HEIF
        } else if (filename.endsWith(EXTENSION_AVIF)) {
            return AVIF
        } else {
            return UNKNOWN
        }
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [UNKNOWN, AC3, AC4, ADTS, AMR, FLAC, FLV, MATROSKA, MP3, MP4, OGG, PS, TS, WAV,
            WEBVTT, JPEG, MIDI, AVI, PNG, WEBP, BMP, HEIF, AVIF]
    )
    annotation class Type
}
