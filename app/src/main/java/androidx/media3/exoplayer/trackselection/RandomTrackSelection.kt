package androidx.media3.exoplayer.trackselection

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.chunk.MediaChunk
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.upstream.BandwidthMeter
import java.util.Random

class RandomTrackSelection(
    group: TrackGroup,
    tracks: IntArray,
    type: @TrackSelection.Type Int,
    private val random: Random
) : BaseTrackSelection(group, tracks, type) {
    class Factory : ExoTrackSelection.Factory {
        private val random: Random

        constructor() {
            random = Random()
        }

        constructor(seed: Int) {
            random = Random(seed.toLong())
        }

        override fun createTrackSelections(
            definitions: Array<ExoTrackSelection.Definition?>,
            bandwidthMeter: BandwidthMeter,
            mediaPeriodId: MediaPeriodId,
            timeline: Timeline
        ): Array<ExoTrackSelection?> {
            return TrackSelectionUtil.createTrackSelectionsForDefinitions(definitions) { definition: ExoTrackSelection.Definition? ->
                RandomTrackSelection(
                    definition!!.group, definition.tracks, definition.type, random
                )
            }
        }
    }

    override var selectedIndex: Int = random.nextInt(length)
    override val selectionReason: @C.SelectionReason Int = C.SELECTION_REASON_ADAPTIVE
    override val selectionData: Any? = null

    override fun updateSelectedTrack(
        playbackPositionUs: Long,
        bufferedDurationUs: Long,
        availableDurationUs: Long,
        queue: List<MediaChunk>,
        mediaChunkIterators: Array<MediaChunkIterator>
    ) {
        // Count the number of allowed formats.
        val nowMs = SystemClock.elapsedRealtime()
        var allowedFormatCount = 0
        for (i in 0..<length) {
            if (!isTrackExcluded(i, nowMs)) {
                allowedFormatCount++
            }
        }

        selectedIndex = random.nextInt(allowedFormatCount)
        if (allowedFormatCount != length) {
            // Adjust the format index to account for excluded formats.
            allowedFormatCount = 0
            for (i in 0..<length) {
                if (!isTrackExcluded(i, nowMs) && selectedIndex == allowedFormatCount++) {
                    selectedIndex = i
                    return
                }
            }
        }
    }

}
