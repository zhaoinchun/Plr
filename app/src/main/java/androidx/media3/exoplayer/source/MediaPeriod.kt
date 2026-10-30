package androidx.media3.exoplayer.source

import androidx.media3.common.C
import androidx.media3.common.StreamKey
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import java.io.IOException

interface MediaPeriod : SequenceableLoader {
    interface Callback : SequenceableLoader.Callback<MediaPeriod> {
        fun onPrepared(mediaPeriod: MediaPeriod)
    }

    fun prepare(callback: Callback, positionUs: Long)

    @Throws(IOException::class)
    fun maybeThrowPrepareError()

    val trackGroups: TrackGroupArray

    fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> {
        return listOf()
    }

    fun selectTracks(
        selections: Array<ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long
    ): Long

    fun discardBuffer(positionUs: Long, toKeyframe: Boolean)
    fun readDiscontinuity(): Long
    fun setUsesStreamPrerollFlags() {}
    fun seekToUs(positionUs: Long): Long
    fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long

    // SequenceableLoader interface. Overridden to provide more specific documentation.
    override val bufferedPositionUs: Long
    override val nextLoadPositionUs: Long

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean

    override val isLoading: Boolean

    override fun reevaluateBuffer(positionUs: Long)

    fun setEndPositionUs(endPositionUs: Long): Long {
        return C.TIME_END_OF_SOURCE
    }
}
