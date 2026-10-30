package androidx.media3.exoplayer.analytics

import android.util.SparseArray
import androidx.annotation.IntDef
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.KeyRequestInfo
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import java.io.IOException
import java.util.Objects

interface AnalyticsListener {
    class Events(private val flags: FlagSet, eventTimes: SparseArray<EventTime>) {
        private val eventTimes: SparseArray<EventTime>

        init {
            val flagsToTimes = SparseArray<EventTime>(flags.size())
            for (i in 0..<flags.size()) {
                val eventFlag: @EventFlags Int = flags[i]
                flagsToTimes.append(eventFlag, eventTimes.get(eventFlag)!!)
            }
            this.eventTimes = flagsToTimes
        }

        fun getEventTime(event: @EventFlags Int): EventTime {
            return eventTimes.get(event)!!
        }

        fun contains(event: @EventFlags Int): Boolean {
            return flags.contains(event)
        }

        fun containsAny(events: IntArray): Boolean {
            return flags.containsAny(events)
        }

        fun size(): Int {
            return flags.size()
        }

        fun get(index: Int): @EventFlags Int {
            return flags.get(index)
        }
    }

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
        value = [EVENT_TIMELINE_CHANGED, EVENT_MEDIA_ITEM_TRANSITION, EVENT_TRACKS_CHANGED, EVENT_IS_LOADING_CHANGED, EVENT_PLAYBACK_STATE_CHANGED, EVENT_PLAY_WHEN_READY_CHANGED, EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED, EVENT_IS_PLAYING_CHANGED, EVENT_REPEAT_MODE_CHANGED, EVENT_SHUFFLE_MODE_ENABLED_CHANGED, EVENT_PLAYER_ERROR, EVENT_POSITION_DISCONTINUITY, EVENT_PLAYBACK_PARAMETERS_CHANGED, EVENT_AVAILABLE_COMMANDS_CHANGED, EVENT_MEDIA_METADATA_CHANGED, EVENT_PLAYLIST_METADATA_CHANGED, EVENT_SEEK_BACK_INCREMENT_CHANGED, EVENT_SEEK_FORWARD_INCREMENT_CHANGED, EVENT_MAX_SEEK_TO_PREVIOUS_POSITION_CHANGED, EVENT_TRACK_SELECTION_PARAMETERS_CHANGED, EVENT_DEVICE_INFO_CHANGED, EVENT_DEVICE_VOLUME_CHANGED, EVENT_LOAD_STARTED, EVENT_LOAD_COMPLETED, EVENT_LOAD_CANCELED, EVENT_LOAD_ERROR, EVENT_DOWNSTREAM_FORMAT_CHANGED, EVENT_UPSTREAM_DISCARDED, EVENT_BANDWIDTH_ESTIMATE, EVENT_METADATA, EVENT_CUES, EVENT_AUDIO_ENABLED, EVENT_AUDIO_DECODER_INITIALIZED, EVENT_AUDIO_INPUT_FORMAT_CHANGED, EVENT_AUDIO_POSITION_ADVANCING, EVENT_AUDIO_UNDERRUN, EVENT_AUDIO_DECODER_RELEASED, EVENT_AUDIO_DISABLED, EVENT_AUDIO_SESSION_ID, EVENT_AUDIO_ATTRIBUTES_CHANGED, EVENT_SKIP_SILENCE_ENABLED_CHANGED, EVENT_AUDIO_SINK_ERROR, EVENT_VOLUME_CHANGED, EVENT_VIDEO_ENABLED, EVENT_VIDEO_DECODER_INITIALIZED, EVENT_VIDEO_INPUT_FORMAT_CHANGED, EVENT_DROPPED_VIDEO_FRAMES, EVENT_VIDEO_DECODER_RELEASED, EVENT_VIDEO_DISABLED, EVENT_VIDEO_FRAME_PROCESSING_OFFSET, EVENT_RENDERED_FIRST_FRAME, EVENT_VIDEO_SIZE_CHANGED, EVENT_SURFACE_SIZE_CHANGED, EVENT_DRM_SESSION_ACQUIRED, EVENT_DRM_KEYS_LOADED, EVENT_DRM_SESSION_MANAGER_ERROR, EVENT_DRM_KEYS_RESTORED, EVENT_DRM_KEYS_REMOVED, EVENT_DRM_SESSION_RELEASED, EVENT_PLAYER_RELEASED, EVENT_AUDIO_CODEC_ERROR, EVENT_VIDEO_CODEC_ERROR, EVENT_AUDIO_TRACK_INITIALIZED, EVENT_AUDIO_TRACK_RELEASED, EVENT_RENDERER_READY_CHANGED]
    )
    annotation class EventFlags

    class EventTime(
        val realtimeMs: Long,
        val timeline: Timeline,
        val windowIndex: Int,
        val mediaPeriodId: MediaPeriodId?,
        val eventPlaybackPositionMs: Long,
        val currentTimeline: Timeline,
        val currentWindowIndex: Int,
        val currentMediaPeriodId: MediaPeriodId?,
        val currentPlaybackPositionMs: Long,
        val totalBufferedDurationMs: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other == null || javaClass != other.javaClass) {
                return false
            }
            val eventTime = other as EventTime
            return realtimeMs == eventTime.realtimeMs && windowIndex == eventTime.windowIndex && eventPlaybackPositionMs == eventTime.eventPlaybackPositionMs && currentWindowIndex == eventTime.currentWindowIndex && currentPlaybackPositionMs == eventTime.currentPlaybackPositionMs && totalBufferedDurationMs == eventTime.totalBufferedDurationMs && timeline == eventTime.timeline
                    && mediaPeriodId == eventTime.mediaPeriodId
                    && currentTimeline == eventTime.currentTimeline
                    && currentMediaPeriodId == eventTime.currentMediaPeriodId
        }

        override fun hashCode(): Int {
            return Objects.hash(
                realtimeMs,
                timeline,
                windowIndex,
                mediaPeriodId,
                eventPlaybackPositionMs,
                currentTimeline,
                currentWindowIndex,
                currentMediaPeriodId,
                currentPlaybackPositionMs,
                totalBufferedDurationMs
            )
        }
    }

    @Deprecated(
        """Use {@link #onPlaybackStateChanged(EventTime, int)} and {@link
   *     #onPlayWhenReadyChanged(EventTime, boolean, int)} instead."""
    )
    fun onPlayerStateChanged(
        eventTime: EventTime,
        playWhenReady: Boolean,
        @Player.State playbackState: @Player.State Int
    ) {
    }

    fun onPlaybackStateChanged(eventTime: EventTime, @Player.State state: @Player.State Int) {
    }

    fun onPlayWhenReadyChanged(
        eventTime: EventTime,
        playWhenReady: Boolean,
        @Player.PlayWhenReadyChangeReason reason: @Player.PlayWhenReadyChangeReason Int
    ) {
    }

    fun onPlaybackSuppressionReasonChanged(
        eventTime: EventTime,
        @Player.PlaybackSuppressionReason playbackSuppressionReason: @Player.PlaybackSuppressionReason Int
    ) {
    }

    fun onIsPlayingChanged(eventTime: EventTime, isPlaying: Boolean) {}

    fun onTimelineChanged(
        eventTime: EventTime,
        @Player.TimelineChangeReason reason: @Player.TimelineChangeReason Int
    ) {
    }

    fun onMediaItemTransition(
        eventTime: EventTime,
        mediaItem: MediaItem?,
        @Player.MediaItemTransitionReason reason: @Player.MediaItemTransitionReason Int
    ) {
    }

    @Deprecated(
        """Use {@link #onPositionDiscontinuity(EventTime, Player.PositionInfo,
   *     Player.PositionInfo, int)} instead."""
    )
    fun onPositionDiscontinuity(
        eventTime: EventTime,
        @Player.DiscontinuityReason reason: @Player.DiscontinuityReason Int
    ) {
    }

    fun onPositionDiscontinuity(
        eventTime: EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        @Player.DiscontinuityReason reason: @Player.DiscontinuityReason Int
    ) {
    }

    @Deprecated(
        """Use {@link #onPositionDiscontinuity(EventTime, Player.PositionInfo,
   *     Player.PositionInfo, int)} instead, listening to changes with {@link
   *     Player#DISCONTINUITY_REASON_SEEK}."""
    )
    fun onSeekStarted(eventTime: EventTime) {
    }

    fun onPlaybackParametersChanged(eventTime: EventTime, playbackParameters: PlaybackParameters) {}
    fun onSeekBackIncrementChanged(eventTime: EventTime, seekBackIncrementMs: Long) {}
    fun onSeekForwardIncrementChanged(eventTime: EventTime, seekForwardIncrementMs: Long) {}
    fun onMaxSeekToPreviousPositionChanged(
        eventTime: EventTime,
        maxSeekToPreviousPositionMs: Long
    ) {
    }

    fun onRepeatModeChanged(
        eventTime: EventTime,
        @Player.RepeatMode repeatMode: @Player.RepeatMode Int
    ) {
    }

    fun onShuffleModeChanged(eventTime: EventTime, shuffleModeEnabled: Boolean) {}
    fun onIsLoadingChanged(eventTime: EventTime, isLoading: Boolean) {}

    @Deprecated("Use {@link #onIsLoadingChanged(EventTime, boolean)} instead.")
    fun onLoadingChanged(eventTime: EventTime, isLoading: Boolean) {
    }

    fun onAvailableCommandsChanged(eventTime: EventTime, availableCommands: Player.Commands) {}
    fun onPlayerError(eventTime: EventTime, error: PlaybackException) {}
    fun onPlayerErrorChanged(eventTime: EventTime, error: PlaybackException?) {}
    fun onTracksChanged(eventTime: EventTime, tracks: Tracks) {}

    fun onTrackSelectionParametersChanged(
        eventTime: EventTime, trackSelectionParameters: TrackSelectionParameters
    ) {
    }

    fun onMediaMetadataChanged(eventTime: EventTime, mediaMetadata: MediaMetadata) {}
    fun onPlaylistMetadataChanged(eventTime: EventTime, playlistMetadata: MediaMetadata) {}

    @Deprecated(
        """Implement {@link #onLoadStarted(EventTime, LoadEventInfo, MediaLoadData, int)}
        instead, and check for {@code retryCount == 0} for equivalent behavior."""
    )
    fun onLoadStarted(
        eventTime: EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData
    ) {
    }

    fun onLoadStarted(
        eventTime: EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        retryCount: Int
    ) {
    }

    fun onLoadCompleted(
        eventTime: EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData
    ) {
    }

    fun onLoadCanceled(
        eventTime: EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData
    ) {
    }

    fun onLoadError(
        eventTime: EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean
    ) {
    }

    fun onDownstreamFormatChanged(eventTime: EventTime, mediaLoadData: MediaLoadData) {}

    fun onUpstreamDiscarded(eventTime: EventTime, mediaLoadData: MediaLoadData) {}

    fun onBandwidthEstimate(
        eventTime: EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long
    ) {
    }

    fun onMetadata(eventTime: EventTime, metadata: Metadata) {}

    @Deprecated("Use {@link #onCues(EventTime, CueGroup)} instead.")
    fun onCues(eventTime: EventTime, cues: List<Cue>) {
    }

    fun onCues(eventTime: EventTime, cueGroup: CueGroup) {}
    fun onAudioEnabled(eventTime: EventTime, decoderCounters: DecoderCounters) {}

    fun onAudioDecoderInitialized(
        eventTime: EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) {
    }

    @Deprecated("Use {@link #onAudioDecoderInitialized(EventTime, String, long, long)}.")
    fun onAudioDecoderInitialized(
        eventTime: EventTime, decoderName: String, initializationDurationMs: Long
    ) {
    }

    fun onAudioInputFormatChanged(
        eventTime: EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?
    ) {
    }

    fun onAudioPositionAdvancing(eventTime: EventTime, playoutStartSystemTimeMs: Long) {
    }

    fun onAudioUnderrun(
        eventTime: EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long
    ) {
    }

    fun onAudioDecoderReleased(eventTime: EventTime, decoderName: String) {}
    fun onAudioDisabled(eventTime: EventTime, decoderCounters: DecoderCounters) {}
    fun onAudioSessionIdChanged(eventTime: EventTime, audioSessionId: Int) {}
    fun onAudioAttributesChanged(eventTime: EventTime, audioAttributes: AudioAttributes) {}
    fun onSkipSilenceEnabledChanged(eventTime: EventTime, skipSilenceEnabled: Boolean) {}
    fun onAudioSinkError(eventTime: EventTime, audioSinkError: Exception) {}
    fun onAudioCodecError(eventTime: EventTime, audioCodecError: Exception) {}
    fun onAudioTrackInitialized(eventTime: EventTime, audioTrackConfig: AudioTrackConfig) {}
    fun onAudioTrackReleased(eventTime: EventTime, audioTrackConfig: AudioTrackConfig) {}
    fun onVolumeChanged(eventTime: EventTime, volume: Float) {}
    fun onDeviceInfoChanged(eventTime: EventTime, deviceInfo: DeviceInfo) {}
    fun onDeviceVolumeChanged(eventTime: EventTime, volume: Int, muted: Boolean) {}
    fun onVideoEnabled(eventTime: EventTime, decoderCounters: DecoderCounters) {}

    fun onVideoDecoderInitialized(
        eventTime: EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) {
    }

    @Deprecated("Use {@link #onVideoDecoderInitialized(EventTime, String, long, long)}.")
    fun onVideoDecoderInitialized(
        eventTime: EventTime, decoderName: String, initializationDurationMs: Long
    ) {
    }

    fun onVideoInputFormatChanged(
        eventTime: EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?
    ) {
    }

    fun onDroppedVideoFrames(eventTime: EventTime, droppedFrames: Int, elapsedMs: Long) {}
    fun onVideoDecoderReleased(eventTime: EventTime, decoderName: String) {}
    fun onVideoDisabled(eventTime: EventTime, decoderCounters: DecoderCounters) {}
    fun onVideoFrameProcessingOffset(
        eventTime: EventTime,
        totalProcessingOffsetUs: Long,
        frameCount: Int
    ) {
    }

    fun onVideoCodecError(eventTime: EventTime, videoCodecError: Exception) {}
    fun onRenderedFirstFrame(eventTime: EventTime, output: Any, renderTimeMs: Long) {}
    fun onVideoSizeChanged(eventTime: EventTime, videoSize: VideoSize) {}

    @Deprecated("Implement {@link #onVideoSizeChanged(EventTime eventTime, VideoSize)} instead.")
    fun onVideoSizeChanged(
        eventTime: EventTime,
        width: Int,
        height: Int,
        unappliedRotationDegrees: Int,
        pixelWidthHeightRatio: Float
    ) {
    }

    fun onSurfaceSizeChanged(eventTime: EventTime, width: Int, height: Int) {}

    @Deprecated("Implement {@link #onDrmSessionAcquired(EventTime, int)} instead.")
    fun onDrmSessionAcquired(eventTime: EventTime) {
    }

    fun onDrmSessionAcquired(
        eventTime: EventTime,
        @DrmSession.State state: @DrmSession.State Int
    ) {
    }

    @Deprecated("Implement {@link #onDrmKeysLoaded(EventTime, KeyRequestInfo)} instead.")
    fun onDrmKeysLoaded(eventTime: EventTime) {
    }

    fun onDrmKeysLoaded(eventTime: EventTime, keyRequestInfo: KeyRequestInfo) {}
    fun onDrmSessionManagerError(eventTime: EventTime, error: Exception) {}
    fun onDrmKeysRestored(eventTime: EventTime) {}
    fun onDrmKeysRemoved(eventTime: EventTime) {}
    fun onDrmSessionReleased(eventTime: EventTime) {}

    fun onRendererReadyChanged(
        eventTime: EventTime,
        rendererIndex: Int,
        rendererTrackType: @C.TrackType Int,
        isRendererReady: Boolean
    ) {
    }

    fun onDroppedSeeksWhileScrubbing(eventTime: EventTime, droppedSeeks: Int) {}
    fun onPlayerReleased(eventTime: EventTime) {}
    fun onEvents(player: Player, events: Events) {}

    companion object {
        const val EVENT_TIMELINE_CHANGED: Int = Player.EVENT_TIMELINE_CHANGED
        const val EVENT_MEDIA_ITEM_TRANSITION: Int = Player.EVENT_MEDIA_ITEM_TRANSITION
        const val EVENT_TRACKS_CHANGED: Int = Player.EVENT_TRACKS_CHANGED
        const val EVENT_IS_LOADING_CHANGED: Int = Player.EVENT_IS_LOADING_CHANGED
        const val EVENT_PLAYBACK_STATE_CHANGED: Int = Player.EVENT_PLAYBACK_STATE_CHANGED
        const val EVENT_PLAY_WHEN_READY_CHANGED: Int = Player.EVENT_PLAY_WHEN_READY_CHANGED
        const val EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED: Int =
            Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED
        const val EVENT_IS_PLAYING_CHANGED: Int = Player.EVENT_IS_PLAYING_CHANGED
        const val EVENT_REPEAT_MODE_CHANGED: Int = Player.EVENT_REPEAT_MODE_CHANGED
        const val EVENT_SHUFFLE_MODE_ENABLED_CHANGED: Int =
            Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED
        const val EVENT_PLAYER_ERROR: Int = Player.EVENT_PLAYER_ERROR
        const val EVENT_POSITION_DISCONTINUITY: Int = Player.EVENT_POSITION_DISCONTINUITY
        const val EVENT_PLAYBACK_PARAMETERS_CHANGED: Int = Player.EVENT_PLAYBACK_PARAMETERS_CHANGED
        const val EVENT_AVAILABLE_COMMANDS_CHANGED: Int = Player.EVENT_AVAILABLE_COMMANDS_CHANGED
        const val EVENT_MEDIA_METADATA_CHANGED: Int = Player.EVENT_MEDIA_METADATA_CHANGED
        const val EVENT_PLAYLIST_METADATA_CHANGED: Int = Player.EVENT_PLAYLIST_METADATA_CHANGED
        const val EVENT_SEEK_BACK_INCREMENT_CHANGED: Int = Player.EVENT_SEEK_BACK_INCREMENT_CHANGED
        const val EVENT_SEEK_FORWARD_INCREMENT_CHANGED: Int =
            Player.EVENT_SEEK_FORWARD_INCREMENT_CHANGED
        const val EVENT_MAX_SEEK_TO_PREVIOUS_POSITION_CHANGED: Int =
            Player.EVENT_MAX_SEEK_TO_PREVIOUS_POSITION_CHANGED
        const val EVENT_TRACK_SELECTION_PARAMETERS_CHANGED: Int =
            Player.EVENT_TRACK_SELECTION_PARAMETERS_CHANGED
        const val EVENT_AUDIO_ATTRIBUTES_CHANGED: Int = Player.EVENT_AUDIO_ATTRIBUTES_CHANGED
        const val EVENT_AUDIO_SESSION_ID: Int = Player.EVENT_AUDIO_SESSION_ID
        const val EVENT_VOLUME_CHANGED: Int = Player.EVENT_VOLUME_CHANGED
        const val EVENT_SKIP_SILENCE_ENABLED_CHANGED: Int =
            Player.EVENT_SKIP_SILENCE_ENABLED_CHANGED
        const val EVENT_SURFACE_SIZE_CHANGED: Int = Player.EVENT_SURFACE_SIZE_CHANGED
        const val EVENT_VIDEO_SIZE_CHANGED: Int = Player.EVENT_VIDEO_SIZE_CHANGED
        const val EVENT_RENDERED_FIRST_FRAME: Int = Player.EVENT_RENDERED_FIRST_FRAME
        const val EVENT_METADATA: Int = Player.EVENT_METADATA
        const val EVENT_CUES: Int = Player.EVENT_CUES
        const val EVENT_DEVICE_INFO_CHANGED: Int = Player.EVENT_DEVICE_INFO_CHANGED
        const val EVENT_DEVICE_VOLUME_CHANGED: Int = Player.EVENT_DEVICE_VOLUME_CHANGED
        const val EVENT_LOAD_STARTED: Int =
            1000 // Intentional gap to leave space for new Player events
        const val EVENT_LOAD_COMPLETED: Int = 1001
        const val EVENT_LOAD_CANCELED: Int = 1002
        const val EVENT_LOAD_ERROR: Int = 1003
        const val EVENT_DOWNSTREAM_FORMAT_CHANGED: Int = 1004
        const val EVENT_UPSTREAM_DISCARDED: Int = 1005
        const val EVENT_BANDWIDTH_ESTIMATE: Int = 1006
        const val EVENT_AUDIO_ENABLED: Int = 1007
        const val EVENT_AUDIO_DECODER_INITIALIZED: Int = 1008
        const val EVENT_AUDIO_INPUT_FORMAT_CHANGED: Int = 1009
        const val EVENT_AUDIO_POSITION_ADVANCING: Int = 1010
        const val EVENT_AUDIO_UNDERRUN: Int = 1011
        const val EVENT_AUDIO_DECODER_RELEASED: Int = 1012
        const val EVENT_AUDIO_DISABLED: Int = 1013
        const val EVENT_AUDIO_SINK_ERROR: Int = 1014
        const val EVENT_VIDEO_ENABLED: Int = 1015
        const val EVENT_VIDEO_DECODER_INITIALIZED: Int = 1016
        const val EVENT_VIDEO_INPUT_FORMAT_CHANGED: Int = 1017
        const val EVENT_DROPPED_VIDEO_FRAMES: Int = 1018
        const val EVENT_VIDEO_DECODER_RELEASED: Int = 1019
        const val EVENT_VIDEO_DISABLED: Int = 1020
        const val EVENT_VIDEO_FRAME_PROCESSING_OFFSET: Int = 1021
        const val EVENT_DRM_SESSION_ACQUIRED: Int = 1022
        const val EVENT_DRM_KEYS_LOADED: Int = 1023
        const val EVENT_DRM_SESSION_MANAGER_ERROR: Int = 1024
        const val EVENT_DRM_KEYS_RESTORED: Int = 1025
        const val EVENT_DRM_KEYS_REMOVED: Int = 1026
        const val EVENT_DRM_SESSION_RELEASED: Int = 1027
        const val EVENT_PLAYER_RELEASED: Int = 1028
        const val EVENT_AUDIO_CODEC_ERROR: Int = 1029
        const val EVENT_VIDEO_CODEC_ERROR: Int = 1030
        const val EVENT_AUDIO_TRACK_INITIALIZED: Int = 1031
        const val EVENT_AUDIO_TRACK_RELEASED: Int = 1032
        const val EVENT_RENDERER_READY_CHANGED: Int = 1033
        const val EVENT_DROPPED_SEEKS_WHILE_SCRUBBING: Int = 1034
    }
}
