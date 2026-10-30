package androidx.media3.common

import android.os.Bundle
import android.os.RemoteException
import android.os.SystemClock
import android.text.TextUtils
import androidx.annotation.CallSuper
import androidx.annotation.IntDef
import androidx.media3.common.util.Clock
import androidx.media3.common.util.Util.convertToNullIfInvalid
import androidx.media3.common.util.Util.intToStringMaxRadix

open class PlaybackException protected constructor(
    message: String?,
    cause: Throwable?,
    @field:ErrorCode @param:ErrorCode val errorCode: @ErrorCode Int,
    val extras: Bundle?,
    val timestampMs: Long
) : Exception(message, cause) {
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
        open = true,
        value = [ERROR_CODE_INVALID_STATE, ERROR_CODE_BAD_VALUE, ERROR_CODE_PERMISSION_DENIED, ERROR_CODE_NOT_SUPPORTED, ERROR_CODE_DISCONNECTED, ERROR_CODE_AUTHENTICATION_EXPIRED, ERROR_CODE_PREMIUM_ACCOUNT_REQUIRED, ERROR_CODE_CONCURRENT_STREAM_LIMIT, ERROR_CODE_PARENTAL_CONTROL_RESTRICTED, ERROR_CODE_NOT_AVAILABLE_IN_REGION, ERROR_CODE_SKIP_LIMIT_REACHED, ERROR_CODE_SETUP_REQUIRED, ERROR_CODE_END_OF_PLAYLIST, ERROR_CODE_CONTENT_ALREADY_PLAYING, ERROR_CODE_UNSPECIFIED, ERROR_CODE_REMOTE_ERROR, ERROR_CODE_BEHIND_LIVE_WINDOW, ERROR_CODE_TIMEOUT, ERROR_CODE_FAILED_RUNTIME_CHECK, ERROR_CODE_IO_UNSPECIFIED, ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE, ERROR_CODE_IO_BAD_HTTP_STATUS, ERROR_CODE_IO_FILE_NOT_FOUND, ERROR_CODE_IO_NO_PERMISSION, ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED, ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, ERROR_CODE_PARSING_CONTAINER_MALFORMED, ERROR_CODE_PARSING_MANIFEST_MALFORMED, ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED, ERROR_CODE_DECODER_INIT_FAILED, ERROR_CODE_DECODER_QUERY_FAILED, ERROR_CODE_DECODING_FAILED, ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, ERROR_CODE_DECODING_RESOURCES_RECLAIMED, ERROR_CODE_AUDIO_TRACK_INIT_FAILED, ERROR_CODE_AUDIO_TRACK_WRITE_FAILED, ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED, ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED, ERROR_CODE_DRM_UNSPECIFIED, ERROR_CODE_DRM_SCHEME_UNSUPPORTED, ERROR_CODE_DRM_PROVISIONING_FAILED, ERROR_CODE_DRM_CONTENT_ERROR, ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED, ERROR_CODE_DRM_DISALLOWED_OPERATION, ERROR_CODE_DRM_SYSTEM_ERROR, ERROR_CODE_DRM_DEVICE_REVOKED, ERROR_CODE_DRM_LICENSE_EXPIRED
        ]
    )
    annotation class ErrorCode

    val errorCodeName: String
        get() = getErrorCodeName(errorCode)

    constructor(message: String?, cause: Throwable?, @ErrorCode errorCode: @ErrorCode Int) : this(
        message,
        cause,
        errorCode,
        Bundle.EMPTY,
        Clock.DEFAULT.elapsedRealtime()
    )

    constructor(
        message: String?,
        cause: Throwable?,
        @ErrorCode errorCode: @ErrorCode Int,
        extras: Bundle?
    ) : this(message, cause, errorCode, extras, Clock.DEFAULT.elapsedRealtime())

    protected constructor(bundle: Bundle) : this( 
        bundle.getString(FIELD_STRING_MESSAGE),  
        getCauseFromBundle(bundle),  
        bundle.getInt(
            FIELD_INT_ERROR_CODE,  ERROR_CODE_UNSPECIFIED
        ),  
        getExtrasFromBundle(bundle),  
        bundle.getLong(
            FIELD_LONG_TIMESTAMP_MS,  SystemClock.elapsedRealtime()
        )
    )

    @CallSuper
    open fun errorInfoEquals(other: PlaybackException?): Boolean {
        if (this === other) {
            return true
        }
        if (other == null || javaClass != other.javaClass) {
            return false
        }

        val thisCause = cause
        val thatCause = other.cause
        if (thisCause != null && thatCause != null) {
            if (thisCause.message != thatCause.message) {
                return false
            }
            if (thisCause.javaClass != thatCause.javaClass) {
                return false
            }
        } else if (thisCause != null || thatCause != null) {
            return false
        }
        return errorCode == other.errorCode && message == other.message
                && timestampMs == other.timestampMs
    }

    @CallSuper
    fun toBundle(): Bundle {
        val bundle = Bundle()
        bundle.putInt(FIELD_INT_ERROR_CODE, errorCode)
        bundle.putLong(FIELD_LONG_TIMESTAMP_MS, timestampMs)
        bundle.putString(FIELD_STRING_MESSAGE, message)
        bundle.putBundle(FIELD_BUNDLE_EXTRAS, extras)
        val cause = cause
        if (cause != null) {
            bundle.putString(FIELD_STRING_CAUSE_CLASS_NAME, cause.javaClass.getName())
            bundle.putString(FIELD_STRING_CAUSE_MESSAGE, cause.message)
        }
        return bundle
    }

    companion object {
        // Policy errors (-1 to -999)
        const val ERROR_CODE_INVALID_STATE: Int = -2
        const val ERROR_CODE_BAD_VALUE: Int = -3
        const val ERROR_CODE_PERMISSION_DENIED: Int = -4
        const val ERROR_CODE_NOT_SUPPORTED: Int = -6
        const val ERROR_CODE_DISCONNECTED: Int = -100
        const val ERROR_CODE_AUTHENTICATION_EXPIRED: Int = -102
        const val ERROR_CODE_PREMIUM_ACCOUNT_REQUIRED: Int = -103
        const val ERROR_CODE_CONCURRENT_STREAM_LIMIT: Int = -104
        const val ERROR_CODE_PARENTAL_CONTROL_RESTRICTED: Int = -105
        const val ERROR_CODE_NOT_AVAILABLE_IN_REGION: Int = -106
        const val ERROR_CODE_SKIP_LIMIT_REACHED: Int = -107
        const val ERROR_CODE_SETUP_REQUIRED: Int = -108
        const val ERROR_CODE_END_OF_PLAYLIST: Int = -109
        const val ERROR_CODE_CONTENT_ALREADY_PLAYING: Int = -110

        // Miscellaneous errors (1xxx).
        const val ERROR_CODE_UNSPECIFIED: Int = 1000
        const val ERROR_CODE_REMOTE_ERROR: Int = 1001
        const val ERROR_CODE_BEHIND_LIVE_WINDOW: Int = 1002
        const val ERROR_CODE_TIMEOUT: Int = 1003
        const val ERROR_CODE_FAILED_RUNTIME_CHECK: Int = 1004

        // Input/Output errors (2xxx).
        const val ERROR_CODE_IO_UNSPECIFIED: Int = 2000
        const val ERROR_CODE_IO_NETWORK_CONNECTION_FAILED: Int = 2001
        const val ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT: Int = 2002
        const val ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE: Int = 2003
        const val ERROR_CODE_IO_BAD_HTTP_STATUS: Int = 2004
        const val ERROR_CODE_IO_FILE_NOT_FOUND: Int = 2005
        const val ERROR_CODE_IO_NO_PERMISSION: Int = 2006
        const val ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED: Int = 2007
        const val ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE: Int = 2008

        // Content parsing errors (3xxx).
        const val ERROR_CODE_PARSING_CONTAINER_MALFORMED: Int = 3001
        const val ERROR_CODE_PARSING_MANIFEST_MALFORMED: Int = 3002
        const val ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED: Int = 3003
        const val ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED: Int = 3004

        // Decoding errors (4xxx).
        const val ERROR_CODE_DECODER_INIT_FAILED: Int = 4001
        const val ERROR_CODE_DECODER_QUERY_FAILED: Int = 4002
        const val ERROR_CODE_DECODING_FAILED: Int = 4003
        const val ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES: Int = 4004
        const val ERROR_CODE_DECODING_FORMAT_UNSUPPORTED: Int = 4005
        const val ERROR_CODE_DECODING_RESOURCES_RECLAIMED: Int = 4006

        // AudioTrack errors (5xxx).
        const val ERROR_CODE_AUDIO_TRACK_INIT_FAILED: Int = 5001
        const val ERROR_CODE_AUDIO_TRACK_WRITE_FAILED: Int = 5002
        const val ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED: Int = 5003
        const val ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED: Int = 5004

        // DRM errors (6xxx).
        const val ERROR_CODE_DRM_UNSPECIFIED: Int = 6000
        const val ERROR_CODE_DRM_SCHEME_UNSUPPORTED: Int = 6001
        const val ERROR_CODE_DRM_PROVISIONING_FAILED: Int = 6002
        const val ERROR_CODE_DRM_CONTENT_ERROR: Int = 6003
        const val ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED: Int = 6004
        const val ERROR_CODE_DRM_DISALLOWED_OPERATION: Int = 6005
        const val ERROR_CODE_DRM_SYSTEM_ERROR: Int = 6006
        const val ERROR_CODE_DRM_DEVICE_REVOKED: Int = 6007
        const val ERROR_CODE_DRM_LICENSE_EXPIRED: Int = 6008

        // Frame processing errors (7xxx).
        const val ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED: Int = 7000
        const val ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED: Int = 7001
        const val CUSTOM_ERROR_CODE_BASE: Int = 1000000

        fun getErrorCodeName(@ErrorCode errorCode: @ErrorCode Int): String {
            when (errorCode) {
                ERROR_CODE_INVALID_STATE -> return "ERROR_CODE_INVALID_STATE"
                ERROR_CODE_BAD_VALUE -> return "ERROR_CODE_BAD_VALUE"
                ERROR_CODE_PERMISSION_DENIED -> return "ERROR_CODE_PERMISSION_DENIED"
                ERROR_CODE_NOT_SUPPORTED -> return "ERROR_CODE_NOT_SUPPORTED"
                ERROR_CODE_DISCONNECTED -> return "ERROR_CODE_DISCONNECTED"
                ERROR_CODE_AUTHENTICATION_EXPIRED -> return "ERROR_CODE_AUTHENTICATION_EXPIRED"
                ERROR_CODE_PREMIUM_ACCOUNT_REQUIRED -> return "ERROR_CODE_PREMIUM_ACCOUNT_REQUIRED"
                ERROR_CODE_CONCURRENT_STREAM_LIMIT -> return "ERROR_CODE_CONCURRENT_STREAM_LIMIT"
                ERROR_CODE_PARENTAL_CONTROL_RESTRICTED -> return "ERROR_CODE_PARENTAL_CONTROL_RESTRICTED"
                ERROR_CODE_NOT_AVAILABLE_IN_REGION -> return "ERROR_CODE_NOT_AVAILABLE_IN_REGION"
                ERROR_CODE_SKIP_LIMIT_REACHED -> return "ERROR_CODE_SKIP_LIMIT_REACHED"
                ERROR_CODE_SETUP_REQUIRED -> return "ERROR_CODE_SETUP_REQUIRED"
                ERROR_CODE_END_OF_PLAYLIST -> return "ERROR_CODE_END_OF_PLAYLIST"
                ERROR_CODE_CONTENT_ALREADY_PLAYING -> return "ERROR_CODE_CONTENT_ALREADY_PLAYING"
                ERROR_CODE_UNSPECIFIED -> return "ERROR_CODE_UNSPECIFIED"
                ERROR_CODE_REMOTE_ERROR -> return "ERROR_CODE_REMOTE_ERROR"
                ERROR_CODE_BEHIND_LIVE_WINDOW -> return "ERROR_CODE_BEHIND_LIVE_WINDOW"
                ERROR_CODE_TIMEOUT -> return "ERROR_CODE_TIMEOUT"
                ERROR_CODE_FAILED_RUNTIME_CHECK -> return "ERROR_CODE_FAILED_RUNTIME_CHECK"
                ERROR_CODE_IO_UNSPECIFIED -> return "ERROR_CODE_IO_UNSPECIFIED"
                ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> return "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"
                ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> return "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT"
                ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> return "ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE"
                ERROR_CODE_IO_BAD_HTTP_STATUS -> return "ERROR_CODE_IO_BAD_HTTP_STATUS"
                ERROR_CODE_IO_FILE_NOT_FOUND -> return "ERROR_CODE_IO_FILE_NOT_FOUND"
                ERROR_CODE_IO_NO_PERMISSION -> return "ERROR_CODE_IO_NO_PERMISSION"
                ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> return "ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED"
                ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> return "ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE"
                ERROR_CODE_PARSING_CONTAINER_MALFORMED -> return "ERROR_CODE_PARSING_CONTAINER_MALFORMED"
                ERROR_CODE_PARSING_MANIFEST_MALFORMED -> return "ERROR_CODE_PARSING_MANIFEST_MALFORMED"
                ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> return "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED"
                ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> return "ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED"
                ERROR_CODE_DECODER_INIT_FAILED -> return "ERROR_CODE_DECODER_INIT_FAILED"
                ERROR_CODE_DECODER_QUERY_FAILED -> return "ERROR_CODE_DECODER_QUERY_FAILED"
                ERROR_CODE_DECODING_FAILED -> return "ERROR_CODE_DECODING_FAILED"
                ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> return "ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES"
                ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> return "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED"
                ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> return "ERROR_CODE_DECODING_RESOURCES_RECLAIMED"
                ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> return "ERROR_CODE_AUDIO_TRACK_INIT_FAILED"
                ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> return "ERROR_CODE_AUDIO_TRACK_WRITE_FAILED"
                ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED -> return "ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED"
                ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED -> return "ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED"
                ERROR_CODE_DRM_UNSPECIFIED -> return "ERROR_CODE_DRM_UNSPECIFIED"
                ERROR_CODE_DRM_SCHEME_UNSUPPORTED -> return "ERROR_CODE_DRM_SCHEME_UNSUPPORTED"
                ERROR_CODE_DRM_PROVISIONING_FAILED -> return "ERROR_CODE_DRM_PROVISIONING_FAILED"
                ERROR_CODE_DRM_CONTENT_ERROR -> return "ERROR_CODE_DRM_CONTENT_ERROR"
                ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED -> return "ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED"
                ERROR_CODE_DRM_DISALLOWED_OPERATION -> return "ERROR_CODE_DRM_DISALLOWED_OPERATION"
                ERROR_CODE_DRM_SYSTEM_ERROR -> return "ERROR_CODE_DRM_SYSTEM_ERROR"
                ERROR_CODE_DRM_DEVICE_REVOKED -> return "ERROR_CODE_DRM_DEVICE_REVOKED"
                ERROR_CODE_DRM_LICENSE_EXPIRED -> return "ERROR_CODE_DRM_LICENSE_EXPIRED"
                ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED -> return "ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED"
                ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED -> return "ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED"
                else -> if (errorCode >= CUSTOM_ERROR_CODE_BASE) {
                    return "custom error code"
                } else {
                    return "invalid error code"
                }
            }
        }

        fun areErrorInfosEqual(
            playbackException1: PlaybackException?,
            playbackException2: PlaybackException?
        ): Boolean {
            if (playbackException1 != null) {
                return playbackException1.errorInfoEquals(playbackException2)
            }
            return playbackException2 == null
        }

        private val FIELD_INT_ERROR_CODE = intToStringMaxRadix(0)
        private val FIELD_LONG_TIMESTAMP_MS = intToStringMaxRadix(1)
        private val FIELD_STRING_MESSAGE = intToStringMaxRadix(2)
        private val FIELD_STRING_CAUSE_CLASS_NAME = intToStringMaxRadix(3)
        private val FIELD_STRING_CAUSE_MESSAGE = intToStringMaxRadix(4)
        private val FIELD_BUNDLE_EXTRAS = intToStringMaxRadix(5)
        protected const val FIELD_CUSTOM_ID_BASE: Int = 1000

        fun fromBundle(bundle: Bundle): PlaybackException {
            return PlaybackException(bundle)
        }

        // Creates a new {@link Throwable} with possibly {@code null} message.
        @Throws(Exception::class)
        private fun createThrowable(clazz: Class<*>, message: String?): Throwable {
            return clazz.getConstructor(String::class.java).newInstance(message) as Throwable
        }

        // Creates a new {@link RemoteException} with possibly {@code null} message.
        private fun createRemoteException(message: String?): RemoteException {
            return RemoteException(message)
        }

        private fun getExtrasFromBundle(bundle: Bundle): Bundle? {
            val extras = convertToNullIfInvalid(bundle.getBundle(FIELD_BUNDLE_EXTRAS))
            return if (extras != null) extras else Bundle.EMPTY
        }

        private fun getCauseFromBundle(bundle: Bundle): Throwable? {
            val causeClassName = bundle.getString(FIELD_STRING_CAUSE_CLASS_NAME)
            val causeMessage = bundle.getString(FIELD_STRING_CAUSE_MESSAGE)
            var cause: Throwable? = null
            if (!TextUtils.isEmpty(causeClassName)) {
                try {
                    val clazz =
                        Class.forName(
                            causeClassName,  
                            true,
                            PlaybackException::class.java.getClassLoader()
                        )
                    if (Throwable::class.java.isAssignableFrom(clazz)) {
                        cause = createThrowable(clazz, causeMessage)
                    }
                } catch (e: Throwable) {
                    // There was an error while creating the cause using reflection, do nothing here and let the
                    // finally block handle the issue.
                } finally {
                    if (cause == null) {
                        // The bundle has fields to represent the cause, but we were unable to re-create the
                        // exception using reflection. We instantiate a RemoteException to reflect this problem.
                        cause = createRemoteException(causeMessage)
                    }
                }
            }
            return cause
        }
    }
}
