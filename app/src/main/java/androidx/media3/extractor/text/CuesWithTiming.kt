package androidx.media3.extractor.text

import androidx.media3.common.C
import androidx.media3.common.text.Cue

class CuesWithTiming(
    cues: List<Cue>,
    val startTimeUs: Long,
    val durationUs: Long
) {
    
    val cues: List<Cue>

    val endTimeUs: Long

    init {
        this.cues = cues.toList()
        this.endTimeUs =
            if (startTimeUs == C.TIME_UNSET || durationUs == C.TIME_UNSET)
                C.TIME_UNSET
            else
                startTimeUs + durationUs
    }
}
