package androidx.media3.exoplayer.mediacodec

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodec.CodecException
import android.media.MediaCrypto
import android.media.MediaCryptoException
import android.media.MediaFormat
import android.media.metrics.LogSessionId
import android.os.Build
import android.os.Bundle
import androidx.annotation.CallSuper
import androidx.annotation.IntDef
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaLibraryInfo.enableWorkarounds
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Timeline
import androidx.media3.common.util.CodecSpecificDataUtil.getDolbyVisionBaseLayerMimeType
import androidx.media3.common.util.Log.d
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.TimedValueQueue
import androidx.media3.common.util.TraceUtil.beginSection
import androidx.media3.common.util.TraceUtil.endSection
import androidx.media3.common.util.Util.formatInvariant
import androidx.media3.common.util.Util.getErrorCodeForMediaDrmErrorCode
import androidx.media3.common.util.Util.getTrackTypeString
import androidx.media3.container.OpusUtil.getPreSkipSamples
import androidx.media3.container.OpusUtil.needToDecodeOpusFrame
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.DecoderInputBuffer.InsufficientCapacityException
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.CodecParameters
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DecoderReuseEvaluation.DecoderDiscardReasons
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.Renderer.WakeupListener
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RendererCapabilities.AdaptiveSupport
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.OggOpusAudioPacketizer
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.FrameworkCryptoConfig
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.SampleStream.ReadDataResult
import androidx.media3.exoplayer.source.SampleStream.ReadFlags
import com.google.common.base.Preconditions

import org.checkerframework.checker.nullness.qual.RequiresNonNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max

abstract class MediaCodecRenderer(
    context: Context,
    trackType: @C.TrackType Int,
    private val codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector?,
    private val enableDecoderFallback: Boolean,
    private val assumedMinimumCodecOperatingRate: Float
) : BaseRenderer(trackType) {
    class DecoderInitializationException private constructor(
        message: String?,
        cause: Throwable?,
        val mimeType: String?,
        val secureDecoderRequired: Boolean,
        val codecInfo: MediaCodecInfo?,
        val diagnosticInfo: String?,
        val fallbackDecoderInitializationException: DecoderInitializationException?
    ) : Exception(message, cause) {
        constructor(
            format: Format,
            cause: Throwable?,
            secureDecoderRequired: Boolean,
            errorCode: Int
        ) : this(
            "Decoder init failed: [$errorCode], $format",
            cause,
            format.sampleMimeType,
            secureDecoderRequired,  
            null,
            buildCustomDiagnosticInfo(errorCode),
            null
        )

        constructor(
            format: Format,
            cause: Throwable?,
            secureDecoderRequired: Boolean,
            mediaCodecInfo: MediaCodecInfo
        ) : this(
            "Decoder init failed: " + mediaCodecInfo.name + ", " + format,
            cause,
            format.sampleMimeType,
            secureDecoderRequired,
            mediaCodecInfo,
            if (cause is CodecException) cause.diagnosticInfo else null,
            null
        )

        fun copyWithFallbackException(
            fallbackException: DecoderInitializationException?
        ): DecoderInitializationException {
            return DecoderInitializationException(
                message,
                cause,
                mimeType,
                secureDecoderRequired,
                codecInfo,
                diagnosticInfo,
                fallbackException
            )
        }

        companion object {
            const val CUSTOM_ERROR_CODE_BASE = -50000
            const val NO_SUITABLE_DECODER_ERROR = CUSTOM_ERROR_CODE_BASE + 1
            const val DECODER_QUERY_ERROR = CUSTOM_ERROR_CODE_BASE + 2

            private fun buildCustomDiagnosticInfo(errorCode: Int): String {
                val sign = if (errorCode < 0) "neg_" else ""
                val packageName = "androidx.media3.exoplayer.mediacodec"
                return packageName + ".MediaCodecRenderer_" + sign + abs(errorCode)
            }
        }
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [RECONFIGURATION_STATE_NONE, RECONFIGURATION_STATE_WRITE_PENDING, RECONFIGURATION_STATE_QUEUE_PENDING]
    )
    private annotation class ReconfigurationState

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [DRAIN_STATE_NONE, DRAIN_STATE_SIGNAL_END_OF_STREAM, DRAIN_STATE_WAIT_END_OF_STREAM])
    private annotation class DrainState

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [DRAIN_ACTION_NONE, DRAIN_ACTION_FLUSH, DRAIN_ACTION_FLUSH_AND_UPDATE_DRM_SESSION, DRAIN_ACTION_REINITIALIZE]
    )
    private annotation class DrainAction

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [ADAPTATION_WORKAROUND_MODE_NEVER, ADAPTATION_WORKAROUND_MODE_SAME_RESOLUTION, ADAPTATION_WORKAROUND_MODE_ALWAYS]
    )
    private annotation class AdaptationWorkaroundMode

    private val context: Context? = context.applicationContext
    private val mediaCodecSelector = mediaCodecSelector!!
    private val noDataBuffer = DecoderInputBuffer.newNoDataInstance()
    private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED)
    private val bypassSampleBuffer =
        DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)
    private val bypassBatchBuffer = BatchBuffer()
    private val outputBufferInfo = MediaCodec.BufferInfo()
    private val pendingOutputStreamChanges = ArrayDeque<OutputStreamInfo>()
    private val oggOpusAudioPacketizer = OggOpusAudioPacketizer()
    private val readDataResultHolder = AtomicInteger()
    private var inputFormat: Format? = null
    private var outputFormat:Format? = null
    private var codecDrmSession: DrmSession? = null
    private var sourceDrmSession: DrmSession? = null

    protected var wakeupListener:WakeupListener? = null
        private set
    private var mediaCrypto: MediaCrypto? = null
    private var renderTimeLimitMs = C.TIME_UNSET
    protected var playbackSpeed: Float = 1f
        private set
    private var targetPlaybackSpeed: Float = 1f
    protected var codec: MediaCodecAdapter? = null
        private set
    protected var codecInputFormat: Format? = null
        private set
    protected var codecOutputMediaFormat: MediaFormat? = null
        private set
    private var codecOutputMediaFormatChanged = false
    protected var codecOperatingRate: Float = CODEC_OPERATING_RATE_UNSET
        private set
    private var availableCodecInfos: ArrayDeque<MediaCodecInfo?>? = null
    private var preferredDecoderInitializationException: DecoderInitializationException? = null
    protected var codecInfo: MediaCodecInfo? = null
        private set
    private var codecAdaptationWorkaroundMode: @AdaptationWorkaroundMode Int =
        ADAPTATION_WORKAROUND_MODE_NEVER
    private var codecNeedsSosFlushWorkaround = false
    private var codecNeedsEosFlushWorkaround = false
    private var codecNeedsAdaptationWorkaroundBuffer = false
    private var shouldSkipAdaptationWorkaroundOutputBuffer = false
    private var codecNeedsEosPropagation = false
    private var lastOutputBufferProcessedRealtimeMs = C.TIME_UNSET
    private var codecRegisteredOnBufferAvailableListener = false
    private var codecHotswapDeadlineMs = C.TIME_UNSET
    private var inputIndex = C.INDEX_UNSET
    private var outputIndex = C.INDEX_UNSET
    private var outputBuffer: ByteBuffer? = null
    private var isDecodeOnlyOutputBuffer = false
    protected var isBypassEnabled: Boolean = false
        private set
    private var bypassSampleBufferPending = false
    private var bypassDrainAndReinitialize = false
    private var codecReconfigured = false
    private var codecReconfigurationState: @ReconfigurationState Int = RECONFIGURATION_STATE_NONE
    private var codecDrainState: @DrainState Int = DRAIN_STATE_NONE
    private var codecDrainAction: @DrainAction Int = DRAIN_STATE_NONE
    private var codecReceivedBuffers = false
    private var codecReceivedEos = false
    private var codecHasOutputMediaFormat = false
    protected var largestQueuedPresentationTimeUs = C.TIME_UNSET
        private set
    private var largestQueuedPresentationTimeWithinDurationUs = C.TIME_UNSET
    private var inputStreamEnded = false
    private var outputStreamEnded = false
    private var waitingForFirstSampleInFormat = false
    private var pendingOutputEndOfStream = false
    private var pendingPlaybackException: ExoPlaybackException? = null
    protected var decoderCounters = DecoderCounters()
    private var outputStreamInfo = OutputStreamInfo.UNSET
    protected var lastProcessedOutputBufferTimeUs = C.TIME_UNSET
        private set
    private var needToNotifyOutputFormatChangeAfterStreamChange = false
    private var experimentalEnableProcessedStreamChangedAtStart = false
    private var hasSkippedFlushAndWaitingForQueueInputBuffer = false
    protected var skippedFlushOffsetUs = 0L
        private set
    private var activeCodecParameters = CodecParameters.EMPTY
    private var lastDispatchedCodecParameters: CodecParameters? = CodecParameters.EMPTY
    private var subscribedCodecParameterKeys = setOf<String>()

    init {
        // MediaCodec outputs audio buffers in native endian:
        // https://developer.android.com/reference/android/media/MediaCodec#raw-audio-buffers
        // and code called from MediaCodecAudioRenderer.processOutputBuffer expects this endianness.
        // Call ensureSpaceForWrite to make sure the buffer has non-null data, and set the expected
        // endianness.
        bypassBatchBuffer.ensureSpaceForWrite( 0)
        bypassBatchBuffer.data!!.order(ByteOrder.nativeOrder())
    }

    fun setRenderTimeLimitMs(renderTimeLimitMs: Long) {
        this.renderTimeLimitMs = renderTimeLimitMs
    }

    override fun supportsMixedMimeTypeAdaptation(): @AdaptiveSupport Int {
        return RendererCapabilities.ADAPTIVE_NOT_SEAMLESS
    }

    @Throws(ExoPlaybackException::class)
    override fun supportsFormat(format: Format): @RendererCapabilities.Capabilities Int {
        try {
            return supportsFormat(mediaCodecSelector, format)
        } catch (e: DecoderQueryException) {
            throw createRendererException(
                e,
                format,
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED
            )
        }
    }

    @Throws(DecoderQueryException::class)
    protected abstract fun supportsFormat(
        mediaCodecSelector: MediaCodecSelector, format: Format
    ): @RendererCapabilities.Capabilities Int

    override fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long): Long {
        return getDurationToProgressUs(
            positionUs,
            elapsedRealtimeUs,  
            codecRegisteredOnBufferAvailableListener
        )
    }

    fun experimentalEnableProcessedStreamChangedAtStart() {
        this.experimentalEnableProcessedStreamChangedAtStart = true
    }

    protected open fun getDurationToProgressUs(
        positionUs: Long, elapsedRealtimeUs: Long, isOnBufferAvailableListenerRegistered: Boolean
    ): Long {
        return super.getDurationToProgressUs(positionUs, elapsedRealtimeUs)
    }

    @Throws(DecoderQueryException::class)
    protected abstract fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector, format: Format, requiresSecureDecoder: Boolean
    ): List<MediaCodecInfo>

    protected abstract fun getMediaCodecConfiguration(
        codecInfo: MediaCodecInfo,
        format: Format,
        crypto: MediaCrypto?,
        codecOperatingRate: Float
    ): MediaCodecAdapter.Configuration

    @Throws(ExoPlaybackException::class)
    protected fun maybeInitCodecOrBypass() {
        if (codec != null || this.isBypassEnabled || inputFormat == null) {
            // We have a codec, are bypassing it, or don't have a format to decide how to render.
            return
        }
        val inputFormat = this.inputFormat!!

        if (isBypassPossible(inputFormat)) {
            initBypass(inputFormat!!)
            return
        }

        setCodecDrmSession(sourceDrmSession)
        if (codecDrmSession == null || initMediaCryptoIfDrmSessionReady()) {
            try {
                val mediaCryptoRequiresSecureDecoder =
                    codecDrmSession != null && (codecDrmSession!!.state === DrmSession.STATE_OPENED
                            || codecDrmSession!!.state === DrmSession.STATE_OPENED_WITH_KEYS)
                            && codecDrmSession!!.requiresSecureDecoder(
                        inputFormat!!.sampleMimeType!!
                    )
                maybeInitCodecWithFallback(mediaCrypto, mediaCryptoRequiresSecureDecoder)
            } catch (e: DecoderInitializationException) {
                throw createRendererException(
                    e, inputFormat, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                )
            }
        }
        if (mediaCrypto != null && codec == null) {
            // mediaCrypto was created, but a codec wasn't, so release the mediaCrypto before returning.
            mediaCrypto!!.release()
            mediaCrypto = null
        }
    }

    protected fun isBypassPossible(format: Format): Boolean {
        return sourceDrmSession == null && shouldUseBypass(format)
    }

    protected open fun shouldUseBypass(format: Format): Boolean {
        return false
    }

    protected open fun shouldInitCodec(codecInfo: MediaCodecInfo): Boolean {
        return true
    }

    protected fun shouldReinitCodec(): Boolean {
        return false
    }

    protected fun setPendingPlaybackException(exception: ExoPlaybackException?) {
        pendingPlaybackException = exception
    }

    @Throws(ExoPlaybackException::class)
    protected fun updateOutputFormatForTime(presentationTimeUs: Long) {
        var outputFormatChanged = false
        var format = outputStreamInfo.formatQueue.pollFloor(presentationTimeUs)
        if (format == null && needToNotifyOutputFormatChangeAfterStreamChange
            && codecOutputMediaFormat != null
        ) {
            // After a stream change or after the initial start, there should be an input format change,
            // which we've not found. Check the Format queue in case the corresponding presentation
            // timestamp is greater than presentationTimeUs, which can happen for some codecs
            // [Internal ref: b/162719047 and https://github.com/google/ExoPlayer/issues/8594].
            format = outputStreamInfo.formatQueue.pollFirst()
        }
        if (format != null) {
            outputFormat = format
            outputFormatChanged = true
        }
        if (outputFormatChanged || (codecOutputMediaFormatChanged && outputFormat != null)) {
            if (DEBUG_LOG_ENABLED) {
                d(
                    DEBUG_LOG_TAG,
                    (getTrackTypeString(trackType)
                            + ", output format changed="
                            + outputFormat)
                )
            }
            onOutputFormatChanged(
                outputFormat!!,
                codecOutputMediaFormat!!
            )
            codecOutputMediaFormatChanged = false
            needToNotifyOutputFormatChangeAfterStreamChange = false
        }
    }

    @Throws(ExoPlaybackException::class)
    override fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) {
        decoderCounters = DecoderCounters()
    }

    @Throws(ExoPlaybackException::class)
    override fun onStreamChanged(
        formats: Array<Format>,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaPeriodId
    ) {
        if (DEBUG_LOG_ENABLED) {
            d(
                DEBUG_LOG_TAG,
                (getTrackTypeString(trackType)
                        + ", input stream change, start="
                        + startPositionUs
                        + ", offset="
                        + offsetUs)
            )
        }

        val durationUs = streamEndPositionUs
        val streamFlags: @SampleStream.Flags Int = stream!!.flags
        if (outputStreamInfo.streamOffsetUs == C.TIME_UNSET) {
            // This is the first stream.
            setOutputStreamInfo(
                OutputStreamInfo( 
                    C.TIME_UNSET,
                    startPositionUs,
                    offsetUs,
                    durationUs,
                    streamFlags
                )
            )
            if (experimentalEnableProcessedStreamChangedAtStart) {
                onProcessedStreamChange()
            }
        } else if (pendingOutputStreamChanges.isEmpty()
            && (largestQueuedPresentationTimeUs == C.TIME_UNSET
                    || (lastProcessedOutputBufferTimeUs != C.TIME_UNSET
                    && lastProcessedOutputBufferTimeUs >= largestQueuedPresentationTimeUs))
        ) {
            // All previous streams have never queued any samples or have been fully output already.
            setOutputStreamInfo(
                OutputStreamInfo( 
                    C.TIME_UNSET,
                    startPositionUs,
                    offsetUs,
                    durationUs,
                    streamFlags
                )
            )
            if (outputStreamInfo.streamOffsetUs != C.TIME_UNSET) {
                onProcessedStreamChange()
            }
        } else {
            pendingOutputStreamChanges.add(
                OutputStreamInfo( 
                    largestQueuedPresentationTimeUs,
                    startPositionUs,
                    offsetUs,
                    durationUs,
                    streamFlags
                )
            )
        }
    }

    @Throws(ExoPlaybackException::class)
    override fun onPositionReset(
        positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean
    ) {
        if (!pendingOutputStreamChanges.isEmpty()) {
            // Update current outputStreamInfo to represent current stream provided by sample queue.
            outputStreamInfo = pendingOutputStreamChanges.getLast()
        }
        pendingOutputStreamChanges.clear()
        if (!sampleStreamIsResetToKeyFrame) {
            // onPositionReset is not resetting the sample stream and so codec-related state will not
            // be reset.
            return
        }
        inputStreamEnded = false
        outputStreamEnded = false
        pendingOutputEndOfStream = false
        if (this.isBypassEnabled) {
            resetBypassState()
        } else {
            flushOrReinitializeCodec()
        }
        // If there is a format change on the input side still pending propagation to the output, we
        // need to queue a format next time a buffer is read. This is because we may not read a new
        // input format after the position reset.
        if (outputStreamInfo.formatQueue.size() > 0) {
            waitingForFirstSampleInFormat = true
        }
        outputStreamInfo.formatQueue.clear()
        outputStreamInfo.queuedBufferAfterReset = false
    }

    override fun onTimelineChanged(timeline: Timeline) {
        val lastOutputStreamInfo =
            this.lastOutputStreamInfo
        lastOutputStreamInfo.durationUs = streamEndPositionUs
    }

    @Throws(ExoPlaybackException::class)
    override fun setPlaybackSpeed(currentPlaybackSpeed: Float, targetPlaybackSpeed: Float) {
        this.playbackSpeed = currentPlaybackSpeed
        this.targetPlaybackSpeed = targetPlaybackSpeed
        updateCodecOperatingRate(codecInputFormat)
    }

    override fun onDisabled() {
        inputFormat = null
        setOutputStreamInfo(OutputStreamInfo.UNSET)
        pendingOutputStreamChanges.clear()
        if (this.isBypassEnabled) {
            disableBypass()
        } else {
            flushOrReleaseCodec()
        }
    }

    override fun onReset() {
        try {
            disableBypass()
            releaseCodec()
        } finally {
            setSourceDrmSession(null)
        }
    }

    private fun disableBypass() {
        this.isBypassEnabled = false
        resetBypassState()
    }

    private fun resetBypassState() {
        resetCommonStateForFlush()
        bypassDrainAndReinitialize = false
        bypassBatchBuffer.clear()
        bypassSampleBuffer.clear()
        bypassSampleBufferPending = false
        oggOpusAudioPacketizer.reset()
    }

    protected fun releaseCodec() {
        try {
            if (codec != null) {
                codec!!.release()
                decoderCounters.decoderReleaseCount++
                onCodecReleased(codecInfo!!.name)
            }
        } finally {
            codec = null
            try {
                if (mediaCrypto != null) {
                    mediaCrypto!!.release()
                }
            } finally {
                mediaCrypto = null
                setCodecDrmSession(null)
                resetCodecStateForRelease()
            }
        }
    }

    override fun onStarted() {
        // Do nothing. Overridden to remove throws clause.
    }

    override fun onStopped() {
        // Do nothing. Overridden to remove throws clause.
    }

    @Throws(ExoPlaybackException::class)
    override fun handleMessage(messageType: @Renderer.MessageType Int, message: Any?) {
        when (messageType) {
            Renderer.MSG_SET_WAKEUP_LISTENER -> wakeupListener = message as WakeupListener

            Renderer.MSG_SET_CODEC_PARAMETERS -> if (Build.VERSION.SDK_INT >= 29) {
                activeCodecParameters = message as CodecParameters

                val codec = this.codec
                if (codec != null) {
                    codec.setParameters(activeCodecParameters.toBundle())
                }
            }

            Renderer.MSG_SET_SUBSCRIBED_CODEC_PARAMETER_KEYS -> if (Build.VERSION.SDK_INT >= 29) {
                updateCodecSubscriptions(message as Set<String>)
            }

            else -> super.handleMessage(messageType, message)
        }
    }

    @Throws(ExoPlaybackException::class)
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        if (pendingOutputEndOfStream) {
            pendingOutputEndOfStream = false
            processEndOfStream()
        }
        if (pendingPlaybackException != null) {
            val playbackException = pendingPlaybackException
            pendingPlaybackException = null
            throw playbackException!!
        }

        try {
            if (outputStreamEnded) {
                renderToEndOfStream()
                return
            }
            if (inputFormat == null && !readSourceOmittingSampleData(SampleStream.FLAG_REQUIRE_FORMAT)) {
                // We still don't have a format and can't make progress without one.
                return
            }
            // We have a format.
            maybeInitCodecOrBypass()
            if (this.isBypassEnabled) {
                beginSection("bypassRender")
                while (bypassRender(positionUs, elapsedRealtimeUs)) {
                }
                endSection()
            } else if (codec != null) {
                val renderStartTimeMs = getClock().elapsedRealtime()
                beginSection("drainAndFeed")
                while (drainOutputBuffer(positionUs, elapsedRealtimeUs)
                    && shouldContinueRendering(renderStartTimeMs)
                ) {
                }
                while (feedInputBuffer() && shouldContinueRendering(renderStartTimeMs)) {
                }
                endSection()
            } else {
                decoderCounters.skippedInputBufferCount += skipSource(positionUs)
                // We need to read any format changes despite not having a codec so that drmSession can be
                // updated, and so that we have the most recent format should the codec be initialized. We
                // may also reach the end of the stream. FLAG_PEEK is used because we don't want to advance
                // the source further than skipSource has already done.
                readSourceOmittingSampleData(SampleStream.FLAG_PEEK)
            }
            decoderCounters.ensureUpdated()
        } catch (e: MediaCodec.CryptoException) {
            throw createRendererException(
                e, inputFormat, getErrorCodeForMediaDrmErrorCode(e.getErrorCode())
            )
        } catch (e: IllegalStateException) {
            if (isMediaCodecException(e)) {
                onCodecError(e)
                val isRecoverable =
                    (e is CodecException) && e.isRecoverable()
                if (isRecoverable) {
                    releaseCodec()
                }
                val exception = createDecoderException(
                    e,
                    this.codecInfo
                )
                @PlaybackException.ErrorCode val errorCode: @PlaybackException.ErrorCode Int =
                    if (exception.errorCode == CodecException.ERROR_RECLAIMED)
                        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED
                    else
                        PlaybackException.ERROR_CODE_DECODING_FAILED
                throw createRendererException(exception, inputFormat, isRecoverable, errorCode)
            }
            throw e
        }
    }

    @Throws(ExoPlaybackException::class)
    protected fun flushOrReinitializeCodec(): Boolean {
        val released = flushOrReleaseCodec()
        if (released) {
            maybeInitCodecOrBypass()
        }
        return released
    }

    private fun flushOrReleaseCodec(): Boolean {
        if (codec == null) {
            return false
        }
        if (shouldReleaseCodecInsteadOfFlushing()) {
            releaseCodec()
            return true
        } else if (shouldFlushCodec()) {
            flushCodec()
        } else if (codecReceivedBuffers) {
            hasSkippedFlushAndWaitingForQueueInputBuffer = true
        }
        return false
    }

    protected open fun shouldReleaseCodecInsteadOfFlushing(): Boolean {
        if (codecDrainAction == DRAIN_ACTION_REINITIALIZE || (codecNeedsSosFlushWorkaround && !codecHasOutputMediaFormat)
            || (codecNeedsEosFlushWorkaround
                    && (codecReceivedEos || codecDrainState == DRAIN_STATE_SIGNAL_END_OF_STREAM))
        ) {
            return true
        }
        if (codecDrainAction == DRAIN_ACTION_FLUSH_AND_UPDATE_DRM_SESSION) {
            // Needed to keep lint happy (it doesn't understand the checkState call alone)
            try {
                updateDrmSession()
            } catch (e: ExoPlaybackException) {
                w(TAG, "Failed to update the DRM session, releasing the codec instead.", e)
                return true
            }
        }
        return false
    }

    protected open fun shouldFlushCodec(): Boolean {
        return codecReceivedBuffers
    }

    private fun flushCodec() {
        if (DEBUG_LOG_ENABLED) {
            d(DEBUG_LOG_TAG, getTrackTypeString(trackType) + ", flush codec")
        }
        try {
            codec!!.flush()
        } finally {
            resetCodecStateForFlush()
        }
    }

    private fun resetCommonStateForFlush() {
        largestQueuedPresentationTimeUs = C.TIME_UNSET
        largestQueuedPresentationTimeWithinDurationUs = C.TIME_UNSET
        this.lastOutputStreamInfo.lastBufferTimeUs = C.TIME_UNSET
        lastProcessedOutputBufferTimeUs = C.TIME_UNSET
    }

    @CallSuper
    protected open fun resetCodecStateForFlush() {
        resetInputBuffer()
        resetOutputBuffer()
        resetCommonStateForFlush()
        codecHotswapDeadlineMs = C.TIME_UNSET
        codecReceivedEos = false
        lastOutputBufferProcessedRealtimeMs = C.TIME_UNSET
        codecReceivedBuffers = false
        codecNeedsAdaptationWorkaroundBuffer = false
        shouldSkipAdaptationWorkaroundOutputBuffer = false
        isDecodeOnlyOutputBuffer = false
        codecDrainState = DRAIN_STATE_NONE
        codecDrainAction = DRAIN_ACTION_NONE
        // Reconfiguration data sent shortly before the flush may not have been processed by the
        // decoder. If the codec has been reconfigured we always send reconfiguration data again to
        // guarantee that it's processed.
        codecReconfigurationState =
            if (codecReconfigured) RECONFIGURATION_STATE_WRITE_PENDING else RECONFIGURATION_STATE_NONE
        hasSkippedFlushAndWaitingForQueueInputBuffer = false
        skippedFlushOffsetUs = 0
    }

    @CallSuper
    protected fun resetCodecStateForRelease() {
        resetCodecStateForFlush()

        pendingPlaybackException = null
        availableCodecInfos = null
        codecInfo = null
        codecInputFormat = null
        codecOutputMediaFormat = null
        codecOutputMediaFormatChanged = false
        codecHasOutputMediaFormat = false
        codecOperatingRate = CODEC_OPERATING_RATE_UNSET
        codecAdaptationWorkaroundMode = ADAPTATION_WORKAROUND_MODE_NEVER
        codecNeedsSosFlushWorkaround = false
        codecNeedsEosFlushWorkaround = false
        codecNeedsEosPropagation = false
        codecRegisteredOnBufferAvailableListener = false
        codecReconfigured = false
        codecReconfigurationState = RECONFIGURATION_STATE_NONE
    }

    protected open fun createDecoderException(
        cause: Throwable, codecInfo: MediaCodecInfo?
    ): MediaCodecDecoderException {
        return MediaCodecDecoderException(cause, codecInfo)
    }

    @RequiresApi(29)
    private fun checkAndNotifyCodecParameterChanges(mediaFormat: MediaFormat) {
        if (subscribedCodecParameterKeys!!.isEmpty()) {
            return
        }

        val currentValues =
            CodecParameters.createFrom(mediaFormat, subscribedCodecParameterKeys!!).build()

        if (currentValues == lastDispatchedCodecParameters) {
            return
        }

        lastDispatchedCodecParameters = currentValues
        onCodecParametersChanged(currentValues)
    }

    @RequiresApi(29)
    private fun updateCodecSubscriptions(newKeys: Set<String>) {
        if (subscribedCodecParameterKeys == newKeys) {
            return
        }

        if (Build.VERSION.SDK_INT >= 31) {
            val addedKeys: MutableSet<String> = HashSet<String>(newKeys)
            val removedKeys: MutableSet<String> = HashSet<String>()
            for (oldKey in subscribedCodecParameterKeys!!) {
                if (!addedKeys.remove(oldKey)) {
                    removedKeys.add(oldKey)
                }
            }

            val codec = this.codec
            if (codec != null) {
                if (!removedKeys.isEmpty()) {
                    codec.unsubscribeFromVendorParameters(removedKeys.toList())
                }
                if (!addedKeys.isEmpty()) {
                    codec.subscribeToVendorParameters(addedKeys.toList())
                }
            }
        }

        subscribedCodecParameterKeys = newKeys
    }

    @Throws(ExoPlaybackException::class)
    private fun readSourceOmittingSampleData(readFlags: @ReadFlags Int): Boolean {
        val formatHolder = getFormatHolder()
        noDataBuffer.clear()
        val result: @ReadDataResult Int =
            readSource(formatHolder, noDataBuffer, readFlags or SampleStream.FLAG_OMIT_SAMPLE_DATA)
        if (result == C.RESULT_FORMAT_READ) {
            onInputFormatChanged(formatHolder)
            return true
        } else if (result == C.RESULT_BUFFER_READ && noDataBuffer.isEndOfStream) {
            inputStreamEnded = true
            processEndOfStream()
        }
        return false
    }

    @RequiresNonNull("this.codecDrmSession")
    @Throws(ExoPlaybackException::class)
    private fun initMediaCryptoIfDrmSessionReady(): Boolean {
        Preconditions.checkState(mediaCrypto == null)
        val codecDrmSession = this.codecDrmSession
        val cryptoConfig = codecDrmSession!!.cryptoConfig
        if (enableWorkarounds()
            && FrameworkCryptoConfig.WORKAROUND_DEVICE_NEEDS_KEYS_TO_CONFIGURE_CODEC
            && cryptoConfig is FrameworkCryptoConfig
        ) {
            @DrmSession.State val drmSessionState: @DrmSession.State Int = codecDrmSession.state
            if (drmSessionState == DrmSession.STATE_ERROR) {
                val drmSessionException = codecDrmSession.error!!
                throw createRendererException(
                    drmSessionException, inputFormat, drmSessionException.errorCode
                )
            } else if (drmSessionState != DrmSession.STATE_OPENED_WITH_KEYS) {
                // Wait for keys.
                return false
            }
        }
        if (cryptoConfig == null) {
            val drmError = codecDrmSession.error
            if (drmError != null) {
                // Continue for now. We may be able to avoid failure if a new input format causes the
                // session to be replaced without it having been used.
                return true
            } else {
                // The drm session isn't open yet.
                return false
            }
        } else if (cryptoConfig is FrameworkCryptoConfig) {
            val frameworkCryptoConfig = cryptoConfig
            try {
                mediaCrypto =
                    MediaCrypto(frameworkCryptoConfig.uuid!!, frameworkCryptoConfig.sessionId!!)
            } catch (e: MediaCryptoException) {
                throw createRendererException(
                    e, inputFormat, PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR
                )
            }
        }
        return true
    }

    @Throws(DecoderInitializationException::class, ExoPlaybackException::class)
    private fun maybeInitCodecWithFallback(
        crypto: MediaCrypto?, mediaCryptoRequiresSecureDecoder: Boolean
    ) {
        val inputFormat = this.inputFormat!!
        if (availableCodecInfos == null) {
            try {
                val allAvailableCodecInfos =
                    getAvailableCodecInfos(mediaCryptoRequiresSecureDecoder)
                availableCodecInfos = ArrayDeque<MediaCodecInfo?>()
                if (enableDecoderFallback) {
                    availableCodecInfos!!.addAll(allAvailableCodecInfos)
                } else if (!allAvailableCodecInfos.isEmpty()) {
                    availableCodecInfos!!.add(allAvailableCodecInfos[0])
                }
                preferredDecoderInitializationException = null
            } catch (e: DecoderQueryException) {
                throw DecoderInitializationException(
                    inputFormat,
                    e,
                    mediaCryptoRequiresSecureDecoder,
                    DecoderInitializationException.DECODER_QUERY_ERROR
                )
            }
        }

        if (availableCodecInfos!!.isEmpty()) {
            throw DecoderInitializationException(
                inputFormat,  
                null,
                mediaCryptoRequiresSecureDecoder,
                DecoderInitializationException.NO_SUITABLE_DECODER_ERROR
            )
        }

        val availableCodecInfos = this.availableCodecInfos!!
        while (codec == null) {
            val codecInfo = availableCodecInfos.peekFirst()!!
            if (!maybeInitializeProcessingPipeline(inputFormat)) {
                return
            }

            if (!shouldInitCodec(codecInfo)) {
                return
            }
            try {
                initCodec(codecInfo, crypto)
            } catch (e: Exception) {
                w(TAG, "Failed to initialize decoder: " + codecInfo, e)
                // This codec failed to initialize, so fall back to the next codec in the list (if any). We
                // won't try to use this codec again unless there's a format change or the renderer is
                // disabled and re-enabled.
                availableCodecInfos.removeFirst()
                val exception =
                    DecoderInitializationException(
                        inputFormat, e, mediaCryptoRequiresSecureDecoder, codecInfo
                    )
                onCodecError(exception)
                if (preferredDecoderInitializationException == null) {
                    preferredDecoderInitializationException = exception
                } else {
                    preferredDecoderInitializationException =
                        preferredDecoderInitializationException!!.copyWithFallbackException(
                            exception
                        )
                }
                if (availableCodecInfos.isEmpty()) {
                    throw preferredDecoderInitializationException!!
                }
            }
        }

        this.availableCodecInfos = null
    }

    @Throws(DecoderQueryException::class)
    private fun getAvailableCodecInfos(mediaCryptoRequiresSecureDecoder: Boolean): List<MediaCodecInfo> {
        val inputFormat = this.inputFormat!!
        var codecInfos =
            getDecoderInfos(mediaCodecSelector, inputFormat, mediaCryptoRequiresSecureDecoder)
        if (codecInfos.isEmpty() && mediaCryptoRequiresSecureDecoder) {
            // The drm session indicates that a secure decoder is required, but the device does not
            // have one. Assuming that supportsFormat indicated support for the media being played, we
            // know that it does not require a secure output path. Most CDM implementations allow
            // playback to proceed with a non-secure decoder in this case, so we try our luck.
            codecInfos =
                getDecoderInfos(mediaCodecSelector, inputFormat,  false)
            if (!codecInfos.isEmpty()) {
                w(
                    TAG,
                    ("Drm session requires secure decoder for "
                            + inputFormat.sampleMimeType
                            + ", but no secure decoder available. Trying to proceed with "
                            + codecInfos
                            + ".")
                )
            }
        }
        return codecInfos
    }

    private fun initBypass(format: Format) {
        disableBypass() // In case of transition between 2 bypass formats.

        val mimeType = format.sampleMimeType
        if ((MimeTypes.AUDIO_AAC != mimeType) && (MimeTypes.AUDIO_MPEG != mimeType) && (MimeTypes.AUDIO_OPUS != mimeType)) {
            // TODO(b/154746451): Batching provokes frame drops in non offload.
            bypassBatchBuffer.setMaxSampleCount(1)
        } else {
            bypassBatchBuffer.setMaxSampleCount(BatchBuffer.DEFAULT_MAX_SAMPLE_COUNT)
        }
        this.isBypassEnabled = true
    }

    @Throws(Exception::class)
    private fun initCodec(codecInfo: MediaCodecInfo, crypto: MediaCrypto?) {
        this.codecInfo = codecInfo
        val inputFormat = this.inputFormat!!
        val codecInitializingTimestamp: Long
        val codecInitializedTimestamp: Long
        val codecName = codecInfo.name
        var codecOperatingRate =
            getCodecOperatingRateV23(targetPlaybackSpeed, inputFormat, getStreamFormats())
        if (codecOperatingRate <= assumedMinimumCodecOperatingRate) {
            codecOperatingRate = CODEC_OPERATING_RATE_UNSET
        }
        codecInitializingTimestamp = getClock().elapsedRealtime()
        val configuration =
            getMediaCodecConfiguration(codecInfo, inputFormat, crypto, codecOperatingRate)
        if (Build.VERSION.SDK_INT >= 31) {
            Api31.setLogSessionIdToMediaCodecFormat(configuration, getPlayerId())
        }
        try {
            beginSection("createCodec:" + codecName)
            codec = codecAdapterFactory.createAdapter(configuration)
            codecRegisteredOnBufferAvailableListener =
                codec!!.registerOnBufferAvailableListener(MediaCodecRendererCodecAdapterListener())
        } finally {
            endSection()
        }
        codecInitializedTimestamp = getClock().elapsedRealtime()

        if (!codecInfo.isFormatSupported(context!!, inputFormat)) {
            w(
                TAG,
                formatInvariant(
                    "Format exceeds selected codec's capabilities [%s, %s]",
                    Format.toLogString(inputFormat), codecName
                )
            )
        }

        this.codecOperatingRate = codecOperatingRate
        codecInputFormat = inputFormat
        codecAdaptationWorkaroundMode = codecAdaptationWorkaroundMode(codecName)
        codecNeedsSosFlushWorkaround = codecNeedsSosFlushWorkaround(codecName)
        codecNeedsEosFlushWorkaround = codecNeedsEosFlushWorkaround(codecName)
        codecNeedsEosPropagation = codecNeedsEosPropagationWorkaround(codecInfo)
        if (codec!!.needsReconfiguration()) {
            this.codecReconfigured = true
            this.codecReconfigurationState = RECONFIGURATION_STATE_WRITE_PENDING
            this.codecNeedsAdaptationWorkaroundBuffer =
                codecAdaptationWorkaroundMode != ADAPTATION_WORKAROUND_MODE_NEVER
        }

        if (state == Renderer.STATE_STARTED) {
            codecHotswapDeadlineMs = getClock().elapsedRealtime() + MAX_CODEC_HOTSWAP_TIME_MS
        }

        decoderCounters.decoderInitCount++
        val elapsed = codecInitializedTimestamp - codecInitializingTimestamp
        if (Build.VERSION.SDK_INT >= 31 && !subscribedCodecParameterKeys!!.isEmpty()) {
            this.codec!!.subscribeToVendorParameters(ArrayList<String>(subscribedCodecParameterKeys))
        }
        onCodecInitialized(codecName, configuration, codecInitializedTimestamp, elapsed)
        if (DEBUG_LOG_ENABLED) {
            val trackType = trackType
            d(DEBUG_LOG_TAG, getTrackTypeString(trackType) + ", new codec, " + codecName)
            if (trackType == C.TRACK_TYPE_VIDEO) {
                d(DEBUG_LOG_TAG, "video, codec surface=" + configuration.surface)
            }
        }
    }

    private fun shouldContinueRendering(renderStartTimeMs: Long): Boolean {
        return renderTimeLimitMs == C.TIME_UNSET
                || getClock().elapsedRealtime() - renderStartTimeMs < renderTimeLimitMs
    }

    private fun hasOutputBuffer(): Boolean {
        return outputIndex >= 0
    }

    private fun resetInputBuffer() {
        inputIndex = C.INDEX_UNSET
        buffer.data = null
    }

    private fun resetOutputBuffer() {
        outputIndex = C.INDEX_UNSET
        outputBuffer = null
    }

    private fun setSourceDrmSession(session: DrmSession?) {
        DrmSession.replaceSession(sourceDrmSession, session)
        sourceDrmSession = session
    }

    private fun setCodecDrmSession(session: DrmSession?) {
        DrmSession.replaceSession(codecDrmSession, session)
        codecDrmSession = session
    }

    @Throws(ExoPlaybackException::class)
    private fun feedInputBuffer(): Boolean {
        this.lastOutputStreamInfo.streamFlags =
            stream!!.flags
        if (codec == null || codecDrainState == DRAIN_STATE_WAIT_END_OF_STREAM || inputStreamEnded) {
            return false
        }
        if (codecDrainState == DRAIN_STATE_NONE && shouldReinitCodec()) {
            drainAndReinitializeCodec()
        }

        val codec = this.codec!!
        if (inputIndex < 0) {
            inputIndex = codec.dequeueInputBufferIndex()
            if (inputIndex < 0) {
                return false
            }
            buffer.data = codec.getInputBuffer(inputIndex)
            buffer.clear()
        }

        if (codecDrainState == DRAIN_STATE_SIGNAL_END_OF_STREAM) {
            // We need to re-initialize the codec. Send an end of stream signal to the existing codec so
            // that it outputs any remaining buffers before we release it.
            if (codecNeedsEosPropagation) {
                // Do nothing.
            } else {
                if (DEBUG_LOG_ENABLED) {
                    d(DEBUG_LOG_TAG, getTrackTypeString(trackType) + ", queue input, flag=EOS")
                }
                codecReceivedEos = true
                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                resetInputBuffer()
            }
            codecDrainState = DRAIN_STATE_WAIT_END_OF_STREAM
            return false
        }

        if (codecNeedsAdaptationWorkaroundBuffer) {
            codecNeedsAdaptationWorkaroundBuffer = false
            buffer.data!!.put(ADAPTATION_WORKAROUND_BUFFER)
            codec.queueInputBuffer(inputIndex, 0, ADAPTATION_WORKAROUND_BUFFER.size, 0, 0)
            resetInputBuffer()
            codecReceivedBuffers = true
            return true
        }

        // For adaptive reconfiguration, decoders expect all reconfiguration data to be supplied at
        // the start of the buffer that also contains the first frame in the new format.
        if (codecReconfigurationState == RECONFIGURATION_STATE_WRITE_PENDING) {
            for (i in codecInputFormat!!.initializationData.indices) {
                val data = codecInputFormat!!.initializationData[i]
                buffer.data!!.put(data)
            }
            codecReconfigurationState = RECONFIGURATION_STATE_QUEUE_PENDING
        }
        val adaptiveReconfigurationBytes =
            buffer.data!!.position()

        val formatHolder = getFormatHolder()
        try {
            codec.useInputBuffer(
                Runnable {
                    readDataResultHolder.set(
                        readSource(
                            formatHolder,
                            buffer,  
                            0
                        )
                    )
                })
        } catch (e: InsufficientCapacityException) {
            onCodecError(e)
            // Skip the sample that's too large by reading it without its data. Then flush the codec so
            // that rendering will resume from the next key frame.
            readSourceOmittingSampleData( 0)
            flushCodec()
            return true
        }
        val result: @ReadDataResult Int = readDataResultHolder.get()

        if (result == C.RESULT_NOTHING_READ) {
            if (hasReadStreamToEnd()) {
                // Notify output queue of the last buffer's timestamp.
                this.lastOutputStreamInfo.lastBufferTimeUs =
                    this.largestValidQueuedPresentationTimeUs
            }
            return false
        }
        if (result == C.RESULT_FORMAT_READ) {
            if (codecReconfigurationState == RECONFIGURATION_STATE_QUEUE_PENDING) {
                // We received two formats in a row. Clear the current buffer of any reconfiguration data
                // associated with the first format.
                buffer.clear()
                codecReconfigurationState = RECONFIGURATION_STATE_WRITE_PENDING
            }
            onInputFormatChanged(formatHolder)
            return true
        }

        // We've read a buffer.
        if (buffer.isEndOfStream) {
            this.lastOutputStreamInfo.lastBufferTimeUs = this.largestValidQueuedPresentationTimeUs
            if (codecReconfigurationState == RECONFIGURATION_STATE_QUEUE_PENDING) {
                // We received a new format immediately before the end of the stream. We need to clear
                // the corresponding reconfiguration data from the current buffer, but re-write it into
                // a subsequent buffer if there are any (for example, if the user seeks backwards).
                buffer.clear()
                codecReconfigurationState = RECONFIGURATION_STATE_WRITE_PENDING
            }
            inputStreamEnded = true
            if (!codecReceivedBuffers) {
                processEndOfStream()
                return false
            }
            if (codecNeedsEosPropagation) {
                // Do nothing.
            } else {
                if (DEBUG_LOG_ENABLED) {
                    d(DEBUG_LOG_TAG, getTrackTypeString(trackType) + ", queue input, flag=EOS")
                }
                codecReceivedEos = true
                codec.queueInputBuffer(
                    inputIndex,  
                    0,  
                    0,  
                    0,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                resetInputBuffer()
            }
            return false
        }

        // This logic is required for cases where the decoder needs to be flushed or re-instantiated
        // during normal consumption of samples from the source (i.e., without a corresponding
        // Renderer.enable or Renderer.resetPosition call). This is necessary for certain legacy and
        // workaround behaviors, for example when switching the output Surface on API levels prior to
        // the introduction of MediaCodec.setOutputSurface, and when it's necessary to skip past a
        // sample that's too large to be held in one of the decoder's input buffers.
        if (!codecReceivedBuffers && !buffer.isKeyFrame) {
            buffer.clear()
            decoderCounters.skippedInputBufferCount++
            if (codecReconfigurationState == RECONFIGURATION_STATE_QUEUE_PENDING) {
                // The buffer we just cleared contained reconfiguration data. We need to re-write this data
                // into a subsequent buffer (if there is one).
                codecReconfigurationState = RECONFIGURATION_STATE_WRITE_PENDING
            }
            return true
        }

        var presentationTimeUs = buffer.timeUs

        if (shouldDiscardDecoderInputBuffer(buffer)) {
            if (DEBUG_LOG_ENABLED) {
                d(
                    DEBUG_LOG_TAG,
                    getTrackTypeString(trackType) + ", discard input, pts=" + presentationTimeUs
                )
            }
            return true
        }

        val bufferEncrypted = buffer.isEncrypted
        if (bufferEncrypted) {
            buffer.cryptoInfo.increaseClearDataFirstSubSampleBy(adaptiveReconfigurationBytes)
        }

        if (waitingForFirstSampleInFormat) {
            val lastStreamInfo =
                this.lastOutputStreamInfo
            lastStreamInfo.formatQueue.add(
                presentationTimeUs,
                inputFormat!!
            )
            lastStreamInfo.queuedBufferAfterReset = true
            waitingForFirstSampleInFormat = false
        }
        largestQueuedPresentationTimeUs = max(largestQueuedPresentationTimeUs, presentationTimeUs)
        val streamEndPositionUs = streamEndPositionUs
        if (streamEndPositionUs == C.TIME_UNSET
            || presentationTimeUs - this.lastOutputStreamInfo.streamOffsetUs < streamEndPositionUs
        ) {
            largestQueuedPresentationTimeWithinDurationUs =
                max(largestQueuedPresentationTimeWithinDurationUs, presentationTimeUs)
        }
        if (hasReadStreamToEnd() || buffer.isLastSample) {
            // Notify output queue of the last buffer's timestamp.
            this.lastOutputStreamInfo.lastBufferTimeUs = this.largestValidQueuedPresentationTimeUs
        }
        buffer.flip()
        if (buffer.hasSupplementalData()) {
            handleInputBufferSupplementalData(buffer)
        }

        if (hasSkippedFlushAndWaitingForQueueInputBuffer) {
            if (presentationTimeUs <= largestQueuedPresentationTimeUs) {
                skippedFlushOffsetUs += largestQueuedPresentationTimeUs - presentationTimeUs + 1
            }
            largestQueuedPresentationTimeUs = presentationTimeUs
            largestQueuedPresentationTimeWithinDurationUs = presentationTimeUs
            hasSkippedFlushAndWaitingForQueueInputBuffer = false
        }

        onQueueInputBuffer(buffer)
        val flags = getCodecBufferFlags(buffer)
        presentationTimeUs += skippedFlushOffsetUs
        if (bufferEncrypted) {
            codec.queueSecureInputBuffer(
                inputIndex,  0, buffer.cryptoInfo, presentationTimeUs, flags
            )
        } else {
            codec.queueInputBuffer(
                inputIndex,  
                0,
                buffer.data!!.limit(),
                presentationTimeUs,
                flags
            )
        }
        if (DEBUG_LOG_ENABLED) {
            d(
                DEBUG_LOG_TAG,
                (getTrackTypeString(trackType)
                        + ", queue input, pts="
                        + presentationTimeUs
                        + (if ((flags and MediaCodec.BUFFER_FLAG_DECODE_ONLY) != 0) ", flag=DECODE_ONLY" else ""))
            )
        }

        resetInputBuffer()
        codecReceivedBuffers = true
        codecReconfigurationState = RECONFIGURATION_STATE_NONE
        decoderCounters.queuedInputBufferCount++
        return true
    }

    private val largestValidQueuedPresentationTimeUs: Long
        get() = if (this.lastOutputStreamInfo.isDurationStrict)
            largestQueuedPresentationTimeWithinDurationUs
        else
            largestQueuedPresentationTimeUs

    @Throws(ExoPlaybackException::class)
    protected open fun maybeInitializeProcessingPipeline(format: Format): Boolean {
        // Do nothing.
        return true
    }

    protected open fun onCodecInitialized(
        name: String,
        configuration: MediaCodecAdapter.Configuration,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) {
        // Do nothing.
    }

    protected open fun onCodecReleased(name: String) {
        // Do nothing.
    }

    protected open fun onCodecError(codecError: Exception) {
        // Do nothing.
    }

    @CallSuper
    @Throws(ExoPlaybackException::class)
    protected open fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
        if (DEBUG_LOG_ENABLED) {
            d(
                DEBUG_LOG_TAG,
                (getTrackTypeString(trackType)
                        + ", input format changed="
                        + formatHolder.format)
            )
        }
        waitingForFirstSampleInFormat = true
        var newFormat = formatHolder.format!!
        if (newFormat.sampleMimeType == null) {
            // If the new format is invalid, it is either a media bug or it is not intended to be played.
            // See also https://github.com/google/ExoPlayer/issues/8283.
            throw createRendererException(
                IllegalArgumentException("Sample MIME type is null."),
                newFormat,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
            )
        }

        // Remove the initialization data (CSD) when decoding AV1 or VP9, as it is not required by
        // MediaCodec for these encodings, and some codec implementations fail if it's present.
        // * https://developer.android.com/reference/android/media/MediaCodec#CSD
        // * b/229399008#comment9
        // * https://github.com/androidx/media/issues/2408
        if ((newFormat.sampleMimeType == MimeTypes.VIDEO_AV1
                    || newFormat.sampleMimeType == MimeTypes.VIDEO_VP9
                    || (newFormat.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
                    && getDolbyVisionBaseLayerMimeType(newFormat) == MimeTypes.VIDEO_AV1))
            && !newFormat.initializationData.isEmpty()
        ) {
            newFormat = newFormat.buildUpon().setInitializationData(null).build()
        }

        setSourceDrmSession(formatHolder.drmSession)
        inputFormat = newFormat

        if (this.isBypassEnabled) {
            bypassDrainAndReinitialize = true
            return null // Need to drain batch buffer first.
        }

        if (codec == null) {
            availableCodecInfos = null
            maybeInitCodecOrBypass()
            return null
        }

        // We have an existing codec that we may need to reconfigure, re-initialize, or release to
        // switch to bypass. If the existing codec instance is kept then its operating rate and DRM
        // session may need to be updated.

        // Copy the current codec and codecInfo to local variables so they remain accessible if the
        // member variables are updated during the logic below.
        val codec = this.codec
        val codecInfo = this.codecInfo!!

        val oldFormat = codecInputFormat!!
        if (drmNeedsCodecReinitialization(
                codecInfo,
                newFormat,
                codecDrmSession,
                sourceDrmSession
            )
        ) {
            drainAndReinitializeCodec()
            return DecoderReuseEvaluation(
                codecInfo.name,
                oldFormat,
                newFormat,
                DecoderReuseEvaluation.REUSE_RESULT_NO,
                DecoderReuseEvaluation.DISCARD_REASON_DRM_SESSION_CHANGED
            )
        }
        val drainAndUpdateCodecDrmSession = sourceDrmSession !== codecDrmSession

        val evaluation =
            canReuseCodec(
                codecInfo,
                oldFormat,
                newFormat,  
                this.lastOutputStreamInfo.queuedBufferAfterReset
            )
        var overridingDiscardReasons: @DecoderDiscardReasons Int = 0
        when (evaluation.result) {
            DecoderReuseEvaluation.REUSE_RESULT_NO -> drainAndReinitializeCodec()
            DecoderReuseEvaluation.REUSE_RESULT_YES_WITH_FLUSH -> {
                updateCodecOperatingRate(newFormat)
                codecInputFormat = newFormat
                if (drainAndUpdateCodecDrmSession) {
                    if (!drainAndUpdateCodecDrmSession()) {
                        overridingDiscardReasons =
                            overridingDiscardReasons or DecoderReuseEvaluation.DISCARD_REASON_WORKAROUND
                    }
                } else if (!drainAndFlushCodec()) {
                    overridingDiscardReasons =
                        overridingDiscardReasons or DecoderReuseEvaluation.DISCARD_REASON_WORKAROUND
                }
            }

            DecoderReuseEvaluation.REUSE_RESULT_YES_WITH_RECONFIGURATION -> {
                updateCodecOperatingRate(newFormat)
                codecReconfigured = true
                codecReconfigurationState = RECONFIGURATION_STATE_WRITE_PENDING
                codecNeedsAdaptationWorkaroundBuffer =
                    codecAdaptationWorkaroundMode == ADAPTATION_WORKAROUND_MODE_ALWAYS
                            || (codecAdaptationWorkaroundMode == ADAPTATION_WORKAROUND_MODE_SAME_RESOLUTION && newFormat.width == oldFormat.width && newFormat.height == oldFormat.height)
                codecInputFormat = newFormat
                if (drainAndUpdateCodecDrmSession && !drainAndUpdateCodecDrmSession()) {
                    overridingDiscardReasons =
                        overridingDiscardReasons or DecoderReuseEvaluation.DISCARD_REASON_WORKAROUND
                }
            }

            DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION -> {
                updateCodecOperatingRate(newFormat)
                codecInputFormat = newFormat
                if (drainAndUpdateCodecDrmSession && !drainAndUpdateCodecDrmSession()) {
                    overridingDiscardReasons =
                        overridingDiscardReasons or DecoderReuseEvaluation.DISCARD_REASON_WORKAROUND
                }
            }

            else -> throw IllegalStateException() // Never happens.

        }

        if (evaluation.result != DecoderReuseEvaluation.REUSE_RESULT_NO
            && (this.codec !== codec || codecDrainAction == DRAIN_ACTION_REINITIALIZE)
        ) {
            // Initial evaluation indicated reuse was possible, but codec re-initialization was triggered.
            // The reasons are indicated by overridingDiscardReasons.
            return DecoderReuseEvaluation(
                codecInfo.name,
                oldFormat,
                newFormat,
                DecoderReuseEvaluation.REUSE_RESULT_NO,
                overridingDiscardReasons
            )
        }

        return evaluation
    }

    @Throws(ExoPlaybackException::class)
    protected open fun onOutputFormatChanged(format: Format, mediaFormat: MediaFormat?) {
        // Do nothing.
    }

    @Throws(ExoPlaybackException::class)
    protected open fun handleInputBufferSupplementalData(buffer: DecoderInputBuffer) {
        // Do nothing.
    }

    @Throws(ExoPlaybackException::class)
    protected open fun onQueueInputBuffer(buffer: DecoderInputBuffer) {
        // Do nothing.
    }

    protected open fun getCodecBufferFlags(buffer: DecoderInputBuffer): Int {
        return 0
    }

    protected fun shouldSkipDecoderInputBuffer(buffer: DecoderInputBuffer): Boolean {
        return false
    }

    protected open fun shouldDiscardDecoderInputBuffer(buffer: DecoderInputBuffer): Boolean {
        if (shouldSkipDecoderInputBuffer(buffer)) {
            buffer.clear()
            decoderCounters.skippedInputBufferCount += 1
            return true
        }
        return false
    }

    @CallSuper
    protected open fun onProcessedOutputBuffer(presentationTimeUs: Long) {
        // Use max even though lastProcessedOutputBufferTimeUs should be increasing for tests because
        // test codecs do not reorder samples.
        lastProcessedOutputBufferTimeUs = max(presentationTimeUs, lastProcessedOutputBufferTimeUs)
        while (!pendingOutputStreamChanges.isEmpty()
            && presentationTimeUs >= pendingOutputStreamChanges.peek().previousStreamLastBufferTimeUs
        ) {
            setOutputStreamInfo(pendingOutputStreamChanges.poll()!!)
            onProcessedStreamChange()
        }
    }

    protected open fun onProcessedStreamChange() {
        // Do nothing.
    }

    protected open fun canReuseCodec(
        codecInfo: MediaCodecInfo,
        oldFormat: Format,
        newFormat: Format,
        isAdaptiveFormatChange: Boolean
    ): DecoderReuseEvaluation {
        return DecoderReuseEvaluation(
            codecInfo.name,
            oldFormat,
            newFormat,
            DecoderReuseEvaluation.REUSE_RESULT_NO,
            DecoderReuseEvaluation.DISCARD_REASON_REUSE_NOT_IMPLEMENTED
        )
    }

    protected open fun onOutputStreamOffsetUsChanged(outputStreamOffsetUs: Long) {
        // Do nothing
    }

    override val isEnded: Boolean
        get() = outputStreamEnded
    override val isReady: Boolean
        get() = this.isReadyForDecoding

    protected val isReadyForDecoding: Boolean
        get() = inputFormat != null
                && (isSourceReady
                || hasOutputBuffer()
                || (codecHotswapDeadlineMs != C.TIME_UNSET
                && getClock().elapsedRealtime() < codecHotswapDeadlineMs))

    protected open fun getCodecOperatingRateV23(
        targetPlaybackSpeed: Float, format: Format, streamFormats: Array<Format>
    ): Float {
        return CODEC_OPERATING_RATE_UNSET
    }

    protected fun updateCodecOperatingRate() {
        updateCodecOperatingRate(codecInputFormat)
    }

    private fun updateCodecOperatingRate(format: Format?) {
        if (codec == null || codecDrainAction == DRAIN_ACTION_REINITIALIZE || state == Renderer.STATE_DISABLED) {
            // No need to update the operating rate.
            return
        }

        val newCodecOperatingRate =
            getCodecOperatingRateV23(
                targetPlaybackSpeed,
                format!!,
                getStreamFormats()
            )
        if (codecOperatingRate == newCodecOperatingRate) {
            // No change.
            return
        } else if (newCodecOperatingRate == CODEC_OPERATING_RATE_UNSET) {
            // Do not clear the operating rate by reinitializing the codec. We assume we will get
            // an update soon and can update the current codec to a good value.
            return
        } else if (codecOperatingRate != CODEC_OPERATING_RATE_UNSET
            || newCodecOperatingRate > assumedMinimumCodecOperatingRate
        ) {
            // We need to set the operating rate, either because we've set it previously or because it's
            // above the assumed minimum rate.
            val codecParameters = Bundle()
            codecParameters.putFloat(MediaFormat.KEY_OPERATING_RATE, newCodecOperatingRate)
            codec!!.setParameters(codecParameters)
            codecOperatingRate = newCodecOperatingRate
        }
    }

    private fun drainAndFlushCodec(): Boolean {
        if (codecReceivedBuffers) {
            codecDrainState = DRAIN_STATE_SIGNAL_END_OF_STREAM
            if (shouldReleaseCodecInsteadOfFlushing()) {
                codecDrainAction = DRAIN_ACTION_REINITIALIZE
                return false
            } else {
                codecDrainAction = DRAIN_ACTION_FLUSH
            }
        }
        return true
    }

    @Throws(ExoPlaybackException::class)
    private fun drainAndUpdateCodecDrmSession(): Boolean {
        if (codecReceivedBuffers) {
            codecDrainState = DRAIN_STATE_SIGNAL_END_OF_STREAM
            if (shouldReleaseCodecInsteadOfFlushing()) {
                codecDrainAction = DRAIN_ACTION_REINITIALIZE
                return false
            } else {
                codecDrainAction = DRAIN_ACTION_FLUSH_AND_UPDATE_DRM_SESSION
            }
        } else {
            // Nothing has been queued to the decoder, so we can do the update immediately.
            updateDrmSession()
        }
        return true
    }

    @Throws(ExoPlaybackException::class)
    private fun drainAndReinitializeCodec() {
        if (codecReceivedBuffers) {
            codecDrainState = DRAIN_STATE_SIGNAL_END_OF_STREAM
            codecDrainAction = DRAIN_ACTION_REINITIALIZE
        } else {
            // Nothing has been queued to the decoder, so we can re-initialize immediately.
            reinitializeCodec()
        }
    }

    @Throws(ExoPlaybackException::class)
    private fun drainOutputBuffer(positionUs: Long, elapsedRealtimeUs: Long): Boolean {
        val codec = this.codec!!
        if (!hasOutputBuffer()) {
            val outputIndex = codec.dequeueOutputBufferIndex(outputBufferInfo)
            if (outputIndex < 0) {
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ) {
                    processOutputMediaFormatChanged()
                    return true
                }
                // MediaCodec.INFO_TRY_AGAIN_LATER (-1) or unknown negative return value.
                if (codecNeedsEosPropagation
                    && (inputStreamEnded || codecDrainState == DRAIN_STATE_WAIT_END_OF_STREAM)
                ) {
                    processEndOfStream()
                }
                if (lastOutputBufferProcessedRealtimeMs != C.TIME_UNSET
                    && lastOutputBufferProcessedRealtimeMs + 100 < getClock().currentTimeMillis()
                ) {
                    // We processed the last output buffer more than 100ms ago without
                    // receiving an EOS buffer. This is likely a misbehaving codec, so
                    // process the end of stream manually. See b/359634542.
                    processEndOfStream()
                }
                return false
            }

            // We've dequeued a buffer.
            outputBufferInfo.presentationTimeUs -= skippedFlushOffsetUs
            if (shouldSkipAdaptationWorkaroundOutputBuffer) {
                shouldSkipAdaptationWorkaroundOutputBuffer = false
                codec.releaseOutputBuffer(outputIndex, false)
                return true
            } else if (outputBufferInfo.size == 0
                && (outputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
            ) {
                // The dequeued buffer indicates the end of the stream. Process it immediately.
                processEndOfStream()
                return false
            }

            this.outputIndex = outputIndex
            outputBuffer = codec.getOutputBuffer(outputIndex)

            // The dequeued buffer is a media buffer. Do some initial setup.
            // It will be processed by calling processOutputBuffer (possibly multiple times).
            if (outputBuffer != null) {
                outputBuffer!!.position(outputBufferInfo.offset)
                outputBuffer!!.limit(outputBufferInfo.offset + outputBufferInfo.size)
            }
            updateOutputFormatForTime(outputBufferInfo.presentationTimeUs)
            if (DEBUG_LOG_ENABLED) {
                d(
                    DEBUG_LOG_TAG,
                    (getTrackTypeString(trackType)
                            + ", dequeue output, pts="
                            + outputBufferInfo.presentationTimeUs)
                )
            }
        }

        val isStrictDurationExceeded =
            outputStreamInfo.isDurationStrict
                    && outputStreamInfo.durationUs != C.TIME_UNSET && (outputBufferInfo.presentationTimeUs - this.outputStreamOffsetUs
                    >= outputStreamInfo.durationUs)
        isDecodeOnlyOutputBuffer =
            hasSkippedFlushAndWaitingForQueueInputBuffer
                    || outputBufferInfo.presentationTimeUs < lastResetPositionUs || isStrictDurationExceeded
        val isLastOutputBuffer =
            outputStreamInfo.lastBufferTimeUs != C.TIME_UNSET && outputStreamInfo.lastBufferTimeUs <= outputBufferInfo.presentationTimeUs && !isStrictDurationExceeded

        val processedOutputBuffer =
            processOutputBuffer(
                positionUs,
                elapsedRealtimeUs,
                codec,
                outputBuffer!!,
                outputIndex,
                outputBufferInfo.flags,  
                1,
                outputBufferInfo.presentationTimeUs,
                isDecodeOnlyOutputBuffer,
                isLastOutputBuffer,
                outputFormat!!
            )
        if (processedOutputBuffer) {
            onProcessedOutputBuffer(outputBufferInfo.presentationTimeUs)
            val isEndOfStream =
                (outputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
            if (!isEndOfStream && codecReceivedEos && isLastOutputBuffer) {
                lastOutputBufferProcessedRealtimeMs = getClock().currentTimeMillis()
            }
            resetOutputBuffer()
            if (!isEndOfStream) {
                return true
            }
            processEndOfStream()
        }

        return false
    }

    private fun processOutputMediaFormatChanged() {
        codecHasOutputMediaFormat = true
        val mediaFormat = codec!!.outputFormat!!
        if (codecAdaptationWorkaroundMode != ADAPTATION_WORKAROUND_MODE_NEVER && mediaFormat.getInteger(
                MediaFormat.KEY_WIDTH
            ) == ADAPTATION_WORKAROUND_SLICE_WIDTH_HEIGHT && (mediaFormat.getInteger(MediaFormat.KEY_HEIGHT)
                    == ADAPTATION_WORKAROUND_SLICE_WIDTH_HEIGHT)
        ) {
            // We assume this format changed event was caused by the adaptation workaround.
            shouldSkipAdaptationWorkaroundOutputBuffer = true
            return
        }
        if (Build.VERSION.SDK_INT >= 29) {
            checkAndNotifyCodecParameterChanges(mediaFormat)
        }
        codecOutputMediaFormat = mediaFormat
        codecOutputMediaFormatChanged = true
    }

    @Throws(ExoPlaybackException::class)
    protected abstract fun processOutputBuffer(
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
    ): Boolean

    protected abstract fun onCodecParametersChanged(codecParameters: CodecParameters?)

    @Throws(ExoPlaybackException::class)
    protected open fun renderToEndOfStream() {
        // Do nothing.
    }

    @Throws(ExoPlaybackException::class)
    private fun processEndOfStream() {
        if (DEBUG_LOG_ENABLED) {
            d(DEBUG_LOG_TAG, getTrackTypeString(trackType) + ", output process EOS")
        }
        when (codecDrainAction) {
            DRAIN_ACTION_REINITIALIZE -> reinitializeCodec()
            DRAIN_ACTION_FLUSH_AND_UPDATE_DRM_SESSION -> {
                flushCodec()
                updateDrmSession()
            }

            DRAIN_ACTION_FLUSH -> flushCodec()
            DRAIN_ACTION_NONE -> {
                outputStreamEnded = true
                renderToEndOfStream()
            }

            else -> {
                outputStreamEnded = true
                renderToEndOfStream()
            }
        }
    }

    protected fun setPendingOutputEndOfStream() {
        pendingOutputEndOfStream = true
    }

    protected val lastBufferInStreamPresentationTimeUs: Long
        get() = outputStreamInfo.lastBufferTimeUs

    protected val outputStreamOffsetUs: Long
        get() = outputStreamInfo.streamOffsetUs

    protected val outputStreamStartPositionUs: Long
        get() = outputStreamInfo.startPositionUs

    protected fun applyCodecParametersToMediaFormat(mediaFormat: MediaFormat) {
        if (Build.VERSION.SDK_INT >= 29) {
            activeCodecParameters.applyTo(mediaFormat)
        }
    }

    private fun setOutputStreamInfo(outputStreamInfo: OutputStreamInfo) {
        if (DEBUG_LOG_ENABLED && outputStreamInfo.streamOffsetUs != C.TIME_UNSET) {
            d(
                DEBUG_LOG_TAG,
                (getTrackTypeString(trackType)
                        + ", output stream change, start="
                        + outputStreamInfo.startPositionUs
                        + ", offset="
                        + outputStreamInfo.streamOffsetUs)
            )
        }
        this.outputStreamInfo = outputStreamInfo
        if (outputStreamInfo.streamOffsetUs != C.TIME_UNSET) {
            needToNotifyOutputFormatChangeAfterStreamChange = true
            onOutputStreamOffsetUsChanged(outputStreamInfo.streamOffsetUs)
        }
    }

    private val lastOutputStreamInfo: OutputStreamInfo
        get() {
            if (!pendingOutputStreamChanges.isEmpty()) {
                return pendingOutputStreamChanges.getLast()
            }
            return outputStreamInfo
        }

    @Throws(ExoPlaybackException::class)
    private fun drmNeedsCodecReinitialization(
        codecInfo: MediaCodecInfo,
        newFormat: Format,
        oldSession: DrmSession?,
        newSession: DrmSession?
    ): Boolean {
        if (oldSession === newSession) {
            // No need to re-initialize if the old and new sessions are the same.
            return false
        }

        // Note: At least one of oldSession and newSession are non-null.
        if (newSession == null || oldSession == null) {
            // Changing from DRM to no DRM and vice-versa always requires re-initialization.
            return true
        }

        val newCryptoConfig = newSession.cryptoConfig
        if (newCryptoConfig == null) {
            // We'd only expect this to happen if the CDM from which newSession is obtained needs
            // provisioning. This is unlikely to happen (it probably requires a switch from one DRM scheme
            // to another, where the new CDM hasn't been used before and needs provisioning). It would be
            // possible to handle this case without codec re-initialization, but it would require the
            // re-use code path to be able to wait for provisioning to finish before calling
            // MediaCrypto.setMediaDrmSession. The extra complexity is not warranted given how unlikely
            // the case is to occur, so we re-initialize in this case.
            return true
        }

        val oldCryptoConfig = oldSession.cryptoConfig
        if (oldCryptoConfig == null || newCryptoConfig.javaClass != oldCryptoConfig.javaClass) {
            // Switching between different CryptoConfig implementations suggests we're switching between
            // different forms of decryption (e.g. in-app vs framework-provided), which requires codec
            // re-initialization.
            return true
        }

        if (newCryptoConfig !is FrameworkCryptoConfig) {
            // Assume that non-framework CryptoConfig implementations indicate the codec can be re-used
            // (since it suggests that decryption is happening in-app before the data is passed to
            // MediaCodec, and therefore a change in DRM keys has no affect on the (decrypted) data seen
            // by MediaCodec).
            return false
        }

        // Note: Both oldSession and newSession are non-null, and they are different sessions.
        if (newSession.schemeUuid != (oldSession.schemeUuid)) {
            // MediaCrypto.setMediaDrmSession is unable to switch between DRM schemes.
            return true
        }

        if (C.PLAYREADY_UUID == oldSession.schemeUuid
            || C.PLAYREADY_UUID == newSession.schemeUuid
        ) {
            // The PlayReady CDM does not support MediaCrypto.setMediaDrmSession, either as the old or new
            // session.
            // TODO: Add an API check once [Internal ref: b/128835874] is fixed.
            return true
        }

        // Re-initialization is required if newSession might require switching to the secure output
        // path. We assume newSession might require a secure decoder if it's not fully open yet.
        return !codecInfo.secure
                && (newSession.state === DrmSession.STATE_OPENING
                || ((newSession.state === DrmSession.STATE_OPENED
                || newSession.state === DrmSession.STATE_OPENED_WITH_KEYS)
                && newSession.requiresSecureDecoder(newFormat.sampleMimeType!!)))
    }

    @Throws(ExoPlaybackException::class)
    private fun reinitializeCodec() {
        releaseCodec()
        maybeInitCodecOrBypass()
    }

    @Throws(ExoPlaybackException::class)
    private fun updateDrmSession() {
        val cryptoConfig = sourceDrmSession!!.cryptoConfig
        if (cryptoConfig is FrameworkCryptoConfig) {
            try {
                mediaCrypto!!.setMediaDrmSession(cryptoConfig.sessionId!!)
            } catch (e: MediaCryptoException) {
                throw createRendererException(
                    e, inputFormat, PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR
                )
            }
        }
        setCodecDrmSession(sourceDrmSession)
        codecDrainState = DRAIN_STATE_NONE
        codecDrainAction = DRAIN_ACTION_NONE
    }

    @Throws(ExoPlaybackException::class)
    private fun bypassRender(positionUs: Long, elapsedRealtimeUs: Long): Boolean {
        // Process any batched data.

        Preconditions.checkState(!outputStreamEnded)
        if (bypassBatchBuffer.hasSamples()) {
            if (processOutputBuffer(
                    positionUs,
                    elapsedRealtimeUs,  
                    null,
                    bypassBatchBuffer.data!!,
                    outputIndex,  
                    0,
                    bypassBatchBuffer.sampleCount,
                    bypassBatchBuffer.firstSampleTimeUs,
                    isDecodeOnly(lastResetPositionUs, bypassBatchBuffer.lastSampleTimeUs),
                    bypassBatchBuffer.isEndOfStream,
                    outputFormat!!
                )
            ) {
                // The batch buffer has been fully processed.
                onProcessedOutputBuffer(bypassBatchBuffer.lastSampleTimeUs)
                bypassBatchBuffer.clear()
            } else {
                // Could not process the whole batch buffer. Try again later.
                return false
            }
        }

        // Process end of stream, if reached.
        if (inputStreamEnded) {
            outputStreamEnded = true
            return false
        }

        if (bypassSampleBufferPending) {
            Preconditions.checkState(bypassBatchBuffer.append(bypassSampleBuffer))
            bypassSampleBufferPending = false
        }

        if (bypassDrainAndReinitialize) {
            if (bypassBatchBuffer.hasSamples()) {
                // This can only happen if bypassSampleBufferPending was true above. Return true to try and
                // immediately process the sample, which has now been appended to the batch buffer.
                return true
            }
            // The new format might require using a codec rather than bypass.
            disableBypass()
            bypassDrainAndReinitialize = false
            maybeInitCodecOrBypass()
            if (!this.isBypassEnabled) {
                // We're no longer in bypass mode.
                return false
            }
        }

        // Read from the input, appending any sample buffers to the batch buffer.
        bypassRead()

        if (bypassBatchBuffer.hasSamples()) {
            bypassBatchBuffer.flip()
        }

        // We can make more progress if we have batched data, an EOS, or a re-initialization to process
        // (note that one or more of the code blocks above will be executed during the next call).
        return bypassBatchBuffer.hasSamples() || inputStreamEnded || bypassDrainAndReinitialize
    }

    @Throws(ExoPlaybackException::class)
    private fun bypassRead() {
        Preconditions.checkState(!inputStreamEnded)
        val formatHolder = getFormatHolder()
        bypassSampleBuffer.clear()
        while (true) {
            bypassSampleBuffer.clear()
            val result: @ReadDataResult Int =
                readSource(formatHolder, bypassSampleBuffer,  0)
            when (result) {
                C.RESULT_FORMAT_READ -> {
                    onInputFormatChanged(formatHolder)
                    return
                }

                C.RESULT_NOTHING_READ -> {
                    if (hasReadStreamToEnd()) {
                        // Notify output queue of the last buffer's timestamp.
                        this.lastOutputStreamInfo.lastBufferTimeUs = largestQueuedPresentationTimeUs
                    }
                    return
                }

                C.RESULT_BUFFER_READ -> {
                    if (bypassSampleBuffer.isEndOfStream) {
                        inputStreamEnded = true
                        this.lastOutputStreamInfo.lastBufferTimeUs = largestQueuedPresentationTimeUs
                        return
                    }
                    largestQueuedPresentationTimeUs =
                        max(largestQueuedPresentationTimeUs, bypassSampleBuffer.timeUs)
                    if (hasReadStreamToEnd() || buffer.isLastSample) {
                        // Notify output queue of the last buffer's timestamp.
                        this.lastOutputStreamInfo.lastBufferTimeUs = largestQueuedPresentationTimeUs
                    }
                    if (waitingForFirstSampleInFormat) {
                        // This is the first buffer in a new format, the output format must be updated.
                        outputFormat = inputFormat!!
                        if (outputFormat!!.sampleMimeType == MimeTypes.AUDIO_OPUS
                            && !outputFormat!!.initializationData.isEmpty()
                        ) {
                            // Format mimetype is Opus so format should be updated with preSkip data.
                            // TODO(b/298634018): Adjust encoderDelay value based on starting position.
                            val numberPreSkipSamples =
                                getPreSkipSamples(outputFormat!!.initializationData[0])
                            outputFormat =
                                outputFormat!!.buildUpon().setEncoderDelay(numberPreSkipSamples)
                                    .build()
                        }
                        onOutputFormatChanged(outputFormat!!,  null)
                        waitingForFirstSampleInFormat = false
                    }
                    // Try to append the buffer to the batch buffer.
                    bypassSampleBuffer.flip()

                    if (outputFormat != null
                        && outputFormat!!.sampleMimeType == MimeTypes.AUDIO_OPUS
                    ) {
                        if (bypassSampleBuffer.hasSupplementalData()) {
                            // Set format on sample buffer so that it contains the mimetype and encodingDelay.
                            bypassSampleBuffer.format = outputFormat
                            handleInputBufferSupplementalData(bypassSampleBuffer)
                        }
                        if (needToDecodeOpusFrame(
                                lastResetPositionUs, bypassSampleBuffer.timeUs
                            )
                        ) {
                            // Packetize as long as frame does not precede the last reset position by more than
                            // seek-preroll.
                            oggOpusAudioPacketizer.packetize(
                                bypassSampleBuffer,
                                outputFormat!!.initializationData
                            )
                        }
                    }
                    if (!haveBypassBatchBufferAndNewSampleSameDecodeOnlyState()
                        || !bypassBatchBuffer.append(bypassSampleBuffer)
                    ) {
                        bypassSampleBufferPending = true
                        return
                    }
                }

                else -> throw IllegalStateException()
            }
        }
    }

    private fun haveBypassBatchBufferAndNewSampleSameDecodeOnlyState(): Boolean {
        // TODO: b/295800114 - Splitting the batch buffer by decode-only state isn't safe for formats
        // where not every sample is a keyframe because the downstream component may receive encoded
        // data starting from a non-keyframe sample.
        if (!bypassBatchBuffer.hasSamples()) {
            return true
        }
        val lastResetPositionUs = lastResetPositionUs
        val batchBufferIsDecodeOnly =
            isDecodeOnly(lastResetPositionUs, bypassBatchBuffer.lastSampleTimeUs)
        val sampleBufferIsDecodeOnly = isDecodeOnly(lastResetPositionUs, bypassSampleBuffer.timeUs)
        return batchBufferIsDecodeOnly == sampleBufferIsDecodeOnly
    }

    private fun isDecodeOnly(startTimeUs: Long, frameTimeUs: Long): Boolean {
        // Opus frames that precede the target position by more than seek-preroll should be skipped.
        return frameTimeUs < startTimeUs
                && (outputFormat == null || (outputFormat!!.sampleMimeType != MimeTypes.AUDIO_OPUS) || !needToDecodeOpusFrame( 
            startTimeUs,  frameTimeUs
        ))
    }

    private fun codecAdaptationWorkaroundMode(name: String?): @AdaptationWorkaroundMode Int {
        if (!enableWorkarounds()) {
            return ADAPTATION_WORKAROUND_MODE_NEVER
        }
        if (Build.VERSION.SDK_INT <= 25 && "OMX.Exynos.avc.dec.secure" == name
            && (Build.MODEL.startsWith("SM-T585")
                    || Build.MODEL.startsWith("SM-A510")
                    || Build.MODEL.startsWith("SM-A520")
                    || Build.MODEL.startsWith("SM-J700"))
        ) {
            return ADAPTATION_WORKAROUND_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT < 24 && ("OMX.Nvidia.h264.decode" == name || "OMX.Nvidia.h264.decode.secure" == name)
            && ("flounder" == Build.DEVICE
                    || "flounder_lte" == Build.DEVICE
                    || "grouper" == Build.DEVICE
                    || "tilapia" == Build.DEVICE)
        ) {
            return ADAPTATION_WORKAROUND_MODE_SAME_RESOLUTION
        } else {
            return ADAPTATION_WORKAROUND_MODE_NEVER
        }
    }

    private class OutputStreamInfo(
        val previousStreamLastBufferTimeUs: Long,
        val startPositionUs: Long,
        val streamOffsetUs: Long,
        var durationUs: Long,
        var streamFlags: @SampleStream.Flags Int
    ) {
        val formatQueue: TimedValueQueue<Format>

        var queuedBufferAfterReset = false
        var lastBufferTimeUs: Long

        init {
            this.formatQueue = TimedValueQueue<Format>()
            this.lastBufferTimeUs = C.TIME_UNSET
        }

        val isDurationStrict: Boolean
            get() = (streamFlags and SampleStream.FLAG_STRICT_DURATION) != 0

        companion object {
            val UNSET = OutputStreamInfo( 
                C.TIME_UNSET,  
                C.TIME_UNSET,  
                C.TIME_UNSET,  
                C.TIME_UNSET,  
                0
            )
        }
    }

    @RequiresApi(31)
    private object Api31 {
        fun setLogSessionIdToMediaCodecFormat(
            codecConfiguration: MediaCodecAdapter.Configuration, playerId: PlayerId
        ) {
            val logSessionId = playerId.logSessionId
            if (logSessionId != LogSessionId.LOG_SESSION_ID_NONE) {
                codecConfiguration.mediaFormat!!.setString(
                    "log-session-id",
                    logSessionId.getStringId()
                )
            }
        }
    }

    private inner class MediaCodecRendererCodecAdapterListener

        : MediaCodecAdapter.OnBufferAvailableListener {
        override fun onInputBufferAvailable() {
            if (wakeupListener != null) {
                wakeupListener!!.onWakeup()
            }
        }

        override fun onOutputBufferAvailable() {
            if (wakeupListener != null) {
                wakeupListener!!.onWakeup()
            }
        }
    }

    companion object {

        protected const val DEBUG_LOG_ENABLED: Boolean = false

        protected const val DEBUG_LOG_TAG: String = "MCRdebug"

        protected const val CODEC_OPERATING_RATE_UNSET: Float = -1f

        private const val TAG = "MediaCodecRenderer"

        private const val MAX_CODEC_HOTSWAP_TIME_MS: Long = 1000

        private const val RECONFIGURATION_STATE_NONE = 0

        private const val RECONFIGURATION_STATE_WRITE_PENDING = 1

        private const val RECONFIGURATION_STATE_QUEUE_PENDING = 2

        private const val DRAIN_STATE_NONE = 0

        private const val DRAIN_STATE_SIGNAL_END_OF_STREAM = 1

        private const val DRAIN_STATE_WAIT_END_OF_STREAM = 2

        private const val DRAIN_ACTION_NONE = 0

        private const val DRAIN_ACTION_FLUSH = 1

        private const val DRAIN_ACTION_FLUSH_AND_UPDATE_DRM_SESSION = 2

        private const val DRAIN_ACTION_REINITIALIZE = 3

        private const val ADAPTATION_WORKAROUND_MODE_NEVER = 0

        private const val ADAPTATION_WORKAROUND_MODE_SAME_RESOLUTION = 1

        private const val ADAPTATION_WORKAROUND_MODE_ALWAYS = 2

        private val ADAPTATION_WORKAROUND_BUFFER = byteArrayOf(
            0,
            0,
            1,
            103,
            66,
            -64,
            11,
            -38,
            37,
            -112,
            0,
            0,
            1,
            104,
            -50,
            15,
            19,
            32,
            0,
            0,
            1,
            101,
            -120,
            -124,
            13,
            -50,
            113,
            24,
            -96,
            0,
            47,
            -65,
            28,
            49,
            -61,
            39,
            93,
            120
        )

        private const val ADAPTATION_WORKAROUND_SLICE_WIDTH_HEIGHT = 32

        fun supportsFormatDrm(format: Format): Boolean {
            return format.cryptoType == C.CRYPTO_TYPE_NONE || format.cryptoType == C.CRYPTO_TYPE_FRAMEWORK
        }

        private fun isMediaCodecException(error: IllegalStateException): Boolean {
            if (error is CodecException) {
                return true
            }
            val stackTrace = error.getStackTrace()
            return stackTrace.size > 0 && stackTrace[0]!!.getClassName() == "android.media.MediaCodec"
        }

        private fun codecNeedsSosFlushWorkaround(name: String?): Boolean {
            if (!enableWorkarounds()) {
                return false
            }
            return Build.VERSION.SDK_INT == 29 && "c2.android.aac.decoder" == name
        }

        // TODO: b/416719590 - Remove this suppression when the false positive is fixed.
        private fun codecNeedsEosPropagationWorkaround(codecInfo: MediaCodecInfo): Boolean {
            if (!enableWorkarounds()) {
                return false
            }
            val name = codecInfo.name
            return (Build.VERSION.SDK_INT <= 25 && "OMX.rk.video_decoder.avc" == name)
                    || (Build.VERSION.SDK_INT <= 29
                    && ("OMX.broadcom.video_decoder.tunnel" == name
                    || "OMX.broadcom.video_decoder.tunnel.secure" == name
                    || "OMX.bcm.vdec.avc.tunnel" == name
                    || "OMX.bcm.vdec.avc.tunnel.secure" == name
                    || "OMX.bcm.vdec.hevc.tunnel" == name
                    || "OMX.bcm.vdec.hevc.tunnel.secure" == name))
                    || ("Amazon" == Build.MANUFACTURER && "AFTS" == Build.MODEL && codecInfo.secure)
        }

        private fun codecNeedsEosFlushWorkaround(name: String?): Boolean {
            if (!enableWorkarounds()) {
                return false
            }
            return Build.VERSION.SDK_INT == 23 && "OMX.google.vorbis.decoder" == name
        }
    }
}
