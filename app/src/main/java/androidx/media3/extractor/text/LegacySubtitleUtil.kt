package androidx.media3.extractor.text

import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer

object LegacySubtitleUtil {
    
    fun toCuesWithTiming(
        subtitle: Subtitle, outputOptions: SubtitleParser.OutputOptions, output: ((CuesWithTiming)-> Unit)
    ) {
        val startIndex = getStartIndex(subtitle, outputOptions.startTimeUs)
        var startedInMiddleOfCue = false
        if (outputOptions.startTimeUs != C.TIME_UNSET && startIndex < subtitle.eventTimeCount) {
            val cuesAtStartTime: MutableList<Cue> = subtitle.getCues(outputOptions.startTimeUs)!!.toMutableList()
            val firstEventTimeUs = subtitle.getEventTime(startIndex)
            if (!cuesAtStartTime.isEmpty() && outputOptions.startTimeUs < firstEventTimeUs) {
                output.invoke(
                    CuesWithTiming(
                        cuesAtStartTime,
                        outputOptions.startTimeUs,
                        firstEventTimeUs - outputOptions.startTimeUs
                    )
                )
                startedInMiddleOfCue = true
            }
        }
        for (i in startIndex..<subtitle.eventTimeCount) {
            outputSubtitleEvent(subtitle, i, output)
        }
        if (outputOptions.outputAllCues) {
            val endIndex = if (startedInMiddleOfCue) startIndex - 1 else startIndex
            for (i in 0..<endIndex) {
                outputSubtitleEvent(subtitle, i, output)
            }
            if (startedInMiddleOfCue) {
                output.invoke(
                    CuesWithTiming(
                        subtitle.getCues(outputOptions.startTimeUs)?.toMutableList()!!,
                        subtitle.getEventTime(endIndex),
                        outputOptions.startTimeUs - subtitle.getEventTime(endIndex)
                    )
                )
            }
        }
    }

    private fun getStartIndex(subtitle: Subtitle, startTimeUs: Long): Int {
        if (startTimeUs == C.TIME_UNSET) {
            return 0
        }
        var nextEventTimeIndex = subtitle.getNextEventTimeIndex(startTimeUs)
        if (nextEventTimeIndex == C.INDEX_UNSET) {
            nextEventTimeIndex = subtitle.eventTimeCount
        }
        if (nextEventTimeIndex > 0 && subtitle.getEventTime(nextEventTimeIndex - 1) == startTimeUs) {
            nextEventTimeIndex--
        }
        return nextEventTimeIndex
    }

    private fun outputSubtitleEvent(
        subtitle: Subtitle, eventIndex: Int, output: ((CuesWithTiming)-> Unit)
    ) {
        val startTimeUs = subtitle.getEventTime(eventIndex)
        val cuesForThisStartTime: MutableList<Cue> = subtitle.getCues(startTimeUs)?.toMutableList()!!
        if (cuesForThisStartTime.isEmpty()) {
            // An empty cue list has already been implicitly encoded in the duration of the previous
            // sample.
            return
        } else check(eventIndex != subtitle.eventTimeCount - 1)
        // It's safe to inspect element i+1, because we already exited the loop above if
        // i == getEventTimeCount() - 1.
        val durationUs = subtitle.getEventTime(eventIndex + 1) - subtitle.getEventTime(eventIndex)
        if (durationUs > 0) {
            output.invoke(CuesWithTiming(cuesForThisStartTime, startTimeUs, durationUs))
        }
    }
}
