package androidx.media3.exoplayer

import androidx.media3.common.Timeline
import androidx.media3.common.util.Log.w
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

interface LoadControl {
    class Parameters(
        val playerId: PlayerId?,
        val timeline: Timeline?,
        val mediaPeriodId: MediaPeriodId?,
        val playbackPositionUs: Long,
        val bufferedDurationUs: Long,
        val playbackSpeed: Float,
        val playWhenReady: Boolean,
        val rebuffering: Boolean,
        val targetLiveOffsetUs: Long,
        val lastRebufferRealtimeMs: Long
    )

    fun onPrepared(playerId: PlayerId) {}

    fun onTracksSelected(
        parameters: Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection?>
    ) {
        // Media3 ExoPlayer will never call this method. This default implementation provides an
        // implementation to please the compiler only.
        throw IllegalStateException("onTracksSelected not implemented")
    }

    fun onStopped(playerId: PlayerId) {}
    fun onReleased(playerId: PlayerId) {}
    fun getAllocator(playerId: PlayerId): Allocator
    fun getBackBufferDurationUs(playerId: PlayerId?): Long
    fun retainBackBufferFromKeyframe(playerId: PlayerId?): Boolean
    fun shouldContinueLoading(parameters: Parameters): Boolean

    fun shouldContinuePreloading(
        playerId: PlayerId?,
        timeline: Timeline?,
        mediaPeriodId: MediaPeriodId?,
        bufferedDurationUs: Long
    ): Boolean {
        w(
            "LoadControl",
            "shouldContinuePreloading needs to be implemented when playlist preloading is enabled"
        )
        return false
    }

    fun shouldStartPlayback(parameters: Parameters): Boolean
}
