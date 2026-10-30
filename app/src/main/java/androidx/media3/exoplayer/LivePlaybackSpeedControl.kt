package androidx.media3.exoplayer

import androidx.media3.common.MediaItem

interface LivePlaybackSpeedControl {
    fun setLiveConfiguration(liveConfiguration: MediaItem.LiveConfiguration)
    fun setTargetLiveOffsetOverrideUs(liveOffsetUs: Long)
    fun notifyRebuffer()
    fun getAdjustedPlaybackSpeed(liveOffsetUs: Long, bufferedDurationUs: Long): Float
    val targetLiveOffsetUs: Long
}
