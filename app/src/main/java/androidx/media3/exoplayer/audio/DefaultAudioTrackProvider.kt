package androidx.media3.exoplayer.audio

import android.content.Context
import android.media.AudioTrack
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes

import androidx.media3.common.util.Util.getAudioFormat
import androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig
import androidx.media3.exoplayer.audio.DefaultAudioSink.AudioTrackProvider

@Deprecated("Use {@link AudioTrackAudioOutputProvider} instead.")
class DefaultAudioTrackProvider : AudioTrackProvider {
    override fun getAudioTrack(
        audioTrackConfig: AudioTrackConfig,
        audioAttributes: AudioAttributes,
        audioSessionId: Int,
        context: Context?
    ): AudioTrack {
        val audioFormat =
            getAudioFormat(
                audioTrackConfig.sampleRate,
                audioTrackConfig.channelConfig,
                audioTrackConfig.encoding
            )
        val audioTrackAttributes =
            getAudioTrackAttributes(audioAttributes, audioTrackConfig.tunneling)
        val audioTrackBuilder =
            AudioTrack.Builder()
                .setAudioAttributes(audioTrackAttributes)
                .setAudioFormat(audioFormat!!)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(audioTrackConfig.bufferSize)
                .setSessionId(audioSessionId)
        if (Build.VERSION.SDK_INT >= 29) {
            setOffloadedPlaybackV29(audioTrackBuilder, audioTrackConfig.offload)
        }
        if (Build.VERSION.SDK_INT >= 34 && context != null) {
            audioTrackBuilder.setContext(context)
        }
        return customizeAudioTrackBuilder(audioTrackBuilder)!!.build()
    }

    @RequiresApi(29)
    private fun setOffloadedPlaybackV29(
        audioTrackBuilder: AudioTrack.Builder,
        isOffloaded: Boolean
    ) {
        audioTrackBuilder.setOffloadedPlayback(isOffloaded)
    }

    private fun customizeAudioTrackBuilder(audioTrackBuilder: AudioTrack.Builder?): AudioTrack.Builder? {
        return audioTrackBuilder
    }

    private fun getAudioTrackAttributes(
        audioAttributes: AudioAttributes, tunneling: Boolean
    ): android.media.AudioAttributes {
        return if (tunneling) {
            this.audioTrackTunnelingAttributes
        } else {
            audioAttributes.platformAudioAttributes!!
        }
    }

    private val audioTrackTunnelingAttributes: android.media.AudioAttributes
        get() = android.media.AudioAttributes.Builder()
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
            .setFlags(android.media.AudioAttributes.FLAG_HW_AV_SYNC)
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .build()
}
