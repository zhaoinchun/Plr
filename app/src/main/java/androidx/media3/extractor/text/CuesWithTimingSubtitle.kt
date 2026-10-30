package androidx.media3.extractor.text

import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Log.w
import androidx.media3.common.util.Util.binarySearchCeil
import androidx.media3.common.util.Util.binarySearchFloor
import com.google.common.base.Function
import com.google.common.base.Preconditions
import com.google.common.collect.Iterables
import com.google.common.collect.Ordering
import java.util.Arrays

internal class CuesWithTimingSubtitle(cuesWithTimingList: MutableList<CuesWithTiming>) : Subtitle {
    // eventCues and eventTimesUs are parallel collections. eventTimesUs is sorted in ascending
    // order, and eventCues[i] contains the the cues for the event at time eventTimesUs[i].
    // eventTimesUs may be longer than eventCues (with padding elements at the end).
    // eventCues.size() is the authoritative source for the number of events in this Subtitle.
    private val eventCues: List<List<Cue>?>
    private val eventTimesUs: LongArray

    init {
        if (cuesWithTimingList.size == 1) {
            val cuesWithTiming = Iterables.getOnlyElement<CuesWithTiming>(cuesWithTimingList)
            val startTimeUs: Long = normalizeUnsetStartTimeToZero(cuesWithTiming.startTimeUs)
            if (cuesWithTiming.durationUs == C.TIME_UNSET) {
                eventCues = listOf(cuesWithTiming.cues)
                eventTimesUs = longArrayOf(startTimeUs)
            } else {
                eventCues = listOf(
                    cuesWithTiming.cues,
                    listOf()
                )
                eventTimesUs = longArrayOf(startTimeUs, startTimeUs + cuesWithTiming.durationUs)
            }
        } else {

            eventTimesUs = LongArray(cuesWithTimingList.size * 2)
            // Ensure that any unused slots at the end of eventTimesUs remain 'sorted' so don't mess
            // with the binary search.
            Arrays.fill(eventTimesUs, Long.MAX_VALUE)
            val eventCues = ArrayList<List<Cue>?>()
            val sortedCuesWithTimingList = cuesWithTimingList.sortedBy {
                normalizeUnsetStartTimeToZero(it.startTimeUs)
            }
            var eventIndex = 0
            for (i in sortedCuesWithTimingList.indices) {
                val cuesWithTiming = sortedCuesWithTimingList[i]

                val startTimeUs: Long = normalizeUnsetStartTimeToZero(cuesWithTiming.startTimeUs)
                val endTimeUs = startTimeUs + cuesWithTiming.durationUs
                if (eventIndex == 0 || eventTimesUs[eventIndex - 1] < startTimeUs) {
                    eventTimesUs[eventIndex++] = startTimeUs
                    eventCues.add(cuesWithTiming.cues)
                } else if (eventTimesUs[eventIndex - 1] == startTimeUs
                    && eventCues.get(eventIndex - 1)!!.isEmpty()
                ) {
                    // The previous CuesWithTiming ends at the same time this one starts, so overwrite the
                    // empty cue list with the cues from this one.
                    eventCues.set(eventIndex - 1, cuesWithTiming.cues)
                } else {
                    w(TAG, "Truncating unsupported overlapping cues.")
                    // The previous CuesWithTiming ends after this one starts, so overwrite the empty cue list
                    // with the cues from this one.
                    eventTimesUs[eventIndex - 1] = startTimeUs
                    eventCues.set(eventIndex - 1, cuesWithTiming.cues)
                }
                if (cuesWithTiming.durationUs != C.TIME_UNSET) {
                    eventTimesUs[eventIndex++] = endTimeUs
                    eventCues.add(listOf())
                }
            }
            this.eventCues = eventCues.toList()
        }
    }

    override fun getNextEventTimeIndex(timeUs: Long): Int {
        val index =
            binarySearchCeil(
                eventTimesUs,  timeUs,  false,  false
            )
        return if (index < eventCues.size) index else C.INDEX_UNSET
    }

    override val eventTimeCount: Int
        get() = eventCues.size

    override fun getEventTime(index: Int): Long {
        Preconditions.checkArgument(index < eventCues.size)
        return eventTimesUs[index]
    }

    override fun getCues(timeUs: Long): List<Cue>? {
        val index =
            binarySearchFloor(
                eventTimesUs,  timeUs,  true,  false
            )
        return if (index == -1) listOf() else eventCues.get(index)
    }

    companion object {
        private const val TAG = "CuesWithTimingSubtitle"

        private val CUES_BY_START_TIME_ASCENDING: Ordering<CuesWithTiming?> =
            Ordering.natural<Comparable<*>?>().onResultOf<CuesWithTiming?>(
                Function { c: CuesWithTiming? -> normalizeUnsetStartTimeToZero(c!!.startTimeUs) })

        // SubtitleParser can return CuesWithTiming with startTimeUs == TIME_UNSET, indicating the
        // start time should be derived from the surrounding sample timestamp. In the context of the
        // Subtitle interface, this means starting at zero, so we can just always interpret TIME_UNSET
        // as zero here.
        private fun normalizeUnsetStartTimeToZero(startTime: Long): Long {
            return if (startTime == C.TIME_UNSET) 0 else startTime
        }
    }
}
