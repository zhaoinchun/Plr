package androidx.media3.exoplayer.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.IntDef
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.common.util.Clock
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.Util
import androidx.media3.common.util.Util.ceilDivide
import androidx.media3.common.util.Util.constrainValue
import androidx.media3.common.util.Util.durationUsToSampleCount
import androidx.media3.common.util.Util.getBigEndianInt
import androidx.media3.common.util.Util.getMediaDurationForPlayoutDuration
import androidx.media3.common.util.Util.getPcmFrameSize
import androidx.media3.common.util.Util.isEncodingHighResolutionPcm
import androidx.media3.common.util.Util.isEncodingLinearPcm
import androidx.media3.common.util.Util.msToUs
import androidx.media3.common.util.Util.sampleCountToDurationUs
import androidx.media3.common.util.Util.scaleLargeValue
import androidx.media3.common.util.Util.usToMs
import androidx.media3.container.OpusUtil.parseOggPacketAudioSampleCount
import androidx.media3.exoplayer.ExoPlayer.AudioOffloadListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioOutputProvider.FormatConfig
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig
import androidx.media3.exoplayer.audio.AudioSink.AudioSinkConfig
import androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig
import androidx.media3.exoplayer.audio.AudioSink.OffloadMode
import androidx.media3.exoplayer.audio.AudioSink.SinkFormatSupport
import androidx.media3.exoplayer.audio.AudioSink.UnexpectedDiscontinuityException
import androidx.media3.extractor.AacUtil
import androidx.media3.extractor.Ac3Util
import androidx.media3.extractor.Ac3Util.findTrueHdSyncframeOffset
import androidx.media3.extractor.Ac3Util.parseAc3SyncframeAudioSampleCount
import androidx.media3.extractor.Ac3Util.parseTrueHdSyncframeAudioSampleCount
import androidx.media3.extractor.Ac4Util.parseAc4SyncframeAudioSampleCount
import androidx.media3.extractor.DtsUtil.parseDtsAudioSampleCount
import androidx.media3.extractor.ExtractorUtil.getMaximumEncodedRateBytesPerSecond
import androidx.media3.extractor.MpegAudioUtil.parseMpegAudioFrameSampleCount
import com.google.common.base.Preconditions
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class DefaultAudioSink private constructor(builder: Builder) :
    AudioSink {
    @Deprecated("Use {@link AudioTrackAudioOutputProvider} instead.")
    interface AudioTrackProvider {
        fun getAudioTrack(
            audioTrackConfig: AudioTrackConfig,
            audioAttributes: AudioAttributes,
            audioSessionId: Int,
            context: Context?
        ): AudioTrack

        fun getAudioTrackChannelConfig(channelCount: Int): Int {
            return Util.getAudioTrackChannelConfig(channelCount)
        }

        companion object {
            val DEFAULT: AudioTrackProvider = DefaultAudioTrackProvider()
        }
    }

    @Deprecated("Use {@link androidx.media3.common.audio.AudioProcessorChain}.")
    interface AudioProcessorChain : androidx.media3.common.audio.AudioProcessorChain

    class DefaultAudioProcessorChain(
        audioProcessors: Array<AudioProcessor>,
        silenceSkippingAudioProcessor: SilenceSkippingAudioProcessor,
        sonicAudioProcessor: SonicAudioProcessor
    ) : AudioProcessorChain {
        // The passed-in type may be more specialized than AudioProcessor[], so allocate a new array
        // rather than using Arrays.copyOf.
        override val audioProcessors: Array<AudioProcessor> = audioProcessors.toMutableList()
            .also { list ->
                list.add(silenceSkippingAudioProcessor)
                list.add(sonicAudioProcessor)
            }.toTypedArray()
        private val silenceSkippingAudioProcessor = silenceSkippingAudioProcessor
        private val sonicAudioProcessor = sonicAudioProcessor

        constructor(audioProcessors: Array<AudioProcessor>) : this(
            audioProcessors,
            SilenceSkippingAudioProcessor(),
            SonicAudioProcessor()
        )

        override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
            sonicAudioProcessor.setSpeed(playbackParameters.speed)
            sonicAudioProcessor.setPitch(playbackParameters.pitch)
            return playbackParameters
        }

        override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
            silenceSkippingAudioProcessor.setEnabled(skipSilenceEnabled)
            return skipSilenceEnabled
        }

        override fun getMediaDuration(playoutDuration: Long): Long {
            return if (sonicAudioProcessor.isActive)
                sonicAudioProcessor.getMediaDuration(playoutDuration)
            else
                playoutDuration
        }

        override val skippedOutputFrameCount: Long
            get() = silenceSkippingAudioProcessor.skippedFrames
    }

    interface AudioTrackBufferSizeProvider {
        fun getBufferSizeInBytes(
            minBufferSizeInBytes: Int,
            encoding: @C.Encoding Int,
            outputMode: @OutputMode Int,
            pcmFrameSize: Int,
            sampleRate: Int,
            bitrate: Int,
            maxAudioTrackPlaybackSpeed: Double
        ): Int

        companion object {
            val DEFAULT: AudioTrackBufferSizeProvider =
                DefaultAudioTrackBufferSizeProvider.Builder().build()
        }
    }

    interface AudioOffloadSupportProvider {
        fun getAudioOffloadSupport(
            format: Format, audioAttributes: AudioAttributes
        ): AudioOffloadSupport
    }

    class Builder {
        val context: Context?
        var audioCapabilities: AudioCapabilities?
        var audioProcessorChain: androidx.media3.common.audio.AudioProcessorChain? = null
        var enableFloatOutput = false
        var enableAudioOutputPlaybackParameters = false
        var buildCalled = false
        var audioTrackBufferSizeProvider: AudioTrackBufferSizeProvider? =
            null
        var audioOutputProvider: AudioOutputProvider? = null
        var audioTrackProvider: AudioTrackProvider? = null
        var audioOffloadSupportProvider: AudioOffloadSupportProvider? =
            null
        var audioOffloadListener: AudioOffloadListener? = null

        @Deprecated("Use {@link #Builder(Context)} instead.")
        constructor() {
            this.context = null
            audioCapabilities = AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES
        }

        constructor(context: Context?) {
            this.context = context
            audioCapabilities = AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES
        }

        @Deprecated(
            """These {@linkplain AudioCapabilities audio capabilities} are only used in the
          absence of a {@linkplain Context context}. In the case when the {@code Context} is {@code
     *     null} and the {@code audioCapabilities} is not set to the {@code Builder}, the default
          capabilities (no encoded audio passthrough support) should be assumed."""
        )
        fun setAudioCapabilities(audioCapabilities: AudioCapabilities?): Builder {
            this.audioCapabilities = audioCapabilities!!
            return this
        }

        fun setAudioProcessors(audioProcessors: Array<AudioProcessor>): Builder {
            return setAudioProcessorChain(DefaultAudioProcessorChain(audioProcessors!!))
        }

        fun setAudioProcessorChain(
            audioProcessorChain: androidx.media3.common.audio.AudioProcessorChain?
        ): Builder {
            this.audioProcessorChain = audioProcessorChain!!
            return this
        }

        fun setEnableFloatOutput(enableFloatOutput: Boolean): Builder {
            this.enableFloatOutput = enableFloatOutput
            return this
        }

        @Deprecated("Use {@link #setEnableAudioOutputPlaybackParameters(boolean)} instead.")
        fun setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams: Boolean): Builder {
            return setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
        }

        fun setEnableAudioOutputPlaybackParameters(
            enableAudioOutputPlaybackParameters: Boolean
        ): Builder {
            this.enableAudioOutputPlaybackParameters = enableAudioOutputPlaybackParameters
            return this
        }

        @Deprecated(
            """Use {@link #setAudioOutputProvider(AudioOutputProvider)} instead and customize
          {@link
     *     AudioTrackAudioOutputProvider.Builder#setAudioTrackBufferSizeProvider(AudioTrackBufferSizeProvider)}."""
        )
        fun setAudioTrackBufferSizeProvider(
            audioTrackBufferSizeProvider: AudioTrackBufferSizeProvider?
        ): Builder {
            this.audioTrackBufferSizeProvider = audioTrackBufferSizeProvider
            return this
        }

        @Deprecated(
            """Use {@link #setAudioOutputProvider(AudioOutputProvider)} instead and customize
          {@link
     *     AudioTrackAudioOutputProvider.Builder#setAudioOffloadSupportProvider(AudioOffloadSupportProvider)}."""
        )
        fun setAudioOffloadSupportProvider(
            audioOffloadSupportProvider: AudioOffloadSupportProvider?
        ): Builder {
            this.audioOffloadSupportProvider = audioOffloadSupportProvider
            return this
        }

        fun setExperimentalAudioOffloadListener(
            audioOffloadListener: AudioOffloadListener?
        ): Builder {
            this.audioOffloadListener = audioOffloadListener
            return this
        }

        @Deprecated(
            """Use {@link #setAudioOutputProvider(AudioOutputProvider)} instead and customize
          {@link AudioTrackAudioOutputProvider.Builder#setAudioTrackBuilderModifier(BiConsumer)} or
          wrap {@link AudioTrackAudioOutputProvider} in a {@link ForwardingAudioOutputProvider}."""
        )
        fun setAudioTrackProvider(audioTrackProvider: AudioTrackProvider?): Builder {
            this.audioTrackProvider = audioTrackProvider
            return this
        }

        fun setAudioOutputProvider(audioOutputProvider: AudioOutputProvider?): Builder {
            Preconditions.checkState(
                context != null, "Cannot set AudioOutputProvider without a Context"
            )
            this.audioOutputProvider = audioOutputProvider
            return this
        }

        fun build(): DefaultAudioSink {
            Preconditions.checkState(!buildCalled)
            buildCalled = true
            if (audioProcessorChain == null) {
                audioProcessorChain = DefaultAudioProcessorChain(arrayOf())
            }
            if (audioOutputProvider == null) {
                if (audioOffloadSupportProvider == null) {
                    audioOffloadSupportProvider = DefaultAudioOffloadSupportProvider(context)
                }
                if (audioTrackBufferSizeProvider == null) {
                    audioTrackBufferSizeProvider = AudioTrackBufferSizeProvider.DEFAULT
                }
                audioOutputProvider =
                    AudioTrackAudioOutputProvider.Builder(context)
                        .setAudioCapabilities(if (context != null) null else audioCapabilities)
                        .setAudioOffloadSupportProvider(audioOffloadSupportProvider)
                        .setAudioTrackBufferSizeProvider(audioTrackBufferSizeProvider!!)
                        .setAudioTrackProvider(audioTrackProvider)
                        .build()
            } else {
                Preconditions.checkState(audioOffloadSupportProvider == null)
                Preconditions.checkState(audioTrackBufferSizeProvider == null)
                Preconditions.checkState(audioTrackProvider == null)
            }
            return DefaultAudioSink(this)
        }
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [OUTPUT_MODE_PCM, OUTPUT_MODE_OFFLOAD, OUTPUT_MODE_PASSTHROUGH])
    annotation class OutputMode

    private val context: Context?
    private val audioProcessorChain: androidx.media3.common.audio.AudioProcessorChain? =
        builder.audioProcessorChain
    private val enableFloatOutput: Boolean = builder.enableFloatOutput
    private val channelMappingAudioProcessor: ChannelMappingAudioProcessor
    private val trimmingAudioProcessor: TrimmingAudioProcessor
    private val toInt16PcmAudioProcessor: ToInt16PcmAudioProcessor
    private val toFloatPcmAudioProcessor: ToFloatPcmAudioProcessor
    private val availableAudioProcessors: List<AudioProcessor>
    private val mediaPositionParametersCheckpoints: ArrayDeque<MediaPositionParameters>
    private val preferAudioOutputPlaybackParameters: Boolean =
        builder.enableAudioOutputPlaybackParameters
    private var offloadMode: @OffloadMode Int
    private var audioOutputListener: AudioOutputListener? = null
    private val initializationExceptionPendingExceptionHolder: PendingExceptionHolder<AudioSink.InitializationException>

    private val writeExceptionPendingExceptionHolder: PendingExceptionHolder<AudioSink.WriteException>
    private val audioOffloadListener: AudioOffloadListener?

    private var playerId: PlayerId? = null
    private var listener: AudioSink.Listener? = null
    private var pendingConfiguration: Configuration? = null
    private var configuration: Configuration? = null
    private var audioProcessingPipeline: AudioProcessingPipeline? = null
    private var audioOutputProvider: AudioOutputProvider
    private var audioOutputProviderListener: AudioOutputProvider.Listener? = null
    private var audioOutput: AudioOutput? = null
    override var audioAttributes: AudioAttributes? = AudioAttributes.DEFAULT
        set(value) {
            if (field!! == value) {
                return
            }
            field = value
            if (tunneling) {
                // The audio attributes are ignored in tunneling mode, so no need to reset.
                return
            }
            reconfigureAndFlush()
        }
    private var afterDrainParameters: MediaPositionParameters? = null
    private var mediaPositionParameters: MediaPositionParameters
    private var _playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
        set(value) {
            if (useAudioOutputPlaybackParams()) {
                field = value
                setAudioOutputPlaybackParameters()
            } else {
                field =
                    PlaybackParameters(
                        constrainValue(
                            value.speed,
                            MIN_PLAYBACK_SPEED,
                            MAX_PLAYBACK_SPEED
                        ),
                        constrainValue(value.pitch, MIN_PITCH, MAX_PITCH)
                    )
                setAudioProcessorPlaybackParameters(field)
            }
        }

    override var playbackParameters: PlaybackParameters
        get() = _playbackParameters
        set(value) {
            _playbackParameters = value
        }

    override var skipSilenceEnabled: Boolean = false
        set(value) {
            field = value
            setAudioProcessorPlaybackParameters(
                if (useAudioOutputPlaybackParams()) PlaybackParameters.DEFAULT else _playbackParameters
            )
        }

    private var submittedPcmBytes: Long = 0
    private var submittedEncodedFrames: Long = 0
    private var writtenPcmBytes: Long = 0
    private var writtenEncodedFrames: Long = 0
    private var framesPerEncodedSample = 0
    private var startMediaTimeUsNeedsSync = false
    private var startMediaTimeUsNeedsInit = false
    private var startMediaTimeUs: Long = 0
    private var outputStreamOffsetUs: Long = 0
    private var volume: Float

    private var inputBuffer: ByteBuffer? = null
    private var inputBufferAccessUnitCount = 0
    private var outputBuffer: ByteBuffer? = null
    private var handledEndOfStream = false
    private var stoppedAudioOutput = false
    private var handledOffloadOnPresentationEnded = false

    private var playing = false
    private var externalAudioSessionIdProvided = false
    private var audioSessionId: Int
    private var pendingAudioSessionIdChangeConfirmation = false
    private var auxEffectInfo: AuxEffectInfo
    private var preferredDevice: AudioDeviceInfo? = null
    private var virtualDeviceId: Int
    private var tunneling = false
    private var lastFeedElapsedRealtimeMs: Long = 0
    private var offloadDisabledUntilNextConfiguration = false
    private var isWaitingForOffloadEndOfStreamHandled = false
    private var skippedOutputFrameCountAtLastPosition: Long = 0
    private var accumulatedSkippedSilenceDurationUs: Long = 0
    private var reportSkippedSilenceHandler: Handler? = null

    init {
        context = builder.context?.applicationContext
        offloadMode = AudioSink.OFFLOAD_MODE_DISABLED
        audioOutputProvider = builder.audioOutputProvider!!
        channelMappingAudioProcessor = ChannelMappingAudioProcessor()
        trimmingAudioProcessor = TrimmingAudioProcessor()
        toInt16PcmAudioProcessor = ToInt16PcmAudioProcessor()
        toFloatPcmAudioProcessor = ToFloatPcmAudioProcessor()
        availableAudioProcessors = listOf(trimmingAudioProcessor, channelMappingAudioProcessor)
        volume = 1f
        audioSessionId = C.AUDIO_SESSION_ID_UNSET
        auxEffectInfo = AuxEffectInfo(AuxEffectInfo.NO_AUX_EFFECT_ID, 0f)
        mediaPositionParameters =
            MediaPositionParameters(
                PlaybackParameters.DEFAULT,  0,  0
            )
        _playbackParameters = PlaybackParameters.DEFAULT
        skipSilenceEnabled = DEFAULT_SKIP_SILENCE
        mediaPositionParametersCheckpoints = ArrayDeque<MediaPositionParameters>()
        initializationExceptionPendingExceptionHolder = PendingExceptionHolder()
        writeExceptionPendingExceptionHolder = PendingExceptionHolder()
        audioOffloadListener = builder.audioOffloadListener
        virtualDeviceId =
            if (Build.VERSION.SDK_INT < 34 || builder.context == null)
                C.INDEX_UNSET
            else
                getDeviceIdFromContext(builder.context)
    }

    // AudioSink implementation.
    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun setPlayerId(playerId: PlayerId?) {
        this.playerId = playerId
    }

    override fun setClock(clock: Clock) {
        audioOutputProvider.setClock(clock)
    }

    override fun supportsFormat(format: Format): Boolean {
        return getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
    }

    override fun getFormatSupport(format: Format): @SinkFormatSupport Int {
        // For PCM formats, convert the format to what our audio processors will produce.
        var format = format
        var transcodingViaAudioProcessors = false
        if (isEncodingLinearPcm(format.pcmEncoding)) {
            val usesFloatPcm = shouldUseFloatOutput(format.pcmEncoding)
            if (usesFloatPcm && format.pcmEncoding != C.ENCODING_PCM_FLOAT) {
                format = format.buildUpon().setPcmEncoding(C.ENCODING_PCM_FLOAT).build()
                transcodingViaAudioProcessors = true
            }
            if (!usesFloatPcm && format.pcmEncoding != C.ENCODING_PCM_16BIT) {
                format = format.buildUpon().setPcmEncoding(C.ENCODING_PCM_16BIT).build()
                transcodingViaAudioProcessors = true
            }
        }
        when (audioOutputProvider.getFormatSupport(getFormatConfig(format)).supportLevel) {
            AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY -> return if (transcodingViaAudioProcessors)
                AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
            else
                AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY

            AudioOutputProvider.FORMAT_SUPPORTED_WITH_TRANSCODING -> return AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
            AudioOutputProvider.FORMAT_UNSUPPORTED -> return AudioSink.SINK_FORMAT_UNSUPPORTED
            else -> return AudioSink.SINK_FORMAT_UNSUPPORTED
        }
    }

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport {
        if (offloadDisabledUntilNextConfiguration) {
            return AudioOffloadSupport.DEFAULT_UNSUPPORTED
        }
        val formatSupport = audioOutputProvider.getFormatSupport(getFormatConfig(format))
        return AudioOffloadSupport.Builder()
            .setIsFormatSupported(formatSupport.isFormatSupportedForOffload)
            .setIsGaplessSupported(formatSupport.isGaplessSupportedForOffload)
            .setIsSpeedChangeSupported(formatSupport.isSpeedChangeSupportedForOffload)
            .build()
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!this.isAudioOutputInitialized || startMediaTimeUsNeedsInit) {
            return AudioSink.CURRENT_POSITION_NOT_SET
        }
        var positionUs = audioOutput!!.positionUs
        positionUs = min(positionUs, configuration!!.framesToDurationUs(this.writtenFrames))
        return applySkipping(applyMediaPositionParameters(positionUs))
    }

    @Throws(AudioSink.ConfigurationException::class)
    override fun configure(audioSinkConfig: AudioSinkConfig) {
        var audioProcessingPipeline: AudioProcessingPipeline?
        val inputPcmFrameSize: Int
        val outputPcmFrameSize: Int
        val afterProcessingFormat: Format?

        maybeAddAudioOutputProviderListener()

        val inputFormat = audioSinkConfig.format
        if (MimeTypes.AUDIO_RAW == inputFormat.sampleMimeType) {
            Preconditions.checkArgument(isEncodingLinearPcm(inputFormat.pcmEncoding))

            inputPcmFrameSize = getPcmFrameSize(inputFormat.pcmEncoding, inputFormat.channelCount)

            val pipelineProcessors = mutableListOf<AudioProcessor>()
            pipelineProcessors.addAll(availableAudioProcessors)
            if (shouldUseFloatOutput(inputFormat.pcmEncoding)) {
                pipelineProcessors.add(toFloatPcmAudioProcessor)
            } else {
                pipelineProcessors.add(toInt16PcmAudioProcessor)
                pipelineProcessors.addAll(audioProcessorChain!!.audioProcessors!!)
            }
            audioProcessingPipeline = AudioProcessingPipeline(pipelineProcessors)

            // If the underlying processors of the new pipeline are the same as the existing pipeline,
            // then use the existing one when the configuration is used.
            if (audioProcessingPipeline == this.audioProcessingPipeline) {
                audioProcessingPipeline = this.audioProcessingPipeline
            }

            trimmingAudioProcessor.setTrimFrameCount(
                inputFormat.encoderDelay, inputFormat.encoderPadding
            )
            channelMappingAudioProcessor.setChannelMap(audioSinkConfig.outputChannelMapping)

            var outputFormat = AudioProcessor.AudioFormat(inputFormat)
            try {
                outputFormat = audioProcessingPipeline!!.configure(outputFormat)
            } catch (e: AudioProcessor.UnhandledAudioFormatException) {
                throw AudioSink.ConfigurationException(e, inputFormat)
            }

            afterProcessingFormat =
                inputFormat
                    .buildUpon()
                    .setPcmEncoding(outputFormat.encoding)
                    .setSampleRate(outputFormat.sampleRate)
                    .setChannelCount(outputFormat.channelCount)
                    .setChannelMask(
                        if (outputFormat.channelCount == inputFormat.channelCount)
                            inputFormat.channelMask
                        else
                            Format.NO_VALUE
                    )
                    .build()
            outputPcmFrameSize = getPcmFrameSize(outputFormat.encoding, outputFormat.channelCount)
        } else {
            // Audio processing is not supported in offload or passthrough mode.
            audioProcessingPipeline = AudioProcessingPipeline(listOf())
            inputPcmFrameSize = C.LENGTH_UNSET
            outputPcmFrameSize = C.LENGTH_UNSET
            afterProcessingFormat = inputFormat
        }

        val outputConfig: OutputConfig
        val preferredBufferSize =
            if (audioSinkConfig.preferredBufferSizeOverride != 0)
                audioSinkConfig.preferredBufferSizeOverride
            else
                C.LENGTH_UNSET
        val formatConfig = getFormatConfig(afterProcessingFormat, preferredBufferSize)
        try {
            outputConfig = audioOutputProvider.getOutputConfig(formatConfig)
        } catch (e: AudioOutputProvider.ConfigurationException) {
            throw AudioSink.ConfigurationException(e, inputFormat)
        }

        if (outputConfig.encoding == C.ENCODING_INVALID) {
            throw AudioSink.ConfigurationException(
                "Invalid output encoding (isOffload=" + outputConfig.isOffload + ")",
                formatConfig.format
            )
        }
        if (outputConfig.channelMask == AudioFormat.CHANNEL_INVALID) {
            throw AudioSink.ConfigurationException(
                "Invalid output channel config (isOffload=" + outputConfig.isOffload + ")",
                formatConfig.format
            )
        }

        offloadDisabledUntilNextConfiguration = false
        val pendingConfiguration =
            Configuration(
                inputFormat,
                afterProcessingFormat,
                inputPcmFrameSize,
                outputPcmFrameSize,
                outputConfig,
                audioProcessingPipeline,
                audioSinkConfig.timeline,
                audioSinkConfig.mediaPeriodId?.periodUid
            )
        if (this.isAudioOutputInitialized) {
            this.pendingConfiguration = pendingConfiguration
        } else {
            configuration = pendingConfiguration
        }
    }

    private fun setupAudioProcessors(nextPresentationTimeUs: Long) {
        val configuration = configuration!!
        audioProcessingPipeline = configuration.audioProcessingPipeline
        var positionOffsetUs: Long
        if (nextPresentationTimeUs == C.TIME_UNSET) {
            positionOffsetUs = 0
        } else {
            positionOffsetUs = nextPresentationTimeUs - outputStreamOffsetUs
            if (configuration.timeline !== Timeline.EMPTY && configuration.periodUid != null) {
                val period = Timeline.Period()
                configuration.timeline.getPeriodByUid(configuration.periodUid, period)
                positionOffsetUs += period.positionInWindowUs
            }
        }
        audioProcessingPipeline!!.flush(
            AudioProcessor.StreamMetadata.Builder()
                .setTimeline(configuration.timeline)
                .setPeriodUid(configuration.periodUid)
                .setPositionOffsetUs(positionOffsetUs)
                .build()
        )
    }

    @Throws(AudioSink.InitializationException::class)
    private fun initializeAudioOutput(): Boolean {
        if (initializationExceptionPendingExceptionHolder.shouldWaitBeforeRetry()) {
            return false
        }

        val audioOutput = buildAudioOutputWithRetry()!!
        this.audioOutput = audioOutput
        val configuration = configuration!!

        this.audioOutputListener = AudioOutputListener(configuration.outputConfig)
        audioOutput.addListener(audioOutputListener!!)
        audioOffloadListener?.onOffloadedPlayback(audioOutput.isOffloadedPlayback)
        if (audioOutput.isOffloadedPlayback) {
            if (configuration.outputConfig.useOffloadGapless) {
                audioOutput.setOffloadDelayPadding(
                    configuration.inputFormat.encoderDelay, configuration.inputFormat.encoderPadding
                )
            }
        }
        if (playerId != null) {
            audioOutput.setPlayerId(playerId!!)
        }
        setVolumeInternal()

        if (auxEffectInfo.effectId != AuxEffectInfo.NO_AUX_EFFECT_ID) {
            audioOutput.attachAuxEffect(auxEffectInfo.effectId)
            audioOutput.setAuxEffectSendLevel(auxEffectInfo.sendLevel)
        }
        if (preferredDevice != null) {
            audioOutput.setPreferredDevice(preferredDevice)
        }
        startMediaTimeUsNeedsInit = true

        val newAudioSessionId = audioOutput.audioSessionId
        val audioSessionIdChanged = newAudioSessionId != audioSessionId
        audioSessionId = newAudioSessionId

        if (listener != null) {
            listener!!.onAudioTrackInitialized(configuration.buildAudioTrackConfig())
            if (audioSessionIdChanged) {
                pendingAudioSessionIdChangeConfirmation = true
                this.configuration = configuration.copyWithOutputConfig(
                    configuration.outputConfig.buildUpon().setAudioSessionId(audioSessionId)
                        .build()
                )
                if (pendingConfiguration != null) {
                    pendingConfiguration =
                        pendingConfiguration!!.copyWithOutputConfig(
                            pendingConfiguration!!
                                .outputConfig
                                .buildUpon()
                                .setAudioSessionId(audioSessionId)
                                .build()
                        )
                }
                listener!!.onAudioSessionIdChanged(audioSessionId)
            }
        }
        return true
    }

    override fun play() {
        playing = true
        if (this.isAudioOutputInitialized) {
            audioOutput!!.play()
        }
    }

    override fun handleDiscontinuity() {
        startMediaTimeUsNeedsSync = true
    }

    @Throws(AudioSink.InitializationException::class, AudioSink.WriteException::class)
    override fun handleBuffer(
        buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int
    ): Boolean {
        Preconditions.checkArgument(inputBuffer == null || buffer === inputBuffer)

        if (pendingConfiguration != null) {
            if (!drainToEndOfStream()) {
                // There's still pending data in audio processors to write to the output.
                return false
            } else if (audioOutput != null
                && !audioOutput!!.canReuseAudioOutput(
                    configuration!!.outputConfig,
                    getFormatConfig(pendingConfiguration!!.afterProcessingInputFormat),
                    pendingConfiguration!!.outputConfig
                )
            ) {
                playPendingData()
                if (hasPendingData()) {
                    // We're waiting for playout on the current audio output to finish.
                    return false
                }
                flush()
            } else {
                // The current audio output can be reused for the new configuration.
                configuration = pendingConfiguration
                pendingConfiguration = null
                if (audioOutput != null && audioOutput!!.isOffloadedPlayback
                    && configuration!!.outputConfig.useOffloadGapless
                ) {
                    audioOutput!!.setOffloadEndOfStream()
                    audioOutput!!.setOffloadDelayPadding(
                        configuration!!.inputFormat.encoderDelay,
                        configuration!!.inputFormat.encoderPadding
                    )
                    isWaitingForOffloadEndOfStreamHandled = true
                }
            }
            // Re-apply playback parameters.
            applyAudioProcessorPlaybackParametersAndSkipSilence(presentationTimeUs)
        }

        if (!this.isAudioOutputInitialized) {
            try {
                if (!initializeAudioOutput()) {
                    // Not yet ready for initialization of a new audio output.
                    return false
                }
            } catch (e: AudioSink.InitializationException) {
                if (e.isRecoverable) {
                    throw e // Do not delay the exception if it can be recovered at higher level.
                }
                initializationExceptionPendingExceptionHolder.throwExceptionIfDeadlineIsReached(e)
                return false
            }
        }
        initializationExceptionPendingExceptionHolder.clear()

        if (startMediaTimeUsNeedsInit) {
            startMediaTimeUs = max(0, presentationTimeUs)
            startMediaTimeUsNeedsSync = false
            startMediaTimeUsNeedsInit = false

            if (useAudioOutputPlaybackParams()) {
                setAudioOutputPlaybackParameters()
            }
            applyAudioProcessorPlaybackParametersAndSkipSilence(presentationTimeUs)
            if (playing) {
                play()
            }
        }

        if (inputBuffer == null) {
            // We are seeing this buffer for the first time.
            Preconditions.checkArgument(buffer.order() == ByteOrder.LITTLE_ENDIAN)
            if (!buffer.hasRemaining()) {
                // The buffer is empty.
                return true
            }
            if (!configuration!!.isPcm && framesPerEncodedSample == 0) {
                // If this is the first encoded sample, calculate the sample size in frames.
                framesPerEncodedSample =
                    getFramesPerEncodedSample(configuration!!.outputConfig.encoding, buffer)
                if (framesPerEncodedSample == 0) {
                    // We still don't know the number of frames per sample, so drop the buffer.
                    // For TrueHD this can occur after some seek operations, as not every sample starts with
                    // a syncframe header. If we chunked samples together so the extracted samples always
                    // started with a syncframe header, the chunks would be too large.
                    return true
                }
            }
            if (afterDrainParameters != null) {
                if (!drainToEndOfStream()) {
                    // Don't process any more input until draining completes.
                    return false
                }
                applyAudioProcessorPlaybackParametersAndSkipSilence(presentationTimeUs)
                afterDrainParameters = null
            }
            // Check that presentationTimeUs is consistent with the expected value.
            val expectedPresentationTimeUs =
                (startMediaTimeUs
                        + configuration!!.inputFramesToDurationUs(
                    this.submittedFrames - trimmingAudioProcessor.trimmedFrameCount
                ))
            if (!startMediaTimeUsNeedsSync
                && abs(expectedPresentationTimeUs - presentationTimeUs) > 200000
            ) {
                listener?.onAudioSinkError(
                    UnexpectedDiscontinuityException(presentationTimeUs, expectedPresentationTimeUs)
                )
                startMediaTimeUsNeedsSync = true
            }
            if (startMediaTimeUsNeedsSync) {
                if (!drainToEndOfStream()) {
                    // Don't update timing until pending AudioProcessor buffers are completely drained.
                    return false
                }
                // Adjust startMediaTimeUs to be consistent with the current buffer's start time and the
                // number of bytes submitted.
                val adjustmentUs = presentationTimeUs - expectedPresentationTimeUs
                startMediaTimeUs += adjustmentUs
                startMediaTimeUsNeedsSync = false
                // Re-apply playback parameters because the startMediaTimeUs changed.
                applyAudioProcessorPlaybackParametersAndSkipSilence(presentationTimeUs)
                if (listener != null && adjustmentUs != 0L) {
                    listener!!.onPositionDiscontinuity()
                }
            }

            if (configuration!!.isPcm) {
                submittedPcmBytes += buffer.remaining().toLong()
            } else {
                submittedEncodedFrames += framesPerEncodedSample.toLong() * encodedAccessUnitCount
            }
            inputBuffer = buffer
            inputBufferAccessUnitCount = encodedAccessUnitCount
        }

        processBuffers(presentationTimeUs)

        if (!inputBuffer!!.hasRemaining()) {
            inputBuffer = null
            inputBufferAccessUnitCount = 0
            return true
        }
        if (audioOutput!!.isStalled) {
            w(TAG, "Resetting stalled audio output")
            flush()
            return true
        }
        return false
    }

    @Throws(AudioSink.InitializationException::class)
    private fun buildAudioOutputWithRetry(): AudioOutput? {
        val configuration = configuration!!
        try {
            return buildAudioOutput(configuration.outputConfig)
        } catch (initialFailure: AudioSink.InitializationException) {
            var bufferSize = configuration.outputConfig.bufferSize

            val threshold = this.minimumRetryBufferSize
            val frameSize =
                if (configuration.outputPcmFrameSize != C.LENGTH_UNSET) configuration.outputPcmFrameSize else 1

            while (bufferSize > threshold) {
                // Retry with a smaller buffer size, which is at least the threshold.
                bufferSize = max(threshold, bufferSize / 2)

                // Align bufferSize to frame size.
                val partialFrameSize = bufferSize % frameSize
                if (partialFrameSize != 0) {
                    bufferSize += frameSize - partialFrameSize
                }

                val retryConfiguration =
                    configuration.outputConfig.buildUpon().setBufferSize(bufferSize).build()
                try {
                    val audioOutput = buildAudioOutput(retryConfiguration)
                    this.configuration = configuration.copyWithOutputConfig(retryConfiguration)
                    return audioOutput
                } catch (retryFailure: AudioSink.InitializationException) {
                    initialFailure.addSuppressed(retryFailure)
                }
            }
            maybeDisableOffload()
            throw initialFailure
        }
    }

    private val minimumRetryBufferSize: Int
        get() {
            val configuration = configuration!!
            val oneSecondBufferSize: Int
            if (isEncodingLinearPcm(configuration.outputConfig.encoding)) {
                oneSecondBufferSize =
                    configuration.outputConfig.sampleRate * configuration.outputPcmFrameSize
            } else if (configuration.inputFormat.bitrate != Format.NO_VALUE) {
                oneSecondBufferSize = configuration.inputFormat.bitrate / 8
            } else {
                val maxRate =
                    getMaximumEncodedRateBytesPerSecond(configuration.outputConfig.encoding)
                oneSecondBufferSize =
                    if (maxRate != C.RATE_UNSET_INT) maxRate else AUDIO_OUTPUT_RETRY_FALLBACK_BUFFER_SIZE
            }
            val minBufferSize =
                AudioTrack.getMinBufferSize(
                    configuration.outputConfig.sampleRate,
                    configuration.outputConfig.channelMask,
                    configuration.outputConfig.encoding
                )

            return max(oneSecondBufferSize, minBufferSize)
        }

    @Throws(AudioSink.InitializationException::class)
    private fun buildAudioOutput(outputConfig: OutputConfig): AudioOutput? {
        try {
            return audioOutputProvider.getAudioOutput(outputConfig)
        } catch (e: AudioOutputProvider.InitializationException) {
            val exception =
                AudioSink.InitializationException(
                    AudioTrack.STATE_UNINITIALIZED,
                    outputConfig.sampleRate,
                    outputConfig.channelMask,
                    outputConfig.encoding,
                    outputConfig.bufferSize,
                    configuration!!.inputFormat,  
                    outputConfig.isOffload,
                    e
                )
            if (listener != null) {
                listener!!.onAudioSinkError(exception)
            }
            throw exception
        }
    }

    @Throws(AudioSink.WriteException::class)
    private fun processBuffers(avSyncPresentationTimeUs: Long) {
        // Drain existing buffer first.
        drainOutputBuffer(avSyncPresentationTimeUs)
        if (outputBuffer != null) {
            // The existing output buffer is not fully processed.
            return
        }

        // Obtain new output buffer and start draining.
        if (!audioProcessingPipeline!!.isOperational) {
            if (inputBuffer != null) {
                setOutputBuffer(inputBuffer!!)
                drainOutputBuffer(avSyncPresentationTimeUs)
            }
            return
        }

        while (!audioProcessingPipeline!!.isEnded) {
            var bufferToWrite: ByteBuffer?
            while ((audioProcessingPipeline!!.output
                    .also { bufferToWrite = it })!!.hasRemaining()
            ) {
                setOutputBuffer(bufferToWrite!!)
                drainOutputBuffer(avSyncPresentationTimeUs)
                if (outputBuffer != null) {
                    // drainOutputBuffer method is providing back pressure.
                    return
                }
            }
            if (inputBuffer == null || !inputBuffer!!.hasRemaining()) {
                return
            }
            audioProcessingPipeline!!.queueInput(inputBuffer!!)
        }
    }

    @Throws(AudioSink.WriteException::class)
    private fun drainToEndOfStream(): Boolean {
        if (!audioProcessingPipeline!!.isOperational) {
            drainOutputBuffer(C.TIME_END_OF_SOURCE)
            return outputBuffer == null
        }

        audioProcessingPipeline!!.queueEndOfStream()
        processBuffers(C.TIME_END_OF_SOURCE)
        return audioProcessingPipeline!!.isEnded
                && (outputBuffer == null || !outputBuffer!!.hasRemaining())
    }

    private fun setOutputBuffer(buffer: ByteBuffer) {
        Preconditions.checkState(outputBuffer == null)
        if (!buffer.hasRemaining()) {
            return
        }
        outputBuffer = maybeRampUpVolume(buffer)
    }

    @Throws(AudioSink.WriteException::class)
    private fun drainOutputBuffer(avSyncPresentationTimeUs: Long) {
        if (outputBuffer == null) {
            return
        }
        if (writeExceptionPendingExceptionHolder.shouldWaitBeforeRetry()) {
            return
        }
        val bytesRemaining = outputBuffer!!.remaining()
        val fullyHandled: Boolean
        try {
            fullyHandled =
                audioOutput!!.write(
                    outputBuffer!!,
                    inputBufferAccessUnitCount,
                    avSyncPresentationTimeUs
                )
        } catch (e: AudioOutput.WriteException) {
            // Treat a write error on a previously successful offload channel as recoverable
            // without disabling offload. Offload will be disabled if offload channel was not successfully
            // written to or when a new AudioOutput is created, if no longer supported.
            var shouldRetry = false
            if (e.isRecoverable) {
                if (this.writtenFrames > 0) {
                    shouldRetry = true
                } else if (audioOutput!!.isOffloadedPlayback) {
                    maybeDisableOffload()
                    shouldRetry = true
                }
            }

            val error =
                AudioSink.WriteException(e.errorCode, configuration!!.inputFormat, shouldRetry)
            if (listener != null) {
                listener!!.onAudioSinkError(error)
            }
            if (e.isRecoverable) {
                throw error // Do not delay the exception if it can be recovered at a higher level.
            }
            writeExceptionPendingExceptionHolder.throwExceptionIfDeadlineIsReached(error)
            return
        }

        lastFeedElapsedRealtimeMs = SystemClock.elapsedRealtime()
        writeExceptionPendingExceptionHolder.clear()

        if (audioOutput!!.isOffloadedPlayback) {
            // After calling AudioOutput.setOffloadEndOfStream, the AudioOutput internally stops and
            // restarts during which AudioOutput.write will return 0. This situation must be detected to
            // prevent reporting the buffer as full even though it is not which could lead ExoPlayer to
            // sleep forever waiting for a onDataRequest that will never come.
            if (writtenEncodedFrames > 0) {
                isWaitingForOffloadEndOfStreamHandled = false
            }

            // Consider the offload buffer as full if the AudioOutput is playing and AudioOutput.write
            // could not write all the data provided to it. This relies on the assumption that
            // AudioOutput.write always writes as much as possible.
            if (playing && listener != null && !fullyHandled && !isWaitingForOffloadEndOfStreamHandled) {
                listener!!.onOffloadBufferFull()
            }
        }

        if (configuration!!.isPcm) {
            writtenPcmBytes += (bytesRemaining - outputBuffer!!.remaining()).toLong()
        }
        if (fullyHandled) {
            if (!configuration!!.isPcm) {
                // When playing non-PCM, the inputBuffer is never processed, thus the last inputBuffer
                // must be the current input buffer.
                Preconditions.checkState(outputBuffer === inputBuffer)
                writtenEncodedFrames += framesPerEncodedSample.toLong() * inputBufferAccessUnitCount
            }
            outputBuffer = null
        }
    }

    @Throws(AudioSink.WriteException::class)
    override fun playToEndOfStream() {
        if (!handledEndOfStream && this.isAudioOutputInitialized && drainToEndOfStream()) {
            playPendingData()
            handledEndOfStream = true
        }
    }

    private fun maybeDisableOffload() {
        if (!configuration!!.outputConfig.isOffload) {
            return
        }
        // Offload was requested, but may not be available. There are cases when this can occur even if
        // AudioManager.isOffloadedPlaybackSupported returned true. For example, due to use of an
        // AudioPlaybackCaptureConfiguration. Disable offload until the sink is next configured.
        offloadDisabledUntilNextConfiguration = true
    }

    override val isEnded: Boolean
        get() = !this.isAudioOutputInitialized || (handledEndOfStream && !hasPendingData())

    override fun hasPendingData(): Boolean {
        return this.isAudioOutputInitialized
                && (Build.VERSION.SDK_INT < 29 || !audioOutput!!.isOffloadedPlayback || !handledOffloadOnPresentationEnded)
                && hasAudioOutputPendingData(this.writtenFrames)
    }

    override val audioCapabilities: AudioCapabilities?
        get() {
            if (audioOutputProvider is AudioTrackAudioOutputProvider) {
                return (audioOutputProvider as AudioTrackAudioOutputProvider).audioCapabilities
            }
            return null
        }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        this.outputStreamOffsetUs = outputStreamOffsetUs
    }

    override fun setAudioSessionId(audioSessionId: Int) {
        if (pendingAudioSessionIdChangeConfirmation) {
            if (this.audioSessionId == audioSessionId) {
                pendingAudioSessionIdChangeConfirmation = false
            } else {
                return
            }
        }
        if (this.audioSessionId != audioSessionId) {
            this.audioSessionId = audioSessionId
            externalAudioSessionIdProvided = audioSessionId != C.AUDIO_SESSION_ID_UNSET
            reconfigureAndFlush()
        }
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        if (this.auxEffectInfo == auxEffectInfo) {
            return
        }
        val effectId = auxEffectInfo.effectId
        val sendLevel = auxEffectInfo.sendLevel
        if (audioOutput != null) {
            if (this.auxEffectInfo.effectId != effectId) {
                audioOutput!!.attachAuxEffect(effectId)
            }
            if (effectId != AuxEffectInfo.NO_AUX_EFFECT_ID) {
                audioOutput!!.setAuxEffectSendLevel(sendLevel)
            }
        }
        this.auxEffectInfo = auxEffectInfo
    }

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {
        this.preferredDevice = audioDeviceInfo
        if (audioOutput != null) {
            audioOutput!!.setPreferredDevice(this.preferredDevice)
        }
    }

    override fun setVirtualDeviceId(virtualDeviceId: Int) {
        var virtualDeviceId = virtualDeviceId
        virtualDeviceId = resolveDefaultVirtualDeviceIds(virtualDeviceId)
        if (this.virtualDeviceId == virtualDeviceId) {
            return
        }
        this.virtualDeviceId = virtualDeviceId
        reconfigureAndFlush()
    }

    override val audioTrackBufferSizeUs: Long
        get() {
            if (!this.isAudioOutputInitialized) {
                return C.TIME_UNSET
            }
            return if (configuration!!.isPcm)
                configuration!!.framesToDurationUs(audioOutput!!.bufferSizeInFrames)
            else
                scaleLargeValue(
                    audioOutput!!.bufferSizeInFrames,
                    C.MICROS_PER_SECOND,
                    getNonPcmMaximumEncodedRateBytesPerSecond(configuration!!.outputConfig.encoding).toLong(),
                    RoundingMode.DOWN
                )
        }

    override fun enableTunnelingV21() {
        Preconditions.checkState(externalAudioSessionIdProvided)
        if (!tunneling) {
            tunneling = true
            reconfigureAndFlush()
        }
    }

    override fun disableTunneling() {
        if (tunneling) {
            tunneling = false
            reconfigureAndFlush()
        }
    }

    @RequiresApi(29)
    override fun setOffloadMode(offloadMode: @OffloadMode Int) {
        Preconditions.checkState(Build.VERSION.SDK_INT >= 29)
        this.offloadMode = offloadMode
    }

    @RequiresApi(29)
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {
        if (audioOutput != null && audioOutput!!.isOffloadedPlayback
            && configuration != null && configuration!!.outputConfig.useOffloadGapless
        ) {
            audioOutput!!.setOffloadDelayPadding(delayInFrames, paddingInFrames)
        }
    }

    override fun setAudioOutputProvider(audioOutputProvider: AudioOutputProvider) {
        if (audioOutputProvider == this.audioOutputProvider) {
            return
        }
        this.audioOutputProvider.release()
        this.audioOutputProvider = audioOutputProvider
        if (audioOutputProviderListener != null) {
            audioOutputProvider.addListener(audioOutputProviderListener!!)
        }
        reconfigureAndFlush()
    }

    override fun setVolume(volume: Float) {
        if (this.volume != volume) {
            this.volume = volume
            setVolumeInternal()
        }
    }

    private fun setVolumeInternal() {
        if (this.isAudioOutputInitialized) {
            audioOutput!!.setVolume(volume)
        }
    }

    override fun pause() {
        playing = false
        if (this.isAudioOutputInitialized) {
            audioOutput!!.pause()
        }
    }

    override fun flush() {
        if (this.isAudioOutputInitialized) {
            resetSinkStateForFlush()
            audioOutputListener = null
            if (pendingConfiguration != null) {
                configuration = pendingConfiguration
                pendingConfiguration = null
            }
            // We need to release the audio output on every flush because of known AudioTrack flush issues
            // on some devices. See b/7941810 or b/19193985.
            pendingReleaseCount.incrementAndGet()
            audioOutput!!.release()
            audioOutput = null
        }
        writeExceptionPendingExceptionHolder.clear()
        initializationExceptionPendingExceptionHolder.clear()
        skippedOutputFrameCountAtLastPosition = 0
        accumulatedSkippedSilenceDurationUs = 0
        if (reportSkippedSilenceHandler != null) {
            reportSkippedSilenceHandler!!.removeCallbacksAndMessages(null)
        }
    }

    override fun reset() {
        flush()
        for (audioProcessor in availableAudioProcessors) {
            audioProcessor.reset()
        }
        toInt16PcmAudioProcessor.reset()
        toFloatPcmAudioProcessor.reset()

        if (audioProcessingPipeline != null) {
            audioProcessingPipeline!!.reset()
        }
        playing = false
        offloadDisabledUntilNextConfiguration = false
    }

    override fun release() {
        audioOutputProvider.release()
    }

    // Internal methods.
    private fun reconfigureAndFlush() {
        if (configuration != null) {
            if (pendingConfiguration != null) {
                configuration = pendingConfiguration
                pendingConfiguration = null
            }
            val outputConfig: OutputConfig
            try {
                outputConfig = audioOutputProvider.getOutputConfig(
                    getFormatConfig(configuration!!.afterProcessingInputFormat)
                )
            } catch (e: AudioOutputProvider.ConfigurationException) {
                // Shouldn't usually happen if the configuration succeeded with same setup before.
                throw IllegalStateException(
                    AudioSink.ConfigurationException(
                        e,
                        configuration!!.inputFormat
                    )
                )
            }
            configuration = configuration!!.copyWithOutputConfig(outputConfig)
        }
        flush()
    }

    private fun resetSinkStateForFlush() {
        submittedPcmBytes = 0
        submittedEncodedFrames = 0
        writtenPcmBytes = 0
        writtenEncodedFrames = 0
        isWaitingForOffloadEndOfStreamHandled = false
        framesPerEncodedSample = 0
        mediaPositionParameters =
            MediaPositionParameters(_playbackParameters, 0, 0)
        startMediaTimeUs = 0
        afterDrainParameters = null
        mediaPositionParametersCheckpoints.clear()
        inputBuffer = null
        inputBufferAccessUnitCount = 0
        outputBuffer = null
        stoppedAudioOutput = false
        handledEndOfStream = false
        handledOffloadOnPresentationEnded = false
        trimmingAudioProcessor.resetTrimmedFrameCount()
        setupAudioProcessors(C.TIME_UNSET)
    }

    private fun setAudioOutputPlaybackParameters() {
        if (this.isAudioOutputInitialized) {
            audioOutput!!.playbackParameters = (_playbackParameters)
            // Update the speed using the actual effective speed from the audio output.
            _playbackParameters = audioOutput!!.playbackParameters
        }
    }

    private fun setAudioProcessorPlaybackParameters(playbackParameters: PlaybackParameters) {
        val mediaPositionParameters =
            MediaPositionParameters(
                playbackParameters,  
                C.TIME_UNSET,  
                C.TIME_UNSET
            )
        if (this.isAudioOutputInitialized) {
            // Drain the audio processors so we can determine the frame position at which the new
            // parameters apply.
            this.afterDrainParameters = mediaPositionParameters
        } else {
            // Update the audio processor chain parameters now. They will be applied to the audio
            // processors during initialization.
            this.mediaPositionParameters = mediaPositionParameters
        }
    }

    private fun applyAudioProcessorPlaybackParametersAndSkipSilence(presentationTimeUs: Long) {
        val audioProcessorPlaybackParameters: PlaybackParameters?
        if (!useAudioOutputPlaybackParams()) {
            _playbackParameters =
                if (shouldApplyAudioProcessorPlaybackParameters())
                    audioProcessorChain!!.applyPlaybackParameters(_playbackParameters)!!
                else
                    PlaybackParameters.DEFAULT
            audioProcessorPlaybackParameters = _playbackParameters
        } else {
            audioProcessorPlaybackParameters = PlaybackParameters.DEFAULT
        }
        skipSilenceEnabled =
            if (shouldApplyAudioProcessorPlaybackParameters())
                audioProcessorChain!!.applySkipSilenceEnabled(skipSilenceEnabled)
            else
                DEFAULT_SKIP_SILENCE
        mediaPositionParametersCheckpoints.add(
            MediaPositionParameters(
                audioProcessorPlaybackParameters,  
                max(0, presentationTimeUs),  
                configuration!!.framesToDurationUs(this.writtenFrames)
            )
        )
        setupAudioProcessors(presentationTimeUs)
        if (listener != null) {
            listener!!.onSkipSilenceEnabledChanged(skipSilenceEnabled)
        }
    }

    private fun shouldApplyAudioProcessorPlaybackParameters(): Boolean {
        // We don't apply speed/pitch adjustment using an audio processor in the following cases:
        // - in tunneling mode, because audio processing can change the duration of audio yet the video
        //   frame presentation times are currently not modified (see also
        //   https://github.com/google/ExoPlayer/issues/4803);
        // - when playing encoded audio via passthrough/offload, because modifying the audio stream
        //   would require decoding/re-encoding; and
        // - when outputting float PCM audio, because SonicAudioProcessor outputs 16-bit integer PCM.
        return !tunneling && configuration!!.isPcm
                && !shouldUseFloatOutput(configuration!!.inputFormat.pcmEncoding)
    }

    private fun useAudioOutputPlaybackParams(): Boolean {
        return configuration != null && configuration!!.outputConfig.usePlaybackParameters
    }

    private fun shouldUseFloatOutput(pcmEncoding: @C.PcmEncoding Int): Boolean {
        return enableFloatOutput && isEncodingHighResolutionPcm(pcmEncoding)
    }

    private fun applyMediaPositionParameters(positionUs: Long): Long {
        while (!mediaPositionParametersCheckpoints.isEmpty()
            && positionUs >= mediaPositionParametersCheckpoints.getFirst().audioOutputPositionUs
        ) {
            // We are playing (or about to play) media with the new parameters, so update them.
            mediaPositionParameters = mediaPositionParametersCheckpoints.remove()
        }

        val playoutDurationSinceLastCheckpointUs =
            positionUs - mediaPositionParameters.audioOutputPositionUs
        val estimatedMediaDurationSinceLastCheckpointUs =
            getMediaDurationForPlayoutDuration(
                playoutDurationSinceLastCheckpointUs,
                mediaPositionParameters.playbackParameters.speed
            )
        if (mediaPositionParametersCheckpoints.isEmpty()) {
            val actualMediaDurationSinceLastCheckpointUs =
                audioProcessorChain!!.getMediaDuration(playoutDurationSinceLastCheckpointUs)
            val currentMediaPositionUs =
                mediaPositionParameters.mediaTimeUs + actualMediaDurationSinceLastCheckpointUs
            mediaPositionParameters.mediaPositionDriftUs =
                actualMediaDurationSinceLastCheckpointUs - estimatedMediaDurationSinceLastCheckpointUs
            return currentMediaPositionUs
        } else {
            // The processor chain has been configured with new parameters, but we're still playing audio
            // that was processed using previous parameters. We can't scale the playout duration using the
            // processor chain in this case, so we fall back to scaling using the previous parameters'
            // target speed instead.
            return (mediaPositionParameters.mediaTimeUs
                    + estimatedMediaDurationSinceLastCheckpointUs
                    + mediaPositionParameters.mediaPositionDriftUs)
        }
    }

    private fun applySkipping(positionUs: Long): Long {
        val skippedOutputFrameCountAtCurrentPosition =
            audioProcessorChain!!.skippedOutputFrameCount
        val adjustedPositionUs =
            positionUs + configuration!!.framesToDurationUs(skippedOutputFrameCountAtCurrentPosition)
        if (skippedOutputFrameCountAtCurrentPosition > skippedOutputFrameCountAtLastPosition) {
            val silenceDurationUs =
                configuration!!.framesToDurationUs(
                    skippedOutputFrameCountAtCurrentPosition - skippedOutputFrameCountAtLastPosition
                )
            skippedOutputFrameCountAtLastPosition = skippedOutputFrameCountAtCurrentPosition
            handleSkippedSilence(silenceDurationUs)
        }
        return adjustedPositionUs
    }

    private fun handleSkippedSilence(silenceDurationUs: Long) {
        accumulatedSkippedSilenceDurationUs += silenceDurationUs
        if (reportSkippedSilenceHandler == null) {
            reportSkippedSilenceHandler = Handler(Looper.myLooper()!!)
        }
        reportSkippedSilenceHandler!!.removeCallbacksAndMessages(null)
        reportSkippedSilenceHandler!!.postDelayed(
            Runnable { this.maybeReportSkippedSilence() },  
            REPORT_SKIPPED_SILENCE_DELAY_MS.toLong()
        )
    }

    private val isAudioOutputInitialized: Boolean
        get() = audioOutput != null

    private val submittedFrames: Long
        get() = if (configuration!!.isPcm)
            (submittedPcmBytes / configuration!!.inputPcmFrameSize)
        else
            submittedEncodedFrames

    private val writtenFrames: Long
        get() = if (configuration!!.isPcm)
            ceilDivide(
                writtenPcmBytes,
                configuration!!.outputPcmFrameSize.toLong()
            )
        else
            writtenEncodedFrames

    private fun maybeAddAudioOutputProviderListener() {
        if (audioOutputProviderListener == null && context != null) {
            // Must be lazily initialized to receive listener events on the current (playback) thread as
            // the constructor is not called in the playback thread.
            audioOutputProviderListener =
                AudioOutputProvider.Listener {
                    listener?.onAudioCapabilitiesChanged()
                }
            audioOutputProvider.addListener(audioOutputProviderListener!!)
        }
    }

    private fun getFormatConfig(format: Format): FormatConfig {
        return getFormatConfig(format,  C.LENGTH_UNSET)
    }

    private fun getFormatConfig(format: Format, preferredBufferSize: Int): FormatConfig {
        return FormatConfig.Builder(format)
            .setAudioAttributes(audioAttributes!!)
            .setEnableHighResolutionPcmOutput(enableFloatOutput)
            .setEnablePlaybackParameters(preferAudioOutputPlaybackParameters)
            .setEnableOffload(offloadMode != AudioSink.OFFLOAD_MODE_DISABLED)
            .setPreferredDevice(preferredDevice)
            .setAudioSessionId(audioSessionId)
            .setEnableTunneling(tunneling)
            .setPreferredBufferSize(preferredBufferSize)
            .setVirtualDeviceId(virtualDeviceId)
            .build()
    }

    private fun playPendingData() {
        if (!stoppedAudioOutput) {
            stoppedAudioOutput = true
            if (audioOutput!!.isOffloadedPlayback) {
                // Reset handledOffloadOnPresentationEnded to track completion after
                // this following stop call.
                handledOffloadOnPresentationEnded = false
            }
            audioOutput!!.stop()
        }
    }

    private fun maybeRampUpVolume(buffer: ByteBuffer): ByteBuffer? {
        if (!configuration!!.isPcm) {
            return buffer
        }
        val rampDurationUs: Long = msToUs(AUDIO_OUTPUT_VOLUME_RAMP_TIME_MS)
        val rampFrameCount =
            durationUsToSampleCount(rampDurationUs, configuration!!.outputConfig.sampleRate).toInt()
        val writtenFrames = this.writtenFrames
        if (writtenFrames >= rampFrameCount) {
            return buffer
        }
        return PcmAudioUtil.rampUpVolume(
            buffer,
            configuration!!.outputConfig.encoding,
            configuration!!.outputPcmFrameSize,
            writtenFrames.toInt(),
            rampFrameCount
        )
    }

    private fun hasAudioOutputPendingData(writtenFrames: Long): Boolean {
        val currentPositionFrames =
            durationUsToSampleCount(
                audioOutput!!.positionUs,
                audioOutput!!.sampleRate
            )
        return writtenFrames > currentPositionFrames
    }

    private inner class AudioOutputListener(private val outputConfig: OutputConfig) :
        AudioOutput.Listener {
        override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) {
            if (this != audioOutputListener) {
                // Stale event.
                return
            }
            if (listener != null) {
                listener!!.onPositionAdvancing(playoutStartSystemTimeMs)
            }
        }

        override fun onOffloadDataRequest() {
            if (this != audioOutputListener) {
                // Stale event.
                return
            }
            if (listener != null && playing) {
                // Do not signal that the buffer is emptying if not playing as it is a transient
                // state.
                listener!!.onOffloadBufferEmptying()
            }
        }

        override fun onOffloadPresentationEnded() {
            if (this != audioOutputListener) {
                // Stale event.
                return
            }
            // Without checking `stoppedAudioOutput`, during playlist playback with offloaded audio,
            // `hasPendingData` could return false prematurely. We only update
            // `handledOffloadOnPresentationEnded` to true if the audio output has actually finished
            // playing.
            if (stoppedAudioOutput) {
                handledOffloadOnPresentationEnded = true
            }
        }

        override fun onUnderrun() {
            if (this != audioOutputListener) {
                // Stale event.
                return
            }
            val configuration = configuration!!
            if (listener != null) {
                val bufferSizeUs =
                    if (configuration.outputPcmFrameSize != C.LENGTH_UNSET)
                        sampleCountToDurationUs(
                            (configuration.outputConfig.bufferSize / configuration.outputPcmFrameSize).toLong(),
                            audioOutput!!.sampleRate
                        )
                    else
                        C.TIME_UNSET
                val elapsedSinceLastFeedMs =
                    SystemClock.elapsedRealtime() - lastFeedElapsedRealtimeMs
                listener!!.onUnderrun(
                    configuration.outputConfig.bufferSize,
                    usToMs(bufferSizeUs),
                    elapsedSinceLastFeedMs
                )
            }
        }

        override fun onReleased() {
            // Don't check for stale events. It's expected that this event arrives after the class field
            // has been updated to null or a new listener.
            pendingReleaseCount.getAndDecrement()
            if (listener != null) {
                listener!!.onAudioTrackReleased(
                    AudioTrackConfig(
                        outputConfig.encoding,
                        outputConfig.sampleRate,
                        outputConfig.channelMask,
                        outputConfig.isTunneling,
                        outputConfig.isOffload,
                        outputConfig.bufferSize
                    )
                )
            }
        }
    }

    private class MediaPositionParameters(
        val playbackParameters: PlaybackParameters,
        val mediaTimeUs: Long,
        val audioOutputPositionUs: Long
    ) {
        var mediaPositionDriftUs: Long = 0
    }

    private class Configuration(
        val inputFormat: Format,
        val afterProcessingInputFormat: Format,
        val inputPcmFrameSize: Int,
        val outputPcmFrameSize: Int,
        val outputConfig: OutputConfig,
        val audioProcessingPipeline: AudioProcessingPipeline?,
        val timeline: Timeline,
        val periodUid: Any?
    ) {
        fun copyWithOutputConfig(outputConfig: OutputConfig): Configuration {
            return Configuration(
                inputFormat,
                afterProcessingInputFormat,
                inputPcmFrameSize,
                outputPcmFrameSize,
                outputConfig,
                audioProcessingPipeline,
                timeline,
                periodUid
            )
        }

        fun inputFramesToDurationUs(frameCount: Long): Long {
            return sampleCountToDurationUs(frameCount, inputFormat.sampleRate)
        }

        fun framesToDurationUs(frameCount: Long): Long {
            return sampleCountToDurationUs(frameCount, outputConfig.sampleRate)
        }

        fun buildAudioTrackConfig(): AudioTrackConfig {
            return AudioTrackConfig(
                outputConfig.encoding,
                outputConfig.sampleRate,
                outputConfig.channelMask,
                outputConfig.isTunneling,
                outputConfig.isOffload,
                outputConfig.bufferSize
            )
        }

        val isPcm: Boolean
            get() = inputFormat.sampleMimeType == MimeTypes.AUDIO_RAW
    }

    private class PendingExceptionHolder<T : Exception> {
        private var pendingException: T? = null
        private var throwDeadlineMs: Long
        private var earliestNextRetryTimeMs: Long

        init {
            this.throwDeadlineMs = C.TIME_UNSET
            this.earliestNextRetryTimeMs = C.TIME_UNSET
        }

        @Throws(Throwable::class)
        fun throwExceptionIfDeadlineIsReached(exception: T?) {
            val nowMs = SystemClock.elapsedRealtime()
            if (pendingException == null) {
                pendingException = exception
            }
            if (throwDeadlineMs == C.TIME_UNSET && !hasPendingAudioOutputReleases()) {
                // The audio system has limited shared memory. If there is an ongoing release, the audio
                // output operation could be failing because this shared memory is exhausted (see
                // b/12565083). Only start the retry timer once all pending audio output releases are done.
                throwDeadlineMs = nowMs + RETRY_DURATION_MS
            }
            if (throwDeadlineMs != C.TIME_UNSET && nowMs >= throwDeadlineMs) {
                if (pendingException !== exception) {
                    // All retry exception are probably the same, thus only save the last one to save memory.
                    pendingException!!.addSuppressed(exception!!)
                }
                val pendingException = this.pendingException
                clear()
                throw pendingException!!
            }
            earliestNextRetryTimeMs = nowMs + RETRY_DELAY_MS
        }

        fun shouldWaitBeforeRetry(): Boolean {
            if (pendingException == null) {
                // No pending exception.
                return false
            }
            if (hasPendingAudioOutputReleases()) {
                // Wait until other outputs are released before retrying.
                return true
            }
            return SystemClock.elapsedRealtime() < earliestNextRetryTimeMs
        }

        fun clear() {
            pendingException = null
            throwDeadlineMs = C.TIME_UNSET
            earliestNextRetryTimeMs = C.TIME_UNSET
        }

        companion object {
            private const val RETRY_DURATION_MS = 200
            private const val RETRY_DELAY_MS = 50
        }
    }

    private fun maybeReportSkippedSilence() {
        if (accumulatedSkippedSilenceDurationUs >= MINIMUM_REPORT_SKIPPED_SILENCE_DURATION_US) {
            // If the existing silence is already long enough, report the silence
            listener!!.onSilenceSkipped()
            accumulatedSkippedSilenceDurationUs = 0
        }
    }

    companion object {
        private const val AUDIO_OUTPUT_RETRY_FALLBACK_BUFFER_SIZE = 1000000
        private const val MINIMUM_REPORT_SKIPPED_SILENCE_DURATION_US = 300000
        private const val REPORT_SKIPPED_SILENCE_DELAY_MS = 100
        private const val AUDIO_OUTPUT_VOLUME_RAMP_TIME_MS = 20
        const val DEFAULT_PLAYBACK_SPEED: Float = 1f
        const val MIN_PLAYBACK_SPEED: Float = 0.1f
        const val MAX_PLAYBACK_SPEED: Float = 8f
        const val MIN_PITCH: Float = 0.1f
        const val MAX_PITCH: Float = 8f
        private const val DEFAULT_SKIP_SILENCE = false
        const val OUTPUT_MODE_PCM: Int = 0
        const val OUTPUT_MODE_OFFLOAD: Int = 1
        const val OUTPUT_MODE_PASSTHROUGH: Int = 2
        private const val TAG = "DefaultAudioSink"
        private val pendingReleaseCount = AtomicInteger()

        fun getFramesPerEncodedSample(encoding: @C.Encoding Int, buffer: ByteBuffer): Int {
            when (encoding) {
                C.ENCODING_MP3 -> {
                    val headerDataInBigEndian = getBigEndianInt(buffer, buffer.position())
                    val frameCount = parseMpegAudioFrameSampleCount(headerDataInBigEndian)
                    require(frameCount != C.LENGTH_UNSET)
                    return frameCount
                }

                C.ENCODING_AAC_LC -> return AacUtil.AAC_LC_AUDIO_SAMPLE_COUNT
                C.ENCODING_AAC_HE_V1, C.ENCODING_AAC_HE_V2 -> return AacUtil.AAC_HE_AUDIO_SAMPLE_COUNT
                C.ENCODING_AAC_XHE -> return AacUtil.AAC_XHE_AUDIO_SAMPLE_COUNT
                C.ENCODING_AAC_ELD -> return AacUtil.AAC_LD_AUDIO_SAMPLE_COUNT
                C.ENCODING_DTS, C.ENCODING_DTS_HD, C.ENCODING_DTS_UHD_P2 -> return parseDtsAudioSampleCount(
                    buffer
                )

                C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC -> return parseAc3SyncframeAudioSampleCount(
                    buffer
                )

                C.ENCODING_AC4 -> return parseAc4SyncframeAudioSampleCount(buffer)
                C.ENCODING_DOLBY_TRUEHD -> {
                    val syncframeOffset = findTrueHdSyncframeOffset(buffer)
                    return if (syncframeOffset == C.INDEX_UNSET)
                        0
                    else
                        (parseTrueHdSyncframeAudioSampleCount(buffer, syncframeOffset)
                                * Ac3Util.TRUEHD_RECHUNK_SAMPLE_COUNT)
                }

                C.ENCODING_OPUS -> return parseOggPacketAudioSampleCount(buffer)
                C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_8BIT, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT_BIG_ENDIAN, C.ENCODING_PCM_DOUBLE, C.ENCODING_PCM_DOUBLE_BIG_ENDIAN, C.ENCODING_AAC_ER_BSAC, C.ENCODING_DSD, C.ENCODING_INVALID, Format.NO_VALUE -> throw IllegalStateException(
                    "Unexpected audio encoding: $encoding"
                )

                else -> throw IllegalStateException("Unexpected audio encoding: $encoding")
            }
        }

        private fun hasPendingAudioOutputReleases(): Boolean {
            return pendingReleaseCount.get() > 0
        }

        @RequiresApi(34)
        private fun getDeviceIdFromContext(context: Context): Int {
            return resolveDefaultVirtualDeviceIds(context.deviceId)
        }

        private fun resolveDefaultVirtualDeviceIds(deviceId: Int): Int {
            return if (deviceId != Context.DEVICE_ID_DEFAULT && deviceId != Context.DEVICE_ID_INVALID)
                deviceId
            else
                C.INDEX_UNSET
        }

        private fun getNonPcmMaximumEncodedRateBytesPerSecond(encoding: @C.Encoding Int): Int {
            val rate = getMaximumEncodedRateBytesPerSecond(encoding)
            Preconditions.checkState(rate != C.RATE_UNSET_INT)
            return rate
        }
    }
}
