package androidx.media3.exoplayer.trackselection

import androidx.annotation.CallSuper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.Clock
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.Util.getPlayoutDurationForMediaDuration
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.chunk.MediaChunk
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.google.common.collect.Iterables
import com.google.common.collect.Multimap
import com.google.common.collect.MultimapBuilder
import java.util.Arrays
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

open class AdaptiveTrackSelection(
    group: TrackGroup,
    tracks: IntArray,
    type: @TrackSelection.Type Int,
    private val bandwidthMeter: BandwidthMeter,
    minDurationForQualityIncreaseMs: Long,
    maxDurationForQualityDecreaseMs: Long,
    minDurationToRetainAfterDiscardMs: Long,
    private val maxWidthToDiscard: Int,
    private val maxHeightToDiscard: Int,
    private val bandwidthFraction: Float,
    private val bufferedFractionToLiveEdgeForQualityIncrease: Float,
    adaptationCheckpoints: List<AdaptationCheckpoint>,
    private val clock: Clock,
    trackFormatComparator: Comparator<Format> = DEFAULT_FORMAT_COMPARATOR
) : BaseTrackSelection(group, tracks, type, trackFormatComparator) {
    class Factory(
        private val minDurationForQualityIncreaseMs: Int,
        private val maxDurationForQualityDecreaseMs: Int,
        private val minDurationToRetainAfterDiscardMs: Int,
        private val maxWidthToDiscard: Int,
        private val maxHeightToDiscard: Int,
        private val bandwidthFraction: Float,
        private val bufferedFractionToLiveEdgeForQualityIncrease: Float = DEFAULT_BUFFERED_FRACTION_TO_LIVE_EDGE_FOR_QUALITY_INCREASE,
        private val clock: Clock = Clock.DEFAULT
    ) : ExoTrackSelection.Factory {
        private var trackFormatComparator: Comparator<Format> = DEFAULT_FORMAT_COMPARATOR

        constructor(
            minDurationForQualityIncreaseMs: Int = DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS,
            maxDurationForQualityDecreaseMs: Int = DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS,
            minDurationToRetainAfterDiscardMs: Int = DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
            bandwidthFraction: Float = DEFAULT_BANDWIDTH_FRACTION
        ) : this(
            minDurationForQualityIncreaseMs,
            maxDurationForQualityDecreaseMs,
            minDurationToRetainAfterDiscardMs,
            DEFAULT_MAX_WIDTH_TO_DISCARD,
            DEFAULT_MAX_HEIGHT_TO_DISCARD,
            bandwidthFraction,
            DEFAULT_BUFFERED_FRACTION_TO_LIVE_EDGE_FOR_QUALITY_INCREASE,
            Clock.DEFAULT
        )

        fun setTrackFormatComparator(trackFormatComparator: Comparator<Format>): Factory {
            this.trackFormatComparator = trackFormatComparator
            return this
        }

        override fun createTrackSelections(
            definitions: Array<ExoTrackSelection.Definition?>,
            bandwidthMeter: BandwidthMeter,
            mediaPeriodId: MediaPeriodId,
            timeline: Timeline
        ): Array<ExoTrackSelection?> {
            val adaptationCheckpoints = getAdaptationCheckpoints(definitions)
            val selections = arrayOfNulls<ExoTrackSelection>(definitions.size)
            for (i in definitions.indices) {
                val definition = definitions[i]
                if (definition == null || definition.tracks.isEmpty()) {
                    continue
                }
                selections[i] = if (definition.tracks.size == 1) {
                    FixedTrackSelection(
                        definition.group,
                        definition.tracks[0],
                        definition.type
                    )
                } else {
                    createAdaptiveTrackSelection(
                        definition.group,
                        definition.tracks,
                        definition.type,
                        bandwidthMeter,
                        adaptationCheckpoints[i]
                    )
                }
            }
            return selections
        }

        protected fun createAdaptiveTrackSelection(
            group: TrackGroup,
            tracks: IntArray,
            type: Int,
            bandwidthMeter: BandwidthMeter,
            adaptationCheckpoints: List<AdaptationCheckpoint>
        ): AdaptiveTrackSelection {
            return AdaptiveTrackSelection(
                group,
                tracks,
                type,
                bandwidthMeter,
                minDurationForQualityIncreaseMs.toLong(),
                maxDurationForQualityDecreaseMs.toLong(),
                minDurationToRetainAfterDiscardMs.toLong(),
                maxWidthToDiscard,
                maxHeightToDiscard,
                bandwidthFraction,
                bufferedFractionToLiveEdgeForQualityIncrease,
                adaptationCheckpoints,
                clock,
                trackFormatComparator
            )
        }
    }

    constructor(group: TrackGroup, tracks: IntArray, bandwidthMeter: BandwidthMeter) : this(
        group,
        tracks,
        TrackSelection.TYPE_UNSET,
        bandwidthMeter,
        DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS.toLong(),
        DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS.toLong(),
        DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS.toLong(),
        DEFAULT_MAX_WIDTH_TO_DISCARD,
        DEFAULT_MAX_HEIGHT_TO_DISCARD,
        DEFAULT_BANDWIDTH_FRACTION,
        DEFAULT_BUFFERED_FRACTION_TO_LIVE_EDGE_FOR_QUALITY_INCREASE,
        listOf(),
        Clock.DEFAULT
    )

    private val minDurationForQualityIncreaseUs: Long = minDurationForQualityIncreaseMs * 1000L
    private val maxDurationForQualityDecreaseUs: Long = maxDurationForQualityDecreaseMs * 1000L
    private val minDurationToRetainAfterDiscardUs: Long =
        run {
            var minDurationToRetainAfterDiscardMs = minDurationToRetainAfterDiscardMs
            if (minDurationToRetainAfterDiscardMs < minDurationForQualityIncreaseMs) {
                w(
                    TAG,
                    "Adjusting minDurationToRetainAfterDiscardMs to be at least"
                            + " minDurationForQualityIncreaseMs"
                )
                minDurationToRetainAfterDiscardMs = minDurationForQualityIncreaseMs
            }
            minDurationToRetainAfterDiscardMs
        } * 1000L
    private val adaptationCheckpoints: List<AdaptationCheckpoint> = adaptationCheckpoints.toList()
    private var playbackSpeed = 1f
    override var selectedIndex = 0
    private var reason: @C.SelectionReason Int = C.SELECTION_REASON_UNKNOWN
    private var lastBufferEvaluationMs = C.TIME_UNSET
    private var lastBufferEvaluationMediaChunk: MediaChunk? = null
    override var latestBitrateEstimate = C.RATE_UNSET_INT.toLong()

    @CallSuper
    override fun enable() {
        lastBufferEvaluationMs = C.TIME_UNSET
        lastBufferEvaluationMediaChunk = null
    }

    @CallSuper
    override fun disable() {
        // Avoid keeping a reference to a MediaChunk in case it prevents garbage collection.
        lastBufferEvaluationMediaChunk = null
    }

    override fun onPlaybackSpeed(playbackSpeed: Float) {
        this.playbackSpeed = playbackSpeed
    }

    override fun updateSelectedTrack(
        playbackPositionUs: Long,
        bufferedDurationUs: Long,
        availableDurationUs: Long,
        queue: List<MediaChunk>,
        mediaChunkIterators: Array<MediaChunkIterator>
    ) {
        val nowMs = clock.elapsedRealtime()
        val chunkDurationUs = getNextChunkDurationUs(mediaChunkIterators, queue)
        val effectiveBitrate = getAllocatedBandwidth(chunkDurationUs)

        // Make initial selection
        if (reason == C.SELECTION_REASON_UNKNOWN) {
            reason = C.SELECTION_REASON_INITIAL
            selectedIndex = determineIdealSelectedIndex(nowMs, effectiveBitrate)
            return
        }

        var previousSelectedIndex = selectedIndex
        var previousReason: @C.SelectionReason Int = reason
        val formatIndexOfPreviousChunk =
            if (queue.isEmpty()) C.INDEX_UNSET else indexOf(Iterables.getLast(queue).trackFormat)
        if (formatIndexOfPreviousChunk != C.INDEX_UNSET) {
            previousSelectedIndex = formatIndexOfPreviousChunk
            previousReason = Iterables.getLast(queue).trackSelectionReason
        }
        var newSelectedIndex = determineIdealSelectedIndex(nowMs, effectiveBitrate)
        if (newSelectedIndex != previousSelectedIndex &&
            !isTrackExcluded(previousSelectedIndex, nowMs)
        ) {
            // Revert back to the previous selection if conditions are not suitable for switching.
            val minDurationForQualityIncreaseUs =
                minDurationForQualityIncreaseUs(availableDurationUs, chunkDurationUs)
            if (newSelectedIndex < previousSelectedIndex
                && bufferedDurationUs < minDurationForQualityIncreaseUs
            ) {
                // The selected track is higher priority, but we have insufficient buffer to safely switch
                // up. Defer switching up for now.
                newSelectedIndex = previousSelectedIndex
            } else if (newSelectedIndex > previousSelectedIndex
                && bufferedDurationUs >= maxDurationForQualityDecreaseUs
            ) {
                // The selected track is lower priority, but we have sufficient buffer to defer switching
                // down for now.
                newSelectedIndex = previousSelectedIndex
            }
        }
        // If we adapted, update the trigger.
        reason =
            if (newSelectedIndex == previousSelectedIndex) previousReason else C.SELECTION_REASON_ADAPTIVE
        selectedIndex = newSelectedIndex
    }

    override val selectionReason: @C.SelectionReason Int
        get() = reason

    override val selectionData = null

    override fun evaluateQueueSize(playbackPositionUs: Long, queue: List<MediaChunk>): Int {
        val nowMs = clock.elapsedRealtime()
        if (!shouldEvaluateQueueSize(nowMs, queue)) {
            return queue.size
        }
        lastBufferEvaluationMs = nowMs
        lastBufferEvaluationMediaChunk = if (queue.isEmpty()) null else Iterables.getLast(queue)

        if (queue.isEmpty()) {
            return 0
        }
        val queueSize = queue.size
        val lastChunk: MediaChunk = queue[queueSize - 1]
        val playoutBufferedDurationBeforeLastChunkUs =
            getPlayoutDurationForMediaDuration(
                lastChunk.startTimeUs - playbackPositionUs, playbackSpeed
            )
        val minDurationToRetainAfterDiscardUs = minDurationToRetainAfterDiscardUs
        if (playoutBufferedDurationBeforeLastChunkUs < minDurationToRetainAfterDiscardUs) {
            return queueSize
        }
        val idealSelectedIndex =
            determineIdealSelectedIndex(nowMs, getAllocatedBandwidth(getLastChunkDurationUs(queue)))
        val idealFormat = getFormat(idealSelectedIndex)
        // If chunks contain video, discard from the first chunk after minDurationToRetainAfterDiscardUs
        // whose resolution and bitrate are both lower than the ideal track, and whose width and height
        // are less than or equal to maxWidthToDiscard and maxHeightToDiscard respectively.
        for (i in 0..<queueSize) {
            val chunk: MediaChunk = queue[i]
            val format = chunk.trackFormat
            val mediaDurationBeforeThisChunkUs = chunk.startTimeUs - playbackPositionUs
            val playoutDurationBeforeThisChunkUs =
                getPlayoutDurationForMediaDuration(mediaDurationBeforeThisChunkUs, playbackSpeed)
            if (playoutDurationBeforeThisChunkUs >= minDurationToRetainAfterDiscardUs &&
                format.bitrate < idealFormat.bitrate &&
                format.height != Format.NO_VALUE && format.height <= maxHeightToDiscard &&
                format.width != Format.NO_VALUE && format.width <= maxWidthToDiscard &&
                format.height < idealFormat.height
            ) {
                return i
            }
        }
        return queueSize
    }

    protected fun canSelectFormat(
        format: Format,
        trackBitrate: Int,
        effectiveBitrate: Long
    ): Boolean {
        return trackBitrate <= effectiveBitrate
    }

    protected fun shouldEvaluateQueueSize(nowMs: Long, queue: List<MediaChunk>): Boolean {
        return lastBufferEvaluationMs == C.TIME_UNSET ||
                nowMs - lastBufferEvaluationMs >= MIN_TIME_BETWEEN_BUFFER_REEVALUTATION_MS ||
                (!queue.isEmpty() && Iterables.getLast(queue) != lastBufferEvaluationMediaChunk)
    }

    private fun determineIdealSelectedIndex(nowMs: Long, effectiveBitrate: Long): Int {
        var lowestBitrateAllowedIndex = 0
        for (i in 0..<length) {
            if (nowMs == Long.MIN_VALUE || !isTrackExcluded(i, nowMs)) {
                val format = getFormat(i)
                if (canSelectFormat(format, format.bitrate, effectiveBitrate)) {
                    return i
                } else {
                    lowestBitrateAllowedIndex = i
                }
            }
        }
        return lowestBitrateAllowedIndex
    }

    private fun minDurationForQualityIncreaseUs(
        availableDurationUs: Long,
        chunkDurationUs: Long
    ): Long {
        var availableDurationUs = availableDurationUs
        if (availableDurationUs == C.TIME_UNSET) {
            // We are not in a live stream. Use the configured value.
            return minDurationForQualityIncreaseUs
        }
        if (chunkDurationUs != C.TIME_UNSET) {
            // We are currently selecting a new live chunk. Even under perfect conditions, the buffered
            // duration can't include the last chunk duration yet because we are still selecting a track
            // for this or a previous chunk. Hence, we subtract one chunk duration from the total
            // available live duration to ensure we only compare the buffered duration against what is
            // actually achievable.
            availableDurationUs -= chunkDurationUs
        }
        val adjustedMinDurationForQualityIncreaseUs =
            (availableDurationUs * bufferedFractionToLiveEdgeForQualityIncrease).toLong()
        return min(adjustedMinDurationForQualityIncreaseUs, minDurationForQualityIncreaseUs)
    }

    private fun getNextChunkDurationUs(
        mediaChunkIterators: Array<MediaChunkIterator>, queue: List<MediaChunk>
    ): Long {
        // Try to get the next chunk duration for the currently selected format.
        if (selectedIndex < mediaChunkIterators.size && mediaChunkIterators[selectedIndex].next()) {
            val iterator = mediaChunkIterators[selectedIndex]
            return iterator.chunkEndTimeUs - iterator.chunkStartTimeUs
        }
        // Try to get the next chunk duration for another format, on the assumption that chunks
        // belonging to different formats are likely to have identical or similar durations.
        for (iterator in mediaChunkIterators) {
            if (iterator.next()) {
                return iterator.chunkEndTimeUs - iterator.chunkStartTimeUs
            }
        }
        // Try to get chunk duration for last chunk in the queue, on the assumption that the next chunk
        // is likely to have a similar duration.
        return getLastChunkDurationUs(queue)
    }

    private fun getLastChunkDurationUs(queue: List<MediaChunk>): Long {
        if (queue.isEmpty()) {
            return C.TIME_UNSET
        }
        val lastChunk = Iterables.getLast(queue)
        return if (lastChunk.startTimeUs != C.TIME_UNSET && lastChunk.endTimeUs != C.TIME_UNSET)
            lastChunk.endTimeUs - lastChunk.startTimeUs
        else
            C.TIME_UNSET
    }

    private fun getAllocatedBandwidth(chunkDurationUs: Long): Long {
        val totalBandwidth = getTotalAllocatableBandwidth(chunkDurationUs)
        if (adaptationCheckpoints.isEmpty()) {
            return totalBandwidth
        }
        var nextIndex = 1
        while (nextIndex < adaptationCheckpoints.size - 1
            && adaptationCheckpoints[nextIndex].totalBandwidth < totalBandwidth
        ) {
            nextIndex++
        }
        val previous = adaptationCheckpoints[nextIndex - 1]
        val next = adaptationCheckpoints[nextIndex]
        val fractionBetweenCheckpoints =
            (totalBandwidth - previous.totalBandwidth).toFloat() / (next.totalBandwidth - previous.totalBandwidth)
        return (previous.allocatedBandwidth
                + (fractionBetweenCheckpoints * (next.allocatedBandwidth - previous.allocatedBandwidth)).toLong())
    }

    private fun getTotalAllocatableBandwidth(chunkDurationUs: Long): Long {
        latestBitrateEstimate = bandwidthMeter.bitrateEstimate
        val cautiousBandwidthEstimate = (latestBitrateEstimate * bandwidthFraction).toLong()
        val timeToFirstByteEstimateUs = bandwidthMeter.timeToFirstByteEstimateUs
        if (timeToFirstByteEstimateUs == C.TIME_UNSET || chunkDurationUs == C.TIME_UNSET) {
            return (cautiousBandwidthEstimate / playbackSpeed).toLong()
        }
        val availableTimeToLoadUs =
            max(chunkDurationUs / playbackSpeed - timeToFirstByteEstimateUs, 0f)
        return (cautiousBandwidthEstimate * availableTimeToLoadUs / chunkDurationUs).toLong()
    }

    class AdaptationCheckpoint(val totalBandwidth: Long, val allocatedBandwidth: Long) {
        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other !is AdaptationCheckpoint) {
                return false
            }
            return totalBandwidth == other.totalBandwidth && allocatedBandwidth == other.allocatedBandwidth
        }

        override fun hashCode(): Int {
            return 31 * totalBandwidth.toInt() + allocatedBandwidth.toInt()
        }
    }

    companion object {
        private const val TAG = "AdaptiveTrackSelection"

        const val DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS: Int = 10000
        const val DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS: Int = 25000
        const val DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS: Int = 25000
        const val DEFAULT_MAX_WIDTH_TO_DISCARD: Int = 1279
        const val DEFAULT_MAX_HEIGHT_TO_DISCARD: Int = 719
        const val DEFAULT_BANDWIDTH_FRACTION: Float = 0.7f
        const val DEFAULT_BUFFERED_FRACTION_TO_LIVE_EDGE_FOR_QUALITY_INCREASE: Float = 0.75f
        private const val MIN_TIME_BETWEEN_BUFFER_REEVALUTATION_MS: Long = 1000

        private fun getAdaptationCheckpoints(definitions: Array<ExoTrackSelection.Definition?>):
                List<List<AdaptationCheckpoint>> {
            val checkPointBuilders = mutableListOf<MutableList<AdaptationCheckpoint>?>()
            for (i in definitions.indices) {
                if (definitions[i] != null && definitions[i]!!.tracks.size > 1) {
                    val builder = mutableListOf<AdaptationCheckpoint>()
                    // Add initial all-zero checkpoint.
                    builder.add(AdaptationCheckpoint(0, 0))
                    checkPointBuilders.add(builder)
                } else {
                    checkPointBuilders.add(null)
                }
            }
            // Add minimum bitrate selection checkpoint.
            val trackBitrates: Array<LongArray> = getSortedTrackBitrates(definitions)
            val currentTrackIndices = IntArray(trackBitrates.size)
            val currentTrackBitrates = LongArray(trackBitrates.size)
            for (i in trackBitrates.indices) {
                currentTrackBitrates[i] = if (trackBitrates[i].isEmpty()) 0 else trackBitrates[i][0]
            }
            addCheckpoint(checkPointBuilders, currentTrackBitrates)
            // Iterate through all adaptive checkpoints.
            val switchOrder = getSwitchOrder(trackBitrates)
            for (i in switchOrder.indices) {
                val switchIndex: Int = switchOrder[i]
                val newTrackIndex = ++currentTrackIndices[switchIndex]
                currentTrackBitrates[switchIndex] = trackBitrates[switchIndex][newTrackIndex]
                addCheckpoint(checkPointBuilders, currentTrackBitrates)
            }
            // Add final checkpoint to extrapolate additional bandwidth for adaptive selections.
            for (i in definitions.indices) {
                if (checkPointBuilders[i] != null) {
                    currentTrackBitrates[i] *= 2
                }
            }
            addCheckpoint(checkPointBuilders, currentTrackBitrates)

            val output = checkPointBuilders.map { it ?: listOf<AdaptationCheckpoint>() }
            return output
        }

        private fun getSortedTrackBitrates(definitions: Array<ExoTrackSelection.Definition?>): Array<LongArray> {
            val trackBitrates = Array(definitions.size) { i ->
                val definition = definitions[i]
                if (definition == null) {
                    LongArray(0)
                } else {
                    LongArray(definition.tracks.size).also { arr ->
                        for (j in definition.tracks.indices) {
                            val bitrate =
                                definition.group.getFormat(definition.tracks[j]).bitrate.toLong()
                            arr[j] = if (bitrate == Format.NO_VALUE.toLong()) 0 else bitrate
                        }
                        Arrays.sort(arr)
                    }
                }
            }
            return trackBitrates
        }

        private fun getSwitchOrder(trackBitrates: Array<LongArray>): List<Int> {
            // Algorithm:
            //  1. Use log bitrates to treat all bitrate update steps equally.
            //  2. Distribute switch points for each selection equally in the same [0.0-1.0] range.
            //  3. Switch up one format at a time in the order of the switch points.
            val switchPoints: Multimap<Double, Int> =
                MultimapBuilder.treeKeys().arrayListValues().build<Double, Int>()
            for (i in trackBitrates.indices) {
                if (trackBitrates[i].size <= 1) {
                    continue
                }
                val logBitrates = DoubleArray(trackBitrates[i].size)
                for (j in trackBitrates[i].indices) {
                    logBitrates[j] =
                        if (trackBitrates[i][j] == Format.NO_VALUE.toLong()) {
                            0.0
                        } else {
                            ln(trackBitrates[i][j].toDouble())
                        }
                }
                val totalBitrateDiff = logBitrates[logBitrates.size - 1] - logBitrates[0]
                for (j in 0..<logBitrates.size - 1) {
                    val switchBitrate = 0.5 * (logBitrates[j] + logBitrates[j + 1])
                    val switchPoint =
                        if (totalBitrateDiff == 0.0) 1.0 else (switchBitrate - logBitrates[0]) / totalBitrateDiff
                    switchPoints.put(switchPoint, i)
                }
            }
            return switchPoints.values().toList()
        }

        private fun addCheckpoint(
            checkPointBuilders: MutableList<MutableList<AdaptationCheckpoint>?>,
            checkpointBitrates: LongArray
        ) {
            var totalBitrate: Long = 0
            for (i in checkpointBitrates.indices) {
                totalBitrate += checkpointBitrates[i]
            }
            for (i in checkPointBuilders.indices) {
                val builder = checkPointBuilders[i] ?: continue
                builder.add(
                    AdaptationCheckpoint(totalBitrate, checkpointBitrates[i])
                )
            }
        }
    }
}
