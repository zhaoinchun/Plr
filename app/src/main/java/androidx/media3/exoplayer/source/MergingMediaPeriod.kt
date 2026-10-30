package androidx.media3.exoplayer.source

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.StreamKey
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.ForwardingTrackSelection
import com.google.common.base.Function
import com.google.common.base.Preconditions
import com.google.common.collect.Lists
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

internal class MergingMediaPeriod(
    private val compositeSequenceableLoaderFactory: CompositeSequenceableLoaderFactory,
    periodTimeOffsetsUs: LongArray,
    private val periods: Array<MediaPeriod>
) : MediaPeriod, MediaPeriod.Callback {
    private val periodsWithTimeOffsets: BooleanArray = BooleanArray(periods.size)
    private val streamPeriodIndices: IdentityHashMap<SampleStream, Int> = IdentityHashMap<SampleStream, Int>()
    private val childrenPendingPreparation: ArrayList<MediaPeriod> = ArrayList()
    private val childTrackGroupByMergedTrackGroup: HashMap<TrackGroup, TrackGroup> = HashMap()
    private var compositeSequenceableLoader: SequenceableLoader=compositeSequenceableLoaderFactory.empty()

    private var callback: MediaPeriod.Callback? = null
    private var _trackGroups: TrackGroupArray? = null
    override val trackGroups: TrackGroupArray
        get() = _trackGroups!!
    private var enabledPeriods: Array<MediaPeriod> = arrayOf()

    init {
        for (i in periods.indices) {
            if (periodTimeOffsetsUs[i] != 0L) {
                periodsWithTimeOffsets[i] = true
                this.periods[i] = TimeOffsetMediaPeriod(periods[i], periodTimeOffsetsUs[i])
            }
        }
    }

    fun getChildPeriod(index: Int): MediaPeriod {
        return if (periodsWithTimeOffsets[index])
            (periods[index] as TimeOffsetMediaPeriod).wrappedMediaPeriod
        else
            periods[index]
    }

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        this.callback = callback
        Collections.addAll(childrenPendingPreparation, *periods)
        for (period in periods) {
            period.prepare(this, positionUs)
        }
    }

    @Throws(IOException::class)
    override fun maybeThrowPrepareError() {
        for (period in periods) {
            period.maybeThrowPrepareError()
        }
    }

    override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> {
        // Not supported due to the ambiguity of the stream keys across periods.
        return listOf()
    }

    override fun selectTracks(
        selections: Array<ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long
    ): Long {
        // Map each selection and stream onto a child period index.
        var positionUs = positionUs
        val streamChildIndices = IntArray(selections.size)
        val selectionChildIndices = IntArray(selections.size)
        for (i in selections.indices) {
            val streamChildIndex =
                if (streams[i] == null) null else streamPeriodIndices[streams[i]]
            streamChildIndices[i] = streamChildIndex ?: C.INDEX_UNSET
            if (selections[i] != null) {
                val mergedTrackGroup = selections[i]!!.trackGroup
                // mergedTrackGroup.id is 'periods array index' + ":" + childTrackGroup.id
                selectionChildIndices[i] =
                    mergedTrackGroup.id.substring(0, mergedTrackGroup.id.indexOf(":")).toInt()
            } else {
                selectionChildIndices[i] = C.INDEX_UNSET
            }
        }
        streamPeriodIndices.clear()
        // Select tracks for each child, copying the resulting streams back into a new streams array.
        val newStreams = arrayOfNulls<SampleStream>(selections.size)
        val childStreams = arrayOfNulls<SampleStream>(selections.size)
        val childSelections = arrayOfNulls<ExoTrackSelection>(selections.size)
        val enabledPeriodsList = mutableListOf<MediaPeriod>()
        for (i in periods.indices) {
            for (j in selections.indices) {
                childStreams[j] = if (streamChildIndices[j] == i) streams[j] else null
                if (selectionChildIndices[j] == i) {
                    val mergedTrackSelection =selections[j]!!
                    val mergedTrackGroup = mergedTrackSelection.trackGroup
                    val childTrackGroup =childTrackGroupByMergedTrackGroup[mergedTrackGroup]!!
                    childSelections[j] =
                        MergingMediaPeriodTrackSelection(mergedTrackSelection, childTrackGroup)
                } else {
                    childSelections[j] = null
                }
            }
            val selectPositionUs =
                periods[i].selectTracks(
                    childSelections,
                    mayRetainStreamFlags,
                    childStreams,
                    streamResetFlags,
                    positionUs
                )
            if (i == 0) {
                positionUs = selectPositionUs
            } else check(selectPositionUs == positionUs) { "Children enabled at different positions." }
            var periodEnabled = false
            for (j in selections.indices) {
                if (selectionChildIndices[j] == i) {
                    // Assert that the child provided a stream for the selection.
                    val childStream =childStreams[j]!!
                    newStreams[j] = childStreams[j]
                    periodEnabled = true
                    streamPeriodIndices[childStream] = i
                } else if (streamChildIndices[j] == i) {
                    // Assert that the child cleared any previous stream.
                    Preconditions.checkState(childStreams[j] == null)
                }
            }
            if (periodEnabled) {
                enabledPeriodsList.add(periods[i])
            }
        }
        // Copy the new streams back into the streams array.
        System.arraycopy(newStreams, 0, streams, 0, newStreams.size)
        // Update the local state.
        enabledPeriods = enabledPeriodsList.toTypedArray<MediaPeriod>()
        compositeSequenceableLoader =
            compositeSequenceableLoaderFactory.create(
                enabledPeriodsList,
                Lists.transform<MediaPeriod, List<Int>>(
                    enabledPeriodsList,
                    Function { period: MediaPeriod -> period.trackGroups.trackTypes })
            )
        return positionUs
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
        for (period in enabledPeriods) {
            period.discardBuffer(positionUs, toKeyframe)
        }
    }

    override fun reevaluateBuffer(positionUs: Long) {
        compositeSequenceableLoader.reevaluateBuffer(positionUs)
    }

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
        if (!childrenPendingPreparation.isEmpty()) {
            // Preparation is still going on.
            val childrenPendingPreparationSize = childrenPendingPreparation.size
            for (i in 0..<childrenPendingPreparationSize) {
                childrenPendingPreparation[i].continueLoading(loadingInfo)
            }
            return false
        } else {
            return compositeSequenceableLoader.continueLoading(loadingInfo)
        }
    }

    override val isLoading: Boolean
        get() = compositeSequenceableLoader.isLoading

    override val nextLoadPositionUs: Long
        get() = compositeSequenceableLoader.nextLoadPositionUs

    override fun setUsesStreamPrerollFlags() {
        for (period in periods) {
            period.setUsesStreamPrerollFlags()
        }
    }

    override fun readDiscontinuity(): Long {
        var discontinuityUs = C.TIME_UNSET
        for (period in enabledPeriods) {
            val otherDiscontinuityUs = period.readDiscontinuity()
            if (otherDiscontinuityUs != C.TIME_UNSET) {
                if (discontinuityUs == C.TIME_UNSET) {
                    discontinuityUs = otherDiscontinuityUs
                    // First reported discontinuity. Seek all previous periods to the new position.
                    for (previousPeriod in enabledPeriods) {
                        if (previousPeriod === period) {
                            break
                        }
                        check(previousPeriod.seekToUs(discontinuityUs) == discontinuityUs) { "Unexpected child seekToUs result." }
                    }
                } else check(otherDiscontinuityUs == discontinuityUs) { "Conflicting discontinuities." }
            } else if (discontinuityUs != C.TIME_UNSET) {
                // We already have a discontinuity, seek this period to the new position.
                check(period.seekToUs(discontinuityUs) == discontinuityUs) { "Unexpected child seekToUs result." }
            }
        }
        return discontinuityUs
    }

    override val bufferedPositionUs: Long
        get() = compositeSequenceableLoader.bufferedPositionUs

    override fun seekToUs(positionUs: Long): Long {
        var positionUs = positionUs
        positionUs = enabledPeriods[0].seekToUs(positionUs)
        // Additional periods must seek to the same position.
        for (i in 1..<enabledPeriods.size) {
            check(enabledPeriods[i].seekToUs(positionUs) == positionUs) { "Unexpected child seekToUs result." }
        }
        return positionUs
    }

    override fun getAdjustedSeekPositionUs(
        positionUs: Long,
        seekParameters: SeekParameters
    ): Long {
        val queryPeriod = if (enabledPeriods.isNotEmpty()) enabledPeriods[0] else periods[0]
        return queryPeriod.getAdjustedSeekPositionUs(positionUs, seekParameters)
    }

    override fun setEndPositionUs(endPositionUs: Long): Long {
        var supported = true
        for (period in periods) {
            val actualEndPositionUs = period.setEndPositionUs(endPositionUs)
            supported = supported and (actualEndPositionUs == endPositionUs)
        }
        return if (supported) endPositionUs else C.TIME_END_OF_SOURCE
    }

    // MediaPeriod.Callback implementation
    override fun onPrepared(preparedPeriod: MediaPeriod) {
        childrenPendingPreparation.remove(preparedPeriod)
        if (!childrenPendingPreparation.isEmpty()) {
            return
        }
        var totalTrackGroupCount = 0
        for (period in periods) {
            totalTrackGroupCount += period.trackGroups.length
        }
        val trackGroupArray = arrayOfNulls<TrackGroup>(totalTrackGroupCount)
        var trackGroupIndex = 0
        for (i in periods.indices) {
            val periodTrackGroups = periods[i].trackGroups
            val periodTrackGroupCount = periodTrackGroups.length
            for (j in 0..<periodTrackGroupCount) {
                val childTrackGroup = periodTrackGroups.get(j)
                val mergedFormats = Array(childTrackGroup.length) {
                    val originalFormat = childTrackGroup.getFormat(it)
                    val mergedFormatBuilder = originalFormat.buildUpon()
                    mergedFormatBuilder.setId(i.toString() + ":" + (originalFormat.id ?: ""))
                    if (originalFormat.primaryTrackGroupId != null) {
                        mergedFormatBuilder.setPrimaryTrackGroupId(
                            i.toString() + ":" + originalFormat.primaryTrackGroupId
                        )
                    }
                    mergedFormatBuilder.build()
                }
                val mergedTrackGroup =
                    TrackGroup( i.toString() + ":" + childTrackGroup.id,
                        mergedFormats.toList()
                    )
                childTrackGroupByMergedTrackGroup[mergedTrackGroup] = childTrackGroup
                trackGroupArray[trackGroupIndex++] = mergedTrackGroup
            }
        }
        _trackGroups = TrackGroupArray(trackGroupArray as Array<TrackGroup>)
        callback!!.onPrepared(this)
    }

    override fun onContinueLoadingRequested(ignored: MediaPeriod) {
        callback!!.onContinueLoadingRequested(this)
    }

    private class MergingMediaPeriodTrackSelection(
        trackSelection: ExoTrackSelection,
        override val trackGroup: TrackGroup
    ) : ForwardingTrackSelection(trackSelection) {

        override fun getFormat(index: Int): Format {
            return trackGroup.getFormat(wrappedInstance.getIndexInTrackGroup(index))
        }

        override fun indexOf(format: Format): Int {
            return wrappedInstance.indexOf(trackGroup.indexOf(format))
        }

        override val selectedFormat: Format
            get() = trackGroup.getFormat(wrappedInstance.selectedIndexInTrackGroup)

        override fun equals(other: Any?): Boolean {
            if (!super.equals(other) || other !is MergingMediaPeriodTrackSelection) {
                return false
            }
            val that: MergingMediaPeriodTrackSelection = other
            return trackGroup == that.trackGroup
        }

        override fun hashCode(): Int {
            return 31 * super.hashCode() + trackGroup.hashCode()
        }
    }
}
