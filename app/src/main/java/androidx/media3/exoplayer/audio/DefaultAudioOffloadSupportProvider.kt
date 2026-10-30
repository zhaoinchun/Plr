package androidx.media3.exoplayer.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes.getEncoding
import androidx.media3.common.audio.AudioManagerCompat.getAudioManager

import androidx.media3.common.util.Util
import androidx.media3.common.util.Util.getApiLevelThatAudioFormatIntroducedAudioEncoding
import androidx.media3.common.util.Util.getAudioFormat
import androidx.media3.exoplayer.audio.DefaultAudioSink.AudioOffloadSupportProvider

class DefaultAudioOffloadSupportProvider(context: Context? = null) : AudioOffloadSupportProvider {
    private val context: Context? = context?.applicationContext
    private var isOffloadVariableRateSupported: Boolean? = null

    override fun getAudioOffloadSupport(
        format: Format, audioAttributes: AudioAttributes
    ): AudioOffloadSupport {
        if (Build.VERSION.SDK_INT < 29 || format.sampleRate == Format.NO_VALUE) {
            return AudioOffloadSupport.DEFAULT_UNSUPPORTED
        }

        // isOffloadVariableRateSupported is lazily-loaded instead of being initialized in
        // the constructor so that the platform will be queried from the playback thread.
        val isOffloadVariableRateSupported = isOffloadVariableRateSupported(context)

        val encoding: @C.Encoding Int = getEncoding(
            format.sampleMimeType!!,
            format.codecs
        )
        if (encoding == C.ENCODING_INVALID
            || Build.VERSION.SDK_INT < getApiLevelThatAudioFormatIntroducedAudioEncoding(encoding)
        ) {
            // Example: AudioFormat.ENCODING_OPUS is supported only from API 30.
            return AudioOffloadSupport.DEFAULT_UNSUPPORTED
        }

        val channelConfig = Util.getAudioTrackChannelConfig(format)
        if (channelConfig == AudioFormat.CHANNEL_INVALID) {
            return AudioOffloadSupport.DEFAULT_UNSUPPORTED
        }

        val audioFormat: AudioFormat?
        try {
            audioFormat = getAudioFormat(format.sampleRate, channelConfig, encoding)
        } catch (e: IllegalArgumentException) {
            return AudioOffloadSupport.DEFAULT_UNSUPPORTED
        }

        if (Build.VERSION.SDK_INT >= 33) {
            return getOffloadedPlaybackSupportV33(
                audioFormat!!,
                audioAttributes.platformAudioAttributes!!,
                isOffloadVariableRateSupported
            )
        }
        if (Build.VERSION.SDK_INT >= 31) {
            return getOffloadedPlaybackSupportV31(
                audioFormat!!,
                audioAttributes.platformAudioAttributes!!,
                isOffloadVariableRateSupported
            )
        }
        return getOffloadedPlaybackSupportV29(
            audioFormat!!, audioAttributes.platformAudioAttributes!!, isOffloadVariableRateSupported
        )
    }

    private fun isOffloadVariableRateSupported(context: Context?): Boolean {
        if (isOffloadVariableRateSupported != null) {
            return isOffloadVariableRateSupported!!
        }

        if (context != null) {
            val audioManager = getAudioManager(context)
            val offloadVariableRateSupportedKeyValue =
                audioManager.getParameters(OFFLOAD_VARIABLE_RATE_SUPPORTED_KEY)
            isOffloadVariableRateSupported =
                offloadVariableRateSupportedKeyValue != null
                        && offloadVariableRateSupportedKeyValue == "$OFFLOAD_VARIABLE_RATE_SUPPORTED_KEY=1"
        } else {
            isOffloadVariableRateSupported = false
        }
        return isOffloadVariableRateSupported!!
    }

    companion object {

        private const val OFFLOAD_VARIABLE_RATE_SUPPORTED_KEY = "offloadVariableRateSupported"

        @RequiresApi(29)
        private fun getOffloadedPlaybackSupportV29(
            audioFormat: AudioFormat,
            audioAttributes: android.media.AudioAttributes,
            isOffloadVariableRateSupported: Boolean
        ): AudioOffloadSupport {
            if (!AudioManager.isOffloadedPlaybackSupported(audioFormat, audioAttributes)) {
                return AudioOffloadSupport.DEFAULT_UNSUPPORTED
            }
            return AudioOffloadSupport.Builder()
                .setIsFormatSupported(true)
                .setIsSpeedChangeSupported(isOffloadVariableRateSupported)
                .build()
        }

        @RequiresApi(31)
        private fun getOffloadedPlaybackSupportV31(
            audioFormat: AudioFormat,
            audioAttributes: android.media.AudioAttributes,
            isOffloadVariableRateSupported: Boolean
        ): AudioOffloadSupport {
            val playbackOffloadSupport =
                AudioManager.getPlaybackOffloadSupport(audioFormat, audioAttributes)
            if (playbackOffloadSupport == AudioManager.PLAYBACK_OFFLOAD_NOT_SUPPORTED) {
                return AudioOffloadSupport.DEFAULT_UNSUPPORTED
            }
            val audioOffloadSupport = AudioOffloadSupport.Builder()
            // (b/191950723) Gapless is not supported pre-API 33 due to playback position
            // issue upon transition of gapless tracks
            val isGaplessSupported =
                Build.VERSION.SDK_INT > 32 && playbackOffloadSupport == AudioManager.PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED
            return audioOffloadSupport
                .setIsFormatSupported(true)
                .setIsGaplessSupported(isGaplessSupported)
                .setIsSpeedChangeSupported(isOffloadVariableRateSupported)
                .build()
        }

        @RequiresApi(33)
        private fun getOffloadedPlaybackSupportV33(
            audioFormat: AudioFormat,
            audioAttributes: android.media.AudioAttributes,
            isOffloadVariableRateSupported: Boolean
        ): AudioOffloadSupport {
            val directSupport = AudioManager.getDirectPlaybackSupport(audioFormat, audioAttributes)
            if ((directSupport and AudioManager.DIRECT_PLAYBACK_OFFLOAD_SUPPORTED) == 0) {
                return AudioOffloadSupport.DEFAULT_UNSUPPORTED
            }
            val isGaplessSupported =
                ((directSupport and AudioManager.DIRECT_PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED)
                        == AudioManager.DIRECT_PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED)
            return AudioOffloadSupport.Builder()
                .setIsFormatSupported(true)
                .setIsGaplessSupported(isGaplessSupported)
                .setIsSpeedChangeSupported(isOffloadVariableRateSupported)
                .build()
        }
    }
}
