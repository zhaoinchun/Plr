package androidx.media3.exoplayer.audio

import android.media.AudioDeviceInfo
import android.media.AudioTrack
import androidx.media3.common.PlaybackParameters

import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioOutputProvider.FormatConfig
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig
import java.nio.ByteBuffer

interface AudioOutput {

    interface Listener {
        fun onPositionAdvancing(playoutStartSystemTimeMs: Long)
        fun onOffloadDataRequest()
        fun onOffloadPresentationEnded()
        fun onUnderrun()
        fun onReleased()
    }

    class WriteException(
        val errorCode: Int,
        val isRecoverable: Boolean
    ) : Exception("AudioOutput write failed: " + errorCode)

    fun play()
    fun pause()

    @Throws(WriteException::class)
    fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean
    fun flush()
    fun stop()
    fun release()
    fun setVolume(volume: Float)
    val isOffloadedPlayback: Boolean
    val audioSessionId: Int
    val sampleRate: Int
    val bufferSizeInFrames: Long
    val positionUs: Long
    var playbackParameters: PlaybackParameters
    val isStalled: Boolean
    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)
    fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int)
    fun setOffloadEndOfStream()
    fun setPlayerId(playerId: PlayerId) {}
    fun canReuseAudioOutput(
        currentConfig: OutputConfig, newFormat: FormatConfig, newConfig: OutputConfig
    ): Boolean {
        return newConfig == currentConfig
    }

    fun attachAuxEffect(effectId: Int)
    fun setAuxEffectSendLevel(level: Float)
    fun setPreferredDevice(preferredDevice: AudioDeviceInfo?)
}
