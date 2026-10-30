
package androidx.media3.extractor.mp4

import androidx.media3.common.C

import androidx.media3.common.util.Util.binarySearchCeil
import androidx.media3.common.util.Util.binarySearchFloor
import com.google.common.base.Preconditions

class TrackSampleTable(
    track: Track?,
    offsets: LongArray,
    sizes: IntArray,
    maximumSize: Int,
    timestampsUs: LongArray,
    flags: IntArray,
    syncSampleIndices: IntArray,
    hasOnlySyncSamples: Boolean,
    durationUs: Long,
    sampleCount: Int
) {
    
    val track: Track?

    val sampleCount: Int

    val offsets: LongArray

    val sizes: IntArray

    val maximumSize: Int

    val timestampsUs: LongArray

    val flags: IntArray

    val syncSampleIndices: IntArray

    val durationUs: Long

    val hasOnlySyncSamples: Boolean

    init {
        Preconditions.checkArgument(sizes.size == timestampsUs.size)
        Preconditions.checkArgument(offsets.size == timestampsUs.size)
        Preconditions.checkArgument(flags.size == timestampsUs.size)

        this.track = track
        this.offsets = offsets
        this.sizes = sizes
        this.maximumSize = maximumSize
        this.timestampsUs = timestampsUs
        this.flags = flags
        this.syncSampleIndices = syncSampleIndices
        this.hasOnlySyncSamples = hasOnlySyncSamples
        this.durationUs = durationUs
        this.sampleCount = sampleCount
        if (flags.size > 0) {
            flags[flags.size - 1] = flags[flags.size - 1] or C.BUFFER_FLAG_LAST_SAMPLE
        }
    }

    fun hasSampleTableData(): Boolean {
        return timestampsUs.size > 0
    }

    fun getIndexOfEarlierOrEqualSynchronizationSample(timeUs: Long): Int {
        if (!hasSampleTableData()) {
            return C.INDEX_UNSET
        }
        if (hasOnlySyncSamples) {
            return binarySearchFloor(
                timestampsUs, timeUs,  true,  false
            )
        }

        var low = 0
        var high = syncSampleIndices.size - 1
        var index = C.INDEX_UNSET

        while (low <= high) {
            val mid = low + ((high - low) / 2)
            val currentTimestamp = timestampsUs[syncSampleIndices[mid]]

            if (currentTimestamp <= timeUs) {
                index = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        if (index == C.INDEX_UNSET) {
            return C.INDEX_UNSET
        }

        val targetTimestamp = timestampsUs[syncSampleIndices[index]]
        // Only scan backwards if the found sample is an EXACT match for the search time.
        if (targetTimestamp == timeUs) {
            while (index > 0 && timestampsUs[syncSampleIndices[index - 1]] == targetTimestamp) {
                index--
            }
        }

        return syncSampleIndices[index]
    }

    fun getIndexOfLaterOrEqualSynchronizationSample(timeUs: Long): Int {
        if (!hasSampleTableData()) {
            return C.INDEX_UNSET
        }
        if (hasOnlySyncSamples) {
            return binarySearchCeil(
                timestampsUs, timeUs,  true,  false
            )
        }

        var low = 0
        var high = syncSampleIndices.size - 1
        var index = C.INDEX_UNSET

        while (low <= high) {
            val mid = low + ((high - low) / 2)
            val currentTimestamp = timestampsUs[syncSampleIndices[mid]]

            if (currentTimestamp >= timeUs) {
                index = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }

        if (index == C.INDEX_UNSET) {
            return C.INDEX_UNSET
        }

        val targetTimestamp = timestampsUs[syncSampleIndices[index]]
        // Only scan forwards if the found sample is an EXACT match for the search time.
        if (targetTimestamp == timeUs) {
            while (index < syncSampleIndices.size - 1
                && timestampsUs[syncSampleIndices[index + 1]] == targetTimestamp
            ) {
                index++
            }
        }

        return syncSampleIndices[index]
    }
}
