package androidx.media3.exoplayer

import androidx.media3.common.PlaybackParameters

interface MediaClock {
    val positionUs: Long
    fun hasSkippedSilenceSinceLastCall(): Boolean {
        return false
    }

    var playbackParameters: PlaybackParameters
}
