package androidx.media3.exoplayer.audio

import android.media.AudioDeviceInfo
import androidx.annotation.IntDef
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.Clock

interface AudioOutputProvider {
    fun interface Listener {
        fun onFormatSupportChanged()
    }

    class FormatConfig private constructor(builder: Builder) {
        val format: Format = builder.format
        val audioAttributes: AudioAttributes = builder.audioAttributes
        val preferredDevice: AudioDeviceInfo?= builder.preferredDevice
        val enableHighResolutionPcmOutput: Boolean= builder.enableHighResolutionPcmOutput
        val enablePlaybackParameters= builder.enablePlaybackParameters
        val enableOffload= builder.enableOffload
        val audioSessionId = builder.audioSessionId
        val virtualDeviceId = builder.virtualDeviceId
        val enableTunneling= builder.enableTunneling
        val preferredBufferSize= builder.preferredBufferSize

        fun buildUpon(): Builder {
            return Builder(this)
        }

        class Builder {
            val format: Format
            var audioAttributes: AudioAttributes
            var preferredDevice: AudioDeviceInfo? = null
            var enableHighResolutionPcmOutput = false
            var enablePlaybackParameters = false
            var enableOffload = false
            var audioSessionId: Int
            var virtualDeviceId: Int
            var enableTunneling = false
            var preferredBufferSize: Int

            constructor(format: Format) {
                this.format = format
                this.audioAttributes = AudioAttributes.DEFAULT
                this.audioSessionId = C.AUDIO_SESSION_ID_UNSET
                this.virtualDeviceId = C.INDEX_UNSET
                this.preferredBufferSize = C.LENGTH_UNSET
            }

            constructor(config: FormatConfig) {
                this.format = config.format
                this.audioAttributes = config.audioAttributes
                this.preferredDevice = config.preferredDevice
                this.enableHighResolutionPcmOutput = config.enableHighResolutionPcmOutput
                this.enablePlaybackParameters = config.enablePlaybackParameters
                this.enableOffload = config.enableOffload
                this.audioSessionId = config.audioSessionId
                this.virtualDeviceId = config.virtualDeviceId
                this.enableTunneling = config.enableTunneling
                this.preferredBufferSize = config.preferredBufferSize
            }

            fun setAudioAttributes(audioAttributes: AudioAttributes): Builder {
                this.audioAttributes = audioAttributes
                return this
            }

            fun setPreferredDevice(preferredDevice: AudioDeviceInfo?): Builder {
                this.preferredDevice = preferredDevice
                return this
            }

            fun setEnableHighResolutionPcmOutput(enableHighResolutionPcmOutput: Boolean): Builder {
                this.enableHighResolutionPcmOutput = enableHighResolutionPcmOutput
                return this
            }

            fun setEnablePlaybackParameters(enablePlaybackParameters: Boolean): Builder {
                this.enablePlaybackParameters = enablePlaybackParameters
                return this
            }

            fun setEnableOffload(enableOffload: Boolean): Builder {
                this.enableOffload = enableOffload
                return this
            }

            fun setAudioSessionId(audioSessionId: Int): Builder {
                this.audioSessionId = audioSessionId
                return this
            }

            fun setVirtualDeviceId(virtualDeviceId: Int): Builder {
                this.virtualDeviceId = virtualDeviceId
                return this
            }

            fun setEnableTunneling(enableTunneling: Boolean): Builder {
                this.enableTunneling = enableTunneling
                return this
            }

            fun setPreferredBufferSize(preferredBufferSize: Int): Builder {
                this.preferredBufferSize = preferredBufferSize
                return this
            }

            fun build(): FormatConfig {
                return FormatConfig(this)
            }
        }
    }

    data class OutputConfig private constructor(
        val encoding: @C.Encoding Int,
        val sampleRate: Int,
        val channelMask: Int,
        val isTunneling: Boolean,
        val isOffload: Boolean,
        val bufferSize: Int,
        val audioAttributes: AudioAttributes,
        val audioSessionId: Int,
        val virtualDeviceId: Int,
        val usePlaybackParameters: Boolean,
        val useOffloadGapless: Boolean,
    ) {
        private constructor(builder: Builder) : this(
            builder.encoding,
            builder.sampleRate,
            builder.channelMask,
            builder.isTunneling,
            builder.isOffload,
            builder.bufferSize,
            builder.audioAttributes,
            builder.audioSessionId,
            builder.virtualDeviceId,
            builder.usePlaybackParameters,
            builder.useOffloadGapless,
        )

        fun buildUpon(): Builder {
            return OutputConfig.Builder(this)
        }

        class Builder {
            var encoding: @C.Encoding Int = 0
            var sampleRate = 0
            var channelMask = 0
            var isTunneling = false
            var isOffload = false
            var bufferSize = 0
            var audioAttributes: AudioAttributes
            var audioSessionId: Int
            var virtualDeviceId: Int
            var usePlaybackParameters = false
            var useOffloadGapless = false

            constructor() {
                audioAttributes = AudioAttributes.DEFAULT
                audioSessionId = C.AUDIO_SESSION_ID_UNSET
                virtualDeviceId = C.INDEX_UNSET
            }

            constructor(config: OutputConfig) {
                this.encoding = config.encoding
                this.sampleRate = config.sampleRate
                this.channelMask = config.channelMask
                this.isTunneling = config.isTunneling
                this.isOffload = config.isOffload
                this.bufferSize = config.bufferSize
                this.audioAttributes = config.audioAttributes
                this.audioSessionId = config.audioSessionId
                this.virtualDeviceId = config.virtualDeviceId
                this.usePlaybackParameters = config.usePlaybackParameters
                this.useOffloadGapless = config.useOffloadGapless
            }

            fun setEncoding(encoding: @C.Encoding Int): Builder {
                this.encoding = encoding
                return this
            }

            fun setSampleRate(sampleRate: Int): Builder {
                this.sampleRate = sampleRate
                return this
            }

            fun setChannelMask(channelMask: Int): Builder {
                this.channelMask = channelMask
                return this
            }

            fun setIsTunneling(isTunneling: Boolean): Builder {
                this.isTunneling = isTunneling
                return this
            }

            fun setIsOffload(isOffload: Boolean): Builder {
                this.isOffload = isOffload
                return this
            }

            fun setBufferSize(bufferSize: Int): Builder {
                this.bufferSize = bufferSize
                return this
            }

            fun setAudioAttributes(audioAttributes: AudioAttributes): Builder {
                this.audioAttributes = audioAttributes
                return this
            }

            fun setAudioSessionId(audioSessionId: Int): Builder {
                this.audioSessionId = audioSessionId
                return this
            }

            fun setVirtualDeviceId(virtualDeviceId: Int): Builder {
                this.virtualDeviceId = virtualDeviceId
                return this
            }

            fun setUsePlaybackParameters(usePlaybackParameters: Boolean): Builder {
                this.usePlaybackParameters = usePlaybackParameters
                return this
            }

            fun setUseOffloadGapless(useOffloadGapless: Boolean): Builder {
                this.useOffloadGapless = useOffloadGapless
                return this
            }

            fun build(): OutputConfig {
                return OutputConfig(this)
            }
        }
    }

    class FormatSupport private constructor(builder: Builder) {
        class Builder {
            var isFormatSupportedForOffload = false
            var isGaplessSupportedForOffload = false
            var isSpeedChangeSupportedForOffload = false
            var supportLevel: @SupportLevel Int

            constructor() {
                supportLevel = FORMAT_UNSUPPORTED
            }

            constructor(other: FormatSupport) {
                isFormatSupportedForOffload = other.isFormatSupportedForOffload
                isGaplessSupportedForOffload = other.isGaplessSupportedForOffload
                isSpeedChangeSupportedForOffload = other.isSpeedChangeSupportedForOffload
                supportLevel = other.supportLevel
            }

            fun setIsFormatSupportedForOffload(isFormatSupportedForOffload: Boolean): Builder {
                this.isFormatSupportedForOffload = isFormatSupportedForOffload
                return this
            }

            fun setIsGaplessSupportedForOffload(isGaplessSupportedForOffload: Boolean): Builder {
                this.isGaplessSupportedForOffload = isGaplessSupportedForOffload
                return this
            }

            fun setIsSpeedChangeSupportedForOffload(isSpeedChangeSupportedForOffload: Boolean): Builder {
                this.isSpeedChangeSupportedForOffload = isSpeedChangeSupportedForOffload
                return this
            }

            fun setFormatSupportLevel(supportLevel: @SupportLevel Int): Builder {
                this.supportLevel = supportLevel
                return this
            }

            fun build(): FormatSupport {
                check(
                    !(!isFormatSupportedForOffload
                            && (isGaplessSupportedForOffload || isSpeedChangeSupportedForOffload))
                ) {
                    ("Secondary offload attribute fields are true but primary isFormatSupportedForOffload"
                            + " is false")
                }
                return FormatSupport(this)
            }
        }

        val isFormatSupportedForOffload = builder.isFormatSupportedForOffload
        val isGaplessSupportedForOffload = builder.isGaplessSupportedForOffload
        val isSpeedChangeSupportedForOffload = builder.isSpeedChangeSupportedForOffload
        val supportLevel: @SupportLevel Int = builder.supportLevel

        companion object {
            val UNSUPPORTED: FormatSupport = Builder().build()
        }
    }

    class ConfigurationException(message: String?) : Exception(message!!)

    class InitializationException : Exception {
        constructor() : super()
        constructor(cause: Throwable?) : super(cause)
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(value = [FORMAT_SUPPORTED_DIRECTLY, FORMAT_SUPPORTED_WITH_TRANSCODING, FORMAT_UNSUPPORTED])
    annotation class SupportLevel

    fun getFormatSupport(formatConfig: FormatConfig): FormatSupport

    @Throws(ConfigurationException::class)
    fun getOutputConfig(formatConfig: FormatConfig): OutputConfig

    @Throws(InitializationException::class)
    fun getAudioOutput(config: OutputConfig): AudioOutput?

    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)
    fun setClock(clock: Clock) {}
    fun release()

    companion object {
        const val FORMAT_SUPPORTED_DIRECTLY: Int = 2
        const val FORMAT_SUPPORTED_WITH_TRANSCODING: Int = 1
        const val FORMAT_UNSUPPORTED: Int = 0
    }
}
