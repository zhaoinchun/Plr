package androidx.media3.exoplayer.audio

import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink.AudioSinkConfig
import com.google.common.primitives.ImmutableIntArray

class ForwardingAudioSink(private val sink: AudioSink) : AudioSink by sink {

    // Implementing deprecated method to force implementors to switch to the new method so their
    // current implementation isn't accidentally bypassed.
    @Throws(AudioSink.ConfigurationException::class)
    override fun configure(
        inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?
    ) {
        val outputChannelMapping =
            if (outputChannels == null) null else ImmutableIntArray.copyOf(outputChannels)
        configure(
            AudioSinkConfig(
                inputFormat,
                preferredBufferSizeOverride = specifiedBufferSize,
                outputChannelMapping = outputChannelMapping
            )
        )
    }
}
