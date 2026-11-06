package androidx.media3.exoplayer.upstream

import android.net.Uri
import android.text.TextUtils
import androidx.annotation.StringDef
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes.getAudioMediaMimeType
import androidx.media3.common.MimeTypes.getTrackType
import androidx.media3.common.MimeTypes.getVideoMediaMimeType
import androidx.media3.common.TrackGroup

import androidx.media3.common.util.UriUtil
import androidx.media3.common.util.Util.ceilDivide
import androidx.media3.common.util.Util.formatInvariant
import androidx.media3.common.util.Util.split
import androidx.media3.common.util.Util.usToMs
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.CmcdConfiguration.DataTransmissionMode
import com.google.common.base.Joiner
import com.google.common.base.Preconditions
import com.google.common.collect.ArrayListMultimap
import java.util.Collections
import java.util.regex.Pattern
import kotlin.math.max

class CmcdData private constructor(
    private val cmcdObject: CmcdObject,
    private val cmcdRequest: CmcdRequest,
    private val cmcdSession: CmcdSession,
    private val cmcdStatus: CmcdStatus,
    private val dataTransmissionMode: @DataTransmissionMode Int
) {
    class Factory(
        private val cmcdConfiguration: CmcdConfiguration,
        private val streamingFormat: @StreamingFormat String?
    ) {
        private var trackSelection: ExoTrackSelection? = null
        private var bufferedDurationUs: Long
        private var playbackRate: Float
        private var isLive: Boolean? = null
        private var didRebuffer = false
        private var isBufferEmpty = false
        private var chunkDurationUs: Long
        private var objectType: @ObjectType String? = null
        private var nextObjectRequest: String? = null
        private var nextRangeRequest: String? = null

        init {
            this.bufferedDurationUs = C.TIME_UNSET
            this.playbackRate = C.RATE_UNSET
            this.chunkDurationUs = C.TIME_UNSET
        }

        fun setChunkDurationUs(chunkDurationUs: Long): Factory {
            Preconditions.checkArgument(chunkDurationUs >= 0)
            this.chunkDurationUs = chunkDurationUs
            return this
        }

        fun setObjectType(objectType: @ObjectType String?): Factory {
            this.objectType = objectType
            return this
        }

        fun setNextObjectRequest(nextObjectRequest: String?): Factory {
            this.nextObjectRequest = nextObjectRequest
            return this
        }

        fun setNextRangeRequest(nextRangeRequest: String?): Factory {
            this.nextRangeRequest = nextRangeRequest
            return this
        }

        fun setTrackSelection(trackSelection: ExoTrackSelection?): Factory {
            this.trackSelection = trackSelection
            return this
        }

        fun setBufferedDurationUs(bufferedDurationUs: Long): Factory {
            Preconditions.checkArgument(bufferedDurationUs >= 0)
            this.bufferedDurationUs = bufferedDurationUs
            return this
        }

        fun setPlaybackRate(playbackRate: Float): Factory {
            Preconditions.checkArgument(playbackRate == C.RATE_UNSET || playbackRate > 0)
            this.playbackRate = playbackRate
            return this
        }

        fun setIsLive(isLive: Boolean): Factory {
            this.isLive = isLive
            return this
        }

        fun setDidRebuffer(didRebuffer: Boolean): Factory {
            this.didRebuffer = didRebuffer
            return this
        }

        fun setIsBufferEmpty(isBufferEmpty: Boolean): Factory {
            this.isBufferEmpty = isBufferEmpty
            return this
        }

        fun createCmcdData(): CmcdData {
            val isManifestObjectType: Boolean = isManifestObjectType(objectType)
            if (!isManifestObjectType) {
                Preconditions.checkNotNull<ExoTrackSelection?>(
                    trackSelection,
                    "Track selection must be set"
                )
            }

            if (objectType == null) {
                objectType = getObjectTypeFromFormat(
                    trackSelection!!.selectedFormat!!
                )
            }

            val isMediaObjectType: Boolean = isMediaObjectType(objectType)
            if (isMediaObjectType) {
                Preconditions.checkState(
                    bufferedDurationUs != C.TIME_UNSET,
                    "Buffered duration must be set"
                )
                Preconditions.checkState(
                    chunkDurationUs != C.TIME_UNSET,
                    "Chunk duration must be set"
                )
            }

            val customData =
                cmcdConfiguration.requestConfig.customData
            for (headerKey in customData.keys) {
                validateCustomDataListFormat(customData[headerKey] ?: listOf())
            }

            var bitrateKbps = C.RATE_UNSET_INT
            var topBitrateKbps = C.RATE_UNSET_INT
            var latestBitrateEstimateKbps = C.RATE_UNSET_INT.toLong()
            var requestedMaximumThroughputKbps = C.RATE_UNSET_INT

            if (!isManifestObjectType) {
                val trackSelection = this.trackSelection!!
                val selectedTrackBitrate: Int = trackSelection.selectedFormat.bitrate
                bitrateKbps = ceilDivide(selectedTrackBitrate, 1000)

                val trackGroup: TrackGroup = trackSelection.trackGroup
                var topBitrate = selectedTrackBitrate
                for (i in 0..<trackGroup.length) {
                    topBitrate = max(topBitrate, trackGroup.getFormat(i).bitrate)
                }
                topBitrateKbps = ceilDivide(topBitrate, 1000)

                if (trackSelection.latestBitrateEstimate != C.RATE_UNSET_INT.toLong()) {
                    latestBitrateEstimateKbps =
                        ceilDivide(trackSelection.latestBitrateEstimate, 1000)
                }

                requestedMaximumThroughputKbps =
                    cmcdConfiguration.requestConfig.getRequestedMaximumThroughputKbps(bitrateKbps)
            }

            val cmcdObject = CmcdObject()
            if (cmcdConfiguration.isBitrateLoggingAllowed) {
                cmcdObject.bitrateKbps = bitrateKbps
            }
            if (cmcdConfiguration.isTopBitrateLoggingAllowed) {
                cmcdObject.topBitrateKbps = topBitrateKbps
            }
            if (isMediaObjectType && cmcdConfiguration.isObjectDurationLoggingAllowed) {
                cmcdObject.objectDurationMs = usToMs(chunkDurationUs)
            }
            if (cmcdConfiguration.isObjectTypeLoggingAllowed) {
                cmcdObject.objectType = objectType
            }
            if (customData.containsKey(CmcdConfiguration.KEY_CMCD_OBJECT)) {
                cmcdObject.customDataList =
                    customData[CmcdConfiguration.KEY_CMCD_OBJECT] ?: listOf()
            }

            val cmcdRequest = CmcdRequest()
            if (isMediaObjectType) {
                if (cmcdConfiguration.isBufferLengthLoggingAllowed) {
                    cmcdRequest.bufferLengthMs = usToMs(bufferedDurationUs)
                }
                if (cmcdConfiguration.isDeadlineLoggingAllowed) {
                    cmcdRequest.deadlineMs = usToMs((bufferedDurationUs / playbackRate).toLong())
                }
            }
            if (cmcdConfiguration.isMeasuredThroughputLoggingAllowed) {
                cmcdRequest.measuredThroughputInKbps=latestBitrateEstimateKbps
            }
            if (cmcdConfiguration.isStartupLoggingAllowed) {
                cmcdRequest.startup = didRebuffer || isBufferEmpty
            }
            if (cmcdConfiguration.isNextObjectRequestLoggingAllowed) {
                cmcdRequest.nextObjectRequest = nextObjectRequest
            }
            if (cmcdConfiguration.isNextRangeRequestLoggingAllowed) {
                cmcdRequest.nextRangeRequest = nextRangeRequest
            }
            if (customData.containsKey(CmcdConfiguration.KEY_CMCD_REQUEST)) {
                cmcdRequest.customDataList =  customData[CmcdConfiguration.KEY_CMCD_REQUEST] ?: listOf()
            }

            val cmcdSession = CmcdSession.Builder()
            if (cmcdConfiguration.isContentIdLoggingAllowed) {
                cmcdSession.setContentId(cmcdConfiguration.contentId)
            }
            if (cmcdConfiguration.isSessionIdLoggingAllowed) {
                cmcdSession.setSessionId(cmcdConfiguration.sessionId)
            }
            if (cmcdConfiguration.isStreamingFormatLoggingAllowed) {
                cmcdSession.setStreamingFormat(streamingFormat)
            }
            if (isLive != null && cmcdConfiguration.isStreamTypeLoggingAllowed) {
                cmcdSession.setStreamType(if (isLive!!) STREAM_TYPE_LIVE else STREAM_TYPE_VOD)
            }
            if (cmcdConfiguration.isPlaybackRateLoggingAllowed) {
                cmcdSession.setPlaybackRate(playbackRate)
            }
            if (customData.containsKey(CmcdConfiguration.KEY_CMCD_SESSION)) {
                cmcdSession.setCustomDataList(
                    customData.get(CmcdConfiguration.KEY_CMCD_SESSION) ?: listOf()
                )
            }

            val cmcdStatus = CmcdStatus()
            if (cmcdConfiguration.isMaximumRequestThroughputLoggingAllowed) {
                cmcdStatus.maximumRequestedThroughputKbps = requestedMaximumThroughputKbps
            }
            if (cmcdConfiguration.isBufferStarvationLoggingAllowed) {
                cmcdStatus.bufferStarvation = didRebuffer
            }
            if (customData.containsKey(CmcdConfiguration.KEY_CMCD_STATUS)) {
                cmcdStatus.customDataList =
                    customData[CmcdConfiguration.KEY_CMCD_STATUS] ?: listOf()
            }

            return CmcdData(
                cmcdObject,
                cmcdRequest,
                cmcdSession.build(),
                cmcdStatus,
                cmcdConfiguration.dataTransmissionMode
            )
        }

        private fun validateCustomDataListFormat(customDataList: List<String>) {
            for (customData in customDataList) {
                val key = split(customData, "=")[0]
                Preconditions.checkState(CUSTOM_KEY_NAME_PATTERN.matcher(key).matches())
            }
        }

        companion object {
            private val CUSTOM_KEY_NAME_PATTERN: Pattern = Pattern.compile(".*-.*")

            private fun getObjectTypeFromFormat(format: Format): @ObjectType String? {
                val audioMimeType = getAudioMediaMimeType(format.codecs)
                val videoMimeType = getVideoMediaMimeType(format.codecs)

                if (audioMimeType != null && videoMimeType != null) {
                    return OBJECT_TYPE_MUXED_AUDIO_AND_VIDEO
                }

                var trackType: @C.TrackType Int = getTrackType(format.sampleMimeType)
                if (trackType == C.TRACK_TYPE_UNKNOWN) {
                    trackType = getTrackType(format.containerMimeType)
                }

                if (trackType == C.TRACK_TYPE_AUDIO) {
                    return OBJECT_TYPE_AUDIO_ONLY
                } else if (trackType == C.TRACK_TYPE_VIDEO) {
                    return OBJECT_TYPE_VIDEO_ONLY
                } else {
                    // Track type cannot be mapped to a known media object type.
                    return null
                }
            }

            private fun isManifestObjectType(objectType: @ObjectType String?): Boolean {
                return objectType == OBJECT_TYPE_MANIFEST
            }

            private fun isMediaObjectType(objectType: @ObjectType String?): Boolean {
                return objectType == OBJECT_TYPE_AUDIO_ONLY
                        || objectType == OBJECT_TYPE_VIDEO_ONLY
                        || objectType == OBJECT_TYPE_MUXED_AUDIO_AND_VIDEO
            }
        }
    }

    @Retention(AnnotationRetention.SOURCE)
    @StringDef(value = [STREAMING_FORMAT_DASH, STREAMING_FORMAT_HLS, STREAMING_FORMAT_SS])
    @MustBeDocumented
    @Target(
        AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER
    )
    annotation class StreamingFormat

    @Retention(AnnotationRetention.SOURCE)
    @StringDef(value = [STREAM_TYPE_VOD, STREAM_TYPE_LIVE])
    @MustBeDocumented
    @Target(
        AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER
    )
    annotation class StreamType

    @Retention(AnnotationRetention.SOURCE)
    @StringDef(
        value = [OBJECT_TYPE_INIT_SEGMENT, OBJECT_TYPE_AUDIO_ONLY, OBJECT_TYPE_VIDEO_ONLY, OBJECT_TYPE_MUXED_AUDIO_AND_VIDEO, OBJECT_TYPE_MANIFEST
        ]
    )
    @MustBeDocumented
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    annotation class ObjectType

    fun addToDataSpec(dataSpec: DataSpec): DataSpec {
        val cmcdDataMap: ArrayListMultimap<String, String> =
            ArrayListMultimap.create<String, String>()
        cmcdObject.populateCmcdDataMap(cmcdDataMap)
        cmcdRequest.populateCmcdDataMap(cmcdDataMap)
        cmcdSession.populateCmcdDataMap(cmcdDataMap)
        cmcdStatus.populateCmcdDataMap(cmcdDataMap)

        if (dataTransmissionMode == CmcdConfiguration.MODE_REQUEST_HEADER) {
            val httpRequestHeaders = mutableMapOf<String, String>()
            for (headerKey in cmcdDataMap.keySet()) {
                val headerValues = cmcdDataMap.get(headerKey)
                Collections.sort<String?>(headerValues)
                httpRequestHeaders[headerKey] = COMMA_JOINER.join(headerValues)
            }
            return dataSpec.withAdditionalHeaders(httpRequestHeaders)
        } else {
            val keyValuePairs: MutableList<String> = ArrayList<String>()
            for (values in cmcdDataMap.asMap().values) {
                keyValuePairs.addAll(values)
            }
            Collections.sort<String>(keyValuePairs)
            val uriBuilder =
                dataSpec
                    .uri
                    .buildUpon()
                    .appendQueryParameter(
                        CmcdConfiguration.CMCD_QUERY_PARAMETER_KEY,
                        COMMA_JOINER.join(keyValuePairs)
                    )
            return dataSpec.buildUpon().setUri(uriBuilder.build()).build()
        }
    }

    private class CmcdObject(
        var bitrateKbps: Int = C.RATE_UNSET_INT,
        var topBitrateKbps: Int = C.RATE_UNSET_INT,
        var objectDurationMs: Long = C.TIME_UNSET,
        var objectType: @ObjectType String? = null,
        var customDataList: List<String?> = listOf()
    ) {

        fun populateCmcdDataMap(cmcdDataMap: ArrayListMultimap<String, String>) {
            val keyValuePairs = ArrayList<String?>()
            if (bitrateKbps != C.RATE_UNSET_INT) {
                keyValuePairs.add(CmcdConfiguration.KEY_BITRATE + "=" + bitrateKbps)
            }
            if (topBitrateKbps != C.RATE_UNSET_INT) {
                keyValuePairs.add(CmcdConfiguration.KEY_TOP_BITRATE + "=" + topBitrateKbps)
            }
            if (objectDurationMs != C.TIME_UNSET) {
                keyValuePairs.add(CmcdConfiguration.KEY_OBJECT_DURATION + "=" + objectDurationMs)
            }
            if (!TextUtils.isEmpty(objectType)) {
                keyValuePairs.add(CmcdConfiguration.KEY_OBJECT_TYPE + "=" + objectType)
            }
            keyValuePairs.addAll(customDataList)

            if (!keyValuePairs.isEmpty()) {
                cmcdDataMap.putAll(CmcdConfiguration.KEY_CMCD_OBJECT, keyValuePairs)
            }
        }
    }

    private class CmcdRequest() {
        var bufferLengthMs: Long= C.TIME_UNSET
        var measuredThroughputInKbps: Long= C.RATE_UNSET_INT.toLong()
        var deadlineMs: Long =C.TIME_UNSET
        var startup = false
        var nextObjectRequest: String? = null
        var nextRangeRequest: String? = null
        var customDataList: List<String?> = listOf()

        fun populateCmcdDataMap(
            cmcdDataMap: ArrayListMultimap<String, String>
        ) {
            val keyValuePairs = ArrayList<String?>()
            if (bufferLengthMs != C.TIME_UNSET) {
                keyValuePairs.add(CmcdConfiguration.KEY_BUFFER_LENGTH + "=" + bufferLengthMs)
            }
            if (measuredThroughputInKbps != C.RATE_UNSET_INT.toLong()) {
                keyValuePairs.add(
                    CmcdConfiguration.KEY_MEASURED_THROUGHPUT + "=" + measuredThroughputInKbps
                )
            }
            if (deadlineMs != C.TIME_UNSET) {
                keyValuePairs.add(CmcdConfiguration.KEY_DEADLINE + "=" + deadlineMs)
            }
            if (startup) {
                keyValuePairs.add(CmcdConfiguration.KEY_STARTUP)
            }
            if (!TextUtils.isEmpty(nextObjectRequest)) {
                keyValuePairs.add(
                    formatInvariant(
                        "%s=\"%s\"",
                        CmcdConfiguration.KEY_NEXT_OBJECT_REQUEST,
                        nextObjectRequest
                    )
                )
            }
            if (!TextUtils.isEmpty(nextRangeRequest)) {
                keyValuePairs.add(
                    formatInvariant(
                        "%s=\"%s\"",
                        CmcdConfiguration.KEY_NEXT_RANGE_REQUEST,
                        nextRangeRequest
                    )
                )
            }
            keyValuePairs.addAll(customDataList)

            if (!keyValuePairs.isEmpty()) {
                cmcdDataMap.putAll(CmcdConfiguration.KEY_CMCD_REQUEST, keyValuePairs)
            }
        }
    }

    private class CmcdSession(builder: Builder) {
        class Builder {
            var contentId: String? = null
            var sessionId: String? = null
            var streamingFormat: @StreamingFormat String? = null
            var streamType: @StreamType String? = null
            var playbackRate: Float
            var customDataList: List<String?>

            init {
                this.playbackRate = C.RATE_UNSET
                this.customDataList = listOf()
            }

            fun setContentId(contentId: String?): Builder {
                Preconditions.checkArgument(contentId == null || contentId.length <= CmcdConfiguration.MAX_ID_LENGTH)
                this.contentId = contentId
                return this
            }

            fun setSessionId(sessionId: String?): Builder {
                Preconditions.checkArgument(sessionId == null || sessionId.length <= CmcdConfiguration.MAX_ID_LENGTH)
                this.sessionId = sessionId
                return this
            }

            fun setStreamingFormat(streamingFormat: @StreamingFormat String?): Builder {
                this.streamingFormat = streamingFormat
                return this
            }

            fun setStreamType(streamType: @StreamType String?): Builder {
                this.streamType = streamType
                return this
            }

            fun setPlaybackRate(playbackRate: Float): Builder {
                Preconditions.checkArgument(playbackRate > 0 || playbackRate == C.RATE_UNSET)
                this.playbackRate = playbackRate
                return this
            }

            fun setCustomDataList(customDataList: List<String>): Builder {
                this.customDataList = customDataList.toList()
                return this
            }

            fun build(): CmcdSession {
                return CmcdSession(this)
            }
        }

        val contentId: String?
        val sessionId: String?
        val streamingFormat: @StreamingFormat String?
        val streamType: @StreamType String?
        val playbackRate: Float

        val customDataList: List<String?>

        init {
            this.contentId = builder.contentId
            this.sessionId = builder.sessionId
            this.streamingFormat = builder.streamingFormat
            this.streamType = builder.streamType
            this.playbackRate = builder.playbackRate
            this.customDataList = builder.customDataList
        }

        fun populateCmcdDataMap(
            cmcdDataMap: ArrayListMultimap<String, String>
        ) {
            val keyValuePairs = ArrayList<String?>()
            if (!TextUtils.isEmpty(this.contentId)) {
                keyValuePairs.add(
                    formatInvariant(
                        "%s=\"%s\"",
                        CmcdConfiguration.KEY_CONTENT_ID,
                        contentId
                    )
                )
            }
            if (!TextUtils.isEmpty(this.sessionId)) {
                keyValuePairs.add(
                    formatInvariant(
                        "%s=\"%s\"",
                        CmcdConfiguration.KEY_SESSION_ID,
                        sessionId
                    )
                )
            }
            if (!TextUtils.isEmpty(this.streamingFormat)) {
                keyValuePairs.add(CmcdConfiguration.KEY_STREAMING_FORMAT + "=" + streamingFormat)
            }
            if (!TextUtils.isEmpty(this.streamType)) {
                keyValuePairs.add(CmcdConfiguration.KEY_STREAM_TYPE + "=" + streamType)
            }
            if (playbackRate != C.RATE_UNSET && playbackRate != 1.0f) {
                keyValuePairs.add(
                    formatInvariant(
                        "%s=%.2f",
                        CmcdConfiguration.KEY_PLAYBACK_RATE,
                        playbackRate
                    )
                )
            }
            if (VERSION != 1) {
                keyValuePairs.add(CmcdConfiguration.KEY_VERSION + "=" + VERSION)
            }
            keyValuePairs.addAll(customDataList)

            if (!keyValuePairs.isEmpty()) {
                cmcdDataMap.putAll(CmcdConfiguration.KEY_CMCD_SESSION, keyValuePairs)
            }
        }

        companion object {
            const val VERSION: Int = 1
        }
    }

    private class CmcdStatus(
        var maximumRequestedThroughputKbps: Int = C.RATE_UNSET_INT,
        var bufferStarvation: Boolean = false,
        var customDataList: List<String?> = listOf(),
    ) {

        fun populateCmcdDataMap(cmcdDataMap: ArrayListMultimap<String, String>) {
            val keyValuePairs = ArrayList<String?>()
            if (maximumRequestedThroughputKbps != C.RATE_UNSET_INT) {
                keyValuePairs.add(
                    CmcdConfiguration.KEY_MAXIMUM_REQUESTED_BITRATE + "=" + maximumRequestedThroughputKbps
                )
            }
            if (bufferStarvation) {
                keyValuePairs.add(CmcdConfiguration.KEY_BUFFER_STARVATION)
            }
            keyValuePairs.addAll(customDataList)

            if (!keyValuePairs.isEmpty()) {
                cmcdDataMap.putAll(CmcdConfiguration.KEY_CMCD_STATUS, keyValuePairs)
            }
        }
    }

    companion object {
        const val STREAMING_FORMAT_DASH: String = "d"
        const val STREAMING_FORMAT_HLS: String = "h"
        const val STREAMING_FORMAT_SS: String = "s"
        const val STREAM_TYPE_VOD: String = "v"
        const val STREAM_TYPE_LIVE: String = "l"
        const val OBJECT_TYPE_INIT_SEGMENT: String = "i"
        const val OBJECT_TYPE_AUDIO_ONLY: String = "a"
        const val OBJECT_TYPE_VIDEO_ONLY: String = "v"
        const val OBJECT_TYPE_MUXED_AUDIO_AND_VIDEO: String = "av"
        const val OBJECT_TYPE_MANIFEST: String = "m"

        private val COMMA_JOINER: Joiner = Joiner.on(",")

        fun removeFromDataSpec(dataSpec: DataSpec): DataSpec {
            var dataSpec = dataSpec
            val updatedUri: Uri = removeFromUri(dataSpec.uri)
            if (updatedUri != dataSpec.uri) {
                dataSpec = dataSpec.withUri(updatedUri)
            }
            if (dataSpec.httpRequestHeaders.containsKey(CmcdConfiguration.KEY_CMCD_OBJECT)
                || dataSpec.httpRequestHeaders.containsKey(CmcdConfiguration.KEY_CMCD_REQUEST)
                || dataSpec.httpRequestHeaders.containsKey(CmcdConfiguration.KEY_CMCD_STATUS)
                || dataSpec.httpRequestHeaders.containsKey(CmcdConfiguration.KEY_CMCD_SESSION)
            ) {
                val httpRequestHeaders = mutableMapOf<String, String>()
                for (header in dataSpec.httpRequestHeaders.entries) {
                    if ((header.key != CmcdConfiguration.KEY_CMCD_OBJECT) && (header.key != CmcdConfiguration.KEY_CMCD_REQUEST) && (header.key != CmcdConfiguration.KEY_CMCD_STATUS) && (header.key != CmcdConfiguration.KEY_CMCD_SESSION)) {
                        httpRequestHeaders[header.key] = header.value
                    }
                }
                dataSpec = dataSpec.withRequestHeaders(httpRequestHeaders)
            }
            return dataSpec
        }

        fun removeFromUri(uri: Uri): Uri {
            return if (uri.isHierarchical
                && uri.getQueryParameter(CmcdConfiguration.CMCD_QUERY_PARAMETER_KEY) != null
            )
                UriUtil.removeQueryParameter(
                    uri, CmcdConfiguration.CMCD_QUERY_PARAMETER_KEY
                )
            else
                uri
        }
    }
}
