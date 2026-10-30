package androidx.media3.exoplayer.audio

import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.CallSuper
import androidx.annotation.IntDef
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes.isAudio
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.Log.e
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.TraceUtil.beginSection
import androidx.media3.common.util.TraceUtil.endSection

import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.Decoder
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.MediaClock
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.Renderer.WakeupListener
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioSink.AudioSinkConfig
import androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig
import androidx.media3.exoplayer.audio.AudioSink.SinkFormatSupport
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.SampleStream.ReadDataResult
import com.google.common.base.MoreObjects
import com.google.common.base.Preconditions
import com.google.common.primitives.ImmutableIntArray
import com.google.errorprone.annotations.ForOverride

import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.min

abstract class DecoderAudioRenderer<T : Decoder<DecoderInputBuffer?, out SimpleDecoderOutputBuffer, out DecoderException>?>(
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener,
    private val audioSink: AudioSink
) : BaseRenderer(C.TRACK_TYPE_AUDIO), MediaClock, Decoder.Callback {
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [REINITIALIZATION_STATE_NONE, REINITIALIZATION_STATE_SIGNAL_END_OF_STREAM, REINITIALIZATION_STATE_WAIT_END_OF_STREAM]
    )
    private annotation class ReinitializationState

    private val eventDispatcher =
        AudioRendererEventListener.EventDispatcher(eventHandler, eventListener)
    private val flagsOnlyBuffer = DecoderInputBuffer.newNoDataInstance()
    private var decoderCounters: DecoderCounters? = null
    private var inputFormat: Format? = null
    private var encoderDelay = 0
    private var encoderPadding = 0
    private var firstStreamSampleRead = false
    private var decoder: T? = null
    private var inputBuffer: DecoderInputBuffer? = null
    private var outputBuffer: SimpleDecoderOutputBuffer? = null
    private var decoderDrmSession: DrmSession? = null
    private var sourceDrmSession: DrmSession? = null
    private var decoderReinitializationState: @ReinitializationState Int = REINITIALIZATION_STATE_NONE
    private var decoderReceivedBuffers = false
    private var audioTrackNeedsConfigure = true
    private var currentPositionUs: Long = 0
    private var allowPositionDiscontinuity = false
    private var inputStreamEnded = false
    private var outputStreamEnded = false
    private var outputStreamOffsetUs: Long = 0
    private val pendingOutputStreamOffsetsUs= LongArray(MAX_PENDING_OUTPUT_STREAM_OFFSET_COUNT)
    private var pendingOutputStreamOffsetCount = 0
    private var hasPendingReportedSkippedSilence = false
    private var hasReportedAudioPositionAdvancing = false
    private var isStarted = false
    private var largestQueuedPresentationTimeUs = C.TIME_UNSET
    private var lastBufferInStreamPresentationTimeUs = C.TIME_UNSET
    private var nextBufferToWritePresentationTimeUs = C.TIME_UNSET
    private var firstNotReadyTimeMs = C.TIME_UNSET
    private var hasBeenReady= false
    private var wakeupListener: WakeupListener? = null
    private var playbackThreadExecutor: Executor? = null

    constructor(
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        audioCapabilities: AudioCapabilities? = null,
        audioProcessors: Array<AudioProcessor>
    ) : this(
        eventHandler,
        eventListener,
        DefaultAudioSink.Builder()
            .setAudioCapabilities(
                audioCapabilities ?: AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES,
            )
            .setAudioProcessors(audioProcessors)
            .build()
    )

    init {
        setOutputStreamOffsetUs(C.TIME_UNSET)
    }

    override val mediaClock: MediaClock?
        get() = this

    override fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long): Long {
        val audioSinkBufferFull =
            audioSink.hasPendingData() && nextBufferToWritePresentationTimeUs != C.TIME_UNSET
        if (!isStarted) {
            // When not started we can only make further progress if the audio track buffer isn't filled
            // yet and there is more data to fill it.
            return if (audioSinkBufferFull || outputStreamEnded)
                Renderer.DEFAULT_IDLE_DURATION_TO_PROGRESS_US
            else
                Renderer.DEFAULT_DURATION_TO_PROGRESS_US
        }
        val audioTrackBufferDurationUs = audioSink.audioTrackBufferSizeUs
        if (!hasReportedAudioPositionAdvancing || !audioSinkBufferFull || audioTrackBufferDurationUs == C.TIME_UNSET) {
            // If audio has not yet advanced, the AudioSink buffer is not yet full, or getting the audio
            // track buffer size is unsupported, continue calling with default duration to progress.
            return Renderer.DEFAULT_DURATION_TO_PROGRESS_US
        }
        // Compare written, yet-to-play content duration against the audio track buffer size.
        val writtenDurationUs = (nextBufferToWritePresentationTimeUs - positionUs)
        var bufferedDurationUs = min(audioTrackBufferDurationUs, writtenDurationUs)
        bufferedDurationUs = (bufferedDurationUs / (playbackParameters.speed) / 2).toLong()
        return max(Renderer.DEFAULT_DURATION_TO_PROGRESS_US, bufferedDurationUs)
    }

    override fun supportsFormat(format: Format): @RendererCapabilities.Capabilities Int {
        if (!isAudio(format.sampleMimeType)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        }
        val formatSupport: @C.FormatSupport Int = supportsFormatInternal(format)
        if (formatSupport <= C.FORMAT_UNSUPPORTED_DRM) {
            return RendererCapabilities.create(formatSupport)
        }
        return RendererCapabilities.create(
            formatSupport,
            RendererCapabilities.ADAPTIVE_NOT_SEAMLESS,
            RendererCapabilities.TUNNELING_SUPPORTED
        )
    }

    protected abstract fun supportsFormatInternal(format: Format?): @C.FormatSupport Int

    protected fun sinkSupportsFormat(format: Format): Boolean {
        return audioSink.supportsFormat(format)
    }

    protected fun getSinkFormatSupport(format: Format): @SinkFormatSupport Int {
        return audioSink.getFormatSupport(format)
    }

    @Throws(ExoPlaybackException::class)
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        if (outputStreamEnded) {
            try {
                audioSink.playToEndOfStream()
                nextBufferToWritePresentationTimeUs = lastBufferInStreamPresentationTimeUs
            } catch (e: AudioSink.WriteException) {
                throw createRendererException(
                    e,
                    e.format,
                    e.isRecoverable,
                    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
                )
            }
            return
        }

        // Try and read a format if we don't have one already.
        if (inputFormat == null) {
            // We don't have a format yet, so try and read one.
            val formatHolder = getFormatHolder()
            flagsOnlyBuffer.clear()
            val result =
                readSource(formatHolder, flagsOnlyBuffer, SampleStream.FLAG_REQUIRE_FORMAT)
            when (result) {
                C.RESULT_FORMAT_READ -> {
                    onInputFormatChanged(formatHolder)
                }
                C.RESULT_BUFFER_READ -> {
                    // End of stream read having not read a format.
                    Preconditions.checkState(flagsOnlyBuffer.isEndOfStream)
                    inputStreamEnded = true
                    try {
                        processEndOfStream()
                    } catch (e: AudioSink.WriteException) {
                        throw createRendererException(
                            e, null, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
                        )
                    }
                    return
                }
                else -> {
                    // We still don't have a format and can't make progress without one.
                    return
                }
            }
        }

        // If we don't have a decoder yet, we need to instantiate one.
        maybeInitDecoder()

        if (decoder != null) {
            try {
                // Rendering loop.
                beginSection("drainAndFeed")
                while (drainOutputBuffer()) {
                }
                while (feedInputBuffer()) {
                }
                endSection()
            } catch (e: DecoderException) {
                // Can happen with dequeueOutputBuffer, dequeueInputBuffer, queueInputBuffer
                e(TAG, "Audio codec error", e)
                eventDispatcher.audioCodecError(e)
                throw createRendererException(
                    e,
                    inputFormat,
                    PlaybackException.ERROR_CODE_DECODING_FAILED
                )
            } catch (e: AudioSink.ConfigurationException) {
                throw createRendererException(
                    e, e.format, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
                )
            } catch (e: AudioSink.InitializationException) {
                throw createRendererException(
                    e,
                    e.format,
                    e.isRecoverable,
                    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
                )
            } catch (e: AudioSink.WriteException) {
                throw createRendererException(
                    e,
                    e.format,
                    e.isRecoverable,
                    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
                )
            }
            decoderCounters!!.ensureUpdated()
        }
    }

    @CallSuper
    protected fun onPositionDiscontinuity() {
        // We are out of sync so allow currentPositionUs to jump backwards.
        allowPositionDiscontinuity = true
    }

    @Throws(DecoderException::class)
    protected abstract fun createDecoder(format: Format?, cryptoConfig: CryptoConfig?): T?

    protected abstract fun getOutputFormat(decoder: T?): Format?

    protected fun getChannelMapping(decoder: T?): ImmutableIntArray? {
        return null
    }

    protected fun canReuseDecoder(
        decoderName: String, oldFormat: Format?, newFormat: Format?
    ): DecoderReuseEvaluation {
        return DecoderReuseEvaluation(
            decoderName,
            oldFormat!!,
            newFormat!!,
            DecoderReuseEvaluation.REUSE_RESULT_NO,
            DecoderReuseEvaluation.DISCARD_REASON_REUSE_NOT_IMPLEMENTED
        )
    }

    @Throws(
        ExoPlaybackException::class,
        DecoderException::class,
        AudioSink.ConfigurationException::class,
        AudioSink.InitializationException::class,
        AudioSink.WriteException::class
    )
    private fun drainOutputBuffer(): Boolean {
        if (outputBuffer == null) {
            outputBuffer = decoder!!.dequeueOutputBuffer()
            if (outputBuffer == null) {
                return false
            }
            if (outputBuffer!!.skippedOutputBufferCount > 0) {
                decoderCounters!!.skippedOutputBufferCount += outputBuffer!!.skippedOutputBufferCount
                audioSink.handleDiscontinuity()
            }
            if (outputBuffer!!.isFirstSample) {
                processFirstSampleOfStream()
            }
        }
        if (outputBuffer!!.isEndOfStream) {
            if (decoderReinitializationState == REINITIALIZATION_STATE_WAIT_END_OF_STREAM) {
                // We're waiting to re-initialize the decoder, and have now processed all final buffers.
                releaseDecoder()
                maybeInitDecoder()
                // The audio track may need to be recreated once the new output format is known.
                audioTrackNeedsConfigure = true
            } else {
                outputBuffer!!.release()
                outputBuffer = null
                try {
                    processEndOfStream()
                } catch (e: AudioSink.WriteException) {
                    throw createRendererException(
                        e,
                        e.format,
                        e.isRecoverable,
                        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
                    )
                }
            }
            return false
        }
        nextBufferToWritePresentationTimeUs = C.TIME_UNSET

        if (audioTrackNeedsConfigure) {
            val outputFormat =
                getOutputFormat(decoder)!!
                    .buildUpon()
                    .setEncoderDelay(encoderDelay)
                    .setEncoderPadding(encoderPadding)
                    .setMetadata(inputFormat!!.metadata)
                    .setCustomData(inputFormat!!.customData)
                    .setId(inputFormat!!.id)
                    .setLabel(inputFormat!!.label)
                    .setLabels(inputFormat!!.labels)
                    .setLanguage(inputFormat!!.language)
                    .setSelectionFlags(inputFormat!!.selectionFlags)
                    .setRoleFlags(inputFormat!!.roleFlags)
                    .build()
            audioSink.configure(
                AudioSinkConfig.Builder(outputFormat)
                    .setOutputChannelMapping(getChannelMapping(decoder))
                    .setTimeline(getTimeline())
                    .setMediaPeriodId(mediaPeriodId)
                    .build()
            )
            audioTrackNeedsConfigure = false
        }

        if (audioSink.handleBuffer(
                outputBuffer!!.data!!, outputBuffer!!.timeUs, 1
            )
        ) {
            decoderCounters!!.renderedOutputBufferCount++
            outputBuffer!!.release()
            outputBuffer = null
            return true
        } else {
            // Downstream buffers are full, set nextBufferToWritePresentationTimeUs to the presentation
            // time of the current 'to be written' sample.
            nextBufferToWritePresentationTimeUs = outputBuffer!!.timeUs
        }

        return false
    }

    private fun processFirstSampleOfStream() {
        audioSink.handleDiscontinuity()
        if (pendingOutputStreamOffsetCount != 0) {
            setOutputStreamOffsetUs(pendingOutputStreamOffsetsUs[0])
            pendingOutputStreamOffsetCount--
            System.arraycopy(
                pendingOutputStreamOffsetsUs,
                1,
                pendingOutputStreamOffsetsUs,
                0,
                pendingOutputStreamOffsetCount
            )
        }
    }

    private fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        this.outputStreamOffsetUs = outputStreamOffsetUs
        if (outputStreamOffsetUs != C.TIME_UNSET) {
            audioSink.setOutputStreamOffsetUs(outputStreamOffsetUs)
        }
    }

    @Throws(DecoderException::class, ExoPlaybackException::class)
    private fun feedInputBuffer(): Boolean {
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

        if (decoderReinitializationState == REINITIALIZATION_STATE_SIGNAL_END_OF_STREAM) {
            inputBuffer!!.setFlags(C.BUFFER_FLAG_END_OF_STREAM)
            decoder!!.queueInputBuffer(inputBuffer)
            inputBuffer = null
            decoderReinitializationState = REINITIALIZATION_STATE_WAIT_END_OF_STREAM
            return false
        }

        val formatHolder = getFormatHolder()
        when (readSource(formatHolder, inputBuffer!!, 0)) {
            C.RESULT_NOTHING_READ -> {
                if (hasReadStreamToEnd()) {
                    // Notify output queue of the last buffer's timestamp.
                    lastBufferInStreamPresentationTimeUs = largestQueuedPresentationTimeUs
                }
                return false
            }

            C.RESULT_FORMAT_READ -> {
                onInputFormatChanged(formatHolder)
                return true
            }

            C.RESULT_BUFFER_READ -> {
                if (inputBuffer!!.isEndOfStream) {
                    inputStreamEnded = true
                    lastBufferInStreamPresentationTimeUs = largestQueuedPresentationTimeUs
                    decoder!!.queueInputBuffer(inputBuffer)
                    inputBuffer = null
                    return false
                }
                if (!firstStreamSampleRead) {
                    firstStreamSampleRead = true
                    inputBuffer!!.addFlag(C.BUFFER_FLAG_FIRST_SAMPLE)
                }
                largestQueuedPresentationTimeUs = inputBuffer!!.timeUs
                if (hasReadStreamToEnd() || inputBuffer!!.isLastSample) {
                    lastBufferInStreamPresentationTimeUs = largestQueuedPresentationTimeUs
                }
                inputBuffer!!.flip()
                inputBuffer!!.format = inputFormat
                decoder!!.queueInputBuffer(inputBuffer)
                decoderReceivedBuffers = true
                decoderCounters!!.queuedInputBufferCount++
                inputBuffer = null
                return true
            }

            else -> throw IllegalStateException()
        }
    }

    @Throws(AudioSink.WriteException::class)
    private fun processEndOfStream() {
        outputStreamEnded = true
        audioSink.playToEndOfStream()
        nextBufferToWritePresentationTimeUs = lastBufferInStreamPresentationTimeUs
    }

    @Throws(ExoPlaybackException::class)
    private fun flushDecoder() {
        if (decoderReinitializationState != REINITIALIZATION_STATE_NONE) {
            releaseDecoder()
            maybeInitDecoder()
        } else {
            inputBuffer = null
            if (outputBuffer != null) {
                outputBuffer!!.release()
                outputBuffer = null
            }
            val decoder = this.decoder!!
            decoder.flush()
            decoder.setOutputStartTimeUs(lastResetPositionUs)
            decoderReceivedBuffers = false
        }
    }

    override val isEnded: Boolean
        get() = outputStreamEnded && audioSink.isEnded

    override val isReady: Boolean
        get() {
            val isReady = audioSink.hasPendingData()
            if (isReady) {
                firstNotReadyTimeMs = C.TIME_UNSET
                hasBeenReady = true
                return true
            }
            if (hasBeenReady && isStarted && isSourceReady && !hasReadStreamToEnd()) {
                // Engage a grace period for downstream underruns, but exclude genuine upstream starvation
                // (isSourceReady() is false) and natural stream transitions (hasReadStreamToEnd() is true).
                val elapsedRealtimeMs = getClock().elapsedRealtime()
                if (firstNotReadyTimeMs == C.TIME_UNSET) {
                    firstNotReadyTimeMs = elapsedRealtimeMs
                    return true
                }
                if (elapsedRealtimeMs - firstNotReadyTimeMs < READY_GRACE_PERIOD_MS) {
                    return true
                }
            }
            return false
        }

    override val positionUs: Long
        get() {
            if (state == Renderer.STATE_STARTED) {
                updateCurrentPosition()
            }
            return currentPositionUs
        }

    override fun hasSkippedSilenceSinceLastCall(): Boolean {
        val hasPendingReportedSkippedSilence = this.hasPendingReportedSkippedSilence
        this.hasPendingReportedSkippedSilence = false
        return hasPendingReportedSkippedSilence
    }

    override var playbackParameters: PlaybackParameters
        get() = audioSink.playbackParameters
        set(value) {
            audioSink.playbackParameters = value
        }

    @Throws(ExoPlaybackException::class)
    override fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) {
        decoderCounters = DecoderCounters()
        eventDispatcher.enabled(decoderCounters!!)
        if (getConfiguration().tunneling) {
            audioSink.enableTunnelingV21()
        } else {
            audioSink.disableTunneling()
        }
        audioSink.setPlayerId(getPlayerId())
        audioSink.setClock(getClock())
        audioSink.setListener(AudioSinkListener())
    }

    @Throws(ExoPlaybackException::class)
    override fun onPositionReset(
        positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean
    ) {
        audioSink.flush()

        currentPositionUs = positionUs
        nextBufferToWritePresentationTimeUs = C.TIME_UNSET
        firstNotReadyTimeMs = C.TIME_UNSET
        hasBeenReady = false
        hasPendingReportedSkippedSilence = false
        hasReportedAudioPositionAdvancing = false
        allowPositionDiscontinuity = true
        inputStreamEnded = false
        outputStreamEnded = false
        if (decoder != null) {
            flushDecoder()
        }
    }

    override fun onStarted() {
        audioSink.play()
        isStarted = true
    }

    override fun onStopped() {
        updateCurrentPosition()
        audioSink.pause()
        isStarted = false
        hasReportedAudioPositionAdvancing = false
        firstNotReadyTimeMs = C.TIME_UNSET
        hasBeenReady = false
    }

    override fun onDisabled() {
        inputFormat = null
        audioTrackNeedsConfigure = true
        setOutputStreamOffsetUs(C.TIME_UNSET)
        hasPendingReportedSkippedSilence = false
        hasReportedAudioPositionAdvancing = false
        nextBufferToWritePresentationTimeUs = C.TIME_UNSET
        try {
            setSourceDrmSession(null)
            releaseDecoder()
            audioSink.reset()
        } finally {
            eventDispatcher.disabled(decoderCounters!!)
        }
    }

    @Throws(ExoPlaybackException::class)
    override fun onStreamChanged(
        formats: Array<Format>,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaPeriodId
    ) {
        super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
        firstStreamSampleRead = false
        if (outputStreamOffsetUs == C.TIME_UNSET) {
            setOutputStreamOffsetUs(offsetUs)
        } else {
            if (pendingOutputStreamOffsetCount == pendingOutputStreamOffsetsUs.size) {
                val offset = pendingOutputStreamOffsetsUs[pendingOutputStreamOffsetCount - 1]
                w(TAG, "Too many stream changes, so dropping offset: $offset")
            } else {
                pendingOutputStreamOffsetCount++
            }
            pendingOutputStreamOffsetsUs[pendingOutputStreamOffsetCount - 1] = offsetUs
        }
    }

    @Throws(ExoPlaybackException::class)
    override fun handleMessage(messageType: @Renderer.MessageType Int, message: Any?) {
        when (messageType) {
            Renderer.MSG_SET_VOLUME -> audioSink.setVolume((message as kotlin.Float?)!!)
            Renderer.MSG_SET_AUDIO_ATTRIBUTES -> {
                val audioAttributes = message as AudioAttributes?
                audioSink.audioAttributes = (audioAttributes)
            }

            Renderer.MSG_SET_AUX_EFFECT_INFO -> {
                val auxEffectInfo = message as AuxEffectInfo
                audioSink.setAuxEffectInfo(auxEffectInfo)
            }

            Renderer.MSG_SET_SKIP_SILENCE_ENABLED -> audioSink.skipSilenceEnabled =
                ((message as kotlin.Boolean?)!!)

            Renderer.MSG_SET_AUDIO_SESSION_ID -> audioSink.setAudioSessionId((message as kotlin.Int?)!!)
            Renderer.MSG_SET_PREFERRED_AUDIO_DEVICE -> audioSink.setPreferredDevice(message as AudioDeviceInfo?)
            Renderer.MSG_SET_VIRTUAL_DEVICE_ID -> audioSink.setVirtualDeviceId(
                message as Int
            )

            Renderer.MSG_SET_AUDIO_OUTPUT_PROVIDER -> audioSink.setAudioOutputProvider(
                message as AudioOutputProvider
            )

            Renderer.MSG_SET_WAKEUP_LISTENER -> wakeupListener = message as WakeupListener?
            Renderer.MSG_SET_CAMERA_MOTION_LISTENER, Renderer.MSG_SET_CHANGE_FRAME_RATE_STRATEGY,
            Renderer.MSG_SET_SCALING_MODE, Renderer.MSG_SET_VIDEO_FRAME_METADATA_LISTENER,
            Renderer.MSG_SET_VIDEO_OUTPUT -> super.handleMessage(
                messageType,
                message
            )

            else -> super.handleMessage(messageType, message)
        }
    }

    override fun onInputBufferAvailable() {
        wakeupListener?.onWakeup()
    }

    override fun onOutputBufferAvailable() {
        wakeupListener?.onWakeup()
    }

    protected val isReadyForDecoding: Boolean
        get() = inputFormat != null && (isSourceReady || outputBuffer != null)

    @Throws(ExoPlaybackException::class)
    private fun maybeInitDecoder() {
        decoder ?: return
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
            if (playbackThreadExecutor == null) {
                val handler = Handler(Looper.myLooper()!!)
                playbackThreadExecutor = Executor { r: Runnable? -> handler.post(r!!) }
            }
            val codecInitializingTimestamp = SystemClock.elapsedRealtime()
            beginSection("createAudioDecoder")
            decoder = createDecoder(inputFormat, cryptoConfig)
            decoder!!.setOutputStartTimeUs(lastResetPositionUs)
            decoder!!.setCallback(this, playbackThreadExecutor)
            endSection()
            val codecInitializedTimestamp = SystemClock.elapsedRealtime()
            eventDispatcher.decoderInitialized(
                decoder!!.name,
                codecInitializedTimestamp,
                codecInitializedTimestamp - codecInitializingTimestamp
            )
            decoderCounters!!.decoderInitCount++
        } catch (e: DecoderException) {
            e(TAG, "Audio codec error", e)
            eventDispatcher.audioCodecError(e)
            throw createRendererException(
                e, inputFormat, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
            )
        } catch (e: OutOfMemoryError) {
            throw createRendererException(
                e, inputFormat, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
            )
        }
    }

    private fun releaseDecoder() {
        inputBuffer = null
        outputBuffer = null
        decoderReinitializationState = REINITIALIZATION_STATE_NONE
        decoderReceivedBuffers = false
        largestQueuedPresentationTimeUs = C.TIME_UNSET
        lastBufferInStreamPresentationTimeUs = C.TIME_UNSET
        decoder?.also {
            decoderCounters!!.decoderReleaseCount++
            it.release()
            eventDispatcher.decoderReleased(it.name)
            decoder = null
        }
        setDecoderDrmSession(null)
    }

    private fun setSourceDrmSession(session: DrmSession?) {
        DrmSession.replaceSession(sourceDrmSession, session)
        sourceDrmSession = session
    }

    private fun setDecoderDrmSession(session: DrmSession?) {
        DrmSession.replaceSession(decoderDrmSession, session)
        decoderDrmSession = session
    }

    @Throws(ExoPlaybackException::class)
    private fun onInputFormatChanged(formatHolder: FormatHolder) {
        val newFormat = formatHolder.format!!
        setSourceDrmSession(formatHolder.drmSession)
        val oldFormat = inputFormat
        inputFormat = newFormat
        encoderDelay = newFormat.encoderDelay
        encoderPadding = newFormat.encoderPadding

        if (decoder == null) {
            maybeInitDecoder()
            eventDispatcher.inputFormatChanged(inputFormat!!, null)
            return
        }
        val evaluation = if (sourceDrmSession !== decoderDrmSession) {
            DecoderReuseEvaluation(
                decoder!!.name,
                oldFormat!!,
                newFormat,
                DecoderReuseEvaluation.REUSE_RESULT_NO,
                DecoderReuseEvaluation.DISCARD_REASON_DRM_SESSION_CHANGED
            )
        } else {
            canReuseDecoder(decoder!!.name, oldFormat, newFormat)
        }

        if (evaluation.result == DecoderReuseEvaluation.REUSE_RESULT_NO) {
            if (decoderReceivedBuffers) {
                // Signal end of stream and wait for any final output buffers before re-initialization.
                decoderReinitializationState = REINITIALIZATION_STATE_SIGNAL_END_OF_STREAM
            } else {
                // There aren't any final output buffers, so release the decoder immediately.
                releaseDecoder()
                maybeInitDecoder()
                audioTrackNeedsConfigure = true
            }
        }
        eventDispatcher.inputFormatChanged(inputFormat!!, evaluation)
    }

    private fun updateCurrentPosition() {
        val newCurrentPositionUs = audioSink.getCurrentPositionUs(isEnded)
        if (newCurrentPositionUs != AudioSink.CURRENT_POSITION_NOT_SET) {
            currentPositionUs =
                if (allowPositionDiscontinuity)
                    newCurrentPositionUs
                else
                    max(currentPositionUs, newCurrentPositionUs)
            allowPositionDiscontinuity = false
        }
    }

    private inner class AudioSinkListener : AudioSink.Listener {
        override fun onPositionDiscontinuity() {
            this@DecoderAudioRenderer.onPositionDiscontinuity()
        }

        override fun onSilenceSkipped() {
            hasPendingReportedSkippedSilence = true
        }

        override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) {
            hasReportedAudioPositionAdvancing = true
            eventDispatcher.positionAdvancing(playoutStartSystemTimeMs)
        }

        override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
            eventDispatcher.underrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs)
        }

        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {
            eventDispatcher.skipSilenceEnabledChanged(skipSilenceEnabled)
        }

        override fun onAudioSinkError(audioSinkError: Exception) {
            e(TAG, "Audio sink error", audioSinkError)
            eventDispatcher.audioSinkError(audioSinkError)
        }

        override fun onAudioTrackInitialized(audioTrackConfig: AudioTrackConfig) {
            eventDispatcher.audioTrackInitialized(audioTrackConfig)
        }

        override fun onAudioTrackReleased(audioTrackConfig: AudioTrackConfig) {
            eventDispatcher.audioTrackReleased(audioTrackConfig)
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            eventDispatcher.audioSessionIdChanged(audioSessionId)
        }

        override fun onAudioCapabilitiesChanged() {
            this@DecoderAudioRenderer.onRendererCapabilitiesChanged()
        }
    }

    companion object {
        private const val TAG = "DecoderAudioRenderer"
        private const val READY_GRACE_PERIOD_MS: Long = 100
        private const val REINITIALIZATION_STATE_NONE = 0
        private const val REINITIALIZATION_STATE_SIGNAL_END_OF_STREAM = 1
        private const val REINITIALIZATION_STATE_WAIT_END_OF_STREAM = 2
        private const val MAX_PENDING_OUTPUT_STREAM_OFFSET_COUNT = 10
    }
}
