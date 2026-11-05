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

    data class FormatSupport(
        var isFormatSupportedForOffload: Boolean = false,
        var isGaplessSupportedForOffload: Boolean = false,
        var isSpeedChangeSupportedForOffload: Boolean = false,
        var supportLevel: @SupportLevel Int = FORMAT_UNSUPPORTED,
    ) {
        companion object {
            val UNSUPPORTED: FormatSupport = FormatSupport()
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
