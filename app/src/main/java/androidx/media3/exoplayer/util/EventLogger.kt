package androidx.media3.exoplayer.util

import android.media.AudioFormat
import android.os.SystemClock
import android.text.TextUtils
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.Log.d
import androidx.media3.common.util.Log.e
import androidx.media3.common.util.Log.getThrowableString

import androidx.media3.common.util.Util.getEncodingString
import androidx.media3.common.util.Util.getFormatSupportString
import androidx.media3.common.util.Util.getTrackTypeString
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.KeyRequestInfo
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.trackselection.MappingTrackSelector
import com.google.common.base.Joiner
import java.io.IOException
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.min

class EventLogger(private val tag: String? = DEFAULT_TAG) :
    AnalyticsListener {
    private val window = Timeline.Window()
    private val period = Timeline.Period()
    private val startTimeMs = SystemClock.elapsedRealtime()

    @Suppress("unused") // Maintain backwards compatibility for callers.
    @Deprecated("Use {@link #EventLogger()}")
    constructor(trackSelector: MappingTrackSelector?) : this(DEFAULT_TAG)

    @Suppress("unused") // Maintain backwards compatibility for callers.
    @Deprecated("Use {@link #EventLogger(String)}")
    constructor(trackSelector: MappingTrackSelector?, tag: String?) : this(tag)

    // AnalyticsListener

    override fun onIsLoadingChanged(eventTime: EventTime, isLoading: Boolean) {
        logd(eventTime, "loading", isLoading.toString())
    }

    override fun onPlaybackStateChanged(
        eventTime: EventTime,
        @Player.State state: @Player.State Int
    ) {
        logd(eventTime, "state", getStateString(state))
    }

    override fun onPlayWhenReadyChanged(
        eventTime: EventTime,
        playWhenReady: Boolean,
        @Player.PlayWhenReadyChangeReason reason: @Player.PlayWhenReadyChangeReason Int
    ) {
        logd(
            eventTime,
            "playWhenReady",
            playWhenReady.toString() + ", " + getPlayWhenReadyChangeReasonString(reason)
        )
    }

    override fun onPlaybackSuppressionReasonChanged(
        eventTime: EventTime,
        @Player.PlaybackSuppressionReason playbackSuppressionReason: @Player.PlaybackSuppressionReason Int
    ) {
        logd(
            eventTime,
            "playbackSuppressionReason",
            getPlaybackSuppressionReasonString(playbackSuppressionReason)
        )
    }

    override fun onIsPlayingChanged(eventTime: EventTime, isPlaying: Boolean) {
        logd(eventTime, "isPlaying", isPlaying.toString())
    }

    override fun onRepeatModeChanged(
        eventTime: EventTime,
        @Player.RepeatMode repeatMode: @Player.RepeatMode Int
    ) {
        logd(eventTime, "repeatMode", getRepeatModeString(repeatMode))
    }

    override fun onShuffleModeChanged(eventTime: EventTime, shuffleModeEnabled: Boolean) {
        logd(eventTime, "shuffleModeEnabled", shuffleModeEnabled.toString())
    }

    override fun onPositionDiscontinuity(
        eventTime: EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        @Player.DiscontinuityReason reason: @Player.DiscontinuityReason Int
    ) {
        val details =
            ("reason="
                    + getDiscontinuityReasonString(reason)
                    + ", PositionInfo:old ["
                    + oldPosition
                    + "], PositionInfo:new ["
                    + newPosition
                    + "]")
        logd(eventTime, "positionDiscontinuity", details)
    }

    override fun onPlaybackParametersChanged(
        eventTime: EventTime, playbackParameters: PlaybackParameters
    ) {
        logd(eventTime, "playbackParameters", playbackParameters.toString())
    }

    override fun onTimelineChanged(
        eventTime: EventTime,
        @Player.TimelineChangeReason reason: @Player.TimelineChangeReason Int
    ) {
        val periodCount = eventTime.timeline!!.periodCount
        val windowCount = eventTime.timeline.windowCount
        logd(
            ("timeline ["
                    + getEventTimeString(eventTime)
                    + ", periodCount="
                    + periodCount
                    + ", windowCount="
                    + windowCount
                    + ", reason="
                    + getTimelineChangeReasonString(reason))
        )
        for (i in 0..<min(periodCount, MAX_TIMELINE_ITEM_LINES)) {
            eventTime.timeline.getPeriod(i, period)
            logd("  " + "period [" + getTimeString(period.durationMs) + "]")
        }
        if (periodCount > MAX_TIMELINE_ITEM_LINES) {
            logd("  ...")
        }
        for (i in 0..<min(windowCount, MAX_TIMELINE_ITEM_LINES)) {
            eventTime.timeline.getWindow(i, window)
            logd(
                ("  "
                        + "window ["
                        + getTimeString(window.durationMs)
                        + ", seekable="
                        + window.isSeekable
                        + ", dynamic="
                        + window.isDynamic
                        + "]")
            )
        }
        if (windowCount > MAX_TIMELINE_ITEM_LINES) {
            logd("  ...")
        }
        logd("]")
    }

    override fun onMediaItemTransition(
        eventTime: EventTime, mediaItem: MediaItem?, reason: Int
    ) {
        logd(
            ("mediaItem ["
                    + getEventTimeString(eventTime)
                    + ", reason="
                    + getMediaItemTransitionReasonString(reason)
                    + "]")
        )
    }

    override fun onPlayerError(eventTime: EventTime, error: PlaybackException) {
        loge(eventTime, "playerFailed", error)
    }

    override fun onTracksChanged(eventTime: EventTime, tracks: Tracks) {
        logd("tracks [" + getEventTimeString(eventTime))
        // Log tracks associated to renderers.
        val trackGroups: List<Tracks.Group> = tracks.groups
        for (groupIndex in trackGroups.indices) {
            val trackGroup = trackGroups[groupIndex]
            logd("  group [ id=" + trackGroup.mediaTrackGroup.id)
            for (trackIndex in 0..<trackGroup.length) {
                val status: String = getTrackStatusString(trackGroup.isTrackSelected(trackIndex))
                val formatSupport: String =
                    getFormatSupportString(trackGroup.getTrackSupport(trackIndex))
                logd(
                    ("    "
                            + status
                            + " Track:"
                            + trackIndex
                            + ", "
                            + Format.toLogString(trackGroup.getTrackFormat(trackIndex))
                            + ", supported="
                            + formatSupport)
                )
            }
            logd("  ]")
        }
        // TODO: Replace this with an override of onMediaMetadataChanged.
        // Log metadata for at most one of the selected tracks.
        var loggedMetadata = false
        var groupIndex = 0
        while (!loggedMetadata && groupIndex < trackGroups.size) {
            val trackGroup = trackGroups[groupIndex]
            var trackIndex = 0
            while (!loggedMetadata && trackIndex < trackGroup.length) {
                if (trackGroup.isTrackSelected(trackIndex)) {
                    val metadata = trackGroup.getTrackFormat(trackIndex).metadata
                    if (metadata != null && metadata.length() > 0) {
                        logd("  Metadata [")
                        printMetadata(metadata, "    ")
                        logd("  ]")
                        loggedMetadata = true
                    }
                }
                trackIndex++
            }
            groupIndex++
        }
        logd("]")
    }

    override fun onMetadata(eventTime: EventTime, metadata: Metadata) {
        logd("metadata [" + getEventTimeString(eventTime))
        printMetadata(metadata, "  ")
        logd("]")
    }

    override fun onAudioEnabled(eventTime: EventTime, decoderCounters: DecoderCounters) {
        logd(eventTime, "audioEnabled")
    }

    override fun onAudioDecoderInitialized(
        eventTime: EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) {
        logd(eventTime, "audioDecoderInitialized", decoderName)
    }

    override fun onAudioInputFormatChanged(
        eventTime: EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?
    ) {
        logd(eventTime, "audioInputFormat", Format.toLogString(format))
    }

    override fun onAudioUnderrun(
        eventTime: EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long
    ) {
        loge(
            eventTime,
            "audioTrackUnderrun",
            bufferSize.toString() + ", " + bufferSizeMs + ", " + elapsedSinceLastFeedMs,
            null
        )
    }

    override fun onAudioDecoderReleased(eventTime: EventTime, decoderName: String) {
        logd(eventTime, "audioDecoderReleased", decoderName)
    }

    override fun onAudioDisabled(eventTime: EventTime, decoderCounters: DecoderCounters) {
        logd(eventTime, "audioDisabled")
    }

    override fun onAudioSessionIdChanged(eventTime: EventTime, audioSessionId: Int) {
        logd(eventTime, "audioSessionId", audioSessionId.toString())
    }

    override fun onAudioAttributesChanged(eventTime: EventTime, audioAttributes: AudioAttributes) {
        logd(
            eventTime,
            "audioAttributes",
            (audioAttributes.contentType
                .toString() + ","
                    + audioAttributes.flags
                    + ","
                    + audioAttributes.usage
                    + ","
                    + audioAttributes.allowedCapturePolicy)
        )
    }

    override fun onSkipSilenceEnabledChanged(eventTime: EventTime, skipSilenceEnabled: Boolean) {
        logd(eventTime, "skipSilenceEnabled", skipSilenceEnabled.toString())
    }

    override fun onVolumeChanged(eventTime: EventTime, volume: Float) {
        logd(eventTime, "volume", volume.toString())
    }

    override fun onAudioTrackInitialized(
        eventTime: EventTime, audioTrackConfig: AudioTrackConfig
    ) {
        logd(eventTime, "audioTrackInit", getAudioTrackConfigString(audioTrackConfig))
    }

    override fun onAudioTrackReleased(
        eventTime: EventTime, audioTrackConfig: AudioTrackConfig
    ) {
        logd(eventTime, "audioTrackReleased", getAudioTrackConfigString(audioTrackConfig))
    }

    override fun onAudioPositionAdvancing(eventTime: EventTime, playoutStartSystemTimeMs: Long) {
        val playoutStartTimeInElapsedRealtimeMs =
            playoutStartSystemTimeMs - System.currentTimeMillis() + SystemClock.elapsedRealtime()
        logd(
            eventTime,
            "audioPositionAdvancing",
            "since " + getTimeString(playoutStartTimeInElapsedRealtimeMs - startTimeMs)
        )
    }

    override fun onVideoEnabled(eventTime: EventTime, decoderCounters: DecoderCounters) {
        logd(eventTime, "videoEnabled")
    }

    override fun onVideoDecoderInitialized(
        eventTime: EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) {
        logd(eventTime, "videoDecoderInitialized", decoderName)
    }

    override fun onVideoInputFormatChanged(
        eventTime: EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?
    ) {
        logd(eventTime, "videoInputFormat", Format.toLogString(format))
    }

    override fun onDroppedVideoFrames(eventTime: EventTime, droppedFrames: Int, elapsedMs: Long) {
        logd(eventTime, "droppedFrames", droppedFrames.toString())
    }

    override fun onVideoDecoderReleased(eventTime: EventTime, decoderName: String) {
        logd(eventTime, "videoDecoderReleased", decoderName)
    }

    override fun onVideoDisabled(eventTime: EventTime, decoderCounters: DecoderCounters) {
        logd(eventTime, "videoDisabled")
    }

    override fun onRenderedFirstFrame(eventTime: EventTime, output: Any, renderTimeMs: Long) {
        logd(eventTime, "renderedFirstFrame", output.toString())
    }

    override fun onVideoSizeChanged(eventTime: EventTime, videoSize: VideoSize) {
        val description =
            StringBuilder("w=" + videoSize.width + ", h=" + videoSize.height)
        if (videoSize.pixelWidthHeightRatio != 1.0f) {
            description.append(", par=").append(videoSize.pixelWidthHeightRatio)
        }
        logd(eventTime, "videoSize", description.toString())
    }

    override fun onLoadError(
        eventTime: EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean
    ) {
        printInternalError(eventTime, "loadError", error)
    }

    override fun onSurfaceSizeChanged(eventTime: EventTime, width: Int, height: Int) {
        logd(eventTime, "surfaceSize", "w=" + width + ", h=" + height)
    }

    override fun onUpstreamDiscarded(eventTime: EventTime, mediaLoadData: MediaLoadData) {
        logd(eventTime, "upstreamDiscarded", Format.toLogString(mediaLoadData.trackFormat))
    }

    override fun onDownstreamFormatChanged(eventTime: EventTime, mediaLoadData: MediaLoadData) {
        logd(eventTime, "downstreamFormat", Format.toLogString(mediaLoadData.trackFormat))
    }

    override fun onDrmSessionAcquired(
        eventTime: EventTime,
        @DrmSession.State state: @DrmSession.State Int
    ) {
        logd(eventTime, "drmSessionAcquired", "state=" + state)
    }

    override fun onDrmSessionManagerError(eventTime: EventTime, error: Exception) {
        printInternalError(eventTime, "drmSessionManagerError", error)
    }

    override fun onDrmKeysRestored(eventTime: EventTime) {
        logd(eventTime, "drmKeysRestored")
    }

    override fun onDrmKeysRemoved(eventTime: EventTime) {
        logd(eventTime, "drmKeysRemoved")
    }

    override fun onDrmKeysLoaded(eventTime: EventTime, keyRequestInfo: KeyRequestInfo) {
        logd(eventTime, "drmKeysLoaded")
    }

    override fun onDrmSessionReleased(eventTime: EventTime) {
        logd(eventTime, "drmSessionReleased")
    }

    override fun onRendererReadyChanged(
        eventTime: EventTime,
        rendererIndex: Int,
        rendererTrackType: @C.TrackType Int,
        isRendererReady: Boolean
    ) {
        logd(
            eventTime,
            "rendererReady",
            ("rendererIndex="
                    + rendererIndex
                    + ", "
                    + getTrackTypeString(rendererTrackType)
                    + ", "
                    + isRendererReady)
        )
    }

    override fun onDroppedSeeksWhileScrubbing(eventTime: EventTime, droppedSeeks: Int) {
        logd(eventTime, "droppedSeeksWhileScrubbing", droppedSeeks.toString())
    }

    protected fun logd(msg: String?) {
        d(tag, msg)
    }

    protected fun loge(msg: String?) {
        e(tag, msg)
    }

    // Internal methods
    private fun logd(eventTime: EventTime, eventName: String?) {
        logd(
            getEventString(
                eventTime,
                eventName,
                null,
                null
            )
        )
    }

    private fun logd(eventTime: EventTime, eventName: String?, eventDescription: String?) {
        logd(getEventString(eventTime, eventName, eventDescription, null))
    }

    private fun loge(eventTime: EventTime, eventName: String?, throwable: Throwable?) {
        loge(getEventString(eventTime, eventName, null, throwable))
    }

    private fun loge(
        eventTime: EventTime,
        eventName: String?,
        eventDescription: String?,
        throwable: Throwable?
    ) {
        loge(getEventString(eventTime, eventName, eventDescription, throwable))
    }

    private fun printInternalError(eventTime: EventTime, type: String?, e: Exception?) {
        loge(eventTime, "internalError", type, e)
    }

    private fun printMetadata(metadata: Metadata, prefix: String?) {
        for (i in 0..<metadata.length()) {
            logd(prefix + metadata.get(i))
        }
    }

    private fun getEventString(
        eventTime: EventTime,
        eventName: String?,
        eventDescription: String?,
        throwable: Throwable?
    ): String {
        var eventString = eventName + " [" + getEventTimeString(eventTime)
        if (throwable is PlaybackException) {
            eventString += ", errorCode=" + throwable.errorCodeName
        }
        if (eventDescription != null) {
            eventString += ", " + eventDescription
        }
        val throwableString = getThrowableString(throwable)
        if (!TextUtils.isEmpty(throwableString)) {
            eventString += "\n  " + throwableString!!.replace("\n", "\n  ") + '\n'
        }
        eventString += "]"
        return eventString
    }

    private fun getEventTimeString(eventTime: EventTime): String {
        var windowPeriodString = "window=" + eventTime.windowIndex
        if (eventTime.mediaPeriodId != null) {
            windowPeriodString +=
                ", period=" + eventTime.timeline!!.getIndexOfPeriod(eventTime.mediaPeriodId.periodUid)
            if (eventTime.mediaPeriodId.isAd) {
                windowPeriodString += ", adGroup=" + eventTime.mediaPeriodId.adGroupIndex
                windowPeriodString += ", ad=" + eventTime.mediaPeriodId.adIndexInAdGroup
            }
        }
        return ("eventTime="
                + getTimeString(eventTime.realtimeMs - startTimeMs)
                + ", mediaPos="
                + getTimeString(eventTime.eventPlaybackPositionMs)
                + ", "
                + windowPeriodString)
    }

    companion object {
        private const val DEFAULT_TAG = "EventLogger"
        private const val MAX_TIMELINE_ITEM_LINES = 3
        private val COMMA_JOINER: Joiner = Joiner.on(", ")
        private val TIME_FORMAT: NumberFormat

        init {
            TIME_FORMAT = NumberFormat.getInstance(Locale.US)
            TIME_FORMAT.setMinimumFractionDigits(2)
            TIME_FORMAT.setMaximumFractionDigits(2)
            TIME_FORMAT.setGroupingUsed(false)
        }

        private fun getTimeString(timeMs: Long): String {
            return if (timeMs == C.TIME_UNSET) "?" else TIME_FORMAT.format(((timeMs) / 1000f).toDouble())
        }

        private fun getStateString(state: Int): String {
            when (state) {
                Player.STATE_BUFFERING -> return "BUFFERING"
                Player.STATE_ENDED -> return "ENDED"
                Player.STATE_IDLE -> return "IDLE"
                Player.STATE_READY -> return "READY"
                else -> return "?"
            }
        }

        private fun getTrackStatusString(selected: Boolean): String {
            return if (selected) "[X]" else "[ ]"
        }

        private fun getRepeatModeString(@Player.RepeatMode repeatMode: @Player.RepeatMode Int): String {
            when (repeatMode) {
                Player.REPEAT_MODE_OFF -> return "OFF"
                Player.REPEAT_MODE_ONE -> return "ONE"
                Player.REPEAT_MODE_ALL -> return "ALL"
                else -> return "?"
            }
        }

        private fun getDiscontinuityReasonString(@Player.DiscontinuityReason reason: @Player.DiscontinuityReason Int): String {
            when (reason) {
                Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> return "AUTO_TRANSITION"
                Player.DISCONTINUITY_REASON_SEEK -> return "SEEK"
                Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> return "SEEK_ADJUSTMENT"
                Player.DISCONTINUITY_REASON_REMOVE -> return "REMOVE"
                Player.DISCONTINUITY_REASON_SKIP -> return "SKIP"
                Player.DISCONTINUITY_REASON_INTERNAL -> return "INTERNAL"
                Player.DISCONTINUITY_REASON_SILENCE_SKIP -> return "SILENCE_SKIP"
                else -> return "?"
            }
        }

        private fun getTimelineChangeReasonString(@Player.TimelineChangeReason reason: @Player.TimelineChangeReason Int): String {
            when (reason) {
                Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE -> return "SOURCE_UPDATE"
                Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED -> return "PLAYLIST_CHANGED"
                else -> return "?"
            }
        }

        private fun getMediaItemTransitionReasonString(
            @Player.MediaItemTransitionReason reason: @Player.MediaItemTransitionReason Int
        ): String {
            when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> return "AUTO"
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> return "PLAYLIST_CHANGED"
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> return "REPEAT"
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> return "SEEK"
                else -> return "?"
            }
        }

        private fun getPlaybackSuppressionReasonString(
            @Player.PlaybackSuppressionReason playbackSuppressionReason: @Player.PlaybackSuppressionReason Int
        ): String {
            when (playbackSuppressionReason) {
                Player.PLAYBACK_SUPPRESSION_REASON_NONE -> return "NONE"
                Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> return "TRANSIENT_AUDIO_FOCUS_LOSS"
                Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT -> return "UNSUITABLE_AUDIO_OUTPUT"
                Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING -> return "SCRUBBING"
                else -> return "?"
            }
        }

        private fun getPlayWhenReadyChangeReasonString(
            @Player.PlayWhenReadyChangeReason reason: @Player.PlayWhenReadyChangeReason Int
        ): String {
            when (reason) {
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> return "AUDIO_BECOMING_NOISY"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> return "AUDIO_FOCUS_LOSS"
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> return "REMOTE"
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> return "USER_REQUEST"
                Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> return "END_OF_MEDIA_ITEM"
                else -> return "?"
            }
        }

        private fun getAudioTrackConfigString(audioTrackConfig: AudioTrackConfig): String {
            val result: MutableList<String?> = ArrayList<String?>()
            if (audioTrackConfig.encoding != Format.NO_VALUE) {
                result.add("enc=" + getEncodingString(audioTrackConfig.encoding))
            }
            result.add("channelConf=" + channelConfigAsString(audioTrackConfig.channelConfig))
            result.add("sampleRate=" + audioTrackConfig.sampleRate)
            result.add("bufferSize=" + audioTrackConfig.bufferSize)
            if (audioTrackConfig.tunneling) {
                result.add("tunneling")
            }
            if (audioTrackConfig.offload) {
                result.add("offload")
            }
            return COMMA_JOINER.join(result)
        }

        private fun channelConfigAsString(channelConfig: Int): String {
            return when (channelConfig) {
                AudioFormat.CHANNEL_OUT_MONO -> "mono"
                AudioFormat.CHANNEL_OUT_STEREO -> "stereo"
                AudioFormat.CHANNEL_OUT_QUAD -> "quad"
                AudioFormat.CHANNEL_OUT_5POINT1 -> "5.1"
                AudioFormat.CHANNEL_OUT_5POINT1POINT2 -> "5.1.2"
                AudioFormat.CHANNEL_OUT_5POINT1POINT4 -> "5.1.4"
                AudioFormat.CHANNEL_OUT_7POINT1_SURROUND -> "7.1"
                AudioFormat.CHANNEL_OUT_7POINT1POINT2 -> "7.1.2"
                AudioFormat.CHANNEL_OUT_7POINT1POINT4 -> "7.1.4"
                AudioFormat.CHANNEL_OUT_9POINT1POINT4 -> "9.1.4"
                AudioFormat.CHANNEL_OUT_9POINT1POINT6 -> "9.1.6"
                else -> "0x" + Integer.toHexString(channelConfig)
            }
        }
    }
}
