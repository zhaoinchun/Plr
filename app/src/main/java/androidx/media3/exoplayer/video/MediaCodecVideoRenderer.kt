
package androidx.media3.exoplayer.video

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.view.Display
import android.view.Display.HdrCapabilities
import android.view.Surface
import androidx.annotation.CallSuper
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.MediaLibraryInfo.enableWorkarounds
import androidx.media3.common.MimeTypes
import androidx.media3.common.MimeTypes.isVideo
import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.VideoSize
import androidx.media3.common.util.CodecSpecificDataUtil.getCodecProfileAndLevel

import androidx.media3.common.util.Log.d
import androidx.media3.common.util.Log.e
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.TraceUtil.beginSection
import androidx.media3.common.util.TraceUtil.endSection

import androidx.media3.common.util.Util.ceilDivide
import androidx.media3.common.util.Util.createHandlerForCurrentLooper
import androidx.media3.common.util.Util.isFrameDropAllowedOnSurfaceInput
import androidx.media3.common.util.Util.msToUs
import androidx.media3.common.util.Util.toLong
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.CodecParameters
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DecoderReuseEvaluation.DecoderDiscardReasons
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RendererCapabilities.AdaptiveSupport
import androidx.media3.exoplayer.RendererCapabilities.Capabilities
import androidx.media3.exoplayer.RendererCapabilities.DecoderSupport
import androidx.media3.exoplayer.RendererCapabilities.HardwareAccelerationSupport
import androidx.media3.exoplayer.RendererCapabilities.TunnelingSupport
import androidx.media3.exoplayer.ScrubbingModeParameters
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter.Configuration
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter.Factory
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getAlternativeDecoderInfos
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfosSoftMatch
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfosSortedByFormatSupport
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.video.VideoFrameReleaseControl.FrameReleaseAction
import androidx.media3.exoplayer.video.VideoFrameReleaseControl.FrameReleaseInfo
import androidx.media3.exoplayer.video.VideoFrameReleaseControl.FrameTimingEvaluator
import androidx.media3.exoplayer.video.VideoSink.FirstFrameReleaseInstruction
import androidx.media3.exoplayer.video.VideoSink.VideoFrameHandler
import androidx.media3.exoplayer.video.VideoSink.VideoSinkException
import com.google.common.base.Preconditions
import com.google.common.util.concurrent.MoreExecutors
import org.checkerframework.checker.initialization.qual.Initialized

import org.checkerframework.checker.nullness.qual.RequiresNonNull
import java.nio.ByteBuffer
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MediaCodecVideoRenderer protected constructor(builder: Builder) : MediaCodecRenderer(
    builder.context.applicationContext,
    C.TRACK_TYPE_VIDEO,
    builder.codecAdapterFactory,
    builder.mediaCodecSelector,
    builder.enableDecoderFallback,
    builder.assumedMinimumCodecOperatingRate
), FrameTimingEvaluator {
    private val context: Context
    private val ownsVideoSink: Boolean
    private val eventDispatcher: VideoRendererEventListener.EventDispatcher
    private val maxDroppedFramesToNotify: Int
    private val deviceNeedsNoPostProcessWorkaround: Boolean
    private val videoFrameReleaseControl: VideoFrameReleaseControl
    private val videoFrameReleaseInfo: FrameReleaseInfo
    private val frameRateEstimator: FixedFrameRateEstimator
    private val av1SampleDependencyParser: Av1SampleDependencyParser?
    private val minEarlyUsToDropDecoderInput: Long
    private val videoFrameReleaseEarlyTimeForecaster: VideoFrameReleaseEarlyTimeForecaster?
    private val discardedDecoderInputBufferTimestamps: PriorityQueue<Long?>
    private val enableMediaCodecBufferDecodeOnlyFlag: Boolean
    private val enableDurationToProgressUs: Boolean

    private var codecMaxValues:CodecMaxValues? = null
    private var codecNeedsSetOutputSurfaceWorkaround = false
    private var codecNeedsStripNonHdr10PlusT35Workaround = false
    private var codecHandlesHdr10PlusOutOfBandMetadata = false
    private var isApplyingContainerHagcMetadata = false
    private var videoSink:VideoSink?
    private var hasSetVideoSink = false
    private var nextVideoSinkFirstFrameReleaseInstruction: @FirstFrameReleaseInstruction Int = 0
    private var videoEffects: List<Effect>? =
        null// TODO(b/260702159) Consider renaming the method to getOutputSurface().

    protected var surface: Surface? = null
        private set
    private var placeholderSurface: PlaceholderSurface? = null
    private var outputResolution: Size
    private var haveReportedFirstFrameRenderedForCurrentSurface = false

    @C.VideoScalingMode
    private var scalingMode: @C.VideoScalingMode Int

    @C.VideoChangeFrameRateStrategy
    private var changeFrameRateStrategy: @C.VideoChangeFrameRateStrategy Int
    private var droppedFrameAccumulationStartTimeMs: Long = 0
    private var droppedFrames = 0
    private var consecutiveDroppedFrameCount = 0
    private var buffersInCodecCount = 0
    private var scrubbingModeParameters: ScrubbingModeParameters?
    private var lastResetToKeyFramePositionUs: Long = 0
    private var isFlushRequired = false
    private var totalVideoFrameProcessingOffsetUs: Long = 0
    private var videoFrameProcessingOffsetCount = 0
    private var lastFrameReleaseTimeNs: Long = 0
    private var decodedVideoSize: VideoSize
    private var reportedVideoSize: VideoSize?
    private var rendererPriority: Int

    private var tunneling = false
    private var tunnelingAudioSessionId: Int
    private var tunnelingOnFrameRenderedListener: OnFrameRenderedListener? = null
    private var frameMetadataListener: VideoFrameMetadataListener? = null
    private var startPositionUs: Long
    private var pendingVideoSinkInputStreamChange = false

    private var consecutiveDroppedInputBufferCount = 0
    private var nextOutputBufferToProcessPresentationTimeUs: Long

    class Builder( val context: Context) {
         var buildCalled = false
         var mediaCodecSelector: MediaCodecSelector?
         var codecAdapterFactory: MediaCodecAdapter.Factory
         var allowedJoiningTimeMs: Long = 0
         var enableDecoderFallback = false
         var eventHandler: Handler? = null
         var eventListener: VideoRendererEventListener? = null
         var maxDroppedFramesToNotify = 0
         var assumedMinimumCodecOperatingRate = 0f
         var videoSink: VideoSink? = null
         var parseAv1SampleDependencies = true
         var lateThresholdToDropDecoderInputUs: Long
         var enableMediaCodecBufferDecodeOnlyFlag = false
         var enableDurationToProgressUs = false
         var earlySchedulingThresholdUs: Long

        init {
            this.mediaCodecSelector = MediaCodecSelector.DEFAULT
            this.codecAdapterFactory = Factory.getDefault(context)
            this.lateThresholdToDropDecoderInputUs = DEFAULT_LATE_THRESHOLD_TO_DROP_DECODER_INPUT_US
            this.earlySchedulingThresholdUs = DEFAULT_EARLY_SCHEDULING_THRESHOLD_US
        }

        fun setMediaCodecSelector(mediaCodecSelector: MediaCodecSelector?): Builder {
            this.mediaCodecSelector = mediaCodecSelector
            return this
        }

        fun setCodecAdapterFactory(codecAdapterFactory: MediaCodecAdapter.Factory): Builder {
            this.codecAdapterFactory = codecAdapterFactory
            return this
        }

        fun setAllowedJoiningTimeMs(allowedJoiningTimeMs: Long): Builder {
            this.allowedJoiningTimeMs = allowedJoiningTimeMs
            return this
        }

        fun setEnableDecoderFallback(enableDecoderFallback: Boolean): Builder {
            this.enableDecoderFallback = enableDecoderFallback
            return this
        }

        fun setEventHandler(eventHandler: Handler?): Builder {
            this.eventHandler = eventHandler
            return this
        }

        fun setEventListener(eventListener: VideoRendererEventListener?): Builder {
            this.eventListener = eventListener
            return this
        }

        fun setMaxDroppedFramesToNotify(maxDroppedFramesToNotify: Int): Builder {
            this.maxDroppedFramesToNotify = maxDroppedFramesToNotify
            return this
        }

        fun setAssumedMinimumCodecOperatingRate(assumedMinimumCodecOperatingRate: Float): Builder {
            this.assumedMinimumCodecOperatingRate = assumedMinimumCodecOperatingRate
            return this
        }

        fun setVideoSink(videoSink: VideoSink?): Builder {
            this.videoSink = videoSink
            return this
        }

         // TODO: b/470365670 - Remove method once config is enabled by default.
        fun experimentalSetParseAv1SampleDependencies(parseAv1SampleDependencies: Boolean): Builder {
            this.parseAv1SampleDependencies = parseAv1SampleDependencies
            return this
        }

         // TODO: b/470367421 - Remove or make non-experimental.
        fun experimentalSetLateThresholdToDropDecoderInputUs(
            lateThresholdToDropDecoderInputUs: Long
        ): Builder {
            this.lateThresholdToDropDecoderInputUs = lateThresholdToDropDecoderInputUs
            return this
        }

        @RequiresApi(34)
         // TODO: b/470367414 - Run experiments and enable by default.
        fun experimentalSetEnableMediaCodecBufferDecodeOnlyFlag(
            enableMediaCodecBufferDecodeOnlyFlag: Boolean
        ): Builder {
            this.enableMediaCodecBufferDecodeOnlyFlag = enableMediaCodecBufferDecodeOnlyFlag
            return this
        }

         // TODO: b/369523131 - Remove once experiment is complete.
        fun setEnableDurationToProgressUs(enableDurationToProgressUs: Boolean): Builder {
            this.enableDurationToProgressUs = enableDurationToProgressUs
            return this
        }

         // TODO: b/505688667 - Remove method once threshold is fine-tuned.
        fun setEarlySchedulingThresholdUs(earlySchedulingThresholdUs: Long): Builder {
            this.earlySchedulingThresholdUs = earlySchedulingThresholdUs
            return this
        }

        fun build(): MediaCodecVideoRenderer {
            Preconditions.checkState(!buildCalled)
            Preconditions.checkState(
                (eventHandler == null && eventListener == null)
                        || (eventHandler != null && eventListener != null)
            )
            buildCalled = true
            return MediaCodecVideoRenderer(this)
        }
    }

    @Deprecated("Use {@link Builder} instead.")
    constructor(
        context: Context,
        mediaCodecSelector: MediaCodecSelector?
    ) : this(Builder(context).setMediaCodecSelector(mediaCodecSelector))

    @Deprecated("Use {@link Builder} instead.")
    constructor(
        context: Context,
        mediaCodecSelector: MediaCodecSelector?,
        allowedJoiningTimeMs: Long
    ) : this(
        Builder(context)
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(allowedJoiningTimeMs)
    )

    @Deprecated("Use {@link Builder} instead.")
    constructor(
        context: Context,
        mediaCodecSelector: MediaCodecSelector?,
        allowedJoiningTimeMs: Long,
        eventHandler: Handler?,
        eventListener: VideoRendererEventListener?,
        maxDroppedFramesToNotify: Int
    ) : this(
        Builder(context)
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(allowedJoiningTimeMs)
            .setEventHandler(eventHandler)
            .setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(maxDroppedFramesToNotify)
    )

    @Deprecated("Use {@link Builder} instead.")
    constructor(
        context: Context,
        mediaCodecSelector: MediaCodecSelector?,
        allowedJoiningTimeMs: Long,
        enableDecoderFallback: Boolean,
        eventHandler: Handler?,
        eventListener: VideoRendererEventListener?,
        maxDroppedFramesToNotify: Int
    ) : this(
        Builder(context)
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(allowedJoiningTimeMs)
            .setEnableDecoderFallback(enableDecoderFallback)
            .setEventHandler(eventHandler)
            .setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(maxDroppedFramesToNotify)
    )

    @Deprecated("Use {@link Builder} instead.")
    constructor(
        context: Context,
        codecAdapterFactory: MediaCodecAdapter.Factory,
        mediaCodecSelector: MediaCodecSelector?,
        allowedJoiningTimeMs: Long,
        enableDecoderFallback: Boolean,
        eventHandler: Handler?,
        eventListener: VideoRendererEventListener?,
        maxDroppedFramesToNotify: Int
    ) : this(
        Builder(context)
            .setMediaCodecSelector(mediaCodecSelector)
            .setCodecAdapterFactory(codecAdapterFactory)
            .setAllowedJoiningTimeMs(allowedJoiningTimeMs)
            .setEnableDecoderFallback(enableDecoderFallback)
            .setEventHandler(eventHandler)
            .setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(maxDroppedFramesToNotify)
    )

    @Deprecated("Use {@link Builder} instead.")
    constructor(
        context: Context,
        codecAdapterFactory: MediaCodecAdapter.Factory,
        mediaCodecSelector: MediaCodecSelector?,
        allowedJoiningTimeMs: Long,
        enableDecoderFallback: Boolean,
        eventHandler: Handler?,
        eventListener: VideoRendererEventListener?,
        maxDroppedFramesToNotify: Int,
        assumedMinimumCodecOperatingRate: Float
    ) : this(
        Builder(context)
            .setMediaCodecSelector(mediaCodecSelector)
            .setCodecAdapterFactory(codecAdapterFactory)
            .setAllowedJoiningTimeMs(allowedJoiningTimeMs)
            .setEnableDecoderFallback(enableDecoderFallback)
            .setEventHandler(eventHandler)
            .setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(maxDroppedFramesToNotify)
            .setAssumedMinimumCodecOperatingRate(assumedMinimumCodecOperatingRate)
    )

    @Deprecated("Use {@link Builder} instead.")
    constructor(
        context: Context,
        codecAdapterFactory: MediaCodecAdapter.Factory,
        mediaCodecSelector: MediaCodecSelector?,
        allowedJoiningTimeMs: Long,
        enableDecoderFallback: Boolean,
        eventHandler: Handler?,
        eventListener: VideoRendererEventListener?,
        maxDroppedFramesToNotify: Int,
        assumedMinimumCodecOperatingRate: Float,
        videoSink: VideoSink?
    ) : this(
        Builder(context)
            .setMediaCodecSelector(mediaCodecSelector)
            .setCodecAdapterFactory(codecAdapterFactory)
            .setAllowedJoiningTimeMs(allowedJoiningTimeMs)
            .setEnableDecoderFallback(enableDecoderFallback)
            .setEventHandler(eventHandler)
            .setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(maxDroppedFramesToNotify)
            .setAssumedMinimumCodecOperatingRate(assumedMinimumCodecOperatingRate)
            .setVideoSink(videoSink)
    )

    init {
        this.context = builder.context.getApplicationContext()
        this.maxDroppedFramesToNotify = builder.maxDroppedFramesToNotify
        this.videoSink = builder.videoSink
        eventDispatcher =
            VideoRendererEventListener.EventDispatcher(builder.eventHandler, builder.eventListener)
        ownsVideoSink = videoSink == null
        val thisRef: @Initialized FrameTimingEvaluator = this
        videoFrameReleaseControl =
            VideoFrameReleaseControl(
                this.context,  thisRef, builder.allowedJoiningTimeMs
            )
        videoFrameReleaseControl.setEarlySchedulingThresholdUs(builder.earlySchedulingThresholdUs)
        videoFrameReleaseInfo = FrameReleaseInfo()
        frameRateEstimator =
            FixedFrameRateEstimator(FixedFrameRateEstimator.Listener { frameRate: Float ->
                this.onFrameRateEstimateChanged(frameRate)
            })
        deviceNeedsNoPostProcessWorkaround = deviceNeedsNoPostProcessWorkaround()
        outputResolution = Size.UNKNOWN
        scalingMode = C.VIDEO_SCALING_MODE_DEFAULT
        changeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
        decodedVideoSize = VideoSize.UNKNOWN
        tunnelingAudioSessionId = C.AUDIO_SESSION_ID_UNSET
        reportedVideoSize = null
        rendererPriority = C.PRIORITY_PLAYBACK
        startPositionUs = C.TIME_UNSET
        av1SampleDependencyParser =
            if (builder.parseAv1SampleDependencies) Av1SampleDependencyParser() else null
        discardedDecoderInputBufferTimestamps = PriorityQueue<Long?>()
        if (builder.lateThresholdToDropDecoderInputUs != C.TIME_UNSET) {
            minEarlyUsToDropDecoderInput = -builder.lateThresholdToDropDecoderInputUs
            videoFrameReleaseEarlyTimeForecaster =
                VideoFrameReleaseEarlyTimeForecaster( 1f)
        } else {
            minEarlyUsToDropDecoderInput = C.TIME_UNSET
            videoFrameReleaseEarlyTimeForecaster = null
        }
        enableMediaCodecBufferDecodeOnlyFlag = builder.enableMediaCodecBufferDecodeOnlyFlag
        enableDurationToProgressUs = builder.enableDurationToProgressUs
        nextOutputBufferToProcessPresentationTimeUs = C.TIME_UNSET
        scrubbingModeParameters = null
    }

    // FrameTimingEvaluator methods
    override fun shouldForceReleaseFrame(earlyUs: Long, elapsedSinceLastReleaseUs: Long): Boolean {
        return shouldForceRenderOutputBuffer(earlyUs, elapsedSinceLastReleaseUs)
    }

    override fun shouldDropFrame(
        earlyUs: Long,
        elapsedRealtimeUs: Long,
        isLastFrame: Boolean
    ): Boolean {
        return shouldDropOutputBuffer(earlyUs, elapsedRealtimeUs, isLastFrame)
    }

    @Throws(ExoPlaybackException::class)
    override fun shouldIgnoreFrame(
        earlyUs: Long,
        positionUs: Long,
        elapsedRealtimeUs: Long,
        isLastFrame: Boolean,
        treatDroppedBuffersAsSkipped: Boolean
    ): Boolean {
        var positionUs = positionUs
        if (videoSink != null && ownsVideoSink) {
            // When using PlaybackVideoGraphWrapper, positionUs is shifted by the buffer timestamp
            // adjustment. Shift it back to the player position.
            positionUs -= this.bufferTimestampAdjustmentUs
        }
        return shouldDropBuffersToKeyframe(earlyUs, elapsedRealtimeUs, isLastFrame)
                && maybeDropBuffersToKeyframe(positionUs, treatDroppedBuffersAsSkipped)
    }

    override val name: String = TAG

    @Throws(DecoderQueryException::class)
    override fun supportsFormat(
        mediaCodecSelector: MediaCodecSelector,
        format: Format
    ): @RendererCapabilities.Capabilities Int {
        return supportsFormatInternal(context, mediaCodecSelector, format)
    }

    @Throws(DecoderQueryException::class)
    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector, format: Format, requiresSecureDecoder: Boolean
    ): List<MediaCodecInfo> {
        return MediaCodecUtil.getDecoderInfosSortedByFormatSupport(
            context,
            getDecoderInfos(context, mediaCodecSelector, format, requiresSecureDecoder, tunneling),
            format
        )
    }

    @RequiresApi(26)
    private object Api26 {
        fun doesDisplaySupportDolbyVision(context: Context): Boolean {
            val displayManager =
                context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager?
            val display =
                if (displayManager != null) displayManager.getDisplay(Display.DEFAULT_DISPLAY) else null
            if (display == null || !display.isHdr()) {
                return false
            }
            val hdrCapabilities = display.getHdrCapabilities()
            if (hdrCapabilities == null) {
                return false
            }
            for (hdrType in hdrCapabilities.getSupportedHdrTypes()) {
                if (hdrType == HdrCapabilities.HDR_TYPE_DOLBY_VISION) {
                    return true
                }
            }
            return false
        }
    }

    override fun onInit() {
        super.onInit()
    }

    @Throws(ExoPlaybackException::class)
    protected override fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) {
        super.onEnabled(joining, mayRenderStartOfStream)
        val tunneling = getConfiguration().tunneling
        Preconditions.checkState(!tunneling || tunnelingAudioSessionId != C.AUDIO_SESSION_ID_UNSET)
        if (this.tunneling != tunneling) {
            this.tunneling = tunneling
            releaseCodec()
        }
        eventDispatcher.enabled(decoderCounters)
        // The video sink can only be enabled the first time the renderer is enabled, or after it has
        // been reset.
        if (!hasSetVideoSink) {
            if (videoEffects != null && videoSink == null) {
                val playbackVideoGraphWrapper =
                    createPlaybackVideoGraphWrapper(context, videoFrameReleaseControl)
                playbackVideoGraphWrapper.setTotalVideoInputCount(1)
                videoSink = playbackVideoGraphWrapper.getSink( 0)
            }
            hasSetVideoSink = true
        }
        if (videoSink != null) {
            // Configure the VideoSink every time the renderer is enabled, in case the parameters have
            // been overridden by another renderer. Also configure the VideoSink with the parameters that
            // have been set on the renderer before creating the VideoSink.
            configureVideoSink()
            nextVideoSinkFirstFrameReleaseInstruction =
                if (mayRenderStartOfStream)
                    VideoSink.RELEASE_FIRST_FRAME_IMMEDIATELY
                else
                    VideoSink.RELEASE_FIRST_FRAME_WHEN_STARTED
            experimentalEnableProcessedStreamChangedAtStart()
        } else {
            videoFrameReleaseControl.setClock(getClock())
            val firstFrameReleaseInstruction: Int =
                if (mayRenderStartOfStream)
                    VideoSink.RELEASE_FIRST_FRAME_IMMEDIATELY
                else
                    VideoSink.RELEASE_FIRST_FRAME_WHEN_STARTED
            videoFrameReleaseControl.onStreamChanged(firstFrameReleaseInstruction)
        }
    }

    @RequiresNonNull("videoSink")
    private fun configureVideoSink() {
        videoSink!!.setListener(
            object : VideoSink.Listener {
                override fun onFrameAvailableForRendering() {
                    val wakeupListener = wakeupListener
                    if (wakeupListener != null) {
                        wakeupListener.onWakeup()
                    }
                }

                override fun onFirstFrameRendered() {
                    if (this@MediaCodecVideoRenderer.surface != null) {
                        notifyRenderedFirstFrame()
                    }
                }

                override fun onFrameDropped() {
                    if (this@MediaCodecVideoRenderer.surface != null) {
                        updateDroppedBufferCounters( 
                            0,  1
                        )
                    }
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    // TODO: b/292111083 - Report video size change to app. Video size reporting is
                    //  removed at the moment to ensure the first frame is rendered, and the video is
                    //  rendered after switching on/off the screen.
                }

                override fun onError(videoSinkException: VideoSinkException) {
                    setPendingPlaybackException(
                        createRendererException(
                            videoSinkException,
                            videoSinkException.format,
                            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED
                        )
                    )
                }
            },  // Pass a direct executor since the callback handling involves posting on the app looper
            // again, so there's no need to do two hops.
            MoreExecutors.directExecutor()
        )
        if (frameMetadataListener != null) {
            videoSink!!.setVideoFrameMetadataListener(frameMetadataListener)
        }
        if (this.surface != null && !outputResolution.equals(Size.UNKNOWN)) {
            videoSink!!.setOutputSurfaceInfo(this.surface!!, outputResolution)
        }
        videoSink!!.setChangeFrameRateStrategy(changeFrameRateStrategy)
        videoSink!!.setPlaybackSpeed(playbackSpeed)
        if (videoEffects != null) {
            videoSink!!.setVideoEffects(videoEffects!!)
        }
    }

    protected fun createPlaybackVideoGraphWrapper(
        context: Context, videoFrameReleaseControl: VideoFrameReleaseControl
    ): PlaybackVideoGraphWrapper {
        // TODO: b/391109644 - Add a more explicit API to enable replaying.
        return PlaybackVideoGraphWrapper.Builder(context, videoFrameReleaseControl)
            .setEnablePlaylistMode(true)
            .experimentalSetLateThresholdToDropInputUs(
                if (minEarlyUsToDropDecoderInput != C.TIME_UNSET)
                    -minEarlyUsToDropDecoderInput
                else
                    C.TIME_UNSET
            )
            .setClock(getClock())
            .build()
    }

    override fun enableMayRenderStartOfStream() {
        if (videoSink != null) {
            if (nextVideoSinkFirstFrameReleaseInstruction == VideoSink.RELEASE_FIRST_FRAME_IMMEDIATELY
                || nextVideoSinkFirstFrameReleaseInstruction == VideoSink.RELEASE_FIRST_FRAME_WHEN_STARTED
            ) {
                // The first stream change hasn't been queued to the sink.
                nextVideoSinkFirstFrameReleaseInstruction =
                    VideoSink.RELEASE_FIRST_FRAME_IMMEDIATELY
            } else {
                videoSink!!.allowReleaseFirstFrameBeforeStarted()
            }
        } else {
            videoFrameReleaseControl.allowReleaseFirstFrameBeforeStarted()
        }
    }

    @Throws(ExoPlaybackException::class)
    protected override fun onStreamChanged(
        formats: Array<Format>,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaPeriodId
    ) {
        super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
        if (videoFrameReleaseEarlyTimeForecaster != null) {
            videoFrameReleaseEarlyTimeForecaster.reset()
        }
    }

    @Throws(ExoPlaybackException::class)
    protected override fun onPositionReset(
        positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean
    ) {
        if (videoSink != null) {
            if (!joining) {
                // Flush the video sink first to ensure it stops reading textures that will be owned by
                // MediaCodec once the codec is flushed.
                videoSink!!.flush( true)
            }
        }
        if (sampleStreamIsResetToKeyFrame) {
            lastResetToKeyFramePositionUs = positionUs
        }
        super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame)
        if (videoSink == null) {
            videoFrameReleaseControl.reset()
        }
        if (videoFrameReleaseEarlyTimeForecaster != null) {
            videoFrameReleaseEarlyTimeForecaster.reset()
        }
        if (joining) {
            // Don't render next frame immediately to let the codec catch up with the playback position
            // first. This prevents a stuttering effect caused by showing the first frame and then
            // dropping many of the subsequent frames during the catch up phase.
            if (videoSink != null) {
                videoSink!!.join( false)
            } else {
                videoFrameReleaseControl.join( false)
            }
        }

        maybeSetupTunnelingForFirstFrame()
        consecutiveDroppedFrameCount = 0
        nextOutputBufferToProcessPresentationTimeUs = C.TIME_UNSET
    }

    override fun supportsResetPositionWithoutKeyFrameReset(positionUs: Long): Boolean {
        // Require a key frame if:
        // - The decoder has not yet been supplied a key frame
        // - The renderer has already processed output or dropped to a keyframe past the intended reset
        // position.
        if (largestQueuedPresentationTimeUs == C.TIME_UNSET) {
            return false
        }
        if (positionUs < lastResetToKeyFramePositionUs) {
            return false
        }
        val lastProcessedOutputBufferTimeUs = lastProcessedOutputBufferTimeUs
        if (lastProcessedOutputBufferTimeUs == C.TIME_UNSET) {
            return true
        }
        return positionUs > lastProcessedOutputBufferTimeUs
    }

    override val isEnded: Boolean
        get() = super.isEnded && (videoSink == null || videoSink!!.isEnded)

    override val isReady: Boolean
        get() {
            val rendererOtherwiseReady = isReadyForDecoding
            if (videoSink != null) {
                return videoSink!!.isReady(rendererOtherwiseReady)
            }
            if (rendererOtherwiseReady && (codec == null || tunneling)) {
                // Not releasing frames.
                return true
            }
            return videoFrameReleaseControl.isReady(rendererOtherwiseReady)
        }

    protected override fun onStarted() {
        super.onStarted()
        droppedFrames = 0
        val elapsedRealtimeMs = getClock().elapsedRealtime()
        droppedFrameAccumulationStartTimeMs = elapsedRealtimeMs
        totalVideoFrameProcessingOffsetUs = 0
        videoFrameProcessingOffsetCount = 0
        if (videoSink != null) {
            videoSink!!.startRendering()
        } else {
            videoFrameReleaseControl.onStarted()
        }
    }

    protected override fun onStopped() {
        maybeNotifyDroppedFrames()
        maybeNotifyVideoFrameProcessingOffset()
        if (videoSink != null) {
            videoSink!!.stopRendering()
        } else {
            videoFrameReleaseControl.onStopped()
        }
        if (videoFrameReleaseEarlyTimeForecaster != null) {
            videoFrameReleaseEarlyTimeForecaster.reset()
        }
        super.onStopped()
    }

    protected override fun onDisabled() {
        reportedVideoSize = null
        maybeSetupTunnelingForFirstFrame()
        haveReportedFirstFrameRenderedForCurrentSurface = false
        tunnelingOnFrameRenderedListener = null
        isFlushRequired = shouldFlushCodec()
        nextOutputBufferToProcessPresentationTimeUs = C.TIME_UNSET
        try {
            super.onDisabled()
        } finally {
            eventDispatcher.disabled(decoderCounters)
            eventDispatcher.videoSizeChanged(VideoSize.UNKNOWN)
        }
    }

    protected override fun onReset() {
        try {
            super.onReset()
        } finally {
            hasSetVideoSink = false
            startPositionUs = C.TIME_UNSET
            nextOutputBufferToProcessPresentationTimeUs = C.TIME_UNSET
            isApplyingContainerHagcMetadata = false
            releasePlaceholderSurface()
        }
    }

    override fun onRelease() {
        super.onRelease()
        if (videoSink != null && ownsVideoSink) {
            videoSink!!.release()
        }
    }

    @Throws(ExoPlaybackException::class)
    override fun handleMessage(messageType: @Renderer.MessageType Int, message: Any?) {
        when (messageType) {
          Renderer. MSG_SET_VIDEO_OUTPUT -> setOutput(message)
            Renderer.  MSG_SET_SCALING_MODE -> {
                scalingMode =message as Int
                val codec = codec
                if (codec != null) {
                    codec.setVideoScalingMode(scalingMode)
                }
            }

            Renderer.  MSG_SET_CHANGE_FRAME_RATE_STRATEGY -> {
                changeFrameRateStrategy = message!! as Int
                if (videoSink != null) {
                    videoSink!!.setChangeFrameRateStrategy(changeFrameRateStrategy)
                } else {
                    videoFrameReleaseControl.setChangeFrameRateStrategy(changeFrameRateStrategy)
                }
            }

            Renderer.  MSG_SET_VIDEO_FRAME_METADATA_LISTENER -> {
                frameMetadataListener =
                    message!! as VideoFrameMetadataListener
                if (videoSink != null) {
                    videoSink!!.setVideoFrameMetadataListener(frameMetadataListener)
                }
            }

            Renderer. MSG_SET_AUDIO_SESSION_ID -> {
                val tunnelingAudioSessionId = message!! as Int
                if (this.tunnelingAudioSessionId != tunnelingAudioSessionId) {
                    this.tunnelingAudioSessionId = tunnelingAudioSessionId
                    if (tunneling) {
                        releaseCodec()
                    }
                }
            }

            Renderer.  MSG_SET_VIDEO_EFFECTS -> {
                val videoEffects = message as List<Effect>
                setVideoEffects(videoEffects)
            }

            Renderer.  MSG_SET_VIDEO_OUTPUT_RESOLUTION -> {
                val outputResolution = message!! as Size
                if (outputResolution.width != 0 && outputResolution.height != 0) {
                    this.outputResolution = outputResolution
                    if (videoSink != null) {
                        videoSink!!.setOutputSurfaceInfo(
                            this.surface!!, outputResolution
                        )
                    }
                }
            }

            Renderer. MSG_SET_PRIORITY -> {
                rendererPriority = message!! as Int
                updateCodecImportance()
            }

            Renderer.  MSG_TRANSFER_RESOURCES -> {
                val surface = this.surface
                setOutput(null)
                (message!! as MediaCodecVideoRenderer)
                    .handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, surface)
            }

            Renderer. MSG_SET_SCRUBBING_MODE -> {
                val codecRateAlreadyIncreasedForScrubbing =
                    scrubbingModeParameters != null
                            && scrubbingModeParameters!!.shouldIncreaseCodecOperatingRate
                scrubbingModeParameters = message as ScrubbingModeParameters?
                val shouldIncreaseCodecOperatingRate =
                    scrubbingModeParameters != null
                            && scrubbingModeParameters!!.shouldIncreaseCodecOperatingRate
                if (codecRateAlreadyIncreasedForScrubbing != shouldIncreaseCodecOperatingRate) {
                    updateCodecOperatingRate()
                }
            }

            else -> super.handleMessage(messageType, message)
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun setOutput(output: Any?) {
        // Handle unsupported (i.e., non-Surface) outputs by clearing the display surface.
        val displaySurface = if (output is Surface) output else null

        if (this.surface !== displaySurface) {
            this.surface = displaySurface
            if (videoSink == null) {
                videoFrameReleaseControl.setOutputSurface(displaySurface)
            }
            haveReportedFirstFrameRenderedForCurrentSurface = false

            val state: @Renderer.State Int = state
            val codec = codec
            var surfaceReplacedSeamlessly = false
            if (codec != null) {
                if (videoSink == null) {
                    val codecInfo = codecInfo!!
                    val canUpdateSurface = hasSurfaceForCodec(codecInfo)
                    if (canUpdateSurface && !codecNeedsSetOutputSurfaceWorkaround) {
                        setOutputSurface(codec, getSurfaceForCodec(codecInfo))
                        surfaceReplacedSeamlessly = true
                    } else {
                        releaseCodec()
                        maybeInitCodecOrBypass()
                    }
                } else {
                    surfaceReplacedSeamlessly = true
                }
            }
            if (displaySurface != null) {
                // If we know the video size, report it again immediately.
                maybeRenotifyVideoSizeChanged()
            } else {
                // The display surface has been removed.
                reportedVideoSize = null
                if (videoSink != null) {
                    videoSink!!.clearOutputSurfaceInfo()
                }
            }
            if (state == Renderer.STATE_STARTED) {
                // We want to "join" playback to prevent an intermediate buffering state in the player
                // before we rendered the new first frame. If the surface was replaced seamlessly,
                // there is no reason to believe the next frame is delayed and the renderer needs to
                // catch up, so we still request to render the next frame as soon as possible.
                val renderNextFrameImmediately = surfaceReplacedSeamlessly
                if (videoSink != null) {
                    videoSink!!.join(renderNextFrameImmediately)
                } else {
                    videoFrameReleaseControl.join(renderNextFrameImmediately)
                }
            }
            maybeSetupTunnelingForFirstFrame()
        } else if (displaySurface != null) {
            // The display surface is set and unchanged. If we know the video size and/or have already
            // rendered to the display surface, report these again immediately.
            maybeRenotifyVideoSizeChanged()
            maybeRenotifyRenderedFirstFrame()
        }
    }

    override fun shouldInitCodec(codecInfo: MediaCodecInfo): Boolean {
        return hasSurfaceForCodec(codecInfo)
    }

    override fun getMediaCodecConfiguration(
        codecInfo: MediaCodecInfo,
        format: Format,
        crypto: MediaCrypto?,
        codecOperatingRate: Float
    ): MediaCodecAdapter.Configuration {
        val codecMimeType = codecInfo.codecMimeType
        codecMaxValues = getCodecMaxValues(codecInfo, format, getStreamFormats())
        val mediaFormat =
            getMediaFormat(
                format,
                codecMimeType,
                codecMaxValues!!,
                codecOperatingRate,
                deviceNeedsNoPostProcessWorkaround,
                if (tunneling) tunnelingAudioSessionId else C.AUDIO_SESSION_ID_UNSET
            )
        val codecSurface = getSurfaceForCodec(codecInfo)
        maybeSetKeyAllowFrameDrop(mediaFormat)
        return Configuration.createForVideoDecoding(
            codecInfo, mediaFormat, format, codecSurface, crypto
        )
    }

    // The API level is checked in the utility method
    private fun maybeSetKeyAllowFrameDrop(mediaFormat: MediaFormat) {
        if (videoSink != null && !isFrameDropAllowedOnSurfaceInput(context)) {
            mediaFormat.setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, 0)
        }
    }

    override fun canReuseCodec(
        codecInfo: MediaCodecInfo,
        oldFormat: Format,
        newFormat: Format,
        isAdaptiveFormatChange: Boolean
    ): DecoderReuseEvaluation {
        val evaluation = codecInfo.canReuseCodec(oldFormat, newFormat)

        var discardReasons: @DecoderDiscardReasons Int = evaluation.discardReasons
        val codecMaxValues: CodecMaxValues =this.codecMaxValues!!
        if (newFormat.width > codecMaxValues.width || newFormat.height > codecMaxValues.height) {
            discardReasons =
                discardReasons or DecoderReuseEvaluation.DISCARD_REASON_VIDEO_MAX_RESOLUTION_EXCEEDED
        }
        if (getMaxInputSize(codecInfo, newFormat) > codecMaxValues.inputSize) {
            discardReasons =
                discardReasons or DecoderReuseEvaluation.DISCARD_REASON_MAX_INPUT_SIZE_EXCEEDED
        }
        if (shouldDiscardCodecForFrameRateChange(
                codecInfo, oldFormat, newFormat, isAdaptiveFormatChange
            )
        ) {
            discardReasons =
                discardReasons or DecoderReuseEvaluation.DISCARD_REASON_VIDEO_FRAME_RATE_CHANGED
        }

        return DecoderReuseEvaluation(
            codecInfo.name,
            oldFormat,
            newFormat,
            if (discardReasons != 0) DecoderReuseEvaluation.REUSE_RESULT_NO else evaluation.result,
            discardReasons
        )
    }

    @CallSuper
    @Throws(ExoPlaybackException::class)
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        if (videoSink != null) {
            try {
                // Drain the sink to make room for a new input frame.
                videoSink!!.render(positionUs, elapsedRealtimeUs)
            } catch (e: VideoSinkException) {
                throw createRendererException(
                    e, e.format, PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED
                )
            }
        }
        super.render(positionUs, elapsedRealtimeUs)
    }

    @CallSuper
    override fun resetCodecStateForFlush() {
        super.resetCodecStateForFlush()
        discardedDecoderInputBufferTimestamps.clear()
        buffersInCodecCount = 0
        consecutiveDroppedInputBufferCount = 0
        isFlushRequired = false
        nextOutputBufferToProcessPresentationTimeUs = C.TIME_UNSET
        if (av1SampleDependencyParser != null) {
            av1SampleDependencyParser.reset()
        }
    }

    @Throws(ExoPlaybackException::class)
    override fun setPlaybackSpeed(currentPlaybackSpeed: Float, targetPlaybackSpeed: Float) {
        super.setPlaybackSpeed(currentPlaybackSpeed, targetPlaybackSpeed)
        if (videoSink != null) {
            videoSink!!.setPlaybackSpeed(currentPlaybackSpeed)
        } else {
            videoFrameReleaseControl.setPlaybackSpeed(currentPlaybackSpeed)
        }
        if (videoFrameReleaseEarlyTimeForecaster != null) {
            videoFrameReleaseEarlyTimeForecaster.setPlaybackSpeed(currentPlaybackSpeed)
        }
    }

    override fun getDurationToProgressUs(
        positionUs: Long, elapsedRealtimeUs: Long, isOnBufferAvailableListenerRegistered: Boolean
    ): Long {
        if (!enableDurationToProgressUs || !isOnBufferAvailableListenerRegistered) {
            return super.getDurationToProgressUs(
                positionUs, elapsedRealtimeUs, isOnBufferAvailableListenerRegistered
            )
        }

        if (state != Renderer.STATE_STARTED) {
            // When not started, we can only make further progress if we don't have a first frame rendered
            // or a frame ready to render.
            return if (isReady || isEnded)
                Renderer.  DEFAULT_IDLE_DURATION_TO_PROGRESS_US
            else
                Renderer. DEFAULT_DURATION_TO_PROGRESS_US
        }

        if (nextOutputBufferToProcessPresentationTimeUs == C.TIME_UNSET || videoSink != null) {
            return  Renderer.DEFAULT_DURATION_TO_PROGRESS_US
        }

        if (isEnded) {
            // If processed all output then return half of the duration to the end.
            var durationUs = (nextOutputBufferToProcessPresentationTimeUs - positionUs)
            durationUs = (durationUs / playbackSpeed / 2).toLong()
            return max( Renderer.DEFAULT_DURATION_TO_PROGRESS_US, durationUs)
        }

        try {
            val frameReleaseAction: @FrameReleaseAction Int =
                videoFrameReleaseControl.getFrameReleaseAction(
                    nextOutputBufferToProcessPresentationTimeUs,
                    positionUs,
                    elapsedRealtimeUs,
                    outputStreamStartPositionUs,  
                    false,  
                    false,
                    frameRateEstimator.frameDurationNs,
                    frameRateEstimator.frameIndex,
                    videoFrameReleaseInfo
                )
            if (frameReleaseAction != VideoFrameReleaseControl.FRAME_RELEASE_TRY_AGAIN_LATER) {
                return 0
            }
            var durationUs = videoFrameReleaseInfo.earlyUs
            // Rebase duration to the start of this iteration of the rendering loop.
            durationUs += msToUs(getClock().elapsedRealtime()) - elapsedRealtimeUs
            // Set a lower bound of waking within 1.5*VSync rate at 60 fps(25 ms).
            return max(0, durationUs - 25000)
        } catch (exoPlaybackException: ExoPlaybackException) {
            w(TAG, "Error while evaluating frame release action")
        }
        return  Renderer.DEFAULT_DURATION_TO_PROGRESS_US
    }

    override fun getCodecOperatingRateV23(
        targetPlaybackSpeed: Float, format: Format, streamFormats: Array<Format>
    ): Float {
        // Use the highest known stream frame-rate up front, to avoid having to reconfigure the codec
        // should an adaptive switch to that stream occur.
        var frameRate = -1f
        for (streamFormat in streamFormats) {
            val streamFrameRate = streamFormat.frameRate
            if (streamFrameRate != Format.NO_VALUE.toFloat()) {
                frameRate = max(frameRate, streamFrameRate)
            }
        }

        if (frameRate == -1f && codec != null && frameRateEstimator.frameDurationNs != C.TIME_UNSET) {
            // Only use the fallback after the codec is initialized to avoid using a stale
            // estimate from a previous codec.
            frameRate = C.NANOS_PER_SECOND.toFloat() / frameRateEstimator.frameDurationNs
        }

        val operatingRate =
            if (frameRate == -1f) CODEC_OPERATING_RATE_UNSET else (frameRate * targetPlaybackSpeed)

        if (scrubbingModeParameters != null) {
            val codecInfo = codecInfo
            if (codecInfo != null) {
                val maxSupportedFrameRate =
                    codecInfo.getMaxSupportedFrameRate(format.width, format.height)
                return if (operatingRate != CODEC_OPERATING_RATE_UNSET)
                    max(operatingRate, maxSupportedFrameRate)
                else
                    maxSupportedFrameRate
            }
        }
        return operatingRate
    }

    private fun onFrameRateEstimateChanged(frameRate: Float) {
        videoFrameReleaseControl.setSurfaceMediaFrameRate(frameRate)
        updateCodecOperatingRate()
    }

    @CallSuper
    @Throws(ExoPlaybackException::class)
    override fun maybeInitializeProcessingPipeline(format: Format): Boolean {
        if (videoSink != null && !videoSink!!.isInitialized) {
            try {
                return videoSink!!.initialize(format)
            } catch (e: VideoSinkException) {
                throw createRendererException(
                    e, format, PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED
                )
            }
        }
        return true
    }

    fun setVideoEffects(effects: List<Effect>) {
        if (effects == VideoFrameProcessor.REDRAW) {
            if (videoSink == null || !videoSink!!.isInitialized) {
                return
            }
            videoSink!!.redraw()
            return
        }

        videoEffects = effects
        if (videoSink != null) {
            videoSink!!.setVideoEffects(effects)
        }
    }

    override fun onCodecInitialized(
        name: String,
        configuration: MediaCodecAdapter.Configuration,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) {
        eventDispatcher.decoderInitialized(name, initializedTimestampMs, initializationDurationMs)
        codecNeedsSetOutputSurfaceWorkaround = codecNeedsSetOutputSurfaceWorkaround(name)
        codecHandlesHdr10PlusOutOfBandMetadata =codecInfo!!.isHdr10PlusOutOfBandMetadataSupported
        codecNeedsStripNonHdr10PlusT35Workaround =codecInfo!!.mimeType == MimeTypes.VIDEO_AV1
        maybeSetupTunnelingForFirstFrame()
    }

    override fun onCodecReleased(name: String) {
        eventDispatcher.decoderReleased(name)
    }

    override fun onCodecError(codecError: Exception) {
        e(TAG, "Video codec error", codecError)
        eventDispatcher.videoCodecError(codecError)
    }

    override fun shouldReleaseCodecInsteadOfFlushing(): Boolean {
        val codecInfo = codecInfo
        if (videoSink != null && codecInfo != null && (codecInfo.name == "c2.mtk.avc.decoder"
                    || codecInfo.name == "c2.mtk.hevc.decoder"
                    || codecInfo.name == "c2.mtk.vp9.decoder")
        ) {
            // Flushing a c2.mtk decoder that outputs to a SurfaceTexture often fails and leaves
            // the SurfaceTexture's BufferQueue in an unrecoverable state. Release the codec instead.
            // See b/362904942 for more details.
            return true
        }
        return super.shouldReleaseCodecInsteadOfFlushing()
    }

    override fun shouldFlushCodec(): Boolean {
        if (!super.shouldFlushCodec()) {
            return false
        }
        val inputFormat = codecInputFormat
        var skippingFlushMayCauseOverflow = true
        val streamEndPositionUs = streamEndPositionUs
        if (streamEndPositionUs != C.TIME_UNSET) {
            val maxPotentialSkippedFlushOffset = streamEndPositionUs + 1
            val maxPotentialSampleTimestamp = outputStreamOffsetUs + streamEndPositionUs
            skippingFlushMayCauseOverflow =
                (skippedFlushOffsetUs + maxPotentialSkippedFlushOffset
                        > Long.MAX_VALUE - maxPotentialSampleTimestamp)
        }
        return scrubbingModeParameters == null || !scrubbingModeParameters!!.allowSkippingMediaCodecFlush || isFlushRequired
                || tunneling
                || (inputFormat != null && inputFormat.maxNumReorderSamples > 0)
                || skippingFlushMayCauseOverflow
                || lastBufferInStreamPresentationTimeUs != C.TIME_UNSET
    }

    @Throws(ExoPlaybackException::class)
    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
        val evaluation = super.onInputFormatChanged(formatHolder)
        isApplyingContainerHagcMetadata = false
        eventDispatcher.inputFormatChanged(
            formatHolder.format!!,
            evaluation
        )
        if (videoFrameReleaseEarlyTimeForecaster != null) {
            // A change in input format is likely to affect decoding speed, making predictions from
            // the forecaster invalid.
            // TODO: b/412588892 - Handle the transition when input and output format are different.
            videoFrameReleaseEarlyTimeForecaster.reset()
        }
        return evaluation
    }

    @CallSuper
    @Throws(ExoPlaybackException::class)
    override fun onQueueInputBuffer(buffer: DecoderInputBuffer) {
        val mimeType = codecInfo!!.mimeType
        if (mimeType == MimeTypes.VIDEO_AV1 && buffer.data != null) {
            val bufferData: ByteBuffer = buffer.data!!
            val codecInputFormat = codecInputFormat
            val isAv1HighBitdepth =
                codecInputFormat != null && codecInputFormat.colorInfo != null && codecInputFormat.colorInfo.lumaBitdepth > 8
            if (isApplyingContainerHagcMetadata && !bufferData.isReadOnly()) {
                // Strip all T.35 metadata to prevent in-band metadata from overriding container HAGC.
                Av1ObuUtil.stripAllT35Metadata(bufferData)
            } else if (isAv1HighBitdepth
                && codecNeedsStripNonHdr10PlusT35Workaround
                && !bufferData.isReadOnly()
            ) {
                // For AV1 streams with bitdepth > 8, try to rewrite OBUs to skip non-HDR10+ metadata on
                // SDKs prior to 37.
                Av1ObuUtil.stripNonHdr10PlusT35Metadata(bufferData)
            }
            if (av1SampleDependencyParser != null && buffer.isKeyFrame) {
                av1SampleDependencyParser.queueInputBuffer(bufferData)
            }
        } else if (mimeType == MimeTypes.VIDEO_H265 && buffer.data != null) {
            if (isApplyingContainerHagcMetadata && !buffer.data!!.isReadOnly()) {
                // Strip all T.35 metadata to prevent in-band metadata from overriding container HAGC.
                HevcSeiUtil.stripAllT35Metadata(buffer.data!!)
            }
        }
        consecutiveDroppedInputBufferCount = 0
        // In tunneling mode the device may do frame rate conversion, so in general we can't keep track
        // of the number of buffers in the codec.
        val flags = getCodecBufferFlags(buffer)
        if ((Build.VERSION.SDK_INT < 34 || (flags and MediaCodec.BUFFER_FLAG_DECODE_ONLY) == 0) && !tunneling) {
            buffersInCodecCount++
        }
    }

    override fun getCodecBufferFlags(buffer: DecoderInputBuffer): Int {
        if (Build.VERSION.SDK_INT >= 34 && (enableMediaCodecBufferDecodeOnlyFlag
                    || (scrubbingModeParameters != null && scrubbingModeParameters!!.useDecodeOnlyFlag)
                    || tunneling)
            && isBufferBeforeStartTime(buffer)
            && !isBufferProbablyLastSample(buffer)
        ) {
            // The buffer likely needs to be dropped because its timestamp is less than the start time.
            // If tunneling, we can't decide to do this after decoding because we won't get the buffer
            // back from the codec in tunneling mode. This may not work perfectly, e.g. when the codec is
            // doing frame rate conversion, but it's still better than not dropping the buffers at all.
            return MediaCodec.BUFFER_FLAG_DECODE_ONLY
        }
        return 0
    }

    override fun shouldDiscardDecoderInputBuffer(buffer: DecoderInputBuffer): Boolean {
        if (isBufferProbablyLastSample(buffer)) {
            // Make sure to decode and render the last frame.
            return false
        }
        val isBufferBeforeStartTime = isBufferBeforeStartTime(buffer)
        var shouldDropDecoderInputBuffer = false
        if (videoFrameReleaseEarlyTimeForecaster != null) {
            val predictedEarlyUs =
                videoFrameReleaseEarlyTimeForecaster.predictEarlyUs(buffer.timeUs)
            shouldDropDecoderInputBuffer =
                predictedEarlyUs != C.TIME_UNSET && predictedEarlyUs < minEarlyUsToDropDecoderInput
        }
        if (!isBufferBeforeStartTime && !shouldDropDecoderInputBuffer) {
            return false
        }
        if (buffer.hasSupplementalData()) {
            return false
        }
        var bufferDiscarded = false
        if (buffer.notDependedOn()) {
            bufferDiscarded = true
            buffer.clear()
        } else if (av1SampleDependencyParser != null && codecInfo!!.mimeType == MimeTypes.VIDEO_AV1
            && buffer.data != null
        ) {
            val skipFrameHeaders =
                isBufferBeforeStartTime
                        || (consecutiveDroppedInputBufferCount
                        <= MAX_CONSECUTIVE_DROPPED_INPUT_BUFFERS_COUNT_TO_DISCARD_HEADER)
            val readOnlySample = buffer.data!!.asReadOnlyBuffer()
            readOnlySample.flip()
            val sampleLimitAfterSkippingNonReferenceFrames =
                av1SampleDependencyParser.sampleLimitAfterSkippingNonReferenceFrame(
                    readOnlySample, skipFrameHeaders
                )
            if (sampleLimitAfterSkippingNonReferenceFrames == 0) {
                buffer.clear()
                bufferDiscarded = true
            } else if (sampleLimitAfterSkippingNonReferenceFrames != readOnlySample.limit()) {
                val hasSpaceForNextFrame =
                    (sampleLimitAfterSkippingNonReferenceFrames + codecMaxValues!!.inputSize
                            < readOnlySample.capacity())
                // An encrypted buffer cannot be partially discarded.
                if (hasSpaceForNextFrame && !buffer.isEncrypted) {
                    buffer.data!! .position(sampleLimitAfterSkippingNonReferenceFrames)
                    bufferDiscarded = true
                }
            }
        }
        if (bufferDiscarded) {
            if (!isBufferBeforeStartTime) {
                consecutiveDroppedInputBufferCount += 1
            }
            discardedDecoderInputBufferTimestamps.add(buffer.timeUs)
        }
        return bufferDiscarded
    }

    private fun isBufferProbablyLastSample(buffer: DecoderInputBuffer): Boolean {
        if (hasReadStreamToEnd() || buffer.isLastSample) {
            return true
        }
        // TODO: b/352276461 - improve buffer.isLastSample() logic.
        // This is a temporary workaround: do not skip buffers close to the period end.
        if (streamEndPositionUs == C.TIME_UNSET) {
            // Duration unknown: probably last sample.
            return true
        }
        val presentationTimeUs = buffer.timeUs - outputStreamOffsetUs
        return (streamEndPositionUs - presentationTimeUs
                <= OFFSET_FROM_PERIOD_END_TO_TREAT_AS_LAST_US)
    }

    private fun isBufferBeforeStartTime(buffer: DecoderInputBuffer): Boolean {
        return buffer.timeUs < lastResetPositionUs
    }

    override fun onOutputFormatChanged(format: Format, mediaFormat: MediaFormat?) {
        val codec = codec
        if (codec != null) {
            // Must be applied each time the output format changes.
            codec.setVideoScalingMode(scalingMode)
        }
        var width: Int
        var height: Int
        var pixelWidthHeightRatio: Float

        if (tunneling) {
            width = format.width
            height = format.height
        } else {
            val hasCrop =
                mediaFormat!!.containsKey(KEY_CROP_RIGHT)
                        && mediaFormat.containsKey(KEY_CROP_LEFT)
                        && mediaFormat.containsKey(KEY_CROP_BOTTOM)
                        && mediaFormat.containsKey(KEY_CROP_TOP)
            width =
                if (hasCrop)
                    mediaFormat.getInteger(KEY_CROP_RIGHT) - mediaFormat.getInteger(
                        KEY_CROP_LEFT
                    ) + 1
                else
                    mediaFormat.getInteger(MediaFormat.KEY_WIDTH)
            height =
                if (hasCrop)
                    mediaFormat.getInteger(KEY_CROP_BOTTOM) - mediaFormat.getInteger(
                        KEY_CROP_TOP
                    ) + 1
                else
                    mediaFormat.getInteger(MediaFormat.KEY_HEIGHT)
        }
        pixelWidthHeightRatio = format.pixelWidthHeightRatio
        // The decoder applies the rotation when rendering to the surface. For 90 and 270 degree
        // rotations, we need to flip the width, height and pixel aspect ratio to reflect the rotation
        // that was applied.
        if (format.rotationDegrees == 90 || format.rotationDegrees == 270) {
            val rotatedHeight = width
            width = height
            height = rotatedHeight
            pixelWidthHeightRatio = 1 / pixelWidthHeightRatio
        }
        decodedVideoSize = VideoSize(width, height, pixelWidthHeightRatio)

        if (videoSink != null && pendingVideoSinkInputStreamChange) {
            if (width <= 0 || height <= 0) {
                // Ignore transient invalid formats (e.g. 0x0) reported by some decoders (b/525733404).
                return
            }
            changeVideoSinkInputStream(
                videoSink!!,  
                VideoSink.INPUT_TYPE_SURFACE,
                format
                    .buildUpon()
                    .setWidth(width)
                    .setHeight(height)
                    .setPixelWidthHeightRatio(pixelWidthHeightRatio)
                    .build(),
                nextVideoSinkFirstFrameReleaseInstruction
            )
            nextVideoSinkFirstFrameReleaseInstruction =
                VideoSink.RELEASE_FIRST_FRAME_WHEN_PREVIOUS_STREAM_PROCESSED
        } else {
            frameRateEstimator.onFormatChanged(format.frameRate)
        }
        pendingVideoSinkInputStreamChange = false
    }

    protected fun changeVideoSinkInputStream(
        videoSink: VideoSink,
        inputType: @VideoSink.InputType Int,
        format: Format,
        firstFrameReleaseInstruction: @FirstFrameReleaseInstruction Int
    ) {
        val videoEffectsToApply =
            (if (videoEffects != null) videoEffects else listOf())!!
        videoSink.onInputStreamChanged(
            inputType,
            format,
            outputStreamStartPositionUs,
            firstFrameReleaseInstruction,
            videoEffectsToApply
        )
    }

    // codecHandlesHdr10PlusOutOfBandMetadata is false if SDK_INT < 29
    @TargetApi(29)
    @Throws(ExoPlaybackException::class)
    override fun handleInputBufferSupplementalData(buffer: DecoderInputBuffer) {
        if (buffer.supplementalData == null) {
            return
        }
        val data: ByteBuffer = buffer.supplementalData!!

        if (data.remaining() >= 7) {
            // Check for HDR10+ or HAGC out-of-band metadata. See User_data_registered_itu_t_t35 in ST
            // 2094-40 / ST 2094-50.
            val ituTT35CountryCode = data.get()
            val ituTT35TerminalProviderCode = data.getShort().toInt()
            val ituTT35TerminalProviderOrientedCode = data.getShort().toInt()
            val applicationIdentifier = data.get()
            val applicationVersion = data.get()
            data.position(0)
            if (ituTT35CountryCode == 0xB5.toByte() && ituTT35TerminalProviderCode == 0x003C && ituTT35TerminalProviderOrientedCode == 0x0001 && applicationIdentifier.toInt() == 4 && (applicationVersion.toInt() == 0 || applicationVersion.toInt() == 1)) {
                if (codecHandlesHdr10PlusOutOfBandMetadata) {
                    // The data is HDR10+ out-of-band metadata.
                    val hdr10PlusInfo = ByteArray(data.remaining())
                    data.get(hdr10PlusInfo)
                    data.position(0)
                    setHdr10PlusInfoV29(
                        codec!!,
                        hdr10PlusInfo
                    )
                }
            } else if (Build.VERSION.SDK_INT >= 37 && ituTT35CountryCode == 0xB5.toByte() && ituTT35TerminalProviderCode == 0x0090 && ituTT35TerminalProviderOrientedCode == 0x0001) {
                // HAGC (ST 2094-50) metadata from out-of-band track.
                // Android's MediaCodec API for ST 2094-50 expects the payload without the 5-byte T.35
                // header.
                data.position(5)
                val hagcData = ByteArray(data.remaining())
                data.get(hagcData)
                data.position(0)

                isApplyingContainerHagcMetadata = hagcData.size > 0
                if (isApplyingContainerHagcMetadata) {
                    val codecParameters = Bundle()
                    codecParameters.putByteArray(KEY_HDR_ST2094_50_INFO, hagcData)
                    codec!!.setParameters(codecParameters)
                }
            }
        }
    }

    @Throws(ExoPlaybackException::class)
    override fun processOutputBuffer(
        positionUs: Long,
        elapsedRealtimeUs: Long,
        codec: MediaCodecAdapter?,
        buffer: ByteBuffer?,
        bufferIndex: Int,
        bufferFlags: Int,
        sampleCount: Int,
        bufferPresentationTimeUs: Long,
        isDecodeOnlyBuffer: Boolean,
        isLastBuffer: Boolean,
        format: Format
    ): Boolean {
        codec!!
        val outputStreamOffsetUs = outputStreamOffsetUs
        val presentationTimeUs = bufferPresentationTimeUs - outputStreamOffsetUs
        updateDiscardedBufferCountersWithInputBuffers(bufferPresentationTimeUs)
        frameRateEstimator.onNextFrame(bufferPresentationTimeUs * 1000)

        if (videoSink != null) {
            // Skip decode-only buffers, e.g. after seeking, immediately.
            if (isDecodeOnlyBuffer && !isLastBuffer) {
                skipOutputBuffer(codec!!, bufferIndex, presentationTimeUs)
                return true
            }
            return videoSink!!.handleInputFrame(
                bufferPresentationTimeUs,
                object : VideoFrameHandler {
                    override fun render(renderTimestampNs: Long) {
                        renderOutputBuffer(
                            codec!!,
                            bufferIndex,
                            presentationTimeUs,
                            renderTimestampNs
                        )
                    }

                    override fun skip() {
                        dropOutputBuffer(codec!!, bufferIndex, presentationTimeUs)
                    }
                })
        }

        val frameReleaseAction: @FrameReleaseAction Int =
            videoFrameReleaseControl.getFrameReleaseAction(
                bufferPresentationTimeUs,
                positionUs,
                elapsedRealtimeUs,
                outputStreamStartPositionUs,
                isDecodeOnlyBuffer,
                isLastBuffer,
                frameRateEstimator.frameDurationNs,
                frameRateEstimator.frameIndex,
                videoFrameReleaseInfo
            )
        if (videoFrameReleaseEarlyTimeForecaster != null && frameReleaseAction != VideoFrameReleaseControl.FRAME_RELEASE_TRY_AGAIN_LATER && frameReleaseAction != VideoFrameReleaseControl.FRAME_RELEASE_IGNORE) {
            videoFrameReleaseEarlyTimeForecaster.onVideoFrameProcessed(
                bufferPresentationTimeUs, videoFrameReleaseInfo.earlyUs
            )
        }
        if (DEBUG_LOG_ENABLED) {
            debugLogForBufferRelease(
                frameReleaseAction,
                bufferPresentationTimeUs,
                positionUs,
                isDecodeOnlyBuffer,
                isLastBuffer,
                videoFrameReleaseInfo,
                lastFrameReleaseTimeNs
            )
        }
        nextOutputBufferToProcessPresentationTimeUs =
            if (frameReleaseAction == VideoFrameReleaseControl.FRAME_RELEASE_TRY_AGAIN_LATER)
                bufferPresentationTimeUs
            else
                C.TIME_UNSET
        when (frameReleaseAction) {
            VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY -> {
                val releaseTimeNs = getClock().nanoTime()
                notifyFrameMetadataListener(presentationTimeUs, releaseTimeNs, format)
                renderOutputBuffer(codec!!, bufferIndex, presentationTimeUs, releaseTimeNs)
                updateVideoFrameProcessingOffsetCounters(videoFrameReleaseInfo.earlyUs)
                return true
            }

            VideoFrameReleaseControl.FRAME_RELEASE_SKIP -> {
                skipOutputBuffer(codec!!, bufferIndex, presentationTimeUs)
                updateVideoFrameProcessingOffsetCounters(videoFrameReleaseInfo.earlyUs)
                return true
            }

            VideoFrameReleaseControl.FRAME_RELEASE_DROP -> {
                dropOutputBuffer(codec!!, bufferIndex, presentationTimeUs)
                updateVideoFrameProcessingOffsetCounters(videoFrameReleaseInfo.earlyUs)
                return true
            }

            VideoFrameReleaseControl.FRAME_RELEASE_TRY_AGAIN_LATER, VideoFrameReleaseControl.FRAME_RELEASE_IGNORE -> return false
            VideoFrameReleaseControl.FRAME_RELEASE_SCHEDULED -> {
                releaseFrame(
                    codec!!,
                    bufferIndex,
                    presentationTimeUs,
                    format
                )
                return true
            }

            else -> throw IllegalStateException(frameReleaseAction.toString())
        }
    }

    override fun onCodecParametersChanged(codecParameters: CodecParameters?) {
        eventDispatcher.videoCodecParametersChanged(codecParameters!!)
    }

    override fun renderToEndOfStream() {
        if (videoSink != null) {
            videoSink!!.signalEndOfCurrentInputStream()
        } else if (lastBufferInStreamPresentationTimeUs != C.TIME_UNSET) {
            nextOutputBufferToProcessPresentationTimeUs = lastBufferInStreamPresentationTimeUs
        }
    }

     // TODO: b/417646815 - Remove this workaround method once it's no longer needed.
    protected fun experimentalDisableAdvancingTimestampChecksInVideoFrameReleaseControl() {
        videoFrameReleaseControl.experimentalDisableAdvancingTimestampChecks()
    }

    protected val bufferTimestampAdjustmentUs: Long
        
        get() = -startPositionUs

    private fun releaseFrame(
        codec: MediaCodecAdapter, bufferIndex: Int, presentationTimeUs: Long, format: Format
    ) {
        val releaseTimeNs = videoFrameReleaseInfo.releaseTimeNs
        val earlyUs = videoFrameReleaseInfo.earlyUs
        if (shouldSkipBuffersWithIdenticalReleaseTime() && releaseTimeNs == lastFrameReleaseTimeNs) {
            // This frame should be displayed on the same vsync with the previous released frame. We
            // are likely rendering frames at a rate higher than the screen refresh rate. Skip
            // this buffer so that it's returned to MediaCodec sooner otherwise MediaCodec may not
            // be able to keep decoding with this rate [b/263454203].
            skipOutputBuffer(codec, bufferIndex, presentationTimeUs)
        } else {
            notifyFrameMetadataListener(presentationTimeUs, releaseTimeNs, format)
            renderOutputBufferV21(codec, bufferIndex, presentationTimeUs, releaseTimeNs)
        }
        updateVideoFrameProcessingOffsetCounters(earlyUs)
        lastFrameReleaseTimeNs = releaseTimeNs
    }

    private fun notifyFrameMetadataListener(
        presentationTimeUs: Long, releaseTimeNs: Long, format: Format
    ) {
        if (frameMetadataListener != null) {
            frameMetadataListener!!.onVideoFrameAboutToBeRendered(
                presentationTimeUs, releaseTimeNs, format, codecOutputMediaFormat
            )
        }
    }

    @Throws(ExoPlaybackException::class)
    protected fun onProcessedTunneledBuffer(presentationTimeUs: Long) {
        updateOutputFormatForTime(presentationTimeUs)
        maybeNotifyVideoSizeChanged(decodedVideoSize)
        decoderCounters.renderedOutputBufferCount++
        maybeNotifyRenderedFirstFrame()
        onProcessedOutputBuffer(presentationTimeUs)
    }

    private fun onProcessedTunneledEndOfStream() {
        setPendingOutputEndOfStream()
    }

    @CallSuper
    override fun onProcessedOutputBuffer(presentationTimeUs: Long) {
        super.onProcessedOutputBuffer(presentationTimeUs)
        if (!tunneling) {
            buffersInCodecCount--
        }
    }

    override fun onProcessedStreamChange() {
        super.onProcessedStreamChange()
        if (videoSink != null) {
            // Signaling end of the previous stream.
            videoSink!!.signalEndOfCurrentInputStream()
            if (this.startPositionUs == C.TIME_UNSET) {
                this.startPositionUs = outputStreamStartPositionUs
            }
            videoSink!!.setBufferTimestampAdjustmentUs(this.bufferTimestampAdjustmentUs)
        } else {
            videoFrameReleaseControl.onStreamChanged(VideoSink.RELEASE_FIRST_FRAME_WHEN_PREVIOUS_STREAM_PROCESSED)
        }
        pendingVideoSinkInputStreamChange = true
        maybeSetupTunnelingForFirstFrame()
    }

    protected fun shouldDropOutputBuffer(
        earlyUs: Long, elapsedRealtimeUs: Long, isLastBuffer: Boolean
    ): Boolean {
        return earlyUs < MIN_EARLY_US_LATE_THRESHOLD && !isLastBuffer
    }

    protected fun shouldDropBuffersToKeyframe(
        earlyUs: Long, elapsedRealtimeUs: Long, isLastBuffer: Boolean
    ): Boolean {
        return earlyUs < MIN_EARLY_US_VERY_LATE_THRESHOLD && !isLastBuffer
    }

    protected fun shouldSkipBuffersWithIdenticalReleaseTime(): Boolean {
        return true
    }

    protected fun shouldForceRenderOutputBuffer(
        earlyUs: Long,
        elapsedSinceLastRenderUs: Long
    ): Boolean {
        return earlyUs < MIN_EARLY_US_LATE_THRESHOLD && elapsedSinceLastRenderUs > 100000
    }

    protected fun skipOutputBuffer(codec: MediaCodecAdapter, index: Int, presentationTimeUs: Long) {
        beginSection("skipVideoBuffer")
        codec.releaseOutputBuffer(index, false)
        endSection()
        decoderCounters.skippedOutputBufferCount++
    }

    protected fun dropOutputBuffer(codec: MediaCodecAdapter, index: Int, presentationTimeUs: Long) {
        beginSection("dropVideoBuffer")
        codec.releaseOutputBuffer(index, false)
        endSection()
        updateDroppedBufferCounters( 
            0,  1
        )
    }

    @Throws(ExoPlaybackException::class)
    protected fun maybeDropBuffersToKeyframe(
        positionUs: Long, treatDroppedBuffersAsSkipped: Boolean
    ): Boolean {
        val droppedSourceBufferCount = skipSource(positionUs)
        if (droppedSourceBufferCount == 0) {
            return false
        }
        lastResetToKeyFramePositionUs = positionUs
        // We dropped some buffers to catch up, so update the decoder counters and flush the codec,
        // which releases all pending buffers buffers including the current output buffer.
        if (DEBUG_LOG_ENABLED) {
            d(DEBUG_LOG_TAG, "video, discard input to keyframe, count=" + droppedSourceBufferCount)
        }
        var droppedInQueue = 0
        var skippedInQueue = 0
        val isJoining = videoFrameReleaseControl.isJoining
        for (timeUs in discardedDecoderInputBufferTimestamps) {
            if (timeUs!! >= lastResetPositionUs && !isJoining) {
                droppedInQueue++
            } else {
                skippedInQueue++
            }
        }
        discardedDecoderInputBufferTimestamps.clear()
        if (treatDroppedBuffersAsSkipped) {
            decoderCounters.skippedInputBufferCount += droppedSourceBufferCount
            decoderCounters.skippedOutputBufferCount += buffersInCodecCount
            decoderCounters.skippedInputBufferCount += droppedInQueue
            decoderCounters.skippedInputBufferCount += skippedInQueue
        } else {
            decoderCounters.droppedToKeyframeCount++
            updateDroppedBufferCounters( 
                droppedSourceBufferCount + droppedInQueue,  
                buffersInCodecCount
            )
            decoderCounters.skippedInputBufferCount += skippedInQueue
        }
        flushOrReinitializeCodec()
        if (videoSink != null) {
            videoSink!!.flush( false)
        }
        return true
    }

    protected fun updateDroppedBufferCounters(
        droppedInputBufferCount: Int, droppedDecoderBufferCount: Int
    ) {
        decoderCounters.droppedInputBufferCount += droppedInputBufferCount
        val totalDroppedBufferCount = droppedInputBufferCount + droppedDecoderBufferCount
        decoderCounters.droppedBufferCount += totalDroppedBufferCount
        droppedFrames += totalDroppedBufferCount
        consecutiveDroppedFrameCount += totalDroppedBufferCount
        decoderCounters.maxConsecutiveDroppedBufferCount =
            max(consecutiveDroppedFrameCount, decoderCounters.maxConsecutiveDroppedBufferCount)
        if (maxDroppedFramesToNotify > 0 && droppedFrames >= maxDroppedFramesToNotify) {
            maybeNotifyDroppedFrames()
        }
    }

    private fun updateDiscardedBufferCountersWithInputBuffers(bufferPresentationTimeUs: Long) {
        var droppedInputBufferCount = 0
        var skippedInputBufferCount = 0
        var minDiscardedDecoderBufferTimeUs: Long?
        val isJoining = videoFrameReleaseControl.isJoining
        while ((discardedDecoderInputBufferTimestamps.peek()
                .also { minDiscardedDecoderBufferTimeUs = it }) != null
            && minDiscardedDecoderBufferTimeUs!! < bufferPresentationTimeUs
        ) {
            discardedDecoderInputBufferTimestamps.poll()
            frameRateEstimator.onNextFrame(minDiscardedDecoderBufferTimeUs * 1000)
            if (minDiscardedDecoderBufferTimeUs >= lastResetPositionUs && !isJoining) {
                droppedInputBufferCount++
            } else {
                skippedInputBufferCount++
            }
        }
        updateDroppedBufferCounters(droppedInputBufferCount,  0)
        decoderCounters.skippedInputBufferCount += skippedInputBufferCount
    }

    protected fun updateVideoFrameProcessingOffsetCounters(processingOffsetUs: Long) {
        decoderCounters.addVideoFrameProcessingOffset(processingOffsetUs)
        totalVideoFrameProcessingOffsetUs += processingOffsetUs
        videoFrameProcessingOffsetCount++
    }

    private fun renderOutputBuffer(
        codec: MediaCodecAdapter, index: Int, presentationTimeUs: Long, releaseTimeNs: Long
    ) {
        renderOutputBufferV21(codec, index, presentationTimeUs, releaseTimeNs)
    }

    @Deprecated(
        """Override {@link #renderOutputBufferV21} instead. The library has min SDK 21, so
        this method is never called."""
    )
    protected fun renderOutputBuffer(
        codec: MediaCodecAdapter,
        index: Int,
        presentationTimeUs: Long
    ) {
        beginSection("releaseOutputBuffer")
        codec.releaseOutputBuffer(index, true)
        endSection()
        decoderCounters.renderedOutputBufferCount++
        consecutiveDroppedFrameCount = 0
        if (videoSink == null) {
            maybeNotifyVideoSizeChanged(decodedVideoSize)
            maybeNotifyRenderedFirstFrame()
        }
    }

    protected fun renderOutputBufferV21(
        codec: MediaCodecAdapter, index: Int, presentationTimeUs: Long, releaseTimeNs: Long
    ) {
        beginSection("releaseOutputBuffer")
        codec.releaseOutputBuffer(index, releaseTimeNs)
        endSection()
        decoderCounters.renderedOutputBufferCount++
        consecutiveDroppedFrameCount = 0
        if (videoSink == null) {
            maybeNotifyVideoSizeChanged(decodedVideoSize)
            maybeNotifyRenderedFirstFrame()
        }
    }

    private fun hasSurfaceForCodec(codecInfo: MediaCodecInfo): Boolean {
        return videoSink != null || (this.surface != null && surface!!.isValid())
                || shouldUseDetachedSurface(codecInfo)
                || shouldUsePlaceholderSurface(codecInfo)
    }

    private fun getSurfaceForCodec(codecInfo: MediaCodecInfo): Surface? {
        if (videoSink != null) {
            return videoSink!!.inputSurface
        } else if (this.surface != null) {
            return this.surface
        } else if (shouldUseDetachedSurface(codecInfo)) {
            return null
        } else {
            Preconditions.checkState(shouldUsePlaceholderSurface(codecInfo))
            if (placeholderSurface != null && placeholderSurface!!.secure != codecInfo.secure) {
                // We can't re-use the current placeholder surface instance with the new decoder.
                releasePlaceholderSurface()
            }
            if (placeholderSurface == null) {
                placeholderSurface =
                    PlaceholderSurface.newInstance(context, codecInfo.secure)
            }
            return placeholderSurface
        }
    }

    protected fun shouldUseDetachedSurface(codecInfo: MediaCodecInfo): Boolean {
        return Build.VERSION.SDK_INT >= 35 && codecInfo.detachedSurfaceSupported
    }

    protected fun shouldUsePlaceholderSurface(codecInfo: MediaCodecInfo): Boolean {
        return !tunneling && !codecNeedsSetOutputSurfaceWorkaround(codecInfo.name) && (!codecInfo.secure || PlaceholderSurface.isSecureSupported(
            context
        ))
    }

    private fun releasePlaceholderSurface() {
        if (placeholderSurface != null) {
            placeholderSurface!!.release()
            placeholderSurface = null
        }
    }

    private fun maybeSetupTunnelingForFirstFrame() {
        if (!tunneling) {
            return
        }
        val codec = codec
        if (codec == null) {
            // If codec is null, then the setup will be triggered again in onCodecInitialized.
            return
        }
        tunnelingOnFrameRenderedListener = OnFrameRenderedListener(codec)
        if (Build.VERSION.SDK_INT >= 33) {
            // This should be the default anyway according to the API contract, but some devices are known
            // to not adhere to this contract and need to get the parameter explicitly. See
            // https://github.com/androidx/media/issues/1169.
            val codecParameters = Bundle()
            codecParameters.putInt(MediaCodec.PARAMETER_KEY_TUNNEL_PEEK, 1)
            codec.setParameters(codecParameters)
        }
    }

    private fun updateCodecImportance() {
        val codec = codec
        if (codec == null) {
            // If codec is null, then the importance will be set when initializing the codec.
            return
        }
        if (Build.VERSION.SDK_INT >= 35) {
            val codecParameters = Bundle()
            codecParameters.putInt(MediaFormat.KEY_IMPORTANCE, max(0, -rendererPriority))
            codec.setParameters(codecParameters)
        }
    }

    private fun maybeNotifyRenderedFirstFrame() {
        if (videoFrameReleaseControl.onFrameReleasedIsFirstFrame() && this.surface != null) {
            notifyRenderedFirstFrame()
        }
    }

    @RequiresNonNull("displaySurface")
    private fun notifyRenderedFirstFrame() {
        eventDispatcher.renderedFirstFrame(this.surface!!)
        haveReportedFirstFrameRenderedForCurrentSurface = true
    }

    private fun maybeRenotifyRenderedFirstFrame() {
        if (this.surface != null && haveReportedFirstFrameRenderedForCurrentSurface) {
            eventDispatcher.renderedFirstFrame(this.surface!!)
        }
    }

    private fun maybeNotifyVideoSizeChanged(newOutputSize: VideoSize) {
        if (newOutputSize != VideoSize.UNKNOWN && newOutputSize != reportedVideoSize) {
            reportedVideoSize = newOutputSize
            eventDispatcher.videoSizeChanged(reportedVideoSize!!)
        }
    }

    private fun maybeRenotifyVideoSizeChanged() {
        if (reportedVideoSize != null) {
            eventDispatcher.videoSizeChanged(reportedVideoSize!!)
        }
    }

    private fun maybeNotifyDroppedFrames() {
        if (droppedFrames > 0) {
            val now = getClock().elapsedRealtime()
            val elapsedMs = now - droppedFrameAccumulationStartTimeMs
            eventDispatcher.droppedFrames(droppedFrames, elapsedMs)
            droppedFrames = 0
            droppedFrameAccumulationStartTimeMs = now
        }
    }

    private fun maybeNotifyVideoFrameProcessingOffset() {
        if (videoFrameProcessingOffsetCount != 0) {
            eventDispatcher.reportVideoFrameProcessingOffset(
                totalVideoFrameProcessingOffsetUs, videoFrameProcessingOffsetCount
            )
            totalVideoFrameProcessingOffsetUs = 0
            videoFrameProcessingOffsetCount = 0
        }
    }

    private fun setOutputSurface(codec: MediaCodecAdapter, surface: Surface?) {
        if (surface != null) {
            setOutputSurfaceV23(codec, surface)
        } else if (Build.VERSION.SDK_INT >= 35) {
            detachOutputSurfaceV35(codec)
        } else {
            throw IllegalStateException()
        }
        if (DEBUG_LOG_ENABLED) {
            d(DEBUG_LOG_TAG, "video, codec surface=" + surface)
        }
    }

    protected fun setOutputSurfaceV23(codec: MediaCodecAdapter, surface: Surface) {
        codec.setOutputSurface(surface)
    }

    @RequiresApi(35)
    protected fun detachOutputSurfaceV35(codec: MediaCodecAdapter) {
        codec.detachOutputSurface()
    }

    @SuppressLint("InlinedApi")
    protected fun getMediaFormat(
        format: Format,
        codecMimeType: String?,
        codecMaxValues: CodecMaxValues,
        codecOperatingRate: Float,
        deviceNeedsNoPostProcessWorkaround: Boolean,
        tunnelingAudioSessionId: Int
    ): MediaFormat {
        val mediaFormat = MediaFormat()
        // Set format parameters that should always be set.
        mediaFormat.setString(MediaFormat.KEY_MIME, codecMimeType)
        mediaFormat.setInteger(MediaFormat.KEY_WIDTH, format.width)
        mediaFormat.setInteger(MediaFormat.KEY_HEIGHT, format.height)
        MediaFormatUtil.setCsdBuffers(mediaFormat, format.initializationData)
        // Set format parameters that may be unset.
        MediaFormatUtil.maybeSetFloat(mediaFormat, MediaFormat.KEY_FRAME_RATE, format.frameRate)
        MediaFormatUtil.maybeSetInteger(
            mediaFormat,
            MediaFormat.KEY_ROTATION,
            format.rotationDegrees
        )
        MediaFormatUtil.maybeSetColorInfo(mediaFormat, format.colorInfo)
        if (MimeTypes.VIDEO_DOLBY_VISION == format.sampleMimeType) {
            // Some phones require the profile to be set on the codec.
            // See https://github.com/google/ExoPlayer/pull/5438.
            val codecProfileAndLevel =
                getCodecProfileAndLevel(format)
            if (codecProfileAndLevel != null) {
                MediaFormatUtil.maybeSetInteger(
                    mediaFormat, MediaFormat.KEY_PROFILE, codecProfileAndLevel.first!!
                )
            }
        }
        // Set codec max values.
        mediaFormat.setInteger(MediaFormat.KEY_MAX_WIDTH, codecMaxValues.width)
        mediaFormat.setInteger(MediaFormat.KEY_MAX_HEIGHT, codecMaxValues.height)
        MediaFormatUtil.maybeSetInteger(
            mediaFormat, MediaFormat.KEY_MAX_INPUT_SIZE, codecMaxValues.inputSize
        )
        // Set codec configuration values.
        mediaFormat.setInteger(MediaFormat.KEY_PRIORITY, 0 )
        if (codecOperatingRate != CODEC_OPERATING_RATE_UNSET) {
            mediaFormat.setFloat(MediaFormat.KEY_OPERATING_RATE, codecOperatingRate)
        }
        if (deviceNeedsNoPostProcessWorkaround) {
            mediaFormat.setInteger("no-post-process", 1)
            mediaFormat.setInteger("auto-frc", 0)
        }
        if (tunnelingAudioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            mediaFormat.setFeatureEnabled(CodecCapabilities.FEATURE_TunneledPlayback, true)
            mediaFormat.setInteger(MediaFormat.KEY_AUDIO_SESSION_ID, tunnelingAudioSessionId)
        }
        if (Build.VERSION.SDK_INT >= 35) {
            mediaFormat.setInteger(MediaFormat.KEY_IMPORTANCE, max(0, -rendererPriority))
        }
        applyCodecParametersToMediaFormat(mediaFormat)
        return mediaFormat
    }

    protected fun getCodecMaxValues(
        codecInfo: MediaCodecInfo, format: Format, streamFormats: Array<Format>
    ): CodecMaxValues {
        var maxWidth = format.width
        var maxHeight = format.height
        var maxInputSize: Int = getMaxInputSize(codecInfo, format)
        if (streamFormats.size == 1) {
            // The single entry in streamFormats must correspond to the format for which the codec is
            // being configured.
            if (maxInputSize != Format.NO_VALUE) {
                val codecMaxInputSize: Int = getCodecMaxInputSize(codecInfo, format)
                if (codecMaxInputSize != Format.NO_VALUE) {
                    // Scale up the initial video decoder maximum input size so playlist item transitions with
                    // small increases in maximum sample size don't require reinitialization. This only makes
                    // a difference if the exact maximum sample sizes are known from the container.
                    val scaledMaxInputSize =
                        (maxInputSize * INITIAL_FORMAT_MAX_INPUT_SIZE_SCALE_FACTOR).toInt()
                    // Avoid exceeding the maximum expected for the codec.
                    maxInputSize = min(scaledMaxInputSize, codecMaxInputSize)
                }
            }
            return CodecMaxValues(maxWidth, maxHeight, maxInputSize)
        }
        var haveUnknownDimensions = false
        for (streamFormat in streamFormats) {
            var streamFormat = streamFormat
            if (format.colorInfo != null && streamFormat.colorInfo == null) {
                // streamFormat likely has incomplete color information. Copy the complete color information
                // from format to avoid codec re-use being ruled out for only this reason.
                streamFormat = streamFormat.buildUpon().setColorInfo(format.colorInfo).build()
            }
            if (codecInfo.canReuseCodec(
                    format,
                    streamFormat
                ).result != DecoderReuseEvaluation.REUSE_RESULT_NO
            ) {
                haveUnknownDimensions = haveUnknownDimensions or
                        (streamFormat.width == Format.NO_VALUE || streamFormat.height == Format.NO_VALUE)
                maxWidth = max(maxWidth, streamFormat.width)
                maxHeight = max(maxHeight, streamFormat.height)
                maxInputSize = max(maxInputSize, getMaxInputSize(codecInfo, streamFormat))
            }
        }
        if (haveUnknownDimensions) {
            w(TAG, "Resolutions unknown. Codec max resolution: " + maxWidth + "x" + maxHeight)
            val codecMaxSize: Point? = getCodecMaxSize(codecInfo, format)
            if (codecMaxSize != null) {
                maxWidth = max(maxWidth, codecMaxSize.x)
                maxHeight = max(maxHeight, codecMaxSize.y)
                maxInputSize =
                    max(
                        maxInputSize,
                        getCodecMaxInputSize(
                            codecInfo,
                            format.buildUpon().setWidth(maxWidth).setHeight(maxHeight).build()
                        )
                    )
                w(TAG, "Codec max resolution adjusted to: " + maxWidth + "x" + maxHeight)
            }
        }
        return CodecMaxValues(maxWidth, maxHeight, maxInputSize)
    }

    override fun createDecoderException(
        cause: Throwable, codecInfo: MediaCodecInfo?
    ): MediaCodecDecoderException {
        return MediaCodecVideoDecoderException(cause, codecInfo, this.surface)
    }

    /*
   * TODO:
   *
   * 1. Validate that Android device certification now ensures correct behavior, and add a
   *    corresponding SDK_INT upper bound for applying the workaround (probably SDK_INT < 26).
   * 2. Determine a complete list of affected devices.
   * 3. Some of the devices in this list only fail to support setOutputSurface when switching from
   *    a SurfaceView provided Surface to a Surface of another type (e.g. TextureView/DummySurface),
   *    and vice versa. One hypothesis is that setOutputSurface fails when the surfaces have
   *    different pixel formats. If we can find a way to query the Surface instances to determine
   *    whether this case applies, then we'll be able to provide a more targeted workaround.
   */
    
    protected fun codecNeedsSetOutputSurfaceWorkaround(name: String): Boolean {
        if (name.startsWith("OMX.google")) {
            // Google OMX decoders are not known to have this issue on any API level.
            return false
        }
        synchronized(MediaCodecVideoRenderer::class.java) {
            if (!evaluatedDeviceNeedsSetOutputSurfaceWorkaround) {
                deviceNeedsSetOutputSurfaceWorkaround =
                    evaluateDeviceNeedsSetOutputSurfaceWorkaround()
                evaluatedDeviceNeedsSetOutputSurfaceWorkaround = true
            }
        }
        return deviceNeedsSetOutputSurfaceWorkaround
    }

    protected class CodecMaxValues(val width: Int, val height: Int, val inputSize: Int)

    private fun shouldDiscardCodecForFrameRateChange(
        mediaCodecInfo: MediaCodecInfo,
        oldFormat: Format,
        newFormat: Format,
        isAdaptiveFormatChange: Boolean
    ): Boolean {
        if (changeFrameRateStrategy == C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF) {
            // We are not attempting to change the surface frame rate anyway.
            return false
        }
        if (Build.VERSION.SDK_INT >= 31 || (Build.VERSION.SDK_INT == 30 && !Build.MODEL.startsWith("MiTV"))) {
            // We can tell the surface about the new frame rate so it can handle this seamlessly. This
            // wasn't possible before API 30 where the initially estimated screen refresh rate from the
            // previous format is likely kept. See https://github.com/androidx/media/issues/2941. MiTV
            // boxes were also reported to still have this problem on API 30.
            return false
        }
        if (oldFormat.frameRate == Format.NO_VALUE.toFloat() || newFormat.frameRate == Format.NO_VALUE.toFloat()) {
            // Not enough information to tell if there was a frame rate change.
            return false
        }
        if (mediaCodecInfo.secure && isAdaptiveFormatChange) {
            // Many secure codecs introduce a black frame when discarded, which is more distracting for an
            // adaptive switch than the potential display refresh mismatch. For non-adaptive switches
            // (e.g. playlist transitions), the codec can still be discarded.
            // See https://github.com/androidx/media/issues/3120.
            return false
        }
        val frameRateRatio =
            (max(newFormat.frameRate, oldFormat.frameRate)
                    / min(newFormat.frameRate, oldFormat.frameRate))
        // Only need to discard the codec if the frame rates are not the same or a close multiple of
        // each other (where the change could be handled seamlessly even without a surface refresh
        // rate change).
        return abs(frameRateRatio - Math.round(frameRateRatio)) > MAX_FRAME_RATE_RATIO_DIFF_TO_KEEP_CODEC
    }

    private inner class OnFrameRenderedListener
        (codec: MediaCodecAdapter) : MediaCodecAdapter.OnFrameRenderedListener, Handler.Callback {
        private val handler: Handler

        init {
            handler = createHandlerForCurrentLooper( this)
            codec.setOnFrameRenderedListener( this, handler)
        }

        override fun onFrameRendered(
            codec: MediaCodecAdapter,
            presentationTimeUs: Long,
            nanoTime: Long
        ) {
            // Workaround bug in MediaCodec that causes deadlock if you call directly back into the
            // MediaCodec from this listener method.
            // Deadlock occurs because MediaCodec calls this listener method holding a lock,
            // which may also be required by calls made back into the MediaCodec.
            // This was fixed in https://android-review.googlesource.com/1156807.
            //
            // The workaround queues the event for subsequent processing, where the lock will not be held.
            if (Build.VERSION.SDK_INT < 30) {
                val message =
                    Message.obtain(
                        handler,  
                        HANDLE_FRAME_RENDERED,  
                        (presentationTimeUs shr 32).toInt(),  
                        presentationTimeUs.toInt()
                    )
                handler.sendMessageAtFrontOfQueue(message)
            } else {
                handleFrameRendered(presentationTimeUs)
            }
        }

        override fun handleMessage(message: Message): Boolean {
            when (message.what) {
                HANDLE_FRAME_RENDERED -> {
                    handleFrameRendered(toLong(message.arg1, message.arg2))
                    return true
                }

                else -> return false
            }
        }

        fun handleFrameRendered(presentationTimeUs: Long) {
            if (this != tunnelingOnFrameRenderedListener || codec == null) {
                // Stale event.
                return
            }
            if (presentationTimeUs == TUNNELING_EOS_PRESENTATION_TIME_US) {
                onProcessedTunneledEndOfStream()
            } else {
                try {
                    onProcessedTunneledBuffer(presentationTimeUs)
                } catch (e: ExoPlaybackException) {
                    setPendingPlaybackException(e)
                }
            }
        }

    }

    companion object {
        const val DEFAULT_LATE_THRESHOLD_TO_DROP_DECODER_INPUT_US: Long = 15000
        private const val HANDLE_FRAME_RENDERED = 0

        private const val TAG = "MediaCodecVideoRenderer"
        private const val KEY_CROP_LEFT = "crop-left"
        private const val KEY_CROP_RIGHT = "crop-right"
        private const val KEY_CROP_BOTTOM = "crop-bottom"
        private const val KEY_CROP_TOP = "crop-top"

        // TODO: b/388762778 - Replace with MediaFormat.KEY_HDR_ST2094_50_INFO once compile SDK is 37.
        private const val KEY_HDR_ST2094_50_INFO = "hdr-st2094-50-info"

        // Long edge length in pixels for standard video formats, in decreasing in order.
        private val STANDARD_LONG_EDGE_VIDEO_PX =
            intArrayOf(1920, 1600, 1440, 1280, 960, 854, 640, 540, 480)

        private const val INITIAL_FORMAT_MAX_INPUT_SIZE_SCALE_FACTOR = 1.5f

        private val TUNNELING_EOS_PRESENTATION_TIME_US = Long.MAX_VALUE

        private val HEVC_MAX_INPUT_SIZE_THRESHOLD = 2 * 1024 * 1024

        private val MIN_EARLY_US_LATE_THRESHOLD = -30000L

        private val MIN_EARLY_US_VERY_LATE_THRESHOLD = -500000L

        private const val OFFSET_FROM_PERIOD_END_TO_TREAT_AS_LAST_US = 100000L

        private const val MAX_CONSECUTIVE_DROPPED_INPUT_BUFFERS_COUNT_TO_DISCARD_HEADER = 0

        private const val MAX_FRAME_RATE_RATIO_DIFF_TO_KEEP_CODEC = 0.01f

        const val DEFAULT_EARLY_SCHEDULING_THRESHOLD_US: Long = 50000

        private var evaluatedDeviceNeedsSetOutputSurfaceWorkaround = false
        private var deviceNeedsSetOutputSurfaceWorkaround = false

        @Throws(DecoderQueryException::class)
        fun supportsFormat(
            context: Context, mediaCodecSelector: MediaCodecSelector, format: Format
        ): @RendererCapabilities.Capabilities Int {
            return supportsFormatInternal(context, mediaCodecSelector, format)
        }

        @Throws(DecoderQueryException::class)
        private fun supportsFormatInternal(
            context: Context, mediaCodecSelector: MediaCodecSelector, format: Format
        ): @RendererCapabilities.Capabilities Int {
            val mimeType = format.sampleMimeType
            if (!isVideo(mimeType)) {
                return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
            }
            val drmInitData = format.drmInitData
            // Assume encrypted content requires secure decoders.
            val requiresSecureDecryption = drmInitData != null
            var decoderInfos: List<MediaCodecInfo> =
                getDecoderInfos(
                    context,
                    mediaCodecSelector,
                    format,
                    requiresSecureDecryption,  
                    false
                )
            if (requiresSecureDecryption && decoderInfos.isEmpty()) {
                // No secure decoders are available. Fall back to non-secure decoders.
                decoderInfos =
                    getDecoderInfos(
                        context,
                        mediaCodecSelector,
                        format,  
                        false,  
                        false
                    )
            }
            if (decoderInfos.isEmpty()) {
                return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
            }
            if (!supportsFormatDrm(format)) {
                return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM)
            }
            // Check whether the first decoder supports the format. This is the preferred decoder for the
            // format's MIME type, according to the MediaCodecSelector.
            var decoderInfo = decoderInfos[0]
            var isFormatSupported = decoderInfo.isFormatSupported(context, format)
            var isPreferredDecoder = true
            if (!isFormatSupported) {
                // Check whether any of the other decoders support the format.
                for (i in 1..<decoderInfos.size) {
                    val otherDecoderInfo = decoderInfos[i]
                    if (otherDecoderInfo.isFormatSupported(context, format)) {
                        decoderInfo = otherDecoderInfo
                        isFormatSupported = true
                        isPreferredDecoder = false
                        break
                    }
                }
            }
            @C.FormatSupport val formatSupport: @C.FormatSupport Int =
                if (isFormatSupported) C.FORMAT_HANDLED else C.FORMAT_EXCEEDS_CAPABILITIES
            val adaptiveSupport: @AdaptiveSupport Int =
                if (decoderInfo.isSeamlessAdaptationSupported(format))
                    RendererCapabilities.ADAPTIVE_SEAMLESS
                else
                    RendererCapabilities.  ADAPTIVE_NOT_SEAMLESS
            val hardwareAccelerationSupport: @HardwareAccelerationSupport Int =
                if (decoderInfo.hardwareAccelerated)
                    RendererCapabilities.  HARDWARE_ACCELERATION_SUPPORTED
                else
                    RendererCapabilities.  HARDWARE_ACCELERATION_NOT_SUPPORTED
            var decoderSupport: @DecoderSupport Int =
                if (isPreferredDecoder)  RendererCapabilities.DECODER_SUPPORT_PRIMARY else  RendererCapabilities.DECODER_SUPPORT_FALLBACK

            if (Build.VERSION.SDK_INT >= 26 && MimeTypes.VIDEO_DOLBY_VISION == format.sampleMimeType
                && !Api26.doesDisplaySupportDolbyVision(context)
            ) {
                decoderSupport =  RendererCapabilities.DECODER_SUPPORT_FALLBACK_MIMETYPE
            }

            var tunnelingSupport: @TunnelingSupport Int =  RendererCapabilities.TUNNELING_NOT_SUPPORTED
            if (isFormatSupported) {
                val tunnelingDecoderInfos: List<MediaCodecInfo> =
                    getDecoderInfos(
                        context,
                        mediaCodecSelector,
                        format,
                        requiresSecureDecryption,  
                        true
                    )
                if (!tunnelingDecoderInfos.isEmpty()) {
                    val tunnelingDecoderInfo =
                        MediaCodecUtil.getDecoderInfosSortedByFormatSupport(
                            context, tunnelingDecoderInfos, format
                        )[0]
                    if (tunnelingDecoderInfo.isFormatSupported(context, format)
                        && tunnelingDecoderInfo.isSeamlessAdaptationSupported(format)
                    ) {
                        tunnelingSupport =  RendererCapabilities.TUNNELING_SUPPORTED
                    }
                }
            }

            return RendererCapabilities.create(
                formatSupport,
                adaptiveSupport,
                tunnelingSupport,
                hardwareAccelerationSupport,
                decoderSupport
            )
        }

        // Other methods
        
        @Throws(DecoderQueryException::class)
        private fun getDecoderInfos(
            context: Context,
            mediaCodecSelector: MediaCodecSelector,
            format: Format,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean
        ): List<MediaCodecInfo> {
            if (format.sampleMimeType == null) {
                return listOf()
            }
            if (Build.VERSION.SDK_INT >= 26 && MimeTypes.VIDEO_DOLBY_VISION == format.sampleMimeType
                && !Api26.doesDisplaySupportDolbyVision(context)
            ) {
                val alternativeDecoderInfos: List<MediaCodecInfo> =
                    getAlternativeDecoderInfos(
                        mediaCodecSelector, format, requiresSecureDecoder, requiresTunnelingDecoder
                    )
                if (!alternativeDecoderInfos.isEmpty()) {
                    return alternativeDecoderInfos
                }
            }
            return getDecoderInfosSoftMatch(
                mediaCodecSelector, format, requiresSecureDecoder, requiresTunnelingDecoder
            )
        }

        fun getCodecMaxInputSize(codecInfo: MediaCodecInfo, format: Format): Int {
            val width = format.width
            val height = format.height
            if (width == Format.NO_VALUE || height == Format.NO_VALUE) {
                // We can't infer a maximum input size without video dimensions.
                return Format.NO_VALUE
            }

            var sampleMimeType = format.sampleMimeType!!
            if (MimeTypes.VIDEO_DOLBY_VISION == sampleMimeType) {
                // Dolby vision can be a wrapper around H.264, H.265 or AV1. We assume it's wrapping H.265 by
                // default because it's the common case, and because some devices may fail to allocate the
                // codec when the larger buffer size required for H.264/AV1 is requested. We size buffers
                // for H.264/AV1 only if the format contains sufficient information for us to determine
                // unambiguously that it's a H.264/AV1 based profile.
                sampleMimeType = MimeTypes.VIDEO_H265
                val codecProfileAndLevel =
                    getCodecProfileAndLevel(format)
                if (codecProfileAndLevel != null) {
                    val profile: Int = codecProfileAndLevel.first!!
                    if (profile == CodecProfileLevel.DolbyVisionProfileDvavSe || profile == CodecProfileLevel.DolbyVisionProfileDvavPer || profile == CodecProfileLevel.DolbyVisionProfileDvavPen) {
                        sampleMimeType = MimeTypes.VIDEO_H264
                    } else if (profile == CodecProfileLevel.DolbyVisionProfileDvav110) {
                        sampleMimeType = MimeTypes.VIDEO_AV1
                    }
                }
            }

            // Attempt to infer a maximum input size from the format.
            when (sampleMimeType) {
                MimeTypes.VIDEO_H263, MimeTypes.VIDEO_MP4V, MimeTypes.VIDEO_AV1, MimeTypes.VIDEO_VP8 ->         // Assume a min compression of 2 similar to the platform's SoftVPX.cpp.
                    return getMaxSampleSize( width * height,  
                        2
                    )

                MimeTypes.VIDEO_H265 ->         // Assume a min compression of 2 similar to the platform's C2SoftHevcDec.cpp, but restrict
                    // the minimum size.
                    return max(
                        HEVC_MAX_INPUT_SIZE_THRESHOLD,
                        getMaxSampleSize( width * height,  
                            2
                        )
                    )

                MimeTypes.VIDEO_H264 -> {
                    if (enableWorkarounds()
                        && ("BRAVIA 4K 2015" == Build.MODEL // Sony Bravia 4K
                                || ("Amazon" == Build.MANUFACTURER
                                && ("KFSOWI" == Build.MODEL // Kindle Soho
                                || ("AFTS" == Build.MODEL && codecInfo.secure))))
                    ) { // Fire TV Gen 2
                        // Use the default value for cases where platform limitations may prevent buffers of the
                        // calculated maximum input size from being allocated.
                        return Format.NO_VALUE
                    }
                    // Round up width/height to an integer number of macroblocks.
                    val maxPixels = ceilDivide(width, 16) * ceilDivide(height, 16) * 16 * 16
                    return getMaxSampleSize(maxPixels,  2)
                }

                MimeTypes.VIDEO_VP9 -> return getMaxSampleSize( width * height,  
                    4
                )

                else ->         // Leave the default max input size.
                    return Format.NO_VALUE
            }
        }

        @RequiresApi(29)
        private fun setHdr10PlusInfoV29(codec: MediaCodecAdapter, hdr10PlusInfo: ByteArray?) {
            val codecParameters = Bundle()
            codecParameters.putByteArray(MediaCodec.PARAMETER_KEY_HDR10_PLUS_INFO, hdr10PlusInfo)
            codec.setParameters(codecParameters)
        }

        private fun getCodecMaxSize(codecInfo: MediaCodecInfo, format: Format): Point? {
            val isVerticalVideo = format.height > format.width
            val formatLongEdgePx = if (isVerticalVideo) format.height else format.width
            val formatShortEdgePx = if (isVerticalVideo) format.width else format.height
            val aspectRatio = formatShortEdgePx.toFloat() / formatLongEdgePx
            for (longEdgePx in STANDARD_LONG_EDGE_VIDEO_PX) {
                val shortEdgePx = (longEdgePx * aspectRatio).toInt()
                if (longEdgePx <= formatLongEdgePx || shortEdgePx <= formatShortEdgePx) {
                    // Don't return a size not larger than the format for which the codec is being configured.
                    return null
                } else {
                    val alignedSize =
                        codecInfo.alignVideoSize(
                            if (isVerticalVideo) shortEdgePx else longEdgePx,
                            if (isVerticalVideo) longEdgePx else shortEdgePx
                        )
                    val frameRate = format.frameRate
                    if (alignedSize != null
                        && codecInfo.isVideoSizeAndRateSupported(
                            alignedSize.x,
                            alignedSize.y,
                            frameRate.toDouble()
                        )
                    ) {
                        return alignedSize
                    }
                }
            }
            return null
        }

        protected fun getMaxInputSize(codecInfo: MediaCodecInfo, format: Format): Int {
            if (format.maxInputSize != Format.NO_VALUE) {
                // The format defines an explicit maximum input size. Add the total size of initialization
                // data buffers, as they may need to be queued in the same input buffer as the largest sample.
                var totalInitializationDataSize = 0
                val initializationDataCount = format.initializationData.size
                for (i in 0..<initializationDataCount) {
                    totalInitializationDataSize += format.initializationData[i].size
                }
                return format.maxInputSize + totalInitializationDataSize
            } else {
                return getCodecMaxInputSize(codecInfo, format)
            }
        }

        private fun deviceNeedsNoPostProcessWorkaround(): Boolean {
            if (!enableWorkarounds()) {
                return false
            }
            // Nvidia devices prior to M try to adjust the playback rate to better map the frame-rate of
            // content to the refresh rate of the display. For example playback of 23.976fps content is
            // adjusted to play at 1.001x speed when the output display is 60Hz. Unfortunately the
            // implementation causes ExoPlayer's reported playback position to drift out of sync. Captions
            // also lose sync [Internal: b/26453592]. Even after M, the devices may apply post processing
            // operations that can modify frame output timestamps, which is incompatible with ExoPlayer's
            // logic for skipping decode-only frames.
            return "NVIDIA" == Build.MANUFACTURER
        }

        private fun debugLogForBufferRelease(
            frameReleaseAction: @FrameReleaseAction Int,
            bufferPresentationTimeUs: Long,
            positionUs: Long,
            isDecodeOnlyBuffer: Boolean,
            isLastBuffer: Boolean,
            videoFrameReleaseInfo: FrameReleaseInfo,
            lastFrameReleaseTimeNs: Long
        ) {
            if (frameReleaseAction == VideoFrameReleaseControl.FRAME_RELEASE_TRY_AGAIN_LATER) {
                return
            }
            var debugString =
                ("video, release output, pts="
                        + bufferPresentationTimeUs
                        + ", pos="
                        + positionUs
                        + ", early="
                        + videoFrameReleaseInfo.earlyUs)
            if (isDecodeOnlyBuffer) {
                debugString += ", decode-only"
            }
            if (isLastBuffer) {
                debugString += ", last-buffer"
            }
            when (frameReleaseAction) {
                VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY -> debugString += ", release immediately"
                VideoFrameReleaseControl.FRAME_RELEASE_SKIP -> debugString += ", skip"
                VideoFrameReleaseControl.FRAME_RELEASE_DROP -> debugString += ", drop"
                VideoFrameReleaseControl.FRAME_RELEASE_IGNORE -> debugString += ", ignore"
                VideoFrameReleaseControl.FRAME_RELEASE_SCHEDULED -> {
                    val releaseTimeNs = videoFrameReleaseInfo.releaseTimeNs
                    debugString += ", release=" + (releaseTimeNs / 1000)
                    if (lastFrameReleaseTimeNs != 0L) {
                        debugString += " (+" + (releaseTimeNs - lastFrameReleaseTimeNs) / 1000 + ")"
                    }
                }

                VideoFrameReleaseControl.FRAME_RELEASE_TRY_AGAIN_LATER -> {}
                else -> {}
            }
            d(DEBUG_LOG_TAG, debugString)
        }

        private fun getMaxSampleSize(pixelCount: Int, minCompressionRatio: Int): Int {
            return (pixelCount * 3) / (2 * minCompressionRatio)
        }

        private fun evaluateDeviceNeedsSetOutputSurfaceWorkaround(): Boolean {
            if (!enableWorkarounds()) {
                return false
            }
            if (Build.VERSION.SDK_INT <= 28) {
                // Workaround for MiTV and MiBox devices which have been observed broken up to API 28.
                // https://github.com/google/ExoPlayer/issues/5169,
                // https://github.com/google/ExoPlayer/issues/6899.
                // https://github.com/google/ExoPlayer/issues/8014.
                // https://github.com/google/ExoPlayer/issues/8329.
                // https://github.com/google/ExoPlayer/issues/9710.
                when (Build.DEVICE) {
                    "aquaman", "dangal", "dangalUHD", "dangalFHD", "magnolia", "machuca", "once", "oneday" -> return true
                    else -> {}
                }
            }
            if (Build.VERSION.SDK_INT <= 27 && "HWEML" == Build.DEVICE) {
                // Workaround for Huawei P20:
                // https://github.com/google/ExoPlayer/issues/4468#issuecomment-459291645.
                return true
            }
            when (Build.MODEL) {
                "AFTA", "AFTN", "AFTR", "AFTEU011", "AFTEU014", "AFTEUFF014", "AFTJMST12", "AFTKMST12", "AFTSO001" -> return true
                else -> {}
            }
            if (Build.VERSION.SDK_INT <= 26) {
                // In general, devices running API level 27 or later should be unaffected unless observed
                // otherwise. Enable the workaround on a per-device basis. Works around:
                // https://github.com/google/ExoPlayer/issues/3236,
                // https://github.com/google/ExoPlayer/issues/3355,
                // https://github.com/google/ExoPlayer/issues/3439,
                // https://github.com/google/ExoPlayer/issues/3724,
                // https://github.com/google/ExoPlayer/issues/3835,
                // https://github.com/google/ExoPlayer/issues/4006,
                // https://github.com/google/ExoPlayer/issues/4084,
                // https://github.com/google/ExoPlayer/issues/4104,
                // https://github.com/google/ExoPlayer/issues/4134,
                // https://github.com/google/ExoPlayer/issues/4315,
                // https://github.com/google/ExoPlayer/issues/4419,
                // https://github.com/google/ExoPlayer/issues/4460,
                // https://github.com/google/ExoPlayer/issues/4468,
                // https://github.com/google/ExoPlayer/issues/5312,
                // https://github.com/google/ExoPlayer/issues/6503.
                // https://github.com/google/ExoPlayer/issues/8014,
                // https://github.com/google/ExoPlayer/pull/8030.
                when (Build.DEVICE) {
                    "1601", "1713", "1714", "601LV", "602LV", "A10-70F", "A10-70L", "A1601", "A2016a40", "A7000-a", "A7000plus", "A7010a48", "A7020a48", "AquaPowerM", "ASUS_X00AD_2", "Aura_Note_2", "b5", "BLACK-1X", "BRAVIA_ATV2", "BRAVIA_ATV3_4K", "C1", "ComioS1", "CP8676_I02", "CPH1609", "CPH1715", "CPY83_I00", "cv1", "cv3", "deb", "DM-01K", "E5643", "ELUGA_A3_Pro", "ELUGA_Note", "ELUGA_Prim", "ELUGA_Ray_X", "EverStar_S", "F01H", "F01J", "F02H", "F03H", "F04H", "F04J", "F3111", "F3113", "F3116", "F3211", "F3213", "F3215", "F3311", "flo", "fugu", "GiONEE_CBL7513", "GiONEE_GBL7319", "GIONEE_GBL7360", "GIONEE_SWW1609", "GIONEE_SWW1627", "GIONEE_SWW1631", "GIONEE_WBL5708", "GIONEE_WBL7365", "GIONEE_WBL7519", "griffin", "htc_e56ml_dtul", "hwALE-H", "HWBLN-H", "HWCAM-H", "HWVNS-H", "HWWAS-H", "i9031", "iball8735_9806", "Infinix-X572", "iris60", "itel_S41", "j2xlteins", "JGZ", "K50a40", "kate", "l5460", "le_x6", "LS-5017", "M04", "M5c", "manning", "marino_f", "MEIZU_M5", "mh", "mido", "MX6", "namath", "nicklaus_f", "NX541J", "NX573J", "OnePlus5T", "p212", "P681", "P85", "pacificrim", "panell_d", "panell_dl", "panell_ds", "panell_dt", "PB2-670M", "PGN528", "PGN610", "PGN611", "Phantom6", "Pixi4-7_3G", "Pixi5-10_4G", "PLE", "PRO7S", "Q350", "Q4260", "Q427", "Q4310", "Q5", "QM16XE_U", "QX1", "RAIJIN", "santoni", "Slate_Pro", "SVP-DTV15", "s905x018", "taido_row", "TB3-730F", "TB3-730X", "TB3-850F", "TB3-850M", "tcl_eu", "V1", "V23GB", "V5", "vernee_M5", "watson", "whyred", "woods_f", "woods_fn", "X3_HK", "XE2X", "XT1663", "Z12_PRO", "Z80" -> return true

                    else -> {}
                }
                when (Build.MODEL) {
                    "JSN-L21" -> return true
                    else -> {}
                }
            }
            return false
        }
    }
}
