
package androidx.media3.exoplayer

import androidx.media3.common.Timeline
import androidx.media3.common.util.Log.w

import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaPeriod
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

    @Suppress("deprecation") // Calling deprecated version of this method.
    fun getBackBufferDurationUs(playerId: PlayerId?): Long {
        return this.backBufferDurationUs
    }

    @get:Deprecated("Implements {@link #getBackBufferDurationUs(PlayerId)} instead.")
    val backBufferDurationUs: Long
        get() {
            // Media3 ExoPlayer will never call this method. This default implementation provides an
            // implementation to please the compiler only.
            throw IllegalStateException("getBackBufferDurationUs not implemented")
        }

    fun retainBackBufferFromKeyframe(playerId: PlayerId?): Boolean

    @Suppress("deprecation")
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

    @Suppress("deprecation") // Calling deprecated version of this method.
    fun shouldStartPlayback(parameters: Parameters): Boolean {
        return shouldStartPlayback(
            parameters.timeline,
            parameters.mediaPeriodId,
            parameters.bufferedDurationUs,
            parameters.playbackSpeed,
            parameters.rebuffering,
            parameters.targetLiveOffsetUs
        )
    }

    @Suppress("deprecation") // Calling deprecated version of this method.
    @Deprecated("Implement {@link #shouldStartPlayback(Parameters)} instead.")
    fun shouldStartPlayback(
        timeline: Timeline?,
        mediaPeriodId: MediaPeriodId?,
        bufferedDurationUs: Long,
        playbackSpeed: Float,
        rebuffering: Boolean,
        targetLiveOffsetUs: Long
    ): Boolean {
        // Media3 ExoPlayer will never call this method. The default implementation is only used to
        // forward to the deprecated version below.
        return shouldStartPlayback(
            bufferedDurationUs,
            playbackSpeed,
            rebuffering,
            targetLiveOffsetUs
        )
    }

    @Deprecated("Implement {@link #shouldStartPlayback(Parameters)} instead.")
    fun shouldStartPlayback(
        bufferedDurationUs: Long,
        playbackSpeed: Float,
        rebuffering: Boolean,
        targetLiveOffsetUs: Long
    ): Boolean {
        // Media3 ExoPlayer will never call this method. This default implementation provides an
        // implementation to please the compiler only.
        throw IllegalStateException("shouldStartPlayback not implemented")
    }

    companion object {
        @Deprecated(
            """Used as a placeholder when MediaPeriodId is unknown. Only used when the deprecated
        methods {@link #onTracksSelected(Renderer[], TrackGroupArray, ExoTrackSelection[])} or
        {@link #shouldStartPlayback(long, float, boolean, long)} are called."""
        )
        val EMPTY_MEDIA_PERIOD_ID: MediaPeriodId = MediaPeriodId( Any())
    }
}
