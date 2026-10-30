package androidx.media3.exoplayer.video

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Looper
import android.util.Pair
import android.util.SparseArray
import android.view.Surface
import androidx.annotation.FloatRange
import androidx.annotation.IntDef
import androidx.annotation.RestrictTo
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.DebugViewProvider
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.SurfaceInfo
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.VideoGraph
import androidx.media3.common.VideoSize
import androidx.media3.common.util.Clock

import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.GlUtil.GlException
import androidx.media3.common.util.HandlerWrapper
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.Size
import androidx.media3.common.util.TimedValueQueue
import androidx.media3.common.util.TimestampIterator
import androidx.media3.common.util.Util.formatInvariant
import androidx.media3.common.util.Util.getMaxPendingFramesCountForMediaCodecDecoders
import androidx.media3.exoplayer.video.VideoSink.FirstFrameReleaseInstruction
import androidx.media3.exoplayer.video.VideoSink.VideoFrameHandler
import androidx.media3.exoplayer.video.VideoSink.VideoSinkException
import com.google.common.base.Preconditions
import com.google.common.base.Supplier
import com.google.common.base.Suppliers

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executor

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class PlaybackVideoGraphWrapper private constructor(builder: Builder) : VideoGraph.Listener {
    
    interface Listener {
        fun onFrameAvailableForRendering() {}
        fun onFirstFrameRendered() {}
        fun onFrameDropped() {}
        fun onVideoSizeChanged(videoSize: VideoSize) {}
        fun onError(videoFrameProcessingException: VideoFrameProcessingException) {}
        fun onEnded(finalFramePresentationTimeUs: Long) {}
    }

    class Builder(
        context: Context,
         val videoFrameReleaseControl: VideoFrameReleaseControl
    ) {
         val context: Context
         var videoGraphFactory:VideoGraph.Factory? = null
         var enablePlaylistMode = false
         var clock: Clock
         var built = false
         var enableReplayableCache = false
         var lateThresholdToDropInputUs: Long
         var videoFrameReleaseEarlyTimeForecaster: VideoFrameReleaseEarlyTimeForecaster

        init {
            this.context = context.getApplicationContext()
            this.lateThresholdToDropInputUs = LATE_US_TO_DROP_INPUT_FRAME
            videoFrameReleaseEarlyTimeForecaster =
                VideoFrameReleaseEarlyTimeForecaster( 1f)
            clock = Clock.DEFAULT
        }

        fun setVideoGraphFactory(videoGraphFactory: VideoGraph.Factory?): Builder {
            this.videoGraphFactory = videoGraphFactory
            return this
        }

        fun setEnablePlaylistMode(enablePlaylistMode: Boolean): Builder {
            // This is set to true for ExoPlayer.setVideoEffects(). It's always false in
            // CompositionPlayer, even if the Composition has a single sequence, because CompositionPlayer
            // shouldn't behave differently for single and multi-sequence.
            this.enablePlaylistMode = enablePlaylistMode
            return this
        }

        fun setClock(clock: Clock): Builder {
            this.clock = clock
            return this
        }

        fun setEnableReplayableCache(enableReplayableCache: Boolean): Builder {
            this.enableReplayableCache = enableReplayableCache
            return this
        }

        fun experimentalSetLateThresholdToDropInputUs(lateThresholdToDropInputUs: Long): Builder {
            this.lateThresholdToDropInputUs = lateThresholdToDropInputUs
            return this
        }

        fun build(): PlaybackVideoGraphWrapper {
            Preconditions.checkState(!built)

            if (videoGraphFactory == null) {
                videoGraphFactory = ReflectiveSingleInputVideoGraphFactory(enableReplayableCache)
            }
            val playbackVideoGraphWrapper = PlaybackVideoGraphWrapper(this)
            built = true
            return playbackVideoGraphWrapper
        }

        fun setVideoFrameReleaseEarlyTimeForecaster(
            videoFrameReleaseEarlyTimeForecaster: VideoFrameReleaseEarlyTimeForecaster
        ): Builder {
            this.videoFrameReleaseEarlyTimeForecaster = videoFrameReleaseEarlyTimeForecaster
            return this
        }
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [STATE_CREATED, STATE_INITIALIZED, STATE_RELEASED])
    private annotation class State

    private val context: Context

    private val videoGraphFactory: VideoGraph.Factory
    private val inputVideoSinks: SparseArray<InputVideoSink?>
    private val enablePlaylistMode: Boolean
    private val defaultVideoSink: VideoSink
    private val videoFrameHandler: VideoFrameHandler
    private val clock: Clock
    private val listeners: CopyOnWriteArraySet<Listener>
    private val earlyThresholdToDropInputUs: Long
    private val videoFrameReleaseEarlyTimeForecaster: VideoFrameReleaseEarlyTimeForecaster
    private var pendingStreamChanges: TimedValueQueue<StreamChangeInfo?>
    private var videoGraphOutputFormat: Format
    private var compositorSettings: VideoCompositorSettings
    private var compositionEffects: List<Effect>
    private var handler:HandlerWrapper? = null
    private var videoGraph:VideoGraph? = null
    private var videoFrameMetadataListener:VideoFrameMetadataListener? = null
    private var requestOpenGlToneMapping = false
    private var isInputSdrToneMapped = false
    private var outputStreamStartPositionUs: Long
    private var outputStreamFirstFrameReleaseInstruction: @FirstFrameReleaseInstruction Int = 0
    private var currentSurfaceAndSize: Pair<Surface?, Size>? = null
    private var pendingFlushCount = 0
    private var state: @State Int
    private var lastOutputFramePresentationTimeUs: Long
    private var finalFramePresentationTimeUs: Long
    private var hasSignaledEndOfVideoGraphOutputStream = false
    private var totalVideoInputCount: Int
    private var registeredVideoInputCount = 0

    init {
        context = builder.context
        pendingStreamChanges = TimedValueQueue<StreamChangeInfo?>()
        videoGraphFactory =builder.videoGraphFactory!!
        inputVideoSinks = SparseArray<InputVideoSink?>()
        compositionEffects = listOf<Effect>()
        compositorSettings = VideoCompositorSettings.DEFAULT
        enablePlaylistMode = builder.enablePlaylistMode
        clock = builder.clock
        earlyThresholdToDropInputUs =
            if (builder.lateThresholdToDropInputUs != C.TIME_UNSET)
                -builder.lateThresholdToDropInputUs
            else
                C.TIME_UNSET
        videoFrameReleaseEarlyTimeForecaster = builder.videoFrameReleaseEarlyTimeForecaster
        defaultVideoSink =
            DefaultVideoSink(
                builder.videoFrameReleaseControl, videoFrameReleaseEarlyTimeForecaster, clock
            )
        videoFrameHandler =
            object : VideoFrameHandler {
                override fun render(renderTimestampNs: Long) {
                    videoGraph!!.renderOutputFrame(renderTimestampNs)
                }

                override fun skip() {
                    videoGraph!!.renderOutputFrame(VideoFrameProcessor.DROP_OUTPUT_FRAME)
                }
            }
        listeners = CopyOnWriteArraySet<Listener>()
        videoGraphOutputFormat = Format.Builder().build()
        outputStreamStartPositionUs = C.TIME_UNSET
        lastOutputFramePresentationTimeUs = C.TIME_UNSET
        finalFramePresentationTimeUs = C.TIME_UNSET
        totalVideoInputCount = C.LENGTH_UNSET
        state = STATE_CREATED
    }

    fun addListener(listener: Listener?) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener?) {
        listeners.remove(listener)
    }

    fun setTotalVideoInputCount(totalVideoInputCount: Int) {
        if (totalVideoInputCount < this.totalVideoInputCount) {
            // Currently we don't allow removing video from a sequence.
            // TODO: b/430250222 - Track types should be fixed after this, and this method could be
            //  removed.
            return
        }
        this.totalVideoInputCount = totalVideoInputCount
    }

    fun getSink(inputIndex: Int): VideoSink? {
        if (inputVideoSinks.contains(inputIndex)) {
            return inputVideoSinks.get(inputIndex)
        }
        val inputVideoSink = InputVideoSink(context, inputIndex)
        if (inputIndex == PRIMARY_SEQUENCE_INDEX) {
            addListener(inputVideoSink)
        }
        inputVideoSinks.put(inputIndex, inputVideoSink)
        return inputVideoSink
    }

    fun setOutputSurfaceInfo(outputSurface: Surface?, outputResolution: Size) {
        if (currentSurfaceAndSize != null && currentSurfaceAndSize!!.first == outputSurface
            && currentSurfaceAndSize!!.second.equals(outputResolution)
        ) {
            return
        }
        currentSurfaceAndSize = Pair.create<Surface?, Size?>(outputSurface, outputResolution)
        maybeSetOutputSurfaceInfo(
            outputSurface, outputResolution.width, outputResolution.height
        )
    }

    fun clearOutputSurfaceInfo() {
        maybeSetOutputSurfaceInfo( 
            null,  
            Size.UNKNOWN.width,  
            Size.UNKNOWN.height
        )
        currentSurfaceAndSize = null
    }

    fun startRendering() {
        defaultVideoSink.startRendering()
    }

    fun stopRendering() {
        defaultVideoSink.stopRendering()
    }

    fun setCompositionEffects(compositionEffects: List<Effect>) {
        this.compositionEffects = compositionEffects.toList()
        if (videoGraph != null) {
            videoGraph!!.setCompositionEffects(compositionEffects)
        }
    }

    fun setCompositorSettings(compositorSettings: VideoCompositorSettings) {
        this.compositorSettings = compositorSettings
        if (videoGraph != null) {
            videoGraph!!.setCompositorSettings(compositorSettings)
        }
    }

    fun setRequestOpenGlToneMapping(requestOpenGlToneMapping: Boolean) {
        this.requestOpenGlToneMapping = requestOpenGlToneMapping
    }

    fun setIsInputSdrToneMapped(isInputSdrToneMapped: Boolean) {
        this.isInputSdrToneMapped = isInputSdrToneMapped
    }

    fun release() {
        if (state == STATE_RELEASED) {
            return
        }
        if (handler != null) {
            handler!!.removeCallbacksAndMessages( null)
        }
        if (videoGraph != null) {
            videoGraph!!.release()
        }
        currentSurfaceAndSize = null
        state = STATE_RELEASED
    }

    // VideoGraph.Listener
    override fun onOutputSizeChanged(width: Int, height: Int) {
        // We forward output size changes to the sink even if we are still flushing.
        videoGraphOutputFormat =
            videoGraphOutputFormat.buildUpon().setWidth(width).setHeight(height).build()
        onOutputStreamChanged()
    }

    override fun onOutputFrameRateChanged(frameRate: Float) {
        videoGraphOutputFormat = videoGraphOutputFormat.buildUpon().setFrameRate(frameRate).build()
        onOutputStreamChanged()
    }

    override fun onOutputFrameAvailableForRendering(
        framePresentationTimeUs: Long, isRedrawnFrame: Boolean
    ) {
        if (pendingFlushCount > 0) {
            // Ignore available frames while flushing
            return
        }
        for (listener in listeners) {
            // Wake up the player when not playing to render the frame more promptly.
            listener.onFrameAvailableForRendering()
        }

        if (isRedrawnFrame) {
            // Redrawn frames are rendered directly in the processing pipeline.
            if (videoFrameMetadataListener != null) {
                videoFrameMetadataListener!!.onVideoFrameAboutToBeRendered( 
                    framePresentationTimeUs,  
                    C.TIME_UNSET,
                    videoGraphOutputFormat,  
                    null
                )
            }
            return
        }

        // The frame presentation time is relative to the start of the Composition and without the
        // renderer offset
        lastOutputFramePresentationTimeUs = framePresentationTimeUs
        val streamChangeInfo = pendingStreamChanges.pollFloor(framePresentationTimeUs)
        if (streamChangeInfo != null) {
            outputStreamStartPositionUs = streamChangeInfo.startPositionUs
            outputStreamFirstFrameReleaseInstruction = streamChangeInfo.firstFrameReleaseInstruction
            onOutputStreamChanged()
        }
        defaultVideoSink.handleInputFrame(framePresentationTimeUs, videoFrameHandler)
        val isLastFrame =
            finalFramePresentationTimeUs != C.TIME_UNSET
                    && framePresentationTimeUs >= finalFramePresentationTimeUs
        if (isLastFrame) {
            signalEndOfVideoGraphOutputStream()
        }
    }

    override fun onEnded(finalFramePresentationTimeUs: Long) {
        for (listener in listeners) {
            listener.onEnded(finalFramePresentationTimeUs)
        }
    }

    override fun onError(exception: VideoFrameProcessingException) {
        for (listener in listeners) {
            listener.onError(exception)
        }
    }

    // Internal methods
    @Throws(VideoSinkException::class)
    private fun registerInput(sourceFormat: Format, inputIndex: Int): Boolean {
        if (inputIndex == PRIMARY_SEQUENCE_INDEX) {
            Preconditions.checkState(state == STATE_CREATED)
            val inputColorInfo = getAdjustedInputColorInfo(sourceFormat.colorInfo)
            val outputColorInfo: ColorInfo?
            try {
                if (requestOpenGlToneMapping) {
                    outputColorInfo = ColorInfo.SDR_BT709_LIMITED
                } else if (inputColorInfo.colorTransfer == C.COLOR_TRANSFER_HLG && Build.VERSION.SDK_INT < 34 && GlUtil.isBt2020PqExtensionSupported) {
                    // PQ SurfaceView output is supported from API 33, but HLG output is supported from API
                    // 34. Therefore, convert HLG to PQ if PQ is supported, so that HLG input can be displayed
                    // properly on API 33.
                    outputColorInfo =
                        inputColorInfo.buildUpon().setColorTransfer(C.COLOR_TRANSFER_ST2084).build()
                    // Force OpenGL tone mapping if the GL extension required to output HDR colors is not
                    // available. OpenGL tone mapping is only supported on API 29+.
                } else if (!GlUtil.isColorTransferSupported(inputColorInfo.colorTransfer)
                    && Build.VERSION.SDK_INT >= 29
                ) {
                    w(
                        TAG,
                        formatInvariant(
                            "Color transfer %d is not supported. Falling back to OpenGl tone mapping.",
                            inputColorInfo.colorTransfer
                        )
                    )
                    outputColorInfo = ColorInfo.SDR_BT709_LIMITED
                } else if (inputColorInfo.colorTransfer == C.COLOR_TRANSFER_SRGB
                    || inputColorInfo.colorTransfer == C.COLOR_TRANSFER_GAMMA_2_2
                ) {
                    outputColorInfo = ColorInfo.SDR_BT709_LIMITED
                } else {
                    outputColorInfo = inputColorInfo
                }
            } catch (e: GlException) {
                throw VideoSinkException(e, sourceFormat)
            }
            handler = clock.createHandler(Looper.myLooper()!!,
                null
            )
            try {
                // TODO: b/412585856 - Allow setting CompositorSetting and CompositionEffects dynamically.
                videoGraph =
                    videoGraphFactory.create(
                        context,
                        outputColorInfo,
                        DebugViewProvider.NONE,  
                        this,  
                        Executor { runnable: Runnable? -> handler!!.post(runnable!!) },  
                        0,  
                        false
                    )
                videoGraph!!.setCompositionEffects(compositionEffects)
                videoGraph!!.setCompositorSettings(compositorSettings)
                videoGraph!!.initialize()
            } catch (e: VideoFrameProcessingException) {
                throw VideoSinkException(e, sourceFormat)
            }
            if (currentSurfaceAndSize != null) {
                val surface = currentSurfaceAndSize!!.first
                val size = currentSurfaceAndSize!!.second
                maybeSetOutputSurfaceInfo(surface, size.width, size.height)
            }
            defaultVideoSink.initialize(sourceFormat)
            defaultVideoSink.setListener(
                DefaultVideoSinkListener(),  
                Executor { runnable: Runnable? -> handler!!.post(runnable!!) })
            state = STATE_INITIALIZED
        } else {
            if (!this.isInitialized) {
                // Make sure the primary sequence is initialized first.
                return false
            }
        }

        try {
            videoGraph!!.registerInput(inputIndex)
        } catch (e: VideoFrameProcessingException) {
            throw VideoSinkException(e, sourceFormat)
        }
        registeredVideoInputCount++
        return true
    }

    private val isInitialized: Boolean
        get() = state == STATE_INITIALIZED

    private fun maybeSetOutputSurfaceInfo(surface: Surface?, width: Int, height: Int) {
        if (videoGraph == null) {
            return
        }
        // Update the surface on the video graph and the default video sink together.
        if (surface != null) {
            videoGraph!!.setOutputSurfaceInfo(SurfaceInfo(surface, width, height))
            defaultVideoSink.setOutputSurfaceInfo(surface, Size(width, height))
        } else {
            videoGraph!!.setOutputSurfaceInfo( null)
            defaultVideoSink.clearOutputSurfaceInfo()
        }
    }

    private fun isReady(otherwiseReady: Boolean): Boolean {
        return defaultVideoSink.isReady( otherwiseReady && pendingFlushCount == 0)
    }

    private fun signalEndOfVideoGraphOutputStream() {
        // TODO: b/257464707 - Support extensively modified media.
        defaultVideoSink.signalEndOfCurrentInputStream()
        hasSignaledEndOfVideoGraphOutputStream = true
    }

    private val isEnded: Boolean
        get() = pendingFlushCount == 0 && hasSignaledEndOfVideoGraphOutputStream
                && defaultVideoSink.isEnded

    @Throws(VideoSinkException::class)
    private fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        defaultVideoSink.render(positionUs, elapsedRealtimeUs)
    }

    private fun flush(resetPosition: Boolean) {
        if (!this.isInitialized) {
            return
        }
        pendingFlushCount++
        defaultVideoSink.flush(resetPosition)
        while (pendingStreamChanges.size() > 1) {
            pendingStreamChanges.pollFirst()
        }
        if (pendingStreamChanges.size() == 1) {
            // Use the latest stream change info if none is passed after flushing.
            val streamChangeInfo: StreamChangeInfo =pendingStreamChanges.pollFirst()!!
            outputStreamStartPositionUs = streamChangeInfo.startPositionUs
            outputStreamFirstFrameReleaseInstruction = streamChangeInfo.firstFrameReleaseInstruction
            onOutputStreamChanged()
        }
        lastOutputFramePresentationTimeUs = C.TIME_UNSET
        if (resetPosition) {
            // If not resetting position, preserve the EOS state, this is necessary in operations like
            // redraw which relies on flushing, but does not reset position.
            finalFramePresentationTimeUs = C.TIME_UNSET
            hasSignaledEndOfVideoGraphOutputStream = false
        }
        // Handle pending video graph callbacks to ensure video size changes reach the video render
        // control.
        handler!!.post(Runnable { pendingFlushCount-- })
    }

    private fun joinPlayback(renderNextFrameImmediately: Boolean) {
        defaultVideoSink.join(renderNextFrameImmediately)
    }

    private fun allowReleaseFirstFrameBeforeStarted() {
        defaultVideoSink.allowReleaseFirstFrameBeforeStarted()
    }

    private fun setVideoFrameMetadataListener(
        videoFrameMetadataListener: VideoFrameMetadataListener?
    ) {
        this.videoFrameMetadataListener = videoFrameMetadataListener
        defaultVideoSink.setVideoFrameMetadataListener(videoFrameMetadataListener)
    }

    private fun setPlaybackSpeed(speed: Float) {
        videoFrameReleaseEarlyTimeForecaster.setPlaybackSpeed(speed)
        defaultVideoSink.setPlaybackSpeed(speed)
    }

    private fun setChangeFrameRateStrategy(
        @C.VideoChangeFrameRateStrategy changeFrameRateStrategy: @C.VideoChangeFrameRateStrategy Int
    ) {
        defaultVideoSink.setChangeFrameRateStrategy(changeFrameRateStrategy)
    }

    private fun shouldRenderToInputVideoSink(): Boolean {
        return totalVideoInputCount != C.LENGTH_UNSET
                && totalVideoInputCount == registeredVideoInputCount
    }

    private fun getAdjustedInputColorInfo(inputColorInfo: ColorInfo?): ColorInfo {
        if (inputColorInfo == null || !inputColorInfo.isDataSpaceValid || isInputSdrToneMapped) {
            return ColorInfo.SDR_BT709_LIMITED
        }

        return inputColorInfo
    }

    private fun onOutputStreamChanged() {
        defaultVideoSink.onInputStreamChanged(
            VideoSink.INPUT_TYPE_SURFACE,
            videoGraphOutputFormat,
            outputStreamStartPositionUs,
            outputStreamFirstFrameReleaseInstruction,  
            listOf()
        )
    }

    private inner class InputVideoSink(context: Context, private val inputIndex: Int) : VideoSink,
        Listener {
        private val videoFrameProcessorMaxPendingFrameCount: Int

        private var videoEffects: List<Effect>
        private var inputFormat: Format? = null
        private var inputType: @VideoSink.InputType Int = 0
        private var inputBufferTimestampAdjustmentUs: Long = 0
        private var lastFramePresentationTimeUs: Long
        private var consecutiveDroppedFrames = 0

        private var listener: VideoSink.Listener
        private var listenerExecutor: Executor
        private var signaledEndOfStream = false
        override var isInitialized = false

        init {
            // TODO b/226330223 - Investigate increasing frame count when frame dropping is allowed.
            // TODO b/278234847 - Evaluate whether limiting frame count when frame dropping is not allowed
            //  reduces decoder timeouts, and consider restoring.
            videoFrameProcessorMaxPendingFrameCount =
                getMaxPendingFramesCountForMediaCodecDecoders(context)
            videoEffects = listOf()
            lastFramePresentationTimeUs = C.TIME_UNSET
            listener = VideoSink.Listener.NO_OP
            listenerExecutor = NO_OP_EXECUTOR
        }

        override fun startRendering() {
            if (enablePlaylistMode) {
                this@PlaybackVideoGraphWrapper.startRendering()
            }
        }

        override fun stopRendering() {
            if (enablePlaylistMode) {
                this@PlaybackVideoGraphWrapper.stopRendering()
            }
        }

        override fun setListener(listener: VideoSink.Listener, executor: Executor) {
            this.listener = listener
            listenerExecutor = executor
        }

        @Throws(VideoSinkException::class)
        override fun initialize(sourceFormat: Format): Boolean {
            Preconditions.checkState(!isInitialized)
            isInitialized = this@PlaybackVideoGraphWrapper.registerInput(sourceFormat, inputIndex)
            return isInitialized
        }

        override fun redraw() {
            if (!isInitialized) {
                return
            }
            // Resignal EOS only for the last item.
            val needsResignalEndOfCurrentInputStream = signaledEndOfStream
            val replayedPresentationTimeUs = lastOutputFramePresentationTimeUs
            this@PlaybackVideoGraphWrapper.flush( false)
            videoGraph!!.redraw()
            lastOutputFramePresentationTimeUs = replayedPresentationTimeUs
            if (needsResignalEndOfCurrentInputStream) {
                signalEndOfCurrentInputStream()
            }
        }

        override fun flush(resetPosition: Boolean) {
            if (isInitialized) {
                videoGraph!!.flush()
            }
            lastFramePresentationTimeUs = C.TIME_UNSET
            this@PlaybackVideoGraphWrapper.flush(resetPosition)
            signaledEndOfStream = false
            // Don't change input stream start position or reset the pending input stream timestamp info
            // change so that it's announced with the next input frame.
            // Don't reset isInputStreamChangePending because it's not guaranteed to receive a new input
            // stream after seeking.
        }

        override fun isReady(otherwiseReady: Boolean): Boolean {
            return this@PlaybackVideoGraphWrapper.isReady( 
                otherwiseReady && isInitialized
            )
        }

        override fun signalEndOfCurrentInputStream() {
            finalFramePresentationTimeUs = lastFramePresentationTimeUs
            if (lastOutputFramePresentationTimeUs >= finalFramePresentationTimeUs) {
                this@PlaybackVideoGraphWrapper.signalEndOfVideoGraphOutputStream()
            }
        }

        override fun signalEndOfInput() {
            if (signaledEndOfStream) {
                return
            }
            if (isInitialized) {
                videoGraph!!.signalEndOfInput(inputIndex)
                signaledEndOfStream = true
            }
        }

        override val isEnded: Boolean
            get() =  isInitialized && this@PlaybackVideoGraphWrapper.isEnded

        override fun onInputStreamChanged(
            inputType: @VideoSink.InputType Int,
            format: Format,
            startPositionUs: Long,
            firstFrameReleaseInstruction: @FirstFrameReleaseInstruction Int,
            videoEffects: List<Effect>
        ) {
            Preconditions.checkState(isInitialized)
            this.videoEffects = videoEffects.toList()
            this.inputType = inputType
            this.inputFormat = format
            finalFramePresentationTimeUs = C.TIME_UNSET
            hasSignaledEndOfVideoGraphOutputStream = false
            registerInputStream(format)
            val isFirstStream = lastFramePresentationTimeUs == C.TIME_UNSET
            if (enablePlaylistMode || (inputIndex == PRIMARY_SEQUENCE_INDEX && isFirstStream)) {
                val fromTimestampUs: Long
                if (isFirstStream) {
                    // Add a stream change info to the queue with a large negative timestamp to always apply
                    // it as long as it is the only one in the queue.
                    fromTimestampUs = Long.MIN_VALUE / 2
                } else {
                    fromTimestampUs = lastFramePresentationTimeUs + 1
                }
                pendingStreamChanges.add(
                    fromTimestampUs,
                    StreamChangeInfo( 
                        startPositionUs + inputBufferTimestampAdjustmentUs,
                        firstFrameReleaseInstruction,
                        fromTimestampUs
                    )
                )
            }
        }

        override fun allowReleaseFirstFrameBeforeStarted() {
            // We know that this sink is connected to renderers. Each renderer will first queue a stream
            // change that has firstFrameReleaseInstruction set to either RELEASE_FIRST_FRAME_IMMEDIATELY
            // or RELEASE_FIRST_FRAME_WHEN_STARTED, and then queue stream changes that have
            // firstFrameReleaseInstruction set to RELEASE_FIRST_FRAME_WHEN_PREVIOUS_STREAM_PROCESSED.
            // When a renderer queues the first stream change, all previous streams should have been fully
            // processed.
            // We want to release the first frame immediately if the firstFrameReleaseInstruction of the
            // first stream change queued by the current renderer was RELEASE_FIRST_FRAME_WHEN_STARTED and
            // the first frame hasn't been released yet.
            if (pendingStreamChanges.size() == 0) {
                // All the stream changes have already been processed by the VideoGraph. Delegate to the
                // downstream component.
                this@PlaybackVideoGraphWrapper.allowReleaseFirstFrameBeforeStarted()
                return
            }
            val newPendingStreamChanges: TimedValueQueue<StreamChangeInfo?> =
                TimedValueQueue<StreamChangeInfo?>()
            var isFirstStreamChange = true
            while (pendingStreamChanges.size() > 0) {
                var streamChangeInfo: StreamChangeInfo =pendingStreamChanges.pollFirst()!!
                if (isFirstStreamChange) {
                    if (streamChangeInfo.firstFrameReleaseInstruction == VideoSink.RELEASE_FIRST_FRAME_IMMEDIATELY
                        || (streamChangeInfo.firstFrameReleaseInstruction
                                == VideoSink.RELEASE_FIRST_FRAME_WHEN_STARTED)
                    ) {
                        // The first stream change hasn't been processed by the VideoGraph yet.
                        streamChangeInfo =
                            StreamChangeInfo(
                                streamChangeInfo.startPositionUs,
                                VideoSink.RELEASE_FIRST_FRAME_IMMEDIATELY,
                                streamChangeInfo.fromTimestampUs
                            )
                    } else {
                        // The first stream change has already been processed by the VideoGraph. Delegate to the
                        // downstream component.
                        this@PlaybackVideoGraphWrapper.allowReleaseFirstFrameBeforeStarted()
                    }
                    isFirstStreamChange = false
                }
                newPendingStreamChanges.add(streamChangeInfo.fromTimestampUs, streamChangeInfo)
            }
            pendingStreamChanges = newPendingStreamChanges
        }

        override val inputSurface: Surface
            get() {
                Preconditions.checkState(isInitialized)
                return videoGraph!!.getInputSurface(inputIndex)
            }

        override fun setVideoFrameMetadataListener(
            videoFrameMetadataListener: VideoFrameMetadataListener?
        ) {
            if (inputIndex == PRIMARY_SEQUENCE_INDEX) {
                this@PlaybackVideoGraphWrapper.setVideoFrameMetadataListener(
                    videoFrameMetadataListener
                )
            }
        }

        override fun setPlaybackSpeed(@FloatRange(from = 0.0, fromInclusive = false) speed: Float) {
            if (inputIndex == PRIMARY_SEQUENCE_INDEX) {
                this@PlaybackVideoGraphWrapper.setPlaybackSpeed(speed)
            }
        }

        override fun setVideoEffects(videoEffects: List<Effect>) {
            if (this.videoEffects == videoEffects) {
                return
            }
            this.videoEffects = videoEffects.toList()
            if (inputFormat != null) {
                registerInputStream(inputFormat!!)
            }
        }

        override fun setBufferTimestampAdjustmentUs(bufferTimestampAdjustmentUs: Long) {
            inputBufferTimestampAdjustmentUs = bufferTimestampAdjustmentUs
        }

        override fun setOutputSurfaceInfo(outputSurface: Surface, outputResolution: Size) {
            this@PlaybackVideoGraphWrapper.setOutputSurfaceInfo(outputSurface, outputResolution)
        }

        override fun clearOutputSurfaceInfo() {
            this@PlaybackVideoGraphWrapper.clearOutputSurfaceInfo()
        }

        override fun setChangeFrameRateStrategy(
            @C.VideoChangeFrameRateStrategy changeFrameRateStrategy: @C.VideoChangeFrameRateStrategy Int
        ) {
            if (inputIndex == PRIMARY_SEQUENCE_INDEX) {
                this@PlaybackVideoGraphWrapper.setChangeFrameRateStrategy(changeFrameRateStrategy)
            }
        }

        override fun handleInputFrame(
            bufferPresentationTimeUs: Long, videoFrameHandler: VideoFrameHandler
        ): Boolean {
            Preconditions.checkState(isInitialized)

            val framePresentationTimeUs =
                bufferPresentationTimeUs + inputBufferTimestampAdjustmentUs
            val predictedEarlyUs =
                videoFrameReleaseEarlyTimeForecaster.predictEarlyUs(framePresentationTimeUs)
            if (predictedEarlyUs != C.TIME_UNSET && earlyThresholdToDropInputUs != C.TIME_UNSET && predictedEarlyUs < earlyThresholdToDropInputUs && consecutiveDroppedFrames < MAX_CONSECUTIVE_FRAMES_TO_DROP) {
                consecutiveDroppedFrames += 1
                videoFrameHandler.skip()
                return true
            }
            if (!shouldRenderToInputVideoSink()) {
                return false
            }
            if (videoGraph!!.getPendingInputFrameCount(inputIndex) >= videoFrameProcessorMaxPendingFrameCount
            ) {
                return false
            }
            if (!videoGraph!!.registerInputFrame(inputIndex)
            ) {
                return false
            }

            // The sink takes in frames with monotonically increasing, non-offset frame
            // timestamps. That is, with two 10s long videos, the first frame of the second video should
            // bear a timestamp of 10s seen from VideoFrameProcessor; while in ExoPlayer, the timestamp of
            // the said frame would be 0s, but the streamOffset is incremented by 10s to include the
            // duration of the first video. Thus this correction is needed to account for the different
            // handling of presentation timestamps in ExoPlayer and VideoFrameProcessor.
            //
            // inputBufferTimestampAdjustmentUs adjusts the buffer timestamp (that corresponds to the
            // player position) to the frame presentation time (which is relative to the start of a
            // composition).
            lastFramePresentationTimeUs = framePresentationTimeUs
            // Use the frame presentation time as render time so that the SurfaceTexture is accompanied
            // by this timestamp. Setting a realtime based release time is only relevant when rendering to
            // a SurfaceView, but we render to a surface in this case.
            videoFrameHandler.render( framePresentationTimeUs * 1000)
            consecutiveDroppedFrames = 0
            return true
        }

        override fun handleInputBitmap(
            inputBitmap: Bitmap, bufferTimestampIterator: TimestampIterator
        ): Boolean {
            Preconditions.checkState(isInitialized)
            if (!shouldRenderToInputVideoSink()) {
                return false
            }
            val frameTimestampIterator: TimestampIterator =
                ShiftingTimestampIterator(bufferTimestampIterator, inputBufferTimestampAdjustmentUs)
            if (!videoGraph!!.queueInputBitmap(inputIndex, inputBitmap, frameTimestampIterator)
            ) {
                return false
            }

            val lastFramePresentationTimeUs = frameTimestampIterator.lastTimestampUs
            Preconditions.checkState(lastFramePresentationTimeUs != C.TIME_UNSET)
            this.lastFramePresentationTimeUs = lastFramePresentationTimeUs
            return true
        }

        @Throws(VideoSinkException::class)
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
            this@PlaybackVideoGraphWrapper.render( 
                positionUs + inputBufferTimestampAdjustmentUs, elapsedRealtimeUs
            )
        }

        override fun join(renderNextFrameImmediately: Boolean) {
            if (enablePlaylistMode) {
                this@PlaybackVideoGraphWrapper.joinPlayback(renderNextFrameImmediately)
            }
        }

        override fun release() {
            this@PlaybackVideoGraphWrapper.release()
        }

        // PlaybackVideoGraphWrapper.Listener implementation
        override fun onFrameAvailableForRendering() {
            val currentListener = listener
            listenerExecutor.execute(Runnable { currentListener.onFrameAvailableForRendering() })
        }

        override fun onFirstFrameRendered() {
            val currentListener = listener
            listenerExecutor.execute(Runnable { currentListener.onFirstFrameRendered() })
        }

        override fun onFrameDropped() {
            val currentListener = listener
            listenerExecutor.execute(Runnable { currentListener.onFrameDropped() })
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            val currentListener = listener
            listenerExecutor.execute(Runnable { currentListener.onVideoSizeChanged(videoSize) })
        }

        override fun onError(videoFrameProcessingException: VideoFrameProcessingException) {
            val currentListener = listener
            listenerExecutor.execute(
                Runnable {
                    currentListener.onError(
                        VideoSinkException(
                            videoFrameProcessingException,
                            this.inputFormat!!
                        )
                    )
                })
        }

        // Private methods
        fun registerInputStream(inputFormat: Format) {
            val adjustedInputFormat =
                inputFormat
                    .buildUpon()
                    .setColorInfo(getAdjustedInputColorInfo(inputFormat.colorInfo))
                    .build()
            val videoGraphInputType: @VideoFrameProcessor.InputType Int =
                if (inputType == VideoSink.INPUT_TYPE_SURFACE)
                    VideoFrameProcessor.INPUT_TYPE_SURFACE
                else
                    VideoFrameProcessor.INPUT_TYPE_BITMAP
            videoGraph!!.registerInputStream(
                    inputIndex,
                    videoGraphInputType,
                    adjustedInputFormat,
                    videoEffects,  
                    0
                )
        }

    }

    private inner class DefaultVideoSinkListener : VideoSink.Listener {
        override fun onFirstFrameRendered() {
            for (listener in listeners) {
                listener.onFirstFrameRendered()
            }
        }

        override fun onFrameDropped() {
            for (listener in listeners) {
                listener.onFrameDropped()
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            for (listener in listeners) {
                listener.onVideoSizeChanged(videoSize)
            }
        }

        override fun onError(videoSinkException: VideoSinkException) {
            for (listener in listeners) {
                listener.onError(VideoFrameProcessingException.from(videoSinkException))
            }
        }
    }

    private class StreamChangeInfo(
        val startPositionUs: Long,
        val firstFrameReleaseInstruction: @FirstFrameReleaseInstruction Int,
        val fromTimestampUs: Long
    )

    private class ShiftingTimestampIterator(
        private val timestampIterator: TimestampIterator,
        private val shift: Long
    ) : TimestampIterator {
        override fun hasNext(): Boolean {
            return timestampIterator.hasNext()
        }

        override fun next(): Long {
            return timestampIterator.next() + shift
        }

        override fun copyOf(): TimestampIterator {
            return ShiftingTimestampIterator(timestampIterator.copyOf(), shift)
        }

        override val lastTimestampUs: Long
            get() {
                val unshiftedLastTimestampUs = timestampIterator.lastTimestampUs
                return if (unshiftedLastTimestampUs == C.TIME_UNSET)
                    C.TIME_UNSET
                else
                    unshiftedLastTimestampUs + shift
            }

    }

    private class ReflectiveSingleInputVideoGraphFactory(enableReplayableCache: Boolean) :
        VideoGraph.Factory {
        private val videoFrameProcessorFactory: VideoFrameProcessor.Factory

        init {
            this.videoFrameProcessorFactory =
                ReflectiveDefaultVideoFrameProcessorFactory(enableReplayableCache)
        }

        override fun create(
            context: Context,
            outputColorInfo: ColorInfo,
            debugViewProvider: DebugViewProvider,
            listener: VideoGraph.Listener,
            listenerExecutor: Executor,
            initialTimestampOffsetUs: Long,
            renderFramesAutomatically: Boolean
        ): VideoGraph {
            val factory: VideoGraph.Factory?
            try {
                // LINT.IfChange
                // b/463697143: Obfuscate class name to bypass R8/AppReduce static analysis.
                val className =
                    StringBuilder()
                        .append("androidx.media3.effect.")
                        .append("SingleInputVideoGraph")
                        .append("\$Factory")
                        .toString()
                val singleInputVideoGraphFactoryClass = Class.forName(className)
                factory =
                    singleInputVideoGraphFactoryClass
                        .getConstructor(VideoFrameProcessor.Factory::class.java)
                        .newInstance(videoFrameProcessorFactory) as VideoGraph.Factory
                // LINT.ThenChange(
                //     ../../../../../../../proguard-rules.txt,
                //     ../ExoPlayerImpl.java:set_video_effects)
            } catch (e: Exception) {
                throw IllegalStateException(e)
            }
            return factory.create(
                context,
                outputColorInfo,
                debugViewProvider,
                listener,
                listenerExecutor,
                initialTimestampOffsetUs,
                renderFramesAutomatically
            )
        }

        override fun supportsMultipleInputs(): Boolean {
            return false
        }
    }

    private class ReflectiveDefaultVideoFrameProcessorFactory
        (private val enableReplayableCache: Boolean) : VideoFrameProcessor.Factory {
        @Throws(VideoFrameProcessingException::class)
        override fun create(
            context: Context,
            debugViewProvider: DebugViewProvider,
            outputColorInfo: ColorInfo,
            renderFramesAutomatically: Boolean,
            listenerExecutor: Executor,
            listener: VideoFrameProcessor.Listener
        ): VideoFrameProcessor {
            try {
                // LINT.IfChange
                val defaultVideoFrameProcessorFactoryBuilderClass: Class<*> =
                    DEFAULT_VIDEO_FRAME_PROCESSOR_FACTORY_BUILDER_CLASS.get()!!
                val builder: Any =
                    defaultVideoFrameProcessorFactoryBuilderClass.getConstructor().newInstance()
                val setUseReplayableCacheMethod =
                    defaultVideoFrameProcessorFactoryBuilderClass.getMethod(
                        "setEnableReplayableCache", Boolean::class.javaPrimitiveType
                    )
                setUseReplayableCacheMethod.invoke(builder, enableReplayableCache)
                val factory =
                    defaultVideoFrameProcessorFactoryBuilderClass
                        .getMethod("build")
                        .invoke(builder)!! as VideoFrameProcessor.Factory
                // LINT.ThenChange(../../../../../../../../effect/src/main/java/androidx/media3/effect/DefaultVideoFrameProcessor.java)
                return factory.create(
                    context,
                    debugViewProvider,
                    outputColorInfo,
                    renderFramesAutomatically,
                    listenerExecutor,
                    listener
                )
            } catch (e: Exception) {
                throw VideoFrameProcessingException(e)
            }
        }

        companion object {
            private val DEFAULT_VIDEO_FRAME_PROCESSOR_FACTORY_BUILDER_CLASS: Supplier<Class<*>?> =
                Suppliers.memoize<Class<*>?>(
                    Supplier {
                        try {
                            // LINT.IfChange
                            // b/463697143: Obfuscate class name to bypass R8/AppReduce static analysis.
                            val className =
                                StringBuilder()
                                    .append("androidx.media3.effect.")
                                    .append("DefaultVideoFrameProcessor")
                                    .append("\$Factory\$Builder")
                                    .toString()
                            return@Supplier Class.forName(className)
                            // LINT.ThenChange(../../../../../../../proguard-rules.txt)
                        } catch (e: Exception) {
                            throw IllegalStateException(e)
                        }
                    })
        }
    }

    companion object {
        const val LATE_US_TO_DROP_INPUT_FRAME: Long = 15000
        private const val TAG = "PlaybackVidGraphWrapper"
        private const val STATE_CREATED = 0
        private const val STATE_INITIALIZED = 1
        private const val STATE_RELEASED = 2

        private const val PRIMARY_SEQUENCE_INDEX = 0

        private val NO_OP_EXECUTOR = Executor { runnable: Runnable? -> }
        private const val MAX_CONSECUTIVE_FRAMES_TO_DROP = 2
    }
}
