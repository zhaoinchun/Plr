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

    class FormatConfig(
        val format: Format,
        val audioAttributes: AudioAttributes = AudioAttributes.DEFAULT,
        val preferredDevice: AudioDeviceInfo? = null,
        val enableHighResolutionPcmOutput: Boolean = false,
        val enablePlaybackParameters: Boolean = false,
        val enableOffload: Boolean = false,
        val audioSessionId: Int = C.AUDIO_SESSION_ID_UNSET,
        val virtualDeviceId: Int = C.INDEX_UNSET,
        val enableTunneling: Boolean = false,
        val preferredBufferSize: Int = C.LENGTH_UNSET,
    )

    data class OutputConfig(
        val encoding: @C.Encoding Int = 0,
        val sampleRate: Int = 0,
        val channelMask: Int = 0,
        val isTunneling: Boolean = false,
        val isOffload: Boolean = false,
        val bufferSize: Int = 0,
        val audioAttributes: AudioAttributes = AudioAttributes.DEFAULT,
        val audioSessionId: Int = C.AUDIO_SESSION_ID_UNSET,
        val virtualDeviceId: Int = C.INDEX_UNSET,
        val usePlaybackParameters: Boolean = false,
        val useOffloadGapless: Boolean = false,
    )

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
