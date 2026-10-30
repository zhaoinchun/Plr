package androidx.media3.exoplayer.video

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.annotation.CallSuper
import androidx.annotation.IntDef
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.common.util.Log.e
import androidx.media3.common.util.TimedValueQueue
import androidx.media3.common.util.TraceUtil.beginSection
import androidx.media3.common.util.TraceUtil.endSection
import androidx.media3.common.util.Util.msToUs
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.Decoder
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.VideoDecoderOutputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.Renderer.WakeupListener
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.SampleStream.ReadDataResult
import com.google.common.base.Preconditions
import java.util.ArrayDeque
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.min

abstract class DecoderVideoRenderer protected constructor(
    private val allowedJoiningTimeMs: Long,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    private val maxDroppedFramesToNotify: Int
) : BaseRenderer(C.TRACK_TYPE_VIDEO), Decoder.Callback {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [REINITIALIZATION_STATE_NONE, REINITIALIZATION_STATE_SIGNAL_END_OF_STREAM, REINITIALIZATION_STATE_WAIT_END_OF_STREAM]
    )
    private annotation class ReinitializationState

    private val eventDispatcher: VideoRendererEventListener.EventDispatcher =
        VideoRendererEventListener.EventDispatcher(eventHandler, eventListener)
    private val formatQueue: TimedValueQueue<Format> = TimedValueQueue()
    private val pendingOutputStreamChanges: ArrayDeque<OutputStreamInfo> =
        ArrayDeque<OutputStreamInfo>()
    private val flagsOnlyBuffer: DecoderInputBuffer = DecoderInputBuffer.newNoDataInstance()

    private var inputFormat: Format? = null
    private var outputFormat: Format? = null
    private var outputStreamInfo: OutputStreamInfo? = null

    private var decoder: Decoder<DecoderInputBuffer, out VideoDecoderOutputBuffer, out DecoderException>? =
        null

    private var inputBuffer: DecoderInputBuffer? = null
    private var largestQueuedPresentationTimeUs: Long = C.TIME_UNSET
    private var outputBuffer: VideoDecoderOutputBuffer? = null
    private var outputMode: @C.VideoOutputMode Int = C.VIDEO_OUTPUT_MODE_NONE
    private var output: Any? = null
    private var outputSurface: Surface? = null
    private var outputBufferRenderer: VideoDecoderOutputBufferRenderer? = null
    private var frameMetadataListener: VideoFrameMetadataListener? = null

    private var decoderDrmSession: DrmSession? = null
    private var sourceDrmSession: DrmSession? = null

    private var decoderReinitializationState: @ReinitializationState Int =
        REINITIALIZATION_STATE_NONE
    private var decoderReceivedBuffers = false

    private var firstFrameState: @C.FirstFrameState Int =
        C.FIRST_FRAME_NOT_RENDERED_ONLY_ALLOWED_IF_STARTED
    private var initialPositionUs: Long = 0
    private var joiningDeadlineMs: Long = C.TIME_UNSET
    private var waitingForFirstSampleInFormat = false

    private var inputStreamEnded = false
    private var outputStreamEnded = false
    private var reportedVideoSize: VideoSize? = null

    private var droppedFrameAccumulationStartTimeMs: Long = 0
    private var droppedFrames = 0
    private var consecutiveDroppedFrameCount = 0
    private var buffersInCodecCount = 0
    private var lastRenderTimeUs: Long = 0
    protected var decoderCounters: DecoderCounters = DecoderCounters()
    private var wakeupListener: WakeupListener? = null
    private var playbackThreadExecutor: Executor? = null

    @Throws(ExoPlaybackException::class)
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        if (outputStreamEnded) {
            return
        }

        if (inputFormat == null) {
            // We don't have a format yet, so try and read one.
            val formatHolder = getFormatHolder()
            flagsOnlyBuffer.clear()
            val result: @ReadDataResult Int =
                readSource(formatHolder, flagsOnlyBuffer, SampleStream.FLAG_REQUIRE_FORMAT)
            if (result == C.RESULT_FORMAT_READ) {
                onInputFormatChanged(formatHolder)
            } else if (result == C.RESULT_BUFFER_READ) {
                // End of stream read having not read a format.
                Preconditions.checkState(flagsOnlyBuffer.isEndOfStream)
                inputStreamEnded = true
                outputStreamEnded = true
                return
            } else {
                // We still don't have a format and can't make progress without one.
                return
            }
        }

        // If we don't have a decoder yet, we need to instantiate one.
        maybeInitDecoder()

        if (decoder != null) {
            try {
                // Rendering loop.
                beginSection("drainAndFeed")
                while (drainOutputBuffer(positionUs, elapsedRealtimeUs)) {
                }
                while (feedInputBuffer()) {
                }
                endSection()
            } catch (e: DecoderException) {
                e(TAG, "Video codec error", e)
                eventDispatcher.videoCodecError(e)
                throw createRendererException(
                    e,
                    inputFormat,
                    PlaybackException.ERROR_CODE_DECODING_FAILED
                )
            }
            decoderCounters.ensureUpdated()
        }
    }

    override val isEnded: Boolean
        get() = outputStreamEnded

    override val isReady: Boolean
        get() {
            if (inputFormat != null && (isSourceReady || outputBuffer != null)
                && (firstFrameState == C.FIRST_FRAME_RENDERED || !hasOutput())
            ) {
                // Ready. If we were joining then we've now joined, so clear the joining deadline.
                joiningDeadlineMs = C.TIME_UNSET
                return true
            } else if (joiningDeadlineMs == C.TIME_UNSET) {
                // Not joining.
                return false
            } else if (SystemClock.elapsedRealtime() < joiningDeadlineMs) {
                // Joining and still within the joining deadline.
                return true
            } else {
                // The joining deadline has been exceeded. Give up and clear the deadline.
                joiningDeadlineMs = C.TIME_UNSET
                return false
            }
        }

    // PlayerMessage.Target implementation.
    @Throws(ExoPlaybackException::class)
    override fun handleMessage(messageType: @Renderer.MessageType Int, message: Any?) {
        when (messageType) {
            Renderer.MSG_SET_VIDEO_OUTPUT -> {
                setOutput(message)
            }

            Renderer.MSG_SET_VIDEO_FRAME_METADATA_LISTENER -> {
                frameMetadataListener = message as VideoFrameMetadataListener?
            }

            Renderer.MSG_SET_WAKEUP_LISTENER -> {
                wakeupListener = message as WakeupListener?
            }

            else -> {
                super.handleMessage(messageType, message)
            }
        }
    }

    override fun onInputBufferAvailable() {
        wakeupListener?.onWakeup()
    }

    override fun onOutputBufferAvailable() {
        wakeupListener?.onWakeup()
    }

    // Protected methods.
    @Throws(ExoPlaybackException::class)
    override fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) {
        decoderCounters = DecoderCounters()
        eventDispatcher.enabled(decoderCounters)
        firstFrameState =
            if (mayRenderStartOfStream)
                C.FIRST_FRAME_NOT_RENDERED
            else
                C.FIRST_FRAME_NOT_RENDERED_ONLY_ALLOWED_IF_STARTED
    }

    override fun enableMayRenderStartOfStream() {
        if (firstFrameState == C.FIRST_FRAME_NOT_RENDERED_ONLY_ALLOWED_IF_STARTED) {
            firstFrameState = C.FIRST_FRAME_NOT_RENDERED
        }
    }

    @Throws(ExoPlaybackException::class)
    override fun onPositionReset(
        positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean
    ) {
        // TODO(b/440006632): Implement decode-only frame drop logic to allow skipping keyframe reset.
        inputStreamEnded = false
        outputStreamEnded = false
        if (!pendingOutputStreamChanges.isEmpty()) {
            // Update current outputStreamInfo to represent current stream provided by sample queue.
            outputStreamInfo = pendingOutputStreamChanges.getLast()
        }
        pendingOutputStreamChanges.clear()
        largestQueuedPresentationTimeUs = C.TIME_UNSET
        lowerFirstFrameState(C.FIRST_FRAME_NOT_RENDERED)
        initialPositionUs = C.TIME_UNSET
        consecutiveDroppedFrameCount = 0
        if (decoder != null) {
            flushDecoder()
        }
        if (joining) {
            setJoiningDeadlineMs()
        } else {
            joiningDeadlineMs = C.TIME_UNSET
        }
        // If there is a format change on the input side still pending propagation to the output, we
        // need to queue a format next time a buffer is read. This is because we may not read a new
        // input format after the position reset.
        if (formatQueue.size() > 0) {
            waitingForFirstSampleInFormat = true
        }
        formatQueue.clear()
    }

    override fun onStarted() {
        droppedFrames = 0
        droppedFrameAccumulationStartTimeMs = SystemClock.elapsedRealtime()
        lastRenderTimeUs = msToUs(SystemClock.elapsedRealtime())
    }

    override fun onStopped() {
        joiningDeadlineMs = C.TIME_UNSET
        maybeNotifyDroppedFrames()
    }

    override fun onDisabled() {
        inputFormat = null
        outputStreamInfo = null
        pendingOutputStreamChanges.clear()
        largestQueuedPresentationTimeUs = C.TIME_UNSET
        reportedVideoSize = null
        lowerFirstFrameState(C.FIRST_FRAME_NOT_RENDERED_ONLY_ALLOWED_IF_STARTED)
        try {
            setSourceDrmSession(null)
            releaseDecoder()
        } finally {
            eventDispatcher.disabled(decoderCounters)
        }
    }

    @Throws(ExoPlaybackException::class)
    override fun onStreamChanged(
        formats: Array<Format>,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaPeriodId
    ) {
        // TODO: This code should make sure to render the first frame of the next stream if the playback
        //  position reached the new stream.
        super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
        val streamFlags: @SampleStream.Flags Int = stream!!.flags
        if (outputStreamInfo == null
            || (pendingOutputStreamChanges.isEmpty() && buffersInCodecCount == 0)
        ) {
            outputStreamInfo =
                OutputStreamInfo( 
                    C.TIME_UNSET,
                    offsetUs,  
                    streamEndPositionUs,
                    streamFlags
                )
        } else {
            pendingOutputStreamChanges.add(
                OutputStreamInfo( 
                    largestQueuedPresentationTimeUs,
                    offsetUs,  
                    streamEndPositionUs,
                    streamFlags
                )
            )
        }
    }

    override fun onTimelineChanged(timeline: Timeline) {
        val lastOutputStreamInfo =
            this.lastOutputStreamInfo
        if (lastOutputStreamInfo != null) {
            lastOutputStreamInfo.durationUs = streamEndPositionUs
        }
    }

    @CallSuper
    @Throws(ExoPlaybackException::class)
    protected fun flushDecoder() {
        buffersInCodecCount = 0
        if (decoderReinitializationState != REINITIALIZATION_STATE_NONE) {
            releaseDecoder()
            maybeInitDecoder()
        } else {
            inputBuffer = null
            if (outputBuffer != null) {
                outputBuffer!!.release()
                outputBuffer = null
            }
            val decoder: Decoder<*, *, *> = decoder!!
            decoder.flush()
            decoder.setOutputStartTimeUs(lastResetPositionUs)
            decoderReceivedBuffers = false
        }
    }

    @CallSuper
    protected fun releaseDecoder() {
        inputBuffer = null
        outputBuffer = null
        decoderReinitializationState = REINITIALIZATION_STATE_NONE
        decoderReceivedBuffers = false
        buffersInCodecCount = 0
        if (decoder != null) {
            decoderCounters.decoderReleaseCount++
            decoder!!.release()
            eventDispatcher.decoderReleased(decoder!!.name)
            decoder = null
        }
        setDecoderDrmSession(null)
    }

    @CallSuper
    @Throws(ExoPlaybackException::class)
    protected fun onInputFormatChanged(formatHolder: FormatHolder) {
        waitingForFirstSampleInFormat = true
        val newFormat = formatHolder.format!!
        setSourceDrmSession(formatHolder.drmSession)
        val oldFormat = inputFormat
        inputFormat = newFormat

        if (decoder == null) {
            maybeInitDecoder()
            eventDispatcher.inputFormatChanged(
                inputFormat!!,  null
            )
            return
        }

        val evaluation: DecoderReuseEvaluation
        if (sourceDrmSession !== decoderDrmSession) {
            evaluation =
                DecoderReuseEvaluation(
                    decoder!!.name,
                    oldFormat!!,
                    newFormat,
                    DecoderReuseEvaluation.REUSE_RESULT_NO,
                    DecoderReuseEvaluation.DISCARD_REASON_DRM_SESSION_CHANGED
                )
        } else {
            evaluation = canReuseDecoder(
                decoder!!.name,
                oldFormat!!,
                newFormat
            )
        }

        if (evaluation.result == DecoderReuseEvaluation.REUSE_RESULT_NO) {
            if (decoderReceivedBuffers) {
                // Signal end of stream and wait for any final output buffers before re-initialization.
                decoderReinitializationState = REINITIALIZATION_STATE_SIGNAL_END_OF_STREAM
            } else {
                // There aren't any final output buffers, so release the decoder immediately.
                releaseDecoder()
                maybeInitDecoder()
            }
        }
        eventDispatcher.inputFormatChanged(
            inputFormat!!,
            evaluation
        )
    }

    protected fun onQueueInputBuffer(buffer: DecoderInputBuffer) {
        // Do nothing.
    }

    @CallSuper
    protected fun onProcessedOutputBuffer(presentationTimeUs: Long) {
        buffersInCodecCount--
        while (!pendingOutputStreamChanges.isEmpty()
            && presentationTimeUs >= pendingOutputStreamChanges.peek()!!.previousStreamLastBufferTimeUs
        ) {
            outputStreamInfo = pendingOutputStreamChanges.poll()!!
        }
    }

    protected fun shouldDropOutputBuffer(earlyUs: Long, elapsedRealtimeUs: Long): Boolean {
        return isBufferLate(earlyUs)
    }

    protected fun shouldDropBuffersToKeyframe(earlyUs: Long, elapsedRealtimeUs: Long): Boolean {
        return isBufferVeryLate(earlyUs)
    }

    protected fun shouldForceRenderOutputBuffer(
        earlyUs: Long,
        elapsedSinceLastRenderUs: Long
    ): Boolean {
        return isBufferLate(earlyUs) && elapsedSinceLastRenderUs > 100000
    }

    protected fun skipOutputBuffer(outputBuffer: VideoDecoderOutputBuffer) {
        decoderCounters.skippedOutputBufferCount++
        outputBuffer.release()
    }

    protected fun dropOutputBuffer(outputBuffer: VideoDecoderOutputBuffer) {
        updateDroppedBufferCounters( 
            0,  1
        )
        outputBuffer.release()
    }

    @Throws(ExoPlaybackException::class)
    protected fun maybeDropBuffersToKeyframe(positionUs: Long): Boolean {
        val droppedSourceBufferCount = skipSource(positionUs)
        if (droppedSourceBufferCount == 0) {
            return false
        }
        decoderCounters.droppedToKeyframeCount++
        // We dropped some buffers to catch up, so update the decoder counters and flush the decoder,
        // which releases all pending buffers buffers including the current output buffer.
        updateDroppedBufferCounters(
            droppedSourceBufferCount,  buffersInCodecCount
        )
        flushDecoder()
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

    @Throws(DecoderException::class)
    protected abstract fun createDecoder(
        format: Format,
        cryptoConfig: CryptoConfig?
    ): Decoder<DecoderInputBuffer, out VideoDecoderOutputBuffer, out DecoderException>

    @Throws(DecoderException::class)
    protected fun renderOutputBuffer(
        outputBuffer: VideoDecoderOutputBuffer, presentationTimeUs: Long, outputFormat: Format
    ) {
        if (frameMetadataListener != null) {
            frameMetadataListener!!.onVideoFrameAboutToBeRendered(
                presentationTimeUs, getClock().nanoTime(), outputFormat,  null
            )
        }
        lastRenderTimeUs = msToUs(SystemClock.elapsedRealtime())
        val bufferMode = outputBuffer.mode
        val renderSurface = bufferMode == C.VIDEO_OUTPUT_MODE_SURFACE_YUV && outputSurface != null
        val renderYuv = bufferMode == C.VIDEO_OUTPUT_MODE_YUV && outputBufferRenderer != null
        if (!renderYuv && !renderSurface) {
            dropOutputBuffer(outputBuffer)
        } else {
            maybeNotifyVideoSizeChanged(outputBuffer.width, outputBuffer.height)
            if (renderYuv) {
                outputBufferRenderer!!.setOutputBuffer(outputBuffer)
            } else {
                renderOutputBufferToSurface(
                    outputBuffer,
                    outputSurface!!
                )
            }
            consecutiveDroppedFrameCount = 0
            decoderCounters.renderedOutputBufferCount++
            maybeNotifyRenderedFirstFrame()
        }
    }

    @Throws(DecoderException::class)
    protected abstract fun renderOutputBufferToSurface(
        outputBuffer: VideoDecoderOutputBuffer, surface: Surface
    )

    protected fun setOutput(output: Any?) {
        var output = output
        if (output is Surface) {
            outputSurface = output
            outputBufferRenderer = null
            outputMode = C.VIDEO_OUTPUT_MODE_SURFACE_YUV
        } else if (output is VideoDecoderOutputBufferRenderer) {
            outputSurface = null
            outputBufferRenderer = output
            outputMode = C.VIDEO_OUTPUT_MODE_YUV
        } else {
            // Handle unsupported outputs by clearing the output.
            output = null
            outputSurface = null
            outputBufferRenderer = null
            outputMode = C.VIDEO_OUTPUT_MODE_NONE
        }
        if (this.output !== output) {
            this.output = output
            if (output != null) {
                if (decoder != null) {
                    setDecoderOutputMode(outputMode)
                }
                onOutputChanged()
            } else {
                // The output has been removed. We leave the outputMode of the underlying decoder unchanged
                // in anticipation that a subsequent output will likely be of the same type.
                onOutputRemoved()
            }
        } else if (output != null) {
            // The output is unchanged and non-null.
            onOutputReset()
        }
    }

    protected abstract fun setDecoderOutputMode(outputMode: @C.VideoOutputMode Int)

    protected fun canReuseDecoder(
        decoderName: String, oldFormat: Format, newFormat: Format
    ): DecoderReuseEvaluation {
        return DecoderReuseEvaluation(
            decoderName,
            oldFormat,
            newFormat,
            DecoderReuseEvaluation.REUSE_RESULT_NO,
            DecoderReuseEvaluation.DISCARD_REASON_REUSE_NOT_IMPLEMENTED
        )
    }

    // Internal methods.
    private fun setSourceDrmSession(session: DrmSession?) {
        DrmSession.replaceSession(sourceDrmSession, session)
        sourceDrmSession = session
    }

    private fun setDecoderDrmSession(session: DrmSession?) {
        DrmSession.replaceSession(decoderDrmSession, session)
        decoderDrmSession = session
    }

    @Throws(ExoPlaybackException::class)
    private fun maybeInitDecoder() {
        if (decoder != null) {
            return
        }

        setDecoderDrmSession(sourceDrmSession)

        var cryptoConfig: CryptoConfig? = null
        if (decoderDrmSession != null) {
            cryptoConfig = decoderDrmSession!!.cryptoConfig
            if (cryptoConfig == null) {
                val drmError = decoderDrmSession!!.error
                if (drmError != null) {
                    // Continue for now. We may be able to avoid failure if a new input format causes the
                    // session to be replaced without it having been used.
                } else {
                    // The drm session isn't open yet.
                    return
                }
            }
        }

        try {
            val decoderInitializingTimestamp = SystemClock.elapsedRealtime()
            if (playbackThreadExecutor == null) {
                val handler = Handler(Looper.myLooper()!!)
                playbackThreadExecutor = Executor { r: Runnable? -> handler.post(r!!) }
            }
            decoder = createDecoder(inputFormat!!, cryptoConfig).also {
                it.setOutputStartTimeUs(lastResetPositionUs)
                it.setCallback(this, playbackThreadExecutor)
            }
            setDecoderOutputMode(outputMode)
            val decoderInitializedTimestamp = SystemClock.elapsedRealtime()
            eventDispatcher.decoderInitialized(
                decoder!!.name,
                decoderInitializedTimestamp,
                decoderInitializedTimestamp - decoderInitializingTimestamp
            )
            decoderCounters.decoderInitCount++
        } catch (e: DecoderException) {
            e(TAG, "Video codec error", e)
            eventDispatcher.videoCodecError(e)
            throw createRendererException(
                e, inputFormat, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
            )
        } catch (e: OutOfMemoryError) {
            throw createRendererException(
                e, inputFormat, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
            )
        }
    }

    @Throws(DecoderException::class, ExoPlaybackException::class)
    private fun feedInputBuffer(): Boolean {
        this.lastOutputStreamInfo!!.streamFlags = stream!!.flags
        if (decoder == null || decoderReinitializationState == REINITIALIZATION_STATE_WAIT_END_OF_STREAM || inputStreamEnded) {
            // We need to reinitialize the decoder or the input stream has ended.
            return false
        }

        if (inputBuffer == null) {
            inputBuffer = decoder!!.dequeueInputBuffer()
            if (inputBuffer == null) {
                return false
            }
        }

        val inputBuffer = this.inputBuffer!!
        if (decoderReinitializationState == REINITIALIZATION_STATE_SIGNAL_END_OF_STREAM) {
            inputBuffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM)
            decoder!!.queueInputBuffer(inputBuffer)
            this.inputBuffer = null
            decoderReinitializationState = REINITIALIZATION_STATE_WAIT_END_OF_STREAM
            return false
        }

        val formatHolder = getFormatHolder()
        when (readSource(formatHolder, inputBuffer,  0)) {
            C.RESULT_NOTHING_READ -> return false
            C.RESULT_FORMAT_READ -> {
                onInputFormatChanged(formatHolder)
                return true
            }

            C.RESULT_BUFFER_READ -> {
                if (inputBuffer.isEndOfStream) {
                    inputStreamEnded = true
                    decoder!!.queueInputBuffer(inputBuffer)
                    this.inputBuffer = null
                    return false
                }
                if (waitingForFirstSampleInFormat) {
                    formatQueue.add(inputBuffer.timeUs, inputFormat!!)
                    waitingForFirstSampleInFormat = false
                }
                inputBuffer.flip()
                inputBuffer.format = inputFormat
                onQueueInputBuffer(inputBuffer)
                decoder!!.queueInputBuffer(inputBuffer)
                buffersInCodecCount++
                decoderReceivedBuffers = true
                decoderCounters.queuedInputBufferCount++
                largestQueuedPresentationTimeUs =
                    max(largestQueuedPresentationTimeUs, inputBuffer.timeUs)
                this.inputBuffer = null
                return true
            }

            else -> throw IllegalStateException()
        }
    }

    @Throws(ExoPlaybackException::class, DecoderException::class)
    private fun drainOutputBuffer(positionUs: Long, elapsedRealtimeUs: Long): Boolean {
        if (outputBuffer == null) {
            outputBuffer = decoder!!.dequeueOutputBuffer()
            if (outputBuffer == null) {
                return false
            }
            decoderCounters.skippedOutputBufferCount += outputBuffer!!.skippedOutputBufferCount
            buffersInCodecCount -= outputBuffer!!.skippedOutputBufferCount
        }

        if (outputBuffer!!.isEndOfStream) {
            if (decoderReinitializationState == REINITIALIZATION_STATE_WAIT_END_OF_STREAM) {
                // We're waiting to re-initialize the decoder, and have now processed all final buffers.
                releaseDecoder()
                maybeInitDecoder()
            } else {
                outputBuffer!!.release()
                outputBuffer = null
                outputStreamEnded = true
            }
            return false
        }

        val outputBufferTimeUs = outputBuffer!!.timeUs
        val processedOutputBuffer = processOutputBuffer(positionUs, elapsedRealtimeUs)
        if (processedOutputBuffer) {
            onProcessedOutputBuffer(outputBufferTimeUs)
            outputBuffer = null
        }
        return processedOutputBuffer
    }

    @Throws(ExoPlaybackException::class, DecoderException::class)
    private fun processOutputBuffer(positionUs: Long, elapsedRealtimeUs: Long): Boolean {
        if (initialPositionUs == C.TIME_UNSET) {
            initialPositionUs = positionUs
        }

        val outputBuffer = this.outputBuffer!!
        val bufferTimeUs = outputBuffer.timeUs
        val earlyUs = bufferTimeUs - positionUs
        if (!hasOutput()) {
            // Skip frames in sync with playback, so we'll be at the right frame if the mode changes.
            if (isBufferLate(earlyUs)) {
                skipOutputBuffer(outputBuffer)
                return true
            }
            return false
        }

        val outputStreamInfo = this.outputStreamInfo!!
        val presentationTimeUs = bufferTimeUs - outputStreamInfo.streamOffsetUs
        if (outputStreamInfo.isDurationStrict
            && outputStreamInfo.durationUs != C.TIME_UNSET && presentationTimeUs >= outputStreamInfo.durationUs
        ) {
            skipOutputBuffer(outputBuffer)
            return true
        }

        val format = formatQueue.pollFloor(bufferTimeUs)
        if (format != null) {
            outputFormat = format
        } else if (outputFormat == null) {
            // After a stream change or after the initial start, there should be an input format change
            // which we've not found. Check the Format queue in case the corresponding presentation
            // timestamp is greater than bufferTimeUs
            outputFormat = formatQueue.pollFirst()
        }

        if (shouldForceRender(earlyUs)) {
            renderOutputBuffer(
                outputBuffer,
                presentationTimeUs,
                outputFormat!!
            )
            return true
        }

        val isStarted = state == Renderer.STATE_STARTED
        if (!isStarted || positionUs == initialPositionUs) {
            return false
        }

        // TODO: Treat dropped buffers as skipped while we are joining an ongoing playback.
        if (shouldDropBuffersToKeyframe(earlyUs, elapsedRealtimeUs)
            && maybeDropBuffersToKeyframe(positionUs)
        ) {
            return false
        } else if (shouldDropOutputBuffer(earlyUs, elapsedRealtimeUs)) {
            dropOutputBuffer(outputBuffer)
            return true
        }

        if (earlyUs < 30000) {
            renderOutputBuffer(
                outputBuffer,
                presentationTimeUs,
                outputFormat!!
            )
            return true
        }

        return false
    }

    private fun shouldForceRender(earlyUs: Long): Boolean {
        // TODO: We shouldn't force render while we are joining an ongoing playback.
        val isStarted = state == Renderer.STATE_STARTED
        when (firstFrameState) {
            C.FIRST_FRAME_NOT_RENDERED_ONLY_ALLOWED_IF_STARTED -> return isStarted
            C.FIRST_FRAME_NOT_RENDERED -> return true
            C.FIRST_FRAME_RENDERED -> {
                val elapsedSinceLastRenderUs =
                    msToUs(SystemClock.elapsedRealtime()) - lastRenderTimeUs
                return isStarted && shouldForceRenderOutputBuffer(earlyUs, elapsedSinceLastRenderUs)
            }

            else -> throw IllegalStateException()
        }
    }

    private fun hasOutput(): Boolean {
        return outputMode != C.VIDEO_OUTPUT_MODE_NONE
    }

    private fun onOutputChanged() {
        // If we know the video size, report it again immediately.
        maybeRenotifyVideoSizeChanged()
        // We haven't rendered to the new output yet.
        lowerFirstFrameState(C.FIRST_FRAME_NOT_RENDERED)
        if (state == Renderer.STATE_STARTED) {
            setJoiningDeadlineMs()
        }
    }

    private fun onOutputRemoved() {
        reportedVideoSize = null
        lowerFirstFrameState(C.FIRST_FRAME_NOT_RENDERED)
    }

    private fun onOutputReset() {
        // The output is unchanged and non-null. If we know the video size and/or have already
        // rendered to the output, report these again immediately.
        maybeRenotifyVideoSizeChanged()
        maybeRenotifyRenderedFirstFrame()
    }

    private fun setJoiningDeadlineMs() {
        joiningDeadlineMs =
            if (allowedJoiningTimeMs > 0)
                (SystemClock.elapsedRealtime() + allowedJoiningTimeMs)
            else
                C.TIME_UNSET
    }

    private fun lowerFirstFrameState(firstFrameState: @C.FirstFrameState Int) {
        this.firstFrameState = min(this.firstFrameState, firstFrameState)
    }

    private fun maybeNotifyRenderedFirstFrame() {
        if (firstFrameState != C.FIRST_FRAME_RENDERED) {
            firstFrameState = C.FIRST_FRAME_RENDERED
            if (output != null) {
                eventDispatcher.renderedFirstFrame(output!!)
            }
        }
    }

    private fun maybeRenotifyRenderedFirstFrame() {
        if (firstFrameState == C.FIRST_FRAME_RENDERED && output != null) {
            eventDispatcher.renderedFirstFrame(output!!)
        }
    }

    private fun maybeNotifyVideoSizeChanged(width: Int, height: Int) {
        if (reportedVideoSize == null || reportedVideoSize!!.width != width || reportedVideoSize!!.height != height) {
            reportedVideoSize = VideoSize(width, height)
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
            val now = SystemClock.elapsedRealtime()
            val elapsedMs = now - droppedFrameAccumulationStartTimeMs
            eventDispatcher.droppedFrames(droppedFrames, elapsedMs)
            droppedFrames = 0
            droppedFrameAccumulationStartTimeMs = now
        }
    }

    private val lastOutputStreamInfo: OutputStreamInfo?
        get() {
            if (!pendingOutputStreamChanges.isEmpty()) {
                return pendingOutputStreamChanges.getLast()
            }
            return outputStreamInfo
        }

    private class OutputStreamInfo(
        val previousStreamLastBufferTimeUs: Long,
        val streamOffsetUs: Long,
        var durationUs: Long,
        var streamFlags: @SampleStream.Flags Int
    ) {
        val isDurationStrict: Boolean
            get() = (streamFlags and SampleStream.FLAG_STRICT_DURATION) != 0
    }

    companion object {
        private const val TAG = "DecoderVideoRenderer"
        private const val REINITIALIZATION_STATE_NONE = 0
        private const val REINITIALIZATION_STATE_SIGNAL_END_OF_STREAM = 1
        private const val REINITIALIZATION_STATE_WAIT_END_OF_STREAM = 2

        private fun isBufferLate(earlyUs: Long): Boolean {
            // Class a buffer as late if it should have been presented more than 30 ms ago.
            return earlyUs < -30000
        }

        private fun isBufferVeryLate(earlyUs: Long): Boolean {
            // Class a buffer as very late if it should have been presented more than 500 ms ago.
            return earlyUs < -500000
        }
    }
}
