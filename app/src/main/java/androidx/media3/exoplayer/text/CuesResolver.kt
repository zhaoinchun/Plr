package androidx.media3.exoplayer.text

import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming

internal interface CuesResolver {
    fun addCues(cues: CuesWithTiming, currentPositionUs: Long): Boolean
    fun getCuesAtTimeUs(timeUs: Long): List<Cue>
    fun discardCuesBeforeTimeUs(timeUs: Long)
    fun getPreviousCueChangeTimeUs(timeUs: Long): Long
    fun getNextCueChangeTimeUs(timeUs: Long): Long
    fun clear()
}
