package androidx.media3.common

import android.text.TextUtils
import androidx.media3.common.util.Util
import com.google.common.base.Ascii
import com.google.common.base.Preconditions
import org.checkerframework.checker.nullness.qual.PolyNull
import org.checkerframework.dataflow.qual.Pure
import java.util.regex.Pattern

object MimeTypes {
    const val BASE_TYPE_VIDEO: String = "video"
    const val BASE_TYPE_AUDIO: String = "audio"
    const val BASE_TYPE_TEXT: String = "text"
    const val BASE_TYPE_IMAGE: String = "image"
    const val BASE_TYPE_APPLICATION: String = "application"

    // video/ MIME types
    const val VIDEO_MP4: String = "$BASE_TYPE_VIDEO/mp4"
    const val VIDEO_QUICK_TIME: String = "$BASE_TYPE_VIDEO/quicktime"
    const val VIDEO_MATROSKA: String = "$BASE_TYPE_VIDEO/x-matroska"
    const val VIDEO_WEBM: String = "$BASE_TYPE_VIDEO/webm"
    const val VIDEO_H263: String = "$BASE_TYPE_VIDEO/3gpp"
    const val VIDEO_H264: String = "$BASE_TYPE_VIDEO/avc"
    const val VIDEO_APV: String = "$BASE_TYPE_VIDEO/apv"
    const val VIDEO_H265: String = "$BASE_TYPE_VIDEO/hevc"
    const val VIDEO_H266: String = "$BASE_TYPE_VIDEO/vvc"
    const val VIDEO_VP8: String = "$BASE_TYPE_VIDEO/x-vnd.on2.vp8"
    const val VIDEO_VP9: String = "$BASE_TYPE_VIDEO/x-vnd.on2.vp9"
    const val VIDEO_AV1: String = "$BASE_TYPE_VIDEO/av01"
    const val VIDEO_MP2T: String = "$BASE_TYPE_VIDEO/mp2t"
    const val VIDEO_MP4V: String = "$BASE_TYPE_VIDEO/mp4v-es"
    const val VIDEO_MPEG: String = "$BASE_TYPE_VIDEO/mpeg"
    const val VIDEO_PS: String = "$BASE_TYPE_VIDEO/mp2p"
    const val VIDEO_MPEG2: String = "$BASE_TYPE_VIDEO/mpeg2"
    const val VIDEO_VC1: String = "$BASE_TYPE_VIDEO/wvc1"
    const val VIDEO_DIVX: String = "$BASE_TYPE_VIDEO/divx"
    const val VIDEO_FLV: String = "$BASE_TYPE_VIDEO/x-flv"
    const val VIDEO_DOLBY_VISION: String = "$BASE_TYPE_VIDEO/dolby-vision"
    const val VIDEO_OGG: String = "$BASE_TYPE_VIDEO/ogg"
    const val VIDEO_AVI: String = "$BASE_TYPE_VIDEO/x-msvideo"
    const val VIDEO_MJPEG: String = "$BASE_TYPE_VIDEO/mjpeg"
    const val VIDEO_MP42: String = "$BASE_TYPE_VIDEO/mp42"
    const val VIDEO_MP43: String = "$BASE_TYPE_VIDEO/mp43"
    const val VIDEO_MV_HEVC: String = "$BASE_TYPE_VIDEO/mv-hevc"
    const val VIDEO_RAW: String = "$BASE_TYPE_VIDEO/raw"
    const val VIDEO_UNKNOWN: String = "$BASE_TYPE_VIDEO/x-unknown"

    // audio/ MIME types
    const val AUDIO_MP4: String = "$BASE_TYPE_AUDIO/mp4"
    const val AUDIO_AAC: String = "$BASE_TYPE_AUDIO/mp4a-latm"
    const val AUDIO_MATROSKA: String = "$BASE_TYPE_AUDIO/x-matroska"
    const val AUDIO_WEBM: String = "$BASE_TYPE_AUDIO/webm"
    const val AUDIO_MPEG: String = "$BASE_TYPE_AUDIO/mpeg"
    const val AUDIO_MPEG_L1: String = "$BASE_TYPE_AUDIO/mpeg-L1"
    const val AUDIO_MPEG_L2: String = "$BASE_TYPE_AUDIO/mpeg-L2"
    const val AUDIO_MPEGH_MHA1: String = "$BASE_TYPE_AUDIO/mha1"
    const val AUDIO_MPEGH_MHM1: String = "$BASE_TYPE_AUDIO/mhm1"
    const val AUDIO_RAW: String = "$BASE_TYPE_AUDIO/raw"
    const val AUDIO_ALAW: String = "$BASE_TYPE_AUDIO/g711-alaw"
    const val AUDIO_MLAW: String = "$BASE_TYPE_AUDIO/g711-mlaw"
    const val AUDIO_AC3: String = "$BASE_TYPE_AUDIO/ac3"
    const val AUDIO_E_AC3: String = "$BASE_TYPE_AUDIO/eac3"
    const val AUDIO_E_AC3_JOC: String = "$BASE_TYPE_AUDIO/eac3-joc"
    const val AUDIO_AC4: String = "$BASE_TYPE_AUDIO/ac4"
    const val AUDIO_TRUEHD: String = "$BASE_TYPE_AUDIO/true-hd"
    const val AUDIO_DSD: String = "$BASE_TYPE_AUDIO/dsd"
    const val AUDIO_DTS: String = "$BASE_TYPE_AUDIO/vnd.dts"
    const val AUDIO_DTS_HD: String = "$BASE_TYPE_AUDIO/vnd.dts.hd"
    const val AUDIO_DTS_EXPRESS: String = "$BASE_TYPE_AUDIO/vnd.dts.hd;profile=lbr"

    const val AUDIO_DTS_UHD_P2: String = "$BASE_TYPE_AUDIO/vnd.dts.uhd;profile=p2"

    @Deprecated("Use {@link #AUDIO_DTS_UHD_P2} instead.")
    val AUDIO_DTS_X: String = AUDIO_DTS_UHD_P2

    const val AUDIO_VORBIS: String = "$BASE_TYPE_AUDIO/vorbis"
    const val AUDIO_OPUS: String = "$BASE_TYPE_AUDIO/opus"
    const val AUDIO_AMR: String = "$BASE_TYPE_AUDIO/amr"
    const val AUDIO_AMR_NB: String = "$BASE_TYPE_AUDIO/3gpp"
    const val AUDIO_AMR_WB: String = "$BASE_TYPE_AUDIO/amr-wb"
    const val AUDIO_FLAC: String = "$BASE_TYPE_AUDIO/flac"
    const val AUDIO_ALAC: String = "$BASE_TYPE_AUDIO/alac"
    const val AUDIO_MSGSM: String = "$BASE_TYPE_AUDIO/gsm"
    const val AUDIO_OGG: String = "$BASE_TYPE_AUDIO/ogg"
    const val AUDIO_WAV: String = "$BASE_TYPE_AUDIO/wav"
    const val AUDIO_MIDI: String = "$BASE_TYPE_AUDIO/midi"
    const val AUDIO_IAMF: String = "$BASE_TYPE_AUDIO/iamf"

    const val AUDIO_EXOPLAYER_MIDI: String = "$BASE_TYPE_AUDIO/x-exoplayer-midi"
    const val AUDIO_UNKNOWN: String = "$BASE_TYPE_AUDIO/x-unknown"

    // text/ MIME types
    const val TEXT_VTT: String = "$BASE_TYPE_TEXT/vtt"
    const val TEXT_SSA: String = "$BASE_TYPE_TEXT/x-ssa"
    const val TEXT_UNKNOWN: String = "$BASE_TYPE_TEXT/x-unknown"

    // application/ MIME types
    const val APPLICATION_MP4: String = "$BASE_TYPE_APPLICATION/mp4"
    const val APPLICATION_WEBM: String = "$BASE_TYPE_APPLICATION/webm"

    const val APPLICATION_MATROSKA: String = "$BASE_TYPE_APPLICATION/x-matroska"

    const val APPLICATION_MPD: String = "$BASE_TYPE_APPLICATION/dash+xml"
    const val APPLICATION_M3U8: String = "$BASE_TYPE_APPLICATION/x-mpegURL"
    const val APPLICATION_SS: String = "$BASE_TYPE_APPLICATION/vnd.ms-sstr+xml"
    const val APPLICATION_ID3: String = "$BASE_TYPE_APPLICATION/id3"
    const val APPLICATION_CEA608: String = "$BASE_TYPE_APPLICATION/cea-608"
    const val APPLICATION_CEA708: String = "$BASE_TYPE_APPLICATION/cea-708"
    const val APPLICATION_SUBRIP: String = "$BASE_TYPE_APPLICATION/x-subrip"
    const val APPLICATION_TTML: String = "$BASE_TYPE_APPLICATION/ttml+xml"
    const val APPLICATION_TX3G: String = "$BASE_TYPE_APPLICATION/x-quicktime-tx3g"
    const val APPLICATION_MP4VTT: String = "$BASE_TYPE_APPLICATION/x-mp4-vtt"
    const val APPLICATION_MP4CEA608: String = "$BASE_TYPE_APPLICATION/x-mp4-cea-608"

    @Deprecated(
        """RawCC is a Google-internal subtitle format that isn't supported by this version of
        Media3. There is no replacement for this value."""
    )
    const val APPLICATION_RAWCC: String = "$BASE_TYPE_APPLICATION/x-rawcc"

    const val APPLICATION_VOBSUB: String = "$BASE_TYPE_APPLICATION/vobsub"
    const val APPLICATION_PGS: String = "$BASE_TYPE_APPLICATION/pgs"
    const val APPLICATION_SCTE35: String = "$BASE_TYPE_APPLICATION/x-scte35"
    const val APPLICATION_SDP: String = "$BASE_TYPE_APPLICATION/sdp"

    const val APPLICATION_CAMERA_MOTION: String = "$BASE_TYPE_APPLICATION/x-camera-motion"

    const val APPLICATION_META: String = "$BASE_TYPE_APPLICATION/meta"

    const val APPLICATION_ITUT_T35: String = "$BASE_TYPE_APPLICATION/x-itut-t35"

    const val APPLICATION_DEPTH_METADATA: String = "$BASE_TYPE_APPLICATION/x-depth-metadata"

    const val APPLICATION_EMSG: String = "$BASE_TYPE_APPLICATION/x-emsg"
    const val APPLICATION_DVBSUBS: String = "$BASE_TYPE_APPLICATION/dvbsubs"
    const val APPLICATION_EXIF: String = "$BASE_TYPE_APPLICATION/x-exif"
    const val APPLICATION_ICY: String = "$BASE_TYPE_APPLICATION/x-icy"
    const val APPLICATION_AIT: String = "$BASE_TYPE_APPLICATION/vnd.dvb.ait"

    const val APPLICATION_RTSP: String = "$BASE_TYPE_APPLICATION/x-rtsp"

    const val APPLICATION_MEDIA3_CUES: String = "$BASE_TYPE_APPLICATION/x-media3-cues"

    const val APPLICATION_EXTERNALLY_LOADED_IMAGE: String = "$BASE_TYPE_APPLICATION/x-image-uri"

    const val IMAGE_JPEG: String = "$BASE_TYPE_IMAGE/jpeg"
    const val IMAGE_JPEG_R: String = "$BASE_TYPE_IMAGE/jpeg_r"
    const val IMAGE_PNG: String = "$BASE_TYPE_IMAGE/png"
    const val IMAGE_HEIF: String = "$BASE_TYPE_IMAGE/heif"
    const val IMAGE_HEIC: String = "$BASE_TYPE_IMAGE/heic"
    const val IMAGE_AVIF: String = "$BASE_TYPE_IMAGE/avif"
    const val IMAGE_BMP: String = "$BASE_TYPE_IMAGE/bmp"
    const val IMAGE_WEBP: String = "$BASE_TYPE_IMAGE/webp"
    const val IMAGE_RAW: String = "$BASE_TYPE_IMAGE/raw"

    const val CODEC_E_AC3_JOC: String = "ec+3"

    private val customMimeTypes: ArrayList<CustomMimeType> = ArrayList<CustomMimeType>()

    private val MP4A_RFC_6381_CODEC_PATTERN: Pattern =
        Pattern.compile("^mp4a\\.([a-zA-Z0-9]{2})(?:\\.([0-9]{1,2}))?$")

    fun registerCustomMimeType(
        mimeType: String, codecPrefix: String, trackType: @C.TrackType Int
    ) {
        val customMimeType = CustomMimeType(mimeType, codecPrefix, trackType)
        val customMimeTypeCount = customMimeTypes.size
        for (i in 0..<customMimeTypeCount) {
            if (mimeType == customMimeTypes[i].mimeType) {
                customMimeTypes.removeAt(i)
                break
            }
        }
        customMimeTypes.add(customMimeType)
    }

    fun clearRegisteredCustomMimeTypes() {
        customMimeTypes.clear()
    }

    fun isAudio(mimeType: String?): Boolean {
        return BASE_TYPE_AUDIO == getTopLevelType(mimeType)
    }

    fun isVideo(mimeType: String?): Boolean {
        return BASE_TYPE_VIDEO == getTopLevelType(mimeType)
    }

    @Pure
    fun isText(mimeType: String?): Boolean {
        return BASE_TYPE_TEXT == getTopLevelType(mimeType)
                || APPLICATION_MEDIA3_CUES == mimeType
                || APPLICATION_CEA608 == mimeType
                || APPLICATION_CEA708 == mimeType
                || APPLICATION_MP4CEA608 == mimeType
                || APPLICATION_SUBRIP == mimeType
                || APPLICATION_TTML == mimeType
                || APPLICATION_TX3G == mimeType
                || APPLICATION_MP4VTT == mimeType
                || APPLICATION_RAWCC == mimeType
                || APPLICATION_VOBSUB == mimeType
                || APPLICATION_PGS == mimeType
                || APPLICATION_DVBSUBS == mimeType
    }

    fun isImage(mimeType: String?): Boolean {
        return BASE_TYPE_IMAGE == getTopLevelType(mimeType)
                || APPLICATION_EXTERNALLY_LOADED_IMAGE == mimeType
    }

    fun allSamplesAreSyncSamples(mimeType: String?, codec: String?): Boolean {
        if (mimeType == null) {
            return false
        }
        when (mimeType) {
            VIDEO_APV, AUDIO_MPEG, AUDIO_MPEG_L1, AUDIO_MPEG_L2, AUDIO_RAW, AUDIO_ALAW, AUDIO_MLAW, AUDIO_FLAC, AUDIO_AC3, AUDIO_E_AC3, AUDIO_E_AC3_JOC, APPLICATION_MEDIA3_CUES, APPLICATION_ID3, APPLICATION_EMSG, APPLICATION_SCTE35, APPLICATION_ICY, APPLICATION_AIT, APPLICATION_META, APPLICATION_ITUT_T35, APPLICATION_CAMERA_MOTION -> return true
            AUDIO_AAC -> {
                if (codec == null) {
                    return false
                }
                val objectType = getObjectTypeFromMp4aRFC6381CodecString(codec) ?: return false

                val encoding: @C.Encoding Int = objectType.encoding
                // xHE-AAC is an exception in which it's not true that all samples will be sync samples.
                // Also return false for ENCODING_INVALID, which indicates we weren't able to parse the
                // encoding from the codec string.
                return encoding != C.ENCODING_INVALID && encoding != C.ENCODING_AAC_XHE
            }

            else -> return false
        }
    }

    fun getVideoMediaMimeType(codecs: String?): String? {
        if (codecs == null) {
            return null
        }
        val codecList = Util.splitCodecs(codecs)
        for (codec in codecList) {
            val mimeType = getMediaMimeType(codec)
            if (mimeType != null && isVideo(mimeType)) {
                return mimeType
            }
        }
        return null
    }

    fun containsCodecsCorrespondingToMimeType(codecs: String?, mimeType: String?): Boolean {
        return getCodecsCorrespondingToMimeType(codecs, mimeType) != null
    }

    fun getCodecsCorrespondingToMimeType(codecs: String?, mimeType: String?): String? {
        if (codecs == null || mimeType == null) {
            return null
        }
        val codecList = Util.splitCodecs(codecs)
        val builder = StringBuilder()
        for (codec in codecList) {
            if (mimeType == getMediaMimeType(codec)) {
                if (builder.isNotEmpty()) {
                    builder.append(",")
                }
                builder.append(codec)
            }
        }
        return if (builder.isNotEmpty()) builder.toString() else null
    }

    fun getAudioMediaMimeType(codecs: String?): String? {
        if (codecs == null) {
            return null
        }
        val codecList = Util.splitCodecs(codecs)
        for (codec in codecList) {
            val mimeType = getMediaMimeType(codec)
            if (mimeType != null && isAudio(mimeType)) {
                return mimeType
            }
        }
        return null
    }

    fun getTextMediaMimeType(codecs: String?): String? {
        if (codecs == null) {
            return null
        }
        val codecList = Util.splitCodecs(codecs)
        for (codec in codecList) {
            val mimeType = getMediaMimeType(codec)
            if (mimeType != null && isText(mimeType)) {
                return mimeType
            }
        }
        return null
    }

    fun getMediaMimeType(codec: String?): String? {
        var codec = codec ?: return null
        codec = Ascii.toLowerCase(codec.trim { it <= ' ' })
        if (codec.startsWith("avc1") || codec.startsWith("avc3")) {
            return VIDEO_H264
        } else if (codec.startsWith("hev1") || codec.startsWith("hvc1")) {
            return VIDEO_H265
        } else if (codec.startsWith("vvc1") || codec.startsWith("vvi1")) {
            return VIDEO_H266
        } else if (codec.startsWith("dvav")
            || codec.startsWith("dva1")
            || codec.startsWith("dvhe")
            || codec.startsWith("dvh1")
            || codec.startsWith("dav1")
        ) {
            return VIDEO_DOLBY_VISION
        } else if (codec.startsWith("av01")) {
            return VIDEO_AV1
        } else if (codec.startsWith("vp9") || codec.startsWith("vp09")) {
            return VIDEO_VP9
        } else if (codec.startsWith("vp8") || codec.startsWith("vp08")) {
            return VIDEO_VP8
        } else if (codec.startsWith("mp4a")) {
            var mimeType: String? = null
            if (codec.startsWith("mp4a.")) {
                val objectType = getObjectTypeFromMp4aRFC6381CodecString(codec)
                if (objectType != null) {
                    mimeType = getMimeTypeFromMp4ObjectType(objectType.objectTypeIndication)
                }
            }
            return mimeType ?: AUDIO_AAC
        } else if (codec.startsWith("mha1")) {
            return AUDIO_MPEGH_MHA1
        } else if (codec.startsWith("mhm1")) {
            return AUDIO_MPEGH_MHM1
        } else if (codec.startsWith("ac-3") || codec.startsWith("dac3")) {
            return AUDIO_AC3
        } else if (codec.startsWith("ec-3") || codec.startsWith("dec3")) {
            return AUDIO_E_AC3
        } else if (codec.startsWith(CODEC_E_AC3_JOC)) {
            return AUDIO_E_AC3_JOC
        } else if (codec.startsWith("ac-4") || codec.startsWith("dac4")) {
            return AUDIO_AC4
        } else if (codec.startsWith("dtsc")) {
            return AUDIO_DTS
        } else if (codec.startsWith("dtse")) {
            return AUDIO_DTS_EXPRESS
        } else if (codec.startsWith("dtsh") || codec.startsWith("dtsl")) {
            return AUDIO_DTS_HD
        } else if (codec.startsWith("dtsx")) {
            return AUDIO_DTS_UHD_P2
        } else if (codec.startsWith("opus")) {
            return AUDIO_OPUS
        } else if (codec.startsWith("vorbis")) {
            return AUDIO_VORBIS
        } else if (codec.startsWith("flac")) {
            return AUDIO_FLAC
        } else if (codec.startsWith("stpp")) {
            return APPLICATION_TTML
        } else if (codec.startsWith("wvtt")) {
            return TEXT_VTT
        } else if (codec.contains("cea708")) {
            return APPLICATION_CEA708
        } else if (codec.contains("eia608") || codec.contains("cea608")) {
            return APPLICATION_CEA608
        } else {
            return getCustomMimeTypeForCodec(codec)
        }
    }

    fun getMp4ObjectTypeFromMimeType(sampleMimeType: String): Byte? {
        return when (sampleMimeType) {
            AUDIO_AAC -> 0x40.toByte()
            AUDIO_VORBIS -> 0xDD.toByte()
            VIDEO_MP4V -> 0x20.toByte()
            else -> null
        }
    }

    fun getMimeTypeFromMp4ObjectType(objectType: Int): String? {
        when (objectType) {
            0x20 -> return VIDEO_MP4V
            0x21 -> return VIDEO_H264
            0x23 -> return VIDEO_H265
            0x60, 0x61, 0x62, 0x63, 0x64, 0x65 -> return VIDEO_MPEG2
            0x6A -> return VIDEO_MPEG
            0x69, 0x6B -> return AUDIO_MPEG
            0x6C -> return IMAGE_JPEG
            0xA3 -> return VIDEO_VC1
            0xB1 -> return VIDEO_VP9
            0x40, 0x66, 0x67, 0x68 -> return AUDIO_AAC
            0xA5 -> return AUDIO_AC3
            0xA6 -> return AUDIO_E_AC3
            0xA9, 0xAC -> return AUDIO_DTS
            0xAA, 0xAB -> return AUDIO_DTS_HD
            0xAD -> return AUDIO_OPUS
            0xAE -> return AUDIO_AC4
            0xDD -> return AUDIO_VORBIS
            else -> return null
        }
    }

    fun isDolbyVisionCodec(
        codecs: String?, supplementalCodecs: String?
    ): Boolean {
        if (codecs == null) {
            return false
        }
        if (codecs.startsWith("dvhe") || codecs.startsWith("dvh1") || codecs.startsWith("dav1")) {
            // profiles 5, 10.0 and 20.0
            return true
        }
        if (supplementalCodecs == null) {
            return false
        }
        // profiles 8, 9 and 10
        return (supplementalCodecs.startsWith("dvhe") && codecs.startsWith("hev1"))
                || (supplementalCodecs.startsWith("dvh1") && codecs.startsWith("hvc1"))
                || (supplementalCodecs.startsWith("dvav") && codecs.startsWith("avc3"))
                || (supplementalCodecs.startsWith("dva1") && codecs.startsWith("avc1"))
                || (supplementalCodecs.startsWith("dav1") && codecs.startsWith("av01"))
    }

    fun getTrackType(mimeType: String?): @C.TrackType Int {
        if (TextUtils.isEmpty(mimeType)) {
            return C.TRACK_TYPE_UNKNOWN
        } else if (isAudio(mimeType)) {
            return C.TRACK_TYPE_AUDIO
        } else if (isVideo(mimeType)) {
            return C.TRACK_TYPE_VIDEO
        } else if (isText(mimeType)) {
            return C.TRACK_TYPE_TEXT
        } else if (isImage(mimeType)) {
            return C.TRACK_TYPE_IMAGE
        } else if (APPLICATION_ID3 == mimeType
            || APPLICATION_EMSG == mimeType
            || APPLICATION_SCTE35 == mimeType
            || APPLICATION_ICY == mimeType
            || APPLICATION_AIT == mimeType
            || APPLICATION_META == mimeType
            || APPLICATION_ITUT_T35 == mimeType
        ) {
            return C.TRACK_TYPE_METADATA
        } else if (APPLICATION_CAMERA_MOTION == mimeType) {
            return C.TRACK_TYPE_CAMERA_MOTION
        } else {
            return getTrackTypeForCustomMimeType(mimeType!!)
        }
    }

    fun getEncoding(mimeType: String, codec: String?): @C.Encoding Int {
        when (mimeType) {
            AUDIO_MPEG -> return C.ENCODING_MP3
            AUDIO_AAC -> {
                if (codec == null) {
                    return C.ENCODING_INVALID
                }
                val objectType = getObjectTypeFromMp4aRFC6381CodecString(codec)
                if (objectType == null) {
                    return C.ENCODING_INVALID
                }
                return objectType.encoding
            }

            AUDIO_AC3 -> return C.ENCODING_AC3
            AUDIO_E_AC3 -> return C.ENCODING_E_AC3
            AUDIO_E_AC3_JOC -> return C.ENCODING_E_AC3_JOC
            AUDIO_AC4 -> return C.ENCODING_AC4
            AUDIO_DTS -> return C.ENCODING_DTS
            AUDIO_DTS_HD -> return C.ENCODING_DTS_HD
            AUDIO_DTS_EXPRESS -> return C.ENCODING_DTS_HD
            AUDIO_DTS_UHD_P2 -> return C.ENCODING_DTS_UHD_P2
            AUDIO_TRUEHD -> return C.ENCODING_DOLBY_TRUEHD
            AUDIO_OPUS -> return C.ENCODING_OPUS
            AUDIO_DSD -> return C.ENCODING_DSD
            else -> return C.ENCODING_INVALID
        }
    }

    fun getTrackTypeOfCodec(codec: String?): @C.TrackType Int {
        return getTrackType(getMediaMimeType(codec))
    }

    fun normalizeMimeType(mimeType: @PolyNull String?): @PolyNull String? {
        var mimeType = mimeType ?: return null
        mimeType = Ascii.toLowerCase(mimeType)
        return when (mimeType) {
            "$BASE_TYPE_VIDEO/x-mvhevc" -> VIDEO_MV_HEVC
            "$BASE_TYPE_AUDIO/x-flac" -> AUDIO_FLAC
            "$BASE_TYPE_AUDIO/mp3" -> AUDIO_MPEG
            "$BASE_TYPE_AUDIO/x-wav" -> AUDIO_WAV
            "application/x-mpegurl" -> APPLICATION_M3U8
            "audio/mpeg-l1" -> AUDIO_MPEG_L1
            "audio/mpeg-l2" -> AUDIO_MPEG_L2
            else -> mimeType
        }
    }

    fun isMatroska(mimeType: String?): Boolean {
        if (mimeType == null) {
            return false
        }
        return mimeType.startsWith(VIDEO_WEBM)
                || mimeType.startsWith(AUDIO_WEBM)
                || mimeType.startsWith(APPLICATION_WEBM)
                || mimeType.startsWith(VIDEO_MATROSKA)
                || mimeType.startsWith(AUDIO_MATROSKA)
                || mimeType.startsWith(APPLICATION_MATROSKA)
    }

    private fun getTopLevelType(mimeType: String?): String? {
        if (mimeType == null) {
            return null
        }
        val indexOfSlash = mimeType.indexOf('/')
        if (indexOfSlash == -1) {
            return null
        }
        return mimeType.substring(0, indexOfSlash)
    }

    private fun getCustomMimeTypeForCodec(codec: String): String? {
        val customMimeTypeCount = customMimeTypes.size
        for (i in 0..<customMimeTypeCount) {
            val customMimeType = customMimeTypes[i]
            if (codec.startsWith(customMimeType.codecPrefix)) {
                return customMimeType.mimeType
            }
        }
        return null
    }

    private fun getTrackTypeForCustomMimeType(mimeType: String): @C.TrackType Int {
        val customMimeTypeCount = customMimeTypes.size
        for (i in 0..<customMimeTypeCount) {
            val customMimeType = customMimeTypes[i]
            if (mimeType == customMimeType.mimeType) {
                return customMimeType.trackType
            }
        }
        return C.TRACK_TYPE_UNKNOWN
    }

    fun getObjectTypeFromMp4aRFC6381CodecString(codec: String): Mp4aObjectType? {
        val matcher = MP4A_RFC_6381_CODEC_PATTERN.matcher(codec)
        if (!matcher.matches()) {
            return null
        }
        val objectTypeIndicationHex = matcher.group(1)!!
        val audioObjectTypeIndicationDec = matcher.group(2)
        val objectTypeIndication: Int
        var audioObjectTypeIndication = 0
        try {
            objectTypeIndication = objectTypeIndicationHex.toInt(16)
            if (audioObjectTypeIndicationDec != null) {
                audioObjectTypeIndication = audioObjectTypeIndicationDec.toInt()
            }
        } catch (e: NumberFormatException) {
            return null
        }
        return Mp4aObjectType(objectTypeIndication, audioObjectTypeIndication)
    }

    class Mp4aObjectType(
        val objectTypeIndication: Int,
        val audioObjectTypeIndication: Int
    ) {
        val encoding: @C.Encoding Int
            get() {
                return when (audioObjectTypeIndication) {
                    2 -> C.ENCODING_AAC_LC
                    5 -> C.ENCODING_AAC_HE_V1
                    29 -> C.ENCODING_AAC_HE_V2
                    42 -> C.ENCODING_AAC_XHE
                    23 -> C.ENCODING_AAC_ELD
                    22 -> C.ENCODING_AAC_ER_BSAC
                    else -> C.ENCODING_INVALID
                }
            }
    }

    private class CustomMimeType(
        val mimeType: String?,
        val codecPrefix: String,
        val trackType: @C.TrackType Int
    )
}
