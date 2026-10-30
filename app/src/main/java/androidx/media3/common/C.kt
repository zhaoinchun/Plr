package androidx.media3.common

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.opengl.GLES20
import android.os.Build
import android.os.IBinder
import android.view.Surface
import androidx.annotation.IntDef
import java.util.UUID

object C {
    const val TIME_END_OF_SOURCE: Long = Long.MIN_VALUE
    const val TIME_UNSET: Long = Long.MIN_VALUE + 1
    const val INDEX_UNSET: Int = -1
    const val RATE_UNSET: Float = -Float.MAX_VALUE
    const val RATE_UNSET_INT: Int = Int.MIN_VALUE + 1
    const val LENGTH_UNSET: Int = -1
    const val PERCENTAGE_UNSET: Int = -1
    const val MILLIS_PER_SECOND: Long = 1000L
    const val MICROS_PER_SECOND: Long = 1000000L
    const val NANOS_PER_SECOND: Long = 1000000000L
    const val BITS_PER_BYTE: Int = 8
    const val BYTES_PER_FLOAT: Int = 4
    const val SERIF_NAME: String = "serif"
    const val SANS_SERIF_NAME: String = "sans-serif"
    const val SSAI_SCHEME: String = "ssai"
    const val CSAI_SCHEME: String = "csai"
    
    val SUGGESTED_MAX_IPC_SIZE: Int =
        if (Build.VERSION.SDK_INT >= 30) IBinder.getSuggestedMaxIpcSizeBytes() else 64 * 1024

    const val CRYPTO_TYPE_NONE: Int = 0
    const val CRYPTO_TYPE_UNSUPPORTED: Int = 1
    const val CRYPTO_TYPE_FRAMEWORK: Int = 2
    const val CRYPTO_TYPE_CUSTOM_BASE: Int = 10000

    const val CRYPTO_MODE_UNENCRYPTED: Int = MediaCodec.CRYPTO_MODE_UNENCRYPTED
    const val CRYPTO_MODE_AES_CTR: Int = MediaCodec.CRYPTO_MODE_AES_CTR
    const val CRYPTO_MODE_AES_CBC: Int = MediaCodec.CRYPTO_MODE_AES_CBC
    const val AUDIO_SESSION_ID_UNSET: Int = AudioManager.AUDIO_SESSION_ID_GENERATE

    const val ENCODING_INVALID: Int = AudioFormat.ENCODING_INVALID
    const val ENCODING_PCM_8BIT: Int = AudioFormat.ENCODING_PCM_8BIT
    const val ENCODING_PCM_16BIT: Int = AudioFormat.ENCODING_PCM_16BIT
    const val ENCODING_PCM_16BIT_BIG_ENDIAN: Int = 0x10000000
    const val ENCODING_PCM_24BIT: Int = AudioFormat.ENCODING_PCM_24BIT_PACKED
    const val ENCODING_PCM_24BIT_BIG_ENDIAN: Int = 0x50000000
    const val ENCODING_PCM_32BIT: Int = AudioFormat.ENCODING_PCM_32BIT
    const val ENCODING_PCM_32BIT_BIG_ENDIAN: Int = 0x60000000
    const val ENCODING_PCM_FLOAT: Int = AudioFormat.ENCODING_PCM_FLOAT
    const val ENCODING_PCM_FLOAT_BIG_ENDIAN: Int = 0x71000000
    const val ENCODING_PCM_DOUBLE: Int = 0x70000000
    const val ENCODING_PCM_DOUBLE_BIG_ENDIAN: Int = 0x72000000
    const val ENCODING_MP3: Int = AudioFormat.ENCODING_MP3
    const val ENCODING_AAC_LC: Int = AudioFormat.ENCODING_AAC_LC
    const val ENCODING_AAC_HE_V1: Int = AudioFormat.ENCODING_AAC_HE_V1
    const val ENCODING_AAC_HE_V2: Int = AudioFormat.ENCODING_AAC_HE_V2
    const val ENCODING_AAC_XHE: Int = AudioFormat.ENCODING_AAC_XHE
    const val ENCODING_AAC_ELD: Int = AudioFormat.ENCODING_AAC_ELD
    const val ENCODING_AAC_ER_BSAC: Int = 0x40000000
    const val ENCODING_AC3: Int = AudioFormat.ENCODING_AC3
    const val ENCODING_E_AC3: Int = AudioFormat.ENCODING_E_AC3
    const val ENCODING_E_AC3_JOC: Int = AudioFormat.ENCODING_E_AC3_JOC
    const val ENCODING_AC4: Int = AudioFormat.ENCODING_AC4
    const val ENCODING_DTS: Int = AudioFormat.ENCODING_DTS
    const val ENCODING_DTS_HD: Int = AudioFormat.ENCODING_DTS_HD
    const val ENCODING_DTS_UHD_P2: Int = AudioFormat.ENCODING_DTS_UHD_P2
    const val ENCODING_DOLBY_TRUEHD: Int = AudioFormat.ENCODING_DOLBY_TRUEHD
    const val ENCODING_OPUS: Int = AudioFormat.ENCODING_OPUS
    const val ENCODING_DSD: Int = AudioFormat.ENCODING_DSD

    const val SPATIALIZATION_BEHAVIOR_AUTO: Int = AudioAttributes.SPATIALIZATION_BEHAVIOR_AUTO

    const val SPATIALIZATION_BEHAVIOR_NEVER: Int = AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER

    const val STREAM_TYPE_ALARM: Int = AudioManager.STREAM_ALARM
    const val STREAM_TYPE_DTMF: Int = AudioManager.STREAM_DTMF
    const val STREAM_TYPE_MUSIC: Int = AudioManager.STREAM_MUSIC
    const val STREAM_TYPE_NOTIFICATION: Int = AudioManager.STREAM_NOTIFICATION
    const val STREAM_TYPE_RING: Int = AudioManager.STREAM_RING
    const val STREAM_TYPE_SYSTEM: Int = AudioManager.STREAM_SYSTEM
    const val STREAM_TYPE_VOICE_CALL: Int = AudioManager.STREAM_VOICE_CALL
    const val STREAM_TYPE_ACCESSIBILITY: Int = AudioManager.STREAM_ACCESSIBILITY
    const val STREAM_TYPE_DEFAULT: Int = STREAM_TYPE_MUSIC

    const val VOLUME_FLAG_SHOW_UI: Int = AudioManager.FLAG_SHOW_UI
    const val VOLUME_FLAG_ALLOW_RINGER_MODES: Int = AudioManager.FLAG_ALLOW_RINGER_MODES
    const val VOLUME_FLAG_PLAY_SOUND: Int = AudioManager.FLAG_PLAY_SOUND
    const val VOLUME_FLAG_REMOVE_SOUND_AND_VIBRATE: Int = AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE
    const val VOLUME_FLAG_VIBRATE: Int = AudioManager.FLAG_VIBRATE

    const val VOLUME_OPERATION_TYPE_SET_VOLUME: Int = 0
    const val VOLUME_OPERATION_TYPE_MUTE: Int = 1
    const val VOLUME_OPERATION_TYPE_UNMUTE: Int = 2

    const val AUDIO_CONTENT_TYPE_MOVIE: Int = AudioAttributes.CONTENT_TYPE_MOVIE
    const val AUDIO_CONTENT_TYPE_MUSIC: Int = AudioAttributes.CONTENT_TYPE_MUSIC
    const val AUDIO_CONTENT_TYPE_SONIFICATION: Int = AudioAttributes.CONTENT_TYPE_SONIFICATION
    const val CONTENT_TYPE_SONIFICATION: Int = AUDIO_CONTENT_TYPE_SONIFICATION
    const val AUDIO_CONTENT_TYPE_SPEECH: Int = AudioAttributes.CONTENT_TYPE_SPEECH
    const val AUDIO_CONTENT_TYPE_UNKNOWN: Int = AudioAttributes.CONTENT_TYPE_UNKNOWN

    const val FLAG_AUDIBILITY_ENFORCED: Int = AudioAttributes.FLAG_AUDIBILITY_ENFORCED

    const val USAGE_ALARM: Int = AudioAttributes.USAGE_ALARM
    const val USAGE_ASSISTANCE_ACCESSIBILITY: Int = AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
    const val USAGE_ASSISTANCE_NAVIGATION_GUIDANCE: Int =
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    const val USAGE_ASSISTANCE_SONIFICATION: Int = AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
    const val USAGE_ASSISTANT: Int = AudioAttributes.USAGE_ASSISTANT
    const val USAGE_GAME: Int = AudioAttributes.USAGE_GAME
    const val USAGE_MEDIA: Int = AudioAttributes.USAGE_MEDIA
    const val USAGE_NOTIFICATION: Int = AudioAttributes.USAGE_NOTIFICATION
    const val USAGE_NOTIFICATION_COMMUNICATION_DELAYED: Int =
        AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED
    const val USAGE_NOTIFICATION_COMMUNICATION_INSTANT: Int =
        AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT
    const val USAGE_NOTIFICATION_COMMUNICATION_REQUEST: Int =
        AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST
    const val USAGE_NOTIFICATION_EVENT: Int = AudioAttributes.USAGE_NOTIFICATION_EVENT
    const val USAGE_NOTIFICATION_RINGTONE: Int = AudioAttributes.USAGE_NOTIFICATION_RINGTONE
    const val USAGE_UNKNOWN: Int = AudioAttributes.USAGE_UNKNOWN
    const val USAGE_VOICE_COMMUNICATION: Int = AudioAttributes.USAGE_VOICE_COMMUNICATION
    const val USAGE_VOICE_COMMUNICATION_SIGNALLING: Int =
        AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING

    const val ALLOW_CAPTURE_BY_ALL: Int = AudioAttributes.ALLOW_CAPTURE_BY_ALL
    const val ALLOW_CAPTURE_BY_NONE: Int = AudioAttributes.ALLOW_CAPTURE_BY_NONE
    const val ALLOW_CAPTURE_BY_SYSTEM: Int = AudioAttributes.ALLOW_CAPTURE_BY_SYSTEM

    const val VIDEO_CODEC_FLAG_H264: Int = 1
    const val VIDEO_CODEC_FLAG_H265: Int = 2

    const val BUFFER_FLAG_KEY_FRAME: Int = MediaCodec.BUFFER_FLAG_KEY_FRAME
    const val BUFFER_FLAG_END_OF_STREAM: Int = MediaCodec.BUFFER_FLAG_END_OF_STREAM
    const val BUFFER_FLAG_NOT_DEPENDED_ON: Int = 1 shl 26 // 0x04000000
    const val BUFFER_FLAG_FIRST_SAMPLE: Int = 1 shl 27 // 0x08000000
    const val BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA: Int = 1 shl 28 // 0x10000000
    const val BUFFER_FLAG_LAST_SAMPLE: Int = 1 shl 29 // 0x20000000
    const val BUFFER_FLAG_ENCRYPTED: Int = 1 shl 30 // 0x40000000
    const val MEDIA_CODEC_PRIORITY_REALTIME: Int = 0
    const val MEDIA_CODEC_PRIORITY_NON_REALTIME: Int = 1

    const val VIDEO_OUTPUT_MODE_NONE: Int = -1
    const val VIDEO_OUTPUT_MODE_YUV: Int = 0
    const val VIDEO_OUTPUT_MODE_SURFACE_YUV: Int = 1

    const val VIDEO_SCALING_MODE_SCALE_TO_FIT: Int = MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT
    const val VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING: Int =
        MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
    const val VIDEO_SCALING_MODE_DEFAULT: Int = VIDEO_SCALING_MODE_SCALE_TO_FIT

    const val VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF: Int = Int.MIN_VALUE
    const val VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS: Int =
        Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS

    const val SELECTION_FLAG_DEFAULT: Int = 1
    const val SELECTION_FLAG_FORCED: Int = 1 shl 1 // 2
    const val SELECTION_FLAG_AUTOSELECT: Int = 1 shl 2 // 4
    const val LANGUAGE_UNDETERMINED: String = "und"

    const val CONTENT_TYPE_DASH: Int = 0
    const val CONTENT_TYPE_SS: Int = 1
    const val CONTENT_TYPE_HLS: Int = 2
    const val CONTENT_TYPE_RTSP: Int = 3
    const val CONTENT_TYPE_OTHER: Int = 4
    const val RESULT_END_OF_INPUT: Int = -1
    const val RESULT_MAX_LENGTH_EXCEEDED: Int = -2
    const val RESULT_NOTHING_READ: Int = -3
    const val RESULT_BUFFER_READ: Int = -4
    const val RESULT_FORMAT_READ: Int = -5

    const val DATA_TYPE_UNKNOWN: Int = 0
    const val DATA_TYPE_MEDIA: Int = 1
    const val DATA_TYPE_MEDIA_INITIALIZATION: Int = 2
    const val DATA_TYPE_DRM: Int = 3
    const val DATA_TYPE_MANIFEST: Int = 4
    const val DATA_TYPE_TIME_SYNCHRONIZATION: Int = 5
    const val DATA_TYPE_AD: Int = 6
    const val DATA_TYPE_MEDIA_PROGRESSIVE_LIVE: Int = 7
    const val DATA_TYPE_STEERING_MANIFEST: Int = 8
    const val DATA_TYPE_CUSTOM_BASE: Int = 10000

    const val TRACK_TYPE_NONE: Int = -2
    const val TRACK_TYPE_UNKNOWN: Int = -1
    const val TRACK_TYPE_DEFAULT: Int = 0
    const val TRACK_TYPE_AUDIO: Int = 1
    const val TRACK_TYPE_VIDEO: Int = 2
    const val TRACK_TYPE_TEXT: Int = 3
    const val TRACK_TYPE_IMAGE: Int = 4
    const val TRACK_TYPE_METADATA: Int = 5
    const val TRACK_TYPE_CAMERA_MOTION: Int = 6
    const val TRACK_TYPE_CUSTOM_BASE: Int = 10000

    const val SELECTION_REASON_UNKNOWN: Int = 0
    const val SELECTION_REASON_INITIAL: Int = 1
    const val SELECTION_REASON_MANUAL: Int = 2
    const val SELECTION_REASON_ADAPTIVE: Int = 3
    const val SELECTION_REASON_TRICK_PLAY: Int = 4
    const val SELECTION_REASON_CUSTOM_BASE: Int = 10000
    const val DEFAULT_BUFFER_SEGMENT_SIZE: Int = 64 * 1024
    const val DEFAULT_SEEK_BACK_INCREMENT_MS: Long = 5000
    const val DEFAULT_SEEK_FORWARD_INCREMENT_MS: Long = 15000
    const val DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS: Long = 3000
    const val CENC_TYPE_cenc: String = "cenc"
    const val CENC_TYPE_cbc1: String = "cbc1"
    const val CENC_TYPE_cens: String = "cens"
    const val CENC_TYPE_cbcs: String = "cbcs"
    
    val UUID_NIL: UUID = UUID(0L, 0L)
    
    val COMMON_PSSH_UUID: UUID = UUID(0x1077EFECC0B24D02L, -0x531cc3e1ad1d04b5L)
    
    val CLEARKEY_UUID: UUID = UUID(-0x1d8e62a7567a4c37L, 0x781AB030AF78D30EL)
    
    val WIDEVINE_UUID: UUID = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
    
    val PLAYREADY_UUID: UUID = UUID(-0x65fb0f8667bfbd7aL, -0x546d19a41f77a06bL)

    const val STEREO_MODE_MONO: Int = 0
    const val STEREO_MODE_TOP_BOTTOM: Int = 1
    const val STEREO_MODE_LEFT_RIGHT: Int = 2
    const val STEREO_MODE_STEREO_MESH: Int = 3
    const val STEREO_MODE_INTERLEAVED_LEFT_PRIMARY: Int = 4
    const val STEREO_MODE_INTERLEAVED_RIGHT_PRIMARY: Int = 5

    const val COLOR_SPACE_BT601: Int = MediaFormat.COLOR_STANDARD_BT601_PAL
    const val COLOR_SPACE_BT709: Int = MediaFormat.COLOR_STANDARD_BT709
    const val COLOR_SPACE_BT2020: Int = MediaFormat.COLOR_STANDARD_BT2020

    const val COLOR_TRANSFER_LINEAR: Int = MediaFormat.COLOR_TRANSFER_LINEAR
    const val COLOR_TRANSFER_SDR: Int = MediaFormat.COLOR_TRANSFER_SDR_VIDEO

    // Value sourced from ordering here:
    // https://cs.android.com/android/platform/superproject/+/master:frameworks/native/headers/media_plugin/media/hardware/VideoAPI.h;drc=55e9bd7c487ee235631f302ab8626776547ac913;l=138.
    const val COLOR_TRANSFER_SRGB: Int = 2
    const val COLOR_TRANSFER_GAMMA_2_2: Int = 10
    const val COLOR_TRANSFER_ST2084: Int = MediaFormat.COLOR_TRANSFER_ST2084
    const val COLOR_TRANSFER_HLG: Int = MediaFormat.COLOR_TRANSFER_HLG

    const val COLOR_RANGE_LIMITED: Int = MediaFormat.COLOR_RANGE_LIMITED
    const val COLOR_RANGE_FULL: Int = MediaFormat.COLOR_RANGE_FULL

    const val PROJECTION_RECTANGULAR: Int = 0
    const val PROJECTION_EQUIRECTANGULAR: Int = 1
    const val PROJECTION_CUBEMAP: Int = 2
    const val PROJECTION_MESH: Int = 3

    const val PRIORITY_MAX: Int = 0
    const val PRIORITY_PLAYBACK: Int = PRIORITY_MAX - 1000
    const val PRIORITY_PROCESSING_FOREGROUND: Int = PRIORITY_PLAYBACK - 1000
    const val PRIORITY_PLAYBACK_PRELOAD: Int = PRIORITY_PROCESSING_FOREGROUND - 1000
    const val PRIORITY_DOWNLOAD: Int = PRIORITY_PLAYBACK_PRELOAD - 1000
    const val PRIORITY_PROCESSING_BACKGROUND: Int = PRIORITY_DOWNLOAD

    const val NETWORK_TYPE_UNKNOWN: Int = 0
    const val NETWORK_TYPE_OFFLINE: Int = 1
    const val NETWORK_TYPE_WIFI: Int = 2
    const val NETWORK_TYPE_2G: Int = 3
    const val NETWORK_TYPE_3G: Int = 4
    const val NETWORK_TYPE_4G: Int = 5
    const val NETWORK_TYPE_5G_SA: Int = 9
    const val NETWORK_TYPE_5G_NSA: Int = 10
    const val NETWORK_TYPE_CELLULAR_UNKNOWN: Int = 6
    const val NETWORK_TYPE_ETHERNET: Int = 7
    const val NETWORK_TYPE_OTHER: Int = 8

    const val WAKE_MODE_NONE: Int = 0
    const val WAKE_MODE_LOCAL: Int = 1
    const val WAKE_MODE_NETWORK: Int = 2

    const val ROLE_FLAG_MAIN: Int = 1
    const val ROLE_FLAG_ALTERNATE: Int = 1 shl 1
    const val ROLE_FLAG_SUPPLEMENTARY: Int = 1 shl 2
    const val ROLE_FLAG_COMMENTARY: Int = 1 shl 3
    const val ROLE_FLAG_DUB: Int = 1 shl 4
    const val ROLE_FLAG_EMERGENCY: Int = 1 shl 5
    const val ROLE_FLAG_CAPTION: Int = 1 shl 6
    const val ROLE_FLAG_SUBTITLE: Int = 1 shl 7
    const val ROLE_FLAG_SIGN: Int = 1 shl 8
    const val ROLE_FLAG_DESCRIBES_VIDEO: Int = 1 shl 9
    const val ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND: Int = 1 shl 10
    const val ROLE_FLAG_ENHANCED_DIALOG_INTELLIGIBILITY: Int = 1 shl 11
    const val ROLE_FLAG_TRANSCRIBES_DIALOG: Int = 1 shl 12
    const val ROLE_FLAG_EASY_TO_READ: Int = 1 shl 13
    const val ROLE_FLAG_TRICK_PLAY: Int = 1 shl 14
    const val ROLE_FLAG_AUXILIARY: Int = 1 shl 15

    const val AUXILIARY_TRACK_TYPE_UNDEFINED: Int = 0
    const val AUXILIARY_TRACK_TYPE_ORIGINAL: Int = 1
    const val AUXILIARY_TRACK_TYPE_DEPTH_LINEAR: Int = 2
    const val AUXILIARY_TRACK_TYPE_DEPTH_INVERSE: Int = 3
    const val AUXILIARY_TRACK_TYPE_DEPTH_METADATA: Int = 4

    const val FORMAT_HANDLED: Int = 4
    const val FORMAT_EXCEEDS_CAPABILITIES: Int = 3
    const val FORMAT_UNSUPPORTED_DRM: Int = 2
    const val FORMAT_UNSUPPORTED_SUBTYPE: Int = 1
    const val FORMAT_UNSUPPORTED_TYPE: Int = 0

    const val FIRST_FRAME_NOT_RENDERED_ONLY_ALLOWED_IF_STARTED: Int = 0
    const val FIRST_FRAME_NOT_RENDERED: Int = 1
    const val FIRST_FRAME_NOT_RENDERED_AFTER_STREAM_CHANGE: Int = 2
    const val FIRST_FRAME_RENDERED: Int = 3

    const val TEXTURE_MIN_FILTER_LINEAR: Int = GLES20.GL_LINEAR
    const val TEXTURE_MIN_FILTER_LINEAR_MIPMAP_LINEAR: Int = GLES20.GL_LINEAR_MIPMAP_LINEAR

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        open = true, value = [CRYPTO_TYPE_UNSUPPORTED, CRYPTO_TYPE_NONE, CRYPTO_TYPE_FRAMEWORK]
    )
    annotation class CryptoType

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(CRYPTO_MODE_UNENCRYPTED, CRYPTO_MODE_AES_CTR, CRYPTO_MODE_AES_CBC)
    annotation class CryptoMode

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        Format.NO_VALUE,
        ENCODING_INVALID,
        ENCODING_PCM_8BIT,
        ENCODING_PCM_16BIT,
        ENCODING_PCM_16BIT_BIG_ENDIAN,
        ENCODING_PCM_24BIT,
        ENCODING_PCM_24BIT_BIG_ENDIAN,
        ENCODING_PCM_32BIT,
        ENCODING_PCM_32BIT_BIG_ENDIAN,
        ENCODING_PCM_FLOAT,
        ENCODING_PCM_FLOAT_BIG_ENDIAN,
        ENCODING_PCM_DOUBLE,
        ENCODING_PCM_DOUBLE_BIG_ENDIAN,
        ENCODING_MP3,
        ENCODING_AAC_LC,
        ENCODING_AAC_HE_V1,
        ENCODING_AAC_HE_V2,
        ENCODING_AAC_XHE,
        ENCODING_AAC_ELD,
        ENCODING_AAC_ER_BSAC,
        ENCODING_AC3,
        ENCODING_E_AC3,
        ENCODING_E_AC3_JOC,
        ENCODING_AC4,
        ENCODING_DTS,
        ENCODING_DTS_HD,
        ENCODING_DOLBY_TRUEHD,
        ENCODING_OPUS,
        ENCODING_DTS_UHD_P2,
        ENCODING_DSD
    )
    annotation class Encoding

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        Format.NO_VALUE,
        ENCODING_INVALID,
        ENCODING_PCM_8BIT,
        ENCODING_PCM_16BIT,
        ENCODING_PCM_16BIT_BIG_ENDIAN,
        ENCODING_PCM_24BIT,
        ENCODING_PCM_24BIT_BIG_ENDIAN,
        ENCODING_PCM_32BIT,
        ENCODING_PCM_32BIT_BIG_ENDIAN,
        ENCODING_PCM_FLOAT,
        ENCODING_PCM_FLOAT_BIG_ENDIAN,
        ENCODING_PCM_DOUBLE,
        ENCODING_PCM_DOUBLE_BIG_ENDIAN
    )
    annotation class PcmEncoding

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(SPATIALIZATION_BEHAVIOR_AUTO, SPATIALIZATION_BEHAVIOR_NEVER)
    annotation class SpatializationBehavior

    @SuppressLint("UniqueConstants") // Intentional duplication to set STREAM_TYPE_DEFAULT.
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(
        STREAM_TYPE_ALARM,
        STREAM_TYPE_DTMF,
        STREAM_TYPE_MUSIC,
        STREAM_TYPE_NOTIFICATION,
        STREAM_TYPE_RING,
        STREAM_TYPE_SYSTEM,
        STREAM_TYPE_VOICE_CALL,
        STREAM_TYPE_ACCESSIBILITY,
        STREAM_TYPE_DEFAULT
    )
    annotation class StreamType

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        flag = true,
        value = [VOLUME_FLAG_SHOW_UI, VOLUME_FLAG_ALLOW_RINGER_MODES, VOLUME_FLAG_PLAY_SOUND, VOLUME_FLAG_REMOVE_SOUND_AND_VIBRATE, VOLUME_FLAG_VIBRATE]
    )
    annotation class VolumeFlags

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        VOLUME_OPERATION_TYPE_SET_VOLUME, VOLUME_OPERATION_TYPE_MUTE, VOLUME_OPERATION_TYPE_UNMUTE
    )
    annotation class VolumeOperationType

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(
        AUDIO_CONTENT_TYPE_MOVIE,
        AUDIO_CONTENT_TYPE_MUSIC,
        AUDIO_CONTENT_TYPE_SONIFICATION,
        AUDIO_CONTENT_TYPE_SPEECH,
        AUDIO_CONTENT_TYPE_UNKNOWN
    )
    annotation class AudioContentType

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(flag = true, value = [FLAG_AUDIBILITY_ENFORCED])
    annotation class AudioFlags

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(
        value = [USAGE_ALARM, USAGE_ASSISTANCE_ACCESSIBILITY, USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, USAGE_ASSISTANCE_SONIFICATION, USAGE_ASSISTANT, USAGE_GAME, USAGE_MEDIA, USAGE_NOTIFICATION, USAGE_NOTIFICATION_COMMUNICATION_DELAYED, USAGE_NOTIFICATION_COMMUNICATION_INSTANT, USAGE_NOTIFICATION_COMMUNICATION_REQUEST, USAGE_NOTIFICATION_EVENT, USAGE_NOTIFICATION_RINGTONE, USAGE_UNKNOWN, USAGE_VOICE_COMMUNICATION, USAGE_VOICE_COMMUNICATION_SIGNALLING]
    )
    annotation class AudioUsage

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(value = [ALLOW_CAPTURE_BY_ALL, ALLOW_CAPTURE_BY_NONE, ALLOW_CAPTURE_BY_SYSTEM])
    annotation class AudioAllowedCapturePolicy

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(flag = true, value = [VIDEO_CODEC_FLAG_H264, VIDEO_CODEC_FLAG_H265])
    annotation class VideoCodecFlags

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        flag = true,
        value = [BUFFER_FLAG_KEY_FRAME, BUFFER_FLAG_END_OF_STREAM, BUFFER_FLAG_NOT_DEPENDED_ON, BUFFER_FLAG_FIRST_SAMPLE, BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA, BUFFER_FLAG_LAST_SAMPLE, BUFFER_FLAG_ENCRYPTED]
    )
    annotation class BufferFlags

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [VIDEO_OUTPUT_MODE_NONE, VIDEO_OUTPUT_MODE_YUV, VIDEO_OUTPUT_MODE_SURFACE_YUV])
    annotation class VideoOutputMode

    @SuppressLint("UniqueConstants") // Intentional duplication to set VIDEO_SCALING_MODE_DEFAULT.
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(
        value = [VIDEO_SCALING_MODE_SCALE_TO_FIT, VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING, VIDEO_SCALING_MODE_DEFAULT]
    )
    annotation class VideoScalingMode

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(value = [VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF, VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS])
    annotation class VideoChangeFrameRateStrategy

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(
        flag = true,
        value = [SELECTION_FLAG_DEFAULT, SELECTION_FLAG_FORCED, SELECTION_FLAG_AUTOSELECT]
    )
    annotation class SelectionFlags

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(
        value = [CONTENT_TYPE_DASH, CONTENT_TYPE_SS, CONTENT_TYPE_HLS, CONTENT_TYPE_RTSP, CONTENT_TYPE_OTHER]
    )
    annotation class ContentType

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        open = true,
        value = [DATA_TYPE_UNKNOWN, DATA_TYPE_MEDIA, DATA_TYPE_MEDIA_INITIALIZATION, DATA_TYPE_DRM, DATA_TYPE_MANIFEST, DATA_TYPE_TIME_SYNCHRONIZATION, DATA_TYPE_AD, DATA_TYPE_MEDIA_PROGRESSIVE_LIVE]
    )
    annotation class DataType

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        open = true,
        value = [TRACK_TYPE_UNKNOWN, TRACK_TYPE_DEFAULT, TRACK_TYPE_AUDIO, TRACK_TYPE_VIDEO,
            TRACK_TYPE_TEXT, TRACK_TYPE_IMAGE, TRACK_TYPE_METADATA, TRACK_TYPE_CAMERA_MOTION, TRACK_TYPE_NONE]
    )
    annotation class TrackType

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        open = true,
        value = [SELECTION_REASON_UNKNOWN, SELECTION_REASON_INITIAL, SELECTION_REASON_MANUAL, SELECTION_REASON_ADAPTIVE, SELECTION_REASON_TRICK_PLAY]
    )
    annotation class SelectionReason

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [Format.NO_VALUE, STEREO_MODE_MONO, STEREO_MODE_TOP_BOTTOM, STEREO_MODE_LEFT_RIGHT, STEREO_MODE_STEREO_MESH, STEREO_MODE_INTERLEAVED_LEFT_PRIMARY, STEREO_MODE_INTERLEAVED_RIGHT_PRIMARY]
    )
    annotation class StereoMode

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [Format.NO_VALUE, COLOR_SPACE_BT601, COLOR_SPACE_BT709, COLOR_SPACE_BT2020])
    annotation class ColorSpace

    // LINT.ThenChange(
    //   util/MediaFormatUtil.java:color_space,
    //   ColorInfo.java:color_space,
    // )
    // LINT.IfChange(color_transfer)
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [Format.NO_VALUE, COLOR_TRANSFER_LINEAR, COLOR_TRANSFER_SDR, COLOR_TRANSFER_SRGB, COLOR_TRANSFER_GAMMA_2_2, COLOR_TRANSFER_ST2084, COLOR_TRANSFER_HLG]
    )
    annotation class ColorTransfer

    // LINT.ThenChange(
    //   util/MediaFormatUtil.java:color_transfer,
    //   ColorInfo.java:color_transfer,
    // ../../../../../../../effect/src/main/res/raw/fragment_shader_transformation_sdr_external_es2.glsl:color_transfer,
    // ../../../../../../../effect/src/main/res/raw/fragment_shader_transformation_external_yuv_es3.glsl:color_transfer,
    // ../../../../../../../effect/src/main/res/raw/fragment_shader_oetf_es3.glsl:color_transfer,
    // )
    // LINT.IfChange(color_range)
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [Format.NO_VALUE, COLOR_RANGE_LIMITED, COLOR_RANGE_FULL])
    annotation class ColorRange

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [Format.NO_VALUE, PROJECTION_RECTANGULAR, PROJECTION_EQUIRECTANGULAR, PROJECTION_CUBEMAP, PROJECTION_MESH]
    )
    annotation class Projection

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        open = true,
        value = [PRIORITY_MAX, PRIORITY_PLAYBACK, PRIORITY_DOWNLOAD, PRIORITY_PLAYBACK_PRELOAD, PRIORITY_PROCESSING_BACKGROUND, PRIORITY_PROCESSING_FOREGROUND]
    )
    annotation class Priority

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(
        value = [NETWORK_TYPE_UNKNOWN, NETWORK_TYPE_OFFLINE, NETWORK_TYPE_WIFI, NETWORK_TYPE_2G, NETWORK_TYPE_3G, NETWORK_TYPE_4G, NETWORK_TYPE_5G_SA, NETWORK_TYPE_5G_NSA, NETWORK_TYPE_CELLULAR_UNKNOWN, NETWORK_TYPE_ETHERNET, NETWORK_TYPE_OTHER]
    )
    annotation class NetworkType

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(value = [WAKE_MODE_NONE, WAKE_MODE_LOCAL, WAKE_MODE_NETWORK])
    annotation class WakeMode

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(
        flag = true,
        value = [ROLE_FLAG_MAIN, ROLE_FLAG_ALTERNATE, ROLE_FLAG_SUPPLEMENTARY, ROLE_FLAG_COMMENTARY, ROLE_FLAG_DUB, ROLE_FLAG_EMERGENCY, ROLE_FLAG_CAPTION, ROLE_FLAG_SUBTITLE, ROLE_FLAG_SIGN, ROLE_FLAG_DESCRIBES_VIDEO, ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND, ROLE_FLAG_ENHANCED_DIALOG_INTELLIGIBILITY, ROLE_FLAG_TRANSCRIBES_DIALOG, ROLE_FLAG_EASY_TO_READ, ROLE_FLAG_TRICK_PLAY, ROLE_FLAG_AUXILIARY]
    )
    annotation class RoleFlags

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(
        value = [AUXILIARY_TRACK_TYPE_UNDEFINED, AUXILIARY_TRACK_TYPE_ORIGINAL, AUXILIARY_TRACK_TYPE_DEPTH_LINEAR, AUXILIARY_TRACK_TYPE_DEPTH_INVERSE, AUXILIARY_TRACK_TYPE_DEPTH_METADATA]
    )
    annotation class AuxiliaryTrackType

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(
        AnnotationTarget.FIELD,
        AnnotationTarget.FUNCTION,
        AnnotationTarget.PROPERTY_GETTER,
        AnnotationTarget.PROPERTY_SETTER,
        AnnotationTarget.VALUE_PARAMETER,
        AnnotationTarget.LOCAL_VARIABLE,
        AnnotationTarget.CLASS,
        AnnotationTarget.TYPE,
        AnnotationTarget.TYPE_PARAMETER
    )
    @IntDef(
        value = [FORMAT_HANDLED, FORMAT_EXCEEDS_CAPABILITIES, FORMAT_UNSUPPORTED_DRM, FORMAT_UNSUPPORTED_SUBTYPE, FORMAT_UNSUPPORTED_TYPE]
    )
    annotation class FormatSupport

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [FIRST_FRAME_NOT_RENDERED_ONLY_ALLOWED_IF_STARTED, FIRST_FRAME_NOT_RENDERED, FIRST_FRAME_NOT_RENDERED_AFTER_STREAM_CHANGE, FIRST_FRAME_RENDERED]
    )
    annotation class FirstFrameState

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [TEXTURE_MIN_FILTER_LINEAR, TEXTURE_MIN_FILTER_LINEAR_MIPMAP_LINEAR])
    annotation class TextureMinFilter
}