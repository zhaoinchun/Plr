package androidx.media3.exoplayer

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioDeviceInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Pair
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.TextureView.SurfaceTextureListener
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.BasePlayer
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Effect
import androidx.media3.common.FlagSet
import androidx.media3.common.Format
import androidx.media3.common.IllegalSeekPositionException
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.MediaLibraryInfo.registerModule
import androidx.media3.common.MediaLibraryInfo.registeredModules
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.PriorityTaskManager
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.VideoSize
import androidx.media3.common.audio.AudioBecomingNoisyManager
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.BackgroundThreadStateHandler
import androidx.media3.common.util.Clock
import androidx.media3.common.util.ConditionVariable
import androidx.media3.common.util.HandlerWrapper
import androidx.media3.common.util.ListenerSet
import androidx.media3.common.util.Log
import androidx.media3.common.util.Size
import androidx.media3.common.util.StuckPlayerDetector
import androidx.media3.common.util.StuckPlayerException
import androidx.media3.common.util.Util
import androidx.media3.common.util.WakeLockManager
import androidx.media3.common.util.WifiLockManager
import androidx.media3.exoplayer.ExoPlayer.AudioOffloadListener
import androidx.media3.exoplayer.ExoPlayer.PreloadConfiguration
import androidx.media3.exoplayer.ExoPlayerImplInternal.PlaybackInfoUpdate
import androidx.media3.exoplayer.ExoPlayerImplInternal.PlaybackInfoUpdateListener
import androidx.media3.exoplayer.analytics.AnalyticsCollector
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.DefaultAnalyticsCollector
import androidx.media3.exoplayer.analytics.MediaMetricsListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig
import androidx.media3.exoplayer.image.ImageOutput
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.source.MaskingMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.exoplayer.source.TimelineWithUpdatedMediaItem
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.trackselection.TrackSelectionArray
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelectorResult
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.video.VideoDecoderOutputBufferRenderer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.video.spherical.CameraMotionListener
import androidx.media3.exoplayer.video.spherical.SphericalGLSurfaceView
import androidx.media3.exoplayer.video.spherical.SphericalGLSurfaceView.VideoSurfaceListener
import com.google.common.base.Preconditions
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.function.IntConsumer
import kotlin.math.max
import kotlin.math.min

// The constructor may be executed on a background thread. Wait with accessing the player from
// the app thread until the constructor finished executing.
internal class ExoPlayerImpl(builder: ExoPlayer.Builder, wrappingPlayer: Player?) : BasePlayer(),
    ExoPlayer {
    val permanentAvailableCommands: Player.Commands
    private val constructorFinished: ConditionVariable = ConditionVariable()
    private val applicationContext: Context = builder.context.applicationContext
    private val wrappingPlayer: Player = wrappingPlayer ?: this
    private val renderers: Array<Renderer>
    private val secondaryRenderers: Array<Renderer?>
    private val _trackSelector: TrackSelector = builder.trackSelectorSupplier.get()
    private val playbackInfoUpdateHandler: HandlerWrapper?
    private val playbackInfoUpdateListener: PlaybackInfoUpdateListener
    private val internalPlayer: ExoPlayerImplInternal
    private val listeners: ListenerSet<Player.Listener>
    private val audioOffloadListeners: CopyOnWriteArraySet<AudioOffloadListener> =
        CopyOnWriteArraySet<AudioOffloadListener>()
    private val period: Timeline.Period = Timeline.Period()
    private val mediaSourceHolderSnapshots = mutableListOf<MediaSourceHolderSnapshot>()
    private val useLazyPreparation: Boolean = builder.useLazyPreparation
    private val mediaSourceFactory: MediaSource.Factory = builder.mediaSourceFactorySupplier.get()
    private val _analyticsCollector: AnalyticsCollector =
        builder.analyticsCollectorFunction.apply(builder.clock)
    override val applicationLooper: Looper = builder.looper
    private val bandwidthMeter: BandwidthMeter = builder.bandwidthMeterSupplier.get()
    override val clock: Clock = builder.clock
    private val componentListener: ComponentListener = ComponentListener()
    private val frameMetadataListener: FrameMetadataListener = FrameMetadataListener()
    private val audioBecomingNoisyManager: AudioBecomingNoisyManager
    private val streamVolumeManager: StreamVolumeManager?
    private val wakeLockManager: WakeLockManager
    private val wifiLockManager: WifiLockManager
    private val detachSurfaceTimeoutMs: Long = builder.detachSurfaceTimeoutMs
    private val suitableOutputChecker: SuitableOutputChecker?
    private val audioSessionIdState: BackgroundThreadStateHandler<Int>
    private val stuckPlayerDetector: StuckPlayerDetector
    private val virtualDeviceIdChangeListener: VirtualDeviceIdChangeListener?
    private val audioListenerManager = CodecParameterListenerManager(C.TRACK_TYPE_AUDIO)
    private val videoListenerManager = CodecParameterListenerManager(C.TRACK_TYPE_VIDEO)
    val emptyTrackSelectorResult: TrackSelectorResult
    private var _repeatMode: @Player.RepeatMode Int = 0
    private var _shuffleModeEnabled = false
    private var pendingOperationAcks = 0
    private var pendingDiscontinuityReason: @Player.DiscontinuityReason Int = 0
    private var pendingDiscontinuity = false
    private var foregroundMode = false
    private var scrubbingModeEnabled = false
    private var disabledTrackTypesWithoutScrubbingMode: MutableSet<Int>? = null
    private var _scrubbingModeParameters: ScrubbingModeParameters? = builder.scrubbingModeParameters
    private var _seekParameters: SeekParameters? = builder.seekParameters
    private var _shuffleOrder: ShuffleOrder = DefaultShuffleOrder(0)
    private var _preloadConfiguration: PreloadConfiguration? = PreloadConfiguration.DEFAULT
    private var _pauseAtEndOfMediaItems = builder.pauseAtEndOfMediaItems
    private var enforceAdPlaybackOnTimelineRefresh = builder.enforceAdPlaybackOnTimelineRefresh
    private var _availableCommands: Player.Commands? = null
    private var _mediaMetadata: MediaMetadata? = null
    private var _playlistMetadata: MediaMetadata? = null
    private var _videoFormat: Format? = null
    private var _audioFormat: Format? = null
    private var videoOutput: Any? = null
    private var ownedSurface: Surface? = null
    private var surfaceHolder: SurfaceHolder? = null
    private var sphericalGLSurfaceView: SphericalGLSurfaceView? = null
    private var surfaceHolderSurfaceIsVideoOutput = false
    private var textureView: TextureView? = null
    private var _videoScalingMode: @C.VideoScalingMode Int = builder.videoScalingMode
    private var _videoChangeFrameRateStrategy: @C.VideoChangeFrameRateStrategy Int =
        builder.videoChangeFrameRateStrategy
    private var _surfaceSize: Size? = Size.UNKNOWN
    private var _videoDecoderCounters: DecoderCounters? = null
    private var _audioDecoderCounters: DecoderCounters? = null
    private var _audioAttributes: AudioAttributes? = builder.audioAttributes
    private var _volume = 0f
    private var unmuteVolume = 0f
    private var _skipSilenceEnabled = builder.skipSilenceEnabled
    private var currentCueGroup: CueGroup? = null
    private var videoFrameMetadataListener: VideoFrameMetadataListener? = null
    private var cameraMotionListener: CameraMotionListener? = null
    private var throwsWhenUsingWrongThread = false
    private var hasNotifiedFullWrongThreadWarning = false
    private var priority: @C.Priority Int = builder.priority
    private var priorityTaskManager: PriorityTaskManager? = builder.priorityTaskManager
    private var isPriorityTaskManagerRegistered = false
    private var playerReleased = false
    private var _deviceInfo: DeviceInfo? = DeviceInfo.UNKNOWN
    override var videoSize: VideoSize? = VideoSize.UNKNOWN
    private var seekBackIncrementMs: Long = builder.seekBackIncrementMs
    private var seekForwardIncrementMs: Long = builder.seekForwardIncrementMs
    private var maxSeekToPreviousPositionMs: Long = builder.maxSeekToPreviousPositionMs
    private var staticAndDynamicMediaMetadata: MediaMetadata? = null
    private var playbackInfo: PlaybackInfo
    private var maskingWindowIndex = 0
    private var maskingWindowPositionMs: Long = 0

    init {
        val eventHandler = Handler(builder.looper)
        val renderersFactory = builder.renderersFactorySupplier.get()
        renderers =
            renderersFactory.createRenderers(
                eventHandler,
                componentListener,
                componentListener,
                componentListener,
                componentListener
            )
        emptyTrackSelectorResult = TrackSelectorResult(
            arrayOfNulls(renderers.size),
            arrayOfNulls(renderers.size),
            Tracks.EMPTY,
            null
        )

        Preconditions.checkState(renderers.isNotEmpty())
        secondaryRenderers = Array<Renderer?>(renderers.size) {
            renderersFactory.createSecondaryRenderer(
                renderers[it],
                eventHandler,
                componentListener,
                componentListener,
                componentListener,
                componentListener
            )
        }
        listeners =
            ListenerSet(builder.looper, clock) { listener: Player.Listener, flags: FlagSet? ->
                listener.onEvents(
                    this.wrappingPlayer,
                    Player.Events(flags!!)
                )
            }

        Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SET_SPEED_AND_PITCH,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_SET_PLAYLIST_METADATA,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_GET_TRACKS,
                Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                Player.COMMAND_SET_AUDIO_ATTRIBUTES,
                Player.COMMAND_GET_VOLUME,
                Player.COMMAND_SET_VOLUME,
                Player.COMMAND_SET_VIDEO_SURFACE,
                Player.COMMAND_GET_TEXT,
                Player.COMMAND_RELEASE
            )
            .addIf(
                Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
                _trackSelector.isSetParametersSupported
            )
            .addIf(Player.COMMAND_GET_DEVICE_VOLUME, builder.deviceVolumeControlEnabled)
            .addIf(Player.COMMAND_SET_DEVICE_VOLUME, builder.deviceVolumeControlEnabled)
            .addIf(
                Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                builder.deviceVolumeControlEnabled
            )
            .addIf(Player.COMMAND_ADJUST_DEVICE_VOLUME, builder.deviceVolumeControlEnabled)
            .addIf(
                Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
                builder.deviceVolumeControlEnabled
            )
            .build().also { permanentAvailableCommands = it }
        _availableCommands =
            Player.Commands.Builder()
                .addAll(permanentAvailableCommands)
                .add(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
                .add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
                .build()
        playbackInfoUpdateHandler = clock.createHandler(applicationLooper, null)
        playbackInfoUpdateListener =
            PlaybackInfoUpdateListener { playbackInfoUpdate: PlaybackInfoUpdate ->
                playbackInfoUpdateHandler.post { handlePlaybackInfo(playbackInfoUpdate) }
            }
        playbackInfo = PlaybackInfo.createDummy(emptyTrackSelectorResult)
        _analyticsCollector.setPlayer(this.wrappingPlayer, applicationLooper)
        val playerId = PlayerId(builder.playerName)
        internalPlayer =
            ExoPlayerImplInternal(
                applicationContext,
                renderers,
                secondaryRenderers,
                _trackSelector,
                emptyTrackSelectorResult,
                builder.loadControlSupplier.get(),
                bandwidthMeter,
                _repeatMode,
                _shuffleModeEnabled,
                _analyticsCollector,
                _seekParameters,
                builder.livePlaybackSpeedControl,
                builder.releaseTimeoutMs,
                _pauseAtEndOfMediaItems,
                builder.dynamicSchedulingEnabled,
                builder.perStreamMediaProgressionEnabled,
                applicationLooper,
                clock,
                playbackInfoUpdateListener,
                playerId,
                builder.playbackLooperProvider,
                _preloadConfiguration!!,
                frameMetadataListener,
                builder.enforceAdPlaybackOnTimelineRefresh
            )
        val playbackLooper = internalPlayer.playbackLooper

        _volume = 1f
        _repeatMode = Player.REPEAT_MODE_OFF
        _mediaMetadata = MediaMetadata.EMPTY
        _playlistMetadata = MediaMetadata.EMPTY
        staticAndDynamicMediaMetadata = MediaMetadata.EMPTY
        maskingWindowIndex = C.INDEX_UNSET
        currentCueGroup = CueGroup.EMPTY_TIME_ZERO
        throwsWhenUsingWrongThread = true

        addListener(_analyticsCollector)
        bandwidthMeter.addEventListener(Handler(applicationLooper), _analyticsCollector)
        addAudioOffloadListener(componentListener)
        if (builder.foregroundModeTimeoutMs > 0) {
            internalPlayer.experimentalSetForegroundModeTimeoutMs(builder.foregroundModeTimeoutMs)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            Api31.registerMediaMetricsListener(
                applicationContext, this, builder.usePlatformDiagnostics, playerId
            )
        }

        audioSessionIdState =
            BackgroundThreadStateHandler(
                C.AUDIO_SESSION_ID_UNSET,
                playbackLooper,
                applicationLooper,
                clock
            ) { oldAudioSessionId: Int, newAudioSessionId: Int ->
                onAudioSessionIdChanged(oldAudioSessionId, newAudioSessionId)
            }
        audioSessionIdState.runInBackground {
            val newAudioSessionId = Util.generateAudioSessionId(applicationContext)
            if (audioSessionIdState.get() != newAudioSessionId) {
                audioSessionIdState.setStateInBackground(newAudioSessionId)
                // Provide the audio session ID to the renderers on playback thread to prevent race
                // condition with player preparation.
                internalPlayer.setAudioSessionId(
                    newAudioSessionId, true
                )
            }
        }
        audioBecomingNoisyManager =
            AudioBecomingNoisyManager(
                builder.context, playbackLooper, builder.looper, componentListener, clock
            )
        audioBecomingNoisyManager.setEnabled(builder.handleAudioBecomingNoisy)

        if (builder.suppressPlaybackOnUnsuitableOutput) {
            suitableOutputChecker = builder.suitableOutputChecker
            suitableOutputChecker!!.enable(
                { isSelectedOutputSuitableForPlayback: Boolean ->
                    onSelectedOutputSuitabilityChanged(
                        isSelectedOutputSuitableForPlayback
                    )
                },
                applicationContext,
                applicationLooper,
                playbackLooper,
                clock
            )
        } else {
            suitableOutputChecker = null
        }

        streamVolumeManager = if (builder.deviceVolumeControlEnabled) {
            StreamVolumeManager(
                builder.context,
                componentListener,
                _audioAttributes!!.volumeControlStream,
                playbackLooper,
                applicationLooper,
                clock
            )
        } else {
            null
        }
        var wakeMode = builder.wakeMode
        if (!builder.wakeModeSet) {
            wakeMode =
                if (builder.stuckBufferingDetectionTimeoutMs == Int.MAX_VALUE || builder.stuckPlayingDetectionTimeoutMs == Int.MAX_VALUE || builder.stuckPlayingNotEndingTimeoutMs == Int.MAX_VALUE || builder.stuckSuppressedDetectionTimeoutMs == Int.MAX_VALUE)
                    C.WAKE_MODE_NONE
                else
                    C.WAKE_MODE_LOCAL
        }
        wakeLockManager = WakeLockManager(builder.context, playbackLooper, clock)
        wakeLockManager.setEnabled(wakeMode != C.WAKE_MODE_NONE)
        wifiLockManager = WifiLockManager(builder.context, playbackLooper, clock)
        wifiLockManager.setEnabled(wakeMode == C.WAKE_MODE_NETWORK)
        virtualDeviceIdChangeListener =
            if (Build.VERSION.SDK_INT >= 34) VirtualDeviceIdChangeListener(builder.context) else null

        stuckPlayerDetector =
            StuckPlayerDetector(
                this,
                componentListener,
                clock,
                builder.stuckBufferingDetectionTimeoutMs,
                builder.stuckPlayingDetectionTimeoutMs,
                builder.stuckPlayingNotEndingTimeoutMs,
                builder.stuckSuppressedDetectionTimeoutMs
            )

        internalPlayer.setScrubbingModeParameters(_scrubbingModeParameters)
        internalPlayer.setAudioAttributes(_audioAttributes, builder.handleAudioFocus)
        sendRendererMessage(
            C.TRACK_TYPE_AUDIO,
            Renderer.MSG_SET_AUDIO_ATTRIBUTES,
            _audioAttributes
        )
        sendRendererMessage(C.TRACK_TYPE_VIDEO, Renderer.MSG_SET_SCALING_MODE, _videoScalingMode)
        sendRendererMessage(
            C.TRACK_TYPE_VIDEO,
            Renderer.MSG_SET_CHANGE_FRAME_RATE_STRATEGY,
            _videoChangeFrameRateStrategy
        )
        sendRendererMessage(
            C.TRACK_TYPE_AUDIO,
            Renderer.MSG_SET_SKIP_SILENCE_ENABLED,
            _skipSilenceEnabled
        )
        sendRendererMessage(
            C.TRACK_TYPE_CAMERA_MOTION,
            Renderer.MSG_SET_CAMERA_MOTION_LISTENER,
            frameMetadataListener
        )
        sendRendererMessage(Renderer.MSG_SET_PRIORITY, priority)
        if (builder.audioOutputProvider != null) {
            sendRendererMessage(
                C.TRACK_TYPE_AUDIO,
                Renderer.MSG_SET_AUDIO_OUTPUT_PROVIDER,
                builder.audioOutputProvider
            )
        }
        Log.i(
            TAG,
            ("Init "
                    + Integer.toHexString(System.identityHashCode(this))
                    + " ["
                    + MediaLibraryInfo.VERSION_SLASHY
                    + "] ["
                    + Util.DEVICE_DEBUG_INFO
                    + "]")
        )
        constructorFinished.open()
    }

    override var videoScalingMode: @C.VideoScalingMode Int
        get() = _videoScalingMode
        set(value) {
            _videoScalingMode = value
            sendRendererMessage(
                C.TRACK_TYPE_VIDEO,
                Renderer.MSG_SET_SCALING_MODE,
                _videoScalingMode
            )
        }
    override var videoChangeFrameRateStrategy: @C.VideoChangeFrameRateStrategy Int
        get() = _videoChangeFrameRateStrategy
        set(value) {
            if (this._videoChangeFrameRateStrategy == value) {
                return
            }
            _videoChangeFrameRateStrategy = value
            sendRendererMessage(
                C.TRACK_TYPE_VIDEO,
                Renderer.MSG_SET_CHANGE_FRAME_RATE_STRATEGY,
                value
            )
        }
    override val videoFormat: Format?
        get() = _videoFormat
    override val audioFormat: Format?
        get() = _audioFormat
    override val availableCommands: Player.Commands?
        get() = _availableCommands
    override val mediaMetadata: MediaMetadata?
        get() = _mediaMetadata
    override var playlistMetadata: MediaMetadata?
        get() = _playlistMetadata
        set(value) {
            value!!
            if (value == this._playlistMetadata) {
                return
            }
            _playlistMetadata = value
            listeners.sendEvent(
                Player.EVENT_PLAYLIST_METADATA_CHANGED
            ) { listener: Player.Listener ->
                listener.onPlaylistMetadataChanged(
                    _playlistMetadata!!
                )
            }
        }
    override var preloadConfiguration: PreloadConfiguration?
        get() = _preloadConfiguration
        set(value) {
            if (_preloadConfiguration == value) {
                return
            }
            _preloadConfiguration = value
            internalPlayer.setPreloadConfiguration(value)
        }
    override var pauseAtEndOfMediaItems: Boolean
        get() = _pauseAtEndOfMediaItems
        set(value) {
            if (this._pauseAtEndOfMediaItems == value) {
                return
            }
            _pauseAtEndOfMediaItems = value
            internalPlayer.setPauseAtEndOfWindow(value)
        }
    override var seekParameters: SeekParameters?
        get() = _seekParameters
        set(value) {
            var seekParameters = value

            if (seekParameters == null) {
                seekParameters = SeekParameters.DEFAULT
            }
            if (this._seekParameters != seekParameters) {
                _seekParameters = seekParameters
                internalPlayer.setSeekParameters(seekParameters)
            }
        }
    override var shuffleOrder: ShuffleOrder
        get() = _shuffleOrder
        set(value) {
            Preconditions.checkArgument(value.length == mediaSourceHolderSnapshots.size)
            _shuffleOrder = value
            val timeline = createMaskingTimeline()
            val newPlaybackInfo =
                maskTimelineAndPosition(
                    playbackInfo,
                    timeline,
                    maskWindowPositionMsOrGetPeriodPositionUs(
                        timeline, currentMediaItemIndex, currentPosition
                    )
                )
            pendingOperationAcks++
            internalPlayer.setShuffleOrder(value)
            updatePlaybackInfo(
                newPlaybackInfo,
                Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
                false,
                Player.DISCONTINUITY_REASON_INTERNAL,
                C.TIME_UNSET,
                C.INDEX_UNSET,
                false
            )
        }
    override var scrubbingModeParameters: ScrubbingModeParameters?
        get() = _scrubbingModeParameters
        set(value) {
            if (_scrubbingModeParameters == value) {
                return
            }
            val previousParameters = _scrubbingModeParameters
            _scrubbingModeParameters = value
            internalPlayer.setScrubbingModeParameters(value)
            if (scrubbingModeEnabled
                && _trackSelector.isSetParametersSupported
                && (previousParameters!!.disabledTrackTypes != value!!.disabledTrackTypes)
            ) {
                // We are already in scrubbing mode, but the tracks we should disable while scrubbing have
                // changed, so we need to re-calculate the track selection parameters.
                val trackSelectionParameters: TrackSelectionParameters =
                    addDisabledTrackTypes(
                        trackSelectionParameters!!, value.disabledTrackTypes
                    )
                if (trackSelectionParameters != _trackSelector.parameters) {
                    // Set the parameters directly, to avoid firing onTrackSelectionParametersChanged.
                    _trackSelector.parameters = trackSelectionParameters
                }
            }
        }
    override var shuffleModeEnabled: Boolean
        get() = _shuffleModeEnabled
        set(value) {
            if (this._shuffleModeEnabled != value) {
                _shuffleModeEnabled = value
                internalPlayer.setShuffleModeEnabled(value)
                listeners.queueEvent(
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED
                ) { listener: Player.Listener ->
                    listener.onShuffleModeEnabledChanged(
                        value
                    )
                }
                updateAvailableCommands()
                listeners.flushEvents()
            }
        }
    override var repeatMode: @Player.RepeatMode Int
        get() = _repeatMode
        set(value) {
            if (this._repeatMode != value) {
                _repeatMode = value
                internalPlayer.setRepeatMode(value)
                listeners.queueEvent(
                    Player.EVENT_REPEAT_MODE_CHANGED
                ) { listener: Player.Listener ->
                    listener.onRepeatModeChanged(value)
                }
                updateAvailableCommands()
                listeners.flushEvents()
            }
        }
    override val trackSelector: TrackSelector
        get() = _trackSelector
    override val analyticsCollector: AnalyticsCollector
        get() {
            return _analyticsCollector
        }
    override val surfaceSize: Size?
        get() = _surfaceSize
    override val videoDecoderCounters: DecoderCounters?
        get() = _videoDecoderCounters
    override val audioDecoderCounters: DecoderCounters?
        get() = _audioDecoderCounters
    override val audioAttributes: AudioAttributes?
        get() = _audioAttributes
    override var volume: Float
        get() = _volume
        set(value) {
            val volume = Util.constrainValue(value, 0f, 1f)
            if (_volume == volume) {
                return
            }
            unmuteVolume = if (volume != 0f) volume else this._volume
            _volume = value
            internalPlayer.setVolume(volume)
            val finalVolume = volume
            listeners.sendEvent(
                Player.EVENT_VOLUME_CHANGED
            ) { listener: Player.Listener -> listener.onVolumeChanged(finalVolume) }
        }
    override val deviceInfo: DeviceInfo?
        get() = _deviceInfo
    override var skipSilenceEnabled: Boolean
        get() = _skipSilenceEnabled
        set(value) {
            if (_skipSilenceEnabled == value) {
                return
            }
            _skipSilenceEnabled = value
            sendRendererMessage(
                C.TRACK_TYPE_AUDIO,
                Renderer.MSG_SET_SKIP_SILENCE_ENABLED,
                value
            )
            listeners.sendEvent(
                Player.EVENT_SKIP_SILENCE_ENABLED_CHANGED
            ) { listener: Player.Listener ->
                listener.onSkipSilenceEnabledChanged(
                    value
                )
            }
        }
    override val isSleepingForOffload: Boolean
        get() = playbackInfo.sleepingForOffload
    override var isScrubbingModeEnabled: Boolean
        get() = scrubbingModeEnabled
        set(value) {
            if (value == this.scrubbingModeEnabled) {
                return
            }
            this.scrubbingModeEnabled = value
            if (!_scrubbingModeParameters!!.disabledTrackTypes.isEmpty()
                && _trackSelector.isSetParametersSupported
            ) {
                val previousTrackSelectionParameters = _trackSelector.parameters
                val newTrackSelectionParameters: TrackSelectionParameters
                if (value) {
                    this.disabledTrackTypesWithoutScrubbingMode =
                        previousTrackSelectionParameters!!.disabledTrackTypes.toMutableSet()
                    newTrackSelectionParameters =
                        addDisabledTrackTypes(
                            previousTrackSelectionParameters,
                            _scrubbingModeParameters!!.disabledTrackTypes
                        )
                } else {
                    newTrackSelectionParameters =
                        previousTrackSelectionParameters!!
                            .buildUpon()
                            .setDisabledTrackTypes(disabledTrackTypesWithoutScrubbingMode!!)
                            .build()
                    this.disabledTrackTypesWithoutScrubbingMode = null
                }
                // Set the parameters directly, to avoid firing onTrackSelectionParametersChanged (because
                // the return value of getTrackSelectionParameters won't change).
                if (newTrackSelectionParameters != previousTrackSelectionParameters) {
                    _trackSelector.parameters = newTrackSelectionParameters
                }
            }
            internalPlayer.setScrubbingModeEnabled(value)
            maybeUpdatePlaybackSuppressionReason()
        }

    override val playbackLooper: Looper
        get() = internalPlayer.playbackLooper

    override fun addAudioOffloadListener(listener: AudioOffloadListener?) {
        // Don't verify application thread. We allow calls to this method from any thread.
        audioOffloadListeners.add(listener)
    }

    override fun removeAudioOffloadListener(listener: AudioOffloadListener?) {
        audioOffloadListeners.remove(listener)
    }

    override val playbackState: @Player.State Int
        get() = playbackInfo.playbackState

    override val playbackSuppressionReason: @Player.PlaybackSuppressionReason Int
        get() = playbackInfo.playbackSuppressionReason

    override val playerError: PlaybackException?
        get() = playbackInfo.playbackError

    override fun prepare() {
        if (playbackInfo.playbackState != Player.STATE_IDLE) {
            return
        }
        var playbackInfo = playbackInfo.copyWithPlaybackError(null)
        playbackInfo =
            maskPlaybackState(
                playbackInfo,
                if (playbackInfo.timeline.isEmpty) Player.STATE_ENDED else Player.STATE_BUFFERING
            )
        // Trigger internal prepare first before updating the playback info and notifying external
        // listeners to ensure that new operations issued in the listener notifications reach the
        // player after this prepare. The internal player can't change the playback info immediately
        // because it uses a callback.
        pendingOperationAcks++
        internalPlayer.prepare()
        updatePlaybackInfo(
            playbackInfo,
            Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE,
            false,
            Player.DISCONTINUITY_REASON_INTERNAL,
            C.TIME_UNSET,
            C.INDEX_UNSET,
            false
        )
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        setMediaSources(createMediaSources(mediaItems), resetPosition)
    }

    override fun setMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ) {
        setMediaSources(createMediaSources(mediaItems), startIndex, startPositionMs)
    }

    override fun setMediaSource(mediaSource: MediaSource) {
        setMediaSources(listOf(mediaSource))
    }

    override fun setMediaSource(mediaSource: MediaSource, startPositionMs: Long) {
        setMediaSources(listOf(mediaSource), 0, startPositionMs)
    }

    override fun setMediaSource(mediaSource: MediaSource, resetPosition: Boolean) {
        setMediaSources(listOf(mediaSource), resetPosition)
    }

    override fun setMediaSources(mediaSources: List<MediaSource>) {
        setMediaSources(mediaSources, true)
    }

    override fun setMediaSources(mediaSources: List<MediaSource>, resetPosition: Boolean) {
        setMediaSourcesInternal(
            mediaSources,
            C.INDEX_UNSET,
            C.TIME_UNSET,
            resetPosition
        )
    }

    override fun setMediaSources(
        mediaSources: List<MediaSource>, startMediaItemIndex: Int, startPositionMs: Long
    ) {
        setMediaSourcesInternal(
            mediaSources, startMediaItemIndex, startPositionMs, false
        )
    }

    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) {
        addMediaSources(index, createMediaSources(mediaItems))
    }

    override fun addMediaSource(mediaSource: MediaSource) {
        addMediaSources(listOf(mediaSource))
    }

    override fun addMediaSource(index: Int, mediaSource: MediaSource) {
        addMediaSources(index, listOf(mediaSource))
    }

    override fun addMediaSources(mediaSources: List<MediaSource>) {
        addMediaSources(mediaSourceHolderSnapshots.size, mediaSources)
    }

    override fun addMediaSources(index: Int, mediaSources: List<MediaSource>) {
        var index = index
        Preconditions.checkArgument(index >= 0)
        index = min(index, mediaSourceHolderSnapshots.size)
        if (playbackInfo.timeline.isEmpty) {
            // Handle initial items in a playlist as a set operation to ensure state changes and initial
            // position are updated correctly.
            setMediaSources(mediaSources, maskingWindowIndex == C.INDEX_UNSET)
            return
        }
        val newPlaybackInfo = addMediaSourcesInternal(playbackInfo, index, mediaSources)
        updatePlaybackInfo(
            newPlaybackInfo,
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
            false,
            Player.DISCONTINUITY_REASON_INTERNAL,
            C.TIME_UNSET,
            C.INDEX_UNSET,
            false
        )
    }

    override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
        var toIndex = toIndex
        Preconditions.checkArgument(fromIndex in 0..toIndex)
        val playlistSize = mediaSourceHolderSnapshots.size
        toIndex = min(toIndex, playlistSize)
        if (fromIndex >= playlistSize || fromIndex == toIndex) {
            // Do nothing.
            return
        }
        val newPlaybackInfo = removeMediaItemsInternal(playbackInfo, fromIndex, toIndex)
        val positionDiscontinuity =
            newPlaybackInfo.periodId.periodUid != playbackInfo.periodId.periodUid
        updatePlaybackInfo(
            newPlaybackInfo,
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
            positionDiscontinuity,
            Player.DISCONTINUITY_REASON_REMOVE,
            getCurrentPositionUsInternal(newPlaybackInfo),
            C.INDEX_UNSET,
            false
        )
    }

    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) {
        var toIndex = toIndex
        var newFromIndex = newIndex
        Preconditions.checkArgument(fromIndex in 0..toIndex && newFromIndex >= 0)
        val playlistSize = mediaSourceHolderSnapshots.size
        toIndex = min(toIndex, playlistSize)
        newFromIndex = min(newFromIndex, playlistSize - (toIndex - fromIndex))
        if (fromIndex >= playlistSize || fromIndex == toIndex || fromIndex == newFromIndex) {
            // Do nothing.
            return
        }
        val oldTimeline = currentTimeline
        pendingOperationAcks++
        Util.moveItems<MediaSourceHolderSnapshot>(
            mediaSourceHolderSnapshots,
            fromIndex,
            toIndex,
            newFromIndex
        )
        _shuffleOrder = _shuffleOrder.cloneAndMove(fromIndex, toIndex, newFromIndex)
        val newTimeline = createMaskingTimeline()
        val newPlaybackInfo =
            maskTimelineAndPosition(
                playbackInfo,
                newTimeline,
                getPeriodPositionUsAfterTimelineChanged(
                    oldTimeline,
                    newTimeline,
                    getCurrentWindowIndexInternal(playbackInfo),
                    getContentPositionInternal(playbackInfo)
                )
            )
        internalPlayer.moveMediaSources(fromIndex, toIndex, newFromIndex, _shuffleOrder)
        updatePlaybackInfo(
            newPlaybackInfo,
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
            false,
            Player.DISCONTINUITY_REASON_INTERNAL,
            C.TIME_UNSET,
            C.INDEX_UNSET,
            false
        )
    }

    override fun replaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: List<MediaItem>
    ) {
        var toIndex = toIndex
        Preconditions.checkArgument(fromIndex in 0..toIndex)
        val playlistSize = mediaSourceHolderSnapshots.size
        if (fromIndex > playlistSize) {
            // Do nothing.
            return
        }
        toIndex = min(toIndex, playlistSize)
        if (canUpdateMediaSourcesWithMediaItems(fromIndex, toIndex, mediaItems)) {
            // Update MediaSources directly without creating new ones if possible.
            updateMediaSourcesWithMediaItems(fromIndex, toIndex, mediaItems)
            return
        }
        val mediaSources = createMediaSources(mediaItems)
        if (playbackInfo.timeline.isEmpty) {
            // Handle initial items in a playlist as a set operation to ensure state changes and initial
            // position are updated correctly.
            setMediaSources(mediaSources, maskingWindowIndex == C.INDEX_UNSET)
            return
        }
        var newPlaybackInfo = addMediaSourcesInternal(playbackInfo, toIndex, mediaSources)
        newPlaybackInfo = removeMediaItemsInternal(newPlaybackInfo, fromIndex, toIndex)
        val positionDiscontinuity =
            newPlaybackInfo.periodId.periodUid != playbackInfo.periodId.periodUid
        updatePlaybackInfo(
            newPlaybackInfo,
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
            positionDiscontinuity,
            Player.DISCONTINUITY_REASON_REMOVE,
            getCurrentPositionUsInternal(newPlaybackInfo),
            C.INDEX_UNSET,
            false
        )
    }

    override fun setEnforceAdPlaybackOnTimelineRefresh(enforceAdPlaybackOnTimelineRefresh: Boolean) {
        if (this.enforceAdPlaybackOnTimelineRefresh == enforceAdPlaybackOnTimelineRefresh) {
            return
        }
        this.enforceAdPlaybackOnTimelineRefresh = enforceAdPlaybackOnTimelineRefresh
        internalPlayer.setEnforceAdPlaybackOnTimelineRefresh(enforceAdPlaybackOnTimelineRefresh)
    }

    override var playWhenReady: Boolean
        get() = playbackInfo.playWhenReady
        set(value) {
            updatePlayWhenReady(value, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        }

    override val isLoading: Boolean
        get() = playbackInfo.isLoading

    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: @Player.Command Int,
        isRepeatingCurrentItem: Boolean
    ) {
        if (mediaItemIndex == C.INDEX_UNSET) {
            return
        }
        Preconditions.checkArgument(mediaItemIndex >= 0)
        val timeline = playbackInfo.timeline
        if (!timeline.isEmpty && mediaItemIndex >= timeline.windowCount) {
            return
        }
        _analyticsCollector.notifySeekStarted()
        pendingOperationAcks++
        if (isPlayingAd) {
            Log.w(TAG, "seekTo ignored because an ad is playing")
            val playbackInfoUpdate =
                PlaybackInfoUpdate(this.playbackInfo)
            playbackInfoUpdate.incrementPendingOperationAcks(1)
            playbackInfoUpdateListener.onPlaybackInfoUpdate(playbackInfoUpdate)
            return
        }
        var newPlaybackInfo = playbackInfo
        if (playbackInfo.playbackState == Player.STATE_READY
            || (playbackInfo.playbackState == Player.STATE_ENDED && !timeline.isEmpty)
        ) {
            newPlaybackInfo = maskPlaybackState(playbackInfo, Player.STATE_BUFFERING)
        }
        val oldMaskingMediaItemIndex = currentMediaItemIndex
        newPlaybackInfo =
            maskTimelineAndPosition(
                newPlaybackInfo,
                timeline,
                maskWindowPositionMsOrGetPeriodPositionUs(timeline, mediaItemIndex, positionMs)
            )
        internalPlayer.seekTo(timeline, mediaItemIndex, Util.msToUs(positionMs))
        updatePlaybackInfo(
            newPlaybackInfo,
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
            true,
            Player.DISCONTINUITY_REASON_SEEK,
            getCurrentPositionUsInternal(newPlaybackInfo),
            oldMaskingMediaItemIndex,
            isRepeatingCurrentItem
        )
    }

    override val seekBackIncrement: Long
        get() = seekBackIncrementMs
    override val seekForwardIncrement: Long
        get() = seekForwardIncrementMs

    override val maxSeekToPreviousPosition: Long
        get() = maxSeekToPreviousPositionMs

    override var playbackParameters: PlaybackParameters?
        get() = playbackInfo.playbackParameters
        set(value) {
            var playbackParameters = value
            if (playbackParameters == null) {
                playbackParameters = PlaybackParameters.DEFAULT
            }
            if (playbackInfo.playbackParameters == playbackParameters) {
                return
            }
            val newPlaybackInfo = playbackInfo.copyWithPlaybackParameters(playbackParameters)
            pendingOperationAcks++
            internalPlayer.setPlaybackParameters(playbackParameters)
            updatePlaybackInfo(
                newPlaybackInfo,
                Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
                false,
                Player.DISCONTINUITY_REASON_INTERNAL,
                C.TIME_UNSET,
                C.INDEX_UNSET,
                false
            )
        }

    override fun setMaxSeekToPreviousPositionMs(maxSeekToPreviousPositionMs: Long) {
        Preconditions.checkArgument(maxSeekToPreviousPositionMs >= 0)
        if (this.maxSeekToPreviousPositionMs == maxSeekToPreviousPositionMs) {
            return
        }
        this.maxSeekToPreviousPositionMs = maxSeekToPreviousPositionMs
        listeners.sendEvent(
            Player.EVENT_MAX_SEEK_TO_PREVIOUS_POSITION_CHANGED
        ) { listener: Player.Listener ->
            listener.onMaxSeekToPreviousPositionChanged(
                maxSeekToPreviousPositionMs
            )
        }
    }

    override fun setSeekBackIncrementMs(seekBackIncrementMs: Long) {
        Preconditions.checkArgument(seekBackIncrementMs > 0)
        if (this.seekBackIncrementMs == seekBackIncrementMs) {
            return
        }
        this.seekBackIncrementMs = seekBackIncrementMs
        listeners.sendEvent(
            Player.EVENT_SEEK_BACK_INCREMENT_CHANGED
        ) { listener: Player.Listener ->
            listener.onSeekBackIncrementChanged(
                seekBackIncrementMs
            )
        }
    }

    override fun setSeekForwardIncrementMs(seekForwardIncrementMs: Long) {
        Preconditions.checkArgument(seekForwardIncrementMs > 0)
        if (this.seekForwardIncrementMs == seekForwardIncrementMs) {
            return
        }
        this.seekForwardIncrementMs = seekForwardIncrementMs
        listeners.sendEvent(
            Player.EVENT_SEEK_FORWARD_INCREMENT_CHANGED
        ) { listener: Player.Listener ->
            listener.onSeekForwardIncrementChanged(
                seekForwardIncrementMs
            )
        }
    }

    override fun setForegroundMode(foregroundMode: Boolean) {
        if (this.foregroundMode != foregroundMode) {
            this.foregroundMode = foregroundMode
            if (!internalPlayer.setForegroundMode(foregroundMode)) {
                // One of the renderers timed out releasing its resources.
                stopInternal(
                    ExoPlaybackException.createForUnexpected(
                        ExoTimeoutException(ExoTimeoutException.TIMEOUT_OPERATION_SET_FOREGROUND_MODE),
                        PlaybackException.ERROR_CODE_TIMEOUT
                    )
                )
            }
        }
    }

    override fun stop() {
        stopInternal(null)
        currentCueGroup = CueGroup(listOf(), playbackInfo.positionUs)
    }

    override fun release() {
        Log.i(
            TAG,
            ("Release "
                    + Integer.toHexString(System.identityHashCode(this))
                    + " ["
                    + MediaLibraryInfo.VERSION_SLASHY
                    + "] ["
                    + Util.DEVICE_DEBUG_INFO
                    + "] ["
                    + registeredModules()
                    + "]")
        )
        audioBecomingNoisyManager.setEnabled(false)
        streamVolumeManager?.release()
        wakeLockManager.setStayAwake(false)
        wifiLockManager.setStayAwake(false)
        suitableOutputChecker?.disable()
        if (virtualDeviceIdChangeListener != null && Build.VERSION.SDK_INT >= 34) {
            virtualDeviceIdChangeListener.release()
        }
        stuckPlayerDetector.release()
        if (!internalPlayer.release()) {
            // One of the renderers timed out releasing its resources.
            listeners.sendEvent(Player.EVENT_PLAYER_ERROR) { listener: Player.Listener ->
                listener.onPlayerError(
                    ExoPlaybackException.createForUnexpected(
                        ExoTimeoutException(ExoTimeoutException.TIMEOUT_OPERATION_RELEASE),
                        PlaybackException.ERROR_CODE_TIMEOUT
                    )
                )
            }
        }
        listeners.release()
        playbackInfoUpdateHandler!!.removeCallbacksAndMessages(null)
        bandwidthMeter.removeEventListener(_analyticsCollector)
        if (playbackInfo.sleepingForOffload) {
            playbackInfo = playbackInfo.copyWithEstimatedPosition()
        }
        playbackInfo = maskPlaybackState(playbackInfo, Player.STATE_IDLE)
        playbackInfo = playbackInfo.copyWithLoadingMediaPeriodId(playbackInfo.periodId)
        playbackInfo.bufferedPositionUs = playbackInfo.positionUs
        playbackInfo.totalBufferedDurationUs = 0
        _analyticsCollector.release()
        removeSurfaceCallbacks()
        ownedSurface?.also {
            it.release()
            ownedSurface = null
        }
        if (isPriorityTaskManagerRegistered) {
            priorityTaskManager!!.remove(priority)
            isPriorityTaskManagerRegistered = false
        }
        currentCueGroup = CueGroup.EMPTY_TIME_ZERO
        playerReleased = true
        if (!playbackInfo.timeline.isEmpty) {
            Preconditions.checkState(
                playbackInfo.timeline.getIndexOfPeriod(playbackInfo.periodId.periodUid) != C.INDEX_UNSET,
                String.format(
                    Locale.US,
                    "periodUid %s not found in timeline %s with size %d",
                    playbackInfo.periodId.periodUid,
                    playbackInfo.timeline.javaClass.getName(),
                    playbackInfo.timeline.windowCount
                )
            )
        }
    }

    override val isReleased: Boolean
        get() = playerReleased

    override fun createMessage(target: PlayerMessage.Target?): PlayerMessage {
        return createMessageInternal(target)
    }

    override val currentPeriodIndex: Int
        get() = if (playbackInfo.timeline.isEmpty) {
            if (maskingWindowIndex == C.INDEX_UNSET) 0 else maskingWindowIndex
        } else {
            playbackInfo.timeline.getIndexOfPeriod(playbackInfo.periodId.periodUid)
        }

    override val currentMediaItemIndex: Int
        get() {
            val currentWindowIndex = getCurrentWindowIndexInternal(playbackInfo)
            return if (currentWindowIndex == C.INDEX_UNSET) 0 else currentWindowIndex
        }

    override val duration: Long
        get() {
            if (isPlayingAd) {
                val periodId = playbackInfo.periodId
                playbackInfo.timeline.getPeriodByUid(periodId.periodUid, period)
                val adDurationUs =
                    period.getAdDurationUs(periodId.adGroupIndex, periodId.adIndexInAdGroup)
                return Util.usToMs(adDurationUs)
            }
            return contentDuration
        }

    override val currentPosition: Long
        get() = Util.usToMs(getCurrentPositionUsInternal(playbackInfo))

    override val bufferedPosition: Long
        get() {
            if (isPlayingAd) {
                return if (playbackInfo.loadingMediaPeriodId == playbackInfo.periodId)
                    Util.usToMs(playbackInfo.bufferedPositionUs)
                else
                    duration
            }
            return contentBufferedPosition
        }

    override val totalBufferedDuration: Long
        get() = Util.usToMs(playbackInfo.totalBufferedDurationUs)

    override val isPlayingAd: Boolean
        get() = playbackInfo.periodId.isAd

    override val currentAdGroupIndex: Int
        get() = if (isPlayingAd) playbackInfo.periodId.adGroupIndex else C.INDEX_UNSET

    override val currentAdIndexInAdGroup: Int
        get() = if (isPlayingAd) playbackInfo.periodId.adIndexInAdGroup else C.INDEX_UNSET

    override val contentPosition: Long
        get() = getContentPositionInternal(playbackInfo)

    override val contentBufferedPosition: Long
        get() {
            if (playbackInfo.timeline.isEmpty) {
                return maskingWindowPositionMs
            }
            if (playbackInfo.loadingMediaPeriodId.windowSequenceNumber
                != playbackInfo.periodId.windowSequenceNumber
            ) {
                return playbackInfo.timeline.getWindow(currentMediaItemIndex, window).durationMs
            }
            var contentBufferedPositionUs = playbackInfo.bufferedPositionUs
            if (playbackInfo.loadingMediaPeriodId.isAd) {
                val loadingPeriod =
                    playbackInfo.timeline.getPeriodByUid(
                        playbackInfo.loadingMediaPeriodId.periodUid,
                        period
                    )
                contentBufferedPositionUs =
                    loadingPeriod.getAdGroupTimeUs(playbackInfo.loadingMediaPeriodId.adGroupIndex)
                if (contentBufferedPositionUs == C.TIME_END_OF_SOURCE) {
                    contentBufferedPositionUs = loadingPeriod.durationUs
                }
            }
            return Util.usToMs(
                periodPositionUsToWindowPositionUs(
                    playbackInfo.timeline,
                    playbackInfo.loadingMediaPeriodId,
                    contentBufferedPositionUs
                )
            )
        }

    override val rendererCount: Int
        get() = renderers.size

    override fun getRendererType(index: Int): @C.TrackType Int {
        return renderers[index].trackType
    }

    override fun getRenderer(index: Int): Renderer {
        return renderers[index]
    }

    override fun getSecondaryRenderer(index: Int): Renderer? {
        return secondaryRenderers[index]
    }

    override val currentTrackGroups: TrackGroupArray?
        get() = playbackInfo.trackGroups

    override val currentTrackSelections: TrackSelectionArray
        get() = TrackSelectionArray(playbackInfo.trackSelectorResult!!.selections)

    override val currentTracks: Tracks
        get() = playbackInfo.trackSelectorResult!!.tracks

    override var trackSelectionParameters: TrackSelectionParameters?
        get() {
            val parameters = _trackSelector.parameters
            return if (scrubbingModeEnabled)
                parameters!!
                    .buildUpon()
                    .setDisabledTrackTypes(disabledTrackTypesWithoutScrubbingMode!!)
                    .build()
            else
                parameters
        }
        set(value) {
            if (!_trackSelector.isSetParametersSupported) {
                return
            }
            val publicParametersBeforeUpdate: TrackSelectionParameters = trackSelectionParameters!!
            val internalParameters: TrackSelectionParameters
            if (scrubbingModeEnabled) {
                this.disabledTrackTypesWithoutScrubbingMode =
                    value!!.disabledTrackTypes.toMutableSet()
                internalParameters =
                    addDisabledTrackTypes(value, _scrubbingModeParameters!!.disabledTrackTypes)
            } else {
                internalParameters = value!!
            }
            if (internalParameters != _trackSelector.parameters) {
                _trackSelector.parameters = internalParameters
            }
            if (publicParametersBeforeUpdate != value) {
                listeners.sendEvent(
                    Player.EVENT_TRACK_SELECTION_PARAMETERS_CHANGED
                ) { listener: Player.Listener ->
                    listener.onTrackSelectionParametersChanged(
                        value
                    )
                }
            }
        }

    override val currentTimeline: Timeline
        get() = playbackInfo.timeline

    override fun setVideoEffects(videoEffects: List<Effect>?) {
        try {
            // LINT.IfChange(set_video_effects)
            Class.forName("androidx.media3.effect.SingleInputVideoGraph\$Factory")
                .getConstructor(VideoFrameProcessor.Factory::class.java)
            // LINT.ThenChange(video/PlaybackVideoGraphWrapper.java)
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException("Could not find required lib-effect dependencies.", e)
        } catch (e: NoSuchMethodException) {
            throw IllegalStateException("Could not find required lib-effect dependencies.", e)
        }
        sendRendererMessage(C.TRACK_TYPE_VIDEO, Renderer.MSG_SET_VIDEO_EFFECTS, videoEffects)
    }

    override fun clearVideoSurface() {
        removeSurfaceCallbacks()
        setVideoOutputInternal(null)
        maybeNotifySurfaceSizeChanged(0, 0)
    }

    override fun clearVideoSurface(surface: Surface?) {
        if (surface != null && surface === videoOutput) {
            clearVideoSurface()
        }
    }

    override fun setVideoSurface(surface: Surface?) {
        removeSurfaceCallbacks()
        setVideoOutputInternal(surface)
        val newSurfaceSize = if (surface == null) 0 else C.LENGTH_UNSET
        maybeNotifySurfaceSizeChanged(newSurfaceSize, newSurfaceSize)
    }

    override fun setVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        if (surfaceHolder == null) {
            clearVideoSurface()
        } else {
            removeSurfaceCallbacks()
            this.surfaceHolderSurfaceIsVideoOutput = true
            this.surfaceHolder = surfaceHolder
            surfaceHolder.addCallback(componentListener)
            val surface = surfaceHolder.surface
            if (surface != null && surface.isValid) {
                setVideoOutputInternal(surface)
                val surfaceSize = surfaceHolder.surfaceFrame
                maybeNotifySurfaceSizeChanged(surfaceSize.width(), surfaceSize.height())
            } else {
                setVideoOutputInternal(null)
                maybeNotifySurfaceSizeChanged(0, 0)
            }
        }
    }

    override fun clearVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        if (surfaceHolder != null && surfaceHolder === this.surfaceHolder) {
            clearVideoSurface()
        }
    }

    override fun setVideoSurfaceView(surfaceView: SurfaceView?) {
        when (surfaceView) {
            is VideoDecoderOutputBufferRenderer -> {
                removeSurfaceCallbacks()
                setVideoOutputInternal(surfaceView)
                setNonVideoOutputSurfaceHolderInternal(surfaceView.holder)
            }

            is SphericalGLSurfaceView -> {
                removeSurfaceCallbacks()
                sphericalGLSurfaceView = surfaceView
                createMessageInternal(frameMetadataListener)
                    .setType(FrameMetadataListener.MSG_SET_SPHERICAL_SURFACE_VIEW)
                    .setPayload(sphericalGLSurfaceView)
                    .send()
                sphericalGLSurfaceView!!.addVideoSurfaceListener(componentListener)
                setVideoOutputInternal(sphericalGLSurfaceView!!.videoSurface)
                setNonVideoOutputSurfaceHolderInternal(surfaceView.holder)
            }

            else -> {
                setVideoSurfaceHolder(surfaceView?.holder)
            }
        }
    }

    override fun clearVideoSurfaceView(surfaceView: SurfaceView?) {
        clearVideoSurfaceHolder(surfaceView?.holder)
    }

    override fun setVideoTextureView(textureView: TextureView?) {
        if (textureView == null) {
            clearVideoSurface()
        } else {
            removeSurfaceCallbacks()
            this.textureView = textureView
            if (textureView.surfaceTextureListener != null) {
                Log.w(TAG, "Replacing existing SurfaceTextureListener.")
            }
            textureView.surfaceTextureListener = componentListener
            val surfaceTexture =
                if (textureView.isAvailable) textureView.surfaceTexture else null
            if (surfaceTexture == null) {
                setVideoOutputInternal(null)
                maybeNotifySurfaceSizeChanged(0, 0)
            } else {
                setSurfaceTextureInternal(surfaceTexture)
                maybeNotifySurfaceSizeChanged(textureView.width, textureView.getHeight())
            }
        }
    }

    override fun clearVideoTextureView(textureView: TextureView?) {
        if (textureView != null && textureView === this.textureView) {
            clearVideoSurface()
        }
    }

    override fun setAudioAttributes(
        audioAttributes: AudioAttributes,
        handleAudioFocus: Boolean
    ) {
        if (playerReleased) {
            return
        }
        if (this._audioAttributes != audioAttributes) {
            this._audioAttributes = audioAttributes
            sendRendererMessage(
                C.TRACK_TYPE_AUDIO,
                Renderer.MSG_SET_AUDIO_ATTRIBUTES,
                audioAttributes
            )
            streamVolumeManager?.setStreamType(audioAttributes.volumeControlStream)
            // Queue event only and flush after updating playWhenReady in case both events are triggered.
            listeners.queueEvent(
                Player.EVENT_AUDIO_ATTRIBUTES_CHANGED,
                { listener: Player.Listener ->
                    listener.onAudioAttributesChanged(
                        audioAttributes
                    )
                })
        }
        internalPlayer.setAudioAttributes(_audioAttributes, handleAudioFocus)
        listeners.flushEvents()
    }

    override fun setAudioSessionId(audioSessionId: Int) {
        if (audioSessionIdState.get() == audioSessionId) {
            return
        }
        audioSessionIdState.updateStateAsync(
            placeholderState = { previousId: Int -> if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) audioSessionId else previousId },
            backgroundStateUpdate = { previousId: Int ->
                if (audioSessionId != C.AUDIO_SESSION_ID_UNSET)
                    audioSessionId
                else
                    Util.generateAudioSessionId(applicationContext)
            })
    }

    override val audioSessionId: Int
        get() = audioSessionIdState.get()!!

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo?) {
        sendRendererMessage(C.TRACK_TYPE_AUDIO, Renderer.MSG_SET_AUX_EFFECT_INFO, auxEffectInfo)
    }

    override fun clearAuxEffectInfo() {
        setAuxEffectInfo(AuxEffectInfo(AuxEffectInfo.NO_AUX_EFFECT_ID, 0f))
    }

    override fun setPreferredAudioDevice(audioDeviceInfo: AudioDeviceInfo?) {
        sendRendererMessage(
            C.TRACK_TYPE_AUDIO,
            Renderer.MSG_SET_PREFERRED_AUDIO_DEVICE,
            audioDeviceInfo
        )
    }

    override fun setVirtualDeviceId(virtualDeviceId: Int) {
        sendRendererMessage(C.TRACK_TYPE_AUDIO, Renderer.MSG_SET_VIRTUAL_DEVICE_ID, virtualDeviceId)
    }

    override fun mute() {
        if (_volume != 0f) {
            _volume = (0f)
        }
    }

    override fun unmute() {
        if (_volume == 0f && unmuteVolume != 0f) {
            _volume = (unmuteVolume)
        }
    }

    override fun addAnalyticsListener(listener: AnalyticsListener) {
        // Don't verify application thread. We allow calls to this method from any thread.
        _analyticsCollector.addListener(listener)
    }

    override fun removeAnalyticsListener(listener: AnalyticsListener) {
        _analyticsCollector.removeListener(listener)
    }

    override fun setHandleAudioBecomingNoisy(handleAudioBecomingNoisy: Boolean) {
        if (playerReleased) {
            return
        }
        audioBecomingNoisyManager.setEnabled(handleAudioBecomingNoisy)
    }

    override fun setPriority(priority: @C.Priority Int) {
        if (this.priority == priority) {
            return
        }
        if (isPriorityTaskManagerRegistered) {
            val priorityTaskManager = this.priorityTaskManager!!
            priorityTaskManager.add(priority)
            priorityTaskManager.remove(this.priority)
        }
        this.priority = priority
        sendRendererMessage(Renderer.MSG_SET_PRIORITY, priority)
    }

    override fun setPriorityTaskManager(priorityTaskManager: PriorityTaskManager?) {
        if (this.priorityTaskManager == priorityTaskManager) {
            return
        }
        if (isPriorityTaskManagerRegistered) {
            this.priorityTaskManager!!.remove(priority)
        }
        if (priorityTaskManager != null && isLoading) {
            priorityTaskManager.add(priority)
            isPriorityTaskManagerRegistered = true
        } else {
            isPriorityTaskManagerRegistered = false
        }
        this.priorityTaskManager = priorityTaskManager
    }

    override fun setVideoFrameMetadataListener(listener: VideoFrameMetadataListener?) {
        videoFrameMetadataListener = listener
        createMessageInternal(frameMetadataListener)
            .setType(FrameMetadataListener.MSG_SET_VIDEO_FRAME_METADATA_LISTENER)
            .setPayload(listener)
            .send()
    }

    override fun clearVideoFrameMetadataListener(listener: VideoFrameMetadataListener?) {
        if (videoFrameMetadataListener !== listener) {
            return
        }
        createMessageInternal(frameMetadataListener)
            .setType(FrameMetadataListener.MSG_SET_VIDEO_FRAME_METADATA_LISTENER)
            .setPayload(null)
            .send()
    }

    override fun setCameraMotionListener(listener: CameraMotionListener?) {
        cameraMotionListener = listener
        createMessageInternal(frameMetadataListener)
            .setType(FrameMetadataListener.MSG_SET_CAMERA_MOTION_LISTENER)
            .setPayload(listener)
            .send()
    }

    override fun clearCameraMotionListener(listener: CameraMotionListener?) {
        if (cameraMotionListener !== listener) {
            return
        }
        createMessageInternal(frameMetadataListener)
            .setType(FrameMetadataListener.MSG_SET_CAMERA_MOTION_LISTENER)
            .setPayload(null)
            .send()
    }

    override val currentCues: CueGroup?
        get() = currentCueGroup

    override fun addListener(listener: Player.Listener) {
        // Don't verify application thread. We allow calls to this method from any thread.
        listeners.add(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
    }

    override fun setWakeMode(@C.WakeMode wakeMode: @C.WakeMode Int) {
        when (wakeMode) {
            C.WAKE_MODE_NONE -> {
                wakeLockManager.setEnabled(false)
                wifiLockManager.setEnabled(false)
            }

            C.WAKE_MODE_LOCAL -> {
                wakeLockManager.setEnabled(true)
                wifiLockManager.setEnabled(false)
            }

            C.WAKE_MODE_NETWORK -> {
                wakeLockManager.setEnabled(true)
                wifiLockManager.setEnabled(true)
            }

            else -> {}
        }
    }

    override val deviceVolume: Int
        get() = streamVolumeManager?.volume ?: 0

    override val isDeviceMuted: Boolean
        get() = streamVolumeManager?.isMuted ?: false

    override fun setDeviceVolume(volume: Int, flags: @C.VolumeFlags Int) {
        streamVolumeManager?.setVolume(volume, flags)
    }

    override fun increaseDeviceVolume(flags: @C.VolumeFlags Int) {
        streamVolumeManager?.increaseVolume(flags)
    }

    override fun decreaseDeviceVolume(flags: @C.VolumeFlags Int) {
        streamVolumeManager?.decreaseVolume(flags)
    }

    override fun setDeviceMuted(muted: Boolean, flags: @C.VolumeFlags Int) {
        streamVolumeManager?.setMuted(muted, flags)
    }

    override val isTunnelingEnabled: Boolean
        get() {
            for (config in playbackInfo.trackSelectorResult!!.rendererConfigurations) {
                if (config != null && config.tunneling) {
                    return true
                }
            }
            return false
        }

    override fun setImageOutput(imageOutput: ImageOutput?) {
        sendRendererMessage(C.TRACK_TYPE_IMAGE, Renderer.MSG_SET_IMAGE_OUTPUT, imageOutput)
    }

    override fun setAudioCodecParameters(codecParameters: CodecParameters?) {
        sendRendererMessage(
            C.TRACK_TYPE_AUDIO,
            Renderer.MSG_SET_CODEC_PARAMETERS,
            codecParameters!!
        )
    }

    override fun addAudioCodecParametersChangeListener(
        listener: CodecParametersChangeListener?, keys: List<String?>?
    ) {
        audioListenerManager.addListener(listener!!, keys!!)
    }

    override fun removeAudioCodecParametersChangeListener(listener: CodecParametersChangeListener?) {
        audioListenerManager.removeListener(listener!!)
    }

    override fun setVideoCodecParameters(codecParameters: CodecParameters?) {
        sendRendererMessage(
            C.TRACK_TYPE_VIDEO,
            Renderer.MSG_SET_CODEC_PARAMETERS,
            codecParameters!!
        )
    }

    override fun addVideoCodecParametersChangeListener(
        listener: CodecParametersChangeListener?, keys: List<String?>?
    ) {
        videoListenerManager.addListener(listener!!, keys!!)
    }

    override fun removeVideoCodecParametersChangeListener(listener: CodecParametersChangeListener?) {
        videoListenerManager.removeListener(listener!!)
    }

    fun setThrowsWhenUsingWrongThread(throwsWhenUsingWrongThread: Boolean) {
        this.throwsWhenUsingWrongThread = throwsWhenUsingWrongThread
        listeners.setThrowsWhenUsingWrongThread(throwsWhenUsingWrongThread)
        (_analyticsCollector as? DefaultAnalyticsCollector)?.setThrowsWhenUsingWrongThread(
            throwsWhenUsingWrongThread
        )
    }

    private fun stopInternal(error: ExoPlaybackException?) {
        var playbackInfo = playbackInfo.copyWithLoadingMediaPeriodId(playbackInfo.periodId)
        playbackInfo.bufferedPositionUs = playbackInfo.positionUs
        playbackInfo.totalBufferedDurationUs = 0
        playbackInfo = maskPlaybackState(playbackInfo, Player.STATE_IDLE)
        if (error != null) {
            playbackInfo = playbackInfo.copyWithPlaybackError(error)
        }
        pendingOperationAcks++
        internalPlayer.stop()
        updatePlaybackInfo(
            playbackInfo,
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
            false,
            Player.DISCONTINUITY_REASON_INTERNAL,
            C.TIME_UNSET,
            C.INDEX_UNSET,
            false
        )
    }

    private fun getCurrentWindowIndexInternal(playbackInfo: PlaybackInfo): Int {
        if (playbackInfo.timeline.isEmpty) {
            return maskingWindowIndex
        }
        return playbackInfo.timeline.getPeriodByUid(playbackInfo.periodId.periodUid, period)
            .windowIndex
    }

    private fun getContentPositionInternal(playbackInfo: PlaybackInfo): Long {
        if (playbackInfo.periodId.isAd) {
            playbackInfo.timeline.getPeriodByUid(playbackInfo.periodId.periodUid, period)
            return if (playbackInfo.requestedContentPositionUs == C.TIME_UNSET)
                playbackInfo
                    .timeline
                    .getWindow(getCurrentWindowIndexInternal(playbackInfo), window)
                    .defaultPositionMs
            else
                period.positionInWindowMs + Util.usToMs(playbackInfo.requestedContentPositionUs)
        }
        return Util.usToMs(getCurrentPositionUsInternal(playbackInfo))
    }

    private fun getCurrentPositionUsInternal(playbackInfo: PlaybackInfo): Long {
        if (playbackInfo.timeline.isEmpty) {
            return Util.msToUs(maskingWindowPositionMs)
        }
        val positionUs =
            if (playbackInfo.sleepingForOffload)
                playbackInfo.estimatedPositionUs
            else
                playbackInfo.positionUs

        if (playbackInfo.periodId.isAd) {
            return positionUs
        }
        return periodPositionUsToWindowPositionUs(
            playbackInfo.timeline, playbackInfo.periodId, positionUs
        )
    }

    private fun createMediaSources(mediaItems: List<MediaItem>): List<MediaSource> {
        return mediaItems.map {
            mediaSourceFactory.createMediaSource(it)
        }
    }

    private fun handlePlaybackInfo(playbackInfoUpdate: PlaybackInfoUpdate) {
        pendingOperationAcks -= playbackInfoUpdate.operationAcks
        if (playbackInfoUpdate.positionDiscontinuity) {
            pendingDiscontinuityReason = playbackInfoUpdate.discontinuityReason
            pendingDiscontinuity = true
        }
        if (pendingOperationAcks == 0) {
            val newTimeline = playbackInfoUpdate.playbackInfo.timeline
            if (!this.playbackInfo.timeline.isEmpty && newTimeline.isEmpty) {
                // Update the masking variables, which are used when the timeline becomes empty because a
                // ConcatenatingMediaSource has been cleared.
                maskingWindowIndex = C.INDEX_UNSET
                maskingWindowPositionMs = 0
            }
            if (!newTimeline.isEmpty) {
                val timelines = (newTimeline as PlaylistTimeline).childTimelines
                Preconditions.checkState(timelines.size == mediaSourceHolderSnapshots.size)
                for (i in timelines.indices) {
                    mediaSourceHolderSnapshots[i].updateTimeline(timelines[i])
                }
            }
            var positionDiscontinuity = false
            var discontinuityWindowStartPositionUs = C.TIME_UNSET
            var oldMaskingMediaItemIndex = C.INDEX_UNSET
            if (pendingDiscontinuity) {
                val oldAndNewTimelineEmpty =
                    playbackInfoUpdate.playbackInfo.timeline.isEmpty && playbackInfo.timeline.isEmpty
                val sameMediaPeriodId =
                    playbackInfoUpdate.playbackInfo.periodId.equalsExceptNextAdGroupIndex(
                        playbackInfo.periodId
                    )
                val samePositon =
                    playbackInfoUpdate.playbackInfo.discontinuityStartPositionUs == playbackInfo.positionUs
                positionDiscontinuity =
                    !oldAndNewTimelineEmpty && (!sameMediaPeriodId || !samePositon)
                if (positionDiscontinuity) {
                    oldMaskingMediaItemIndex = currentMediaItemIndex
                    discontinuityWindowStartPositionUs =
                        if (newTimeline.isEmpty || playbackInfoUpdate.playbackInfo.periodId.isAd)
                            playbackInfoUpdate.playbackInfo.discontinuityStartPositionUs
                        else
                            periodPositionUsToWindowPositionUs(
                                newTimeline,
                                playbackInfoUpdate.playbackInfo.periodId,
                                playbackInfoUpdate.playbackInfo.discontinuityStartPositionUs
                            )
                }
            }
            pendingDiscontinuity = false
            updatePlaybackInfo(
                playbackInfoUpdate.playbackInfo,
                Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE,
                positionDiscontinuity,
                pendingDiscontinuityReason,
                discontinuityWindowStartPositionUs,
                oldMaskingMediaItemIndex,
                false
            )
        }
    }

    // Calling deprecated listeners.
    private fun updatePlaybackInfo(
        playbackInfo: PlaybackInfo,
        @Player.TimelineChangeReason timelineChangeReason: @Player.TimelineChangeReason Int,
        positionDiscontinuity: Boolean,
        @Player.DiscontinuityReason positionDiscontinuityReason: @Player.DiscontinuityReason Int,
        discontinuityWindowStartPositionUs: Long,
        oldMaskingMediaItemIndex: Int,
        repeatCurrentMediaItem: Boolean
    ) {
        // Assign playback info immediately such that all getters return the right values, but keep
        // snapshot of previous and new state so that listener invocations are triggered correctly.
        val previousPlaybackInfo = playbackInfo
        val newPlaybackInfo: PlaybackInfo = playbackInfo
        this.playbackInfo = playbackInfo
        if (!playbackInfo.timeline.isEmpty) {
            Preconditions.checkState(
                playbackInfo.timeline.getIndexOfPeriod(playbackInfo.periodId.periodUid) != C.INDEX_UNSET,
                String.format(
                    Locale.US,
                    "periodUid %s not found in timeline %s with size %d",
                    playbackInfo.periodId.periodUid,
                    playbackInfo.timeline.javaClass.getName(),
                    playbackInfo.timeline.windowCount
                )
            )
        }

        val timelineChanged = previousPlaybackInfo.timeline != newPlaybackInfo.timeline
        val mediaItemTransitionInfo =
            evaluateMediaItemTransitionReason(
                newPlaybackInfo,
                previousPlaybackInfo,
                positionDiscontinuity,
                positionDiscontinuityReason,
                timelineChanged,
                repeatCurrentMediaItem
            )
        val mediaItemTransitioned: Boolean = mediaItemTransitionInfo.first!!
        val mediaItemTransitionReason: Int = mediaItemTransitionInfo.second!!
        var mediaItem: MediaItem? = null
        if (mediaItemTransitioned) {
            if (!newPlaybackInfo.timeline.isEmpty) {
                val windowIndex =
                    newPlaybackInfo.timeline.getPeriodByUid(
                        newPlaybackInfo.periodId.periodUid, period
                    ).windowIndex
                mediaItem = newPlaybackInfo.timeline.getWindow(windowIndex, window).mediaItem
            }
            staticAndDynamicMediaMetadata = MediaMetadata.EMPTY
        }
        if (mediaItemTransitioned
            || previousPlaybackInfo.staticMetadata != newPlaybackInfo.staticMetadata
        ) {
            staticAndDynamicMediaMetadata =
                staticAndDynamicMediaMetadata!!
                    .buildUpon()
                    .populateFromMetadata(newPlaybackInfo.staticMetadata!!)
                    .build()
        }
        val newMediaMetadata = buildUpdatedMediaMetadata()
        val metadataChanged = newMediaMetadata != _mediaMetadata
        _mediaMetadata = newMediaMetadata
        val playWhenReadyChanged =
            previousPlaybackInfo.playWhenReady != newPlaybackInfo.playWhenReady
        val playbackStateChanged =
            previousPlaybackInfo.playbackState != newPlaybackInfo.playbackState
        if (playbackStateChanged || playWhenReadyChanged) {
            updateWakeAndWifiLock()
        }
        val isLoadingChanged = previousPlaybackInfo.isLoading != newPlaybackInfo.isLoading
        if (isLoadingChanged) {
            updatePriorityTaskManagerForIsLoadingChange(newPlaybackInfo.isLoading)
        }

        if (timelineChanged) {
            listeners.queueEvent(Player.EVENT_TIMELINE_CHANGED) { listener: Player.Listener ->
                listener.onTimelineChanged(newPlaybackInfo.timeline, timelineChangeReason)
            }
        }
        if (positionDiscontinuity) {
            val previousPositionInfo =
                getPreviousPositionInfo(
                    positionDiscontinuityReason, previousPlaybackInfo, oldMaskingMediaItemIndex
                )
            val positionInfo = getPositionInfo(discontinuityWindowStartPositionUs)
            listeners.queueEvent(Player.EVENT_POSITION_DISCONTINUITY) { listener: Player.Listener ->
                listener.onPositionDiscontinuity(positionDiscontinuityReason)
                listener.onPositionDiscontinuity(
                    previousPositionInfo, positionInfo, positionDiscontinuityReason
                )
            }
        }
        if (mediaItemTransitioned) {
            val finalMediaItem = mediaItem
            listeners.queueEvent(Player.EVENT_MEDIA_ITEM_TRANSITION) { listener: Player.Listener ->
                listener.onMediaItemTransition(finalMediaItem, mediaItemTransitionReason)
            }
        }
        if (previousPlaybackInfo.playbackError != newPlaybackInfo.playbackError) {
            listeners.queueEvent(Player.EVENT_PLAYER_ERROR) { listener: Player.Listener ->
                listener.onPlayerErrorChanged(newPlaybackInfo.playbackError)
            }
            if (newPlaybackInfo.playbackError != null) {
                listeners.queueEvent(Player.EVENT_PLAYER_ERROR) { listener: Player.Listener ->
                    listener.onPlayerError(newPlaybackInfo.playbackError)
                }
            }
        }
        if (previousPlaybackInfo.trackSelectorResult != newPlaybackInfo.trackSelectorResult) {
            _trackSelector.onSelectionActivated(newPlaybackInfo.trackSelectorResult!!.info)
            listeners.queueEvent(Player.EVENT_TRACKS_CHANGED) { listener: Player.Listener ->
                listener.onTracksChanged(newPlaybackInfo.trackSelectorResult.tracks)
            }
        }
        if (metadataChanged) {
            val finalMediaMetadata = _mediaMetadata
            listeners.queueEvent(Player.EVENT_MEDIA_METADATA_CHANGED) { listener: Player.Listener ->
                listener.onMediaMetadataChanged(finalMediaMetadata!!)
            }
        }
        if (isLoadingChanged) {
            listeners.queueEvent(Player.EVENT_IS_LOADING_CHANGED) { listener: Player.Listener ->
                listener.onLoadingChanged(newPlaybackInfo.isLoading)
                listener.onIsLoadingChanged(newPlaybackInfo.isLoading)
            }
        }
        if (playbackStateChanged || playWhenReadyChanged) {
            listeners.queueEvent(C.INDEX_UNSET) { listener: Player.Listener ->
                listener.onPlayerStateChanged(
                    newPlaybackInfo.playWhenReady,
                    newPlaybackInfo.playbackState
                )
            }
        }
        if (playbackStateChanged) {
            listeners.queueEvent(Player.EVENT_PLAYBACK_STATE_CHANGED) { listener: Player.Listener ->
                listener.onPlaybackStateChanged(newPlaybackInfo.playbackState)
            }
        }
        if (playWhenReadyChanged
            || (previousPlaybackInfo.playWhenReadyChangeReason != newPlaybackInfo.playWhenReadyChangeReason)
        ) {
            listeners.queueEvent(Player.EVENT_PLAY_WHEN_READY_CHANGED) { listener: Player.Listener ->
                listener.onPlayWhenReadyChanged(
                    newPlaybackInfo.playWhenReady, newPlaybackInfo.playWhenReadyChangeReason
                )
            }
        }
        if (previousPlaybackInfo.playbackSuppressionReason != newPlaybackInfo.playbackSuppressionReason) {
            listeners.queueEvent(Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED) { listener: Player.Listener ->
                listener.onPlaybackSuppressionReasonChanged(newPlaybackInfo.playbackSuppressionReason)
            }
        }
        if (previousPlaybackInfo.isPlaying != newPlaybackInfo.isPlaying) {
            listeners.queueEvent(Player.EVENT_IS_PLAYING_CHANGED) { listener: Player.Listener ->
                listener.onIsPlayingChanged(newPlaybackInfo.isPlaying)
            }
        }
        if (previousPlaybackInfo.playbackParameters != newPlaybackInfo.playbackParameters) {
            listeners.queueEvent(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED) { listener: Player.Listener ->
                listener.onPlaybackParametersChanged(newPlaybackInfo.playbackParameters)
            }
        }
        updateAvailableCommands()
        listeners.flushEvents()

        if (previousPlaybackInfo.sleepingForOffload != newPlaybackInfo.sleepingForOffload) {
            for (listener in audioOffloadListeners) {
                listener.onSleepingForOffloadChanged(newPlaybackInfo.sleepingForOffload)
            }
        }
    }

    private fun getPreviousPositionInfo(
        positionDiscontinuityReason: @Player.DiscontinuityReason Int,
        oldPlaybackInfo: PlaybackInfo,
        oldMaskingMediaItemIndex: Int
    ): Player.PositionInfo {
        var oldWindowUid: Any? = null
        var oldPeriodUid: Any? = null
        var oldMediaItemIndex = oldMaskingMediaItemIndex
        var oldPeriodIndex = oldMaskingMediaItemIndex
        var oldMediaItem: MediaItem? = null
        val oldPeriod = Timeline.Period()
        if (!oldPlaybackInfo.timeline.isEmpty) {
            oldPeriodUid = oldPlaybackInfo.periodId.periodUid
            oldPlaybackInfo.timeline.getPeriodByUid(oldPeriodUid, oldPeriod)
            oldMediaItemIndex = oldPeriod.windowIndex
            oldPeriodIndex = oldPlaybackInfo.timeline.getIndexOfPeriod(oldPeriodUid)
            oldWindowUid = oldPlaybackInfo.timeline.getWindow(oldMediaItemIndex, window).uid
            oldMediaItem = window.mediaItem
        }
        val oldPositionUs: Long
        val oldContentPositionUs: Long
        if (positionDiscontinuityReason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
            if (oldPlaybackInfo.periodId.isAd) {
                // The old position is the end of the previous ad.
                oldPositionUs =
                    oldPeriod.getAdDurationUs(
                        oldPlaybackInfo.periodId.adGroupIndex,
                        oldPlaybackInfo.periodId.adIndexInAdGroup
                    )
                // The ad cue point is stored in the old requested content position.
                oldContentPositionUs = getRequestedContentPositionUs(oldPlaybackInfo)
            } else if (oldPlaybackInfo.periodId.nextAdGroupIndex != C.INDEX_UNSET) {
                // The old position is the end of a clipped content before an ad group. Use the exact ad
                // cue point as the transition position.
                oldPositionUs = getRequestedContentPositionUs(playbackInfo)
                oldContentPositionUs = oldPositionUs
            } else {
                // The old position is the end of a Timeline period. Use the exact duration.
                oldPositionUs = oldPeriod.positionInWindowUs + oldPeriod.durationUs
                oldContentPositionUs = oldPositionUs
            }
        } else if (oldPlaybackInfo.periodId.isAd) {
            oldPositionUs = oldPlaybackInfo.positionUs
            oldContentPositionUs = getRequestedContentPositionUs(oldPlaybackInfo)
        } else {
            oldPositionUs = oldPeriod.positionInWindowUs + oldPlaybackInfo.positionUs
            oldContentPositionUs = oldPositionUs
        }
        return Player.PositionInfo(
            oldWindowUid,
            oldMediaItemIndex,
            oldMediaItem,
            oldPeriodUid,
            oldPeriodIndex,
            Util.usToMs(oldPositionUs),
            Util.usToMs(oldContentPositionUs),
            oldPlaybackInfo.periodId.adGroupIndex,
            oldPlaybackInfo.periodId.adIndexInAdGroup
        )
    }

    private fun getPositionInfo(discontinuityWindowStartPositionUs: Long): Player.PositionInfo {
        var newWindowUid: Any? = null
        var newPeriodUid: Any? = null
        val newMediaItemIndex = currentMediaItemIndex
        var newPeriodIndex = currentPeriodIndex
        var newMediaItem: MediaItem? = null
        if (!playbackInfo.timeline.isEmpty) {
            newPeriodUid = playbackInfo.periodId.periodUid
            playbackInfo.timeline.getPeriodByUid(newPeriodUid, period)
            newPeriodIndex = playbackInfo.timeline.getIndexOfPeriod(newPeriodUid)
            newWindowUid = playbackInfo.timeline.getWindow(newMediaItemIndex, window).uid
            newMediaItem = window.mediaItem
        }
        val positionMs = Util.usToMs(discontinuityWindowStartPositionUs)
        return Player.PositionInfo(
            newWindowUid,
            newMediaItemIndex,
            newMediaItem,
            newPeriodUid,
            newPeriodIndex,
            positionMs,
            if (playbackInfo.periodId.isAd)
                Util.usToMs(getRequestedContentPositionUs(playbackInfo))
            else
                positionMs,
            playbackInfo.periodId.adGroupIndex,
            playbackInfo.periodId.adIndexInAdGroup
        )
    }

    private fun evaluateMediaItemTransitionReason(
        playbackInfo: PlaybackInfo,
        oldPlaybackInfo: PlaybackInfo,
        positionDiscontinuity: Boolean,
        @Player.DiscontinuityReason positionDiscontinuityReason: @Player.DiscontinuityReason Int,
        timelineChanged: Boolean,
        repeatCurrentMediaItem: Boolean
    ): Pair<Boolean?, Int?> {
        val oldTimeline = oldPlaybackInfo.timeline
        val newTimeline = playbackInfo.timeline
        if (newTimeline.isEmpty && oldTimeline.isEmpty) {
            return Pair<Boolean?, Int?>(false, C.INDEX_UNSET)
        } else if (newTimeline.isEmpty != oldTimeline.isEmpty) {
            return Pair<Boolean?, Int?>(true, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        }
        val oldWindowIndex =
            oldTimeline.getPeriodByUid(oldPlaybackInfo.periodId.periodUid, period).windowIndex
        val oldWindowUid = oldTimeline.getWindow(oldWindowIndex, window).uid
        val newWindowIndex =
            newTimeline.getPeriodByUid(playbackInfo.periodId.periodUid, period).windowIndex
        val newWindowUid = newTimeline.getWindow(newWindowIndex, window).uid
        if (oldWindowUid != newWindowUid) {
            val transitionReason =
                if (positionDiscontinuity
                    && positionDiscontinuityReason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                ) {
                    Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                } else if (positionDiscontinuity
                    && positionDiscontinuityReason == Player.DISCONTINUITY_REASON_SEEK
                ) {
                    Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
                } else if (timelineChanged) {
                    Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
                } else {
                    // A change in window uid must be justified by one of the reasons above.
                    throw IllegalStateException()
                }
            return Pair<Boolean?, Int?>(true, transitionReason)
        } else {
            // Only mark changes within the current item as a transition if we are repeating automatically
            // or via a seek to next/previous.
            if (positionDiscontinuity
                && positionDiscontinuityReason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && (oldPlaybackInfo.periodId.windowSequenceNumber
                        < playbackInfo.periodId.windowSequenceNumber)
            ) {
                return Pair<Boolean?, Int?>(true, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
            }
            if (positionDiscontinuity
                && positionDiscontinuityReason == Player.DISCONTINUITY_REASON_SEEK && repeatCurrentMediaItem
            ) {
                return Pair<Boolean?, Int?>(true, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
            }
        }
        return Pair<Boolean?, Int?>(false, C.INDEX_UNSET)
    }

    private fun updateAvailableCommands() {
        val previousAvailableCommands = _availableCommands
        _availableCommands = Util.getAvailableCommands(wrappingPlayer, permanentAvailableCommands)
        if (_availableCommands != previousAvailableCommands) {
            listeners.queueEvent(Player.EVENT_AVAILABLE_COMMANDS_CHANGED) { listener: Player.Listener ->
                listener.onAvailableCommandsChanged(_availableCommands!!)
            }
        }
    }

    private fun setMediaSourcesInternal(
        mediaSources: List<MediaSource>,
        startWindowIndex: Int,
        startPositionMs: Long,
        resetToDefaultPosition: Boolean
    ) {
        var startWindowIndex = startWindowIndex
        var startPositionMs = startPositionMs
        val currentWindowIndex = getCurrentWindowIndexInternal(playbackInfo)
        val currentPositionMs = currentPosition
        pendingOperationAcks++
        val holders = setMediaSourceHolders(mediaSources, startWindowIndex)
        val timeline = createMaskingTimeline()
        if (!timeline.isEmpty && startWindowIndex >= timeline.windowCount) {
            throw IllegalSeekPositionException(timeline, startWindowIndex, startPositionMs)
        }
        // Evaluate the actual start position.
        if (resetToDefaultPosition) {
            startWindowIndex = timeline.getFirstWindowIndex(_shuffleModeEnabled)
            startPositionMs = C.TIME_UNSET
        } else if (startWindowIndex == C.INDEX_UNSET) {
            startWindowIndex = currentWindowIndex
            startPositionMs = currentPositionMs
        }
        var newPlaybackInfo =
            maskTimelineAndPosition(
                playbackInfo,
                timeline,
                maskWindowPositionMsOrGetPeriodPositionUs(
                    timeline,
                    startWindowIndex,
                    startPositionMs
                )
            )
        val maskingPlaybackState = if (newPlaybackInfo.playbackState == Player.STATE_IDLE) {
            Player.STATE_IDLE // never move out of IDLE automatically
        } else if (timeline.isEmpty) {
            Player.STATE_ENDED // ensure ENDED for empty playlist
        } else if (startWindowIndex == C.INDEX_UNSET) {
            newPlaybackInfo.playbackState // no implicit seek, keep old state
        } else if (startWindowIndex >= timeline.windowCount) {
            Player.STATE_ENDED // invalid seek, transition to ENDED
        } else {
            Player.STATE_BUFFERING
        }
        newPlaybackInfo = maskPlaybackState(newPlaybackInfo, maskingPlaybackState)
        internalPlayer.setMediaSources(
            holders, startWindowIndex, Util.msToUs(startPositionMs), _shuffleOrder
        )
        val positionDiscontinuity =
            playbackInfo.periodId.periodUid != newPlaybackInfo.periodId.periodUid && !playbackInfo.timeline.isEmpty
        updatePlaybackInfo(
            newPlaybackInfo,
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
            positionDiscontinuity,
            Player.DISCONTINUITY_REASON_REMOVE,
            getCurrentPositionUsInternal(newPlaybackInfo),
            C.INDEX_UNSET,
            false
        )
    }

    private fun setMediaSourceHolders(
        mediaSources: List<MediaSource>, startIndex: Int
    ): List<MediaSourceList.MediaSourceHolder> {
        mediaSourceHolderSnapshots.clear()
        val holders = MutableList(mediaSources.size) {
            val holder = MediaSourceList.MediaSourceHolder(mediaSources[it], useLazyPreparation)
            mediaSourceHolderSnapshots.add(
                it, MediaSourceHolderSnapshot(holder.uid, holder.mediaSource)
            )
            holder
        }
        _shuffleOrder = _shuffleOrder.cloneAndSet(holders.size, startIndex)
        return holders
    }

    private fun addMediaSourceHolders(
        index: Int, mediaSources: List<MediaSource>
    ): List<MediaSourceList.MediaSourceHolder> {
        val holders = mutableListOf<MediaSourceList.MediaSourceHolder>()
        for (i in mediaSources.indices) {
            val holder =
                MediaSourceList.MediaSourceHolder(mediaSources[i], useLazyPreparation)
            holders.add(holder)
            mediaSourceHolderSnapshots.add(
                i + index, MediaSourceHolderSnapshot(holder.uid, holder.mediaSource)
            )
        }
        _shuffleOrder = _shuffleOrder.cloneAndInsert(index, holders.size)
        return holders
    }

    private fun addMediaSourcesInternal(
        playbackInfo: PlaybackInfo, index: Int, mediaSources: List<MediaSource>
    ): PlaybackInfo {
        val oldTimeline = playbackInfo.timeline
        pendingOperationAcks++
        val holders = addMediaSourceHolders(index, mediaSources)
        val newTimeline = createMaskingTimeline()
        val newPlaybackInfo =
            maskTimelineAndPosition(
                playbackInfo,
                newTimeline,
                getPeriodPositionUsAfterTimelineChanged(
                    oldTimeline,
                    newTimeline,
                    getCurrentWindowIndexInternal(playbackInfo),
                    getContentPositionInternal(playbackInfo)
                )
            )
        internalPlayer.addMediaSources(index, holders, _shuffleOrder)
        return newPlaybackInfo
    }

    private fun removeMediaItemsInternal(
        playbackInfo: PlaybackInfo, fromIndex: Int, toIndex: Int
    ): PlaybackInfo {
        val currentIndex = getCurrentWindowIndexInternal(playbackInfo)
        val contentPositionMs = getContentPositionInternal(playbackInfo)
        val oldTimeline = playbackInfo.timeline
        pendingOperationAcks++
        removeMediaSourceHolders(fromIndex, toIndex)
        val newTimeline = createMaskingTimeline()
        var newPlaybackInfo =
            maskTimelineAndPosition(
                playbackInfo,
                newTimeline,
                getPeriodPositionUsAfterTimelineChanged(
                    oldTimeline, newTimeline, currentIndex, contentPositionMs
                )
            )
        if (newPlaybackInfo.playbackState != Player.STATE_IDLE && newPlaybackInfo.playbackState != Player.STATE_ENDED && currentIndex >= fromIndex && currentIndex < toIndex) {
            // Check if we need to transition to STATE_ENDED after the current item was removed and no
            // subsequent period can be found.
            val periodUid = playbackInfo.periodId.periodUid
            val resolvedWindowIndex: Int =
                ExoPlayerImplInternal.resolveSubsequentPeriod(
                    window,
                    period,
                    _repeatMode,
                    _shuffleModeEnabled,
                    periodUid,
                    oldTimeline,
                    newTimeline
                )
            if (resolvedWindowIndex == C.INDEX_UNSET) {
                newPlaybackInfo = maskPlaybackState(newPlaybackInfo, Player.STATE_ENDED)
            }
        }
        internalPlayer.removeMediaSources(fromIndex, toIndex, _shuffleOrder)
        return newPlaybackInfo
    }

    private fun removeMediaSourceHolders(fromIndex: Int, toIndexExclusive: Int) {
        for (i in toIndexExclusive - 1 downTo fromIndex) {
            mediaSourceHolderSnapshots.removeAt(i)
        }
        _shuffleOrder = _shuffleOrder.cloneAndRemove(fromIndex, toIndexExclusive)
    }

    private fun createMaskingTimeline(): Timeline {
        return PlaylistTimeline(mediaSourceHolderSnapshots, _shuffleOrder)
    }

    private fun maskTimelineAndPosition(
        playbackInfo: PlaybackInfo, timeline: Timeline, periodPositionUs: Pair<Any, Long>?
    ): PlaybackInfo {
        var playbackInfo = playbackInfo
        Preconditions.checkArgument(timeline.isEmpty || periodPositionUs != null)
        // Get the old timeline and position before updating playbackInfo.
        val oldTimeline = playbackInfo.timeline
        val oldContentPositionMs = getContentPositionInternal(playbackInfo)
        // Mask the timeline.
        playbackInfo = playbackInfo.copyWithTimeline(timeline)

        if (timeline.isEmpty) {
            // Reset periodId and loadingPeriodId.
            val dummyMediaPeriodId = PlaybackInfo.dummyPeriodForEmptyTimeline
            val positionUs = Util.msToUs(maskingWindowPositionMs)
            playbackInfo =
                playbackInfo.copyWithNewPosition(
                    dummyMediaPeriodId,
                    positionUs,
                    positionUs,
                    positionUs,
                    0,
                    TrackGroupArray.EMPTY,
                    emptyTrackSelectorResult,
                    listOf()
                )
            playbackInfo = playbackInfo.copyWithLoadingMediaPeriodId(dummyMediaPeriodId)
            playbackInfo.bufferedPositionUs = playbackInfo.positionUs
            return playbackInfo
        }

        val oldPeriodUid = playbackInfo.periodId.periodUid
        val playingPeriodChanged = oldPeriodUid != periodPositionUs!!.first
        val newPeriodId =
            if (playingPeriodChanged) MediaPeriodId(periodPositionUs.first!!) else playbackInfo.periodId
        val newContentPositionUs: Long = periodPositionUs.second!!
        var oldContentPositionUs = Util.msToUs(oldContentPositionMs)
        if (!oldTimeline.isEmpty) {
            oldContentPositionUs -=
                oldTimeline.getPeriodByUid(oldPeriodUid, period).positionInWindowUs
            if (!playingPeriodChanged && oldContentPositionUs - newContentPositionUs == 1L) {
                val oldDurationUs = oldTimeline.getPeriodByUid(oldPeriodUid, period).durationUs
                val endOfSameStream = oldContentPositionUs == oldDurationUs
                if (endOfSameStream) {
                    // Correct the old position to be durationUs - 1.
                    oldContentPositionUs -= 1
                }
            }
        }

        if (playingPeriodChanged || newContentPositionUs < oldContentPositionUs) {
            Preconditions.checkState(!newPeriodId.isAd)
            // The playing period changes or a backwards seek within the playing period occurs.
            playbackInfo =
                playbackInfo.copyWithNewPosition(
                    newPeriodId,
                    newContentPositionUs,
                    newContentPositionUs,
                    newContentPositionUs,
                    0,
                    if (playingPeriodChanged) TrackGroupArray.EMPTY else playbackInfo.trackGroups,
                    if (playingPeriodChanged) emptyTrackSelectorResult else playbackInfo.trackSelectorResult,
                    if (playingPeriodChanged) listOf() else playbackInfo.staticMetadata
                )
            playbackInfo = playbackInfo.copyWithLoadingMediaPeriodId(newPeriodId)
            playbackInfo.bufferedPositionUs = newContentPositionUs
        } else if (newContentPositionUs == oldContentPositionUs) {
            // Period position remains unchanged.
            val loadingPeriodIndex =
                timeline.getIndexOfPeriod(playbackInfo.loadingMediaPeriodId.periodUid)
            if (loadingPeriodIndex == C.INDEX_UNSET
                || (timeline.getPeriod(loadingPeriodIndex, period).windowIndex
                        != timeline.getPeriodByUid(newPeriodId.periodUid, period).windowIndex)
            ) {
                // Discard periods after the playing period, if the loading period is discarded or the
                // playing and loading period are not in the same window.
                timeline.getPeriodByUid(newPeriodId.periodUid, period)
                val maskedBufferedPositionUs =
                    if (newPeriodId.isAd)
                        period.getAdDurationUs(
                            newPeriodId.adGroupIndex,
                            newPeriodId.adIndexInAdGroup
                        )
                    else
                        period.durationUs
                playbackInfo =
                    playbackInfo.copyWithNewPosition(
                        newPeriodId,
                        playbackInfo.positionUs,
                        playbackInfo.positionUs,
                        playbackInfo.discontinuityStartPositionUs,
                        maskedBufferedPositionUs - playbackInfo.positionUs,
                        playbackInfo.trackGroups,
                        playbackInfo.trackSelectorResult,
                        playbackInfo.staticMetadata
                    )
                playbackInfo = playbackInfo.copyWithLoadingMediaPeriodId(newPeriodId)
                playbackInfo.bufferedPositionUs = maskedBufferedPositionUs
            }
        } else {
            Preconditions.checkState(!newPeriodId.isAd)
            // A forward seek within the playing period (timeline did not change).
            val maskedTotalBufferedDurationUs =
                max(
                    0,
                    playbackInfo.totalBufferedDurationUs - (newContentPositionUs - oldContentPositionUs)
                )
            var maskedBufferedPositionUs = playbackInfo.bufferedPositionUs
            if (playbackInfo.loadingMediaPeriodId == playbackInfo.periodId) {
                maskedBufferedPositionUs = newContentPositionUs + maskedTotalBufferedDurationUs
            }
            playbackInfo =
                playbackInfo.copyWithNewPosition(
                    newPeriodId,
                    newContentPositionUs,
                    newContentPositionUs,
                    newContentPositionUs,
                    maskedTotalBufferedDurationUs,
                    playbackInfo.trackGroups,
                    playbackInfo.trackSelectorResult,
                    playbackInfo.staticMetadata
                )
            playbackInfo.bufferedPositionUs = maskedBufferedPositionUs
        }
        return playbackInfo
    }

    private fun getPeriodPositionUsAfterTimelineChanged(
        oldTimeline: Timeline,
        newTimeline: Timeline,
        currentWindowIndexInternal: Int,
        contentPositionMs: Long
    ): Pair<Any, Long>? {
        if (oldTimeline.isEmpty || newTimeline.isEmpty) {
            val isCleared = !oldTimeline.isEmpty && newTimeline.isEmpty
            return maskWindowPositionMsOrGetPeriodPositionUs(
                newTimeline,
                if (isCleared) C.INDEX_UNSET else currentWindowIndexInternal,
                if (isCleared) C.TIME_UNSET else contentPositionMs
            )
        }
        val oldPeriodPositionUs: Pair<Any, Long> =
            oldTimeline.getPeriodPositionUs(
                window, period, currentWindowIndexInternal, Util.msToUs(contentPositionMs)
            )
        val periodUid = oldPeriodPositionUs.first
        if (newTimeline.getIndexOfPeriod(periodUid) != C.INDEX_UNSET) {
            // The old period position is still available in the new timeline.
            return oldPeriodPositionUs
        }
        // Period uid not found in new timeline. Try to get subsequent period.
        val newWindowIndex: Int =
            ExoPlayerImplInternal.resolveSubsequentPeriod(
                window,
                period,
                _repeatMode,
                _shuffleModeEnabled,
                periodUid,
                oldTimeline,
                newTimeline
            )
        if (newWindowIndex != C.INDEX_UNSET) {
            // Reset position to the default position of the window of the subsequent period.
            return maskWindowPositionMsOrGetPeriodPositionUs(
                newTimeline,
                newWindowIndex,
                newTimeline.getWindow(newWindowIndex, window).defaultPositionMs
            )
        } else {
            // No subsequent period found and the new timeline is not empty. Use the default position.
            return maskWindowPositionMsOrGetPeriodPositionUs(
                newTimeline, C.INDEX_UNSET, C.TIME_UNSET
            )
        }
    }

    private fun maskWindowPositionMsOrGetPeriodPositionUs(
        timeline: Timeline, windowIndex: Int, windowPositionMs: Long
    ): Pair<Any, Long>? {
        var windowIndex = windowIndex
        var windowPositionMs = windowPositionMs
        if (timeline.isEmpty) {
            // If empty we store the initial seek in the masking variables.
            maskingWindowIndex = windowIndex
            maskingWindowPositionMs = if (windowPositionMs == C.TIME_UNSET) 0 else windowPositionMs
            return null
        }
        if (windowIndex == C.INDEX_UNSET || windowIndex >= timeline.windowCount) {
            // Use default position of timeline if window index still unset or if a previous initial seek
            // now turns out to be invalid.
            windowIndex = timeline.getFirstWindowIndex(_shuffleModeEnabled)
            windowPositionMs = timeline.getWindow(windowIndex, window).defaultPositionMs
        }
        return timeline.getPeriodPositionUs(
            window,
            period,
            windowIndex,
            Util.msToUs(windowPositionMs)
        )
    }

    private fun periodPositionUsToWindowPositionUs(
        timeline: Timeline, periodId: MediaPeriodId, positionUs: Long
    ): Long {
        var positionUs = positionUs
        timeline.getPeriodByUid(periodId.periodUid, period)
        positionUs += period.positionInWindowUs
        return positionUs
    }

    private fun createMessageInternal(target: PlayerMessage.Target?): PlayerMessage {
        val currentWindowIndex = getCurrentWindowIndexInternal(playbackInfo)
        return PlayerMessage(
            internalPlayer,
            target,
            playbackInfo.timeline,
            if (currentWindowIndex == C.INDEX_UNSET) 0 else currentWindowIndex,
            clock,
            internalPlayer.playbackLooper
        )
    }

    private fun buildUpdatedMediaMetadata(): MediaMetadata {
        val timeline = currentTimeline
        if (timeline.isEmpty) {
            return staticAndDynamicMediaMetadata!!
        }
        val mediaItem = timeline.getWindow(currentMediaItemIndex, window).mediaItem
        // MediaItem metadata is prioritized over metadata within the media.
        return staticAndDynamicMediaMetadata!!.buildUpon().populate(mediaItem.mediaMetadata).build()
    }

    private fun removeSurfaceCallbacks() {
        sphericalGLSurfaceView?.also {
            createMessageInternal(frameMetadataListener)
                .setType(FrameMetadataListener.MSG_SET_SPHERICAL_SURFACE_VIEW)
                .setPayload(null)
                .send()
            it.removeVideoSurfaceListener(componentListener)
            sphericalGLSurfaceView = null
        }
        textureView?.also {
            if (it.surfaceTextureListener !== componentListener) {
                Log.w(TAG, "SurfaceTextureListener already unset or replaced.")
            } else {
                it.surfaceTextureListener = null
            }
            textureView = null
        }
        surfaceHolder?.also {
            it.removeCallback(componentListener)
            surfaceHolder = null
        }
    }

    private fun setSurfaceTextureInternal(surfaceTexture: SurfaceTexture?) {
        val surface = Surface(surfaceTexture)
        setVideoOutputInternal(surface)
        ownedSurface = surface
    }

    private fun setVideoOutputInternal(videoOutput: Any?) {
        val isReplacingVideoOutput = this.videoOutput != null && this.videoOutput !== videoOutput
        val timeoutMs = if (isReplacingVideoOutput) detachSurfaceTimeoutMs else C.TIME_UNSET
        // Note: We don't turn this method into a no-op if the output is being replaced with itself so
        // as to ensure onRenderedFirstFrame callbacks are still called in this case.
        val isSuccess = internalPlayer.setVideoOutput(videoOutput, timeoutMs)
        if (isReplacingVideoOutput) {
            if (this.videoOutput === ownedSurface) {
                // We're replacing a surface that we are responsible for releasing.
                ownedSurface!!.release()
                ownedSurface = null
            }
        }
        this.videoOutput = videoOutput
        if (!isSuccess) {
            stopInternal(
                ExoPlaybackException.createForUnexpected(
                    ExoTimeoutException(ExoTimeoutException.TIMEOUT_OPERATION_DETACH_SURFACE),
                    PlaybackException.ERROR_CODE_TIMEOUT
                )
            )
        }
    }

    private fun setNonVideoOutputSurfaceHolderInternal(nonVideoOutputSurfaceHolder: SurfaceHolder?) {
        // Although we won't use the view's surface directly as the video output, still use the holder
        // to query the surface size, to be informed in changes to the size via componentListener, and
        // for equality checking in clearVideoSurfaceHolder.
        surfaceHolderSurfaceIsVideoOutput = false
        surfaceHolder = nonVideoOutputSurfaceHolder
        surfaceHolder!!.addCallback(componentListener)
        val surface = surfaceHolder!!.surface
        if (surface != null && surface.isValid) {
            val surfaceSize = surfaceHolder!!.surfaceFrame
            maybeNotifySurfaceSizeChanged(surfaceSize.width(), surfaceSize.height())
        } else {
            maybeNotifySurfaceSizeChanged(0, 0)
        }
    }

    private fun maybeNotifySurfaceSizeChanged(width: Int, height: Int) {
        if (width != _surfaceSize!!.width || height != _surfaceSize!!.height) {
            _surfaceSize = Size(width, height)
            listeners.sendEvent(Player.EVENT_SURFACE_SIZE_CHANGED) { listener: Player.Listener ->
                listener.onSurfaceSizeChanged(width, height)
            }
            sendRendererMessage(
                C.TRACK_TYPE_VIDEO, Renderer.MSG_SET_VIDEO_OUTPUT_RESOLUTION, Size(width, height)
            )
        }
    }

    private fun maybeUpdatePlaybackSuppressionReason() {
        updatePlayWhenReady(playbackInfo.playWhenReady, playbackInfo.playWhenReadyChangeReason)
    }

    private fun updatePlayWhenReady(
        playWhenReady: Boolean, playWhenReadyChangeReason: @Player.PlayWhenReadyChangeReason Int
    ) {
        val playbackSuppressionReason = computePlaybackSuppressionReason(playWhenReady)
        if (playbackInfo.playWhenReady == playWhenReady && playbackInfo.playbackSuppressionReason == playbackSuppressionReason && playbackInfo.playWhenReadyChangeReason == playWhenReadyChangeReason) {
            return
        }
        pendingOperationAcks++
        // Position estimation and copy must occur before changing/masking playback state.
        var newPlaybackInfo =
            if (playbackInfo.sleepingForOffload)
                playbackInfo.copyWithEstimatedPosition()
            else
                playbackInfo
        newPlaybackInfo =
            newPlaybackInfo.copyWithPlayWhenReady(
                playWhenReady, playWhenReadyChangeReason, playbackSuppressionReason
            )
        internalPlayer.setPlayWhenReady(
            playWhenReady, playWhenReadyChangeReason, playbackSuppressionReason
        )
        updatePlaybackInfo(
            newPlaybackInfo,
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
            false,
            Player.DISCONTINUITY_REASON_INTERNAL,
            C.TIME_UNSET,
            C.INDEX_UNSET,
            false
        )
    }

    private fun computePlaybackSuppressionReason(playWhenReady: Boolean): @Player.PlaybackSuppressionReason Int {
        if (scrubbingModeEnabled) {
            return Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING
        }
        if (suitableOutputChecker?.isSelectedOutputSuitableForPlayback == false) {
            return Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT
        }
        if (playbackInfo.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
            && !playWhenReady
        ) {
            return Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
        }
        return Player.PLAYBACK_SUPPRESSION_REASON_NONE
    }

    private fun updateWakeAndWifiLock() {
        val playbackState: @Player.State Int = playbackState
        when (playbackState) {
            Player.STATE_READY, Player.STATE_BUFFERING -> {
                val isSleeping = isSleepingForOffload
                wakeLockManager.setStayAwake(playWhenReady && !isSleeping)
                // The wifi lock is not released while sleeping to avoid interrupting downloads.
                wifiLockManager.setStayAwake(playWhenReady)
            }

            Player.STATE_ENDED, Player.STATE_IDLE -> {
                wakeLockManager.setStayAwake(false)
                wifiLockManager.setStayAwake(false)
            }

            else -> throw IllegalStateException()
        }
    }

    private fun sendRendererMessage(messageType: Int, payload: Any?) {
        sendRendererMessage(-1, messageType, payload)
    }

    private fun sendRendererMessage(
        trackType: @C.TrackType Int, messageType: Int, payload: Any?
    ) {
        for (renderer in renderers) {
            if (trackType == -1 || renderer.trackType == trackType) {
                createMessageInternal(renderer).setType(messageType).setPayload(payload).send()
            }
        }
        for (renderer in secondaryRenderers) {
            if (renderer != null && (trackType == -1 || renderer.trackType == trackType)) {
                createMessageInternal(renderer).setType(messageType).setPayload(payload).send()
            }
        }
    }

    private fun updatePriorityTaskManagerForIsLoadingChange(isLoading: Boolean) {
        if (priorityTaskManager != null) {
            if (isLoading && !isPriorityTaskManagerRegistered) {
                priorityTaskManager!!.add(priority)
                isPriorityTaskManagerRegistered = true
            } else if (!isLoading && isPriorityTaskManagerRegistered) {
                priorityTaskManager!!.remove(priority)
                isPriorityTaskManagerRegistered = false
            }
        }
    }

    private fun canUpdateMediaSourcesWithMediaItems(
        fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>
    ): Boolean {
        if (toIndex - fromIndex != mediaItems.size) {
            // Number of items doesn't match.
            return false
        }
        for (i in fromIndex..<toIndex) {
            val mediaSource = mediaSourceHolderSnapshots[i].mediaSource
            if (!mediaSource.canUpdateMediaItem(mediaItems[i - fromIndex])) {
                return false
            }
        }
        return true
    }

    private fun updateMediaSourcesWithMediaItems(
        fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>
    ) {
        pendingOperationAcks++
        internalPlayer.updateMediaSourcesWithMediaItems(fromIndex, toIndex, mediaItems)
        for (i in fromIndex..<toIndex) {
            val snapshot = mediaSourceHolderSnapshots[i]
            val mediaItem = mediaItems[i - fromIndex]
            snapshot.updateTimeline(
                TimelineWithUpdatedMediaItem.create(snapshot.timeline, mediaItem)
            )
        }
        val newTimeline = createMaskingTimeline()
        val newPlaybackInfo = playbackInfo.copyWithTimeline(newTimeline)
        updatePlaybackInfo(
            newPlaybackInfo,
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
            false,
            Player.DISCONTINUITY_REASON_REMOVE,
            C.TIME_UNSET,
            C.INDEX_UNSET,
            false
        )
    }

    private fun onSelectedOutputSuitabilityChanged(isSelectedOutputSuitableForPlayback: Boolean) {
        if (playerReleased) {
            return
        }
        if (isSelectedOutputSuitableForPlayback) {
            if (playbackInfo.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT) {
                maybeUpdatePlaybackSuppressionReason()
            }
        } else {
            maybeUpdatePlaybackSuppressionReason()
        }
    }

    private fun onAudioSessionIdChanged(oldAudioSessionId: Int, newAudioSessionId: Int) {
        internalPlayer.setAudioSessionId(newAudioSessionId, false)
        listeners.sendEvent(Player.EVENT_AUDIO_SESSION_ID) { listener: Player.Listener ->
            listener.onAudioSessionIdChanged(newAudioSessionId)
        }
    }

    private inner class CodecParameterListenerManager(private val trackType: @C.TrackType Int) {
        private val listeners = mutableMapOf<CodecParametersChangeListener?, List<String?>>()
        private var lastNotifiedParameters = CodecParameters.EMPTY

        fun addListener(listener: CodecParametersChangeListener, keys: List<String?>) {
            listeners[listener] = keys
            updateAndSendSubscribedKeysToRenderer()
            // Immediately notify the new listener with its filtered view of the last known state.
            val listenerInitialState =
                createFilteredCodecParameters(lastNotifiedParameters, keys)
            listener.onCodecParametersChanged(listenerInitialState)
        }

        fun removeListener(listener: CodecParametersChangeListener?) {
            if (listeners.remove(listener) != null) {
                updateAndSendSubscribedKeysToRenderer()
            }
        }

        fun onParametersChanged(newParameters: CodecParameters) {
            for (entry in HashMap<CodecParametersChangeListener?, List<String?>?>(listeners).entries) {
                val listener: CodecParametersChangeListener = entry.key!!
                val listenerKeys: List<String?> = entry.value!!
                val listenerCurrentState =
                    createFilteredCodecParameters(newParameters, listenerKeys)
                val listenerPreviousState =
                    createFilteredCodecParameters(lastNotifiedParameters, listenerKeys)

                if (listenerCurrentState != listenerPreviousState) {
                    listener.onCodecParametersChanged(listenerCurrentState)
                }
            }
            lastNotifiedParameters = newParameters
        }

        fun updateAndSendSubscribedKeysToRenderer() {
            val newKeysBuilder = mutableSetOf<String?>()
            for (keys in listeners.values) {
                newKeysBuilder.addAll(keys)
            }
            sendRendererMessage(
                trackType, Renderer.MSG_SET_SUBSCRIBED_CODEC_PARAMETER_KEYS, newKeysBuilder
            )
        }

        fun createFilteredCodecParameters(
            source: CodecParameters, keys: List<String?>
        ): CodecParameters {
            val builder = source.buildUpon()
            val keysToKeep = keys.toSet()
            for (key in source.keySet()) {
                if (!keysToKeep.contains(key)) {
                    builder.remove(key)
                }
            }
            return builder.build()
        }
    }

    private class MediaSourceHolderSnapshot(
        override val uid: Any?,
        val mediaSource: MaskingMediaSource
    ) : MediaSourceInfoHolder {
        override var timeline: Timeline = mediaSource.getTimeline()

        fun updateTimeline(timeline: Timeline) {
            this.timeline = timeline
        }
    }

    private inner class ComponentListener
        : VideoRendererEventListener, AudioRendererEventListener, TextOutput, MetadataOutput,
        SurfaceHolder.Callback, SurfaceTextureListener, VideoSurfaceListener,
        AudioBecomingNoisyManager.Listener, StreamVolumeManager.Listener, AudioOffloadListener,
        StuckPlayerDetector.Callback {
        // VideoRendererEventListener implementation
        override fun onVideoEnabled(counters: DecoderCounters) {
            _videoDecoderCounters = counters
            _analyticsCollector.onVideoEnabled(counters)
        }

        override fun onVideoDecoderInitialized(
            decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long
        ) {
            _analyticsCollector.onVideoDecoderInitialized(
                decoderName, initializedTimestampMs, initializationDurationMs
            )
        }

        override fun onVideoInputFormatChanged(
            format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            _videoFormat = format
            _analyticsCollector.onVideoInputFormatChanged(format, decoderReuseEvaluation)
        }

        override fun onDroppedFrames(count: Int, elapsedMs: Long) {
            _analyticsCollector.onDroppedFrames(count, elapsedMs)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            this@ExoPlayerImpl.videoSize = videoSize
            listeners.sendEvent(Player.EVENT_VIDEO_SIZE_CHANGED) { listener: Player.Listener ->
                listener.onVideoSizeChanged(videoSize)
            }
        }

        override fun onRenderedFirstFrame(output: Any, renderTimeMs: Long) {
            _analyticsCollector.onRenderedFirstFrame(output, renderTimeMs)
            if (videoOutput === output) {
                listeners.sendEvent(Player.EVENT_RENDERED_FIRST_FRAME) { obj: Player.Listener -> obj.onRenderedFirstFrame() }
            }
        }

        override fun onVideoDecoderReleased(decoderName: String) {
            _analyticsCollector.onVideoDecoderReleased(decoderName)
        }

        override fun onVideoDisabled(counters: DecoderCounters) {
            _analyticsCollector.onVideoDisabled(counters)
            _videoFormat = null
            _videoDecoderCounters = null
        }

        override fun onVideoFrameProcessingOffset(totalProcessingOffsetUs: Long, frameCount: Int) {
            _analyticsCollector.onVideoFrameProcessingOffset(totalProcessingOffsetUs, frameCount)
        }

        override fun onVideoCodecError(videoCodecError: Exception) {
            _analyticsCollector.onVideoCodecError(videoCodecError)
        }

        // AudioRendererEventListener implementation
        override fun onAudioEnabled(counters: DecoderCounters) {
            _audioDecoderCounters = counters
            _analyticsCollector.onAudioEnabled(counters)
        }

        override fun onAudioDecoderInitialized(
            decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long
        ) {
            _analyticsCollector.onAudioDecoderInitialized(
                decoderName, initializedTimestampMs, initializationDurationMs
            )
        }

        override fun onAudioInputFormatChanged(
            format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            _audioFormat = format
            _analyticsCollector.onAudioInputFormatChanged(format, decoderReuseEvaluation)
        }

        override fun onAudioPositionAdvancing(playoutStartSystemTimeMs: Long) {
            _analyticsCollector.onAudioPositionAdvancing(playoutStartSystemTimeMs)
        }

        override fun onAudioUnderrun(
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long
        ) {
            _analyticsCollector.onAudioUnderrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs)
        }

        override fun onAudioDecoderReleased(decoderName: String) {
            _analyticsCollector.onAudioDecoderReleased(decoderName)
        }

        override fun onAudioDisabled(counters: DecoderCounters) {
            _analyticsCollector.onAudioDisabled(counters)
            _audioFormat = null
            _audioDecoderCounters = null
        }

        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {
            if (_skipSilenceEnabled == skipSilenceEnabled) {
                return
            }
            _skipSilenceEnabled = skipSilenceEnabled
            listeners.sendEvent(Player.EVENT_SKIP_SILENCE_ENABLED_CHANGED) { listener: Player.Listener ->
                listener.onSkipSilenceEnabledChanged(skipSilenceEnabled)
            }
        }

        override fun onAudioSinkError(audioSinkError: Exception) {
            _analyticsCollector.onAudioSinkError(audioSinkError)
        }

        override fun onAudioCodecError(audioCodecError: Exception) {
            _analyticsCollector.onAudioCodecError(audioCodecError)
        }

        override fun onAudioTrackInitialized(audioTrackConfig: AudioTrackConfig) {
            _analyticsCollector.onAudioTrackInitialized(audioTrackConfig)
        }

        override fun onAudioTrackReleased(audioTrackConfig: AudioTrackConfig) {
            _analyticsCollector.onAudioTrackReleased(audioTrackConfig)
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            audioSessionIdState.updateStateAsync(
                { previousId: Int -> audioSessionId },
                { previousId: Int -> audioSessionId })
        }

        override fun onAudioCodecParametersChanged(codecParameters: CodecParameters) {
            audioListenerManager.onParametersChanged(codecParameters)
        }

        override fun onVideoCodecParametersChanged(codecParameters: CodecParameters) {
            videoListenerManager.onParametersChanged(codecParameters)
        }

        override fun onCues(cues: List<Cue>) {
            listeners.sendEvent(Player.EVENT_CUES) { listener: Player.Listener ->
                listener.onCues(cues)
            }
        }

        override fun onCues(cueGroup: CueGroup) {
            currentCueGroup = cueGroup
            listeners.sendEvent(Player.EVENT_CUES) { listener: Player.Listener ->
                listener.onCues(cueGroup)
            }
        }

        // MetadataOutput implementation
        override fun onMetadata(metadata: Metadata) {
            staticAndDynamicMediaMetadata =
                staticAndDynamicMediaMetadata!!.buildUpon().populateFromMetadata(metadata).build()
            val newMediaMetadata = buildUpdatedMediaMetadata()
            if (newMediaMetadata != _mediaMetadata) {
                _mediaMetadata = newMediaMetadata
                listeners.queueEvent(Player.EVENT_MEDIA_METADATA_CHANGED) { listener: Player.Listener ->
                    listener.onMediaMetadataChanged(_mediaMetadata!!)
                }
            }
            listeners.queueEvent(Player.EVENT_METADATA) { listener: Player.Listener ->
                listener.onMetadata(metadata)
            }
            listeners.flushEvents()
        }

        // SurfaceHolder.Callback implementation
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (surfaceHolderSurfaceIsVideoOutput) {
                setVideoOutputInternal(holder.surface)
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            maybeNotifySurfaceSizeChanged(width, height)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (surfaceHolderSurfaceIsVideoOutput) {
                setVideoOutputInternal(null)
            }
            maybeNotifySurfaceSizeChanged(0, 0)
        }

        // TextureView.SurfaceTextureListener implementation
        override fun onSurfaceTextureAvailable(
            surfaceTexture: SurfaceTexture, width: Int, height: Int
        ) {
            setSurfaceTextureInternal(surfaceTexture)
            maybeNotifySurfaceSizeChanged(width, height)
        }

        override fun onSurfaceTextureSizeChanged(
            surfaceTexture: SurfaceTexture, width: Int, height: Int
        ) {
            maybeNotifySurfaceSizeChanged(width, height)
        }

        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
            setVideoOutputInternal(null)
            maybeNotifySurfaceSizeChanged(0, 0)
            return true
        }

        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}

        // SphericalGLSurfaceView.VideoSurfaceListener
        override fun onVideoSurfaceCreated(surface: Surface) {
            setVideoOutputInternal(surface)
        }

        override fun onVideoSurfaceDestroyed(surface: Surface) {
            setVideoOutputInternal(null)
        }

        // AudioBecomingNoisyManager.EventListener implementation.
        override fun onAudioBecomingNoisy() {
            updatePlayWhenReady(
                false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
            )
        }

        // StreamVolumeManager.Listener implementation.
        override fun onStreamTypeChanged(@C.StreamType streamType: @C.StreamType Int) {
            val newDeviceInfo: DeviceInfo = createDeviceInfo(streamVolumeManager)
            if (newDeviceInfo != _deviceInfo) {
                _deviceInfo = newDeviceInfo
                listeners.sendEvent(Player.EVENT_DEVICE_INFO_CHANGED) { listener: Player.Listener ->
                    listener.onDeviceInfoChanged(newDeviceInfo)
                }
            }
        }

        override fun onStreamVolumeChanged(streamVolume: Int, streamMuted: Boolean) {
            listeners.sendEvent(Player.EVENT_DEVICE_VOLUME_CHANGED) { listener: Player.Listener ->
                listener.onDeviceVolumeChanged(streamVolume, streamMuted)
            }
        }

        // Player.AudioOffloadListener implementation.
        override fun onSleepingForOffloadChanged(isSleepingForOffload: Boolean) {
            updateWakeAndWifiLock()
        }

        // StuckPlayerDetector.Callback implementation.
        override fun onStuckPlayerDetected(exception: StuckPlayerException) {
            stopInternal(
                ExoPlaybackException.createForUnexpected(
                    exception, PlaybackException.ERROR_CODE_TIMEOUT
                )
            )
        }
    }

    private class FrameMetadataListener
        : VideoFrameMetadataListener, CameraMotionListener, PlayerMessage.Target {
        private var videoFrameMetadataListener: VideoFrameMetadataListener? = null
        private var cameraMotionListener: CameraMotionListener? = null
        private var internalVideoFrameMetadataListener: VideoFrameMetadataListener? = null
        private var internalCameraMotionListener: CameraMotionListener? = null

        override fun handleMessage(messageType: @Renderer.MessageType Int, message: Any?) {
            when (messageType) {
                MSG_SET_VIDEO_FRAME_METADATA_LISTENER ->
                    videoFrameMetadataListener = message as VideoFrameMetadataListener?

                MSG_SET_CAMERA_MOTION_LISTENER ->
                    cameraMotionListener = message as CameraMotionListener?

                MSG_SET_SPHERICAL_SURFACE_VIEW -> {
                    val surfaceView = message as SphericalGLSurfaceView?
                    if (surfaceView == null) {
                        internalVideoFrameMetadataListener = null
                        internalCameraMotionListener = null
                    } else {
                        internalVideoFrameMetadataListener = surfaceView.videoFrameMetadataListener
                        internalCameraMotionListener = surfaceView.cameraMotionListener
                    }
                }

                Renderer.MSG_SET_AUDIO_ATTRIBUTES, Renderer.MSG_SET_AUDIO_SESSION_ID, Renderer.MSG_SET_AUX_EFFECT_INFO, Renderer.MSG_SET_CHANGE_FRAME_RATE_STRATEGY, Renderer.MSG_SET_SCALING_MODE, Renderer.MSG_SET_SKIP_SILENCE_ENABLED, Renderer.MSG_SET_VIDEO_OUTPUT, Renderer.MSG_SET_VOLUME, Renderer.MSG_SET_WAKEUP_LISTENER -> {}
                else -> {}
            }
        }

        // VideoFrameMetadataListener
        override fun onVideoFrameAboutToBeRendered(
            presentationTimeUs: Long,
            releaseTimeNs: Long,
            format: Format,
            mediaFormat: MediaFormat?
        ) {
            internalVideoFrameMetadataListener?.onVideoFrameAboutToBeRendered(
                presentationTimeUs, releaseTimeNs, format, mediaFormat
            )
            videoFrameMetadataListener?.onVideoFrameAboutToBeRendered(
                presentationTimeUs, releaseTimeNs, format, mediaFormat
            )
        }

        // CameraMotionListener
        override fun onCameraMotion(timeUs: Long, rotation: FloatArray) {
            internalCameraMotionListener?.onCameraMotion(timeUs, rotation)
            cameraMotionListener?.onCameraMotion(timeUs, rotation)
        }

        override fun onCameraMotionReset() {
            internalCameraMotionListener?.onCameraMotionReset()
            cameraMotionListener?.onCameraMotionReset()
        }

        companion object {
            const val MSG_SET_VIDEO_FRAME_METADATA_LISTENER: @Renderer.MessageType Int =
                Renderer.MSG_SET_VIDEO_FRAME_METADATA_LISTENER
            const val MSG_SET_CAMERA_MOTION_LISTENER: @Renderer.MessageType Int =
                Renderer.MSG_SET_CAMERA_MOTION_LISTENER
            const val MSG_SET_SPHERICAL_SURFACE_VIEW: @Renderer.MessageType Int =
                Renderer.MSG_CUSTOM_BASE
        }
    }

    @RequiresApi(31)
    private object Api31 {
        fun registerMediaMetricsListener(
            context: Context,
            player: ExoPlayerImpl,
            usePlatformDiagnostics: Boolean,
            playerId: PlayerId
        ) {
            val playbackThreadHandler = player.clock.createHandler(player.playbackLooper, null)
            playbackThreadHandler.post {
                val listener = MediaMetricsListener.create(context)
                if (listener == null) {
                    Log.w(TAG, "MediaMetricsService unavailable.")
                    return@post
                }
                if (usePlatformDiagnostics) {
                    player.addAnalyticsListener(listener)
                }
                playerId.logSessionId = listener.logSessionId
            }
        }
    }

    @RequiresApi(34)
    private inner class VirtualDeviceIdChangeListener(context: Context) {
        // We must not keep a strong reference to the original Context to prevent leaking it if the
        // player outlives the Context (e.g. an Activity or Service). However, we need the original
        // Context instance to register and unregister the listener as the application Context does not
        // have the device ID handling.
        private val contextReference = WeakReference(context)
        private val listener =
            IntConsumer { virtualDeviceId: Int -> onVirtualDeviceIdChanged(virtualDeviceId) }

        init {
            val handler = clock.createHandler(applicationLooper, null)
            context.registerDeviceIdChangeListener({ runnable: Runnable ->
                handler.post(runnable)
            }, listener)
        }

        fun release() {
            val context = contextReference.get() ?: return
            context.unregisterDeviceIdChangeListener(listener)
        }

        fun onVirtualDeviceIdChanged(virtualDeviceId: Int) {
            if (playerReleased) {
                return
            }
            sendRendererMessage(
                C.TRACK_TYPE_AUDIO,
                Renderer.MSG_SET_VIRTUAL_DEVICE_ID,
                virtualDeviceId
            )
        }
    }

    companion object {
        private const val TAG = "ExoPlayerImpl"

        init {
            registerModule("media3.exoplayer")
        }

        private fun getRequestedContentPositionUs(playbackInfo: PlaybackInfo): Long {
            val window = Timeline.Window()
            val period = Timeline.Period()
            playbackInfo.timeline.getPeriodByUid(playbackInfo.periodId.periodUid, period)
            return if (playbackInfo.requestedContentPositionUs == C.TIME_UNSET)
                playbackInfo.timeline.getWindow(period.windowIndex, window).defaultPositionUs
            else
                period.positionInWindowUs + playbackInfo.requestedContentPositionUs
        }

        private fun maskPlaybackState(
            playbackInfo: PlaybackInfo, playbackState: Int
        ): PlaybackInfo {
            var playbackInfo = playbackInfo.copyWithPlaybackState(playbackState)
            if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                playbackInfo = playbackInfo.copyWithIsLoading(false)
            }
            return playbackInfo
        }

        private fun createDeviceInfo(streamVolumeManager: StreamVolumeManager?): DeviceInfo {
            return DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_LOCAL)
                .setMinVolume(streamVolumeManager?.minVolume ?: 0)
                .setMaxVolume(streamVolumeManager?.maxVolume ?: 0)
                .build()
        }

        private fun addDisabledTrackTypes(
            parameters: TrackSelectionParameters, trackTypesToDisable: Set<Int>
        ): TrackSelectionParameters {
            val parametersBuilder = parameters.buildUpon()
            trackTypesToDisable.forEach { parametersBuilder.setTrackTypeDisabled(it, true) }
            return parametersBuilder.build()
        }
    }
}
