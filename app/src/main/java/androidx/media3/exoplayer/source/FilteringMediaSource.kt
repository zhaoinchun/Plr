package androidx.media3.exoplayer.source

import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.StreamKey
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import java.io.IOException

class FilteringMediaSource(mediaSource: MediaSource, trackTypes: Set<Int>) :
    WrappingMediaSource(mediaSource) {
    private val trackTypes: Set<Int> = trackTypes.toSet()

    constructor(mediaSource: MediaSource, trackType: @C.TrackType Int) : this(
        mediaSource,
        setOf(trackType)
    )

    override fun createPeriod(
        id: MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod {
        val wrappedPeriod = super.createPeriod(id, allocator, startPositionUs)
        return FilteringMediaPeriod(wrappedPeriod, trackTypes)
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        val wrappedPeriod = (mediaPeriod as FilteringMediaPeriod).mediaPeriod
        super.releasePeriod(wrappedPeriod)
    }

    @VisibleForTesting
    internal class FilteringMediaPeriod(
        val mediaPeriod: MediaPeriod,
        private val trackTypes: Set<Int>
    ) : MediaPeriod, MediaPeriod.Callback {
        private var callback: MediaPeriod.Callback? = null
        private var filteredTrackGroups: TrackGroupArray? = null

        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
            this.callback = callback
            mediaPeriod.prepare(this, positionUs)
        }

        @Throws(IOException::class)
        override fun maybeThrowPrepareError() {
            mediaPeriod.maybeThrowPrepareError()
        }

        override val trackGroups: TrackGroupArray
            get() = filteredTrackGroups!!

        override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> {
            return mediaPeriod.getStreamKeys(trackSelections)
        }

        override fun selectTracks(
            selections: Array<ExoTrackSelection?>,
            mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>,
            streamResetFlags: BooleanArray,
            positionUs: Long
        ): Long {
            return mediaPeriod.selectTracks(
                selections, mayRetainStreamFlags, streams, streamResetFlags, positionUs
            )
        }

        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
            mediaPeriod.discardBuffer(positionUs, toKeyframe)
        }

        override fun setUsesStreamPrerollFlags() {
            mediaPeriod.setUsesStreamPrerollFlags()
        }

        override fun readDiscontinuity(): Long {
            return mediaPeriod.readDiscontinuity()
        }

        override fun seekToUs(positionUs: Long): Long {
            return mediaPeriod.seekToUs(positionUs)
        }

        override fun getAdjustedSeekPositionUs(
            positionUs: Long,
            seekParameters: SeekParameters
        ): Long {
            return mediaPeriod.getAdjustedSeekPositionUs(positionUs, seekParameters)
        }

        override val bufferedPositionUs: Long
            get() = mediaPeriod.bufferedPositionUs

        override val nextLoadPositionUs: Long
            get() = mediaPeriod.nextLoadPositionUs

        override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
            return mediaPeriod.continueLoading(loadingInfo)
        }

        override val isLoading: Boolean
            get() = mediaPeriod.isLoading

        override fun reevaluateBuffer(positionUs: Long) {
            mediaPeriod.reevaluateBuffer(positionUs)
        }

        override fun onPrepared(mediaPeriod: MediaPeriod) {
            val trackGroups = mediaPeriod.trackGroups
            val trackGroupsBuilder = mutableListOf<TrackGroup>()
            for (i in 0..<trackGroups.length) {
                val trackGroup = trackGroups.get(i)
                if (trackTypes.contains(trackGroup.type)) {
                    trackGroupsBuilder.add(trackGroup)
                }
            }
            filteredTrackGroups =
                TrackGroupArray(trackGroupsBuilder.toTypedArray())
            callback!!.onPrepared(this)
        }

        override fun onContinueLoadingRequested(source: MediaPeriod) {
            callback!!.onContinueLoadingRequested(this)
        }

        override fun setEndPositionUs(endPositionUs: Long): Long {
            return mediaPeriod.setEndPositionUs(endPositionUs)
        }
    }
}
