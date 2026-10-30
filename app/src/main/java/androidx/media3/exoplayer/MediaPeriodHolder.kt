
package androidx.media3.exoplayer

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.Log.e
import androidx.media3.exoplayer.source.EmptySampleStream
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelectorResult
import androidx.media3.exoplayer.upstream.Allocator
import com.google.common.base.Preconditions
import java.io.IOException
import kotlin.math.max

internal class MediaPeriodHolder(
    private val rendererCapabilities: Array<RendererCapabilities>,

    var rendererOffset: Long,
    private val trackSelector: TrackSelector,
    allocator: Allocator,
    private val mediaSourceList: MediaSourceList,
     var info: MediaPeriodInfo,
    var trackSelectorResult: TrackSelectorResult,
    val targetPreloadBufferDurationUs: Long,
    usesStreamPrerollFlags: Boolean
) {
    val mediaPeriod: MediaPeriod
    val uid: Any
    val sampleStreams: Array<SampleStream?>
    var prepareCalled: Boolean = false
    var prepared: Boolean = false
    var hasEnabledTracks: Boolean = false
    private val renderersInCorrectState: BooleanArray
    private val mayRetainStreamFlags: BooleanArray
    private var next: MediaPeriodHolder? = null
    var trackGroups: TrackGroupArray
        private set

    init {
        this.uid = info.id.periodUid
        this.trackGroups = TrackGroupArray.EMPTY
        sampleStreams = arrayOfNulls<SampleStream>(rendererCapabilities.size)
        renderersInCorrectState = BooleanArray(rendererCapabilities.size)
        mayRetainStreamFlags = BooleanArray(rendererCapabilities.size)
        mediaPeriod = mediaSourceList.createPeriod(info.id, allocator, info.startPositionUs)
        if (usesStreamPrerollFlags) {
            mediaPeriod.setUsesStreamPrerollFlags()
        }
    }

    fun toRendererTime(periodTimeUs: Long): Long {
        return periodTimeUs + this.rendererOffset
    }

    fun toPeriodTime(rendererTimeUs: Long): Long {
        return rendererTimeUs - this.rendererOffset
    }

    val startPositionRendererTime: Long
        get() = info.startPositionUs + this.rendererOffset

    val isFullyBuffered: Boolean
        get() = prepared
                && (!hasEnabledTracks || mediaPeriod.bufferedPositionUs == C.TIME_END_OF_SOURCE)

    val isFullyPreloaded: Boolean
        get() = prepared
                && (this.isFullyBuffered
                || this.bufferedPositionUs - info.startPositionUs >= targetPreloadBufferDurationUs)

    val bufferedPositionUs: Long
        get() {
            if (!prepared) {
                return info.startPositionUs
            }
            val bufferedPositionUs =
                if (hasEnabledTracks) mediaPeriod.bufferedPositionUs else C.TIME_END_OF_SOURCE
            return if (bufferedPositionUs == C.TIME_END_OF_SOURCE) info.durationUs else bufferedPositionUs
        }

    val nextLoadPositionUs: Long
        get() = if (!prepared) 0 else mediaPeriod.nextLoadPositionUs

    @Throws(ExoPlaybackException::class)
    fun handlePrepared(playbackSpeed: Float, timeline: Timeline, playWhenReady: Boolean) {
        prepared = true
        trackGroups = mediaPeriod.trackGroups
        val selectorResult = selectTracks(playbackSpeed, timeline, playWhenReady)
        var requestedStartPositionUs = info.startPositionUs
        if (info.durationUs != C.TIME_UNSET && requestedStartPositionUs >= info.durationUs) {
            // Make sure start position doesn't exceed period duration.
            requestedStartPositionUs = max(0, info.durationUs - 1)
        }
        val newStartPositionUs =
            applyTrackSelection(
                selectorResult, requestedStartPositionUs,  false
            )
        this.rendererOffset += info.startPositionUs - newStartPositionUs
        info =
            info.copyWithStartPositionUs(
                newStartPositionUs,
                info.liveStreamStartPositionProjectionUs
            )
    }

    fun reevaluateBuffer(rendererPositionUs: Long) {
        Preconditions.checkState(this.isLoadingMediaPeriod)
        if (prepared) {
            mediaPeriod.reevaluateBuffer(toPeriodTime(rendererPositionUs))
        }
    }

    fun continueLoading(loadingInfo: LoadingInfo) {
        Preconditions.checkState(this.isLoadingMediaPeriod)
        mediaPeriod.continueLoading(loadingInfo)
    }

    @Throws(ExoPlaybackException::class)
    fun selectTracks(
        playbackSpeed: Float, timeline: Timeline, playWhenReady: Boolean
    ): TrackSelectorResult {
        val selectorResult =
            trackSelector.selectTracks(rendererCapabilities, this.trackGroups!!, info.id, timeline)
        for (i in 0..<selectorResult!!.length) {
            if (selectorResult.isRendererEnabled(i)) {
                Preconditions.checkState(
                    selectorResult.selections[i] != null
                            || rendererCapabilities[i]!!.trackType == C.TRACK_TYPE_NONE
                )
            } else {
                Preconditions.checkState(selectorResult.selections[i] == null)
            }
        }
        for (trackSelection in selectorResult.selections) {
            if (trackSelection != null) {
                trackSelection.onPlaybackSpeed(playbackSpeed)
                trackSelection.onPlayWhenReadyChanged(playWhenReady)
            }
        }
        return selectorResult
    }

    fun applyTrackSelection(
        trackSelectorResult: TrackSelectorResult, positionUs: Long, forceRecreateStreams: Boolean
    ): Long {
        return applyTrackSelection(
            trackSelectorResult,
            positionUs,
            forceRecreateStreams,
            BooleanArray(rendererCapabilities.size)
        )
    }

    fun applyTrackSelection(
        newTrackSelectorResult: TrackSelectorResult,
        positionUs: Long,
        forceRecreateStreams: Boolean,
        streamResetFlags: BooleanArray
    ): Long {
        var positionUs = positionUs
        for (i in 0..<newTrackSelectorResult.length) {
            mayRetainStreamFlags[i] =
                !forceRecreateStreams && newTrackSelectorResult.isEquivalent(trackSelectorResult, i)
        }

        // Undo the effect of previous call to associate no-sample renderers with empty tracks
        // so the mediaPeriod receives back whatever it sent us before.
        disassociateNoSampleRenderersWithEmptySampleStream(sampleStreams)
        disableTrackSelectionsInResult()
        trackSelectorResult = newTrackSelectorResult
        enableTrackSelectionsInResult()
        // Disable streams on the period and get new streams for updated/newly-enabled tracks.
        positionUs =
            mediaPeriod.selectTracks(
                newTrackSelectorResult.selections,
                mayRetainStreamFlags,
                sampleStreams,
                streamResetFlags,
                positionUs
            )
        associateNoSampleRenderersWithEmptySampleStream(sampleStreams)

        // Update whether we have enabled tracks and check that the expected streams are non-null.
        hasEnabledTracks = false
        for (i in sampleStreams.indices) {
            if (sampleStreams[i] != null) {
                Preconditions.checkState(newTrackSelectorResult.isRendererEnabled(i))
                // hasEnabledTracks should be true only when non-empty streams exists.
                if (rendererCapabilities[i]!!.trackType != C.TRACK_TYPE_NONE) {
                    hasEnabledTracks = true
                }
            } else {
                Preconditions.checkState(newTrackSelectorResult.selections[i] == null)
            }
        }
        return positionUs
    }

    fun release() {
        disableTrackSelectionsInResult()
        releaseMediaPeriod(mediaSourceList, mediaPeriod)
    }

    fun setNext(nextMediaPeriodHolder: MediaPeriodHolder?) {
        if (nextMediaPeriodHolder == next) {
            return
        }
        disableTrackSelectionsInResult()
        next = nextMediaPeriodHolder
        enableTrackSelectionsInResult()
    }

    fun getNext(): MediaPeriodHolder? {
        return next
    }

    fun hasLoadingError(): Boolean {
        try {
            if (!prepared) {
                mediaPeriod.maybeThrowPrepareError()
            } else {
                for (sampleStream in sampleStreams) {
                    if (sampleStream != null) {
                        sampleStream.maybeThrowError()
                    }
                }
            }
        } catch (e: IOException) {
            return true
        }
        return false
    }

    private fun enableTrackSelectionsInResult() {
        if (!this.isLoadingMediaPeriod) {
            return
        }
        for (i in 0..<trackSelectorResult.length) {
            val rendererEnabled = trackSelectorResult.isRendererEnabled(i)
            val trackSelection = trackSelectorResult.selections[i]
            if (rendererEnabled && trackSelection != null) {
                trackSelection.enable()
            }
        }
    }

    private fun disableTrackSelectionsInResult() {
        if (!this.isLoadingMediaPeriod) {
            return
        }
        for (i in 0..<trackSelectorResult.length) {
            val rendererEnabled = trackSelectorResult.isRendererEnabled(i)
            val trackSelection = trackSelectorResult.selections[i]
            if (rendererEnabled && trackSelection != null) {
                trackSelection.disable()
            }
        }
    }

    private fun disassociateNoSampleRenderersWithEmptySampleStream(
        sampleStreams: Array<SampleStream?>
    ) {
        for (i in rendererCapabilities.indices) {
            if (rendererCapabilities[i]!!.trackType == C.TRACK_TYPE_NONE) {
                sampleStreams[i] = null
            }
        }
    }

    private fun associateNoSampleRenderersWithEmptySampleStream(
        sampleStreams: Array<SampleStream?>
    ) {
        for (i in rendererCapabilities.indices) {
            if (rendererCapabilities[i]!!.trackType == C.TRACK_TYPE_NONE
                && trackSelectorResult.isRendererEnabled(i)
            ) {
                sampleStreams[i] = EmptySampleStream()
            }
        }
    }

    private val isLoadingMediaPeriod: Boolean
        get() = next == null

    fun canBeUsedForMediaPeriodInfo(info: MediaPeriodInfo): Boolean {
        return MediaPeriodQueue.areDurationsCompatible(
            this.info.durationUs,
            info.durationUs
        )
                && this.info.startPositionUs == info.startPositionUs && this.info.id.equals(info.id)
    }

    fun prepare(callback: MediaPeriod.Callback, startPositionUs: Long) {
        prepareCalled = true
        mediaPeriod.prepare(callback, startPositionUs)
    }

    fun setRendererToCorrectState(index: Int) {
        renderersInCorrectState[index] = true
    }

    fun isRendererInCorrectState(index: Int): Boolean {
        return renderersInCorrectState[index]
    }

    internal fun interface Factory {
        fun create(info: MediaPeriodInfo?, rendererPositionOffsetUs: Long): MediaPeriodHolder?
    }

    companion object {
        private const val TAG = "MediaPeriodHolder"

        private fun releaseMediaPeriod(
            mediaSourceList: MediaSourceList,
            mediaPeriod: MediaPeriod
        ) {
            try {
                mediaSourceList.releasePeriod(mediaPeriod)
            } catch (e: RuntimeException) {
                // There's nothing we can do.
                e(TAG, "Period release failed.", e)
            }
        }
    }
}
