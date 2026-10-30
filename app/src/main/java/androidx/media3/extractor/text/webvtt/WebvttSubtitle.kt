package androidx.media3.extractor.text.webvtt

import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Util.binarySearchCeil
import androidx.media3.extractor.text.Subtitle
import com.google.common.base.Preconditions
import java.util.Arrays
import java.util.Collections
import kotlin.Comparator
import kotlin.Int
import kotlin.LongArray
import kotlin.compareTo

internal class WebvttSubtitle(cueInfos: MutableList<WebvttCueInfo>) : Subtitle {
    private val cueInfos: MutableList<WebvttCueInfo>
    private val cueTimesUs: LongArray
    private val sortedCueTimesUs: LongArray

    init {
        this.cueInfos =
            Collections.unmodifiableList<WebvttCueInfo?>(ArrayList<WebvttCueInfo?>(cueInfos))
        cueTimesUs = LongArray(2 * cueInfos.size)
        for (cueIndex in cueInfos.indices) {
            val cueInfo = cueInfos.get(cueIndex)
            val arrayIndex = cueIndex * 2
            cueTimesUs[arrayIndex] = cueInfo.startTimeUs
            cueTimesUs[arrayIndex + 1] = cueInfo.endTimeUs
        }
        sortedCueTimesUs = cueTimesUs.copyOf(cueTimesUs.size)
        Arrays.sort(sortedCueTimesUs)
    }

    override fun getNextEventTimeIndex(timeUs: Long): Int {
        val index = binarySearchCeil(sortedCueTimesUs, timeUs, false, false)
        return if (index < sortedCueTimesUs.size) index else C.INDEX_UNSET
    }

    override val eventTimeCount: Int
        get() = sortedCueTimesUs.size

    override fun getEventTime(index: Int): Long {
        Preconditions.checkArgument(index >= 0)
        Preconditions.checkArgument(index < sortedCueTimesUs.size)
        return sortedCueTimesUs[index]
    }

    override fun getCues(timeUs: Long): MutableList<Cue> {
        val currentCues: MutableList<Cue> = ArrayList<Cue>()
        val cuesWithUnsetLine: MutableList<WebvttCueInfo> = ArrayList<WebvttCueInfo>()
        for (i in cueInfos.indices) {
            if ((cueTimesUs[i * 2] <= timeUs) && (timeUs < cueTimesUs[i * 2 + 1])) {
                val cueInfo = cueInfos[i]
                if (cueInfo.cue!!.line == Cue.DIMEN_UNSET) {
                    cuesWithUnsetLine.add(cueInfo)
                } else {
                    currentCues.add(cueInfo.cue)
                }
            }
        }
        // Steps 4 - 10 of https://www.w3.org/TR/webvtt1/#cue-computed-line
        // (steps 1 - 3 are handled by WebvttCueParser#computeLine(float, int))
        Collections.sort<WebvttCueInfo?>(
            cuesWithUnsetLine,
            Comparator { c1: WebvttCueInfo?, c2: WebvttCueInfo? ->
                c1!!.startTimeUs.compareTo(c2!!.startTimeUs)
            })
        for (i in cuesWithUnsetLine.indices) {
            val cue = cuesWithUnsetLine[i]!!.cue
            currentCues.add(
                cue!!.buildUpon().setLine((-1 - i).toFloat(), Cue.LINE_TYPE_NUMBER).build()
            )
        }
        return currentCues
    }
}
