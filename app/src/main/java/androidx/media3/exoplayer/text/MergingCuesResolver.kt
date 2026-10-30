package androidx.media3.exoplayer.text

import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming
import com.google.common.base.Function
import com.google.common.base.Preconditions
import com.google.common.collect.Ordering
import kotlin.math.max
import kotlin.math.min

internal class MergingCuesResolver : CuesResolver {
    private val cuesWithTimingList: MutableList<CuesWithTiming>

    init {
        cuesWithTimingList = ArrayList<CuesWithTiming>()
    }

    override fun addCues(cues: CuesWithTiming, currentPositionUs: Long): Boolean {
        Preconditions.checkArgument(cues.startTimeUs != C.TIME_UNSET)
        Preconditions.checkArgument(cues.durationUs != C.TIME_UNSET)
        val cuesAreShownAtCurrentTime =
            cues.startTimeUs <= currentPositionUs && currentPositionUs < cues.endTimeUs
        for (i in cuesWithTimingList.indices.reversed()) {
            if (cues.startTimeUs >= cuesWithTimingList[i].startTimeUs) {
                cuesWithTimingList.add(i + 1, cues)
                return cuesAreShownAtCurrentTime
            }
        }
        cuesWithTimingList.add(0, cues)
        return cuesAreShownAtCurrentTime
    }

    override fun getCuesAtTimeUs(timeUs: Long): List<Cue> {
        if (cuesWithTimingList.isEmpty() || timeUs < cuesWithTimingList[0].startTimeUs) {
            return listOf()
        }

        val visibleCues: MutableList<CuesWithTiming> = ArrayList<CuesWithTiming>()
        for (i in cuesWithTimingList.indices) {
            val cues = cuesWithTimingList[i]
            if (timeUs >= cues.startTimeUs && timeUs < cues.endTimeUs) {
                visibleCues.add(cues)
            }
            if (timeUs < cues.startTimeUs) {
                break
            }
        }
        val sortedResult =visibleCues.sortedWith { timing, timing1 ->
            if (timing.startTimeUs==timing1.startTimeUs){
                return@sortedWith timing.durationUs.compareTo(timing1.durationUs)
            }else{
                return@sortedWith timing.startTimeUs.compareTo(timing1.startTimeUs)
            }
        }
        val result = mutableListOf<Cue>()
        for (i in sortedResult.indices) {
            result.addAll(sortedResult[i]!!.cues)
        }
        return result
    }

    override fun discardCuesBeforeTimeUs(timeUs: Long) {
        var i = 0
        while (i < cuesWithTimingList.size) {
            val startTimeUs = cuesWithTimingList[i].startTimeUs
            if (timeUs > startTimeUs && timeUs > cuesWithTimingList[i].endTimeUs) {
                // In most cases only a single item will be removed in each invocation of this method, so
                // the inefficiency of removing items one-by-one inside a loop is mitigated.
                cuesWithTimingList.removeAt(i)
                i--
            } else if (timeUs < startTimeUs) {
                break
            }
            i++
        }
    }

    override fun getPreviousCueChangeTimeUs(timeUs: Long): Long {
        if (cuesWithTimingList.isEmpty() || timeUs < cuesWithTimingList[0].startTimeUs) {
            return C.TIME_UNSET
        }
        var result = cuesWithTimingList[0].startTimeUs
        for (i in cuesWithTimingList.indices) {
            val startTimeUs = cuesWithTimingList[i].startTimeUs
            val endTimeUs = cuesWithTimingList[i].endTimeUs
            if (endTimeUs <= timeUs) {
                result = max(result, endTimeUs)
            } else if (startTimeUs <= timeUs) {
                result = max(result, startTimeUs)
            } else {
                break
            }
        }
        return result
    }

    override fun getNextCueChangeTimeUs(timeUs: Long): Long {
        var result = C.TIME_UNSET
        for (i in cuesWithTimingList.indices) {
            val startTimeUs = cuesWithTimingList[i].startTimeUs
            val endTimeUs = cuesWithTimingList[i].endTimeUs
            if (timeUs < startTimeUs) {
                result = if (result == C.TIME_UNSET) startTimeUs else min(result, startTimeUs)
                break
            } else if (timeUs < endTimeUs) {
                result = if (result == C.TIME_UNSET) endTimeUs else min(result, endTimeUs)
            }
        }
        return if (result != C.TIME_UNSET) result else C.TIME_END_OF_SOURCE
    }

    override fun clear() {
        cuesWithTimingList.clear()
    }

    companion object {
        private val CUES_DISPLAY_PRIORITY_COMPARATOR: Ordering<CuesWithTiming?> =
            Ordering.natural<Long?>()
                .onResultOf<CuesWithTiming?>(Function { c: CuesWithTiming? -> c!!.startTimeUs })
                .compound<CuesWithTiming?>(
                    Ordering.natural<Long?>().reverse<Long?>()
                        .onResultOf<CuesWithTiming?>(Function { c: CuesWithTiming? -> c!!.durationUs })
                )
    }
}
