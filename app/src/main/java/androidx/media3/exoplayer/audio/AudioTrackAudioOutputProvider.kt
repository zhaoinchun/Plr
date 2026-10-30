package androidx.media3.exoplayer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.MimeTypes.getEncoding
import androidx.media3.common.util.Clock
import androidx.media3.common.util.ListenerSet
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.Util.getApiLevelThatAudioFormatIntroducedAudioEncoding
import androidx.media3.common.util.Util.getAudioTrackChannelConfig
import androidx.media3.common.util.Util.getPcmFrameSize
import androidx.media3.common.util.Util.getSystemProperty
import androidx.media3.common.util.Util.isEncodingLinearPcm
import androidx.media3.exoplayer.audio.AudioOutputProvider.FormatConfig
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig
import androidx.media3.exoplayer.audio.AudioOutputProvider.SupportLevel
import androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig
import androidx.media3.exoplayer.audio.DefaultAudioSink.AudioOffloadSupportProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink.AudioTrackBufferSizeProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink.AudioTrackProvider
import androidx.media3.extractor.DtsUtil
import com.google.common.base.Preconditions
import com.google.common.base.Supplier
import com.google.common.base.Suppliers
import java.util.function.BiConsumer

class AudioTrackAudioOutputProvider private constructor(builder: Builder) : AudioOutputProvider {
    class Builder(context: Context?) {
        val context: Context? = context?.applicationContext
        var audioTrackBuilderModifier: BiConsumer<AudioTrack.Builder?, OutputConfig?>? =
            null
        var audioOffloadSupportProvider: AudioOffloadSupportProvider? =
            null
        var bufferSizeProvider = AudioTrackBufferSizeProvider.DEFAULT
        var audioCapabilities: AudioCapabilities? = null
        var maxPlaybackSpeed = DefaultAudioSink.MAX_PLAYBACK_SPEED

        @Suppress("deprecation") // Supporting deprecated AudioTrack customization path.
        var audioTrackProvider: AudioTrackProvider? = null

        init {
            if (context == null) {
                audioCapabilities = AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES
            }
        }

        @RequiresApi(24)
        fun setAudioTrackBuilderModifier(
            audioTrackBuilderModifier: BiConsumer<AudioTrack.Builder?, OutputConfig?>?
        ): Builder {
            this.audioTrackBuilderModifier = audioTrackBuilderModifier
            return this
        }

        fun setAudioOffloadSupportProvider(
            audioOffloadSupportProvider: AudioOffloadSupportProvider?
        ): Builder {
            this.audioOffloadSupportProvider = audioOffloadSupportProvider
            return this
        }

        fun setAudioTrackBufferSizeProvider(
            bufferSizeProvider: AudioTrackBufferSizeProvider
        ): Builder {
            this.bufferSizeProvider = bufferSizeProvider
            return this
        }

        fun setMaxPlaybackSpeed(maxPlaybackSpeed: Float): Builder {
            Preconditions.checkArgument(maxPlaybackSpeed >= 1f)
            this.maxPlaybackSpeed = maxPlaybackSpeed
            return this
        }

        fun setAudioCapabilities(audioCapabilities: AudioCapabilities?): Builder {
            if (context == null) {
                this.audioCapabilities = audioCapabilities
            }
            return this
        }

        fun setAudioTrackProvider(audioTrackProvider: AudioTrackProvider?): Builder {
            this.audioTrackProvider = audioTrackProvider
            return this
        }

        fun build(): AudioTrackAudioOutputProvider {
            if (audioOffloadSupportProvider == null) {
                audioOffloadSupportProvider = DefaultAudioOffloadSupportProvider(context)
            }
            return AudioTrackAudioOutputProvider(this)
        }
    }

    private val context: Context? = builder.context

    @Suppress("deprecation") // Supporting deprecated AudioTrack customization path.
    private val audioTrackProvider: AudioTrackProvider? = builder.audioTrackProvider
    private val builderModifier: BiConsumer<AudioTrack.Builder?, OutputConfig?>? =
        builder.audioTrackBuilderModifier
    private val audioTrackBufferSizeProvider = builder.bufferSizeProvider
    private val audioOffloadSupportProvider: AudioOffloadSupportProvider =
        builder.audioOffloadSupportProvider!!
    private val capabilityChangeListener: CapabilityChangeListener? =
        if (builder.context == null) null else CapabilityChangeListener()
    private val maxPlaybackSpeed = builder.maxPlaybackSpeed
    private var listeners: ListenerSet<AudioOutputProvider.Listener>? = null
    private var clock = Clock.DEFAULT

    var audioCapabilities: AudioCapabilities? = builder.audioCapabilities
        private set
    private var audioCapabilitiesReceiver: AudioCapabilitiesReceiver? = null
    private var playbackLooper: Looper? = null
    private var contextWithDeviceId: Context? = null

    override fun getFormatSupport(formatConfig: FormatConfig): AudioOutputProvider.FormatSupport {
        updateAudioCapabilitiesReceiver(formatConfig)
        val offloadSupport =
            audioOffloadSupportProvider.getAudioOffloadSupport(
                formatConfig.format, formatConfig.audioAttributes
            )
        return AudioOutputProvider.FormatSupport.Builder()
            .setFormatSupportLevel(getFormatSupportLevel(formatConfig))
            .setIsFormatSupportedForOffload(offloadSupport.isFormatSupported)
            .setIsGaplessSupportedForOffload(offloadSupport.isGaplessSupported)
            .setIsSpeedChangeSupportedForOffload(offloadSupport.isSpeedChangeSupported)
            .build()
    }

    @Throws(AudioOutputProvider.ConfigurationException::class)
    override fun getOutputConfig(formatConfig: FormatConfig): OutputConfig {
        val format = formatConfig.format
        updateAudioCapabilitiesReceiver(formatConfig)

        val outputMode: @DefaultAudioSink.OutputMode Int
        var outputEncoding: @C.Encoding Int
        var outputSampleRate: Int
        var outputChannelConfig: Int
        val outputPcmFrameSize: Int
        val usePlaybackParameters: Boolean
        var useOffloadGapless = false

        if (format.sampleMimeType == MimeTypes.AUDIO_RAW) {
            Preconditions.checkArgument(isEncodingLinearPcm(format.pcmEncoding))
            outputMode = DefaultAudioSink.OUTPUT_MODE_PCM
            outputEncoding = format.pcmEncoding
            outputSampleRate = format.sampleRate
            outputChannelConfig = getAudioOutputChannelConfig(format)
            outputPcmFrameSize = getPcmFrameSize(outputEncoding, format.channelCount)
            usePlaybackParameters = formatConfig.enablePlaybackParameters
        } else {
            outputSampleRate = format.sampleRate
            outputPcmFrameSize = C.LENGTH_UNSET
            val audioOffloadSupport =
                if (formatConfig.enableOffload)
                    audioOffloadSupportProvider.getAudioOffloadSupport(
                        format, formatConfig.audioAttributes
                    )
                else
                    AudioOffloadSupport.DEFAULT_UNSUPPORTED
            if (formatConfig.enableOffload && audioOffloadSupport.isFormatSupported) {
                outputMode = DefaultAudioSink.OUTPUT_MODE_OFFLOAD
                outputEncoding = getEncoding(
                    format.sampleMimeType!!,
                    format.codecs
                )
                outputChannelConfig = getAudioOutputChannelConfig(format)
                if ((outputEncoding == C.ENCODING_AAC_HE_V1 || outputEncoding == C.ENCODING_AAC_HE_V2)
                    && outputSampleRate >= 16000 && !COMPRESSED_OFFLOAD_EXPLICIT_AAC_ENABLED.get()!!
                ) {
                    if (outputEncoding == C.ENCODING_AAC_HE_V2 && format.channelCount == 2) {
                        outputChannelConfig = AudioFormat.CHANNEL_OUT_MONO
                    }
                    outputEncoding = C.ENCODING_AAC_LC
                    outputSampleRate /= 2
                }
                // Offload requires AudioTrack playback parameters to apply speed changes quickly.
                usePlaybackParameters = true
                useOffloadGapless = audioOffloadSupport.isGaplessSupported
            } else {
                outputMode = DefaultAudioSink.OUTPUT_MODE_PASSTHROUGH
                val encodingAndChannelConfig =
                    audioCapabilities!!.getEncodingAndChannelConfigForPassthrough(
                        format, formatConfig.audioAttributes
                    )
                if (encodingAndChannelConfig == null) {
                    throw AudioOutputProvider.ConfigurationException("Unable to configure passthrough for: $format")
                }
                outputEncoding = encodingAndChannelConfig.first!!
                outputChannelConfig = encodingAndChannelConfig.second!!
                // Passthrough only supports audio output playback parameters, but we only enable it this
                // was specifically requested by the app.
                usePlaybackParameters = formatConfig.enablePlaybackParameters
            }
        }

        // Replace unknown bitrate by maximum allowed bitrate for DTS Express to avoid allocating an
        // AudioTrack buffer for the much larger maximum bitrate of the underlying DTS-HD encoding.
        var bitrate = format.bitrate
        if (format.sampleMimeType == MimeTypes.AUDIO_DTS_EXPRESS
            && bitrate == Format.NO_VALUE
        ) {
            bitrate = DtsUtil.DTS_EXPRESS_MAX_RATE_BITS_PER_SECOND
        }

        val bufferSize =
            if (formatConfig.preferredBufferSize != C.LENGTH_UNSET)
                formatConfig.preferredBufferSize
            else
                audioTrackBufferSizeProvider.getBufferSizeInBytes(
                    getAudioTrackMinBufferSize(
                        outputSampleRate,
                        outputChannelConfig,
                        outputEncoding
                    ),
                    outputEncoding,
                    outputMode,
                    if (outputPcmFrameSize != C.LENGTH_UNSET) outputPcmFrameSize else 1,
                    outputSampleRate,
                    bitrate,
                    (if (usePlaybackParameters) maxPlaybackSpeed else DefaultAudioSink.DEFAULT_PLAYBACK_SPEED).toDouble()
                )
        return OutputConfig.Builder()
            .setSampleRate(outputSampleRate)
            .setChannelMask(outputChannelConfig)
            .setEncoding(outputEncoding)
            .setBufferSize(bufferSize)
            .setAudioSessionId(formatConfig.audioSessionId)
            .setAudioAttributes(formatConfig.audioAttributes)
            .setIsOffload(outputMode == DefaultAudioSink.OUTPUT_MODE_OFFLOAD)
            .setIsTunneling(formatConfig.enableTunneling)
            .setUsePlaybackParameters(usePlaybackParameters)
            .setUseOffloadGapless(useOffloadGapless)
            .setVirtualDeviceId(formatConfig.virtualDeviceId)
            .build()
    }

    @Throws(AudioOutputProvider.InitializationException::class)
    override fun getAudioOutput(config: OutputConfig): AudioTrackAudioOutput {
        val audioTrack: AudioTrack
        try {
            var contextForAudioTrack: Context? = null
            var audioSessionId = config.audioSessionId
            if (config.virtualDeviceId != C.INDEX_UNSET && context != null && Build.VERSION.SDK_INT >= 34) {
                if (contextWithDeviceId == null
                    || contextWithDeviceId!!.deviceId != config.virtualDeviceId
                ) {
                    contextWithDeviceId = context.createDeviceContext(config.virtualDeviceId)
                }
                contextForAudioTrack = contextWithDeviceId
                audioSessionId = AudioManager.AUDIO_SESSION_ID_GENERATE
            }
            if (audioTrackProvider != null) {
                val audioTrackConfig = getAudioTrackConfig(config)
                audioTrack = audioTrackProvider.getAudioTrack(
                    audioTrackConfig,
                    config.audioAttributes,
                    audioSessionId,
                    contextForAudioTrack
                )
            } else {
                // Using C.Encoding as AudioFormat encoding
                val format = AudioFormat.Builder()
                    .setSampleRate(config.sampleRate)
                    .setChannelMask(config.channelMask)
                    .setEncoding(config.encoding)
                    .build()
                val audioTrackAttributes =
                    getAudioTrackAttributes(config.audioAttributes, config.isTunneling)
                val audioTrackBuilder =
                    AudioTrack.Builder()
                        .setAudioAttributes(audioTrackAttributes)
                        .setAudioFormat(format)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(config.bufferSize)
                        .setSessionId(audioSessionId)
                if (Build.VERSION.SDK_INT >= 29) {
                    audioTrackBuilder.setOffloadedPlayback(config.isOffload)
                }
                if (Build.VERSION.SDK_INT >= 34 && contextForAudioTrack != null) {
                    audioTrackBuilder.setContext(contextForAudioTrack)
                }
                if (builderModifier != null && Build.VERSION.SDK_INT >= 24) {
                    builderModifier.accept(audioTrackBuilder, config)
                }
                audioTrack = audioTrackBuilder.build()
            }
        } catch (e: UnsupportedOperationException) {
            throw AudioOutputProvider.InitializationException(e)
        } catch (e: IllegalArgumentException) {
            throw AudioOutputProvider.InitializationException(e)
        }
        if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
            try {
                audioTrack.release()
            } catch (e: Exception) {
                // The track has already failed to initialize, so it wouldn't be that surprising if
                // release were to fail too. Swallow the exception.
            }
            throw AudioOutputProvider.InitializationException()
        }
        return AudioTrackAudioOutput(
            audioTrack, config, capabilityChangeListener, maxPlaybackSpeed, clock
        )
    }

    override fun addListener(listener: AudioOutputProvider.Listener) {
        verifySinglePlaybackLooper()
        if (listeners == null) {
            listeners = ListenerSet(Thread.currentThread())
        }
        listeners!!.add(listener)
    }

    override fun removeListener(listener: AudioOutputProvider.Listener) {
        listeners?.remove(listener)
    }

    override fun setClock(clock: Clock) {
        this.clock = clock
    }

    override fun release() {
        listeners?.release()
        audioCapabilitiesReceiver?.unregister()
    }

    private fun getAudioTrackAttributes(
        audioAttributes: androidx.media3.common.AudioAttributes, tunneling: Boolean
    ): AudioAttributes {
        return if (tunneling) {
            this.audioTrackTunnelingAttributes
        } else {
            audioAttributes.platformAudioAttributes!!
        }
    }

    private val audioTrackTunnelingAttributes: AudioAttributes
        get() = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .setFlags(AudioAttributes.FLAG_HW_AV_SYNC)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()

    fun onAudioCapabilitiesChanged(audioCapabilities: AudioCapabilities) {
        verifySinglePlaybackLooper()
        if (this.audioCapabilities != null && audioCapabilities != this.audioCapabilities) {
            this.audioCapabilities = audioCapabilities
            listeners?.sendEvent { obj: AudioOutputProvider.Listener? -> obj!!.onFormatSupportChanged() }
        }
    }

    private fun getAudioOutputChannelConfig(format: Format): Int {
        return audioTrackProvider?.getAudioTrackChannelConfig(format.channelCount)
            ?: getAudioTrackChannelConfig(format)
    }

    private fun getAudioTrackMinBufferSize(
        sampleRateInHz: Int,
        channelConfig: Int,
        encoding: Int
    ): Int {
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRateInHz, channelConfig, encoding)
        Preconditions.checkState(minBufferSize != AudioTrack.ERROR_BAD_VALUE)
        return minBufferSize
    }

    private fun updateAudioCapabilitiesReceiver(formatConfig: FormatConfig) {
        verifySinglePlaybackLooper()
        if (audioCapabilitiesReceiver == null && context != null) {
            // Must be lazily initialized to receive audio capabilities receiver listener event on the
            // current (playback) thread as the constructor is not called in the playback thread.
            audioCapabilitiesReceiver =
                AudioCapabilitiesReceiver(
                    context,
                    object : AudioCapabilitiesReceiver.Listener {
                        override fun onAudioCapabilitiesChanged(audioCapabilities: AudioCapabilities?) {
                            this.onAudioCapabilitiesChanged(
                                audioCapabilities!!
                            )
                        }
                    },
                    formatConfig.audioAttributes,
                    formatConfig.preferredDevice
                ).also {
                    audioCapabilities = it.register()
                }
        } else if (audioCapabilitiesReceiver != null) {
            if (formatConfig.preferredDevice != null) {
                audioCapabilitiesReceiver!!.setRoutedDevice(formatConfig.preferredDevice)
            }
            audioCapabilitiesReceiver!!.setAudioAttributes(formatConfig.audioAttributes)
        }
        audioCapabilities!!
    }

    private fun verifySinglePlaybackLooper() {
        if (context == null) {
            // Don't verify threads in deprecated mode without context.
            return
        }
        val myLooper = Looper.myLooper()
        Preconditions.checkState(
            playbackLooper == null || playbackLooper == myLooper,
            "AudioTrackAudioOutputProvider accessed on multiple threads: %s and %s",
            getLooperThreadName(playbackLooper),
            getLooperThreadName(myLooper)
        )
        playbackLooper = myLooper
    }

    private fun getFormatSupportLevel(formatConfig: FormatConfig): @SupportLevel Int {
        val format = formatConfig.format
        if (format.sampleMimeType == MimeTypes.AUDIO_RAW) {
            if (format.pcmEncoding == C.ENCODING_PCM_16BIT) {
                // Always supported.
                return AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY
            }
            if (!formatConfig.enableHighResolutionPcmOutput) {
                // Other PCM formats explicitly disabled, so claim no support.
                return AudioOutputProvider.FORMAT_UNSUPPORTED
            }
            if (!isEncodingLinearPcm(format.pcmEncoding)) {
                w(TAG, "Invalid PCM encoding: " + format.pcmEncoding)
                return AudioOutputProvider.FORMAT_UNSUPPORTED
            }
            if (Build.VERSION.SDK_INT < getApiLevelThatAudioFormatIntroducedAudioEncoding(format.pcmEncoding)) {
                // Format not yet supported by AudioTrack on this SDK level.
                return AudioOutputProvider.FORMAT_UNSUPPORTED
            }
            // AudioTrack can play this PCM format. It may internally resample to other PCM formats, but
            // this is outside of our control and knowledge.
            return AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY
        }
        if (audioCapabilities!!.isPassthroughPlaybackSupported(
                format, formatConfig.audioAttributes
            )
        ) {
            return AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY
        }
        return AudioOutputProvider.FORMAT_UNSUPPORTED
    }

    private fun getAudioTrackConfig(config: OutputConfig): AudioTrackConfig {
        return AudioTrackConfig(
            config.encoding,
            config.sampleRate,
            config.channelMask,
            config.isTunneling,
            config.isOffload,
            config.bufferSize
        )
    }

    private inner class CapabilityChangeListener
        : AudioTrackAudioOutput.CapabilityChangeListener {
        override fun onRecoverableWriteError() {
            audioCapabilitiesReceiver?.also {
                // Change to the audio capabilities supported by all the devices during the error recovery.
                audioCapabilities = AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES
                it.overrideCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            }
        }

        override fun onRoutedDeviceChanged(routedDevice: AudioDeviceInfo?) {
            audioCapabilitiesReceiver?.setRoutedDevice(routedDevice)
        }
    }

    companion object {
        private const val TAG = "ATAudioOutputProvider"
        private val COMPRESSED_OFFLOAD_EXPLICIT_AAC_ENABLED: Supplier<Boolean> =
            Suppliers.memoize { isCompressedOffloadExplicitAacEnabled }

        private fun getLooperThreadName(looper: Looper?): String {
            return looper?.thread?.name ?: "null"
        }

        private val isCompressedOffloadExplicitAacEnabled: Boolean
            get() = Build.VERSION.SDK_INT > 37
                    && getSystemProperty("persist.audio.compressed_offload_implicit_aac") == "false"
    }
}
