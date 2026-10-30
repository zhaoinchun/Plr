package androidx.media3.exoplayer.audio

import android.media.AudioDeviceInfo
import androidx.media3.exoplayer.audio.AudioOutputProvider.FormatConfig
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig
import java.nio.ByteBuffer

class ForwardingAudioOutput(private val audioOutput: AudioOutput) : AudioOutput by audioOutput{
    override fun addListener(listener: AudioOutput.Listener) {
        audioOutput.addListener(listener)
    }

    override fun play() {
        audioOutput.play()
    }

    override fun pause() {
        audioOutput.pause()
    }

    @Throws(AudioOutput.WriteException::class)
    override fun write(
        buffer: ByteBuffer,
        encodedAccessUnitCount: Int,
        presentationTimeUs: Long
    ): Boolean {
        return audioOutput.write(buffer, encodedAccessUnitCount, presentationTimeUs)
    }

    override fun flush() {
        audioOutput.flush()
    }

    override fun stop() {
        audioOutput.stop()
    }

    override fun release() {
        audioOutput.release()
    }

    override fun setVolume(volume: Float) {
        audioOutput.setVolume(volume)
    }

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {
        audioOutput.setOffloadDelayPadding(delayInFrames, paddingInFrames)
    }

    override fun setOffloadEndOfStream() {
        audioOutput.setOffloadEndOfStream()
    }

    override fun attachAuxEffect(effectId: Int) {
        audioOutput.attachAuxEffect(effectId)
    }

    override fun setAuxEffectSendLevel(level: Float) {
        audioOutput.setAuxEffectSendLevel(level)
    }

    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) {
        audioOutput.setPreferredDevice(preferredDevice)
    }

    override fun canReuseAudioOutput(
        currentConfig: OutputConfig,
        newFormat: FormatConfig,
        newConfig: OutputConfig
    ): Boolean {
        return audioOutput.canReuseAudioOutput(currentConfig, newFormat, newConfig)
    }
}
