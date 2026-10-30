package androidx.media3.exoplayer.text

import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming
import com.google.common.base.Preconditions
import com.google.common.collect.Iterables

internal class ReplacingCuesResolver : CuesResolver {
    private val cuesWithTimingList: ArrayList<CuesWithTiming> = ArrayList<CuesWithTiming>()

    override fun addCues(cues: CuesWithTiming, currentPositionUs: Long): Boolean {
        Preconditions.checkArgument(cues.startTimeUs != C.TIME_UNSET)
        var cuesAreShownAtCurrentTime =
            cues.startTimeUs <= currentPositionUs
                    && (cues.endTimeUs == C.TIME_UNSET || currentPositionUs < cues.endTimeUs)
        for (i in cuesWithTimingList.indices.reversed()) {
            if (cues.startTimeUs >= cuesWithTimingList[i].startTimeUs) {
                cuesWithTimingList.add(i + 1, cues)
                return cuesAreShownAtCurrentTime
            } else if (cuesWithTimingList[i].startTimeUs <= currentPositionUs) {
                // There's a cue that starts after the new cues, but before the current time, meaning
                // the new cues will not be displayed at the current time.
                cuesAreShownAtCurrentTime = false
            }
        }
        cuesWithTimingList.add(0, cues)
        return cuesAreShownAtCurrentTime
    }

    override fun getCuesAtTimeUs(timeUs: Long): List<Cue> {
        val indexStartingAfterTimeUs = getIndexOfCuesStartingAfter(timeUs)
        if (indexStartingAfterTimeUs == 0) {
            // Either the first cue starts after timeUs, or the cues list is empty.
            return listOf<Cue>()
        }
        val cues = cuesWithTimingList.get(indexStartingAfterTimeUs - 1)
        return if (cues.endTimeUs == C.TIME_UNSET || timeUs < cues.endTimeUs)
            cues.cues
        else
            listOf<Cue>()
    }

    override fun discardCuesBeforeTimeUs(timeUs: Long) {
        var indexToDiscardTo = getIndexOfCuesStartingAfter(timeUs)
        if (indexToDiscardTo == 0) {
            // Either the first cue starts after timeUs, or the cues list is empty.
            return
        }
        val lastCueToDiscard = cuesWithTimingList[indexToDiscardTo - 1]
        if (lastCueToDiscard.endTimeUs == C.TIME_UNSET || lastCueToDiscard.endTimeUs >= timeUs) {
            indexToDiscardTo--
        }
        cuesWithTimingList.subList(0, indexToDiscardTo).clear()
    }

    override fun getPreviousCueChangeTimeUs(timeUs: Long): Long {
        if (cuesWithTimingList.isEmpty() || timeUs < cuesWithTimingList[0].startTimeUs) {
            return C.TIME_UNSET
        }

        for (i in 1..<cuesWithTimingList.size) {
            val nextCuesStartTimeUs = cuesWithTimingList[i].startTimeUs
            if (timeUs == nextCuesStartTimeUs) {
                return nextCuesStartTimeUs
            }
            if (timeUs < nextCuesStartTimeUs) {
                val cues = cuesWithTimingList[i - 1]
                return if (cues.endTimeUs != C.TIME_UNSET && cues.endTimeUs <= timeUs)
                    cues.endTimeUs
                else
                    cues.startTimeUs
            }
        }
        val lastCues = Iterables.getLast<CuesWithTiming>(cuesWithTimingList)
        return if (lastCues.endTimeUs == C.TIME_UNSET || timeUs < lastCues.endTimeUs)
            lastCues.startTimeUs
        else
            lastCues.endTimeUs
    }

    override fun getNextCueChangeTimeUs(timeUs: Long): Long {
        if (cuesWithTimingList.isEmpty()) {
            return C.TIME_END_OF_SOURCE
        }
        if (timeUs < cuesWithTimingList[0].startTimeUs) {
            return cuesWithTimingList[0].startTimeUs
        }

        for (i in 1..<cuesWithTimingList.size) {
            val cues = cuesWithTimingList[i]
            if (timeUs < cues.startTimeUs) {
                val previousCues = cuesWithTimingList.get(i - 1)
                return if (previousCues.endTimeUs != C.TIME_UNSET && previousCues.endTimeUs > timeUs && previousCues.endTimeUs < cues.startTimeUs)
                    previousCues.endTimeUs
                else
                    cues.startTimeUs
            }
        }
        val lastCues = Iterables.getLast<CuesWithTiming>(cuesWithTimingList)
        return if (lastCues.endTimeUs != C.TIME_UNSET && timeUs < lastCues.endTimeUs)
            lastCues.endTimeUs
        else
            C.TIME_END_OF_SOURCE
    }

    override fun clear() {
        cuesWithTimingList.clear()
    }

    private fun getIndexOfCuesStartingAfter(timeUs: Long): Int {
        for (i in cuesWithTimingList.indices) {
            if (timeUs < cuesWithTimingList[i].startTimeUs) {
                return i
            }
        }
        return cuesWithTimingList.size
    }
}
