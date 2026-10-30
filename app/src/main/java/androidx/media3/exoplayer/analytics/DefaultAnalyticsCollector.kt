package androidx.media3.exoplayer.analytics

import android.os.Looper
import android.util.SparseArray
import androidx.annotation.CallSuper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
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
import androidx.media3.common.util.Clock
import androidx.media3.common.util.HandlerWrapper
import androidx.media3.common.util.ListenerSet
import androidx.media3.common.util.Util.currentOrMainLooper
import androidx.media3.common.util.Util.msToUs
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.KeyRequestInfo
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import com.google.common.base.Preconditions
import com.google.common.collect.Iterables
import java.io.IOException

class DefaultAnalyticsCollector(private val clock: Clock) : AnalyticsCollector {
    private var listeners: ListenerSet<AnalyticsListener> = ListenerSet(currentOrMainLooper)
    private val period: Timeline.Period = Timeline.Period()
    private val window: Timeline.Window = Timeline.Window()
    private val mediaPeriodQueueTracker: MediaPeriodQueueTracker = MediaPeriodQueueTracker(period)
    private val eventTimes: SparseArray<EventTime> = SparseArray<EventTime>()
    private var player: Player? = null
    private var handler: HandlerWrapper? = null
    private var isSeeking = false

    @Suppress("deprecation") // Calling deprecated method.
    @Deprecated("Do not use this method and ensure all calls are made from the correct thread.")
    fun setThrowsWhenUsingWrongThread(throwsWhenUsingWrongThread: Boolean) {
        listeners.setThrowsWhenUsingWrongThread(throwsWhenUsingWrongThread)
    }

    @CallSuper
    override fun addListener(listener: AnalyticsListener) {
        listeners.add(listener)
    }

    @CallSuper
    override fun removeListener(listener: AnalyticsListener) {
        listeners.remove(listener)
    }

    @CallSuper
    override fun setPlayer(player: Player, looper: Looper) {
        Preconditions.checkState(this.player == null || mediaPeriodQueueTracker.mediaPeriodQueue.isEmpty())
        this.player = player
        handler = clock.createHandler(looper, null)
        listeners =
            listeners.copy(
                looper,
                clock
            ) { listener, eventFlags ->
                listener.onEvents(
                    player,
                    AnalyticsListener.Events(eventFlags, eventTimes)
                )
            }
    }

    @CallSuper
    override fun release() {
        // Release lazily so that all events that got triggered as part of player.release()
        // are still delivered to all listeners and onPlayerReleased() is delivered last.
        handler!!.post { this.releaseInternal() }
    }

    override fun updateMediaPeriodQueueInfo(
        queue: List<MediaPeriodId>, readingPeriod: MediaPeriodId?
    ) {
        mediaPeriodQueueTracker.onQueueUpdated(
            queue,
            readingPeriod,
            player!!
        )
    }

    // External events.
    @Suppress("deprecation") // Calling deprecated listener method.
    override fun notifySeekStarted() {
        if (!isSeeking) {
            val eventTime = generateCurrentPlayerMediaPeriodEventTime()
            isSeeking = true
            sendEvent(
                eventTime,  
                C.INDEX_UNSET
            ) { listener: AnalyticsListener ->
                listener.onSeekStarted(eventTime)
            }
        }
    }

    override fun onRendererReadyChanged(
        rendererIndex: Int, rendererTrackType: @C.TrackType Int, isRendererReady: Boolean
    ) {
        val eventTime = generateReadingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_RENDERER_READY_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onRendererReadyChanged(
                eventTime, rendererIndex, rendererTrackType, isRendererReady
            )
        }
    }

    override fun onAudioEnabled(counters: DecoderCounters) {
        val eventTime = generateReadingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_AUDIO_ENABLED
        ) { listener: AnalyticsListener ->
            listener.onAudioEnabled(
                eventTime,
                counters
            )
        }
    }

    @Suppress("deprecation") // Calling deprecated listener method.
    override fun onAudioDecoderInitialized(
        decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long
    ) {
        val eventTime = generateReadingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_AUDIO_DECODER_INITIALIZED
        ) { listener: AnalyticsListener ->
            listener.onAudioDecoderInitialized(
                eventTime,
                decoderName,
                initializationDurationMs
            )
            listener.onAudioDecoderInitialized(
                eventTime, decoderName, initializedTimestampMs, initializationDurationMs
            )
        }
    }

    override fun onAudioInputFormatChanged(
        format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?
    ) {
        val eventTime = generateReadingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_AUDIO_INPUT_FORMAT_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onAudioInputFormatChanged(
                eventTime,
                format,
                decoderReuseEvaluation
            )
        }
    }

    override fun onAudioPositionAdvancing(playoutStartSystemTimeMs: Long) {
        val eventTime = generateReadingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_AUDIO_POSITION_ADVANCING
        ) { listener: AnalyticsListener ->
            listener.onAudioPositionAdvancing(
                eventTime,
                playoutStartSystemTimeMs
            )
        }
    }

    override fun onAudioUnderrun(
        bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long
    ) {
        val eventTime = generateReadingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_AUDIO_UNDERRUN
        ) { listener: AnalyticsListener ->
            listener.onAudioUnderrun(
                eventTime,
                bufferSize,
                bufferSizeMs,
                elapsedSinceLastFeedMs
            )
        }
    }

    override fun onAudioDecoderReleased(decoderName: String) {
        val eventTime = generateReadingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_AUDIO_DECODER_RELEASED
        ) { listener: AnalyticsListener ->
            listener.onAudioDecoderReleased(
                eventTime,
                decoderName
            )
        }
    }

    override fun onAudioDisabled(counters: DecoderCounters) {
        val eventTime = generatePlayingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_AUDIO_DISABLED
        ) { listener: AnalyticsListener ->
            listener.onAudioDisabled(
                eventTime,
                counters
            )
        }
    }

    override fun onAudioSinkError(audioSinkError: Exception) {
        val eventTime = generateReadingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_AUDIO_SINK_ERROR
        ) { listener: AnalyticsListener ->
            listener.onAudioSinkError(
                eventTime,
                audioSinkError
            )
        }
    }

    override fun onAudioCodecError(audioCodecError: Exception) {
        val eventTime = generateReadingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_AUDIO_CODEC_ERROR
        ) { listener: AnalyticsListener ->
            listener.onAudioCodecError(
                eventTime,
                audioCodecError
            )
        }
    }

    override fun onAudioTrackInitialized(audioTrackConfig: AudioTrackConfig) {
        val eventTime = generateReadingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_AUDIO_TRACK_INITIALIZED
        ) { listener: AnalyticsListener ->
            listener.onAudioTrackInitialized(
                eventTime,
                audioTrackConfig
            )
        }
    }

    override fun onAudioTrackReleased(audioTrackConfig: AudioTrackConfig) {
        val eventTime = generateReadingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_AUDIO_TRACK_RELEASED
        ) { listener: AnalyticsListener ->
            listener.onAudioTrackReleased(
                eventTime,
                audioTrackConfig
            )
        }
    }

    override fun onVolumeChanged(volume: Float) {
        val eventTime = generateReadingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_VOLUME_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onVolumeChanged(
                eventTime,
                volume
            )
        }
    }

    // Video events.
    override fun onVideoEnabled(counters: DecoderCounters) {
        val eventTime = generateReadingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_VIDEO_ENABLED
        ) { listener: AnalyticsListener ->
            listener.onVideoEnabled(
                eventTime,
                counters
            )
        }
    }

    @Suppress("deprecation") // Calling deprecated listener method.
    override fun onVideoDecoderInitialized(
        decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long
    ) {
        val eventTime = generateReadingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_VIDEO_DECODER_INITIALIZED
        ) { listener: AnalyticsListener ->
            listener.onVideoDecoderInitialized(
                eventTime,
                decoderName,
                initializationDurationMs
            )
            listener.onVideoDecoderInitialized(
                eventTime, decoderName, initializedTimestampMs, initializationDurationMs
            )
        }
    }

    override fun onVideoInputFormatChanged(
        format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?
    ) {
        val eventTime = generateReadingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_VIDEO_INPUT_FORMAT_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onVideoInputFormatChanged(
                eventTime,
                format,
                decoderReuseEvaluation
            )
        }
    }

    override fun onDroppedFrames(count: Int, elapsedMs: Long) {
        val eventTime = generatePlayingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_DROPPED_VIDEO_FRAMES
        ) { listener: AnalyticsListener ->
            listener.onDroppedVideoFrames(
                eventTime,
                count,
                elapsedMs
            )
        }
    }

    override fun onVideoDecoderReleased(decoderName: String) {
        val eventTime = generateReadingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_VIDEO_DECODER_RELEASED
        ) { listener: AnalyticsListener ->
            listener.onVideoDecoderReleased(
                eventTime,
                decoderName
            )
        }
    }

    override fun onVideoDisabled(counters: DecoderCounters) {
        val eventTime = generatePlayingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_VIDEO_DISABLED
        ) { listener: AnalyticsListener ->
            listener.onVideoDisabled(
                eventTime,
                counters
            )
        }
    }

    override fun onRenderedFirstFrame(output: Any, renderTimeMs: Long) {
        val eventTime = generateReadingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_RENDERED_FIRST_FRAME
        ) { listener: AnalyticsListener ->
            listener.onRenderedFirstFrame(
                eventTime,
                output,
                renderTimeMs
            )
        }
    }

    override fun onVideoFrameProcessingOffset(totalProcessingOffsetUs: Long, frameCount: Int) {
        val eventTime = generatePlayingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_VIDEO_FRAME_PROCESSING_OFFSET
        ) { listener: AnalyticsListener ->
            listener.onVideoFrameProcessingOffset(
                eventTime,
                totalProcessingOffsetUs,
                frameCount
            )
        }
    }

    override fun onVideoCodecError(videoCodecError: Exception) {
        val eventTime = generateReadingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_VIDEO_CODEC_ERROR
        ) { listener: AnalyticsListener ->
            listener.onVideoCodecError(
                eventTime,
                videoCodecError
            )
        }
    }

    override fun onDroppedSeeksWhileScrubbing(droppedSeekCount: Int) {
        val eventTime = generateCurrentPlayerMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_DROPPED_SEEKS_WHILE_SCRUBBING
        ) { listener: AnalyticsListener ->
            listener.onDroppedSeeksWhileScrubbing(
                eventTime,
                droppedSeekCount
            )
        }
    }

    override fun onSurfaceSizeChanged(width: Int, height: Int) {
        val eventTime = generateReadingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_SURFACE_SIZE_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onSurfaceSizeChanged(
                eventTime,
                width,
                height
            )
        }
    }

    // MediaSourceEventListener implementation.
    // Deliberately calling deprecated listener method for backwards compatibility.
    @Suppress("deprecation")
    override fun onLoadStarted(
        windowIndex: Int,
        mediaPeriodId: MediaPeriodId?,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        retryCount: Int
    ) {
        val eventTime = generateMediaPeriodEventTime(windowIndex, mediaPeriodId)
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_LOAD_STARTED
        ) { listener: AnalyticsListener ->
            listener.onLoadStarted(eventTime, loadEventInfo, mediaLoadData)
            listener.onLoadStarted(eventTime, loadEventInfo, mediaLoadData, retryCount)
        }
    }

    override fun onLoadCompleted(
        windowIndex: Int,
        mediaPeriodId: MediaPeriodId?,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        val eventTime = generateMediaPeriodEventTime(windowIndex, mediaPeriodId)
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_LOAD_COMPLETED
        ) { listener: AnalyticsListener ->
            listener.onLoadCompleted(
                eventTime,
                loadEventInfo,
                mediaLoadData
            )
        }
    }

    override fun onLoadCanceled(
        windowIndex: Int,
        mediaPeriodId: MediaPeriodId?,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        val eventTime = generateMediaPeriodEventTime(windowIndex, mediaPeriodId)
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_LOAD_CANCELED
        ) { listener: AnalyticsListener ->
            listener.onLoadCanceled(
                eventTime,
                loadEventInfo,
                mediaLoadData
            )
        }
    }

    override fun onLoadError(
        windowIndex: Int,
        mediaPeriodId: MediaPeriodId?,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean
    ) {
        val eventTime = generateMediaPeriodEventTime(windowIndex, mediaPeriodId)
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_LOAD_ERROR
        ) { listener: AnalyticsListener ->
            listener.onLoadError(
                eventTime,
                loadEventInfo,
                mediaLoadData,
                error,
                wasCanceled
            )
        }
    }

    override fun onUpstreamDiscarded(
        windowIndex: Int, mediaPeriodId: MediaPeriodId?, mediaLoadData: MediaLoadData
    ) {
        val eventTime = generateMediaPeriodEventTime(windowIndex, mediaPeriodId)
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_UPSTREAM_DISCARDED
        ) { listener: AnalyticsListener ->
            listener.onUpstreamDiscarded(
                eventTime,
                mediaLoadData
            )
        }
    }

    override fun onDownstreamFormatChanged(
        windowIndex: Int, mediaPeriodId: MediaPeriodId?, mediaLoadData: MediaLoadData
    ) {
        val eventTime = generateMediaPeriodEventTime(windowIndex, mediaPeriodId)
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_DOWNSTREAM_FORMAT_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onDownstreamFormatChanged(
                eventTime,
                mediaLoadData
            )
        }
    }

    // Player.Listener implementation.
    // TODO: Use Player.Listener.onEvents to know when a set of simultaneous callbacks finished.
    // This helps to assign exactly the same EventTime to all of them instead of having slightly
    // different real times.
    override fun onTimelineChanged(
        timeline: Timeline,
        @Player.TimelineChangeReason reason: @Player.TimelineChangeReason Int
    ) {
        mediaPeriodQueueTracker.onTimelineChanged(player!!)
        val eventTime = generateCurrentPlayerMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_TIMELINE_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onTimelineChanged(
                eventTime,
                reason
            )
        }
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        @Player.MediaItemTransitionReason reason: @Player.MediaItemTransitionReason Int
    ) {
        val eventTime = generateCurrentPlayerMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_MEDIA_ITEM_TRANSITION
        ) { listener: AnalyticsListener ->
            listener.onMediaItemTransition(
                eventTime,
                mediaItem,
                reason
            )
        }
    }

    override fun onTracksChanged(tracks: Tracks) {
        val eventTime = generateCurrentPlayerMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_TRACKS_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onTracksChanged(
                eventTime,
                tracks
            )
        }
    }

    @Suppress("deprecation") // Implementing deprecated method.
    override fun onLoadingChanged(isLoading: Boolean) {
        // Do nothing. Handled by non-deprecated onIsLoadingChanged.
    }

    @Suppress("deprecation") // Calling deprecated listener method.
    override fun onIsLoadingChanged(isLoading: Boolean) {
        val eventTime = generateCurrentPlayerMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_IS_LOADING_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onLoadingChanged(eventTime, isLoading)
            listener.onIsLoadingChanged(eventTime, isLoading)
        }
    }

    override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
        val eventTime = generateCurrentPlayerMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_AVAILABLE_COMMANDS_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onAvailableCommandsChanged(
                eventTime,
                availableCommands
            )
        }
    }

    @Suppress("deprecation") // Implementing and calling deprecated listener method.
    override fun onPlayerStateChanged(
        playWhenReady: Boolean,
        @Player.State playbackState: @Player.State Int
    ) {
        val eventTime = generateCurrentPlayerMediaPeriodEventTime()
        sendEvent(eventTime, C.INDEX_UNSET) { listener: AnalyticsListener ->
            listener.onPlayerStateChanged(
                eventTime,
                playWhenReady,
                playbackState
            )
        }
    }

    override fun onPlaybackStateChanged(@Player.State playbackState: @Player.State Int) {
        val eventTime = generateCurrentPlayerMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_PLAYBACK_STATE_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onPlaybackStateChanged(
                eventTime,
                playbackState
            )
        }
    }

    override fun onPlayWhenReadyChanged(
        playWhenReady: Boolean,
        @Player.PlayWhenReadyChangeReason reason: @Player.PlayWhenReadyChangeReason Int
    ) {
        val eventTime = generateCurrentPlayerMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_PLAY_WHEN_READY_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onPlayWhenReadyChanged(
                eventTime,
                playWhenReady,
                reason
            )
        }
    }

    override fun onPlaybackSuppressionReasonChanged(
        @Player.PlaybackSuppressionReason playbackSuppressionReason: @Player.PlaybackSuppressionReason Int
    ) {
        val eventTime = generateCurrentPlayerMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onPlaybackSuppressionReasonChanged(
                eventTime,
                playbackSuppressionReason
            )
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        val eventTime = generateCurrentPlayerMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_IS_PLAYING_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onIsPlayingChanged(
                eventTime,
                isPlaying
            )
        }
    }

    override fun onRepeatModeChanged(@Player.RepeatMode repeatMode: @Player.RepeatMode Int) {
        val eventTime = generateCurrentPlayerMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_REPEAT_MODE_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onRepeatModeChanged(
                eventTime,
                repeatMode
            )
        }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        val eventTime = generateCurrentPlayerMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_SHUFFLE_MODE_ENABLED_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onShuffleModeChanged(
                eventTime,
                shuffleModeEnabled
            )
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        val eventTime = getEventTimeForErrorEvent(error)
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_PLAYER_ERROR
        ) { listener: AnalyticsListener ->
            listener.onPlayerError(
                eventTime,
                error
            )
        }
    }

    override fun onPlayerErrorChanged(error: PlaybackException?) {
        val eventTime = getEventTimeForErrorEvent(error)
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_PLAYER_ERROR
        ) { listener: AnalyticsListener ->
            listener.onPlayerErrorChanged(
                eventTime,
                error
            )
        }
    }

    @Suppress("deprecation") // Implementing deprecated method.
    override fun onPositionDiscontinuity(@Player.DiscontinuityReason reason: @Player.DiscontinuityReason Int) {
        // Do nothing. Handled by non-deprecated onPositionDiscontinuity.
    }

    // Calling deprecated callback.
    @Suppress("deprecation")
    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        @Player.DiscontinuityReason reason: @Player.DiscontinuityReason Int
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            isSeeking = false
        }
        mediaPeriodQueueTracker.onPositionDiscontinuity(player!!)
        val eventTime = generateCurrentPlayerMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_POSITION_DISCONTINUITY
        ) { listener: AnalyticsListener ->
            listener.onPositionDiscontinuity(eventTime, reason)
            listener.onPositionDiscontinuity(eventTime, oldPosition, newPosition, reason)
        }
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        val eventTime = generateCurrentPlayerMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_PLAYBACK_PARAMETERS_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onPlaybackParametersChanged(
                eventTime,
                playbackParameters
            )
        }
    }

    override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) {
        val eventTime = generateCurrentPlayerMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_SEEK_BACK_INCREMENT_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onSeekBackIncrementChanged(
                eventTime,
                seekBackIncrementMs
            )
        }
    }

    override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) {
        val eventTime = generateCurrentPlayerMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_SEEK_FORWARD_INCREMENT_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onSeekForwardIncrementChanged(
                eventTime,
                seekForwardIncrementMs
            )
        }
    }

    override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) {
        val eventTime = generateCurrentPlayerMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_MAX_SEEK_TO_PREVIOUS_POSITION_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onMaxSeekToPreviousPositionChanged(
                eventTime,
                maxSeekToPreviousPositionMs
            )
        }
    }

    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
        val eventTime = generateCurrentPlayerMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_MEDIA_METADATA_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onMediaMetadataChanged(
                eventTime,
                mediaMetadata
            )
        }
    }

    override fun onPlaylistMetadataChanged(playlistMetadata: MediaMetadata) {
        val eventTime = generateCurrentPlayerMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_PLAYLIST_METADATA_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onPlaylistMetadataChanged(
                eventTime,
                playlistMetadata
            )
        }
    }

    override fun onMetadata(metadata: Metadata) {
        val eventTime = generateCurrentPlayerMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_METADATA
        ) { listener: AnalyticsListener ->
            listener.onMetadata(
                eventTime,
                metadata
            )
        }
    }

    @Suppress("deprecation") // Implementing and calling deprecated listener method.
    override fun onCues(cues: List<Cue>) {
        val eventTime = generateCurrentPlayerMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_CUES
        ) { listener: AnalyticsListener ->
            listener.onCues(
                eventTime,
                cues
            )
        }
    }

    override fun onCues(cueGroup: CueGroup) {
        val eventTime = generateCurrentPlayerMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_CUES
        ) { listener: AnalyticsListener ->
            listener.onCues(
                eventTime,
                cueGroup
            )
        }
    }

    override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {
        val eventTime = generateReadingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_SKIP_SILENCE_ENABLED_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onSkipSilenceEnabledChanged(
                eventTime,
                skipSilenceEnabled
            )
        }
    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        val eventTime = generateReadingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_AUDIO_SESSION_ID
        ) { listener: AnalyticsListener ->
            listener.onAudioSessionIdChanged(
                eventTime,
                audioSessionId
            )
        }
    }

    override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) {
        val eventTime = generateReadingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_AUDIO_ATTRIBUTES_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onAudioAttributesChanged(
                eventTime,
                audioAttributes
            )
        }
    }

    @Suppress("deprecation") // Calling deprecated listener method.
    override fun onVideoSizeChanged(videoSize: VideoSize) {
        val eventTime = generateReadingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_VIDEO_SIZE_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onVideoSizeChanged(eventTime, videoSize)
            listener.onVideoSizeChanged(
                eventTime,
                videoSize.width,
                videoSize.height,  
                0,
                videoSize.pixelWidthHeightRatio
            )
        }
    }

    override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
        val eventTime = generateCurrentPlayerMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_TRACK_SELECTION_PARAMETERS_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onTrackSelectionParametersChanged(
                eventTime,
                parameters
            )
        }
    }

    override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
        val eventTime = generateCurrentPlayerMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_DEVICE_INFO_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onDeviceInfoChanged(
                eventTime,
                deviceInfo
            )
        }
    }

    override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) {
        val eventTime = generateCurrentPlayerMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_DEVICE_VOLUME_CHANGED
        ) { listener: AnalyticsListener ->
            listener.onDeviceVolumeChanged(
                eventTime,
                volume,
                muted
            )
        }
    }

    override fun onRenderedFirstFrame() {
        // Do nothing. Handled by onRenderedFirstFrame call with additional parameters.
    }

    override fun onEvents(player: Player, events: Player.Events) {
        // Do nothing. AnalyticsCollector issues its own onEvents.
    }

    // BandwidthMeter.EventListener implementation.
    override fun onBandwidthSample(elapsedMs: Int, bytesTransferred: Long, bitrateEstimate: Long) {
        val eventTime = generateLoadingMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_BANDWIDTH_ESTIMATE
        ) { listener: AnalyticsListener ->
            listener.onBandwidthEstimate(
                eventTime,
                elapsedMs,
                bytesTransferred,
                bitrateEstimate
            )
        }
    }

    // DrmSessionEventListener implementation.
    @Suppress("deprecation") // Calls deprecated listener method.
    override fun onDrmSessionAcquired(
        windowIndex: Int,
        mediaPeriodId: MediaPeriodId?,
        @DrmSession.State state: @DrmSession.State Int
    ) {
        val eventTime = generateMediaPeriodEventTime(windowIndex, mediaPeriodId)
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_DRM_SESSION_ACQUIRED
        ) { listener: AnalyticsListener ->
            listener.onDrmSessionAcquired(eventTime)
            listener.onDrmSessionAcquired(eventTime, state)
        }
    }

    @Suppress("deprecation") // Calls deprecated listener method.
    override fun onDrmKeysLoaded(
        windowIndex: Int, mediaPeriodId: MediaPeriodId?, keyRequestInfo: KeyRequestInfo
    ) {
        val eventTime = generateMediaPeriodEventTime(windowIndex, mediaPeriodId)
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_DRM_KEYS_LOADED
        ) { listener: AnalyticsListener ->
            listener.onDrmKeysLoaded(eventTime)
            listener.onDrmKeysLoaded(eventTime, keyRequestInfo)
        }
    }

    override fun onDrmSessionManagerError(
        windowIndex: Int, mediaPeriodId: MediaPeriodId?, error: Exception
    ) {
        val eventTime = generateMediaPeriodEventTime(windowIndex, mediaPeriodId)
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_DRM_SESSION_MANAGER_ERROR
        ) { listener: AnalyticsListener ->
            listener.onDrmSessionManagerError(
                eventTime,
                error
            )
        }
    }

    override fun onDrmKeysRestored(windowIndex: Int, mediaPeriodId: MediaPeriodId?) {
        val eventTime = generateMediaPeriodEventTime(windowIndex, mediaPeriodId)
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_DRM_KEYS_RESTORED
        ) { listener: AnalyticsListener ->
            listener.onDrmKeysRestored(
                eventTime
            )
        }
    }

    override fun onDrmKeysRemoved(windowIndex: Int, mediaPeriodId: MediaPeriodId?) {
        val eventTime = generateMediaPeriodEventTime(windowIndex, mediaPeriodId)
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_DRM_KEYS_REMOVED
        ) { listener: AnalyticsListener ->
            listener.onDrmKeysRemoved(
                eventTime
            )
        }
    }

    override fun onDrmSessionReleased(windowIndex: Int, mediaPeriodId: MediaPeriodId?) {
        val eventTime = generateMediaPeriodEventTime(windowIndex, mediaPeriodId)
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_DRM_SESSION_RELEASED
        ) { listener: AnalyticsListener ->
            listener.onDrmSessionReleased(
                eventTime
            )
        }
    }

    fun sendEvent(
        eventTime: EventTime?,
        eventFlag: Int,
        eventInvocation: (AnalyticsListener) -> Unit
    ) {
        eventTimes.put(eventFlag, eventTime)
        listeners.sendEvent(eventFlag, eventInvocation)
    }

    fun generateCurrentPlayerMediaPeriodEventTime(): EventTime {
        return generateEventTime(mediaPeriodQueueTracker.currentPlayerMediaPeriod)
    }

    fun generateEventTime(
        timeline: Timeline, windowIndex: Int, mediaPeriodId: MediaPeriodId?
    ): EventTime {
        var mediaPeriodId = mediaPeriodId
        if (timeline.isEmpty) {
            // Ensure media period id is only reported together with a valid timeline.
            mediaPeriodId = null
        }
        val realtimeMs = clock.elapsedRealtime()
        val eventPositionMs: Long
        val isInCurrentWindow =
            timeline == player!!.currentTimeline
                    && windowIndex == player!!.currentMediaItemIndex
        if (mediaPeriodId != null && mediaPeriodId.isAd) {
            val isCurrentAd =
                isInCurrentWindow
                        && player!!.currentAdGroupIndex == mediaPeriodId.adGroupIndex && player!!.currentAdIndexInAdGroup == mediaPeriodId.adIndexInAdGroup
            // Assume start position of 0 for future ads.
            eventPositionMs = if (isCurrentAd) player!!.currentPosition else 0
        } else if (isInCurrentWindow) {
            eventPositionMs = player!!.contentPosition
        } else {
            // Assume default start position for future content windows. If timeline is not available yet,
            // assume start position of 0.
            eventPositionMs =
                if (timeline.isEmpty) 0 else timeline.getWindow(
                    windowIndex,
                    window
                ).defaultPositionMs
        }
        val currentMediaPeriodId = mediaPeriodQueueTracker.currentPlayerMediaPeriod
        return EventTime(
            realtimeMs,
            timeline,
            windowIndex,
            mediaPeriodId,
            eventPositionMs,
            player!!.currentTimeline,
            player!!.currentMediaItemIndex,
            currentMediaPeriodId,
            player!!.currentPosition,
            player!!.totalBufferedDuration
        )
    }

    private fun releaseInternal() {
        val eventTime = generateCurrentPlayerMediaPeriodEventTime()
        sendEvent(
            eventTime,
            AnalyticsListener.EVENT_PLAYER_RELEASED
        ) { listener: AnalyticsListener ->
            listener.onPlayerReleased(
                eventTime
            )
        }
        listeners.release()
    }

    private fun generateEventTime(mediaPeriodId: MediaPeriodId?): EventTime {
        val player = player!!
        val knownTimeline =
            if (mediaPeriodId == null)
                null
            else
                mediaPeriodQueueTracker.getMediaPeriodIdTimeline(mediaPeriodId)
        if (mediaPeriodId == null || knownTimeline == null) {
            val windowIndex = player.currentMediaItemIndex
            val timeline = player.currentTimeline
            val windowIsInTimeline = windowIndex < timeline.windowCount
            return generateEventTime(
                if (windowIsInTimeline) timeline else Timeline.EMPTY,
                windowIndex,  
                null
            )
        }
        val windowIndex = knownTimeline.getPeriodByUid(mediaPeriodId.periodUid, period).windowIndex
        return generateEventTime(knownTimeline, windowIndex, mediaPeriodId)
    }

    private fun generatePlayingMediaPeriodEventTime(): EventTime {
        return generateEventTime(mediaPeriodQueueTracker.playingMediaPeriod)
    }

    private fun generateReadingMediaPeriodEventTime(): EventTime {
        return generateEventTime(mediaPeriodQueueTracker.readingMediaPeriod)
    }

    private fun generateLoadingMediaPeriodEventTime(): EventTime {
        return generateEventTime(mediaPeriodQueueTracker.loadingMediaPeriod)
    }

    private fun generateMediaPeriodEventTime(
        windowIndex: Int, mediaPeriodId: MediaPeriodId?
    ): EventTime {
        if (mediaPeriodId != null) {
            val isInKnownTimeline =
                mediaPeriodQueueTracker.getMediaPeriodIdTimeline(mediaPeriodId) != null
            return if (isInKnownTimeline)
                generateEventTime(mediaPeriodId)
            else
                generateEventTime(Timeline.EMPTY, windowIndex, mediaPeriodId)
        }
        val timeline = player!!.currentTimeline
        val windowIsInTimeline = windowIndex < timeline.windowCount
        return generateEventTime(
            if (windowIsInTimeline) timeline else Timeline.EMPTY,
            windowIndex,  
            null
        )
    }

    private fun getEventTimeForErrorEvent(error: PlaybackException?): EventTime {
        if (error is ExoPlaybackException) {
            if (error.mediaPeriodId != null) {
                return generateEventTime(error.mediaPeriodId)
            }
        }
        return generateCurrentPlayerMediaPeriodEventTime()
    }

    private class MediaPeriodQueueTracker(// TODO: Investigate reporting MediaPeriodId in renderer events.
        private val period: Timeline.Period
    ) {
        var mediaPeriodQueue: List<MediaPeriodId> = listOf()
        private var mediaPeriodTimelines: Map<MediaPeriodId?, Timeline?>

        var currentPlayerMediaPeriod: MediaPeriodId? = null
            private set

        var playingMediaPeriod: MediaPeriodId? = null
            private set

        var readingMediaPeriod: MediaPeriodId? = null
            private set

        init {
            mediaPeriodTimelines = mapOf()
        }

        val loadingMediaPeriod: MediaPeriodId?
            get() = if (mediaPeriodQueue.isEmpty()) null else Iterables.getLast<MediaPeriodId?>(
                mediaPeriodQueue
            )

        fun getMediaPeriodIdTimeline(mediaPeriodId: MediaPeriodId?): Timeline? {
            return mediaPeriodTimelines[mediaPeriodId]
        }

        fun onPositionDiscontinuity(player: Player) {
            currentPlayerMediaPeriod =
                findCurrentPlayerMediaPeriodInQueue(
                    player,
                    mediaPeriodQueue,
                    playingMediaPeriod,
                    period
                )
        }

        fun onTimelineChanged(player: Player) {
            currentPlayerMediaPeriod =
                findCurrentPlayerMediaPeriodInQueue(
                    player,
                    mediaPeriodQueue,
                    playingMediaPeriod,
                    period
                )
            updateMediaPeriodTimelines( player.currentTimeline)
        }

        fun onQueueUpdated(
            queue: List<MediaPeriodId>, readingPeriod: MediaPeriodId?, player: Player
        ) {
            mediaPeriodQueue = queue
            if (!queue.isEmpty()) {
                playingMediaPeriod = queue[0]
                readingMediaPeriod = readingPeriod!!
            }
            if (currentPlayerMediaPeriod == null) {
                currentPlayerMediaPeriod =
                    findCurrentPlayerMediaPeriodInQueue(
                        player, mediaPeriodQueue, playingMediaPeriod, period
                    )
            }
            updateMediaPeriodTimelines(player.currentTimeline)
        }

        fun updateMediaPeriodTimelines(preferredTimeline: Timeline) {
            val builder = mutableMapOf<MediaPeriodId?, Timeline?>()
            if (mediaPeriodQueue.isEmpty()) {
                addTimelineForMediaPeriodId(builder, playingMediaPeriod, preferredTimeline)
                if (readingMediaPeriod != playingMediaPeriod) {
                    addTimelineForMediaPeriodId(builder, readingMediaPeriod, preferredTimeline)
                }
                if (currentPlayerMediaPeriod != playingMediaPeriod && currentPlayerMediaPeriod != readingMediaPeriod) {
                    addTimelineForMediaPeriodId(
                        builder,
                        currentPlayerMediaPeriod,
                        preferredTimeline
                    )
                }
            } else {
                for (i in mediaPeriodQueue.indices) {
                    addTimelineForMediaPeriodId(builder, mediaPeriodQueue[i], preferredTimeline)
                }
                if (!mediaPeriodQueue.contains(currentPlayerMediaPeriod)) {
                    addTimelineForMediaPeriodId(
                        builder,
                        currentPlayerMediaPeriod,
                        preferredTimeline
                    )
                }
            }
            mediaPeriodTimelines = builder.toMap()
        }

        fun addTimelineForMediaPeriodId(
            mediaPeriodTimelinesBuilder: MutableMap<MediaPeriodId?, Timeline?>,
            mediaPeriodId: MediaPeriodId?,
            preferredTimeline: Timeline
        ) {
            if (mediaPeriodId == null) {
                return
            }
            if (preferredTimeline.getIndexOfPeriod(mediaPeriodId.periodUid) != C.INDEX_UNSET) {
                mediaPeriodTimelinesBuilder[mediaPeriodId] = preferredTimeline
            } else {
                val existingTimeline = mediaPeriodTimelines[mediaPeriodId]
                if (existingTimeline != null) {
                    mediaPeriodTimelinesBuilder[mediaPeriodId] = existingTimeline
                }
            }
        }

        companion object {
            private fun findCurrentPlayerMediaPeriodInQueue(
                player: Player,
                mediaPeriodQueue: List<MediaPeriodId>,
                playingMediaPeriod: MediaPeriodId?,
                period: Timeline.Period
            ): MediaPeriodId? {
                val playerTimeline = player.currentTimeline
                val playerPeriodIndex = player.currentPeriodIndex
                val playerPeriodUid =
                    if (playerTimeline.isEmpty) null else playerTimeline.getUidOfPeriod(
                        playerPeriodIndex
                    )
                val playerNextAdGroupIndex =
                    if (player.isPlayingAd || playerTimeline.isEmpty)
                        C.INDEX_UNSET
                    else
                        playerTimeline
                            .getPeriod(playerPeriodIndex, period)
                            .getAdGroupIndexAfterPositionUs(
                                msToUs(player.currentPosition) - period.positionInWindowUs
                            )
                for (i in mediaPeriodQueue.indices) {
                    val mediaPeriodId = mediaPeriodQueue[i]
                    if (isMatchingMediaPeriod(
                            mediaPeriodId,
                            playerPeriodUid,
                            player.isPlayingAd,
                            player.currentAdGroupIndex,
                            player.currentAdIndexInAdGroup,
                            playerNextAdGroupIndex
                        )
                    ) {
                        return mediaPeriodId
                    }
                }
                if (mediaPeriodQueue.isEmpty() && playingMediaPeriod != null) {
                    if (isMatchingMediaPeriod(
                            playingMediaPeriod,
                            playerPeriodUid,
                            player.isPlayingAd,
                            player.currentAdGroupIndex,
                            player.currentAdIndexInAdGroup,
                            playerNextAdGroupIndex
                        )
                    ) {
                        return playingMediaPeriod
                    }
                }
                return null
            }

            private fun isMatchingMediaPeriod(
                mediaPeriodId: MediaPeriodId,
                playerPeriodUid: Any?,
                isPlayingAd: Boolean,
                playerAdGroupIndex: Int,
                playerAdIndexInAdGroup: Int,
                playerNextAdGroupIndex: Int
            ): Boolean {
                if (mediaPeriodId.periodUid != playerPeriodUid) {
                    return false
                }
                // Timeline period matches. Still need to check ad information.
                return (isPlayingAd
                        && mediaPeriodId.adGroupIndex == playerAdGroupIndex && mediaPeriodId.adIndexInAdGroup == playerAdIndexInAdGroup)
                        || (!isPlayingAd && mediaPeriodId.adGroupIndex == C.INDEX_UNSET && mediaPeriodId.nextAdGroupIndex == playerNextAdGroupIndex)
            }
        }
    }
}
