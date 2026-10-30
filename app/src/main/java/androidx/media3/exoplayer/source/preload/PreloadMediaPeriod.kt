package androidx.media3.exoplayer.source.preload

import androidx.media3.common.C
import androidx.media3.common.StreamKey
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import com.google.common.base.Preconditions
import java.io.IOException

internal class PreloadMediaPeriod
(val mediaPeriod: MediaPeriod) : MediaPeriod {
    var prepared: Boolean = false
    private var prepareInternalCalled = false
    private var callback: MediaPeriod.Callback? = null
    private var preloadTrackSelectionHolder: PreloadTrackSelectionHolder? = null

    fun preload(callback: MediaPeriod.Callback, positionUs: Long) {
        this.callback = callback
        if (prepared) {
            callback.onPrepared(this@PreloadMediaPeriod)
        }
        if (!prepareInternalCalled) {
            prepareInternal(positionUs)
        }
    }

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        this.callback = callback
        if (prepared) {
            callback.onPrepared(this@PreloadMediaPeriod)
            return
        }
        if (!prepareInternalCalled) {
            prepareInternal(positionUs)
        }
    }

    private fun prepareInternal(positionUs: Long) {
        prepareInternalCalled = true
        mediaPeriod.prepare(
            object : MediaPeriod.Callback {
                override fun onContinueLoadingRequested(mediaPeriod: MediaPeriod) {
                    callback!!.onContinueLoadingRequested(this@PreloadMediaPeriod)
                }

                override fun onPrepared(mediaPeriod: MediaPeriod) {
                    prepared = true
                    callback!!.onPrepared(this@PreloadMediaPeriod)
                }
            },
            positionUs
        )
    }

    @Throws(IOException::class)
    override fun maybeThrowPrepareError() {
        mediaPeriod.maybeThrowPrepareError()
    }

    override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> {
        return mediaPeriod.getStreamKeys(trackSelections).toList()
    }

    override val trackGroups: TrackGroupArray
        get() = mediaPeriod.trackGroups

    override fun selectTracks(
        selections: Array<ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long
    ): Long {
        return selectTracksInternal(
            selections, mayRetainStreamFlags, streams, streamResetFlags, positionUs
        )
    }

    private fun selectTracksInternal(
        selections: Array<ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long
    ): Long {
        if (preloadTrackSelectionHolder == null) {
            // No preload track selection was done.
            return mediaPeriod.selectTracks(
                selections, mayRetainStreamFlags, streams, streamResetFlags, positionUs
            )
        }
        Preconditions.checkState(streams.size == preloadTrackSelectionHolder!!.streams.size)
        if (positionUs != preloadTrackSelectionHolder!!.trackSelectionPositionUs) {
            // Position changed. Copy formerly preloaded sample streams to the track selection properties
            // to make sure we give the period the chance to release discarded sample streams.
            for (i in preloadTrackSelectionHolder!!.streams.indices) {
                if (preloadTrackSelectionHolder!!.streams[i] != null) {
                    streams[i] = preloadTrackSelectionHolder!!.streams[i]
                    mayRetainStreamFlags[i] = false
                }
            }
            preloadTrackSelectionHolder = null
            return mediaPeriod.selectTracks(
                selections, mayRetainStreamFlags, streams, streamResetFlags, positionUs
            )
        }
        val holder: PreloadTrackSelectionHolder =preloadTrackSelectionHolder!!
        var trackSelectionPositionUs = holder.trackSelectionPositionUs
        var preloadStreamResetFlags = holder.streamResetFlags
        if (maybeUpdatePreloadTrackSelectionHolderForReselection(selections, holder)) {
            // Preload can only be partially reused. Select tracks again attempting to retain preloads.
            preloadStreamResetFlags = BooleanArray(preloadStreamResetFlags.size)
            trackSelectionPositionUs =
                mediaPeriod.selectTracks(
                    holder.selections,
                    holder.mayRetainStreamFlags,
                    holder.streams,
                    preloadStreamResetFlags,
                    holder.trackSelectionPositionUs
                )
            // Make sure to set the reset flags for streams we preloaded.
            for (i in holder.mayRetainStreamFlags.indices) {
                if (holder.mayRetainStreamFlags[i]) {
                    preloadStreamResetFlags[i] = true
                }
            }
        }
        System.arraycopy(holder.streams, 0, streams, 0, holder.streams.size)
        System.arraycopy(
            preloadStreamResetFlags, 0, streamResetFlags, 0, preloadStreamResetFlags.size
        )
        preloadTrackSelectionHolder = null
        return trackSelectionPositionUs
    }

    override fun setEndPositionUs(endPositionUs: Long): Long {
        return mediaPeriod.setEndPositionUs(endPositionUs)
    }

    fun selectTracksForPreloading(
        selections: Array<ExoTrackSelection?>, positionUs: Long
    ): Long {
        val preloadedSampleStreams = arrayOfNulls<SampleStream>(selections.size)
        val preloadedStreamResetFlags = BooleanArray(selections.size)
        val mayRetainStreamFlags = BooleanArray(selections.size)
        val trackSelectionPositionUs =
            selectTracksInternal(
                selections,
                mayRetainStreamFlags,
                preloadedSampleStreams,
                preloadedStreamResetFlags,
                positionUs
            )
        preloadTrackSelectionHolder =
            PreloadTrackSelectionHolder(
                selections,
                mayRetainStreamFlags,
                preloadedSampleStreams,
                preloadedStreamResetFlags,
                trackSelectionPositionUs
            )
        return trackSelectionPositionUs
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

    @Throws(IOException::class)
    fun maybeThrowStreamError() {
        Preconditions.checkState(prepared)
        if (preloadTrackSelectionHolder != null) {
            for (stream in preloadTrackSelectionHolder!!.streams) {
                stream?.maybeThrowError()
            }
        }
    }

    private class PreloadTrackSelectionHolder(
        val selections: Array<ExoTrackSelection?>,
        val mayRetainStreamFlags: BooleanArray,
        val streams: Array<SampleStream?>,
        val streamResetFlags: BooleanArray,
        val trackSelectionPositionUs: Long
    )

    companion object {
        private fun maybeUpdatePreloadTrackSelectionHolderForReselection(
            selections: Array<ExoTrackSelection?>,
            preloadTrackSelectionHolder: PreloadTrackSelectionHolder?
        ): Boolean {
            val preloadSelections: Array<ExoTrackSelection?> =preloadTrackSelectionHolder!!.selections
            var needsReselection = false
            for (i in selections.indices) {
                val selection = selections[i]
                val preloadSelection = preloadSelections[i]
                if (selection == null && preloadSelection == null) {
                    continue
                }
                preloadTrackSelectionHolder.mayRetainStreamFlags[i] = false
                if (selection == null) {
                    // Preloaded track got disabled. Discard preloaded stream.
                    preloadTrackSelectionHolder.selections[i] = null
                    needsReselection = true
                } else if (preloadSelection == null) {
                    // Enabled track not preloaded. Update selection.
                    preloadTrackSelectionHolder.selections[i] = selection
                    needsReselection = true
                } else if (!isSameAdaptionSet(selection, preloadSelection)) {
                    // Adaption set has changed. Discard preloaded stream.
                    preloadTrackSelectionHolder.selections[i] = selection
                    needsReselection = true
                } else if (selection.trackGroup.type == C.TRACK_TYPE_VIDEO || selection.trackGroup.type == C.TRACK_TYPE_AUDIO || (selection.selectedIndexInTrackGroup
                            == preloadSelection.selectedIndexInTrackGroup)
                ) {
                    // The selection in a audio or video track has changed or it hasn't changed. Set the retain
                    // flag in case we reselect with a partially preloaded streams set.
                    preloadTrackSelectionHolder.mayRetainStreamFlags[i] = true
                } else {
                    // The selection in a non-audio or non-video track has changed. Discard preloaded stream.
                    preloadTrackSelectionHolder.selections[i] = selection
                    needsReselection = true
                }
            }
            return needsReselection
        }

        private fun isSameAdaptionSet(
            selection: ExoTrackSelection, preloadSelection: ExoTrackSelection
        ): Boolean {
            if (selection.trackGroup != preloadSelection.trackGroup || selection.length() != preloadSelection.length()) {
                return false
            }
            for (i in 0..<selection.length()) {
                if (selection.getIndexInTrackGroup(i) != preloadSelection.getIndexInTrackGroup(i)) {
                    return false
                }
            }
            return true
        }
    }
}
