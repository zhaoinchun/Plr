package androidx.media3.exoplayer

import android.content.Context
import android.media.AudioDeviceInfo
import android.os.Looper
import androidx.annotation.IntRange
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.PriorityTaskManager
import androidx.media3.common.util.Clock
import androidx.media3.common.util.Util.currentOrMainLooper
import androidx.media3.common.util.Util.isRunningOnEmulator
import androidx.media3.exoplayer.analytics.AnalyticsCollector
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.DefaultAnalyticsCollector
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.image.ImageOutput
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelectionArray
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.exoplayer.video.spherical.CameraMotionListener
import androidx.media3.extractor.DefaultExtractorsFactory
import com.google.common.base.Function
import com.google.common.base.Preconditions
import com.google.common.base.Supplier

interface ExoPlayer : Player {
    interface AudioOffloadListener {
        fun onSleepingForOffloadChanged(isSleepingForOffload: Boolean) {}
        fun onOffloadedPlayback(isOffloadedPlayback: Boolean) {}
    }

    class PreloadConfiguration(val targetPreloadDurationUs: Long) {
        companion object {
            val DEFAULT: PreloadConfiguration = PreloadConfiguration(C.TIME_UNSET)
        }
    }

    class Builder private constructor(
        val context: Context,
        var renderersFactorySupplier: Supplier<RenderersFactory>,
        var mediaSourceFactorySupplier: Supplier<MediaSource.Factory>,
        var trackSelectorSupplier: Supplier<TrackSelector> = Supplier { DefaultTrackSelector(context) },
        var loadControlSupplier: Supplier<LoadControl> = Supplier { DefaultLoadControl() },
        var bandwidthMeterSupplier: Supplier<BandwidthMeter> = Supplier {
            DefaultBandwidthMeter.getSingletonInstance(context)
        },
        var analyticsCollectorFunction: Function<Clock, AnalyticsCollector> = Function { clock: Clock ->
            DefaultAnalyticsCollector(clock)
        }
    ) {
        var clock: Clock = Clock.DEFAULT
        var foregroundModeTimeoutMs: Long = 0
        var audioOutputProvider: AudioOutputProvider? = null
        var looper: Looper = currentOrMainLooper
        var priority: @C.Priority Int = C.PRIORITY_PLAYBACK
        var priorityTaskManager: PriorityTaskManager? = null
        var audioAttributes: AudioAttributes? = AudioAttributes.DEFAULT
        var handleAudioFocus: Boolean = false
        var wakeMode: @C.WakeMode Int = C.WAKE_MODE_NONE
        var wakeModeSet: Boolean = false
        var handleAudioBecomingNoisy: Boolean = false
        var skipSilenceEnabled: Boolean = false
        var deviceVolumeControlEnabled: Boolean = false
        var videoScalingMode: @C.VideoScalingMode Int = C.VIDEO_SCALING_MODE_DEFAULT
        var videoChangeFrameRateStrategy: @C.VideoChangeFrameRateStrategy Int =
            C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
        var useLazyPreparation: Boolean = true
        var seekParameters: SeekParameters? = SeekParameters.DEFAULT
        var scrubbingModeParameters: ScrubbingModeParameters? = ScrubbingModeParameters.DEFAULT
        var seekBackIncrementMs = C.DEFAULT_SEEK_BACK_INCREMENT_MS
        var seekForwardIncrementMs = C.DEFAULT_SEEK_FORWARD_INCREMENT_MS
        var maxSeekToPreviousPositionMs = C.DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS
        var livePlaybackSpeedControl: LivePlaybackSpeedControl =
            DefaultLivePlaybackSpeedControl.Builder().build()
        var releaseTimeoutMs = DEFAULT_RELEASE_TIMEOUT_MS
        var detachSurfaceTimeoutMs = DEFAULT_DETACH_SURFACE_TIMEOUT_MS
        var stuckBufferingDetectionTimeoutMs = DEFAULT_STUCK_BUFFERING_DETECTION_TIMEOUT_MS
        var stuckPlayingDetectionTimeoutMs = if (experimentalEnableStuckPlayingDetection)
            DEFAULT_STUCK_PLAYING_DETECTION_TIMEOUT_MS else Int.MAX_VALUE
        var stuckPlayingNotEndingTimeoutMs = if (experimentalEnableStuckPlayingDetection)
            DEFAULT_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS else Int.MAX_VALUE
        var stuckSuppressedDetectionTimeoutMs = DEFAULT_STUCK_SUPPRESSED_DETECTION_TIMEOUT_MS
        var pauseAtEndOfMediaItems: Boolean = false
        var usePlatformDiagnostics = true
        var playbackLooperProvider: PlaybackLooperProvider? = null
        var buildCalled: Boolean = false
        var suppressPlaybackOnUnsuitableOutput: Boolean = false
        var playerName: String = ""
        var dynamicSchedulingEnabled = true
        var suitableOutputChecker: SuitableOutputChecker? = DefaultSuitableOutputChecker()
        var enforceAdPlaybackOnTimelineRefresh = true
        var perStreamMediaProgressionEnabled: Boolean = false

        constructor(
            context: Context,
            renderersFactory: RenderersFactory = DefaultRenderersFactory(context),
            mediaSourceFactory: MediaSource.Factory = DefaultMediaSourceFactory(
                context, DefaultExtractorsFactory()
            ),
            trackSelector: TrackSelector = DefaultTrackSelector(context),
            loadControl: LoadControl = DefaultLoadControl(),
            bandwidthMeter: BandwidthMeter = DefaultBandwidthMeter.getSingletonInstance(context),
        ) : this(
            context,
            Supplier { renderersFactory },
            Supplier { mediaSourceFactory },
            Supplier { trackSelector },
            Supplier { loadControl },
            Supplier { bandwidthMeter },
        )

        fun setSuppressPlaybackOnUnsuitableOutput(suppressPlaybackOnUnsuitableOutput: Boolean): Builder {
            Preconditions.checkState(!buildCalled)
            this.suppressPlaybackOnUnsuitableOutput = suppressPlaybackOnUnsuitableOutput
            return this
        }

        fun setRenderersFactory(renderersFactory: RenderersFactory): Builder {
            Preconditions.checkState(!buildCalled)
            this.renderersFactorySupplier = Supplier { renderersFactory }
            return this
        }

        fun setMediaSourceFactory(mediaSourceFactory: MediaSource.Factory): Builder {
            Preconditions.checkState(!buildCalled)
            this.mediaSourceFactorySupplier = Supplier { mediaSourceFactory }
            return this
        }

        fun setTrackSelector(trackSelector: TrackSelector): Builder {
            Preconditions.checkState(!buildCalled)
            this.trackSelectorSupplier = Supplier { trackSelector }
            return this
        }

        fun setLoadControl(loadControl: LoadControl): Builder {
            Preconditions.checkState(!buildCalled)
            this.loadControlSupplier = Supplier { loadControl }
            return this
        }

        fun setBandwidthMeter(bandwidthMeter: BandwidthMeter): Builder {
            Preconditions.checkState(!buildCalled)
            Preconditions.checkArgument(bandwidthMeter !== BandwidthMeter.NO_OP)
            this.bandwidthMeterSupplier = Supplier { bandwidthMeter }
            return this
        }

        fun setLooper(looper: Looper): Builder {
            Preconditions.checkState(!buildCalled)
            this.looper = looper
            return this
        }

        fun setAnalyticsCollector(analyticsCollector: AnalyticsCollector): Builder {
            Preconditions.checkState(!buildCalled)
            this.analyticsCollectorFunction = Function { clock: Clock? -> analyticsCollector }
            return this
        }

        fun setAudioOutputProvider(audioOutputProvider: AudioOutputProvider?): Builder {
            Preconditions.checkState(!buildCalled)
            this.audioOutputProvider = audioOutputProvider!!
            return this
        }

        fun setPriority(priority: @C.Priority Int): Builder {
            Preconditions.checkState(!buildCalled)
            this.priority = priority
            return this
        }

        fun setPriorityTaskManager(priorityTaskManager: PriorityTaskManager?): Builder {
            Preconditions.checkState(!buildCalled)
            this.priorityTaskManager = priorityTaskManager
            return this
        }

        fun setAudioAttributes(
            audioAttributes: AudioAttributes,
            handleAudioFocus: Boolean
        ): Builder {
            Preconditions.checkState(!buildCalled)
            this.handleAudioFocus = handleAudioFocus
            return this
        }

        fun setWakeMode(@C.WakeMode wakeMode: @C.WakeMode Int): Builder {
            Preconditions.checkState(!buildCalled)
            this.wakeMode = wakeMode
            this.wakeModeSet = true
            return this
        }

        fun setHandleAudioBecomingNoisy(handleAudioBecomingNoisy: Boolean): Builder {
            Preconditions.checkState(!buildCalled)
            this.handleAudioBecomingNoisy = handleAudioBecomingNoisy
            return this
        }

        fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean): Builder {
            Preconditions.checkState(!buildCalled)
            this.skipSilenceEnabled = skipSilenceEnabled
            return this
        }

        fun setDeviceVolumeControlEnabled(deviceVolumeControlEnabled: Boolean): Builder {
            Preconditions.checkState(!buildCalled)
            this.deviceVolumeControlEnabled = deviceVolumeControlEnabled
            return this
        }

        fun setVideoScalingMode(videoScalingMode: @C.VideoScalingMode Int): Builder {
            Preconditions.checkState(!buildCalled)
            this.videoScalingMode = videoScalingMode
            return this
        }

        fun setVideoChangeFrameRateStrategy(videoChangeFrameRateStrategy: @C.VideoChangeFrameRateStrategy Int): Builder {
            Preconditions.checkState(!buildCalled)
            this.videoChangeFrameRateStrategy = videoChangeFrameRateStrategy
            return this
        }

        fun setUseLazyPreparation(useLazyPreparation: Boolean): Builder {
            Preconditions.checkState(!buildCalled)
            this.useLazyPreparation = useLazyPreparation
            return this
        }

        fun setSeekParameters(seekParameters: SeekParameters?): Builder {
            Preconditions.checkState(!buildCalled)
            this.seekParameters = seekParameters!!
            return this
        }

        fun setSeekBackIncrementMs(@IntRange(from = 1) seekBackIncrementMs: Long): Builder {
            Preconditions.checkArgument(seekBackIncrementMs > 0)
            Preconditions.checkState(!buildCalled)
            this.seekBackIncrementMs = seekBackIncrementMs
            return this
        }

        fun setSeekForwardIncrementMs(@IntRange(from = 1) seekForwardIncrementMs: Long): Builder {
            Preconditions.checkArgument(seekForwardIncrementMs > 0)
            Preconditions.checkState(!buildCalled)
            this.seekForwardIncrementMs = seekForwardIncrementMs
            return this
        }

        fun setMaxSeekToPreviousPositionMs(@IntRange(from = 0) maxSeekToPreviousPositionMs: Long): Builder {
            Preconditions.checkArgument(maxSeekToPreviousPositionMs >= 0L)
            Preconditions.checkState(!buildCalled)
            this.maxSeekToPreviousPositionMs = maxSeekToPreviousPositionMs
            return this
        }

        fun setScrubbingModeParameters(scrubbingModeParameters: ScrubbingModeParameters?): Builder {
            Preconditions.checkState(!buildCalled)
            this.scrubbingModeParameters = scrubbingModeParameters!!
            return this
        }

        fun setReleaseTimeoutMs(releaseTimeoutMs: Long): Builder {
            Preconditions.checkState(!buildCalled)
            this.releaseTimeoutMs = releaseTimeoutMs
            return this
        }

        fun setDetachSurfaceTimeoutMs(detachSurfaceTimeoutMs: Long): Builder {
            Preconditions.checkState(!buildCalled)
            this.detachSurfaceTimeoutMs = detachSurfaceTimeoutMs
            return this
        }

        fun setStuckBufferingDetectionTimeoutMs(stuckBufferingDetectionTimeoutMs: Int): Builder {
            Preconditions.checkState(!buildCalled)
            Preconditions.checkArgument(stuckBufferingDetectionTimeoutMs > 0)
            this.stuckBufferingDetectionTimeoutMs = stuckBufferingDetectionTimeoutMs
            return this
        }

        fun setStuckPlayingDetectionTimeoutMs(stuckPlayingDetectionTimeoutMs: Int): Builder {
            Preconditions.checkState(!buildCalled)
            Preconditions.checkArgument(stuckPlayingDetectionTimeoutMs > 0)
            this.stuckPlayingDetectionTimeoutMs = stuckPlayingDetectionTimeoutMs
            return this
        }

        fun setStuckPlayingNotEndingTimeoutMs(stuckPlayingNotEndingTimeoutMs: Int): Builder {
            Preconditions.checkState(!buildCalled)
            Preconditions.checkArgument(stuckPlayingNotEndingTimeoutMs > 0)
            this.stuckPlayingNotEndingTimeoutMs = stuckPlayingNotEndingTimeoutMs
            return this
        }

        fun setStuckSuppressedDetectionTimeoutMs(stuckSuppressedDetectionTimeoutMs: Int): Builder {
            Preconditions.checkState(!buildCalled)
            Preconditions.checkArgument(stuckSuppressedDetectionTimeoutMs > 0)
            this.stuckSuppressedDetectionTimeoutMs = stuckSuppressedDetectionTimeoutMs
            return this
        }

        fun setPauseAtEndOfMediaItems(pauseAtEndOfMediaItems: Boolean): Builder {
            Preconditions.checkState(!buildCalled)
            this.pauseAtEndOfMediaItems = pauseAtEndOfMediaItems
            return this
        }

        fun setEnforceAdPlaybackOnTimelineRefresh(
            enforceAdPlaybackOnTimelineRefresh: Boolean
        ): Builder {
            Preconditions.checkState(!buildCalled)
            this.enforceAdPlaybackOnTimelineRefresh = enforceAdPlaybackOnTimelineRefresh
            return this
        }

        fun setLivePlaybackSpeedControl(livePlaybackSpeedControl: LivePlaybackSpeedControl?): Builder {
            Preconditions.checkState(!buildCalled)
            this.livePlaybackSpeedControl = livePlaybackSpeedControl!!
            return this
        }

        fun setUsePlatformDiagnostics(usePlatformDiagnostics: Boolean): Builder {
            Preconditions.checkState(!buildCalled)
            this.usePlatformDiagnostics = usePlatformDiagnostics
            return this
        }

        fun setClock(clock: Clock): Builder {
            Preconditions.checkState(!buildCalled)
            this.clock = clock
            return this
        }

        fun setSuitableOutputChecker(suitableOutputChecker: SuitableOutputChecker?): Builder {
            Preconditions.checkState(!buildCalled)
            this.suitableOutputChecker = suitableOutputChecker
            return this
        }

        fun setPlaybackLooper(playbackLooper: Looper?): Builder {
            Preconditions.checkState(!buildCalled && playbackLooper != Looper.getMainLooper())
            this.playbackLooperProvider = PlaybackLooperProvider(playbackLooper)
            return this
        }

        fun setPlaybackLooperProvider(playbackLooperProvider: PlaybackLooperProvider?): Builder {
            Preconditions.checkState(!buildCalled)
            this.playbackLooperProvider = playbackLooperProvider
            return this
        }

        fun setName(playerName: String): Builder {
            Preconditions.checkState(!buildCalled)
            Preconditions.checkArgument(playerName != PlayerId.PRELOAD.name)
            this.playerName = playerName
            return this
        }

        fun build(): ExoPlayer {
            Preconditions.checkState(!buildCalled)
            buildCalled = true
            return ExoPlayerImpl( this,  null)
        }

        companion object {
            const val DEFAULT_RELEASE_TIMEOUT_MS: Long = 500
            const val DEFAULT_DETACH_SURFACE_TIMEOUT_MS: Long = 2000
            const val DEFAULT_STUCK_BUFFERING_DETECTION_TIMEOUT_MS: Int = 600000
            val DEFAULT_STUCK_PLAYING_DETECTION_TIMEOUT_MS: Int =
                if (isRunningOnEmulator) 30000 else 10000
            const val DEFAULT_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS: Int = 60000
            const val DEFAULT_STUCK_SUPPRESSED_DETECTION_TIMEOUT_MS: Int = 600000
            var experimentalEnableStuckPlayingDetection: Boolean = true
        }
    }

    override val playerError: PlaybackException?
    fun addAudioOffloadListener(listener: AudioOffloadListener?)
    fun removeAudioOffloadListener(listener: AudioOffloadListener?)
    val analyticsCollector: AnalyticsCollector?
    fun addAnalyticsListener(listener: AnalyticsListener)
    fun removeAnalyticsListener(listener: AnalyticsListener)
    val rendererCount: Int
    fun getRendererType(index: Int): @C.TrackType Int
    fun getRenderer(index: Int): Renderer?
    fun getSecondaryRenderer(index: Int): Renderer?
    val trackSelector: TrackSelector?
    val currentTrackGroups: TrackGroupArray?
    val currentTrackSelections: TrackSelectionArray?
    val playbackLooper: Looper?
    val clock: Clock?

    fun setMediaSources(mediaSources: List<MediaSource>)
    fun setMediaSources(mediaSources: List<MediaSource>, resetPosition: Boolean)
    fun setMediaSources(
        mediaSources: List<MediaSource>, startMediaItemIndex: Int, startPositionMs: Long
    )

    fun setMediaSource(mediaSource: MediaSource)
    fun setMediaSource(mediaSource: MediaSource, startPositionMs: Long)
    fun setMediaSource(mediaSource: MediaSource, resetPosition: Boolean)
    fun addMediaSource(mediaSource: MediaSource)
    fun addMediaSource(index: Int, mediaSource: MediaSource)
    fun addMediaSources(mediaSources: List<MediaSource>)
    fun addMediaSources(index: Int, mediaSources: List<MediaSource>)
    var shuffleOrder: ShuffleOrder
    var preloadConfiguration: PreloadConfiguration?
    override fun replaceMediaItem(index: Int, mediaItem: MediaItem)
    override fun replaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: List<MediaItem>
    )

    fun setAudioSessionId(audioSessionId: Int)
    fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo?)
    fun clearAuxEffectInfo()
    fun setPreferredAudioDevice(audioDeviceInfo: AudioDeviceInfo?)
    fun setVirtualDeviceId(virtualDeviceId: Int)
    var skipSilenceEnabled: Boolean
    var isScrubbingModeEnabled: Boolean
    var scrubbingModeParameters: ScrubbingModeParameters?
    fun setVideoEffects(videoEffects: List<Effect>?)
    var videoScalingMode: @C.VideoScalingMode Int
    var videoChangeFrameRateStrategy: @C.VideoChangeFrameRateStrategy Int
    fun setVideoFrameMetadataListener(listener: VideoFrameMetadataListener?)
    fun clearVideoFrameMetadataListener(listener: VideoFrameMetadataListener?)
    fun setCameraMotionListener(listener: CameraMotionListener?)
    fun clearCameraMotionListener(listener: CameraMotionListener?)
    fun createMessage(target: PlayerMessage.Target?): PlayerMessage?
    var seekParameters: SeekParameters?
    fun setSeekBackIncrementMs(@IntRange(from = 1) seekBackIncrementMs: Long)
    fun setSeekForwardIncrementMs(@IntRange(from = 1) seekForwardIncrementMs: Long)
    fun setMaxSeekToPreviousPositionMs(@IntRange(from = 0) maxSeekToPreviousPositionMs: Long)
    fun setForegroundMode(foregroundMode: Boolean)
    fun setEnforceAdPlaybackOnTimelineRefresh(enforceAdPlaybackOnTimelineRefresh: Boolean)
    var pauseAtEndOfMediaItems: Boolean
    val audioFormat: Format?
    val videoFormat: Format?
    val audioDecoderCounters: DecoderCounters?
    val videoDecoderCounters: DecoderCounters?
    fun setHandleAudioBecomingNoisy(handleAudioBecomingNoisy: Boolean)
    fun setWakeMode(@C.WakeMode wakeMode: @C.WakeMode Int)
    fun setPriority(priority: @C.Priority Int)
    fun setPriorityTaskManager(priorityTaskManager: PriorityTaskManager?)
    val isSleepingForOffload: Boolean
    val isTunnelingEnabled: Boolean
    override fun release()
    val isReleased: Boolean
    fun setImageOutput(imageOutput: ImageOutput?)
    fun setAudioCodecParameters(codecParameters: CodecParameters?)
    fun addAudioCodecParametersChangeListener(
        listener: CodecParametersChangeListener?, keys: List<String?>?
    )

    fun removeAudioCodecParametersChangeListener(listener: CodecParametersChangeListener?)
    fun setVideoCodecParameters(codecParameters: CodecParameters?)
    fun addVideoCodecParametersChangeListener(
        listener: CodecParametersChangeListener?, keys: List<String?>?
    )

    fun removeVideoCodecParametersChangeListener(listener: CodecParametersChangeListener?)
}
