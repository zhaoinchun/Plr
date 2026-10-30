package androidx.media3.exoplayer.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import androidx.annotation.CallSuper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaLibraryInfo.enableWorkarounds
import androidx.media3.common.MimeTypes
import androidx.media3.common.MimeTypes.isAudio
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.CodecSpecificDataUtil.getCodecProfileAndLevel
import androidx.media3.common.util.Log.d
import androidx.media3.common.util.Log.e
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.Util.getPcmEncoding
import androidx.media3.common.util.Util.getPcmFormat
import androidx.media3.common.util.Util.isTv
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.CodecParameters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DecoderReuseEvaluation.DecoderDiscardReasons
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.MediaClock
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RendererCapabilities.AdaptiveSupport
import androidx.media3.exoplayer.RendererCapabilities.DecoderSupport
import androidx.media3.exoplayer.RendererCapabilities.HardwareAccelerationSupport
import androidx.media3.exoplayer.audio.AudioSink.AudioSinkConfig
import androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig
import androidx.media3.exoplayer.mediacodec.LoudnessCodecController
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException
import androidx.media3.extractor.VorbisUtil.getVorbisToAndroidChannelLayoutMapping
import com.google.common.base.MoreObjects
import com.google.common.primitives.ImmutableIntArray
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

class MediaCodecAudioRenderer constructor(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector?,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener,
    audioSink: AudioSink,
    loudnessCodecController: LoudnessCodecController? = if (Build.VERSION.SDK_INT >= 35) LoudnessCodecController() else null
) : MediaCodecRenderer(
    context.applicationContext,
    C.TRACK_TYPE_AUDIO,
    codecAdapterFactory,
    mediaCodecSelector,
    enableDecoderFallback,
    0f
), MediaClock {
    private val context: Context
    private val eventDispatcher: AudioRendererEventListener.EventDispatcher
    private val audioSink: AudioSink
    private val loudnessCodecController: LoudnessCodecController?

    private var codecMaxInputSize = 0
    private var codecNeedsDiscardChannelsWorkaround = false
    private var codecNeedsVorbisToAndroidChannelMappingWorkaround = false
    private var inputFormat: Format? = null

    private var decryptOnlyCodecFormat: Format? = null

    private var currentPositionUs: Long = 0
    private var allowPositionDiscontinuity = false
    private var audioSinkNeedsReset = false
    private var hasPendingReportedSkippedSilence = false
    private var hasReportedAudioPositionAdvancing = false
    private var rendererPriority = 0
    private var isStarted = false
    private var nextBufferToWritePresentationTimeUs: Long = 0
    private var firstNotReadyTimeMs: Long = 0
    private var hasBeenReady = false

    @Deprecated(
        """Use a constructor without {@link AudioCapabilities}. These are obtained
        automatically from the {@link Context}."""
    )
    constructor(
        context: Context,
        mediaCodecSelector: MediaCodecSelector?,
        eventHandler: Handler?,
        eventListener: AudioRendererEventListener,
        audioCapabilities: AudioCapabilities?,
        audioProcessors: Array<AudioProcessor>
    ) : this(
        context,
        mediaCodecSelector,
        eventHandler,
        eventListener,
        DefaultAudioSink.Builder()
            .setAudioCapabilities( // For backward compatibility, null == default.
                MoreObjects.firstNonNull<AudioCapabilities?>(
                    audioCapabilities,
                    AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES
                )
            )
            .setAudioProcessors(audioProcessors)
            .build()
    )

    constructor(
        context: Context,
        mediaCodecSelector: MediaCodecSelector?,
        eventHandler: Handler? = null,
        eventListener: AudioRendererEventListener,
        audioSink: AudioSink = DefaultAudioSink.Builder(context).build()
    ) : this(
        context,
        MediaCodecAdapter.Factory.getDefault(context),
        mediaCodecSelector,  
        false,
        eventHandler,
        eventListener,
        audioSink
    )

    constructor(
        context: Context,
        mediaCodecSelector: MediaCodecSelector?,
        enableDecoderFallback: Boolean,
        eventHandler: Handler?,
        eventListener: AudioRendererEventListener,
        audioSink: AudioSink
    ) : this(
        context,
        MediaCodecAdapter.Factory.getDefault(context),
        mediaCodecSelector,
        enableDecoderFallback,
        eventHandler,
        eventListener,
        audioSink
    )

    init {
        var context = context
        context = context.getApplicationContext()
        this.context = context
        this.audioSink = audioSink
        this.loudnessCodecController = loudnessCodecController
        rendererPriority = C.PRIORITY_PLAYBACK
        eventDispatcher = AudioRendererEventListener.EventDispatcher(eventHandler, eventListener)
        nextBufferToWritePresentationTimeUs = C.TIME_UNSET
        firstNotReadyTimeMs = C.TIME_UNSET
        hasBeenReady = false
    }

    override val name: String
        get() = TAG

    @Throws(DecoderQueryException::class)
    override fun supportsFormat(
        mediaCodecSelector: MediaCodecSelector,
        format: Format
    ): @RendererCapabilities.Capabilities Int {
        if (!isAudio(format.sampleMimeType)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        }
        val formatHasDrm = format.cryptoType != C.CRYPTO_TYPE_NONE
        val supportsFormatDrm = supportsFormatDrm(format)

        var audioOffloadSupport: @RendererCapabilities.AudioOffloadSupport Int =
            RendererCapabilities.AUDIO_OFFLOAD_NOT_SUPPORTED
        // In direct mode, if the format has DRM then we need to use a decoder that only decrypts.
        // Else we don't need a decoder at all.
        if (supportsFormatDrm
            && (!formatHasDrm || MediaCodecUtil.decryptOnlyDecoderInfo != null)
        ) {
            audioOffloadSupport = getAudioOffloadSupport(format)
            if (audioSink.supportsFormat(format)) {
                return RendererCapabilities.create(
                    C.FORMAT_HANDLED,
                    RendererCapabilities.ADAPTIVE_NOT_SEAMLESS,
                    RendererCapabilities.TUNNELING_SUPPORTED,
                    audioOffloadSupport
                )
            }
        }
        // If the input is PCM then it will be passed directly to the sink. Hence the sink must support
        // the input format directly.
        if (MimeTypes.AUDIO_RAW == format.sampleMimeType && !audioSink.supportsFormat(format)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        }
        // For all other input formats, we expect the decoder to output 16-bit PCM.
        if (!audioSink.supportsFormat(
                getPcmFormat(C.ENCODING_PCM_16BIT, format.channelCount, format.sampleRate)
            )
        ) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        }
        val decoderInfos: List<MediaCodecInfo> =
            getDecoderInfos(
                mediaCodecSelector,
                format,
                false,
                audioSink
            )
        if (decoderInfos.isEmpty()) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        }
        if (!supportsFormatDrm) {
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
            if (isFormatSupported && decoderInfo.isSeamlessAdaptationSupported(format))
                RendererCapabilities.ADAPTIVE_SEAMLESS
            else
                RendererCapabilities.ADAPTIVE_NOT_SEAMLESS
        val hardwareAccelerationSupport: @HardwareAccelerationSupport Int =
            if (decoderInfo.hardwareAccelerated)
                RendererCapabilities.HARDWARE_ACCELERATION_SUPPORTED
            else
                RendererCapabilities.HARDWARE_ACCELERATION_NOT_SUPPORTED
        val decoderSupport: @DecoderSupport Int =
            if (isPreferredDecoder) RendererCapabilities.DECODER_SUPPORT_PRIMARY else RendererCapabilities.DECODER_SUPPORT_FALLBACK
        return RendererCapabilities.create(
            formatSupport,
            adaptiveSupport,
            RendererCapabilities.TUNNELING_SUPPORTED,
            hardwareAccelerationSupport,
            decoderSupport,
            audioOffloadSupport
        )
    }

    private fun getAudioOffloadSupport(format: Format): @RendererCapabilities.AudioOffloadSupport Int {
        val audioSinkOffloadSupport = audioSink.getFormatOffloadSupport(format)
        if (!audioSinkOffloadSupport.isFormatSupported) {
            return RendererCapabilities.AUDIO_OFFLOAD_NOT_SUPPORTED
        }
        var audioOffloadSupport: @RendererCapabilities.AudioOffloadSupport Int =
            RendererCapabilities.AUDIO_OFFLOAD_SUPPORTED
        if (audioSinkOffloadSupport.isGaplessSupported) {
            audioOffloadSupport =
                audioOffloadSupport or RendererCapabilities.AUDIO_OFFLOAD_GAPLESS_SUPPORTED
        }
        if (audioSinkOffloadSupport.isSpeedChangeSupported) {
            audioOffloadSupport =
                audioOffloadSupport or RendererCapabilities.AUDIO_OFFLOAD_SPEED_CHANGE_SUPPORTED
        }
        return audioOffloadSupport
    }

    @Throws(DecoderQueryException::class)
    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector, format: Format, requiresSecureDecoder: Boolean
    ): List<MediaCodecInfo> {
        return MediaCodecUtil.getDecoderInfosSortedByFormatSupport(
            context,
            getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder, audioSink),
            format
        )
    }

    override fun shouldUseBypass(format: Format): Boolean {
        if (getConfiguration().offloadModePreferred != AudioSink.OFFLOAD_MODE_DISABLED) {
            val audioOffloadSupport: @RendererCapabilities.AudioOffloadSupport Int =
                getAudioOffloadSupport(format)
            if ((audioOffloadSupport and RendererCapabilities.AUDIO_OFFLOAD_SUPPORTED) != 0
                && ((getConfiguration().offloadModePreferred
                        == AudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_NOT_REQUIRED) || (audioOffloadSupport and RendererCapabilities.AUDIO_OFFLOAD_GAPLESS_SUPPORTED) != 0 || (format.encoderDelay == 0 && format.encoderPadding == 0))
            ) {
                return true
            }
        }
        return audioSink.supportsFormat(format)
    }

    override fun getMediaCodecConfiguration(
        codecInfo: MediaCodecInfo,
        format: Format,
        crypto: MediaCrypto?,
        codecOperatingRate: Float
    ): MediaCodecAdapter.Configuration {
        codecMaxInputSize = getCodecMaxInputSize(codecInfo, format, getStreamFormats())
        codecNeedsDiscardChannelsWorkaround = codecNeedsDiscardChannelsWorkaround(codecInfo.name)
        codecNeedsVorbisToAndroidChannelMappingWorkaround =
            codecNeedsVorbisToAndroidChannelMappingWorkaround(codecInfo.name)
        val mediaFormat =
            getMediaFormat(format, codecInfo.codecMimeType, codecMaxInputSize, codecOperatingRate)
        // Store the input MIME type if we're only using the codec for decryption.
        val decryptOnlyCodecEnabled =
            MimeTypes.AUDIO_RAW == codecInfo.mimeType
                    && MimeTypes.AUDIO_RAW != format.sampleMimeType
        decryptOnlyCodecFormat = if (decryptOnlyCodecEnabled) format else null
        return MediaCodecAdapter.Configuration.createForAudioDecoding(
            codecInfo, mediaFormat, format, crypto, loudnessCodecController
        )
    }

    override fun canReuseCodec(
        codecInfo: MediaCodecInfo,
        oldFormat: Format,
        newFormat: Format,
        isAdaptiveFormatChange: Boolean
    ): DecoderReuseEvaluation {
        val evaluation = codecInfo.canReuseCodec(oldFormat, newFormat)

        var discardReasons: @DecoderDiscardReasons Int = evaluation.discardReasons
        if (isBypassPossible(newFormat)) {
            // We prefer direct audio playback so that for multi-channel tracks the audio is not downmixed
            // to stereo.
            discardReasons =
                discardReasons or DecoderReuseEvaluation.DISCARD_REASON_AUDIO_BYPASS_POSSIBLE
        }
        if (getCodecMaxInputSize(codecInfo, newFormat) > codecMaxInputSize) {
            discardReasons =
                discardReasons or DecoderReuseEvaluation.DISCARD_REASON_MAX_INPUT_SIZE_EXCEEDED
        }

        return DecoderReuseEvaluation(
            codecInfo.name,
            oldFormat,
            newFormat,
            if (discardReasons != 0) DecoderReuseEvaluation.REUSE_RESULT_NO else evaluation.result,
            discardReasons
        )
    }

    override val mediaClock: MediaClock
        get() = this

    override fun getDurationToProgressUs(
        positionUs: Long, elapsedRealtimeUs: Long, isOnBufferAvailableListenerRegistered: Boolean
    ): Long {
        val audioSinkBufferFull =
            audioSink.hasPendingData() && nextBufferToWritePresentationTimeUs != C.TIME_UNSET
        if (!isStarted) {
            // When not started we can only make further progress if the audio track buffer isn't filled
            // yet and there is more data to fill it.
            return if (audioSinkBufferFull || super.isEnded)
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

    override fun getCodecOperatingRateV23(
        targetPlaybackSpeed: Float, format: Format, streamFormats: Array<Format>
    ): Float {
        // Use the highest known stream sample-rate up front, to avoid having to reconfigure the codec
        // should an adaptive switch to that stream occur.
        var maxSampleRate = -1
        for (streamFormat in streamFormats) {
            val streamSampleRate = streamFormat.sampleRate
            if (streamSampleRate != Format.NO_VALUE) {
                maxSampleRate = max(maxSampleRate, streamSampleRate)
            }
        }

        var sampleRate = maxSampleRate
        if (sampleRate == -1) {
            val codecOutputMediaFormat = codecOutputMediaFormat
            if (codecOutputMediaFormat != null
                && codecOutputMediaFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
            ) {
                sampleRate = codecOutputMediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            }
        }

        return if (sampleRate == -1) CODEC_OPERATING_RATE_UNSET else (sampleRate * targetPlaybackSpeed)
    }

    override fun onCodecInitialized(
        name: String,
        configuration: MediaCodecAdapter.Configuration,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) {
        eventDispatcher.decoderInitialized(name, initializedTimestampMs, initializationDurationMs)
    }

    override fun onCodecReleased(name: String) {
        eventDispatcher.decoderReleased(name)
    }

    override fun onCodecError(codecError: Exception) {
        e(TAG, "Audio codec error", codecError)
        eventDispatcher.audioCodecError(codecError)
    }

    @Throws(ExoPlaybackException::class)
    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
        val inputFormat = formatHolder.format!!
        this.inputFormat = inputFormat
        val evaluation = super.onInputFormatChanged(formatHolder)
        eventDispatcher.inputFormatChanged(inputFormat, evaluation)
        return evaluation
    }

    @Throws(ExoPlaybackException::class)
    override fun onOutputFormatChanged(format: Format, mediaFormat: MediaFormat?) {
        val audioSinkInputFormat: Format
        var channelMap: ImmutableIntArray? = null
        if (decryptOnlyCodecFormat != null) { // Direct playback with a codec for decryption.
            audioSinkInputFormat = decryptOnlyCodecFormat!!
        } else if (codec == null) { // Direct playback with codec bypass.
            audioSinkInputFormat = format
        } else {
            mediaFormat!!
            val pcmEncoding: @C.PcmEncoding Int
            if (MimeTypes.AUDIO_RAW == format.sampleMimeType) {
                // For PCM streams, the encoder passes through int samples despite set to float mode.
                pcmEncoding = format.pcmEncoding
            } else if (Build.VERSION.SDK_INT >= 24 && mediaFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                pcmEncoding = mediaFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else if (mediaFormat.containsKey(VIVO_BITS_PER_SAMPLE_KEY)) {
                pcmEncoding = getPcmEncoding(mediaFormat.getInteger(VIVO_BITS_PER_SAMPLE_KEY))
            } else {
                // If the format is anything other than PCM then we assume that the audio decoder will
                // output 16-bit PCM.
                pcmEncoding = C.ENCODING_PCM_16BIT
            }
            val channelCount = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var channelMask = Format.NO_VALUE
            if (format.channelMask != Format.NO_VALUE && format.channelCount == channelCount) {
                channelMask = format.channelMask
            }

            if (mediaFormat.containsKey(MediaFormat.KEY_CHANNEL_MASK)) {
                val mediaFormatChannelMask = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_MASK)
                if (mediaFormatChannelMask != AudioFormat.CHANNEL_INVALID
                    && Integer.bitCount(mediaFormatChannelMask) == channelCount
                ) {
                    channelMask = mediaFormatChannelMask
                }
            }
            audioSinkInputFormat =
                Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_RAW)
                    .setPcmEncoding(pcmEncoding)
                    .setEncoderDelay(format.encoderDelay)
                    .setEncoderPadding(format.encoderPadding)
                    .setMetadata(format.metadata)
                    .setCustomData(format.customData)
                    .setId(format.id)
                    .setLabel(format.label)
                    .setLabels(format.labels)
                    .setLanguage(format.language)
                    .setSelectionFlags(format.selectionFlags)
                    .setRoleFlags(format.roleFlags)
                    .setChannelCount(channelCount)
                    .setChannelMask(channelMask)
                    .setSampleRate(mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    .build()
            if (codecNeedsDiscardChannelsWorkaround
                && audioSinkInputFormat.channelCount == 6 && format.channelCount < 6
            ) {
                val channelMapBuilder =
                    ImmutableIntArray.builder(format.channelCount)
                for (i in 0..<format.channelCount) {
                    channelMapBuilder.add(i)
                }
                channelMap = channelMapBuilder.build()
            } else if (codecNeedsVorbisToAndroidChannelMappingWorkaround) {
                channelMap =
                    getVorbisToAndroidChannelLayoutMapping(audioSinkInputFormat.channelCount)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                if (isBypassEnabled
                    && getConfiguration().offloadModePreferred != AudioSink.OFFLOAD_MODE_DISABLED
                ) {
                    audioSink.setOffloadMode(getConfiguration().offloadModePreferred)
                } else {
                    audioSink.setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)
                }
            }
            audioSink.configure(
                AudioSinkConfig.Builder(audioSinkInputFormat)
                    .setOutputChannelMapping(channelMap)
                    .setTimeline(getTimeline()!!)
                    .setMediaPeriodId(mediaPeriodId)
                    .build()
            )
        } catch (e: AudioSink.ConfigurationException) {
            throw createRendererException(
                e, e.format, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
            )
        }
        updateCodecOperatingRate()
    }

    @CallSuper
    private fun onPositionDiscontinuity() {
        // We are out of sync so allow currentPositionUs to jump backwards.
        allowPositionDiscontinuity = true
    }

    @Throws(ExoPlaybackException::class)
    override fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) {
        super.onEnabled(joining, mayRenderStartOfStream)
        eventDispatcher.enabled(decoderCounters)
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
        super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame)
        audioSink.flush()

        currentPositionUs = positionUs
        nextBufferToWritePresentationTimeUs = C.TIME_UNSET
        firstNotReadyTimeMs = C.TIME_UNSET
        hasBeenReady = false
        hasPendingReportedSkippedSilence = false
        hasReportedAudioPositionAdvancing = false
        allowPositionDiscontinuity = true
    }

    override fun onStarted() {
        super.onStarted()
        audioSink.play()
        isStarted = true
    }

    override fun onStopped() {
        updateCurrentPosition()
        isStarted = false
        audioSink.pause()
        super.onStopped()
        hasReportedAudioPositionAdvancing = false
        firstNotReadyTimeMs = C.TIME_UNSET
        hasBeenReady = false
    }

    override fun onDisabled() {
        audioSinkNeedsReset = true
        inputFormat = null
        nextBufferToWritePresentationTimeUs = C.TIME_UNSET
        hasReportedAudioPositionAdvancing = false
        try {
            audioSink.flush()
        } finally {
            try {
                super.onDisabled()
            } finally {
                eventDispatcher.disabled(decoderCounters)
            }
        }
    }

    override fun onReset() {
        hasPendingReportedSkippedSilence = false
        hasReportedAudioPositionAdvancing = false
        nextBufferToWritePresentationTimeUs = C.TIME_UNSET
        try {
            super.onReset()
        } finally {
            if (audioSinkNeedsReset) {
                audioSinkNeedsReset = false
                audioSink.reset()
            }
        }
    }

    override fun onRelease() {
        audioSink.release()
        if (Build.VERSION.SDK_INT >= 35 && loudnessCodecController != null) {
            loudnessCodecController.release()
        }
    }

    override val isEnded: Boolean
        get() = super.isEnded && audioSink.isEnded

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

    override fun onProcessedStreamChange() {
        super.onProcessedStreamChange()
        audioSink.handleDiscontinuity()
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
        buffer!!
        // Reset nextBufferToWritePresentationTimeUs to default value C.TIME_UNSET for if
        // buffer is skipped, dropped, or written.
        nextBufferToWritePresentationTimeUs = C.TIME_UNSET

        if (decryptOnlyCodecFormat != null
            && (bufferFlags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
        ) {
            // Discard output buffers from the passthrough (raw) decoder containing codec specific data.
            codec!!.releaseOutputBuffer(bufferIndex, false)
            return true
        }

        if (isDecodeOnlyBuffer) {
            if (codec != null) {
                if (DEBUG_LOG_ENABLED) {
                    d(
                        DEBUG_LOG_TAG,
                        ("audio, release output, pts="
                                + bufferPresentationTimeUs
                                + ", pos="
                                + positionUs
                                + ", decode-only")
                    )
                }
                codec.releaseOutputBuffer(bufferIndex, false)
            }
            decoderCounters.skippedOutputBufferCount += sampleCount
            audioSink.handleDiscontinuity()
            return true
        }

        val fullyConsumed: Boolean
        try {
            fullyConsumed = audioSink.handleBuffer(buffer!!, bufferPresentationTimeUs, sampleCount)
        } catch (e: AudioSink.InitializationException) {
            throw createRendererException(
                e,
                inputFormat,
                e.isRecoverable,
                if (isBypassEnabled
                    && getConfiguration().offloadModePreferred != AudioSink.OFFLOAD_MODE_DISABLED
                )
                    PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED
                else
                    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
            )
        } catch (e: AudioSink.WriteException) {
            throw createRendererException(
                e,
                format,
                e.isRecoverable,
                if (isBypassEnabled
                    && getConfiguration().offloadModePreferred != AudioSink.OFFLOAD_MODE_DISABLED
                )
                    PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED
                else
                    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
            )
        }

        if (fullyConsumed) {
            if (codec != null) {
                if (DEBUG_LOG_ENABLED) {
                    d(
                        DEBUG_LOG_TAG,
                        ("audio, release output, pts="
                                + bufferPresentationTimeUs
                                + ", pos="
                                + positionUs
                                + (if (isLastBuffer) ", last-buffer" else ""))
                    )
                }
                codec.releaseOutputBuffer(bufferIndex, false)
            }
            decoderCounters.renderedOutputBufferCount += sampleCount
            return true
        } else {
            // Downstream buffers are full, set nextBufferToWritePresentationTimeUs to the presentation
            // time of the current 'to be written' sample.
            nextBufferToWritePresentationTimeUs = bufferPresentationTimeUs
        }

        return false
    }

    override fun onCodecParametersChanged(codecParameters: CodecParameters?) {
        eventDispatcher.audioCodecParametersChanged(codecParameters!!)
    }

    @Throws(ExoPlaybackException::class)
    override fun renderToEndOfStream() {
        try {
            audioSink.playToEndOfStream()
            if (lastBufferInStreamPresentationTimeUs != C.TIME_UNSET) {
                nextBufferToWritePresentationTimeUs = lastBufferInStreamPresentationTimeUs
            }
        } catch (e: AudioSink.WriteException) {
            throw createRendererException(
                e,
                e.format,
                e.isRecoverable,
                if (isBypassEnabled)
                    PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED
                else
                    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
            )
        }
    }

    override fun onOutputStreamOffsetUsChanged(outputStreamOffsetUs: Long) {
        audioSink.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    @Throws(ExoPlaybackException::class)
    override fun handleMessage(messageType: @Renderer.MessageType Int, message: Any?) {
        when (messageType) {
            Renderer.MSG_SET_VOLUME -> audioSink.setVolume(message as Float)
            Renderer.MSG_SET_AUDIO_ATTRIBUTES -> {
                val audioAttributes = message as AudioAttributes
                audioSink.audioAttributes = (audioAttributes)
            }

            Renderer.MSG_SET_AUX_EFFECT_INFO -> {
                val auxEffectInfo = message as AuxEffectInfo
                audioSink.setAuxEffectInfo(auxEffectInfo)
            }

            Renderer.MSG_SET_PREFERRED_AUDIO_DEVICE -> audioSink.setPreferredDevice(message as AudioDeviceInfo?)
            Renderer.MSG_SET_VIRTUAL_DEVICE_ID -> audioSink.setVirtualDeviceId(
                message as Int
            )

            Renderer.MSG_SET_SKIP_SILENCE_ENABLED -> audioSink.skipSilenceEnabled = (
                    message as Boolean
                    )

            Renderer.MSG_SET_AUDIO_SESSION_ID -> setAudioSessionId(message as Int)
            Renderer.MSG_SET_PRIORITY -> {
                rendererPriority = message as Int
                updateCodecImportance()
            }

            Renderer.MSG_SET_AUDIO_OUTPUT_PROVIDER -> audioSink.setAudioOutputProvider(
                message as AudioOutputProvider
            )

            else -> super.handleMessage(messageType, message)
        }
    }

    override fun handleInputBufferSupplementalData(buffer: DecoderInputBuffer) {
        if (Build.VERSION.SDK_INT >= 29 && buffer!!.format != null && buffer.format!!.sampleMimeType == MimeTypes.AUDIO_OPUS
            && isBypassEnabled
        ) {
            val data = buffer.supplementalData!!
            val preSkip = buffer.format!!.encoderDelay
            if (data.remaining() == 8) {
                val discardSamples = ((data.order(ByteOrder.LITTLE_ENDIAN)
                    .getLong() * 48000L) / C.NANOS_PER_SECOND).toInt()
                audioSink.setOffloadDelayPadding(preSkip, discardSamples)
            }
        }
    }

    private fun getCodecMaxInputSize(
        codecInfo: MediaCodecInfo, format: Format, streamFormats: Array<Format>
    ): Int {
        var maxInputSize = getCodecMaxInputSize(codecInfo, format)
        if (streamFormats.size == 1) {
            // The single entry in streamFormats must correspond to the format for which the codec is
            // being configured.
            return maxInputSize
        }
        for (streamFormat in streamFormats) {
            if (codecInfo.canReuseCodec(
                    format,
                    streamFormat
                ).result != DecoderReuseEvaluation.REUSE_RESULT_NO
            ) {
                maxInputSize = max(maxInputSize, getCodecMaxInputSize(codecInfo, streamFormat))
            }
        }
        return maxInputSize
    }

    private fun getCodecMaxInputSize(codecInfo: MediaCodecInfo, format: Format): Int {
        if ("OMX.google.raw.decoder" == codecInfo.name) {
            // OMX.google.raw.decoder didn't resize its output buffers correctly prior to N, except on
            // Android TV running M, so there's no point requesting a non-default input size. Doing so may
            // cause a native crash, whereas not doing so will cause a more controlled failure when
            // attempting to fill an input buffer. See: https://github.com/google/ExoPlayer/issues/4057.
            if (Build.VERSION.SDK_INT == 23 && !isTv(context)) {
                return Format.NO_VALUE
            }
        }
        return format.maxInputSize
    }

    private fun getMediaFormat(
        format: Format, codecMimeType: String?, codecMaxInputSize: Int, codecOperatingRate: Float
    ): MediaFormat {
        val mediaFormat = MediaFormat()
        // Set format parameters that should always be set.
        mediaFormat.setString(MediaFormat.KEY_MIME, codecMimeType)
        mediaFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channelCount)
        mediaFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
        MediaFormatUtil.setCsdBuffers(mediaFormat, format.initializationData)
        // Set codec max values.
        MediaFormatUtil.maybeSetInteger(
            mediaFormat,
            MediaFormat.KEY_MAX_INPUT_SIZE,
            codecMaxInputSize
        )
        // Set codec configuration values.
        mediaFormat.setInteger(MediaFormat.KEY_PRIORITY, 0 )
        if (codecOperatingRate != CODEC_OPERATING_RATE_UNSET && !deviceDoesntSupportOperatingRate()) {
            mediaFormat.setFloat(MediaFormat.KEY_OPERATING_RATE, codecOperatingRate)
        }
        if (MimeTypes.AUDIO_AC4 == format.sampleMimeType) {
            val profileLevel = getCodecProfileAndLevel(format)
            if (profileLevel != null) {
                MediaFormatUtil.maybeSetInteger(
                    mediaFormat,
                    MediaFormat.KEY_PROFILE,
                    profileLevel.first!!
                )
                MediaFormatUtil.maybeSetInteger(
                    mediaFormat,
                    MediaFormat.KEY_LEVEL,
                    profileLevel.second!!
                )
            }
            if (Build.VERSION.SDK_INT <= 28) {
                // On some older builds, the AC-4 decoder expects to receive samples formatted as raw frames
                // not sync frames. Set a format key to override this.
                mediaFormat.setInteger("ac4-is-sync", 1)
            }
        }
        if (Build.VERSION.SDK_INT >= 24
            && (audioSink.getFormatSupport(
                getPcmFormat(C.ENCODING_PCM_FLOAT, format.channelCount, format.sampleRate)
            )
                    == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY)
        ) {
            mediaFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
        }
        if (Build.VERSION.SDK_INT >= 32) {
            mediaFormat.setInteger(MediaFormat.KEY_MAX_OUTPUT_CHANNEL_COUNT, 99)
        }
        if (Build.VERSION.SDK_INT >= 35) {
            mediaFormat.setInteger(MediaFormat.KEY_IMPORTANCE, max(0, -rendererPriority))
        }

        if (format.sampleMimeType == MimeTypes.AUDIO_IAMF) {
            // IAMF can support many different output Layouts, which are communicated to the Media Codec
            // (IAMF Codec2 software decoder) as a channel mask.  We want to choose one appropriate
            // for the current audio output configuration.
            val audioCapabilities = audioSink.audioCapabilities
            if (audioCapabilities == null) {
                w(
                    TAG,
                    "AudioCapabilities from the AudioSink are null, using default stereo output layout."
                )
                mediaFormat.setInteger(MediaFormat.KEY_CHANNEL_MASK, AudioFormat.CHANNEL_OUT_STEREO)
                mediaFormat.setInteger(MediaFormat.KEY_MAX_OUTPUT_CHANNEL_COUNT, 2)
            } else {
                val channelMask =
                    IamfUtil.getOutputChannelMaskForCurrentConfiguration(audioCapabilities)
                val channelCount = Integer.bitCount(channelMask)
                mediaFormat.setInteger(MediaFormat.KEY_CHANNEL_MASK, channelMask)
                mediaFormat.setInteger(MediaFormat.KEY_MAX_OUTPUT_CHANNEL_COUNT, channelCount)
            }
        }

        applyCodecParametersToMediaFormat(mediaFormat)
        return mediaFormat
    }

    private fun setAudioSessionId(audioSessionId: Int) {
        audioSink.setAudioSessionId(audioSessionId)
        if (Build.VERSION.SDK_INT >= 35 && loudnessCodecController != null) {
            loudnessCodecController.setAudioSessionId(audioSessionId)
        }
    }

    private fun updateCodecImportance() {
        // If codec is null, then the importance will be set when initializing the codec.
        val codec = codec ?: return
        if (Build.VERSION.SDK_INT >= 35) {
            val codecParameters = Bundle()
            codecParameters.putInt(MediaFormat.KEY_IMPORTANCE, max(0, -rendererPriority))
            codec.setParameters(codecParameters)
        }
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
            this@MediaCodecAudioRenderer.onPositionDiscontinuity()
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

        override fun onOffloadBufferEmptying() {
            val wakeupListener = wakeupListener
            wakeupListener?.onWakeup()
        }

        override fun onOffloadBufferFull() {
            val wakeupListener = wakeupListener
            wakeupListener?.onSleep()
        }

        override fun onAudioSinkError(audioSinkError: Exception) {
            e(TAG, "Audio sink error", audioSinkError)
            eventDispatcher.audioSinkError(audioSinkError)
        }

        override fun onAudioCapabilitiesChanged() {
            this@MediaCodecAudioRenderer.onRendererCapabilitiesChanged()
        }

        override fun onAudioTrackInitialized(audioTrackConfig: AudioTrackConfig) {
            eventDispatcher.audioTrackInitialized(audioTrackConfig)
        }

        override fun onAudioTrackReleased(audioTrackConfig: AudioTrackConfig) {
            eventDispatcher.audioTrackReleased(audioTrackConfig)
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            if (Build.VERSION.SDK_INT >= 35 && loudnessCodecController != null) {
                loudnessCodecController.setAudioSessionId(audioSessionId)
            }
            eventDispatcher.audioSessionIdChanged(audioSessionId)
        }
    }

    companion object {
        private const val TAG = "MediaCodecAudioRenderer"
        private const val VIVO_BITS_PER_SAMPLE_KEY = "v-bits-per-sample"
        private const val READY_GRACE_PERIOD_MS: Long = 100

        @Throws(DecoderQueryException::class)
        private fun getDecoderInfos(
            mediaCodecSelector: MediaCodecSelector,
            format: Format,
            requiresSecureDecoder: Boolean,
            audioSink: AudioSink
        ): List<MediaCodecInfo> {
            if (format.sampleMimeType == null) {
                return listOf()
            }
            if (audioSink.supportsFormat(format)) {
                // The format is supported directly, so a codec is only needed for decryption.
                val codecInfo = MediaCodecUtil.decryptOnlyDecoderInfo
                if (codecInfo != null) {
                    return listOf(codecInfo)
                }
            }
            return MediaCodecUtil.getDecoderInfosSoftMatch(
                mediaCodecSelector,
                format,
                requiresSecureDecoder, 
                false
            )
        }

        private fun deviceDoesntSupportOperatingRate(): Boolean {
            return enableWorkarounds() && Build.VERSION.SDK_INT == 23
                    && ("ZTE B2017G" == Build.MODEL || "AXON 7 mini" == Build.MODEL)
        }

        private fun codecNeedsDiscardChannelsWorkaround(codecName: String?): Boolean {
            if (!enableWorkarounds()) {
                return false
            }
            // The workaround applies to Samsung Galaxy S6 and Samsung Galaxy S7.
            return Build.VERSION.SDK_INT < 24 && "OMX.SEC.aac.dec" == codecName
                    && "samsung" == Build.MANUFACTURER
                    && (Build.DEVICE.startsWith("zeroflte")
                    || Build.DEVICE.startsWith("herolte")
                    || Build.DEVICE.startsWith("heroqlte"))
        }

        private fun codecNeedsVorbisToAndroidChannelMappingWorkaround(codecName: String): Boolean {
            return codecName == "OMX.google.opus.decoder"
                    || codecName == "c2.android.opus.decoder"
                    || codecName == "OMX.google.vorbis.decoder"
                    || codecName == "c2.android.vorbis.decoder"
        }
    }
}
