package androidx.media3.exoplayer.upstream

import androidx.annotation.IntDef
import androidx.annotation.StringDef
import androidx.media3.common.C
import androidx.media3.common.MediaItem

import com.google.common.base.Preconditions
import java.util.UUID

class CmcdConfiguration(
    sessionId: String?,
    contentId: String?,
    requestConfig: RequestConfig,
    dataTransmissionMode: @DataTransmissionMode Int = MODE_REQUEST_HEADER
) {
    @Retention(AnnotationRetention.SOURCE)
    @StringDef(value = [KEY_CMCD_OBJECT, KEY_CMCD_REQUEST, KEY_CMCD_SESSION, KEY_CMCD_STATUS])
    @MustBeDocumented
    @Target(
        AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER
    )
    annotation class HeaderKey

    @Retention(AnnotationRetention.SOURCE)
    @StringDef(
        value = [KEY_BITRATE, KEY_BUFFER_LENGTH, KEY_CONTENT_ID, KEY_SESSION_ID, KEY_MAXIMUM_REQUESTED_BITRATE, KEY_STREAMING_FORMAT, KEY_STREAM_TYPE, KEY_VERSION, KEY_TOP_BITRATE, KEY_OBJECT_DURATION, KEY_MEASURED_THROUGHPUT, KEY_OBJECT_TYPE, KEY_BUFFER_STARVATION, KEY_DEADLINE, KEY_PLAYBACK_RATE, KEY_STARTUP, KEY_NEXT_OBJECT_REQUEST, KEY_NEXT_RANGE_REQUEST]
    )
    @MustBeDocumented
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    annotation class CmcdKey

    @Retention(AnnotationRetention.SOURCE)
    @IntDef(value = [MODE_REQUEST_HEADER, MODE_QUERY_PARAMETER])
    @MustBeDocumented
    @Target(
        AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER
    )
    annotation class DataTransmissionMode

    fun interface Factory {
        fun createCmcdConfiguration(mediaItem: MediaItem?): CmcdConfiguration?

        companion object {
            val DEFAULT: Factory = CmcdConfiguration.Factory { mediaItem: MediaItem? ->
                CmcdConfiguration(
                    UUID.randomUUID().toString(),
                    mediaItem?.mediaId ?: MediaItem.DEFAULT_MEDIA_ID,
                    object : RequestConfig {})
            }
        }
    }

    interface RequestConfig {

        fun isKeyAllowed(key: @CmcdKey String?): Boolean {
            return true
        }

        val customData: Map<String, List<String>>
            get() = mapOf<@HeaderKey String, List<String>>()

        fun getRequestedMaximumThroughputKbps(throughputKbps: Int): Int {
            return C.RATE_UNSET_INT
        }
    }

    val sessionId: String?
    val contentId: String?
    val requestConfig: RequestConfig
    val dataTransmissionMode: @DataTransmissionMode Int

    init {
        Preconditions.checkArgument(sessionId == null || sessionId.length <= MAX_ID_LENGTH)
        Preconditions.checkArgument(contentId == null || contentId.length <= MAX_ID_LENGTH)
        this.sessionId = sessionId
        this.contentId = contentId
        this.requestConfig = requestConfig
        this.dataTransmissionMode = dataTransmissionMode
    }

    val isBitrateLoggingAllowed: Boolean
        get() = requestConfig.isKeyAllowed(KEY_BITRATE)

    val isBufferLengthLoggingAllowed: Boolean
        get() = requestConfig.isKeyAllowed(KEY_BUFFER_LENGTH)

    val isContentIdLoggingAllowed: Boolean
        get() = requestConfig.isKeyAllowed(KEY_CONTENT_ID)

    val isSessionIdLoggingAllowed: Boolean
        get() = requestConfig.isKeyAllowed(KEY_SESSION_ID)

    val isMaximumRequestThroughputLoggingAllowed: Boolean
        get() = requestConfig.isKeyAllowed(KEY_MAXIMUM_REQUESTED_BITRATE)

    val isStreamingFormatLoggingAllowed: Boolean
        get() = requestConfig.isKeyAllowed(KEY_STREAMING_FORMAT)

    val isStreamTypeLoggingAllowed: Boolean
        get() = requestConfig.isKeyAllowed(KEY_STREAM_TYPE)

    val isTopBitrateLoggingAllowed: Boolean
        get() = requestConfig.isKeyAllowed(KEY_TOP_BITRATE)

    val isObjectDurationLoggingAllowed: Boolean
        get() = requestConfig.isKeyAllowed(KEY_OBJECT_DURATION)

    val isMeasuredThroughputLoggingAllowed: Boolean
        get() = requestConfig.isKeyAllowed(KEY_MEASURED_THROUGHPUT)

    val isObjectTypeLoggingAllowed: Boolean
        get() = requestConfig.isKeyAllowed(KEY_OBJECT_TYPE)

    val isBufferStarvationLoggingAllowed: Boolean
        get() = requestConfig.isKeyAllowed(KEY_BUFFER_STARVATION)

    val isDeadlineLoggingAllowed: Boolean
        get() = requestConfig.isKeyAllowed(KEY_DEADLINE)

    val isPlaybackRateLoggingAllowed: Boolean
        get() = requestConfig.isKeyAllowed(KEY_PLAYBACK_RATE)

    val isStartupLoggingAllowed: Boolean
        get() = requestConfig.isKeyAllowed(KEY_STARTUP)

    val isNextObjectRequestLoggingAllowed: Boolean
        get() = requestConfig.isKeyAllowed(KEY_NEXT_OBJECT_REQUEST)

    val isNextRangeRequestLoggingAllowed: Boolean
        get() = requestConfig.isKeyAllowed(KEY_NEXT_RANGE_REQUEST)

    companion object {
        const val MAX_ID_LENGTH: Int = 64
        const val KEY_CMCD_OBJECT: String = "CMCD-Object"
        const val KEY_CMCD_REQUEST: String = "CMCD-Request"
        const val KEY_CMCD_SESSION: String = "CMCD-Session"
        const val KEY_CMCD_STATUS: String = "CMCD-Status"
        const val CMCD_QUERY_PARAMETER_KEY: String = "CMCD"
        const val KEY_BITRATE: String = "br"
        const val KEY_BUFFER_LENGTH: String = "bl"
        const val KEY_CONTENT_ID: String = "cid"
        const val KEY_SESSION_ID: String = "sid"
        const val KEY_MAXIMUM_REQUESTED_BITRATE: String = "rtp"
        const val KEY_STREAMING_FORMAT: String = "sf"
        const val KEY_STREAM_TYPE: String = "st"
        const val KEY_VERSION: String = "v"
        const val KEY_TOP_BITRATE: String = "tb"
        const val KEY_OBJECT_DURATION: String = "d"
        const val KEY_MEASURED_THROUGHPUT: String = "mtp"
        const val KEY_OBJECT_TYPE: String = "ot"
        const val KEY_BUFFER_STARVATION: String = "bs"
        const val KEY_DEADLINE: String = "dl"
        const val KEY_PLAYBACK_RATE: String = "pr"
        const val KEY_STARTUP: String = "su"
        const val KEY_NEXT_OBJECT_REQUEST: String = "nor"
        const val KEY_NEXT_RANGE_REQUEST: String = "nrr"
        const val MODE_REQUEST_HEADER: Int = 0
        const val MODE_QUERY_PARAMETER: Int = 1
    }
}
