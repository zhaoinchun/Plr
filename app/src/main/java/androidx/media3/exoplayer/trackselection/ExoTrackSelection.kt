package androidx.media3.exoplayer.trackselection

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.Log.e
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.chunk.Chunk
import androidx.media3.exoplayer.source.chunk.MediaChunk
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.upstream.BandwidthMeter

interface ExoTrackSelection : TrackSelection {
    class Definition(
        val group: TrackGroup,
        val tracks: IntArray,
        val type: @TrackSelection.Type Int = TrackSelection.TYPE_UNSET
    ) {
        init {
            if (tracks.isEmpty()) {
                e(TAG, "Empty tracks are not allowed", IllegalArgumentException())
            }
        }

        companion object {
            private const val TAG = "ETSDefinition"
        }
    }

    interface Factory {
        fun createTrackSelections(
            definitions: Array<Definition?>,
            bandwidthMeter: BandwidthMeter,
            mediaPeriodId: MediaPeriodId,
            timeline: Timeline
        ): Array<ExoTrackSelection?>
    }

    fun enable()
    fun disable()

    val selectedFormat: Format
    val selectedIndexInTrackGroup: Int
    val selectedIndex: Int
    val selectionReason: @C.SelectionReason Int
    val selectionData: Any?
    fun onPlaybackSpeed(playbackSpeed: Float)
    fun onDiscontinuity() {}
    fun onRebuffer() {}
    fun onPlayWhenReadyChanged(playWhenReady: Boolean) {}

    fun updateSelectedTrack(
        playbackPositionUs: Long,
        bufferedDurationUs: Long,
        availableDurationUs: Long,
        queue: List<MediaChunk>,
        mediaChunkIterators: Array<MediaChunkIterator>
    )

    fun evaluateQueueSize(playbackPositionUs: Long, queue: List<MediaChunk>): Int
    fun shouldCancelChunkLoad(
        playbackPositionUs: Long, loadingChunk: Chunk, queue: List<MediaChunk>
    ): Boolean {
        return false
    }

    fun excludeTrack(index: Int, exclusionDurationMs: Long): Boolean
    fun isTrackExcluded(index: Int, nowMs: Long): Boolean
    fun clearTrackExclusions() {}
    val latestBitrateEstimate: Long
        get() = C.RATE_UNSET_INT.toLong()
}
