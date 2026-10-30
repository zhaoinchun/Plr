package androidx.media3.common.audio

import androidx.media3.common.PlaybackParameters

interface AudioProcessorChain {
    
    val audioProcessors: Array<AudioProcessor>?

    fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters?

    fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean

    fun getMediaDuration(playoutDuration: Long): Long

    val skippedOutputFrameCount: Long
}
