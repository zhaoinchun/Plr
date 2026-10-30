
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

    @Suppress("deprecation") // Calling deprecated version of this method.
    fun onPrepared(playerId: PlayerId) {
        onPrepared()
    }

    @Deprecated("Use {@link #onPrepared(PlayerId)} instead.")
    fun onPrepared() {
        // Media3 ExoPlayer will never call this method. This default implementation provides an
        // implementation to please the compiler only.
        throw IllegalStateException("onPrepared not implemented")
    }

    fun onTracksSelected(
        parameters: Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection?>
    ) {
        // Media3 ExoPlayer will never call this method. This default implementation provides an
        // implementation to please the compiler only.
        throw IllegalStateException("onTracksSelected not implemented")
    }

    @Suppress("deprecation") // Calling deprecated version of this method.
    @Deprecated(
        """Implement {@link #onTracksSelected(Parameters, TrackGroupArray,
   *     ExoTrackSelection[])} instead."""
    )
    fun onTracksSelected(
        renderers: Array<Renderer>?,
        trackGroups: TrackGroupArray?,
        trackSelections: Array<ExoTrackSelection?>?
    ) {
        // Media3 ExoPlayer will never call this method. This default implementation provides an
        // implementation to please the compiler only.
        throw IllegalStateException("onTracksSelected not implemented")
    }

    @Suppress("deprecation") // Calling deprecated version of this method.
    fun onStopped(playerId: PlayerId) {
        onStopped()
    }

    @Deprecated("Implement {@link #onStopped(PlayerId)} instead.")
    fun onStopped() {
        // Media3 ExoPlayer will never call this method. This default implementation provides an
        // implementation to please the compiler only.
        throw IllegalStateException("onStopped not implemented")
    }

    @Suppress("deprecation") // Calling deprecated version of this method.
    fun onReleased(playerId: PlayerId) {
        onReleased()
    }

    @Deprecated("Implement {@link #onReleased(PlayerId)} instead.")
    fun onReleased() {
        // Media3 ExoPlayer will never call this method. This default implementation provides an
        // implementation to please the compiler only.
        throw IllegalStateException("onReleased not implemented")
    }

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

    @Suppress("deprecation") // Calling deprecated version of this method.
    fun retainBackBufferFromKeyframe(playerId: PlayerId?): Boolean {
        return retainBackBufferFromKeyframe()
    }

    @Deprecated("Implements {@link #retainBackBufferFromKeyframe(PlayerId)} instead.")
    fun retainBackBufferFromKeyframe(): Boolean {
        // Media3 ExoPlayer will never call this method. This default implementation provides an
        // implementation to please the compiler only.
        throw IllegalStateException("retainBackBufferFromKeyframe not implemented")
    }

    @Suppress("deprecation")
    fun shouldContinueLoading(parameters: Parameters): Boolean {
        return shouldContinueLoading(
            parameters.playbackPositionUs, parameters.bufferedDurationUs, parameters.playbackSpeed
        )
    }

    @Deprecated("Implement {@link #shouldContinueLoading(Parameters)} instead.")
    fun shouldContinueLoading(
        playbackPositionUs: Long, bufferedDurationUs: Long, playbackSpeed: Float
    ): Boolean {
        // Media3 ExoPlayer will never call this method. This default implementation provides an
        // implementation to please the compiler only.
        throw IllegalStateException("shouldContinueLoading not implemented")
    }

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
