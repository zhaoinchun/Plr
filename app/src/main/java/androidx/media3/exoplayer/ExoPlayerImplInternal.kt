package androidx.media3.exoplayer

import android.content.Context
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.util.Pair
import androidx.media3.common.AdPlaybackState
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.IllegalSeekPositionException
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes.allSamplesAreSyncSamples
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.audio.AudioFocusManager
import androidx.media3.common.audio.AudioFocusManager.PlayerCommand
import androidx.media3.common.audio.AudioFocusManager.PlayerControl
import androidx.media3.common.util.Clock
import androidx.media3.common.util.ConditionVariable
import androidx.media3.common.util.HandlerWrapper
import androidx.media3.common.util.Log.e
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.StuckPlayerException
import androidx.media3.common.util.TraceUtil.beginSection
import androidx.media3.common.util.TraceUtil.endSection
import androidx.media3.common.util.Util.constrainValue
import androidx.media3.common.util.Util.msToUs
import androidx.media3.common.util.Util.usToMs
import androidx.media3.datasource.DataSourceException
import androidx.media3.exoplayer.DefaultMediaClock.PlaybackParametersListener
import androidx.media3.exoplayer.ExoPlayer.PreloadConfiguration
import androidx.media3.exoplayer.MediaPeriodQueue.UpdatePeriodQueueResult
import androidx.media3.exoplayer.MediaSourceList.MediaSourceListInfoRefreshListener
import androidx.media3.exoplayer.PlayerMessage.Sender
import androidx.media3.exoplayer.Renderer.WakeupListener
import androidx.media3.exoplayer.analytics.AnalyticsCollector
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.drm.DrmSession.DrmSessionException
import androidx.media3.exoplayer.image.ImageMetadataListener
import androidx.media3.exoplayer.source.BehindLiveWindowException
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelectorResult
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.google.common.base.Preconditions
import com.google.common.math.DoubleMath
import java.io.IOException
import java.math.RoundingMode
import java.util.Collections
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

internal class ExoPlayerImplInternal(
    context: Context,
    renderers: Array<Renderer>,
    secondaryRenderers: Array<Renderer?>,
    private val trackSelector: TrackSelector,
    private val emptyTrackSelectorResult: TrackSelectorResult,
    private val loadControl: LoadControl,
    bandwidthMeter: BandwidthMeter,
    private var repeatMode: @Player.RepeatMode Int,
    private var shuffleModeEnabled: Boolean,
    private val analyticsCollector: AnalyticsCollector,
    private var seekParameters: SeekParameters?,
    private val livePlaybackSpeedControl: LivePlaybackSpeedControl,
    private val releaseTimeoutMs: Long,
    private var pauseAtEndOfWindow: Boolean,
    private val dynamicSchedulingEnabled: Boolean,
    private val perStreamMediaProgressionEnabled: Boolean,
    applicationLooper: Looper,
    private val clock: Clock,
    private val playbackInfoUpdateListener: PlaybackInfoUpdateListener,
    private val playerId: PlayerId,
    playbackLooperProvider: PlaybackLooperProvider?,
    private var preloadConfiguration: PreloadConfiguration,
    videoFrameMetadataListener: VideoFrameMetadataListener,
    private var enforceAdPlaybackOnTimelineRefresh: Boolean
) : Handler.Callback, MediaPeriod.Callback, TrackSelector.InvalidationListener,
    MediaSourceListInfoRefreshListener, PlaybackParametersListener, Sender, PlayerControl,
    VideoFrameMetadataListener {
    class PlaybackInfoUpdate(playbackInfo: PlaybackInfo) {
        var hasPendingChange = false
        var playbackInfo: PlaybackInfo = playbackInfo
            set(value) {
                hasPendingChange = hasPendingChange or (this.playbackInfo != value)
                field = value
            }
        var operationAcks: Int = 0
        var positionDiscontinuity: Boolean = false
        var discontinuityReason: @Player.DiscontinuityReason Int = 0

        fun incrementPendingOperationAcks(operationAcks: Int) {
            hasPendingChange = hasPendingChange or (operationAcks > 0)
            this.operationAcks += operationAcks
        }

        fun setPositionDiscontinuity(@Player.DiscontinuityReason discontinuityReason: @Player.DiscontinuityReason Int) {
            if (positionDiscontinuity
                && this.discontinuityReason != Player.DISCONTINUITY_REASON_INTERNAL
            ) {
                // We always prefer non-internal discontinuity reasons. We also assume that we won't report
                // more than one non-internal discontinuity per message iteration.
                Preconditions.checkArgument(discontinuityReason == Player.DISCONTINUITY_REASON_INTERNAL)
                return
            }
            hasPendingChange = true
            positionDiscontinuity = true
            this.discontinuityReason = discontinuityReason
        }
    }

    fun interface PlaybackInfoUpdateListener {
        fun onPlaybackInfoUpdate(playbackInfo: PlaybackInfoUpdate)
    }

    private val renderers: Array<RendererHolder>
    private val rendererCapabilities: Array<RendererCapabilities>
    private val rendererReportedReady: BooleanArray
    private val playbackLooperProvider: PlaybackLooperProvider =
        playbackLooperProvider ?: PlaybackLooperProvider()
    val playbackLooper: Looper = this.playbackLooperProvider.obtainLooper()
    private val handler: HandlerWrapper = clock.createHandler(this.playbackLooper, this)
    private val window = Timeline.Window()
    private val period = Timeline.Period()
    private val backBufferDurationUs = loadControl.getBackBufferDurationUs(playerId)
    private val retainBackBufferFromKeyframe = loadControl.retainBackBufferFromKeyframe(playerId)
    private val mediaClock = DefaultMediaClock(this, clock)
    private val pendingMessages = mutableListOf<PendingMessageInfo>()
    private val queue: MediaPeriodQueue
    private val mediaSourceList: MediaSourceList
    private val applicationLooperHandler: HandlerWrapper =
        clock.createHandler(applicationLooper, null)
    private val hasSecondaryRenderers: Boolean
    private val audioFocusManager: AudioFocusManager =
        AudioFocusManager(context, playbackLooper, this)
    private var scrubbingModeParameters = ScrubbingModeParameters.DEFAULT
    private var scrubbingModeSeekParameters: SeekParameters? = null
    private var scrubbingModeEnabled = false
    private var seekIsPendingWhileScrubbing = false
    private var queuedSeekWhileScrubbing: SeekPosition? = null
    private var droppedSeeksWhileScrubbing = 0
    private var playbackInfo: PlaybackInfo = PlaybackInfo.createDummy(emptyTrackSelectorResult)
    private var playbackInfoUpdate = PlaybackInfoUpdate(playbackInfo)
    private var releasedOnApplicationThread = false
    private var pendingPauseAtEndOfPeriod = false
    private var isRebuffering = false
    private var lastRebufferRealtimeMs = C.TIME_UNSET
    private var shouldContinueLoading = false
    private var foregroundMode = false
    private var requestForRendererSleep = false
    private var offloadSchedulingEnabled = false
    private var enabledRendererCount = 0
    private var pendingInitialSeekPosition: SeekPosition? = null
    private var rendererPositionUs: Long = 0
    private var rendererPositionElapsedRealtimeUs: Long = 0
    private var nextPendingMessageIndexHint = 0
    private var deliverPendingMessageAtStartPositionRequired = true
    private var pendingRecoverableRendererError: ExoPlaybackException? = null
    private var setForegroundModeTimeoutMs = releaseTimeoutMs
    private var playbackMaybeBecameStuckAtMs = C.TIME_UNSET
    private var lastPreloadPoolInvalidationTimeline: Timeline? = Timeline.EMPTY
    private var prewarmingMediaPeriodDiscontinuity = C.TIME_UNSET
    private var isPrewarmingDisabledUntilNextTransition = false
    private var volume = 1f

    init {
        val rendererCapabilities = arrayOfNulls<RendererCapabilities>(renderers.size)
        rendererReportedReady = BooleanArray(renderers.size)
        val rendererCapabilitiesListener =
            trackSelector.rendererCapabilitiesListener

        var hasSecondaryRenderers = false
        this.renderers = Array(renderers.size) { i ->
            renderers[i].init(i, playerId, clock)
            rendererCapabilities[i] = renderers[i].capabilities
            if (rendererCapabilitiesListener != null) {
                rendererCapabilities[i]!!.setListener(rendererCapabilitiesListener)
            }
            if (secondaryRenderers[i] != null) {
                secondaryRenderers[i]!!.init(i, playerId, clock)
                hasSecondaryRenderers = true
            }
            RendererHolder(renderers[i], secondaryRenderers[i], i)
        }
        this.rendererCapabilities = rendererCapabilities as Array<RendererCapabilities>
        this.hasSecondaryRenderers = hasSecondaryRenderers
        trackSelector.init(this, bandwidthMeter)
        queue =
            MediaPeriodQueue(
                analyticsCollector,
                applicationLooperHandler,
                { mediaPeriodInfo: MediaPeriodInfo?, rendererPositionOffsetUs: Long ->
                    createMediaPeriodHolder(
                        mediaPeriodInfo!!,
                        rendererPositionOffsetUs
                    )
                },
                preloadConfiguration,
                renderers.size
            )
        mediaSourceList =
            MediaSourceList(
                this,
                analyticsCollector,
                applicationLooperHandler,
                playerId,
                bandwidthMeter
            )
        val internalVideoFrameMetadataListener =
            VideoFrameMetadataListener { presentationTimeUs: Long, releaseTimeNs: Long, format: Format, mediaFormat: MediaFormat? ->
                videoFrameMetadataListener.onVideoFrameAboutToBeRendered(
                    presentationTimeUs, releaseTimeNs, format, mediaFormat
                )
                onVideoFrameAboutToBeRendered(
                    presentationTimeUs,
                    releaseTimeNs,
                    format,
                    mediaFormat
                )
            }
        handler.obtainMessage(
            MSG_SET_VIDEO_FRAME_METADATA_LISTENER, internalVideoFrameMetadataListener
        ).sendToTarget()

        val internalOnImageAvailableListener =
            ImageMetadataListener { presentationTimeUs: Long, format: Format? ->
                if (seekIsPendingWhileScrubbing) {
                    handler.obtainMessage(MSG_SEEK_COMPLETED_IN_SCRUBBING_MODE).sendToTarget()
                }
            }
        handler.obtainMessage(MSG_SET_IMAGE_METADATA_LISTENER, internalOnImageAvailableListener)
            .sendToTarget()
    }

    private fun createMediaPeriodHolder(
        mediaPeriodInfo: MediaPeriodInfo, rendererPositionOffsetUs: Long
    ): MediaPeriodHolder {
        return MediaPeriodHolder(
            rendererCapabilities,
            rendererPositionOffsetUs,
            trackSelector,
            loadControl.getAllocator(playerId),
            mediaSourceList,
            mediaPeriodInfo,
            emptyTrackSelectorResult,
            preloadConfiguration.targetPreloadDurationUs,
            perStreamMediaProgressionEnabled
        )
    }

    fun experimentalSetForegroundModeTimeoutMs(setForegroundModeTimeoutMs: Long) {
        this.setForegroundModeTimeoutMs = setForegroundModeTimeoutMs
    }

    fun prepare() {
        handler.obtainMessage(MSG_PREPARE).sendToTarget()
    }

    fun setPlayWhenReady(
        playWhenReady: Boolean,
        playWhenReadyChangeReason: @Player.PlayWhenReadyChangeReason Int,
        playbackSuppressionReason: @Player.PlaybackSuppressionReason Int
    ) {
        val combinedReasons = playbackSuppressionReason shl 4 or playWhenReadyChangeReason
        handler
            .obtainMessage(MSG_SET_PLAY_WHEN_READY, if (playWhenReady) 1 else 0, combinedReasons)
            .sendToTarget()
    }

    fun setPauseAtEndOfWindow(pauseAtEndOfWindow: Boolean) {
        handler
            .obtainMessage(
                MSG_SET_PAUSE_AT_END_OF_WINDOW,
                if (pauseAtEndOfWindow) 1 else 0,
                0
            )
            .sendToTarget()
    }

    fun setEnforceAdPlaybackOnTimelineRefresh(enforceAdPlaybackOnTimelineRefresh: Boolean) {
        handler
            .obtainMessage(
                MSG_SET_ENFORCE_AD_PLAYBACK_ON_TIMELINE_REFRESH,
                if (enforceAdPlaybackOnTimelineRefresh) 1 else 0,
                0
            )
            .sendToTarget()
    }

    fun setRepeatMode(@Player.RepeatMode repeatMode: @Player.RepeatMode Int) {
        handler.obtainMessage(MSG_SET_REPEAT_MODE, repeatMode, 0).sendToTarget()
    }

    fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        handler.obtainMessage(MSG_SET_SHUFFLE_ENABLED, if (shuffleModeEnabled) 1 else 0, 0)
            .sendToTarget()
    }

    fun setPreloadConfiguration(preloadConfiguration: PreloadConfiguration?) {
        handler.obtainMessage(MSG_SET_PRELOAD_CONFIGURATION, preloadConfiguration).sendToTarget()
    }

    fun seekTo(timeline: Timeline, windowIndex: Int, positionUs: Long) {
        handler
            .obtainMessage(MSG_SEEK_TO, SeekPosition(timeline, windowIndex, positionUs))
            .sendToTarget()
    }

    fun setPlaybackParameters(playbackParameters: PlaybackParameters?) {
        handler.obtainMessage(MSG_SET_PLAYBACK_PARAMETERS, playbackParameters).sendToTarget()
    }

    fun setSeekParameters(seekParameters: SeekParameters?) {
        handler.obtainMessage(MSG_SET_SEEK_PARAMETERS, seekParameters).sendToTarget()
    }

    fun setScrubbingModeEnabled(scrubbingModeEnabled: Boolean) {
        handler.obtainMessage(MSG_SET_SCRUBBING_MODE_ENABLED, scrubbingModeEnabled).sendToTarget()
    }

    fun setScrubbingModeParameters(scrubbingModeParameters: ScrubbingModeParameters?) {
        handler
            .obtainMessage(MSG_SET_SCRUBBING_MODE_PARAMETERS, scrubbingModeParameters)
            .sendToTarget()
    }

    fun stop() {
        handler.obtainMessage(MSG_STOP).sendToTarget()
    }

    fun setMediaSources(
        mediaSources: List<MediaSourceList.MediaSourceHolder>,
        windowIndex: Int,
        positionUs: Long,
        shuffleOrder: ShuffleOrder
    ) {
        handler
            .obtainMessage(
                MSG_SET_MEDIA_SOURCES,
                MediaSourceListUpdateMessage(mediaSources, shuffleOrder, windowIndex, positionUs)
            )
            .sendToTarget()
    }

    fun addMediaSources(
        index: Int,
        mediaSources: List<MediaSourceList.MediaSourceHolder>,
        shuffleOrder: ShuffleOrder
    ) {
        handler
            .obtainMessage(
                MSG_ADD_MEDIA_SOURCES,
                index,
                0,
                MediaSourceListUpdateMessage(
                    mediaSources,
                    shuffleOrder,
                    C.INDEX_UNSET,
                    C.TIME_UNSET
                )
            )
            .sendToTarget()
    }

    fun removeMediaSources(fromIndex: Int, toIndex: Int, shuffleOrder: ShuffleOrder?) {
        handler
            .obtainMessage(MSG_REMOVE_MEDIA_SOURCES, fromIndex, toIndex, shuffleOrder)
            .sendToTarget()
    }

    fun moveMediaSources(
        fromIndex: Int, toIndex: Int, newFromIndex: Int, shuffleOrder: ShuffleOrder
    ) {
        val moveMediaItemsMessage =
            MoveMediaItemsMessage(fromIndex, toIndex, newFromIndex, shuffleOrder)
        handler.obtainMessage(MSG_MOVE_MEDIA_SOURCES, moveMediaItemsMessage).sendToTarget()
    }

    fun setShuffleOrder(shuffleOrder: ShuffleOrder?) {
        handler.obtainMessage(MSG_SET_SHUFFLE_ORDER, shuffleOrder).sendToTarget()
    }

    fun updateMediaSourcesWithMediaItems(
        fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>
    ) {
        handler
            .obtainMessage(
                MSG_UPDATE_MEDIA_SOURCES_WITH_MEDIA_ITEMS,
                fromIndex,
                toIndex,
                mediaItems
            )
            .sendToTarget()
    }

    fun setAudioAttributes(audioAttributes: AudioAttributes?, handleAudioFocus: Boolean) {
        handler
            .obtainMessage(
                MSG_SET_AUDIO_ATTRIBUTES,
                if (handleAudioFocus) 1 else 0,
                0,
                audioAttributes
            )
            .sendToTarget()
    }

    fun setVolume(volume: Float) {
        handler.obtainMessage(MSG_SET_VOLUME, volume).sendToTarget()
    }

    fun setAudioSessionId(audioSessionId: Int, isInitialAudioSessionId: Boolean) {
        val message =
            handler.obtainMessage(MSG_SET_AUDIO_SESSION_ID, audioSessionId, 0)
        if (isInitialAudioSessionId) {
            // The auto-generated audio session id must be processed prior to any potential manually-set
            // audio session ids to ensure correct assignment.
            handler.sendMessageAtFrontOfQueue(message)
        } else {
            message.sendToTarget()
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun handleAudioFocusPlayerCommandInternal(playerCommand: @PlayerCommand Int) {
        updatePlayWhenReadyWithAudioFocus(
            playbackInfo.playWhenReady,
            playerCommand,
            playbackInfo.playbackSuppressionReason,
            playbackInfo.playWhenReadyChangeReason
        )
    }

    @Throws(ExoPlaybackException::class)
    private fun handleAudioFocusVolumeMultiplierChange() {
        setVolumeInternal(volume)
    }

    @Throws(ExoPlaybackException::class)
    private fun setVideoFrameMetadataListenerInternal(
        videoFrameMetadataListener: VideoFrameMetadataListener?
    ) {
        for (renderer in renderers) {
            renderer.setVideoFrameMetadataListener(videoFrameMetadataListener)
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun setImageMetadataListenerInternal(imageMetadataListener: ImageMetadataListener?) {
        for (renderer in renderers) {
            renderer.setImageMetadataListener(imageMetadataListener)
        }
    }

    override fun sendMessage(message: PlayerMessage) {
        if (releasedOnApplicationThread || !playbackLooper.getThread().isAlive()) {
            w(TAG, "Ignoring messages sent after release.")
            message.markAsProcessed(false)
            return
        }
        handler.obtainMessage(MSG_SEND_MESSAGE, message).sendToTarget()
    }

    fun setForegroundMode(foregroundMode: Boolean): Boolean {
        if (releasedOnApplicationThread || !playbackLooper.getThread().isAlive()) {
            return true
        }
        if (foregroundMode) {
            handler.obtainMessage(MSG_SET_FOREGROUND_MODE, 1, 0)
                .sendToTarget()
            return true
        } else {
            val processedCondition = ConditionVariable(clock)
            handler
                .obtainMessage(
                    MSG_SET_FOREGROUND_MODE,
                    0,
                    0,
                    processedCondition
                )
                .sendToTarget()
            return processedCondition.blockUninterruptible(setForegroundModeTimeoutMs)
        }
    }

    fun setVideoOutput(videoOutput: Any?, timeoutMs: Long): Boolean {
        if (releasedOnApplicationThread || !playbackLooper.getThread().isAlive()) {
            return true
        }
        val processedCondition = ConditionVariable(clock)
        handler
            .obtainMessage(
                MSG_SET_VIDEO_OUTPUT,
                Pair<Any?, ConditionVariable?>(videoOutput, processedCondition)
            )
            .sendToTarget()
        if (timeoutMs != C.TIME_UNSET) {
            return processedCondition.blockUninterruptible(timeoutMs)
        }
        return true
    }

    fun release(): Boolean {
        if (releasedOnApplicationThread || !playbackLooper.getThread().isAlive()) {
            return true
        }
        releasedOnApplicationThread = true
        val processedCondition = ConditionVariable(clock)
        handler.obtainMessage(MSG_RELEASE, processedCondition).sendToTarget()
        return processedCondition.blockUninterruptible(releaseTimeoutMs)
    }

    // Playlist.PlaylistInfoRefreshListener implementation.
    override fun onPlaylistUpdateRequested() {
        handler.removeMessages(MSG_DO_SOME_WORK)
        handler.sendEmptyMessage(MSG_PLAYLIST_UPDATE_REQUESTED)
    }

    // MediaPeriod.Callback implementation.
    override fun onPrepared(source: MediaPeriod) {
        handler.obtainMessage(MSG_PERIOD_PREPARED, source).sendToTarget()
    }

    override fun onContinueLoadingRequested(source: MediaPeriod) {
        handler.obtainMessage(MSG_SOURCE_CONTINUE_LOADING_REQUESTED, source).sendToTarget()
    }

    // TrackSelector.InvalidationListener implementation.
    override fun onTrackSelectionsInvalidated(parameters: TrackSelectionParameters?) {
        handler.obtainMessage(MSG_TRACK_SELECTION_INVALIDATED, parameters).sendToTarget()
    }

    override fun onRendererCapabilitiesChanged(renderer: Renderer) {
        handler.sendEmptyMessage(MSG_RENDERER_CAPABILITIES_CHANGED)
    }

    // DefaultMediaClock.PlaybackParametersListener implementation.
    override fun onPlaybackParametersChanged(newPlaybackParameters: PlaybackParameters?) {
        handler
            .obtainMessage(MSG_PLAYBACK_PARAMETERS_CHANGED_INTERNAL, newPlaybackParameters)
            .sendToTarget()
    }

    // AudioFocusManager.PlayerControl implementation
    override fun setVolumeMultiplier(volumeMultiplier: Float) {
        handler.sendEmptyMessage(MSG_AUDIO_FOCUS_VOLUME_MULTIPLIER)
    }

    override fun executePlayerCommand(playerCommand: @PlayerCommand Int) {
        handler.obtainMessage(MSG_AUDIO_FOCUS_PLAYER_COMMAND, playerCommand, 0).sendToTarget()
    }

    // VideoFrameMetadataListener implementation
    override fun onVideoFrameAboutToBeRendered(
        presentationTimeUs: Long,
        releaseTimeNs: Long,
        format: Format,
        mediaFormat: MediaFormat?
    ) {
        if (seekIsPendingWhileScrubbing) {
            handler.obtainMessage(MSG_SEEK_COMPLETED_IN_SCRUBBING_MODE).sendToTarget()
        }
    }

    // Handler.Callback implementation.
    override fun handleMessage(msg: Message): Boolean {
        Log.e(TAG, "handleMessage: ${msg.what}")
        try {
            when (msg.what) {
                MSG_PREPARE -> prepareInternal()
                MSG_SET_PLAY_WHEN_READY -> setPlayWhenReadyInternal(
                    msg.arg1 != 0,
                    msg.arg2 shr 4,
                    true,
                    msg.arg2 and 0x0F
                )

                MSG_SET_REPEAT_MODE -> setRepeatModeInternal(msg.arg1)
                MSG_SET_SHUFFLE_ENABLED -> setShuffleModeEnabledInternal(msg.arg1 != 0)
                MSG_SET_PRELOAD_CONFIGURATION -> setPreloadConfigurationInternal((msg.obj as PreloadConfiguration?)!!)
                MSG_DO_SOME_WORK -> doSomeWork()
                MSG_SEEK_TO -> seekToInternal((msg.obj as SeekPosition?)!!)
                MSG_SEEK_COMPLETED_IN_SCRUBBING_MODE -> {
                    seekIsPendingWhileScrubbing = false
                    if (queuedSeekWhileScrubbing != null) {
                        seekToInternal(queuedSeekWhileScrubbing!!)
                        queuedSeekWhileScrubbing = null
                    }
                }

                MSG_SET_PLAYBACK_PARAMETERS -> setPlaybackParametersInternal(msg.obj as PlaybackParameters)
                MSG_SET_SEEK_PARAMETERS -> setSeekParametersInternal(msg.obj as SeekParameters?)
                MSG_SET_SCRUBBING_MODE_ENABLED -> setScrubbingModeEnabledInternal((msg.obj as kotlin.Boolean?)!!)
                MSG_SET_SCRUBBING_MODE_PARAMETERS -> setScrubbingModeParametersInternal((msg.obj as ScrubbingModeParameters?)!!)
                MSG_SET_FOREGROUND_MODE -> setForegroundModeInternal(
                    msg.arg1 != 0,
                    msg.obj as ConditionVariable?
                )

                MSG_SET_VIDEO_OUTPUT -> {
                    val setVideoOutputPayload =
                        msg.obj as Pair<Any?, ConditionVariable?>
                    setVideoOutputInternal(
                        setVideoOutputPayload.first,
                        setVideoOutputPayload.second
                    )
                }

                MSG_STOP -> stopInternal(
                    false,
                    true
                )

                MSG_PERIOD_PREPARED -> handlePeriodPrepared(msg.obj as MediaPeriod?)
                MSG_SOURCE_CONTINUE_LOADING_REQUESTED -> handleContinueLoadingRequested(msg.obj as MediaPeriod?)
                MSG_TRACK_SELECTION_INVALIDATED -> {
                    val parameters = msg.obj as TrackSelectionParameters?
                    trackSelector.onParametersActivated(parameters)
                    reselectTracksInternal()
                }

                MSG_PLAYBACK_PARAMETERS_CHANGED_INTERNAL -> handlePlaybackParameters(
                    (msg.obj as PlaybackParameters?)!!,
                    false
                )

                MSG_SEND_MESSAGE -> sendMessageInternal((msg.obj as PlayerMessage?)!!)
                MSG_SEND_MESSAGE_TO_TARGET_THREAD -> sendMessageToTargetThread((msg.obj as PlayerMessage?)!!)
                MSG_SET_MEDIA_SOURCES -> setMediaItemsInternal((msg.obj as MediaSourceListUpdateMessage?)!!)
                MSG_ADD_MEDIA_SOURCES -> addMediaItemsInternal(
                    (msg.obj as MediaSourceListUpdateMessage?)!!,
                    msg.arg1
                )

                MSG_MOVE_MEDIA_SOURCES -> moveMediaItemsInternal((msg.obj as MoveMediaItemsMessage?)!!)
                MSG_REMOVE_MEDIA_SOURCES -> removeMediaItemsInternal(
                    msg.arg1,
                    msg.arg2,
                    msg.obj as ShuffleOrder
                )

                MSG_SET_SHUFFLE_ORDER -> setShuffleOrderInternal((msg.obj as ShuffleOrder?)!!)
                MSG_PLAYLIST_UPDATE_REQUESTED -> mediaSourceListUpdateRequestedInternal()
                MSG_SET_PAUSE_AT_END_OF_WINDOW -> setPauseAtEndOfWindowInternal(msg.arg1 != 0)
                MSG_SET_ENFORCE_AD_PLAYBACK_ON_TIMELINE_REFRESH -> setEnforceAdPlaybackOnTimelineRefreshInternal(
                    msg.arg1 != 0
                )

                MSG_ATTEMPT_RENDERER_ERROR_RECOVERY -> attemptRendererErrorRecovery()
                MSG_RENDERER_CAPABILITIES_CHANGED -> reselectTracksInternalAndSeek()
                MSG_UPDATE_MEDIA_SOURCES_WITH_MEDIA_ITEMS -> updateMediaSourcesWithMediaItemsInternal(
                    msg.arg1,
                    msg.arg2,
                    (msg.obj as List<MediaItem>)
                )

                MSG_SET_AUDIO_ATTRIBUTES -> setAudioAttributesInternal(
                    msg.obj as AudioAttributes, msg.arg1 != 0
                )

                MSG_SET_VOLUME -> setVolumeInternal((msg.obj as kotlin.Float?)!!)
                MSG_SET_AUDIO_SESSION_ID -> setAudioSessionIdInternal(msg.arg1)
                MSG_AUDIO_FOCUS_PLAYER_COMMAND -> handleAudioFocusPlayerCommandInternal(
                    msg.arg1
                )

                MSG_AUDIO_FOCUS_VOLUME_MULTIPLIER -> handleAudioFocusVolumeMultiplierChange()
                MSG_SET_VIDEO_FRAME_METADATA_LISTENER -> setVideoFrameMetadataListenerInternal(msg.obj as VideoFrameMetadataListener?)
                MSG_SET_IMAGE_METADATA_LISTENER -> setImageMetadataListenerInternal(msg.obj as ImageMetadataListener?)
                MSG_RELEASE -> {
                    releaseInternal((msg.obj as androidx.media3.common.util.ConditionVariable?)!!)
                    // Return immediately to not send playback info updates after release.
                    return true
                }

                else -> return false
            }
        } catch (e: ExoPlaybackException) {
            var e = e
            if (e.type == ExoPlaybackException.TYPE_RENDERER && e.mediaPeriodId == null) {
                val renderer = renderers[e.rendererIndex]
                var backupErrorPeriod = queue.playingPeriod
                while (backupErrorPeriod != null && !renderer.isReadingFromPeriod(backupErrorPeriod)) {
                    backupErrorPeriod = backupErrorPeriod.getNext()
                }
                if (backupErrorPeriod == null) {
                    backupErrorPeriod = queue.earliestReadingPeriod
                }

                if (backupErrorPeriod != null) {
                    // We can assume that all renderer errors happen in the context of the reading period. See
                    // [internal: b/150584930#comment4] for exceptions that aren't covered by this assumption.
                    e = e.copyWithMediaPeriodId(backupErrorPeriod.info.id)
                }
            }
            if (e.type == ExoPlaybackException.TYPE_RENDERER && e.mediaPeriodId != null && isRendererPrewarmingMediaPeriod(
                    e.rendererIndex,
                    e.mediaPeriodId
                )
            ) {
                isPrewarmingDisabledUntilNextTransition = true
                disableAndResetPrewarmingRenderers()
                // Remove periods from the queue starting at the pre-warming period.
                val prewarmingPeriod = queue.getPrewarmingPeriod(e.rendererIndex)
                var periodToRemoveAfter = queue.playingPeriod
                if (queue.playingPeriod != prewarmingPeriod) {
                    while (periodToRemoveAfter != null && periodToRemoveAfter.getNext() != prewarmingPeriod) {
                        periodToRemoveAfter = periodToRemoveAfter.getNext()
                    }
                }
                queue.removeAfter(periodToRemoveAfter)
                if (playbackInfo.playbackState != Player.STATE_ENDED) {
                    maybeContinueLoading()
                    handler.sendEmptyMessage(MSG_DO_SOME_WORK)
                }
            } else {
                if (pendingRecoverableRendererError != null) {
                    pendingRecoverableRendererError!!.addSuppressed(e)
                    e = pendingRecoverableRendererError!!
                }

                if (e.type == ExoPlaybackException.TYPE_RENDERER
                    && queue.playingPeriod != queue.latestReadingPeriod
                ) {
                    // We encountered a renderer error while reading ahead. Force-update the playback position
                    // to the failing item to ensure correct retry or that the user-visible error is reported
                    // after the transition.
                    var period = queue.playingPeriod
                    while (period != null && period.info.id != e.mediaPeriodId) {
                        period = period.getNext()
                    }
                    if (period == null) {
                        period = queue.earliestReadingPeriod
                    }
                    while (queue.playingPeriod != period) {
                        queue.advancePlayingPeriod()
                    }
                    val newPlayingPeriodHolder = queue.playingPeriod!!
                    // Send already pending updates if needed before making further changes to PlaybackInfo.
                    maybeNotifyPlaybackInfoChanged(msg.what)
                    playbackInfo =
                        handlePositionDiscontinuity(
                            newPlayingPeriodHolder.info.id,
                            newPlayingPeriodHolder.info.startPositionUs,
                            newPlayingPeriodHolder.info.requestedContentPositionUs,
                            newPlayingPeriodHolder.info.startPositionUs,
                            true,
                            Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                        )
                }

                if (e.isRecoverable
                    && (pendingRecoverableRendererError == null || e.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED || e.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED)
                ) {
                    // Given that the player is now in an unhandled exception state, the error needs to be
                    // recovered or the player stopped before any other message is handled.
                    w(TAG, "Recoverable renderer error", e)
                    if (pendingRecoverableRendererError == null) {
                        pendingRecoverableRendererError = e
                    }
                    handler.sendMessageAtFrontOfQueue(
                        handler.obtainMessage(MSG_ATTEMPT_RENDERER_ERROR_RECOVERY, e)
                    )
                } else {
                    e(TAG, "Playback error", e)
                    stopInternal(true, false)
                    playbackInfo = playbackInfo.copyWithPlaybackError(e)
                }
            }
        } catch (e: DrmSessionException) {
            handleIoException(e, e.errorCode)
        } catch (e: ParserException) {
            @PlaybackException.ErrorCode val errorCode: @PlaybackException.ErrorCode Int
            if (e.dataType == C.DATA_TYPE_MEDIA) {
                errorCode =
                    if (e.contentIsMalformed)
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                    else
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
            } else if (e.dataType == C.DATA_TYPE_MANIFEST) {
                errorCode =
                    if (e.contentIsMalformed)
                        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
                    else
                        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
            } else {
                errorCode = PlaybackException.ERROR_CODE_UNSPECIFIED
            }
            handleIoException(e, errorCode)
        } catch (e: DataSourceException) {
            handleIoException(e, e.reason)
        } catch (e: BehindLiveWindowException) {
            handleIoException(e, PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)
        } catch (e: IOException) {
            handleIoException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        } catch (e: RuntimeException) {
            @PlaybackException.ErrorCode val errorCode: @PlaybackException.ErrorCode Int
            if (e is IllegalStateException || e is IllegalArgumentException) {
                errorCode = PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK
            } else {
                errorCode = PlaybackException.ERROR_CODE_UNSPECIFIED
            }
            val error = ExoPlaybackException.createForUnexpected(e, errorCode)
            e(TAG, "Playback error", error)
            stopInternal(true, false)
            playbackInfo = playbackInfo.copyWithPlaybackError(error)
        }
        maybeNotifyPlaybackInfoChanged(msg.what)
        return true
    }

    private fun handleIoException(
        e: IOException?,
        @PlaybackException.ErrorCode errorCode: @PlaybackException.ErrorCode Int
    ) {
        var error = ExoPlaybackException.createForSource(e, errorCode)
        val playingPeriod = queue.playingPeriod
        if (playingPeriod != null) {
            // We ensure that all IOException throwing methods are only executed for the playing period.
            error = error.copyWithMediaPeriodId(playingPeriod.info.id)
        }
        e(TAG, "Playback error", error)
        stopInternal(false, false)
        playbackInfo = playbackInfo.copyWithPlaybackError(error)
    }

    private fun setState(state: Int) {
        if (playbackInfo.playbackState != state) {
            if (state != Player.STATE_BUFFERING) {
                playbackMaybeBecameStuckAtMs = C.TIME_UNSET
            }
            if (state != Player.STATE_READY && playbackInfo.sleepingForOffload) {
                playbackInfo = playbackInfo.copyWithSleepingForOffload(false)
            }
            playbackInfo = playbackInfo.copyWithPlaybackState(state)
        }
    }

    private fun maybeNotifyPlaybackInfoChanged(messageNumber: Int) {
        playbackInfoUpdate.playbackInfo = (playbackInfo)
        if (playbackInfoUpdate.hasPendingChange) {
            if (!playbackInfo.timeline.isEmpty) {
                Preconditions.checkState(
                    playbackInfo.timeline.getIndexOfPeriod(playbackInfo.periodId.periodUid)
                            != C.INDEX_UNSET,
                    String.format(
                        Locale.US,
                        "periodUid %s not found in timeline %s with size %d triggered by msg %d",
                        playbackInfo.periodId.periodUid,
                        playbackInfo.timeline.javaClass.getName(),
                        playbackInfo.timeline.windowCount,
                        messageNumber
                    )
                )
            }
            playbackInfoUpdateListener.onPlaybackInfoUpdate(playbackInfoUpdate)
            playbackInfoUpdate = PlaybackInfoUpdate(playbackInfo)
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun prepareInternal() {
        playbackInfoUpdate.incrementPendingOperationAcks(1)
        resetInternal(
            false,
            false,
            false,
            true
        )
        loadControl.onPrepared(playerId)
        setState(if (playbackInfo.timeline.isEmpty) Player.STATE_ENDED else Player.STATE_BUFFERING)
        updatePlayWhenReadyWithAudioFocus()
        mediaSourceList.prepare()
        handler.sendEmptyMessage(MSG_DO_SOME_WORK)
    }

    @Throws(ExoPlaybackException::class)
    private fun setMediaItemsInternal(mediaSourceListUpdateMessage: MediaSourceListUpdateMessage) {
        playbackInfoUpdate.incrementPendingOperationAcks(1)
        if (mediaSourceListUpdateMessage.windowIndex != C.INDEX_UNSET) {
            pendingInitialSeekPosition =
                SeekPosition(
                    PlaylistTimeline(
                        mediaSourceListUpdateMessage.mediaSourceHolders,
                        mediaSourceListUpdateMessage.shuffleOrder
                    ),
                    mediaSourceListUpdateMessage.windowIndex,
                    mediaSourceListUpdateMessage.positionUs
                )
        }
        val timeline =
            mediaSourceList.setMediaSources(
                mediaSourceListUpdateMessage.mediaSourceHolders,
                mediaSourceListUpdateMessage.shuffleOrder
            )
        handleMediaSourceListInfoRefreshed(timeline, false)
    }

    @Throws(ExoPlaybackException::class)
    private fun addMediaItemsInternal(
        addMessage: MediaSourceListUpdateMessage,
        insertionIndex: Int
    ) {
        playbackInfoUpdate.incrementPendingOperationAcks(1)
        val timeline =
            mediaSourceList.addMediaSources(
                if (insertionIndex == C.INDEX_UNSET) mediaSourceList.size else insertionIndex,
                addMessage.mediaSourceHolders,
                addMessage.shuffleOrder
            )
        handleMediaSourceListInfoRefreshed(timeline, false)
    }

    @Throws(ExoPlaybackException::class)
    private fun moveMediaItemsInternal(moveMediaItemsMessage: MoveMediaItemsMessage) {
        playbackInfoUpdate.incrementPendingOperationAcks(1)
        val timeline =
            mediaSourceList.moveMediaSourceRange(
                moveMediaItemsMessage.fromIndex,
                moveMediaItemsMessage.toIndex,
                moveMediaItemsMessage.newFromIndex,
                moveMediaItemsMessage.shuffleOrder
            )
        handleMediaSourceListInfoRefreshed(timeline, false)
    }

    @Throws(ExoPlaybackException::class)
    private fun removeMediaItemsInternal(
        fromIndex: Int,
        toIndex: Int,
        shuffleOrder: ShuffleOrder
    ) {
        playbackInfoUpdate.incrementPendingOperationAcks(1)
        val timeline = mediaSourceList.removeMediaSourceRange(fromIndex, toIndex, shuffleOrder)
        handleMediaSourceListInfoRefreshed(timeline, false)
    }

    @Throws(ExoPlaybackException::class)
    private fun mediaSourceListUpdateRequestedInternal() {
        handleMediaSourceListInfoRefreshed(
            mediaSourceList.createTimeline(), true
        )
    }

    @Throws(ExoPlaybackException::class)
    private fun setShuffleOrderInternal(shuffleOrder: ShuffleOrder) {
        playbackInfoUpdate.incrementPendingOperationAcks(1)
        val timeline = mediaSourceList.setShuffleOrder(shuffleOrder)
        handleMediaSourceListInfoRefreshed(timeline, false)
    }

    @Throws(ExoPlaybackException::class)
    private fun updateMediaSourcesWithMediaItemsInternal(
        fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>
    ) {
        playbackInfoUpdate.incrementPendingOperationAcks(1)
        val timeline =
            mediaSourceList.updateMediaSourcesWithMediaItems(fromIndex, toIndex, mediaItems)
        handleMediaSourceListInfoRefreshed(timeline, false)
    }

    @Throws(ExoPlaybackException::class)
    private fun setAudioAttributesInternal(
        audioAttributes: AudioAttributes,
        handleAudioFocus: Boolean
    ) {
        trackSelector.setAudioAttributes(audioAttributes)
        audioFocusManager.setAudioAttributes(if (handleAudioFocus) audioAttributes else null)
        updatePlayWhenReadyWithAudioFocus()
    }

    @Throws(ExoPlaybackException::class)
    private fun setVolumeInternal(volume: Float) {
        this.volume = volume
        val scaledVolume = volume * audioFocusManager.volumeMultiplier
        for (renderer in renderers) {
            renderer.setVolume(scaledVolume)
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun setAudioSessionIdInternal(audioSessionId: Int) {
        for (renderer in renderers) {
            renderer.setAudioSessionId(audioSessionId)
        }
    }

    private fun notifyTrackSelectionPlayWhenReadyChanged(playWhenReady: Boolean) {
        var periodHolder = queue.playingPeriod
        while (periodHolder != null) {
            for (trackSelection in periodHolder.trackSelectorResult.selections) {
                trackSelection?.onPlayWhenReadyChanged(playWhenReady)
            }
            periodHolder = periodHolder.getNext()
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun setPlayWhenReadyInternal(
        playWhenReady: Boolean,
        playbackSuppressionReason: @Player.PlaybackSuppressionReason Int,
        operationAck: Boolean,
        reason: @Player.PlayWhenReadyChangeReason Int
    ) {
        playbackInfoUpdate.incrementPendingOperationAcks(if (operationAck) 1 else 0)
        updatePlayWhenReadyWithAudioFocus(playWhenReady, playbackSuppressionReason, reason)
    }

    @Throws(ExoPlaybackException::class)
    private fun updatePlayWhenReadyWithAudioFocus(
        playWhenReady: Boolean = playbackInfo.playWhenReady,
        playbackSuppressionReason: @Player.PlaybackSuppressionReason Int = playbackInfo.playbackSuppressionReason,
        playWhenReadyChangeReason: @Player.PlayWhenReadyChangeReason Int = playbackInfo.playWhenReadyChangeReason
    ) {
        val playerCommand: @PlayerCommand Int =
            audioFocusManager.updateAudioFocus(playWhenReady, playbackInfo.playbackState)
        updatePlayWhenReadyWithAudioFocus(
            playWhenReady, playerCommand, playbackSuppressionReason, playWhenReadyChangeReason
        )
    }

    @Throws(ExoPlaybackException::class)
    private fun updatePlayWhenReadyWithAudioFocus(
        playWhenReady: Boolean,
        playerCommand: @PlayerCommand Int,
        playbackSuppressionReason: @Player.PlaybackSuppressionReason Int,
        playWhenReadyChangeReason: @Player.PlayWhenReadyChangeReason Int
    ) {
        var playWhenReady = playWhenReady
        var playbackSuppressionReason = playbackSuppressionReason
        var playWhenReadyChangeReason = playWhenReadyChangeReason
        playWhenReady =
            playWhenReady && playerCommand != AudioFocusManager.PLAYER_COMMAND_DO_NOT_PLAY
        playWhenReadyChangeReason =
            updatePlayWhenReadyChangeReason(playerCommand, playWhenReadyChangeReason)
        playbackSuppressionReason =
            updatePlaybackSuppressionReason(
                playerCommand, playbackSuppressionReason, scrubbingModeEnabled
            )
        if (playbackInfo.playWhenReady == playWhenReady && playbackInfo.playbackSuppressionReason == playbackSuppressionReason && playbackInfo.playWhenReadyChangeReason == playWhenReadyChangeReason) {
            return
        }
        playbackInfo =
            playbackInfo.copyWithPlayWhenReady(
                playWhenReady, playWhenReadyChangeReason, playbackSuppressionReason
            )
        updateRebufferingState(false, false)
        notifyTrackSelectionPlayWhenReadyChanged(playWhenReady)
        if (!shouldPlayWhenReady()) {
            stopRenderers()
            updatePlaybackPositions()
            if (playbackInfo.sleepingForOffload) {
                playbackInfo = playbackInfo.copyWithSleepingForOffload(false)
            }
            queue.reevaluateBuffer(rendererPositionUs)
        } else {
            if (playbackInfo.playbackState == Player.STATE_READY) {
                mediaClock.start()
                startRenderers()
                handler.sendEmptyMessage(MSG_DO_SOME_WORK)
            } else if (playbackInfo.playbackState == Player.STATE_BUFFERING) {
                handler.sendEmptyMessage(MSG_DO_SOME_WORK)
            }
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun setPauseAtEndOfWindowInternal(pauseAtEndOfWindow: Boolean) {
        this.pauseAtEndOfWindow = pauseAtEndOfWindow
        resetPendingPauseAtEndOfPeriod()
        if (pendingPauseAtEndOfPeriod && this.isReadingAhead) {
            // When pausing is required, we need to set the streams of the playing period final. If we
            // already started reading the next period, we need to flush the renderers.
            seekToCurrentPosition(true)
            handleLoadingMediaPeriodChanged(false)
        }
    }

    private fun setEnforceAdPlaybackOnTimelineRefreshInternal(
        enforceAdPlaybackOnTimelineRefresh: Boolean
    ) {
        this.enforceAdPlaybackOnTimelineRefresh = enforceAdPlaybackOnTimelineRefresh
    }

    private fun setOffloadSchedulingEnabled(offloadSchedulingEnabled: Boolean) {
        if (offloadSchedulingEnabled == this.offloadSchedulingEnabled) {
            return
        }
        this.offloadSchedulingEnabled = offloadSchedulingEnabled
        if (!offloadSchedulingEnabled && playbackInfo.sleepingForOffload) {
            // We need to wake the player up if offload scheduling is disabled and we are sleeping.
            handler.sendEmptyMessage(MSG_DO_SOME_WORK)
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun setRepeatModeInternal(@Player.RepeatMode repeatMode: @Player.RepeatMode Int) {
        this.repeatMode = repeatMode
        val result: @UpdatePeriodQueueResult Int =
            queue.updateRepeatMode(playbackInfo.timeline, repeatMode)
        if ((result and MediaPeriodQueue.UPDATE_PERIOD_QUEUE_ALTERED_READING_PERIOD) != 0) {
            seekToCurrentPosition(true)
        } else if ((result and MediaPeriodQueue.UPDATE_PERIOD_QUEUE_ALTERED_PREWARMING_PERIOD) != 0) {
            disableAndResetPrewarmingRenderers()
        }
        handleLoadingMediaPeriodChanged(false)
    }

    @Throws(ExoPlaybackException::class)
    private fun setShuffleModeEnabledInternal(shuffleModeEnabled: Boolean) {
        this.shuffleModeEnabled = shuffleModeEnabled
        val result: @UpdatePeriodQueueResult Int =
            queue.updateShuffleModeEnabled(playbackInfo.timeline, shuffleModeEnabled)
        if ((result and MediaPeriodQueue.UPDATE_PERIOD_QUEUE_ALTERED_READING_PERIOD) != 0) {
            seekToCurrentPosition(true)
        } else if ((result and MediaPeriodQueue.UPDATE_PERIOD_QUEUE_ALTERED_PREWARMING_PERIOD) != 0) {
            disableAndResetPrewarmingRenderers()
        }
        handleLoadingMediaPeriodChanged(false)
    }

    private fun setPreloadConfigurationInternal(preloadConfiguration: PreloadConfiguration) {
        this.preloadConfiguration = preloadConfiguration
        queue.updatePreloadConfiguration(playbackInfo.timeline, preloadConfiguration)
    }

    @Throws(ExoPlaybackException::class)
    private fun seekToCurrentPosition(sendDiscontinuity: Boolean) {
        // Renderers may have read from a period that's been removed. Seek back to the current
        // position of the playing period to make sure none of the removed period is played.
        val periodId = queue.playingPeriod!!.info.id
        val newPositionUs =
            seekToPeriodPosition(
                periodId,
                playbackInfo.positionUs,
                true,
                false
            )
        if (newPositionUs != playbackInfo.positionUs) {
            playbackInfo =
                handlePositionDiscontinuity(
                    periodId,
                    newPositionUs,
                    playbackInfo.requestedContentPositionUs,
                    playbackInfo.discontinuityStartPositionUs,
                    sendDiscontinuity,
                    Player.DISCONTINUITY_REASON_INTERNAL
                )
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun startRenderers() {
        val playingPeriodHolder = queue.playingPeriod ?: return
        val trackSelectorResult = playingPeriodHolder.trackSelectorResult
        for (i in renderers.indices) {
            if (!trackSelectorResult.isRendererEnabled(i)) {
                continue
            }
            renderers[i].start()
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun stopRenderers() {
        mediaClock.stop()
        for (rendererHolder in renderers) {
            rendererHolder.stop()
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun attemptRendererErrorRecovery() {
        reselectTracksInternalAndSeek()
    }

    @Throws(ExoPlaybackException::class)
    private fun updatePlaybackPositions() {
        val playingPeriodHolder = queue.playingPeriod ?: return

        // Update the playback position.
        val discontinuityPositionUs =
            if (playingPeriodHolder.prepared)
                playingPeriodHolder.mediaPeriod.readDiscontinuity()
            else
                C.TIME_UNSET
        if (discontinuityPositionUs != C.TIME_UNSET) {
            if (!playingPeriodHolder.isFullyBuffered) {
                // The discontinuity caused the period to not be fully buffered. Continue loading from this
                // period again and discard all other periods we already started loading.
                queue.removeAfter(playingPeriodHolder)
                // Since subsequent periods are discarded, prewarming must also be reset.
                disableAndResetPrewarmingRenderers()
                handleLoadingMediaPeriodChanged(false)
                maybeContinueLoading()
            }
            resetRendererPosition(
                discontinuityPositionUs,
                true
            )
            // A MediaPeriod may report a discontinuity at the current playback position to ensure the
            // renderers are flushed. Only report the discontinuity externally if the position changed.
            if (discontinuityPositionUs != playbackInfo.positionUs) {
                playbackInfo =
                    handlePositionDiscontinuity(
                        playbackInfo.periodId,
                        discontinuityPositionUs,
                        playbackInfo.requestedContentPositionUs,
                        discontinuityPositionUs,
                        true,
                        Player.DISCONTINUITY_REASON_INTERNAL
                    )
            }
        } else {
            rendererPositionUs = mediaClock.syncAndGetPositionUs(this.isReadingAhead)
            val periodPositionUs = playingPeriodHolder.toPeriodTime(rendererPositionUs)
            maybeTriggerPendingMessages(playbackInfo.positionUs, periodPositionUs)
            if (mediaClock.hasSkippedSilenceSinceLastCall()) {
                // Only report silence skipping if there isn't already another discontinuity.
                val reportSilenceSkip = !playbackInfoUpdate.positionDiscontinuity
                playbackInfo =
                    handlePositionDiscontinuity(
                        playbackInfo.periodId,
                        periodPositionUs,
                        playbackInfo.requestedContentPositionUs,
                        periodPositionUs,
                        reportSilenceSkip,
                        Player.DISCONTINUITY_REASON_SILENCE_SKIP
                    )
            } else {
                playbackInfo.updatePositionUs(periodPositionUs)
            }
        }

        // Update the buffered position and total buffered duration.
        val loadingPeriod = queue.loadingPeriod
        playbackInfo.bufferedPositionUs = loadingPeriod!!.bufferedPositionUs
        playbackInfo.totalBufferedDurationUs = this.totalBufferedDurationUs

        // Adjust live playback speed to new position.
        if (playbackInfo.playWhenReady
            && playbackInfo.playbackState == Player.STATE_READY && shouldUseLivePlaybackSpeedControl(
                playbackInfo.timeline,
                playbackInfo.periodId
            )
            && playbackInfo.playbackParameters.speed == 1f
        ) {
            val adjustedSpeed =
                livePlaybackSpeedControl.getAdjustedPlaybackSpeed(
                    this.currentLiveOffsetUs, playbackInfo.totalBufferedDurationUs
                )
            if (mediaClock.playbackParameters.speed != adjustedSpeed) {
                setMediaClockPlaybackParameters(
                    playbackInfo.playbackParameters.withSpeed(
                        adjustedSpeed
                    )
                )
                handlePlaybackParameters(
                    playbackInfo.playbackParameters,
                    mediaClock.playbackParameters.speed,
                    false,
                    false
                )
            }
        }
    }

    private fun setMediaClockPlaybackParameters(playbackParameters: PlaybackParameters) {
        // Previously sent speed updates from the media clock now become stale.
        handler.removeMessages(MSG_PLAYBACK_PARAMETERS_CHANGED_INTERNAL)
        mediaClock.playbackParameters = (playbackParameters)
    }

    private fun notifyTrackSelectionRebuffer() {
        var periodHolder = queue.playingPeriod
        while (periodHolder != null) {
            for (trackSelection in periodHolder.trackSelectorResult.selections) {
                trackSelection?.onRebuffer()
            }
            periodHolder = periodHolder.getNext()
        }
    }

    @Throws(ExoPlaybackException::class, IOException::class)
    private fun doSomeWork() {
        val operationStartTimeMs = clock.uptimeMillis()
        // Remove other pending DO_SOME_WORK requests that are handled by this invocation.
        handler.removeMessages(MSG_DO_SOME_WORK)

        if (playbackInfo.playbackState == Player.STATE_IDLE
            || playbackInfo.playbackState == Player.STATE_ENDED
        ) {
            // Nothing to do. Prepare (in case of IDLE) or seek (in case of ENDED) will resume.
            return
        }

        updatePeriods()

        val playingPeriodHolder = queue.playingPeriod
        if (playingPeriodHolder == null) {
            // We're still waiting until the playing period is available.
            scheduleNextWork(operationStartTimeMs)
            return
        }

        beginSection("doSomeWork")

        updatePlaybackPositions()

        var renderersEnded = true
        var renderersAllowPlayback = true
        var hasActiveVideoOrImageRenderer = false
        Log.e(TAG, "doSomeWork: "+playingPeriodHolder.prepared, )
        if (playingPeriodHolder.prepared) {
            rendererPositionElapsedRealtimeUs = msToUs(clock.elapsedRealtime())
            playingPeriodHolder.mediaPeriod.discardBuffer(
                playbackInfo.positionUs - backBufferDurationUs, retainBackBufferFromKeyframe
            )
            for (i in renderers.indices) {
                val renderer = renderers[i]
                if (renderer.enabledRendererCount == 0) {
                    maybeTriggerOnRendererReadyChanged(
                        i,
                        false
                    )
                    continue
                }
                renderer.render(rendererPositionUs, rendererPositionElapsedRealtimeUs)
                // Determine whether the renderer allows playback to continue. Playback can
                // continue if the renderer is ready or ended. Also continue playback if the renderer is
                // reading ahead into the next stream or is waiting for the next stream. This is to avoid
                // getting stuck if tracks in the current period have uneven durations and are still being
                // read by another renderer. See: https://github.com/google/ExoPlayer/issues/1874.
                renderersEnded = renderersEnded && renderer.isEnded
                if (seekIsPendingWhileScrubbing
                    && renderer.isRendererEnabled
                    && (renderer.trackType == C.TRACK_TYPE_VIDEO
                            || renderer.trackType == C.TRACK_TYPE_IMAGE)
                    && !renderer.isEnded
                ) {
                    hasActiveVideoOrImageRenderer = true
                }
                val allowsPlayback = renderer.allowsPlayback(playingPeriodHolder)
                maybeTriggerOnRendererReadyChanged(i, allowsPlayback)
                renderersAllowPlayback = renderersAllowPlayback && allowsPlayback
                if (!allowsPlayback) {
                    maybeThrowRendererStreamError(i)
                }
            }
            if (seekIsPendingWhileScrubbing
                && !hasActiveVideoOrImageRenderer && !handler.hasMessages(
                    MSG_SEEK_COMPLETED_IN_SCRUBBING_MODE
                )
            ) {
                handler.obtainMessage(MSG_SEEK_COMPLETED_IN_SCRUBBING_MODE).sendToTarget()
            }
        } else {
            playingPeriodHolder.mediaPeriod.maybeThrowPrepareError()
        }

        val playingPeriodDurationUs = playingPeriodHolder.info.durationUs
        val finishedRendering =
            renderersEnded
                    && playingPeriodHolder.prepared
                    && (playingPeriodDurationUs == C.TIME_UNSET
                    || playingPeriodDurationUs <= playbackInfo.positionUs)
        if (finishedRendering && pendingPauseAtEndOfPeriod) {
            pendingPauseAtEndOfPeriod = false
            setPlayWhenReadyInternal(
                false,
                playbackInfo.playbackSuppressionReason,
                false,
                Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
            )
        }
        if (finishedRendering && playingPeriodHolder.info.isFinal) {
            setState(Player.STATE_ENDED)
            stopRenderers()
        } else if (playbackInfo.playbackState == Player.STATE_BUFFERING
            && shouldTransitionToReadyState(renderersAllowPlayback)
        ) {
            setState(Player.STATE_READY)
            pendingRecoverableRendererError =
                null // Any pending error was successfully recovered from.
            if (shouldPlayWhenReady()) {
                updateRebufferingState(
                    false, false
                )
                mediaClock.start()
                startRenderers()
            }
        } else if (playbackInfo.playbackState == Player.STATE_READY
            && !(if (enabledRendererCount == 0) this.isTimelineReady else renderersAllowPlayback)
        ) {
            updateRebufferingState(
                shouldPlayWhenReady(), false
            )
            setState(Player.STATE_BUFFERING)
            if (isRebuffering) {
                notifyTrackSelectionRebuffer()
                livePlaybackSpeedControl.notifyRebuffer()
            }
            stopRenderers()
        }

        var playbackMaybeStuck = false
        if (playbackInfo.playbackState == Player.STATE_BUFFERING) {
            for (i in renderers.indices) {
                if (renderers[i].isReadingFromPeriod(playingPeriodHolder)) {
                    maybeThrowRendererStreamError(i)
                }
            }
            if (!playbackInfo.isLoading && playbackInfo.totalBufferedDurationUs < PLAYBACK_BUFFER_EMPTY_THRESHOLD_US && isLoadingPossible(
                    queue.loadingPeriod
                )
                && shouldPlayWhenReady()
            ) {
                // The renderers are not ready, there is more media available to load, and the LoadControl
                // is refusing to load it (indicated by !playbackInfo.isLoading). This could be because the
                // renderers are still transitioning to their ready states, but it could also indicate a
                // stuck playback. The playbackInfo.totalBufferedDurationUs check further isolates the
                // cause to a lack of media for the renderers to consume, to avoid classifying playbacks as
                // stuck when they're waiting for other reasons (in particular, loading DRM keys).
                playbackMaybeStuck = true
            }
        }

        if (!playbackMaybeStuck) {
            playbackMaybeBecameStuckAtMs = C.TIME_UNSET
        } else if (playbackMaybeBecameStuckAtMs == C.TIME_UNSET) {
            playbackMaybeBecameStuckAtMs = clock.elapsedRealtime()
        } else if (clock.elapsedRealtime() - playbackMaybeBecameStuckAtMs >= PLAYBACK_STUCK_AFTER_MS) {
            throw StuckPlayerException(
                StuckPlayerException.STUCK_BUFFERING_NOT_LOADING, PLAYBACK_STUCK_AFTER_MS
            )
        }

        val isPlaying = shouldPlayWhenReady() && playbackInfo.playbackState == Player.STATE_READY
        val sleepingForOffload = offloadSchedulingEnabled && requestForRendererSleep && isPlaying
        if (playbackInfo.sleepingForOffload != sleepingForOffload) {
            playbackInfo = playbackInfo.copyWithSleepingForOffload(sleepingForOffload)
        }
        requestForRendererSleep = false // A sleep request is only valid for the current doSomeWork.

        if (sleepingForOffload || playbackInfo.playbackState == Player.STATE_ENDED) {
            // No need to schedule next work.
        } else if ((isPlaying || playbackInfo.playbackState == Player.STATE_BUFFERING)
            || (playbackInfo.playbackState == Player.STATE_READY && enabledRendererCount != 0)
        ) {
            // Schedule next work as either we are actively playing, buffering, or we
            // are ready but not playing.
            scheduleNextWork(operationStartTimeMs)
        }

        endSection()
    }

    private fun maybeTriggerOnRendererReadyChanged(rendererIndex: Int, allowsPlayback: Boolean) {
        if (rendererReportedReady[rendererIndex] != allowsPlayback) {
            rendererReportedReady[rendererIndex] = allowsPlayback
            applicationLooperHandler.post {
                analyticsCollector.onRendererReadyChanged(
                    rendererIndex, renderers[rendererIndex].trackType, allowsPlayback
                )
            }
        }
    }

    private val currentLiveOffsetUs: Long
        get() = getLiveOffsetUs(
            playbackInfo.timeline, playbackInfo.periodId.periodUid, playbackInfo.positionUs
        )

    private fun getLiveOffsetUs(timeline: Timeline, periodUid: Any?, periodPositionUs: Long): Long {
        val windowIndex = timeline.getPeriodByUid(periodUid!!, period).windowIndex
        timeline.getWindow(windowIndex, window)
        if (window.windowStartTimeMs == C.TIME_UNSET || !window.isLive || !window.isDynamic) {
            return C.TIME_UNSET
        }
        return (msToUs(window.currentUnixTimeMs - window.windowStartTimeMs)
                - (periodPositionUs + period.positionInWindowUs))
    }

    private fun shouldUseLivePlaybackSpeedControl(
        timeline: Timeline, mediaPeriodId: MediaPeriodId
    ): Boolean {
        if (mediaPeriodId.isAd || timeline.isEmpty) {
            return false
        }
        val windowIndex = timeline.getPeriodByUid(mediaPeriodId.periodUid, period).windowIndex
        timeline.getWindow(windowIndex, window)
        return window.isLive && window.isDynamic && window.windowStartTimeMs != C.TIME_UNSET
    }

    private fun scheduleNextWork(thisOperationStartTimeMs: Long) {
        val wakeUpTimeIntervalMs =
            if (isDynamicSchedulingEnabled())
                this.dynamicSchedulingWakeUpIntervalMs
            else
                this.staticSchedulingWakeUpIntervalMs
        handler.sendEmptyMessageAtTime(
            MSG_DO_SOME_WORK, thisOperationStartTimeMs + wakeUpTimeIntervalMs
        )
    }

    private val dynamicSchedulingWakeUpIntervalMs: Long
        get() {
            var wakeUpTimeIntervalMs: Long =
                if (playbackInfo.playbackState == Player.STATE_READY)
                    READY_MAXIMUM_INTERVAL_MS
                else
                    BUFFERING_MAXIMUM_INTERVAL_MS
            for (rendererHolder in renderers) {
                wakeUpTimeIntervalMs =
                    min(
                        wakeUpTimeIntervalMs,
                        usToMs(
                            rendererHolder.getMinDurationToProgressUs(
                                rendererPositionUs, rendererPositionElapsedRealtimeUs
                            )
                        )
                    )
            }
            // Do not schedule next doSomeWork past the playing period transition point.
            if (playbackInfo.isPlaying) {
                val nextPlayingPeriodHolder =
                    if (queue.playingPeriod != null) queue.playingPeriod!!
                        .getNext() else null
                if (nextPlayingPeriodHolder != null
                    && (rendererPositionUs
                            + msToUs(wakeUpTimeIntervalMs) * playbackInfo.playbackParameters.speed
                            >= nextPlayingPeriodHolder.startPositionRendererTime)
                ) {
                    wakeUpTimeIntervalMs = min(
                        wakeUpTimeIntervalMs,
                        BUFFERING_MAXIMUM_INTERVAL_MS
                    )
                }
            }
            return wakeUpTimeIntervalMs
        }

    private val staticSchedulingWakeUpIntervalMs: Long
        get() = if (playbackInfo.playbackState == Player.STATE_READY && !shouldPlayWhenReady())
            READY_MAXIMUM_INTERVAL_MS
        else
            BUFFERING_MAXIMUM_INTERVAL_MS

    @Throws(ExoPlaybackException::class)
    private fun seekToInternal(seekPosition: SeekPosition) {
        if (seekIsPendingWhileScrubbing) {
            if (queuedSeekWhileScrubbing != null) {
                droppedSeeksWhileScrubbing++
                playbackInfoUpdate.incrementPendingOperationAcks(1)
            }
            queuedSeekWhileScrubbing = seekPosition
            return
        }
        playbackInfoUpdate.incrementPendingOperationAcks(1)
        val periodId: MediaPeriodId
        var periodPositionUs: Long
        var requestedContentPositionUs: Long
        var seekPositionAdjusted: Boolean
        val resolvedSeekPosition: Pair<Any, Long>? =
            resolveSeekPositionUs(
                playbackInfo.timeline,
                seekPosition,
                true,
                repeatMode,
                shuffleModeEnabled,
                window,
                period
            )
        if (resolvedSeekPosition == null) {
            // The seek position was valid for the timeline that it was performed into, but the
            // timeline has changed or is not ready and a suitable seek position could not be resolved.
            val firstPeriodAndPositionUs =
                getPlaceholderFirstMediaPeriodPositionUs(playbackInfo.timeline)
            periodId = firstPeriodAndPositionUs.first
            periodPositionUs = firstPeriodAndPositionUs.second!!
            requestedContentPositionUs = C.TIME_UNSET
            seekPositionAdjusted = !playbackInfo.timeline.isEmpty
        } else {
            // Update the resolved seek position to take ads into account.
            val periodUid = resolvedSeekPosition.first
            val resolvedContentPositionUs: Long = resolvedSeekPosition.second!!
            requestedContentPositionUs =
                if (seekPosition.windowPositionUs == C.TIME_UNSET) C.TIME_UNSET else resolvedContentPositionUs
            periodId =
                queue.resolveMediaPeriodIdForAdsAfterPeriodPositionChange(
                    playbackInfo,
                    playbackInfo.timeline,
                    periodUid!!,
                    resolvedContentPositionUs,
                    true,
                    false
                )
            if (periodId.isAd) {
                playbackInfo.timeline.getPeriodByUid(periodId.periodUid, period)
                periodPositionUs =
                    if (period.getFirstAdIndexToPlay(periodId.adGroupIndex) == periodId.adIndexInAdGroup)
                        period.adResumePositionUs
                    else
                        0
                seekPositionAdjusted = true
                val adGroup = period.adPlaybackState.getAdGroup(periodId.adGroupIndex)
                requestedContentPositionUs =
                    max(requestedContentPositionUs, adGroup.timeUs + adGroup.contentResumeOffsetUs)
            } else {
                periodPositionUs = resolvedContentPositionUs
                seekPositionAdjusted = seekPosition.windowPositionUs == C.TIME_UNSET
            }
        }

        try {
            if (playbackInfo.timeline.isEmpty) {
                // Save seek position for later, as we are still waiting for a prepared source.
                pendingInitialSeekPosition = seekPosition
            } else if (resolvedSeekPosition == null) {
                // End playback, as we didn't manage to find a valid seek position.
                if (playbackInfo.playbackState != Player.STATE_IDLE) {
                    setState(Player.STATE_ENDED)
                }
                resetInternal(
                    false,
                    true,
                    false,
                    true
                )
            } else {
                // Execute the seek in the current media periods.
                var newPeriodPositionUs = periodPositionUs
                if (periodId == playbackInfo.periodId) {
                    val playingPeriodHolder = queue.playingPeriod
                    if (playingPeriodHolder != null && playingPeriodHolder.prepared
                        && newPeriodPositionUs != 0L
                    ) {
                        newPeriodPositionUs =
                            playingPeriodHolder.mediaPeriod.getAdjustedSeekPositionUs(
                                newPeriodPositionUs, getSeekParameters(window.durationUs)!!
                            )
                    }
                    if (usToMs(newPeriodPositionUs) == usToMs(playbackInfo.positionUs)
                        && (playbackInfo.playbackState == Player.STATE_BUFFERING
                                || playbackInfo.playbackState == Player.STATE_READY)
                    ) {
                        // Seek will be performed to the current position. Do nothing.
                        periodPositionUs = playbackInfo.positionUs
                        return
                    }
                }

                newPeriodPositionUs =
                    seekToPeriodPosition(
                        periodId,
                        newPeriodPositionUs,
                        playbackInfo.playbackState == Player.STATE_ENDED
                    )
                seekPositionAdjusted =
                    seekPositionAdjusted or (periodPositionUs != newPeriodPositionUs)
                periodPositionUs = newPeriodPositionUs
                updatePlaybackSpeedSettingsForNewPeriod(
                    playbackInfo.timeline,
                    periodId,
                    playbackInfo.timeline,
                    playbackInfo.periodId,
                    requestedContentPositionUs,
                    true
                )
            }
        } finally {
            playbackInfo =
                handlePositionDiscontinuity(
                    periodId,
                    periodPositionUs,
                    requestedContentPositionUs,
                    periodPositionUs,
                    seekPositionAdjusted,
                    Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                )
        }
    }

    private fun getSeekParameters(durationUs: Long): SeekParameters? {
        if (!scrubbingModeEnabled || durationUs == C.TIME_UNSET || scrubbingModeParameters.fractionalSeekToleranceBefore == null || scrubbingModeParameters.fractionalSeekToleranceAfter == null) {
            return seekParameters
        }
        val toleranceBeforeUs =
            DoubleMath.roundToLong(
                scrubbingModeParameters.fractionalSeekToleranceBefore!! * durationUs,
                RoundingMode.FLOOR
            )
        val toleranceAfterUs =
            DoubleMath.roundToLong(
                scrubbingModeParameters.fractionalSeekToleranceAfter!! * durationUs,
                RoundingMode.FLOOR
            )
        if (scrubbingModeSeekParameters == null || scrubbingModeSeekParameters!!.toleranceBeforeUs != toleranceBeforeUs || scrubbingModeSeekParameters!!.toleranceAfterUs != toleranceAfterUs) {
            scrubbingModeSeekParameters = SeekParameters(toleranceBeforeUs, toleranceAfterUs)
        }
        return scrubbingModeSeekParameters
    }

    @Throws(ExoPlaybackException::class)
    private fun seekToPeriodPosition(
        periodId: MediaPeriodId, periodPositionUs: Long, forceBufferingState: Boolean
    ): Long {
        // Force disable renderers if they are reading from a period other than the one being played.
        return seekToPeriodPosition(
            periodId, periodPositionUs,
            this.isReadingAhead, forceBufferingState
        )
    }

    @Throws(ExoPlaybackException::class)
    private fun seekToPeriodPosition(
        periodId: MediaPeriodId,
        periodPositionUs: Long,
        forceDisableRenderers: Boolean,
        forceBufferingState: Boolean
    ): Long {
        var periodPositionUs = periodPositionUs
        stopRenderers()
        updateRebufferingState(false, true)
        if (forceBufferingState || playbackInfo.playbackState == Player.STATE_READY) {
            setState(Player.STATE_BUFFERING)
        }

        // Find the requested period if it already exists.
        val oldPlayingPeriodHolder = queue.playingPeriod
        var newPlayingPeriodHolder = oldPlayingPeriodHolder
        while (newPlayingPeriodHolder != null) {
            if (periodId.equals(newPlayingPeriodHolder.info.id)) {
                break
            }
            newPlayingPeriodHolder = newPlayingPeriodHolder.getNext()
        }

        // Disable all renderers if the period being played is changing, if the seek results in negative
        // renderer timestamps, or if forced.
        if (forceDisableRenderers
            || oldPlayingPeriodHolder != newPlayingPeriodHolder || (newPlayingPeriodHolder != null
                    && newPlayingPeriodHolder.toRendererTime(periodPositionUs) < 0)
        ) {
            disableRenderers()
            if (newPlayingPeriodHolder != null) {
                // Update the queue and reenable renderers if the requested media period already exists.
                while (queue.playingPeriod != newPlayingPeriodHolder) {
                    queue.advancePlayingPeriod()
                }
                queue.removeAfter(newPlayingPeriodHolder)
                newPlayingPeriodHolder.rendererOffset = (
                        MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US
                        )
                enableRenderers()
                setAllRenderersToCorrectState(newPlayingPeriodHolder)
            }
        }

        // Disable pre-warming as following logic will reset any pre-warming media periods.
        disableAndResetPrewarmingRenderers()

        if (scrubbingModeEnabled) {
            for (renderer in renderers) {
                if (renderer.isRendererEnabled
                    && (renderer.trackType == C.TRACK_TYPE_VIDEO
                            || renderer.trackType == C.TRACK_TYPE_IMAGE)
                ) {
                    seekIsPendingWhileScrubbing = true
                    break
                }
            }
        }

        // Do the actual seeking.
        if (newPlayingPeriodHolder != null) {
            queue.removeAfter(newPlayingPeriodHolder)
            var hasResetToKeyFrame = true
            if (!newPlayingPeriodHolder.prepared) {
                newPlayingPeriodHolder.info =
                    newPlayingPeriodHolder.info.copyWithStartPositionUs(
                        periodPositionUs, C.TIME_UNSET
                    )
            } else if (newPlayingPeriodHolder.hasEnabledTracks) {
                if (scrubbingModeEnabled
                    && scrubbingModeParameters.allowSkippingKeyFrameReset
                    && shouldSkipKeyFrameReset(newPlayingPeriodHolder, periodPositionUs)
                ) {
                    hasResetToKeyFrame = false
                } else {
                    periodPositionUs = newPlayingPeriodHolder.mediaPeriod.seekToUs(periodPositionUs)
                    newPlayingPeriodHolder.mediaPeriod.discardBuffer(
                        periodPositionUs - backBufferDurationUs, retainBackBufferFromKeyframe
                    )
                }
            }
            resetRendererPosition(
                periodPositionUs, hasResetToKeyFrame
            )
            maybeContinueLoading()
        } else {
            // New period has not been prepared.
            queue.clear()
            resetRendererPosition(periodPositionUs, true)
        }

        handleLoadingMediaPeriodChanged(false)
        handler.sendEmptyMessage(MSG_DO_SOME_WORK)
        return periodPositionUs
    }

    private fun shouldSkipKeyFrameReset(
        playingPeriod: MediaPeriodHolder, periodPositionUs: Long
    ): Boolean {
        if (playbackInfo.timeline.isEmpty || playingPeriod.info.id != playbackInfo.periodId) {
            return false
        }
        val rendererPositionUs = playingPeriod.toRendererTime(periodPositionUs)
        var renderersSupportSkipKeyFrameReset = true
        for (renderer in renderers) {
            if (renderer.isRendererEnabled) {
                renderersSupportSkipKeyFrameReset = renderersSupportSkipKeyFrameReset and
                        renderer.supportsResetPositionWithoutKeyFrameReset(
                            playingPeriod,
                            rendererPositionUs
                        )
            }
        }
        if (!renderersSupportSkipKeyFrameReset) {
            return false
        }
        val adjustedCurrentPositionSyncUs =
            playingPeriod.mediaPeriod.getAdjustedSeekPositionUs(
                playbackInfo.positionUs, SeekParameters.PREVIOUS_SYNC
            )
        val adjustedSeekPositionSyncUs =
            playingPeriod.mediaPeriod.getAdjustedSeekPositionUs(
                periodPositionUs, SeekParameters.PREVIOUS_SYNC
            )
        return adjustedCurrentPositionSyncUs == adjustedSeekPositionSyncUs
    }

    @Throws(ExoPlaybackException::class)
    private fun resetRendererPosition(
        periodPositionUs: Long,
        sampleStreamIsResetToKeyFrame: Boolean
    ) {
        val playingMediaPeriod = queue.playingPeriod
        rendererPositionUs =
            playingMediaPeriod?.toRendererTime(periodPositionUs)
                ?: (MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US + periodPositionUs)
        mediaClock.resetPosition(rendererPositionUs)
        for (rendererHolder in renderers) {
            rendererHolder.resetPosition(
                playingMediaPeriod, rendererPositionUs, sampleStreamIsResetToKeyFrame
            )
        }
        notifyTrackSelectionDiscontinuity()
    }

    @Throws(ExoPlaybackException::class)
    private fun setPlaybackParametersInternal(playbackParameters: PlaybackParameters) {
        setMediaClockPlaybackParameters(playbackParameters)
        handlePlaybackParameters(
            mediaClock.playbackParameters, true
        )
    }

    private fun setSeekParametersInternal(seekParameters: SeekParameters?) {
        this.seekParameters = seekParameters
    }

    @Throws(ExoPlaybackException::class)
    private fun setScrubbingModeEnabledInternal(scrubbingModeEnabled: Boolean) {
        if (!scrubbingModeEnabled) {
            if (queuedSeekWhileScrubbing != null && seekIsPendingWhileScrubbing
                && !handler.hasMessages(MSG_SEEK_COMPLETED_IN_SCRUBBING_MODE)
            ) {
                droppedSeeksWhileScrubbing++
            }
            if (droppedSeeksWhileScrubbing > 0) {
                val localDroppedSeeksCount = droppedSeeksWhileScrubbing
                applicationLooperHandler.post {
                    analyticsCollector.onDroppedSeeksWhileScrubbing(localDroppedSeeksCount)
                }
            }
            droppedSeeksWhileScrubbing = 0
            seekIsPendingWhileScrubbing = false
            handler.removeMessages(MSG_SEEK_COMPLETED_IN_SCRUBBING_MODE)
            if (queuedSeekWhileScrubbing != null) {
                // Immediately seek to the latest received scrub position (interrupting a pending seek).
                seekToInternal(queuedSeekWhileScrubbing!!)
                queuedSeekWhileScrubbing = null
                // Set value to false as subsequent seeks should pre-empt the newly-queued seek.
                seekIsPendingWhileScrubbing = false
            }
        }
        this.scrubbingModeEnabled = scrubbingModeEnabled
        applyScrubbingModeParameters()
    }

    @Throws(ExoPlaybackException::class)
    private fun setScrubbingModeParametersInternal(scrubbingModeParameters: ScrubbingModeParameters) {
        this.scrubbingModeParameters = scrubbingModeParameters
        applyScrubbingModeParameters()
    }

    @Throws(ExoPlaybackException::class)
    private fun applyScrubbingModeParameters() {
        for (renderer in renderers) {
            renderer.setScrubbingMode(if (scrubbingModeEnabled) scrubbingModeParameters else null)
        }
    }

    private fun setForegroundModeInternal(
        foregroundMode: Boolean, processedCondition: ConditionVariable?
    ) {
        if (this.foregroundMode != foregroundMode) {
            this.foregroundMode = foregroundMode
            if (!foregroundMode) {
                for (rendererHolder in renderers) {
                    rendererHolder.reset()
                }
            }
        }
        processedCondition?.open()
    }

    @Throws(ExoPlaybackException::class)
    private fun setVideoOutputInternal(
        videoOutput: Any?, processedCondition: ConditionVariable?
    ) {
        for (renderer in renderers) {
            renderer.setVideoOutput(videoOutput)
        }
        if (playbackInfo.playbackState == Player.STATE_READY
            || playbackInfo.playbackState == Player.STATE_BUFFERING
        ) {
            handler.sendEmptyMessage(MSG_DO_SOME_WORK)
        }
        processedCondition?.open()
    }

    private fun stopInternal(forceResetRenderers: Boolean, acknowledgeStop: Boolean) {
        resetInternal(
            forceResetRenderers || !foregroundMode,
            false,
            true,
            false
        )
        playbackInfoUpdate.incrementPendingOperationAcks(if (acknowledgeStop) 1 else 0)
        loadControl.onStopped(playerId)
        val unused =
            audioFocusManager.updateAudioFocus(playbackInfo.playWhenReady, Player.STATE_IDLE)
        setState(Player.STATE_IDLE)
    }

    private fun releaseInternal(processedCondition: ConditionVariable) {
        try {
            resetInternal(true, false, true, false)
            releaseRenderers()
            loadControl.onReleased(playerId)
            audioFocusManager.release()
            trackSelector.release()
            setState(Player.STATE_IDLE)
        } finally {
            handler.removeCallbacksAndMessages(null)
            playbackLooperProvider.releaseLooper()
            processedCondition.open()
        }
    }

    private fun resetInternal(
        resetRenderers: Boolean,
        resetPosition: Boolean,
        releaseMediaSourceList: Boolean,
        resetError: Boolean
    ) {
        handler.removeMessages(MSG_DO_SOME_WORK)
        seekIsPendingWhileScrubbing = false
        if (queuedSeekWhileScrubbing != null) {
            playbackInfoUpdate.incrementPendingOperationAcks(1)
            queuedSeekWhileScrubbing = null
        }
        pendingRecoverableRendererError = null
        updateRebufferingState(false, true)
        mediaClock.stop()
        rendererPositionUs = MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US
        try {
            disableRenderers()
        } catch (e: RuntimeException) {
            // There's nothing we can do.
            e(TAG, "Disable failed.", e)
        } catch (e: ExoPlaybackException) {
            e(TAG, "Disable failed.", e)
        }
        if (resetRenderers) {
            for (rendererHolder in renderers) {
                try {
                    rendererHolder.reset()
                } catch (e: RuntimeException) {
                    // There's nothing we can do.
                    e(TAG, "Reset failed.", e)
                }
            }
        }
        enabledRendererCount = 0

        var mediaPeriodId = playbackInfo.periodId
        var startPositionUs = playbackInfo.positionUs
        var requestedContentPositionUs =
            if (playbackInfo.periodId.isAd || isUsingPlaceholderPeriod(playbackInfo, period))
                playbackInfo.requestedContentPositionUs
            else
                playbackInfo.positionUs
        var resetTrackInfo = false
        if (resetPosition) {
            pendingInitialSeekPosition = null
            val firstPeriodAndPositionUs =
                getPlaceholderFirstMediaPeriodPositionUs(playbackInfo.timeline)
            mediaPeriodId = firstPeriodAndPositionUs.first
            startPositionUs = firstPeriodAndPositionUs.second!!
            requestedContentPositionUs = C.TIME_UNSET
            if (mediaPeriodId != playbackInfo.periodId) {
                resetTrackInfo = true
            }
        }

        queue.clear()
        shouldContinueLoading = false

        var timeline = playbackInfo.timeline
        if (releaseMediaSourceList && timeline is PlaylistTimeline) {
            // Wrap the current timeline to make sure the current period is marked as a placeholder to
            // force resolving the default start position with the next timeline refresh.
            timeline =
                (playbackInfo.timeline as PlaylistTimeline)
                    .copyWithPlaceholderTimeline(mediaSourceList.shuffleOrder!!)
            if (mediaPeriodId.adGroupIndex != C.INDEX_UNSET) {
                timeline.getPeriodByUid(mediaPeriodId.periodUid, period)
                if (timeline.getWindow(period.windowIndex, window).isLive) {
                    // Drop ad metadata to allow live streams to reset the ad playback state. In case the ad
                    // playback state is not reset by the source, the first timeline refresh after
                    // re-preparation will add the ad metadata to the period again.
                    mediaPeriodId =
                        MediaPeriodId(mediaPeriodId.periodUid, mediaPeriodId.windowSequenceNumber)
                }
            }
        }
        playbackInfo =
            PlaybackInfo(
                timeline,
                mediaPeriodId,
                requestedContentPositionUs,
                startPositionUs,
                playbackInfo.playbackState,
                if (resetError) null else playbackInfo.playbackError,
                false,
                if (resetTrackInfo) TrackGroupArray.EMPTY else playbackInfo.trackGroups,
                if (resetTrackInfo) emptyTrackSelectorResult else playbackInfo.trackSelectorResult,
                if (resetTrackInfo) listOf() else playbackInfo.staticMetadata,
                mediaPeriodId,
                playbackInfo.playWhenReady,
                playbackInfo.playWhenReadyChangeReason,
                playbackInfo.playbackSuppressionReason,
                playbackInfo.playbackParameters,
                startPositionUs,
                0,
                startPositionUs,
                0,
                false
            )
        if (releaseMediaSourceList) {
            queue.releasePreloadPool()
            mediaSourceList.release()
        }
    }

    private fun getPlaceholderFirstMediaPeriodPositionUs(timeline: Timeline): Pair<MediaPeriodId, Long?> {
        if (timeline.isEmpty) {
            return Pair.create<MediaPeriodId, Long?>(
                PlaybackInfo.dummyPeriodForEmptyTimeline,
                0L
            )
        }
        val firstWindowIndex = timeline.getFirstWindowIndex(shuffleModeEnabled)
        val firstPeriodAndPositionUs =
            timeline.getPeriodPositionUs(
                window, period, firstWindowIndex, C.TIME_UNSET
            )
        // Add ad metadata if any and propagate the window sequence number to new period id.
        val firstPeriodId =
            queue.resolveMediaPeriodIdForAdsAfterPeriodPositionChange(
                playbackInfo,
                timeline,
                firstPeriodAndPositionUs.first,
                0,
                true,
                false
            )
        var positionUs: Long = firstPeriodAndPositionUs.second!!
        if (firstPeriodId.isAd) {
            timeline.getPeriodByUid(firstPeriodId.periodUid, period)
            positionUs =
                if (firstPeriodId.adIndexInAdGroup == period.getFirstAdIndexToPlay(firstPeriodId.adGroupIndex))
                    period.adResumePositionUs
                else
                    0
        }
        return Pair.create<MediaPeriodId?, Long?>(firstPeriodId, positionUs)
    }

    @Throws(ExoPlaybackException::class)
    private fun sendMessageInternal(message: PlayerMessage) {
        if (message.positionMs == C.TIME_UNSET) {
            // If no delivery time is specified, trigger immediate message delivery.
            sendMessageToTarget(message)
        } else if (playbackInfo.timeline.isEmpty) {
            // Still waiting for initial timeline to resolve position.
            pendingMessages.add(PendingMessageInfo(message))
        } else {
            val pendingMessageInfo = PendingMessageInfo(message)
            if (resolvePendingMessagePosition(
                    pendingMessageInfo,
                    playbackInfo.timeline,
                    playbackInfo.timeline,
                    repeatMode,
                    shuffleModeEnabled,
                    window,
                    period
                )
            ) {
                pendingMessages.add(pendingMessageInfo)
                // Ensure new message is inserted according to playback order.
                Collections.sort<PendingMessageInfo?>(pendingMessages)
            } else {
                message.markAsProcessed(false)
            }
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun sendMessageToTarget(message: PlayerMessage) {
        if (message.looper == playbackLooper) {
            deliverMessage(message)
            if (playbackInfo.playbackState == Player.STATE_READY
                || playbackInfo.playbackState == Player.STATE_BUFFERING
            ) {
                // The message may have caused something to change that now requires us to do work.
                handler.sendEmptyMessage(MSG_DO_SOME_WORK)
            }
        } else {
            handler.obtainMessage(MSG_SEND_MESSAGE_TO_TARGET_THREAD, message).sendToTarget()
        }
    }

    private fun sendMessageToTargetThread(message: PlayerMessage) {
        val looper = message.looper
        if (!looper.thread.isAlive) {
            w("TAG", "Trying to send message on a dead thread.")
            message.markAsProcessed(false)
            return
        }
        clock.createHandler(looper, null).post {
            try {
                deliverMessage(message)
            } catch (e: ExoPlaybackException) {
                e(TAG, "Unexpected error delivering message on external thread.", e)
                throw RuntimeException(e)
            }
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun deliverMessage(message: PlayerMessage) {
        if (message.isCanceled) {
            return
        }
        try {
            message.target!!.handleMessage(message.type, message.payload)
        } finally {
            message.markAsProcessed(true)
        }
    }

    private fun resolvePendingMessagePositions(newTimeline: Timeline, previousTimeline: Timeline) {
        if (newTimeline.isEmpty && previousTimeline.isEmpty) {
            // Keep all messages unresolved until we have a non-empty timeline.
            return
        }
        for (i in pendingMessages.indices.reversed()) {
            if (!Companion.resolvePendingMessagePosition(
                    pendingMessages[i],
                    newTimeline,
                    previousTimeline,
                    repeatMode,
                    shuffleModeEnabled,
                    window,
                    period
                )
            ) {
                // Unable to resolve a new position for the message. Remove it.
                pendingMessages[i].message.markAsProcessed(false)
                pendingMessages.removeAt(i)
            }
        }
        // Re-sort messages by playback order.
        Collections.sort<PendingMessageInfo?>(pendingMessages)
    }

    @Throws(ExoPlaybackException::class)
    private fun maybeTriggerPendingMessages(oldPeriodPositionUs: Long, newPeriodPositionUs: Long) {
        var oldPeriodPositionUs = oldPeriodPositionUs
        if (pendingMessages.isEmpty() || playbackInfo.periodId.isAd) {
            return
        }
        // If this is the first call after resetting the renderer position, include oldPeriodPositionUs
        // in potential trigger positions, but make sure we deliver it only once.
        if (deliverPendingMessageAtStartPositionRequired) {
            oldPeriodPositionUs--
            deliverPendingMessageAtStartPositionRequired = false
        }

        // Correct next index if necessary (e.g. after seeking, timeline changes, or new messages)
        val currentPeriodIndex =
            playbackInfo.timeline.getIndexOfPeriod(playbackInfo.periodId.periodUid)
        var nextPendingMessageIndex = min(nextPendingMessageIndexHint, pendingMessages.size)
        var previousInfo =
            if (nextPendingMessageIndex > 0) pendingMessages[nextPendingMessageIndex - 1] else null
        while (previousInfo != null
            && (previousInfo.resolvedPeriodIndex > currentPeriodIndex
                    || (previousInfo.resolvedPeriodIndex == currentPeriodIndex
                    && previousInfo.resolvedPeriodTimeUs > oldPeriodPositionUs))
        ) {
            nextPendingMessageIndex--
            previousInfo =
                if (nextPendingMessageIndex > 0) pendingMessages[nextPendingMessageIndex - 1] else null
        }
        var nextInfo =
            if (nextPendingMessageIndex < pendingMessages.size)
                pendingMessages[nextPendingMessageIndex]
            else
                null
        while (nextInfo != null && nextInfo.resolvedPeriodUid != null && (nextInfo.resolvedPeriodIndex < currentPeriodIndex
                    || (nextInfo.resolvedPeriodIndex == currentPeriodIndex
                    && nextInfo.resolvedPeriodTimeUs <= oldPeriodPositionUs))
        ) {
            nextPendingMessageIndex++
            nextInfo =
                if (nextPendingMessageIndex < pendingMessages.size)
                    pendingMessages[nextPendingMessageIndex]
                else
                    null
        }
        // Check if any message falls within the covered time span.
        while (nextInfo != null && nextInfo.resolvedPeriodUid != null && nextInfo.resolvedPeriodIndex == currentPeriodIndex && nextInfo.resolvedPeriodTimeUs > oldPeriodPositionUs && nextInfo.resolvedPeriodTimeUs <= newPeriodPositionUs) {
            try {
                sendMessageToTarget(nextInfo.message)
            } finally {
                if (nextInfo.message.deleteAfterDelivery || nextInfo.message.isCanceled) {
                    pendingMessages.removeAt(nextPendingMessageIndex)
                } else {
                    nextPendingMessageIndex++
                }
            }
            nextInfo =
                if (nextPendingMessageIndex < pendingMessages.size)
                    pendingMessages[nextPendingMessageIndex]
                else
                    null
        }
        nextPendingMessageIndexHint = nextPendingMessageIndex
    }

    @Throws(ExoPlaybackException::class)
    private fun disableRenderers() {
        for (i in renderers.indices) {
            disableRenderer(i)
        }
        prewarmingMediaPeriodDiscontinuity = C.TIME_UNSET
    }

    @Throws(ExoPlaybackException::class)
    private fun disableRenderer(rendererIndex: Int) {
        val enabledRendererCountBeforeDisabling = renderers[rendererIndex].enabledRendererCount
        renderers[rendererIndex].disable(mediaClock)
        maybeTriggerOnRendererReadyChanged(rendererIndex, false)
        enabledRendererCount -= enabledRendererCountBeforeDisabling
    }

    private fun disableAndResetPrewarmingRenderers() {
        if (!hasSecondaryRenderers || !areRenderersPrewarming()) {
            return
        }
        for (renderer in renderers) {
            val enabledRendererCountBeforeDisabling = renderer.enabledRendererCount
            renderer.disablePrewarming(mediaClock)
            enabledRendererCount -=
                enabledRendererCountBeforeDisabling - renderer.enabledRendererCount
        }
        prewarmingMediaPeriodDiscontinuity = C.TIME_UNSET
    }

    private fun isRendererPrewarmingMediaPeriod(
        rendererIndex: Int,
        mediaPeriodId: MediaPeriodId?
    ): Boolean {
        if (queue.getPrewarmingPeriod(rendererIndex) == null
            || queue.getPrewarmingPeriod(rendererIndex)!!.info.id != mediaPeriodId
        ) {
            return false
        }
        return renderers[rendererIndex].isPrewarmingPeriod(queue.getPrewarmingPeriod(rendererIndex))
    }

    @Throws(ExoPlaybackException::class)
    private fun reselectTracksInternalAndSeek() {
        reselectTracksInternal()
        seekToCurrentPosition(true)
    }

    @Throws(ExoPlaybackException::class)
    private fun reselectTracksInternal() {
        val playbackSpeed = mediaClock.playbackParameters.speed
        // Reselect tracks on each period in turn, until the selection changes.
        var periodHolder = queue.playingPeriod
        val readingPeriodHolder = queue.latestReadingPeriod
        var selectionsChangedForReadPeriod = true
        var newTrackSelectorResult: TrackSelectorResult
        // Keep playing period result in case of track selection change for reading period only.
        var newPlayingPeriodTrackSelectorResult: TrackSelectorResult? = null
        while (true) {
            if (periodHolder == null || !periodHolder.prepared) {
                // The reselection did not change any prepared periods.
                return
            }
            newTrackSelectorResult =
                periodHolder.selectTracks(
                    playbackSpeed, playbackInfo.timeline, playbackInfo.playWhenReady
                )
            if (periodHolder == queue.playingPeriod) {
                newPlayingPeriodTrackSelectorResult = newTrackSelectorResult
            }
            if (!newTrackSelectorResult.isEquivalent(periodHolder.trackSelectorResult)) {
                // Selected tracks have changed for this period.
                break
            }
            if (periodHolder == readingPeriodHolder) {
                // The track reselection didn't affect any period that has been read.
                selectionsChangedForReadPeriod = false
            }
            periodHolder = periodHolder.getNext()
        }

        if (selectionsChangedForReadPeriod) {
            // Update streams and rebuffer for the new selection, recreating all streams if reading ahead.
            val playingPeriodHolder = queue.playingPeriod
            val removeAfterResult: @UpdatePeriodQueueResult Int =
                queue.removeAfter(playingPeriodHolder)
            val recreateStreams =
                (removeAfterResult and MediaPeriodQueue.UPDATE_PERIOD_QUEUE_ALTERED_READING_PERIOD) != 0

            val streamResetFlags = BooleanArray(renderers.size)
            val periodPositionUs =
                playingPeriodHolder!!.applyTrackSelection(
                    newPlayingPeriodTrackSelectorResult!!,
                    playbackInfo.positionUs,
                    recreateStreams,
                    streamResetFlags
                )
            val hasDiscontinuity =
                playbackInfo.playbackState != Player.STATE_ENDED
                        && periodPositionUs != playbackInfo.positionUs
            playbackInfo =
                handlePositionDiscontinuity(
                    playbackInfo.periodId,
                    periodPositionUs,
                    playbackInfo.requestedContentPositionUs,
                    playbackInfo.discontinuityStartPositionUs,
                    hasDiscontinuity,
                    Player.DISCONTINUITY_REASON_INTERNAL
                )
            if (hasDiscontinuity) {
                resetRendererPosition(periodPositionUs, true)
            }

            // Disable pre-warming renderers.
            disableAndResetPrewarmingRenderers()

            val rendererWasEnabledFlags = BooleanArray(renderers.size)
            for (i in renderers.indices) {
                val enabledRendererCountBeforeDisabling = renderers[i].enabledRendererCount
                rendererWasEnabledFlags[i] = renderers[i].isRendererEnabled

                renderers[i].maybeDisableOrResetPosition(
                    playingPeriodHolder.sampleStreams[i],
                    mediaClock,
                    rendererPositionUs,
                    streamResetFlags[i]
                )
                if (enabledRendererCountBeforeDisabling - renderers[i].enabledRendererCount > 0) {
                    maybeTriggerOnRendererReadyChanged(i, false)
                }
                enabledRendererCount -=
                    enabledRendererCountBeforeDisabling - renderers[i].enabledRendererCount
            }
            enableRenderers(rendererWasEnabledFlags, rendererPositionUs)
            setAllRenderersToCorrectState(playingPeriodHolder)
        } else {
            // Release and re-prepare/buffer periods after the one whose selection changed.
            queue.removeAfter(periodHolder)
            if (periodHolder.prepared) {
                val loadingPeriodPositionUs =
                    max(
                        periodHolder.info.startPositionUs,
                        periodHolder.toPeriodTime(rendererPositionUs)
                    )
                if (hasSecondaryRenderers) {
                    for (i in renderers.indices) {
                        if (queue.getPrewarmingPeriod(i) == periodHolder
                            && renderers[i].isPrewarmingPeriod(periodHolder)
                        ) {
                            // If renderers are enabled early and track reselection is on the enabled-early period
                            // then there is a need to disable those renderers. Must be done prior to call to
                            // applyTrackSelection.
                            disableAndResetPrewarmingRenderers()
                            break
                        }
                    }
                }
                periodHolder.applyTrackSelection(
                    newTrackSelectorResult,
                    loadingPeriodPositionUs,
                    false
                )
            }
        }
        handleLoadingMediaPeriodChanged(true)
        if (playbackInfo.playbackState != Player.STATE_ENDED) {
            maybeContinueLoading()
            updatePlaybackPositions()
            handler.sendEmptyMessage(MSG_DO_SOME_WORK)
        }
    }

    private fun updateTrackSelectionPlaybackSpeed(playbackSpeed: Float) {
        var periodHolder = queue.playingPeriod
        while (periodHolder != null) {
            for (trackSelection in periodHolder.trackSelectorResult.selections) {
                trackSelection?.onPlaybackSpeed(playbackSpeed)
            }
            periodHolder = periodHolder.getNext()
        }
    }

    private fun notifyTrackSelectionDiscontinuity() {
        var periodHolder = queue.playingPeriod
        while (periodHolder != null) {
            for (trackSelection in periodHolder.trackSelectorResult.selections) {
                trackSelection?.onDiscontinuity()
            }
            periodHolder = periodHolder.getNext()
        }
    }

    private fun shouldTransitionToReadyState(renderersReadyOrEnded: Boolean): Boolean {
        if (enabledRendererCount == 0) {
            // If there are no enabled renderers, determine whether we're ready based on the timeline.
            return this.isTimelineReady
        }
        if (!renderersReadyOrEnded) {
            return false
        }
        if (!playbackInfo.isLoading) {
            // Renderers are ready and we're not loading. Transition to ready, since the alternative is
            // getting stuck waiting for additional media that's not being loaded.
            return true
        }
        // Renderers are ready and we're loading. Ask the LoadControl whether to transition.
        val playingPeriodHolder = queue.playingPeriod
        val targetLiveOffsetUs =
            if (shouldUseLivePlaybackSpeedControl(
                    playbackInfo.timeline,
                    playingPeriodHolder!!.info.id
                )
            )
                livePlaybackSpeedControl.targetLiveOffsetUs
            else
                C.TIME_UNSET
        val loadingHolder = queue.loadingPeriod
        val isBufferedToEnd = loadingHolder!!.isFullyBuffered && loadingHolder.info.isFinal
        // Ad loader implementations may only load ad media once playback has nearly reached the ad, but
        // it is possible for playback to be stuck buffering waiting for this. Therefore, we start
        // playback regardless of buffered duration if we are waiting for an ad media period to prepare.
        val isAdPendingPreparation = loadingHolder.info.id.isAd && !loadingHolder.prepared
        if (isBufferedToEnd || isAdPendingPreparation) {
            return true
        }
        // Get updated buffered duration as it may have changed since the start of the renderer loop.
        val bufferedDurationUs = getTotalBufferedDurationUs(loadingHolder.bufferedPositionUs)
        return loadControl.shouldStartPlayback(
            LoadControl.Parameters(
                playerId,
                playbackInfo.timeline,
                playingPeriodHolder.info.id,
                playingPeriodHolder.toPeriodTime(rendererPositionUs),
                bufferedDurationUs,
                mediaClock.playbackParameters.speed,
                playbackInfo.playWhenReady,
                isRebuffering,
                targetLiveOffsetUs,
                lastRebufferRealtimeMs
            )
        )
    }

    private val isTimelineReady: Boolean
        get() {
            val playingPeriodHolder = queue.playingPeriod
            val playingPeriodDurationUs = playingPeriodHolder!!.info.durationUs
            return playingPeriodHolder.prepared
                    && (playingPeriodDurationUs == C.TIME_UNSET || playbackInfo.positionUs < playingPeriodDurationUs || !shouldPlayWhenReady())
        }

    @Throws(ExoPlaybackException::class)
    private fun handleMediaSourceListInfoRefreshed(timeline: Timeline, isSourceRefresh: Boolean) {
        val positionUpdate: PositionUpdateForPlaylistChange =
            resolvePositionForPlaylistChange(
                timeline,
                playbackInfo,
                pendingInitialSeekPosition,
                queue,
                repeatMode,
                shuffleModeEnabled,
                isSourceRefresh,
                window,
                period,
                enforceAdPlaybackOnTimelineRefresh
            )
        val newPeriodId = positionUpdate.periodId
        var newPositionUs = positionUpdate.periodPositionUs
        try {
            if (positionUpdate.endPlayback) {
                if (playbackInfo.playbackState != Player.STATE_IDLE) {
                    setState(Player.STATE_ENDED)
                }
                resetInternal(false, false, false, true)
            }
            for (rendererHolder in renderers) {
                rendererHolder.setTimeline(timeline)
            }
            if (!positionUpdate.periodPositionChanged) {
                // We can keep the current playing period. Update the rest of the queued periods.
                val rendererReadingPositionsUs = LongArray(renderers.size)
                for (i in renderers.indices) {
                    rendererReadingPositionsUs[i] =
                        getRendererReadingPositionUs(
                            queue.getReadingPeriod(i),
                            i
                        )
                }
                val rendererPrewarmingPositionsUs = LongArray(renderers.size)
                for (i in renderers.indices) {
                    rendererPrewarmingPositionsUs[i] =
                        getRendererReadingPositionUs(
                            queue.getPrewarmingPeriod(i),
                            i
                        )
                }
                val updateQueuedPeriodsResult: @UpdatePeriodQueueResult Int =
                    queue.updateQueuedPeriods(
                        timeline,
                        rendererPositionUs,
                        rendererReadingPositionsUs,
                        rendererPrewarmingPositionsUs
                    )
                if ((updateQueuedPeriodsResult and MediaPeriodQueue.UPDATE_PERIOD_QUEUE_ALTERED_READING_PERIOD) != 0) {
                    seekToCurrentPosition(false)
                } else if ((updateQueuedPeriodsResult and MediaPeriodQueue.UPDATE_PERIOD_QUEUE_ALTERED_PREWARMING_PERIOD)
                    != 0
                ) {
                    disableAndResetPrewarmingRenderers()
                }
            } else if (!timeline.isEmpty) {
                // Something changed. Seek to new start position.
                var periodHolder = queue.playingPeriod
                while (periodHolder != null) {
                    // Update the new playing media period info if it already exists.
                    if (periodHolder.info.id == newPeriodId) {
                        periodHolder.info =
                            queue.getUpdatedMediaPeriodInfo(timeline, periodHolder.info)
                    }
                    periodHolder = periodHolder.getNext()
                }
                newPositionUs =
                    seekToPeriodPosition(
                        newPeriodId,
                        newPositionUs,
                        positionUpdate.forceBufferingState
                    )
            }
        } finally {
            updatePlaybackSpeedSettingsForNewPeriod(
                timeline,
                newPeriodId,
                playbackInfo.timeline,
                playbackInfo.periodId,
                if (positionUpdate.setTargetLiveOffset)
                    newPositionUs
                else
                    C.TIME_UNSET,
                false
            )
            if (positionUpdate.periodPositionChanged
                || positionUpdate.requestedContentPositionUs != playbackInfo.requestedContentPositionUs
            ) {
                playbackInfo =
                    handlePositionDiscontinuity(
                        newPeriodId,
                        newPositionUs,
                        positionUpdate.requestedContentPositionUs,
                        if (positionUpdate.reportDiscontinuity)
                            newPositionUs
                        else
                            playbackInfo.discontinuityStartPositionUs,
                        positionUpdate.reportDiscontinuity,
                        positionUpdate.discontinuityReason
                    )
            }
            resetPendingPauseAtEndOfPeriod()
            resolvePendingMessagePositions(
                timeline, playbackInfo.timeline
            )
            playbackInfo = playbackInfo.copyWithTimeline(timeline)
            if (!timeline.isEmpty) {
                // Retain pending seek position only while the timeline is still empty.
                pendingInitialSeekPosition = null
            }
            handleLoadingMediaPeriodChanged(false)
            handler.sendEmptyMessage(MSG_DO_SOME_WORK)
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun updatePlaybackSpeedSettingsForNewPeriod(
        newTimeline: Timeline,
        newPeriodId: MediaPeriodId,
        oldTimeline: Timeline,
        oldPeriodId: MediaPeriodId,
        positionForTargetOffsetOverrideUs: Long,
        forceSetTargetOffsetOverride: Boolean
    ) {
        if (!shouldUseLivePlaybackSpeedControl(newTimeline, newPeriodId)) {
            // Live playback speed control is unused for the current period, reset speed to user-defined
            // playback parameters or 1.0 for ad playback.
            val targetPlaybackParameters =
                if (newPeriodId.isAd) PlaybackParameters.DEFAULT else playbackInfo.playbackParameters
            if (mediaClock.playbackParameters != targetPlaybackParameters) {
                setMediaClockPlaybackParameters(targetPlaybackParameters)
                handlePlaybackParameters(
                    playbackInfo.playbackParameters,
                    targetPlaybackParameters.speed,
                    false,
                    false
                )
            }
            return
        }
        val windowIndex = newTimeline.getPeriodByUid(newPeriodId.periodUid, period).windowIndex
        newTimeline.getWindow(windowIndex, window)
        livePlaybackSpeedControl.setLiveConfiguration(window.liveConfiguration!!)
        if (positionForTargetOffsetOverrideUs != C.TIME_UNSET) {
            livePlaybackSpeedControl.setTargetLiveOffsetOverrideUs(
                getLiveOffsetUs(
                    newTimeline,
                    newPeriodId.periodUid,
                    positionForTargetOffsetOverrideUs
                )
            )
        } else {
            val windowUid = window.uid
            var oldWindowUid: Any? = null
            if (!oldTimeline.isEmpty) {
                val oldWindowIndex =
                    oldTimeline.getPeriodByUid(oldPeriodId.periodUid, period).windowIndex
                oldWindowUid = oldTimeline.getWindow(oldWindowIndex, window).uid
            }
            if (oldWindowUid != windowUid || forceSetTargetOffsetOverride) {
                // Reset overridden target live offset to media values if window changes or if seekTo
                // default live position.
                livePlaybackSpeedControl.setTargetLiveOffsetOverrideUs(C.TIME_UNSET)
            }
        }
    }

    private fun getRendererReadingPositionUs(
        periodHolder: MediaPeriodHolder?, rendererIndex: Int
    ): Long {
        if (periodHolder == null) {
            return 0
        }
        if (!periodHolder.prepared || !renderers[rendererIndex].isReadingFromPeriod(periodHolder)) {
            return periodHolder.rendererOffset
        }
        return renderers[rendererIndex].getReadingPositionUs(periodHolder)
    }

    @Throws(ExoPlaybackException::class)
    private fun updatePeriods() {
        if (playbackInfo.timeline.isEmpty || !mediaSourceList.isPrepared) {
            // No periods available.
            return
        }
        val loadingPeriodChanged = maybeUpdateLoadingPeriod()
        if (!perStreamMediaProgressionEnabled) {
            maybeUpdatePrewarmingPeriod()
            maybeUpdateReadingPeriod()
            maybeUpdateReadingRenderers()
        } else {
            maybeUpdatePrewarmingPeriods()
            maybeUpdateReadingPeriods()
            maybeUpdateReadingRenderersPerStream()
        }

        maybeUpdatePlayingPeriod()
        maybeUpdatePreloadPeriods(loadingPeriodChanged)
    }

    @Throws(ExoPlaybackException::class)
    private fun maybeUpdateLoadingPeriod(): Boolean {
        var loadingPeriodChanged = false
        queue.reevaluateBuffer(rendererPositionUs)
        if (queue.shouldLoadNextMediaPeriod()) {
            val info = queue.getNextMediaPeriodInfo(rendererPositionUs, playbackInfo)
            if (info != null) {
                val mediaPeriodHolder = queue.enqueueNextMediaPeriodHolder(info)!!
                if (!mediaPeriodHolder.prepareCalled) {
                    mediaPeriodHolder.prepare(this, info.startPositionUs)
                } else if (mediaPeriodHolder.prepared) {
                    handler.obtainMessage(MSG_PERIOD_PREPARED, mediaPeriodHolder.mediaPeriod)
                        .sendToTarget()
                }
                if (queue.playingPeriod == mediaPeriodHolder) {
                    resetRendererPosition(
                        info.startPositionUs,
                        true
                    )
                }
                handleLoadingMediaPeriodChanged(false)
                loadingPeriodChanged = true
            }
        }
        if (shouldContinueLoading) {
            // We should still be loading, except when there is nothing to load or we have fully loaded
            // the current period.
            shouldContinueLoading = isLoadingPossible(queue.loadingPeriod)
            updateisLoading()
        } else {
            maybeContinueLoading()
        }
        return loadingPeriodChanged
    }

    @Throws(ExoPlaybackException::class)
    private fun maybeUpdatePrewarmingPeriods() {
        if (pendingPauseAtEndOfPeriod
            || !hasSecondaryRenderers || isPrewarmingDisabledUntilNextTransition
        ) {
            return
        }
        for (i in renderers.indices) {
            if (!renderers[i].hasSecondary()) {
                continue
            }
            maybeUpdatePrewarmingPeriod(i)
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun maybeUpdatePrewarmingPeriod() {
        if (pendingPauseAtEndOfPeriod
            || !hasSecondaryRenderers || isPrewarmingDisabledUntilNextTransition
            || areRenderersPrewarming()
        ) {
            return
        }
        val prewarmingPeriodHolder = queue.earliestPrewarmingPeriod
        if (prewarmingPeriodHolder == null || (prewarmingPeriodHolder != queue.earliestReadingPeriod) || prewarmingPeriodHolder.getNext() == null || !prewarmingPeriodHolder.getNext()!!.prepared) {
            return
        }

        // Only start pre-warming if under the threshold to advance the reading period.
        val durationToNextMediaPeriodUs =
            getDurationToMediaPeriodUs(prewarmingPeriodHolder.getNext()!!)
        if (durationToNextMediaPeriodUs > DURATION_TO_ADVANCE_READING_THRESHOLD_US) {
            return
        }

        queue.advancePrewarmingPeriod()
        maybePrewarmRenderers()
    }

    @Throws(ExoPlaybackException::class)
    private fun maybeUpdatePrewarmingPeriod(index: Int) {
        if (renderers[index].isPrewarming) {
            return
        }
        val prewarmingPeriodHolder = queue.getPrewarmingPeriod(index)
        if (prewarmingPeriodHolder == null || (prewarmingPeriodHolder != queue.getReadingPeriod(
                index
            )) || prewarmingPeriodHolder.getNext() == null || !prewarmingPeriodHolder.getNext()!!.prepared
        ) {
            return
        }

        // Only start pre-warming if under the threshold to advance the reading period.
        val durationToNextMediaPeriodUs =
            getDurationToMediaPeriodUs(prewarmingPeriodHolder.getNext()!!)
        if (durationToNextMediaPeriodUs > DURATION_TO_ADVANCE_READING_THRESHOLD_US) {
            return
        }

        queue.advancePrewarmingPeriod(index)
        maybePrewarmRenderer(index)
    }

    @Throws(ExoPlaybackException::class)
    private fun maybePrewarmRenderers() {
        val prewarmingPeriod = queue.earliestPrewarmingPeriod ?: return
        val trackSelectorResult = prewarmingPeriod.trackSelectorResult
        for (i in renderers.indices) {
            if (trackSelectorResult.isRendererEnabled(i)
                && renderers[i].hasSecondary()
                && !renderers[i].isPrewarming
            ) {
                renderers[i].startPrewarming()
                enableRenderer(
                    prewarmingPeriod,
                    i,
                    false,
                    prewarmingPeriod.startPositionRendererTime
                )
            }
        }
        // Handle any media period discontinuities.
        if (areRenderersPrewarming()) {
            prewarmingMediaPeriodDiscontinuity = prewarmingPeriod.mediaPeriod.readDiscontinuity()
            if (!prewarmingPeriod.isFullyBuffered) {
                // The discontinuity caused the period to not be fully buffered. Continue loading from
                // this period again and discard all other periods we already started loading.
                queue.removeAfter(prewarmingPeriod)
                handleLoadingMediaPeriodChanged(false)
                maybeContinueLoading()
            }
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun maybePrewarmRenderer(index: Int) {
        val prewarmingPeriod = queue.getPrewarmingPeriod(index) ?: return
        val trackSelectorResult = prewarmingPeriod.trackSelectorResult
        if (trackSelectorResult.isRendererEnabled(index)
            && renderers[index].hasSecondary()
            && !renderers[index].isPrewarming
        ) {
            renderers[index].startPrewarming()
            enableRenderer(
                prewarmingPeriod,
                index,
                false,
                prewarmingPeriod.startPositionRendererTime
            )
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun maybeUpdateReadingPeriods() {
        for (i in renderers.indices) {
            maybeUpdateReadingPeriod(i)
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun maybeUpdateReadingPeriod(index: Int) {
        var readingPeriodHolder = queue.getReadingPeriod(index)
        val renderer = renderers[index]
        if (readingPeriodHolder == null || !readingPeriodHolder.prepared) {
            return
        }

        if (readingPeriodHolder.getNext() == null || pendingPauseAtEndOfPeriod) {
            // We don't have a successor to advance the reading period to or we want to let them end
            // intentionally to pause at the end of the period.
            if (readingPeriodHolder.info.isFinal || pendingPauseAtEndOfPeriod) {
                if (renderer.isReadingFromPeriod(readingPeriodHolder)) {
                    // Defer setting the stream as final until the renderer has actually consumed the whole
                    // stream in case of playlist changes that cause the stream to be no longer final.
                    if (renderer.hasReadPeriodToEnd(readingPeriodHolder)) {
                        val streamEndPositionUs =
                            if (readingPeriodHolder.info.durationUs != C.TIME_UNSET
                                && readingPeriodHolder.info.durationUs != C.TIME_END_OF_SOURCE
                            )
                                readingPeriodHolder.rendererOffset + readingPeriodHolder.info.durationUs
                            else
                                C.TIME_UNSET
                        renderer.setCurrentStreamFinal(readingPeriodHolder, streamEndPositionUs)
                    }
                }
            }
            return
        }

        if (!isRendererPrewarming(index) && !renderer.isReadingFromPeriod(readingPeriodHolder) && !haveAllRenderersFinishedReading(
                readingPeriodHolder
            )
        ) {
            // If renderer is not reading from current period nor prewarming then don't advance reading
            // period unless all renderers have finished reading from the current period.
            return
        }

        if (!renderers[index].hasFinishedReadingFromPeriod(readingPeriodHolder)) {
            return
        }

        if (isRendererPrewarming(index)
            && queue.getPrewarmingPeriod(index) == queue.getReadingPeriod(index)
        ) {
            // Reading period has already advanced to pre-warming period.
            return
        }

        if (!readingPeriodHolder.getNext()!!.prepared) {
            if (rendererPositionUs < readingPeriodHolder.getNext()!!
                    .startPositionRendererTime
            ) {
                // The successor is not prepared yet and playback hasn't reached the transition point.
                return
            }
            if (!haveAllRenderersFinishedReading(readingPeriodHolder)) {
                // If next period is not prepared and not all renderers have finished reading current period
                // then don't progress.
                return
            }
        }

        if (!haveAllRenderersFinishedReading(readingPeriodHolder)
            && !isSafeToAdvancePerStream(readingPeriodHolder)
        ) {
            return
        }

        if (readingPeriodHolder.getNext()!!.prepared
            && (getDurationToMediaPeriodUs(readingPeriodHolder.getNext()!!)
                    > DURATION_TO_ADVANCE_READING_THRESHOLD_US)
        ) {
            return
        }

        val wasOldReadingPeriodLatest =
            readingPeriodHolder == queue.latestReadingPeriod
        val oldReadingPeriodHolder: MediaPeriodHolder? = readingPeriodHolder
        val oldTrackSelectorResult = readingPeriodHolder.trackSelectorResult
        readingPeriodHolder = queue.advanceReadingPeriod(index)
        val newTrackSelectorResult = readingPeriodHolder.trackSelectorResult

        if (wasOldReadingPeriodLatest) {
            // Only update the playback speed settings if advancing to reading period for its first time.
            updatePlaybackSpeedSettingsForNewPeriod(
                playbackInfo.timeline,
                readingPeriodHolder.info.id,
                playbackInfo.timeline,
                oldReadingPeriodHolder!!.info.id,
                C.TIME_UNSET,
                false
            )
        }

        renderer.maybeSetOldStreamToFinal(
            oldTrackSelectorResult,
            newTrackSelectorResult,
            readingPeriodHolder.startPositionRendererTime
        )
    }

    @Throws(ExoPlaybackException::class)
    private fun maybeUpdateReadingPeriod() {
        var readingPeriodHolder = queue.earliestReadingPeriod ?: return

        if (readingPeriodHolder.getNext() == null || pendingPauseAtEndOfPeriod) {
            // We don't have a successor to advance the reading period to or we want to let them end
            // intentionally to pause at the end of the period.
            if (readingPeriodHolder.info.isFinal || pendingPauseAtEndOfPeriod) {
                for (renderer in renderers) {
                    if (!renderer.isReadingFromPeriod(readingPeriodHolder)) {
                        continue
                    }
                    // Defer setting the stream as final until the renderer has actually consumed the whole
                    // stream in case of playlist changes that cause the stream to be no longer final.
                    if (renderer.hasReadPeriodToEnd(readingPeriodHolder)) {
                        val streamEndPositionUs =
                            if (readingPeriodHolder.info.durationUs != C.TIME_UNSET
                                && readingPeriodHolder.info.durationUs != C.TIME_END_OF_SOURCE
                            )
                                readingPeriodHolder.rendererOffset + readingPeriodHolder.info.durationUs
                            else
                                C.TIME_UNSET
                        renderer.setCurrentStreamFinal(readingPeriodHolder, streamEndPositionUs)
                    }
                }
            }
            return
        }

        if (!haveAllRenderersFinishedReading(queue.earliestReadingPeriod!!)) {
            return
        }

        if (areRenderersPrewarming()
            && queue.earliestPrewarmingPeriod == queue.earliestReadingPeriod
        ) {
            // Reading period has already advanced to pre-warming period.
            return
        }

        if (!readingPeriodHolder.getNext()!!.prepared
            && rendererPositionUs < readingPeriodHolder.getNext()!!.startPositionRendererTime
        ) {
            // The successor is not prepared yet and playback hasn't reached the transition point.
            return
        }

        if (readingPeriodHolder.getNext()!!.prepared
            && (getDurationToMediaPeriodUs(readingPeriodHolder.getNext()!!)
                    > DURATION_TO_ADVANCE_READING_THRESHOLD_US)
        ) {
            return
        }

        val oldReadingPeriodHolder: MediaPeriodHolder? = readingPeriodHolder
        val oldTrackSelectorResult = readingPeriodHolder.trackSelectorResult
        readingPeriodHolder = queue.advanceReadingPeriod()
        val newTrackSelectorResult = readingPeriodHolder.trackSelectorResult

        updatePlaybackSpeedSettingsForNewPeriod(
            playbackInfo.timeline,
            readingPeriodHolder.info.id,
            playbackInfo.timeline,
            oldReadingPeriodHolder!!.info.id,
            C.TIME_UNSET,
            false
        )

        if (readingPeriodHolder.prepared
            && ((hasSecondaryRenderers && prewarmingMediaPeriodDiscontinuity != C.TIME_UNSET)
                    || readingPeriodHolder.mediaPeriod.readDiscontinuity() != C.TIME_UNSET)
        ) {
            prewarmingMediaPeriodDiscontinuity = C.TIME_UNSET
            // The new period starts with a discontinuity, so unless a pre-warming renderer is handling
            // the discontinuity, the renderers will play out all data, then
            // be disabled and re-enabled when they start playing the next period.
            var arePrewarmingRenderersHandlingDiscontinuity =
                hasSecondaryRenderers && !isPrewarmingDisabledUntilNextTransition
            if (arePrewarmingRenderersHandlingDiscontinuity) {
                for (i in renderers.indices) {
                    if (!newTrackSelectorResult.isRendererEnabled(i)
                        || renderers[i].trackType == C.TRACK_TYPE_NONE
                    ) {
                        continue
                    }
                    if (!allSamplesAreSyncSamples(
                            newTrackSelectorResult.selections[i]!!.selectedFormat.sampleMimeType,
                            newTrackSelectorResult.selections[i]!!.selectedFormat.codecs
                        )
                        && !renderers[i].isPrewarming
                    ) {
                        arePrewarmingRenderersHandlingDiscontinuity = false
                        break
                    }
                }
            }
            if (!arePrewarmingRenderersHandlingDiscontinuity) {
                setAllNonPrewarmingRendererStreamsFinal(
                    readingPeriodHolder.startPositionRendererTime
                )
                if (!readingPeriodHolder.isFullyBuffered) {
                    // The discontinuity caused the period to not be fully buffered. Continue loading from
                    // this period again and discard all other periods we already started loading.
                    queue.removeAfter(readingPeriodHolder)
                    handleLoadingMediaPeriodChanged(false)
                    maybeContinueLoading()
                }
                return
            }
        }

        for (renderer in renderers) {
            renderer.maybeSetOldStreamToFinal(
                oldTrackSelectorResult,
                newTrackSelectorResult,
                readingPeriodHolder.startPositionRendererTime
            )
        }
    }

    private fun isSafeToAdvancePerStream(current: MediaPeriodHolder): Boolean {
        val next = current.getNext()!!
        if (current.info.id.isAd != next.info.id.isAd) {
            return false
        }

        // Check for live stream boundaries.
        val window = Timeline.Window()

        val currentWindowIndex =
            playbackInfo.timeline.getPeriodByUid(current.info.id.periodUid, period).windowIndex
        playbackInfo.timeline.getWindow(currentWindowIndex, window)
        val currentIsLive = window.isLive

        val nextWindowIndex =
            playbackInfo.timeline.getPeriodByUid(next.info.id.periodUid, period).windowIndex
        playbackInfo.timeline.getWindow(nextWindowIndex, window)
        val nextIsLive = window.isLive

        // Transition involves a live stream; fallback to synchronized reading.
        return !currentIsLive && !nextIsLive
    }

    @Throws(ExoPlaybackException::class)
    private fun maybeUpdateReadingRenderers() {
        val readingPeriod = queue.earliestReadingPeriod
        if (readingPeriod == null || queue.playingPeriod == readingPeriod || areAllRenderersInCorrectState(
                readingPeriod
            )
        ) {
            // Not reading ahead or all renderers updated.
            return
        }
        val allUpdated = updateRenderersForTransition()
        if (allUpdated) {
            // Only if all streams for reading period has all renderers in correct state then its true
            setAllRenderersToCorrectState(queue.earliestReadingPeriod!!)
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun maybeUpdateReadingRenderersPerStream() {
        for (i in renderers.indices) {
            val readingPeriod = queue.getReadingPeriod(i)
            if (readingPeriod == null || queue.playingPeriod == readingPeriod
                || readingPeriod.isRendererInCorrectState(i)
            ) {
                // Not reading ahead or all renderers updated.
                continue
            }

            if (updateRendererForTransition(i)) {
                // Only if all streams for reading period has all renderers in correct state then its true
                queue.getReadingPeriod(i)!!.setRendererToCorrectState(i)
            }
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun updateRenderersForTransition(): Boolean {
        val readingMediaPeriod = queue.earliestReadingPeriod
        val newTrackSelectorResult = readingMediaPeriod!!.trackSelectorResult
        var allUpdated = true
        for (renderer in renderers) {
            val enabledRendererCountPreTransition = renderer.enabledRendererCount
            val result =
                renderer.replaceStreamsOrDisableRendererForTransition(
                    readingMediaPeriod, newTrackSelectorResult, mediaClock
                )
            if ((result and RendererHolder.REPLACE_STREAMS_DISABLE_RENDERERS_DISABLE_OFFLOAD_SCHEDULING) != 0
                && offloadSchedulingEnabled
            ) {
                // Prevent sleeping across offload track transition else position won't get updated.
                setOffloadSchedulingEnabled(false)
            }
            enabledRendererCount -=
                enabledRendererCountPreTransition - renderer.enabledRendererCount

            val completedUpdate =
                (result and RendererHolder.REPLACE_STREAMS_DISABLE_RENDERERS_COMPLETED) != 0
            allUpdated = allUpdated and completedUpdate
        }
        if (allUpdated) {
            for (i in renderers.indices) {
                if (newTrackSelectorResult.isRendererEnabled(i)
                    && !renderers[i].isReadingFromPeriod(readingMediaPeriod)
                ) {
                    enableRenderer(
                        readingMediaPeriod,
                        i,
                        false,
                        readingMediaPeriod.startPositionRendererTime
                    )
                }
            }
        }
        return allUpdated
    }

    @Throws(ExoPlaybackException::class)
    private fun updateRendererForTransition(index: Int): Boolean {
        val renderer = renderers[index]
        val readingMediaPeriod = queue.getReadingPeriod(index)
        val newTrackSelectorResult = readingMediaPeriod!!.trackSelectorResult

        if (readingMediaPeriod.prepared
            && newTrackSelectorResult.isRendererEnabled(index)
            && renderer.isRendererEnabled
            && !isRendererPrewarming(index) && !renderer.isReadingFromPeriod(readingMediaPeriod)
        ) {
            val flags: @SampleStream.Flags Int = readingMediaPeriod.sampleStreams[index]!!.flags
            if ((flags and SampleStream.FLAG_MAYBE_HAS_PREROLL) !== 0) {
                return false
            } else if ((flags and SampleStream.FLAG_HAS_PREROLL) !== 0) {
                renderer.setCurrentStreamFinal(
                    readingMediaPeriod.startPositionRendererTime
                )
            }
        }

        val enabledRendererCountPreTransition = renderer.enabledRendererCount
        val result =
            renderer.replaceStreamsOrDisableRendererForTransition(
                readingMediaPeriod, newTrackSelectorResult, mediaClock
            )
        if ((result and RendererHolder.REPLACE_STREAMS_DISABLE_RENDERERS_DISABLE_OFFLOAD_SCHEDULING) != 0
            && offloadSchedulingEnabled
        ) {
            // Prevent sleeping across offload track transition else position won't get updated.
            setOffloadSchedulingEnabled(false)
        }
        enabledRendererCount -= enabledRendererCountPreTransition - renderer.enabledRendererCount

        val replaceAndDisableCompleted =
            (result and RendererHolder.REPLACE_STREAMS_DISABLE_RENDERERS_COMPLETED) != 0
        if (replaceAndDisableCompleted) {
            if (newTrackSelectorResult.isRendererEnabled(index)
                && !renderer.isReadingFromPeriod(readingMediaPeriod)
            ) {
                enableRenderer(
                    readingMediaPeriod,
                    index,
                    false,
                    readingMediaPeriod.startPositionRendererTime
                )
            }
        }
        return replaceAndDisableCompleted
    }

    private fun maybeUpdatePreloadPeriods(loadingPeriodChanged: Boolean) {
        if (preloadConfiguration.targetPreloadDurationUs == C.TIME_UNSET) {
            // Do nothing if preloading disabled.
            return
        }
        if (loadingPeriodChanged
            || playbackInfo.timeline != lastPreloadPoolInvalidationTimeline
        ) {
            // invalidate the pool when the loading period or the timeline changed.
            lastPreloadPoolInvalidationTimeline = playbackInfo.timeline
            queue.invalidatePreloadPool(playbackInfo.timeline)
        }
        maybeContinuePreloading()
    }

    private fun maybeContinuePreloading() {
        queue.maybeUpdatePreloadMediaPeriodHolder()
        val preloading = queue.preloadingPeriod
        if (preloading == null || (preloading.prepareCalled && !preloading.prepared)
            || preloading.mediaPeriod.isLoading
            || !loadControl.shouldContinuePreloading(
                playerId,
                playbackInfo.timeline,
                preloading.info.id,
                if (preloading.prepared) preloading.mediaPeriod.bufferedPositionUs else 0L
            )
        ) {
            return
        }
        if (!preloading.prepareCalled) {
            preloading.prepare(this, preloading.info.startPositionUs)
        } else {
            preloading.continueLoading(
                LoadingInfo.Builder()
                    .setPlaybackPositionUs(preloading.toPeriodTime(rendererPositionUs))
                    .setPlaybackSpeed(mediaClock.playbackParameters.speed)
                    .setLastRebufferRealtimeMs(lastRebufferRealtimeMs)
                    .build()
            )
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun maybeUpdatePlayingPeriod() {
        var advancedPlayingPeriod = false
        while (shouldAdvancePlayingPeriod()) {
            if (advancedPlayingPeriod) {
                // If we advance more than one period at a time, notify listeners after each update.
                maybeNotifyPlaybackInfoChanged(-1)
            }
            isPrewarmingDisabledUntilNextTransition = false
            val newPlayingPeriodHolder = queue.advancePlayingPeriod()!!
            val isCancelledSSAIAdTransition =
                playbackInfo.periodId.periodUid == newPlayingPeriodHolder.info.id.periodUid
                        && playbackInfo.periodId.adGroupIndex == C.INDEX_UNSET && newPlayingPeriodHolder.info.id.adGroupIndex == C.INDEX_UNSET && (playbackInfo.periodId.nextAdGroupIndex
                        != newPlayingPeriodHolder.info.id.nextAdGroupIndex)
            playbackInfo =
                handlePositionDiscontinuity(
                    newPlayingPeriodHolder.info.id,
                    newPlayingPeriodHolder.info.startPositionUs,
                    newPlayingPeriodHolder.info.requestedContentPositionUs,
                    newPlayingPeriodHolder.info.startPositionUs,
                    !isCancelledSSAIAdTransition,
                    Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                )
            resetPendingPauseAtEndOfPeriod()
            updatePlaybackPositions()
            for (i in renderers.indices) {
                if (isRendererPrewarming(i)
                    && newPlayingPeriodHolder == queue.getPrewarmingPeriod(i)
                ) {
                    renderers[i].maybeHandlePrewarmingTransition()
                }
            }
            if (playbackInfo.playbackState == Player.STATE_READY) {
                startRenderers()
            }
            allowRenderersToRenderStartOfStreams()
            advancedPlayingPeriod = true
        }
    }

    private val isReadingAhead: Boolean
        get() {
            for (i in renderers.indices) {
                if (queue.playingPeriod != queue.getReadingPeriod(i)) {
                    return true
                }
            }
            return false
        }

    private fun maybeUpdateOffloadScheduling() {
        // If playing period is audio-only with offload mode preference to enable, then offload
        // scheduling should be enabled.
        if (this.isReadingAhead) {
            // Do not enable offload scheduling when starting to process the next media item.
            return
        }
        val playingPeriodHolder = queue.playingPeriod
        if (playingPeriodHolder != null) {
            val trackSelectorResult = playingPeriodHolder.trackSelectorResult
            var isAudioRendererEnabledAndOffloadPreferred = false
            var isAudioOnly = true
            for (i in renderers.indices) {
                if (trackSelectorResult.isRendererEnabled(i)) {
                    if (renderers[i].trackType != C.TRACK_TYPE_AUDIO) {
                        isAudioOnly = false
                        break
                    }
                    if (trackSelectorResult.rendererConfigurations[i]!!.offloadModePreferred
                        != AudioSink.OFFLOAD_MODE_DISABLED
                    ) {
                        isAudioRendererEnabledAndOffloadPreferred = true
                    }
                }
            }
            setOffloadSchedulingEnabled(isAudioRendererEnabledAndOffloadPreferred && isAudioOnly)
        }
    }

    private fun allowRenderersToRenderStartOfStreams() {
        val playingTracks = queue.playingPeriod!!.trackSelectorResult
        for (i in renderers.indices) {
            if (!playingTracks.isRendererEnabled(i)) {
                continue
            }
            renderers[i].enableMayRenderStartOfStream()
        }
    }

    private fun resetPendingPauseAtEndOfPeriod() {
        val playingPeriod = queue.playingPeriod
        pendingPauseAtEndOfPeriod =
            playingPeriod != null && playingPeriod.info.isLastInTimelineWindow && pauseAtEndOfWindow
    }

    private fun shouldAdvancePlayingPeriod(): Boolean {
        if (!shouldPlayWhenReady()) {
            return false
        }
        if (pendingPauseAtEndOfPeriod) {
            return false
        }
        val playingPeriodHolder = queue.playingPeriod ?: return false
        val nextPlayingPeriodHolder = playingPeriodHolder.getNext()
        return nextPlayingPeriodHolder != null && rendererPositionUs >= nextPlayingPeriodHolder.startPositionRendererTime && areAllRenderersInCorrectState(
            nextPlayingPeriodHolder
        )
    }

    private fun haveAllRenderersFinishedReading(readingPeriodHolder: MediaPeriodHolder): Boolean {
        if (!readingPeriodHolder.prepared) {
            return false
        }
        for (renderer in renderers) {
            if (!renderer.hasFinishedReadingFromPeriod(readingPeriodHolder)) {
                return false
            }
        }
        return true
    }

    private fun setAllNonPrewarmingRendererStreamsFinal(streamEndPositionUs: Long) {
        for (renderer in renderers) {
            renderer.setAllNonPrewarmingRendererStreamsFinal(streamEndPositionUs)
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun handlePeriodPrepared(mediaPeriod: MediaPeriod?) {
        if (queue.isLoading(mediaPeriod)) {
            handleLoadingPeriodPrepared(queue.loadingPeriod!!)
        } else {
            val preloadHolder = queue.getPreloadHolderByMediaPeriod(mediaPeriod)
            if (preloadHolder != null) {
                Preconditions.checkState(!preloadHolder.prepared)
                preloadHolder.handlePrepared(
                    mediaClock.playbackParameters.speed,
                    playbackInfo.timeline,
                    playbackInfo.playWhenReady
                )
                if (queue.isPreloading(mediaPeriod)) {
                    maybeContinuePreloading()
                }
            }
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun handleLoadingPeriodPrepared(loadingPeriodHolder: MediaPeriodHolder) {
        if (!loadingPeriodHolder.prepared) {
            loadingPeriodHolder.handlePrepared(
                mediaClock.playbackParameters.speed,
                playbackInfo.timeline,
                playbackInfo.playWhenReady
            )
        }
        updateLoadControlTrackSelection(
            loadingPeriodHolder.info.id,
            loadingPeriodHolder.trackGroups,
            loadingPeriodHolder.trackSelectorResult
        )
        if (loadingPeriodHolder == queue.playingPeriod) {
            // This is the first prepared period, so update the position and the renderers.
            resetRendererPosition(
                loadingPeriodHolder.info.startPositionUs, true
            )
            enableRenderers()
            setAllRenderersToCorrectState(loadingPeriodHolder)
            playbackInfo =
                handlePositionDiscontinuity(
                    playbackInfo.periodId,
                    loadingPeriodHolder.info.startPositionUs,
                    playbackInfo.requestedContentPositionUs,
                    loadingPeriodHolder.info.startPositionUs,
                    false,
                    Player.DISCONTINUITY_REASON_INTERNAL
                )
        }
        maybeContinueLoading()
    }

    private fun handleContinueLoadingRequested(mediaPeriod: MediaPeriod?) {
        if (queue.isLoading(mediaPeriod)) {
            queue.reevaluateBuffer(rendererPositionUs)
            maybeContinueLoading()
        } else if (queue.isPreloading(mediaPeriod)) {
            maybeContinuePreloading()
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun handlePlaybackParameters(
        playbackParameters: PlaybackParameters, acknowledgeCommand: Boolean
    ) {
        handlePlaybackParameters(
            playbackParameters,
            playbackParameters.speed,
            true,
            acknowledgeCommand
        )
    }

    @Throws(ExoPlaybackException::class)
    private fun handlePlaybackParameters(
        playbackParameters: PlaybackParameters,
        currentPlaybackSpeed: Float,
        updatePlaybackInfo: Boolean,
        acknowledgeCommand: Boolean
    ) {
        if (updatePlaybackInfo) {
            if (acknowledgeCommand) {
                playbackInfoUpdate.incrementPendingOperationAcks(1)
            }
            playbackInfo = playbackInfo.copyWithPlaybackParameters(playbackParameters)
        }
        updateTrackSelectionPlaybackSpeed(playbackParameters.speed)
        for (rendererHolder in renderers) {
            rendererHolder.setPlaybackSpeed(
                currentPlaybackSpeed, playbackParameters.speed
            )
        }
    }

    private fun maybeContinueLoading() {
        shouldContinueLoading = shouldContinueLoading()
        if (shouldContinueLoading) {
            val loadingPeriod = queue.loadingPeriod!!
            loadingPeriod.continueLoading(
                LoadingInfo.Builder()
                    .setPlaybackPositionUs(loadingPeriod.toPeriodTime(rendererPositionUs))
                    .setPlaybackSpeed(mediaClock.playbackParameters!!.speed)
                    .setLastRebufferRealtimeMs(lastRebufferRealtimeMs)
                    .build()
            )
        }
        updateisLoading()
    }

    private fun shouldContinueLoading(): Boolean {
        if (!isLoadingPossible(queue.loadingPeriod)) {
            return false
        }
        val loadingPeriodHolder = queue.loadingPeriod
        val bufferedDurationUs =
            getTotalBufferedDurationUs(loadingPeriodHolder!!.nextLoadPositionUs)
        val playbackPositionUs =
            if (loadingPeriodHolder == queue.playingPeriod)
                loadingPeriodHolder.toPeriodTime(rendererPositionUs)
            else
                (loadingPeriodHolder.toPeriodTime(rendererPositionUs)
                        - loadingPeriodHolder.info.startPositionUs)
        val targetLiveOffsetUs =
            if (shouldUseLivePlaybackSpeedControl(
                    playbackInfo.timeline,
                    loadingPeriodHolder.info.id
                )
            )
                livePlaybackSpeedControl.targetLiveOffsetUs
            else
                C.TIME_UNSET
        val loadParameters =
            LoadControl.Parameters(
                playerId,
                playbackInfo.timeline,
                loadingPeriodHolder.info.id,
                playbackPositionUs,
                bufferedDurationUs,
                mediaClock.playbackParameters.speed,
                playbackInfo.playWhenReady,
                isRebuffering,
                targetLiveOffsetUs,
                lastRebufferRealtimeMs
            )
        var shouldContinueLoading = loadControl.shouldContinueLoading(loadParameters)
        val playingPeriodHolder = queue.playingPeriod
        if (!shouldContinueLoading && playingPeriodHolder!!.prepared
            && bufferedDurationUs < PLAYBACK_BUFFER_EMPTY_THRESHOLD_US && (backBufferDurationUs > 0 || retainBackBufferFromKeyframe)
        ) {
            // LoadControl doesn't want to continue loading despite no buffered data. Clear back buffer
            // and try again in case it's blocked on memory usage of the back buffer.
            playingPeriodHolder.mediaPeriod.discardBuffer(
                playbackInfo.positionUs, false
            )
            shouldContinueLoading = loadControl.shouldContinueLoading(loadParameters)
        }
        return shouldContinueLoading
    }

    private fun isLoadingPossible(mediaPeriodHolder: MediaPeriodHolder?): Boolean {
        return mediaPeriodHolder != null && !mediaPeriodHolder.hasLoadingError() && mediaPeriodHolder.nextLoadPositionUs != C.TIME_END_OF_SOURCE
    }

    private fun updateisLoading() {
        val loadingPeriod = queue.loadingPeriod
        val isLoading =
            shouldContinueLoading || (loadingPeriod != null && loadingPeriod.mediaPeriod.isLoading)
        if (isLoading != playbackInfo.isLoading) {
            playbackInfo = playbackInfo.copyWithIsLoading(isLoading)
        }
    }

    private fun handlePositionDiscontinuity(
        mediaPeriodId: MediaPeriodId,
        positionUs: Long,
        requestedContentPositionUs: Long,
        discontinuityStartPositionUs: Long,
        reportDiscontinuity: Boolean,
        discontinuityReason: @Player.DiscontinuityReason Int
    ): PlaybackInfo {
        deliverPendingMessageAtStartPositionRequired =
            deliverPendingMessageAtStartPositionRequired
                    || positionUs != playbackInfo.positionUs || !mediaPeriodId.equals(playbackInfo.periodId)
        resetPendingPauseAtEndOfPeriod()
        var trackGroupArray = playbackInfo.trackGroups
        var trackSelectorResult = playbackInfo.trackSelectorResult
        var staticMetadata = playbackInfo.staticMetadata
        if (mediaSourceList.isPrepared) {
            val playingPeriodHolder = queue.playingPeriod
            trackGroupArray =
                playingPeriodHolder?.trackGroups ?: TrackGroupArray.EMPTY
            trackSelectorResult =
                playingPeriodHolder?.trackSelectorResult ?: emptyTrackSelectorResult
            staticMetadata = extractMetadataFromTrackSelectionArray(trackSelectorResult.selections)
            // Ensure the media period queue requested content position matches the new playback info.
            if (playingPeriodHolder != null
                && playingPeriodHolder.info.requestedContentPositionUs != requestedContentPositionUs
            ) {
                playingPeriodHolder.info =
                    playingPeriodHolder.info.copyWithRequestedContentPositionUs(
                        requestedContentPositionUs
                    )
            }
            maybeUpdateOffloadScheduling()
        } else if (mediaPeriodId != playbackInfo.periodId) {
            // Reset previously kept track info if unprepared and the period changes.
            trackGroupArray = TrackGroupArray.EMPTY
            trackSelectorResult = emptyTrackSelectorResult
            staticMetadata = listOf()
        }
        if (reportDiscontinuity) {
            playbackInfoUpdate.setPositionDiscontinuity(discontinuityReason)
        }
        return playbackInfo.copyWithNewPosition(
            mediaPeriodId,
            positionUs,
            requestedContentPositionUs,
            discontinuityStartPositionUs,
            this.totalBufferedDurationUs,
            trackGroupArray,
            trackSelectorResult,
            staticMetadata
        )
    }

    private fun extractMetadataFromTrackSelectionArray(
        trackSelections: Array<ExoTrackSelection?>
    ): List<Metadata> {
        val result = mutableListOf<Metadata>()
        var seenNonEmptyMetadata = false
        for (trackSelection in trackSelections) {
            if (trackSelection != null) {
                val format = trackSelection.getFormat(0)
                if (format.metadata == null) {
                    result.add(Metadata(listOf()))
                } else {
                    result.add(format.metadata)
                    seenNonEmptyMetadata = true
                }
            }
        }
        return if (seenNonEmptyMetadata) result else listOf()
    }

    @Throws(ExoPlaybackException::class)
    private fun enableRenderers() {
        Preconditions.checkState(!this.isReadingAhead)
        enableRenderers(
            BooleanArray(renderers.size),
            queue.earliestReadingPeriod!!.startPositionRendererTime
        )
    }

    @Throws(ExoPlaybackException::class)
    private fun enableRenderers(rendererWasEnabledFlags: BooleanArray, startPositionUs: Long) {
        Preconditions.checkState(!this.isReadingAhead)
        val readingMediaPeriod = queue.earliestReadingPeriod
        val trackSelectorResult = readingMediaPeriod!!.trackSelectorResult
        // Reset all disabled renderers before enabling any new ones. This makes sure resources released
        // by the disabled renderers will be available to renderers that are being enabled.
        for (i in renderers.indices) {
            if (!trackSelectorResult.isRendererEnabled(i)) {
                renderers[i].reset()
            }
        }
        for (i in renderers.indices) {
            if (trackSelectorResult.isRendererEnabled(i)
                && !renderers[i].isReadingFromPeriod(readingMediaPeriod)
            ) {
                enableRenderer(
                    readingMediaPeriod,
                    i,
                    rendererWasEnabledFlags[i],
                    startPositionUs
                )
            }
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun enableRenderer(
        periodHolder: MediaPeriodHolder,
        rendererIndex: Int,
        wasRendererEnabled: Boolean,
        startPositionUs: Long
    ) {
        val renderer = renderers[rendererIndex]
        if (renderer.isRendererEnabled) {
            return
        }
        val arePlayingAndReadingTheSamePeriod = periodHolder == queue.playingPeriod
        val trackSelectorResult = periodHolder.trackSelectorResult
        val rendererConfiguration =
            trackSelectorResult.rendererConfigurations[rendererIndex]!!
        val newSelection = trackSelectorResult.selections[rendererIndex]!!
        // The renderer needs enabling with its new track selection.
        val playing = shouldPlayWhenReady() && playbackInfo.playbackState == Player.STATE_READY
        // Consider as joining only if the renderer was previously disabled and being enabled on the
        // playing period.
        val joining = !wasRendererEnabled && playing
        // Enable the renderer.
        enabledRendererCount++
        renderer.enable(
            rendererConfiguration,
            newSelection,
            periodHolder.sampleStreams[rendererIndex]!!,
            rendererPositionUs,
            joining,
            arePlayingAndReadingTheSamePeriod,
            startPositionUs,
            periodHolder.rendererOffset,
            periodHolder.info.id,
            mediaClock
        )
        renderer.handleMessage(
            Renderer.MSG_SET_WAKEUP_LISTENER,
            object : WakeupListener {
                override fun onSleep() {
                    requestForRendererSleep = true
                }

                override fun onWakeup() {
                    if (isDynamicSchedulingEnabled() || offloadSchedulingEnabled) {
                        handler.sendEmptyMessage(MSG_DO_SOME_WORK)
                    }
                }
            },
            periodHolder
        )
        // Start the renderer if playing and the Playing and Reading periods are the same.
        if (playing && arePlayingAndReadingTheSamePeriod) {
            renderer.start()
        }
    }

    private fun releaseRenderers() {
        for (i in renderers.indices) {
            rendererCapabilities[i].clearListener()
            renderers[i].release()
        }
    }

    private fun setAllRenderersToCorrectState(mediaPeriod: MediaPeriodHolder) {
        for (i in renderers.indices) {
            mediaPeriod.setRendererToCorrectState(i)
        }
    }

    private fun areAllRenderersInCorrectState(mediaPeriod: MediaPeriodHolder): Boolean {
        for (i in renderers.indices) {
            if (!mediaPeriod.isRendererInCorrectState(i)) {
                return false
            }
        }
        return true
    }

    private fun handleLoadingMediaPeriodChanged(loadingTrackSelectionChanged: Boolean) {
        val loadingMediaPeriodHolder = queue.loadingPeriod
        val loadingMediaPeriodId =
            loadingMediaPeriodHolder?.info?.id ?: playbackInfo.periodId
        val loadingMediaPeriodChanged =
            playbackInfo.loadingMediaPeriodId != loadingMediaPeriodId
        if (loadingMediaPeriodChanged) {
            playbackInfo = playbackInfo.copyWithLoadingMediaPeriodId(loadingMediaPeriodId)
        }
        playbackInfo.bufferedPositionUs =
            loadingMediaPeriodHolder?.bufferedPositionUs ?: playbackInfo.positionUs
        playbackInfo.totalBufferedDurationUs = this.totalBufferedDurationUs
        if ((loadingMediaPeriodChanged || loadingTrackSelectionChanged)
            && loadingMediaPeriodHolder != null && loadingMediaPeriodHolder.prepared
        ) {
            updateLoadControlTrackSelection(
                loadingMediaPeriodHolder.info.id,
                loadingMediaPeriodHolder.trackGroups,
                loadingMediaPeriodHolder.trackSelectorResult
            )
        }
    }

    private val totalBufferedDurationUs: Long
        get() = getTotalBufferedDurationUs(playbackInfo.bufferedPositionUs)

    private fun getTotalBufferedDurationUs(bufferedPositionInLoadingPeriodUs: Long): Long {
        val loadingPeriodHolder = queue.loadingPeriod ?: return 0
        val totalBufferedDurationUs =
            bufferedPositionInLoadingPeriodUs - loadingPeriodHolder.toPeriodTime(rendererPositionUs)
        return max(0, totalBufferedDurationUs)
    }

    private fun getDurationToMediaPeriodUs(mediaPeriodHolder: MediaPeriodHolder): Long {
        Preconditions.checkState(mediaPeriodHolder.prepared)
        return ((mediaPeriodHolder.startPositionRendererTime - rendererPositionUs)
                / mediaClock.playbackParameters.speed).toLong()
    }

    private fun updateLoadControlTrackSelection(
        mediaPeriodId: MediaPeriodId,
        trackGroups: TrackGroupArray,
        trackSelectorResult: TrackSelectorResult
    ) {
        val loadingPeriodHolder = queue.loadingPeriod!!
        val playbackPositionUs =
            if (loadingPeriodHolder == queue.playingPeriod)
                loadingPeriodHolder.toPeriodTime(rendererPositionUs)
            else
                (loadingPeriodHolder.toPeriodTime(rendererPositionUs)
                        - loadingPeriodHolder.info.startPositionUs)
        val bufferedDurationUs =
            getTotalBufferedDurationUs(loadingPeriodHolder.bufferedPositionUs)
        val targetLiveOffsetUs =
            if (shouldUseLivePlaybackSpeedControl(
                    playbackInfo.timeline,
                    loadingPeriodHolder.info.id
                )
            )
                livePlaybackSpeedControl.targetLiveOffsetUs
            else
                C.TIME_UNSET
        loadControl.onTracksSelected(
            LoadControl.Parameters(
                playerId,
                playbackInfo.timeline,
                mediaPeriodId,
                playbackPositionUs,
                bufferedDurationUs,
                mediaClock.playbackParameters.speed,
                playbackInfo.playWhenReady,
                isRebuffering,
                targetLiveOffsetUs,
                lastRebufferRealtimeMs
            ),
            trackGroups,
            trackSelectorResult.selections
        )
    }

    private fun shouldPlayWhenReady(): Boolean {
        return playbackInfo.playWhenReady
                && playbackInfo.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
    }

    @Throws(IOException::class, ExoPlaybackException::class)
    private fun maybeThrowRendererStreamError(rendererIndex: Int) {
        val renderer = renderers[rendererIndex]
        try {
            renderer.maybeThrowStreamError(queue.playingPeriod!!)
        } catch (e: IOException) {
            when (renderer.trackType) {
                C.TRACK_TYPE_TEXT, C.TRACK_TYPE_METADATA -> {
                    val currentTrackSelectorResult =
                        queue.playingPeriod!!.trackSelectorResult
                    e(
                        TAG,
                        "Disabling track due to error: "
                                + Format.toLogString(
                            currentTrackSelectorResult.selections[rendererIndex]!!.selectedFormat
                        ),
                        e
                    )

                    val newTrackSelectorResult =
                        TrackSelectorResult(
                            currentTrackSelectorResult.rendererConfigurations.clone(),
                            currentTrackSelectorResult.selections.clone(),
                            currentTrackSelectorResult.tracks,
                            currentTrackSelectorResult.info
                        )
                    newTrackSelectorResult.rendererConfigurations[rendererIndex] = null
                    newTrackSelectorResult.selections[rendererIndex] = null
                    disableRenderer(rendererIndex)
                    queue
                        .playingPeriod!!
                        .applyTrackSelection(
                            newTrackSelectorResult,
                            playbackInfo.positionUs,
                            false
                        )
                }

                else -> throw e
            }
        } catch (e: RuntimeException) {
            when (renderer.trackType) {
                C.TRACK_TYPE_TEXT, C.TRACK_TYPE_METADATA -> {
                    val currentTrackSelectorResult =
                        queue.playingPeriod!!.trackSelectorResult
                    e(
                        TAG,
                        "Disabling track due to error: "
                                + Format.toLogString(
                            currentTrackSelectorResult.selections[rendererIndex]!!.selectedFormat
                        ),
                        e
                    )

                    val newTrackSelectorResult =
                        TrackSelectorResult(
                            currentTrackSelectorResult.rendererConfigurations.clone(),
                            currentTrackSelectorResult.selections.clone(),
                            currentTrackSelectorResult.tracks,
                            currentTrackSelectorResult.info
                        )
                    newTrackSelectorResult.rendererConfigurations[rendererIndex] = null
                    newTrackSelectorResult.selections[rendererIndex] = null
                    disableRenderer(rendererIndex)
                    queue
                        .playingPeriod!!
                        .applyTrackSelection(
                            newTrackSelectorResult,
                            playbackInfo.positionUs,
                            false
                        )
                }

                else -> throw e
            }
        }
    }

    private fun areRenderersPrewarming(): Boolean {
        if (!hasSecondaryRenderers) {
            return false
        }
        for (renderer in renderers) {
            if (renderer.isPrewarming) {
                return true
            }
        }
        return false
    }

    private fun isRendererPrewarming(index: Int): Boolean {
        if (!hasSecondaryRenderers) {
            return false
        }
        return renderers[index].isPrewarming
    }

    private fun isDynamicSchedulingEnabled(): Boolean {
        return dynamicSchedulingEnabled
                || (scrubbingModeEnabled && scrubbingModeParameters.shouldEnableDynamicScheduling)
    }

    private fun updateRebufferingState(
        isRebuffering: Boolean,
        resetLastRebufferRealtimeMs: Boolean
    ) {
        this.isRebuffering = isRebuffering
        this.lastRebufferRealtimeMs =
            if (isRebuffering && !resetLastRebufferRealtimeMs) clock.elapsedRealtime() else C.TIME_UNSET
    }

    private class SeekPosition(
        val timeline: Timeline,
        val windowIndex: Int,
        val windowPositionUs: Long
    )

    private class PositionUpdateForPlaylistChange(
        val periodId: MediaPeriodId,
        val periodPositionUs: Long,
        val requestedContentPositionUs: Long,
        val forceBufferingState: Boolean,
        val endPlayback: Boolean,
        val setTargetLiveOffset: Boolean,
        val periodPositionChanged: Boolean,
        val reportDiscontinuity: Boolean,
        val discontinuityReason: @Player.DiscontinuityReason Int
    )

    private class PendingMessageInfo(val message: PlayerMessage) : Comparable<PendingMessageInfo?> {
        var resolvedPeriodIndex: Int = 0
        var resolvedPeriodTimeUs: Long = 0
        var resolvedPeriodUid: Any? = null

        fun setResolvedPosition(periodIndex: Int, periodTimeUs: Long, periodUid: Any?) {
            resolvedPeriodIndex = periodIndex
            resolvedPeriodTimeUs = periodTimeUs
            resolvedPeriodUid = periodUid
        }

        override fun compareTo(other: PendingMessageInfo?): Int {
            if ((resolvedPeriodUid == null) != (other!!.resolvedPeriodUid == null)) {
                // PendingMessageInfos with a resolved period position are always smaller.
                return if (resolvedPeriodUid != null) -1 else 1
            }
            if (resolvedPeriodUid == null) {
                // Don't sort message with unresolved positions.
                return 0
            }
            // Sort resolved media times by period index and then by period position.
            val comparePeriodIndex = resolvedPeriodIndex - other.resolvedPeriodIndex
            if (comparePeriodIndex != 0) {
                return comparePeriodIndex
            }
            return java.lang.Long.compare(resolvedPeriodTimeUs, other.resolvedPeriodTimeUs)
        }
    }

    private class MediaSourceListUpdateMessage(
        val mediaSourceHolders: List<MediaSourceList.MediaSourceHolder>,
        val shuffleOrder: ShuffleOrder,
        val windowIndex: Int,
        val positionUs: Long
    )

    private class MoveMediaItemsMessage(
        val fromIndex: Int,
        val toIndex: Int,
        val newFromIndex: Int,
        val shuffleOrder: ShuffleOrder
    )

    companion object {
        private const val TAG = "ExoPlayerImplInternal"

        // Internal messages
        private const val MSG_SET_PLAY_WHEN_READY = 1
        private const val MSG_DO_SOME_WORK = 2
        private const val MSG_SEEK_TO = 3
        private const val MSG_SET_PLAYBACK_PARAMETERS = 4
        private const val MSG_SET_SEEK_PARAMETERS = 5
        private const val MSG_STOP = 6
        private const val MSG_RELEASE = 7
        private const val MSG_PERIOD_PREPARED = 8
        private const val MSG_SOURCE_CONTINUE_LOADING_REQUESTED = 9
        private const val MSG_TRACK_SELECTION_INVALIDATED = 10
        private const val MSG_SET_REPEAT_MODE = 11
        private const val MSG_SET_SHUFFLE_ENABLED = 12
        private const val MSG_SET_FOREGROUND_MODE = 13
        private const val MSG_SEND_MESSAGE = 14
        private const val MSG_SEND_MESSAGE_TO_TARGET_THREAD = 15
        private const val MSG_PLAYBACK_PARAMETERS_CHANGED_INTERNAL = 16
        private const val MSG_SET_MEDIA_SOURCES = 17
        private const val MSG_ADD_MEDIA_SOURCES = 18
        private const val MSG_MOVE_MEDIA_SOURCES = 19
        private const val MSG_REMOVE_MEDIA_SOURCES = 20
        private const val MSG_SET_SHUFFLE_ORDER = 21
        private const val MSG_PLAYLIST_UPDATE_REQUESTED = 22
        private const val MSG_SET_PAUSE_AT_END_OF_WINDOW = 23
        private const val MSG_SET_ENFORCE_AD_PLAYBACK_ON_TIMELINE_REFRESH = 24
        private const val MSG_ATTEMPT_RENDERER_ERROR_RECOVERY = 25
        private const val MSG_RENDERER_CAPABILITIES_CHANGED = 26
        private const val MSG_UPDATE_MEDIA_SOURCES_WITH_MEDIA_ITEMS = 27
        private const val MSG_SET_PRELOAD_CONFIGURATION = 28
        private const val MSG_PREPARE = 29
        private const val MSG_SET_VIDEO_OUTPUT = 30
        private const val MSG_SET_AUDIO_ATTRIBUTES = 31
        private const val MSG_SET_VOLUME = 32
        private const val MSG_AUDIO_FOCUS_PLAYER_COMMAND = 33
        private const val MSG_AUDIO_FOCUS_VOLUME_MULTIPLIER = 34
        private const val MSG_SET_VIDEO_FRAME_METADATA_LISTENER = 35
        private const val MSG_SET_SCRUBBING_MODE_ENABLED = 36
        private const val MSG_SEEK_COMPLETED_IN_SCRUBBING_MODE = 37
        private const val MSG_SET_SCRUBBING_MODE_PARAMETERS = 38
        private const val MSG_SET_IMAGE_METADATA_LISTENER = 39
        private const val MSG_SET_AUDIO_SESSION_ID = 40
        private val BUFFERING_MAXIMUM_INTERVAL_MS = usToMs(Renderer.DEFAULT_DURATION_TO_PROGRESS_US)
        private const val READY_MAXIMUM_INTERVAL_MS: Long = 1000
        private const val PLAYBACK_STUCK_AFTER_MS = 4000
        private const val PLAYBACK_BUFFER_EMPTY_THRESHOLD_US: Long = 500000
        private const val DURATION_TO_ADVANCE_READING_THRESHOLD_US = 10 * C.MICROS_PER_SECOND

        private fun resolvePositionForPlaylistChange(
            timeline: Timeline,
            playbackInfo: PlaybackInfo,
            pendingInitialSeekPosition: SeekPosition?,
            queue: MediaPeriodQueue,
            @Player.RepeatMode repeatMode: @Player.RepeatMode Int,
            shuffleModeEnabled: Boolean,
            isSourceRefresh: Boolean,
            window: Timeline.Window,
            period: Timeline.Period,
            enforceAdPlaybackOnTimelineRefresh: Boolean
        ): PositionUpdateForPlaylistChange {
            if (timeline.isEmpty) {
                val newPeriodId = PlaybackInfo.dummyPeriodForEmptyTimeline
                val periodPositionChanged =
                    !newPeriodId.equals(playbackInfo.periodId) || playbackInfo.positionUs != 0L
                val reportDiscontinuity =
                    periodPositionChanged
                            && isSourceRefresh
                            && !playbackInfo.timeline.isEmpty && !playbackInfo.timeline.getPeriodByUid(
                        playbackInfo.periodId.periodUid,
                        period
                    )
                        .isPlaceholder
                return PositionUpdateForPlaylistChange(
                    newPeriodId,
                    0,
                    C.TIME_UNSET,
                    false,
                    true,
                    false,
                    periodPositionChanged,
                    reportDiscontinuity,
                    Player.DISCONTINUITY_REASON_REMOVE
                )
            }
            val oldPeriodId = playbackInfo.periodId
            var newPeriodUid = oldPeriodId.periodUid
            val isUsingPlaceholderPeriod: Boolean = isUsingPlaceholderPeriod(playbackInfo, period)
            val oldContentPositionUs =
                if (playbackInfo.periodId.isAd || isUsingPlaceholderPeriod)
                    playbackInfo.requestedContentPositionUs
                else
                    playbackInfo.positionUs
            var newContentPositionUs = oldContentPositionUs
            var startAtDefaultPositionWindowIndex = C.INDEX_UNSET
            var forceBufferingState = false
            var endPlayback = false
            var setTargetLiveOffset = false
            if (pendingInitialSeekPosition != null) {
                // Resolve initial seek position.
                val periodPosition: Pair<Any, Long>? =
                    resolveSeekPositionUs(
                        timeline,
                        pendingInitialSeekPosition,
                        true,
                        repeatMode,
                        shuffleModeEnabled,
                        window,
                        period
                    )
                if (periodPosition == null) {
                    // The initial seek in the empty old timeline is invalid in the new timeline.
                    endPlayback = true
                    startAtDefaultPositionWindowIndex =
                        timeline.getFirstWindowIndex(shuffleModeEnabled)
                } else {
                    // The pending seek has been resolved successfully in the new timeline.
                    if (pendingInitialSeekPosition.windowPositionUs == C.TIME_UNSET) {
                        startAtDefaultPositionWindowIndex =
                            timeline.getPeriodByUid(periodPosition.first, period).windowIndex
                    } else {
                        newPeriodUid = periodPosition.first
                        newContentPositionUs = periodPosition.second!!
                        // Use explicit initial seek as new target live offset.
                        setTargetLiveOffset = true
                    }
                    forceBufferingState = playbackInfo.playbackState == Player.STATE_ENDED
                }
            } else if (playbackInfo.timeline.isEmpty) {
                // Resolve to default position if the old timeline is empty and no seek is requested above.
                startAtDefaultPositionWindowIndex = timeline.getFirstWindowIndex(shuffleModeEnabled)
            } else if (timeline.getIndexOfPeriod(newPeriodUid) == C.INDEX_UNSET) {
                // The current period isn't in the new timeline. Attempt to resolve a subsequent period whose
                // window we can restart from.
                val newWindowIndex: Int =
                    resolveSubsequentPeriod(
                        window,
                        period,
                        repeatMode,
                        shuffleModeEnabled,
                        newPeriodUid,
                        playbackInfo.timeline,
                        timeline
                    )
                if (newWindowIndex == C.INDEX_UNSET) {
                    // We failed to resolve a suitable restart position but the timeline is not empty.
                    endPlayback = true
                    startAtDefaultPositionWindowIndex =
                        timeline.getFirstWindowIndex(shuffleModeEnabled)
                } else {
                    // We resolved a subsequent period. Start at the default position in the corresponding
                    // window.
                    startAtDefaultPositionWindowIndex = newWindowIndex
                }
            } else if (oldContentPositionUs == C.TIME_UNSET) {
                // The content was requested to start from its default position and we haven't used the
                // resolved position yet. Re-resolve in case the default position changed.
                startAtDefaultPositionWindowIndex =
                    timeline.getPeriodByUid(newPeriodUid, period).windowIndex
            } else if (isUsingPlaceholderPeriod) {
                // We previously requested a content position for a placeholder period, but haven't used it
                // yet. Re-resolve the requested window position to the period position in case it changed.
                playbackInfo.timeline.getPeriodByUid(oldPeriodId.periodUid, period)
                if (playbackInfo.timeline.getWindow(period.windowIndex, window).firstPeriodIndex
                    == playbackInfo.timeline.getIndexOfPeriod(oldPeriodId.periodUid)
                ) {
                    // Only need to resolve the first period in a window because subsequent periods must start
                    // at position 0 and don't need to be resolved.
                    val windowPositionUs = oldContentPositionUs + period.positionInWindowUs
                    val windowIndex = timeline.getPeriodByUid(newPeriodUid, period).windowIndex
                    val periodPositionUs =
                        timeline.getPeriodPositionUs(window, period, windowIndex, windowPositionUs)
                    newPeriodUid = periodPositionUs.first
                    newContentPositionUs = periodPositionUs.second!!
                } else {
                    // For all other periods, we may need to clip the duration again.
                    val newPeriodDurationUs =
                        timeline.getPeriodByUid(newPeriodUid, period).durationUs
                    if (newPeriodDurationUs != C.TIME_UNSET) {
                        newContentPositionUs =
                            constrainValue(
                                newContentPositionUs,
                                0,
                                period.durationUs - 1
                            )
                    }
                }
                // Use an explicitly requested content position as new target live offset.
                setTargetLiveOffset = true
            }

            // Set period uid for default positions and resolve position for ad resolution.
            var contentPositionForAdResolutionUs = newContentPositionUs
            if (startAtDefaultPositionWindowIndex != C.INDEX_UNSET) {
                val defaultPositionUs =
                    timeline.getPeriodPositionUs(
                        window,
                        period,
                        startAtDefaultPositionWindowIndex,
                        C.TIME_UNSET
                    )
                newPeriodUid = defaultPositionUs.first
                contentPositionForAdResolutionUs = defaultPositionUs.second!!
                newContentPositionUs = C.TIME_UNSET
            }

            // Ensure ad insertion metadata is up to date.
            val periodIdWithAds =
                queue.resolveMediaPeriodIdForAdsAfterPeriodPositionChange(
                    playbackInfo,
                    timeline,
                    newPeriodUid,
                    contentPositionForAdResolutionUs,
                    enforceAdPlaybackOnTimelineRefresh,
                    isUsingPlaceholderPeriod
                )
            val earliestCuePointIsUnchangedOrLater =
                periodIdWithAds.nextAdGroupIndex == C.INDEX_UNSET
                        || (oldPeriodId.nextAdGroupIndex != C.INDEX_UNSET
                        && periodIdWithAds.nextAdGroupIndex >= oldPeriodId.nextAdGroupIndex)
            // Drop update if we keep playing the same content (MediaPeriod.periodUid are identical) and
            // the only change is that MediaPeriodId.nextAdGroupIndex increased. This postpones a potential
            // discontinuity until we reach the former next ad group position.
            val sameOldAndNewPeriodUid = oldPeriodId.periodUid == newPeriodUid
            val onlyNextAdGroupIndexIncreased =
                sameOldAndNewPeriodUid
                        && !oldPeriodId.isAd && !periodIdWithAds.isAd && earliestCuePointIsUnchangedOrLater
            // Drop update if the change is from/to server-side inserted ads at the same content position to
            // avoid any unintentional renderer reset.
            val isInStreamAdChange: Boolean =
                isIgnorableServerSideAdInsertionPeriodChange(
                    isUsingPlaceholderPeriod,
                    oldPeriodId,
                    oldContentPositionUs,
                    periodIdWithAds,
                    timeline.getPeriodByUid(newPeriodUid, period),
                    newContentPositionUs
                )
            val newPeriodId =
                if (onlyNextAdGroupIndexIncreased || isInStreamAdChange) oldPeriodId else periodIdWithAds

            var periodPositionUs = contentPositionForAdResolutionUs
            if (newPeriodId.isAd) {
                if (newPeriodId.equals(oldPeriodId)) {
                    periodPositionUs = playbackInfo.positionUs
                } else {
                    timeline.getPeriodByUid(newPeriodId.periodUid, period)
                    periodPositionUs =
                        if (newPeriodId.adIndexInAdGroup == period.getFirstAdIndexToPlay(newPeriodId.adGroupIndex))
                            period.adResumePositionUs
                        else
                            0
                }
            } else if (sameOldAndNewPeriodUid && oldPeriodId.isAd) {
                // Transition to content after ad.
                val adPlaybackState =
                    timeline.getPeriodByUid(newPeriodUid, period).adPlaybackState
                val adGroup = adPlaybackState.getAdGroup(oldPeriodId.adGroupIndex)
                val contentResumeOffsetUs = adGroup.contentResumeOffsetUs
                val useRequestedContentPosition =
                    playbackInfo.requestedContentPositionUs != C.TIME_UNSET && adGroup.timeUs != C.TIME_END_OF_SOURCE && adGroup.timeUs + contentResumeOffsetUs <= playbackInfo.requestedContentPositionUs
                if (!useRequestedContentPosition && adGroup.count > oldPeriodId.adIndexInAdGroup && adGroup.states[oldPeriodId.adIndexInAdGroup] == AdPlaybackState.AD_STATE_SKIPPED) {
                    // An ad period was skipped and playback continues on content. Apply resume offset.
                    val durationUs = timeline.getPeriodByUid(newPeriodUid, period).durationUs
                    periodPositionUs =
                        if (durationUs != C.TIME_UNSET)
                            min(durationUs - 1, periodPositionUs + contentResumeOffsetUs)
                        else
                            periodPositionUs + contentResumeOffsetUs
                    newContentPositionUs = periodPositionUs
                }
            }
            val periodPositionChanged =
                newPeriodId != playbackInfo.periodId || periodPositionUs != playbackInfo.positionUs
            var discontinuityReason: @Player.DiscontinuityReason Int =
                if (timeline.getIndexOfPeriod(playbackInfo.periodId.periodUid) == C.INDEX_UNSET)
                    Player.DISCONTINUITY_REASON_REMOVE
                else
                    Player.DISCONTINUITY_REASON_SKIP
            if (newPeriodId.periodUid == playbackInfo.periodId.periodUid
                && newPeriodId.adGroupIndex != C.INDEX_UNSET
            ) {
                val adPlaybackState =
                    timeline.getPeriodByUid(newPeriodId.periodUid, period).adPlaybackState
                val adGroup = adPlaybackState.getAdGroup(newPeriodId.adGroupIndex)
                if (newPeriodId.adIndexInAdGroup >= adGroup.states.size
                    || adGroup.states[newPeriodId.adIndexInAdGroup] != AdPlaybackState.AD_STATE_SKIPPED
                ) {
                    // An ad inserted into the timeline causes a sudden change of the playing period.
                    discontinuityReason = Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                }
            }
            val reportDiscontinuity =
                periodPositionChanged
                        && isSourceRefresh
                        && !playbackInfo.timeline.isEmpty && !playbackInfo.timeline.getPeriodByUid(
                    playbackInfo.periodId.periodUid,
                    period
                )
                    .isPlaceholder
            return PositionUpdateForPlaylistChange(
                newPeriodId,
                periodPositionUs,
                newContentPositionUs,
                forceBufferingState,
                endPlayback,
                setTargetLiveOffset,
                periodPositionChanged,
                reportDiscontinuity,
                discontinuityReason
            )
        }

        private fun isIgnorableServerSideAdInsertionPeriodChange(
            isUsingPlaceholderPeriod: Boolean,
            oldPeriodId: MediaPeriodId,
            oldContentPositionUs: Long,
            newPeriodId: MediaPeriodId,
            newPeriod: Timeline.Period,
            newContentPositionUs: Long
        ): Boolean {
            if (isUsingPlaceholderPeriod
                || oldContentPositionUs != newContentPositionUs || (oldPeriodId.periodUid != newPeriodId.periodUid)
            ) {
                // The period position changed.
                return false
            }
            if (oldPeriodId.isAd && newPeriod.isServerSideInsertedAdGroup(oldPeriodId.adGroupIndex)) {
                // Whether the old period was a server side ad that doesn't need skipping to the content.
                return (newPeriod.getAdState(oldPeriodId.adGroupIndex, oldPeriodId.adIndexInAdGroup)
                        != AdPlaybackState.AD_STATE_ERROR)
                        && (newPeriod.getAdState(
                    oldPeriodId.adGroupIndex,
                    oldPeriodId.adIndexInAdGroup
                )
                        != AdPlaybackState.AD_STATE_SKIPPED)
            }
            // If the new period is a server side inserted ad, we can just continue playing.
            return newPeriodId.isAd && newPeriod.isServerSideInsertedAdGroup(newPeriodId.adGroupIndex)
        }

        private fun isUsingPlaceholderPeriod(
            playbackInfo: PlaybackInfo, period: Timeline.Period?
        ): Boolean {
            val periodId = playbackInfo.periodId
            val timeline = playbackInfo.timeline
            return timeline.isEmpty || timeline.getPeriodByUid(
                periodId.periodUid,
                period!!
            ).isPlaceholder
        }

        private fun resolvePendingMessagePosition(
            pendingMessageInfo: PendingMessageInfo,
            newTimeline: Timeline,
            previousTimeline: Timeline,
            repeatMode: @Player.RepeatMode Int,
            shuffleModeEnabled: Boolean,
            window: Timeline.Window,
            period: Timeline.Period
        ): Boolean {
            val resolvedPeriodUid = pendingMessageInfo.resolvedPeriodUid
            if (resolvedPeriodUid == null) {
                // Position is still unresolved. Try to find window in new timeline.
                val requestPositionUs =
                    if (pendingMessageInfo.message.positionMs == C.TIME_END_OF_SOURCE)
                        C.TIME_UNSET
                    else
                        msToUs(pendingMessageInfo.message.positionMs)
                val periodPosition: Pair<Any, Long>? =
                    resolveSeekPositionUs(
                        newTimeline,
                        SeekPosition(
                            pendingMessageInfo.message.timeline,
                            pendingMessageInfo.message.mediaItemIndex,
                            requestPositionUs
                        ),
                        false,
                        repeatMode,
                        shuffleModeEnabled,
                        window,
                        period
                    )
                if (periodPosition == null) {
                    return false
                }
                pendingMessageInfo.setResolvedPosition(
                    newTimeline.getIndexOfPeriod(periodPosition.first),
                    periodPosition.second!!,
                    periodPosition.first
                )
                if (pendingMessageInfo.message.positionMs == C.TIME_END_OF_SOURCE) {
                    resolvePendingMessageEndOfStreamPosition(
                        newTimeline,
                        pendingMessageInfo,
                        window,
                        period
                    )
                }
                return true
            }
            // Position has been resolved for a previous timeline. Try to find the updated period index.
            val index = newTimeline.getIndexOfPeriod(resolvedPeriodUid)
            if (index == C.INDEX_UNSET) {
                return false
            }
            if (pendingMessageInfo.message.positionMs == C.TIME_END_OF_SOURCE) {
                // Re-resolve end of stream in case the duration changed.
                resolvePendingMessageEndOfStreamPosition(
                    newTimeline,
                    pendingMessageInfo,
                    window,
                    period
                )
                return true
            }
            pendingMessageInfo.resolvedPeriodIndex = index
            previousTimeline.getPeriodByUid(resolvedPeriodUid, period)
            if (period.isPlaceholder
                && (previousTimeline.getWindow(period.windowIndex, window).firstPeriodIndex
                        == previousTimeline.getIndexOfPeriod(resolvedPeriodUid))
            ) {
                // The position needs to be re-resolved because the window in the previous timeline wasn't
                // fully prepared. Only resolve the first period in a window because subsequent periods must
                // start at position 0 and don't need to be resolved.
                val windowPositionUs =
                    pendingMessageInfo.resolvedPeriodTimeUs + period.positionInWindowUs
                val windowIndex =
                    newTimeline.getPeriodByUid(
                        pendingMessageInfo.resolvedPeriodUid!!,
                        period
                    ).windowIndex
                val periodPositionUs =
                    newTimeline.getPeriodPositionUs(window, period, windowIndex, windowPositionUs)
                pendingMessageInfo.setResolvedPosition(
                    newTimeline.getIndexOfPeriod(periodPositionUs.first),
                    periodPositionUs.second!!,
                    periodPositionUs.first
                )
            }
            return true
        }

        private fun resolvePendingMessageEndOfStreamPosition(
            timeline: Timeline,
            messageInfo: PendingMessageInfo,
            window: Timeline.Window?,
            period: Timeline.Period
        ) {
            val windowIndex =
                timeline.getPeriodByUid(messageInfo.resolvedPeriodUid!!, period).windowIndex
            val lastPeriodIndex = timeline.getWindow(windowIndex, window!!).lastPeriodIndex
            val lastPeriodUid = timeline.getPeriod(lastPeriodIndex, period, true).uid
            val positionUs =
                if (period.durationUs != C.TIME_UNSET) period.durationUs - 1 else Long.MAX_VALUE
            messageInfo.setResolvedPosition(lastPeriodIndex, positionUs, lastPeriodUid)
        }

        private fun resolveSeekPositionUs(
            timeline: Timeline,
            seekPosition: SeekPosition,
            trySubsequentPeriods: Boolean,
            repeatMode: @Player.RepeatMode Int,
            shuffleModeEnabled: Boolean,
            window: Timeline.Window,
            period: Timeline.Period
        ): Pair<Any, Long>? {
            var seekTimeline = seekPosition.timeline
            if (timeline.isEmpty) {
                // We don't have a valid timeline yet, so we can't resolve the position.
                return null
            }
            if (seekTimeline.isEmpty) {
                // The application performed a blind seek with an empty timeline (most likely based on
                // knowledge of what the future timeline will be). Use the internal timeline.
                seekTimeline = timeline
            }
            // Map the SeekPosition to a position in the corresponding timeline.
            var periodPositionUs: Pair<Any, Long>?
            try {
                periodPositionUs =
                    seekTimeline.getPeriodPositionUs(
                        window, period, seekPosition.windowIndex, seekPosition.windowPositionUs
                    )
            } catch (e: IndexOutOfBoundsException) {
                // The window index of the seek position was outside the bounds of the timeline.
                return null
            }
            if (timeline == seekTimeline) {
                // Our internal timeline is the seek timeline, so the mapped position is correct.
                return periodPositionUs
            }
            // Attempt to find the mapped period in the internal timeline.
            val periodIndex = timeline.getIndexOfPeriod(periodPositionUs.first)
            if (periodIndex != C.INDEX_UNSET) {
                // We successfully located the period in the internal timeline.
                if (seekTimeline.getPeriodByUid(periodPositionUs.first, period).isPlaceholder
                    && (seekTimeline.getWindow(period.windowIndex, window).firstPeriodIndex
                            == seekTimeline.getIndexOfPeriod(periodPositionUs.first))
                ) {
                    // The seek timeline was using a placeholder, so we need to re-resolve using the updated
                    // timeline in case the resolved position changed. Only resolve the first period in a window
                    // because subsequent periods must start at position 0 and don't need to be resolved.
                    val newWindowIndex =
                        timeline.getPeriodByUid(periodPositionUs.first, period).windowIndex
                    periodPositionUs =
                        timeline.getPeriodPositionUs(
                            window, period, newWindowIndex, seekPosition.windowPositionUs
                        )
                }
                return periodPositionUs
            }
            if (trySubsequentPeriods) {
                // Try and find a subsequent period from the seek timeline in the internal timeline.
                val newWindowIndex: Int =
                    resolveSubsequentPeriod(
                        window,
                        period,
                        repeatMode,
                        shuffleModeEnabled,
                        periodPositionUs.first,
                        seekTimeline,
                        timeline
                    )
                if (newWindowIndex != C.INDEX_UNSET) {
                    // We found one. Use the default position of the corresponding window.
                    return timeline.getPeriodPositionUs(
                        window, period, newWindowIndex, C.TIME_UNSET
                    )
                }
            }
            // We didn't find one. Give up.
            return null
        }

        fun resolveSubsequentPeriod(
            window: Timeline.Window?,
            period: Timeline.Period?,
            repeatMode: @Player.RepeatMode Int,
            shuffleModeEnabled: Boolean,
            oldPeriodUid: Any?,
            oldTimeline: Timeline,
            newTimeline: Timeline
        ): Int {
            val oldWindowIndex = oldTimeline.getPeriodByUid(oldPeriodUid!!, period!!).windowIndex
            val oldWindowUid = oldTimeline.getWindow(oldWindowIndex, window!!).uid
            for (i in 0..<newTimeline.windowCount) {
                if (newTimeline.getWindow(i, window).uid == oldWindowUid) {
                    // Window still exists, resume from there.
                    return i
                }
            }
            var oldPeriodIndex = oldTimeline.getIndexOfPeriod(oldPeriodUid)
            var newPeriodIndex = C.INDEX_UNSET
            val maxIterations = oldTimeline.periodCount
            var i = 0
            while (i < maxIterations && newPeriodIndex == C.INDEX_UNSET) {
                oldPeriodIndex =
                    oldTimeline.getNextPeriodIndex(
                        oldPeriodIndex, period, window, repeatMode, shuffleModeEnabled
                    )
                if (oldPeriodIndex == C.INDEX_UNSET) {
                    // We've reached the end of the old timeline.
                    break
                }
                newPeriodIndex =
                    newTimeline.getIndexOfPeriod(oldTimeline.getUidOfPeriod(oldPeriodIndex))
                i++
            }
            return if (newPeriodIndex == C.INDEX_UNSET)
                C.INDEX_UNSET
            else
                newTimeline.getPeriod(newPeriodIndex, period).windowIndex
        }

        private fun updatePlayWhenReadyChangeReason(
            playerCommand: @PlayerCommand Int,
            playWhenReadyChangeReason: @Player.PlayWhenReadyChangeReason Int
        ): Int {
            if (playerCommand == AudioFocusManager.PLAYER_COMMAND_DO_NOT_PLAY) {
                return Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
            }
            if (playWhenReadyChangeReason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                return Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            }
            return playWhenReadyChangeReason
        }

        private fun updatePlaybackSuppressionReason(
            playerCommand: @PlayerCommand Int,
            playbackSuppressionReason: @Player.PlaybackSuppressionReason Int,
            isScrubbingModeEnabled: Boolean
        ): Int {
            if (playerCommand == AudioFocusManager.PLAYER_COMMAND_WAIT_FOR_CALLBACK) {
                return Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
            }
            if (playbackSuppressionReason
                == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
            ) {
                return if (isScrubbingModeEnabled)
                    Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING
                else
                    Player.PLAYBACK_SUPPRESSION_REASON_NONE
            }
            return playbackSuppressionReason
        }
    }
}
