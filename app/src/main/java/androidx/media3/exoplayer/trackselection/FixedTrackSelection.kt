package androidx.media3.exoplayer.trackselection

import androidx.media3.common.C
import androidx.media3.common.TrackGroup

import androidx.media3.exoplayer.source.chunk.MediaChunk
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator

class FixedTrackSelection(
    group: TrackGroup,
    track: Int,
    type: @TrackSelection.Type Int = TrackSelection.TYPE_UNSET,
    private val reason: @C.SelectionReason Int = C.SELECTION_REASON_UNKNOWN,
    private val data: Any? = null
) : BaseTrackSelection(group, intArrayOf(track), type) {

    override fun updateSelectedTrack(
        playbackPositionUs: Long,
        bufferedDurationUs: Long,
        availableDurationUs: Long,
        queue: List<MediaChunk>,
        mediaChunkIterators: Array<MediaChunkIterator>
    ) {
        // Do nothing.
    }

    override val selectedIndex = 0
    override val selectionReason: @C.SelectionReason Int
        get() = reason
    override val selectionData: Any?
        get() = data

}
